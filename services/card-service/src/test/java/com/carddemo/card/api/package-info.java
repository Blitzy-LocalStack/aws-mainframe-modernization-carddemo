/**
 * Tests that hold this context's HTTP surface and its published contract to each other.
 *
 * <p>Assumptions: two test classes execute here and they ask different questions, so a failure in one
 * localises differently from a failure in the other. {@code CardControllerContractCensusTest} tests
 * EXISTENCE and ADDRESSING: it reads the operation identifiers out of the packaged contract at
 * {@code src/main/resources/openapi/card-api.yaml} and requires a mapped handler for each, then pins the
 * four addresses those handlers are mapped to. Nothing in a compiler or a linter performs that
 * comparison -- an operation identifier is a string in a YAML document and a handler is a method, and
 * the two are unrelated artifacts as far as the build is concerned. {@code CardDispatcherTest} tests
 * REQUEST BEHAVIOUR on the browse route: argument resolution, optional-body binding, bean validation,
 * the shared advice's rendering and the status a caller receives.</p>
 *
 * <p>Refactoring Rationale: the second class is here because the first proved insufficient, and the
 * insufficiency was measured rather than argued. A review found that a census of this kind "missed the
 * literal POST/GET break and does not exercise binding, validation, advice, headers, or status
 * rendering" -- a comparison between a published address and a mapped address cannot see what a request
 * to that address does. The census is retained rather than replaced, because the two failures it detects
 * -- a published operation with no handler, and a handler at the wrong address -- are ones a dispatcher
 * test cannot report as such: a request to an unmounted address is simply refused, without saying that
 * the contract promised it.</p>
 *
 * <p>Refactoring Rationale: this package exists because of a measured defect rather than as a matter of
 * form. A review found that the contract declared five operations while the production tree held ZERO
 * controllers, so every published operation answered 404 while the document said otherwise, and the
 * build reported nothing. The census here is the smallest artifact that makes that class of gap
 * impossible to reintroduce, and it derives its expectation from the contract rather than from a list
 * written into the test, so it strengthens automatically as the contract grows.</p>
 *
 * <p>Refactoring Rationale: this paragraph previously ruled the dispatcher form OUT of this package
 * entirely, on the ground that standing up the web context would also exercise the security chain and so
 * a failure would not localise to the missing-handler question. That reasoning held for a FULL web
 * context and was then applied to a form that does not need one: {@code CardDispatcherTest} is assembled
 * with {@code standaloneSetup} and NO security chain, so an absent handler and a refused authority cannot
 * be confused -- there is no authority to refuse. The distinction the paragraph was protecting is
 * therefore kept by construction rather than by omitting the test. Which authority each route demands
 * remains asserted by {@code com.carddemo.card.config} against the chain's own installed authorization
 * managers, and is deliberately not restated here.</p>
 *
 * <p>Alternatives Considered: a full {@code @SpringBootTest} web environment for the behavioural class,
 * which would additionally exercise the real filter chain and the auto-configured converters. Rejected
 * for this package: it would start a context, need a datasource and a token issuer, and turn a failure in
 * argument resolution into a failure that could equally be either of those. The one property a full
 * context would add that {@code standaloneSetup} cannot -- that the configured chain admits the route at
 * all -- is the property {@code com.carddemo.card.config} already asserts.</p>
 *
 * <p>Trade-offs: the contract is scanned line by line for operation identifiers rather than parsed
 * through an OpenAPI object model. A model would validate the document's structure on the way in; it
 * would also mean this package could only see what that model chose to expose, and this context's
 * contract leans on an extension field for its authority model and on a composition keyword for its
 * disclosure boundary -- exactly the kind of thing a model may normalise away. Reading the document as
 * written is the property being kept.</p>
 */
package com.carddemo.card.api;
