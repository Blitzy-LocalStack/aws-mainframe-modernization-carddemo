package com.carddemo.reporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The entry point of the reporting and statement context.
 *
 * <p>This is the migrated form of {@code app/cbl/CORPT00C.cbl} online and of the batch report and
 * statement programs {@code app/cbl/CBTRN03C.cbl}, {@code app/cbl/CBSTM03A.CBL} and
 * {@code app/cbl/CBSTM03B.CBL}. It is the only context that owns no tables: its reads go to the writer
 * through read-only cross-schema views under a database role holding select and nothing else.</p>
 *
 * <p>Refactoring Rationale: the ad-hoc report submission the baseline performs by writing job control
 * into a transient data queue mapped to the internal reader -- the {@code TDQUEUE(JOBS)} definition at
 * line 502 of {@code app/csd/CARDDEMO.CSD} -- becomes a state-machine execution start. The mechanism is
 * replaced rather than reproduced because nothing in the target submits text to a job entry subsystem,
 * and the substitution is recorded in {@code docs/architecture/batch-orchestration.md}.</p>
 *
 * <p>Assumptions: two documented divergences from the baseline belong to this context and both are
 * corrections rather than changes of intent. The statement generator's two unchecked tables -- which
 * overflow at 512 same-card transactions and at 52 distinct cards -- have no counterpart here, because
 * the migrated implementation holds no fixed-arity table at all; and the 133-column report keeps its
 * exact edit masks so its output bytes stay comparable against the golden masters. Both are registered
 * in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Assumptions: component scanning is rooted at this package, so the shared kernel's cross-cutting
 * components are not scanned -- they arrive through
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}. A scan wide enough to reach them would
 * also reach another context's types, which is the coupling the layering test forbids.</p>
 */
@SpringBootApplication
public class ReportingApplication {

    /**
     * Creates the application type.
     *
     * <p>Assumptions: explicit and protected rather than implicit and public. The type exists to carry
     * the annotation and the entry point and nothing constructs it directly, so a wider constructor
     * would advertise a use it does not have.</p>
     */
    protected ReportingApplication() {
        // Assumptions: empty by design. The framework builds the context from the annotation on this
        // type; it never instantiates the type itself, so there is no state to establish here.
    }

    /**
     * Starts the context.
     *
     * @param args the command-line arguments, passed through so an operator can override any property
     *     on the command line exactly as they can on every other service in this repository; must not
     *     be {@code null}
     */
    public static void main(String[] args) {
        SpringApplication.run(ReportingApplication.class, args);
    }
}
