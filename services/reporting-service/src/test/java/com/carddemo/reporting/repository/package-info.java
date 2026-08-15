/**
 * Integration tests for the read-only data-access layer of the reporting bounded context.
 *
 * <h2>Purpose and charter</h2>
 *
 * <p>This directory holds the tests of this module that need a real database engine before they can
 * say anything at all, and there are two of them because there are exactly two such properties. It
 * is the test-tree counterpart of the production package of the same name, and the two are
 * deliberately not symmetrical: the production package declares six repository roles, while a role
 * only earns a class here if the property that governs it is invisible to every cheaper gate.
 *
 * <p>The first property is whether the declared queries PARSE. The roles beside them declare
 * queries -- derived from method names, and written out for the report join -- and a query naming a
 * property the metamodel does not carry, or joining two entities along a path that does not exist,
 * is a defect no compiler reports and no unit test reaches. It surfaces when the repository proxy is
 * created, which needs an entity manager factory, which needs a data source.
 * {@code ReportingQueryBootstrapIT} provokes that moment and deliberately establishes no relation,
 * because parsing resolves against the metamodel rather than against rows.
 *
 * <p>The second property is whether a keyset predicate reproduces the whole of its own ordering.
 * {@code StatementHeadingChunkIT} proves that for the statement heading walk, and unlike the first
 * it needs ROWS. {@code StatementCardXrefRepository.findHeadingChunk} pages a relation ordered by
 * the masked card rendering and then by the per-card fingerprint; a predicate comparing one
 * component of a two-component ordering parses perfectly, so the first class passes on it, and a
 * stubbed repository answers whatever a unit test arranges, so the service's own tests pass on it
 * too. The property is a relationship between a predicate and an ordering over real rows, so
 * nothing short of an engine can assert it. Refactoring Rationale: that class exists because
 * exactly that defect reached this repository with no gate here able to see it -- cards were skipped
 * and others repeated, and neither outcome raised anything -- which is why this charter states the
 * directory's purpose as two properties rather than one.
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
 *       {@code services/reporting-service/pom.xml} L439, so this charter does not become a second
 *       place to maintain that decision;</li>
 *   <li>there is no read replica. Reads go to the writer through the views;</li>
 *   <li>result windows are positioned by key, never by the ordinal of a row;</li>
 *   <li>no money value passes through a binary floating-point type or through a bare JSON number.</li>
 * </ul>
 *
 * <p>Assumptions: the package-wide labelled-decision register is authored ONCE, in the main-tree
 * charter at
 * {@code services/reporting-service/src/main/java/com/carddemo/reporting/repository/package-info.java},
 * whose register opens at its L241 and runs from R1 to R15. That file states at its L248-L249 that
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
 * {@code services/pom.xml} declares at its L1233-L1236 as a bare coordinate: no version, no
 * executions block and, most consequentially, NO include configuration. The Spring Boot parent's
 * plugin management already binds the {@code integration-test} and {@code verify} goals, so
 * declaring the coordinate alone is what turns those bindings on. Because no include is configured,
 * the plugin's own default selection applies, and that default matches a leading {@code IT} as well
 * as a trailing {@code IT} or {@code ITCase}. Both classes here end in {@code IT} and are therefore
 * selected. {@code services/pom.xml} records that this is deliberate at its L1224-L1226: the naming
 * this project uses already matches the default pattern, so an include configuration is neither
 * needed nor wanted.
 *
 * <p>Assumptions: the hazard is consequently the opposite of the one a reader might expect, and it
 * is worth stating precisely because getting it backwards would invite a pointless rename. Nothing
 * is lost by ending a name in {@code IT}. What IS lost is a name matching neither selection: a class
 * ending in {@code Test} is claimed instead by the plugin that runs this module's unit tests, during
 * the {@code test} phase, which for a container-backed class means it runs before packaging and its
 * result is not asserted by {@code verify}; and a name matching neither pattern -- anything that
 * merely reads as though it should, such as a trailing {@code Tests} -- is run by neither plugin.
 * That last case is the dangerous one, because it compiles, the build goes green, and it proves
 * nothing.
 *
 * <p>Execution is therefore proven by artifact rather than by assumption:
 * {@code services/reporting-service/target/failsafe-reports} must list a report for every class in
 * this directory. Assumptions: that path and its unit-test counterpart are the plugins' DEFAULTS and
 * are deliberately not relocated, as {@code services/pom.xml} records at its L1227-L1231, because
 * the continuous-integration workflow collects from exactly those two locations -- so moving either
 * would make the build green while the workflow published nothing.
 *
 * <h2>The container mechanism, and the document that is its authority</h2>
 *
 * <p>Each class here declares a {@code static} {@code PostgreSQLContainer} annotated
 * {@code @Container} and {@code @ServiceConnection}, and activates the {@code test} profile. The
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
 *       posture provable at all -- generated statements would fabricate exactly the seven mapped
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
 * {@code services/pom.xml} L373, and this module declares only artifact coordinates. Those
 * coordinates are the 2.x PREFIXED spellings {@code testcontainers-postgresql} and
 * {@code testcontainers-junit-jupiter}, not the 1.x short names, which
 * {@code services/reporting-service/pom.xml} records at its L346-L349 as being absent from that BOM
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
 * <p>That leaves one genuine exception, and it is drawn narrowly on purpose rather than waved
 * through. {@code StatementHeadingChunkIT} needs ROWS in an ordered relation, so it applies
 * {@code db/testharness/test-harness-reporting-relations.sql} to its own container as a
 * classpath-relative initialisation script. Assumptions: that script creates its three stand-ins as
 * PLAIN TABLES and never as views, and it creates nothing else -- a measurement of the whole test
 * tree finds a single schema creation and three table creations and NOTHING ELSE: no view creation,
 * no privilege grant or withdrawal, no alteration and no discard, anywhere. Alternatives Considered:
 * reusing the two authoritative files above so the harness would exercise the real views. Rejected,
 * because copies on this classpath drift from their authorities and a passing assertion would then
 * assert agreement with a stale copy rather than with the real schema. Trade-offs: the accepted cost
 * is stated plainly in that class -- a stand-in cannot detect a mismatch between an entity mapping
 * and a view, so this directory proves the predicate reproduces the ordering and does NOT prove the
 * view's shape. Plain tables are chosen over views for the same reason the exception is narrow: a
 * table cannot be mistaken for a second definition of a masking view.
 *
 * <p>The boundary is therefore crisp, and it is a boundary on VERBS rather than on files. Inserting
 * rows is permitted, because fixtures have to be loaded before anything can be read. Creating,
 * altering, privileging or discarding anything the running service reads is not, and creating a view
 * is not, anywhere in this tree.
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
 * <p>The six roles the production package declares therefore take four distinct shapes, and the two
 * classes here reach them accordingly. {@code StatementTransactionRepository} and
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
 * <p>The projections those roles return live in the sibling {@code domain} package and number seven:
 * {@code StatementTransactionView}, {@code CardXrefView}, {@code CustomerView}, {@code AccountView},
 * {@code ReportTransactionView}, {@code TransactionTypeView} and {@code TransactionCategoryView}.
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
 * plus 289, which is 300. Two of the four are corroborated independently by measurement of the
 * fixtures in this module, whose account rows are 300 bytes and whose customer rows are 500.
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
 * module currently binds five by exact name, with the record length and row count each asserts:
 * {@code acctfile.txt} at 300 bytes over 4 rows, {@code custfile.txt} at 500 bytes over 4 rows,
 * {@code tcatbal.txt} at 50 bytes over 8 rows, {@code trancatg.txt} at 60 bytes over 9 rows, and
 * {@code trantype.txt} at 60 bytes over 7 rows.
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
 * <p>Assumptions: no fixture in this module carries a primary account number, a card verification
 * value or an enciphered column, because none of the five record types it binds declares one -- the
 * two records that would, the card master and the cross-reference, are not fixtures here. The
 * synthetic-data attestation in that directory's own README states this alongside the structural
 * evidence for it. That is recorded so nobody adds such a fixture on the assumption the directory was
 * already cleared for it.
 * The card number this context surfaces reaches it through the cross-reference and is rendered
 * masked, which is also the leading component of the ordering {@code StatementHeadingChunkIT} walks.
 *
 * <p>Trade-offs: the statement-side and report-side record layouts are consequently NOT represented
 * by a fixture here, and the omission is a consequence of the container strategy rather than an
 * oversight. The one class needing rows seeds them through the harness script described above, and
 * the class that does not need rows deliberately establishes no relation. A future class binding a
 * new fixture registers it in the contract test's own list, which grows by deliberate edit; a fixture
 * with no consumer is indistinguishable from a fixture nobody needs.
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
 * {@code services/pom.xml} sets {@code includeTestSourceDirectory} to true at its L1005 -- confirmed
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
 * <p>Refactoring Rationale: this charter replaces a shorter one that described the two classes
 * correctly but stopped there, and the expansion is not decoration -- what it adds is the EVIDENCE
 * behind the shapes, so that the next author changes them from the source rather than from a
 * recollection of it. Four specific misreadings are foreclosed by name because each is plausible,
 * each was reached in practice while this file was written, and each would have caused a wrong edit:
 * that a trailing {@code IT} or {@code ITCase} is skipped by the integration-test plugin, when no
 * include is configured and the default selection accepts both; that the transport fetch size is what
 * makes a read stream, when the two reads that matter carry their own hints; that schema resolution
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
