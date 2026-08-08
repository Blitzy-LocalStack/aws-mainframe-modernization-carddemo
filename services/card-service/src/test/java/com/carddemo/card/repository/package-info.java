/**
 * Verification boundary for the two access paths by which a card row can be reached, asserted against
 * a real PostgreSQL engine rather than against a substitute for one.
 *
 * <h2>Purpose, and the charter this one narrows</h2>
 *
 * <p><b>Purpose.</b> This package is the persistence-layer test boundary of card-service. It holds the
 * integration test of the card bounded context's data-access layer: a test here starts a PostgreSQL
 * container, lets Flyway build the {@code card} schema from
 * {@code services/card-service/src/main/resources/db/migration/V1__card.sql}, and then asserts that
 * {@link com.carddemo.card.repository.CardRepository} reaches the rows that migration defines, through
 * the key and in the order the reference application reached them. Nothing here substitutes a mock for
 * a database; a test that mocks the repository is asserting its caller and belongs beside that caller
 * in {@code com.carddemo.card.service}.</p>
 *
 * <p>Alternatives Considered: the main-tree charter at
 * {@code services/card-service/src/main/java/com/carddemo/card/repository/package-info.java} owns the
 * query contracts and the access-path rulings themselves, and reproducing them here was the obvious
 * alternative to citing them. This charter narrows that one to integration-test concerns and cites it
 * instead, because two statements of one contract drift apart and a reader then cannot tell which of
 * them is current -- and the reader most likely to consult this file is the one deciding what a test
 * here may assume, which is exactly the question a stale copy would answer wrongly.</p>
 *
 * <h2>Target contract: naming a class here is not a claim it is on disk</h2>
 *
 * <p>Assumptions: the inventory below states the package contract the migration plan assigns, and it is
 * not a listing of the directory. A class named here belongs to this package and a class absent from it
 * does not, whichever files the directory happens to hold; the present contents are read from the
 * directory itself, or from {@code mvn -f services/card-service/pom.xml verify}, rather than from a
 * comment that no comment could keep accurate.</p>
 *
 * <p>Alternatives Considered: withholding this charter until the test it governs was written. Rejected
 * on two independent grounds. This charter is what the author of that test works from -- which column
 * the cursor is, which fixtures may reach the table at all, and what may not be declared here -- so
 * writing it afterwards would leave the package with no stated contract across exactly the interval in
 * which one is needed. And the documentation gate treats a directory as a file set: {@code JavadocPackage}
 * in {@code config/checkstyle/checkstyle.xml} demands a {@code package-info.java} in any directory
 * holding a source file the gate processes, so an integration test landing here without this file beside
 * it fails the build before anything is compiled.</p>
 *
 * <h2>The closed set: one integration test, and the suffix that decides when it runs</h2>
 *
 * <p>Exactly two {@code .java} files constitute this package: this charter, and
 * {@code CardRepositoryIT}, which exercises {@code CardRepository} against a real PostgreSQL engine
 * over the entity {@link com.carddemo.card.domain.Card} and the single table {@code cards} in the
 * {@code card} schema. The set is closed because the context maps exactly one entity onto exactly one
 * table, so a second repository test here would mean a second table had come into existence outside the
 * migration meant to create it.</p>
 *
 * <p>Assumptions: the {@code IT} suffix on that name is load-bearing rather than decorative, and a
 * rename would silently stop the test running. Surefire executes the classes whose names end in
 * {@code Test} at Maven's {@code test} step, and Failsafe executes the classes whose names end in
 * {@code IT} -- its own default include -- at {@code integration-test}, asserting their results at
 * {@code verify}. The two are described together at {@code services/pom.xml:66-67}. A consequence worth
 * knowing before debugging an apparently absent run: a {@code Test}-suffixed name would place a
 * container-backed test in the same step as the mocked unit tests, and {@code -DskipTests} suppresses
 * Failsafe as well as Surefire, so neither a rename nor that flag produces a failure -- both produce
 * silence. Results land in {@code services/card-service/target/failsafe-reports}, which is the
 * directory {@code .github/workflows/services-ci.yml} collects, and
 * {@code services/card-service/pom.xml} declares the Failsafe coordinate in its own build section for
 * the reason recorded there: the parent carries the plugin in plugin management only, and without the
 * module's entry the integration step does not happen while the build still reports success.</p>
 *
 * <h2>The cursor is one column, and the reference's pair is not it</h2>
 *
 * <p>Assumptions: the browse cursor is the single sixteen-character column {@code card_num}, and nothing
 * is paired with it. That is recorded because a reader glancing at the reference program will see a pair
 * and reasonably infer a composite key: {@code app/cbl/COCRDLIC.cbl:230-235} keeps a last-key pair of a
 * sixteen-byte card number and an eleven-digit account identifier, and then the same two fields again as
 * a first-key pair. Resuming on {@code card_num} alone is sufficient because that column is the primary
 * key of {@code card.cards}, declared as constraint {@code pk_cards} at
 * {@code services/card-service/src/main/resources/db/migration/V1__card.sql:355}, and is therefore
 * already unique; the account identifier in the reference pair serves the list filter and the separate
 * account-keyed access path described below, not the ordering. A test that treated the pair as the key
 * would page differently from the reference at every boundary where two cards share a number prefix,
 * and would still compile and still run.</p>
 *
 * <p>Forward paging walks strictly greater than the cursor in ascending key order; backward paging walks
 * strictly less than it in descending key order, which is what the reference's backward browse does.
 * Whether a further page exists is discovered by requesting one row beyond the page size and observing
 * whether it arrived, so a test asserting the end-of-data boundary asserts the presence or absence of
 * that surplus row rather than a count of the whole matching set.</p>
 *
 * <p>Trade-offs: page size reaches the query as the caller's argument rather than as a constant in this
 * package, so a test here has to supply one on every paging call. The reference fixes it at seven, but
 * seven is the row capacity of a twenty-four-row by eighty-column terminal, which makes it presentation
 * geometry rather than a property of the table; the target carries the same seven as configuration in
 * this module's {@code application.yml}. The cost accepted is that extra argument at every call site.
 * What is bought is that a test asserting paging cannot be invalidated by a change to how many rows a
 * screen shows, because the two are no longer the same number in the same place.</p>
 *
 * <h2>Offset pagination is not available to a test in this package</h2>
 *
 * <p>Alternatives Considered: resuming a page by counting rows from the start of the result, which is
 * the familiar alternative and is excluded here. The reason is behavioural rather than a matter of
 * taste. When rows are inserted or removed between two requests, a query that resumes by counting
 * <b>skips rows it never showed and repeats rows it already showed</b>, because the number of rows
 * preceding the resume point has changed underneath it. Resuming from the key of the last row shown can
 * do neither, since that key is unaffected by what was inserted elsewhere. A test written against
 * counted offsets would therefore assert a paging behaviour the reference does not have, and would pass
 * while doing so.</p>
 *
 * <h2>Where the page envelope is assembled, and what a test here actually receives</h2>
 *
 * <p>Assumptions: every query method on {@code CardRepository} returns {@code List<Card>}, so a test in
 * this package asserts a row sequence and not a page envelope. The shared envelope
 * {@link com.carddemo.common.web.PageResponse} -- carrying items, both boundary keys and the
 * further-page indicator -- is assembled one layer up, in {@code com.carddemo.card.service}, and the
 * reason is a hard constraint rather than a layering preference: that envelope's canonical constructor
 * requires each cursor component to be a token sealed by {@code com.carddemo.common.web.CursorToken}
 * and refuses a raw key, enforced at
 * {@code services/common-lib/src/main/java/com/carddemo/common/web/PageResponse.java:342-343} by the
 * check at {@code :357-364} and asserted against a raw key at
 * {@code services/common-lib/src/test/java/com/carddemo/common/web/PageResponseTest.java:263-271}.
 * Sealing needs key material and the subject the token is issued for, neither of which a repository
 * interface has. A test here that expected an envelope from the repository would be asserting a
 * contract that deliberately lives elsewhere.</p>
 *
 * <p>Refactoring Rationale: that envelope is imported from the shared kernel and is never re-declared in
 * this context, and no test here should introduce a local page or cursor type to assert against. Each
 * reference program hand-rolled its own browse cursor in its own communication area with its own
 * conventions, which is why the pair cited above resembles no other screen's cursor and why no single
 * place could see every assignment to one. A single shared envelope is the direct analogue of the
 * discipline the reference already applies to its record layouts, resolved through one compiler include
 * path rather than copied -- stated at {@code tests/README.md:540-542} as never duplicating a layout and
 * keeping it single-sourced.</p>
 *
 * <h2>The account-keyed path, and why its index is non-unique</h2>
 *
 * <p>Assumptions: reaching an account's cards is an indexed access path that the reference itself
 * declares, and its target index {@code idx_cards_account_id} is non-unique by that declaration rather
 * than by caution. The evidence is entirely in the reference. {@code app/jcl/CARDFILE.jcl:83} defines an
 * alternate index, relates it to the same base cluster at {@code :84}, keys it at {@code :85} with
 * {@code KEYS(11 16)}, and then declares {@code NONUNIQUEKEY} at {@code :86} and {@code UPGRADE} at
 * {@code :87}; the job states the intent itself in the comment at {@code :78}, which names the account
 * identifier as the subject of the index. That key resolves precisely: the operands are a length and a
 * zero-based offset, so eleven bytes at offset sixteen are one-based positions seventeen to twenty-seven
 * of the record, which is exactly {@code CARD-ACCT-ID} at {@code app/cpy/CVACT02Y.cpy:6}. A path over
 * that index is what {@code app/csd/CARDDEMO.CSD} surfaces to the online region as the separate file
 * {@code CARDAIX}, defined at {@code :13} over the alternate-index path data set named at {@code :14}
 * and enabled for browse at {@code :19}. {@code NONUNIQUEKEY} is the whole justification for omitting
 * {@code UNIQUE} from the target index at {@code V1__card.sql:399}: declaring it unique would refuse the
 * second card on an account, a cardinality the reference admits by design. A test asserting that path
 * therefore has to treat zero, one and many rows per account as equally legitimate.</p>
 *
 * <h2>Which fixtures may reach the table, and which must never</h2>
 *
 * <p>Assumptions: only the fixtures classified persistable may be inserted into {@code card.cards} --
 * Class A and Class A-R -- and a Class B fixture must never be, because the schema rejects it
 * physically: the named constraint {@code ck_cards_active_status} at {@code V1__card.sql:366} admits
 * only two status values, and {@code expiration_date} is a true {@code DATE} at {@code :258} rather than
 * a ten-character string. Inserting one yields a constraint violation or a date-parse error instead of
 * the intended assertion, which is a failure pointing at the wrong layer. The normative register of
 * every fixture and its class is {@code services/card-service/src/test/resources/fixtures/README.md},
 * which also records that Class B bytes are to be fed to the request validator or the mapper directly
 * and never to the repository. That register is cited rather than reproduced here, on the same
 * single-sourcing principle the reference applies to its own record layouts.</p>
 *
 * <h2>What may not be added to this package</h2>
 *
 * <p>Assumptions: no architecture rule belongs here. The layering boundaries this charter relies on are
 * owned solely by {@code LayeringRulesTest} in
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture}, and a second statement of
 * a boundary is worse than none, because a reader could not tell which of the two the build enforces.
 * {@code config/checkstyle/checkstyle.xml} configures no import-control module for the same reason, so a
 * question about whether a given import is permitted is answered by that test and nowhere else.</p>
 *
 * <p>Assumptions: no queue-listener and no batch configuration belongs here, because this context has
 * neither to configure. Its whole configuration package is five classes -- the card selector, the data
 * source, the key-management client, the contract metadata and the security chain -- and no listener
 * container or job registration among them. A test fixture standing one up would be configuring a
 * mechanism this bounded context does not have.</p>
 *
 * <p>Assumptions: no money type appears anywhere in this package, and that is a property of the record
 * rather than a restraint being exercised. The whole hundred-and-fifty-byte card record is declared at
 * {@code app/cpy/CVACT02Y.cpy:4-11} as a card number, an account identifier, a verification value, an
 * embossed name, an expiration date, an active-status flag and padding, and not one of the seven is an
 * amount. Stating the absence is more useful than asserting the project's exact-decimal money
 * discipline, which is real but does not bite on this table, and a reader who found it asserted here
 * would go looking for a money path this context does not have.</p>
 *
 * <h2>Two data stores with different capabilities</h2>
 *
 * <p>Assumptions: several properties a test here depends on are supplied by the store rather than by
 * either codebase, and they are recorded as capability differences rather than as anything wanting in
 * the reference. {@code app/csd/CARDDEMO.CSD} defines both card files with
 * {@code READINTEG(UNCOMMITTED)} at {@code :15} and {@code :27}, {@code STRINGS(1)} at {@code :16} and
 * {@code :28}, {@code JOURNAL(NO)} at {@code :19} and {@code :31} and {@code RECOVERY(NONE)} at
 * {@code :21} and {@code :33}. A non-recoverable, single-string data set simply has nowhere to keep a
 * log or a second concurrent position, so the reference programs are written against what their store
 * offers, and a container-backed test here can assert rollback, isolation and a second reader because
 * the relational engine beneath it offers those instead. Both paths remain in service side by side, and
 * neither reading is the correct one for the other's store.</p>
 *
 * <h2>Where the authority for each contract lives</h2>
 *
 * <p>Assumptions: the authoritative spelling of every column, constraint and index is the Flyway
 * migration named above, and not this charter. The reason to consult it is specific rather than
 * procedural: a query naming a property that resolves to a column the migration does not create is
 * accepted by the compiler without complaint and fails only when the statement first executes, so a
 * disagreement between the two has no compile-time signal at all. That migration owns only the objects
 * inside the {@code card} schema and creates no schema itself; the schema, its role and its grants are
 * bootstrapped by {@code data-migration/sql/V0__schemas_and_roles.sql}, which is not on this module's
 * classpath, which is why a container must be told to create the schema before Flyway runs. There is no
 * second migration in this module, so a test expecting one is expecting a file that does not exist.</p>
 *
 * <p>Assumptions: the property-key contract for this package is
 * {@code services/card-service/src/test/resources/application-test.yml}, and its most load-bearing
 * feature is an omission. It deliberately defines no datasource URL, user name or password, because a
 * container assigns its host port when it starts and no literal authored beforehand could be correct;
 * {@code CardRepositoryIT} registers all three dynamically from the running container instead. That is
 * also what keeps every credential and every endpoint out of this repository rather than merely out of
 * sight, so none should be added to a fixture, a comment or an annotation here. Activation is by
 * profile annotation on the test class itself, so a class omitting it reads none of that file.</p>
 *
 * <p>Assumptions: no recorded-output comparison against mainframe behaviour is available for the paths
 * this package verifies, and no claim of that kind should be made for them. {@code tests/README.md:83-85}
 * records that the online reference programs cannot be run end to end without a CICS runtime, which is
 * absent on the runner, and that only their extractable field-validation logic is unit-tested. The one
 * batch program over this file does run, but its read path is a sequential open, read and close, which
 * is a useful reference for record framing while exercising neither the keyset cursor nor the
 * account-keyed path. Correctness for those two therefore rests on this package's own assertions.</p>
 *
 * <p>Assumptions: every path under {@code app/} cited anywhere in this charter is reference material. It
 * is read as the specification, is never modified, and keeps running; the migration adds a path beside
 * it rather than removing one.</p>
 *
 * <p>{@code docs/CODE_DOCUMENTATION_STANDARD.md} states the prose documentation convention these
 * comments follow. Its precedence clause is worth reading exactly rather than from memory: the Agent
 * Action Plan and user-specified Rule 1 govern, and the Checkstyle configuration is authoritative only
 * as the record of what the build currently enforces, as that document states at {@code :386-388}. A
 * setting may never narrow the obligation, so a clause the gate cannot express still stands and review
 * enforces the remainder.</p>
 *
 * <h2>Documentation contract</h2>
 *
 * <p>This charter exists because two checks act as a deliberate pair. {@code JavadocPackage} audits the
 * directory as a file set and requires this file to be present; {@code MissingJavadocPackage} reads the
 * parsed tree of this file and requires it to carry Javadoc, so a plain block comment would satisfy the
 * first and fail the second. Both apply to test sources because {@code services/pom.xml} sets the
 * plugin to include the test source directory, and {@code config/checkstyle/suppressions.xml} exempts
 * only generated sources and the fixture resources -- it states in place that it must never be widened
 * to {@code src/test/java}. Nor can an exemption be taken locally: the three suppression-comment filters
 * are absent from the ruleset, so no in-source marker has any suppressing effect here.</p>
 *
 * <p>Parameters, return values and exceptions are <b>inapplicable</b> to a package declaration rather
 * than omitted from it: there is no callable member in this file to document, so there is nothing for
 * such a tag to describe. The inapplicability is stated explicitly because a docstring that silently
 * omits a required element is one of the patterns Rule 1 forbids, and a reader has to be able to tell a
 * declared inapplicability from an oversight. Fabricating a placeholder tag to fill the gap would be the
 * worse error of the two, and not merely an untidy one: {@code NonEmptyAtclauseDescription} rejects a
 * tag whose description is empty or meaningless, so inventing one would turn a compliant file into a
 * failing build.</p>
 */
package com.carddemo.card.repository;
