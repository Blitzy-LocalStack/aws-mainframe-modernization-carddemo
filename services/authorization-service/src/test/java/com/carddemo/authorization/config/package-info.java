/**
 * Tests holding this context's published HTTP contract and its enforced configuration to each other.
 *
 * <p><strong>Purpose.</strong> The types here read
 * {@code src/main/resources/openapi/authorization-api.yaml} from the CLASSPATH and assert that what it
 * declares is what the module's own compiled constants and security rules produce. Nothing here starts an
 * application context, opens a socket or reaches a datastore.
 *
 * <p>Refactoring Rationale: the review that prompted this package found several statements in the contract
 * that no compiler could check and that had drifted from the code — a response bound narrower than the codes
 * it must carry, a status enumeration admitting a value the write path cannot reach, a shared message width
 * declared at one number in one schema and another in its sibling, and a correlation domain broader than the
 * filter's. Each of those is a string or a number inside a document, so the only way to stop the next one is
 * a test that reads the document and compares it against the constant it is supposed to mirror.
 *
 * <p>Assumptions: the contract is read from the classpath rather than from the source tree, so these tests
 * assert against the artifact the service actually packages. Reading the source path through the file system
 * would pass while the packaged resource was stale or absent.
 *
 *
 * <p>Refactoring Rationale: the security half of this package exists because a review found the
 * catch-all rule of {@code com.carddemo.authorization.config.SecurityConfig} written as
 * {@code anyRequest().authenticated()}, which admitted a validly signed token that had been granted no
 * authority at all. The gap was invisible to every gate in the build: the chain compiled, the shared
 * authority converter behaved exactly as documented, and nothing anywhere compared "authenticated"
 * with "authorized". The catch-all is now {@code denyAll()} and every published path carries its own
 * rule; these assertions are what make the two comparable.
 *
 * <p>Alternatives Considered: asserting the same rules through a servlet slice -- a request per route
 * against the built filter chain -- which is the stronger form because it binds the WIRING as well as
 * the decision. Not used here, and the reason is a property of this checkpoint rather than a
 * preference: no module in this repository stands up a web context for a security assertion, so
 * introducing the first one would make this package the only place a route's status code is asserted,
 * with no sibling to compare it against. Asserting the installed decision object keeps the assertion
 * uniform across the eight contexts, and {@code SecurityConfig.businessAccess()} is published
 * precisely so that object is addressable without a servlet container.
 *
 * <p>Trade-offs: exercising the manager returned by {@code businessAccess()} binds the DECISION but not
 * the line that wires it to the routes it guards, which remains visible in the chain builder. Note
 * that in THIS context that manager is not the catch-all: the catch-all is {@code denyAll()}, so a
 * principal the manager would refuse is refused twice over. A white-box interaction test over a mocked
 * {@code HttpSecurity} would bind the wiring too, at the cost of asserting against the builder's
 * internal call sequence -- which changes between framework releases without any change in meaning.
 * One visible line reviewed by eye is the cheaper half of that trade.
 * <p>Assumptions: of the four content elements user-specified Rule 1 enumerates, only Purpose applies to a
 * package declaration — it accepts no parameters, yields no value and raises nothing — so the other three
 * are inapplicable rather than omitted, and no at-clause is fabricated to stand in for one.
 */
package com.carddemo.authorization.config;
