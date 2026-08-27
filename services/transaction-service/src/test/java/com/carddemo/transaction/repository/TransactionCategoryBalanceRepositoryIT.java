package com.carddemo.transaction.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.money.Money;
import com.carddemo.transaction.domain.TransactionCategoryBalance;
import com.carddemo.transaction.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Pins {@code ledger.transaction_category_balances} onto a real PostgreSQL engine: the only
 * composite key in this bounded context, and the create-versus-update decision asserted along both
 * arms separately.
 *
 * <p><b>Purpose.</b> Two properties are verified here and nowhere else in this package. The first is
 * that the three-part identifier really is a key of arity three, so that every component
 * participates and no single-component bug survives. The second is that a balance write is
 * observably an insert or observably an update, never an outcome a reader cannot attribute. The
 * package charter beside this file assigns exactly that pair to this class, and the rulings this
 * class obeys -- the closed file set, the connection-details bean, the mandatory profile, the
 * keyset-only vocabulary, the single-sourcing obligation and the report directories -- are owned
 * there and cited rather than restated.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; each member below carries its own. The inapplicability
 * is stated rather than left silent, because a reader has to be able to tell a declared
 * inapplicability from an oversight.
 *
 * <p><b>The key is a real composite, and three independent artifacts say so.</b>
 *
 * <p>Assumptions: the identifier's arity, its component order and its width are read off the
 * reference declarations rather than inferred from the column list. {@code app/cpy/CVTRA01Y.cpy} is
 * 13 lines; its line 2 states the record length as {@code RECLN = 50}; line 4 opens
 * {@code TRAN-CAT-BAL-RECORD}; and line 5 declares {@code TRAN-CAT-KEY} as a group item carrying no
 * picture of its own, spanning the three level-10 items that follow --
 * {@code TRANCAT-ACCT-ID PIC 9(11)} at line 6, {@code TRANCAT-TYPE-CD PIC X(02)} at line 7 and
 * {@code TRANCAT-CD PIC 9(04)} at line 8. Those three widths sum to 17 bytes; the eleven-byte
 * balance at line 9 and the twenty-two-byte {@code FILLER} at line 10 close the record at the
 * declared 50.
 *
 * <p>Assumptions: that the group is genuinely the access key, and not merely a convenient leading
 * cluster of three fields, is settled outside the copybook -- which matters because the two look
 * alike in a layout. {@code app/cbl/CBACT04C.cbl} selects the dataset across lines 28 to 32,
 * declaring it {@code ORGANIZATION IS INDEXED} at line 29 and stating
 * {@code RECORD KEY IS FD-TRAN-CAT-KEY} at line 31, and its file description puts
 * {@code 05 FD-TRAN-CAT-KEY.} first in the record at lines 61 to 63.
 * {@code app/cbl/CBTRN02C.cbl} states the same key independently at lines 57 to 61, naming it at
 * line 60. The component order is confirmed a third time, outside both programs, by
 * {@code app/jcl/PRTCATBL.jcl} line 52,
 * {@code SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)}, whose own field
 * declarations at lines 47 to 50 put the account at one-based byte 1 for 11, the type code at 12
 * for 2, the category code at 14 for 4 and the balance at 18 for 11 -- matching the copybook widths
 * exactly.
 *
 * <p>Alternatives Considered: the identifier is the entity's nested embedded type, and an
 * identifier-class mapping -- {@code IdClass}, the genuine alternative -- was declined upstream and
 * is not reintroduced here. That form requires the three key fields to be declared TWICE, once on
 * the entity and once on the identifier class, so the 17-byte layout of lines 6 to 8 would be
 * written out in two places that nothing keeps in step. The house precedent rules exactly that out:
 * {@code tests/README.md} lines 540 to 542 record that the reference unit tests resolve every record
 * layout through the single compiler copybook path and say never to duplicate a layout. This class
 * therefore CONSUMES the single declaration -- it constructs the nested identifier and never
 * restates any part of the 50-byte record.
 *
 * <p><b>The branch selector is the flag, not the file status.</b>
 *
 * <p>Assumptions: this is the one reading of {@code 2700-UPDATE-TCATBAL} that is easy to get wrong,
 * and getting it wrong would put an assertion here against a mechanism that does not exist. In
 * {@code app/cbl/CBTRN02C.cbl} that paragraph opens at line 467 and builds the whole key at lines
 * 469 to 471 -- note that the account component is moved from {@code XREF-ACCT-ID}, the
 * cross-reference lookup, rather than from the incoming record. It presets a flag to {@code 'N'} at
 * line 473, issues ONE {@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD} at line 474, and flips
 * that flag to {@code 'Y'} inside the {@code INVALID KEY} branch at line 478, closing the read at
 * line 479. Line 495 then branches on THE FLAG: {@code IF WS-CREATE-TRANCAT-REC = 'Y'} performs the
 * create paragraph at line 496, otherwise the update paragraph at line 498.
 *
 * <p>Assumptions: line 481 is an ERROR GATE and selects nothing. It accepts a file status of
 * {@code '00'} <em>or</em> {@code '23'} and moves zero to the result field at line 482, moving
 * twelve at line 484 for anything else; lines 486 to 493 then abend on that twelve, displaying at
 * line 489 and performing the abend paragraph at line 492. So a missing row is a NORMAL outcome of
 * that single read rather than an error, and the status code never chooses an arm. No assertion in
 * this class claims otherwise, and none should be added that does: a future reader tempted to
 * "simplify" these cases into a status-selects-the-arm test would be encoding a mechanism the
 * reference program does not have.
 *
 * <p><b>Both arms add the same amount; only the base differs.</b>
 *
 * <p>Assumptions: the create arm at lines 503 to 524 issues {@code INITIALIZE TRAN-CAT-BAL-RECORD}
 * at line 504, moves the three key components at lines 505 to 507, adds the feed amount at line 508
 * and {@code WRITE}s at line 510. The update arm at lines 526 to 542 performs the IDENTICAL
 * {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} at line 527 and then {@code REWRITE}s at line 528. The
 * two differ in nothing except whether the row pre-existed, and that {@code INITIALIZE} is the whole
 * reason the entity offers a zero-balance constructor: it sets the balance to zero before anything is
 * added, so zero is the base the first amount accumulates onto.
 *
 * <p>Assumptions: because both arms add the same amount, the balance VALUE alone cannot say which
 * arm ran -- so the row-count delta is the honest discriminator, and the fixture pair is chosen so
 * that the two values differ as well. A create of the feed amount yields that amount alone; an
 * update of a pre-existing balance by the same amount yields their sum. The pair is measured, not
 * minted: {@code src/test/resources/fixtures/zero_balance/tcatbal.txt} is present and ZERO BYTES
 * long, which is how that scenario reaches the create arm, while
 * {@code src/test/resources/fixtures/happy_path/tcatbal.txt} carries exactly one row; the two
 * scenarios' {@code dailytran.txt} files are byte-identical, so the category-balance fork is the
 * single variable between them.
 *
 * <p>Alternatives Considered: a conflict-resolving upsert -- a {@code MERGE}, or an
 * {@code INSERT ... ON CONFLICT DO UPDATE}, or a bulk statement standing in for one. Rejected, and
 * the consequence is concrete rather than stylistic: one statement collapses two separately
 * observable outcomes into one, so a regression that turned every create into an update, or every
 * update into a create, would leave every balance assertion green and go undetected. The migration
 * reaches the same conclusion from the schema side and records it at
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} lines 809 to
 * 822: the composite natural key is the sole constraint, it carries no default and no generated
 * value, and nothing conflict-shaped is provided, expressly so that a caller can still tell an
 * insert from an update. The parity oracle requires the same thing of the behaviour:
 * {@code tests/README.md} lines 581 to 583 state that the create-versus-update branch --
 * {@code 2700-A-CREATE} when the category row is new, {@code 2700-B-UPDATE} when it exists -- is
 * exercised both ways.
 *
 * <p>Refactoring Rationale: the two arms are driven through the repository's own write members,
 * {@code createRow} and {@code updateBalance}, and this paragraph used to explain why they could not
 * be. The reason it gave was correct about the mechanism then available: the inherited save method is
 * the shorter call a reader expects on a Spring Data test, but this entity's identifier is ASSIGNED
 * and it carries no version attribute, so the newness test reads a non-null identifier, concludes
 * the instance is not new, and routes EVERY call through the provider's merge operation -- one code
 * path for both outcomes, deciding insert against update inside the provider where an assertion
 * cannot see it. This class therefore reproduced the two statements locally, persisting on the create
 * arm and mutating a managed instance on the update arm. What was wrong with that arrangement was not
 * the statements it issued but WHOSE they were: the test held the discipline and production code did
 * not, so every assertion below could pass while the only caller that mattered still merged.
 *
 * <p>Assumptions: the discipline now lives on {@code TransactionCategoryBalanceWriter}, the fragment
 * the repository composes in, and this class drives it rather than duplicating it. The create arm
 * calls the member that persists, mirroring the {@code WRITE} at line 510; the update arm calls the
 * member that mutates in place, mirroring the {@code REWRITE} at line 528. Two cases at the foot of
 * this class then pin what the merge silently permitted and the members refuse -- a create onto a key
 * another writer already holds, and an update against a key no row carries.
 *
 * <p>Trade-offs: one write path is deliberately NOT routed through the repository. The fixture
 * builder persists through the entity manager directly, because it is also the instrument that proves
 * the ENGINE's own composite key refuses a duplicate, naming the constraint and its three columns.
 * The create member refuses that case earlier, by design, so routing the builder through it would
 * replace an assertion about the schema with an assertion about the application. Both facts are worth
 * holding, so both paths exist and the division is stated here rather than left to be inferred.
 *
 * <p><b>What this class does not reach into.</b>
 *
 * <p>Assumptions: {@code app/cbl/CBTRN02C.cbl} is the batch context's posting program and not this
 * module's. Its posting paragraph runs from line 424 to line 444 and performs three writes in
 * sequence -- this table at line 440, the account record at line 441 and the posted transaction at
 * line 442 -- which is context for why the category balance is written first and is NOT an invitation
 * to assert the other two here. They belong to other classes and other schemas. The two modules
 * agree through the physical {@code ledger} schema -- column names, types, nullability and decimal
 * scale -- and never through code: no batch type is imported, no Maven dependency joins the two, and
 * no cross-service domain import exists. No saga and no two-phase commit is introduced for these
 * tables.
 *
 * <p>Assumptions: no field of this record is renamed by this module, so no renaming assertion appears
 * below. The baseline's own spelling is used as it stands wherever it is cited --
 * {@code app/cbl/CBTRN02C.cbl} line 414 carries {@code ACCT-EXPIRAION-DATE} -- and the migration's
 * three documented spelling corrections belong to the account, card and authorization contexts. One
 * name here is easily confused with another record's: the trailing key component is
 * {@code TRANCAT-CD} at {@code app/cpy/CVTRA01Y.cpy} line 8, and it is NOT {@code TRAN-CAT-CD},
 * which is declared at {@code app/cpy/CVTRA05Y.cpy} line 7 inside the 350-byte {@code TRAN-RECORD}.
 * The two share a picture of {@code 9(04)} and nothing else.
 *
 * <p><b>Determinism, and the obligation that has no subject here.</b>
 *
 * <p>Assumptions: this table carries NO temporal column at all -- the migration declares four
 * columns and none of them is a timestamp -- so the package's prohibition on an ambient current-time
 * read has no subject in this file. It is satisfied because there is no clock to read, not because a
 * clock was injected: no fixed clock is declared and no time type is imported, since an import this
 * class never used would itself be a defect. The discipline the reference suite states for the parity
 * oracle at {@code tests/README.md} section 11, whose heading is at line 475, still applies in the
 * form that does have a subject here: fresh state per test at its lines 479 to 480, and
 * parallel-safety following from tests sharing nothing at its lines 490 to 498. Its timestamp
 * normalisation at lines 485 to 487 and its injected business dates at lines 488 to 489 govern values
 * this table does not hold.
 *
 * <p>Assumptions: the oracle's flat-to-indexed load is NOT ported. {@code tests/README.md} lines 481
 * to 484 describe that step as its analogue of the mainframe record-copy utility, needed because the
 * batch programs declare indexed organisation and cannot read a flat fixture directly. The
 * equivalent here is the PostgreSQL container together with Flyway, so nothing below loads an indexed
 * file or shells out to that helper, and the oracle's parallel-execution mechanics are likewise not
 * ported.
 *
 * <p><b>A real engine, and the substitute that cannot stand in for it.</b>
 *
 * <p>Assumptions: an in-memory substitute is excluded rather than merely unused, and H2 is the
 * specific alternative rejected. This suite's properties are engine behaviours it does not carry:
 * the deliberately non-unique secondary index this schema declares elsewhere, and the keyset access
 * paths that walk declared-width character keys in the engine's own collation. The module POM names
 * exactly that reason beside the Testcontainers artifacts it declares at test scope. Two consequences
 * bind this file: no in-memory or embedded database appears on this classpath, and if a write below
 * were ever replaced by an embedded equivalent the remedy is to restore the container, never to add
 * that engine to make the substitute work.
 *
 * <p>Alternatives Considered: the JPA test slice annotation, which is the shorter wiring for a
 * repository test. It is rejected twice over. Spring Boot 4.1.0 relocated that slice into a separate
 * per-technology test artifact this module does not declare, and declaring one would be a change to a
 * POM that this subtree does not own. More importantly for THIS class, that slice wraps each case in
 * a transaction that never commits -- which is precisely the arrangement that would let an assertion
 * be satisfied by the persistence context rather than by the engine, and would therefore blur the one
 * distinction this class exists to keep sharp: whether a write was an insert or an update. Every
 * write below instead flushes and clears, so each following read must reach PostgreSQL.
 *
 * <p>Alternatives Considered: naming this module's own application class as the test configuration,
 * which is the ordinary shape. Rejected on a measured failure: that class component-scans this
 * bounded context and so registers an explicit pool factory built from datasource PROPERTIES, while
 * the container contributes a connection-details BEAN, and the context then fails while creating the
 * pool. The nested configuration at the foot of this file registers no component scan, so the
 * framework's own auto-configuration builds the pool from that bean.
 *
 * <p>Alternatives Considered: extracting the container, the profile annotation and the row builders
 * into a shared abstract base class for this package's integration tests, or into an imported test
 * configuration. Rejected, and the charter beside this file records the same rejection as the reason
 * its file set is closed: a base class holding a container is shared mutable state, so rows one test
 * inserts become rows another test reads and a failure names the test that ran afterwards rather than
 * the one that caused it. That hazard is sharper here than anywhere else in the package, because a
 * row left behind by a sibling class is exactly what would turn this class's create arm into an
 * update. Each test owns its own schema state instead, which is what lets this class be run alone and
 * still mean something; the cost accepted is that the container field and the profile annotation are
 * declared once per test class.
 *
 * <p>Trade-offs: {@code @ActiveProfiles("test")} is load-bearing rather than conventional. The
 * profile document holds the only surviving mechanism that reaches the {@code ledger} schema inside a
 * container database that starts empty -- its Flyway and Hibernate default-schema settings together
 * with its create-schemas allowance and its schemas list -- because the JDBC URL is GENERATED by the
 * container, so no schema parameter can ride on it, and because a pooled search-path statement cannot
 * report failure: PostgreSQL accepts a search path naming a schema that does not exist. Omitting the
 * annotation does not degrade a run, it fails it on a missing schema before any assertion executes.
 * The compromise accepted is that a mandatory annotation is enforced by that failure rather than by
 * the compiler. The profile file is already authored and is cited rather than duplicated, because a
 * second copy of a profile key is a second place to change it.
 *
 * <p>Assumptions: Flyway needs two artifacts on this classpath and not one, and the second one's
 * absence would surface here rather than at build time. {@code flyway-core} and
 * {@code flyway-database-postgresql} are both managed at 13.0.0 by {@code services/pom.xml}. From
 * Flyway 10 onwards the database-specific support was moved out of core into companion artifacts, so
 * core on its own resolves and compiles perfectly and then fails as the application context starts,
 * with no PostgreSQL support registered. That the migration ran, and that an unqualified entity
 * resolves to {@code ledger}, are the two facts the charter assigns to this package's first
 * integration test and directs every other test not to re-assert, so they are not re-asserted here.
 * Two mechanisms still make their failure loud in this class: the profile sets Hibernate's schema
 * validation to compare the mapping against the migrated objects, so a missing table or a mismatched
 * type aborts context startup before any case runs; and the catalogue cases below read this table's
 * own key and columns, which no other test in the package asserts.
 *
 * <p>Assumptions: the balance is exact fixed point at two places and never an approximate type.
 * {@code app/cpy/CVTRA01Y.cpy} line 9 declares {@code TRAN-CAT-BAL PIC S9(09)V99}, which the
 * migration maps to {@code balance NUMERIC(11,2)} at its line 791 -- the picture's nine integral and
 * two fractional digits, and not one digit more. Arithmetic and scale come from
 * {@code com.carddemo.common.money.Money} and are not re-implemented here, and no binary
 * floating-point type appears in this file, in the values it draws from fixtures or in its
 * assertions: such a type returns plausible values that are wrong in the last place, invisible in any
 * one row while accumulating across a run.
 *
 * <p>Refactoring Rationale: the codecs that decode a zoned-decimal sign overpunch are consumed from
 * the shared kernel and are deliberately not reproduced in this file, even though every balance
 * constant below is the decoded form of a real fixture field. The single-sourcing rule at
 * {@code tests/README.md} lines 540 to 542 is what forecloses a local copy, and the drift it
 * forecloses would be silent: both copies would go on compiling and the stale one would go on passing
 * its own assertions. Decoded values therefore appear here as measured constants with their byte
 * positions recorded, while the decoding itself, the money contract, the page envelope, the timestamp
 * formatter and the validation-flag model all stay in {@code com.carddemo.common} where the whole
 * migration reads them.
  *
 * <h2>What this class asserts, and what it deliberately does not</h2>
 *
 * <p>Refactoring Rationale: this class asserts PERSISTENCE behaviour of the owned category-balance
 * table -- that an absent key inserts exactly one row, that a present key updates in place and leaves
 * the count unchanged, that the composite key resolves by value across a detach boundary, that the
 * amount keeps its sign and both decimal places, and that the declared constraints refuse what they
 * say they refuse. It does NOT assert the reference's create-against-update DISPATCH, and an earlier
 * revision of this class read as though it did: it constructed the row, performed the addition through
 * a local helper and persisted the result, which is the shape of the reference's two arms rather than a
 * call into anything that implements them. A test that performs the logic it is named after can only
 * ever agree with itself, so it would have passed unchanged had the production dispatch been absent,
 * inverted or never written -- which is exactly the state it was written in.
 *
 * <p>Assumptions: the dispatch itself belongs to the posting owner. The reference performs it in
 * {@code 2700-UPDATE-TCATBAL} of {@code app/cbl/CBTRN02C.cbl}, whose two arms at lines 500 and 526 are
 * reached from the flag the read at line 474 sets, and that program migrates to the batch deployable --
 * which reaches this table through a narrowly scoped cross-schema grant so that the three posting
 * writes stay one atomic commit. The arms are therefore asserted against the service that implements
 * them, in the module that owns it, where a failure names the dispatch. What remains here is the
 * physical contract that dispatch relies on, which is this module's to own because this module owns the
 * schema.
 *
 * <p>Trade-offs: the local addition helper is retained, because these cases still need a base and an
 * increment in order to observe that an in-place update is an update and not a second row. What
 * changed is the claim: the helper is fixture arithmetic used to produce two distinguishable stored
 * values, and no case presents it as evidence about how the reference chooses an arm.
 */
@Testcontainers
@SpringBootTest(
        classes = TransactionCategoryBalanceRepositoryIT.CategoryBalancePersistenceTestApplication
                .class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class TransactionCategoryBalanceRepositoryIT {

    // Assumptions: the engine is named by manifest DIGEST and never by a tag. The digest below
    //   resolves to PostgreSQL 17.10 on Alpine, the major line the deployed cluster targets, and the
    //   version is recorded in prose because a digest states nothing a reader can recognise.
    // WHY : Alternatives Considered: the readable tag postgres:17-alpine, or even an exact minor tag
    //       such as postgres:17.10-alpine. Both are rejected because both are MUTABLE -- the
    //       publisher moves a major tag onto each new minor release and may rebuild and republish a
    //       minor tag on a new base layer -- so either would let the engine change between two runs
    //       of an unchanged repository. That matters concretely here: the properties under test are
    //       engine behaviours, whether a composite primary key admits a duplicate and the collation a
    //       declared-width character key is ordered in, so a moving image could either make a failure
    //       unattributable or, worse, alter an ordering silently while every assertion stayed green.
    // WHY : Trade-offs: a digest is unreadable, so the engine version it denotes survives only in the
    //       comment above and has to be updated together with the value. That is the same trade the
    //       repository's own workflows already accept when they pin an action to a commit identifier
    //       with the readable tag written beside it, and it buys an attributable failure.
    // WHY : Assumptions: this is a multi-architecture MANIFEST digest rather than a local image
    //       identifier, so it resolves on every architecture the publisher builds for instead of
    //       binding this suite to the one machine that recorded it. It is deliberately the same
    //       digest this package's first integration test pins, so the two classes cannot disagree
    //       about the engine they prove a shared schema against.
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";
    /**
     * Class-path location of the harness that creates the schema's owning role in the container.
     *
     * <p>Assumptions: the role is a NOLOGIN role {@code data-migration/sql/V0__schemas_and_roles.sql} names and no container has, so it has
     * to exist before Flyway opens a connection and assumes it. A Testcontainers init script runs once
     * at container start, which is strictly earlier than Flyway's first connection; the alternative
     * this replaces -- creating the role from {@code spring.flyway.init-sqls} -- carried Flyway's
     * deprecated {@code initSql} setting and its per-connection removal notice.</p>
     */
    private static final String OWNER_ROLE_SCRIPT = "db/testharness/test-harness-owner-role.sql";

    // Assumptions: the type comes from org.testcontainers.postgresql and NOT from
    //   org.testcontainers.containers. Testcontainers 2.0.5 ships both and only the legacy one is
    //   deprecated, so the module package is where the type now lives.
    // WHY : Trade-offs: the replacement is not generic, so this declaration carries no wildcard and
    //       no self-type. Nothing here relied on that self-type, because the container is configured
    //       entirely through the connection-details annotation rather than by chained builder calls
    //       whose return type the parameter existed to refine.
    // WHY : Assumptions: the annotation below contributes a JdbcConnectionDetails bean that the
    //       auto-configuration reads in preference to any datasource property, which is why no URL,
    //       host, port, user name or password appears anywhere in this file. The container's port is
    //       assigned at run time, so a written connection string would either address nothing or
    //       address whichever database happens to be listening on the author's machine -- and the
    //       second failure mode is the worse of the two, because it passes.
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(OWNER_ROLE_SCRIPT);

    // Assumptions: the account component of the seeded key, read from one-based bytes 1 to 11 of the
    //   single record of src/test/resources/fixtures/happy_path/tcatbal.txt, which hold 00000000007.
    //   TRANCAT-ACCT-ID is declared PIC 9(11) at app/cpy/CVTRA01Y.cpy line 6 -- UNSIGNED, so those
    //   eleven bytes are plain digits and carry no sign overpunch. The value crosses to a Java long
    //   because the declared domain reaches 99999999999, which a 32-bit integer cannot hold.
    private static final Long SEED_ACCOUNT_ID = 7L;

    // Assumptions: the type component, one-based bytes 12 to 13 of that same record, which hold 01.
    //   It is carried as text because TRANCAT-TYPE-CD is declared PIC X(02) at line 7 of the
    //   copybook, so the two characters are a code and not a quantity. The value is a real reference
    //   code: app/data/ASCII/trantype.txt record 1 is `01Purchase`.
    private static final String SEED_TYPE_CD = "01";

    // Assumptions: the category component, one-based bytes 14 to 17, which hold 0001, supplied at its
    //   declared width with the leading zeros intact. The pair (01, 0001) is a real reference
    //   combination -- app/data/ASCII/trancatg.txt record 1 is `010001Regular Sales Draft` -- and it
    //   is the only pair the whole 50-record seed at app/data/ASCII/tcatbal.txt uses.
    // WHY : Assumptions: the width matters at the point of use, not merely in documentation. The
    //       column is CHAR(4) at V1__ledger.sql line 822, so a shorter value is stored blank padded
    //       and reads back padded, which would not compare equal to the value written; the failure
    //       then presents as a row that cannot be found rather than as anything about a width.
    private static final String SEED_CATEGORY_CD = "0001";

    // Assumptions: the balance the seeded row starts from, decoded from one-based bytes 18 to 28 of
    //   that record, which hold `0000001000{`. TRAN-CAT-BAL is declared PIC S9(09)V99 at
    //   app/cpy/CVTRA01Y.cpy line 9, so those eleven bytes are nine integral digits, two fractional
    //   digits and a sign carried as an overpunch over the low-order digit; the trailing left brace
    //   is that overpunch and denotes the digit zero with a positive sign, giving 100.00. The
    //   overpunch table itself is owned by src/test/resources/fixtures/README.md and is not
    //   reproduced here.
    private static final BigDecimal SEED_BALANCE = new BigDecimal("100.00");

    // Assumptions: the amount both arms add, decoded from one-based bytes 133 to 143 of the single
    //   record of src/test/resources/fixtures/happy_path/dailytran.txt, which hold `0000005047G`;
    //   the trailing G is a positive overpunch over the digit seven, giving 504.77. The zero_balance
    //   scenario's feed record is byte-identical, which is what makes the category-balance fork the
    //   only variable between the two scenarios and therefore what lets one amount drive both arms.
    private static final BigDecimal FEED_AMOUNT = new BigDecimal("504.77");

    // Assumptions: the balance the UPDATE arm must produce, being SEED_BALANCE plus FEED_AMOUNT. It
    //   is written out as a literal rather than computed in this declaration so that the expected
    //   value is stated independently of the arithmetic under test; a test that computed its own
    //   expectation with the same call the code under test uses would agree with itself whatever that
    //   call did.
    // WHY : Assumptions: this value differs from FEED_AMOUNT, and that difference is deliberate. If a
    //       create of the amount and an update by the amount could yield the same number, the balance
    //       assertions would prove nothing about which arm executed; the seeded base is non-zero
    //       precisely so the two outcomes are distinguishable by value as well as by row count.
    private static final BigDecimal ACCUMULATED_BALANCE = new BigDecimal("604.77");

    // Assumptions: a second account, differing from the seeded key in the LEADING component only. It
    //   is a real seed account: app/data/ASCII/tcatbal.txt carries accounts 1 through 50, so this
    //   value identifies nothing invented.
    private static final Long OTHER_ACCOUNT_ID = 8L;

    // Assumptions: a second type code, differing in the MIDDLE component only, and a real reference
    //   code -- app/data/ASCII/trantype.txt record 2 is `02Payment`.
    private static final String OTHER_TYPE_CD = "02";

    // Assumptions: a second category code, differing in the TRAILING component only, and a real
    //   reference combination -- app/data/ASCII/trancatg.txt record 2 is
    //   `010002Regular Cash Advance`, so the pair (01, 0002) exists in the reference data exactly as
    //   the pair (02, 0001) does at record 6, `020001Cash payment`.
    private static final String OTHER_CATEGORY_CD = "0002";

    // Assumptions: the largest account the declared picture admits. PIC 9(11) at
    //   app/cpy/CVTRA01Y.cpy line 6 reaches 99999999999, which exceeds the 2147483647 a 32-bit
    //   integer holds, so this value is what proves the column is genuinely the wider integer type
    //   the migration declares at V1__ledger.sql line 810 rather than a narrower one that happened to
    //   fit every other value in this file.
    private static final Long MAX_DOMAIN_ACCOUNT_ID = 99_999_999_999L;

    // Assumptions: a negative balance, decoded from one-based bytes 133 to 143 of record 2 of
    //   app/data/ASCII/dailytran.txt, which hold `0000009190` closed by a right brace whose overpunch
    //   makes the value negative, giving -919.00. A negative case is not optional: the field is
    //   signed at app/cpy/CVTRA01Y.cpy line 9, and a sign carried as an overpunch is exactly the
    //   value a mis-declared column keeps the magnitude of and loses the sign of.
    private static final BigDecimal NEGATIVE_BALANCE = new BigDecimal("-919.00");

    // Assumptions: the negative amount added to that negative balance, being FEED_AMOUNT negated, and
    //   the expected sum -1423.77 stated as a literal for the same reason ACCUMULATED_BALANCE is.
    //   Adding a negative amount to a negative base is the case where a lost sign would otherwise
    //   still produce a plausible magnitude.
    private static final BigDecimal NEGATIVE_AMOUNT = new BigDecimal("-504.77");

    // Assumptions: the sum of the two negative values above.
    private static final BigDecimal NEGATIVE_ACCUMULATED = new BigDecimal("-1423.77");

    // Assumptions: a zero amount at the money contract's own scale rather than BigDecimal.ZERO, whose
    //   scale is nought. The reference create arm reaches zero through INITIALIZE at
    //   app/cbl/CBTRN02C.cbl line 504, and the entity reaches it through the shared money type's own
    //   zero, so the value compared against here is drawn from that same type rather than written as a
    //   literal that could disagree with it about scale.
    private static final BigDecimal ZERO_AMOUNT = Money.ZERO.amount();

    // Assumptions: the number of columns the migration declares on this table, being the four
    //   elementary fields of app/cpy/CVTRA01Y.cpy lines 6 to 9. The twenty-two-byte FILLER at line 10
    //   is dropped rather than mapped, which is why this is four and not five.
    private static final int MAPPED_COLUMN_COUNT = 4;

    // Assumptions: the repository under test. Only two access shapes exist on it -- the inherited
    //   keyed read and one declared account-scoped ordered read -- because the two reference programs
    //   that reach this dataset open it under different access modes: RANDOM at
    //   app/cbl/CBTRN02C.cbl line 59 and SEQUENTIAL at app/cbl/CBACT04C.cbl line 30.
    @Autowired
    private TransactionCategoryBalanceRepository balances;

    // Assumptions: the physical contract is asserted through the engine's own catalogue rather than
    //   by inspecting configuration, because a configured key and an effective one are different
    //   facts and only the second one binds a query. This collaborator also supplies the explicit
    //   persist, flush and clear the two arms below depend on.
    @Autowired
    private EntityManager entityManager;

    // Assumptions: a flush is only expressible inside a transaction, so the write helpers need one of
    //   their own. Annotating the class transactional instead was rejected: it would wrap every case
    //   in a scope that never commits, which is the arrangement that lets an assertion be satisfied
    //   by the persistence context rather than by the engine -- and here it would specifically hide
    //   whether a write was an insert or an update, which is the distinction this class exists to
    //   keep visible.
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * Empties the table before each case so that no case can pass on another case's rows.
     *
     * <p>Assumptions: this matters more in this class than in any of its siblings. Every assertion
     * about the create arm depends on the row genuinely NOT existing, so a row left behind by an
     * earlier case would turn that arm into an update and the case would then fail while naming the
     * wrong cause. Deleting rows rather than rebuilding the schema keeps the migration applied once
     * per container, which is what keeps the catalogue cases below statements about the migration
     * rather than about this method. The charter beside this file records the isolation discipline
     * this implements, and the reference suite states it for the parity oracle at section 11 of
     * {@code tests/README.md}, whose heading is at line 475.</p>
     */
    @BeforeEach
    void clearTable() {
        this.balances.deleteAll();
    }

    /**
     * Confirms the composite primary key exists under its migrated name over all three components in
     * key order.
     *
     * <p>This case pins the record key itself rather than a paragraph of procedural logic. Its
     * baseline authority is {@code app/cbl/CBACT04C.cbl} line 31,
     * {@code RECORD KEY IS FD-TRAN-CAT-KEY}, inside the {@code SELECT TCATBAL-FILE} block that spans
     * lines 28 to 32, restated independently by {@code app/cbl/CBTRN02C.cbl} line 60 within its own
     * selection at lines 57 to 61. The group that name refers to is declared at
     * {@code app/cpy/CVTRA01Y.cpy} line 5 over the three components at lines 6, 7 and 8.
     *
     * <p>Assumptions: the constraint NAME is asserted and not merely the behaviour, because nothing in
     * the repository package declares a key: schema generation is switched off for this module, so
     * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} is the only
     * thing that creates one, at its lines 805 and 806, and a rename there would otherwise be
     * discovered as a duplicate row rather than as a failure.
     *
     * <p>Assumptions: the component ORDER is asserted because it is load-bearing and because every
     * column would still be present if it were wrong. {@code app/cbl/CBACT04C.cbl} walks this dataset
     * sequentially -- its loop opens at line 188 and fetches one row per iteration at line 190 -- and
     * takes a control break on a change of account at line 194, resetting its running total at line
     * 200 and fetching that account's own records at lines 201 to 205. That logic is correct only if
     * rows arrive account-major, which is exactly what a LEADING account component provides.
     * Reordering the three would leave the key intact and silently break the break.
     */
    @Test
    void theCompositePrimaryKeyCoversAllThreeComponentsInTheDeclaredKeyOrder() {
        List<String> keyColumns = this.nativeStringColumn(
                "select kcu.column_name"
                        + " from information_schema.table_constraints tc"
                        + " join information_schema.key_column_usage kcu"
                        + "   on kcu.constraint_schema = tc.constraint_schema"
                        + "  and kcu.constraint_name = tc.constraint_name"
                        + " where tc.constraint_schema = 'ledger'"
                        + "   and tc.table_name = 'transaction_category_balances'"
                        + "   and tc.constraint_type = 'PRIMARY KEY'"
                        + "   and tc.constraint_name = 'pk_transaction_category_balances'"
                        + " order by kcu.ordinal_position");

        assertThat(keyColumns).containsExactly("account_id", "type_cd", "category_cd");
    }

    /**
     * Confirms the four mapped columns carry the declared types and widths the migration gives them.
     *
     * <p>This case pins the record layout rather than a paragraph. Its baseline authority is
     * {@code app/cpy/CVTRA01Y.cpy} lines 4 to 10, whose line 2 states the record length as
     * {@code RECLN = 50}: the three key components at lines 6, 7 and 8 measure 11, 2 and 4 bytes, the
     * balance at line 9 measures 11, and the {@code FILLER} at line 10 pads bytes 29 to 50 out to the
     * declared 50. Those widths are corroborated outside the copybook by
     * {@code app/jcl/PRTCATBL.jcl} lines 47 to 50, which declare the same four fields at the same
     * one-based positions and lengths.
     *
     * <p>Assumptions: four columns are expected and not five, because the {@code FILLER} is dropped
     * rather than mapped. The seed extracts show directly that those 22 bytes are padding and not
     * data: in {@code app/data/ASCII/tcatbal.txt} they are ASCII zero digits, while the sibling
     * feed's own trailing padding is spaces, so a column holding either would store a writer's
     * convention and nothing about a balance. Rebuilding a declared-length image is the work of the
     * shared codec package.
     *
     * <p>Assumptions: the two code components are fixed-width character columns and NOT numbers,
     * which is a divergence from what the pictures alone would suggest and is therefore worth
     * asserting rather than assuming. The type code follows its picture -- {@code PIC X(02)} is
     * already alphanumeric -- but the category code is declared {@code PIC 9(04)} and is still mapped
     * to a four-character column at {@code V1__ledger.sql} line 747, because it is a code rather than
     * a quantity and being part of the key makes that stronger: {@code 0001} and {@code 1} must not
     * resolve to two different keys. The migration is normative for every column name, type,
     * precision and scale in this schema; the picture is recorded here for provenance.
     *
     * <p>Assumptions: the balance's precision and scale are asserted together with its type, because
     * an exact numeric column of the wrong scale would still accept every value in this file while
     * rendering it with the wrong number of digits. Eleven and two are the nine integral and two
     * fractional digits of {@code PIC S9(09)V99} at line 9 of the copybook, mapped at
     * {@code V1__ledger.sql} line 791, and not one digit more.
     */
    @Test
    void theFourMappedColumnsCarryTheDeclaredTypesTheMigrationGivesThem() {
        // WHY : Assumptions: the type is rendered together with its width so that one assertion
        //       carries both facts. A fixed-width character column reports its width in the maximum
        //       length column while an exact numeric reports precision and scale instead, so the
        //       coalesce below selects whichever of the two the column actually populates; ordering
        //       by declared position additionally asserts that the four columns sit in the copybook's
        //       own order rather than merely all being present.
        List<String> columns = this.nativeStringColumn(
                "select column_name || ':' || data_type || ':'"
                        + " || coalesce(character_maximum_length::text,"
                        + "             numeric_precision || ',' || numeric_scale, '')"
                        + " from information_schema.columns"
                        + " where table_schema = 'ledger'"
                        + "   and table_name = 'transaction_category_balances'"
                        + " order by ordinal_position");

        assertThat(columns)
                .hasSize(MAPPED_COLUMN_COUNT)
                .containsExactly(
                        "account_id:bigint:64,0",
                        "type_cd:character:2",
                        "category_cd:character:4",
                        "balance:numeric:11,2");
    }

    /**
     * Confirms the keyed read finds the row by its whole composite key and genuinely re-reads it.
     *
     * <p>This pins the single keyed read of {@code app/cbl/CBTRN02C.cbl} line 474,
     * {@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD}, inside {@code 2700-UPDATE-TCATBAL} whose
     * label is at line 467 and whose key is built from three parts immediately before it at lines 469
     * to 471. The dataset is opened {@code ACCESS MODE IS RANDOM} for that read, at line 59 of the
     * same program.
     *
     * <p>Alternatives Considered: an existence probe followed by a read. Rejected on the shape of the
     * reference branch, which is a SINGLE keyed read whose own outcome decides the arm -- the flag is
     * preset at line 473 and flipped inside the {@code INVALID KEY} branch at line 478 of that one
     * statement. A probe-then-read pair would turn one round trip into two and would open a window in
     * which another writer could insert the very row the probe reported absent, which the single read
     * has no equivalent of.
     *
     * <p>Assumptions: the instance read back must not be the instance written. That is the one
     * assertion here that distinguishes a genuine re-read from a first-level-cache hit, because the
     * entity compares equal on its identity alone -- so an equality assertion would be satisfied by
     * the very object handed over, while an identity assertion cannot be.
     */
    @Test
    void theKeyedReadFindsTheRowByItsWholeCompositeKeyAndGenuinelyReReadsIt() {
        TransactionCategoryBalance written =
                new TransactionCategoryBalance(this.seededKey(), SEED_BALANCE);

        this.persistAndDetach(written);

        TransactionCategoryBalance read =
                this.balances.findById(this.seededKey()).orElseThrow();

        assertThat(read).isNotSameAs(written);
        assertThat(read.getId().getAccountId()).isEqualTo(SEED_ACCOUNT_ID);
        assertThat(read.getId().getTypeCd()).isEqualTo(SEED_TYPE_CD);
        assertThat(read.getId().getCategoryCd()).isEqualTo(SEED_CATEGORY_CD);
        assertThat(read.getBalance()).isEqualByComparingTo(SEED_BALANCE);
    }

    /**
     * Confirms varying the account component alone makes the keyed read report an absence.
     *
     * <p>This pins the {@code INVALID KEY} branch of {@code app/cbl/CBTRN02C.cbl} line 475, reached
     * from the read at line 474, whose only effect is to flip the create flag at line 478. That a
     * missing row is a normal outcome and not an error is settled at line 481, which accepts a file
     * status of {@code '00'} <em>or</em> {@code '23'} and only abends -- through line 492 -- on
     * anything else.
     *
     * <p>Assumptions: each of the three components is varied in its OWN case rather than together in
     * one, because an identity comparison that ignored exactly one field would still satisfy a case
     * that varied a different field, or all three at once. This case varies the LEADING component,
     * {@code TRANCAT-ACCT-ID PIC 9(11)} at {@code app/cpy/CVTRA01Y.cpy} line 6, and holds the other
     * two at their seeded values.
     */
    @Test
    void varyingTheAccountComponentAloneMakesTheKeyedReadReportAnAbsence() {
        this.persistAndDetach(new TransactionCategoryBalance(this.seededKey(), SEED_BALANCE));

        assertThat(this.balances.findById(
                this.newKey(OTHER_ACCOUNT_ID, SEED_TYPE_CD, SEED_CATEGORY_CD))).isEmpty();
    }

    /**
     * Confirms varying the type-code component alone makes the keyed read report an absence.
     *
     * <p>This pins the same {@code INVALID KEY} branch of {@code app/cbl/CBTRN02C.cbl} line 475 as
     * the case above, and for the same reason it is a separate case: this one varies the MIDDLE
     * component, {@code TRANCAT-TYPE-CD PIC X(02)} at {@code app/cpy/CVTRA01Y.cpy} line 7, which the
     * reference paragraph moves into the key from the incoming feed record at line 470.
     *
     * <p>Assumptions: the substituted value is supplied at the column's declared width of two
     * characters. A shorter value would be blank padded by the engine and would then miss for a reason
     * unrelated to the component being varied, which would leave this case passing while proving
     * nothing.
     */
    @Test
    void varyingTheTypeCodeComponentAloneMakesTheKeyedReadReportAnAbsence() {
        this.persistAndDetach(new TransactionCategoryBalance(this.seededKey(), SEED_BALANCE));

        assertThat(this.balances.findById(
                this.newKey(SEED_ACCOUNT_ID, OTHER_TYPE_CD, SEED_CATEGORY_CD))).isEmpty();
    }

    /**
     * Confirms varying the category-code component alone makes the keyed read report an absence.
     *
     * <p>This pins the same {@code INVALID KEY} branch of {@code app/cbl/CBTRN02C.cbl} line 475, for
     * the TRAILING component {@code TRANCAT-CD PIC 9(04)} at {@code app/cpy/CVTRA01Y.cpy} line 8,
     * which the reference paragraph moves into the key from the feed record at line 471.
     *
     * <p>Assumptions: the trailing component is the one a partial identity comparison is likeliest to
     * drop, because it is last in the declared order and because its two sibling components already
     * narrow a lookup to a handful of rows. Asserting it separately is what keeps that omission
     * detectable.
     */
    @Test
    void varyingTheCategoryCodeComponentAloneMakesTheKeyedReadReportAnAbsence() {
        this.persistAndDetach(new TransactionCategoryBalance(this.seededKey(), SEED_BALANCE));

        assertThat(this.balances.findById(
                this.newKey(SEED_ACCOUNT_ID, SEED_TYPE_CD, OTHER_CATEGORY_CD))).isEmpty();
    }

    /**
     * Confirms rows differing in only one component each coexist under the composite key.
     *
     * <p>This pins the key's ARITY, which {@code app/cpy/CVTRA01Y.cpy} line 5 fixes at three by
     * grouping exactly the items at lines 6, 7 and 8, and which the migration declares as
     * {@code PRIMARY KEY (account_id, type_cd, category_cd)} at
     * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} line 806.
     * No fourth component and no surrogate is admissible, and none is introduced.
     *
     * <p>Assumptions: coexistence is the converse of the three absence cases above and is asserted
     * separately from them, because the two failure modes are different. A key too NARROW rejects the
     * second of these four rows as a duplicate; a key too WIDE, or an identity comparison that
     * ignored a component, lets the absence cases miss. Only both directions together establish that
     * all three components participate and that exactly three do.
     *
     * <p>Assumptions: the three substituted values are real reference combinations rather than
     * arbitrary ones -- {@code app/data/ASCII/trancatg.txt} carries {@code 010001},
     * {@code 010002} and {@code 020001} as records 1, 2 and 6 -- so the fixture describes rows the
     * reference data could actually hold.
     */
    @Test
    void rowsDifferingInOnlyOneComponentEachCoexistUnderTheCompositeKey() {
        this.persistAndDetach(
                new TransactionCategoryBalance(this.seededKey(), SEED_BALANCE),
                new TransactionCategoryBalance(
                        this.newKey(OTHER_ACCOUNT_ID, SEED_TYPE_CD, SEED_CATEGORY_CD), SEED_BALANCE),
                new TransactionCategoryBalance(
                        this.newKey(SEED_ACCOUNT_ID, OTHER_TYPE_CD, SEED_CATEGORY_CD), SEED_BALANCE),
                new TransactionCategoryBalance(
                        this.newKey(SEED_ACCOUNT_ID, SEED_TYPE_CD, OTHER_CATEGORY_CD), SEED_BALANCE));

        assertThat(this.rowCount()).isEqualTo(4L);
        assertThat(this.balances.findById(this.seededKey())).isPresent();
        assertThat(this.balances.findById(
                this.newKey(OTHER_ACCOUNT_ID, SEED_TYPE_CD, SEED_CATEGORY_CD))).isPresent();
        assertThat(this.balances.findById(
                this.newKey(SEED_ACCOUNT_ID, OTHER_TYPE_CD, SEED_CATEGORY_CD))).isPresent();
        assertThat(this.balances.findById(
                this.newKey(SEED_ACCOUNT_ID, SEED_TYPE_CD, OTHER_CATEGORY_CD))).isPresent();
    }

    /**
     * Confirms a second row under an equal composite key is rejected.
     *
     * <p>This pins the {@code WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD} of
     * {@code app/cbl/CBTRN02C.cbl} line 510, inside {@code 2700-A-CREATE-TCATBAL-REC} whose label is
     * at line 503. That statement writes to an indexed dataset keyed on the group at
     * {@code app/cpy/CVTRA01Y.cpy} line 5, so a second record under one key has no representation
     * there; the paragraph's own status gate at line 512 accepts only {@code '00'} and abends
     * otherwise.
     *
     * <p>Assumptions: uniqueness is asserted as a REFUSAL rather than as a row count, because the
     * migration keeps it a refusal on purpose. Its note at
     * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} lines 809 to
     * 822 records that the composite natural key is the sole constraint and that nothing
     * conflict-shaped is provided, expressly so that a uniqueness violation -- and therefore the
     * create-versus-update decision -- stays visible to the service that has to report it. A design
     * that silently absorbed the second write is exactly what this case is here to catch.
     *
     * <p>Assumptions: the second write is issued as an explicit persist and NOT through the
     * repository's inherited save. On this entity the identifier is always assigned and there is no
     * version attribute, so the newness test concludes the instance is not new and the call becomes a
     * merge, which would load the existing row and update it -- and this case would then pass by
     * silently observing no refusal at all. Persisting states the insert the reference source issues.
     *
     * <p>Alternatives Considered: the repository's {@code createRow} member, which now exists and
     * refuses exactly this case. It is deliberately NOT used here, because the two assertions differ in
     * subject. That member refuses on its own guard, before any statement reaches the database, so a
     * case driving it would establish that the APPLICATION refuses -- which is what the case at the
     * foot of this class establishes. This one establishes that the SCHEMA refuses, by naming
     * {@code pk_transaction_category_balances} and its three columns in the provider's own message, so
     * a key silently reduced to fewer components fails here. Neither case can stand in for the other.
     *
     * <p>Alternatives Considered: asserting the framework's portable data-integrity exception, which
     * would keep this case independent of the persistence provider. It is rejected on two measured
     * grounds. That translation happens where a transaction COMMITS, and every write in this file
     * flushes explicitly beforehand so that the following read must reach the engine -- so the
     * provider's own exception escapes first, and reaching the portable one would mean giving up the
     * flush that makes the surrounding assertions meaningful. It is also the weaker assertion: the
     * portable type is raised just as readily by a not-null or a check violation, so it would not
     * establish that the COMPOSITE KEY is what refused the row. The provider type carries a message
     * naming the constraint and its three columns, which does establish exactly that.
     *
     * <p>Trade-offs: the cost accepted is a compile-time dependency on one provider exception type in
     * this file. It is bounded and already precedented -- the entity this class exercises binds two
     * provider annotations for its fixed-width columns -- and what it buys is an assertion that names
     * {@code pk_transaction_category_balances}, so a refusal by some other rule would fail here rather
     * than pass.
     *
     * <p>⚠️ Refactoring Rationale: the triple {@code (account_id, type_cd, category_cd)} was asserted
     * from that same exception MESSAGE, and is now asserted from the live CATALOGUE instead. The columns
     * only ever appeared in the message because the driver folded the server's {@code DETAIL} field into
     * it, and that detail also enumerates the offending row's VALUES -- so the assertion depended on a
     * driver setting that has since been turned off precisely because it published record data into
     * logs. Reading {@code pg_get_constraintdef} is the stronger source in any case: it states the
     * columns AND their order as the engine holds them, where the detail line stated only the order the
     * message happened to render, and it is the pattern the sibling repository suites already use.
     */
    @Test
    void aSecondRowUnderAnEqualCompositeKeyIsRejected() {
        this.persistAndDetach(new TransactionCategoryBalance(this.seededKey(), SEED_BALANCE));

        TransactionCategoryBalance duplicate =
                new TransactionCategoryBalance(this.seededKey(), FEED_AMOUNT);

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> this.persistAndDetach(duplicate))
                .withMessageContaining("pk_transaction_category_balances");

        Object declared = this.entityManager.createNativeQuery(
                        "select pg_get_constraintdef(oid) from pg_constraint"
                                + " where conname = 'pk_transaction_category_balances'")
                .getSingleResult();
        assertThat(String.valueOf(declared))
                .as("the key that refused the row must be the composite over all three components, in"
                        + " the order the migration declares, or a key silently reduced to fewer would"
                        + " pass the refusal assertion above")
                .contains("(account_id, type_cd, category_cd)");

        assertThat(this.rowCount()).isEqualTo(1L);
        assertThat(this.balances.findById(this.seededKey()).orElseThrow().getBalance())
                .isEqualByComparingTo(SEED_BALANCE);
    }

    /**
     * Confirms the embedded identity behaves as a lookup key across a flush-and-clear boundary.
     *
     * <p>This pins the key composition of {@code app/cbl/CBTRN02C.cbl} lines 469 to 471, where the
     * three components are moved into the key field before the read at line 474 -- the reference
     * program's own act of building a lookup value from three parts and expecting it to match a stored
     * record.
     *
     * <p>Assumptions: value equality over all three components is what the specification requires of
     * an embedded identifier, because the provider uses it to decide whether two loaded rows are the
     * same row. The boundary is what makes this case worth writing separately from the keyed read
     * above: the identity a test constructs and the identity the provider materialises from a row are
     * different instances, so an identifier relying on reference equality, or on a hash that
     * disagreed with its own equality, would work perfectly until a row was read back and then fail
     * to match a key built from the same three values.
     *
     * <p>Assumptions: the map is keyed by identities built BEFORE the write and looked up with
     * identities returned AFTER the clear, which is the only arrangement that exercises both halves
     * of the contract at once -- a hash bucket found by the returned identity, and an equality
     * comparison confirmed within it.
     */
    @Test
    void theEmbeddedIdentityBehavesAsALookupKeyAcrossAFlushAndClearBoundary() {
        Map<TransactionCategoryBalanceId, BigDecimal> expected = new HashMap<>();
        expected.put(this.seededKey(), SEED_BALANCE);
        expected.put(this.newKey(SEED_ACCOUNT_ID, SEED_TYPE_CD, OTHER_CATEGORY_CD), FEED_AMOUNT);

        this.persistAndDetach(
                new TransactionCategoryBalance(this.seededKey(), SEED_BALANCE),
                new TransactionCategoryBalance(
                        this.newKey(SEED_ACCOUNT_ID, SEED_TYPE_CD, OTHER_CATEGORY_CD), FEED_AMOUNT));

        List<TransactionCategoryBalance> read = this.balances
                .findByIdAccountIdOrderByIdTypeCdAscIdCategoryCdAsc(SEED_ACCOUNT_ID);

        assertThat(read).hasSize(expected.size());
        for (TransactionCategoryBalance row : read) {
            assertThat(expected).containsKey(row.getId());
            assertThat(row.getBalance()).isEqualByComparingTo(expected.get(row.getId()));
        }
    }

    /**
     * Confirms the two fixed-width code components and the wide account identifier round-trip intact.
     *
     * <p>This pins the three key components declared at {@code app/cpy/CVTRA01Y.cpy} lines 6, 7 and 8
     * against the columns the migration gives them at
     * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} lines 735,
     * 741 and 747. It asserts no procedural paragraph, because a round trip is a property of the
     * declaration rather than of any statement.
     *
     * <p>Assumptions: the two code components are asserted at their exact declared LENGTHS, not merely
     * for equality, because a fixed-width column blank pads a short value and returns it padded. A
     * value written two characters wide and read back at some other width would be a contract
     * violation that an equality assertion alone could still report as a match, if both sides happened
     * to be padded the same way.
     *
     * <p>Assumptions: the category code's leading zeros are the point of asserting it at all. It is
     * declared {@code PIC 9(04)} yet mapped to a character column, so {@code 0001} must come back as
     * {@code 0001}; a numeric mapping would return the same value as {@code 1}, which is a different
     * key.
     *
     * <p>Assumptions: the account component is asserted at the TOP of its declared domain as well as
     * at its seeded value. {@code PIC 9(11)} admits 99999999999, which exceeds what a 32-bit integer
     * holds, so this is what proves the column is genuinely the wider integer type the migration
     * declares. It is also asserted to carry no sign artefact: the picture is UNSIGNED, so those
     * eleven bytes are plain digits in the reference file and there is no overpunch to decode -- only
     * the balance at line 9 carries one.
     */
    @Test
    void theCodeComponentsAndTheWideAccountIdentifierRoundTripAtTheirDeclaredWidths() {
        this.persistAndDetach(
                new TransactionCategoryBalance(this.seededKey(), SEED_BALANCE),
                new TransactionCategoryBalance(
                        this.newKey(MAX_DOMAIN_ACCOUNT_ID, SEED_TYPE_CD, SEED_CATEGORY_CD),
                        SEED_BALANCE));

        TransactionCategoryBalanceId seeded =
                this.balances.findById(this.seededKey()).orElseThrow().getId();

        assertThat(seeded.getTypeCd()).isEqualTo(SEED_TYPE_CD).hasSize(2);
        assertThat(seeded.getCategoryCd()).isEqualTo(SEED_CATEGORY_CD).hasSize(4);
        assertThat(seeded.getAccountId()).isEqualTo(SEED_ACCOUNT_ID).isPositive();

        TransactionCategoryBalanceId widest = this.balances
                .findById(this.newKey(MAX_DOMAIN_ACCOUNT_ID, SEED_TYPE_CD, SEED_CATEGORY_CD))
                .orElseThrow()
                .getId();

        assertThat(widest.getAccountId()).isEqualTo(MAX_DOMAIN_ACCOUNT_ID);
    }

    /**
     * Confirms the account-scoped read returns only that account's rows, in the declared key order.
     *
     * <p>This pins the grouped run of rows that {@code app/cbl/CBACT04C.cbl} consumes between two
     * control breaks. That program opens its walk at line 188, fetches one row per iteration at line
     * 190 through {@code 1000-TCATBALF-GET-NEXT}, and detects a change of account at line 194 with
     * {@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM}, resetting its running total at line 200,
     * fixing the account at line 201, fetching that account's own records at lines 202 to 205 and
     * closing the break block at line 206. The rows lying between two such detections are exactly one
     * account's group, and that group is what this query returns in one call.
     *
     * <p>Refactoring Rationale: in the reference program the ordering was stated NOWHERE. Rows arrived
     * account-major, and by type and category within an account, purely because the walk read an
     * indexed dataset opened {@code ACCESS MODE IS SEQUENTIAL} at line 30 of that program. Naming both
     * ordering components in the query makes the sequence a property of the call, so a caller
     * reproducing the grouped run does not depend on a storage engine returning rows in key order when
     * nothing obliges it to. Were the ordering omitted, the grouping would still be right and the
     * sequence within each group would be arbitrary -- which is the half a control break relies on.
     *
     * <p>Assumptions: the rows are written in an order DIFFERENT from the order asserted, so that the
     * query is what establishes the sequence rather than the insertion sequence happening to agree
     * with it. Were they written already sorted, this case would pass against a query with no ordering
     * at all.
     *
     * <p>Assumptions: a row for a second account is written alongside them and asserted absent from
     * the result, because a query that ignored its predicate entirely would otherwise return a
     * correctly ordered superset and still pass.
     */
    @Test
    void theAccountScopedReadReturnsOnlyThatAccountsRowsInTheDeclaredKeyOrder() {
        this.persistAndDetach(
                new TransactionCategoryBalance(
                        this.newKey(SEED_ACCOUNT_ID, OTHER_TYPE_CD, SEED_CATEGORY_CD), SEED_BALANCE),
                new TransactionCategoryBalance(
                        this.newKey(SEED_ACCOUNT_ID, SEED_TYPE_CD, OTHER_CATEGORY_CD), FEED_AMOUNT),
                new TransactionCategoryBalance(this.seededKey(), SEED_BALANCE),
                new TransactionCategoryBalance(
                        this.newKey(OTHER_ACCOUNT_ID, SEED_TYPE_CD, SEED_CATEGORY_CD), FEED_AMOUNT));

        List<TransactionCategoryBalance> group = this.balances
                .findByIdAccountIdOrderByIdTypeCdAscIdCategoryCdAsc(SEED_ACCOUNT_ID);

        assertThat(group)
                .extracting(row -> row.getId().getTypeCd() + row.getId().getCategoryCd())
                .containsExactly("010001", "010002", "020001");
        assertThat(group)
                .allSatisfy(row ->
                        assertThat(row.getId().getAccountId()).isEqualTo(SEED_ACCOUNT_ID));
    }

    /**
     * Confirms the account-scoped read reports an empty group for an account holding no balance.
     *
     * <p>This pins the same sequential walk of {@code app/cbl/CBACT04C.cbl} at lines 188 to 190, from
     * the other side: an account with no category-balance record simply contributes no rows between
     * two control breaks, so it never reaches the break at line 194 at all.
     *
     * <p>Assumptions: an empty group is a normal outcome and not an error, which the reference program
     * asserts for the keyed case at {@code app/cbl/CBTRN02C.cbl} line 481 by accepting a file status
     * of {@code '23'} beside {@code '00'}. The declared return is a list, so the empty case must be an
     * empty list rather than an absent value, and asserting it here is what keeps a future
     * implementation from reporting a miss as a failure.
     */
    @Test
    void theAccountScopedReadReportsAnEmptyGroupForAnAccountHoldingNoBalance() {
        this.persistAndDetach(new TransactionCategoryBalance(this.seededKey(), SEED_BALANCE));

        assertThat(this.balances
                .findByIdAccountIdOrderByIdTypeCdAscIdCategoryCdAsc(OTHER_ACCOUNT_ID))
                .isEmpty();
    }

    /**
     * Confirms the create arm yields the amount alone and adds exactly one row.
     *
     * <p>The stored value this asserts is the one {@code 2700-A-CREATE-TCATBAL-REC} of {@code app/cbl/CBTRN02C.cbl}, lines 503 to
     * 512: {@code INITIALIZE TRAN-CAT-BAL-RECORD} at line 504, the three key moves at lines 505 to
     * 507, {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} at line 508 and
     * {@code WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD} at line 510, whose status gate
     * opens at line 512. The arm is reached from the dispatcher's branch at line 495 -- flag
     * {@code 'Y'} -- performed at line 496, after the flag was preset {@code 'N'} at line 473 and
     * flipped at line 478 by the {@code INVALID KEY} branch of the read at line 474.
     *
     * <p>Assumptions: the {@code INITIALIZE} at line 504 is why the zero-balance constructor exists
     * and why it is used here rather than the two-argument one. That statement sets the balance to zero
     * BEFORE anything is added, so zero is the base the first amount accumulates onto, and constructing
     * the row already carrying the amount would skip the very step this case is pinning. The zero comes
     * from the shared money type rather than from a literal written here, so the scale is fixed by the
     * type that owns the money contract.
     *
     * <p>Assumptions: the row count is the honest discriminator, not the balance. Both reference arms
     * perform the identical addition -- line 508 on this arm and line 527 on the other -- so the value
     * alone cannot say which ran; only whether a row APPEARED can. The count is read before and after
     * and the delta asserted, so the evidence in a failure report names the arm.
     *
     * <p>Trade-offs: a total row count is used here even though the package charter excludes
     * total-count vocabulary. That exclusion governs PAGING -- it forbids discovering page availability
     * by counting rather than by a surplus probe row -- and this table has no browse screen, no page
     * envelope and no cursor at all. The count here is the insert-versus-update discriminator the
     * charter's own roster requires this class to assert along both arms separately, so the two
     * requirements do not meet. No query, method name or assertion in this file positions a read by
     * counting rows from the start of an ordered set.
     *
     * <p>Assumptions: the write goes through the repository's create member rather than through the
     * inherited save, for the reason recorded on the class: an assigned identifier and no version
     * attribute make that call a merge, which would decide insert against update inside the provider
     * and hide the arm this case is named after. The member persists, so the statement this case
     * observes is the insert the reference source issues.
     */
    @Test
    void insertingAnAbsentKeyStoresTheAmountAndAddsExactlyOneRow() {
        long before = this.rowCount();

        assertThat(before).isZero();
        assertThat(this.balances.findById(this.seededKey())).isEmpty();

        TransactionCategoryBalance created = new TransactionCategoryBalance(this.seededKey());

        assertThat(created.getBalance()).isEqualByComparingTo(ZERO_AMOUNT);

        created.setBalance(added(created.getBalance(), FEED_AMOUNT));
        this.createThroughWriteMember(created);

        assertThat(this.rowCount()).isEqualTo(before + 1L);

        BigDecimal stored = this.balances.findById(this.seededKey()).orElseThrow().getBalance();

        assertThat(stored).isEqualByComparingTo(FEED_AMOUNT);
        assertThat(stored.scale()).isEqualTo(Money.SCALE);
    }

    /**
     * Confirms the update arm yields the sum and leaves the row count unchanged.
     *
     * <p>The stored value this asserts is the one {@code 2700-B-UPDATE-TCATBAL-REC} of {@code app/cbl/CBTRN02C.cbl}, lines 526 to
     * 528: the IDENTICAL {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} at line 527 and
     * {@code REWRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD} at line 528, whose status gate
     * opens at line 530. The arm is reached from the dispatcher's branch at line 495 taking its
     * {@code ELSE} at line 498, which happens when the flag still holds the {@code 'N'} it was preset
     * to at line 473 -- that is, when the read at line 474 found the row and never entered its
     * {@code INVALID KEY} branch at line 475.
     *
     * <p>Assumptions: the base added onto is the value just READ, not a value the caller carried in.
     * Line 527 adds to the record the read at line 474 populated, so this case takes the balance from
     * the instance the repository returned and adds to that. Reconstructing the base from a constant
     * would still produce the same number here and would stop being a test of the arm.
     *
     * <p>Assumptions: the row count being UNCHANGED is the assertion that distinguishes this arm from
     * the other. The sum alone would be produced just as well by an implementation that deleted the row
     * and inserted a replacement, or by one that inserted a second row whose sibling this read happened
     * to miss; only the count settles it.
     *
     * <p>Assumptions: the write is a mutation of the MANAGED instance followed by a flush, which is
     * what makes the resulting statement an update in place -- the counterpart of the reference
     * {@code REWRITE}. The repository's inherited save is avoided for the reason recorded on the class,
     * and a fresh transient instance carrying the sum is avoided too: that would be an insert against
     * an existing key, which is the case two methods above and would fail here.
     */
    @Test
    void updatingAPresentKeyStoresTheSumAndLeavesTheRowCountUnchanged() {
        this.persistAndDetach(new TransactionCategoryBalance(this.seededKey(), SEED_BALANCE));

        long before = this.rowCount();

        assertThat(before).isEqualTo(1L);

        this.accumulateOntoManagedRow(this.seededKey(), FEED_AMOUNT);

        assertThat(this.rowCount()).isEqualTo(before);

        BigDecimal stored = this.balances.findById(this.seededKey()).orElseThrow().getBalance();

        assertThat(stored).isEqualByComparingTo(ACCUMULATED_BALANCE);
        assertThat(stored.scale()).isEqualTo(Money.SCALE);
    }

    /**
     * Confirms the two arms produce different balances and different row-count deltas from one amount.
     *
     * <p>This pins the dispatcher {@code 2700-UPDATE-TCATBAL} of {@code app/cbl/CBTRN02C.cbl}, lines
     * 467 to 501, as a whole: the key composed at lines 469 to 471, the flag preset at line 473, the
     * single read at line 474, the flip at line 478, and the branch at lines 495 to 499 that reaches
     * {@code 2700-A-CREATE-TCATBAL-REC} at line 503 or {@code 2700-B-UPDATE-TCATBAL-REC} at line 526.
     * Both arms then add the same amount, at lines 508 and 527 respectively.
     *
     * <p>Assumptions: one amount drives both arms in one case, because that is the arrangement in
     * which the outcomes are attributable. The reference fixtures are built the same way and this case
     * mirrors them: {@code src/test/resources/fixtures/zero_balance/tcatbal.txt} is present and zero
     * bytes long while {@code src/test/resources/fixtures/happy_path/tcatbal.txt} carries one row, and
     * the two scenarios' feed records are byte-identical, so the presence of the row is the single
     * variable. The scenario name is worth reading carefully: {@code zero_balance} means no
     * accumulated balance exists yet, expressed as an ABSENT row, and not a row whose balance is zero.
     *
     * <p>Assumptions: the two results differ -- the amount alone against the seeded base plus the
     * amount -- and the two row-count deltas differ too. Differing on both counts at once is what lets
     * a reader of the output tell which arm ran for which key: a regression turning every create into
     * an update would leave one key unwritten and the count short, and one turning every update into a
     * create would fail on the key that already exists.
     *
     * <p>Assumptions: the two keys differ in the TRAILING component only, so the pair is as close as
     * the composite key allows while still being two keys. A pair differing in the account would also
     * work and would additionally exercise the leading component, which the three absence cases above
     * already cover.
     */
    @Test
    void theTwoArmsProduceDifferentBalancesAndDifferentRowCountDeltasFromOneAmount() {
        TransactionCategoryBalanceId absentKey = this.seededKey();
        TransactionCategoryBalanceId presentKey =
                this.newKey(SEED_ACCOUNT_ID, SEED_TYPE_CD, OTHER_CATEGORY_CD);

        this.persistAndDetach(new TransactionCategoryBalance(presentKey, SEED_BALANCE));

        long before = this.rowCount();

        assertThat(this.balances.findById(absentKey)).isEmpty();
        assertThat(this.balances.findById(presentKey)).isPresent();

        TransactionCategoryBalance created = new TransactionCategoryBalance(absentKey);
        created.setBalance(added(created.getBalance(), FEED_AMOUNT));
        this.createThroughWriteMember(created);

        this.accumulateOntoManagedRow(presentKey, FEED_AMOUNT);

        assertThat(this.rowCount()).isEqualTo(before + 1L);

        BigDecimal fromCreate = this.balances.findById(absentKey).orElseThrow().getBalance();
        BigDecimal fromUpdate = this.balances.findById(presentKey).orElseThrow().getBalance();

        assertThat(fromCreate).isEqualByComparingTo(FEED_AMOUNT);
        assertThat(fromUpdate).isEqualByComparingTo(ACCUMULATED_BALANCE);
        assertThat(fromCreate).isNotEqualByComparingTo(fromUpdate);
    }

    /**
     * Confirms a create carrying a zero amount still adds a row, and that the row's balance is zero.
     *
     * <p>This pins the same create arm, {@code app/cbl/CBTRN02C.cbl} lines 503 to 510, in the case
     * where the amount added at line 508 is itself zero: {@code INITIALIZE} at line 504 has already
     * set the balance to zero, so the addition changes nothing and the {@code WRITE} at line 510 still
     * happens. The row is created because the read at line 474 missed and the flag was flipped at line
     * 478, not because the amount was non-zero.
     *
     * <p>Assumptions: a zero-valued balance is a real state of this record and not a degenerate one.
     * Every one of the 50 records of {@code app/data/ASCII/tcatbal.txt} carries a zero balance, written
     * as {@code 0000000000} closed by a left-brace overpunch that denotes the digit zero with a
     * positive sign -- so the reference data itself is entirely zero balances. A design that treated a
     * zero amount as nothing to do would leave that whole file unrepresentable.
     *
     * <p>Assumptions: the zero is asserted at the money contract's scale rather than merely as
     * numerically zero, because a value equal to zero but scaled differently would render with the
     * wrong number of digits, and the fixture evidence above is a two-decimal zero.
     */
    @Test
    void aCreateCarryingAZeroAmountStillAddsARowWhoseBalanceIsZero() {
        long before = this.rowCount();

        TransactionCategoryBalance created = new TransactionCategoryBalance(this.seededKey());
        created.setBalance(added(created.getBalance(), ZERO_AMOUNT));
        this.createThroughWriteMember(created);

        assertThat(this.rowCount()).isEqualTo(before + 1L);

        BigDecimal stored = this.balances.findById(this.seededKey()).orElseThrow().getBalance();

        assertThat(stored).isEqualByComparingTo(ZERO_AMOUNT);
        assertThat(stored.scale()).isEqualTo(Money.SCALE);
    }

    /**
     * Confirms an update by zero leaves the balance unchanged and still adds no row.
     *
     * <p>This pins the update arm, {@code app/cbl/CBTRN02C.cbl} lines 526 to 528, where the amount
     * added at line 527 is zero: the addition leaves the read value as it was and the
     * {@code REWRITE} at line 528 still rewrites the record in place. Which arm runs is decided at
     * line 495 by the flag, so a zero amount cannot move the decision -- the row exists, therefore the
     * read at line 474 succeeded, therefore the flag still holds the {@code 'N'} of line 473.
     *
     * <p>Assumptions: this case is the counterpart of the one above and exists because the two failure
     * modes differ. An implementation that skipped a zero-amount write entirely would pass a
     * balance-value assertion here by doing nothing at all, so the row count is asserted alongside the
     * value: it must stay as it was, which rules out an insert, while the value must stay as it was,
     * which is what a genuine rewrite of an unchanged value produces.
     */
    @Test
    void anUpdateByZeroLeavesTheBalanceUnchangedAndStillAddsNoRow() {
        this.persistAndDetach(new TransactionCategoryBalance(this.seededKey(), SEED_BALANCE));

        long before = this.rowCount();

        this.accumulateOntoManagedRow(this.seededKey(), ZERO_AMOUNT);

        assertThat(this.rowCount()).isEqualTo(before);

        BigDecimal stored = this.balances.findById(this.seededKey()).orElseThrow().getBalance();

        assertThat(stored).isEqualByComparingTo(SEED_BALANCE);
        assertThat(stored.scale()).isEqualTo(Money.SCALE);
    }

    /**
     * Confirms the balance keeps its sign and both decimal places through a write and an accumulation.
     *
     * <p>This pins the addition both arms perform -- {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} at
     * {@code app/cbl/CBTRN02C.cbl} line 508 on the create arm and line 527 on the update arm -- over a
     * signed field. {@code app/cpy/CVTRA01Y.cpy} line 9 declares {@code TRAN-CAT-BAL PIC S9(09)V99},
     * whose leading {@code S} is the whole reason a negative case exists, and the migration maps it to
     * an exact decimal column of precision eleven and scale two at
     * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} line 791.
     *
     * <p>Assumptions: exactness is the property under test and binary floating point is the specific
     * alternative excluded. A 64-bit binary floating-point column would return plausible values that
     * are wrong in the last place, and the error would be invisible in any one row while accumulating
     * across a run -- which is why no such type appears anywhere in this file, in the values it draws
     * from fixtures or in its assertions, and why the wire form of this value is a JSON string applied
     * by the shared money serialization rather than a JSON number.
     *
     * <p>Assumptions: the negative values are decoded from real seed records rather than invented. One
     * of them is the amount at one-based bytes 133 to 143 of record 2 of
     * {@code app/data/ASCII/dailytran.txt}, whose trailing right brace is a negative overpunch over
     * the low-order digit. A sign carried that way is exactly the value a mis-declared column keeps the
     * magnitude of and loses the sign of, so the assertion below would catch that as a positive 919.00
     * rather than as a missing row.
     *
     * <p>Assumptions: the scale is asserted AFTER the addition and not only after the initial write,
     * because an addition is where a scale most easily drifts. The arithmetic is the shared money
     * type's, so the invariant it maintains is the one asserted rather than a local convention.
     */
    @Test
    void theBalanceKeepsItsSignAndBothDecimalPlacesThroughAWriteAndAnAccumulation() {
        this.persistAndDetach(new TransactionCategoryBalance(this.seededKey(), NEGATIVE_BALANCE));

        BigDecimal afterWrite = this.balances.findById(this.seededKey()).orElseThrow().getBalance();

        assertThat(afterWrite).isEqualByComparingTo(NEGATIVE_BALANCE).isNegative();
        assertThat(afterWrite.scale()).isEqualTo(Money.SCALE);

        this.accumulateOntoManagedRow(this.seededKey(), NEGATIVE_AMOUNT);

        BigDecimal afterAdd = this.balances.findById(this.seededKey()).orElseThrow().getBalance();

        assertThat(afterAdd).isEqualByComparingTo(NEGATIVE_ACCUMULATED).isNegative();
        assertThat(afterAdd.scale()).isEqualTo(Money.SCALE);
    }

    /**
     * Confirms the create member refuses a key another writer already holds and changes nothing.
     *
     * <p>This pins the failure mode of the {@code WRITE FD-TRAN-CAT-BAL-RECORD} at
     * {@code app/cbl/CBTRN02C.cbl} line 510. That statement writes to an indexed dataset keyed on the
     * group at {@code app/cpy/CVTRA01Y.cpy} line 5, and its status gate at line 512 accepts only
     * {@code '00'}, so a write onto an existing key is refused and the paragraph abends rather than
     * replacing the record. Nothing in the reference create arm can update.
     *
     * <p>Refactoring Rationale: this case exists because the mechanism it now exercises replaced one
     * that silently did the opposite. The repository previously offered only the inherited save for this
     * arm, and on this entity -- assigned identifier, no version attribute -- that call is a merge: it
     * would have FOUND the row this case seeds and UPDATED it, discarding the seeded balance in favour
     * of the amount the second writer carried, and raised nothing at all. That is why the balance is
     * asserted afterwards and not merely the row count: a count of one is satisfied by an overwrite, and
     * the overwritten VALUE is the loss.
     *
     * <p>Assumptions: this is the concurrent create race stated as two sequential writers, which is the
     * only form in which it is deterministic. Two genuinely simultaneous transactions would race on the
     * key and one of them would be refused, but which one is not fixed, so an assertion over the
     * surviving balance could name either. Seeding the row first makes the second writer's outcome the
     * only variable while exercising exactly the code path a loser of that race reaches.
     *
     * @throws AssertionError if the second write is absorbed, if the row count changes, or if the
     *     seeded balance is replaced
     */
    @Test
    void theCreateMemberRefusesAKeyAnotherWriterAlreadyHoldsAndLeavesTheBalanceIntact() {
        this.persistAndDetach(new TransactionCategoryBalance(this.seededKey(), SEED_BALANCE));

        long before = this.rowCount();

        assertThat(before).isEqualTo(1L);

        TransactionCategoryBalance second =
                new TransactionCategoryBalance(this.seededKey(), FEED_AMOUNT);

        assertThatExceptionOfType(RecordConflictException.class)
                .isThrownBy(() -> this.createThroughWriteMember(second))
                .satisfies(refusal -> assertThat(refusal.kind())
                        .isEqualTo(RecordConflictException.Kind.STALE_VERSION));

        assertThat(this.rowCount()).isEqualTo(before);
        assertThat(this.balances.findById(this.seededKey()).orElseThrow().getBalance())
                .isEqualByComparingTo(SEED_BALANCE);
    }

    /**
     * Confirms the update member refuses a key no row carries and inserts nothing.
     *
     * <p>This pins the reachability of the {@code REWRITE FD-TRAN-CAT-BAL-RECORD} at
     * {@code app/cbl/CBTRN02C.cbl} line 528. That statement is reached only through the {@code ELSE} of
     * the branch at line 495, which requires the flag to still hold the {@code 'N'} preset at line 473
     * -- that is, it requires the read at line 474 to have found the record. A rewrite against a key no
     * record carries is not a path the reference program has, and it certainly cannot create one.
     *
     * <p>Refactoring Rationale: this case is the mirror of the one above and exists for the mirrored
     * reason. Under the inherited save the update arm would have found no row and INSERTED one, so a row
     * a competing writer had deleted would quietly reappear carrying the balance of the caller that
     * thought it was updating. Asserting that the table is still empty afterwards is what distinguishes
     * a refusal from that recreation.
     *
     * <p>Assumptions: the vanished-row race is likewise stated sequentially -- the row simply never
     * exists -- because the outcome of the member is identical whether the row was deleted a moment ago
     * or was never there. The member establishes absence by a keyed read, so both histories reach the
     * same statement, and only the sequential form makes the assertion deterministic.
     *
     * @throws AssertionError if the update is absorbed, or if any row is created by it
     */
    @Test
    void theUpdateMemberRefusesAKeyNoRowCarriesAndCreatesNothing() {
        assertThat(this.rowCount()).isZero();

        TransactionCategoryBalanceId absent = this.seededKey();

        assertThatExceptionOfType(RecordConflictException.class)
                .isThrownBy(() -> this.transactionTemplate.executeWithoutResult(
                        status -> this.balances.updateBalance(absent, FEED_AMOUNT)))
                .satisfies(refusal -> assertThat(refusal.kind())
                        .isEqualTo(RecordConflictException.Kind.STALE_VERSION));

        assertThat(this.rowCount()).isZero();
        assertThat(this.balances.findById(absent)).isEmpty();
    }

    /**
     * Confirms the update member rewrites the existing row in place rather than replacing it.
     *
     * <p>This pins the {@code REWRITE} at {@code app/cbl/CBTRN02C.cbl} line 528 as an update IN PLACE.
     * The distinction matters because the resulting balance alone does not establish it: a delete
     * followed by an insert would leave the same value behind. What separates them is the row's
     * identity, so this case reads the generated column that no reference field corresponds to -- there
     * is none on this table -- and instead asserts the two properties that are available: the row count
     * is unchanged, and the value replaces rather than accumulates onto itself.
     *
     * <p>Assumptions: the member is given an absolute balance rather than an increment, so this case
     * supplies a value that is NOT the sum of the existing one and anything, and asserts it lands
     * exactly. An increment-shaped member would make that assertion impossible to write, which is why
     * the fragment takes the computed value and leaves the addition to its caller.
     *
     * <p>Assumptions: the scale is asserted as well as the value, because the member routes through the
     * entity's mutator and therefore through the shared money contract. A member that assigned the
     * argument directly would store whatever scale a caller happened to supply, and this case supplies
     * four decimal places precisely so that the reduction to two is observable.
     *
     * @throws AssertionError if a row is added or removed, if the balance is not replaced exactly, or if
     *     the stored scale is not the money contract's
     */
    @Test
    void theUpdateMemberReplacesTheBalanceInPlaceAtTheMoneyContractsScale() {
        this.persistAndDetach(new TransactionCategoryBalance(this.seededKey(), SEED_BALANCE));

        long before = this.rowCount();

        this.transactionTemplate.executeWithoutResult(status -> {
            this.balances.updateBalance(this.seededKey(), new BigDecimal("604.7700"));
            this.entityManager.clear();
        });

        assertThat(this.rowCount()).isEqualTo(before);

        BigDecimal stored = this.balances.findById(this.seededKey()).orElseThrow().getBalance();

        assertThat(stored).isEqualByComparingTo(ACCUMULATED_BALANCE);
        assertThat(stored.scale()).isEqualTo(Money.SCALE);
    }

    /**
     * Confirms both write members refuse to run without a caller's transaction, and write nothing.
     *
     * <p>Assumptions: this asserts a load-bearing annotation rather than a convention. Both members
     * declare mandatory transaction participation on the fragment interface, which is what stops the
     * repository infrastructure from supplying its own default attribute -- taken from the read-only
     * class-level attribute of its base implementation -- to a method that writes. Without the
     * declaration a write reaching either member outside a transaction would run inside a read-only one
     * and be refused by the database as a rejected statement instead of by the boundary as a stated
     * precondition.
     *
     * <p>Assumptions: the refusal is asserted through the repository PROXY, by calling the members with
     * no surrounding transaction template, because that is the only arrangement in which the interceptor
     * participates at all. A direct call on the implementation class would bypass the proxy and prove
     * nothing about the annotation.
     *
     * <p>Assumptions: the reference analogue is the commit boundary the posting program owns rather
     * than anything this table declares. {@code app/cbl/CBTRN02C.cbl} performs three writes in sequence
     * at lines 440 to 442 inside one unit of work, so a member that opened a boundary of its own would
     * make a partial posting observable; refusing outright is the behaviour that keeps the boundary the
     * caller's.
     *
     * @throws AssertionError if either member runs without a transaction, or if the table is changed
     */
    @Test
    void bothWriteMembersRefuseToRunWithoutACallersTransaction() {
        TransactionCategoryBalance row =
                new TransactionCategoryBalance(this.seededKey(), SEED_BALANCE);

        assertThatExceptionOfType(IllegalTransactionStateException.class)
                .isThrownBy(() -> this.balances.createRow(row));

        assertThatExceptionOfType(IllegalTransactionStateException.class)
                .isThrownBy(() -> this.balances.updateBalance(this.seededKey(), FEED_AMOUNT));

        assertThat(this.rowCount()).isZero();
    }


    /**
     * Adds an amount to a balance exactly as both reference arms do, at the money contract's scale.
     *
     * <p>Assumptions: this single helper expresses the addition that
     * {@code app/cbl/CBTRN02C.cbl} performs identically at line 508 on the create arm and at line 527
     * on the update arm, so that the two arms in this file demonstrably use the SAME operation. That
     * identity is a property of the reference source, and stating it twice in two call sites would let
     * one of them drift while both went on compiling.
     *
     * <p>Refactoring Rationale: the arithmetic is delegated to {@link Money} rather than performed on
     * the decimal values directly. A direct addition would return whichever scale the two operands
     * happened to produce and would carry no domain check at all, whereas the shared type pins the
     * scale and the rounding mode for the whole migration. The single-sourcing rule at
     * {@code tests/README.md} lines 540 to 542 is what forecloses a local money helper: a second
     * implementation of an arithmetic contract is a second place for it to diverge, silently, with both
     * copies still passing their own assertions.
     *
     * @param base the balance being added onto, being zero on the create arm and the value just read
     *     on the update arm; must not be {@code null}
     * @param amount the feed amount to add, which may be zero or negative because the reference field
     *     is signed; must not be {@code null}
     * @return the sum as an exact decimal at the scale {@link Money} pins, never {@code null}
     * @throws NullPointerException if either argument is {@code null}, which {@link Money} refuses
     *     because a monetary field under this contract has no representation for an absent amount
     * @throws ArithmeticException if either argument falls outside the domain {@link Money} admits, or
     *     if their sum does, which that type reports rather than silently truncating
     */
    private static BigDecimal added(BigDecimal base, BigDecimal amount) {
        return Money.of(base).plus(Money.of(amount)).amount();
    }

    /**
     * Builds the identity of the seeded row, the one the happy-path fixture carries.
     *
     * <p>Assumptions: a fresh instance is returned on every call rather than a shared constant being
     * held in a field. The identity is used both as a lookup argument and as a map key across a
     * flush-and-clear boundary, and a single shared instance would let a lookup succeed by reference
     * where the contract requires it to succeed by value -- which is precisely what the map-key case
     * exists to rule out.
     *
     * @return the identity whose three components are the values at one-based bytes 1 to 11, 12 to 13
     *     and 14 to 17 of {@code src/test/resources/fixtures/happy_path/tcatbal.txt}, never
     *     {@code null}
     */
    private TransactionCategoryBalanceId seededKey() {
        return this.newKey(SEED_ACCOUNT_ID, SEED_TYPE_CD, SEED_CATEGORY_CD);
    }

    /**
     * Builds a composite identity from its three components, in the declared key order.
     *
     * <p>Assumptions: the parameter order below is the copybook's own order --
     * {@code TRANCAT-ACCT-ID} at {@code app/cpy/CVTRA01Y.cpy} line 6,
     * {@code TRANCAT-TYPE-CD} at line 7 and {@code TRANCAT-CD} at line 8 -- which is the order of the
     * group item at line 5 and therefore of the reference file's record key, and which the migration
     * matches at
     * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} line 806.
     * Two of the three components are strings, so a transposition would compile and would then miss
     * silently; keeping this construction in one place is what makes the order stated once.
     *
     * <p>Alternatives Considered: constructing each identity inline at its call site. Rejected because
     * the two code components are supplied at declared widths and in a fixed order, and every call site
     * would then repeat both facts; one constructor call, cited once, is the same discipline the
     * copybook include path imposes on the reference tests.
     *
     * @param accountId the leading component, an account identifier whose declared domain reaches
     *     eleven digits; must not be {@code null} on an identity that is to be persisted
     * @param typeCd the middle component, a transaction type code supplied at its declared width of
     *     two characters; must not be {@code null}
     * @param categoryCd the trailing component, a transaction category code supplied at its declared
     *     width of four characters with leading zeros intact; must not be {@code null}
     * @return the composite identity carrying those three components, never {@code null}
     */
    private TransactionCategoryBalanceId newKey(
            Long accountId, String typeCd, String categoryCd) {
        return new TransactionCategoryBalanceId(accountId, typeCd, categoryCd);
    }

    /**
     * Returns the number of rows the table currently holds.
     *
     * <p>Trade-offs: a total row count is read here, and the package charter excludes total-count
     * vocabulary. That exclusion governs PAGING: it forbids discovering whether a further page exists
     * by counting rows instead of by reading one surplus probe row. This table has no browse screen, no
     * page envelope and no cursor, so no read in this file is positioned by a count. What the count is
     * used for instead is the insert-versus-update discriminator the charter's own roster requires this
     * class to assert along both arms separately, which no other value can carry: both reference arms
     * add the same amount at lines 508 and 527 of {@code app/cbl/CBTRN02C.cbl}, so only whether a row
     * appeared distinguishes them.
     *
     * @return the current row count of {@code ledger.transaction_category_balances}, which is zero at
     *     the start of every case because the hook above empties the table
     */
    private long rowCount() {
        return this.balances.count();
    }

    /**
     * Reads a row by its whole key and applies the sum of its value and an amount through the
     * repository's update member.
     *
     * <p>Assumptions: this is the update arm of {@code app/cbl/CBTRN02C.cbl} expressed as the two acts
     * the reference performs in that order. The read corresponds to line 474, whose success is what
     * leaves the flag at the {@code 'N'} preset at line 473 and so takes the {@code ELSE} of the branch
     * at line 495; the addition corresponds to line 527; and the member's own statement corresponds to
     * the {@code REWRITE} at line 528.
     *
     * <p>Refactoring Rationale: this helper used to mutate the managed instance and flush the change
     * itself, which reproduced the reference statement inside the test while production code still
     * offered only the inherited save. The statement is now issued by
     * {@code TransactionCategoryBalanceWriter.updateBalance}, so the discipline is exercised where it
     * has to hold rather than restated here; the arithmetic stays in this helper because deciding the
     * base is the calling service's rule in the migration and the caller's rule here.
     *
     * <p>Assumptions: the read is performed INSIDE the transaction rather than outside it, because the
     * update member requires a caller's transaction and refuses to start one, and because the member
     * finds the row in the same persistence context this read populated rather than issuing a second
     * query. The context is cleared afterwards for the same reason every write in this file clears it:
     * the assertion that follows must be answered by PostgreSQL and not by the persistence context.
     *
     * @param id the whole composite key of the row to accumulate onto, which must already exist; must
     *     not be {@code null}
     * @param amount the amount to add, which may be zero or negative; must not be {@code null}
     * @throws java.util.NoSuchElementException if no row carries that key when this helper reads it,
     *     which means the caller has reached the update arm for a key that belongs to the create arm
     */
    private void accumulateOntoManagedRow(TransactionCategoryBalanceId id, BigDecimal amount) {
        this.transactionTemplate.executeWithoutResult(status -> {
            BigDecimal base = this.balances.findById(id).orElseThrow().getBalance();
            this.balances.updateBalance(id, added(base, amount));

            // WHY : Assumptions: the clear stays here even though the member flushes for itself,
            //       because the two acts answer different needs. The member's flush sends the
            //       statement; this clear empties the context, so the read in the case that follows
            //       has nothing local to satisfy it and must reach PostgreSQL. Dropping the clear
            //       would let an assertion be answered by the very instance this helper handed over.
            this.entityManager.clear();
        });
    }

    /**
     * Inserts one row through the repository's create member inside its own transaction.
     *
     * <p>Assumptions: this is the create arm's write path expressed through production code. The
     * member's persist corresponds to the {@code WRITE} at line 510 of {@code app/cbl/CBTRN02C.cbl},
     * and its own flush sends the insert while the transaction is still open, so the read that follows
     * must reach PostgreSQL rather than the persistence context.
     *
     * <p>Trade-offs: this exists alongside the raw fixture builder rather than replacing it, and the
     * two are not interchangeable. The member refuses a duplicate key on its own guard before any
     * statement is issued, which is the right behaviour and the wrong instrument for the case that has
     * to observe the ENGINE refusing and name the constraint. Fixtures and that one case use the
     * builder; every case asserting the create ARM uses this.
     *
     * @param row the transient entity to insert, which must carry all three key components because the
     *     migration declares every one of them not null; must not be {@code null}
     * @throws com.carddemo.common.error.RecordConflictException if a row already carries that composite
     *     key, which the member refuses rather than absorbing into an update
     */
    private void createThroughWriteMember(TransactionCategoryBalance row) {
        this.transactionTemplate.executeWithoutResult(status -> {
            this.balances.createRow(row);
            this.entityManager.clear();
        });
    }

    /**
     * Writes the supplied rows inside one transaction, flushes them, and detaches them.
     *
     * <p>Assumptions: this is the create arm's write path and the fixture builder for every other case,
     * and it persists rather than merging for the reason recorded on the class. The persist corresponds
     * to the {@code WRITE} at line 510 of {@code app/cbl/CBTRN02C.cbl}; flushing sends the insert
     * statements while the transaction is still open, and clearing then empties the context, so the read
     * that follows has nothing local to satisfy it and must go to PostgreSQL. A read answered out of the
     * persistence context reaches no key and no column -- it returns the very object the test handed
     * over.
     *
     * <p>Trade-offs: the rows are left detached rather than reattached. That is what makes the identity
     * assertion in the keyed-read case meaningful, since a genuine re-read must produce a different
     * object, and the cost is that a caller must not expect a later change to one of these instances to
     * reach the database.
     *
     * @param rows the transient entities to insert, each of which must carry all three key components
     *     because the migration declares every one of them not null; an empty array writes nothing and
     *     is accepted
     * @throws ConstraintViolationException if any row duplicates a composite key already present,
     *     which the sole constraint on this table refuses rather than absorbing. The provider's own
     *     exception is what surfaces rather than the framework's portable one, because the flush below
     *     precedes the commit at which that translation would happen
     */
    private void persistAndDetach(TransactionCategoryBalance... rows) {
        this.transactionTemplate.executeWithoutResult(status -> {
            for (TransactionCategoryBalance row : rows) {
                this.entityManager.persist(row);
            }

            // WHY : Assumptions: the order is not interchangeable here either. Clearing before the
            //       flush would discard the pending inserts unwritten, so the rows the following
            //       assertion reasons about would never exist and the assertion would fail for a
            //       reason that has nothing to do with the key or the arm it names.
            this.entityManager.flush();
            this.entityManager.clear();
        });
    }

    /**
     * Reads one text column from the engine's catalogue as a list.
     *
     * <p>Assumptions: the rows are mapped through the platform's string conversion rather than cast,
     * because a catalogue name arrives as a character type whose exact Java class is the driver's
     * choice, and a cast would couple this helper to that choice for no gain.
     *
     * <p>Assumptions: the physical contract is read from the catalogue rather than from configuration,
     * because a declared key and an effective one are different facts and only the second one binds a
     * query. That is what lets the two cases at the head of this class assert the constraint name and
     * the column order that no other test in this package asserts.
     *
     * @param sql a query returning one text column, ordered by the caller where order matters
     * @return the column's values in the order the query produced them, never {@code null}
     */
    private List<String> nativeStringColumn(String sql) {
        List<?> rows = this.entityManager.createNativeQuery(sql).getResultList();
        return rows.stream().map(String::valueOf).toList();
    }

    /**
     * The minimal Spring Boot configuration this suite runs against.
     *
     * <p>Assumptions: this configuration declares no component scan, and that omission is the whole
     * point of it. The module's own application class scans this bounded context and so registers an
     * explicit pool factory that builds from datasource PROPERTIES; those properties are absent here
     * because the container's coordinates arrive as a connection-details bean, and the context then
     * fails on a URL that does not begin with the JDBC scheme. Naming the two persistence packages
     * explicitly instead leaves the framework's own auto-configuration to build the pool from that
     * bean.
     *
     * <p>Assumptions: nothing beyond persistence is enabled -- no web layer, no security filter chain,
     * no API documentation -- because the suite runs with no servlet environment and every additional
     * auto-configured concern is one more way for a persistence assertion to fail for an unrelated
     * reason. The test profile's stub issuer is reachable by nothing and needs to be, for exactly that
     * reason.
     *
     * <p>Trade-offs: declaring it nested rather than as a file of its own keeps the package charter's
     * closed file set true, at the cost of this configuration duplicating the one this package's first
     * integration test declares. That cost is accepted deliberately: the same charter rejects a shared
     * holder because a container in a shared base class is shared mutable state, and a row surviving
     * from a sibling class is exactly what would turn this class's create arm into an update.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.transaction.domain")
    @EnableJpaRepositories("com.carddemo.transaction.repository")
    static class CategoryBalancePersistenceTestApplication {
    }
}
