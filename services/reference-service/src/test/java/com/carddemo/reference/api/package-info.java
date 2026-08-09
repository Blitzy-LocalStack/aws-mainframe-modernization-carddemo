/**
 * Tests over the reference REST surface, and in particular over its agreement with the published
 * contract.
 *
 * <p>Purpose: the contract document and the controllers are two descriptions of one interface, and
 * nothing but a test compares them. {@code ReferenceApiRoutingContractTest} holds that comparison, in both
 * directions: an operation the document declares with no handler, and a handler with no declared
 * operation, each fail the build.</p>
 *
 * <p>Assumptions: the comparison is made against the annotations rather than against a running context,
 * so it needs no database, no queue and no token. That is deliberate -- a contract check that required
 * the whole application to start would be skipped on the runs where it matters most.</p>
 *
 * <p>Purpose: {@code DateEvaluationDispatcherTest} answers the question the comparison above cannot --
 * what a request to a mapped address actually does. It drives the date-evaluation route through a real
 * dispatcher and asserts the candidate binds as TEXT rather than as a date, that the parameter constraints
 * are enforced, that an unusable day is reported on with 200 instead of refused, and that the width
 * mismatch the handler relabels renders as the published 400 rather than as a 500.</p>
 *
 * <p>Refactoring Rationale: the dispatcher class is here because a review found that a routing census of
 * this kind, on its own, "missed the literal POST/GET break and does not exercise binding, validation,
 * advice, headers, or status rendering". The census is retained rather than replaced: the two failures it
 * detects -- a published operation with no handler, and a handler with no published operation -- are ones a
 * dispatcher test cannot report as such, because a request to an unmounted address is simply refused
 * without saying that the document promised it.</p>
 *
 * <p>Purpose: {@code ReferenceParameterConstraintTest} covers the remaining routes the same way, and is a
 * second dispatcher class rather than more cases in the first because it drives a different set of
 * controllers: the three address lookups, the two filtered browses, the disclosure-group read and the six
 * item routes those controllers publish. Every constrained query parameter and path segment is sent a value
 * that must be refused, and each refusal case is paired with an accepted case on the same parameter so the
 * boundary is shown to be where the contract puts it rather than merely somewhere. Every one of those
 * operations already declared a 400 response in the published document, so the cases assert agreement with
 * the contract rather than a new status.</p>
 *
 * <p>Refactoring Rationale: that class exists because a review found the constraints declared on the
 * request records while the handlers bound raw text and constructed the record afterwards, where nothing
 * validated it. The item routes failed in two distinguishable ways and both are covered: a malformed
 * segment on either category item route reached a composite identity type whose bare refusal the shared
 * advice does not classify, answering 500; whereas the type and lookup item routes read straight for the
 * value, so a malformed segment matched nothing and answered 404 with a "NOT found" sentence -- a
 * misdirection that is harder to notice than a fault, because the status looks like an ordinary outcome.
 * Cases were added here rather than in the service test package because a declared constraint is only
 * enforced by a dispatcher -- a direct handler call passes whatever value it likes straight into the body
 * and cannot tell an enforced constraint from a written-down one.</p>
 *
 * <p>Assumptions: both dispatcher classes use {@code standaloneSetup} and register no security chain, so an
 * absent handler cannot be confused with a refused authority -- there is no authority to refuse. Which
 * authority each route demands is asserted in the sibling {@code com.carddemo.reference.config} test
 * package against the chain's own installed authorization managers, and is deliberately not restated
 * here.</p>
 *
 * <p>Assumptions: user-specified Rule 1 (Explainability) requires a docstring on every module entry
 * point, and Checkstyle audits test sources as well as main sources, so this descriptor is required
 * rather than decorative. The written convention it follows is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
package com.carddemo.reference.api;
