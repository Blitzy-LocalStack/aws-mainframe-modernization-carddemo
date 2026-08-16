// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/repository/package-info.java
// -----------------------------------------------------------------------------
// Purpose:
//      Charter of the container-backed repository integration-test package of
//      the reference-data bounded context. It declares no type. It exists so
//      that the rulings the integration-test classes beside it all depend on
//      are stated once, in the package they govern, instead of once per class.
//
// WHY (non-obvious design decisions):
//  (1) Assumptions: this file is load-bearing rather than decorative, because
//      two Checkstyle modules split one obligation between them and neither
//      half can be satisfied by the other. config/checkstyle/checkstyle.xml
//      declares JavadocPackage at Checker level, line 238, which inspects the
//      file system and asserts only that a package-info.java exists for this
//      directory; it declares MissingJavadocPackage inside TreeWalker, line
//      378, which reads the parsed file and asserts that it carries Javadoc. A
//      file holding only this banner would pass the first and fail the second.
//      services/pom.xml binds the plugin to the validate phase, configured to
//      fail the build on any violation at warning severity or above and with
//      includeTestSourceDirectory true, so an absent or Javadoc-less file here
//      stops all nine reactor modules before a single source file is compiled.
//  (2) Assumptions: there is no way to waive either half locally.
//      config/checkstyle/suppressions.xml carries exactly two path patterns,
//      at lines 124 and 151, covering generated sources and
//      src/test/resources/fixtures, and neither matches this directory; the
//      rule set configures no comment-based or annotation-based filter, so an
//      in-code suppression marker has no effect at all here and its
//      SuppressionFilter is declared optional false, which fails closed.
//  (3) Alternatives Considered: restating these rulings inside each
//      integration-test class, so that a reader never has to open a second
//      file. Rejected because the rulings are shared and a restated ruling
//      drifts. The forward and backward seek predicates transcribed below are
//      deliberately asymmetric, and a paraphrase that smoothed the asymmetry
//      in one class of several would read as a correction of the baseline
//      rather than as the transcription it is. The cost accepted is one
//      indirection from each class to this charter.
//  (4) Trade-offs: this charter states the package CONTRACT, not a listing of
//      the directory. A class named below belongs to this package and a class
//      absent from below does not, whichever files the directory happens to
//      hold; present contents are read from the directory itself or from
//      `mvn -f services/reference-service/pom.xml verify`. The compromise is
//      that a reader cannot use this file as an inventory of what is on disk,
//      which is stated plainly because the alternative -- a state sentence
//      that silently goes stale -- costs a reader their trust in the rulings
//      as well, and the rulings are the half that cannot be recovered from
//      the files.
//  (5) Refactoring Rationale: three premises this charter was drafted against
//      do not hold once checked against the tree, and each is restated
//      correctly here rather than repeated. The subtree charter at
//      services/reference-service/src/test/java/com/carddemo/reference/package-info.java
//      states at its lines 65 to 70 that the main tree's repository package
//      has no counterpart in the test tree; the existence of this package
//      supersedes that one sentence and nothing else in that file. The three
//      lookup-table test classes are named for interfaces that carry a Us
//      prefix, so the unprefixed names are not used. And an optimistic-lock
//      counter is present on two of the six entities rather than on none.
//      Each correction is recorded at the ruling it affects.
//  (6) Assumptions: every line number cited in this file is a PHYSICAL line
//      number, the number a line-numbering reader reports, and in the
//      normative browse program that is not the number printed in the
//      source's own sequence columns. The divergence and how to verify around
//      it are stated under the browse rulings, because a citation a reader
//      cannot check is worse than no citation.
//  (7) Assumptions: the four justification labels used below are the plural
//      forms as the project rules document writes them, and that document is
//      the source they were copied from rather than any file in this
//      repository. The singular variants that appear in this repository's
//      shell, XML, YAML and HCL headers carry the same meaning and are not
//      used anywhere in this Java tree; that equivalence is declared here
//      once and the plural form is used from here on. The rules document was
//      chosen as the source because the existing suite's README renders one of
//      those labels with a non-breaking hyphen and an em dash instead of the
//      ASCII hyphen, a difference no reader can see on screen.
// =============================================================================
/**
 * Charter of the container-backed repository integration tests of the reference-data bounded context,
 * which exercise the seven Spring Data JPA interfaces of {@code com.carddemo.reference.repository}
 * against a real PostgreSQL engine.
 *
 * <h2>Purpose, and the rule elements that have no subject here</h2>
 *
 * <p><b>Purpose.</b> This package is the persistence-layer test boundary of reference-service. Every
 * class in it starts a PostgreSQL container, lets Flyway build the {@code reference} schema from this
 * module's {@code src/main/resources/db/migration/V1__reference.sql} and seed it from
 * {@code V2__seed_reference.sql}, and then asserts that the seven repository interfaces declared in the
 * main tree's package of the same name reach the rows those migrations define, through the keys and in
 * the order the COBOL baseline reached them. Nothing here substitutes a test double for a database,
 * and the reason is the single assertion this package exists to make: a referential-integrity rule
 * that only a real engine enforces.</p>
 *
 * <p>The main-tree charter at
 * {@code services/reference-service/src/main/java/com/carddemo/reference/repository/package-info.java}
 * owns the query contracts and the access-path rulings. This charter governs their verification and
 * cites that file rather than restating it, because two statements of one contract drift apart and a
 * reader then cannot tell which of them is current. The subtree charter at
 * {@code services/reference-service/src/test/java/com/carddemo/reference/package-info.java} owns the
 * conventions shared by every test subpackage of this module and is cited the same way.</p>
 *
 * <p>On parameters, return values and exceptions: a package declaration accepts no argument, returns
 * no value and raises nothing, so this charter carries no parameter, return or exception at-clause.
 * The inapplicability is stated rather than left silent, because the project Explainability rule
 * counts a docstring that omits its parameters or return values among the omissions it rejects at
 * line 39, and a reader has to be able to tell a declared inapplicability from an oversight. It is
 * also why the exception element is named here even though the rule qualifies it as applying where
 * applicable at line 21: naming all three at once is what makes the absence of all three legible as
 * one decision rather than as three separate gaps. Assumptions: Javadoc
 * models no such concept for a package, and the rule set audits at-clause bodies for emptiness through
 * {@code NonEmptyAtclauseDescription}, so an invented empty tag would be reported rather than
 * credited. For the same reason no authorship, version or availability tag appears: the modules that
 * would require them, {@code JavadocStyle}, {@code WriteTag} and {@code JavadocParagraph}, are
 * deliberately absent from {@code config/checkstyle/checkstyle.xml}, recorded there at lines 561 to
 * 569.</p>
 *
 * <h2>The inventory this package owns, and the shared base that has a file of its own</h2>
 *
 * <p>Assumptions: the closed set is <b>twelve compilation units</b> -- this charter, the shared
 * container fixture {@code ReferencePersistenceBase}, and ten integration-test classes. The naming rule
 * is that a class takes the name of the type it covers with {@code IT} appended, and the ten are
 * {@code TransactionTypeRepositoryIT}, {@code TransactionCategoryRepositoryIT},
 * {@code DisclosureGroupRepositoryIT}, {@code UsPhoneAreaCodeRepositoryIT},
 * {@code PhoneAreaCodeRepositoryIT}, {@code UsStateRepositoryIT},
 * {@code UsStateZipPrefixRepositoryIT}, {@code StateRepositoryIT},
 * {@code StateZipPrefixRepositoryIT} and {@code InquiryReplyLedgerIT}.</p>
 *
 * <p>Refactoring Rationale: the set grew from eleven to twelve when the main tree gained
 * {@code InquiryReplyLedger} -- the one CLASS in the data-access package, and therefore the one member
 * here whose covered type is not an interface. The naming rule is unchanged and is simply stated over
 * types rather than over interfaces. Assumptions: that class earns an engine-backed test of its own
 * rather than a substituted one because every property it holds is the ENGINE's: an
 * {@code INSERT ... ON CONFLICT DO NOTHING} reporting zero affected rows on a second claim, two
 * {@code CHECK} constraints, and a guarded {@code UPDATE} reporting whether it retired a row. A
 * substituted ledger would answer whatever a stub was told to and would pass against a statement
 * carrying a typo.</p>
 *
 * <p>⚠️ Refactoring Rationale: the twelfth unit is {@code AddressLookupPagingIT}, and it is the one
 * exception to the naming rule above, deliberately. It covers no single interface: it walks each of the
 * three seeded allow-lists page by page through {@code AddressLookupService}, which reads all three
 * repositories, so no interface name would describe it and any one of the three would misdescribe it. It
 * is HERE rather than in the service test package because it extends this package's shared container
 * fixture and needs nothing that fixture does not already provide, and standing up a second engine in
 * another package to obey a naming rule would cost the slowest part of this module's build to gain a
 * filename. Assumptions: it exists because the property it asserts was previously asserted at the HTTP
 * boundary against a single page carrying an entire domain -- 490 rows where the browse publishes 20 and
 * the schema declares 20 as its maximum -- so the multi-page continuation a client actually performs was
 * exercised by nothing, and the two failures that continuation exposes, a row repeated at every boundary
 * and a row skipped, were both invisible.</p>
 *
 * <p>⚠️ Refactoring Rationale: this paragraph claimed eight units and named seven classes while the
 * directory held ten, and both halves of the discrepancy are corrected here rather than one of them.
 * The count moved to eleven for two independent reasons. The shared fixture became a file of its own,
 * which is recorded in the paragraph below. And the roster was already two classes short of the
 * directory: {@code StateRepositoryIT} and {@code StateZipPrefixRepositoryIT} exist and were never
 * listed, even though the paragraph further down names their unprefixed forms as <b>not</b> the names
 * to use. Assumptions: the discrepancy is recorded as a measurement rather than resolved by deleting
 * files, because those two classes hold nine asserting cases between them and removing a passing
 * assertion is a behavioural decision this charter is not the place to take. Trade-offs: the package
 * therefore holds two pairs of classes reading the same two seeded tables -- the prefixed and unprefixed
 * forms over {@code reference.us_states} and {@code reference.us_state_zip_prefixes} -- which is one
 * more engine-backed class per table than the naming rule intends and is stated here so that a reader
 * meets it as a known duplication rather than as a surprise.</p>
 *
 * <p>Refactoring Rationale: this set was seven units covering six interfaces, and it grew by one when
 * the main tree reinstated {@code PhoneAreaCodeRepository} as the classification-scoped membership
 * predicate over {@code reference.us_phone_area_codes}. Two classes therefore read the same seeded
 * table. That is the naming rule holding rather than bending: folding the predicate's assertions into
 * {@code UsPhoneAreaCodeRepositoryIT} would have kept the count at seven while breaking the one
 * property the rule buys -- that the interface under test is derivable from the test's own name -- and
 * a reader looking for the predicate's coverage would have had to find it under a different
 * interface's name. Trade-offs: the cost is one more file and a second class reading rows the first
 * already reads, and the shared container base means it is not a second engine start.</p>
 *
 * <p>Refactoring Rationale: two names carry the {@code Us} prefix because the interfaces
 * they cover carry it -- the main tree declares {@code UsPhoneAreaCodeRepository} and
 * {@code UsStateZipPrefixRepository}, over the tables
 * {@code reference.us_phone_area_codes} and
 * {@code reference.us_state_zip_prefixes}. Their unprefixed forms are recorded here as <b>not</b> the
 * names to use, because they are the forms a reader working from the table's informal name would
 * reach for, and a class named for a subject it does not cover breaks the property the whole naming
 * rule buys: that the interface under test is derivable from the test's own name.</p>
 *
 * <p>⚠️ Refactoring Rationale: the state interface is the ONE that moved the other way, and it moved by
 * a rename in the main tree rather than by anything decided here. {@code UsStateRepository} is now
 * {@code StateRepository}, the name this package's checkpoint contract assigns to the interface over
 * {@code UsState}; the entity and the table keep their prefixed names. Two consequences follow for this
 * roster. {@code UsStateRepositoryIT} is renamed {@code StateRepositoryWalkIT}, because a test named for
 * a type that no longer exists is exactly the failure the naming rule guards against. And
 * {@code StateRepositoryIT}, previously one of the two classes this charter recorded as an unlisted
 * duplication, is now the canonically-named test of a canonically-named interface. Assumptions: the two
 * classes are RETAINED as a pair rather than merged, and both still name the interface under test, which
 * is the property the rule buys; they divide by subject -- the domain, the key width and the primary key
 * in one, the three keyset walks and the keyed finder in the other -- and {@code StateRepositoryIT}
 * reasons about its own cardinality read on the basis that the walks are covered elsewhere, so the
 * division is load-bearing documentation. Trade-offs: the {@code us_state_zip_prefixes} pair is
 * untouched and its unprefixed member, {@code StateZipPrefixRepositoryIT}, still names an interface that
 * does not exist -- the duplication this charter already records -- because nothing in this change
 * assigns that interface a new name and renaming it on the strength of a neighbouring change would be a
 * decision taken in the wrong place.</p>
 *
 * <p>⚠️ Refactoring Rationale: <b>the shared container fixture now has a file of its own,
 * {@code ReferencePersistenceBase.java}.</b> It was previously a second, package-private, top-level type
 * inside {@code TransactionTypeRepositoryIT.java}, and this charter argued for that arrangement on the
 * ground that a separate file would grow the closed set. The argument omitted the cost: an auxiliary
 * top-level type accessed from another source file raises a compiler diagnostic in every accessing file,
 * and all eight classes that extend the fixture raised one -- they were the only warnings this module
 * emitted. Checkstyle's silence on the arrangement, which the old paragraph cited in its favour, is not
 * evidence the compiler is silent too. A charter naming eleven files serves the enumerability the closed
 * set exists for exactly as well as one naming eight, so the count is restated and the warnings are
 * gone. Assumptions: the fixture keeps its name and stays package-private, because the ten test classes
 * resolve it by simple name with no import between them and it.</p>
 *
 * <p>Refactoring Rationale: the shared fixture's nested configuration now declares ONE bean,
 * {@code InquiryReplyLedger}. Assumptions: it has to be declared rather than discovered, because that
 * configuration enables Spring Data repositories and component-scans nothing, so the one authored class
 * in the data-access package would otherwise be absent from the context while every interface beside it
 * resolved. Alternatives Considered: a component scan over the main-tree repository package -- rejected
 * because such a scan also reaches the nested configuration classes of the test files in THIS package,
 * and the duplicate definitions end context load. Alternatives Considered: a nested configuration inside
 * {@code InquiryReplyLedgerIT} -- rejected because a second configuration class starts a second cached
 * context against the same engine, which is the cost this fixture exists to avoid.</p>
 *
 * <h2>The runner split is carried by the class-name suffix alone</h2>
 *
 * <p>Assumptions: which runner executes a class, and therefore at which point in the build a failure
 * surfaces, is decided entirely by the suffix on the class name. A class ending {@code Test} is
 * collected by Surefire at the {@code test} phase. A class ending {@code IT}, which in this package
 * means a {@code RepositoryIT} class, is collected by Failsafe at {@code integration-test} with its
 * result asserted at {@code verify}. Both plugins are declared once in {@code services/pom.xml} and
 * neither is redeclared by this module, whose only build plugin is the Spring Boot one; Failsafe is
 * left at its default inclusion patterns, which is what makes the {@code IT} suffix sufficient.</p>
 *
 * <p>Assumptions: the suffix is doing structural work, which is why it is ruled at package scope. A
 * container-backed class misnamed {@code Test} runs under Surefire with no container started and fails
 * for a reason unrelated to what it asserts. A class here misnamed so that neither pattern matches is
 * passed over by both runners and reports nothing at all, which is the dangerous case, because in
 * every report it is indistinguishable from success.</p>
 *
 * <p>Assumptions: both runners keep their DEFAULT report directories,
 * {@code services/reference-service/target/surefire-reports} and
 * {@code services/reference-service/target/failsafe-reports}. No class in this package may set a
 * reports directory and no module-level configuration that relocates one may be added. Relocating
 * either would leave the build green while a collector reading those two paths published nothing,
 * which is the one failure mode here that reads as success. Trade-offs: the directory name
 * {@code reports} at the repository root is unavailable to this package, being reserved by the
 * existing COBOL suite's own workflow for its three layers' output. The cost is that these reports sit
 * deeper than a single shared folder would put them; what it buys is that neither suite can overwrite
 * the other's output, and the suite that must not be disturbed is the one that keeps its established
 * path.</p>
 *
 * <p>Assumptions: this module is sixth of nine in the reactor declared by {@code services/pom.xml} and
 * its only intra-reactor dependency is {@code common-lib}, which is first. So a class here may use the
 * shared kernel and may not reference a type from any other bounded context, however closely that
 * context's data is related: the reference data this module owns reaches other contexts through this
 * service's published contract or through the schema, never through compiled code.</p>
 *
 * <h2>The container, and the configuration it is wired to</h2>
 *
 * <p>Alternatives Considered: <b>a real PostgreSQL container is mandatory and an in-memory engine was
 * rejected.</b> The single most important assertion in this module is that a delete of a referenced
 * transaction type is refused by the database, and an engine that models the restrict action loosely,
 * or that Flyway addresses through a different dialect, would let that assertion pass against a
 * fiction while the deployed schema behaved differently. Two further properties would also go
 * unverified: the fixed-width character semantics that make a space-padded key compare equal to its
 * stored form, and the exact scaled-decimal rate column. Trade-offs: container start-up cost is
 * accepted in exchange for making those three assertions real rather than notional, and it is the
 * reason a shared base type exists at all.</p>
 *
 * <p>Assumptions: <b>the connection details are published to the context declaratively, through
 * {@code @ServiceConnection} on the shared base's container field.</b> {@code spring-boot-testcontainers}
 * is therefore a test-scoped dependency of {@code services/reference-service/pom.xml}, which records the
 * reason at the declaration.</p>
 *
 * <p>Refactoring Rationale: this ruling was the opposite. It required the dynamic-property mechanism and
 * stated that no dependency was to be added to obtain the declarative form, on the grounds that the
 * module's test dependency list is its statement of what its tests may reach for. The ruling is WITHDRAWN,
 * for two reasons that were measured rather than argued.
 *
 * <p>First, it was not satisfiable. Publishing the three {@code spring.datasource} keys leaves
 * {@code spring.flyway.user} bound to an unset {@code SPRING_FLYWAY_USER} placeholder, because Boot reads
 * Flyway's credentials through a separate connection-details bean rather than from the datasource, and
 * every context load then failed authenticating as the literal placeholder text. Publishing Flyway's two
 * keys as well would have restated keys this same charter forbids a class here from restating, so neither
 * direction met the ruling.
 *
 * <p>Second, this module's own {@code src/test/resources/application-test.yml} already DEPENDED on the
 * declarative form. That file omits Flyway's user and password deliberately and records, as its own
 * measured finding, that the container's {@code @ServiceConnection} is adapted into a Flyway
 * connection-details bean so the container's generated credential migrates the schema and no stand-in
 * credential has to be invented for a role this profile never creates with a password. A charter and a
 * landed configuration file cannot both be authoritative about one mechanism; the configuration was
 * followed, because it is executable and its note was verified by running it.
 *
 * <p>Trade-offs: the dependency list did grow by one entry, which is the cost the withdrawn ruling was
 * protecting against. What is bought is a suite that runs at all, and one mechanism rather than two
 * descriptions of one.
 *
 * <p>Assumptions: {@code src/test/resources/application-test.yml} already supplies every property that
 * makes both migrations run against a bare container, and <b>no class here may duplicate or contradict
 * those keys.</b> It enables Flyway, points it at {@code classpath:db/migration}, names
 * {@code reference} as both its schema and its default schema, and asks it to create that schema,
 * which is what lets a throwaway database start empty; it pins Hibernate's default schema to the same
 * name and every {@code @Table} in the main tree names that schema explicitly as well; it pins the
 * JDBC search path on connection initialisation; it disables the request-scoped persistence context;
 * it supplies a deliberately unreachable token-issuer address; it holds the queue listener container
 * shut so that no test in this module makes a network call; and it exposes only the health endpoint.
 * Duplicating any of those in a class would create a second place where the value could be changed and
 * a first place a reader would then have to check for agreement.</p>
 *
 * <p>Assumptions: <b>the test profile sets Hibernate's schema management to validate, and that makes
 * this package the only level at which entity-to-table drift is caught.</b> The production profile
 * sets it to none, because Flyway owns every statement and three things {@code V1__reference.sql}
 * declares cannot be derived from an entity at all: the restrict-on-delete foreign key, the check
 * constraints on the fixed-width code columns, and the scaled-decimal rate. In this profile a mapping
 * that disagrees with the migrated table aborts context startup and names the object that disagrees,
 * so the drift surfaces as a failure here rather than as a query fault after deployment.</p>
 *
 * <p>Assumptions: Flyway resolves the PostgreSQL dialect through a mandatory companion artifact.
 * {@code services/reference-service/pom.xml} declares both the Flyway starter and
 * {@code org.flywaydb:flyway-database-postgresql}, because release 10 and later moved PostgreSQL
 * support out of the core artifact, so the core alone resolves at compile time and then fails when a
 * migration runs. The version for both comes from the parent and no class or configuration in this
 * package declares one.</p>
 *
 * <p>Assumptions: <b>no schema, role or grant statement is ever authored in this package.</b> Neither
 * {@code V1__reference.sql} nor {@code V2__seed_reference.sql} contains one; the schemas and the
 * per-service roles live in {@code data-migration/sql/V0__schemas_and_roles.sql}, which runs ahead of
 * them and is a forward contract this tree neither creates nor stubs. A class that created a role to
 * make an assertion pass would be asserting against privileges it granted itself.</p>
 *
 * <h2>What these tests may assert, inherited from the main-tree charter</h2>
 *
 * <p>Assumptions: the rulings below are the main-tree charter's and are reproduced here only as far as
 * a test author must honour them. Where this summary and that file could be read differently, that
 * file wins.</p>
 *
 * <ul>
 *   <li><b>Every window is positioned by key, never by distance.</b> A window taken by distance has to
 *       re-count the rows preceding it on each request, so a concurrent insert or delete makes the next
 *       window skip a row or repeat one. That is the ordinary situation for this data rather than an
 *       edge case: the browse program itself deletes rows from the table it is browsing while the
 *       companion maintenance program inserts and updates them. The vocabulary that expresses a
 *       distance-positioned window is therefore excluded from this package entirely, <b>including from
 *       its prose</b>, so that nothing written here can be read as sanctioning it -- which is why this
 *       bullet describes the prohibited mechanism instead of naming its types.</li>
 *   <li><b>A walk is bounded by a row count with no starting position</b>, expressed as a bound
 *       parameter, as a derived keyword capping the result at a literal count, or as the row-count
 *       clause inside a query already written out. What makes a bound compatible with a
 *       key-positioned walk is that it cannot express a window beginning anywhere other than at the
 *       first row the predicate admits, so the predicate remains the only thing that positions
 *       the walk.</li>
 *   <li><b>No walk takes a caller-supplied ordering.</b> The ascending direction of a forward walk and
 *       the descending direction of a backward one are transcribed from the two cursor declarations,
 *       so a test asserts the ordering the method reproduces and never supplies one of its own; a
 *       method handed an ordering under which its own bound selects different rows would make the
 *       transcription unverifiable.</li>
 *   <li><b>A transaction-category code is character data, four wide, and never an integer.</b> Its
 *       leading zeros are part of the key, so every probe, bound and expected value in a test here is
 *       text. Two copybooks declare the field numeric, which is why this is ruled rather than assumed;
 *       {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} line 3 declares
 *       {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL} and line 5 places it in the primary key, and
 *       {@code V1__reference.sql} follows it with {@code cat_cd CHAR(4) NOT NULL} at line 192 inside
 *       the composite primary key at line 223.</li>
 *   <li><b>A disclosure account-group id is ten characters wide and is never trimmed</b>, on either
 *       side of a comparison, because its trailing spaces are part of the stored key.</li>
 *   <li><b>The rate is an exact scaled decimal.</b> {@code V1__reference.sql} declares
 *       {@code interest_rate NUMERIC(6,2) NOT NULL} at line 326 and the Java carries it as
 *       {@code java.math.BigDecimal} at scale two. Binary floating-point types are excluded from every
 *       position on this path, and the shared architecture test already rejects them in a field, a
 *       parameter or a return type across the whole migration root, so a declaration of one fails the
 *       build rather than only review. Assumptions: the module-scoped form of that assertion is owned
 *       by {@code ReferenceMoneyPathRulesTest}, reserved at this module's test root, and <b>is not
 *       duplicated here</b>; a prohibition asserted in two places can be satisfied in one of them and
 *       reported as satisfied in both.</li>
 *   <li><b>The composite keys are nested {@code @Embeddable} types used through {@code @EmbeddedId}.</b>
 *       {@code DisclosureGroup} and {@code TransactionCategory} each declare theirs as a
 *       {@code public static class} implementing {@code java.io.Serializable} with value equality, so
 *       a test builds an identifier through that nested type rather than through a standalone class.
 *       The remaining four entities take a plain single-column identifier.</li>
 *   <li><b>No entity declares an association.</b> There is no owning or inverse relationship anywhere
 *       in {@code com.carddemo.reference.domain}; the foreign key is enforced in the database only.
 *       Assumptions: a test therefore cannot reach a category from a type by navigation and must query
 *       for it, and this is the property that makes the refusal assertion below a statement about the
 *       constraint rather than about a cascade the mapping configured.</li>
 *   <li><b>An optimistic-lock counter exists on two of the six entities and on no others.</b>
 *       {@code TransactionType} and {@code TransactionCategory} map one onto the
 *       {@code version BIGINT NOT NULL DEFAULT 0} column that {@code V1__reference.sql} declares for
 *       their tables at lines 140 and 212; {@code DisclosureGroup}, {@code UsPhoneAreaCode},
 *       {@code UsState} and {@code UsStateZipPrefix} have none, because their tables declare none.
 *       Refactoring Rationale: this charter was drafted against the premise that no such counter
 *       existed anywhere in the context, and that premise is false; asserting an absent counter on the
 *       two maintained tables, or a present one on the four seeded ones, would each fail for a reason
 *       unrelated to the behaviour under test. The ruling and its rationale belong to
 *       {@code com.carddemo.reference.domain}, whose charter records that the asymmetry follows the
 *       maintenance path; this bullet records only which entities it applies to.</li>
 *   <li><b>{@code V1__reference.sql} is the sole source of column names for the three lookup
 *       tables</b>, because no baseline table exists for them at all -- their content comes from
 *       condition-name literals in a copybook, which declares values and no columns. For those three,
 *       a test that inferred a column name from anywhere else would be inventing one.</li>
 *   <li><b>No test performs a bulk or derived modifying statement.</b> A delete goes through the
 *       inherited keyed operation, so that the declared foreign key is what refuses a restricted
 *       delete and so that the counter above is read and written by the provider rather than
 *       bypassed.</li>
 * </ul>
 *
 * <h2>The envelope, and how backward availability is answered</h2>
 *
 * <p>Assumptions: the published shape is {@code com.carddemo.common.web.PageResponse}, a record with
 * <b>exactly five components</b> in this order: the rows, the leading boundary token, the trailing
 * boundary token, the further-page flag and the earlier-page flag. It declares <b>no
 * page-size or total component</b>, and none is to be added from here. Refactoring Rationale: this
 * paragraph previously said the envelope had no backward-availability component and that an assertion
 * here should infer one from the presence of the leading token. Both halves are superseded: the
 * component exists and is settled by the read that produced the page, and the inference it replaced
 * announced an earlier page on the opening page, because every page returning rows names its own
 * leading row. An assertion here reads the accessor rather than reconstructing the answer.</p>
 *
 * <p>Assumptions: the two boundary components are opaque sealed tokens rather than raw keys, minted by
 * {@code com.carddemo.common.web.CursorToken} in the layer that assembles the envelope. The repository
 * interfaces take and return <b>key values</b>. A test that asserts on a repository method compares key
 * values; a test that asserts on an envelope compares its rows and the presence or absence of each
 * token, never a token's text, because the text is an authenticated encoding and not the key.</p>
 *
 * <p>Assumptions: the envelope's own constructor already rejects an inconsistent instance -- a raw key
 * in a boundary position, a non-empty page missing a boundary, a further-page claim without a
 * trailing boundary, and an earlier-page claim without a leading one. A test here therefore asserts the
 * query's row selection and ordering, and leaves
 * envelope well-formedness to the type that enforces it, rather than restating those four rules as
 * assertions that would pass by construction.</p>
 *
 * <h2>Two test suites, two result models, and the quarantine between them</h2>
 *
 * <p>Assumptions: two unrelated test suites exist in this repository and conflating their result models
 * corrupts both. The suite at {@code tests/} is the COBOL three-layer parity oracle. It is
 * REFERENCE-ONLY: no file under {@code tests/} or {@code scripts/} is modified, re-pinned or re-run
 * differently on account of anything in this package, because that suite is green and touching it
 * invalidates the oracle. Its result model is <b>graded and worst-wins</b> --
 * {@code tests/README.md} line 415 states that runners aggregate the worst code seen, its warn tier is
 * a documented green outcome for that suite, and its line 423 excludes the usage code from aggregation
 * altogether.</p>
 *
 * <p>Assumptions: <b>that rubric is quarantined to {@code tests/} and never reaches a gate here.</b>
 * Every Maven, Surefire, Failsafe, Checkstyle and JUnit outcome in this package is <b>binary</b>:
 * it passes or it fails the build. No return-code tolerance, no command-level failure suppression, no
 * continue-on-error setting, no violation-tolerating plugin flag and no gate-skipping property may be
 * introduced by or for anything in this package, and a build here is never described as green at a warn
 * level, because that phrase belongs to the other suite's vocabulary and would invite a tolerance this
 * one does not have. Trade-offs: carrying two result models in one repository is a genuine cost, paid
 * so that the oracle keeps the convention it was authored with while the Java gates keep the only
 * semantics a build failure can usefully have.</p>
 *
 * <p>Assumptions: one naming collision between the two vocabularies is worth stating, because both
 * candidates are real paragraphs with almost the same name and only one of them abends.
 * {@code tests/README.md} line 422 defines the fatal tier and names {@code 9999-ABEND-PROGRAM} as its
 * example. That is the paragraph in {@code app/cbl/CBACT04C.cbl} at lines 628 to 632, which displays an
 * abend notice, sets a timing value and an abend code, and calls the Language Environment abend
 * service -- a genuine abend. It is <b>not</b> {@code 9999-ABEND} in
 * {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} at lines 230 to 233, which displays a message,
 * moves 4 into the return code and exits normally. A test in this package that reasoned from the wrong
 * one would expect a hard failure where the reference program continues.</p>
 *
 * <p>Assumptions: <b>no golden master exists for the paths this context migrates, which makes this
 * package's assertions primary evidence rather than a supplement.</b> {@code tests/README.md} lines 83
 * to 85 record that the online CICS programs cannot run end to end without a CICS runtime, which the
 * runner does not have, so only their extractable field-validation logic is unit-tested there. The
 * fixture and golden trees under {@code tests/} carry no artefact for the transaction-type,
 * transaction-category or date-conversion paths at all. Parity for this context therefore rests on the
 * transcribed logic together with the data-definition and copybook contracts, and an assertion here is
 * the only place a rule such as the refused delete is demonstrated at all. New tests remain strictly
 * <b>additive</b>, as {@code tests/README.md} lines 587 to 589 state for that suite.</p>
 *
 * <h2>The assertions this package owns</h2>
 *
 * <p>Assumptions: the five below are where the value of this package is concentrated. Each is stated
 * with the evidence it is derived from, so a reader weakening one can see what they would be
 * contradicting.</p>
 *
 * <ol>
 *   <li><b>The foreign key demonstrably refuses a delete of a referenced transaction type, at the
 *       database level.</b> {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} declares it across
 *       lines 6 and 7 as a foreign key on the category table's type column referencing the type table,
 *       with the restrict action; {@code V1__reference.sql} reproduces it at lines 256 and 257. The
 *       refusal travels a fixed chain: the reference platform's referential-constraint failure becomes
 *       the PostgreSQL foreign-key-violation state, which the framework translates into a
 *       data-integrity exception, which {@code com.carddemo.common.error.GlobalExceptionHandler}
 *       renders as HTTP <b>409</b>. <b>This package is the only level at which the constraint itself is
 *       proven</b>; the surfacing of 409 belongs to the {@code api} subpackage, and this assertion may
 *       never be weakened to accept a server-error status in place of the conflict. Assumptions: the
 *       count of referencing categories that one repository declares is a diagnosis and not the
 *       authority -- two callers deleting and inserting at once could each read zero -- so a test
 *       proves the refusal by attempting the delete, not by reading the count.</li>
 *   <li><b>The fallback disclosure group exists after the seed, space-padded to ten characters.</b>
 *       {@code app/cbl/CBACT04C.cbl} declares the group-id field at line 79 as {@code PIC X(10)} and,
 *       when a rate lookup misses with file status 23 at line 436, falls back at line 437 by moving the
 *       seven-character literal {@code 'DEFAULT'} into it. A short literal moved into a ten-byte
 *       alphanumeric field is left-justified and space-filled, so the key actually searched for is that
 *       literal followed by three spaces, and {@code V2__seed_reference.sql} stores it in exactly that
 *       padded form. Trimming either side of the comparison would leave a padded stored value and an
 *       unpadded probe as different values, and the fallback would then match nothing -- a miss that
 *       returns no row rather than an error, so it would surface as an interest figure that was never
 *       produced rather than as a failure at the lookup. This assertion is never omitted.</li>
 *   <li><b>The seed is auditable row by row.</b> {@code V2__seed_reference.sql} loads 7 transaction
 *       types, 18 transaction categories, 51 disclosure-group rows of which 17 belong to the fallback
 *       group, 490 phone area codes, 56 states and 240 state-and-postal-prefix combinations: a lookup
 *       subtotal of 786 and a grand total of <b>862</b>. Assumptions: the three lookup counts are
 *       exactly the literal counts in the baseline's condition names, so a count assertion here is a
 *       statement that the seed transcribed the copybook completely rather than a statement about the
 *       seed alone.</li>
 *   <li><b>The phone-code classification is a disjoint and total two-value partition.</b>
 *       {@code app/cpy/CSLKPCDY.cpy} declares the full allow-list at line 30 with 490 literals, a
 *       general-purpose sublist at line 521 with 410, and an easily-recognisable sublist at line 931
 *       with 80. The two sublists share no member, their union is set-equal to the full list in both
 *       directions, and 410 plus 80 is exactly 490. {@code V1__reference.sql} carries that as one
 *       {@code CHAR(1) NOT NULL} classification column at line 382 under the check constraint at line
 *       407 admitting two values, so the partition is total by the not-null, two-valued by the check,
 *       and disjoint because it is one column per row. Assumptions: <b>the arithmetic is the
 *       authority.</b> Should a description of these lists as overlapping be encountered anywhere, it
 *       is refuted by the sums above, and this assertion must not be weakened to a subset relation or
 *       an approximate count to accommodate it.</li>
 *   <li><b>The keyset asymmetry is transcribed, not smoothed.</b> In
 *       {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} the forward cursor tests the key at
 *       physical line 343 with an <b>inclusive</b> comparison and orders ascending at line 351, while
 *       the backward cursor tests it at physical line 359 with an <b>exclusive</b> comparison and orders
 *       descending at line 367. A backward page is read descending and reversed before it is
 *       published, so rows always reach a caller ascending whichever direction was asked for, and the
 *       surplus row sits at the opposite end on a backward page from where it sits on a forward one --
 *       dropping the wrong end silently removes a row the caller should have seen. The window is
 *       <b>seven</b> rows, declared as a program constant at physical line 60 and corroborated by eight
 *       arrays of seven occurrences, and the further-page flag comes from reading one row beyond the
 *       window and observing whether it exists, never from a count. The one aggregate the baseline does
 *       issue, at physical line 1804 inside the filter check beginning at 1801, carries the filter
 *       predicates and <b>no comparison against the start key at all</b>, so it answers whether the
 *       filter matches anything anywhere and is not a page count.</li>
 * </ol>
 *
 * <p>Assumptions: <b>a counting trap sits directly under the last assertion.</b> The seed loads exactly
 * seven transaction types and the window is exactly seven, so on seeded data alone the whole table is
 * one page and the further-page flag is <b>false</b>. A test that expects it true has to insert an
 * eighth row first. This is recorded because the alternative is meeting it as a puzzling failure in a
 * test whose query and envelope are both correct.</p>
 *
 * <p>Assumptions: <b>a line-number trap sits under it too.</b> Every line cited above is a physical
 * line. In that browse program the printed six-digit sequence field tracks the physical line one for
 * one only as far as physical line 1807; four sequence values are skipped inside the block declaring
 * the record count, after which the printed sequence stands four higher than the physical line for the
 * remainder of the file. Searching for a sequence value therefore finds the wrong line, or none.
 * Verify positionally instead, with {@code sed -n '343p'} or an equivalent, and the two agree.</p>
 *
 * <h2>Two rulings a test author must read the authored source for</h2>
 *
 * <p>Assumptions: <b>the escaping of the description filter is settled by the authored main-tree source
 * and must be read there rather than guessed at.</b> The evidence this charter can supply is the
 * baseline's own: {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} passes the filter across
 * physical lines 348 and 349 as a pattern match against a trimmed host variable, with <b>no escape
 * clause</b> and no wildcard added by the program, so the baseline treats a caller-supplied wildcard as
 * a wildcard. Whether the migrated query preserves that or escapes the two pattern metacharacters is a
 * decision recorded in the query's own documentation, and a test that encoded the opposite choice would
 * pass or fail on the guess rather than on the behaviour. Read the method, then assert what it
 * states.</p>
 *
 * <p>Refactoring Rationale: <b>the subtree charter's statement that this package has no counterpart is
 * superseded by this package existing, and by nothing more.</b>
 * {@code services/reference-service/src/test/java/com/carddemo/reference/package-info.java} records at
 * its lines 65 to 70 that the main tree's repository package has no mirror in the test tree because the
 * assertions belonging there need a database. That reasoning was sound and its conclusion no longer
 * holds: the database is supplied by a container. Everything else in that file continues to govern this
 * package and is cited rather than restated -- the mirroring property, the runner split, the default
 * report directories, the prohibition on distance-positioned reads, the read-only status of the
 * baseline tree, and the quarantine between the two suites. Assumptions: that file is not edited from
 * here; a correction is recorded at the package the correction is about, so that a reader who arrives
 * at either file finds the discrepancy named instead of silently resolved in one of them.</p>
 *
 * <h2>Explainability</h2>
 *
 * <p>Assumptions: the project Explainability rule attaches its docstring obligation to a module entry
 * point at line 15, and in Java a package declaration is that entry point, which is the whole reason
 * this file exists. Its Validation Gate at line 43 is conjunctive: a docstring alone does not pass and
 * a justification alone does not pass, so this charter carries a stated purpose and every non-obvious
 * ruling in it carries one of the rule's four named justification categories, drawn from its lines 31
 * to 34. Its line 41 forbids a vague rationale, which is why each ruling names a concrete mechanism and
 * the consequence of choosing otherwise rather than asserting a preference. The written convention this
 * file follows is {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never restated.</p>
 *
 * <p>Assumptions: the rule governs newly authored code only. The reference baseline under {@code app/}
 * and the existing suite under {@code tests/} are read-only for this migration, so neither is
 * retro-documented; doing so would breach the reference-only boundary rather than merely exceed the
 * rule. Where a migrated behaviour differs from the baseline deliberately, the permitted form is that
 * the baseline does one thing at a named path and line, the Java does another, and the difference is
 * registered in {@code docs/architecture/cobol-to-service-traceability.md} -- a document this package
 * references and does not author.</p>
 */
package com.carddemo.reference.repository;
