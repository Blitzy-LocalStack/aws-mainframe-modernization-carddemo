package com.carddemo.card;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bootstraps the card bounded context as a standalone deployable.
 *
 * <h2>What this file is</h2>
 *
 * <p>This is the single entry point of the card service, and its whole job is to name the
 * configuration root the framework assembles a context from. The behaviour that context serves is
 * migrated from three online programs reached through CICS and one batch program, all of them
 * reference material read as the specification and never modified: the card list
 * {@code app/cbl/COCRDLIC.cbl} at 1459 lines, whose transaction {@code CCLI} is defined at
 * {@code app/csd/CARDDEMO.CSD} L357; the card detail {@code app/cbl/COCRDSLC.cbl} at 887 lines,
 * transaction {@code CCDL} at L347; the card update {@code app/cbl/COCRDUPC.cbl} at 1560 lines,
 * transaction {@code CCUP} at L367; and the sequential card-file reader
 * {@code app/cbl/CBACT02C.cbl} at 178 lines.</p>
 *
 * <p>Assumptions: the charter for this package, its closed subpackage set and the record contract
 * the whole context derives from are held once in the sibling {@code package-info.java} and are
 * deliberately not restated here. That charter names {@code app/cpy/CVACT02Y.cpy} as the normative
 * layout, whose six declared fields and trailing filler sum to the 150-byte record length recorded
 * at its own L2. A second copy of either the charter or that layout in this file would be a second
 * thing to keep true, and whichever copy went stale would be indistinguishable from the one that
 * had not.</p>
 *
 * <h2>How the shared kernel reaches this context</h2>
 *
 * <p>Alternatives Considered: the annotation below carries no {@code scanBasePackages} and no
 * {@code @Import}, and both absences are decisions rather than defaults.
 * {@code @SpringBootApplication} implies a component scan rooted at the annotated class's own
 * package, so it reaches {@code com.carddemo.card} and stops there, which leaves the shared
 * kernel's Spring-managed components outside the root. Three mechanisms were weighed, and the two
 * that were rejected are recorded because each looks reasonable until its cost is named.</p>
 *
 * <p>The first was a surgical import of the shared error advice and the shared metrics
 * configuration, which has the merit of naming exactly what enters the context. It is rejected on
 * two measured grounds. The kernel already registers both: its resource
 * {@code services/common-lib/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * names {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, whose L78 imports the metrics
 * configuration unconditionally and whose nested {@code ServletErrorConfiguration} at L499 supplies
 * the error advice from the bean method at its L516, behind the missing-bean condition at L515 and
 * under two class-level guards, a class-presence condition opening at L493 and a servlet
 * web-application condition at L498. An import here would satisfy that missing-bean condition and
 * so replace a guarded registration with an unguarded one, contributing the advice to contexts the
 * kernel deliberately withholds it from. Separately, the advice declares no no-argument
 * constructor: the only constructor
 * {@code services/common-lib/src/main/java/com/carddemo/common/error/GlobalExceptionHandler.java}
 * offers is at its L622 and takes a clock, so importing the class from here would make this file
 * depend on a bean whose only contributor is that same auto-configuration, at its L207.</p>
 *
 * <p>The second was widening {@code scanBasePackages} to name this package and the kernel package
 * {@code com.carddemo.common} together. It is rejected because a scan root is an open set where an
 * import list is a closed one: every component the kernel gains later would be adopted by this
 * deployable without anyone choosing it. The kernel's own registration resource rejects per-service
 * registration by name on the matching ground that a registration a service has to remember is one
 * a service can omit, and that the symptom of omitting it is invisible.</p>
 *
 * <p>The third is the mechanism the kernel publishes, and it is the one in force here: declare
 * neither, and let that auto-configuration contribute the correlation filter, the common-tag meter
 * filter, the money codec module and the single error advice to every service that puts the module
 * on its path. Assumptions: this rests on the kernel publishing those components from package roots
 * that no service scans, which is the entire reason an explicit registration step is under
 * discussion at all, and on the import resource named above travelling inside the packaged jar. Were
 * that resource dropped, the loss would be silent rather than loud: responses would still be served,
 * but log lines would carry no correlation identity, meters no service dimension, and a failed
 * request would be rendered in the framework's default shape instead of this system's problem
 * shape.</p>
 *
 * <p>Trade-offs: the scan root is left at this one package and is never widened towards
 * {@code com.carddemo}, which would reach the seven sibling service roots that
 * {@code services/pom.xml} declares beside this one at its L233 to L241. What is given up is the
 * convenience of one root covering everything; what it buys is a deployable that cannot couple to
 * another bounded context by accident. The boundary is enforced by the layering rules at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java},
 * which is a test and therefore cannot rot, and the documentation gate at
 * {@code config/checkstyle/checkstyle.xml} declares no import-control module, so layering has
 * exactly one owner rather than two that could disagree.</p>
 *
 * <p>Assumptions: two kernel types that a reader might expect here are registered elsewhere on
 * purpose. The correlation filter's position is a property of the security filter chain rather than
 * of the entry point, and the claim-to-authority converter plugs into the resource-server wiring, so
 * both belong to {@code com.carddemo.card.config.SecurityConfig}, which owns the chain that any
 * ordering is relative to. Registering either from here would put ordering knowledge in a file that
 * holds no chain, which is how a filter ends up running after the authentication failure it was
 * meant to label.</p>
 *
 * <h2>What this file deliberately does not do</h2>
 *
 * <p>Assumptions: this class declares no property and no property default. The whole shape of this
 * module's configuration belongs to the sibling {@code application.yml}, which owns the listener
 * port, the datasource and its pool, the schema search path pinned to the one schema this context
 * owns, the migration location, the token issuer, the actuator exposure and the meter tags that the
 * kernel's metrics configuration then reads. The two profile documents beside it override values
 * only. No active profile is set here in any form, because a profile chosen in code would make one
 * built image behave differently from its own committed configuration and would defeat the
 * per-environment parameterization those two documents exist to provide.</p>
 *
 * <p>Assumptions: the health endpoint is not configured here either. It arrives with the actuator
 * starter and its exposure is pinned in that same configuration file, and it has to stay reachable
 * without a credential because both the load-balancer target-group registration and this module's
 * container health check poll it and neither can present a token. Permitting that one path is
 * {@code SecurityConfig}'s to do, and it is not this file's path to move or rename.</p>
 *
 * <p>Alternatives Considered: no repository-enabling or entity-scanning annotation appears below.
 * Each of those defaults to the package of the annotated class, so both already cover
 * {@code com.carddemo.card.repository} and {@code com.carddemo.card.domain}, and adding either would
 * restate a default while inviting a future reader to assume the default had been unsafe.</p>
 *
 * <p>Assumptions: this context carries no messaging and no batch concern, so no queue or job
 * configuration is registered here and none exists in this module. The absence is a property of the
 * migrated baseline rather than an omission: the three card screens exchange no message, and the one
 * batch program named above is a read-and-print utility whose logic becomes a read path on the
 * repository.</p>
 *
 * <p>Alternatives Considered: no resilience dependency, no circuit breaker and no retry annotation
 * appears here, and the layering rules assert that absence across every package root in this system
 * rather than leaving it to review. Spring Framework 7, which arrives inside the Spring Boot parent
 * this module inherits, moved retry into the framework core, so a third-party resilience library
 * would add a dependency for a capability already present; and the framework's own enabling
 * annotation and its retry-count attribute both differ in name from the older forms that are easy to
 * reach for from memory. Adding either to this entry point without a concrete requirement would be a
 * capability nobody asked for, placed where no call is made.</p>
 *
 * <h2>How this context is verified</h2>
 *
 * <p>Assumptions: three of the four programs named above are online CICS programs, and
 * {@code tests/README.md} records at its L83 to L85 that such programs cannot be run end to end
 * without a CICS runtime, which is absent from the runner. No executable baseline oracle therefore
 * exists for those paths and nothing here claims one: their behaviour is verified against the
 * programs and copybooks read as a specification, plus the tests in this module's own test tree.
 * What that does not weaken is text fidelity, since every user-visible message remains directly
 * checkable against its originating program character for character. The audit that governs this
 * file is pass or fail with no tolerated warning level, because the documentation gate is bound to
 * Maven's {@code validate} phase at {@code services/pom.xml} L841 with {@code failOnViolation} true
 * at its L930 and {@code violationSeverity} at warning at its L931, so a warning and a failure are
 * one outcome. The graded condition-code rubric of the COBOL suite, under which code 4 counts as a
 * passing result, describes no build in this tree, and reading a build here through it would treat a
 * real violation as acceptable.</p>
 *
 * @see org.springframework.boot.SpringApplication
 */
@SpringBootApplication
public final class CardApplication {

    /**
     * Withholds construction of this entry point from application code.
     *
     * <p>Refactoring Rationale: the type is declared final with a private constructor because
     * nothing constructs it and nothing extends it. {@code SpringApplication.run(Class, String[])}
     * registers the argument as a primary configuration source and reflects over its annotations,
     * and this class declares no bean method at all, so there is no inter-bean reference for the
     * framework to intercept and no need for it to subclass this type or to hold an instance built
     * by application code. Making that structural is what stops a later contributor turning the
     * entry point into a holder of services or state, which is the drift the layering rules exist to
     * catch, and it is cheaper to prevent here than to unpick once something depends on it.</p>
     *
     * <p>Assumptions: the shape above is safe only while this class stays a primary configuration
     * source that declares no bean method, and the failure mode if that stops holding is specific
     * rather than gradual. A final class registered by component scanning instead of as a primary
     * source cannot be enhanced, because a scanned configuration class is subclassed and a final
     * class cannot be; what keeps the two from colliding is that the primary-source registration of
     * this package pre-empts the scan of it by bean name. Adding a bean method here would remove
     * that protection as well as breaching this file's closed set of responsibilities.</p>
     */
    private CardApplication() {
        // Assumptions: deliberately empty, and empty is the whole contract. This type declares zero
        //       fields, and the framework builds the context from the annotation on it rather than
        //       from anything an instance holds, so there is no state to establish here. A body that
        //       did initialise something would be state the annotation cannot see, reachable only
        //       through an instance that nothing in this deployable ever asks for.
    }

    /**
     * Starts the card bounded context as a Spring Boot application.
     *
     * <p>The call is left to propagate. A failure to assemble the context surfaces as an unchecked
     * exception that terminates the process with a non-zero exit code, which is the signal the
     * container runtime and the orchestrator both read. Catching it here, or converting it into an
     * explicit process exit, would turn a task that visibly failed to start into one that appears to
     * have started and then serves nothing, and the placeholders this module's configuration leaves
     * unresolved on purpose are exactly the failures that need to stay visible.</p>
     *
     * @param args the process command-line arguments as supplied by the launcher, each entry a
     *     {@link String}, forwarded unmodified to {@link SpringApplication#run(Class, String[])};
     *     the array is expected to be non-null, which the launcher guarantees
     */
    public static void main(final String[] args) {
        // Assumptions: the array is forwarded exactly as received, neither filtered, reordered nor
        //       supplemented. Spring Boot's own overrides in the --property=value form, and profile
        //       activation among them, travel in this array and nowhere else, so a service that
        //       inspected or rewrote it would silently take away the container's ability to override
        //       configuration at the command layer -- the one channel left once an image is built,
        //       since this file sets no profile and reads no property of its own.
        SpringApplication.run(CardApplication.class, args);
    }
}
