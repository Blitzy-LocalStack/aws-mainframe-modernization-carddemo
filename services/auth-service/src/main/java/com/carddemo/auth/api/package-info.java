/**
 * REST boundary of the auth bounded context, and the only place in this context where an HTTP
 * request becomes a call and an outcome becomes a status code.
 *
 * <p>Three concerns belong here and nothing else does: validating an inbound request as transport,
 * mapping an outcome onto an HTTP status, and delegating to the layer beneath. Business behaviour
 * lives one layer down, in {@code com.carddemo.auth.service}, which is the division the migration
 * plan assigns at its section 0.4.1.2 where it describes a REST layer carrying validation and no
 * business logic. Persistence lives further down again: no type declared here reaches
 * {@code com.carddemo.auth.repository}, and none touches a {@code com.carddemo.auth.domain}
 * entity.</p>
 *
 * <h2>Target contract, not a directory listing</h2>
 *
 * <p>Assumptions: every name, operation and inventory in this charter states the package's
 * <b>target contract</b> as the migration plan assigns it, and not a census of the directory that
 * holds the charter. The roster below is closed: a type here that the roster does not name is
 * outside the contract rather than merely new.</p>
 *
 * <p>Alternatives Considered: deriving the roster from the directory instead of from the plan.
 * Rejected on two independent grounds. A roster describing whatever happens to be present cannot
 * say what may <em>not</em> be added, and that is the half of a package contract a reader cannot
 * reconstruct from the files beside it. Separately, {@code JavadocPackage} audits a directory
 * rather than one compilation unit, so this charter has to govern the directory as a whole and be
 * readable before any type in it is, or the directory fails the documentation gate and the module
 * does not build.</p>
 *
 * <h2>Roster, and the operations it carries</h2>
 *
 * <p>Two adapters belong to this package and the set is closed. Their contract of record is the
 * hand-authored OpenAPI 3.1 document at
 * {@code services/auth-service/src/main/resources/openapi/auth-api.yaml}, which settles the paths,
 * the parameter and property names, the response shapes and the error vocabulary. That document
 * declares eight synchronous operations and splits them between the two adapters by its own tags.
 * Each is named below by the operation identifier it carries there, so that a reader can move
 * between this charter and the contract without guessing which entry answers to which.</p>
 *
 * <p>{@code AuthController} carries the three operations the contract tags {@code Sign-On}. Every
 * one of them declares {@code x-required-authority: none}, because between them they are the
 * operations that issue a token and so none of them can require one.</p>
 *
 * <ul>
 *   <li>{@code signOn}, the exchange of a user identifier and password for a token set, served by
 *       {@code POST} on {@code /api/v1/auth/signon}</li>
 *   <li>{@code answerSignOnChallenge}, the setting of a permanent password in answer to a sign-on
 *       challenge, served by {@code POST} on {@code /api/v1/auth/challenge}</li>
 *   <li>{@code refreshTokens}, the exchange of a refresh token for a new token set, served by
 *       {@code POST} on {@code /api/v1/auth/refresh}</li>
 * </ul>
 *
 * <p>{@code UserController} carries the five operations the contract tags
 * {@code User Administration}, every one of them declaring
 * {@code x-required-authority: carddemo-admin}. The collection path is
 * {@code /api/v1/auth/users}, and the single-user path adds the eight-character user identifier
 * beneath it as a final path segment.</p>
 *
 * <ul>
 *   <li>{@code listUsers}, one bounded page of the user list positioned by key rather than by
 *       offset, served by {@code GET} on the collection path</li>
 *   <li>{@code createUser}, the creation of one user row, served by {@code POST} on that same
 *       collection path</li>
 *   <li>{@code getUser}, the read of one user row, served by {@code GET} on the single-user
 *       path</li>
 *   <li>{@code updateUser}, the update of the mutable values of one user row, served by
 *       {@code PUT} on the single-user path</li>
 *   <li>{@code deleteUser}, the deletion of one user row with the deletion explicitly confirmed,
 *       served by {@code DELETE} on the single-user path</li>
 * </ul>
 *
 * <p>Alternatives Considered: those five user operations sit on one adapter rather than on four,
 * one per baseline program, which would have mirrored {@code COUSR00C}, {@code COUSR01C},
 * {@code COUSR02C} and {@code COUSR03C} one for one. The per-program split was weighed and
 * rejected. This bounded context owns a single aggregate, the {@code auth.users} table the
 * migration plan assigns it at its section 0.4.1.3, and the five operations are five views of that
 * one row: the create, the update and the delete all write the row the two reads return. Splitting
 * one aggregate's HTTP surface across four adapters would spread a single five-operation contract
 * over four files while adding no seam the domain actually has, and it would leave no single file
 * whose shape can be read against the contract of record as a whole. The trade accepted is that
 * the surviving adapter carries five handlers, which is a size this layer can hold precisely
 * because it holds no business rules.</p>
 *
 * <p>Assumptions: sign-on is nevertheless kept apart from user administration, and the seam is the
 * authority boundary rather than a preference about file size. The three sign-on operations are
 * reachable without a token while the five administrative operations require
 * {@code carddemo-admin}, so putting both groups in one file would place an unauthenticated
 * handler and an administrative handler side by side, where a filter-chain rule added for one is
 * easy to read as covering the other.</p>
 *
 * <h2>Baseline lineage</h2>
 *
 * <p>This package re-expresses the presentation surface of five online COBOL programs. They are
 * reference material, read as the specification and never modified, and they are cited by path and
 * line so that any claim made here can be checked against them. Each is reached in the baseline by
 * a CICS transaction whose resource definition is a ten-line stanza in
 * {@code app/csd/CARDDEMO.CSD}, cited below as the pair of lines that carries the mapping: the
 * {@code DEFINE TRANSACTION} header, and the continuation line that names the program.</p>
 *
 * <ul>
 *   <li>{@code app/cbl/COSGN00C.cbl}, 260 lines, the sign-on screen, reached by transaction
 *       {@code CC00}, whose definition opens at {@code app/csd/CARDDEMO.CSD:378} and names the
 *       program at {@code :379}. That one program is the whole specification for
 *       {@code AuthController}</li>
 *   <li>{@code app/cbl/COUSR00C.cbl}, 695 lines, the user list, reached by {@code CU00}, defined
 *       from {@code :449} and naming the program at {@code :450}</li>
 *   <li>{@code app/cbl/COUSR01C.cbl}, 299 lines, the user add, reached by {@code CU01}, defined
 *       from {@code :459} and naming the program at {@code :460}</li>
 *   <li>{@code app/cbl/COUSR02C.cbl}, 414 lines, the user update, reached by {@code CU02}, defined
 *       from {@code :469} and naming the program at {@code :470}</li>
 *   <li>{@code app/cbl/COUSR03C.cbl}, 359 lines, the user delete, reached by {@code CU03}, defined
 *       from {@code :479} and naming the program at {@code :480}. Those four programs together are
 *       the specification for {@code UserController}</li>
 * </ul>
 *
 * <p>Assumptions: {@code CC00} is the sign-on transaction and nothing else, and the point is
 * recorded because the neighbouring stanzas make it easy to misread. The administrative menu is a
 * different transaction, {@code CA00}, defined at {@code app/csd/CARDDEMO.CSD:327} and naming
 * {@code COADM01C} at {@code :328}, with that program's own definition at {@code :189}. Sign-on
 * transfers control to that menu, so the two appear together in any reading of the sign-on path,
 * and a reader who attributes the menu program to the sign-on transaction will look for menu
 * behaviour in this package and find none.</p>
 *
 * <p>Assumptions: all five transaction definitions declare {@code TWASIZE(0)}, on lines
 * {@code :379}, {@code :450}, {@code :460}, {@code :470} and {@code :480} respectively, so the
 * per-task work area these transactions could have reserved is zero bytes wide in every one of
 * them. That is corroboration from the baseline's own resource definitions that a stateless
 * boundary is faithful to it rather than a departure from it, and it is worth recording because
 * the argument for statelessness below would otherwise rest on the target design alone.</p>
 *
 * <h2>What replaces the communication area</h2>
 *
 * <p>The baseline is strictly pseudo-conversational: the task ends at every screen turn, so all
 * continuity between turns lives in one passed structure. {@code app/cbl/COSGN00C.cbl} declares
 * that structure's inbound face in its linkage section at {@code :64-67}, as a
 * {@code DFHCOMMAREA} whose single subordinate item is
 * {@code LK-COMMAREA PIC X(01) OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN}; it detects first
 * entry by testing {@code IF EIBCALEN = 0} at {@code :80}; and it ends every turn by handing the
 * whole structure back to the terminal at {@code :98-102}, with the {@code COMMAREA} option itself
 * on {@code :100}. The structure is declared once, at {@code app/cpy/COCOM01Y.cpy:19-44}, and is
 * shared by every online program.</p>
 *
 * <p>That structure does not travel here, and no part of it is echoed back to a caller. It
 * decomposes into three surviving mechanisms and one that has no counterpart at all.</p>
 *
 * <ul>
 *   <li>Identity, carried by {@code CDEMO-USER-ID} at {@code app/cpy/COCOM01Y.cpy:25} and
 *       {@code CDEMO-USER-TYPE} at {@code :26} with its two condition names at {@code :27-28},
 *       arrives instead as claims on a validated bearer token. The authority each operation
 *       requires is asserted by the filter chain declared in {@code com.carddemo.auth.config} and
 *       never inside a handler body</li>
 *   <li>Selection context, the user identifier a screen would have carried in that same structure,
 *       arrives as the final segment of the single-user path, which is what makes each request
 *       self-describing and therefore independently authorizable</li>
 *   <li>The browse cursor of the user list travels in the page envelope
 *       {@code com.carddemo.common.web.PageResponse}, through its first-key, last-key and
 *       has-next members, so the list operation positions by key and never by offset</li>
 *   <li>Navigation, carried by the from-program and to-program fields with their transaction
 *       counterparts at {@code app/cpy/COCOM01Y.cpy:21-24} and by the last-map pair at
 *       {@code :43-44}, is entirely client-side. No response leaving this package names a next
 *       program or a next screen. The baseline picks the next program itself, at
 *       {@code app/cbl/COSGN00C.cbl:230-240}, where one test of the administrative condition name
 *       selects between a transfer to {@code COADM01C} named on {@code :232} and one to
 *       {@code COMEN01C} named on {@code :237}; here the response carries a token and the browser
 *       routes on its group claim</li>
 * </ul>
 *
 * <p>Refactoring Rationale: the re-entry discriminator {@code CDEMO-PGM-CONTEXT}, declared at
 * {@code app/cpy/COCOM01Y.cpy:29-31} with an enter value on {@code :30} and a re-enter value on
 * {@code :31}, has no counterpart here, and the absence is deliberate rather than an oversight. A
 * stateless handler that answers with a per-field error array has no
 * first-entry-against-re-entry distinction left to make, so error rendering is driven solely by
 * response data and by nothing the server remembers. That is precisely why no handler in this
 * package carries a first-entry branch, and why a reader will not find one to maintain. Nothing
 * here holds state between requests: there is no sticky session, no server-side session store and
 * no affinity requirement on the load balancer in front of it, which is what lets the tasks
 * running this package scale horizontally.</p>
 *
 * <p>Refactoring Rationale: reading the user type from a signed claim rather than from that passed
 * structure closes an exposure instead of merely relocating a field. In the baseline the structure
 * is storage the client holds between turns and hands back, so the one-character user type is
 * client-asserted, and the resource definitions add no compensating gate: the {@code CU02} stanza
 * declares {@code CONFDATA(NO)} at {@code app/csd/CARDDEMO.CSD:475} and then
 * {@code RESSEC(NO) CMDSEC(NO)} at {@code :476}, so neither resource nor command security is
 * checked for that transaction. A group claim on a validated token is not something a caller can
 * assert, so the administrative operations above rest on an authority this service verifies rather
 * than on a byte the caller supplied. The baseline behaves as just described; the target encodes
 * the signed claim; the divergence is recorded here and in the traceability matrix rather than
 * being introduced silently.</p>
 *
 * <h2>Layering, and which half of it a build can check</h2>
 *
 * <p>What this package may reference is a short list: the sibling {@code com.carddemo.auth.dto}
 * records that carry request and response shapes, the sibling {@code com.carddemo.auth.service}
 * types it delegates to, and the shared kernel packages {@code com.carddemo.common.web},
 * {@code com.carddemo.common.error} and {@code com.carddemo.common.security}. What it may not
 * reference is shorter and matters more: not {@code com.carddemo.auth.repository}, not a
 * {@code com.carddemo.auth.domain} entity, and no business rule of its own.</p>
 *
 * <p>Assumptions: those two halves do not rest on the same mechanism, and a reader who assumes
 * they do will trust one of them further than this build warrants. The cross-context half is
 * mechanical: rule A2 of
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * fails any type under one CardDemo package root that depends on a domain type owned by a
 * different root, and that rule set is published by the shared kernel as a test artifact and
 * re-evaluated against this module's own compiled types by the {@code architecture-rules}
 * execution in {@code services/pom.xml}. The intra-context half, that a handler here does not
 * reach this context's own repository or entity types, is this charter plus review, and no gate in
 * this build expresses it. Recording which is which is the whole point of the paragraph: a
 * boundary believed to be checked and in fact unchecked is worse than one known to rest on
 * review.</p>
 *
 * <p>Alternatives Considered: configuring Checkstyle's {@code ImportControl} module so that the
 * intra-context half became mechanical too. Rejected, and the exclusion is recorded in
 * {@code config/checkstyle/checkstyle.xml} itself rather than only here. Layering in this
 * repository is owned by the ArchUnit rule set named above, and that rule set carries a constraint
 * {@code ImportControl} cannot state at all, the prohibition on binary floating point anywhere in
 * the money path. Configuring both would leave two engines enforcing overlapping halves of one
 * charter, and a reader could then no longer tell which of them owned a given boundary, so
 * whichever is cheaper to silence is the one that gets silenced. A rule set local to this package
 * was rejected for that reason and for a second one: a charter split across two files can be
 * weakened by editing the other one, where the change reads as maintenance.</p>
 *
 * <h2>Shared contracts, consumed and never re-declared</h2>
 *
 * <p>Alternatives Considered: a package-local advice translating this context's failures into
 * response bodies. Rejected. The single advice
 * {@code com.carddemo.common.error.GlobalExceptionHandler} is owned by the shared kernel, which
 * the migration plan assigns at its section 0.5.1.1, and it reaches this service through the one
 * auto-configuration type that kernel contributes,
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}. Plan rule T2 forbids re-declaring a
 * shared concern per service, and a concrete failure sits behind that rule: the kernel guards its
 * advice with a missing-bean condition keyed on its own type, so a local advice of some other type
 * would not displace it but would stand beside it, and two advices with no declared precedence
 * leave it unpredictable which of them renders any given failure.</p>
 *
 * <p>Assumptions: the same reasoning governs every other cross-cutting concern this package relies
 * on, so not one of them is declared here. The correlation-identifier filter
 * {@code com.carddemo.common.web.CorrelationIdFilter} and the conversion of group claims into
 * authorities by {@code com.carddemo.common.security.JwtRoleConverter} are wired in
 * {@code com.carddemo.auth.config}; the page envelope, the error body and the structured abend
 * detail come from {@code com.carddemo.common}. No local error type, no local pagination type, no
 * local exception type, no local validation-flag type and no local message catalog is declared in
 * this package, and none may be added to it.</p>
 *
 * <p>Trade-offs: the user-visible message strings these operations return are reproduced character
 * for character from the programs cited above rather than paraphrased, among them the three
 * sign-on outcomes at {@code app/cbl/COSGN00C.cbl:242-243}, {@code :249} and {@code :254}. The
 * cost is that a message reads as terse mainframe text in a browser; what it buys is that message
 * text stays checkable against a cited line, which is the one aspect of these paths that can be
 * compared byte for byte at all.</p>
 *
 * <p>Trade-offs: no golden-master oracle exists for any path this package serves. The online
 * programs named above cannot run end to end without a CICS runtime, which
 * {@code tests/README.md:83-85} records among its known limitations, so parity for these paths
 * rests on the transcribed rules and on the tests under
 * {@code services/auth-service/src/test} rather than on byte comparison against recorded mainframe
 * output. That is a weaker guarantee than the batch contexts enjoy, and it is accepted because the
 * alternative, standing up a CICS region, lies outside this migration and would not be
 * reproducible in continuous integration.</p>
 *
 * <h2>Why this charter exists, and why it is written as it is</h2>
 *
 * <p>Assumptions: this file exists because Rule 1, the project's one user-specified rule, requires
 * a docstring on every module entry point at its line 15, and in Java a package declaration is
 * that entry point while a {@code package-info.java} charter is the only place its docstring can
 * live. The obligation is mechanised by a pair of checks in
 * {@code config/checkstyle/checkstyle.xml} that have to be read together. {@code JavadocPackage}
 * sits at checker level, where it audits the directory and asserts only that a
 * {@code package-info.java} file is present. Its companion {@code MissingJavadocPackage} sits
 * inside the tree walker, where it reads that file and asserts that the file carries Javadoc. An
 * empty file, or one carrying only an ordinary block comment, therefore satisfies the first and
 * fails the second, which is why this file opens on a documentation comment. Both run at the Maven
 * {@code validate} phase, ahead of compilation, and neither is escaped by a build that declines to
 * run tests, so deleting this file does not merely lose the charter: it fails the whole package
 * before a single Java file is compiled.</p>
 *
 * <p>Assumptions: that consequence deserves stating precisely, because the loose version of it is
 * testable and comes out wrong. {@code JavadocPackage} reports against each Java source it audits,
 * so it is the presence of a source in this directory that makes the charter mandatory: with either
 * adapter present, removing this file fails {@code validate}, whereas removing it from a directory
 * holding nothing else raises no finding, there being no source left to audit. A reader who checked
 * the claim against an otherwise-empty directory would see a green build and conclude the paragraph
 * above was wrong, when what the pair actually guarantees is narrower and stronger: no Java source
 * in this package can reach the compiler without a charter beside it.</p>
 *
 * <p>Alternatives Considered: a {@code README.md} in this directory carrying the charter instead.
 * Rejected on two grounds that hold independently. This package is closed at three files, this
 * charter and the two adapters named above, so a fourth file is outside the contract whatever it
 * holds. And a Markdown file is invisible to both checks just described, because the checker
 * narrows its audit to the {@code java} file extension, so the charter would sit beside a package
 * that still failed the documentation gate for carrying no charter at all.</p>
 *
 * <p>Alternatives Considered: the category labels throughout this charter are written in the
 * plural, unparenthesised, colon-terminated form with no emphasis markup, which diverges from the
 * singular parenthesised idiom predominating in this repository's older prose. Matching that
 * majority idiom was weighed and rejected. Rule 1 states these categories at its lines 31 to 34,
 * and its validation gate at line 43 makes that wording the sentence this tree is audited against,
 * so the audited spelling is the one that has to appear. A reviewer looking for every rationale
 * across the new trees has only a literal string search to work with, because no linter reads
 * prose in a Terraform file or a SQL migration; one spelling makes that search complete, while two
 * make it silently partial. The two forms are never mixed inside one file.</p>
 *
 * <p>Assumptions: this charter carries no parameter, return-value, authorship or version
 * at-clause, and each omission is a fact about the subject rather than an economy. A package
 * declares no parameter and returns no value, so the parameter and return-value elements Rule 1
 * lists at its lines 19 and 20 have nothing to describe here and the purpose element at its line
 * 18 carries the whole obligation; inventing either at-clause would state something untrue of a
 * package. An authorship or version at-clause is required by no check in this build and would
 * record metadata that the version-control history already holds more accurately and keeps up to
 * date without being edited.</p>
 */
package com.carddemo.auth.api;
