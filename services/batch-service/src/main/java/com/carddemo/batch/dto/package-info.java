/**
 * Job-boundary data transfer shapes for the CardDemo batch bounded context: the
 * argument, result and payload records that cross this module's edges.
 *
 * <h2>Target contract, and the tree state at the checkpoint that authored it</h2>
 *
 * <p>Assumptions: every type name and every count below describes this package's
 * <b>target contract</b> as the migration plan assigns it, and not the set of
 * files sitting beside this charter today. The migration lands its artifacts in
 * plan order and a package charter is authored before the types it governs, so
 * at the checkpoint that authored this one the directory holds this file alone.
 * A type named below that has no file yet is therefore <b>planned</b>, not
 * missing, and a count below is a target total rather than a measurement of the
 * directory. The parent charter at {@code com.carddemo.batch} opens with the
 * same declaration for the same reason, so a reader moving between the two
 * meets one convention rather than two.</p>
 *
 * <p>Alternatives Considered: withholding this charter until the eleven types it
 * governs exist. Rejected, because this charter is what the authors of those
 * types work from -- which shape belongs here, which may not, and where the set
 * closes -- so writing it last would leave the package with no stated contract
 * during exactly the interval in which one is needed. The accepted cost is that
 * the inventory below reads as present tense unless the distinction is declared,
 * which is what this section is for; this is the single place a reader has to
 * look to tell a target from a measurement. The parent charter at
 * {@code com.carddemo.batch} reached the same decision for the same reason, so
 * the ordering is a subtree convention rather than a choice made once here.</p>
 *
 * <h2>Purpose: the shapes that cross this module's job boundaries</h2>
 *
 * <p><b>Purpose.</b> This package holds the data transfer shapes that cross the
 * batch module's boundaries, and it holds nothing else. Six categories account
 * for every type in it:</p>
 *
 * <ul>
 *   <li>the job parameters decoded from the container command line that an AWS
 *       Step Functions state supplies through its container overrides;</li>
 *   <li>the per-step run summary surfaced both to the durable
 *       {@code batch.batch_run} ledger and to standard output;</li>
 *   <li>the outcome of validating one daily transaction, together with the
 *       reject codes and descriptions that outcome carries;</li>
 *   <li>the disclosure-group rate-lookup key and the rate-lookup result;</li>
 *   <li>the generation coordinate that locates one dataset generation in object
 *       storage;</li>
 *   <li>the single queue payload envelope this module publishes.</li>
 * </ul>
 *
 * <p>Assumptions: a type here carries data across a boundary and carries no
 * behaviour beyond validating its own shape. Every business rule transcribed
 * from a COBOL paragraph belongs to {@code com.carddemo.batch.service}, every
 * reader, processor, writer and step ordering belongs to
 * {@code com.carddemo.batch.job}, and every representation concern of the
 * baseline record layouts -- widths, sign overpunch, packed decimal, dropped
 * {@code FILLER}, the misspelled baseline field names -- belongs to
 * {@code com.carddemo.batch.mapper}. Stating the exclusions is what keeps a
 * shape from quietly acquiring a rule, because a rule that reaches a transfer
 * object is a rule the traceability matrix can no longer cite against a
 * paragraph.</p>
 *
 * <h2>These are not HTTP request or response bodies</h2>
 *
 * <p><b>This module is argument-driven, not request-driven, so the types here
 * are internal job-boundary shapes and not the request and response bodies of
 * an interface contract.</b> A job in this module is started by a state machine
 * state that runs a container task and waits for it, and that state's container
 * overrides carry the job token and the business date as process arguments.
 * The health-only actuator listener accepts no job body and exposes no route that
 * consumes these types. Their boundary is a process argument list on the way in
 * and a process exit status plus a ledger row on the way out.</p>
 *
 * <p>Assumptions: this is the one point on which the batch module deliberately
 * breaks the symmetry of the reactor, and the asymmetry is load-bearing rather
 * than incidental. Of the eight service rows in the migration plan's
 * transformation mapping this is the single row that omits both an
 * {@code api/} controller entry and a published interface-contract entry under
 * {@code openapi/}; the other seven service modules -- auth, account, card,
 * transaction, reference, authorization and reporting -- each publish an
 * interface contract, and this one does not. The parent charter at
 * {@code com.carddemo.batch} states the matching negative boundaries in its own
 * words: no {@code api} subpackage, no controller, no published interface
 * contract, no interface-documentation configuration class and no security
 * configuration class. The module's own {@code services/batch-service/pom.xml}
 * agrees independently: web and actuator are present solely for health, while
 * no interface-documentation or security starter is declared. The actuator
 * route has no application data-transfer contract.</p>
 *
 * <p>Assumptions: consequently no type in this package may carry a request-body
 * or response-body binding annotation, an HTTP response-entity wrapper type, a
 * bean-validation annotation asserted as an HTTP contract, or any annotation
 * from the interface-documentation library. None of those has a consumer here,
 * because there is no business body to bind and no application route to
 * document. The web starter makes binding annotations and response wrappers
 * technically resolvable, which makes this charter the load-bearing prohibition
 * against using them for a nonexistent API. Documentation annotations remain
 * absent from the classpath. Bean validation behaves differently and the
 * difference is worth stating rather than glossing:
 * {@code services/batch-service/pom.xml:190} does declare
 * {@code spring-boot-starter-validation}, so a bean-validation annotation
 * resolves perfectly well and is legitimate here -- as a constraint on the
 * shape's own members, checked wherever this module chooses to check it. What is
 * excluded is reading it as an HTTP contract, because no request-handling layer
 * exists to enforce one on a body that never arrives. The prohibition is
 * recorded in this charter, at the package's own
 * documentation entry point, precisely because the reasonable alternative is so
 * inviting: an author who has just written a transfer object for one of the
 * other seven modules will recognise the shape of this directory and complete
 * the pattern by reflex. A controller-shaped type added here compiles and tests
 * cleanly and then sits in the build with no caller, which is the failure mode
 * hardest to notice and hardest to remove once other code has grown around
 * it.</p>
 *
 * <p>Trade-offs: refusing the HTTP-facing vocabulary costs this package the
 * convenience of the shared conventions the other seven modules use for
 * request bodies and interface documentation. The web types are technically
 * resolvable because actuator health needs a servlet container, but using those
 * types here would invent a business request contract that no controller or
 * published route consumes. These shapes therefore state their constraints in
 * ordinary Java and remain tied to the process and ledger boundaries.</p>
 *
 * <h2>Parameters, return values and exceptions: declared inapplicable</h2>
 *
 * <p>A package declaration accepts no parameter, yields no value and raises
 * nothing. This charter therefore carries no parameter, return or exception
 * at-clause, and no authorship, availability or revision at-clause either.</p>
 *
 * <p>Assumptions: the inapplicability is declared rather than left silent
 * because the user-specified Explainability rule -- the single rule governing
 * this project -- names at its line 39 a docstring that omits parameters,
 * return values or purpose among its forbidden patterns, and a reader has to be
 * able to tell a declared inapplicability from an oversight. Fabricating the
 * at-clauses would be worse than leaving them out: Javadoc has no parameter,
 * return or exception concept for a package, and the repository rule set audits
 * at-clause bodies for emptiness, so an invented clause would either be
 * discarded or flagged. Of the four docstring elements that rule enumerates at
 * its lines 18 to 21, exactly one -- purpose -- applies to this compilation
 * unit, and the sections above and below discharge it.</p>
 *
 * <p>Assumptions: the exception element deserves its own note, because the
 * authorities behind it are not the one a reader might reach for first. The
 * obligation to document raised exceptions comes from that rule's line 21,
 * which qualifies it "where applicable"; from the house convention recorded at
 * {@code tests/README.md:544-549}, which names four elements -- purpose,
 * parameters, returns and exceptions; and from
 * {@code config/checkstyle/checkstyle.xml:451-455}, where the completeness
 * module is configured to validate declared throws clauses. It does <em>not</em>
 * come from that rule's validation gate at line 43, which names purpose,
 * parameters and return values and omits exceptions altogether. Attributing the
 * exception obligation to line 43 would misquote the very clause a reviewer
 * audits this file against, which is why the three real authorities are named
 * instead.</p>
 *
 * <h2>The roster: eleven types, and this charter</h2>
 *
 * <p>Eleven types, and no twelfth. The list is closed, so the question "which
 * type owns this contract" keeps a definite answer as the package fills:</p>
 *
 * <dl>
 *   <dt>{@code BatchJobName}</dt>
 *   <dd>The seven job tokens of the container argument contract:
 *       {@code preflight-daily-transactions}, {@code post-transactions},
 *       {@code calculate-interest}, {@code backup-transactions},
 *       {@code combine-transactions}, {@code export} and {@code import}. Each
 *       token must stay byte-identical to the name its job bean registers
 *       under, a requirement the sibling charter at
 *       {@code com.carddemo.batch.job} states from the other side. A token that
 *       differs by one character compiles and deploys, then fails inside the
 *       state machine with an unresolved-job error.</dd>
 *
 *   <dt>{@code BusinessDate}</dt>
 *   <dd>The opaque ten-character business-date token. Its width is the
 *       baseline's: {@code app/cbl/CBACT04C.cbl:178} declares
 *       {@code PARM-DATE PIC X(10)} inside the linkage group whose length field
 *       at line 177 is {@code PARM-LENGTH PIC S9(04) COMP}, and
 *       {@code app/cbl/CBACT04C.cbl:180} receives it through
 *       {@code PROCEDURE DIVISION USING EXTERNAL-PARMS}.</dd>
 *
 *   <dt>{@code BatchJobParameters}</dt>
 *   <dd>The decoded container command line as one immutable record: the job
 *       token, the business date, and the per-step arguments a step needs. This
 *       is the type at which an orchestration decision becomes a Java one, so
 *       it is also the type at which a malformed argument list must be rejected
 *       rather than defaulted.</dd>
 *
 *   <dt>{@code BatchReturnCode}</dt>
 *   <dd>The three process exit-status tiers a job reports to the orchestrator:
 *       {@code 0} for clean completion, {@code 4} for a soft warn on which
 *       downstream states may still run, and {@code >= 8} for a hard failure
 *       that the state's catch handler routes to failure notification.</dd>
 *
 *   <dt>{@code BatchRunSummary}</dt>
 *   <dd>The per-step run summary, written to the durable
 *       {@code batch.batch_run} ledger and echoed to standard output. Its
 *       members align with the ledger columns the module's own migration
 *       defines -- run identifier, step name, status, start and finish
 *       instants, and return code -- so the ledger row and the log line cannot
 *       disagree about the same run.</dd>
 *
 *   <dt>{@code RejectReason}</dt>
 *   <dd>The four posting reject codes paired with their descriptions, carried
 *       across character for character: {@code INVALID CARD NUMBER FOUND} with
 *       reason 100 at {@code app/cbl/CBTRN02C.cbl:385-386},
 *       {@code ACCOUNT RECORD NOT FOUND} with reason 101 at lines 397 to 398,
 *       {@code OVERLIMIT TRANSACTION} with reason 102 at lines 410 to 411, and
 *       {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION} with reason 103 at
 *       lines 417 to 418.</dd>
 *
 *   <dt>{@code PostingValidationResult}</dt>
 *   <dd>The outcome of validating one daily transaction: whether it may post,
 *       and if not, which {@code RejectReason} explains why. The baseline
 *       expresses the same decision as a numeric test,
 *       {@code IF WS-VALIDATION-FAIL-REASON = 0} at
 *       {@code app/cbl/CBTRN02C.cbl:211}, which selects posting on zero and the
 *       reject path otherwise.</dd>
 *
 *   <dt>{@code DisclosureGroupKey}</dt>
 *   <dd>The three-component disclosure-group lookup key, and exactly three:
 *       {@code app/cpy/CVTRA02Y.cpy:6} declares
 *       {@code DIS-ACCT-GROUP-ID PIC X(10)}, line 7 declares
 *       {@code DIS-TRAN-TYPE-CD PIC X(02)} and line 8 declares
 *       {@code DIS-TRAN-CAT-CD PIC 9(04)}, together forming the group key of
 *       the 50-byte disclosure-group record.</dd>
 *
 *   <dt>{@code InterestRateLookup}</dt>
 *   <dd>The rate-lookup result, including the two outcomes that are easy to
 *       collapse into one and must not be: the fallback to the group named
 *       {@code DEFAULT}, and a genuine rate of zero. The fallback is at
 *       {@code app/cbl/CBACT04C.cbl:436-438}, where a status of {@code '23'} on
 *       the disclosure-group read moves {@code 'DEFAULT'} into the group
 *       identifier and re-reads. The rate itself is
 *       {@code DIS-INT-RATE PIC S9(04)V99} at
 *       {@code app/cpy/CVTRA02Y.cpy:9}.</dd>
 *
 *   <dt>{@code DatasetGeneration}</dt>
 *   <dd>The date-and-generation coordinate that locates one dataset generation
 *       in object storage, spelled as a {@code dt=} prefix followed by a
 *       {@code gen=} prefix. It stands in for the baseline's relative
 *       generation reference: {@code app/jcl/DEFGDGB.jcl} defines six
 *       generation bases, at lines 25, 31, 37, 43, 49 and 55, every one of them
 *       carrying {@code LIMIT(5)} and {@code SCRATCH}.</dd>
 *
 *   <dt>{@code BatchErrorEvent}</dt>
 *   <dd>The terminal error-sink message envelope, and the only queue payload
 *       this module publishes. One publisher and one sink is the whole of this
 *       module's asynchronous surface; the request-and-reply exchanges of the
 *       migration belong to other bounded contexts.</dd>
 * </dl>
 *
 * <p>Assumptions: those eleven plus this charter make twelve compilation units
 * in this directory, and a thirteenth is outside the package's assigned
 * contract. The count is stated because the roster is the mechanism by which a
 * reader routes a question to a type without opening eleven files, and an
 * unlisted type defeats that the moment it appears. The parent charter at
 * {@code com.carddemo.batch} closes its own inventory the same way, declaring
 * seven subpackages and no eighth, so a reader meets one convention across this
 * subtree rather than a closed list in one charter and an open one in the
 * next.</p>
 *
 * <p>Alternatives Considered: distributing these eleven contracts into the
 * subpackages that consume them -- the job tokens beside the jobs, the reject
 * reasons beside the posting validation service, the generation coordinate
 * beside the dataset service. Rejected, because six of the eleven are consumed
 * by more than one subpackage and three of them cross the module boundary
 * outward: {@code BatchReturnCode} is read by the orchestrator through the exit
 * status, {@code BatchRunSummary} is read through the ledger, and
 * {@code BatchErrorEvent} is read off a queue by a consumer this module does
 * not own. A contract with an external reader placed inside the subpackage that
 * happens to write it invites a second, divergent copy in the next subpackage
 * that needs to read it, and the divergent copy is discovered by an
 * orchestration failure rather than by a compiler.</p>
 *
 * <h2>Three invariants every type in this package inherits</h2>
 *
 * <p><b>First: money is exact fixed point, and it never leaves fixed point.</b>
 * The migration plan's transformation rule T3 pins the representation at every
 * hop -- a numeric column of scale 2 in the database, {@code BigDecimal} at
 * scale 2 in Java, and a JSON string on any wire. Binary radix-2 numeric types
 * and bare JSON numbers are excluded from the money path, and the exclusion is
 * asserted by the architecture test at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * rather than requested in prose, so it cannot decay into a comment nobody
 * runs.</p>
 *
 * <p>Assumptions: the normative field is
 * {@code app/cpy/CVACT01Y.cpy:7}, which reads
 * {@code 05  ACCT-CURR-BAL                     PIC S9(10)V99.} in the 300-byte
 * account record. Two properties of that declaration matter here. It is
 * <em>zoned</em> decimal with a sign overpunch rather than packed decimal, so
 * the sign travels inside the last digit position and a decoder that assumes
 * the wrong sign convention returns a plausible positive figure for a negative
 * balance; the house records that trap directly, noting that the compiler's
 * default sign convention misreads the overpunch and silently corrupts negative
 * balances. And it carries ten integer digits with two decimal places, twelve
 * significant digits in all.</p>
 *
 * <p>Alternatives Considered: transporting money as a JSON number instead of a
 * JSON string. Rejected on arithmetic, not on taste. A JSON number is parsed by
 * most clients into an IEEE-754 binary64 value, and binary64 is a radix-2
 * format, so a decimal fraction of scale 2 such as {@code 0.01} or
 * {@code 0.10} has no terminating expansion in it and is stored as the nearest
 * representable neighbour rather than as the value written. The error is below
 * a cent per value and accumulates across a posting or accrual run, so a total
 * reconciles to within a few cents and never exactly -- and at twelve
 * significant digits there is no headroom left to absorb it. A string crosses
 * the boundary as the digits that were written, and the receiver decides what
 * to parse them into. The accepted cost is that a client must parse rather than
 * read a number directly, which is the lesser cost: a wrong figure in the cents
 * still looks entirely plausible, so this class of error is not reported by
 * anything except a reconciliation that fails much later.</p>
 *
 * <p><b>Second: user-visible message text is carried character for character.</b>
 * The migration plan's transformation rule T8 carries every message across
 * verbatim from the COBOL program or copybook it originates in. In this package
 * that lands on {@code RejectReason}, whose four descriptions are the string
 * literals quoted in the roster above and are reproduced exactly, including
 * their spelling and their abbreviations.</p>
 *
 * <p>Assumptions: this is why {@code RejectReason} holds those literals directly
 * rather than externalising them behind message keys. The reject descriptions
 * are not presentation text that a locale may vary; they are bytes in a data
 * file. The baseline writes each one into
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at
 * {@code app/cbl/CBTRN02C.cbl:182}, immediately after
 * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at line 181, and that four-plus-76
 * pair is the 80-byte trailer that follows the 350-byte transaction image
 * declared at {@code app/cbl/CBTRN02C.cbl:176-178}. The record length those two
 * parts sum to is asserted independently by the driver, at
 * {@code app/jcl/POSTTRAN.jcl:36}, which creates the reject stream with
 * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)}. A message indirected through a
 * lookup table can be edited without touching the code that emits it, and
 * editing one would change the bytes of a data file that the parity comparison
 * reads.</p>
 *
 * <p><b>Third: the import discipline, stated as an absolute.</b> A type in this
 * package depends on the Java platform library, on
 * {@code com.carddemo.common.money.Money} for every monetary member, and on
 * serialization annotations only where a shape genuinely crosses a wire.
 * Nothing else. In particular a type here never reaches for another service
 * module's {@code domain} package, never for a cloud provider software
 * development kit type, never for a web or servlet type, and never for a batch
 * framework internal.</p>
 *
 * <p>Assumptions: the four exclusions are not stylistic. A cross-module
 * {@code domain} dependency turns a shared physical table into a shared
 * deployable, which is the failure bounded contexts exist to prevent, and it is
 * forbidden by the architecture test named above rather than by convention. A
 * provider software development kit type in a transfer object makes the shape
 * untestable without a client and pins the package to a release cadence it does
 * not control. A web type contradicts the negative boundary declared earlier in
 * this charter, and no such type is on this module's classpath to import in any
 * case. A batch framework internal in a transfer object couples the argument
 * contract to a framework version, so a framework upgrade becomes an
 * orchestration change. The migration plan's transformation rule T2 supplies
 * the positive form of the same discipline: one former {@code COPY} statement
 * becomes exactly one type dependency, taken from the single package that owns
 * that contract, and the shared concerns -- money, codecs, error types, the
 * timestamp form, the validation flags -- come only from
 * {@code com.carddemo.common} and are never re-declared per service. That is
 * the Java analogue of compiling every COBOL program against one copybook
 * include path, which the house states in its own words as never duplicating a
 * layout and keeping it single-sourced from {@code app/cpy/}.</p>
 *
 * <h2>Baseline lineage: which program or job each contract comes from</h2>
 *
 * <p>Baseline paths are cited for provenance only. Nothing under
 * {@code app/**} is read at run time, and nothing under it is altered by this
 * migration: the COBOL is the behavioural oracle and stays byte-identical.
 * Where the migrated behaviour differs from the baseline, the baseline does one
 * thing, the Java does another, and the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. No claim is made
 * anywhere in this package that the baseline itself was altered, because it was
 * not. Line numbers refer to the source as committed, and columns 73 to 80 of a
 * COBOL or JCL line carry a sequence field that is not part of the
 * statement.</p>
 *
 * <dl>
 *   <dt>{@code app/cbl/CBTRN02C.cbl}, 731 lines</dt>
 *   <dd>The origin of {@code RejectReason}, {@code PostingValidationResult} and
 *       the counters {@code BatchRunSummary} reports. The validation trailer is
 *       at lines 180 to 182, the counter group at lines 184 to 187 --
 *       {@code WS-TRANSACTION-COUNT} and {@code WS-REJECT-COUNT}, each
 *       {@code PIC 9(09)}, alongside {@code WS-TEMP-BAL PIC S9(09)V99} -- and
 *       the exit contract at lines 227 to 231, where the two counts are
 *       displayed and then {@code IF WS-REJECT-COUNT > 0} at line 229 selects
 *       {@code MOVE 4 TO RETURN-CODE} at line 230. That statement is the sole
 *       origin of the warn tier {@code BatchReturnCode} models. Its driver is
 *       {@code app/jcl/POSTTRAN.jcl:23}, {@code //STEP15 EXEC PGM=CBTRN02C},
 *       which carries no parameter and supplies nine data definitions, the
 *       reject stream among them as a newly created generation.</dd>
 *
 *   <dt>{@code app/cbl/CBACT04C.cbl}, 652 lines</dt>
 *   <dd>The origin of {@code BusinessDate}, {@code DisclosureGroupKey} and
 *       {@code InterestRateLookup}. The linkage group is at lines 175 to 178
 *       and the receiving header at line 180; the {@code DEFAULT} fallback is
 *       at lines 436 to 438; and the accrual itself is at lines 462 to 465,
 *       where line 464 reads {@code COMPUTE WS-MONTHLY-INT} and line 465 reads
 *       {@code = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. Its driver is
 *       {@code app/jcl/INTCALC.jcl:22},
 *       {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}.</dd>
 *
 *   <dt>{@code app/cbl/CBTRN01C.cbl}, {@code app/cbl/CBEXPORT.cbl} and
 *       {@code app/cbl/CBIMPORT.cbl}</dt>
 *   <dd>The remaining job lineages behind three of the seven
 *       {@code BatchJobName} tokens. The first is the one genuinely driverless
 *       program in the baseline, named by no job among the files in
 *       {@code app/jcl/}; the other two have drivers but are not wired into the
 *       batch chain, which makes them unscheduled rather than undriven.</dd>
 *
 *   <dt>{@code app/jcl/DEFGDGB.jcl}, 63 lines</dt>
 *   <dd>The origin of {@code DatasetGeneration}, through the six generation
 *       bases it defines at a five-generation scratch limit.</dd>
 * </dl>
 *
 * <p>Assumptions: the six bases in that one file are not the whole inventory,
 * and the arity is easy to understate. Ten generation bases exist across the
 * baseline -- six there, three in {@code app/jcl/DEFGDGD.jcl} and one in
 * {@code app/jcl/DALYREJS.jcl} -- so {@code DatasetGeneration} is written
 * against a family count of ten rather than six, and the object-store layer
 * provisions ten prefix families with a five-noncurrent-version lifecycle rule.
 * A coordinate type built for six families silently has nowhere to put the
 * other four.</p>
 *
 * <p>Trade-offs: the graded numeric scale that {@code BatchReturnCode} carries
 * belongs to the container's process exit status and to the parity oracle, and
 * to nothing else in this repository. The oracle's rubric under {@code tests/}
 * grades its own five levels and treats a warn-level aggregate as its green
 * state; the Java build tooling is binary, because the documentation gate, the
 * compiler, the unit-test runner and the assertion library each pass or fail.
 * Describing a Java build in the oracle's graded vocabulary would hide a real
 * failure behind a borrowed word, so the two vocabularies are kept apart even
 * though two adjacent parts of one repository then count success differently
 * and a reader has to know which is speaking. The cost of not separating them
 * is a build that reports success while failing.</p>
 *
 * <h2>Why this charter exists, and why none sits above this directory</h2>
 *
 * <p>Two independent grounds require this file, and either alone would be
 * sufficient.</p>
 *
 * <p>Assumptions: the first ground is the user-specified Explainability rule,
 * which attaches at its line 15 the docstring duty to every module entry point.
 * In Java that entry point is the package declaration, and a package
 * declaration can carry a docstring only in a {@code package-info.java}, so
 * there is no alternative location for this text. The Javadoc block above is
 * therefore not decoration on this file; it is the file's entire reason to
 * exist, and a bare {@code package} statement would be a failure rather than a
 * minimum. This file would not be in scope from the migration requirements
 * alone -- no service behaviour depends on it -- which is precisely why the
 * ground is recorded here.</p>
 *
 * <p>Assumptions: the second ground is an external contract that makes the
 * first one build-fatal, and it is a deliberate pair of checks rather than one.
 * {@code config/checkstyle/checkstyle.xml} declares at its line 245, at file-set
 * level, the check that a directory holding processed compilation units must
 * contain a {@code package-info.java} at all; and it declares at its line 378,
 * inside the syntax-tree walker, the companion check that such a file must
 * carry Javadoc. A file holding nothing but a {@code package} statement
 * satisfies the first and fails the second, which is exactly the outcome the
 * pairing is designed to produce. That rule set is bound to the Maven
 * {@code validate} phase in {@code services/pom.xml}, under the execution
 * identifier {@code checkstyle-documentation-gate}, with violations failing the
 * build at warning severity, so the gate runs before compilation on every local
 * build and not only in continuous integration. The rule set admits no in-code
 * bypass, because no comment-based or annotation-based suppression filter is
 * configured in it; and its companion {@code config/checkstyle/suppressions.xml}
 * is loaded fail-closed and scoped to generated sources and test fixtures only,
 * so nothing under {@code src/main/java} can be excused from the gate. Emptying
 * this block breaks the module locally, not merely in a pipeline.</p>
 *
 * <p>Assumptions: no charter file exists at {@code com/}, at
 * {@code com/carddemo/} or at any other pure namespace segment above this
 * directory, and none belongs there. The presence check at
 * {@code config/checkstyle/checkstyle.xml:245} is a
 * file-set check: it fires only for a directory that contains a compilation
 * unit the audit actually processed. A namespace directory holds no compilation
 * unit, so no violation is reachable in one -- while the companion content
 * check would immediately demand real Javadoc from any defensive stub placed
 * there. A stub above this directory would therefore satisfy no check that
 * could ever have fired while exposing itself to one that would. The absence is
 * recorded so that it reads as a decision rather than as an omission.</p>
 *
 * <p>Assumptions: that same rule set is stricter than its own defaults in two
 * ways that bear on the eleven types this charter governs, so their authors
 * meet the requirement here rather than discovering it from a failed build.
 * First, presence and completeness are audited at every visibility, private
 * members included, and the exemption a Javadoc-inheriting annotation would
 * normally earn is cleared: {@code config/checkstyle/checkstyle.xml:364-366}
 * carries the presence scope together with the emptied allowed-annotations
 * property, and {@code config/checkstyle/checkstyle.xml:452-455} carries the
 * completeness access modifiers with both missing-tag allowances set false and
 * declared throws clauses validated. An overriding method therefore still
 * documents what <em>this</em> implementation does with the contract it
 * inherits. Second, the summary module at
 * {@code config/checkstyle/checkstyle.xml:505-508} leaves its sentence
 * terminator at the default period and supplies a forbidden-fragment pattern
 * that rejects a placeholder marker, either of the two vague rationales the rule
 * names as examples at its line 41, and a summary opening by restating an
 * accessor's own name. Because no formatting module from the Javadoc family is
 * configured at all, an authorship, availability or revision at-clause is never
 * required, and none is added anywhere in this package.</p>
 *
 * <h2>Authoring notes</h2>
 *
 * <p>Trade-offs: this file is restricted to printable ASCII. Where a cited
 * source carries a non-breaking hyphen or an em dash, this charter uses an
 * ASCII hyphen-minus or a pair of ASCII hyphens; wording is otherwise
 * unchanged and only those punctuation code points are normalised. The reason
 * is concrete rather than aesthetic: {@code tests/README.md} uses the
 * non-breaking hyphen inside the very words this charter has to reproduce,
 * including the justification labels themselves, so text copied from there
 * yields a label that looks correct, greps wrong, and silently escapes an audit
 * searching for the canonical spelling. The parent charter at
 * {@code com.carddemo.batch} adopts the same restriction for the same reason.
 * The accepted cost is typographically plainer prose; the gain is that every
 * label and every quoted phrase here is byte-predictable.</p>
 *
 * <p>Assumptions: the four justification labels used throughout this file --
 * {@code Alternatives Considered:}, {@code Refactoring Rationale:},
 * {@code Assumptions:} and {@code Trade-offs:} -- are spelled exactly as the
 * user-specified Explainability rule presents them at its lines 31 to 34, and
 * as {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes them: plural where the
 * rule writes them plural, unparenthesised, and each closed by a colon that
 * belongs to the label rather than to the sentence after it.</p>
 *
 * <p>Alternatives Considered: wrapping each label in an emphasis element, which
 * is what the two adjacent charters in this module do. Rejected here, for two
 * stated reasons. That standard lists absence of emphasis markup among the four
 * load-bearing properties of the label form, on the ground that the label is
 * read by a text search before it is read by a person and one spelling is what
 * makes such a search complete; and its own worked Java example writes the
 * labels bare inside a paragraph, as the shared-kernel charter under
 * {@code com.carddemo.common.money} also does. The same standard requires that
 * the forms are never mixed inside one file, so this file uses the bare form
 * for every label and the emphasis form for none. The cost is that this charter
 * reads slightly differently from its two neighbours; the gain is that its
 * labels match the wording the validation gate actually audits.</p>
 *
 * <p>Assumptions: {@code Refactoring Rationale:} is deliberately unused in this
 * file. That rule defines the label at its line 32 as applying when existing
 * code is replaced, and the Java in this package replaces nothing: the COBOL
 * stays byte-identical and keeps running. Labelling an ordinary
 * COBOL-versus-Java difference that way would assert something untrue about the
 * baseline, so the rationales here are labelled
 * {@code Alternatives Considered:}, {@code Assumptions:} or
 * {@code Trade-offs:} instead. The label is named in the list above because the
 * list states the canonical four, not because this file uses all four.</p>
 *
 * <p>Assumptions: the two rule namespaces this charter cites are distinct and
 * are kept textually distinct in every sentence above. The user-specified
 * Explainability rule is the single rule governing this project, cited by name
 * and by line number at its lines 15, 18 to 21, 31 to 34, 39, 41 and 43 above;
 * the tokens T2, T3 and T8 name transformation rules from the migration plan,
 * a separate numbering whose own echo of the explainability requirement is
 * rule T10. Neither scheme is an alias for the other, and collapsing them would
 * send a reader to the wrong document for the wording that binds.</p>
 */
package com.carddemo.batch.dto;
