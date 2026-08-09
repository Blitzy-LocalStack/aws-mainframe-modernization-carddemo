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
     * @param parameters the job parameters, carrying the business date under
     *     {@link MaintenanceTaskRunner#BUSINESS_DATE_PARAMETER}; must not be {@code null}
     * @throws NullPointerException if {@code parameters} is {@code null} or carries no business date
     */
    @Override
    public void run(Map<String, String> parameters) {
        Objects.requireNonNull(parameters, "parameters must not be null");
        LocalDate businessDate = LocalDate.parse(Objects.requireNonNull(
                parameters.get(MaintenanceTaskRunner.BUSINESS_DATE_PARAMETER),
                "the business date parameter must be present"));
        PurgeJob.PurgeOutcome outcome =
                this.purge.purge(new PurgeJob.PurgeParameters(businessDate, PurgeJob.DEFAULT_EXPIRY_DAYS,
                        PurgeJob.DEFAULT_CHECKPOINT_FREQUENCY, PurgeJob.DEFAULT_PROGRESS_LOG_FREQUENCY));
        LOG.info("event=authorization.purge.outcome summariesRead={} summariesDeleted={}"
                        + " detailsRead={} detailsDeleted={}", outcome.summariesRead(),
                outcome.summariesDeleted(), outcome.detailsRead(), outcome.detailsDeleted());
    }
}
