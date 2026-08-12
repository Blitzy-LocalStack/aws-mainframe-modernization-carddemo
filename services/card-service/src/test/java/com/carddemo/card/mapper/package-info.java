/**
 * Executable proof of the two data-exposure properties this module's anti-corruption layer owes every
 * caller.
 *
 * <h2>Purpose, and what this package holds</h2>
 *
 * <p>Purpose: this package holds the tests that establish, rather than presume, the two disclosure
 * properties the migration mandates of {@code com.carddemo.card.mapper.CardMapper}. The card
 * verification value is suppressed entirely, so no serialised payload on any route carries it in any
 * form, masked or otherwise. The primary account number is published masked to its last four digits on
 * every response except the one administrative card-detail route authorised to receive it whole, which
 * {@code CardMapper} exposes as a separate disclosure method precisely so that the exception is a named
 * route rather than a branch inside a shared one. The production package one source root away states
 * those properties as its charter; this package is where they stop being a claim.</p>
 *
 * <p>Assumptions: the inventory below is a census of this directory and no longer a projection of one.
 * Both test classes are present:
 *
 * <pre>
 * this directory: 3 java files = 2 tests + 1 charter
 * </pre>
 *
 * <ul>
 *   <li>{@code CardMapperTest} across 17 cases -- the verification-value suppression, the masking of the
 *       primary account number on every ordinary response, the whole-number disclosure on the one
 *       administrative route, the removal of the record's trailing padding, the two identifiers
 *       travelling as digit strings with their leading zeros intact, and the three cases holding the
 *       listing's behaviour when a stored key lies outside the published card-number domain: the page is
 *       served with that row omitted, a single-card read still refuses, and the record of the omission
 *       names the account and no part of the key.</li>
 *   <li>{@code CardExpiryParityTest} across 4 cases -- what an expiry edit does to the stored day. The
 *       reference edits a month and a year and never a day, and composes a day into the stored value
 *       without consulting a calendar, so the target's true date column cannot hold the impossible
 *       combination the reference can; the mapper clamps the day to the target month's last, which is
 *       registered as divergence {@code D-CARD-EXPIRY-DAY-CLAMP}.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: this paragraph previously declared the second name planned rather than
 * missing, on the ground that artifacts land in plan order and this descriptor was authored first. That
 * device outlived its occasion twice over -- {@code CardMapperTest} landed, and a second class landed
 * beside it that the closed set did not admit at all. The marker line above is the form
 * {@code common-lib}'s {@code PackageCharterInventoryTest} re-measures against this directory, and the
 * same test checks each enumerated member's file and each declared case count against the annotations
 * in it, so a projection can no longer survive the artifact it projected.</p>
 *
 * <p>Assumptions: this descriptor governs the test source root only. A separate descriptor under
 * {@code src/main/java} governs the production package of the same name, and the duplication of the
 * package name across the two is correct rather than accidental: the build compiles the two source
 * roots independently into one package namespace, so each root needs its own charter. The division of
 * labour between them is strict. The production charter records what the translation does and why it is
 * allowed to know about copybook representation at all; this one records how the resulting disclosure
 * behaviour is held to account. Neither restates the other.</p>
 *
 * <h2>What the assertions are transcribed from</h2>
 *
 * <p>Assumptions: the record under test is the 150-byte {@code CARD-RECORD} declared at
 * app/cpy/CVACT02Y.cpy:4-11, and that copybook is the normative source for every field this package
 * reasons about. Four of its declarations carry the whole of this package's subject matter:
 * {@code CARD-NUM PIC X(16)} at :5, the number that is masked; {@code CARD-CVV-CD PIC 9(03)} at :7, the
 * value that is suppressed; the field at :9, whose baseline name carries a misspelling and which the
 * target reads into a member named {@code expirationDate} as a target-side naming decision that leaves
 * the baseline spelling untouched; and the 59-byte {@code FILLER} at :11, which pads the six data
 * fields out to the declared record length and reaches neither the entity nor any response.</p>
 *
 * <p>Assumptions: the behavioural sources are the three baseline card programs --
 * app/cbl/COCRDLIC.cbl, the list screen; app/cbl/COCRDSLC.cbl, the detail screen; and
 * app/cbl/COCRDUPC.cbl, the update screen. They are reference material that is read and cited by path
 * and line and never modified, which is why every assertion in this package quotes a line rather than
 * importing a layout.</p>
 *
 * <h2>The verification value is a preserved absence, not a removal</h2>
 *
 * <p>Assumptions: the suppression property is easy to describe wrongly, and describing it wrongly would
 * misdirect anyone auditing the assertions that enforce it. The baseline never put the verification
 * value on a card screen and never accepted it as terminal input. Two independent measurements
 * establish this: a case-insensitive search for the value's name across the three symbolic map
 * copybooks app/cpy-bms/COCRDLI.CPY, app/cpy-bms/COCRDSL.CPY and app/cpy-bms/COCRDUP.CPY returns
 * nothing, and the same search across the three mapsets app/bms/COCRDLI.bms, app/bms/COCRDSL.bms and
 * app/bms/COCRDUP.bms also returns nothing. The value is absent from both the map definitions and the
 * structures generated from them, so no field existed on any of these screens to display it into or to
 * read it from.</p>
 *
 * <p>Assumptions: the two read-only programs go further still. app/cbl/COCRDLIC.cbl declares the decode
 * pair {@code CARD-CVV-CD-X} with its numeric {@code REDEFINES} at :102-104 and refers to neither name
 * anywhere else in its 1459 lines, and app/cbl/COCRDSLC.cbl declares the same pair at :76-78 with the
 * same result across its 887 lines. app/cbl/COCRDUPC.cbl is deliberately excluded from that particular
 * finding, because it does move the value internally between its before-image and its update area; what
 * it does not do, and the reason the screen-level measurement above is the load-bearing one, is put the
 * value into a map. Scoping the claim to the two programs it actually holds for keeps this descriptor
 * checkable against the source instead of merely plausible.</p>
 *
 * <p>Assumptions: on the strength of those two measurements the target preserves an absence the
 * baseline already had for the field declared at app/cpy/CVACT02Y.cpy:7, and adds encryption of the
 * stored form on top of it. That framing matters to the assertions: a test written as though the value
 * were being withdrawn from a screen that once showed it would be asserting against a history that did
 * not happen, and would invite a reviewer to accept a masked rendering as a sufficient outcome. The
 * property being proved is total absence from the serialised form, not reduction of it.</p>
 *
 * <p>Trade-offs: the baseline platform offered no equivalent control, and this package proves a
 * capability rather than a port. The transaction definitions in app/csd/CARDDEMO.CSD carry
 * {@code CONFDATA(NO)} in all eighteen of their stanzas, including :363 for the list transaction bound
 * to {@code COCRDLIC} and :374 for the update transaction bound to {@code COCRDUPC}, so the baseline
 * did not suppress confidential data on the wire and the programs written against it had no facility to
 * ask it to. The migration adds the suppression and masking behaviour at the translation boundary and
 * adds these tests to hold it there; it does not replace or retire the path that ran without them,
 * which remains exactly as it is.</p>
 *
 * <h2>How the properties are asserted</h2>
 *
 * <p>Alternatives Considered: the assertions in this package are made against the serialised JSON that
 * a real Jackson {@code ObjectMapper} produces from the mapped result, not against the accessors of the
 * mapped objects. Reading the accessors was the reasonable alternative and is rejected on a specific
 * ground: an accessor-level assertion inspects the object graph, whereas the property being proved is a
 * property of the wire form. A stray serialisation annotation, a renamed member, an inherited mixin or
 * a visibility default that reintroduced the verification value into the payload would leave every
 * accessor assertion passing while the value travelled to the caller regardless. Serialising through
 * the mapper the application configures asserts the thing the property is actually about, and the wire
 * form is what services/card-service/src/main/resources/openapi/card-api.yaml publishes as this
 * module's contract. The compromise accepted is that a failure implicates two participants, the
 * translation and the serialisation configuration, and so takes one extra step to localise.</p>
 *
 * <p>Assumptions: the assertions in this package are held to the baseline by transcription fidelity
 * against the lines cited above and by this module's own tests, and by nothing else, because no
 * recorded-output comparison exists for any of these paths. tests/README.md:83-85 records that the
 * baseline's online programs cannot be run end to end without a terminal runtime that the build host
 * does not provide, so only their extractable field-validation logic is exercised at all. The house
 * recorded-output comparison covers the batch chain, and none of app/cbl/COCRDLIC.cbl,
 * app/cbl/COCRDSLC.cbl or app/cbl/COCRDUPC.cbl is a batch program, so no recorded output for a card
 * screen exists to compare against and none should be claimed. Stating that plainly is the point: an
 * assertion whose authority is a cited line is auditable, whereas an assertion presented as matching an
 * oracle that does not exist is not.</p>
 *
 * <p>Assumptions: a directory of test material carrying no recorded-output counterpart is established
 * practice in this repository rather than a shortfall. tests/README.md:139-146 describes exactly that
 * arrangement for the export domain, whose records drive a round-trip comparison and which ships input
 * material with no recorded-output directory, and calls the arrangement internally consistent.</p>
 *
 * <h2>What this package does not own</h2>
 *
 * <p>Assumptions: three neighbouring artifacts are authoritative for material this package uses and
 * deliberately does not restate. The record layout and the loadability taxonomy that classifies each
 * test record belong to services/card-service/src/test/resources/fixtures/README.md, which is also the
 * single place the byte composition of those records is written down. The house discipline at
 * tests/README.md:540-542 forbids duplicating a layout and requires it be kept single-sourced, and it
 * applies to test material exactly as it applies to production code, so no byte position and no record
 * classification is re-derived in this descriptor or in the assertions it governs. Layering belongs to
 * the architecture test in {@code common-lib}, which is the sole owner of that rule in the reactor;
 * this package adds no rule of its own about what may import what. The shape of every published
 * response belongs to services/card-service/src/main/resources/openapi/card-api.yaml, the contract of
 * record, so where an assertion needs to know what a caller receives it reads that document rather than
 * hard-coding a second answer that could disagree with it.</p>
 *
 * <h2>Why this descriptor exists, and how its rationales are labelled</h2>
 *
 * <p>Assumptions: the project's Explainability rule requires a docstring on every module entry point at
 * its clause on line 15, a Java package declaration is that entry point, and
 * {@code package-info.java} is the only compilation unit to which package-level Javadoc can attach.
 * Two Checkstyle modules enforce the obligation independently and both must pass: one operates on the
 * file set and requires this file to be present in a directory holding an audited source, the other
 * walks the syntax tree and requires the file to carry a Javadoc block. A file present but empty
 * satisfies the first and fails the second. The gate is bound to the build's validate phase with
 * violations made fatal and test sources audited on the same terms as main sources, so this descriptor
 * is a build prerequisite for the directory rather than a courtesy to a reader.</p>
 *
 * <p>Assumptions: the parameter, return value and exception elements that the rule's docstring
 * specification names describe callable code. A package declares no parameter, returns no value and
 * raises nothing, so all three are omitted here deliberately rather than written out empty. The
 * omission is also the only safe form: config/checkstyle/checkstyle.xml enables a completeness module
 * that rejects an at-clause carrying no description, so an empty tag added to look thorough would fail
 * the very gate this file exists to satisfy.</p>
 *
 * <p>Alternatives Considered: the rationale labels in this file are written in the plural,
 * unparenthesised, colon-terminated form the project rule sets out at its lines 31 to 34 and that
 * docs/CODE_DOCUMENTATION_STANDARD.md reproduces at its lines 209 to 212. The alternative was the
 * idiom that predominates in the reference test suite, which opens a rationale with the category name
 * in the singular and inside parentheses -- live at tests/README.md:66. It is declined because the
 * rule's validation gate at line 43 is the sentence this tree is audited against, and an auditor finds
 * rationales across a polyglot tree by literal string search, no linter parsing prose in a template or
 * a migration script. One spelling makes that search complete; a second makes it silently partial, and
 * a rationale a search cannot find is a rationale a review cannot count. Recorded here so that a later
 * reader who notices the divergence from the majority idiom recognises it as chosen rather than
 * mistaken, and does not harmonise this file back.</p>
 *
 * <p>Assumptions: every quotation this package draws from tests/README.md is transliterated to the
 * ASCII hyphen-minus, U+002D. That file writes its compound terms with the Unicode non-breaking
 * hyphen, U+2011, in 106 places across 77 of its lines, and its lines 530, 542 and 548 contain no ASCII
 * hyphen at all. A quotation copied byte for byte would therefore import a character that is
 * indistinguishable from a hyphen on screen and that no search for the hyphenated term would match,
 * which would quietly exempt the quoting line from exactly the string search the paragraph above
 * depends on. Holding this descriptor and its assertions to plain ASCII removes the hazard by
 * construction rather than by inspection.</p>
 */
package com.carddemo.card.mapper;
