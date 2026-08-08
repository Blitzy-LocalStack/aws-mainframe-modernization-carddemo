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
 * issuing a request per route, which is the stronger form. Still not adopted, and the reason has now
 * changed a second time, so the note is corrected again rather than left standing. It previously said
 * that this module published no controller, so every request would answer 404 and the assertion would
 * rest on distinguishing 403 from 404. {@code com.carddemo.card.api.CardController} now exists and maps
 * all five published operations, so that particular objection has lapsed. What remains is that a
 * request-level test would assert the OUTCOME of the chain rather than the rule table, and the two fail
 * differently: a rule deleted from the table and a rule shadowed by an earlier pattern both produce the
 * same refused request, whereas comparing the table against the contract names which of the two
 * happened. These assertions also remain the only ones that read the PUBLISHED contract, which a
 * request-level test never opens.</p>
 *
 * <p>Trade-offs: the contract is parsed as untyped nested maps rather than through an OpenAPI object
 * model. A model would give typed access and would validate the document's structure on the way in;
 * it would also mean this test could only see what that model chose to expose, and the extension field
 * the authority model depends on, together with the composition keyword the disclosure boundary is
 * expressed with, are exactly the kind of thing a model may normalise away. Untyped access reads the
 * document as written.</p>
 */
package com.carddemo.card.config;
