/**
 * Test charter for the byte-exact edit and projection boundary of the reporting bounded context:
 * the tests that hold the mapper package's numeric output to the bytes the reference artifacts
 * declare.
 *
 * <h2>Purpose</h2>
 *
 * <p><b>Purpose.</b> This package holds unit tests over the types in
 * {@code com.carddemo.reporting.mapper}. A test here needs no database, no container, no
 * application context and no test double: an edit mask is a pure function from an exact decimal
 * value to a fixed-width string, so every assertion in this package is a function of its own
 * arguments.</p>
 *
 * <p>Assumptions: the sibling charter at
 * {@code services/reporting-service/src/main/java/com/carddemo/reporting/mapper/package-info.java}
 * is the normative statement of what this package tests -- it enumerates the display and edit
 * regimes the production package emits and the declared record lengths those regimes fill. This
 * charter does not restate that inventory, because two copies of a count are two things to keep in
 * step and the production charter is the one the migration plan assigns the contract to. A test
 * here cites it rather than paraphrasing it.</p>
 *
 * <h2>The one obligation every test in this package carries</h2>
 *
 * <p>Assumptions: an edit mask is a byte contract, not a formatting preference, so an expectation
 * in this package is pinned to a declared width or an edit mask read from an immutable reference
 * artifact under {@code app/}, and never to a string chosen because it read well. The report is the
 * worked example: its 133-column line and its {@code -ZZZ,ZZZ,ZZZ.ZZ} and {@code +ZZZ,ZZZ,ZZZ.ZZ}
 * edit masks are declared by the reference program and its copybook, so the width and the polarity
 * of every emitted item are facts to be reproduced rather than choices to be made. A test that
 * asserted a self-chosen literal would pass while the emitted artifact drifted away from the
 * declared record length, which is precisely the failure these assertions exist to prevent.</p>
 *
 * <p>Assumptions: the reference implementation under {@code app/} and the functional-parity oracle
 * suite under {@code tests/} are read as evidence and are never modified, re-pinned or regenerated
 * by anything in this package. A test here cites a reference program, copybook or job by path when
 * it needs to ground an expectation, and that citation is the whole extent of the relationship.</p>
 *
 * <p>Assumptions: money reaches these tests as {@code com.carddemo.common.money.Money} built from
 * exact decimal text, and never from a binary floating point literal. A mask that is correct to the
 * cent cannot be demonstrated by a fixture that was already wrong before the mask saw it, so the
 * fixture construction is part of the contract under test rather than incidental setup.</p>
 *
 * <p>Trade-offs: a test here asserts the rendered bytes and deliberately does not assert which
 * component performed any rounding. Rounding belongs to the money type and to the arithmetic that
 * produced the value; a formatter that also rounded would be two contracts in one method, and an
 * expectation that conflated them would keep passing after either half changed. The cost is that a
 * rounding regression is caught by the money tests rather than here; the gain is that a width or
 * polarity failure in this package names the formatter and nothing else.</p>
 *
 * <h2>Two hazards these tests are built to catch</h2>
 *
 * <p>Assumptions: the default locale is hostile territory for a fixed-width numeric contract. A
 * grouping separator and a decimal separator are locale-dependent in most formatting APIs, so a
 * mask that reads correctly on a machine configured one way emits a different byte sequence on a
 * machine configured another way. Every test in this package therefore installs a default locale
 * whose punctuation differs from the expected output before it runs and restores the original
 * afterwards, so a locale-dependent implementation fails here instead of failing in a deployed
 * region. Restoring the previous value is not politeness: the locale is process-global state, and
 * leaving it changed would make an unrelated test in the same reactor fail for a reason nothing in
 * its own source explains.</p>
 *
 * <p>Assumptions: these formatters are reached concurrently in the target, because report and
 * statement generation runs as batch steps over many rows, so a mask holding mutable formatting
 * state between calls would corrupt output only under load and only sometimes. That is the least
 * reproducible failure mode this package could ship, which is why concurrent use is asserted
 * directly rather than left to be inferred from the absence of a field.</p>
 *
 * <h2>Naming, and what a wrong name costs</h2>
 *
 * <p>Assumptions: a class in this package is collected by the unit-test runner during the Maven
 * {@code test} phase, which requires its name to begin with {@code Test} or end with {@code Test},
 * {@code Tests} or {@code TestCase}. Neither {@code services/pom.xml} nor
 * {@code services/reporting-service/pom.xml} configures an include pattern for the default test
 * execution, so those default patterns are the whole of the contract; the one include the parent
 * does declare, at {@code services/pom.xml:1047}, narrows a separate {@code architecture-rules}
 * execution to {@code LayeringRulesTest} and does not govern this package. A class whose name ended
 * in {@code IT} would instead be collected at {@code integration-test}, after packaging, and would
 * not run at all in a plain {@code test} invocation -- so a no-container assertion would silently
 * stop being checked in the inner loop while the build still reported success.</p>
 *
 * <h2>Parameters, return values and exceptions: declared inapplicable</h2>
 *
 * <p>A package declaration accepts no parameter, yields no value and raises nothing, so this
 * charter carries no parameter, return or exception at-clause, and no authorship, availability or
 * revision at-clause either. The inapplicability is stated rather than left silent because the
 * project's single user-specified rule, Explainability, names a docstring that omits parameters,
 * return values or purpose among its forbidden patterns, and a reader has to be able to tell a
 * declared inapplicability from an oversight. Fabricating the at-clauses would be worse: Javadoc
 * has no parameter, return or exception concept for a package, and the shared rule set audits
 * at-clause bodies for emptiness, so an invented clause would either be discarded or reported. That
 * exemption is this compilation unit's alone and does not travel into the test classes beside it,
 * where a test method and a private helper alike do have parameters, return values and thrown types
 * to document.</p>
 *
 * <h2>Why this charter exists, and the form it takes</h2>
 *
 * <p>Assumptions: the Explainability rule requires a docstring on every module entry point, and in
 * Java the entry point of a package is its package declaration, which only a
 * {@code package-info.java} can carry, so this file is load-bearing rather than decorative. Two
 * Checkstyle modules enforce that independently and neither is redundant: the file-set check at
 * {@code config/checkstyle/checkstyle.xml:245} inspects the file system and requires this file to
 * exist in any directory holding an audited source file, while the syntax-tree check at
 * {@code config/checkstyle/checkstyle.xml:378} parses the file and requires it to carry Javadoc. A
 * charter reduced to a bare package statement satisfies the first and fails the second, which is
 * why prose is the deliverable and the file's mere existence is not.</p>
 *
 * <p>Assumptions: the gate audits this tree as well as the main tree.
 * {@code services/pom.xml:888} sets {@code includeTestSourceDirectory} for the
 * {@code checkstyle-documentation-gate} execution declared at {@code services/pom.xml:770}, bound
 * to the Maven {@code validate} phase at {@code services/pom.xml:771} with
 * {@code failOnViolation} true at {@code services/pom.xml:860} and {@code violationSeverity} set to
 * warning at {@code services/pom.xml:861}. A test package holding a test class and no charter
 * therefore fails the reactor at {@code validate}, before anything is compiled, rather than passing
 * quietly -- which is how the absence of this file was observed rather than assumed.</p>
 *
 * <p>Trade-offs: this file is restricted to printable ASCII, and where a cited source carries a
 * non-breaking hyphen or an em dash it uses an ASCII hyphen-minus or a pair of ASCII hyphens. The
 * reason is concrete rather than aesthetic: {@code tests/README.md} uses the non-breaking hyphen
 * inside the very words a justification label is spelled from, so text copied from there yields a
 * label that looks correct, greps wrong, and silently escapes an audit searching for the canonical
 * spelling. The accepted cost is typographically plainer prose.</p>
 *
 * <p>Assumptions: the four justification labels used here -- {@code Alternatives Considered:},
 * {@code Refactoring Rationale:}, {@code Assumptions:} and {@code Trade-offs:} -- are spelled
 * exactly as {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes them: plural where the rule writes
 * them plural, unparenthesised, each closed by its own colon, and never wrapped in emphasis markup.
 * The bare form is used throughout this charter and the emphasis form nowhere, because that
 * standard requires that the forms are never mixed inside one file.
 * {@code Refactoring Rationale:} is deliberately unused here, because that label applies when
 * existing code is replaced and the Java in this module replaces nothing: the reference COBOL stays
 * byte-identical and keeps running.</p>
 */
package com.carddemo.reporting.mapper;
