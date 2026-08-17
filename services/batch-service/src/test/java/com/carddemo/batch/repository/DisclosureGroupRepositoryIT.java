package com.carddemo.batch.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.carddemo.batch.domain.DisclosureGroup;
import com.carddemo.batch.domain.DisclosureGroup.DisclosureGroupId;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Holds {@code reference.disclosure_groups} -- the interest-rate lookup this module reads and never
 * writes -- against the harness schema that both creates and seeds it.
 *
 * <h2>The decision that shapes this whole class, stated first because its absence looks like a gap</h2>
 *
 * <p>A reviewer arriving here will look for a case asserting that a write to this table is REFUSED,
 * and will not find one. That is deliberate, and the reason is a property of the harness rather than
 * a matter of taste. This module holds no write grant on the {@code reference} schema at all: the
 * migration plan scopes the batch role's cross-schema writes to {@code ledger} and {@code account}
 * only. But the harness script beside this file creates no role and issues no privilege statement, so
 * every case here connects as the container's own superuser and every table is writable at test time.
 * A permission failure is therefore NOT OBSERVABLE from this class, and a case written to expect one
 * would either fail against a correctly-scoped deployment or pass for the wrong reason here.</p>
 *
 * <p>Alternatives Considered: asserting the read-only property at RUN time, by issuing a write
 * through the repository and expecting the engine to reject it with a privilege error. Rejected as
 * unavailable rather than as inferior -- the superuser connection described above means no privilege
 * error can be provoked, so the case would assert nothing while appearing to assert the central
 * property of this table. The property is therefore carried at COMPILE time instead:
 * {@link DisclosureGroupRepository} extends the narrow
 * {@link org.springframework.data.repository.Repository} marker rather than a CRUD base, so no write
 * method exists on it to call. A method that does not exist cannot be invoked from anywhere in the
 * module, which is a wider guarantee than a rejected call at one call site, and
 * {@link #theRepositoryDeclaresNoWriteMethodAtAll()} reads that shape back so a base type widened in
 * a later edit fails a named case instead of silently admitting writes.</p>
 *
 * <p>Trade-offs: what that compile-time reading establishes is narrower than it may appear, and the
 * limit is stated rather than left for a reader to discover. It proves the REPOSITORY cannot write.
 * It does not prove the privilege is correctly scoped in a provisioned environment -- that belongs to
 * {@code data-migration/sql/V0__schemas_and_roles.sql} and to infrastructure verification, neither of
 * which this class can reach. Both halves are needed for the property to hold end to end, and this
 * class owns exactly one of them.</p>
 *
 * <p>Assumptions: this is the one proof in this class with NO baseline citation, and it is called out
 * because every other case below names the reference line it pins. The narrow base type is a
 * target-side design decision about a Java interface; the reference program has no analogue of it,
 * reaching its dataset through a file description opened for input. Citing a line for it would
 * manufacture provenance that does not exist.</p>
 *
 * <h2>The key, and the transposition it invites</h2>
 *
 * <p>Assumptions: the key is sixteen bytes at offset zero whose physical component order is group
 * identifier, then transaction type code, then transaction category code. Three independent sources
 * agree: {@code app/cpy/CVTRA02Y.cpy:5-8} declares {@code DIS-GROUP-KEY} over
 * {@code DIS-ACCT-GROUP-ID PIC X(10)}, {@code DIS-TRAN-TYPE-CD PIC X(02)} and
 * {@code DIS-TRAN-CAT-CD PIC 9(04)} in that order, which sums to ten plus two plus four;
 * {@code app/jcl/DISCGRP.jcl:40} declares {@code KEYS(16 0)} on the cluster; and the file description
 * at {@code app/cbl/CBACT04C.cbl:77-82} repeats the same three widths in the same sequence.</p>
 *
 * <p>Assumptions: the reference program ASSIGNS those components in a different order from the one it
 * stores them in, and that mismatch is the defect every ordering case below exists to catch.
 * {@code app/cbl/CBACT04C.cbl:210} moves the group identifier, then {@code :211} moves the CATEGORY
 * code, then {@code :212} moves the TYPE code -- the reverse of the physical order for the last two.
 * A test transcribed from the move sequence rather than from the record layout would build its key
 * with those two transposed, and because both are short numeric-looking codes such a key can still
 * FIND A ROW: the seed carries both type {@code 01} with category {@code 0002} and type {@code 02}
 * with category {@code 0001}, at different rates. The transposed lookup would then return a rate,
 * the case would go green, and the wrong operand would reach the accrual arithmetic. Every assertion
 * here is written against the physical order for that reason, and
 * {@link #twoPairsSharingTheirDigitsCarryDifferentRates()} is the case that makes the claim provable
 * rather than incidental.</p>
 *
 * <h2>The DEFAULT fallback: the mechanism, not the control flow</h2>
 *
 * <p>Assumptions: the fallback is reachable only because a miss on the first read is a NORMAL
 * outcome. {@code app/cbl/CBACT04C.cbl:415} issues the keyed read and its invalid-key clause only
 * displays a message; {@code :422} then accepts status {@code '00'} or {@code '23'} alike, so
 * record-not-found is not an error; and {@code :436} tests for that {@code '23'} before {@code :437}
 * substitutes the default group and {@code :438} retries. This class asserts the two READS that
 * mechanism is built from -- a miss that yields empty without raising, and a substituted-group hit --
 * and asserts nothing about the sequencing between them.</p>
 *
 * <p>Alternatives Considered: giving the repository a convenience method that performed the retry
 * itself, so a caller could ask for a rate once and receive either the direct hit or the fallback.
 * Rejected because the two-step retry is control flow the golden masters OBSERVE, and the migration
 * plan places it in {@code InterestCalculationService}; folding it into a repository method would
 * hide the step boundary the goldens compare against and move a documented behaviour out of the layer
 * that owns it. The interface accordingly declares one keyed read and no default-group convenience,
 * and this class asserts the mechanism that caller relies on rather than the caller's own logic.</p>
 *
 * <p>Assumptions: the substitution replaces the GROUP COMPONENT ALONE.
 * {@code app/cbl/CBACT04C.cbl:437} moves the literal into the group-identifier field only, leaving
 * the type and category codes in place from the first attempt, which is why the seed shape below is
 * what it is. {@link #theFallbackSubstitutesTheGroupComponentAlone()} asserts that explicitly: a case
 * that substituted all three components would resolve a row without demonstrating the rule.</p>
 *
 * <p>Assumptions: a missing default row ABENDS the reference program rather than defaulting the rate
 * to zero, which is why the seeded rows are asserted here as a precondition instead of being taken on
 * trust. The retry at {@code app/cbl/CBACT04C.cbl:443} issues its read with NO invalid-key clause at
 * {@code :444} and accepts only status {@code '00'} at {@code :446}, so a second miss reaches
 * {@code :455} and the abend at {@code :458}. The observable symptom is a crash attributed to the
 * interest step while that step is behaving exactly as specified, and the rows whose absence causes
 * it are authored by another context -- {@code reference-service}'s
 * {@code V2__seed_reference.sql} -- so a missing row is a CROSS-SERVICE defect and not a local one.
 * That is recorded so an operator reading a failure of
 * {@link #everySeededDefaultPairIsReachableAtItsSeededRate(String, String, String)} looks in the
 * owning service rather than in this module.</p>
 *
 * <p>Assumptions: the seed requirement is ONE ROW PER type-and-category PAIR, not one row overall,
 * because the retry key keeps the original type and category and replaces only the group. Three
 * independent sources put that pair count at seventeen: the harness script seeds seventeen rows under
 * the padded default group; the committed reference fixture
 * {@code tests/fixtures/interest/default_fallback/discgrp.txt} carries exactly seventeen rows, all
 * under that group; and the shipped extract {@code app/data/ASCII/discgrp.txt} carries the same
 * seventeen pairs for each of its three groups. A single default row would satisfy one pair and leave
 * sixteen accounts abending.</p>
 *
 * <h2>What this class does not assert</h2>
 *
 * <p>Assumptions: this table supplies the RATE and nothing else, so no arithmetic and no rounding
 * appears below. The computation that consumes the rate at {@code app/cbl/CBACT04C.cbl:464-465}
 * multiplies before dividing and carries no rounding phrase, and the zero-rate gate at {@code :214}
 * suppresses accrual outright; all of that belongs to the tier-one service cases, together with the
 * fallback as control flow. The control break, the business-date parameter and job-level golden
 * parity belong to the tier-two interest job case. The account master's own columns, its atomicity
 * proof and its version ruling belong to {@code AccountRepositoryIT}, whose {@code group_id} column
 * is the group component of this key. The ascending category-balance walk belongs to
 * {@code TransactionCategoryBalanceRepositoryIT}, whose type and category columns are the other two
 * components of this key and whose ordering is what makes the control break correct. The physical
 * shape of this table and its seed belong to {@code reference-service}.</p>
 *
 * <p>Assumptions: this tier asserts ROWS, not BYTES. The fifty-byte fixed-width record and its
 * trailing filler are a mapper-level concern -- the fixture guide beside this file records that this
 * record's filler is written as ASCII zeros rather than blanks -- and no byte image is asserted here.
 * The rate values below are read as decimals, not decoded from their zoned form.</p>
 *
 * <p>Assumptions: no diagnostic below renders a record. This table carries no card number and no
 * personal data, so it is the lowest-risk table in the package on that axis, and the convention is
 * kept anyway rather than relaxed where it happens to cost nothing: the migration plan masks primary
 * account numbers to their last four digits and never returns card verification values, and a
 * convention a reader sees applied only where it is expensive reads as an accident.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
@Testcontainers
// Assumptions: the Parameter Store config-data location is disabled for this context because it
//     cannot start otherwise. The module's application.yml imports an optional parameter-store
//     location, and resolving it builds a management client while configuration is still loading; on
//     a build host with no region set, that client is built from an unresolved placeholder and the
//     context aborts before a single row is read. The flag stops the LOAD rather than the location
//     resolution, which is why it suffices: the loader consults it and returns an empty contribution
//     before any client is constructed.
// Alternatives Considered: narrowing the configuration import to the classpath document alone, which
//     the siblings measured and rejected -- an import list contributes locations rather than
//     replacing them, so the parameter-store location survives the override. Supplying a real region
//     instead was also rejected: the loader would then issue a genuine lookup against a public
//     endpoint, turning a persistence assertion into a network call.
@SpringBootTest(
        classes = DisclosureGroupRepositoryIT.RateLookupPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.aws.parameterstore.enabled=false")
@ActiveProfiles("test")
class DisclosureGroupRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine, the major line the
     * deployed cluster runs.
     *
     * <p>Assumptions: the version is recorded in prose because a digest states nothing a reader
     * recognises, and the two must be changed together.</p>
     */
    // Assumptions: this is the digest every sibling in this package already pins, and matching it is
    //     load-bearing rather than tidy. Two classes pinning two engines could disagree about one
    //     schema, and the disagreement would surface as whichever of them ran second -- a failure
    //     attributed to the wrong class. The readable tags for the same release are mutable, so a
    //     rebuild could change engine behaviour under an unchanged assertion here.
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The classpath-relative path of the harness script the container runs as it becomes ready.
     *
     * <p>Assumptions: this script is the sole origin of both the table this class reads and the rows
     * it reads from it. Neither arrives through Flyway: the test profile has Flyway manage the
     * {@code batch} schema alone, so {@code reference.disclosure_groups} exists at test time only
     * because this script creates it, and its seventeen default rows exist only because this script
     * seeds them idempotently. The file NAME is therefore part of the contract rather than a
     * description of the file, and neither renaming nor relocating it is a local change.</p>
     *
     * <p>Assumptions: the ORDER the two halves of the schema arrive in is load-bearing, and
     * {@code withInitScript} is what fixes it. Testcontainers runs this script before the application
     * context opens its first connection, so the table exists when the persistence provider runs its
     * validation pass over the mapping that references it. Issuing the same statements from a
     * lifecycle callback in Java would place them after the context had already started and failed.</p>
     */
    private static final String HARNESS_SCRIPT =
            "db/testharness/test-harness-schemas-and-foreign-tables.sql";

    /**
     * The default group identifier in the padded ten-character form the seed stores it in.
     *
     * <p>Assumptions: the three trailing blanks are data, not formatting. The reference field is
     * {@code PIC X(10)} and {@code app/cbl/CBACT04C.cbl:437} moves the seven-character literal into
     * it, where the language left-justifies and space-fills, so the key the retry searches for is the
     * padded form. The harness seeds that padded form for the same reason.</p>
     */
    private static final String DEFAULT_GROUP_ID = "DEFAULT   ";

    /** The same default group identifier written bare, as a reader would naturally type it. */
    private static final String BARE_DEFAULT_GROUP_ID = "DEFAULT";

    /**
     * A group identifier the harness does NOT seed, used wherever a case needs a genuine miss.
     *
     * <p>Assumptions: this is the group the reference interest fixtures carry on their accounts --
     * {@code tests/fixtures/interest/happy_path/discgrp.txt} keys its single row on it, and
     * {@code app/data/ASCII/discgrp.txt} carries it as one of three groups -- while the harness seeds
     * the default group alone. It is therefore an absent key HERE and a present one in the reference
     * suite, which is exactly the arrangement the fallback exists to serve.</p>
     */
    private static final String UNSEEDED_GROUP_ID = "A000000000";

    /**
     * The number of default rows the harness seeds, one per transaction type and category pair.
     *
     * <p>Assumptions: seventeen is measured from three independent sources that agree, not chosen --
     * the harness seed, the seventeen-row fixture at
     * {@code tests/fixtures/interest/default_fallback/discgrp.txt}, and the seventeen pairs each group
     * carries in {@code app/data/ASCII/discgrp.txt}.</p>
     */
    private static final int SEEDED_DEFAULT_ROWS = 17;

    /** The purchase transaction type code, the type the fixtures exercise most. */
    private static final String TYPE_PURCHASE = "01";

    /** The transaction type code whose seeded categories all carry a zero rate. */
    private static final String TYPE_ZERO_RATED = "02";

    /** The first transaction category code within a type, zero-padded to its declared width. */
    private static final String CATEGORY_FIRST = "0001";

    /** The second transaction category code within a type, zero-padded to its declared width. */
    private static final String CATEGORY_SECOND = "0002";

    /**
     * The rate the seed carries for the pair of type {@code 01} and category {@code 0002}.
     *
     * <p>Assumptions: constructed from a string rather than from a numeric literal, so the scale is
     * exactly two as the column declares and not whatever a literal would carry. The picture at
     * {@code app/cpy/CVTRA02Y.cpy:9} is {@code PIC S9(04)V99}, an annual percentage with four integer
     * digits and two fractional.</p>
     */
    private static final BigDecimal RATE_TWENTY_FIVE = new BigDecimal("25.00");

    /** The rate the seed carries for the pair of type {@code 01} and category {@code 0001}. */
    private static final BigDecimal RATE_FIFTEEN = new BigDecimal("15.00");

    /**
     * The zero rate the seed carries for every category of the zero-rated transaction type.
     *
     * <p>Assumptions: zero is MEANINGFUL DATA here and not a missing row. The gate at
     * {@code app/cbl/CBACT04C.cbl:214} tests the rate against zero and suppresses accrual when it is
     * zero, so a caller must be able to tell a seeded zero-rate group from an absent one -- which is
     * why the repository returns the row rather than the bare rate. Whether that gate fires is a
     * tier-one assertion and not one made here.</p>
     */
    private static final BigDecimal RATE_ZERO = new BigDecimal("0.00");

    /**
     * The scale every rate read from this table must present.
     *
     * <p>Assumptions: the column is declared with two fractional digits, so the provider returns a
     * decimal already at that scale and no rescaling is needed to compare one.</p>
     */
    private static final int RATE_SCALE = 2;

    /**
     * Every method name a CRUD or JPA repository base would contribute, and none of which may appear.
     *
     * <p>Assumptions: the list enumerates the write surface of both wider bases rather than sampling
     * it, because a base type widened in a later edit contributes all of these at once and a sampled
     * list would catch only the sampled names. The two flush-and-write compounds are included
     * separately from their plain forms because the JPA base declares each independently.</p>
     */
    private static final List<String> WRITE_METHOD_NAMES = List.of(
            "save",
            "saveAll",
            "saveAndFlush",
            "saveAllAndFlush",
            "delete",
            "deleteAll",
            "deleteById",
            "deleteAllById",
            "deleteInBatch",
            "deleteAllInBatch",
            "deleteAllByIdInBatch",
            "flush");

    /**
     * The container every assertion in this class runs against, started once for the class.
     *
     * <p>Assumptions: the type is imported from {@code org.testcontainers.postgresql} rather than
     * from the legacy container package, which the pinned Testcontainers release also ships and
     * deprecates. The replacement type is not generic, so the declaration carries no type
     * argument.</p>
     */
    // Alternatives Considered: an in-memory engine, which would start in a fraction of the time a
    //     container costs. Rejected because two properties asserted below are engine behaviours that
    //     no substitute reproduces: a fixed-character column comparing equal to an unpadded literal,
    //     which is what makes the bare and padded default group one value, and the exact scale a
    //     numeric column returns. Either would need rewriting to assert something weaker, and no
    //     embedded driver is on this module's classpath in any case.
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(HARNESS_SCRIPT);

    /** The rate table under test, injected as the production repository interface. */
    @Autowired
    private DisclosureGroupRepository repository;

    /**
     * The persistence context, used by the one case that stages a row it does not find seeded.
     *
     * <p>Assumptions: staging goes through the persistence context because the repository has no
     * write method to stage through, which is the property this class exists to establish.</p>
     */
    @Autowired
    private EntityManager entityManager;

    /**
     * The transaction boundary the staging case runs inside so that it can be undone.
     *
     * <p>Assumptions: a boundary is required rather than convenient, because the persistence context
     * refuses a flush with no transaction bound to the thread, and the staging case must flush before
     * it reads.</p>
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** A plain JDBC handle, used for the seeded-row count no entity mapping can express. */
    private JdbcTemplate jdbc;

    /**
     * Registers the running container's coordinates as configuration properties.
     *
     * @param registry the Spring test property registry that this method adds the container's JDBC
     *     URL, user name and credential to as deferred suppliers; must not be {@code null}
     */
    // Assumptions: the two migration credentials are registered as well and are not redundant with
    //     the datasource pair. The module's application.yml binds the migration user and its
    //     credential to placeholders carrying no fallback, and the framework reads those keys because
    //     no connection-details bean supplies them instead -- the annotation that would contribute
    //     one lives in an artifact this module does not declare. Leaving them unregistered aborts the
    //     context on an unresolved placeholder before any migration runs.
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Opens a JDBC handle before each case.
     *
     * <p>Assumptions: no table is emptied here, and the omission is deliberate rather than overlooked.
     * Every case below reads rows the harness seeded, so emptying this table would remove the very
     * fixture under test; and the one case that stages a row of its own undoes that row by rolling
     * its transaction back, so no case leaves state for the next one to trip over.</p>
     *
     * @param dataSource the container-backed datasource the handle is opened over, injected per case
     *     rather than held as a field so that the handle is rebuilt for each; must not be
     *     {@code null}
     */
    @BeforeEach
    void openJdbcHandle(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Reads back the repository's declared surface and finds it carries no write method.
     *
     * <p>This is the compile-time reading of the read-only contract described on this class, and the
     * only form that proof can take here: the harness connects as a superuser, so a privilege failure
     * cannot be provoked at run time. The assertion is on the interface's METHOD SET rather than on
     * the outcome of a call, because a method that is not declared cannot be called from anywhere in
     * the module.</p>
     *
     * <p>Assumptions: no reference line is cited because none exists. The narrow base type is a
     * decision about a Java interface, and the reference program has no analogue of it -- it opens its
     * dataset for input through a file description. Every other case below cites the line it pins;
     * this one states the absence instead of inventing a citation.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    // Alternatives Considered: asserting only that the two wider bases are not assignable, which is
    //     one statement instead of three. Rejected as insufficient on its own: an interface can
    //     declare a write method DIRECTLY without extending either base, and a base-type assertion
    //     would not see it. Reading the method set catches both routes, and the base-type assertions
    //     are kept alongside it because they name the specific widening a later edit is most likely to
    //     make.
    @Test
    @DisplayName("the rate repository declares one keyed read and no write method of any kind")
    void theRepositoryDeclaresNoWriteMethodAtAll() {
        List<String> declared = Arrays.stream(DisclosureGroupRepository.class.getMethods())
                .map(Method::getName)
                .toList();

        assertThat(declared)
                .as("the interface exposes the keyed read alone, so no other access path exists")
                .containsExactly("findByIdIs");
        assertThat(declared)
                .as("a write method here would fail at the database rather than at compilation")
                .doesNotContainAnyElementsOf(WRITE_METHOD_NAMES);

        assertThat(Repository.class.isAssignableFrom(DisclosureGroupRepository.class))
                .as("the narrow marker base is what withholds the write surface")
                .isTrue();
        assertThat(CrudRepository.class.isAssignableFrom(DisclosureGroupRepository.class))
                .as("the CRUD base would contribute save and delete to a read-only table")
                .isFalse();
        assertThat(JpaRepository.class.isAssignableFrom(DisclosureGroupRepository.class))
                .as("the JPA base would additionally contribute flush and the batch deletes")
                .isFalse();
    }

    /**
     * Resolves a seeded key built in the record's physical component order.
     *
     * <p>Pins the key layout at {@code app/cpy/CVTRA02Y.cpy:5-8} and the sixteen-byte key operand at
     * {@code app/jcl/DISCGRP.jcl:40}, corroborated by the file description at
     * {@code app/cbl/CBACT04C.cbl:77-82}. The pair chosen is type {@code 01} with category
     * {@code 0002}, whose seeded rate differs from every neighbouring pair, so a key assembled in any
     * other order cannot return this rate by coincidence.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a key in physical order of group, type then category resolves its seeded rate")
    void aSeededKeyResolvesInPhysicalComponentOrder() {
        Optional<DisclosureGroup> found =
                this.repository.findByIdIs(keyOf(DEFAULT_GROUP_ID, TYPE_PURCHASE, CATEGORY_SECOND));

        assertThat(found)
                .as("the harness seeds this pair, so the keyed read must resolve it")
                .isPresent();

        DisclosureGroup group = found.orElseThrow();
        // Assumptions: the group component is compared on its trimmed value while the other two are
        //     compared whole. Only the group is shorter than its column -- seven characters in ten --
        //     so only it can come back padded, and whether the driver returns the padding is an
        //     implementation detail of how it renders a fixed-character column. The CONTRACT is that
        //     the padded and unpadded forms are one value, which the padded-and-bare case below
        //     asserts directly; pinning the driver's rendering here would couple this case to that
        //     detail instead.
        assertThat(group.getAcctGroupId().trim()).isEqualTo(BARE_DEFAULT_GROUP_ID);
        assertThat(group.getTranTypeCd()).isEqualTo(TYPE_PURCHASE);
        assertThat(group.getTranCatCd()).isEqualTo(CATEGORY_SECOND);
        // Assumptions: compared with compareTo rather than with equals, because equality on this
        //     decimal type is scale-sensitive -- a value of 25.0 is not equal to 25.00 while being the
        //     same rate -- and a scale difference is a mapping question the scale case below asks
        //     separately.
        assertThat(group.getInterestRate()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(RATE_TWENTY_FIVE);
    }

    /**
     * Finds that an unseeded group yields an empty result rather than raising.
     *
     * <p>Pins {@code app/cbl/CBACT04C.cbl:415}, whose invalid-key clause only displays a message, and
     * {@code app/cbl/CBACT04C.cbl:422}, which accepts status {@code '00'} or {@code '23'} alike so
     * that record-not-found is a normal outcome. That neutrality is what makes the fallback reachable
     * at all: were a miss an error, the substitution at {@code :437} could never be reached.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unseeded group misses neutrally, returning empty without raising")
    void anUnseededGroupReturnsEmptyWithoutRaising() {
        DisclosureGroupId absent = keyOf(UNSEEDED_GROUP_ID, TYPE_PURCHASE, CATEGORY_FIRST);

        assertThatCode(() -> this.repository.findByIdIs(absent))
                .as("a miss is reported, never raised, on both of the reference's two reads")
                .doesNotThrowAnyException();
        assertThat(this.repository.findByIdIs(absent))
                .as("the harness seeds the default group alone, so this group has no row")
                .isEmpty();
    }

    /**
     * Finds that a key whose type and category are transposed resolves nothing.
     *
     * <p>Pins the assignment order at {@code app/cbl/CBACT04C.cbl:210-212}, which moves the group
     * identifier, then the CATEGORY code, then the TYPE code -- the reverse of the physical order for
     * the last two. This case builds the key a transcription of that sequence would produce, passing
     * the category value where the type belongs, and asserts it finds nothing.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    // Assumptions: the transposed key is constructible at all because both components are strings,
    //     which is precisely why the defect is a silent one rather than a compilation error. The
    //     four-character category value is simply wider than the two-character type column, so no row
    //     can match it; the engine compares and finds nothing rather than refusing the comparison,
    //     since a width limit constrains what may be STORED in the column and not what may be
    //     compared against it.
    @Test
    @DisplayName("a key transposing the type and category codes resolves no row")
    void transposingTheTypeAndCategoryCodesResolvesNothing() {
        Optional<DisclosureGroup> transposed =
                this.repository.findByIdIs(keyOf(DEFAULT_GROUP_ID, CATEGORY_SECOND, TYPE_PURCHASE));

        assertThat(transposed)
                .as("the assignment order at CBACT04C:210-212 is not the record's physical order")
                .isEmpty();
        assertThat(this.repository.findByIdIs(keyOf(DEFAULT_GROUP_ID, TYPE_PURCHASE, CATEGORY_SECOND)))
                .as("the same two values in physical order do resolve, so the miss is the ordering")
                .isPresent();
    }

    /**
     * Finds that two seeded pairs sharing their digits in swapped positions carry different rates.
     *
     * <p>This is the case that makes the ordering claim provable rather than incidental, and it is the
     * strongest form the proof can take. Type {@code 01} with category {@code 0002} and type
     * {@code 02} with category {@code 0001} are BOTH seeded, so a caller that transposed the two
     * components would not miss -- it would resolve the other row and receive a different rate. The
     * transposed lookup would go green while supplying the wrong operand to the accrual computation
     * at {@code app/cbl/CBACT04C.cbl:464-465}, which is exactly the class of silent defect a test has
     * to catch.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    // Alternatives Considered: proving the ordering with the transposed-key miss above and nothing
    //     further. Rejected as incomplete in the direction that matters: a miss shows only that ONE
    //     malformed key finds nothing, which a reader could put down to the width difference between
    //     the two components. This pair shows the failure mode that survives width -- two well-formed
    //     keys, same digits, different rates -- so the ordering is load-bearing rather than merely
    //     unverified.
    @Test
    @DisplayName("type 01 category 0002 and type 02 category 0001 are distinct rows at distinct rates")
    void twoPairsSharingTheirDigitsCarryDifferentRates() {
        BigDecimal purchaseSecondCategory = this.repository
                .findByIdIs(keyOf(DEFAULT_GROUP_ID, TYPE_PURCHASE, CATEGORY_SECOND))
                .orElseThrow()
                .getInterestRate();
        BigDecimal zeroRatedFirstCategory = this.repository
                .findByIdIs(keyOf(DEFAULT_GROUP_ID, TYPE_ZERO_RATED, CATEGORY_FIRST))
                .orElseThrow()
                .getInterestRate();

        assertThat(purchaseSecondCategory).usingComparator(BigDecimal::compareTo)
                .isEqualTo(RATE_TWENTY_FIVE);
        assertThat(zeroRatedFirstCategory).usingComparator(BigDecimal::compareTo)
                .isEqualTo(RATE_ZERO);
        assertThat(purchaseSecondCategory.compareTo(zeroRatedFirstCategory))
                .as("a transposed key would resolve the other row and return the wrong rate")
                .isNotZero();
    }

    /**
     * Resolves the default group from both the padded and the bare form of its identifier.
     *
     * <p>Pins {@code app/cbl/CBACT04C.cbl:437}, which moves the seven-character literal
     * {@code 'DEFAULT'} into a {@code PIC X(10)} field where the language space-fills it, so the key
     * the retry searches for is the padded form while the literal a reader writes is the bare one.
     * Both must resolve the same row or the fallback works only for whichever form happens to be
     * used.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    // Assumptions: the equivalence rests on an EXTERNAL type contract, not on anything this class
    //     does. The harness declares the column as fixed-character, and a fixed-character comparison
    //     ignores trailing blanks, which is what makes the two forms one value. Were the column
    //     variable-character instead, the seeded value would have to be the bare literal for this
    //     comparison to hold, and the padded key the reference actually builds would then miss. That
    //     is the kind of dependency that breaks silently if the column type changes, so it is named
    //     here rather than relied upon quietly.
    @Test
    @DisplayName("the default group resolves from the padded key and from the bare literal alike")
    void theDefaultGroupResolvesPaddedAndBare() {
        Optional<DisclosureGroup> padded =
                this.repository.findByIdIs(keyOf(DEFAULT_GROUP_ID, TYPE_PURCHASE, CATEGORY_FIRST));
        Optional<DisclosureGroup> bare =
                this.repository.findByIdIs(keyOf(BARE_DEFAULT_GROUP_ID, TYPE_PURCHASE, CATEGORY_FIRST));

        assertThat(padded)
                .as("the seed stores the padded form, which is the form the retry builds")
                .isPresent();
        assertThat(bare)
                .as("a bare literal must reach the same row, or the fallback is form-dependent")
                .isPresent();
        assertThat(bare.orElseThrow().getInterestRate()).usingComparator(BigDecimal::compareTo)
                .as("both forms address one row, so both must carry one rate")
                .isEqualTo(padded.orElseThrow().getInterestRate());
    }

    /**
     * Resolves the fallback row from a key in which only the group component has been substituted.
     *
     * <p>Pins {@code app/cbl/CBACT04C.cbl:436-439}: on status {@code '23'} the program moves the
     * default literal into the group-identifier field ALONE and retries, leaving the type and category
     * codes as the first attempt set them. This case performs both reads in sequence -- the miss on
     * the account's own group, then the hit on the substituted one -- and asserts that the row
     * returned still carries the ORIGINAL type and category.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    // Assumptions: the case substitutes exactly one of the three components, and that restraint is
    //     the whole assertion. Replacing all three would resolve a row too, and would demonstrate
    //     nothing about the rule -- the seed would answer any well-formed default key. Holding the
    //     type and category fixed across the two reads is what shows the retry narrows to the group.
    @Test
    @DisplayName("substituting the group alone resolves the fallback and keeps type and category")
    void theFallbackSubstitutesTheGroupComponentAlone() {
        assertThat(this.repository.findByIdIs(
                        keyOf(UNSEEDED_GROUP_ID, TYPE_PURCHASE, CATEGORY_SECOND)))
                .as("the first read misses, which is the status 23 that triggers the substitution")
                .isEmpty();

        DisclosureGroup fallback = this.repository
                .findByIdIs(keyOf(DEFAULT_GROUP_ID, TYPE_PURCHASE, CATEGORY_SECOND))
                .orElseThrow();

        assertThat(fallback.getAcctGroupId().trim())
                .as("only the group component changed between the two reads")
                .isEqualTo(BARE_DEFAULT_GROUP_ID);
        assertThat(fallback.getTranTypeCd())
                .as("the type code is carried over from the first attempt unchanged")
                .isEqualTo(TYPE_PURCHASE);
        assertThat(fallback.getTranCatCd())
                .as("the category code is carried over from the first attempt unchanged")
                .isEqualTo(CATEGORY_SECOND);
        assertThat(fallback.getInterestRate()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(RATE_TWENTY_FIVE);
    }

    /**
     * Confirms every seeded default pair is individually reachable at the rate the owning seed states.
     *
     * <p>Pins the seventeen rows the harness seeds, corroborated row for row by the committed fixture
     * {@code tests/fixtures/interest/default_fallback/discgrp.txt}. The pair list is exhaustive rather
     * than sampled because the seed requirement is one row PER pair -- a consequence of
     * {@code app/cbl/CBACT04C.cbl:437} substituting only the group -- so a single absent pair abends
     * every account presenting it while sixteen others accrue normally.</p>
     *
     * <p>Assumptions: a failure of this case is a CROSS-SERVICE defect and not a local one. These rows
     * are authored by {@code reference-service}'s {@code V2__seed_reference.sql} and mirrored by the
     * harness beside this file; this class asserts them as a PRECONDITION and creates none of them. An
     * operator reading a failure here should look in the owning service rather than in this module.</p>
     *
     * @param typeCd the two-character transaction type code of the seeded pair under test, as the
     *     record carries it at {@code app/cpy/CVTRA02Y.cpy:7}
     * @param catCd the four-character transaction category code of that pair, zero-padded as the
     *     {@code PIC 9(04)} picture at {@code app/cpy/CVTRA02Y.cpy:8} renders it
     * @param expectedRate the annual percentage rate the owning seed carries for that pair, written as
     *     a decimal string so that it parses at the column's own scale
     */
    // Assumptions: the pair of type 07 and category 0001 is seeded at fifteen, NOT at zero, and the
    //     value is taken from the owning migration rather than decoded from the reference bytes. The
    //     two shipped encodings of this dataset disagree on exactly that pair -- the EBCDIC extract
    //     reads it as fifteen and the ASCII extract as zero -- and the owner settles it in favour of
    //     the EBCDIC extract, registered as D-SEED-ENCODING-AUTHORITY in
    //     docs/architecture/cobol-to-service-traceability.md. Mirroring the owner is what makes a test
    //     and a provisioned environment agree; asserting the ASCII value instead would fail this case
    //     against correct data.
    @ParameterizedTest(name = "type {0} category {1} is seeded at {2}")
    @CsvSource({
        "01, 0001, 15.00",
        "01, 0002, 25.00",
        "01, 0003, 25.00",
        "01, 0004, 25.00",
        "02, 0001, 0.00",
        "02, 0002, 0.00",
        "02, 0003, 0.00",
        "03, 0001, 0.00",
        "03, 0002, 0.00",
        "03, 0003, 0.00",
        "04, 0001, 15.00",
        "04, 0002, 15.00",
        "04, 0003, 15.00",
        "05, 0001, 15.00",
        "06, 0001, 15.00",
        "06, 0002, 15.00",
        "07, 0001, 15.00",
    })
    void everySeededDefaultPairIsReachableAtItsSeededRate(
            String typeCd, String catCd, String expectedRate) {

        Optional<DisclosureGroup> seeded =
                this.repository.findByIdIs(keyOf(DEFAULT_GROUP_ID, typeCd, catCd));

        assertThat(seeded)
                .as("a missing default pair abends the interest step at CBACT04C:455 and :458")
                .isPresent();
        assertThat(seeded.orElseThrow().getInterestRate()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal(expectedRate));

        Integer seededRows = this.jdbc.queryForObject(
                "SELECT count(*) FROM reference.disclosure_groups WHERE acct_group_id = ?",
                Integer.class,
                DEFAULT_GROUP_ID);
        assertThat(seededRows)
                .as("the pair list above is exhaustive, so the seeded count must equal its length")
                .isEqualTo(SEEDED_DEFAULT_ROWS);
    }

    /**
     * Reads a rate back at the exact scale its column declares.
     *
     * <p>Pins {@code app/cpy/CVTRA02Y.cpy:9}, where {@code DIS-INT-RATE PIC S9(04)V99} fixes two
     * fractional digits, against the two-scale numeric column the harness declares. The scale matters
     * beyond tidiness because the rate is an operand of the accrual computation at
     * {@code app/cbl/CBACT04C.cbl:464-465}: a value arriving at a different scale would change the
     * precision of that computation's intermediate result, and therefore its cents.</p>
     *
     * <p>Assumptions: the rate is carried as an exact decimal at every hop and never as a binary
     * approximation. The migration plan forbids binary floating-point types anywhere in the money
     * path, and an architecture rule in the shared kernel fails the build on one in any member
     * position, so the prohibition is executable rather than advisory. This case asserts the scale that
     * exactness is expressed at; it performs no arithmetic and applies no rounding, both of which
     * belong to the tier-one service cases.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a rate reads back exact at scale two, including a seeded zero")
    void theRateReadsBackExactAtScaleTwo() {
        BigDecimal fifteen = this.repository
                .findByIdIs(keyOf(DEFAULT_GROUP_ID, TYPE_PURCHASE, CATEGORY_FIRST))
                .orElseThrow()
                .getInterestRate();
        BigDecimal zero = this.repository
                .findByIdIs(keyOf(DEFAULT_GROUP_ID, TYPE_ZERO_RATED, CATEGORY_FIRST))
                .orElseThrow()
                .getInterestRate();

        assertThat(fifteen.scale()).isEqualTo(RATE_SCALE);
        assertThat(zero.scale()).isEqualTo(RATE_SCALE);
        assertThat(fifteen).usingComparator(BigDecimal::compareTo).isEqualTo(RATE_FIFTEEN);
        // Assumptions: the seeded zero is compared with compareTo like every other rate, and the
        //     choice is not cosmetic here. Equality on this decimal type is scale-sensitive, so a zero
        //     read at scale two is not equal to an unscaled zero while being the same rate; a case
        //     written with equals would pass or fail on the scale rather than on the value, which is
        //     the confusion this comparator removes.
        assertThat(zero).usingComparator(BigDecimal::compareTo).isEqualTo(RATE_ZERO);
    }

    /**
     * Resolves a group staged through the persistence context, and finds no trace of it after rollback.
     *
     * <p>Pins {@code tests/fixtures/interest/happy_path/discgrp.txt}, whose single row keys the group
     * the harness does not seed, at a rate of fifteen. That fixture is the DIRECT-HIT arrangement --
     * the account's own group has a row, so {@code app/cbl/CBACT04C.cbl:436} never sees status
     * {@code '23'} and the fallback is never entered -- and it is the arrangement no seeded row here
     * can express, since the harness seeds the default group alone.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    // Trade-offs: the row is staged through the persistence context because the repository has NO
    //     write method to stage it through, and that arrange path does not contradict the read-only
    //     claim this class makes: the claim is about the repository's declared surface, which the
    //     staging does not touch. The compromise accepted is that the case reaches past the interface
    //     under test to arrange its own fixture, which is why it is the only case that does so and why
    //     every other case relies on the harness seed instead.
    // Alternatives Considered: committing the staged row and removing it afterwards, which is the
    //     more familiar arrangement. Rejected because a committed write to the reference schema --
    //     even one later removed -- would leave this class briefly holding exactly the state its
    //     central claim says it never produces, and a cleanup that failed part-way would leave a row
    //     the seeded-count assertion above would then trip over. Rolling the transaction back reaches
    //     the same post-state without ever making the write durable, and the second half of this case
    //     asserts that post-state rather than assuming it.
    @Test
    @DisplayName("a staged direct-hit group resolves inside its transaction and vanishes on rollback")
    void aStagedGroupResolvesAndItsRollbackLeavesNoTrace() {
        DisclosureGroupId staged = keyOf(UNSEEDED_GROUP_ID, TYPE_PURCHASE, CATEGORY_FIRST);

        Optional<BigDecimal> stagedRate = this.transactionTemplate.execute(status -> {
            this.entityManager.persist(new DisclosureGroup(staged, RATE_FIFTEEN));
            this.entityManager.flush();
            Optional<BigDecimal> read =
                    this.repository.findByIdIs(staged).map(DisclosureGroup::getInterestRate);
            status.setRollbackOnly();
            return read;
        });

        assertThat(stagedRate)
                .as("a group with its own row is a direct hit, which never reaches the fallback")
                .isPresent();
        assertThat(stagedRate.orElseThrow()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(RATE_FIFTEEN);
        assertThat(this.repository.findByIdIs(staged))
                .as("the rollback leaves the reference schema exactly as the harness seeded it")
                .isEmpty();
    }

    /**
     * Assembles a lookup key from its three components in the record's physical order.
     *
     * <p>Assumptions: the parameter order of this helper IS the physical order of the key -- group,
     * then type, then category, as declared at {@code app/cpy/CVTRA02Y.cpy:5-8} -- and not the order
     * {@code app/cbl/CBACT04C.cbl:210-212} assigns the components in. Routing every case through one
     * builder is what keeps that distinction in a single place: a case that needs a deliberately
     * malformed key passes its arguments in the wrong order at the CALL SITE, where the transposition
     * is visible, rather than by assembling a key of its own.</p>
     *
     * @param acctGroupId the account group identifier, either bare or padded to its declared ten
     *     characters, both of which the fixed-character column treats as one value
     * @param tranTypeCd the two-character transaction type code
     * @param tranCatCd the four-character transaction category code, with its leading zeros intact so
     *     that {@code 0001} and {@code 1} cannot become two distinct keys
     * @return the assembled composite identifier, never {@code null}
     */
    private static DisclosureGroupId keyOf(
            String acctGroupId, String tranTypeCd, String tranCatCd) {

        return new DisclosureGroupId(acctGroupId, tranTypeCd, tranCatCd);
    }

    /**
     * The minimal application this class boots: the batch entities and their repositories, nothing else.
     *
     * <p>Assumptions: the entity scan is what subjects the rate mapping to the provider's schema
     * validation pass, which is how a disagreement between the mapping and the harness surfaces as a
     * startup failure here rather than as a wrong answer later.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.batch.domain")
    @EnableJpaRepositories("com.carddemo.batch.repository")
    static class RateLookupPersistenceTestApplication {
    }
}



