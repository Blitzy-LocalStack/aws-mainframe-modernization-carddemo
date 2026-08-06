/**
 * Tests that hold this context's published contract and its enforced configuration to each other.
 *
 * <p>Assumptions: this package tests CONFIGURATION rather than behaviour, so its assertions are about
 * agreement between two artifacts rather than about the outcome of a request. The published contract at
 * {@code src/main/resources/openapi/auth-api.yaml} and the rule table in
 * {@code com.carddemo.auth.config.SecurityConfig} each state which authority an operation requires and
 * which operations need none, and nothing in a compiler or a linter compares them - a path is a string
 * on one side and a string on the other. The single test class here is that comparison.</p>
 *
 * <p>Refactoring Rationale: this package exists because a review found that the five operations which
 * create, alter and delete the rows deciding who is an administrator asserted their restriction in
 * prose and in a tag name, with nothing enforcing it and nothing checking it; and that the contract
 * capped a password at the eight characters of a baseline record field this migration deliberately does
 * not carry forward, while the identity pool this context authenticates against is configured to
 * require at least twelve. The second defect made the primary operation unusable rather than merely
 * strict - every credential the pool would accept was one the contract refused - and neither defect was
 * visible to any build step, because an authority is a string in a document and a length is a number in
 * a document.</p>
 *
 * <p>Alternatives Considered: asserting the same rules by standing up an application context and
 * issuing a request per route, which is the stronger form and is what a negative authorization test
 * needs. Not available at the checkpoint that authored this package - this module has no application
 * class yet - and deferring the whole comparison until one exists would leave the rules unverified
 * during exactly the interval in which they were introduced. The request-level assertion is added when
 * that class lands; this package's assertions remain useful afterwards, because they read the PUBLISHED
 * contract, which a request-level test never opens.</p>
 *
 * <p>Trade-offs: the contract is parsed as untyped nested maps rather than through an OpenAPI object
 * model. A model would give typed access and would validate the document's structure on the way in; it
 * would also mean this test could see only what that model chose to expose, and the extension field the
 * authority model depends on, together with the discriminator that tells the two sign-on outcomes
 * apart, are exactly the kind of thing a model may normalise away. Untyped access reads the document as
 * written.</p>
 */
package com.carddemo.auth.config;
