package com.carddemo.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The entry point of the transaction ledger context.
 *
 * <p>This is the migrated form of {@code app/cbl/COTRN00C.cbl}, {@code app/cbl/COTRN01C.cbl} and
 * {@code app/cbl/COTRN02C.cbl} together with the bill-payment transaction
 * {@code app/cbl/COBIL00C.cbl}. The four record layouts this context owns -- the 350-byte transaction,
 * the 350-byte daily transaction, the reject stream and the 50-byte category balance -- all become
 * tables of one schema, and the batch alternate index over the processing timestamp becomes an ordinary
 * secondary index.</p>
 *
 * <p>Assumptions: money never leaves fixed point in this context and it is the context where that
 * matters most, because every amount here is a posted value rather than a rate or a limit. Amounts are
 * exact decimal in the column, exact decimal in Java, and a JSON STRING on the wire. The string is not
 * fastidiousness: a JSON number is parsed into an IEEE-754 double by most clients, which destroys
 * exactness at the boundary the user actually sees. The rule is enforced by the shared money module,
 * which this context receives through the shared kernel's auto-configuration rather than registering
 * itself.</p>
 *
 * <p>Assumptions: the endpoints of this context are the ones whose misuse moves money -- adding a
 * transaction and taking a bill payment -- which is why its filter chain admits nothing unauthenticated
 * except the health probe, and why the token checks it installs are the same three every other context
 * installs rather than a subset chosen here.</p>
 *
 * <p>Assumptions: component scanning is rooted at this package, so the shared kernel's cross-cutting
 * components are not scanned -- they arrive through
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}. A scan wide enough to reach them would
 * also reach another context's types, which is the coupling the layering test forbids.</p>
 */
@SpringBootApplication
public class TransactionApplication {

    /**
     * Creates the application type.
     *
     * <p>Assumptions: explicit and protected rather than implicit and public. The type exists to carry
     * the annotation and the entry point and nothing constructs it directly, so a wider constructor
     * would advertise a use it does not have.</p>
     */
    protected TransactionApplication() {
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
        SpringApplication.run(TransactionApplication.class, args);
    }
}
