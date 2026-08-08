package com.carddemo.card.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the document-level OpenAPI metadata this module publishes, so that the document generated
 * at run time declares the same identity, license, origin and bearer requirement as the contract
 * committed beside it.
 *
 * <h2>Purpose</h2>
 *
 * <p>One responsibility, and deliberately no others: the members of an OpenAPI document that sit
 * above its paths. There are four of them. The information block, carrying the title, the version,
 * the summary, the description and the license. The single relative server entry. The
 * document-level security requirement. And the one security scheme that requirement names. Each is
 * settled by the contract committed at {@code src/main/resources/openapi/card-api.yaml}, and each is
 * reproduced here so that the two documents agree member for member rather than approximately.</p>
 *
 * <p>Nothing below the information block is declared here. This class names no path, no operation,
 * no parameter, no schema and no response, because those are the parts of a document that have to
 * be derived from the code which serves them rather than restated by hand. Why the boundary is
 * drawn at exactly that line, and not one member further in either direction, is recorded on the
 * class body below.</p>
 *
 * <p>The health endpoint is likewise absent, and its absence is deliberate rather than an
 * oversight. That endpoint is contributed by the framework, configured through the management keys
 * in {@code src/main/resources/application.yml}, and polled by the load balancer target group and
 * the container health check. The committed contract does not describe it either, so describing it
 * here would create a second hand-maintained account of an endpoint neither file owns.</p>
 *
 * <p>No business rule lives here. The card validation chains, the keyset paging behaviour and the
 * account-number shortening encoded from the baseline card programs belong to the service, mapper
 * and repository packages of this context, so a reader looking for why an update is refused, or why
 * an account number arrives shortened, will not find the answer in this class.</p>
 *
 * <h2>Configuration consumed</h2>
 *
 * <p>None, which is why the contract values below are constants rather than injected fields. The
 * {@code springdoc} keys declared in {@code src/main/resources/application.yml} settle where the
 * generated document is published, in which specification version, and in which key order. Not one
 * of them carries a title, a version, a summary or a description, so there is no key here to bind
 * against and no default worth inventing for one. Inventing a property for this class would add a
 * further place a document title could be written, and the two profile overlays beside that file may
 * override the value of an existing key but may not add one.</p>
 *
 * <h2>Beans contributed</h2>
 *
 * <p>Exactly one {@link OpenAPI}, which the publishing library reads as the starting document and
 * then merges with what it discovers from this module's request handlers. The merge direction is the
 * whole point: the members this class sets are the ones a reader has to be told, and the members it
 * leaves unset are the ones the code has to answer for.</p>
 *
 * <h2>Relationship to the committed contract</h2>
 *
 * <p>The committed file is the contract of record and this bean is aligned to it, never the other
 * way round. A difference between the generated document and the committed one is a defect in
 * whichever side drifted; it is not a formatting artifact to be tolerated, and it is not resolved by
 * editing the committed file to match a bean. The same relationship is recorded from the
 * configuration side on the {@code springdoc} keys, and from the contract side in that file's own
 * header.</p>
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    // WHY : Alternatives Considered: two other ways of reconciling this service's published
    //       document with the contract committed beside it were weighed, and both were rejected for
    //       the same underlying reason. The first was to let the generated document be authoritative
    //       and demote the committed file to documentation. The second, which looks the stronger of
    //       the two, was to read that committed file at startup and hand the whole of it to the
    //       publishing library, so that the served document would be the contract by construction.
    //
    //       The first was rejected because the committed file already has a consumer authored
    //       against it rather than against this service: the browser client at ui/src/api/cards.ts
    //       declares its row, detail and page shapes from that file's schemas. Drift in a generated
    //       document would therefore break a real client, and it would break it silently, because
    //       every value in question crosses the wire as a string and neither the Java build nor the
    //       TypeScript build can see the disagreement. The repository already settles precedence
    //       this way for its COBOL suite, where tests/README.md at lines 5 to 6 puts a runner script
    //       above the prose describing it: the committed artifact outranks the account of it, and
    //       this is that same rule applied to a contract.
    //
    //       The second was rejected because it would destroy the only check that exists. The
    //       generated document earns its keep by being derived from the handlers, so that a handler
    //       which stops matching the contract yields a document which stops matching it too. Feeding
    //       the contract in as the source would make the two agree unconditionally, and report
    //       agreement in precisely the case where disagreement is the fact worth knowing. That is
    //       why this class sets the members no handler can supply -- identity, license, origin and
    //       the bearer requirement -- and sets nothing a handler must answer for.
    //
    // WHY : Assumptions: the members set below are read as the STARTING document and merged with
    //       what the publishing library discovers, rather than replacing it. Setting a path or a
    //       schema here would therefore not merely duplicate the contract, it would pre-empt the
    //       discovery that makes the comparison meaningful. That assumption is what settles the
    //       scope of this class, so a future release changing the merge behaviour would require the
    //       scope to be revisited rather than quietly extended.

    // Assumptions: every text constant below has to resolve to the SAME string the corresponding
    //   folded block scalar in card-api.yaml resolves to, because a difference is exactly what a
    //   contract comparison is looking for. Two properties of that YAML form are reproduced
    //   deliberately: a single line break inside a folded scalar becomes one space, which is what
    //   each trailing backslash does here, and the strip indicator on those scalars leaves the value
    //   with no trailing newline, which is what the backslash on each closing line does. Dropping
    //   either would leave these values equal to the eye and unequal to a comparison.

    private static final String CONTRACT_TITLE = "CardDemo Card Service API";

    // Assumptions: this is the contract document's own version and is unrelated to the Maven version
    //   of the artifact that serves it. The two move for different reasons -- this one when the
    //   published interface changes, that one on every build of the module -- so deriving this from
    //   the project version would republish a new API version for a change no caller can observe.
    private static final String CONTRACT_VERSION = "1.0.0";

    /**
     * The OpenAPI specification version this document declares, reproduced from the committed
     * contract so a comparison of the two finds the member equal.
     *
     * <p>This is the specification's own version and not the API's; {@link #CONTRACT_VERSION}
     * carries the latter. It is set explicitly rather than left to the document model's default,
     * because that default is a 3.0 value -- see the rationale recorded at the assembly site.</p>
     */
    private static final String SPEC_VERSION = "3.1.1";

    private static final String CONTRACT_SUMMARY = """
            Card list, card lookup, card detail and card update for the card bounded context of \
            the migrated CardDemo credit-card management application.\
            """;

    private static final String CONTRACT_DESCRIPTION = """
            The card bounded context owns one table, card.cards, and exposes it through five \
            synchronous operations that carry across three online baseline programs: the card list \
            app/cbl/COCRDLIC.cbl, the card detail app/cbl/COCRDSLC.cbl and the card update \
            app/cbl/COCRDUPC.cbl. Those three ran as CICS transactions CCLI, CCDL and CCUP \
            respectively (app/csd/CARDDEMO.CSD:347-375) against the VSAM cluster defined at \
            app/jcl/CARDFILE.jcl:50.

            Three structural transformations distinguish this contract from the screens it \
            replaces. Session state is gone: the baseline was pseudo-conversational and carried \
            identity, selection and navigation in a buffer the terminal echoed back, so every \
            request here instead takes identity from a signed token and selection from an opaque \
            selector in the request path. The four-verb VSAM browse becomes a single \
            keyset-paginated query per request. And the record's before-image, which the update \
            screen carried across the gap between screen turns, becomes one server-owned version \
            token.

            Money does not appear in this context: card.cards holds no monetary column. Where a \
            value elsewhere in the migrated system is monetary it is transported as a JSON string \
            for the reason recorded on the accountId property below, and this service applies the \
            same discipline to its identifiers.\
            """;

    // Assumptions: one constant serves both license members because the committed contract sets
    //   both to the same SPDX expression. An OpenAPI 3.1 document may identify a license by such an
    //   expression instead of by a URL, which is why no URL is named here and none is missing.
    private static final String LICENSE_SPDX_IDENTIFIER = "Apache-2.0";

    // Assumptions: a single RELATIVE server entry, which resolves against whichever origin served
    //   the document. It is the one form that is simultaneously correct behind the edge, behind the
    //   internal load balancer and in a local run, and the only form that cannot carry a deployment
    //   address into version control. No host, port, region or environment name is named here or
    //   anywhere else in this class, and none may be added.
    private static final String ORIGIN_RELATIVE_SERVER_URL = "/";

    private static final String ORIGIN_RELATIVE_SERVER_DESCRIPTION = """
            The origin that served this document. Every path below is appended to it unchanged, so \
            no host, port or environment name is recorded here.\
            """;

    // Assumptions: the scheme name is held once because it is spelled in two places that have to
    //   agree -- the requirement below refers to it, and the committed contract declares it -- and a
    //   requirement naming a scheme that does not exist is published without complaint. The two
    //   values beside it are the specification's lower-case transport token and an informational
    //   token format; neither validates anything at run time, which the filter chain built elsewhere
    //   in this package does.
    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    private static final String BEARER_HTTP_SCHEME = "bearer";

    private static final String BEARER_TOKEN_FORMAT = "JWT";

    private static final String BEARER_SCHEME_DESCRIPTION = """
            A JSON Web Token issued by the configured identity provider and validated as a \
            resource-server token. Its cognito:groups claim is converted to authorities, where the \
            baseline user type 'A' becomes carddemo-admin and 'U' becomes carddemo-user. Presenting \
            no usable token yields 401; presenting a valid token without the authority an \
            operation requires yields 403.\
            """;

    /**
     * Builds the starting OpenAPI document for this module, carrying only the members that sit above
     * the document's paths.
     *
     * <p>Four members are set and no others: the information block, one relative server entry, one
     * document-level security requirement, and the single security scheme that requirement names.
     * The paths, the schemas and the responses are left entirely unset so that the publishing
     * library derives them from this module's request handlers, which is what allows the resulting
     * document to disagree with the committed contract when a handler has drifted from it.</p>
     *
     * @return the {@link OpenAPI} document carrying this service's identity, license, origin and
     *     bearer requirement, with no path, schema, response or tag declared on it; never
     *     {@code null}
     */
    @Bean
    public OpenAPI cardServiceOpenApi() {
        // WHY : Assumptions: the specification version is set on this object in BOTH of the two
        //       places that carry it -- the constructor's specification flag and the version string
        //       -- because the document model defaults both to a 3.0 value and this contract is 3.1.
        //       Two members of this document exist only in 3.1: the summary on the information block
        //       and the SPDX identifier on the licence. Which of them survive is decided by which
        //       serialiser the publishing library runs, selected by the springdoc.api-docs.version
        //       key in application.yml, and NOT by the flag set here; the 3.1 serialiser emits both
        //       members from a document constructed either way. The two carriers are INDEPENDENT: the
        //       constructor sets the flag and leaves the string at 3.0.1, so setting the flag alone
        //       still serves a document declaring 3.0.1 while carrying 3.1-only members and while the
        //       contract of record declares 3.1.0 -- internally contradictory, and silently so. That
        //       is why the string is set here as well rather than expected to follow from the flag.
        //
        //       Refactoring Rationale: an earlier revision of this comment asserted that the
        //       library's assembly step overwrites both the flag and the version string, and
        //       therefore that setting either here would be discarded without complaint. Assembly
        //       clones this document through an object mapper, and MEASUREMENT of that clone -- the
        //       case named below -- shows the two carriers behave differently rather than either
        //       being discarded. The version STRING survives every clone path, so the value set here
        //       does reach the served document; without it the document is served declaring the
        //       model's 3.0.1 default. The FLAG survives swagger's own 3.1 mapper and is reset to
        //       V30 by a plain one, which is exactly why the string is set explicitly instead of
        //       being left to follow from the flag.
        //
        //       Trade-offs: both are set although only one is load-bearing at the served document.
        //       The flag is what any reader of this bean sees before assembly -- a test comparing it
        //       to the contract, or a future library path that consults it -- so leaving it at a 3.0
        //       default while the document carries 3.1-only members would be a contradiction inside
        //       this object. Setting it also removes the dependence on the configuration key being
        //       present: transaction-service in this repository pins no version key at all and rests
        //       entirely on its constructor, which is the shape that survives someone tidying the
        //       key away. Both facts are asserted by account-service's config/OpenApiDocumentTest.java against the same library, so neither can
        //       drift back into prose that nothing checks.
        //
        // WHY : Assumptions: the bearer requirement is asserted once at document level and inherited
        //       by every operation, which is how the committed contract asserts it. Its scope list
        //       is empty because the specification requires an empty list for any scheme that is
        //       neither oauth2 nor openIdConnect, and this scheme is of HTTP type, so the empty list
        //       is the correct value rather than an omission.
        return new OpenAPI(SpecVersion.V31)
                .openapi(SPEC_VERSION)
                .info(contractInfo())
                .addServersItem(new Server()
                        .url(ORIGIN_RELATIVE_SERVER_URL)
                        .description(ORIGIN_RELATIVE_SERVER_DESCRIPTION))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, bearerTokenScheme()));
    }

    /**
     * Assembles the information block that identifies the published document.
     *
     * <p>Every value it carries is a constant declared on this class and reproduced from the
     * committed contract, so that a comparison between the generated document and that file finds
     * these members equal. The summary and the description are both set because an OpenAPI 3.1
     * document distinguishes them and the committed contract declares both.</p>
     *
     * @return the {@link Info} block carrying the title, the document version, the short summary,
     *     the long description and the license of this service's published API; never {@code null}
     */
    private static Info contractInfo() {
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
     * Declares the single security scheme every operation in this context is published as requiring.
     *
     * <p>The scheme is descriptive only. It tells a caller what to present, and what to expect when
     * it presents nothing usable or presents a token lacking the required authority; it enforces
     * none of that itself, which the filter chain built elsewhere in this package does.</p>
     *
     * @return the {@link SecurityScheme} of HTTP type declaring the bearer scheme, its informational
     *     token format and the description of how a presented token is validated; never
     *     {@code null}
     */
    private static SecurityScheme bearerTokenScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme(BEARER_HTTP_SCHEME)
                .bearerFormat(BEARER_TOKEN_FORMAT)
                .description(BEARER_SCHEME_DESCRIPTION);
    }
}
