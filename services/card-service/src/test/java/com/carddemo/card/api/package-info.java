/**
 * Tests that hold this context's HTTP surface and its published contract to each other.
 *
 * <p>Assumptions: this package tests EXISTENCE and ADDRESSING rather than request behaviour. Its single
 * test class reads the operation identifiers out of the packaged contract at
 * {@code src/main/resources/openapi/card-api.yaml} and requires a mapped handler for each, then pins the
 * four addresses those handlers are mapped to. Nothing in a compiler or a linter performs that
 * comparison -- an operation identifier is a string in a YAML document and a handler is a method, and
 * the two are unrelated artifacts as far as the build is concerned.</p>
 *
 * <p>Refactoring Rationale: this package exists because of a measured defect rather than as a matter of
 * form. A review found that the contract declared five operations while the production tree held ZERO
 * controllers, so every published operation answered 404 while the document said otherwise, and the
 * build reported nothing. The census here is the smallest artifact that makes that class of gap
 * impossible to reintroduce, and it derives its expectation from the contract rather than from a list
 * written into the test, so it strengthens automatically as the contract grows.</p>
 *
 * <p>Alternatives Considered: standing up the web context and probing each route, which is the stronger
 * form and is a different test rather than a better one. It would additionally exercise the security
 * chain and the message converters, so a failure would not localise to the missing-handler question this
 * package asks -- and distinguishing an absent handler from a refused authority means distinguishing 404
 * from 403, which is precisely the ambiguity a census avoids by asking about the method table instead.
 * Route and authority behaviour is asserted by {@code com.carddemo.card.config} alongside the contract
 * it is written against.</p>
 *
 * <p>Trade-offs: the contract is scanned line by line for operation identifiers rather than parsed
 * through an OpenAPI object model. A model would validate the document's structure on the way in; it
 * would also mean this package could only see what that model chose to expose, and this context's
 * contract leans on an extension field for its authority model and on a composition keyword for its
 * disclosure boundary -- exactly the kind of thing a model may normalise away. Reading the document as
 * written is the property being kept.</p>
 */
package com.carddemo.card.api;
