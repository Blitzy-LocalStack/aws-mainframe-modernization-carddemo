package com.carddemo.auth.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.servers.ServerVariable;
import io.swagger.v3.oas.models.servers.ServerVariables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the document-level OpenAPI metadata this module publishes, so that the document generated
 * at run time declares the same identity, license, origin and bearer requirement as the contract
 * committed beside it.
 *
 * <h2>Purpose</h2>
 *
 * <p>One responsibility and deliberately no others: the members of an OpenAPI document that sit above
 * its paths. There are four. The information block, carrying the title, version, summary, description
 * and license. The templated server entry and its one variable. The document-level security
 * requirement. And the single security scheme that requirement names. Each is settled by the contract
 * committed at {@code src/main/resources/openapi/auth-api.yaml}, and each is reproduced here so the
 * two documents agree member for member rather than approximately.</p>
 *
 * <p>Nothing below the information block is declared here. This class names no path, no operation, no
 * parameter, no schema and no response, because those are the parts of a document that must be derived
 * from the code that serves them rather than restated by hand. Restating them would produce a second
 * account of a surface with only one implementation, and the two would drift the moment a handler
 * changed.</p>
 *
 * <p>Assumptions: the health endpoint is absent from this class, and its absence is deliberate. It is
 * contributed by the framework, configured through the management keys in
 * {@code src/main/resources/application.yml}, and polled by the load balancer target group and the
 * container health check. The committed contract does not describe it either, so describing it here
 * would create a hand-maintained account of an endpoint neither file owns.</p>
 *
 * <p>Assumptions: no business rule lives here. The sign-on branch, the challenge answer, and the field
 * validation transcribed from the four user-administration programs belong to this context's
 * {@code service}, {@code mapper} and {@code dto} packages, which is the boundary the layering rule set
 * asserts mechanically.</p>
 *
 * <h2>Why the metadata is restated in code at all</h2>
 *
 * <p>Refactoring Rationale: without this class the generator publishes a document assembled purely
 * from its own defaults -- the placeholder title {@code OpenAPI definition}, a placeholder version and
 * no security scheme whatever -- so the generated document would describe a service sharing not one
 * member with the contract committed beside it, and a reader inspecting the running service would find
 * nothing in it stating that a bearer credential is required at all. The package charter in this
 * directory carries a measured census of four configuration types and marks each one landed;
 * {@code SecurityConfig} enforces the authority the contract declares, {@code CognitoIdentityConfig}
 * supplies the provider client, {@code DataSourceConfig} pins the schema, and this is the entry that
 * supplies the document-level members. It supplies them by restating the committed contract rather
 * than by minting a second identity for one service.</p>
 *
 * <p>Trade-offs: two artifacts therefore describe this service's document-level metadata, and only one
 * of them is the contract of record. The committed {@code openapi/auth-api.yaml} decides every
 * disagreement, because the browser client is authored against that file rather than against whatever
 * the generator emits -- {@code ui/src/api/auth.ts} names the contract as its source in its own header
 * and enumerates the operations that contract publishes -- so a client compiled against the committed
 * contract and then served a document that has drifted from it fails at the first call touching the
 * drifted member, and it fails inside the client, where nothing on this side of the boundary has logged
 * a cause. Drift is a defect in whichever artifact moved; it is not a variation to tolerate and not
 * something to reconcile at run time. The reference suite binds itself with the same shape of rule at
 * {@code tests/README.md} lines 5 to 6, which name the runner script rather than the prose documenting
 * it as the authority whenever the two disagree, and ask for the disagreement to be fixed rather than
 * worked around. What is accepted in exchange is the duplication itself: title, version, summary,
 * description, license, origin and scheme are each written twice, so an edit to one is an edit that has
 * to reach the other. What is bought is that the duplication is CHECKED rather than trusted --
 * {@code AuthConfigPackageTest} builds the bean below and compares its information block, its server
 * entry and its security scheme against the committed document read off the class path, so a divergence
 * fails the build instead of reaching a client.</p>
 *
 * <p>Alternatives Considered: generating the whole document from annotations and deleting the committed
 * contract. Rejected because the contract is a cross-boundary artifact consumed by a separately built
 * and separately deployed browser application, so it has to be reviewable as a diff before the code that
 * serves it exists -- which a generated document, by definition, cannot be.</p>
 *
 * <p>Alternatives Considered: reading these values out of the committed contract at start-up instead of
 * declaring them. Rejected because it inverts the dependency in the wrong direction: the running
 * service would then describe itself using a file it does not enforce, so a contract edit would change
 * what the service claims about itself without changing anything the service does.</p>
 */
// Alternatives Considered: leaving proxyBeanMethods at its default of true, which is what the
//       annotation does when the attribute is omitted. Rejected because the default exists to make a
//       direct call from one bean method to another return the singleton, and it buys that by having
//       the container generate a CGLIB subclass of this class at start-up. Nothing here makes such a
//       call: the three builders below are deliberately private and static rather than further bean
//       methods, so the proxy would be generated and never used. Turning it off also removes the
//       option of a later bean-to-bean call whose singleton semantics would depend on the proxy
//       silently being there, which is a coupling that is easier to prevent than to notice.
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /**
     * The document title, matching {@code info.title} of the committed contract.
     */
    private static final String CONTRACT_TITLE = "CardDemo Auth API";

    /**
     * The document version, matching {@code info.version} of the committed contract.
     *
     * <p>Assumptions: this is the CONTRACT's version and deliberately not the module's Maven version.
     * The two answer different questions -- what shape a caller may rely on, and which build produced
     * the artifact -- and tying them together would publish a breaking-change signal on every release.
     * The build identity is available separately from the framework's own information endpoint.</p>
     */
    private static final String CONTRACT_VERSION = "1.0.0";

    /**
     * The document summary, matching {@code info.summary} of the committed contract.
     */
    private static final String CONTRACT_SUMMARY =
            "Sign-on and user administration for the CardDemo auth bounded context.";

    /**
     * The document description, matching {@code info.description} of the committed contract.
     *
     * <p>Assumptions: the CICS transaction identifiers and the resource-definition line numbers are
     * carried across verbatim from the contract, because they are the traceability a reader uses to
     * find the baseline surface each operation replaces. Paraphrasing them would leave two versions of
     * one citation, and only one of them checkable.</p>
     */
    private static final String CONTRACT_DESCRIPTION = """
            Migrated surface of the CardDemo sign-on transaction and the four \
            user-administration transactions that the CICS resource definition \
            registered as CC00, CU00, CU01, CU02 and CU03 \
            (app/csd/CARDDEMO.CSD lines 378, 449, 459, 469 and 479). Sign-on exchanges a \
            user identifier and password for tokens minted by the configured Cognito \
            user pool, or returns the challenge a temporary password raises, which the \
            challenge operation answers; the remaining five operations administer the rows \
            of the auth schema's users table and are restricted to the carddemo-admin \
            authority. \
            Pseudo-conversational state is gone: no operation accepts or returns a \
            communication area, identity is read from the signed token on every request, \
            and the selected user travels in the request path.""";

    /**
     * The SPDX identifier of the license, used for both the license name and its identifier.
     *
     * <p>Assumptions: the same string serves both members, which is what the committed contract does.
     * The specification admits either a name or an identifier, and supplying the identifier for both
     * means a tool reading either member resolves the same license rather than one member being a
     * human-readable label a tool cannot act on.</p>
     */
    private static final String LICENSE_SPDX_IDENTIFIER = "Apache-2.0";

    /**
     * The name of the server-URL variable the single server entry is templated on.
     */
    private static final String EDGE_ORIGIN_VARIABLE = "edgeOrigin";

    /**
     * The templated server URL, matching {@code servers[0].url} of the committed contract.
     *
     * <p>Assumptions: a TEMPLATED origin rather than the relative {@code /} that some sibling contexts
     * publish, because this context's paths are absolute and already carry the {@code /api/v1} prefix
     * the edge route keys publish, and because the sign-on operations are the ones a browser reaches
     * FIRST -- before any same-origin assumption has been established by an earlier response.</p>
     */
    private static final String TEMPLATED_SERVER_URL = "{" + EDGE_ORIGIN_VARIABLE + "}";

    /**
     * Description of the single server entry, matching the committed contract.
     */
    private static final String SERVER_DESCRIPTION = """
            Origin of the HTTP API that fronts this service. Supplied per environment \
            from infrastructure outputs; the default below is a deliberately \
            unresolvable placeholder and is not an address of any deployment.""";

    /**
     * The default value of the origin variable, matching the committed contract.
     *
     * <p>Assumptions: the reserved {@code .invalid} top-level domain is used deliberately rather than a
     * plausible-looking hostname. It can never resolve, so a caller that failed to substitute the
     * variable fails immediately and visibly, whereas a plausible default fails later and looks like an
     * outage. It is also the mechanism by which no deployment address reaches source control, which the
     * migration requires.</p>
     */
    private static final String EDGE_ORIGIN_DEFAULT = "https://auth-api.example.invalid";

    /**
     * Description of the origin variable, matching the committed contract.
     */
    private static final String EDGE_ORIGIN_DESCRIPTION = """
            Scheme and host of the environment's API edge, without a trailing slash. \
            Every path in this document is absolute and already carries the /api/v1 \
            prefix that the edge route keys publish.""";

    /**
     * The name the document-level security requirement refers to the scheme by.
     */
    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    /**
     * The HTTP authentication scheme the credential is presented under.
     */
    private static final String BEARER_HTTP_SCHEME = "bearer";

    /**
     * The bearer token format, which is informational and identifies the credential's encoding.
     */
    private static final String BEARER_TOKEN_FORMAT = "JWT";

    /**
     * Description of the security scheme, matching the committed contract.
     *
     * <p>Assumptions: the three narrowing checks are stated in full -- the {@code token_use} claim, the
     * client identity and the required scope -- because each rejects a token that is otherwise valid and
     * signed by the same pool with the same key. A caller told only that a bearer JWT is required would
     * present an identity token, receive 401, and have nothing in this document to explain why.</p>
     */
    private static final String BEARER_SCHEME_DESCRIPTION = """
            Access token minted by the CardDemo Cognito user pool and returned by the \
            sign-on operation, presented as an HTTP bearer credential. The service accepts \
            only an access token, identified by a token_use claim of access: the same pool \
            signs identity tokens with the same key, and an identity token describes a user \
            to a client rather than authorising an API call, so accepting one would be a \
            confusion of purpose. The token must also have been issued to this deployment's \
            app client and must carry the pool's administrative sign-in scope; a Cognito \
            access token carries no audience claim, so the client is checked through its \
            client_id claim instead. Authorities are derived from the cognito:groups claim \
            -- carddemo-admin or carddemo-user -- and never from anything the caller \
            supplies in the request.""";

    /**
     * Publishes the document-level metadata of this service's OpenAPI document.
     *
     * <p>Assumptions: the security requirement is asserted at the DOCUMENT level and the three sign-on
     * operations override it with an empty requirement, exactly as the committed contract does. Stating
     * it document-wide means an operation added without a requirement inherits one, whereas stating it
     * per operation means a forgotten requirement is an unauthenticated endpoint that looks like every
     * other line in a diff.</p>
     *
     * @return the document-level metadata: information block, templated server, security requirement and
     *     the one scheme that requirement names; never {@code null}
     */
    @Bean
    public OpenAPI authServiceOpenApi() {
        return new OpenAPI()
                .info(contractInfo())
                .addServersItem(edgeServer())
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, bearerTokenScheme()));
    }

    /**
     * Builds the information block.
     *
     * @return the title, version, summary, description and license of the contract; never {@code null}
     */
    private static Info contractInfo() {
        // Trade-offs: every value assembled here is a restatement of a member of the committed
        //       openapi/auth-api.yaml, and the contract wins on any disagreement because the browser
        //       client is generated from it. A member edited here and not there therefore fails
        //       AuthConfigPackageTest, which is the point of accepting the duplication: the
        //       alternative is a client compiled against one spelling and served the other.
        return new Info()
                .title(CONTRACT_TITLE)
                .version(CONTRACT_VERSION)
                .summary(CONTRACT_SUMMARY)
                .description(CONTRACT_DESCRIPTION)
                .license(new License()
                        .name(LICENSE_SPDX_IDENTIFIER)
                        .identifier(LICENSE_SPDX_IDENTIFIER));
    }

    /**
     * Builds the single templated server entry and its origin variable.
     *
     * @return the server entry, never {@code null}
     */
    private static Server edgeServer() {
        ServerVariable origin = new ServerVariable();
        origin.setDefault(EDGE_ORIGIN_DEFAULT);
        origin.setDescription(EDGE_ORIGIN_DESCRIPTION);

        ServerVariables variables = new ServerVariables();
        variables.addServerVariable(EDGE_ORIGIN_VARIABLE, origin);

        return new Server()
                .url(TEMPLATED_SERVER_URL)
                .description(SERVER_DESCRIPTION)
                .variables(variables);
    }

    /**
     * Builds the one security scheme this document declares.
     *
     * @return the bearer scheme, never {@code null}
     */
    private static SecurityScheme bearerTokenScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme(BEARER_HTTP_SCHEME)
                .bearerFormat(BEARER_TOKEN_FORMAT)
                .description(BEARER_SCHEME_DESCRIPTION);
    }
}
