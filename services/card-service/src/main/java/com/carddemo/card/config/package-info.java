/**
 * Spring configuration for the card bounded context, covering stateless request
 * security, published API metadata, and datasource wiring.
 *
 * <p>Every class in this package binds one external concern to the service while
 * the application context is being built. None of them carries a business rule.
 * The validation chains, the keyset paging behaviour and the field masking rules
 * encoded from the baseline card programs live in the service, mapper and
 * repository packages of this context instead, so a reader looking for why a card
 * update is refused, or why a primary account number arrives truncated on a
 * response, will not find the answer here.</p>
 *
 * <h2>The three configuration classes</h2>
 *
 * <p>This package holds exactly three classes, each with a narrow and separately
 * testable responsibility.</p>
 *
 * <ul>
 *   <li>{@code SecurityConfig} builds the resource server filter chain that
 *       validates a signed Cognito token on every request, converts the
 *       {@code cognito:groups} claim carried by that token into Spring Security
 *       authorities, guards the administrative routes so that only the
 *       administrator group reaches them, and leaves the health endpoint
 *       unauthenticated because the load balancer target group and the container
 *       health check both poll it before any credential exists. Authorization is
 *       therefore decided from a validated token rather than from anything the
 *       caller supplies about itself. The baseline defines all three card
 *       transactions with resource level and command level security switched off
 *       (see {@code app/csd/CARDDEMO.CSD}), so that ground was covered outside
 *       the COBOL by an external security manager, and that component has no
 *       in-process counterpart on this side. Redistributing its job onto token
 *       validation here, and onto task role policy in the infrastructure tree, is
 *       a platform-capability difference rather than a change of business
 *       behaviour.</li>
 *   <li>{@code OpenApiConfig} supplies the OpenAPI 3.1 metadata this service
 *       publishes, aligned to the contract committed at
 *       {@code src/main/resources/openapi/card-api.yaml}. That contract is an
 *       interface with a real consumer, because the browser application's typed
 *       client is generated from it, which is why the metadata declared here and
 *       the committed contract are expected to agree rather than drift.</li>
 *   <li>{@code DataSourceConfig} pins the JDBC {@code search_path} to the
 *       {@code card} schema, the single schema this context owns, and sizes the
 *       HikariCP connection pool. Pinning the search path is what keeps an
 *       unqualified table name in a query resolving inside this service's own
 *       schema and nowhere else.</li>
 * </ul>
 *
 * <h2>Deliberately absent from this package</h2>
 *
 * <p>Three classes a reader might reasonably expect in a service configuration
 * package are missing on purpose. Each is named below with its specific reason,
 * so that an absence is not mistaken for an oversight and filled in.</p>
 *
 * <ul>
 *   <li>There is no {@code SqsConfig}. Messaging configuration is scoped to the
 *       two contexts that actually exchange messages, and this is not one of
 *       them: the card screens neither publish nor consume a message. The build
 *       agrees, because {@code services/card-service/pom.xml} declares no SQS
 *       client, no Spring Cloud AWS module and no AWS SDK messaging dependency,
 *       so there is no listener container to configure and no queue for a
 *       listener to read. The service configuration carries no queue or
 *       messaging property either.</li>
 *   <li>There is no {@code BatchConfig}. None of the migrated card programs is a
 *       scheduled job, and {@code spring-boot-starter-batch} is absent from this
 *       module's classpath, so there is no job repository to define. The
 *       sequential card reader of the baseline becomes the read path on the card
 *       repository rather than a chunk oriented step.</li>
 *   <li>There is no exception handler class. The handler that turns an exception
 *       into the shared error payload is
 *       {@code com.carddemo.common.error.GlobalExceptionHandler} in the shared
 *       kernel, and it is registered once by {@code CardApplication}. Declaring a
 *       second handler here would give one concern two owners and make which of
 *       them answers a given exception depend on bean ordering rather than on
 *       anything written down.</li>
 * </ul>
 *
 * <h2>How shared kernel components reach this context</h2>
 *
 * <p>This is the most easily misread thing about this package, so it is stated
 * explicitly. The component scan implied by the annotation on
 * {@code CardApplication} is rooted at {@code com.carddemo.card}, while every
 * shared kernel type lives under {@code com.carddemo.common}, which sits outside
 * that root. Shared components are therefore never discovered automatically;
 * each one is registered deliberately, and those registrations are split across
 * two places.</p>
 *
 * <ul>
 *   <li>{@code CardApplication} imports
 *       {@code com.carddemo.common.error.GlobalExceptionHandler} and
 *       {@code com.carddemo.common.observability.MetricsConfig}, because neither
 *       belongs to the security filter chain and both apply to the application as
 *       a whole.</li>
 *   <li>{@code SecurityConfig} in this package registers the remaining two,
 *       {@code com.carddemo.common.web.CorrelationIdFilter} and
 *       {@code com.carddemo.common.security.JwtRoleConverter}, because both take
 *       effect as part of the request pipeline that class defines and their
 *       position within that pipeline is part of their contract.</li>
 * </ul>
 *
 * <p>The scan root is never widened to {@code com.carddemo} to shorten either
 * list. Widening it would pull every shared kernel component into every service
 * at once, including the ones a given context has no use for, and would replace
 * four explicit registrations that can be read in two files with an implicit set
 * that changes silently whenever the shared kernel gains a component.</p>
 *
 * <p>The rest of the shared kernel needs no registration at all. Its records and
 * static utilities, among them {@code ApiError}, {@code AbendDetail},
 * {@code PageResponse}, {@code TimestampFormatter} and the validation and codec
 * helpers, are used as plain imports wherever they are needed, which is why they
 * appear in neither list above.</p>
 */
package com.carddemo.card.config;
