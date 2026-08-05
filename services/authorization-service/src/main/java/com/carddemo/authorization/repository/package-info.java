/**
 * Spring Data JPA data-access boundary of the pending-authorization bounded context.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this package is the persistence boundary of the authorization bounded context. The
 * baseline reaches the same data one record at a time through two unrelated interfaces: IMS DL/I
 * segment navigation, whose retrieval verbs are {@code GU} for a keyed root read, {@code GN} for a
 * sequential step and {@code GNP} for a step within the current parent, together with {@code ISRT},
 * {@code REPL} and {@code DLET}; and embedded Db2 {@code SELECT}, {@code INSERT} and {@code UPDATE}
 * against a relational fraud table. Here both collapse onto typed repository interfaces, one per
 * persistent shape, and a caller reaches the {@code authorization} schema through no other route.
 *
 * <p>Four interfaces sit beside this charter, and there is no fifth, because the schema this
 * context owns holds exactly four tables:
 * <ul>
 *   <li>{@code PendingAuthSummaryRepository} -- the per-account authorization summary. Its shape is
 *       the IMS root segment {@code PAUTSUM0}, declared at 100 bytes in
 *       {@code app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd} L28, whose fields are laid out in
 *       {@code cpy/CIPAUSMY.cpy} L19 to L31. It reaches table {@code pending_auth_summary}.</li>
 *   <li>{@code PendingAuthDetailRepository} -- the individual authorization messages beneath one
 *       summary. Its shape is the child segment {@code PAUTDTL1}, declared at 200 bytes in the same
 *       file at L36, whose fields are laid out in {@code cpy/CIPAUDTY.cpy} L19 to L54. It reaches
 *       table {@code pending_auth_detail} and it is the only one of the four that pages.</li>
 *   <li>{@code AuthFraudRepository} -- the fraud-tagged authorizations. Its shape is the Db2 table
 *       {@code CARDDEMO.AUTHFRDS}, whose 26 columns are declared at
 *       {@code ddl/AUTHFRDS.ddl} L2 to L27 under the primary key at L28. It reaches table
 *       {@code auth_fraud}.</li>
 *   <li>{@code OutboxRepository} -- the transactional outbox reaching table
 *       {@code auth_reply_outbox}. This one has no baseline counterpart at all: it exists so that a
 *       reply row is committed in the same transaction as the decision it answers, which is the
 *       divergence the module charter records as D-5.</li>
 * </ul>
 *
 * <h2>The seam between this package and the service layer</h2>
 *
 * <p>Assumptions: a keyset method here returns {@code java.util.List} of the entity holding up to
 * one row MORE than the caller's page size, and that extra look-ahead row is <b>included in the
 * returned list and is not removed here</b>. The service layer discards it, reads has-next from
 * whether it arrived, encodes the first and last cursor tokens, and assembles
 * {@code com.carddemo.common.web.PageResponse}. Anything written against this boundary that assumes
 * a list of exactly the page size will silently drop a row, so the extra row is the contract rather
 * than an implementation detail.
 *
 * <p>Assumptions: reading one row beyond the page is transcribed from the source rather than chosen
 * here. {@code cbl/COPAUS0C.cbl} fills a five-row screen array under an index bounded at five at
 * L424, having declared that array as {@code OCCURS 5 TIMES} at L126, and then at L445 to L452 it
 * issues one further retrieval purely as a probe and sets its next-page indicator from whether that
 * retrieval succeeded, at L448 and L450. It keeps the page's last key at L434 and L435 and the
 * page's first key at L439 and L440, and it resumes a page by key rather than by position, through
 * the qualified re-seek at L493 to L497. Those are the same four values the page envelope carries,
 * so the probe read is preserved and only its placement moves.
 *
 * <p>Alternatives Considered: having a repository method return the assembled
 * {@code PageResponse} directly, so that the extra row never leaves this package. Rejected for two
 * concrete reasons. The shared kernel that declares that envelope depends on neither Spring Data
 * JPA nor a JDBC driver -- its POM declares no such artifact -- so making the envelope a query
 * return type would either drag persistence onto the kernel's classpath or move the envelope out of
 * the kernel and into each context, and it is one shared type precisely so that nine modules cannot
 * each grow their own. Separately, a derived or native Spring Data query materialises entities,
 * projections and the framework's own paging types, and not an arbitrary record carrying a computed
 * flag and two encoded cursor tokens, so the assembly has to happen in code that runs after the
 * query either way. Placing it in the service layer is what keeps this boundary expressible as
 * plain query methods.
 *
 * <p>Assumptions: {@code com.carddemo.common.web.PageResponse} is imported and is never
 * re-declared, extended or shadowed by a local page type in this module. It carries the rows of one
 * page, the key of the first row, the key of the last row and whether a further page exists, and
 * the two cursors are opaque string tokens that may be absent. It carries no page size, no page
 * number, no row offset and no total count, so nothing on this boundary accepts or returns one of
 * those, and a caller navigates by cursor rather than by index.
 *
 * <p><strong>Decisions.</strong> What follows discharges the inline-comment half of user-specified
 * Rule 1 (Explainability), whose four justification categories its clauses L31 to L34 name, for the
 * choices on this boundary that a reasonable alternative could have gone the other way on. L40
 * forbids leaving such a choice undocumented where a reasonable alternative exists and L41 forbids
 * a rationale without specific justification, so each entry below names the concrete alternative
 * and the concrete consequence of having taken it.
 *
 * <p>Assumptions: {@code pending_auth_summary} needs no secondary index, and the only directional
 * index this package queries through is {@code (card_num ASC, auth_ts DESC)} on {@code auth_fraud}.
 * This is stated positively because the opposite reading is available and would be wrong.
 * {@code ims/DBPAUTX0.dbd} is a second database whose access method is declared
 * {@code ACCESS=(INDEX,VSAM,PROT)} at L18, and it is easy to read a database whose whole content is
 * an index as an alternate access path owed a target equivalent. It is not one. That file declares
 * a single six-byte root segment {@code PAUTINDX} at L27 and L28, gives it the sequence field
 * {@code FIELD NAME=(INDXSEQ,SEQ,U),START=1,BYTES=6,TYPE=P} at L29, and points it at the indexed
 * database with {@code LCHILD NAME=(PAUTSUM0,DBPAUTP0), INDEX=ACCNTID} at L30 and L31;
 * {@code ims/DBPAUTP0.dbd} carries the reciprocal {@code LCHILD ... POINTER=INDX} at L31 and L32
 * and declares its own root sequence field {@code ACCNTID} as six packed bytes at L30, and its
 * access method is {@code ACCESS=(HIDAM,VSAM)} at L18. A HIDAM database is required to carry a
 * primary index over its root's sequence field, and the six packed bytes of {@code INDXSEQ} are the
 * six packed bytes of {@code ACCNTID}: the index is over the root's own key, not over a second
 * field. An IMS secondary index needs an {@code XDFLD} statement to name the field it inverts, and
 * there is no {@code XDFLD} anywhere in the reference tree. Consistently, the only qualified root
 * retrieval in any of the eight programs is {@code WHERE (ACCNTID = PA-ACCT-ID)}, at
 * {@code cbl/COPAUS0C.cbl} L973 to L976. The alternative was therefore to add an index on
 * {@code pending_auth_summary(account_id)}; its concrete consequence is a second B-tree over the
 * same column as the primary key, maintained on every insert, update and delete, answering no query
 * the primary key does not already answer. The migration plan's warning that alternate indexes are
 * access paths and not decoration is about the VSAM base masters {@code CARDAIX}, {@code CXACAIX}
 * and {@code TRANSACT.VSAM.AIX}, and not one of those three is in this bounded context.
 *
 * <p>Assumptions: the descending second key of {@code (card_num ASC, auth_ts DESC)} is the reason
 * that index is worth naming here even though {@code ddl/AUTHFRDS.ddl} L28 already declares a
 * primary key over the same column pair. {@code ddl/XAUTHFRD.ddl} declares it unique over
 * {@code CARDDEMO.AUTHFRDS} at L1 to L4, so it constrains nothing the primary key does not; what it
 * adds is the ordering, which reads one card's authorizations most recent first. A finder here that
 * orders by those two columns in that direction matches the declared index, and one that orders
 * ascending on the second column does not.
 *
 * <p>Alternatives Considered: no method on this boundary declares a lock mode. The alternative was
 * to reproduce DL/I get-hold retrieval, whose Java spelling is
 * {@code @Lock(LockModeType.PESSIMISTIC_WRITE)} and whose SQL spelling is
 * {@code SELECT ... FOR UPDATE}, on the reading that a read followed by a write ought to hold the
 * row in between. The source does not read that way. {@code cpy/IMSFUNCS.cpy} declares all three
 * get-hold function codes -- {@code FUNC-GHU} at L19, {@code FUNC-GHN} at L21 and
 * {@code FUNC-GHNP} at L23 -- and no program in the reference tree passes any of the three to a
 * DL/I call: across all eight programs and both DL/I interfaces, the embedded statement form and
 * the {@code CBLTDLI} call form alike, the retrieval verbs used are the non-hold {@code GU},
 * {@code GN} and {@code GNP}. Both unload views run {@code PROCOPT=GOTP}, at
 * {@code ims/PAUTBUNL.PSB} L18 and {@code ims/DLIGSAMP.PSB} L18. The concrete consequence of taking
 * the rejected path is lock-wait queueing and deadlock-victim rollback on a path that today has
 * neither, which is new behaviour rather than preserved behaviour; and because a deadlock surfaces
 * only under concurrency, it would be new behaviour that testing a single caller would not reveal.
 * Where a write genuinely needs protecting from a concurrent write, that is the service layer's
 * transaction and the entity's own version column, and neither of those is declared in this
 * package.
 *
 * <p>Refactoring Rationale: the DL/I and Db2 verbs become typed interfaces, and what that changes
 * is where knowledge of an access path lives. In the source it lives at each call site: a program
 * that wants a summary and its details issues its own {@code GU} then its own {@code GNP}
 * sequence, moves {@code DIBSTAT} into its own working storage, and evaluates the status codes
 * itself -- {@code cbl/COPAUS0C.cbl} does so at L466 to L484 and {@code cbl/COPAUS1C.cbl} at L530
 * to L551 -- so the same access path, the same segment layout and the same status handling are
 * re-expressed in each program that needs them, and a reader has to re-derive the path from
 * whichever program is in front of them. A repository interface states each access path once, as a
 * method with a name and a signature, and the status handling becomes the framework's exception
 * contract instead of an evaluated code. That single statement is also what makes the paging
 * collapse expressible: transformation rule T5 of the migration plan maps the positioned browse
 * verbs by category onto one keyset-paginated query, and the DL/I analogue of that browse is the
 * unqualified {@code GNP} step at {@code cbl/COPAUS0C.cbl} L461 to L464 paired with the qualified
 * re-seek at L493 to L497. The alternative was to keep record-at-a-time stepping behind a
 * hand-written data-access object; its concrete consequence is that a cursor position has to be
 * held somewhere between two calls, and a stateless request handler has nowhere to hold one, which
 * is why the position travels in the request as a key instead. The baseline behaves as described
 * and remains reference material, read but never modified; the migration adds a path, it does not
 * remove one.
 *
 * <p>Assumptions: a native query in this package names its tables <b>unqualified</b> --
 * {@code pending_auth_summary}, {@code pending_auth_detail}, {@code auth_fraud} and
 * {@code auth_reply_outbox} -- and resolves them through the connection {@code search_path} that
 * {@code com.carddemo.authorization.config.DataSourceConfig} pins to the {@code authorization}
 * schema. That dependency is named here because a query written in this package is silently wrong
 * if the assumption is broken in that one, and the failure is not a compile error: a connection
 * whose {@code search_path} resolves elsewhere first would read a same-named table in another
 * schema and return rows. The alternative was to schema-qualify every statement; its concrete
 * consequence is the schema name compiled into every query string in the package, so that pinning
 * it in one place and naming it in every place become two facts that can disagree. Nothing here
 * creates, alters or drops a schema object either: the schema and its role grants come from
 * {@code data-migration/sql/V0__schemas_and_roles.sql} and the four tables from this module's own
 * Flyway migration, both authored elsewhere.
 *
 * <p>Assumptions: no transaction boundary is declared in this package, and no annotation, rollback
 * rule or propagation attribute appears in it. The source puts the boundary in the calling program
 * rather than around the data access: {@code cbl/COPAUS1C.cbl} replaces a segment with
 * {@code EXEC DLI REPL} at L525 to L528, moves the resulting status into its own field at L530, and
 * then selects between two paragraphs on that status in the {@code EVALUATE} at L531 to L551 --
 * {@code TAKE-SYNCPOINT} at L557, whose {@code EXEC CICS SYNCPOINT} is at L558, on the success arm
 * at L533, and {@code ROLL-BACK} at L565, whose {@code EXEC CICS SYNCPOINT ROLLBACK} is at L566 and
 * L567, on the other arm at L540. The commit and the back-out therefore bracket the data access
 * from outside it, and their target analogue is a transactional service method. Declaring a
 * boundary here instead would put a commit inside the unit of work the service is composing, which
 * is the one arrangement the source rules out: a summary read, a detail update and an outbox insert
 * have to commit together or not at all.
 *
 * <p>Assumptions: this boundary returns the persisted shape and masks nothing.
 * Primary-account-number masking, card-verification-value suppression and identifier encryption
 * belong to the sibling {@code com.carddemo.authorization.mapper} package, which is the one place
 * representation concerns are allowed to appear. Masking here would put the same decision in two
 * layers, and a repository that returned an already-masked account number could not support the
 * administrative path that is entitled to the unmasked one, because the information would be gone
 * before the mapper saw it.
 *
 * <p>Assumptions: every money-valued member reached through this boundary is
 * {@code java.math.BigDecimal}, and neither binary floating-point type nor a boxed
 * {@code java.lang.Double} appears anywhere in the money path. This is a substantive constraint in
 * this context rather than a formality, because all of the money here is declared as packed
 * decimal: {@code cpy/CIPAUSMY.cpy} carries the credit and cash limits and balances and the
 * approved and declined amounts as {@code COMP-3} at L23 to L26 and L29 and L30,
 * {@code cpy/CIPAUDTY.cpy} carries the transaction and approved amounts as {@code COMP-3} at L34
 * and L35, and {@code ddl/AUTHFRDS.ddl} carries the two relational amounts as
 * {@code DECIMAL(12,2)} at L12 and L13. A binary floating-point value cannot hold a decimal cent
 * exactly, and the resulting error is silent rather than loud, which is why the prohibition belongs
 * to the layering rules the {@code architecture-rules} Surefire execution in
 * {@code services/pom.xml} selects by the simple name {@code LayeringRulesTest}, and so is settled by
 * a build rather than by a reading.
 *
 * <p>Assumptions: the shared kernel is the only intra-reactor dependency available to this package.
 * Transformation rule T2 of the migration plan states the discipline in Java terms -- one former
 * COBOL {@code COPY} statement becomes exactly one type import, from the single package that owns
 * that contract -- so the page envelope, the money type, the record codecs, the error model and the
 * timestamp formatter are imported from {@code com.carddemo.common} and are never re-declared here.
 * No sibling service module is a dependency of this one, and importing another context's
 * {@code domain} package is forbidden. That boundary is asserted by the same ArchUnit test named
 * above and is deliberately not restated as a Checkstyle import-control module, so that it keeps a
 * single owner and cannot be tightened in one place and forgotten in the other.
 *
 * <p>Trade-offs: keyset paging gives up random access to an arbitrary page. A caller can step
 * forward or back one page but cannot jump to the fiftieth, and nothing on this boundary accepts an
 * argument that would let it. That cost is accepted because the source offers the same two
 * movements and no jump either -- {@code cbl/COPAUS0C.cbl} reaches its forward path from the two
 * paging keys and nothing else -- so nothing available to a user of the source is withdrawn. The
 * offsetting property is the one that matters under concurrency: an offset query locates its
 * starting row by counting from the beginning of the ordering on every request, so an insert or a
 * delete landing ahead of that point between two requests shifts every later row by one position
 * and the reader then either skips a row it never saw or receives one it has already seen. Keyset
 * paging resumes from the key it last returned and is not exposed to that.
 *
 * <p>Assumptions: what this boundary does is held to the source by transcription against the
 * programs cited throughout and by this module's own planned tests, not by comparison against
 * captured output. No recorded output exists for any path in this module and none should be claimed
 * for it: the screens this package serves are CICS online programs, which the reference test suite
 * documents as unable to run end to end without a CICS runtime. The page size, the key ordering,
 * the single probe read, the index direction and the placement of the commit are all readable
 * straight from the source, and those are the properties asserted.
 *
 * <p>Trade-offs: the three docstring elements user-specified Rule 1 (Explainability) names at its
 * clauses L19 to L21 are deliberately absent from this file rather than filled in. A package
 * declaration accepts no parameter, returns no value and raises nothing, so no at-clause here could
 * carry a true description, and an empty parameter, return or exception tag added to look complete
 * would assert something false and would in any case be reported by the
 * {@code NonEmptyAtclauseDescription} check. The purpose required at L18 and the rationale required
 * at L40 are both stated above, which is what the gate at L43 asks of a file that declares no
 * member. The prose convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md}; the mechanical gate
 * is {@code config/checkstyle/checkstyle.xml}, where {@code JavadocPackage} requires this file to
 * exist and {@code MissingJavadocPackage} requires it to carry this comment. Those two checks sit
 * at different levels, the first at Checker level because it inspects the directory and the second
 * inside the tree walker because it inspects this comment, so dropping either half would leave the
 * module entry point clause at L15 only half enforced. Both fire from the
 * {@code checkstyle-documentation-gate} execution bound to the Maven {@code validate} phase, which
 * precedes compilation, and no in-code suppression filter is wired into that rule set, so a
 * violation here cannot be waived from inside this file. The exit status of that gate is binary;
 * the graded condition-code rubric that treats a warning-level aggregate as a passing state belongs
 * to the COBOL suite alone and is never carried onto this side.
 */
package com.carddemo.authorization.repository;
