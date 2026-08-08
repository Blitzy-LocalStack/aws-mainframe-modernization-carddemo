package com.carddemo.reference.config;

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
 * Publishes the OpenAPI 3.1 document metadata for the reference-data bounded context.
 *
 * <p>This class contributes exactly three members to the document the publishing library serves: the
 * information block that identifies the contract, the one bearer-token security scheme, and the
 * document-level requirement naming that scheme. It contributes no path, no operation, no parameter,
 * no schema and no response, and that emptiness is the design rather than an unfinished edge.</p>
 *
 * <h2>Orientation</h2>
 *
 * <p>The surface this metadata fronts is the reference data every other context reads and none of them
 * writes: transaction types together with their categories, the disclosure-group rate lookup, the three
 * seeded United States address allow-lists, date evaluation, and reference-data maintenance. Assumptions:
 * that enumeration is by CAPABILITY and names neither a request-handler type nor a route, deliberately.
 * Naming either would put a second inventory of this context's HTTP surface in a file that contributes
 * none of it, and the two would then have to be kept in step by hand; the authority for what is
 * published is the committed contract, and the authority for which package holds what is this package's
 * own charter beside this file.</p>
 *
 * <p>On parameters, return values and exceptions: a class declaration accepts no argument, returns no
 * value and raises nothing, so this block carries none of the three corresponding at-clauses.
 * Assumptions: the inapplicability is stated rather than left silent, because the project Explainability
 * rule lists a docstring that omits its parameters or return values among its forbidden patterns, and a
 * reader has to be able to tell a declared inapplicability from an oversight. Each method below does
 * return a value, and each one documents what it returns.</p>
 *
 * <h2>Why every description here is authored rather than lifted</h2>
 *
 * <p>Refactoring Rationale: the baseline expresses this service's ancestry as CICS resource definitions,
 * and those definitions carry a description member that looks like a ready-made source for the prose an
 * interface document needs. It is not one. {@code app/app-vsam-mq/csd/CRDDEMOM.csd} declares
 * {@code DESCRIPTION(LIST CARDS)} at its L2, against {@code PROGRAM(COACCT01)}, and declares the very
 * same {@code DESCRIPTION(LIST CARDS)} again at its L10, against {@code PROGRAM(CODATE01)} -- and
 * neither program lists cards: the first is an account-inquiry consumer and the second a
 * date-conversion consumer. Lifting either string would publish, as this document's own prose, a
 * sentence that describes no behaviour this service has. Every description in this file is therefore
 * authored from observed behaviour instead. The baseline is the behavioural oracle for this migration
 * and is read and never modified, so the duplication stands exactly as written; it is recorded here as
 * the evidence for the authoring decision, and the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which this file cites and does not
 * maintain.</p>
 *
 * <p>Assumptions: the baseline's descriptions are NOT uniformly unreliable, and that is what makes a
 * blanket rule indefensible in either direction. {@code app/app-transaction-type-db2/csd/CRDDEMOD.csd}
 * declares {@code DESCRIPTION(CREDIT CARD TRAN TYPE INQ MAP)} at its L2 and
 * {@code DESCRIPTION(CREDIT CARD TRAN TYPE MAINT MAP)} at its L7, and both are accurate: they name the
 * transaction-type inquiry and maintenance surfaces this context really does publish, and they informed
 * the transaction-type wording the contract carries. So a blanket lift would import the two useless
 * strings along with the two useful ones, while a blanket discard would throw away information the
 * baseline actually got right. The rule applied is per-definition: read each one, keep what the source
 * demonstrably supports, and author the rest.</p>
 *
 * <p>Assumptions: exactly one of the two programs in that first resource definition belongs to this
 * context, and the description text is written so it cannot be read as covering both.
 * {@code app/app-vsam-mq/csd/CRDDEMOM.csd} binds {@code TRANSACTION(CDRD)} to {@code PROGRAM(CODATE01)}
 * at its L27-L28, which is the date-conversion flow this context migrated, and binds
 * {@code TRANSACTION(CDRA)} to {@code PROGRAM(COACCT01)} at its L17-L18, which is the account-inquiry
 * flow owned by {@code account-service} and described by that service's own document, not by this one.
 * The two sit adjacent in one file and share a duplicated description, which is precisely why the
 * boundary is stated rather than assumed.</p>
 *
 * <h2>Two documents, one surface</h2>
 *
 * <p>Trade-offs: two documents describe this service's HTTP surface and only one of them is the contract
 * of record. The hand-authored {@code src/main/resources/openapi/reference-api.yaml} is what the browser
 * client and this module's contract tests are written against, so it decides on any disagreement, and
 * the document generated from this context's request handlers is a CHECK on it rather than a second
 * source of truth. Every member this class sets is byte-identical to the corresponding member of that
 * file, so the relationship between the two is a literal comparison rather than a belief. The cost
 * accepted is that the two are kept in step deliberately instead of one being generated from the
 * other.</p>
 *
 * <p>Trade-offs: what no step in this module compares is FIELD-LEVEL agreement between a request handler
 * and the contract -- whether a property this context serializes carries the name, type and width the
 * contract declares for it. One side is Java and the other YAML, and nothing in this build reads both as
 * schemas. The generated document remains the instrument for that half, read by eye, and naming the
 * boundary is what stops a green build from being mistaken for full contract conformance.</p>
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    // WHY : Assumptions: the publishing library owns the specification version of the SERVED document,
    //       and this value agrees with it rather than competing with it. Verified against the pinned
    //       engine rather than taken from prose: the library's api-docs properties type defines a 3.1
    //       enumeration constant carrying exactly the string below, and the library's document resource
    //       re-asserts BOTH that string and the 3.1 specification flag on whatever document the factory
    //       method here returns, whenever the api-docs version property holds that constant. That
    //       property is declared exactly once, as the api-docs version key in this module's
    //       application.yml at its L905, and it is declared there rather than here for the reason
    //       recorded on the bean method.
    //
    // WHY : Assumptions: the swagger model's own constructors initialise their version field to a 3.0
    //       patch string, including the constructor that takes the 3.1 specification flag. So without
    //       this line the bean reports a 3.0 version to anything that inspects it directly, with no web
    //       layer running, and only the served document is correct. Setting it makes the bean and the
    //       document say the same thing at both observation points.
    //
    // WHY : Alternatives Considered: asserting nothing here and resting the 3.1 form entirely on the
    //       library default. Rejected because the default is legible only by decompiling a dependency,
    //       so a reader could not tell from this module whether the bean was 3.0 or 3.1. The
    //       mirror-image alternative, naming a different patch component from the library's, was
    //       rejected for the opposite reason: it is overwritten on the way out, so it would read as
    //       effective while having no effect whatsoever.
    private static final String SPEC_VERSION = "3.1.0";

    // WHY : Assumptions: byte-identical to the title of the committed contract, and its leading token is
    //       carried across from the baseline rather than invented. app/cpy/COTTL01Y.cpy L22 declares
    //       CCDA-TITLE02 with the value '              CardDemo                  ', and the field is
    //       PIC X(40), so the surrounding blanks are padding to the declared width and not data; the
    //       trimmed form is what a title member can hold. Recording the trim matters because a reader
    //       comparing this constant against the copybook would otherwise see a 40-character literal
    //       against an 8-character one and suspect the value had been retyped.
    //
    // WHY : Alternatives Considered: the baseline author already weighed a longer, more descriptive
    //       title and left it inactive. app/cpy/COTTL01Y.cpy L21 holds the commented-out
    //       '  Credit Card Demo Application (CCDA)   ' immediately above the active L22, and L19 holds
    //       CCDA-TITLE01 '      AWS Mainframe Modernization       '. Neither is adopted as this
    //       document's title. The commented-out longer form names the whole application rather than one
    //       context, so all nine service documents would open with the same words and a consumer
    //       holding two of them could not tell which one it was reading; the L19 banner names the
    //       modernization programme rather than a service, and appending it would make this document's
    //       title differ from the title of the contract of record, which is the one identity value a
    //       consumer keys on. The active short form is the one that survives, extended by the context
    //       name so the nine documents are distinguishable.
    //
    // WHY : Assumptions: this is a literal rather than the running application's configured name. Those
    //       two are not the same kind of value: the configured name is an operational identifier that
    //       also tags this service's metrics, whereas this is the human-readable name of a published
    //       contract. Deriving one from the other would let a change made for an operational reason
    //       rename the contract a browser client is written against.
    private static final String CONTRACT_TITLE = "CardDemo Reference Service API";

    // WHY : Assumptions: this is the revision of the published CONTRACT, byte-identical to the version
    //       member of the committed document, and it is deliberately neither this module's build
    //       coordinate nor any deployment identity. The module's own coordinate is a snapshot resolved
    //       from the aggregator, and binding the two together would republish the contract as changed on
    //       every rebuild that altered no operation, which is the opposite of what a consumer reads this
    //       value for.
    //
    // WHY : Refactoring Rationale: the baseline expressed deployment identity as a load library --
    //       app/app-vsam-mq/csd/CRDDEMOM.csd defines LIBRARY(CARDDLIB) at its L37-L38 over the data-set
    //       name at its L39 -- and that mechanism is retired rather than ported, because a task now runs
    //       a container image held in a private Amazon ECR registry and resolved at deployment. Carrying
    //       any part of that identity in this member was rejected on a concrete consequence rather than
    //       on taste: the image reference is not known to source, so it would have to arrive from the
    //       environment, and a contract version that moved with the environment would tell a consumer
    //       the interface had changed when only the build had. No image tag, registry host or account
    //       identifier appears anywhere in this file for the same reason.
    private static final String CONTRACT_VERSION = "1.0.0";

    // WHY : Assumptions: byte-identical to the summary member of the committed contract, and the copy
    //       direction is contract to Java rather than the reverse, because that document is the contract
    //       of record and this bean agrees with it.
    private static final String CONTRACT_SUMMARY = """
            Reference data of the migrated CardDemo credit-card management application: \
            transaction types and their categories, disclosure-group interest rates, the three \
            United States address lookups, and date evaluation.\
            """;

    // WHY : Assumptions: byte-identical to the description member of the committed contract, paragraph
    //       breaks included. The text-block form is what makes that achievable: one blank line per break
    //       yields exactly the two newline characters the contract's folded scalar yields for the same
    //       break, and every other line ends with a backslash so it contributes a space and not a
    //       newline. A single stray newline would make the two texts differ, which is the intended
    //       sensitivity rather than a fragility to work around.
    //
    // WHY : Alternatives Considered: authoring a shorter, independent description here instead. Rejected
    //       because two documents already describe one surface -- this constant and the description
    //       member of src/main/resources/openapi/reference-api.yaml at its L166 -- and two texts saying
    //       compatible things in different words are precisely the state in which a reader cannot tell
    //       whether the difference is meaningful: there is nothing to compare and no way to be wrong.
    //       Equality turns the relationship into one a literal comparison settles.
    //
    // WHY : Trade-offs: the copied text states figures -- the number of operations, of paths and of
    //       tables -- that describe the contract's content rather than this bean's, and stating them
    //       twice is normally how two places come to disagree about one number. It is accepted here
    //       because the figures are reproduced from the single authority verbatim rather than asserted
    //       independently, so a change to the surface is one edit in the contract that this constant then
    //       follows; the alternative of paraphrasing them away would have broken the byte-identity that
    //       makes the divergence detectable at all.
    private static final String CONTRACT_DESCRIPTION = """
            The reference context owns the data every other context reads and none of them writes. \
            It publishes nineteen operations, across thirteen paths, over six tables.

            Transaction types and transaction categories are maintained here, and the relationship \
            between them is the one referential rule the baseline states anywhere in this domain: \
            a category references its type, and the type cannot be deleted while a category still \
            references it. That refusal is a first-class user outcome in the baseline rather than \
            a fault, and it is reported here as 409 for the reason recorded on the Conflict \
            response.

            Disclosure-group rows carry the interest rate the nightly interest calculation \
            consumes. That rate is an arithmetic operand, not a display value, so it is \
            transported as a JSON string for the reason recorded on the InterestRate schema, and \
            the lookup operation documents the DEFAULT-group fallback the baseline performs when \
            an account's own group has no matching row.

            The three address lookups -- phone area codes, state codes and state and postal-prefix \
            combinations -- are seeded here and read from here, but they are validated elsewhere: \
            account-service queries them during account maintenance. This context therefore \
            exposes them for reading and offers no operation that writes them.

            Date evaluation is published twice over, and neither publication replaces the other. A \
            synchronous operation evaluates a date against a mask and returns the structured \
            verdict. A separate asynchronous contract, described on the AsyncDateReplyContract \
            schema, carries the same evaluation over a queue and answers in a 1000-byte positional \
            buffer, because that is the reply shape the baseline's queue consumers already parse.

            Identifiers and codes travel as strings throughout, never as JSON numbers, and every \
            code retains the declared width of the column behind it. The reasoning is recorded \
            once on the TransactionCategoryCode schema, where a copybook and a set of table \
            definitions genuinely disagree about the type of one field.\
            """;

    // WHY : Assumptions: the identifier is the SPDX short form rather than a prose licence name, because
    //       an OpenAPI 3.1 document carries a dedicated identifier member defined to hold an SPDX
    //       expression, and the repository's own LICENSE and NOTICE files put this project under that
    //       licence. A prose name in that member would be syntactically acceptable and semantically
    //       wrong. The value is byte-identical to both licence members of the committed contract.
    private static final String LICENSE_SPDX_IDENTIFIER = "Apache-2.0";

    // WHY : Assumptions: this name is a document-internal key, and it is the SAME key in two places by
    //       necessity -- the entry registered under the components block and the entry named by the
    //       document-level requirement. They are drawn from one constant so that they cannot fall out of
    //       step; two literals would let a rename in one place leave a requirement pointing at a scheme
    //       that no longer exists, which serialises without complaint and only fails a consumer reading
    //       the document. The value also matches the key the committed contract registers, so the two
    //       documents name one scheme rather than two.
    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    // WHY : Assumptions: the two values below are the registered names the specification defines for an
    //       HTTP-type scheme and are not free text. The scheme name is matched case-insensitively
    //       against the HTTP authentication registry, and the token format is informational only --
    //       nothing validates a token because this member says so.
    private static final String BEARER_HTTP_SCHEME = "bearer";

    private static final String BEARER_TOKEN_FORMAT = "JWT";

    // WHY : Assumptions: byte-identical to the scheme description of the committed contract, and it names
    //       both refusal outcomes because they are decided in two different places and a caller cannot
    //       tell them apart from the scheme alone. The 401 is decided before any handler runs, by the
    //       token filter that finds no usable credential; the 403 is decided after a credential has been
    //       accepted, by the rule guarding the route, which finds the authority it requires absent.
    //       Naming only one of the two would leave a caller unable to tell a bad credential from an
    //       insufficient one, and both outcomes are what the filter chain in this package enforces.
    private static final String BEARER_SCHEME_DESCRIPTION = """
            A JSON Web Token issued by the configured identity provider and validated as a \
            resource-server token. Its cognito:groups claim is converted to authorities by \
            services/common-lib/src/main/java/com/carddemo/common/security/JwtRoleConverter.java, \
            where the baseline user type 'A' becomes carddemo-admin and 'U' becomes carddemo-user. \
            Presenting no usable token yields 401; presenting a valid token that lacks the \
            authority an operation requires yields 403.\
            """;

    /**
     * Builds the starting OpenAPI document for this context, carrying only the members that sit above
     * the document's paths.
     *
     * <p>Three members are set and no others: the information block, one document-level security
     * requirement, and the single security scheme that requirement names. The paths, the schemas, the
     * responses and the tags are all left unset so that the publishing library derives them from this
     * context's own request handlers.</p>
     *
     * @return the {@link OpenAPI} document carrying this context's identity, licence and bearer
     *     requirement, with no path, parameter, schema, response or tag declared on it; never
     *     {@code null}
     */
    @Bean
    public OpenAPI referenceServiceOpenApi() {
        // WHY : Alternatives Considered: assembling the paths, the operations, the parameters, the
        //       schemas and the responses here in Java, or slicing the published document into named
        //       groups. Rejected on one concrete ground rather than on taste: the committed contract at
        //       src/main/resources/openapi/reference-api.yaml already states all of that, from its L338
        //       onward, so restating it here would produce two hand-maintained descriptions of one
        //       surface that no build step compares, and which of them a reader trusted would depend on
        //       which file they happened to open. Leaving those members unset is
        //       precisely what lets the library derive them from the handlers, and that is the only
        //       arrangement in which a handler that has drifted from the contract is visible at all.

        // WHY : Assumptions: no server entry and no environment-specific value appears in this document.
        //       This service is reached through two hops it cannot observe, so the library advertises a
        //       server address derived from the request instead. A literal address here would name one
        //       environment inside an artifact committed to source control and would be wrong in at
        //       least one other, and the address it would most plausibly name is the internal load
        //       balancer, which no browser can resolve. The committed contract carries a single relative
        //       entry for the same reason, so there is nothing here for this bean to add. Every
        //       environment-specific value this service reads arrives from infrastructure outputs through
        //       Parameter Store and Secrets Manager rather than from source, which is what makes the
        //       absence of endpoints and credentials in this repository structural instead of a matter of
        //       review discipline.

        // WHY : Assumptions: not one publishing-library property is bound here. This module's
        //       application.yml declares the document endpoint, its path and its specification version,
        //       and points the interactive interface at the committed contract; its two profile overlays
        //       vary only whether that interface is reachable. Binding any of those settings in Java as
        //       well would give one setting two owners, and which of them won would depend on property
        //       ordering rather than on anything written down -- a disagreement that surfaces as a
        //       missing endpoint rather than as a startup failure.

        // WHY : Alternatives Considered: declaring a codec-mapper bean in this package so that this
        //       class could shape serialisation alongside the document that describes it. Rejected, and
        //       the rejection is load-bearing rather than stylistic: the shared kernel contributes its
        //       money codec as a codec-module BEAN, at
        //       services/common-lib/src/main/java/com/carddemo/common/CardDemoCommonAutoConfiguration.java
        //       L157-L167, and the framework's own codec auto-configuration is what collects that bean
        //       onto the mapper it builds. Any mapper, mapper-builder or
        //       builder-customiser bean declared anywhere in this package makes that auto-configuration
        //       back off, so the collection step never runs and the money codec is never registered.
        //       Every amount then leaves in whatever shape the mapper's default handling produces rather
        //       than as the contracted JSON string, and the failure mode is silent -- it compiles, it
        //       starts, and it passes any assertion made against a deserialised object rather than
        //       against raw JSON. The prohibition is recorded here because this is the one place a reader
        //       would think to add such a bean.

        // WHY : Assumptions: the requirement is asserted once at document level and inherited by every
        //       operation, rather than repeated on each one where a single omission would pass review
        //       unnoticed. Its scope list is empty because the specification requires an empty list for
        //       any scheme that is neither of the two identity-federation types, and this scheme is of
        //       HTTP type, so an empty list is the correct value rather than a forgotten one.
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
     * <p>Every value it carries is a constant declared on this class, and each of those constants is
     * byte-identical to the corresponding member of the committed contract, so a comparison between the
     * generated document and that file finds these members equal. The summary and the description are
     * both set because an OpenAPI 3.1 document distinguishes them: the first is a one-line
     * identification and the second carries the detail a caller needs.</p>
     *
     * @return the {@link Info} block carrying the title, the contract revision, the short summary, the
     *     long description and the licence of this context's published API; never {@code null}
     */
    private static Info contractInfo() {
        // WHY : Assumptions: the licence member sets its name and its identifier to the same SPDX short
        //       form, and it sets no URL. The name member is required by the specification, so it cannot
        //       be dropped in favour of the identifier alone; the identifier member is the OpenAPI 3.1
        //       addition that carries an SPDX expression, and it is mutually exclusive with the URL
        //       member, which is why no URL is set here. Giving both members the same SPDX short form
        //       therefore satisfies the required member and the machine-readable one without introducing
        //       the one combination the specification rejects.
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
     * <p>The scheme is descriptive only. It states what a caller must present, and what a caller should
     * expect when it presents nothing usable or presents a token lacking the authority an operation
     * requires; it enforces none of that by itself.</p>
     *
     * @return the {@link SecurityScheme} of HTTP type declaring the bearer scheme, its informational
     *     token format and the description of how a presented token is treated; never {@code null}
     */
    private static SecurityScheme bearerTokenScheme() {
        // WHY : Assumptions: this declaration DESCRIBES the credential and enforces nothing. The filter
        //       chain and the token-validation rules that sit beside it in this package are what decide
        //       whether a presented credential is acceptable and what it may reach, and this document
        //       reports their outcome rather than deciding it. No decoder bean is declared here and no
        //       resource-server property is bound here, because the framework builds the decoder from
        //       configuration this module supplies through its environment; duplicating any part of that
        //       would create a second place for the two to disagree about which credentials are valid --
        //       a disagreement that would surface as an intermittent refusal rather than as a startup
        //       failure.
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme(BEARER_HTTP_SCHEME)
                .bearerFormat(BEARER_TOKEN_FORMAT)
                .description(BEARER_SCHEME_DESCRIPTION);
    }
}
