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
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the OpenAPI metadata of transaction-service, and records how the shared kernel's
 * cross-cutting beans reach this context.
 *
 * <p>The first half of this class is the metadata itself: the title, contract version, summary,
 * description, deployment root, tag set and bearer-token security scheme the generated API description
 * carries. The second half is a VERIFICATION rather than a declaration, and it is here because the
 * component scan this module's application class establishes is rooted at
 * {@code com.carddemo.transaction} while every shared component lives under
 * {@code com.carddemo.common}, so nothing under the shared package is discovered here automatically. A
 * reader looking for three missing bean methods finds the reason they are missing beside the class body
 * rather than concluding they were forgotten.
 *
 * <p>Assumptions: {@code com.carddemo.common.money.MoneyModule},
 * {@code com.carddemo.common.error.GlobalExceptionHandler} and
 * {@code com.carddemo.common.observability.MetricsConfig} all reach this context on their own, through
 * the shared kernel's auto-configuration. That was established by inspection rather than assumed, and
 * this class declares none of the three.
 *
 * <p>Trade-offs: the document this class describes is NOT the contract of record. The contract is the
 * hand-authored file at {@code src/main/resources/openapi/transaction-api.yaml}, which
 * {@code application.yml} pins the browser view to and which the browser client is written against.
 * This bean supplies metadata for the separate, generated description, so the values below MIRROR that
 * file rather than replacing it. Keeping two descriptions of one surface in agreement is a real and
 * recurring cost, accepted for a stated reason: a contract that external consumers bind to has to be
 * reviewable as a committed file, line by line in a change, rather than assembled at run time from
 * annotations spread across controllers. Every mirrored value is named as a constant with the contract
 * line it came from, so the agreement surface is one short list instead of a scatter of literals.
 *
 * <p>Assumptions: the generated description is worth accurate metadata only because
 * {@code application.yml} deliberately leaves that endpoint enabled as the artifact to compare the
 * contract against when an implementation and the contract are suspected of having diverged. Left alone
 * it would carry the library's placeholder title and version, so a comparison would be dominated by
 * metadata differences instead of the endpoint differences it exists to surface.
 *
 * <p>Assumptions: this class declares no data source, no filter chain and no filter registration. The
 * persistence boundary belongs to {@code DataSourceConfig} and identity and request filtering to
 * {@code SecurityConfig}, and this package is closed at those two classes plus this one and the package
 * charter.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    // Alternatives Considered: declaring the three shared registrations here anyway. Rejected on
    //   three grounds. Each shared registration is guarded by a missing-bean condition, so a local
    //   declaration does not collide with the shared one -- it SILENTLY REPLACES it, and what it
    //   replaces includes conditions this class would not restate: the error advice is guarded on a
    //   servlet web application and on three named types, so an unconditional import here would
    //   register it wherever this class is loaded and drop that guard. A registration a service has to
    //   remember is also one a service can omit, and eight explicit copies are eight places to get one
    //   ordering wrong. And no service module in this repository declares any of the three, so
    //   declaring them here would make this module the only one of the eight that registers what the
    //   other seven inherit.

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
     * The configuration key the contract path is read from, named so a refusal can cite it.
     *
     * <p>Refactoring Rationale: the key is named in a constant rather than written twice. It appears in
     * the {@code @Value} expression below and in the message of every refusal, and a key quoted in a
     * diagnostic that no longer matches the key being read sends an operator to the wrong line.</p>
     */
    private static final String CONTRACT_PATH_PROPERTY = "springdoc.swagger-ui.url";

    /**
     * The exact accepted shape of the contract path: origin-relative, one segment, ending in a YAML suffix.
     *
     * <p>Assumptions: the path must be ORIGIN-RELATIVE, so it resolves against whichever host served the
     * page. An absolute URL would pin the contract to one host name and break the moment the service is
     * reached through a different one -- the load balancer, the gateway, or a port-forward -- and it would
     * also let a value in configuration point a reader's browser at a document served by somewhere else
     * entirely. The single segment and the suffix together are what make the value resolvable under the
     * served static location, which publishes one class-path folder and not a tree.</p>
     */
    private static final Pattern CONTRACT_PATH_SHAPE =
            Pattern.compile("^/[A-Za-z0-9._-]+\\.ya?ml$");

    /**
     * Supplies the metadata the generated API description of this service carries.
     *
     * @param contractPath the {@link String} request path the contract of record is served at, read
     *     from {@code springdoc.swagger-ui.url} so that the pointer this description publishes cannot
     *     drift from the document the browser view actually loads; must be an origin-relative path of
     *     one segment ending in a YAML suffix, which is checked rather than assumed
     * @return the {@link OpenAPI} metadata carrying the contract's specification version, information
     *     block, deployment root, tag set, global security requirement and bearer-token scheme, never
     *     {@code null}
     * @throws IllegalStateException if {@code contractPath} is {@code null}, blank, or not an
     *     origin-relative single-segment YAML path, because the generated description would then name a
     *     contract a reader cannot fetch while presenting it as the document that governs
     */
    @Bean
    public OpenAPI transactionServiceOpenApi(
            @Value("${springdoc.swagger-ui.url}") String contractPath) {

        // Assumptions: the property is read without a default on purpose. It is set in this
        //     module's application.yml, and a context that cannot resolve it is one where the
        //     browser view has no contract pinned either, so failing at startup reports the real
        //     condition. Supplying a default here would instead publish a path that resolves to
        //     nothing and read as a broken contract rather than as a missing setting.
        requireServedContractPath(contractPath);
        // Assumptions: no endpoint, host or credential is written into this class. The one
        //     external value it consumes arrives through configuration, and the deployment root
        //     below is a relative template rather than an absolute address.
        return new OpenAPI(SpecVersion.V31)

                // Assumptions: the enumeration passed to the constructor above selects the 3.1
                //     MODEL, and that selection is load-bearing rather than cosmetic: the
                //     information summary and the licence identifier set below are both members
                //     that exist only from 3.1, so a 3.0 model would silently drop them.
                .openapi(SPEC_VERSION)
                .info(contractInfo(contractPath))
                .servers(List.of(deploymentRootServer()))
                .tags(contractTags())

                // Assumptions: the requirement is declared once at document level rather than
                //     on each operation, matching the contract, because every operation of this
                //     context requires the same credential and none is reachable without one. A
                //     per-operation repetition would leave room for one operation to be published
                //     as open by omission.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, bearerTokenScheme()));
    }

    /**
     * Refuses a contract path the browser view could not load, before it is written into the description.
     *
     * <p>Assumptions: the check is on SHAPE and not on existence. Whether the named file is packaged is
     * asserted by this module's contract test, which can open it; a configuration class cannot, because the
     * path is resolved by the servlet container's static-resource handling rather than by the class path
     * directly, and a class that guessed at the mapping would be asserting its own guess.</p>
     *
     * @param contractPath the resolved value of {@link #CONTRACT_PATH_PROPERTY}
     * @throws IllegalStateException if the value is {@code null}, blank, or does not take the accepted
     *     origin-relative single-segment YAML form
     */
    private static void requireServedContractPath(String contractPath) {
        if (contractPath == null || contractPath.isBlank()) {
            throw new IllegalStateException(CONTRACT_PATH_PROPERTY
                    + " must name the request path the contract of record is served at,"
                    + " and must not be blank");
        }

        // Assumptions: the value is matched untrimmed. A path carrying leading or trailing whitespace is
        // not the path the container serves, so accepting it after a trim would make this class agree
        // with a value the static-resource handler would answer 404 for.
        if (!CONTRACT_PATH_SHAPE.matcher(contractPath).matches()) {
            throw new IllegalStateException(CONTRACT_PATH_PROPERTY + " must be an origin-relative path"
                    + " of one segment ending in .yaml or .yml, such as /transaction-api.yaml;"
                    + " an absolute URL would pin the contract to one host name");
        }
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

        // Assumptions: the version below is the version of the CONTRACT and not of the build.
        //     It is deliberately NOT read from this module's Maven coordinate, and that is the
        //     contract's own stated decision rather than an oversight here: a snapshot rebuild that
        //     changes no observable shape must not present itself to a client as a new API, so the
        //     two revise independently. Wiring the artifact version in would couple them and make
        //     every rebuild look like an API change.
        return new Info()
                .title(CONTRACT_TITLE)
                .summary(CONTRACT_SUMMARY)

                // Assumptions: this text restates the contract's own description rather than
                //     adding to it, and it opens by naming the contract because the two documents
                //     are not equal in authority. The four migrated CICS transactions are named
                //     with the source program each was transcribed from, so the traceability
                //     record stays a lookup rather than a translation, and the transaction names
                //     are the ones the root README.md inventory uses at its lines 298 to 302 --
                //     CT01 is Transaction View there, and calling it a detail view here would
                //     introduce a second name for one screen.
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

        // Alternatives Considered: a bare "/" url with no variable. Rejected because an edge
        //     stage prefix then has nowhere to go except an edit of the document, whereas a
        //     variable lets the prefix be substituted by whoever publishes the surface.
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

        // Alternatives Considered: declaring a second scheme, an API key or basic
        //     authentication, alongside this one. Rejected because a second admitted credential
        //     form is a second thing to get wrong at the edge and provides no capability the first
        //     does not already provide.
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

        // Alternatives Considered: leaving the tag set to be assembled from annotations on the
        //     controllers. Rejected because the tag DESCRIPTIONS would then be absent from the
        //     generated document while present in the contract, which is exactly the metadata
        //     difference that would obscure a genuine endpoint difference in the comparison the
        //     generated document is kept for.
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
