/**
 * Tests asserting the HTTP boundary of this context: its routes, its bindings and its status codes.
 *
 * <p><strong>Purpose.</strong> The types here stand up a STANDALONE server over one controller and a
 * service double, and assert the three things a controller in this migration is responsible for: that a
 * request reaches the route the contract publishes, that its parameters and body are validated into the
 * per-field refusal transformation rule T7 requires, and that the outcome selects the status code the
 * contract declares. Nothing here starts an application context, opens a socket or reaches a datastore.
 *
 * <p>Refactoring Rationale: this package exists because a review found that this context's published
 * contract promised three operations, a sealed path selector and a selector-versus-body key comparison, and
 * that no controller existed for any of them -- so the request record the comparison was written for had no
 * production consumer at all. The two halves of that gap need different tests: the comparison itself is
 * behaviour and is asserted in {@code com.carddemo.authorization.service}, while whether it is REACHABLE
 * over HTTP at the published route, and whether its refusal arrives as the problem body a client parses, can
 * only be asserted through a server. These are those assertions.
 *
 * <p>Refactoring Rationale: the server is assembled standalone rather than through a sliced application
 * context, and the reason is specific to this module. {@code SecurityConfig} builds its decoder with
 * {@code NimbusJwtDecoder.withIssuerLocation}, which resolves the issuer's discovery document EAGERLY at
 * bean construction, so any context that includes that configuration reaches the network from a unit test --
 * against an issuer the test profile deliberately points at an unresolvable host. A standalone server
 * exercises routing, binding, validation, delegation and status selection with none of that.
 *
 * <p>Assumptions: the authority matrix is deliberately NOT asserted here. It is declared once, against path
 * prefixes, in {@code com.carddemo.authorization.config.SecurityConfig}, and it is asserted against the
 * installed decision object in {@code com.carddemo.authorization.config.SecurityConfigTest}. A standalone
 * server installs no filter chain, so an assertion here that a route was reachable would say nothing about
 * whether it is reachable without the right authority, and stating it would be worse than omitting it.
 *
 * <p>Assumptions: the shared advice from {@code com.carddemo.common.error} is registered on every server
 * these tests build, because half of what a controller publishes is the SHAPE of a refusal. Without it a
 * refusal surfaces as a raised exception rather than as the problem body, and the per-field array the
 * contract promises would go unasserted.
 *
 * <p>Trade-offs: a standalone server binds the controller's own mappings, constraints and status selection
 * but not the wiring that installs it into a running context, which remains visible in the annotations
 * themselves. A sliced context would bind that too, at the cost described above; the eager decoder makes
 * that cost a network call, which is not a cost a unit test may pay.
 *
 * <p>Assumptions: of the four content elements user-specified Rule 1 enumerates, only Purpose applies to a
 * package declaration -- it accepts no parameters, yields no value and raises nothing -- so the other three
 * are inapplicable rather than omitted, and no at-clause is fabricated to stand in for one.
 */
package com.carddemo.authorization.api;
