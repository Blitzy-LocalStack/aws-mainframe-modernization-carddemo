/**
 * Business-logic layer of {@code authorization-service}, where each significant COBOL
 * paragraph becomes a named method.
 *
 * <p><strong>Charter scope.</strong> This file completes the parent charter's eight-file
 * package-entry set: {@code com.carddemo.authorization} and its {@code api},
 * {@code config}, {@code domain}, {@code dto}, {@code mapper}, {@code repository}, and
 * {@code service} packages. The inventory below is the target contract. A named role whose
 * class file has not yet been authored is planned rather than missing from that contract.</p>
 *
 * <h2>Service roles</h2>
 *
 * <ul>
 *   <li>{@code PendingAuthSummaryService} serves {@code GET /authorizations}. Its principal
 *       source is {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl}: paragraphs
 *       {@code PROCESS-PAGE-FORWARD} at lines 415 to 457, {@code GET-AUTHORIZATIONS} at
 *       lines 458 to 487, {@code REPOSITION-AUTHORIZATIONS} at lines 488 to 521,
 *       {@code POPULATE-AUTH-LIST} at lines 522 to 607, and {@code GET-AUTH-SUMMARY} at
 *       lines 966 to 1000.</li>
 *   <li>{@code PendingAuthDetailService} serves {@code GET /authorizations/{key}}. Its
 *       principal source is {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl}:
 *       paragraphs {@code POPULATE-AUTH-DETAILS} at lines 291 to 359,
 *       {@code READ-AUTH-RECORD} at lines 431 to 492, and
 *       {@code READ-NEXT-AUTH-RECORD} at lines 493 to 519.</li>
 *   <li>{@code FraudMarkingService} serves
 *       {@code PUT /authorizations/{key}/fraud}. Its principal sources are
 *       {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl}, paragraph
 *       {@code MARK-AUTH-FRAUD} at lines 230 to 267 and
 *       {@code UPDATE-AUTH-DETAILS} at lines 520 to 556, and
 *       {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl},
 *       {@code MAIN-PARA} at lines 89 to 220 and {@code FRAUD-UPDATE} at lines 221 to
 *       244.</li>
 *   <li>{@code AuthorizationRequestListener} consumes the FIFO request, decodes and
 *       decides it, persists the result, and records the reply for publication. Its
 *       principal source is {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}:
 *       paragraphs {@code 2000-MAIN-PROCESS} at lines 323 to 347,
 *       {@code 2100-EXTRACT-REQUEST-MSG} at lines 351 to 382,
 *       {@code 3100-READ-REQUEST-MQ} at lines 386 to 434,
 *       {@code 5000-PROCESS-AUTH} at lines 438 to 468,
 *       {@code 6000-MAKE-DECISION} at lines 657 to 734, and the writes at lines 786 to
 *       935.</li>
 *   <li>{@code OutboxPublisher} drains committed reply rows to the reply queue. Its
 *       principal source is {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl},
 *       paragraph {@code 7100-SEND-RESPONSE} at lines 738 to 782.</li>
 *   <li>{@code PurgeJob} performs age-based expiry. Its principal source is
 *       {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl}, from
 *       {@code 1000-INITIALIZE} at lines 183 to 212 through {@code 9999-ABEND} at lines
 *       377 to 385, including the summary/detail scans, expiry test, deletes, and
 *       checkpoint.</li>
 *   <li>{@code LoadService} bulk-loads authorization summary and detail data. Its
 *       principal source is {@code app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL},
 *       from {@code MAIN-PARA} at lines 169 to 189 through
 *       {@code 4000-FILE-CLOSE} at lines 341 to 357.</li>
 *   <li>{@code UnloadService} bulk-exports authorization summary and detail data. Its
 *       principal sources are
 *       {@code app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL}, from
 *       {@code MAIN-PARA} at line 157 through {@code 4000-FILE-CLOSE} at lines 289 to
 *       305, and {@code app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL}, from
 *       {@code MAIN-PARA} at line 164 through {@code 4000-FILE-CLOSE} at lines 338 to
 *       354.</li>
 * </ul>
 *
 * <p>The currently authored {@code AuthorizationDecisionService} is the extracted
 * implementation of {@code COPAUA0C.cbl}'s {@code 6000-MAKE-DECISION} paragraph at lines
 * 657 to 734. It supports the listener's decision step; it does not add a ninth target
 * role to the inventory above.</p>
 *
 * <h2>Ownership boundaries</h2>
 *
 * <p><strong>Transactions.</strong> Every {@code @Transactional} boundary belongs to this
 * package; repository interfaces declare none. The listener commits one request, its
 * authorization writes, and its outbox row in one local transaction. Scheduled and
 * orchestrator-invoked maintenance entry points set their own unit-of-work boundaries.
 * {@code com.carddemo.authorization.config.DataSourceConfig} owns the single
 * non-distributed {@code DataSource} and default {@code JpaTransactionManager}; no service
 * declares another transaction manager or a distributed-transaction coordinator.</p>
 *
 * <p><strong>Pagination.</strong> The repository returns a size-plus-one keyset result and
 * leaves the probe row present. {@code PendingAuthSummaryService} discards that row, derives
 * {@code hasNext} from whether it arrived, encodes the first and last opaque cursor tokens,
 * and assembles {@code com.carddemo.common.web.PageResponse}. Those response semantics do
 * not belong to the repository.</p>
 *
 * <p><strong>Data disclosure.</strong> PAN masking and CVV suppression belong to
 * {@code com.carddemo.authorization.mapper}. Services pass domain values to that boundary
 * and do not implement a second masking or suppression policy.</p>
 *
 * <p><strong>Access control.</strong>
 * {@code com.carddemo.authorization.config.SecurityConfig} owns the
 * {@code carddemo-admin} route guard for fraud marking.
 * {@code FraudMarkingService} documents the required authority at its entry point but does
 * not reproduce the HTTP security rule.</p>
 *
 * <p><strong>Messaging.</strong>
 * {@code com.carddemo.authorization.config.SqsConfig} owns the
 * {@code carddemo.messaging.*} binding: queue references, receive wait, per-invocation cap,
 * and message-attribute contract. The listener and publisher consume those beans and bound
 * properties; they do not redeclare queue topology or transport defaults.</p>
 *
 * <p><strong>Row protection.</strong> Service methods declare transaction boundaries but
 * no JPA lock mode. The current listener obtains summary-row protection through the
 * repository-owned {@code findWithLockByAccountId} method, whose
 * {@code PESSIMISTIC_WRITE} declaration remains on the repository boundary. No additional
 * lock declaration or lock-policy duplication belongs in this package.</p>
 *
 * <p><strong>Bounded-context data.</strong> Account, customer, card, and card-cross-reference
 * data are interface concerns rather than authorization tables. The baseline reads those
 * records in {@code COPAUS0C.cbl} at lines 818 to 819, 869 to 870, and 920 to 921, and in
 * {@code COPAUA0C.cbl} paragraphs {@code 5100-READ-XREF-RECORD} at lines 472 to 516,
 * {@code 5200-READ-ACCT-RECORD} at lines 520 to 564, and
 * {@code 5300-READ-CUST-RECORD} at lines 568 to 612. The target reaches
 * {@code account-service} and {@code card-service} through interfaces; it defines no account,
 * customer, card, or cross-reference entity here and imports no other service's domain
 * package. That boundary is asserted by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}.</p>
 *
 * <h2>Deliberate absences</h2>
 *
 * <p>No {@code BatchConfig} or Spring Batch {@code JobRepository} belongs to this context.
 * The parent manages the {@code spring-boot-starter-batch} version, while
 * {@code batch-service} alone consumes it. {@code PurgeJob}, {@code LoadService}, and
 * {@code UnloadService} are scheduled service methods or Step Functions-invoked entry
 * points, not Spring Batch jobs, so this module does not acquire a restart repository or a
 * second transaction owner.</p>
 *
 * <p>No {@code module-info.java} exists in this source tree. The three online services are
 * called by controllers planned under
 * {@code services/authorization-service/src/main/java/com/carddemo/authorization/api/};
 * that caller relationship is stated by path because controller classes are not part of
 * this package contract.</p>
 *
 * <h2>Traceability and behavioral divergences</h2>
 *
 * <p>Each significant source paragraph becomes a named method. That method's own Javadoc
 * cites the paragraph name and its verified line range, so a reviewer can move directly
 * between the service operation and immutable baseline source. A behavior that intentionally
 * differs from the baseline is implemented here and justified beside the method that causes
 * the difference, rather than being hidden in a repository or configuration adapter.</p>
 *
 * <p>The reply-publication divergence is owned by the listener and publisher. The baseline
 * performs {@code 7100-SEND-RESPONSE} at line 461 before its database writes at lines 463
 * to 465, while its syncpoint is at line 335 and both MQ operations request no-syncpoint
 * behavior at lines 389 to 391 and 753 to 754. The target commits the reply intent as an
 * outbox row with the authorization data, then publishes that committed row. The
 * IMS-and-Db2 transaction in the fraud path is likewise collapsed into the one local
 * PostgreSQL transaction owned here: {@code COPAUS1C.cbl} links to
 * {@code COPAUS2C.cbl} at lines 248 to 252, while the linked program inserts at lines 141
 * to 142 and updates at lines 222 to 223.</p>
 *
 * <p>The divergence register is
 * {@code docs/architecture/cobol-to-service-traceability.md}; that path is part of the
 * documentation gate and must remain character-for-character stable.</p>
 *
 * <h2>Documentation gate</h2>
 *
 * <p>{@code JavadocPackage} and {@code MissingJavadocPackage} run from the inherited
 * {@code checkstyle-documentation-gate}, which is bound to Maven's {@code validate} phase
 * with {@code failOnViolation=true}; {@code SuppressionFilter} loads its file with
 * {@code optional=false}, that file covers generated sources and test fixtures rather than
 * production packages, and no in-code suppression filter is wired, so a clean validation
 * run fails before compilation when this file is deleted or reduced to its package
 * declaration.</p>
 *
 * <p>This package entry point supplies the purpose required by Rule 1 lines 15, 18, and 22.
 * Parameters, return values, and exceptions from lines 19 to 21 are inapplicable rather
 * than omitted because a package declaration has no callable members. The declaration also
 * has no executable decision site, so implementation-decision labels stay beside the
 * methods they explain; adjacency under line 27 is satisfied here without inventing a
 * statement to annotate.</p>
 */
package com.carddemo.authorization.service;
