/**
 * Tests for the transaction-service persistence entities, covering the three properties that neither
 * the compiler nor the schema can assert on its own.
 *
 * <p>The first is the money-column invariant. Every monetary member of this package is backed by a
 * {@code NUMERIC(11,2) NOT NULL} column, and the three values that column cannot hold -- an absent one,
 * a magnitude needing ten integer digits, and a scale finer than two -- must be refused at the
 * assignment that introduced them rather than at the provider. None of the three is a compile error,
 * and without the guard only one of them even fails at run time: an over-scale value is silently
 * coerced by the driver, and a ten-integer-digit magnitude is admitted by the general money contract,
 * whose own bound is the widest reference picture rather than the picture of the column being written.
 *
 * <p>The second is the explicit {@code CHAR} binding of every fixed-width column. A mapping that omits
 * it compiles, deploys, and reads and writes rows successfully; what changes is that the driver sends
 * the value as a variable-length string, so the database's own blank-padding comparison semantics no
 * longer apply and a padded stored value stops comparing equal to an unpadded bound one. That failure
 * surfaces as a query returning nothing, at a distance from the mapping that caused it.
 *
 * <p>The third is diagnostic rendering, where what a rendering carries is a disclosure decision
 * rather than a formatting preference. A log is retained, aggregated and readable by every holder of
 * log access, so a value emitted once per record accumulates into a searchable copy of whatever it
 * names.
 *
 * <p>Assumptions: all three properties need tests because all three regress silently. Deleting a
 * {@code @JdbcTypeCode} annotation breaks no build, and deleting a {@code toString} override breaks no
 * build either -- the generated or inherited rendering simply resumes and appears only in a log file
 * that no test reads. A monetary guard removed from a constructor or a mutator breaks no build either --
 * the value simply flows on to a column that will coerce it or fail on it later. Assertions on the
 * refusal itself, reflection over the mapped members and assertions on the rendered string are the only
 * mechanisms that turn any of the three regressions into a build failure.
 *
 * <p>Trade-offs: asserting an annotation by reflection couples a test to member names, so renaming a
 * field will fail this test rather than silently passing. That coupling is accepted deliberately: a
 * rename that dropped the annotation is exactly the regression being guarded against, and a failure
 * naming the member is more useful than a query that returns no rows in production.
 */
package com.carddemo.transaction.domain;
