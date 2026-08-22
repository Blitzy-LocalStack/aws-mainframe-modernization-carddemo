/**
 * Container-backed integration tests that hold this context's persistence layer against a real
 * PostgreSQL engine rather than against a substitute for one.
 *
 * <h2>Purpose</h2>
 *
 * <p>The sibling {@code com.carddemo.account.api}, {@code com.carddemo.account.service} and
 * {@code com.carddemo.account.mapper} test packages substitute the store, which is the right choice for
 * what each of them asserts: a status, a property name, the order validation runs in, which query a
 * scan chooses and which key it seals. None of them can establish the properties this package exists
 * for, because in each of those properties the engine itself is the subject.</p>
 *
 * <ul>
 *   <li>That {@code db/migration/V1__account.sql} applies as written, producing the three tables this
 *       context owns under the ownership a provisioned environment gives them, and that every entity
 *       mapping validates against what it produced.</li>
 *   <li>That the declared widths, nullability and check constraints are real rather than nominal. A
 *       {@code CHAR(10)} authored as a {@code VARCHAR} pads nothing, and a check constraint that can
 *       never be false is indistinguishable from one that was mistyped.</li>
 *   <li>That the keyed window queries the customer scan is assembled from order ascending, resume
 *       STRICTLY after a position, and concatenate into a walk visiting every row exactly once.</li>
 *   <li>That {@code db/migration/V2__account_inquiry_reply_ledger.sql} applies as written and that its
 *       claim statement reports a conflict on a second delivery of one request rather than inserting a
 *       second row or overwriting the first. That decision belongs to the engine --
 *       {@code INSERT ... ON CONFLICT DO NOTHING} reporting no affected row -- so a substituted ledger
 *       would report whatever it was told to and would pass against a statement carrying a typo.</li>
 *   <li>That the account update's two writes, the customer row flushed first and the account row
 *       second, commit together and roll back together. Only something able to commit and to roll back
 *       can show it.</li>
 *   <li>That the by-account read of the cross-reference resolves through the secondary index the
 *       migration creates rather than by reading that table end to end. Which access path a cost-based
 *       planner chooses is a decision only a planner makes, so a substituted store would answer the
 *       query correctly while saying nothing at all about the path.</li>
 *   <li>That {@code db/migration/V3__batch_account_write_grant.sql} leaves the nightly batch role
 *       holding {@code UPDATE} on {@code account.accounts} and no other write anywhere in this schema.
 *       An access-control list is the engine's own state, established by one script running after
 *       another, so nothing short of the engine can answer for it -- and the defect that migration
 *       answers was exactly a grant that read as correct in the file that declared it and never reached
 *       a provisioned database.</li>
 * </ul>
 *
 * <h2>The closed inventory</h2>
 *
 * <p>This directory holds nine files and a tenth is prohibited: this descriptor, together with
 * {@code AccountRepositoryIT}, {@code AccountScreenProjectionIT}, {@code AccountUpdateAtomicityIT},
 * {@code BatchAccountWriteGrantIT}, {@code CardXrefRepositoryIT}, {@code CustomerMasterRepositoryIT},
 * {@code CustomerRepositoryIT} and
 * {@code InquiryReplyLedgerIT}. The three entities those eight
 * reach are the three this context owns, and each derives from one copybook record -- {@code Account}
 * from {@code ACCOUNT-RECORD} at {@code app/cpy/CVACT01Y.cpy} L4, {@code Customer} from
 * {@code CUSTOMER-RECORD} at {@code app/cpy/CVCUS01Y.cpy} L4 and {@code CardXref} from
 * {@code CARD-XREF-RECORD} at {@code app/cpy/CVACT03Y.cpy} L4 -- alongside the reply ledger that the
 * inquiry exchange this context absorbs writes through.</p>
 *
 * <pre>
 * this directory: 9 java files = 8 tests + 1 charter
 * </pre>
 *
 * <p>Refactoring Rationale: that marker line is new, and it is what turns the sentence above from a
 * claim into a checked one. {@code PackageCharterInventoryTest} in the shared kernel parses the line and
 * re-counts this directory, so an inventory that falls behind now fails the build instead of ageing
 * quietly -- which is the precise failure the paragraph below this one describes having already happened
 * once. Deleting the line to silence a failure is a visible act, because that test also holds the number
 * of charters carrying it to a floor.</p>
 *
 * <p>Assumptions: two members reach the cross-reference and they divide it rather than overlap.
 * {@code AccountScreenProjectionIT} owns the joined composition -- its outer-join arms, its ordering and
 * its bound -- while {@code CardXrefRepositoryIT} owns that table's own contract: the column set, the
 * absence of a version column, the keyed read on its textual key, and the by-account access path
 * together with the plan the engine chooses for it. The division is recorded because both classes name
 * the same table and a reader could otherwise take one for a duplicate of the other.</p>
 *
 * <p>Alternatives Considered: an abstract base class holding the container, the property registration
 * and the schema prerequisite once for all eight. Rejected because it would be a type whose only purpose
 * is to be extended, and it would place behind inheritance the two things a reader of any one class most
 * needs to see at that class: which properties it publishes, and what it creates before the context is
 * built. Trade-offs: the same prerequisite is therefore expressed seven times, which is a real cost every
 * time it changes -- {@code BatchAccountWriteGrantIT} being the one member that does not express it,
 * because it applies the shipped bootstrap instead, as ruling four records. It is accepted on the same
 * ground recorded beside {@code spring.flyway} in
 * {@code services/account-service/src/test/resources/application-test.yml}, whose own note reaches the
 * identical conclusion about the identical duplication.</p>
 *
 * <p>Refactoring Rationale: this descriptor previously named a smaller inventory, and it is restated
 * rather than left to age because a closed inventory that has fallen behind the directory is worse than
 * no inventory at all: a reader who trusts it concludes a class has gone missing, or adds a class beside
 * a sentence saying there are fewer. This file is the only place the inventory can be recorded at all,
 * for the reason set out in the closing section -- {@code config/checkstyle/checkstyle.xml} makes a
 * descriptor mandatory here and Java admits only one per package -- so a later class arriving in this
 * directory belongs in this list on the same change that introduces it. {@code CustomerRepositoryIT}
 * arrived under that clause and is listed above on the change that introduced it: it takes the
 * copybook-contract seat, holding the customer record layout, the fixture bytes, the keyed read, the
 * version column and the composition of the three window queries into one envelope, where
 * {@code CustomerMasterRepositoryIT} holds the migration history, the catalog geometry and those queries
 * individually. Ruling nine's sentence about fixtures is scoped to the members that predate it: that
 * class reads none, and this one resolves one by the classpath name recorded on itself, which is why the
 * name is part of its contract and is stated there.</p>
 *
 * <p>Refactoring Rationale: {@code BatchAccountWriteGrantIT} arrives under the same clause and takes the
 * privilege-graph seat, which no member here previously held. Its subject is a grant this schema issues
 * to ANOTHER context's role -- the one exception to the standing arrangement that a per-service
 * migration issues no {@code GRANT}, argued in section 4 of
 * {@code data-migration/sql/V0__schemas_and_roles.sql} and at the head of
 * {@code db/migration/V3__batch_account_write_grant.sql} -- so it is the one class here whose assertions
 * concern a role this context does not connect as. It reads no fixture, and ruling nine's sentence about
 * seeding through the subject reads the other way round for it: it seeds through the container's
 * superuser BECAUSE one of its cases requires that the same insert be refused to the batch role.</p>
 *
 * <h2>Ruling one: the name is the selector</h2>
 *
 * <p>Assumptions: every class here ends in {@code IT}, and that suffix alone is what makes it run. The
 * reactor divides its two test phases by class name and by nothing else. Surefire's default include
 * pattern is {@code **}{@code /*Test.java} and Failsafe's is {@code **}{@code /*IT.java}, and
 * {@code services/pom.xml} declares {@code maven-failsafe-plugin} with neither a version nor an
 * executions block, so those defaults are exactly what is in force. A container-starting class named to
 * end in {@code Test} would be run by Surefire in a phase that starts no container; a class named
 * neither way would match no pattern, would not run, and would not fail either, so the report would come
 * back complete and green with that class simply missing from it.</p>
 *
 * <p>Assumptions: the report directory {@code target/failsafe-reports} is not relocatable, because
 * {@code .github/workflows/services-ci.yml} L751 collects from exactly
 * {@code services/*}{@code /target/failsafe-reports/}. Local verification is
 * {@code mvn -f services/pom.xml clean verify} and not {@code test}: Failsafe runs these classes in
 * {@code integration-test} but asserts their results in {@code verify}, so a run stopping at
 * {@code test} executes none of them, and a run stopping at {@code integration-test} executes them
 * without failing on their outcome.</p>
 *
 * <h2>Ruling two: the profile is named on the class</h2>
 *
 * <p>Assumptions: each of the eight carries {@code @ActiveProfiles("test")}, which is the whole of how
 * {@code services/account-service/src/test/resources/application-test.yml} comes into force. No build
 * plugin activates that profile on any class's behalf, so a class omitting the annotation would resolve
 * the base profile alone -- reaching for the two remote configuration sources named in ruling three, and
 * requiring a transport mode the container does not serve.</p>
 *
 * <h2>Ruling three: this package owns the connection</h2>
 *
 * <p>Assumptions: no connection literal appears anywhere in this package, and none may be introduced. A
 * container assigns its host port as it starts, so a literal authored ahead of the run either addresses
 * nothing or -- the worse outcome, because it passes -- addresses whatever database happened to be
 * listening. Every one of the eight classes publishes the container's generated URL, user name and
 * credential through {@code @DynamicPropertySource}, and publishes {@code spring.flyway.user} and
 * {@code spring.flyway.password} beside them.</p>
 *
 * <p>Assumptions: {@code BatchAccountWriteGrantIT} publishes a different value for that migration pair
 * and the same values for everything else, which is a deliberate divergence rather than an omission. It
 * names {@code carddemo_account_migrator} and a credential it issued to that role, because what it
 * asserts is a grant, and a grant issued by a superuser would have succeeded whether or not the deployed
 * identity could have issued it. Every other class here migrates as the container's generated user
 * because none of them asserts anything about who issued a statement.</p>
 *
 * <p>Assumptions: that registration mechanism is {@code @DynamicPropertySource} and not
 * {@code @ServiceConnection}. The distinction is worth stating because the second is the more usual
 * modern idiom, so its absence reads as an oversight: {@code services/account-service/pom.xml} declares
 * {@code testcontainers-junit-jupiter}, {@code testcontainers-postgresql} and
 * {@code testcontainers-localstack} from the Testcontainers bill of materials and declares no
 * {@code spring-boot-testcontainers}, so no service-connection annotation and no connection-details bean
 * is on this module's test classpath to be used. Publishing the migration credential pair explicitly is
 * a consequence of the same absence, there being no bean to supply it.</p>
 *
 * <p>Assumptions: {@code services/account-service/src/test/resources/application-test.yml} holds no
 * datasource URL, no user name, no credential, no queue location, no issuer and no key material, and its
 * register of deliberate omissions says so at the foot of the file. It also switches off the two remote
 * configuration sources the base profile imports,
 * because marking an import optional decides what happens when a value is ABSENT and does not decide
 * whether the lookup is attempted; left enabled, the resolver builds a client and calls out while the
 * environment is still being assembled, and does so differently on a host carrying an instance profile
 * than on one that does not. No test here reaches a cloud control plane.</p>
 *
 * <p>Assumptions: the profile restates both schema selectors,
 * {@code spring.jpa.properties.hibernate.default_schema} and {@code spring.flyway.default-schema}, even
 * though the base profile already sets each. The restatement is load-bearing rather than redundant,
 * because the base profile also carries its schema selection as a connection statement and a URL the
 * container generates cannot carry a schema parameter authored before that container existed -- so a
 * selector reaching a run only through the URL would not reach it at all. Trade-offs: two documents now
 * name the schema, and a deliberate change in one needs a matching change in the other. Being made to
 * write that second edit is the point, because the failure these selectors prevent is a PASSING test: an
 * unqualified mapping resolving to a same-named table outside this context validates against that table
 * and reports success, where a missing relation fails loudly on the first run.</p>
 *
 * <h2>Ruling four: the schema exists before Flyway opens a connection</h2>
 *
 * <p>Assumptions: seven of the eight classes create the owning role and the schema themselves, in a
 * {@code @BeforeAll} that runs after the container has started and before the application context is
 * refreshed. Each creates a {@code NOLOGIN} role, grants the container's generated user the right to
 * assume it, and then creates schema {@code account} owned by that role. This is the single most likely
 * wiring failure in this package, and it earns the emphasis: the failure arrives at RUN TIME as a
 * context that will not start, with nothing whatsoever from the compiler beforehand.</p>
 *
 * <p>Assumptions: {@code BatchAccountWriteGrantIT} is the eighth and satisfies this ruling differently,
 * by applying {@code data-migration/sql/V0__schemas_and_roles.sql} unchanged through the engine's own
 * client in the same {@code @BeforeAll} position. The distinction is the subject: the other seven need a
 * schema and nothing more, so three statements are the honest shape, whereas that class asserts the
 * privilege graph the shipped bootstrap establishes -- and three harness-authored statements would be a
 * second definition of the very thing under test, passing whatever they were written to say.
 * Trade-offs: applying that file costs seconds per run and requires two local acknowledgements it
 * records on itself, against being the only arrangement in which the grant, the role that issues it and
 * the role that receives it all come from the shipped documents.</p>
 *
 * <p>Assumptions: the prerequisite exists because no other participant supplies it.
 * {@code db/migration/V1__account.sql} declares no {@code CREATE SCHEMA}, no {@code CREATE ROLE} and no
 * {@code GRANT}; {@code data-migration/sql/V0__schemas_and_roles.sql} is the exclusive authority for
 * schemas, owner roles, runtime roles and grants across this system; and a throwaway container has never
 * run that bootstrap. The base profile forbids Flyway from creating a schema and the test profile
 * inherits that setting unchanged, so nothing in either document will produce one.</p>
 *
 * <p>Refactoring Rationale: this descriptor previously attributed the prerequisite to the test profile
 * -- to a schema-creation setting turned on there, and to role statements running from that profile's
 * own initialisation hook. Both sentences are withdrawn rather than adjusted, because each named a
 * mechanism {@code services/account-service/src/test/resources/application-test.yml} does not use, and
 * that document records under {@code spring.flyway} why it does not. Letting Flyway create the
 * schema makes it appear owned by whichever user the container generated, which leaves every
 * default-privilege grant keyed on the owning role inert and fails the ownership assertion
 * {@code CustomerMasterRepositoryIT} makes by name. Creating the role from the profile's initialisation
 * hook makes that assertion pass but turns the profile into a second, unversioned definition of the
 * cluster's role graph, free to drift from the document holding that authority and recorded in no
 * migration history. Placing the step in this tree keeps one authority for the deployed graph and one
 * harness step for the container.</p>
 *
 * <h2>Ruling five: results are reached by key and in key order</h2>
 *
 * <p>Alternatives Considered: reaching a window of rows by counted distance, asking the engine to pass
 * over a tally of rows and return what follows. Rejected on observable behaviour rather than on taste.
 * Under concurrent inserts a query of that shape omits rows and repeats rows, because the tally is
 * evaluated against whatever the table holds at the moment the second query runs, whereas a read
 * resuming from the last key it actually returned can do neither -- the key names a row rather than a
 * distance. The reference never counted rows either: {@code app/cbl/COCRDLIC.cbl} declares
 * {@code 01 WS-THIS-PROGCOMMAREA.} at L229 and carries across the terminal turn a trailing key pair at
 * L230 through L232 and a leading key pair at L233 through L235, never an ordinal into a result set.</p>
 *
 * <p>Assumptions: every windowed result crossing this layer is carried by
 * {@code com.carddemo.common.web.PageResponse}, a record with one type parameter and four components in
 * this order: {@code items}, {@code firstKey}, {@code lastKey} and {@code hasNext}. The two keys are
 * opaque cursor tokens. {@code lastKey} is the key of the LAST RETURNED item and never that of the one
 * further row read to settle {@code hasNext}; {@code hasNext} is derived from that one further row and
 * never from a tally of rows; and both keys may be absent when nothing was returned. Reading forward asks
 * for keys strictly greater than {@code lastKey} in ascending order, one further than the window needs;
 * reading backward asks for keys strictly less than {@code firstKey} in descending order, which is what
 * makes the backward direction the exact counterpart of the reference read-previous rather than a re-read
 * from the start. The envelope is assembled above this layer: a member here yields entities and ordered
 * collections of entities, and the service layer seals the boundary keys.</p>
 *
 * <h2>Ruling six: versioning is optimistic, and selective</h2>
 *
 * <p>Assumptions: {@code Account} and {@code Customer} each carry a JPA version column and
 * {@code CardXref} deliberately carries none, {@code db/migration/V1__account.sql} recording that
 * omission at the table it omits it from. The cross-reference has no update path, and a version column
 * exists to detect a competing update.</p>
 *
 * <p>Alternatives Considered: an explicit lock -- a locking hint, a select-for-update, or a pessimistic
 * mode on a repository method. Rejected outright, and the reason is load-bearing rather than stylistic.
 * The reference read-for-update lock was never held across the terminal turn, which is precisely why
 * {@code app/cbl/COACTUPC.cbl} has to keep a manual snapshot of the record it is about to write:
 * {@code 05 ACUP-OLD-DETAILS.} at L669, ending immediately before {@code 05 ACUP-NEW-DETAILS.} at L757
 * and so spanning L669 through L756. Introducing a lock here would hold a row across a request boundary
 * the reference never held one across, which claims more than the source does and gives one slow caller a
 * way to stall another. Assumptions: the conflict's outward form is settled elsewhere --
 * {@code com.carddemo.common.error.GlobalExceptionHandler} answers the optimistic failure with HTTP 409
 * -- so no member of this package restates that mapping or asserts a status.</p>
 *
 * <h2>Ruling seven: no table name is qualified here</h2>
 *
 * <p>Assumptions: no member of this package qualifies a table name with its schema.
 * {@code com.carddemo.account.config.DataSourceConfig} holds the schema name as a single named constant
 * and pins the session search path to it on every pooled connection, so it is the one owner of that
 * question. Restating the schema in a query here would give one setting two definitions free to drift
 * apart, and the drift would surface as a relation-not-found failure while the context starts rather than
 * as a compilation error.</p>
 *
 * <h2>Ruling eight: the engine's isolation is the stronger of the two</h2>
 *
 * <p>Assumptions: no test in this package may assert dirty-read behaviour, and the reason is that the
 * reference is the looser of the two rather than the stricter. Every one of the eight file resources in
 * {@code app/csd/CARDDEMO.CSD} is defined to read without regard to uncommitted change: among the eight,
 * the {@code READINTEG(UNCOMMITTED)} operand appears at L3 for {@code ACCTDAT}, at L40 for
 * {@code CCXREF}, at L53 for {@code CUSTDAT} and at L66 for {@code CXACAIX}, alongside
 * {@code JNLSYNCWRITE(YES) RECOVERY(NONE) FWDRECOVLOG(NO)} at L46 and L72. The default isolation these
 * tests run under is strictly stronger, so a read here may decline to see something the reference would
 * have shown, and never the reverse. Trade-offs: the compromise is the direction nobody minds, and an
 * assertion written the other way round would be asserting a weakness the target does not have.</p>
 *
 * <h2>Ruling nine: the rows a case needs are written by that case</h2>
 *
 * <p>Assumptions: every class here seeds its own rows with plain statements against the container, and
 * none seeds through the repository under test. That was rejected because it lets a defect in that
 * repository conceal itself: a projection returning nothing because the seed never persisted is
 * indistinguishable from a projection whose query is wrong.</p>
 *
 * <p>Refactoring Rationale: this ruling previously said that no member here reads a fixture from the
 * classpath and that no fixture filename is part of any contract here. Both halves are withdrawn rather
 * than adjusted, because the directory has moved past them and a stale ruling is worse than none. Four
 * flat records now exist under this module's test resources: two account records of 300 bytes each,
 * which is the length {@code app/cpy/CVACT01Y.cpy} declares at its L2, one customer record of 500 bytes
 * per {@code app/cpy/CVCUS01Y.cpy} L2, and one cross-reference record of 50 bytes per
 * {@code app/cpy/CVACT03Y.cpy} L2.</p>
 *
 * <p>Assumptions: those four records are decode vectors and never a seed, and the distinction is what
 * keeps the previous paragraph intact -- reading a record is not the same as seeding through the
 * subject: the rows still reach the container as plain statements, and the record supplies their values.
 * Three members read them, and each declares the names it resolves as constants on itself, so those
 * names ARE part of that class's contract. {@code AccountRepositoryIT} resolves both account records,
 * the negative-balance one being the only negative money vector in this module.
 * {@code CardXrefRepositoryIT} resolves the cross-reference record together with the account and
 * customer records, for one reason: the account and customer identifiers those two carry are the
 * identifiers the cross-reference record points at, so the triple is pinned across three separate files
 * and a change to any one of them breaks the join deliberately and visibly. {@code CustomerRepositoryIT}
 * resolves the customer record. No other member of this package reads any of them. Each record is
 * decoded through the layout registered for it in {@code com.carddemo.common.codec.CopybookLayout}
 * rather than through offsets written into a test, which is how the house rule recorded at L540 through
 * L542 of {@code tests/README.md} -- that a layout stays single-sourced and is never duplicated -- is
 * honoured on this side of the migration.</p>
 *
 * <p>Assumptions: every date, identifier and stored byte a case writes is a literal, and no case reads a
 * wall clock. A value compared against the current instant passes for a reason unrelated to the code
 * under test and fails only when two reads straddle a boundary; the COBOL suite reaches the same
 * conclusion at L488 and L489 of {@code tests/README.md}, where it records injecting its business date as
 * a parameter rather than reading the wall clock so that reruns produce identical output. No key material
 * of any
 * kind appears in this package. What a case may assert about the two protected columns is that the bytes
 * they hold survive a round trip unaltered, which is a property a {@code BYTEA} column has and a
 * character column does not.</p>
 *
 * <h2>The conversational state that no longer exists</h2>
 *
 * <p>Assumptions: nothing in this layer holds state between requests, so a case may build a context and
 * discard it freely. What the reference carried between terminal turns was one structure,
 * {@code 01 CARDDEMO-COMMAREA.} at {@code app/cpy/COCOM01Y.cpy} L19 and running to L44, whose declared
 * field widths sum to 160 bytes. It does not travel in the target at all: identity arrives as claims on a
 * validated token, the administrator and user values {@code 'A'} and {@code 'U'} declared at L27 and L28
 * of that copybook becoming the {@code carddemo-admin} and {@code carddemo-user} groups, and selection
 * context arrives on the request path. The reference asserts its own statelessness independently --
 * {@code app/csd/CARDDEMO.CSD} gives {@code TWASIZE(0)} to {@code COACTUPC} at L308 and to
 * {@code COACTVWC} at L318 -- so no work area survived a task there either.</p>
 *
 * <h2>The specification these tests are written from</h2>
 *
 * <p>Assumptions: the behaviour this package checks is specified by the reference material below, cited
 * here so that a member class can point at this list instead of repeating it. Every path under
 * {@code app/} is read as the specification, is never modified, and keeps running exactly as it does
 * today.</p>
 *
 * <ul>
 *   <li>{@code app/cpy/CVACT01Y.cpy}, 20 lines, its L2 header declaring a record length of 300 bytes.
 *       {@code 01 ACCOUNT-RECORD.} at L4 carries twelve named fields at L5 through L16 and a
 *       {@code FILLER PIC X(178)} at L17.</li>
 *   <li>{@code app/cpy/CVCUS01Y.cpy}, 26 lines, declaring 500 bytes at L2.
 *       {@code 01 CUSTOMER-RECORD.} at L4 carries eighteen named fields at L5 through L22 and a
 *       {@code FILLER PIC X(168)} at L23.</li>
 *   <li>{@code app/cpy/CVACT03Y.cpy}, 11 lines, declaring 50 bytes at L2.
 *       {@code 01 CARD-XREF-RECORD.} at L4 carries {@code XREF-CARD-NUM PIC X(16)} at L5,
 *       {@code XREF-CUST-ID PIC 9(09)} at L6, {@code XREF-ACCT-ID PIC 9(11)} at L7 and a
 *       {@code FILLER PIC X(14)} at L8.</li>
 *   <li>{@code app/cbl/COACTVWC.cbl}, 941 lines, holding the three keyed reads as three paragraphs of one
 *       chain: {@code 9200-GETCARDXREF-BYACCT.} at L723, whose comment at L725 names the access as being
 *       by way of an alternate index on the account identifier, then
 *       {@code 9300-GETACCTDATA-BYACCT.} at L774 and {@code 9400-GETCUSTDATA-BYCUST.} at L825.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl}, 4236 lines, holding the manual snapshot cited in ruling six.</li>
 *   <li>{@code app/cbl/CBACT01C.cbl} at 430 lines, {@code app/cbl/CBACT03C.cbl} at 178 and
 *       {@code app/cbl/CBCUS01C.cbl} at 178, each an open, get-next, close triad over one of the three
 *       records -- the sequential reads that a keyed ordered scan replaces.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD}, 505 lines, declaring exactly eight file resources at L1, L13, L25,
 *       L37, L50, L63, L76 and L88. Four of them belong to this context: {@code ACCTDAT} at L1,
 *       {@code CCXREF} at L37, {@code CUSTDAT} at L50 and {@code CXACAIX} at L63.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl} and {@code app/cbl/CBACT04C.cbl}, cited by
 *       {@code BatchAccountWriteGrantIT} alone and belonging to another context's chain. The first
 *       reaches its three writes from {@code 2000-POST-TRANSACTION.} at L424 and rewrites the account
 *       master at L554; the second rewrites the same master on each account control break at L356.
 *       Those two statements are the whole reason a role this context does not connect as holds a write
 *       privilege on one of its tables.</li>
 * </ul>
 *
 * <p>Assumptions: two resource names in that last file are routinely confused, and neither confusion is
 * harmless. The base cross-reference cluster's resource name is {@code CCXREF} at L37 and NOT
 * {@code CARDXREF}; the string {@code CARDXREF} occurs in that file only inside the data set names at L39
 * and L65. Separately, {@code CARDAIX} at L13 is the alternate index over the CARD master and belongs to
 * the card context, whereas {@code CXACAIX} at L63 is the alternate index over the cross-reference by
 * account key, as L64 states in words. A test reaching for one while meaning the other would exercise
 * another context's access path.</p>
 *
 * <h2>The absence of a parity oracle</h2>
 *
 * <p>Assumptions: no executable parity oracle exists for any path this context migrates, and this is
 * stated plainly because a reader may reasonably assume one does. Two independent statements in
 * {@code tests/README.md} establish it. Its L83 through L85 record that the online programs cannot be run
 * end to end without a CICS runtime, which the runner does not have, so only their extractable
 * field-validation logic is unit-tested there. And the business rules that suite asserts verbatim, from
 * its L553 onward, name the posting, interest and category-balance programs of other contexts and name
 * none of this context's programs or files at all. Every case in this package is therefore authored
 * directly from the copybook contracts and program paragraphs cited above, and no golden-master
 * comparison is claimed for any of them.</p>
 *
 * <p>Assumptions: nothing here displaces that suite. The COBOL three-layer suite under {@code tests/} is
 * reference material with its own runners, and these Java tests are strictly additive to it; the two trees
 * are separate things. That suite's graded condition-code convention, under which a soft code still reads
 * as success, belongs to it alone -- the gate over this package is pass or fail.</p>
 *
 * <h2>Why this descriptor exists, and why it carries no tag section</h2>
 *
 * <p>Assumptions: this file exists for two reasons that happen to coincide, and it omits three tag
 * sections for a third. The project Explainability rule requires a docstring on every module entry point
 * at its L15 and hardens that into a conjunctive review gate at its L43; in Java a package declaration is
 * that entry point, and {@code package-info.java} is the only compilation unit able to carry a
 * package-level docstring. Mechanically and separately,
 * {@code config/checkstyle/checkstyle.xml} declares {@code JavadocPackage} at Checker level, outside the
 * tree walker, where it demands the FILE, and declares {@code MissingJavadocPackage} inside the tree
 * walker, where it demands that the file CARRY Javadoc. That gate reaches this tree because the
 * Checkstyle execution in {@code services/pom.xml} sets its test-source-directory flag, and it runs at
 * {@code validate}, ahead of compilation. An empty descriptor, or one holding only a plain block comment,
 * satisfies the first check and fails the second.</p>
 *
 * <p>Assumptions: no {@code package-info.java} exists at {@code src/test/java}, at {@code com}, at
 * {@code com/carddemo} or at {@code com/carddemo/account} in this module, and those four absences are
 * deliberate rather than overlooked. {@code JavadocPackage} is a file-set check that fires only for a
 * directory CONTAINING a processed source file, and each of those four holds subdirectories and no file at
 * all, so neither check has anything there to fire on. Adding a descriptor at any of the four would be
 * four files documenting nothing, each then carrying its own maintenance. The seven leaf packages of this
 * module's test tree each carry exactly one, which is the whole of the obligation.</p>
 *
 * <p>Trade-offs: the four category labels in this file are written in the plural and without parentheses
 * -- {@code Alternatives Considered:}, {@code Refactoring Rationale:}, {@code Assumptions:} and
 * {@code Trade-offs:} -- taking their spelling from the rule that defines them, even though the two
 * Checkstyle configuration files and section 11 of {@code tests/README.md} use singular forms and the
 * parenthesised singular is the more common idiom in the surrounding non-Java material. The compromise
 * accepted is that this file reads differently from that material; what is bought is one spelling across
 * the whole Java tree, taken from the one document that defines the labels, and it is the spelling
 * {@code services/pom.xml} and {@code docs/CODE_DOCUMENTATION_STANDARD.md} already use. The hazard this
 * specifically avoids is not hypothetical: L548 of {@code tests/README.md} writes its trade-offs label
 * with a non-breaking hyphen rather than the ordinary one, and that line carries no ordinary hyphen at
 * all, so a label imitated from there is byte-different from every other label in the repository and no
 * plain search will ever find it.</p>
 *
 * <p>Assumptions: the written convention these labels answer to is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}. Where this descriptor and that document appear to differ,
 * the rule governs first, the Checkstyle configuration second and the prose standard third.</p>
 *
 * <p>Parameters, return values, exceptions or errors. A package declaration accepts no argument, yields no
 * value and raises nothing, so this descriptor carries no such at-clause. The inapplicability is declared
 * rather than passed over so that a reader can tell it from an omission, and inventing a tag to look
 * thorough would fabricate a contract that does not exist -- which is the defect the rule's own list of
 * forbidden patterns is aimed at.</p>
 */
package com.carddemo.account.repository;
