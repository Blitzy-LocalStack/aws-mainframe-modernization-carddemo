package com.carddemo.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The entry point of the account, customer and card-cross-reference context.
 *
 * <p>This is the migrated form of {@code app/cbl/COACTVWC.cbl} and {@code app/cbl/COACTUPC.cbl} online,
 * of the batch readers {@code CBACT01C}, {@code CBACT03C} and {@code CBCUS01C}, and of the queue-driven
 * account inquiry {@code app/app-vsam-mq/cbl/COACCT01.cbl}. All four data sets the baseline keeps in
 * separate VSAM clusters -- account, customer, cross-reference and the {@code CXACAIX} alternate index
 * over the cross-reference -- become one schema with a real secondary index here, so one deployable owns
 * all of them.</p>
 *
 * <p>Refactoring Rationale: the account-inquiry flow is folded into this context rather than standing up
 * as its own service. It is an alternate TRANSPORT over data this context already owns, so a separate
 * deployable would have split ownership of one table across two writers -- the exact failure mode
 * bounded contexts exist to prevent. The refinement is recorded against the candidate service list in
 * the migration plan.</p>
 *
 * <p>Assumptions: the optimistic concurrency this context needs is not an addition. The baseline already
 * implements a before-image check across the pseudo-conversational gap -- it snapshots the pre-edit
 * record from {@code app/cbl/COACTUPC.cbl} line 669 onward and rolls back at lines 4095 to 4104 when the
 * rewrite finds the record changed -- so the migrated form expresses the same rule natively with a
 * version column and answers a lost update with HTTP 409. Nothing is gained or given up; a mechanism is
 * stated in the target's own vocabulary.</p>
 *
 * <p>Assumptions: component scanning is rooted at this package, so the shared kernel's cross-cutting
 * components are not scanned -- they arrive through
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}. A scan wide enough to reach them would
 * also reach another context's types, which is the coupling the layering test forbids.</p>
 */
@SpringBootApplication
public class AccountApplication {

    /**
     * Creates the application type.
     *
     * <p>Assumptions: explicit and protected rather than implicit and public. The type exists to carry
     * the annotation and the entry point and nothing constructs it directly, so a wider constructor
     * would advertise a use it does not have.</p>
     */
    protected AccountApplication() {
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
        SpringApplication.run(AccountApplication.class, args);
    }
}
