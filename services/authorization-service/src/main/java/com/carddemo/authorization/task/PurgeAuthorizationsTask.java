package com.carddemo.authorization.task;

import com.carddemo.authorization.service.PurgeJob;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The task entry point for the pending-authorization expiry purge.
 *
 * <p>Assumptions: the bean NAME is the job name the orchestrator passes, which is what lets the runner
 * resolve it without a dispatch table. Refactoring Rationale: this class exists because
 * {@link PurgeJob} had no production caller at all -- its own documentation described it as
 * orchestrator-invoked while nothing invoked it -- so the purge was reachable only from its tests.</p>
 *
 * <p>Assumptions: the business date arrives as a PARAMETER and is never read from the clock, which is the
 * property that makes a rerun reproduce its predecessor exactly. The reference job takes the same date on
 * its execution card, so this preserves a contract rather than adding one, and it is why a missing date is
 * refused here rather than defaulted.</p>
 */
@Component(MaintenanceTaskRunner.PURGE_JOB)
public class PurgeAuthorizationsTask implements AuthorizationTask {

    /** The logger the task outcome is reported through. */
    private static final Logger LOG = LoggerFactory.getLogger(PurgeAuthorizationsTask.class);

    /** The purge this task invokes. */
    private final PurgeJob purge;

    /**
     * Builds the task over the purge it invokes.
     *
     * @param purge the purge job; must not be {@code null}
     * @throws NullPointerException if {@code purge} is {@code null}
     */
    public PurgeAuthorizationsTask(PurgeJob purge) {
        this.purge = Objects.requireNonNull(purge, "purge must not be null");
    }

    /**
     * Runs the purge for the stated business date and reports what it removed.
     *
     * <p>Assumptions: nothing is caught here. The runner translates a failure into the hard-failure exit
     * status the orchestrator branches on, and catching it here would either swallow it or duplicate that
     * translation in a second place where the two could disagree.</p>
     *
     * <p>⚠️ Refactoring Rationale: the three control values are now taken from the parameters when the
     * operator stated them, where this method passed the three published defaults unconditionally. That made
     * the expiry threshold permanently five days and the commit window permanently five summaries, so the
     * job's own {@code MAX_EXPIRY_DAYS} and {@code MAX_CARD_FREQUENCY} ceilings and its documented refusal of
     * a zero threshold were unreachable from any runtime entry point -- and an orchestrator state expressing
     * the reference program's parameter card at
     * {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} L98 to L108 had nothing to write into.</p>
     *
     * <p>Assumptions: each default is applied HERE, by falling back to the constant the job publishes, and
     * the runner deliberately does not put a default into the map. One publisher of each default means an
     * operator reading {@code PurgeJob} and an operator reading a run's log see the same number.</p>
     *
     * @param parameters the job parameters, carrying the business date under
     *     {@link MaintenanceTaskRunner#BUSINESS_DATE_PARAMETER} and, optionally, the expiry threshold and
     *     the two frequencies under their own names; must not be {@code null}
     * @throws NullPointerException if {@code parameters} is {@code null} or carries no business date
     * @throws IllegalArgumentException if a stated control value is outside the range its position on the
     *     reference parameter card can express, propagated from {@link PurgeJob.PurgeParameters}
     */
    @Override
    public void run(Map<String, String> parameters) {
        Objects.requireNonNull(parameters, "parameters must not be null");
        LocalDate businessDate = LocalDate.parse(Objects.requireNonNull(
                parameters.get(MaintenanceTaskRunner.BUSINESS_DATE_PARAMETER),
                "the business date parameter must be present"));
        int expiryDays = countOr(parameters, MaintenanceTaskRunner.EXPIRY_DAYS_PARAMETER,
                PurgeJob.DEFAULT_EXPIRY_DAYS);
        int checkpointFrequency = countOr(parameters,
                MaintenanceTaskRunner.CHECKPOINT_FREQUENCY_PARAMETER,
                PurgeJob.DEFAULT_CHECKPOINT_FREQUENCY);
        int progressLogFrequency = countOr(parameters,
                MaintenanceTaskRunner.PROGRESS_LOG_FREQUENCY_PARAMETER,
                PurgeJob.DEFAULT_PROGRESS_LOG_FREQUENCY);
        PurgeJob.PurgeOutcome outcome = this.purge.purge(new PurgeJob.PurgeParameters(businessDate,
                expiryDays, checkpointFrequency, progressLogFrequency));
        LOG.info("event=authorization.purge.outcome summariesRead={} summariesDeleted={}"
                        + " detailsRead={} detailsDeleted={}", outcome.summariesRead(),
                outcome.summariesDeleted(), outcome.detailsRead(), outcome.detailsDeleted());
    }

    /**
     * Reads one optional control value, falling back to the default the job publishes.
     *
     * <p>Assumptions: the value is parsed without a further range test, because the runner has already
     * refused anything that is not a positive whole number and {@link PurgeJob.PurgeParameters} refuses
     * anything outside the reference card field's width. A third check here would be a third place to keep
     * the same rule.</p>
     *
     * @param parameters the job parameters; must not be {@code null}
     * @param name the parameter name to read; must not be {@code null}
     * @param fallback the value to use when the parameter is absent
     * @return the stated value, or {@code fallback} when the operator stated none
     * @throws NumberFormatException if a value is present and is not a whole number, which the runner's own
     *     validation makes unreachable through the published entry point
     */
    private static int countOr(Map<String, String> parameters, String name, int fallback) {
        String stated = parameters.get(name);
        return stated == null ? fallback : Integer.parseInt(stated);
    }
}
