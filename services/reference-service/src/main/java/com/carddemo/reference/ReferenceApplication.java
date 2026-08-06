package com.carddemo.reference;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The entry point of the reference-data context.
 *
 * <p>This is the migrated form of the transaction-type screens
 * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} and {@code COTRTUPC.cbl}, their batch updater
 * {@code COBTUPDT.cbl}, the queue-driven date conversion {@code app/app-vsam-mq/cbl/CODATE01.cbl} and
 * the date-edit utility {@code app/cbl/CSUTLDTC.cbl}. It also owns the seeded lookup tables the address
 * validation of other contexts reads, drawn from the 490 codes of {@code app/cpy/CSLKPCDY.cpy}.</p>
 *
 * <p>Refactoring Rationale: this context does not exist in the baseline's own decomposition. It was
 * added because the reference and lookup data has readers in three other contexts, and leaving it in any
 * one of them would have given a shared table an owner that also had unrelated reasons to change. The
 * transaction-type screens were folded in here for the same reason rather than standing up as their own
 * service: they are a screen over data this context owns.</p>
 *
 * <p>Assumptions: the referential rule the baseline expresses as a Db2 restrict-on-delete constraint is
 * preserved as a real foreign key, and its violation surfaces as HTTP 409 rather than as a driver error.
 * A category still referencing a type therefore blocks that type's deletion exactly as it does today,
 * and the caller is told why in the migrated vocabulary instead of being shown the store's internal
 * shape.</p>
 *
 * <p>Assumptions: component scanning is rooted at this package, so the shared kernel's cross-cutting
 * components are not scanned -- they arrive through
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}. A scan wide enough to reach them would
 * also reach another context's types, which is the coupling the layering test forbids.</p>
 */
@SpringBootApplication
public class ReferenceApplication {

    /**
     * Creates the application type.
     *
     * <p>Assumptions: explicit and protected rather than implicit and public. The type exists to carry
     * the annotation and the entry point and nothing constructs it directly, so a wider constructor
     * would advertise a use it does not have.</p>
     */
    protected ReferenceApplication() {
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
        SpringApplication.run(ReferenceApplication.class, args);
    }
}
