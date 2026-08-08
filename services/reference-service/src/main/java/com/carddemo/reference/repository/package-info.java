//=============================================================================
// WHY : Assumptions: this descriptor is the one place the rulings below are
//       recorded, and the seven repository interfaces beside it cite it rather
//       than restating them. The questions it settles are the ones each author
//       would otherwise answer alone -- whether a window is taken by key or by
//       ordinal, which row's key a page publishes, whether a category code is
//       character or numeric -- and seven independent answers to one question is
//       how a package acquires two behaviours.
// WHY : Alternatives Considered: restating each ruling at every interface that
//       depends on it, so a reader never has to open a second file. Rejected,
//       because the rulings are shared and a restated rule drifts: the forward
//       and backward predicates are deliberately asymmetric, and a paraphrase
//       that smoothed the asymmetry in one of seven files would read as a
//       correction rather than as the defect it is. The cost accepted is one
//       indirection at each interface.
// WHY : Assumptions: every reference to the baseline below is a PHYSICAL line
//       number, and in one cited program that is not the number printed in the
//       source's own sequence columns. The hazard is stated in full under the
//       browse rulings, because a citation a reader cannot verify is worse than
//       no citation at all.
/**
 * Data access for the reference-data bounded context: six Spring Data JPA repository interfaces over
 * the six tables of the PostgreSQL {@code reference} schema, one interface per table, together with the
 * keyset queries that replace the baseline's cursor paging.
 *
 * <h2>Purpose</h2>
 *
 * <p>Every type here reads or writes rows and does nothing else. A repository is the migrated form of
 * a file or cursor verb: where the baseline issues a keyed read, this package offers a keyed finder,
 * and where it opens a cursor and fetches through it, this package offers a bounded ordered walk.
 * Business rules live in {@code com.carddemo.reference.service}, which is the only consumer of these
 * types; the anti-corruption mapping onto transfer objects lives in
 * {@code com.carddemo.reference.mapper}, which is where a fixed-width representation concern such as
 * padding or a renamed field is allowed to appear. Neither belongs here, and the single decision left
 * to a type in this package is the one this descriptor constrains: how a row is located, and in what
 * order rows are returned.</p>
 *
 * <p>Assumptions: all six types in this package are {@code interface} declarations with no
 * implementation authored anywhere, because the persistence provider derives one at run time from the
 * method names and the query annotations. This matters for documentation rather than for behaviour:
 * {@code config/checkstyle/checkstyle.xml} lists {@code INTERFACE_DEF} first among the tokens its
 * type-documentation check inspects, so the project Explainability rule's word "class" at line 15
 * covers every type declaration here, and each interface and each of its members carries its own
 * Javadoc for that reason.</p>
 *
 * <p>On parameters, return values and exceptions: a package declaration accepts no argument, returns
 * no value and raises nothing, so this descriptor carries no parameter, return or exception
 * at-clause. The inapplicability is stated rather than left silent, because the Explainability rule
 * lists a docstring that omits its parameters or return values among its forbidden patterns at line
 * 39, and a reader has to be able to tell a declared inapplicability from an oversight. Assumptions:
 * Javadoc models no such concept for a package, and the ruleset audits at-clause bodies for
 * emptiness through {@code NonEmptyAtclauseDescription}, so an invented empty tag would be reported
 * rather than credited.</p>
 *
 * <h2>The six repositories, the entities they read, and their identities</h2>
 *
 * <p>Assumptions: all six are landed as compilation units beside this descriptor, so a reader who
 * cannot open one has found a gap rather than the expected state. The closed set is seven compilation
 * units: this descriptor and the six interfaces. The pairing below is settled here and enumerated
 * nowhere else, which is why it is written out in full rather than left to be inferred from a file
 * name.</p>
 *
 * <p>Assumptions: the mapping is exactly one interface per table, with no table carrying a second.</p>
 *
 * <p>Refactoring Rationale: this directory held NINE interfaces over these six tables. Three of them --
 * {@code PhoneAreaCodeRepository}, {@code StateRepository} and {@code StateZipPrefixRepository} -- were
 * second interfaces over the same three entities the {@code Us}-prefixed three already address, and this
 * charter described the duplication as one deliberate division of one table by question. That
 * description did not survive measurement: the duplication covered three tables rather than one, and
 * none of the three had a single consumer anywhere in the codebase, while the {@code Us}-prefixed three
 * are the ones {@code api/AddressLookupController} injects. Two interfaces over one entity is not a
 * division of labour when nothing calls either side of it; it is two derivations of the same query
 * surface that a later reader has to choose between with no basis for choosing. All three are withdrawn.
 *
 * <p>Assumptions: two methods went with them and their loss is accounted for rather than incidental.
 * {@code existsByAreaCodeAndCodeClass} answered whether a code belongs to a named classification, and
 * {@code findByAreaCode} on the surviving interface answers strictly more -- it returns the row WITH its
 * classification, which is what the published item route serves and what the account context's adapter
 * compares. {@code countByCodeClass} answered a per-classification census that no published route asks
 * for. Neither had a caller.
 *
 * <p>Assumptions: the withdrawn area-code interface additionally claimed that the account context's
 * address validation reaches this data through that interface's existence check. The claim named the
 * wrong mechanism: that context holds no repository over this schema at all, and its
 * {@code service/RestReferenceAddressLookup} reads the three published item routes over HTTP. The
 * surviving interface makes no such claim.
 *
 * <dl>
 *   <dt>{@code TransactionTypeRepository}</dt>
 *   <dd>Over {@code TransactionType}, table {@code reference.transaction_types}, identity
 *       {@code String}. Three walks and a keyed finder; the normative baseline for all paging in this
 *       package.</dd>
 *
 *   <dt>{@code TransactionCategoryRepository}</dt>
 *   <dd>Over {@code TransactionCategory}, table {@code reference.transaction_categories}, identity
 *       {@code TransactionCategory.TransactionCategoryId}, a nested {@code @Embeddable} whose
 *       components are the type code and the category code. Six walks, because the composite key
 *       admits both an unfiltered browse and one narrowed to a single type, plus a child count and a
 *       keyed finder.</dd>
 *
 *   <dt>{@code DisclosureGroupRepository}</dt>
 *   <dd>Over {@code DisclosureGroup}, table {@code reference.disclosure_groups}, identity
 *       {@code DisclosureGroup.DisclosureGroupId}, a nested {@code @Embeddable} whose components are
 *       the account group id, the transaction type code and the transaction category code. One keyed
 *       finder and no walk at all. Assumptions: that is the whole of its baseline surface. The rate
 *       lookup is a keyed read followed, on a miss, by a second keyed read of the fallback group;
 *       {@code app/cbl/CBACT04C.cbl} never browses this data, so a walk here would be a query no
 *       cited program performs.</dd>
 *
 *   <dt>{@code UsPhoneAreaCodeRepository}</dt>
 *   <dd>Over {@code UsPhoneAreaCode}, table {@code reference.us_phone_area_codes}, identity
 *       {@code String}. Six walks and a keyed finder: three over the whole table and three narrowed
 *       by the classification column, because the baseline holds one broad allow-list and two
 *       sublists of it. Assumptions: this is the ONLY interface over that table. Its keyed finder
 *       returns the row together with its classification, which is what the published item route serves
 *       and what a caller asking a membership question compares against.</dd>
 *
 *   <dt>{@code UsStateRepository}</dt>
 *   <dd>Over {@code UsState}, table {@code reference.us_states}, identity {@code String}. Three walks
 *       and a keyed finder.</dd>
 *
 *   <dt>{@code UsStateZipPrefixRepository}</dt>
 *   <dd>Over {@code UsStateZipPrefix}, table {@code reference.us_state_zip_prefixes}, identity
 *       {@code String}. Three walks and a keyed finder.</dd>
 * </dl>
 *
 * <p>Assumptions: the authoritative column contract for all six tables is this module's
 * {@code src/main/resources/db/migration/V1__reference.sql}. This descriptor names tables and
 * identities and defers to that migration for every column, type, width and constraint, rather than
 * restating them where the two could drift apart. That migration creates tables only: it contains no
 * schema creation, no role creation and no grant, because
 * {@code data-migration/sql/V0__schemas_and_roles.sql} owns the schemas and the per-service roles and
 * runs ahead of it. The schema these repositories resolve against is pinned by the JDBC search path
 * that {@code com.carddemo.reference.config} configures, so no query here qualifies a table name.</p>
 *
 * <h2>How to read a line citation in this package</h2>
 *
 * <p>Assumptions: every line number cited anywhere in this package is a PHYSICAL line number, the
 * number a line-numbering reader reports, and in the normative browse program that is <b>not</b> the
 * number printed in the source's own sequence columns. That program,
 * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}, is 2098 physical lines long, and its six-digit
 * sequence field tracks the physical line one for one only as far as physical line 1807. Four sequence
 * values are skipped inside the block that declares the record count, so from physical line 1808 the
 * printed sequence runs ahead: it gains one at a time across those four skips and then stands four
 * higher than the physical line for the remaining 284 lines, which is 291 of 2098 lines on which the
 * two numbers disagree. A reader checking the cited {@code OPEN} of the forward cursor at physical line
 * 1944 by searching for sequence {@code 194400} finds nothing, because that line carries sequence
 * {@code 194800}. Verify a citation positionally instead, with {@code sed -n '1944p'} or an equivalent,
 * and the two will agree.</p>
 *
 * <p>Alternatives Considered: citing the printed sequence numbers, which are visible in the source
 * and need no tool to read. Rejected because they are not unique across the file and are not what any
 * search tool reports, so a citation in that form is unverifiable by the means a reader actually has;
 * and because the same citation would then mean two different things either side of line 1807. The
 * cost accepted is this paragraph, which is the price of citations that resolve.</p>
 *
 * <h2>Every browse is a bounded ordered walk positioned by key</h2>
 *
 * <p>The normative browse is {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}, which declares two
 * cursors over the transaction-type table and reproduces them here as two separate queries. Their
 * predicates are deliberately <b>asymmetric</b>, and the asymmetry is transcribed rather than
 * smoothed:</p>
 *
 * <ul>
 *   <li><b>Forward</b>, {@code C-TR-TYPE-FORWARD} at physical lines 338 to 352: line 343 tests
 *       {@code TR_TYPE >= :WS-START-KEY} and line 351 orders {@code BY TR_TYPE}. The comparison is
 *       inclusive and the order ascending.</li>
 *   <li><b>Backward</b>, {@code C-TR-TYPE-BACKWARD} at physical lines 354 to 368: line 359 tests
 *       {@code TR_TYPE < :WS-START-KEY} and line 367 orders {@code BY TR_TYPE DESC}. The comparison is
 *       exclusive and the order descending.</li>
 * </ul>
 *
 * <p>Because the ordering key is the unique primary key by itself, the order is total: no two rows
 * compare equal, so no tie-break column is needed and none is added. On the composite-key table the
 * same property holds over both key columns together, which is why its walks order by the type code
 * and then the category code and compare the pair lexicographically.</p>
 *
 * <p>Assumptions: <b>the inclusive forward comparison and the exclusive forward method in this
 * package select the same rows, and the reason is worth recording because the two look
 * contradictory.</b> The baseline advances by moving its saved trailing key into the cursor's start
 * key -- physical lines 766 to 769 -- and that saved key is the key of the surplus row, not of the
 * last row displayed, for the reason given under the page-boundary ruling below. Testing
 * {@code >=} against the surplus row's key therefore selects exactly the rows that testing strictly
 * greater than the last displayed row's key selects. The asymmetry is real in the baseline's SQL and
 * is preserved in effect; what differs is only which key the envelope publishes.</p>
 *
 * <p>Assumptions: each browse offers three methods and not one -- a first page taken with no
 * position, a forward page bounded by a position, and a backward page bounded by one and ordered
 * descending. The backward walk is a separate query rather than a reversal of the forward one because
 * the row set it must bound is a different set: rows below a key and rows above it are different
 * predicates, and obtaining the rows below a key from an ascending walk would mean reading every row
 * that precedes it. The baseline expresses the same separation as two cursor declarations rather than
 * one.</p>
 *
 * <p>Refactoring Rationale: the baseline's cursors are opened and closed inside a single screen turn.
 * The forward cursor opens at physical line 1944 and closes at 1972; the backward cursor opens at
 * 1999 and closes at 2028. No cursor survives the turn, so its position has to be carried in the
 * communication area the terminal echoes back, and the program is pseudo-conversational precisely
 * because it cannot hold one. Each method here is therefore a stateless per-request query with no
 * server-side cursor and no session state of any kind, which is what allows this service to run as
 * several interchangeable instances with no affinity between a caller and the instance that answered
 * it last.</p>
 *
 * <p>Trade-offs: a stateless query re-evaluates its predicate on every turn where an open cursor
 * would have resumed a position it already held. That cost is accepted because it is what buys
 * horizontal scaling, and because the predicate is an ordered index range on the primary key rather
 * than a scan. It is stated plainly so nobody reintroduces a held cursor believing the omission was
 * an oversight.</p>
 *
 * <h2>How a further page is discovered, and how a backward page is ordered</h2>
 *
 * <p>Assumptions: a further page is established by reading one row beyond the window and observing
 * whether that row exists, never by counting. The baseline does exactly this: physical line 1657 tests
 * whether the row just placed filled the last slot on the screen, and line 1662 issues one further
 * fetch whose outcome sets the flag -- present at line 1671, absent on the no-more-rows path at line
 * 1674. Every walk in this package therefore takes a bound one greater than the window its caller
 * intends to publish, and the surplus row is the answer. The caller drops that row before publishing
 * and reports its presence as the envelope's further-page flag.</p>
 *
 * <p>Assumptions: a backward walk is read descending and <b>reversed before it is published</b>, so
 * the rows a caller receives are always ascending whichever direction it asked for. The baseline
 * expresses the reversal as a descending subscript rather than as a list operation: paragraph
 * {@code 8100-READ-BACKWARDS} begins at physical line 1727 and exits at 1794, sets its row index to
 * the page size at lines 1735 to 1736, and fills the display array downward by decrementing that index
 * at line 1769. The consequence for the surplus row is the one detail easy to get wrong: on a
 * descending walk the surplus row carries the <b>lowest</b> key and so sits first after the reversal,
 * whereas on an ascending walk it sits last. Dropping the wrong end silently removes a row the caller
 * should have seen, which is why the direction is a parameter of the assembly step rather than
 * something inferred there.</p>
 *
 * <p>Alternatives Considered: <b>the page-boundary ruling.</b> The baseline captures the last
 * displayed row's key at physical line 1659 and then, when the surplus fetch succeeds, <b>overwrites
 * it at line 1673 with the surplus row's key</b>. The envelope this package publishes into,
 * {@code com.carddemo.common.web.PageResponse}, settles the opposite: its trailing boundary is the key
 * of the last row the caller actually received, never the surplus row read to settle the flag. This is
 * a deliberate divergence, registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}, and it changes which key is published
 * and not which rows are returned, for the reason given under the forward comparison above. Two
 * further baseline browses were weighed before settling it, and they do not agree with each other:
 * {@code app/cbl/COCRDLIC.cbl} overwrites identically at physical lines 1207 to 1214, while
 * {@code app/cbl/COTRN00C.cbl} at physical lines 305 to 313 performs its surplus read purely to set
 * the flag and keeps no key from it. Publishing the last received row's key is the reading that makes
 * all three browses behave alike in this migration instead of preserving a split three ways.</p>
 *
 * <h2>The envelope these queries feed</h2>
 *
 * <p>Assumptions: the published shape is {@code com.carddemo.common.web.PageResponse}, a record with
 * exactly four components in this order: the rows, the leading boundary token, the trailing boundary
 * token, and the further-page flag. It is owned by the shared kernel and is not to be changed from
 * here. Four properties of it govern how these queries are written, and each is a property a caller
 * would otherwise guess at:</p>
 *
 * <ul>
 *   <li>The two boundary components are <b>opaque sealed tokens</b>, not raw keys. The envelope's
 *       canonical constructor rejects a raw key outright, so a walk's key value is sealed by
 *       {@code com.carddemo.common.web.CursorToken} on the way out and opened on the way back in.
 *       Assumptions: that is why no method here accepts or returns a token -- these interfaces take
 *       and compare <b>key values</b>, and sealing belongs to the layer that assembles the
 *       envelope.</li>
 *   <li>The trailing token names the last row returned, as ruled above.</li>
 *   <li>There is <b>no backward-availability component</b>, and none is to be added.</li>
 *   <li>There is <b>no page-size component and no total of any kind</b>.</li>
 * </ul>
 *
 * <p>Assumptions: the absent backward-availability component is grounded in the baseline rather than
 * chosen, and this is the evidence for it: <b>the baseline never computes backward availability at
 * all.</b> Paragraph {@code 8100-READ-BACKWARDS} asserts a further page unconditionally at physical
 * line 1738, before it has read a single row, with no surplus fetch anywhere in the paragraph -- which
 * is sound, because arriving at a page by stepping back from one further on already proves that a page
 * further on exists. Whether a step further back is possible comes from the screen counter instead:
 * {@code WS-CA-SCREEN-NUM} is decremented at physical line 784 immediately before the paragraph is
 * invoked at lines 785 to 786, and a first-page condition over that counter is what suppresses the
 * step. There was consequently nothing to port, so nothing was invented; a caller derives backward
 * availability from the presence of the leading token together with its own position.</p>
 *
 * <h2>Bounding a walk: what may be used, and what may not</h2>
 *
 * <p>Assumptions: three mechanisms are admissible for bounding a walk, and they are enumerated because
 * this is the point at which an author reaching for the obvious tool reaches for the forbidden one.
 * A {@code org.springframework.data.domain.Limit} parameter is the form used throughout this package.
 * The derived keyword forms that cap a result at a literal count are equally admissible. A query
 * carrying the SQL row-count clause in its own text is admissible where the query is already written
 * out. What makes {@code Limit} compatible with a key-positioned walk is specific and worth naming:
 * <b>it expresses a row count with no starting ordinal</b>, so it cannot express a window that begins
 * anywhere other than at the first row the predicate admits, and the predicate is the only thing that
 * positions the walk.</p>
 *
 * <p>Alternatives Considered: <b>positioning a window by ordinal was evaluated and rejected on a
 * behavioural ground, not a preference.</b> A window taken by ordinal has to re-count the rows that
 * precede it on every request, so when another caller inserts or deletes a row in between, the next
 * window is measured against a changed set: a row that shifted past the boundary is skipped and never
 * shown, and a row that shifted back across it is shown twice. A window positioned by key cannot do
 * either, because a key names a row rather than a distance. This is a real case here and not a
 * hypothetical one, and the baseline shows why: the browse program itself deletes rows from the table
 * it is browsing, counting the requests in {@code WS-DELETES-REQUESTED} at physical line 208 and
 * flagging each marked row from physical lines 58 and 184, while the companion maintenance program
 * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} issues the inserts and updates, its
 * {@code UPDATE CARDDEMO.TRANSACTION_TYPE} standing at physical line 1545. A concurrent modification
 * part-way through a browse is therefore the ordinary situation for this data rather than an edge
 * case. The types and vocabulary that express ordinal positioning are therefore excluded from
 * this package entirely, including from its prose, so that nothing here can be read as sanctioning
 * them.</p>
 *
 * <p>Assumptions: no walk takes a caller-supplied ordering. The ascending direction of a forward walk
 * and the descending direction of a backward one are transcribed from the two cursor declarations
 * cited above, so the ordering is part of the contract each method reproduces rather than a choice its
 * caller makes. A method that accepted an ordering could be handed one under which its own bound
 * selects different rows, which would make the transcription unverifiable.</p>
 *
 * <h2>The window is seven rows, and where that number comes from</h2>
 *
 * <p>Assumptions: the published window is <b>seven</b> rows, and the single authoritative source for it
 * is the program, never a count of fields on a map.
 * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} declares it at physical line 60 as
 * {@code 05  WS-MAX-SCREEN-LINES     PIC S9(4)      COMP VALUE 7.} and reads it at physical lines 60,
 * 940, 1004, 1018, 1334, 1386, 1657 and 1736 -- among them the two that matter most here, the
 * further-page gate and the descending fill index. Eight of the program's arrays are declared over
 * seven occurrences, at physical lines 118, 168, 182, 193, 283, 389, 437 and 459, which is the
 * corroboration that the constant and the storage agree.</p>
 *
 * <p>Assumptions: two counting traps sit around that number, and both are recorded because each yields
 * a plausible wrong answer rather than an obvious error. First, a fixed-string search for the
 * occurrence clause finds only six of the eight arrays, because physical lines 168 and 389 spell it
 * with two spaces before the count; a whitespace-tolerant pattern finds all eight. Second,
 * {@code app/app-transaction-type-db2/cpy-bms/COTRTLI.cpy} declares an eighth lettered triple at
 * physical lines 204, 210 and 216 -- and it is <b>not</b> an eighth row. Its third member's stem
 * differs from the stem the seven rows use, and the program never names it at all: it redeclares those
 * attribute bytes inside its own seven-occurrence structure at physical lines 437, 441 and 447, so the
 * map's eighth triple is shadowed and unreferenced. Counting map fields would therefore give eight
 * where the program gives seven.</p>
 *
 * <p>Assumptions: the seed migration {@code src/main/resources/db/migration/V2__seed_reference.sql}
 * loads exactly seven transaction types, and the window is exactly seven, so on seeded data alone the
 * whole table is one page and the further-page flag is false. A test that expects it true has to insert
 * an eighth row first. This is recorded here because the alternative is discovering it as a puzzling
 * assertion failure in a test whose query and envelope are both correct.</p>
 *
 * <h2>The one count query here counts nothing for paging</h2>
 *
 * <p>Assumptions: the baseline's aggregate query is not a page count and is not a total. Paragraph
 * {@code 9100-CHECK-FILTERS} begins at physical line 1801 and its aggregate is at physical line 1804;
 * its predicate carries the two filter tests and <b>no comparison against the cursor's start key at
 * all</b>, so it answers whether the filter matches any row anywhere in the table rather than how many
 * rows a page or the table holds. It exists to distinguish a filter that matched nothing from a browse
 * that ran off the end.</p>
 *
 * <p>Assumptions: the one aggregate this package declares -- the count of categories referencing a
 * type, on {@code TransactionCategoryRepository} -- is a diagnosis and not a total either. It lets a
 * refused parent delete be reported as the migrated refusal rather than escaping as a driver failure,
 * and it is deliberately not the authority: two callers deleting and inserting at once could each read
 * zero, so the declared foreign key remains what actually refuses. Alternatives Considered: computing
 * a row total per page to publish alongside the rows. Rejected, because the envelope declares no
 * component that could carry it and the published contract declares none either, so the value would be
 * computed, paid for with a second pass over the range, and then discarded -- and its mere presence
 * would invite exactly the ordinal positioning ruled out above.</p>
 *
 * <h2>Three type rulings that bind every query here</h2>
 *
 * <p>Assumptions: <b>a transaction-category code is character data, four wide, and never an integer.</b>
 * Its leading zeros are part of the key, so every comparison, bound and key value in this package
 * treats it as text. This departs from applying the migration's copybook-is-normative rule
 * mechanically, and the departure is named rather than hidden because two copybooks declare the field
 * numeric: {@code app/cpy/CVTRA04Y.cpy} line 7 states {@code TRAN-CAT-CD PIC 9(04)} and
 * {@code app/cpy/CVTRA02Y.cpy} line 8 states {@code DIS-TRAN-CAT-CD PIC 9(04)}. A numeric picture maps
 * to an integer column, so a reasonable alternative genuinely exists and leaving the choice
 * undocumented is the omission the Explainability rule names at line 40. Three character-typed sources
 * decide it: {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} line 3 declares
 * {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL} and line 5 places it in the primary key;
 * {@code app/app-transaction-type-db2/dcl/DCLTRCAT.dcl} lines 42 to 43 generate the host variable as
 * {@code PIC X(4)}, character and not numeric; and {@code app/data/ASCII/trancatg.txt} stores the codes
 * zero-padded, its first three keys being {@code 010001}, {@code 010002} and {@code 010003}. An
 * integer would hold {@code 0001} as 1, and a bound built from that value would order and compare
 * against rows it cannot match.</p>
 *
 * <p>Assumptions: <b>a disclosure account-group id is ten characters wide and is never trimmed</b>, on
 * either side of a comparison. Its trailing spaces are part of the stored key rather than formatting.
 * {@code app/cbl/CBACT04C.cbl} declares the field at line 79 as
 * {@code 10 FD-DIS-ACCT-GROUP-ID           PIC X(10).} and, when a rate lookup misses, falls back at
 * line 437 by moving the seven-character literal {@code 'DEFAULT'} into it; a short literal moved into
 * a ten-byte alphanumeric field is left-justified and space-filled, so the key actually searched for is
 * {@code DEFAULT} followed by three spaces. The seed data stores it that way: the first ten bytes of
 * {@code app/data/ASCII/discgrp.txt} hold exactly three distinct ids, one of them ten characters of
 * digits and the other two space-padded to ten. Trimming either side of the comparison would leave the
 * padded stored value and an unpadded probe as different values, and the fallback would then match
 * nothing -- which is a miss that returns no row rather than an error, so it would surface as an
 * interest figure that was never produced rather than as a failure at the lookup.</p>
 *
 * <p>Assumptions: <b>the disclosure interest rate is an exact scaled decimal</b> -- a fixed-point
 * numeric of scale 2 in the schema and {@code java.math.BigDecimal} in Java. The baseline field is
 * {@code DIS-INT-RATE PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy} line 9, a zoned decimal carrying
 * its sign as an overpunch in the trailing byte, which is an exact base-ten encoding. It is not merely
 * displayed: {@code app/cbl/CBACT04C.cbl} computes at lines 464 to 465
 * {@code ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, so the rate is an operand of a money calculation whose
 * result posts to a balance. Binary floating-point types are therefore excluded from every member
 * position on this path.</p>
 *
 * <p>Trade-offs: what enforces that exclusion, and what does not, is stated precisely, because a green
 * build is easy to mistake for a proof of correctness. The shared architecture test's money rule was
 * originally scoped to the shared money package alone and has since been widened: its subject is now
 * every production type under the migration's root, this package included, and it rejects the two
 * binary floating-point primitives and their two wrapper types in a field, a parameter or a return
 * type, including as a generic type argument. So a declaration of one here fails the build rather than
 * only review. What that rule cannot do is read arithmetic: it cannot tell whether a scale is 2,
 * whether rounding is half-up, or whether the product above was formed before the quotient as the
 * cited calculation requires. Those properties live in the shared money type and the tests over it,
 * and they remain a review obligation on anything written here.</p>
 *
 * <h2>Layering, and what the architecture gates do and do not reach</h2>
 *
 * <p>Assumptions: depending on {@code com.carddemo.reference.domain} from here is legal and expected,
 * because both packages sit under the same ownership root. The shared architecture test resolves
 * ownership from a fixed list of nine roots -- the shared kernel plus the eight bounded contexts -- and
 * asserts that the list still holds nine, so a root that went missing cannot silently stop matching. A
 * dependency on a <b>different</b> root's domain package is what that rule rejects, and it would fail
 * the build: this package may therefore never reach into another context's entities, and the reference
 * data it owns is offered to other contexts through this service's published contract and its transfer
 * objects instead.</p>
 *
 * <p>Assumptions: the persistence framework types these interfaces are built from are permitted here
 * precisely because this is not a domain package. The rule that forbids a type from depending on cloud
 * client, web and servlet packages takes any {@code domain} package as its subject and does not reach
 * this one, so the repository abstraction and its query annotations are admissible here in a way they
 * would not be one package over. That asymmetry is the layering, and it is the reason the entities
 * carry no query and these interfaces carry no mapping.</p>
 *
 * <p>Assumptions: layering has exactly one mechanical owner, the shared architecture test.
 * {@code config/checkstyle/checkstyle.xml} deliberately configures no import-control module, and none
 * is to be added here. Alternatives Considered: adding one as a second gate over the same boundary.
 * Rejected, because a reader who hit a layering failure would then have two engines to consult with
 * nothing to say which owned the rule, and the two could disagree while both reported success.</p>
 *
 * <h2>What no type in this package does</h2>
 *
 * <ul>
 *   <li><b>No ordinal-positioned window and no total for paging</b>, for the two reasons ruled
 *       above.</li>
 *   <li><b>No caller-supplied ordering</b> on a walk, as ruled above.</li>
 *   <li><b>No cursor sealing or opening.</b> These interfaces take and return key values; the
 *       envelope's tokens are minted and verified in the layer that assembles it, so a key never
 *       leaves this package already wrapped and never arrives still wrapped.</li>
 *   <li><b>No derived or bulk modifying statement.</b> A delete goes through the inherited keyed
 *       operation so that the declared foreign key is what refuses a restricted delete, and so that
 *       the row's concurrency counter is read and written by the provider. The rulings on that counter
 *       belong to {@code com.carddemo.reference.domain}, which is where the entities declare it; this
 *       bullet records only that bypassing the entity would bypass it.</li>
 *   <li><b>No exception handler and no status mapping.</b> A refused delete surfaces from the driver
 *       as a referential-integrity failure, is translated by the framework into a data-integrity
 *       exception, and is rendered as HTTP 409 by
 *       {@code com.carddemo.common.error.GlobalExceptionHandler}, which this module inherits. This
 *       module declares no advice of its own, so that the status and its wording stay identical across
 *       every service.</li>
 *   <li><b>No caching, no message broker, no read replica.</b> The baseline has no cache tier and no
 *       replica, and a read here is a primary-key probe or a bounded index range, so a second copy of
 *       this data would add a staleness window that the baseline cannot exhibit.</li>
 *   <li><b>No retry and no resilience library.</b> The shared architecture test rejects a dependency on
 *       either of the two libraries the migration considered, so this is enforced rather than merely
 *       intended. Should a retry ever be warranted, the framework's own facility is the one to use, and
 *       its attribute counts retries rather than attempts -- total attempts are one more than the value
 *       given -- and it is switched on by the framework's resilient-methods annotation and not by the
 *       older enabling annotation the superseded library used.</li>
 *   <li><b>No code generation over these interfaces.</b> No annotation processor supplies accessors or
 *       mappers anywhere in this module. Assumptions: a generated member cannot carry the Javadoc the
 *       Explainability rule requires of it, and {@code config/checkstyle/checkstyle.xml} leaves
 *       {@code MissingJavadocMethod} at its widest visibility with an empty allowed-annotation list, so
 *       no annotation exempts a member from needing a block. A generated accessor would therefore have
 *       to be excluded from the audit to build at all, which is an exemption the rule does not
 *       grant.</li>
 * </ul>
 *
 * <h2>The baseline is read and never written</h2>
 *
 * <p>Assumptions: everything beneath {@code app/} is the behavioural oracle for this migration. It is
 * read, cited by path and physical line, and never modified -- which is also why no line of it is
 * quoted here as anything other than evidence. Where the migrated behaviour departs from it
 * deliberately, as the page-boundary ruling above departs, the departure is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. That document is maintained elsewhere and
 * is referenced from here rather than reproduced, so the register has one owner.</p>
 *
 * <h2>Why this file exists, and the form it takes</h2>
 *
 * <p>Assumptions: this descriptor is required by the project's single user-specified rule rather than by
 * the migration plan. Rule 1 (Explainability) requires at line 15 a docstring on every module entry
 * point, and in Java the entry point of a package is its package declaration, which only a
 * {@code package-info} compilation unit can carry. Nothing in the migration requirements alone would
 * have produced this file; without it the package has no entry-point documentation, so the rule is
 * violated and the build fails as well.</p>
 *
 * <p>Assumptions: two Checkstyle modules enforce that, and neither is redundant.
 * {@code JavadocPackage} inspects the file set and requires this file to exist in any directory holding
 * an audited source file -- this directory holds seven interfaces, so it fires. {@code
 * MissingJavadocPackage} inspects the parsed tree and requires this file to carry Javadoc. A descriptor
 * reduced to a bare package statement, or to an ordinary block comment, satisfies the first and fails
 * the second, which is why prose is the deliverable and this file's existence is not. Both run at the
 * {@code validate} phase of every local build, ahead of compilation, with violations failing it; and
 * the configuration provides no comment-based or annotation-based suppression, so there is no in-file
 * way to opt out of either and none is attempted.</p>
 *
 * <p>Assumptions: this compilation unit holds one statement, so the rationale the rule's inline-comment
 * half asks for has no adjacent executable line to sit beside. It is carried in this block and in the
 * preamble above it, under the four labels the rule names at lines 31 to 34 -- Alternatives Considered,
 * Refactoring Rationale, Assumptions and Trade-offs -- which is the only placement a package makes
 * available. Each label is written in one form throughout, because the ruleset cannot check a label's
 * spelling and a second spelling of the same label would read as a second category. The rule's
 * Validation Gate at line 43 is conjunctive: a docstring alone does not pass it and a rationale alone
 * does not pass it, so every ruling above carries both. Its bar at line 41 rejects a rationale that
 * gives no specific justification, which is why each ruling names a path with a physical line, a named
 * constraint, or a named gate rather than asserting a preference. The written convention this block
 * follows is {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never restated.</p>
 *
 * <p>Assumptions: the rule governs newly authored code only. The reference baseline under {@code app/}
 * and the existing test suite under {@code tests/} are read-only for this migration, so neither is
 * retro-documented; doing so would breach the reference-only boundary rather than merely exceed the
 * rule. The house convention recorded alongside that suite imposes the same four justification
 * categories on new work, so it corroborates the rule and there is no conflict between them to
 * resolve.</p>
 */
package com.carddemo.reference.repository;
