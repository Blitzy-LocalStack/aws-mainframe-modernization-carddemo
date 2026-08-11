/**
 * Unit tests that transcribe this bounded context's business rules from its eight baseline COBOL
 * programs.
 *
 * <p><b>Purpose.</b> This package holds the business-rule transcription tests of the pending credit-card
 * authorization context. Each class takes one migrated service, drives it with plain objects and mocked
 * collaborators, and asserts that what it computes is what the reference program computes: the outcome
 * selected, the reason reported, the row written, the key it is written under, the boundary a value has to
 * fall inside, and the input that is refused rather than coerced. None of them starts a Spring context,
 * opens a database or reaches a queue, which is the whole reason they can assert a branch table
 * exhaustively. This file is the contract those classes are written against. It records what the package
 * owns, what it inherits and must therefore not re-prove, which class answers for which documented
 * divergence, and how far the parity claim actually reaches. It declares no type and holds no import, so
 * nothing here executes; its entire effect is on what the classes beside it assert.</p>
 *
 * <h2>The closed inventory: nineteen files here, eighteen of them tests</h2>
 *
 * <p>The parent charter at {@code com.carddemo.authorization} deliberately fixes no leaf-class count and
 * names no leaf class, making each package's own charter the authority for its own inventory. This is that
 * authority for this package. The reference program named beside each class is the normative
 * specification for it, and the extension case is reproduced exactly as it stands on disk: five of the
 * eight programs carry a lowercase {@code .cbl} extension and three an uppercase {@code .CBL}, so a
 * citation that normalises either half points at nothing.</p>
 *
 * <pre>
 * this directory: 19 java files = 18 tests + 1 charter
 * </pre>
 *
 * <p>Alternatives Considered: stating the inventory in prose alone, which is what this charter did before
 * and is what the marker line above replaces. A closed-set claim that nothing checks is false the moment
 * another test class is authored without an entry below, and nothing reveals it. Refactoring Rationale:
 * this sentence once counted to a specific class -- "an eleventh" -- which made the prose itself go stale
 * every time the directory grew, so it now states the rule instead of the current total. The total lives
 * on the marker line, where a machine keeps it honest. That line and every
 * class name under it are compared against this directory on every build by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/PackageCharterInventoryTest.java},
 * which asserts the two figures against the file count and asserts that each enumerated name is a file
 * beside this one. The closure is therefore mechanical rather than aspirational, and the amendment rule
 * below is what a reader does about a failure instead of guessing.</p>
 *
 * <ul>
 *   <li>{@code package-info.java} -- this charter. It governs and asserts nothing.</li>
 *   <li>{@code PendingAuthSummaryServiceTest} exercises {@code PendingAuthSummaryService} against
 *       {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl}.</li>
 *   <li>{@code PendingAuthDetailServiceTest} exercises {@code PendingAuthDetailService} against
 *       {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl}.</li>
 *   <li>{@code PendingAuthDetailProjectionTest} exercises that same service for the screen projection
 *       half of that same program: the ten-entry response-reason display table declared at its lines 57
 *       to 73, the twelve-character money edit mask at its line 52 held apart from the fourteen-character
 *       wire mask at line 66 of {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}, the
 *       empty-state literals its lines 53 and 54 declare, and the direction of the forward step at its
 *       lines 493 to 519. Refactoring Rationale: this is a second class over one service rather than
 *       more cases in the first, because the first asserts that ONE query is issued and verifies no
 *       further interaction with the repository, so cases needing a different stubbing of that same
 *       repository cannot share its fixture without weakening that verification.</li>
 *   <li>{@code FraudMarkingServiceTest} exercises {@code FraudMarkingService} against
 *       {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} and
 *       {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl}.</li>
 *   <li>{@code AuthorizationRequestListenerTest} exercises {@code AuthorizationRequestListener} against
 *       {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}.</li>
 *   <li>{@code AuthorizationDecisionServiceTest} exercises {@code AuthorizationDecisionService} against
 *       that same program's {@code 6000-MAKE-DECISION} paragraph at lines 657 to 734. The decision is a
 *       collaborator of the listener rather than a service role of its own.</li>
 *   <li>{@code RestAccountContextClientTest} exercises {@code RestAccountContextClient}, the one adapter
 *       of the outbound port that stands in for that program's three reads against records this context
 *       does not own: {@code 5100-READ-XREF-RECORD}, {@code 5200-READ-ACCT-RECORD} and
 *       {@code 5300-READ-CUST-RECORD}.</li>
 *   <li>{@code OutboxPublisherTest} exercises {@code OutboxPublisher}, which has no COBOL antecedent as a
 *       component: the baseline has no outbox at all, and the reply the publisher replaces is sent inline
 *       at {@code COPAUA0C.cbl} line 461.</li>
 *   <li>{@code OutboxMetadataConfidentialityTest} exercises that same publisher for one separable
 *       property, that no value taken off the wire reaches a log line or an exception message.</li>
 *   <li>{@code PurgeJobTest} exercises {@code PurgeJob} against
 *       {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} and its driver
 *       {@code app/app-authorization-ims-db2-mq/jcl/CBPAUP0J.jcl}.</li>
 *   <li>{@code AuthorizationExtractRoundTripTest} exercises {@code LoadService} and
 *       {@code UnloadService} together, against
 *       {@code app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL},
 *       {@code app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL} and
 *       {@code app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL}.</li>
 *   <li>{@code LoadServiceTest} exercises {@code LoadService} alone against
 *       {@code app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL}, for the three conditions that
 *       program passes over in silence and for what the migrated service does at each instead. Its
 *       subject is the SERVICE BOUNDARY -- what is raised, what is written, what is logged and what is
 *       rolled back -- where the entry above owns the FILE FORMAT and proves it survives a circuit.
 *       Refactoring Rationale: this is a second class over a subject that already has one, and the
 *       division is by subject rather than by tier, as it is for
 *       {@code PendingAuthDetailProjectionTest} above. Three of its claims cannot be made inside a
 *       round-trip fixture at all: a rollback needs an observable transaction manager rather than the
 *       lenient stub a happy circuit needs, a diagnostic needs a captured appender, and a refusal held
 *       against a tolerated duplicate needs both outcomes inside one case so that a change satisfying
 *       each of them separately still fails.</li>
 *   <li>{@code UnloadServiceTest} exercises {@code UnloadService} for the RULINGS a round trip cannot
 *       reach, against {@code app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL} and
 *       {@code app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL}: that the prefixed form is the default
 *       and the sequential form an explicit opt-in, that the two forms emit child records of two hundred
 *       and six and two hundred bytes and so can never silently converge, that the parent prefix is
 *       written packed rather than as text, that a root carrying no account identifier is passed over
 *       explicitly rather than in silence, that a page offering no key to resume from ends the walk, and
 *       that the export declares a read-only unit of work and reaches no operation that writes.
 *       Refactoring Rationale: this is a second class over a subject the round-trip class already covers,
 *       and the division is by QUESTION rather than by tier. A circuit asserts that a format survives
 *       being written and read back, which is silent on which form was written and on what happens to a
 *       row the walk cannot attribute -- and the two cases about that row have to present rows the
 *       circuit's own fixtures do not contain, so they cannot share its fixture without changing what the
 *       circuit proves.</li>
 *   <li>{@code AuthorizationDecisionUnitOfWorkRepositoryIT} exercises
 *       {@code AuthorizationRequestListener} against a real engine, for the one property its unit test
 *       cannot reach: that the summary, the authorization and the reply row of one decision commit and
 *       roll back together. It also covers the reference's asymmetry at
 *       {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 461 and 463, where an unresolved
 *       card is answered and recorded nowhere.</li>
 *   <li>{@code OutboxPublisherLifecycleRepositoryIT} exercises {@code OutboxPublisher} against a real
 *       engine and a scripted queue client, for the claim-send-transition seam: that the claim has
 *       COMMITTED before the reply is handed to the queue, and that one pass advances the attempt counter
 *       once however many times the transport is retried inside it.</li>
 *   <li>{@code PurgeWindowRollbackRepositoryIT} exercises {@code PurgeJob} against a real engine, for the
 *       checkpoint semantic of {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} lines 358 to
 *       364: that one window's deletes and its summary reduction share a fate, and that a failure in a
 *       later window leaves an earlier one committed.</li>
 *   <li>{@code FraudMarkingTransactionRepositoryIT} exercises {@code FraudMarkingService} against a real
 *       engine, for the atomicity of its two writes -- the fraud row through a native upsert and the
 *       authorization's own two fraud members through the persistence context -- against
 *       {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl}.</li>
 *   <li>{@code AuthorizationDiagnosticDisclosureTest} exercises {@code LoadService} and
 *       {@code PurgeJob} for one property neither of their behavioural tests can observe: that no log
 *       template and no throwable message in either class names an account or customer identifier, as
 *       {@code docs/architecture/observability.md} directs. Refactoring Rationale: it reads the two
 *       SOURCES rather than capturing a logger, because a captured logger proves the property only for
 *       the paths a test happens to drive, and a template on an undriven path is exactly what let those
 *       identifiers stand. Its subject is therefore source text rather than a service role, which is
 *       why it is a class of its own rather than cases inside the round-trip and purge tests.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: four of the last five entries are a SECOND class over a subject that
 * already has one, and the division is by TIER rather than by subject. Each of those four subjects makes a claim about what
 * a database RETAINS after a failure, and a mocked collaborator can only record what was asked of it, so
 * the claims were documented and unproven until these classes were added. Their names end in
 * {@code RepositoryIT} because the module's Failsafe configuration includes exactly that suffix; the
 * suffix names the tier they run in and not the subject they exercise, so reading it as a claim about a
 * repository is a misreading the naming convention makes easy.</p>
 *
 * <p>Alternatives Considered: the load and the unload were planned as two classes named after their two
 * services, and they are one class named after what it proves. Asserting a load and an unload separately
 * measures each against a layout table the other would have to restate, so the two copies can each agree
 * with the table and still disagree with one another; a round trip through both is the only form in which
 * a width, an offset or a sign convention that moved on one side fails. The cost accepted is that the
 * class name matches neither service name, which is why both services are named against it here.</p>
 *
 * <p>Assumptions: the amendment rule is what the marker line obliges. A class belongs here only if its
 * subject is one of the ten production service types the roster names, and a class whose subject is not
 * requires this charter to be amended -- an entry added and both figures raised -- in the same commit that
 * adds it. The failure a reader will actually meet is the marker check, and the fix is never to lower a
 * figure without adding the entry beside it, because the figures exist to make the entry compulsory. The
 * production charter one tree away carries the complementary statement and the two do not disagree: it
 * names eight service ROLES and five supporting types, of which the roster above takes ten as test
 * subjects, because a port interface and the seam that closes intake are exercised through the classes
 * that use them rather than on their own.</p>
 *
 * <h2>Names decide what runs</h2>
 *
 * <p>Two plugins split this module's test tree by class name alone and neither reads an include list
 * authored here. Every class in this package ends {@code Test}, which is what the standard Surefire
 * binding selects during the test phase, reporting into {@code target/surefire-reports}. That suffix is
 * the correct one for all of them because each uses mocked collaborators rather than a container. An
 * integration test in this module ends {@code RepositoryIT}, runs under Failsafe and reports into
 * {@code target/failsafe-reports}. Neither report directory may be relocated, because continuous
 * integration collects exactly those two paths and moving either would leave the build green while the
 * workflow published nothing. A name ending {@code IT}, {@code ITCase} or {@code IntegrationTest} is
 * collected by neither plugin here and simply never executes.</p>
 *
 * <h2>The eight divergences this package owns</h2>
 *
 * <p>Each entry below is a place where this context's behaviour departs from the baseline's. Each is
 * registered in {@code docs/architecture/cobol-to-service-traceability.md}, which is the register of
 * record, and each carries, at the test that asserts it, the rationale label user-specified Rule 1
 * (Explainability) reserves for replaced code -- which is what a divergence assertion is about, even
 * though the test itself is newly authored.</p>
 *
 * <ul>
 *   <li><b>D-5</b> -- the reply left before the data landed, and becomes an outbox row committed with the
 *       decision. Owner: {@code OutboxPublisherTest}. Baseline: the get computes no-syncpoint options at
 *       {@code COPAUA0C.cbl} line 389, the put does the same at line 753, the reply is sent at line 461
 *       BEFORE the conditional write at lines 463 to 465, and the single commit is at line 335.</li>
 *   <li><b>D-6</b> -- two writes split across an {@code EXEC CICS LINK} become one local
 *       {@code @Transactional} unit, and the two-phase commit is eliminated rather than emulated because
 *       every table involved now lives in one schema. Owner: {@code FraudMarkingServiceTest}. Baseline:
 *       {@code COPAUS1C.cbl} links at lines 248 to 252, replaces the segment at lines 525 to 528, and
 *       commits once at line 558.</li>
 *   <li><b>D-C</b> -- a genuine root-lookup failure silently skips the child insert, and here it fails
 *       loudly. Owner: {@code LoadServiceTest}, whose {@code RootPositioningFailure} cases assert the
 *       three properties the corrected behaviour has to carry -- that the refusal is RAISED, that its
 *       unit of work is ROLLED BACK, and that it is VISIBLE in the job log -- and assert it beside a
 *       tolerated duplicate so the correction cannot be over-applied.
 *       {@code AuthorizationExtractRoundTripTest} also asserts this divergence, from the other side:
 *       that the refusal carries the unresolved account in typed form and ends the pass at the first
 *       such record. Assumptions: naming one owner and one corroborator is deliberate rather than a
 *       divided claim. The registration in {@code docs/architecture/cobol-to-service-traceability.md}
 *       is single, and a divergence asserted in two places needs one entry here that says which class a
 *       reader should open first. Baseline: {@code PAUDBLOD.CBL} tests the root status at line 305,
 *       inserts at line 309, opens a second test at line 310 and closes both with the single
 *       {@code END-IF.} at line 314, so the failure branch is reachable only from inside the success
 *       branch.</li>
 *   <li><b>D-D</b> -- a receive failure sets neither exit flag, so the poll cycle continues over a stale
 *       buffer; here a receive failure is terminal for that cycle. Owner:
 *       {@code AuthorizationRequestListenerTest}. Baseline: {@code COPAUA0C.cbl} lines 418 to 431 mark the
 *       error critical and log it at line 429 without setting a flag or rolling anything back.</li>
 *   <li><b>D-E</b> -- an integer subtraction of Julian day numbers is wrong across a year boundary, and
 *       here the difference is computed from real dates. Owner: {@code PurgeJobTest}. Baseline:
 *       {@code CBPAUP0C.cbl} line 280 recovers the stored date by complementing it against 99999 and line
 *       282 subtracts that from the current day number.</li>
 *   <li><b>D-F</b> -- one counter is tested twice so the second condition is unreachable, and here both
 *       counters are guarded. Owner: {@code PurgeJobTest} for the guard, with accumulation of the two
 *       counters owned by {@code AuthorizationRequestListenerTest}, which is where they are incremented.
 *       Baseline: {@code CBPAUP0C.cbl} line 156.</li>
 *   <li><b>D-G</b> -- decrements are computed and never persisted, and here they are persisted in the same
 *       transaction as the child delete, so the adjustment and the deletion cannot diverge. Owner:
 *       {@code PurgeJobTest}.</li>
 *   <li><b>D-H</b> -- a fourteen-character declared money field is parsed through a thirteen-character
 *       receiver, and here an over-long token fails loudly instead of losing its final digit. Owner:
 *       {@code AuthorizationRequestListenerTest}.</li>
 * </ul>
 *
 * <p>Trade-offs: the parent charter enumerates NINE divergences and this package owns eight of them. The
 * ninth is the one logical amount with two textual forms, a zero-suppressed rendering on the wire against
 * a zero-filled one in the database, and it belongs to the codec and mapper boundary rather than here
 * because neither form is chosen by a service. A test in this package may REFERENCE it; it may not claim
 * it, label itself with it, or register it. The cost accepted is that a reader counting divergence owners
 * across the two charters finds eight here and nine there, which is why the difference is stated rather
 * than left to be met later as an apparent inconsistency.</p>
 *
 * <p>Assumptions: not one of the eight authorises a change to the baseline. Everything under {@code app}
 * is reference material and the behavioural oracle, so discovering a defect there creates an obligation to
 * DOCUMENT it and never an obligation to fix or delete it. The house has already written that precedent
 * down. {@code tests/README.md} section 1.1 at lines 53 to 60 records an unfixable record-key defect in
 * two immutable baseline programs, states that no compiler flag can fix it and that the minimal-change
 * principle forbids editing it, and gives its reason at lines 50 to 51: so that no runnable claim hides a
 * blocked feature, which it calls a financial-enterprise auditability requirement. Every registration
 * here follows that existing precedent rather than inventing one.</p>
 *
 * <h2>Division of labour, which runs the opposite way from the usual expectation</h2>
 *
 * <p>In this context the SERVICE package, not the repository package, owns every {@code @Transactional}
 * boundary -- the repository interfaces declare none -- and it also owns discarding the size-plus-one
 * look-ahead row a keyset query returns, deriving the has-more flag from whether that row arrived,
 * encoding and reading the opaque cursor token, and assembling
 * {@code com.carddemo.common.web.PageResponse}. Two consequences are the ones most often got backwards. A
 * repository test asserts that the extra row IS returned and NOT stripped, because stripping is not the
 * repository's job, while a service test asserts the discard and the flag derived from it. And because a
 * cursor is opaque by construction it may only be round-tripped, never asserted as an encoding: asserting
 * its bytes would promote an implementation detail into a contract that fails the moment the keying
 * changes.</p>
 *
 * <p>What the neighbours own, so that no test here asserts in the wrong place. The {@code ..mapper}
 * package owns primary-account-number masking and card-verification-value suppression.
 * {@code ..config.SecurityConfig} owns the {@code carddemo-admin} route guard for fraud marking, so a
 * service test documents the required authority and does not reproduce the HTTP security rule.
 * {@code ..config.SqsConfig} owns the {@code carddemo.messaging.*} transport binding, so a test here
 * consumes those bound properties and never redeclares queue topology or transport defaults. And
 * {@code ..config.DataSourceConfig} owns the single non-distributed {@code DataSource}, the pinned
 * {@code search_path} -- which is why a native query in this module names its tables WITHOUT a schema
 * qualifier -- and the default {@code JpaTransactionManager}.</p>
 *
 * <p>Assumptions: two absences are deliberate and no test may assume otherwise. There is no
 * {@code BatchConfig} and no Spring Batch job repository in this module, because its own POM declares no
 * batch starter of any kind; chunk-oriented batch is scoped to the batch context, whose module declares
 * it. {@code PurgeJob}, {@code LoadService} and {@code UnloadService} are parameterised service entry
 * points the batch orchestrator invokes, DESPITE the name {@code PurgeJob}, so a test written here against
 * a job repository, a chunk listener or a restart contract would be asserting a bean that cannot
 * exist.</p>
 *
 * <p>Assumptions: no service method in this package declares a lock mode, and that is the precise form of
 * the claim rather than a claim that the context takes no lock. Row protection exists and is owned on the
 * repository boundary: {@code PendingAuthSummaryRepository} declares its atomic accumulation statements under
 * a pessimistic write lock and the listener calls it. A test here therefore asserts that the protected
 * read is the one taken, and never restates the lock mode, because a duplicated lock policy is one that
 * can drift from the declaration that actually runs.</p>
 *
 * <h2>What is inherited, and must not be re-proved here</h2>
 *
 * <p>The shared kernel owns the codec internals. This package CONSUMES
 * {@code com.carddemo.common.codec.CsvAuthCodec} and {@code com.carddemo.common.codec.PackedDecimalCodec}
 * and asserts at its own service boundary, where the question is what this context DOES with a decoded
 * value rather than whether the decoding was correct. The exhaustive round-trip proofs belong to
 * {@code services/common-lib}'s own tests: the eighteen-field request and six-field reply wire contract,
 * the two segment closure sums, the packed width table and the sign-overpunch table. Re-proving any of
 * them here would leave two suites asserting one contract, so a change to the contract would fail twice
 * and a reader could not tell which assertion was the authority.</p>
 *
 * <p>Assumptions: the prohibition on binary floating point is INHERITED and this package must not
 * re-declare it. The shared kernel's layering rules scope that rule to the whole analysed root rather than
 * to the money package, and the {@code architecture-rules} Surefire execution declared in
 * {@code services/pom.xml} re-runs those rules against THIS module's own compiled classes, so a
 * {@code float} or {@code double} declared in a service, transfer object, entity or mapper here is a
 * broken build rather than a review comment. What an import graph cannot see is BEHAVIOUR, and that is
 * what remains this package's own to assert: that an amount is a {@code java.math.BigDecimal} carrying a
 * scale of two, that rounding is {@code RoundingMode.HALF_UP}, and that an amount crosses a boundary as a
 * STRING rather than as a bare JSON number. That is AAP Rule T3, and each of the three is a property of a
 * value or a payload rather than of a declared member type, which is precisely why no import graph
 * reaches them.</p>
 *
 * <p>Assumptions: the zoned sign-overpunch convention does NOT apply anywhere in this context, which is
 * worth stating because it applies almost everywhere else in the migration. The numerics reaching these
 * segments are packed decimal, a two-byte binary counter -- {@code PA-APPROVED-AUTH-CNT} and
 * {@code PA-DECLINED-AUTH-CNT}, both {@code PIC S9(04) COMP} -- and one plain unsigned display field,
 * {@code PA-CUST-ID PIC 9(09)}. A test written here against overpunch behaviour would be asserting a
 * decoding regime these segments never use. Packed width follows {@code ceil((digits + 1) / 2)}, so the
 * ladder runs three digits to two bytes, five to three, nine to five, eleven to six and twelve to SEVEN:
 * a {@code PIC S9(10)V99 COMP-3} amount is seven bytes and not six. That is independently provable rather
 * than merely asserted, because {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} declares two
 * such amounts and its field widths sum to exactly 200 bytes at seven and to 198 at six.</p>
 *
 * <h2>Two data-language interface styles that must never be unified</h2>
 *
 * <p>The eight programs do not share one data-language interface, and a test that assumed they did would
 * assert the wrong status field. Exactly three of them -- {@code PAUDBLOD.CBL}, {@code PAUDBUNL.CBL} and
 * {@code DBUNLDGS.CBL} -- {@code COPY IMSFUNCS}, call the batch language interface directly and evaluate
 * {@code PAUT-PCB-STATUS}. Exactly four -- {@code COPAUS0C.cbl}, {@code COPAUS1C.cbl},
 * {@code COPAUA0C.cbl} and {@code CBPAUP0C.cbl} -- use {@code EXEC DLI} and evaluate {@code DIBSTAT}.
 * So {@code AuthorizationExtractRoundTripTest} asserts the program-communication-block status shape and
 * {@code PurgeJobTest} asserts the interface-block status shape, and neither shape is expressed in the
 * other's vocabulary.</p>
 *
 * <p>Assumptions: the eighth program reaches neither, and the asymmetry is stated here because four plus
 * three is seven. {@code COPAUS2C.cbl} is the only one of the eight that issues {@code EXEC SQL} and the
 * only one with no data-language call at all: it evaluates {@code SQLCODE}, and the duplicate-key value it
 * tests for is what {@code FraudMarkingServiceTest} asserts as a conflict rather than as a status.</p>
 *
 * <h2>Coverage, stated plainly</h2>
 *
 * <p>There is NO golden master for any path in this package and no test here may imply otherwise. Three
 * independent structural reasons make that so, and each was verified rather than assumed. First,
 * {@code tests/README.md} at lines 83 to 85 records that the online programs cannot run end to end without
 * a transaction runtime, which the runner does not have, so only their extractable field-validation logic
 * is unit-tested -- and four of this context's eight programs are of that kind. Second, that harness
 * compiles from {@code app/cbl/} alone, as its build section states at line 267, and its own scope note
 * covers ten of the twelve batch programs there, so the extension trees are never built and no program of
 * this context is ever compiled by it. Third, {@code COPAUA0C.cbl} issues eight {@code COPY CMQ*}
 * statements, at lines 149, 152, 155, 158, 161, 164, 167 and 170, and no file whose name begins with those
 * three characters exists anywhere in the repository; the queue-interface copybooks are simply absent, so
 * that program could not be compiled by that harness even if the extension trees were in its scope.</p>
 *
 * <p>Assumptions: parity here therefore rests on the copybook and table-definition contracts and on the
 * transcribed logic, which is a real basis and a narrower one than a recorded output. The difference is to
 * be stated rather than blurred. A class here may claim that an expected value comes from a cited
 * paragraph and line range; it may not claim that its output was compared against a run of the
 * program.</p>
 *
 * <p>Assumptions: the Explainability obligation for the recorded byte images this package reads is
 * DISCHARGED jointly and is not this package's alone. The fixtures directory carries its own README,
 * written per file rather than per family, and one contract test loads every resource there and asserts
 * its width, its fields, its final record and its failure path. What remains obligatory for a class here
 * is narrower and cannot be delegated: it must state, in its own {@code Assumptions:} block, the layout
 * and length invariant IT relies on, because the README cannot say which invariant a particular test
 * depends upon.</p>
 *
 * <p>Assumptions: every fail-loudly assertion in this package rests on an established house stance rather
 * than on a preference. {@code tests/fixtures/README.md} at lines 144 to 151 records that the existing
 * readers and loaders REJECT any physical row whose length is not exactly the record length, that they do
 * not pad short rows, do not truncate long ones and do not silently drop blank lines, and that the only
 * input treated as empty is a genuinely zero-byte dataset -- because, in its own words at lines 150 to
 * 151, a malformed monetary record must never be silently coerced into a well-formed-looking one. D-C,
 * D-D and D-H are that same stance applied to this context, and D-UNLOAD-SKIP-REPORTED is its REPORTING
 * half rather than its refusing half: an export cannot coerce a row it declines to write, so what that
 * difference adds is a count and a log line where the reference programs leave neither.</p>
 *
 * <h2>What a test here may reach for</h2>
 *
 * <p>The module's POM makes available, all versionless because the aggregator manages them:
 * {@code spring-boot-starter-test}, which aggregates the test engine, the mocking library, the fluent
 * assertions and the server-less request harness; the Testcontainers PostgreSQL, LocalStack and JUnit
 * Jupiter modules; and {@code com.tngtech.archunit:archunit-junit5}. The one intra-project artifact is
 * {@code com.carddemo:common-lib}, declared twice -- once as a compile dependency and once as its test
 * artifact, which is what lets the shared layering rules run inside this module. No Maven dependency on a
 * sibling service module exists or may be added. Java is 21 and Spring Boot is 4.1.0.</p>
 *
 * <p>Assumptions: four things are absent by decision and none may be reached for. There is no Lombok,
 * because generated accessors cannot carry the Javadoc user-specified Rule 1 (Explainability) requires,
 * and that is the one place in this build where a documentation rule settles a dependency choice. There is
 * no MapStruct, because its current release is a beta and copybook-to-transfer-object mapping is not
 * mechanical: it drops filler, masks, suppresses, encrypts and renames, and each of those needs a
 * justification at the mapping site that a generated mapper cannot hold. There is no cache, stream or
 * broker dependency. And the documentation gate is never re-declared or re-bound here, nor is a
 * suppressions location set on it: the plugin is declared once in {@code services/pom.xml} and the rule
 * set wires its own filter, so a second declaration would give one behaviour two owners.</p>
 *
 * <p>Alternatives Considered: a retry or resilience library, which is not adopted and is mechanically
 * forbidden -- the shared layering rules fail the build on a dependency upon either of the two library
 * roots they name. Framework-native retry is what the migration relies on instead, where the attribute is
 * {@code maxRetries} rather than {@code maxAttempts}, total attempts are one plus its value, and the
 * enabler is {@code @EnableResilientMethods} rather than {@code @EnableRetry}. No class in this context
 * annotates a retry at all today. If one ever does, a test must describe it as a faithful realisation of
 * DECLARED-BUT-UNIMPLEMENTED intent and never as preserved behaviour: seven of the eight programs declare
 * {@code 88 RETRY-CONDITION VALUE 'BA', 'FH', 'TE'.} and a repository-wide search returns exactly those
 * seven hits, every one of them the declaration itself, so the condition is never once referenced. The
 * only retry the tree actually implements is the terminate-then-reschedule at {@code COPAUS0C.cbl} lines
 * 1007 to 1016, taken when scheduling reports the already-scheduled status.</p>
 *
 * <h2>Two rule namespaces, kept apart</h2>
 *
 * <p>Assumptions: user-specified Rule 1 (Explainability) and the migration plan's transformation rules
 * {@code T1} through {@code T10} are different namespaces, which is why a citation here always writes them
 * in full. The plan rules this package leans on are AAP Rule T1, that the copybook is normative; AAP Rule
 * T2, that one former {@code COPY} becomes one import from the package that owns the contract; AAP Rule
 * T3, that money never leaves fixed point and crosses the wire as a string; AAP Rule T5, that a syncpoint
 * becomes a transaction boundary and a browse becomes one keyset query; AAP Rule T8, that user-visible
 * strings are carried across verbatim; and AAP Rule T9, that structure changes while behaviour does not.
 * AAP Rule T10 is the plan's own echo of user-specified Rule 1. Writing "Rule 1" where Rule T1 is meant is
 * the specific confusion this paragraph exists to prevent.</p>
 *
 * <p>Assumptions: the {@code tests} directory at the repository root is the COBOL three-layer
 * functional-parity oracle suite and is not this tree. It is read as evidence, never modified and never
 * re-pinned, and everything authored here is strictly additive to it. Its graded condition-code rubric,
 * under which a warning-level aggregate is the current green state, belongs to it alone. The gate on this
 * side is BINARY: a Java build here either passes or fails, and no test, POM or workflow in this module may
 * skip the documentation gate, lower its violation threshold, tolerate a non-zero status or continue on
 * error. The reason sits in that rubric itself, which assigns the warning level to a layer that collected
 * no tests -- so a warn-tolerant gate would report success over a module in which nothing ran, and that is
 * indistinguishable from a module in which everything passed.</p>
 *
 * <h2>Documentation contract</h2>
 *
 * <p>This charter is required by user-specified Rule 1 (Explainability), whose docstring clause covers
 * every module entry point; a Java package declaration is the reasonable reading of that phrase, and a
 * {@code package-info.java} is the only construct that can carry Javadoc for one. The requirement is
 * mechanical as well as textual. {@code JavadocPackage} audits any directory contributing a Java source
 * the gate processes and {@code MissingJavadocPackage} requires the file, once present, to CARRY Javadoc,
 * and {@code services/pom.xml} binds both to the {@code validate} phase with test-source auditing on and
 * failure on violation, so this file's absence stops the build before compilation. Nothing can waive that
 * from inside a Java file: the rule set wires no in-code suppression filter of any kind, its file-based
 * filter fails closed, and the two entries in {@code config/checkstyle/suppressions.xml} cover generated
 * sources and fixture RESOURCES rather than {@code src/test/java/}, which is where the transcribed rules
 * are actually asserted.</p>
 *
 * <p>Assumptions: of the four content elements user-specified Rule 1 (Explainability) enumerates, only
 * Purpose applies to a package declaration -- it accepts no parameters, yields no value and raises
 * nothing -- so parameters, return values and exceptions are INAPPLICABLE here rather than omitted, and no
 * at-clause is fabricated to stand in for one. The distinction is drawn explicitly because a docstring
 * that silently omits them is one of the patterns that rule forbids, and a reader has to be able to tell a
 * declared inapplicability from an oversight.</p>
 *
 * <p>Trade-offs: every labelled justification above sits inside this Javadoc block rather than beside a
 * statement, which departs from the letter of the adjacency requirement and is nevertheless the only
 * placement this file admits, since a package declaration has no statements for a comment to sit adjacent
 * to. The requirement is satisfied vacuously rather than waived. The cost accepted is that each entry sits
 * further from the behaviour it governs than an inline comment would, and the compensation is that each one
 * names the file and the line that settle it.</p>
 *
 * <p>Assumptions: one of the four labels is deliberately absent throughout, the one the rule reserves for
 * replaced code, and the omission is a scoping decision rather than a gap. That label marks a statement
 * about replaced BEHAVIOUR, which is what the eight divergence assertions are and what this file only
 * allocates. Where an earlier wording of this charter was itself superseded -- the prose inventory, which
 * the marker line replaced -- the reason is given at the entry that supersedes it, under a label from the
 * same list, because the rule asks for at least one of the four and not for a particular one. Keeping the
 * reserved label out of a file that asserts nothing also keeps a search for it pointing only at the tests
 * that carry a real behavioural claim.</p>
 */
package com.carddemo.authorization.service;
