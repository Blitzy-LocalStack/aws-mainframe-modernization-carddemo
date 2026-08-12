/**
 * Tests that hold this context's enforced configuration to the contract it is meant to carry.
 *
 * <p>Assumptions: this package tests CONFIGURATION rather than behaviour, so its assertions are about
 * agreement between two artifacts rather than about the outcome of a request. A path pattern is a string
 * on one side and a string on the other, and an authority name is a string in both the rule table and the
 * token claim; no compiler and no linter compares them. The classes here are those comparisons.</p>
 *
 * <p>Assumptions: two kinds of configuration are held here and they fail differently, which is why they
 * are separate classes rather than cases on one. An authorization rule that is wrong admits a request it
 * should refuse, and the wrong value is a string nothing compares. A queue-client TIME bound that is wrong
 * -- {@code SqsConfigTest} -- lets one message be handled twice, and the wrong value is a number nothing
 * adds up. Both are invisible to every other gate in the build, and neither is observable in a log after
 * the fact: the first looks like an authorised request and the second looks like ordinary redelivery.</p>
 *
 * <p>Refactoring Rationale: this package exists because a review found the catch-all rule of
 * {@code com.carddemo.account.config.SecurityConfig} written as {@code anyRequest().authenticated()}, which
 * admitted a validly signed token that had been granted no authority at all. The gap was invisible to
 * every gate in the build: the chain compiled, the shared authority converter behaved exactly as
 * documented, and nothing anywhere compared "authenticated" with "authorized". These assertions are what
 * make the two comparable.</p>
 *
 * <p>Alternatives Considered: asserting the same rules by standing up an application context and issuing
 * one request per route, which is the stronger form. Rejected because it would answer a DIFFERENT question
 * from the one these classes ask. Asserting the installed decision object binds the rule table itself, so a
 * route added later without an entry in it fails here; asserting a request per route binds only the routes
 * the case list happens to name, and the omission this package exists to catch is by definition one nobody
 * thought to name. {@code SecurityConfig.businessAccess()} exists precisely so that object is addressable
 * without a servlet container, and the per-request form is covered where it belongs -- the api package
 * mounts each controller and asserts its refusals against the deployed chain.</p>
 *
 * <p>Refactoring Rationale: the paragraph above previously rejected the per-request form on the ground that
 * "this context publishes no controller yet", which stopped being true once the account, cross-reference
 * and customer controllers landed. The rejection still holds, for the reason now recorded, but the reason
 * had to be restated rather than left resting on a premise the package it describes had outgrown.</p>
 *
 * <p>Assumptions: a THIRD kind of configuration is held here as well, and it fails differently again.
 * {@code InternalRouteClosureTest} compares this service's published contract against the listener rules in
 * both Terraform environment roots -- two artifacts in two languages that no compiler, linter or test
 * outside this class reads together. A rule narrower than the contract withdraws an operation silently: the
 * caller receives a not-found from the load balancer that it cannot tell from a missing row, and this
 * service logs nothing at all because no request ever arrives.</p>
 *
 * <p>Trade-offs: exercising the manager returned by {@code businessAccess()} binds the DECISION but not
 * the wiring of {@code anyRequest()} to it, which remains a single visible line in the chain builder. A
 * white-box interaction test over a mocked {@code HttpSecurity} would bind that line too, at the cost of
 * asserting against the builder's internal call sequence -- which changes between framework releases
 * without any change in meaning. One visible line reviewed by eye is the cheaper half of that trade.</p>
 */
package com.carddemo.account.config;
