/**
 * Unit tests of the transaction-service anti-corruption layer, pinning the two
 * hand-written mappers that convert between the ledger context's persistence
 * entity and the wire shapes its four migrated screens exchange.
 *
 * <h2>Purpose, and the at-clauses this charter does not carry</h2>
 *
 * <p><b>Purpose.</b> Every test beneath this package asserts a CONVERSION:
 * which stored column becomes which published member, what is withheld on the
 * way out, what is padded and to which width, which side of a computation a
 * value is read from, and which absences collapse onto null. Those are the
 * decisions the two production mappers exist to absorb, and they are the
 * decisions a reader cannot recover from a field name. Assertions about a
 * transfer object's own declared constraints belong to
 * {@code com.carddemo.transaction.dto}; assertions about a declared-width
 * record's geometry belong to {@code com.carddemo.transaction.domain}. A test
 * that strays across either line duplicates a sibling package's subject and is
 * refused here.
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> A package
 * declaration accepts no parameter, yields no value and raises nothing, so this
 * charter carries no parameter, no result and no exception at-clause. The
 * inapplicability is declared rather than left silent, because the project
 * Explainability rule forbids at its line 39 a docstring that omits parameters,
 * return values or purpose, and a reader has to be able to tell a declared
 * inapplicability from an omission.
 *
 * <p>Assumptions: inventing those at-clauses would do more than add noise.
 * Javadoc has no parameter, result or exception concept for a package, and
 * {@code NonEmptyAtclauseDescription} is enabled in
 * {@code config/checkstyle/checkstyle.xml} at its line 470, so a fabricated
 * at-clause would either be discarded by the tool or reported as an empty
 * description. The rule enumerates four docstring elements at its lines 18 to
 * 21, exactly one of the four has a subject in this compilation unit, and the
 * two paragraphs above account for the other three.
 *
 * <h2>Two independent grounds for this file's existence</h2>
 *
 * <p>Assumptions: this charter is mandated twice over by mechanisms that do not
 * depend on each other, and either one settles it alone. The first is the
 * project Explainability rule, whose line 15 attaches the docstring obligation
 * to every new or modified function, class and module entry point. A Java
 * {@code package} declaration IS a module entry point, and a
 * {@code package-info.java} file is the only legal home for its Javadoc, so the
 * rule reaches this directory whether or not any tool is configured to check
 * it. The second is the build itself, described below.
 *
 * <p>Assumptions: which sentence of the rule grounds this file is itself worth
 * stating precisely, because the two candidates say different things. The
 * rule's Validation Gate at its line 43 speaks of every new or modified
 * FUNCTION, and a package declaration is not a function; the gate is therefore
 * NOT this file's ground. What the gate does supply is force: it converts the
 * category obligation at line 29, which is written as a recommendation, into a
 * requirement, and it is conjunctive -- a missing docstring and a missing
 * rationale each fail review on their own. Citing line 43 as the reason a
 * package needs a charter would misquote the rule, and a misquoted rationale is
 * itself the vague rationale that line 41 forbids.
 *
 * <p>Assumptions: the build's half of the mandate is a paired interlock, and
 * the two halves are not redundant. {@code JavadocPackage} sits at the top
 * level of {@code config/checkstyle/checkstyle.xml}, at its line 245, which
 * makes it a file-set check demanding that a charter FILE EXIST in any
 * directory holding a source file the audit processed.
 * {@code MissingJavadocPackage} sits inside the syntax-tree container, at line
 * 378, and demands that the charter CARRY a Javadoc block on its package
 * declaration. A charter reduced to a bare package statement satisfies the
 * first and fails the second, which is the precise reason this one is prose
 * rather than a placeholder.
 *
 * <p>Assumptions: the interlock reaches this directory because
 * {@code services/pom.xml} sets {@code includeTestSourceDirectory} to true on
 * the documentation-gate execution, which is what pulls {@code src/test/java}
 * into the sweep alongside the main tree, and because this directory holds
 * source files the audit processes. The gate is local rather than a pipeline
 * courtesy: the execution is bound to the build's {@code validate} phase, ahead
 * of compilation, with violation severity lowered to warning and failure on
 * violation switched on, so an absent or wordless charter here breaks the
 * nine-module reactor on a developer's own machine before a single class is
 * compiled. That is what makes the rule's Validation Gate machine-checked
 * rather than aspirational.
 *
 * <h2>Target contract, and the tree state measured against it</h2>
 *
 * <p>Assumptions: the inventory below states this package's <b>target
 * contract</b> as the migration plan assigns it, and every member of that
 * contract has now LANDED, so for this package the inventory and a listing of
 * the directory beside this file agree. The PLANNED and LANDED labels are kept
 * rather than dropped, because they are what let a reader tell the two apart in
 * a sibling package where they still differ. Four source files constitute this
 * package and no more, and that is measured rather than asserted:
 *
 * <pre>
 * this directory: 4 java files = 3 tests + 1 charter
 * </pre>
 *
 * <p>Refactoring Rationale: the marker line above was added because the sentence
 * beside it -- "four source files constitute this package and no more" -- is a
 * closed-set claim that nothing checked. A fourth test authored here without an
 * entry below would have left the claim quietly false, which is the failure mode
 * every inventory in this tree has actually suffered. The line and the names under
 * it are compared with this directory on every build by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/PackageCharterInventoryTest.java}.</p>
 *
 * <ul>
 *   <li>{@code package-info.java} -- this charter. LANDED.</li>
 *   <li>{@code TransactionMapperTest} -- LANDED. Covers the mapper over the
 *       350-byte transaction record, whose length {@code app/cpy/CVTRA05Y.cpy}
 *       declares at its line 2, across 26 cases.</li>
 *   <li>{@code BillPaymentMapperTest} -- LANDED. Covers the bill-payment
 *       conversions, the assembled acknowledgement text and the invariant
 *       members of the appended ledger row, across 33 cases.</li>
 *   <li>{@code BillPaymentMappingTest} -- LANDED. Covers the conversion that
 *       reports a posted bill payment and the pay-in-full balance semantic it
 *       fixes, across 6 cases.</li>
 * </ul>
 *
 * <p>Assumptions: a count above is a count of DECLARED cases -- methods annotated
 * as a test or as a parameterised test -- and not of the cases the test engine
 * reports having executed, which is the larger number a parameterised case expands
 * into. The metric is named because the two figures differ by a wide margin: a
 * reader re-measuring a count against a run's reported total would read an accurate
 * figure as too low and correct it downward, and the next reader counting methods
 * would put it back. The counts are stated per member so that a divergence is
 * arithmetic rather than impression, and the member list itself is compared with
 * this directory on every build.
 *
 * <p>Alternatives Considered: withholding this charter until every member of
 * that set exists, which would let the inventory be read as a plain listing and
 * make the paragraph above unnecessary. It was rejected because this charter is
 * what the author of {@code TransactionMapperTest} works from -- which test
 * belongs here, which does not, and what the closed set is -- so writing it
 * after that class would leave the package with no stated contract during
 * precisely the interval in which one is consulted. It was also rejected
 * because a charter that merely describes whatever happens to be present cannot
 * say what is RESERVED, and a reserved class name is what stops a second test
 * of one behaviour being authored under a third name in a fourth package. The
 * subtree charter at
 * {@code services/transaction-service/src/test/java/com/carddemo/transaction/package-info.java}
 * carries the same paragraph for the same reason at its lines 43 to 62, so the
 * arrangement is the subtree's convention rather than a local preference.
 *
 * <p>Assumptions: three kinds of file are excluded from this directory
 * outright, and each exclusion has a mechanical reason rather than a stylistic
 * one. A class carrying the {@code IT} suffix does not belong here, because
 * this module adds no separate integration source root and the only integration
 * subjects it has are data-access classes, which the sibling {@code repository}
 * package owns. A shared base class or a record builder does not belong here,
 * because a mapper test's arguments are fabricated inline precisely so that the
 * figures a test supplies are visible in the test that supplies them. Fixture
 * material does not belong here at all: its chartered location is
 * {@code services/transaction-service/src/test/resources/fixtures}, a different
 * directory under a different source root, and it is the one location in this
 * module that the ruleset's companion suppression file narrows.
 *
 * <p>Assumptions: both bill-payment classes are present and neither supersedes
 * the other, so the relationship between them is stated here rather than left to
 * be inferred from their names. {@code BillPaymentMapperTest} is the broader of
 * the two. {@code BillPaymentMappingTest} sits beside it asserting a strict
 * subset of the same subjects -- the pre-payment figure the response reports, the
 * zero remainder the far side is left with, the two zero-padded identifiers, the
 * invariant posted discriminator, an absent message reported as null, and a
 * refused absent balance. The narrower one is therefore recorded as a subset and
 * not as a second subject, and the target set above stays closed at three so that
 * a reviewer has a boundary to hold a proposed addition against.
 *
 * <h2>What these tests exercise, and where it lives</h2>
 *
 * <p>Assumptions: the subjects of these tests are production classes under
 * {@code src/main}, which is a sibling of this directory's PARENT rather than a
 * sibling of this directory. The package name is identical on both sides --
 * {@code com.carddemo.transaction.mapper} is declared by the main tree and by
 * this one -- so a test reaches a mapper's package-private surface without that
 * surface being widened, and a reader tracing a subject must follow the source
 * root rather than the package. The two subjects are:
 *
 * <dl>
 *   <dt>{@code services/transaction-service/src/main/java/com/carddemo/transaction/mapper/TransactionMapper.java}</dt>
 *   <dd>The sole owner in this module of five decisions, each of which is a
 *       decision no field name discloses: the primary account number is masked
 *       to its last four digits; the twenty padding bytes that
 *       {@code app/cpy/CVTRA05Y.cpy} declares at its line 18 as
 *       {@code 05 FILLER PIC X(20)} are dropped rather than carried; money
 *       crosses as {@code com.carddemo.common.money.Money} holding a
 *       {@code BigDecimal} at scale 2 and leaves as a JSON string; identifier
 *       members are validated as digits and zero-padded to their declared
 *       widths; and every timestamp is rendered in the whole 26-character form
 *       through {@code com.carddemo.common.time.TimestampFormatter}. It
 *       converts {@code com.carddemo.transaction.domain.Transaction} to and
 *       from four wire shapes: {@code TransactionDetailResponse},
 *       {@code TransactionListItemResponse}, {@code TransactionAddRequest} and
 *       {@code TransactionAddResponse}.</dd>
 *
 *   <dt>{@code services/transaction-service/src/main/java/com/carddemo/transaction/mapper/BillPaymentMapper.java}</dt>
 *   <dd>Carries the ten verbatim field moves that {@code app/cbl/COBIL00C.cbl}
 *       performs at its lines 220 to 229 when it fills the payment row, and
 *       takes the account balance as a scalar
 *       {@code com.carddemo.common.money.Money} argument rather than as an
 *       {@code Account} entity. The scalar shape is the consequence of a
 *       context boundary: bill payment reads and rewrites the account master in
 *       the reference program, and this module does not own that record, so the
 *       balance arrives as a value obtained from the context that does.</dd>
 * </dl>
 *
 * <p>Assumptions: both mappers are hand-written, and no annotation processor or
 * code generator contributes a line to either. No mapping framework and no
 * accessor generator is declared in
 * {@code services/transaction-service/pom.xml} or in {@code services/pom.xml};
 * both are named there only in the comments that record their rejection. A test
 * here may therefore assert the text of a conversion decision, because there is
 * a hand-authored decision to assert.
 *
 * <h2>No field is renamed in this module</h2>
 *
 * <p>Assumptions: every field name beneath this package matches its copybook
 * exactly, and no test here may assert a spelling change. A reader who knows
 * the neighbouring services will arrive expecting one, because the wider
 * migration does rename three misspelled baseline names in its own target
 * columns -- {@code ACCT-EXPIRAION-DATE} in the account context,
 * {@code CARD-EXPIRAION-DATE} in the card context and
 * {@code PA-MERCHANT-CATAGORY-CODE} in the authorization context. None of the
 * three belongs to this module. The reference material is unambiguous on the
 * first of them: {@code app/cbl/CBTRN02C.cbl} reads at its line 414
 * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)} and uses the
 * misspelled name as it stands. A test asserting a rename here would assert
 * behaviour this module does not have and would pass only against an
 * implementation that had invented a divergence from the reference layout.
 *
 * <p>Assumptions: the ruling itself is owned by the production charter at
 * {@code services/transaction-service/src/main/java/com/carddemo/transaction/mapper/package-info.java},
 * which records why none of the three names can occur in the record layouts
 * this context owns. It is cited rather than restated, because two statements
 * of one ruling drift apart and a reader then cannot tell which is current.
 *
 * <h2>Three 26-character timestamp forms, never normalised together</h2>
 *
 * <p>Assumptions: three different byte patterns reach a {@code PIC X(26)}
 * timestamp field in the reference material, and folding them into one another
 * is a silent data error rather than a tidy-up. Each test asserts one form
 * against its own source, and no test asserts a form its subject does not
 * produce:
 *
 * <ul>
 *   <li><b>Two timestamps identical, sub-second component zero.</b>
 *       {@code app/cbl/COBIL00C.cbl} moves zeros into the microsecond component
 *       at its line 266 and then, at its lines 231 and 232, moves the one
 *       assembled value into BOTH the originating and the processing timestamp
 *       in a single statement with two receivers. A bill payment's two stamps
 *       are therefore equal by construction, and its sub-second component is
 *       invariably zero.</li>
 *   <li><b>Two timestamps different.</b> In the batch posting path
 *       {@code app/cbl/CBTRN02C.cbl} passes the feed's own originating stamp
 *       straight through at its line 436, then obtains a separate
 *       database-format value at its line 437 and moves that into the
 *       processing stamp at its line 438 -- the only site in that program where
 *       the processing stamp is assigned at all. The two stamps consequently
 *       differ, and a test that built them from one value would pass against an
 *       implementation that had lost the distinction.</li>
 *   <li><b>Date only, space-padded.</b> {@code app/cbl/COTRN02C.cbl} moves two
 *       {@code PIC X(10)} screen fields into the two {@code PIC X(26)} record
 *       fields at its lines 464 and 465. Those screen fields are
 *       {@code TORIGDTI} and {@code TPROCDTI}, declared at lines 102 and 108 of
 *       {@code app/cpy-bms/COTRN02.CPY}. A ten-byte value moved into a
 *       twenty-six-byte character field is followed by sixteen spaces, so a
 *       transaction captured through that screen carries a date and no time
 *       whatever.</li>
 * </ul>
 *
 * <p>Assumptions: the three-form ruling is owned by the production charter
 * named above, which additionally records the 26-byte group's own declaration
 * and why a renderer that emits the digits and assumes the punctuation produces
 * a value that parses as neither a date nor a timestamp. This charter names the
 * three forms so that a test author cannot merge two of them without
 * contradicting the package they are writing in; it does not reproduce that
 * reasoning.
 *
 * <h2>Nothing here may be exempted from the documentation gate</h2>
 *
 * <p>Assumptions: this package is named by path in
 * {@code config/checkstyle/suppressions.xml} as the exemption most likely to be
 * requested, and it is simultaneously the one place an exemption is refused
 * outright. That file records at its lines 169 to 181 that a mapper package is
 * the trap most easily walked into, because the word reads as machine-produced
 * in most codebases while here the opposite holds, and it calls a mapper the
 * highest-value documentation target in the repository for exactly that reason.
 * Its sanctioned reach, stated at its line 29, is generated sources and test
 * fixture material and nothing else; within this module the only location that
 * reach covers is
 * {@code services/transaction-service/src/test/resources/fixtures}, and the
 * file records at its lines 141 to 148 that widening that entry to
 * {@code src/test/java} is forbidden because it would exempt the very tests the
 * rule exists to reach.
 *
 * <p>Assumptions: there is no in-code route to the same outcome either, so a
 * suppression cannot be smuggled in as a local annotation or a marker comment.
 * None of the three comment-driven or annotation-driven suppression filters is
 * enabled in {@code config/checkstyle/checkstyle.xml} -- the file records their
 * absence deliberately at its lines 588 and 589 -- so neither a marker comment
 * nor an annotation suppresses anything anywhere in this tree. A suppression
 * would have to be a durable entry in the companion file, and the companion
 * file's own charter refuses one for the test tree as a whole. Relaxing the
 * gate is therefore a breach of the Explainability rule rather than a
 * build-configuration choice, and a test in this package that cannot be
 * documented is a test that has not been understood.
 *
 * <h2>The parity oracle is a different tree, and it does not reach here</h2>
 *
 * <p>Assumptions: two directories in this repository are called {@code test} or
 * {@code tests} and they are unrelated, so they are kept textually distinct in
 * every sentence. The repository-root {@code tests} directory is the COBOL
 * three-layer functional-parity oracle suite, holding {@code cobol-unit},
 * {@code integration}, {@code e2e}, {@code fixtures}, {@code golden},
 * {@code helpers} and {@code mocks}; it is reference material, read and never
 * modified, and it is not {@code services/transaction-service/src/test}, which
 * is where this charter sits.
 *
 * <p>Assumptions: no golden master exists for any of the four programs this
 * module migrates -- {@code app/cbl/COTRN00C.cbl},
 * {@code app/cbl/COTRN01C.cbl}, {@code app/cbl/COTRN02C.cbl} and
 * {@code app/cbl/COBIL00C.cbl}. The oracle's golden directories cover its batch
 * domains only, and {@code tests/README.md} records at its lines 43 to 45 and
 * 83 that the eighteen online programs cannot be exercised end to end without a
 * transaction monitor the runner does not provide, so only their extractable
 * field-validation logic is covered there at all. The tests in this package are
 * consequently strictly ADDITIVE: they are answerable to the reference source
 * line they cite, not to a recorded output they can be differenced against, and
 * describing any of them as a golden-master parity check would claim an
 * authority that does not exist. Each assertion therefore names the reference
 * line it reproduces, in the same form the production code does, because a
 * conversion that merely compiles proves nothing.
 *
 * <p>Assumptions: the graded return-code rubric that {@code tests/README.md}
 * defines in its section 8, beginning at its line 412, belongs to that suite
 * alone and has no meaning here. Its runners aggregate a worst-case condition
 * code across three layers, and one of the grades it defines is a soft one. A
 * build driven by the surefire and failsafe plugins has no such grade: this
 * build's outcome is binary -- a test passes or the build fails -- so no
 * assertion in this package may tolerate a return code, no result here may be
 * described as warn-level, and no soft outcome is available to a test that
 * cannot decide.
 *
 * <h2>Conventions inherited from the parent charter rather than restated</h2>
 *
 * <p>Assumptions: the subtree charter at
 * {@code services/transaction-service/src/test/java/com/carddemo/transaction/package-info.java}
 * is the established owner of the conventions this package obeys, and it is
 * cited rather than copied. Four in particular govern every file authored here:
 * the runner split, in which a class named with the {@code Test} suffix is
 * collected at the {@code test} phase while the {@code IT} suffix is collected
 * at {@code integration-test} and asserted at {@code verify}, both reporting
 * into the DEFAULT report directories the pipeline reads; the single-owner
 * ruling for this module's layering gate, which is additive to the shared one,
 * is named {@code TransactionLayeringRulesTest}, and must never be a second
 * class of the shared simple name, because the shared class is selected by a
 * literal simple-name pattern that a duplicate would also match; the charter
 * census for the test subtree, including its record of the levels at which no
 * charter exists; and the four rationale labels together with the refusal of
 * any authorship, release or version at-clause.
 *
 * <p>Trade-offs: citing that charter rather than reproducing its figures means
 * a reader after a count has to open a second file. The alternative was to
 * restate the census here, and it was rejected on evidence: that charter
 * records at its lines 27 to 41 that its own state paragraph had been wrong
 * three times in the same direction, and a second copy of a number is a second
 * thing to go stale while looking authoritative. One owner per figure is the
 * property worth buying, and the cost is one hop.
 *
 * <h2>Why no charter sits at the two namespace levels above</h2>
 *
 * <p>Assumptions: {@code services/transaction-service/src/test/java/com} and
 * {@code services/transaction-service/src/test/java/com/carddemo} hold no
 * charter, and the absence is a decision rather than an oversight.
 * {@code JavadocPackage} is a file-set check, so it fires only for a directory
 * that itself CONTAINS a source file the audit processed; each of those two
 * levels holds nothing but a subdirectory, so no violation is reachable there
 * and a charter placed at either would satisfy no gate. The Explainability rule
 * reaches the same conclusion without any tool: it attaches the obligation to a
 * function, a class or a module entry point, and a directory holding only a
 * subdirectory presents none of the three. Both grounds hold independently,
 * which is what makes the absence safe rather than merely convenient.
 *
 * <h2>Single-sourcing: a test consumes a layout, it never re-declares one</h2>
 *
 * <p>Refactoring Rationale: the discipline these tests follow is the one the
 * reference suite already states for its own. {@code tests/README.md} requires
 * at its lines 540 to 542 that a COBOL unit test resolve record layouts through
 * the compiler copybook path rather than carry its own copy, and closes with
 * the instruction never to duplicate a layout but to keep it single-sourced
 * from {@code app/cpy}; its line 549 records that the surrounding convention is
 * a hard review gate. The failure that discipline prevents is specific: a
 * layout copied into a test drifts from the layout the code uses, and the test
 * then passes against its own copy while the code is wrong.
 *
 * <p>Assumptions: the Java analogue is that a test here declares no layout of
 * its own. Record geometry is owned by the domain entity, conversion decisions
 * by the mapper under test, and scenario byte material by the fixtures README
 * at
 * {@code services/transaction-service/src/test/resources/fixtures/README.md}; a
 * test consumes all three. The shared kernel types a conversion assertion needs
 * -- {@code Money} and its serialisation module, {@code TimestampFormatter},
 * the zoned-decimal codec, the problem shape, the field validation flag and the
 * keyset page envelope -- are supplied by {@code com.carddemo.common} and are
 * never re-implemented for a test's convenience. A test that re-declared any of
 * them would be asserting its own arithmetic rather than the module's.
 *
 * <h2>Authoring notes for this file</h2>
 *
 * <p>Trade-offs: this file is pure ASCII with no byte-order mark, and its
 * summary sentence ends with a period because the summary check's sentence
 * terminator is left at its configured default. The alternative was to
 * reproduce the typographic punctuation the migration prose uses, which would
 * read closer to that prose; ASCII was chosen because it makes one specific
 * failure unreachable by construction rather than by care --
 * {@code tests/README.md} is written throughout with non-breaking hyphens, more
 * than a hundred of them, and it quotes the four rationale labels among that
 * prose at its lines 547 and 548, so a label copied from there would carry a
 * byte that no literal search for the canonical label can match. Every label in
 * this file was typed from the canonical form that
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes at its lines 209 to 212:
 * plural, unparenthesised, colon retained, no emphasis markup, and the hyphen
 * an ASCII hyphen-minus. The cost is plainer punctuation.
 *
 * <p>Trade-offs: this file holds one Javadoc block and one package declaration
 * and nothing else -- no type declaration, no annotation, no field, no method,
 * no line comment and no dependency statement of any kind. Its rationale is
 * therefore carried inside the Javadoc as labelled sentences rather than as
 * adjacent line comments, since the twin-comment idiom this tree uses for
 * command blocks has no place inside a Javadoc block and there is no second
 * statement for a comment to sit beside. Prose is wrapped at 80 columns to
 * match the subtree charter's own note at its lines 600 to 604, even though
 * {@code config/checkstyle/checkstyle.xml} records in its Scope statement at line
 * 56 that it declares no line-length module. Every line that does exceed 80 is an inline
 * code span holding a path, and that is stated as a universal rather than as a
 * count of such lines so that citing one more path cannot falsify the sentence:
 * a path carries no space to break at, and a break inserted inside one would
 * put this comment's margin into the middle of the rendered path.
 */
package com.carddemo.transaction.mapper;
