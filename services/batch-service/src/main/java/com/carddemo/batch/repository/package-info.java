/**
 * Data access for the batch bounded context: the one seam through which a migrated batch job
 * reaches persistent state.
 *
 * <h2>Purpose</h2>
 *
 * <p>The package root is {@code com.carddemo.batch}, and {@code repository} is the data-access
 * layer within the seven-subpackage shape that {@code com.carddemo.batch.package-info} fixes and
 * declares closed: {@code job}, {@code service}, {@code repository}, {@code domain}, {@code dto},
 * {@code mapper} and {@code config}. A type here names a query and returns rows. It holds no
 * business rule, because a transcribed COBOL paragraph becomes a named method in
 * {@code com.carddemo.batch.service}, and that separation is what keeps a paragraph citable
 * against exactly one Java method in the traceability matrix. It holds no record-representation
 * concern either, because declared widths, sign overpunch, packed decimal, dropped
 * {@code FILLER} and the baseline field spellings are confined to
 * {@code com.carddemo.batch.mapper}.</p>
 *
 * <p><b>Every file verb of the baseline lands here and nowhere else.</b> That is the whole
 * constraint this charter exists to hold: no job definition, no service class and no mapper
 * issues a query of its own, so the set of ways this module can touch a row is enumerable by
 * reading one package.</p>
 *
 * <h2>Why this charter exists, and the form it takes</h2>
 *
 * <p>Assumptions: the project's Explainability rule attaches a docstring obligation to every
 * module entry point, and in Java the entry point of a package is its package declaration, which
 * only a {@code package-info.java} can carry -- so this file is load-bearing rather than
 * decorative. Two Checkstyle modules enforce that independently and neither is redundant.
 * {@code JavadocPackage} inspects the file set and requires this file to exist in any directory
 * holding an audited source file; {@code MissingJavadocPackage} inspects the parsed tree and
 * requires it to carry Javadoc. A charter reduced to a bare package statement satisfies the first
 * and fails the second, which is why prose is the deliverable and this file's mere existence is
 * not.</p>
 *
 * <p>Assumptions: no parameter, return or exception clause appears anywhere below, and the
 * omission is the compliant reading of that rule rather than a departure from it. A package
 * declaration accepts no argument, yields no value and raises nothing, so there is no such thing
 * here to document; an invented tag added for the appearance of completeness would carry an empty
 * body, which {@code NonEmptyAtclauseDescription} reports as a violation in its own right. What
 * the rule does ask of a construct with no signature is a purpose discharged thoroughly, which is
 * what the sections below are.</p>
 *
 * <p>Assumptions: this compilation unit holds one statement, so the rationale the rule's
 * inline-comment half asks for has no adjacent executable line to sit beside and is carried inside
 * this block under the four canonical labels -- the only placement a package makes available. The
 * written convention every block here follows is {@code docs/CODE_DOCUMENTATION_STANDARD.md},
 * cited by path and never restated.</p>
 *
 * <h2>Target contract, and the tree state at the checkpoint that authored it</h2>
 *
 * <p>Assumptions: every inventory, interface name and count in this charter describes the
 * package's <b>target contract</b> as the migration plan assigns it, and each entry additionally
 * states whether it is <b>AUTHORED</b> or <b>PLANNED</b> as of the latest revision of this file.
 * The migration lands its artifacts in plan order, so a count below is a target total rather than
 * a measurement of the directory, while the per-entry marker is a measurement and is expected to
 * move as files land.</p>
 *
 * <p>Refactoring Rationale: every entry below once read PLANNED unconditionally, on the reasoning
 * that this charter was authored before any interface beside it. That stopped being true the
 * moment the first interface landed, and a blanket marker cannot record the difference: with
 * {@code BatchRunRepository} present in this directory the inventory described a package that no
 * longer existed, and a reader could not tell the two states apart without listing the directory
 * themselves -- which is exactly the work a charter exists to save. The markers are therefore
 * per-entry, so a landed interface and a genuinely future one read differently.</p>
 *
 * <p>Alternatives Considered: withholding this charter until every interface it governs exists.
 * Rejected, because the charter is what the authors of those interfaces work from -- which type
 * belongs here, which may not, and what the closed set is -- so writing it last would leave the
 * package with no stated contract during exactly the interval in which one is needed. The cost of
 * authoring it first is that its inventory reads as present tense unless the distinction is
 * declared, which is what the per-entry markers above are for; they are the single place a reader
 * has to look to tell a target from a measurement.</p>
 *
 * <h2>Why data access is an interface here and was a file verb there</h2>
 *
 * <p>The migration plan designates the Repository pattern as the replacement for the direct CICS
 * and COBOL file verbs at its section 0.4.3, and fixes this package's shape at its section 0.4.1.2
 * as one Spring Data JPA interface per aggregate. The verb-level mapping is AAP transformation
 * rule T5: a {@code READ}, {@code WRITE}, {@code REWRITE} or {@code DELETE} becomes a repository
 * method, and a {@code STARTBR} plus {@code READNEXT} plus {@code READPREV} plus {@code ENDBR}
 * browse collapses into one keyset query rather than four calls and a cursor.</p>
 *
 * <p>Assumptions: two rule namespaces meet in this file and they are unrelated. The project's
 * single user-specified rule is the Explainability rule, which is why this charter exists at all.
 * The migration plan's transformation rules are numbered T1 through T10, and rule T5 above is one
 * of those. A citation of the form "rule T5" always means the transformation rule and never the
 * user-specified one; the two are never abbreviated to a bare number in this package.</p>
 *
 * <p>The reason a typed interface is the right replacement is not that it is a more modern
 * spelling of the same thing. It is that <b>the baseline declared its access mode per file, in the
 * {@code FILE-CONTROL} paragraph, and the same cluster was declared differently by different
 * programs.</b> Posting opens its daily-transaction feed at
 * {@code app/cbl/CBTRN02C.cbl:29-32} as {@code ORGANIZATION IS SEQUENTIAL} with
 * {@code ACCESS MODE IS SEQUENTIAL}, and opens the category-balance cluster twenty-eight lines
 * later, at {@code app/cbl/CBTRN02C.cbl:57-61}, as {@code ORGANIZATION IS INDEXED} with
 * {@code ACCESS MODE IS RANDOM}. Interest accrual then opens <b>that same cluster</b> at
 * {@code app/cbl/CBACT04C.cbl:28-32} as {@code ORGANIZATION IS INDEXED} with
 * {@code ACCESS MODE IS SEQUENTIAL}. One physical dataset, two access disciplines, decided by
 * whichever program happened to open it.</p>
 *
 * <p>Refactoring Rationale: that arrangement puts the access discipline in the program rather than
 * at the data, so the question "how is this cluster read" has as many answers as there are
 * programs, and each answer is a property of a file-control entry sitting ninety lines above the
 * statement that depends on it. A typed interface per aggregate inverts that: the random keyed
 * read and the ordered walk both become named, separately documented methods on one interface, so
 * both disciplines are visible in one place and neither is implied by a declaration made
 * elsewhere.</p>
 *
 * <h2>The eight interfaces, and what does not belong here</h2>
 *
 * <p>Eight interfaces belong here and no ninth; with this charter that makes nine compilation
 * units at the target. Each is listed with the schema-qualified table it reaches and the access
 * discipline that table is reached with, because the discipline is the part a caller cannot infer
 * from the name.</p>
 *
 * <p>Refactoring Rationale: each entry states LANDED, and at this revision ALL EIGHT are, so the roster
 * and the directory now hold the same set. Two earlier revisions both understated it. The first marked
 * every entry PLANNED, including {@code BatchRunRepository} whose file was already present, so a reader
 * consulting the roster to learn whether the step ledger had an interface was told it did not. The second
 * corrected that to THREE and left five marked PLANNED, three of which -- the feed, the ledger and the
 * reject stream -- were already present as well. Both understatements had the same cause: the count was
 * written from what the checkpoint had just added rather than measured against the directory. It is
 * measured now, and it is re-measured whenever a file is added.</p>

 *
 * <p>Assumptions: the last two to arrive, {@code AccountRepository} and {@code CardXrefRepository}, came
 * with the jobs that read them rather than with a service, which is what the earlier revisions predicted:
 * the account master and the cross-reference are read by the posting job and by the interest accrual, and
 * neither is reached by any rule in {@code com.carddemo.batch.service}. Every one of the eight now has at
 * least one production caller, and each caller is a job or a service in this module rather than a
 * test.</p>
 *
 * <dl>
 *   <dt>{@code BatchRunRepository} into {@code batch.batch_run}</dt>
 *   <dd>LANDED. The <b>only</b> table this module owns, and the only entry here with no baseline
 *       record behind it. It is the durable step ledger, and it is the mechanism <i>intended</i> to
 *       make a resumed step idempotent: a step that already recorded completion for a run is meant
 *       to become a no-op rather than repeating its writes. Assumptions: the uniqueness constraint
 *       that makes a row an idempotency key is {@code uq_batch_run_run_step} over the run and step
 *       pair, declared by
 *       {@code services/batch-service/src/main/resources/db/migration/V1__batch.sql} and mirrored
 *       on the {@code BatchRun} mapping, so a duplicate insert is refused by the database rather
 *       than by a prior read. Trade-offs: the repository and its table are landed, but the
 *       idempotency they describe is <b>not operative yet</b>, because the only class that reads or
 *       writes them -- {@code BatchStepLedger} in the sibling service package -- has no production
 *       caller while the job beans remain unauthored. Two consequences are worth recording here
 *       rather than at the call site that does not exist: the restart guarantee that actually holds
 *       today is the coarser one Spring Batch gives from the identifying business-date job
 *       parameter, and the same uniqueness constraint named above will <i>reject</i> the retry
 *       insert that {@code BatchStepLedger} performs after a failed attempt unless the caller
 *       removes the terminal row first, which is the caller-side obligation its own comment
 *       records and which no caller yet discharges.</dd>
 *
 *   <dt>{@code DailyTransactionRepository} into {@code ledger.daily_transactions}</dt>
 *   <dd>LANDED. Read-only from this module, and reached by an ordered forward walk rather than by
 *       key. It is the batch input stream: the preflight and posting both read it and neither
 *       writes it, which is why no write method belongs on this interface at all.</dd>
 *
 *   <dt>{@code TransactionRepository} into {@code ledger.transactions}</dt>
 *   <dd>LANDED. Write plus one ordered walk. Posting and interest accrual both insert here, and
 *       the combine step reads the table in transaction-identifier order under the contract
 *       recorded below.</dd>
 *
 *   <dt>{@code TransactionCategoryBalanceRepository} into
 *       {@code ledger.transaction_category_balances}</dt>
 *   <dd>LANDED. A keyed read on the three-part composite key, an ordered walk over that same key,
 *       and both arms of the create-versus-update branch kept separately observable. Assumptions:
 *       the two arms must stay distinguishable rather than collapsing into one upsert, because the
 *       distinction is observable behaviour in the baseline and the golden masters compare it;
 *       the owning migration records the same requirement at its composite-key constraint, and
 *       deliberately declares no default, no generated value and no conflict-resolution helper so
 *       that a caller can still tell an insert from an update.</dd>
 *
 *   <dt>{@code TransactionRejectRepository} into {@code ledger.transaction_rejects}</dt>
 *   <dd>LANDED <b>in this package</b>, and the qualification is load-bearing: the owning ledger
 *       context has already authored an interface of the same name over the same table, at
 *       {@code services/transaction-service/src/main/java/com/carddemo/transaction/repository/TransactionRejectRepository.java}.
 *       The two are distinct types in distinct packages reached through distinct schema grants --
 *       that context writes as {@code carddemo_ledger}, this module as {@code carddemo_batch} under
 *       the scoped cross-schema grant recorded below -- so the existence of theirs neither supplies
 *       nor replaces this one. Append-only: an insert method and nothing else. Assumptions: the table's key is
 *       the surrogate sequence {@code reject_seq} rather than any part of the rejected record, so
 *       a reject carries no natural key this module could read it back by, and no finder is
 *       offered because none would have a caller. The reject stream is written and then compared
 *       byte for byte against a committed expectation file; it is never re-read by the run that
 *       produced it.</dd>
 *
 *   <dt>{@code AccountRepository} into {@code account.accounts}</dt>
 *   <dd>LANDED. A keyed read and an update. This is the most contended table the module touches:
 *       all three migrated batch programs read it and two of them rewrite it, so it is the one
 *       whose optimistic-concurrency version column the update path has to respect.</dd>
 *
 *   <dt>{@code CardXrefRepository} into {@code account.card_xref}</dt>
 *   <dd>LANDED. Read-only, and <b>reached by two distinct access paths rather than one</b> --
 *       by card number, and by account identifier. Assumptions: the second path is not an
 *       invention of the migration. {@code app/cbl/CBACT04C.cbl:37} declares
 *       {@code RECORD KEY IS FD-XREF-CARD-NUM} and the very next line,
 *       {@code app/cbl/CBACT04C.cbl:38}, declares
 *       {@code ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID}; the interest job then reads by account
 *       at {@code app/cbl/CBACT04C.cbl:204-205}. Its driver corroborates this independently by
 *       supplying two definitions for one dataset, the base cluster at
 *       {@code app/jcl/INTCALC.jcl:29-30} and the alternate-index path at
 *       {@code app/jcl/INTCALC.jcl:31-32}. The relational replacement for that alternate index is
 *       the non-unique index {@code idx_card_xref_account_id}, which the owning service declares
 *       and this module must not re-declare.</dd>
 *
 *   <dt>{@code DisclosureGroupRepository} into {@code reference.disclosure_groups}</dt>
 *   <dd>LANDED. Strictly read-only: an interest-rate lookup on the three-part key of account
 *       group, transaction type and transaction category. No write method belongs here under any
 *       circumstances, and the reason is a grant rather than a convention -- see the schema
 *       ruling below, which extends this module no write privilege on {@code reference} at
 *       all.</dd>
 * </dl>
 *
 * <p><b>What does not belong in this package.</b> No repository implementation class. No
 * {@code RepositoryCustom} fragment interface and no {@code RepositoryImpl} companion. No class
 * carrying the {@code Repository} stereotype annotation, because Spring Data already registers a
 * proxy for each interface and a second bean declaring the same role would compete with it. No
 * data-transfer record, no mapper, no service, no job definition and no entity: each of those has
 * its own subpackage in the closed seven, and the parent charter is the authority on which.</p>
 *
 * <p>Alternatives Considered: splitting each interface into a derived-query half plus a
 * hand-written custom fragment, which is the conventional shape once a query stops being
 * expressible as a method name. Rejected on evidence rather than on taste: every query this
 * package needs -- a keyed read, an ordered forward walk, a continuation finder bounded by a key,
 * an insert and an update -- is expressible as a derived method or as a JPQL query on the
 * interface itself, so a fragment would add an implementation class, a naming convention and a
 * second place to look without removing anything. The migration plan settles the same point from
 * the other direction at its section 0.4.1.2, which fixes this package as interfaces. Should a
 * future query genuinely exceed both forms, the fragment is added then, with its own recorded
 * rationale, rather than pre-emptively now.</p>
 *
 * <p>Two absences a reader will otherwise wonder about are recorded here rather than left to be
 * rediscovered.</p>
 *
 * <p>Assumptions: <b>no customer repository and no card repository.</b> The preflight program
 * looks as though it needs both, because it opens six files in succession at
 * {@code app/cbl/CBTRN01C.cbl:157-162} -- the customer file at line 158, the card file at line 160
 * and the transaction file at line 162 among them. Its processing loop at
 * {@code app/cbl/CBTRN01C.cbl:164-186} then reads only three: the daily feed at line 166, the
 * cross-reference at line 172 and the account at line 176. The customer, card and transaction
 * files are opened, held and closed again at {@code app/cbl/CBTRN01C.cbl:189-193} without ever
 * being read. A repository for either would therefore have no caller. The grant graph settles it
 * independently: the migration plan extends this module cross-schema write access on
 * {@code ledger} and {@code account} only, so there is no privilege on the {@code card} schema
 * for a card repository to use even if one were written.</p>
 *
 * <p>Assumptions: <b>no wrapper around the Spring Batch job repository.</b> The framework supplies
 * its own, and its tables live in the {@code batch} schema alongside this module's own table --
 * the job-instance, job-execution, job-execution-parameter, step-execution and both
 * execution-context tables are created by the same migration that creates
 * {@code batch.batch_run}. They are framework-managed: the sibling {@code config} package wires
 * the job repository to them, and nothing in this package reads or writes them. The distinction
 * matters because the two ledgers answer different questions -- the framework's records what the
 * framework did, and {@code batch.batch_run} records what the invoking state machine needs in
 * order to decide whether a redriven step has already happened.</p>
 *
 * <h2>Why this package reaches three schemas it does not own</h2>
 *
 * <p>Seven of the eight interfaces target a table another bounded context owns. That is the single
 * most consequential fact about this package, so it is stated before any query detail rather than
 * after it: {@code ledger} belongs to {@code transaction-service}, {@code account} to
 * {@code account-service} and {@code reference} to {@code reference-service}. This module reaches
 * them against the one database cluster under a dedicated role holding narrowly-scoped
 * cross-schema write grants on {@code ledger} and {@code account}, read access on
 * {@code reference}, and nothing beyond that. The migration plan records this at its section
 * 0.4.1.3 as <b>the one documented exception to database-per-service purity in the entire
 * migration</b>, which makes it a bounded concession and not a pattern to copy.</p>
 *
 * <p>The fact that forces the exception is one paragraph of COBOL. Posting's
 * {@code 2000-POST-TRANSACTION} paragraph begins at {@code app/cbl/CBTRN02C.cbl:424} and performs
 * three writes in immediate succession: the category-balance update at
 * {@code app/cbl/CBTRN02C.cbl:440}, the account update at {@code app/cbl/CBTRN02C.cbl:441} and the
 * transaction write at {@code app/cbl/CBTRN02C.cbl:442}. <b>There is no commit verb anywhere
 * between them, nor between the last of them and the paragraph exit at
 * {@code app/cbl/CBTRN02C.cbl:444}.</b> The paragraph is reached with all three applied or with
 * none, and those two are the only states the baseline can be observed in.</p>
 *
 * <p>Alternatives Considered: a saga, with each of the three writes committed independently and
 * compensating reversals on failure. Rejected on parity rather than on preference. A saga replaces
 * one atomic commit with a sequence of committed steps, and each interval between them is a state
 * the baseline cannot produce -- a posted transaction whose category balance has not moved, or an
 * updated account balance with no matching transaction row. The golden-master comparison would
 * flag such a state as a parity failure and would be right to, because parity here is defined
 * against committed output bytes and not against an eventual convergence.</p>
 *
 * <p>Alternatives Considered: a transactional outbox with compensating reversal, which reaches the
 * same decomposition through a different trigger. Rejected for the same reason and it is worth
 * saying why the different trigger does not help: the outbox makes the follow-on writes reliable,
 * not simultaneous. Reliability is not the property under test. The property under test is that no
 * intermediate combination is ever observable, and an outbox guarantees the opposite -- that the
 * intermediate state exists and is later resolved.</p>
 *
 * <p>Alternatives Considered: leaving each schema to its owner and having this module call the
 * owning service over HTTP for the writes it does not own, which is the decomposition a reader is
 * most likely to expect. Rejected because a remote call cannot enlist in the local transaction. A
 * synchronous hop commits on the far side independently of this side, so the three writes would no
 * longer be able to commit or roll back together, and the failure mode would be exactly the
 * partial posting the two rejections above exist to prevent -- reached this time by a network
 * timeout rather than by a design choice.</p>
 *
 * <p>Trade-offs: the accepted cost is that this package maps tables it does not own, so the owning
 * service's migration is normative and this one declares nothing about their shape. No interface
 * here contributes a table, an index or a constraint for {@code ledger}, {@code account} or
 * {@code reference}. The authorities are
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} for
 * {@code ledger}, {@code services/account-service/src/main/resources/db/migration/V1__account.sql}
 * for {@code account}, and {@code reference-service}'s own
 * {@code V1__reference.sql} for {@code reference}. A query here that names something those files
 * do not declare is this package's defect and never theirs.</p>
 *
 * <p>Trade-offs: the grant is also the boundary that keeps the concession bounded, which is why it
 * is worth stating what it does not include. It carries no privilege on the {@code card},
 * {@code auth} or {@code authorization} schemas, and no write privilege on {@code reference}. The
 * eight interfaces above are therefore not merely the set that has been written; they are close to
 * the set that <b>could</b> be written, because a ninth reaching an ungranted schema would fail at
 * the database rather than compile and quietly widen the exception.</p>
 *
 * <h2>No native SQL: every query binds to a declared property name</h2>
 *
 * <p><b>This package authors no native SQL.</b> Every query is a Spring Data derived method or a
 * JPQL query written over property paths, and every ordering is a JPQL ordering clause over
 * properties or a {@code Sort} built from property names. No physical column name appears as raw
 * text in this package, and no ordering is expressed as a column string.</p>
 *
 * <p>Alternatives Considered: native SQL, which is the obvious reach for a batch module because a
 * set-based statement is the natural expression of a nightly pass and reads more directly than a
 * derived method name. Rejected on a concrete failure mode rather than on style. Native SQL binds
 * to physical column names, and <b>the physical spelling of the same logical field is not uniform
 * across the schemas this one package reaches.</b> The transaction type code is {@code type_cd} in
 * {@code ledger.transactions} and in {@code ledger.transaction_category_balances}, at
 * {@code V1__ledger.sql:133} and {@code V1__ledger.sql:816}, but it is {@code tran_type_cd} in
 * {@code reference.disclosure_groups}, at {@code V1__reference.sql:313}. The category code is
 * {@code category_cd} in those same two ledger tables, at {@code V1__ledger.sql:147} and
 * {@code V1__ledger.sql:822}, and {@code tran_cat_cd} in the reference table, at
 * {@code V1__reference.sql:319}. The amount column diverges within a single schema: it is
 * {@code amount} in {@code ledger.transactions} at {@code V1__ledger.sql:198} and
 * {@code balance} in {@code ledger.transaction_category_balances} at
 * {@code V1__ledger.sql:869}.</p>
 *
 * <p>Assumptions: those spellings genuinely meet inside one method rather than staying in separate
 * files, which is what makes the divergence a live hazard and not a curiosity. Interest accrual
 * walks the category balances and assembles a disclosure-group key in the same loop iteration --
 * it takes the next balance at {@code app/cbl/CBACT04C.cbl:190} and begins assembling the
 * disclosure-group key at {@code app/cbl/CBACT04C.cbl:210}. The migrated equivalent therefore has
 * a ledger predicate and a reference predicate within a few lines of each other, and a native
 * predicate carried from the first to the second names a column that does not exist.</p>
 *
 * <p>Assumptions: the timing of that failure is the decisive part, and it turns on how the
 * start-up assertion pass actually works. This module runs with Hibernate's schema handling set to
 * {@code validate} at {@code services/batch-service/src/main/resources/application.yml:657}, and
 * to {@code none} under the test profile at
 * {@code services/batch-service/src/test/resources/application-test.yml:250}. That assertion pass
 * reads <b>mapping metadata</b> -- the property-to-column bindings the entity declares -- so a
 * property path that resolves to a column the schema does not have is reported at start-up, before
 * a single row is read. It does not parse the text of a native query, under any profile. A wrong
 * column name inside a native statement is therefore invisible to it and surfaces only when that
 * statement executes: inside a batch step, part-way through a nightly pass, with earlier steps
 * already committed. Binding to declared property names moves the same mistake to start-up, and
 * moves the subset of it that a derived method name can express to compilation.</p>
 *
 * <p>Trade-offs: the cost is that a genuinely set-shaped operation is expressed less directly than
 * one statement of SQL would express it, and a reader who wants to know the emitted statement has
 * to read the mapping to find it. That is accepted because the failure it removes is the expensive
 * kind: silent until execution, and expensive precisely because a nightly chain has already
 * committed work by the time the step that fails is reached.</p>
 *
 * <p>Assumptions: the binding surface for every interface here is the set of property names
 * <b>declared in the sibling entity file</b>, and the author of an interface is expected to open
 * that file and read them rather than infer them from this charter or from the column name. The
 * two do not track each other reliably: the disclosure-group key components are declared as
 * {@code acctGroupId}, {@code tranTypeCd} and {@code tranCatCd} while the corresponding ledger
 * properties are {@code typeCd} and {@code categoryCd}, so the property spelling diverges in the
 * same places the column spelling does. Guessing a property name from a column name is the way a
 * derived method silently fails to resolve.</p>
 *
 * <h2>No offset pagination: forward cursors and keyset continuation</h2>
 *
 * <p>Prohibited package-wide, by name so that the prohibition is searchable: the Spring Data
 * {@code Pageable} and {@code Page} types, and the vocabulary {@code offset}, {@code skip},
 * {@code page}, {@code pageNumber}, {@code pageSize}, {@code totalPages}, {@code totalElements}
 * and {@code totalCount}. None of them appears on a method, a parameter or a return type in this
 * package.</p>
 *
 * <p>Alternatives Considered: offset pagination, which every one of those names belongs to.
 * Rejected because it does not preserve the behaviour it would replace. An offset-based read
 * re-executes its ordering on each call and counts rows from the start, so a row inserted or
 * removed ahead of the current position shifts every later row: a concurrent insert makes an
 * unread row skip past the boundary and a concurrent delete makes an already-read row repeat. A
 * VSAM browse by key has no such behaviour, because it resumes from a key rather than from a
 * count. Since this module's correctness is judged by comparing committed output against a
 * golden master, a read discipline that can skip or repeat a row under concurrency is not a
 * near-equivalent of the baseline; it is a different program.</p>
 *
 * <p>Trade-offs: a keyset read cannot report a total row count without a second query, so the
 * count is simply not offered. Nothing observable is lost, because the baseline never reported one
 * either -- a browse discovers the end by reaching it. The accepted cost is that a caller wanting
 * a progress percentage cannot have one from this package.</p>
 *
 * <p>Two shapes replace it, and they are not interchangeable.</p>
 *
 * <ul>
 *   <li><b>Forward-only cursors</b> -- a {@code Stream} return with an explicit ordering and a
 *       fetch-size hint, for the two ordered walks recorded below. Assumptions: this is the direct
 *       analogue of a COBOL {@code ACCESS MODE IS SEQUENTIAL} read loop, which reads to end of
 *       file in one pass and never revisits a record, so a single forward cursor expresses it
 *       exactly while any repeated bounded query would re-establish a position the baseline never
 *       re-establishes. A caller must close the stream, and must hold a transaction open across
 *       the walk for the cursor to stay valid.</li>
 *   <li><b>Keyset-continuation finders</b> -- a predicate selecting rows whose ordering key is
 *       strictly greater than a supplied key, with a bounded result. Assumptions: these exist for
 *       resumption rather than for reading in convenient chunks. A redriven state-machine
 *       execution restarts a step that may already have processed part of its input, and a
 *       continuation finder is what turns the key it resumes from back into the remainder of the
 *       walk. Where that key is <b>stored</b> is stated below, because it is not stored where an
 *       earlier revision of this charter said it was.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: the continuation key is held in the framework's own step
 * {@code ExecutionContext} -- persisted by the configured {@code JobRepository} in its
 * {@code BATCH_STEP_EXECUTION_CONTEXT} table and restored into the same step on a restart -- and
 * <b>not</b> in {@code batch.batch_run}, which an earlier revision of this paragraph asserted. That
 * assertion was checkable and false: {@code services/batch-service/src/main/resources/db/migration/V1__batch.sql}
 * declares exactly seven columns on that table -- {@code id}, {@code run_id}, {@code step_name},
 * {@code status}, {@code started_at}, {@code finished_at} and {@code return_code} -- and the
 * {@code BatchRun} mapping beside it maps those seven and no eighth, so no column exists that could
 * hold a cursor. The two mechanisms answer different questions and the split is deliberate:
 * {@code batch.batch_run} answers <i>did this step already complete for this run</i>, which is the
 * idempotency question a redriven state machine asks before doing anything at all, and the
 * execution context answers <i>how far into its input had it got</i>, which is the resumption
 * question a step asks once it has decided to run. Alternatives Considered: adding a continuation
 * column to {@code batch.batch_run} so that one table answered both. Rejected because the framework
 * already persists and restores the execution context transactionally with the step's own chunk
 * commit, so a second store would have to be written in the same commit to stay consistent with it
 * -- two records of one position that can disagree, where the disagreement is silent and the loser
 * is a re-processed or a skipped chunk.</p>
 *
 * <p>Assumptions: {@code com.carddemo.common.web.PageResponse} is <b>not</b> used in this package,
 * and neither is its companion {@code com.carddemo.common.web.CursorToken}. Both are the HTTP
 * keyset envelope and its opaque token: the envelope exists because a stateless request handler
 * cannot remember where a caller had reached, and the token exists so that a boundary key is never
 * handed to a client in the form it was built from. This module has no client. The parent charter
 * fixes it as having no {@code api} subpackage, no controller and no REST surface beyond the
 * actuator health probe, so neither type has a consumer here. A batch continuation key never
 * leaves the process or the database it was read from, so sealing it would add a keyed-hash
 * computation against a threat that does not exist on this path, and the key is carried as the
 * plain value the framework's step {@code ExecutionContext} already stores.</p>
 *
 * <h2>The two ordered-walk contracts this package defines</h2>
 *
 * <p>Two of the eight interfaces expose an ordered walk, and in both cases the ordering is part of
 * the contract rather than a convenience. A walk in a different order still returns every row and
 * still produces output a comparison rejects, so each ordering is recorded here with the evidence
 * that settles it.</p>
 *
 * <p><b>The category-balance walk orders by account identifier, then transaction type code, then
 * transaction category code.</b> Assumptions: that ordering is the VSAM key order and is provable
 * rather than inferred, from three sources that agree. The cluster is defined at
 * {@code app/jcl/TCATBALF.jcl:40} as {@code KEYS(17 0)} -- a seventeen-byte key beginning at
 * position zero, so the key is the first seventeen bytes of the record and nothing else.
 * {@code app/cpy/CVTRA01Y.cpy:5} declares those leading bytes as a group of exactly three fields:
 * an eleven-digit account identifier at {@code app/cpy/CVTRA01Y.cpy:6}, a two-character type code
 * at {@code app/cpy/CVTRA01Y.cpy:7} and a four-digit category code at
 * {@code app/cpy/CVTRA01Y.cpy:8}. Those widths sum to exactly seventeen, in that declared
 * sequence, so the key order is not a matter of interpretation. The owning service then declares
 * the same sequence independently as the composite primary key
 * {@code (account_id, type_cd, category_cd)} at {@code V1__ledger.sql:884}.</p>
 *
 * <p>Assumptions: the reason that ordering has to be honoured, rather than merely documented, is
 * that a downstream control break depends on it. Interest accrual detects a change of account by
 * comparing the current row's account identifier against the previous row's, at
 * {@code app/cbl/CBACT04C.cbl:194}, and flushes the accumulated interest for the account it is
 * leaving. That comparison is correct only while rows for one account arrive consecutively, which
 * is exactly what ordering by account identifier first guarantees. A walk ordered any other way
 * would break the account into several apparent groups and produce an interest row per group.</p>
 *
 * <p><b>The combine walk orders by transaction identifier, ascending.</b> Assumptions: the baseline
 * combine step is a sort rather than a program, and it declares that ordering explicitly. The sort
 * symbol is defined under the symbol-names definition at {@code app/jcl/COMBTRAN.jcl:27}, and the
 * symbol itself at {@code app/jcl/COMBTRAN.jcl:28} reads {@code TRAN-ID,1,16,CH} -- a
 * sixteen-byte character field at one-based position one, so the identifier occupies the front of
 * the record and is compared as characters rather than as a number. The direction is requested at
 * {@code app/jcl/COMBTRAN.jcl:30} as {@code SORT FIELDS=(TRAN-ID,A)}, where the trailing
 * {@code A} is ascending. Because the comparison is declared as character rather than numeric, the
 * migrated ordering must be over the identifier's character property and must not be over a
 * numeric conversion of it: the two orders differ for identifiers that are not uniformly
 * zero-padded, and the difference is visible in the combined output.</p>
 *
 * <h2>The layering boundary, and one name collision to resolve deliberately</h2>
 *
 * <p>What an interface here may import: Jakarta Persistence, Spring Data, the {@code java}
 * standard library, {@code com.carddemo.batch.domain} for the entity it is typed on, and
 * {@code com.carddemo.common} where a shared type is genuinely needed. What it may never import:
 * another bounded context's {@code domain} package, an AWS SDK type, or a web or servlet type.</p>
 *
 * <p>Assumptions: the layering constraints have exactly one mechanical owner, and it is the
 * ArchUnit rule set at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java},
 * re-run inside this module by the {@code architecture-rules} test execution that
 * {@code services/pom.xml} declares. The Checkstyle {@code ImportControl} module is deliberately
 * absent from {@code config/checkstyle/checkstyle.xml}, so there is no second enforcement point
 * that could drift from the first. Two of those rules bear on this package directly. Rule A2
 * forbids any class under one CardDemo package root from depending on a {@code domain} class owned
 * by a different root, which is what makes the foreign-domain prohibition above a build failure
 * rather than a review comment. Rule A3 forbids every production type under {@code com.carddemo}
 * from declaring a binary floating-point type in a field, a parameter or a return type, including
 * as a generic type argument -- so a repository method here may not return or accept
 * {@code double} or {@code Double}, and a monetary value crosses this package as an exact decimal
 * or not at all.</p>
 *
 * <p>Assumptions: the honest limit of that mechanism is worth recording, because overstating it
 * would let a real gap pass as covered. Rule A1, which forbids AWS SDK, Spring Web and Jakarta
 * Servlet types, is scoped to {@code ..domain..} packages and therefore does <b>not</b> reach this
 * one. The prohibition on those types in this package rests on this charter and on review, exactly
 * as the Checkstyle rule set records for the clauses it cannot mechanise. It is stated as a rule
 * here rather than omitted because the reason it exists is unaffected by whether a test enforces
 * it: a repository that reached a queue client or a servlet type would make the data-access seam
 * untestable without standing up the platform around it.</p>
 *
 * <p>Assumptions: two names in this package collide with names in {@code account-service}, and the
 * collision must not be resolved by reaching for the other one. {@code AccountRepository} and
 * {@code CardXrefRepository} here share their simple names with
 * {@code com.carddemo.account.repository.AccountRepository} and
 * {@code com.carddemo.account.repository.CardXrefRepository}. Same simple name, different package,
 * and different declaring module. Reusing account-service's is barred twice over: its interfaces
 * are typed on {@code com.carddemo.account.domain} entities, so importing either one would take a
 * dependency on a foreign {@code domain} class and fail rule A2; and account-service is not a
 * dependency of this module at all, since the only intra-repository Maven dependency this module
 * declares is {@code common-lib}, so those types are not on this module's compile classpath to be
 * imported. The duplication is the deliberate consequence of the local-mapping decision the
 * sibling {@code domain} charter records, and it is not an oversight to be tidied away.</p>
 *
 * <h2>How the baseline citations in this charter are to be read</h2>
 *
 * <p>Every {@code app/} path above is cited as reference material for provenance. Nothing under
 * {@code app/**} is read at run time, and nothing under it is modified by this migration or by
 * anything else: the COBOL remains byte-identical and keeps running. Line numbers refer to the
 * source as committed, and columns 73 to 80 of a COBOL or JCL line carry a sequence field that is
 * not part of the statement.</p>
 *
 * <p>Assumptions: the permitted framing for any difference between the two implementations is
 * settled and narrow. The baseline does one thing, the Java does another, and the divergence is
 * documented in the traceability matrix. Nothing in this package is described as repairing,
 * amending or improving upon a baseline behaviour, because the baseline is the specification this
 * work is measured against -- a claim that it was wrong would be a claim that the oracle is
 * wrong.</p>
 *
 * <p>Assumptions: the step ledger reached through {@code BatchRunRepository} is the one place that
 * framing is easy to get backwards, so it is settled here. It is <b>an improvement the baseline
 * does not have</b>, and not a migration of something that existed. The only restart directive
 * anywhere in the thirty-eight jobs under {@code app/jcl} is at {@code app/jcl/DEFGDGD.jcl:2},
 * where it is commented out and therefore inert, and no checkpoint directive appears in any of
 * those jobs at all. There is consequently no baseline checkpoint contract to preserve, nothing to
 * compare the ledger's behaviour against, and no defect being addressed -- which is precisely why
 * a resumed step's idempotency has to be established by this package's own uniqueness constraint
 * rather than by appeal to how the baseline did it.</p>
 */
package com.carddemo.batch.repository;
