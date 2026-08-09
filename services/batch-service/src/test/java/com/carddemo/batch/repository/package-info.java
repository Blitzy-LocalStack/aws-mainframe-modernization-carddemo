/**
 * The integration tests that hold this module's persistence layer against a real PostgreSQL engine.
 *
 * <h2>Purpose</h2>
 *
 * <p>Every other test package in this module substitutes the store. That is correct for the rules --
 * a reject precedence, a fixed-point sum, a control break -- because those rules are arithmetic and a
 * database would only slow them down. It is not sufficient for the three properties this package
 * exists for, because each of them IS the database:</p>
 *
 * <ul>
 *   <li>that the production Flyway migration applies, creating {@code batch.batch_run} and the Spring
 *       Batch job-repository tables under the ownership a deployed environment gives them;</li>
 *   <li>that the eight entities this module maps across FOUR schemas -- three of which it does not
 *       own -- resolve against real tables with real declared widths, real check constraints and real
 *       secondary indexes;</li>
 *   <li>that the posting unit of work -- transcribed from {@code app/cbl/CBTRN02C.cbl} L424 to L444,
 *       where a category balance, an account and a posted transaction are written and then committed
 *       together at L440 to L442 -- both COMMITS and ROLLS BACK as ONE transaction while spanning two
 *       of those schemas.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: this package was empty while
 * {@code src/test/resources/db/testharness/test-harness-schemas-and-foreign-tables.sql} and the
 * Failsafe binding in {@code pom.xml} were both already authored for it. A harness with no executable
 * consumer proves nothing at all: its SQL can be read for correctness, but reading cannot show that
 * the entity mappings match it, that the constraints fire, or that the three-write commit is atomic.
 * It was worse than nothing while it sat unconsumed, because the module then LOOKED
 * integration-covered. The three classes here consume it.</p>
 *
 * <h2>How the schema is reached, and why in two halves</h2>
 *
 * <p>Assumptions: the schema arrives from two places and the split is not arbitrary. {@code batch} is
 * this module's own, so its objects come from the production migration on the classpath and from
 * nothing else -- that migration is part of what is under test. The other three schemas belong to
 * transaction-service, account-service and reference-service, and this module may depend on
 * {@code common-lib} and on no other sibling service, so their migrations are unreachable from this
 * test classpath. They are supplied instead by the init script named above, which each class hands to
 * its container through {@code withInitScript} so that it runs before Flyway opens a connection.</p>
 *
 * <p>Assumptions: the init script deliberately does NOT create {@code batch}. Its own Section 1
 * records the measurement behind that: an init script runs as the container's superuser, so a
 * {@code batch} schema created there is owned by that user, and the test profile's Flyway
 * {@code init-sqls} then assumes the NOLOGIN role {@code carddemo_batch_owner}, which is refused
 * CREATE on a schema it does not own with SQLSTATE 42501. Letting Flyway create it under that role
 * instead reproduces {@code data-migration/sql/V0__schemas_and_roles.sql} L713 exactly.</p>
 *
 * <h2>Rulings this package applies to every class in it</h2>
 *
 * <p>Alternatives Considered: an in-memory engine, rejected more firmly here than anywhere else in
 * the build. Two of the three properties above are engine behaviours -- cross-schema qualification
 * inside one transaction, and a CHECK constraint that rejects a row at the statement that writes it --
 * and a substitute implements both differently or not at all, so a passing assertion would say nothing
 * about the engine the jobs run against. No embedded driver appears on this module's classpath.</p>
 *
 * <p>Alternatives Considered: one shared abstract base class holding the container, so the three
 * classes below could inherit it and start one container between them. Rejected: a container held in
 * a base class is shared mutable state, so rows one class inserts are rows another reads, and the
 * failure then names whichever class ran second. Each class starts its own container and owns its own
 * schema state, which is what lets any one of the three be run alone and still mean something. The
 * accepted cost is three container starts and three copies of the container declaration.</p>
 *
 * <p>Assumptions: no connection literal appears anywhere in this package or in
 * {@code src/test/resources/application-test.yml}. A container assigns its host port as it starts, so
 * a literal authored beforehand would either address nothing or -- the worse failure, because it
 * passes -- address whatever database happened to be listening. Each class registers the container's
 * generated URL, user name and credential through {@code @DynamicPropertySource}.</p>
 *
 * <p>Assumptions: {@code @ServiceConnection} is NOT used, on a classpath fact rather than a
 * preference. It ships in {@code org.springframework.boot:spring-boot-testcontainers}, which four
 * sibling modules declare and this module's POM deliberately does not, so a reader arriving from one
 * of those files would otherwise expect a mechanism that cannot resolve here. Its absence is also why
 * each class registers {@code spring.flyway.user} and {@code spring.flyway.password} in addition to
 * the datasource pair: {@code application.yml} binds those two keys to placeholders with no fallback,
 * and Boot consults them precisely when no connection-details bean supplies them instead.</p>
 *
 * <p>Assumptions: every business date, timestamp and amount below is a literal. No case reads a wall
 * clock, because a value compared against the current time passes for a reason unrelated to the code
 * under test and fails only when two reads straddle a boundary -- a failure that reproduces at the
 * hour it was introduced and at no other. This mirrors the reference suite's own discipline of
 * injecting the business date through {@code PARM-DATE} rather than reading it.</p>
 *
 * <p>Assumptions: money is asserted as {@code BigDecimal} at scale two throughout, never as a
 * primitive floating-point value and never through a {@code double} comparison. That is transformation
 * rule T3 of the migration plan, and {@code common-lib}'s architecture rules fail the build on a
 * violation, so the discipline here is consistency with a gate rather than an independent policy.</p>
 *
 * <p>Assumptions: this package's file set is closed at the three integration classes and this charter.
 * A fixture builder, a shared constant holder or a container base class added later would reintroduce
 * the shared state the ruling above rejects.</p>
 *
 * <p>Every path under {@code app/} cited by any member of this package is reference material. It is
 * read as the specification, never modified, and keeps running exactly as it does today.</p>
 *
 * <p>A package declaration accepts no parameter, yields no value and raises nothing, so this block
 * carries no parameter, return or exception at-clause.</p>
 */
package com.carddemo.batch.repository;
