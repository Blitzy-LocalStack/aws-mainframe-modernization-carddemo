/**
 * Integration tests for the read-only data-access layer of the reporting bounded context.
 *
 * <h2>Purpose and charter</h2>
 *
 * <p>This directory holds the tests of this module that need a real database engine before they can
 * say anything at all. It is the test-tree counterpart of the production package of the same name,
 * and the two are deliberately not symmetrical: the production package declares six repository roles,
 * while a role only earns a class here if the property that governs it is invisible to every cheaper
 * gate.
 *
 * <p>⚠️ Refactoring Rationale: this section previously opened by asserting there were "three of them
 * because there are exactly three such properties", and named three classes. A review re-measured the
 * directory and found EIGHT. The three named were the first three authored, and the sentence was left
 * standing as five more landed -- each of which states its own non-overlap in its own class Javadoc, so
 * the coverage was sound and only this charter was wrong. That is the more dangerous failure of the
 * two: a reader deciding where a new engine-backed property belongs consults this file, and a census
 * short by five tells them a boundary is unclaimed when it is owned. The census below is therefore
 * MEASURED and carries the marker the shared kernel's inventory check re-measures, so the same drift
 * fails a build instead of surviving a reading. That marker is stated ONCE, below, and it counts the
 * whole directory the way every sibling charter does -- ten Java files: the
 * nine container-backed classes, the charter census beside them, and this charter itself.
 *
 * <p>Assumptions: the count above is a CENSUS and not a budget. What is durable is the set of
 * engine-backed properties and which class owns each, because that is what a new class has to be
 * placed against; the total moves whenever a genuinely new property is claimed, and the marker moves
 * with it. Each entry states the properties its class owns and the number of cases it DECLARES, being
 * methods annotated as a test or a parameterised test rather than the larger number a run reports once
 * a parameterised method expands -- the same metric the inventory check measures:
 *
 * <ul>
 *   <li>{@code ReportingQueryBootstrapIT}, across 1 cases -- establishes no relation at all and proves
 *       every declared query PARSES against the metamodel.</li>
 *   <li>{@code StatementHeadingChunkIT}, across 2 cases -- seeds stand-in tables and proves the
 *       heading walk's keyset predicate reproduces the whole of its own two-component ordering.</li>
 *   <li>{@code ReportingDeployedRelationIT}, across 9 cases -- applies the SHIPPED definitions and
 *       proves the projections narrow a card number, that a card joins to its customer and its
 *       account, and that the login role can read those projections and write nothing.</li>
 *   <li>{@code StatementCardXrefRepositoryIT}, across 13 cases -- owns the DRIVING cursor of a
 *       statement run, the fifty-byte cross-reference geometry, and the two-level write refusal
 *       against a base table in another context's schema.</li>
 *   <li>{@code StatementCustomerRepositoryIT}, across 10 cases -- owns the whole-key customer read and
 *       the property that an unresolved identifier stops the run rather than ending it.</li>
 *   <li>{@code StatementAccountRepositoryIT}, across 12 cases -- owns the keyed account read, its
 *       eleven-digit key, and the exactness of the money it carries at twelve digits and scale two.</li>
 *   <li>{@code StatementTransactionRepositoryIT}, across 17 cases -- owns the per-card transaction
 *       cursor: the statement record geometry rather than the posting one, the job's two-key order,
 *       and the absence of any arity ceiling where the reference declares fixed tables.</li>
 *   <li>{@code TransactionReportRepositoryIT}, across 38 cases -- owns the report surface's two
 *       readings of one date range, the whole-range cursor and the keyset window, which are never
 *       interchangeable.</li>
 *   <li>{@code CategoryBalanceReportRepositoryIT}, across 3 cases -- owns the category-balance
 *       walk, which returns rows in account, then type, then category order. That role declares its
 *       whole query by METHOD NAME, so the three ordering components and their sequence exist only
 *       inside an identifier; these cases prove each component RESOLVES against the deployed view and
 *       that the three are applied in the declared sequence.</li>
 * </ul>
 *
 * <p>Assumptions: the three properties described next are the first three of the eight above rather
 * than the whole of the directory, and they are set out at length because each is the reason a class
 * exists at all rather than a restatement of what it asserts. The remaining five state their own
 * reasoning in their own class Javadoc, each naming the siblings it does not overlap, and are
 * deliberately not paraphrased here: a paraphrase is the thing that drifted.
 *
 * <pre>
 * this directory: 11 java files = 10 tests + 1 charter
 * </pre>
 *
 * <p>Refactoring Rationale: that census read "three of them because there are exactly three such
 * properties" while eight classes sat in the directory, and the five it omitted are the five that
 * cover a repository role each. Nothing detected it, because the figure lived in a different file
 * from the thing it counted, so every change that falsified it left it untouched. The census is now
 * measured rather than asserted: the marker line above is re-counted by
 * {@code com.carddemo.common.architecture.PackageCharterInventoryTest}, which also requires every
 * class enumerated below to be a file in this directory, and {@code RepositoryCharterCensusTest}
 * re-counts both censuses inside this module so the drift fails this module's own build too.
 * Alternatives Considered: keeping the census as prose and reviewing it by eye, which is what was
 * done before. Rejected on evidence -- this charter carried a stale class count and a stale fixture
 * count at the same time, and both had survived review.
 *
 * <p>The eight are:
 *
 * <ul>
 *   <li>{@code ReportingQueryBootstrapIT} -- whether the declared queries parse. Cross-cutting:
 *       it establishes no relation at all;</li>
 *   <li>{@code StatementHeadingChunkIT} -- whether a keyset predicate reproduces the whole of its
 *       own ordering. Cross-cutting: it seeds stand-ins rather than the shipped relations;</li>
 *   <li>{@code ReportingDeployedRelationIT} -- whether the shipped relations mask, join and refuse
 *       writes as shipped. Cross-cutting: it applies the authoritative definitions unchanged;</li>
 *   <li>{@code StatementTransactionRepositoryIT} -- the statement cursor: one card's whole run in
 *       the baseline's own order, at exact fixed-point scale, with no arity ceiling;</li>
 *   <li>{@code StatementCardXrefRepositoryIT} -- the driving traversal of a whole statement run,
 *       whose exhaustion is what ends the run;</li>
 *   <li>{@code StatementCustomerRepositoryIT} -- the customer lookup as an exact whole-key read
 *       whose empty answer aborts rather than skips;</li>
 *   <li>{@code StatementAccountRepositoryIT} -- the account lookup as an exact whole-key read at
 *       its declared precision, unwritably;</li>
 *   <li>{@code TransactionReportRepositoryIT} -- the report surface's two reads of one date range,
 *       a full-range stream and a keyset window, which are never interchangeable.</li>
 * </ul>
 *
 * <p>The first cross-cutting property is whether the declared queries PARSE. The roles beside them
 * declare queries -- derived from method names, and written out for the report join -- and a query
 * naming a
 * property the metamodel does not carry, or joining two entities along a path that does not exist,
 * is a defect no compiler reports and no unit test reaches. It surfaces when the repository proxy is
 * created, which needs an entity manager factory, which needs a data source.
 * {@code ReportingQueryBootstrapIT} provokes that moment and deliberately establishes no relation,
 * because parsing resolves against the metamodel rather than against rows.
 *
 * <p>The second cross-cutting property is whether a keyset predicate reproduces the whole of its
 * own ordering. {@code StatementHeadingChunkIT} proves that for the statement heading walk, and
 * unlike the first it needs ROWS. {@code StatementCardXrefRepository.findHeadingChunk} pages a
 * relation ordered by the masked card rendering and then by the per-card fingerprint; a predicate
 * comparing one
 * component of a two-component ordering parses perfectly, so the first class passes on it, and a
 * stubbed repository answers whatever a unit test arranges, so the service's own tests pass on it
 * too. The property is a relationship between a predicate and an ordering over real rows, so
 * nothing short of an engine can assert it. Refactoring Rationale: that class exists because
 * exactly that defect reached this repository with no gate here able to see it -- cards were skipped
 * and others repeated, and neither outcome raised anything -- which is why this charter states the
 * directory's purpose as more than one property.
 *
 * <p>The third cross-cutting property is whether the SHIPPED relations behave as the shipped
 * relations. {@code ReportingDeployedRelationIT} proves that. It is not the only class here that
 * needs rows AND the real definitions -- the five per-role classes apply the same eleven
 * authoritative files in the same order -- and what is its own is the SUBJECT: those five each read
 * one role through the shipped relations, while this one asserts the relations themselves. Three
 * things this context depends on live entirely inside view definitions and grants rather than inside
 * any Java: a card number is narrowed to its last four digits before it is published, a card resolves
 * across three separate projections to its customer and its account, and the login role those
 * projections are read under can read them and cannot write anything at all. Refactoring Rationale:
 * this charter previously stated the directory's purpose as two properties, and it was written when
 * the card and cross-reference fixtures had a codec-only
 * consumer. A codec settles record geometry; it cannot observe a masking expression, a join predicate
 * or a privilege, so all three shipped controls were unasserted anywhere in the repository.
 * Trade-offs: this class and the five per-role classes are the slowest in the module, because each
 * applies eleven script executions before its first assertion. That is accepted, and the alternative
 * was not a faster class but a weaker one -- see "How relations arrive here" below, where the
 * boundary they sit inside is drawn.
 *
 * <p>Six invariants govern every class in this directory, and each is inherited rather than restated
 * from scratch, so that a reader who disagrees with one knows which document to argue with:
 *
 * <ul>
 *   <li>this module owns no relational object and no migration directory of its own. It reads
 *       read-only cross-schema views under a dedicated login role holding read privileges only;</li>
 *   <li>no schema-definition statement here may create, privilege or discard anything the running
 *       service reads. The one narrow exception, and the precise boundary it draws, is set out under
 *       "How relations arrive here" below rather than left to inference;</li>
 *   <li>there is no migration tooling on this module's classpath and no {@code db/migration}
 *       directory anywhere in it. {@code services/reporting-service/src/main/resources/db} does not
 *       exist, and the absent tooling is named once, in the DELIBERATELY ABSENT block at
 *       {@code services/reporting-service/pom.xml} L430, so this charter does not become a second
 *       place to maintain that decision;</li>
 *   <li>there is no read replica. Reads go to the writer through the views;</li>
 *   <li>result windows are positioned by key, never by the ordinal of a row;</li>
 *   <li>no money value passes through a binary floating-point type or through a bare JSON number.</li>
 * </ul>
 *
 * <p>Assumptions: the package-wide labelled-decision register is authored ONCE, in the main-tree
 * charter at
 * {@code services/reporting-service/src/main/java/com/carddemo/reporting/repository/package-info.java},
 * whose register opens at its L199 and runs from R1 to R15. That file states at its L203-L204 that
 * the register is authored there once and cited elsewhere by row identifier, so this charter and the
 * classes beside it CITE a row rather than restating it. The last four invariants above are
 * respectively R11, R12, R1 and the money-path rule the shared architecture test owns. Duplicating a
 * row here would create two sources of truth for one decision, and the failure mode of that is not a
 * contradiction a reader can see but a silent divergence after one side is edited.
 *
 * <p>Assumptions: three of those invariants are not merely conventions of this directory. R11
 * records that when a relation this layer needs is absent at run time the only correct action from
 * here is to report a defect against the data-migration package -- which
 * {@code data-migration/sql/V0__schemas_and_roles.sql} instructs directly at its L565-L567 -- because
 * this login role holds only the schema usage conveyed at that file's L944 and could not create the
 * relation in any case. R12 names the specific behavioural cost a replica would carry rather than
 * leaving it a generality: the baseline statement job reads the live transaction cluster directly at
 * {@code app/jcl/CREASTMT.JCL} L45, so it cannot omit a transaction the online path has already
 * accepted, whereas a statement generated from a lagging replica can.
 *
 * <h2>The naming contract that decides whether a class here runs at all</h2>
 *
 * <p>A class in this directory is selected by {@code maven-failsafe-plugin}, which
 * {@code services/pom.xml} declares at its L1222-L1225 as a bare coordinate: no version, no
 * executions block and, most consequentially, NO include configuration. The Spring Boot parent's
 * plugin management already binds the {@code integration-test} and {@code verify} goals, so
 * declaring the coordinate alone is what turns those bindings on. Because no include is configured,
 * the plugin's own default selection applies, and that default -- read from the resolved descriptor
 * of {@code maven-failsafe-plugin} 3.5.6 -- is the three patterns {@code IT*}, {@code *IT} and
 * {@code *ITCase}, so it matches a leading {@code IT} as well as a trailing {@code IT} or
 * {@code ITCase}. EVERY class in this directory ends in {@code IT} and is therefore selected, which
 * is the invariant to preserve rather than a headcount to maintain.
 * {@code services/pom.xml} records that this is deliberate at its L1213-L1215: the naming
 * this project uses already matches the default pattern, so an include configuration is neither
 * needed nor wanted.
 *
 * <p>Assumptions: the hazard is consequently the opposite of the one a reader might expect, and it
 * is worth stating precisely because getting it backwards would invite a pointless rename. Nothing
 * is lost by ending a name in {@code IT}. What IS lost is a name the integration-test plugin does not
 * match: a class ending in {@code Test}, {@code Tests} or {@code TestCase} is claimed instead by the
 * plugin that runs this module's unit tests -- those three are among Surefire's own four default
 * patterns, the fourth being a leading {@code Test} -- so for a container-backed class it runs during
 * the {@code test} phase, before packaging, and its result is not asserted by {@code verify}. A name
 * matching NEITHER plugin's patterns -- a trailing {@code Spec} or {@code Should}, say -- is run by
 * neither, and that is the dangerous case, because it compiles, the build goes green, and it proves
 * nothing. Refactoring Rationale: this paragraph previously offered "a trailing {@code Tests}" as its
 * example of the name run by neither plugin. That example was wrong: Surefire's defaults include
 * {@code *Tests}, so such a class runs in the wrong phase rather than not at all, and a reader who
 * trusted the example would have looked for silence and found a container starting before packaging
 * instead. The two failure modes are different defects with different symptoms, so each now carries
 * an example that genuinely produces it.
 *
 * <p>Execution is therefore proven by artifact rather than by assumption:
 * {@code services/reporting-service/target/failsafe-reports} must list a report for every one of the
 * eight, and {@code surefire-reports} one for the census class. Assumptions: those paths are the
 * plugins' DEFAULTS and are deliberately not relocated, as {@code services/pom.xml} records at its
 * L1216-L1220, because the continuous-integration workflow collects from exactly those two
 * locations -- so moving either would make the build green while the workflow published nothing.
 *
 * <h2>The container mechanism, and the document that is its authority</h2>
 *
 * <p>Seven of the eight declare a {@code static} {@code PostgreSQLContainer} annotated
 * {@code @Container} and {@code @ServiceConnection}, and every one of the eight activates the
 * {@code test} profile. Assumptions: the eighth, {@code ReportingDeployedRelationIT}, starts its
 * container from a static initialiser and carries neither annotation, and the exception is recorded
 * in that class rather than argued here: its pool authenticates as a role the bootstrap creates and
 * runs a connection-initialisation statement naming a schema, so both must exist before the first
 * pooled connection opens, and class initialisation is ordered before any context is built for it by
 * the language rather than by an extension. The
 * authority on that mechanism is {@code services/reporting-service/src/test/resources/application-test.yml},
 * and it is an authority by what it OMITS: it declares no datasource location, no login name and no
 * credential of any kind, precisely so those coordinates arrive as a bean from the annotated container.
 * That file argues the point at its L168-L177. Alternatives Considered: a literal connection string
 * committed in that document would address whichever engine happened to be listening on the author's
 * host, and the failure mode of that mistake is not an error but a test that PASSES against the
 * wrong data; registering the coordinates through a dynamic property source would work but restates
 * in Java what the container already knows, so the two can drift. The container's port is assigned as
 * it starts, so committed text could not have been correct in any case.
 *
 * <p>Four values in that document are load-bearing for what is asserted here, and each is quoted
 * with its line so an assertion can be read against the setting it depends on rather than against a
 * recollection of it:
 *
 * <ul>
 *   <li>{@code spring.datasource.hikari.connection-init-sql} at its L240 pins schema resolution to
 *       the SINGLE schema {@code reporting}. Assumptions: this is narrower than the phrase
 *       "cross-schema views" suggests and the narrowness is enforced rather than merely intended --
 *       {@code DataSourceConfig} in the sibling {@code config} package refuses any statement naming
 *       more than the one permitted schema, and {@code DataSourceConfigTest} asserts that refusal,
 *       so the narrowness is compiled in rather than merely intended. Naming the
 *       source schemas instead would be wrong twice over: it would resolve nothing, because a
 *       search-path entry whose schema the role may not use is ignored in silence; and were that
 *       access ever conveyed, unqualified names would begin resolving to BASE TABLES rather than to
 *       the views, so the masking the views carry would be bypassed silently by queries nobody had
 *       changed;</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto} at its L328 is {@code none}, so the persistence
 *       provider emits no schema-generation statement. Assumptions: this is what makes the read-only
 *       posture provable at all -- generated statements would fabricate exactly the eight mapped
 *       relations this role must be unable to make, so the assertion would then pass against
 *       relations the test itself had created;</li>
 *   <li>{@code spring.sql.init.mode} at its L425 is {@code never}, so a script arriving in this
 *       module's test resources is not picked up by name alone and cannot run against a login role
 *       whose inability to write is one of the properties under test;</li>
 *   <li>{@code hibernate.jdbc.time_zone} at its L407 is {@code UTC}. Assumptions: a deployed task
 *       runs in a fixed zone by construction and a build host does not, and the report's date filter
 *       is inclusive at BOTH ends over the first ten characters of a 26-character processing
 *       timestamp -- the comparisons at {@code app/cbl/CBTRN03C.cbl} L173-L174 are greater-or-equal
 *       against the start date and less-or-equal against the end date. A one-hour shift applied by a
 *       host in a negative offset therefore moves a boundary row across a closed bound, and the same
 *       fixture would yield a different report on two hosts.</li>
 * </ul>
 *
 * <p>Assumptions: one value in that document is routinely misread and the misreading is recorded
 * here so it is not repeated. {@code hibernate.jdbc.fetch_size} at its L380 is NOT what makes the
 * statement or the report stream, and that file says so at its L356-L361: the default governs only
 * queries carrying no hint of their own, and the two reads that matter carry their own query hints on
 * {@code TransactionReportRepository} and {@code StatementTransactionRepository}. Trade-offs: the
 * value is also not a cap of any kind. It bounds rows in flight per round trip, and bounds neither
 * how many transactions one statement may carry nor how many cards one run may cover -- the target
 * carries no fixed arity, which is a divergence recorded in
 * {@code docs/architecture/cobol-to-service-traceability.md} rather than a property of this setting.
 *
 * <p>Assumptions: what that document does NOT declare shapes how a context may be assembled here.
 * It carries no orchestrator execution identifier, no object-store bucket, no object-key prefix and
 * no artifact-signing material, and it declares the token issuer as the EMPTY STRING at its L483
 * rather than leaving the key absent. The empty value is load-bearing and the mechanism is subtle:
 * the inherited declaration is a placeholder with no fallback, and the framework's issuer condition
 * reads the key through an eagerly-resolving lookup, so merely EVALUATING that condition on a host
 * supplying no such variable aborts the context refresh while bean definitions are still loading,
 * reporting the condition rather than the missing value. Absent would mean inherited, which is
 * precisely the unresolvable form. Consequently a context here may not scan the module root: each
 * class declares a narrow nested configuration naming only the entity and repository packages, and
 * supplies in its own source the few values it needs. Alternatives Considered: stubbing those values
 * in the shared profile for symmetry was rejected, because a fabricated location or resource
 * identifier in a committed test resource is indistinguishable from a real one to a scanner, whereas
 * an unresolved inherited placeholder cannot be mistaken for something reachable.
 *
 * <p>Assumptions: the container dependencies carry no version of their own. The parent imports
 * {@code org.testcontainers:testcontainers-bom} at version 2.0.5 through the property at
 * {@code services/pom.xml} L364, and this module declares only artifact coordinates. Those
 * coordinates are the 2.x PREFIXED spellings {@code testcontainers-postgresql} and
 * {@code testcontainers-junit-jupiter}, not the 1.x short names, which
 * {@code services/reporting-service/pom.xml} records at its L337-L340 as being absent from that BOM
 * -- verified against the BOM rather than assumed, because using a short name leaves the dependency
 * with no managed version and the resulting error names a missing version rather than a wrong name.
 * The container class accordingly imports from the 2.x package. Trade-offs:
 * {@code org.testcontainers:testcontainers-localstack} and every cloud emulator are declined
 * deliberately, at that POM's L507-L510: what is worth proving about the orchestration call is which
 * input the request carries and that a failure propagates, and neither needs a container.
 *
 * <h2>How relations arrive here without this module owning one</h2>
 *
 * <p>The relations the running service reads are authored outside this module, in two files and in a
 * fixed order, because a view cannot precede the relations it reads:
 * {@code data-migration/sql/V0__schemas_and_roles.sql} establishes the eight schemas, the per-service
 * roles, the narrowly-scoped cross-schema grants and this service's read-only login role; and
 * {@code data-migration/sql/V1__reporting_views.sql} establishes the views themselves and the
 * per-view read privileges, ordered after every per-service migration -- those being
 * {@code services/account-service/src/main/resources/db/migration/V1__account.sql},
 * {@code services/card-service/src/main/resources/db/migration/V1__card.sql},
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} and
 * {@code services/reference-service/src/main/resources/db/migration/V1__reference.sql} with its
 * companion {@code V2__seed_reference.sql}.
 *
 * <p>Assumptions: the {@code reporting} schema IS this context's own, the eighth of eight, and
 * stating otherwise puts the absence in the wrong place. What makes this MODULE carry no migration
 * directory is that the schema's CONTENTS are authored outside it under a no-login owner role, which
 * is also what lets a view read a base table the service's own login cannot. The main-tree charter
 * records this at its L122-L129.
 *
 * <p>The failure policy follows from ownership and is not discretionary. A view or a privilege
 * missing at run time surfaces as a permission or undefined-relation diagnostic naming the view, and
 * that is a defect to REPORT against whichever of those two files owns it -- never something for a
 * class here to remedy. Trade-offs: a local remedy would place a second, drifting definition of a
 * masking view inside the one module deliberately unable to replace one, and this login role could
 * not create it anyway. Register row R11 is the authority, and it names the instruction at
 * {@code data-migration/sql/V0__schemas_and_roles.sql} L565-L567 and the usage grant at that file's
 * L944.
 *
 * <p>That leaves two exceptions, and each is drawn narrowly on purpose rather than waved through.
 * Assumptions: they are two MECHANISMS and not two classes, and the nine classes divide unevenly
 * between them -- one class needs no relation at all and creates none, one uses the first mechanism,
 * and the remaining seven use the second.
 *
 * <p>The first is a STAND-IN. {@code StatementHeadingChunkIT} needs ROWS in an ordered relation, so it
 * applies {@code db/testharness/test-harness-reporting-relations.sql} to its own container as a
 * classpath-relative initialisation script. Assumptions: that script creates its three stand-ins as
 * PLAIN TABLES and never as views, and it creates nothing else -- a measurement of every SQL artifact
 * in this module's test tree finds a single schema creation and three table creations and NOTHING
 * ELSE: no view creation, no privilege grant or withdrawal, no alteration and no discard, in any file
 * under {@code src/test/resources}. Alternatives Considered: copying the two authoritative files onto
 * this classpath so the harness would exercise the real views. Rejected, because a copy drifts from its
 * authority and a passing assertion would then assert agreement with a stale copy rather than with the
 * real schema. Trade-offs: the accepted cost is stated plainly in that class -- a stand-in cannot
 * detect a mismatch between an entity mapping and a view, so that class proves the predicate
 * reproduces the ordering and does NOT prove the view's shape. Plain tables are chosen over views for
 * the same reason the exception is narrow: a table cannot be mistaken for a second definition of a
 * masking view.
 *
 * <p>The second is the AUTHORITY ITSELF, and it is what the first exception's rejection deliberately
 * did not cover. SEVEN classes read the shipped files out of the working tree and send them to their
 * own container UNCHANGED -- {@code ReportingDeployedRelationIT},
 * {@code CategoryBalanceReportRepositoryIT}, {@code StatementAccountRepositoryIT},
 * {@code StatementCardXrefRepositoryIT}, {@code StatementCustomerRepositoryIT},
 * {@code StatementTransactionRepositoryIT} and {@code TransactionReportRepositoryIT} -- and each
 * applies the SAME eleven entries in the same order: the bootstrap at
 * {@code data-migration/sql/V0__schemas_and_roles.sql}, the four owning services' migrations that
 * create the base tables, and {@code data-migration/sql/V1__reporting_views.sql}. So those classes do
 * create views and do issue grants, and none of them defines either. Assumptions: each carries its own
 * copy of that list rather than sharing one, because each starts its own container and a shared holder
 * would put one class's arrangement inside another's lifecycle; the seven lists are identical entry for
 * entry, which is a property a reader can check by eye and the reason the list is quoted once here.
 * Assumptions: the prohibition below exists to stop a SECOND, DRIFTING DEFINITION living on this
 * classpath, and applying the only definition there
 * is cannot produce one: there is nothing here to drift from the authority, because the authority is
 * what ran. Assumptions: the bootstrap is applied TWICE, before and after the service migrations,
 * because the cross-schema read grants it issues to the projection owner are conditional on base
 * tables existing and on a first pass none of them does; the projections are definer-rights, so
 * without the second pass the first read fails with a privilege diagnostic even for a superuser.
 * Trade-offs: those classes are coupled to file PATHS outside their own module, so moving a migration
 * breaks all seven at once. That is accepted and is arguably the point -- a shipped DDL file that no
 * longer applies cleanly from a clean database is a defect whether or not a test notices.
 *
 * <p>The boundary is therefore crisp, and it is a boundary on DEFINITIONS rather than on verbs or on
 * files. Inserting rows is permitted, because fixtures have to be loaded before anything can be read.
 * Executing an authoritative definition unchanged is permitted, because it introduces no second
 * definition. AUTHORING a definition of anything the running service reads -- a view above all -- is
 * not permitted anywhere in this tree, and no file here does.
 *
 * <h2>Why the roles under test have deliberately different shapes</h2>
 *
 * <p>Assumptions: the shapes are not a stylistic choice, and the evidence is one dispatcher in the
 * baseline. {@code app/cbl/CBSTM03B.CBL} L118 evaluates the DD NAME first and the opcode only
 * inside the branch it selects, and the four branch bodies are NOT uniform. Reading them is what
 * settles how many roles there are:
 *
 * <ul>
 *   <li>{@code 'TRNXFILE'}, L133 to L155 -- open at L135, a PLAIN read at L140, close at L146. There
 *       is no keyed-read branch, and the file is declared with sequential access at L33;</li>
 *   <li>{@code 'XREFFILE'}, L157 to L179 -- open at L159, a PLAIN read at L164, close at L170. Again
 *       no keyed-read branch, and sequential access at L39;</li>
 *   <li>{@code 'CUSTFILE'}, L181 to L204 -- open at L183, a KEYED read and nothing else at L188,
 *       close at L195, with random access declared at L45. There is no plain-read branch;</li>
 *   <li>{@code 'ACCTFILE'}, L206 to L229 -- open at L208, a KEYED read and nothing else at L213,
 *       close at L220, with random access declared at L51. Again no plain-read branch;</li>
 *   <li>anything else falls to {@code WHEN OTHER} at L127 to L128, which transfers to the exit
 *       paragraph and returns without touching a status field.</li>
 * </ul>
 *
 * <p>Two ordered scans and two keyed lookups therefore follow from the source rather than from
 * preference, and they must not be collapsed behind one interface. Register row R3 is the authority
 * for replacing the single dispatcher with separate roles, and R4 for the last bullet: an unsupported
 * read request raises rather than returning a stale status, which is the divergence from a dispatcher
 * whose {@code WHEN OTHER} arm leaves the caller's status field holding whatever it held before.
 *
 * <p>Assumptions: there is no write surface on any of the four, and the suite asserts that absence
 * rather than merely omitting it. The opcodes for write and rewrite are DECLARED and referenced
 * nowhere: a search across the baseline returns exactly four occurrences, all of them condition-name
 * declarations, at {@code app/cbl/CBSTM03B.CBL} L107 and L108 and at {@code app/cbl/CBSTM03A.CBL}
 * L78 and L79. A search of the caller for the statement that would select one yields only open,
 * close, read and keyed read. An unexercised opcode is indistinguishable from an unsupported one from
 * the caller's side, so the roles expose no write method at all.
 *
 * <p>Assumptions: the keyed read is an EXACT FULL-KEY read and not a partial-key browse, and this is
 * recorded because the opposite reading is an easy mistake with a consequence. The caller moves a
 * transient zero into the key-length field at {@code app/cbl/CBSTM03A.CBL} L373 and L397 and then
 * IMMEDIATELY recomputes it at L374 and L398 to the declared length of the key it is about to use --
 * nine for the customer identifier and eleven for the account identifier, per
 * {@code app/cpy/CVACT03Y.cpy} L6 and L7 -- and a search of the baseline for a zero-length reference
 * modification returns nothing at all. The consequence is that keyset positioning must NOT be
 * justified from the keyed-read opcode, because that opcode addresses one row by a whole key. Its
 * provenance is elsewhere: {@code app/cbl/COCRDLIC.cbl} L230 to L244 carries a last-key pair, a
 * first-key pair, a screen ordinal, a last-page-displayed flag and a next-page-exists indicator, and
 * that structure is already a keyset cursor. Register row R1 records the decision, and R2 records
 * which key a backward walk returns.
 *
 * <p>The six roles the production package declares therefore take four distinct shapes, and the
 * eight classes here reach them accordingly -- five of them one role each, and the three
 * cross-cutting ones through whichever roles their property spans. {@code StatementTransactionRepository} and
 * {@code StatementCardXrefRepository} are streaming cursors with no lookup by identifier;
 * {@code StatementCustomerRepository} and {@code StatementAccountRepository} are keyed lookups
 * returning an optional projection and nothing else; {@code TransactionReportRepository} carries TWO
 * surfaces that must never be conflated, a full-range stream for generating the report and
 * forward-and-backward keyset methods returning {@code com.carddemo.common.web.PageResponse} for the
 * interactive list; and {@code CategoryBalanceReportRepository} is an ordered traversal with no
 * program behind it at all, the report it feeds being a sort step, in the key sequence declared at
 * {@code app/jcl/PRTCATBL.jcl} L52.
 *
 * <p>Assumptions: the cross-reference scan is the DRIVING cursor of the statement run, and the two
 * keyed lookups abort it rather than skipping a row. Of the three read paragraphs the mainline loop
 * reaches, only the cross-reference one carries an end-of-file arm --
 * {@code app/cbl/CBSTM03A.CBL} L353 to L362 -- and the loop at L316 to L329 continues until that arm
 * fires, so its exhaustion is what ends the run. The customer read at L379 to L386 and the account
 * read at L403 to L410 carry NO such arm: anything other than a clean status reaches the abend
 * paragraph at L921, whose body at L922 and L923 displays and then calls the language-environment
 * abend routine. An empty optional from either keyed role is therefore a referential-integrity
 * violation that must abort, never a row to pass over, which is register row R10. Assumptions: a
 * fourth read path exists and is deliberately NOT counted among those three, because miscounting it
 * would misplace the termination condition -- the transaction read at L818 to L847 has an end-of-file
 * arm at L841, but it belongs to the open-phase pre-load entered from L730 and L744, and the
 * paragraph the mainline loop calls at L416 reads no file at all, walking instead the in-memory
 * tables that pre-load filled.
 *
 * <p>The projections those roles return live in the sibling {@code domain} package and number
 * EIGHT: {@code StatementTransactionView}, {@code CardXrefView}, {@code CustomerView},
 * {@code AccountView}, {@code ReportTransactionView}, {@code TransactionTypeView},
 * {@code TransactionCategoryView} and {@code TransactionCategoryBalanceView}. Refactoring Rationale:
 * that figure read seven and omitted the last of them, which is the projection
 * {@code CategoryBalanceReportRepository} traverses -- the same omission as the class census above
 * and from the same cause, a count stated in one file and measured in another.
 * Assumptions: each maps to a view rather than to a table another context owns, which is why nothing
 * here references another service's domain package -- agreement with another bounded context is
 * through the physical relation, never through a compile-time dependency.
 *
 * <h2>The four record lengths that corroborate a fixture</h2>
 *
 * <p>Assumptions: every length below is derived by SUMMING the declared field widths, never by
 * trusting a descriptive banner, because a banner is a comment and a comment cannot be wrong in a way
 * the compiler notices. From the file section of {@code app/cbl/CBSTM03B.CBL}: the transaction record
 * at L58 is 16 plus 16 plus 318, which is 350; the cross-reference record at L65 is 16 plus 34, which
 * is 50; the customer record at L70 is 9 plus 491, which is 500; and the account record at L75 is 11
 * plus 289, which is 300. ALL FOUR are now corroborated independently by measurement of the fixtures
 * in this module: {@code acctfile.txt} carries 300-byte rows, {@code custfile.txt} 500-byte rows,
 * {@code cardxref.txt} and {@code xreffile.txt} 50-byte rows, and {@code tranfile.txt} and
 * {@code trnxfile.txt} 350-byte rows. Refactoring Rationale: this paragraph read "THREE of the four",
 * with the 350-byte transaction record called out as "the one length no fixture in this module
 * measures" -- true of a seven-fixture directory and false since the report and statement transaction
 * fixtures arrived. The correction matters in one direction only: the sentence invited the next author
 * to keep the transaction length as a sum nothing checks, when two committed fixtures now measure it,
 * and a length measured on both sides is the only kind that cannot drift silently. Both derivations
 * are kept, because they are independent: the sum reads the baseline's own declaration, the
 * measurement reads bytes, and agreement between them is the evidence.
 *
 * <p>Assumptions: one artifact of the baseline is recorded here because a reader summing those widths
 * will meet it and should not conclude the arithmetic above is wrong. The same field name
 * {@code FD-ACCT-DATA} is declared twice with two different widths -- 318 bytes at L63, inside the
 * transaction record, and 289 bytes at L78, inside the account record. The two are separate group
 * items in separate record descriptions, so the baseline behaves exactly as written and both sums
 * hold. Following the house convention for an immutable baseline, the observation is stated in one
 * permitted framing only: the baseline declares the name twice, the target carries two distinct
 * projections, and the divergence is documented rather than resolved here. Nothing in this directory
 * edits, or may edit, that source.
 *
 * <h2>The fixtures, bound by exact name</h2>
 *
 * <p>Fixtures live in {@code services/reporting-service/src/test/resources/fixtures}, and this
 * module binds TEN fixtures by exact name, each with the copybook descriptor it decodes against and
 * the record length and row count the contract test asserts: {@code acctfile.txt} as
 * {@code ACCOUNT} at 300 bytes over 4 rows, {@code carddata.txt} as {@code CARD} at 150 bytes over
 * 5 rows, {@code cardxref.txt} as {@code XREF} at 50 bytes over 4 rows, {@code custfile.txt} as
 * {@code CUSTOMER} at 500 bytes over 4 rows, {@code tcatbal.txt} as {@code TCATBAL} at 50 bytes
 * over 8 rows, {@code trancatg.txt} as {@code TRANCAT} at 60 bytes over 9 rows,
 * {@code tranfile.txt} as {@code TRAN} at 350 bytes over 31 rows, {@code trantype.txt} as
 * {@code TRANTYPE} at 60 bytes over 7 rows, {@code trnxfile.txt} as {@code TRNX} at 350 bytes over
 * 700 rows, and {@code xreffile.txt} as {@code XREF} at 50 bytes over 88 rows -- the same descriptor
 * {@code cardxref.txt} decodes against, that file being the same record under a different
 * data-definition name rather than a new record type.
 *
 * <p>Assumptions: the directory holds an ELEVENTH admitted resource that is not a fixture, its own
 * {@code README.md}. It is named here because {@code ReportingFixtureContractTest} asserts a CLOSED
 * resource set of eleven entries, so a reader reconciling that list against this roster meets it and
 * would otherwise read the difference as a discrepancy. A file arriving in that directory with no
 * entry in either place fails a case rather than passing unlisted.
 *
 * <p>Refactoring Rationale: this roster has now been short TWICE, in the same way and from the same
 * cause. It read FIVE while the contract test bound seven, omitting {@code carddata.txt} and
 * {@code cardxref.txt} -- which are the two files the security attestation below turns on, so a
 * census short by two is what allowed a statement about the whole directory to be written from a
 * subset of it and to be wrong. It then read SEVEN while the contract test bound ten, omitting
 * {@code tranfile.txt}, {@code trnxfile.txt} and {@code xreffile.txt} -- the two 350-byte
 * transaction corpora and the second cross-reference, which are precisely the fixtures the report
 * and statement reads need. Alternatives Considered: correcting the figure a third time and relying
 * on review to keep it. Rejected on the evidence of the two corrections: the figure lives in a
 * different file from the directory it counts, so the change that falsifies it never touches it.
 * {@code RepositoryCharterCensusTest} now re-counts this roster against the fixtures directory and
 * against {@code ReportingFixtureContractTest}'s own accepted-resource list, so a fixture added
 * without an entry here fails this module's build.
 *
 * <p>Assumptions: they are decoded through {@code com.carddemo.common.codec.FixedWidthCodec} against
 * a descriptor registered in {@code com.carddemo.common.codec.CopybookLayout} rather than
 * hand-assembled, and {@code com/carddemo/reporting/fixtures/ReportingFixtureContractTest.java} loads
 * each by name, asserts its length, row count and key domain, and re-encodes every row to prove byte
 * identity -- so a byte in that directory cannot change while the suite stays green. Assumptions:
 * each file is line-oriented with one record per line and the newline is a FILE convention that is
 * not part of any record; the declared length excludes it, so reading a fixture as one continuous
 * byte stream would mis-align every record after the first.
 *
 * <p>Assumptions: this directory DOES carry primary account numbers and a card-verification-value
 * COLUMN, and a reader deciding how to handle a fixture needs the positions and the actual bytes
 * rather than a reassurance. A card-number column appears in FIVE of the ten fixtures, at two
 * different offsets, and the offset is the part that is easy to get wrong: {@code carddata.txt} holds
 * {@code CARD-NUM} at offset 0 for 16 bytes, per {@code app/cpy/CVACT02Y.cpy} line 5;
 * {@code cardxref.txt} and {@code xreffile.txt} each hold {@code XREF-CARD-NUM} at offset 0 for 16,
 * per {@code app/cpy/CVACT03Y.cpy} line 5; {@code trnxfile.txt} holds {@code TRNX-CARD-NUM} at offset
 * 0 for 16, which is why the statement path can read one card's run as a key-ordered scan; and
 * {@code tranfile.txt} holds {@code TRAN-CARD-NUM} at offset 262 for 16, summed from the field widths
 * declared in {@code app/cpy/CVTRA05Y.cpy} and corroborated independently by
 * {@code app/jcl/TRANREPT.jcl} L41, which sorts that record on {@code TRAN-CARD-NUM,263,16,ZD} in
 * one-based positions. Measured across those five columns the directory holds 90 distinct card
 * numbers. The verification value, by contrast, appears in ONE file only: {@code carddata.txt} holds
 * {@code CARD-CVV-CD} at offset 27 for 3 bytes, per {@code CVACT02Y.cpy} line 7.
 *
 * <p>Refactoring Rationale: this paragraph asserted the opposite -- that "no fixture in this module
 * carries a primary account number, a card verification value or an enciphered column", on the
 * ground that "the card master and the cross-reference are not fixtures here". Both halves were
 * false once the roster above grew past five, and the second was contradicted by the roster in the
 * same charter. A false negative of this shape is the costliest documentation error available here,
 * because it is the sentence a reader consults BEFORE deciding a fixture needs no handling care,
 * and it told them there was nothing to care about. Only one clause of it survives and is kept
 * below: no enciphered column appears, which remains true because the target stores the
 * verification value as an encrypted {@code BYTEA} and these are fixed-width baseline records.
 *
 * <p>Assumptions: that column carries the literal {@code 000} on every one of the five rows, read
 * from the committed bytes at offset 27 length 3 of each row of {@code carddata.txt}. It is a
 * PLACEHOLDER occupying the three bytes the 150-byte record declares, not a value, so no row here
 * stands in any relation to a verification value at all and nothing here can authenticate anything.
 * The sibling {@code ReportingFixtureContractTest} holds that property mechanically rather than
 * leaving it to this prose: it reads the whole column and requires it to contain only that one
 * placeholder, so a distinct per-row value cannot be introduced while the suite stays green. The card
 * numbers alongside it are synthetic on their own evidence, measured over all 90 distinct values of
 * the five columns above: 87 open {@code 9900}, a national-assignment major-industry range no issuer
 * identifier occupies; one more is the orphan sentinel {@code 9999999999999999} that the report path
 * uses for a deliberately unresolvable card; every one of those 88 additionally fails the Luhn check
 * digit that every card network requires; and the remaining TWO are keys shared verbatim with
 * {@code app/data/ASCII/carddata.txt}, itself this project's committed synthetic seed rather than
 * production data. The sibling contract test asserts both halves per value, over the card columns it
 * derives from each admitted fixture's registered descriptor rather than from a file list, so the
 * property survives a fixture being added without this paragraph being reread.
 *
 * <p>Refactoring Rationale: this passage attested that "the five verification values are 901 through
 * 905, one per row in key order" and offered that ascending run as the structural evidence of
 * synthesis. The bytes say {@code 000} five times, so the attestation was false about the one column
 * in this directory that needs no ambiguity -- and false in the shape that costs most, since a
 * specific-sounding claim about a sensitive column is exactly the sentence a reader accepts without
 * opening the file. It also cited a table in the fixtures README as carrying "the same" values, which
 * made a second document appear to corroborate it. The claim is now stated from the fixture bytes and
 * from nothing else, and the synthesis evidence is re-grounded on the card-number prefixes and on the
 * published seed, both of which are equally checkable and are not contradicted by the data. An earlier
 * revision had asserted the reverse error -- that no fixture here carried a card number or
 * verification value at all -- and the revision that replaced it named THREE card-number columns and
 * described their synthesis from {@code carddata.txt}'s five rows alone, when five fixtures carry such
 * a column and the two that were missing hold 88 of the 90 distinct values between them. Every one of
 * those readings was written from a subset of the directory, which is the single failure mode this
 * paragraph keeps reproducing, so the positions and the counts above are now stated per column with
 * the offset each one sits at; the standing
 * lesson recorded for the next author is that a claim about a column is written by reading the column.
 *
 * <p>Assumptions: NO enciphered column appears in any fixture here, and no class in this directory
 * carries a fixture verification value into a relation. The two classes that load the card master
 * insert their own {@code PLACEHOLDER_CIPHERTEXT} into {@code card.cards.cvv_encrypted} rather than
 * the fixture's three bytes, because the target column holds ciphertext and no reporting relation
 * projects it at all; the only reader of the fixture column anywhere in this module is the contract
 * test's own placeholder assertion. The column is present only because {@code CVACT02Y.cpy} declares
 * it inside the 150-byte record and that test proves byte-identical round-tripping, which a record
 * with a hole in it cannot do. No reporting mapper may read it: the target returns the verification
 * value from no endpoint at all. A fixture card number must never reach a log, an assertion message
 * or a report fixture;
 * {@code com.carddemo.common.security.CardNumberMasker} is what a diagnostic uses instead. The card
 * number this context surfaces reaches it through the cross-reference and is rendered masked, which is
 * also the leading component of the ordering {@code StatementHeadingChunkIT} walks.
 *
 * <p>Trade-offs: the classes here reach their relations three different ways, one way per kind of
 * property, and which fixtures a class loads follows from that rather than from convenience. The
 * parse-only class establishes NO relation and loads nothing. The ordering class seeds stand-in tables
 * through the harness script and loads no fixture either, because a stand-in's rows are arranged to
 * exercise an ordering rather than to reproduce a record. The remaining six apply the shipped
 * definitions and load the fixtures their own property needs -- the deployed-relation class loads the
 * account, card, cross-reference and customer masters; the driving-cursor class loads those four plus
 * {@code xreffile.txt}; the customer and account lookups load their own master plus
 * {@code xreffile.txt} as the driving set; the statement transaction cursor loads
 * {@code trnxfile.txt} with the three masters its heading needs; and the report surface loads
 * {@code tranfile.txt} with {@code cardxref.txt}, {@code trantype.txt}, {@code trancatg.txt} and
 * {@code tcatbal.txt}. Every one of the ten fixtures therefore has at least one consumer in this
 * directory or in the mapper tests. A future class binding a new fixture registers it in the contract
 * test's own list, which grows by deliberate edit; a fixture with no consumer is indistinguishable
 * from a fixture nobody needs.
 *
 * <p>Assumptions: the statement-side and report-side record layouts ARE represented here, by
 * {@code trnxfile.txt} and {@code tranfile.txt} respectively. Refactoring Rationale: this section
 * read "the statement-side and report-side record layouts are consequently NOT represented by a
 * fixture here", which was a conclusion drawn from the seven-name roster and was false as soon as the
 * two 350-byte corpora were bound. A future class binding a new fixture registers it in the contract
 * test's own list and in the roster above, both of which grow by deliberate edit.
 *
 * <h2>Filename-case discipline</h2>
 *
 * <p>Assumptions: this baseline mixes cases on disk, so a citation in the wrong case is a dead
 * reference rather than a cosmetic slip -- it resolves for nobody and fails silently in any tooling
 * that follows it. Four of the files this directory's reasoning depends on are UPPERCASE including
 * their extension: {@code app/cbl/CBSTM03A.CBL}, {@code app/cbl/CBSTM03B.CBL},
 * {@code app/jcl/CREASTMT.JCL} and {@code app/cpy/COSTM01.CPY}. The remainder carry a lowercase
 * extension: {@code app/cbl/CBTRN03C.cbl}, {@code app/cbl/COCRDLIC.cbl}, {@code app/jcl/TRANREPT.jcl},
 * {@code app/jcl/PRTCATBL.jcl}, {@code app/cpy/CVTRA05Y.cpy}, {@code app/cpy/CVACT03Y.cpy},
 * {@code app/cpy/CVCUS01Y.cpy} and {@code app/cpy/CVACT01Y.cpy}.
 *
 * <p>Assumptions: TWO copybooks describe the same 500-byte customer record and both exist, so a
 * citation has to choose between them knowingly. {@code app/cpy/CUSTREC.cpy} and
 * {@code app/cpy/CVCUS01Y.cpy} each declare the customer record group at their L4 beneath a banner at
 * their L2 giving the record length as 500, and they differ only in field-name punctuation and in
 * whitespace, the former being tab-indented. This module cites {@code CUSTREC.cpy} for the fixture
 * and for the mapping layer, and the fixtures directory records the reason in its own README: the
 * reporting mappers cite that book throughout, so following them avoids introducing a second reading
 * of one record. Trade-offs: the cost is that a reader arriving from another module may expect the
 * other spelling, which is exactly why both are named here rather than one being left unmentioned.
 *
 * <h2>Terminology discipline</h2>
 *
 * <p>Assumptions: two directories in this repository are both called tests and they are not the same
 * thing, so this charter keeps them textually distinct. The repository-root {@code tests} directory
 * is the COBOL three-layer functional-parity oracle suite -- unit, single-program integration and
 * golden-master end-to-end -- and it is the behavioural oracle this migration is measured against.
 * {@code services/reporting-service/src/test} is this module's own Java suite. Neither stands in for
 * the other, and a sentence that says only "the tests" is ambiguous in a way that has consequences,
 * because the two have different owners and different rules.
 *
 * <p>Assumptions: the oracle suite's graded return-code rubric, in which a soft reject is reported
 * distinctly from a failure, belongs exclusively to {@code tests} and must not travel. Every gate on
 * this side -- the build, the documentation gate, the unit-test plugin, the integration-test plugin
 * and the assertion framework -- is BINARY: a class either passes or fails the build. A Java build
 * here is therefore never described as warn-level green, and no aggregate grade is computed from one.
 *
 * <p>Assumptions: this suite is strictly ADDITIVE. {@code app}, {@code tests}, {@code scripts} and
 * {@code samples} are reference-only: they are cited by path and line, never modified, never
 * re-pinned and never retro-documented. That is what allows the oracle suite to keep serving as the
 * parity oracle, since a suite edited to agree with the code it measures has stopped measuring
 * anything.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>This file exists because user-specified Rule 1 (Explainability) requires a docstring on every
 * module entry point, a Java package declaration is one, and a {@code package-info.java} Javadoc
 * block is the only construct able to carry it. The requirement is Rule 1's own and precedes the
 * build interlock: the migration requirements alone would not have asked for this file.
 *
 * <p>Assumptions: of the four content elements Rule 1 enumerates, only Purpose applies here. A
 * package declaration accepts no argument, yields no value and raises nothing, so the other three are
 * INAPPLICABLE rather than omitted, and no at-clause is invented to stand in for one -- an invented
 * tag would additionally be an empty-description violation under the completeness check this project
 * configures, so inventing one would fail the gate twice. Rule 1's inline-comment clause likewise
 * cannot bind a file containing no code, since there is nothing adjacent to annotate; the rationale
 * it asks for therefore lives inside this block, which is why the block is long rather than
 * decorative.
 *
 * <p>Assumptions: the four rationale labels above are written bare, plural, unparenthesised,
 * colon-terminated and free of emphasis markup, which is the single permitted written form fixed by
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} at its L232-L243 and echoed by the main-tree register at
 * its L251-L259. The emphasis around those labels in the rule text is that document's own typography
 * and is not part of a label. The hyphen in the fourth label is ASCII hyphen-minus. Each label was
 * RETYPED from the rule rather than copied from the reference suite, for the measured reason recorded
 * at register row R14: the label is found by a literal search before it is read by a person, one
 * spelling makes that search complete, and the reference suite's prose uses a non-breaking hyphen that
 * would silently defeat it.
 *
 * <p>Assumptions: two Checkstyle checks act on this file and neither is redundant. One requires the
 * file to be PRESENT in any directory holding Java sources; the other requires it to CARRY Javadoc.
 * Both are live here rather than nominally configured, because the documentation gate declared in
 * {@code services/pom.xml} sets {@code includeTestSourceDirectory} to true at its L994 -- confirmed
 * in the resolved effective model rather than inferred from the declaration, and corroborated
 * independently by {@code docs/CODE_DOCUMENTATION_STANDARD.md} at its L1167, which records the Java
 * gate as bound to the {@code validate} phase and covering test sources. That branch is what makes
 * this file mandatory rather than merely conventional: deleting it fails the build, and stripping this
 * block while leaving the declaration fails it differently. Assumptions: the suppression list at
 * {@code config/checkstyle/suppressions.xml} admits only generated sources and test RESOURCE fixtures,
 * and its filter is configured to fail closed, so {@code src/test/java} is not exempt; and no
 * comment-driven or annotation-driven suppression filter is configured at all, so compliant Javadoc is
 * the only way through.
 *
 * <p>Refactoring Rationale: this charter replaces a shorter one that described a two-class directory
 * correctly but stopped there, and the expansion is not decoration -- what it adds is the EVIDENCE
 * behind the shapes, so that the next author changes them from the source rather than from a
 * recollection of it. Five specific misreadings are foreclosed by name because each is plausible,
 * each was reached in practice while this file was written, and each would have caused a wrong edit:
 * that a trailing {@code IT} or {@code ITCase} is skipped by the integration-test plugin, when no
 * include is configured and the default selection accepts both; that a trailing {@code Tests} is run
 * by neither plugin, when Surefire's defaults collect it and it therefore runs in the wrong phase;
 * that the transport fetch size is what makes a read stream, when the two reads that matter carry
 * their own hints; that schema resolution
 * names the four source schemas, when it names one and a compiled guard refuses more; and that keyset
 * positioning descends from the keyed-read opcode, when that opcode addresses one row by a whole key
 * and the cursor comes from a different program entirely. Trade-offs: the accepted cost is length. It
 * is preferred to the alternative, because every claim here carries a path and a line a reader can
 * check in a source that will not change, whereas a short charter that omitted them would have to be
 * believed.
 *
 * <p>Assumptions: this test package deliberately carries the SAME fully-qualified name as the
 * production package documented in the main-tree charter, and that is safe rather than a collision.
 * The two live in separate source roots compiled to separate output directories, and the presence
 * check is evaluated per DIRECTORY, so this directory needs its own file whether or not the main-tree
 * one exists. The consequence is a division of labour worth stating: this block describes the
 * integration-test charter and CITES the main-tree register, while the production package's contract
 * and the register itself are documented there and are not restated here.
 */
package com.carddemo.reporting.repository;
