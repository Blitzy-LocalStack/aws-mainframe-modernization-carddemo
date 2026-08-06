/**
 * Unit tests for the queue-driven decision path of the authorization bounded context.
 *
 * <p><b>Purpose.</b> Two classes execute here, and between them they pin the behaviour of the one
 * program in this context that decides money: {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}.
 * {@code AuthorizationDecisionServiceTest} covers the decision itself -- which of eight outcomes a
 * request reaches and which four-character reason it reports -- and
 * {@code AuthorizationRequestListenerTest} covers everything around the decision: the order the three
 * account-context reads happen in, the key the recorded row is given, the values that are refused before
 * any lookup, and the destinations a reply may be sent to.</p>
 *
 * <p>Assumptions: neither class starts a Spring context, opens a database or touches a queue. The
 * decision service holds no state and takes its inputs as one argument, and the listener takes every
 * collaborator through its constructor, so both are exercised with plain objects and a fixed clock. That
 * is the whole reason the decision was extracted from the listener in the first place: a branch table
 * with eight outcomes is worth asserting exhaustively, and it cannot be asserted exhaustively through a
 * container.</p>
 *
 * <p>Assumptions: the reference program is the specification and is read, never modified. Every
 * assertion below cites the paragraph and line range it comes from, so a reader can confirm the expected
 * value against the source rather than trusting the test name. The paragraphs that matter are
 * {@code 5000-PROCESS-AUTH} at lines 438 to 468, which fixes the order and the conditionality of the
 * reads; {@code 6000-MAKE-DECISION} at lines 657 to 734, which holds the decline test, the two response
 * codes and the reason selection; {@code 8400-UPDATE-SUMMARY} at lines 798 to 850, which creates or
 * updates the summary row; and {@code 8500-INSERT-AUTH} at lines 855 onward, which derives the row key
 * from the platform's clock.</p>
 *
 * <h2>What these tests are for</h2>
 *
 * <p>Assumptions: four of the properties asserted here were absent from this service before the
 * remediation these tests accompany, and each was a parity break rather than a missing check. A card
 * presenting its first authorization could not be resolved to an account at all, so it was declined. The
 * decline reason was chosen from two values where the reference program chooses from seven, and its
 * selection order -- which reports a missing account rather than exhausted funds when both are true --
 * was absent entirely. The recorded row was keyed by values the caller supplied rather than by the
 * server's clock, so a requester chose its own primary key. And a negative amount was approved, which
 * released credit rather than reserving it. A test that only exercised the happy path would have passed
 * throughout, which is why the classes here assert the branch table exhaustively and assert the refusals
 * by name.</p>
 *
 * <p>Assumptions: the four decline reasons the reference program declares and never sets are asserted to
 * be UNREACHABLE rather than asserted to work. Rule T9 of the migration plan forbids inventing a
 * behavioural change, so a test that drove one of those reasons would be testing a condition the
 * baseline cannot produce; what is testable, and is tested, is that each constant carries the
 * four-character literal the reference selection moves and that no input reaches it.</p>
 *
 * <h2>Documentation contract</h2>
 *
 * <p>This charter exists because {@code JavadocPackage} in {@code config/checkstyle/checkstyle.xml}
 * audits any directory holding a source file the gate processes, and that gate includes test sources.
 * Parameters, return values and exceptions are inapplicable to a package declaration rather than omitted
 * from it: there is no callable member here to document. The inapplicability is stated because a
 * docstring that silently omits them is one of the patterns user-specified Rule 1 forbids, and a reader
 * has to be able to tell a declared inapplicability from an oversight.</p>
 */
package com.carddemo.authorization.service;
