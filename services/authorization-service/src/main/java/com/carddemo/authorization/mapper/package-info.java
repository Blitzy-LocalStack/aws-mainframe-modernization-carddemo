/**
 * Anti-corruption boundary of the pending credit-card authorization bounded context.
 *
 * <h2>Purpose</h2>
 *
 * <p>This package is the hand-written anti-corruption layer of the pending-authorization bounded
 * context, and it is the only place in this context where the representation of a baseline record
 * may appear at all. Everything downstream of it works in plain Java types and never sees a byte
 * offset, a sign nibble, a padding field or a spelling inherited from a copybook. The parent
 * charter assigns exactly that role to this package at
 * {@code com/carddemo/authorization/package-info.java} L163 to L168, and the concerns it names are
 * each measurable in the reference tree {@code app/app-authorization-ims-db2-mq}, which is read and
 * never modified:
 *
 * <ul>
 *   <li><b>Declared widths.</b> The two segments this context stores are declared at
 *       {@code ims/DBPAUTP0.dbd} L28 as 100 bytes for the root and L36 as 200 bytes for the child.
 *       Those totals are load-bearing rather than incidental: the thirteen components of
 *       {@code cpy/CIPAUSMY.cpy} L19 to L31 sum to precisely 100, so a width read wrongly does not
 *       fail, it shifts every field after it.</li>
 *   <li><b>Packed decimal.</b> Money and keys in this context are packed, not zoned:
 *       {@code cpy/CIPAUSMY.cpy} L19 declares {@code PIC S9(11) COMP-3} for the account key and its
 *       L23 to L26 and L29 to L30 declare {@code PIC S9(09)V99 COMP-3} for four balances and two
 *       amounts, while {@code cpy/CIPAUDTY.cpy} L34 to L35 declare {@code PIC S9(10)V99 COMP-3}.
 *       Packed bytes are decoded at this edge and never reach a database column.</li>
 *   <li><b>The nines complement.</b> The child segment key is stored inverted.
 *       {@code cbl/COPAUA0C.cbl} L874 computes it as {@code 99999} minus the day-of-year form and
 *       L875 as {@code 999999999} minus the time, into the two packed key components at
 *       {@code cpy/CIPAUDTY.cpy} L20 to L21. The inversion is what makes an ascending key read
 *       return the newest authorization first, so decoding it is a semantic step and not a
 *       formatting one.</li>
 *   <li><b>Sign placement.</b> A packed field carries its sign in the low-order nibble, whereas the
 *       message payload carries an edited sign character: {@code cpy/CCPAURQY.cpy} L27 and
 *       {@code cpy/CCPAURLY.cpy} L24 both declare {@code PIC +9(10).99}. One record therefore has
 *       two sign conventions in play, and only this package is permitted to know that.</li>
 *   <li><b>An OCCURS array.</b> {@code cpy/CIPAUSMY.cpy} L22 declares
 *       {@code PA-ACCOUNT-STATUS PIC X(02) OCCURS 5 TIMES}, which becomes five discrete columns
 *       rather than a collection column, so the arity of five is asserted by the schema itself.</li>
 *   <li><b>Padding fields.</b> {@code cpy/CIPAUSMY.cpy} L31 and {@code cpy/CIPAUDTY.cpy} L54 declare
 *       {@code FILLER} of 34 and 17 bytes. Padding exists to reach the declared segment length and
 *       carries no data, so it is dropped here and the drop is recorded at the mapping site.</li>
 *   <li><b>The one spelling change this context makes.</b> The baseline spells the merchant
 *       category code {@code CATAGORY} consistently across all three of its own layers:
 *       {@code cpy/CIPAUDTY.cpy} L36, {@code cpy/CCPAURQY.cpy} L28 and {@code ddl/AUTHFRDS.ddl}
 *       L14. The target column reads {@code merchant_category_code}. The baseline spells it as
 *       described and remains reference material; the target spelling is applied in this package
 *       alone, and the lineage is registered in
 *       {@code docs/architecture/cobol-to-service-traceability.md} so it is never ambiguous.</li>
 *   <li><b>Two timestamp provenances in one record.</b> {@code cpy/CIPAUDTY.cpy} L20 to L21 hold the
 *       inverted packed key while L22 to L23 hold {@code PA-AUTH-ORIG-DATE} and
 *       {@code PA-AUTH-ORIG-TIME} as {@code PIC X(06)} each. Composing one instant from the right
 *       pair is a decision, because the two disagree by construction.</li>
 *   <li><b>Exposure narrowing.</b> The card number is {@code PIC X(16)} at
 *       {@code cpy/CIPAUDTY.cpy} L24 and {@code CHAR(16) NOT NULL} at {@code ddl/AUTHFRDS.ddl} L2.
 *       Masking it to its last four digits, suppressing a card verification value outright, and
 *       encrypting an identifier are applied here, for the reason set out under the boundary
 *       section below.</li>
 *   <li><b>Blank against null.</b> A declared-width character field that arrives blank becomes SQL
 *       {@code NULL} rather than a string of spaces. {@code ddl/AUTHFRDS.ddl} L4 to L27 are all
 *       nullable and admit that reading, while the two key columns at L2 to L3 are
 *       {@code NOT NULL} and do not, so the choice is per column and cannot be applied wholesale.</li>
 * </ul>
 *
 * <h2>The five mappers this package charters</h2>
 *
 * <ul>
 *   <li>{@code PendingAuthDetailMapper} carries the 200-byte child segment declared at
 *       {@code ims/DBPAUTP0.dbd} L36 to the {@code PendingAuthDetail} entity and on to the
 *       27-component detail response. The figure 27 is arrived at twice over, which is why it is
 *       stated: the symbolic map {@code cpy-bms/COPAU01.cpy} carries 27 field components, and
 *       {@code cpy/CIPAUDTY.cpy} leaves exactly 27 leaf data components across L20 to L53 once its
 *       L19 group header and its L54 padding field are set aside. The same mapper also carries the
 *       206-byte unload child record, whose shape is declared at {@code cbl/PAUDBUNL.CBL} L46 to
 *       L48 as a {@code PIC S9(11) COMP-3} parent key occupying six bytes ahead of a
 *       {@code PIC X(200)} segment image. Its segment and unload conversions are consumed by
 *       {@code service.LoadService} and {@code service.UnloadService}, the migrated forms of
 *       {@code cbl/PAUDBLOD.CBL} and of {@code cbl/PAUDBUNL.CBL} with {@code cbl/DBUNLDGS.CBL}; its
 *       27-component response projection is NOT consumed by an HTTP path, for the reason recorded
 *       below. This mapper defines the per-field render helpers the other
 *       three share, so a width or a sign convention is expressed once for the package.</li>
 *   <li>{@code PendingAuthSummaryMapper} carries the 100-byte root segment declared at
 *       {@code ims/DBPAUTP0.dbd} L28 to the {@code PendingAuthSummary} entity and on to the
 *       62-component summary response, that count being the field components of the symbolic map
 *       {@code cpy-bms/COPAU00.cpy}. It also carries the 100-byte unload root record declared at
 *       {@code cbl/PAUDBUNL.CBL} L44, which for this segment is the segment image itself because the
 *       parent record carries no prefix; that conversion is consumed by {@code service.LoadService}
 *       and {@code service.UnloadService}. Its 62-component response projection is NOT consumed by an
 *       HTTP path, for the reason recorded below. The five-slot status array of
 *       {@code cpy/CIPAUSMY.cpy} L22 is split here.</li>
 *   <li>{@code AuthFraudMapper} carries a {@code PendingAuthDetail} to the {@code auth_fraud} row
 *       and carries the fraud-mark request and response pair. The baseline shape is
 *       {@code ddl/AUTHFRDS.ddl}, whose 26 columns span L2 to L27 and whose key is declared at L28,
 *       with {@code ddl/XAUTHFRD.ddl} L3 ordering its index {@code CARD_NUM ASC, AUTH_TS DESC}. The
 *       two closed value domains this mapper must respect are declared as condition names rather
 *       than as constraints: {@code cpy/CIPAUDTY.cpy} L46 to L49 admit only P, D, E and M for the
 *       match status, and L51 to L52 admit only F and R for the fraud marker.</li>
 *   <li>{@code AuthorizationMessageMapper} carries the 18-field request and the 6-field reply to the
 *       entity and to the outbox row, and carries the 122-byte error-log record. The request fields
 *       are declared at {@code cpy/CCPAURQY.cpy} L19 to L36 and parsed comma-delimited into exactly
 *       eighteen receiving items at {@code cbl/COPAUA0C.cbl} L354 to L374. The reply fields are
 *       declared at {@code cpy/CCPAURLY.cpy} L19 to L24 and emitted at {@code cbl/COPAUA0C.cbl}
 *       L722 to L727. The error-log record is declared at {@code cpy/CCPAUERY.cpy} L20 to L40,
 *       whose eleven component widths sum to 122.</li>
 *   <li>{@code PendingAuthViewMapper} carries the two entities to the HTTP bodies
 *       {@code src/main/resources/openapi/authorization-api.yaml} publishes, and is the only mapper
 *       here that is a Spring bean rather than a static utility -- it holds the cursor signer, so it
 *       cannot be static. It is the live adapter for the list and detail operations:
 *       {@code service.PendingAuthSummaryService} and {@code service.PendingAuthDetailService} both
 *       route through it.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: this list read "four mappers" while the package held five, omitting the one
 * that actually serves the HTTP boundary. A closed-set count is a claim, and a reader checking which
 * mapper served a response would have found the four listed here and concluded that none did.</p>
 *
 * <h2>Why two response projections exist, and which one the contract serves</h2>
 *
 * <p>Assumptions: the flat map projections on the two SEGMENT mappers and the view types on
 * {@code PendingAuthViewMapper} are not duplicates and neither supersedes the other. The segment
 * mappers' projections are the record of what the 3270 terminal displayed, field for field against the
 * symbolic maps {@code cpy-bms/COPAU00.cpy} and {@code cpy-bms/COPAU01.cpy}; the view types are the
 * HTTP bodies. The published contract states the distinction itself at
 * {@code openapi/authorization-api.yaml} L1702 to L1706: the flat summary projection "is not a
 * candidate for this body", because it carries no cursor member and six screen-chrome positions a
 * browser client renders for itself.</p>
 *
 * <p>Trade-offs: keeping the terminal projections means two shapes exist for one aggregate, and a
 * reader can mistake the unused one for a defect. Removing them would discard the field-for-field
 * correspondence with the symbolic maps, which is the evidence that the migrated field set is complete
 * -- the 27 and 62 component counts above are derived from those maps and are checked against these
 * projections. The distinction is therefore recorded here rather than resolved by deletion, and the
 * contract's own sentence is cited so the decision is not this package's alone.</p>
 *
 * <p>Assumptions: with a string-format payload the field order and the delimiter are themselves the
 * contract, so two details of the reply are recorded here rather than left to be rediscovered. The
 * emitter at {@code cbl/COPAUA0C.cbl} L722 to L727 writes a comma after every one of the six
 * fields, the last one included, so the separator count is six and not five. And its sixth field
 * reaches the wire through the edited form declared at {@code cbl/COPAUA0C.cbl} L66,
 * {@code PIC -zzzzzzzzz9.99}, which suppresses leading zeros and places a minus sign immediately
 * ahead of the first significant digit, emitting a blank where the value is not negative, whereas
 * the copybook at {@code cpy/CCPAURLY.cpy} L24 declares {@code PIC +9(10).99}, which retains the
 * leading zeros and always emits an explicit sign character. Both
 * forms are 14 characters wide, so the payload is 57 bytes of field data and 63 bytes on the wire;
 * the widths agreeing while the renderings differ is precisely the kind of detail that a byte count
 * alone would hide. The request amount is likewise not what its copybook suggests: it arrives as
 * {@code PIC X(13)} at {@code cbl/COPAUA0C.cbl} L63 and is converted by an explicit numeric-value
 * call at L377.
 *
 * <h2>The boundary this package owns</h2>
 *
 * <p>Assumptions: two contracts authored elsewhere assign work to this package in writing, and
 * neither of them can discharge that work itself. The parent charter at
 * {@code com/carddemo/authorization/package-info.java} L160 to L162 records that the
 * {@code dto} package carries no local page or error type, and its L163 to L168 assign this package
 * the representation concerns by name; the sibling charter for
 * {@code com.carddemo.authorization.dto} adds that no type in that package may reference
 * {@code com.carddemo.authorization.domain}, on the stated ground that the anti-corruption boundary
 * lives here. And {@code com.carddemo.common.codec.FixedWidthCodec} ends its own contract at bytes
 * decoded to a decimal or a string: masking a primary account number, suppressing a card
 * verification value and encrypting an identifier are assigned to the mapper package of each
 * service, which for this bounded context is this one, and the {@code sensitive} flag on that
 * codec's layout descriptor only marks a field as needing such treatment rather than applying any.
 *
 * <p>Assumptions: the two sentences above name a directory pattern in prose rather than as a glob.
 * A glob of the shape used elsewhere in the migration plan would place an asterisk immediately
 * before a slash, and that two-character sequence closes a Java block comment wherever it appears,
 * including inside an inline code tag, because the lexer resolves it before Javadoc ever sees the
 * text. Writing the pattern out would therefore end this charter at that point and leave the
 * remainder of the file as unparseable tokens ahead of the package declaration. The check is
 * mechanical and anyone may repeat it: that sequence occurs exactly once in this file, as the
 * closing delimiter on the last line of this comment, and a second occurrence anywhere above it
 * means the file no longer parses.
 *
 * <p>The consequence is a property worth naming, because it is what makes the concentration in this
 * package effective rather than merely tidy. This package is the only one in the bounded context
 * permitted to name a {@code domain} type and a {@code dto} type in the same method signature.
 * Every crossing between stored form and transported form therefore passes through a signature that
 * lives here, which is why a representation concern surfaces at a mapper method rather than
 * anywhere else, and why a masked field cannot escape its masking by being serialised from some
 * other layer.
 *
 * <h2>This package is never suppressed</h2>
 *
 * <p>Assumptions: {@code config/checkstyle/suppressions.xml} is chartered in six words at its L36,
 * generated sources and test fixtures, and it declares only two entries, one reaching a plugin
 * output tree under {@code target} and one reaching
 * {@code src/test/resources/fixtures}. A mapper package is named on that file's never-suppress
 * list twice over, at its L45 to L46 and again at L271 to L284, and the reason it is singled out is
 * the word itself: mapper reads as generated in most codebases and here it is the opposite. That
 * file records the consequence in its own terms, that the behaviours listed under Purpose above are
 * each a decision a reader cannot recover from the code, which makes a mapper the highest-value
 * documentation target in the repository rather than a candidate for exemption. No suppression of
 * this package is permissible under any circumstance.
 *
 * <p>Assumptions: no bypass exists from inside a source file either, and that is a property of the
 * rule set rather than of anyone's restraint. {@code config/checkstyle/checkstyle.xml} configures no
 * {@code SuppressWarningsFilter}, no {@code SuppressionCommentFilter} and no
 * {@code SuppressWithNearbyCommentFilter}. All three are absent, so an annotation-based suppression
 * and a magic off-switch comment alike do nothing here. Were any of the three configured, the gate
 * could be switched off line by line with no single artifact showing that it had been, which is the
 * failure mode their absence forecloses.
 *
 * <h2>Two libraries deliberately not adopted</h2>
 *
 * <p>Alternatives Considered: a generated mapping framework, specifically MapStruct, was evaluated
 * for this package and rejected, on two independent grounds. The first is that its most recent
 * published release is a beta, as the migration plan's dependency inventory records. The second is
 * decisive on its own and is visible in the citations under Purpose above: this mapping is not
 * mechanical. It decodes packed decimal and separately decodes the nines complement of
 * {@code cbl/COPAUA0C.cbl} L874 to L875; it splits the five-slot array of
 * {@code cpy/CIPAUSMY.cpy} L22 into five columns; it applies a target spelling in place of the
 * baseline {@code CATAGORY} of {@code cpy/CIPAUDTY.cpy} L36; it composes one instant from two
 * disagreeing provenances at L20 to L23; it masks a primary account number and suppresses a card
 * verification value outright; and it distinguishes a blank that becomes SQL {@code NULL} under
 * {@code ddl/AUTHFRDS.ddl} L4 to L27 from one that cannot under its {@code NOT NULL} keys at L2 to
 * L3. Each of those needs a justification recorded at the mapping site, and a generated mapper has
 * nowhere to hold one. Adopting the framework would therefore not save the documentation work; it
 * would delete the place the documentation goes, and the gate described below would then be
 * satisfied by a file nobody wrote.
 *
 * <p>Alternatives Considered: Lombok was evaluated for the mapper and record types here and
 * rejected. Generated accessors and constructors cannot carry the Javadoc that
 * user-specified Rule 1 (Explainability) L15 requires of a member, so a class built that way either
 * fails the {@code MissingJavadocMethod} check outright or has to be exempted from it in
 * {@code config/checkstyle/suppressions.xml}, whose charter admits no such entry. Java 21
 * {@code record} types with explicit constructors give the same brevity with members that can be
 * documented, so the brevity is available without trading the gate away for it.
 *
 * <h2>Why this charter exists, and the form it takes</h2>
 *
 * <p>Assumptions: the project Explainability rule requires a docstring on every module entry point,
 * and in Java the entry point of a package is its package declaration, which only
 * {@code package-info.java} can carry -- so this file is load-bearing rather than decorative. Two
 * Checkstyle modules enforce that independently and neither is redundant: {@code JavadocPackage}
 * inspects the file set and requires this file to exist in any directory holding an audited source
 * file, while {@code MissingJavadocPackage} inspects the parsed tree and requires it to carry Javadoc.
 * A charter reduced to a bare package statement satisfies the first and fails the second, which is
 * why prose is the deliverable and the file's mere existence is not.
 *
 * <p>Assumptions: this compilation unit holds one statement, so the rationale the rule's
 * inline-comment half asks for has no adjacent executable line to sit beside and is carried inside
 * this block under the four canonical labels -- the only placement a package makes available. No
 * parameter, return or exception at-clause appears, because a package declaration accepts no
 * argument, yields no value and raises nothing, and {@code NonEmptyAtclauseDescription} would report
 * an invented tag with an empty body; omitting them is therefore the compliant reading of the rule
 * rather than a departure from it. The written convention every block here follows is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never restated.
 * <h2>Where no charter is written, and why that is deliberate</h2>
 *
 * <p>Assumptions: {@code JavadocPackage} audits only a directory that actually holds a processed
 * Java source, and {@code config/checkstyle/checkstyle.xml} narrows the audit to the java extension
 * at its L178. The directories {@code com} and {@code com/carddemo} hold subdirectories and no Java
 * source at all, so they have no package declaration, and the module-entry-point obligation of
 * user-specified Rule 1 (Explainability) L15 attaches to a package declaration. A charter at either
 * level would therefore document a package that has no entry point to document. None is required
 * and none is created, and this is recorded so that a later reader does not read the absence as an
 * oversight and add two files the gate never asked for. The set this module is to carry is nine
 * charters, one for the {@code com.carddemo.authorization} root and one for each of its eight
 * subpackages, and this file is the {@code mapper} one.
 *
 * <h2>Parity evidence available to this package, stated plainly</h2>
 *
 * <p>Assumptions: no golden master exists for any path in this module, and none may be claimed for
 * one. Three grounds establish that independently. First, {@code tests/README.md} L83 to L85 records
 * that the online CICS programs cannot run end to end without a CICS runtime, which is absent on the
 * runner, and that only their extractable field-validation logic is unit-tested. Second, the
 * message producer is not supplied by the baseline: the reference tree's own
 * {@code README.md} L63 states that the client component shown in its diagram is not included with
 * the example code, only a stub exists at {@code tests/mocks/mq_request_stub.py}, and building a
 * producer is out of scope under the migration plan at section 0.2.2. Third, and most concretely,
 * {@code cbl/COPAUA0C.cbl} cannot be compiled at all: it carries eight vendor copybook statements at
 * L149, L152, L155, L158, L161, L164, L167 and L170, naming six distinct message-queue books
 * ({@code CMQODV}, {@code CMQMDV}, {@code CMQV}, {@code CMQTML}, {@code CMQPMOV} and
 * {@code CMQGMOV}), and a case-insensitive search for {@code CMQ} across the whole repository
 * returns nothing. Parity for this package therefore rests on the copybook and data-definition
 * contracts cited throughout this charter and on logic transcribed from the reference programs, and
 * it does not rest on a recorded output comparison. Anything asserted about this package is asserted
 * against those contracts.
 *
 * <h2>Retired with no target</h2>
 *
 * <p>Assumptions: one item in the reference tree is inventoried here so that a reader looking for
 * its Java equivalent stops looking. {@code cbl/PAUDBLOD.CBL} L307 and L308 are two statements that
 * add 2 to each half of the packed authorization key, and both carry an asterisk in column 7, so
 * both are commented out and neither executes; they sit inside the branch that begins at L305 after
 * a successful root-segment read. They are dead scaffolding in the loader, they are not part of the
 * loader's behaviour, and they are not carried into this package. This is an inventory entry and it
 * is not a finding about the baseline.
 */
package com.carddemo.authorization.mapper;
