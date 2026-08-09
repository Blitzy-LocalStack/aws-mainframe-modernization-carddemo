/**
 * Holds the context-building wiring of the sign-on and user-administration context: the stateless JWT
 * security chain, which is landed, together with the OpenAPI metadata and datasource bindings the
 * migration plan assigns to this package.
 *
 * <p>Every type in this package binds one external concern to the service while the application
 * context is being built, and none of them decides a business outcome. The sign-on branch, the field
 * validation transcribed from the four user-administration programs, and the keyset browse behaviour
 * live in the service, repository and mapper packages of this context. A reader looking for why a
 * user creation is refused, or why a sign-on returns a challenge instead of a token, will not find
 * the answer here.</p>
 *
 * <h2>Why the wiring is a package of its own</h2>
 *
 * <p>Cross-cutting wiring is held apart from behaviour so that the layering rule set can state a
 * boundary which is mechanically checkable: request handling belongs to the {@code api} package,
 * business rules to {@code service}, persistence types to {@code domain} and {@code repository}, and
 * transport shapes to {@code dto}. None of those concerns appears here, and no configuration type
 * appears in any of them. Keeping the two apart means a change to how a token is validated cannot
 * quietly alter what a user-administration rule permits, because the two are not co-located and
 * cannot share private state.</p>
 *
 * <h2>The configuration types</h2>
 *
 * <p>Assumptions: the inventory below is the package contract the migration plan assigns, and every
 * entry is now on disk. Measured rather than asserted: this directory holds {@code SecurityConfig.java},
 * {@code CognitoIdentityConfig.java}, {@code OpenApiConfig.java}, {@code DataSourceConfig.java} and this
 * charter, and nothing else. Naming a type here is neither a claim that it exists nor permission to
 * substitute something else for it; the present contents are read from the directory or from
 * {@code mvn -f services/auth-service/pom.xml test} rather than from this comment.</p>
 *
 * <p>Refactoring Rationale: an earlier revision of this block opened "the inventory is a closed set of
 * three, each owning exactly one binding" and described all three in the present tense, as though each
 * were on disk. Only {@code SecurityConfig} was. A charter is consulted precisely to learn which bindings
 * are settled, so describing absent types as present sends a reader looking for a file that was never
 * written -- and, worse here than in most packages, invites them to conclude that a concern is already
 * discharged and to leave it undone. A later revision fixed that by marking each entry PLANNED or
 * AUTHORED; the markers are now all the same, because the provider client, the document metadata and the
 * datasource pin have all landed and the set is four rather than three. The markers stay per entry and
 * are re-read from the directory whenever this file is touched, which is how the count moved twice.</p>
 *
 * <dl>
 *   <dt>{@code SecurityConfig} -- LANDED</dt>
 *   <dd>The stateless resource-server filter chain: which routes demand the administrator authority,
 *       which are reachable with no token at all, and how a presented token is decoded and checked.
 *       It also supplies the conversion of the {@code cognito:groups} claim into Spring Security
 *       authorities, so the two admitted user-type values reach an authorization decision as
 *       authorities rather than as a field a caller supplied.</dd>
 * </dl>
 *
 * <p>What that chain decides, in the order it decides it, is four rules and a refusal. The health group
 * is reachable with no credential, because the load balancer target group and the container health check
 * both poll it before any credential exists and demanding a token there would withdraw a healthy task
 * from service. The management endpoints -- the three the base exposure list publishes and the whole
 * {@code /actuator} namespace behind them -- are granted by NETWORK POSITION rather than by any
 * authority, so the tokenless collector sidecar can scrape them and nothing off the box can read them.
 * The three token-issuing paths are open, because a caller reaching them holds no token yet. The two
 * user-administration patterns require the administrator authority. Everything else is DENIED.</p>
 *
 * <p>Refactoring Rationale: this description replaces a Trade-offs note claiming "the health endpoint is
 * left unauthenticated although every other route is guarded", which was true of neither end of the
 * sentence. Three further paths were open, not one; and the chain ended in {@code authenticated()}, so
 * whatever no rule matched -- including the {@code env}, {@code configprops} and {@code flyway}
 * endpoints the development profile publishes -- was reachable by any validly signed token, including
 * one carrying no CardDemo group at all. The chain now ends in {@code denyAll()} and matches the
 * management namespace explicitly, which is what makes "everything else is denied" a description rather
 * than an aspiration.</p>
 *
 * <dl>
 *   <dt>{@code CognitoIdentityConfig} -- LANDED</dt>
 *   <dd>The administrative identity-provider client this context provisions pool accounts through.
 *       It is a separate file from the filter chain rather than a bean method on it because the two
 *       hold opposite ends of the identity relationship: the chain decides whether a presented token
 *       is accepted and needs no provider permission at all, while this client is used with
 *       administrative user-management permissions on the pool. Assumptions: the region, the
 *       credentials and the endpoint are resolved by the provider SDK's own default chains and by no
 *       property declared in this package, so a deployed task takes its region from the execution
 *       environment and its credentials from the task role.</dd>
 *
 *   <dt>{@code OpenApiConfig} -- LANDED</dt>
 *   <dd>The OpenAPI 3.1 document metadata this service publishes -- title, version and security
 *       scheme -- aligned to the contract committed under this module's
 *       {@code src/main/resources/openapi} directory. Assumptions: that contract has a real consumer
 *       in the browser application's typed client {@code ui/src/api/auth.ts}, and it is already
 *       verified against this module's enforced rules by {@code AuthApiContractTest} and against that
 *       client by {@code ui/src/api/contracts.test.ts}. What this type adds is the SERVED document's
 *       metadata and not the contract itself, which is why the two are expected to agree rather than
 *       drift apart and why the type belongs beside the chain that enforces the same authorities.</dd>
 *
 *   <dt>{@code DataSourceConfig} -- LANDED</dt>
 *   <dd>Connection-pool behaviour, and the verification that the JDBC {@code search_path} really
 *       resolves to the {@code auth} schema at the connection boundary. Assumptions: {@code auth} is
 *       the only schema this context owns, so pinning the search path is what keeps an unqualified
 *       table name resolving inside this service's own schema and nowhere else, and the pin belongs at
 *       the connection boundary rather than in each statement so that a query omitting the qualifier
 *       cannot reach another context's tables. Assumptions: the PIN itself is declared once, in this
 *       module's {@code application.yml} under
 *       {@code spring.datasource.hikari.connection-init-sql}, which records at its own head why it is
 *       expressed there; this type does not restate it. What this type contributes is the check that
 *       the setting took EFFECT, because the raw setting reports only what was asked for. A pin
 *       declared in both places would be two authorities over one connection property.</dd>
 * </dl>
 *
 * <h2>Deliberately absent from this package</h2>
 *
 * <p>Alternatives Considered: a separate type holding the token decoder was weighed and left out.
 * The decoder is a bean of {@code SecurityConfig} instead, because deciding what this service accepts
 * as a credential is part of the same request-authentication decision that type already owns.
 * Trade-offs: a reader expecting one file per bean finds two responsibilities in one file; what that
 * buys is that everything governing whether a request is authenticated is readable in one place
 * rather than assembled from two.</p>
 *
 * <p>There is no queue configuration and no batch-job configuration. This context neither consumes
 * nor produces a message, and this module declares neither a messaging client nor the batch starter, so
 * neither would have anything to configure.</p>
 *
 * <p>Refactoring Rationale: the sentence above previously also read "and runs no scheduled job", and that
 * clause is withdrawn because it is no longer true. {@code CognitoIdentityConfig} now carries
 * {@code @EnableScheduling}, and {@code com.carddemo.auth.service.IdentitySyncService} declares one fixed
 * delay job: the reconciliation pass that applies the changes {@code auth.users} owes the managed user pool
 * when the request that recorded them did not survive to apply them. Assumptions: the job is enabled on the
 * provider client's own configuration rather than on a configuration of its own, because converging that
 * provider is the only reason it exists; the rationale is recorded at the annotation. Trade-offs: a reader
 * looking for scheduling in a file named for it finds nothing, which is why this paragraph names the file
 * that carries it.</p>
 *
 * <p>There is no web-layer or serialization configuration in this package, and the framework's own
 * defaults for content negotiation are accepted unchanged rather than restated.</p>
 *
 * <p>Refactoring Rationale: the sentence this replaces said that "the shared kernel's money codec is
 * never registered here", which read as a claim that no money codec is registered in this service at
 * all -- and the section below states the opposite, that the kernel's auto-configuration contributes
 * one to every service that carries the kernel module. Both halves were describing different subjects
 * with the same word: nothing in THIS PACKAGE declares the module, and the module is nonetheless
 * present in this service's context. The owner is
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, whose {@code carddemoMoneyModule} bean
 * contributes it under {@code @ConditionalOnMissingBean(MoneyModule.class)} and
 * {@code @ConditionalOnClass(JacksonModule.class)}.
 *
 * <p>Assumptions: that registration is correct here rather than merely harmless, and it is left in
 * place deliberately. No transport shape of this context carries a monetary value, so the module has
 * nothing in this service to serialise -- but the kernel contributes it uniformly rather than per
 * service, and a per-service exclusion would be a second decision about one contract, made in the one
 * place least able to see the others. Excluding it would also be the more dangerous default: a context
 * that later gained a monetary field would silently serialise it as a JSON number.</p>
 *
 * <h2>How shared kernel components reach this context</h2>
 *
 * <p>Assumptions: the component scan for this service is rooted at {@code com.carddemo.auth}, while
 * every shared kernel type lives under {@code com.carddemo.common}, outside that root. Shared
 * components are therefore never discovered by scanning. They arrive instead through the kernel's own
 * auto-configuration entry, {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, which
 * contributes the correlation filter, the common-tag meter filter, the money codec module and the
 * single error advice to any service placing the kernel module on its path. Nothing in this package
 * re-declares any of them, and nothing here needs to: a second declaration would be a second
 * authority over one contract, which is the outcome the shared kernel exists to prevent.</p>
 *
 * <p>Assumptions: that entry is named rather than described, because "the kernel's auto-configuration"
 * is not a unique referent -- a reader cannot grep for it, and this file previously left the money
 * codec's owner unnameable for exactly that reason. Each of the four contributions is guarded by a
 * missing-bean condition on its own type or bean name, which is why a service that genuinely needs to
 * replace one does so by declaring a bean of that type rather than by switching the entry off.</p>
 *
 * <p>Refactoring Rationale: this package once declared the shared correlation filter as a bean of its
 * own, and that declaration is withdrawn. The kernel's auto-configuration already contributes a
 * registration for the filter over every request path at a fixed order, and the condition guarding
 * that registration tests for the name of its own bean, which a differently named bean here did not
 * satisfy, so both registrations survived and one filter sat in the chain twice. The filter's
 * once-per-request guard made the duplicate harmless rather than visible, which is exactly why it had
 * to be withdrawn deliberately instead of being left for someone to notice.</p>
 *
 * <p>What {@code SecurityConfig} does register from the kernel is narrower and deliberate: the
 * authority converter and the access-token validator, because each takes effect as part of the
 * request pipeline that type defines, and the validator cannot be contributed from outside the
 * decoder it is installed into. The remainder of the kernel -- the error shapes, the page envelope,
 * the timestamp formatter, the validation and codec helpers -- needs no registration and is reached
 * as ordinary type imports.</p>
 *
 * <h2>What this package replaces</h2>
 *
 * <p>The baseline authenticates against a VSAM security file whose record carries an eight-character
 * password in the clear. Its resource definition -- {@code DEFINE FILE(USRSEC)} at lines 88 to 99 of
 * {@code app/csd/CARDDEMO.CSD} -- declares <b>no forward-recovery log</b> and <b>no data-change
 * journaling</b>: {@code RECOVERY(NONE)} and {@code FWDRECOVLOG(NO)} at line 96, and
 * {@code JOURNAL(NO)} at line 94 alongside {@code JNLREAD(NONE)}, {@code JNLSYNCREAD(NO)},
 * {@code JNLUPDATE(NO)} and {@code JNLADD(NONE)} at line 95. Refactoring Rationale: this
 * sentence read that the definition "declares neither recovery nor journalling", which inverted what
 * the file actually says. The definition is explicit on both, and one attribute reads the other way --
 * {@code JNLSYNCWRITE(YES)} at line 96 -- so a reader taking the old wording at face value would
 * expect the attributes to be absent, find them present and disagreeing, and have no way to tell
 * which reading was intended. Naming each attribute and its line is what makes the claim checkable
 * against the file rather than against a paraphrase of it, and the distinction is not pedantic: an
 * attribute deliberately set to none is a recorded operational decision, while an absent attribute is
 * a default nobody chose.</p>
 *
 * <p>The Java encodes identity as a signed token minted outside this service and validated on every
 * request, so no password field is carried forward into this context at all; the divergence is
 * documented in the migration's traceability record rather than treated as a like-for-like port. The
 * admitted user-type domain is the pair of values the shared communication-area copybook defines, and
 * this package is where those values become the authority a guarded route is matched against.</p>
 */
package com.carddemo.auth.config;
