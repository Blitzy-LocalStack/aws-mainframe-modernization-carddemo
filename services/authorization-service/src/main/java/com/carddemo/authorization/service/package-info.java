/**
 * Business-logic layer of {@code authorization-service}, where each significant COBOL
 * paragraph becomes a named method.
 *
 * <p><strong>Charter scope.</strong> This file is the {@code service} member of the parent
 * charter's eight-file package-entry set: {@code com.carddemo.authorization} and its
 * {@code api}, {@code config}, {@code domain}, {@code dto}, {@code mapper},
 * {@code repository}, and {@code service} packages. The inventory below is the target
 * contract this package is audited against: a role named here belongs to this package, and a
 * role absent from it does not, whichever types the directory happens to hold.</p>
 *
 * <h2>Service roles</h2>
 *
 * <ul>
 *   <li>{@code PendingAuthSummaryService} serves {@code GET /api/v1/authorizations}. Its principal
 *       source is {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl}: paragraphs
 *       {@code PROCESS-PAGE-FORWARD} at lines 415 to 457, {@code GET-AUTHORIZATIONS} at
 *       lines 458 to 487, {@code REPOSITION-AUTHORIZATIONS} at lines 488 to 521,
 *       {@code POPULATE-AUTH-LIST} at lines 522 to 607, and {@code GET-AUTH-SUMMARY} at
 *       lines 966 to 1000.</li>
 *   <li>{@code PendingAuthDetailService} serves {@code GET /api/v1/authorizations/{key}}. Its
 *       principal source is {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl}:
 *       paragraphs {@code POPULATE-AUTH-DETAILS} at lines 291 to 359,
 *       {@code READ-AUTH-RECORD} at lines 431 to 492, and
 *       {@code READ-NEXT-AUTH-RECORD} at lines 493 to 519.</li>
 *   <li>{@code FraudMarkingService} serves
 *       {@code PUT /api/v1/authorizations/{key}/fraud}. Its principal sources are
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
 * <p>Five further authored classes support those roles without adding one.
 * {@code AuthorizationDecisionService} is a collaborator of the listener rather than a
 * ninth role: it is the extracted implementation of {@code COPAUA0C.cbl}'s
 * {@code 6000-MAKE-DECISION} paragraph at lines 657 to 734, holding the complete
 * decline-reason table that paragraph selects from at its lines 699 to 717, and it owns no
 * queue, no transaction boundary and no HTTP surface of its own. {@code AccountContextClient}
 * is the outbound port for the three reads that paragraph's caller performs against records
 * this context does not own, and {@code RestAccountContextClient} is its one adapter. The port
 * and its adapter are the concrete form of the interface boundary described under
 * <em>Bounded-context data</em> below; neither is a ninth target role, because both exist only
 * so the listener can reach data the inventory above already assumes is reachable.
 * {@code RequestWindowBoundary} and its one implementation {@code ContainerCyclingWindowBoundary} are
 * the fifth and fourth: the interface is the seam that closes intake once the declared per-run request
 * limit is reached, and the implementation is the mechanism that acts on it. Neither is a role either,
 * because the bound they carry belongs to the listener's contract -- it is the migrated form of that
 * program's own five-hundred-request limit -- and they exist as separate types only because the counting
 * and the stopping cannot happen on the same thread.
 * Refactoring Rationale: the decision paragraph is extracted rather than inlined into the
 * listener because it is the one part of the consumer that is a pure function of the decoded
 * request and the account state -- every response code and reason literal is carried across
 * character for character -- so extracting it lets the decision be tested against the
 * baseline's literals with no queue, no database and no transaction in the way. Inlining it
 * would make each of those assertions require a message.</p>
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
 * <p>Assumptions: nothing in this package logs a value that came off the wire, and the
 * discipline is stated at package level because it is the one place it can be. A failure
 * message from a queue client can embed the request it was building, and that request
 * carries a primary account number, so {@code OutboxPublisher} records an exception's CLASS
 * NAME rather than its message and {@code AuthorizationRequestListener} logs an unparseable
 * attribute's LENGTH rather than its content. Log storage is the one destination the masking
 * applied at the API edge does not reach: a masked response body and an unmasked log line
 * can come from the same request, and only the body passes through the mapper. Every service
 * added to this package inherits the obligation, which is why it is not left to the two
 * classes that currently satisfy it.</p>
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
 * properties; they do not redeclare queue topology or transport defaults. The one messaging
 * property read directly by the listener is {@code reply-queue-allowlist}, and it is read there
 * because it is a disclosure POLICY rather than topology -- it decides which destinations a card
 * number and an authorization outcome may be sent to, which is a decision the code that assembles
 * the reply has to make and cannot delegate to a client bean.</p>
 *
 * <p>Assumptions: four properties of the baseline consumer ARE preserved, and each is
 * preserved as configuration rather than as code, so an operator can change it without a
 * rebuild. The unit of work is one message, matching the per-message syncpoint at
 * {@code COPAUA0C.cbl} line 335. The receive wait is five seconds, matching the baseline's
 * own get-with-wait. The in-flight batch is bounded, which is the continuous equivalent of
 * the baseline's five-hundred-message processing limit rather than a literal translation of
 * it. And the reply destination comes from the REQUEST, exactly as the message descriptor's
 * reply-to queue field did, so one consumer serves however many requesters have their own
 * reply queues -- falling back to a configured queue would answer a requester that never
 * asked, which is why the listener refuses to.</p>
 *
 * <p>Assumptions: the request queue is a FIFO queue whose message group is the card number
 * and whose deduplication identifier is the acquirer's transaction identifier. Grouping by
 * card gives per-card ordering while leaving different cards to be delivered in parallel,
 * which is what lets the listener take a pessimistic row lock on one account's summary
 * without serialising the whole service. Deduplicating by transaction identifier makes
 * suppression independent of the payload, so a re-sent request whose bytes differ is still
 * recognised as the same request; that is why {@code infra/modules/sqs} sets
 * {@code content_based_deduplication = false} rather than letting the transport hash the
 * body.</p>
 *
 * <p>Assumptions: one genuine semantic gap is resolved here rather than ignored. The
 * baseline sets a five-second expiry on its reply message and the target queue service has
 * no per-message time to live, so the expiry travels as a message attribute instead: the
 * listener DROPS and logs a request whose expiry has already passed, and the publisher
 * retires a reply whose expiry passed before it could be sent. Deciding a stale request
 * would reserve funds against an authorization whose requester has stopped waiting, and
 * sending a stale reply would consume the deduplication identifier so that a legitimate
 * retry would then be suppressed as a duplicate of an answer nobody read. The resolution is
 * recorded in {@code docs/adr/ADR-004-messaging.md}.</p>
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
 * package.</p>
 *
 * <p>{@code AccountContextClient} is that interface for the three reads
 * {@code COPAUA0C.cbl} performs, and it is deliberately narrow: it publishes two record types of
 * its own carrying only the fields a decision reads, so no type from another context's domain
 * package appears in this one even as a parameter. {@code RestAccountContextClient} is its only
 * implementation and is the only class in this service that names a path, a status code or a
 * timeout for that hop. Refactoring Rationale: the boundary was previously described here without
 * being declared anywhere, and the listener stood in for it by resolving a card to an account from
 * this context's OWN authorization history -- so the description was accurate about intent and the
 * code did something else. The interface now exists, and the substitute query has been removed from
 * {@code PendingAuthDetailRepository} so nothing can reach for it again.</p>
 *
 * <p>Assumptions: the boundary stated above is MECHANICALLY enforced rather than carried by review.
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * owns it, and it RUNS IN THIS MODULE: this module declares the shared kernel's test artifact, and the
 * {@code architecture-rules} Surefire execution in {@code services/pom.xml} scans that artifact and runs
 * the rules on THIS module's test classpath, so they see {@code com.carddemo.authorization}'s own
 * classes. An import of another context's domain package, or of a framework type into a domain class, is
 * therefore a broken build rather than a review comment. The ownership is recorded here so that a rule
 * about this package's imports is not restated in this package, where it could differ from the one that
 * actually runs.</p>
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
 * <p>Refactoring Rationale: the reply relocation is the one deliberate behavioural
 * improvement in this package and the reason the outbox exists at all. The baseline commits
 * its decision and publishes its reply as two separate units of work, so a failure between
 * them leaves an authorization the data says was decided and a requester that never hears
 * back. The listener writes the reply into the database INSIDE the deciding transaction and
 * the publisher sends it afterwards, so the row and the decision commit together or neither
 * does, and publication can be retried without re-deciding anything. Alternatives
 * Considered: publishing first and writing second, which is the baseline's order reversed
 * and closes nothing -- it merely moves the lost window from the reply to the data. The
 * divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
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
 * <p>Assumptions: four properties of the baseline consumer ARE preserved, and each is
 * preserved as configuration rather than as code, so an operator can change it without a
 * rebuild. The unit of work is one message, matching the per-message syncpoint. The receive
 * wait is five seconds, matching the baseline's own get-with-wait. The in-flight batch is
 * bounded, which is the continuous equivalent of the baseline's five-hundred-message
 * processing limit. And the reply destination comes from the REQUEST, exactly as the message
 * descriptor's reply-to queue field did, so one consumer serves however many requesters have
 * their own reply queues.</p>
 *
 * <p>Assumptions: the request queue is a FIFO queue whose message group is the card number
 * and whose deduplication identifier is the acquirer's transaction identifier. Grouping by
 * card gives per-card ordering while leaving different cards to be delivered in parallel,
 * which is what lets the listener take a pessimistic row lock on one account's summary
 * without serialising the whole service. Deduplicating by transaction identifier makes
 * suppression independent of the payload, so a re-sent request whose bytes differ is still
 * recognised as the same request.</p>
 *
 * <p>Assumptions: one genuine semantic gap is resolved here rather than ignored. The baseline
 * sets a five-second expiry on its reply message and the target queue service has no
 * per-message time to live, so the expiry travels as a message attribute instead: the
 * listener DROPS and logs a request whose expiry has already passed, and the publisher
 * retires a reply whose expiry passed before it could be sent. Deciding a stale request would
 * reserve funds against an authorization whose requester has stopped waiting, and sending a
 * stale reply would consume the deduplication identifier so that a legitimate retry would
 * then be suppressed as a duplicate of an answer nobody read. The resolution is recorded in
 * {@code docs/adr/ADR-004-messaging.md}.</p>
 *
 * <p>Assumptions: nothing in this package logs a value that came off the wire. A failure
 * message from a queue client can embed the request it was building, and that request carries
 * a primary account number, so the publisher records an exception's CLASS NAME rather than
 * its message and the listener logs an unparseable attribute's LENGTH rather than its
 * content. Log storage is the one destination the masking applied at the API edge does not
 * reach, which is why the discipline is stated at package level and not left to each
 * type.</p>
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
 * <p>Refactoring Rationale: this closing note previously argued that a package declaration has no
 * executable decision site, so implementation-decision labels stay beside the methods they explain,
 * and it carried no rationale label at all as a result. That reasoning does not survive contact with
 * this package's actual decisions. The five rationales restored above are package-SCOPED rather than
 * method-scoped: the PAN-logging discipline binds every service added here and is satisfied by two
 * classes today, so stating it beside either one would leave the third author unbound; the four
 * preserved consumer properties, the FIFO grouping contract and the reply-expiry resolution are
 * properties of the queue and its configuration that no single method owns; and the outbox relocation
 * is the reason two classes exist in the shape they do, which is not a fact either class can state
 * alone. An earlier revision of this file did carry exactly these five, and they were dropped when the
 * inventory and ownership sections were added -- a regression rather than a decision, which is why
 * they are restored rather than reinvented.</p>
 *
 * <p>Assumptions: labels beside individual methods remain the norm and are unaffected. A decision
 * whose blast radius is one statement belongs next to that statement, and moving it here would put it
 * where a reader of the method cannot see it. What this charter adds is the set of decisions that have
 * no single statement to sit beside.</p>
 */
package com.carddemo.authorization.service;
