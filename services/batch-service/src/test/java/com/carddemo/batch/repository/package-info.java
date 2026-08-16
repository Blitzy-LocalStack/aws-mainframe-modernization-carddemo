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
 * <p>This directory holds six files and a seventh is prohibited: this charter, together with
 * {@code BatchRunRepositoryIT}, {@code CrossSchemaFeedRepositoryIT}, {@code CardXrefRepositoryIT},
 * {@code DailyTransactionRepositoryIT} and {@code PostingUnitOfWorkIT}.</p>
 *
 * <pre>
 * this directory: 6 java files = 5 tests + 1 charter
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
 * <p>Refactoring Rationale: that marker line is what turns the sentence above from a claim into a checked
 * one. {@code PackageCharterInventoryTest} in the shared kernel parses the line and re-counts this
 * directory on every build, so an inventory that falls behind fails the build instead of ageing quietly.
 * The same test re-counts each member's declared case total against the annotations in the file it names,
 * and it holds the number of charters carrying a marker to a floor, so deleting the line to silence a
 * failure is a visible act in review rather than a quiet one.</p>
 *
 * <p>Refactoring Rationale: this section previously described eight entities across FOUR schemas, and the
 * directory had moved past it in both figures. The count is ten entities across five, because
 * {@code CBEXPORT} reads five masters and the export job could reach only three of them, so the customer
 * and card phases published empty on every run; the customer and card interfaces and their two entities
 * were the whole of that shortfall, and the {@code card} schema arrived with them. The production charter
 * beside this one records the same correction from its own side and is the authority for the interface
 * roster; it is cited rather than restated, so the two cannot disagree about a number.</p>
 *
 * <p>Alternatives Considered: one integration class per production interface, which would make this a
 * roster of ten rather than four and would let each class be named for the interface it covers. Rejected
 * on what the classes would then contain. The property worth proving about most of the read-only feeds is
 * identical in each case -- that a mapping resolves against a table this module does not own and walks it
 * in a declared order -- so ten classes would be several near-copies of one another, and a change to the
 * harness would have to be chased through all of them. The property worth proving about posting is not a
 * property of any single interface at all: it spans four of them in one commit, so no per-interface class
 * could hold it without either splitting the proof or duplicating it. The five classes below are
 * therefore partitioned by PROPERTY rather than by interface, and between them they reach all ten.</p>
 *
 * <p>Assumptions: neither of the two classes named for a single interface is a departure from that
 * partition but an application of it. Each is named for an interface because it happens to reach only
 * one, yet what each owns is a PROPERTY none of the others holds -- the CHUNKED walk's behaviour across
 * more than one statement in the one case, the determinacy of a by-account read over a NON-UNIQUE index
 * in the other -- and both are different questions from whether a mapping resolves and walks in order.
 * Two classes therefore touch the daily-transaction interface, and the split between them is by
 * QUESTION rather than by member, now that the interface declares a single read: one bounded walk of the
 * feed belongs to {@code CrossSchemaFeedRepositoryIT} because it is one of that class's six
 * near-identical feed walks and asks only whether the mapping resolves and orders, while the driving
 * loop's own properties -- chunk geometry, resumption, and positioning under a concurrent delete --
 * belong to {@code DailyTransactionRepositoryIT} because they have no counterpart among them. Two classes
 * likewise touch the cross-reference, and that split is by KIND of statement: the catalog fact and a
 * reachability read stay with the feed walks, while the keyed access paths and their multiplicity
 * ruling belong to {@code CardXrefRepositoryIT}. A sixth test would need to name a property this list
 * does not already own.</p>
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
 * <p>Assumptions: the plan's uniform per-service shape spells the name {@code *RepositoryIT}, and four
 * of the five members here follow that spelling while {@code PostingUnitOfWorkIT} does not. The departure is
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
 * <p>Assumptions: each of the five carries {@code @ActiveProfiles("test")}, which is the whole of how
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
 * registration and the schema prerequisite once for all five. Rejected: a container held in a base class
 * is shared mutable state, so rows one class inserts are rows another reads, and the failure then names
 * whichever class happened to run second. That hazard is concrete rather than hypothetical here, because
 * three of the five arrange rows in {@code account.card_xref} and each empties it for itself. Each class
 * starts its own container and owns its own schema state, which is what lets any one of the five be run
 * alone and still mean something. Trade-offs: the
 * accepted cost is five container starts and five copies of the container declaration, paid every time
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
 *       member, the interface now declaring a single read. It owns the 430-byte reject composition, which the
 *       baseline builds at
 *       {@code app/cbl/CBTRN02C.cbl:447} as a whole-group move of the daily record exactly as read, so
 *       the stored row retains that record's own trailing filler span and its own processing timestamp
 *       rather than acquiring fresh ones. On the cross-reference it owns the CATALOG fact -- that the
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
 *   <li>{@code PostingUnitOfWorkIT} across 6 cases -- the atomicity proof, the account and
 *       transaction writes that only it performs, and the daily-subset finder's ordering and window
 *       against a real engine. That last case is here rather than beside the job that calls the finder
 *       because a stubbed repository can model an ORDER BY but cannot evaluate one, and because the
 *       window's upper bound is STRICT on a {@code TIMESTAMP(6)} -- a boundary only a real engine
 *       decides. It seeds rows one microsecond outside each edge, which is the resolution the column
 *       is declared at, so a comparison that admitted the following midnight is caught. It owns the commit-and-rollback pair in the baseline
 *       order established at L440 to L442, the account cycle accumulators that
 *       {@code app/cbl/CBTRN02C.cbl:545-551} maintains alongside the running balance, and the version
 *       ruling: a step that loses the optimistic-lock race FAILS THE STEP so the orchestrator retries
 *       it, and never re-reads and writes again behind the caller's back. It also owns the ascending walk
 *       of the category balances in key order, which is what makes the interest control break at
 *       {@code app/cbl/CBACT04C.cbl:194} correct -- that program declares the cluster
 *       {@code ORGANIZATION IS INDEXED} with {@code ACCESS MODE IS SEQUENTIAL} at L29 to L30, and the
 *       key it walks is proved arithmetically by the {@code KEYS(17 0)} at
 *       {@code app/jcl/TCATBALF.jcl:40} against the eleven, two and four digit fields at
 *       {@code app/cpy/CVTRA01Y.cpy:6-8}, fixing the order as account, then type, then category.</li>
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
 * cross-referencing between the five classes -- the cross-reference table, for instance, is written by
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
 * <p>Assumptions: this package's file set is closed at the five integration classes and this charter. A
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
 * rather than negating it, so the stored accumulator is itself negative. And the shipped cross-reference
 * data is fifty rows with fifty distinct account identifiers, so the one-account-to-many-cards case that
 * the non-unique index permits does not occur in it at all -- which is precisely why a bare single-result
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
 * and does not travel to the five classes beside it, where a test class, a test method and a private
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
