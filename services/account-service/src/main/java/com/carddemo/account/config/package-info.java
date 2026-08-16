/**
 * Spring wiring for the account, customer and card-cross-reference context, closed at seven configuration
 * classes that hold no business rule, no error mapping and no shared-kernel registration of their own.
 *
 * <h2>What this package is for</h2>
 *
 * <p>This package answers five assembly questions for the bounded context whose charter, records, tables
 * and transcribed behaviour are described once at {@code com.carddemo.account}: which tokens are accepted
 * and what each route demands of them, what metadata the published contract carries, how the context
 * reaches its own schema and no other, how the queue-driven inquiry listener is fed, and how the two
 * protected customer identifiers become ciphertext. Nothing here
 * transcribes a baseline paragraph, so nothing here carries a functional-parity obligation of its own.
 * The wiring exists so that the classes which do transcribe baseline paragraphs may assume a correctly
 * assembled context rather than establishing one apiece.</p>
 *
 * <p>The context is the migration target of six baseline programs -- {@code app/cbl/COACTVWC.cbl} at 941
 * lines, {@code app/cbl/COACTUPC.cbl} at 4236, {@code app/cbl/CBACT01C.cbl} at 430,
 * {@code app/cbl/CBACT03C.cbl} at 178, {@code app/cbl/CBCUS01C.cbl} at 178 and
 * {@code app/app-vsam-mq/cbl/COACCT01.cbl} at 620 -- and it owns the PostgreSQL schema {@code account}
 * with exactly three tables, {@code accounts}, {@code customers} and {@code card_xref}. The line counts
 * are {@code wc -l} values, which is the measure this repository uses, and they are quoted because they
 * are the honest indication of how much transcribed logic each program contributes to the context these
 * seven classes assemble.</p>
 *
 * <h2>The closed set of seven configuration classes</h2>
 *
 * <p>The set is <strong>closed at seven</strong>, and all seven exist beside this charter. That is a
 * constraint on what may be added here as well as a listing of the directory, and each member owns
 * exactly one concern. The count and the enumeration below are measured against the directory on every
 * build:</p>
 *
 * <pre>
 * this directory: 8 java files = 7 classes + 1 charter
 * </pre>
 *
 * <p>Assumptions: that marker line is what makes the closure checkable rather than merely asserted. It
 * and the entries under it are measured by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/PackageCharterInventoryTest.java},
 * so an eighth class arriving without an entry here fails the build instead of quietly falsifying this
 * paragraph. Mechanical measurement earns its keep in this package more than in most, because two of the
 * seven members -- {@code CustomerIdentifierProtectionConfig} and {@code KmsConfig} -- are the ENTIRE
 * production implementation of this context's encryption boundary: {@code CustomerMapper} requires the
 * {@code CustomerIdentifierProtection} port through its constructor and is component-scanned, so a reader
 * who acted on "anything else here does not belong" and removed them would not get a compile failure --
 * they would get a context that refuses to refresh, and, if they instead satisfied the port with
 * something inert, two columns named {@code ssn_encrypted} and {@code govt_issued_id_encrypted} holding
 * cleartext.</p>
 *
 * <p>Trade-offs: the closure statement below -- that this is a constraint on what may be added here and
 * not a snapshot of what happens to exist -- is deliberately paired with the measured marker rather than
 * left to stand on its own. Alone it invites a reader to treat anything else in the directory as not
 * belonging, and acting on that would remove the second filter chain, which does not fail a build: it
 * makes three internal reads from the authorization context refuse authentication, which that caller
 * reports as the dependency being unavailable, so the authorization would be redelivered until the queue
 * dead-lettered it. The closure argument grounds each member on a capability the POM declares, and it is
 * extended to each new member rather than loosened.</p>
 *
 * <ul>
 *   <li>{@code SecurityConfig} -- which tokens are accepted and what authority each route requires. It
 *       assembles the resource-server filter chain, the decoder that validates a presented token, and the
 *       conversion of the identity provider's group claim into granted authorities through
 *       {@code com.carddemo.common.security.JwtRoleConverter}. It also declares the one path an
 *       unauthenticated caller may reach, as the constant {@code HEALTH_PATH}, so that the load
 *       balancer's probe and the container's own probe succeed while every other management path stays
 *       behind the chain.</li>
 *   <li>{@code OpenApiConfig} -- the published contract's document metadata, and nothing else. It
 *       describes the contract; it never alters a status code, a payload shape or a validation
 *       outcome.</li>
 *   <li>{@code DataSourceConfig} -- the schema search path pinned to the single schema {@code account},
 *       connection-pool sizing, and the migration runner's wiring.</li>
 *   <li>{@code SqsConfig} -- the listener-container factory that feeds the account-inquiry request and
 *       reply flow transcribed from {@code app/app-vsam-mq/cbl/COACCT01.cbl}.</li>
 *   <li>{@code InternalApiSecurityConfig} -- the second filter chain, and the only one that authorises a
 *       caller which is a workload rather than a person. It matches exactly the internal read addresses
 *       its own {@code internalPaths()} enumerates and verifies a shared-symmetric-key service token on
 *       them. Assumptions: the enumeration is pointed at rather than restated here, because a restated
 *       list drifts from the list it copies. Two of the paths it matches are worth naming for a reader
 *       reasoning about callers: the customer record read and the customer scan sit on this chain not
 *       because the authorization context calls them, but because {@code SecurityConfig}'s customer
 *       pattern denies that whole subtree on the human chain. Assumptions: it is ordered <b>ahead</b> of
 *       {@code SecurityConfig}'s chain,
 *       and the ordering is absolute rather than a preference -- the framework offers a request to each
 *       chain in turn and the first matcher that accepts it decides it, so a lower-precedence internal
 *       chain would never see a request at all. Alternatives Considered: adding those paths to the human
 *       chain under a permissive rule, which would also have made the calls succeed. Rejected because the
 *       paths would then be reachable by any authenticated cardholder and one of them resolves a primary
 *       account number, so the permissive rule buys availability with a disclosure. Assumptions: this
 *       class is why this context has an internal chain at all, where a context with no inbound service
 *       caller has none.</li>
 *   <li>{@code KmsConfig} -- the key-management client, and nothing else. It contributes the one client
 *       that {@code com.carddemo.account.service.CustomerIdentifierCipher} and
 *       {@code CustomerIdentifierProtectionConfig} reach, so that neither has to build its own.
 *       Assumptions: region and credentials resolve through the SDK's own default provider chains rather
 *       than being named here, because in a deployed task the region arrives as an environment variable
 *       and the credentials as the task role -- and a task role only means anything if no credential is
 *       configurable. Alternatives Considered: constructing the client inside the cipher instead.
 *       Rejected because a class that builds its own infrastructure client cannot be handed a substitute,
 *       so every case asserting what an envelope discloses would then need a real key.</li>
 *   <li>{@code CustomerIdentifierProtectionConfig} -- the WIRING of the
 *       {@code CustomerMapper.CustomerIdentifierProtection} port, which is the only production route by
 *       which the national identifier {@code CUST-SSN} at {@code app/cpy/CVCUS01Y.cpy} L17 and the
 *       government-issued identifier {@code CUST-GOVT-ISSUED-ID} at L18 become the {@code BYTEA}
 *       ciphertext that {@code src/main/resources/db/migration/V1__account.sql} declares. ⚠️ Refactoring
 *       Rationale: this entry read "the implementation of the port" and said that this class "decides the
 *       framing", and both were true of a private nested implementation it used to hold -- one that framed
 *       with no marker and no version byte while
 *       {@code com.carddemo.account.service.CustomerIdentifierCipher} framed both. Two writers of one
 *       {@code BYTEA} column, selected by whichever bean a context happened to register, and no decipher
 *       path in this context to notice. The bean method now delegates to that cipher, so the framing
 *       decision -- envelope encryption under a fresh data key per identifier, Galois/Counter Mode, and an
 *       encryption context naming the purpose and the column but never the customer -- is recorded and
 *       implemented in ONE place, and this class supplies no cryptography of its own. Assumptions: it
 *       remains a separate class from {@code KmsConfig} above even though both concern one key, because
 *       the two answer different questions -- that one supplies a client, this one satisfies a port -- and
 *       because it is what keeps the port satisfiable in a context that scans {@code mapper} without
 *       scanning {@code service}, which is the slice several of this module's own tests use. Assumptions:
 *       it lives in this package and not in {@code mapper} because the port is declared there precisely so
 *       the mapping layer can state what it needs of a key provider without naming one; an infrastructure
 *       type is admissible here and is not admissible there.</li>
 * </ul>
 *
 * <p>Assumptions: each member of that set rests on a capability this module actually declares, so the
 * closure is auditable from the build file rather than asserted here.
 * {@code services/account-service/pom.xml} declares the security and resource-server starters that
 * {@code SecurityConfig} configures, the contract-documentation starter at L422 that gives
 * {@code OpenApiConfig} something to
 * describe, the database driver at L376 with the migration artifacts at L397 and L401 that
 * {@code DataSourceConfig} wires, and the queue starter at L330 that {@code SqsConfig} tunes. It also
 * declares the key-management client that {@code KmsConfig} contributes and
 * {@code CustomerIdentifierProtectionConfig} consumes, and that dependency is what grounds the last two
 * members: without it neither class would compile, and with it the two protected columns have an
 * implementation rather than a name. {@code InternalApiSecurityConfig} is the one member grounded
 * differently, and deliberately so: it rests on no additional dependency at all -- it uses the same
 * security starter as the first member plus the shared kernel's service-token minter -- and what it rests
 * on instead is the existence of an inbound service-to-service caller, which the sibling authorization
 * and transaction contexts' clients establish. An EIGHTH class would therefore have to arrive either with
 * a newly declared capability or with a further class of caller, and a reader can check both claims
 * without reading a single Java file.</p>
 *
 * <h2>What this package must never own</h2>
 *
 * <p>Assumptions: there is no {@code BatchConfig} here and there must never be one. Chunk-oriented jobs
 * and the durable job repository belong to {@code batch-service}, which is also where the posting unit of
 * work that needs cross-schema write grants is owned. The batch starter is version-managed centrally at
 * {@code services/pom.xml} L703 but is deliberately <strong>not</strong> declared by this module, and
 * {@code services/account-service/pom.xml} records that exclusion in prose at L587 to L591. The
 * consequence is stronger than a convention: the framework types such a class would reference are absent
 * from this module's compile classpath, so the omission is enforced by the compiler and not merely by
 * agreement.</p>
 *
 * <p>Assumptions: there is no exception-handler class, no correlation-filter registration, no meter
 * customiser and no serialisation module here either. All four are shared-kernel components, all four sit
 * outside the component-scan root, and all four reach this context through
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, which the framework loads from the shared
 * module's registration resource rather than discovering by scan. The entry point records the same fact
 * from its own side at
 * {@code services/account-service/src/main/java/com/carddemo/account/AccountApplication.java} L29 to L32.
 * Declaring any of the four again here would give one concern two owners and make which one answers a
 * given request depend on bean ordering rather than on anything written down.</p>
 *
 * <p>Alternatives Considered: registering those shared components explicitly from the entry point, which
 * an earlier design of this repository did. Rejected, because a registration a service has to remember is
 * a registration a service can omit, and every symptom of omitting it is silent rather than loud -- log
 * lines carrying no correlation identity, meters carrying no service dimension, amounts leaving as bare
 * numbers in the response body, or one service answering with a framework-shaped error body while its
 * siblings answer in the migrated shape. Registration through
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration} fails loudly at startup instead, and it
 * fails once for the whole context rather than once per forgetful service. That class is named by the
 * shared module's registration resource at
 * {@code services/common-lib/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports},
 * which names exactly that one class and nothing else, and whose own charter records this same decision
 * from the other side.</p>
 *
 * <p>Assumptions: mapping a failed optimistic-lock check or a restricted delete onto an HTTP status is
 * likewise not a concern of this package, so no handler for either may be declared here.
 * {@code com.carddemo.common.error.GlobalExceptionHandler} already renders the conflict conditions as
 * HTTP 409, as its own documentation records at
 * {@code services/common-lib/src/main/java/com/carddemo/common/error/GlobalExceptionHandler.java} L769,
 * and it keeps them as separate handlers precisely so each can carry its own message, per L140 to L144 of
 * that file. A second mapping declared here would compete with it for the same exception.</p>
 *
 * <h2>Schema binding: one search path, one schema</h2>
 *
 * <p>Assumptions: the search path {@code DataSourceConfig} establishes names the single schema
 * {@code account} and no other, which is the mechanism that keeps this deployable from reading a sibling
 * context's tables even by accident. Two facts about the schema's lifecycle follow from that and are
 * stated because getting either one backwards produces a migration that fails on a clean database. The
 * schema is created outside this service, by {@code data-migration/sql/V0__schemas_and_roles.sql} at
 * L502, together with the role that owns it. This service's own migration at
 * {@code services/account-service/src/main/resources/db/migration/V1__account.sql} therefore creates
 * tables into a schema it assumes already exists and issues no schema creation of its own; it currently
 * contains no such statement, and it must stay that way. A service that created its own schema would own
 * it, and ownership is exactly what the bootstrap script assigns deliberately and per role.</p>
 *
 * <h2>No transactional outbox, and the evidence that settles it</h2>
 *
 * <p>Assumptions: this context deliberately carries <strong>no transactional outbox</strong>, and the
 * evidence is in the baseline rather than in a preference. In
 * {@code app/app-vsam-mq/cbl/COACCT01.cbl} the inbound receive is set up under syncpoint at L347, where
 * the get options are computed as the syncpoint option, with the get itself issued at L352; the outbound
 * reply is set up under syncpoint in the same way at L475, with the put issued at L479. That file
 * contains no no-syncpoint option anywhere. Receive, business work and reply are consequently one atomic
 * unit of work, so there is no window in which the data is committed while the reply is lost, and
 * therefore nothing for an outbox to close.</p>
 *
 * <p>The contrast is what makes this worth recording at package level rather than only at the listener.
 * The authorization context's consumer, {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl},
 * computes its get options with the <strong>no</strong>-syncpoint option combined with a wait at L389 and
 * its put options with the no-syncpoint option at L753, so there its reply genuinely can be lost after
 * its data is committed, and that context does need an outbox. Two services, two opposite rulings, and
 * the same word appearing in both option names is precisely why a reader skimming for "syncpoint" can
 * come away with the pair inverted. Adding an outbox here would not be harmlessly redundant either: it
 * would publish a reply from a second, later transaction and so introduce an observable intermediate
 * state that the baseline's single unit of work does not have.</p>
 *
 * <h2>Identity moves from echoed storage to a signed claim</h2>
 *
 * <p>Refactoring Rationale: in the baseline, identity travels between screen turns inside the CICS
 * communication area declared at {@code app/cpy/COCOM01Y.cpy} L19, whose identity fields are the
 * eight-character user identifier at L25 and the single-character user type at L26, the latter carrying
 * condition names for the administrator value at L27 and the ordinary-user value at L28. Those two values
 * are what {@code SecurityConfig} maps onto the identity provider's two groups. What was wrong with the
 * old arrangement is structural rather than cosmetic: the communication area is storage the client
 * receives and echoes back, so a client could in principle return a user type it was never granted. In
 * the migrated form the client cannot assert its own authority at all, because the group claim arrives
 * inside a signed token that this package configures the decoder to verify before any authority is
 * derived from it. The trust boundary moves from the caller to the signature.</p>
 *
 * <p>Assumptions: the two identity values at L27 and L28 are quoted character literals, whereas the two
 * re-entry values in the same record at L30 and L31 are bare numerics. Anything deriving from that record
 * must preserve the distinction rather than normalising the four onto one form. The re-entry discriminator
 * itself has no counterpart here in any case: a stateless request carries no notion of a first turn
 * versus a later one, which is why no member of this package configures a session store and why
 * {@code SecurityConfig} establishes a stateless session policy instead. Sign-on itself, and the known
 * weakness in how the baseline's separate security file stores the value it verifies against, belong to
 * the authentication context and are out of scope for this package entirely.</p>
 *
 * <h2>Durability and isolation at rest</h2>
 *
 * <p>Trade-offs: the four CICS file resources whose data this schema now holds are all defined in
 * {@code app/csd/CARDDEMO.CSD} with journalled synchronous write enabled, recovery declared as none and
 * no forward recovery log, that trio appearing verbatim at L9, L46, L59 and L72 for the stanzas opening
 * at L1, L37, L50 and L63 respectively. All four additionally declare uncommitted read integrity, at L3,
 * L40, L53 and L66. The target this package wires gives up the baseline's read behaviour: reads are
 * transactional and do not see uncommitted data, and encryption at rest with automated backups
 * replaces a resource declaring no recovery at all. That is a correction of posture rather than a port of
 * it, so it is a deliberate divergence and is recorded as one. The compromise accepted is real and worth
 * naming: stronger isolation is not free, since a read that the baseline satisfied without regard to
 * concurrent writers can now wait on one. It is accepted because the alternative preserves a weaker
 * guarantee for financial record data purely for the sake of matching, and no baseline behaviour depends
 * on observing a partial write.</p>
 *
 * <p>Assumptions: only those four stanzas are in this context's scope. The stanzas opening at L13 and L25
 * belong to the card context, the one at L76 to the transaction context and the one at L88 to the
 * authentication context, and no member of this package may reach any of them. Note also that the
 * resource name at L37 is {@code CCXREF} even though the data set it names carries {@code CARDXREF} in
 * its own name; the resource name is the one the baseline programs open, so it is the one cited
 * here.</p>
 *
 * <h2>Amounts stay in fixed point</h2>
 *
 * <p>Assumptions: nothing this package configures may introduce IEEE-754 binary floating point into the
 * amount path. {@code app/cpy/CVACT01Y.cpy} declares five amount fields, at L7, L8, L9, L13 and L14, each
 * of them zoned decimal with a sign overpunch and two declared decimal places. Those propagate as an
 * exact numeric column with scale two in SQL, an arbitrary-precision decimal at scale two in Java, and a
 * string in the response body. The reason the wire form is a string is specific rather than fastidious:
 * a bare number in a response body is parsed into a binary floating-point value by most clients, which
 * loses exactness at the one boundary a user actually reads. The serialisation module that enforces this
 * is a shared-kernel component and arrives by auto-configuration, which is why this package configures no
 * serialisation of its own and must not begin to.</p>
 *
 * <h2>How this package is verified, and what cannot verify it</h2>
 *
 * <p>Assumptions: none of this context's six programs has a parity oracle, so correctness here rests on
 * transcription fidelity against cited line numbers rather than on a golden master. Two independent
 * reasons produce that gap. The repository's test suite records at {@code tests/README.md} L83 to L85
 * that the online programs cannot run end to end without a CICS runtime, which the runner does not have,
 * and that only their extractable field-validation logic is unit-tested; and its business-rules section
 * beginning at L553 enumerates rules for the posting, interest and category-balance programs only, so not
 * one rule stated there governs this context. Separately,
 * {@code app/app-vsam-mq/cbl/COACCT01.cbl} includes six messaging copybooks, at L71, L75, L79, L83, L87
 * and L90, none of which exists anywhere in this repository, so that program cannot be compiled by the
 * existing harness at all. Every citation in this descriptor was therefore read directly from the
 * baseline file named beside it.</p>
 *
 * <p>Assumptions: the gate that judges this package is binary, and it is a different kind of gate from
 * the one the baseline suite uses. That suite aggregates a mainframe-style condition code, published as a
 * rubric at {@code tests/README.md} L412, in which the warn level described at L420 is a documented
 * healthy outcome; the build that compiles and audits this package passes or fails outright, with no
 * tolerated middle level. The two must not be read against each other, and this module's own test sources
 * are not that reference suite.</p>
 */
package com.carddemo.account.config;
