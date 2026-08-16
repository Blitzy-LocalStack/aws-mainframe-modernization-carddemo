/**
 * API request and response types of the CardDemo reporting and statement bounded context.
 *
 * <p><strong>Purpose.</strong> Every type in this package is declared as a Java 21 {@code record}
 * carrying an explicit constructor, and its component order and declared widths are read from the
 * BMS symbolic maps and the copybooks rather than chosen. That is how the 3270 field-length
 * contract survives the move: a length the terminal enforced by refusing a keystroke becomes a
 * bean-validation constraint on the matching component, so the same value is refused at the same
 * boundary for the same reason a user would recognise. Nothing else belongs here -- no business
 * rule, no data access, no paging envelope of this package's own and no problem shape of its own.
 *
 * <p>The anti-corruption boundary runs between this package and
 * {@code com.carddemo.reporting.domain}. A type here never imports a view projection, and
 * {@code com.carddemo.reporting.mapper} is the one place the two meet. Confining the crossing to a
 * single package is what makes the boundary real rather than nominal: a padding, truncation,
 * masking or edit-mask decision then has exactly one site at which it may legally appear, and a
 * reviewer has exactly one place to audit for it.
 *
 * <p><strong>Source contracts.</strong> Every width, position and line number stated in this file
 * and in the types beside it was read from the reference artifacts below. The upper-case spellings
 * are load-bearing, because a lower-case citation of an upper-case member resolves to nothing.
 * {@code docs/architecture/data-model-and-schema-mapping.md} is the authority for the mapping as a
 * whole and is cited rather than restated.
 * <ul>
 *   <li>{@code app/bms/CORPT00.bms} governs the report-request roster: the map {@code CORPT0A} is
 *       declared at L26 and sized {@code SIZE=(24,80)} at L28. Of 42 {@code DFHMDF} definitions
 *       only 17 are named, which is why 17 fields reach the symbolic map and the remaining 25 are
 *       constant labels with no component here at all.</li>
 *   <li>{@code app/cpy-bms/CORPT00.CPY} governs the declared width of every request field.
 *       {@code 01 CORPT0AI.} at L17 is the input side and {@code 01 CORPT0AO REDEFINES CORPT0AI.}
 *       at L121 the output side over the same bytes; the narrowest field is
 *       {@code CONFIRMI PIC X(1)} at L114 and the widest {@code ERRMSGI PIC X(78)} at L120.</li>
 *   <li>{@code app/cbl/CORPT00C.cbl} governs the date composites and every user-visible message. A
 *       requested range is assembled from parts rather than typed as one string:
 *       {@code WS-START-DATE} at L60 and {@code WS-END-DATE} at L66 each hold four digits, then
 *       two, then two, separated by literal hyphen fillers, producing exactly the 10 characters
 *       {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} names at L72. A request type here
 *       accepts the parts, and the assembled value is what validation sees.</li>
 *   <li>{@code app/cpy/CVTRA07Y.cpy} is normative for the 133-column report a response describes.
 *       The detail figure carries a leading-minus edit mask at L30 while the page, account and
 *       grand totals carry a leading-plus mask at L54, L60 and L66, each 15 characters wide; the
 *       rule band at L48 is {@code PIC X(133) VALUE ALL '-'}. 133 is a declared line width and
 *       never a sum of component widths, which is why no type here computes it.</li>
 *   <li>{@code app/cpy/CVTRA05Y.cpy} governs the 350-byte transaction as the ledger holds it.
 *       {@code TRAN-CARD-NUM} at L15 begins at byte 262 counting from zero and {@code TRAN-PROC-TS}
 *       at L17 at byte 304 -- the two columns a report or statement query orders by.</li>
 *   <li>{@code app/cpy/COSTM01.CPY} governs the same 350 bytes re-keyed for statement production.
 *       {@code TRNX-KEY} at L21 to L23 is 32 bytes with the card number leading and
 *       {@code TRNX-REST} at L24 to L36 is 318. Card number leading is why a statement response
 *       groups by card where a report response does not.</li>
 *   <li>{@code app/cpy/CVCRD01Y.cpy} governs the message widths and the digits-only identifier
 *       discipline: two 75-character carriers at L28 and L29, the low-values sentinel at L30
 *       belonging to the second of them alone, and three character-over-numeric overlay pairs at
 *       L34 and L36, at L37 and L39, and at L40 and L42.</li>
 *   <li>{@code app/cpy/CSMSG02Y.cpy} governs the structured abend members:
 *       {@code ABEND-CODE PIC X(4)} at L22, {@code ABEND-CULPRIT PIC X(8)} at L24,
 *       {@code ABEND-REASON PIC X(50)} at L26 and {@code ABEND-MSG PIC X(72)} at L28. The file ends
 *       at L35, so the group is L21 to L29 and any citation past L35 is a dead reference.</li>
 * </ul>
 *
 * <p><strong>Ownership.</strong> This context owns no table, no index and no database migration
 * artifact, and the shape of these types follows from that. It does have a schema:
 * {@code reporting} is dedicated to it and is owned in the database by the {@code NOLOGIN} role
 * {@code carddemo_reporting_owner} rather than by the login role the service authenticates as.
 *
 * <p>Refactoring Rationale: that schema holds TEN views, exactly one table
 * {@code card_grouping_key} and one function {@code resolve_card}, and an earlier revision of the
 * sentence above said it held "views rather than tables", which is false.
 * ⚠️ Refactoring Rationale: the view figure here read "seven". Eight are created by
 * {@code data-migration/sql/V1__reporting_views.sql} -- one per projection in
 * {@code ..reporting.domain} -- and two aggregate-only verification relations are added by
 * {@code V3__verification_surfaces.sql} and granted to the same login, so the login reads ten and
 * the types below project eight of them. The one table carries the secret that keeps the per-card
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
 * <p><strong>Shared contracts consumed, never re-declared.</strong> Assumptions: the paging
 * envelope is imported from {@code com.carddemo.common.web.PageResponse}. Its shape is
 * {@code PageResponse<T>(List<T> items, String firstKey, String lastKey, boolean hasNext)}, where
 * the two boundary keys and the availability flag are what the baseline kept in its communication
 * area between screen turns. That flag is settled by the read that produced the page, from one
 * surplus row requested beyond the window, and the envelope publishes no backward availability
 * answer at all -- the baseline answers the backward question from the page ordinal it also kept in
 * that area, refusing the step on the opening page without reading anything, and that ordinal's
 * migrated home is the browser client's navigation state rather than a fifth component here.
 * Declaring a local equivalent would put the same envelope in every service, which transformation
 * rule T2 exists to prevent: two copies of a browse cursor disagreeing about whether a further page
 * exists is a defect no test in either service would localise.
 *
 * <p>Assumptions: the two ordered columns are what the cursor MEANS and not what it CARRIES, and
 * the distinction is a security one. One of the two is a primary account number, and where the
 * baseline echoed its cursor to a 3270 terminal inside a closed CICS session this context answers a
 * browser across a public edge -- so serialising the column pair into a response body would hand a
 * client a card number and invite it back on the next request, where an edge access log could
 * capture it. Both components are therefore tokens sealed by
 * {@code com.carddemo.common.web.CursorToken}, which carries the ordered key pair inside a payload
 * authenticated against the query and the requesting subject; {@code PageResponse} refuses a
 * component that is not sealed, so the raw pair is not representable here at all. Every field-shape
 * statement in this package still describes the key pair, because that is what a token opens to and
 * what the repository predicate is built from.
 *
 * <p>Assumptions: the problem shape is imported from {@code com.carddemo.common.error.ApiError},
 * its per-field entries from {@code com.carddemo.common.validation.FieldValidationFlag} and the
 * structured abend detail from {@code com.carddemo.common.error.AbendDetail}; none of the three is
 * declared again here. {@code AbendDetail} carries the four members {@code app/cpy/CSMSG02Y.cpy}
 * declares at L21 to L29, widths 4, 8, 50 and 72. This rests on the same T2 ground as the envelope,
 * and the specific harm avoided is different: a problem shape is what a client parses on every
 * failure path, so a second one differing by a member name or a width would force every consumer to
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
 * by type, and it is not declared by this module at all. The shared kernel registers
 * {@code com.carddemo.common.money.MoneyModule} twice, by two mechanisms that are independent on
 * purpose. Inside a Spring context it arrives through the one auto-configuration class the kernel
 * names in its {@code META-INF/spring} registration resource,
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, which declares the module as a bean;
 * outside one it arrives through the kernel's
 * {@code META-INF/services/tools.jackson.databind.JacksonModule} provider file, which is what makes a
 * plain mapper built without a context render an amount as a quoted string too. Either way, a
 * money-bearing component in this package serialises as a string with no annotation of its own and no
 * registration written in this module.
 *
 * <p>Refactoring Rationale: this passage said {@code ReportingApplication} "imports
 * {@code MoneyModule} explicitly, because component scanning rooted at {@code com.carddemo.reporting}
 * reaches nothing beneath {@code com.carddemo.common}". Neither half held. That class declares no
 * import of the module and no bean for it -- it records, against its own component-scan rationale, the
 * deliberate decision to leave all three of the kernel's Spring contributions to the kernel's own
 * auto-configuration -- and the scanning premise is not the reason: a scan widened to the kernel root
 * would still not register this module, because the module carries no stereotype for a scan to match,
 * which is exactly the ground on which that alternative was rejected there. Documenting a registration
 * in the wrong file is worse than documenting none, in a specific way this passage demonstrates: a
 * maintainer removing what looks like a redundant import from the entry point would have found nothing
 * to remove and might have ADDED one, re-registering a module the auto-configuration already supplies.
 * The mechanism is now stated where a reader can verify it in one jump -- a registration resource, an
 * auto-configuration class and a service-provider file -- and the second, framework-neutral path is
 * named as well, because it is the one that keeps the encoding true for the batch-shaped task runner
 * this module also publishes, which builds no web context at all.
 *
 * <p>Assumptions: an identifier crosses this boundary as a string of digits and not as an integer,
 * on the strength of the baseline's own treatment of it. {@code app/cpy/CVCRD01Y.cpy} declares each
 * of the three identifiers twice over the same bytes, once as characters and once as a number:
 * {@code CC-ACCT-ID PIC X(11)} at L34 with {@code CC-ACCT-ID-N PIC 9(11)} redefining it at L36,
 * {@code CC-CARD-NUM PIC X(16)} at L37 with its numeric redefinition at L39, and
 * {@code CC-CUST-ID PIC X(09)} at L40 with its numeric redefinition at L42. The character
 * declaration is the one the screen and the message carry; the numeric one exists so that
 * arithmetic can reach the same bytes. An integer component would discard a significant leading
 * zero, and a 16-digit card number does not fit a 32-bit integer at all.
 *
 * <p>Assumptions: two message widths coexist and neither is normalised onto the other. The
 * communication-area carriers are 75 characters, {@code CCARD-ERROR-MSG PIC X(75)} at
 * {@code app/cpy/CVCRD01Y.cpy} L28 and {@code CCARD-RETURN-MSG PIC X(75)} at L29, while the screen
 * field is 78, {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/CORPT00.CPY} L120, left-justified
 * and blank-padded. The {@code 88}-level sentinel at L30 is subordinate to {@code CCARD-RETURN-MSG}
 * alone, so an absent return message arrives as low values while an absent error message arrives as
 * blanks: the first maps to null or to omission and the second to a blank string, and collapsing
 * them loses a distinction the baseline makes.
 *
 * <p>Assumptions: a declared width is read from the symbolic map, and the map's overlay groups
 * contribute nothing to a byte position. Each screen field occupies a five-part group in
 * {@code app/cpy-bms/CORPT00.CPY}: a length halfword {@code COMP PIC S9(4)} of two bytes, an
 * attribute byte {@code PICTURE X}, an overlay group introduced by {@code FILLER REDEFINES} that
 * holds one {@code PICTURE X} alias and adds zero bytes because it addresses the attribute byte a
 * second time rather than a new one, four filler bytes, and then the value field. Seventeen such
 * overlay groups appear, from L21 at a stride of exactly six. A naive walk that adds every group it
 * meets reports each position one byte further along per group, 17 bytes too far by the end of the
 * map, which is why the overlays are excluded from the calculation rather than trusted to cancel
 * out.
 *
 * <p>Assumptions: no type here carries a first-entry-against-repeat-entry discriminator, where the
 * baseline carries one. {@code app/cbl/CORPT00C.cbl} reaches the shared session structure through
 * {@code COPY COCOM01Y.} at L138, and that structure supplies {@code CDEMO-PGM-CONTEXT PIC 9(01)}
 * at {@code app/cpy/COCOM01Y.cpy} L29 with its two condition names at L30 and L31, which tell a
 * program whether the screen it is answering is the one it just sent. The field is only meaningful
 * because a CICS task ends at every screen turn: its value is remembered state, and the templated
 * highlight copybook at {@code app/cpy/CSSETATY.cpy} L17 to L27 gates its red-attribute move and
 * its literal {@code '*'} marker on that remembered value, so a field marker appears only on a turn
 * the program recognises as a repeat. A handler that returns field errors in the response body has
 * nothing to remember and nothing to gate, so presentation is driven entirely by what the response
 * says. The discriminator is eliminated rather than ported, and no component here stands in for it.
 *
 * <p>Trade-offs: a declared width is asserted on the API type as well as being bounded by the view
 * column behind it, so one width lives in two artifacts and a change to it has two edit sites. The
 * duplication is accepted because a rejection has to name a field: the message field is 78
 * characters at {@code app/cpy-bms/CORPT00.CPY} L120 and the templated highlight copybook marks the
 * offending field individually, so an over-long value must be refused at the edge with that field
 * identified, which neither a truncation nor a driver error further in can supply. The compensating
 * discipline is that the width asserted on a component is always the copybook's declared width and
 * never a rounded one, so the two sites can be compared mechanically instead of by eye.
 *
 * <p><strong>Never brought into existence in this package.</strong> Each of the following simply
 * never appears, for the reason attached:
 * <ul>
 *   <li>No paging envelope of its own, because {@code com.carddemo.common.web.PageResponse} is the
 *       single envelope for the reactor.</li>
 *   <li>No problem shape, no per-field error carrier and no thrown type of its own, because
 *       {@code ApiError}, {@code FieldValidationFlag} and {@code AbendDetail} are consumed from the
 *       shared kernel.</li>
 *   <li>No message-catalogue type and no properties companion, because reproducing a user-visible
 *       string verbatim is the presentation layer's charge and not this package's.</li>
 *   <li>No business rule, no repository call and no view-projection import, because
 *       {@code com.carddemo.reporting.mapper} is the only bridge to
 *       {@code com.carddemo.reporting.domain}.</li>
 *   <li>No ordinal paging component, parameter or accessor, and no prose here implying one exists.
 *       A cursor keyed on the columns a query already orders by is used instead, because an ordinal
 *       cursor loses and repeats rows once a concurrent insert shifts positions between two reads,
 *       and that is a change in observable behaviour a key-based browse does not produce. Two
 *       baseline browse fields are absent for the same reason and must not reappear:
 *       {@code WS-CA-SCREEN-NUM}, a counter, and {@code WS-CA-LAST-PAGE-DISPLAYED}, whose polarity
 *       inverts the reading a name suggests because shown is 0 and not-shown is 9.</li>
 *   <li>The carve-out to the item above, stated so that it is not mistaken for a lapse: the words
 *       page, report page, page total and grand total are domain vocabulary here and name nothing
 *       of that kind. {@code app/cpy/CVTRA07Y.cpy} L50 to L54 declares a band headed with the
 *       literal {@code 'Page Total'} and gives it its own edit-masked figure at L54, and
 *       {@code app/cbl/CBTRN03C.cbl} holds the report's page depth at 20 lines through
 *       {@code WS-PAGE-SIZE PIC 9(03) COMP-3} at L131 to L132, tested at L282. A report page is a
 *       property of the output layout that has to be reproduced byte for byte; an API page is a
 *       property of a query cursor. The two are never conflated.</li>
 *   <li>No repeat-entry marker of any kind, so no resubmission flag, no first-entry flag and no
 *       turn counter, for the reason recorded above.</li>
 *   <li>No Lombok, because its generated accessors cannot carry the Javadoc Rule 1 requires; a Java
 *       21 {@code record} with an explicit constructor gives the same brevity with members that can
 *       be documented.</li>
 *   <li>No MapStruct, because the mapping is not mechanical -- it drops {@code FILLER}, masks a
 *       primary account number to its last four digits and applies the COBOL edit masks -- and each
 *       of those needs a justification at the mapping site, which a generated mapper has nowhere to
 *       hold.</li>
 *   <li>No {@code module-info.java}, because this build is classpath-based, and no
 *       {@code package.html}, because {@code package-info.java} is the canonical carrier of package
 *       documentation.</li>
 * </ul>
 *
 * <p><strong>Baseline framing.</strong> Everything under {@code app/} is reference material and
 * remains byte-identical; no statement in this file describes an edit to it. Two framings are used
 * and no third: the baseline does one thing, the Java does another, and the difference is
 * registered in {@code docs/architecture/cobol-to-service-traceability.md}, which owns that
 * register; or the Java encodes a stated rule. The two divergences arising from the export and
 * import key declarations are batch-service's to register and are not claimed here. Three baseline
 * field names carry a misspelling that the bounded contexts owning them resolve in their own column
 * names -- {@code ACCT-EXPIRAION-DATE}, {@code CARD-EXPIRAION-DATE} and
 * {@code PA-MERCHANT-CATAGORY-CODE} -- and none of the three reaches this context: no baseline
 * field is renamed here, and every component is named from the field it carries at the width that
 * field declares.
 */
package com.carddemo.reporting.dto;
