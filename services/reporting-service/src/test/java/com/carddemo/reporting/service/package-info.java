/**
 * Tests for the reporting-service business layer, covering statement selection, boundedness and
 * artifact-metadata disclosure.
 *
 * <p>Purpose: the two services in this context sit between a request and a read that no test above them
 * can see. A controller test mocks the service, so it cannot tell a statement selected by its whole
 * card number from one selected by a display mask; a repository integration test parses the queries but
 * never chooses between them. The decisions that go wrong in this layer are decisions about WHICH row
 * is read and HOW MANY rows are read, and both are visible only in the arguments the service hands its
 * repositories -- which is what the cases here assert on.</p>
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
