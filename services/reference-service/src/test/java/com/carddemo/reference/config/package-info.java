/**
 * Tests holding the published reference contract to the shared kernel that has to serve it.
 *
 * <p>Assumptions: this package exists because Checkstyle enforces {@code JavadocPackage} at Checker
 * level over the test sources as well as the main sources, so a test package with no
 * {@code package-info.java} fails the build at the {@code validate} phase rather than at review.</p>
 *
 *
 * <p>Assumptions: MOST of the classes here test CONFIGURATION rather than behaviour, so their assertions
 * are about agreement between two artifacts rather than about the outcome of a request. A path pattern is a
 * string on one side and a string on the other, and an authority name is a string in both the rule table
 * and the token claim; no compiler and no linter compares them. Those classes are that comparison.
 *
 * <p>⚠️ Refactoring Rationale: that paragraph said "the classes here" without qualification, and one class
 * now contradicts it. {@code SecurityChainDispatchTest} asserts the OUTCOME of a request, because the rule
 * it covers matches a DISPATCHER TYPE rather than a path or an authority -- so no comparison of two
 * artifacts can witness it, and only a dispatch can. The qualification is added rather than the class being
 * moved: it belongs beside the chain it exercises.
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
 * against the built filter chain -- which is the stronger form because it binds the WIRING as well as the
 * decision. Still not used for the ROUTE rules, and asserting the installed decision object instead keeps
 * that assertion uniform across the eight contexts; {@code businessAccess()} is published precisely so the
 * object is addressable without a servlet container.
 *
 * <p>⚠️ Refactoring Rationale: this note used to justify the choice by saying that "no module in this
 * repository stands up a web context for a security assertion", which has stopped being true in four places
 * at once -- the account, card and authorization contexts each stand one up, and so does
 * {@code SecurityChainDispatchTest} in this package. The reason it gave was therefore an argument about a
 * missing sibling, and there are now four. What survives is the narrower and still-correct reason: a
 * decision object is comparable across contexts and needs no container, so the route rules are asserted that
 * way, while the one rule no comparison can witness is asserted with a dispatch.
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
