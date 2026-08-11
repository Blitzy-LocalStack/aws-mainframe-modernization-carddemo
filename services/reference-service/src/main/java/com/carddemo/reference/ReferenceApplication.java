//=============================================================================
// services/reference-service/src/main/java/com/carddemo/reference/
//     ReferenceApplication.java
//-----------------------------------------------------------------------------
// Purpose:
//   The Spring Boot entry point of the reference-data bounded context. This
//   type carries the annotation that assembles the deployable and the process
//   entry point that starts it, and it carries nothing else: no bean method,
//   no field, no nested type, no property read and no logging statement. Its
//   position at the root of com.carddemo.reference is the load-bearing part,
//   because that position, and not any attribute written on the annotation, is
//   what fixes the component-scan root for this module.
//
// WHY (non-obvious design decisions):
//
// WHY : Assumptions: the four rationale labels used throughout this file are
//       the plural, un-parenthesised forms of the Explainability rule's lines
//       31 to 34 -- Alternatives Considered, Refactoring Rationale,
//       Assumptions and Trade-offs. The XML, YAML and HCL files of this
//       migration write the same four in the singular. The two spellings name
//       one set of categories, this file uses the plural form only, and the
//       equivalence is recorded here once so that no reader mistakes the
//       difference for a second taxonomy.
// WHY : Refactoring Rationale: this class replaces the CICS region as the
//       process host for this context's transactions. Three of the four
//       relevant definitions are this context's:
//       app/app-transaction-type-db2/csd/CRDDEMOD.csd L25 defines
//       TRANSACTION(CTLI) and its L26 binds PROGRAM(COTRTLIC) TWASIZE(0), its
//       L35 defines TRANSACTION(CTTU) and its L36 binds PROGRAM(COTRTUPC)
//       TWASIZE(0), and app/app-vsam-mq/csd/CRDDEMOM.csd L27 defines
//       TRANSACTION(CDRD) with its L28 binding PROGRAM(CODATE01) TWASIZE(0).
//       The fourth is NOT this context's: CRDDEMOM L17 defines
//       TRANSACTION(CDRA) and its L18 binds PROGRAM(COACCT01), which is
//       account-service's inquiry consumer. Reading two definitions that
//       share one file as two definitions of one context is the easiest
//       boundary error available here, which is why the exclusion is written
//       down rather than left to inference.
// WHY : Assumptions: statelessness is the baseline's own contract rather than
//       an invention of this migration. app/app-vsam-mq/cbl/CODATE01.cbl L2
//       declares PROGRAM-ID. CODATE01 IS INITIAL., which gives that program
//       fresh WORKING-STORAGE on every invocation. All four transactions named
//       above declare TWASIZE(0), at CRDDEMOD L26 and L36 and CRDDEMOM L18
//       and L28, so there was no transaction work area at all and every scrap
//       of continuity lived in the app/cpy/COCOM01Y.cpy commarea instead. That
//       structure is not carried forward, so this module holds no server-side
//       session state, needs no sticky sessions and needs no session store,
//       which is what makes horizontally scaled tasks behind a load balancer
//       viable.
// WHY : Alternatives Considered: the three shared-kernel components this
//       module depends on are deliberately NOT named in an @Import on this
//       type. They arrive from
//       com.carddemo.common.CardDemoCommonAutoConfiguration, and naming any
//       of them again here would displace a guarded bean rather than join it.
//       The class Javadoc below records the evidence and both rejected
//       alternatives in full.
// WHY : Assumptions: this class reads no configuration of any kind. Every
//       property shape for this service lives in the sibling-owned
//       application.yml with its -dev and -prod overlays, and no endpoint,
//       credential or queue address appears as a literal anywhere in this
//       module.
// WHY : Assumptions: everything beneath app/ is the behavioural oracle of this
//       migration. It is read, cited by path and physical line, and never
//       modified. Where the migrated behaviour departs from it deliberately
//       the departure is registered in
//       docs/architecture/cobol-to-service-traceability.md, which is
//       maintained elsewhere and referenced from here rather than authored
//       here.
//=============================================================================
package com.carddemo.reference;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Spring Boot entry point of the CardDemo reference-data and lookup context.
 *
 * <p>This type carries the annotation that assembles the deployable and the process entry point that
 * starts it, and it carries nothing else. Every concern beyond assembly has a named owner inside this
 * module, and each owner is named below, so that a reader who arrives here looking for the wiring is
 * sent to the one place that decides it rather than shown a second copy of the decision.</p>
 *
 * <h2>What this deployable replaces</h2>
 *
 * <p>Five programs of the baseline, three of them reached as CICS transactions. Two are the
 * transaction-type screens: {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} is the inquiry list,
 * reached as transaction {@code CTLI}, which {@code app/app-transaction-type-db2/csd/CRDDEMOD.csd} L25
 * defines and whose L26 binds to {@code PROGRAM(COTRTLIC) TWASIZE(0)}; and
 * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} is the maintenance screen, reached as
 * transaction {@code CTTU}, defined at that file's L35 and bound at its L36 to
 * {@code PROGRAM(COTRTUPC) TWASIZE(0)}. The third is {@code app/app-vsam-mq/cbl/CODATE01.cbl}, the
 * queue-driven date conversion, reached as transaction {@code CDRD}, which
 * {@code app/app-vsam-mq/csd/CRDDEMOM.csd} L27 defines and whose L28 binds to
 * {@code PROGRAM(CODATE01) TWASIZE(0)}. The remaining two carry no transaction:
 * {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} is the batch reference updater and
 * {@code app/cbl/CSUTLDTC.cbl} is the date-edit utility. This context additionally owns the seeded
 * United States lookup data that address validation in other contexts reads.</p>
 *
 * <p>Assumptions: {@code CRDDEMOM.csd} defines two transactions and only one of them is this
 * context's. Its L17 defines {@code TRANSACTION(CDRA)} and its L18 binds {@code PROGRAM(COACCT01)},
 * which is the account inquiry consumer and belongs to {@code account-service}. The exclusion is
 * stated rather than left silent because the two definitions sit twenty lines apart in one file, so a
 * reader who takes that file as one context's resource set will pull an account-owned program into
 * this scan root and split write ownership of a table that already has an owner.</p>
 *
 * <p>Refactoring Rationale: this context does not appear in the baseline's own decomposition, and it
 * was added rather than discovered. The reference and lookup data has readers in three other
 * contexts, so leaving it inside any one of them would have given a shared table an owner that also
 * had unrelated reasons to change. The transaction-type screens were folded in here for the
 * complementary reason rather than standing up as a service of their own: they are a screen over data
 * this context already owns, and a separate deployable would have split that ownership in two.</p>
 *
 * <h2>Why this deployable holds no session state</h2>
 *
 * <p>Assumptions: statelessness here restates a contract the baseline already had rather than
 * imposing a new one. {@code app/app-vsam-mq/cbl/CODATE01.cbl} L2 declares
 * {@code PROGRAM-ID. CODATE01 IS INITIAL.}, and that clause is what guarantees the program a fresh
 * {@code WORKING-STORAGE} on every invocation, so the baseline's own date-conversion path kept nothing
 * between calls. What makes this load-bearing rather than incidental is that all four transactions
 * named above declare {@code TWASIZE(0)} -- {@code CRDDEMOD.csd} L26 and L36, {@code CRDDEMOM.csd}
 * L18 and L28 -- so there was no transaction work area anywhere in this context and every scrap of
 * continuity lived in the {@code app/cpy/COCOM01Y.cpy} commarea and nowhere else.</p>
 *
 * <p>That commarea is not carried forward. Its navigation fields become client-side router history,
 * its identity fields become claims on a signed token that the client cannot assert for itself, its
 * selection fields become path and query parameters that make each request independently
 * authorizable, and its re-entry discriminator disappears outright, because a stateless handler that
 * answers a bad request with a field-error array has no first-entry-versus-re-entry distinction left
 * to make. The consequence is the property the whole deployment model rests on: this service holds no
 * server-side session state, so it needs neither sticky sessions nor a session store, and that is
 * what makes horizontally scaled container tasks behind a load balancer viable at all.</p>
 *
 * <h2>How the shared kernel reaches this context</h2>
 *
 * <p>Alternatives Considered: the annotation on this type implies a component scan rooted at this
 * class's own package and reaching nothing above it, so the three shared-kernel components this
 * context depends on all sit outside the scan root: the single error advice in
 * {@code com.carddemo.common.error}, the common-tag meter contribution in
 * {@code com.carddemo.common.observability} and the exact-money codec module in
 * {@code com.carddemo.common.money}. None of the three is named on this type, and that absence is the
 * published mechanism rather than an oversight. They arrive from
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, which the kernel registers as the sole
 * content line of {@code services/common-lib/src/main/resources/META-INF/spring/}
 * {@code org.springframework.boot.autoconfigure.AutoConfiguration.imports}, and which contributes the
 * meter configuration by a class-level import at its L78, the money module by its
 * {@code carddemoMoneyModule()} bean at its L227 under the missing-bean guard on its L225, and the
 * error advice by {@code carddemoGlobalExceptionHandler(Clock)} at its L516 under the missing-bean
 * guard on its L515, inside the servlet-conditional nested configuration that opens at its L499.</p>
 *
 * <p>Two alternatives were weighed and both were rejected. Naming the three types in an explicit
 * import on this entry point is rejected on two independent grounds. The kernel's own charter at its
 * L42 to L49 rejects per-service registration by name, on the reasoning that a registration a service
 * has to remember is one it can omit and that the symptom of omitting it is invisible -- the service
 * starts, serves requests and simply emits none of the three. And because each kernel bean is guarded
 * by {@code @ConditionalOnMissingBean}, a registration written here would displace the kernel's bean
 * rather than join it, which for the error advice means bypassing the servlet condition the kernel
 * explains at its L60 to L68, whose whole purpose is that a consumer without a servlet API never
 * resolves that API merely to discover a bean should be skipped. Widening the scan root to cover the
 * kernel package as well is rejected because it is a standing instruction to adopt every present and
 * future kernel component whether this context needs it or not, where the guarded contribution above
 * adopts exactly what the kernel publishes today. Because a silently missing contribution shows up
 * only as wrongly shaped responses at runtime, all three are asserted by test rather than assumed.</p>
 *
 * <p>Assumptions: the error advice is the mechanism by which this context's one referential refusal
 * becomes an HTTP 409 rather than a 500, and the mapping is inherited whole rather than restated here.
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L6 declares
 * {@code FOREIGN KEY TRC_TYPE_CODE (TRC_TYPE_CODE)} and its L7 completes it with
 * {@code REFERENCES CARDDEMO.TRANSACTION_TYPE (TR_TYPE) ON DELETE RESTRICT}. In Db2 that refusal
 * surfaced as {@code SQLCODE} -532, which the baseline handles explicitly:
 * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} L1624 opens
 * {@code 9800-DELETE-PROCESSING.}, its L1628 issues the delete and its L1638 tests
 * {@code WHEN SQLCODE = -532} to build the message its L1641 begins. The migrated constraint is
 * carried at {@code src/main/resources/db/migration/V1__reference.sql} L268, where PostgreSQL raises
 * {@code SQLSTATE} 23503, which the framework translates to a data-integrity violation and the shared
 * advice renders as 409. No type in this module declares a second
 * {@code @RestControllerAdvice}: a duplicate would give one refusal two renderings, shadowing the
 * shared error shape and splitting the contract that {@code ui/src/screens/refTypeList} reads to
 * render a blocked delete.</p>
 *
 * <p>Assumptions: the money codec module is the contribution this context can least afford to lose,
 * and the reason is specific to the one field it governs here rather than generic. This context's
 * {@code dto/DisclosureGroupRateResponse} declares its {@code interestRate} component as the shared
 * {@code com.carddemo.common.money.Money} type, the column behind it is
 * {@code interest_rate NUMERIC(6,2) NOT NULL} at
 * {@code src/main/resources/db/migration/V1__reference.sql} L337, and that column is transcribed from
 * {@code app/cpy/CVTRA02Y.cpy} L9, which declares {@code DIS-INT-RATE PIC S9(04)V99} inside the
 * fifty-byte disclosure-group record its L2 names. The rate is not merely displayed: it is an operand.
 * {@code app/cbl/CBACT04C.cbl} L462 opens {@code 1300-COMPUTE-INTEREST.} and its L464 to L465 compute
 * {@code WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. The module binds its serialiser to
 * the {@code Money} type and writes the value as a JSON string, which is why
 * {@code src/main/resources/openapi/reference-api.yaml} declares the field as an exact decimal string
 * and quotes its examples as {@code '15.00'} and {@code '25.00'}; those two match the seed rows of
 * {@code app/data/ASCII/discgrp.txt}, whose rate field holds the digits {@code 00150} and
 * {@code 00250} followed by the positive-zero sign overpunch character, because that file stores the
 * rate as zoned decimal. Emitting the rate as a JSON number instead would let a client
 * parse it into an IEEE-754 double, and that loss of exactness would then propagate into every
 * generated interest transaction rather than staying at the boundary where it happened.</p>
 *
 * <h2>What this file deliberately does not do</h2>
 *
 * <p>Assumptions: the kernel's correlation filter and its claim-to-authority converter are not
 * registered here either, and for a different reason than the three above. A filter's position is a
 * property of the security filter chain rather than of the entry point, and the kernel fixes that
 * position at {@code CardDemoCommonAutoConfiguration.CORRELATION_FILTER_ORDER} on its L92; this
 * module's {@code config/SecurityConfig.java} owns the chain that ordering is relative to, together
 * with the resource-server wiring the converter plugs into. Registering either from this type would
 * put ordering knowledge in a file that holds no chain, which is exactly how a filter ends up running
 * after the authentication failure it was meant to label.</p>
 *
 * <p>Assumptions: this type reads no configuration and sets no active profile, and neither does it
 * name any endpoint, host, issuer, pool identifier, region, queue address or credential. Those all
 * live in the sibling-owned {@code src/main/resources/application.yml}, with
 * {@code application-dev.yml} and {@code application-prod.yml} beside it overriding values only and
 * the selecting profile arriving from the environment the task runs in. That file pins the schema
 * search path at its L578, the migration locations and schema at its L680 and L690 with schema
 * creation left off at its L716, the token issuer at its L848 as an environment reference rather than
 * a value, the served contract path at its L942, the actuator base path and exposure at its L994 and
 * L1013, the listen port at its L176 and the connection-pool ceiling at its L489. Its queue settings
 * carry a five-second receive wait, which preserves {@code app/app-vsam-mq/cbl/CODATE01.cbl} L286,
 * where {@code MOVE 5000 TO MQGMO-WAITINTERVAL} sets the same interval in milliseconds. Setting any
 * of these in code would make one image behave differently from its own committed configuration and
 * would defeat the per-environment parameterization those overlays exist to provide.</p>
 *
 * <p>Assumptions: the neighbouring concerns are each configured in a named file rather than here, and
 * a batch configuration does not exist at all. {@code config/DataSourceConfig.java} owns the
 * connection pool and the {@code reference} search path, {@code config/OpenApiConfig.java} owns the
 * OpenAPI 3.1 metadata for the hand-authored {@code src/main/resources/openapi/reference-api.yaml},
 * and {@code config/SqsConfig.java} owns the listener wiring for the date-conversion consumer. The
 * management endpoints arrive from the actuator starter, so {@code GET /actuator/health} is
 * framework-provided rather than a declared operation of the contract, and it has to stay reachable
 * without a credential because both the load-balancer target-group registration and this module's
 * container health check poll it and neither can present a token. The fifth is a batch configuration,
 * and this subtree has none: {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} is migrated as the
 * ordinary service method {@code service/ReferenceBatchUpdateService} rather than as a Spring Batch
 * job, this module's {@code pom.xml} explains at its L423 that the batch starter is absent by design,
 * and {@code application.yml} carries no {@code spring.batch} key at all, so a job type here would
 * not compile.</p>
 *
 * <p>Alternatives Considered: no resilience dependency, no circuit breaker and no retry annotation
 * appears here. Spring Framework 7, which arrives inside the Spring Boot parent this module inherits,
 * moved retry into the framework core, so a third-party resilience library would add a dependency for
 * a capability already present. Where retry does become genuinely necessary the framework's own form
 * is {@code @EnableResilientMethods} on a configuration class together with a {@code maxRetries}
 * attribute whose total attempt count is one more than its value; the older enabling annotation and
 * the older attribute name are both wrong against this parent, will not activate, and are the easier
 * pair to reach for from memory. A circuit breaker is omitted because the only synchronous hops are
 * inside the private network behind an internal load balancer with bounded timeouts, so it would add
 * a failure mode without removing one. Adding either to this entry point without a concrete
 * requirement would place a capability where no call is made.</p>
 *
 * <p>Trade-offs: the scan root is left at this one package and is never widened towards
 * {@code com.carddemo}, which would reach the seven sibling service roots {@code .auth},
 * {@code .account}, {@code .card}, {@code .transaction}, {@code .batch}, {@code .authorization} and
 * {@code .reporting}. What is given up is the convenience of one root covering everything; what it
 * buys is a deployable that cannot couple to another context by accident. The risk is counted rather
 * than hypothetical, because every one of those seven roots is on this build's own reactor path and
 * would therefore be reachable from the wider root. The boundary is enforced by the layering test in
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/}, which is a test and so
 * cannot rot, while {@code config/checkstyle/checkstyle.xml} deliberately declares no import-control
 * module, so layering has exactly one owner rather than two that could disagree.</p>
 *
 * @see org.springframework.boot.SpringApplication
 */
@SpringBootApplication
public final class ReferenceApplication {

    /**
     * Prevents instantiation of this entry-point type.
     *
     * <p>Assumptions: private rather than the implicit public default, because nothing constructs
     * this type. {@code SpringApplication.run(Class, String[])} takes the class itself and reads the
     * annotation from it, and the framework never needs an instance, so any wider constructor would
     * advertise a use that does not exist. Declaring it explicitly is also what keeps the type from
     * acquiring the implicit public no-argument constructor a reader could otherwise call.</p>
     */
    private ReferenceApplication() {
        // Assumptions: deliberately empty, and it can be nothing else. This type holds no field to
        //       initialise and no invariant to establish, and the constructor exists only to withhold
        //       the implicit public one, so any statement placed here would run for no caller.
    }

    /**
     * Starts the reference-data service by building and refreshing its Spring application context.
     *
     * <p>Assumptions: this method delegates and does nothing else. It sets no profile, reads no
     * property and logs nothing, because the configuration that governs startup is the sibling-owned
     * {@code application.yml} with its two profile overlays, and the selecting profile arrives from
     * the environment the container task runs in rather than from this call.</p>
     *
     * @param args the process command-line arguments as received from the launcher, forwarded
     *     unmodified to {@link SpringApplication#run(Class, String[])}; each entry is a
     *     {@link String}, and passing the array through unread is what lets an operator override any
     *     property, or select a profile, on the command line of this service exactly as on every
     *     other service of this repository. An empty array is the normal case in the container image,
     *     whose entry point supplies configuration through the environment instead
     */
    public static void main(final String[] args) {
        SpringApplication.run(ReferenceApplication.class, args);
    }
}
