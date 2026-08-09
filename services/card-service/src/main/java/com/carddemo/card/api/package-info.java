/**
 * REST boundary of the card bounded context, and the only place in this context where an HTTP
 * request becomes a call and an outcome becomes a status code.
 *
 * <p>Three responsibilities belong here and nothing else does: binding an HTTP request, validating
 * that request declaratively, and mapping an outcome onto a status code. Business rules live one
 * layer down, in {@code com.carddemo.card.service}, which is the shape the migration plan assigns
 * this layer at its section 0.4.1.2 where it describes a REST layer carrying validation and no
 * business logic. Persistence lives further down again, in the repository layer, and no type in
 * this package reaches it directly.
 *
 * <h2>Roster, and the operations it carries</h2>
 *
 * <p>One controller belongs to this package, {@code CardController}, and the set is closed. Its
 * contract of record is the hand-authored OpenAPI 3.1 document at
 * {@code services/card-service/src/main/resources/openapi/card-api.yaml}, which settles the paths,
 * the parameter and property names, the response shapes and the error vocabulary. That document
 * declares <b>five</b> synchronous operations. Each is named below by the operation identifier it
 * carries there, so that a reader can move between this charter and the contract without guessing
 * which entry answers to which.
 *
 * <p>Assumptions: <b>the count is five</b>, and it is measured rather than remembered. The contract
 * publishes {@code listCards}, {@code lookupCard}, {@code getCard}, {@code updateCard} and
 * {@code getAdminCardDetail} and nothing else, the list below holds exactly those five entries, and
 * {@code CardApiContractTest.theContractPublishesExactlyTheFiveContractedOperations} is what keeps that
 * true rather than merely asserted.
 *
 * <p>Refactoring Rationale: this preamble said four while the list immediately below it held five
 * entries and the paragraph after it referred to "those five operations", so the file contradicted
 * itself in the one place a reader consults it -- the roster's own count. The history is worth stating
 * because the number has now been wrong in both directions: an earlier revision read five here against
 * a four-entry list, and the correction applied then moved the preamble instead of the list, which
 * turned an overcount into an undercount and left the resolution note asserting that no fifth operation
 * had ever existed. It had: {@code lookupCard} resolves a typed card number to the card it names, and
 * it is the one operation that accepts a full primary account number, which is why it takes the number
 * in a request BODY. The lesson recorded rather than the number: a count belongs beside the roster that
 * justifies it, and the entries below are the authority for this one.
 *
 * <p><b>Assumptions: {@code CardController} is authored and every one of the five operations is
 * served.</b> {@code CardController.java} sits beside this charter and mounts all five, so the chain is
 * complete end to end: the request and response shapes in {@code com.carddemo.card.dto}, the mapper in
 * {@code com.carddemo.card.mapper}, the repository in {@code com.carddemo.card.repository}, the entity
 * and the sealed selector in {@code com.carddemo.card.domain}, the read and write services in
 * {@code com.carddemo.card.service}, the configuration classes in {@code com.carddemo.card.config}, and
 * the adapter that binds them to the published paths.
 *
 * <p>Refactoring Rationale: this paragraph previously stated that no {@code CardController.java} existed
 * in this directory and that none of the five operations was served. That was accurate while the package
 * held this charter alone and is not accurate now, so it is restated rather than left to be discovered by
 * a reader who opened the directory. The delivery state is asserted mechanically as well as described
 * here, which is what keeps the two from parting again:
 * {@code com.carddemo.card.api.CardControllerContractCensusTest} reads every {@code operationId} out of
 * the packaged contract and requires a mapped handler of the same name for each, so a claim that an
 * operation is served cannot outlive the handler that serves it.
 *
 * <ul>
 *   <li>{@code listCards}, one page of the card list positioned by key and never by a row count,
 *       served by {@code POST} on {@code /api/v1/cards/search} with the account narrowing, the
 *       cursor and the direction in the request BODY. The verb and the literal segment are both
 *       consequences of the same finding the lookup below records: a query string is part of the
 *       request line, and the migration's logging contract names account and customer identifiers
 *       alongside the primary account number</li>
 *   <li>{@code lookupCard}, the resolution of a card number a user typed into the card it names,
 *       served by {@code POST} on {@code /api/v1/cards/lookup} with the number in the request
 *       BODY. It is the one operation that accepts a full primary account number, and the body is
 *       why: a request line is written verbatim into the load balancer's mandatory access log
 *       before any application code runs, and a body is not part of it</li>
 *   <li>{@code getCard}, one card with its primary account number rendered to its last four
 *       digits, served by {@code GET} on the single-card path, which is keyed by the opaque
 *       selector every card response publishes rather than by the card number</li>
 *   <li>{@code getAdminCardDetail}, the administrative read of that same card carrying the full
 *       primary account number, served by {@code GET} on {@code /api/v1/admin/cards/{cardKey}},
 *       which is its own path prefix rather than a segment appended beneath the card</li>
 *   <li>{@code updateCard}, the update of the three editable members of one card, served by
 *       {@code PUT} on the single-card path</li>
 * </ul>
 *
 * <p>Alternatives Considered: those five operations sit on one controller rather than on three,
 * one per baseline program, which would have mirrored {@code COCRDLIC}, {@code COCRDSLC} and
 * {@code COCRDUPC} class for class. The per-program split was weighed and rejected. This bounded
 * context owns a single aggregate, the {@code card.cards} table the migration plan assigns it at
 * its section 0.4.1.3, and the five operations are five views of that one aggregate: the update
 * writes the row the detail read returns, the lookup resolves which row a typed number names, and
 * the administrative read widens what is rendered of one member of that same row. Splitting one
 * aggregate's HTTP surface across three classes would therefore spread a single five-operation
 * contract over three files while adding no seam that the
 * domain actually has, and it would leave no single file whose shape can be read against the
 * contract of record as a whole. The trade accepted is that the surviving class carries five
 * handlers rather than one or two, which is a size this layer can hold precisely because it holds
 * no business rules.
 *
 * <p>Assumptions: the single-card path carries an OPAQUE SELECTOR and not the card number. The
 * selector is minted by this service under the shared sealer, is bound to the resource, the
 * authenticated subject and the query scope, carries no card number and cannot be forged from one;
 * every card response publishes it as its {@code key} member, so a client always holds the value its
 * next request needs.
 *
 * <p>Refactoring Rationale: the path carried the sixteen-digit card number, and the list carried a
 * card-number query filter, on the reasoning that the resulting request-line exposure was "answered
 * by redaction rather than by indirection" because {@code CardNumberMasker} narrows a number embedded
 * in a path and the shared error advice applies it to every path it records. A review established
 * that the redaction does not reach the record in question. The load balancer writes its own
 * access-log record, from the request line, inside the process that terminates the connection and
 * before any application code runs; access logging is mandatory here, with no input able to disable
 * it; so under the previous spelling every single-card request left one durable copy of a primary
 * account number in an object store, and masking downstream of an already-written log cannot unwrite
 * it. What the masker does bound -- this service's own log lines and error bodies -- is worth having,
 * is retained, and was never the same hazard. The number is now confined to the {@code lookupCard}
 * request body, which neither access log records, and the administrative read keeps its own prefix,
 * which removes the rule-ordering dependency its authority previously rested on.
 *
 * <p>Trade-offs: two costs land on the caller and neither is hidden. A client can no longer build a
 * card route from a number a user typed without one call to {@code lookupCard} first; and a selector
 * expires with the configured token lifetime, so a bookmarked card URL eventually stops resolving and
 * the number has to be entered again. Both were cited as reasons for withdrawing this shape once
 * before. They are accepted now because the alternative is a permanent record of cardholder data in
 * object storage, which is not a cost that trades against an interaction.
 *
 * <p>Assumptions: {@code updateCard} answers HTTP 409 when an update cannot be applied against the
 * persisted state, and three distinct conditions reach that status without ever being merged: the
 * row could not be taken for update, the write itself did not succeed, or the row had changed since
 * the caller read it. The third is the optimistic-concurrency conflict, and it is the only one of
 * the three that carries a refreshed card back, because it is the only one for which a current
 * representation exists to return. Each condition carries its own subordinate code and its own
 * sentence, and those sentences are reproduced character for character from
 * {@code app/cbl/COCRDUPC.cbl:205-210}. The conflict itself is detected on the version column that
 * {@code services/card-service/src/main/resources/db/migration/V1__card.sql} declares at its line
 * 303, so this package neither detects the conflict nor composes the body: it reports the status the
 * contract declares.
 *
 * <h2>Baseline lineage</h2>
 *
 * <p>This package re-expresses the presentation surface of three online COBOL programs. They are
 * reference material, read as the specification and never modified, and they are cited by path and
 * line so that any claim made here can be checked against them.
 *
 * <ul>
 *   <li>{@code app/cbl/COCRDLIC.cbl}, the card list, reached by CICS transaction {@code CCLI},
 *       whose resource definition opens at {@code app/csd/CARDDEMO.CSD:357} and names the program
 *       at {@code :358}</li>
 *   <li>{@code app/cbl/COCRDSLC.cbl}, the card detail, reached by {@code CCDL}, defined from
 *       {@code app/csd/CARDDEMO.CSD:347} and naming the program at {@code :348}</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl}, the card update, reached by {@code CCUP}, defined from
 *       {@code app/csd/CARDDEMO.CSD:367} and naming the program at {@code :369}. Of the three it is
 *       the only one carrying a {@code DESCRIPTION} line, at {@code :368}, so a reader searching
 *       the resource definitions for a descriptive name will find one for this transaction alone
 *       and none for its two siblings</li>
 * </ul>
 *
 * <p>Assumptions: all three definitions declare {@code TWASIZE(0)}, at {@code :358}, {@code :348}
 * and {@code :369} respectively, so the per-task work area these transactions could have reserved
 * is zero bytes wide in every one of them. That is corroboration from the baseline's own resource
 * definitions that a stateless boundary is faithful to it rather than a departure from it, and it
 * is worth recording because the argument for statelessness below would otherwise rest on the
 * target design alone.
 *
 * <h2>What replaces the communication area</h2>
 *
 * <p>The baseline is pseudo-conversational: each of those transactions ends at every screen turn,
 * so all continuity between turns lives in one passed structure, {@code CARDDEMO-COMMAREA},
 * declared at {@code app/cpy/COCOM01Y.cpy:19-44} and included by all three programs. That
 * structure does not travel here, and no part of it is echoed back to a caller. It decomposes into
 * four separate mechanisms.
 *
 * <ul>
 *   <li>Identity, carried by {@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE} at
 *       {@code app/cpy/COCOM01Y.cpy:25-28}, arrives instead as claims on a validated bearer token.
 *       The authority each operation requires is asserted by the filter chain declared in
 *       {@code com.carddemo.card.config} and never inside a controller method body</li>
 *   <li>Selection context, carried by {@code CDEMO-ACCT-ID} at {@code :38} and
 *       {@code CDEMO-CARD-NUM} at {@code :41}, arrives as the path selector and as query
 *       parameters, which is what makes each request self-describing and therefore independently
 *       authorizable</li>
 *   <li>The browse cursor, which {@code app/cbl/COCRDLIC.cbl} keeps in that same structure as a
 *       last-key pair, a first-key pair, a screen number and a next-page indicator at
 *       {@code :230-244}, travels in the page envelope
 *       {@code com.carddemo.common.web.PageResponse} through its {@code firstKey},
 *       {@code lastKey} and {@code hasNext} members</li>
 *   <li>Navigation, carried by the from-program and to-program fields and their transaction
 *       counterparts at {@code app/cpy/COCOM01Y.cpy:21-24}, is entirely client-side. No response
 *       leaving this package names a next program or a next screen</li>
 * </ul>
 *
 * <p>Refactoring Rationale: the re-entry discriminator {@code CDEMO-PGM-CONTEXT}, declared at
 * {@code app/cpy/COCOM01Y.cpy:29-31} with an enter value and a re-enter value, has no counterpart
 * here at all, and that absence is deliberate rather than an oversight. A stateless handler that
 * answers with a per-field error array has no first-entry-against-re-entry distinction left to
 * make, so field-level error presentation is driven by the response body alone and by nothing the
 * server remembers. Nothing in this package holds state between requests: there is no sticky
 * session, no server-side session store and no affinity requirement on the load balancer in front
 * of it, which is precisely what lets the tasks running it scale horizontally.
 *
 * <h2>Shared contracts, consumed and never re-declared</h2>
 *
 * <p>Alternatives Considered: a package-local exception advice translating this context's failures
 * into response bodies. Rejected. The single advice
 * {@code com.carddemo.common.error.GlobalExceptionHandler} is owned by the shared kernel, which the
 * migration plan assigns at its section 0.5.1.1, and it reaches this service through the one
 * auto-configuration class that kernel contributes,
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, named in the kernel's
 * {@code META-INF/spring} registration resource. Plan rule T2 forbids re-declaring a shared concern
 * per service, and a concrete failure sits behind that rule here: the kernel guards its advice with
 * a missing-bean condition keyed on its own type, so a local advice of some other type would not
 * displace it but would stand beside it, and two advices with no declared precedence leave it
 * unpredictable which of them renders any given failure. The three subordinate codes behind HTTP
 * 409 would be the first thing lost, because a caller that distinguishes them needs the body shape
 * the contract declares rather than whichever shape happened to win.
 *
 * <p>Assumptions: the same reasoning governs every other cross-cutting concern this package relies
 * on, so not one of them is declared here. The correlation-identifier filter and the conversion of
 * group claims into authorities are wired in {@code com.carddemo.card.config}; the page envelope,
 * the error body and the timestamp form come from {@code com.carddemo.common}. No local error type,
 * no local pagination type, no local exception class and no local message catalog is declared in
 * this package.
 */
package com.carddemo.card.api;
