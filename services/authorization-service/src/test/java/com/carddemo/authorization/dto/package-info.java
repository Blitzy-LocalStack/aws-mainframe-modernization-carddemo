/**
 * Boundary-contract tests for the six payload types of the authorization bounded context.
 *
 * <p><b>Purpose.</b> Six classes execute here, and the count is a measurement of the directory rather
 * than a target. {@code AuthorizationPayloadDomainTest} asserts the value domains the queue payloads
 * declare -- requiredness, the character domains the copybook pictures admit, the two closed response
 * domains and the non-negative amount domain -- and the truncation the detail response's composed reason
 * performs. {@code RowSelectorContractTest} asserts how a row is addressed in each direction under the
 * two DIFFERENT rules that govern them, the ends being deliberately asymmetric: outbound, the summary
 * response publishes only an opaque sealed selector and never a raw row address; inbound, the fraud
 * request carries the three decoded primary-key columns and holds each one to its own domain, including
 * the component decomposition that refuses an impossible day, minute or second lying inside the
 * published numeric range. {@code AuthorizationRequestPayloadTest} covers the request record's own
 * declarations, including which of its eighteen components a diagnostic rendering withholds,
 * {@code SummaryRenderingTest} the values a summary rendering withholds,
 * {@code PendingAuthSummarySegmentFixtureTest} the packed segment fixture the summary is decoded from,
 * and {@code AuthorizationWireContractTest} the published document against the two queue payload
 * records -- the corrected spelling, the wire ordinal it sits at, and that the eighteen request fields
 * admit no nineteenth.</p>
 *
 * <p>Refactoring Rationale: that last class is named for the WIRE rather than for the API, and the name
 * is deliberate. This module already holds {@code AuthorizationApiContractTest} in its {@code config}
 * package, and two classes of one simple name inside one module are told apart only by the package they
 * ran in -- so a reader given a failure, a report line or a reviewer's reference to "the contract test"
 * cannot tell which class produced it. The same reasoning is recorded at length on the shared layering
 * gate, which forbids a second class of its own simple name for exactly this reason.</p>
 *
 * <p>Assumptions: these are unit tests over records and a validation engine built from the default
 * provider, with no Spring context. A payload is a value, so its contract is testable as a value; slicing
 * a web layer to assert it would test the binding as well and would report a failure as a status code
 * rather than as the component at fault.</p>
 *
 * <h2>Why these particular properties</h2>
 *
 * <p>Assumptions: every property asserted here was wrong or absent before the remediation these tests
 * accompany, and each was wrong in a way a happy-path test would not have caught. All six reply
 * components were nullable, so a reply missing an answer satisfied its own contract. Three of them were
 * restricted to digits although their copybook fields are {@code PIC X} and admit any character, and
 * three carried exact widths although the codec that renders them pads a short value -- so the payload
 * refused values the wire accepts. The request's amount had no domain at all, so a negative amount passed
 * a validator and was then approved. The detail response's composed reason allowed 21 characters where
 * the only observable field is 20. And a row carried no address of any kind, while the fraud request took
 * three caller-supplied key numbers, so a client could act on any authorization on any account by
 * arithmetic.</p>
 *
 * <p>Assumptions: the two closed domains are asserted against the enumeration that owns them rather than
 * against a copy of the literals, so the pattern a Jakarta constraint needs -- which must be a
 * compile-time constant, and therefore a second statement of the same table -- cannot drift from
 * {@code com.carddemo.authorization.service.AuthorizationDecisionService.DeclineReason} without failing a
 * build. That is the whole point of testing a duplicated constant: the duplication is unavoidable and the
 * agreement is not.</p>
 *
 * <h2>Documentation contract</h2>
 *
 * <p>This charter exists because {@code JavadocPackage} in {@code config/checkstyle/checkstyle.xml}
 * audits any directory holding a source file the gate processes, and the gate is configured to include
 * test sources. Parameters, return values and exceptions are inapplicable to a package declaration rather
 * than omitted from it, since there is no callable member here; the inapplicability is stated because a
 * docstring that silently omits them is one of the patterns user-specified Rule 1 forbids.</p>
 */
package com.carddemo.authorization.dto;
