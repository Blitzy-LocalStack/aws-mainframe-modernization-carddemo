/**
 * Typed read-only data access for the reporting bounded context, standing in for the COBOL
 * file verbs its two report programs issue.
 *
 * <p>Every query this context runs is issued from here and from nowhere else. The service
 * layer above composes report and statement content out of what these interfaces return, the
 * mapper layer beside it renders bytes, and no class outside this package opens a connection
 * or writes a query. That single-entrance property is what makes the read-only posture below
 * checkable by reading one directory instead of the whole module.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>This file exists because user-specified Rule 1 (Explainability) requires, at its L15
 * clause, a docstring on every new or modified function, class and module entry point, and a
 * Java package declaration is a module entry point. A {@code package-info.java} is the only
 * construct able to carry Javadoc for one. The citation is deliberately L15 and not L43: the
 * Validation Gate sentence at L43 governs "every new or modified function", and this file
 * declares none, so L15 is the only clause of the rule that reaches a package declaration.
 * Of the four content elements the rule enumerates at L18-L21, only Purpose applies here. A
 * package declaration accepts no parameters, yields no value and raises nothing, so the
 * Parameters element at L19, the Return values element at L20 and the exception element at
 * L21 are inapplicable rather than omitted, and no at-clause is invented to stand in for one
 * of them. The block form used is what L22 prescribes for Java.
 *
 * <p>Two checks in {@code config/checkstyle/checkstyle.xml} act on this file and neither is
 * redundant. {@code JavadocPackage}, declared at L245 of that file, runs at Checker level as
 * a file-set check over the {@code java} extension and asserts only that this file is
 * present. {@code MissingJavadocPackage}, declared at L378 inside the tree walker, asserts
 * that it carries Javadoc. A {@code package-info.java} holding nothing but a package
 * statement satisfies the first and fails the second, which is why both are wired. Both fire
 * from the plugin execution the parent aggregator binds to the Maven {@code validate} phase,
 * documented at {@code services/reporting-service/pom.xml} L552-L558, and that phase runs
 * ahead of compilation, so deleting this file breaks the build before a single class is
 * compiled. {@code config/checkstyle/suppressions.xml} carries exactly two entries, reaching
 * generated sources at its L213-L214 and test fixtures at its L250-L251; {@code src/main/java}
 * is unreachable from either, so nothing in this file can be waived. No in-code suppression
 * filter is wired at all, so comment-driven and annotation-driven waivers do not function.
 * The written convention this discharges is {@code docs/CODE_DOCUMENTATION_STANDARD.md},
 * whose L203-L249 fixes the label form used throughout the register below; the convention
 * itself originates in the reference test suite at {@code tests/README.md} section 12,
 * heading at L516, mandatory paragraph at L544-L549. Precedence runs Rule 1 first, then the
 * Checkstyle rule set, then that written standard, then prose.
 *
 * <p>Assumptions: this build's exit status is binary. The graded condition-code rubric under
 * which the COBOL parity oracle treats a warning-level aggregate as its own green state, set
 * out at {@code .github/workflows/tests.yml} L28-L38, belongs to that suite alone and arises
 * there from one immutable baseline defect outside this context. It is never carried into a
 * Maven, Checkstyle, Surefire or Failsafe gate on this side, where a violation is a failure
 * and nothing else.
 *
 * <h2>What this package is</h2>
 *
 * <p>The target design's Repository pattern stands in for the CICS file verbs
 * {@code READ}, {@code WRITE}, {@code REWRITE} and {@code STARTBR}, and for the batch
 * programs' {@code READ} and {@code READ ... INVALID KEY}. Data access becomes a typed
 * interface per role, so keyset positioning is expressed once and reused rather than
 * open-coded at each call. The browse quartet {@code STARTBR}, {@code READNEXT},
 * {@code READPREV} and {@code ENDBR} collapses into a single keyed query per direction, and
 * the shared envelope {@code com.carddemo.common.web.PageResponse}, whose four components at
 * L231-L235 of that record are the item list, {@code firstKey}, {@code lastKey} and
 * {@code hasNext}, carries the cursor. That record declares no row-count member of any kind,
 * which is the structural reason no ordinal-position addressing can be expressed through it.
 *
 * <h2>The closed inventory: this charter plus five interfaces</h2>
 *
 * <p>Assumptions: this inventory states the package's <b>target contract</b> as the migration
 * plan assigns it, and not a measurement of the files sitting beside this one. A role named
 * here that has no file is <b>planned</b>, not missing, and is authored at another index of
 * the same plan. This paragraph is the single place a reader has to look to tell a target
 * from a measurement, and the distinction is declared because the list below otherwise reads
 * as present tense and a reader who finds one name absent has reason to doubt all 6. The
 * context charter one level up makes the same declaration in the same terms at its L83-L106,
 * so the convention is house-wide rather than local to this file; the sibling projection
 * charter states the neighbouring guarantee at its L11, that a projection beyond its declared
 * set is a defect rather than an addition, which is the same closed-set property this list
 * asserts with the number 6.
 *
 * <p>Six files, and a seventh is a defect rather than an addition:
 * <ul>
 *   <li>this {@code package-info.java}, the charter and the decision register;</li>
 *   <li>{@code StatementTransactionRepository} -- the card-ordered transaction traversal;</li>
 *   <li>{@code StatementCardXrefRepository} -- the sequential card cross-reference
 *       traversal;</li>
 *   <li>{@code StatementCustomerRepository} -- the keyed customer lookup;</li>
 *   <li>{@code StatementAccountRepository} -- the keyed account lookup;</li>
 *   <li>{@code TransactionReportRepository} -- the report-side query surface, standing in for
 *       the four-way join {@code app/cbl/CBTRN03C.cbl} opens.</li>
 * </ul>
 *
 * <p>The first four together discharge the entire responsibility of
 * {@code app/cbl/CBSTM03B.CBL}, and each of the five maps to a view projection in the sibling
 * {@code domain} package rather than to a table another context owns.
 *
 * <p>Assumptions: the context charter one level up, at
 * {@code services/reporting-service/src/main/java/com/carddemo/reporting/package-info.java},
 * summarises this package as two streaming cursors and two keyed lookups. That is an exact
 * description of the first four roles and of the access modes their source declares --
 * {@code CBSTM03B.CBL} L33 and L39 declare {@code ACCESS MODE IS SEQUENTIAL} for the
 * transaction and cross-reference files, L45 and L51 declare {@code ACCESS MODE IS RANDOM}
 * for the customer and account files -- and the report-side surface is the fifth beside them.
 * The two readings agree; the four-role phrasing counts the statement path only.
 *
 * <h2>No write path exists here</h2>
 *
 * <p>This package declares no table, no index, no constraint and no schema-definition
 * statement of any kind, and it never will. The target design records this context's owned
 * tables as "(none)" and gives it read-only cross-schema views plus a database role holding
 * read privileges only; it also records that there is no owned schema and therefore no
 * migration directory. Three consequences follow and are stated plainly so that a subsequent
 * author reads an absence as a design decision rather than as an omission.
 *
 * <p>First, no schema-migration tooling is on this module's classpath. The two migration
 * artifacts the six table-owning services declare are deliberately absent from
 * {@code services/reporting-service/pom.xml}, which documents that omission at its L440. No
 * {@code db/migration} directory exists anywhere in this module, and one appearing under it
 * would itself be a defect.
 *
 * <p>Second, the connection posture is supplied at the JDBC boundary by
 * {@code services/reporting-service/src/main/java/com/carddemo/reporting/config/DataSourceConfig.java},
 * which pins the schema resolution path, asserts the pool's read-only flag and disables
 * schema generation by the persistence provider. This package configures no datasource of its
 * own and asserts nothing about the connection; it consumes whatever that class pins.
 *
 * <p>Third, the relations this package reads and the read privileges over them belong to the
 * data-migration package and are cited here by path rather than reproduced.
 * {@code data-migration/sql/V0__schemas_and_roles.sql} establishes the {@code reporting}
 * schema and the two roles at its L588-L589, conveys the cross-schema read privileges to the
 * no-login owner role at its L865 through L885, and withdraws every base-table privilege from
 * the service login role at its L937-L942 while leaving it schema usage at L944.
 * {@code data-migration/sql/V1__reporting_views.sql} declares the seven view definitions at
 * its L234, L295, L358, L387, L423, L469 and L513, assigns each to the owner role, and
 * conveys read on each by name to the service role at its L545-L551. Both files are another
 * agent's ownership. The operating rule is therefore blunt: <b>a view missing at run time is
 * a data-migration defect to report, and never something to create from here.</b> V0 states
 * that same instruction directly at its L565-L567.
 *
 * <p>Assumptions: the privilege shape is a view-owner indirection and not a direct read, and
 * mistaking one for the other would make the whole arrangement look redundant. The service
 * login role this module authenticates as holds usage on the {@code reporting} schema, conveyed
 * at V0 L944, and no privilege whatsoever on the four schemas holding data, every base-table
 * and schema-level privilege having been withdrawn from it at V0 L937-L942. The no-login owner
 * role holds the cross-schema read privileges instead, at V0 L865 and L870, L875, L880 and
 * L885, and the views execute with the owner's rights behind a security barrier, so a view
 * reads what its caller cannot. That is why the schema resolution path names one schema rather
 * than four, and why a query here that names a base relation directly fails at the database
 * rather than returning rows.
 *
 * <h2>Cross-schema reach is by privilege, never by a module dependency</h2>
 *
 * <p>The only intra-reactor dependency this module declares is the shared kernel, at
 * {@code services/reporting-service/pom.xml} L133 for the main artifact and L428 for its test
 * artifact. No module in the reactor declares a dependency on another service module.
 *
 * <p>A type in this package may depend on a projection under this context's own root, so
 * {@code com.carddemo.reporting.repository} reaching {@code com.carddemo.reporting.domain} is
 * legitimate. Reaching the domain package under any <i>other</i> context's root is a failure
 * -- in every form, whether as an import, a fully qualified name, a reflective string literal
 * or a token inside a Javadoc code span. Which is why no such root is written anywhere in
 * this file, not even as an illustration of what is prohibited. Agreement with the account,
 * card, transaction and reference contexts runs through the physical views and the read
 * privileges over them, never through code.
 *
 * <p>Assumptions: the prohibition is a build step rather than a convention. It is owned by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java},
 * whose cross-context isolation family resolves ownership from the exact root, declared as a
 * constant at its L138, and from the dot-delimited {@code domain} segment, declared at its
 * L221 and L231 precisely so that a package named for a subdomain or for domain events is not
 * mistaken for a domain package by a substring test. It spans the 9 roots the migration
 * establishes and is guarded by a further assertion, at its L689, that the root list still
 * holds exactly 9. That file is not relocated,
 * renamed or duplicated, and no second layering assertion is added here, because
 * {@code .github/workflows/services-ci.yml} names that path character for character. The
 * same class carries the prohibition on binary floating point, which is why no member in this
 * package is ever declared as an IEEE-754 binary floating-point quantity and why money on
 * every projection and every return type is the shared exact decimal type.
 *
 * <h2>Two joins, four participants each, never conflated</h2>
 *
 * <p>The statement path joins four participants, evidenced by the data definitions at
 * {@code app/jcl/CREASTMT.JCL} L83-L86: the card-ordered transaction projection, the card
 * cross-reference, the account and the customer.
 *
 * <p>The report path joins its own four, evidenced by the data definitions at
 * {@code app/jcl/TRANREPT.jcl} L65-L72 and corroborated by the {@code COPY} statements at
 * {@code app/cbl/CBTRN03C.cbl} L93, L98, L103 and L108: the transaction record, the card
 * cross-reference, the transaction type and the transaction category. The parameter file at
 * {@code TRANREPT.jcl} L73-L74 supplies the reporting date range and is not a join
 * participant.
 *
 * <p>The card cross-reference is the only participant common to both, and it is reached
 * through two different access shapes: sequentially in the statement path, where
 * {@code CBSTM03B.CBL} L39 declares it {@code SEQUENTIAL} and {@code CBSTM03A.CBL} drives it
 * from the paragraph {@code 1000-XREFFILE-GET-NEXT.} at L345, and as a keyed join participant
 * in the report path, where {@code CBTRN03C.cbl} looks it up at {@code 1500-A-LOOKUP-XREF}
 * L484-L492. The two joins are never conflated. They read different relations with provably
 * different geometry, and the register below records the two places that difference is
 * observable: the transaction projections differ in what the sort actually writes, and the
 * ordering guarantees differ in how many sort keys the baseline declares.
 *
 * <h2>Decision register</h2>
 *
 * <p>What follows discharges Rule 1's inline-comment requirement at L27-L34 for the choices
 * in this package a reasonable alternative could have gone the other way on. L40 forbids
 * leaving such a choice undocumented and L41 forbids a rationale without specific
 * justification, naming two vague phrasings verbatim as examples of what is not accepted, so
 * every entry below carries a concrete anchor: a line number, a declared width or a byte
 * count. The register is authored here once, and the five interfaces cite a row by its
 * identifier rather than restating it.
 *
 * <p>Assumptions: the four category labels are written character for character as Rule 1
 * writes them at L31-L34, which is also the one permitted written form
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes at its L209-L212. Four properties of that
 * form are load-bearing and each has been got wrong in practice: plural where the rule writes
 * it plural, unparenthesised, colon retained, and no emphasis markup, since the emphasis
 * around the labels in the rule text is that document's own presentation and not part of the
 * label. The hyphen in the fourth label is ASCII hyphen-minus, byte {@code 0x2d}. The labels
 * were retyped from the rule rather than copied from the reference suite for a measured
 * reason recorded at R14.
 *
 * <h3>R1 -- keyset positioning, not addressing a window by row ordinal</h3>
 *
 * <p>Alternatives Considered: addressing a result window by the ordinal of its first row was
 * evaluated and rejected, because a row's ordinal is not stable. Insert a row ahead of it and
 * every ordinal behind the insertion moves by one, so two consecutive requests over a table
 * being written omit rows entirely and return others twice. The target design states the same
 * defect in its own words, that such addressing loses and repeats rows under concurrent
 * inserts, and reaches the same conclusion. Keying the next window strictly past the last key
 * already returned has no such failure, because a key does not move when a neighbour is
 * inserted. The decisive evidence that this reproduces rather than replaces the baseline is
 * that the baseline's own cursor is already a key: the block at {@code app/cbl/COCRDLIC.cbl}
 * L229-L244 carries key pairs and a next-page indicator, and carries no row ordinal anywhere.
 *
 * <p>Assumptions: concurrent inserts really do land mid-key-space while a traversal is in
 * flight, and the baseline shows it. {@code app/cbl/COBIL00C.cbl} L212-L217 is an unlocked
 * read-then-increment: {@code MOVE HIGH-VALUES TO TRAN-ID} at L212, then
 * {@code STARTBR}, {@code READPREV} and {@code ENDBR} at L213, L214 and L215 to land on the
 * highest key, then {@code MOVE TRAN-ID TO WS-TRAN-ID-NUM} at L216 and {@code ADD 1} at L217,
 * with L218-L220 building a new record from that value. Nothing holds a lock across those six
 * statements, so two concurrent bill payments can derive the same next identifier. That site
 * is a maximum-key and next-identifier generator and is cited here only as the counter-example
 * that proves insertion is unsynchronised -- it is <b>not</b> a browse screen and is not the
 * ground for the keyset decision.
 *
 * <p>Assumptions: the keyset shape itself is grounded on the two genuine browse screens.
 * {@code app/cbl/COCRDLIC.cbl} carries a page of 7 rows, declared as
 * {@code WS-MAX-SCREEN-LINES} at L177 with {@code VALUE 7} at L178, and drives
 * {@code STARTBR} at L1129, {@code READNEXT} at L1146, a lookahead {@code READNEXT} at L1197
 * and {@code ENDBR} at L1258 going forward, then a backward {@code STARTBR} at L1273, a
 * priming {@code READPREV} at L1294, the loop {@code READPREV} at L1322 and {@code ENDBR} at
 * L1376. Its cursor is already a keyset cursor and not an ordinal: the communication-area
 * block at L229-L244 holds a last-key pair at L230-L232, a first-key pair at L233-L235, a
 * screen number at L237, a last-page-displayed flag at L239 and a next-page indicator at
 * L242-L244, alongside a row counter at L145. {@code app/cbl/COTRN00C.cbl} carries a page of
 * 10, bounded at L290 and L344, and drives the same quartet at L591 and L593, L624 and L626,
 * L658 and L660, and L692 and L694. The target therefore reproduces a shape the baseline
 * already had, rather than substituting a different one.
 *
 * <h3>R2 -- the returned backward key is the last row shown, not the lookahead probe</h3>
 *
 * <p>Trade-offs: the baseline is genuinely inconsistent between its two browse screens, so
 * one of the two cannot be reproduced exactly and the divergence is accepted deliberately.
 * {@code app/cbl/COCRDLIC.cbl} L1194-L1195 stores the key of the last row actually displayed,
 * then issues the lookahead {@code READNEXT} at L1197-L1205 and <b>overwrites</b> that stored
 * key with the probe row's key at L1207-L1214, under both the normal and the duplicate-record
 * arms. {@code app/cbl/COTRN00C.cbl} L305-L313 runs the same lookahead and discards the probe
 * key, setting only the has-next flag at L310 and L312. The probe row is by construction a
 * row the client never saw, so a cursor carrying its key describes a position the user was
 * never shown. The target anchors the cursor on the last row returned, which is what the
 * second screen already does. What is preserved on both paths is the observable behaviour: the
 * page boundary and the availability of a next page. What is not preserved is the first
 * screen's internal cursor value, and that value never reached the user.
 *
 * <h3>R3 -- four roles replace one dispatcher, and opcode {@code 'K'} is a keyed single-row
 * read rather than a browse</h3>
 *
 * <p>Assumptions: {@code app/cbl/CBSTM03B.CBL} is 230 lines and is definitively a subprogram,
 * declaring {@code PROCEDURE DIVISION USING LK-M03B-AREA.} at L114. It owns all four input
 * file definitions, at L58, L65, L70 and L75, while {@code app/cbl/CBSTM03A.CBL} declares
 * none of them -- only two output definitions at L44-L47, an 80-byte statement record at L45
 * and a 100-byte markup record at L47, behind the two {@code SELECT} clauses at L39-L40.
 * Dispatch is by data-definition name first, {@code EVALUATE LK-M03B-DD} at L118, and only
 * then by operation code, and per-definition support is asymmetric: <b>no file supports both
 * {@code 'R'} and {@code 'K'}</b>. Four roles here rather than one dispatching type is what
 * lets a caller name the data it wants instead of naming an operation code and a data
 * definition string.
 *
 * <p>Assumptions: the call census is thirteen sites, not six, and the count matters because it
 * is what establishes that this one subprogram carries the whole statement read path.
 * {@code CBSTM03A.CBL} calls it at L351, L377, L401, L734, L746, L769, L787, L805, L835,
 * L860, L877, L893 and L909, decomposing as four opens at L734, L769, L787 and L805, four
 * closes at L860, L877, L893 and L909, and five reads at L351, L377, L401, L746 and L835. The
 * {@code CALL 'CEE3ABD'} at L923 is the language-environment abend service and is not part of
 * this contract. Two sources disagree on the figure and both are named rather than one being
 * silently overwritten: the context charter one level up states six sites, and the first-hand
 * census over {@code CBSTM03A.CBL} states thirteen. The first-hand COBOL is authoritative,
 * and the four-plus-four-plus-five decomposition above is what makes the thirteen
 * self-checking against the four data definitions.
 *
 * <p>Assumptions: {@code LK-M03B-KEY-LN}, declared {@code PIC S9(4)} at
 * {@code CBSTM03B.CBL} L111 and mirrored at {@code CBSTM03A.CBL} L82, is a substring length
 * and <b>not</b> a significant-key length. A peer brief for
 * {@code services/reporting-service/README.md} reads it as a significant-key length implying
 * a generic browse, and would justify keyset positioning from inside the statement read path
 * on that basis. Both readings are named here and the first-hand COBOL is authoritative, on
 * the same precedent the shared authorization codec contract sets for its own comma defect.
 * Five independent proofs settle it. First, the browse-verb census across all 230 lines
 * returns zero for {@code KEY IS GREATER}, {@code READ NEXT}, {@code READNEXT},
 * {@code READPREV}, {@code STARTBR} and {@code ENDBR}; the only bare {@code START} hit in the
 * file is the paragraph label {@code 0000-START.} at L116. Second, the {@code SELECT} clauses
 * declare {@code ACCESS MODE IS RANDOM} at L45 for the customer file and L51 for the account
 * file, and a COBOL indexed file so declared cannot be browsed at all, while L33 and L39
 * declare {@code SEQUENTIAL} for the other two. Third, the callee bodies at L188-L193 and
 * L213-L218 are plain reference-modified moves followed by a keyed read,
 * {@code MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-<x>-ID} then
 * {@code READ <file> INTO LK-M03B-FLDT}, with no greater-or-equal qualifier anywhere. Fourth,
 * the caller computes the whole key every time: {@code CBSTM03A.CBL} L373-L374 zeroes the
 * length then sets it from {@code LENGTH OF XREF-CUST-ID}, giving 9, and L397-L398 does the
 * same for the account identifier, giving 11, which match {@code app/cpy/CVACT03Y.cpy} L6
 * {@code PIC 9(09)} and L7 {@code PIC 9(11)} exactly, with no prefix and no partial-key
 * intent. Fifth, the paragraph names themselves separate the two shapes:
 * {@code 1000-XREFFILE-GET-NEXT.} at L345 for the sequential definition against
 * {@code 2000-CUSTFILE-GET.} at L368 and {@code 3000-ACCTFILE-GET.} at L392 for the two
 * random ones. The consequence for this package is direct: the customer and account roles are
 * single-row lookups by whole key and need no cursor at all, and only the two sequential roles
 * stream.
 *
 * <h3>R4 -- an unsupported read request raises rather than returning a stale status</h3>
 *
 * <p>Refactoring Rationale: the code being replaced returns a value that is not an answer.
 * {@code CBSTM03B.CBL} L103-L108 declares six operation codes as condition names -- open,
 * close, read, keyed read, write and rewrite -- but the last two are implemented in no
 * paragraph: a census for their two condition names across the whole file returns exactly two
 * hits, which are the L107 and L108 declarations themselves. A write or rewrite request
 * therefore falls through every {@code IF} in its per-definition paragraph and lands on that
 * paragraph's exit, whose only act is to move the file status into the return code, at L152,
 * L176, L201 and L226. The caller receives the status of the <i>previous</i> operation on that
 * file and cannot tell a silent no-operation from a success. An unrecognised data-definition
 * name is worse still: {@code WHEN OTHER} at L127-L128 branches straight to the goback,
 * leaving the return code never assigned at all. Because this package is read-only, only the
 * four implemented codes are ever exercised through it, and the target's equivalent of an
 * unsupported request raises instead of returning, so an unreachable request cannot be
 * mistaken for a completed one.
 *
 * <h3>R5 -- a record-selection condition becomes a SQL predicate, never a control-flow
 * branch</h3>
 *
 * <p>Refactoring Rationale: the construct being replaced selects records, and the construct
 * that shares its keyword gates steps, so translating either one as the other inverts a
 * behaviour. {@code app/jcl/TRANREPT.jcl} L47-L48 carries
 * {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND, TRAN-PROC-DT,LE,PARM-END-DATE)},
 * a record-selection predicate, inclusive on both bounds and on the date part alone. It is
 * corroborated independently by the program's own in-line filter at
 * {@code app/cbl/CBTRN03C.cbl} L173-L174, which compares the first ten characters of the
 * processing timestamp against the two range endpoints with the same inclusive operators. In
 * the target that becomes a {@code WHERE} clause on the driving query, with both bounds
 * inclusive.
 *
 * <p>Trade-offs: the step gates {@code COND=(0,NE)} at {@code app/jcl/CREASTMT.JCL} L56, L66
 * and L79 look like the same thing and are not. Each states the condition under which its
 * step is bypassed, so its sense negates when it is expressed as a condition for running, and
 * a gate of that form belongs in orchestration rather than in a query. The cost of stating
 * this here is a paragraph about a construct this package does not implement; it is accepted
 * because the two forms share a keyword and conflating them in either direction silently
 * changes either which rows appear or which steps run.
 *
 * <h3>R6 -- ordering is card number and then a stable secondary key</h3>
 *
 * <p>Assumptions: the report ordering the baseline declares is not total, so reproducing it
 * literally would leave the output non-deterministic. {@code app/jcl/TRANREPT.jcl} L46 is
 * {@code SORT FIELDS=(TRAN-CARD-NUM,A)} -- one key, with no equal-records qualifier -- so the
 * relative order of two rows sharing a card number is whatever the sort happens to produce.
 * The target orders on the card number and then on a stable secondary key, which makes a rerun
 * over the same range reproduce the same bytes and therefore makes golden-master comparison
 * meaningful at all. That two of the baseline's own sorts are already total is what shows the
 * single-key declaration to be an omission in that one member rather than a house style:
 * {@code app/jcl/CREASTMT.JCL} L53 declares {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}, two
 * keys, and {@code app/jcl/PRTCATBL.jcl} L52 declares three.
 *
 * <p>Assumptions: the field positions the ordering rests on are cross-checked from two
 * unrelated members, so neither reading depends on the other. Summing the fourteen declared
 * widths of {@code app/cpy/CVTRA05Y.cpy} L5-L18 gives exactly 350 bytes, placing the card
 * number at zero-based position 262 and the processing timestamp at zero-based position 304.
 * Independently, {@code app/jcl/TRANREPT.jcl} L41-L42 declare the sort symbols
 * {@code TRAN-CARD-NUM,263,16,ZD} and {@code TRAN-PROC-DT,305,10,CH} in one-based positions,
 * and {@code app/jcl/TRANIDX.jcl} L27 declares {@code KEYS(26 304)} with space-separated
 * operands. All three agree once the single conversion rule is applied: a zero-based position
 * is the one-based position minus one. The conversion is stated because the three sources
 * genuinely use two different conventions and a silent off-by-one here moves a key onto the
 * wrong field rather than failing loudly.
 *
 * <h3>R7 -- the date part comes from the shared timestamp utility, never from a local slice</h3>
 *
 * <p>Assumptions: the 26-character {@code 'YYYY-MM-DD HH:MM:SS.mmmmmm'} contract is owned by
 * {@code com.carddemo.common.time.TimestampFormatter}, which declares that width as a
 * constant at its L210 and exposes both accessors this package needs:
 * {@code datePrefix(String)} at its L601, returning the first ten characters, and
 * {@code toLocalDate(String)} at its L640. That class names
 * {@code app/jcl/TRANREPT.jcl} L42 in its own contract, at its L206 and L579, as the only
 * production consumer of that field anywhere in the migration, and {@code TRANREPT.jcl} L59,
 * {@code //STEP10R EXEC PGM=CBTRN03C}, drives the very program this package's report surface
 * stands in for. <b>This package is therefore that sole named consumer</b>, which is why the
 * accessor lives in the shared kernel rather than here. The baseline itself takes the first
 * ten characters by reference modification at {@code app/cbl/CBTRN03C.cbl} L173-L174, which is
 * exactly what {@code datePrefix} returns. No locally written ten-character slice is permitted
 * anywhere in this package.
 *
 * <p>Trade-offs: the parity oracle cannot validate the punctuation of that field, and claiming
 * otherwise would overstate what the golden masters prove.
 * {@code tests/helpers/golden_compare.py} L25-L26 records that the run-generated processing
 * timestamp is masked in place, blanked to spaces at its declared position while its width is
 * preserved, so the goldens constrain the field's width and its position and say nothing at
 * all about its separators. The punctuation is therefore held by the shared utility's own
 * unit tests rather than by the oracle, and that is the honest division of evidence.
 *
 * <h3>R8 -- the index-rebuild step retires; the index itself is owned elsewhere</h3>
 *
 * <p>Refactoring Rationale: what disappears is a build step and not an access path, and the
 * distinction decides who owns what. PostgreSQL maintains an index transactionally as rows
 * change, so the separate rebuild the baseline runs has no counterpart; the secondary access
 * path it produced survives intact. That rebuild appears at exactly four sites repository-wide
 * -- {@code app/jcl/XREFFILE.jcl} L100, {@code app/jcl/TRANIDX.jcl} L52,
 * {@code app/jcl/CARDFILE.jcl} L110 and {@code app/jcl/TRANFILE.jcl} L109 -- and the one this
 * context reads through is the processing-timestamp path from {@code TRANIDX.jcl}. In the
 * target that path is the non-unique index on the transaction table's processing timestamp,
 * <b>owned by the transaction context and neither created nor removed from here</b>.
 * {@code app/jcl/TRANIDX.jcl} stays on disk untouched and is cited only as position evidence:
 * {@code KEYS(26 304)} at L27, {@code NONUNIQUEKEY} at L28 and {@code UPGRADE} at L29, the
 * last two confirming the path is non-unique and maintained on update, which is the same pair
 * of properties the target index carries.
 *
 * <h3>R9 -- the card-ordered projection is an index over rows another context owns, and
 * nothing here copies bytes</h3>
 *
 * <p>Alternatives Considered: materialising a second physical copy of the transaction data in
 * card order was evaluated and rejected, and it is worth naming as a real alternative because
 * <b>the baseline does exactly that</b>. {@code app/jcl/CREASTMT.JCL} L44-L61 runs a sort at
 * L44 over the transaction cluster named at L45, writes a sequential dataset declared at
 * L48-L51, then at L56 runs the dataset utility whose control card at L61 is
 * {@code REPRO INFILE(INFILE) OUTFILE(OUTFILE)}, loading the keyed dataset named at L59 -- and
 * it is that second dataset the statement generator reads, through the data definition at L83.
 * The target defines no relation for it and copies no bytes: the card-first ordering is an
 * index plus a read-only projection over rows the transaction context owns. Rejected because a
 * second physical copy is a second truth for figures whose only purpose is to restate the
 * first one exactly, and because keeping it in step would add a synchronisation the baseline's
 * nightly rebuild hides only by rebuilding wholesale.
 *
 * <p>Assumptions: the rearrangement that sort performs is worth recording precisely, because it
 * is why the two transaction projections in this context differ rather than being one relation
 * read twice. {@code CREASTMT.JCL} L54 is
 * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)}: it lifts the 16-byte card number from
 * one-based position 263 to position 1, block-shifts the original first 262 bytes to position
 * 17, and copies 50 bytes from position 279 verbatim. That is 328 of the record's 350 bytes.
 * Because {@code app/cpy/CVTRA05Y.cpy} places the origination timestamp at one-based 279 for
 * 26 characters and the processing timestamp at one-based 305 for 26, the 50-byte tail carries
 * the origination timestamp whole and <b>only the first 24 characters</b> of the processing
 * timestamp, leaving one-based output positions 329 through 350 -- twenty-two bytes -- never
 * written at all. {@code app/cpy/COSTM01.CPY} nonetheless declares the full 350 bytes with its
 * own 26-character processing timestamp at one-based 305, so those two trailing characters are
 * declared and never filled. That is recorded as an observation about an immutable baseline
 * artifact and not as something to act on: the baseline truncates there, the Java reads a
 * microsecond-precision timestamp column from the physical view, and the divergence is
 * registered in the traceability document rather than absorbed silently.
 *
 * <h3>R10 -- a missing dimension aborts the run; it is never silently discarded</h3>
 *
 * <p>Assumptions: the baseline treats an unresolvable dimension as fatal on both paths, and the
 * policy here rests on that rather than on SQL's default behaviour. On the report path all
 * three lookups abort: {@code 1500-A-LOOKUP-XREF} at {@code app/cbl/CBTRN03C.cbl} L484-L492,
 * {@code 1500-B-LOOKUP-TRANTYPE} at L494-L502 and {@code 1500-C-LOOKUP-TRANCATG} at L504-L512
 * each read with an invalid-key branch that displays a message and the offending key, moves 23
 * into the status field, displays the status and then abends. The three messages, at L487, L497
 * and L507, each carry a space both before and after the colon. On the statement path the same
 * class of failure holds and is even less forgiving: the cross-reference read tolerates
 * end-of-file through its {@code WHEN '10'} arm at {@code app/cbl/CBSTM03A.CBL} L353-L362, but
 * the customer read at L379-L386 and the account read at L403-L410 have no such arm at all, so
 * a cross-reference row whose customer or account is absent abends the whole run at
 * {@code 9999-ABEND-PROGRAM.} L921, whose two-statement body at L922-L923 displays and then
 * calls the language-environment abend service.
 *
 * <p>Alternatives Considered: a plain inner join alone was rejected outright, because it
 * <b>discards</b> the unresolvable row instead of abending -- different bytes, different
 * account and grand totals, and different report page boundaries, which is a silent parity
 * failure of exactly the kind the golden masters exist to catch. Of the two viable shapes, an
 * outer join that admits a null dimension and then aborts on it was also rejected: it forces
 * every dimension member on the projection to be nullable purely to model a state the baseline
 * treats as fatal, and that nullability then reads identically to a genuinely optional column,
 * so the type no longer states which absences are legal. The shape adopted is an inner join
 * plus an integrity reconciliation at the query boundary: the count of driving rows the date
 * predicate admits is compared against the count the join yields, and any shortfall raises
 * naming the unresolved key, which is what the three baseline paragraphs at
 * {@code app/cbl/CBTRN03C.cbl} L484-L492, L494-L502 and L504-L512 do. The projection members
 * stay non-null by construction and the fatal case lives in one place.
 *
 * <h3>R11 -- read privileges only, over relations this package does not own</h3>
 *
 * <p>Trade-offs: this context accepts that it cannot supply its own read surface. The target
 * design records its owned tables as "(none)", and the relations plus the read privileges over
 * them are established by {@code data-migration/sql/V0__schemas_and_roles.sql} and
 * {@code data-migration/sql/V1__reporting_views.sql}, both owned by another agent. The
 * compromise is real and is stated rather than worked around: when a relation this package
 * needs is absent at run time, the only correct action from here is to report a defect against
 * the data-migration package, which V0 instructs directly at its L565-L567, because creating it
 * from here would put the same object under two owners and the two would drift -- and this role
 * could not create it in any case, holding only the schema usage conveyed at V0 L944. What is
 * bought in exchange is that a write issued from this
 * package fails at the database rather than succeeding against data another context is
 * accountable for, which no amount of care inside this package could guarantee on its own.
 *
 * <h3>R12 -- no read replica</h3>
 *
 * <p>Alternatives Considered: pointing this context at a read replica is the reflex for a
 * reporting workload and was rejected on the target design's own stated ground, that a replica
 * adds cost and replica-lag semantics for no parity benefit. The specific behavioural cost is
 * worth naming rather than left as a generality: the baseline statement job reads the live
 * transaction cluster directly, at {@code app/jcl/CREASTMT.JCL} L45, so it cannot omit a
 * transaction the online path has already accepted, whereas a statement generated from a
 * lagging replica can. Reads therefore go to the writer through the read-only cross-schema
 * views.
 *
 * <h3>R13 -- a query boundary returns a fully populated projection or nothing</h3>
 *
 * <p>Assumptions: this is contract hygiene the baseline already practises, and it is adopted
 * rather than invented. Every one of the thirteen calls into the dispatcher is bracketed. Each
 * is preceded by a clear-before pair, {@code MOVE ZERO TO WS-M03B-RC.} together with
 * {@code MOVE SPACES TO WS-M03B-FLDT.}, at {@code app/cbl/CBSTM03A.CBL} L349-L350, L375-L376
 * and L399-L400, and each read is followed by a copy-after into a typed record: L364 for the
 * cross-reference, L388 for the customer and L412 for the account. The generic 1000-byte data
 * buffer declared at {@code app/cbl/CBSTM03B.CBL} L112 is never read directly by the caller;
 * the caller always copies it into a record whose fields are declared. The Java equivalent is
 * that a query here returns a fully populated typed projection or returns nothing -- an empty
 * optional, or an empty item list inside the shared envelope -- and never a half-filled
 * instance a caller has to inspect field by field to find out whether the read succeeded.
 *
 * <h3>R14 -- the plural label register, and why the labels were retyped</h3>
 *
 * <p>Alternatives Considered: the singular register that predominates in the reference trees
 * was evaluated and rejected. {@code .github/workflows/tests.yml} L19-L43 tags its
 * design-decision bullets in the singular, and the reference test tree carries the
 * parenthesised singular form 68 times across 19 files against 5 occurrences of the plural
 * colon-terminated form -- better than 13 to 1 against the register chosen here. Both figures
 * are measured over immutable trees with the C locale, so the citation does not decay. The
 * plural wins for one specific reason and not on taste: Rule 1 writes the four categories in
 * the plural at L31-L34, and its Validation Gate at L43 makes that wording the sentence this
 * tree is audited against, so matching the majority idiom would read more consistently with
 * the reference tree while failing to match the rule actually enforced.
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} records the same reasoning at its L236-L245.
 * The two registers are never mixed inside one file.
 *
 * <p>Assumptions: the labels in this file were retyped from the rule text and not copied from
 * {@code tests/README.md}, for a byte-level reason. That member is the repository's principal
 * carrier of the non-breaking hyphen U+2011 -- 106 occurrences across 77 lines, measured with
 * the C locale, because a UTF-8 locale reports a misleading zero for a byte-pattern search --
 * and the mandatory paragraph at its L544-L549 renders the fourth category as the letters
 * {@code Trade}, then that non-breaking hyphen, then {@code offs)}. A byte dump of L548 shows
 * {@code 64 65 e2 80 91 6f 66 66 73 29}. Copying it would diverge three ways at once: a
 * non-ASCII hyphen codepoint where the rule uses ASCII hyphen-minus, a closing parenthesis
 * where the rule uses a colon, and an inline-list position where the rule's form opens the
 * rationale. None of the three is visible on screen, which is exactly why the byte dump is
 * cited rather than the rendering.
 *
 * <h3>R15 -- the privileged schema set is wider than the baseline evidence, and that is
 * stated rather than smoothed over</h3>
 *
 * <p>Assumptions: the read privileges the view owner holds reach four schemas while the direct
 * data-definition evidence in the two baseline members exercises only three, and the gap is
 * recorded here rather than reconciled by quietly narrowing one of the two.
 * {@code data-migration/sql/V0__schemas_and_roles.sql} conveys usage at its L865 and read on
 * all tables in four schemas at its L870, L875, L880 and L885 -- {@code ledger},
 * {@code account}, {@code card} and {@code reference}. Against that,
 * {@code app/jcl/CREASTMT.JCL} L83-L86 resolves to the transaction store plus three
 * account-side stores, and {@code app/jcl/TRANREPT.jcl} L65-L72 resolves to the transaction
 * store, the card cross-reference and two reference stores; their union is {@code ledger},
 * {@code account} and {@code reference} alone. <b>The {@code card} schema is read by neither
 * program</b>: no card record copybook appears in either {@code COPY} set and no card data
 * definition appears in either job. It is nonetheless inside the privileged set because a card
 * attribute does surface from this context, masked, on the relations derived from the ledger.
 * The rule this package follows is the resolution path the datasource pins, not a schema list
 * restated here, because a list with two owners drifts invisibly. The same discrepancy is
 * recorded independently by the sibling projection charter, with three proofs at its L341-L350,
 * and by {@code DataSourceConfig} at its L79-L92; three concurring records of one gap is the
 * intended state, and none of them resolves it.
 */
package com.carddemo.reporting.repository;
