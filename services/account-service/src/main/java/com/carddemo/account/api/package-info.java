/**
 * REST boundary of the account bounded context, and the one place in this context where an HTTP
 * request becomes a call and an outcome becomes a status code.
 *
 * <p>Three responsibilities belong here and no fourth one does: binding an incoming request,
 * validating the shape of that request declaratively, and shaping the outcome into a response with a
 * status code. Business rules live one layer down, in {@code com.carddemo.account.service}, which is
 * the division the migration plan assigns at its section 0.4.1.2 where it describes a REST layer
 * carrying validation and no business logic. Persistence lives one layer down again, in the
 * repository layer, and nothing in this package reaches it directly.
 *
 * <h2>Target contract, not a directory listing</h2>
 *
 * <p>Assumptions: every class name, sibling package name and responsibility recorded in this charter
 * states the package's <b>target contract</b> as the migration plan assigns it at its section 0.5.1.3,
 * where this service's files are enumerated, and not a census of whatever
 * {@code services/account-service/src/main/java/com/carddemo/account/api} happens to contain when it is
 * read. The roster below is therefore closed: a type placed in this package that the roster does not
 * name is outside the contract rather than merely new.</p>
 *
 * <p>Alternatives Considered: deriving the roster from the directory rather than from the plan.
 * Rejected on two independent grounds. A roster that describes only what is present cannot express
 * what may <em>not</em> be added, and that prohibition is exactly the half of a package contract a
 * reader cannot reconstruct by listing files. Separately, {@code JavadocPackage} audits a directory
 * rather than a single compilation unit, so this charter has to govern the directory as a whole and
 * has to be readable before any class in it exists, or the directory fails the documentation gate
 * and the module does not build.</p>
 *
 * <h2>The roster, and the baseline programs behind it</h2>
 *
 * <p>Three controllers belong to this package and the set is closed. Each is named with the baseline
 * programs whose presentation surface it re-expresses, so that a reader can move from a Java type to
 * the specification that fixes its behaviour without guessing. The lengths are the physical lengths
 * of those reference sources, given because they are the honest measure of how much logic each one
 * carries.
 *
 * <ul>
 *   <li>{@code AccountController} carries the account view and the account update. The view path is
 *       transcribed from {@code app/cbl/COACTVWC.cbl}, 941 lines, reached in the baseline by CICS
 *       transaction {@code CAVW}, whose resource definition opens at {@code app/csd/CARDDEMO.CSD}
 *       L317 and names its program at L318. The update path is transcribed from
 *       {@code app/cbl/COACTUPC.cbl}, 4236 lines and the largest online program in the baseline,
 *       reached by transaction {@code CAUP}, defined from L306 with a description line at L307 and
 *       naming its program at L308.</li>
 *   <li>{@code CustomerController} carries the customer read paths, transcribed from
 *       {@code app/cbl/CBCUS01C.cbl}, 178 lines. That program contains no {@code EXEC CICS} verb at
 *       all and appears nowhere in the resource definitions, so it is a batch reader with no CICS
 *       transaction behind it and no screen of its own. What this controller publishes is therefore
 *       a read surface the baseline reached only through the account view, and the record layout
 *       rather than a map is what fixes its fields.</li>
 *   <li>{@code CardXrefController} carries the card cross-reference lookups, transcribed from
 *       {@code app/cbl/CBACT03C.cbl}, 178 lines and likewise a batch reader with no
 *       {@code EXEC CICS} verb. It includes the lookup by account identifier that stands in for the
 *       {@code CXACAIX} alternate index, defined as a CICS file at {@code app/csd/CARDDEMO.CSD}
 *       L63.</li>
 * </ul>
 *
 * <p>Assumptions: the by-account lookup is a distinct operation rather than a filter over the
 * by-card one, because the baseline record itself admits only one direct path. The cross-reference
 * file is declared with {@code RECORD KEY IS FD-XREF-CARD-NUM} at {@code app/cbl/CBACT03C.cbl} L32,
 * so the card number is the base key and reaching a row by account was possible only through the
 * separate alternate index named above. The target replaces that index with a secondary index on the
 * same column, which is what allows the second access path to be published as an ordinary operation
 * with no second table and no duplicated row.</p>
 *
 * <p>Assumptions: this charter deliberately records no count of endpoints, paths, request methods or
 * status codes. The migration plan fixes this service's responsibilities at its section 0.5.1.3 and
 * fixes no operation inventory, so a count written here would be a second and competing statement of
 * the surface. Responsibilities are attributed to named baseline programs instead, because those
 * programs can be opened at the cited lines and a count could not be.</p>
 *
 * <p>Refactoring Rationale: this paragraph previously justified the absence of a count partly on the
 * grounds that no {@code openapi} directory existed under
 * {@code services/account-service/src/main/resources} for one to be read from. That is no longer true.
 * {@code openapi/account-api.yaml} now exists and is the contract of record for EVERY operation this
 * package publishes, and the sentence was corrected rather than left standing because a charter that
 * misdescribes its own module is worse than one that says less.</p>
 *
 * <p>Refactoring Rationale: the sentence that replaced it was corrected a second time, and the
 * correction is worth recording because the first version described a state that had already been left
 * behind. It said the document declares ONLY the three internal read operations and deliberately not
 * the account view or account update named in the roster above -- which was true when the view and the
 * update did not exist. They were then built and mounted here, and the document was not extended, so
 * three routes served requests that no contract described. That is the same defect as a contract
 * publishing an operation with no handler, in the opposite direction, and it is closed the same way:
 * the document now declares both surfaces and marks each operation with the surface it belongs to, and
 * the contract test at
 * {@code src/test/java/com/carddemo/account/api/AccountContextContractTest.java} compares the mapping
 * annotations of all three controllers with the operations the document declares and fails on any
 * difference in EITHER direction. Neither the document nor this package can gain or lose an operation
 * without the other.</p>
 *
 * <p>Assumptions: the two surfaces share the account address rather than being separated by a path
 * prefix -- the machine read is a {@code GET} on {@code /api/v1/accounts/{accountId}} and the end-user
 * edit is a {@code PUT} on the same address -- and they are separated by filter CHAIN instead, which
 * {@code SecurityConfig.ACCOUNT_PATH_PATTERN} records in full. One consequence is load bearing enough
 * to state here: the matcher that selects the internal chain has to name the METHOD and not the path
 * alone, because a path-only matcher claims every method at that address and would demand a
 * service-minted token for the end-user edit, which no browser holds. That property is asserted by
 * {@code InternalApiSecurityConfigTest}.</p>
 *
 * <h2>The layer boundary, and the two prohibitions that define it</h2>
 *
 * <p>Trade-offs: two prohibitions hold at this boundary, and together they are the reason a separate
 * set of request and response types exists at all rather than persistence types being published
 * directly. The migration plan assigns this layer validation and no business logic at its section
 * 0.4.1.2, and these two prohibitions are what that assignment amounts to in practice.
 *
 * <ul>
 *   <li><b>No JPA entity is accepted or returned by any controller in this package.</b> The entities
 *       {@code Account}, {@code Customer} and {@code CardXref} in the sibling {@code domain} package
 *       do not cross this boundary in either direction.</li>
 *   <li><b>No mapper is injected here.</b> Translation between a persistence type and a published
 *       type is invoked from the service layer, so the only record shapes this package handles are
 *       the request and response types themselves.</li>
 * </ul>
 *
 * <p>The compromise accepted is real and worth naming: a separate published type means one more
 * indirection to write and to keep aligned than returning an entity straight from a handler would
 * cost, and returning entities directly is a shape many Spring codebases adopt, so the choice here
 * is a decision rather than a default. What it buys is that the boundary is enforced instead of
 * merely intended. The layering rules at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * forbid web types inside a {@code ..domain..} package, so an entity annotated for serialisation
 * fails a test rather than passing a review, and the prohibition holds even where nobody is looking
 * for it. The second prohibition earns its place for a different reason: it fixes one direction of
 * travel through the layers, from the service layer into the mapper and never from a controller into
 * it, and a listing anywhere that presents a controller in this package as a caller of a mapper is
 * superseded by this charter rather than describing an alternative that is also permitted.</p>
 *
 * <h2>What replaces the communication area</h2>
 *
 * <p>Refactoring Rationale: the baseline is strictly pseudo-conversational. Each of the online
 * transactions named above ends its task at every screen turn, so every scrap of continuity between
 * turns lived in one passed structure, {@code CARDDEMO-COMMAREA}, declared at
 * {@code app/cpy/COCOM01Y.cpy} L19 and spanning L19 to L44 across five subordinate groups and 160
 * bytes in total. That structure does not travel into this package, no part of it is echoed back to
 * a caller, and it has no single replacement. It decomposes into four separate mechanisms, and the
 * decomposition is what makes a stateless boundary possible rather than merely desirable.
 *
 * <ul>
 *   <li><b>Selection context</b> -- {@code CDEMO-CUST-ID} at {@code app/cpy/COCOM01Y.cpy} L33,
 *       {@code CDEMO-ACCT-ID} at L38, {@code CDEMO-ACCT-STATUS} at L39 and {@code CDEMO-CARD-NUM} at
 *       L41 -- arrives as path and query parameters. That is what makes each request
 *       self-describing, and a self-describing request is one that can be authorized on its own
 *       terms rather than against something the server remembered from a previous turn.</li>
 *   <li><b>Identity</b> -- {@code CDEMO-USER-ID}, a {@code PIC X(08)} at L25, and
 *       {@code CDEMO-USER-TYPE}, a {@code PIC X(01)} at L26 whose two condition names admit
 *       {@code 'A'} at L27 and {@code 'U'} at L28 -- arrives as claims on a validated bearer token.
 *       The substance of that change is recorded below rather than left implicit, because it is a
 *       change in what the caller is able to assert and not only a change of transport.</li>
 *   <li><b>Navigation</b> -- the from-program and to-program fields with their transaction
 *       counterparts at L21 to L24, and the last map and mapset at L43 to L44 -- is entirely
 *       client-side. No response leaving this package names a next program, a next transaction or a
 *       next screen, and there is no property anywhere in this package's published types that
 *       carries one.</li>
 *   <li><b>The re-entry discriminator</b> -- {@code CDEMO-PGM-CONTEXT} at L29, with its enter value
 *       at L30 and its re-enter value at L31 -- has no counterpart here at all, and that absence is
 *       deliberate rather than an omission. A stateless handler that answers a malformed request
 *       with a per-field error array has no first-entry-against-re-entry distinction left to draw,
 *       so field-level error presentation is driven by the response body alone and by nothing the
 *       server retained.</li>
 * </ul>
 *
 * <p>Assumptions: the identity substitution changes what a caller is able to assert, and the baseline
 * evidence for that is specific. The communication area is storage the client is handed and hands
 * back, and both online programs write the user type into it on their way out:
 * {@code app/cbl/COACTVWC.cbl} L344 and {@code app/cbl/COACTUPC.cbl} L947 each set the user
 * condition name immediately before transferring control. Nothing at the CICS level re-checked it,
 * either, since both transaction definitions declare {@code RESSEC(NO) CMDSEC(NO)} -- at
 * {@code app/csd/CARDDEMO.CSD} L314 for {@code CAUP} and L324 for {@code CAVW} -- so no resource
 * check and no command check stood behind that field. The echoed field was the authorization. In the
 * target the caller asserts nothing at all: the group claim is signed by the issuer, the filter chain
 * declared in {@code com.carddemo.account.config} decides what each operation requires, and no
 * handler body in this package reads an authority out of a request payload.</p>
 *
 * <p>Assumptions: the navigation fields are load bearing in the baseline rather than advisory, which
 * is why replacing them is a decomposition and not a deletion. The transfer of control at
 * {@code app/cbl/COACTVWC.cbl} L349 and at {@code app/cbl/COACTUPC.cbl} L956 to L959 names
 * {@code PROGRAM (CDEMO-TO-PROGRAM)} -- the communication-area field itself -- rather than a program
 * literal, so the next program really was chosen from data travelling with the request. The target
 * makes the equivalent choice in the client's router, which is the only place it can live once no
 * response carries a next-program field.</p>
 *
 * <p>Assumptions: the baseline's own resource definitions corroborate that a stateless boundary is
 * faithful to it rather than a departure from it, and that corroboration is worth recording because
 * the argument would otherwise rest on the target design alone. Both transactions declare
 * {@code TWASIZE(0)} -- {@code app/csd/CARDDEMO.CSD} L308 for {@code CAUP} and L318 for
 * {@code CAVW} -- so the per-task work area each could have reserved is zero bytes wide in both.
 * There was nowhere else for continuity to live, which is precisely why the communication area can be
 * decomposed into request-scoped and client-side mechanisms without stranding state that had no other
 * home. The consequence for this package is unconditional: nothing here holds state between
 * requests, so there is no sticky session, no server-side session store, no {@code HttpSession} and
 * no server-side navigation state, and that is what lets the container tasks serving these paths
 * scale horizontally behind a load balancer with no affinity requirement.</p>
 *
 * <h2>Shared contracts, consumed and never re-declared</h2>
 *
 * <p>Alternatives Considered: an error shape and a pagination envelope declared locally in this
 * service, which is the obvious alternative and was rejected. Migration plan rule T2 makes one
 * former {@code COPY} statement become exactly one import from the single package that owns that
 * contract, and the plan assigns every one of these types to the shared kernel at its section 0.5.1.1,
 * so {@code services/common-lib} is that single owner and this service is a consumer of it. The
 * arrangement mirrors the baseline exactly, where every program reached one copybook library through a
 * single compiler include path rather than keeping a private copy of a record layout. Duplicating
 * either shape per service would let two services drift apart on the wire while each remained
 * internally consistent, and wire drift between two
 * independently deployed services is the failure a single owning package exists to prevent. This
 * package therefore consumes {@code com.carddemo.common.error.ApiError} for the problem body,
 * {@code com.carddemo.common.error.GlobalExceptionHandler} as the single advice that renders it,
 * {@code com.carddemo.common.error.AbendDetail} for the structured system-error surface,
 * {@code com.carddemo.common.web.PageResponse} for the page envelope,
 * {@code com.carddemo.common.validation.FieldValidationFlag} for per-field validation state and
 * {@code com.carddemo.common.money.Money} for every monetary value, and it declares no local
 * equivalent of any one of them. No local error type, no local pagination type, no local exception
 * advice and no local message catalog appears in this package.</p>
 *
 * <h2>Pagination positioned by key</h2>
 *
 * <p>Alternatives Considered: offset pagination, rejected on correctness grounds. Every
 * list-returning operation in this package positions its page by key and answers with
 * {@code com.carddemo.common.web.PageResponse}, accepting an opaque key cursor together with a page
 * size, and it accepts neither a page number nor a row offset. The baseline settles this rather than
 * taste doing so: the browse state it carries between turns is already a keyset cursor. At
 * {@code app/cbl/COCRDLIC.cbl} L230 to L244 it holds a last-key pair, a first-key pair, a screen
 * number, a last-page-displayed flag and a next-page indicator, with a row counter at L145, and there
 * is no offset, no row number and no page-size field anywhere in that structure. What specifically
 * goes wrong in the rejected alternative is that an offset is evaluated against the table as it stands
 * at the moment each page is fetched, so rows inserted or removed between two fetches shift the window
 * and the caller silently skips rows or receives the same row twice. A cursor naming the last key seen
 * resumes from a position that concurrent writes do not move, so paging behaviour stays what the
 * baseline browse produced.</p>
 *
 * <h2>Why this charter exists, and why it omits the tags it omits</h2>
 *
 * <p>Assumptions: this file exists because Rule 1, the project's single user-specified rule, requires
 * a docstring on every module entry point at its L15. In Java a {@code package} declaration is that
 * entry point, and a {@code package-info.java} charter is the only place its docstring can live, so
 * no functional requirement puts this file in scope and that clause alone does. The obligation is
 * mechanised by a pair of checks in {@code config/checkstyle/checkstyle.xml} that have to be read
 * together, because each one alone is satisfiable without the other. {@code JavadocPackage} is
 * declared at <em>Checker</em> level at L245, outside the {@code TreeWalker} that opens at L259, so
 * it is a file-set check and asserts only that a {@code package-info.java} file <em>exists</em> in
 * any directory holding a processed compilation unit. {@code MissingJavadocPackage} is declared at
 * L378 <em>inside</em> that {@code TreeWalker}, and it asserts that the file <em>carries</em>
 * Javadoc. A file holding nothing but a bare {@code package} statement therefore passes the first and
 * fails the second, which is why this charter opens on a documentation comment rather than on a plain
 * block comment, and why it had to be written before the three controllers rather than after
 * them.</p>
 *
 * <p>Assumptions: the gate runs at the Maven {@code validate} phase, bound in
 * {@code services/pom.xml} at L816 to L817 under execution id
 * {@code checkstyle-documentation-gate}, with {@code failOnViolation} true at L906 and
 * {@code violationSeverity} warning at L907, so a finding stops every local build ahead of
 * compilation rather than surfacing only in continuous integration. Worth noting is what the
 * configuration leaves out: not one of the three filters that could silence a finding from inside the
 * source is declared in it, so there is no in-code bypass available and the only way to satisfy the
 * gate is to write the documentation.</p>
 *
 * <p>Assumptions: Rule 1's parameter, return-value and exception elements at its L19 to L21 describe
 * callable code and have no counterpart on a package declaration, so they are omitted here
 * deliberately rather than written out empty. Fabricating such a block tag to look thorough would add
 * a claim nothing could verify and would offend the specificity requirement at L41, which is the
 * clause forbidding a rationale that does not say anything particular. Authorship and version tags are
 * absent for a separate reason: the three formatting checks that would ask for them,
 * {@code JavadocStyle}, {@code WriteTag} and {@code JavadocParagraph}, are themselves declared nowhere
 * in {@code config/checkstyle/checkstyle.xml}, so adding such a tag would be noise rather than
 * compliance. Their absence is recorded here precisely so a later reader does not mistake it for an
 * oversight and supply the tags to fix it.</p>
 *
 * <p>Trade-offs: no golden-master oracle exists for any path this package serves, and the guarantee
 * behind it is correspondingly weaker than the batch contexts enjoy. The online programs named above
 * cannot be run end to end without a CICS runtime, which is absent from the runner, and only their
 * extractable field-validation logic is exercised, as recorded at {@code tests/README.md} L83 to L85.
 * Parity for these paths therefore rests on validation logic transcribed rule by rule from the cited
 * programs and on the copybook record layouts that fix every field width and every decimal scale,
 * verified by the tests under {@code services/account-service/src/test}, and not on byte comparison
 * against recorded mainframe output. That is accepted because the alternative, standing up a CICS
 * region, lies outside this migration and would not be reproducible in continuous integration.
 * Message text is the one part that stays checkable character for character against the programs
 * cited above, which is why any such string is quoted from a cited line rather than paraphrased.</p>
 */
package com.carddemo.account.api;
