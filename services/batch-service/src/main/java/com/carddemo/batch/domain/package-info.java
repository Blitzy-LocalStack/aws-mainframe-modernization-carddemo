/**
 * JPA entity mappings for the batch bounded context: one type per fixed-length record the
 * nightly z/OS chain read or wrote, plus the single table this module owns outright.
 *
 * <h2>Purpose</h2>
 *
 * <p>The package root is {@code com.carddemo.batch}, and {@code domain} is the entity layer
 * within the seven-subpackage shape that {@code com.carddemo.batch.package-info} fixes and
 * declares closed: {@code job}, {@code service}, {@code repository}, {@code domain},
 * {@code dto}, {@code mapper} and {@code config}. A type here is a persistent mapping and
 * nothing else. It holds no business rule, because a transcribed COBOL paragraph becomes a
 * named method in {@code com.carddemo.batch.service}; and it holds no copybook representation
 * concern, because fixed widths, sign overpunch, packed decimal, dropped {@code FILLER} and the
 * baseline field-name spellings are confined to {@code com.carddemo.batch.mapper}. What is left
 * for a type here to decide is exactly one thing, and it is the thing this charter exists to
 * constrain: which physical column, of which type, length and scale, in which schema, each
 * field of each baseline record lands on.</p>
 *
 * <h2>Why this charter exists, and the form it takes</h2>
 *
 * <p>Assumptions: the project Explainability rule requires a docstring on every module entry point,
 * and in Java the entry point of a package is its package declaration, which only
 * {@code package-info.java} can carry -- so this file is load-bearing rather than decorative. Two
 * Checkstyle modules enforce that independently and neither is redundant: {@code JavadocPackage}
 * inspects the file set and requires this file to exist in any directory holding an audited source
 * file, while {@code MissingJavadocPackage} inspects the parsed tree and requires it to carry Javadoc.
 * A charter reduced to a bare package statement satisfies the first and fails the second, which is
 * why prose is the deliverable and the file's mere existence is not.
 *
 * <p>Assumptions: this compilation unit holds one statement, so the rationale the rule's
 * inline-comment half asks for has no adjacent executable line to sit beside and is carried inside
 * this block under the four canonical labels -- the only placement a package makes available. No
 * parameter, return or exception at-clause appears, because a package declaration accepts no
 * argument, yields no value and raises nothing, and {@code NonEmptyAtclauseDescription} would report
 * an invented tag with an empty body; omitting them is therefore the compliant reading of the rule
 * rather than a departure from it. The written convention every block here follows is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never restated.
 * <h2>What this package holds, and which contract each mapping is bound to</h2>
 *
 * <p>Eleven entity types belong here and no twelfth; with this charter that makes twelve compilation
 * units. Each mapping is listed with the schema-qualified table it targets and the baseline
 * record that fixes its field set.
 *
 * <p>Assumptions: twelve is this package's contract as the migration plan assigns it and also a
 * measurement of the directory, because all eleven entities have landed -- {@code BatchRun},
 * {@code DailyFeedWatermark}, {@code Account}, {@code CardXref}, {@code DisclosureGroup},
 * {@code Transaction}, {@code DailyTransaction}, {@code TransactionCategoryBalance},
 * {@code TransactionReject}, {@code Customer} and {@code Card} -- so nothing in the roster below is
 * planned. The roster stays closed at eleven, which is what keeps the question "which entity owns
 * this table" with a definite answer.
 *
 * <p>Refactoring Rationale: the roster read ten and CLOSED at ten while {@code DailyFeedWatermark}
 * was already mapped in this directory, and the omission was not a miscount -- it left the module's
 * SECOND owned table undocumented in the very layer that defines it, so a reader auditing what this
 * module owns found one owned table named and a closed roster telling them to look no further. The
 * consequence was concrete rather than cosmetic: the table decides where a posting pass RESUMES, and
 * for as long as this charter denied its existence nothing recorded that it needed an owner, a test
 * or a grant. Every count in this block, in the boundary section below and in the two ratios further
 * down is corrected together, because a roster that is right in one paragraph and wrong in the next
 * is no more usable than one that is wrong throughout.
 *
 * <p>Refactoring Rationale: the roster stood at eight and was CLOSED at eight, and that closure was
 * the direct cause of a data-integrity defect rather than a documentation slip. {@code CBEXPORT}
 * reads five masters and this module could reach only three of them, so the branch-migration export
 * emitted a dataset whose customer and card phases reported a count of zero on every run while the
 * import dispatcher accepted the three-type file as structurally complete. The closure had been
 * reasoned from the PREFLIGHT program, which opens six files and reads three -- sound for preflight
 * and not extensible to a program that reads all five. {@code Customer} and {@code Card} are
 * therefore added as read-only projections and the roster is reopened to ten. Assumptions: both are
 * projections of tables another context owns, which is not new in this package -- seven of the ten
 * already are -- and both are mapped immutable so the read-only posture is structural rather than
 * conventional.</p>
 *
 * <p>Refactoring Rationale: an earlier revision of this passage recorded that two of the then-eight
 * entities were still planned, naming {@code TransactionCategoryBalance} and
 * {@code TransactionReject}, and that the directory held seven compilation units. Both landed, so
 * that passage was replaced rather than annotated. It mattered more here than a stale count usually
 * would, because
 * the roster below pairs each entity with a schema-qualified table and a normative copybook: a
 * reader who took an entity for absent would have had both the table and the record layout in hand
 * and could reasonably have authored a duplicate under a different name, which is the one outcome a
 * closed roster exists to prevent. The copybook is normative -- the migration plan's transformation
 * rule T1 makes a field's {@code PICTURE} clause decide its column type, its Java type and its byte
 * offset -- so the copybook column below is the specification and not a provenance note.</p>
 *
 * <dl>
 *   <dt>{@code BatchRun} into {@code batch.batch_run}</dt>
 *   <dd>The FIRST of the two tables this module owns, and one of the two members of this package
 *       with no baseline record behind it. It is the durable step ledger: a row per run and step,
 *       carrying the lifecycle state, the start and end instants and the published exit status. Its
 *       columns are created by {@code services/batch-service/src/main/resources/db/migration/}
 *       {@code V1__batch.sql}, which also declares the uniqueness constraint over the run and
 *       step pair that makes the row an idempotency key. Assumptions: its start instant is mapped
 *       UPDATABLE, unlike the run identifier and step name beside it, because re-opening a row for a
 *       further attempt replaces that instant with the new attempt's own -- an operator reading a
 *       recovered night asks when the attempt now running began, and the attempt counter carries the
 *       history the earlier instants would otherwise have to.</dd>
 *
 *   <dt>{@code DailyFeedWatermark} into {@code batch.daily_feed_watermark}</dt>
 *   <dd>The SECOND table this module owns, and the second member with no baseline record behind it.
 *       One row per feed, keyed by the feed's record-layout name, carrying the highest ingestion
 *       ordinal any run has consumed together with the run, business date and instant that advanced
 *       it. Its columns are created by
 *       {@code services/batch-service/src/main/resources/db/migration/}
 *       {@code V2__batch_feed_watermark.sql}, which also declares the four check constraints that
 *       keep a negative ordinal, a blank feed name, a blank run identifier and a business-date token
 *       of the wrong width out of the table.
 *       <p>Assumptions: it has no baseline record because the reference needed none.
 *       {@code app/jcl/POSTTRAN.jcl:30-31} supplies the feed as the flat sequential dataset
 *       {@code AWS.M2.CARDDEMO.DALYTRAN.PS} and {@code app/cbl/CBTRN02C.cbl:202-219} reads it to end
 *       of file -- and that dataset is REPLACED between runs, so "the whole file" and "tonight's
 *       transactions" are the same set. The target's feed accumulates, its rows being what the three
 *       verification passes in {@code docs/runbooks/data-migration.md} compare against, so the
 *       identity the replaced dataset carried implicitly has to be carried explicitly, and this row
 *       carries it. The divergence is registered with the step ledger's in
 *       {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *       <p>Assumptions: getting this row wrong is not a one-record fault. A position that advanced
 *       too far skips those transactions permanently; one that failed to advance re-posts them, and
 *       under the additive model of {@code app/cbl/CBTRN02C.cbl:202-219} every amount is added to its
 *       account balance a second time -- each of those postings individually valid, so no reject is
 *       written and no return code changes.</p></dd>
 *
 *   <dt>{@code Customer} into {@code account.customers}, from {@code app/cpy/CVCUS01Y.cpy}</dt>
 *   <dd>The 500-byte customer record, walked in key order by the export alone -- the first phase of
 *       the reference export at {@code app/cbl/CBEXPORT.cbl:260}, emitting one {@code 'C'} record per
 *       customer -- and never written here, by any job in this module. Declared {@code @Immutable}, so
 *       no write path exists at any layer. Sixteen of its eighteen fields are mapped: the national
 *       identifier at {@code app/cpy/CVCUS01Y.cpy:17} and the government-issued identifier at line 18
 *       are stored enciphered by the owning service and are deliberately unmapped, which is a
 *       registered divergence recorded on the type itself. Assumptions: both hold envelopes sealed
 *       under the Aurora customer-managed key, this module holds no decrypt grant for that key and
 *       must not acquire one, and an unmapped field cannot be selected -- so the omission is what
 *       makes the missing grant unnecessary rather than merely unexercised. The export carries a
 *       redacted constant in each of the two spans, which keeps every following field of the exported
 *       record at its declared offset.</dd>
 *
 *   <dt>{@code Account} into {@code account.accounts}, from {@code app/cpy/CVACT01Y.cpy}</dt>
 *   <dd>The 300-byte account record. Read by all three migrated batch programs and rewritten by
 *       two of them, so it is the most contended mapping in the package and the one that carries
 *       the version column discussed under failure modes.</dd>
 *
 *   <dt>{@code CardXref} into {@code account.card_xref}, from {@code app/cpy/CVACT03Y.cpy}</dt>
 *   <dd>The 50-byte card cross-reference, three fields and a filler. It resolves a card number
 *       to an account and a customer, and it is reached by two different keys rather than one --
 *       see the boundaries section.</dd>
 *
 *   <dt>{@code Card} into {@code card.cards}, from {@code app/cpy/CVACT02Y.cpy}</dt>
 *   <dd>The 150-byte card record, walked in key order by the export alone -- the fifth phase of the
 *       reference export at {@code app/cbl/CBEXPORT.cbl:513}, emitting one {@code 'D'} record per card
 *       and NOT {@code 'C'}, which belongs to the customer view -- and never written here. Declared
 *       {@code @Immutable}, and the ONLY mapping in this package that reaches the {@code card} schema
 *       at all; the grant admitting it is {@code SELECT} only, at
 *       {@code data-migration/sql/V0__schemas_and_roles.sql:1268}. Five of its six fields are mapped:
 *       the verification value at {@code app/cpy/CVACT02Y.cpy:7} is stored enciphered by the owning
 *       service and is deliberately unmapped, under the same registered divergence as the two customer
 *       identifiers above, and with one addition -- a verification value is sensitive authentication
 *       data that may not be retained after authorisation at all, so the export writes a redacted
 *       constant into that span and the import does the same rather than carrying a value into a
 *       durable artefact. It is not to be confused with {@code CardXref}, which maps a different table
 *       in a different schema.</dd>
 *
 *   <dt>{@code DisclosureGroup} into {@code reference.disclosure_groups}, from
 *       {@code app/cpy/CVTRA02Y.cpy}</dt>
 *   <dd>The 50-byte interest-rate lookup: a three-part key of account group, transaction type
 *       and transaction category at {@code app/cpy/CVTRA02Y.cpy:6-8}, and one rate at
 *       {@code app/cpy/CVTRA02Y.cpy:9}. Read-only from this module, and the source of the abend
 *       described under failure modes.</dd>
 *
 *   <dt>{@code Transaction} into {@code ledger.transactions}, from
 *       {@code app/cpy/CVTRA05Y.cpy}</dt>
 *   <dd>The 350-byte posted-transaction record, written by posting and by interest accrual.</dd>
 *
 *   <dt>{@code DailyTransaction} into {@code ledger.daily_transactions}, from
 *       {@code app/cpy/CVTRA06Y.cpy}</dt>
 *   <dd>The 350-byte daily-transaction input record: the batch input stream, read by the
 *       preflight and by posting, and never written by either.</dd>
 *
 *   <dt>{@code TransactionCategoryBalance} into {@code ledger.transaction_category_balances},
 *       from {@code app/cpy/CVTRA01Y.cpy}</dt>
 *   <dd>The 50-byte per-account, per-type, per-category running balance: a three-part key at
 *       {@code app/cpy/CVTRA01Y.cpy:6-8} and one signed amount at
 *       {@code app/cpy/CVTRA01Y.cpy:9}. Both the create arm and the update arm of the baseline's
 *       category-balance write land on this one mapping, and the two stay separately observable
 *       because the golden masters distinguish them.</dd>
 *
 *   <dt>{@code TransactionReject} into {@code ledger.transaction_rejects}, from the 430-byte
 *       reject contract</dt>
 *   <dd>The one mapping whose contract is not a single copybook. The reject record is a rejected
 *       daily transaction carried whole, followed by a numeric reason and its description.</dd>
 *
 * </dl>
 *
 * <p>Assumptions: the 430 figure is derived rather than assumed, and it is corroborated twice
 * over, which matters because a reject stream written at the wrong length is a file that still
 * opens and still reads. The file description at {@code app/cbl/CBTRN02C.cbl:83-84} declares a
 * 350-byte reject record followed by an 80-byte trailer; working storage at
 * {@code app/cbl/CBTRN02C.cbl:176-182} subdivides that trailer into a 4-digit reason at line 181
 * and a 76-character description at line 182, so the payload is 350 plus 4 plus 76; and the
 * driving job at {@code app/jcl/POSTTRAN.jcl:36} independently declares the dataset as
 * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)}. The mapping therefore keeps the three parts as
 * three columns -- the raw 350-character record, the reason as a small integer, and the
 * 76-character description -- rather than collapsing them into one opaque 430-character blob,
 * because the reason code is queried and the description is compared byte for byte against a
 * committed expectation file.</p>
 *
 * <h2>The five baseline programs this package serves</h2>
 *
 * <p>Baseline paths are cited for provenance only. Nothing under {@code app/**} is read at run
 * time, and nothing under it is modified by this migration or by anything else; the COBOL
 * remains byte-identical and keeps running. Line numbers refer to the source as committed, and
 * columns 73 to 80 of a COBOL or JCL line carry a sequence field that is not part of the
 * statement.</p>
 *
 * <ul>
 *   <li>{@code app/cbl/CBTRN01C.cbl} -- the daily-transaction preflight. Reads
 *       {@code DailyTransaction}, {@code CardXref} and {@code Account}, and writes none of
 *       them.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl} -- transaction posting. The only program that touches
 *       every mapping in this package except {@code DisclosureGroup}, and the only one that
 *       writes {@code TransactionReject}.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} -- interest accrual. Reads
 *       {@code TransactionCategoryBalance} sequentially, {@code CardXref} by account and
 *       {@code DisclosureGroup} by its three-part key, and writes {@code Transaction} and
 *       {@code Account}.</li>
 *   <li>{@code app/cbl/CBEXPORT.cbl} and {@code app/cbl/CBIMPORT.cbl} -- the export and import
 *       round trip, which moves a 500-byte packed-decimal record through flat-file codecs in
 *       {@code com.carddemo.common.codec}. The EXPORT direction reads five masters and so consumes
 *       five mappings from this package -- {@code Customer}, {@code Account}, {@code CardXref},
 *       {@code Transaction} and {@code Card} -- writing one record per row and none of them back.
 *       Refactoring Rationale: this bullet previously said that no entity in this package
 *       participates and that none was missing. That was true of an earlier revision in which two of
 *       the five export phases were held open for want of exactly the two mappings now present, and
 *       it is corrected rather than deleted because a reader who believed it would conclude the
 *       export needs no mapping at all. The IMPORT direction still consumes none: it decodes into
 *       ordered field maps and separates the dataset by discriminator without persisting a row.</li>
 * </ul>
 *
 * <h2>The schema-ownership boundary</h2>
 *
 * <p><b>The two tables in the {@code batch} schema -- {@code batch.batch_run} and
 * {@code batch.daily_feed_watermark} -- are the only tables this module owns.</b> The other nine
 * mappings target tables owned elsewhere: {@code ledger} belongs to {@code transaction-service},
 * {@code account} and {@code card} to {@code account-service} and {@code card-service}, and
 * {@code reference} to {@code reference-service}. This module reaches them through a narrowly-scoped database grant
 * held by a dedicated role, and through nothing else. Reading this package as though it owned
 * eleven tables is the single most consequential misreading available here, which is why the
 * boundary is stated before any mapping detail rather than after it.</p>
 *
 * <p>Assumptions: the grant graph is not created here, and neither are the tables. The schemas,
 * the per-service roles and the cross-schema grants are created by
 * {@code data-migration/sql/V0__schemas_and_roles.sql}; this module's own migrations --
 * {@code services/batch-service/src/main/resources/db/migration/V1__batch.sql} and
 * {@code V2__batch_feed_watermark.sql} -- create {@code batch} objects and only {@code batch}
 * objects, and issue no grant, no revoke, no role and no schema of their own. <b>This package declares mappings only, and creates, alters or seeds
 * no other service's schema.</b> The tables, columns, types and indexes those nine mappings
 * describe are created by the owning services' own migrations, so an annotation here describes a
 * shape that already exists rather than requesting one that does not.</p>
 *
 * <p><b>The nine granted-table mappings are consequently DDL-passive.</b> None of them declares
 * {@code @Table(indexes = ...)}, none declares {@code @Table(uniqueConstraints = ...)}, and none
 * names a key generation strategy that implies sequence or identity DDL. Hibernate is never
 * permitted to emit DDL in this module: schema evolution is Flyway's, and the JPA setting is at
 * most an assertion against the existing shape, never a generator of it. {@code BatchRun} and
 * {@code DailyFeedWatermark} are the two exceptions, because their tables are genuinely owned here
 * -- the ledger's key is an identity column declared by {@code V1__batch.sql} and the watermark's is
 * an assigned feed name declared by {@code V2__batch_feed_watermark.sql} -- and even for those two
 * the migration, not Hibernate, is the source of truth.</p>
 *
 * <p>Assumptions: three indexes this package depends on are declared by other services and must
 * not be re-declared here. {@code idx_card_xref_account_id} is what makes the by-account
 * cross-reference read a keyed lookup rather than a scan, and it is the relational replacement
 * for a VSAM alternate index; {@code idx_transactions_proc_ts} replaces the batch alternate-index
 * path over the posted-transaction processing timestamp; and {@code idx_transactions_card_num}
 * serves the card-ordered reads. A mapping here that re-declared any of the three would be
 * asking Hibernate to create an object that already exists in a schema this module has no right
 * to alter, and the request would surface either as a validation failure or as a permission
 * error, neither of which names the actual mistake.</p>
 *
 * <p>Trade-offs: the cost of DDL passivity is that a column added to {@code ledger} or
 * {@code account} is invisible here until the owning service ships it, so the two repositories
 * of knowledge can disagree for as long as it takes a mapping to catch up. The compensation is
 * that the disagreement is loud rather than silent: a mapping naming a column the schema does
 * not have fails on the assertion pass at start-up, before a single row is read. The alternative
 * -- letting this module generate what it believes -- would resolve the disagreement by
 * overwriting another service's schema from a module that does not own it.</p>
 *
 * <h2>Why the mappings are local rather than borrowed</h2>
 *
 * <p>Nine of the eleven tables mapped here already have an entity somewhere else in the reactor,
 * so declaring a second mapping over the same table looks like duplication and has to be
 * justified as something other than that. Assumptions: the ratio moved from seven-of-eight to
 * nine-of-ten when the customer and card projections landed, and it is nine-of-ELEVEN now that
 * {@code DailyFeedWatermark} is counted -- the denominator moved and the numerator did not, because
 * the watermark is a table this module owns rather than one it borrows. The exceptions are therefore
 * the two {@code batch} mappings, and every other mapping here is a second view of a table another
 * context defines.</p>
 *
 * <p>Alternatives Considered: a Maven dependency on {@code transaction-service},
 * {@code account-service} and {@code card-service}, so that their existing entities could be reused
 * directly. Rejected on
 * two independent grounds. A service module importing another service module's {@code domain}
 * package is forbidden outright, and the prohibition belongs to the layering rules the
 * {@code architecture-rules} Surefire execution in {@code services/pom.xml} selects by the simple
 * name {@code LayeringRulesTest} rather than to a review convention, so the import fails a build
 * rather than drawing a comment. Independently of the test, a compile-time dependency between
 * two independently deployable services reintroduces exactly the coupling that bounded contexts
 * exist to remove: the two would then have to be built, versioned and released together, and a
 * field added for one service's reasons would land in the other's build.</p>
 *
 * <p>Alternatives Considered: extracting the shared entities upward into {@code common-lib} so
 * that one mapping could serve every module that needs it. Rejected because {@code common-lib}
 * deliberately ships no persistence at all -- its own POM declares neither the JPA starter nor a
 * JDBC driver nor Flyway, and its charter forbids it a {@code domain} package -- so
 * {@code jakarta.persistence} is simply not on its classpath, by design and not by omission.
 * Adding it there to serve this module would put an entity mapping into the one artifact every
 * other module depends on, which would make a schema change in one bounded context a rebuild of
 * all nine.</p>
 *
 * <p>The resulting principle is worth stating in one sentence, because it is what makes a shared
 * table stop short of becoming a shared deployable: <b>this module and
 * {@code transaction-service} agree through the physical schema and never through code.</b> The
 * only intra-repository Maven dependency this module declares is {@code common-lib}.</p>
 *
 * <p>Trade-offs: two mappings over one table can drift, and nothing in the compiler will notice.
 * The accepted mitigation is that both are asserted against the same physical schema at start-up
 * and both are compared against the same committed expectation files, so a drift that matters
 * shows up as a failing assertion or a failing byte comparison rather than as a silent
 * divergence. What is genuinely given up is compile-time agreement between the two, and that is
 * the price of independent deployability rather than an oversight.</p>
 *
 * <h2>The one documented exception to database-per-service purity</h2>
 *
 * <p>This package is where that exception becomes visible, because it is the list of tables a
 * batch job may write. Posting updates two schemas it does not own inside a single transaction
 * together with this module's own work, running against one database cluster under a role
 * holding narrowly-scoped cross-schema write grants on {@code ledger} and {@code account} and on
 * nothing beyond them. The migration plan records this at its section 0.4.1.3 as <b>the one
 * documented exception to database-per-service purity in the entire migration</b>, so it is a
 * deliberate, bounded concession rather than a pattern to copy.</p>
 *
 * <p>Alternatives Considered: a transactional outbox with compensating reversal, and a saga, were
 * both evaluated for that unit of work and both rejected on the same concrete ground. The
 * baseline commits three writes together: the posting paragraph at
 * {@code app/cbl/CBTRN02C.cbl:424-444} performs {@code 2700-UPDATE-TCATBAL} at line 440, then
 * {@code 2800-UPDATE-ACCOUNT-REC} at line 441, then {@code 2900-WRITE-TRANSACTION-FILE} at line
 * 442, and reaches line 444 with all three applied or none. Either decomposition replaces that
 * one atomic commit with a sequence of committed steps plus reversals, which makes
 * partial-posting states observable -- a posted transaction whose category balance has not moved,
 * or an account balance updated with no matching transaction row. Those states do not exist in
 * the baseline, so the golden masters would flag them as a parity failure, and they would be
 * right to. Keeping the commit atomic needs no coordinator at all, which makes it both the
 * lower-risk option and the one that preserves observable behaviour.</p>
 *
 * <p>Assumptions: the write set is closed and the grant is what closes it. Of the eleven mappings
 * here, {@code DisclosureGroup}, {@code Customer} and {@code Card} are read-only from this module,
 * and {@code DailyTransaction} is an input stream that no job in this package writes. A mapping in this package that acquired a
 * write path into {@code reference} would be reaching outside the granted set, and would fail on
 * a permission error rather than on anything that names the design mistake.</p>
 *
 * <h2>Two money precisions, and the two places they cross</h2>
 *
 * <p>Two different money precisions meet in this package, and a reader has to know which is
 * which, because the narrower one silently truncates the widest legal value of the wider one
 * rather than raising anything. Both are derived mechanically from the copybook rather than
 * chosen, under the migration plan's transformation rule T1:</p>
 *
 * <ul>
 *   <li><b>Account-side money is {@code PIC S9(10)V99}</b> -- twelve display bytes, ten integer
 *       digits and two decimal places -- mapping to {@code NUMERIC(12,2)} in SQL and
 *       {@code BigDecimal} at scale 2 in Java. Five fields of the account record carry it:
 *       {@code ACCT-CURR-BAL} at {@code app/cpy/CVACT01Y.cpy:7}, {@code ACCT-CREDIT-LIMIT} at
 *       {@code app/cpy/CVACT01Y.cpy:8}, {@code ACCT-CASH-CREDIT-LIMIT} at
 *       {@code app/cpy/CVACT01Y.cpy:9}, {@code ACCT-CURR-CYC-CREDIT} at
 *       {@code app/cpy/CVACT01Y.cpy:13} and {@code ACCT-CURR-CYC-DEBIT} at
 *       {@code app/cpy/CVACT01Y.cpy:14}.</li>
 *   <li><b>Transaction-side money is {@code PIC S9(09)V99}</b> -- eleven display bytes -- mapping
 *       to {@code NUMERIC(11,2)}. Three records carry it: {@code TRAN-AMT} at
 *       {@code app/cpy/CVTRA05Y.cpy:10}, {@code DALYTRAN-AMT} at
 *       {@code app/cpy/CVTRA06Y.cpy:10} and {@code TRAN-CAT-BAL} at
 *       {@code app/cpy/CVTRA01Y.cpy:9}.</li>
 *   <li><b>The interest rate is {@code PIC S9(04)V99}</b> -- six display bytes -- mapping to
 *       {@code NUMERIC(6,2)}. One field carries it: {@code DIS-INT-RATE} at
 *       {@code app/cpy/CVTRA02Y.cpy:9}. It is a rate rather than an amount, so it is the one
 *       numeric here that is not money and is not summed with any.</li>
 * </ul>
 *
 * <p>The two money precisions cross at exactly two sites, and both are additions of a
 * transaction-side amount into an account-side balance. This is where a precision error would
 * first become visible, which is why they are named individually rather than described as a
 * class: {@code app/cbl/CBTRN02C.cbl:547} reads
 * {@code ADD DALYTRAN-AMT  TO ACCT-CURR-BAL}, and {@code app/cbl/CBACT04C.cbl:352} reads
 * {@code ADD WS-TOTAL-INT  TO ACCT-CURR-BAL}. Declaring an account balance at the narrower scale
 * would lose the eleventh integer digit on exactly the accounts most in need of it.</p>
 *
 * <p>Assumptions: money is exact fixed point at every hop and never anything else. The migration
 * plan's transformation rule T3 fixes the representation as {@code NUMERIC(p,2)} in SQL,
 * {@code BigDecimal} at scale 2 in Java, and a JSON string on any wire.
 * {@code float}, {@code double} and bare JSON numbers are forbidden in the money path, and the
 * prohibition belongs to the layering rules the {@code architecture-rules} Surefire execution
 * selects by the simple name {@code LayeringRulesTest} rather than to a review note, so an entity
 * here cannot introduce a {@code double} without failing a build. A JSON number is
 * parsed into an IEEE-754 double by most clients, which destroys exactness at precisely the
 * boundary a user reads. The arithmetic helpers live in {@code com.carddemo.common.money.Money}
 * and are never re-declared per entity, which is the Java analogue of single-sourcing a record
 * layout from one copybook include path.</p>
 *
 * <p>Trade-offs: {@code BigDecimal} at a fixed scale costs allocation and verbosity against a
 * primitive, and the arithmetic has to name a rounding mode at every division rather than
 * inheriting one. That cost is accepted because the failure mode it removes is silent: a posting
 * or accrual figure wrong in the cents still looks entirely plausible, and would be caught only
 * by a byte comparison against a committed expectation file, after the fact.</p>
 *
 * <h2>Baseline names a reader will otherwise mistake for transcription errors</h2>
 *
 * <p>Four naming artifacts in the baseline records and programs look like typing mistakes made
 * during this migration, and none of them is. Each is recorded here so that nobody
 * helpfully corrects a citation into something that no longer matches the file it cites.
 * Everything under {@code app/**} is reference-only, so the framing throughout is the same in
 * every case: <b>the baseline declares one thing, the target column is another, and the
 * divergence is documented.</b> No claim is made anywhere that the baseline itself was
 * altered.</p>
 *
 * <p><b>The misspelled expiration date.</b> {@code app/cpy/CVACT01Y.cpy:11} declares
 * {@code ACCT-EXPIRAION-DATE}, with the {@code T} absent from the third syllable, occupying ten
 * bytes at offset 58 of the account record. The target column is {@code expiration_date}, and the
 * divergence is registered in {@code docs/architecture/data-model-and-schema-mapping.md} together
 * with the two other renames the migration makes. The name is carried unchanged into every
 * citation of that line, including citations that sit beside the corrected column name, because a
 * citation whose text has been tidied no longer locates the byte range it claims to.</p>
 *
 * <p><b>The doubled field prefix.</b> {@code app/cbl/CBACT04C.cbl:67} declares
 * {@code FD-FD-TRAN-CAT-DATA}, with the file-description prefix applied twice. It is the trailing
 * data portion of the category-balance record and it has no counterpart in this package, since
 * the mapping takes its field set from {@code app/cpy/CVTRA01Y.cpy} rather than from a program's
 * own file description.</p>
 *
 * <p><b>Two field names each declared twice in one program.</b> {@code FD-ACCT-DATA} appears at
 * {@code app/cbl/CBACT04C.cbl:87} as {@code PIC X(289)} and again at
 * {@code app/cbl/CBACT04C.cbl:92} as {@code PIC X(334)}, in two different file descriptions, and
 * the same pair recurs at {@code app/cbl/CBTRN01C.cbl:89} and {@code app/cbl/CBTRN01C.cbl:94}.
 * {@code FD-CUST-DATA} is doubled the same way in {@code app/cbl/CBTRN01C.cbl}, at line 69 as
 * {@code PIC X(334)} and at line 74 as {@code PIC X(491)}. A tool that flattens a program into
 * one symbol table collides on both names and reports a redefinition that the compiler never
 * sees, since each name is qualified by its own record.</p>
 *
 * <p>Assumptions: those two widths are more useful than they look, and the mapping relies on
 * them. An eleven-byte account key followed by a 289-byte remainder confirms the 300-byte account
 * record independently of the copybook header that also asserts it, and a sixteen-byte
 * transaction key followed by a 334-byte remainder confirms the 350-byte transaction record the
 * same way. Two independent derivations of each record length matter because a 300-byte record
 * decoded against a 299-byte belief is not a build failure, it is a wrong balance.</p>
 *
 * <p><b>Two near-identical key names, and one field named differently from its copybook.</b>
 * {@code FD-TRAN-ID} and {@code FD-TRANS-ID} differ by one letter and coexist inside a single
 * program: {@code app/cbl/CBTRN02C.cbl:68} declares {@code FD-TRAN-ID} as the key of the
 * daily-transaction file, while {@code app/cbl/CBTRN02C.cbl:73} declares {@code FD-TRANS-ID} as
 * the key of the posted-transaction file, which {@code app/cbl/CBTRN02C.cbl:37} names as that
 * file's {@code RECORD KEY}. The same two spellings recur in
 * {@code app/cbl/CBTRN01C.cbl} at lines 68 and 93, and {@code app/cbl/CBACT04C.cbl:91} uses
 * {@code FD-TRANS-ID}. They are therefore two distinct keys over two distinct files rather than
 * one key spelled two ways, and reading them as a single field conflates the batch input stream
 * with the posted ledger. Separately, {@code app/cbl/CBACT04C.cbl:72} names a field
 * {@code FD-XREF-CUST-NUM} where {@code app/cpy/CVACT03Y.cpy:6} names the same nine-byte range
 * {@code XREF-CUST-ID}; the mapping follows the copybook, and both names are kept as written
 * wherever either is cited.</p>
 *
 * <h2>Dropped {@code FILLER}, and the widths that keep each record reconcilable</h2>
 *
 * <p>Trailing {@code FILLER} is padding to a fixed record length and is not data, so the
 * migration plan's transformation rule T1 drops it and requires the drop be recorded. Recording
 * the width is the point: without it, a reader cannot confirm that a mapping accounts for every
 * byte of its record, and an unnoticed shortfall shifts every field after it. Five records in
 * this package carry one, and each mapped field set plus its dropped filler reconciles to the
 * declared length:</p>
 *
 * <ul>
 *   <li>{@code app/cpy/CVACT01Y.cpy:17} -- {@code FILLER PIC X(178)} at offset 122, so 122 plus
 *       178 is the 300-byte account record.</li>
 *   <li>{@code app/cpy/CVACT03Y.cpy:8} -- {@code FILLER PIC X(14)} at offset 36, so 36 plus 14 is
 *       the 50-byte cross-reference record.</li>
 *   <li>{@code app/cpy/CVTRA01Y.cpy:10} -- {@code FILLER PIC X(22)} at offset 28, so 28 plus 22
 *       is the 50-byte category-balance record.</li>
 *   <li>{@code app/cpy/CVTRA02Y.cpy:10} -- {@code FILLER PIC X(28)} at offset 22, so 22 plus 28
 *       is the 50-byte disclosure-group record.</li>
 *   <li>{@code app/cpy/CVTRA05Y.cpy:18} and {@code app/cpy/CVTRA06Y.cpy:18} -- {@code FILLER PIC
 *       X(20)} at offset 330 in both, so 330 plus 20 is the 350-byte transaction record in each
 *       case.</li>
 * </ul>
 *
 * <p>Assumptions: the posted-transaction and daily-transaction layouts are field for field
 * identical, at the same line numbers 4 through 18 of their respective copybooks, and differ only
 * in the {@code TRAN-} against {@code DALYTRAN-} prefix on every field name. Two mappings exist
 * regardless, because the two records are two tables with two different lifecycles -- one is the
 * input stream and the other is the ledger -- and collapsing them into one shared mapping would
 * make posting a self-referential update on the table it reads from. Their shared offsets are
 * nonetheless worth knowing when reading either: the card number sits at 262, the originating
 * timestamp at 278 and the processing timestamp at 304, and those three are corroborated
 * independently by the sort field positions declared at {@code app/jcl/TRANREPT.jcl:41-42} in
 * one-based form and by the alternate index built at {@code app/jcl/TRANIDX.jcl} over the
 * processing timestamp.</p>
 *
 * <h2>What this package exposes, and why every column decision is parity-observable</h2>
 *
 * <p>What consumers get from this package is nine mappings over tables another service owns and
 * two entities over the two tables this module owns. What makes that a stricter contract than it
 * sounds is that five of the eleven land directly on a committed expectation file, so a column type,
 * length or scale chosen wrongly here does not merely misbehave -- it fails a byte comparison.
 * Under {@code tests/golden/posting/} the pairings are: {@code Account} against
 * {@code acctdat.expected}, {@code Transaction} against {@code tranfile.expected},
 * {@code TransactionCategoryBalance} against {@code tcatbal.expected},
 * {@code TransactionReject} against {@code dalyrejs.expected}, and the exit status a
 * {@code BatchRun} row records against {@code return_code.expected}.</p>
 *
 * <p>Each of those five is present in all nine committed posting scenarios, which is what makes
 * the coverage a boundary sweep rather than a smoke test: {@code boundary_exact_limit},
 * {@code boundary_expiry_equal}, {@code empty_input}, {@code happy_path},
 * {@code reject_100_card_missing}, {@code reject_101_acct_missing},
 * {@code reject_102_overlimit}, {@code reject_103_expired} and {@code zero_balance}. The two
 * boundary scenarios are the ones a mapping can quietly break: a credit limit that no longer
 * compares exactly, or a ten-character date column that no longer orders lexically, changes which
 * side of an inclusive boundary a record falls on.</p>
 *
 * <p>Assumptions: {@code tests/**} and {@code scripts/**} are reference material -- the parity
 * oracle -- and are never modified and never re-pinned by this migration. Tests authored in Java
 * for this package are strictly additive and sit beside that suite rather than replacing any part
 * of it. The oracle keeps its own dependency pins so that its byte comparisons stay reproducible,
 * and re-pinning it to suit a Java module would destroy the only independent check this package
 * has.</p>
 *
 * <p>Trade-offs: the graded numeric scale the oracle uses belongs to the oracle and to
 * the batch container's own process exit status, which the orchestrator reads, and it does not
 * govern anything on the Java side. Maven, the documentation gate, the unit-test runner and the
 * assertion library are each binary: they pass or they fail. Borrowing the oracle's vocabulary to
 * describe a Java build would let a real failure hide behind a tier that reads as acceptable, so
 * the two vocabularies are kept apart deliberately. The cost is that two adjacent parts of one
 * repository count success differently and a reader has to know which is speaking; the cost of
 * not doing it is a build that reports success while failing.</p>
 *
 * <h2>Failure modes a reader must know about</h2>
 *
 * <p>Two failures originate at these mappings, and they are unlike each other in the one way that
 * matters operationally: the first is this module's to handle, and the second is not this
 * module's to fix.</p>
 *
 * <p><b>An optimistic-lock conflict on {@code Account}.</b> That mapping carries a version
 * column, so a concurrent modification between read and write raises an optimistic-lock failure
 * instead of silently overwriting. This is not a modern addition imposed on a baseline that
 * lacked the concept: the baseline implements the same check by hand, snapshotting the complete
 * pre-edit record into the before-image group declared at {@code app/cbl/COACTUPC.cbl:669} and
 * gating its rewrite on the change-flag condition declared at {@code app/cbl/COACTUPC.cbl:521}.
 * The version column is the native expression of a pattern already present in the COBOL, which is
 * why it is not registered as a divergence.</p>
 *
 * <p>Assumptions: the conflict is genuinely reachable from a batch job and is not a theoretical
 * concern inherited from the online path. Both posting and interest accrual rewrite the account
 * record -- at {@code app/cbl/CBTRN02C.cbl:547} and at {@code app/cbl/CBACT04C.cbl:352}
 * respectively -- and the online services write the same rows. A job that treated the failure as
 * unreachable would abandon a nightly step over a contention event that the baseline resolved by
 * re-reading.</p>
 *
 * <p><b>A missing {@code 'DEFAULT'} disclosure-group row aborts the interest job outright, and
 * the row is not seeded here.</b> The baseline's fallback is narrower than it appears.
 * {@code app/cbl/CBACT04C.cbl:437} moves the literal {@code 'DEFAULT'} into the account-group
 * component of the lookup key alone, leaving the transaction type and category components as they
 * were, and then retries. The retry paragraph spans
 * {@code app/cbl/CBACT04C.cbl:443-460}: the read at line 444 carries <b>no</b>
 * {@code INVALID KEY} clause, and the status test at line 446 accepts <b>only</b> {@code '00'},
 * so a key that is still absent after the substitution takes the failure arm and reaches the
 * abend at {@code app/cbl/CBACT04C.cbl:455-458}. There is no third fallback and no default rate
 * compiled into the program.</p>
 *
 * <p>Assumptions: those rows are reference data, and {@code reference-service} owns and seeds
 * them through its own migration. This module holds {@code DisclosureGroup} as a read-only
 * mapping over a table in a schema it did not create. The consequence for an operator is the
 * whole reason this paragraph exists: an interest run that aborts on a missing disclosure group
 * is <b>not a defect in this module</b>, and time spent reading this package looking for the
 * cause is time spent in the wrong service.</p>
 *
 * <p>Assumptions: the exit status a {@code BatchRun} row records is narrower than the column's
 * own admitted range, because only one program in this package has a statement that can produce
 * the middle tier. {@code app/cbl/CBTRN02C.cbl:229} tests whether the reject counter exceeded
 * zero and line 230 moves 4 into {@code RETURN-CODE}; that is the sole origin of the soft-warn
 * tier. The string {@code RETURN-CODE} appears <b>nowhere at all</b> in
 * {@code app/cbl/CBACT04C.cbl}, and nowhere in {@code app/cbl/CBTRN01C.cbl} either. An interest
 * run therefore records 0, a posting run records 0 or 4, and a run that recorded 4 from interest
 * accrual would be inventing a tier the baseline has no statement to produce. The hard-failure
 * tier, 8 and above, comes from an abend or an input-output failure rather than from a business
 * outcome.</p>
 *
 * <h2>Boundaries this package does not cross</h2>
 *
 * <p><b>The {@code Customer} and {@code Card} mappings exist for the export job and for nothing
 * else, and no job in the posting, interest or preflight chain reads either.</b> The distinction
 * matters because it is easy to reach the wrong conclusion in either direction here. Reading only
 * the file section of the preflight program suggests both are needed when they are not:
 * {@code app/cbl/CBTRN01C.cbl} opens six files, at lines 157 to 162, and closes the same six, at
 * lines 188 to 193, so a reader counting open statements concludes that six records are in play.
 * The loop body at lines 164 to 186 tells a different story -- the only read statements in the
 * entire program are at line 203 for the daily transaction, line 229 for the cross-reference and
 * line 243 for the account, so the customer, card and posted-transaction files are opened and
 * closed without a single record ever being read from any of them. Nothing in that chain justifies
 * either mapping.</p>
 *
 * <p>Refactoring Rationale: this passage previously concluded from that reading that there was no
 * {@code Customer} mapping and no {@code Card} mapping and that neither was missing. The premise was
 * sound and the conclusion was too broad, because it reasoned from the preflight program alone over
 * a package four jobs draw on. The export job's contract is set by
 * {@code app/cbl/CBEXPORT.cbl}, which writes <b>five</b> record types -- customer, account,
 * cross-reference, transaction and card -- and a Java export that emitted three of them produced an
 * artefact a conforming importer would read as an export with two of its five types missing, while
 * reconciling its own counts and reporting a clean run. Closing that gap needs a read seam per type,
 * which is what these two mappings are. They are declared {@code @Immutable}, they carry no write
 * path, and they deliberately omit every protected column, so the narrow reading the previous
 * passage was protecting -- that this package acquires no write path a grant does not cover -- still
 * holds exactly.</p>
 *
 * <p>Assumptions: both mappings omit their schema's protected columns rather than mapping and
 * ignoring them. {@code account.customers.ssn_encrypted} and
 * {@code account.customers.govt_issued_id_encrypted} and {@code card.cards.cvv_encrypted} hold
 * envelopes sealed under the Aurora customer-managed key, and this module holds no decrypt grant
 * for that key and must not acquire one; a field that is not mapped cannot be selected, so the
 * omission is what makes the absence of the grant unnecessary rather than merely unused. The export
 * artefact carries a redacted constant in each of those three spans, which keeps every following
 * field at its declared offset.</p>
 *
 * <p>Assumptions: {@code TransactionCategoryBalance} is read in key order, and the ordering is
 * part of the contract rather than an incidental property of the storage.
 * {@code app/cbl/CBACT04C.cbl:28-32} declares the category-balance file with
 * {@code ACCESS MODE  IS SEQUENTIAL} over an indexed organization, so records arrive in key
 * sequence, and the interest control break at {@code app/cbl/CBACT04C.cbl:194} -- which detects a
 * change of account and flushes the accumulated interest -- depends entirely on that. A
 * relational read returns rows in no guaranteed order unless one is requested, so the repository
 * layer orders explicitly by the key columns. An unordered read does not fail; it interleaves
 * accounts, breaks the control break into fragments and accrues a partial figure per fragment,
 * which is a wrong number rather than an error.</p>
 *
 * <p>Assumptions: {@code CardXref} is reached by two live keys, not one, so its mapping needs a
 * by-account query as well as a keyed read on its primary key. Posting reads it by the base key,
 * the card number. Interest accrual reads it by account instead: the file is declared with
 * {@code ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID} at {@code app/cbl/CBACT04C.cbl:38}, the read at
 * {@code app/cbl/CBACT04C.cbl:394-395} names that alternate key explicitly, and the driving job
 * mounts the alternate-index path as a second data definition at
 * {@code app/jcl/INTCALC.jcl:31-32}. Supporting only the primary key would leave interest accrual
 * scanning the whole cross-reference once per account.</p>
 *
 * <p><b>{@code BatchRun} is a documented improvement over the baseline, not a port of an existing
 * capability</b>, and the distinction matters when someone asks which behaviour parity testing is
 * entitled to assume. No checkpoint or restart contract existed to port: the only
 * {@code RESTART=} anywhere in the thirty-eight jobs of {@code app/jcl/} is commented out, at
 * {@code app/jcl/DEFGDGD.jcl:2}, and no {@code CHKPT=} appears in that tree at all, so a failed
 * baseline run was resumed by an operator editing and resubmitting the job. The
 * {@code batch.batch_run} ledger gives each step a durable idempotency key so that a resumed step
 * which already completed is a no-op rather than a second application of the same work.
 * Presenting this as a port would misrepresent the baseline; presenting it as an improvement is
 * accurate.</p>
 *
 * <p><b>No repository, no service, no transfer object and no mapper belongs in this package.</b>
 * Data access lives in {@code com.carddemo.batch.repository}, transcribed rules in
 * {@code com.carddemo.batch.service}, job arguments and results in
 * {@code com.carddemo.batch.dto}, and every representation concern in
 * {@code com.carddemo.batch.mapper}. A type here that reached into any of the four would put an
 * entity in a position to depend on the layer that depends on it, and the layering test would
 * fail the build rather than leave the inversion to a reviewer.</p>
 *
 * <h2>Where to read next</h2>
 *
 * <ul>
 *   <li>{@code docs/CODE_DOCUMENTATION_STANDARD.md} -- the governing written documentation
 *       convention for this repository, and the prose standard the Java gate partially
 *       mechanises.</li>
 *   <li>{@code docs/architecture/data-model-and-schema-mapping.md} -- the field-by-field mapping
 *       record: every column's type, length and scale beside the copybook field it derives from,
 *       every dropped {@code FILLER}, and the three renames the migration makes.</li>
 *   <li>{@code docs/architecture/cobol-to-service-traceability.md} -- the register of documented
 *       behavioural divergences, and the authoritative matrix from baseline artifact to migrated
 *       type.</li>
 *   <li>{@code com.carddemo.batch.package-info} -- the module charter, which fixes the
 *       seven-subpackage shape, the exit-status contract and the invariants this package
 *       inherits.</li>
 * </ul>
 */
package com.carddemo.batch.domain;
