//=============================================================================
// WHAT: the package charter for com.carddemo.reference.mapper, the hand-written
//       anti-corruption layer of the reference-data bounded context. It records
//       the rulings every mapper in this directory applies, so that a conversion
//       question is settled here once rather than answered again, and possibly
//       differently, in each mapper that meets it.
// WHY : Assumptions: every column name, SQL type and width quoted below was read
//       out of services/reference-service/src/main/resources/db/migration/
//       V1__reference.sql, which the charter of com.carddemo.reference.domain
//       already names as the authority for the physical shape of this schema.
//       Where this file and that migration could ever disagree, the migration is
//       right and this file is the defect.
// WHY : Assumptions: every path beginning app/ is reference material. It is read
//       as the specification for what a mapper must carry, cited by path and
//       physical line, and never modified.
// WHY : Alternatives Considered: the roster and the class shapes recorded below
//       are stated as a measurement of this directory, not as a plan for it. The
//       alternative, describing an intended set, is what two earlier revisions of
//       this file did, and each was wrong in a different direction. A charter
//       consulted to learn which mapper owns a conversion sends a reader to write
//       one that already exists whenever it under-reports, so the roster is
//       re-measured whenever a file is added here.
//=============================================================================
/**
 * The hand-written anti-corruption layer of the reference-data bounded context, and the only place
 * in this service where a copybook representation concern may appear.
 *
 * <h2>Purpose</h2>
 *
 * <p>Every type in this package converts between {@code com.carddemo.reference.domain} and
 * {@code com.carddemo.reference.dto}. Its consumers are {@code com.carddemo.reference.service} and,
 * on the outbound direction only, {@code com.carddemo.reference.api}. Nothing here reads a
 * datastore, decides a business rule, performs money arithmetic or holds state between requests.</p>
 *
 * <p>The layer is deliberately two-sided: it depends on the domain package and on the DTO package at
 * once, and neither of those two depends on it. Assumptions: that shape is what lets both sides be
 * clean. Trailing blanks are padding to a declared width rather than data; a rate is a scaled decimal
 * in storage and a string on the wire; a category code carries leading zeros that a numeric type
 * would discard. Each of those is a property of the source record rather than of the target domain,
 * so each is resolved here. A one-sided mapper -- one that lived inside either neighbour -- would
 * push exactly these concerns back into the package it was meant to keep free of them.</p>
 *
 * <p>Every class here is written by hand and no code generator is introduced anywhere in this
 * package. That is worth stating rather than assuming, because in most codebases the word mapper
 * implies generated output, and a reader who assumes generation will go looking for a generator that
 * does not exist. {@code config/checkstyle/suppressions.xml} makes the same point from the opposite
 * direction at L169 to L180, where a mapper package is named as the one exemption that must never be
 * added to that file, on the ground that a hand-written anti-corruption layer is the highest-value
 * documentation target in the module.</p>
 *
 * <h2>How this charter is written, and the rule that requires it</h2>
 *
 * <p>Assumptions: user-specified Rule 1 (Explainability) L15 requires a docstring on every module
 * entry point. In Java the module entry point is the package declaration, and a
 * {@code package-info.java} charter is the only place its Javadoc can live, which is what makes this
 * file rule-mandated rather than a convention this package happens to follow.
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} L326 names the same artefact in the same terms, and
 * {@code config/checkstyle/checkstyle.xml} binds it twice over: {@code JavadocPackage} at L245 asserts
 * that the file exists and {@code MissingJavadocPackage} at L378 asserts that it carries Javadoc, so
 * an empty file would satisfy the first and fail the second.</p>
 *
 * <p>Assumptions: of the four docstring elements the rule enumerates, only Purpose at L18 has content
 * for a package. Parameters at L19, return values at L20 and exceptions at L21 are vacuous here,
 * because a package takes no argument, yields no value and raises nothing. This charter therefore
 * carries no at-clause of any kind. Trade-offs: emitting an empty or invented at-clause to look
 * complete would be worse than emitting none -- {@code NonEmptyAtclauseDescription} at L470 fails an
 * empty one outright, and an invented one would misdescribe the package to the next reader. L22 is
 * satisfied by using the language's standard form, a Javadoc block rather than a run of line
 * comments, and L27 by keeping each rationale beside the ruling it explains instead of collecting all
 * of them in one place.</p>
 *
 * <p>Assumptions: the rule's Validation Gate at L43 is conjunctive. It requires a docstring carrying
 * purpose, parameters and return values, and it separately requires a labelled rationale on every
 * non-obvious decision; a deficiency in either half fails, so satisfying one of them is not partial
 * credit toward the other. Two details of that sentence are worth stating precisely, because both are
 * easy to misquote. The gate is the mandatory statement of the obligation, whereas L29 merely
 * permits the four categories, so the gate is the correct citation for the requirement. And the
 * gate's own triad names purpose, parameters and return values and does not name exceptions -- the
 * obligation to document a thrown exception rests on L21, on the house convention, and on
 * {@code JavadocMethod} at L451, which is configured with {@code validateThrows} true. This charter
 * has no method and so no exception to declare, but the mappers beside it do, and a charter that
 * misattributed the source of that obligation would mislead every one of them.</p>
 *
 * <p>Assumptions: L40 is why this file is long. It makes an undocumented non-obvious choice a defect
 * wherever a reasonable alternative existed, and this package is almost entirely composed of such
 * choices; each one below therefore carries a named category. L41 sets the standard those categories
 * are held to: a rationale is anchored to a concrete path and physical line, a declared width, a
 * {@code PICTURE} clause or a named constraint. An unanchored explanation is a vague one however
 * confidently it reads, so nothing below rests on an assertion that a reader cannot go and check.</p>
 *
 * <p>Refactoring Rationale: the four rationale labels are written in the plural, bare and
 * unparenthesised, with the colon retained and no emphasis markup:
 * {@code Alternatives Considered:}, {@code Refactoring Rationale:}, {@code Assumptions:} and
 * {@code Trade-offs:}. {@code docs/CODE_DOCUMENTATION_STANDARD.md} L203 to L251 fixes that form
 * character-for-character and explains why one spelling matters: the label is found by literal search
 * across seven languages before it is read by a person, and a second spelling of one category makes
 * that search silently partial. A reader who has met a singular spelling of the assumption or
 * trade-off label in this repository met it in the reference-only test suite, whose header bullets tag
 * those two categories without the trailing letter s -- {@code .github/workflows/tests.yml} L19 to L43
 * is the example the standard itself cites, in its own paragraph at L236 to L245 and specifically at
 * L239. That suite is not retyped, so the two trees genuinely read differently; the plural is used
 * here because it is the form Rule 1 uses at its own L31 to L34 and the form its gate at L43 is
 * audited against. The singular spellings are not reproduced anywhere in this file, not even as an
 * illustration, so that a reviewer auditing the tree by literal search finds only labels that are
 * actually in use. The two forms are never mixed inside one file.</p>
 *
 * <h2>The six mappers, and why four of them are static</h2>
 *
 * <p>Assumptions: six mappers are landed as compilation units beside this charter, and the closed
 * set of this package is those six plus this file. Nothing is outstanding, so a reader who cannot
 * open one of the six has found a gap rather than the expected state. The count is measured against
 * the directory on every build:</p>
 *
 * <pre>
 * this directory: 7 java files = 6 classes + 1 charter
 * </pre>
 *
 * <p>Refactoring Rationale: this section said five, and enumerated five, while
 * {@code UsPhoneAreaCodeMapper} stood beside it. Two things made that worse than an ordinary stale
 * count. The first is that three sibling classes cite this charter by line number, so a reader
 * arriving from one of them is being told the set is closed by the document those citations point at.
 * The second is specific to the omitted class: it renders the same entity as {@code LookupMapper}'s
 * area-code member, so a reader who took "the closed set is those five" literally would conclude a
 * duplicate conversion had been added in the wrong place -- and would be half right, which is the
 * hardest kind of wrong document to act on. What is actually true is recorded in its entry below,
 * including that it has no caller today. The marker line above is measured by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/PackageCharterInventoryTest.java},
 * so a seventh mapper arriving without an entry here now fails the build.</p>
 *
 * <dl>
 *   <dt>{@code TransactionTypeMapper}</dt>
 *   <dd>Converts {@code TransactionType} to {@code TransactionTypeResponse} and builds a new entity
 *       from {@code TransactionTypeCreateRequest}. It also owns the two normalisation members that
 *       two of its siblings reuse, which the trim boundary below sets out.</dd>
 *
 *   <dt>{@code TransactionCategoryMapper}</dt>
 *   <dd>Converts {@code TransactionCategory} to {@code TransactionCategoryResponse} and builds a new
 *       entity from {@code TransactionCategoryCreateRequest}, composing the nested identity type
 *       rather than setting key members individually.</dd>
 *
 *   <dt>{@code DisclosureGroupMapper}</dt>
 *   <dd>Renders a resolved rate as {@code DisclosureGroupRateResponse}, reporting both the group the
 *       caller asked for and the group that supplied the rate so that a fallback is visible in the
 *       reply. Outbound only.</dd>
 *
 *   <dt>{@code LookupMapper}</dt>
 *   <dd>Renders the three seeded lookup entities -- {@code UsPhoneAreaCode}, {@code UsState} and
 *       {@code UsStateZipPrefix} -- as their response shapes. Outbound only.</dd>
 *
 *   <dt>{@code DateInquiryReplyMapper}</dt>
 *   <dd>Renders the date-conversion reply line of the message-driven inquiry flow, transcribed from
 *       {@code app/app-vsam-mq/cbl/CODATE01.cbl}, and frames it to the declared message length. It
 *       converts no entity, because this reply has none.</dd>
 *
 *   <dt>{@code UsPhoneAreaCodeMapper}</dt>
 *   <dd>Renders one seeded area-code row, with the sub-list it belongs to, as
 *       {@code PhoneAreaCodeResponse}, and renders a list of them. Assumptions: this is the SAME
 *       conversion {@code LookupMapper}'s area-code member performs, and the duplication is stated
 *       rather than glossed, because a reader finding two implementations of one conversion needs to
 *       know which is reached. {@code AddressLookupController} calls {@code LookupMapper} on all three
 *       lookup routes, so this class has <b>no caller in the delivered code</b>. It carries the
 *       classification filtering and the list member that a paged area-code route would need, which is
 *       the reason it exists as a bean rather than as a fifth static member on
 *       {@code LookupMapper}. Trade-offs: an unreached conversion is dead weight and is recorded as
 *       such here rather than defended; removing it or routing the controller through it are both
 *       single-caller changes, and either is preferable to leaving a reader to guess which member the
 *       area-code route uses.</dd>
 * </dl>
 *
 * <p>Alternatives Considered: the four entity conversions are {@code final} classes with a private
 * constructor and static members, while {@code DateInquiryReplyMapper} alone is a Spring
 * {@code @Component} with instance members. A uniform set of injected instance components was the
 * alternative, and the split is deliberate rather than residue. Each of the four is a total function
 * of its argument: it has no collaborator, no configuration and no state, so a static call site
 * states that honestly and leaves no constructor through which a dependency could later be
 * introduced without anyone noticing the class had stopped being a pure conversion. The other two are
 * not that shape, for two different reasons. {@code DateInquiryReplyMapper} delegates framing to
 * {@code com.carddemo.common.codec.InquiryRequestCodec} and is
 * consumed by the message listener in {@code com.carddemo.reference.service}, where being a bean is
 * what allows a listener test to supply a substitute without the listener changing.
 * {@code UsPhoneAreaCodeMapper} is a bean because it was authored for constructor injection into a
 * route that would page area codes, and its own header records that choice; it is the one member of
 * this package whose shape is justified by a consumer that does not exist yet, which is why its entry
 * above says so plainly instead of letting the static/bean split look uniform.</p>
 *
 * <p>Trade-offs: the four static classes are declared {@code final} with private constructors, so
 * nothing can subclass or proxy them, and a later cross-cutting concern that needed to wrap a
 * conversion would first have to convert them to instance members. A static member also cannot be
 * replaced in a slice test the way an injected bean can. Both costs are accepted on the same ground:
 * a conversion with no collaborator has no behaviour worth substituting, so a test asserts its output
 * directly and gains nothing from indirection. {@code DateInquiryReplyMapper} is correspondingly not
 * declared {@code final}, precisely so that it remains proxyable.</p>
 *
 * <p>Alternatives Considered: discrete mappers rather than one consolidated
 * {@code ReferenceMapper}. The evidence differs record by record -- three separate copybooks, one Db2
 * declaration pair and one message-flow program -- so a single class would need one Javadoc block
 * speaking for unrelated record layouts, which is precisely the adjacency that L27 requires be kept.
 * There is also no shared text-normalisation class, and that absence is a decision rather than an
 * omission: only two of the records converted here carry a description at all, so a third class
 * holding one helper for
 * two callers would add an indirection whose only content is a two-line loop. The helper pair
 * therefore lives on {@code TransactionTypeMapper}, the mapper of the record whose description the
 * baseline manipulates most, and {@code TransactionCategoryMapper} calls it by qualified name.
 * Trade-offs: that makes the category mapper depend on a sibling rather than on a neutral utility,
 * which is the cost accepted for keeping one implementation of the rule. Every other mapper calls
 * it not at all: {@code LookupMapper}, {@code UsPhoneAreaCodeMapper} and
 * {@code DisclosureGroupMapper} convert records that carry no
 * description, {@code DateInquiryReplyMapper} converts no record, so the trim boundary below never
 * reaches any of them and every value they publish is
 * verbatim.</p>
 *
 * <h2>Why the conversion is written rather than generated</h2>
 *
 * <p>Alternatives Considered: MapStruct was evaluated for the whole migration and rejected, and this
 * package is where the reason is most visible. Two independent grounds carry the decision. The first
 * is availability: its most recent published release is a beta, which is not a dependency this
 * migration takes on a layer that every response passes through. The second is the substantive one --
 * copybook-to-DTO mapping is not mechanical. Within this module alone the layer drops {@code FILLER}
 * at three different widths, normalises blank-padded description text on one side of a record and
 * leaves the key side untouched on the other, preserves a zero-padded character key against a
 * {@code PICTURE} clause that declares it numeric, and carries a stored fixed-point rate into the
 * shared money type so that it leaves the service as a JSON string. Each of those is a judgement with
 * a reasonable alternative, so each needs its justification at the mapping site under L40, and a
 * generated mapper has nowhere to hold one: the annotation that would drive it can express the
 * mapping but not the reason for it.</p>
 *
 * <p>Assumptions: the same rejection reasoning is repository-wide, and the sibling behaviour it cites
 * does not apply here, which is worth stating so that no one imports a concern this module does not
 * have. In other bounded contexts that layer additionally masks a primary account number to its last
 * four digits, suppresses a card verification value entirely, encrypts a national identifier and a
 * government-issued identifier, and renames three misspelled baseline fields. None of the masking,
 * suppression or encryption applies in this module, because reference data carries no personally
 * identifying information at all -- the six tables hold type codes, category codes, group codes,
 * descriptions, a rate and three lists of geographic codes. None of the three renames belongs here
 * either: they are owned by account-service, card-service and authorization-service respectively.
 * They are named rather than omitted so that a reader can see the repository-wide reasoning was
 * evaluated against this module and found to hold here for different reasons.</p>
 *
 * <p>Alternatives Considered: Lombok was rejected on its own separate ground, which is not the
 * MapStruct ground and is kept distinct from it. A generated accessor cannot carry the Javadoc that
 * Rule 1 L15 requires of it, and {@code MissingJavadocMethod} at L363 is configured with
 * {@code scope} set to private and {@code allowedAnnotations} cleared, so a member that exists
 * without a docstring fails the build rather than deferring to review. Java 21 record types with
 * explicit constructors give the same brevity while leaving every member documentable, which is what
 * the DTO package uses.</p>
 *
 * <h2>Padding fields that are not carried across</h2>
 *
 * <p>Assumptions: each of the three records this package converts ends in a {@code FILLER} item whose
 * only function is to pad the record to its declared length. None is carried into a column, and the
 * drops are registered here per record because the copybook rule requires each one to be recorded
 * rather than left to inference. The arithmetic is given with each entry so that the claim is
 * checkable rather than asserted: when the declared widths sum to the declared record length, no field
 * has been overlooked and the dropped item is demonstrably padding.</p>
 *
 * <ul>
 *   <li>{@code app/cpy/CVTRA02Y.cpy} L10, {@code 05 FILLER PIC X(28)}, in the disclosure-group record
 *       whose L2 comment declares a length of 50. The widths sum 10 + 2 + 4 + 6 + 28 = 50, taking the
 *       L9 {@code DIS-INT-RATE PIC S9(04)V99} as the six display positions it occupies.</li>
 *   <li>{@code app/cpy/CVTRA03Y.cpy} L7, {@code 05 FILLER PIC X(08)}, in the transaction-type record
 *       whose L2 comment declares a length of 60. The widths sum 2 + 50 + 8 = 60.</li>
 *   <li>{@code app/cpy/CVTRA04Y.cpy} L9, {@code 05 FILLER PIC X(04)}, in the transaction-category
 *       record whose L2 comment declares a length of 60. The widths sum 2 + 4 + 50 + 4 = 60.</li>
 * </ul>
 *
 * <p>Alternatives Considered: carrying the padding into a column was the alternative, and it is
 * rejected because the target columns are individually typed and separately addressable. A fixed
 * record needs padding so that the next record begins at a predictable offset; a row does not, so the
 * padding would arrive as a column holding blanks that no consumer could act on and that every
 * consumer would have to ignore.</p>
 *
 * <h2>The trim boundary, which follows the column type</h2>
 *
 * <p>This is the most reused ruling in the package, so it is settled once here and cited rather than
 * restated. It turns on the target column type, and the column type follows how the baseline uses the
 * field.</p>
 *
 * <p>Assumptions: a {@code PIC X(n)} used as a key or a fixed code becomes {@code CHAR(n)}, because
 * its declared width is part of the contract. That covers {@code type_cd CHAR(2)} at
 * {@code V1__reference.sql} L106 and L178, {@code cat_cd CHAR(4)} at L203, and
 * {@code acct_group_id CHAR(10)}, {@code tran_type_cd CHAR(2)} and {@code tran_cat_cd CHAR(4)} at
 * L311, L313 and L319. No key is ever trimmed in a way that could change its value, in either
 * direction. A {@code PIC X(n)} used descriptively becomes {@code VARCHAR(n)}, because there the
 * trailing blanks are padding rather than content. That covers {@code description VARCHAR(50)} at
 * L117 and L209, and it alone is normalised: trailing blanks are removed on the inbound path before
 * the value is stored, and the stored value is returned as it stands on the outbound path and is
 * never re-padded to fifty.</p>
 *
 * <p>Assumptions: the key half is anchored in the baseline's own fallback key.
 * {@code app/cbl/CBACT04C.cbl} L437 moves the seven-character literal {@code 'DEFAULT'} into the field
 * declared at L79 as {@code PIC X(10)}, and a shorter literal moved into a longer alphanumeric item is
 * padded on the right, so the key the program then reads with is seven characters followed by three
 * spaces. {@code app/data/ASCII/discgrp.txt} holds exactly three distinct group identifiers across its
 * rows -- {@code A000000000}, {@code DEFAULT} followed by three spaces, and {@code ZEROAPR} followed
 * by three spaces -- so two of the three depend on that padding to reach their declared width.
 * Trimming a group identifier would collapse both to seven characters and the keyed lookup would then
 * match no row at all, so {@code DisclosureGroupMapper} passes it through untouched in both
 * directions.</p>
 *
 * <p>Assumptions: a two-character type code is passed through the trailing-trim member on the outbound
 * path, and that is not an exception to the rule above. The distinction the rule draws is not whether
 * the member is called but whether calling it could alter a value: a valid code occupies both
 * characters of a {@code CHAR(2)} column, so there is nothing for it to remove and the call is a
 * no-op. On {@code acct_group_id} the same call would not be a no-op, which is exactly why it is not
 * made there. The difference is recorded because a reader comparing the two mappers would otherwise
 * read the inconsistency as an oversight in one of them.</p>
 *
 * <p>Assumptions: the description half is anchored in three baseline behaviours, and they do not agree
 * with one another. {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} L1841 to L1844 moves a
 * trimmed description into the generated text host variable while computing the accompanying length
 * from the untrimmed field, and {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} L1539 to L1542
 * does the same. In contrast {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} L172 to L174 binds
 * the raw fixed host variables declared at its L74 {@code 05 INPUT-REC-NUMBER PIC X(2)} and L76
 * {@code 05 INPUT-REC-DESC PIC X(50)} rather than the generated structure, so it carries none of the
 * trimming behaviour at all.</p>
 *
 * <p>Assumptions: what those three converge on is reasoned out from the language definition, and is
 * offered as that rather than as an observation of stored bytes. Both trimming sites take a fixed
 * fifty-character argument -- {@code COTRTLIC.cbl} L172 declares
 * {@code 30 WS-ROW-TR-DESC-IN PIC X(50)} and {@code COTRTUPC.cbl} L335 declares
 * {@code 15 TTUP-NEW-TTYP-TYPE-DESC PIC X(50)}. Because the length function applied to a fixed
 * alphanumeric item yields that item's declared storage size rather than the length of its content,
 * the length host variable receives fifty unconditionally at {@code COTRTLIC.cbl} L1843 to L1844 and
 * at {@code COTRTUPC.cbl} L1541 to L1542. The three behaviours therefore agree on trailing blanks and
 * differ only on leading ones. Corroborating that the baseline treats surrounding blanks as
 * insignificant for equality, every description comparison it makes goes through a trim, at
 * {@code COTRTLIC.cbl} L1065 and L1069 and at {@code COTRTUPC.cbl} L791 and L795.</p>
 *
 * <p>Assumptions: trimming inbound rather than merely outbound is the load-bearing half. A
 * {@code VARCHAR} column stores precisely what it is given, so a description stored with its padding
 * intact would compare unequal to the same text without it in every later filter and ordering, and no
 * constraint anywhere would report the discrepancy. Normalising once at the point of storage is what
 * makes a create and a replace agree on one stored form.</p>
 *
 * <p>Two divergences from the baseline follow from this ruling, and both are stated rather than
 * smoothed over. The baseline's trim function removes blanks from both ends of its argument, whereas
 * the member here removes only trailing ones and treats a leading blank as content the source record
 * is entitled to hold. And on the outbound path the baseline sends the raw fixed fifty-byte field to
 * the map at {@code COTRTUPC.cbl} L1200 and {@code COTRTLIC.cbl} L1417, whereas this package does not
 * re-pad, so a response carries a description without its padding. In each case the baseline does what
 * those lines say, the Java implements what this ruling says, and the divergence is registered as
 * {@code D-REFERENCE-TRIM-TRAILING-ONLY} in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which is the one place such a difference
 * is recorded.
 *
 * <p>Refactoring Rationale: that citation named no entry, and when it was written no entry existed --
 * the register held only the integrity-sentence, action-domain and upsert-not-exposed entries, none of
 * them about trimming. So a claim that the divergence "is registered" was false, and false in the one
 * direction a reader cannot detect without opening the other document. The entry now exists and is
 * named here, which is what makes the claim checkable by literal search rather than by reading a
 * four-thousand-line register.</p> Everything under {@code app/} is reference material this migration reads and never
 * rewrites, so neither statement above describes an edit made to it.</p>
 *
 * <p>Assumptions: the generated declaration pair corroborates where this boundary falls, and the
 * corroboration is categorical rather than incidental. Only the varying-length columns use the split
 * length-and-text host-variable form -- {@code app/app-transaction-type-db2/dcl/DCLTRTYP.dcl} L40 to
 * L46 and {@code app/app-transaction-type-db2/dcl/DCLTRCAT.dcl} L45 to L51 -- while every fixed column
 * is a plain picture, at {@code DCLTRTYP.dcl} L38 and {@code DCLTRCAT.dcl} L39 and L42 to L43. Any
 * trimming the baseline performs can therefore only ever have reached a description and never a key,
 * which is precisely where this ruling puts the boundary.</p>
 *
 * <h2>The transaction-category code is character data</h2>
 *
 * <p>Alternatives Considered: this is the one documented type exception in the package, and the
 * evidence is set out on both sides so that a reader can audit the judgement rather than take it. The
 * category code is carried as a {@code String} onto a {@code CHAR(4)} column, and three authorities
 * support that reading. {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L3 declares
 * {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL}. The generated declaration agrees at
 * {@code app/app-transaction-type-db2/dcl/DCLTRCAT.dcl} L42 to L43, where
 * {@code DCL-TRC-TYPE-CATEGORY} is a plain {@code PIC X(4)}. And the seed data agrees: the codes in
 * {@code app/data/ASCII/trancatg.txt} run from {@code 0001} to {@code 0005}, zero-padded to four
 * characters.</p>
 *
 * <p>The two contrary declarations are named rather than passed over, because a reader who finds them
 * first will otherwise conclude the column type is wrong. {@code app/cpy/CVTRA04Y.cpy} L7 declares
 * {@code 10 TRAN-CAT-CD PIC 9(04)} and {@code app/cpy/CVTRA02Y.cpy} L8 declares
 * {@code 10 DIS-TRAN-CAT-CD PIC 9(04)}, both of which read as numeric. What decides it is the
 * consequence rather than the count of authorities: an integer column would render {@code 0001} as
 * {@code 1}, so only the character reading preserves the value a consumer of the baseline can observe
 * today. {@code V1__reference.sql} L203 records the same contradiction at the column itself, so the
 * schema and this charter are not two independent claims.</p>
 *
 * <p>Assumptions: this is a ruling about type, not about naming. No field is renamed anywhere in this
 * package, in either direction.</p>
 *
 * <h2>The rate never leaves fixed point</h2>
 *
 * <p>Assumptions: the disclosure-group interest rate is exact at every hop. It is
 * {@code NUMERIC(6,2)} in the schema at {@code V1__reference.sql} L337, derived from
 * {@code app/cpy/CVTRA02Y.cpy} L9 {@code DIS-INT-RATE PIC S9(04)V99}; it is a
 * {@code java.math.BigDecimal} at scale two on the entity; it is a
 * {@code com.carddemo.common.money.Money} on the response; and it reaches the wire as a JSON string,
 * because {@code com.carddemo.common.money.MoneyModule} serialises it that way. The string form is
 * the point of the chain rather than a stylistic preference: a JSON number is parsed into a
 * double-precision binary value by most clients, which loses exactness at the one boundary a user
 * actually reads.</p>
 *
 * <p>Assumptions: {@code float}, {@code double}, {@code java.lang.Float}, {@code java.lang.Double}
 * and a bare JSON number are forbidden in the rate path of this package, and that prohibition is
 * mechanised rather than left to review. Rule A3 of
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * subjects every production class residing under the analysed root {@code com.carddemo} rather than
 * the shared money package alone -- its own note records that under the narrower scope a transfer
 * object, an entity or a mapper could declare such a member and still pass -- and
 * {@code services/pom.xml} runs that class against this module's compiled classes through Surefire's
 * {@code dependenciesToScan}. A stray {@code double} introduced in a mapper here therefore fails the
 * build. Refactoring Rationale: an earlier revision of this paragraph stated the opposite, that the
 * rule's subject set stopped at the money package and that a breach here rested on review. That was
 * the more damaging direction to be wrong in: a reader who trusted it would take the prohibition for
 * a convention and could introduce the member believing only a reviewer stood in the way, and the
 * sibling charter of {@code com.carddemo.reference.dto} already described the gate correctly, so the
 * two files disagreed. The prohibition is still written out in full here, because a rule whose
 * enforcement lives two modules away is one a reader has to be told about before the failure explains
 * it to them.</p>
 *
 * <p>Assumptions: no mapper in this package performs arithmetic and none applies a rounding mode. The
 * rate is an operand of the interest computation at {@code app/cbl/CBACT04C.cbl} L464 to L465, whose
 * other operand is the category balance declared at {@code app/cpy/CVTRA01Y.cpy} L9 as
 * {@code TRAN-CAT-BAL PIC S9(09)V99} -- a record of the ledger schema, owned by a different bounded
 * context. The computation therefore belongs to batch-service and not here. Trade-offs: rescaling or
 * rounding a rate in transit would look harmless at the mapping site and would silently alter an
 * operand of that formula, so this package moves the value and declines to adjust it.</p>
 *
 * <h2>What this package does not do</h2>
 *
 * <p>Assumptions: mappers produce item types only. Assembling
 * {@code com.carddemo.common.web.PageResponse} -- its items together with the first key, the last key
 * and the more-pages indicator -- belongs to {@code com.carddemo.reference.service}, which is the only
 * layer that holds the keyset cursor and can therefore say whether a further page exists. A mapper is
 * handed one row and knows nothing about the query that produced it, so it could not populate those
 * members even if it were asked to.</p>
 *
 * <p>Assumptions: mappers construct no error payload and no user-facing message.
 * {@code com.carddemo.common.error.ApiError} and the shared exception handler own that, and this
 * module declares no second controller advice of its own, because a second one would take precedence
 * over the shared handler for the types it covers.</p>
 *
 * <p>Assumptions: the message-width contract is recorded here for completeness, and it has to be
 * stated per program because a single figure would be wrong. The error channel is the same in both
 * online programs -- working storage of {@code PIC X(75)} at
 * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} L167 and at
 * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} L249 -- against a screen field of
 * {@code PIC X(78)} in both symbolic maps, at
 * {@code app/app-transaction-type-db2/cpy-bms/COTRTUP.cpy} L78 and
 * {@code app/app-transaction-type-db2/cpy-bms/COTRTLI.cpy} L228. Seventy-five is therefore the
 * narrowest of the two widths and is the one the shared error payload honours. The information channel
 * does differ by program: {@code PIC X(40)} at {@code COTRTUPC.cbl} L142 but {@code PIC X(45)} at
 * {@code COTRTLIC.cbl} L236, against a screen field of {@code PIC X(45)} in both maps, at
 * {@code COTRTUP.cpy} L72 and {@code COTRTLI.cpy} L222. For contrast and to forestall a
 * misreading, {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} L61 declares {@code PIC X(80)},
 * but that is a print line in a batch program and is not a screen contract at all.</p>
 *
 * <h2>Deliberate omissions</h2>
 *
 * <p>These are recorded rather than merely left out, so that a later reader does not add one of them
 * for the sake of symmetry.</p>
 *
 * <ul>
 *   <li>Assumptions: there is no mapper for the synchronous date-conversion request and response pair.
 *       Those two shapes have no domain entity behind them, and the service layer builds the response
 *       directly from what {@code com.carddemo.common.validation.DateEditValidator} returns, so a
 *       mapper would have nothing on the entity side to convert.
 *       {@code DateInquiryReplyMapper} is not that missing class and should not be mistaken for it: it
 *       serves the message-driven inquiry flow and renders a fixed-width reply line, which is a
 *       different artefact answering a different caller.</li>
 *   <li>Assumptions: {@code LookupMapper} and {@code DisclosureGroupMapper} carry no inbound member.
 *       The three lookup tables and the disclosure groups are seeded reference data, so the DTO
 *       package publishes no create or update shape for them and there is nothing for an inbound
 *       member to accept.</li>
 *   <li>Assumptions: {@code DisclosureGroupMapper} has no list member either. The charter of
 *       {@code com.carddemo.reference.dto} records at its own L150 to L152 that the service exposes a
 *       single resolved rate rather than a collection, so no list request shape exists to page
 *       through.</li>
 *   <li>Assumptions: there is no masking, no encryption and no handling of personally identifying
 *       information here, for the reason set out with the MapStruct rejection above.</li>
 * </ul>
 *
 * <h2>Assumptions: the external contracts this package rests on</h2>
 *
 * <p>These are enumerated so that a breaking change made elsewhere is traceable to this file rather
 * than discovered in a failing conversion.</p>
 *
 * <ul>
 *   <li>The record layouts and declared lengths of {@code app/cpy/CVTRA02Y.cpy},
 *       {@code app/cpy/CVTRA03Y.cpy} and {@code app/cpy/CVTRA04Y.cpy}, and the lookup allow-lists of
 *       {@code app/cpy/CSLKPCDY.cpy} from which the three seeded lookup tables derive.</li>
 *   <li>The column names, types and widths declared by
 *       {@code services/reference-service/src/main/resources/db/migration/V1__reference.sql}.</li>
 *   <li>The shape of the six entities in {@code com.carddemo.reference.domain}: that every table sits
 *       in the {@code reference} schema; that the two composite primary keys are nested embeddable
 *       types on {@code DisclosureGroup} and {@code TransactionCategory} rather than separate
 *       top-level classes, which is why a mapper composes an identity object instead of setting key
 *       members one by one; and that a version member exists on {@code TransactionType} and
 *       {@code TransactionCategory} and on neither {@code DisclosureGroup} nor any of the three
 *       lookups. The response shapes of the first two therefore carry a version and the others do
 *       not, and that asymmetry is a property of the schema rather than of the mapping.</li>
 *   <li>The type names published by {@code com.carddemo.reference.dto}, and their record component
 *       order, since each conversion invokes a canonical constructor positionally.</li>
 *   <li>The JSON string serialisation performed by {@code com.carddemo.common.money.MoneyModule}, on
 *       which the rate contract above depends entirely.</li>
 *   <li>The member set of {@code com.carddemo.common.web.PageResponse}, which this package never
 *       constructs but whose item types it supplies.</li>
 * </ul>
 *
 * <p>Assumptions: one naming asymmetry is called out because it reads as a mistake and is not. The
 * entity is {@code UsPhoneAreaCode} but the response type published for it is
 * {@code PhoneAreaCodeResponse}, with no {@code Us} prefix, while the two response types beside it,
 * {@code UsStateResponse} and {@code UsStateZipPrefixResponse}, do keep theirs. All three conversions
 * live in {@code LookupMapper}, so the inconsistency is visible within a single class. The DTO package
 * owns those names, so this package follows them rather than renaming anything to make the group look
 * uniform.</p>
 *
 * <p>Assumptions: the referential rule that relates the two transaction-reference records is named
 * here only as context for why they are converted by two mappers that share a helper.
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L6 to L7 restricts deletion of a type that
 * categories still reference, which becomes the foreign key at {@code V1__reference.sql} L266 to L268
 * and surfaces to a caller as HTTP 409. Enforcing it is the database's work and reporting it is the
 * service layer's; no mapper participates.</p>
 *
 * <p>Trade-offs: cross-package references in this charter are written as plain qualified names in
 * inline code spans rather than as documentation links. No link-validating doclint step is
 * configured for this build, so a link would buy no verification while coupling this file to a
 * documentation goal that may be enabled later and would then have to resolve every name on a class
 * path. A plain name costs nothing now and stays readable in a plain-text diff, which is where most
 * readers of a charter meet it.</p>
 */
package com.carddemo.reference.mapper;
