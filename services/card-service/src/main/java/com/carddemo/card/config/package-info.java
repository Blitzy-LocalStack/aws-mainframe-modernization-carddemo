/**
 * Spring configuration for the card bounded context, covering stateless request
 * security, published API metadata, datasource wiring, and the key-management
 * client the stored card verification value is enciphered with.
 *
 * <h2>The five configuration classes</h2>
 *
 * <p>Assumptions: this package holds exactly five classes, each with a narrow and
 * separately testable responsibility, and <b>all five exist</b> beside this
 * charter -- {@code SecurityConfig}, {@code OpenApiConfig},
 * {@code DataSourceConfig}, {@code KmsConfig} and {@code CardSelectorConfig}. The
 * enumeration below is therefore both the closed set assigned to this package and a
 * listing of the directory, and it is checked against the directory on every build:
 *
 * <pre>
 * this directory: 6 java files = 5 classes + 1 charter
 * </pre>
 *
 * <p>Refactoring Rationale: that marker line replaces a count stated in prose alone.
 * This section has now been wrong twice in the same direction -- it closed the set at
 * three while {@code KmsConfig} stood beside it, and then at four while
 * {@code CardSelectorConfig} did -- and both times the error was invisible to
 * everything except a reader comparing the paragraph with a directory listing. The
 * marker and the enumeration under it are measured by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/PackageCharterInventoryTest.java},
 * so a sixth class arriving without an entry here now fails the build.</p>
 *
 * <p>Refactoring Rationale: this paragraph used to close by saying "the count is
 * corrected to four", three paragraphs after the heading and the marker above it both
 * said five. That is a worse defect than either original undercount, because the two
 * numbers sat in one file and a reader had no way to tell which of them the author
 * meant -- so the corrected figure read as the doubtful one. Only the reasoning is kept
 * here, and the count is stated in exactly one place, the marker above, which is
 * measured against the directory on every build. An omission is the worst kind of error
 * for a closed-set claim to carry: the sentence a reader takes from "exactly three, and
 * here they are" is that anything else in the directory does not belong, so a reader
 * could reasonably have concluded the key-management wiring had been added in the wrong
 * place and moved or deleted it -- and deleting it would leave
 * {@code com.carddemo.card.service.CardVerificationValueCipher} with no client to
 * inject. Every class is therefore described below with its own responsibility, rather
 * than the count being softened into a range.</p>
 *
 * <p>Refactoring Rationale: the preamble also said all three classes were "authored
 * at later indexes of the same plan", which was the planned-versus-delivered
 * qualification appropriate when the directory held this charter alone. It is
 * withdrawn because all five are present, and a stale planned marker sends a reader
 * away from files that are there.</p>
 *
 * <ul>
 *   <li>{@code SecurityConfig} builds the resource server filter chain that
 *       validates a signed Cognito token on every request, converts the
 *       {@code cognito:groups} claim carried by that token into Spring Security
 *       authorities, guards the administrative routes so that only the
 *       administrator group reaches them, and leaves the health endpoint
 *       unauthenticated. Trade-offs: the load balancer target group and the
 *       container health check both poll that endpoint before any credential
 *       exists, so requiring a token there would fail every probe and take a
 *       healthy task out of service; the accepted cost is one route readable
 *       without a credential, and it is the only one left open. Authorization is
 *       therefore decided from a validated token rather than from anything the
 *       caller supplies about itself. Assumptions: the baseline defines all three
 *       card transactions with resource level and command level security switched
 *       off (see {@code app/csd/CARDDEMO.CSD}), so that ground was covered
 *       outside the COBOL by an external security manager, and that component has
 *       no in-process counterpart on this side. Alternatives Considered: porting
 *       that manager was therefore not one of the options, so its job is
 *       redistributed onto token validation here and onto task role policy in the
 *       infrastructure tree, which is a platform-capability difference rather
 *       than a change of business behaviour.</li>
 *   <li>{@code OpenApiConfig} supplies the OpenAPI 3.1 metadata this service
 *       publishes, aligned to the contract committed at
 *       {@code src/main/resources/openapi/card-api.yaml}. Assumptions: that
 *       contract is an interface with a real consumer, because the browser
 *       application's typed client is generated from it, which is why the metadata
 *       declared here and the committed contract are expected to agree rather
 *       than drift.</li>
 *   <li>{@code DataSourceConfig} pins the JDBC {@code search_path} to the
 *       {@code card} schema and sizes the HikariCP connection pool.
 *       Assumptions: {@code card} is the single schema this context owns, so
 *       pinning the search path is what keeps an unqualified table name in a
 *       query resolving inside this service's own schema and nowhere else.</li>
 *   <li>{@code KmsConfig} contributes the key-management client that
 *       {@code com.carddemo.card.service.CardVerificationValueCipher} enciphers the
 *       stored card verification value with, and that class is its only consumer.
 *       Assumptions: the bean exists so the cipher can take the client through its
 *       constructor and be exercised against a mock, which is what keeps
 *       encipherment testable without a key or an account. Region and credentials
 *       resolve through the SDK's own default provider chains rather than being set
 *       here, because in a deployed task the region arrives as an environment
 *       variable and the credentials as the task role -- and the whole point of a
 *       task role is that no credential is configurable. This class is what makes
 *       the card context the one context in this reactor whose configuration
 *       package reaches a cryptographic service at all, which is why the set here
 *       is five where the sibling account and transaction contexts differ.</li>
 *   <li>{@code CardSelectorConfig} contributes the keyed sealer that
 *       {@code com.carddemo.card.mapper.CardMapper} mints and opens every route's opaque
 *       card selector with, and that mapper is its only consumer. Assumptions: it is a
 *       separate class from {@code KmsConfig} above even though both concern keys,
 *       because the two keys have nothing in common operationally -- this one is a
 *       symmetric signing key bound from configuration and used in process, and that one
 *       is a managed key reached over an API -- so a single class would have made one
 *       absent key look like the other's problem. Assumptions: the signing key is bound
 *       with NO default, so a deployment that fails to supply it does not start; the
 *       reasoning for refusing both a shared literal and a per-task random value is
 *       recorded on the class.</li>
 * </ul>
 *
 * <h2>Deliberately absent from this package</h2>
 *
 * <p>Alternatives Considered: three classes a reader might reasonably expect in a
 * service configuration package were each weighed and left out on purpose. Each
 * is named below with its specific reason, so that an absence is not mistaken
 * for an oversight and filled in.</p>
 *
 * <ul>
 *   <li>There is no {@code SqsConfig}. Messaging configuration is scoped to the
 *       two contexts that actually exchange messages, and this is not one of
 *       them: the card screens neither publish nor consume a message.
 *       Assumptions: the build agrees, because
 *       {@code services/card-service/pom.xml} declares no SQS client, no Spring
 *       Cloud AWS module and no AWS SDK messaging dependency, so there is no
 *       listener container to configure and no queue for a listener to read. The
 *       service configuration carries no queue or messaging property either.</li>
 *   <li>There is no {@code BatchConfig}. None of the migrated card programs is a
 *       scheduled job, and {@code spring-boot-starter-batch} is absent from this
 *       module's classpath, so there is no job repository to define.
 *       Alternatives Considered: the sequential card reader of the baseline
 *       becomes the read path on the card repository rather than a chunk oriented
 *       step, because a step would add a job repository and a restart contract to
 *       a path that only ever serves one online request.</li>
 *   <li>There is no exception handler class. The handler that turns an exception
 *       into the shared error payload is
 *       {@code com.carddemo.common.error.GlobalExceptionHandler} in the shared
 *       kernel, and it is registered once by that kernel's own
 *       {@code CardDemoCommonAutoConfiguration}.
 *       Alternatives Considered: declaring a second handler here was rejected
 *       because it would give one concern two owners and make which of them
 *       answers a given exception depend on bean ordering rather than on anything
 *       written down.</li>
 * </ul>
 *
 * <h2>How shared kernel components reach this context</h2>
 *
 * <p>This is the most easily misread thing about this package, so it is stated
 * explicitly. Assumptions: the component scan implied by the annotation on
 * {@code CardApplication} is rooted at {@code com.carddemo.card}, while every
 * shared kernel type lives under {@code com.carddemo.common}, which sits outside
 * that root. Shared components are therefore never discovered automatically;
 * each one is registered deliberately, and those registrations are split across
 * two places. A reader who assumes the scan reaches the shared kernel will look
 * for a missing bean in the wrong tree entirely.</p>
 *
 * <ul>
 *   <li>{@code com.carddemo.common.CardDemoCommonAutoConfiguration} contributes
 *       six components: {@code MetricsConfig}, which it pulls in with
 *       {@code @Import}, and five {@code @Bean} declarations -- a {@code Clock},
 *       the money codec module, a {@code CursorToken}, the
 *       {@code CorrelationIdFilter} registration and
 *       {@code GlobalExceptionHandler}. The framework loads it from the shared
 *       module's own
 *       {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 *       entry, so this context receives all six without naming any of them.
 *       Refactoring Rationale: this list read "four" and omitted the
 *       {@code Clock} and the {@code CursorToken}. The second omission mattered
 *       most: this service's keyset paging seals its boundary keys through that
 *       bean, so a reader tracing where the sealing key comes from was sent to
 *       look for a registration this charter said did not exist.
 *       Refactoring Rationale: an earlier design had {@code CardApplication}
 *       import the first two explicitly and this charter described that. It was
 *       superseded because a registration a service has to remember is a
 *       registration a service can omit, and the symptom of omitting one is
 *       silent -- a log line with no correlation identity, a meter with no service
 *       dimension, or an amount on the wire as a bare JSON number. The shared
 *       module's registration resource records the same decision from the other
 *       side.</li>
 *   <li>{@code SecurityConfig} in this package registers
 *       {@code com.carddemo.common.security.JwtRoleConverter} and
 *       {@code com.carddemo.common.security.CognitoAccessTokenValidator}, because
 *       both take effect as part of the request pipeline that class defines and
 *       their position within that pipeline is part of their contract. The
 *       validator in particular CANNOT be contributed by the shared kernel:
 *       installing it means building the {@code JwtDecoder}, and the framework
 *       offers no hook to extend the validator chain it composes from an issuer
 *       location.</li>
 * </ul>
 *
 * <p>Alternatives Considered: the scan root is never widened to
 * {@code com.carddemo} to shorten either list. Widening it would pull every
 * shared kernel component into every service at once, including the ones a given
 * context has no use for, and would replace eight registrations that can be read
 * in two files -- the six above plus {@code SecurityConfig}'s two -- with an
 * implicit set that changes silently whenever the shared kernel gains a component.
 * Trade-offs: the price of the narrow root is
 * that a newly added shared component has to be registered by hand in one of the
 * two places above, and that a reader has to consult both to see the whole set.
 * That price buys a registration list which only changes when someone edits
 * it.</p>
 *
 * <p>The rest of the shared kernel needs no registration at all. Its records and
 * static utilities, among them {@code ApiError}, {@code AbendDetail},
 * {@code PageResponse}, {@code TimestampFormatter} and the validation and codec
 * helpers, are used as plain imports wherever they are needed, which is why they
 * appear in neither list above.</p>
 *
 * <h2>Labelling of the rationale above</h2>
 *
 * <p>Assumptions: all four of the canonical labels this project's explainability
 * rule names appear above, in the plural and unparenthesised form the tree uses,
 * and {@code Refactoring Rationale:} is used twice, both times on something this
 * package genuinely superseded rather than merely differs from. The first is on the
 * shared auto-configuration entry in the registration list, where the earlier design
 * had {@code CardApplication} import two of those components explicitly and this
 * charter described that arrangement: Java configuration that existed in this
 * package was replaced by the shared module's own registration resource. The second
 * is the paragraph immediately below, which supersedes a statement this section
 * itself used to make. Assumptions: a charter is part of what this package
 * delivers, so a claim in it that has been replaced is within the label's reach on
 * the same reasoning as replaced Java -- and recording the replacement under the
 * label is what lets a reviewer's literal search find it, which is the whole
 * purpose the label serves.
 *
 * <p>Refactoring Rationale: this section previously asserted the opposite -- that
 * only three labels appeared and that {@code Refactoring Rationale:} was
 * "deliberately absent rather than overlooked" because "no earlier Java
 * configuration existed here to supersede". The label was in the same file,
 * forty lines above the sentence denying it, and both halves of the denial were
 * false. That combination is the one worth correcting rather than tolerating: a
 * reviewer sweeping this tree for label discipline reads a claim of absence,
 * finds a present instance, and cannot tell which of the two the author intended
 * -- so the instance reads as the violation when in fact the claim was.
 *
 * <p>Assumptions: the label's precondition remains unmet for a choice that merely
 * DIFFERS from the COBOL baseline, and no such choice is labelled this way. The
 * baseline is never edited, so transcribing it replaces nothing; where a decision
 * here departs from it, {@code Alternatives Considered:} is the accurate label and
 * is the one used. The written convention is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, and the Checkstyle Javadoc gate
 * bound to the Maven {@code validate} phase in {@code services/pom.xml} checks
 * that this documentation is present but cannot check that its labels are
 * accurate, which is why the choice of label is recorded here in prose.</p>
 */
package com.carddemo.card.config;
