package com.carddemo.transaction.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.servers.ServerVariable;
import io.swagger.v3.oas.models.servers.ServerVariables;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the OpenAPI metadata of transaction-service, and records how the shared kernel's
 * cross-cutting beans reach this context.
 *
 * <p>This class has two halves, and the second is the one a reader does not expect to find beside an
 * API document. The first half is the metadata itself: the title, contract version, summary,
 * description, deployment root, tag set and bearer-token security scheme that this service's
 * generated API description carries. The second half concerns the shared kernel. The component scan
 * this module's application class establishes is rooted at {@code com.carddemo.transaction}, while
 * every shared component lives under {@code com.carddemo.common} and therefore outside that root, so
 * nothing under the shared package is discovered here automatically. The charter in this package and
 * the note at {@code DataSourceConfig} both assign three of those registrations to this class. What
 * this class holds for them is a verification rather than a declaration, and the next paragraph is
 * why.
 *
 * <p>Assumptions: all three of {@code com.carddemo.common.money.MoneyModule},
 * {@code com.carddemo.common.error.GlobalExceptionHandler} and
 * {@code com.carddemo.common.observability.MetricsConfig} already reach this context on their own.
 * That was established by inspection, not assumed, and the inspection is recorded beside the class
 * body below so that a reader looking for three missing bean methods finds the reason they are
 * missing rather than concluding they were forgotten.
 *
 * <p>Trade-offs: the document this class describes is NOT the contract of record. The contract is the
 * hand-authored file at
 * {@code services/transaction-service/src/main/resources/openapi/transaction-api.yaml}, which
 * {@code application.yml} pins the browser view to and which the browser client is written against.
 * This bean supplies metadata for the separate, generated description, and the values below therefore
 * mirror that file rather than replacing it. Keeping two descriptions of one surface in agreement is
 * a real and recurring cost, and it was accepted for a stated reason: a contract that external
 * consumers bind to has to be reviewable as a committed file, line by line in a change, rather than
 * emerging from annotations spread across controllers that do not exist yet. Every value this class
 * mirrors is named as a constant with the contract line it came from, so the agreement surface is one
 * short list instead of a scatter of literals.
 *
 * <p>Assumptions: the generated description is worth giving accurate metadata at all only because
 * {@code application.yml} deliberately leaves that endpoint enabled as the artifact to compare the
 * contract against when an implementation and the contract are suspected of having diverged. Left
 * alone the generated document would carry the library's placeholder title and a placeholder version,
 * so a comparison would be dominated by metadata differences instead of showing the endpoint
 * differences it exists to surface.
 *
 * <p>Assumptions: this class declares no data source, no filter chain and no filter registration. The
 * persistence boundary belongs to {@code DataSourceConfig} and identity and request filtering belong
 * to {@code SecurityConfig}, and this package is closed at those two classes plus this one and the
 * package charter.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    // WHY : Assumptions: the three shared components the charters assign to this class are registered
    //       by the shared kernel itself, so no bean method for any of them appears below. This was
    //       verified before the omission was decided, and these are the artifacts that were read:
    //       (1) services/common-lib/src/main/resources/META-INF/spring/
    //           org.springframework.boot.autoconfigure.AutoConfiguration.imports names
    //           com.carddemo.common.CardDemoCommonAutoConfiguration, which the framework loads from
    //           any module carrying common-lib on its path -- this module cannot compile without it.
    //       (2) That class carries @Import for the metrics configuration at its line 66, contributes
    //           the money codec module at its lines 112 to 123, and contributes the error advice from
    //           a nested servlet-guarded configuration at its lines 188 to 208.
    //       (3) services/common-lib/src/main/resources/META-INF/services/
    //           tools.jackson.databind.JacksonModule names the money module a second and independent
    //           time, through the platform service-provider mechanism the codec library resolves, so
    //           the money handlers also reach a mapper built outside any application context.
    // WHY : Alternatives Considered: declaring all three here anyway, which is what the charter in
    //       this package and the note in DataSourceConfig describe. Rejected on three specific
    //       grounds. First, each shared registration is guarded by a missing-bean condition, so a
    //       local declaration does not collide with the shared one -- it SILENTLY REPLACES it, and
    //       what it replaces includes conditions this class would not restate. The error advice in
    //       particular is guarded on a servlet web application and on three named types; an
    //       unconditional import here would register it wherever this class is loaded and drop that
    //       guard. Second, the shared kernel's own record rejects per-service registration in terms
    //       that apply to this file directly: a registration a service has to remember is one a
    //       service can omit, and eight explicit copies are eight places to get one ordering wrong.
    //       Third, no service module in this repository declares any of the three, and the sibling
    //       SecurityConfig in this very package likewise leaves the shared request filter to the same
    //       mechanism, so declaring them here would make this module the only one of the eight that
    //       registers what the other seven inherit.
    // WHY : Alternatives Considered: a fourth configuration class to hold shared-kernel wiring, named
    //       for the framework it configures. Rejected because the package charter closes this package
    //       at three configuration classes and the same three-class shape recurs across the module
    //       tree, so a fourth class would diverge in a way visible only to someone comparing two
    //       modules. The verification above is prose, not a bean, so it needs no class of its own.
    // WHY : Assumptions: the money module binds its handlers to the com.carddemo.common.money.Money
    //       type specifically. A money-bearing member typed as a bare decimal instead of that type
    //       matches no handler the module installs, so its value is written as a JSON number: the
    //       code compiles, the context starts, the response is produced, and the amount is wrong on
    //       arrival, because most clients parse a JSON number into an IEEE-754 binary value and that
    //       representation cannot hold every cent value exactly. Nothing in a build, a startup log or
    //       a response status reports it. That is why the contract publishes every amount as
    //       type string and why the members of this module's request and response records carry the
    //       money type rather than a bare decimal -- the registration alone is not sufficient, and
    //       relying on it while typing a member loosely reintroduces the same silent defect.
    // WHY : Assumptions: the shared error advice identifies a persistence conflict by matching fully
    //       qualified exception class NAMES as text. The two it matches are
    //       OptimisticLockingFailureException and DataIntegrityViolationException, both in the
    //       org.springframework.dao package, and it matches them as strings rather than referencing
    //       either type because common-lib declares neither JPA nor a JDBC driver and so cannot name
    //       them at compile time. This module declares spring-boot-starter-data-jpa and the
    //       PostgreSQL driver, so both are genuinely raised here and the name match resolves. No
    //       local handler and no configuration change is needed, and adding a duplicate handler here
    //       would take the optimistic-lock and delete-restrict conflicts away from the one advice
    //       that renders them as the shared problem shape this contract publishes.
    // WHY : Alternatives Considered: associating the error advice with SecurityConfig instead, which
    //       is the plausible alternative because that class already owns the one other thing that
    //       decides what a caller receives instead of a result -- a rejected request. Declined,
    //       because the advice and this class describe the SAME artifact from two sides: the problem
    //       shape it renders is com.carddemo.common.error.ApiError, and ApiError is the schema this
    //       contract declares for every failure response, so a change to either half is read against
    //       one committed document. SecurityConfig decides WHETHER a request is admitted, which is a
    //       different question from what the body of a failure looks like on the wire.
    // WHY : Alternatives Considered: putting the observability registration in DataSourceConfig or in
    //       SecurityConfig instead of associating it with this class at all. This is the weakest of
    //       the three associations and is recorded as such: metric tags have no intrinsic relationship
    //       to an API document. DataSourceConfig was rejected because it is deliberately held at the
    //       persistence boundary so that the class owning the pool does not become the module's
    //       general bean registry, which its own note states. SecurityConfig was rejected because its
    //       three responsibilities are the filter chain, the group-to-authority conversion and the
    //       token decoder, and a meter concern shares nothing with any of them. The association
    //       survives only as this record, since the registration itself is the shared kernel's.
    // WHY : Assumptions: the metrics configuration lives under a package named observability and not
    //       under any package named config, because the shared kernel files by concern rather than by
    //       Spring stereotype. Reading it as misplaced is the predictable wrong turn, so it is stated
    //       here, in the directory someone hunting for the class would open first.

    /**
     * The specification version the contract declares, mirrored from {@code transaction-api.yaml}
     * line 123.
     */
    private static final String SPEC_VERSION = "3.1.1";

    /**
     * The document title, mirrored from {@code transaction-api.yaml} line 126.
     */
    private static final String CONTRACT_TITLE = "CardDemo Transaction Service API";

    /**
     * The one-line summary, mirrored from {@code transaction-api.yaml} line 127.
     */
    private static final String CONTRACT_SUMMARY =
            "Transaction list, view and add, and full-balance bill payment for the CardDemo ledger.";

    /**
     * The contract version, mirrored from {@code transaction-api.yaml} line 161.
     */
    private static final String CONTRACT_VERSION = "1.0.0";

    /**
     * The licence name and identifier, mirrored from {@code transaction-api.yaml} lines 162 to 164.
     */
    private static final String LICENSE_IDENTIFIER = "Apache-2.0";

    /**
     * The security scheme key, mirrored from {@code transaction-api.yaml} lines 250 and 816.
     */
    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    /**
     * The server variable carrying the deployment root, mirrored from {@code transaction-api.yaml}
     * lines 199 and 206.
     */
    private static final String BASE_PATH_VARIABLE = "basePath";

    /**
     * Supplies the metadata the generated API description of this service carries.
     *
     * @param contractPath the {@link String} request path the contract of record is served at, read
     *     from {@code springdoc.swagger-ui.url} so that the pointer this description publishes cannot
     *     drift from the document the browser view actually loads; must not be blank
     * @return the {@link OpenAPI} metadata carrying the contract's specification version, information
     *     block, deployment root, tag set, global security requirement and bearer-token scheme, never
     *     {@code null}
     */
    @Bean
    public OpenAPI transactionServiceOpenApi(
            @Value("${springdoc.swagger-ui.url}") String contractPath) {

        // WHY : Assumptions: the property is read without a default on purpose. It is set in this
        //       module's application.yml, and a context that cannot resolve it is one where the
        //       browser view has no contract pinned either, so failing at startup reports the real
        //       condition. Supplying a default here would instead publish a path that resolves to
        //       nothing and read as a broken contract rather than as a missing setting.
        // WHY : Assumptions: no endpoint, host or credential is written into this class. The one
        //       external value it consumes arrives through configuration, and the deployment root
        //       below is a relative template rather than an absolute address.
        return new OpenAPI(SpecVersion.V31)

                // WHY : Assumptions: the enumeration passed to the constructor above selects the 3.1
                //       MODEL, and that selection is load-bearing rather than cosmetic: the
                //       information summary and the licence identifier set below are both members
                //       that exist only from 3.1, so a 3.0 model would silently drop them.
                // WHY : Refactoring Rationale: the version STRING is then set explicitly, because
                //       constructing with that enumeration was measured against this library version
                //       and leaves the string at its 3.0 default -- a bean reporting 3.0 while
                //       carrying 3.1-only members is a contradiction a reader of the bean would have
                //       no way to resolve. Setting it makes the bean report the version the contract
                //       of record declares.
                // WHY : Trade-offs: the document generator re-derives the string it EMITS from the
                //       model's specification version rather than copying this value, and the served
                //       description was measured to carry 3.1.0 where the contract declares 3.1.1.
                //       The agreement this class can enforce is therefore on the 3.1 minor version,
                //       which is what decides whether a consumer's parser accepts the document; the
                //       patch digit belongs to the generator. That was accepted rather than worked
                //       around, because forcing the emitted digit would mean overriding the
                //       generator's own output and giving this class a second, competing say in what
                //       the served description says about itself.
                .openapi(SPEC_VERSION)
                .info(contractInfo(contractPath))
                .servers(List.of(deploymentRootServer()))
                .tags(contractTags())

                // WHY : Assumptions: the requirement is declared once at document level rather than
                //       on each operation, matching the contract, because every operation of this
                //       context requires the same credential and none is reachable without one. A
                //       per-operation repetition would leave room for one operation to be published
                //       as open by omission.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, bearerTokenScheme()));
    }

    /**
     * Builds the information block, mirroring the contract's own and pointing at it.
     *
     * @param contractPath the {@link String} request path the contract of record is served at, named
     *     in the description so a reader of the generated document learns which of the two governs;
     *     must not be blank
     * @return the {@link Info} block carrying the contract's title, summary, description, version and
     *     licence, never {@code null}
     */
    private static Info contractInfo(String contractPath) {

        // WHY : Assumptions: the version below is the version of the CONTRACT and not of the build.
        //       It is deliberately NOT read from this module's Maven coordinate, and that is the
        //       contract's own stated decision rather than an oversight here: a snapshot rebuild that
        //       changes no observable shape must not present itself to a client as a new API, so the
        //       two revise independently. Wiring the artifact version in would couple them and make
        //       every rebuild look like an API change.
        return new Info()
                .title(CONTRACT_TITLE)
                .summary(CONTRACT_SUMMARY)

                // WHY : Assumptions: this text restates the contract's own description rather than
                //       adding to it, and it opens by naming the contract because the two documents
                //       are not equal in authority. The four migrated CICS transactions are named
                //       with the source program each was transcribed from, so the traceability
                //       record stays a lookup rather than a translation, and the transaction names
                //       are the ones the root README.md inventory uses at its lines 298 to 302 --
                //       CT01 is Transaction View there, and calling it a detail view here would
                //       introduce a second name for one screen.
                // WHY : Assumptions: the representation sentences are load-bearing, not decoration.
                //       Amounts are published as strings because a JSON number is parsed into an
                //       IEEE-754 binary value by most clients; identifiers are published as strings
                //       of declared width because the committed seed data is zero-padded and an
                //       integer type would discard leading digits. Both are properties a client has
                //       to know before it writes a parser, which is why they sit in the description
                //       rather than only in the schemas.
                // WHY : Trade-offs: the verification caveat is published rather than omitted. All
                //       four source programs are online CICS programs, which tests/README.md records
                //       at its lines 83 to 85 as impossible to run end to end without a CICS runtime,
                //       so no golden-master oracle covers these paths and parity rests on
                //       transcription fidelity against the cited source lines. Stating that in the
                //       published document costs some polish and buys a consumer who is not misled
                //       about how the behaviour behind it was confirmed.
                .description("""
                        The published HTTP surface of the CardDemo LEDGER bounded context. The \
                        contract of record is the hand-authored document served at %s; where this \
                        generated description and that document disagree, that document governs.

                        It replaces four CICS transactions with stateless request handling: CT00 \
                        Transaction List (app/cbl/COTRN00C.cbl), CT01 Transaction View \
                        (app/cbl/COTRN01C.cbl), CT02 Transaction Add (app/cbl/COTRN02C.cbl) and \
                        CB00 Bill Payment (app/cbl/COBIL00C.cbl).

                        Amounts are exact decimals carried as JSON strings, never as JSON numbers. \
                        Transaction identifiers, card numbers and account identifiers are \
                        digits-only strings of declared width, never integers. Primary account \
                        numbers are masked to their last four digits in every response, and no card \
                        verification value appears anywhere.

                        Browse results are paged by sealed keyset cursor. There is no offset, page \
                        number or total count in this surface, and one operation only -- the \
                        transaction list -- is paged.

                        Verification caveat: all four source programs are online CICS programs, \
                        which tests/README.md lines 83 to 85 record as impossible to run end to end \
                        without a CICS runtime, so no golden-master oracle exists for these paths.\
                        """.formatted(contractPath))
                .version(CONTRACT_VERSION)
                .license(new License().name(LICENSE_IDENTIFIER).identifier(LICENSE_IDENTIFIER));
    }

    /**
     * Builds the single deployment root the contract publishes.
     *
     * @return the {@link Server} whose url is a relative template resolved through one variable,
     *     never {@code null}
     */
    private static Server deploymentRootServer() {

        // WHY : Assumptions: the entry is a RELATIVE root and carries no scheme and no host. The
        //       browser client resolves its own origin from a build variable, so a host written here
        //       would either contradict that variable or commit a deployment endpoint to source
        //       control, which the no-secrets constraint forbids.
        // WHY : Alternatives Considered: a bare "/" url with no variable. Rejected because an edge
        //       stage prefix then has nowhere to go except an edit of the document, whereas a
        //       variable lets the prefix be substituted by whoever publishes the surface.
        // WHY : Assumptions: this contributes a deployment root ONLY. The version segment every
        //       operation path carries is not repeated here, because the gateway route keys and the
        //       load-balancer rules both match absolute versioned paths, and carrying the segment in
        //       both places would present it twice.
        ServerVariable basePath = new ServerVariable()
                ._default("/")
                .description("Stage prefix in front of every operation path. It is \"/\" both when "
                        + "the service is reached directly and when it is reached through the HTTP "
                        + "API, whose route keys already carry the same version segment every "
                        + "operation path spells out, and it is set to a stage or route prefix only "
                        + "where an edge adds one of its own.");

        return new Server()
                .url("{" + BASE_PATH_VARIABLE + "}")
                .description("The deployment root this surface is served beneath. Requests are "
                        + "issued against the origin the client is configured with, and this value "
                        + "contributes only a stage prefix in front of the absolute, already "
                        + "versioned operation paths.")
                .variables(new ServerVariables().addServerVariable(BASE_PATH_VARIABLE, basePath));
    }

    /**
     * Builds the one credential form this surface admits.
     *
     * @return the {@link SecurityScheme} describing the bearer token the managed identity provider
     *     issues and the gateway authorizer validates, never {@code null}
     */
    private static SecurityScheme bearerTokenScheme() {

        // WHY : Refactoring Rationale: one scheme replaces the baseline's sign-on state. The
        //       reference system carried the user identity and the administrator flag in the
        //       DFHCOMMAREA, storage the client echoed back, so a client could in principle assert
        //       its own user type; a signed claim set cannot be asserted by its bearer. Group
        //       membership travels in the token's group claim and is converted to service authorities
        //       by the shared converter in com.carddemo.common.security.
        // WHY : Alternatives Considered: declaring a second scheme, an API key or basic
        //       authentication, alongside this one. Rejected because a second admitted credential
        //       form is a second thing to get wrong at the edge and provides no capability the first
        //       does not already provide.
        // WHY : Assumptions: this DESCRIBES the credential and installs nothing. The filter chain
        //       that actually validates a token belongs to SecurityConfig, and duplicating any part
        //       of it here would create a second place for the accepted token kind to be decided.
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("The bearer token the managed identity provider issues and the gateway "
                        + "authorizer validates. Group membership travels in the token's group claim "
                        + "and is converted to service authorities by JwtRoleConverter in "
                        + "com.carddemo.common.security, which is what replaces the baseline's "
                        + "client-echoed user-type flag.");
    }

    /**
     * Builds the tag definitions the four migrated transactions are grouped under.
     *
     * @return the four {@link Tag} definitions, named from the root {@code README.md} inventory rows,
     *     never {@code null}
     */
    private static List<Tag> contractTags() {

        // WHY : Assumptions: there are exactly four tags because there are exactly four source
        //       programs, and each name is the function name recorded in the root README.md
        //       transaction inventory at its lines 298 to 302 rather than a name invented here.
        //       Using the inventory's own wording keeps the traceability matrix a lookup rather than
        //       a translation, and it is the reason CT01 is Transaction View below.
        // WHY : Alternatives Considered: leaving the tag set to be assembled from annotations on the
        //       controllers. Rejected because the tag DESCRIPTIONS would then be absent from the
        //       generated document while present in the contract, which is exactly the metadata
        //       difference that would obscure a genuine endpoint difference in the comparison the
        //       generated document is kept for.
        return List.of(
                new Tag()
                        .name("Transaction List")
                        .description("Keyset-paged browse over the ledger, migrated from CICS "
                                + "transaction CT00 and app/cbl/COTRN00C.cbl."),
                new Tag()
                        .name("Transaction View")
                        .description("Retrieval of one transaction by identifier, migrated from CICS "
                                + "transaction CT01 and app/cbl/COTRN01C.cbl."),
                new Tag()
                        .name("Transaction Add")
                        .description("Creation of one transaction with a server-assigned identifier, "
                                + "migrated from CICS transaction CT02 and app/cbl/COTRN02C.cbl."),
                new Tag()
                        .name("Bill Payment")
                        .description("Full-balance payment against an account, migrated from CICS "
                                + "transaction CB00 and app/cbl/COBIL00C.cbl."));
    }
}
