package com.carddemo.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.auth.domain.User;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Pins the alternate-key lookup of {@code auth.users} onto a real PostgreSQL engine.
 *
 * <p>This class exists for one member of {@link UserRepository}: the lookup by
 * {@code cognito_sub}, which resolves an authenticated provider subject to the local row whose
 * {@code user_type} supplies a request's authorities. Two properties decide whether that member is
 * sound, and neither can be established without an engine, which is why this is an integration test
 * and not a unit test.
 *
 * <p>The first is UNIQUENESS. The member returns a single value, and it may do so only because the
 * column admits one row per subject. That is a database constraint, not a property of the Java: a
 * test that inserted one row and read it back would pass identically against a non-unique column, so
 * the constraint is asserted by attempting the duplicate the constraint exists to refuse.
 *
 * <p>The second is the MISSING-USER outcome. A subject the identity provider has issued need not yet
 * have a local row, so absence is an ordinary state rather than a failure, and the member reports it
 * as an empty result rather than by raising. That distinction is what a caller branches on, so it is
 * asserted directly.
 *
 * <h2>What this class deliberately does not assert</h2>
 *
 * <p>Assumptions: no case here asserts on a password, and none may be added. The target of this
 * bounded context persists none -- {@code V1__auth.sql} declares no password column, which is the one
 * point in the migration where parity is declined, recorded in AAP section 0.7.8 -- so an assertion
 * about one would describe a column that does not exist. The subject reference this class reads is
 * the whole of the link between a token and a row.
 *
 * <p>Assumptions: no case re-asserts the two keyset browse queries. Their contract is the page
 * boundary rather than the engine, and the properties that decide them -- strict comparison, the
 * surplus probe row, the ordering each returns -- are stated on those members. Repeating them here
 * would add a second copy of an assertion to keep true, and the copy that still passed would hide the
 * one that should not.
 *
 * <p>Assumptions: the schema is created by Flyway rather than by the persistence provider, and
 * {@code src/test/resources/application-test.yml} is where that is configured -- it sets
 * {@code ddl-auto: validate}, so the provider VALIDATES the entity against the migrated table instead
 * of generating one. That has a consequence worth naming: every case below also depends on
 * {@link User} agreeing with {@code V1__auth.sql}, so a mapping that drifted from the migration would
 * fail this class at context start rather than at an assertion.
 */
// WHY : Alternatives Considered: asserting these two properties WITHOUT an engine was considered
//       first, because it is cheaper -- a reflective check that the method name resolves to a mapped
//       property, that the return type is Optional, and that the entity member carries
//       unique = true. It was rejected because it would prove the wrong things. The annotation
//       attribute is inert under ddl-auto: validate, so a unique = true on an entity whose table
//       carries no unique index would satisfy such a check while the database accepted duplicates;
//       and no reflective assertion can distinguish an empty result from a raised exception, which is
//       the missing-user contract itself. The properties under test are properties of the SCHEMA, so
//       they are asserted against a schema.
// WHY : Alternatives Considered: an H2 or other in-memory engine would have removed the container
//       dependency, and was rejected on fidelity. The column is a NATIVE uuid type and the guarantee
//       under test is a unique index over it; an engine that emulated either would let this class
//       pass while the deployed cluster behaved differently, which is the one way a green result here
//       could mean nothing.
@Testcontainers
@SpringBootTest(
        classes = UserRepositoryIT.IdentityPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class UserRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine, the major version the
     * deployed cluster runs.
     */
    // WHY : Assumptions: this is deliberately the SAME digest the four repository integration tests
    //       of the transaction bounded context already name. Two integration tests pinning two
    //       engines could disagree about one constraint, and the disagreement would surface as
    //       whichever ran second; pinning by digest rather than by tag is what makes the reference
    //       immutable, since a publisher may rebuild and republish a minor tag on a new base layer.
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The container the assertions run against, started once for this class.
     *
     * <p>Assumptions: the type comes from {@code org.testcontainers.postgresql} and NOT from the
     * legacy {@code org.testcontainers.containers}. Testcontainers 2.0.5 ships both and only the
     * legacy one is deprecated, so importing that package would carry a compile-time notice into
     * every future build of this module for no benefit. The replacement is not generic, so the
     * declaration carries no type argument.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /**
     * The identifier of the row every case in this class resolves, eight characters exactly.
     *
     * <p>Assumptions: the width is the key's declared width rather than a convenient one.
     * {@code user_id} is {@code CHAR(8)} in {@code V1__auth.sql}, transcribed from
     * {@code SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy} line 18, so a shorter literal
     * would be stored blank-padded and would compare equal to itself under the type's own rules
     * without ever exercising the full-width case.
     */
    private static final String SEED_USER_ID = "ADMIN001";

    /**
     * The identifier of the second row, used only by the duplicate-subject case.
     *
     * <p>Assumptions: it differs from {@link #SEED_USER_ID} so that the insert the duplicate case
     * attempts can only be refused by the SUBJECT constraint. Reusing one identifier would trip the
     * primary key instead, and the case would then report a passing result while proving nothing
     * about uniqueness of the alternate key.
     */
    private static final String OTHER_USER_ID = "USER0001";

    /** The provider subject the seeded row carries, fixed so no case depends on a generated value. */
    private static final UUID SEED_SUBJECT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** A subject no row carries, used by the missing-user case. */
    // WHY : Assumptions: this differs from SEED_SUBJECT in its first group rather than its last, so a
    //       truncating or prefix-matching comparison would still distinguish the two. Two values
    //       differing only in a trailing character would let such a defect pass.
    private static final UUID ABSENT_SUBJECT = UUID.fromString("99999999-2222-3333-4444-555555555555");

    /**
     * The number of links of a cause chain the diagnostic helper below will walk.
     *
     * <p>Assumptions: a bound is required rather than prudent. A cause chain is not guaranteed acyclic
     * by the platform -- a throwable may return itself, or two may reference each other -- so an
     * unbounded walk is a hang rather than a slow test. Sixteen is far beyond any chain a constraint
     * violation produces, so the bound never truncates a real diagnosis.
     */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** The port under test. */
    @Autowired
    private UserRepository users;

    /** Runs each write in its own committed transaction, so a constraint is evaluated per case. */
    // WHY : Alternatives Considered: annotating this class @Transactional and letting the framework
    //       roll each case back was rejected, and the reason is specific to what is under test. A
    //       UNIQUE constraint is evaluated when a statement executes, but a persistence context
    //       defers its inserts until it flushes, so inside one ambient transaction the duplicate case
    //       would either need an explicit flush to fail at all or would fail at an unpredictable
    //       point. Committing each write through an explicit template makes the refusal arrive from
    //       the statement that caused it.
    @Autowired
    private TransactionTemplate commit;

    /**
     * Empties the table and inserts the single seeded row every case starts from.
     *
     * <p>Assumptions: the table is cleared rather than relied upon to be empty. The container is
     * started once for the class, so a row committed by one case would otherwise survive into the
     * next, and the duplicate case commits deliberately.
     */
    @BeforeEach
    void seedOneUser() {
        this.commit.executeWithoutResult(status -> {
            this.users.deleteAll();
            this.users.save(new User(SEED_USER_ID, "Admin", "One", "A", SEED_SUBJECT));
        });
    }

    /**
     * A subject that a row carries resolves to that row.
     *
     * <p>Assumptions: this asserts the identifier AND the type of the resolved row, not merely that
     * something was found. The type is the reason the lookup exists -- it is what a request's
     * authorities are derived from -- so a case that stopped at presence would pass against a lookup
     * that resolved the wrong row.
     */
    @Test
    void aKnownSubjectResolvesToItsRow() {
        Optional<User> found = this.users.findByCognitoSub(SEED_SUBJECT);

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(SEED_USER_ID);
        assertThat(found.get().getUserType()).isEqualTo("A");
        assertThat(found.get().getCognitoSub()).isEqualTo(SEED_SUBJECT);
    }

    /**
     * A subject no row carries yields an empty result rather than raising.
     *
     * <p>This is the missing-user contract the member documents: a subject the identity provider has
     * issued need not yet have a local row, so absence is reported and not thrown.
     */
    @Test
    void anUnknownSubjectYieldsAnEmptyResult() {
        Optional<User> found = this.users.findByCognitoSub(ABSENT_SUBJECT);

        assertThat(found).isEmpty();
    }

    /**
     * The table refuses a second row carrying a subject another row already holds.
     *
     * <p>This is the uniqueness guarantee that makes the member's single-valued return type sound.
     * The insert names a DIFFERENT primary key, so the only constraint it can violate is the unique
     * index over the subject column.
     */
    // WHY : Refactoring Rationale: this case first asserted only that SOME exception was raised, which
    //       was too weak to be evidence. Any failure would have satisfied it -- a mis-seeded row, a
    //       rejected user type, a closed connection -- so it could have reported a passing result while
    //       the unique index was absent. It now asserts the TRANSLATED exception type and that the
    //       refusal names the offending column, so the case can only pass for the reason it claims.
    // WHY : Alternatives Considered: pinning the persistence provider's own constraint-violation type
    //       was rejected in favour of Spring's DataIntegrityViolationException. The provider's type is
    //       an implementation detail reached only through the cause chain, so pinning it would couple
    //       this case to a translation table that a provider upgrade may rearrange; the Spring type is
    //       the documented boundary a repository caller sees, so it is both the stabler assertion and
    //       the one that describes what a caller would actually catch.
    // WHY : Assumptions: the column name is matched case-insensitively against the WHOLE cause chain
    //       rather than against the top-level message. The engine's own text names the violated unique
    //       index, and Spring's wrapper carries that text through the chain rather than reproducing it
    //       at the top, so matching only the outer message would fail on a correct refusal.
    @Test
    void aDuplicateSubjectIsRefusedByTheDatabase() {
        assertThatThrownBy(() -> this.commit.executeWithoutResult(status ->
                        this.users.save(new User(OTHER_USER_ID, "User", "Two", "U", SEED_SUBJECT))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(failure -> assertThat(causeChainText(failure)).contains("cognito_sub"));
    }

    /**
     * After a refused duplicate, the original row is still the one the subject resolves to.
     *
     * <p>Assumptions: this case is not a repeat of the resolve case above. It asserts that the
     * refusal left no partial state behind -- that the subject still resolves, still resolves to ONE
     * row, and still resolves to the ORIGINAL row rather than to the rejected one. A constraint that
     * refused the statement but let the row land would satisfy the previous case and fail this one.
     */
    @Test
    void aRefusedDuplicateLeavesTheOriginalRowResolvable() {
        try {
            this.commit.executeWithoutResult(status ->
                    this.users.save(new User(OTHER_USER_ID, "User", "Two", "U", SEED_SUBJECT)));
        } catch (RuntimeException expected) {
            // The refusal itself is asserted by aDuplicateSubjectIsRefusedByTheDatabase; this case is
            // about the state it leaves behind, so the failure is swallowed here on purpose.
        }

        Optional<User> found = this.users.findByCognitoSub(SEED_SUBJECT);

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(SEED_USER_ID);
        assertThat(this.users.count()).isEqualTo(1L);
    }

    /**
     * Two rows carrying two different subjects each resolve to their own row.
     *
     * <p>Assumptions: this is what shows the lookup discriminates rather than returning whatever row
     * it finds. With one row in the table, a member that ignored its argument entirely would pass
     * every case above except the missing-subject one; with two rows, it cannot.
     */
    @Test
    void distinctSubjectsResolveToDistinctRows() {
        UUID otherSubject = UUID.fromString("77777777-2222-3333-4444-555555555555");
        this.commit.executeWithoutResult(status ->
                this.users.save(new User(OTHER_USER_ID, "User", "Two", "U", otherSubject)));

        assertThat(this.users.findByCognitoSub(SEED_SUBJECT))
                .get()
                .extracting(User::getUserId)
                .isEqualTo(SEED_USER_ID);
        assertThat(this.users.findByCognitoSub(otherSubject))
                .get()
                .extracting(User::getUserId)
                .isEqualTo(OTHER_USER_ID);
    }

    /**
     * Flattens a throwable and every cause beneath it into one lower-cased string.
     *
     * <p>Assumptions: the walk is bounded and it tolerates a self-referencing cause, because a
     * diagnostic helper that looped would turn a failed assertion into a hung build. The bound is
     * generous relative to any real chain, so reaching it means the chain is malformed rather than
     * merely deep.
     *
     * @param failure the throwable to flatten, of type {@code Throwable}, never {@code null}
     * @return the concatenated messages of the throwable and its causes, lower-cased so a caller can
     *     match a column name without depending on the engine's capitalisation, never {@code null}
     */
    private static String causeChainText(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            text.append(current.getClass().getName()).append(' ').append(current.getMessage()).append(' ');
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * The minimal Spring Boot configuration this suite runs against.
     *
     * <p>Assumptions: this configuration declares no component scan, and that omission is the point
     * of it. The module's own application class scans this bounded context and registers components
     * that read deployment properties -- an explicit token decoder among them -- whereas the
     * container's coordinates arrive here as a connection-details bean. Naming the two persistence
     * packages explicitly leaves the framework's own auto-configuration to build the pool from that
     * bean, and keeps a persistence assertion from failing for a security-configuration reason.
     *
     * <p>Trade-offs: declaring it nested rather than as a file of its own keeps the package charter's
     * closed file set true, at the cost of not being reusable by a future sibling integration test.
     * That cost is accepted for the reason the sibling context's charter gives for the same choice:
     * two tests reaching one schema through two differently configured contexts could disagree about
     * what they reached.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.auth.domain")
    @EnableJpaRepositories("com.carddemo.auth.repository")
    static class IdentityPersistenceTestApplication {
    }
}
