package com.carddemo.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Spring Boot entry point of the CardDemo account, customer and card-cross-reference context.
 *
 * <p>This type carries the annotation that assembles the deployable and the process entry point that
 * starts it, and it carries nothing else: no bean method, no runner, no field, no nested type, no
 * property read and no logging statement. Every concern beyond assembly has a named owner inside this
 * module, and each owner is named below, so a reader who arrives here looking for the wiring is sent
 * to the one place that decides it rather than shown a second copy of the decision.</p>
 *
 * <h2>What this deployable replaces</h2>
 *
 * <p>Six programs of the baseline, two of them online CICS transactions. {@code app/cbl/COACTVWC.cbl},
 * 941 lines, is account view, reached as transaction {@code CAVW}: {@code app/csd/CARDDEMO.CSD} L317
 * defines that transaction and L318 names {@code PROGRAM(COACTVWC) TWASIZE(0)}, with no description
 * clause of its own. {@code app/cbl/COACTUPC.cbl}, 4236 lines and the largest online program in the
 * baseline, is account update, reached as transaction {@code CAUP} at L306, described on L307 and bound
 * on L308 to {@code PROGRAM(COACTUPC) TWASIZE(0)}; the two program definitions themselves are at L173
 * and L181, and both transactions declare {@code PRIORITY(1) TRANCLASS(DFHTCL00)} on L311 and L321 and
 * {@code ACTION(BACKOUT) WAIT(YES)} on L313 and L323. Three sequential readers carry no transaction at
 * all: {@code app/cbl/CBACT01C.cbl} at 430 lines reads the account master,
 * {@code app/cbl/CBACT03C.cbl} at 178 lines the card cross-reference and
 * {@code app/cbl/CBCUS01C.cbl} at 178 lines the customer master. The sixth,
 * {@code app/app-vsam-mq/cbl/COACCT01.cbl} at 620 lines, answers account inquiry over a request and
 * reply queue.</p>
 *
 * <p>Refactoring Rationale: the queue-driven inquiry is folded into this context rather than stood up
 * as a ninth deployable of its own. That it is an alternate transport over data this context already
 * owns is not an interpretation but a reading of the program: {@code COACCT01.cbl} L171 includes the
 * same {@code CVACT01Y} account layout this context owns, and its L400 reads a record straight into
 * {@code ACCOUNT-RECORD}. A separate service would therefore have split write ownership of one table
 * across two deployables, which is the failure mode bounded contexts exist to prevent. The consequence
 * is visible in this file only as the absence of a boundary: the inquiry consumer sits under this same
 * scan root, in {@code service/InquiryMessageListener.java}, and needs no registration here.</p>
 *
 * <h2>Why this deployable holds no session state</h2>
 *
 * <p>Refactoring Rationale: the baseline is strictly pseudo-conversational, so a CICS task ends at
 * every screen turn and the whole of its continuity between turns lives in one passed structure. That
 * structure is {@code app/cpy/COCOM01Y.cpy}, whose {@code 01 CARDDEMO-COMMAREA} at L19 spans L19 to
 * L44 and holds five {@code 05} groups at L20, L32, L37, L40 and L42 whose declared widths sum to 34
 * plus 84 plus 12 plus 16 plus 14, exactly 160 bytes. {@code app/cbl/COACTUPC.cbl} L952 to L954 commit
 * the unit of work and L956 to L959 immediately transfer control, the operand on L958 naming that same
 * structure, so the structure travels on the transfer; {@code app/cbl/COACTVWC.cbl} L349 begins the
 * matching transfer. That both transactions declare {@code TWASIZE(0)}, on
 * {@code app/csd/CARDDEMO.CSD} L308 and L318, is what makes this load-bearing rather than incidental:
 * there is no transaction work area at all, so every scrap of continuity lived in that one structure
 * and nowhere else.</p>
 *
 * <p>That structure decomposes here into four separate mechanisms rather than being ported as a unit.
 * Its navigation fields, L21 to L24 with L43 and L44, become client-side router history, and the
 * transfer verb becomes a route change rather than a server-side redirect, because the target has no
 * next-program field at all. Its identity fields become claims on a signed token validated on every
 * request: L25 declares {@code CDEMO-USER-ID PIC X(08)} and L26 {@code CDEMO-USER-TYPE PIC X(01)} with
 * the two condition names on L27 and L28 testing the quoted values {@code 'A'} and {@code 'U'}, and
 * that change is a genuine security gain rather than a change of transport, because the structure was
 * storage the client echoed back and could therefore assert its own user type, whereas a signed group
 * claim cannot be forged. Its selection fields, the customer identifier on L33, the account identifier
 * and status on L38 and L39 and the card number on L41, become path and query parameters, which makes
 * each request self-describing and so independently authorizable. Its re-entry discriminator,
 * {@code CDEMO-PGM-CONTEXT} on L29 with the two condition names on L30 and L31 testing the bare
 * numerics {@code 0} and {@code 1}, disappears outright, because a stateless handler that answers a bad
 * request with a field-error array has no first-entry-versus-re-entry distinction left to make. The
 * consequence is the property the whole deployment model rests on: this service holds no session state,
 * needs no sticky sessions and needs no server-side session store, which is what makes horizontally
 * scaled container tasks behind a load balancer viable at all.</p>
 *
 * <h2>How the shared kernel reaches this context</h2>
 *
 * <p>Alternatives Considered: this annotation implies a component scan rooted at this class's own
 * package and reaching nothing above it, so the three shared-kernel components this context depends on
 * are all outside the scan root: the single error advice in {@code com.carddemo.common.error}, the
 * common-tag meter contribution in {@code com.carddemo.common.observability} and the exact-money codec
 * module in {@code com.carddemo.common.money}. They are not named here, and that is the published
 * mechanism rather than an oversight. They arrive from
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, which the kernel registers in
 * {@code services/common-lib/src/main/resources/META-INF/spring/}
 * {@code org.springframework.boot.autoconfigure.AutoConfiguration.imports} and which contributes the
 * meter configuration by a class-level import at its L78, the money module by its
 * {@code carddemoMoneyModule()} bean at its L227 under the missing-bean guard on its L225, and the
 * error advice by {@code carddemoGlobalExceptionHandler} at its L516 inside the servlet-conditional
 * nested configuration that begins at its L499. Two alternatives were weighed and both were rejected.
 * Naming the three types in an explicit import on this entry point is rejected because the kernel's own
 * charter at its L42 to L49 rejects per-service registration by name, on the ground that a registration
 * a service has to remember is one it can omit and that the symptom of omitting it is invisible, and
 * because a second registration of the advice here would bypass the servlet condition the kernel
 * explains at its L60 to L68, whose whole purpose is that a consumer without a servlet API never
 * resolves that API merely to discover a bean should be skipped. Widening the scan root to cover the
 * kernel package as well is rejected because it is a standing instruction to adopt every present and
 * future kernel component whether this context needs it or not, where the guarded contribution above
 * adopts exactly what the kernel publishes today. Because a silently missing advice shows up only as
 * wrongly shaped error bodies at runtime, all three are asserted by test rather than assumed.</p>
 *
 * <p>Assumptions: the money codec module is the contribution this context can least afford to lose, and
 * the reason is specific to the data it owns. {@code app/cpy/CVACT01Y.cpy} declares five money fields on
 * one record, each {@code PIC S9(10)V99}: {@code ACCT-CURR-BAL} on L7, {@code ACCT-CREDIT-LIMIT} on L8,
 * {@code ACCT-CASH-CREDIT-LIMIT} on L9, {@code ACCT-CURR-CYC-CREDIT} on L13 and
 * {@code ACCT-CURR-CYC-DEBIT} on L14. The module reaches the mapper two independent ways, which is why
 * no wiring appears here: the kernel's guarded bean named above, and the platform service-provider file
 * {@code META-INF/services/tools.jackson.databind.JacksonModule}, which the mapper builder resolves
 * through its find-and-add-modules step. With no handler bound to the money type the mapper treats it as
 * an ordinary bean and, as that provider file's own note records, discovers only its sign predicates and
 * writes them with the amount absent from the payload altogether; nothing in a build, a startup log or a
 * response status reports that, so the defect is silent by construction.</p>
 *
 * <p>Alternatives Considered: a mapper configuration of this module's own, declared beside the other
 * configuration classes in {@code config/}, was weighed against the kernel contribution and rejected.
 * The kernel owns the codec contract for every copybook field in the migration, so a per-service mapper
 * would let two contexts disagree about the wire form of the same field while both compiled and both
 * passed their own tests. It would also buy no safety margin: the kernel's contribution at its L227 is
 * guarded by the missing-bean condition on its L225, which means a local bean would displace it rather
 * than join it, and the module's own registration identifier makes a second registration idempotent
 * rather than additive.</p>
 *
 * <p>Trade-offs: the scan root is left at this one package and is never widened towards
 * {@code com.carddemo}, which would reach the seven sibling service roots {@code .auth}, {@code .card},
 * {@code .transaction}, {@code .reference}, {@code .batch}, {@code .authorization} and
 * {@code .reporting}. What is given up is the convenience of one root covering everything; what it buys
 * is a deployable that cannot couple to another context by accident, and the risk is counted rather
 * than hypothetical, because {@code services/pom.xml} L242 to L250 declares nine modules, one shared
 * kernel and eight bounded contexts, so every one of those seven roots is on this build's own reactor
 * path and would be reachable from the wider root. The boundary is enforced by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java},
 * which is a test and therefore cannot rot, while {@code config/checkstyle/checkstyle.xml} deliberately
 * declares no import-control module, so layering has exactly one owner rather than two that could
 * disagree.</p>
 *
 * <p>Assumptions: the kernel's correlation filter and its claim-to-authority converter are deliberately
 * not registered here either, and for a different reason than the three above. The filter's position is
 * a property of the security filter chain rather than of the entry point, and the kernel places it at
 * {@code CardDemoCommonAutoConfiguration.CORRELATION_FILTER_ORDER}, declared on its L92 as one step
 * after the highest precedence the framework offers; this module's {@code config/SecurityConfig.java}
 * owns the chain that ordering is relative to, together with the resource-server wiring the converter
 * plugs into, and that converter is what turns a group claim into an authority in place of
 * {@code CDEMO-USER-TYPE} at {@code app/cpy/COCOM01Y.cpy} L26 to L28. Registering either from the entry
 * point would put ordering knowledge in a file that holds no chain, which is exactly how a filter ends
 * up running after the authentication failure it was meant to label.</p>
 *
 * <h2>What this file deliberately does not do</h2>
 *
 * <p>Assumptions: no active profile is set here, in any form, and neither is any endpoint, host name,
 * issuer, pool or client identifier, region, queue address or credential. The property shapes for this
 * service live in the sibling-owned {@code application.yml}, with {@code application-dev.yml} and
 * {@code application-prod.yml} beside it overriding values only, and the profile that selects between
 * them arrives from the environment the task runs in. The baseline itself already treats addresses this
 * way: {@code app/app-vsam-mq/cbl/COACCT01.cbl} declares {@code 01 QUEUE-INFO} on L92 and every one of
 * its queue-name fields on L93 to L96 as {@code PIC X(48) VALUE SPACES}, so not one address is a
 * literal even there. Setting any of them in code would make a single image behave differently from its
 * own committed configuration and would defeat the per-environment parameterization the two profile
 * documents exist to provide.</p>
 *
 * <p>Assumptions: the neighbouring concerns are each configured in a named file rather than here, and a
 * batch configuration does not exist at all. {@code config/DataSourceConfig.java} owns the connection
 * pool and pins the
 * schema search path to {@code account}. {@code config/OpenApiConfig.java} owns the OpenAPI 3.1
 * metadata, and the served contract is the sibling-owned
 * {@code services/account-service/src/main/resources/openapi/account-api.yaml}, which the browser client
 * is written against; the surface it declares is account view and update, customer read, and
 * card-cross-reference lookup including the by-account path that replaces the {@code CXACAIX} alternate
 * index; no operation count is stated here, because the contract is that file's to declare and this one
 * would only go stale beside it. {@code config/SqsConfig.java} owns the listener wiring for the inquiry
 * consumer. The management
 * endpoints arrive from {@code spring-boot-starter-actuator}, declared at
 * {@code services/account-service/pom.xml} L298, so {@code GET /actuator/health} is framework-provided
 * and is no declared operation of the contract; it has to stay reachable without a credential because
 * both the load-balancer target-group registration and this module's container health check poll it and
 * neither can present a token, which is why {@code config/SecurityConfig.java} permits that pattern
 * unauthenticated. The fifth is a batch configuration, and this subtree has none: that same POM names
 * the batch starter only inside the note at its L682 to L686 explaining that chunk-oriented jobs and the
 * durable job repository belong to the batch context, so Spring Batch is not on this classpath and a job
 * type here would not compile.</p>
 *
 * <p>Alternatives Considered: no resilience dependency, no circuit breaker and no retry annotation
 * appear here or anywhere in this tree, and the reasoning is recorded once for the whole module in the
 * non-adoption note at {@code services/account-service/pom.xml} L668 to L680 rather than repeated at
 * each site. Spring Framework 7, which arrives inside the Spring Boot parent this module inherits, moved
 * retry into the framework core, so a third-party resilience library would add a dependency for a
 * capability already present. Where retry does become genuinely necessary the
 * framework's own form is {@code @EnableResilientMethods} on a configuration class with a
 * {@code maxRetries} attribute whose total attempt count is one more than its value; the older enabling
 * annotation and the older attribute name are both wrong against this parent, will not activate, and are
 * the easier pair to reach for from memory. The posture prescribed for this context is different again:
 * explicit connect and read timeouts on the outbound lookup in the service layer, plus queue redelivery
 * into a dead-letter queue for the inquiry path. A circuit breaker is omitted because the only
 * synchronous hops are inside the private network behind an internal load balancer with bounded
 * timeouts, so it would add a failure mode without removing one. Adding either annotation to this entry
 * point without a concrete requirement would be a capability nobody asked for, placed where no call is
 * made.</p>
 *
 * <h2>The three owned tables</h2>
 *
 * <p>This context owns the PostgreSQL schema {@code account} and exactly three tables, created by the
 * sibling-owned {@code services/account-service/src/main/resources/db/migration/V1__account.sql}:
 * {@code account.accounts} at its L183 from {@code app/cpy/CVACT01Y.cpy}, whose declared widths sum to
 * the 300-byte record its L2 names; {@code account.customers} at its L423 from
 * {@code app/cpy/CVCUS01Y.cpy}, summing to 500; and {@code account.card_xref} at its L641 from
 * {@code app/cpy/CVACT03Y.cpy}, summing to 50. The index {@code idx_card_xref_account_id} at its L726
 * gives the {@code CXACAIX} alternate index a real secondary access path. The schema, the roles and the
 * grants the three tables sit inside belong to {@code data-migration/sql/V0__schemas_and_roles.sql},
 * which this module reads as a precondition and never creates, so the migration issues no schema
 * statement of its own.</p>
 *
 * <p>Assumptions: on the account record, a declared width of {@code PIC X(10)} does not by itself mean a
 * date, and reading it as one is the easiest silent data loss available in this context. Exactly three of
 * those fields hold a {@code 'YYYY-MM-DD'} value: {@code ACCT-OPEN-DATE} on
 * {@code app/cpy/CVACT01Y.cpy} L10, {@code ACCT-EXPIRAION-DATE} on its L11 and
 * {@code ACCT-REISSUE-DATE} on its L12, the second keeping the declared spelling of the baseline while
 * the column it maps to is spelled correctly and the rename recorded. Two further fields share that same
 * width and are not dates at all: {@code ACCT-ADDR-ZIP} on its L15 and {@code ACCT-GROUP-ID} on its L16
 * both stay character columns, because narrowing a postal code or a group identifier to a date would
 * discard values the baseline accepts and would do so without any error to read.</p>
 *
 * <p>Assumptions: three vocabulary rules hold across everything beneath this root, and each is stated
 * because a reader who assumes the ordinary framework idiom will write the wrong thing. Money is exact
 * decimal at every hop, carried as a scale-2 decimal in Java, as {@code NUMERIC(12,2)} in the schema and
 * as a JSON string on the wire, and never as IEEE-754 binary floating point, which the layering test
 * asserts; the same hazard is documented from the other end at {@code tests/README.md} L273 to L274,
 * which records that the compiler's default sign convention misreads the zoned-decimal sign overpunch
 * and silently corrupts negative balances. Paging is by key alone, through the kernel's page envelope
 * carrying a first key, a last key and a has-next flag, reading one row beyond the page to discover
 * whether another follows; a positional window is never used, because under concurrent inserts it skips
 * and repeats rows, which browse-by-key does not. Concurrency is optimistic and expresses a rule the
 * baseline already has rather than adding one: {@code app/cbl/COACTUPC.cbl} snapshots the whole pre-edit
 * record from its L669 through L756, with the new image beginning at its L757, and its condition name on
 * L521 carries the message its L522 declares, so a lost update answers with HTTP 409 and that same text.
 * The version columns sit on {@code accounts} and {@code customers} only.</p>
 *
 * <p>Assumptions: this context carries no transactional outbox, and the absence is a finding rather than
 * an omission. In {@code app/app-vsam-mq/cbl/COACCT01.cbl} the read is prepared with the syncpoint option
 * on L347 and the reply is prepared with the syncpoint option on L475, and the file names no
 * no-syncpoint option anywhere, so the read, the business logic and the reply are one unit of work with
 * no window in which a committed decision can lose its reply. The inquiry consumer therefore needs only
 * a visibility timeout and delete-on-success. The sibling authorization context reaches the opposite
 * conclusion from the opposite evidence and does carry an outbox, which is why this is written down: a
 * reader who assumes uniformity across the eight contexts will get exactly one of the two backwards.</p>
 *
 * <h2>How this context is verified</h2>
 *
 * <p>Assumptions: this context has no executable baseline oracle, and nothing here claims one. Its two
 * online programs cannot be run end to end without a CICS runtime, which {@code tests/README.md} records
 * at L83 to L85 is absent from the runner, and the business rules that same document asserts verbatim
 * from its L553 onward name only the interest program, the posting program and the transaction-category
 * balance, none of which belongs to this context. Behaviour is therefore verified against the programs
 * and copybooks read as a specification rather than against captured output, and the module's own tests
 * under {@code services/account-service/src/test} are additive to the COBOL parity oracle suite rooted
 * at {@code tests/} rather than a replacement for it. What that does not weaken is text fidelity: every
 * user-visible message is directly checkable against its originating program and is asserted character
 * for character. The audit that governs this file is pass or fail with no tolerated warning level,
 * because the documentation gate is bound to Maven's {@code validate} phase at {@code services/pom.xml}
 * L850 with {@code failOnViolation} true at its L939 and {@code violationSeverity} at warning on its
 * L940, so a warning and a failure are one outcome here. The graded condition-code rubric of the COBOL
 * suite, under which code 4 counts as a passing result, describes no build in this tree, and reading a
 * build here through it would treat a real violation as acceptable.</p>
 *
 * @see org.springframework.boot.SpringApplication
 */
@SpringBootApplication
public final class AccountApplication {

    /**
     * Withholds construction of the entry point from application code.
     *
     * <p>Refactoring Rationale: the type is declared final with a private constructor because nothing
     * constructs it and nothing extends it. {@code SpringApplication.run(Class, String[])} registers
     * the argument as a primary configuration source and reflects over its annotations, and this class
     * declares no bean method at all -- measured on this file, zero declared fields, exactly one
     * declared constructor and exactly one declared method -- so the inter-bean proxying machinery has
     * no reference to intercept and the framework never needs to subclass it or hold an instance built
     * by application code. The shared kernel already relies on the same property one level down, where
     * {@code MetricsConfig} is declared final at its L234 under
     * {@code @Configuration(proxyBeanMethods = false)} on its L233. Making it structural here is what
     * stops a later contributor turning the entry point into a holder of services or state, which is
     * the drift the layering rules exist to catch, and it is cheaper to prevent than to unpick once
     * something depends on it.</p>
     *
     * <p>Assumptions: the shape above is safe only while this class stays a primary source that
     * declares no bean method, and the failure mode if that stops holding is abrupt rather than
     * gradual. A configuration class that must be proxied is subclassed, and a final class cannot be,
     * so the same type reached by component scanning rather than as a primary source would abort
     * assembly with a {@code BeanDefinitionStoreException} reporting that it could not enhance the
     * configuration class; what keeps the two from colliding is that the primary-source registration of
     * this very package pre-empts the scan of it by bean name. Adding a bean method here would remove
     * that protection as well as breaching this file's closed set of responsibilities.</p>
     */
    private AccountApplication() {
        // Assumptions: deliberately empty, and empty is the whole contract. This type declares zero
        //   fields -- a count, not an impression -- and the framework builds the context from the
        //   annotation on it rather than from anything an instance holds, so there is no state to
        //   establish here; a body that did initialise something would be state the annotation cannot
        //   see, reachable only through an instance that nothing in this deployable ever asks for.
    }

    /**
     * Starts the account bounded context as a Spring Boot application.
     *
     * <p>The call is left to propagate. A failure to assemble the context surfaces as an unchecked
     * exception that terminates the process with a non-zero exit code, which is the signal the
     * container runtime and the orchestrator both read; catching it here would convert a task that
     * visibly failed to start into one that appears to have started and serves nothing. That is also
     * why the call is not wrapped in a try block, quite apart from the documentation gate being unable
     * to check a throw sited inside one.</p>
     *
     * @param args the process command-line arguments as supplied by the launcher, forwarded unmodified
     *     to {@link SpringApplication#run(Class, String[])}; each entry is a {@link String}, and the
     *     array is expected to be non-null, which the launcher guarantees
     */
    public static void main(final String[] args) {
        // Assumptions: the array is forwarded exactly as received, neither filtered, reordered nor
        //   supplemented. Spring Boot's own overrides in the --property=value form, and profile
        //   activation with them, travel in this array and nowhere else, and this module's image really
        //   does hand arguments through: its Dockerfile declares the entrypoint script on its L246 and
        //   the note on its L231 records that the script takes no argument of its own and forwards
        //   "$@". A service that inspected or rewrote the array would therefore silently take away the
        //   container's ability to override configuration at the command layer, which is the one
        //   channel left once an image is built, since this file sets no profile and reads no property
        //   of its own.
        SpringApplication.run(AccountApplication.class, args);
    }
}
