// WHY : Alternatives Considered: this charter is authored before any of the six record types it
//       governs exists beside it, and the alternative was to add it together with the first of
//       them. That alternative is rejected on a mechanical ground rather than a stylistic one.
//       Checkstyle's JavadocPackage is a Checker-level file-set check, so it audits a DIRECTORY
//       that holds a processed source file rather than a type: the first record file to land here
//       would fail the build for the absence of this file, and whoever authored that record would
//       have to compose this charter under a red build instead of reading it beforehand.
//       Authoring it now inverts that order, so the contract is in place for the authors of the
//       types it constrains. No licence header precedes it, because the charter one level up at
//       com.carddemo.authorization carries none and a header introduced here alone would make the
//       module inconsistent with itself.
/**
 * Request and response payload types for the pending credit-card authorization context.
 *
 * <h2>Target contract, and the tree state at the checkpoint that authored it</h2>
 *
 * <p>Assumptions: every type name below describes this package's <b>target contract</b> as the
 * migration plan assigns it, not the set of files present beside this one. At the checkpoint that
 * authored this charter the directory held this file alone, so each type named below was
 * <b>planned</b> rather than missing; all eleven have since landed. The counts attached to the COBOL
 * sources are different in kind: those are measurements taken from the reference tree, and each names
 * the file and the line range it was taken from so that any reader can re-take it.</p>
 *
 * <h2>Which type is authoritative for which edge</h2>
 *
 * <p>Refactoring Rationale: this section exists because the package holds two types for each of two
 * payloads and an earlier state of it did not say which was which. The rule is one sentence:
 * <b>{@code src/main/resources/openapi/authorization-api.yaml} is the contract of record for the HTTP
 * edge, and the four {@code ...View} types plus {@code FraudMarkRequest} and {@code FraudMarkResponse}
 * below are its Java realisation.</b> The two symbolic-map projections are the record of what the 3270
 * terminal displayed and received -- the specification the browser screens implement and the module's
 * own tests assert -- and they are not HTTP bodies. Assumptions: the two fraud-area records are the one
 * place where both readings coincide, because the contract was aligned to them rather than a second pair
 * declared beside them: one payload carries one name, so a client typed from the document and a handler
 * compiled from the record cannot be describing different requests. Without that sentence a reader had two plausible
 * candidates for one response and no way to choose, and no code path existed from either entity to the
 * shapes the contract publishes.</p>
 *
 * <p>Assumptions: the split is forced by three properties of the map projections rather than chosen for
 * neatness. They carry the screen's six header positions, which are chrome a browser client owns; they
 * have no cursor or availability member, so they cannot express the keyset envelope this migration
 * requires of every list; and the summary projection writes its page size of five into its own component
 * names, so it cannot return four rows or six. Alternatives Considered: deleting them and keeping one
 * type per payload. Rejected because they are the only record of three compositions the reference
 * programs perform on the way to the screen -- the card expiry around a solidus, the response reason
 * truncated to twenty characters around a separator, and the fraud mark around its report date -- one of
 * which is a registered divergence, D-AUTH-REASON-WIDTH, whose verification lives in a test of one of
 * them. Trade-offs: the package is larger than one type per payload would make it, and the compensation
 * is that the boundary each type serves is now stated on the type itself as well as here.</p>
 *
 * <p><strong>Purpose.</strong> This package holds the types that cross this context's two external
 * edges, the synchronous HTTP edge and the asynchronous message-queue edge, and it holds nothing
 * else. No type here reads or writes a datastore, carries a business rule, or decides an
 * authorization. Each one is shaped component for component from a copybook or a symbolic-map
 * layout in {@code app/app-authorization-ims-db2-mq}, which is reference material: it is read as
 * the specification and is never modified.
 *
 * <p><strong>The eleven types.</strong> Every one of them is a Java 21 {@code record}, and not one of
 * them carries a persistence annotation. The record form is what makes the components final and
 * the type a value, which is the whole of what a payload needs to be; the absence of persistence
 * annotations is the load-bearing half, because an annotated payload would be simultaneously a
 * wire contract and a table mapping, and the two change for unrelated reasons. The persistent
 * types live in {@code com.carddemo.authorization.domain} and are reached only as set out under
 * the first boundary assertion below.
 * <ul>
 *   <li>{@code AuthorizationRequestPayload} - the inbound queue message, from the 18 fields of
 *       {@code cpy/CCPAURQY.cpy} L19 to L36. The payload is declared to the queue as a string
 *       format, so field order and delimiter are not an encoding detail but the interface itself,
 *       and a component reordered here is a contract change rather than a refactor.</li>
 *   <li>{@code AuthorizationReplyPayload} - the outbound decision, from the 6 fields of
 *       {@code cpy/CCPAURLY.cpy} L19 to L24: card number, transaction identifier, authorization
 *       identifier code, response code, response reason and approved amount. The same field-order
 *       observation applies, and the six are the complete set rather than a selection from a
 *       larger record.</li>
 *   <li>{@code FraudMarkRequest} - the request half of the fraud-marking pair, carrying one of the
 *       five request-direction components of the {@code LINKAGE SECTION} of
 *       {@code cbl/COPAUS2C.cbl} L73 to L86: the action at L80, whose two values report an
 *       authorization fraudulent and remove that mark, so the component is a closed two-value
 *       domain and not free text. Assumptions: the four remaining components of that block -- the
 *       account identifier at L75, the customer identifier at L76 and the two decoded key parts --
 *       are deliberately NOT reproduced. The baseline's caller is a sibling program reached by
 *       {@code EXEC CICS LINK} inside one task, whereas this caller is a browser, so the row is
 *       named by the operation's sealed path selector and the customer is resolved server-side from
 *       the account's summary row.</li>
 *   <li>{@code FraudMarkResponse} - the reply half of the same pair, carrying the two
 *       response-direction components of the same L73 to L86 block: an outcome component drawn
 *       from a closed two-value domain at L83, and an action message whose declared width is 50
 *       characters at L86. The two halves together are the whole 272-byte area, which HTTP has no
 *       single shape for.</li>
 *   <li>{@code PendingAuthDetailResponse} - a projection of the 27 components of the
 *       {@code COPAU1AI} structure in {@code cpy-bms/COPAU01.cpy}, the detail screen's symbolic
 *       map.</li>
 *   <li>{@code PendingAuthSummaryResponse} - a projection of the 62 components of the
 *       {@code COPAU0AI} structure in {@code cpy-bms/COPAU00.cpy}, the summary screen's symbolic
 *       map. Five of its component groups repeat, one per displayed row, which is the screen's
 *       page size expressed in the layout itself.</li>
 *   <li>{@code PendingAuthSummaryView} - the account-level summary block of the list body, the Java
 *       realisation of the contract's {@code PendingAuthSummary} schema and a component-for-component
 *       projection of the 16 columns of {@code cpy/CIPAUSMY.cpy} L19 to L31.</li>
 *   <li>{@code PendingAuthRowView} - one row of the list page, the realisation of the contract's
 *       {@code PendingAuthListItem} schema. Three of its nine components are derived rather than
 *       stored: the sealed selector, the approval character the reference program computes at
 *       {@code cbl/COPAUS0C.cbl} L536 to L539, and the masked card number.</li>
 *   <li>{@code PendingAuthListView} - the whole list body, the realisation of the contract's
 *       {@code PendingAuthListResponse} schema: the summary block, a
 *       {@code com.carddemo.common.web.PageResponse} of the row type, and the navigation-boundary
 *       sentence when a paging move was already at a boundary.</li>
 *   <li>{@code PendingAuthDetailView} - the read body, the realisation of the contract's
 *       {@code PendingAuthDetail} schema. It publishes the STORED value of every column, the three
 *       compositions the detail screen performs being the browser client's.</li>
 *   <li>{@code FraudMarkRequest} - the HTTP request body of the fraud-state operation, carrying the
 *       state to END IN rather than an operation to perform, which is registered divergence
 *       D-AUTH-FRAUD-TARGET-STATE. Assumptions: the row is named in the path and NOWHERE else. A body
 *       that named it too would add a disagreement to detect rather than a capability, and would let a
 *       browser supply identity the service must take from the selector it issued.</li>
 *   <li>{@code FraudMarkResponse} - the body of a successful fraud-state write, carrying exactly the
 *       two components the response direction of the reference area declares: the success flag and the
 *       fifty-character sentence the fraud subprogram reported. Assumptions: which of the two write
 *       paths ran is carried on the STATUS CODE -- 201 for the insert, 200 for the update -- rather
 *       than as a third member, so a client never learns it by string-matching a sentence.</li>
 *   <li>{@code PendingAuthPageQuery} - the request body of the list search, carrying the account
 *       scope and, when a page other than the first is asked for, a sealed cursor and the direction
 *       it was issued for. Refactoring Rationale: it is enumerated here because it was absent from
 *       this inventory while the package held it, which is the one defect a charter can have that a
 *       reader cannot detect from the charter alone -- a type nobody documented reads as a type
 *       nobody meant to add. Assumptions: it is a request BODY rather than query parameters because
 *       the scope is an account identifier and a query string is written into the load balancer's
 *       mandatory access log before any application code runs; the reasoning is on the type itself.
 *       </li>
 * </ul>
 *
 * <p>Assumptions: the last two are called projections rather than copies, and the word is chosen
 * with care. A symbolic map interleaves data components with screen furniture -- the title band,
 * the current date and time, the program name, and the message line -- because a 3270 map carries
 * its own chrome. Here that chrome is carried once by the browser shell and by the shared problem
 * shape, so re-declaring it in each response type would put the same seven components -- the six
 * header components and the message line, a count that holds for both maps alike -- into every
 * payload this context returns, and would make the message line a per-response concern rather than
 * a single shared one. The counts above are therefore stated as the measured size of the structure
 * each type is derived from, which is a checkable number, and deliberately not as a promised
 * component count for the Java record, which would be a different and smaller one.
 *
 * <p><strong>Documentation contract.</strong> This file exists because user-specified Rule 1
 * (Explainability) L15 requires a docstring on every module entry point, and a Java package
 * declaration is a module entry point. A {@code package-info.java} is the only construct that can
 * carry Javadoc for one, so the obligation can land nowhere else. Of the four content elements
 * L18 to L21 enumerate, only Purpose applies here: a package declaration accepts no parameters,
 * yields no value and raises nothing. Those three elements are therefore inapplicable rather than
 * omitted, and no at-clause is written to stand in for one of them, because a fabricated clause
 * would describe a contract this construct does not have and L39 forbids exactly that. The block
 * form used here is what L22 requires for Java, which names Javadoc explicitly. Two Checkstyle
 * checks act on this file and neither is redundant: {@code JavadocPackage} requires the file to
 * exist in a package holding a Java source file, and {@code MissingJavadocPackage} requires it to
 * carry Javadoc, so an empty file would satisfy the first and fail the second. Both fire from the
 * {@code checkstyle-documentation-gate} execution bound to the {@code validate} phase in
 * {@code services/pom.xml}, which precedes compilation, and that binding is the migration plan's
 * chosen mechanisation of the docstring half of the gate whose consequence L43 states as a failed
 * review. No in-code suppression filter is wired into the rule set, so a finding here cannot be
 * waived from inside a source file, and the suppressions companion reaches generated sources and
 * test fixtures only, never a production source root. This build's exit status is binary: a
 * graded rubric that treats a warning-level aggregate as a passing state belongs to the COBOL
 * parity oracle alone and is never carried into a Maven or Checkstyle gate here.
 *
 * <p><strong>First boundary assertion: no type in this package imports
 * {@code com.carddemo.authorization.domain}.</strong>
 *
 * <p>Alternatives Considered: the genuinely available alternative was to let a payload expose a
 * {@code domain} entity directly as a component, or to annotate one of these types as the
 * persistent type itself, either of which removes a mapping step and the hand-written mapper that
 * performs it. It is rejected because the migration plan places the anti-corruption boundary in
 * this context's {@code mapper} package and in {@code com.carddemo.common.codec}, and names those
 * as the only places representation concerns may appear at all. Those concerns are enumerable
 * rather than vague, and this context exhibits most of them: declared byte widths; zoned-decimal
 * sign overpunch; packed-decimal encoding, which {@code cpy/CIPAUDTY.cpy} uses for both money
 * components at L34 and L35 and for the two components of its composite key at L20 and L21, the
 * second of those being held as a nines complement so that a descending read order falls out of an
 * ascending key; {@code FILLER}, which the same copybook declares at L54 purely to reach the
 * record length; primary-account-number masking to the last four digits; suppression of the card
 * verification value wherever one occurs; and the three baseline field spellings the migration's
 * persisted names change, of which exactly one falls in this context, the merchant category code
 * at {@code cpy/CIPAUDTY.cpy} L36 and {@code cpy/CCPAURQY.cpy} L28. The consequence of the
 * alternative is specific and it is why the assertion is worth stating: a payload that reached
 * into {@code domain} would carry every one of those concerns straight onto the wire, and masking
 * would become bypassable by the ordinary act of serialising an entity, which is precisely what
 * routing every response through {@code mapper} prevents. This package's declared dependency set
 * is empty for the same reason, and the emptiness is the assertion: the absence of a dependency on
 * {@code domain} is a design decision recorded here, not an omission for a later reader to
 * helpfully supply.
 *
 * <p><strong>Second boundary assertion: no type in this package re-declares a
 * {@code com.carddemo.common} contract.</strong>
 *
 * <p>Assumptions: seven shared-kernel types are imported by the code on either side of this
 * package and are never restated inside it, and each is named here with the concern it carries so
 * that a local substitute is recognisable as a duplicate rather than as a convenience.
 * {@code com.carddemo.common.money.Money} holds every monetary component, and
 * {@code com.carddemo.common.money.MoneyModule} fixes its wire form as a string, because a
 * decimal cent has no exact binary floating-point value and a payload that emitted a bare number
 * would invite a client to parse it into one, losing exactness silently at the boundary a user
 * actually reads. {@code com.carddemo.common.web.PageResponse} is the one page envelope, which is
 * what the five repeating row groups of the summary map resolve to.
 * {@code com.carddemo.common.error.ApiError} is the one problem shape, and it is what the message
 * line of both symbolic maps resolves to. {@code com.carddemo.common.validation.FieldValidationFlag}
 * carries the per-component validation outcome that the baseline expresses as its not-valid and
 * blank condition flags. {@code com.carddemo.common.codec.CsvAuthCodec} owns the 18-field and
 * 6-field wire forms named above. {@code com.carddemo.common.time.TimestampFormatter} owns the
 * single timestamp rendering.
 *
 * <p>Refactoring Rationale: that codec entry previously added "so the field order lives in one place
 * rather than once per payload", and the second half of that sentence was not true. Two of the six
 * types here restate the same field order the codec's own records declare, component for component,
 * because they are this context's structured representation of a contract whose normative form is
 * delimited text. What the codec genuinely owns is the wire ENCODING -- the delimiter, the declared
 * width table, the edited money rendering and the normalisation each component receives -- and it is
 * the normative side of the pair. The two payload records are derived from it through the single
 * crossing in {@code com.carddemo.authorization.mapper.AuthorizationMessageMapper}, which validates
 * the payload on every conversion, and a contract test beside that mapper asserts field for field
 * that the payload widths, the declared value domains and the component order match the codec's
 * published width and name tables. So the duplication is real, deliberate, singly-crossed and
 * mechanically checked, which is a different claim from there being only one of it -- and stating the
 * stronger claim is what let the payload constraints drift out of agreement unnoticed.
 *
 * <p>Assumptions: this discipline is transformation rule T2 of the migration plan, which states
 * that one former COBOL {@code COPY} statement becomes exactly one Java type import, always from
 * the single package that owns that contract, so the shared kernel is the Java analogue of
 * compiling every program against one copybook include path. That plan numbers its transformation
 * rules T1 to T10, and those identifiers are a different namespace from the user-specified rules:
 * T2 here is not Rule 1, Rule 1 is not T1, and a citation that blurs the two sends a reader to the
 * other document entirely. Two further assumptions are relied on, and both are checkable rather
 * than hoped for. The shared kernel is on this module's compile classpath, declared once in this
 * module's own {@code pom.xml} as its only intra-reactor compile dependency; the same artifact
 * appears a second time as a test-scoped test-jar, which is delivery of the layering test rather
 * than a second contract. And this module declares no Maven dependency on any sibling service
 * module, which is what makes the cross-context import prohibition a compile-time impossibility
 * here rather than a convention a reviewer has to remember; the prohibition itself is asserted by
 * the ArchUnit layering test that the shared kernel owns, and it is not restated in this tree so
 * that it keeps one owner.
 *
 * <p>Trade-offs: both assertions above are written into this Javadoc block rather than beside a
 * statement, which departs from the letter of user-specified Rule 1 (Explainability) L27 and is
 * nevertheless the only placement this file admits. L27 asks that a comment sit adjacent to the
 * code it explains; a package declaration has no statements, so there is no code for a comment to
 * be adjacent to and the requirement is satisfied vacuously rather than waived. The one decision
 * that does have something to sit beside, the order in which this charter is authored relative to
 * the types it governs, is written as a line comment immediately above the declaration instead.
 * The cost accepted is that these entries sit further from the behaviour they constrain than an
 * inline comment would, and the compensation is that each names the file and line its evidence
 * comes from, so a reader can check the claim without trusting it.
 */
package com.carddemo.authorization.dto;
