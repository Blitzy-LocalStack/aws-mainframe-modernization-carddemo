/**
 * Transcribed COBOL business rules of the batch bounded context, and the only layer of
 * batch-service permitted to hold a decision.
 *
 * <p><b>Purpose.</b> The job definitions above this package wire readers, processors,
 * writers and step ordering; the repositories below it move rows; the four rule classes here
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
 * {@code JavadocPackage}, configured at line 238, is a file-set check asserting that a
 * {@code package-info.java} EXISTS wherever a processed compilation unit does, while
 * {@code MissingJavadocPackage}, configured at line 378, asserts that the file carries
 * real Javadoc rather than merely existing. The plugin is bound to Maven's
 * {@code validate} phase and fails the build at warning severity, so both fire before
 * anything in this directory is compiled. The consequence is worth stating plainly: the
 * absence of this file would fail the ten sibling types, not itself. Refactoring Rationale: that
 * number read six, then nine, and the directory now holds ten, so it is restated as a measurement a
 * reader can re-take -- {@code ls} this directory, subtract this charter, and the remainder is what
 * the two checks above would fail. Naming the population rather than a remembered total is the only
 * form of the sentence that survives the next class landing here.</p>
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
 * <p>Refactoring Rationale: this section used to say that the directory held this charter and nothing
 * else, and that every class named below was planned rather than delivered. Measured at this revision
 * all FOUR are delivered -- {@code PostingValidationService}, {@code CategoryBalanceService},
 * {@code InterestCalculationService} and {@code DatasetGenerationService} -- alongside
 * {@code BatchStepLedger} and {@code BatchErrorPublisher}, both of which are named separately below
 * because neither transcribes a paragraph. The
 * earlier wording was accurate when written and became the single most misleading paragraph in the file
 * once the classes landed: a reader consulting it to learn whether the posting reject chain existed was
 * told it did not, and the four rules it governs -- the reject precedence, the two category-balance
 * arms, the {@code DEFAULT} rate fallback and the retention discipline -- read as descriptions of work
 * still to do rather than of behaviour under test. Each entry below now carries LANDED or PLANNED
 * against the directory beside it, which is a claim to be re-measured whenever a file is added.</p>
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
 * <p>Four RULE classes, and no fifth. The list is closed, so the question "which class does
 * this rule belong in" keeps a definite answer as the tree grows. A fifth rule class must not
 * be added to this package on the strength of being useful: usefulness is not an
 * authority, and the authorities are the migration plan and the project rules.</p>
 *
 * <p>Assumptions: the directory holds TEN non-charter classes and this roster names four of them, so
 * the closure is over transcriptions and not over files. Refactoring Rationale: the
 * word RULE is emphasised here because the previous wording, "Four classes, and no fifth", was true of
 * this roster and false of the directory from the moment {@code BatchStepLedger} landed -- a reader
 * counting files found five, then six, then nine, and now finds ten, and had to reach the section three
 * hundred lines below to learn that the closure was never over files. Saying which population is closed
 * is cheaper than the sentence that reconciles two readings of it. The ten are the four transcriptions
 * below, {@code BatchStepLedger} and {@code BatchErrorPublisher} named in the orchestration section,
 * {@code DailyFeedWatermarkService} named there with them, {@code PostingRecordUnitOfWork} named there
 * with those, {@code BatchStepLedgerWriter} which the
 * invariants section names as the one type carrying an independent transaction, and
 * {@code BatchFailureReporter}, the port {@code BatchStepLedger} reports a failed STEP through and the
 * only interface here -- its adapter is {@code com.carddemo.batch.config.SqsBatchFailureReporter},
 * which is why the interface is in this package and the transport is not. Assumptions: that port and
 * {@code BatchErrorPublisher} are two OCCASIONS and one sender. The port carries the step-level failure
 * the ledger records and the publisher carries the RUN-level notification
 * {@code com.carddemo.batch.BatchApplication} issues, but the port's adapter now delegates to the
 * publisher, so the module has exactly one class that puts a message on the terminal error sink.
 * Refactoring Rationale: the previous wording, "two senders and not one", described the shape that made
 * one hard failure publish TWICE -- the adapter held its own client and mapper and sent, and the
 * publisher sent again once the job had returned -- while the publisher's own charter promised one
 * notification per failed run. Naming the occasions rather than the senders is what keeps a reader from
 * restoring the second sender as a symmetry the design never wanted.</p>
 *
 * <dl>
 *   <dt>{@code PostingValidationService}</dt>
 *   <dd>LANDED. The posting reject chain, transcribed from
 *       {@code app/cbl/CBTRN02C.cbl:370-420}. Three paragraphs carry it:
 *       {@code 1500-VALIDATE-TRAN} at {@code :370} is the entry point,
 *       {@code 1500-A-LOOKUP-XREF} at {@code :380} resolves the card cross-reference and
 *       {@code 1500-B-LOOKUP-ACCT} at {@code :393} resolves the account and applies the
 *       two balance and date tests. Assumptions: the baseline's precedence is MIXED and not
 *       uniformly first-reason-wins, and the mixture is part of the contract. The first two
 *       conditions short-circuit: the card lookup at {@code :372} guards the account lookup
 *       at {@code :373}, so reason 100 excludes 101, and the account read's
 *       {@code NOT INVALID KEY} branch at {@code :400} encloses both boundary tests, so
 *       reason 101 excludes both 102 and 103. The last two do NOT short-circuit: the two
 *       boundary blocks at {@code :407} and {@code :414} are sequential and unguarded --
 *       line 413 closes the first and line 414 opens the second with no test of the reason
 *       between them -- so a transaction failing BOTH has the second assignment overwrite
 *       the first and is reported as 103. First-reason-wins over the first two, last-writer-wins
 *       over the last two. Reading the whole chain as first-reason-wins is exactly what a naive
 *       transcription produces, and it is wrong in a way no single-fault case reveals: it
 *       yields 102 for a transaction that is both over the limit and late, where the
 *       reference yields 103. The order is therefore preserved as an order, and the
 *       precedence itself is not decided here -- see the boundary section below for the
 *       type that owns it. The two boundaries those tests guard are inclusive in the
 *       baseline and stay inclusive: a balance landing exactly on the credit limit posts
 *       and one cent beyond rejects, and a transaction dated equal to the account
 *       expiration date posts while one day past rejects.</dd>
 *
 *   <dt>{@code CategoryBalanceService}</dt>
 *   <dd>LANDED. The transaction-category-balance maintenance,
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
 *   <dd>LANDED. The monthly interest accrual, transcribed from
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
 *   <dd>LANDED. The generation-dataset retention discipline that stands
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
 *       boundary section below.
 *       <p>Assumptions: this class holds a WRITING surface, and so -- since the accrual's own
 *       paragraphs landed -- does {@code InterestCalculationService}, which persists the generated
 *       interest transaction of {@code 1300-B-WRITE-TX} and the account update of
 *       {@code 1050-UPDATE-ACCOUNT}. Both are deliberate and neither is the general case: the
 *       remaining rules here are functions of their arguments and touch nothing. This one holds the
 *       dataset bucket, so it writes the two reservation markers that make
 *       a generation allocation durable across containers and retries, it stages a generation's dataset
 *       file, and it deletes the objects of a generation the retention rule has aged out. Placing those
 *       three in a job instead was the alternative and was declined: three jobs stage generations, so
 *       the bucket and the key convention would then be known in three places rather than one.</p>
 *       <p>Assumptions: every one of its operations is reached from a job in
 *       {@code com.carddemo.batch.job}. {@code BackupTransactionsJob} allocates, stages, applies the
 *       retention rule, scratches what rolls off and renders a location;
 *       {@code CombineTransactionsJob} additionally resolves the current generation of its two input
 *       families, which is the only reader of that form -- {@code app/jcl/COMBTRAN.jcl:24} and
 *       {@code :26} are the only two {@code (0)} references in the whole baseline.</p></dd>
 * </dl>
 *
 * <h2>Invariants every class in this package inherits</h2>
 *
 * <p><b>5. Money is {@code BigDecimal} at scale 2, and the rounding mode is fixed per
 * operation.</b> The migration plan's transformation rule T3 fixes the representation at every
 * hop and forbids {@code double} and {@code float} in the money path -- which the shared
 * architecture test asserts rather than requests. ONE mode exists and it is not selectable:
 * {@code com.carddemo.common.money.Money#GENERAL_ROUNDING}, which is {@code RoundingMode.HALF_UP},
 * governs every monetary reduction in this package including the accrual quotient.
 * {@code Money#monthlyInterest} takes no mode parameter, so no call site can select another.</p>
 *
 * <p>Trade-offs: the reference accrual discards its surplus digits where this package rounds them, and
 * the derivation is recorded here because the evidence on the reference side is an ABSENCE and so
 * cannot be read off a statement. The baseline statement at {@code app/cbl/CBACT04C.cbl:464-465} reads
 * {@code COMPUTE WS-MONTHLY-INT} then {@code = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200} and carries no
 * {@code ROUNDED} phrase -- nor does any other statement in that program -- and its target field
 * declared {@code PIC S9(09)V99} at line 168 therefore discards surplus digits toward zero. This
 * package discards them the same way, under {@code Money.BASELINE_INTEREST_ROUNDING}, because the plan
 * pins this formula to the reference at its section 0.7.3 and makes the golden masters its oracle at
 * 0.7.7; transformation rule T3's half up remains the general default for every OTHER reduction on the
 * money path. No cent differs, so nothing is registered, and {@code C-ROUNDING} is withdrawn in
 * section 7.5 of {@code docs/architecture/cobol-to-service-traceability.md}.
 * {@code InterestCalculationService.ACCRUAL_ROUNDING} derives from the shared constant and names the
 * mode beside the service for a reader who looks for it here.</p>
 *
 * <p>Trade-offs: the cent does not stay local, which is why the divergence is registered with parity
 * evidence rather than merely noted -- line 467 adds each reduced term into the account total and line
 * 352 adds that total to the balance, which the next inclusive over-limit comparison is made against.
 * A predecessor of this paragraph described a second, truncating mode adopted to avoid the cent; that
 * constant is withdrawn, because a frozen transformation rule outranks a parity argument that the plan
 * itself provides a divergence register for.</p>
 *
 * <p>Assumptions: that unit of work is genuinely three writes and the baseline applies all
 * three or none. {@code app/cbl/CBTRN02C.cbl:440-442} performs
 * {@code 2700-UPDATE-TCATBAL} at line 440, then {@code 2800-UPDATE-ACCOUNT-REC} at line
 * 441, then {@code 2900-WRITE-TRANSACTION-FILE} at line 442, all inside the
 * {@code 2000-POST-TRANSACTION} paragraph.</p>
 *
 * <p>Assumptions: the reason this rule is stated as a rule is a <b>readability</b> one and not a
 * correctness one, and the distinction is worth stating because the intuitive justification for it is
 * false. Annotating a method here does NOT open a nested boundary inside the caller's:
 * {@code Transactional} defaults to {@code Propagation.REQUIRED}, which JOINS the caller's
 * transaction and opens nothing, so an ordinary annotation on a method in this package is
 * behaviourally inert when the job layer has already begun the unit of work. The two propagation
 * modes that genuinely would split it are {@code REQUIRES_NEW}, which suspends the outer
 * transaction and commits an inner one independently, and {@code NESTED}, which commits to a
 * savepoint the outer transaction can still roll back past -- and either one is how the three
 * writes stop being one commit: a posted transaction whose category balance has not moved, or an
 * updated account balance with no matching transaction row, are states the baseline cannot produce,
 * and a golden-master comparison would report either as a parity failure and would be right to.
 * Trade-offs: the rule stays absolute for every class that touches a business schema -- no annotation
 * of any propagation -- even though the
 * default one is harmless, because "annotate only with the default, never with the other two"
 * relies on every future author knowing which of eight enum constants is safe here, whereas "no
 * annotation in this package" is checkable by grep and states where the boundary lives. What is
 * given up is the ability to annotate a method for documentation value; the boundary is documented
 * here instead, once.</p>
 *
 * <p>Refactoring Rationale: there is now exactly ONE named exception, {@code BatchStepLedgerWriter},
 * and it is stated here because the rule above is otherwise absolute and a reader who found that
 * annotation would be entitled to conclude the rule had simply been broken. Every method of that class
 * is annotated {@code Propagation.REQUIRES_NEW} deliberately, and what keeps the invariant intact is
 * the schema it writes: {@code batch.batch_run} is this module's OWN ledger metadata and is none of the
 * three writes the reference commits together. The independence is the entire point. A ledger row saved
 * inside the step's transaction is rolled back by the very failure it exists to record, so before that
 * class existed a hard-failed run left no failed row at all. Trade-offs: the exception is named rather
 * than expressed as a general clause such as "except when writing the ledger", because a general clause
 * invites a second exception argued by analogy and the next thing argued by analogy is a business
 * write. One name is checkable; a category is not.</p>
 *
 * <p>Assumptions: that exception cannot reach a business schema even by mistake. The class holds one
 * collaborator, the ledger row's own repository, so there is no path from it to {@code ledger} or to
 * {@code account} for an independent transaction to split.</p>
 *
 * <p>Assumptions: a method here reached with <b>no</b> ambient transaction runs each of its
 * statements in its own implicit one, which is the case a unit test exercises. That is why the job
 * layer's boundary is a requirement of the production path rather than a convenience: the classes
 * in this package are correct only as participants, and nothing in them asserts that a caller
 * supplied a transaction.</p>
 *
 * <p>Assumptions: a business date arrives as a job parameter and is never read from the
 * clock, so a rerun is reproducible and the golden comparison is stable. The
 * {@code reference} schema is read-only to this module: its grant carries {@code SELECT}
 * and nothing further, so a write attempted against it is refused by the database rather
 * than by a convention.</p>
 *
 * <p>Assumptions: every collaborator arrives through constructor injection and no class
 * here holds mutable static state, which is what lets a rule be unit tested without a
 * database, a queue or a running step. The reference programs kept their equivalents in
 * shared {@code WORKING-STORAGE}, so this is a structural change with no behavioural one.</p>
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
 *       select posting on zero and the reject path otherwise. Assumptions: what that factory
 *       preserves is a MIXED precedence rather than a uniform first-reason-wins one --
 *       first-reason-wins across reasons 100 and 101, which short-circuit their successors,
 *       and last-writer-wins across reasons 102 and 103, whose two assignment blocks at
 *       {@code :407} and {@code :414} are sequential and unguarded so that 103 overwrites
 *       102. Stating it as uniformly first-reason-wins would describe an implementation that
 *       reports 102 for a transaction failing both boundaries, which is a divergence on a
 *       combination that is ordinary rather than contrived.</dd>
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
 * <p>{@code com.carddemo.batch.job} orchestrates and this package decides. A job selects
 * steps, wires readers and writers and reports a status; it never holds a rule. The methods
 * here are the named decisions a job calls, and each one corresponds to a cited COBOL
 * paragraph so that the traceability matrix can pair the two.</p>
 *
 * <p>The four rule classes here define the named methods a job calls, and each such method is
 * the migrated form of a COBOL paragraph, which is what makes the traceability matrix
 * citable. Assumptions: the fifth class, {@code BatchStepLedger}, sits beside them and is NOT
 * a fifth transcription -- it transcribes no paragraph, because the baseline has no checkpoint contract
 * to transcribe: the only {@code RESTART=} anywhere is commented out at
 * {@code app/jcl/DEFGDGD.jcl:2} and there is no {@code CHKPT=} in the tree at all. It is here rather
 * than in the job layer because the decision it makes -- whether a step of a run has already finished,
 * and with what graded outcome -- is a decision and not a wiring concern, and because the job layer is
 * the layer it exists to be called BY.</p>
 *
 * <p>Refactoring Rationale: a SIXTH class, {@code BatchErrorPublisher}, sits beside them on the same
 * terms and for a different reason, and both the placement and the reason are worth stating. It
 * transcribes no paragraph because the baseline publishes no such message at all -- a reference batch
 * program signals failure by terminating abnormally, at {@code app/cbl/CBTRN02C.cbl:707-711} and at the
 * matching paragraph of each sibling program -- so it is an addition and is registered as one. It is
 * here rather than in this module's configuration package because a send loop, a swallow policy and a
 * log contract are behaviour: a class declared inside a {@code @Configuration} type could not be
 * exercised without building a context, which is the property this package's tests depend on not
 * needing. Its wiring alone is declared there, behind the property that gates the whole sink.</p>
 *
 * <p>Assumptions: it holds no rule and takes no decision about the run. What to publish is decided by
 * the entry point, which alone knows the job name, the run identifier and the graded return code
 * together; whether the tier is one that stops the chain is decided by
 * {@code com.carddemo.batch.dto.BatchReturnCode}; and what a payload may carry is decided by
 * {@code com.carddemo.batch.dto.BatchErrorEvent}, whose own constructor refuses a success tier and
 * redacts an identifier-shaped or credential-naming component. This class serialises, sends, and
 * reports whether the send landed.</p>
 *
 * <p>Refactoring Rationale: a SEVENTH class, {@code DailyFeedWatermarkService}, sits beside those two
 * on the same terms -- it transcribes no paragraph and it is not a fifth transcription -- and the
 * reason it exists at all is a difference between the two systems rather than a rule in either. The
 * reference's daily feed is a flat dataset REPLACED between runs, so a program reading it from the top
 * reads exactly one night's records; this module's feed is {@code ledger.daily_transactions}, which
 * ACCUMULATES because its rows are what the three verification passes of the migration plan's section
 * 0.9.2 compare against. Reading that relation from the top therefore reposts every retained night,
 * and the position consumed so far has to be durable somewhere for the second run to be correct.</p>
 *
 * <p>Assumptions: it holds no rule and decides nothing about a transaction. It answers one question --
 * which feed ordinal a named consumer has already consumed through -- and records one answer, and both
 * the read and the advance run inside the caller's transaction so the position commits with the
 * postings it describes rather than beside them. It is here rather than in the job layer for the reason
 * {@code BatchStepLedger} is: whether work has already been consumed is a decision, and the job layer
 * is the layer it exists to be called BY. Trade-offs: it is the third class here with a writing
 * surface, so the "a rule here is a function of its arguments" property does not extend to it either.
 * Alternatives Considered: deleting consumed rows, or filtering by business date. Both were rejected in
 * the migration it landed with, the first because the verification passes need the rows it would
 * delete, the second because the reference's own feed carries a BLANK processing stamp on every one of
 * the 300 records of {@code app/data/ASCII/dailytran.txt}, leaving no date on the record to filter
 * by.</p>
 *
 * <p>Refactoring Rationale: an EIGHTH class, {@code PostingRecordUnitOfWork}, sits beside those three
 * and is not a fifth transcription either, though it is the one whose placement needs the most care to
 * state. It applies the posting decisions to ONE feed record and issues that record's writes, which is
 * the migrated form of {@code 2000-POST-TRANSACTION} at {@code app/cbl/CBTRN02C.cbl:424-444} and of
 * the reject branch at {@code app/cbl/CBTRN02C.cbl:446-465}. What it holds is the ORDER of the three
 * writes at {@code :440-442}, the account accumulation and its sign convention at
 * {@code app/cbl/CBTRN02C.cbl:547-552}, and the pairing between those writes and the feed checkpoint.
 * It decides no reject reason, no category-balance arm and no rate, so the roster above stays closed at
 * four: a reader asking "which class decides whether a category balance is created" is not sent
 * here.</p>
 *
 * <p>Refactoring Rationale: it arrived here from the job layer rather than being written here, and
 * the move settled a contradiction this charter already carried. The section above states that a job
 * "never holds a rule", while the posting job held the account sign convention transcribed from
 * {@code :547-552} in a private method -- so the rule was one layer above where this charter says
 * rules live, and being private it was also unreachable by any test that wanted to drive it. Both
 * facts had one cause and one fix. Assumptions: the transaction boundary did NOT move with it. The
 * job still opens one transaction per record around {@code applyOneRecord}, and this class carries no
 * transaction annotation, so invariant 1 above holds over it unchanged.</p>
 *
 * <p>Trade-offs: it has a writing surface, and it is the one class here for which that matters most,
 * because its writes span two business schemas -- {@code ledger} and {@code account} -- in a single
 * unit. No ordinal is put on that among the writers here, because the count depends on whether a
 * persisted accrual and a staged dataset are read as writes and two neighbouring paragraphs already
 * answer that differently; the property that matters is which schemas one class writes, and it is
 * stated. That is accepted for the reason the
 * migration plan's section 0.4.1.3 records: the reference commits the three writes together, and any
 * arrangement that committed them separately would make partial states observable that the reference
 * cannot produce. Alternatives Considered: leaving the unit private to the job. Rejected on measured
 * evidence rather than on layering preference -- while it was private, the engine-backed tests that
 * claim to prove its atomicity re-implemented the sequence in their own helpers, and one of them
 * omitted the checkpoint write, so a production regression in the write order or in the checkpoint
 * could not fail them.</p>
 *
 * <p>Trade-offs: it is the second class in this package with a WRITING surface, alongside
 * {@code DatasetGenerationService} and the accrual's persistence noted above, so the "a rule here is a
 * function of its arguments" property does not extend to it. That is accepted rather than worked
 * around: the alternative places the only producer of this module's one outbound message in the job
 * layer, where seven jobs would each have to know the sink, or in the configuration layer, where it
 * could not be unit tested at all. Everything about HOW those methods are driven belongs to the job layer: step
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
