/**
 * REST boundary of the card bounded context, and the only place in this context where an HTTP
 * request becomes a call and an outcome becomes a status code.
 *
 * <p>Three responsibilities belong here and nothing else does: binding an HTTP request, validating
 * that request declaratively, and mapping an outcome onto a status code. Business rules live one
 * layer down, in {@code com.carddemo.card.service}, which is the shape AAP 0.4.1.2 assigns this
 * layer. Persistence lives further down again, in the repository layer, and no type in this package
 * reaches it directly.
 *
 * <h2>Roster, and the operations it carries</h2>
 *
 * <p>One controller belongs to this package, {@code CardController}, and the set is closed. Its
 * contract of record is the hand-authored OpenAPI 3.1 document at
 * {@code services/card-service/src/main/resources/openapi/card-api.yaml}, which settles the paths,
 * the parameter and property names, the response shapes and the error vocabulary. Each operation
 * below is named by the operation identifier it carries there, so a reader can move between this
 * charter and the contract without guessing which entry answers to which.
 *
 * <ul>
 *   <li>{@code listCards}, one page of the card list positioned by key and never by a row count,
 *       re-expressing the browse of {@code app/cbl/COCRDLIC.cbl}</li>
 *   <li>{@code lookupCard}, the resolution of a card number a user typed into the card it names.
 *       It is the one operation that accepts a full primary account number, which is why the number
 *       travels in a request BODY</li>
 *   <li>{@code getCard}, one card with its primary account number rendered to its last four
 *       digits</li>
 *   <li>{@code getAdminCardDetail}, the administrative read of that same card carrying the full
 *       number, under its own path prefix and its own authority</li>
 *   <li>{@code updateCard}, the update of the three editable members of one card</li>
 * </ul>
 *
 * <p>Assumptions: the count is five and it is measured rather than remembered.
 * {@code CardApiContractTest.theContractPublishesExactlyTheFiveContractedOperations} holds the
 * contract to exactly these five, and
 * {@code com.carddemo.card.api.CardControllerContractCensusTest} reads every {@code operationId} out
 * of the packaged contract and requires a mapped handler of the same name for each, so a claim that
 * an operation is served cannot outlive the handler that serves it.</p>
 *
 * <p>Alternatives Considered: one controller per reference program, splitting {@code COCRDLIC},
 * {@code COCRDSLC} and {@code COCRDUPC} class for class. Rejected because this bounded context owns a
 * single aggregate, the {@code card.cards} table AAP 0.4.1.3 assigns it, and the five operations are
 * five views of that one aggregate: the update writes the row the detail read returns, the lookup
 * resolves which row a typed number names, and the administrative read widens what is rendered of one
 * member of the same row. Splitting one aggregate's HTTP surface over three files would add no seam the
 * domain has and would leave no single file whose shape can be read against the contract as a whole.
 * The trade accepted is one class carrying five handlers, which is a size this layer can hold precisely
 * because it holds no business rules.
 *
 * <h2>Card numbers, selectors and masking</h2>
 *
 * <p>Assumptions: the single-card path carries an OPAQUE SELECTOR and not the card number. The selector
 * is minted by this service under the shared sealer, is bound to the resource, the authenticated subject
 * and the query scope, carries no card number and cannot be forged from one; every card response
 * publishes it as its {@code key} member, so a client always holds the value its next request needs.</p>
 *
 * <p>Assumptions: a primary account number therefore appears in no request line this package serves.
 * The load balancer writes its own access-log record from the request line, inside the process that
 * terminates the connection and before any application code runs, and access logging is mandatory with
 * no input able to disable it -- so a number in a path or a query filter would leave a durable copy in
 * an object store that no masking downstream can unwrite. What {@code CardNumberMasker} does bound is
 * this service's own log lines and error bodies, which is worth having and is retained. The number is
 * confined to the {@code lookupCard} request body, which no access log records, and the administrative
 * read carries its own path prefix so its authority does not depend on rule ordering.</p>
 *
 * <p>Trade-offs: two costs land on the caller and neither is hidden. A client cannot build a card route
 * from a number a user typed without one call to {@code lookupCard} first, and a selector expires with
 * the configured token lifetime, so a bookmarked card URL eventually stops resolving and the number has
 * to be entered again. Both are accepted because the alternative is a permanent record of cardholder
 * data in object storage, which is not a cost that trades against an interaction.</p>
 *
 * <h2>Validation and error shapes</h2>
 *
 * <p>Assumptions: {@code updateCard} answers HTTP 409 when an update cannot be applied against the
 * persisted state, and three distinct conditions reach that status without being merged: the row could
 * not be taken for update, the write itself did not succeed, or the row had changed since the caller
 * read it. The third is the optimistic-concurrency conflict and the only one carrying a refreshed card
 * back, because it is the only one for which a current representation exists. Each condition carries its
 * own subordinate code and its own sentence, reproduced character for character from
 * {@code app/cbl/COCRDUPC.cbl:205-210}. The conflict is detected on the version column
 * {@code services/card-service/src/main/resources/db/migration/V1__card.sql} declares at its line 303,
 * so this package neither detects the conflict nor composes the body: it reports the status the contract
 * declares.</p>
 *
 * <h2>Baseline lineage</h2>
 *
 * <p>This package re-expresses the presentation surface of three online COBOL programs. They are
 * reference material, read as the specification and never modified, and they are cited by path and line
 * so that any claim made here can be checked against them.</p>
 *
 * <ul>
 *   <li>{@code app/cbl/COCRDLIC.cbl}, the card list, reached by CICS transaction {@code CCLI}, whose
 *       resource definition opens at {@code app/csd/CARDDEMO.CSD:357} and names the program at
 *       {@code :358}</li>
 *   <li>{@code app/cbl/COCRDSLC.cbl}, the card detail, reached by {@code CCDL}, defined from
 *       {@code app/csd/CARDDEMO.CSD:347} and naming the program at {@code :348}</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl}, the card update, reached by {@code CCUP}, defined from
 *       {@code app/csd/CARDDEMO.CSD:367} and naming the program at {@code :369}</li>
 * </ul>
 *
 * <p>Assumptions: all three definitions declare {@code TWASIZE(0)}, at {@code :358}, {@code :348} and
 * {@code :369}, so the per-task work area these transactions could have reserved is zero bytes wide in
 * every one of them. That is corroboration from the baseline's own resource definitions that a stateless
 * boundary is faithful to it rather than a departure from it.</p>
 *
 * <h2>What replaces the communication area</h2>
 *
 * <p>The baseline is pseudo-conversational: each transaction ends at every screen turn, so all
 * continuity between turns lives in one passed structure, {@code CARDDEMO-COMMAREA}, declared at
 * {@code app/cpy/COCOM01Y.cpy:19-44} and included by all three programs. It does not travel here and no
 * part of it is echoed back to a caller. It decomposes into four mechanisms.</p>
 *
 * <ul>
 *   <li>Identity, carried by {@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE} at
 *       {@code app/cpy/COCOM01Y.cpy:25-28}, arrives as claims on a validated bearer token. The authority
 *       each operation requires is asserted by the filter chain declared in
 *       {@code com.carddemo.card.config} and never inside a controller method body</li>
 *   <li>Selection context, carried by {@code CDEMO-ACCT-ID} at {@code :38} and
 *       {@code CDEMO-CARD-NUM} at {@code :41}, arrives as the sealed path selector on the three
 *       single-card operations and as a REQUEST BODY member on the two collection operations, which
 *       is what makes each request self-describing and therefore independently authorizable.
 *       ⚠️ Refactoring Rationale: this said "the path selector and as query parameters", and this
 *       context publishes no query parameter at all -- {@code card-api.yaml} declares exactly two
 *       reusable parameters, one path and one header. Both of the values named here are ones the
 *       migration's sensitive-data contract keeps out of a request line, because the load balancer
 *       writes that line into its access log before any application code can redact it: the card
 *       number travels in {@code CardLookupRequest} and the account identifier in
 *       {@code CardPageQuery}. A charter describing them as query parameters describes the exact
 *       disclosure this package's shape exists to prevent</li>
 *   <li>The browse cursor, which {@code app/cbl/COCRDLIC.cbl} keeps in that same structure as a
 *       last-key pair, a first-key pair, a screen number and a next-page indicator at
 *       {@code :230-244}, travels in the page envelope
 *       {@code com.carddemo.common.web.PageResponse} through its {@code firstKey},
 *       {@code lastKey} and {@code hasNext} members. Assumptions: the screen number is the one
 *       field of the four with no envelope member, because the reference answers "is there an
 *       earlier page" from that ordinal and never from a read of the file -- it refuses the
 *       backward step on the opening page at {@code :902-903} on exactly that condition, with the
 *       notice at {@code :1301-1302}, and moves the ordinal itself at {@code :492} and
 *       {@code :508}. Its migrated home is therefore the browser client's navigation state, and
 *       the envelope carries the backward POSITION, {@code firstKey}, rather than a backward
 *       availability claim</li>
 *   <li>Navigation, carried by the from-program and to-program fields and their transaction
 *       counterparts at {@code app/cpy/COCOM01Y.cpy:21-24}, is entirely client-side. No response
 *       leaving this package names a next program or a next screen</li>
 * </ul>
 *
 * <p>Assumptions: the re-entry discriminator {@code CDEMO-PGM-CONTEXT}, declared at
 * {@code app/cpy/COCOM01Y.cpy:29-31} with an enter value and a re-enter value, has no counterpart here at
 * all. A stateless handler that answers with a per-field error array has no
 * first-entry-against-re-entry distinction left to make, so field-level error presentation is driven by
 * the response body alone and by nothing the server remembers. Nothing in this package holds state
 * between requests -- no sticky session, no server-side session store and no affinity requirement on the
 * load balancer in front of it -- which is what lets the tasks running it scale horizontally.</p>
 *
 * <h2>Shared contracts, consumed and never re-declared</h2>
 *
 * <p>Alternatives Considered: a package-local exception advice translating this context's failures into
 * response bodies. Rejected. The single advice
 * {@code com.carddemo.common.error.GlobalExceptionHandler} is owned by the shared kernel, per AAP
 * 0.5.1.1, and reaches this service through
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}. Rule T2 forbids re-declaring a shared
 * concern per service, and a concrete failure sits behind that rule here: the kernel guards its advice
 * with a missing-bean condition keyed on its own type, so a local advice of some other type would stand
 * beside it rather than displace it, and two advices with no declared precedence leave it unpredictable
 * which renders any given failure. The three subordinate codes behind HTTP 409 would be the first thing
 * lost, because a caller that distinguishes them needs the body shape the contract declares.</p>
 *
 * <p>Assumptions: the same reasoning governs every other cross-cutting concern this package relies on,
 * so none is declared here. The correlation-identifier filter and the conversion of group claims into
 * authorities are wired in {@code com.carddemo.card.config}; the page envelope, the error body and the
 * timestamp form come from {@code com.carddemo.common}. No local error type, no local pagination type, no
 * local exception class and no local message catalog is declared in this package.</p>
 */
package com.carddemo.card.api;
