package com.carddemo.batch.repository;

import com.carddemo.batch.domain.BatchRun;
import com.carddemo.batch.domain.BatchRun.BatchRunStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and writes the durable step ledger of the batch bounded context.
 *
 * <p>The table behind this interface is {@code batch.batch_run}, and one row of it stands for one
 * step of one orchestrator execution. A step inserts its row when it opens and updates that row
 * when it reaches a terminal state, so the ledger answers the one question a redrive has to ask
 * before it does anything at all: has this step of this run already finished, and with what
 * outcome. That answer is what turns a redriven, already-completed step into a no-op instead of a
 * second pass over the same input.</p>
 *
 * <h2>Why this one interface carries the full read and write surface</h2>
 *
 * <p>Alternatives Considered: a narrower base type, so that this interface exposed only the two
 * finders below and none of the inherited surface. Rejected because the ownership above is real and
 * the write surface is genuinely used -- a step opens its row, later transitions it, and the
 * migration that created the table is this module's own. A reader comparing this file against its
 * siblings will find the opposite decision taken for the same reason read the other way round: the
 * charter withholds every write member from {@code DisclosureGroupRepository}, because this module
 * holds no write privilege on {@code reference} at all, so a full read and write base type there
 * would compile and then be refused by the database. The base type follows the grant in both cases
 * rather than a wish for symmetry between the two files.</p>
 *
 * <h2>This ledger is a capability the target adds</h2>
 *
 * <p>Refactoring Rationale: there is no earlier mechanism whose behaviour this interface reproduces,
 * and stating that plainly matters because the reverse reading is the easy mistake to make. Across
 * the thirty-eight members of {@code app/jcl} exactly one restart directive appears, at
 * {@code app/jcl/DEFGDGD.jcl:2}, and it is written {@code //*  RESTART=STEP30} -- the leading
 * {@code //*} makes the line a comment, so no job ever acts on it. No checkpoint directive appears
 * in any of those thirty-eight members at all. The reference pipeline consequently expresses no
 * restart or checkpoint contract, this ledger is an addition the target makes rather than a port of
 * something that existed, and the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. It follows that a resumed step's
 * idempotency has to rest on this table's own uniqueness constraint, recorded on the first finder
 * below, because there is no prior behaviour to appeal to.</p>
 *
 * <h2>Rulings this interface inherits from the package charter</h2>
 *
 * <p>Alternatives Considered: native SQL, which is the obvious reach in a batch module because a
 * nightly pass is naturally set-shaped and reads more directly as one statement. Rejected on the
 * timing of the failure it admits. This module runs the persistence provider with schema handling
 * set to {@code validate}, and that pass compares MAPPING METADATA against the deployed table; it
 * never parses the text of a native query. A property path resolving to a column the schema does not
 * have is therefore reported at start-up, before a row is read, whereas a mistyped physical column
 * inside a native statement stays invisible until that statement executes -- which for this module
 * means part-way through a nightly chain, with earlier steps already committed. Both members below
 * are derived methods bound to property names declared on {@code BatchRun}, so no physical column
 * name appears anywhere in this file.</p>
 */
public interface BatchRunRepository extends JpaRepository<BatchRun, Long> {

    /**
     * Finds the ledger row already recorded for one step of one orchestrator run.
     *
     * <p>This is the read a redriven execution performs before it does any work. A present row whose
     * state is terminal tells the caller that this step of this run has already been carried out, so
     * the caller returns without repeating the step's writes; an absent row tells it that the step
     * has not been recorded for this run and that it is free to open one.</p>
     *
     * @param runId the orchestrator execution name to look under, a {@code String} of at most
     *     eighty characters, and the LEADING component of the uniqueness constraint that makes this
     *     query single valued; must not be {@code null}
     * @param stepName the state-machine step name to look for, a {@code String} of at most one
     *     hundred characters, and the TRAILING component of that same constraint; must not be
     *     {@code null}
     * @return the one {@code BatchRun} recorded for that run and step, as an
     *     {@code Optional<BatchRun>}, or an EMPTY optional when the step has not been recorded for
     *     this run -- which is a normal outcome rather than an error, since the first execution of
     *     any step necessarily finds nothing
     */
    // Assumptions: the single-result contract this optional states is a claim about the
    //     SCHEMA and not a convenience of the return type. V1__batch.sql declares
    //     uq_batch_run_run_step UNIQUE (run_id, step_name), and BatchRun mirrors it as a named
    //     unique constraint on its mapping, so the pair matches at most one row and the query is
    //     provably single valued. Were that constraint absent, this same signature would be a
    //     latent runtime failure instead: Spring Data raises an incorrect-result-size data-access
    //     exception the first time a second row matched, and no earlier check would have caught
    //     it. That is exactly why the contrast with the sibling CardXrefRepository is worth
    //     stating -- its account-id lookup cannot be shaped this way, because
    //     app/jcl/XREFFILE.jcl:74-75 defines that access path as KEYS(11,25) NONUNIQUEKEY and its
    //     relational replacement is a non-unique index. An optional is earned per table.
    // Refactoring Rationale: this method is the one the reference pipeline has no counterpart
    //     for. The pipeline's only restart directive is the inert comment at
    //     app/jcl/DEFGDGD.jcl:2, and no checkpoint directive appears in any of the thirty-eight
    //     members of app/jcl, so there is no prior mechanism to reproduce and no behaviour to
    //     compare a result against. The capability is one the target adds, which is why the
    //     divergence is registered in the traceability matrix rather than presented as parity.
    Optional<BatchRun> findByRunIdAndStepName(String runId, String stepName);

    /**
     * Lists the steps of one orchestrator run that stand in a given lifecycle state.
     *
     * <p>This is the read that turns the ledger into a picture of a whole run rather than of a
     * single step. An operator diagnosing a nightly chain asks it for the failed steps of the run
     * under investigation, and a caller establishing what a redrive still has left to do asks it for
     * the steps that are recorded as in flight.</p>
     *
     * @param runId the orchestrator execution name whose steps are wanted, a {@code String} of at
     *     most eighty characters, and the leading column of the unique index that answers this
     *     query; must not be {@code null}
     * @param status the lifecycle state to select, a {@code BatchRunStatus} drawn from the closed
     *     set of {@code STARTED}, {@code COMPLETED} and {@code FAILED} that {@code BatchRun}
     *     declares; must not be {@code null}
     * @return that run's matching rows as a {@code List<BatchRun>}, ordered by the database-assigned
     *     identity ascending, and an EMPTY list when the run holds no step in that state -- which is
     *     a normal outcome rather than an error, since a clean run holds no failed step at all
     */
    // Alternatives Considered: ordering by the recorded start time, which is the ordering a
    //     reader expects of a chronology. Declined because that value is supplied by the caller
    //     from an injected clock and is normalised to a constant under test, so equal values are
    //     routine and the resulting order would be partial rather than total. The identity is
    //     assigned by the database as the row is inserted, and a row is inserted when its step
    //     OPENS, so ordering by it is the same chronology and is a total order. The sibling
    //     OutboxRepository declines timestamp ordering on the same ground, that two rows written
    //     inside one microsecond cannot be separated by it.
    // Alternatives Considered: a third finder returning a run's steps irrespective of state,
    //     and a fourth counting them. Both are declined because no caller in
    //     com.carddemo.batch.job needs either one: a redrive decision is taken per step and is
    //     served by the finder above, while an operational view of a run is a state-scoped
    //     question and is served by this one. The inherited members already cover administrative
    //     access, and a member with no caller is surface that has to be maintained and that no
    //     test can meaningfully exercise.
    List<BatchRun> findByRunIdAndStatusOrderByIdAsc(String runId, BatchRunStatus status);
}
