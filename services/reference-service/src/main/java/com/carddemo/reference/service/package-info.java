/**
 * Business rules for CardDemo reference data. This package is the service layer of the
 * {@code reference-service} module: it holds every reference data business rule in the
 * module, and it is the only layer in the module that holds one.
 *
 * <p>Purpose. Each significant paragraph of the baseline COBOL programs behind this
 * context becomes one named method here, so that the register at
 * {@code docs/architecture/cobol-to-service-traceability.md} can cite paragraph-to-method
 * pairs. Assumptions: a paragraph carrying a business rule is therefore never folded into
 * its caller, even where folding would read more naturally in Java, because the register
 * has to be able to name the method that stands for it. That register also holds the
 * per-program provenance and line-level citations, and this charter does not restate them.
 *
 * <h2>Layering</h2>
 *
 * <p>Classes here depend on the sibling repository, transfer-object and mapper packages of
 * this context, and on the {@code com.carddemo.common.*} packages published by
 * {@code common-lib}. Their consumers are this context's own web adapters and message entry
 * points. The dependency runs one way only: nothing here reads a request or writes a
 * response, and nothing here calls back into a controller.
 *
 * <p>Assumptions: no class in this package may import another service module's types, and
 * the only intra-reactor Maven dependency {@code reference-service} declares is
 * {@code common-lib}. Other contexts consume reference data over the published HTTP API or
 * over the database schema, never by importing a type from here -- such an import would
 * compile inside one reactor and then break the moment the two services are deployed as
 * separate containers, which is the arrangement this migration targets.
 *
 * <h2>Contract 1: a restricted delete surfaces as HTTP 409, never HTTP 500</h2>
 *
 * <p>{@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L6-L7 declares
 * {@code FOREIGN KEY TRC_TYPE_CODE (TRC_TYPE_CODE) REFERENCES
 * CARDDEMO.TRANSACTION_TYPE (TR_TYPE) ON DELETE RESTRICT}, so deleting a transaction type
 * that still has categories is refused by the database rather than by application code. The
 * chain the target preserves is Db2 SQLCODE -532, then PostgreSQL SQLSTATE 23503, then
 * {@code DataIntegrityViolationException}, then
 * {@code com.carddemo.common.error.GlobalExceptionHandler}, then HTTP 409.
 *
 * <p>Assumptions: that 409 mapping is INHERITED rather than declared here. The shared
 * kernel's {@code CardDemoCommonAutoConfiguration} registers the handler, which the
 * framework loads from that module's registration resource, so this context receives it
 * without naming it -- a registration a service has to remember is a registration a service
 * can omit, and the symptom would be a framework-shaped error body escaping from one service
 * while the others answer in the migrated shape. Spring selects one handler per exception
 * type, so a second {@code @RestControllerAdvice} anywhere in this module would take
 * precedence unpredictably and could turn the 409 back into a 500. No second advice may be
 * declared anywhere in this module.
 *
 * <h2>Contract 2: the disclosure group fallback key is space padded to ten characters</h2>
 *
 * <p>{@code app/cbl/CBACT04C.cbl} L437 moves the seven character literal {@code 'DEFAULT'}
 * into {@code FD-DIS-ACCT-GROUP-ID}, which L79 of the same program declares as
 * {@code PIC X(10)}. COBOL pads an alphanumeric move on the right with spaces, so the key
 * the baseline actually reads with is {@code DEFAULT} followed by three spaces. Assumptions:
 * that is why the target column is {@code CHAR(10)} and why a lookup here must never trim
 * the group identifier -- trimming would search for a seven character key that no row
 * carries.
 *
 * <p>Assumptions: the fallback is not total. One {@code (tran_type_cd, tran_cat_cd)} pair has
 * no disclosure row in any group, the default group included, so a genuine terminal
 * not-found path exists and has to be handled rather than assumed away. Treating the fallback
 * as exhaustive would replace that path with an absent rate and silently accrue nothing.
 *
 * <h2>Contract 3: three baseline write behaviours, all kept distinct</h2>
 *
 * <ul>
 *   <li>The maintenance screen is an upsert. {@code COTRTUPC.cbl}
 *       {@code 9600-WRITE-PROCESSING} (L1531-L1592) issues an update and, on SQLCODE +100,
 *       performs {@code 9700-INSERT-RECORD} instead of reporting a miss.</li>
 *   <li>The list inline edit is a strict update. {@code COTRTLIC.cbl}
 *       {@code 9200-UPDATE-RECORD} (L1837-L1894) issues the same update but, on SQLCODE
 *       +100, reports not found and inserts nothing.</li>
 *   <li>The batch driver soft rejects the record and continues. {@code COBTUPDT.cbl}
 *       {@code 10032-UPDATE-DB} (L166-L195) routes every failure to {@code 9999-ABEND},
 *       which despite its name only displays the message and sets return code 4
 *       (L230-L233), leaving the sequential read loop at L93-L96 free to take the next
 *       record.</li>
 * </ul>
 *
 * <p>Trade-offs: collapsing the three into one shared write method would
 * remove duplication and would also erase the distinction, because an
 * upsert cannot report a miss and a soft reject cannot fail a request.
 * {@code TransactionTypeService} therefore keeps the strict update as its own
 * method and {@code ReferenceBatchUpdateService} keeps the batch behaviour as
 * a second.
 *
 * <p>Assumptions: of those three behaviours, TWO are reachable as a single
 * published operation and the third is not, and the difference is registered
 * rather than resolved silently. {@code replace} on
 * {@code TransactionTypeService} is the strict update the list screen performs,
 * reporting the miss and inserting nothing, which is what the contract declares
 * by carrying 404 on that operation; {@code apply} on
 * {@code ReferenceBatchUpdateService} is the soft-reject run. The maintenance
 * screen's insert-on-miss has no operation of its own, because the contract
 * gives the create a 409 and the replace a 404 so that a duplicate and a miss
 * are each reportable, and an upsert can report neither. Making {@code replace}
 * an upsert instead was rejected outright: it would make the published 404
 * unreachable, so a client that mistyped a code would create a type rather than
 * be told the code does not exist. The divergence is
 * {@code D-REFERENCE-UPSERT-NOT-EXPOSED} in
 * {@code docs/architecture/cobol-to-service-traceability.md}, and
 * {@code ReferenceWriteBehaviourTest} asserts that the strict path writes
 * nothing on a miss and that the batch path applies actions after an earlier
 * reject.
 *
 * <ul>
 *   <li>⚠️ Refactoring Rationale: this list used to open "No lookup service", on the ground that the
 *       phone-area-code, state and state-zip-prefix tables "carry no rule beyond whether a code
 *       exists" and could therefore be read straight from this context's web adapters. Review
 *       found the claim false in practice. What the adapter actually held was not one presence
 *       check but three near-identical keyset browses -- a four-way selection per browse on the
 *       paging direction and the classification filter, the walk bound that makes the
 *       next-page flag derivable, the in-memory reversal of a backward page, the envelope
 *       assembly and three verbatim "NOT found" refusals. Paging IS a rule, it was stated three
 *       times, and it was stated in the layer the AAP's ports-and-adapters rule says reaches no
 *       store. {@code AddressLookupService} now owns all three browses, all three item reads and
 *       all three refusal sentences, and it is a member of this package on exactly the same terms
 *       as the others.</li>
 *   <li>No Spring Batch job, batch configuration class or batch starter. Assumptions:
 *       {@code COBTUPDT.cbl} is a plain sequential reader, a
 *       {@code PERFORM UNTIL LASTREC = 'Y'} loop at L93-L96 over a file declared
 *       {@code ORGANIZATION IS SEQUENTIAL}, ending in a return code rather than in a
 *       restartable chunk, so it migrates to a service method that the API and the batch
 *       chain both invoke. Job ownership belongs to {@code batch-service}.</li>
 *   <li>No queue consumer, and consequently no SQS configuration class or messaging property.
 *       ⚠️ Refactoring Rationale: this entry previously deferred a queue CLIENT to the sibling config
 *       package while this package held the consumer that used it. Both are withdrawn. The baseline
 *       drives BOTH inquiry programs from ONE request destination,
 *       {@code DEFINE QLOCAL('CARDDEMO.REQUEST.QUEUE')} at {@code app/app-vsam-mq/README.md} L53,
 *       aliased to CICS as {@code MQQUEUE(CARDREQ)} at L71, and the migrated topology provisions that
 *       one queue rather than one per consumer -- a queue admits exactly one owning consumer, because a
 *       receive hides the message from every other consumer rather than delivering a copy to each. The
 *       owner is {@code com.carddemo.account.service.InquiryMessageListener}, which dispatches on the
 *       request's four-character function code and renders the date answer from
 *       {@code com.carddemo.common.codec.DateInquiryReplyCodec}. Assumptions: what stays in this
 *       package is the date EVALUATION on {@code DateConversionService}, reached from the synchronous
 *       endpoint, which answers a different question -- it judges a date a caller submits, where the
 *       queue route emits the current system date and time and reads no field of its request. The
 *       baseline keeps them apart too: a search for {@code CSUTLDTC} across all 524 lines of
 *       {@code app/app-vsam-mq/cbl/CODATE01.cbl} returns zero occurrences.
 *       {@code ReferenceServiceStructureTest} asserts this package declares no queue binding, so the
 *       absence is measured rather than described.</li>
 *   <li>No second exception handling advice, per Contract 1.</li>
 *   <li>The date edit rules are not restated here. They live in
 *       {@code com.carddemo.common.validation.DateEditValidator} and
 *       {@code DateConversionService} delegates to them.
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
 *       Refactoring Rationale: this entry previously said the prohibition
 *       rests on review alone here, on the ground that the ArchUnit rule
 *       enforcing it scoped its subject set to
 *       {@code com.carddemo.common.money} and so never inspected a class in
 *       this package. That was true when it was written and is not true now:
 *       the rule's subject set was widened to every production type beneath
 *       {@code com.carddemo}, and it is re-run inside every module that
 *       consumes the shared test artifact, so it is evaluated against this
 *       package's own compiled classes. A {@code double} declared on a rate
 *       here fails the build rather than waiting for a reviewer. The entry
 *       is corrected rather than deleted because understating a gate has a
 *       cost of its own -- a reader told the check does not run has reason
 *       to add a second one, and the sibling {@code domain} charter already
 *       described the widened scope, so the two descriptors disagreed about
 *       one rule.</li>
 *   <li>What that gate does NOT establish is worth being equally clear
 *       about, so that a green build is not mistaken for proof of
 *       correctness. The rule reads declared member types; it does not read
 *       arithmetic. It cannot tell whether a scale is 2, whether a rounding
 *       mode is half-up, or whether a product was formed before a quotient
 *       as the accrual computation requires. Those properties are carried by
 *       {@code com.carddemo.common.money.Money} and by the unit tests over
 *       it, and they remain a review obligation on every method here.</li>
 *   <li>There is no offset pagination and no offset helper. Paging is
 *       keyset based throughout, carried by
 *       {@code com.carddemo.common.web.PageResponse}.
 *       Alternatives Considered: offset paging is shorter to write and was
 *       rejected because it skips and repeats rows under concurrent
 *       inserts, which the baseline's cursor paging does not do.</li>
 * </ul>
 *
 * <h2>Shared conventions inherited by every class in this package</h2>
 *
 * <ul>
 *   <li>Trim boundary. Assumptions: a {@code CHAR} key is never trimmed, because the
 *       declared width is part of the contract -- {@code type_cd CHAR(2)},
 *       {@code cat_cd CHAR(4)} and {@code acct_group_id CHAR(10)}. A {@code VARCHAR}
 *       description is trimmed on the way in and never padded again on the way out.</li>
 *   <li>Rates. {@code interest_rate} is {@code NUMERIC(6,2)} in PostgreSQL and
 *       {@code java.math.BigDecimal} at scale 2 on the entity, the target of the baseline's
 *       {@code PIC S9(04)V99}. The single conversion to
 *       {@code com.carddemo.common.money.Money} in this service is in the disclosure-group
 *       mapper, which performs no arithmetic. Assumptions:
 *       {@code com.carddemo.common.money.MoneyModule} binds its serialiser to the
 *       {@code Money} type, so a response field left as {@code BigDecimal} would quietly
 *       serialise as a JSON number where a JSON string is required.</li>
 *   <li>Verbatim messages. AAP Rule T8 applies character for character to every message
 *       literal carried across, trailing spaces, missing spaces after a period and
 *       inconsistent casing included. Assumptions: nothing is harmonised, so the three
 *       renderings of the same table reference stay apart --
 *       {@code 'Error reading TRANSACTION_TYPE table '} at {@code COTRTLIC.cbl} L1826,
 *       {@code ' TRANSACTION_TYPE Table. SQLCODE:'} at {@code COTRTUPC.cbl} L1571 and
 *       L1611, and {@code ' TRANSACTION_TYPE table. SQLCODE:'} at {@code COBTUPDT.cbl}
 *       L157, L188 and L219.</li>
 *   <li>Page envelope assembly. {@code PageResponse} carries the item list, an opaque
 *       nullable first key, an opaque nullable last key, a boolean saying whether a
 *       further page follows and a boolean saying whether an earlier page exists -- no page
 *       size, page number or total count.
 *       Mappers supply item types only, so assembling the envelope is this layer's job, and that
 *       includes both availability booleans: only the walk issued here reads the surplus row either
 *       answer rests on, and neither is inferred from a boundary key being present.</li>
 *   <li>Concurrency. The two tables this context maintains each carry a
 *       {@code version BIGINT NOT NULL DEFAULT 0} column, so the before-image comparison the
 *       baseline decides at {@code COTRTUPC.cbl} L1585 is delegated to the persistence
 *       provider through a {@code @Version} attribute. Refactoring Rationale: a
 *       provider-managed version compares one column under the row lock the UPDATE already
 *       takes, whereas a hand-rolled comparison would have to re-read every field and could
 *       still lose a write interleaved between that re-read and the UPDATE. The seeded lookup
 *       tables carry no such column because no operation updates them. Assumptions: the
 *       exception the provider throws is discriminated by fully qualified class name, because
 *       {@code common-lib} has no JPA dependency and so cannot name it by type; the shared
 *       handler walks the cause chain by name instead.</li>
 * </ul>
 *
 * <p>Assumptions: where the target's behaviour departs from the baseline's, the baseline is
 * left exactly as it stands and the departure is recorded in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which owns that register and is
 * not authored or edited from here.
 */
package com.carddemo.reference.service;
