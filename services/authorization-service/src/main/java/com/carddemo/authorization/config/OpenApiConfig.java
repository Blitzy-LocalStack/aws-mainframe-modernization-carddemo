package com.carddemo.authorization.config;

import com.carddemo.common.web.PageResponse;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;

/**
 * Supplies the document-level OpenAPI 3.1 metadata this module publishes, together with the authority
 * marker recording which CardDemo group each published operation requires.
 *
 * <h2>Purpose</h2>
 *
 * <p>Two responsibilities and deliberately no others. The first is the set of members that sit above an
 * OpenAPI document's paths: the information block carrying title, version, summary, description and
 * licence; the single relative server entry; the document-level security requirement; the one security
 * scheme that requirement names; and the single tag every operation of this context carries. The second
 * is the authority marker, which is the one member of this document that a request handler cannot
 * supply and that the filter chain in this same package nevertheless enforces.</p>
 *
 * <p>Every value below is settled by the contract committed at
 * {@code src/main/resources/openapi/authorization-api.yaml} and is reproduced here so the two documents
 * agree member for member rather than approximately.</p>
 *
 * <h2>Configuration consumed</h2>
 *
 * <p>Exactly two properties, both read through placeholders rather than written here:
 * {@code carddemo.security.cognito.admin-group-name} and
 * {@code carddemo.security.cognito.user-group-name}. They are the same two {@code SecurityConfig}
 * injects to build its filter chain, which is why the marker this class publishes and the rule that
 * enforces it cannot name different groups.</p>
 *
 * <p>Everything else this document's publication depends on is owned by
 * {@code src/main/resources/application.yml} and is not restated here: the generated document's path
 * and its specification version under the {@code springdoc.api-docs} keys, the browser view and the
 * contract it displays under {@code springdoc.swagger-ui}, the served class-path locations under
 * {@code spring.web.resources.static-locations}, the exposed management endpoints under
 * {@code management.endpoints.web.exposure}, and the token issuer under
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}. No issuer value, host, port, region,
 * environment name, queue address, account identifier or credential appears in this class, and none
 * may be added: the published document has to be correct behind the edge, behind the internal load
 * balancer and in a local run, and a deployment address written here would be wrong in at least two of
 * the three.</p>
 *
 * <h2>Beans contributed</h2>
 *
 * <p>Two. An {@link OpenAPI} the publishing library reads as the starting document and then merges
 * with what it discovers from this module's request handlers, and an {@link OpenApiCustomizer} that
 * runs after that merge to stamp the authority marker onto the document and onto each operation the
 * merge produced. The order matters and is the library's, not this class's: the marker has to be
 * applied after the paths exist, which is precisely why it is a customiser rather than another member
 * set on the starting document.</p>
 *
 * <h2>Members this class deliberately does not declare</h2>
 *
 * <p>No path, no operation, no parameter, no request body, no response and no schema. Those are the
 * parts of a document that must be derived from the code serving them rather than restated by hand,
 * and deriving them is what allows the generated document to <em>disagree</em> with the committed
 * contract when a handler has drifted from it. A schema restated here would make the two agree
 * unconditionally and so report agreement in exactly the case where disagreement is the fact worth
 * knowing.</p>
 *
 * <p>The paged list response is the clearest instance, because it is the member most often written by
 * hand elsewhere. It is derived from {@link PageResponse}, whose four components are its whole
 * surface: the rows, the leading cursor, the trailing cursor and the forward-availability flag, with
 * both cursors carried as opaque strings.</p>
 *
 * <p>WHY : Alternatives Considered: offset pagination -- a page number with a page size, or a skip
 * count with a total -- was the alternative, and it is rejected. Under concurrent inserts an offset
 * both skips and repeats rows, because the ordinal a caller asks for names a different row once
 * anything has been inserted ahead of it; key-ordered browsing has no such behaviour, since a cursor
 * names a row rather than a position. That is not a presentation difference but a change in observable
 * behaviour, and it would be a change away from the baseline rather than towards it: the reference
 * browse state was already a keyset cursor, carrying a first key, a last key and a
 * further-rows-exist indicator across each screen turn. Keyset paging is therefore the faithful
 * mapping and offset paging would be a behavioural change disguised as an implementation detail. The
 * consequence for this class is a prohibition with teeth: no page number, offset, skip count, total
 * count or page-size member may reach the published document, and if one ever appears there this class
 * or the envelope it derives from is wrong.</p>
 *
 * <h2>Widths this context does not share with its siblings</h2>
 *
 * <p>WHY : Assumptions: the message field in this context is <b>seventy-eight</b> characters wide, not
 * the seventy-five that appears in the wider migration documentation, and four declarations in the
 * reference tree agree on seventy-eight: {@code ERRMSGI PIC X(78)} at line 390 of
 * {@code app/app-authorization-ims-db2-mq/cpy-bms/COPAU00.cpy} with {@code ERRMSGO PIC X(78)} at its
 * line 764, and {@code ERRMSGI PIC X(78)} at line 180 of
 * {@code app/app-authorization-ims-db2-mq/cpy-bms/COPAU01.cpy} with {@code ERRMSGO PIC X(78)} at its
 * line 344. The seventy-five-character figure is a real contract but a <em>different</em> one: it
 * belongs to {@code CCARD-ERROR-MSG} and {@code CCARD-RETURN-MSG}, declared {@code PIC X(75)} at lines
 * 28 and 29 of {@code app/cpy/CVCRD01Y.cpy}, which is the shared session structure other contexts
 * carry and not this one. The consequence is concrete and one-directional: anyone adding a length,
 * an example or a pattern for a message member to this document who assumes seventy-five publishes a
 * bound three characters short of what the reference screen renders, and a message the baseline shows
 * whole would arrive at a conforming client truncated. Recorded here rather than left to the contract
 * alone because this class is where such a member would be added.</p>
 *
 * <h2>Relationship to the committed contract</h2>
 *
 * <p>The committed file is the contract of record and this class is aligned to it, never the other way
 * round. A difference between the generated document and that file is a defect in whichever side
 * drifted; it is not a formatting artifact to be tolerated, and it is not resolved by editing the
 * committed file to match a bean.</p>
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    // WHY : Alternatives Considered: two other ways of reconciling the served document with the
    //       committed contract were weighed and both rejected. The first was to treat the generated
    //       document as authoritative and demote the committed file to documentation; it is rejected
    //       because that file already has a consumer authored against it rather than against this
    //       service, so drift would break a real client and break it silently, every value in
    //       question crossing the wire as a string that neither build can compare. The second, which
    //       looks the stronger, was to read the committed file at startup and hand the whole of it to
    //       the publishing library so the served document would be the contract by construction; it
    //       is rejected because it would make the two agree unconditionally and so destroy the only
    //       check that exists. This class therefore sets the members no handler can supply and sets
    //       nothing a handler must answer for.

    // Assumptions: every text constant below has to resolve to the SAME string the corresponding
    //   block scalar in authorization-api.yaml resolves to, because a difference is exactly what a
    //   contract comparison looks for. Two properties of that YAML form are reproduced deliberately:
    //   a single line break inside a folded scalar becomes one space, which is what each trailing
    //   backslash does here, and the strip indicator leaves the value with no trailing newline, which
    //   is what the backslash on each closing line does.

    /** The published document's title, as the committed contract states it. */
    private static final String CONTRACT_TITLE = "CardDemo Authorization Service API";

    // Assumptions: this is the contract document's own version and is unrelated to the Maven version
    //   of the artifact that serves it. The two move for different reasons -- this one when the
    //   published interface changes, that one on every build of the module -- so deriving this from
    //   the project version would republish a new API version for a change no caller can observe.
    /** The published interface's version, as the committed contract states it. */
    private static final String CONTRACT_VERSION = "1.0.0";

    /**
     * The OpenAPI specification version this document declares, reproduced from the committed
     * contract so a comparison of the two finds the member equal.
     *
     * <p>This is the specification's own version and not the API's; {@link #CONTRACT_VERSION} carries
     * the latter. It is set explicitly because the constructor's specification flag does NOT set it:
     * a document constructed at 3.1 still reports the model's {@code 3.0.1} default string until this
     * value is applied -- see the rationale recorded at the assembly site.</p>
     */
    private static final String SPEC_VERSION = "3.1.0";

    /** The one-sentence account of this context's published surface. */
    private static final String CONTRACT_SUMMARY = """
            Pending-authorization summary list, pending-authorization detail and fraud tagging for \
            the authorization bounded context of the migrated CardDemo credit-card management \
            application.\
            """;

    // WHY : Alternatives Considered: the third paragraph below states that money crosses this
    //       boundary as a JSON STRING, and the alternative -- a JSON number -- is rejected for two
    //       independent reasons, the second of which is the stronger.
    //
    //       Looking forward: most clients parse a JSON number into an IEEE-754 double, which cannot
    //       represent most exact cent values, so an amount that is exact in the database and exact in
    //       this process becomes inexact at the one boundary a user actually reads. Money is exact
    //       fixed point at every other hop -- NUMERIC(p,2) in PostgreSQL, BigDecimal at scale 2 with
    //       RoundingMode.HALF_UP in Java -- and a JSON number would be the single link in that chain
    //       that discards precision. The prohibition on float and double in the money path is
    //       enforced by the shared LayeringRulesTest rather than left to review, so the string form
    //       here is the wire half of a rule the build already checks.
    //
    //       Looking backward, and decisively: the reference system already carries these amounts as
    //       TEXT, so a JSON string preserves its contract rather than departing from it. Both message
    //       money fields are declared PIC +9(10).99 -- a numeric-EDITED display field of fourteen
    //       characters, being one sign, ten integer digits, a decimal point and two fractional digits
    //       -- at line 27 of app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy and line 24 of
    //       app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy. Because those fields are characters
    //       rather than a numeric type, the consumer has to convert them explicitly, and it does:
    //       COMPUTE ... FUNCTION NUMVAL(...) at lines 376 to 377 of
    //       app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl. Adopting a JSON number would therefore
    //       introduce silent precision loss on a value the reference tree itself keeps in decimal
    //       text, which is the concrete cost of the alternative.
    /** The long account of this context, its transformations and its data-exposure boundaries. */
    private static final String CONTRACT_DESCRIPTION = """
            The authorization bounded context owns four tables in one schema and exposes three of \
            them through three synchronous operations that carry across three online reference \
            programs: the summary list cbl/COPAUS0C.cbl, the detail view cbl/COPAUS1C.cbl and the \
            fraud write cbl/COPAUS2C.cbl. The first ran as CICS transaction CPVS \
            (csd/CRDDEMO2.csd L49-L50) and the second as CPVD (L39-L40); the third is not a \
            transaction of its own and is reached by EXEC CICS LINK from the detail program at \
            cbl/COPAUS1C.cbl L248-L252.

            Four structural transformations distinguish this contract from the screens it replaces. \
            Session state is gone: the baseline was pseudo-conversational and carried identity, \
            selection and navigation in a structure the terminal echoed back, so every request here \
            takes identity from a signed token and selection from a sealed selector in the request \
            path. The IMS get-next-within-parent browse becomes a single keyset-paginated query per \
            request. The two resource managers become one: the baseline joined an IMS database and a \
            Db2 table with a genuine two-phase commit, and because all four tables now live in one \
            PostgreSQL schema that distributed transaction is eliminated rather than emulated, which \
            is documented divergence D-6 in docs/architecture/cobol-to-service-traceability.md. And \
            the reply the message-driven half publishes is committed with the data it reports \
            through a transactional outbox, closing a window the baseline leaves open, which is \
            documented divergence D-5 in the same register.

            Money in this context is transported as a JSON string at two distinct precisions, never \
            as a JSON number, for the reasons recorded on the SummaryAmount and DetailAmount \
            schemas. No card verification value appears anywhere in this document, in any schema, \
            example or description. Primary account numbers are masked to their last four digits in \
            every HTTP RESPONSE, and no response member is able to carry one whole. That guarantee \
            stops at the HTTP boundary and deliberately does not extend to the two queue payload \
            schemas: AuthorizationRequestMessage.cardNum carries the number whole because the \
            acquirer sends it whole and this context cannot mask what it has not yet received, and \
            AuthorizationReplyMessage.cardNum echoes the request field so the acquirer can correlate \
            the reply. Both are internal wire contracts on encrypted queues that no client of this \
            API reads, and each says so on its own member. This distinction is stated here rather \
            than left to those two members because a blanket masking claim at the top of a contract \
            is exactly what a reviewer assessing data exposure reads and stops at, and an \
            unqualified one would be false.\
            """;

    // Assumptions: one constant serves both licence members because the committed contract sets both
    //   to the same SPDX expression. An OpenAPI 3.1 document may identify a licence by such an
    //   expression instead of by a URL, which is why no URL is named here and none is missing.
    /** The SPDX expression identifying this repository's licence. */
    private static final String LICENSE_SPDX_IDENTIFIER = "Apache-2.0";

    // Assumptions: a single RELATIVE server entry, which resolves against whichever origin served the
    //   document. It is the one form simultaneously correct behind the edge, behind the internal load
    //   balancer and in a local run, and the only form that cannot carry a deployment address into
    //   version control.
    /** The origin-relative server entry, which names no deployment address. */
    private static final String ORIGIN_RELATIVE_SERVER_URL = "/";

    /** The account of what the relative server entry resolves against. */
    private static final String ORIGIN_RELATIVE_SERVER_DESCRIPTION = """
            The origin that served this document. Every path below is appended to it unchanged, so \
            no host, port, region or environment name is recorded here.\
            """;

    // Assumptions: the scheme name is held once because it is spelled in two places that have to
    //   agree -- the requirement below refers to it and the committed contract declares it -- and a
    //   requirement naming a scheme that does not exist is published without complaint.
    /** The name under which the bearer scheme is registered and referred to. */
    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    /** The specification's lower-case transport token for a bearer scheme. */
    private static final String BEARER_HTTP_SCHEME = "bearer";

    /** The informational token format, which validates nothing at run time. */
    private static final String BEARER_TOKEN_FORMAT = "JWT";

    /** The account of what a caller presents and what it receives when the token is unusable. */
    private static final String BEARER_SCHEME_DESCRIPTION = """
            A JSON Web Token issued by the configured identity provider and validated as a \
            resource-server token. The issuer is resolved at run time from this service's \
            configuration and no issuer value appears in this document. The token's cognito:groups \
            claim is converted to authorities by the shared converter in common-lib, where the \
            baseline administrative user type becomes carddemo-admin and the ordinary user type \
            becomes carddemo-user. Presenting no usable token yields 401; presenting a valid token \
            without the authority an operation requires yields 403.\
            """;

    /** The single tag every operation of this context carries. */
    private static final String TAG_NAME = "authorizations";

    /** The account of what the single tag groups, and why the authority field exists beside it. */
    private static final String TAG_DESCRIPTION = """
            Every operation of the pending credit-card authorization context: listing an account's \
            pending authorizations by key, reading one of them, and setting the fraud state of one \
            of them. The first two carry x-required-authority carddemo-user; the third carries \
            carddemo-admin, and that difference is the reason the field exists rather than being \
            implied by this one tag.\
            """;

    // WHY : Alternatives Considered: the group an operation requires is published as a specification
    //       extension rather than left to prose or implied by tag membership. Prose and tag membership
    //       are not checkable -- a reader could be told that fraud tagging is restricted while a
    //       generator or a gateway had no way to discover it -- whereas a named field is read by both.
    //       Expressing the authority as a security-scheme scope was the other candidate and is
    //       rejected: the specification requires an empty scope list for any scheme that is neither
    //       oauth2 nor openIdConnect, and this scheme is of HTTP type, so a scope list here would be
    //       invalid; re-declaring the scheme as oauth2 purely to gain a scope list was rejected in
    //       turn, because these values are group names carried in a claim and not OAuth scopes, and
    //       the re-declaration would misdescribe how a token is obtained.

    /** The document-level extension naming the authority model this context publishes. */
    private static final String AUTHORITY_MODEL_EXTENSION = "x-authority-model";

    /** The per-operation extension naming the single authority that operation requires. */
    private static final String REQUIRED_AUTHORITY_EXTENSION = "x-required-authority";

    /** The authority model's member naming the per-operation field a reader should look for. */
    private static final String AUTHORITY_MODEL_FIELD_KEY = "field";

    /** The authority model's member enumerating every authority the field may carry. */
    private static final String AUTHORITY_MODEL_VALUES_KEY = "values";

    /** The authority model's member naming the class in which the field is enforced. */
    private static final String AUTHORITY_MODEL_ENFORCED_BY_KEY = "enforcedBy";

    // Assumptions: the enforcing class's repository path is DERIVED from the class itself rather than
    //   written out, so that renaming it or moving it between packages updates the published value
    //   instead of leaving it naming a file that no longer exists. Only the source root is a literal,
    //   because a class object carries its package but not the tree it was compiled from.
    /** The Maven source root beneath which this module's Java sources sit. */
    private static final String JAVA_SOURCE_ROOT = "services/authorization-service/src/main/java/";

    /** The extension a Java source file carries. */
    private static final String JAVA_SOURCE_SUFFIX = ".java";

    /**
     * Builds the starting OpenAPI document for this module, carrying only the members that sit above
     * the document's paths.
     *
     * <p>Five members are set and no others: the information block, one relative server entry, one
     * document-level security requirement, the single security scheme that requirement names, and the
     * one tag every operation of this context carries. The paths, the parameters, the request bodies,
     * the responses and the schemas are left entirely unset so that the publishing library derives
     * them from this module's request handlers.</p>
     *
     * @return the {@link OpenAPI} document carrying this context's identity, licence, origin, bearer
     *     requirement and tag, flagged as an OpenAPI 3.1 document so its two 3.1-only information
     *     members survive serialisation, and with no path, schema, response or extension declared on
     *     it; never {@code null}
     */
    @Bean
    public OpenAPI authorizationServiceOpenApi() {
        // WHY : Assumptions: the specification FLAG is set on this object while the version STRING is
        //       left to the publishing library, and the two are different members that behave
        //       differently. The library resolves the version string from springdoc.api-docs.version
        //       in application.yml, which is pinned to the 3.1 form, so a string written here would be
        //       redundant at best. The flag is another matter: the model's own no-argument constructor
        //       defaults it to the 3.0 form, and no class in the publishing library assigns it -- the
        //       library reads its configured version to choose a serialiser and a schema resolver and
        //       never writes the flag onto the document it was handed. Two members of the information
        //       block below exist only in 3.1, the summary and the licence identifier, and both are
        //       emitted by the 3.1 serialiser and dropped by the 3.0 one. Leaving the flag at its
        //       default would therefore make those two members contingent on the serialiser choice
        //       alone, and their loss would be silent: a document missing a summary is a valid
        //       document, so nothing would fail and the served document would simply disagree with the
        //       committed contract on two members. Setting the flag makes the object self-consistent
        //       with the 3.1.0 document it mirrors and removes that contingency.
        //
        // WHY : Assumptions: the bearer requirement is asserted once at document level and inherited
        //       by every operation, which is how the committed contract asserts it. There is no
        //       unauthenticated operation in this context and none may be added; a requirement
        //       omitted from a single operation is invisible in review, whereas a document-level one
        //       has to be explicitly overridden to be lost. Its scope list is empty because the
        //       specification requires an empty list for a scheme that is neither oauth2 nor
        //       openIdConnect, so the empty list is the correct value rather than an omission.
        // WHY : Assumptions: the specification flag and the version STRING are two separate members
        //       and setting one does not set the other. Constructing at 3.1 leaves the string at the
        //       model's 3.0.1 default, so a document that carried the flag alone was served declaring
        //       3.0.1 while this context's committed contract declares 3.1.0 and while the document
        //       carries members -- the summary on the information block, the SPDX identifier on the
        //       licence -- that exist only in 3.1. Both are therefore set here.
        //
        //       Refactoring Rationale: the flag was already correct and the string was not, which is
        //       why nothing failed: whether those 3.1-only members reach a reader is decided by which
        //       serialiser the library runs, selected by the springdoc.api-docs.version key in
        //       application.yml, so the emitted document was complete while mislabelling its own
        //       version. The mechanism is measured in account-service's config/OpenApiDocumentTest.java
        //       against the same library.
        return new OpenAPI(SpecVersion.V31)
                .openapi(SPEC_VERSION)
                .info(contractInfo())
                .addServersItem(new Server()
                        .url(ORIGIN_RELATIVE_SERVER_URL)
                        .description(ORIGIN_RELATIVE_SERVER_DESCRIPTION))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME))
                .addTagsItem(authorizationsTag())
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, bearerTokenScheme()));
    }

    /**
     * Stamps the authority model onto the generated document and the required authority onto each
     * operation the publishing library discovered.
     *
     * <p>This is the one customiser this class contributes, and it exists because the marker is the
     * single member of the committed contract that no request handler can supply: a handler declares
     * a route and a payload, not the group a filter chain will demand of its caller. Without it the
     * generated document would omit a field the committed contract declares, so the two would
     * disagree on a member for a reason that is not drift.</p>
     *
     * @param adminGroupName the Cognito group name required by the fraud operation, bound from
     *     {@code carddemo.security.cognito.admin-group-name}; the same property
     *     {@code SecurityConfig} binds to guard that route
     * @param userGroupName the Cognito group name required by the read operations, bound from
     *     {@code carddemo.security.cognito.user-group-name}
     * @return an {@link OpenApiCustomizer} that adds the document-level authority model and the
     *     per-operation required-authority marker; never {@code null}
     */
    @Bean
    public OpenApiCustomizer authorizationAuthorityModelCustomizer(
            @Value("${carddemo.security.cognito.admin-group-name}") String adminGroupName,
            @Value("${carddemo.security.cognito.user-group-name}") String userGroupName) {
        // WHY : Assumptions: both group names arrive from the two properties SecurityConfig itself
        //       binds, so the enumeration published in the document-level model and the value stamped
        //       on each operation are computed from one source and cannot come to disagree with each
        //       other or with the rule that enforces them. Restating either name as a literal here
        //       would create a second place a group is spelled, and a document that advertised an
        //       authority the chain did not demand would be a security claim that is merely
        //       plausible.
        return document -> {
            document.addExtension(AUTHORITY_MODEL_EXTENSION,
                    authorityModel(adminGroupName, userGroupName));
            stampRequiredAuthority(document, adminGroupName, userGroupName);
        };
    }

    /**
     * Assembles the information block identifying the published document.
     *
     * <p>Every value it carries is a constant declared on this class and reproduced from the committed
     * contract, so a comparison between the generated document and that file finds these members
     * equal. The summary and the description are both set because an OpenAPI 3.1 document
     * distinguishes them and the committed contract declares both.</p>
     *
     * @return the {@link Info} block carrying this context's title, document version, short summary,
     *     long description and licence; never {@code null}
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
     * <p>The scheme is descriptive only. It tells a caller what to present and what to expect when it
     * presents nothing usable or presents a token lacking the required authority; it enforces none of
     * that itself, which the filter chain built elsewhere in this package does.</p>
     *
     * @return the {@link SecurityScheme} of HTTP type declaring the bearer scheme, its informational
     *     token format and the account of how a presented token is validated; never {@code null}
     */
    private static SecurityScheme bearerTokenScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme(BEARER_HTTP_SCHEME)
                .bearerFormat(BEARER_TOKEN_FORMAT)
                .description(BEARER_SCHEME_DESCRIPTION);
    }

    /**
     * Declares the single tag under which every operation of this context is grouped.
     *
     * @return the {@link Tag} carrying this context's tag name and the account of what it groups;
     *     never {@code null}
     */
    private static Tag authorizationsTag() {
        // WHY : Trade-offs: one tag for all three operations rather than a tag per authority level.
        //       Splitting by authority would let a reader infer the required group from tag
        //       membership, but it would also split a single cohesive resource across two groups in
        //       every generated client and every rendered view. The authority is instead carried by
        //       the per-operation field below, which a generator can read; the accepted cost is that
        //       this one tag says nothing about authority, which is why its own text points at the
        //       field.
        return new Tag()
                .name(TAG_NAME)
                .description(TAG_DESCRIPTION);
    }

    /**
     * Assembles the document-level block describing how this context publishes required authorities.
     *
     * <p>It names the per-operation field a reader should look for, enumerates the authorities that
     * field may carry, and names the class in which the field is enforced, so a reader who doubts the
     * marker can go and read the rule rather than trusting the document.</p>
     *
     * @param adminGroupName the Cognito group name the fraud operation requires; must not be
     *     {@code null}
     * @param userGroupName the Cognito group name the read operations require; must not be
     *     {@code null}
     * @return an unmodifiable, insertion-ordered mapping of the authority model's three members;
     *     never {@code null}
     */
    private static Map<String, Object> authorityModel(String adminGroupName, String userGroupName) {
        // WHY : Trade-offs: an insertion-ordered map is built and then wrapped, rather than assembled
        //       with Map.of. Map.of gives no iteration order at all, so the three members would be
        //       serialised in an order that could change between runs of the same build and turn a
        //       byte comparison of the served document into a false alarm. The accepted cost is two
        //       statements instead of one.
        Map<String, Object> model = new LinkedHashMap<>();
        model.put(AUTHORITY_MODEL_FIELD_KEY, REQUIRED_AUTHORITY_EXTENSION);
        // Assumptions: the enumeration is ordered least-privileged first, matching the committed
        //   contract, so the two documents agree on the list and not merely on its membership.
        model.put(AUTHORITY_MODEL_VALUES_KEY, List.of(userGroupName, adminGroupName));
        model.put(AUTHORITY_MODEL_ENFORCED_BY_KEY, enforcementSourcePath());
        return Collections.unmodifiableMap(model);
    }

    /**
     * Marks every operation of the generated document with the single authority its route requires.
     *
     * <p>The marker is derived from the same path pattern the filter chain in this package guards, so
     * a change to that pattern moves the published marker with it. Nothing is stamped when the
     * publishing library discovered no path, which is the state a context holding no request handler
     * would be in.</p>
     *
     * @param document the generated document to mark, already carrying whatever paths the publishing
     *     library discovered; must not be {@code null}
     * @param adminGroupName the Cognito group name to publish on the fraud route; must not be
     *     {@code null}
     * @param userGroupName the Cognito group name to publish on every other route; must not be
     *     {@code null}
     */
    private static void stampRequiredAuthority(OpenAPI document, String adminGroupName,
            String userGroupName) {
        if (document.getPaths() == null) {
            return;
        }
        // WHY : Alternatives Considered: the fraud route is recognised by matching each documented
        //       path against SecurityConfig.FRAUD_PATH_PATTERN, the very constant the filter chain
        //       matches on. Two alternatives were weighed. Writing the fraud path as a literal here
        //       was rejected because the marker and the rule would then be two independent spellings
        //       of one route, and the failure mode is silent in the dangerous direction: a document
        //       that keeps advertising an administrative restriction after the guarded pattern has
        //       moved reads exactly like a correct one. Keying off the handler's declaring class was
        //       rejected because it would bind this class to the controller package, which is the
        //       coupling the layering rules exist to prevent, and it would still not describe the
        //       route the chain actually guards. The remaining risk is inverted rather than removed:
        //       if the pattern ever stops matching the published path, every operation is published as
        //       requiring the ordinary group, which under-claims the restriction instead of
        //       over-claiming it and so cannot mislead a caller into believing a route is guarded when
        //       it is not.
        AntPathMatcher matcher = new AntPathMatcher();
        for (Map.Entry<String, PathItem> published : document.getPaths().entrySet()) {
            String requiredAuthority =
                    matcher.match(SecurityConfig.FRAUD_PATH_PATTERN, published.getKey())
                            ? adminGroupName
                            : userGroupName;
            for (Operation operation : published.getValue().readOperations()) {
                operation.addExtension(REQUIRED_AUTHORITY_EXTENSION, requiredAuthority);
            }
        }
    }

    /**
     * Derives the repository-relative path of the class in which the authority marker is enforced.
     *
     * @return the source path of the enforcing configuration class, relative to the repository root;
     *     never {@code null}
     */
    private static String enforcementSourcePath() {
        return JAVA_SOURCE_ROOT
                + SecurityConfig.class.getName().replace('.', '/')
                + JAVA_SOURCE_SUFFIX;
    }
}
