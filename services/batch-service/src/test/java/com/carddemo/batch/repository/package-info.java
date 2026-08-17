/**
 * Holds this module's persistence layer against a real PostgreSQL engine, which no other test package
 * in the module does.
 *
 * <h2>Purpose, and the tier this package occupies</h2>
 *
 * <p>Three tiers of this module's test tree assert on the same transcribed rules from different
 * distances, and a reader has to know which one they are standing in before they can judge whether an
 * assertion belongs here. Tier one is {@code com.carddemo.batch.service}: unit tests over those rules,
 * with no Spring context and no store at all. Tier two is {@code com.carddemo.batch.job}: the job
 * launcher on {@code spring-batch-test}, asserting what a job declares and what it returns. Tier three
 * is this package. A fourth tier is not Java at all -- it is the support material under
 * {@code src/test/resources} that this package consumes: the {@code test} profile, the fixture bytes,
 * and the harness script named further down. <b>This package is the only one of the four that starts a
 * database.</b></p>
 *
 * <p>Assumptions: that numbering is a demarcation device for the boundary this charter has to draw, and
 * it is NOT a census of the test tree. The tree's inventory is fixed by the charter at
 * {@code com.carddemo.batch}, which closes it at seven subpackages; the four tiers above name only the
 * three that reason about the same rules this package does, plus the resources they reason with. A
 * reader reconciling the two figures is looking at a scope difference rather than a disagreement.</p>
 *
 * <p>Every other tier substitutes the store, and for those tiers that is correct: a reject precedence, a
 * fixed-point sum and a control break are arithmetic, and an engine would only slow them down. It is not
 * sufficient for the three properties this package exists for, because each of them IS the engine:</p>
 *
 * <ul>
 *   <li>that the production Flyway migration applies, creating {@code batch.batch_run} and the Spring
 *       Batch job-repository tables under the ownership a deployed environment gives them;</li>
 *   <li>that the ten entities this module maps across FIVE schemas -- four of which it does not own --
 *       resolve against real tables with real declared widths, real check constraints and real secondary
 *       indexes;</li>
 *   <li>that the posting unit of work -- transcribed from {@code app/cbl/CBTRN02C.cbl}, whose
 *       {@code 2000-POST-TRANSACTION} paragraph opens at L424 and performs the category-balance update at
 *       L440, the account update at L441 and the transaction write at L442 -- both COMMITS and ROLLS BACK
 *       as ONE transaction while spanning two of those schemas.</li>
 * </ul>
 *
 * <p>The migration plan is the authority for all three. It requires Testcontainers-backed repository
 * integration tests at its section 0.2.1.2, fixes the per-service test shape that names them at its
 * section 0.4.1.2, fixes this package root at its section 0.5.3.1, and at its section 0.4.1.3 records the
 * single documented exception to database-per-service purity -- the posting unit of work reaching two
 * schemas under one commit -- which the third property above is the executable proof of.</p>
 *
 * <p>Refactoring Rationale: this package was empty while
 * {@code src/test/resources/db/testharness/test-harness-schemas-and-foreign-tables.sql} and the Failsafe
 * binding in {@code pom.xml} were both already authored for it. A harness with no executable consumer
 * proves nothing: its SQL can be read for correctness, but reading cannot show that the entity mappings
 * match it, that the constraints fire, or that the three-write commit is atomic. It was worse than
 * nothing while it sat unconsumed, because the module then LOOKED integration-covered.</p>
 *
 * <h2>The closed inventory</h2>
 *
 * <p>This directory holds eleven files and a twelfth is prohibited: this charter, together with
 * {@code BatchRunRepositoryIT}, {@code CrossSchemaFeedRepositoryIT}, {@code CardXrefRepositoryIT},
 * {@code DailyTransactionRepositoryIT}, {@code TransactionRepositoryIT},
 * {@code TransactionCategoryBalanceRepositoryIT}, {@code TransactionRejectRepositoryIT},
 * {@code PostingUnitOfWorkIT}, {@code AccountRepositoryIT} and
 * {@code DisclosureGroupRepositoryIT}.</p>
 *
 * <pre>
 * this directory: 11 java files = 10 tests + 1 charter
 * </pre>
 *
 * <p>Refactoring Rationale: this section counted four files and three tests, and admitted a fourth test
 * once a measurement showed one property genuinely unowned. {@code DailyTransactionRepository} then
 * declared TWO reads over the unposted feed with different lifetimes -- an unbounded lazily-populated
 * cursor and a bounded chunk -- and the feed's entry in {@code CrossSchemaFeedRepositoryIT} exercised the
 * BOUNDED finder only, so the cursor's whole contract was unexercised while the feed LOOKED covered.</p>
 *
 * <p>Refactoring Rationale: that cursor has since been WITHDRAWN, and the fourth test remains for a
 * different reason. A search for callers found none in any module: both jobs loop on the bounded
 * continuation from a watermark that sits below every assigned ordinal, so the continuation finder already
 * covered the first chunk and the cursor froze a surface nothing called. What
 * {@code DailyTransactionRepositoryIT} owns now is the CHUNKED walk's result under conditions one
 * statement cannot show -- per-statement chunk geometry, resumption across a commit, and keyset rather
 * than offset positioning when a read row is deleted -- which is a property of the driving loop rather
 * than of a cursor's lifetime, and still one no sibling holds.</p>
 *
 * <p>Refactoring Rationale: this inventory then read five files and named a sixth prohibited, and it is
 * raised to six because {@code CardXrefRepositoryIT} was added deliberately rather than by drift. What
 * was wrong with the previous arrangement is recorded on the cross-reference entry below: the two
 * access paths over {@code account.card_xref} were carried as ONE case inside
 * {@code CrossSchemaFeedRepositoryIT}, which could assert that both finders resolve but could not
 * assert the property the by-account path actually turns on -- that its result is DETERMINED when an
 * account holds several cards. A prohibition is a useful thing for this roster to carry, so the prose
 * above still carries one at the next number up; what it must not do is prohibit a proof the package
 * needs, and the marker line is re-measured against the directory on every build so the two figures
 * cannot drift apart again.</p>
 *
 * <p>Refactoring Rationale: this inventory then read six files and named a seventh prohibited, and it is
 * raised to nine because three further proofs were admitted, each on the test this roster states rather
 * than by drift. The first is {@code TransactionRepositoryIT}, admitted on the test this roster states
 * rather than by drift: it names properties the list did not already own. {@code TransactionRepository}
 * declares three reads, and a search for callers of each found that TWO of them had no executable
 * consumer anywhere in the module -- the unbounded ascending walk that rebuilds the master, and the
 * bounded continuation a restarted step resumes from. Only the third, the card-ordered daily subset, was
 * exercised, and that one is {@code PostingUnitOfWorkIT}'s. So the interface that carries the module's
 * only write surface had its two ordered reads entirely unexercised while the table LOOKED covered,
 * which is the same shortfall this section already corrected twice above and is corrected here the same
 * way. The prohibition the prose carries moves up a number rather than being dropped, because what a
 * prohibition must not do is bar a proof the package needs.</p>
 *
 * <p>Refactoring Rationale: the second admission is
 * {@code TransactionCategoryBalanceRepositoryIT}, which closes a proof this charter had
 * CLAIMED while no file held it. The entry for {@code PostingUnitOfWorkIT} below asserted that it owned
 * the ascending walk of the category balances in key order, citing the arithmetic that fixes that order;
 * that class declares six cases and none of them was the walk. Nor did any sibling hold it: the two
 * production callers of the ordered finder are stubbed wherever a job test reaches them, and the one
 * integration class that calls it for real uses it as a byte read-back over single-account fixtures and
 * asserts nothing about ordering. So the claim was true of no file, which is a worse failure than an
 * uncounted file -- a reader auditing coverage would have found the property attributed and stopped
 * looking. The marker check catches a count that falls behind but cannot catch a claim that names the
 * wrong owner, which is why the entry below now names the class that actually holds it.</p>
 *
 * <p>Refactoring Rationale: the third admission is
 * {@code TransactionRejectRepositoryIT}, added deliberately rather than by
 * drift. What was wrong with the previous arrangement is recorded on the feed entry below: the reject
 * stream's 430-byte composition was carried as ONE case inside {@code CrossSchemaFeedRepositoryIT}, which
 * could assert that a 350-character value round-trips at its declared width but could not assert the
 * property the composition actually turns on -- that the stored image is the daily record's own image AS
 * READ, retaining that record's blank processing-timestamp span rather than a freshly stamped one. A
 * case measuring only the width passes against an implementation that recomposed the record and stamped
 * it, which is precisely the defect the composition proof exists to catch, so the proof was widened and
 * given an owner of its own.</p>
 *
 * <p>Refactoring Rationale: this inventory then read nine files and named a tenth prohibited, and it is
 * raised to ten because {@code AccountRepositoryIT} closes proofs this charter had ATTRIBUTED while no
 * file held them -- the same failure this section corrected once already, and the one it calls worse than
 * an uncounted file because a reader auditing coverage finds the property named and stops looking. The
 * entry for {@code PostingUnitOfWorkIT} below claimed the account cycle accumulators and the version
 * ruling; a count of its six cases found the accumulators covered on their POSITIVE arm alone and the
 * version ruling covered by nothing whatever, in that class or any sibling. It also read every commit and
 * every rollback through a CLEARED PERSISTENCE CONTEXT on the writing connection, while ruling four below
 * states that atomicity here is proved from a SECOND CONNECTION -- so the technique the ruling prescribes
 * had no executable instance either, and the in-flight moment that distinguishes one transaction from
 * three was never observed at all. The prohibition the prose carries moves up a number rather than being
 * dropped, because what a prohibition must not do is bar a proof the package needs.</p>
 *
 * <p>Refactoring Rationale: that marker line is what turns the sentence above from a claim into a checked
 * one. {@code PackageCharterInventoryTest} in the shared kernel parses the line and re-counts this
 * directory on every build, so an inventory that falls behind fails the build instead of ageing quietly.
 * The same test re-counts each member's declared case total against the annotations in the file it names,
 * and it holds the number of charters carrying a marker to a floor, so deleting the line to silence a
 * failure is a visible act in review rather than a quiet one.</p>
 *
 * <p>Refactoring Rationale: this inventory then read ten files and named an eleventh prohibited, and it
 * is raised to eleven because {@code DisclosureGroupRepositoryIT} closes proofs that were PARTIALLY held
 * and, in two cases, held nowhere. What was wrong with the previous arrangement is that
 * {@code reference.disclosure_groups} was reached by a single case inside
 * {@code CrossSchemaFeedRepositoryIT}, which counts the seeded default rows and spot-checks three of
 * them. A count cannot show that each of the seventeen type-and-category pairs is INDIVIDUALLY
 * reachable, and the seed requirement is one row per pair precisely because
 * {@code app/cbl/CBACT04C.cbl:437} substitutes the group component alone -- so a single absent pair
 * abends every account presenting it while the count still reads seventeen. Two further properties had no
 * executable instance anywhere in the package: that the key's COMPONENT ORDER is the record's physical
 * one rather than the order {@code app/cbl/CBACT04C.cbl:210-212} assigns the components in, which matters
 * because the seed carries both type {@code 01} with category {@code 0002} and type {@code 02} with
 * category {@code 0001} at different rates, so a transposed key returns a rate instead of missing and the
 * defect is silent; and that the padded and bare forms of the default group identifier address one row,
 * which is what makes the retry's space-filled literal find the row a reader's bare literal seeds. The
 * prohibition the prose carries moves up a number rather than being dropped, because what a prohibition
 * must not do is bar a proof the package needs.</p>
 *
 * <p>Trade-offs: the count case in {@code CrossSchemaFeedRepositoryIT} is RETAINED rather than removed
 * now that a dedicated owner exists, on the reasoning this section already records for the reject stream
 * and the cross-reference. That case reads the seeded total as one of the harness post-state facts its
 * own subject is, where the new class reads each pair as a precondition of the fallback; deleting it to
 * tidy the boundary would take a green sibling's assertion out of use without replacing what it
 * measures. The residual cost is that a reader meets the default rows in two files, recorded here so the
 * second encounter reads as a boundary rather than as a duplicated proof.</p>
 *
 * <p>Refactoring Rationale: this section previously described eight entities across FOUR schemas, and the
 * directory had moved past it in both figures. The count is ten entities across five, because
 * {@code CBEXPORT} reads five masters and the export job could reach only three of them, so the customer
 * and card phases published empty on every run; the customer and card interfaces and their two entities
 * were the whole of that shortfall, and the {@code card} schema arrived with them. The production charter
 * beside this one records the same correction from its own side and is the authority for the interface
 * roster; it is cited rather than restated, so the two cannot disagree about a number.</p>
 *
 * <p>Alternatives Considered: one integration class per production interface, which would tie this
 * roster's size to the interface count and would let each class be named for the interface it covers. Rejected
 * on what the classes would then contain. The property worth proving about most of the read-only feeds is
 * identical in each case -- that a mapping resolves against a table this module does not own and walks it
 * in a declared order -- so ten classes would be several near-copies of one another, and a change to the
 * harness would have to be chased through all of them. The property worth proving about posting is not a
 * property of any single interface at all: it spans four of them in one commit, so no per-interface class
 * could hold it without either splitting the proof or duplicating it. The classes below are
 * therefore partitioned by PROPERTY rather than by interface, and between them they reach all ten.</p>
 *
 * <p>Assumptions: none of the three classes named for a single interface is a departure from that
 * partition but an application of it. Each is named for an interface because it happens to reach only
 * one, yet what each owns is a PROPERTY none of the others holds -- the CHUNKED walk's behaviour across
 * more than one statement in the first, the determinacy of a by-account read over a NON-UNIQUE index in
 * the second, and the two access disciplines one cluster carries at once in the third -- and all three
 * are different questions from whether a mapping resolves and walks in order.
 * Two classes therefore touch the daily-transaction interface, and the split between them is by
 * QUESTION rather than by member, now that the interface declares a single read: one bounded walk of the
 * feed belongs to {@code CrossSchemaFeedRepositoryIT} because it is one of that class's six
 * near-identical feed walks and asks only whether the mapping resolves and orders, while the driving
 * loop's own properties -- chunk geometry, resumption, and positioning under a concurrent delete --
 * belong to {@code DailyTransactionRepositoryIT} because they have no counterpart among them. Two classes
 * likewise touch the cross-reference, and that split is by KIND of statement: the catalog fact and a
 * reachability read stay with the feed walks, while the keyed access paths and their multiplicity
 * ruling belong to {@code CardXrefRepositoryIT}.
 * Two classes finally touch the reject stream, and that
 * split is by QUESTION as well: the reason-code constraint and a reachability read stay with the feed
 * walks, while the 430-byte composition and its as-read property belong to
 * {@code TransactionRejectRepositoryIT}.
 * A further test would need to name a property this list does not
 * already own.</p>
 *
 * <h2>Ruling one: the name is what makes a class here run at all</h2>
 *
 * <p>Assumptions: every class in this package ends in {@code IT}, and that ending alone is what causes it
 * to run. The reactor divides its two test phases by class name and by nothing else -- neither runner is
 * configured with an include pattern anywhere in the reactor, and both {@code services/pom.xml} and
 * {@code services/batch-service/pom.xml} declare the integration runner as a bare coordinate whose own
 * comment records that the default patterns already match. The unit runner collects names beginning with
 * {@code Test} or ending in {@code Test}, {@code Tests} or {@code TestCase}, during the Maven
 * {@code test} phase, where no container has been started. The integration runner collects names
 * beginning with {@code IT} or ending in {@code IT} or {@code ITCase}, running them at
 * {@code integration-test} and asserting their results at {@code verify}. The test-tree charter at
 * {@code com.carddemo.batch} owns the full derivation of that split and the failure a wrong name
 * produces; what is restated here is only the part that binds a class in this directory.</p>
 *
 * <p>Assumptions: the plan's uniform per-service shape spells the name {@code *RepositoryIT}, and nine
 * of the ten members here follow that spelling while {@code PostingUnitOfWorkIT} does not. The departure
 * is
 * deliberate and is recorded so it does not read as an oversight: the operative selector is the
 * {@code IT} ending, which that name satisfies, and what the class proves is a unit of work spanning four
 * interfaces and two schemas rather than one interface's contract, so naming it after any single
 * repository would misdescribe the only thing it asserts. A name ending in {@code Test} would be the real
 * error, and it fails quietly rather than loudly -- the unit runner would collect it in a phase that
 * starts no container, so it would fail on connection rather than on its assertions, or be skipped by
 * whatever guard its author added to keep the build green.</p>
 *
 * <p>Assumptions: neither report directory is relocatable. {@code .github/workflows/services-ci.yml}
 * collects from exactly {@code services/*}{@code /target/surefire-reports/} and
 * {@code services/*}{@code /target/failsafe-reports/} at two adjacent lines, so a relocated directory
 * yields a green run that published no evidence. Local verification is
 * {@code mvn -f services/pom.xml clean verify} and never {@code test}: a run stopping at {@code test}
 * executes none of these classes, and one stopping at {@code integration-test} executes them without
 * failing on their outcome.</p>
 *
 * <h2>Ruling two: the profile and the connection are declared on the class</h2>
 *
 * <p>Assumptions: each of the ten carries {@code @ActiveProfiles("test")}, which is the whole of how
 * {@code src/test/resources/application-test.yml} comes into force. No build plugin activates that
 * profile on any class's behalf, so a class omitting the annotation would resolve the base profile
 * instead and reach for remote configuration sources the container does not serve.</p>
 *
 * <p>Assumptions: no connection literal appears anywhere in this package or in that profile, and none may
 * be introduced. A container assigns its host port as it starts, so a literal authored beforehand would
 * either address nothing or -- the worse outcome, because it passes -- address whatever database happened
 * to be listening. Each class registers the container's generated URL, user name and credential through
 * {@code @DynamicPropertySource}, and registers the Flyway user and password in addition to the datasource
 * pair because the base profile binds those two keys to placeholders with no fallback.</p>
 *
 * <p>Assumptions: {@code @ServiceConnection} is not used, on a classpath fact rather than a preference. It
 * ships in {@code org.springframework.boot:spring-boot-testcontainers}, which sibling modules declare and
 * this module's POM deliberately does not, so a reader arriving from one of those files would otherwise
 * expect a mechanism that cannot resolve here.</p>
 *
 * <p>Alternatives Considered: one shared abstract base class holding the container, the property
 * registration and the schema prerequisite once for all ten. Rejected: a container held in a base class
 * is shared mutable state, so rows one class inserts are rows another reads, and the failure then names
 * whichever class happened to run second. That hazard is concrete rather than hypothetical here, because
 * four of the ten arrange rows in {@code account.card_xref} and each empties it for itself. Each class
 * starts its own container and owns its own schema state, which is what lets any one of the ten be run
 * alone and still mean something. Trade-offs: the
 * accepted cost is ten container starts and ten copies of the container declaration, paid every time
 * that declaration changes.</p>
 *
 * <p>Alternatives Considered: an in-memory engine, rejected more firmly here than anywhere else in the
 * build. Two of the three properties this package exists for are engine behaviours -- cross-schema
 * qualification inside one transaction, and a CHECK constraint that rejects a row at the statement that
 * writes it -- and a substitute implements both differently or not at all, so a passing assertion would
 * say nothing about the engine the jobs actually run against. No embedded driver appears on this module's
 * classpath.</p>
 *
 * <h2>Ruling three: the schema arrives in two halves, and the order is load-bearing</h2>
 *
 * <p>Assumptions: the split between the two halves is not arbitrary. The {@code batch} schema is this
 * module's own, so its objects come from the production migration on the classpath and from nothing else
 * -- that migration is part of what is under test. The other four schemas belong to transaction-service,
 * account-service, reference-service and card-service, and this module may depend on {@code common-lib}
 * and on no other sibling service, so their migrations are unreachable from this test classpath. They are
 * supplied instead by
 * {@code src/test/resources/db/testharness/test-harness-schemas-and-foreign-tables.sql}, which each class
 * hands to its container through {@code withInitScript} so that it runs before Flyway opens a connection.
 * That script is deliberately not a Flyway migration -- it carries no version prefix and sits outside
 * {@code db/migration} -- because Flyway applying it would place another context's tables under this
 * module's migration history. Its name is therefore load-bearing rather than descriptive.</p>
 *
 * <p>Assumptions: the script supplies four schemas and nine tables, and each class relies on them rather
 * than creating them. It creates {@code ledger}, {@code account}, {@code reference} and {@code card}
 * idempotently; the nine tables backing the nine entities whose schemas this module does not own; the
 * NON-UNIQUE index on the cross-reference's account column, which is the replacement for the baseline's
 * account-keyed alternate index and without which the two-path proof below could not run at all; and
 * exactly seventeen rows in the disclosure-group table under the padded {@code DEFAULT} group key, seeded
 * through {@code ON CONFLICT DO NOTHING} so a reused container does not accumulate duplicates.</p>
 *
 * <p>Assumptions: the script deliberately does NOT create {@code batch}, and its own opening section
 * records the measurement behind that. An init script runs as the container's superuser, so a
 * {@code batch} schema created there is owned by that user, and the test profile's Flyway
 * {@code init-sqls} then assumes a NOLOGIN role which is refused CREATE on a schema it does not own with
 * SQLSTATE 42501. Letting Flyway create it under that role instead -- which is why that profile sets
 * {@code create-schemas} true rather than false -- reproduces the deployed ownership exactly.</p>
 *
 * <h2>Ruling four: three harness facts that decide test technique</h2>
 *
 * <p>Alternatives Considered: proving the reference schema read-only by writing to it and asserting the
 * privilege error. Impossible here, and the reason is a property of the harness rather than a matter of
 * taste: the script creates no role and issues no {@code GRANT}, so these tests connect as the container's
 * superuser and every table is writable at test time. The read-only property is therefore carried at
 * COMPILE time instead -- the disclosure-group interface extends the narrow
 * {@code org.springframework.data.repository.Repository} base rather than a CRUD base, so a save or a
 * delete is a compilation error and never reaches a database that would have permitted it.</p>
 *
 * <p>Alternatives Considered: proving rollback atomicity by provoking a foreign-key violation between two
 * of the written tables. Also impossible: the harness declares no inter-table foreign key. Atomicity is
 * therefore proved by ROW VISIBILITY observed from OUTSIDE the failed transaction -- a second connection
 * reads the three tables after the failure and finds none of the three writes -- which is a stronger
 * statement in any case, because it is about what the engine made durable rather than about which
 * constraint fired.</p>
 *
 * <p>Assumptions: that second connection is already available and no mechanism needs inventing to obtain
 * one. The test profile sets the pool maximum above one and turns connection auto-commit off, which is
 * exactly what an outside observer and a version race both require; each class reaches the observer by
 * building a template over the injected {@code DataSource}. A pool of one would deadlock the observation
 * rather than fail it, which is the failure this note exists to prevent.</p>
 *
 * <h2>Ruling five: the harness DDL is the normative physical contract at test time</h2>
 *
 * <p>Assumptions: the test profile runs Hibernate schema validation, so a mapping that disagrees with the
 * harness on a column name, a width or a type is a HARD STARTUP FAILURE for every class in this package
 * at once -- not one failing assertion in one case. The blast radius is what makes this a standing
 * ruling: the harness DDL is the normative physical contract at test time, every mapping is verified
 * against it before anything is asserted about behaviour, and a residual disagreement is recorded as a
 * blocking coordination finding rather than settled by quietly editing whichever side is easier to
 * reach.</p>
 *
 * <p>Refactoring Rationale: four column contracts were previously carried differently on the two sides
 * and are reconciled, and they are named so nobody re-derives them: the account master's key column and
 * its postal-code width, the cross-reference's customer and account columns, and the disclosure group's
 * three key columns. The harness spellings are the ones in force, and the fixture guide under
 * {@code src/test/resources/fixtures} cites the harness for the schema facts it states rather than
 * deriving them independently, so there is one source for them and not two. The reject table agrees on
 * both sides already, and its agreement is the 430-byte contract itself: a 350-character raw record
 * beside a small reason code and a 76-character description, which is the file description at
 * {@code app/cbl/CBTRN02C.cbl:82-84} adding 350 and 80, confirmed independently by the
 * {@code LRECL=430} at {@code app/jcl/POSTTRAN.jcl:36}.</p>
 *
 * <h2>What each member owns, so that no proof is duplicated and none is orphaned</h2>
 *
 * <ul>
 *   <li>{@code BatchRunRepositoryIT} across 12 cases -- the step ledger this module OWNS, and the only
 *       table it does own. It holds the unique constraint over the run and step pair that makes a
 *       redriven step which already completed a no-op, and it is the class that proves the production
 *       migration applies at all. The ledger is a documented IMPROVEMENT rather than a port: the only
 *       {@code RESTART=} anywhere in the reference is commented out, at {@code app/jcl/DEFGDGD.jcl:2},
 *       and no checkpoint clause appears in any of its jobs, so there is no baseline capability here to
 *       be faithful to.</li>
 *   <li>{@code CrossSchemaFeedRepositoryIT} across 9 cases -- the harness post-state and the six
 *       read-only feeds: the card, cross-reference, customer, daily-transaction, disclosure-group and
 *       reject interfaces. Of the daily-transaction interface it owns ONE bounded walk asking whether the
 *       mapping resolves and orders; the driving loop's own properties belong to
 *       {@code DailyTransactionRepositoryIT} below, and the division is by question rather than by
 *       member, the interface now declaring a single read. Of the reject interface it owns the
 *       reason-code CHECK constraint -- that a value outside the four-digit picture is refused by the
 *       engine at the statement that writes it -- together with one reachability case over the stored
 *       row; the 430-byte COMPOSITION and the as-read property belong to
 *       {@code TransactionRejectRepositoryIT} below.
 *       Trade-offs: that reachability case overlaps the composition owner's width case, and the overlap
 *       is retained rather than removed, on the same reasoning recorded for the cross-reference further
 *       down. Deleting a case from a green sibling to tidy a boundary would take its reject fixture
 *       helper and two of its imports out of use with it, and the two are not the same assertion in any
 *       event -- this one asks whether a 350-character value round-trips at its declared width, where the
 *       owner asks whether the stored image is the daily record's own, which a width assertion cannot
 *       see. The residual cost is that a reader meets the reject stream in two files; it is recorded
 *       here so the second encounter reads as a boundary rather than as a duplicated proof.
 *       On the cross-reference it owns the CATALOG fact -- that the
 *       by-account secondary index exists and is not unique -- which is a statement about the schema
 *       rather than about a query, and it additionally carries one reachability case confirming that
 *       both cross-reference finders resolve at all.</li>
 *   <li>{@code CardXrefRepositoryIT} across 8 cases -- the two keyed access paths over
 *       {@code account.card_xref}, held apart from one another. They are separate cases because the
 *       baseline separates them at three levels: {@code app/jcl/XREFFILE.jcl:43} keys the base cluster
 *       {@code KEYS(16 0)} on the card number while {@code app/jcl/XREFFILE.jcl:74-75} keys the
 *       alternate index {@code KEYS(11,25)} and declares it {@code NONUNIQUEKEY};
 *       {@code app/cbl/CBACT04C.cbl:38} declares that alternate key alongside the record key; and
 *       {@code app/jcl/INTCALC.jcl:29-32} mounts BOTH as separate data definitions where
 *       {@code app/jcl/POSTTRAN.jcl:32-33} mounts the base cluster alone. Because the index is
 *       explicitly non-unique one account may legitimately hold many cards, so this class owns the
 *       ruling that the by-account finder must be ordered by card number ascending and bounded to one
 *       row, and it owns the demonstration that the bare single-result alternative raises an
 *       incorrect-result-size failure over exactly the rows the bounded finder resolves cleanly. It
 *       also owns the compile-time reading of the read-only contract, the cross-reference being the one
 *       {@code account} table this module never writes. Its multiplicity fixture is CONSTRUCTED and
 *       that is the point of the class: {@code app/data/ASCII/cardxref.txt} is 50 rows under 50
 *       distinct accounts and both parity fixtures are one-to-one, so no shipped row exercises the
 *       multiplicity the contract admits and a broken finder would pass against all of it.
 *       Trade-offs: the reachability case named on the entry above overlaps this class's two path
 *       cases, and the overlap is retained rather than removed. Deleting a case from a green sibling to
 *       tidy a boundary would take four of its constants and one of its imports out of use with it, and
 *       the two are not the same assertion in any event -- reachability asks whether each finder
 *       resolves, where the cases here ask whether the by-account result is DETERMINED under
 *       multiplicity that no shipped row carries. The residual cost is that a reader meets the
 *       cross-reference in two files; it is recorded here so the second encounter reads as a boundary
 *       rather than as a duplicated proof.</li>
 *   <li>{@code DailyTransactionRepositoryIT} across 9 cases -- the CHUNKED forward walk over the unposted
 *       feed, driven exactly as the two jobs drive it: repeated bounded continuations from the watermark,
 *       each capped at the deployed chunk size. Refactoring Rationale: this entry described an UNBOUNDED
 *       forward-only cursor and the properties of its lifetime -- that a mandatory propagation refused a
 *       call holding no transaction, and that the walk closed -- and both the member and those properties
 *       are gone. {@code findAllByOrderByIngestSeqAsc} had no production caller: both jobs loop on the
 *       bounded continuation from {@code DailyFeedWatermarkService.NOTHING_CONSUMED}, which is below every
 *       assigned ordinal, so the continuation finder already covered the first chunk and the cursor froze a
 *       surface nothing called. It was withdrawn rather than given a caller, because a caller would have
 *       had to abandon per-record commits to satisfy a cursor's lifetime.</li>
 *   <li>What the class owns now is the walk's RESULT under conditions one statement cannot show. It
 *       inspects each statement separately -- exactly two chunks, the first at the cap and the second
 *       carrying the remainder, with no chunk above the cap -- which eager buffering of a single query
 *       cannot satisfy; that a chunk read needs no enclosing transaction and resumes across a commit taken
 *       between two reads; that a row DELETED from the already-read range cannot make the next chunk skip
 *       an unread row, which offset positioning would fail and keyset positioning cannot; that an empty
 *       feed delivers no row; that the continuation predicate is STRICTLY greater, so a resumed step never
 *       processes a row twice, and that it caps its result; that a continuation from the last ordinal
 *       delivers nothing; that the interface's reachable surface is exactly ONE read, exposing no mutator
 *       and no row-counting window, which is what makes the read-only guarantee structural rather than
 *       dependent on a privilege these tests do not have; and that an amount round-trips through the
 *       engine at scale two with its sign. The ordering
 *       key is the ingestion ordinal and the class asserts it is neither of the two columns a reader
 *       reaches for first: the processing stamp is absent on every row the walk returns, and the
 *       transaction identifier carries only a partial order. The reference read it mirrors is declared
 *       {@code ORGANIZATION IS SEQUENTIAL} with {@code ACCESS MODE IS SEQUENTIAL} at
 *       {@code app/cbl/CBTRN02C.cbl:29-32} and driven at {@code app/cbl/CBTRN02C.cbl:202-219}, and
 *       {@code app/jcl/POSTTRAN.jcl:30-31} mounts the dataset for input only, which is the documentary
 *       basis for an interface that declares no mutator.</li>
 *   <li>{@code TransactionRepositoryIT} across 11 cases -- the posted-transaction master's two ordered
 *       reads, neither of which any other member reaches, and the convergence of the two producers that
 *       write it. It owns the combine walk's ordering, which is transcribed from a sort utility rather
 *       than from a program: {@code app/jcl/COMBTRAN.jcl:28} declares {@code TRAN-ID,1,16,CH}, a
 *       sixteen-byte CHARACTER field at position one, and {@code app/jcl/COMBTRAN.jcl:30} requests it
 *       ascending. Because that format is a BYTE comparison, the class owns the further ruling that the
 *       walk must be collation-independent, and its fixture is built to detect the failure rather than to
 *       illustrate the success: it mixes the hyphenated identifiers the accrual golden carries with
 *       digit-only ones, and asserts that a byte order and a punctuation-blind order genuinely DISAGREE
 *       on that fixture before asserting which of the two the engine produced. A single-shape fixture
 *       would order identically under every collation and pass whatever the column's collation was. It
 *       owns the continuation predicate being STRICTLY greater and which row that excludes, and the
 *       absence of counting-based positioning from the interface's declared surface. Both of those are
 *       target-side: the only {@code RESTART=} in the reference is commented out at
 *       {@code app/jcl/DEFGDGD.jcl:2}, so resumption is an improvement rather than a port, and the class
 *       says so rather than citing a paragraph that does not exist. Its convergence cases are the one
 *       place the two producers are observed on one table at all -- posting writes the master at
 *       {@code app/cbl/CBTRN02C.cbl:564} while accrual writes a separate {@code SYSTRAN} generation
 *       declared across {@code app/jcl/INTCALC.jcl:37-41} -- and what licenses one table for both is that
 *       {@code app/jcl/COMBTRAN.jcl:23-26} already fed the sort their concatenation, so their union was
 *       always the working set. It asserts the accrual constants against
 *       {@code tests/golden/interest/happy_path/transact.expected}, including that the category code
 *       stores as {@code 0005} and not {@code 05}: the program moves a two-character literal at
 *       {@code app/cbl/CBACT04C.cbl:483} into a field {@code app/cpy/CVTRA05Y.cpy:7} declares
 *       {@code PIC 9(04)}, so the move zero-fills rather than space-fills.</li>
 *   <li>{@code PostingUnitOfWorkIT} across 6 cases -- the atomicity proof, the account write that only
 *       it performs, and the daily-subset finder's ordering and window
 *       against a real engine. That last case is here rather than beside the job that calls the finder
 *       because a stubbed repository can model an ORDER BY but cannot evaluate one, and because the
 *       window's upper bound is STRICT on a {@code TIMESTAMP(6)} -- a boundary only a real engine
 *       decides. It seeds rows one microsecond outside each edge, which is the resolution the column
 *       is declared at, so a comparison that admitted the following midnight is caught. It owns the
 *       commit-and-rollback pair in the baseline order established at L440 to L442 AS READ THROUGH A
 *       CLEARED PERSISTENCE CONTEXT on the writing connection, refused at the LAST of the three writes,
 *       together with the POSITIVE arm of the account cycle accumulators that
 *       {@code app/cbl/CBTRN02C.cbl:545-551} maintains alongside the running balance.
 *       Refactoring Rationale: this entry also claimed the ascending walk of the category balances in key
 *       order, and that claim is withdrawn because none of the six cases here was that walk -- the
 *       property is owned by the entry below, which does assert it. What this entry retains of the
 *       category balance is the single write it performs as the FIRST of the three that commit together.
 *       Refactoring Rationale: this entry further claimed the accumulators without qualification and the
 *       version ruling outright, and both claims are narrowed for the reason the admission note above
 *       records -- the six cases here reach neither the zero nor the negative arm and contain no version
 *       case at all. The out-of-transaction reading, the two earlier failure positions and the whole of
 *       the version ruling belong to {@code AccountRepositoryIT} below.</li>
 *   <li>{@code TransactionCategoryBalanceRepositoryIT} across 11 cases -- the two access disciplines the
 *       category-balance interface declares, which is the one cluster the reference reaches two
 *       structurally different ways. It owns the ASCENDING WALK in key order, which is what makes the
 *       interest control break at {@code app/cbl/CBACT04C.cbl:194} correct: that program declares the
 *       cluster {@code ORGANIZATION IS INDEXED} with {@code ACCESS MODE IS SEQUENTIAL} at L29 to L30,
 *       and the key it walks is proved arithmetically by the {@code KEYS(17 0)} at
 *       {@code app/jcl/TCATBALF.jcl:40} against the eleven, two and four digit fields at
 *       {@code app/cpy/CVTRA01Y.cpy:6-8}, fixing the order as account, then type, then category. The
 *       break is a SINGLE-KEY compare carrying no set of accounts already seen, so an unordered walk
 *       does not merely look untidy -- it flushes a partial interest total per spurious break and
 *       reports success having posted the wrong money. Its walk fixture therefore varies all THREE key
 *       levels, is inserted in the exact reverse of the expected order, and exceeds the walk's own
 *       fetch window, so neither insertion order nor a single-level ordering can satisfy it; a
 *       companion case pins the account identifier as ordered by MAGNITUDE using identifiers of
 *       differing digit counts, which equal-width padded values could not distinguish. It also owns the
 *       keyed read and the two arms at the DATABASE level: that an absent row is an empty result rather
 *       than a raise, which is the relational form of the not-found status
 *       {@code app/cbl/CBTRN02C.cbl:481} accepts alongside success, and that the create arm at
 *       {@code :503-524} stores exactly the posted amount while the update arm at {@code :526-542} adds
 *       to the balance already read -- asserted as separate cases against the two committed vectors, so
 *       which arm ran stays observable. Two applications of an amount distinguish addition from
 *       assignment, and a CONSTRUCTED negative case covers the sign that no shipped posting fixture
 *       reaches. It owns the CATEGORY-BALANCE half of the accumulation obligation only; the account
 *       cycle buckets are the entry above's.</li>
 *   <li>{@code TransactionRejectRepositoryIT} across 7 cases -- the reject stream's 430-byte
 *       composition, and specifically the property that phrase turns on: that the stored image is the
 *       daily-transaction record AS READ. {@code app/cbl/CBTRN02C.cbl:447} moves
 *       {@code DALYTRAN-RECORD} wholesale into the reject area in one statement and {@code :448} moves
 *       the trailer over the remaining eighty, so nothing is recomposed, reformatted or re-stamped. The
 *       consequence it proves is that the image keeps the DAILY record's own spans from
 *       {@code app/cpy/CVTRA06Y.cpy} -- a BLANK processing-timestamp span at 1-based 305 to 330 beside a
 *       POPULATED originating span at 279 to 304 -- and not the posted transaction's stamp, which only
 *       {@code app/cbl/CBTRN02C.cbl:437-438} mints and only on the arm of {@code :211-216} this stream is
 *       never written from. Both directions are asserted, and a further case appends a deliberately
 *       stamped image to show the span predicate can actually fail, because a width-only assertion passes
 *       against a recomposing implementation. It also owns the four persisted reason codes with their
 *       verbatim texts as DATA, stored as a {@code SMALLINT} against the four zero-padded characters of
 *       the wire form; the admission of DUPLICATE rejects, which is target-side and has no baseline
 *       constraint to be faithful to; the database-assigned surrogate ordinal, the reference stream being
 *       keyless per {@code app/cbl/CBTRN02C.cbl:46-49}; and the compile-time reading of the append-only
 *       contract, the interface extending the marker base and declaring one member. Trade-offs: it
 *       asserts ROWS and not BYTES -- {@code TransactionRejectRecordMapper} owns the byte image and the
 *       job tier owns whole-stream parity against the committed reject expectations -- and it asserts no
 *       return code, a graded exit status being a process concern rather than a repository's.</li>
 *   <li>{@code AccountRepositoryIT} across 16 cases -- the account master's three durable rulings, each
 *       read from a SECOND CONNECTION taken straight from the pool rather than through the writing
 *       session. It owns the atomicity proof in the form ruling four prescribes: that the three flushed
 *       writes are INVISIBLE to another session until the commit, which is the single assertion
 *       distinguishing one transaction from three and the one that makes the plan's rejection of a
 *       compensating-reversal design checkable rather than asserted; that a refusal at EACH of the three
 *       positions leaves nothing in either schema, the first two positions having had no case before;
 *       that no partial posting state is observable in either direction; and the ORDER itself, proved by
 *       making two positions refusable at once so that the refusal which arises reports which write was
 *       reached first. It owns the ACCOUNT half of the accumulation obligation across all three arms of
 *       the sign partition -- the category-balance half is the entry above's -- including the two arms no
 *       shipped row reaches, and it owns the interest control break's THIRD state change, the reset of
 *       both cycle accumulators at {@code app/cbl/CBACT04C.cbl:353-354} that a balance-only assertion
 *       misses. It owns the whole of the version ruling: that the column advances at all, that a lost
 *       race is propagated rather than re-read behind the caller, and the composite in which a race lost
 *       at the SECOND write rolls back the first and prevents the third. Two of its cases are
 *       CONSTRUCTED because the reference supplies no vector -- a zero amount appears in no fixture and
 *       in none of the three hundred shipped feed rows, and no posting fixture is negative although
 *       fifty of those rows are -- and one records a MEASURED divergence rather than a predicted
 *       behaviour: the reference rewrites the account unconditionally at
 *       {@code app/cbl/CBTRN02C.cbl:554} while the provider issues no statement when no mapped column
 *       changed, so a zero-amount posting leaves the version untouched. That difference is confined to a
 *       column with no baseline counterpart and is unobservable in the migrated data, the record the
 *       reference rewrote being byte-identical to the one already there.
 *       Trade-offs: its commit case overlaps the entry above's commit case, and the overlap is retained
 *       rather than removed. The two are not the same assertion -- that one reads the commit through the
 *       writer's own cleared context, where this one reads it from a session that never participated --
 *       and deleting either would leave one of the two readings unowned. The residual cost is that a
 *       reader meets the posting commit in two files; it is recorded here so the second encounter reads
 *       as a boundary rather than as a duplicated proof.</li>
 *   <li>{@code DisclosureGroupRepositoryIT} across 10 cases -- the interest-rate lookup, which is the one
 *       table in this package on which this module holds no write privilege at all, the plan scoping the
 *       batch role's cross-schema writes to {@code ledger} and {@code account} only. It owns the
 *       COMPILE-TIME reading of that read-only contract in the form ruling four prescribes, reading the
 *       interface's method set back and asserting the two wider bases are not assignable, because the
 *       harness creates no role and the superuser connection makes a privilege failure unobservable; and
 *       it states the limit of that reading rather than overclaiming it -- the repository cannot write,
 *       which is not the same as the privilege being correctly scoped in a provisioned environment. It
 *       owns the key's COMPONENT ORDER in both directions: that a key in the record's physical order of
 *       group, type then category resolves, and that the transposition
 *       {@code app/cbl/CBACT04C.cbl:210-212} invites does not -- proved not only by a miss but by two
 *       seeded pairs sharing their digits in swapped positions at different rates, which is the form the
 *       defect takes when it goes green. It owns the padded-and-bare equivalence of the default group
 *       identifier, the substitution that replaces the group component ALONE with the type and category
 *       carried over, the per-pair reachability of all seventeen seeded pairs as a CROSS-SERVICE
 *       precondition, and the rate's exact scale. One case is the only one in this package to stage a row
 *       through the persistence context, the interface having no write method to stage through, and it
 *       rolls that row back rather than committing it so the class never produces the state its central
 *       claim says it does not.
 *       Trade-offs: it asserts ROWS and not BYTES, the fifty-byte record and its filler being a mapper
 *       concern, and it asserts NO arithmetic and no rounding -- the formula, the zero-rate gate and the
 *       fallback as control flow belong to the tier-one service cases, and job-level golden parity to the
 *       tier-two interest job. This table supplies the rate; the computation over it is owned
 *       elsewhere.</li>
 * </ul>
 *
 * <p>Alternatives Considered: no saga, no two-phase commit and no compensating reversal for the posting
 * unit of work. That design was evaluated and rejected at the migration plan's section 0.4.1.3, because
 * each of its forms replaces one atomic commit with a sequence of committed steps and therefore makes
 * intermediate states observable -- a posted transaction beside an unposted balance -- which the baseline
 * does not have and which the golden masters would correctly report as a parity failure. The scoped
 * cross-schema grant keeps the commit atomic, and this package is where that claim is checked against an
 * engine rather than asserted in prose.</p>
 *
 * <p>Trade-offs: each proof above has exactly ONE owner, and the cost accepted for that is some
 * cross-referencing between the ten classes -- the cross-reference table, for instance, is written by
 * three of them, and only one of them owns its access paths, and the daily-transaction interface is read
 * by more than one, its resolve-and-order question and its driving-loop properties owned separately. The alternative
 * was to let two classes each
 * assert a property partially, which is how a proof drifts: one side is updated, the other still passes,
 * and the two now describe different behaviour with nothing able to report the divergence.</p>
 *
 * <h2>The line this tier does not cross into tier one or tier two</h2>
 *
 * <p>Alternatives Considered: restating here the rules the other two tiers own, so that a reader of this
 * package would not have to open theirs. Rejected, because three files asserting the same rule three ways
 * is three places it can be relaxed while the other two still read as intact. Tier one owns and is not
 * restated here: reject-reason precedence and the verbatim reject literals, the composition of the
 * 80-byte trailer, the two inclusive boundaries, the projected balance being taken from the cycle
 * accumulators rather than the stored balance, the create-versus-update arms of the category balance, and
 * ALL interest arithmetic. Tier two owns and is not restated here: the graded return-code tier and its
 * two summary lines, the inversion of a baseline step gate into an orchestrator predicate, the
 * business-date job parameter, and job-level parity against the reference goldens.</p>
 *
 * <p>Assumptions: tier two and this tier make different assertions about the same transaction boundary,
 * and the difference in kind is the reason this package exists. Tier two can prove that the posting job
 * DECLARES one boundary, which it establishes by construction from the job definition. Only this tier can
 * prove that the DATABASE observably committed all three writes or none of them, because that is a
 * statement about what an engine made durable and it is verified from outside the transaction. The second
 * assertion is evidence about the database; the first is evidence about the code.</p>
 *
 * <p>Assumptions: this package therefore depends on both sibling test packages, and neither dependency is
 * stylistic. It depends on tier two because the boundary being proved is opened by the posting job and by
 * none of the rule services -- the validation, category-balance and interest services declare no
 * transaction of their own and run inside whatever boundary their caller opened -- and on tier one
 * because the cases here drive those same services INSIDE that boundary.</p>
 *
 * <p>Assumptions: one member of the production service package is transactional, and a case here that
 * overlooked it would assert the opposite of the truth. The step-ledger writer declares
 * {@code REQUIRES_NEW} on each of its three transitions, which suspends the caller's transaction and
 * opens an independent one, precisely so that a FAILED row survives the rollback of the step it records
 * -- before it was a separate bean, the failure write joined the step's transaction and was rolled back
 * by the very exception it was recording, so a hard-failed run left no FAILED row at all. The
 * consequence for this package is concrete: a ledger row is NOT part of the three-write unit of work, so
 * a rollback case must expect the three writes to vanish and the ledger row to remain.</p>
 *
 * <h2>Boundaries</h2>
 *
 * <p>Assumptions: these tests write ROWS into the four schemas this module does not own, and they may not
 * create, alter or seed STRUCTURE in any of them. That DDL belongs to the owning services' own
 * migrations, and a table created from here would be a second definition of a table another context
 * already defines, diverging silently the moment either side changed.</p>
 *
 * <p>Assumptions: money is asserted as {@code BigDecimal} at scale two throughout, matching the
 * {@code NUMERIC} scale the columns declare, and never as a primitive floating-point value or through a
 * {@code double} comparison. That is the migration plan's transformation rule T3, and
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java} fails
 * the build on a violation, so the discipline here is consistency with an existing gate rather than an
 * independent policy this package invented.</p>
 *
 * <p>Assumptions: no case in this package asserts rounding behaviour on the interest path. The baseline
 * divide TRUNCATES rather than rounding half up, because no {@code ROUNDED} phrase appears in
 * {@code app/cbl/CBACT04C.cbl} or anywhere else under {@code app/cbl}, and tier one's interest test owns
 * that arithmetic. An assertion here that assumed half-up would contradict the baseline while appearing
 * to defend it.</p>
 *
 * <p>Assumptions: every business date, timestamp and amount in this package is a literal. No case reads a
 * wall clock, because a value compared against the current time passes for a reason unrelated to the code
 * under test and fails only when two reads straddle a boundary -- a failure that reproduces at the hour
 * it was introduced and at no other. This mirrors the reference suite's own discipline of injecting the
 * business date as a parameter rather than reading it.</p>
 *
 * <p>Assumptions: no queue emulator and no cloud emulator belongs in this package. The test profile
 * deliberately leaves the messaging properties unset, and the module's queue configuration is gated on
 * one of them with no match-if-missing fallback, so the gate stays closed and no case here needs a
 * transport to be stood up.</p>
 *
 * <p>Assumptions: this package's file set is closed at the ten integration classes and this charter. A
 * fixture builder, a shared constant holder or a container base class introduced here would reintroduce
 * the shared state ruling two rejects, and no subdirectory belongs here. The test-tree charter at
 * {@code com.carddemo.batch} owns the boundaries this whole tree does not cross -- that no controller
 * tier is possible in a module with no HTTP surface, that the layering rules have exactly one owner in
 * the shared kernel and no local configuration file, and that build output is ignored by the single
 * repository-root ignore file -- and each of those applies here without being restated.</p>
 *
 * <p>Every path under {@code app/} cited by any member of this package is reference material, read as the
 * specification and never modified. The oracle suite under {@code tests/} and the runners under
 * {@code scripts/} are read the same way and are never modified or re-pinned: they are the parity oracle,
 * and everything this package adds is additive to them.</p>
 *
 * <h2>Three proofs the reference cannot supply a vector for</h2>
 *
 * <p>Assumptions: three of the properties above have no fixture in the reference data, so the owning
 * class constructs them in code. This is recorded so nobody searches for a vector that does not exist and
 * concludes the proof is missing. A zero transaction amount appears in no fixture and in none of the
 * three hundred rows of the shipped daily-transaction data, yet the guard at
 * {@code app/cbl/CBTRN02C.cbl:548} is an inclusive comparison, so zero accumulates into the CREDIT bucket
 * and only a constructed row can show it. A negative amount appears in no posting fixture, although fifty
 * of those three hundred rows are negative, and the debit arm adds the already-negative amount AS IS
 * rather than negating it, so the stored accumulator is itself negative -- which is true of BOTH
 * accumulators the posting paragraph maintains, so the account cycle bucket and the category balance each
 * carry a constructed negative case in the class that owns that table rather than sharing one. And the
 * shipped cross-reference data is fifty rows with fifty distinct account identifiers, so the
 * one-account-to-many-cards case that the non-unique index permits does not occur in it at all --
 * which is precisely why a bare single-result
 * finder would pass against everything that ships and fail only once data the contract admits arrives.</p>
 *
 * <h2>Parameters, return values and exceptions: declared inapplicable</h2>
 *
 * <p>A package declaration accepts no parameter, yields no value and raises nothing, so this charter
 * carries no parameter, return or exception at-clause, and no authorship, availability or revision
 * at-clause either.</p>
 *
 * <p>Assumptions: the inapplicability is stated rather than left silent because the user-specified
 * Explainability rule lists a docstring that omits parameters, return values or purpose among its
 * forbidden patterns, and a reader has to be able to tell a declared inapplicability from an oversight.
 * Fabricating the at-clauses instead would be worse than useless: Javadoc has no parameter, return or
 * exception concept for a package, and the shared ruleset audits at-clause bodies for emptiness, so an
 * invented clause would either be discarded or reported. That exemption is this compilation unit's alone
 * and does not travel to the six classes beside it, where a test class, a test method and a private
 * helper alike do have parameters, return values and thrown types to document.</p>
 *
 * <h2>Why this charter exists, and the form it takes</h2>
 *
 * <p>Assumptions: the project's Explainability rule requires a docstring on every module entry point, and
 * in Java the entry point of a package is its package declaration, which only {@code package-info.java}
 * can carry -- so this file is load-bearing rather than decorative. Two Checkstyle modules enforce that
 * independently and neither is redundant: {@code JavadocPackage} inspects the file set and requires this
 * file to exist in any directory holding an audited source file, while {@code MissingJavadocPackage}
 * inspects the parsed tree and requires it to carry Javadoc. A charter reduced to a bare package
 * statement satisfies the first and fails the second, which is why prose is the deliverable and this
 * file's mere existence is not. Nothing in {@code config/checkstyle/suppressions.xml} exempts this tree,
 * and that ruleset configures no comment-based or annotation-based suppression filter at all, so the
 * obligation cannot be waived from inside a source file.</p>
 *
 * <p>Assumptions: this compilation unit holds one statement, so the rationale the rule's inline-comment
 * half asks for has no adjacent executable line to sit beside and is carried inside this block under the
 * four canonical labels -- the only placement a package makes available. The written convention every
 * block here follows is {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never restated.</p>
 */
package com.carddemo.batch.repository;
