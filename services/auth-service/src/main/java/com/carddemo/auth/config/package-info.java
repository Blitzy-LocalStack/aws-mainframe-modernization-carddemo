/**
 * Stateless JWT security, OpenAPI metadata, datasource and search_path wiring for the sign-on and
 * user-administration context.
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
 * <p>The inventory is a closed set of three, each owning exactly one binding.</p>
 *
 * <dl>
 *   <dt>{@code SecurityConfig}</dt>
 *   <dd>The stateless resource-server filter chain: which routes demand the administrator authority,
 *       which are reachable with no token at all, and how a presented token is decoded and checked.
 *       It also supplies the conversion of the {@code cognito:groups} claim into Spring Security
 *       authorities, so the two admitted user-type values reach an authorization decision as
 *       authorities rather than as a field a caller supplied. Trade-offs: the health endpoint is left
 *       unauthenticated although every other route is guarded, because the load balancer target group
 *       and the container health check both poll it before any credential exists; demanding a token
 *       there would fail every probe and withdraw a healthy task from service.</dd>
 *
 *   <dt>{@code OpenApiConfig}</dt>
 *   <dd>The OpenAPI 3.1 document metadata this service publishes -- title, version and security
 *       scheme -- aligned to the contract committed under this module's
 *       {@code src/main/resources/openapi} directory. Assumptions: that contract has a real consumer
 *       in the browser application's typed client, which is why metadata declared in code and the
 *       committed contract are expected to agree rather than drift apart.</dd>
 *
 *   <dt>{@code DataSourceConfig}</dt>
 *   <dd>Connection-pool behaviour, and the pin of the JDBC {@code search_path} to the {@code auth}
 *       schema at the connection boundary. Assumptions: {@code auth} is the only schema this context
 *       owns, so pinning the search path is what keeps an unqualified table name resolving inside
 *       this service's own schema and nowhere else. The pin sits at the connection boundary rather
 *       than in each statement so that a query which omits the qualifier cannot reach another
 *       context's tables.</dd>
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
 * nor produces a message and runs no scheduled job, and this module declares neither a messaging
 * client nor the batch starter, so neither would have anything to configure.</p>
 *
 * <p>There is no web-layer or serialization configuration. This context carries no monetary value, so
 * the shared kernel's money codec is never registered here, and the framework's own defaults for
 * content negotiation are accepted unchanged rather than restated.</p>
 *
 * <h2>How shared kernel components reach this context</h2>
 *
 * <p>Assumptions: the component scan for this service is rooted at {@code com.carddemo.auth}, while
 * every shared kernel type lives under {@code com.carddemo.common}, outside that root. Shared
 * components are therefore never discovered by scanning. They arrive instead through the kernel's own
 * auto-configuration entry, which contributes the correlation filter, the common-tag meter filter,
 * the money codec module and the single error advice to any service placing the kernel module on its
 * path. Nothing in this package re-declares any of them, and nothing here needs to: a second
 * declaration would be a second authority over one contract, which is the outcome the shared kernel
 * exists to prevent.</p>
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
 * password in the clear, and its resource definition declares neither recovery nor journalling. The
 * Java encodes identity as a signed token minted outside this service and validated on every
 * request, so no password field is carried forward into this context at all; the divergence is
 * documented in the migration's traceability record rather than treated as a like-for-like port. The
 * admitted user-type domain is the pair of values the shared communication-area copybook defines, and
 * this package is where those values become the authority a guarded route is matched against.</p>
 */
package com.carddemo.auth.config;
