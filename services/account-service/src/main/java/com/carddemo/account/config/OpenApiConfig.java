package com.carddemo.account.config;

import com.carddemo.common.security.InternalServiceToken;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the document-level OpenAPI metadata of the account, customer and card-cross-reference
 * context, so that the document generated at run time declares the same identity, licence, origin
 * and bearer requirement as the contract committed beside it.
 *
 * <h2>Purpose</h2>
 *
 * <p>One responsibility and deliberately no others: the members of an OpenAPI document that sit
 * above its paths. The information block carrying the title, the document version, the summary, the
 * description and the licence; one relative server entry; one document-level security requirement;
 * and the single reusable security scheme that requirement names. Nothing below the information
 * block is declared here. This class names no path, no operation, no parameter, no response and no
 * component schema, because those are the members that have to be derived from the code which
 * serves them rather than restated by hand.</p>
 *
 * <p>The package charter in this directory records the same boundary from the other side, naming
 * this class as the owner of the published contract's document metadata and nothing else, and
 * noting that it never alters a status code, a payload shape or a validation outcome.</p>
 *
 * <h2>Why this class is needed at all</h2>
 *
 * <p>The publishing library reads the object built here as the STARTING document and then merges
 * into it what it discovers from this module's request handlers. Left without such an object it
 * still publishes a document, but one carrying its own placeholder title and version and no
 * security scheme whatsoever. Those are precisely the members no handler can supply, so they are
 * precisely the members this class exists to set.</p>
 *
 * <h2>Relationship to the committed contract</h2>
 *
 * <p>The file {@code src/main/resources/openapi/account-api.yaml} is the contract of record and this
 * bean is aligned to it, never the other way round. The {@code springdoc} keys in
 * {@code src/main/resources/application.yml} record the same relationship from the configuration
 * side: the generated document is not a second source of truth, it is a check on the first, and a
 * difference between the two is a defect in whichever side drifted rather than a formatting
 * artifact to be tolerated. That is the whole reason the members below are reproduced rather than
 * paraphrased.</p>
 *
 * <h2>What this class must never own</h2>
 *
 * <p>No exception handling and no error-response schema. The conflict conditions this context can
 * raise are already rendered as HTTP 409 by {@code com.carddemo.common.error.GlobalExceptionHandler}
 * over the single problem shape {@code com.carddemo.common.error.ApiError}, and a second account of
 * either declared here would fork one contract into two. No serialisation module either: the
 * shared kernel's money serialisation arrives by auto-configuration, as the entry point
 * {@code AccountApplication} records.</p>
 *
 * <p>No enforcement. The scheme declared below is descriptive: it tells a caller what to present.
 * Which tokens are actually accepted on which path is settled by {@code InternalApiSecurityConfig}
 * for the internal read paths and by {@code SecurityConfig} for everything else in this context.</p>
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    // WHY : Assumptions: decision D1 -- this class is scoped to document-level metadata because the
    //       members below it have two other owners that would compete with it. Per-operation
    //       metadata is derived by the publishing library from the handler annotations in
    //       com.carddemo.account.api, and the inventory of published operations is settled by
    //       src/main/resources/openapi/account-api.yaml. Setting a path or a component schema here
    //       would not merely duplicate that file, it would PRE-EMPT the discovery that makes the
    //       comparison between the two documents meaningful -- a generated document fed its own
    //       expected answer reports agreement in exactly the case where disagreement is the fact
    //       worth knowing.
    //
    // WHY : Trade-offs: decision D6 -- the summary and description below therefore describe the
    //       published surface QUALITATIVELY and state no number of operations, paths or verbs. The
    //       operation inventory is a property of the contract of record and of the handlers that
    //       answer it, and a count restated in a third place is a value that can drift without any
    //       of the three noticing. Precision is given up deliberately: these two members are the
    //       ones a comparison against openapi/account-api.yaml must NOT be held to byte equality
    //       on, whereas the title, the document version, the licence and the security scheme are.
    //       This wording is revisited in lockstep with that file whenever the published surface
    //       changes.
    //
    // WHY : Assumptions: decision D5 -- no handler and no error-response schema is declared here,
    //       and the reason is that both already exist elsewhere rather than that neither belongs in
    //       a document. com.carddemo.common.error.GlobalExceptionHandler already renders this
    //       context's conflict conditions, a failed optimistic-lock check and a restricted delete,
    //       as HTTP 409 over the single problem shape com.carddemo.common.error.ApiError. Declaring
    //       a second mapping or a second error schema here would fork one contract into two and make
    //       which of them answers a given request depend on bean ordering.
    //
    //       The conflict this context can actually raise is not an invention of the migration, which
    //       is why it needs no new vocabulary here. app/cbl/COACTUPC.cbl already implements a
    //       before-image concurrency check across the pseudo-conversational gap: it snapshots the
    //       pre-edit record from line 669 onward, carries the change flag declared at line 168, and
    //       selects the user-visible text held at lines 521 to 522 when the rewrite finds the record
    //       altered. That text is chosen by the service package of this context and is deliberately
    //       NOT reproduced here as a constant or an example value; only its provenance is cited, so
    //       there is exactly one place it can be edited.

    // Assumptions: every text constant below is reproduced from the corresponding member of
    //   openapi/account-api.yaml so that a comparison between the generated document and that file
    //   finds these members equal rather than merely similar. Two properties of the folded block
    //   scalars in that file are reproduced deliberately: a single line break inside such a scalar
    //   becomes one space, which is what each trailing backslash below does, and the strip
    //   indicator leaves the value with no trailing newline, which is what the backslash on each
    //   closing line does. Dropping either would leave these values equal to the eye and unequal to
    //   a comparison.

    /** The document title, reproduced from the title of the contract of record. */
    private static final String CONTRACT_TITLE = "CardDemo Account Context - Internal Read API";

    // WHY : Alternatives Considered: decision D2 -- deriving this value from build information, that
    //       is from the Maven coordinate of the artifact which serves the document, was the obvious
    //       route and is wrong here for two independent and checkable reasons.
    //
    //       First, it would guarantee permanent disagreement rather than removing drift. This
    //       module declares no <version> of its own and inherits from the parent
    //       com.carddemo:carddemo-services, whose version carries a SNAPSHOT qualifier, while the
    //       contract of record declares its info.version without one. A document version taken
    //       from the build would therefore differ from the committed contract on EVERY build, and
    //       the comparison the springdoc keys exist to enable would report a false positive
    //       continuously until it stopped being trusted.
    //
    //       Second, a contract version and a build version are not the same quantity and are not a
    //       duplicated coordinate. They move for different reasons: this one when the published
    //       interface changes, that one on every build of the module. Deriving one from the other
    //       would republish a new API version for a change no caller can observe.
    //
    //       Sourcing it from a configuration property was considered too and rejected as
    //       unavailable rather than undesirable: no springdoc or carddemo key in application.yml
    //       carries a document title, version, summary or description, so there is no key here to
    //       bind against, and the two profile overlays beside that file may override the value of
    //       an existing key but may not add one. Inventing a key would create a further place a
    //       document version could be written.
    /** The version of the published contract, reproduced from the contract of record. */
    private static final String CONTRACT_VERSION = "1.0.0";

    /**
     * The OpenAPI specification version this document declares, reproduced from the contract of
     * record so a comparison of the two finds the member equal.
     *
     * <p>This is the specification's own version and not the API's; {@link #CONTRACT_VERSION}
     * carries the latter. It is set explicitly rather than left to the document model's default,
     * because that default is a 3.0 value -- see the rationale recorded at the assembly site.</p>
     */
    private static final String SPEC_VERSION = "3.1.0";

    /** The short-form statement of what the published surface is for. */
    private static final String CONTRACT_SUMMARY = """
            The internal read surface of the account bounded context, through which another \
            bounded context resolves each incoming authorization against the account, customer \
            and card-cross-reference records this context owns.\
            """;

    // Assumptions: the citations in the description are physical line numbers, counted the way this
    //   repository counts lines, and every one was read from the file named beside it rather than
    //   inferred. They are carried into the published document on purpose: a reader asking why a
    //   field is shaped as it is can reach the baseline that settled it without being given a
    //   second-hand account of it first.
    /** The long-form description of the published surface and the baseline it carries across. */
    private static final String CONTRACT_DESCRIPTION = """
            This context owns the PostgreSQL schema account and exactly three tables in it -- \
            accounts, customers and card_xref -- and this document describes the internal read \
            surface published over them to one other bounded context. Each published operation is \
            a keyed read, and together they supply the facts an authorization decision needs: \
            which account and customer a card belongs to, what that account's limits and balances \
            are, and whether a customer record exists.

            The context is the migration target of the online account view app/cbl/COACTVWC.cbl, \
            which its own header declares at lines 2 to 4 as business logic accepting an account \
            view request, of the online account update app/cbl/COACTUPC.cbl, and of the sequential \
            readers app/cbl/CBACT03C.cbl over the cross-reference and app/cbl/CBCUS01C.cbl over the \
            customer master. The first two ran as the CICS transactions CAVW and CAUP, bound to \
            their programs at app/csd/CARDDEMO.CSD lines 317 and 318 and lines 306 and 308 \
            respectively. The human-facing view and update operations those two transactions carry \
            are NOT declared in this document; the contract of record records that scoping \
            decision and the point at which it is revisited.

            Selection reached the baseline programs as screen filters rather than as a request \
            path, and app/cbl/COACTVWC.cbl still shows the two it accepted as validation-flag \
            triads at its lines 58 to 61 and 62 to 65. In the migrated form a selection value \
            travels in the request instead, which is what makes each request independently \
            authorizable rather than dependent on state a client echoed back.

            Two physical files the baseline kept apart are one table here. The CICS file resource \
            CCXREF at app/csd/CARDDEMO.CSD line 37, and the alternate index CXACAIX over it \
            defined at line 63 and described at line 64 as an alternate index to CCXREF via the \
            account key, become the card_xref table and a non-unique secondary index on its \
            account column. The alternate index is preserved as a real secondary access path \
            rather than re-derived per query, though the operations published here read the \
            cross-reference by its own key rather than through that path.

            Money is rendered as a JSON string throughout and never as a JSON number. \
            app/cpy/CVACT01Y.cpy declares the account record's monetary fields at its lines 7, 8, \
            9, 13 and 14, each of them zoned decimal with a sign overpunch and two declared \
            decimal places. A JSON number is parsed into IEEE-754 binary floating point by most \
            clients, which cannot represent every such value exactly, so the string form is how \
            that exactness survives the wire.\
            """;

    // Assumptions: the licence is named by its SPDX identifier and carries no address. OpenAPI 3.1
    //   treats the identifier and a URL as mutually exclusive, so naming the identifier is the form
    //   that states the licence without putting an address into Java source at all. The
    //   repository's own licence is the value named; nothing is asserted about it that the
    //   repository does not already declare.
    /** The SPDX identifier of the licence this repository is published under. */
    private static final String LICENSE_SPDX_IDENTIFIER = "Apache-2.0";

    // WHY : Assumptions: decision D3 -- a single RELATIVE server entry, which resolves against
    //       whichever origin served the document. No host, port, region, stage, environment name or
    //       account identifier is named here, and none may be added. It is the one form that is
    //       simultaneously correct behind the edge, behind the internal load balancer and in a
    //       local run, and the only form that cannot carry a deployment address into version
    //       control. The contract of record templates its own server entry over a host variable
    //       instead; that file supplies a development default for the variable, and this class
    //       deliberately does not, which is the one member where the two documents differ by
    //       design rather than by drift.
    //
    //       There is baseline precedent for treating an endpoint as run-time configuration, and it
    //       is worth citing accurately rather than flatteringly. app/app-vsam-mq/cbl/COACCT01.cbl
    //       declares 01 QUEUE-INFO at line 92 with four PIC X(48) names at lines 93 to 96, every
    //       one initialised to spaces. Only the INPUT queue is genuinely injected at run time: the
    //       program retrieves it at lines 191 to 192 and moves it into place at line 197. The reply
    //       and error names are hard-coded, at line 198 and line 294 respectively.
    //
    // WHY : Refactoring Rationale: decision D3 -- externalising every endpoint value is therefore an
    //       improvement on the baseline rather than a port of it. What was wrong with the old
    //       arrangement is that two of its four names could not be changed without recompiling the
    //       program that used them, while the field widths advertised an intent to configure all
    //       four.
    /** The relative server entry, resolved against whichever origin served the document. */
    private static final String ORIGIN_RELATIVE_SERVER_URL = "/";

    /** The description of the relative server entry, stating that it records no address. */
    private static final String ORIGIN_RELATIVE_SERVER_DESCRIPTION = """
            The origin that served this document. Every path below is appended to it unchanged, so \
            no host, port or environment name is recorded here.\
            """;

    // Assumptions: the scheme name is held once because it is spelled in three places that have to
    //   agree -- the requirement below refers to it, the component map below is keyed by it, and the
    //   contract of record declares it under exactly this name. A requirement naming a scheme that
    //   does not exist is published without complaint, so a disagreement here fails silently rather
    //   than loudly. The name is the contract's own rather than the name the sibling contexts use,
    //   because those describe an identity-provider token at the public edge and this describes a
    //   machine token on an internal path; one name for two different credentials would be worse
    //   than two names.
    /** The component name of the reusable security scheme, as the contract of record declares it. */
    private static final String INTERNAL_TOKEN_SCHEME_NAME = "internalServiceToken";

    /**
     * The vendor-extension member on the security scheme that names the scope a presented token
     * must carry.
     *
     * <p>An {@code x-} member is the specification's own mechanism for data it does not define, and
     * it is used here in preference to the security requirement's list for the reason recorded at
     * the assembly site: 3.1 permits that list to name roles but defines them as not exchanged
     * in-band, so a populated list would read as an OAuth scope while carrying no more force than
     * this does.</p>
     */
    private static final String REQUIRED_SCOPE_EXTENSION_NAME = "x-carddemo-required-scope";

    /**
     * The vendor-extension member on the security scheme that names the scope the two customer-record
     * operations require instead of the default one.
     *
     * <p>Refactoring Rationale: a SECOND member exists because there are now two scopes and the member
     * above can name only one. It named the only scope there was, and that one scope governed the
     * customer scan and the keyed customer record read as well as the decision-path reads -- so a document
     * reader was told, correctly for the code as it then stood, that a credential admitting a
     * cross-reference lookup also admitted enumerating the customer master. Publishing the second scope
     * beside the first is what lets a reader see that the two groups are separated without reading the
     * filter chain.</p>
     *
     * <p>Alternatives Considered: replacing the member above with a LIST of both scopes. Rejected because
     * a list says which scopes exist and not which operation needs which, so a caller would still have to
     * guess; the pair of named members says both, and each operation in the committed contract that
     * departs from the default declares this same member on itself.</p>
     */
    private static final String CUSTOMER_MASTER_SCOPE_EXTENSION_NAME =
            "x-carddemo-customer-master-scope";

    // Assumptions: these two are the specification's lower-case transport token and an
    //   informational token format. Neither validates anything at run time, which is the whole
    //   point of decision D4 recorded on the scheme method below.
    /** The OpenAPI transport token for an HTTP bearer scheme. */
    private static final String BEARER_HTTP_SCHEME = "bearer";

    /** The informational token format advertised to a caller of the internal read paths. */
    private static final String BEARER_TOKEN_FORMAT = "JWT";

    /** The description of what a caller presents on an internal read path, and what refuses it. */
    private static final String INTERNAL_TOKEN_SCHEME_DESCRIPTION = """
            A bearer token minted by the calling service and verified by this one against a signing \
            key both hold. It is not an identity-provider token and it is not interchangeable with \
            one: the identity provider's tokens are accepted by this context's human filter chain, \
            and a token of this scheme is accepted only on the internal read paths. The token names \
            this service as its audience, names its own service as the subject from a closed set this \
            verifier admits, and carries exactly one scope -- so a token minted for another callee, \
            by an unnamed workload, or for the other group of routes is refused rather than accepted \
            for the wrong path. Presenting nothing usable yields 401.

            Two scopes exist and each governs one group of routes. The five decision-path reads \
            require the scope named by x-carddemo-required-scope on this scheme. The two operations \
            that disclose a whole customer record -- the ascending scan and the keyed record read -- \
            require the scope named by x-carddemo-customer-master-scope instead, and each declares \
            that requirement on itself in the contract of record. A token carrying one scope does not \
            satisfy the other group.

            The rationale for choosing a minted token over a client-credentials grant, over mutual \
            TLS and over a static shared header is recorded once on this scheme in the contract of \
            record and is deliberately not repeated here.\
            """;

    /**
     * Builds the starting OpenAPI document for this module, carrying only the members that sit above
     * the document's paths.
     *
     * <p>Four members are set and no others: the information block, one relative server entry, one
     * document-level security requirement, and the single security scheme that requirement names.
     * The paths, the parameters, the responses and the component schemas are left entirely unset so
     * that the publishing library derives them from this module's request handlers, which is what
     * allows the resulting document to disagree with the committed contract when a handler has
     * drifted from it.</p>
     *
     * @return the {@link OpenAPI} document carrying this context's identity, licence, origin and
     *     internal-token requirement, with no path, parameter, response, component schema or tag
     *     declared on it; never {@code null}
     */
    @Bean
    public OpenAPI accountServiceOpenApi() {
        // WHY : Alternatives Considered: decision D7 -- two other ways of publishing this contract
        //       were weighed. The first was to serve the committed file directly as a static
        //       resource, which would make the served document equal the contract by construction.
        //       Rejected, because it destroys the only check that exists: the generated document
        //       earns its keep by being derived from the handlers, so a handler that stops matching
        //       the contract yields a document that stops matching it too, whereas feeding the
        //       contract in as the source reports agreement unconditionally. The second was to let
        //       the generated document be authoritative and demote the committed file to
        //       documentation. Rejected because that file has a consumer authored against it rather
        //       than against this module -- the pending-authorization context builds each path
        //       itself and deserialises each response into its own record -- and the layering rules
        //       forbid either module importing the other's types, so no compiler and no build sees
        //       both halves of the agreement. Generation is done by the parent-managed springdoc
        //       starter this module declares, and the specification family is settled at OpenAPI 3.1
        //       by the migration plan's API decision.
        //
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
        //       key away. Both facts are asserted by config/OpenApiDocumentTest.java, so neither can
        //       drift back into prose that nothing checks.
        //
        // WHY : Assumptions: decision D8 -- the health endpoint is absent from this document, and
        //       its absence is deliberate rather than an oversight. It is contributed by the
        //       framework, it is the one path this context serves to an unauthenticated caller --
        //       admitted by the HEALTH_PATH constant on SecurityConfig, which owns that ruling --
        //       and both the load balancer's target-group registration and the container's own
        //       health check poll it without being able to present a token of any kind. Declaring
        //       it here would create a second hand-maintained account of an endpoint neither this
        //       class nor the contract of record owns, and declaring it while the requirement below
        //       applies document-wide would additionally describe it as requiring a credential it
        //       is exempt from.
        //
        // WHY : Trade-offs: the requirement is asserted ONCE at document level and inherited by
        //       every operation, whereas the contract of record asserts the same single scheme on
        //       each operation separately. The two are semantically equivalent precisely because
        //       there is one scheme and every published operation requires it, so the difference is
        //       structural and not a difference in what a caller must present. Document level is
        //       chosen because the handlers in com.carddemo.account.api carry no per-operation
        //       security annotation, so a requirement expressed here is the only form that reaches
        //       the generated document at all; the alternative would be annotating each handler,
        //       which would put the same fact in as many places as there are handlers.
        return new OpenAPI(SpecVersion.V31)
                .openapi(SPEC_VERSION)
                .info(contractInfo())
                .addServersItem(new Server()
                        .url(ORIGIN_RELATIVE_SERVER_URL)
                        .description(ORIGIN_RELATIVE_SERVER_DESCRIPTION))
                // Assumptions: the requirement's list is EMPTY, and empty is the deliberate value
                //   rather than an unfinished one. Under 3.1 a non-empty list on a scheme that is
                //   neither oauth2 nor openIdConnect is permitted and denotes role names required
                //   for execution -- so the populated form an earlier revision used here was legal
                //   for this document, not the specification violation it was once read as. It is
                //   still dropped, for a reason the legality does not settle: 3.1 defines those
                //   names as neither exchanged nor otherwise defined in-band, so the list is
                //   documentation whichever way it is written, and the populated form reads as an
                //   OAuth scope to every 3.0-era reader and tool that meets it. Trade-offs: the
                //   scope stops being machine-readable HERE, so it is published on the scheme
                //   instead -- as the x- extension below, taken from the same constant the
                //   enforcing filter chain composes its required authority from, so what this
                //   document advertises and what InternalApiSecurityConfig demands still cannot
                //   drift apart. That also makes this the same shape the other six contexts
                //   publish, so the one populated list in the fleet stops being a difference a
                //   reader has to explain.
                .addSecurityItem(new SecurityRequirement().addList(INTERNAL_TOKEN_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(INTERNAL_TOKEN_SCHEME_NAME, internalServiceTokenScheme()));
    }

    /**
     * Assembles the information block that identifies the published document.
     *
     * <p>Every value it carries is a constant declared on this class. The title, the version and the
     * licence are reproduced from the contract of record so that a comparison finds those members
     * equal; the summary and the description are worded qualitatively for the reason recorded as
     * decision D6 on the class body above. Both the summary and the description are set because an
     * OpenAPI 3.1 document distinguishes them and the contract of record declares both.</p>
     *
     * @return the {@link Info} block carrying the title, the document version, the short summary,
     *     the long description and the licence of this context's published API; never {@code null}
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
     * Declares the single reusable security scheme every published internal operation is documented as
     * requiring.
     *
     * <p>The scheme is descriptive only. It tells a caller what to present and what to expect when
     * it presents nothing usable; it enforces none of that itself.</p>
     *
     * <p>Assumptions: ONE scheme carries TWO scopes rather than two schemes carrying one each. The
     * credential is the same in every respect a scheme describes -- the same transport, the same token
     * format, the same signing key, the same audience -- and only the scope claim inside it differs, so
     * two schemes would duplicate every one of those members in order to vary a value the specification
     * has no field for. The differing value is published as the two named extensions instead, and the
     * operations that depart from the default declare theirs on themselves.</p>
     *
     * @return the {@link SecurityScheme} of HTTP type declaring the bearer transport, its
     *     informational token format, both published scopes and the description of what a caller must
     *     present on an internal read path; never {@code null}
     */
    private static SecurityScheme internalServiceTokenScheme() {
        // WHY : Assumptions: decision D4 -- this object is METADATA and not policy, and the
        //       distinction matters because a reader who mistook it for policy would look here for
        //       a security defect that could not be here. Nothing on it is consulted at run time.
        //       Enforcement for the paths this document publishes belongs entirely to
        //       InternalApiSecurityConfig in this package, which verifies a presented token against
        //       the signing key supplied by the carddemo.internal-identity.signing-key property, and
        //       requires per route group the authority it composes from whichever of the two scope
        //       constants named above governs that group.
        //       Everything else this context serves is governed by SecurityConfig, which owns the
        //       identity-provider filter chain, the conversion of that provider's group claim into
        //       authorities through com.carddemo.common.security.JwtRoleConverter, and the ordering
        //       of the shared correlation filter. Deleting this method would change what the
        //       document says and change nothing about what the service accepts.
        //
        // WHY : Assumptions: no issuer, key, audience, client identifier, pool identifier, region or
        //       address appears on this object or anywhere else in this class. Every such value
        //       arrives from configuration at run time, and a scheme description is published to
        //       whoever can fetch the document, so a value written here would be both redundant and
        //       readable by its intended attacker.
        // WHY : Trade-offs: the required scope is published as a vendor extension rather than in the
        //       security requirement's list, which is where a reader familiar with oauth2 would look
        //       for it. The reasoning is recorded at the requirement site; in short, 3.1 permits the
        //       list to name roles but defines them as not exchanged in-band, so the value is
        //       documentation either way and an x- member says plainly that it is this system's
        //       vocabulary rather than an OAuth scope a token endpoint would issue. The value is
        //       read from the same constant InternalApiSecurityConfig composes its required
        //       authority from, so the two cannot disagree.
        // WHY : Refactoring Rationale: the extension now names BOTH internal scopes rather than one.
        //       A single value was accurate while one authority governed every internal address, and
        //       it stopped being accurate when the two whole-customer-record reads moved onto
        //       SCOPE_CUSTOMER_MASTER_READ. Publishing the one value would now tell a reader that a
        //       token carrying the decision scope reaches every internal operation this document
        //       declares, which is exactly the escalation the split removed -- so the wrong
        //       documentation here would describe a system less safe than the one that ships, and a
        //       caller acting on it would mint a token its request is refused with. Both values are
        //       read from the same constants InternalApiSecurityConfig composes its two required
        //       authorities from, so neither can disagree with what is enforced.
        // WHY : Trade-offs: the extension carries the two scopes as a list on ONE scheme rather than
        //       declaring two schemes. Both authorities are verified by the same chain, the same
        //       decoder and the same issuer and audience validators -- the credential is identical
        //       and only the claim value differs -- so two schemes would describe one mechanism
        //       twice. Which of the two a given operation requires is stated per operation in the
        //       committed contract document, which is where an operation-specific fact belongs.
        // Assumptions: the extension is added in a separate statement rather than chained, because
        //   addExtension on this type returns void where every other setter returns the scheme, so
        //   chaining it does not compile.
        SecurityScheme scheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme(BEARER_HTTP_SCHEME)
                .bearerFormat(BEARER_TOKEN_FORMAT)
                .description(INTERNAL_TOKEN_SCHEME_DESCRIPTION);
        // Refactoring Rationale: the single account-context read scope this extension named was split into
        //   one scope per operation family, so all three are published rather than the one. Publishing the
        //   withdrawn name would tell a caller to mint a scope no verifier admits.
        scheme.addExtension(REQUIRED_SCOPE_EXTENSION_NAME,
                List.of(InternalServiceToken.SCOPE_CARD_XREF_READ,
                        InternalServiceToken.SCOPE_ACCOUNT_READ,
                        InternalServiceToken.SCOPE_CUSTOMER_READ,
                        InternalServiceToken.SCOPE_CUSTOMER_MASTER_READ));
        return scheme;
    }
}
