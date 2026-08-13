/**
 * Tests holding this context's published HTTP contract and its enforced configuration to each other.
 *
 * <p><strong>Purpose.</strong> The types here read
 * {@code src/main/resources/openapi/authorization-api.yaml} from the CLASSPATH and assert that what it
 * declares is what the module's own compiled constants and security rules produce. Nothing here opens a
 * socket or reaches a datastore.
 *
 * <p>⚠️ Refactoring Rationale: this paragraph also said that nothing here starts an application context,
 * and one class now does. {@code SecurityChainDispatchTest} assembles a web context because the rule it
 * covers matches the container's DISPATCHER TYPE rather than a path or an authority, and a dispatcher type
 * is not something a decision object can be asked about -- only a dispatch can witness it. The claim is
 * corrected rather than deleted, because the reason the rest of the package holds to it is unchanged and a
 * reader needs to know which of the two shapes a new assertion belongs in.
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
 * the decision. Still not used for the AUTHORITY matrix, because asserting the installed decision object
 * keeps that assertion uniform across the eight contexts and {@code SecurityConfig.businessAccess()} is
 * published precisely so the object is addressable without a servlet container.
 *
 * <p>⚠️ Refactoring Rationale: the reason recorded here for not using a slice was that no module in this
 * repository stood up a web context for a security assertion, so this package would have been the first
 * with no sibling to compare against. That is no longer true -- {@code auth-service}'s
 * {@code com.carddemo.auth.api.UserControllerTest} installs its deployed chain in front of a hand-wired
 * web context and asserts route status codes against it -- and it was never a reason that could cover the
 * DISPATCHER-TYPE rule, which no decision object can express. {@code SecurityChainDispatchTest} follows
 * that sibling's shape deliberately, so the two remain comparable, and it is scoped to the one rule that
 * requires a dispatch rather than being widened into a second copy of the authority matrix.
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
