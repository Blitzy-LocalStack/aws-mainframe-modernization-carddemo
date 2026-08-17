package com.carddemo.reporting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.money.Money;
import com.carddemo.reporting.domain.AccountView;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hibernate.annotations.Immutable;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.Repository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Proves the account lookup addresses one row by a whole key, at twelve-digit precision, unwritably.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@code StatementAccountRepository} stands in for exactly one data definition of one job step.
 * {@code app/jcl/CREASTMT.JCL} runs the statement generator at its L79 and supplies it four input
 * definitions across L83 to L86, of which the THIRD is {@code //ACCTFILE} at L85 -- L86 being
 * {@code //CUSTFILE}, a distinction the case below asserts against the job itself rather than
 * asking to be believed. {@code app/cbl/CBSTM03B.CBL} selects that definition at its L49 with
 * {@code ACCESS MODE  IS RANDOM} at L51 and {@code RECORD KEY   IS FD-ACCT-ID} at L52, and its
 * dispatcher branch at L206 to L229 implements an open arm at L208, a KEYED read arm at L213 and a
 * close arm at L220 with no plain-sequential arm at all. The caller reaches it once per
 * cross-reference row through {@code 3000-ACCTFILE-GET.} at L392 of {@code app/cbl/CBSTM03A.CBL}.
 * Four properties of that read are invisible to every cheaper gate in this module and are asserted
 * here against a real engine carrying the shipped definitions: the surface is one keyed lookup and
 * nothing else, the key is a whole eleven-digit identifier supplied by the driving cross-reference,
 * the money it carries is exact at twelve digits and scale two, and an identifier the account master
 * does not hold yields nothing that a caller can consume by accident.</p>
 *
 * <h2>What this class asserts that no sibling does</h2>
 *
 * <p>Assumptions: the four classes already in this directory divide the module's engine-backed
 * properties between them, and this one takes the properties none of them reaches rather than
 * restating any of theirs. {@code ReportingQueryBootstrapIT} establishes no relation and proves the
 * declared queries parse; {@code StatementHeadingChunkIT} seeds stand-in tables and proves the
 * heading walk's keyset predicate reproduces its own ordering; {@code ReportingDeployedRelationIT}
 * applies the shipped definitions and proves the projections narrow a card number, that a card joins
 * to a customer and an account, and that the login role cannot write a relation it can read;
 * {@code StatementCardXrefRepositoryIT} owns the driving cursor, the two-level write refusal against
 * a base table in another context's schema, and the fifty-byte cross-reference geometry. The
 * overlap with the third of those is deliberately narrow and is worth naming: it establishes only
 * that a joined heading's account RESOLVES, by an existence check at its L459 to L464 and a relative
 * comparison of the joined balance against the projection's own at its L478 to L480. It asserts no
 * absolute value, no precision, no scale, no column name, no key width and no absence. Every
 * property below is one of those.</p>
 *
 * <h2>Why the shipped definitions are applied rather than reproduced</h2>
 *
 * <p>Assumptions: every relation read below is created by executing the shipped files themselves,
 * read from the working tree and handed to the engine unchanged. Nothing here authors a schema
 * object of any kind -- no relation, no privilege, no index -- and no statement below defines one.
 * The authority for the schemas, the roles and the privileges is
 * {@code data-migration/sql/V0__schemas_and_roles.sql}; the authority for the projections is
 * {@code data-migration/sql/V1__reporting_views.sql}; the base tables beneath them belong to four
 * other services' migrations. Register entry <b>R11</b> in the main-tree charter records that a
 * relation missing at run time is a defect to report against whichever of those files owns it, never
 * something for a class here to remedy, and that this login role could not create it in any case.
 * The charter beside this file draws the same boundary on DEFINITIONS rather than on verbs: inserting
 * rows is permitted because a fixture has to be loaded before anything can be read.</p>
 *
 * <p>Alternatives Considered: a hand-written stand-in relation, which is what the sibling ordering
 * class uses. Rejected here because three of the properties below live entirely outside Java -- the
 * declared precision and scale of the two published money columns, the projected column name that
 * differs from the baseline field name, and the ABSENCE of the owning table's optimistic-locking
 * column from the projection. A stand-in would assert all three of the stand-in and nothing about
 * what ships.</p>
 *
 * <h2>The two contracts this class exists to keep apart</h2>
 *
 * <p>Assumptions: the account master's money is one digit WIDER than the statement transaction's,
 * and the two are never unified in either direction. {@code ACCT-CURR-BAL PIC S9(10)V99} at L7 of
 * {@code app/cpy/CVACT01Y.cpy} is twelve significant digits over twelve bytes of zoned decimal, and
 * L8, L9, L13 and L14 declare the same picture for four siblings; the statement transaction amount is
 * {@code PIC S9(09)V99}, eleven digits over eleven bytes. Widening or aliasing either to match the
 * other shifts every field after it in the record by one byte and moves the implied decimal point,
 * so a balance would read as ten times or a tenth of itself while every row still decoded without
 * complaint. {@code com.carddemo.reporting.domain.AccountView} keeps them apart structurally by
 * writing its precision and scale as literals on each column and by nesting its own attribute
 * converter rather than sharing one, and the cases below assert the outcome of that choice.</p>
 *
 * <h2>Parameters, return values, exceptions or errors</h2>
 *
 * <p>This is a test class with no constructor a caller invokes, no value it yields and no exception
 * it raises outside the test engine, so the type accepts no parameter, returns nothing and raises
 * nothing. The inapplicability is stated rather than passed over, because user-specified Rule 1
 * (Explainability) forbids a docstring that omits parameters, return values or exceptions.</p>
 */
@Testcontainers
@SpringBootTest(
        classes = StatementAccountRepositoryIT.AccountLookupTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    // WHAT:
    //       The narrowest property set that lets a context in this module start at all.
    // WHY : Assumptions: this module carries cloud starters as compile dependencies, so a context
    //       that enables auto-configuration builds their beans, the region provider resolves eagerly
    //       and raises when it finds none, and both remote configuration importers otherwise read a
    //       remote store while the environment is being prepared. Nothing below contacts a cloud
    //       service, so the region identifies nothing this class reaches. The values are supplied
    //       here rather than in application-test.yml because that document's own register of
    //       deliberate omissions rules that a cloud-service value belongs to the test that needs it.
    "spring.cloud.aws.region.static=us-east-1",
    // WHAT:
    //       Two switches that turn the remote configuration importers off.
    // WHY : Alternatives Considered: the five-property form two siblings use, which leaves both
    //       importers enabled and supplies fabricated access-key and signing strings to satisfy their
    //       eager credential resolution. Rejected here in favour of the three-property form the
    //       driving-cursor sibling uses, because switching the importers off removes the need for
    //       either string, so this class commits no credential-shaped value of any kind. Assumptions:
    //       the second key's NAME references a remote store while its VALUE is precisely what stops
    //       one from being read, so neither key is a credential, an endpoint, a location or an
    //       identifier -- this class makes no cloud call for a credential to sign.
    "spring.cloud.aws.parameterstore.enabled=false",
    "spring.cloud.aws.secretsmanager.enabled=false",
    // WHAT:
    //       The recorder every statement the persistence provider emits passes through.
    // WHY : Alternatives Considered: proving the optimistic-locking column is never named by reading
    //       the projection's column list alone, which shows the column is not even available to be
    //       named. Kept as one of the three proofs below rather than as the only one, because it
    //       establishes a property of the RELATION and not of the statement the provider generates:
    //       a provider that mapped a version attribute would emit a statement naming a column the
    //       view does not publish, and that failure reads as a missing column rather than as the
    //       write capability it would actually signal. Recording the statements observes the
    //       generated text directly, and the case fails loudly if this key ever stops binding
    //       because it also requires the recorder to have seen the read it asserts about.
    "spring.jpa.properties.hibernate.session_factory.statement_inspector="
            + "com.carddemo.reporting.repository.StatementAccountRepositoryIT$EmittedStatements"
})
class StatementAccountRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every other integration test in this repository pins, so
     * the whole suite validates against one engine build. It is a digest rather than a tag because a
     * publisher moves a major-line tag to each new minor release, and the properties asserted here
     * that depend on the engine -- exact fixed-point arithmetic through a numeric column, and the
     * catalogue's own account of what a view publishes -- are ones an engine version can change.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The two session settings the shipped bootstrap requires before it will run.
     *
     * <p>Assumptions: both are opt-outs the bootstrap reads through {@code current_setting} with its
     * missing-value flag set, and it raises rather than proceeding when neither is on: one
     * acknowledges an unencrypted local connection, the other accepts roles arriving without a
     * credential because their source is a remote store no test has. They are session settings and
     * are applied as such below -- the engine's own client accepts several statement options
     * intermixed with a file option and runs them in ONE session, so a setting made before the file
     * is still in force while the file executes. Alternatives Considered: recording them on the
     * database instead. Rejected because that form is a schema-alteration statement, and this
     * directory's charter draws its boundary on definitions: executing an authoritative definition
     * unchanged is permitted, authoring one is not, and a database-level setting written here would
     * be a definition this class authored.</p>
     */
    private static final List<String> BOOTSTRAP_ACKNOWLEDGEMENTS = List.of(
            "carddemo.bootstrap_allow_insecure",
            "carddemo.bootstrap_allow_missing_credentials");

    /**
     * The shipped definition files, repository-relative, in the order an operator applies them.
     *
     * <p>Assumptions: the bootstrap appears TWICE and the repetition is required rather than
     * defensive. Its first pass creates the schemas and the roles; the cross-schema read privileges
     * it conveys to the projection owner are conditional on the base tables existing, and on that
     * pass none of them does. The projections run with their owner's rights, so a privilege missing
     * at creation surfaces on the first READ rather than at creation -- which is exactly the failure
     * the second pass removes. Applying the files in the order an operator applies them is therefore
     * part of what this class establishes, and the list is identical to the one both sibling classes
     * that apply the shipped definitions use, so a moved migration breaks all three together rather
     * than leaving one silently reading a stale arrangement.</p>
     */
    private static final List<String> DEPLOYED_SCHEMA_SCRIPTS = List.of(
            "data-migration/sql/V0__schemas_and_roles.sql",
            "services/account-service/src/main/resources/db/migration/V1__account.sql",
            "services/account-service/src/main/resources/db/migration/"
                    + "V2__account_inquiry_reply_ledger.sql",
            "services/card-service/src/main/resources/db/migration/V1__card.sql",
            "services/card-service/src/main/resources/db/migration/V2__card_num_digit_domain.sql",
            "services/reference-service/src/main/resources/db/migration/V1__reference.sql",
            "services/transaction-service/src/main/resources/db/migration/V1__ledger.sql",
            "services/transaction-service/src/main/resources/db/migration/"
                    + "V2__ledger_transaction_id_allocator.sql",
            "services/transaction-service/src/main/resources/db/migration/"
                    + "V3__ledger_bytewise_collation.sql",
            "data-migration/sql/V0__schemas_and_roles.sql",
            "data-migration/sql/V1__reporting_views.sql");

    /**
     * The classpath directory holding the fixture corpus this class loads.
     */
    private static final String FIXTURE_DIRECTORY = "fixtures";

    /**
     * The account master fixture, bound by exact name.
     */
    private static final String ACCOUNT_FIXTURE = "acctfile.txt";

    /**
     * The statement-path cross-reference fixture, bound by exact name.
     *
     * <p>Assumptions: this file and {@code cardxref.txt} are DIFFERENT fixtures for the same
     * fifty-byte record under two different data-definition names, and conflating them would put an
     * assertion on the wrong path. This one is named for the {@code XREFFILE} definition at
     * {@code app/jcl/CREASTMT.JCL} L84, which the statement generator reads and which supplies the
     * account identifier this class looks up; the other is named for the {@code CARDXREF} definition
     * at {@code app/jcl/TRANREPT.jcl} L67 and L68, which the report writer reads and which the
     * sibling deployed-relation class loads. This class binds the statement-path file because the
     * account master is a statement-path input only.</p>
     */
    private static final String STATEMENT_PATH_XREF_FIXTURE = "xreffile.txt";

    /**
     * The registered copybook descriptor the account fixture decodes against.
     */
    private static final String ACCOUNT_DESCRIPTOR = "ACCOUNT";

    /**
     * The registered copybook descriptor the cross-reference fixture decodes against.
     */
    private static final String XREF_DESCRIPTOR = "XREF";

    /**
     * The declared length of the account record, three hundred bytes.
     *
     * <p>Assumptions: derived by SUMMING the declared field widths of
     * {@code app/cpy/CVACT01Y.cpy} and never by trusting the banner at its L2, because a banner is a
     * comment and a comment cannot be wrong in a way the compiler notices -- here the two agree. It is
     * corroborated independently by the file description at {@code app/cbl/CBSTM03B.CBL} L75 to L78,
     * whose two fields are eleven digits and two hundred and eighty-nine characters, and a third time
     * by measurement of the committed fixture.</p>
     */
    private static final int ACCOUNT_RECORD_LENGTH = 300;

    /**
     * The key width the account file description declares, eleven digits.
     */
    private static final int FILE_DESCRIPTION_KEY_DIGITS = 11;

    /**
     * The data width the account file description declares beside its key, two hundred and
     * eighty-nine characters.
     *
     * <p>Assumptions: one artifact of the baseline is recorded here because a reader summing these two
     * numbers will meet it and should not conclude the arithmetic is wrong. The same field name
     * {@code FD-ACCT-DATA} is declared twice with two different widths -- three hundred and eighteen
     * characters at {@code app/cbl/CBSTM03B.CBL} L63, inside the transaction record, and this one at
     * L78, inside the account record. The two are separate group items in separate record
     * descriptions, so the baseline behaves exactly as written and both sums hold. The baseline
     * declares the name twice, the target carries two distinct projections, and the divergence is
     * documented rather than resolved here. Nothing in this directory edits, or may edit, that
     * source.</p>
     */
    private static final int FILE_DESCRIPTION_DATA_WIDTH = 289;

    /**
     * The declared digit count of the account identifier, eleven.
     *
     * <p>Assumptions: eleven is what makes the keyed read a WHOLE-key read rather than a prefix read,
     * and the caller establishes it rather than the callee. {@code app/cbl/CBSTM03A.CBL} L396 moves
     * {@code XREF-ACCT-ID} into the shared key buffer, L397 moves a transient zero into the key-length
     * field, and L398 IMMEDIATELY recomputes that field to
     * {@code COMPUTE WS-M03B-KEY-LN = LENGTH OF XREF-ACCT-ID}, which is eleven per
     * {@code app/cpy/CVACT03Y.cpy} L7. The transient zero is never the value the callee reads, and a
     * census of the whole baseline for a zero-length reference modification returns nothing at
     * all.</p>
     */
    private static final int ACCOUNT_KEY_DIGITS = 11;

    /**
     * The declared digit count of the customer identifier, nine.
     *
     * <p>Assumptions: this is carried as a contrast and not as a value this class looks anything up
     * by. The two keyed lookups of the statement path have DIFFERENT key widths -- nine for the
     * customer at {@code app/cpy/CVACT03Y.cpy} L6 and eleven for the account at its L7 -- so a reader
     * who took one branch's evidence for the other's would read the wrong number of bytes out of the
     * one shared key buffer that serves all four definitions.</p>
     */
    private static final int CUSTOMER_KEY_DIGITS = 9;

    /**
     * The one-based first byte of the account identifier within the cross-reference record,
     * twenty-six.
     */
    private static final int XREF_ACCOUNT_ID_FIRST_BYTE = 26;

    /**
     * The one-based last byte of the account identifier within the cross-reference record,
     * thirty-six.
     */
    private static final int XREF_ACCOUNT_ID_LAST_BYTE = 36;

    /**
     * The copybook field name of the account identifier inside the cross-reference record.
     */
    private static final String XREF_ACCOUNT_ID_FIELD = "XREF-ACCT-ID";

    /**
     * The copybook field name of the card number inside the cross-reference record.
     */
    private static final String XREF_CARD_NUMBER_FIELD = "XREF-CARD-NUM";

    /**
     * The copybook field name of the customer identifier inside the cross-reference record.
     */
    private static final String XREF_CUSTOMER_ID_FIELD = "XREF-CUST-ID";

    /**
     * The copybook field name of the account identifier inside the account record.
     */
    private static final String ACCOUNT_ID_FIELD = "ACCT-ID";

    /**
     * The copybook name of the trailing pad that carries the account record to its declared length.
     */
    private static final String PADDING_FIELD = "FILLER";

    /**
     * The one-based first byte of the account record's trailing pad, one hundred and twenty-three.
     */
    private static final int PADDING_FIRST_BYTE = 123;

    /**
     * The physical width of the account record's trailing pad, one hundred and seventy-eight bytes.
     */
    private static final int PADDING_WIDTH = 178;

    /**
     * The byte width of one account money field, twelve.
     *
     * <p>Assumptions: twelve bytes is what {@code PIC S9(10)V99} occupies as zoned decimal with the
     * sign overpunched into the final byte -- ten integer digit positions plus two fractional ones,
     * one byte each, with no separate sign byte and no stored decimal point. That is the regime the
     * base masters use throughout, and it is corroborated from outside the COBOL by
     * {@code app/jcl/PRTCATBL.jcl}, whose sort symbol declarations at L47 to L50 type every numeric
     * field {@code ZD} -- L50 reads {@code TRAN-CAT-BAL,18,11,ZD} -- and whose output record at L53
     * to L56 renders that field through {@code EDIT=(TTTTTTTTT.TT)}, a twelve-character mask that
     * keeps leading zeros and emits no sign, provably a different rendering from the zero-suppression
     * masks the report program uses.</p>
     */
    private static final int MONEY_FIELD_WIDTH = 12;

    /**
     * The integer digit positions one account money field declares, ten.
     */
    private static final int MONEY_INTEGER_DIGITS = 10;

    /**
     * The fractional digit positions one account money field declares, two.
     */
    private static final int MONEY_FRACTION_DIGITS = 2;

    /**
     * The declared numeric precision of a published account money column, twelve.
     *
     * <p>Assumptions: twelve is the total significant digits of {@code PIC S9(10)V99} and is the
     * precision the owning migration declares on all five money columns of the account table. It must
     * NEVER be unified with the statement transaction projection's eleven: one integer digit more or
     * fewer shifts every field after it in the record and moves the implied decimal point, so a
     * balance would read as ten times or a tenth of itself while every row still decoded without
     * complaint.</p>
     */
    private static final int PUBLISHED_MONEY_PRECISION = 12;

    /**
     * The declared numeric scale of a published account money column, two.
     */
    private static final int PUBLISHED_MONEY_SCALE = 2;

    /**
     * The declared numeric precision of the statement transaction amount, eleven.
     *
     * <p>Assumptions: this is carried only so the case below can assert the two precisions are
     * DIFFERENT. The transaction amount is {@code PIC S9(09)V99}, eleven digits over eleven bytes, and
     * naming it here is what turns a claim about non-unification into something an assertion can
     * fail.</p>
     */
    private static final int TRANSACTION_MONEY_PRECISION = 11;

    /**
     * The five money fields the account record declares, by copybook name.
     *
     * <p>Assumptions: five, and the count is load-bearing. The projection publishes only TWO of them,
     * because {@code data-migration/sql/V1__reporting_views.sql} records at its L497 to L503 that the
     * cash credit limit and the two cycle accumulators are omitted deliberately -- no statement or
     * report band prints them, so including them would widen the reporting role's reach past what it
     * renders. The record still declares five, so the geometry case walks five while the projection
     * case walks two, and the two counts are not interchangeable.</p>
     */
    private static final List<String> ACCOUNT_MONEY_FIELDS = List.of(
            "ACCT-CURR-BAL",
            "ACCT-CREDIT-LIMIT",
            "ACCT-CASH-CREDIT-LIMIT",
            "ACCT-CURR-CYC-CREDIT",
            "ACCT-CURR-CYC-DEBIT");

    /**
     * The copybook field name of the balance the statement prints.
     */
    private static final String BALANCE_FIELD = "ACCT-CURR-BAL";

    /**
     * The copybook field name of the credit limit the projection publishes beside the balance.
     */
    private static final String CREDIT_LIMIT_FIELD = "ACCT-CREDIT-LIMIT";

    /**
     * The copybook field name of the account status flag.
     */
    private static final String ACTIVE_STATUS_FIELD = "ACCT-ACTIVE-STATUS";

    /**
     * The copybook field name of the disclosure group identifier.
     */
    private static final String GROUP_ID_FIELD = "ACCT-GROUP-ID";

    /**
     * The baseline field name of the expiration date, reproduced exactly as the copybook spells it.
     *
     * <p>Assumptions: the baseline field name at {@code app/cpy/CVACT01Y.cpy} L11 carries a
     * misspelling -- there is no letter T in the middle of the word -- and the target column is
     * {@code expiration_date}. That divergence is one of exactly three baseline field-name divergences
     * in the whole migration, it is registered in
     * {@code docs/architecture/data-model-and-schema-mapping.md}, and no other field is renamed
     * anywhere. The baseline spelling is written out here so that a reader tracing the copybook can
     * find the field the column came from, and because a reader searching the baseline for the target
     * name will not find it while a reader searching the target for the baseline name will not find
     * that either. The copybook is reference-only and stays exactly as it is.</p>
     */
    private static final String BASELINE_EXPIRATION_FIELD = "ACCT-EXPIRAION-DATE";

    /**
     * The one-based first byte the expiration date occupies in the account record, fifty-nine.
     */
    private static final int EXPIRATION_FIRST_BYTE = 59;

    /**
     * The one-based last byte the expiration date occupies in the account record, sixty-eight.
     */
    private static final int EXPIRATION_LAST_BYTE = 68;

    /**
     * The projected column name of the expiration date.
     */
    private static final String EXPIRATION_COLUMN = "expiration_date";

    /**
     * The optimistic-locking column name this projection must never publish or read.
     */
    private static final String VERSION_COLUMN = "version";

    /**
     * The schema holding the projection this module reads.
     */
    private static final String PROJECTION_SCHEMA = "reporting";

    /**
     * The projection this module reads, by relation name.
     */
    private static final String PROJECTION_RELATION = "v_accounts";

    /**
     * The schema holding the base table the projection is derived from.
     */
    private static final String OWNING_SCHEMA = "account";

    /**
     * The base table the projection is derived from, by relation name.
     */
    private static final String OWNING_RELATION = "accounts";

    /**
     * The relation the driving cross-reference cursor reads, by qualified name.
     */
    private static final String CROSS_REFERENCE_RELATION = "account.card_xref";

    /**
     * The eight columns the projection publishes, in the order the shipped definition selects them.
     *
     * <p>Assumptions: the set is asserted as CLOSED rather than merely containing what is needed, so a
     * column added to the projection fails this class instead of arriving unnoticed. That matters
     * specifically because the omitted columns are the three money columns no band prints and the
     * optimistic-locking column no reader may see: a widened projection is a widened reach for a role
     * whose whole contract is that it reads only what it renders.</p>
     */
    private static final List<String> PUBLISHED_COLUMNS = List.of(
            "account_id",
            "active_status",
            "curr_bal",
            "credit_limit",
            "open_date",
            EXPIRATION_COLUMN,
            "reissue_date",
            "group_id");

    /**
     * The four account identifiers the committed fixture publishes, ascending.
     *
     * <p>Assumptions: these are synthetic identities and the figure is asserted against the fixture as
     * well as against the lookup, so a fixture edit cannot silently weaken a resolution assertion into
     * one over fewer rows.</p>
     */
    private static final List<Long> PUBLISHED_ACCOUNT_IDS = List.of(7L, 50L, 101L, 102L);

    /**
     * The number of distinct cards the statement-path cross-reference fixture publishes.
     */
    private static final int PUBLISHED_CARD_COUNT = 88;

    /**
     * An account identifier deliberately absent from the account master.
     *
     * <p>Assumptions: the missing-dimension case is constructed FROM DATA and never by discarding a
     * row or altering a relation. Eleven nines is within the declared eleven-digit domain, so the
     * lookup is a well-formed whole-key read rather than one the column could reject, and a census of
     * both committed fixtures finds it in neither -- so what the lookup reports is an absence in the
     * account master and not a malformed request.</p>
     */
    private static final long ABSENT_ACCOUNT_ID = 99_999_999_999L;

    /**
     * The exact set of methods the interface under test declares.
     *
     * <p>Alternatives Considered: asserting only that a write method is absent. Rejected because
     * naming the whole set is the stronger form: it fails on a method REMOVED as well as on one added,
     * and the property this class exists to pin is that the surface is one keyed lookup rather than
     * merely that it is not a mutator. The reference supports the singleton directly -- the
     * {@code 'ACCTFILE'} branch of {@code app/cbl/CBSTM03B.CBL} at L206 to L229 implements one read
     * arm and it is the keyed one, with no plain-sequential arm to stand in for a traversal.</p>
     */
    private static final Set<String> DECLARED_METHOD_NAMES = Set.of("findById");

    /**
     * The method names a mutating repository surface would carry.
     *
     * <p>Assumptions: the vocabulary is the one the persistence framework's own write interfaces
     * publish, so the assertion catches a widened base interface as well as a hand-declared write
     * method. It is matched against the interface's WHOLE method surface, inherited members included,
     * because the failure mode being guarded against is INHERITING the surface rather than declaring
     * it.</p>
     */
    private static final Set<String> WRITE_METHOD_NAMES = Set.of(
            "save", "saveAll", "saveAndFlush", "saveAllAndFlush", "insert", "update",
            "delete", "deleteAll", "deleteById", "deleteAllById", "deleteInBatch",
            "deleteAllInBatch", "deleteAllByIdInBatch", "flush", "persist", "merge", "remove");

    /**
     * The simple type name a keyset-paged return would carry.
     *
     * <p>Assumptions: the name is written out rather than imported, because importing the type would
     * put a class this interface must not return onto this file's import list and would read as though
     * a paged surface were expected here. The consequence being guarded is specific: keyset
     * positioning must NOT be justified from the keyed-read opcode, because that opcode addresses one
     * row by a whole key. Its provenance is a different program entirely --
     * {@code app/cbl/COCRDLIC.cbl} L230 to L244 carries a last-key pair, a first-key pair, a screen
     * ordinal, a last-page-displayed flag and a next-page-exists indicator, which is already a keyset
     * cursor -- and it belongs to the report-side role, not to this one.</p>
     */
    private static final String PAGED_RETURN_TYPE_NAME = "PageResponse";

    /**
     * The four input data definitions the statement job supplies, in the order it declares them.
     *
     * <p>Assumptions: the account definition is the THIRD of the four and the order is asserted
     * against the job file itself. That is worth an assertion rather than a citation because the
     * account and customer definitions sit on adjacent lines, so an off-by-one reading names the wrong
     * definition while still looking plausible, and the four-way join the statement path performs is
     * NOT the four-way join the report path performs -- they share only the transaction store and the
     * cross-reference.</p>
     */
    private static final List<String> STATEMENT_PATH_DEFINITIONS = List.of(
            "TRNXFILE", "XREFFILE", "ACCTFILE", "CUSTFILE");

    /**
     * The data-definition name of the account master.
     */
    private static final String ACCOUNT_DEFINITION = "ACCTFILE";

    /**
     * The statement job, repository-relative, with its filename case exactly as the disk carries it.
     *
     * <p>Assumptions: this baseline mixes cases on disk, so a citation in the wrong case is a dead
     * reference rather than a cosmetic slip -- it resolves for nobody and fails silently in any tooling
     * that follows it. This path and the two program files are UPPERCASE including their extension;
     * the report job and both copybooks carry a lowercase extension.</p>
     */
    private static final String STATEMENT_JOB = "app/jcl/CREASTMT.JCL";

    /**
     * The report job, repository-relative, with its lowercase extension.
     */
    private static final String REPORT_JOB = "app/jcl/TRANREPT.jcl";

    /**
     * The one-based first line of the statement job's input data-definition block, eighty-three.
     */
    private static final int STATEMENT_DEFINITION_FIRST_LINE = 83;

    /**
     * The one-based last line of the statement job's input data-definition block, eighty-six.
     */
    private static final int STATEMENT_DEFINITION_LAST_LINE = 86;

    /**
     * The one-based line of the statement job carrying the account data definition, eighty-five.
     */
    private static final int ACCOUNT_DEFINITION_LINE = 85;

    /**
     * The classpath location of the profile document this class activates.
     */
    private static final String TEST_PROFILE_RESOURCE = "application-test.yml";

    /**
     * The one relation in the projection schema that is a table rather than a view.
     *
     * <p>Assumptions: the projection schema is not empty of tables, and this was MEASURED rather than
     * assumed -- an earlier form of the case below asserted the schema carried no index at all and
     * failed against the shipped arrangement. {@code data-migration/sql/V1__reporting_views.sql}
     * creates one single-row table here, holding the key a card-grouping digest is computed with, whose
     * primary key on a column fixed to one value is what makes a second row unrepresentable. That
     * primary key is the schema's only index. The same file then withdraws every privilege on that
     * table from this module's login role, because the digest would be invertible by anyone able to
     * read both the token and the key -- so it is a relation in the schema this module resolves names
     * in, and not a relation this module reads.</p>
     */
    private static final String KEY_TABLE_RELATION = "card_grouping_key";

    /**
     * The one schema the profile document permits a session to resolve.
     *
     * <p>Assumptions: this is narrower than the phrase "cross-schema views" suggests, and the
     * narrowness is compiled in rather than merely intended --
     * {@code com.carddemo.reporting.config.DataSourceConfig} refuses any initialisation statement
     * naming more than the one permitted schema. Naming the source schemas instead would be wrong
     * twice over: a search-path entry whose schema the role may not use is ignored in silence, and
     * were that reach ever conveyed, unqualified names would begin resolving to base tables rather
     * than to the projections, so the narrowing the projections carry would be bypassed by queries
     * nobody had changed.</p>
     */
    private static final String PERMITTED_SEARCH_PATH_SCHEMA = "reporting";

    /**
     * The container the context connects to, started once for this class.
     *
     * <p>Assumptions: connection coordinates arrive as a bean through {@code @ServiceConnection} and
     * never as text, because the container's port is assigned as it starts -- so committed text could
     * not be correct, and the failure mode of that mistake is not an error but a class that passes
     * against whichever engine happened to be listening. {@code application-test.yml} declares no
     * location, no login name and no credential precisely so that these arrive this way. The declared
     * type is raw rather than parameterised because the container class of this Testcontainers major
     * line is not generic: it declares its own self type as the argument to its base, so a wildcard
     * would not compile.</p>
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /**
     * The lookup under test, injected so every read goes through the declared query.
     */
    @Autowired
    private StatementAccountRepository accounts;

    /**
     * The entity manager, used only to read the persistence provider's own model of the projection.
     */
    @Autowired
    private EntityManager entityManager;

    /**
     * Applies the shipped definitions and loads the fixture corpus before the context connects.
     *
     * <p>Assumptions: the container is already running when this runs, and that is a framework
     * guarantee rather than an assumption about extension ordering -- every {@code BeforeAllCallback}
     * extension is invoked before a {@code @BeforeAll} method, so the container annotation's extension
     * has started it whichever order the two class-level extensions were registered in. The Spring
     * context, by contrast, is built when the first test instance is prepared, which is after this
     * method, so the relations and the rows exist before the pool opens its first connection.</p>
     *
     * <p>Assumptions: nothing here needs to precede the pool for correctness in any case, and the
     * reason is worth recording so a later edit does not treat the ordering as fragile.
     * {@code application-test.yml} pins schema resolution to the single {@code reporting} schema, and
     * a search-path entry naming a schema that does not yet exist is accepted and simply resolves
     * nothing until it does. Its schema-generation setting is {@code none}, so the provider emits no
     * statement that could fabricate the very relations this class must not create.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @BeforeAll
    static void arrangeTheDeployedSchemaAndFixtureCorpus() {
        applyDeployedSchema();
        loadFixtureCorpus();
    }

    /**
     * Confirms the interface exposes one keyed lookup and no other surface at all.
     *
     * <p>Assumptions: the dispatcher the reference uses is asymmetric per data definition, and reading
     * it is what settles that a keyed lookup and an ordered scan cannot share one interface.
     * {@code app/cbl/CBSTM03B.CBL} L118 evaluates the DEFINITION NAME first and the operation code
     * only inside the branch it selects. The {@code 'TRNXFILE'} branch at L133 to L155 implements a
     * plain read at L140 and has no keyed-read arm, with sequential access declared at L33; the
     * {@code 'XREFFILE'} branch at L157 to L179 is the same shape, with sequential access at L39; the
     * {@code 'ACCTFILE'} branch at L206 to L229 implements a keyed read at L213 and NO plain-read arm,
     * with random access at L51. Two ordered scans and two keyed lookups therefore follow from the
     * source rather than from preference, which register entry <b>R3</b> records.</p>
     *
     * <p>Assumptions: the absences are proven reflectively over the WHOLE method surface, inherited
     * members included, and not over the declared list alone. The failure mode being guarded against
     * is inheriting a surface rather than declaring one: a wider base interface would put write and
     * traversal methods on a type whose entire contract is that it has neither, they would compile,
     * they would appear in every completion list, and the refusal would arrive from the database at
     * run time instead of from the compiler.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the interface declares one keyed lookup and neither a traversal nor a write")
    void theInterfaceDeclaresOneKeyedLookupAndNothingElse() {
        // WHAT:
        //       The declared surface, compared as a closed set against the reference's single read arm.
        // WHY : Assumptions: the reference's account branch at app/cbl/CBSTM03B.CBL L206 to L229
        //       implements exactly one read, and it is the keyed one at L213 -- there is no
        //       plain-sequential arm for a traversal method to stand in for. A method added here would
        //       therefore have no baseline counterpart, and a method removed would leave the one
        //       counterpart unserved, so the set is asserted closed in both directions.
        Set<String> declaredNames = new LinkedHashSet<>();
        for (Method declared : StatementAccountRepository.class.getDeclaredMethods()) {
            declaredNames.add(declared.getName());
        }
        assertThat(declaredNames)
                .as("the declared surface is exactly the one keyed lookup the register accounts for")
                .isEqualTo(DECLARED_METHOD_NAMES);

        assertThat(StatementAccountRepository.class.getInterfaces())
                .as("the base is the marker interface, which declares nothing")
                .containsExactly(Repository.class);

        // WHAT:
        //       The whole method surface, screened for a traversal return and for a paged return.
        // WHY : Assumptions: an absent traversal cannot be asserted by NAME, because a cursor method
        //       may be called anything; it is the RETURN TYPE that makes one. Screening for a lazily
        //       consumed sequence and for the keyset envelope catches both shapes the sibling roles
        //       legitimately carry, so this case fails if either ever reaches this interface.
        for (Method surfaced : StatementAccountRepository.class.getMethods()) {
            assertThat(surfaced.getReturnType())
                    .withFailMessage("no method of this interface may return a lazily consumed"
                            + " sequence; %s does, so a traversal has reached a role whose reference"
                            + " branch implements no plain-sequential read", surfaced.getName())
                    .isNotEqualTo(Stream.class);
            assertThat(surfaced.getReturnType().getSimpleName())
                    .withFailMessage("no method of this interface may return the keyset envelope; %s"
                            + " does, so keyset positioning has been justified from an opcode that"
                            + " addresses one row by a whole key", surfaced.getName())
                    .isNotEqualTo(PAGED_RETURN_TYPE_NAME);
        }

        // WHAT:
        //       The whole method surface, screened for the persistence framework's write vocabulary.
        // WHY : Assumptions: the reference DECLARES a write opcode and a rewrite opcode and references
        //       neither. app/cbl/CBSTM03B.CBL L107 and L108 declare them and app/cbl/CBSTM03A.CBL L78
        //       and L79 mirror them, a census of those two condition names across the whole baseline
        //       returns exactly those four occurrences with no reference to either, and a search of the
        //       caller for the statement that selects an operation code yields only open, close, read
        //       and keyed read. An unexercised opcode is indistinguishable from an unsupported one from
        //       the caller's side, so this role exposes no write method at all.
        assertThat(Arrays.stream(StatementAccountRepository.class.getMethods())
                        .map(Method::getName)
                        .filter(WRITE_METHOD_NAMES::contains)
                        .toList())
                .as("no write operation appears anywhere on the interface's method surface")
                .isEmpty();
    }

    /**
     * Confirms a whole eleven-digit identifier addresses exactly one row and never a range.
     *
     * <p>Assumptions: the operation the reference issues is an EXACT FULL-KEY random read and not a
     * partial-key browse, and the account branch establishes that independently of the customer
     * branch. Three facts settle it. Access is declared {@code ACCESS MODE  IS RANDOM} at
     * {@code app/cbl/CBSTM03B.CBL} L51 with {@code RECORD KEY   IS FD-ACCT-ID} at L52, and an indexed
     * file so declared cannot be browsed. The callee body at L213 to L218 is a guard on the keyed-read
     * condition, then {@code MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-ACCT-ID} at L214, then a plain
     * {@code READ ACCT-FILE INTO LK-M03B-FLDT} at L215 -- no greater-or-equal qualifier, no browse
     * start, no read-next and no browse end anywhere in the program. And the caller computes the FULL
     * key length at {@code app/cbl/CBSTM03A.CBL} L398 immediately after moving a transient zero into
     * the same field at L397, so the zero is never the value the callee reads.</p>
     *
     * <p>Assumptions: the key width is asserted at eleven digits and against the registered descriptor
     * rather than against a recollection, because the two keyed lookups of this path have DIFFERENT
     * widths and one shared key buffer serves all four definitions. Nine digits belongs to the
     * customer identifier at {@code app/cpy/CVACT03Y.cpy} L6 and eleven to the account identifier at
     * its L7, so taking one branch's width for the other's would read the wrong number of bytes out of
     * that buffer.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a whole eleven-digit identifier addresses exactly one row")
    void aWholeElevenDigitIdentifierAddressesExactlyOneRow() {
        CopybookLayout.RecordSpec crossReference = CopybookLayout.layout(XREF_DESCRIPTOR);
        CopybookLayout.FieldSpec accountKey = crossReference.field(XREF_ACCOUNT_ID_FIELD);

        // WHAT:
        //       The key width and its position, converted from the descriptor's frame to the copybook's.
        // WHY : Assumptions: the descriptor records a zero-based start while the copybook and every
        //       citation in this repository count bytes from one, so the two differ by exactly one and
        //       an assertion written in the descriptor's frame would read as though it disagreed with
        //       app/cpy/CVACT03Y.cpy L7. Converting here keeps the assertion legible against that line
        //       without changing what is checked.
        assertThat(accountKey.length())
                .as("the account key the driving cross-reference supplies is eleven digits wide")
                .isEqualTo(ACCOUNT_KEY_DIGITS);
        assertThat(accountKey.start() + 1)
                .as("the account key begins at its one-based first byte")
                .isEqualTo(XREF_ACCOUNT_ID_FIRST_BYTE);
        assertThat(accountKey.start() + accountKey.length())
                .as("the account key ends at its one-based last byte")
                .isEqualTo(XREF_ACCOUNT_ID_LAST_BYTE);
        assertThat(crossReference.field(XREF_CUSTOMER_ID_FIELD).length())
                .as("the customer key of the same record is nine digits, deliberately not eleven")
                .isEqualTo(CUSTOMER_KEY_DIGITS);
        assertThat(CopybookLayout.layout(ACCOUNT_DESCRIPTOR).keyLength())
                .as("the account master is keyed on the same eleven digits")
                .isEqualTo(ACCOUNT_KEY_DIGITS);

        // WHAT:
        //       Each published identifier read back, and the row it addresses identified by its own key.
        // WHY : Assumptions: a read that yielded the WRONG row would satisfy a bare presence check, so
        //       each result is identified by the identifier it carries rather than merely counted. That
        //       is the property a full-key read has and a prefix read does not: seven and fifty share
        //       no whole key, but a prefix read for seven over a zero-padded key domain could reach
        //       either, and the assertion below fails in that case rather than passing.
        for (Long published : PUBLISHED_ACCOUNT_IDS) {
            Optional<AccountView> found = accounts.findById(published);

            assertThat(found)
                    .withFailMessage("the account master publishes %d, so the keyed lookup must"
                            + " resolve it; it did not, so either the projection is not reading the"
                            + " loaded rows or the key is not being matched whole", published)
                    .isPresent();
            assertThat(found.orElseThrow().getAccountId())
                    .as("the row addressed carries the identifier that addressed it")
                    .isEqualTo(published);
        }
    }

    /**
     * Confirms every account identifier the driving cross-reference supplies resolves.
     *
     * <p>Assumptions: the identifiers are read back FROM the loaded cross-reference relation rather
     * than written out here, because that is where the statement run gets them. The reference reaches
     * this read once per cross-reference row: {@code app/cbl/CBSTM03A.CBL} L396 moves
     * {@code XREF-ACCT-ID} straight from the record the driving scan just returned into the key buffer,
     * so the key is supplied per row by that scan and is never derived independently. An assertion
     * written against four identifiers typed into this file would hold even if the cross-reference
     * named a fifth.</p>
     *
     * <p>Assumptions: the two counts below are both asserted because they answer different questions.
     * The row count establishes that the corpus is the one this class expects, and the distinct-account
     * count establishes that the relation is a genuine many-to-one onto the account master -- eighty-
     * eight cards over four accounts, with one account carrying the large majority. A corpus of
     * eighty-eight cards over eighty-eight accounts would satisfy the resolution assertion while
     * exercising none of the sharing the statement path actually meets.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the connection to the container cannot be opened or the relation cannot
     *     be read, which is an arrangement failure rather than the resolution under test
     */
    @Test
    @DisplayName("every account the driving cross-reference names resolves through the keyed lookup")
    void everyAccountTheDrivingCrossReferenceNamesResolves() throws SQLException {
        List<Long> suppliedByTheCursor = new ArrayList<>();
        try (Connection connection = POSTGRES.createConnection("");
                PreparedStatement select = connection.prepareStatement(
                        "select distinct account_id from " + CROSS_REFERENCE_RELATION
                                + " order by account_id")) {
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    suppliedByTheCursor.add(rows.getLong(1));
                }
            }
        }

        assertThat(decodeAll(STATEMENT_PATH_XREF_FIXTURE, XREF_DESCRIPTOR))
                .as("the committed fixture publishes the expected number of distinct cards")
                .hasSize(PUBLISHED_CARD_COUNT);
        assertThat(suppliedByTheCursor)
                .as("the loaded cross-reference names exactly the accounts the master publishes")
                .containsExactlyElementsOf(PUBLISHED_ACCOUNT_IDS);

        // WHAT:
        //       Every identifier the cursor supplies, resolved one at a time through the keyed lookup.
        // WHY : Assumptions: resolving them one at a time is the shape the reference uses and the shape
        //       that matters here. app/cbl/CBSTM03A.CBL performs 3000-ACCTFILE-GET once per
        //       cross-reference row, so a chain that resolved three of four accounts and abended on the
        //       fourth is exactly what a single representative lookup would miss.
        for (Long supplied : suppliedByTheCursor) {
            assertThat(accounts.findById(supplied))
                    .withFailMessage("the cross-reference names account %d, so the account projection"
                            + " must hold it; it does not, so the referential chain the statement run"
                            + " walks is broken and the run would abend rather than skip the row",
                            supplied)
                    .isPresent();
        }
    }

    /**
     * Confirms the account definition is the third statement input and appears in no report input.
     *
     * <p>Assumptions: this is asserted against the job files themselves rather than cited, because the
     * account and customer definitions sit on ADJACENT lines and an off-by-one reading names the wrong
     * one while still looking plausible. {@code app/jcl/CREASTMT.JCL} declares its four inputs across
     * L83 to L86 and the account one is at L85; {@code app/jcl/TRANREPT.jcl} declares five inputs
     * across L65 to L73 and none of them is the account master.</p>
     *
     * <p>Assumptions: the consequence is that the two four-way joins of this module must never be
     * conflated. The statement path resolves the transaction store, the cross-reference, the account
     * master and the customer master; the report path resolves the transaction store, the
     * cross-reference and two reference stores. They share only the transaction store and the
     * cross-reference, so the account master is a statement-path input ONLY -- which is why this class
     * binds the statement-path cross-reference fixture and not the report-path one.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the account definition is the third statement input and is no report input")
    void theAccountDefinitionIsTheThirdStatementInputAndNoReportInput() {
        List<String> statementBlock = definitionNamesOf(
                STATEMENT_JOB, STATEMENT_DEFINITION_FIRST_LINE, STATEMENT_DEFINITION_LAST_LINE);

        assertThat(statementBlock)
                .as("the statement job declares its four inputs in the order the charter records")
                .containsExactlyElementsOf(STATEMENT_PATH_DEFINITIONS);
        assertThat(statementBlock.indexOf(ACCOUNT_DEFINITION)
                        + STATEMENT_DEFINITION_FIRST_LINE)
                .as("the account definition sits on the line this class cites throughout")
                .isEqualTo(ACCOUNT_DEFINITION_LINE);

        // WHAT:
        //       The whole report job, screened for any mention of the account definition name.
        // WHY : Assumptions: the whole file is screened rather than the input block alone, because the
        //       claim being made is that the report path never reaches the account master at all --
        //       not merely that it does not name it among its inputs. A mention anywhere, including in
        //       an output definition or a sort control statement, would contradict that.
        assertThat(linesOf(REPORT_JOB))
                .withFailMessage("%s must not name %s anywhere; the report path resolves two reference"
                        + " stores where the statement path resolves the account and customer masters,"
                        + " so a mention here would mean the two four-way joins had been conflated",
                        REPORT_JOB, ACCOUNT_DEFINITION)
                .noneMatch(line -> line.contains(ACCOUNT_DEFINITION));
    }

    /**
     * Confirms the record is three hundred bytes and that its trailing pad reaches no projection.
     *
     * <p>Assumptions: the total is derived by SUMMING the declared field widths of
     * {@code app/cpy/CVACT01Y.cpy} and corroborated two further ways, so no single source has to be
     * trusted. The file description at {@code app/cbl/CBSTM03B.CBL} L75 to L78 declares eleven digits
     * of key and two hundred and eighty-nine characters of data, which closes the same three hundred;
     * and the committed fixture measures three hundred bytes per row.</p>
     *
     * <p>Assumptions: the trailing pad carries the record to its declared length and holds no data, so
     * a projection member for it would publish padding. The pad is the last field of the record and
     * begins immediately after the disclosure group identifier, which is asserted rather than assumed
     * because a pad that began earlier would mean a named field had been dropped from the middle of
     * the record.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the account record is three hundred bytes with its trailing pad dropped")
    void theAccountRecordIsThreeHundredBytesWithItsTrailingPadDropped() {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(ACCOUNT_DESCRIPTOR);
        CopybookLayout.FieldSpec pad = spec.field(PADDING_FIELD);

        assertThat(spec.reclen())
                .as("the declared record length")
                .isEqualTo(ACCOUNT_RECORD_LENGTH);
        assertThat(FILE_DESCRIPTION_KEY_DIGITS + FILE_DESCRIPTION_DATA_WIDTH)
                .as("the file description's key and data widths close the same declared length")
                .isEqualTo(ACCOUNT_RECORD_LENGTH);
        assertThat(spec.field(ACCOUNT_ID_FIELD).length())
                .as("the record is keyed on eleven digits at its first byte")
                .isEqualTo(ACCOUNT_KEY_DIGITS);
        assertThat(spec.field(ACTIVE_STATUS_FIELD).length())
                .as("the status flag occupies its one declared character")
                .isEqualTo(AccountView.ACTIVE_STATUS_WIDTH);
        assertThat(spec.field(GROUP_ID_FIELD).length())
                .as("the disclosure group identifier occupies its ten declared characters")
                .isEqualTo(AccountView.GROUP_ID_WIDTH);

        assertThat(pad.start() + 1)
                .as("the trailing pad begins at its one-based first byte")
                .isEqualTo(PADDING_FIRST_BYTE);
        assertThat(pad.length())
                .as("the trailing pad occupies the remainder of the declared length")
                .isEqualTo(PADDING_WIDTH);
        assertThat(pad.end())
                .as("the trailing pad closes the record exactly")
                .isEqualTo(ACCOUNT_RECORD_LENGTH);

        // WHAT:
        //       The projection's declared members, screened for a member named after the pad.
        // WHY : Assumptions: the pad exists only to reach the declared length, so a member for it would
        //       publish padding as data and would widen the reporting role's reach to bytes that carry
        //       nothing. The comparison lowercases the copybook name because the projection names its
        //       members in the target's convention rather than the copybook's.
        assertThat(Arrays.stream(AccountView.class.getDeclaredFields())
                        .map(Field::getName)
                        .toList())
                .withFailMessage("the projection must carry no member for the trailing pad; the pad"
                        + " exists only to reach the three-hundred-byte declared length, so a member"
                        + " for it would publish padding as data")
                .doesNotContain(PADDING_FIELD.toLowerCase(Locale.ROOT));
    }

    /**
     * Confirms each of the five declared money fields is twelve bytes of signed zoned decimal.
     *
     * <p>Assumptions: five fields are walked and not one, because they share a picture clause and
     * therefore share a failure mode: a change to the shared width would move every field after the
     * first one it touched, and a case covering only the balance would pass while the four behind it
     * had shifted. The one-based spans are the sums of the declared widths ahead of each field, and the
     * scale is asserted at exactly two because a scale of anything else moves the implied decimal
     * point without changing the byte count.</p>
     *
     * @param expected the declared geometry of one account money field
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("declaredMoneyGeometry")
    @DisplayName("each declared money field is twelve bytes of signed zoned decimal at scale two")
    void eachDeclaredMoneyFieldIsTwelveBytesOfSignedZonedDecimal(MoneyFieldGeometry expected) {
        CopybookLayout.FieldSpec field =
                CopybookLayout.layout(ACCOUNT_DESCRIPTOR).field(expected.fieldName());

        // WHAT:
        //       One money field's storage regime, width, digit split, sign and one-based span.
        // WHY : Assumptions: the regime is zoned decimal and NOT packed, and the distinction decides
        //       how many bytes a value occupies. Twelve digits packed would occupy seven bytes rather
        //       than twelve, so a regime read wrongly here would mis-align every field after it. The
        //       base masters are zoned throughout, corroborated from outside the COBOL by
        //       app/jcl/PRTCATBL.jcl L47 to L50, whose sort symbols type every numeric field ZD -- L50
        //       reads TRAN-CAT-BAL,18,11,ZD -- and whose output record at L53 to L56 renders that field
        //       through EDIT=(TTTTTTTTT.TT), a twelve-character mask keeping leading zeros and emitting
        //       no sign.
        assertThat(field.kind())
                .as("the storage regime is zoned decimal rather than packed or binary")
                .isEqualTo(CopybookLayout.Kind.ZONED);
        assertThat(field.length())
                .as("one signed zoned money field occupies twelve bytes")
                .isEqualTo(MONEY_FIELD_WIDTH);
        assertThat(field.intDigits())
                .as("ten integer digit positions, one wider than the transaction amount's nine")
                .isEqualTo(MONEY_INTEGER_DIGITS);
        assertThat(field.decDigits())
                .as("two fractional digit positions, so the implied decimal point holds cents")
                .isEqualTo(MONEY_FRACTION_DIGITS);
        assertThat(field.signed())
                .as("the picture carries a leading sign, overpunched into the final byte")
                .isTrue();
        assertThat(field.start() + 1)
                .as("the field begins at its one-based first byte")
                .isEqualTo(expected.firstByte());
        assertThat(field.end())
                .as("the field ends at its one-based last byte")
                .isEqualTo(expected.lastByte());
    }

    /**
     * The declared geometry of one account money field, as the copybook states it.
     *
     * <p>Assumptions: the two byte positions are ONE-BASED, matching the copybook and every citation in
     * this repository, while the registered descriptor records a zero-based start. The conversion is
     * done at the point of comparison rather than here, so this record reads against
     * {@code app/cpy/CVACT01Y.cpy} directly.</p>
     *
     * @param fieldName the copybook field name exactly as the copybook declares it
     * @param firstByte the one-based first byte the field occupies in the three-hundred-byte record
     * @param lastByte the one-based last byte the field occupies in the three-hundred-byte record
     */
    record MoneyFieldGeometry(String fieldName, int firstByte, int lastByte) {

        /**
         * Renders the field name so a parameterised case is identified by it in the report.
         *
         * @return the copybook field name, never {@code null}
         */
        @Override
        public String toString() {
            return fieldName;
        }
    }

    /**
     * Supplies the five declared money geometries, in the order the copybook declares them.
     *
     * <p>Assumptions: the spans are written out here rather than computed from the descriptor, because
     * a case that derived its expectation from the thing under test would compare the descriptor
     * against itself and would hold however the offsets had moved. They are the sums of the declared
     * widths ahead of each field in {@code app/cpy/CVACT01Y.cpy}: eleven digits of key and one
     * character of status ahead of the first, then two ten-character dates and a third between the
     * third and the fourth.</p>
     *
     * @return one geometry per declared money field, in copybook order, never {@code null}
     */
    static Stream<MoneyFieldGeometry> declaredMoneyGeometry() {
        return Stream.of(
                new MoneyFieldGeometry(ACCOUNT_MONEY_FIELDS.get(0), 13, 24),
                new MoneyFieldGeometry(ACCOUNT_MONEY_FIELDS.get(1), 25, 36),
                new MoneyFieldGeometry(ACCOUNT_MONEY_FIELDS.get(2), 37, 48),
                new MoneyFieldGeometry(ACCOUNT_MONEY_FIELDS.get(3), 79, 90),
                new MoneyFieldGeometry(ACCOUNT_MONEY_FIELDS.get(4), 91, 102));
    }

    /**
     * Confirms the published money is exact at twelve digits and scale two, and never eleven.
     *
     * <p>Assumptions: the projection publishes TWO of the record's five money fields and the count is
     * not interchangeable with five. {@code data-migration/sql/V1__reporting_views.sql} records at its
     * L497 to L503 that the cash credit limit and the two cycle accumulators are omitted deliberately,
     * because no statement or report band prints them and including them would widen the reporting
     * role's reach past what it renders. The two published columns are asserted at the mapping, at the
     * relation and at the value, because those are three separate ways one digit could be lost.</p>
     *
     * <p>Assumptions: twelve is never unified with the statement transaction projection's eleven, and
     * the case asserts the INEQUALITY rather than leaving it implied. One integer digit more or fewer
     * shifts every field after it in the record and moves the implied decimal point, so a balance would
     * read as ten times or a tenth of itself while every row still decoded without complaint -- which
     * is the class of silent divergence a golden-master comparison exists to catch and the one no
     * compiler would.</p>
     *
     * <p>Assumptions: the expected values are DECODED from the committed fixture through the shared
     * codec rather than written out here. Alternatives Considered: writing the four balances out as
     * literals. Rejected because the fixture's zoned bytes are the single source of truth for what the
     * corpus holds, a literal here would be a second transcription of them, and the two would then be
     * free to disagree -- with the literal winning, since it is what the assertion reads.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the catalogue cannot be read, which is an arrangement failure rather
     *     than the precision under test
     */
    @Test
    @DisplayName("published money is exact at twelve digits and scale two, never at eleven")
    void publishedMoneyIsExactAtTwelveDigitsAndScaleTwo() throws SQLException {
        Column balanceColumn = mappedColumn("currentBalance");
        Column limitColumn = mappedColumn("creditLimit");

        // WHAT:
        //       The declared precision and scale of both published money columns, at the mapping.
        // WHY : Assumptions: the mapping writes these as literals on each column rather than taking
        //       them from a shared constant, and it nests its own attribute converter rather than
        //       sharing one, precisely so an eleven-digit attribute cannot reach either. Asserting the
        //       literals here is what makes that arrangement fail visibly if a later edit reaches for a
        //       shared constant, which is the exact mechanism by which one declared contract silently
        //       becomes the other.
        assertThat(balanceColumn.precision())
                .as("the balance is declared at twelve significant digits")
                .isEqualTo(PUBLISHED_MONEY_PRECISION);
        assertThat(balanceColumn.scale())
                .as("the balance is declared at a scale of two, so it holds cents exactly")
                .isEqualTo(PUBLISHED_MONEY_SCALE);
        assertThat(limitColumn.precision())
                .as("the credit limit shares the balance's twelve digits")
                .isEqualTo(PUBLISHED_MONEY_PRECISION);
        assertThat(limitColumn.scale())
                .as("the credit limit shares the balance's scale of two")
                .isEqualTo(PUBLISHED_MONEY_SCALE);
        assertThat(PUBLISHED_MONEY_PRECISION)
                .withFailMessage("the account money precision must remain distinct from the statement"
                        + " transaction amount's; the two agreed, so one contract has been widened or"
                        + " narrowed to match the other and every amount is out by a factor of ten")
                .isNotEqualTo(TRANSACTION_MONEY_PRECISION);

        // WHAT:
        //       The same precision and scale as the engine's own catalogue reports them for the view.
        // WHY : Assumptions: the mapping and the relation are separate declarations and either can move
        //       without the other, so agreement between them is a property worth asserting rather than
        //       assuming. The type name is asserted positively as the exact numeric type rather than by
        //       excluding the approximate ones, which excludes every approximate type at once and needs
        //       no list to be kept current.
        assertThat(catalogueTypeOf(PROJECTION_SCHEMA, PROJECTION_RELATION, "curr_bal"))
                .as("the relation publishes the balance as the exact numeric type at twelve and two")
                .isEqualTo("numeric(" + PUBLISHED_MONEY_PRECISION + "," + PUBLISHED_MONEY_SCALE + ")");
        assertThat(catalogueTypeOf(PROJECTION_SCHEMA, PROJECTION_RELATION, "credit_limit"))
                .as("the relation publishes the credit limit at the same precision and scale")
                .isEqualTo("numeric(" + PUBLISHED_MONEY_PRECISION + "," + PUBLISHED_MONEY_SCALE + ")");

        Map<Long, Map<String, Object>> committed = committedAccountRows();
        boolean sawNegative = false;
        boolean sawZero = false;

        for (Long published : PUBLISHED_ACCOUNT_IDS) {
            Money balance = accounts.findById(published).orElseThrow().getCurrentBalance();
            BigDecimal expected = money(committed.get(published), BALANCE_FIELD);

            // WHAT:
            //       The read balance compared to the decoded bytes, with its scale asserted separately.
            // WHY : Assumptions: the shared money type's own equality compares by VALUE and ignores
            //       scale, so comparing two instances of it could not detect a stripped scale at all.
            //       The comparison is therefore made on the exact decimal the instance carries, whose
            //       equality does compare scale, and the scale is asserted a second time on its own so
            //       a failure says which of the two properties broke.
            assertThat(balance.amount())
                    .withFailMessage("account %d must read back the balance its committed bytes"
                            + " encode; it did not, so a value has been lost or rescaled between the"
                            + " zoned bytes and the numeric column", published)
                    .isEqualTo(expected);
            assertThat(balance.amount().scale())
                    .as("the balance is carried at exactly two decimal places")
                    .isEqualTo(Money.SCALE);
            assertThat(accounts.findById(published).orElseThrow().getCreditLimit().amount())
                    .as("the credit limit reads back the value its committed bytes encode")
                    .isEqualTo(money(committed.get(published), CREDIT_LIMIT_FIELD));

            sawNegative = sawNegative || balance.isNegative();
            sawZero = sawZero || balance.isZero();
        }

        // WHAT:
        //       That the corpus genuinely covers a negative and a zero balance.
        // WHY : Assumptions: the sign is OVERPUNCHED into the final byte rather than carried
        //       separately, so a negative value and a positive one differ in that byte alone and a
        //       corpus of positives would exercise only half the decode. Zero has its own overpunch
        //       character again, distinct from both, so it is covered explicitly rather than treated as
        //       a positive.
        assertThat(sawNegative)
                .as("the corpus carries a negative balance, so the sign overpunch is exercised")
                .isTrue();
        assertThat(sawZero)
                .as("the corpus carries a zero balance, whose overpunch differs from both signs")
                .isTrue();

        // WHAT:
        //       That the zero balance is carried unstripped.
        // WHY : Assumptions: reducing a value to its shortest form would make two amounts that encode
        //       to DIFFERENT bytes compare equal, which is what breaks a byte-reproducible comparison
        //       of two runs. Zero is the value where the difference is largest -- reduced, it loses both
        //       decimal places -- so it is the one asserted.
        BigDecimal zeroBalance = accounts.findById(zeroBalanceAccount(committed))
                .orElseThrow().getCurrentBalance().amount();
        assertThat(zeroBalance)
                .withFailMessage("the zero balance must be carried at its declared scale and not"
                        + " reduced to its shortest form; it was reduced, so two amounts encoding to"
                        + " different bytes would now compare equal")
                .isNotEqualTo(zeroBalance.stripTrailingZeros());
    }

    /**
     * Confirms the expiration date is read under the target column name and carries its bytes across.
     *
     * <p>Assumptions: the baseline field name at {@code app/cpy/CVACT01Y.cpy} L11 carries a misspelling
     * -- there is no letter T in the middle of the word -- and the target column is
     * {@code expiration_date}. The lineage is stated explicitly so it is never ambiguous: the baseline
     * field name carries the misspelling, the target column is {@code expiration_date}, and the
     * divergence is documented in {@code docs/architecture/data-model-and-schema-mapping.md}. It is one
     * of exactly three baseline field-name divergences in the whole migration and no other field is
     * renamed anywhere. The copybook is reference-only and stays exactly as it is; nothing in this
     * directory edits, or may edit, that source.</p>
     *
     * <p>Assumptions: BOTH names are asserted, and asserting only the target name would be the weaker
     * half. A reader searching the baseline for the target name will not find it and a reader searching
     * the target for the baseline name will not find that either, so the case pins the baseline
     * spelling at its declared bytes and the target spelling at the relation, which is the only place
     * the two are tied together mechanically.</p>
     *
     * <p>Assumptions: the ten characters the baseline stores are already in year-month-day order, so a
     * lexical comparison over them agrees with a chronological comparison over dates -- which is why
     * the target carries a real date column rather than text, and why the decoded characters can be
     * compared against the read date directly.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the catalogue cannot be read, which is an arrangement failure rather
     *     than the column name under test
     */
    @Test
    @DisplayName("the expiration date is read under the target column name at its declared bytes")
    void theExpirationDateIsReadUnderTheTargetColumnName() throws SQLException {
        CopybookLayout.FieldSpec baselineField =
                CopybookLayout.layout(ACCOUNT_DESCRIPTOR).field(BASELINE_EXPIRATION_FIELD);

        assertThat(baselineField.start() + 1)
                .as("the baseline field begins at its one-based first byte")
                .isEqualTo(EXPIRATION_FIRST_BYTE);
        assertThat(baselineField.end())
                .as("the baseline field ends at its one-based last byte")
                .isEqualTo(EXPIRATION_LAST_BYTE);
        assertThat(mappedColumn("expirationDate").name())
                .as("the mapping reads the target column name")
                .isEqualTo(EXPIRATION_COLUMN);

        // WHAT:
        //       The relation's own column list, screened for the target name and the baseline name.
        // WHY : Assumptions: the baseline name is asserted ABSENT from the relation as well as the
        //       target name present, because a relation carrying both would mean the divergence had been
        //       resolved by duplication rather than by a rename -- and a projection with two spellings
        //       of one field is how a mapper comes to read the stale one.
        List<String> published = catalogueColumnsOf(PROJECTION_SCHEMA, PROJECTION_RELATION);
        assertThat(published)
                .as("the relation publishes exactly the eight columns the shipped definition selects")
                .containsExactlyElementsOf(PUBLISHED_COLUMNS);
        assertThat(published)
                .withFailMessage("the relation must not publish the baseline spelling under any form;"
                        + " it does, so the divergence has been resolved by duplication and a mapper"
                        + " could read whichever spelling it met first")
                .noneMatch(column -> column.replace("_", "")
                        .equalsIgnoreCase(BASELINE_EXPIRATION_FIELD.replace("-", "")));

        Map<Long, Map<String, Object>> committed = committedAccountRows();
        for (Long account : PUBLISHED_ACCOUNT_IDS) {
            assertThat(accounts.findById(account).orElseThrow().getExpirationDate())
                    .withFailMessage("account %d must read back the expiration date its committed"
                            + " bytes carry at positions %d to %d; it did not, so the projection is"
                            + " reading a different field or a different relation", account,
                            EXPIRATION_FIRST_BYTE, EXPIRATION_LAST_BYTE)
                    .isEqualTo(date(committed.get(account), BASELINE_EXPIRATION_FIELD));
        }
    }

    /**
     * Confirms the optimistic-locking column is neither mapped, published nor named in a read.
     *
     * <p>Assumptions: that column belongs to the writable entity in the context that OWNS the table,
     * where it stands in for the before-image comparison the account update program performs across the
     * pseudo-conversational gap. A statement is a point-in-time read rather than a compare-and-swap, so
     * there is nothing here for a version to guard. The concrete reason it must not be read is a
     * privilege one: this module reads a projection under a login role holding read privileges only,
     * and it can therefore never participate in optimistic locking at all -- so reading the column
     * would imply a write capability the module must not have and must not appear to have.</p>
     *
     * <p>Assumptions: the absence is proven FOUR independent ways because each answers a different
     * question and any one alone would leave a gap. Reflection shows the type declares no such member;
     * the persistence provider's own model shows it recognises no version attribute, which is what
     * decides whether a generated statement could carry one; the catalogue shows the relation does not
     * publish the column at all, contrasted against the owning table which does, so the omission is
     * located in the projection rather than mistaken for the column not existing; and the recorded
     * statements show the read the lookup actually issues names it nowhere.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the catalogue cannot be read, which is an arrangement failure rather
     *     than the absence under test
     */
    @Test
    @DisplayName("the optimistic-locking column is neither mapped, published nor named in a read")
    void theOptimisticLockingColumnIsNeitherMappedNorPublishedNorRead() throws SQLException {
        assertThat(Arrays.stream(AccountView.class.getDeclaredFields())
                        .anyMatch(field -> field.isAnnotationPresent(Version.class)))
                .as("the projection declares no version member, so no optimistic write path exists")
                .isFalse();
        assertThat(Arrays.stream(AccountView.class.getDeclaredFields())
                        .map(Field::getName)
                        .toList())
                .as("no declared member is named after the optimistic-locking column")
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains(VERSION_COLUMN));

        EntityType<AccountView> model = entityManager.getMetamodel().entity(AccountView.class);
        assertThat(model.hasVersionAttribute())
                .as("the persistence provider recognises no version attribute on this projection")
                .isFalse();

        // WHAT:
        //       The column present on the owning table and absent from the projection.
        // WHY : Assumptions: the contrast is what locates the omission. Asserting only that the
        //       projection lacks the column would hold just as well if the column had never been
        //       created upstream, and the reader would learn nothing about whether this module was
        //       declining to read it or simply unable to.
        assertThat(catalogueColumnsOf(OWNING_SCHEMA, OWNING_RELATION))
                .as("the owning table does carry the optimistic-locking column")
                .contains(VERSION_COLUMN);
        assertThat(catalogueColumnsOf(PROJECTION_SCHEMA, PROJECTION_RELATION))
                .withFailMessage("the projection must not publish the optimistic-locking column; it"
                        + " does, so a read from this module would carry a value only a writer has any"
                        + " use for, under a role that can never write")
                .doesNotContain(VERSION_COLUMN);

        // WHAT:
        //       A read issued from inside this case, then the statements it caused.
        // WHY : Assumptions: the read is issued HERE rather than relied upon from another case, because
        //       the test engine gives no ordering guarantee between cases and a recorder consulted
        //       before any read had happened would be empty -- which would make this the one assertion
        //       in the class that passed for the wrong reason. Assumptions: the filter to statements
        //       naming the projection keeps the check specific, since an unrelated startup statement
        //       mentioning the word would otherwise fail the case for the wrong reason; and requiring at
        //       least one such statement is what makes it fail loudly rather than vacuously if the
        //       recorder ever stops being wired in.
        accounts.findById(PUBLISHED_ACCOUNT_IDS.getFirst());
        List<String> readsOfTheProjection = EmittedStatements.namingRelation(PROJECTION_RELATION);
        assertThat(readsOfTheProjection)
                .as("the lookup issued at least one statement against the projection")
                .isNotEmpty();
        assertThat(readsOfTheProjection)
                .withFailMessage("at least one recorded statement must be the projection read itself,"
                        + " naming a published money column; none did, so the recorder captured"
                        + " something other than the read this case is about and the assertion below"
                        + " would hold without observing it")
                .anyMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("curr_bal"));
        assertThat(readsOfTheProjection)
                .withFailMessage("no statement issued against the projection may name the"
                        + " optimistic-locking column; one did, so the provider is generating a read"
                        + " that assumes a write path this module must not have")
                .noneMatch(sql -> sql.toLowerCase(Locale.ROOT).contains(VERSION_COLUMN));
    }

    /**
     * Confirms the mapping refuses a write before any statement can be generated for it.
     *
     * <p>Assumptions: this is the mapping-layer half of the read-only posture and it is entirely
     * structural. Four facts together mean no insert, update or delete statement for this projection
     * can be generated at all: it carries the immutability marker, so it is excluded from dirty
     * checking; it declares no version member, so no optimistic write path exists; every mapped column
     * is declared neither insertable nor updatable; and it declares no association at all, so there is
     * nothing for a cascade to be declared on. A behavioural probe was considered instead and rejected:
     * an immutable entity has its updates silently discarded rather than refused, so a probe would have
     * to assert an unchanged row -- which the read-only pool this profile inherits would produce on its
     * own, and the assertion would then hold for the wrong reason.</p>
     *
     * <p>Assumptions: the DATABASE half of the refusal is asserted by
     * {@code StatementCardXrefRepositoryIT} in this same directory, against a base table in another
     * context's schema, and is cited here rather than duplicated. The two halves answer different
     * questions -- the structural one cannot show that a statement reaching the engine would be
     * refused, and the privilege one cannot show that the application fails before one is generated --
     * so neither is redundant, but neither needs asserting twice either.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the mapping declares no mutable column, no version and no association")
    void theMappingDeclaresNoMutableColumnAndNoAssociation() {
        assertThat(AccountView.class.getAnnotation(Immutable.class))
                .as("the projection carries the immutability marker, so it is not dirty checked")
                .isNotNull();
        assertThat(Arrays.stream(AccountView.class.getDeclaredFields())
                        .anyMatch(field -> field.isAnnotationPresent(Id.class)))
                .as("the projection declares an identifier, so a keyed lookup can address one row")
                .isTrue();

        for (Field field : AccountView.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                assertThat(column.updatable())
                        .withFailMessage("every mapped column of the projection must be declared"
                                + " non-updatable; %s is not, so the provider could generate an update"
                                + " for it", field.getName())
                        .isFalse();
                assertThat(column.insertable())
                        .withFailMessage("every mapped column of the projection must be declared"
                                + " non-insertable; %s is not, so the provider could generate an insert"
                                + " for it", field.getName())
                        .isFalse();
            }
        }

        // WHAT:
        //       The provider's own account of whether this projection reaches another entity.
        // WHY : Assumptions: a cascade cannot be declared without an association to declare it on, so
        //       asserting the absence of every association is the complete form of asserting the
        //       absence of every cascade -- and it does not have to be kept current against the list of
        //       association kinds the specification defines.
        EntityType<AccountView> model = entityManager.getMetamodel().entity(AccountView.class);
        assertThat(model.getAttributes().stream().anyMatch(Attribute::isAssociation))
                .withFailMessage("the projection must declare no association; one is declared, so a"
                        + " cascade could be declared on it and a read could reach a relation this"
                        + " module holds no privilege over")
                .isFalse();
        assertThat(model.getAttributes().stream().map(Attribute::getName).toList())
                .as("the provider maps exactly the eight members the relation publishes")
                .hasSize(PUBLISHED_COLUMNS.size());
    }

    /**
     * Confirms an identifier the master does not hold yields nothing a caller can consume by accident.
     *
     * <p>Assumptions: an empty result on this path is a referential-integrity violation that aborts the
     * run, and it is never swallowed, defaulted or passed over. The reference is unambiguous:
     * {@code 3000-ACCTFILE-GET.} spans L392 to L414 of {@code app/cbl/CBSTM03A.CBL} and its evaluation
     * at L403 to L410 carries only {@code WHEN '00' CONTINUE} and a {@code WHEN OTHER} arm that
     * displays and then performs {@code 9999-ABEND-PROGRAM} -- there is NO end-of-file arm at all. That
     * paragraph is at L921 and its two-statement body displays at L922 and calls the
     * language-environment abend service at L923.</p>
     *
     * <p>Assumptions: the driving cursor is deliberately the OPPOSITE and the contrast is the point.
     * {@code 1000-XREFFILE-GET-NEXT.} spans L345 to L366 and its evaluation DOES carry an end-of-file
     * arm, {@code WHEN '10' MOVE 'Y' TO END-OF-FILE} at L356 and L357, so cross-reference exhaustion
     * ends the run normally while a missing account aborts it. What breaks if the two are treated alike
     * is specific: a broken referential chain would silently TRUNCATE a statement run at the row it
     * failed on instead of failing it, so cardholders past that row would have no document and nothing
     * anywhere would record the omission. Register entry <b>R10</b> fixes the policy.</p>
     *
     * <p>Assumptions: the refusal belongs to the CALLER and not to the lookup, which is why the lookup
     * is asserted to yield an empty optional rather than to raise. Returning the empty is what lets the
     * caller name the unresolved identifier in its own refusal, exactly as the reference's lookup
     * paragraphs display the offending key before abending, and register entry <b>R13</b> records that a
     * query boundary yields a fully populated projection or nothing at all rather than a half-filled
     * instance a caller must inspect field by field.</p>
     *
     * <p>Assumptions: the exception the assertion captures is {@link IllegalStateException}, and it is
     * that type because it is the type the production caller raises. {@code StatementService} resolves
     * this dimension at its L1064 to L1066 and converts an empty optional through a helper that returns
     * an {@code IllegalStateException} whose message opens with {@code ERROR READING ACCTFILE} --
     * itself the text the reference displays at L407. Alternatives Considered: letting the empty stand
     * as a silently absent dimension, or substituting a defaulted account row. Both are rejected
     * because the reference abends rather than continuing, so either would be a behavioural divergence
     * introduced without being documented, and a defaulted row in particular would print a statement
     * carrying a balance no account ever held.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an absent identifier yields an empty result the caller converts into a refusal")
    void anAbsentIdentifierYieldsAnEmptyResultTheCallerRefuses() {
        // WHAT:
        //       That the identifier under test is absent from both committed fixtures.
        // WHY : Assumptions: the case is constructed FROM DATA and never by discarding a row or altering
        //       a relation, because either would leave this class defining what it reads and would put
        //       the corpus every other case here depends on into a state that depends on execution
        //       order. Establishing the absence first is also what distinguishes an empty result from a
        //       lookup that failed for some other reason.
        assertThat(PUBLISHED_ACCOUNT_IDS)
                .as("the identifier under test is not one the account master publishes")
                .doesNotContain(ABSENT_ACCOUNT_ID);
        assertThat(decodeAll(STATEMENT_PATH_XREF_FIXTURE, XREF_DESCRIPTOR).stream()
                        .map(row -> integral(row, XREF_ACCOUNT_ID_FIELD))
                        .distinct()
                        .toList())
                .as("no cross-reference row names the identifier under test either")
                .doesNotContain(ABSENT_ACCOUNT_ID);

        Optional<AccountView> unresolved = accounts.findById(ABSENT_ACCOUNT_ID);

        assertThat(unresolved)
                .withFailMessage("an identifier the account master does not hold must yield an empty"
                        + " result rather than a row; a row was returned, so the lookup is matching"
                        + " something other than the whole key it was given")
                .isEmpty();

        // WHAT:
        //       The caller's own policy applied to the empty result.
        // WHY : Assumptions: asserting the emptiness alone would leave the dangerous half unproven. An
        //       empty optional that a caller could unwrap to a usable value is exactly the shape in
        //       which a broken referential chain becomes a printed statement, so the case applies the
        //       refusal the production caller applies and asserts it raises rather than yielding
        //       anything at all.
        assertThatThrownBy(() -> unresolved.orElseThrow(() -> new IllegalStateException(
                "ERROR READING " + ACCOUNT_DEFINITION
                        + ": the cross-reference names an account that does not resolve")))
                .withFailMessage("an unresolved account must abort rather than yield a value; it did"
                        + " not raise, so a statement run could continue past a broken referential"
                        + " chain and truncate silently where the reference abends")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ACCOUNT_DEFINITION);
    }

    /**
     * Confirms this module contributes no index, materialises nothing and reaches one schema.
     *
     * <p>Assumptions: the index-rebuild step of the baseline retires outright rather than being ported,
     * because the engine maintains an index transactionally and there is nothing left for a rebuild step
     * to do; the index this projection reads over is owned by the migration that creates the base table.
     * Register entry <b>R8</b> holds that ground. The assertion here is the consequence: no projection
     * in this schema carries an index, because a view cannot carry one and this module creates
     * nothing.</p>
     *
     * <p>Assumptions: the schema does hold ONE index and the case names it rather than asserting the
     * schema is empty, because that was measured. The shipped definition creates a single-row table here
     * to hold the card-grouping digest key, and its primary key is that index; the same file then
     * withdraws every privilege on the table from this module's login role, so it is a relation in the
     * schema rather than a relation this module reads. Asserting the indexed set is CLOSED is the
     * stronger form in any case: it fails on an index added over a projection, which asserting the
     * absence of one particular index would not.</p>
     *
     * <p>Assumptions: nothing is materialised, and that is a refusal rather than an omission. A
     * materialised projection would answer from a snapshot taken at some earlier instant, which is the
     * same class of behaviour a read replica would introduce -- register entry <b>R12</b> declines the
     * replica on the ground that it adds cost and replica-lag semantics for no parity benefit, and the
     * specific behavioural cost is that the reference statement job reads the live transaction cluster
     * directly, so it cannot omit a transaction the online path has already accepted while a statement
     * generated from a stale snapshot can.</p>
     *
     * <p>Assumptions: schema resolution is pinned to ONE schema and this is the narrowness a reader most
     * often mistakes. Naming the source schemas instead would be wrong twice over: a search-path entry
     * whose schema the role may not use is ignored in silence, and were that reach ever conveyed,
     * unqualified names would begin resolving to base tables rather than to the projections, so the
     * narrowing the projections carry would be bypassed by queries nobody had changed.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the catalogue cannot be read, which is an arrangement failure rather
     *     than the absence under test
     */
    @Test
    @DisplayName("this module contributes no index, materialises nothing and reaches one schema")
    void thisModuleContributesNoIndexAndMaterialisesNothing() throws SQLException {
        try (Connection connection = POSTGRES.createConnection("")) {
            List<String> indexedRelations = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "select distinct tablename from pg_indexes where schemaname = ?"
                            + " order by tablename")) {
                select.setString(1, PROJECTION_SCHEMA);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        indexedRelations.add(rows.getString(1));
                    }
                }
            }

            assertThat(indexedRelations)
                    .withFailMessage("the only indexed relation in this schema must be the single-row"
                            + " key table the shipped definition creates, whose privileges that file"
                            + " withdraws from this module's role; the indexed set is %s instead, so"
                            + " an index has arrived over something this module reads",
                            indexedRelations)
                    .containsExactly(KEY_TABLE_RELATION);
            assertThat(indexedRelations)
                    .as("the projection the lookup reads carries no index, because a view cannot")
                    .doesNotContain(PROJECTION_RELATION);
            assertThat(singleValue(connection,
                    "select count(*) from pg_matviews where schemaname = '"
                            + PROJECTION_SCHEMA + "'"))
                    .as("nothing in that schema is materialised, so no read answers from a snapshot")
                    .isEqualTo("0");

            // WHAT:
            //       The catalogue's own classification of the relation the lookup reads.
            // WHY : Assumptions: the classification is asserted rather than inferred from the name,
            //       because a materialised relation and an ordinary view are addressed identically in a
            //       query and differ only in when their rows were computed. A name beginning with the
            //       view prefix would keep reading correctly after such a change, and every value this
            //       class asserts would still match -- until the snapshot went stale.
            assertThat(singleValue(connection,
                    "select c.relkind from pg_class c join pg_namespace n on n.oid = c.relnamespace"
                            + " where n.nspname = '" + PROJECTION_SCHEMA + "'"
                            + " and c.relname = '" + PROJECTION_RELATION + "'"))
                    .withFailMessage("the relation the lookup reads must be an ordinary view; the"
                            + " catalogue classifies it otherwise, so rows could be answered from a"
                            + " snapshot rather than from the live base table")
                    .isEqualTo("v");
        }

        List<String> initialisation = profileDocumentLines().stream()
                .filter(line -> line.toLowerCase(Locale.ROOT).contains("search_path"))
                .toList();
        assertThat(initialisation)
                .as("the profile document pins schema resolution in exactly one place")
                .hasSize(1);
        assertThat(initialisation.getFirst())
                .withFailMessage("schema resolution must name exactly the one permitted schema; the"
                        + " profile names something else, and a wider path would let an unqualified"
                        + " name resolve to a base table and bypass the narrowing the projections"
                        + " carry")
                .contains(PERMITTED_SEARCH_PATH_SCHEMA)
                .doesNotContain(OWNING_SCHEMA);
    }

    /**
     * Reads the column declaration of one mapped member of the projection.
     *
     * @param memberName the declared member name on {@code AccountView}
     * @return that member's column declaration, never {@code null}
     * @throws IllegalStateException if the member is absent or carries no column declaration, which
     *     means the projection's shape has changed rather than that this helper is wrong
     */
    private static Column mappedColumn(String memberName) {
        try {
            Column column = AccountView.class.getDeclaredField(memberName).getAnnotation(Column.class);
            if (column == null) {
                throw new IllegalStateException("the projection member " + memberName
                        + " carries no column declaration, so nothing ties it to a published column");
            }
            return column;
        } catch (NoSuchFieldException cause) {
            throw new IllegalStateException("the projection declares no member " + memberName
                    + ", so the shape this class asserts against has changed", cause);
        }
    }

    /**
     * Decodes the committed account fixture and indexes it by the identifier each row carries.
     *
     * <p>Assumptions: the fixture is the single source of truth for what the corpus holds, so every
     * expected value in this class is read from it rather than written out. Indexing by identifier is
     * what lets an assertion name the account it is about, which is the difference between a failure
     * that says which row broke and one that says only that a comparison failed.</p>
     *
     * @return one decoded field map per account, keyed by account identifier, never {@code null}
     */
    private static Map<Long, Map<String, Object>> committedAccountRows() {
        return decodeAll(ACCOUNT_FIXTURE, ACCOUNT_DESCRIPTOR).stream()
                .collect(Collectors.toMap(row -> integral(row, ACCOUNT_ID_FIELD), row -> row));
    }

    /**
     * Finds the account whose committed balance is an exact zero.
     *
     * <p>Assumptions: the account is located from the DATA rather than named by identifier, so the
     * unstripped-scale assertion follows the fixture if a later edit moves the zero balance to a
     * different row. Naming an identifier would make that edit silently turn the assertion into one
     * about a non-zero value, where reducing the scale changes nothing.</p>
     *
     * @param committed the decoded account rows, keyed by account identifier
     * @return the identifier of the account carrying a zero balance
     * @throws IllegalStateException if no committed row carries a zero balance, which would leave the
     *     unstripped-scale property unexercised rather than merely unasserted
     */
    private static long zeroBalanceAccount(Map<Long, Map<String, Object>> committed) {
        return committed.entrySet().stream()
                .filter(entry -> money(entry.getValue(), BALANCE_FIELD).signum() == 0)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no committed account carries a zero"
                        + " balance, so the zero overpunch and the unstripped scale are unexercised"));
    }

    /**
     * Reads the column names of one relation from the engine's own catalogue, in declared order.
     *
     * <p>Assumptions: the catalogue is read rather than the shipped definition text, because what
     * matters is what the engine actually publishes after the definition ran. Dropped attributes are
     * excluded and system attributes are skipped by the positive ordinal filter, so the list is the
     * user-visible one a query would see.</p>
     *
     * @param schema the schema holding the relation
     * @param relation the relation name
     * @return its column names in declared order, never {@code null}
     * @throws SQLException if the catalogue cannot be read
     * @throws IllegalStateException if the relation publishes no column, which means it is absent or
     *     unreadable -- a defect to report against the file that owns it and never something for this
     *     class to create
     */
    private static List<String> catalogueColumnsOf(String schema, String relation)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        String sql = "select a.attname from pg_attribute a"
                + " join pg_class c on c.oid = a.attrelid"
                + " join pg_namespace n on n.oid = c.relnamespace"
                + " where n.nspname = ? and c.relname = ? and a.attnum > 0 and not a.attisdropped"
                + " order by a.attnum";
        try (Connection connection = POSTGRES.createConnection("");
                PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, schema);
            select.setString(2, relation);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
        }
        if (columns.isEmpty()) {
            throw new IllegalStateException("the relation " + schema + '.' + relation
                    + " publishes no column, so it is absent or unreadable; that is a defect to report"
                    + " against the file that owns it and never something for this class to create");
        }
        return columns;
    }

    /**
     * Reads the rendered type of one column of one relation from the engine's own catalogue.
     *
     * <p>Assumptions: the rendered form carries the precision and the scale together, so one comparison
     * settles both and neither can be asserted while the other silently moves.</p>
     *
     * @param schema the schema holding the relation
     * @param relation the relation name
     * @param column the column name
     * @return the rendered type, for example an exact numeric with its precision and scale, never
     *     {@code null}
     * @throws SQLException if the catalogue cannot be read
     * @throws IllegalStateException if the relation does not publish that column
     */
    private static String catalogueTypeOf(String schema, String relation, String column)
            throws SQLException {
        String sql = "select format_type(a.atttypid, a.atttypmod) from pg_attribute a"
                + " join pg_class c on c.oid = a.attrelid"
                + " join pg_namespace n on n.oid = c.relnamespace"
                + " where n.nspname = ? and c.relname = ? and a.attname = ?";
        try (Connection connection = POSTGRES.createConnection("");
                PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, schema);
            select.setString(2, relation);
            select.setString(3, column);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("the relation " + schema + '.' + relation
                            + " does not publish " + column);
                }
                return rows.getString(1);
            }
        }
    }

    /**
     * Reads one scalar from the engine as text, so a count and a classification share one helper.
     *
     * @param connection an open connection to the container
     * @param sql a statement yielding exactly one row of one column
     * @return that value rendered as text, never {@code null}
     * @throws SQLException if the statement cannot be run
     * @throws IllegalStateException if the statement yields no row
     */
    private static String singleValue(Connection connection, String sql) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(sql);
                ResultSet rows = select.executeQuery()) {
            if (!rows.next()) {
                throw new IllegalStateException("the catalogue yielded no row for " + sql);
            }
            return rows.getString(1);
        }
    }

    /**
     * Extracts the data-definition names a job declares over one inclusive one-based line span.
     *
     * <p>Assumptions: a definition line begins with the job-control prefix immediately followed by the
     * name, so the name is the characters between that prefix and the first blank. Reading the job file
     * itself rather than restating its contents is what makes the line numbers this class cites
     * self-verifying: a definition that moved would fail the case that reads it rather than leaving a
     * stale citation behind.</p>
     *
     * @param jobPath the job file, repository-relative, with its filename case as the disk carries it
     * @param firstLine the one-based first line of the span to read
     * @param lastLine the one-based last line of the span to read, inclusive
     * @return the definition names in the order the job declares them, never {@code null}
     */
    private static List<String> definitionNamesOf(String jobPath, int firstLine, int lastLine) {
        List<String> all = linesOf(jobPath);
        List<String> names = new ArrayList<>();
        for (int line = firstLine; line <= lastLine; line++) {
            String text = all.get(line - 1);
            int nameStart = 2;
            int nameEnd = text.indexOf(' ', nameStart);
            names.add(nameEnd < 0 ? text.substring(nameStart) : text.substring(nameStart, nameEnd));
        }
        return names;
    }

    /**
     * Reads a reference-only baseline file out of the working tree, line by line.
     *
     * <p>Assumptions: the file is read and never written, and the bytes are taken in a single-byte
     * encoding so that a record-oriented baseline file cannot fail to decode. The baseline is
     * reference-only: it is cited by path and line, never modified, never re-pinned and never
     * retro-documented.</p>
     *
     * @param repositoryRelativePath the path to read, relative to the repository root
     * @return its lines in file order, never {@code null}
     * @throws IllegalStateException if the file is absent, which makes every citation of it dead
     * @throws UncheckedIOException if the file cannot be read
     */
    private static List<String> linesOf(String repositoryRelativePath) {
        Path source = repositoryRoot().resolve(repositoryRelativePath);
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("the baseline file " + source + " is absent, so every"
                    + " citation of it in this class is a dead reference; check the filename case,"
                    + " which this baseline mixes on disk");
        }
        try {
            return Files.readAllLines(source, StandardCharsets.ISO_8859_1);
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot read the baseline file " + source, cause);
        }
    }

    /**
     * Reads the effective lines of the profile document this class activates, comments excluded.
     *
     * <p>Assumptions: comment lines are excluded and the exclusion is load-bearing rather than tidy.
     * That document argues its own settings at length and quotes REJECTED forms of the schema
     * resolution statement in its commentary, naming other schemas as examples of what the compiled
     * guard refuses -- so a case that read the commentary would find those names and fail on the
     * document's own explanation of why they are wrong.</p>
     *
     * @return the document's non-comment lines in file order, never {@code null}
     * @throws IllegalStateException if the document is absent from the classpath, which means the
     *     profile this class activates cannot be the one it asserts about
     * @throws UncheckedIOException if the document cannot be read
     */
    private static List<String> profileDocumentLines() {
        try (InputStream stream = StatementAccountRepositoryIT.class.getClassLoader()
                .getResourceAsStream(TEST_PROFILE_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("the profile document " + TEST_PROFILE_RESOURCE
                        + " is absent from the classpath, so the profile this class activates cannot"
                        + " be the one it asserts about");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return reader.lines().filter(line -> !line.strip().startsWith("#")).toList();
            }
        } catch (IOException cause) {
            throw new UncheckedIOException(
                    "cannot read the profile document " + TEST_PROFILE_RESOURCE, cause);
        }
    }

    /**
     * Applies every shipped definition file, unchanged, in the order an operator applies them.
     *
     * <p>Assumptions: each file is copied into the container and executed by the engine's OWN client
     * rather than sent through the driver, and the reason is syntactic. Two of the files wrap themselves
     * in an explicit transaction and several carry dollar-quoted procedural blocks whose bodies hold
     * semicolons, so any client-side splitting on a statement terminator would cut them in half. Handing
     * the whole file to the client the shipping documentation tells an operator to use removes the
     * question entirely.</p>
     *
     * <p>Assumptions: the host paths are resolved from the repository root located by probe rather than
     * by a fixed number of parent steps, so this class runs identically from the module directory and
     * from the reactor root.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws IllegalStateException if a shipped file is absent, naming the path so the defect is
     *     reported against the file that owns it rather than remedied here
     */
    private static void applyDeployedSchema() {
        Path root = repositoryRoot();
        int sequence = 0;
        for (String script : DEPLOYED_SCHEMA_SCRIPTS) {
            Path source = root.resolve(script);
            if (!Files.isRegularFile(source)) {
                throw new IllegalStateException("the shipped definition file " + source
                        + " is absent, so this class cannot assert anything about the relations the"
                        + " reporting context reads; that file owns them and this class must not"
                        + " create them");
            }
            String target = "/tmp/deployed-" + sequence + ".sql";
            sequence++;
            POSTGRES.copyFileToContainer(MountableFile.forHostPath(source), target);
            List<String> arguments = new ArrayList<>(List.of("-v", "ON_ERROR_STOP=1"));
            for (String acknowledgement : BOOTSTRAP_ACKNOWLEDGEMENTS) {
                arguments.add("-c");
                arguments.add("set " + acknowledgement + " = 'on'");
            }
            arguments.add("-f");
            arguments.add(target);
            runEngineClient(arguments.toArray(String[]::new));
        }
    }

    /**
     * Runs the engine's own client inside the container and fails loudly on a non-zero result.
     *
     * <p>Assumptions: the client is invoked without a credential, because it runs inside the container
     * as the operating-system user the engine trusts locally. That is what lets this class apply the
     * shipped files and load the corpus with no credential written anywhere in it.</p>
     *
     * @param arguments the client arguments following the connection flags
     * @throws IllegalStateException if the client reports a failure, or if the calling thread is
     *     interrupted while the client is running
     * @throws UncheckedIOException if the client cannot be run at all
     */
    private static void runEngineClient(String... arguments) {
        List<String> command = new ArrayList<>(List.of(
                "psql", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(), "-q"));
        command.addAll(List.of(arguments));
        try {
            org.testcontainers.containers.Container.ExecResult result =
                    POSTGRES.execInContainer(command.toArray(String[]::new));
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("the engine client refused " + command + ": "
                        + result.getStderr() + result.getStdout());
            }
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot run " + command, cause);
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + command, cause);
        }
    }

    /**
     * Loads the two fixtures this class reads into the relations their owning service declares.
     *
     * <p>Assumptions: every value inserted is DECODED from the committed fixture through the shared
     * codec against its registered descriptor, so the load carries the record layout the migration is
     * defined against rather than a second transcription of it. Alternatives Considered: parsing the
     * fixed-width rows here by offset. Rejected because the descriptor registry is the one place offsets
     * are declared, a second parser in this class would drift from it in silence, and the fixture's
     * geometry is precisely what several assertions here depend on.</p>
     *
     * <p>Assumptions: the loads are inserts and nothing else. This class defines no relation, conveys
     * no privilege and discards nothing; the boundary this directory draws is on DEFINITIONS, and
     * inserting rows sits inside it because a fixture has to be loaded before anything can be read.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws IllegalStateException if any row cannot be loaded
     */
    private static void loadFixtureCorpus() {
        try (Connection connection = POSTGRES.createConnection("")) {
            loadAccounts(connection);
            loadCardCrossReferences(connection);
        } catch (SQLException cause) {
            throw new IllegalStateException("cannot load the account-lookup fixture corpus", cause);
        }
    }

    /**
     * Loads the account fixture into the account master.
     *
     * <p>Assumptions: all five declared money fields are inserted even though the projection publishes
     * only two, because the columns behind the other three are declared with no absent state and the
     * insert has to supply them. The optimistic-locking column is supplied as its initial value for the
     * same reason and for no other: it is the owning context's column, no assertion here reads it, and
     * the projection does not publish it at all.</p>
     *
     * @param connection an open connection able to write the account schema
     * @throws SQLException if any row cannot be inserted
     */
    private static void loadAccounts(Connection connection) throws SQLException {
        String sql = "insert into " + OWNING_SCHEMA + '.' + OWNING_RELATION
                + "(account_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date,"
                + " expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip,"
                + " group_id, version) values (?,?,?,?,?,?,?,?,?,?,?,?,0)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Map<String, Object> row : decodeAll(ACCOUNT_FIXTURE, ACCOUNT_DESCRIPTOR)) {
                insert.setLong(1, integral(row, ACCOUNT_ID_FIELD));
                insert.setString(2, text(row, ACTIVE_STATUS_FIELD));
                insert.setBigDecimal(3, money(row, ACCOUNT_MONEY_FIELDS.get(0)));
                insert.setBigDecimal(4, money(row, ACCOUNT_MONEY_FIELDS.get(1)));
                insert.setBigDecimal(5, money(row, ACCOUNT_MONEY_FIELDS.get(2)));
                insert.setObject(6, date(row, "ACCT-OPEN-DATE"));
                insert.setObject(7, date(row, BASELINE_EXPIRATION_FIELD));
                insert.setObject(8, date(row, "ACCT-REISSUE-DATE"));
                insert.setBigDecimal(9, money(row, ACCOUNT_MONEY_FIELDS.get(3)));
                insert.setBigDecimal(10, money(row, ACCOUNT_MONEY_FIELDS.get(4)));
                insert.setString(11, text(row, "ACCT-ADDR-ZIP"));
                insert.setString(12, text(row, GROUP_ID_FIELD));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Loads the statement-path cross-reference fixture into the card cross-reference.
     *
     * <p>Assumptions: this is the statement-path fixture and it is the only cross-reference fixture
     * loaded here, because loading the report-path one as well would insert one card twice and the
     * relation's key is the card number. The card number is inserted because it is the key and for no
     * other reason: no assertion in this class reads it, none renders it, and every diagnostic below
     * names an account identifier instead -- which is not cardholder data and which the reference prints
     * in full on every statement.</p>
     *
     * @param connection an open connection able to write the account schema
     * @throws SQLException if any row cannot be inserted
     */
    private static void loadCardCrossReferences(Connection connection) throws SQLException {
        String sql = "insert into " + CROSS_REFERENCE_RELATION
                + "(card_num, customer_id, account_id) values (?,?,?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Map<String, Object> row
                    : decodeAll(STATEMENT_PATH_XREF_FIXTURE, XREF_DESCRIPTOR)) {
                insert.setString(1, text(row, XREF_CARD_NUMBER_FIELD));
                insert.setLong(2, integral(row, XREF_CUSTOMER_ID_FIELD));
                insert.setLong(3, integral(row, XREF_ACCOUNT_ID_FIELD));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Decodes every row of one fixture through the shared codec against its registered descriptor.
     *
     * <p>Assumptions: each fixture is line-oriented with one record per line, and the newline is a FILE
     * convention that is not part of any record -- the declared length excludes it, so reading a fixture
     * as one continuous byte stream would mis-align every record after the first. The bytes are taken in
     * a single-byte encoding so that one character is one byte and every declared offset holds, which
     * matters here in particular because the sign of a money field is overpunched into a byte that is
     * not a digit.</p>
     *
     * @param fileName the fixture file name inside {@link #FIXTURE_DIRECTORY}
     * @param descriptorName the registered copybook descriptor name
     * @return one decoded field map per row, in file order, never {@code null}
     * @throws IllegalStateException if the fixture is absent from the classpath
     * @throws UncheckedIOException if the fixture cannot be read
     */
    private static List<Map<String, Object>> decodeAll(String fileName, String descriptorName) {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(descriptorName);
        String resource = FIXTURE_DIRECTORY + '/' + fileName;
        try (InputStream stream = StatementAccountRepositoryIT.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("the fixture " + resource + " is absent");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.ISO_8859_1))) {
                return reader.lines()
                        .filter(line -> !line.isBlank())
                        .map(line -> FixedWidthCodec.decodeRecord(
                                line.getBytes(StandardCharsets.ISO_8859_1), spec))
                        .toList();
            }
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot read the fixture " + resource, cause);
        }
    }

    /**
     * Reads one decoded character field, trimmed of the pad that carries it to its declared width.
     *
     * <p>Assumptions: the trailing blanks are removed because the copybook pads a character field to a
     * declared width while several target columns are variable-width. A fixed-width target column pads
     * the value back on retrieval, so trimming here loses nothing either way.</p>
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return the field's value with trailing blanks removed, never {@code null}
     */
    private static String text(Map<String, Object> row, String field) {
        return String.valueOf(row.get(field)).stripTrailing();
    }

    /**
     * Reads one decoded unsigned integral field.
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return the field's value
     */
    private static long integral(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    /**
     * Reads one decoded monetary field as an exact decimal.
     *
     * <p>Assumptions: the value stays an exact decimal from the codec to the column and never passes
     * through an approximate numeric type, which transformation rule T3 of the migration plan requires
     * of every value in the money path. The prohibition is not this file's preference: the shared
     * layering rules fail a build over an approximate numeric member anywhere beneath the analysed root,
     * and that rule is owned by {@code LayeringRulesTest} in the shared kernel rather than restated
     * here.</p>
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return the field's exact value, never {@code null}
     */
    private static BigDecimal money(Map<String, Object> row, String field) {
        return (BigDecimal) row.get(field);
    }

    /**
     * Reads one decoded character field holding an already-ordered calendar date.
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return that date, never {@code null}
     */
    private static LocalDate date(Map<String, Object> row, String field) {
        return LocalDate.parse(text(row, field));
    }

    /**
     * Resolves the repository root by walking up from the working directory.
     *
     * <p>Assumptions: located by the presence of {@code services/pom.xml} rather than by a fixed number
     * of parent steps, so this class runs identically from the reactor root and from the module
     * directory. A relative path would resolve differently between those two invocations.</p>
     *
     * @return the repository root, never {@code null}
     * @throws IllegalStateException if no ancestor carries the reactor descriptor
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("services/pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "no ancestor of the working directory carries services/pom.xml");
    }

    /**
     * Records every statement the persistence provider emits, so a generated read can be inspected.
     *
     * <p>Assumptions: the provider instantiates this type by name from the property declared on the
     * class above, so it is public with an implicit no-argument constructor and its store is static --
     * the instance the provider builds is not the one a test could otherwise reach. Alternatives
     * Considered: reading the provider's mapping model alone, which the version case also does.
     * Rejected as the only mechanism because the model answers what the provider RECOGNISES while this
     * answers what it EMITTED, and the property under test is a property of the emitted text.</p>
     *
     * <p>Trade-offs: the store grows for the lifetime of the class and is never cleared, which is
     * accepted because the case that reads it asks only whether a matching statement exists and whether
     * any matching statement names a forbidden column -- neither question is affected by statements from
     * an earlier case, and clearing between cases would make the result depend on execution order.</p>
     */
    public static final class EmittedStatements implements StatementInspector {

        /**
         * Every statement seen, in emission order, safe for concurrent appends and reads.
         */
        private static final List<String> SEEN = new CopyOnWriteArrayList<>();

        /**
         * Records one statement and returns it unchanged.
         *
         * <p>Assumptions: the statement is returned exactly as received, because this inspector observes
         * and never rewrites -- a returned change would alter what the provider executes and the case
         * would then assert about a statement the deployment never issues.</p>
         *
         * @param sql the statement the provider is about to issue
         * @return that same statement, unchanged
         */
        @Override
        public String inspect(String sql) {
            SEEN.add(sql);
            return sql;
        }

        /**
         * Selects the recorded statements that name one relation.
         *
         * @param relation the unqualified relation name to match
         * @return the matching statements in emission order, never {@code null}
         */
        static List<String> namingRelation(String relation) {
            return SEEN.stream()
                    .filter(sql -> sql.toLowerCase(Locale.ROOT).contains(relation))
                    .toList();
        }
    }

    /**
     * The narrowest context that can create the lookup's repository proxy.
     *
     * <p>Assumptions: the configuration is nested and names the two persistence packages explicitly
     * rather than component-scanning from the module root, for the reason all four sibling integration
     * tests record: scanning the root would instantiate the orchestration client and the filter chain,
     * so a context started to read one row would additionally need an execution identifier and an
     * object-store location, and a failure to supply either would read as a lookup defect.
     * Alternatives Considered: a data-access test slice, which would restrict auto-configuration to
     * persistence and remove the cloud values supplied above entirely. It is not available -- the
     * artifact carrying that annotation is absent from {@code services/reporting-service/pom.xml} and
     * from the reactor's managed set -- and adding a dependency to reshape a test is not a trade this
     * module makes.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.reporting.domain")
    @EnableJpaRepositories("com.carddemo.reporting.repository")
    static class AccountLookupTestApplication {
    }
}
