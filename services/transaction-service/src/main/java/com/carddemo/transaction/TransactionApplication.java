package com.carddemo.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Spring Boot entry point of the CardDemo transaction ledger bounded context.
 *
 * <p>Purpose: this type carries the annotation that assembles the deployable and the process entry
 * point that starts it, and it carries nothing else -- no bean method, no runner, no field, no nested
 * type, no property read and no logging statement. It is also the component-scan root for the whole of
 * {@code com.carddemo.transaction}, so the seven subpackages beneath it are discovered only because
 * this class sits at exactly that package rather than one level above or below it. Every concern past
 * assembly has a named owner inside this module, and each owner is named below so that a reader who
 * arrives here looking for the wiring is sent to the one place that decides it instead of being shown
 * a second copy of the decision.</p>
 *
 * <h2>What this deployable replaces</h2>
 *
 * <p>Four online CICS transactions, quoted by identifier and by the official screen name the
 * online-components table of the repository root {@code README.md} assigns them, so that the names
 * used here and the names an operator already knows are the same names:</p>
 *
 * <ul>
 *   <li>{@code CT00}, <b>Transaction List</b> at {@code README.md:298}, migrated from
 *       {@code app/cbl/COTRN00C.cbl} (699 lines) -- the paged browse</li>
 *   <li>{@code CT01}, <b>Transaction View</b> at {@code README.md:299}, migrated from
 *       {@code app/cbl/COTRN01C.cbl} (330 lines) -- the keyed single-record read</li>
 *   <li>{@code CT02}, <b>Transaction Add</b> at {@code README.md:300}, migrated from
 *       {@code app/cbl/COTRN02C.cbl} (783 lines) -- the capture screen</li>
 *   <li>{@code CB00}, <b>Bill Payment</b> at {@code README.md:302}, migrated from
 *       {@code app/cbl/COBIL00C.cbl} (572 lines) -- the balance-affecting payment</li>
 * </ul>
 *
 * <p>Assumptions: the official name of {@code CT01} is <b>Transaction View</b> and not "Transaction
 * Detail". The intuitive wording reads naturally beside a detail endpoint and appears nowhere in that
 * table, so adopting it would break the correspondence with the inventory that assigns the name.</p>
 *
 * <p>Assumptions: two neighbouring rows of that same table are deliberately not part of this
 * deployable, and both absences are recorded because each one is easy to assume into it. {@code CR00},
 * <b>Transaction Reports</b> at {@code README.md:301}, sits between {@code CT02} and {@code CB00} in
 * the table and therefore reads as a fifth transaction of this context; it belongs to
 * reporting-service, which owns no table in this schema and reaches these four through read-only
 * cross-schema views. {@code app/cbl/CBTRN02C.cbl} (731 lines), the nightly posting program, writes
 * three of the four tables this context owns and is nonetheless the batch context's program; it is
 * read here for the schema contract alone. The two modules agree through the physical schema and never
 * through code, so neither imports a type from the other.</p>
 *
 * <h2>Why this deployable holds no session state</h2>
 *
 * <p>Refactoring Rationale: the baseline is strictly pseudo-conversational, so a CICS task ends at
 * every screen turn and the whole of its continuity between turns lives in one passed structure.
 * That structure is {@code CARDDEMO-COMMAREA} at {@code app/cpy/COCOM01Y.cpy:19} through {@code :44},
 * shared by every online program, and it does not travel here. It decomposes four ways, and the fourth
 * is the one that disappears rather than moving:</p>
 *
 * <ul>
 *   <li>Navigation -- the from and to transaction and program fields at {@code :21} through
 *       {@code :24}, with the last map and mapset at {@code :43} and {@code :44} -- becomes
 *       client-side routing. No server-side next-program field exists at all.</li>
 *   <li>Identity -- {@code CDEMO-USER-ID PIC X(08)} at {@code :25} and
 *       {@code CDEMO-USER-TYPE PIC X(01)} at {@code :26}, with condition names testing {@code 'A'} at
 *       {@code :27} and {@code 'U'} at {@code :28} -- becomes claims on a token verified on every
 *       request.</li>
 *   <li>Selection context -- {@code CDEMO-CUST-ID PIC 9(09)} at {@code :33},
 *       {@code CDEMO-ACCT-ID PIC 9(11)} at {@code :38} and {@code CDEMO-CARD-NUM PIC 9(16)} at
 *       {@code :41} -- becomes path and query values, which is what makes each request
 *       self-describing and therefore independently authorizable.</li>
 *   <li>The re-entry discriminator {@code CDEMO-PGM-CONTEXT PIC 9(01)} at {@code :29}, with its enter
 *       and re-enter condition names at {@code :30} and {@code :31}, disappears entirely. A stateless
 *       handler that answers a rejected request with a per-field error array has no
 *       first-entry-versus-re-entry distinction left to draw, which is also why field-level error
 *       presentation here is driven by the response body and never by a remembered turn count.</li>
 * </ul>
 *
 * <p>Assumptions: moving identity onto a verified token changes the security posture rather than only
 * the transport. The passed structure is storage the client hands back on the following turn, so a
 * client could in principle assert its own one-character user type; a signed group claim cannot be
 * asserted by the client at all. The baseline trusts the returned structure, the Java trusts the
 * signature, and the divergence is documented.</p>
 *
 * <p>Trade-offs: holding nothing between requests -- no sticky session and no server-side session
 * store -- lets any task serve any request, which is what makes horizontally scaled container tasks
 * behind a load balancer viable at all. The cost accepted is that every request carries its own
 * selection context and is authorized from scratch, which is more work per request than reading a
 * field a previous turn had already populated.</p>
 *
 * <h2>How the shared kernel reaches this context</h2>
 *
 * <p>Alternatives Considered: the annotation below carries no {@code scanBasePackages} and no
 * {@code @Import}, and both omissions are decisions rather than defaults. The annotation implies a
 * component scan rooted at this class's own package, so four shared-kernel participants this module
 * genuinely needs active sit outside that root: the exact-money codec module in
 * {@code com.carddemo.common.money}, the correlation filter in {@code com.carddemo.common.web}, the
 * single error advice in {@code com.carddemo.common.error} and the common-tag meter contribution in
 * {@code com.carddemo.common.observability}, which lives under that concern's package and under no
 * package named {@code config}. None of them is named here because the kernel registers itself:
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration} is declared {@code @AutoConfiguration}
 * at its line 77 and is the sole entry in
 * {@code services/common-lib/src/main/resources/META-INF/spring/}
 * {@code org.springframework.boot.autoconfigure.AutoConfiguration.imports}. It contributes the meter
 * configuration by a class-level import at its line 78, the money module by its
 * {@code carddemoMoneyModule()} bean at its line 227 under the missing-bean guard at its line 225, the
 * correlation filter registration at its line 466 inside the servlet-conditional nested configuration
 * that begins at its line 439, and the error advice at its line 516 inside the second such nested
 * configuration that begins at its line 499. Any registration written here would therefore be a second
 * statement of a fact the kernel already states, and two statements of one fact can disagree while
 * both look authoritative.</p>
 *
 * <p>Alternatives Considered: the first rejected mechanism was widening the scan root to
 * {@code com.carddemo}. That root also covers the seven sibling service packages {@code .auth},
 * {@code .account}, {@code .card}, {@code .reference}, {@code .batch}, {@code .authorization} and
 * {@code .reporting}, every one of which {@code services/pom.xml} lines 242 to 250 declares as a
 * module of this same reactor. The classpath risk is bounded to zero here by a specific mechanism and
 * not by reassurance: this module declares a Maven dependency on {@code common-lib} alone, so no
 * sibling service class is ever resolvable on its classpath. Widening the root would make that bound a
 * property of the dependency list rather than of the annotation, and it would additionally be a
 * standing instruction to adopt every kernel component whether this context needs it or not, where the
 * guarded contributions above adopt exactly what the kernel publishes.</p>
 *
 * <p>Alternatives Considered: the second rejected mechanism was an explicit {@code @Import} of those
 * four types. It is narrower than a widened scan, which is its attraction, and that narrowness is the
 * defect: it would not adopt a kernel contribution added without a matching edit here, so the kernel
 * and its consumers would drift apart while both went on compiling. It would also import the error
 * advice unconditionally, bypassing the servlet condition at line 498 of that auto-configuration whose
 * stated purpose is that a consumer without the servlet API never resolves that API merely to discover
 * a bean should be skipped, and a locally declared bean would displace the kernel's guarded one rather
 * than join it. Registering the money module by hand is rejected for an additional reason of its own:
 * it is already discovered twice, once through that guarded bean and once through the platform
 * provider file {@code META-INF/services/tools.jackson.databind.JacksonModule}, so a third
 * registration would add a duplicate that buys nothing.</p>
 *
 * <h2>Four starters are re-declared, and omitting one fails at run time</h2>
 *
 * <p>Assumptions: {@code services/common-lib/pom.xml} marks {@code spring-boot-starter-web} optional
 * at its line 225, {@code spring-boot-starter-validation} at its line 230,
 * {@code spring-boot-starter-security} at its line 235 and
 * {@code spring-boot-starter-oauth2-resource-server} at its line 240, and Maven does not propagate an
 * optional dependency to a consumer, so not one of the four arrives transitively from the kernel. The
 * kernel does that deliberately, because a plain compile-scope declaration there would place a servlet
 * container and a full security filter chain on all eight consuming modules including those with no
 * web tier. {@code services/transaction-service/pom.xml} therefore re-declares all four at its lines
 * 171 to 186.</p>
 *
 * <p>Trade-offs: the asymmetry that makes those four declarations load-bearing is recorded in that
 * POM's own note at its lines 158 to 162 and is repeated on the class the container launches because
 * this is where a reader looks when a task will not start. Omitting any one of the four does not break
 * the build. The module compiles clean and then raises {@code NoClassDefFoundError} when the context is
 * assembled or on the first request that reaches the missing tier. A defect a green build cannot see is
 * the reason all four are listed explicitly instead of being left to inheritance, and the accepted cost
 * is that each consumer restates what it actually uses.</p>
 *
 * <p>Assumptions: a fifth Boot starter in that POM carries the same asymmetry for an unrelated reason,
 * and it is named here so the count above is not read as the whole story. Boot 4 split the HTTP clients
 * out of the web starter, so {@code RestClient} compiles from spring-web while the autoconfiguration
 * that publishes the {@code RestClient.Builder} bean the outbound adapter in {@code service} injects
 * does not arrive with it. {@code spring-boot-starter-restclient} at that POM's lines 222 to 225
 * supplies it, and its own note records that the gap was found by starting the built archive rather
 * than by any test, because no test in this module assembles the whole context.</p>
 *
 * <h2>Port 8080 and the health path are load-bearing in two places at once</h2>
 *
 * <p>Assumptions: {@code server.port} is 8080 at {@code application.yml:147} and the image publishes
 * that same number at {@code Dockerfile:167}; one contract with two writers. The path
 * {@code /actuator/health} is polled by the container health check at {@code Dockerfile:215} through
 * {@code :218} and, independently, by the load-balancer target group that registers this task, whose
 * probe path is defaulted to that same string at {@code infra/modules/alb/variables.tf:651}.
 * Changing either the number or the path breaks container orchestration and load-balancer registration
 * at the same time, and the two failures look alike from outside: a task failing its health check is
 * killed and replaced, and an unregistered target serves no traffic while appearing to run. The
 * endpoint answers without a credential because neither poller can present a token, and it is exposed
 * through {@code management.endpoints.web.exposure.include} at {@code application.yml:741} with
 * component details withheld at {@code :755}, so an unauthenticated probe learns liveness and nothing
 * else.</p>
 *
 * <p>Assumptions: that connector is encrypted -- {@code server.ssl} is enabled at
 * {@code application.yml:169} -- and the base profile declares a single connector, so 8080 carries the
 * business surface and the health group together over one transport. The probe is consequently an
 * {@code https} request, which is what the health command at {@code Dockerfile:216} through
 * {@code :218} names; a plaintext probe would fail against every healthy task, and a check that always
 * fails is worse than none because it makes a working deployment look broken.</p>
 *
 * <h2>This class is the main class of the executable archive</h2>
 *
 * <p>Assumptions: {@code services/transaction-service/pom.xml} activates
 * {@code spring-boot-maven-plugin} at its lines 537 to 540 and declares no {@code mainClass} element,
 * so the plugin resolves the main class by scanning for an annotated class that declares a
 * {@code main} method, finds this one, and produces the single self-contained archive the runtime stage
 * copies at {@code Dockerfile:159} through {@code :161}. A second annotated class declaring
 * {@code main} anywhere in this module would make that resolution ambiguous and force the main class to
 * be pinned in the POM as a third place for the two to disagree, which is one concrete reason this
 * file's member set is closed.</p>
 *
 * <p>Alternatives Considered: the same plugin is deliberately absent from
 * {@code services/common-lib/pom.xml}, whose non-adoption note at its lines 610 to 620 states why, and
 * the asymmetry between the two POMs is a decision rather than an inconsistency to be tidied away. The
 * {@code repackage} goal moves application classes from the archive root down to
 * {@code BOOT-INF/classes} and dependency jars to {@code BOOT-INF/lib}. That layout is loadable by the
 * framework's own launcher but is not a usable classpath entry for a compiler, so a repackaged kernel
 * would resolve successfully for all eight consuming modules and then present none of its types. A
 * library consumed by other modules stays a plain jar; only a deployable repackages, and this module is
 * the deployable.</p>
 *
 * <h2>The ledger schema, and why Hibernate does not own its shape</h2>
 *
 * <p>This context owns the PostgreSQL schema {@code ledger} and exactly four tables, created by the
 * sibling-owned {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql}:
 * {@code ledger.transactions} at its line 117, {@code ledger.daily_transactions} at its line 339,
 * {@code ledger.transaction_rejects} at its line 579 and
 * {@code ledger.transaction_category_balances} at its line 801. Flyway applies that migration when this
 * context starts, reading {@code classpath:db/migration} as declared at {@code application.yml:434}
 * with the schema pinned at {@code :443} and {@code :444} and schema creation withheld at
 * {@code :481}, because the schema itself and the roles inside it are created once by
 * {@code data-migration/sql/V0__schemas_and_roles.sql} and are a precondition this module reads rather
 * than a thing it makes.</p>
 *
 * <p>Assumptions: {@code spring.jpa.hibernate.ddl-auto} is {@code none} at
 * {@code application.yml:569}, and the reason is ownership rather than caution. The batch context
 * writes three of these four tables under narrowly scoped cross-schema grants -- line 1093 of that
 * bootstrap script grants its role usage on this schema, and its lines 1115 and 1116 grant that role
 * select, insert and update on the tables in it -- so two deployables read one physical schema as their
 * contract. A Hibernate-managed schema would let this deployable alter a column that the other's next
 * run still expects in its previous shape, and the disagreement would surface in the nightly chain
 * rather than in either module's own tests. One migration owner, one shape, and a schema statement
 * issued from exactly one place.</p>
 *
 * <p>Assumptions: the search path is pinned to {@code ledger} on every pooled connection by
 * {@code spring.datasource.hikari.connection-init-sql} at {@code application.yml:351}, read from
 * configuration rather than written into a Java constant, so one built image runs in every environment
 * without a rebuild. Every endpoint, credential, issuer and base URL this process needs arrives the
 * same way, as an environment-supplied property; none is set in this file, and no profile is activated
 * from code.</p>
 *
 * <h2>Money is exact fixed point at every hop</h2>
 *
 * <p>Assumptions: transformation rule T3 of the migration plan governs every amount this context
 * carries, and this is the context where it matters most, because each amount here is a posted value
 * rather than a rate or a limit. An amount is {@code NUMERIC} with scale 2 in the column, a scale-2
 * decimal reduced half-up in Java, and a JSON <b>string</b> on the wire. The string is not
 * fastidiousness: most clients parse a JSON number into an IEEE-754 binary floating-point value, which
 * destroys exactness at the boundary a user actually reads. Binary floating-point types are barred from
 * the money path, and the prohibition is asserted by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java},
 * which runs against this module's own compiled classes and so cannot decay into a convention.</p>
 *
 * <h2>What this file deliberately does not do</h2>
 *
 * <p>Assumptions: the member set above is closed, and each neighbouring concern is configured in a
 * named file instead. {@code config/SecurityConfig} owns the filter chain and the claim-to-authority
 * wiring; {@code config/DataSourceConfig} owns the connection pool; {@code config/OpenApiConfig} owns
 * the published contract's metadata; {@code config/InternalIdentityConfig} mints the service token the
 * account context requires on this module's two cross-context reads. Business rules live in
 * {@code service} and data access in {@code repository}, and the outbound HTTP client for the
 * cross-context hop is built in {@code service} rather than configured here. That division is the
 * layering contract the package charter states and the layering rules enforce, not a filing preference:
 * a bean declared on this class would sit above every layer and therefore belong to none of them, and
 * it would also break the single-main-class property recorded above.</p>
 *
 * <p>Alternatives Considered: no resilience dependency, no circuit breaker and no retry annotation
 * appears on this class. Spring Framework 7, which arrives inside the Spring Boot parent this module
 * inherits, moved retry into the framework core, so a third-party resilience library would add a
 * dependency for a capability already present; where retry does become necessary the framework's own
 * form is {@code @EnableResilientMethods} on a configuration class with a {@code maxRetries} attribute
 * whose total attempt count is one more than its value, and the older enabling annotation and the older
 * attribute name are both inert against this parent and are the easier pair to reach for from memory.
 * The posture this context actually uses is explicit connect and read timeouts on the outbound lookup
 * in {@code service}. A circuit breaker is omitted because the only synchronous hop leaving this
 * process stays inside the private network behind an internal load balancer with bounded timeouts, so a
 * breaker would add a failure mode without removing one.</p>
 *
 * <h2>Parity evidence, and the limit of it</h2>
 *
 * <p>Assumptions: all four migrated programs are online programs, and {@code tests/README.md} records
 * at its lines 83 to 85 that the online programs cannot be run end to end without a CICS runtime, which
 * the runner does not have, so only their extractable field-validation logic is unit-tested. No
 * golden-master oracle exists for this module and none is claimed. Parity rests on two other things:
 * validation logic transcribed from the COBOL paragraphs, and the copybook record contracts the entity
 * layer is single-sourced from. The posting program that shares this schema does have golden-master
 * coverage and belongs to the batch context, so its coverage must not be read as coverage of these four
 * screens, which is the mistake the shared schema invites.</p>
 *
 * <p>Assumptions: this module's build is binary. The documentation gate bound to Maven's
 * {@code validate} phase, the compiler and the test runners each pass or fail, so a warning and a
 * failure are one outcome here. The graded condition-code rubric under which the repository's COBOL
 * suite treats a warning-level result as its green state belongs to that suite alone and describes no
 * build in this tree; reading a build here through it would treat a real violation as acceptable.</p>
 *
 * @see org.springframework.boot.SpringApplication
 */
@SpringBootApplication
public class TransactionApplication {

    /**
     * Starts the transaction ledger bounded context as a Spring Boot application.
     *
     * <p>Assumptions: the call is left to propagate. A failure to assemble the context surfaces as an
     * unchecked exception that terminates the process with a non-zero exit status, which is the signal
     * both the container runtime and the orchestrator read; catching it here would turn a task that
     * visibly failed to start into one that appears to have started and serves nothing. No exception
     * at-clause is declared because {@link SpringApplication#run(Class, String[])} declares no checked
     * exception and this method declares no {@code throws} clause of its own.</p>
     *
     * @param args the process command-line arguments as supplied by the launcher, each entry a
     *     {@link String}, forwarded unmodified to {@link SpringApplication#run(Class, String[])},
     *     where the framework parses them into the environment as its highest-precedence property
     *     source; the array is expected to be non-null, which the launcher guarantees
     */
    public static void main(String[] args) {
        // WHY : Assumptions: the array is forwarded exactly as received, neither filtered, reordered
        //       nor supplemented. Spring Boot's own overrides in the --property=value form, and
        //       profile activation among them, travel in this array and nowhere else, and this
        //       module's image really does hand arguments through: Dockerfile:242 declares its entry
        //       point in exec form. A method that inspected or rewrote the array would silently take
        //       away the container's ability to override configuration at the command layer, which is
        //       the one channel left once an image is built, because this file activates no profile
        //       and reads no property of its own.
        SpringApplication.run(TransactionApplication.class, args);
    }
}
