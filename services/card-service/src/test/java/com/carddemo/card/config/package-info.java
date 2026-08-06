/**
 * Tests that hold this context's published contract and its enforced configuration to each other.
 *
 * <p>Assumptions: this package tests CONFIGURATION rather than behaviour, so its assertions are about
 * agreement between two artifacts rather than about the outcome of a request. The published contract at
 * {@code src/main/resources/openapi/card-api.yaml} and the rule table in
 * {@code com.carddemo.card.config.SecurityConfig} each state which authority an operation requires, and
 * nothing in a compiler or a linter compares them - a path is a string on one side and a string on the
 * other. The single test class here is that comparison.</p>
 *
 * <p>Refactoring Rationale: this package exists because a review found four disagreements of exactly
 * that kind in this context alone, each invisible to both the Java and the TypeScript build: an
 * administrative authority stated only in prose, a path selector that was a primary account number
 * where the browser client and the edge route table both carried an opaque token, paging inputs named
 * after the response members that are null on the one page whose cursors are not, and a correlation
 * identity bounded at three different widths across three contracts. Every one of them would have
 * surfaced first as a refused or over-permissive request in a deployed environment.</p>
 *
 * <p>Alternatives Considered: asserting the same rules by standing up an application context and
 * issuing a request per route, which is the stronger form and is what a negative authorization test
 * needs. Not available at the checkpoint that authored this package - this module has no application
 * class yet - and deferring the whole comparison until one exists would leave the rules unverified
 * during exactly the interval in which they were introduced. The runtime assertion is added when that
 * class lands; this package's assertions remain useful afterwards, because they check the PUBLISHED
 * contract, which a request-level test does not read.</p>
 *
 * <p>Trade-offs: the contract is parsed as untyped nested maps rather than through an OpenAPI object
 * model. A model would give typed access and would validate the document's structure on the way in;
 * it would also mean this test could only see what that model chose to expose, and the extension field
 * the authority model depends on, together with the composition keyword the disclosure boundary is
 * expressed with, are exactly the kind of thing a model may normalise away. Untyped access reads the
 * document as written.</p>
 */
package com.carddemo.card.config;
