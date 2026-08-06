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
 * <h2>Target contract, not a directory listing</h2>
 *
 * <p>Assumptions: every class name, operation name, sibling package name and count anywhere in this
 * charter states the package's <b>target contract</b> as the migration plan assigns it, and not a
 * census of the directory that holds the charter. It is read against the plan, and the roster below
 * is closed: a type in this package that the roster does not name is outside the contract rather
 * than merely new.</p>
 *
 * <p>Alternatives Considered: deriving the roster from the directory instead of from the plan.
 * Rejected on two independent grounds. A roster that describes whatever is present cannot say what
 * may <em>not</em> be added, which is the half of the contract a reader cannot reconstruct from the
 * files. Separately, {@code JavadocPackage} audits a directory rather than a single compilation
 * unit, so this charter has to govern the directory as a whole and be readable before any class in
 * it is, or the whole directory fails the documentation gate and the module will not build.</p>
 *
 * <h2>Roster, and the operations it carries</h2>
 *
 * <p>One controller belongs to this package, {@code CardController}, and the set is closed. Its
 * contract of record is the hand-authored OpenAPI 3.1 document at
 * {@code services/card-service/src/main/resources/openapi/card-api.yaml}, which settles the paths,
 * the parameter and property names, the response shapes and the error vocabulary. That document
 * declares five synchronous operations. Each is named below by the operation identifier it carries
 * there, so that a reader can move between this charter and the contract without guessing which
 * entry answers to which.
 *
 * <ul>
 *   <li>{@code listCards}, one page of the card list positioned by key rather than by offset,
 *       served by {@code GET} on the collection path {@code /api/v1/cards}</li>
 *   <li>{@code getCard}, one card with its primary account number rendered to its last four
 *       digits, served by {@code GET} on the single-card path</li>
 *   <li>{@code getAdminCardDetail}, the administrative read of that same card carrying the full
 *       primary account number, served by {@code GET} on {@code /api/v1/admin/cards/{cardNumber}},
 *       which is its own path prefix rather than a segment appended beneath the card</li>
 *   <li>{@code updateCard}, the update of the three editable members of one card, served by
 *       {@code PUT} on the single-card path</li>
 * </ul>
 *
 * <p>Alternatives Considered: those four operations sit on one controller rather than on three,
 * one per baseline program, which would have mirrored {@code COCRDLIC}, {@code COCRDSLC} and
 * {@code COCRDUPC} class for class. The per-program split was weighed and rejected. This bounded
 * context owns a single aggregate, the {@code card.cards} table the migration plan assigns it at
 * its section 0.4.1.3, and the four operations are four views of that one aggregate: the update
 * writes the row the detail read returns, and the administrative read widens what is rendered of
 * one member of that same row. Splitting one aggregate's HTTP surface across three classes would
 * therefore spread a single four-operation contract over three files while adding no seam that the
 * domain actually has, and it would leave no single file whose shape can be read against the
 * contract of record as a whole. The trade accepted is that the surviving class carries four
 * handlers rather than one or two, which is a size this layer can hold precisely because it holds
 * no business rules.
 *
 * <p>Assumptions: the single-card path carries the sixteen-digit card number, which is the primary
 * key of {@code card.cards} and the value a user supplies -- the baseline's own detail and update
 * screens are entered by typing it into the list screen's card-number filter field. A card number
 * consequently reaches the request line, and the exposure that creates is answered by redaction
 * rather than by indirection: common-lib's {@code CardNumberMasker} renders any sixteen-digit run
 * embedded in a path down to its last four digits, and the shared error advice applies it to every
 * path it records, so no operational record of this service carries a full number.
 *
 * <p>Refactoring Rationale: an intermediate revision of the contract of record addressed a card by
 * an opaque token instead, and published a fifth operation -- a {@code POST} carrying a card number
 * in a request body -- because the token left the primary key unpublishable and the list screen's
 * second filter field had nowhere else to go. Both are withdrawn together. The contract publishes
 * the four operations above, the two filter fields of the baseline list screen are both query
 * parameters of the list operation, and the administrative read moved from a segment inside the card
 * subtree to its own prefix, which removes the rule-ordering dependency its authority previously
 * rested on.
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
 *
 * <h2>Why this charter exists, and why it is written as it is</h2>
 *
 * <p>Assumptions: this file exists because Rule 1, the project's one user-specified rule, requires
 * a docstring on every module entry point at its line 15, and in Java a package declaration is that
 * entry point while a {@code package-info.java} charter is the only place its docstring can live.
 * The obligation is mechanised by a pair of checks in {@code config/checkstyle/checkstyle.xml} that
 * have to be read together. {@code JavadocPackage} sits at checker level, where it audits the
 * directory and asserts only that a {@code package-info.java} file is present. Its companion
 * {@code MissingJavadocPackage} sits inside the tree walker, where it reads that file and asserts
 * that the file carries Javadoc. An empty file therefore satisfies the first and fails the second,
 * which is why this file opens on a documentation comment rather than on a plain block comment.
 * Both run at the Maven validate phase, ahead of compilation, so neither is escaped by a build that
 * merely declines to run tests.
 *
 * <p>Assumptions: this bounded context carries eight of these charters once complete, one at the
 * context root and one in each of {@code api}, {@code config}, {@code domain}, {@code dto},
 * {@code mapper}, {@code repository} and {@code service}. The shared kernel carries nine, because
 * it has eight subpackages beside its own root, and that nine must not be read across to here. The
 * two counts are recorded together because a reader who assumes the contexts are symmetrical will
 * look for a ninth charter here and conclude that one is missing.
 *
 * <p>Alternatives Considered: the category labels used throughout this charter are written in the
 * plural, unparenthesised, colon-terminated form, which diverges from the singular parenthesised
 * idiom predominating in this repository's older prose. Matching that majority idiom was weighed
 * and rejected. Rule 1 states these categories at its lines 31 to 34, and its validation gate at
 * line 43 makes that wording the sentence this tree is audited against, so the audited spelling is
 * the one that has to appear. A reviewer looking for every rationale across seven languages has
 * only a literal string search to work with, because no linter reads prose in a Terraform file or a
 * SQL migration; one spelling makes that search complete, while two make it silently partial.
 * Consistency with the rule actually enforced therefore outranks consistency with prose that is
 * not, and the two forms are never mixed inside one file.
 *
 * <p>Trade-offs: no golden-master oracle exists for any path this package serves. The online
 * programs named above cannot run end-to-end without a CICS runtime, as recorded at
 * {@code tests/README.md:83-85}, so parity for these paths rests on the transcribed rules and on
 * the tests under {@code services/card-service/src/test} rather than on byte comparison against
 * recorded mainframe output. That is a weaker guarantee than the batch contexts enjoy, and it is
 * accepted because the alternative, standing up a CICS region, lies outside this migration and
 * would not be reproducible in continuous integration. Message text is the exception: it stays
 * verifiable character for character against the copybooks and programs cited above, which is why
 * every such string is quoted from a cited line rather than paraphrased.
 */
package com.carddemo.card.api;
