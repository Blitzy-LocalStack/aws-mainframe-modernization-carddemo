/**
 * Carries the request and response records of the CardDemo card enquiry and maintenance API.
 *
 * <h2>Target contract, and the tree state at the checkpoint that authored it</h2>
 *
 * <p>Assumptions: every inventory, type name and count in this charter describes this package's
 * <b>target contract</b> as the migration plan assigns it, and not the set of files present beside
 * this one today. The migration lands its artifacts in plan order and this charter is authored
 * first, so at the checkpoint that authored it this directory holds this charter and nothing else.
 * A record named below that has no file yet is therefore <b>planned</b> rather than missing, and a
 * count below is a target total rather than a measurement of the directory.</p>
 *
 * <p>Alternatives Considered: withholding this charter until every record it governs exists.
 * Rejected, because the charter is what the authors of those records work from -- which shape
 * belongs here, which does not, and which invariants every one of them obeys -- so writing it last
 * would leave this package with no stated contract during exactly the interval in which one is
 * needed. The cost of authoring it first is that its inventory reads as present tense unless the
 * distinction is declared, which is what the paragraph above is for.</p>
 *
 * <h2>Purpose, and where every shape comes from</h2>
 *
 * <p>Purpose: this package holds the wire contracts of the card bounded context, meaning the shapes
 * a client submits and the shapes it receives back, and it holds nothing else. Every one of them is
 * derived member for member from reference material rather than designed locally. The data comes
 * from the 150-byte {@code CARD-RECORD} layout in {@code app/cpy/CVACT02Y.cpy}, which declares six
 * data fields at lines 5 to 10 and a 59-byte {@code FILLER} at line 11. Which of those fields each
 * edge carries, and in what order, comes from the three 3270 symbolic map copybooks the online
 * programs exchanged with their screens: {@code app/cpy-bms/COCRDLI.CPY} for the list,
 * {@code app/cpy-bms/COCRDSL.CPY} for the detail view and {@code app/cpy-bms/COCRDUP.CPY} for the
 * update. Those five files, together with the behaviour of {@code app/cbl/COCRDLIC.cbl} at 1459
 * lines, {@code app/cbl/COCRDSLC.cbl} at 887, {@code app/cbl/COCRDUPC.cbl} at 1560 and the
 * sequential reader {@code app/cbl/CBACT02C.cbl} at 178, are the specification these shapes encode.
 * They are reference material: read, cited by path and line, and never modified.</p>
 *
 * <p>Assumptions: the record layout is deliberately not restated field by field in this charter,
 * for the same reason the context root charter does not restate it. A layout written out in prose
 * is a second definition of it, and the second one goes stale the first time only one of the two is
 * edited. It is single-sourced from the copybook into the entity and into these records, which is
 * the Java analogue of the COBOL convention of resolving every layout through one compiler include
 * path, recorded at {@code tests/README.md:540-542}.</p>
 *
 * <h2>The contract of record</h2>
 *
 * <p>{@code services/card-service/src/main/resources/openapi/card-api.yaml} is the contract of
 * record for every shape in this package. It declares four operations, all beneath the
 * {@code /api/v1} prefix:</p>
 *
 * <ul>
 *   <li>{@code listCards}, {@code GET} on {@code /api/v1/cards}, answering with the schema
 *       {@code CardPage}: one page of the list positioned by key, with an optional account
 *       identifier and an optional card number as filters. A full page carries seven rows, settled
 *       server-side from {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at
 *       {@code app/cbl/COCRDLIC.cbl:177-178}, and a client cannot vary it.</li>
 *   <li>{@code getCard}, {@code GET} on {@code /api/v1/cards/{cardNumber}}, answering with
 *       {@code CardDetail}, on which the primary account number appears only as its last four
 *       digits behind a masking prefix.</li>
 *   <li>{@code getAdminCardDetail}, {@code GET} on {@code /api/v1/admin/cards/{cardNumber}},
 *       answering with {@code AdminCardDetail}, the one shape in the contract able to carry that
 *       number in full, and reachable only by a caller holding the {@code carddemo-admin}
 *       authority.</li>
 *   <li>{@code updateCard}, {@code PUT} on {@code /api/v1/cards/{cardNumber}}, accepting
 *       {@code CardUpdateRequest} and answering 409 when the concurrency token submitted with it no
 *       longer matches the stored one.</li>
 * </ul>
 *
 * <p>Assumptions: where a record in this package and that contract disagree about a shape, the
 * contract decides and the record is brought to it, never the reverse. The reason is that nothing
 * in the build would report the disagreement. The two artifacts are authored separately and each
 * derives independently from {@code app/cpy/CVACT02Y.cpy}, so a divergence leaves both sides
 * internally consistent, compiles cleanly, and surfaces only as a client generated from the
 * published contract failing against a running service. Declaring one side authoritative in
 * advance is what removes the ambiguity, because there is no compile-time signal to fall back on.
 * The concrete instance already exists and is worth naming so that it is resolved the right way:
 * the subpackage map in the context root charter at
 * {@code services/card-service/src/main/java/com/carddemo/card/package-info.java:65} names three
 * record types for this package, while the contract declares the administrative detail as a schema
 * of its own, and the contract test
 * {@code services/card-service/src/test/java/com/carddemo/card/config/CardApiContractTest.java}
 * asserts that separation structurally. The contract governs.</p>
 *
 * <h2>The inventory, and the shapes that are deliberately not types here</h2>
 *
 * <p>The records this package declares at target are these, and their names are the contract's
 * schema names so that the two can be read against each other:</p>
 *
 * <ul>
 *   <li>{@code CardSummary}, one row of the list: the three values the baseline row displayed in
 *       its twenty-eight characters, being the account identifier, the masked rendering of the card
 *       number and the one-character active status, and nothing by which a client could address the
 *       row's card in a later request.</li>
 *   <li>{@code CardDetail}, the full state of one card as a caller holding only
 *       {@code carddemo-user} receives it, from the non-administrative read and from a successful
 *       update. It has no member able to hold the card number in full.</li>
 *   <li>{@code AdminCardDetail}, that same state plus the card number in full, returned by the
 *       administrative read alone.</li>
 *   <li>{@code CardUpdateRequest}, the update payload: the three editable attributes -- the
 *       embossed name, the expiry date and the active status -- together with the concurrency token
 *       last read for the card. The card being changed is named in the request path and is not
 *       repeated in the body, and neither identifier is editable.</li>
 * </ul>
 *
 * <p>Assumptions: the contract cuts the two detail shapes from one shared core, which it names
 * {@code CardDetailCore} and returns from no operation directly. Java records do not inherit
 * components, so that core is expressed here either as a record of its own that both detail shapes
 * compose or as members restated in both; either way it belongs to this package and to no other,
 * and either way the disclosure boundary stays where the contract puts it, with exactly one named
 * shape able to carry an unmasked number.</p>
 *
 * <p>The closed set is the wire shapes above and nothing else of any kind. No entity, no
 * repository, no mapper, no service, no adapter, no configuration class, no validator
 * implementation and no message catalog belongs in this package, and two shapes that a reader might
 * expect to find here belong to the shared kernel instead, for the reason recorded on the fourth
 * invariant below.</p>
 *
 * <h2>Seven invariants every record here obeys</h2>
 *
 * <p>These hold for the package rather than for one record, which is why each is stated once here
 * instead of being argued again in each file. A record that breaks one of them is wrong even if it
 * compiles and even if it round-trips.</p>
 *
 * <p><b>1. Every identifier travels as a digits-only string, never as a numeric component.</b>
 * Assumptions: the baseline settles this rather than taste, because it keeps both representations of
 * the same storage and moves between them deliberately. {@code app/cpy/CVCRD01Y.cpy} declares
 * {@code CC-ACCT-ID PIC X(11) VALUE SPACES} at lines 34 to 35 with
 * {@code CC-ACCT-ID-N REDEFINES CC-ACCT-ID PIC 9(11)} at line 36; it declares
 * {@code CC-CARD-NUM PIC X(16) VALUE SPACES} at lines 37 to 38 with {@code CC-CARD-NUM-N} over it
 * as {@code PIC 9(16)} at line 39; and lines 40 to 42 do the same for the customer identifier. The
 * decisive evidence is the name the baseline gave the group that holds the character forms:
 * {@code app/cbl/COCRDUPC.cbl:103} opens {@code 05 CICS-OUTPUT-EDIT-VARS.} and carries
 * {@code CARD-ACCT-ID-X PIC X(11)} at line 104 redefined as {@code PIC 9(11)} at lines 105 to 106,
 * and {@code CARD-CARD-NUM-X PIC X(16)} at line 110 redefined as {@code PIC 9(16)} at lines 111 to
 * 112, so the character form is the baseline's own presentation form and the numeric view is a lens
 * onto it. That program reaches for the numeric view only where a number is required, moving
 * {@code CC-ACCT-ID-N} into the record being written at line 1463; and
 * {@code app/cbl/COCRDLIC.cbl} tests the numeric views for zero at lines 1009 and 1044 while the
 * lines immediately above each test the character views for low values and for spaces. The wire
 * form here is therefore the presentation form the baseline itself used.
 *
 * <p>Trade-offs: a string component cannot be range-checked by the type system, so nothing in the
 * compiler stops a caller sending letters where digits belong. The digit set and the exact width are
 * therefore carried as declarative constraints on each component -- {@code jakarta.validation}
 * reaches this module non-optionally through {@code spring-boot-starter-validation}, declared at
 * {@code services/card-service/pom.xml:211} -- and the contract states each bound twice, as a length
 * and as a pattern such as {@code ^[0-9]{11}$}. The cost is one constraint written in two places;
 * what it buys is that a bad submission is refused with a per-field entry naming the offending
 * member, rather than with a parse failure that names none.
 *
 * <p>Assumptions: the card number carries a second, independent reason that the account identifier
 * does not share. The exact-integer ceiling of IEEE-754 binary floating point is two to the
 * fifty-third power, that is 9,007,199,254,740,992, which is itself a sixteen-digit number. The
 * sixteen-digit domain therefore extends beyond the exact-integer range, so exactness is not
 * guaranteed across it. The claim is deliberately no stronger than that: the smallest sixteen-digit
 * value, 1,000,000,000,000,000, sits below that ceiling and is held exactly, so the hazard belongs
 * to the upper part of the domain and not to all of it. A value that arrives as a string is not
 * subject to it anywhere in the domain, because a client that never parses the value as a number
 * cannot re-round it.
 *
 * <p>Assumptions: the eleven-digit account identifier is a string for different reasons, and naming
 * the wrong one would be a false claim rather than a harmless simplification. Its largest value,
 * 99,999,999,999, is far below the ceiling above and is held exactly, so exactness is not at stake
 * for it at all. Its grounds are three. First, the baseline's own character wire form, cited two
 * paragraphs above. Second, leading zeros, which an eleven-character fixed field carries as data and
 * which a numeric component drops: the contract's own example for the member is
 * {@code 00000000011}, which a numeric rendering could not reproduce. Third, one uniform digits-only
 * constraint discipline across both identifiers, so that neither is validated by a different
 * mechanism from the other and a reviewer has one rule to check rather than two.
 *
 * <p><b>2. No representation of the card verification value appears anywhere in this package.</b>
 * Not in full, not masked, not truncated, and not implied by a length. Assumptions: this preserves
 * an absence the baseline already had, rather than withdrawing something it showed. A
 * case-insensitive search for {@code CVV} returns no match in any of the six presentation files of
 * this context -- {@code app/cpy-bms/COCRDSL.CPY},
 * {@code app/cpy-bms/COCRDLI.CPY}, {@code app/cpy-bms/COCRDUP.CPY}, {@code app/bms/COCRDSL.bms},
 * {@code app/bms/COCRDLI.bms} and {@code app/bms/COCRDUP.bms} -- so no screen in the set ever
 * displayed it or accepted it. In the update program the field {@code CCUP-NEW-CVV-CD} is declared
 * at {@code app/cbl/COCRDUPC.cbl:306} and occurs exactly twice in that program's 1560 lines: at its
 * declaration, and as the source of one move at line 1464. It is never a move target, so nothing
 * ever wrote a value into it. What the target adds is encryption of the stored column, and it adds
 * nothing to the wire. A masked rendering was not the safer half-measure it can look like: a masked
 * member still publishes that the value exists, how wide it is and which member to attack, so total
 * absence is the only rendering that discloses nothing.
 *
 * <p><b>3. A page is positioned by key, and by nothing else.</b> Alternatives Considered:
 * counted-position paging, in which a request states how far into the ordered set to begin.
 * Rejected, because a boundary defined by a count moves when the set changes beneath it: one row
 * inserted between two reads pushes a row across the boundary, so a caller walking the list never
 * sees that row and sees another twice. Concurrent insertion is not hypothetical in this
 * application -- the baseline's own bill-payment path takes the next transaction identifier with an
 * unlocked read-then-increment, browsing to the high key and adding one at
 * {@code app/cbl/COBIL00C.cbl:212-217}, so two callers can be inside that sequence at once. The
 * second half of the argument is that the baseline never used a counted position as a control
 * either: its list map declares the input half of the screen counter, {@code PAGENOI PIC X(3)}, at
 * {@code app/cpy-bms/COCRDLI.CPY:60}, and {@code app/cbl/COCRDLIC.cbl} never reads it; the counter
 * {@code WS-CA-SCREEN-NUM} declared at line 237 of that program reaches the map at exactly one
 * site, line 667, where it writes the output half alone. What the program did hold between screen
 * turns was the first and last key of the displayed rows, declared at lines 230 to 235, which is a
 * key-positioned page in all but name. No component in this package counts pages, counts the rows
 * in the ordered set, or positions a page by anything other than a key.
 *
 * <p><b>4. The page envelope and the problem shape are imported from the shared kernel and are never
 * restated here.</b> {@code com.carddemo.common.web.PageResponse} declares exactly four components,
 * being the page items, a first-key cursor, a last-key cursor and a more-pages flag, and the
 * contract's {@code CardPage} schema is the serialised form of that type parameterised with
 * {@code CardSummary}. That is precisely why <b>no {@code CardPage} record exists in this
 * package</b>: the shape is already declared once, and a fifth record here would be a second
 * declaration of it. {@code com.carddemo.common.error.ApiError} owns the problem shape, including
 * the per-field error array a refused submission answers with -- whose entry is a record nested
 * inside {@code ApiError} rather than a separate top-level type, and whose blank state carries the
 * literal marker the baseline wrote into an empty field -- and
 * {@code com.carddemo.common.error.AbendDetail} owns the structured system-error surface.
 *
 * <p>Refactoring Rationale: transformation rule T2 of the migration plan requires a shared concern
 * to be consumed from {@code com.carddemo.common} alone, and the baseline is the precedent for that
 * discipline rather than an exception to it: every COBOL program resolves its record layouts through
 * one compiler include path, a convention the repository states at {@code tests/README.md:540-542},
 * where it asks that a layout never be duplicated but kept single-sourced from {@code app/cpy}. A
 * page envelope declared locally would be exactly that duplication, and because eight bounded
 * contexts page the same way, the copy that drifted would produce paging that differs between
 * screens without a single build failing to say so.
 *
 * <p><b>5. Masking, encryption and suppression happen in {@code com.carddemo.card.mapper} and
 * nowhere else.</b> A record here is an inert carrier: it holds the values it was constructed with
 * and transforms none of them. Alternatives Considered: performing the masking inside these records
 * instead, either by normalising the value in a constructor or by rendering it behind an accessor.
 * Rejected, because the decision would then be spread across every record that carries the number,
 * and auditing it would mean reading all of them and satisfying oneself that no path had been
 * missed. Concentrating it in the mapper leaves one class to inspect, and that matters here more
 * than it would elsewhere, because two detail shapes are cut from one core: the same members reach a
 * caller holding only {@code carddemo-user} and a caller holding {@code carddemo-admin}, and only
 * the second may additionally receive the number in full. Because these records transform nothing,
 * an unmasked value can reach a response only by passing through the mapper, which is what makes the
 * mapper the single auditable place where that disclosure is decided.
 *
 * <p><b>6. No record here carries money, and none carries a timestamp.</b> Assumptions: the absence
 * is recorded rather than left silent, because silence in a package derived from a financial record
 * reads as an oversight. The card layout declares exactly seven items and no more:
 * {@code CARD-NUM PIC X(16)} at line 5, {@code CARD-ACCT-ID PIC 9(11)} at 6,
 * {@code CARD-CVV-CD PIC 9(03)} at 7, {@code CARD-EMBOSSED-NAME PIC X(50)} at 8,
 * {@code CARD-EXPIRAION-DATE PIC X(10)} at 9, {@code CARD-ACTIVE-STATUS PIC X(01)} at 10 and
 * {@code FILLER PIC X(59)} at 11 -- and there is no amount and no timestamp among them. It follows
 * that {@code com.carddemo.common.money.Money} is imported by nothing in this package, and that no
 * timestamp type is either: the expiry member the contract declares is an ISO calendar date, and the
 * twenty-six-character timestamp a refusal carries belongs to {@code ApiError} in the shared kernel
 * rather than to any card shape. One target-side naming decision applies to that expiry member: it
 * is named {@code expirationDate} while the baseline field keeps its own spelling, which is why the
 * citation above reads as it does, and the pairing is recorded in
 * {@code docs/architecture/data-model-and-schema-mapping.md} so the lineage stays traceable from
 * either side.
 *
 * <p><b>7. Every shape here is a Java 21 record with explicit, fully documented components, and no
 * code generator participates.</b> Alternatives Considered: an annotation processor to generate the
 * accessors, and a mapping generator to produce the translation. Both were evaluated and both
 * rejected, for different reasons. Generated accessors cannot carry the documentation that Rule 1
 * requires of every member at its line 15, so the components whose provenance most needs stating --
 * a width taken from a copybook, a domain restricted to two values -- would be the very components
 * with nowhere to state it; a record with an explicit constructor gives the same brevity with
 * documentable components. A mapping generator is rejected because this mapping is not mechanical:
 * it drops padding, renders one value masked on every response and in full on one, suppresses the
 * verification value entirely, converts between a numeric column and a digits-only string, and
 * carries a misspelled baseline field name into a target member named differently. Each of those
 * needs its justification at the line that performs it, and a generated method body offers no such
 * line.
 *
 * <p>Trade-offs: the cost is hand-written mapping and hand-written component documentation, which is
 * more code to read and more to keep true as the contract moves. What it buys is that every decision
 * listed above is visible where it is taken and reviewable there, which is the standard Rule 1's
 * validation gate sets at its line 43: a docstring and a labelled rationale together, or the change
 * fails review.
 *
 * <h2>What this package is not</h2>
 *
 * <p>This list is as much of the charter as the contents are, because an absence in a package is
 * invisible and an author who cannot see why something is missing supplies it.</p>
 *
 * <p><b>Not a persistence model.</b> The entity is {@code com.carddemo.card.domain.Card}, mapped to
 * {@code card.cards}, and it differs from these records on purpose, not by accident. The Flyway
 * migration at {@code services/card-service/src/main/resources/db/migration/V1__card.sql} declares
 * the account identifier as a {@code BIGINT} column and the verification value as an encrypted
 * {@code BYTEA} column, where these records carry the account identifier as eleven digit characters
 * and carry no verification value at all. Converting between those two representations is the
 * mapper's work and belongs to no other layer. This is the single most likely place for an author
 * working downstream to go wrong, because both shapes describe the same card and neither is a
 * subset of the other: passing an entity where a response shape is expected would publish a value
 * this package has no member for.</p>
 *
 * <p><b>Not a place for validation logic.</b> Declarative Bean Validation constraints on components
 * are in scope and expected -- a width, a digit pattern, a two-value domain, a lower bound on the
 * concurrency token -- because they are part of the shape a caller is held to. Imperative checking
 * is not: a cross-field rule, a lookup, a comparison against stored state or anything that has to
 * read another record belongs in {@code com.carddemo.card.service}, where the transcribed baseline
 * rules live and can be tested without a web layer.</p>
 *
 * <p><b>Not the owner of user-visible message text.</b> Message strings and their catalog belong
 * with the error model in {@code com.carddemo.common.error} and with the user interface. No record
 * here declares a message component, and none truncates or clamps one.</p>
 *
 * <h2>Layering</h2>
 *
 * <p>These records may import freely from {@code com.carddemo.common}, and the reverse is forbidden:
 * the shared kernel never references a service package, or the eight contexts that depend on it
 * would depend on each other through it. No record here may import another service's
 * {@code domain} package. Assumptions: neither rule rests on review. The ArchUnit rules in
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * own layering for the whole of {@code services}, and they are run against each module's own
 * compiled classes, so a cross-context import and a binary-numeric member in a money path each fail
 * a test rather than earning a review comment. That single ownership is also why the documentation
 * ruleset configures no import-control module of its own: two engines enforcing overlapping halves
 * of one boundary would leave no way to tell which of them owned a given rule.</p>
 *
 * <p>Trade-offs: no golden-master oracle exists for any path these shapes serve. The three online
 * programs behind them cannot be run end to end without a CICS runtime, as the repository records at
 * {@code tests/README.md:83-85}, so the recorded outputs that hold the batch contexts to a byte
 * comparison have no counterpart here. Parity for these edges rests instead on the transcribed rules
 * and on the tests that assert them, including the contract test that asserts the disclosure
 * boundary structurally rather than in prose. That is a weaker guarantee than a byte comparison, and
 * it is accepted because standing up a CICS region is outside this migration and would not be
 * reproducible in the build pipeline.</p>
 *
 * <h2>Authoring notes for this file</h2>
 *
 * <p>Alternatives Considered: not writing this file at all and letting the package go undocumented.
 * That option does not exist. Rule 1 requires a docstring on every module entry point at its line
 * 15, a package declaration is that entry point in Java, and {@code package-info.java} is the only
 * compilation unit able to carry package-level Javadoc. The documentation gate then enforces the
 * requirement in two independent halves, and neither is redundant: {@code JavadocPackage}, a
 * file-set check declared at {@code config/checkstyle/checkstyle.xml:245}, asserts that this file
 * <b>exists</b> in any directory holding an audited source file, while
 * {@code MissingJavadocPackage}, a tree check at line 378 of the same ruleset, asserts that the file
 * <b>carries Javadoc</b>. An empty {@code package-info.java} satisfies the first and fails the
 * second, and so does one whose block opens with an ordinary comment marker rather than the Javadoc
 * marker. Neither half can be waived from inside this source: the ruleset configures the file-based
 * suppression filter alone, that filter is set to fail closed if its companion file is absent, and
 * the companion carries no entry for anything under a module's main source tree.</p>
 *
 * <p>Trade-offs: this block carries no parameter, return or exception at-clause, and no authorship,
 * version or release marker either. Rule 1 enumerates four docstring elements at its lines 18 to 21,
 * and only the first of them, purpose, has any meaning for a package: a package declares no
 * parameters, returns nothing and raises nothing, so the other three are recorded here as
 * inapplicable rather than answered with an invented tag. An empty tag would restate nothing, which
 * the rule forbids at its line 38. The three authorship-style markers are absent because the
 * ruleset omits the entire family of checks that would ask for them, and because the version
 * control history answers those three questions more reliably than a comment maintained by hand.</p>
 *
 * <p>Assumptions: this file is one Javadoc block and one package declaration, with no type, no
 * annotation, no import and no line comment. A package declaration needs no import, and any shape
 * that would require one belongs in one of the records instead. It is also pure ASCII with no
 * byte-order mark, and that is load-bearing rather than cosmetic: the four rationale labels used
 * throughout this charter are retyped from Rule 1's own lines 31 to 34 rather than copied from
 * {@code tests/README.md}, which names the same four categories across its lines 547 and 548 and
 * writes the fourth of them with a non-breaking hyphen there, while its line 504 writes that same
 * category in the singular. That character is indistinguishable from an ordinary hyphen on screen
 * and behaves differently under a search, and a rationale a search cannot find is a rationale a
 * review cannot count. The build declares UTF-8 for
 * the source encoding and for the documentation gate's charset, so ASCII is a strict subset of what
 * is configured and nothing is lost mechanically.</p>
 *
 * <p>Assumptions: this is one of the eight charters this bounded context's main source tree carries
 * at target -- one at the context root, and one in each of the seven subpackages {@code api},
 * {@code service}, {@code repository}, {@code domain}, {@code dto}, {@code mapper} and
 * {@code config}. None belongs in the {@code com} or {@code com/carddemo} directories above them,
 * because the file-set check audits only a directory that holds a processed source file and those
 * two hold nothing but further directories.</p>
 */
package com.carddemo.card.dto;
