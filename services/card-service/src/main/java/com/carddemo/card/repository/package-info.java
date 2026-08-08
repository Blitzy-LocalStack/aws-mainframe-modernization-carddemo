/**
 * Data-access boundary of the card bounded context: the single layer permitted to turn a demand for
 * card rows into a query, and the sole owner of both access paths by which a card record can be
 * reached at all.
 *
 * <h2>Contract and current membership, which now agree</h2>
 *
 * <p>Assumptions: the roster below is both this package's <b>contract</b> as the migration plan assigns
 * it and a <b>measurement of the directory</b>, because the interface it governs exists:
 * {@code CardRepository.java} sits beside this charter and declares the keyed read, the two keyset
 * browse queries and the by-account query described throughout. Every statement in this file is
 * therefore in the plain present tense, with no planned-versus-delivered marking to interpret.</p>
 *
 * <p>Refactoring Rationale: an earlier revision of this section opened with the qualification that "at
 * the checkpoint that authored this charter the directory holds this charter alone", so the interface
 * named throughout was marked planned rather than present. That was true when written and is now false,
 * and it was the costlier of the two possible errors: a reader consulting this charter to learn whether
 * the card access paths had an interface -- which is exactly the question a charter answers -- was told
 * they did not, and would have had no reason to open the file that declares them. The qualification is
 * removed rather than reworded, because what it stood in for is now simply the case. Assumptions: the
 * argument for having authored the charter first is kept below rather than deleted with the marker, since
 * it is the record of a decision and does not go stale with the directory.</p>
 *
 * <p>Alternatives Considered: withholding this charter until the interface it governs existed.
 * Rejected, because this charter is what the author of that interface works from -- which column the
 * cursor is, which direction each query walks, which envelope carries the result, and what may not be
 * declared here at all -- so writing it last would have left the package with no stated contract across
 * exactly the interval in which one was needed.</p>
 *
 * <h2>Purpose, and the verb mapping that terminates here</h2>
 *
 * <p>The migration plan maps the reference application's file verbs onto target constructs by
 * category. A keyed read, write, rewrite or delete becomes a repository method; the four browse verbs
 * {@code STARTBR}, {@code READNEXT}, {@code READPREV} and {@code ENDBR} collapse into one
 * keyset-paginated query. This package is where that mapping terminates, and that is the whole of its
 * responsibility. No layer above it issues a query and no layer below it exists, so the set of ways a
 * card row can be reached is exactly the set of methods declared here, and a reader auditing that set
 * has one file to read rather than a call graph to trace.</p>
 *
 * <p>The roster is one Spring Data JPA interface, {@code CardRepository}, and the set is closed. It
 * spans exactly one entity, {@code Card}, which maps exactly one table, {@code cards}, in the
 * {@code card} schema. Counting to one three times over is worth a sentence because it is not the
 * count a reader arrives with: a sibling context maps three tables inside a single domain package, so
 * a reader coming from there will look for company beside {@code CardRepository} and find none. A
 * second interface appearing here would mean a second table had come into existence outside the
 * migration meant to create it.</p>
 *
 * <p>Assumptions: no type in this package names a schema, and the omission is deliberate rather than
 * an oversight. The schema is pinned twice outside Java, by
 * {@code spring.jpa.properties.hibernate.default_schema} and by the connection initialisation
 * statement {@code SET search_path TO card}, both in this module's {@code application.yml}. A query
 * that named the schema itself would be a third declaration of it, disagreeing silently with those two
 * the first time only one was edited, and it would also defeat the search path that every pooled
 * connection is already opened with.</p>
 *
 * <h2>The keyset cursor, and the single column it is</h2>
 *
 * <p>Assumptions: the cursor is the single sixteen-character column {@code card_num}, and nothing is
 * paired with it. Three independent pieces of evidence settle that, and they are recorded because a
 * reader glancing at the reference program will see a pair and reasonably infer a composite key.
 * First, the reference declares a group of two fields at line 137 of {@code app/cbl/COCRDLIC.cbl},
 * whose members are a sixteen-byte card number at line 138 and an eleven-digit account identifier at
 * line 139; every one of the four browse verbs names the sixteen-byte <b>member</b> rather than the
 * group, at lines 1131, 1150, 1275 and 1298, and each states its key length as the length of that same
 * member. Second, the account-identifier half is present at every repositioning site and
 * <b>commented out</b> at all four of them, at lines 448 to 449, 475 to 476, 490 to 491 and 506 to
 * 507, while the card-number half is moved live at lines 446 to 447, 473 to 474, 488 to 489 and 504 to
 * 505. Third, the cluster definition agrees from outside the program entirely: line 54 of
 * {@code app/jcl/CARDFILE.jcl} declares a key of sixteen bytes beginning at the first byte of the
 * record, inside the cluster defined at line 50.</p>
 *
 * <p>Assumptions: the pair a reader sees is storage for redisplay, not a positioning key. The
 * reference keeps a last-key pair and a first-key pair across the gap between screen turns, declared
 * at lines 229 to 235 of the same program, and a query here consumes only the sixteen-character half
 * of whichever pair applies. Reading that pair as the key would produce queries that compile, run and
 * page differently from the reference at every boundary where two cards share a number prefix.</p>
 *
 * <p>Forward paging walks strictly greater than the cursor in ascending key order; backward paging
 * walks strictly less than it in descending key order, which is what the reference's backward browse
 * does. Whether a further page exists is discovered by requesting one row beyond the page size and
 * observing whether it arrived, and the result travels in {@code com.carddemo.common.web.PageResponse}
 * through its {@code items}, {@code firstKey}, {@code lastKey} and {@code hasNext} components.</p>
 *
 * <p>Assumptions: that one extra row is the reference's own technique rather than an invention, which
 * is why the size-plus-one shape is stated as a contract here and not left to each query to improvise.
 * The reference tests its row counter against its screen limit at line 1191 of
 * {@code app/cbl/COCRDLIC.cbl}, then issues one further read at line 1197, and sets its next-page
 * indicator at line 1210 when that read returned a record or clears it at line 1216 when the read hit
 * end of file. Counting the whole matching set alongside each page was the alternative and is not
 * offered by any method here: it answers a different question at the cost of a second pass over the
 * rows, and the reference never asks it, so answering it would be a behaviour this migration added
 * rather than carried across.</p>
 *
 * <p>Refactoring Rationale: offset pagination is the alternative, and no method here offers it. The
 * reason is behavioural rather than a matter of taste. When rows are inserted or removed between two
 * requests, a query that resumes by counting rows from the start of the result <b>skips rows it never
 * showed and repeats rows it already showed</b>, because the number of rows preceding the resume point
 * has changed underneath it. Resuming from the key of the last row shown can do neither, since that
 * key is unaffected by what was inserted elsewhere. The reference already resumes by key, so carrying
 * its cursor across preserves page boundaries rather than approximating them.</p>
 *
 * <p>Trade-offs: page size is a parameter of the query methods and is deliberately not a constant in
 * this package. The reference sets it at seven, at lines 176 to 178 of {@code app/cbl/COCRDLIC.cbl},
 * corroborated by the comment reading {@code 28 CHARS X 7 ROWS = 196} at line 250 and by the
 * seven-occurrence screen array at line 255 -- but seven is the row capacity of a twenty-four-row by
 * eighty-column terminal, which is presentation geometry and not a property of the table. The target
 * carries the same seven, as {@code carddemo.card.list.page-size} in this module's
 * {@code application.yml}, precisely so that it stays configuration rather than becoming a literal
 * here. The cost accepted is one more argument on every paging method; what is bought is a persistence
 * layer that a screen redesign cannot reach into.</p>
 *
 * <h2>The by-account access path</h2>
 *
 * <p>Assumptions: reaching an account's cards is an indexed access path rather than a scan, and the
 * index is <b>non-unique</b> by contract rather than by caution. The reference does not scan either:
 * line 83 of {@code app/jcl/CARDFILE.jcl} defines an alternate index, related to the same base cluster
 * at line 84 and keyed at line 85 on the eleven bytes at position sixteen of the record -- exactly the
 * account identifier -- then declared {@code NONUNIQUEKEY} at line 86 and {@code UPGRADE} at line 87.
 * A path over that index is what {@code app/csd/CARDDEMO.CSD} surfaces to the online region as the
 * separate file {@code CARDAIX} at lines 13 to 14, enabled for browse at line 19. The target form of
 * that path is the secondary index {@code idx_cards_account_id}. Declaring it unique would refuse the
 * second card on an account, which the reference admits by design.</p>
 *
 * <h2>What this package does not own, and the evidence for each absence</h2>
 *
 * <p>Assumptions: no transaction boundary is declared here. The reference commits the card update
 * exactly once, at line 470 of {@code app/cbl/COCRDUPC.cbl}, and under the same verb-mapping rule that
 * governs the browse verbs a commit point becomes a transactional boundary in
 * {@code com.carddemo.card.service}, one layer up. Two consequences follow for anyone extending this
 * package: a method here participates in whatever transaction its caller opened and never opens one of
 * its own, so a unit of work spanning two methods stays a single commit instead of becoming two; and a
 * rollback is reached by letting an exception propagate rather than by any call, because the
 * reference's rollback is likewise a control transfer and not a data-access operation.</p>
 *
 * <p>Assumptions: no status code and no error body is constructed here. The {@code Card} entity
 * carries a version column, so a concurrent edit surfaces as an optimistic-locking failure, and that
 * failure is rendered as an HTTP 409 conflict by the single advice in
 * {@code com.carddemo.common.error}. Assumptions: that advice reaches this service through the shared
 * kernel's auto-configuration rather than through any declaration in this module, which is why no
 * registration for it appears anywhere in this bounded context and none should be added. A translation
 * written here as well would be a second answer to the same failure, and which of the two a client
 * received would depend on call order rather than on anything a reader could see.</p>
 *
 * <p>Refactoring Rationale: the page envelope is imported from {@code com.carddemo.common.web} and is
 * never re-declared in this package. A per-service copy would let two contexts' notions of a page
 * cursor drift apart without either side changing, and nothing in any build would compare them. The
 * reference establishes the same discipline for its own record layouts, resolving every one of them
 * through a single compiler include path -- the repository states it at
 * {@code tests/README.md:540-542} as never duplicating a layout and keeping it single-sourced -- and
 * an imported envelope is the direct analogue of that one include path.</p>
 *
 * <p>Assumptions: no other bounded context's data is reachable from here. There is no {@code Account},
 * {@code Customer} or {@code CardXref} type in this package, and the absence traces to the reference
 * rather than to a target simplification: {@code app/cbl/COCRDSLC.cbl} and
 * {@code app/cbl/COCRDUPC.cbl} each include the customer layout, at lines 240 and 359 respectively,
 * yet neither program references a single field of it, and each additionally carries the account and
 * cross-reference layouts commented out, at lines 231 and 237 and at lines 350 and 356. An inert
 * include confers no ownership. Independently of the reference, the architecture rules authored in
 * {@code com.carddemo.common.architecture} make a cross-context domain dependency a failing test
 * rather than a review comment.</p>
 *
 * <p>Assumptions: no money type appears here at all, in any method signature or return type. That is
 * a property of the record rather than a restraint being exercised: the whole hundred-and-fifty-byte
 * card record is declared at lines 4 to 11 of {@code app/cpy/CVACT02Y.cpy} as a card number, an
 * account identifier, a verification value, an embossed name, an expiration date, an active-status
 * flag and padding, and not one of the seven is an amount. Stating the absence is more useful than
 * asserting the project's exact-decimal money discipline, which is real but does not bite on this
 * table, and a reader who found it asserted here would go looking for a money path this context does
 * not have.</p>
 *
 * <h2>Platform-capability differences between the two data stores</h2>
 *
 * <p>Assumptions: several properties the target relies on are supplied by the store rather than by
 * either codebase, and they are recorded as capability differences rather than as anything wanting in
 * the reference. {@code app/csd/CARDDEMO.CSD} defines both card files with
 * {@code READINTEG(UNCOMMITTED)} at lines 15 and 27, {@code STRINGS(1)} at lines 16 and 28,
 * {@code JOURNAL(NO)} at lines 19 and 31 and {@code RECOVERY(NONE)} at lines 21 and 33. The target
 * reads at the read-committed isolation level and draws from a connection pool, both of which are
 * settings in this module's {@code application.yml} rather than anything a query here arranges, and the
 * migration plan assigns write-ahead logging, automated backup and encryption at rest to the managed
 * store beneath them. A non-recoverable, single-string data set simply has nowhere to keep a log or a
 * second concurrent position, so the reference programs are written against what their store offers and
 * the queries here are written against what this one offers. The two remain in service side by side,
 * and neither reading is the correct one for the other's store.</p>
 *
 * <h2>Where the authority for each contract lives</h2>
 *
 * <p>Assumptions: the authoritative names of every column and every index are in the Flyway migration
 * at {@code services/card-service/src/main/resources/db/migration/V1__card.sql}, and not in this
 * charter. That file creates the table and the secondary index and settles their spelling, widths,
 * nullability and constraints. The reason to consult it rather than this file is specific: a query
 * naming a column the migration does not create is accepted by the Java compiler without complaint
 * and fails only when the statement is first executed, so a disagreement between the two has no
 * compile-time signal and surfaces as a runtime error against a live database.</p>
 *
 * <p>Assumptions: no recorded-output comparison against mainframe behaviour is available for the three
 * card screens whose access paths this package carries. The repository records at
 * {@code tests/README.md:83-85} that the online programs cannot be run end to end without a CICS
 * runtime, which is absent on the runner, and that only their extractable field-validation logic is
 * unit-tested. The one batch program on this file, {@code app/cbl/CBACT02C.cbl}, does run, and its
 * read path is a sequential open at line 118, a read-next at line 92 and a close at line 136 -- which
 * is a useful reference for record framing but exercises neither the keyset cursor nor the by-account
 * path. Correctness here therefore rests on this module's own tests under
 * {@code services/card-service/src/test/java}, and no claim of recorded-output parity should be made
 * for these paths.</p>
 *
 * <p>Three further pointers complete the picture for a reader arriving at this package. The migration
 * named above is the only place the persistent shape is declared. {@code LayeringRulesTest}, in
 * {@code com.carddemo.common.architecture}, is the sole owner of the layering boundaries this charter
 * describes, so a boundary is enforced by that test and is deliberately not re-encoded in any linter
 * configuration; a reader wanting to know whether a given import is permitted reads it there and
 * nowhere else. And three sibling packages build directly on this one: {@code service} holds the
 * transcribed rules and the transactional boundaries, {@code mapper} converts a row-shaped object into
 * a served shape, and {@code api} binds that shape to HTTP.</p>
 *
 * <p>Assumptions: this is one of the eight charters this bounded context's main source tree carries at
 * target -- one at the context root and one in each of the seven subpackages {@code api},
 * {@code service}, {@code repository}, {@code domain}, {@code dto}, {@code mapper} and
 * {@code config}. The shared kernel carries nine, because it has eight subpackages beside its own
 * root, and that nine must not be read across to here. Both counts are recorded together because a
 * reader who assumes the two modules are symmetrical will look for a ninth charter in this context and
 * conclude one is missing. None belongs in the {@code com} or {@code com/carddemo} directories above
 * this one either, because the file-set check that requires these charters audits only a directory
 * holding a processed source file, and those two hold nothing but further directories.</p>
 */
package com.carddemo.card.repository;
