/**
 * Web-layer slice tests for the REST adapters of the account bounded context.
 *
 * <p>Every class in this package holds the web layer of account-service alone, with each collaborator
 * beneath it arriving as a mock. The subject under test is the production package
 * {@code com.carddemo.account.api} and nothing deeper: what a request must look like to be accepted,
 * what status and body a response carries, which authority a route demands, and that no handler depends
 * on a previous call having happened.
 *
 * <p>Assumptions: two shapes are used, and the choice per class is the level the assertion needs rather
 * than a preference. A class asserting the MACHINE contract -- the route constants, the published
 * property names, the security expression, the status a handler returns -- reaches the handler by DIRECT
 * INVOCATION and by reading its metadata, because none of that needs a request to travel. A class
 * asserting the WIRE contract -- a serialised body, a rejected query parameter answered as a per-field
 * array -- drives a standalone {@code MockMvc} over the real controller with the shared advice
 * registered, which is what every other controller test in this build does, so a reader moving between
 * modules meets one shape.
 *
 * <p>Alternatives Considered: {@code @WebMvcTest} for the wire-level classes, which is the shape this
 * charter originally named for all of them. Rejected on what it would add: a Spring context, this
 * module's own auto-configuration and its security filter chain, none of which decides any outcome
 * asserted here -- while route membership and the security expression are already asserted from the
 * metadata by the contract class. No test in this build uses it, so adopting it here would also make
 * this the one module a reader had to learn twice.
 *
 * <p>Parameters, return values, exceptions or errors. A package declaration accepts no parameter,
 * yields no value and raises nothing, so this charter carries no such at-clause; the inapplicability
 * is declared rather than passed over so that a reader can tell it from an oversight.
 *
 * <h2>What is asserted here, and what is asserted elsewhere</h2>
 *
 * <p>Assumptions: the omissions matter more than an inventory, because an omission is what sends a
 * reader hunting here for an assertion that was never meant to be here. The transcribed validation
 * chains, the per-program validation order read as behaviour, and the dirty-check semantics of the
 * update path are asserted in the sibling {@code com.carddemo.account.service} test package against
 * mocked repositories; repository behaviour and the schema constraints this service owns are asserted
 * in the sibling {@code com.carddemo.account.repository} test package under Failsafe against a real
 * container. Asserting a rule in two places gives a later change two places to update and one place
 * to forget, so each is asserted once.
 *
 * <p>Assumptions: nothing in this package starts a database, starts a container, applies a schema
 * migration or reaches a managed identity pool, and no assertion here may be written as though one of
 * those were available. Fixed-width record images derived from the copybook layouts belong under
 * {@code src/test/resources}, a different tree entirely.
 *
 * <p>Assumptions: the update path's conflict handling divides cleanly along the same line. The
 * conflict <b>status</b> a caller receives is a transport contract and is asserted here, while
 * whether a given edit constitutes a conflict is behaviour and is asserted in the sibling service
 * package. The baseline draws the same line: {@code app/cbl/COACTUPC.cbl} commits with a bare
 * {@code SYNCPOINT} and abandons the unit of work with {@code SYNCPOINT ROLLBACK}, and which of the
 * two it reaches is a business decision rather than a response shape.
 *
 * <h2>The Surefire selection contract, which is the only thing that makes these tests run</h2>
 *
 * <p>Assumptions: every class here ends in {@code Test}, and that suffix is not a naming preference
 * -- the reactor splits its two test phases by class name alone, Surefire selecting
 * {@code **}{@code /*Test.java} in {@code test} and Failsafe selecting {@code **}{@code /*IT.java} in
 * {@code integration-test}, both left at their default include patterns in
 * {@code services/pom.xml}. The hazard that follows is silent, which is why it is written down. A
 * class placed here and named to end in {@code IT} matches neither pattern nor this package's
 * purpose: it would not run under {@code mvn test} and it would not fail either, so the report would
 * be complete and green with one class missing from it. A slice test that silently does not execute
 * is worse than an absent one, because the absent one is visible. The {@code IT} suffix is reserved
 * for the container-backed repository tests in the sibling package.
 *
 * <p>Assumptions: the report directories are equally load-bearing. Unit-test results land in
 * {@code target/surefire-reports} and integration-test results in {@code target/failsafe-reports},
 * both at the Maven defaults, and CI report consumers use those stable locations. Nothing here may
 * relocate them, because a test run can remain green while an external publisher silently collects no
 * results.
 *
 * <h2>What this package does not re-declare</h2>
 *
 * <p>Assumptions: the documentation gate, its suppression policy and the layering rules are declared
 * once, outside this package, and are deliberately not restated here. The gate is the
 * {@code checkstyle-documentation-gate} execution in {@code services/pom.xml} bound to
 * {@code validate} with {@code includeTestSourceDirectory} true, its module set and its two
 * suppression entries live in {@code config/checkstyle}, and layering has exactly one owner in
 * {@code LayeringRulesTest}. A charter that restated any of them would become a second source of
 * truth for a configuration it does not own, and the two could then disagree with nothing to say
 * which was authoritative. What holds for this package is only the consequence: no annotation and no
 * visibility earns an exemption, so every class, test method and private helper carries Javadoc with
 * complete at-clauses.
 *
 * <p>Assumptions: this module builds as a plain jar and adds no {@code module-info.java}, which
 * would impose a second, stricter readability model on a test tree whose dependencies are already
 * resolved by Maven scope.
 */
package com.carddemo.account.api;
