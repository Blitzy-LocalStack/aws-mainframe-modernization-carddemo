package com.carddemo.authorization.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.authorization.domain.AuthFraud;
import com.carddemo.authorization.domain.AuthFraudKey;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.common.time.TimestampFormatter;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Pins the schema truth of {@code auth_fraud} onto a live engine: two catalogue objects, one upsert.
 *
 * <p>Purpose: this class settles the properties of the migrated Db2 fraud table that only a running
 * engine can answer, and that the interface beside it states it relies on rather than restates. They
 * are: the primary key is the composite {@code (card_num, auth_ts)} in that order under the name
 * {@code pk_auth_fraud}; a SECOND catalogue object {@code idx_auth_fraud_card_recent} exists over the
 * same pair with the second component descending; the two objects are genuinely distinct rather than
 * one object wearing the wanted name; the table carries exactly twenty-six columns; the timestamp
 * column carries microsecond precision; the sole variable-width column does not trim what it is
 * given; the table carries no check constraint; and the upsert behaves correctly in BOTH directions,
 * reporting which one it took, moving exactly two columns and leaving the other twenty-four as they
 * were.
 *
 * <p>Refactoring Rationale: F1, and this is the headline. The reference system expressed uniqueness
 * and the newest-first access path in ONE Db2 object. Its whole index definition is four lines --
 * {@code app/app-authorization-ims-db2-mq/ddl/XAUTHFRD.ddl} declares
 * {@code CREATE UNIQUE INDEX CARDDEMO.XAUTHFRD} at L1, {@code ON CARDDEMO.AUTHFRDS} at L2,
 * {@code (CARD_NUM ASC, AUTH_TS DESC)} at L3 and {@code COPY YES;} at L4 -- and that single unique
 * index simultaneously enforced the primary key declared at
 * {@code app/app-authorization-ims-db2-mq/ddl/AUTHFRDS.ddl} L28,
 * {@code PRIMARY KEY(CARD_NUM,AUTH_TS )}, and provided the mixed-direction path used to read a card's
 * fraud history newest first. PostgreSQL cannot do both jobs with one object: a primary-key
 * constraint is backed by an implicitly created unique index that is always all-ascending and cannot
 * carry a descending column. The target therefore declares the two concerns SEPARATELY -- the key
 * constraint on {@code (card_num, auth_ts)} with its ascending backing index, plus a second index
 * over {@code (card_num ASC, auth_ts DESC)}. Same observable behaviour, two objects instead of one;
 * the divergence is documented here and in the migration.
 *
 * <p>Assumptions: F1 rests on the two SHIPPED definition files just cited and on nothing else. The
 * extension tree's prose carries a similar-looking statement block; it is not the authority and is
 * deliberately not cited, because the shipped definitions are what the reference system actually
 * built. The contrast with the sibling table is what makes the split legible rather than arbitrary:
 * {@code app/app-authorization-ims-db2-mq/ims/DBPAUTX0.dbd} L18 declares an index database whose one
 * segment at L27 to L31 carries a single sequence field, {@code NAME=(INDXSEQ,SEQ,U)}, six bytes and
 * plainly ascending. A PostgreSQL primary key reproduces that exactly, so the summary table needs ONE
 * object. It is the presence of a descending component here, and nothing else, that forces two.
 *
 * <p>Alternatives Considered: F2, the direction is read out of the catalogue rather than out of a
 * query plan. Asserting on an execution plan was rejected on two independent grounds: the planner may
 * legitimately choose a sequential scan on a table holding a handful of test rows, and it may satisfy
 * a descending order by walking an ascending index backwards -- so a plan assertion neither proves
 * nor disproves the DECLARED direction, and would additionally flap with row counts and statistics.
 * The declared direction is a property of the schema object, so the schema object is what is
 * interrogated. Both available readings are taken: the engine's own rendering of the definition, and
 * the per-column descending bit out of {@code pg_index.indoption}, which is the machine-readable form
 * of the same fact.
 *
 * <p>Assumptions: F3, {@code COPY YES} at {@code XAUTHFRD.ddl} L4 has NO PostgreSQL analogue and none
 * is invented. It declared the Db2 index eligible for image copy, which is a mainframe-side
 * operational attribute of the utility that backs the index up, not a property of the access path.
 * Nothing in this class asserts a storage parameter standing in for it, and a later reader finding no
 * such assertion is not looking at an oversight. This is the same disposition the retired scheduling
 * verbs elsewhere in this tree receive.
 *
 * <p>Alternatives Considered: F4, and this one is mandatory to record because the faithful choice
 * looks like the lazy one. The reference program attempts the insert and branches on what the engine
 * returns: {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} runs the twenty-six-column
 * insert at L141 to L198, takes the success message at L199 to L201, and at L203 tests for the
 * duplicate-key condition, whereupon L204 performs the narrow update. The target's conflict-then-
 * update form is the direct analogue of that shape. Two alternatives were evaluated and both
 * rejected. A read-then-write -- probe with a select, then branch to an insert or an update -- was
 * rejected because it is not what the reference does, and because a probe opens a window between the
 * look and the write in which another writer can insert the same key, a race the reference does not
 * have. A trigger or a stored procedure was rejected because it would put the rule in a second place
 * that the interface does not show, so a reader of the repository would not be able to see what a
 * write does.
 *
 * <p>Assumptions: F6, the update direction moves exactly two columns of the twenty-six. The reference
 * update at {@code COPAUS2C.cbl} L221 to L232 names {@code AUTH_FRAUD} at L224 and
 * {@code FRAUD_RPT_DATE} at L225 in its set clause, and its where clause at L226 to L228 names only
 * the two key columns. The remaining twenty-four carry the authorization snapshot taken when the row
 * was first written and must survive a second report untouched. That is asserted by comparing the
 * whole row before and after with the two permitted movers REMOVED from both readings, because only
 * comparing what is left can fail on a column nobody thought about; checking the two movers directly
 * would pass on an update that also cleared the merchant name.
 *
 * <p>Alternatives Considered: F5, the report date is the engine's and the expected value is read back
 * from the engine in the same session. Comparing against this process's clock was rejected outright:
 * the two agree whenever container and runner share a zone, which is nearly always, and disagree
 * exactly at the boundary where the difference matters, so such an assertion would pass while the
 * property was broken. A hard-coded date was rejected for the same reason in a more obvious form.
 * Note also which of two forms the set clause carries: the reference supplies {@code CURRENT DATE} on
 * both paths, at {@code COPAUS2C.cbl} L194 on the insert and L225 on the update, whereas the target's
 * conflict arm propagates the proposed row's own value. The two coincide as long as the caller sources
 * that value from the engine, which is what the interface's date reader exists for and what every case
 * here does; the assertion below is written against the observable outcome -- the stored stamp equals
 * the engine's current date -- so it holds under either form and cannot be satisfied by the JVM clock.
 *
 * <p>Assumptions: F5 does not conflict with the house determinism convention, and the reconciliation
 * is worth stating because the two look opposed. {@code tests/README.md} L488 to L489 requires
 * business dates to be injected rather than read from the wall clock, so that reruns are reproducible.
 * A fraud report date is not a business date driving a calculation; it is an audit stamp of the moment
 * an operator acted, and the reference takes it from the database on the very path this class
 * exercises. The convention constrains the dates that feed arithmetic, and this column feeds none.
 *
 * <p>Assumptions: F8, the timestamp column carries microsecond precision, and the evidence is the
 * conversion mask rather than a preference. {@code COPAUS2C.cbl} L171 to L172 converts the host field
 * with {@code 'YY-MM-DD HH24.MI.SSNNNNNN'} -- six positional digits of fraction -- and the host form
 * at {@code app/app-authorization-ims-db2-mq/dcl/AUTHFRDS.dcl} L57 is {@code AUTH-TS PIC X(26)}, the
 * same twenty-six-character width the shared kernel's timestamp formatter emits. A microsecond
 * timestamp is therefore the exact match for that form, and the round trip below writes one and reads
 * it back unchanged.
 *
 * <p>Assumptions: F7, the table carries exactly twenty-six columns.
 * {@code AUTHFRDS.ddl} L2 to L27 declare them and L28 the key, and {@code dcl/AUTHFRDS.dcl} L88 says
 * so in words. Asserting the count catches in one line what a column-by-column comparison would take
 * twenty-six to catch: a column silently added or dropped. The last two are the account and customer
 * identifiers, at {@code AUTHFRDS.ddl} L26 and L27, whose scale the host declaration pins to zero at
 * {@code dcl} L49 to L50, which is why they are whole numbers in the target and not scaled decimals.
 *
 * <p>Assumptions: F12, the merchant name is the only variable-width text column and it is never
 * trimmed. {@code AUTHFRDS.ddl} L18 declares {@code MERCHANT_NAME VARCHAR(22)} where every other text
 * column is fixed width, and its host form is the length-and-text pair at {@code dcl} L73 to L77.
 * What settles the trimming question is {@code COPAUS2C.cbl} L130, which moves the LENGTH OF the
 * source field into the length half unconditionally -- always twenty-two, whatever the text -- so the
 * reference transmits the padding rather than measuring the content. A padded value must therefore
 * come back at its full declared width.
 *
 * <p>Assumptions: F9, this table carries NO check constraint, and that negative finding is asserted
 * rather than left implicit. {@code AUTHFRDS.ddl} declares none: the match status at L23 and the fraud
 * indicator at L24 are unconstrained single characters, and {@code dcl/AUTHFRDS.dcl} L46 to L47 agree.
 * The sibling detail table does carry domain checks, and they are asserted by the class that owns it;
 * inferring one here would fail the build against a constraint that does not exist, and adding one to
 * the migration to make such an assertion pass would refuse fraud rows the reference accepts.
 *
 * <p>Assumptions: F10, the upsert reports which direction it took, and the value it reports is what is
 * asserted. The reference surfaces two distinct operator-visible strings, the add message at
 * {@code COPAUS2C.cbl} L201 and the update message at L232; user-visible text is carried across
 * verbatim, so the writer must tell its caller which of the two applies. Alternatives Considered: on
 * the MECHANISM, the conflict-aware statement can distinguish the paths by returning whether the row
 * it produced was newly inserted, which the engine exposes through the row's transaction metadata.
 * That is an implementation detail of the writer and this class does not reach around the interface to
 * check the statement text; it drives the published call and asserts the boolean that comes back.
 *
 * <p>Assumptions: F11, both directions are provoked separately. The house convention recorded at
 * {@code tests/README.md} L581 to L583 requires the create-versus-update branch of a balance row to be
 * exercised both ways, and a conflict-aware insert is the direct analogue, so it gets the same
 * treatment. Exercising only whichever branch happens to run first leaves half the statement unproven,
 * and which half that is would depend on ordering rather than on anything a case states.
 *
 * <p>Assumptions: no fixture file is loaded here, deliberately. Every recorded image in this module
 * declares the tests that consume it and not one of them names a container-backed repository case, so
 * rows are built programmatically from the column list at {@code AUTHFRDS.ddl} L2 to L27 and the host
 * declaration at {@code dcl/AUTHFRDS.dcl} L56 to L86. A later reader should not add a fixture
 * dependency this table does not have, and should not restate a codec proof that belongs to the
 * shared kernel.
 *
 * <p>Trade-offs: a real engine in a container is used rather than an in-memory database, and this
 * class is the clearest case in the module for paying that cost. The accepted price is container
 * start-up on every run plus a container runtime the host must provide. It is accepted because BOTH
 * headline properties here are specific to the target engine and are not expressible elsewhere: a
 * mixed-direction index is rejected or silently flattened by an in-memory engine, and a
 * conflict-then-update insert is either unsupported or supported with different semantics. A green
 * in-memory run would establish nothing about the schema that is actually deployed, which makes the
 * faster option a weaker assertion rather than a cheaper version of this one.
 *
 * <p>Trade-offs: the persistence unit is handed the whole domain package rather than the single entity
 * under assertion. The difference from the sibling summary case is forced rather than stylistic: this
 * entity's identifier is an embedded composite, so naming the entity alone would leave the embeddable
 * unmapped and the unit would fail to build. Mapping the neighbouring entities costs nothing at run
 * time, since mapping a type requires only that its table exist, which the migration guarantees, and
 * no case below reads or writes one.
 *
 * <p>Trade-offs: the class name ends in the repository-and-integration suffix because the module's
 * failsafe configuration includes exactly that suffix and nothing else. Any other spelling would
 * compile, be collected by no plugin, never run, and be reported by nothing -- a green build over a
 * case that never executed.
 */
@Testcontainers
class AuthFraudRepositoryIT {

    /** The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine. */
    // WHY : Assumptions: this is deliberately the SAME digest the other container-backed cases in this
    //       module name. Two of them pinning two engines could disagree about one catalogue shape, and
    //       the disagreement would surface as whichever ran second; pinning by digest rather than by
    //       tag is what makes the reference immutable, since a publisher may rebuild and republish a
    //       minor tag onto a new base layer.
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /** The container every case in this class runs against, started once for the class. */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /** The unquoted schema name, used as a bind value in every catalogue predicate. */
    private static final String SCHEMA_NAME = "authorization";

    /** The schema name as it must appear inside a statement. */
    // WHY : Assumptions: the name must be QUOTED here. It is a reserved word the parser otherwise
    //       reads as the AUTHORIZATION keyword, which yields a syntax error rather than a
    //       missing-schema error and so points the reader at the wrong thing entirely; the schema and
    //       role bootstrap records the same hazard for the same reason.
    private static final String QUOTED_SCHEMA = "\"authorization\"";

    /** The statement each pooled connection runs so an unqualified table name resolves here. */
    private static final String SEARCH_PATH_PIN = "SET search_path TO " + QUOTED_SCHEMA;

    /** The table under assertion, unqualified because the search path is pinned. */
    private static final String TABLE = "auth_fraud";

    /** The name the migration gives the composite primary key. */
    private static final String PRIMARY_KEY_NAME = "pk_auth_fraud";

    /** The name the migration gives the mixed-direction secondary index. */
    private static final String RECENT_INDEX_NAME = "idx_auth_fraud_card_recent";

    /** The column count the reference table declares, asserted rather than assumed. */
    private static final int DECLARED_COLUMN_COUNT = 26;

    /** The columns the update direction is permitted to move, removed before a whole-row compare. */
    private static final List<String> MUTABLE_COLUMNS = List.of("auth_fraud", "fraud_rpt_date");

    /** The card whose history most cases write, sixteen characters as the column declares. */
    private static final String CARD_NUMBER = "4000000000000007";

    /** A second card, so a card-prefixed read can be shown not to cross into another card. */
    private static final String OTHER_CARD_NUMBER = "4000000000000015";

    /** The card the precision round trip writes, so no other case shares its key. */
    private static final String PRECISION_CARD_NUMBER = "4000000000000031";

    /** The card the padded-name case writes, so no other case shares its key. */
    private static final String PADDING_CARD_NUMBER = "4000000000000049";

    /** The card the insert-direction case writes, so no other case shares its key. */
    private static final String INSERT_CARD_NUMBER = "4000000000000056";

    /** The card the conflict-direction case writes, so no other case shares its key. */
    private static final String CONFLICT_CARD_NUMBER = "4000000000000064";

    /** The card the absent-row guard writes, so no other case shares its key. */
    private static final String GUARD_CARD_NUMBER = "4000000000000072";

    /** The earlier of the two authorizations written for {@link #CARD_NUMBER}. */
    private static final LocalDateTime EARLIER_AUTH_TS =
            LocalDateTime.of(2022, 3, 14, 9, 15, 30, 123_456_000);

    /** The later of the two authorizations written for {@link #CARD_NUMBER}. */
    private static final LocalDateTime LATER_AUTH_TS =
            LocalDateTime.of(2022, 3, 14, 21, 45, 5, 654_321_000);

    /** The instant the precision round trip writes, carrying six significant fraction digits. */
    // WHY : Assumptions: the fraction is microsecond-ALIGNED on purpose. A nanosecond value the
    //       column cannot represent would be rounded on the way in, so the round trip would fail on
    //       the JVM's spare precision rather than on the column's declared precision, which is the
    //       property under assertion.
    private static final LocalDateTime PRECISE_AUTH_TS =
            LocalDateTime.of(2022, 7, 18, 9, 16, 44, 987_654_000);

    /** The report-fraud indicator, one of the two values the reference writes. */
    private static final String REPORTED = PendingAuthDetail.FRAUD_REPORTED;

    /** The removed indicator, the other value, and the update direction's target. */
    private static final String RESOLVED = PendingAuthDetail.FRAUD_REMOVED;

    /** The account the written rows name, carried as a plain column rather than a foreign key. */
    private static final long ACCOUNT_ID = 111_111_111L;

    /** The customer the written rows name, likewise a plain column. */
    private static final long CUSTOMER_ID = 222_222_222L;

    /** The authorization date half of the source authorization's key, as a packed day number. */
    private static final int AUTH_DATE_KEY = 26_215;

    /** The authorization time half of the source authorization's key, to millisecond resolution. */
    private static final int AUTH_TIME_KEY = 9_16_44_902;

    /** The transaction amount written, exact to the scale the column declares. */
    private static final BigDecimal TRANSACTION_AMOUNT = new BigDecimal("250.00");

    /** The approved amount written, deliberately distinct from the transaction amount. */
    private static final BigDecimal APPROVED_AMOUNT = new BigDecimal("249.00");

    /** The merchant name at its full declared width, trailing blanks included. */
    // WHY : Assumptions: this literal is exactly twenty-two characters, which is what makes the
    //       padded-name case meaningful; the case asserts the width rather than trusting this comment,
    //       so a later edit that changes the literal fails there rather than passing quietly.
    private static final String PADDED_MERCHANT_NAME = "ACME HARDWARE         ";

    /** The catalogue question that settles which columns the primary key names, in order. */
    private static final String PRIMARY_KEY_COLUMNS = """
            SELECT kcu.column_name
              FROM information_schema.table_constraints tc
              JOIN information_schema.key_column_usage kcu
                ON kcu.constraint_schema = tc.constraint_schema
               AND kcu.constraint_name = tc.constraint_name
             WHERE tc.table_schema = ?
               AND tc.table_name = ?
               AND tc.constraint_type = 'PRIMARY KEY'
             ORDER BY kcu.ordinal_position
            """;

    /** The catalogue question that settles what the primary-key constraint is called. */
    private static final String PRIMARY_KEY_CONSTRAINT_NAME = """
            SELECT tc.constraint_name
              FROM information_schema.table_constraints tc
             WHERE tc.table_schema = ?
               AND tc.table_name = ?
               AND tc.constraint_type = 'PRIMARY KEY'
            """;

    /** The catalogue question that returns one index's own definition text. */
    private static final String INDEX_DEFINITION = """
            SELECT indexdef
              FROM pg_indexes
             WHERE schemaname = ?
               AND tablename = ?
               AND indexname = ?
            """;

    /** The catalogue question that enumerates every index object on the table. */
    private static final String INDEX_NAMES = """
            SELECT indexname
              FROM pg_indexes
             WHERE schemaname = ?
               AND tablename = ?
             ORDER BY indexname
            """;

    /** The catalogue question that names the index backing the primary key. */
    private static final String PRIMARY_BACKING_INDEX = """
            SELECT cls.relname
              FROM pg_index idx
              JOIN pg_class cls ON cls.oid = idx.indexrelid
              JOIN pg_class tbl ON tbl.oid = idx.indrelid
              JOIN pg_namespace nsp ON nsp.oid = tbl.relnamespace
             WHERE nsp.nspname = ?
               AND tbl.relname = ?
               AND idx.indisprimary
             ORDER BY cls.relname
            """;

    /** The catalogue question that names every index on the table that does NOT back the key. */
    private static final String NON_PRIMARY_INDEXES = """
            SELECT cls.relname
              FROM pg_index idx
              JOIN pg_class cls ON cls.oid = idx.indexrelid
              JOIN pg_class tbl ON tbl.oid = idx.indrelid
              JOIN pg_namespace nsp ON nsp.oid = tbl.relnamespace
             WHERE nsp.nspname = ?
               AND tbl.relname = ?
               AND NOT idx.indisprimary
             ORDER BY cls.relname
            """;

    /** The catalogue question that reads one index's per-column sort direction, in key order. */
    // WHY : Assumptions: the descending flag is the low bit of the per-column option word, and the
    //       option and key vectors of an index are zero-based, which is why the generated subscript is
    //       used directly to index both rather than being shifted by one. Reading the bit is the
    //       machine-readable half of the direction assertion; the rendered definition text is the
    //       human-readable half, and both are checked because either alone has a blind spot -- the
    //       text can omit an implicit ascending marker, and the bit carries no column name.
    private static final String INDEX_COLUMN_DIRECTIONS = """
            SELECT att.attname || ' '
                   || CASE WHEN (idx.indoption[pos] & 1) = 1 THEN 'DESC' ELSE 'ASC' END
              FROM pg_index idx
              JOIN pg_class cls ON cls.oid = idx.indexrelid
              JOIN pg_class tbl ON tbl.oid = idx.indrelid
              JOIN pg_namespace nsp ON nsp.oid = tbl.relnamespace
              CROSS JOIN generate_subscripts(idx.indkey, 1) AS pos
              JOIN pg_attribute att
                ON att.attrelid = tbl.oid
               AND att.attnum = idx.indkey[pos]
             WHERE nsp.nspname = ?
               AND tbl.relname = ?
               AND cls.relname = ?
             ORDER BY pos
            """;

    /** The catalogue question that counts the table's columns. */
    private static final String COLUMN_COUNT = """
            SELECT count(*)::text
              FROM information_schema.columns
             WHERE table_schema = ?
               AND table_name = ?
            """;

    /** The catalogue question that reports one column's declared type and fractional precision. */
    private static final String COLUMN_TYPE = """
            SELECT data_type
                   || COALESCE('(' || datetime_precision::text || ')', '')
                   || COALESCE('(' || character_maximum_length::text || ')', '')
              FROM information_schema.columns
             WHERE table_schema = ?
               AND table_name = ?
               AND column_name = ?
            """;

    /** The catalogue question that enumerates the table's constraints of one given kind. */
    // WHY : Alternatives Considered: the information-schema constraint view was rejected for this one
    //       question. It reports every NOT NULL column as a check constraint under a generated name, so
    //       a negative assertion driven from it would fail on the columns the migration declares NOT
    //       NULL -- for a reason with nothing to do with the property under assertion. The system
    //       catalogue distinguishes the two: a genuine check carries the check type code, and a
    //       not-null constraint does not, so filtering on that code asks the question actually intended.
    // WHY : Assumptions: the kind is a BIND VALUE rather than a literal so that one query can both
    //       report the absence of checks and demonstrate that its own joins and predicates resolve, by
    //       returning the key that is known to be there. A hard-coded kind could return nothing because
    //       the table name was misspelled and would read as a clean absence.
    private static final String CONSTRAINTS_OF_KIND = """
            SELECT con.conname
              FROM pg_constraint con
              JOIN pg_class rel ON rel.oid = con.conrelid
              JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
             WHERE nsp.nspname = ?
               AND rel.relname = ?
               AND con.contype = ?
             ORDER BY con.conname
            """;

    /** The constraint kind code the catalogue uses for a genuine check constraint. */
    private static final String CHECK_KIND = "c";

    /** The constraint kind code the catalogue uses for a primary key. */
    private static final String PRIMARY_KEY_KIND = "p";

    /** Every column of one row, so an update can be compared against the row it replaced. */
    private static final String SELECT_WHOLE_ROW =
            "SELECT * FROM auth_fraud WHERE card_num = ? AND auth_ts = ?";

    /** One card's authorization instants, newest first, through the mixed-direction index. */
    private static final String CARD_HISTORY_NEWEST_FIRST =
            "SELECT auth_ts FROM auth_fraud WHERE card_num = ? ORDER BY auth_ts DESC";

    /** The pool every persistence context and every direct read in this class draws from. */
    private static HikariDataSource dataSource;

    /** The persistence unit this class assembles, held so teardown can release it. */
    private static LocalContainerEntityManagerFactoryBean persistenceUnit;

    /** The factory every persistence context in this class is created from. */
    private static EntityManagerFactory entityManagerFactory;

    /**
     * Applies the module's own migration, then assembles the persistence unit over the domain package.
     *
     * <p>Assumptions: the order is not interchangeable. The migration runs first so the table exists
     * before any persistence context opens, and automatic definition emission is off, so nothing else
     * would create it. Schema creation is enabled for the migration although the deployed
     * configuration disables it, because deployment relies on the bootstrap that owns the eight
     * schemas having run first and a bare container has had no bootstrap. No role and no grant is
     * issued here; those belong to that bootstrap.</p>
     *
     * <p>Trade-offs: a migration failure is deliberately not caught. It aborts the class before any
     * case runs, which reports the migration as the cause, instead of letting every case fail on a
     * missing table and leaving a reader to work out why.</p>
     */
    @BeforeAll
    static void startEngineAndPersistence() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA_NAME)
                .defaultSchema(SCHEMA_NAME)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setConnectionInitSql(SEARCH_PATH_PIN);
        dataSource.setMaximumPoolSize(4);

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        // WHY : Assumptions: the provider is told to emit no definition, which restates the deployed
        //       profile's own setting rather than relying on a default. An emitted definition would
        //       replace the migrated table -- and with it the mixed-direction index this class reads --
        //       by one derived from entity metadata, on which that index simply does not exist, so the
        //       class would pass while asserting nothing about the deployed schema.
        adapter.setGenerateDdl(false);

        persistenceUnit = new LocalContainerEntityManagerFactoryBean();
        persistenceUnit.setDataSource(dataSource);
        persistenceUnit.setPersistenceUnitName("carddemo-authorization-fraud-catalogue-it");
        persistenceUnit.setPackagesToScan(AuthFraud.class.getPackageName());
        persistenceUnit.setJpaVendorAdapter(adapter);
        // WHY : Assumptions: no default schema is handed to the provider, mirroring the deployed
        //       configuration's deliberate omission of one. The pin belongs to the connection, so a
        //       provider-level default would give this schema a second resolution route that could stay
        //       correct while the connection's own pin was wrong.
        persistenceUnit.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        persistenceUnit.afterPropertiesSet();
        entityManagerFactory = persistenceUnit.getObject();
    }

    /**
     * Releases the persistence unit and the pool once every case has run.
     *
     * <p>Assumptions: the container is released by the Testcontainers extension and is deliberately
     * not closed here, whereas the pool and the persistence unit are this class's own and would keep
     * non-daemon threads alive after the last case if they were left open.</p>
     */
    @AfterAll
    static void stopPersistence() {
        if (persistenceUnit != null) {
            persistenceUnit.destroy();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * Empties the table before each case.
     *
     * <p>Assumptions: no parent row is created, unlike the sibling detail cases, because this table
     * declares no foreign key at all -- it carries the account and customer identifiers as plain
     * columns. That is a property of the migration rather than an oversight: a fraud row is a snapshot
     * taken when a report was made and has to survive the purge of the authorization it describes,
     * which a foreign key would forbid.</p>
     */
    @BeforeEach
    void emptyTheTable() {
        execute("DELETE FROM auth_fraud");
    }

    /**
     * Opens a pooled connection whose search path is already pinned to the migrated schema.
     *
     * @return a live connection, which the caller closes
     * @throws SQLException if the pool cannot supply a connection
     */
    private static Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Runs one statement that is expected to succeed.
     *
     * @param sql the statement to run
     * @throws IllegalStateException if the statement fails, because every caller here is setup and a
     *     setup failure must abort rather than be mistaken for a property under assertion
     */
    private static void execute(String sql) {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException("setup statement failed: " + sql, failure);
        }
    }

    /**
     * Runs one single-column catalogue query and collects its rows in the order returned.
     *
     * @param sql the query to run
     * @param arguments the bind values, in order
     * @return the single column of every row returned, in order
     * @throws SQLException if the query fails, which is a setup fault rather than the property asserted
     */
    private static List<String> queryColumn(String sql, String... arguments) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement query = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                query.setString(index + 1, arguments[index]);
            }
            try (ResultSet rows = query.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
                return values;
            }
        }
    }

    /**
     * Reads exactly one value from a catalogue query that must answer with exactly one row.
     *
     * @param description what the value means, for the failure message
     * @param sql the query to run
     * @param arguments the bind values, in order
     * @return the single value the query returned
     * @throws SQLException if the query fails, which is a setup fault
     * @throws AssertionError if the query returned other than exactly one row, because a catalogue
     *     question that answers twice or not at all has not been asked precisely enough to assert on
     */
    private static String querySingleValue(String description, String sql, String... arguments)
            throws SQLException {
        List<String> values = queryColumn(sql, arguments);
        assertThat(values).as("exactly one answer for %s", description).hasSize(1);
        return values.get(0);
    }

    /**
     * Reads one row as a column-name-to-value map, so two readings can be compared whole.
     *
     * @param cardNum the card half of the key
     * @param authTs the timestamp half of the key
     * @return every column of that row, keyed by column name in catalogue order
     * @throws SQLException if the read fails, which is a setup fault rather than the property asserted
     * @throws AssertionError if the row is absent, because every caller has just written it
     */
    private static Map<String, Object> readWholeRow(String cardNum, LocalDateTime authTs)
            throws SQLException {
        try (Connection connection = connection();
                PreparedStatement read = connection.prepareStatement(SELECT_WHOLE_ROW)) {
            read.setString(1, cardNum);
            read.setObject(2, authTs);
            try (ResultSet rows = read.executeQuery()) {
                assertThat(rows.next()).as("a row for %s at %s", cardNum, authTs).isTrue();
                ResultSetMetaData shape = rows.getMetaData();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int column = 1; column <= shape.getColumnCount(); column++) {
                    row.put(shape.getColumnName(column), rows.getObject(column));
                }
                return row;
            }
        }
    }

    /**
     * Copies a whole-row reading with the two columns the update direction may move removed.
     *
     * <p>Assumptions: F6 as recorded on this class. Comparing what is LEFT after the movers are taken
     * out is what lets the comparison fail on a column nobody anticipated; asserting the movers
     * directly would pass on an update that also cleared, say, the merchant name.</p>
     *
     * @param row a whole-row reading to filter
     * @return the same reading without the fraud indicator and the report date
     */
    private static Map<String, Object> withoutMutableColumns(Map<String, Object> row) {
        Map<String, Object> untouched = new LinkedHashMap<>(row);
        MUTABLE_COLUMNS.forEach(untouched::remove);
        return untouched;
    }

    /**
     * Runs one unit of work against the repository inside a committed transaction.
     *
     * <p>Assumptions: the context is created per call and closed in a {@code finally}, which is the
     * arrangement the sibling cases in this package established. A context shared across cases would
     * carry an identity map between them, so a later case could read an entity the engine never
     * received.</p>
     *
     * @param <R> the result type the work produces
     * @param work what to do with the repository; run once
     * @return whatever the work returned
     * @throws RuntimeException if the work or the commit fails, rethrown after the rollback so a
     *     refusal assertion can read the provider's own exception chain
     */
    private static <R> R inTransaction(Function<AuthFraudRepository, R> work) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            AuthFraudRepository repository =
                    new JpaRepositoryFactory(entityManager).getRepository(AuthFraudRepository.class);
            entityManager.getTransaction().begin();
            try {
                R result = work.apply(repository);
                entityManager.flush();
                entityManager.getTransaction().commit();
                return result;
            } catch (RuntimeException failure) {
                if (entityManager.getTransaction().isActive()) {
                    entityManager.getTransaction().rollback();
                }
                throw failure;
            }
        } finally {
            entityManager.close();
        }
    }

    /**
     * Runs one read against the repository through a context that has written nothing.
     *
     * <p>Assumptions: a fresh context is the whole point. A context that had written the row would
     * answer from its own identity map, so the read would pass on an entity that never reached the
     * engine.</p>
     *
     * @param <R> the result type the read produces
     * @param read what to read; run once
     * @return whatever the read returned
     */
    private static <R> R readThrough(Function<AuthFraudRepository, R> read) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            return read.apply(
                    new JpaRepositoryFactory(entityManager).getRepository(AuthFraudRepository.class));
        } finally {
            entityManager.close();
        }
    }

    /**
     * Applies one row through the published upsert inside a transaction of its own.
     *
     * <p>Assumptions: the transaction is begun and committed here rather than by a container, because
     * this case raises no application context. The writer declares mandatory propagation so that in
     * production it can never open a transaction of its own; that declaration is not enforced without
     * a transaction manager, and an explicit begin and commit is the equivalent guarantee -- the
     * statement runs inside a transaction, which its own flush requires.</p>
     *
     * <p>Assumptions: the implementation is reachable because it is package-private and this case sits
     * in the same package. What is asserted is the boolean the published interface returns, not the
     * statement behind it, so a writer that changed its mechanism while keeping its contract would
     * still pass.</p>
     *
     * @param row the fully projected fraud row to write
     * @return {@code true} when this call inserted the row, {@code false} when it updated one already
     *     present
     * @throws RuntimeException if the statement or the commit fails, rethrown after the rollback
     */
    private static boolean upsertInOwnTransaction(AuthFraud row) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            AuthFraudUpserterImpl upserter = new AuthFraudUpserterImpl();
            upserter.setEntityManager(entityManager);
            entityManager.getTransaction().begin();
            try {
                boolean inserted = upserter.upsert(row);
                entityManager.getTransaction().commit();
                return inserted;
            } catch (RuntimeException failure) {
                if (entityManager.getTransaction().isActive()) {
                    entityManager.getTransaction().rollback();
                }
                throw failure;
            }
        } finally {
            entityManager.close();
        }
    }

    /**
     * Builds a fraud row for one key, carrying the base authorization snapshot.
     *
     * <p>Assumptions: the row is projected from an authorization rather than assembled field by field,
     * because the entity publishes no other way to populate all twenty-six columns and reaching past
     * that factory would let this case write a combination the production path cannot produce.</p>
     *
     * @param cardNum the sixteen-character card the row belongs to
     * @param authTs the authorization instant that completes the key
     * @param fraudState the fraud indicator to record
     * @param reportDate the report date to record
     * @return a fully projected fraud row for that key
     */
    private static AuthFraud fraudRow(String cardNum, LocalDateTime authTs, String fraudState,
            LocalDate reportDate) {
        return AuthFraud.from(baseDetail(cardNum, "ACME HARDWARE"), authTs, ACCOUNT_ID, CUSTOMER_ID,
                fraudState, reportDate);
    }

    /**
     * Builds a row for one key whose every snapshot column differs from {@link #fraudRow}.
     *
     * <p>Assumptions: every snapshot value differs so the preservation assertion cannot pass by
     * coincidence. A superseding row carrying the same merchant and the same amounts would satisfy a
     * whole-row comparison whether the update direction respected its two-column restriction or
     * not.</p>
     *
     * @param cardNum the sixteen-character card the row belongs to
     * @param authTs the authorization instant that completes the key
     * @param fraudState the fraud indicator to record
     * @param reportDate the report date to record
     * @return a superseding row for that key, proposing a wholly different snapshot
     */
    private static AuthFraud supersedingRow(String cardNum, LocalDateTime authTs, String fraudState,
            LocalDate reportDate) {
        PendingAuthDetail different = new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE_KEY, AUTH_TIME_KEY),
                "220719", "101530", cardNum, "0200", "2812", "0200", "0001",
                "AUTH99", "05", "9999", "004000",
                new BigDecimal("999.99"), new BigDecimal("888.88"),
                "5812", "826", (short) 9, "MERCHANT999999", "SUPERSEDED MERCHANT",
                "SHELBYVILLE", "NY", "100010000", "TX9999999999999",
                PendingAuthDetail.MATCH_STATUS_DECLINED);
        return AuthFraud.from(different, authTs, ACCOUNT_ID, CUSTOMER_ID, fraudState, reportDate);
    }

    /**
     * Builds the authorization a fraud row is projected from.
     *
     * @param cardNum the card the authorization names
     * @param merchantName the merchant name to carry, exactly as given and never trimmed
     * @return a fabricated authorization carrying the fixed account and the given card
     */
    private static PendingAuthDetail baseDetail(String cardNum, String merchantName) {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE_KEY, AUTH_TIME_KEY),
                "220718", "091644", cardNum, "0100", "2712", "0100", "0000",
                "AUTH01", "00", "0000", "003000",
                TRANSACTION_AMOUNT, APPROVED_AMOUNT,
                "5411", "840", (short) 5, "MERCHANT000001", merchantName,
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }

    /**
     * Reads one fixed-width text column with its declared padding removed.
     *
     * <p>Assumptions: a fixed-width text column pads what it stores out to its declared width, so the
     * padding is a property of the column rather than of the value written. Trimming it is what lets a
     * case assert the value it supplied; the one column that must NOT be read this way is the
     * variable-width merchant name, whose padding IS the value, per F12 on this class.</p>
     *
     * @param row a whole-row reading
     * @param column the column to read
     * @return that column rendered as text with padding removed
     */
    private static String paddedText(Map<String, Object> row, String column) {
        return String.valueOf(row.get(column)).trim();
    }

    /**
     * Confirms the primary key is the ordered composite the migration declares, under its own name.
     *
     * <p>Assumptions: F1 as recorded on this class. The live catalogue is interrogated rather than the
     * migration text, because that text is the INPUT to this schema and reading it back would assert
     * only that a file says what it says. Ordinal position is what makes the result a key SHAPE rather
     * than an unordered set of names, so a key declared the other way round cannot pass by returning
     * its columns in a convenient order -- and the order matters, because a key over the timestamp
     * first would admit the same rows while making the card-prefixed range scan impossible.</p>
     *
     * @throws SQLException if the container refuses a connection or either catalogue query fails,
     *     which is a setup fault rather than the property under test and must surface as itself
     */
    @Test
    @DisplayName("pk_auth_fraud is the composite (card_num, auth_ts), in that order")
    void thePrimaryKeyIsTheOrderedCompositeOfCardAndTimestamp() throws SQLException {
        assertThat(queryColumn(PRIMARY_KEY_COLUMNS, SCHEMA_NAME, TABLE))
                .as("the primary key columns of %s.%s in ordinal order", SCHEMA_NAME, TABLE)
                .containsExactly("card_num", "auth_ts");
        assertThat(queryColumn(PRIMARY_KEY_CONSTRAINT_NAME, SCHEMA_NAME, TABLE))
                .as("the name the engine holds for that key")
                .containsExactly(PRIMARY_KEY_NAME);
    }

    /**
     * Confirms the fraud access path is TWO catalogue objects and not one.
     *
     * <p>Assumptions: F1 as recorded on this class -- this is the assertion the whole class exists for.
     * The set of index objects is read WHOLE rather than looked up by name, because the property is
     * that the mixed-direction index exists ALONGSIDE the key's own index; a name lookup would pass on
     * a schema where the two had been collapsed into one object carrying the wanted name, which is
     * exactly the shape the reference system had and the target cannot have.</p>
     *
     * @throws SQLException if the catalogue query fails, which is a setup fault
     */
    @Test
    @DisplayName("the fraud access path is two catalogue objects: the key's index and the recent index")
    void theFraudAccessPathIsTwoCatalogueObjects() throws SQLException {
        assertThat(queryColumn(INDEX_NAMES, SCHEMA_NAME, TABLE))
                .as("every index object on %s.%s", SCHEMA_NAME, TABLE)
                .containsExactly(RECENT_INDEX_NAME, PRIMARY_KEY_NAME);
    }

    /**
     * Confirms the two index objects are distinct, and that only one of them backs the primary key.
     *
     * <p>Assumptions: F1 as recorded on this class, taken to the level the claim actually requires.
     * Counting two names does not by itself establish that the descending index is a second object;
     * the catalogue flag that marks an index as the key's backing index is what separates them. It
     * must be set on the key's index and clear on the mixed-direction one, so the descending path
     * cannot be the key's own index wearing another name.</p>
     *
     * @throws SQLException if either catalogue query fails, which is a setup fault
     */
    @Test
    @DisplayName("only pk_auth_fraud backs the key; idx_auth_fraud_card_recent is a separate object")
    void theTwoIndexObjectsAreDistinctAndOnlyOneBacksTheKey() throws SQLException {
        assertThat(queryColumn(PRIMARY_BACKING_INDEX, SCHEMA_NAME, TABLE))
                .as("the one index the engine records as backing the primary key")
                .containsExactly(PRIMARY_KEY_NAME);
        assertThat(queryColumn(NON_PRIMARY_INDEXES, SCHEMA_NAME, TABLE))
                .as("every index on %s that does NOT back the key", TABLE)
                .containsExactly(RECENT_INDEX_NAME);
    }

    /**
     * Confirms the recent index ascends on the card and descends on the timestamp.
     *
     * <p>Assumptions: F2 as recorded on this class. Both readings of the direction are taken. The
     * per-column option bit is the machine-readable one and carries the column names alongside it, so
     * it settles which component descends rather than merely that one does. The engine's rendered
     * definition is the human-readable one and is checked for the descending marker as well; the
     * ascending spelling and the bare unmodified spelling are both asserted ABSENT, because an index
     * created over the same two columns with no direction modifiers would occupy the same catalogue
     * row under the same name and would satisfy any check that merely looked the name up.</p>
     *
     * @throws SQLException if either catalogue query fails, which is a setup fault
     */
    @Test
    @DisplayName("idx_auth_fraud_card_recent is (card_num ASC, auth_ts DESC) per the engine itself")
    void theRecentIndexDescendsOnTheTimestamp() throws SQLException {
        assertThat(queryColumn(INDEX_COLUMN_DIRECTIONS, SCHEMA_NAME, TABLE, RECENT_INDEX_NAME))
                .as("the per-column sort direction of %s, in key order", RECENT_INDEX_NAME)
                .containsExactly("card_num ASC", "auth_ts DESC");

        String definition =
                querySingleValue("the definition of " + RECENT_INDEX_NAME, INDEX_DEFINITION,
                        SCHEMA_NAME, TABLE, RECENT_INDEX_NAME);
        // WHY : Assumptions: the rendering is compared case-insensitively and by containment rather
        //       than as a whole statement. The engine reproduces its own definition in its own style,
        //       omitting an ascending marker it considers implicit and qualifying names as it sees fit,
        //       so an exact-string comparison would be brittle for reasons unrelated to direction.
        assertThat(definition.toLowerCase(Locale.ROOT))
                .as("the engine's rendering of %s, which must show the descending component",
                        RECENT_INDEX_NAME)
                .contains("card_num")
                .contains("auth_ts desc")
                .doesNotContain("auth_ts asc");
    }

    /**
     * Confirms the table carries exactly the twenty-six columns the reference declares.
     *
     * <p>Assumptions: F7 as recorded on this class. One count catches in a single assertion what a
     * column-by-column comparison would need twenty-six to catch, namely a column silently added or
     * dropped, and it is asserted against the constant declared on this class so the expected number
     * appears once rather than as a literal inside a case.</p>
     *
     * @throws SQLException if the catalogue query fails, which is a setup fault
     */
    @Test
    @DisplayName("auth_fraud carries exactly 26 columns, as the reference declaration states")
    void theTableCarriesExactlyTwentySixColumns() throws SQLException {
        assertThat(querySingleValue("the column count of " + TABLE, COLUMN_COUNT, SCHEMA_NAME, TABLE))
                .as("the number of columns the reference table declares")
                .isEqualTo(String.valueOf(DECLARED_COLUMN_COUNT));
    }

    /**
     * Confirms the table carries NO check constraint, and that the query saying so actually works.
     *
     * <p>Assumptions: F9 as recorded on this class, and this is a HARD prohibition rather than a
     * preference. The reference declares no check on this table -- neither on the match status nor on
     * the fraud indicator -- so nothing may be asserted here beyond that absence, and nothing may be
     * added to the migration to make a positive assertion possible: such a constraint would refuse
     * fraud rows the reference accepts. The sibling detail table's domain checks are real and are
     * asserted by the case that owns that table, not restated here.</p>
     *
     * <p>Assumptions: the same query is run twice, for the check kind and for the key kind, because a
     * negative assertion has to demonstrate that it CAN see something. The second call returning the
     * key proves the joins and the schema and table predicates resolve, so the first call's empty
     * answer is a genuine absence rather than a misspelled table name.</p>
     *
     * @throws SQLException if either catalogue query fails, which is a setup fault
     */
    @Test
    @DisplayName("auth_fraud carries no check constraint, and the catalogue query proves it can see one")
    void theTableCarriesNoCheckConstraint() throws SQLException {
        assertThat(queryColumn(CONSTRAINTS_OF_KIND, SCHEMA_NAME, TABLE, PRIMARY_KEY_KIND))
                .as("the key the catalogue can see, which shows this query resolves")
                .containsExactly(PRIMARY_KEY_NAME);
        assertThat(queryColumn(CONSTRAINTS_OF_KIND, SCHEMA_NAME, TABLE, CHECK_KIND))
                .as("the check constraints on %s, of which the reference declares none", TABLE)
                .isEmpty();
    }

    /**
     * Confirms the timestamp column carries microsecond precision and round-trips one unchanged.
     *
     * <p>Assumptions: F8 as recorded on this class. The declared precision is asserted from the
     * catalogue, and then a value carrying six significant fraction digits is written and read back
     * through the entity, because a column declared to six digits that silently truncated would still
     * report six. The rendered width is checked against the shared kernel's formatter as well, since
     * the twenty-six-character form is the contract the host field carries and the formatter is the one
     * place that width is defined.</p>
     *
     * <p>Assumptions: where a value reaches this table from the millisecond-resolution authorization
     * key, its low three microsecond digits are structurally zero. That is a property of the source
     * data and not a loss of precision here; the column still carries six digits, which is why this
     * case supplies a value that uses all six rather than one that happens to end in zeros.</p>
     *
     * @throws SQLException if the catalogue query fails, which is a setup fault
     */
    @Test
    @DisplayName("auth_ts is TIMESTAMP(6) and a microsecond instant round-trips exactly")
    void theTimestampColumnCarriesMicrosecondPrecision() throws SQLException {
        assertThat(querySingleValue("the declared type of auth_ts", COLUMN_TYPE, SCHEMA_NAME, TABLE,
                        "auth_ts"))
                .as("the declared type and fractional precision of auth_ts")
                .isEqualTo("timestamp without time zone(6)");

        LocalDate reportDate = readThrough(AuthFraudRepository::currentDate);
        upsertInOwnTransaction(
                fraudRow(PRECISION_CARD_NUMBER, PRECISE_AUTH_TS, REPORTED, reportDate));

        AuthFraudKey key = new AuthFraudKey(PRECISION_CARD_NUMBER, PRECISE_AUTH_TS);
        Optional<AuthFraud> stored = readThrough(repository -> repository.findById(key));
        assertThat(stored).as("the row written at a microsecond-precise instant").isPresent();
        assertThat(stored.orElseThrow().getId().getAuthTs())
                .as("the instant the column returns, which must not have been truncated")
                .isEqualTo(PRECISE_AUTH_TS);
        assertThat(TimestampFormatter.format(PRECISE_AUTH_TS))
                .as("the rendered form of that instant, at the width the host field declares")
                .hasSize(TimestampFormatter.TIMESTAMP_LENGTH);
    }

    /**
     * Confirms the merchant name is variable-width and keeps every character it was given.
     *
     * <p>Assumptions: F12 as recorded on this class. The declared type is read from the catalogue, and
     * then a value padded out to the full declared width is written and read back WITHOUT trimming,
     * because the reference transmits the padding rather than measuring the content. Reading this one
     * column through the padding-removing helper would destroy the very property under assertion,
     * which is why the helper is not used here and says so.</p>
     *
     * @throws SQLException if the catalogue query or the whole-row read fails, which is a setup fault
     */
    @Test
    @DisplayName("merchant_name is VARCHAR(22) and a padded value keeps its full width")
    void theMerchantNameKeepsItsFullDeclaredWidth() throws SQLException {
        assertThat(querySingleValue("the declared type of merchant_name", COLUMN_TYPE, SCHEMA_NAME,
                        TABLE, "merchant_name"))
                .as("the declared type and width of merchant_name")
                .isEqualTo("character varying(22)");

        LocalDate reportDate = readThrough(AuthFraudRepository::currentDate);
        AuthFraud padded = AuthFraud.from(
                baseDetail(PADDING_CARD_NUMBER, PADDED_MERCHANT_NAME), LATER_AUTH_TS, ACCOUNT_ID,
                CUSTOMER_ID, REPORTED, reportDate);
        upsertInOwnTransaction(padded);

        Map<String, Object> row = readWholeRow(PADDING_CARD_NUMBER, LATER_AUTH_TS);
        assertThat(row.get("merchant_name"))
                .as("the merchant name as stored, padding included because the reference sends it")
                .isEqualTo(PADDED_MERCHANT_NAME);
        assertThat(String.valueOf(row.get("merchant_name")))
                .as("the stored width, which the reference holds at the column's full declared width")
                .hasSize(22);
    }

    /**
     * Confirms the insert direction reports an insert and lands every one of the twenty-six columns.
     *
     * <p>Assumptions: F10 and F11 as recorded on this class. The boolean the published writer returns
     * is the discriminator, and it is asserted directly rather than inferred from a row count, because
     * the caller has to choose between two operator-visible messages on the strength of it. Every
     * column is then compared, so the case also establishes that the insert direction writes the whole
     * snapshot rather than only the columns the update direction later moves.</p>
     *
     * <p>Assumptions: the fixed-width text columns are compared with their declared padding removed,
     * because that padding belongs to the column; the variable-width merchant name is compared as
     * stored. The two money columns are compared by value rather than by representation, so a scale
     * that widened without changing the amount does not fail the case for the wrong reason.</p>
     *
     * @throws SQLException if the whole-row read fails, which is a setup fault
     */
    @Test
    @DisplayName("the insert direction reports an insert and every one of the 26 columns lands")
    void theInsertDirectionReportsAnInsertAndLandsEveryColumn() throws SQLException {
        LocalDate reportDate = readThrough(AuthFraudRepository::currentDate);
        AuthFraudKey key = new AuthFraudKey(INSERT_CARD_NUMBER, LATER_AUTH_TS);
        // WHY : Assumptions: the helper's result is bound to a TYPED local before it is asserted on.
        //       Handing a generic method's result straight to the matcher leaves the type variable to be
        //       inferred from an overload set that includes a predicate form, and the compiler then
        //       reports an ambiguity rather than the mismatch a reader would expect to see.
        Optional<AuthFraud> beforeInsert = readThrough(repository -> repository.findById(key));
        assertThat(beforeInsert)
                .as("no row may exist before the insert direction runs")
                .isEmpty();

        boolean inserted =
                upsertInOwnTransaction(fraudRow(INSERT_CARD_NUMBER, LATER_AUTH_TS, REPORTED,
                        reportDate));

        assertThat(inserted)
                .as("the discriminator must report an INSERT, which selects the add message")
                .isTrue();

        Map<String, Object> row = readWholeRow(INSERT_CARD_NUMBER, LATER_AUTH_TS);
        assertThat(row)
                .as("the stored row must expose exactly the declared column set")
                .hasSize(DECLARED_COLUMN_COUNT);
        assertThat(row.get("card_num")).as("the card half of the key").isEqualTo(INSERT_CARD_NUMBER);
        assertThat(row.get("auth_ts")).as("the instant half of the key").isNotNull();
        assertThat(paddedText(row, "auth_type")).as("the authorization type").isEqualTo("0100");
        assertThat(paddedText(row, "card_expiry_date")).as("the card expiry").isEqualTo("2712");
        assertThat(paddedText(row, "message_type")).as("the message type").isEqualTo("0100");
        assertThat(paddedText(row, "message_source")).as("the message source").isEqualTo("0000");
        assertThat(paddedText(row, "auth_id_code")).as("the authorization code").isEqualTo("AUTH01");
        assertThat(paddedText(row, "auth_resp_code")).as("the response code").isEqualTo("00");
        assertThat(paddedText(row, "auth_resp_reason")).as("the response reason").isEqualTo("0000");
        assertThat(paddedText(row, "processing_code")).as("the processing code").isEqualTo("003000");
        assertThat((BigDecimal) row.get("transaction_amt"))
                .as("the transaction amount, compared by value at the declared scale")
                .isEqualByComparingTo(TRANSACTION_AMOUNT);
        assertThat((BigDecimal) row.get("approved_amt"))
                .as("the approved amount, compared by value at the declared scale")
                .isEqualByComparingTo(APPROVED_AMOUNT);
        // WHY : Assumptions: the reference spells this column MERCHANT_CATAGORY_CODE, at
        //       ddl/AUTHFRDS.ddl L14, and the target column is merchant_category_code; the divergence is
        //       documented in the schema mapping. The name is written here in its target spelling
        //       because the catalogue is what this read addresses, and a reader comparing the two
        //       declarations needs to know the difference is deliberate rather than a typing slip.
        assertThat(paddedText(row, "merchant_category_code"))
                .as("the merchant category the authorization carried")
                .isEqualTo("5411");
        assertThat(paddedText(row, "acqr_country_code")).as("the acquirer country").isEqualTo("840");
        assertThat(row.get("pos_entry_mode")).as("the entry mode").hasToString("5");
        assertThat(paddedText(row, "merchant_id")).as("the merchant").isEqualTo("MERCHANT000001");
        assertThat(row.get("merchant_name")).as("the merchant name").isEqualTo("ACME HARDWARE");
        assertThat(paddedText(row, "merchant_city")).as("the merchant city").isEqualTo("SPRINGFIELD");
        assertThat(paddedText(row, "merchant_state")).as("the merchant state").isEqualTo("IL");
        assertThat(paddedText(row, "merchant_zip")).as("the merchant zip").isEqualTo("627040000");
        assertThat(paddedText(row, "transaction_id"))
                .as("the transaction the authorization names")
                .isEqualTo("TX0000000000001");
        assertThat(paddedText(row, "match_status"))
                .as("the match status the authorization carried")
                .isEqualTo(PendingAuthDetail.MATCH_STATUS_PENDING);
        assertThat(paddedText(row, "auth_fraud")).as("the fraud indicator").isEqualTo(REPORTED);
        assertThat(String.valueOf(row.get("fraud_rpt_date")))
                .as("the report date, which the engine supplied")
                .isEqualTo(reportDate.toString());
        assertThat(row.get("acct_id")).as("the account the row names").hasToString("111111111");
        assertThat(row.get("cust_id")).as("the customer the row names").hasToString("222222222");
    }

    /**
     * Confirms the update direction reports an update, moves two columns and leaves twenty-four alone.
     *
     * <p>Assumptions: F6, F10 and F11 as recorded on this class -- this is the narrowness claim, and
     * the twenty-four-column comparison is what makes it testable rather than merely stated. A
     * superseding row is applied whose EVERY snapshot column differs from the one first written, so a
     * widened update arm cannot escape detection by proposing the same values; the two permitted movers
     * are removed from both readings and what remains is compared whole.</p>
     *
     * <p>Assumptions: F5 as recorded on this class. The report date is compared against the engine's
     * own current date, read back over a separate connection in the same session, so the assertion
     * cannot be satisfied by this process's clock and no date literal appears in it.</p>
     *
     * @throws SQLException if either whole-row read or the date read fails, which is a setup fault
     */
    @Test
    @DisplayName("the update direction reports an update, moves two columns and leaves 24 untouched")
    void theUpdateDirectionReportsAnUpdateAndMovesOnlyTwoColumns() throws SQLException {
        LocalDate firstReport = readThrough(AuthFraudRepository::currentDate);
        assertThat(upsertInOwnTransaction(
                        fraudRow(CONFLICT_CARD_NUMBER, LATER_AUTH_TS, REPORTED, firstReport)))
                .as("the first application of this key must take the insert direction")
                .isTrue();
        Map<String, Object> before = readWholeRow(CONFLICT_CARD_NUMBER, LATER_AUTH_TS);

        LocalDate secondReport = readThrough(AuthFraudRepository::currentDate);
        boolean inserted = upsertInOwnTransaction(
                supersedingRow(CONFLICT_CARD_NUMBER, LATER_AUTH_TS, RESOLVED, secondReport));

        assertThat(inserted)
                .as("the discriminator must report an UPDATE, which selects the update message")
                .isFalse();

        Map<String, Object> after = readWholeRow(CONFLICT_CARD_NUMBER, LATER_AUTH_TS);
        assertThat(paddedText(after, "auth_fraud"))
                .as("the fraud indicator is one of the two columns the reference update names")
                .isEqualTo(RESOLVED);
        assertThat(String.valueOf(after.get("fraud_rpt_date")))
                .as("the report date is the other, and it is the engine's own date")
                .isEqualTo(secondReport.toString());

        assertThat(withoutMutableColumns(after))
                .as("every column the reference update does not name must survive unchanged")
                .isEqualTo(withoutMutableColumns(before));
        assertThat(withoutMutableColumns(after))
                .as("the surviving columns are the snapshot itself, so there are 24 of them")
                .hasSize(DECLARED_COLUMN_COUNT - MUTABLE_COLUMNS.size());
    }

    /**
     * Confirms the absent-only insert attempts the write and declines a key already present.
     *
     * <p>Assumptions: F4 as recorded on this class. This is the repository's own conflict-aware
     * statement, and its shape is the point: it ATTEMPTS the insert and lets the engine decide, rather
     * than probing first and branching, which is what the reference does when it tests the returned
     * duplicate-key condition. The affected-row count is the discriminator here -- one on the path that
     * wrote, zero on the path that found the key taken -- and a zero must leave the stored row exactly
     * as it was, since this statement has no update arm at all.</p>
     *
     * <p>Assumptions: the second attempt is applied in its OWN transaction. This engine aborts a whole
     * transaction on the first statement that raises, so an attempt that was refused rather than
     * absorbed would poison anything sharing its transaction and the following read would fail for a
     * reason unconnected to the property asserted.</p>
     *
     * @throws SQLException if the whole-row read fails, which is a setup fault
     */
    @Test
    @DisplayName("insertFraudRowIfAbsent writes once, then declines the taken key without changing it")
    void theAbsentOnlyInsertWritesOnceThenDeclines() throws SQLException {
        LocalDate reportDate = readThrough(AuthFraudRepository::currentDate);
        AuthFraud first = fraudRow(GUARD_CARD_NUMBER, LATER_AUTH_TS, REPORTED, reportDate);

        // WHY : Assumptions: each affected-row count is bound to a TYPED local before it is asserted
        //       on, for the same reason the insert-direction case records: a generic result handed
        //       straight to the matcher is inferred against an overload set carrying a predicate form,
        //       and the compiler reports an ambiguity instead of the type it actually found.
        int firstAttempt = inTransaction(repository -> repository.insertFraudRowIfAbsent(first));
        assertThat(firstAttempt)
                .as("the first attempt finds the key free and writes one row")
                .isEqualTo(1);
        Map<String, Object> afterFirst = readWholeRow(GUARD_CARD_NUMBER, LATER_AUTH_TS);

        AuthFraud second = supersedingRow(GUARD_CARD_NUMBER, LATER_AUTH_TS, RESOLVED, reportDate);
        int secondAttempt = inTransaction(repository -> repository.insertFraudRowIfAbsent(second));
        assertThat(secondAttempt)
                .as("the second attempt finds the key taken and writes nothing")
                .isEqualTo(0);

        assertThat(readWholeRow(GUARD_CARD_NUMBER, LATER_AUTH_TS))
                .as("a declined attempt has no update arm, so the whole stored row is unchanged")
                .isEqualTo(afterFirst);
    }

    /**
     * Confirms the card-recent access path reads newest first and does not cross into another card.
     *
     * <p>Assumptions: the two properties are asserted together because neither means much alone. An
     * ordering assertion over one card's rows would pass on a read carrying no card predicate at all,
     * and a card-isolation assertion would pass on a read that returned the oldest row first. The other
     * card's authorization is dated BETWEEN this card's two, so a read that ignored the predicate would
     * return it in the middle of the result and fail on order as well as on membership.</p>
     *
     * <p>Refactoring Rationale: the access path is exercised by a STATEMENT here rather than through a
     * repository method, because the interface deliberately publishes no card-history reader. That
     * withdrawal is argued on the interface itself, on the ground that an uncalled query whose first
     * parameter is an unmasked account number invites a fraud-history route to be built by calling it,
     * and the same paragraph directs that the surviving obligation be asserted against the schema. What
     * the parity requirement names is the INDEX, and this case shows the index answers the question it
     * was declared for.</p>
     *
     * @throws SQLException if the read fails, which fails the case rather than skipping it
     */
    @Test
    @DisplayName("the card-recent path answers newest first and only for the card asked about")
    void theCardHistoryReadsNewestFirstAndIsCardIsolated() throws SQLException {
        LocalDate reportDate = readThrough(AuthFraudRepository::currentDate);
        upsertInOwnTransaction(fraudRow(CARD_NUMBER, EARLIER_AUTH_TS, REPORTED, reportDate));
        upsertInOwnTransaction(fraudRow(CARD_NUMBER, LATER_AUTH_TS, RESOLVED, reportDate));
        upsertInOwnTransaction(fraudRow(OTHER_CARD_NUMBER,
                LocalDateTime.of(2022, 3, 14, 15, 0, 0, 500_000_000), REPORTED, reportDate));

        assertThat(queryColumn(CARD_HISTORY_NEWEST_FIRST, CARD_NUMBER))
                .as("the whole history of the card under test, newest first, and nothing else")
                .containsExactly(renderedInstant(LATER_AUTH_TS), renderedInstant(EARLIER_AUTH_TS));
    }

    /**
     * Confirms the report date comes from the engine and not from this process.
     *
     * <p>Assumptions: F5 as recorded on this class. The interface's own date reader is compared with
     * the engine's current date read over a separate connection, never with this process's clock.
     * Comparing against the clock is the assertion that would pass while the property was broken,
     * because the two agree whenever container and runner share a zone -- which is nearly always, and
     * never at the boundary where the difference matters.</p>
     *
     * @throws SQLException if the direct read fails, which is a setup fault
     */
    @Test
    @DisplayName("currentDate() returns the engine's date, not this process's")
    void theReportDateComesFromTheEngine() throws SQLException {
        String engineDate = querySingleValue("the engine's current date", "SELECT CURRENT_DATE::text");
        assertThat(readThrough(AuthFraudRepository::currentDate))
                .as("the date a fraud report is stamped with")
                .isEqualTo(LocalDate.parse(engineDate));
    }

    /**
     * Renders one instant the way the engine renders a timestamp column, for a text comparison.
     *
     * <p>Assumptions: the engine separates the date and the time with a space where this platform's own
     * rendering uses a letter, and both drop trailing zeros in the fraction identically, so swapping
     * the separator is the whole of the difference. Comparing rendered text rather than parsed values
     * is deliberate: it is the ORDER the query returned that is under assertion, and text preserves it
     * without a second conversion that could mask a difference.</p>
     *
     * @param instant the instant to render
     * @return that instant in the engine's own rendering of a timestamp
     */
    private static String renderedInstant(LocalDateTime instant) {
        return instant.toString().replace('T', ' ');
    }
}
