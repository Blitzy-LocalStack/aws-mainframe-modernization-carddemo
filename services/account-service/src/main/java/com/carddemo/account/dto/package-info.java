/**
 * Wire contracts of the account bounded context, derived field for field from the baseline record
 * copybooks and BMS symbolic maps rather than from any persistence entity.
 *
 * <p>The records under this package are the request and response shapes of four endpoint families:
 * the account view, the account update, the customer read paths, and the card cross-reference
 * lookups including the lookup by account that stands in for the baseline's alternate index. They
 * carry transport representation and nothing else. No record here holds a business rule, reads a
 * row, or knows that a database exists.</p>
 *
 * <h2>Why this descriptor exists</h2>
 *
 * <p>Assumptions: two independent obligations put this file here, and either one alone would be
 * enough. The project Explainability rule requires a docstring on every module entry point at its
 * L15, and in Java a package declaration is that entry point, so {@code package-info.java} is the
 * only compilation unit in which a package-level docstring can be written at all. This file is
 * therefore mandated by the rule rather than by the migration plan's own file inventory, which
 * wildcards this directory as request and response records only; the plan groups artifacts of this
 * kind as rule-mandated, existing so that the rule's gate is machine-checked instead of
 * aspirational.</p>
 *
 * <p>The second obligation is mechanical, and it is build-fatal. {@code JavadocPackage} is declared
 * at <em>Checker</em> level in {@code config/checkstyle/checkstyle.xml} L245, outside the
 * {@code TreeWalker} that opens at L259, so it is a file-set check: it demands that a
 * {@code package-info.java} file <em>exist</em> in any directory holding a processed compilation
 * unit. {@code MissingJavadocPackage} is declared at L378 <em>inside</em> that {@code TreeWalker},
 * and it demands that the file <em>carry</em> Javadoc. A descriptor holding nothing but a bare
 * {@code package} statement satisfies the first and fails the second, which is why neither the
 * omission nor the empty-file shortcut was available. The gate runs on the Maven {@code validate}
 * phase under execution id {@code checkstyle-documentation-gate} at {@code services/pom.xml} L816 to
 * L817, with {@code failOnViolation} true at L906 and {@code violationSeverity} warning at L907, so
 * a miss stops every local build before compilation rather than only in continuous integration.
 * Every other record authored in this directory is unbuildable until this file exists.</p>
 *
 * <p>Assumptions: the file-set half of that pairing, {@code JavadocPackage} at
 * {@code config/checkstyle/checkstyle.xml} L245, fires only for a directory that already holds a
 * processed compilation unit, so the necessity of this descriptor is contingent on this package
 * holding at least one record. It is not contingent on which records those are, and it is independent
 * of the tree half at L378, which reads this file's content once the file is reached at all. The
 * contingency is
 * recorded because it names the one circumstance in which this descriptor's absence would go
 * unreported: delete it from a directory holding no other compilation unit and the file-set check has
 * nothing to fire on, so the omission passes rather than failing the build. Add a single record back
 * and the same omission becomes a build failure. That asymmetry is a property of the check and not of
 * this package, and it is written down so that a green build on an empty directory is not mistaken
 * for evidence that the descriptor was never required.</p>
 *
 * <p>The rule's parameter, return-value and exception elements at L19 to L21 describe callable code
 * and have no counterpart on a package declaration, so they are omitted here deliberately rather
 * than written out empty; L21 is itself qualified "where applicable". Fabricating a block tag to
 * look compliant would add unverifiable content and would offend the specificity requirement at
 * L41. No authorship or version tag appears either: the formatting checks that would demand one are
 * absent from that configuration, and the written convention at
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} names four docstring elements, none of which is such a
 * tag.</p>
 *
 * <h2>The two kinds of source these contracts derive from</h2>
 *
 * <p>The distinction between the two kinds is load bearing rather than bookkeeping. Record copybooks
 * fix <em>record</em> widths; BMS symbolic maps fix <em>screen</em> widths; and where the two
 * disagree about the same value, which they do, a record in this package must not silently inherit
 * whichever width its author happened to read first.</p>
 *
 * <p>Three record copybooks supply the record widths. {@code app/cpy/CVACT01Y.cpy} declares
 * {@code 01 ACCOUNT-RECORD} at L4 across 300 bytes. {@code app/cpy/CVCUS01Y.cpy} declares
 * {@code 01 CUSTOMER-RECORD} at L4 across 500 bytes. {@code app/cpy/CVACT03Y.cpy} declares
 * {@code 01 CARD-XREF-RECORD} at L4 across 50 bytes, being {@code XREF-CARD-NUM PIC X(16)} at L5,
 * {@code XREF-CUST-ID PIC 9(09)} at L6 and {@code XREF-ACCT-ID PIC 9(11)} at L7.</p>
 *
 * <p>Two symbolic maps supply the screen widths, and both are UPPERCASE {@code .CPY} on disk, as are
 * all seventeen files in {@code app/cpy-bms}. A case-sensitive filesystem does not resolve a
 * lowercase spelling of either name, so a citation written that way names no file at all.
 * {@code app/cpy-bms/COACTVW.CPY} runs 464 lines and declares {@code 01 CACTVWAI} at L17 with 37
 * named data fields, its output group following at L241 as
 * {@code 01 CACTVWAO REDEFINES CACTVWAI}. {@code app/cpy-bms/COACTUP.CPY} runs 668 lines and
 * declares {@code 01 CACTUPAI} at L17 with 54 named data fields, its output group at L343.</p>
 *
 * <p>The copybook is normative. A field's {@code PICTURE} clause determines the Java type and the
 * documented width of the component derived from it, and every {@code FILLER} is dropped with the
 * drop recorded against the record it came from, so that a later reader can tell a deliberate
 * omission from an oversight: {@code FILLER PIC X(178)} at {@code CVACT01Y.cpy} L17,
 * {@code FILLER PIC X(168)} at {@code CVCUS01Y.cpy} L23 and {@code FILLER PIC X(14)} at
 * {@code CVACT03Y.cpy} L8 have no counterpart component here.</p>
 *
 * <p>Exactly one field is renamed. {@code ACCT-EXPIRAION-DATE} at {@code CVACT01Y.cpy} L11 is
 * misspelled in the baseline, and the component derived from it reads {@code expirationDate}. The
 * baseline is reference material and is never edited: this is a target-side rename, and the
 * divergence is registered in {@code docs/architecture/cobol-to-service-traceability.md}. No other
 * field in this package is renamed, and the similarly misspelled fields elsewhere in the baseline
 * belong to other bounded contexts and are not this package's concern.</p>
 *
 * <h2>Two shapes for one record, because two maps declare it two ways</h2>
 *
 * <p>Assumptions: field-for-field derivation forces the view path and the update path onto two
 * distinct record shapes rather than one shared shape, and the two maps settle it between them. The
 * single sharpest instance is the national identifier, carried as one {@code PIC X(12)} field on
 * {@code app/cpy-bms/COACTVW.CPY} L132 and as three fields totalling nine declared characters on
 * {@code app/cpy-bms/COACTUP.CPY} L168, L174 and L180; the dates below differ the same way. Both maps
 * are cited by name throughout, because the two happen to carry several of these declarations at the
 * same line numbers and a bare line reference would be ambiguous between them.</p>
 *
 * <p>{@code app/cpy-bms/COACTVW.CPY} keeps each date whole, at {@code ADTOPENI PIC X(10)} L72,
 * {@code AEXPDTI PIC X(10)} L84 and {@code AREISDTI PIC X(10)} L96, and it composes the national
 * identifier into a single {@code ACSTSSNI PIC X(12)} at L132. {@code app/cpy-bms/COACTUP.CPY}
 * decomposes every date into a four-two-two triple -- {@code OPNYEARI PIC X(4)},
 * {@code OPNMONI PIC X(2)} and {@code OPNDAYI PIC X(2)} at its L72, L78 and L84; the expiry triple at
 * its L96, L102 and L108; the reissue triple at its L120, L126 and L132; the date-of-birth triple at
 * its L186, L192 and L198 -- and it splits the national identifier three ways, at
 * {@code ACTSSN1I PIC X(3)} its L168, {@code ACTSSN2I PIC X(2)} its L174 and
 * {@code ACTSSN3I PIC X(4)} its L180.</p>
 *
 * <p>Alternatives Considered: collapsing the two into one shared shape. Rejected, because a single
 * shape would have to adopt one of the two decompositions and then convert at the boundary for the
 * other -- recomposing {@code OPNYEARI}, {@code OPNMONI} and {@code OPNDAYI} at
 * {@code app/cpy-bms/COACTUP.CPY} L72, L78 and L84 into the whole {@code ADTOPENI PIC X(10)} that
 * {@code app/cpy-bms/COACTVW.CPY} L72 declares, or splitting it the other way. That conversion is a
 * representation concern, and the one place this migration reserves for such a concern is the mapper
 * package named below, so putting it inside a transport record would breach the same boundary this
 * charter draws. Two shapes cost more declarations and buy a package in which every component's width
 * is the width its own map declares, checkable against one cited line.</p>
 *
 * <h2>No record here derives from an entity</h2>
 *
 * <p>Alternatives Considered: shaping these records from the persistence entities under
 * {@code com.carddemo.account.domain} and serialising those directly, which is the shorter route and
 * is rejected outright. No type in this package imports an entity from that package in any form --
 * not as an import, not as a fully qualified name and not as a string resolved reflectively -- and
 * consequently this package declares no dependency on any sibling package at all. The reason is that
 * {@code com.carddemo.account.mapper} is the sole boundary at which representation concerns may
 * appear: fixed-width representation, zoned-decimal sign overpunch, dropped {@code FILLER}, masking
 * of the primary account number to its last four digits, encryption of the national identifier at
 * {@code CVCUS01Y.cpy} L17 and the government-issued identifier at L18, and the single rename of the
 * misspelled field at {@code CVACT01Y.cpy} L11. Deriving a transport record from an entity would
 * spread those concerns across two packages and leave no one place a reviewer could read them, while
 * each of them needs its justification written beside it at the point of use.</p>
 *
 * <p>Layering has exactly one mechanical owner, the shared architecture test at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java},
 * which asserts that a domain type depends on no transport or infrastructure type at L396, that no
 * bounded context depends on another context's domain package at L457, and that no type on the money
 * path declares an IEEE-754 binary floating point type at L486. Those are assertions in a running
 * test rather than sentences in a document, so they fail a build instead of surviving a review.
 * {@code ImportControl} is deliberately absent from the Checkstyle configuration so that this
 * boundary has one owner and not two.</p>
 *
 * <h2>Shared contracts are consumed here and never re-declared</h2>
 *
 * <p>Assumptions: one former copybook inclusion becomes exactly one import from the single package
 * that owns that contract, which is the Java counterpart of compiling every baseline program against
 * one copybook path. The house precedent is explicit rather than inferred:
 * {@code tests/README.md} L268 records the compiler invocation carrying {@code -I app/cpy}, and its
 * L540 to L542 require a record layout to be resolved through that same path and never duplicated,
 * kept single-sourced instead. Four shared contracts are relied on by the records here, and not one
 * of them is re-declared in this package.</p>
 *
 * <ul>
 *   <li>{@code com.carddemo.common.money.Money}, with
 *       {@code com.carddemo.common.money.MoneyModule} as its serialisation, which together render an
 *       amount as a quoted JSON string.</li>
 *   <li>{@code com.carddemo.common.error.ApiError}, the problem shape whose per-field array is typed
 *       by its own nested {@code ApiError.FieldError} record, declared at L508 of that file as a
 *       field name, a validation state and a message.</li>
 *   <li>{@code com.carddemo.common.validation.FieldValidationFlag}, the validation triad
 *       {@code VALID}, {@code NOT_OK} and {@code BLANK} declared at L218, L229 and L240 of that
 *       file.</li>
 *   <li>{@code com.carddemo.common.web.PageResponse}, the keyset envelope, parameterised at the
 *       controller and service layers over the record types declared here.</li>
 * </ul>
 *
 * <p>Those four are not reused merely as a preference: they arrive already wired. The shared module
 * publishes {@code com.carddemo.common.CardDemoCommonAutoConfiguration} through its own registration
 * resource, and this service's component scan is rooted at {@code com.carddemo.account}, which does
 * not reach the shared kernel at all. A locally declared substitute would therefore not replace the
 * shared contract; it would sit alongside it, and one concept would have two definitions with
 * nothing in a build log to say which one a given response used.</p>
 *
 * <h2>Records rather than classes, and no accessor generation</h2>
 *
 * <p>Members of this package are Java 21 {@code record} types by preference, and that preference
 * carries a documentation consequence better stated here than discovered through a failing build.
 * {@code JavadocType} is configured with {@code allowMissingParamTags} false at
 * {@code config/checkstyle/checkstyle.xml} L413, and {@code MissingJavadocType} lists
 * {@code RECORD_DEF} among its tokens at L311, so a record requires one parameter block tag on the
 * record's own type Javadoc for every component it declares, while
 * {@code NonEmptyAtclauseDescription} at L470 requires each of those descriptions to be non-empty.
 * A shape following the update map, whose named data fields number 54, therefore carries a described
 * tag for every component it declares, and it is the widest shape in this package. That cost is
 * accepted because these are fixed-width contracts, and the tag is the one place a component's
 * provenance and its declared width can be stated immediately next to it.</p>
 *
 * <p>Alternatives Considered: generating the accessors, which would remove most of that typing. It
 * is rejected, and no accessor-generation library is a declared dependency of this module.
 * {@code MissingJavadocMethod} is configured with {@code allowMissingPropertyJavadoc} false at L365
 * and with {@code allowedAnnotations} cleared at L366, so an accessor must carry Javadoc of its own;
 * a generated member has nowhere to hold the docstring the Explainability rule requires at its L15,
 * so generation would fail the gate rather than satisfy it. A record's canonical accessors are
 * covered instead by the type-level tags described above, which is why the record form satisfies both
 * the rule and the gate without any generation at all.</p>
 *
 * <h2>Long identifiers and amounts travel as digits-only strings</h2>
 *
 * <p>Assumptions: the baseline treats these values as characters on the wire and as numbers only
 * inside arithmetic, so the components derived from them are declared as strings validated for digits
 * and never as JSON numbers. The shortest demonstration is that {@code app/cbl/COACTUPC.cbl} L675
 * declares the current balance {@code PIC X(12)} and only its redefinition at L676 to L677 is
 * {@code PIC S9(10)V99}. Three independent lines of evidence establish the general case, set out in
 * the three paragraphs below, and the third settles a genuine disagreement between the two maps.</p>
 *
 * <p>First, the update program holds every numeric of its before-image as a character field with a
 * numeric {@code REDEFINES} laid over it. {@code app/cbl/COACTUPC.cbl} declares
 * {@code 05 ACUP-OLD-DETAILS} at L669 and the group runs to L756, ending exactly where
 * {@code 05 ACUP-NEW-DETAILS} begins at L757. Inside it, {@code ACUP-OLD-CURR-BAL PIC X(12)} at L675
 * is redefined at L676 to L677 as {@code PIC S9(10)V99}; the account identifier at L671 is
 * {@code PIC X(11)} redefined at L672 to L673 as {@code PIC 9(11)}; the credit limit at L678 and the
 * cash credit limit at L681 take the same treatment, as does the tail field at L754, a
 * {@code PIC X(03)} redefined at L755 to L756 as {@code PIC 9(03)}. The cleanest single proof that
 * the character view is the transport view while the numeric view is the arithmetic view is that the
 * program's own comparison reads the numeric views and not the character ones, at L4117, L4119,
 * L4121, L4123 and L4125 for the five money fields.</p>
 *
 * <p>Second, every money field on both symbolic maps is alphanumeric {@code PIC X(15)} rather than a
 * numeric picture: on the view map at L78, L90, L102, L108 and L120, and on the update map at L90,
 * L114, L138, L144 and L156.</p>
 *
 * <p>Third, the two maps disagree about the account identifier itself, and the target adopts the
 * narrower of the two admitted value sets. {@code app/cpy-bms/COACTUP.CPY} L60 declares
 * {@code ACCTSIDI PIC X(11)}, alphanumeric, while {@code app/cpy-bms/COACTVW.CPY} L60 declares
 * {@code ACCTSIDI PIC 99999999999}, numeric. The update form is taken for both paths, so the
 * component is a string constrained to eleven digits. Reading the view form as the contract instead
 * would admit a value the update path refuses, and a transport contract that accepts on one path what
 * it rejects on the other is a contract with two meanings.</p>
 *
 * <h2>Money-bearing components are typed as the shared money type</h2>
 *
 * <p>Alternatives Considered: emitting an amount as a JSON number, which reads to a client author as
 * a convenience and is rejected. A JSON number is parsed into an IEEE-754 binary floating point value
 * by most clients by default, and {@code ACCT-CURR-BAL PIC S9(10)V99} at
 * {@code app/cpy/CVACT01Y.cpy} L7 carries twelve significant digits, which such a representation
 * cannot hold exactly. The five money fields of the account record all share that picture -- L7, then
 * L8 {@code ACCT-CREDIT-LIMIT}, L9 {@code ACCT-CASH-CREDIT-LIMIT}, L13
 * {@code ACCT-CURR-CYC-CREDIT} and L14 {@code ACCT-CURR-CYC-DEBIT} -- and all five are zoned decimal
 * with a sign overpunch rather than packed decimal, which is why they are decoded through the shared
 * zoned-decimal codec and never through a general-purpose numeric parse.</p>
 *
 * <p>A response component carrying an amount is consequently typed {@code Money} and not a
 * general-purpose arbitrary-precision decimal, and that distinction is not stylistic. The shared
 * module's serialiser is bound to the {@code Money} type alone, at
 * {@code services/common-lib/src/main/java/com/carddemo/common/money/MoneyModule.java} L271. A
 * component typed as a general decimal is not matched by it and renders as a bare JSON number: the
 * code compiles, the service starts, the response is well formed, and the amount has become inexact
 * at exactly the boundary a balance or a credit limit is read at. That failure mode is recorded as
 * measured rather than assumed at L124 to L132 of that same file, alongside the converse mistake, in
 * which a {@code Money}-typed component reaches serialisation with no handler registered and renders
 * as three sign predicates with the amount absent from the payload altogether. Neither outcome
 * reports itself in a build log, a startup log or a response status.</p>
 *
 * <p>Assumptions: that registration is not written in this service's Java at all, and a reader
 * looking for it in the application entry point will not find it there. The shared module publishes a
 * provider-configuration file for the platform service-provider mechanism at
 * {@code services/common-lib/src/main/resources/META-INF/services/tools.jackson.databind.JacksonModule},
 * naming the module class, and the framework's serialisation configuration performs its
 * find-and-add-modules step by default, so the handlers are present in every service carrying the
 * shared module on its classpath. IEEE-754 binary floating point types are barred from the money path
 * outright, and the bar is asserted by the shared architecture test rather than left to review.</p>
 *
 * <h2>Message text is reproduced verbatim, at the program-side width</h2>
 *
 * <p>Assumptions: every user-visible string these records carry is reproduced character for character
 * from its originating copybook or program, and its whitespace is never normalised. The width contract
 * is the program-side one, {@code 05 WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COACTVWC.cbl} L117
 * and at {@code app/cbl/COACTUPC.cbl} L479, seventy-five characters in both programs. It is
 * deliberately not the screen field's width, which is {@code ERRMSGI PIC X(78)} at
 * {@code app/cpy-bms/COACTVW.CPY} L240 and {@code app/cpy-bms/COACTUP.CPY} L324. The screen field is
 * three characters wider than the text any program puts into it, so treating the wider figure as the
 * contract would license three characters the baseline never emits.</p>
 *
 * <p>Two neighbouring width regimes are named here because each is easy to attribute to the wrong
 * file. {@code app/cpy/CSMSG01Y.cpy} is a fifty-character, two-message regime and not a
 * seventy-five-character one: it declares {@code 01 CCDA-COMMON-MESSAGES} at L17, with
 * {@code CCDA-MSG-THANK-YOU PIC X(50)} at L18 to L19 and
 * {@code CCDA-MSG-INVALID-KEY PIC X(50)} at L20 to L21, and holds nothing else. The abend surface is
 * a different file again, {@code app/cpy/CSMSG02Y.cpy}, which declares {@code 01 ABEND-DATA} at L21
 * over {@code ABEND-CODE PIC X(4)} at L22, {@code ABEND-CULPRIT PIC X(8)} at L24,
 * {@code ABEND-REASON PIC X(50)} at L26 and {@code ABEND-MSG PIC X(72)} at L28, being 134 bytes in
 * total; that surface is carried by {@code com.carddemo.common.error.AbendDetail} and never by a
 * record declared here. That file is 35 lines long in total, so a citation reaching past its end
 * names nothing and cannot be written.</p>
 *
 * <p>Assumptions: message text is directly verifiable against the baseline even though behaviour in
 * this context is not. {@code tests/README.md} L83 to L85 records that the online programs cannot run
 * end to end without a CICS runtime, which the runner does not have, and that only their extractable
 * field-validation logic is unit-tested; the business-rules section opening at its L553 names batch
 * programs only. There is consequently no golden-master oracle for the account view or the account
 * update path, and no such claim is made here. String equality against the copybook and program lines
 * cited above is the verification that does apply, and it applies character for character.</p>
 *
 * <h2>The re-entry gate is severed, and a field error is data</h2>
 *
 * <p>Refactoring Rationale: a validation failure is reported by this package as a structured
 * per-field error array in the response body, replacing a screen-attribute assignment that the
 * baseline gated on a session flag. {@code app/cpy/CSSETATY.cpy} is the template being replaced, and
 * it is a procedure-division macro fragment rather than a data record: its three substitution
 * placeholders are filled in per call site. It tests the field's validation flag at L18 and L19,
 * moves the red colour attribute into that field's colour subfield at L21 to L22, tests specifically
 * for blank at L23, moves a literal asterisk into that field's output subfield at L24 to L25, and
 * closes at L26 and L27.</p>
 *
 * <p>L20 is the clause that does not survive. The whole assignment is conjoined with
 * {@code AND CDEMO-PGM-REENTER}, the pseudo-conversational re-entry flag declared at
 * {@code app/cpy/COCOM01Y.cpy} L29 as {@code CDEMO-PGM-CONTEXT PIC 9(01)} with its two condition
 * names at L30 and L31, so in the baseline a field is highlighted only on a turn that is itself a
 * re-entry. The target holds no such flag anywhere: a stateless request carries no turn count, so
 * field-error rendering is driven purely by the response body. The observable consequence is stated
 * rather than glossed over. A validation failure on a first submission now returns field errors where
 * the baseline would have returned none, that divergence is intended, and it is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. What the replaced approach cost is the
 * reason to replace it: presentation state had to be remembered across a turn boundary for the error
 * display to work at all, and remembering it is precisely the property that stops a service being
 * scaled horizontally without a session store.</p>
 *
 * <p>The asterisk marker is preserved rather than discarded, and here it is output-only. In the
 * baseline the terminal echoed that marker back on the following turn, which is why the update
 * program treats an incoming asterisk as equivalent to blank, at {@code app/cbl/COACTUPC.cbl} L1051
 * and L1052. A stateless request has no echo, so no record in this package accepts the marker as
 * input; it is carried on the response side alone, and its canonical value lives with the shared
 * validation triad rather than as a literal declared here. No re-entry discriminator, resubmission
 * flag or turn counter appears anywhere in this package, and none may be introduced.</p>
 *
 * <h2>What this package never contains</h2>
 *
 * <p>These are standing prohibitions on what may be authored here, not a record of anything removed.
 * Each absence is a decision, and writing them down is what makes the boundary reviewable against a
 * proposed addition rather than only against the files already present.</p>
 *
 * <ul>
 *   <li>No locally declared error, validation, pagination, exception or message-catalogue type. Each
 *       of the four contracts named above belongs to the shared kernel, and a local substitute would
 *       give one concept two definitions.</li>
 *   <li>No request record for any read path. Selection context is decomposed into the request path
 *       and query rather than carried in a body, so the account view, the customer reads and the
 *       cross-reference lookups take no request body at all and there is nothing for such a record to
 *       carry.</li>
 *   <li>No dedicated conflict record. An optimistic-lock failure is rendered as {@code ApiError} by
 *       the shared {@code GlobalExceptionHandler}, which already maps that failure to HTTP 409, so a
 *       second shape for the same response would compete with it.</li>
 *   <li>No list wrapper for the lookup by account. That is {@code PageResponse}, parameterised at the
 *       controller and service layers over the record types declared here.</li>
 *   <li>No position-based paging vocabulary of any kind. The browse contract is keyset only, and a
 *       component naming a position would imply a second paging model that does not exist.</li>
 *   <li>No card verification value on any response, in any shape. No record here declares a component
 *       for it.</li>
 * </ul>
 *
 * <p>Trade-offs: this charter states the package's target contract rather than a listing of the
 * directory beside it, so it has to be revised whenever that contract itself changes. The cost is
 * accepted because a descriptor that describes only whatever happens to be present cannot say what
 * may not be added, and the closed set is the half of a package contract a reader cannot reconstruct
 * from the files. The contract being elaborated is not invented here either: the context root's own
 * descriptor already assigns this package its one-line constraint at
 * {@code services/account-service/src/main/java/com/carddemo/account/package-info.java} L511 to L513,
 * naming transport records that follow the symbolic-map and copybook field order and widths, with
 * amounts and long identifiers as digits-only strings and no local page or error type. Every section
 * above expands one clause of that sentence, so the two descriptors can be read against each other and
 * a divergence between them is visible rather than latent.</p>
 */
package com.carddemo.account.dto;
