package com.carddemo.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The entry point of the sign-on and user-administration context.
 *
 * <p>This is the migrated form of {@code app/cbl/COSGN00C.cbl} together with the four user-maintenance
 * transactions {@code COUSR00C} through {@code COUSR03C}. The baseline runs them as five separate CICS
 * transactions over one shared {@code USRSEC} file; here they are one deployable, because they contend
 * over the same table and splitting them would put a bounded context's data behind two writers.</p>
 *
 * <p>Refactoring Rationale: the one behaviour this context deliberately does NOT carry across is the
 * credential comparison. Line 21 of {@code app/cpy/CSUSR01Y.cpy} declares an eight-character plaintext
 * password on the security record and line 223 of {@code app/cbl/COSGN00C.cbl} compares it directly
 * against what was typed. The field reaches no column, no transfer object and no constant here:
 * comparison moves to the managed identity provider and this context retains only a subject reference.
 * The divergence is registered in {@code docs/architecture/cobol-to-service-traceability.md} and is the
 * single point in the whole migration where behavioural parity is declined deliberately.</p>
 *
 * <p>Assumptions: the three sign-on messages the baseline emits are preserved character for character
 * by the service layer, as transformation rule T8 requires, so the identity change is invisible to the
 * person signing on -- the same sentences appear for the same conditions while what happens behind them
 * has moved off the mainframe entirely.</p>
 *
 * <p>Assumptions: component scanning is rooted at this package, so every class of this context is
 * discovered and no class of another context can be. The shared kernel's cross-cutting components are
 * NOT scanned -- they arrive through {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, which
 * the framework loads from the shared module's registration resource. A scan wide enough to reach them
 * would also reach another context's types, which is the coupling the layering test forbids.</p>
 */
@SpringBootApplication
public class AuthApplication {

    /**
     * Creates the application type.
     *
     * <p>Assumptions: explicit and protected rather than implicit and public. The type exists to carry
     * the annotation and the entry point and nothing constructs it directly, so a wider constructor
     * would advertise a use it does not have.</p>
     */
    protected AuthApplication() {
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
        SpringApplication.run(AuthApplication.class, args);
    }
}
