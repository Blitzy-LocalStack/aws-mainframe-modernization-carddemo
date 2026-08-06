/**
 * Spring configuration for the authentication and user-administration bounded context.
 *
 * <h2>What belongs here, and what does not</h2>
 *
 * <p>Every class in this package binds one external concern to the service while the application
 * context is being built. None of them carries a business rule. The sign-on branch, the field
 * validation transcribed from the four user-administration programs and the keyset paging behaviour
 * live in the service, mapper and repository packages of this context, so a reader looking for why a
 * user creation is refused, or why a sign-on returns a challenge rather than tokens, will not find
 * the answer here.</p>
 *
 * <p>Assumptions: this charter describes the package's target contract as the migration plan assigns
 * it, not the set of files present beside it today. The plan lands its artifacts in order, so a class
 * named below with no file yet is <b>planned</b> rather than missing. At the checkpoint that authored
 * this charter the directory holds this file and {@code SecurityConfig}.</p>
 *
 * <h2>The configuration classes</h2>
 *
 * <ul>
 *   <li>{@code SecurityConfig} - <b>landed</b>. It builds the resource server filter chain, decides
 *       which routes require the administrator authority and which two are reachable with no token,
 *       supplies the decoder that checks a presented token's kind, minting client and scope, converts
 *       the {@code cognito:groups} claim into Spring Security authorities, and leaves the health
 *       endpoint unauthenticated. Trade-offs: the load balancer target group and the container health
 *       check both poll that endpoint before any credential exists, so requiring a token there would
 *       fail every probe and take a healthy task out of service.</li>
 *   <li>{@code OpenApiConfig} - <b>planned</b>. It supplies the OpenAPI 3.1 metadata this service
 *       publishes, aligned to the contract committed at
 *       {@code src/main/resources/openapi/auth-api.yaml}. Assumptions: that contract has a real
 *       consumer, the browser application's typed client, which is why the metadata declared in code
 *       and the committed contract are expected to agree rather than drift.</li>
 *   <li>{@code DataSourceConfig} - <b>planned</b>. It pins the JDBC {@code search_path} to the
 *       {@code auth} schema and sizes the connection pool. Assumptions: {@code auth} is the single
 *       schema this context owns, so pinning the search path is what keeps an unqualified table name
 *       resolving inside this service's own schema and nowhere else.</li>
 * </ul>
 *
 * <h2>Deliberately absent from this package</h2>
 *
 * <p>Alternatives Considered: two classes a reader might expect here were each weighed and left out,
 * so that an absence is not mistaken for an oversight and filled in.</p>
 *
 * <ul>
 *   <li>There is no separate {@code JwtDecoderConfig}, which is what the reporting context uses for
 *       the same concern. The decoder is a bean of {@code SecurityConfig} instead, because it is part
 *       of the same request-authentication decision that class already owns and because this
 *       package's inventory is a closed set of three. Trade-offs: the two contexts therefore differ
 *       in file layout for one identical concern; what that buys here is that a reader looking for
 *       what this service accepts as a credential finds all of it in one file.</li>
 *   <li>There is no {@code SqsConfig} and no {@code BatchConfig}. This context neither publishes nor
 *       consumes a message and runs no scheduled job, and this module's POM declares neither an SQS
 *       client nor the batch starter, so there would be nothing for either to configure.</li>
 * </ul>
 *
 * <h2>How shared kernel components reach this context</h2>
 *
 * <p>Assumptions: the component scan implied by this context's application class is rooted at
 * {@code com.carddemo.auth}, while every shared kernel type lives under {@code com.carddemo.common},
 * outside that root. Shared components are therefore never discovered automatically; each is
 * registered deliberately. {@code SecurityConfig} registers
 * {@code com.carddemo.common.web.CorrelationIdFilter} and
 * {@code com.carddemo.common.security.JwtRoleConverter}, because both take effect as part of the
 * request pipeline it defines. The remainder of the shared kernel - the error shapes, the page
 * envelope, the timestamp formatter, the validation and codec helpers - needs no registration and is
 * used as plain imports.</p>
 *
 * <h2>Labelling of the rationale above</h2>
 *
 * <p>Assumptions: three of the four canonical labels the project's explainability rule names appear
 * above, in the plural unparenthesised form the tree uses, and {@code Refactoring Rationale:} is
 * deliberately absent from this charter rather than overlooked: that label is reserved for genuinely
 * replaced code, and this package replaces nothing - the COBOL baseline it is transcribed from is
 * never edited, and no earlier Java configuration existed here to supersede. The written convention
 * is {@code docs/CODE_DOCUMENTATION_STANDARD.md}, and the Checkstyle Javadoc gate bound to the Maven
 * {@code validate} phase checks that this documentation is present but cannot check that its labels
 * are accurate, which is why the choice of label is recorded here in prose.</p>
 */
package com.carddemo.auth.config;
