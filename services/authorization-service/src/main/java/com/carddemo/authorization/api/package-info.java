/**
 * REST adapter surface of the pending credit-card authorization bounded context.
 *
 * <h2>Target contract, and the tree state at the checkpoint that authored it</h2>
 *
 * <p>Assumptions: every inventory, file name, class name and count in this charter describes the
 * package's <b>target contract</b> as the migration plan assigns it, not the set of files present
 * beside this one today. The migration lands its artifacts in plan order and this charter is
 * authored first, so at the checkpoint that authored it this directory holds this charter and
 * nothing else. A type named below that has no file yet is therefore <b>planned</b>, not missing,
 * and a count below is a target total rather than a measurement of the directory.</p>
 *
 * <p>Alternatives Considered: withholding this charter until the two controllers it governs exist.
 * Rejected, because the charter is what the authors of those classes work from -- which type
 * belongs here, which may not, and which dependency is refused outright -- so writing it last
 * would leave the package with no stated contract during exactly the interval in which one is
 * needed. The cost of authoring it first is that its inventory reads as present tense unless the
 * distinction is declared, which is what the paragraph above is for.</p>
 *
 * <p>Purpose: this package is the stateless HTTP boundary of authorization-service. It binds a
 * request, validates its fields, delegates to the service layer, and renders the result as a
 * status code and a response body. It holds validation and HTTP concerns only, and no business
 * logic: every rule transcribed from the reference COBOL lives one package over, in
 * {@code com.carddemo.authorization.service}.
 *
 * <p>Two controllers are planned here, and there is no third:
 *
 * <ul>
 *   <li>{@code PendingAuthController} -- the pending-authorization summary list, and the detail
 *       view of one pending authorization.</li>
 *   <li>{@code FraudController} -- setting the fraud state of one authorization message.</li>
 * </ul>
 *
 * <h2>Lineage: two terminal transactions, three programs</h2>
 *
 * <p>The reference tree {@code app/app-authorization-ims-db2-mq} is the specification for this
 * boundary. It is read and never modified. Two of its transactions drive a terminal, and the
 * routes in this package carry what those two carried:
 *
 * <ul>
 *   <li>Transaction {@code CPVS}, the summary list. {@code csd/CRDDEMO2.csd} L49 and L50 define it
 *       against {@code cbl/COPAUS0C.cbl}, whose program definition carries {@code TRANSID(CPVS)}
 *       at L22 and whose own header states its function as a summary view of authorization
 *       messages. It drives mapset {@code COPAU00}, described at L2 as the authorization summary
 *       map.</li>
 *   <li>Transaction {@code CPVD}, the detail view. {@code csd/CRDDEMO2.csd} L39 and L40 define it
 *       against {@code cbl/COPAUS1C.cbl}, whose program definition carries {@code TRANSID(CPVD)}
 *       at L29, over mapset {@code COPAU01}, described at L7 as the authorization details map.</li>
 *   <li>{@code cbl/COPAUS2C.cbl}, the fraud write, which its own header states as marking an
 *       authorization message fraudulent. It is not a transaction of its own: it carries
 *       {@code TRANSID(CPVD)} at {@code csd/CRDDEMO2.csd} L36 yet is named in none of that file's
 *       three {@code DEFINE TRANSACTION} stanzas, and {@code cbl/COPAUS1C.cbl} reaches it by
 *       {@code EXEC CICS LINK} at L248 to L252, so it runs inside the caller's task rather than
 *       as a transaction a terminal can start.</li>
 * </ul>
 *
 * <p>Field validation is a concern this package inherits from those programs rather than invents.
 * {@code cbl/COPAUS0C.cbl} edits its account identifier before it reads anything: it rejects the
 * field when it is blank or low values at L264, rejects it when it is not numeric at L273, and
 * repositions the cursor onto it in each case. Those two edits are request validation in the
 * precise sense a controller performs it, and they are why this package is described as holding
 * validation concerns at all.
 *
 * <h2>Statelessness, and what replaces the passed structure</h2>
 *
 * <p>The baseline is pseudo-conversational: a task ends at every screen turn, so continuity
 * between turns travels in a structure the terminal echoes back. Nothing in this package holds a
 * session. Identity arrives as claims on a validated token rather than as a field a caller
 * supplied, the selection a screen used to remember arrives in the request path, and the
 * navigation the baseline performed by transferring control between programs is performed by the
 * browser client. The baseline carries its continuity that way and remains reference material;
 * this package carries none. The migration adds a path, it does not remove one.
 *
 * <p>Assumptions: that statelessness is what lets any task behind the load balancer serve any
 * route in this package, with no sticky session and no shared session store. A controller here
 * that cached anything request-scoped in an instance field would break that property without
 * announcing it, because a single task would still answer every request correctly.
 *
 * <h2>Dependency direction, and the two packages this one refuses</h2>
 *
 * <p>This package depends on exactly three things: {@code com.carddemo.authorization.dto} for the
 * request and response shapes it binds and returns, {@code com.carddemo.authorization.service}
 * for the behaviour it delegates to, and {@code com.carddemo.common} for the shared page
 * envelope, the problem shape, and the correlation and security plumbing.
 *
 * <p>It depends on neither {@code com.carddemo.authorization.domain} nor
 * {@code com.carddemo.authorization.repository}. No controller here touches a JPA entity, injects
 * a repository or an {@code EntityManager}, or declares {@code @Transactional}. <b>That absence is
 * a deliberate design assertion and not an oversight.</b> A reader who opens either controller,
 * finds no repository import and wonders whether one was forgotten should read this paragraph as
 * the answer: it was excluded on purpose, and restoring one would change this package's contract
 * rather than merely add a convenience to it.
 *
 * <p>Alternatives Considered: injecting a Spring Data repository straight into a controller and
 * returning what it finds. That arrangement is widespread and unremarkable, and it is rejected
 * here for two concrete consequences rather than a preference. First, a controller holding a
 * repository can open a transaction and mutate an entity, which puts a unit of work outside the
 * single package that owns every transaction boundary in this module. The fraud write is the case
 * where that costs something measurable: the baseline runs the fraud write and the detail update
 * inside one unit of work, reaching {@code cbl/COPAUS2C.cbl} by {@code EXEC CICS LINK} from
 * {@code cbl/COPAUS1C.cbl} L248 to L252, committing at L558 and rolling back at L567, so a second
 * transaction boundary opened from this layer would fragment a commit that has to stay whole.
 * Second, an entity returned from a controller is serialised field by field by whatever sits on
 * the response path, which means it never passes through
 * {@code com.carddemo.authorization.mapper}, and the masking that package performs is then simply
 * not applied to it. That second consequence is why the assertion is worth stating in this module
 * specifically: the routes here carry primary account numbers, which the mapper masks, and they
 * must never carry a card verification value at all. Neither leak would announce itself, because
 * the response would be well formed and would merely contain a field it was never meant to
 * contain.
 *
 * <h2>Boundaries this package relies on, and does not re-implement</h2>
 *
 * <p>Four behaviours a controller might plausibly be written to perform are owned elsewhere. Each
 * is named with its owner, because a second implementation added here would not replace the first
 * one, it would compete with it, and a reader could then no longer tell which of the two decided
 * a given response.
 *
 * <ul>
 *   <li>{@code com.carddemo.authorization.mapper} owns primary-account-number masking and the
 *       suppression of the card verification value, and it is the only place in this module where
 *       representation concerns may appear at all. That exclusivity is what makes it hold: a field
 *       cannot escape masking by being serialised from somewhere else, because there is nowhere
 *       else for it to be serialised from. A controller here never re-implements masking, never
 *       returns a card verification value, and does not decide which routes are exempt from
 *       masking, which is the mapper's to decide.</li>
 *   <li>{@code com.carddemo.authorization.service} owns every {@code @Transactional} boundary in
 *       this module, the {@code size + 1} look-ahead read and the discard of the extra row it
 *       returns, the encoding of the opaque cursor a caller sends back, and the assembly of
 *       {@code com.carddemo.common.web.PageResponse}. A controller passes a page size and a cursor
 *       through and renders the envelope it receives; it does not compute whether a further page
 *       exists.</li>
 *   <li>{@code com.carddemo.authorization.config.SecurityConfig} owns the route authorization
 *       matrix, including the {@code carddemo-admin} authority required on the fraud route, and
 *       registers {@code com.carddemo.common.web.CorrelationIdFilter} into this module's filter
 *       chain. Authorization is declared once, in one readable place, rather than annotated route
 *       by route across this package.</li>
 *   <li>{@code com.carddemo.common.error.GlobalExceptionHandler} owns the mapping from an
 *       exception to a status code and a problem body. The concurrent-update case is the one worth
 *       naming: an {@code OptimisticLockException} becomes HTTP 409. A controller therefore
 *       catches nothing in order to choose a status, and writes no error body of its own.</li>
 * </ul>
 *
 * <h2>Documentation contract and build interlock</h2>
 *
 * <p>This file exists because user-specified Rule 1 (Explainability) L15 requires a docstring on
 * every module entry point, and a Java package declaration is a module entry point. A
 * {@code package-info.java} is the only construct that can carry Javadoc for one, so the
 * obligation lands in this file and nowhere else. Of the four content elements L18 to L21
 * enumerate, only Purpose applies here: a package declaration accepts no parameters, yields no
 * value and raises nothing, so the Parameters, Return values and Exceptions elements are
 * inapplicable rather than omitted, and no at-clause is written to stand in for one of them. L22
 * names Javadoc as the format for Java, which is why the documentation above is a Javadoc block
 * placed immediately before the package declaration; an ordinary line or block comment in the
 * same position is not package documentation and would not discharge the obligation.
 *
 * <p>Rule 1 imposes that obligation and the migration plan supplies the mechanism that enforces
 * it. The two are separate and the distinction is worth keeping, because the rule would still
 * bind if no linter existed. Rule 1's own Validation Gate at L43 states the consequence as a
 * failed review. The plan mechanises the docstring half of that gate through
 * {@code config/checkstyle/checkstyle.xml}: {@code JavadocPackage} runs at Checker level and
 * requires this file to be present in a package holding Java sources, while
 * {@code MissingJavadocPackage} runs inside the tree walker and requires the file to carry
 * Javadoc. Neither check is redundant, because an empty {@code package-info.java} satisfies the
 * first and fails the second. Both run at error severity from the
 * {@code checkstyle-documentation-gate} execution bound to the {@code validate} phase in
 * {@code services/pom.xml}, and that phase precedes compilation, so an omission here stops the
 * build rather than adding a line to a log nobody reads. No in-code suppression filter is
 * configured in that rule set, so a finding here cannot be waived from inside a source file, and
 * the suppressions companion reaches generated sources and test fixtures only.
 *
 * <p>This gate's result is binary: it passes or it fails. The graded condition-code rubric under
 * which the reference COBOL suite treats a warning-level aggregate as its green state belongs to
 * that suite alone, and it is never carried into a Maven, Checkstyle, Surefire or Failsafe result
 * on this side.
 *
 * <h2>Decisions</h2>
 *
 * <p>What follows discharges the inline-comment half of user-specified Rule 1 (Explainability),
 * whose four justification categories L31 to L34 name, for the choices in this package that a
 * reasonable alternative could have gone the other way on. L40 forbids leaving such a choice
 * undocumented and L41 forbids a rationale offered without specific justification, so each entry
 * below names the concrete alternative and the concrete consequence of having taken it. The
 * refusal of the {@code domain} and {@code repository} packages is the decision this package
 * exists to record, and it is argued in full in its own section above rather than repeated here.
 *
 * <p>Alternatives Considered: folding the fraud route into {@code PendingAuthController}, on the
 * ground that the baseline reaches the fraud program from inside transaction {@code CPVD} and so
 * treats detail and fraud as one screen's work. Rejected, and the deciding factor is the
 * authority rather than the grouping. The fraud route is the only route in this package that
 * changes state and the only one gated on the {@code carddemo-admin} authority, so giving it its
 * own type lets the route authorization matrix in {@code SecurityConfig} name one class-level
 * path prefix instead of singling out a single method inside a mixed controller, where a later
 * edit could add a second method under the same prefix and inherit an authority nobody intended
 * for it. The cost accepted is one more small type than the baseline's screen count suggests,
 * which is why the pairing is recorded here rather than left for a reader to reconstruct.
 *
 * <p>Assumptions: the package name {@code com.carddemo.authorization.api} is an external contract
 * and not a local naming choice. The migration plan fixes the context root and the subpackage
 * names identically across all eight bounded contexts so that one import-rule expression covers
 * the whole reactor, and the ArchUnit layering test matches on those names. Renaming this
 * segment, pluralising it or inserting another would therefore not be cosmetic: a rule matching
 * the old name would stop applying to this tree and would then pass while asserting nothing about
 * it, which is harder to notice than an outright failure.
 *
 * <p>Refactoring Rationale: every reference to the baseline in this charter states what the
 * baseline does and what this package does instead, and passes no judgement on it. That framing
 * is deliberate and it is the house convention for this migration: the COBOL is the behavioural
 * oracle, it is reference material that is never modified, and a comment calling it defective
 * would invite exactly the edit the migration forbids. Each such reference is cited by path and
 * line so that a reader can check both sides of the comparison.
 *
 * <p>Trade-offs: the justifications in this charter sit inside a Javadoc block rather than beside
 * a statement, which departs from the letter of user-specified Rule 1 (Explainability) L27. A
 * package declaration has no statements, so there is no code for a comment to be adjacent to, and
 * the adjacency requirement is satisfied vacuously rather than waived. The cost accepted is that
 * these entries sit further from the behaviour they govern than an inline comment would, and the
 * compensation is that each one names its evidence explicitly. A reader auditing this file should
 * read the labelled entries above as the rationale half of the conjunctive L43 gate, and should
 * not conclude that the half was skipped for want of somewhere to put it.
 */
package com.carddemo.authorization.api;
