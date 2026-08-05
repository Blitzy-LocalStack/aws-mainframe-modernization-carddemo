/**
 * Root package of the transaction-service module, the LEDGER bounded context of
 * the CardDemo mainframe-to-microservices migration.
 *
 * <p><b>Purpose.</b> This package roots the Java re-expression of the CardDemo
 * transaction ledger: the four CICS transactions that list, view and add
 * transactions and that take a bill payment, migrated from z/OS COBOL running
 * under CICS against VSAM onto Spring Boot. It owns the PostgreSQL schema
 * {@code ledger} and the four tables within it, named {@code transactions},
 * {@code daily_transactions}, {@code transaction_rejects} and
 * {@code transaction_category_balances}. The COBOL baseline is the
 * specification, so the code beneath this package encodes those rules rather
 * than redefining them, and any intentional divergence is recorded in the
 * migration traceability matrix rather than introduced silently.
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> A package
 * declaration accepts no parameters, returns no value and raises nothing, so
 * this charter carries no parameter, return or exception at-clause. The
 * inapplicability is declared rather than left silent, because the
 * Explainability rule's line 39 forbids a docstring that omits parameters,
 * return values or purpose, and a reader has to be able to tell a declared
 * inapplicability from an oversight.
 *
 * <p>Assumptions: fabricating those at-clauses would not merely add noise.
 * Javadoc has no parameter, return or exception concept for a package, and the
 * repository ruleset audits at-clause bodies for emptiness, so an invented
 * at-clause would either be discarded or reported as empty. The rule enumerates
 * four docstring elements at its lines 18 to 21 -- Purpose, Parameters, Return
 * values, and Exceptions or errors -- and exactly one of the four has a subject
 * in this compilation unit. The paragraph above accounts for the other three.
 *
 * <h2>Baseline provenance</h2>
 *
 * <p>Every behaviour implemented beneath this package traces to one of the
 * artifacts below. They are reference material, read but never modified. The
 * transaction identifiers and screen names are quoted from the transaction
 * inventory in the repository root {@code README.md}, lines 278 to 282, so that
 * the names used here and the names an operator already knows are the same
 * names:
 *
 * <ul>
 *   <li>{@code CT00} / {@code app/cbl/COTRN00C.cbl}, 699 lines --
 *       "Transaction List", the paged browse screen</li>
 *   <li>{@code CT01} / {@code app/cbl/COTRN01C.cbl}, 330 lines --
 *       "Transaction View", the single-record detail screen</li>
 *   <li>{@code CT02} / {@code app/cbl/COTRN02C.cbl}, 783 lines --
 *       "Transaction Add", the capture screen</li>
 *   <li>{@code CB00} / {@code app/cbl/COBIL00C.cbl}, 572 lines --
 *       "Bill Payment", the balance-affecting payment screen</li>
 * </ul>
 *
 * <p>Assumptions: the screen name at line 279 is "Transaction View". The
 * plausible mis-citation is "Transaction Detail", which reads naturally beside
 * a detail endpoint and appears nowhere in the inventory. The inventory name is
 * the one already in an operator's vocabulary, so it is the one used here.
 *
 * <p>Two nearby programs are deliberately absent from that list, and both
 * absences are recorded because each one is easy to assume into this context:
 *
 * <ul>
 *   <li>{@code CR00} / {@code CORPT00C}, "Transaction Reports" at
 *       {@code README.md} line 281, sits between {@code CT02} and {@code CB00}
 *       in the inventory and so reads as a fifth transaction of this context.
 *       It belongs to the reporting bounded context, which owns no TABLE of its
 *       own -- its {@code reporting} schema holds views and nothing else, and is
 *       owned in the database by {@code carddemo_reporting_owner}, a role created
 *       {@code NOLOGIN} -- and which reaches these tables through those
 *       read-only cross-schema views under a role holding {@code SELECT} on the
 *       views alone.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl}, the nightly posting program, writes three
 *       of the four tables this context owns and is nonetheless the batch
 *       context's program. It is read here for the schema contract only: for
 *       the reject record layout and the posted-transaction field set. The two
 *       modules agree through the physical schema and never through code, so
 *       neither imports a type from the other.</li>
 * </ul>
 *
 * <p>The normative record layouts are {@code app/cpy/CVTRA05Y.cpy},
 * {@code app/cpy/CVTRA06Y.cpy} and {@code app/cpy/CVTRA01Y.cpy}. They are
 * deliberately not restated field by field here; they are single-sourced from
 * the copybooks into {@code domain}, mirroring the COBOL convention of
 * resolving every layout through one compiler include path instead of
 * duplicating it, which the repository documents at {@code tests/README.md}
 * lines 540 to 542.
 *
 * <h2>Subpackage map and the layering contract</h2>
 *
 * <p>Seven subpackages sit beneath this one, and the layer each occupies is a
 * contract rather than a filing convention:
 *
 * <ul>
 *   <li><b>{@code api}</b> -- the REST layer. It validates its input and
 *       delegates, and holds no business rule. Two controllers:
 *       {@code TransactionController} and {@code BillPaymentController}.</li>
 *   <li><b>{@code service}</b> -- the business rules, transcribed paragraph by
 *       paragraph from the COBOL: {@code TransactionListService},
 *       {@code TransactionAddService} and {@code BillPaymentService}.</li>
 *   <li><b>{@code repository}</b> -- data access, including the
 *       keyset-paginated queries that replace the CICS browse.</li>
 *   <li><b>{@code domain}</b> -- the JPA entities, one per copybook record:
 *       {@code Transaction}, {@code DailyTransaction},
 *       {@code TransactionReject} and {@code TransactionCategoryBalance}.</li>
 *   <li><b>{@code dto}</b> -- the request and response shapes, field for field
 *       from the copybook and symbolic-map layouts.</li>
 *   <li><b>{@code mapper}</b> -- the anti-corruption layer, and the only place
 *       copybook representation concerns may appear.</li>
 *   <li><b>{@code config}</b> -- Spring wiring. Three classes:
 *       {@code SecurityConfig}, {@code OpenApiConfig} and
 *       {@code DataSourceConfig}.</li>
 * </ul>
 *
 * <p>Trade-offs: confining fixed-width padding, sign overpunch, {@code FILLER},
 * account-number masking and the baseline's field spellings to {@code mapper}
 * costs one extra hop on every read and every write, and it means a field's
 * wire name and its column name are reconciled in a third place rather than on
 * the entity itself. What it buys is that {@code domain} and {@code service}
 * never see a copybook concern, so a reader chasing a padding or masking
 * question has exactly one package to open. The alternative of annotating the
 * entities directly was rejected: it spreads representation decisions across
 * every entity and leaves no single site where a masking decision can be
 * justified beside the code that performs it.
 *
 * <p>Assumptions: {@code mapper} is also the one package here that must never
 * be exempted from the documentation gate. It reads as generated code and is
 * not, and the ruleset's companion suppression file names a mapper package
 * among the entries it forbids for exactly that reason.
 *
 * <h2>The count canon</h2>
 *
 * <p>This module holds eight Java packages -- this root and the seven above --
 * so it holds exactly eight package charter files, every one of them at this
 * folder or deeper. The figure is recorded so that a later reader can tell a
 * charter that is missing from a charter that was never intended, and so that
 * the negative boundary near the foot of this file rests on arithmetic that can
 * be re-checked rather than on an argument that has to be re-made.
 *
 * <h2>Shared kernel: the Java equivalent of one copybook include path</h2>
 *
 * <p>Transformation rule T2 governs imports here: one former {@code COPY}
 * statement becomes exactly one type import, always from the package that owns
 * that contract. Shared concerns are consumed from {@code com.carddemo.common}
 * and are never re-declared in this module -- money and its wire form, the
 * copybook record codecs, the problem shape and the structured abend detail,
 * correlation-id propagation and the keyset page envelope, the group-claim
 * conversion, the metric tag set, the timestamp form, and the date-edit and
 * field-flag validators. The eight subpackages holding them are {@code money},
 * {@code codec}, {@code error}, {@code web}, {@code security},
 * {@code observability}, {@code time} and {@code validation}.
 *
 * <p>Assumptions: {@code MetricsConfig} lives under
 * {@code com.carddemo.common.observability}, not under any package named
 * {@code config}. Looking for it by the name of its layer rather than by the
 * name of its concern is the predictable wrong turn, because this module does
 * have a {@code config} package and it holds three unrelated classes.
 *
 * <p>Refactoring Rationale: re-declaring a shared type locally is the failure
 * this rule exists to prevent. The baseline compiles every program against a
 * single copybook include path, so a layout has one definition and cannot drift
 * between two programs; the repository imposes the same discipline on its own
 * COBOL tests at {@code tests/README.md} lines 540 to 542, which resolve record
 * layouts through {@code cobc -I app/cpy} and never duplicate one. A second
 * local copy of a money type or a sign-overpunch codec would reintroduce
 * precisely the drift that include path forecloses, and the drift would be
 * silent, because both copies would go on compiling.
 *
 * <h2>Boundaries this context does not cross</h2>
 *
 * <p>This context neither owns nor imports {@code Account}, {@code Card},
 * {@code CardXref}, {@code Customer} or {@code User}. Cross-service
 * {@code domain} imports are forbidden outright, and the prohibition belongs to a
 * build rule rather than to a convention: the shared kernel's layering rules,
 * selected by the simple name {@code LayeringRulesTest}, are evaluated against
 * this module's own compiled classes, and they additionally keep AWS and web
 * types out of {@code domain} and binary floating-point types out of the money
 * path. Cross-context data is reached over HTTP, never by import.
 *
 * <p>Assumptions: authored once is not executed everywhere, and the difference is why this module
 * carries two declarations of its own rather than relying on inheritance. Maven hands a dependency's
 * MAIN classes to its consumers and never its test classes, so delivery of that rule class takes
 * three cooperating declarations, and all three are present in this reactor:
 * {@code services/common-lib/pom.xml} binds {@code maven-jar-plugin}'s {@code test-jar} goal at
 * {@code process-test-classes}, narrowed by an include to the architecture directory alone; this
 * module's POM declares that artifact with {@code <type>test-jar</type>} at test scope; and the
 * {@code architecture-rules} Surefire execution in {@code services/pom.xml} names
 * {@code com.carddemo:common-lib} in {@code dependenciesToScan}, so the class is collected from that
 * artifact and run against THIS module's own compiled classes. This module additionally declares the
 * ArchUnit engine at test scope in its own POM, because test scope is not transitive and the
 * assertion API has to resolve wherever the class executes. Those four declarations are the whole of
 * the delivery mechanism, and they are what makes one authored rule class enforce this boundary in
 * every module instead of in the one module that declares it.
 *
 * <p>Assumptions: the baseline reaches other contexts' records directly,
 * because one CICS region shares one file set, and two of the four programs
 * migrated here do exactly that. {@code COTRN02C} declares the account master,
 * the card cross-reference and its alternate index at lines 40 to 42 and reads
 * the alternate index at lines 578 and 579. {@code COBIL00C} declares the
 * account master and the alternate index at lines 41 and 42, reads the account
 * record at lines 345 and 346, and REWRITES it at lines 379 and 380. Bill
 * payment therefore writes across what is now a context boundary. A shared file
 * handle has no equivalent here, so that write becomes a call to the owning
 * context, and every pooled connection is pinned to the {@code ledger} search
 * path, which makes the boundary a property of the connection rather than a
 * naming habit.
 *
 * <h2>Persistence boundary and the record contracts</h2>
 *
 * <p>The authoritative column list is the Flyway migration this module ships at
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql},
 * not this overview. What is recorded here is the provenance of the four tables
 * and the widths every offset in the module depends on:
 *
 * <ul>
 *   <li>{@code transactions} from {@code app/cpy/CVTRA05Y.cpy} lines 4 to 18,
 *       {@code TRAN-RECORD}, 350 bytes. {@code TRAN-CARD-NUM PIC X(16)} at line
 *       15 occupies one-based bytes 263 to 278, and
 *       {@code TRAN-PROC-TS PIC X(26)} at line 17 occupies 305 to 330.</li>
 *   <li>{@code daily_transactions} from {@code app/cpy/CVTRA06Y.cpy} lines 4 to
 *       18, {@code DALYTRAN-RECORD}, 350 bytes, structurally identical to
 *       {@code CVTRA05Y} field for field with only the field-name prefix
 *       differing.</li>
 *   <li>{@code transaction_category_balances} from {@code app/cpy/CVTRA01Y.cpy}
 *       lines 4 to 10, {@code TRAN-CAT-BAL-RECORD}, 50 bytes. Line 5 declares
 *       {@code TRAN-CAT-KEY} as a GROUP over {@code TRANCAT-ACCT-ID PIC 9(11)},
 *       {@code TRANCAT-TYPE-CD PIC X(02)} and {@code TRANCAT-CD PIC 9(04)} at
 *       lines 6 to 8, a 17-byte composite, so the target key is composite
 *       too.</li>
 *   <li>{@code transaction_rejects} from {@code app/cbl/CBTRN02C.cbl} line 176:
 *       a 350-byte record image at line 177 followed by an 80-byte trailer at
 *       line 178, which lines 181 and 182 resolve into a four-digit reason code
 *       and a 76-character description, for 430 bytes in total.</li>
 * </ul>
 *
 * <p>Assumptions: that 430-byte figure is corroborated independently by the job
 * control allocating the reject stream, {@code app/jcl/POSTTRAN.jcl} line 36,
 * which declares {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)}. The reject row
 * keeps the 350-byte image as a fixed-width character column with the code and
 * the description beside it, and the image is never decomposed into fields. A
 * rejected record has already failed validation, so its bytes cannot be trusted
 * to parse; retaining them verbatim is what leaves a re-drive possible once the
 * cause is addressed.
 *
 * <p>Assumptions: the two byte offsets above are agreed by three independent
 * sources, which is why every offset-dependent decision in this module may rest
 * on them. Summing the declared field widths of {@code CVTRA05Y} yields 263 and
 * 305. The sort control at {@code app/jcl/TRANREPT.jcl} lines 41 and 42
 * declares {@code TRAN-CARD-NUM,263,16,ZD} and {@code TRAN-PROC-DT,305,10,CH}
 * in one-based positions. The alternate index at {@code app/jcl/TRANIDX.jcl}
 * line 27 declares {@code KEYS(26 304)}, a space-separated length-and-offset
 * pair in zero-based positions, and line 30 of that same job independently
 * declares {@code RECORDSIZE(350,350)}.
 *
 * <p>Assumptions: {@code TRANREPT.jcl} line 41 types the card number
 * {@code ZD}, zoned decimal, for the sort utility's benefit, while the copybook
 * declares it {@code PIC X(16)}. Transformation rule T1 makes the copybook
 * normative, so the target carries a fixed-width character column. Taking the
 * sort control as the type authority would turn a 16-character identifier into
 * a number and discard any leading zero it carries.
 *
 * <p>Assumptions: {@code FILLER} is dropped from every record, and each drop is
 * recorded in the owning entity's own Javadoc rather than here --
 * {@code CVTRA05Y} line 18 and {@code CVTRA06Y} line 18 each drop
 * {@code PIC X(20)}, and {@code CVTRA01Y} line 10 drops {@code PIC X(22)}.
 * Those bytes pad a record to its fixed length and carry no value, so a column
 * for them would store padding. The drop is nonetheless recorded per record,
 * because it is the one transformation that makes the target row narrower than
 * the source record, and a reader reconciling 350 bytes against a column list
 * needs the difference accounted for.
 *
 * <p>Assumptions: no field is renamed in this module. The wider migration does
 * rename three baseline field names in its own target columns, and all three of
 * those belong to other contexts, so a reader arriving here expecting a
 * spelling change will find none and every field name beneath this package
 * matches its copybook exactly.
 *
 * <h2>Money is exact fixed point, at every hop</h2>
 *
 * <p>Transformation rule T3 pins one representation per layer and admits no
 * exception: {@code NUMERIC(p,2)} in SQL, {@code BigDecimal} carried at scale 2
 * with {@code RoundingMode.HALF_UP} through {@code Money} from
 * {@code com.carddemo.common.money}, and a JSON <em>string</em> on the wire
 * through {@code MoneyModule}. Neither binary floating-point type may appear
 * anywhere in the money path, and a JSON number may not either; the prohibition
 * is asserted by the inherited ArchUnit rules rather than left to review.
 *
 * <p>Assumptions: the money fields of these records are zoned decimal with sign
 * overpunch, not packed decimal, so the sign travels in the final byte of the
 * digits rather than in a nibble of its own. {@code TRAN-AMT} is declared
 * {@code PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy} line 10, and the seed
 * data shows the encoding at byte level: in
 * {@code app/data/ASCII/dailytran.txt} the amount field of the first record
 * reads {@code 0000005047G}, whose trailing {@code G} carries both the digit 7
 * and a positive sign, giving +504.77, while the second record's amount field
 * ends in a right-brace character, which carries the digit 0 together with a
 * negative sign, giving -919.00. A decoder that treats either final byte as an
 * ordinary digit reads the amount wrongly and loses the sign entirely.
 *
 * <p>Trade-offs: transporting money as a JSON string costs every client an
 * explicit parse and makes the payload marginally larger. A JSON number was
 * rejected because most clients parse one into a binary double, and a value
 * such as 504.77 has no exact binary representation, so the exactness the
 * database and the domain both preserve would be lost at the last hop -- the
 * one hop the user actually sees. Transformation rule T4 covers the same
 * concern for arithmetic order, and no money calculation in this module may
 * reorder a product and a quotient, because doing so changes the final cent.
 *
 * <h2>Keyset pagination, never offset</h2>
 *
 * <p>Transformation rule T5 collapses the CICS browse verbs -- start browse,
 * read next, read previous and end browse -- into one keyset-paginated query
 * per list, answered as {@code PageResponse} from
 * {@code com.carddemo.common.web}. Paging forward selects keys strictly greater
 * than the last key of the page just shown, ordered ascending, with a limit of
 * one more than the page size; paging backward selects keys strictly less than
 * the first key of that page, ordered descending.
 *
 * <p>Assumptions: fetching one extra row to discover whether a further page
 * exists is not an invention of this migration, it is what the baseline does.
 * {@code app/cbl/COTRN00C.cbl} line 297 loops until its index reaches 11,
 * filling exactly ten screen rows at lines 298 to 302. Lines 306 and 307 then
 * advance the page number, line 308 performs an ELEVENTH read, and the outcome
 * of that read alone sets the next-page flag at lines 310 and 312. The extra
 * read is the whole mechanism, and the envelope carries its result as a
 * boolean.
 *
 * <p>Alternatives Considered: offset pagination is shorter to express and is
 * rejected. Under concurrent inserts an offset silently skips and repeats rows,
 * because it is counted against a result set that has changed since the
 * previous page was served, whereas browsing by key is unaffected. Choosing an
 * offset would therefore change observable behaviour and not merely the
 * implementation. The paging controls bind to the previous-page and next-page
 * availability the envelope reports, never to a page number.
 *
 * <h2>Three 26-character timestamp forms, kept distinct</h2>
 *
 * <p>A {@code PIC X(26)} timestamp becomes {@code TIMESTAMP(6)} in SQL and
 * {@code LocalDateTime} in Java, rendered by {@code TimestampFormatter} from
 * {@code com.carddemo.common.time} to exactly the 26-character
 * {@code YYYY-MM-DD HH:MM:SS.mmmmmm} form. Microsecond precision is what makes
 * the declared width exact rather than approximate:
 * {@code app/cpy/CSDAT01Y.cpy} lines 42 to 55 assemble the group from four date
 * and time components and six separators, and the separator at position 11 is a
 * space at line 48 while the separator at position 20 is a period at line 54.
 *
 * <p>Assumptions: both of those separators survive an {@code INITIALIZE},
 * because that verb does not touch {@code FILLER} items and each separator is
 * declared as {@code FILLER} carrying a {@code VALUE} clause. A reader who
 * assumes the group is blanked to 26 spaces would predict the wrong bytes.
 *
 * <p>Assumptions: three distinct forms coexist in the baseline, and they are
 * carried across separately rather than folded into one:
 *
 * <ul>
 *   <li>{@code COTRN02C} writes a DATE-ONLY value, space-padded. Lines 464 and
 *       465 move two map fields into the 26-byte originating and processing
 *       timestamps, and {@code app/cpy-bms/COTRN02.CPY} declares both of those
 *       map fields {@code PIC X(10)} at lines 102 and 108, so ten characters of
 *       date are followed by sixteen spaces.</li>
 *   <li>{@code COBIL00C} writes a full timestamp with ZERO microseconds: lines
 *       264 and 265 place the date and the time into the group, and line 266
 *       moves zeros into the microsecond component.</li>
 *   <li>{@code CBTRN02C}, the batch context's posting program, writes the
 *       database-format timestamp at lines 437 and 438.</li>
 * </ul>
 *
 * <p>Trade-offs: normalising all three onto one fully-populated form would make
 * the column uniform, and it is rejected. The stored value is observable, and
 * two of the three forms are distinguishable from a fully-populated one by
 * inspection, so collapsing them would change what a reader of the ledger sees
 * for a transaction captured on a screen as against one posted overnight. The
 * cost accepted is that a consumer of the timestamp column cannot assume a
 * populated time-of-day.
 *
 * <h2>Message width regimes, and the one that governs this module</h2>
 *
 * <p>Four message widths exist in the baseline and they are not
 * interchangeable. The one this module's error payload follows is 75, from
 * {@code app/cpy/CVCRD01Y.cpy}: {@code CCARD-ERROR-MSG PIC X(75)} at line 28
 * and {@code CCARD-RETURN-MSG PIC X(75)} at line 29. That is the catalog form
 * for the message line the problem shape carries.
 *
 * <p>Assumptions: line 30 of that same copybook declares the message-off
 * condition as {@code LOW-VALUES} and not as spaces, and the two are not
 * interchangeable. {@code LOW-VALUES} maps to an absent, null message, whereas
 * 75 spaces is a message that is present and happens to be blank. A client
 * rendering a blank band for one and nothing for the other would diverge on the
 * very distinction the baseline draws.
 *
 * <p>Assumptions: the other three widths are recorded so that a reader does not
 * reach for the wrong one. 50 is the common-message form at
 * {@code app/cpy/CSMSG01Y.cpy} lines 18 to 21, trailing padding included. 72 is
 * the abend message at {@code app/cpy/CSMSG02Y.cpy} line 28, beside a
 * four-character code at line 22, an eight-character culprit at line 24 and a
 * 50-character reason at line 26; that abend structure is carried by common-lib
 * and not by this module, and the copybook is only 35 lines long, so a citation
 * beyond that is out of range. 78 is the width of the screen error field
 * itself, declared {@code PIC X(78)} in the symbolic maps at
 * {@code app/cpy-bms/COBIL00.CPY} lines 78 and 140,
 * {@code app/cpy-bms/COTRN00.CPY} lines 372 and 728, and both
 * {@code app/cpy-bms/COTRN01.CPY} and {@code app/cpy-bms/COTRN02.CPY} at lines
 * 144 and 272. Those file names are upper case on disk, extension included, so
 * a lower-case path does not resolve.
 *
 * <p>Assumptions: 80 is NOT a regime, and the near miss is worth naming because
 * it looks like a fifth width. {@code app/cbl/COTRN01C.cbl} line 38 declares
 * {@code WS-MESSAGE PIC X(80)}, which is an internal work field: line 217 moves
 * it into the 78-character screen field, so COBOL truncates two bytes on the
 * way out and 80 never reaches an interface. Transformation rule T8 keeps every
 * user-visible string verbatim, which is why the width a string is stored in
 * and the width it is emitted in both have to be known.
 *
 * <h2>No session state crosses a request boundary</h2>
 *
 * <p>The baseline is pseudo-conversational: a CICS task ends at every screen
 * turn, so all continuity between turns lives in one passed structure,
 * {@code CARDDEMO-COMMAREA} at {@code app/cpy/COCOM01Y.cpy} lines 19 to 44,
 * shared by every online program. That structure does not travel here. It
 * decomposes four ways, and the fourth is the one that vanishes:
 *
 * <ul>
 *   <li>Navigation -- the from and to transaction and program fields at lines
 *       21 to 24, plus the last map and mapset at lines 43 and 44 -- becomes
 *       client-side routing. No server-side "next program" field exists.</li>
 *   <li>Identity -- {@code CDEMO-USER-ID} at line 25 and
 *       {@code CDEMO-USER-TYPE} at line 26, with its administrator and user
 *       condition names at lines 27 and 28 -- becomes claims on a validated
 *       token, converted to authorities by {@code JwtRoleConverter}.</li>
 *   <li>Selection context -- {@code CDEMO-CUST-ID} at line 33,
 *       {@code CDEMO-ACCT-ID} at line 38 and {@code CDEMO-CARD-NUM} at line 41
 *       -- becomes path and query parameters, which is what makes each request
 *       self-describing and therefore independently authorizable.</li>
 *   <li>The re-entry discriminator {@code CDEMO-PGM-CONTEXT} at line 29, with
 *       its enter and re-enter condition names at lines 30 and 31, vanishes
 *       entirely. A stateless handler answering with a per-field error array
 *       has no first-entry-versus-re-entry distinction left to make, which is
 *       also why field-level error presentation here is driven purely by the
 *       response body and never by a remembered turn count.</li>
 * </ul>
 *
 * <p>Assumptions: the browse cursor is NOT part of that shared structure and
 * decomposes separately. {@code app/cbl/COTRN00C.cbl} includes the shared
 * structure at line 61 and then appends its own block at lines 62 to 70, whose
 * first and last keys are a single scalar {@code PIC X(16)} pair at lines 63
 * and 64 rather than a composite. That pair, the page number and the next-page
 * flag become the fields of {@code PageResponse}, so the cursor lands in the
 * response envelope while the shared structure lands in the four places above.
 * Reading the cursor as part of the shared structure would misplace it into
 * identity or navigation, where nothing would page.
 *
 * <p>Assumptions: moving identity onto a signed token changes the security
 * posture and is not merely a change of transport. The passed structure is
 * storage the client hands back on the following turn, so a client could in
 * principle assert its own user type; a signed group claim cannot be asserted
 * by the client at all. The baseline trusts the returned structure, the Java
 * trusts the signature, and the divergence is documented.
 *
 * <p>Trade-offs: holding nothing between requests -- no sticky session and no
 * server-side session store -- lets any task serve any request, which is what
 * makes horizontally-scaled tasks behind a load balancer viable at all. The
 * cost accepted is that every request carries its own selection context and is
 * authorized from scratch, which is more work per request than reading a field
 * from a structure the previous turn had already populated.
 *
 * <p>Assumptions: account numbers are masked to their last four digits in every
 * response this module serves, and no card verification value is returned by
 * any endpoint. Both happen in {@code mapper}, which is the reason that package
 * is the one place a representation concern is permitted to appear.
 *
 * <h2>Parity evidence, and the limit of it</h2>
 *
 * <p>All four migrated programs are online programs, and the repository records
 * at {@code tests/README.md} lines 83 to 85 that the online programs cannot be
 * run end to end without a CICS runtime, which the runner does not have, so
 * only their extractable field-validation logic is unit-tested. No
 * golden-master oracle exists for this module, and none is claimed. Parity here
 * rests on two things instead: validation logic transcribed faithfully from the
 * COBOL paragraphs, and the record contracts recorded above.
 *
 * <p>Assumptions: the batch posting program that shares this schema does have
 * golden-master coverage, and it belongs to the batch context rather than to
 * this module. Its coverage must not be read as coverage of these four screens,
 * which is the mistake the shared schema invites.
 *
 * <p>Assumptions: the repository's COBOL suite grades its outcome on a
 * mainframe condition-code rubric in which a warning-level result is the green
 * state, and that rubric belongs to that suite alone.
 * {@code app/cbl/CBTRN02C.cbl} line 230 sets a non-zero return code of its own
 * whenever it rejects a record, which is the same graded idea expressed in a
 * program. This module's build is binary: the compiler, the documentation gate
 * and the test runner each pass or fail, and no result here is ever described
 * as warning-level green.
 *
 * <h2>The documentation contract this subtree is held to</h2>
 *
 * <p>One user-specified rule governs this migration: Explainability. Every
 * compilation unit beneath this package carries a docstring on every function,
 * class and module entry point, stating the elements the rule enumerates at its
 * lines 18 to 21, in the Javadoc form its line 22 names for Java. A package
 * declaration is the language's module entry point, which is why this file
 * exists.
 *
 * <p>Every non-obvious decision carries one of exactly four labels, worded as
 * the rule words them at its lines 31 to 34:
 *
 * <pre>
 * Alternatives Considered:  What other approaches were evaluated and why this one was chosen
 * Refactoring Rationale:    When replacing existing code, what was wrong with the old approach
 * Assumptions:              What external contracts, data formats, or behaviors this code depends on
 * Trade-offs:               What compromises were accepted (performance vs. readability,
 *                           simplicity vs. flexibility, etc.)
 * </pre>
 *
 * <p>Assumptions: the plural, unparenthesised, colon-terminated forms above are
 * the only accepted spelling of the four labels, and they are mandatory in every
 * language and every file of the migration trees -- Java, TypeScript, Python,
 * HCL, SQL, Dockerfile, YAML and shell alike. The rule words its four categories
 * in the plural, and its line 43 makes that wording the sentence this tree is
 * audited against, so the plural is the audited text itself. A singular,
 * bracketed, heading-style or dash-terminated variant is not an alternative
 * spelling: it is a label that a fixed-string search for the category will not
 * find, which makes a documented rationale read as absent to the audit that
 * looks for it. {@code docs/CODE_DOCUMENTATION_STANDARD.md} carries the full
 * statement of the convention and enumerates the rejected shapes.
 *
 * <p>The rule's validation gate at its line 43 is conjunctive: a docstring with
 * purpose, parameters and return values, and an inline rationale naming at
 * least one of those four categories, are each independently fatal when absent,
 * because the gate closes by failing code that is missing either one. Note that
 * the gate's triad names purpose, parameters and return values and does not
 * mention exceptions. Exception at-clauses are mandatory throughout this
 * subtree all the same, on three grounds that are not the gate's: the rule's
 * own line 21 lists exceptions or errors among its elements, qualified
 * "where applicable"; the house convention at {@code tests/README.md} lines 544
 * to 549 names Purpose, Parameters, Returns and Exceptions and calls itself a
 * hard review gate at line 549; and the repository ruleset validates declared
 * throw clauses mechanically. Line 43 is the floor rather than the ceiling. The
 * provenance is stated this precisely because line 41 forbids a vague
 * rationale, and misquoting an authority in order to strengthen a point would
 * itself be one.
 *
 * <p>Assumptions: a green documentation gate is necessary and NOT sufficient
 * for compliance with this rule. The rule's line 15 attaches its docstring
 * requirement to every function with no qualification by visibility, whereas
 * the ruleset's presence check for methods governs package visibility and
 * upward, so a private method carries the obligation without the linter ever
 * raising it. That gap is load-bearing for this module in particular:
 * {@code service} is where COBOL paragraphs are transcribed, and a transcribed
 * paragraph naturally becomes a private helper. The ruleset's own header says
 * as much from the other side, recording that it mechanises the docstring half
 * of the rule and cannot judge whether a comment explains why rather than what,
 * whether it restates the code beside it, or whether a non-obvious choice was
 * left undocumented.
 *
 * <p>Assumptions: the rule's line 23 allows a trivial accessor a single-line
 * docstring. That grants a FORM and not an ABSENCE, because the docstring is
 * still required. It has no subject in this file, which declares no accessor,
 * and it is recorded because this charter is where the author of a sibling
 * package looks first and because {@code dto} here is built from Java 21
 * records, whose components each need a parameter at-clause on the record
 * itself. The concession is usable at all only because the ruleset omits the
 * single-line Javadoc check while requiring a return at-clause on every
 * value-returning method: were both active, a one-line docstring carrying a
 * return at-clause would be rejected and the rule's own allowance would be
 * unreachable.
 *
 * <p>Assumptions: there is no in-code escape from that gate. The ruleset
 * enables none of the three comment-driven or annotation-driven suppression
 * filters, so neither a marker comment nor an annotation suppresses anything; a
 * suppression has to be a durable entry in the ruleset's companion file, whose
 * charter reaches generated sources and test fixtures only. Nothing beneath
 * this module's main source tree may be suppressed, and relaxing the gate is a
 * breach of the rule rather than a build-configuration choice.
 *
 * <p>Assumptions: the gate is local and not merely a pipeline step, being bound
 * to the build's validate phase ahead of compilation, so a missing charter
 * breaks the build on a developer's own machine. Two checks interlock to make
 * this file undroppable: a file-set check requires a charter to exist in any
 * directory holding an audited compilation unit, and a syntax-tree check
 * requires that charter to carry Javadoc. A charter reduced to a bare package
 * statement would satisfy the first and fail the second, which is why this one
 * is prose.
 *
 * <p>Assumptions: the inline form this Java tree establishes for rationale on
 * statements is a single comment placed immediately above the code it explains,
 * opening with one of the four canonical labels and its colon, then the reason
 * and what would differ under the alternative, with continuations indented to
 * line up beneath the label. Nothing else belongs in it.
 *
 * <p>Alternatives Considered: pairing that comment with a second, preceding line
 * labelled for what the code does was the earlier convention in this tree and is
 * rejected. The shell blocks at {@code tests/README.md} lines 267 and 270 do use
 * that twin form, but they annotate command pipelines in prose documentation,
 * where no docstring construct exists and the effect of a pipeline genuinely is
 * not evident from its tokens. A Java statement is the opposite case: a line
 * above it saying what it does restates it, which is the first pattern the
 * explainability rule forbids, and purpose already has a home in the Javadoc.
 * The twin form is therefore kept for fenced command blocks and forbidden on
 * statements, as {@code docs/CODE_DOCUMENTATION_STANDARD.md} sets out. This
 * charter uses the in-Javadoc equivalent, a labelled sentence opening a
 * paragraph, because it holds one declaration and no statements to annotate.
 *
 * <p>Assumptions: two identifier namespaces collide by number and are kept
 * textually distinct throughout this subtree. The user-specified rule is
 * Explainability and is cited by its line numbers. The migration plan's
 * transformation rules are numbered T1 to T10 and are always written with the
 * T: T1 makes the copybook normative, T2 turns one former {@code COPY} into one
 * import, T3 pins money to exact fixed point, T4 preserves arithmetic order, T5
 * maps the CICS verbs, T7 turns validation flags into per-field errors and T8
 * keeps user-visible strings verbatim. "Rule 1" never means "T1".
 *
 * <h2>There is no package charter above this one, and that is deliberate</h2>
 *
 * <p>No charter file exists at
 * {@code services/transaction-service/src/main/java}, at
 * {@code services/transaction-service/src/main/java/com}, or at
 * {@code services/transaction-service/src/main/java/com/carddemo}. The absence
 * is a decision rather than an oversight, and it rests on four grounds. Their
 * mutual independence is what makes the conclusion safe: any one of them
 * settles it on its own, so the conclusion does not fall if any single one is
 * re-examined.
 *
 * <p>Assumptions: first, the mechanism. The ruleset's charter-presence check
 * sits at the top level of the configuration rather than inside the syntax-tree
 * container, which makes it a file-set check: it fires only for a directory
 * that contains a {@code .java} file the audit actually processed, and the
 * processed set is narrowed further by the configuration's own restriction of
 * the audit to the {@code .java} extension. Each of those three levels holds
 * only a subdirectory, so no violation is reachable at any of them, and a file
 * placed there would satisfy no gate.
 *
 * <p>Second, the count canon above admits exactly eight charter files, every
 * one at this folder or deeper. A ninth would break it, which is what turns
 * this boundary into arithmetic a later reader can re-check rather than a
 * judgement they have to re-make.
 *
 * <p>Third, the rule itself, independently of any linter. Its line 15 attaches
 * the docstring requirement to a function, a class or a module entry point, and
 * all three are compilation units. A directory holding only a subdirectory has
 * none, so no docstring can be demanded of it. This ground would hold even if
 * the ruleset were configured differently.
 *
 * <p>Fourth, the empirical shape of the tree, verified directly rather than
 * assumed. In the shared-kernel module, {@code com/carddemo} holds exactly one
 * child, the {@code common} folder, and no file at all. In this module,
 * {@code src/main}, {@code src/main/java} and {@code src/main/java/com} each
 * hold folders only and no file, and {@code com/carddemo} holds the
 * {@code transaction} folder and no file. Across all nine modules every charter
 * sits in a directory that also holds Java sources, and none sits in a
 * directory holding only subdirectories, so the convention is consistent from
 * independent probes and this file does not break a pattern.
 *
 * <p>Trade-offs: the cost is that a reader browsing generated documentation
 * meets three package pages carrying no description. That was accepted over
 * authoring three files whose whole content would be a sentence pointing at
 * this one, because a charter that defers has to be kept in step with the file
 * it defers to and it invites the next author to add a fourth. Recording the
 * boundary here rather than leaving it silent is required in its own right: the
 * rule's line 40 forbids leaving a non-obvious choice undocumented where a
 * reasonable alternative exists, and mirroring a charter at every path level
 * for symmetry is exactly such an alternative.
 *
 * <h2>Authoring notes for this file</h2>
 *
 * <p>Trade-offs: this file is pure ASCII with no byte-order mark. The
 * alternative was to reproduce the typographic dashes the migration prose uses,
 * which would read closer to that prose. ASCII was chosen because the house
 * convention this charter cites is written at {@code tests/README.md} lines 542
 * and 548 with a non-breaking hyphen, and a non-breaking hyphen is
 * indistinguishable from an ordinary one on screen while behaving differently
 * in a search: copying a label from there would turn {@code Trade-offs:} into a
 * token that a search for the label fails to find. The label in this file
 * therefore uses the ordinary hyphen-minus the rule itself uses. Restricting
 * the whole file to ASCII makes that failure mode unreachable, at the cost of
 * plainer punctuation, and the build declares UTF-8 for both the source
 * encoding and the documentation gate's charset, so ASCII is a strict subset of
 * what is configured.
 *
 * <p>Trade-offs: this file holds one Javadoc block and one package declaration
 * and nothing else -- no type, no annotation, no import and no line comment. It
 * is not a module descriptor: none exists anywhere in this repository and none
 * is introduced. It carries no authorship, version or release-marker at-clause
 * either, because the ruleset omits the whole Javadoc-formatting family that
 * would ask for them, and the version control history answers those three
 * questions more reliably than a comment maintained by hand.
 *
 * <p>Trade-offs: prose is wrapped at 80 columns to match the sibling charters
 * and build manifests in this tree, even though the ruleset enables no
 * line-length check and so does not require it. Six lines exceed that width
 * deliberately and should be left as they are. Four are the label definitions
 * above, which are preformatted: they are quoted, and their alignment is what
 * makes four labels legible as a column rather than as a paragraph. The other
 * two are the layering test path and the migration path, neither of which can
 * be broken, because a line break inside an inline code span would insert the
 * comment margin into the rendered path.
 */
package com.carddemo.transaction;
