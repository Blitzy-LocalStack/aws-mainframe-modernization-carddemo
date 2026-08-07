/**
 * Transcribed COBOL business rules of the batch bounded context, and the only layer of
 * batch-service permitted to hold a decision.
 *
 * <p><b>Purpose.</b> The job definitions above this package wire readers, processors,
 * writers and step ordering; the repositories below it move rows; the four classes here
 * hold the rules that neither of those layers may own. Each significant COBOL paragraph
 * becomes one named method, which is the property that lets
 * {@code docs/architecture/cobol-to-service-traceability.md} cite a paragraph-to-method
 * pair for every migrated rule rather than name a class and leave a reader to search it.
 * Every collaborator arrives through constructor injection, so a rule here can be unit
 * tested without a database, a queue or a running step.</p>
 *
 * <p><b>Why this file exists at all.</b> It is required by the project's single
 * user-specified rule, Rule 1 (Explainability), which attaches a documentation duty to
 * every module entry point. A Java package declaration is such an entry point, and it can
 * carry documentation only from a {@code package-info.java}, so this file is the only
 * place that duty can be discharged for this package. The migration plan's transformation
 * mapping does not list it: at its section 0.5.1.7 that mapping names the business
 * services of this package and no charter beside them. The Javadoc block below is
 * therefore not decoration on this file, it is the file's entire reason to exist, and a
 * bare {@code package} statement here would be a failure rather than a minimum.</p>
 *
 * <p>Assumptions: the documentation duty is enforced mechanically rather than
 * aspirationally, which is why the block below is long rather than a sentence. Two checks
 * in {@code config/checkstyle/checkstyle.xml} reach this directory and they are distinct:
 * {@code JavadocPackage}, configured at line 245, is a file-set check asserting that a
 * {@code package-info.java} EXISTS wherever a processed compilation unit does, while
 * {@code MissingJavadocPackage}, configured at line 378, asserts that the file carries
 * real Javadoc rather than merely existing. The plugin is bound to Maven's
 * {@code validate} phase and fails the build at warning severity, so both fire before
 * anything in this directory is compiled. The consequence is worth stating plainly: the
 * absence of this file would fail the four sibling classes, not itself.</p>
 *
 * <p>Trade-offs: no in-source escape hatch exists here and none is wanted. The
 * annotation-based suppression filter and both comment-based ones are deliberately omitted
 * from that configuration, so an in-source suppression comment and an in-source
 * suppression annotation each do nothing to this gate. The only filter configured is the
 * file-based one, and {@code config/checkstyle/suppressions.xml} scopes its entries to
 * generated sources at line 213 and to test fixtures at line 250, so nothing under
 * {@code src/main/java} is suppressible at all. The flexibility given up is the whole
 * point: a gate that can be switched off line by line, with no single artifact showing
 * that it was switched off, is not a gate.</p>
 *
 * <h2>Target contract, and the tree state at the checkpoint that authored it</h2>
 *
 * <p>Assumptions: every inventory, class name and count in this charter describes the
 * package's <b>target contract</b> as the migration plan assigns it, not the set of files
 * present beside this one today. The migration lands its artifacts in plan order and this
 * charter is authored first, so at the checkpoint that authored it this directory holds
 * this charter and nothing else. A class or test named below that has no file yet is
 * therefore <b>planned</b>, not missing, and a count below is a target total rather than a
 * measurement of the directory.</p>
 *
 * <p>Alternatives Considered: withholding this charter until every class it governs
 * exists. Rejected, because the charter is what the authors of those classes work from --
 * which type belongs here, which may not, and what the closed set is -- so writing it last
 * would leave the package with no stated contract during exactly the interval in which one
 * is needed. It would also leave the directory failing {@code JavadocPackage} the moment
 * the first service class landed. The cost of authoring it first is that its inventory
 * reads as present tense unless the distinction is declared, which is what this section is
 * for; the paragraph above is the single place a reader has to look to tell a target from
 * a measurement.</p>
 *
 * <p>Assumptions: baseline paths below are cited for provenance only. Nothing under
 * {@code app/} is read at run time, and nothing under it is modified by this migration or
 * by anything else -- it is reference-only and stays byte-identical, which is precisely
 * what qualifies it to serve as the parity oracle these rules are measured against. Line
 * numbers refer to the source as committed, and columns 73 to 80 of a COBOL or JCL line
 * carry a sequence field that is not part of the statement.</p>
 *
 * <h2>Service classes and their baseline provenance</h2>
 *
 * <p>Four classes, and no fifth. The list is closed, so the question "which class does
 * this rule belong in" keeps a definite answer as the tree grows. A fifth class must not
 * be added to this package on the strength of being useful: usefulness is not an
 * authority, and the authorities are the migration plan and the project rules.</p>
 *
 * <dl>
 *   <dt>{@code PostingValidationService}</dt>
 *   <dd>PLANNED, not yet authored. The posting reject chain, transcribed from
 *       {@code app/cbl/CBTRN02C.cbl:370-420}. Three paragraphs carry it:
 *       {@code 1500-VALIDATE-TRAN} at {@code :370} is the entry point,
 *       {@code 1500-A-LOOKUP-XREF} at {@code :380} resolves the card cross-reference and
 *       {@code 1500-B-LOOKUP-ACCT} at {@code :393} resolves the account and applies the
 *       two balance and date tests. Assumptions: the baseline evaluates its four
 *       conditions in a short-circuit order that is itself part of the contract, because a
 *       transaction failing two of them is rejected under the first one reached rather than
 *       under the more severe one. The order is therefore preserved as an order, and the
 *       precedence itself is not decided here -- see the boundary section below for the
 *       type that owns it. The two boundaries those tests guard are inclusive in the
 *       baseline and stay inclusive: a balance landing exactly on the credit limit posts
 *       and one cent beyond rejects, and a transaction dated equal to the account
 *       expiration date posts while one day past rejects.</dd>
 *
 *   <dt>{@code CategoryBalanceService}</dt>
 *   <dd>PLANNED, not yet authored. The transaction-category-balance maintenance,
 *       transcribed from {@code app/cbl/CBTRN02C.cbl:467-539}. Three paragraphs carry it:
 *       {@code 2700-UPDATE-TCATBAL} at {@code :467} reads the category row and selects an
 *       arm, {@code 2700-A-CREATE-TCATBAL-REC} at {@code :503} is the create arm and
 *       {@code 2700-B-UPDATE-TCATBAL-REC} at {@code :526} is the update arm.
 *       Alternatives Considered: collapsing the two arms into a single upsert statement.
 *       Rejected, because the baseline's choice of arm is an observable behaviour the
 *       parity suite exercises deliberately in both directions, and an upsert performed by
 *       the database resolves the branch where no test can see which way it went. Two
 *       separately reachable and separately assertable paths cost one extra read and buy a
 *       branch a test can name.</dd>
 *
 *   <dt>{@code InterestCalculationService}</dt>
 *   <dd>PLANNED, not yet authored. The monthly interest accrual, transcribed from
 *       {@code app/cbl/CBACT04C.cbl:415-500}. Four paragraphs carry it:
 *       {@code 1200-GET-INTEREST-RATE} at {@code :415} reads the disclosure group,
 *       {@code 1200-A-GET-DEFAULT-INT-RATE} at {@code :443} is the fallback that re-reads
 *       under the group named {@code DEFAULT}, {@code 1300-COMPUTE-INTEREST} at
 *       {@code :462} applies the formula and {@code 1300-B-WRITE-TX} at {@code :473}
 *       renders the generated transaction. Two further sites belong to the same rule and
 *       are easy to miss because they sit outside that range: the control break at
 *       {@code app/cbl/CBACT04C.cbl:194-222} detects a change of account and is what makes
 *       the accrual an per-account total rather than a per-row one, and
 *       {@code 1050-UPDATE-ACCOUNT} at {@code :350} is the write that break triggers.
 *       Assumptions: the control break depends on the input arriving ordered by account,
 *       so the ordering is a contract of the query that feeds this rule and not an
 *       incidental property of it -- an unordered feed would silently produce one accrual
 *       per row instead of one per account.</dd>
 *
 *   <dt>{@code DatasetGenerationService}</dt>
 *   <dd>PLANNED, not yet authored. The generation-dataset retention discipline that stands
 *       in for the baseline's generation data groups. Ten bases exist, not six, and the
 *       count is the baseline's own across three defining jobs rather than the six of any
 *       one of them: {@code app/jcl/DEFGDGB.jcl} defines six, at lines 25, 31, 37, 43, 49
 *       and 55; {@code app/jcl/DEFGDGD.jcl} defines three more, at lines 28, 51 and 74;
 *       and {@code app/jcl/DALYREJS.jcl} defines the tenth, at line 25. Every one of the
 *       ten carries {@code LIMIT(5)} with {@code SCRATCH}, so five retained generations is
 *       a uniform rule here and never a per-family parameter. Assumptions: each of those
 *       ten citations anchors on the base's {@code NAME(} line, not on the
 *       {@code DEFINE GENERATIONDATAGROUP} verb that precedes it one line earlier. The
 *       anchor is stated because both lines are defensible citations and only a consistent
 *       choice makes the ten comparable; a reader checking line 24 of
 *       {@code app/jcl/DALYREJS.jcl} finds the verb and a reader checking line 25 finds the
 *       name. Note what this class does NOT own: the coordinate itself, the two relative
 *       generation forms and the prefix rendering belong to a sibling type named in the
 *       boundary section below.</dd>
 * </dl>
 *
 * <h2>Invariants every class in this package inherits</h2>
 *
 * <p>Six rulings, each of which a sibling class depends on and none of which is a
 * preference. They are stated here rather than repeated in four places so that they cannot
 * drift apart across the four.</p>
 *
 * <p><b>1. No class here declares {@code Transactional}.</b> The transaction boundary
 * belongs to the job layer. {@code com.carddemo.batch.config.DataSourceConfig} establishes
 * one non-distributed data source with one auto-configured transaction manager over it and
 * no coordinator of any kind, and {@code com.carddemo.batch.config.BatchConfig} owns the
 * chunk transaction that wraps a step. The migration plan places the posting unit of work,
 * at its section 0.5.1.7, under a single boundary on
 * {@code com.carddemo.batch.job.PostTransactionsJob} and not here. A class in this package
 * therefore participates in the caller's transaction and never opens, commits or rolls
 * back one.</p>
 *
 * <p>Assumptions: that unit of work is genuinely three writes and the baseline applies all
 * three or none. {@code app/cbl/CBTRN02C.cbl:440-442} performs
 * {@code 2700-UPDATE-TCATBAL} at line 440, then {@code 2800-UPDATE-ACCOUNT-REC} at line
 * 441, then {@code 2900-WRITE-TRANSACTION-FILE} at line 442, all inside the
 * {@code 2000-POST-TRANSACTION} paragraph. Annotating a method in this package would open
 * a nested boundary inside that one, and a nested boundary is how the three writes stop
 * being one commit: a posted transaction whose category balance has not moved, or an
 * updated account balance with no matching transaction row, are states the baseline cannot
 * produce. A golden-master comparison would report either as a parity failure, and it
 * would be right to.</p>
 *
 * <p><b>2. No retry annotation, and no resilience library.</b> Retry here is durable
 * rather than in-process: {@code com.carddemo.batch.config.BatchConfig} records that the
 * retry tier is the state machine's per-state retry, backed by queue redelivery into a
 * dead-letter queue, and the migration plan records at its section 0.6.1.1 that no
 * resilience library is adopted anywhere in the reactor. So no {@code Retryable}, no
 * {@code EnableResilientMethods} and no circuit breaker appears in this package.</p>
 *
 * <p>Alternatives Considered: an in-process retry around a rule in this package. Rejected
 * on two concrete grounds. A rule here is a pure decision over data already read, so there
 * is no transient fault for a retry to absorb -- retrying a validation that rejected a
 * transaction rejects it again. And a retry inside the caller's transaction would re-run
 * work inside a boundary already marked for rollback, which produces a second failure
 * rather than a recovery. A breaker was rejected on its own ground: it would add a failure
 * mode without removing one, since the only calls this module makes leave it through a
 * pool and a queue client that already carry bounded timeouts.</p>
 *
 * <p><b>3. No queue type, no web type and no security type.</b>
 * {@code com.carddemo.batch.config.SqsConfig} is publish-only with a listener that does
 * not start with the context, so nothing in this package consumes a message; a class here
 * is reached by a step, never by a message arrival. The module's negative boundary is
 * settled by {@code com.carddemo.batch.config}: there is no {@code api} subpackage, no
 * controller, no published interface contract, no {@code OpenApiConfig} and no
 * {@code SecurityConfig}. Assumptions: this is a compile-time guarantee and not merely a
 * convention, because {@code services/batch-service/pom.xml} declares neither an API
 * documentation starter nor either security starter -- it records the rejection explicitly
 * at line 229 -- so such a reference would not resolve at all.</p>
 *
 * <p>Trade-offs: the layering itself is asserted by a test rather than requested in prose.
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * is scanned into every module by the {@code architecture-rules} execution declared in
 * {@code services/pom.xml}, so a class here reaching for a forbidden type fails a build
 * instead of waiting for a reviewer to notice. The accepted cost is that a layering
 * question is answered by running a test rather than by reading a comment, which is the
 * trade that keeps the answer true as the tree grows.</p>
 *
 * <p><b>4. Every {@code Stream} this package consumes is closed, with try-with-resources.</b>
 * The repository layer returns forward-only cursors rather than materialised lists, and the
 * cursor is real: {@code services/batch-service/src/main/resources/application.yml} sets
 * {@code auto-commit: false} at line 457, without which the driver ignores the fetch size
 * and buffers the entire result. Assumptions: a caller in this package must therefore both
 * close the stream and stay inside the transaction the job opened, because the cursor is
 * only valid for the life of that transaction. A stream left open pins its pooled
 * connection for the remainder of the step, which is why the same file sets
 * {@code leak-detection-threshold} at line 498 -- a leak here presents as a step that
 * stalls waiting for a connection it is itself holding, which is a considerably harder
 * thing to read than a closed cursor would have been.</p>
 *
 * <p><b>5. Money is {@code BigDecimal} at scale 2, and the interest divide is the one site
 * in this package that truncates.</b> The migration plan's transformation rule T3 fixes
 * the representation at every hop and forbids {@code double} and {@code float} in the money
 * path, which the shared architecture test asserts rather than requests. The general mode
 * is {@code com.carddemo.common.money.Money#GENERAL_ROUNDING}, which is
 * {@code RoundingMode.HALF_UP}, and it governs ordinary monetary reduction throughout.</p>
 *
 * <p>Assumptions: the accrual is the documented exception, and it is recorded here so that
 * a later reader does not normalise it away as an oversight. The baseline statement at
 * {@code app/cbl/CBACT04C.cbl:464-465} reads
 * {@code COMPUTE WS-MONTHLY-INT} then {@code = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, and
 * it carries no {@code ROUNDED} phrase -- nor does any other statement in that program's
 * 652 lines. Its target field is declared {@code PIC S9(09)V99} at
 * {@code app/cbl/CBACT04C.cbl:168}, so the store discards surplus digits toward zero. The
 * shared kernel names that behaviour instead of leaving each caller to rediscover it:
 * {@code com.carddemo.common.money.Money#BASELINE_INTEREST_ROUNDING} is
 * {@code RoundingMode.DOWN}, and {@code Money#monthlyInterest} takes the mode as a
 * required parameter with no no-argument overload, so the choice is explicit at the call
 * site by construction. {@code InterestCalculationService} accrues with the baseline mode.</p>
 *
 * <p>Alternatives Considered: applying the general half-up mode to the accrual as well, on
 * the reading that one mode across the whole money path is easier to check. Rejected,
 * because the two modes disagree by a cent whenever the quotient lands exactly on a half
 * cent, and this is the one module measured against committed golden bytes rather than
 * against transcribed prose, so a cent of disagreement is a parity failure rather than a
 * rounding preference. Trade-offs: the accepted cost is that this package carries two
 * modes and a reader has to know which applies where, which is why the two are named
 * constants on one class rather than literals at four call sites. Arithmetic order is a
 * separate and equally binding constraint under transformation rule T4: the product is
 * formed at full precision and only then divided by 1200, because dividing first yields
 * different cents on many balances.</p>
 *
 * <p><b>6. The {@code reference} schema is read-only to this module.</b>
 * {@code com.carddemo.batch.config.DataSourceConfig} records the four schemas this module
 * spans and their differing grants: {@code batch} is the only one it owns, {@code ledger}
 * and {@code account} are reached under narrowly-scoped cross-schema write grants, and
 * {@code reference} carries {@code SELECT} and nothing further -- its grant in
 * {@code data-migration/sql/V0__schemas_and_roles.sql} at line 815 adds no write
 * privilege. Assumptions: {@code InterestCalculationService} therefore reads a disclosure
 * group and never writes one, and the {@code DEFAULT} group it falls back to must already
 * exist as seeded reference data owned by {@code reference-service}. A fallback that tried
 * to create the missing row would fail on the grant rather than on the logic, and it would
 * also invent a rate the baseline never invents.</p>
 *
 * <h2>Boundaries this package does not cross</h2>
 *
 * <p>Four responsibilities sit close enough to these rules to be re-implemented here by
 * accident, and each already has an owner. They are named rather than described, because a
 * downstream author needs the type, not a hint.</p>
 *
 * <dl>
 *   <dt>Reject-reason precedence belongs to
 *       {@code com.carddemo.batch.dto.PostingValidationResult}</dt>
 *   <dd>Its precedence-resolving factory exists so that the service layer cannot invert
 *       the order, which is a stronger guarantee than asking it not to.
 *       {@code PostingValidationService} decides WHICH conditions a transaction fails; the
 *       factory decides which of those failures is reported. Assumptions: the baseline
 *       expresses the same decision numerically, testing
 *       {@code IF WS-VALIDATION-FAIL-REASON = 0} at {@code app/cbl/CBTRN02C.cbl:211} to
 *       select posting on zero and the reject path otherwise, so a first-reason-wins
 *       assignment is the behaviour being preserved.</dd>
 *
 *   <dt>Reject codes and their message text belong to
 *       {@code com.carddemo.batch.dto.RejectReason}</dt>
 *   <dd>The four numeric codes, the four descriptions carried across character for
 *       character under transformation rule T8, and their fixed-width renderings all live
 *       there. The renderings are a real contract and not formatting: the baseline declares
 *       {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} and
 *       {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}, and those four plus seventy-six
 *       display positions are what the reject stream is composed from. No class in this
 *       package holds a reject literal or a width.</dd>
 *
 *   <dt>All copybook representation concerns belong to {@code com.carddemo.batch.mapper}</dt>
 *   <dd>That package's charter states its single defining ruling: it is the only place such
 *       concerns may surface. That covers fixed-width offsets and record lengths, zoned
 *       decimal sign overpunch, packed decimal, {@code FILLER} dropped on decode, the two
 *       {@code EXPIRAION} field names the baseline spells that way, and primary account
 *       number masking. Assumptions: a class in this package receives clean domain objects
 *       and works in {@code BigDecimal} and {@code LocalDate}, never in bytes, offsets or
 *       sign nibbles. A rule that reached for an offset would put representation knowledge
 *       in two places, and the second copy is the one that goes stale.</dd>
 *
 *   <dt>The generation coordinate belongs to
 *       {@code com.carddemo.batch.dto.DatasetGeneration}</dt>
 *   <dd>The ten families, the two relative-generation forms the baseline writes as
 *       {@code (+1)} for a new generation and {@code (0)} for the current one, the
 *       derivation of the {@code dt=} and {@code gen=} segments, and the rendering of a
 *       full prefix are all declared there. {@code DatasetGenerationService} applies the
 *       retention discipline and decides WHICH generation a step reads or writes; it does
 *       not spell one. Trade-offs: splitting a naming rule from the type that renders the
 *       name costs one indirection, and it buys a coordinate that the object-store layer
 *       and this package cannot disagree about.</dd>
 * </dl>
 *
 * <h2>What this package defines for the job layer</h2>
 *
 * <p>{@code com.carddemo.batch.job} orchestrates and this package decides. The division is
 * worth stating in both directions, because either layer can absorb the other's work
 * without any compiler objecting.</p>
 *
 * <p>The four classes here define the named methods a job calls, and each such method is
 * the migrated form of a COBOL paragraph, which is what makes the traceability matrix
 * citable. Everything about HOW those methods are driven belongs to the job layer: step
 * wiring, reader and writer construction, chunk boundaries, the transaction boundary of
 * invariant 1, the process return code, the reject tally, and the end-of-run summary the
 * baseline renders at {@code app/cbl/CBTRN02C.cbl:227-228} -- two {@code DISPLAY}
 * statements whose literals are padded so that their colons align in the job log.</p>
 *
 * <p>Assumptions: a rule here returns its decision to its caller and never reports it. A
 * validation returns which reason applies and does not write the reject row; an accrual
 * returns the computed amount and the transaction to be generated and does not tally
 * anything. That is what keeps a rule callable from a unit test with no step, no writer and
 * no ledger row, and it is also why the reject count that drives the baseline's return code
 * is counted by the job rather than accumulated in a field here.</p>
 *
 * <h2>What a green build for this package does and does not prove</h2>
 *
 * <p>Trade-offs: the parity oracle for these rules is the COBOL suite under
 * {@code tests/}, which runs the reference programs and compares committed golden outputs
 * after timestamp normalisation. A green build here proves that the transcription compiles
 * and that its unit tests pass; it does not by itself prove byte parity with the baseline,
 * and no stronger claim should be read into it. The distinction is stated because this is
 * the one module in the reactor where a stronger check exists, so treating the weaker one
 * as sufficient would waste the stronger one.</p>
 *
 * <p>Assumptions: the outcome of this build is binary. Maven, Checkstyle, Surefire and
 * JUnit each pass or fail, and the graded return-code rubric belongs exclusively to the
 * COBOL suite under {@code tests/} and has no meaning for a Java build. The value 4 appears
 * in this module in exactly two unrelated senses and conflating them is the mistake this
 * paragraph exists to prevent: it is the baseline's own soft-warn condition code, set at
 * {@code app/cbl/CBTRN02C.cbl:229-230} where a reject count above zero moves 4 into
 * {@code RETURN-CODE}; and it is a value the batch container can carry out as its process
 * exit status under the contract declared on {@code com.carddemo.batch.BatchApplication},
 * which is what the invoking state machine state reads. Neither is a build result.</p>
 */
package com.carddemo.batch.service;

// WHAT: the twin comment form the four classes of this package inherit, shown against the
//       one declaration this file carries.
// WHY : Assumptions: Javadoc binds to a package declaration only when it IMMEDIATELY
//       precedes it, so this pair sits below the declaration rather than between the
//       charter and it. An earlier revision of this file placed these lines in that gap,
//       and the documentation gate rejected the file at the declaration's own line --
//       MissingJavadocPackage read the charter above as an orphaned comment and reported
//       the package as undocumented, which is the one outcome this file exists to prevent.
//       Trade-offs: the pair now follows the declaration instead of preceding it, which is
//       the accepted cost of keeping intact the Javadoc-to-declaration binding that
//       MissingJavadocPackage audits. Note also that no type, field, annotation or import
//       belongs in this file, so this declaration is the whole of its code: a member here
//       would be one the package's own charter does not govern.
