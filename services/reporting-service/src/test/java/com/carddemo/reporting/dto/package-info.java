/**
 * Tests for the reporting-service request shapes, covering diagnostic rendering.
 *
 * <p>{@link com.carddemo.reporting.dto.StatementRequest} carries the two alternative selectors of a
 * statement run, and both are protected values: a primary account number and an account identifier. A
 * request shape is also the type most likely to be logged, because the moment a request is refused is
 * the moment something is written about it and no handler has yet had a chance to narrow anything. What
 * its {@code toString} carries is therefore a disclosure decision rather than a formatting
 * preference.</p>
 *
 * <p>Assumptions: this needs a test because {@code toString} is invoked implicitly. Nothing in the build
 * fails when the override is deleted -- the generated record rendering names both components verbatim --
 * and the resulting disclosure appears only in a log file no other test reads. An assertion on the
 * rendered string is the only mechanism that turns that silent regression into a build failure.</p>
 *
 * <p>Alternatives Considered: placing this coverage beside the prepared-field rendering cases in
 * {@code com.carddemo.reporting.mapper}, which already assert on two withheld renderings. Rejected
 * because those cases assert on values a mapper PREPARES, while this one asserts on a value a client
 * SUPPLIES, and the two regress for different reasons -- a mapper rendering changes when the emitter is
 * edited, a request rendering changes when the record's components are.</p>
 *
 * <p>Assumptions: the parameter, return and exception elements of the project Explainability rule's
 * docstring specification describe callable code, and are omitted from this descriptor deliberately
 * rather than written out empty, because an at-clause carrying no description is itself a violation of
 * the completeness module that audits this build.</p>
 */
package com.carddemo.reporting.dto;
