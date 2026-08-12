/**
 * REST layer of transaction-service: the only place in this module where an HTTP request becomes a
 * call and an outcome becomes a status code.
 *
 * <p>Three concerns belong here and nothing else does. A handler binds and validates its inbound
 * request as transport, delegates to exactly one method one layer down, and maps what that method
 * returned onto an HTTP response. Business behaviour lives in
 * {@code com.carddemo.transaction.service}, which is the division the migration plan assigns at its
 * section 0.4.1.2 where it describes a REST layer carrying validation and no business logic. A type
 * here that computed a balance, formatted a timestamp or issued a query would be in the wrong
 * package.</p>
 *
 * <h2>Target contract, not a directory listing</h2>
 *
 * <p>Assumptions: every roster, name and prohibition in this charter states the package's target
 * contract as the migration plan assigns it, and not a census of the directory that holds the
 * charter. The roster below is closed: a type in this directory that the roster does not name is
 * outside the contract rather than merely new.</p>
 *
 * <p>Alternatives Considered: deriving the roster from the directory instead of from the plan and
 * the published contract. Rejected because a roster describing whatever happens to be present cannot
 * say what may <em>not</em> be added, and that is the half of a package contract a reader cannot
 * reconstruct from the files beside it.</p>
 *
 * <h2>Roster, and the operations it carries</h2>
 *
 * <p>Two adapters belong to this package and the set is closed. Their contract of record is the
 * hand-authored OpenAPI 3.1 document at
 * {@code services/transaction-service/src/main/resources/openapi/transaction-api.yaml}, which
 * declares {@code openapi: 3.1.1} on its line 125 and settles the paths, the parameter and property
 * names, the response shapes and the error vocabulary. It declares four synchronous operations.
 * Each is named below by the operation identifier it carries there, by the CICS transaction
 * identifier and the official screen name the online-components table of the root
 * {@code README.md} gives it, and by the baseline program that is its specification.</p>
 *
 * <p>{@code TransactionController} carries three of the four, on the collection path
 * {@code /api/v1/transactions} declared at line 298 of that contract and on the member path beneath
 * it declared at line 528.</p>
 *
 * <ul>
 *   <li>{@code listTransactions}, contract line 306, transaction {@code CT00}, official name
 *       <b>Transaction List</b>, migrated from {@code app/cbl/COTRN00C.cbl}: one bounded page
 *       positioned by key rather than by offset, served by {@code GET} on the collection path</li>
 *   <li>{@code addTransaction}, contract line 375, transaction {@code CT02}, official name
 *       <b>Transaction Add</b>, migrated from {@code app/cbl/COTRN02C.cbl}: the capture of one
 *       transaction with its confirmation explicitly given, served by {@code POST} on that same
 *       collection path</li>
 *   <li>{@code viewTransaction}, contract line 546, transaction {@code CT01}, official name
 *       <b>Transaction View</b>, migrated from {@code app/cbl/COTRN01C.cbl}: the read of one
 *       transaction, served by {@code GET} on the member path
 *       {@code /api/v1/transactions/{transactionId}}</li>
 * </ul>
 *
 * <p>{@code BillPaymentController} carries the fourth, {@code payAccountBalanceInFull} at contract
 * line 670, transaction {@code CB00}, official name <b>Bill Payment</b>, migrated from
 * {@code app/cbl/COBIL00C.cbl}: the settlement of an account balance in full with the payment
 * explicitly confirmed, served by {@code POST} on {@code /api/v1/billpay}, declared at contract
 * line 660.</p>
 *
 * <p>Alternatives Considered: the official name of {@code CT01} is <b>Transaction View</b> and this
 * charter uses that wording rather than the intuitive "transaction detail". The point is recorded
 * because the response type this operation returns is named for the detail it carries, so a reader
 * moving between the two vocabularies has one name for the screen and another for the payload;
 * paraphrasing the screen name to match the payload would silently break the correspondence with
 * the table that assigns it.</p>
 *
 * <p>Alternatives Considered: bill payment sits on its own adapter rather than joining the three
 * transaction operations on one, and the per-program split it uses here was chosen rather than
 * inherited. The baseline gives it a separate transaction and a separate screen, its path is a
 * sibling of the transaction collection rather than a member of it, and its request shape shares no
 * property with the capture's. A single adapter would have grouped four handlers whose only common
 * factor is the schema beneath them. Trade-offs: a reader looking for every write in this context
 * now opens two files instead of one, which is accepted because the alternative buries the
 * resource boundary the contract itself draws.</p>
 *
 * <p>Alternatives Considered: a shared abstract base adapter beneath the two, holding whatever they
 * have in common. Rejected because they have nothing in common to hold. Their handler signatures
 * differ in arity, in HTTP method and in what they return, so a base class would carry no behaviour
 * and would only add a level of indirection between a request mapping and the baseline program it
 * re-expresses. Constructor injection of the services each one actually needs is what the two share
 * instead, and that is a pattern rather than a supertype.</p>
 *
 * <h2>What this package deliberately does not serve</h2>
 *
 * <p>Assumptions: the online-components table names a fifth entry alongside the four above,
 * transaction {@code CR00}, official name <b>Transaction Reports</b>, program
 * {@code app/cbl/CORPT00C.cbl}. It is <b>not</b> exposed here and its absence is a decision rather
 * than an omission: the migration plan assigns that program to reporting-service, whose own REST
 * layer serves it, so a reader who finds four operations where the table shows five has found the
 * boundary and not a gap. Nothing in this package may grow a report endpoint, because doing so
 * would put one screen's surface in two bounded contexts at once.</p>
 *
 * <h2>Baseline lineage</h2>
 *
 * <p>This package re-expresses the presentation surface of four online COBOL programs. They are
 * reference material, read as the specification and never modified, and they are cited by path and
 * line so that any claim made here can be checked against them. Each is reached in the baseline by
 * a CICS transaction whose resource definition in {@code app/csd/CARDDEMO.CSD} opens with a
 * {@code DEFINE TRANSACTION} header and names its program on the following line.</p>
 *
 * <ul>
 *   <li>{@code app/cbl/COTRN00C.cbl}, 699 lines, the transaction list, reached by {@code CT00},
 *       defined from {@code app/csd/CARDDEMO.CSD:419} and naming the program at {@code :420}</li>
 *   <li>{@code app/cbl/COTRN01C.cbl}, 330 lines, the transaction view, reached by {@code CT01},
 *       defined from {@code :429} and naming the program at {@code :430}</li>
 *   <li>{@code app/cbl/COTRN02C.cbl}, 783 lines, the transaction add, reached by {@code CT02},
 *       defined from {@code :439} and naming the program at {@code :440}</li>
 *   <li>{@code app/cbl/COBIL00C.cbl}, 572 lines, the bill payment, reached by {@code CB00}, defined
 *       from {@code :337} and naming the program at {@code :338}</li>
 * </ul>
 *
 * <p>Assumptions: all four of those transaction definitions declare {@code TWASIZE(0)}, on lines
 * {@code :420}, {@code :430}, {@code :440} and {@code :338} respectively, so the per-task work area
 * each of them could have reserved is zero bytes wide in every one. That is corroboration from the
 * baseline's own resource definitions that a stateless boundary is faithful to it rather than a
 * departure from it, and it is worth recording because the argument for statelessness below would
 * otherwise rest on the target design alone.</p>
 *
 * <h2>What replaces the communication area</h2>
 *
 * <p>The baseline is strictly pseudo-conversational: the task ends at every screen turn, so all
 * continuity between turns lives in one passed structure. That structure is declared once, at
 * {@code app/cpy/COCOM01Y.cpy:19-44} as {@code CARDDEMO-COMMAREA}, and is shared by every online
 * program. It does not travel here, and no part of it is echoed back to a caller. It decomposes
 * into three surviving mechanisms and one that has no counterpart at all.</p>
 *
 * <ul>
 *   <li>Identity, carried by {@code CDEMO-USER-ID PIC X(08)} at {@code app/cpy/COCOM01Y.cpy:25} and
 *       {@code CDEMO-USER-TYPE PIC X(01)} at {@code :26} with its two condition names for
 *       {@code 'A'} at {@code :27} and {@code 'U'} at {@code :28}, arrives instead as claims on a
 *       validated bearer token. The {@code cognito:groups} claim is converted into authorities by
 *       {@code com.carddemo.common.security.JwtRoleConverter}, which the sibling
 *       {@code com.carddemo.transaction.config.SecurityConfig} wires into a
 *       {@code JwtAuthenticationConverter}. The authority names are the group names verbatim,
 *       {@code carddemo-admin} and {@code carddemo-user}, so every predicate is an authority
 *       predicate such as {@code hasAuthority} or {@code hasAnyAuthority} and never
 *       {@code hasRole}, whose prefix convention those verbatim names do not follow</li>
 *   <li>Selection context, carried by {@code CDEMO-CUST-ID PIC 9(09)} at
 *       {@code app/cpy/COCOM01Y.cpy:33}, {@code CDEMO-ACCT-ID PIC 9(11)} at {@code :38} and
 *       {@code CDEMO-CARD-NUM PIC 9(16)} at {@code :41}, arrives as path and query parameters,
 *       which is what makes each request self-describing and therefore independently
 *       authorizable</li>
 *   <li>The browse cursor of the transaction list travels in the page envelope
 *       {@code com.carddemo.common.web.PageResponse}, through its first-key and last-key members,
 *       with its has-next and has-previous members each settled by a surplus row read in that
 *       direction, so the list operation positions by key and never by offset. Assumptions:
 *       has-previous is one of the envelope's five components and is not read off the first-key
 *       member, whose presence answers where a retreat resumes from rather than whether one
 *       exists. Sealing and opening
 *       that cursor is {@code com.carddemo.common.web.CursorToken}, held by the adapter rather than
 *       by the service beneath it, because the token's key material is infrastructure the service
 *       layer must not reach for. That is binding work rather than a business rule</li>
 *   <li>Navigation, carried by {@code CDEMO-FROM-TRANID PIC X(04)} at
 *       {@code app/cpy/COCOM01Y.cpy:21}, {@code CDEMO-FROM-PROGRAM PIC X(08)} at {@code :22},
 *       {@code CDEMO-TO-TRANID PIC X(04)} at {@code :23} and {@code CDEMO-TO-PROGRAM PIC X(08)} at
 *       {@code :24}, together with {@code CDEMO-LAST-MAP PIC X(7)} at {@code :43} and
 *       {@code CDEMO-LAST-MAPSET PIC X(7)} at {@code :44}, is entirely client-side. No response
 *       leaving this package names a next program or a next screen, and there is no server-side
 *       next-program field of any kind</li>
 * </ul>
 *
 * <p>Refactoring Rationale: the re-entry discriminator {@code CDEMO-PGM-CONTEXT PIC 9(01)},
 * declared at {@code app/cpy/COCOM01Y.cpy:29} with an enter value at {@code :30} and a re-enter
 * value at {@code :31}, has no counterpart here, and the absence is deliberate. What was wrong with
 * the approach being replaced is precise rather than aesthetic: continuity lived in a structure the
 * client held between turns and handed back, so every screen turn depended on state the server had
 * to remember about the last one, and a handler had to branch on which turn it was in before it
 * could decide what to render. A stateless handler that answers with HTTP 400 and a per-field error
 * array has no first-entry-against-re-entry distinction left to draw, so error rendering is driven
 * solely by response data. The concrete gain is twofold. Nothing here holds state between requests:
 * {@code SecurityConfig} sets stateless session management, so there is no sticky session, no
 * server-side session store and no affinity requirement on the load balancer in front of it, which
 * is what lets the tasks running this package scale horizontally. And no handler carries a
 * first-entry branch, so a reader will not find one to maintain. No {@code isResubmit},
 * {@code firstEntry}, {@code turnCount} or re-enter concept may be introduced to this package under
 * any name.</p>
 *
 * <p>Refactoring Rationale: reading the user type from a signed claim rather than from that passed
 * structure closes an exposure instead of merely relocating a field. In the baseline the structure
 * is storage the client holds between turns and hands back, so the one-character user type is
 * client-asserted and a caller could in principle present its own. A group claim on a validated
 * token is not something a caller can assert, because the token is signed by its issuer and
 * verified here, so the authority an operation requires rests on something this service checks. The
 * baseline behaves as just described; the target reads the signed claim; the divergence is
 * documented here and in the traceability matrix rather than being introduced silently.</p>
 *
 * <h2>Layering, and which half of it a build can check</h2>
 *
 * <p>What this package may reference is a short list: the sibling
 * {@code com.carddemo.transaction.dto} types that carry request and response shapes, the sibling
 * {@code com.carddemo.transaction.service} types it delegates to, and the shared kernel packages
 * {@code com.carddemo.common.web}, {@code com.carddemo.common.error},
 * {@code com.carddemo.common.money} and {@code com.carddemo.common.security}. What it may not
 * reference is shorter and matters more: not {@code com.carddemo.transaction.repository}, not a
 * {@code com.carddemo.transaction.domain} entity, not {@code com.carddemo.transaction.mapper}, not
 * the {@code domain} package of any other service, and no business rule of its own.</p>
 *
 * <p>Assumptions: the four business rules this package delegates to live behind exactly four
 * {@code @Service} types in {@code com.carddemo.transaction.service} --
 * {@code TransactionListService}, {@code TransactionViewService}, {@code TransactionAddService} and
 * {@code BillPaymentService} -- one per operation, so every decision the baseline makes is made
 * there and can be tested without a servlet. Data access lives in
 * {@code com.carddemo.transaction.repository}. Copybook representation concerns, meaning the
 * declared field widths, sign overpunch, dropped {@code FILLER}, masking and the migration's
 * renamings of misspelled baseline fields, live only in {@code com.carddemo.transaction.mapper},
 * and keeping them in one package is what lets everything downstream of it work in clean types.</p>
 *
 * <p>Assumptions: those prohibitions do not all rest on the same mechanism, and a reader who
 * assumes they do will trust one of them further than this build warrants. The cross-context half
 * is mechanical: rule A2 of
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * fails any type under one CardDemo package root that depends on a domain type owned by a different
 * root, its rule A3 fails any production type declaring a binary floating point member, and that
 * rule set is published by the shared kernel as a test artifact and re-evaluated against this
 * module's own compiled types by the {@code architecture-rules} Surefire execution in
 * {@code services/pom.xml}. The intra-context half, that a handler here does not reach this
 * context's own repository, entity or mapper types, is this charter plus review, and no gate in
 * this build expresses it. Recording which is which is the whole point of the paragraph: a boundary
 * believed to be checked and in fact unchecked is worse than one known to rest on review.</p>
 *
 * <p>Alternatives Considered: configuring Checkstyle's {@code ImportControl} module so that the
 * intra-context half became mechanical too. Rejected, and the exclusion is recorded in
 * {@code config/checkstyle/checkstyle.xml} itself rather than only here. Layering in this
 * repository is owned by the ArchUnit rule set named above, and that rule set carries a constraint
 * {@code ImportControl} cannot state at all, the prohibition on binary floating point anywhere in
 * the money path. Configuring both would leave two engines enforcing overlapping halves of one
 * charter, leaving a reader unable to tell which of them owned a given boundary, so whichever is
 * cheaper to silence is the one that gets silenced.</p>
 *
 * <h2>Shared contracts, consumed and never re-declared</h2>
 *
 * <p>Alternatives Considered: a package-local advice translating this context's failures into
 * response bodies. Rejected. The single {@code @RestControllerAdvice} in this project is
 * {@code com.carddemo.common.error.GlobalExceptionHandler}, owned by the shared kernel, and it is
 * the one that maps an optimistic-lock failure and a foreign key declared
 * {@code ON DELETE RESTRICT} onto HTTP 409. <b>No type in this package declares
 * {@code @RestControllerAdvice} or {@code @ExceptionHandler}</b>, and none may be added that does.
 * The concrete failure behind the prohibition is that the kernel guards its advice with a
 * missing-bean condition keyed on its own type, so a local advice of some other type would not
 * displace it but would stand beside it, and two advices with no declared precedence leave it
 * unpredictable which of them renders any given failure.</p>
 *
 * <p>Assumptions: that advice reaches this module through the one auto-configuration type the
 * shared kernel contributes, {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, and not
 * through anything declared in this service. The distinction is worth stating because the component
 * scan this module's application class establishes is rooted at {@code com.carddemo.transaction}
 * while every shared component lives under {@code com.carddemo.common}, so nothing under the shared
 * package is discovered here by scanning, and a reader could reasonably expect a local bean method
 * to bridge the gap. The sibling {@code com.carddemo.transaction.config.OpenApiConfig} records that
 * the advice, the money codec module and the metrics configuration all arrive by that
 * auto-configuration and that it declares none of the three, so the reason those bean methods are
 * absent sits beside the class rather than having to be inferred.</p>
 *
 * <p>Assumptions: the same reasoning governs every other cross-cutting concern this package relies
 * on, so not one of them is declared here. The correlation-identifier filter
 * {@code com.carddemo.common.web.CorrelationIdFilter} and the conversion of group claims into
 * authorities by {@code com.carddemo.common.security.JwtRoleConverter} are wired in
 * {@code com.carddemo.transaction.config}; the page envelope, the cursor seam, the error body and
 * the money type come from {@code com.carddemo.common}. No local error type, no local pagination
 * type, no local money type, no local validation-flag type and no local message catalog is declared
 * in this package, and none may be added to it. The concrete failure a duplicate would cause is
 * worth naming rather than leaving to inference: a second money type would carry its own
 * serialisation, so the wire format of an amount would depend on which service rendered it, and a
 * second message catalog would let one screen's wording drift from another's while both still
 * claimed to reproduce the same baseline text. The house precedent for that discipline predates
 * the Java: {@code tests/README.md:540-542} directs the COBOL unit tests to resolve record layouts
 * through the compiler copybook path rather than restating a layout, and to keep it single-sourced
 * from {@code app/cpy/}. The Java analogue is exact -- one former {@code COPY} statement becomes
 * one type import from the single package that owns that contract.</p>
 *
 * <h2>Response shape: the message line and the money line</h2>
 *
 * <p>Assumptions: the aggregate message line every response here can carry is the 75-character form
 * the baseline declares in {@code app/cpy/CVCRD01Y.cpy}, as {@code CCARD-ERROR-MSG PIC X(75)} at
 * its line 28 and {@code CCARD-RETURN-MSG PIC X(75)} at its line 29, and the target carries it as
 * the single latched member of {@code com.carddemo.common.error.ApiError} rather than as one entry
 * in the per-field array beside it. The {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES} condition
 * attached to it at line 30 maps to <b>null or absent</b>, never to an empty string. That mapping
 * is load-bearing and not a nicety: {@code LOW-VALUES} means the message is off while
 * {@code SPACES} means the message is explicitly blank, and the two are not interchangeable,
 * because the baseline reads the off state as control flow -- an aggregate sentence is written only
 * inside a test of that condition, so it latches to the first failure while the per-field markers
 * beside it accumulate. Collapsing both sentinels to an empty string would erase that distinction
 * and, with it, the difference between reporting the first failure and reporting the last.</p>
 *
 * <p>Assumptions: the four programs named above reach that contract by a different route, and the
 * difference is recorded so a reader comparing widths is not misled. None of the four copies
 * {@code CVCRD01Y}; each declares its own {@code WS-MESSAGE PIC X(80) VALUE SPACES} in working
 * storage, at {@code app/cbl/COTRN00C.cbl:38}, {@code app/cbl/COTRN01C.cbl:38},
 * {@code app/cbl/COTRN02C.cbl:38} and {@code app/cbl/COBIL00C.cbl:39}, and renders it into a
 * 78-character screen field, {@code ERRMSGO PIC X(78)} at {@code app/cpy-bms/COTRN00.CPY:728},
 * {@code app/cpy-bms/COTRN01.CPY:272}, {@code app/cpy-bms/COTRN02.CPY:272} and
 * {@code app/cpy-bms/COBIL00.CPY:140}. So three widths appear in one path -- 80 in working storage,
 * 78 on the screen, 75 in the shared contract -- and the target carries the shared 75-character
 * contract because that is the one every service in this migration answers to. These four also
 * clear their own message with {@code MOVE SPACES} rather than to low values and never test it,
 * gating instead on a separate {@code WS-ERR-FLG} switch, so the control-flow reading above is
 * attributed to the shared contract that declares it and not to these programs.</p>
 *
 * <p>Assumptions: money crosses this boundary as a <b>JSON string</b>, never a JSON number. It is
 * held as {@code com.carddemo.common.money.Money} at scale 2 with half-up rounding, and
 * {@code com.carddemo.common.money.MoneyModule} performs the encoding by binding a serialiser and
 * a deserialiser to that one type, so no adapter here formats an amount itself and no client can
 * parse one into an IEEE-754 binary floating point value. Most clients would parse a JSON number
 * into a double, which destroys exactness at the boundary a user actually reads. The prohibition on
 * {@code float} and {@code double} in the money path is rule A3 of the ArchUnit rule set named
 * above, so it is a failing test rather than a convention.</p>
 *
 * <h2>Consumers of this boundary</h2>
 *
 * <p>Assumptions: this package's consumers are the sibling {@code dto}, {@code service} and
 * {@code config} packages together with {@code com.carddemo.common}, and beyond the module boundary
 * the browser client written against the contract of record named above --
 * {@code ui/src/api/transactions.ts}, which binds to all four operation identifiers and paths, and
 * {@code ui/src/api/types.ts}, which mirrors the shapes they exchange. A change to a path, a
 * parameter name or a response property here is a change to that contract and to those two files,
 * which is why the contract is a committed document reviewable line by line rather than something
 * assembled at run time from annotations.</p>
 *
 * <p>Assumptions: the documentation convention this charter and every type beside it follow is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, which is where the rationale-label spellings, the
 * scope of the paired command-block idiom and the Checkstyle checks that enforce presence are
 * stated. They are not restated here, because one written convention with one home is the property
 * that makes a repository-wide search for a rationale complete rather than silently partial.</p>
 */
package com.carddemo.transaction.api;
