/**
 * Tests for the batch-service persistence entities, covering the properties that no compiler or
 * schema check can assert.
 *
 * <p>The one property covered here is diagnostic rendering. Every entity in
 * {@code com.carddemo.batch.domain} may end up in a log line -- through a wrapped exception message,
 * a framework's own reporting, or a deliberate log statement -- and what a rendering carries is a
 * disclosure decision rather than a formatting preference. A log is retained, aggregated, and
 * readable by every holder of log access, so a value emitted once per record accumulates into a
 * searchable copy of whatever it names.
 *
 * <p>Assumptions: the reason these need tests at all is that {@code toString} is invoked implicitly.
 * Nothing in the build fails when an override is deleted or when a member is added back into one, and
 * the resulting disclosure appears only in a log file that no test reads. An assertion on the rendered
 * string is the only mechanism that turns a silent regression into a build failure.
 *
 * <p>Alternatives Considered: asserting that the rendering is merely non-blank, or that it starts with
 * the type name. Rejected, because neither assertion can disagree with the defect: a generated
 * {@code toString} that emits every component satisfies both. The assertions below name the values
 * that must be absent, so they fail exactly when a sensitive member returns.
 */
package com.carddemo.batch.domain;
