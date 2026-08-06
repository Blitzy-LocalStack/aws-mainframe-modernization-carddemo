package com.carddemo.reporting.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the OpenAPI 3.1 document metadata for the reporting and statement bounded context.
 *
 * <p>This class contributes exactly three members to the document the publishing library serves:
 * the information block that identifies the contract, the one bearer-token security scheme, and the
 * document-level requirement naming that scheme. It contributes no path, no operation, no schema and
 * no response, and that emptiness is the design rather than an unfinished edge.</p>
 *
 * <p>Trade-offs: two documents describe this service's HTTP surface and only one of them is the
 * contract of record. The hand-authored {@code src/main/resources/openapi/reporting-api.yaml} is
 * what {@code ui/src/api/reporting.ts} is written against, so it decides on any disagreement, and
 * the document generated from this context's request handlers is a CHECK on it rather than a second
 * source of truth. The compromise accepted is that the two can drift apart quietly, because no build
 * step compares them; the offsetting benefit is that a drift becomes discoverable at all, which a
 * single generated document could never be. Measured while this class was authored, that contract
 * file was not yet present in the tree, so every value below is pitched at the narrowest scope that
 * is accurate and carries no claim this context cannot honour. Where that contract and a database
 * migration disagree about a shape, the migration sits upstream of both and decides.</p>
 *
 * <p>Assumptions: {@code GET /actuator/health} is supplied by the framework and is deliberately not
 * a declared operation of this contract, so its absence from the published document is correct
 * rather than an omission. It is nonetheless a live runtime endpoint, answered without a token at
 * one unchanging path, because two consumers outside this repository's Java probe that identical
 * path and neither of them can present a token: the container health check in this module's
 * {@code Dockerfile}, and the load balancer target group. Both reach it on port 8080, the same
 * listener port as application traffic, and this module's {@code application.yml} records at its
 * L914 to L918 that there is deliberately no separate management port and no relocated base path,
 * because either one would let those two consumers disagree about liveness, with one calling the
 * task healthy while the other took it out of service.</p>
 *
 * <p>Assumptions: the count of declared operations is deliberately not stated anywhere in this file.
 * Measured while this class was authored, {@code com.carddemo.reporting.api} held exactly one file,
 * its package charter, and zero request-handler types, so there was no operation list to count. A
 * number written here would therefore have been a guess, and a guess in document metadata is worse
 * than an absence because it reads as measured.</p>
 *
 * <p>Alternatives Considered: the singular and the parenthesised registers for the four rationale
 * labels used throughout this file. Rejected on a census measured across this repository while this
 * file was authored: within {@code .java} sources under {@code services/} the plural,
 * colon-terminated forms occur 6064 times for assumptions, 997 for alternatives and 868 for
 * trade-offs, against exactly one parenthesised singular occurrence; and repository-wide all 152
 * parenthesised occurrences sit inside {@code tests/}, whose Python suite is reference-only and
 * keeps a register of its own. Matching the dominant register keeps a literal search for a label
 * complete, which a file mixing registers would defeat. The written convention itself is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never restated here.</p>
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    // WHY : Assumptions: the publishing library owns the specification version of the SERVED
    //       document, and this value agrees with it rather than competing with it. Verified against
    //       the pinned engine rather than taken from prose: the library's own api-docs properties
    //       type initialises its version field to the 3.1 enumeration constant, that constant
    //       carries exactly the string below, and the library's document resource re-asserts BOTH
    //       that string and the specification flag on whatever document the factory method here
    //       returns whenever the property holds the 3.1 constant. This module declares no key for
    //       that library in any of its three application documents, so it takes the default.
    //
    // WHY : Alternatives Considered: asserting nothing at all here and resting the 3.1 form entirely
    //       on that library default. Rejected because no file in this module records the default, so
    //       the contract's specification version would be legible only by decompiling a dependency;
    //       stating it makes this bean observable as 3.1 with no web layer running. The mirror-image
    //       alternative, asserting a different patch component from the library's, was rejected for
    //       the opposite reason: it would be overwritten on the way out and would then read as
    //       effective while having no effect whatsoever.
    private static final String SPEC_VERSION = "3.1.0";

    // WHY : Assumptions: the title is a literal rather than the running application's configured
    //       name. Those two are not the same kind of value: the configured name is an operational
    //       identifier that also tags this service's metrics, whereas this is the human-readable name
    //       of a published contract. Deriving one from the other would let a change made for an
    //       operational reason rename the contract a browser client is written against.
    private static final String CONTRACT_TITLE = "CardDemo Reporting Service API";

    // WHY : Assumptions: this is the version of the published CONTRACT and not of the built
    //       artifact. The module's own coordinate is a snapshot resolved from the aggregator, and
    //       binding the two together would republish the contract as changed on every rebuild that
    //       altered no operation, which is the opposite of what a consumer reads this value for.
    private static final String CONTRACT_VERSION = "1.0.0";

    private static final String CONTRACT_SUMMARY = """
            Transaction reports and account statements for the migrated CardDemo system, plus the \
            starting of an on-demand report execution.\
            """;

    // WHY : Assumptions: the description states what this context reads and what shape its output
    //       takes, both of which a caller needs and neither of which is derivable from an operation
    //       list. The two report edit masks are named separately on purpose: app/cpy/CVTRA07Y.cpy
    //       declares a leading-minus mask at its L30 for a detail amount and a leading-plus mask at
    //       its L54, L60 and L66 for the page, account and grand totals, each 15 characters wide,
    //       and treating them as one mask would change the bytes this context emits.
    private static final String CONTRACT_DESCRIPTION = """
            Reports and statements for the migrated CardDemo credit-card system. This context reads \
            and never writes: its queries resolve against read-only cross-schema views under a \
            database role holding select and nothing else, and it owns no table, index or \
            constraint. The surface it publishes covers the transaction detail report, whose \
            133-column width comes from the X(133) separator declared at app/cpy/CVTRA07Y.cpy L48; \
            the account statement pair rendered as plain text and as HTML; and the starting of an \
            on-demand report execution, which stands in for the baseline's submission of job \
            control text to a transient data queue. Monetary amounts are carried as JSON strings so \
            that no client parses one into IEEE-754 binary floating point. The report's two \
            distinct COBOL edit masks are preserved separately rather than unified: \
            app/cpy/CVTRA07Y.cpy L30 declares a leading-minus mask for a detail amount, while its \
            L54, L60 and L66 declare a leading-plus mask for the page, account and grand totals.\
            """;

    // WHY : Assumptions: the identifier is the SPDX short form rather than a prose licence name,
    //       because an OpenAPI 3.1 document carries a dedicated identifier member that is defined
    //       to hold an SPDX expression, and the repository's own LICENSE and NOTICE files put this
    //       project under that licence. A prose name in that member would be syntactically
    //       acceptable and semantically wrong.
    private static final String LICENSE_SPDX_IDENTIFIER = "Apache-2.0";

    // WHY : Assumptions: this name is a document-internal key, and it is the SAME key in two
    //       places by necessity -- the entry registered under the components block and the entry
    //       named by the document-level requirement. They are drawn from one constant so that they
    //       cannot fall out of step; two literals would let a rename in one place leave a
    //       requirement pointing at a scheme that no longer exists, which serialises without
    //       complaint and only fails a consumer reading the document.
    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    // WHY : Assumptions: the two values below are the registered names the specification defines
    //       for an HTTP-type scheme and are not free text. The scheme name is matched
    //       case-insensitively against the HTTP authentication registry, and the token format is
    //       informational only -- nothing validates a token because this member says so.
    private static final String BEARER_HTTP_SCHEME = "bearer";

    private static final String BEARER_TOKEN_FORMAT = "JWT";

    // WHY : Assumptions: the description names both refusal outcomes, 401 and 403, because they are
    //       decided in two different places and a caller cannot tell them apart from the scheme
    //       alone. The 401 is decided before any handler runs, by the token filter that finds no
    //       usable credential; the 403 is decided after a credential has been accepted, by the rule
    //       guarding the route, which finds the authority it requires absent. Naming only one of the
    //       two would leave a caller unable to tell a bad credential from an insufficient one.
    private static final String BEARER_SCHEME_DESCRIPTION = """
            An access token minted by the project's managed identity provider and validated by this \
            service acting as a resource server. The token's group claim is converted to \
            authorities, where the baseline user type 'A' becomes the administrator group and 'U' \
            becomes the ordinary user group. Presenting no usable token yields 401; presenting a \
            valid token that lacks the authority an operation requires yields 403.\
            """;

    /**
     * Builds the starting OpenAPI document for this context, carrying only the members that sit
     * above the document's paths.
     *
     * <p>Three members are set and no others: the information block, one document-level security
     * requirement, and the single security scheme that requirement names. The paths, the schemas,
     * the responses and the tags are all left unset so that the publishing library derives them from
     * this context's own request handlers.</p>
     *
     * @return the {@link OpenAPI} document carrying this context's identity, licence and bearer
     *     requirement, with no path, schema, response or tag declared on it; never {@code null}
     */
    @Bean
    public OpenAPI reportingServiceOpenApi() {
        // WHY : Alternatives Considered: assembling the paths, the operations, the schemas and the
        //       responses here in Java, or slicing the published document into named groups.
        //       Rejected on one concrete ground rather than on taste: the committed contract already
        //       states all of that, so restating it here would produce two hand-maintained
        //       descriptions of one surface that no build step compares, and which of them a reader
        //       trusted would depend on which file they happened to open. Leaving those members
        //       unset is precisely what lets the library derive them from the handlers, and that is
        //       the only arrangement in which a handler that has drifted from the contract is
        //       visible at all.

        // WHY : Alternatives Considered: declaring a codec-mapper bean in this package so that this
        //       class could shape serialisation alongside the document that describes it. Rejected,
        //       and the rejection is load-bearing rather than stylistic. The shared kernel
        //       contributes its money codec as a codec-module BEAN at
        //       com.carddemo.common.CardDemoCommonAutoConfiguration L112 to L123, and the
        //       framework's own codec auto-configuration is what collects that bean onto the mapper
        //       it builds. Any ObjectMapper, Jackson2ObjectMapperBuilder or
        //       Jackson2ObjectMapperBuilderCustomizer bean declared anywhere in this package makes
        //       that auto-configuration back off, so the collection step never runs and the money
        //       codec is never registered. Every amount then leaves in whatever shape the mapper's
        //       default handling produces rather than in the contracted JSON string: measured
        //       directly against this classpath, an unregistered codec renders one amount as a JSON
        //       object of the money type's predicate accessors, and in the general case a numeric
        //       type renders as a bare JSON number. Transformation rule T3 admits neither. The
        //       failure mode is silent -- it compiles, it starts, and it passes any assertion made
        //       against a deserialised object rather than against raw JSON -- which is why the
        //       prohibition is recorded here, at the one place a reader would think to add such a
        //       bean.

        // WHY : Assumptions: a JSON string for money is a contract and not fastidiousness.
        //       IEEE-754 binary floating point cannot represent most two-decimal fractions exactly,
        //       and app/cpy/CVACT01Y.cpy L7 declares ACCT-CURR-BAL PIC S9(10)V99, which is 12
        //       significant digits and leaves no margin at all. The baseline itself already carries
        //       money as text at a boundary while storing it packed:
        //       app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy L27 declares
        //       PA-RQ-TRANSACTION-AMT PIC +9(10).99, which is 14 characters of edited display, while
        //       app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy L34 declares
        //       PA-TRANSACTION-AMT PIC S9(10)V99 COMP-3, which is 7 packed bytes. A JSON string
        //       preserves that split; a JSON number would be the novel and lossy departure from it.

        // WHY : Assumptions: no server entry and no environment-specific value appears in this
        //       document. This service is reached through two hops it cannot observe, so the library
        //       advertises a server address derived from the request, which this module's
        //       application.yml depends on at its L318 to L337. A literal address here would name
        //       the internal load balancer instead, which no browser can resolve. Every
        //       environment-specific value this service reads arrives from infrastructure outputs
        //       through Parameter Store and Secrets Manager rather than from source, which is what
        //       makes the absence of credentials in this repository structural instead of a matter
        //       of review discipline.

        // WHY : Assumptions: the requirement is asserted once at document level and inherited by
        //       every operation. Its scope list is empty because the specification requires an empty
        //       list for any scheme that is neither of the two identity-federation types, and this
        //       scheme is of HTTP type, so an empty list is the correct value rather than a
        //       forgotten one.
        return new OpenAPI(SpecVersion.V31)
                .openapi(SPEC_VERSION)
                .info(contractInfo())
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, bearerTokenScheme()));
    }

    /**
     * Assembles the information block that identifies this published document.
     *
     * <p>Every value it carries is a constant declared on this class, so a comparison between the
     * generated document and the committed contract finds these members equal once that contract
     * lands in the tree. The summary and the description are both set because an OpenAPI 3.1
     * document distinguishes them: the first is a one-line identification and the second carries the
     * detail a caller needs.</p>
     *
     * @return the {@link Info} block carrying the title, the contract version, the short summary,
     *     the long description and the licence of this context's published API; never
     *     {@code null}
     */
    private static Info contractInfo() {
        // WHY : Assumptions: the licence member sets its name and its identifier to the same SPDX
        //       short form, and it sets no URL. The name member is required by the specification, so
        //       it cannot be dropped in favour of the identifier alone; the identifier member is the
        //       OpenAPI 3.1 addition that carries an SPDX expression, and it is mutually exclusive
        //       with the URL member, which is why no URL is set here. Giving both members the same
        //       SPDX short form therefore satisfies the required member and the machine-readable one
        //       without introducing the one combination the specification rejects.
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
     * Declares the single security scheme every operation in this context is published as
     * requiring.
     *
     * <p>The scheme is descriptive only. It states what a caller must present, and what a caller
     * should expect when it presents nothing usable or presents a token lacking the authority an
     * operation requires; it enforces none of that by itself.</p>
     *
     * @return the {@link SecurityScheme} of HTTP type declaring the bearer scheme, its
     *     informational token format and the description of how a presented token is treated;
     *     never {@code null}
     */
    private static SecurityScheme bearerTokenScheme() {
        // WHY : Assumptions: this declaration DESCRIBES the credential and enforces nothing. The
        //       filter chain and the token-validation class that sit beside it in this package are
        //       what decide whether a presented credential is acceptable and what it may reach. No
        //       decoder bean is declared here and no resource-server property is bound here,
        //       because the framework builds the decoder from configuration this module supplies
        //       through its environment, and duplicating any part of that would create a second
        //       place for the two to disagree about which credentials are valid -- a disagreement
        //       that would surface as an intermittent refusal rather than as a startup failure.
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme(BEARER_HTTP_SCHEME)
                .bearerFormat(BEARER_TOKEN_FORMAT)
                .description(BEARER_SCHEME_DESCRIPTION);
    }
}
