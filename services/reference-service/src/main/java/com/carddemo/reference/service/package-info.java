/**
 * Business rules for CardDemo reference data. This package is the service
 * layer of the {@code reference-service} module: it holds every reference
 * data business rule in the module, and it is the only layer in the module
 * that holds one.
 *
 * <h2>Target contract, and the tree state at the checkpoint that authored it</h2>
 *
 * <p>Assumptions: every inventory, file name, class name and count in this charter describes the
 * package's <b>target contract</b> as the migration plan assigns it, not the set of files present
 * beside this one today. The migration lands its artifacts in plan order and this charter is
 * authored first, so at the checkpoint that authored it this directory holds this charter and
 * nothing else. A type or test named below that has no file yet is therefore <b>planned</b>, not
 * missing, and a count below is a target total rather than a measurement of the directory.</p>
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
 * <p>Purpose. Each significant paragraph of the baseline COBOL programs
 * named below becomes one named method here, so that the register at
 * {@code docs/architecture/cobol-to-service-traceability.md} can cite
 * paragraph-to-method pairs. A paragraph that carries a business rule is
 * therefore never folded into its caller, even where folding would read
 * more naturally in Java, because the register has to be able to name the
 * method that stands for it.
 *
 * <p>Layering. Classes here depend on the sibling packages
 * {@code com.carddemo.reference.repository},
 * {@code com.carddemo.reference.dto} and
 * {@code com.carddemo.reference.mapper}, and on the
 * {@code com.carddemo.common.*} packages published by the
 * {@code common-lib} module. Their consumers are
 * {@code com.carddemo.reference.api} and, in the case of
 * {@code DateConversionMessageListener}, that class's own SQS entry point.
 * The dependency runs one way only: nothing here reads a request or writes a
 * response, and nothing here calls back into a controller.
 *
 * <p>No class in this package may import another service module's types.
 * The only intra-reactor Maven dependency {@code reference-service}
 * declares is {@code common-lib}. Assumptions: {@code batch-service} and
 * {@code account-service} both consume reference data, and both do so over
 * the published HTTP API or over the database schema, never by importing a
 * type from here. Such an import would compile inside one reactor and then
 * break the moment the two services are deployed as separate containers,
 * which is the arrangement this migration targets.
 *
 * <p>Class inventory and baseline lineage. Everything beneath
 * {@code app/} is REFERENCE-ONLY: it is the behavioural oracle for this
 * migration and is never modified. Line counts are given so a reader can
 * tell at a glance how much baseline each class answers for.
 * <ul>
 *   <li>{@code TransactionTypeService} transcribes
 *       {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} (2098 lines)
 *       and {@code COTRTUPC.cbl} (1702 lines).</li>
 *   <li>{@code TransactionCategoryService} transcribes the category side of
 *       the same feature, defined by
 *       {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl},
 *       {@code ddl/XTRNTYCAT.ddl}, {@code dcl/DCLTRCAT.dcl} and
 *       {@code ctl/DB2LTCAT.ctl}, and it reuses the cursor paging pattern
 *       of {@code COTRTLIC.cbl}.</li>
 *   <li>{@code DisclosureGroupService} transcribes
 *       {@code app/cbl/CBACT04C.cbl} (652 lines).</li>
 *   <li>{@code ReferenceBatchUpdateService} transcribes
 *       {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} (237 lines),
 *       which {@code jcl/MNTTRDB2.jcl} drives.</li>
 *   <li>{@code DateConversionMessageListener} transcribes
 *       {@code app/app-vsam-mq/cbl/CODATE01.cbl} (524 lines) and delegates
 *       its date edit rules to {@code common-lib}, which carries the rules
 *       of {@code app/cbl/CSUTLDTC.cbl} (157 lines).</li>
 * </ul>
 *
 * <p>Contract 1: a restricted delete surfaces as HTTP 409, never HTTP 500.
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L6-L7 declares
 * {@code FOREIGN KEY TRC_TYPE_CODE (TRC_TYPE_CODE) REFERENCES
 * CARDDEMO.TRANSACTION_TYPE (TR_TYPE) ON DELETE RESTRICT}, so deleting a
 * transaction type that still has categories is refused by the database
 * rather than by application code. The chain the target preserves is Db2
 * SQLCODE -532, then PostgreSQL SQLSTATE 23503, then
 * {@code DataIntegrityViolationException}, then
 * {@code com.carddemo.common.error.GlobalExceptionHandler}, then HTTP 409.
 * {@code TransactionTypeService} owns the parent side and
 * {@code TransactionCategoryService} the child side.
 *
 * <p>That 409 mapping is INHERITED, not declared here.
 * {@code ReferenceApplication} already registers the shared handler with
 * {@code @Import(GlobalExceptionHandler.class)}. Assumptions: Spring
 * selects one handler per exception type, so a second
 * {@code @RestControllerAdvice} anywhere in this module would take
 * precedence unpredictably and could turn the 409 back into a 500. No
 * second advice may be declared anywhere in this module.
 *
 * <p>Contract 2: the disclosure group fallback key is space padded to ten
 * characters. {@code app/cbl/CBACT04C.cbl} L437 moves the seven character
 * literal {@code 'DEFAULT'} into {@code FD-DIS-ACCT-GROUP-ID}, which L79
 * of the same program declares as {@code PIC X(10)}. COBOL pads an
 * alphanumeric move on the right with spaces, so the key the baseline
 * actually reads with is {@code DEFAULT} followed by three spaces. That is
 * why the target column is {@code CHAR(10)} and why a lookup here must
 * never trim the group identifier: trimming would search for a seven
 * character key that no row carries.
 * {@code DisclosureGroupService} owns this.
 *
 * <p>The fallback is not total. Assumptions: one
 * {@code (tran_type_cd, tran_cat_cd)} pair has no disclosure row in any
 * group, the default group included, so a genuine terminal not found path
 * exists and has to be handled rather than assumed away. Treating the
 * fallback as exhaustive would replace that path with an absent rate and
 * silently accrue nothing.
 *
 * <p>Contract 3: three baseline programs write the transaction type table
 * with three different behaviours, and all three are kept distinct.
 * <ul>
 *   <li>The maintenance screen is an upsert.
 *       {@code COTRTUPC.cbl} {@code 9600-WRITE-PROCESSING} (L1531-L1592)
 *       issues an update and, on SQLCODE +100, performs
 *       {@code 9700-INSERT-RECORD} instead of reporting a miss.</li>
 *   <li>The list inline edit is a strict update.
 *       {@code COTRTLIC.cbl} {@code 9200-UPDATE-RECORD} (L1837-L1894)
 *       issues the same update but, on SQLCODE +100, reports not found and
 *       inserts nothing.</li>
 *   <li>The batch driver soft rejects the record and continues.
 *       {@code COBTUPDT.cbl} {@code 10032-UPDATE-DB} (L166-L195) routes
 *       every failure to {@code 9999-ABEND}, which despite its name only
 *       displays the message and sets return code 4 (L230-L233), leaving
 *       the sequential read loop at L93-L96 free to take the next
 *       record.</li>
 * </ul>
 *
 * <p>Trade-offs: collapsing the three into one shared write method would
 * remove duplication and would also erase the distinction, because an
 * upsert cannot report a miss and a soft reject cannot fail a request.
 * {@code TransactionTypeService} therefore keeps the two online behaviours
 * as separate methods and {@code ReferenceBatchUpdateService} keeps the
 * batch behaviour as a third.
 *
 * <p>Deliberate absences. Each item below is a decision, not an omission,
 * and each is recorded because a later reader would otherwise supply it in
 * good faith. This package is complete at the five classes listed above
 * plus this file.
 * <ul>
 *   <li>There is no lookup service. Alternatives Considered: a
 *       {@code LookupService} wrapping {@code PhoneAreaCodeRepository},
 *       {@code StateRepository} and {@code StateZipPrefixRepository} was
 *       weighed and rejected, because those three repositories carry no
 *       rule beyond whether a code exists. They are read directly by the
 *       {@code com.carddemo.reference.api} controllers, and across the
 *       service boundary by {@code account-service}'s
 *       {@code AddressValidationService} over HTTP, never by a
 *       cross-service domain import.</li>
 *   <li>There is no Spring Batch job, no batch configuration class and no
 *       batch starter on the module classpath. Assumptions:
 *       {@code COBTUPDT.cbl} is a plain sequential reader, a
 *       {@code PERFORM UNTIL LASTREC = 'Y'} loop at L93-L96 over a file
 *       declared {@code ORGANIZATION IS SEQUENTIAL}, ending in a return
 *       code rather than in a restartable chunk. It therefore migrates to
 *       a service method that the API and the batch chain both invoke, not
 *       to a job repository owner. Two independent facts corroborate that:
 *       the module POM declares no batch starter, and none of
 *       {@code application.yml}, {@code application-dev.yml} or
 *       {@code application-prod.yml} declares a {@code spring.batch} key.
 *       Job ownership belongs to {@code batch-service}.</li>
 *   <li>No SQS configuration class belongs in this package.
 *       Assumptions: {@code application.yml} already declares the
 *       {@code spring.cloud.aws.sqs} properties, among them a five second
 *       receive wait that answers the baseline's own
 *       {@code MQGMO-WAITINTERVAL} of 5000 at
 *       {@code app/app-vsam-mq/cbl/CODATE01.cbl} L286, and Spring Cloud
 *       AWS auto-configures the {@code @SqsListener} infrastructure from
 *       them. Whatever configuration the module does need lives in the
 *       sibling {@code com.carddemo.reference.config} package, which this
 *       package does not own.</li>
 *   <li>No second exception handling advice, per Contract 1.</li>
 *   <li>The date edit rules are not restated here. They live in
 *       {@code com.carddemo.common.validation.DateEditValidator} and
 *       {@code DateConversionMessageListener} delegates to them.
 *       Assumptions: that is faithful rather than a liberty, because
 *       {@code app/cbl/CSUTLDTC.cbl} was itself a shared callable
 *       subprogram: it declares
 *       {@code PROCEDURE DIVISION USING LS-DATE, LS-DATE-FORMAT,
 *       LS-RESULT.} at L88 and hands control back with
 *       {@code EXIT PROGRAM} at L100. One shared implementation therefore
 *       reproduces the baseline's own shape, and it is what AAP Rule T2
 *       (one {@code COPY} becomes one import) requires.</li>
 *   <li>No IEEE-754 binary floating point appears in the rate path.
 *       Assumptions: an interest rate is exact decimal data, and a binary
 *       radix type cannot represent every value it has to carry exactly.
 *       In this package the prohibition rests on code review, not on a
 *       mechanical gate: the ArchUnit rule that enforces it lives in
 *       {@code common-lib}'s test tree and scopes its subject set to
 *       {@code com.carddemo.common.money}, so it never inspects a class
 *       here. That limit is stated rather than glossed, because claiming a
 *       gate that does not run would be worse than claiming none.</li>
 *   <li>There is no offset pagination and no offset helper. Paging is
 *       keyset based throughout, carried by
 *       {@code com.carddemo.common.web.PageResponse}.
 *       Alternatives Considered: offset paging is shorter to write and was
 *       rejected because it skips and repeats rows under concurrent
 *       inserts, which the baseline's cursor paging does not do.</li>
 * </ul>
 *
 * <p>Shared conventions inherited by all five classes.
 * <ul>
 *   <li>Trim boundary. A {@code CHAR} key is never trimmed, because the
 *       declared width is part of the contract: {@code type_cd CHAR(2)},
 *       {@code cat_cd CHAR(4)} and {@code acct_group_id CHAR(10)}. A
 *       {@code VARCHAR} description is trimmed on the way in and never
 *       padded again on the way out.</li>
 *   <li>Rates. {@code interest_rate} is {@code NUMERIC(6,2)} in PostgreSQL
 *       and {@code java.math.BigDecimal} at scale 2 on the entity, which
 *       is the target of the baseline's {@code PIC S9(04)V99}. The single
 *       conversion from {@code BigDecimal} to
 *       {@code com.carddemo.common.money.Money} in this whole service is
 *       {@code DisclosureGroupMapper}, which performs no arithmetic; this
 *       package never constructs {@code Money} directly. Assumptions:
 *       {@code com.carddemo.common.money.MoneyModule}, already active
 *       through {@code ReferenceApplication}, binds its serialiser to the
 *       {@code Money} type, so a response field left as
 *       {@code BigDecimal} would quietly serialise as a JSON number where
 *       a JSON string is required.</li>
 *   <li>Verbatim messages. AAP Rule T8 (user-visible strings are verbatim)
 *       applies character for character to every message literal carried
 *       across, trailing spaces, missing spaces after a period and
 *       inconsistent casing included. Nothing is harmonised. In particular
 *       the three renderings of the same table reference are all kept
 *       apart: {@code 'Error reading TRANSACTION_TYPE table '} at
 *       {@code COTRTLIC.cbl} L1826, lowercase, with no period and a
 *       trailing space; {@code ' TRANSACTION_TYPE Table. SQLCODE:'} at
 *       {@code COTRTUPC.cbl} L1571 and L1611, with a capital T; and
 *       {@code ' TRANSACTION_TYPE table. SQLCODE:'} at
 *       {@code COBTUPDT.cbl} L157, L188 and L219, with a lowercase t.</li>
 *   <li>Page envelope assembly. {@code PageResponse} is a record of four
 *       components over one type parameter: the item list, an opaque
 *       nullable first key, an opaque nullable last key and a boolean
 *       saying whether a further page exists. It carries no previous page
 *       flag, no page size, no page number and no total count. Mappers
 *       supply item types only, so assembling the envelope is this layer's
 *       job.</li>
 *   <li>Concurrency. No entity in this module's domain carries a version
 *       column, so the before-image comparison the baseline performs at
 *       {@code COTRTUPC.cbl} L1585 is implemented in this layer rather
 *       than by the persistence provider. Assumptions: the exception it
 *       throws is discriminated by its fully qualified class name, because
 *       {@code common-lib} has no JPA dependency and so cannot name
 *       {@code jakarta.persistence.OptimisticLockException} by type; the
 *       shared handler walks the cause chain by name instead.</li>
 * </ul>
 *
 * <p>Divergence register. Where the target's behaviour departs from the
 * baseline's, the baseline is left exactly as it stands and the departure
 * is recorded in
 * {@code docs/architecture/cobol-to-service-traceability.md}, the register
 * of every documented behavioural divergence in this migration. That
 * document is owned elsewhere: read it, and do not author or edit it from
 * here.
 *
 * <p>Why this file exists. It exists because user-specified Rule 1
 * (Explainability) names a module entry point as a documentation subject
 * at L15, and in Java a package-level Javadoc comment has nowhere to live
 * except a {@code package-info} compilation unit. Its own obligation under
 * that rule is the purpose statement above; the rule's parameter, return
 * value and exception clauses have no subject here, because this file
 * declares no method, so no tag is invented to stand in for them. L43
 * states the gate the five sibling classes are held to, and it is
 * conjunctive: a docstring and a why-comment are both required, and code
 * missing either one fails review.
 */
package com.carddemo.reference.service;
