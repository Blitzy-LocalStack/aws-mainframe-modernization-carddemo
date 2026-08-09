package com.carddemo.authorization.task;

import com.carddemo.authorization.service.LoadService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>Assumptions: the two extracts are named as FILESYSTEM PATHS rather than object-store locations,
 * because the container the orchestrator runs this in stages its inputs into the task's filesystem before
 * it starts, exactly as the staging state of the batch state machine does for every other job. Reading a
 * remote location here would put a second, differently-configured transfer inside a job whose subject is
 * decoding, and would give a partially-transferred input no distinguishable failure.</p>
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

    /**
     * Builds the task over the loader it invokes.
     *
     * @param loader the extract loader; must not be {@code null}
     * @throws NullPointerException if {@code loader} is {@code null}
     */
    public LoadAuthorizationsTask(LoadService loader) {
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
    }

    /**
     * Loads both extracts and reports what was read, inserted and skipped.
     *
     * @param parameters the job parameters, carrying the two extract paths under
     *     {@link MaintenanceTaskRunner#ROOT_EXTRACT_PARAMETER} and
     *     {@link MaintenanceTaskRunner#CHILD_EXTRACT_PARAMETER}; must not be {@code null}
     * @throws IOException if either extract cannot be opened
     * @throws NullPointerException if {@code parameters} is {@code null} or an extract path is absent
     */
    @Override
    public void run(Map<String, String> parameters) throws IOException {
        Objects.requireNonNull(parameters, "parameters must not be null");
        Path roots = Path.of(Objects.requireNonNull(
                parameters.get(MaintenanceTaskRunner.ROOT_EXTRACT_PARAMETER),
                "the root extract path parameter must be present"));
        Path children = Path.of(Objects.requireNonNull(
                parameters.get(MaintenanceTaskRunner.CHILD_EXTRACT_PARAMETER),
                "the child extract path parameter must be present"));
        try (InputStream rootImages = Files.newInputStream(roots);
                InputStream childRecords = Files.newInputStream(children)) {
            LoadService.LoadOutcome outcome = this.loader.load(rootImages, childRecords);
            LOG.info("event=authorization.load.outcome read={} inserted={} alreadyPresent={}",
                    outcome.read(), outcome.inserted(), outcome.alreadyPresent());
        }
    }
}
