/**
 * Tests for the reporting-service business layer, covering statement selection, boundedness,
 * artifact-metadata disclosure and the identity a submitted run is started under.
 *
 * <p>Purpose: the four services in this context sit between a request and a read that no test above
 * them can see. A controller test mocks the service, so it cannot tell a statement selected by its whole
 * card number from one selected by a display mask; a repository integration test parses the queries but
 * never chooses between them. The decisions that go wrong in this layer are decisions about WHICH row
 * is read, HOW MANY rows are read, and -- for the report request edge -- WHICH branch a selection or a
 * confirmation answer resolves to, and all three are visible only in the arguments the service hands
 * its collaborators, which is what the cases here assert on.</p>
 *
 * <p>Refactoring Rationale: this charter described TWO services while three existed, and the third is
 * the one whose absence from this package let two wrong behaviours reach review. {@code
 * ReportExecutionService} resolves a report type, a confirmation answer and a date range and then starts
 * an orchestration; the only test that touched it mocked it wholesale from the controller slice, so its
 * branches were asserted against a stub that behaved as its author believed. It counted the three type
 * marks and refused any request carrying more than one, where the reference's condition chain takes the
 * first matching arm, and it refused an unanswered confirmation where the reference composes a prompt.
 * A mocked collaborator cannot contradict its author, which is why a service in this layer without a
 * case in this package is now treated as uncovered rather than as covered from above.</p>
 *
 * <p>Refactoring Rationale: the count moved from three to four with
 * {@code CategoryBalanceReportService}, and {@code CategoryBalanceReportServiceTest} landed with it
 * rather than after it, for the reason the paragraph above gives. What that case owns and no other
 * case can see is the generation pass itself: one line per row in the order it was handed, the exact
 * signed total it returns and never prints, the cursor closed on the failure path, and a sink failure
 * stopping the run rather than yielding a short artifact reported as complete.</p>
 *
 * <p>Assumptions: the report request edge's own split is the same one in different clothes: its
 * orchestration client is mocked because the property under test is the ARGUMENT handed to it, which is
 * observable from a captor and from nothing else, while its CLOCK is real and fixed because the three
 * date presets are derived from it and a stub returning a date would assert the stub.</p>
 *
 * <p>⚠️ Refactoring Rationale: this descriptor said TWO services and covered two, while the package it
 * describes has held three since the submission path was built -- {@code ReportExecutionService} had no
 * cases at all, so the execution name it composes was reachable only through a controller test that
 * mocked the very method that composes it. That gap is why a name unique to the report and its range
 * could be derived, and refuse every submission of one range for the ninety days the orchestrator
 * remembers an execution, without any case objecting. The third service's decisions are visible in the
 * same place as the other two's -- in the argument it hands its collaborator -- so
 * {@code ReportExecutionServiceTest} asserts on the captured start request rather than on a literal.</p>
 *
 * <p>Assumptions: the repositories are mocked and the keyed collaborators are REAL. That split is
 * deliberate rather than convenient. A mocked repository is what lets a case state a portfolio shape --
 * an account holding two cards, a card holding four thousand transactions, a portfolio holding none --
 * that no fixture file expresses. A real tokeniser is required because the property under test is what
 * a derived value does NOT contain, and a stub returning a constant satisfies every absence assertion
 * while proving nothing about the derivation.</p>
 *
 * <p>Assumptions: absence is asserted by value AND by the name of the thing withheld wherever a
 * rendering or a key is involved. A key emitting an empty account segment would pass a value-only
 * assertion while still announcing that the segment is composed, and the next statement would
 * disclose.</p>
 *
 * <p>Assumptions: the parameter, return and exception elements of the project Explainability rule's
 * docstring specification describe callable code, and are omitted from this descriptor deliberately
 * rather than written out empty, because an at-clause carrying no description is itself a violation of
 * the completeness module that audits this build.</p>
 */
package com.carddemo.reporting.service;
