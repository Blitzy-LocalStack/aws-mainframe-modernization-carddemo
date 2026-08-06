/**
 * Owns all persistence access for the LEDGER bounded context of the CardDemo migration.
 *
 * <p><b>Purpose.</b> This package is the sole data-access boundary for the four
 * records stored in the {@code ledger} schema. It translates the baseline's
 * keyed reads, writes, rewrites, deletes and transaction browse into explicit
 * repository contracts while keeping storage details out of the service,
 * mapper, DTO and domain packages.
 *
 * <h2>Target contract and closed inventory</h2>
 *
 * <p>Assumptions: the names below state the package contract assigned by the
 * migration plan, not a claim that every named interface sits beside this
 * charter in the source tree used to author it. The charter is the contract
 * from which those interfaces are authored, so a planned interface is neither
 * an absent responsibility nor permission to invent a substitute.
 *
 * <p>Alternatives Considered: withholding this charter until an interface
 * exists was rejected on two independent grounds. Interface authors need the
 * package boundary in order to keep query ownership closed, and the
 * documentation gate checks directories as file sets: the first audited Java
 * interface in this directory would fail the charter-presence check if this
 * file were not part of the same package.
 *
 * <p>Exactly five {@code .java} files constitute this package, of which FOUR are landed:
 *
 * <ul>
 *   <li>{@code package-info.java}, this charter -- LANDED;</li>
 *   <li>{@code TransactionRepository}, the Spring Data JPA interface for
 *       {@code com.carddemo.transaction.domain.Transaction} -- LANDED;</li>
 *   <li>{@code DailyTransactionRepository}, the Spring Data JPA interface for
 *       {@code com.carddemo.transaction.domain.DailyTransaction} -- LANDED. It serves the
 *       posting job's sequential scan of the daily feed rather than a screen, which is what
 *       distinguishes its member surface from the three keyed interfaces here. Its identity
 *       type is {@code Long}, not {@code String}: the feed's own identifier is not unique,
 *       so the entity is keyed on the generated ingestion sequence the owning migration
 *       declares, and this interface's CURSOR is that same {@code ingest_seq} rather than the
 *       staged transaction identifier;</li>
 *   <li>{@code TransactionCategoryBalanceRepository}, the Spring Data JPA
 *       interface for
 *       {@code com.carddemo.transaction.domain.TransactionCategoryBalance} --
 *       LANDED. Its identifier is the declared three-part composite key, and it adds one
 *       ordered read to the inherited keyed read; and</li>
 *   <li>{@code TransactionRejectRepository}, the Spring Data JPA interface for
 *       {@code com.carddemo.transaction.domain.TransactionReject} -- PLANNED, not yet
 *       authored. Its ENTITY is landed and keyed on the reject-event sequence
 *       {@code reject_seq}, so this one is a single file away rather than two.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: the inventory is re-stated from the directory each time an interface
 * lands, because an entry that cannot be distinguished from a landed one defeats the lookup it
 * exists to serve. An earlier revision of this census said TWO of the five were landed and marked
 * {@code DailyTransactionRepository} and {@code TransactionCategoryBalanceRepository} as not yet
 * authored. Both are present, and each carries substantive query rulings -- the daily feed's scan
 * contract and the composite-key ordered read -- so the census understated the package by two
 * files and, worse, marked as absent the two interfaces whose rulings a reader most needs to
 * find. The same revision said {@code TransactionRejectRepository} was "two files away" because
 * its entity was also unauthored; {@code TransactionReject} is present in the sibling
 * {@code domain} package, so that entry is one file away, not two. A census is consulted
 * precisely to learn which contracts are settled, so under-reporting sends a reader to write an
 * interface that already exists and over-states the work remaining on the one that does not. The
 * inventory stays closed at five and now names the four that are here.
 *
 * <p>Assumptions: the remaining interface is not authored as an empty one to close the gap. A
 * Spring Data interface with no declared query and no caller contributes no behaviour and cannot
 * be exercised, so it would be a placeholder occupying the name of a reviewed contract; it
 * arrives with the job or service whose queries it declares. That is also why the three that are
 * here were correct to author rather than premature: each declares queries a landed caller
 * already issues.
 *
 * <p>Trade-offs: the inventory is closed rather than extensible. There is no
 * shared base repository, keyset base interface, custom fragment,
 * implementation fragment, Specification or Criteria helper, DTO, mapper or
 * service here. The cost is repetition across the small interfaces; what is
 * bought is one visible repository per physical record contract and no hidden
 * query surface behind a sixth type. This directory has no subdirectory.
 *
 * <h2>Transformation rule T5 is this package's charter</h2>
 *
 * <p>AAP transformation rule T5 maps {@code EXEC CICS READ},
 * {@code WRITE}, {@code REWRITE} and {@code DELETE} to repository methods.
 * It maps {@code STARTBR}, {@code READNEXT}, {@code READPREV} and
 * {@code ENDBR} to one keyset-paginated query for the transaction list. No
 * CICS file verb survives in a controller, service, mapper, DTO or domain
 * type; this package is the only place that may express its relational
 * equivalent.
 *
 * <p>Assumptions: the verb call sites and verb bodies are distinct citations.
 * The forward and backward paths call the end-browse paragraph at
 * {@code app/cbl/COTRN00C.cbl} lines 322 and 371; the paragraph begins at line
 * 692 and executes {@code CICS ENDBR} at line 694. The read paragraphs begin at
 * lines 624 and 658 and execute {@code READNEXT} and {@code READPREV} at lines
 * 626 and 660. Repository queries replace both levels, not merely the call-site
 * syntax.
 *
 * <p>Assumptions: the bounded context contains one true forward/backward
 * browse and two max-key identifier derivations. The browse is
 * {@code app/cbl/COTRN00C.cbl} lines 279 to 376. The two max-key paths are
 * {@code app/cbl/COTRN02C.cbl} lines 444 to 449 and
 * {@code app/cbl/COBIL00C.cbl} lines 212 to 217. Counting all three as
 * browses would attach a page envelope to two operations that return only one
 * maximum key.
 *
 * <p>Alternatives Considered: {@code COBIL00C} is a counter-example, not a
 * second list implementation. Its 572 lines contain zero {@code READNEXT},
 * and its {@code STARTBR} at lines 441 to 449 carries no {@code GTEQ}.
 * Lines 212 to 217 seek from high values, read the preceding row, end the
 * browse and increment that row's identifier. The page envelope must never be
 * wired into the bill-pay path. The honest tally is one true browse and two
 * max-key derivations.
 *
 * <h2>The transaction list uses the baseline's scalar keyset cursor</h2>
 *
 * <p>The cursor is one scalar transaction identifier, not a composite.
 * {@code app/cbl/COTRN00C.cbl} line 62 declares the CT00 state appended to
 * the shared communication area; lines 63 and 64 declare its first and last
 * identifiers as {@code PIC X(16)}. Lines 65 to 70 add the screen ordinal,
 * availability flag and selected identifier, but none adds a second key
 * component.
 *
 * <p>The forward query selects transaction identifiers strictly greater than
 * {@code lastKey}, orders them ascending and requests
 * {@code LIMIT size + 1}. The backward query selects identifiers strictly
 * less than {@code firstKey} and orders them descending, reproducing
 * {@code READPREV}. The baseline display capacity is ten: line 290 clears ten
 * slots, the loop at line 297 stops when its index reaches 11, and line 349
 * starts the reverse fill at slot ten. Line 360 performs the backward surplus
 * probe.
 *
 * <p>The service assembles the query result into
 * {@code com.carddemo.common.web.PageResponse}. Its record contract exposes
 * exactly four members: {@code items}, {@code firstKey}, {@code lastKey} and
 * {@code hasNext}. The two boundary keys name the ends of the page returned and
 * are {@code null} when no row is returned and no scan position remains in that
 * direction; {@code hasNext} is admitted only alongside a present
 * {@code lastKey}, so a caller told a further page follows always holds the
 * token to request it with. A backward step is expressible exactly when
 * {@code firstKey} is present. The repository supplies ordered row boundaries,
 * while the shared envelope owns its sealed cursor representation.
 *
 * <p>Assumptions: requesting one extra row is a transcription of the
 * baseline, not an invented heuristic. {@code COTRN00C} line 308 performs an
 * eleventh {@code READNEXT} beyond the ten-row fill; lines 309 to 313 set the
 * next-page condition solely from that read's outcome. The surplus row is not
 * returned. Its existence becomes {@code hasNext}.
 *
 * <p>Refactoring Rationale: the mapping is one-to-one because the baseline
 * state at {@code COTRN00C} lines 62 to 70 is a keyset cursor rather
 * than a count of consumed rows. The first and last values are read back into
 * {@code TRAN-ID} at lines 236 to 240 and 259 to 263, and displayed row
 * identifiers populate them at lines 393 and 439. What the Java design
 * removes is client-echoed state and a screen-specific paging routine: the
 * server accepts a sealed cursor and every list uses the shared envelope.
 *
 * <p>Refactoring Rationale: strict comparisons are explicit because the
 * baseline positioning was implicit. The {@code STARTBR} paragraph begins at
 * {@code COTRN00C} line 591 and names the dataset, key and key length at lines
 * 593 to 596, while {@code GTEQ} at line 597 is commented out. Repository
 * predicates therefore state {@code > lastKey} and {@code < firstKey}
 * directly instead of relying on a file-system default that a query reader
 * cannot see.
 *
 * <p>Alternatives Considered: offset pagination was rejected because
 * concurrent insertion changes the number of rows preceding a returned key,
 * causing row omission and repetition across requests. The concurrency is
 * evidenced by {@code COBIL00C} lines 212 to 217: high values, start, reverse
 * read, end, copy and increment form an unlocked read-then-increment
 * sequence. Key comparison preserves the returned boundary even when another
 * insert lands in the ordered set.
 *
 * <h2>Single-record lookup and identifier allocation stay separate</h2>
 *
 * <p>The direct transaction lookup follows the primary-key path.
 * {@code app/cbl/COTRN01C.cbl} line 269 issues {@code EXEC CICS READ};
 * lines 270 and 271 identify the transaction dataset and record, and line 273
 * supplies {@code RIDFLD(TRAN-ID)}. Rule T5 maps that keyed operation to a
 * repository method rather than to the list query.
 *
 * <p>Assumptions: identifier allocation preserves the two max-key
 * derivations. {@code COTRN02C} lines 444 to 449 move high values into the
 * key, start a browse, read the preceding record, end the browse, copy the
 * identifier and add one. {@code COBIL00C} lines 212 to 217 perform the same
 * sequence with work field {@code WS-TRAN-ID-NUM}. These operations return a
 * scalar candidate identifier and no page state.
 *
 * <p>Assumptions: {@code COTRN02C} also contains zero {@code READNEXT} and
 * zero {@code GTEQ}. Its {@code STARTBR} body at lines 642 to 650 and its
 * {@code READPREV} body beginning at line 673 exist only to obtain the maximum
 * key. The absence of a forward read corroborates the separation between
 * allocation and paging.
 *
 * <p>Assumptions: an empty transaction table yields identifier 1.
 * {@code COTRN02C} lines 688 and 689 move zeros into {@code TRAN-ID} on
 * {@code ENDFILE}; {@code COBIL00C} lines 487 and 488 do the same. Each
 * calling sequence adds one to that zero. The empty case is part of the
 * contract and cannot be replaced by a nullable maximum whose handling differs
 * by caller.
 *
 * <h2>Three query paths preserve three ordered access paths</h2>
 *
 * <ol>
 *   <li>The {@code transactions} primary key serves the scalar identifier
 *       lookup and the CT00 keyset list.</li>
 *   <li>{@code idx_transactions_card_num} serves card-ordered transaction
 *       access. {@code app/jcl/TRANREPT.jcl} line 41 identifies the card
 *       field and line 46 sorts by it ascending. The index is non-unique
 *       because one card can own many transaction rows.</li>
 *   <li>{@code idx_transactions_proc_ts} preserves the processed-timestamp
 *       alternate-key path. {@code app/jcl/TRANIDX.jcl} line 27 declares
 *       {@code KEYS(26 304)}, line 28 declares {@code NONUNIQUEKEY}, line 29
 *       declares {@code UPGRADE}, and line 30 confirms record size 350.</li>
 * </ol>
 *
 * <p>Assumptions: {@code TRANREPT.jcl} line 47 begins
 * {@code INCLUDE COND=} and line 48 closes its date range. In DFSORT control
 * statements that construct is a record-selection predicate, so its relational
 * analogue is a {@code WHERE} clause. It is not a job-step condition despite
 * sharing the word {@code COND}.
 *
 * <p>Refactoring Rationale: the processed-timestamp key survives while the
 * index-build operation does not become an application feature. The baseline
 * job still contains {@code BLDINDEX} at {@code TRANIDX.jcl} line 52, with
 * its input and output datasets at lines 53 and 54. PostgreSQL maintains the
 * declared key as rows change, so {@code BLDINDEX} is retired as a migration
 * target. Retired never means deleted: the reference JCL remains on disk and
 * unmodified, while the key at line 27 is carried by
 * {@code idx_transactions_proc_ts}.
 *
 * <h2>The record positions agree across three independent declarations</h2>
 *
 * <p>Assumptions: {@code app/cpy/CVTRA05Y.cpy} is normative under AAP rule
 * T1. Summing lines 4 to 18 places {@code TRAN-CARD-NUM} from line 15 at
 * one-based bytes 263 to 278 and {@code TRAN-PROC-TS} from line 17 at bytes
 * 305 to 330; the filler at line 18 closes the record at 350. The field widths
 * sum to exactly the record length stated at line 2.
 *
 * <p>{@code TRANREPT.jcl} independently names
 * {@code TRAN-CARD-NUM,263,16,ZD} at line 41 and
 * {@code TRAN-PROC-DT,305,10,CH} at line 42. {@code TRANIDX.jcl} line 27
 * names the timestamp key as length 26 at zero-based position 304, which is
 * one-based position 305. Copybook summation, report control and alternate
 * index definition therefore identify the same bytes.
 *
 * <p>Assumptions: the report utility's {@code ZD} token does not change the
 * card field's domain type. {@code CVTRA05Y.cpy} line 15 declares
 * {@code PIC X(16)}, and rule T1 makes that declaration authoritative. The
 * Java model preserves a declared-width character identifier, including any
 * leading zero.
 *
 * <p>Assumptions: {@code app/cpy/CVTRA06Y.cpy} lines 4 to 18 declare the
 * daily feed with the same thirteen pictures, widths and positions; only the
 * {@code DALYTRAN-} prefix differs. Its total is 350 bytes, its card number
 * occupies 263 to 278, and its processing timestamp occupies 305 to 330.
 *
 * <h2>The category balance uses its declared composite key</h2>
 *
 * <p>Assumptions: {@code app/cpy/CVTRA01Y.cpy} line 5 declares
 * {@code TRAN-CAT-KEY} as a group over the three fields at lines 6 to 8:
 * an eleven-digit account identifier, a two-character type code and a
 * four-digit category code. Their widths sum to 17 bytes. The eleven-byte
 * balance at line 9 and twenty-two-byte filler at line 10 bring the record to
 * the 50 bytes stated at line 2.
 *
 * <p>The key status is corroborated outside the copybook.
 * {@code app/cbl/CBACT04C.cbl} lines 28 to 32 select the indexed category
 * balance file, and line 31 states
 * {@code RECORD KEY IS FD-TRAN-CAT-KEY}. The repository therefore uses the
 * same three components in the same order; no surrogate or fourth component
 * is part of this record contract.
 *
 * <p>Trade-offs: a composite identifier class costs one extra domain type
 * and more explicit repository signatures than a generated scalar would.
 * The scalar alternative was rejected because it would add identity that no
 * source field carries and would hide the three-part lookup assembled by the
 * posting program. The declared group remains both the relational primary
 * key and the repository identifier.
 *
 * <h2>Transaction and concurrency boundaries preserve one posting unit</h2>
 *
 * <p>Assumptions: rule T5 maps a CICS {@code SYNCPOINT} boundary to
 * {@code @Transactional}, and maps {@code SYNCPOINT ROLLBACK} to exception
 * propagation so the transaction manager rolls back the whole unit.
 * Repository exceptions are not converted into partial success. An
 * optimistic-lock conflict is surfaced through the web error contract as
 * HTTP {@code 409}, allowing the caller to distinguish stale state from an
 * invalid request.
 *
 * <p>The posting unit stays one ACID commit. In
 * {@code app/cbl/CBTRN02C.cbl}, paragraph
 * {@code 2000-POST-TRANSACTION} begins at line 424 and invokes category
 * balance update at line 440, account update at line 441 and transaction
 * write at line 442, closing at line 444. The Java implementation keeps those
 * writes atomic even though two schema owners participate.
 *
 * <p>Alternatives Considered: a transactional outbox followed by
 * compensating reversal was rejected. It would make a posted transaction with
 * an unapplied balance, or an applied balance with no posted transaction,
 * externally observable between committed steps. No such partial-posting
 * state exists in the cited baseline paragraph, so the parity oracle would
 * identify the design as a behavioural divergence. Neither a saga nor
 * two-phase commit is introduced for these tables.
 *
 * <p>Trade-offs: {@code batch-service} writes the required
 * {@code ledger.*} objects through a narrowly scoped cross-schema
 * {@code GRANT}, which gives up strict schema-per-deployable isolation for
 * this one unit of work. The bootstrap grants schema use at
 * {@code data-migration/sql/V0__schemas_and_roles.sql} line 693 and ledger
 * table writes at line 715. The limited database privilege preserves one
 * atomic commit without creating a code dependency between deployables.
 *
 * <h2>Flyway owns the physical contract</h2>
 *
 * <p>Assumptions: {@code data-migration/sql/V0__schemas_and_roles.sql}
 * bootstraps schemas, roles and grants; line 508 creates {@code ledger} under
 * its owning login. The single normative physical contract for this package
 * is
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql}.
 * It defines the four tables named by the four entities and the transaction
 * indexes described above, and it creates no schema, role or privilege.
 *
 * <p>Assumptions: Hibernate consumes that contract and never generates it.
 * The transaction configuration pins each pooled connection to
 * {@code ledger} at {@code application.yml} line 317, selects the Flyway
 * location at line 400, refuses implicit schema creation at line 446 and sets
 * {@code spring.jpa.hibernate.ddl-auto} to {@code none} at line 469. Flyway
 * owns a schema written by two deployables, so entity annotations cannot
 * become a competing DDL authority.
 *
 * <p>Alternatives Considered: duplicating index declarations as
 * {@code @Index} metadata on entities was rejected. Hibernate generation is
 * disabled, so the metadata would not create the contract; it would only
 * provide a second prose-like copy able to drift from
 * {@code V1__ledger.sql}. Index names, uniqueness and column order stay in
 * the migration and are not reintroduced in this package.
 *
 * <h2>Import discipline mirrors one copybook include path</h2>
 *
 * <p>Refactoring Rationale: AAP rule T2 turns one former {@code COPY} into
 * one import from the type's owning package. Repository entity parameters
 * come from this module's {@code com.carddemo.transaction.domain}; the page
 * envelope comes only from {@code com.carddemo.common.web.PageResponse} and
 * is never re-declared here. Cross-service domain imports are forbidden by
 * {@code LayeringRulesTest}.
 *
 * <p>The house precedent is {@code tests/README.md} lines 540 to 542. It
 * resolves layouts through {@code cobc -I app/cpy}, names
 * {@code COPY CVTRA06Y.} and says never to duplicate a layout. The Java
 * analogy is exact:
 *
 * <pre>
 * -I app/cpy : COPY CVTRA06Y.  ::  Maven dependency on common-lib : import com.carddemo.common.web.PageResponse;
 * </pre>
 *
 * <p>Trade-offs: depending on the shared page type couples repository-facing
 * service code to one cursor envelope. Re-declaring it locally would avoid
 * that dependency but recreate the same contract in multiple bounded
 * contexts, allowing nullability, token sealing or availability semantics to
 * diverge without a compiler error. One shared type is the safer cost.
 *
 * <h2>Verification belongs to the real storage engine</h2>
 *
 * <p>Assumptions: the four interfaces are exercised by the
 * Testcontainers-backed {@code *RepositoryIT} suite under
 * {@code services/transaction-service/src/test/java} against PostgreSQL with
 * {@code V1__ledger.sql} applied. The module POM supplies the PostgreSQL
 * Testcontainers integration and the reactor's integration-test binding
 * selects the repository suite.
 *
 * <p>Assumptions: the three-layer COBOL parity oracle under
 * {@code tests/} is the batch oracle. The online browse cannot be represented
 * as covered by that oracle merely because it reaches the same tables.
 * Repository integration tests establish SQL ordering, key predicates,
 * nullability and index-compatible behaviour; they do not claim
 * golden-master coverage for the transaction-list screen.
 *
 * <p>Assumptions: the batch oracle's graded return code is not a Java build
 * state. {@code app/cbl/CBTRN02C.cbl} line 229 tests whether any record was
 * rejected and line 230 moves 4 to {@code RETURN-CODE}. That convention stays
 * with the batch program and its reference suite. Maven, Checkstyle and the
 * repository tests have binary pass-or-fail outcomes.
 *
 * <h2>The explainability labels have one auditable spelling</h2>
 *
 * <p>The project's Explainability convention requires a docstring on every
 * module entry point, which is the reason this package declaration carries this
 * block. It requires reasons rather than narration, it names the four rationale
 * categories reproduced below, and it makes the docstring and the rationale a
 * conjunctive review gate -- work fails for a missing docstring and,
 * independently, for a missing reason. The convention is recorded for readers in
 * {@code CONTRIBUTING.md} and {@code docs/CODE_DOCUMENTATION_STANDARD.md}.
 *
 * <pre>
 * Alternatives Considered:
 * Refactoring Rationale:
 * Assumptions:
 * Trade-offs:
 * </pre>
 *
 * <p>Alternatives Considered: the plural, unparenthesised spelling with an
 * ASCII hyphen-minus and trailing colon is taken from the governing convention's
 * own wording as reproduced in {@code docs/CODE_DOCUMENTATION_STANDARD.md}, not
 * inferred from nearby artifacts. A repository-wide text census excluding
 * this charter finds the canonical fourth label 1,501 times in 276 files, the
 * singular spelling 342 times in 84 files, and the parenthesised WHY spelling
 * 94 times in 28 files. The plural form is the measured majority as well as
 * the governing form. One main-source Java charter is a singular outlier, and
 * XML, YAML and HCL artifacts deliberately preserve singular house forms; none
 * changes the rule text that this Java package is audited against. Forms are
 * never mixed within this file.
 *
 * <p>Assumptions: quotations from {@code tests/README.md} are transliterated
 * to ASCII. A repository-wide census finds code point U+2011 in two files:
 * 106 occurrences in {@code tests/README.md} and one in
 * {@code docs/architecture/data-model-and-schema-mapping.md}. Lines 542 and
 * 548 of the test guide each contain one U+2011 and zero ASCII hyphens.
 * Retyping those labels with U+002D makes literal searches reliable and keeps
 * this compilation unit pure ASCII.
 *
 * <h2>Inline comments carry a canonical rationale and nothing else</h2>
 *
 * <p>An inline comment in this package is a single block placed immediately
 * above the code it explains, opening with one of the four labels above and its
 * colon, then the reason and what differs under the alternative. Purpose is
 * stated once, in the Javadoc, where the language puts it. No statement in this
 * package carries a {@code // WHAT:} line.
 *
 * <p>Refactoring Rationale: an earlier draft of this charter authorised a twin
 * comment pairing a statement-level {@code // WHAT:} line with a
 * {@code // WHY :} line, on the ground that
 * {@code tests/README.md} lines 267 and 270 establish that aligned pair. That
 * authorisation is withdrawn, and the reversal is recorded here rather than
 * silently dropped because two artifacts had already settled the question the
 * other way. the "The {@code # WHAT:} / {@code # WHY :} idiom -- prose command blocks only"
 * section of {@code docs/CODE_DOCUMENTATION_STANDARD.md} scopes
 * the twin idiom to fenced command blocks in prose "and nowhere else", and names
 * {@code .java} first in the list of files that may not carry a statement-level
 * {@code WHAT:} comment; the shared-library charter at
 * {@code services/common-lib/src/main/java/com/carddemo/common/package-info.java}
 * had already tried the twin form in Java and rejected it, on the ground that its
 * first line restates the statement it sits above. Reinstating it here would have
 * left three governing artifacts in contradiction, and that standard's own
 * Precedence note makes such a disagreement a review failure
 * until it is reconciled rather than a local choice either side may keep.
 *
 * <p>Trade-offs: the withdrawn form's one genuine use was to name a migrated
 * contract the Java tokens cannot reveal -- a baseline paragraph or a record path
 * and byte offset -- and that information still has to live somewhere. It moves
 * into the Javadoc of the declaration it describes, which is where the
 * convention puts purpose and provenance anyway, so nothing is lost and the reader gains a
 * form the language's own tooling renders. The cost is that a provenance note is
 * a few lines further from the annotation it explains than it was; the benefit is
 * that no line in this package restates the statement beneath it, which is the
 * first pattern the convention's forbidden-patterns clause rules out.
 *
 * <p>Assumptions: {@code tests/README.md} remains the source of the aligned pair
 * for fenced command blocks in prose, and this migration's new READMEs and
 * runbooks continue to use it there. That reference suite is reference-only and
 * is not retyped, so the two trees do read differently: a shell pipeline in a
 * fenced block has no docstring construct available and its effect genuinely is
 * not evident from its tokens, which is what earns the twin form there and only
 * there.
 *
 * <h2>The package charter is itself mechanically gated</h2>
 *
 * <p>Assumptions: {@code JavadocPackage} requires this file to exist, while
 * {@code MissingJavadocPackage} requires the file to carry Javadoc. A bare
 * package statement satisfies only the existence half. The summary check
 * requires the opening sentence to end with a period and rejects placeholder
 * summaries and vague rationale fragments.
 *
 * <p>Assumptions: the companion suppression file covers generated sources and
 * test fixtures only. No main-source exemption, marker comment or suppression
 * annotation can bypass this charter. The file therefore carries the complete
 * package explanation rather than relying on a build exception.
 *
 * <p>Trade-offs: this compilation unit contains one Javadoc block and one
 * package declaration and nothing else -- no annotation, import, type, field,
 * method or line comment. It carries no parameter, return, exception,
 * authorship, release or version at-clause because none has a valid subject on
 * a package declaration. Version control answers provenance; this block
 * answers responsibility and rationale.
 *
 * <p>Trade-offs: the source uses UTF-8 with no byte-order mark and limits its
 * content to ASCII characters. Typographic punctuation would read more like
 * surrounding prose, but it would make the label and citation censuses depend
 * on visually indistinguishable code points. ASCII keeps the audited label
 * bytes, operators and paths literal.
 */
package com.carddemo.transaction.repository;
