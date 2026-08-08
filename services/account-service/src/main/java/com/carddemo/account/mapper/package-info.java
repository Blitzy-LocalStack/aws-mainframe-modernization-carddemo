/**
 * Hand-written anti-corruption layer of the account bounded context, and the only place in this
 * service where the representation concerns of a baseline record are permitted to appear.
 *
 * <p>Fixed widths, zoned-decimal sign overpunch, dropped padding, the one renamed field, masking and
 * the encrypted-identifier boundary are all admitted here and nowhere else. Every package downstream
 * of this one holds clean objects, and it holds them precisely because this package absorbed the
 * representation. The pattern has a name in the migration plan's design-pattern table, where it is
 * recorded as net-new and deliberate rather than as something carried over: an anti-corruption
 * layer. Nothing in the baseline corresponds to it, because a program that reads a record and writes
 * a screen has no boundary between the two to defend.</p>
 *
 * <p>Both sibling charters point here, and between them they close the gap on either side.
 * {@code com.carddemo.account.dto} names this package at L129 of its own descriptor as the sole
 * boundary at which representation concerns may appear, and it declares no dependency on any sibling
 * package at all. {@code com.carddemo.account.domain} states at L305 to L308 that it holds no mapper
 * and no codec, and it assigns fixed-width decoding, sign conventions, trailing-blank handling,
 * dropped padding, masking, encryption and the one renamed field to this package by name. An entity
 * therefore knows nothing of a transport shape and a transport shape knows nothing of an entity, so
 * this package is the single seam between them and the only place their two vocabularies meet.</p>
 *
 * <h2>What this package translates</h2>
 *
 * <p>Refactoring Rationale: this roster named TWO projections, was corrected to FOUR, and the package
 * holds FIVE. The omissions were never incidental -- {@code CustomerMapper} is the only cryptography
 * boundary in the service and the only place either protected identifier is masked,
 * {@code CardXrefMapper} is where the primary account number is masked to its last four digits, and
 * {@code AccountMapper} owns the other half of the account-update request that {@code CustomerMapper}
 * does not. A charter that listed the concerns and then omitted the files performing them would send a
 * reader looking for the masking rule to the files that do not hold it. Twice corrected by hand is
 * twice too many, so the enumeration is now MEASURED rather than counted: the marker line below and
 * every class name enumerated under it are checked against this directory on every build by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/PackageCharterInventoryTest.java},
 * so a sixth translation authored without an entry here fails the build instead of quietly making this
 * paragraph wrong a third time.</p>
 *
 * <p>Five translations are authored here, and each is cited by what it actually converts rather than by
 * what its name suggests:
 *
 * <pre>
 * this directory: 6 java files = 5 classes + 1 charter
 * </pre>
 *
 * <ul>
 *   <li>{@code AccountContextMapper} projects the two rows this context owns onto the contract a
 *       neighbouring context reads, taking {@code com.carddemo.account.domain.Account} to
 *       {@code com.carddemo.account.dto.AccountContextView} and
 *       {@code com.carddemo.account.domain.CardXref} to
 *       {@code com.carddemo.account.dto.CardXrefView}.</li>
 *   <li>{@code CardXrefMapper} projects a cross-reference row onto the published
 *       {@code com.carddemo.account.dto.CardXrefResponse}, and it is where the primary account number
 *       of that row is masked to its last four digits. That masking is the reason it exists as a
 *       separate projection from {@code AccountContextMapper}, whose view travels only between
 *       services inside the private network and therefore answers a different disclosure question.</li>
 *   <li>{@code CustomerMapper} translates the 500-byte customer record in both directions -- onto
 *       {@code com.carddemo.account.dto.CustomerResponse} at RECORD widths, onto the nested customer
 *       group of {@code com.carddemo.account.dto.AccountViewResponse} at SCREEN widths, and from
 *       {@code com.carddemo.account.dto.AccountUpdateRequest} back to
 *       {@code com.carddemo.account.domain.Customer}. It is the only cryptography boundary in this
 *       service: it declares the port through which a clear identifier becomes stored ciphertext,
 *       {@code com.carddemo.account.service.CustomerIdentifierCipher} implements it, and both output
 *       shapes publish the national identifier and the government-issued identifier as a fixed
 *       withholding marker. It is also the only projection here that produces two shapes from one row,
 *       which is what makes the record-versus-screen width rule below load-bearing rather than
 *       advisory.</li>
 *   <li>{@code AccountInquiryReplyMapper} encodes the fixed-layout reply of the inquiry flow, and it
 *       is wider than its two exported methods suggest because a fixed-layout reply is assembled field
 *       by declared width rather than by field name. Its private helpers each carry one width or one
 *       sign convention, and they are private precisely so that no caller can assemble a reply field
 *       by any other route.</li>
 *   <li>{@code AccountMapper} owns the ACCOUNT region of the account-update request -- the components
 *       {@code CustomerMapper} does not -- and is the counterpart of that class. It mutates a loaded
 *       row where its counterpart constructs one, because {@code com.carddemo.account.domain.Account}
 *       declares setters and {@code com.carddemo.account.domain.Customer} declares none, and the
 *       reason that difference is forced rather than chosen is recorded on the class itself.</li>
 * </ul>
 *
 * <p>Three baseline record contracts stand behind those projections, and each is a fixed record
 * length rather than a loose set of fields. {@code app/cpy/CVACT01Y.cpy} declares
 * {@code 01 ACCOUNT-RECORD} at L4 over 300 bytes, the length its own L2 records.
 * {@code app/cpy/CVCUS01Y.cpy} declares {@code 01 CUSTOMER-RECORD} at L4 over 500 bytes.
 * {@code app/cpy/CVACT03Y.cpy} declares {@code 01 CARD-XREF-RECORD} at L4 over 50 bytes, being
 * {@code XREF-CARD-NUM PIC X(16)} at L5, {@code XREF-CUST-ID PIC 9(09)} at L6 and
 * {@code XREF-ACCT-ID PIC 9(11)} at L7. Two symbolic maps supply the screen widths that the record
 * widths do not agree with, and both are UPPERCASE {@code .CPY} on disk, as are all seventeen files
 * in {@code app/cpy-bms}: {@code app/cpy-bms/COACTVW.CPY} runs 464 lines with 37 named data fields,
 * and {@code app/cpy-bms/COACTUP.CPY} runs 668 lines with 54. A lowercase spelling of either name
 * resolves to no file on a case-sensitive filesystem, so a citation written that way names
 * nothing.</p>
 *
 * <p>Assumptions: the copybook is normative and the screen map is not a second opinion about the
 * same value. A field's {@code PICTURE} clause fixes the Java type and the width on the side it was
 * declared on, and where a record width and a screen width disagree about one value, which they do
 * four times over, a projection must adopt the width belonging to the side it is producing. Reading
 * whichever width came to hand first is the specific mistake this charter exists to make
 * unavailable.</p>
 *
 * <h2>Why every projection here is written by hand</h2>
 *
 * <p>Alternatives Considered: generating these projections with MapStruct, which is the shortest
 * route and is rejected on two independent grounds, either of which would be sufficient on its own.
 * The first is availability: its most recent published release is a beta, and a code generator that
 * sits between a stored row and a published contract is not somewhere this migration accepts
 * pre-release behaviour. The second is the decisive one, because it would still hold if a stable
 * release shipped: copybook-to-transport mapping is not mechanical. It drops the trailing filler of
 * every one of the three records, masks a primary account number to its last four digits, suppresses
 * a card verification value outright, encrypts the national identifier and the government-issued
 * identifier, and renames one misspelled baseline field. Each of those is a judgement a reader
 * cannot recover from the code that performs it, each needs its justification written at the mapping
 * site, and a generated member has nowhere to hold one.</p>
 *
 * <p>Alternatives Considered: generating the accessors of the types involved with Lombok, rejected
 * for the same reason one layer down and recorded as such in the build rather than only here.
 * {@code services/pom.xml} L812 to L820 records that Lombok was rejected BECAUSE of this
 * documentation gate: an accessor produced during annotation processing has no source member on
 * which a docstring could sit, and {@code MissingJavadocMethod} is configured with
 * {@code allowMissingPropertyJavadoc} set false at L365 of
 * {@code config/checkstyle/checkstyle.xml}, so an undocumented accessor is a violation rather than a
 * tolerated omission. Generation would therefore have to be paired with a weakening of the gate to
 * be viable at all, which is the trade the gate exists to refuse.</p>
 *
 * <p>Assumptions: no suppression will ever cover this package, and the reason is written down in the
 * suppression file itself rather than inferred from its current contents.
 * {@code config/checkstyle/suppressions.xml} lists a mapper package at L169 to L180 as a target that
 * is never to be exempted, and it names the reason plainly: the word invites the assumption
 * "generated", and here the opposite is true. That same passage records a mapper as the
 * highest-value documentation target in the repository, on the ground that every one of the
 * decisions listed above is unrecoverable from the code. It also records the consequence for the
 * ruleset, which is easy to mistake for an unrelated setting: type scope is widened to private for
 * this exact population, and because that property is inclusive downwards, a private nested helper
 * here is audited on the same terms as the class enclosing it. Only two suppression entries exist in
 * that file, covering generated sources and test fixtures, and neither can match a path under
 * {@code src/main/java}. Adding a third that did would leave the build reporting success over the
 * code the gate was written for, which is indistinguishable in a log from a genuine pass.</p>
 *
 * <h2>The five representation concerns this package owns</h2>
 *
 * <p>Each of the five is a decision, not a mechanism, so each needs its rationale beside the method
 * that performs it rather than collected in a class header. They are enumerated here so that a
 * reviewer can check a proposed mapping against a closed list.</p>
 *
 * <p>First, trailing filler is dropped, and the drop is recorded against the record it came from so
 * that a later reader can distinguish a deliberate omission from an oversight. The three drops are
 * {@code FILLER PIC X(178)} at {@code app/cpy/CVACT01Y.cpy} L17, {@code FILLER PIC X(168)} at
 * {@code app/cpy/CVCUS01Y.cpy} L23 and {@code FILLER PIC X(14)} at {@code app/cpy/CVACT03Y.cpy} L8.
 * Assumptions: each is padding to a fixed record length and carries no value, and the arithmetic is
 * what establishes that rather than the field name. Summing the declared widths of the named fields
 * gives 122 bytes for the account record, 332 for the customer record and 36 for the cross-reference
 * record, and adding the filler in each case reaches exactly the 300, 500 and 50 bytes those
 * copybooks declare at their L2. A projection that carried a filler forward would publish padding as
 * data.</p>
 *
 * <p>Second, exactly one field is renamed, and the count matters because the repository-wide figure
 * is different. {@code ACCT-EXPIRAION-DATE} at {@code app/cpy/CVACT01Y.cpy} L11 is misspelled in the
 * baseline, and the member derived from it reads {@code expirationDate} over the
 * {@code expiration_date} column. Assumptions: this bounded context owns that rename and no other.
 * Three such renames exist across the whole migration, as
 * {@code services/account-service/src/main/resources/db/migration/V1__account.sql} records at L282
 * to L284, and the other two lie outside this context: {@code CARD-EXPIRAION-DATE} at
 * {@code app/cpy/CVACT02Y.cpy} L9 belongs to the card context, and
 * {@code PA-MERCHANT-CATAGORY-CODE} at
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} L36 belongs to the authorization
 * context. Claiming three here would attribute two other contexts' decisions to this one. The
 * baseline spells the field as it spells it, is reference material, and is never edited; the target
 * member carries the other spelling, and that divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md} rather than left to be noticed.</p>
 *
 * <p>Third, masking and encryption are applied on the way out and never on the way in.
 * Trade-offs: a primary account number is published masked to its last four digits, which costs the
 * caller the ability to read the full value from a response and buys a response that is safe to log,
 * and the one path that needs the unmasked value is an administrative card-detail path belonging to
 * the card context rather than to this one. The national identifier
 * {@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy} L17 and the government-issued
 * identifier {@code CUST-GOVT-ISSUED-ID PIC X(20)} at L18 are held as encrypted bytes over
 * {@code BYTEA} columns and are published masked. Assumptions: the three records this package
 * translates contain no card verification value at any offset, so there is nothing of that kind here
 * to suppress, and a mapping method that appeared to suppress one would be describing a field that
 * does not exist in its input.</p>
 *
 * <p>Fourth, two screen mappings are not one-to-one with the record, and both need their evidence
 * recorded because both look like oversights otherwise. Assumptions: there is no customer city field
 * anywhere in the baseline. A search for one across {@code app} returns nothing, and the customer
 * record accounts for its address entirely as {@code CUST-ADDR-LINE-1}, {@code CUST-ADDR-LINE-2} and
 * {@code CUST-ADDR-LINE-3} at {@code app/cpy/CVCUS01Y.cpy} L9 to L11, followed by
 * {@code CUST-ADDR-STATE-CD} at L12 and {@code CUST-ADDR-COUNTRY-CD} at L13. The screen city field
 * {@code ACSCITYI PIC X(50)} at {@code app/cpy-bms/COACTVW.CPY} L192 therefore maps to
 * {@code CUST-ADDR-LINE-3} at L11 of that copybook, and the width is the evidence for the choice
 * rather than the name: both are declared {@code X(50)}, so the mapping neither truncates nor pads.
 * The postal code is the second case, and it does truncate. {@code ACSZIPCI PIC X(5)} at
 * {@code app/cpy-bms/COACTVW.CPY} L186 is five characters narrower than
 * {@code CUST-ADDR-ZIP PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy} L14, so the projection to the
 * screen shape is lossy by construction and the loss belongs in the method that performs it.</p>
 *
 * <p>Fifth, amounts and long identifiers cross the boundary as digits-only strings and are numbers
 * only inside this package. Assumptions: the baseline itself treats them that way, holding each as a
 * character field with a numeric redefinition laid over it and reading the numeric view only inside
 * arithmetic, so a string on the wire is the faithful representation rather than a defensive one.
 * They become an exact fixed-point decimal at scale two, or a long integer, on entry here and revert
 * to text on exit.</p>
 *
 * <h2>One entity, two output shapes, and four widths that disagree</h2>
 *
 * <p>Assumptions: a single customer row is published in two different shapes, one at record widths
 * and one at the screen widths of the account-view map, and the two disagree about exactly four
 * fields. This is the clearest available demonstration that the mapping cannot be generated, because
 * no generator can choose between two declared widths for one value; only the shape being produced
 * decides, and that is a fact about the contract rather than about the row.</p>
 *
 * <ul>
 *   <li>Postal code: {@code X(10)} on the record at {@code app/cpy/CVCUS01Y.cpy} L14 against
 *       {@code X(5)} on the screen at {@code app/cpy-bms/COACTVW.CPY} L186.</li>
 *   <li>National identifier: {@code 9(09)} on the record at {@code app/cpy/CVCUS01Y.cpy} L17 against
 *       {@code X(12)} on the screen at {@code app/cpy-bms/COACTVW.CPY} L132.</li>
 *   <li>First phone number: {@code X(15)} on the record at {@code app/cpy/CVCUS01Y.cpy} L15 against
 *       {@code X(13)} on the screen at {@code app/cpy-bms/COACTVW.CPY} L204.</li>
 *   <li>Second phone number: {@code X(15)} on the record at {@code app/cpy/CVCUS01Y.cpy} L16 against
 *       {@code X(13)} on the screen at {@code app/cpy-bms/COACTVW.CPY} L216.</li>
 * </ul>
 *
 * <p>Two neighbouring fields are deliberately absent from that list, and saying why is what makes it
 * checkable. The city field agrees at {@code X(50)} on both sides, as recorded above, and the
 * government-issued identifier agrees at {@code X(20)}, being {@code CUST-GOVT-ISSUED-ID} at
 * {@code app/cpy/CVCUS01Y.cpy} L18 and {@code ACSGOVTI} at {@code app/cpy-bms/COACTVW.CPY} L210 and
 * at {@code app/cpy-bms/COACTUP.CPY} L282. Neither needs a width decision, so neither belongs among
 * the four that do.</p>
 *
 * <h2>Money is exact fixed point at every hop, and this package is one of the hops</h2>
 *
 * <p>The invariant is implemented in the shared kernel, and it is restated here because this package
 * is where an amount changes representation and therefore where it can be broken. An amount is
 * {@code NUMERIC(12,2)} in the database, an exact decimal carried at scale two with half-up rounding
 * in Java, and a quoted JSON string on the wire. The five money fields of the account record all
 * share one picture, {@code PIC S9(10)V99}, and they are
 * {@code ACCT-CURR-BAL} at {@code app/cpy/CVACT01Y.cpy} L7, {@code ACCT-CREDIT-LIMIT} at L8,
 * {@code ACCT-CASH-CREDIT-LIMIT} at L9, {@code ACCT-CURR-CYC-CREDIT} at L13 and
 * {@code ACCT-CURR-CYC-DEBIT} at L14. The first of those is the normative exemplar for the whole
 * migration.</p>
 *
 * <p>Alternatives Considered: publishing an amount as a JSON number, which reads to a client author
 * as the obvious choice and is rejected. A JSON number is parsed into an IEEE-754 binary floating
 * point value by most clients by default, and twelve significant digits at two decimal places cannot
 * be held exactly in that representation, so the amount is already inexact by the time it reaches the
 * reader. Trade-offs: the string form costs a client one explicit conversion and buys exactness at
 * the one boundary a balance or a credit limit is actually read at. IEEE-754 binary floating point is
 * barred from the money path outright, and the bar is an assertion in a running test rather than a
 * sentence in a document:
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * declares that rule at its L486.</p>
 *
 * <p>Assumptions: the serialiser that produces the string form is not registered anywhere in this
 * service's Java, and a reader looking for it in the application entry point will not find it. The
 * shared module publishes a provider-configuration file at
 * {@code services/common-lib/src/main/resources/META-INF/services/tools.jackson.databind.JacksonModule}
 * naming {@code com.carddemo.common.money.MoneyModule}, and the framework's find-and-add-modules step
 * is enabled by default, so the handler is present in every service carrying the shared module on its
 * classpath. The consequence for this package is specific: an amount must be wrapped in the shared
 * money type, because the serialiser is bound to that type alone. A member left as a general-purpose
 * decimal is not matched by it and renders as a bare JSON number, in which case the code compiles,
 * the service starts, the response is well formed, and the value has quietly become inexact. Nothing
 * in a build log, a startup log or a response status reports that.</p>
 *
 * <p>Assumptions: the decode is taken from the shared zoned-decimal codec and is never written
 * locally, because the sign convention is the part that fails silently. Every money field in these
 * records is zoned decimal with a sign overpunch rather than packed decimal, and
 * {@code tests/README.md} L273 to L274 records that the EBCDIC sign option is required because the
 * default misreads that overpunch and silently corrupts negative balances. A local decode would be
 * one more place that convention could be got wrong, and the failure mode is a plausible number
 * rather than an exception. {@code AccountInquiryReplyMapper} already encodes through
 * {@code com.carddemo.common.codec.ZonedDecimalCodec} at its L295 for exactly this reason.</p>
 *
 * <h2>What this package may import</h2>
 *
 * <p>Assumptions: one former copybook inclusion becomes exactly one type import from the single
 * package that owns that contract, which is the Java counterpart of compiling every baseline program
 * against one copybook include path. Shared concerns arrive from {@code com.carddemo.common} and are
 * never re-declared per service: the money type and its serialisation, the fixed-width, zoned-decimal
 * and packed-decimal codecs, the error model, the validation flags and the keyset page envelope.
 * Trade-offs: consuming a shared type couples this module to the shared kernel's release, and it buys
 * one definition per concept. A locally declared substitute would not replace the shared contract; it
 * would sit beside it, and one concept would have two definitions with nothing in a build log to say
 * which one a given response used.</p>
 *
 * <p>This package is the one place in the module that legitimately imports both an entity package and
 * a transport package, and that dual import is its purpose rather than a lapse: it is what being the
 * seam means. Three import classes are nonetheless closed to it. No web or serialisation-framework
 * type, because a projection that knew about a request or a response status would be making a
 * decision belonging to the controller. No cloud provider type, for the reason set out under the
 * cryptography boundary below. Nothing from another bounded context, and in particular nothing from
 * another context's entity package.</p>
 *
 * <p>Assumptions: those boundaries are enforced by a test rather than by a convention, so they cannot
 * decay quietly. The shared architecture test named above asserts at its L396 that a domain type
 * depends on no framework or infrastructure type, and at its L457 that no bounded context depends on
 * another context's entity package. Alternatives Considered: adding an import-control module to the
 * Checkstyle ruleset to police the same boundary. It is deliberately absent, so that layering has
 * exactly one owner; two owners would eventually disagree, and a reader finding a violation reported
 * by one and permitted by the other would have no way to tell which was authoritative.</p>
 *
 * <h2>The cryptography boundary runs through this package</h2>
 *
 * <p>Refactoring Rationale: the entity was deliberately left unable to encrypt or decrypt anything,
 * which pushes both directions of that conversion here. {@code Customer} holds the national
 * identifier and the government-issued identifier as encrypted bytes only, and no accessor on it
 * returns either in clear text; the architecture rule at L396 of the shared architecture test is what
 * makes that necessary, because reaching a key provider from an entity is reaching infrastructure
 * from the domain layer. The alternative was an entity that decrypted its own fields on access, which
 * would have been more convenient at every call site and would have made the domain layer depend on a
 * key provider, defeating the boundary the rule exists to hold.</p>
 *
 * <p>Assumptions: the conversion is reached through a port declared in this package's own signature
 * surface and injected through a constructor, never by importing a cloud provider type directly. Two
 * things follow, and both are the point. A projection stays unit-testable with a substitute and needs
 * neither a database nor a key provider to exercise, which is the same reason every other
 * collaborator in this migration is injected. And the provider type stays out of this module's import
 * graph, so the boundary asserted by the architecture test is not merely respected by habit. The
 * baseline stores both identifiers in clear; the target stores them encrypted and publishes them
 * masked, and that divergence is deliberate and is registered in the traceability document rather
 * than presented as equivalent behaviour.</p>
 *
 * <h2>How this package is verified, and the oracle it does not have</h2>
 *
 * <p>Assumptions: there is no golden-master oracle for anything in this bounded context, and no claim
 * of behavioural parity by comparison is available here. {@code tests/README.md} states at its L83 to
 * L85 that the online programs cannot run end to end without a CICS runtime, which the runner does not
 * have, and that only their extractable field-validation logic is unit-tested. Its business-rules
 * section, which opens at L553, names batch programs only, and not one of its rules governs the six
 * programs this context migrates: {@code COACTVWC}, {@code COACTUPC}, {@code CBACT01C},
 * {@code CBACT03C}, {@code CBCUS01C} and {@code COACCT01}. Recording that absence is what stops a
 * later reader assuming a comparison was made and passed.</p>
 *
 * <p>What is directly verifiable is exactly what this package deals in. A declared width, a record
 * length, a dropped filler and a user-visible string are all checkable against the cited copybook or
 * program line by string and integer equality, and they must be asserted character for character in
 * this service's own tests under {@code services/account-service/src/test}. That tree is a different
 * thing from the root {@code tests} suite and the two are never conflated: the root suite is the
 * reference oracle for the batch programs and is never modified, while this service's tests are new
 * and additive.</p>
 *
 * <p>Assumptions: the two trees are also graded differently, and importing one grading scheme into the
 * other would misreport a result. The root suite follows a mainframe condition-code convention in
 * which a warn level is its documented green state. The Java build has no such tier: the compiler, the
 * documentation gate, the test runner and the architecture test each either pass or fail. A build here
 * is therefore never described as green at a warn level, because no such outcome exists to describe.
 * </p>
 *
 * <h2>Why this descriptor exists</h2>
 *
 * <p>Assumptions: two independent obligations put this file here, and either alone would be enough.
 * The project Explainability rule requires a docstring on every module entry point at its L15, and in
 * Java a package declaration is that entry point, so a package descriptor is the only compilation unit
 * in which a package-level docstring can be written at all. There is no alternative location for
 * it.</p>
 *
 * <p>The second obligation is mechanical and it is build-fatal, and it takes two checks rather than
 * one. {@code JavadocPackage} is declared at L245 of {@code config/checkstyle/checkstyle.xml}, at
 * Checker level and therefore outside the tree walker that opens at L259, which makes it a file-set
 * check: it demands that a package descriptor FILE EXIST in any directory holding a processed
 * compilation unit, and this directory holds two. {@code MissingJavadocPackage} is declared at L378,
 * inside that tree walker, and it demands that the file CARRY Javadoc. A descriptor holding nothing but
 * a bare package statement satisfies the first and fails the second, so neither omitting this file nor
 * emptying it was available. The gate runs under execution id
 * {@code checkstyle-documentation-gate} at {@code services/pom.xml} L854, bound to the Maven
 * {@code validate} phase at L855, with {@code failOnViolation} true at L944,
 * {@code violationSeverity} at warning at L945 and {@code includeTestSourceDirectory} true at L972,
 * against a Checkstyle engine pinned to 13.8.0 at L461. It therefore fails closed, before compilation,
 * on every developer machine rather than only in continuous integration, and the suppression filter it
 * loads is declared non-optional at L228 of the ruleset so a missing suppression file cannot silently
 * disable it.</p>
 *
 * <p>Assumptions: the parameter, return-value and exception elements the rule enumerates at its L19 to
 * L21 describe callable code and have no counterpart on a package declaration, so they are omitted
 * here deliberately rather than written out empty; L21 is itself qualified as applying where
 * applicable. Fabricating a block tag to look compliant would add unverifiable content and would
 * offend the rule's own prohibition on vague rationale at L41. No authorship or version tag appears
 * either: the formatting checks that would require one are absent from the ruleset, and the written
 * convention at {@code docs/CODE_DOCUMENTATION_STANDARD.md} enumerates four docstring elements, none
 * of which is such a tag.</p>
 *
 * <p>Assumptions: the exception obligation on the methods in this package rests on the rule's L21
 * together with the house convention at {@code tests/README.md} L544 to L549 and the
 * {@code validateThrows} property set true at L455 of the ruleset. It does not rest on the rule's
 * validation gate at L43, whose triad names purpose, parameters and return values and does not
 * mention exceptions. A method here that validates an argument or decodes a width does raise, so the
 * obligation is live even though the gate sentence omits it.</p>
 *
 * <p>Assumptions: the rule's adjacency requirement at L27 asks that a justification sit beside the
 * code it explains, and a package descriptor has no code beside it, so the equivalent here is a
 * labelled sentence inside this block. That equivalence does not travel into the classes. In a mapper
 * method the labelled justification belongs AT THE MAPPING SITE, on the statement that masks, drops,
 * renames, truncates or converts, and not gathered into a class header where a reviewer cannot tell
 * which of several decisions it was written for.</p>
 *
 * <p>Every rationale in this block carries one of exactly four labels, written plural,
 * unparenthesised, colon-terminated and without emphasis markup, as
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes them at L203 to L226 and as the rule itself words
 * them at L31 to L34. Assumptions: a singular or parenthesised variant of a label is read by search
 * before it is read by a person, so a variant form is not a stylistic difference but a rationale that
 * an audit does not find. The abbreviated singular forms are named at L218 to L219 of that standard as
 * not permitted, and the ASCII hyphen in the fourth label is required because the surrounding
 * documentation elsewhere in the repository uses a non-breaking hyphen that no ordinary search
 * matches.</p>
 *
 * <h2>What a passing build does and does not establish</h2>
 *
 * <p>Trade-offs: the gate mechanises the docstring half of the rule thoroughly and the rationale half
 * not at all, and stating where the line falls is worth more than a claim of full coverage. It reaches
 * further than it first appears to: {@code MissingJavadocMethod} at L363 of the ruleset is configured
 * at private scope with its allowed-annotations list explicitly cleared, so an overriding method and a
 * private helper are both audited rather than exempted, and inherit-documentation alone satisfies
 * none of the rule's docstring elements. That matters directly here, because the reply encoder in this
 * package carries four private helpers.</p>
 *
 * <p>Assumptions: the rule's concession at its L23, permitting a single-line docstring on a trivial
 * accessor, is a concession about FORMAT and not a waiver of the obligation, and it is restated here
 * because reading it as a waiver is the likelier mistake. A one-line docstring is still a docstring and
 * must still state the purpose the rule's L18 asks for. Two further limits apply in this package. The
 * concession reaches a trivial accessor only, so none of the helpers here qualifies: a method that
 * decodes a sign convention, applies a declared width or masks a value is carrying a decision and
 * needs the fuller form. And the concession remains usable at all only because
 * {@code SingleLineJavadoc} is absent from the ruleset, which is what keeps a compact block legal
 * rather than a violation; were that module ever added, the format the rule expressly permits would
 * start failing the build.</p>
 *
 * <p>What the gate cannot do is enumerated in the ruleset's own header at L61 to L93, and none of it is
 * delegated away silently. It cannot judge whether a comment explains why rather than what; it cannot
 * detect a comment that merely restates the code beside it; it cannot verify that one of the four
 * categories was actually documented, nor that a category which is named was written in the canonical
 * form; it cannot identify which implementation choices are non-obvious, and so cannot detect one left
 * undocumented; and it detects a vague rationale only inside a Javadoc summary. The exception check has
 * its own blind spots for the same reason: it carries no knowledge of an exception hierarchy, so a
 * documented supertype and the exact type raised are indistinguishable to it.</p>
 *
 * <p>The consequence is the single most useful sentence a mapper author can read here, and it follows
 * from the rule's validation gate at L43 being CONJUNCTIVE. The docstring requirement and the
 * inline-rationale requirement fail review independently, so a method carrying a complete docstring
 * and an unexplained masking decision fails, and it fails while the build is green. A green
 * {@code validate} is evidence that the docstrings are present, and it is not evidence that the rule is
 * satisfied.</p>
 *
 * <h2>What this package never contains</h2>
 *
 * <p>These are standing prohibitions on what may be authored here rather than a record of anything
 * removed. Writing them down is what makes the boundary reviewable against a proposed addition instead
 * of only against the files already present, and it stops an absence being read as an oversight.</p>
 *
 * <ul>
 *   <li>No row access and no business rule. A projection receives an entity and returns a transport
 *       shape; it does not decide whether the entity should have been read, and it does not decide
 *       what happens when it was absent.</li>
 *   <li>No locally declared codec. Fixed-width, zoned-decimal and packed-decimal decoding all belong
 *       to the shared kernel, and a local copy would be a second place a sign convention could be got
 *       wrong.</li>
 *   <li>No locally declared error, exception, validation, pagination or message-catalogue type. Each
 *       belongs to the shared kernel, and a local substitute would give one concept two
 *       definitions.</li>
 *   <li>No offset pagination and no page envelope of any kind. Paging is keyset-based and its envelope
 *       is a shared kernel type, consumed at the layers that page and never declared here.</li>
 *   <li>No configuration class, no bean declaration and no batch job. Those belong to the
 *       configuration package of this module and to the batch context respectively.</li>
 *   <li>No second package descriptor, no platform module descriptor, no properties file, no ignore
 *       file, no readme and no copy of the architecture test. Each either belongs to a named location
 *       elsewhere or does not exist anywhere in the repository, and introducing one here would break an
 *       assumption every sibling module is built on.</li>
 *   <li>No subdirectory. This package is a leaf, and a nested package would need a descriptor of its
 *       own the moment it held a compilation unit.</li>
 * </ul>
 */
package com.carddemo.account.mapper;
