/**
 * The batch entry points the orchestrator dispatches, one per job name it knows.
 *
 * <h2>What this package holds</h2>
 *
 * <p>Purpose: {@code com.carddemo.reporting.ReportingTaskRunner} turns a {@code --job=} token into a
 * Spring bean lookup by that exact name and invokes {@code ReportingTask.run}. Its accepted-name list is
 * therefore a bean-name contract with this package, and every name on it must resolve to a component here
 * or the run exits with the task-unresolved code having produced nothing. This package holds one class
 * per name, plus three collaborators the tasks share:</p>
 *
 * <ul>
 *   <li>{@code GenerateStatementsTask} -- registered as {@code generate-statements}. The nightly
 *       statement run over one business date, producing the plain-text and markup datasets.</li>
 *   <li>{@code GenerateReportsTask} -- registered as {@code generate-reports}. The nightly run of BOTH
 *       reports: the transaction report, whose range is that one business date treated as an inclusive
 *       one-day period, and the category-balance report, which takes no range because
 *       {@code app/jcl/PRTCATBL.jcl:44-45} feeds its sort the whole unloaded file with no
 *       {@code INCLUDE} condition.</li>
 *   <li>{@code GenerateAdHocReportTask} -- registered as {@code generate-report}, singular. The
 *       on-demand run a request submits, whose range arrives with the request.</li>
 *   <li>{@code ReportArtifactPublisher} -- not a task. The shared transaction-report publication path
 *       the two report tasks both drive. The nightly path publishes to TWO keys from one generation
 *       pass: the request-scoped key a range-addressed request resolves, and the generation key of the
 *       {@code tranrept} family, which is what carries the {@code LIMIT(5)} analogue.</li>
 *   <li>{@code CategoryBalanceArtifactPublisher} -- not a task. The category-balance report's
 *       publication path, driven only by the nightly task. It is a second publisher rather than a
 *       method on the first because the two artifacts share no key rule, no record length and no
 *       source relation.</li>
 *   <li>{@code GenerationKeys} -- not a task. The generation-key convention and the number a new write
 *       is allocated, read by listing the date partition. Retention is NOT performed here: the bucket's
 *       object-created notification drives {@code infra/lambda/dataset_generation_retention.py}, which
 *       prunes past the newest five.</li>
 * </ul>
 *
 * <p>Assumptions: the roster is closed at six and is a MEASUREMENT of the directory rather than an
 * assignment -- all six classes have landed. Three of the entries are pinned by the runner's own
 * accepted-name list, so the roster cannot grow or shrink in its TASK half without that list changing
 * too, and the agreement between the two is asserted by a test rather than left to a reader.
 * Refactoring Rationale: the roster was four. The three collaborators arrived with the
 * category-balance report and the generation key it and the transaction report are both numbered
 * under; the task half is unchanged at three, which is why the runner's list did not move.</p>
 *
 * <h2>Why singular and plural names both appear</h2>
 *
 * <p>Assumptions: {@code generate-reports} and {@code generate-report} differ by one character and are
 * deliberately kept distinct rather than collapsed onto one bean. They are different units of work -- a
 * scheduled run the orchestrator starts on a cron, against a run a caller submitted and is waiting on --
 * and one name for both would make the two indistinguishable in every journal line, every execution
 * history and every alarm. The near-collision is the cost of following the orchestrator's existing
 * vocabulary rather than inventing a second one, and the alternative -- renaming one of them -- would put
 * this package's names out of step with the state machine and the runbooks that already quote them.</p>
 *
 * <p>Trade-offs: the two report tasks differ only in how they arrive at a range, so their bodies are
 * short and the substance sits in the publishers. That is intentional: the object key
 * rule and the sink lifecycle stated twice would let two copies of the key rule disagree about where an
 * artifact lives, and a reader discovers that kind of disagreement by finding no artifact rather than by
 * reading two files.</p>
 *
 * <h2>What a task is responsible for, and what it is not</h2>
 *
 * <p>Assumptions: a task reads its parameters, refuses an unusable one, opens a sink, drives one
 * generator and writes one journal line. It does not parse the command line -- the runner has already
 * validated every option it requires and put the values in the map -- and it does not translate its own
 * failure into an exit code, because the runner owns the exit-code rubric and a task that caught its own
 * failure would report success. Every task therefore lets an exception propagate, which is what the
 * runner's failure path is written against.</p>
 *
 * <p>Assumptions: a required parameter absent from the map is refused rather than defaulted, and the
 * refusal quotes the COMMAND-LINE option an operator typed rather than the internal parameter key they
 * never saw. Defaulting a business date to the clock is the specific mistake the migration plan forbids
 * across the whole batch tier: it makes a rerun produce a different answer from the run it is repeating,
 * which destroys the reproducibility the golden-master comparison depends on.</p>
 *
 * <p>Assumptions: nothing here opens a database transaction. The generators own their own bounded reads
 * and close each one before a sink write is in flight, and a transaction opened at this layer would span
 * the whole run and the whole of the object-store I/O with it -- the defect this shape was restructured
 * to remove.</p>
 *
 * <h2>Journal lines</h2>
 *
 * <p>Assumptions: each task logs exactly one line on success, carrying the range it covered, the
 * orchestrator's identity for the run and the exact locator of the artifact it wrote, together with the
 * counts the generator returned, so an execution can be reconciled against its artifact without reading
 * the artifact. The identity and the locator are what make that reconciliation possible in both
 * directions -- from a state machine history entry to an object, and from an object back to the run that
 * produced it -- and an earlier revision carried neither. Counts are safe to publish and identifiers are not, so no line here carries an account
 * identifier, a customer identifier, a card number or a monetary amount. A caller-supplied string that
 * reaches a journal line is sanitised first, because a value carrying a line terminator forges a log
 * entry.</p>
 *
 * <p>Alternatives Considered: logging the object key each task published, which would make an artifact
 * directly findable from its journal line. Rejected because the key is derived from an opaque token
 * precisely so that the stored artifact cannot be attributed from its name, and printing the key beside
 * the range would hand back most of what the token withholds.</p>
 *
 * <h2>Why this charter exists</h2>
 *
 * <p>Assumptions: the project Explainability rule requires a docstring on every module entry point, and
 * in Java a package's entry point is its package declaration, which only {@code package-info.java} can
 * carry. Two Checkstyle modules enforce that independently: {@code JavadocPackage} requires this file to
 * exist in any directory holding an audited source file, and {@code MissingJavadocPackage} requires it to
 * carry Javadoc, so a bare package statement satisfies the first and fails the second. The written
 * convention every block here follows is {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and
 * never restated. No parameter, return or exception at-clause appears, because a package declaration
 * accepts no argument, yields no value and raises nothing.</p>
 */
package com.carddemo.reporting.task;
