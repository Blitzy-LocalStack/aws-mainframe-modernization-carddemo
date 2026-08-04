/**
 * Parity evidence for the two card behaviours that carry decision logic: the browse that
 * fills one screen of cards, and the update chain that validates four fields and then
 * writes. Each behaviour is asserted against the COBOL paragraphs it was transcribed
 * from, so a failure here names a business rule rather than merely a layer.
 *
 * <h2>What this charter describes, and the tree state that authored it</h2>
 *
 * <p>Assumptions: every class name, sibling file and count below states the <b>target
 * contract</b> assigned to this package, not an inventory of what sits beside this file.
 * The charter is authored ahead of the classes it governs, so at the checkpoint that wrote
 * it this directory holds this file alone. Anything named below that has no file yet is
 * therefore <b>planned</b> rather than missing, and every count is a target total rather
 * than a measurement. Declaring that once, here, is what lets the rest of this document be
 * read as a contract instead of as a claim about the file system.</p>
 *
 * <p>Six names below are targets of exactly that kind, listed so the distinction can be
 * checked rather than taken on trust: {@code CardListServiceTest} and
 * {@code CardUpdateServiceTest} in this package, {@code CardControllerTest} in
 * {@code com.carddemo.card.api}, {@code CardRepositoryIT} in
 * {@code com.carddemo.card.repository}, {@code LayeringRulesTest} in
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture}, and
 * {@code services/card-service/src/test/resources/fixtures/README.md}. Each is assigned by
 * the migration plan and none had been authored when this file was written.</p>
 *
 * <h2>The two behaviours and their baseline provenance</h2>
 *
 * <p>The line numbers below locate paragraphs and declarations in the reference sources.
 * Those sources are the specification for this package: they are read, and never
 * modified.</p>
 *
 * <ul>
 *   <li>{@code CardListServiceTest} covers {@code CardListService}, transcribed from
 *       {@code app/cbl/COCRDLIC.cbl} (1459 lines, CICS transaction {@code CCLI}). One
 *       screen holds seven rows: {@code WS-MAX-SCREEN-LINES} is declared across lines 177
 *       and 178 with {@code VALUE 7}, and the comment at line 250 reads
 *       {@code 28 CHARS X 7 ROWS = 196}. The baseline walks the file forward through
 *       {@code 9000-READ-FORWARD} at line 1123, opening with {@code STARTBR} at line 1129,
 *       reading with {@code READNEXT} at lines 1146 and 1197, and closing with
 *       {@code ENDBR} at line 1258. It walks backward through
 *       {@code 9100-READ-BACKWARDS} at line 1264, opening at line 1273, reading with
 *       {@code READPREV} at lines 1294 and 1322, and closing at line 1376.
 *       {@code 9500-FILTER-RECORDS} at line 1382 selects which rows reach the screen.</li>
 *   <li>{@code CardUpdateServiceTest} covers {@code CardUpdateService}, transcribed from
 *       {@code app/cbl/COCRDUPC.cbl} (1560 lines, CICS transaction {@code CCUP}). Its
 *       dispatcher {@code 1200-EDIT-MAP-INPUTS} at line 641 drives four field gates:
 *       {@code 1230-EDIT-NAME} at line 806, {@code 1240-EDIT-CARDSTATUS} at line 845,
 *       {@code 1250-EDIT-EXPIRY-MON} at line 877, and
 *       {@code 1260-EDIT-EXPIRY-YEAR} at line 913. That program commits in exactly one
 *       place, the {@code SYNCPOINT} at line 470, and that single commit is the one
 *       transaction boundary these tests expect a write to observe.</li>
 * </ul>
 *
 * <p>Both classes operate on the 150-byte {@code CARD-RECORD} declared at
 * {@code app/cpy/CVACT02Y.cpy} line 4, whose leading field is {@code CARD-NUM PIC X(16)}
 * at line 5. That record lives in a lowercase {@code .cpy} record copybook, whereas the
 * uppercase {@code .CPY} books under {@code app/cpy-bms} are the symbolic screen maps and
 * describe a different contract, so the two are cited with their case preserved.</p>
 *
 * <h2>The two claims this package owns</h2>
 *
 * <p>First, each browse direction resolves to a single keyset query. The baseline spends
 * eight browse verbs on two directions because a CICS browse is opened, read repeatedly
 * and then closed, while the migrated service answers each direction with one query keyed
 * relative to the key it was handed. What makes that a transcription rather than a
 * redesign is that the baseline already carries a keyset cursor, at lines 229 to 244: a
 * last-key pair, a first-key pair and a next-page indicator, with no ordinal position
 * anywhere among them. The baseline derives that indicator by reading one row past the
 * screen, comparing its row count against the screen-line limit at line 1191 and then
 * issuing the further {@code READNEXT} at line 1197. Assertions here require the migrated
 * reads to request seven rows plus one for that same reason, and require that a row
 * arriving between two requests neither conceals a row nor presents one twice.</p>
 *
 * <p>Second, all four update gates always run, so per-field errors accumulate. The
 * dispatcher issues its four {@code PERFORM} statements back to back with no branch
 * between them and evaluates {@code INPUT-ERROR} only once the last has returned, so a
 * submission carrying three unacceptable fields is answered with three field errors and
 * not merely the first. Assertions here inspect the whole set, because checking a single
 * error would pass just as readily against a chain that stopped at its first failure.</p>
 *
 * <h2>Claims this package does not make</h2>
 *
 * <ul>
 *   <li>That the generated SQL is right. These tests drive a mocked repository and
 *       establish orchestration only: which query is chosen, which key and direction it
 *       receives, and what the service does with the rows handed back.
 *       {@code CardRepositoryIT}, in the sibling {@code com.carddemo.card.repository} test
 *       package, exercises a real database and owns that claim.</li>
 *   <li>That the single-card detail route behaves. {@code CardControllerTest}, in the
 *       sibling {@code com.carddemo.card.api} test package, owns it.</li>
 *   <li>That layering holds. {@code LayeringRulesTest}, under
 *       {@code services/common-lib/src/test/java/com/carddemo/common/architecture}, is the
 *       sole owner of that enforcement in this build.</li>
 *   <li>That a corpus record is laid out correctly.
 *       {@code services/card-service/src/test/resources/fixtures/README.md} holds the
 *       normative byte-position table, together with the invariant that a corpus file
 *       measures 151 bytes for every record it contains, one beyond the record itself for
 *       the line terminator. No test class re-derives those positions: the COBOL parity
 *       suite under {@code tests} states the same discipline at lines 540 to 542 of its
 *       {@code README.md}, that a layout is never duplicated and stays single-sourced.</li>
 * </ul>
 *
 * <h2>Decisions a reader would otherwise have to guess at</h2>
 *
 * <p>Alternatives Considered: a third test class covering {@code CardViewService}. The
 * main-tree package of this same name does hold that class, for the single-card detail
 * read transcribed from {@code app/cbl/COCRDSLC.cbl}, so arriving here and expecting a
 * matching test is entirely reasonable. It is deliberately absent. The detail route is
 * covered by {@code CardControllerTest} in the sibling {@code api} test package, and the
 * migration plan names only {@code CardListService} and {@code CardUpdateService} as this
 * service's service-layer test targets. A third class here would assert that same route a
 * second time, contributing no evidence the first assertion did not already carry, while
 * leaving two places to edit whenever the detail contract moves.</p>
 *
 * <p>Assumptions: no golden-master oracle exists for either program under test. The COBOL
 * three-layer parity suite under {@code tests} records, at lines 83 to 85 of its
 * {@code README.md}, that the online CICS programs cannot run end to end without a CICS
 * runtime, which the runner does not provide, and that only their extractable
 * field-validation logic is unit-tested. Its golden masters cover the batch flows, and
 * neither {@code COCRDLIC} nor {@code COCRDUPC} is a batch program. Parity for this
 * package therefore rests on transcription fidelity against the paragraphs and the record
 * contract cited above, each named by path and line. No assertion here may be read as a
 * comparison against a recorded baseline run, because no such run exists to compare
 * against.</p>
 *
 * <p>Assumptions: layering is not enforced in this package, and no architecture test
 * belongs in it. Checkstyle's {@code ImportControl} module is deliberately absent from
 * {@code config/checkstyle/checkstyle.xml} for the same reason that
 * {@code LayeringRulesTest} is a single owner: two engines enforcing overlapping halves of
 * one boundary would leave a reader unable to tell which of them governed a given rule. A
 * competing architecture test added here would reintroduce precisely that ambiguity.</p>
 *
 * <p>Alternatives Considered: citing {@code config/checkstyle/checkstyle.xml} by line
 * number, as the COBOL sources are cited above. Rejected, and the resulting asymmetry is
 * deliberate rather than an oversight. The reference sources under {@code app} are
 * read-only throughout this migration, so a line number in one of them cannot drift,
 * whereas the Checkstyle configuration is a living file whose module positions move
 * whenever a module or its rationale is edited. Checkstyle modules are therefore cited by
 * name, which survives those edits, and only immutable sources are cited by line.</p>
 *
 * <h2>The closed set, and why this file cannot be omitted</h2>
 *
 * <p>This package holds exactly two test classes, {@code CardListServiceTest} and
 * {@code CardUpdateServiceTest}, together with this file. Nothing else belongs in it, and
 * it has no subpackages.</p>
 *
 * <p>Assumptions: this file is a build requirement rather than a courtesy.
 * {@code config/checkstyle/checkstyle.xml} pairs two modules to that end.
 * {@code JavadocPackage} runs above the tree walker, because it inspects the file system,
 * and asserts only that a {@code package-info.java} exists; {@code MissingJavadocPackage}
 * runs inside the tree walker, because it inspects parsed Javadoc, and asserts that the
 * file carries documentation. Either module alone is satisfied by a file that documents
 * nothing, which is why both are configured. {@code services/pom.xml} binds that gate to
 * the Maven {@code validate} phase with test sources included, so this document is audited
 * before a single class in this module is compiled, and no in-code suppression can waive
 * it because the configuration registers no suppression filter at all.</p>
 *
 * <h2>What a green build here does and does not mean</h2>
 *
 * <p>The outcome of this module's build is binary: the Checkstyle gate, the compiler and
 * the JUnit engine each pass or fail. The graded return-code rubric that tolerates a warn
 * level belongs to the COBOL parity suite under {@code tests} and carries no meaning for
 * this module, so a passing run here must never be described in its terms.</p>
 */
package com.carddemo.card.service;
