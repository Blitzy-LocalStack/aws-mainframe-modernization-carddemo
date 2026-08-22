/**
 * JPA keyed operations, exactly four keyset browse queries in two directions and
 * one alternate-key lookup. No offset paging.
 *
 * <h2>Target contract, not a directory listing</h2>
 *
 * <p>Assumptions: every inventory, file name, class name and count in this charter states the
 * package's <b>target contract</b> as the migration plan assigns it. It is a specification of what
 * this package owns and of what it may never hold, so it is read against the plan rather than against
 * a listing of the directory beside it.</p>
 *
 * <p>Alternatives Considered: deriving the inventory from the directory instead of from the plan.
 * Rejected, because a charter that describes whatever happens to be present cannot say what may
 * <em>not</em> be added, and that is the half of a package contract a reader cannot reconstruct from
 * the files. Stating the closed set costs a charter that has to be revised when the contract itself
 * changes, and buys a boundary a reviewer can enforce against a proposed addition.</p>
 *
 * <p>Purpose: this package is the persistence boundary of the auth bounded
 * context. It declares two Spring Data types. The first, {@code UserRepository},
 * reaches the {@code auth.users} table in three ways and in no fourth way.
 * The first is keyed access through the primary key: find by identifier,
 * existence check, save and delete. The second is keyset browsing: exactly four
 * queries -- two that OPEN a browse, one bounded only by row count and one
 * positioned at or after a supplied identifier, and two that CONTINUE one, one
 * reading forward from a key and one reading backward from a key. The third is a
 * single lookup by the table's one alternate key, {@code cognito_sub}, returning
 * at most one row. The exclusions are as much the charter as the inclusions are,
 * because each of them is load bearing rather than an oversight: no offset
 * pagination of any kind, no page number, no total count, and no third browse
 * DIRECTION.</p>
 *
 * <p>The second type is {@code IdentitySyncTaskRepository}, over
 * {@code auth.identity_sync_task}, and it reaches that table in exactly two ways
 * beyond the keyed operations its base interface supplies: the pending entries for
 * one named user in ledger order, and the oldest pending entries across all users
 * in the same order. Both take an explicit row limit and neither takes an offset.
 * Refactoring Rationale: this type was added to the charter rather than smuggled in
 * beside it, following the ruling above, because the table it serves has no baseline
 * counterpart at all -- it exists because the migrated context writes to two stores
 * where the baseline wrote to one, and the provider is not a transaction participant.
 * Assumptions: the second read is deliberately NOT filtered by user, because it
 * serves the reconciliation pass, whose whole purpose is to find work no request is
 * going to come back for. Trade-offs: an unfiltered read of a work table is the shape
 * that most easily becomes unbounded, so the limit is a required parameter rather than
 * a default -- a caller cannot ask for the whole table by omitting it.</p>
 *
 * <p>Refactoring Rationale: the second way was closed at two queries and has been
 * widened twice, each time by revising this charter rather than by adding a member
 * against it -- the process the ruling above prescribes. The first widening added
 * the unbounded opening read, because a browse has to start somewhere: both
 * positioned queries take a key, and the first page has none, so opening the
 * browse was expressible only by passing a value chosen to sort below every key.
 * That works by way of a collation detail rather than by intent -- the column is
 * {@code CHAR(8)} and trailing blanks are ignored in its comparison, so an empty
 * string and a string of blanks compare equal and a blank identifier would be
 * dropped from the first page while appearing on later ones. A query with no lower
 * bound states the intent and cannot be wrong about it.</p>
 *
 * <p>⚠️ Refactoring Rationale: the second widening added the POSITIONED opening
 * read, and what made it necessary is a defect rather than a preference. The
 * baseline's browse takes an identifier typed into a search field and seeks on it
 * -- {@code app/cbl/COUSR00C.cbl} lines 218 to 221 move a typed value into
 * {@code SEC-USR-ID} and {@code LOW-VALUES} when the field is blank -- so the
 * ENTER turn has two branches and this boundary had a query for only one of them.
 * The browser client compensated by filtering the page it had already been
 * handed, which returned nothing at all whenever the identifier sorted beyond that
 * page, and the service could not have served it with either positioned query: the
 * forward one compares STRICTLY, so it would have hidden the very row whose
 * identifier was typed, and the baseline shows that row first because its priming
 * read is skipped on the ENTER turn (the guard at line 288). Hence a fourth member
 * whose comparison is inclusive.</p>
 *
 * <p>Assumptions: neither widening adds a DIRECTION, which is why that exclusion
 * is unchanged and is capitalised to mark the distinction: four queries, two
 * directions. Three of the four read ascending -- both opening reads and the
 * forward continuation -- and one reads descending, so a reader counting orderings
 * finds two where a reader counting members finds four.</p>
 *
 * <p>Refactoring Rationale: this charter previously closed the set at two ways
 * and named no alternate-key access, and the third way is admitted by revising
 * it rather than by adding a member against it -- which is the process the
 * ruling above prescribes for exactly this case. What made the revision
 * necessary is that the target's identity design reaches a row by the subject a
 * validated token carries, and no primary-key or keyset member can express that:
 * a token names a subject, not an eight-character identifier. Admitting it as a
 * NAMED third way rather than widening the charter to "keyed access generally"
 * keeps the boundary enforceable -- one column, at most one row, no predicate
 * beyond equality -- so a proposed fourth WAY of reaching the table is still
 * something a reviewer can refuse.</p>
 *
 * <p>Assumptions: the third way is bounded by the schema and not merely by
 * intention. {@code cognito_sub} is declared {@code NOT NULL UNIQUE} in
 * V1__auth.sql, so the lookup is total and one-to-one and its return type is
 * single-valued as a consequence of the constraint. Were the column ever made
 * nullable or non-unique, this way of reaching the table would stop being sound,
 * which is why the constraint is cited here and not only in the entity.</p>
 *
 * <p>The neighbouring packages are shaped by that charter.
 * {@code com.carddemo.auth.service} composes these operations into the
 * behaviour transcribed from the baseline programs, and the four browse queries
 * fill {@code com.carddemo.common.web.PageResponse}, whose FOUR components --
 * items, first key, last key and has-next -- are what a caller navigates by. A
 * caller therefore never supplies a page number, because nothing on this boundary
 * accepts one. Assumptions: forward availability is settled by the service layer
 * from the surplus row, not inferred from a boundary key being present; the
 * surplus row is what every one of this package's browse queries returns by
 * asking for one row more than the page.</p>
 *
 * <p>⚠️ Refactoring Rationale: this paragraph used to name a has-previous member
 * as well, and no such member exists -- the envelope declares exactly the four
 * components listed above. Backward availability is deliberately absent from it
 * rather than missing: the reference answers the question from the terminal side,
 * testing {@code CDEMO-CU00-PAGE-NUM > 1} at app/cbl/COUSR00C.cbl line 247 and
 * refusing at line 251 without reading the file at all, and that ordinal lived in
 * the communication area the screen carried between turns, so its migrated home
 * is the client's own navigation state. This package therefore reads a surplus row
 * in both directions for the sake of TRIMMING to the page size, but only the
 * forward walk's surplus reaches a caller as an availability answer.</p>
 *
 * <h2>Design decisions</h2>
 *
 * <p>Refactoring Rationale: the browse the baseline performs cannot be carried
 * across unchanged, and what has to change is where the position is kept. In
 * app/cbl/COUSR00C.cbl the position lives in a CICS cursor opened against the
 * {@code USRSEC} file and in the identifier pair the program hands back to
 * itself between screen turns, declared at 67-73, so it survives only for as
 * long as a task and its cursor survive. A stateless request handler has
 * neither. AAP transformation rule T5 resolves this by mapping the file verbs
 * by category, collapsing a positioned browse onto a keyset-paginated query, so
 * the position travels in the request as a key instead of being remembered
 * between requests. Four verbs become four queries in two directions.
 * {@code STARTBR} at 588-595 is the SEEK, and its counterpart here is one opening
 * query per value the program seeks on -- {@code LOW-VALUES} for a blank search
 * field and the typed identifier otherwise, the two branches at 218-221.
 * {@code ENDBR} at 689-691 releases the cursor and has no counterpart at all,
 * because a query carries its own predicate and closes its own result set.
 * {@code READNEXT} at 621-629 and {@code READPREV} at 655-663 become the two
 * positioned continuations, one per direction.</p>
 *
 * <p>⚠️ Refactoring Rationale: {@code STARTBR} was recorded here as having no
 * counterpart, alongside {@code ENDBR}, and that reading is what left the
 * positioned opening read unbuilt: it treats the seek as cursor bookkeeping when
 * the value it seeks ON is the operator's own input. The two verbs are not alike
 * -- one carries a position and the other carries nothing -- so they are now
 * accounted for separately.</p>
 *
 * <p>Assumptions: TWO is transcribed from the source as a count of DIRECTIONS
 * rather than of queries. The same program drives all four verbs from exactly two
 * paragraphs, {@code PROCESS-PAGE-FORWARD} at 282 and
 * {@code PROCESS-PAGE-BACKWARD} at 336, reached in turn from
 * {@code PROCESS-PF7-KEY} at 237 and {@code PROCESS-PF8-KEY} at 260. Two
 * directions in the baseline is two directions here, so a query reading a third
 * way would describe a movement the source does not offer.</p>
 *
 * <p>Assumptions: the key every one of the four browse queries pages by is
 * {@code SEC-USR-ID}, the eight-character field at offset zero of the 80-byte
 * {@code SEC-USER-DATA} layout declared at app/cpy/CSUSR01Y.cpy:17-23. It is the
 * whole key rather than the leading part of a compound one, which is what makes a
 * single-column keyset predicate sufficient here: each of the three positioned
 * verbs above passes that same field as both its {@code RIDFLD} and its
 * {@code KEYLENGTH}, so ordering by one column reproduces the sequence the
 * baseline reads in.</p>
 *
 * <p>Assumptions: a page holds ten rows. app/cbl/COUSR00C.cbl:56-57 declares
 * the screen array as {@code USER-REC OCCURS 10 TIMES}, and the forward
 * paragraph fills it under an index bounded at eleven at 300-306, so ten is a
 * property of the source and not a default chosen on this side.</p>
 *
 * <p>Alternatives Considered: offset pagination was rejected, and with it the
 * Spring Data {@code Pageable}, {@code Page} and {@code Slice} types that
 * express it. An offset query locates its starting row by counting from the
 * beginning of the ordering on every request, so an insert or a delete landing
 * ahead of that point between two requests shifts every later row by one
 * position, and the reader then either skips a row it has never seen or
 * receives one it has already seen. Keyset paging resumes from the key it last
 * returned, so it is not exposed to that. The baseline is already keyed in
 * precisely this way, which makes keyset paging the option that preserves the
 * page boundary the source produces, and offset paging the option that changes
 * it.</p>
 *
 * <p>Alternatives Considered: a total count query was rejected for a separate
 * reason, which is that nothing in the source computes one. Having filled the
 * ten screen rows, app/cbl/COUSR00C.cbl reads one further row at 311 purely as
 * a probe and sets its next-page indicator from whether that read succeeded, at
 * 313 and 315. Fetching a single row beyond the page is exactly what the
 * has-next member of the page envelope reports, so counting the table would
 * answer a question no screen asks and would add a second scan to every page
 * turn.</p>
 *
 * <p>Assumptions: no password member is persisted here or queried by. The
 * baseline layout carries {@code SEC-USR-PWD} at app/cpy/CSUSR01Y.cpy:21 and
 * sign-on compares it directly; the target encodes identity verification
 * against the managed user pool instead and keeps no password column on
 * {@code auth.users}, so no query here selects it, filters on it or returns it.
 * The baseline tree stays reference material and keeps its own behaviour, and
 * the divergence is documented in AAP section 0.7.8.</p>
 *
 * <p>Assumptions: no commit boundary is declared in this package. The baseline
 * file definition at app/csd/CARDDEMO.CSD:88-96 enables browsing while setting
 * {@code JOURNAL(NO)} at 94 and {@code RECOVERY(NONE)} at 96, and none of the
 * five auth programs issues a {@code SYNCPOINT} at all, so the source has no
 * commit or back-out point around a browse to reproduce. Demarcation belongs to
 * the service layer above, and placing it here would introduce a boundary the
 * source does not have.</p>
 *
 * <p>Assumptions: the {@code auth} schema and its role grants exist before
 * anything in this package runs. data-migration/sql/V0__schemas_and_roles.sql
 * creates them and this module's own Flyway migration creates the table, so
 * nothing here creates or alters a schema object.</p>
 *
 * <p>Assumptions: what this boundary does is held to the baseline by
 * transcription against the program cited throughout and by this module's own
 * tests, not by a comparison against captured output. tests/README.md:83-85
 * states that the baseline's online programs cannot be exercised end to end
 * without a CICS runtime, and every program this package serves is one of them,
 * so no such comparison exists for the user list screen and none should be
 * claimed for it. The page size, the key ordering, the two directions, the
 * inclusive opening position and the single probe read are all readable straight
 * from the source, and those are the properties asserted exactly.</p>
 *
 * <p>Trade-offs: keyset paging gives up random access to an arbitrary page. A
 * caller can step to the next page or back to the previous one but cannot jump
 * to the fiftieth, and nothing here accepts an argument that would let it. That
 * cost is accepted because the baseline screen offers the same two movements
 * and no jump either, so nothing available to a user of the source is
 * withdrawn.</p>
 *
 * <p>Trade-offs: the three docstring elements Rule 1 (Explainability) names at
 * clauses L19 to L21 are deliberately absent rather than filled in. A package
 * declaration accepts no parameter, returns no value and raises nothing, so no
 * at-clause in this file could carry a true description, and an empty
 * parameter, return or exception tag added to look complete would assert
 * something false and would in any case be reported by the
 * NonEmptyAtclauseDescription check. The purpose required at clause L18 and the
 * rationale required at clause L40 are both stated above, which is what the
 * gate at clause L43 asks of a file that declares no member. The prose
 * convention is docs/CODE_DOCUMENTATION_STANDARD.md; the mechanical gate is
 * config/checkstyle/checkstyle.xml, where JavadocPackage requires this file to
 * exist and MissingJavadocPackage requires it to carry this comment. Those two
 * checks sit at different levels, the first at Checker level because it
 * inspects the directory and the second inside TreeWalker because it inspects
 * this comment, so dropping either half would leave the module entry point
 * clause at L15 only half enforced.</p>
 */
package com.carddemo.auth.repository;
