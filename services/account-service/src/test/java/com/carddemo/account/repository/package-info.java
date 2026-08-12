/**
 * The integration tests that hold this context's persistence layer against a real PostgreSQL engine.
 *
 * <h2>Purpose</h2>
 *
 * <p>The sibling {@code com.carddemo.account.api} and {@code com.carddemo.account.service} packages
 * substitute the store, which is correct for what they assert -- a status, a property name, a validation
 * order, which query a scan chooses and which key it seals. None of them can assert the three properties
 * this package exists for, because each of them IS the database:
 *
 * <ul>
 *   <li>that {@code V1__account.sql} applies as written, creating the three tables this context owns under
 *       the ownership a deployed environment gives them, and that every entity mapping validates against
 *       what it created;</li>
 *   <li>that the declared widths, nullability and check constraints of {@code account.customers} are real
 *       -- a {@code CHAR(10)} that had been authored as a {@code VARCHAR} pads nothing, and a check
 *       constraint that can never be false is indistinguishable from one that was mistyped;</li>
 *   <li>that the three keyset window queries the customer scan is built on order ascending, resume
 *       STRICTLY after a position and concatenate into a walk that visits every row exactly once;</li>
 *   <li>that {@code V2__account_inquiry_reply_ledger.sql} applies as written and that its claim statement
 *       reports a conflict on the second delivery of one request rather than inserting a second row or
 *       overwriting the first. That claim is the ENGINE's decision -- {@code INSERT ... ON CONFLICT DO
 *       NOTHING} reporting zero affected rows -- so a substituted ledger would return whatever a stub was
 *       told to and would pass against a statement with a typo in it;</li>
 *   <li>that the account update's two writes -- the customer row flushed first and the account row second
 *       -- COMMIT together and ROLL BACK together, which only an engine that can commit and roll back can
 *       show.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: the fourth property is a later addition, and the gap it closed is instructive.
 * The service test that covers those two writes substitutes the transaction manager and the controller test
 * substitutes the whole service, so every assertion about them was made against a component that cannot
 * commit and cannot roll back -- and the service test's own comment deferred the commit to "the
 * container-backed integration test" that did not yet exist. The property was therefore asserted nowhere
 * while appearing to be assigned somewhere, which is the failure mode a charter naming the split is
 * supposed to prevent. {@code AccountUpdateAtomicityIT} closes it.
 *
 * <p>Refactoring Rationale: the sibling {@code api} charter already named this package as the home of
 * container-backed repository assertions while the package did not exist, so the split of
 * responsibilities it describes had one side missing. The keyset queries were the concrete gap: they are
 * derived from their method names, so nothing but an engine can show that
 * {@code findByCustomerIdGreaterThanOrderByCustomerIdAsc} really is exclusive of its bound. An
 * off-by-one there would repeat one row on every page boundary of a scan that otherwise looked correct.
 *
 * <h2>How the schema is reached</h2>
 *
 * <p>Assumptions: the schema arrives from the production migration on the classpath and from nowhere
 * else. No initialisation script is supplied and none is wanted: this context owns every table it maps,
 * so {@code create-schemas: true} in the test profile plus {@code db/migration} is the whole of the
 * arrangement, and the migration is part of what is under test. That also makes
 * {@code ddl-auto: validate} in the test profile meaningful here rather than merely tolerated -- the
 * context refuses to start if an entity mapping and the migration disagree.
 *
 * <p>Assumptions: the test profile's Flyway {@code init-sqls} create the NOLOGIN role
 * {@code carddemo_account_owner} and assume it, so the migration's objects belong to that role exactly as
 * they do in a provisioned environment. Every {@code ALTER DEFAULT PRIVILEGES FOR ROLE} clause in
 * {@code data-migration/sql/V0__schemas_and_roles.sql} is keyed on the CREATING role and is inert
 * otherwise, which is why the ownership and not merely the schema's existence is what has to match.
 *
 * <h2>Rulings this package applies to every class in it</h2>
 *
 * <p>Alternatives Considered: an in-memory engine. Rejected because two of the three properties above are
 * engine behaviours -- fixed-width character padding and a check constraint refusing a row at the
 * statement that writes it -- and a substitute implements both differently or not at all, so a passing
 * assertion would say nothing about the engine this service runs against. No embedded driver appears on
 * this module's classpath.
 *
 * <p>Assumptions: no connection literal appears anywhere in this package. A container assigns its host
 * port as it starts, so a literal authored beforehand would either address nothing or -- the worse
 * failure, because it passes -- address whatever database happened to be listening. Each class registers
 * the container's generated URL, user name and credential through {@code @DynamicPropertySource}, and
 * registers {@code spring.flyway.user} and {@code spring.flyway.password} beside them because
 * {@code spring-boot-testcontainers} is deliberately absent from this module's POM, so no
 * {@code @ServiceConnection} and no connection-details bean is available to supply them.
 *
 * <p>Assumptions: every date, identifier and ciphertext below is a literal, and no case reads a wall
 * clock. A value compared against the current time passes for a reason unrelated to the code under test
 * and fails only when two reads straddle a boundary. This mirrors the reference suite's own discipline of
 * injecting the business date through {@code PARM-DATE} rather than reading it.
 *
 * <p>Assumptions: the two protected columns hold fixed bytes rather than the ciphertext of anything, and
 * no key material of any kind appears in this package. What a case may assert about them is that the
 * bytes survive a round trip unaltered, which is the property a {@code BYTEA} column has and a character
 * column does not.
 *
 * <h2>The Surefire and Failsafe selection contract</h2>
 *
 * <p>Assumptions: every class here ends in {@code IT}, and the suffix is what makes it run. The reactor
 * splits its two test phases by class name alone -- Surefire selecting {@code **}{@code /*Test.java} in
 * {@code test} and Failsafe selecting {@code **}{@code /*IT.java} in {@code integration-test}, both left
 * at their default include patterns in {@code services/pom.xml}. A class placed here and named to end in
 * {@code Test} would be run by Surefire in a phase that starts no container; a class named neither way
 * would match no pattern, would not run, and would not fail either, so the report would be complete and
 * green with one class missing from it.
 *
 * <p>Every path under {@code app/} cited by any member of this package is reference material. It is read
 * as the specification, never modified, and keeps running exactly as it does today.
 *
 * <p>Parameters, return values, exceptions or errors. A package declaration accepts no parameter, yields
 * no value and raises nothing, so this charter carries no such at-clause; the inapplicability is declared
 * rather than passed over so that a reader can tell it from an oversight.
 */
package com.carddemo.account.repository;
