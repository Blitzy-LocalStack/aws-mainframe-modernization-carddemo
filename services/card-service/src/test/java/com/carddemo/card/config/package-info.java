/**
 * Tests that hold this context's published contract and its enforced configuration to each other.
 *
 * <p>Assumptions: this package tests CONFIGURATION rather than behaviour, so its assertions are about
 * agreement between two artifacts rather than about the outcome of a request. The published contract at
 * {@code src/main/resources/openapi/card-api.yaml} and the rule table in
 * {@code com.carddemo.card.config.SecurityConfig} each state which authority an operation requires, and
 * nothing in a compiler or a linter compares them - a path is a string on one side and a string on the
 * other. The classes here are those comparisons.</p>
 *
 * this directory: 8 java files = 7 tests + 1 charter
 *
 * <p>Refactoring Rationale: that sentence read "The single test class here is that comparison", which was
 * true of one class and is now false of six. It is restated in the plural AND accompanied by a counted
 * inventory line, because the singular claim went stale silently: nothing measured it. The line above is
 * measured -- {@code PackageCharterInventoryTest} in {@code common-lib} re-counts this directory and fails
 * the build when the figure drifts -- so the next class added here cannot leave the charter wrong.</p>
 *
 * <p>What each of the six holds to what: {@code CardApiContractTest} holds the document to the enforced
 * security rule table and to the shared kernel's constants. {@code CardApiContractGateTest} holds it to the
 * records that serialise into it and to its own published examples. {@code ContractPublicationTest} holds
 * the packaged resource to the committed file. {@code OpenApiDocumentTest} holds the SERVED document to its
 * declared version and security scheme. {@code SecurityConfigTest} holds the rule table to the authorities
 * it names. And {@code SecurityChainDispatchTest} stands the chain up and drives requests through it.</p>
 *
 * <p>Refactoring Rationale: this package exists because a review found four disagreements of exactly
 * that kind in this context alone, each invisible to both the Java and the TypeScript build: an
 * administrative authority stated only in prose, a path selector that was a primary account number
 * where the browser client and the edge route table both carried an opaque token, paging inputs named
 * after the response members that are null on the one page whose cursors are not, and a correlation
 * identity bounded at three different widths across three contracts. Every one of them would have
 * surfaced first as a refused or over-permissive request in a deployed environment.</p>
 *
 * <p>Alternatives Considered: asserting the same rules ONLY by standing up an application context and
 * issuing a request per route. Refactoring Rationale: this note has been corrected twice and is now
 * corrected a third time, because its premise finally changed rather than merely its reasoning. It first
 * said this module published no controller; then that a request-level test asserts an outcome rather than a
 * rule table and so is "still not adopted". A request-level test IS now adopted --
 * {@code SecurityChainDispatchTest} stands the chain up and drives dispatches through it -- so the two forms
 * coexist here rather than one standing in for the other, and the note describes the division instead of a
 * rejection. The division is that the two fail differently: a rule deleted from the table and a rule
 * shadowed by an earlier pattern produce the SAME refused request, whereas comparing the table against the
 * contract names which of the two happened. And the contract-reading classes remain the only ones that open
 * the PUBLISHED document at all, which no request-level test does.</p>
 *
 * <p>Trade-offs: the contract is parsed as untyped nested maps rather than through an OpenAPI object
 * model. A model would give typed access and would validate the document's structure on the way in;
 * it would also mean this test could only see what that model chose to expose, and the extension field
 * the authority model depends on, together with the composition keyword the disclosure boundary is
 * expressed with, are exactly the kind of thing a model may normalise away. Untyped access reads the
 * document as written.</p>
 */
package com.carddemo.card.config;
