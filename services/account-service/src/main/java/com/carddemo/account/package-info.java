/**
 * Stateless account, customer and card-cross-reference bounded context, owning the PostgreSQL schema
 * {@code account} and its three tables.
 *
 * <h2>Charter</h2>
 *
 * <p>Three clauses define this context, and each is a constraint on what may be authored beneath this
 * root rather than a description of what is authored today.</p>
 *
 * <p>It is <strong>stateless</strong>. No request carries continuation state, no sticky session is
 * required and no server-side session store exists, for the reason set out under pseudo-conversational
 * state below. That property is what makes horizontally scaled container tasks behind a load balancer a
 * viable target at all.</p>
 *
 * <p>It <strong>owns the schema {@code account} and exactly three tables</strong> — {@code accounts},
 * {@code customers} and {@code card_xref} — and no fourth table, no view and no seeded reference row. A
 * reader looking here for the transaction-type or state and ZIP lookup rows will not find them; those
 * are owned elsewhere, as recorded under the lookup allow-lists below.</p>
 *
 * <p>It <strong>carries JPA optimistic versioning</strong> on {@code accounts} and {@code customers},
 * and it <strong>carries no transactional outbox</strong>. Both are stated in the charter rather than
 * only in the code that implements them, because both differ from a sibling context and a reader who
 * assumes uniformity across the eight contexts will get exactly one of them backwards.</p>
 *
 * <h2>The six programs this context replaces</h2>
 *
 * <ul>
 *   <li>{@code app/cbl/COACTVWC.cbl}, CICS transaction {@code CAVW} — account view, composing customer
 *       and cross-reference data.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl}, CICS transaction {@code CAUP} — account update, and the largest
 *       online program in the baseline.</li>
 *   <li>{@code app/cbl/CBACT01C.cbl} — account master sequential reader.</li>
 *   <li>{@code app/cbl/CBACT03C.cbl} — card cross-reference sequential reader.</li>
 *   <li>{@code app/cbl/CBCUS01C.cbl} — customer master sequential reader.</li>
 *   <li>{@code app/app-vsam-mq/cbl/COACCT01.cbl} — account inquiry over a request and reply queue.</li>
 * </ul>
 *
 * <p>Assumptions: both online transactions declare {@code TWASIZE(0)} in
 * {@code app/csd/CARDDEMO.CSD}, and that is load-bearing rather than incidental. There is no
 * transaction work area at all, so every scrap of continuity between screen turns lived in the
 * communication area — which is precisely why that area can be decomposed into request-scoped and
 * client-side mechanisms without stranding state that had nowhere else to live.
 * {@code docs/architecture/cobol-to-service-traceability.md} carries the program-to-method pairs and
 * the resource-definition line citations.</p>
 *
 * <h2>Money never leaves fixed point</h2>
 *
 * <p>An amount is {@code NUMERIC(12,2)} in the schema, {@code BigDecimal} at scale two with half-up
 * rounding in Java, and a JSON <em>string</em> on the wire, produced through the shared money module.
 * Alternatives Considered: emitting amounts as JSON numbers so clients receive something
 * arithmetic-looking. Rejected, and it is the more tempting mistake because it looks like a convenience
 * rather than a defect: most clients parse a JSON number into an IEEE-754 binary floating-point value by
 * default, reintroducing representation error at exactly the boundary a balance or a credit limit is read
 * at, after the database and the service have both kept the value exact. Binary floating-point types are
 * barred from the money path outright, and the bar is asserted by the shared architecture test rather
 * than left to review.</p>
 *
 * <p>Assumptions: where an amount is computed, the baseline's order of operations is preserved — a
 * product is taken at full precision first and only then divided, with scale and rounding stated
 * explicitly. Dividing first shifts results by whole cents on ordinary inputs, so re-ordering is a
 * behavioural change and not an optimisation.</p>
 *
 * <p>Assumptions: the source values are zoned decimal with a sign OVERPUNCH, not a printable minus sign,
 * so the sign convention has to be declared rather than defaulted — {@code tests/README.md} records that
 * the ASCII default misreads the overpunch and silently corrupts negative balances. Silently is the
 * operative word: nothing fails and the numbers simply come back wrong, which is why every fixed-width
 * amount is routed through the shared zoned-decimal codec instead of a general-purpose numeric parse.
 * The exemplar is {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy} L7.</p>
 *
 * <p>Assumptions: long identifiers and amounts travel as digits-only strings and are validated for
 * digits, because the baseline itself treats them as characters on the wire and as numbers only inside
 * arithmetic — the before-image below holds each numeric as a character field with a numeric
 * {@code REDEFINES} over it, and every money field on both screen maps is alphanumeric.</p>
 *
 * <h2>Optimistic concurrency is inherited, not invented</h2>
 *
 * <p>Refactoring Rationale: the version column on {@code accounts} and {@code customers} is not a new
 * guarantee imposed on a baseline that lacked one — it is the native expression of a before-image
 * comparison {@code app/cbl/COACTUPC.cbl} already performs. That program snapshots the complete pre-edit
 * record into {@code ACUP-OLD-DETAILS} (L669 onward), holding each numeric as a character field with a
 * numeric {@code REDEFINES} laid over it, carries a tri-state outcome flag distinguishing unconfirmed
 * from applied from failed changes, and on a failed rewrite issues an explicit rollback. Recognising the
 * pattern as already present is what makes the JPA version column a replacement rather than an
 * addition.</p>
 *
 * <p>Assumptions: the read-for-update lock was never held across client think-time — that is exactly why
 * the before-image exists — so nothing is lost by expressing the check as a version column. A version
 * conflict surfaces as HTTP 409 through the shared exception advice, which already maps it; this subtree
 * must not re-implement that mapping.</p>
 *
 * <h2>No transactional outbox, and why this context differs from its siblings</h2>
 *
 * <p>Alternatives Considered: an outbox table in this schema, for symmetry with the authorization
 * context. Rejected because the two contexts have different reply disciplines in the baseline. The
 * authorization consumer publishes its reply OUTSIDE the database transaction, so a crash between commit
 * and publish loses a reply the data says was produced, and an outbox is what closes that window. The
 * inquiry flow this context inherits reads under syncpoint, which maps onto visibility timeout plus
 * delete-on-success with no window to close. Adding an outbox here would add a table, a publisher and a
 * failure mode to guarantee something the transport already guarantees.</p>
 *
 * <h2>Pseudo-conversational state is decomposed, not ported</h2>
 *
 * <p>Assumptions: the baseline's shared communication area decomposes into four different target
 * mechanisms, and the split is the most consequential structural transformation in this context.
 * Navigation fields become client-side router history. Identity becomes signed token claims — a genuine
 * security improvement rather than a port, because the communication area is storage the client echoes
 * back, so a client could in principle assert its own user type, whereas a claim is signed. Selection
 * context becomes request path and query parameters, which is what makes every request independently
 * authorizable. The re-entry discriminator disappears entirely: a stateless handler returning a
 * field-error array has no first-entry-versus-re-entry distinction to make.</p>
 *
 * <p>Assumptions: that last removal severs a coupling worth naming. The baseline's field-highlight logic
 * is gated on the re-entry flag, so in the target the error presentation is driven purely by the response
 * body.</p>
 *
 * <h2>Lists page by key</h2>
 *
 * <p>Alternatives Considered: position-based paging. Rejected because the baseline browse state is
 * already a keyset cursor — it persists a last-key pair, a first-key pair and a next-page indicator, and
 * sets that indicator by discovering one more record than fits — and because under concurrent inserts an
 * offset skips and repeats rows, changing observable behaviour that browse-by-key does not. Forward reads
 * are keyed strictly greater than the last key ascending with a limit of size plus one; backward reads are
 * keyed strictly less than the first key descending, which is what read-previous does.</p>
 *
 * <p>Assumptions: the by-account query over {@code card_xref} stands in for the {@code CXACAIX} alternate
 * index, which the baseline surfaces to CICS as a file in its own right. It is an access path, not a
 * convenience, so it is a real secondary index rather than a filtered scan.</p>
 *
 * <h2>The lookup allow-lists this context queries but does not own</h2>
 *
 * <p>Assumptions: address validation reads phone-area-code, state and state-and-ZIP-prefix rows that the
 * reference context owns and seeds. This context queries them and never defines, seeds or caches them —
 * a local copy would be a second authority over one allow-list, and the two would diverge silently
 * because nothing compares them.</p>
 *
 * <h2>The subtree this package roots</h2>
 *
 * <p>This package holds the Spring Boot entry point and this descriptor at its own level. Every type
 * carrying behaviour belongs to a subpackage named for its layer, and each layer's charter is a
 * constraint on what may appear there — stated as a boundary so it holds as each layer is authored:</p>
 *
 * <ul>
 *   <li>{@code .api} — transport adapters only: request binding, transport-level validation, status
 *       mapping and delegation. No business rule, no persistence access, and no entity leaves here.</li>
 *   <li>{@code .service} — transcribed business behaviour, one named method per significant baseline
 *       paragraph so the traceability matrix can cite pairs, plus transaction boundaries, keyset
 *       orchestration, address validation and the inquiry listener.</li>
 *   <li>{@code .domain} — the three persistence entities derived from the copybooks through the
 *       anti-corruption boundary, with the version column on the account and customer entities.</li>
 *   <li>{@code .dto} — transport records following the symbolic-map and copybook field order and widths,
 *       with amounts and long identifiers as digits-only strings. No local page type, no local error
 *       type.</li>
 *   <li>{@code .config} — stateless token security and any wiring that cannot be expressed as a
 *       property.</li>
 * </ul>
 *
 * <p>Assumptions: the component scan is rooted here and is never widened. Every shared kernel type lives
 * under {@code com.carddemo.common}, outside that root, so shared components are not discovered by the
 * scan at all — they arrive through {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, which
 * the framework loads from the shared module's registration resource.</p>
 *
 * <h2>Dependency discipline</h2>
 *
 * <p>This context declares exactly one dependency inside the build reactor,
 * {@code com.carddemo:common-lib}. It declares no dependency on a sibling service module, and no type
 * beneath this root may import another context's domain package — not as an import, not as a fully
 * qualified name, and not as a string literal resolved reflectively.</p>
 *
 * <p>Assumptions: layering has exactly one owner, the shared architecture test at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}.
 * Because those are assertions in a test rather than prose in a document they cannot rot: a violation
 * fails a build instead of surviving a review. Checkstyle's {@code ImportControl} is deliberately absent
 * for the same reason — adding it would create a second, silently divergent owner of one rule.</p>
 *
 * <p>Alternatives Considered: re-declaring shared types per context, and generating accessor and mapping
 * code. Per-context copies are rejected because one former copybook inclusion becomes exactly one import
 * from the single package that owns that contract, which is the Java counterpart of compiling every
 * baseline program against one copybook path. Accessor generation is rejected because generated members
 * cannot carry the docstrings the Explainability rule requires. Mapping generation is rejected because the
 * mapping is not mechanical: it drops filler, masks the primary account number, encrypts two identifiers
 * and renames a misspelled field, and each of those needs a justification written beside it that a
 * generated mapper has nowhere to hold.</p>
 *
 * <p>Assumptions: the web, validation, security and token resource-server starters are declared by this
 * module directly, because the shared module marks them optional and optional dependencies are not
 * transitive. Assuming otherwise produces a missing class at runtime rather than at build time. Versions
 * are managed by the parent and are never re-pinned here.</p>
 *
 * <h2>How this context is verified</h2>
 *
 * <p>Assumptions: this context has no executable baseline oracle, and claiming one would misdescribe the
 * evidence. The online programs cannot be run end to end without a transaction runtime, which the runner
 * does not have, and the parity suite's verbatim business-rule section names only the interest program,
 * the posting program and the transaction-category balance — none of this context's six programs.
 * Parity here therefore rests on transcribed validation logic and on the copybook contracts rather than
 * on a golden-master comparison.</p>
 *
 * <p>Message text is the exception and is verifiable directly: every user-visible string in this context
 * is present in a copybook or a program at a stated line, so it must be asserted character for character
 * — including the emitted account-filter literal with its doubled space and the conflict message with
 * "some one" as two words.</p>
 *
 * <p>Assumptions: every gate over this module is pass or fail with no tolerated middle result. The graded
 * condition-code rubric, under which code 4 is a passing aggregate, belongs to the baseline COBOL oracle
 * suite alone. Reading a build in this tree through that rubric would treat a real violation as an
 * acceptable outcome, so no build here is ever described in its terms.</p>
 */
package com.carddemo.account;
