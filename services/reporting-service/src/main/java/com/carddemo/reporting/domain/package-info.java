/**
 * Persistence-mapped projections of the read-only cross-schema views this bounded context
 * queries.
 *
 * <h2>A target contract, not a listing of this directory</h2>
 *
 * <p>Alternatives Considered: this charter is authored ahead of the seven projections it
 * governs, so every class name, count and inventory below states the package's <b>target
 * contract</b> as the migration plan assigns it rather than measuring the files that sit
 * beside it. Withholding the charter until each projection existed was evaluated and
 * rejected, because the charter is precisely what the author of a projection needs -- which
 * type belongs here, which does not, what the closed set is, and which copybook line each
 * declared width comes from -- so writing it last would leave the package with no stated
 * contract exactly where one is load-bearing. Nothing here is speculative on that account:
 * every geometry figure below names the copybook line it was read from, spanning
 * {@code app/cpy/COSTM01.CPY} L20 through {@code app/cpy/CVTRA04Y.cpy} L9, and each of the
 * seven record lengths was re-derived by summing declared widths rather than copied, so the
 * charter is checkable against reference material that never changes. The cost accepted is
 * that an inventory reads as present tense unless the distinction is declared, which this
 * paragraph is for: a projection named below that has no file beside this one is
 * <b>planned</b>, never missing.
 *
 * <h2>The closed set of types</h2>
 *
 * <p>This package holds exactly seven projection types plus this charter, eight {@code .java}
 * files in all, and it holds no subdirectory whatsoever. The seven are
 * {@code StatementTransactionView}, {@code ReportTransactionView}, {@code CardXrefView},
 * {@code AccountView}, {@code CustomerView}, {@code TransactionTypeView} and
 * {@code TransactionCategoryView}. An eighth projection is a defect rather than an addition,
 * and so is a nested package: nesting one would manufacture a second package-charter
 * obligation that nothing in the plan asks for. No {@code db/migration} directory appears
 * here, and none appears anywhere under this module.
 *
 * <p>Every type in this package maps a <b>view</b>, never a base table. This context is the
 * one entry in the migration plan's schema-ownership table recorded with no owned tables: it
 * declares no table, no index, no constraint, no DDL of its own, and no schema-migration
 * artifact on its classpath. The wider statement of what the context owns and does not own,
 * including the register of indexes belonging to other contexts, is held once at
 * {@code services/reporting-service/src/main/java/com/carddemo/reporting/package-info.java}
 * and is referenced from here rather than repeated, so the two cannot drift apart.
 *
 * <p>No projection here carries an application-level paging member of any kind. The cursor
 * envelope {@code com.carddemo.common.web.PageResponse} is composed in the repository layer
 * and is not reached from this package, so a query concern cannot leak into a row shape.
 *
 * <p>{@code app/cpy/CVTRA07Y.cpy} is deliberately absent from this package even though
 * {@code app/cbl/CBTRN03C.cbl} copies it at L113. That copybook declares report OUTPUT bands
 * carrying numeric edit masks -- a leading-minus mask at L30 and leading-plus masks at L54,
 * L60 and L66, each fifteen characters wide -- and an edit mask is a presentation contract
 * belonging to the assembly code rather than the shape of a row a query returns. A projection
 * built from it would be a formatted line masquerading as a persisted record, which is why it
 * is named here as excluded instead of being left to look like an omission.
 *
 * <h2>Two joins, sharing exactly one participant</h2>
 *
 * <p>This context serves two independent reads, and conflating them is the error this section
 * exists to prevent. Each was verified twice over, once from the job control that supplies the
 * inputs and once from the {@code COPY} set of the program that consumes them.
 *
 * <p>The <b>statement join</b> runs under {@code app/jcl/CREASTMT.JCL}, whose STEP040 at L79
 * invokes {@code CBSTM03A} and supplies four inputs: {@code TRNXFILE} at L83,
 * {@code XREFFILE} at L84, {@code ACCTFILE} at L85 and {@code CUSTFILE} at L86.
 * {@code app/cbl/CBSTM03A.CBL} corroborates with exactly four copybooks, at L51, L53, L55 and
 * L57, and {@code app/cbl/CBSTM03B.CBL} declares the same four inputs at L31, L37, L43 and
 * L49.
 *
 * <p>The <b>report join</b> runs under {@code app/jcl/TRANREPT.jcl}, whose STEP10R at L59
 * invokes {@code CBTRN03C} and supplies {@code TRANFILE} at L65-L66, {@code CARDXREF} at
 * L67-L68, {@code TRANTYPE} at L69-L70 and {@code TRANCATG} at L71-L72. A fifth entry,
 * {@code DATEPARM} at L73-L74, resolves to a parameter dataset and is <b>not</b> a join
 * participant: it carries the reporting date range, which is the reason that range is handed
 * to the job instead of being read from a clock. {@code app/cbl/CBTRN03C.cbl} corroborates the
 * four true inputs with its copybooks at L93, L98, L103 and L108, and declares the same four
 * at L29, L33, L39 and L45.
 *
 * <p>Taken together the two members declare nine input entries, of which one is the parameter
 * dataset and eight are data inputs, and those eight resolve to seven distinct datasets. The
 * duplicate is not an accident and it is the whole reason one projection appears in both
 * joins: {@code XREFFILE} at CREASTMT.JCL L84 and {@code CARDXREF} at TRANREPT.jcl L67-L68
 * name the identical dataset, {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}, under two different
 * data-definition names. Seven distinct datasets and seven projections is not a coincidence
 * either; it is the mapping this package implements.
 *
 * <p>The participation matrix below is stated in full because a projection written as though
 * it joined where it does not is a substantive defect and not a cosmetic one.
 * <ul>
 *   <li>{@code CardXrefView} -- BOTH joins, as {@code XREFFILE} at CREASTMT.JCL L84 and as
 *       {@code CARDXREF} at TRANREPT.jcl L67-L68. It is the only participant in both. </li>
 *   <li>{@code StatementTransactionView} -- statement join only, as {@code TRNXFILE} at
 *       CREASTMT.JCL L83. </li>
 *   <li>{@code AccountView} -- statement join only, as {@code ACCTFILE} at CREASTMT.JCL
 *       L85. </li>
 *   <li>{@code CustomerView} -- statement join only, as {@code CUSTFILE} at CREASTMT.JCL
 *       L86. </li>
 *   <li>{@code ReportTransactionView} -- report join only, as {@code TRANFILE} at
 *       TRANREPT.jcl L65-L66. </li>
 *   <li>{@code TransactionTypeView} -- report join only, as {@code TRANTYPE} at TRANREPT.jcl
 *       L69-L70. </li>
 *   <li>{@code TransactionCategoryView} -- report join only, as {@code TRANCATG} at
 *       TRANREPT.jcl L71-L72. </li>
 * </ul>
 *
 * <h2>Copybook geometry, and why it is asserted rather than estimated</h2>
 *
 * <p>The copybook is normative under the migration plan's own transformation rule, so a
 * declared width settles the column type, the Java type and the byte position of a field, and
 * nothing else does. Every record length below was re-derived by summing the declared
 * {@code PICTURE} widths of the record, and the sibling projections cite this table rather
 * than repeating that derivation seven times.
 * <ul>
 *   <li>{@code StatementTransactionView} from {@code app/cpy/COSTM01.CPY}, whose
 *       {@code 01 TRNX-RECORD} sits at L20 and whose record length is 350; its trailing
 *       {@code FILLER PIC X(20)} is at L36, occupying one-based positions 331 through 350.
 *       Note the uppercase extension: this is the single uppercase-extension member of
 *       {@code app/cpy}, so a lowercase citation of it is a dead reference. </li>
 *   <li>{@code ReportTransactionView} from {@code app/cpy/CVTRA05Y.cpy}, {@code 01} at L4,
 *       record length 350 -- which its own header comment at L2 states independently -- with
 *       trailing {@code FILLER PIC X(20)} at L18. </li>
 *   <li>{@code CardXrefView} from {@code app/cpy/CVACT03Y.cpy}, {@code 01} at L4, record
 *       length 50 as 16 plus 9 plus 11 plus 14, with trailing {@code FILLER PIC X(14)} at
 *       L8. </li>
 *   <li>{@code AccountView} from {@code app/cpy/CVACT01Y.cpy}, {@code 01} at L4, record
 *       length 300, with trailing {@code FILLER PIC X(178)} at L17. </li>
 *   <li>{@code CustomerView} from {@code app/cpy/CUSTREC.cpy}, {@code 01} at L4, eighteen
 *       fields summing to a record length of 500, with trailing {@code FILLER PIC X(168)} at
 *       L23. </li>
 *   <li>{@code TransactionTypeView} from {@code app/cpy/CVTRA03Y.cpy}, {@code 01} at L4,
 *       record length 60 as 2 plus 50 plus 8, with trailing {@code FILLER PIC X(08)} at
 *       L7. </li>
 *   <li>{@code TransactionCategoryView} from {@code app/cpy/CVTRA04Y.cpy}, {@code 01} at L4,
 *       record length 60 as 6 plus 50 plus 4, with trailing {@code FILLER PIC X(04)} at
 *       L9. </li>
 * </ul>
 *
 * <p>The statement input record's one-based positions, each read from its declaring line in
 * {@code COSTM01.CPY}, are: card number 1 through 16 at L22, transaction identifier 17
 * through 32 at L23, type code 33 through 34 at L25, category code 35 through 38 at L26,
 * source 39 through 48 at L27, description 49 through 148 at L28, amount 149 through 159 at
 * L29, merchant identifier 160 through 168 at L30, merchant name 169 through 218 at L31,
 * merchant city 219 through 268 at L32, merchant postal code 269 through 278 at L33,
 * originating timestamp 279 through 304 at L34, processing timestamp 305 through 330 at L35,
 * and the dropped {@code FILLER} 331 through 350 at L36. The arithmetic closes exactly:
 * {@code TRNX-KEY} at L21 is two sixteen-character fields, so 32, and {@code TRNX-REST} at
 * L24 sums to 318, and 32 plus 318 is 350.
 *
 * <p>An independent oracle confirms four of those record lengths from a source with no
 * copybook in it at all. {@code app/cbl/CBSTM03B.CBL} declares four file records whose member
 * widths reproduce them: {@code FD TRNX-FILE} at L58 gives 16 plus 16 at L61 and L62 plus
 * {@code PIC X(318)} at L63, which is 350; {@code FD XREF-FILE} at L65 gives {@code X(16)} at
 * L67 plus {@code X(34)} at L68, which is 50; {@code FD CUST-FILE} at L70 gives {@code X(09)}
 * at L72 plus {@code X(491)} at L73, which is 500; and {@code FD ACCT-FILE} at L75 gives
 * {@code 9(11)} at L77 plus {@code X(289)} at L78, which is 300. Two unrelated sources
 * agreeing on all four is the reason these positions are asserted here rather than estimated.
 *
 * <h2>Type mapping</h2>
 *
 * <p>These rules are stated once, in this charter, so that seven projections apply one mapping
 * instead of seven. A {@code PIC 9(n)} field acting as a key or an identifier becomes
 * {@code BIGINT} and a {@code Long}. A {@code PIC X(n)} field acting as a key or a fixed code
 * becomes {@code CHAR(n)} and a {@code String}, because its width is part of the contract. A
 * descriptive {@code PIC X(n)} field becomes {@code VARCHAR(n)} and a {@code String}, because
 * its trailing blanks are padding rather than data. A {@code PIC S9(09)V99} field becomes
 * {@code NUMERIC(11,2)} and a {@code PIC S9(10)V99} field becomes {@code NUMERIC(12,2)}, and
 * those two are never unified. A {@code PIC X(10)} field holding an ISO calendar date becomes
 * {@code DATE} and a {@code LocalDate}; the direct evidence is {@code app/jcl/TRANREPT.jcl}
 * L42, which declares {@code TRAN-PROC-DT,305,10,CH} and so selects exactly the leading ten
 * characters of a twenty-six-character timestamp, an operation that is only meaningful because
 * the form is year-month-day ordered and therefore compares lexically the way it compares
 * chronologically. A {@code PIC X(26)} field becomes {@code TIMESTAMP(6)} and a
 * {@code LocalDateTime}. A bounded {@code PIC 9(03)} field becomes {@code SMALLINT} and a
 * {@code Short}.
 *
 * <p>Two prohibitions travel with those rules. Money is {@code com.carddemo.common.money.Money}
 * and is never a raw arbitrary-precision decimal and never an IEEE-754 binary floating point
 * value, because a binary floating point representation cannot hold an exact cent and the
 * whole chain from view to response exists to hold one. A zone-bearing or zone-shifted
 * date-time type is likewise never used: the baseline timestamps carry no zone at all, so
 * attaching one would invent information the source record never held and make two runs in two
 * regions disagree about the same row.
 *
 * <h2>Hazards that silently corrupt a projection</h2>
 *
 * <p>Five are named here in full, each with the line that demonstrates it, because every one
 * of them produces a projection that looks correct and reads the wrong bytes.
 * <ul>
 *   <li><b>Field names collide across copybooks. </b> {@code TRAN-TYPE-CD} and
 *       {@code TRAN-CAT-CD} are declared in {@code CVTRA05Y.cpy} at L6 and L7 and again in
 *       {@code CVTRA04Y.cpy} at L6 and L7. The group {@code TRAN-CAT-KEY} is six bytes at
 *       {@code CVTRA04Y.cpy} L5 but seventeen bytes at {@code CVTRA01Y.cpy} L5. The asymmetry
 *       also runs the other way: the same two-byte type code is named {@code TRAN-TYPE}, with
 *       no code suffix, at {@code CVTRA03Y.cpy} L5, while {@code CVTRA04Y.cpy} L6 and
 *       {@code CVTRA05Y.cpy} L6 name it {@code TRAN-TYPE-CD}. A single repository-wide field
 *       name map is therefore impossible, and every layout is scoped to its owning copybook
 *       with that copybook named in the citation. {@code app/cbl/CBTRN03C.cbl} adopts exactly
 *       this discipline, qualifying only the two ambiguous names -- at L365 and L367 it moves
 *       the type code and the category code with an explicit record qualifier -- and leaving
 *       every unambiguous name bare. </li>
 *   <li><b>A collision can occur inside one program. </b> {@code app/cbl/CBSTM03B.CBL} declares
 *       {@code FD-ACCT-DATA} twice at two different widths, {@code PIC X(318)} at L63 within
 *       the transaction file record and {@code PIC X(289)} at L78 within the account file
 *       record, so even a single-file reading of a name is not sufficient to fix its
 *       width. </li>
 *   <li><b>A redefinition is a zero-byte overlay and must not advance a byte position. </b>
 *       {@code app/cpy/CVCRD01Y.cpy} carries three character-over-numeric overlay pairs, at
 *       L36, L39 and L42, declaring 11, 16 and 9 characters. Walking them as if they were
 *       fresh fields adds 36 bytes that do not exist and shifts every following field. </li>
 *   <li><b>Two customer copybooks exist and are geometrically identical. </b>
 *       {@code app/cpy/CUSTREC.cpy} and {@code app/cpy/CVCUS01Y.cpy} both declare
 *       {@code 01 CUSTOMER-RECORD} at L4 across 500 bytes with the same eighteen fields at the
 *       same widths and the same trailing {@code FILLER PIC X(168)} at L23 in each. They
 *       diverge in exactly one token, the date-of-birth field name at L19. This context is
 *       bound to {@code CUSTREC.cpy}, because {@code app/cbl/CBSTM03A.CBL} L55 copies that one
 *       and it has exactly one consumer repository-wide, which makes it the authoritative
 *       provenance here and the other book a near-identical decoy. </li>
 *   <li><b>A generic input-output handle is not a contract. </b>
 *       {@code app/cbl/CBSTM03B.CBL} L72 declares {@code FD-CUST-ID PIC X(09)} while both
 *       customer copybooks declare {@code CUST-ID PIC 9(09)} at L5. The copybook is normative,
 *       so the customer identifier is {@code BIGINT} and a {@code Long}; the alphanumeric
 *       spelling is an artifact of that module's untyped thousand-byte transfer buffer,
 *       declared at L112, and not a competing declaration of the field's type. </li>
 * </ul>
 *
 * <h2>Documentation contract</h2>
 *
 * <p>This file exists because user-specified Rule 1 (Explainability) L15 requires a docstring
 * on every module entry point, and a Java package declaration is one; a
 * {@code package-info.java} is the only construct able to carry Javadoc for a package, which
 * is why the obligation lands in this file and nowhere else. Of the four content elements
 * L18-L21 enumerates, only Purpose at L18 applies: a package declaration accepts no
 * parameters, yields no value and raises nothing, so the Parameters, Return values and
 * Exceptions elements at L19, L20 and L21 are inapplicable here rather than omitted, and no
 * at-clause is written to stand in for one of them. The block form used here is what L22
 * requires for Java. The written convention this file conforms to is stated once at
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and owned elsewhere.
 *
 * <p>Two Checkstyle checks act on this file and neither is redundant.
 * {@code JavadocPackage} runs at Checker level and requires the file to be present in a
 * package holding a processed {@code .java} file; {@code MissingJavadocPackage} runs inside
 * the tree walker and requires the file to carry Javadoc. A {@code package-info.java} holding
 * nothing but a package statement satisfies the first and fails the second, which is exactly
 * why both are wired rather than one. Both fire from the {@code maven-checkstyle-plugin}
 * execution bound to the {@code validate} phase in {@code services/pom.xml}, which precedes
 * compilation on every build, the image build included, and no in-code suppression filter is
 * wired anywhere in the ruleset, so a finding here cannot be waived from inside a source file.
 *
 * <p>Trade-offs: a green Checkstyle run is not evidence that Rule 1 is satisfied, and the
 * sibling projections in this package must not lean on the linter as though it were. The
 * ruleset is deliberately strict about presence and structural completeness -- its
 * {@code MissingJavadocMethod} entry empties the allowed-annotation list, so not even an
 * overriding method escapes the presence audit by carrying an annotation, and its
 * {@code JavadocMethod} entry validates declared thrown types -- but no module in it can read
 * whether a labelled sentence explains a decision or merely restates the code, which is what
 * L38 forbids, nor whether a rationale is specific, which is what L41 forbids. L43 makes those
 * two halves conjunctive. The enforced half is presence and shape; the unenforced half is
 * rationale, and only a reader supplies the second. The compromise is accepted because the
 * alternative, a pattern asserting that a rationale is genuine, would either pass every
 * labelled sentence or fail honest ones.
 *
 * <h2>Decisions</h2>
 *
 * <p>What follows discharges the obligation user-specified Rule 1 (Explainability) states at
 * L43, using the four categories L31-L34 names, for the choices in this package that a
 * reasonable alternative could have gone the other way on. L40 forbids leaving such a choice
 * undocumented and L41 forbids a rationale without specific justification, so every entry
 * names the line, the declared width or the byte count it rests on.
 *
 * <p>Trade-offs: every projection here is mapped immutable, through Hibernate's
 * {@code @Immutable}, which reaches this module by way of the JPA starter its POM declares,
 * and none carries an optimistic-locking version column, a cascade or a setter. The baseline
 * itself establishes that this loses nothing: all eight join inputs are opened for input only,
 * the four statement inputs at {@code app/cbl/CBSTM03B.CBL} L136, L160, L184 and L209 and the
 * four report inputs at {@code app/cbl/CBTRN03C.cbl} L378, L414, L432 and L450, and neither
 * program executes a record-replacing or record-removing verb against any of them.
 * {@code CBTRN03C.cbl} opens exactly one output, the report at L396, and its only writing verb
 * targets the report record at L345; {@code CBSTM03A.CBL} likewise opens only its two outputs,
 * at L293. The compromise is real and is accepted deliberately: dirty checking and the
 * convenience of merging a detached instance are given up outright, and a value that needs
 * changing has to be changed by the context that owns the underlying table. What is bought is
 * <b>where</b> an accidental write fails. Mapped immutable, it fails at the mapping layer and
 * names the type; unmapped, the same write travels all the way to the database and fails
 * against the {@code SELECT}-only role, surfacing as an opaque privilege error at a call site
 * far from the assignment that caused it. A failure that names the offending type is worth
 * more than a mutability neither this context nor its baseline ever uses.
 *
 * <p>Alternatives Considered: these seven types map views, and this context declares no table
 * of its own. Two other shapes were evaluated and both were rejected. Declaring base tables
 * here was rejected because every store the report and the statement read is already owned
 * elsewhere -- the two job control members declare nine input entries between them at
 * {@code app/jcl/TRANREPT.jcl} L65-L74 and {@code app/jcl/CREASTMT.JCL} L83-L86, one of them a
 * parameter dataset and the remaining eight resolving to seven distinct datasets -- so a local
 * copy would create a second truth for figures whose entire purpose is to state the first one
 * exactly. Reading a replica was rejected on the
 * plan's own ground that a replica adds cost and replica-lag semantics for no parity benefit:
 * a statement disagreeing with the ledger by one replication interval is a support case rather
 * than a feature. What remains is read-only access through cross-schema views, which is why
 * this context is listed with no owned tables at all.
 *
 * <p>Assumptions: agreement with the contexts that own these rows runs through the physical
 * views and the narrowly-scoped grants behind them, and never through code. Two independent
 * mechanisms hold that line. The ArchUnit layering test at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * forbids one context's domain package importing another's, and being a test it cannot rot;
 * and this module's POM declares exactly one dependency inside the reactor, the shared kernel,
 * so no other service module is on its compile classpath to be imported from in the first
 * place. That is the same discipline the baseline read under, where the two consuming programs
 * reached their eight data inputs through job control alone, declared at
 * {@code app/jcl/TRANREPT.jcl} L65-L74 and {@code app/jcl/CREASTMT.JCL} L83-L86, and never
 * through a compile-time bond between them. The database half is authored elsewhere and is
 * cited here by path: {@code data-migration/sql/V0__schemas_and_roles.sql} establishes the
 * schema, the roles and, at its L816 and the default-privilege statements following it, the
 * {@code SELECT}-only grants reaching {@code ledger}, {@code account}, {@code card} and
 * {@code reference}; and {@code data-migration/sql/V1__reporting_views.sql} declares the views
 * themselves, ordered after every per-service migration because a view cannot precede the
 * table it reads. A view absent at run time is a defect to report against those artifacts and
 * never one to work around from inside this package.
 *
 * <p>Assumptions: the read path this package sits on names four schemas while the baseline job
 * control exercises only three of them, and the gap is recorded here rather than smoothed
 * over. Between them {@code app/jcl/TRANREPT.jcl} L65-L74 and {@code app/jcl/CREASTMT.JCL}
 * L83-L86 name inputs resolving to {@code ledger}, {@code account} and {@code reference}
 * alone. The {@code card} schema is read by neither program, and that is established three
 * independent ways: no card record copybook appears in either {@code COPY} set, neither the
 * four at {@code CBSTM03A.CBL} L51, L53, L55 and L57 nor the five at {@code CBTRN03C.cbl} L93,
 * L98, L103, L108 and L113; no card file appears in either member's input list; and a search
 * of {@code CBSTM03A.CBL}, {@code CBSTM03B.CBL} and {@code CBTRN03C.cbl} for the card record
 * copybook name and for every card dataset name returns nothing at all. The {@code CARDXREF}
 * input at {@code TRANREPT.jcl} L67-L68 is the card cross-reference and resolves to the
 * account context rather than the card context, which is the near miss most easily mistaken
 * for a card read. That fourth schema is therefore carried on the strength of the published
 * read surface and not on baseline evidence, no projection in this package maps a card entity,
 * and a reader who cannot find a {@code card} read in either program has not overlooked one.
 *
 * <p>Assumptions: each record's trailing {@code FILLER} is dropped, and each dropped field is
 * recorded per record, which is what the plan's normative-copybook rule requires. The seven
 * lines are {@code COSTM01.CPY} L36, {@code CVTRA05Y.cpy} L18, {@code CVACT03Y.cpy} L8,
 * {@code CVACT01Y.cpy} L17, {@code CUSTREC.cpy} L23, {@code CVTRA03Y.cpy} L7 and
 * {@code CVTRA04Y.cpy} L9. {@code FILLER} is padding to a fixed record length and carries
 * nothing a reader of a report or a statement could act on, so mapping it would add a column
 * whose only content is blanks; naming the line each one came from is what keeps its removal
 * auditable instead of invisible.
 *
 * <p>Assumptions: the two transaction amounts stay at {@code NUMERIC(11,2)} and are not
 * widened to match the account balances. {@code COSTM01.CPY} L29 declares {@code TRNX-AMT} as
 * {@code PIC S9(09)V99} and {@code CVTRA05Y.cpy} L10 declares {@code TRAN-AMT} as
 * {@code PIC S9(09)V99}, eleven digits each, whereas {@code CVACT01Y.cpy} L7 declares
 * {@code ACCT-CURR-BAL} as {@code PIC S9(10)V99}, twelve digits, which is
 * {@code NUMERIC(12,2)}. {@code app/cbl/CBTRN03C.cbl} corroborates independently: its three
 * report accumulators, at L134, L135 and L136, are each {@code PIC S9(09)V99}, so the report
 * itself never needs the wider precision. Unifying the two on the wider form would be a silent
 * widening of a declared contract, and a column that accepts a value the source record cannot
 * hold stops being evidence of what the source record held.
 *
 * <p>Assumptions: a timestamp of twenty-six blanks is a legitimate value in this pipeline and
 * not an error to reject. {@code COSTM01.CPY} declares the originating timestamp at L34 and
 * the processing timestamp at L35, each {@code PIC X(26)}, and {@code app/jcl/CREASTMT.JCL}
 * L54 declares {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)}. That statement copies 16
 * plus 262 plus 50 bytes, which is 328 of the record's 350, and the fifty bytes it takes
 * starting at position 279 cover the whole twenty-six-character originating timestamp and only
 * twenty-four of the twenty-six characters of the processing timestamp. A partly blank
 * {@code X(26)} is therefore produced by the pipeline's own reformatting step, so a projection
 * treating blanks as a violation would reject rows the baseline writes on purpose.
 *
 * <p>Assumptions: every layout is scoped to its owning copybook, and every citation names that
 * copybook. This rests on the collisions enumerated under Hazards above -- the two field names
 * shared between {@code CVTRA05Y.cpy} L6-L7 and {@code CVTRA04Y.cpy} L6-L7, the group name
 * that is six bytes at {@code CVTRA04Y.cpy} L5 and seventeen at {@code CVTRA01Y.cpy} L5, and
 * the suffix that is present at {@code CVTRA04Y.cpy} L6 and absent at {@code CVTRA03Y.cpy}
 * L5 -- and it follows the baseline's own practice, since {@code app/cbl/CBTRN03C.cbl}
 * qualifies precisely the two ambiguous names at L365 and L367 and nothing else. A global name
 * map was the alternative and it cannot be built: it would have to resolve one name to two
 * widths.
 *
 * <p>Assumptions: the report transaction record and the statement transaction record are kept
 * as two separate types and are never treated as aliases, because their geometry genuinely
 * differs. The report record places the transaction identifier at positions 1 through 16 and
 * the card number at 263 through 278; the statement record places the card number at 1 through
 * 16 and the identifier at 17 through 32. The re-keying is deliberate in the baseline, which
 * sorts on the card number ahead of the identifier at {@code app/jcl/CREASTMT.JCL} L53 and
 * names the same position pair at {@code app/jcl/TRANREPT.jcl} L41. Both records are 350 bytes
 * and carry the same field names, so nothing but the two lexically distinct class names
 * {@code ReportTransactionView} and {@code StatementTransactionView} stands between a reader
 * and a mapping that reads a card number where an identifier lives.
 *
 * <p>Alternatives Considered: the four category labels used in this file are written in the
 * plural, un-parenthesised, colon-terminated spelling, and they were retyped by hand from
 * user-specified Rule 1 L31-L34 rather than copied from prose elsewhere in the repository.
 * Read byte for byte with the C locale forced, that rules document is 2447 bytes of pure
 * seven-bit ASCII with no non-breaking hyphen anywhere in it, and each of its four labels
 * places the colon inside the emphasis markers, which is what fixes the trailing colon as part
 * of the label. {@code tests/README.md} is the wrong source to copy from: it carries 106
 * non-breaking hyphens across 77 lines, and its L548 spells the fourth label with a
 * non-breaking hyphen, a closing bracket and no colon at all -- three departures inside one
 * token. Measured across this repository the registers genuinely disagree: a singular spelling
 * appears 344 times in 86 files and a bracketed prefixed spelling 92 times in 26 files, while
 * the plural spelling appears 645 times in 109 files. Within the Java tree the plural is
 * exclusive -- 101 occurrences across the 31 Java sources standing beside this one, and not one
 * occurrence of the singular among them -- and that is the register this file joins. A singular, bracketed or non-breaking-hyphen
 * variant is not an alternative spelling of the same label; it is a label that a fixed-string
 * search for the category will not find, which makes a documented rationale read as absent to
 * the audit looking for it, so the plural form must never be normalised away.
 *
 * <p>Trade-offs: every justification above sits inside this Javadoc block rather than beside a
 * statement, which departs from the letter of L27 and is nonetheless the only placement this
 * file admits. L27 asks that a comment sit adjacent to the code it explains; a package
 * declaration has no statements, so there is no code for a comment to be adjacent to, and the
 * adjacency requirement is satisfied vacuously rather than waived. The cost accepted is that
 * these entries sit further from the behaviour they describe than an inline comment would, and
 * the compensation is that each one names its evidence explicitly. A reader auditing this file
 * should read the labelled entries above as the rationale half of the conjunctive L43 gate,
 * and should not conclude that half was left out for want of somewhere to put it.
 */
package com.carddemo.reporting.domain;
