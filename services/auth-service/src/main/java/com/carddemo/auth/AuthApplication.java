package com.carddemo.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Spring Boot entry point of the CardDemo auth bounded context.
 *
 * <p>This type carries the annotation that assembles the deployable and the process entry point
 * that starts it, and it carries nothing else: no bean method, no runner, no field, no property
 * read, no logging statement and no nested type. Every concern beyond assembly has a named owner
 * inside this module, and each owner is named below, so a reader who arrives here looking for the
 * wiring is sent to the one place that decides it rather than shown a second copy of the
 * decision.</p>
 *
 * <h2>What this deployable replaces</h2>
 *
 * <p>Five online CICS programs, each its own transaction over the one indexed security file.
 * {@code app/cbl/COSGN00C.cbl}, 260 lines, is sign-on, reached as transaction {@code CC00}:
 * {@code app/csd/CARDDEMO.CSD} L378 defines that transaction and L379 names
 * {@code PROGRAM(COSGN00C)}. {@code app/cbl/COUSR00C.cbl} at 695 lines,
 * {@code app/cbl/COUSR01C.cbl} at 299, {@code app/cbl/COUSR02C.cbl} at 414 and
 * {@code app/cbl/COUSR03C.cbl} at 359 are the user list, create, update and delete screens,
 * reached as the {@code CU00} through {@code CU03} family at {@code CARDDEMO.CSD} L449 with L450,
 * L459 with L460, L469 with L470 and L479 with L480. That the four user-maintenance screens form a
 * transaction family of their own, separate from every other transaction in the region, is the
 * clearest grounding in the baseline for treating the whole user-administration surface as
 * administrative only. The admin menu is a different transaction over a different program and is no
 * part of this context: {@code CA00} to {@code COADM01C} at {@code CARDDEMO.CSD} L327 with L328,
 * and {@code app/csd/CARDDEMO.CSD} L189 defines that program. Sign-on merely transfers to it, at
 * the transfer pair in {@code app/cbl/COSGN00C.cbl} L230 to L240, where L232 names
 * {@code 'COADM01C'} and L237 names {@code 'COMEN01C'}.</p>
 *
 * <p>Refactoring Rationale: the baseline is strictly pseudo-conversational, so the whole of its
 * continuity between screen turns lives in one passed structure, and this deployable carries none
 * of it. {@code app/cbl/COSGN00C.cbl} L64 to L67 declare the linkage record as
 * {@code 05 LK-COMMAREA PIC X(01) OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN}, L80 detects first
 * entry with {@code IF EIBCALEN = 0}, and L98 to L102 end every turn by handing the structure back
 * to the terminal, with the operand naming it on L100. The structure itself is
 * {@code app/cpy/COCOM01Y.cpy}, whose {@code 01 CARDDEMO-COMMAREA} at L19 holds five {@code 05}
 * groups at L20, L32, L37, L40 and L42 whose declared widths sum to 34 plus 84 plus 12 plus 16 plus
 * 14, exactly 160 bytes, and {@code app/cbl/COSGN00C.cbl} L224 to L228 are the five moves that
 * populate it, L226 carrying the user identifier and L228 clearing the re-entry discriminator. That
 * one structure decomposes here into four separate mechanisms rather than being ported as a unit.
 * Its navigation fields become client-side router history. Its identity fields become claims on a
 * signed token validated on every request, which is a genuine security gain and not merely a change
 * of transport, because the structure was storage the client echoed back and could therefore assert
 * its own user type, whereas a signed group claim cannot be forged. Its selection fields become
 * path and query parameters, which makes each request self-describing and so independently
 * authorizable. Its re-entry discriminator, {@code CDEMO-PGM-CONTEXT} with the two condition names
 * at {@code app/cpy/COCOM01Y.cpy} L29 to L31, disappears outright, because a stateless handler that
 * answers a bad request with a field-error array has no first-entry-versus-re-entry distinction
 * left to make. The consequence is the property the whole deployment model rests on: this service
 * holds no session state, needs no sticky sessions and needs no server-side session store, which is
 * what makes horizontally scaled container tasks behind a load balancer viable at all.</p>
 *
 * <p>Refactoring Rationale: one behaviour is deliberately not carried across, and it is the only
 * declined parity in this context. {@code app/cpy/CSUSR01Y.cpy} L21 declares
 * {@code 05 SEC-USR-PWD PIC X(08)}, an eight-character cleartext password at zero-based offset 48
 * of the 80-byte security record, and {@code app/cbl/COSGN00C.cbl} L223 compares it directly
 * against what was typed with {@code IF SEC-USR-PWD = WS-USER-PWD}. The baseline stores and
 * compares a cleartext credential; the Java encodes credential handling in a managed identity pool
 * and keeps only a subject reference on the row, so the field reaches no column of
 * {@code auth.users}, no transfer object, no constant and no comparison anywhere in this module.
 * The divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md} rather than absorbed silently. Choosing
 * the pool over a hashed local column is what removes the whole class of stored-credential defects
 * instead of narrowing one instance of it.</p>
 *
 * <h2>How the shared kernel reaches this context</h2>
 *
 * <p>Alternatives Considered: the annotation below carries no {@code scanBasePackages} and no
 * {@code @Import}, and both omissions are decisions rather than defaults.
 * {@code @SpringBootApplication} implies a component scan rooted at the annotated class's own
 * package, so it reaches {@code com.carddemo.auth} and nothing else, which leaves the shared
 * kernel's Spring-managed components outside the root. Two ways of pulling them in were evaluated
 * and both were rejected in favour of the mechanism the kernel itself publishes. The first was a
 * surgical import of {@code com.carddemo.common.error.GlobalExceptionHandler} and
 * {@code com.carddemo.common.observability.MetricsConfig}, rejected because the kernel already
 * registers both: its resource
 * {@code services/common-lib/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * names {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, whose L73 imports the metrics
 * configuration unconditionally and whose nested {@code ServletErrorConfiguration} supplies the
 * error advice at L326 behind a missing-bean condition, itself guarded at L303 to L308 by a
 * class-presence condition naming the servlet request, the web advice annotation and the
 * access-denial type, and by a servlet web-application condition. An import here would register the
 * advice with none of those guards while simultaneously satisfying the missing-bean condition that
 * currently supplies it, so it would trade a guarded registration for an unguarded one; the advice
 * also has no no-argument constructor, since
 * {@code services/common-lib/src/main/java/com/carddemo/common/error/GlobalExceptionHandler.java}
 * L582 takes a clock, so the import would depend on a bean only that same auto-configuration
 * contributes. The second was widening {@code scanBasePackages} to name this package and the
 * kernel package {@code com.carddemo.common} together, rejected because it is a standing
 * instruction to adopt every present and future kernel component whether this context needs it or
 * not, and because the kernel's own charter at
 * {@code CardDemoCommonAutoConfiguration.java} L37 to L44 rejects per-service registration by name,
 * on the ground that a registration a service has to remember is one it can omit and that the
 * symptom of omitting it is invisible. Declaring neither is therefore not an oversight: it is the
 * published mechanism, and it is asserted by test rather than assumed, because a silently missing
 * advice shows up only as wrongly shaped error bodies at runtime.</p>
 *
 * <p>Trade-offs: the scan root is left at this one package and is never widened towards
 * {@code com.carddemo}, which would reach the seven sibling service roots {@code .account},
 * {@code .card}, {@code .transaction}, {@code .reference}, {@code .batch}, {@code .authorization}
 * and {@code .reporting}. What is given up is the convenience of one root covering everything; what
 * it buys is a deployable that cannot couple to another context by accident, and the risk is
 * counted rather than hypothetical: {@code services/pom.xml} L233 to L241 declares nine modules,
 * one shared kernel and eight bounded contexts, so every one of those seven roots is on this
 * build's own reactor path and would be reachable from the wider root. The
 * boundary is enforced by the layering rules at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java},
 * which is a test and therefore cannot rot; the documentation gate in
 * {@code config/checkstyle/checkstyle.xml} deliberately declares no import-control module, so
 * layering has exactly one owner rather than two that could disagree.</p>
 *
 * <p>Assumptions: the kernel's correlation filter and its claim-to-authority converter are
 * deliberately not registered here. The filter's position is a property of the security filter
 * chain, not of the entry point: the auto-configuration places it at
 * {@code CardDemoCommonAutoConfiguration.CORRELATION_FILTER_ORDER}, one step after the highest
 * precedence the framework offers, and this module's {@code config/SecurityConfig.java} owns the
 * chain that ordering is relative to, together with the resource-server wiring the converter plugs
 * into. Registering either from the entry point would put ordering knowledge in a file that holds
 * no chain, which is exactly how a filter ends up running after the authentication failure it was
 * meant to label.</p>
 *
 * <h2>What this file deliberately does not do</h2>
 *
 * <p>Assumptions: no active profile is set here, in any form. The property shapes for this service
 * live in the sibling-owned {@code application.yml}, which pulls in the kernel defaults at its L191
 * with L192, and {@code application-dev.yml} and {@code application-prod.yml} beside it override
 * values only; the profile that selects between them arrives from the environment the task runs in.
 * Setting one in code would make a single image behave differently from its own committed
 * configuration and would defeat the per-environment parameterization the two profile documents
 * exist to provide. The absence is measured rather than asserted: across this module's main sources
 * the count of {@code setAdditionalProfiles}, {@code spring.profiles.active} and
 * {@code System.setProperty} occurrences is zero, zero and zero.</p>
 *
 * <p>Assumptions: the management endpoints are configured nowhere in this file. They arrive from
 * {@code spring-boot-starter-actuator}, declared at {@code services/auth-service/pom.xml} L208, and
 * their exposure is pinned in {@code application.yml}, whose management block admits health, info,
 * metrics and prometheus. {@code GET /actuator/health} is therefore framework-provided, and it is
 * deliberately absent from the contract of record: the runtime surface of this service is the eight
 * declared operations plus those four management paths, not nine or twelve declared operations.
 * Health has to stay reachable without a credential because both the load-balancer target-group
 * registration and this module's container health check poll it and neither can present a token,
 * which is why {@code config/SecurityConfig.java} permits its pattern unauthenticated while ending
 * the chain in a blanket refusal. Moving, renaming or crediting that path would break target-group
 * registration and container liveness in one change, and it is not this file's path to move.</p>
 *
 * <p>Alternatives Considered: no resilience dependency, no circuit breaker and no retry annotation
 * appear here or anywhere in this tree. Spring Framework 7, which arrives inside the Spring Boot
 * parent this module inherits, moved retry into the framework core, so a third-party resilience
 * library would add a dependency for a capability already present. Where retry does become
 * genuinely necessary the framework's own form is an enabling annotation on a configuration class
 * with a {@code maxRetries} attribute whose total attempt count is one more than its value; the
 * older enabling annotation and the older attribute name are both wrong against this parent and are
 * easy to reach for from memory. The posture prescribed for this context is different again:
 * explicit connect and read timeouts on the identity-pool call in the service layer, because a
 * retry against a credential check multiplies pool-side lockout risk rather than reducing latency.
 * Adding either annotation to this entry point without a concrete requirement would therefore be a
 * capability nobody asked for, placed where no call is made.</p>
 *
 * <h2>The one owned table</h2>
 *
 * <p>Exactly one table, {@code auth.users}, created by the single statement of the sibling-owned
 * {@code services/auth-service/src/main/resources/db/migration/V1__auth.sql} beginning at its L35,
 * with exactly five columns: {@code user_id CHAR(8) PRIMARY KEY} at L39,
 * {@code first_name VARCHAR(20) NOT NULL} at L44, {@code last_name VARCHAR(20) NOT NULL} at L45,
 * {@code user_type CHAR(1) NOT NULL CHECK (user_type IN ('A','U'))} at L49 and
 * {@code cognito_sub UUID NOT NULL UNIQUE} at L54. The eight-character key width is the declared
 * width of {@code 05 SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy} L18 and is never
 * widened, and the two twenty-character name columns are the declared widths of L19 and L20 of that
 * same copybook. There is no password column, no version column and no created, updated or audit
 * timestamp column, so this context declares no optimistic-lock field and consumes no timestamp
 * helper from the shared kernel. Paging over the table is by key alone, ten rows to a screen after
 * {@code 02 USER-REC OCCURS 10 TIMES} at {@code app/cbl/COUSR00C.cbl} L57, with one extra row read
 * to discover whether another screen follows. The schema, the roles and the grants the table sits
 * inside belong to {@code data-migration/sql/V0__schemas_and_roles.sql}, which this module reads as
 * a precondition and never creates; the {@code auth} schema is assumed to pre-exist.</p>
 *
 * <h2>How this context is verified</h2>
 *
 * <p>Assumptions: this context has no executable baseline oracle, and nothing here claims one. All
 * five programs it replaces are online CICS programs, and {@code tests/README.md} records at L43 to
 * L46, and again at L83 to L85, that such programs cannot be run end to end without a CICS runtime,
 * which is absent from the runner. Behaviour is therefore verified against the programs and
 * copybooks read as a specification rather than against captured output. What that does not weaken
 * is text fidelity: every user-visible message is directly checkable against its originating
 * program, for instance the duplicate-key sentence {@code User ID already exist...} at
 * {@code app/cbl/COUSR01C.cbl} L263, and each is asserted character for character. The audit that
 * governs this file is pass or fail with no tolerated warning level, because the documentation gate
 * is bound to Maven's {@code validate} phase at {@code services/pom.xml} L841 with
 * {@code failOnViolation} true at L930 and {@code violationSeverity} at warning at L931, so a
 * warning and a failure are one outcome. The graded condition-code rubric of the COBOL suite, under
 * which code 4 counts as a passing result, describes no build in this tree, and reading a build
 * here through it would treat a real violation as acceptable.</p>
 *
 * @see org.springframework.boot.SpringApplication
 */
@SpringBootApplication
public final class AuthApplication {

    /**
     * Withholds construction of the entry point from application code.
     *
     * <p>Refactoring Rationale: the type is declared final with a private constructor because
     * nothing constructs it and nothing extends it. {@code SpringApplication.run(Class, String[])}
     * registers the argument as a primary configuration source and reflects over its annotations,
     * and this class declares no bean method at all, so the {@code proxyBeanMethods} machinery has
     * no inter-bean reference to intercept and the framework never needs to subclass it or hold an
     * instance built by application code; a context started from exactly this shape was observed to
     * come up with the entry-point bean's runtime type equal to this declared class rather than to a
     * generated subclass. Making that structural is what stops a later contributor turning the entry
     * point into a holder of services or state, which is the drift the layering rules exist to
     * catch, and it is cheaper to prevent here than to unpick once something depends on it.</p>
     *
     * <p>Assumptions: the shape above is safe only while this class stays a primary source that
     * declares no bean method, and the failure mode if that stops holding is specific rather than
     * gradual. The same final class registered by component scanning instead of as a primary source
     * was observed to abort assembly with a {@code BeanDefinitionStoreException} reading
     * {@code Could not enhance configuration class}, because a scanned configuration class is
     * subclassed and a final class cannot be. What keeps the two from colliding is that the
     * primary-source registration of this very package pre-empts the scan of it by bean name.
     * Adding a bean method here would remove that protection as well as breaching this file's
     * closed set of responsibilities.</p>
     */
    private AuthApplication() {
        // Assumptions: deliberately empty, and empty is the whole contract. This type declares zero
        //   fields, and the framework builds the context from the annotation on it rather than from
        //   anything an instance holds, so there is no state to establish here; a body that did
        //   initialise something would be state the annotation cannot see, reachable only through
        //   an instance that nothing in this deployable ever asks for.
    }

    /**
     * Starts the auth bounded context as a Spring Boot application.
     *
     * <p>The call is left to propagate. A failure to assemble the context surfaces as an unchecked
     * exception that terminates the process with a non-zero exit code, which is the signal the
     * container runtime and the orchestrator both read; catching it here would convert a task that
     * visibly failed to start into one that appears to have started and serves nothing.</p>
     *
     * @param args the process command-line arguments as supplied by the launcher, forwarded
     *     unmodified to {@link SpringApplication#run(Class, String[])}; each entry is a
     *     {@link String}, and the array is expected to be non-null, which the launcher guarantees
     */
    public static void main(final String[] args) {
        // Assumptions: the array is forwarded exactly as received, neither filtered, reordered nor
        //   supplemented. Spring Boot's own overrides in the --property=value form, and profile
        //   activation with them, travel in this array and nowhere else, and this module's image
        //   really does hand arguments through: its Dockerfile declares the entrypoint script at
        //   L248 and that script forwards "$@" rather than consuming it, as its own note at L233
        //   records. A service that inspected or rewrote the array would therefore silently take
        //   away the container's ability to override configuration at the command layer -- the one
        //   channel left once an image is built, since this file sets no profile and reads no
        //   property of its own.
        SpringApplication.run(AuthApplication.class, args);
    }
}
