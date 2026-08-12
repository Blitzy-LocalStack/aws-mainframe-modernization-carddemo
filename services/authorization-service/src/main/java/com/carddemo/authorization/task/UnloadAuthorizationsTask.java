package com.carddemo.authorization.task;

import com.carddemo.authorization.service.UnloadService;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The task entry point for exporting the pending-authorization segments to flat extracts.
 *
 * <p>Assumptions: the bean NAME is the job name the orchestrator passes. Refactoring Rationale: this class
 * exists because {@link UnloadService} had no production caller of any kind -- no task, no controller, no
 * schedule and no state in the batch state machine -- so the transcription of {@code cbl/PAUDBUNL.CBL} and
 * {@code cbl/DBUNLDGS.CBL} was reachable only from its own tests. The load and the purge had exactly this
 * defect and were given the two sibling tasks in this package; the export was left behind, which is why
 * this class is the third of three rather than one of two.</p>
 *
 * <p>Assumptions: the two destinations are resolved through {@link ExtractStore} rather than opened as
 * filesystem paths, because an extract written inside this task's own container is discarded when the
 * container exits. That makes the object-store form the one the orchestrator uses and is the whole reason
 * the capability is worth invoking; a task that wrote to a container-local path would satisfy the letter
 * of being invocable and produce nothing anybody could read.</p>
 *
 * <p>Assumptions: BOTH extracts are published only after the export has RETURNED, and neither is published
 * if it raised. The two files are one artefact -- a child record in the prefixed form is attributed to its
 * parent by a key that only the root file explains, and in the sequential form only by the interleaved
 * order of the two -- so a complete root file beside a truncated child file is not a partial export, it is
 * a misleading one. Alternatives Considered: publishing each as its stream closed. Rejected for exactly
 * that reason.</p>
 *
 * <p>Assumptions: the export form is an OPTION with a default rather than a required argument, so the
 * common invocation names only the two destinations. The default is {@link UnloadService#DEFAULT_FORM},
 * which is the form the loader reads back, and the option exists because the two reference programs the
 * service transcribes emit different child record shapes -- naming only one of them here would leave the
 * other as unreachable as the whole service was.</p>
 */
@Component(MaintenanceTaskRunner.UNLOAD_JOB)
public class UnloadAuthorizationsTask implements AuthorizationTask {

    /** The logger the task outcome is reported through. */
    private static final Logger LOG = LoggerFactory.getLogger(UnloadAuthorizationsTask.class);

    /** The exporter this task invokes. */
    private final UnloadService unloader;

    /** The store the two extract destinations are resolved and published through. */
    private final ExtractStore extracts;

    /**
     * Builds the task over the exporter it invokes and the store it publishes through.
     *
     * @param unloader the segment exporter; must not be {@code null}
     * @param extracts the extract store; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public UnloadAuthorizationsTask(UnloadService unloader, ExtractStore extracts) {
        this.unloader = Objects.requireNonNull(unloader, "unloader must not be null");
        this.extracts = Objects.requireNonNull(extracts, "extracts must not be null");
    }

    /**
     * Exports both extracts and reports what was written and what was passed over.
     *
     * <p>Assumptions: the outcome's skipped count is reported at INFO alongside the two written counts
     * rather than only when it is non-zero, because an operator comparing a run against the previous one
     * needs the figure present in both. The service's own diagnostics name no subject of a skipped row, so
     * the count is the whole of what this line can say about them.</p>
     *
     * @param parameters the job parameters, carrying the two extract destinations under
     *     {@link MaintenanceTaskRunner#ROOT_EXTRACT_PARAMETER} and
     *     {@link MaintenanceTaskRunner#CHILD_EXTRACT_PARAMETER} and the form under
     *     {@link MaintenanceTaskRunner#EXTRACT_FORM_PARAMETER}; must not be {@code null}
     * @throws IOException if either destination cannot be opened or published
     * @throws NullPointerException if {@code parameters} is {@code null} or a destination is absent
     * @throws IllegalArgumentException if the form parameter names no published form
     */
    @Override
    public void run(Map<String, String> parameters) throws IOException {
        Objects.requireNonNull(parameters, "parameters must not be null");
        String rootDestination = Objects.requireNonNull(
                parameters.get(MaintenanceTaskRunner.ROOT_EXTRACT_PARAMETER),
                "the root extract destination parameter must be present");
        String childDestination = Objects.requireNonNull(
                parameters.get(MaintenanceTaskRunner.CHILD_EXTRACT_PARAMETER),
                "the child extract destination parameter must be present");
        UnloadService.UnloadForm form = formIn(parameters);

        try (ExtractStore.StagedWrite roots = this.extracts.openForWrite(rootDestination);
                ExtractStore.StagedWrite children = this.extracts.openForWrite(childDestination)) {
            UnloadService.UnloadOutcome outcome =
                    this.unloader.unload(form, roots.stream(), children.stream());
            roots.publish();
            children.publish();
            LOG.info("event=authorization.unload.outcome form={} rootsWritten={} childrenWritten={} "
                            + "rootsSkipped={}",
                    form, outcome.rootsWritten(), outcome.childrenWritten(), outcome.rootsSkipped());
        }
    }

    /**
     * Resolves the export form, falling back to the default when the parameter is absent.
     *
     * @param parameters the job parameters; must not be {@code null}
     * @return the form to export in, never {@code null}
     * @throws IllegalArgumentException if the parameter is present and names no published form
     */
    private static UnloadService.UnloadForm formIn(Map<String, String> parameters) {
        String requested = parameters.get(MaintenanceTaskRunner.EXTRACT_FORM_PARAMETER);
        if (requested == null) {
            return UnloadService.DEFAULT_FORM;
        }
        return UnloadService.UnloadForm.fromRequestParameter(requested);
    }
}
