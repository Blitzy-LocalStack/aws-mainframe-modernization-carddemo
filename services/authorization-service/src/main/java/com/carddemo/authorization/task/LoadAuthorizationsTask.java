package com.carddemo.authorization.task;

import com.carddemo.authorization.service.LoadService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The task entry point for loading the pending-authorization extracts.
 *
 * <p>Assumptions: the bean NAME is the job name the orchestrator passes. Refactoring Rationale: this class
 * exists because {@link LoadService} had no production caller at all -- its own documentation described it
 * as orchestrator-invoked while nothing invoked it -- so the load was reachable only from its tests.</p>
 *
 * <p>Refactoring Rationale: the two extracts are resolved through {@link ExtractStore}, which accepts an
 * object-store location as well as a filesystem path. This block previously asserted that filesystem paths
 * were sufficient BECAUSE "the container the orchestrator runs this in stages its inputs into the task's
 * filesystem before it starts, exactly as the staging state of the batch state machine does for every other
 * job", and that claim was false in both of its halves. The batch chain's staging state runs the
 * data-migration image, and a Fargate volume is shared only WITHIN one task definition while
 * infra/modules/ecs-service creates one task definition per service -- so nothing the orchestrator ran
 * could put a byte into this container's filesystem, and the inputs this task required were undeliverable.
 * The concern the withdrawn text raised is answered rather than dismissed: {@link ExtractStore} transfers a
 * remote extract to a staging file IN FULL before the first byte is decoded, so a partial transfer fails as
 * a transfer and not as a malformed record part-way through a load.</p>
 *
 * <p>Assumptions: both streams are opened in a try-with-resources and the ROOT extract is passed first,
 * because the loader requires every authorization's parent summary to exist. That ordering is the load's
 * own precondition, not an implementation detail of this class, which is why it is fixed here rather than
 * left to the order an operator happens to pass the paths in.</p>
 */
@Component(MaintenanceTaskRunner.LOAD_JOB)
public class LoadAuthorizationsTask implements AuthorizationTask {

    /** The logger the task outcome is reported through. */
    private static final Logger LOG = LoggerFactory.getLogger(LoadAuthorizationsTask.class);

    /** The loader this task invokes. */
    private final LoadService loader;

    /** The store the two extract sources are resolved and staged through. */
    private final ExtractStore extracts;

    /**
     * Builds the task over the loader it invokes and the store it reads through.
     *
     * @param loader the extract loader; must not be {@code null}
     * @param extracts the extract store; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public LoadAuthorizationsTask(LoadService loader, ExtractStore extracts) {
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
        this.extracts = Objects.requireNonNull(extracts, "extracts must not be null");
    }

    /**
     * Loads both extracts and reports what was read, inserted and skipped.
     *
     * @param parameters the job parameters, carrying the two extract locations under
     *     {@link MaintenanceTaskRunner#ROOT_EXTRACT_PARAMETER} and
     *     {@link MaintenanceTaskRunner#CHILD_EXTRACT_PARAMETER}; must not be {@code null}
     * @throws IOException if either extract cannot be opened or transferred
     * @throws NullPointerException if {@code parameters} is {@code null} or an extract location is absent
     */
    @Override
    public void run(Map<String, String> parameters) throws IOException {
        Objects.requireNonNull(parameters, "parameters must not be null");
        String rootSource = Objects.requireNonNull(
                parameters.get(MaintenanceTaskRunner.ROOT_EXTRACT_PARAMETER),
                "the root extract location parameter must be present");
        String childSource = Objects.requireNonNull(
                parameters.get(MaintenanceTaskRunner.CHILD_EXTRACT_PARAMETER),
                "the child extract location parameter must be present");
        try (InputStream rootImages = this.extracts.openForRead(rootSource);
                InputStream childRecords = this.extracts.openForRead(childSource)) {
            LoadService.LoadOutcome outcome = this.loader.load(rootImages, childRecords);
            LOG.info("event=authorization.load.outcome read={} inserted={} alreadyPresent={}",
                    outcome.read(), outcome.inserted(), outcome.alreadyPresent());
        }
    }
}
