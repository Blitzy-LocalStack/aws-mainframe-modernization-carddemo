/**
 * Tests holding the published reference contract to the shared kernel that has to serve it.
 *
 * <p>Assumptions: this package exists because Checkstyle enforces {@code JavadocPackage} at Checker
 * level over the test sources as well as the main sources, so a test package with no
 * {@code package-info.java} fails the build at the {@code validate} phase rather than at review.</p>
 *
 *
 * <p>Assumptions: the classes here test CONFIGURATION rather than behaviour, so their assertions are
 * about agreement between two artifacts rather than about the outcome of a request. A path pattern is a
 * string on one side and a string on the other, and an authority name is a string in both the rule
 * table and the token claim; no compiler and no linter compares them. These classes are that
 * comparison.
 *
 * <p>Refactoring Rationale: the security half of this package exists because a review found the
 * catch-all rule of {@code com.carddemo.reference.config.SecurityConfig} written as
 * {@code anyRequest().authenticated()}, which admitted a validly signed token that had been granted no
 * authority at all. The gap was invisible to every gate in the build: the chain compiled, the shared
 * authority converter behaved exactly as documented, and nothing anywhere compared "authenticated"
 * with "authorized". The catch-all is now {@code denyAll()}, the read route is guarded by
 * {@code SecurityConfig.businessAccess()}, and these assertions are what make the two comparable.
 *
 * <p>Alternatives Considered: asserting the same rules through a servlet slice -- a request per route
 * against the built filter chain -- which is the stronger form because it binds the WIRING as well as
 * the decision. Not used here, and the reason is a property of this checkpoint rather than a
 * preference: no module in this repository stands up a web context for a security assertion, so
 * introducing the first one would make this package the only place a route's status code is asserted,
 * with no sibling to compare it against. Asserting the installed decision object keeps the assertion
 * uniform across the eight contexts, and {@code businessAccess()} is published precisely so that
 * object is addressable without a servlet container.
 *
 * <p>Trade-offs: exercising the manager the chain installs binds the DECISION but not the line that
 * wires it to {@code READ_PATH_PATTERN}, which remains a single visible statement in the chain
 * builder. A white-box interaction test over a mocked {@code HttpSecurity} would bind that line too, at
 * the cost of asserting against the builder's internal call sequence -- which changes between framework
 * releases without any change in meaning. One visible line reviewed by eye is the cheaper half of that
 * trade.
 * <p>Refactoring Rationale: the contract assertions live beside {@code SecurityConfig} in the
 * {@code config} package rather than in a package of their own, matching the sibling services, so that
 * a reader looking for "what enforces the published document" finds it in the same place in every
 * module.</p>
 */
package com.carddemo.reference.config;
