/**
 * Unit tests for the reporting anti-corruption layer, holding its output to the bytes the reference
 * artifacts declare.
 *
 * This is the test-side counterpart of the charter at
 * {@code services/reporting-service/src/main/java/com/carddemo/reporting/mapper/package-info.java}.
 * That file is the normative statement of what the production package emits: its declared record
 * lengths, its display and edit regimes, its seam with the shared codec and its padding
 * obligations. This charter restates none of that inventory. It states instead what the tests
 * themselves are, which class owns which contract, and the handful of facts a reader needs before
 * opening any of them.
 *
 * The seven test classes the migration plan assigns to this package, and the unit each covers:
 *
 * {@code CobolEditMaskTest} covers {@code CobolEditMask}: the edit-mask cases and the coexisting
 * numeric regimes, including all four zero renderings set out below.
 *
 * {@code ReportBandLayoutsTest} covers {@code ReportBandLayouts}: the seven band descriptors
 * transcribed from {@code app/cpy/CVTRA07Y.cpy}, every one declaring a 133-byte record length.
 *
 * {@code StatementBandLayoutsTest} covers {@code StatementBandLayouts}: the seventeen
 * {@code ST-LINE} band descriptors declared at lines 86 to 146 of {@code app/cbl/CBSTM03A.CBL},
 * every one 80 bytes.
 *
 * {@code TransactionReportMapperTest} covers {@code TransactionReportMapper}: the 133-column report
 * emission, band by band.
 *
 * {@code StatementTextMapperTest} covers {@code StatementTextMapper}: the 80-column plain-text
 * statement emission, and the immutable prepared-fields record that the HTML mapper shares.
 *
 * {@code StatementHtmlMapperTest} covers {@code StatementHtmlMapper}: the 100-column HTML statement
 * emission and its thirty-four markup fragments.
 *
 * {@code ReportingDtoMapperTest} covers {@code ReportingDtoMapper}: the JSON boundary.
 *
 * One further class sits in this package and is listed so that this inventory matches the
 * directory. {@code ReportingFixtureRecordTest} consumes the committed fixture records, resolving
 * each from the test classpath and decoding it against the production layout registry. Its subject
 * is the fixture corpus rather than a mapper contract, which is why it is named apart from the
 * seven above rather than counted among them.
 *
 * The ownership boundary, which is the rule here most easily broken by a well-meant edit: each
 * byte-exact rule is asserted in exactly one class. Mask semantics belong to
 * {@code CobolEditMaskTest}. Layout geometry, meaning declared record lengths, field offsets,
 * widths and the literals a band carries, belongs to {@code ReportBandLayoutsTest} and
 * {@code StatementBandLayoutsTest}. The placement of an already-formatted value into a band belongs
 * to the three emitter tests. JSON shape belongs to {@code ReportingDtoMapperTest}. Two owners of
 * one rule drift apart, and the drift stays invisible until a golden comparison fails, which is
 * long after the edit that caused it.
 *
 * Four zero renderings coexist in this module and none substitutes for another:
 *
 * Fifteen blanks, the whole item suppressed. This is the report amount, from the
 * {@code Z}-suppressed 15-byte mask {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} declared at line 30 of
 * {@code app/cpy/CVTRA07Y.cpy}. Owned by {@code CobolEditMaskTest}.
 *
 * Nine blanks, then a period, then two zero digits, then a blank sign position. This is the
 * statement amount, from {@code PIC Z(9).99-} declared at lines 137 and 142 of
 * {@code app/cbl/CBSTM03A.CBL}. Thirteen characters. Owned by {@code CobolEditMaskTest}.
 *
 * Zero-padded integer digits, then a period, then two zero digits, then a blank sign position. This
 * is the statement balance, from {@code PIC 9(9).99-} declared at line 113 of
 * {@code app/cbl/CBSTM03A.CBL}. Thirteen characters. Owned by {@code CobolEditMaskTest}.
 *
 * The two-decimal-place quoted string form. This is the JSON representation, carried on the wire as
 * a JSON string and never as a JSON number. Its shape is owned by {@code ReportingDtoMapperTest},
 * and it is held apart from the three masks in {@code CobolEditMaskTest}.
 *
 * The two statement forms differ from each other only in whether a leading zero prints, and both
 * are the same width, so a mask that emitted the wrong one would still fill its field and would
 * still misstate every value in the artifact.
 *
 * {@code FILLER} is handled in opposite ways two files apart, and the two readings must never be
 * generalised to one another. In the report band descriptors it is real valued content: all
 * twenty-two {@code FILLER} items declared across lines 4 to 66 of {@code app/cpy/CVTRA07Y.cpy}
 * carry a {@code VALUE}, and they survive every record in the baseline because line 362 of
 * {@code app/cbl/CBTRN03C.cbl} re-initialises the band and the {@code INITIALIZE} verb skips
 * {@code FILLER}, so a joiner written once is never cleared. {@code ReportBandLayoutsTest}
 * therefore asserts that each one is modelled and seeded. In the JSON DTOs the same construct is
 * dropped, and the drop is recorded rather than merely performed: {@code ReportingDtoMapperTest}
 * asserts that no record component is named for filler, padding or a reserved area, and separately
 * that every dropped field is registered. Input padding on the one side, output literal on the
 * other.
 *
 * Every class in this package carries a {@code Test} suffix, and that is deliberate rather than
 * incidental. Neither {@code services/pom.xml} nor {@code services/reporting-service/pom.xml}
 * declares an include pattern for the default test execution, so the resolved unit-test runner's
 * own defaults govern, and those defaults gather four name shapes only: a {@code Test} prefix, or a
 * {@code Test}, {@code Tests} or {@code TestCase} suffix. A {@code Spec} suffix matches none of the
 * four and would be gathered by nothing at all, and a silently uncollected test is worse than no
 * test because the build still reports success. An {@code IT} suffix is the integration-test
 * runner's pattern and is gathered after packaging, so it does not belong in a package whose
 * assertions need no packaged artifact.
 *
 * The fixture records these tests read live in the sibling directory
 * {@code services/reporting-service/src/test/resources/fixtures/}, and no fixture file belongs in
 * this package. That separation is the one the documentation gate depends on: the shared
 * suppression charter exempts that resources directory and nothing else on the test side, so a
 * fixture moved in here would arrive inside an audited Java package and be read as source.
 *
 * These are plain JUnit 5 unit tests. No class here starts an application context, a container, a
 * cloud emulator or a database, and none adds a dependency. An edit mask is a pure function from an
 * exact decimal value to a fixed-width string and a band descriptor is a declaration, so every
 * assertion in this package is a function of its own arguments.
 *
 * Every assertion traces to a reference artifact cited by path and line. The artifacts read here
 * are {@code app/cpy/CVTRA07Y.cpy} and {@code app/cpy/CVTRA05Y.cpy}, {@code app/cbl/CBTRN03C.cbl}
 * and {@code app/cbl/CORPT00C.cbl}, {@code app/cbl/CBSTM03A.CBL} and {@code app/cbl/CBSTM03B.CBL},
 * {@code app/cpy/COSTM01.CPY}, and the jobs {@code app/jcl/TRANREPT.jcl},
 * {@code app/jcl/PRTCATBL.jcl} and {@code app/jcl/CREASTMT.JCL}. Filename extension case is part of
 * each path and is reproduced as the tree carries it. The baseline under {@code app/} is
 * reference-only: it is read as evidence and is never modified, re-pinned or regenerated by
 * anything here. Where the target behaves differently, the baseline does one thing, the Java
 * implements another, and the divergence is documented in
 * {@code docs/architecture/cobol-to-service-traceability.md}. A test here never presents such a
 * difference as a repair of the baseline.
 *
 * The functional-parity oracle suite under the repository-root {@code tests/} tree is a separate
 * body of work from this package. That tree is the COBOL three-layer suite which compares the
 * reference programs against golden masters, and it is not
 * {@code services/reporting-service/src/test}. Nothing here runs it, extends it or re-pins it.
 *
 * Parameters, return values and exceptions or errors: none applies. A package declaration accepts
 * no argument, yields no value and raises nothing, so this file carries no parameter, return or
 * exception at-clause, and no authorship, availability or revision at-clause either. The
 * inapplicability is declared rather than left silent so that a reviewer can tell it from an
 * omission, because the project's single user-specified rule, Explainability, lists a docstring
 * that omits parameters or return values among its forbidden patterns. The exemption belongs to
 * this compilation unit alone and does not travel into the classes beside it, where a test method
 * and a private helper alike do have arguments and thrown types to document.
 */
package com.carddemo.reporting.mapper;

// WHAT: places the justification block after the package declaration rather than between it and the
//       charter above.
// WHY : Assumptions: MissingJavadocPackage resolves the charter by looking at the comment
//       immediately preceding the package declaration, so an intervening line comment detaches the
//       two. This was measured rather than reasoned about: with these comments sitting above the
//       declaration, `mvn -f services/reporting-service/pom.xml validate` failed with exactly one
//       finding, MissingJavadocPackage reported against the package declaration line at column 1,
//       on a file whose very first token is the opening of a Javadoc block. Moving the block below
//       the declaration keeps every comment adjacent to the declaration it explains, which is what
//       Rule 1 asks at its line 27, while leaving the charter in the only position the check
//       accepts. Re-running the same command afterwards reported zero violations.
// WHAT: records the determination that decided whether this file is required, and the branch it
//       resolved to.
// WHY : Assumptions: whether the documentation gate audits this tree at all is a property of the
//       Maven plugin and not of config/checkstyle/checkstyle.xml, so it cannot be read from the
//       rule set no matter how carefully that file is studied. Running
//       `mvn -f services/reporting-service/pom.xml help:effective-pom` and reading the resolved
//       maven-checkstyle-plugin 3.6.0 execution `checkstyle-documentation-gate` printed the
//       property includeTestSourceDirectory carrying the value true, alongside failOnViolation true
//       and violationSeverity warning. The value is declared, so no plugin default applies and none
//       was assumed. src/test/java is therefore audited, JavadocPackage at line 245 of that rule
//       set is a file-set check that reports any audited directory holding no package-info.java,
//       and this file is consequently required at the validate phase before anything compiles.
// WHAT: states why this file would exist even had that determination gone the other way.
// WHY : Alternatives Considered: omitting the file whenever the gate does not demand it. Rejected,
//       because user-specified Rule 1 requires a docstring on every module entry point at
//       its line 15, and in Java the entry point of a package is its package declaration, which
//       only a package-info.java can carry; because a test package whose classes hold interlocking
//       byte-exact contracts is exactly where a reader needs one orientation point; and because the
//       plugin property is a single line somebody may later flip, at which point a package with no
//       charter would break the build in a file nobody was watching. Only the enforcement differs
//       between the two branches, never the correctness of having the file. The class total is
//       given once in the charter above and is deliberately not repeated here, since a count
//       restated inside a justification is a third place for it to fall out of step.
// WHAT: keeps this compilation unit to a documentation block and a package declaration.
// WHY : Assumptions: a package-info.java is read for package-level Javadoc and annotations and for
//       nothing else, so a type, a constant, an import or a static block declared here would make
//       it an ordinary source file that merely happens to be named package-info, and the package
//       would then have its documentation attached to a class instead of to itself. No
//       package-level annotation is declared either, because any this module could want would pull
//       in an import and add a second thing to document for no assertion gained.
// WHAT: keeps this charter to the tests and leaves the emitted contract to the production charter.
// WHY : Trade-offs: the production charter beside this one already fixes the emitted contract, so
//       repeating its record lengths, its regime table or its padding obligations here would create
//       a second copy of every figure. Two copies of a count are two things to keep in step, and
//       the one that falls behind is indistinguishable from the one that is right. The accepted
//       cost is that a reader wanting the emitted contract opens that file; the gain is that no
//       figure in this module has two homes.
// WHAT: states the one-owner-per-rule boundary in this file rather than in each class separately.
// WHY : Alternatives Considered: letting each class assert its own scope in its own header.
//       Rejected, because a boundary is a statement about the whole set and no single member can
//       make it: a reader who opens one class sees only what that class covers and cannot tell that
//       a mask assertion added there duplicates one already made elsewhere. Stating it at package
//       level puts it where somebody editing a single file in isolation still passes it.
// WHAT: enumerates the four coexisting zero renderings in one place rather than once per owner.
// WHY : Assumptions: the four are genuinely different byte sequences for the same value, and three
//       of them are identical or near-identical in width, so the failure mode is substitution
//       rather than absence. A substituted zero still fills its field and still satisfies any check
//       that only measures length, which is why the set is written out together with each owner
//       named: the comparison a reader has to make is between the four, and a fact split across
//       four files cannot be compared without opening all four.
// WHAT: calls out that FILLER is modelled on one side of this package and dropped on the other.
// WHY : Assumptions: the two readings are opposite rather than merely different, so the natural
//       generalisation of one FILLER policy for the whole module is wrong in one direction whichever
//       way it is chosen. On the report side the construct carries a VALUE and is output content
//       that must be emitted; on the JSON side it is input padding that exists to reach a declared
//       record length and carries nothing a consumer can use. Stating the inversion is what stops a
//       shared helper being written across the two, which is the concrete edit this note exists to
//       prevent.
// WHAT: declares the parameter, return and exception elements inapplicable in prose rather than as
//       at-clauses.
// WHY : Trade-offs: Rule 1 names four docstring elements and its validation gate at line 43 is
//       conjunctive, so a reviewer auditing against it looks for all four and has to be able to
//       tell a declared inapplicability from an oversight. Adding an empty at-clause to look
//       complete would fail the build outright, because NonEmptyAtclauseDescription at line 470 of
//       the shared rule set rejects an at-clause with no description, and Javadoc has no parameter,
//       return or exception concept for a package in the first place. Declaring the inapplicability
//       in the block above costs a sentence and is the only form that both satisfies that gate and
//       survives it.
// WHAT: states the collected test-name shapes as the resolved runner documents them rather than as
//       a stricter rule of thumb.
// WHY : Assumptions: the default test execution declares no include pattern, so the resolved
//       maven-surefire-plugin 3.5.6 defaults govern, and that version's own goal descriptor
//       documents them as `**/Test*.java`, `**/*Test.java`, `**/*Tests.java` and
//       `**/*TestCase.java`. The tighter claim that only a Test suffix is ever gathered reads as
//       the safer thing to write and is not true of this runner, and a maintainer who believed it
//       could rename a working class to fix a problem that never existed. Rule 1 forbids an
//       unfounded rationale at its line 41, so the four patterns are cited as they were read.
// WHAT: restricts this file to printable ASCII and to Javadoc carrying no markup element.
// WHY : Trade-offs: the repository-root tests/README.md spells a justification label with a
//       non-breaking hyphen at its line 548, so text copied from there yields a label that looks
//       correct and matches no search for the canonical spelling; every label here is therefore
//       typed with an ASCII hyphen-minus. Markup is dropped for a second and independent reason:
//       JavadocParagraph and JavadocStyle are both absent from the shared rule set and no javadoc
//       goal is bound in this reactor, so no element is required by anything, while an unescaped
//       angle bracket in a file that is almost entirely prose is a live hazard. The accepted cost
//       is typographically plainer output.
// WHAT: names ReportingFixtureRecordTest in the inventory beside the seven the plan assigns here.
// WHY : Assumptions: the inventory exists so that a reader can navigate this package without
//       opening its files, which makes it wrong the moment it omits one that is present. The
//       migration plan assigns seven mapper test classes to this package and that figure is stated
//       as seven; the eighth class landed beside them and covers a different subject, consuming the
//       committed fixture records rather than asserting a mapper contract. Listing it under its own
//       sentence keeps both facts exact instead of collapsing them into one number that matches
//       neither.
