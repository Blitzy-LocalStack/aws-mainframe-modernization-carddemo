/**
 * Tests that hold this context's enforced authorization rules to the contract they are meant to carry.
 *
 * <p>Assumptions: this package tests CONFIGURATION rather than behaviour, so its assertions are about
 * agreement between two artifacts rather than about the outcome of a request. A path pattern is a string
 * on one side and a string on the other, and an authority name is a string in both the rule table and the
 * token claim; no compiler and no linter compares them. The class here is that comparison.</p>
 *
 * <p>Refactoring Rationale: this package exists because a review found the catch-all rule of
 * {@code com.carddemo.reporting.config.SecurityConfig} written as {@code anyRequest().authenticated()}, which
 * admitted a validly signed token that had been granted no authority at all. The gap was invisible to
 * every gate in the build: the chain compiled, the shared authority converter behaved exactly as
 * documented, and nothing anywhere compared "authenticated" with "authorized". These assertions are what
 * make the two comparable.</p>
 *
 * <p>Alternatives Considered: asserting the same rules by standing up an application context and issuing
 * one request per route, which is the stronger form. Rejected at this checkpoint for a reason specific to
 * it rather than for convenience: this context publishes no controller yet, so every request would answer
 * 404 from the handler mapping and the assertion would rest on distinguishing 403 from 404 -- a signal
 * that changes the moment the first controller lands. Asserting the installed decision object instead
 * gives a result that stays true afterwards, and {@code SecurityConfig.businessAccess()} exists precisely
 * so that object is addressable without a servlet container.</p>
 *
 * <p>Trade-offs: exercising the manager returned by {@code businessAccess()} binds the DECISION but not
 * the wiring of {@code anyRequest()} to it, which remains a single visible line in the chain builder. A
 * white-box interaction test over a mocked {@code HttpSecurity} would bind that line too, at the cost of
 * asserting against the builder's internal call sequence -- which changes between framework releases
 * without any change in meaning. One visible line reviewed by eye is the cheaper half of that trade.</p>
 */
package com.carddemo.reporting.config;
