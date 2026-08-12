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
 * <p>Refactoring Rationale: that case previously enumerated two tokens as having no landed job, export
 * and import, and compared the vocabulary against the union of the landed set and that declared gap.
 * All seven tokens now resolve to a job bean, so the enumerated gap has been removed and the case
 * asserts set equality directly -- every advertised token has a landed job, and no landed job sits
 * outside the vocabulary. A declared gap that outlives the artifacts it described weakens the
 * assertion silently, because the union it is compared against grows to cover whatever is missing.</p>
 *
 * <h2>What this directory holds</h2>
 *
 * <pre>
 * this directory: 7 java files = 6 tests + 1 charter
 * </pre>
 *
 * <ul>
 *   <li>{@code JobRegistrationCensusTest} across 5 cases -- assembles a context over all seven job
 *       configurations and asserts bidirectionally that every declared token resolves to a job bean of
 *       that name and that no bean carries a name outside the vocabulary.</li>
 *   <li>{@code BatchJobRosterTest} across 5 cases -- the same agreement read from the classes
 *       themselves by reflection, plus distinctness of the registered names and of the durable ledger
 *       step names.</li>
 *   <li>{@code PostTransactionsJobTest} across 9 cases -- the posting job's step, its single
 *       transactional boundary and its reject-count return code.</li>
 *   <li>{@code DatasetJobBodiesTest} across 14 cases and {@code GenerationStagingJobsTest} across 6 --
 *       what the dataset-writing jobs emit and the generation prefix they emit it under.</li>
 *   <li>{@code CalculateInterestJobTest} across 4 cases -- the interest job's control break and its
 *       injected business date.</li>
 * </ul>
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
