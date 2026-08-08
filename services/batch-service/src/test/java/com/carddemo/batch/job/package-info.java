/**
 * Tests over the step ordering, roster and unit of work of this module's batch job definitions.
 *
 * <h2>Purpose, and the boundary against the sibling tiers</h2>
 *
 * <p>Purpose: the production package of this same name holds job definitions and nothing else -- each
 * one wires readers, processors and writers, fixes the order its rules are applied in, and delegates
 * every rule to {@code com.carddemo.batch.service}. This tier asserts exactly that: which jobs exist,
 * what they are named, what order they apply their rules in, and what they write. It re-derives no
 * business rule.</p>
 *
 * <p>Assumptions: every ruling about a RULE is settled by the sibling {@code service} tier, against the
 * production type that carries it, and this tier consumes those rulings rather than restating them. The
 * reject precedence, both numeric boundaries, the interest formula, the default disclosure-group fallback
 * and the category-balance arms are all settled there. A case here that re-asserted one of them would
 * create a second declaration of one contract, and a second declaration is how two declarations come to
 * disagree.</p>
 *
 * <p>Assumptions: no case here starts an application context. A job definition's wiring is observable
 * through its collaborators, so a mocked collaborator settles the ordering rulings; and a context start
 * would need a data source and migrated tables in four schemas this module does not own, which settles
 * nothing this tier asserts. Whether the beans actually wire against a real database belongs to the
 * repository tier, which runs against one.</p>
 *
 * <h2>The roster is asserted, not assumed</h2>
 *
 * <p>Assumptions: the roster case in this package exists because this module states what it can run in
 * TWO independent places -- the entry point's accepted token list and the set of registered job beans --
 * and nothing but a test makes them agree. A token accepted by the argument check with no bean behind it
 * produces a container that starts, validates its arguments and then fails inside the state machine, so
 * the gap is decidable at build time and is worth deciding there.</p>
 *
 * <p>Assumptions: the two tokens with no landed job are enumerated explicitly in that case rather than
 * left to be inferred from a failure, so the gap is a measured statement a reader can find rather than
 * something discovered from an incident. It cannot widen without that file changing.</p>
 *
 * <h2>Baseline lineage: provenance only</h2>
 *
 * <p>Every citation in this package is provenance. Nothing under {@code app/**} is read at run time and
 * nothing under it is altered by this migration -- the reference implementation is the behavioural oracle
 * and stays byte-identical. Where migrated behaviour differs from the reference, the divergence is
 * registered in {@code docs/architecture/cobol-to-service-traceability.md}. Line numbers refer to the
 * source as committed, and in a job line columns 73 to 80 carry a sequence field that is not part of the
 * statement.</p>
 */
package com.carddemo.batch.job;
