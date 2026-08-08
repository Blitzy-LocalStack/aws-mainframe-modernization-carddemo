/**
 * Charter for the test sources of the pending credit-card authorization bounded context.
 *
 * <p><strong>Purpose.</strong> This package declaration is the root of this module's test tree, and this
 * file is the contract the packages beneath it are written against. It records what these tests own, what
 * they inherit and must therefore not re-prove, the class-name suffixes that decide whether a test is
 * executed at all, the measurements most easily conflated in this context, and the parity claim this module
 * may honestly make. It declares no type and holds no import, so nothing here runs; its entire effect is on
 * what the tests beneath it assert. Where this charter contradicts a sibling document, the contradiction is
 * stated below with the evidence that settles it, because a reader acting on superseded wording is the
 * specific failure this file exists to prevent.
 *
 * <p><strong>The subtree, as verified on disk.</strong> Eight test packages sit beneath this one and each
 * carries its own {@code package-info.java}, for exactly the reason this file does, so this tree holds nine
 * charters in total and a missing one fails the gate before compilation:
 * <ul>
 *   <li>{@code .api} - the HTTP boundary only: that a request reaches the published route, that binding and
 *       validation produce the per-field refusal, and that the outcome selects the declared status.</li>
 *   <li>{@code .config} - the wiring that cannot be observed from behaviour: the authority matrix installed
 *       on the security chain, the profile and property closure, the schema naming the migration depends
 *       on, and whether the published contract is actually served.</li>
 *   <li>{@code .contract} - the character-level constraints of the message wire, held apart from the
 *       mapping tests because a width and a delimiter are properties of the frame rather than of any one
 *       mapper.</li>
 *   <li>{@code .domain} - entity behaviour: key rendering, match-status origination, and the reversal and
 *       rehydration paths of the summary segment.</li>
 *   <li>{@code .dto} - the transfer types: their serialisation, the masked primary account number, the
 *       sealed row selector, and the rendering the wire depends upon.</li>
 *   <li>{@code .fixtures} - the tests that read the recorded byte images under this module's test resources
 *       and assert each one's width, its fields, its final record and its failure path.</li>
 *   <li>{@code .mapper} - the anti-corruption boundary: segment conversion, the wire fixtures, the date
 *       pivot, and what a mapper is permitted to expose.</li>
 *   <li>{@code .service} - the business behaviour transcribed from the COBOL, together with the queue
 *       listener, the outbox publisher, the purge job, the extract round trip and the outbound
 *       account-context client. This is where the divergences below are actually asserted.</li>
 * </ul>
 *
 * <p>Assumptions: this charter fixes no leaf-class count and names no leaf class, and both omissions are
 * deliberate. Each package's own charter is the authority for its own inventory, and the production charter
 * one tree away records what happens otherwise: it carried an exact type count for the shared kernel that
 * was already wrong by the time it was read, and it dropped the number rather than correcting it, because
 * the fact that sentence existed to establish did not depend on it. The same reasoning applies here. What
 * this charter fixes instead is the set of package names and the responsibility of each, because those are
 * what a new test has to be placed against and they do not drift with the census.
 *
 * <p>Refactoring Rationale: the planned shape of this tree was four packages - {@code .api},
 * {@code .service}, {@code .mapper} and {@code .repository} - holding twenty-three files behind five
 * charters, with {@code .dto}, {@code .domain} and {@code .config} named as packages that must not exist.
 * Every part of that is superseded by what is on disk, and the correction is recorded here rather than left
 * for eight agents to rediscover separately. There are eight packages, not four. There is no
 * {@code .repository} test package at all. {@code .dto}, {@code .domain} and {@code .config} all exist and
 * are load-bearing. {@code .contract} and {@code .fixtures} exist and were not planned. The migration plan
 * states the governing precedence for exactly this case: the repository is authoritative, and where the two
 * differ the repository wins and the difference is recorded so that no downstream agent re-derives it.
 * Publishing the planned map instead would have sent a reader looking for a package that is not there and
 * told three real packages to delete themselves. What survives unchanged from the planned shape is the
 * naming contract below, which is real and is enforced by the build.
 *
 * <p><strong>Naming contract: obey the suffix or the class does not run.</strong> Two plugins split this
 * tree by class name alone, and neither reads an include list authored here. A unit test name must end
 * {@code Test}, which is what the standard lifecycle binding of the Surefire plugin selects during the test
 * phase, reporting into {@code target/surefire-reports}. An integration test name must end
 * {@code RepositoryIT}, which already matches the Failsafe plugin's own default include pattern, so
 * {@code services/pom.xml} declares that plugin's coordinate and deliberately configures no includes at
 * all; Failsafe runs after packaging and asserts in the verify phase, reporting into
 * {@code target/failsafe-reports}. Both report directories are the defaults and {@code services/pom.xml}
 * records that they must not be relocated, because the continuous integration workflow collects exactly
 * those two paths and moving either would make the build green while the workflow published nothing.
 * A name ending {@code IT}, {@code ITCase} or {@code IntegrationTest} is therefore not a stylistic
 * variation: {@code IntegrationTest} is collected by neither plugin and simply never executes.
 *
 * <p>Alternatives Considered: a JUnit suite descriptor, or any other explicit enumeration of the classes to
 * run, was evaluated for this tree and rejected. It would stand up a second selection mechanism alongside
 * the suffix convention, and the two would then have to agree; when they did not, the descriptor would win
 * silently, so a correctly named and correctly authored test class could be excluded from every run while
 * the build stayed green and the report file simply did not mention it. A missing suffix fails loudly by
 * comparison, because the class is visibly absent from a report that lists everything else. The same
 * reasoning is why no {@code junit-platform.properties} is added here.
 *
 * <p><strong>Non-duplication: the shared kernel owns the codec internals.</strong> The codecs this module
 * consumes are proved in {@code services/common-lib}, and re-proving them here would mean two suites
 * asserting one contract, so that a change to the contract fails twice and a reader cannot tell which
 * assertion is the authority. Specifically: the exhaustive eighteen-field request and six-field reply wire
 * contract belongs to that module's {@code CsvAuthCodec} tests; the closure proofs for the hundred-byte
 * summary segment and the two-hundred-byte detail segment, the seven-byte width of a packed
 * {@code S9(10)V99} and the eight-byte composite key belong to its packed-decimal tests; every
 * {@code CopybookLayout} assertion belongs to its fixed-width tests; and the sign-overpunch table belongs
 * to its zoned-decimal tests. This module therefore CONSUMES {@code CsvAuthCodec} and the packed-decimal
 * codec and asserts at its own service boundary, where the question is what this context does with a
 * decoded value rather than whether the decoding was correct.
 *
 * <p>Assumptions: the zoned sign-overpunch table does not apply anywhere in this module, which is worth
 * stating because it applies almost everywhere else in the migration. The numerics reaching this context
 * are packed decimal, a two-byte binary counter, and one plain unsigned display field, the customer
 * identifier declared {@code PIC 9(09)}. A test written here against overpunch behaviour would be asserting
 * a decoding regime these segments never use.
 *
 * <p><strong>What this module owns exclusively.</strong> These are the assertions no sibling module makes,
 * so if they are absent here they are absent everywhere: the positional split of the five-slot
 * {@code OCCURS} account-status array into five discrete columns; the composition of the authorization
 * timestamp from its separate date and time parts; the deliberate absence of trimming on the merchant name;
 * the one field-spelling change this context makes, from the baseline's misspelled merchant category code
 * at {@code cpy/CCPAURQY.cpy} to {@code merchantCategoryCode}; primary-account-number masking; and
 * divergences D-H and D-I below.
 *
 * <p><strong>What is inherited, and it reaches further than a first reading suggests.</strong> The layering
 * rules are authored once, in {@code services/common-lib}'s test tree, and re-run against this module's own
 * compiled classes by the {@code architecture-rules} Surefire execution declared in
 * {@code services/pom.xml}, which scans that artifact rather than copying it. The class must never be
 * copied, relocated, renamed or split; Checkstyle's import-control module is deliberately absent from the
 * rule set precisely so that this boundary keeps a single owner. Five rules and three guards arrive that
 * way: no domain package may depend on a cloud software development kit, web or servlet type; no package
 * root may depend on a domain class owned by a different root, across the nine fixed roots; no production
 * type may declare a binary floating-point member in a field, a parameter or a return type; every security
 * chain must install the shared refusal renderers; and no class may depend on a retry or resilience
 * library. Selecting nothing is itself a build failure, because a profile in that same POM adds a
 * fail-if-no-tests flag wherever a {@code src/main/java} directory exists.
 *
 * <p>Refactoring Rationale: this paragraph previously stated that the binary floating-point rule was scoped
 * to the shared money package alone and that this context's money path was consequently not covered by
 * inheritance, so that these tests had to assert the prohibition themselves. That is false, and it is the
 * most consequential correction in this charter. The rule selects classes residing in the analysed root
 * identifier, which is {@code com.carddemo} followed by the subpackage wildcard, so
 * {@code com.carddemo.authorization} is inside its subject set; the money package identifier the earlier
 * reading mistook for the scope is used only as the anchor for that rule's own emptiness check. The rule's
 * own documentation states the same thing in the opposite direction, recording that under the narrower
 * scope a service transfer object, entity, mapper or batch job could declare an inexact amount and still
 * pass. Acting on the superseded wording would have cost twice: these tests would have re-proved an
 * inherited rule, and a reader would have believed the module unprotected while it was protected.
 *
 * <p>Assumptions: inheritance covers the DECLARATION of an inexact type and not the ARITHMETIC done with an
 * exact one, so the money assertions that remain this module's own are the ones about behaviour. That an
 * amount carries a scale of two, that rounding is half-up, and that an amount crosses the boundary as a
 * string rather than as a bare number, are properties of values and payloads rather than of declared member
 * types, and no import graph can see them. They are asserted here.
 *
 * <p><strong>Four assertions that must be demonstrably green, with the measurements kept apart.</strong>
 * Each message has TWO different lengths and one of them has a third; conflating them is the easiest error
 * available in this tree, so each is labelled at every mention.
 * <ul>
 *   <li><strong>The wire contract.</strong> For the request, the sum of the declared field widths at
 *       {@code cpy/CCPAURQY.cpy} L19 to L36 is 153, while the comma-delimited payload is 170. For the
 *       reply, the sum of the declared field widths at {@code cpy/CCPAURLY.cpy} L19 to L24 is 57, the
 *       payload is 63, and the baseline TRANSMITS 64. The payload is 63 rather than 62 because the frame
 *       built at {@code cbl/COPAUA0C.cbl} L722 to L731 emits six comma literals, the sixth of them
 *       TRAILING the money field at L727. The transmitted length is 64 because the pointer used at L730 is
 *       declared {@code PIC S9(4) VALUE 1} at L46, so it is one-based and ends one past the content it
 *       counted; that value is moved to the put buffer length at L756 before the queue call at L758.
 *       {@code docs/architecture/messaging-contracts.md} reconciles the same 62 against the same 63
 *       explicitly, so the two agree and neither should be read as contradicting the other.</li>
 *   <li><strong>The outbox invariant.</strong> A committed decision leaves exactly one publishable outbox
 *       row; a rolled-back decision leaves none; and a publisher failing before publication loses nothing,
 *       because the row survives the failure. This is what guards D-5. The database write it guards is
 *       CONDITIONAL, which is the part most easily missed: {@code cbl/COPAUA0C.cbl} decides at L459, sends
 *       the reply at L461, and only then tests the cross-reference outcome at L463 before writing at L464
 *       and closing at L465, so a card-not-found decline sends a reply with no row written at all. That
 *       path needs its own assertion rather than falling out of the others.</li>
 *   <li><strong>Stale-message handling.</strong> A message whose expiry instant has passed is dropped AND
 *       logged, both halves asserted, because a silent drop and a handled drop are indistinguishable from
 *       the outside. The baseline sets an expiry of 50 at {@code cbl/COPAUA0C.cbl} L750, and that field is
 *       denominated in tenths of a second, so the interval is 5.0 seconds and not 50. The target queue
 *       service offers no per-message expiry, so the interval is carried as an attribute the consumer
 *       honours; the short retention chosen for the reply queue is justified by the non-persistent
 *       delivery the baseline selects one line earlier, at L749. The reasoning is recorded in
 *       {@code docs/adr/ADR-004-messaging.md}.</li>
 *   <li><strong>The fraud access path.</strong> In this schema it is TWO database objects and both must be
 *       asserted, against the catalogue rather than against the migration text. The shipped definition is
 *       four lines at {@code ddl/XAUTHFRD.ddl}: a unique index on the fraud table over the card number
 *       ascending and the authorization timestamp descending. In the baseline that single mixed-direction
 *       unique index also enforces the primary key declared on the last line of {@code ddl/AUTHFRDS.ddl},
 *       L28; here a primary-key backing index is always wholly ascending and cannot carry the descending
 *       path, so the key constraint and the descending index are separate objects. The image-copy attribute
 *       on the fourth line of the shipped definition has no counterpart here and no index option may be
 *       invented to stand in for it. Cite the shipped definition and never the extension README's rendering
 *       of it, which carries a storage clause that is not on disk.</li>
 * </ul>
 *
 * <p><strong>The nine divergences.</strong> Each is a place where this context's behaviour departs from the
 * baseline's, each is registered in {@code docs/architecture/cobol-to-service-traceability.md}, and each
 * needs a {@code Refactoring Rationale:} at the test that asserts it. That label is the correct one here
 * even though these tests are newly authored: user-specified Rule 1 defines it as applying when existing
 * code is replaced and requires it to state what was wrong with the old approach, and a divergence test is
 * precisely an assertion about a replaced behaviour. The shared kernel's ruling that a net-new test carries
 * no such label holds only where the test replaces nothing, which is not the case for any of these.
 * <ul>
 *   <li><strong>D-5</strong> - the reply left before the data landed. The reply is now an outbox row written
 *       inside the decision transaction and drained afterwards. Baseline: the get computes no-syncpoint
 *       options at {@code cbl/COPAUA0C.cbl} L389, the put does the same at L753 and L754, the write is
 *       conditional at L463 to L465, and the single commit at L335 happens after the put.</li>
 *   <li><strong>D-6</strong> - two resource managers under one distributed transaction become one local
 *       transaction. Baseline: {@code cbl/COPAUS1C.cbl} reaches {@code cbl/COPAUS2C.cbl} by link at L248 to
 *       L252, replaces a segment at L525 to L528, and commits once at L558. All the tables that work
 *       touches now live in one schema, so the two-phase commit is eliminated rather than emulated.</li>
 *   <li><strong>D-C</strong> - a root-lookup failure in {@code cbl/PAUDBLOD.CBL} falls through silently.
 *       Here it fails loudly. The one status the baseline legitimately tolerates is still tolerated.</li>
 *   <li><strong>D-D</strong> - a receive failure at {@code cbl/COPAUA0C.cbl} L418 to L431 sets neither exit
 *       flag, so the poll cycle continues as though nothing happened. Here it is terminal for that
 *       cycle.</li>
 *   <li><strong>D-E</strong> - {@code cbl/CBPAUP0C.cbl} L280 and L282 subtract Julian day numbers as plain
 *       integers. Here the difference is computed from real dates, and a case crossing a year boundary is
 *       part of the assertion rather than an afterthought.</li>
 *   <li><strong>D-F</strong> - {@code cbl/CBPAUP0C.cbl} L156 tests one counter twice, so the second
 *       condition is unreachable. Here both counters are guarded.</li>
 *   <li><strong>D-G</strong> - {@code cbl/CBPAUP0C.cbl} computes four decrements and never persists them.
 *       Here they are persisted in the same transaction as the child delete, so the adjustment and the
 *       deletion cannot diverge.</li>
 *   <li><strong>D-H</strong> - a fourteen-character money layout is parsed through a thirteen-character
 *       receiver. Here an over-long token fails loudly instead of losing its final digit.</li>
 *   <li><strong>D-I</strong> - one logical amount has two textual forms: the wire carries the
 *       zero-SUPPRESSED edited rendering and the database carries the zero-FILLED one. Both are asserted,
 *       because asserting either alone would let the other drift.</li>
 * </ul>
 *
 * <p>Assumptions: none of the nine authorises a change to the baseline. The COBOL and everything beside it
 * under {@code app} is reference material and the behavioural oracle; discovering a defect there creates an
 * obligation to document it and never an obligation to fix or delete it. A test may assert what this
 * context does instead; it may not assert that the baseline has been altered, because it has not been.
 *
 * <p><strong>Five corrections, each re-verified against the source before being written here.</strong>
 * <ul>
 *   <li><strong>C1</strong> - the purge job's parameter record is 17 bytes with FOUR filler fields, not 16
 *       with three. {@code cbl/CBPAUP0C.cbl} declares it at L98: a two-digit expiry-day count at L99, a
 *       filler at L100, a five-character checkpoint frequency at L101, a filler at L102, a five-character
 *       checkpoint display frequency at L103, a filler at L104, a one-character debug flag at L105 with its
 *       two condition names at L106 and L107, and a FOURTH filler at L108. The shipped sixteen-character
 *       control card therefore lands in a seventeen-byte record with the trailing filler absorbing the
 *       slack. The conclusion drawn from it is unaffected and correct: L196 defaults the expiry-day count
 *       only when it is NOT numeric, whereas L201 and L204 default the two checkpoint parameters on spaces,
 *       zero or low values, so the card at {@code jcl/CBPAUP0J.jcl} L37 supplies a numeric value, the
 *       default at L199 never fires, the working expiry count is zero, and the job purges everything.</li>
 *   <li><strong>C2</strong> - {@code cbl/PAUDBLOD.CBL} L332 to L336 DOES abend, at L335, so the seam once
 *       claimed there does not exist and no test may assert it. The three real seams in that program are:
 *       the single {@code END-IF.} at L314 closing both the L305 and the L310 conditions, which is D-C; the
 *       numeric test at L275, closed at L282 with no alternative branch, which silently DROPS a
 *       non-numeric child record with neither message nor abend; and L286 to L288, where an unexpected
 *       child-file status is only displayed, with no abend and no exit from the read loop.</li>
 *   <li><strong>C3</strong> - there are TWO segment-replace calls, not one: {@code cbl/COPAUA0C.cbl} L825
 *       and {@code cbl/COPAUS1C.cbl} L525. Only the second is in the fraud path that D-6 describes. The
 *       first is one half of an upsert: L824 tests whether the summary segment was found, replaces at L825
 *       to L828 if it was, and inserts at L830 to L833 if it was not. There are likewise two inserts in
 *       that program, the summary one at L830 and the DETAIL one at L913 to L919, and the second is a
 *       two-level qualified path insert that names the parent segment with a key predicate, then the child
 *       segment, then the child source area and its length - which independently confirms the child record
 *       length at the call site rather than only in the copybook.</li>
 *   <li><strong>C4</strong> - {@code cbl/COPAUA0C.cbl} has THREE silent-continuation seams, not one. Each
 *       marks the error critical, each logs, and none abends, sets a flag or rolls anything back: the
 *       receive failure at L418 to L431, which is D-D; the summary write at L835 to L847, logging at L846;
 *       and the detail insert at L920 to L932, logging at L931. This sharpens D-5 rather than merely adding
 *       to it. Because the reply is sent at L461 BEFORE the conditional write at L463 to L465, and because
 *       a failed detail insert is only logged, the baseline can reply that a transaction was approved and
 *       then commit successfully at L335 with no detail row at all - on an ordinary run, not merely inside
 *       a crash window. All three must fail loudly here, and each needs its own assertion.</li>
 *   <li><strong>C5</strong> - D-H depends on the HUNDREDTHS digit rather than on the amount's magnitude. A
 *       conforming money token is always fourteen characters, so a move into a thirteen-character receiver
 *       always discards the fourteenth; the VALUE is only corrupted when that discarded character is
 *       non-zero. Of the amounts available in this module's fixtures, only the all-nines variant satisfies
 *       that, so a D-H test must use the second record of {@code auth-request-amount-variants.csv}. Written
 *       against the canonical request fixture the test passes while proving nothing, which is the worst
 *       available outcome because it looks like coverage.</li>
 * </ul>
 *
 * <p><strong>Two reconciliations with sibling documents, both settled by reading the source.</strong>
 * First, the response code and the response reason are different fields and the apparent disagreement
 * between documents dissolves once they are kept apart. {@code cbl/COPAUA0C.cbl} sets the response CODE to
 * one of exactly two values, at L688 for a decline and L693 for an approval, and it sets the response
 * REASON to one of eight: the default at L698, plus the seven the evaluation at L700 to L717 emits. Of
 * those seven, the first is emitted for THREE distinct not-found conditions collapsed onto one code at L701
 * to L704, so a test must not read that code as meaning the card alone was missing. The approval path also
 * copies the transaction amount to both the reply amount and the working amount at L694 and L695, while the
 * decline path zeroes the reply amount at L689. This module's own declined-reason fixture carries exactly
 * seven records, one per reason, each with the decline response code, so the fixture corroborates both
 * counts independently of the source. Second, the detail screen's display table is a superset of what the
 * decision program can produce, by exactly two entries that program never emits. State the asymmetry; it is
 * a property of the baseline and is not to be reconciled away in either direction.
 *
 * <p>Refactoring Rationale: this paragraph previously recorded that the four reply fixtures carried the
 * wrong money content, having had the request-side picture applied to them. That is no longer true and
 * repeating it would be actively harmful, because it would invite an agent to overwrite a correct fixture.
 * The reply amount is placed on the wire at {@code cbl/COPAUA0C.cbl} L727 from the numeric-EDITED field
 * declared at L66, whose leading-zero suppression renders blanks; the persisted form comes from a different
 * move at L900 and is zero-FILLED. On disk the reply fixtures already carry the zero-suppressed rendering
 * that L66 produces - a positive two-hundred-and-fifty amount as eight blanks followed by its digits, a
 * zero amount as ten blanks followed by its digits - so they agree with the source and with D-I. The
 * declared widths are unaffected either way: the field sum stays 57, the payload 63 and the transmitted
 * length 64.
 *
 * <p><strong>The fixtures, and where their Explainability obligation actually sits.</strong> The recorded
 * byte images under this module's test resources cannot carry a docstring, and
 * {@code tests/fixtures/README.md} section 9.1 at L695 to L698 establishes that a directory of static
 * fixtures must therefore carry a README of its own as a mandatory Explainability artifact. That obligation
 * is DISCHARGED here rather than outstanding, and by three artifacts jointly: the fixtures directory does
 * carry its own README, written per file rather than per family; one contract test named in that README
 * loads every resource and asserts its width, its fields, its final record and its failure path; and each
 * consuming test states, in its own {@code Assumptions:} block, the layout and length invariant it relies
 * on. A test that consumes a fixture without stating that invariant is incomplete even though the README
 * exists, because the README cannot say which invariant THAT test depends upon.
 *
 * <p>Refactoring Rationale: this paragraph previously stated that the fixtures directory held no README and
 * that these test classes were consequently the sole carrier of the obligation. Both halves are false: the
 * README exists, and the count of fixture files it inventories is larger than the count once recorded here.
 * The correction matters in a specific way rather than a general one. Believing the README absent, an agent
 * would either write a second one - splitting one inventory across two files that must then be kept in
 * agreement, with nothing to reveal it when they drift - or would treat the per-test statement of layout as
 * discretionary because it appeared to be the only record. Neither follows once the README is known to be
 * there. No count of fixture files is fixed in this charter, for the same reason no leaf-class count is.
 *
 * <p>Assumptions: three fixture conventions here diverge deliberately from those under {@code tests}, and
 * each is load-bearing. A recorded byte image carries NO trailing newline, so that its length equals its
 * content length and an array comparison is meaningful; its comma-delimited sibling does carry one. Nobody
 * may make a byte image tidy by adding a newline. Two of the images are documented decode-only records
 * whose re-encoding is deliberately not byte-identical, so neither may ever be used as an encoding oracle.
 * The two segment strides are not multiples of one another, which makes non-divisibility itself a detector:
 * a file read at the wrong stride does not land on a record boundary. And three artifacts share a length of
 * 64 while meaning three different things - the comma-delimited reply plus a line terminator, the
 * transmitted frame plus the one byte its one-based pointer counted past the content, and the declared
 * transmitted length - with the two files differing at their final byte alone, a line feed against a blank.
 * Splitting that frame on its delimiter yields a seventh element that is a single blank, so an emptiness
 * test fails there where a blankness test passes, and that trailing blank must never be promoted into a
 * seventh field.
 *
 * <p><strong>Prerequisites, already discharged.</strong> Nothing in this charter waits on a sibling folder.
 * The two things these tests genuinely need sit two levels away and both exist: the test resources, which
 * supply the test-profile overlay and the fixtures; and the production sources, which supply every type the
 * tests import. The overlay is what makes an integration test possible against a fresh container - it
 * enables the schema migration and permits schema creation while leaving automatic schema generation off,
 * makes the shared defaults import optional so a missing file cannot fail the context, disables transport
 * security and listener auto-startup so no test reaches a network by accident, and publishes this context's
 * messaging settings, among them the poll timeout and the bounded processing limit the listener tests
 * assert against.
 *
 * <p>Assumptions: the production package names the tests import are {@code .api}, {@code .service},
 * {@code .repository}, {@code .domain}, {@code .dto}, {@code .mapper} and {@code .config}, seven in all,
 * and they are NOT the same set as the eight test packages above. The asymmetry is intended in both
 * directions: there is no {@code .repository} test package even though a production one exists, because the
 * single integration test that exercises persistence lives with the fixture tests that supply its rows; and
 * there are {@code .contract} and {@code .fixtures} test packages with no production counterpart, because a
 * wire width and a recorded byte image are subjects of a test rather than of a deployable. No test package
 * is added merely to mirror a production one, and none is deleted merely because no production one exists.
 *
 * <p>Assumptions: this module has no batch job and no test may assume one. Its own POM declares no batch
 * starter and no batch dependency of any kind; chunk-oriented batch is scoped to the batch context, whose
 * module declares it. A test written here against a batch configuration would be asserting a bean that
 * cannot exist, and the purge job it might be mistaken for is an ordinary service in {@code .service}.
 *
 * <p><strong>Division of labour, which runs the opposite way from the usual expectation.</strong> The
 * SERVICE layer, not the repository layer, owns every transaction boundary, discards the extra look-ahead
 * row a paging query returns, mints and reads the opaque paging cursor, and assembles the shared page
 * envelope. The mapper layer owns primary-account-number masking and the suppression of anything that must
 * never leave the context. The security configuration owns the administrative route guard. Two consequences
 * follow and they are the ones most often got backwards: an integration test asserts that the extra row IS
 * returned and NOT stripped, because stripping is not the repository's job, while a service test asserts
 * the discard and the derivation of the has-more flag from it. And because a cursor is opaque by
 * construction, it may only be round-tripped, never asserted as an encoding; asserting its bytes would
 * convert an implementation detail into a contract and would fail the moment the keying changed.
 *
 * <p>Assumptions: a native query in this module names its tables WITHOUT a schema qualifier, because the
 * search path is pinned to this context's schema in configuration. Qualifying a table name in a test query
 * would pass while the pin was correct and would then keep passing if the pin were removed, which makes it
 * the one form of the assertion that cannot detect the failure it appears to guard against.
 *
 * <p><strong>Citation discipline.</strong> Two things make a citation in this tree dead rather than merely
 * imprecise. Extension case is MIXED inside one directory of the reference tree - four of its programs
 * carry a lowercase extension and four an uppercase one, and the copybooks split the same way between the
 * symbolic-map directory and the record directory - so normalising either half breaks the path. And a line
 * number beyond a file's length cites nothing: the eight programs of that tree run to 1032, 604, 244, 1026,
 * 386, 369, 317 and 366 lines respectively, the request and reply copybooks to 36 and 24, the two segment
 * copybooks to 31 and 54, the fraud table and index definitions to 28 and 4, and the resource definition
 * file to 78. Every line cited in this charter was read at the line it is cited at.
 *
 * <p><strong>Coverage, stated honestly.</strong> There is NO golden master for any path in this context,
 * and no test here may imply otherwise. {@code tests/README.md} section 1.1 at L83 to L85 is the direct
 * authority: the online programs cannot run end to end without a transaction runtime, which the runner does
 * not have, so only their extractable field-validation logic is unit-tested. All four online programs in
 * this context are of that kind. The request producer is not supplied by the baseline at all, only a stub
 * under the existing suite's mocks. The existing golden directory holds nothing for this domain and the
 * existing fixture directory holds no file for it either. Parity here therefore rests on the copybook,
 * table-definition and database-definition contracts and on the transcribed logic - which is a real basis
 * and a narrower one than a recorded output, and the difference is to be stated rather than blurred.
 *
 * <p>Assumptions: everything authored here is strictly ADDITIVE. The existing suite is the parity oracle;
 * its tests, helpers, fixtures, golden files, runner scripts and pinned dependency set are reference
 * material, never modified and never re-pinned. Its documented aggregate warning-level result is its GREEN
 * state and is not a regression: it is caused solely by an unfixable record-key defect in two baseline
 * programs outside this context, and nothing in this module either causes or repairs it.
 *
 * <p>Alternatives Considered: tolerating that warning level in a gate on this side was evaluated and
 * rejected outright. The graded rubric the existing suite aggregates is documented at
 * {@code tests/README.md} section 8, whose heading is at L412, whose aggregation sentence is at L415 and
 * whose table is at L417 to L423, and it assigns the warning level to soft rejects - INCLUDING, and this is
 * the decisive entry, a layer that collected no tests. A warn-tolerant gate here would therefore report
 * success over a module in which nothing ran, which is indistinguishable from a module in which everything
 * passed. The gates on this side are consequently BINARY, and {@code services/pom.xml} records the same
 * prohibition in its own words. The purge job's fatal return code is a separate matter: it maps to a
 * non-zero process exit that the batch orchestrator's failure handler catches, which is a runtime signal
 * and not a test verdict.
 *
 * <p><strong>Checkstyle interlocks these tests inherit.</strong> The documentation gate audits this tree
 * because {@code services/pom.xml} sets its test-source flag on, and {@code config/checkstyle/suppressions.xml}
 * carries exactly two entries, one for generated sources and one for fixture resources; that second entry
 * states in its own comment that it does not and must not cover {@code src/test/java/}, because the tests
 * are where the transcribed business rules are actually asserted. Nothing may be bypassed: the rule set
 * configures no in-source suppression filter of any kind, so a violation cannot be waived from within a Java
 * file, and the file-based filter fails closed. What a leaf class must satisfy follows from that: a summary
 * sentence ending in a period on every documented element; a parameter tag with a non-empty description for
 * every parameter and every record component; a return tag wherever a value is returned; at-clauses in the
 * order parameter, return, then thrown; and, because thrown-clause validation is switched on, a named
 * exception on every failure-path method - which for a test means the exception its assertion captures.
 *
 * <p>Assumptions: the obligation to name a thrown exception comes from user-specified Rule 1's own
 * enumeration, whose exceptions element is qualified as applying where applicable, together with the house
 * convention at {@code tests/README.md} L544 to L549 and the thrown-clause validation switch in the rule
 * set. It does NOT come from Rule 1's Validation Gate sentence, which names purpose, parameters and return
 * values and does not repeat exceptions. The distinction is worth keeping straight because the Gate is the
 * sentence a reviewer quotes, and attributing to it something it does not say invites the reply that the
 * requirement was invented.
 *
 * <p>Assumptions: the house convention just cited uses the SHORTER element names, and Rule 1 uses longer
 * ones for two of the four. Where a docstring in this tree names its elements, it uses the rule's forms,
 * because the rule is the requirement and the convention is one implementation of it. The divergence is
 * real and verified on both sides; it is recorded so that nobody reconciles it by weakening the rule.
 *
 * <p>Trade-offs: two configuration properties in that rule set are spelled differently on two adjacent
 * checks - the presence check on methods filters by one name and the completeness check by another - and
 * they are not interchangeable in either direction. Harmonising them does not produce a stricter or a laxer
 * gate; it produces a rule set that fails to LOAD, so the build stops before a single file has been audited
 * and the failure reads as a tooling fault rather than as an edit. The accepted cost is that the two lines
 * look inconsistent and must be left that way.
 *
 * <p>Alternatives Considered: several checks are deliberately absent from that rule set and none may be
 * added, requested, or worked around here. Single-line Javadoc restriction is absent because it would
 * forbid the one-line accessor form Rule 1 explicitly permits, setting the gate against the rule; field
 * documentation is absent because Rule 1 does not scope its requirement to fields; the style and tag-writing
 * checks are absent because they would duplicate a check already configured or invent a fifth element; every
 * purely typographical Javadoc check is absent because a build failing on asterisk alignment teaches readers
 * to skim the gate; and import control is absent so that layering keeps a single owner. Annotation-based
 * exemption is not relaxed, so a test's own annotations buy it nothing.
 *
 * <p>Assumptions: what REQUIRES this file is user-specified Rule 1, and what makes the requirement
 * mechanical is the check configuration - and the two are not the same thing, so the distinction is drawn
 * rather than blurred. Rule 1's scope sentence names a function, a class and a module entry point, and it
 * does NOT say package or directory; a Java package declaration is the reasonable reading of module entry
 * point, and a {@code package-info.java} is the only construct that can carry Javadoc for one. That
 * attribution is stated precisely because it is checkable: claiming the rule names packages outright would
 * be wrong, and a reader who verified it would then have grounds to doubt everything else here. The two
 * package-level checks then divide the work. The one at checker level requires the file to be PRESENT in
 * any package that contributes a processed Java source, which makes this file's presence self-sustaining
 * once it exists but cannot by itself compel it into existence, because a directory holding nothing but
 * subdirectories contributes no such source. The one inside the tree walker requires the file, once
 * present, to CARRY Javadoc, which is the half that is mechanical here. Each of those statements was
 * confirmed by running the gate rather than by reading the module documentation: with this file absent the
 * audit reports no violation, with this file present but stripped to its package statement the audit fails
 * naming the tree-walker check, and with a sibling package's charter removed while that package still held
 * test classes the audit fails naming the checker-level check. Enforcement over this tree also depends on
 * the effective value of the parent POM's test-source flag, an external contract this file depends upon and
 * does not own; it resolves on, and Rule 1 would bind regardless, so this charter would be required even if
 * it resolved off.
 *
 * <p>Assumptions: there is deliberately no charter at the two parent directories of this one, and the
 * mechanism above is exactly why. Neither holds a Java source, so neither has a package declaration for the
 * module-entry-point obligation to attach to, and the checker-level check - narrowed to the Java extension -
 * cannot fire on a directory that contributes no such file. The production charter one tree away records
 * the same boundary for the same reason. It is written down because an absence is invisible: a reader who
 * did not find this note could reasonably read the gap as an oversight and add two files the gate never
 * asked for, each documenting a package that has no entry point to document.
 *
 * <p>Assumptions: the gate keeps a per-module cache of the files it has already audited, so a second run
 * audits only what changed. A leaf-class author needs to know this for one specific reason: a run that
 * skipped a file reports the same clean result as a run that audited it and found nothing, so a green
 * result on an incremental run is not evidence that a newly added or renamed class was examined. Removing
 * the cache, or building from clean, is what turns the audit back into a full one - and the full audit here
 * covers both source roots, main and test together, which is the only run against which the counts in this
 * charter were taken.
 *
 * <p>Trade-offs: every justification above sits inside this Javadoc block rather than beside a statement,
 * which departs from the letter of Rule 1's adjacency requirement and is nevertheless the only placement
 * this file admits. A package declaration has no statements, so there is nothing for a comment to be
 * adjacent TO, and the requirement is satisfied vacuously rather than waived. The cost accepted is that
 * these entries sit further from the behaviour they govern than an inline comment would, and the
 * compensation is that each one names the file and line that settle it. Read the labelled entries as the
 * rationale half of the conjunctive gate - which fails code missing EITHER the docstring or the rationale -
 * and not as evidence that the half was skipped for want of somewhere to put it.
 *
 * <p>Assumptions: of the four content elements user-specified Rule 1 enumerates, only Purpose applies to a
 * package declaration -- it accepts no parameters, yields no value and raises nothing -- so the other three
 * are inapplicable rather than omitted, and no at-clause is fabricated to stand in for one.
 */
package com.carddemo.authorization;
