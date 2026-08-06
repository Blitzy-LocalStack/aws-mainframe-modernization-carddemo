/**
 * Tests for the transaction-service persistence entities, covering the two properties that neither
 * the compiler nor the schema can assert on its own.
 *
 * <p>The first is the explicit {@code CHAR} binding of every fixed-width column. A mapping that omits
 * it compiles, deploys, and reads and writes rows successfully; what changes is that the driver sends
 * the value as a variable-length string, so the database's own blank-padding comparison semantics no
 * longer apply and a padded stored value stops comparing equal to an unpadded bound one. That failure
 * surfaces as a query returning nothing, at a distance from the mapping that caused it.
 *
 * <p>The second is diagnostic rendering, where what a rendering carries is a disclosure decision
 * rather than a formatting preference. A log is retained, aggregated and readable by every holder of
 * log access, so a value emitted once per record accumulates into a searchable copy of whatever it
 * names.
 *
 * <p>Assumptions: both properties need tests because both regress silently. Deleting a
 * {@code @JdbcTypeCode} annotation breaks no build, and deleting a {@code toString} override breaks no
 * build either -- the generated or inherited rendering simply resumes and appears only in a log file
 * that no test reads. Reflection over the mapped members and assertions on the rendered string are the
 * only mechanisms that turn either regression into a build failure.
 *
 * <p>Trade-offs: asserting an annotation by reflection couples a test to member names, so renaming a
 * field will fail this test rather than silently passing. That coupling is accepted deliberately: a
 * rename that dropped the annotation is exactly the regression being guarded against, and a failure
 * naming the member is more useful than a query that returns no rows in production.
 */
package com.carddemo.transaction.domain;
