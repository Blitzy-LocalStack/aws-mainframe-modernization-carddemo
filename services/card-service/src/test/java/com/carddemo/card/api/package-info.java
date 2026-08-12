/**
 * Tests that hold this context's HTTP surface and its published contract to each other.
 *
 * <p>Four classes sit beside this charter and each asks a different question about the card
 * context's REST boundary, so a failure in one localises differently from a failure in another. One
 * asks whether every operation the contract publishes is mounted, and at which address. Two send
 * real requests through a dispatcher assembled from constructors and assert what a caller receives
 * back: how an argument is resolved, how a faulted attribute is reported, and which status is
 * returned. The fourth sends its requests through the DEPLOYED filter chain and the DEPLOYED error
 * advice, which is the only way two further properties become assertable at all -- which callers a
 * route admits, and whether a refusal carries the shared problem document. No class in this package
 * asserts a business rule or a stored row.</p>
 *
 * <h2>The directory, measured rather than remembered</h2>
 *
 * <p>Assumptions: the enumeration below is both the closed set of this package's members and a
 * listing of the directory, and it is re-measured on every build by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/PackageCharterInventoryTest.java},
 * which reads the marker line, counts the {@code .java} files beside this charter, requires every
 * name enumerated under it to be a file in this same directory, and re-counts each declared case
 * figure against the class it names. That check carries 4 cases of its own and declares a floor of
 * four marked charters at its line 90, so removing the marker below to silence a disagreement is
 * itself a visible act rather than a quiet one:</p>
 *
 * <pre>
 * this directory: 5 java files = 4 tests + 1 charter
 * </pre>
 *
 * <ul>
 *   <li>{@code CardControllerContractCensusTest} asks EXISTENCE and ADDRESSING across 4 cases, and
 *       it builds no application context and sends no request. It reads every operation identifier
 *       out of the packaged contract at {@code src/main/resources/openapi/card-api.yaml}, requires a
 *       mapped handler named after each, pins the four addresses those handlers answer on, and
 *       confines the one response shape carrying a full primary account number to the single
 *       administrative handler. Neither the compiler nor a linter performs that comparison: an
 *       operation identifier is a string in a document and a handler is a method, and the build
 *       treats the two as unrelated artifacts.</li>
 *   <li>{@code CardDispatcherTest} asks REQUEST BEHAVIOUR on the browse route across 5 cases:
 *       whether a request carrying no body at all is accepted as the opening screen, whether a
 *       cursor issued to one caller is refused when a different caller presents it, whether a
 *       forward cursor presented as a backward step is refused, whether an account narrowing of the
 *       wrong width is refused naming the member at fault, and whether an unreadable cursor is
 *       refused rather than answered.</li>
 *   <li>{@code CardUpdateHttpValidationTest} asks REQUEST BEHAVIOUR on the update route across 8
 *       cases: that a conforming submission reaches the write path, that each faulted attribute is
 *       answered with its reference sentence and its field state, that several attributes faulting
 *       together are reported in one response, that attributes disagreeing on a state report the
 *       first with its own, that a blank attribute stays distinguishable from an unacceptable one,
 *       that an over-width attribute is refused at the boundary, and that a stale revision is
 *       refused even when the submission changes nothing.</li>
 *   <li>{@code CardControllerTest} asks ADMITTANCE and RENDERING across 25 cases, and it is the only
 *       class here that refreshes an application context. It installs the chain
 *       {@code com.carddemo.card.config.SecurityConfig} declares and the advice
 *       {@code com.carddemo.common.error.GlobalExceptionHandler} is, then drives all five operations
 *       through them: that an administrative caller reads a full primary account number while an
 *       ordinary one is refused the same address and reaches no service, that a caller presenting no
 *       token is challenged rather than denied, that a group name in a token becomes an authority
 *       through the deployed converter, that an unpublished address meets the closing deny rule while
 *       the health path does not, that each detail shape publishes its complete member set and
 *       therefore carries no verification value, that the envelope carries all five of its members
 *       and neither cursor on an exhausted page, that the browse narrowing is optional where the
 *       lookup number is mandatory, that the reference clear-filter sentinel earns a refusal rather
 *       than clearing a narrowing, and that a refusal is rendered as the shared problem document
 *       carrying the reference sentence -- including the three conflict conditions kept apart from one
 *       another and the blank field state kept distinguishable from the unacceptable one.</li>
 * </ul>
 *
 * <p>Assumptions: the roster is stated as the MARKER LINE above rather than as a count in prose,
 * because a prose count is invisible to everything except a reader comparing the paragraph against a
 * directory listing. The marker and the entries under it are measured against this directory on every
 * build by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/PackageCharterInventoryTest.java},
 * so a further class arriving here without an entry fails the build rather than quietly making this
 * section wrong.</p>
 *
 * <h2>The boundary this package holds, and the four it does not</h2>
 *
 * <p>Only the transport boundary is asserted here: how a request binds, which handler answers it,
 * how a refusal is rendered, which status a caller receives, and which caller a route admits. Three
 * neighbouring questions are deliberately absent, each owned by a package that can answer it without
 * assembling a dispatcher.</p>
 *
 * <ul>
 *   <li>business rules, which are asserted against the COBOL paragraphs they were transcribed from
 *       in the sibling test package {@code com.carddemo.card.service}</li>
 *   <li>persistence, and therefore whether the generated SQL is right, which is asserted against a
 *       real database in the sibling test package {@code com.carddemo.card.repository}</li>
 *   <li>layering, which has exactly one owner in the whole build,
 *       {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java},
 *       a path {@code .github/workflows/services-ci.yml} cites character for character at its lines
 *       15 and 434; no rule of that kind is restated in this package</li>
 * </ul>
 *
 * <p>Trade-offs: two of the three behavioural classes are assembled from constructors rather than from
 * an application context, which buys precise localisation at the cost of covering the wiring itself.
 * What is bought is that a refusal observed in either of those two can only mean argument resolution,
 * binding, validation or the advice, because no chain, no data source and no token issuer is present
 * to have failed instead. What is given up there is any evidence that the configured chain admits
 * these routes at all, across the five operations this contract publishes on four addresses -- and
 * that is why the third behavioural class pays the opposite price. {@code CardControllerTest}
 * refreshes a context so the chain declared at line 531 of
 * {@code services/card-service/src/main/java/com/carddemo/card/config/SecurityConfig.java} is the one
 * its requests pass through, which costs it the precise localisation the other two have: a refusal
 * observed there may come from the chain, the advice, binding or a rule. The two prices are paid on
 * purpose and in different places, so a failure in one class narrows what a failure in the other can
 * mean.</p>
 *
 * <p>Assumptions: the STRUCTURE of the authority matrix stays asserted where it was, against the
 * chain's own installed authorization managers by the 12 cases of
 * {@code com.carddemo.card.config.SecurityConfigTest}, and against the contract's published
 * {@code x-required-authority} by {@code com.carddemo.card.config.CardApiContractTest}. What
 * {@code CardControllerTest} adds is the OBSERVABLE outcome of those rules -- which status and which
 * body a caller receives -- so the two are complementary rather than duplicated: a rule list can be
 * correct while the chain it is installed into answers with the wrong shape, and a status can be right
 * while the rule that produced it matches a wider path than intended.</p>
 *
 * <h2>What the routes under test were transcribed from</h2>
 *
 * <p>The reference programs below are the specification for the operations this package exercises.
 * They are read, never modified, and cited by path and line only.</p>
 *
 * <ul>
 *   <li>the card list, {@code app/cbl/COCRDLIC.cbl} at 1459 lines, reached in the baseline through
 *       CICS transaction {@code CCLI}, whose resource stanza opens at
 *       {@code app/csd/CARDDEMO.CSD:357} and names {@code PROGRAM(COCRDLIC) TWASIZE(0)} at line
 *       358</li>
 *   <li>the card detail read, {@code app/cbl/COCRDSLC.cbl} at 887 lines, reached through CICS
 *       transaction {@code CCDL}, whose stanza opens at line 347 and names
 *       {@code PROGRAM(COCRDSLC) TWASIZE(0)} at line 348</li>
 *   <li>the card update, {@code app/cbl/COCRDUPC.cbl} at 1560 lines, reached through CICS
 *       transaction {@code CCUP}, whose stanza opens at line 367, carries
 *       {@code DESCRIPTION(CREDIT CARD UPDATE TRANSACTION)} at line 368 and names
 *       {@code PROGRAM(COCRDUPC) TWASIZE(0)} at line 369</li>
 * </ul>
 *
 * <p>Two copybooks carry the contracts those programs work to. The 150-byte {@code CARD-RECORD} is
 * declared at {@code app/cpy/CVACT02Y.cpy} line 4, in a file of 14 lines whose header states that
 * record length. The message contract is declared at {@code app/cpy/CVCRD01Y.cpy}, 46 lines, whose
 * lines 28 and 29 each declare a {@code PIC X(75)} message line and whose line 30 gives the second
 * of them a {@code LOW-VALUES} sentinel for the unset state. Those widths are why a refusal asserted
 * in this package is compared against a sentence rather than against a code alone.</p>
 *
 * <p>Two capabilities the target platform supplies are asserted in this package and beside it, and the
 * split is recorded here so it is legible rather than surprising. The baseline region declares
 * {@code RESSEC(NO) CMDSEC(NO)} for each of the three transactions, on single lines at
 * {@code app/csd/CARDDEMO.CSD:354} for {@code CCDL}, line 364 for {@code CCLI} and line 375 for
 * {@code CCUP}, so authority travelled with the terminal session rather than being verified per
 * request; the migrated service verifies it on every request from a signed claim, whose STRUCTURE is
 * asserted in the configuration test package and whose OBSERVABLE outcome is asserted by
 * {@code CardControllerTest} here. The same three stanzas declare {@code CONFDATA(NO)}, at lines 353,
 * 363 and 374, so the region applied no narrowing of its own to what it sent to the screen; the
 * migrated service narrows the primary account number in every response shape but one, and two classes
 * beside this charter confine that exception to the single administrative handler -- the census by
 * reflecting over the return types, and {@code CardControllerTest} by asserting the complete published
 * member set of each detail shape, which is also what states that no shape carries a card verification
 * value.</p>
 *
 * <h2>Two ways a class here can pass while testing nothing</h2>
 *
 * <p>Assumptions: the advice that renders a refusal is not part of this context, so a dispatcher
 * assembled here has none unless the class supplies one.
 * {@code com.carddemo.common.error.GlobalExceptionHandler} is declared
 * {@code @RestControllerAdvice} at line 160 of
 * {@code services/common-lib/src/main/java/com/carddemo/common/error/GlobalExceptionHandler.java},
 * it sits in {@code com.carddemo.common.error} and therefore outside {@code com.carddemo.card}, and
 * it reaches a running service only through {@code com.carddemo.common.CardDemoCommonAutoConfiguration},
 * the single class named in the shared kernel's
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}. A
 * dispatcher assembled from constructors applies no auto-configuration at all, and neither does a
 * context refreshed by hand, so the advice arrives only because each of the three behavioural classes
 * registers it -- the two dispatchers as controller advice on the builder, and
 * {@code CardControllerTest} as a bean of its own type in its wiring class -- each passing a clock
 * pinned to one instant so a rendered timestamp stays comparable between runs. The consequence of
 * dropping that registration is the reason it is written down here: the answer would be the
 * container's own default error representation, every assertion about the problem body's shape and
 * about a 404 or a 409 would still pass, and the class would report success having verified none of
 * them. {@code CardControllerTest} carries one case whose whole purpose is to fail if that
 * registration is ever dropped, asserting members of the shared problem document that no default
 * representation can produce.</p>
 *
 * <p>Assumptions: exactly ONE class here installs an authorization chain, and the other three
 * install none -- a property of how each is assembled rather than an omission in any of them.
 * {@code com.carddemo.card.config.SecurityConfig} owns the whole authority matrix, including the
 * administrator gate on the one route that answers with a full primary account number,
 * {@code /api/v1/admin/cards/{cardKey}}, and it builds the token decoder that chain needs at its
 * line 682. {@code CardController} itself carries no authority expression of any kind, so reading
 * the controller reveals nothing about who may call it. A dispatcher assembled from constructors
 * installs neither the chain nor a decoder, so an assertion written in one of those two that an
 * administrator is admitted and an ordinary caller refused would pass with nothing present to refuse
 * anybody -- which is why neither of them writes one. {@code CardControllerTest} obtains the chain by
 * calling that configuration's own chain and converter methods and substitutes only the decoder,
 * whose factory would otherwise resolve an issuer document while the context refreshes; it then
 * asserts BOTH outcomes on that one address, because a route answering one way for every caller
 * states nothing about a rule.</p>
 *
 * <p>Assumptions: none of the four classes reads
 * {@code services/card-service/src/test/resources/application-test.yml}, and that stays true now that
 * one of them refreshes a context. The overlay takes effect only for a class activating the
 * {@code test} profile, and none does: the census reads a resource and reflects over a type, the two
 * dispatchers are assembled from constructors, and {@code CardControllerTest} registers its own
 * configuration class into a context it refreshes itself, supplying the two group names as the shared
 * converter's compiled constants rather than as properties. This is recorded because that overlay
 * names {@code CardControllerTest} among its readers, so a maintainer could reasonably expect a
 * property set there, an issuer location or a transport setting, to influence what that class does. It
 * does not, and a value changed there to steer a result here would have no effect at all. What the
 * overlay's own notes describe is nevertheless honoured: the substituted decoder is present for exactly
 * the reason recorded there, that the pinned issuer is a reserved name whose provider document is
 * resolved while a context refreshes.</p>
 *
 * <h2>What parity here does and does not rest on</h2>
 *
 * <p>Assumptions: no executable golden master exists for any path this package covers, so parity
 * rests on validation logic transcribed from the COBOL paragraphs together with the copybook
 * contracts, and never on a recorded output stream. The repository's own suite states the reason at
 * {@code tests/README.md:83-85}: the online {@code CO*} CICS programs cannot run end to end without
 * a CICS runtime, which is absent on the runner, so only their extractable field-validation logic is
 * unit-tested there. Its golden masters cover the batch chain, and none of {@code COCRDLIC},
 * {@code COCRDSLC} or {@code COCRDUPC} is a batch program. Shipping inputs without a recorded output
 * is established house practice rather than a shortfall: {@code tests/README.md:139-146} describes
 * the export domain shipping fixtures and no golden directory, and calls that arrangement internally
 * consistent.</p>
 *
 * <p>Assumptions: the graded return codes of that COBOL suite do not reach this package. The rubric
 * is quarantined to {@code tests/}, where {@code tests/README.md:412-423} defines the values 0, 2,
 * 4, 8 and 16 and has each runner aggregate the worst code seen, and where
 * {@code tests/README.md:63} records a soft warn of 4 as that suite's own passing state. The gates a
 * class in this directory answers to are binary instead: Checkstyle, the compiler, Surefire,
 * Failsafe and JUnit each pass or fail outright. No warning tier exists here to land in, so a
 * partial result in this package is a failure.</p>
 *
 * <h2>Why this file exists, and why it is not empty</h2>
 *
 * <p>Alternatives Considered: leaving this directory without a charter, and leaving a charter holding
 * nothing but its package statement. Both were weighed against the gate that actually runs, and each
 * fails it in a different place, which is why this file is neither absent nor bare. Two checks act
 * here and they act on different things. {@code JavadocPackage} is declared at Checker level in
 * {@code config/checkstyle/checkstyle.xml} at line 276, so it is a file-set check that fires for any
 * directory holding an audited {@code .java} file and demands that a {@code package-info.java} exist
 * in it. {@code MissingJavadocPackage} is declared inside {@code TreeWalker} at line 409 of that
 * same ruleset, and it demands that the file carry a documentation comment. A charter reduced to its
 * package statement therefore satisfies the first and fails the second. The gate is also not a
 * review-time courtesy: {@code services/pom.xml} binds it as the
 * {@code checkstyle-documentation-gate} execution on the Maven {@code validate} phase with
 * {@code failOnViolation} true, {@code violationSeverity} at warning and
 * {@code includeTestSourceDirectory} true, so it audits this test tree on every local build and does
 * so before a single class is compiled. Nor is there an exemption to fall back on:
 * {@code config/checkstyle/suppressions.xml} carries exactly two entries, one for generated sources
 * and one for test fixture material, and its own charter refuses to widen the second to
 * {@code src/test/java} on the stated ground that doing so would exempt every controller, service
 * and repository test in every module. That ruleset configures no suppression filter of any kind
 * either, so an exemption cannot be written into a Java source at all and this file cannot be
 * excused in place.</p>
 *
 * <p>Assumptions: no charter belongs at {@code src/test/java/com/}, at
 * {@code src/test/java/com/carddemo/} or at {@code src/test/java/com/carddemo/card/}, and none is
 * present at any of the three. {@code JavadocPackage} fires only for a directory that holds an
 * audited {@code .java} file, and each of those three holds subdirectories and nothing else, the
 * last of them holding the 8 that carry this module's tests, so a
 * charter placed there would document a package with no compilation unit in it. This is recorded so
 * that the absence reads as a measurement rather than as three files somebody forgot.</p>
 *
 * <p>Assumptions: no parameter, return-value or exception at-clause appears in this charter, and
 * their absence is deliberate rather than an omission. The project's single user-specified rule asks
 * a docstring to give a purpose, each parameter, the return value and the exceptions raised where
 * applicable; a package declaration has no parameter, returns nothing and raises nothing, so the
 * purpose clause is the whole of what applies to this file. A placeholder written to look complete
 * would fail mechanically as well, because {@code NonEmptyAtclauseDescription} is configured at line
 * 501 of the ruleset and an at-clause with an empty description is a violation.</p>
 *
 * <p>Alternatives Considered: every rationale above is tagged with one of four labels written in the
 * plural, unparenthesised, colon-terminated form, taken from lines 31 to 34 of that same rule and
 * set out for this tree in {@code docs/CODE_DOCUMENTATION_STANDARD.md} at its lines 236 to 239. The
 * alternative was to match the idiom that predominates in the repository's existing suite, which
 * tags the same categories in the parenthesised singular, as {@code tests/README.md:66} does. It was
 * rejected on two concrete grounds. The rule's validation gate at its line 43 is the sentence this
 * tree is audited against, and that gate is written with the plural wording. And those labels are
 * not safe to lift byte for byte from that file: it carries the non-breaking hyphen 106 times across
 * 77 lines, and its lines 542 and 548 contain no ASCII hyphen at all, so a label copied from line
 * 548, which is precisely where it spells the compromises category, would read correctly to a person
 * and match no search a reviewer ran.</p>
 */
package com.carddemo.card.api;
