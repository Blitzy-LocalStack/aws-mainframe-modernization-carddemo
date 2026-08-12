/**
 * API request and response types of the CardDemo reporting and statement bounded context.
 *
 * <p>Every type in this package is declared as a Java 21 {@code record} carrying an explicit
 * constructor, and its component order and declared widths are read from the BMS symbolic
 * maps and the copybooks rather than chosen. That is how the 3270 field-length contract
 * survives the move: a length the terminal used to enforce by refusing a keystroke becomes a
 * bean-validation constraint on the matching component, so the same value is refused at the
 * same boundary for the same reason a user would recognise. Nothing else belongs here. There
 * is no business rule, no data access, no paging envelope of this package's own and no
 * problem shape of its own, each for a reason set out under Decisions below.
 *
 * <p>The anti-corruption boundary runs between this package and
 * {@code com.carddemo.reporting.domain}. A type here never imports a view projection, and
 * {@code com.carddemo.reporting.mapper} is the one place the two meet. Confining the crossing
 * to a single package is what makes the boundary real rather than nominal: a padding,
 * truncation, masking or edit-mask decision then has exactly one site at which it may legally
 * appear, and a reviewer has exactly one place to audit for it.
 *
 * <p><strong>Documentation contract.</strong> This file exists because user-specified Rule 1
 * (Explainability) requires a docstring on every module entry point at L15, and a Java
 * package declaration is one; a {@code package-info.java} is the only construct able to carry
 * Javadoc for it, which is why the obligation lands here and nowhere else. Of the four content
 * elements the rule enumerates at L18 to L21, only Purpose applies: a package declaration
 * accepts no parameters, yields no value and raises nothing, so the Parameters, Return values
 * and Exceptions elements are inapplicable here rather than omitted, and no at-clause is
 * written to stand in for one of them. The block form used throughout is what L22 asks for in
 * Java. The written convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md}, and the house
 * precedent it extends is section 12 of {@code tests/README.md}, whose heading sits at L516
 * and whose mandatory-explainability note runs from L544 to L549; both are cited and neither
 * is authored here. The reactor-wide build interlock that makes this file's absence and its
 * emptiness two separate failures is recorded once at {@code com.carddemo.reporting} and is
 * deliberately not restated, so that it keeps one owner.
 *
 * <p><strong>Reference sources.</strong> Every width, position and line number stated in this
 * file was read from the artifacts below. Each is reference material, and the upper-case
 * spellings are load-bearing because a lower-case citation of an upper-case member resolves to
 * nothing:
 * <ul>
 *   <li>{@code app/bms/CORPT00.bms} governs the report-request roster. The map
 *       {@code CORPT0A} is declared by {@code DFHMDI} at L26 and sized {@code SIZE=(24,80)} at
 *       L28, inside the mapset declared at L19. Of 42 {@code DFHMDF} definitions only 17 are
 *       named, which is precisely why 17 fields reach the symbolic map and the remaining 25
 *       are constant labels with no component in this package at all. {@code MONTHLY} at L80
 *       is the only definition carrying {@code IC}, so it is the sole initial-cursor field.</li>
 *   <li>{@code app/cpy-bms/CORPT00.CPY} governs the declared width of every request field
 *       across its 224 lines. {@code 01 CORPT0AI.} at L17 is the input side and
 *       {@code 01 CORPT0AO REDEFINES CORPT0AI.} at L121 the output side over the same bytes.
 *       The narrowest field is the single-character {@code CONFIRMI PIC X(1)} at L114 and the
 *       widest is {@code ERRMSGI PIC X(78)} at L120.</li>
 *   <li>{@code app/cbl/CORPT00C.cbl}, 649 lines, governs the date composites and every
 *       user-visible message. A requested range is assembled from parts rather than typed as
 *       one string: {@code WS-START-DATE} at L60 and {@code WS-END-DATE} at L66 each hold four
 *       digits, then two, then two, separated by literal hyphen fillers at L62 and L64 and at
 *       L68 and L70, producing exactly the 10 characters that
 *       {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} names at L72. The validation call
 *       it feeds is described by
 *       {@code CSUTLDTC-PARM} at L129 to L136, whose reply is a severity code, a message
 *       number and a 61-character message. A request type here therefore accepts the parts,
 *       and the assembled value is what validation sees.</li>
 *   <li>{@code app/cpy/CVTRA07Y.cpy}, 73 lines, is normative for the 133-column report a
 *       response describes. The detail figure carries a leading-minus edit mask at L30 while
 *       the page, account and grand totals carry a leading-plus mask at L54, L60 and L66, each
 *       mask 15 characters wide; the rule band at L48 is {@code PIC X(133) VALUE ALL '-'} and
 *       is the only declaration in the file natively that wide. 133 is consequently a declared
 *       line width and never a sum of component widths, which is why no type here computes
 *       it.</li>
 *   <li>{@code app/cpy/CVTRA05Y.cpy} governs the 350-byte transaction as the ledger holds it.
 *       {@code TRAN-CARD-NUM} at L15 begins at byte 262 counting from zero and
 *       {@code TRAN-PROC-TS} at L17 at byte 304, both re-derived by summing every picture
 *       ahead of them rather than taken on trust, because those two positions are the columns
 *       a report or statement query orders by.</li>
 *   <li>{@code app/cpy/COSTM01.CPY} governs the same 350 bytes re-keyed for statement
 *       production, and is the single upper-case member of {@code app/cpy}.
 *       {@code 01 TRNX-RECORD.} sits at L20; {@code TRNX-KEY} at L21 to L23 is 32 bytes with
 *       the card number leading, and {@code TRNX-REST} at L24 to L36 is 318, summing to 350.
 *       Card number leading is why a statement response groups by card where a report response
 *       does not.</li>
 *   <li>{@code app/cpy/CVCRD01Y.cpy} governs the message widths and the digits-only identifier
 *       discipline: two 75-character carriers at L28 and L29, the low-values sentinel at L30
 *       belonging to the second of them alone, and three character-over-numeric overlay pairs
 *       at L34 and L36, at L37 and L39, and at L40 and L42.</li>
 *   <li>{@code app/cpy/CSMSG02Y.cpy}, 35 lines, governs the structured abend members:
 *       {@code 01 ABEND-DATA.} at L21 with {@code ABEND-CODE PIC X(4)} at L22,
 *       {@code ABEND-CULPRIT PIC X(8)} at L24, {@code ABEND-REASON PIC X(50)} at L26 and
 *       {@code ABEND-MSG PIC X(72)} at L28. Because the file ends at L35, the group is L21 to
 *       L29 and any citation past L35 is a dead reference rather than a detail to look up.</li>
 * </ul>
 *
 * <p><strong>Ownership.</strong> This context owns no table, no index and no database
 * migration artifact, and the shape of these types follows from that. It does have a schema:
 * {@code reporting} is dedicated to it and is owned in the database by the {@code NOLOGIN} role
 * {@code carddemo_reporting_owner} rather than by the login role the service authenticates as.
 *
 * <p>Refactoring Rationale: that schema holds seven views AND exactly one table,
 * {@code card_grouping_key}, and an earlier revision of the sentence above said it held "views
 * rather than tables", which is false. The one table carries the secret that keeps the per-card
 * statement grouping token non-invertible, and the same script that creates it revokes it from
 * this context's login -- so the module owning no table, the schema holding one, and this
 * context being unable to read it are three separate true statements rather than one. The
 * ownership authority is {@code docs/architecture/data-model-and-schema-mapping.md} and it is
 * cited rather than restated. The correction changes nothing about the types below: they still
 * describe read-only projections over views, because the one table is not among what this
 * context may read. These types therefore describe read-only projections over
 * cross-schema views that other contexts populate -- views created by a reporting-views
 * migration under {@code data-migration/sql} and reached under a database role holding
 * {@code SELECT} on them alone; the ledger indexes those queries rely on belong
 * to transaction-service. So no type here carries a version marker for optimistic
 * concurrency, a creation or deletion payload, or an identifier a caller is expected to supply
 * for a new row, because there is nothing in this context to write.
 *
 * <p><strong>Decisions.</strong> What follows discharges the second half of user-specified
 * Rule 1's validation gate at L43, which requires every non-obvious decision to carry a
 * rationale drawn from one of the four categories the rule names at L31 to L34 and fails code
 * missing either half. Each entry sits with the claim it explains rather than in a footer,
 * which is how L27's adjacency requirement is honoured in a file that has no statements for a
 * comment to sit beside. Each also names the line, the declared width or the byte count it
 * rests on, because L41 rejects a rationale that offers no specific justification. The four
 * labels were retyped from the rule document, which is 7-bit ASCII throughout, rather than
 * copied from {@code tests/README.md}: measured with the C locale forced, that file carries 106
 * non-breaking hyphens across 77 lines, and its L548 spells the fourth label with a
 * non-breaking hyphen, a trailing bracket and no colon, so there is no safe token there to
 * copy.
 *
 * <p>Refactoring Rationale: the paging envelope is imported from
 * {@code com.carddemo.common.web.PageResponse} and is never declared again here. Its shape is
 * {@code PageResponse<T>(List<T> items, String firstKey, String lastKey, boolean hasNext)} -
 * four components and one type parameter - where the two boundary keys and the one
 * availability flag are what the baseline kept in its communication area between screen turns.
 * Assumptions: that flag is settled by the read that produced the page, from one surplus row
 * requested beyond the window, and the envelope publishes no backward availability answer at all -
 * the baseline answers the backward question from the page ordinal it also kept in that area,
 * refusing the step on the opening page without reading anything, and that ordinal's migrated home
 * is the browser client's navigation state rather than a fifth component here. In this
 * context the position those keys mark is the two columns a report or statement query orders
 * by, the card number at {@code app/cpy/CVTRA05Y.cpy} L15 and byte 262 and the processing
 * timestamp at L17 and byte 304, so the envelope is not a generic convenience here but exactly
 * the cursor those two positions imply.
 *
 * <p>Refactoring Rationale: those two columns are what the cursor MEANS and no longer what it
 * CARRIES, and the distinction is a security one rather than a nicety. One of the two is a
 * primary account number, and the baseline echoed its cursor to a 3270 terminal inside a closed
 * CICS session whereas this context answers a browser across a public edge - so serialising the
 * column pair into a response body would hand a client a card number and invite it back on the
 * next request, where an edge access log could capture it. Both components are therefore tokens
 * sealed by {@code com.carddemo.common.web.CursorToken}, which carries the ordered key pair
 * inside a payload authenticated against the query and the requesting subject;
 * {@code PageResponse} refuses a component that is not sealed, so the raw pair is not
 * representable here at all. Every field-shape statement in this package still describes the key
 * pair, because that is what a token opens to and what the repository predicate is built from.
 *
 * <p>What was wrong with declaring a local equivalent is concrete rather than
 * stylistic: the same envelope would then exist once per service, and transformation rule T2
 * exists to prevent exactly that, requiring one former {@code COPY} statement to become one
 * type import from the one package that owns the contract. Two copies of a browse cursor
 * disagreeing about whether a further page exists is a defect no test in either service would
 * localise.
 *
 * <p>Refactoring Rationale: the problem shape is imported from
 * {@code com.carddemo.common.error.ApiError}, its per-field entries from
 * {@code com.carddemo.common.validation.FieldValidationFlag} and the structured abend detail
 * from {@code com.carddemo.common.error.AbendDetail}; none of the three is declared again here.
 * {@code AbendDetail} carries the four members {@code app/cpy/CSMSG02Y.cpy} declares at L21 to
 * L29, widths 4, 8, 50 and 72. This rests on the same T2 ground as the envelope above, and the
 * specific harm avoided is different: a problem shape is what a client parses on every failure
 * path, so a second one differing by a member name or a width would force every consumer to
 * branch on which service answered before it could read an error at all.
 *
 * <p>Alternatives Considered: a monetary component crosses this boundary as a JSON string, and
 * the alternative evaluated and rejected was a JSON number. Most clients parse a JSON number
 * into an IEEE-754 binary floating point value, so exactness that survives every earlier hop
 * would be surrendered at the one hop a user actually reads. The margin does not permit it:
 * the amount pictures these types carry are {@code PIC S9(09)V99} at
 * {@code app/cpy/CVTRA05Y.cpy} L10 and at {@code app/cpy/COSTM01.CPY} L29, 11 significant
 * digits each, and the balance a statement also reports is
 * {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy} L7, 12 significant
 * digits, against the roughly 15 to 17 that binary64 offers. The encoding is not applied type
 * by type: {@code com.carddemo.reporting.ReportingApplication} imports
 * {@code com.carddemo.common.money.MoneyModule} explicitly, because component scanning rooted
 * at {@code com.carddemo.reporting} reaches nothing beneath {@code com.carddemo.common}, and
 * that one registration is the mechanism by which a money-bearing component in this package
 * serialises as a string without any annotation of its own.
 *
 * <p>Assumptions: an identifier crosses this boundary as a string of digits and not as an
 * integer, on the strength of the baseline's own treatment of it.
 * {@code app/cpy/CVCRD01Y.cpy} declares each of the three identifiers twice over the same
 * bytes, once as characters and once as a number: {@code CC-ACCT-ID PIC X(11)} at L34 with
 * {@code CC-ACCT-ID-N PIC 9(11)} redefining it at L36, {@code CC-CARD-NUM PIC X(16)} at L37
 * with its numeric redefinition at L39, and {@code CC-CUST-ID PIC X(09)} at L40 with its
 * numeric redefinition at L42. The character declaration is the one the screen and the message
 * carry; the numeric one exists so that arithmetic can reach the same bytes. An integer
 * component would discard a significant leading zero, and a 16-digit card number does not fit
 * a 32-bit integer at all, so a digit string is the representation that preserves what the
 * baseline actually transmits.
 *
 * <p>Assumptions: two message widths coexist and neither is normalised onto the other. The
 * communication-area carriers are 75 characters, {@code CCARD-ERROR-MSG PIC X(75)} at
 * {@code app/cpy/CVCRD01Y.cpy} L28 and {@code CCARD-RETURN-MSG PIC X(75)} at L29, while the
 * screen field is 78, {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/CORPT00.CPY} L120,
 * left-justified and blank-padded. The {@code 88}-level sentinel at L30 is subordinate to
 * {@code CCARD-RETURN-MSG} alone, so an absent return message arrives as low values while an
 * absent error message arrives as blanks: the first maps to null or to omission and the second
 * to a blank string, and collapsing them into one value loses a distinction the baseline
 * makes. Both widths are correct as declared, so neither is altered and neither is widened to
 * meet the other.
 *
 * <p>Assumptions: a declared width is read from the symbolic map, and the map's overlay groups
 * contribute nothing to a byte position. Each screen field occupies a five-part group in
 * {@code app/cpy-bms/CORPT00.CPY}: a length halfword {@code COMP PIC S9(4)} of two bytes, an
 * attribute byte {@code PICTURE X}, an overlay group introduced by {@code FILLER REDEFINES}
 * that holds one {@code PICTURE X} alias and adds zero bytes because it addresses the attribute
 * byte a second time rather than a new one, four filler bytes, and then the value field.
 * Seventeen such overlay groups appear, at L21, L27, L33, L39, L45, L51, L57, L63, L69, L75,
 * L81, L87, L93, L99, L105, L111 and L117, a stride of exactly six. A naive walk down the
 * declarations that adds every group it meets therefore reports each position one byte further
 * along per group, 17 bytes too far by the end of the map, which is why the overlays are
 * excluded from the calculation rather than trusted to cancel out.
 *
 * <p>Assumptions: the four labels in this section are written in the plural, bracket-free,
 * colon-terminated spelling that user-specified Rule 1 uses at L31 to L34, and that spelling
 * is the only accepted one. It is mandatory in every language and every file of the migration
 * trees -- the sibling shell, YAML and HCL artifacts included -- so it must never be
 * normalised to anything else. A singular, bracketed, heading-style or dash-terminated variant
 * is not an alternative spelling: it is a label that a fixed-string search for the category
 * will not find, which makes a documented rationale read as absent to the audit that looks for
 * it. {@code docs/CODE_DOCUMENTATION_STANDARD.md} carries the full statement of the convention
 * and enumerates the rejected shapes.
 *
 * <p>Refactoring Rationale: no type here carries a first-entry-against-repeat-entry
 * discriminator, where the baseline carries one. {@code app/cbl/CORPT00C.cbl} reaches the
 * shared session structure through {@code COPY COCOM01Y.} at L138, and that structure supplies
 * {@code CDEMO-PGM-CONTEXT PIC 9(01)} at {@code app/cpy/COCOM01Y.cpy} L29 with its two
 * condition names at L30 and L31, which tell a program whether the screen it is answering is
 * the one it just sent. What was wrong with carrying that forward is that the field is only
 * meaningful because a CICS task ends at every screen turn: its value is remembered state, and
 * the templated highlight copybook at {@code app/cpy/CSSETATY.cpy} L17 to L27 gates its
 * red-attribute move and its literal {@code '*'} marker on that remembered value, so a field
 * marker appears only on a turn the program recognises as a repeat. A handler that returns
 * field errors in the response body has nothing to remember and nothing to gate, so
 * presentation is driven entirely by what the response says. The discriminator is eliminated
 * rather than ported, and no component in this package stands in for it.
 *
 * <p>Trade-offs: a declared width is asserted on the API type as well as being bounded by the
 * view column behind it, so one width lives in two artifacts and a change to it has two edit
 * sites. The duplication is accepted because a rejection has to name a field: the message field
 * is 78 characters at {@code app/cpy-bms/CORPT00.CPY} L120 and the templated highlight
 * copybook marks the offending field individually, so an over-long value must be refused at the
 * edge with that field identified, which neither a truncation nor a driver error further in can
 * supply. The compensating discipline is that the width asserted on a component is always the
 * copybook's declared width and never a rounded one, so the two sites can be compared
 * mechanically instead of by eye.
 *
 * <p><strong>Never brought into existence in this package.</strong> Nothing is being taken
 * away; the following simply never appear, each for the reason attached:
 * <ul>
 *   <li>No paging envelope of its own, because
 *       {@code com.carddemo.common.web.PageResponse} is the single envelope for the
 *       reactor.</li>
 *   <li>No problem shape, no per-field error carrier and no thrown type of its own, because
 *       {@code ApiError}, {@code FieldValidationFlag} and {@code AbendDetail} are consumed from
 *       the shared kernel.</li>
 *   <li>No message-catalogue type and no properties companion, because reproducing a
 *       user-visible string verbatim is the presentation layer's charge and not this
 *       package's.</li>
 *   <li>No business rule, no repository call and no view-projection import, because
 *       {@code com.carddemo.reporting.mapper} is the only bridge to
 *       {@code com.carddemo.reporting.domain}.</li>
 *   <li>No ordinal paging component, parameter or accessor, and no prose here implying one
 *       exists. A cursor keyed on the columns a query already orders by is used instead,
 *       because an ordinal cursor loses and repeats rows once a concurrent insert shifts
 *       positions between two reads, and that is a change in observable behaviour that a
 *       key-based browse does not produce. Two baseline browse fields are absent for the same
 *       reason and must not reappear: {@code WS-CA-SCREEN-NUM}, a counter, and
 *       {@code WS-CA-LAST-PAGE-DISPLAYED}, whose polarity inverts the reading a name suggests
 *       because shown is 0 and not-shown is 9.</li>
 *   <li>The carve-out to the item above, stated so that it is not mistaken for a lapse: the
 *       words page, report page, page total and grand total are domain vocabulary here and
 *       name nothing of that kind. {@code app/cpy/CVTRA07Y.cpy} L50 to L54 declares a band
 *       headed with the literal {@code 'Page Total'} and gives it its own edit-masked figure at
 *       L54, and {@code app/cbl/CBTRN03C.cbl} holds the report's page depth at 20 lines through
 *       {@code WS-PAGE-SIZE PIC 9(03) COMP-3} at L131 to L132, tested at L282. A report page is
 *       a property of the output layout that has to be reproduced byte for byte; an API page is
 *       a property of a query cursor. The two are different mechanisms serving different
 *       consumers and are never conflated.</li>
 *   <li>No repeat-entry marker of any kind, so no resubmission flag, no first-entry flag and no
 *       turn counter, for the reason recorded under Decisions above.</li>
 *   <li>No Lombok, because its generated accessors cannot carry the Javadoc Rule 1 requires and
 *       the ruleset's allowed-annotation list is left at its default rather than widened to
 *       excuse a generated member; a Java 21 {@code record} with an explicit constructor gives
 *       the same brevity with members that can be documented.</li>
 *   <li>No MapStruct, because the mapping is not mechanical - it drops {@code FILLER}, masks a
 *       primary account number to its last four digits and applies the COBOL edit masks - and
 *       each of those needs a justification at the mapping site, which a generated mapper has
 *       nowhere to hold.</li>
 *   <li>No {@code module-info.java}, because this build is classpath-based; no
 *       {@code package.html}, because {@code package-info.java} is the canonical carrier of
 *       package documentation and the ruleset audits that file; no ignore file for either git
 *       or the image build; and no module-local copy of the ruleset or of its suppressions
 *       companion.</li>
 *   <li>No author, revision or version at-clause. The ruleset requires none, the house
 *       convention names four docstring elements and no such tag is among them, and version
 *       control already answers what those tags would assert.</li>
 * </ul>
 *
 * <p><strong>Baseline framing.</strong> Everything under {@code app/} is reference material and
 * remains byte-identical; no statement in this file describes an edit to it. Two framings are
 * used and no third: the baseline does one thing, the Java does another, and the difference is
 * registered; or the Java encodes a stated rule. Where a difference exists it is entered in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which owns that register, so this
 * package cites an entry and never defines one. In particular the two divergences arising from
 * the export and import key declarations are batch-service's to register and are not claimed
 * here. Three baseline field names carry a misspelling that the bounded contexts owning them
 * resolve in their own column names - {@code ACCT-EXPIRAION-DATE},
 * {@code CARD-EXPIRAION-DATE} and {@code PA-MERCHANT-CATAGORY-CODE} - and none of the three
 * reaches this context: no baseline field is renamed in this package, and every component is
 * named from the field it carries at the width that field declares.
 */
package com.carddemo.reporting.dto;
