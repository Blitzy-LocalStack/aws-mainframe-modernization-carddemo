package com.carddemo.card;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The entry point of the card context.
 *
 * <p>This is the migrated form of {@code app/cbl/COCRDLIC.cbl}, {@code app/cbl/COCRDSLC.cbl} and
 * {@code app/cbl/COCRDUPC.cbl} online, together with the batch reader {@code app/cbl/CBACT02C.cbl}. The
 * baseline surfaces the card data through a base VSAM cluster and the {@code CARDAIX} alternate index
 * over it; both become one table with a real secondary index on the account column here.</p>
 *
 * <p>Refactoring Rationale: the card list is the clearest case of a CICS browse becoming a keyset query.
 * {@code COCRDLIC} carries a last-key pair, a first-key pair, a screen number and a next-page indicator
 * in the communication area at lines 230 to 244 and discovers whether another page exists by reading one
 * more record than fits. That IS a keyset cursor, so the migrated form expresses the same cursor as a
 * page envelope rather than approximating it. Offset pagination was rejected outright: under concurrent
 * inserts it skips and repeats rows, which would change observable behaviour that browse-by-key does
 * not.</p>
 *
 * <p>Assumptions: the primary account number is narrowed to its final four digits everywhere except the
 * administrative detail operation, and the card verification value is returned by no operation at all.
 * Both rules belong to this context's mapping layer; this class only establishes that the context has
 * one entry point and therefore one place those rules are configured from.</p>
 *
 * <p>Assumptions: component scanning is rooted at this package, so the shared kernel's cross-cutting
 * components are not scanned -- they arrive through
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, which the framework loads from the shared
 * module's registration resource. A scan wide enough to reach them would also reach another context's
 * types, which is the coupling the layering test forbids.</p>
 */
@SpringBootApplication
public class CardApplication {

    /**
     * Creates the application type.
     *
     * <p>Assumptions: explicit and protected rather than implicit and public. The type exists to carry
     * the annotation and the entry point and nothing constructs it directly, so a wider constructor
     * would advertise a use it does not have.</p>
     */
    protected CardApplication() {
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
        SpringApplication.run(CardApplication.class, args);
    }
}
