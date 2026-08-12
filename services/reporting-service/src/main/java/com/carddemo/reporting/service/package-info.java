/**
 * Business behaviour of the Reporting and Statement bounded context, expressed
 * as ordinary Java services rather than as CICS transactions or JCL job steps.
 *
 * <h2>The directory, measured rather than remembered</h2>
 *
 * <p>Four compilation units sit in this directory: this charter and the three services
 * {@code ReportExecutionService}, {@code StatementService} and {@code TransactionReportService}. Every
 * inventory, file name, class name and count here is a measurement of that directory, and the marker
 * line is re-measured on every build by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/PackageCharterInventoryTest.java},
 * so a fourth service arriving without an entry here fails the build:</p>
 *
 * <pre>
 * this directory: 4 java files = 3 classes + 1 charter
 * </pre>
 *
 * <p>Assumptions: the two controllers in {@code com.carddemo.reporting.api} are the only callers of
 * these three services over HTTP, and the published contract at
 * {@code src/main/resources/openapi/reporting-api.yaml} settles the four operations they expose. The
 * business rules stay here and none of them moves into a controller: the report-type PRECEDENCE, the
 * per-type date-range derivation, the confirmation vocabulary, the integrity reconciliation, the
 * subtotal accumulation and the statement selector rules are all owned by these classes, and a
 * controller maps their outcomes onto statuses and assembles the two sentences that interpolate a
 * resolved report name.</p>
 *
 * <p>Refactoring Rationale: that list read "report-type exclusivity" and now reads precedence, because
 * exclusivity is not what the baseline enforces. {@code app/cbl/CORPT00C.cbl} L212 is an
 * {@code EVALUATE TRUE} whose first matching arm wins, so a request marking two types runs the first of
 * them; an earlier revision of the owning service read the screen's exclusivity as a RULE and refused
 * such a request, which is a refusal the reference does not have. The word is corrected here because a
 * charter naming the wrong rule is how the wrong rule gets re-implemented after it has been fixed
 * once.</p>
 *
 * <p>Assumptions: the two composed sentences -- the submission acknowledgement and the confirmation
 * prompt -- are assembled by the controller and not here, and the split is deliberate rather than
 * incidental. Both interpolate a resolved report name into two verbatim fragments, and the controller
 * already holds that name because it resolved it before it chose a status; assembling them here would
 * mean handing the name back to a service that had just returned it. The fragments themselves are
 * verbatim reference strings either way, so transformation rule T8 is satisfied wherever they live --
 * what it forbids is a sentence the baseline does not carry, not a particular home for one it
 * does.</p>
 *
 * <p>Refactoring Rationale: that list named a "masked-collision refusal" and no longer does, because the
 * refusal it named cannot occur. Statement selection was a lookup by the card's masked rendering, which
 * is twelve constant asterisks and four digits -- so it named a TAIL, and the collision was the case
 * where two cards shared one. Two cards sharing a tail raised that refusal and a legitimate request was
 * refused; worse, ONE card sharing a tail with a card that did not exist matched exactly one row and the
 * caller received a different cardholder's statement with nothing recording the substitution. Selection
 * is now an equality on the whole number, performed by a definer-rights function that is the only
 * construct in the reporting schema able to express one, so at most one row can match and there is
 * nothing to collide. What replaces the entry is the pair of rules that IS enforced here:
 * exactly-one-of a card and an account, and a refusal when a named account holds more than one card --
 * because a statement is a per-card document and an account with several cards has several statements
 * with no basis for choosing between them.</p>
 *
 * <p>Assumptions: these services also serve the BATCH half of the context, and the callers there are
 * not controllers. {@code com.carddemo.reporting.task} drives the whole-run statement generation and the
 * report generation through the same two classes the HTTP paths use, passing a sink rather than
 * receiving a response. That is why the generation methods take a destination as a parameter and hold no
 * client of their own: one set of business rules, two destinations, and no path where a rule is stated
 * twice. It is also why no generation method here is transactional across its whole run -- each reads in
 * bounded chunks and closes each read before offering a record, so no database transaction is open while
 * an object-store write is in flight.</p>
 *
 * <p>Alternatives Considered: withholding this charter until every class it governs
 * exists. Rejected, because the charter is what the authors of those classes work
 * from -- which type belongs here, which may not, what the closed set is -- so
 * writing it last would leave the package with no stated contract during exactly
 * the interval in which one is needed. The cost of authoring it first is that its
 * inventory reads as present tense unless the distinction is declared, which is
 * what this section is for; the sentence above is the single place a reader has to
 * look to tell a target from a measurement.</p>
 *
 * <p>The COBOL programs named below are the specification for everything in
 * this package. These services encode that specification; they do not redefine
 * it. Each significant COBOL paragraph becomes one named method, which is what
 * lets {@code docs/architecture/cobol-to-service-traceability.md} cite
 * paragraph-to-method pairs instead of gesturing at a whole class. The baseline
 * under {@code app/} is read as the reference and is never edited: this
 * migration adds a path, it does not remove one.
 *
 * <p>Three services live here, and only three.
 *
 * <ul>
 *   <li>{@code TransactionReportService} produces the transaction detail report
 *       of {@code app/cbl/CBTRN03C.cbl} (649 lines), laid out by
 *       {@code app/cpy/CVTRA07Y.cpy} (73 lines) and driven in the baseline by
 *       {@code app/jcl/TRANREPT.jcl} (84 lines). Its obligation is the
 *       133-column fixed-width report, byte for byte, at the record width
 *       declared PIC X(133) on L85 of {@code app/cbl/CBTRN03C.cbl}.</li>
 *   <li>{@code StatementService} produces the account statements of
 *       {@code app/cbl/CBSTM03A.CBL} (924 lines) together with its
 *       file-handling subroutine {@code app/cbl/CBSTM03B.CBL} (230 lines), over
 *       the record layout {@code app/cpy/COSTM01.CPY} (38 lines) and the job
 *       {@code app/jcl/CREASTMT.JCL} (97 lines). It emits both a plain-text and
 *       an HTML form, and it carries no fixed arity anywhere. That last point
 *       is divergence D-2, set out below.</li>
 *   <li>{@code ReportExecutionService} takes over the on-demand submission that
 *       {@code app/cbl/CORPT00C.cbl} (649 lines) performs by writing to the
 *       JOBS transient data queue, defined across L499 to L505 of
 *       {@code app/csd/CARDDEMO.CSD} with DDNAME(INREADER) on L501 and reached
 *       from the paragraph at L462 of {@code app/cbl/CORPT00C.cbl}. Here it
 *       becomes a {@code states:StartExecution} call on a second, smaller state
 *       machine.</li>
 * </ul>
 *
 * <p>All three are built by constructor injection. They hold no static state
 * and no shared mutable working storage; repositories and mappers arrive as
 * injected collaborators. Assumptions: a COBOL program's WORKING-STORAGE is
 * process-wide and single-threaded, so a table such as WS-TRNX-TABLE at L225 of
 * {@code app/cbl/CBSTM03A.CBL}, inside the WORKING-STORAGE SECTION opened at
 * L49, is owned outright by the one running program. One instance of each of
 * these services instead serves many concurrent callers, so a field behaving
 * like that table would be shared across unrelated reports. Every value that
 * varies per invocation is therefore a parameter or a local.
 *
 * <h2>Layering</h2>
 *
 * <p>This package holds business behaviour and nothing else. The three
 * neighbours it must not absorb are worth naming, because each boundary is easy
 * to cross by accident.
 *
 * <ul>
 *   <li>HTTP concerns belong to {@code com.carddemo.reporting.api}. No request
 *       mapping, status code, header or content negotiation appears here.</li>
 *   <li>Query construction belongs to
 *       {@code com.carddemo.reporting.repository}. Nothing here writes SQL and
 *       nothing here touches an {@code EntityManager}; every read arrives
 *       through an injected repository backed by a SELECT-only database
 *       role.</li>
 *   <li>Edit-mask formatting and fixed-width byte emission belong to
 *       {@code com.carddemo.reporting.mapper}. Band geometry, padding and the
 *       two COBOL edit masks live there.</li>
 * </ul>
 *
 * <p>Trade-offs: keeping the edit masks out of this package costs one more hop
 * for anything that wants a formatted line, and that cost is accepted because
 * the masks are the part of the contract with no tolerance at all.
 * {@code app/cpy/CVTRA07Y.cpy} declares TRAN-REPORT-AMT as PIC -ZZZ,ZZZ,ZZZ.ZZ
 * on L30 and the three total bands as PIC +ZZZ,ZZZ,ZZZ.ZZ on L54, L60 and L66,
 * and the separator on L48 is PIC X(133) filled with hyphens. A sign that leads
 * where it should trail, or a column that shifts by one, is a golden-master
 * failure rather than a cosmetic difference. Gathering every such rule into one
 * mapper leaves exactly one place to audit, whereas letting each service format
 * its own lines would give one rule three homes and three chances to drift.
 * These services orchestrate; they do not format.
 *
 * <p>Assumptions: that boundary is enforced by the ArchUnit rules held in
 * {@code LayeringRulesTest.java}, under the directory
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/},
 * as an executable test rather than a written convention, so it cannot rot as
 * reviewers come and go, and its rules are discovered by package name, which is
 * why that file must not be relocated or duplicated. The boundary it guards in
 * this package is the one that keeps every PIC X(133) emission in the mapper. A
 * Checkstyle ImportControl module is deliberately not added beside it: two
 * enforcers of one boundary can disagree, and the weaker one then silently
 * defines the rule.
 *
 * <h2>What this context does not own</h2>
 *
 * <p>Assumptions: this context owns no table, no index and no view, and so has no
 * write path at all -- meaning that this module declares none and that the login role
 * it connects as can write none and can read only the seven views. The schema itself
 * is not empty of tables: it holds exactly one, {@code card_grouping_key}, created by
 * {@code data-migration/sql/V1__reporting_views.sql}, owned by the no-login role and
 * revoked from this context's login, because it carries the secret that keeps the
 * per-card statement grouping token non-invertible. The two readings are stated
 * together because the shorter one reads as "the schema is empty" and would make that
 * revoke look redundant; the ownership model is owned by
 * {@code docs/architecture/data-model-and-schema-mapping.md}. It does own a schema,
 * and the distinction matters because a
 * flat "owns no schema" disagrees with the bootstrap DDL:
 * {@code data-migration/sql/V0__schemas_and_roles.sql} creates {@code reporting}
 * as the eighth schema, owned in the database by the {@code NOLOGIN} role
 * {@code carddemo_reporting_owner} rather than by the {@code carddemo_reporting}
 * login this context connects as, and creates
 * it EMPTY. It exists to give this context a home for the read-only cross-schema
 * views it reads through, and to make the eight-schema post-state that every
 * downstream artifact asserts literally true rather than seven-plus-a-footnote.
 * What this context owns no instance of is a table, and that is what makes it a
 * pure consumer.
 *
 * <p>Assumptions: the privileges reaching across schemas are created by that same
 * file and reach FOUR schemas, not three -- {@code ledger}, {@code account},
 * {@code card} and {@code reference} -- with {@code SELECT} and nothing else.
 * {@code reference} is required by a direct baseline read: {@code CBTRN03C} opens
 * {@code TRANTYPE} and {@code TRANCATG} to resolve a transaction's type and
 * category description onto the report line.
 *
 * <p>Assumptions: the VIEWS are NOT created by that file, and the sequencing is
 * the reason rather than an oversight. A view over {@code ledger.transactions}
 * cannot be created before that table exists, and at the point the bootstrap DDL
 * runs no table exists anywhere; that file therefore creates the schema and the
 * grants and no view at all. This module also owns no migration directory -- a
 * {@code src/main/resources/db/migration} directory here would be an affirmative
 * defect, which is why Flyway is absent from its dependency set -- so the views
 * cannot come from a service migration either. They belong to a data-migration
 * step ordered AFTER the per-service migrations have created the tables they
 * read. Assumptions: that ordering is REALISED, not merely intended --
 * {@code data-migration/sql/V1__reporting_views.sql} creates all seven views this
 * package reads, and each per-service migration it depends on exists in the module
 * that owns it. The sequencing constraint above is therefore a rule about where a
 * view may be created, not a note about something absent.
 *
 * <p>Two consequences follow, and both are to be acted on rather than worked
 * around. Nothing in this package emits a data-definition statement of any kind:
 * no table, view, schema or index creation, no alteration, no privilege change
 * and no removal. And a view missing at runtime is a data-migration defect to
 * report, not something to conjure from here.
 *
 * <p>Assumptions: this package also declares no Spring Batch job repository and
 * no queue listener configuration. The batch-service module owns the Spring
 * Batch job repository and the {@code batch.batch_run} step ledger, and those
 * two together supply restart. A job repository here would be a second,
 * competing restart mechanism over the same runs, and two ledgers disagreeing
 * about whether a step completed is a worse position than having one. The
 * direction of control is the whole point: this package starts a state machine
 * execution and returns, it does not run the job inline.
 *
 * <h2>Divergence ownership</h2>
 *
 * <p>Exactly one documented behavioural divergence belongs to this package,
 * and it is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}.
 *
 * <p>D-2 is owned here: {@code StatementService} carries no fixed arity.
 * {@code app/cbl/CBSTM03A.CBL} contains exactly three OCCURS clauses in its 924
 * lines, and all three matter separately.
 *
 * <ul>
 *   <li>10 is the declared inner arity. L228 reads
 *       {@code 10  WS-TRAN-TBL OCCURS 10 TIMES}, nested inside the card
 *       table.</li>
 *   <li>512 is the measured same-card boundary. One card renders 512
 *       transactions and the 513th overruns the inner table and takes a
 *       segmentation fault. {@code tests/README.md} names that outcome
 *       F-STMT-INNER-OVERFLOW on L74.</li>
 *   <li>51 is both declared and measured, for the distinct-card boundary. L226
 *       reads {@code 05  WS-CARD-TBL OCCURS 51 TIMES}, with its parallel
 *       counter {@code 05  WS-TRN-TBL-CTR OCCURS 51 TIMES} on L232; 51 distinct
 *       cards render and the 52nd overruns the outer table.
 *       {@code tests/README.md} names that outcome F-STMT-OUTER-OVERFLOW on
 *       L76.</li>
 * </ul>
 *
 * <p>Never collapse those three numbers into one. {@code tests/README.md} L70
 * to L82 establishes that there are two independent unchecked tables, which is
 * why the phrase "~51 transactions" must not be written anywhere in this tree
 * except, as here, to warn against it: a lone threshold of 51 hides the far
 * larger same-card limit and mislabels the outcome as a transaction-count
 * problem when it is really two separate table overruns.
 *
 * <p>Alternatives Considered: dimension the Java as the baseline is
 * dimensioned, so that it stops where the COBOL stops. Rejected, because the
 * two boundaries are artefacts of statically sized tables at L226, L228 and
 * L232 of {@code app/cbl/CBSTM03A.CBL} rather than statements about how many
 * transactions a cardholder may hold, and encoding them would cap a statement
 * at a number the business never chose. {@code StatementService} therefore
 * streams, and its output has no upper bound. The baseline behaves as its
 * tables size it; the Java carries no arity; the divergence is documented
 * rather than silent. Both thresholds are registered separately, under their
 * own names, in {@code docs/architecture/cobol-to-service-traceability.md}, so
 * that a reader cannot mistake one for the other.
 *
 * <p>D-1 and D-3 are not owned here, and this package must not claim them. D-1
 * is the FD RECORD KEY declaration at line 68 of
 * {@code app/cbl/CBEXPORT.cbl} and line 40 of {@code app/cbl/CBIMPORT.cbl},
 * which names a field the file record does not contain; D-3 is the
 * final-account interest flush in CBACT04C. Both belong to batch-service.
 * Assumptions: a divergence is owned by whichever module carries the behaviour,
 * so recording one in the wrong package would leave the owning module's
 * register incomplete while making this one appear answerable for code it does
 * not hold.
 *
 * <h2>Business date</h2>
 *
 * <p>The two generators must never read a clock.
 * {@code TransactionReportService} and {@code StatementService} receive an
 * explicit date range and use it; a {@code LocalDate.now()} anywhere on the
 * generation path is a defect. Assumptions: the baseline already works this
 * way. {@code app/jcl/TRANREPT.jcl} selects records with INCLUDE
 * COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)
 * across L47 and L48, so the range reaches the report program as parameters,
 * which is exactly what lets a rerun reproduce its own output and lets a golden
 * master mean anything.
 *
 * <p>The request edge is different, and conflating the two would make one of
 * them unimplementable. {@code app/cbl/CORPT00C.cbl} reads FUNCTION
 * CURRENT-DATE at request time to turn a preset into concrete dates, on L215
 * for the monthly preset and L241 for the yearly one, and then moves the
 * resolved range into PARM-START-DATE-1 and PARM-END-DATE-2 before submitting.
 * {@code ReportExecutionService} may do the same, resolving a preset once and
 * passing the resolved range onward. Trade-offs: that one resolution must go
 * through an injectable {@code java.time.Clock} rather than a direct reading of
 * the current date, which is marginally more ceremony at the call site and is
 * accepted because a test can then pin the date and assert the resolved range.
 * The mechanism is
 * {@code com.carddemo.common.time.TimestampFormatter.formatNow(Clock)}. That
 * class offers no no-argument format method, no now method and no
 * currentTimestamp method, so a reader hunting for one of those three will not
 * find it and should not add it.
 *
 * <h2>Money and timestamps</h2>
 *
 * <p>Every monetary value here is {@code com.carddemo.common.money.Money},
 * carried at scale 2 with HALF_UP rounding and serialised as a JSON string by
 * {@code com.carddemo.common.money.MoneyModule}. Assumptions: the baseline
 * fields are exact fixed point, for example TRNX-AMT declared PIC S9(09)V99 on
 * L29 of {@code app/cpy/COSTM01.CPY}, so exactness is a contract rather than a
 * preference. IEEE-754 binary floating point, in both its primitive and its
 * boxed form, and a bare JSON number are all forbidden on the money path, and
 * that prohibition is asserted by ArchUnit rather than left to review. A JSON
 * number is excluded for the same reason as the binary forms: most clients
 * parse one into a binary floating-point value, which loses exactness at the
 * boundary a reader actually sees. Where a calculation both multiplies and
 * divides, it multiplies at full precision first and only then divides with an
 * explicit scale and rounding mode; reversing that order changes the result in
 * cents.
 *
 * <p>Assumptions: timestamps come from
 * {@code com.carddemo.common.time.TimestampFormatter}, whose
 * {@code TIMESTAMP_LENGTH} is 26 because the baseline declares TRNX-ORIG-TS and
 * TRNX-PROC-TS as PIC X(26) on L34 and L35 of {@code app/cpy/COSTM01.CPY}. A
 * timestamp of 26 blanks is a legitimate value to carry through as it stands,
 * not an error to reject: an unset X(26) field holds spaces, so rejecting them
 * would refuse records the baseline accepts.
 *
 * <h2>State</h2>
 *
 * <p>CICS pseudo-conversational state is gone, not relocated. The baseline
 * carries continuity between screen turns in a passed structure:
 * {@code app/cbl/CORPT00C.cbl} declares DFHCOMMAREA on L155, detects a first
 * arrival with IF EIBCALEN = 0 on L172, and copies the area into its own
 * working copy on L176. Identity inside that structure is CDEMO-USER-ID
 * PIC X(08) on L25 of {@code app/cpy/COCOM01Y.cpy} and CDEMO-USER-TYPE
 * PIC X(01) on L26. Here, identity comes from validated JWT claims through
 * {@code com.carddemo.common.security.JwtRoleConverter}, selection context
 * comes from the request path and query parameters, and navigation is the
 * client's business.
 *
 * <p>Alternatives Considered: keep an equivalent of that structure as a
 * server-side session, so the screen flow ports across directly. Rejected on
 * two counts. The structure is storage the client echoes back, so a client
 * could assert its own CDEMO-USER-TYPE, whereas a signed group claim cannot be
 * asserted by the caller at all. And a session store would make these services
 * sticky, which is precisely what would stop them scaling horizontally behind a
 * load balancer. The re-entry discriminator disappears outright:
 * CDEMO-PGM-CONTEXT PIC 9(01) on L29 of {@code app/cpy/COCOM01Y.cpy}, with its
 * two condition names on L30 and L31, has no counterpart here, because a
 * stateless handler answering with a field-error array has no first-visit
 * versus repeat-visit distinction to draw. Field-error presentation is driven
 * purely by the response body and never by a remembered turn count. No
 * resubmission flag, no first-arrival flag, no turn counter and no equivalent
 * of that discriminator may be reintroduced under any name.
 *
 * <p>Assumptions: this package depends on common-lib and on nothing else inside
 * the reactor. The three shared types it draws on are named above:
 * {@code Money} at scale 2, {@code TimestampFormatter} with its
 * {@code TIMESTAMP_LENGTH} of 26, and {@code JwtRoleConverter}. Another
 * service's domain package is never referenced in any form, including as a
 * fully-qualified name, a reflective string literal or a Javadoc code span, and
 * no module dependency on a sibling service exists.
 *
 * <h2>Parity evidence, which is deliberately uneven</h2>
 *
 * <p>Two of the four programs this package replaces have a golden-master oracle
 * and one does not. Saying so plainly follows the house doctrine at L50 and L51
 * of {@code tests/README.md}, which requires documenting honestly so that no
 * runnable claim hides a blocked feature.
 *
 * <p>CBTRN03C, CBSTM03A and CBSTM03B are batch CB programs. L40 to L42 of
 * {@code tests/README.md} records that the twelve such programs contain no EXEC
 * CICS verbs and run standalone, ten of them fully automatable, so their output
 * is compared byte for byte after timestamp normalisation. The shipped goldens
 * are {@code tests/golden/reporting/e2e_full_cycle_report.expected}, 857 lines
 * whose widest line is exactly 133, and
 * {@code tests/golden/statement/happy_path/statement.txt.expected} together
 * with {@code statement.html.expected} beside it, whose widest lines measure 80
 * and 85. Assumptions: 85 is a measurement of that one golden and not the
 * record contract, which is PIC X(80) on L45 and PIC X(100) on L47 of
 * {@code app/cbl/CBSTM03A.CBL}, matching DCB LRECL=80 on L89 and DCB LRECL=100
 * on L94 of {@code app/jcl/CREASTMT.JCL}. A golden that happens not to reach
 * its declared width does not narrow the declared width.
 *
 * <p>CORPT00C has no such oracle. L43 to L46 of {@code tests/README.md},
 * restated at L83 to L85, records that the eighteen online CO programs use the
 * CICS command-level API and cannot be driven end to end without a CICS
 * runtime, so only their extractable field-validation logic is unit-tested.
 * Parity for {@code ReportExecutionService} therefore rests on transcribed
 * logic and on the copybook contracts, which is a weaker footing than a golden
 * master and is stated here rather than glossed over.
 *
 * <p>Trade-offs: the COBOL suite's condition-code rubric, and its standing
 * aggregate warn code of 4, stay inside {@code tests/} and are quarantined from
 * every gate on this module. A Maven, Surefire, Failsafe, Checkstyle or JUnit
 * outcome here is binary, so there is no warn-level green for a Java build.
 * Carrying two result vocabularies in one repository is the price of leaving
 * the COBOL oracle exactly as it stands, and it is cheaper than teaching a Java
 * gate to tolerate a non-zero result, which would let a real failure through.
 * That standing warn code arises from D-1, which this package does not own.
 *
 * <h2>Documentation canon for this tree</h2>
 *
 * <p>Assumptions: the canonical labels are {@code Alternatives Considered:},
 * {@code Refactoring Rationale:}, {@code Assumptions:} and {@code Trade-offs:},
 * written in the plural, unparenthesised, each keeping its trailing colon and
 * using an ASCII hyphen-minus. That spelling is the only accepted one and it is
 * mandatory in every language and every file of the migration trees. The
 * project's explainability rule states the four labels in the plural at L31 to
 * L34, and its Validation Gate at L43 is the sentence being audited, so the
 * plural is the audited text itself. A singular, bracketed, heading-style or
 * dash-terminated variant is not an alternative spelling: it is a label that a
 * fixed-string search for the category will not find, which makes a documented
 * rationale read as absent to the audit that looks for it.
 *
 * <p>Assumptions: the label text has to be retyped from the rule
 * rather than copied out of {@code tests/README.md}, whose L548 renders that
 * token with a non-breaking hyphen, a closing parenthesis and no colon at all;
 * that file carries 106 such non-breaking hyphens across 77 lines, so a copied
 * label is silently unmatchable. Refactoring Rationale: is reserved for
 * genuinely replaced code, per L32, so where new Java merely differs from a
 * baseline this project never edits, Alternatives Considered: is the accurate
 * label.
 *
 * <p>This package documentation exists because that rule requires a docstring
 * on every module entry point at L15, and a Java package declaration is the
 * language's module entry point. The written convention is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, and the house practice it
 * extends originates in section 12 of {@code tests/README.md}. Assumptions: a
 * green Checkstyle run is necessary but not sufficient evidence of compliance.
 * The documentation gate is bound to the Maven validate lifecycle phase, so it
 * fires before compilation on a developer's machine rather than only in CI, and
 * JavadocPackage requires that this file exist in this directory while
 * MissingJavadocPackage requires that it carry Javadoc, a bare package
 * declaration satisfying the first and failing the second. What the gate cannot
 * see is whether a rationale is real: it tolerates an undocumented overriding
 * method, and a reference that merely inherits documentation satisfies none of
 * the four elements the rule lists at L18, L19, L20 and L21. Those remain a
 * reviewer's obligation.
 */
package com.carddemo.reporting.service;
