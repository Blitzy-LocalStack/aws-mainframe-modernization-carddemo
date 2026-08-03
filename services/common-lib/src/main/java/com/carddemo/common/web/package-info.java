/**
 * Owns the two request-scoped web contracts of the CardDemo shared kernel: the
 * correlation identity that travels with every request, and the keyset page
 * envelope that carries a browse cursor across a stateless boundary.
 *
 * <p><b>Purpose.</b> Two concerns live here and nothing else does. A
 * correlation identity has to accompany a request from the edge through to the
 * log line, so that one unit of work can be reassembled afterwards out of
 * evidence scattered across services. A list response has to state where its
 * page begins, where it ends, and whether a further page follows, so that a
 * caller can move forward and backward through an ordered result set without
 * ever counting rows from the start of it. Both concerns cut across every
 * bounded context: each of the eight service modules answers requests, and most
 * of them answer list requests, so neither concern belongs to any one module.
 * Both are therefore declared once here and consumed everywhere.
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> None of the three
 * applies. A package declaration accepts no argument, yields no value and
 * raises nothing, so this charter carries no parameter, return or exception
 * at-clause. The inapplicability is declared rather than left silent, because
 * the Explainability rule lists a docstring that omits parameters or return
 * values among its forbidden patterns at line 39, and a reader has to be able
 * to tell a declared inapplicability from an oversight. Fabricating those
 * at-clauses would be worse than noise: Javadoc has no parameter, return or
 * exception concept for a package, and the ruleset audits at-clause bodies for
 * emptiness, so an invented tag would be either discarded or flagged. The rule
 * enumerates four docstring elements at its lines 18 to 21, and for this
 * compilation unit exactly one of the four, Purpose, is applicable; the
 * paragraph you are reading accounts for the other three.
 *
 * <h2>What this package holds, and what it deliberately does not</h2>
 *
 * <p>Two production types sit beside this charter, and there is no third:
 *
 * <ul>
 *   <li>{@code PageResponse} -- the keyset page envelope. It carries the rows
 *       of one page, the key of the first row, the key of the last row, and
 *       whether a further page exists.</li>
 *   <li>{@code CorrelationIdFilter} -- correlation identity in, logging context
 *       and response header out. It accepts an identity supplied on the inbound
 *       request, mints one when none was supplied, publishes it to the logging
 *       context for the life of the request, and echoes it on the
 *       response.</li>
 * </ul>
 *
 * <p>Three neighbouring concerns are pointedly absent. Exact decimal money and
 * its wire form belong to {@code com.carddemo.common.money}; the copybook
 * record codecs belong to {@code com.carddemo.common.codec}; the problem shape
 * and the structured abend detail belong to {@code com.carddemo.common.error}.
 * A page envelope carries whatever rows a caller puts into it and never
 * interprets one of their fields, so this package needs none of the three and
 * imports none of them. That is a deliberate boundary rather than an accident
 * of what has been written so far: the moment an envelope knows what a money
 * column is, every service returning a page of anything else inherits a
 * dependency it has no use for.
 *
 * <p>Assumptions: {@code CorrelationIdFilter} is registered by the consuming
 * service, not by anything shipped from here. This module is a library and not
 * a deployable -- it has no application entry point, no runtime configuration
 * resource, no schema migration, no API description document and no container
 * image of its own -- so a service wires the filter into its own chain, and
 * this package supplies only the behaviour being wired.
 *
 * <h2>Lineage: four reference-only programs, only two of which browse</h2>
 *
 * <p>The page envelope is not a new idea. It is the reference baseline's own
 * browse state, written down as a type. Four programs supply the lineage, and
 * one distinction among them is worth stating before any detail: two are
 * genuine browse screens, a third is routinely taken for one and is not, and
 * the fourth carries the correlation discipline rather than the paging
 * discipline. Every program named below is reference-only. It is cited by path
 * and line, it is never modified, and where the migrated behaviour differs the
 * framing is always that the baseline does one thing, this code implements
 * another, and the divergence is documented.
 *
 * <h3>Browse screen one: {@code app/cbl/COCRDLIC.cbl}, seven rows to a page</h3>
 *
 * <p>The card list keeps its whole browse state in the communication area it
 * echoes between screen turns, declared from line 229. Lines 230 to 232 hold
 * the last key of the page as a card number of sixteen characters followed by
 * an account identifier of eleven digits; lines 233 to 235 hold the first key
 * of the page in the same shape. Line 242 carries a one-character indicator
 * whose two condition names, at lines 243 and 244, say whether a further page
 * exists. The row count is settled twice over: line 177 declares a maximum of
 * seven screen lines, and the comment at line 250 records the display array as
 * 28 characters by 7 rows totalling 196, which agrees with the three row fields
 * at lines 258 to 260 summing to exactly 28. The browse runs the full sequence
 * -- start at line 1129, read forward at line 1146, the lookahead read at line
 * 1197, end at line 1258, then start backward at line 1273 with a priming read
 * at line 1294, the backward loop at line 1322, and end at line 1376.
 *
 * <p>Assumptions: the cursor and the display row are two different layouts in
 * two different field orders, and conflating them would corrupt every page
 * boundary. The cursor at lines 230 to 232 is card number then account
 * identifier, twenty-seven characters, which is the physical key order of the
 * card file. The display row at lines 258 to 260 is account number, then card
 * number, then card status: twenty-eight characters, in a reading order meant
 * for a person. The twenty-eighth character is the card status. It is not a
 * row-selection marker, and reading it as one would put a status byte into a
 * cursor. This package therefore preserves the cursor order and takes no
 * interest in the display order at all.
 *
 * <p>Assumptions: one condition pair in that block reads against intuition, and
 * it is quoted here so that nobody restores what looks like a transcription
 * error. Line 239 declares a one-digit last-page-displayed field whose
 * condition names at lines 240 and 241 give shown the value 0 and not-shown the
 * value 9, so the truthy state is the lower value. This package does not carry
 * that field in any form: the envelope states positively whether a further page
 * exists, and derives nothing from a page ordinal. The polarity is recorded
 * because a reader comparing the two representations would otherwise assume the
 * baseline value had been inverted on the way across.
 *
 * <h3>Browse screen two: {@code app/cbl/COTRN00C.cbl}, ten rows to a page</h3>
 *
 * <p>The transaction list runs the same discipline over a different key, ten
 * rows to a page. The forward loop at line 297 runs until its index reaches
 * eleven; the backward loop starts that index at ten on line 349 and runs on
 * line 351 until it falls to zero. Each browse verb is its own paragraph --
 * start at line 591, with the record identification field at line 595 and the
 * key length at line 596; read forward at line 624; read backward at line 658;
 * end at line 692. Line 597 is the notable one: the greater-or-equal option is
 * present in the source and commented out.
 *
 * <p>Assumptions: the two browse screens do not share a key shape, which is why
 * the envelope has to be generic in its key rather than hold a card-and-account
 * pair. This screen's cursor is a single scalar transaction identifier, carried
 * in the record identification field at line 595, where the card list's is the
 * twenty-seven-character composite described above. A type that hard-coded
 * either shape would fit one screen and fail the other, and a type that
 * hard-coded both would carry a field that is meaningless on each.
 *
 * <p>Alternatives Considered: the page counter at lines 306 and 307, adjusted
 * again at lines 361 to 367, was evaluated as a model for the envelope and
 * rejected. It is a display quantity and nothing more -- the screen shows it to
 * the user, and no read is ever positioned by it. It is called out because it
 * is the one place in either browse screen where an ordinal appears at all, so
 * it is the one thing here that could be mistaken for the paging mechanism when
 * the mechanism is entirely key-driven.
 *
 * <h3>The counter-example: {@code app/cbl/COBIL00C.cbl} does not browse</h3>
 *
 * <p>Bill payment is named in the migration plan alongside the two screens
 * above as a third paging source. It is not one. This charter records the
 * distinction so that the envelope is never wired into that path. Lines 210 to
 * 219 are a highest-key lookup followed by an increment: high values are moved
 * into the transaction identifier at line 212, a browse is started at line 213,
 * a single backward read at line 214 lands on the highest existing key, the
 * browse ends at line 215, and lines 216 and 217 move that key into a working
 * field and add one to it. The relational equivalent is a maximum over the key
 * column, not a page. The start at line 441 carries no greater-or-equal option
 * at all; the end-of-file path at line 488 moves zeros into the identifier as
 * an empty sentinel; the browse closes at lines 501 to 505. And decisively, in
 * all 572 lines of that program there is no forward-read paragraph whatsoever.
 *
 * <p>Alternatives Considered: those same lines are the specific reason this
 * package expresses a page as a range of keys rather than as an ordinal
 * position within a result set. Lines 212 to 217 read the highest key and
 * increment it without holding a lock across the two steps, which means two
 * units of work can occupy that sequence at once, and inserts therefore land
 * against the transaction file while other readers are part-way through it.
 * Under concurrent insertion, positioning a page by counting rows from the
 * start of the ordered set loses some rows and presents others twice, because
 * the number of rows preceding the cursor changes underneath the reader between
 * one turn and the next. Positioning it by key does not: a key that has already
 * been read keeps its place in the ordering no matter what is inserted around
 * it. The baseline never had the weaker behaviour available to it, because it
 * only ever positions by key. This is a named mechanism in a cited program
 * rather than a general preference, which is what the rule's line 41 requires
 * of a rationale.
 *
 * <h3>The correlation discipline:
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}</h3>
 *
 * <p>The message-queue consumer settles both the width and the round-trip. Line
 * 45 declares the saved correlation identity as twenty-four characters, and that
 * is where the width used here comes from. Lines 411 to 414 save two things off
 * the inbound message: the correlation identity, and the queue to reply to, so
 * the reply destination is chosen per message rather than configured once. Line
 * 745 echoes the saved identity back onto the reply verbatim, while line 746
 * lets the reply take a new message identity of its own. That pairing is exactly
 * the discipline this package reproduces over HTTP: the identity of the
 * conversation is preserved across the hop, the identity of the individual
 * message is not. Line 758 issues the reply as a single open-put-close call,
 * which is what makes a per-message reply destination workable at all.
 *
 * <p>Assumptions: only three widths in that program are assertable, and this
 * package uses one of them. Twenty characters is the baseline's own event key,
 * at line 40 of
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy}. Twenty-four
 * characters is the correlation identity, at line 45 of the program.
 * Forty-eight characters is the width of the two queue-name fields at lines 43
 * and 44, and of nothing else. A forty-eight-character message identity is
 * sometimes quoted alongside these, and it is not assertable here at all: the
 * message-descriptor layout that would declare it comes from a vendor-supplied
 * copybook the program includes at lines 152 and 158, and no copybook of that
 * family is present anywhere in this repository. The twenty-four-character
 * figure is citable precisely because the program declares it in its own
 * storage instead of inheriting it from an absent include.
 *
 * <p>Refactoring Rationale: correlation is not invented by this package. It is
 * taken over from application code that already had the concept and could
 * already forget to use it. Line 40 of {@code CCPAUERY.cpy} is the last field of
 * an eleven-field, 122-byte structured error-log record, and it is the
 * baseline's own correlation key -- carried in a record that a program has to
 * remember to populate. What happens when nothing enforces that is visible in
 * the transaction list: at lines 613, 647 and 681 of
 * {@code app/cbl/COTRN00C.cbl} a response code and a reason code are written
 * straight out beside a message, with no correlation key at all, so three
 * distinct failure paths in one program cannot be tied back to the requests that
 * provoked them. Moving the key out of an application field and into a filter is
 * the whole of the change: a filter runs on every request by construction, so no
 * handler can omit it, and one identity then reaches the log line and the caller
 * alike.
 *
 * <p>Assumptions: a correlation identity is an opaque request label and carries
 * nothing else. It is never derived from a credential and never carries one, and
 * it never carries an account number or a card number, so a log line that quotes
 * it discloses nothing about the subject of the request it labels.
 *
 * <p>Trade-offs: this charter takes the correlation width from that program and
 * deliberately takes nothing else from it, even though two timing values sit a
 * few lines away and would be easy to lift. Line 242 sets a wait of 5000 in
 * milliseconds; line 750 sets an expiry of 50 in tenths of a second. Both
 * express five seconds, in units that differ by a factor of one hundred, and
 * neither carries a comment saying so. The cost accepted is that a reader
 * looking for a request deadline will not find one here. The gain is that this
 * package holds no timing value it could get wrong, because a deadline is a
 * transport concern belonging to the service that configures it and not to the
 * identity a filter propagates.
 *
 * <h2>The three contracts this package owns</h2>
 *
 * <p>Everything above reduces to three commitments, stated plainly so that they
 * can be checked against the two production types rather than inferred from
 * them:
 *
 * <ol>
 *   <li><b>Cursor field order.</b> Where a cursor is composite it is ordered as
 *       the underlying physical key is ordered -- card number before account
 *       identifier, per lines 230 to 232 of {@code app/cbl/COCRDLIC.cbl} -- and
 *       never in the order some screen happened to display.</li>
 *   <li><b>How a further page is discovered.</b> By reading one row beyond the
 *       page and letting the presence of that row set the indicator, exactly as
 *       the baseline does at line 1197 of {@code app/cbl/COCRDLIC.cbl} and again
 *       at lines 305 to 313 of {@code app/cbl/COTRN00C.cbl}. Two independent
 *       programs attest to it, which is why it is treated as a contract and not
 *       as one program's habit.</li>
 *   <li><b>Correlation width.</b> Twenty-four characters, per line 45 of
 *       {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}.</li>
 * </ol>
 *
 * <h2>The count canon</h2>
 *
 * <p>The shared kernel holds <b>17 production classes</b> and <b>9</b> package
 * charters -- one at the kernel root and one for each subpackage -- for <b>26</b>
 * compilation units in total. This package contributes two of the seventeen and
 * one of the nine. The breakdown is given so that a reader can re-derive the
 * total instead of trusting it:
 *
 * <pre>
 * subpackage        production classes
 * money                              2
 * codec                              5
 * error                              3
 * web                                2
 * security                           1
 * observability                      1
 * time                               1
 * validation                         2
 * </pre>
 *
 * <p>Those eight sum to 17, the kernel root itself contributing none; adding the
 * nine charters gives 26.
 *
 * <p>Assumptions: an earlier draft of the migration plan puts the
 * production-class total one higher than 17. That figure is superseded and must
 * not be propagated. The canon above is stated as a breakdown and not merely as
 * a total for exactly that reason: a bare total invites a reader to trust it,
 * whereas a per-subpackage list can be re-derived, and a stale figure caught the
 * way the superseded one was caught.
 *
 * <h2>Why these two types live in the shared kernel</h2>
 *
 * <p>Refactoring Rationale: the alternative is for each service to declare its
 * own page envelope and its own correlation filter. It deserves a precise answer
 * rather than a dismissal, because on the surface it removes a module from the
 * build and lets one team change an envelope without consulting seven others.
 *
 * <p>The reference baseline resolves every record layout through a single
 * compiler include path. {@code tests/README.md} line 268 records the compile
 * invocation, whose trailing {@code -I app/cpy} is that include path, and lines
 * 540 to 542 of the same file state the discipline it buys: "COBOL unit tests
 * resolve record layouts through the compiler copybook path
 * ({@code cobc -I app/cpy}) via {@code COPY CVTRA06Y.} / {@code COPY CVACT01Y.}
 * -- never duplicate a layout; keep it single-sourced from {@code app/cpy/}."
 * The analogy is exact, and it is the reason this module exists at all:
 *
 * <pre>
 * COBOL   one include path over app/cpy       :  COPY CVACT01Y.
 * Java    one dependency on the shared kernel :  import com.carddemo.common.web.PageResponse;
 * </pre>
 *
 * <p>What is wrong with the per-service alternative is the same thing that is
 * wrong with eight copies of a record layout, and it is not a matter of taste.
 * Two services that each declare their own envelope will eventually disagree
 * about it: one treats the last key as the last row returned, the other as the
 * first row of the next page; one discovers a further page by reading one row
 * beyond, the other by comparing against a tally it keeps itself. Neither
 * disagreement is a compilation error. Both yield pages that look right and
 * quietly lose or repeat a row at the boundary, which is precisely the class of
 * defect the baseline's single include path exists to prevent. A cursor semantic
 * is either shared exactly or it is not shared at all.
 *
 * <p>Assumptions: single-sourcing is safe to rely on only because what it points
 * at does not move. The 30 copybooks of {@code app/cpy} are reference-only and
 * are never modified, by this migration or by anything else, so a contract
 * derived from them can be written once here and then left alone.
 *
 * <p>Trade-offs: only the include-path portion of that compile invocation is
 * quoted above. The other switches on {@code tests/README.md} line 268 select a
 * source format and a sign convention, and the analogy rests on neither, so they
 * are described instead of transcribed. The cost is that the quotation is
 * partial and a reader wanting the whole command must open the cited line. The
 * gain is that this charter contains no token it would then have to explain
 * away: the vocabulary this tree is audited over is kept out of it by
 * construction rather than by exception.
 *
 * <h2>Authoring conventions this charter is held to</h2>
 *
 * <p>One user-specified rule governs this migration: Explainability. Its line 15
 * requires a docstring on every module entry point; a package declaration is the
 * language's module entry point; and this file is the only place Javadoc can
 * attach to one. That is why this file exists. The ruleset reinforces the
 * requirement with a deliberate pair of checks -- one asserts that a charter
 * file exists in a package directory, the other that the file carries Javadoc --
 * so an empty charter would satisfy the first and fail the second, and both
 * halves are load-bearing.
 *
 * <p>Alternatives Considered: the four rationale labels used throughout this
 * charter, namely Alternatives Considered, Refactoring Rationale, Assumptions
 * and Trade-offs, are written in the plural, without parentheses, and with the
 * ASCII hyphen-minus. That is a deliberate divergence from the form that
 * predominates elsewhere in this repository, and it is recorded here so that a
 * later reader does not quietly normalise it back. A census taken while this
 * migration was being planned found the plural form in a handful of files, the
 * parenthesised singular in roughly two dozen, and the bare singular in some
 * eighty -- a majority of between one and two orders of magnitude against the
 * form chosen here. That tally necessarily moves as this Java tree lands, so the
 * durable fact is the direction and not the digit: the singular and the
 * parenthesised singular dominate the markup, infrastructure and shell
 * artifacts, and the plural is used here regardless. The reason is that the rule
 * states its own four categories in the plural at its lines 31 to 34, and its
 * line 43 makes that wording the sentence this work is audited against, so the
 * plural is the form that matches the audited text. The two forms mean the same
 * thing; choosing one and holding to it is what makes the convention read as one
 * convention instead of two. Forms are never mixed within a file, and this Java
 * tree uses the plural exclusively.
 *
 * <p>Assumptions: every quotation in this charter is transliterated to ASCII.
 * {@code tests/README.md} is the only file in the repository that contains the
 * non-breaking hyphen, and it contains it 106 times across 77 lines; three of
 * its lines, line 542 quoted above among them, contain no ASCII hyphen at all.
 * Its em dashes are rendered here as a pair of ASCII hyphens and its
 * non-breaking hyphens as the ASCII hyphen-minus. Wording is unchanged and only
 * those two code points are normalised. The substitution is declared so that it
 * reads as a deliberate normalisation rather than a transcription slip, and so
 * that a reader comparing this file against the source byte for byte knows which
 * differences to expect.
 *
 * <p>Assumptions: neither type in this package has an executable oracle, and no
 * claim to the contrary should be read into the lineage above. The suite beneath
 * {@code tests} is a three-layer functional-parity oracle for the batch
 * programs; it exercises no browse screen at all, because the online programs
 * need a transaction monitor its runner does not have. Both of this package's
 * types are therefore verified entirely by unit tests under
 * {@code services/common-lib/src/test/java/com/carddemo/common/web}, and the
 * programs cited throughout this charter are the specification those tests were
 * written against, not a harness they are run against. The two test trees are
 * different things and are kept textually distinct here: {@code tests} is the
 * COBOL parity oracle, and {@code services/common-lib/src/test} is this module's
 * own test source.
 *
 * <p>Trade-offs: the names of the ordinal paging parameters this package refuses
 * to carry are described in this charter and never spelled. Spelling them would
 * make this file match a search for the very tokens the tree is audited over,
 * and that hit would then have to be explained away on every audit. The
 * description is unambiguous -- a page positioned by counting rows from the
 * start of an ordered set, together with any running tally of how many rows or
 * how many pages exist altogether -- and the enforcement lives in the two types
 * and their tests rather than in this prose, because prose cannot fail a build.
 *
 * <p>Two identifier namespaces are kept textually distinct throughout, because
 * they collide by number. The user-specified rule is Explainability and is
 * referred to here only by its line numbers. The migration plan's
 * transformation rules are numbered with a leading T, and the one that governs
 * this package is T5: the four browse verbs of the reference baseline become one
 * keyset-paginated query.

 */
package com.carddemo.common.web;
