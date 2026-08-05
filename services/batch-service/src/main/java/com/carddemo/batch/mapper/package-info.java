/**
 * Hand-written anti-corruption layer for the batch bounded context, and the one boundary at which
 * the physical representation of a baseline record is read or written.
 *
 * <h2>Purpose, and the boundary this package is</h2>
 *
 * <p>Assumptions: the package root is {@code com.carddemo.batch}, and {@code mapper} is one of the
 * subpackages that {@code com.carddemo.batch.package-info} fixes and declares closed:
 * {@code job}, {@code service}, {@code repository}, {@code domain}, {@code dto}, {@code mapper}
 * and {@code config}. The migration plan designates the anti-corruption layer at its section
 * 0.4.3 as net-new and deliberate rather than as a transcription of anything the baseline had,
 * and it is the same section that gives this package its single defining ruling: <b>this is the
 * only place copybook representation concerns are allowed to appear, and every object downstream
 * of a mapper is clean.</b></p>
 *
 * <p>Read that ruling in both directions, because only one of the two is obvious. Read forwards
 * it is a permission: a type here may know about byte offsets, sign nibbles and padding, and it
 * is the only kind of type in this module that may. Read backwards it is a prohibition with far
 * more reach: nothing in {@code com.carddemo.batch.domain}, {@code com.carddemo.batch.dto},
 * {@code com.carddemo.batch.service}, {@code com.carddemo.batch.job},
 * {@code com.carddemo.batch.repository} or {@code com.carddemo.batch.config} may know any of it.
 * A field width that leaks into a service is not merely misplaced; it makes that service
 * untestable without a byte image, and it puts a second, undocumented copy of a layout into a
 * tree whose whole discipline is that a layout exists once. The sibling charters state the
 * matching half of that boundary from their own side, so the two agree rather than merely
 * coexist.</p>
 *
 * <p>"Representation concerns" is enumerated rather than gestured at, because a vague boundary
 * drifts and a specific one does not. Concretely, and exhaustively, the following belong here and
 * nowhere else in this module:</p>
 *
 * <ul>
 *   <li><b>Fixed-width byte offsets and declared record lengths.</b> The zero-based start offset
 *       and length of every field, and the record length each field set must reconcile to -- 300
 *       bytes for the account record, 50 for the card cross-reference, the category balance and
 *       the disclosure group, 350 for the posted-transaction and daily-transaction records, 430
 *       for the reject stream and 500 for the export record.</li>
 *   <li><b>Zoned-decimal sign overpunch.</b> The money regime of every base master, in which the
 *       sign is carried in the high nibble of the trailing digit rather than in a byte of its
 *       own.</li>
 *   <li><b>Packed decimal ({@code COMP-3}) and binary ({@code COMP}) storage widths.</b> Both
 *       appear, and they appear together in one record: {@code app/cpy/CVEXPORT.cpy} declares
 *       {@code COMP-3} amounts at its lines 41, 50, 52 and 71 and {@code COMP} integers at its
 *       lines 16, 25, 57, 72, 87, 95 and 96. A width taken from the digit count rather than from
 *       the storage rule is wrong for both.</li>
 *   <li><b>{@code FILLER}, dropped on decode and padded back on encode.</b> The asymmetry is
 *       deliberate and is stated in full below, along with the two cases that invert the
 *       intuition.</li>
 *   <li><b>The two {@code EXPIRAION} misspellings.</b> The baseline spells the account and card
 *       expiration dates without the {@code T} in the third syllable; the target names them
 *       differently. This boundary is where the two names meet, and the wording that meeting must
 *       use is prescribed below.</li>
 *   <li><b>Primary account number masking, card verification value suppression, and encryption of
 *       the national and government-issued identifiers.</b> These are transformations of value
 *       rather than of layout, and the reason they sit here rather than one layer down is
 *       important enough to have its own section.</li>
 * </ul>
 *
 * <h2>Why every mapper here is hand-written, and never generated</h2>
 *
 * <p>The name of this package is misleading in a way worth confronting immediately, because the
 * repository's own configuration confronts it too. In most codebases "mapper" means "generated",
 * and here it means the opposite. {@code config/checkstyle/suppressions.xml} lists the things
 * that may never be exempted from the documentation gate and names a mapper package as the second
 * of them, describing it as the trap most likely to be walked into precisely because the word
 * reads as generated; it concludes that a mapper is the highest-value documentation target in the
 * repository rather than a candidate for exemption. {@code config/checkstyle/checkstyle.xml}
 * widens its type scope all the way to {@code private} for this exact population, on the recorded
 * ground that a mapper is package-private because it is internal and internal is where an
 * undocumented type costs a future reader most.</p>
 *
 * <p>The causal chain behind that is worth stating plainly, because it is the honest argument
 * rather than a stylistic preference. The project's single user-specified rule requires an inline
 * rationale for every non-obvious implementation decision where a reasonable alternative existed,
 * and its validation gate fails code that lacks one. This package is by design the only place
 * representation concerns surface. Every decision that surfaces here is therefore a decision
 * about which of two or more defensible readings of a byte range to take -- whether a trailing
 * region is padding or content, whether a sign lives in a nibble or a byte, whether a name
 * crosses the boundary as written or as renamed, whether a value crosses whole or masked. In
 * other words the second limb of that gate bites on very nearly every method in this package, not
 * on an unusual few. A code generator emits a method body; it has nowhere to put the sentence
 * explaining why that body reads offset 58 rather than 59. That is why these are written by
 * hand.</p>
 *
 * <p>Alternatives Considered: MapStruct, the obvious candidate for exactly this shape of work,
 * is prohibited outright, and the migration plan records two independent grounds at its section
 * 0.6.1.1. The first is availability: its most recent published release is a beta, which is not
 * a dependency this tree takes. The second is the substantive one and would stand even if a
 * stable release shipped tomorrow -- copybook-to-target mapping is <em>not mechanical</em>. It
 * drops {@code FILLER}, masks the primary account number to its last four digits, suppresses the
 * card verification value entirely, encrypts the national and government-issued identifiers, and
 * renames misspelled baseline fields. Not one of those five is recoverable by a reader from the
 * code that performs it, so each needs its justification at the mapping site, and a generated
 * mapper has nowhere to hold one. Generating these bodies would satisfy the compiler and defeat
 * the only mechanism this repository has for keeping the five decisions reviewable.</p>
 *
 * <p>Alternatives Considered: Lombok is prohibited too, for a reason that has nothing to do with
 * the one above and must not be collapsed into it. The migration plan rejects it at the same
 * section on the ground that generated accessors cannot carry the Javadoc the Explainability rule
 * requires: a Lombok-built class either fails the documentation gate or has to be exempted out of
 * it, and exempting it is closed off from both sides here. The sanctioned brevity mechanism is
 * the Java 21 {@code record} with an explicit constructor, which gives the same concision with
 * members that can be documented. Collapsing the two prohibitions into one sentence would lose
 * information, because removing either cause leaves the other prohibition standing on its
 * own.</p>
 *
 * <p>Trade-offs: hand-written mapping is more verbose than either generated alternative, and that
 * cost is accepted rather than disputed. What is bought is a justification at every site where a
 * decision was made, which is the deliverable the gate audits; the verbosity is the price of it.
 * What is given up is compile-time coupling between a layout descriptor and the mapper that reads
 * it, so a descriptor edited without its mapper is a runtime geometry failure rather than a
 * compile error. That exposure is mitigated by keeping one descriptor per record in
 * {@code com.carddemo.common.codec.CopybookLayout} rather than by generating the mappers, since
 * generation would remove the compile-time gap only by removing the documentation with it.</p>
 *
 * <p>Alternatives Considered: <b>no abstract base mapper, no generic reflective mapper, and no
 * shared superclass of any kind belongs in this package.</b> This follows from the same reasoning
 * and is stated separately because it is the form the temptation actually takes once the verbosity
 * above becomes visible. Eight mappers that each decode a fixed-width record look like eight
 * instances of one pattern, and factoring the pattern upward is the natural instinct. The
 * migration plan's designation of this layer as hand-written means each decision is justified in
 * place, and a base class is precisely a mechanism for relocating decisions away from the place
 * they apply: the eight justifications would collapse into one paragraph on the superclass that is
 * true of none of the eight in particular. Reflection makes it worse again, because a reflective
 * mapper's field handling is not readable at all from the site that invokes it.</p>
 *
 * <h2>The rationale vocabulary every mapper here uses</h2>
 *
 * <p>Every rationale in this package is tagged with one of exactly four labels, written in the one
 * permitted form and in no other:</p>
 *
 * <ul>
 *   <li>{@code Alternatives Considered:}</li>
 *   <li>{@code Refactoring Rationale:}</li>
 *   <li>{@code Assumptions:}</li>
 *   <li>{@code Trade-offs:}</li>
 * </ul>
 *
 * <p>Assumptions: the form is load-bearing and {@code docs/CODE_DOCUMENTATION_STANDARD.md} is its
 * authority. Four properties matter, and each is a way the label has been written wrongly before.
 * The plural forms are the accepted ones, so neither singular abbreviation is available. The
 * label is unparenthesised and opens its own rationale, rather than being wrapped in brackets or
 * prefixed by a heading word. The colon is retained, because a label without it names a category
 * without reading as a label. And no emphasis markup is applied: the standard's own bullets
 * render these in bold, but that bold is the document's typography and not part of the written
 * form -- a Java comment renders no markup, so the label in code is exactly
 * {@code Assumptions:} and nothing more. The mechanical reason is that these labels are found by
 * a literal search before they are read by a person, across seven languages in which no linter
 * parses prose, so one spelling makes a Rule-audit complete and a second spelling makes it
 * silently partial.</p>
 *
 * <p>Assumptions: the labels are typed rather than copied from anywhere in the existing tree, and
 * this is a real hazard rather than a hypothetical one. {@code tests/README.md} tags its own
 * design-decision bullets with these same words but writes the hyphen in {@code Trade-offs} as a
 * Unicode non-breaking hyphen rather than as the ASCII hyphen-minus, and it carries that byte in
 * over a hundred places. A label copied out of it looks identical on screen and does not match
 * the search that audits this tree. That suite is reference-only and is not retyped, so the two
 * trees genuinely read differently here, and the divergence is accepted in favour of matching the
 * rule this tree is audited against.</p>
 *
 * <p>Assumptions: a label alone does not discharge the obligation, and this is the requirement
 * most easily satisfied in appearance only. Every "why" in this package resolves to a concrete
 * citation -- a copybook line, a COBOL paragraph line, a job-control data-definition or sort
 * field line, or an index-build key operand -- so that a reader can check the claim against the
 * artifact rather than take it on trust. The rule forbids vague rationales explicitly, and an
 * unsupported assertion that one arrangement is cleaner or reads more naturally than another
 * fails the gate no matter which of the four labels precedes it. A rationale is specific when a
 * reader can tell what would have gone wrong under the alternative: which byte moves, which cents
 * differ, which comparison against a committed expectation file stops matching.</p>
 *
 * <p>Assumptions: one note on naming, because two rule sets are in play and they are numbered
 * alike. The user-specified rule is Rule 1, and it is the sole user-specified rule governing this
 * work. The
 * migration plan's transformation rules are separately numbered T1 through T10, of which T1 makes
 * the copybook normative, T2 fixes the import discipline and T3 fixes the money representation;
 * T10 is the plan's own echo of Rule 1. Citing one when the other is meant inverts the
 * authority, so they are always written distinctly here.</p>
 *
 * <h2>The nine compilation units, and the two mappers that look missing</h2>
 *
 * <p>Assumptions: eight mappers belong here and no ninth; with this charter the directory holds
 * nine compilation units. The migration plan declares this population at its section 0.4.1.2 as
 * {@code mapper/*Mapper.java}, which fixes two things at once: what the members are, and that
 * they sit directly in this directory. <b>This package is a leaf and has no subpackage.</b> A
 * directory beneath this one would need a charter of its own and would have to justify a boundary
 * the plan does not draw, so a mapper that grows large is still one file rather than a subtree.</p>
 *
 * <dl>
 *   <dt>{@code package-info.java}</dt>
 *   <dd>This charter. It declares no executable type, and its entire value is that the eight
 *       mappers are written against the rulings stated here.</dd>
 *
 *   <dt>{@code CardXrefRecordMapper}</dt>
 *   <dd>The 50-byte card cross-reference of {@code app/cpy/CVACT03Y.cpy}: three fields and a
 *       trailing {@code FILLER PIC X(14)} at line 8, reconciling 36 mapped bytes plus 14 of
 *       padding to the declared 50.</dd>
 *
 *   <dt>{@code AccountRecordMapper}</dt>
 *   <dd>The 300-byte account record of {@code app/cpy/CVACT01Y.cpy}, carrying five zoned
 *       {@code PIC S9(10)V99} money fields, one of the two {@code EXPIRAION} names at line 11,
 *       and a trailing {@code FILLER PIC X(178)} at line 17.</dd>
 *
 *   <dt>{@code DisclosureGroupRecordMapper}</dt>
 *   <dd>The 50-byte interest-rate lookup of {@code app/cpy/CVTRA02Y.cpy}: a three-part key and
 *       one {@code PIC S9(04)V99} rate. The rate is the one number this package handles that is
 *       not money and is never summed with any.</dd>
 *
 *   <dt>{@code TransactionCategoryBalanceRecordMapper}</dt>
 *   <dd>The 50-byte per-account, per-type, per-category running balance of
 *       {@code app/cpy/CVTRA01Y.cpy}, whose amount is the narrower {@code PIC S9(09)V99}
 *       precision.</dd>
 *
 *   <dt>{@code TransactionRecordMapper}</dt>
 *   <dd>The 350-byte posted-transaction record of {@code app/cpy/CVTRA05Y.cpy} -- the ledger side
 *       of the pair whose two halves are field-for-field identical and differ only by prefix.</dd>
 *
 *   <dt>{@code DailyTransactionMapper}</dt>
 *   <dd>The 350-byte daily-transaction input record of {@code app/cpy/CVTRA06Y.cpy}, and also the
 *       posting field map described below.</dd>
 *
 *   <dt>{@code TransactionRejectRecordMapper}</dt>
 *   <dd>The 430-byte reject stream: a rejected daily transaction carried whole at its full 350
 *       bytes, followed by a numeric reason and a 76-character description.</dd>
 *
 *   <dt>{@code ExportRecordMapper}</dt>
 *   <dd>The 500-byte multi-record export layout of {@code app/cpy/CVEXPORT.cpy}, discriminator
 *       and all five views.</dd>
 * </dl>
 *
 * <p>Alternatives Considered: {@code DailyTransactionMapper} deliberately owns two things rather
 * than one -- the {@code app/cpy/CVTRA06Y.cpy} 350-byte record representation, and the field map
 * that the {@code 2000-POST-TRANSACTION} paragraph of {@code app/cbl/CBTRN02C.cbl} applies to it.
 * The alternative was a separate posting-map type, and it was rejected on a concrete dependency:
 * {@code TransactionRejectRecordMapper} needs that same 350-byte layout descriptor in order to
 * emit the raw reject image at its original width, so the descriptor has two consumers whichever
 * way the files are cut. Splitting it produces two descriptors for one record, and two
 * descriptors for one record drift -- silently, because each stays internally consistent while
 * they disagree with each other, and a reject image written from the stale one is a file that
 * still opens and still reads. One owner makes the drift impossible rather than merely
 * unlikely.</p>
 *
 * <p>Alternatives Considered: {@code ExportRecordMapper} is one file and not five, even though
 * {@code app/cpy/CVEXPORT.cpy} defines five distinct record shapes over one payload -- the
 * customer view at lines 24 to 42, the account view at 47 to 60, the transaction view at 65 to
 * 79, the card cross-reference view at 84 to 88 and the card view at 93 to 100, each redefining
 * the same {@code EXPORT-RECORD-DATA PIC X(460)} declared at line 19. A file per view is the
 * tidier-looking arrangement and was rejected because Java has no {@code REDEFINES}. In COBOL the
 * five overlays coexist and the program selects among them; in Java the bytes are one array and
 * nothing about them says which view applies. Only the {@code EXPORT-REC-TYPE PIC X(1)}
 * discriminator at {@code app/cpy/CVEXPORT.cpy:10} says that, and it must be read before any of
 * the five views is valid. Five files would each have to duplicate the discriminator read, which
 * puts five copies of one decision in the tree and admits the failure where four are updated and
 * the fifth silently decodes an account view over customer bytes. One file reads the
 * discriminator once and dispatches.</p>
 *
 * <p>Assumptions: <b>there is deliberately no {@code CustomerRecordMapper} and no
 * {@code CardRecordMapper} in this package, and neither is missing.</b> A reader who knows the
 * baseline will look for them, because the customer and card shapes plainly exist -- the customer
 * shape at {@code app/cpy/CVEXPORT.cpy:24-42} and the card shape at
 * {@code app/cpy/CVEXPORT.cpy:93-100}. What matters is that in this module they exist <em>only</em>
 * as views inside the export record, and {@code com.carddemo.batch.domain} declares no
 * {@code Customer} entity and no {@code Card} entity, so a mapper for either would have a source
 * shape and no target to map it to. The two views therefore yield ordered field maps inside
 * {@code ExportRecordMapper}, which is where the bytes are actually reached. Adding either file
 * would mean inventing a batch-side entity that no job in this module reads or writes, which is
 * how a bounded context acquires a table it does not own.</p>
 *
 * <h2>What is imported here, and never re-declared here</h2>
 *
 * <p>Assumptions: the migration plan's transformation rule T2 makes one former {@code COPY}
 * statement become exactly one type import from the single package that owns that contract. For
 * this package the
 * consequence is narrow and absolute: <b>shared representation machinery is imported from
 * {@code com.carddemo.common} and is never re-declared under {@code com.carddemo.batch}.</b> The
 * types are named individually below, because naming them is the mechanism that stops a mapper
 * from writing its own:</p>
 *
 * <ul>
 *   <li>{@code com.carddemo.common.codec.CopybookLayout} -- the layout descriptors, together with
 *       its nested {@code Kind}, {@code FieldSpec} and {@code RecordSpec}. {@code Kind} is the
 *       storage classification a field's width is derived from, {@code FieldSpec} carries one
 *       field's name, zero-based start offset, length and kind, and {@code RecordSpec} carries a
 *       whole record's field set with its declared and key lengths. This is the single source
 *       every geometry decision in this package reads from.</li>
 *   <li>{@code com.carddemo.common.codec.FixedWidthCodec} -- record and field encode and decode by
 *       offset and length.</li>
 *   <li>{@code com.carddemo.common.codec.ZonedDecimalCodec} -- sign-overpunch decode and encode,
 *       the money regime of every base master record this package handles.</li>
 *   <li>{@code com.carddemo.common.codec.PackedDecimalCodec} -- packed {@code COMP-3} and binary
 *       {@code COMP} decode and encode, the regime of the export record.</li>
 *   <li>{@code com.carddemo.common.money.Money} -- the fixed-point arithmetic, including the
 *       multiply-before-divide ordering the interest formula depends on.</li>
 *   <li>{@code com.carddemo.common.money.MoneyModule} -- the serialisation that puts money on a
 *       wire as a JSON string.</li>
 *   <li>{@code com.carddemo.common.time.TimestampFormatter} -- the exact 26-character target
 *       timestamp form.</li>
 * </ul>
 *
 * <p>Assumptions: {@code common-lib} is the Java analogue of compiling every COBOL program with a
 * single copybook include path, so a layout or a codec re-declared per service is the same defect
 * as a copybook duplicated per program -- one artifact, two truths, and nothing that fails when
 * they diverge. That is the whole reason the shared kernel exists, and it is why a mapper that
 * needs a codec behaviour the kernel does not yet offer extends the kernel rather than writing a
 * local variant. This module declares exactly one intra-repository Maven dependency, on
 * {@code common-lib}, so the kernel is reachable and no other service's code is.</p>
 *
 * <p>Assumptions: money is exact fixed point at every hop and is never anything else. The
 * migration plan's transformation rule T3 fixes the representation as {@code NUMERIC(p,2)} in
 * SQL, {@code BigDecimal} at scale 2 in Java, and a JSON string on any wire. <b>{@code float},
 * {@code double}, {@code java.lang.Double} and bare JSON numbers are forbidden in the money
 * path</b>, and the prohibition belongs to the layering rules that the {@code architecture-rules}
 * Surefire execution in {@code services/pom.xml} selects by the simple name
 * {@code LayeringRulesTest}, so it is asserted by a build rather than requested in a review. The
 * specific harm is not abstract: a JSON number is parsed into an IEEE-754 double by most clients,
 * which destroys exactness at precisely the boundary a user reads, and a balance wrong in the
 * cents still looks entirely plausible. Two precisions meet in this package and a mapper has to
 * know which is which -- account-side money is {@code PIC S9(10)V99}, twelve display bytes, for
 * example {@code ACCT-CURR-BAL} at {@code app/cpy/CVACT01Y.cpy:7}, while transaction-side money is
 * {@code PIC S9(09)V99}, eleven display bytes. Decoding the wider field at the narrower scale
 * silently loses its eleventh integer digit on exactly the accounts that have one.</p>
 *
 * <p>Assumptions: <b>no type in this package may import another service's {@code ..domain..}
 * package</b>, and this too is asserted by {@code LayeringRulesTest} rather than left to review.
 * The rule is easy to trip here specifically, because seven of the tables this module's entities
 * map already have an entity in {@code transaction-service} or {@code account-service}, so
 * reaching for one of those looks like reuse. It is not: a compile-time dependency between two
 * independently deployable services means the two must be built, versioned and released together,
 * which reintroduces the coupling bounded contexts exist to remove. This module and those two
 * agree through the physical schema and never through code.</p>
 *
 * <h2>{@code FILLER} is asymmetric, and two cases invert the intuition</h2>
 *
 * <p>Assumptions: <b>{@code FILLER} is dropped on decode and padded back as blanks on encode.</b>
 * The asymmetry is the ruling, and treating the two directions alike is the error. Dropping it on
 * decode follows from transformation rule T1, under which trailing filler is padding to a fixed
 * record length rather than data, so it becomes no field of any target; the plan requires the drop
 * be recorded, and each mapper records the width it dropped so that its field set plus that width
 * reconciles to the declared record length. Restoring it on encode is what makes an encoded record
 * byte-identical to the baseline at its full declared length rather than merely correct in its
 * populated prefix.</p>
 *
 * <p>Assumptions: the restoration is not tidiness, and the reason is the parity oracle. The
 * committed expectation files are compared byte for byte after timestamp normalisation, so a
 * re-encoded 300-byte account record that is right in its first 122 bytes and 178 bytes short is
 * a failed comparison, not a near miss. The same property is what makes round-tripping meaningful
 * at all: a decode followed by an encode has to reproduce the original bytes exactly, and it
 * cannot if either direction treats the padding as optional.</p>
 *
 * <p>Assumptions: two cases are genuinely not padding, and a mapper that applies the general rule
 * to either will place every subsequent field wrongly.</p>
 *
 * <ul>
 *   <li><b>A {@code FILLER} carrying a {@code REDEFINES} clause is an overlay alias and
 *       contributes zero bytes.</b> It must not advance the offset. The exemplar is
 *       {@code app/cbl/CBTRN02C.cbl:160}, which declares {@code 01 FILLER REDEFINES
 *       DB2-FORMAT-TS.} over the {@code PIC X(26)} timestamp declared immediately above it at
 *       line 159, and then subdivides that same 26 bytes into fourteen subordinates. The
 *       subordinates are a second way of reading bytes that already exist; they are not 26 further
 *       bytes. Adding the overlay's width to the running offset double-counts the region and
 *       shifts everything after it, and because the record still reaches its declared length the
 *       result is a decode that succeeds and is wrong.</li>
 *   <li><b>A {@code FILLER} carrying a {@code VALUE} clause is content, not padding.</b> A value
 *       clause means the region has a defined content the baseline relies on, so it is carried
 *       rather than dropped and is certainly not overwritten with blanks on encode.</li>
 * </ul>
 *
 * <p>Assumptions: a <em>named</em> filler is still padding. The name is a convenience for the
 * COBOL programmer and carries no meaning that survives the boundary; the shared kernel's own
 * layout catalogue makes the same call, treating {@code SEC-USR-FILLER PIC X(23)} as padding
 * despite its having a name. The test a mapper applies is therefore the clause the field carries
 * -- a redefinition, a value, or neither -- and never whether the field happens to be called
 * {@code FILLER}.</p>
 *
 * <h2>Masking belongs to this package, and not to the codec</h2>
 *
 * <p>Assumptions: {@code FixedWidthCodec} does not mask anything, and a mapper that assumes
 * otherwise will ship a full primary account number. The shared kernel is explicit about the
 * division:
 * {@code CopybookLayout.FieldSpec} carries a {@code sensitive} flag, and that flag <em>marks</em>
 * the field and does nothing else. The kernel's own charter states the boundary from its side and
 * assigns primary account number masking to the last four digits, card verification value
 * suppression, and encryption of the national and government-issued identifiers to the mapper
 * layer -- to this package. <b>This charter is the receiving end of that delegation, and there is
 * no third place for the work to have gone.</b></p>
 *
 * <p>Assumptions: the reason the codec declines the job is structural rather than a division of
 * labour that could have gone the other way. A codec that masked could not round-trip. Masking is
 * lossy by definition -- twelve of the sixteen digits are gone, and a suppressed verification
 * value is gone entirely -- so a decode that masked could never be followed by an encode
 * reproducing the original bytes, and byte-identical round-tripping is the property the parity
 * oracle depends on. Masking therefore has to happen strictly after the layout boundary, on the
 * way to a target that is allowed to hold less than the record did. The {@code sensitive} flag
 * exists so that a mapper can find those fields without hard-coding a list of names, which is the
 * part the descriptor can usefully own.</p>
 *
 * <p>Assumptions: one discipline follows immediately and applies to every mapper here.
 * <b>An exception message concerning a sensitive field reports only that field's name, its offset,
 * its length and its kind, and never echoes the raw bytes.</b> The kernel's descriptor already
 * takes this line in its own failure reporting, and it has to hold on this side too, because a
 * decode failure is exactly the moment the tempting thing to log is the input that failed. A
 * diagnostic that quotes the offending bytes of a card number has put a primary account number
 * into a log aggregator, where it long outlives the incident it was meant to explain. Naming the
 * field, the offset, the length and the kind is enough to locate any real geometry defect, so
 * nothing diagnostic is lost by the restriction.</p>
 *
 * <h2>How a divergence from the baseline is worded</h2>
 *
 * <p>Assumptions: this package sits closer to the baseline than any other in the module, so it is
 * where the temptation to describe the migration as an improvement is strongest. The wording is
 * constrained, and the constraint is absolute. Everything under {@code app/**} is reference-only
 * under the migration plan's section 0.2.2: it is read as the specification, it is never modified,
 * and it keeps running unchanged. <b>A comment in this package therefore never says that anything
 * under {@code app/**} was {@code fixed}, {@code corrected}, {@code patched}, {@code remediated}
 * or {@code repaired}.</b> Those five words are quoted here as the prohibition itself rather than
 * used, so that a search for them lands on this list. Each would be factually wrong, not merely out
 * of scope -- nothing was done to the COBOL at all.</p>
 *
 * <p>The one permitted framing has three parts and always all three: <b>the baseline declares X;
 * the Java implements Y; the divergence is documented in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</b> The third part is what makes it
 * a divergence rather than a discrepancy, so it is never dropped.</p>
 *
 * <p>Two divergences reach this package, and they are named so that a mapper author recognises one
 * on meeting it rather than inventing wording under pressure.</p>
 *
 * <ul>
 *   <li><b>The two {@code EXPIRAION} names.</b> {@code app/cpy/CVACT01Y.cpy:11} declares
 *       {@code ACCT-EXPIRAION-DATE}, ten bytes, with the {@code T} absent from the third syllable;
 *       {@code app/cpy/CVEXPORT.cpy:54} declares {@code EXP-ACCT-EXPIRAION-DATE} and
 *       {@code app/cpy/CVEXPORT.cpy:98} declares {@code EXP-CARD-EXPIRAION-DATE} the same way. The
 *       target names the field for its ordinary spelling, and the rename is registered in
 *       {@code docs/architecture/data-model-and-schema-mapping.md} along with the migration's other
 *       renames. Two consequences follow for a mapper. First, the baseline spelling is carried
 *       unchanged into every citation of those lines, including citations that sit beside the
 *       target name, because a citation whose text has been tidied no longer locates the field it
 *       claims to. Second, the rename happens at this boundary and nowhere else: no type outside
 *       this package ever sees the baseline spelling, which is what keeps the misspelling from
 *       propagating into a column name, a transfer object or an interface.</li>
 *   <li><b>The export and import record key.</b> {@code app/cbl/CBEXPORT.cbl:65-69} selects the
 *       export file as {@code ORGANIZATION IS INDEXED} with {@code RECORD KEY IS
 *       EXPORT-SEQUENCE-NUM}, while the file description at {@code app/cbl/CBEXPORT.cbl:89-92}
 *       declares its record as a bare {@code PIC X(500)} -- so the named key is not a field of the
 *       record it keys. The name resolves instead to the working-storage copy of
 *       {@code app/cpy/CVEXPORT.cpy}, where {@code EXPORT-SEQUENCE-NUM PIC 9(9) COMP} is declared
 *       at line 16. {@code app/cbl/CBIMPORT.cbl} declares the mirror image of the same
 *       arrangement. The consequence recorded by the reference test suite is that this pair does
 *       not compile under the open-source compiler and its round-trip test is skipped, and the
 *       suite reports that as a soft warning rather than a failure. {@code ExportRecordMapper}
 *       implements the sequence number as a field of the 500-byte record it keys, which is the
 *       behaviour the layout supports; the baseline is left exactly as it is, and the divergence is
 *       registered in the traceability document. The framing matters here more than anywhere else
 *       in this package, because this is the one place where a reader might reasonably suppose the
 *       migration had touched the COBOL. It did not.</li>
 * </ul>
 *
 * <h2>The documentation gate, and why nothing here may be exempted from it</h2>
 *
 * <p>The facts below are stated once, here, so that eight mappers do not each rediscover them from
 * a failing build.</p>
 *
 * <ul>
 *   <li><b>The gate runs at {@code validate}, before compilation.</b>
 *       {@code services/pom.xml} binds {@code maven-checkstyle-plugin} to the Maven
 *       {@code validate} phase with {@code failOnViolation} true and {@code violationSeverity}
 *       {@code warning}. A documentation defect therefore fails <em>every local build</em> and does
 *       so before a single class is compiled, rather than surfacing later in continuous
 *       integration. {@code includeTestSourceDirectory} is true, so a mapper's tests are audited on
 *       the same terms as the mapper.</li>
 *   <li><b>A {@code record} needs a parameter at-clause for every component.</b>
 *       {@code JavadocType} runs with missing parameter tags disallowed, and a record's components
 *       are its parameters. Since the sanctioned brevity mechanism in this tree is the record, this
 *       is the check a mapper meets most often.</li>
 *   <li><b>Method documentation is complete or it is a violation.</b> {@code JavadocMethod} runs
 *       with missing parameter tags disallowed, missing return tags disallowed and throws
 *       validation enabled, across public, protected, package-private and private alike.</li>
 *   <li><b>At-clause order is the default order:</b> parameters first, then the return value, then
 *       the exceptions. {@code AtclauseOrder} is configured at its default and enforces it.</li>
 *   <li><b>Every summary ends with a period, and some summaries are rejected outright.</b>
 *       {@code SummaryJavadoc} forbids the fragments {@code TODO}, {@code FIXME} and {@code TBD},
 *       the phrases this ruleset names as vague rationales, and a summary that opens by announcing
 *       itself as a getter or a setter. The period is the check's default requirement and is not
 *       optional.</li>
 *   <li><b>Never add an authorship, a since or a version at-clause.</b> {@code JavadocStyle} and
 *       {@code WriteTag} are deliberately absent from {@code config/checkstyle/checkstyle.xml},
 *       which means those clauses are unpoliced rather than required; adding them satisfies no
 *       check and dates the file.</li>
 *   <li><b>Fields need no Javadoc.</b> {@code JavadocVariable} is deliberately absent as well. Do
 *       not add field documentation to satisfy a rule that is not configured -- and do not read its
 *       absence as licence to leave a non-obvious constant unexplained, since the rationale
 *       obligation is independent of the presence checks.</li>
 * </ul>
 *
 * <p>Assumptions: the exception at-clause is required on the authority of Rule 1's own exceptions
 * clause, reinforced by the ruleset's throws validation -- and <em>not</em> on the authority of the
 * validation gate, which enumerates only purpose, parameters and return values. The distinction is
 * recorded because misattributing it invites the inference that the clause is optional wherever the
 * gate is the only thing cited, and it is not.</p>
 *
 * <p>Assumptions: <b>no Checkstyle check may ever be suppressed for anything in this package.</b>
 * {@code config/checkstyle/suppressions.xml} is the only place in the repository where an
 * exemption may be recorded, and it names a mapper package outright as the second of four things
 * that must never be added to it, describing it as the trap most likely to be walked into precisely
 * because the word reads as generated. Its two existing entries reach generated sources under
 * {@code target/} and fixture material under {@code src/test/resources/fixtures/}, and neither
 * touches {@code src/main/java}.</p>
 *
 * <p>Assumptions: there is no in-code bypass either, and this is worth knowing before an hour is
 * spent trying one. {@code config/checkstyle/checkstyle.xml} omits all three of the filters that
 * would make one work -- the warnings filter, the comment filter and the nearby-comment filter --
 * so a {@code @SuppressWarnings} annotation and a {@code // CHECKSTYLE:OFF} comment are both inert
 * here. They will not fail the build and they will not suppress anything; they will simply have no
 * effect, which is the worst of the three outcomes because it reads as a handled exemption. <b>A
 * violation in this package is fixed by writing the missing documentation.</b></p>
 *
 * <p>Assumptions: the gate itself is never weakened, from any direction. Skipping the plugin by
 * property, setting its skip flag in a module, or turning {@code failOnViolation} off all produce
 * the same outcome, and it is strictly worse than having no gate: the build still reports success
 * while no longer enforcing the rule for the code the gate was built for, so a green build becomes
 * evidence of nothing. The suppression file's filter is configured to fail closed for the same
 * reason, and {@code services/pom.xml} records that the setting is relied upon and must not be
 * worked around.</p>
 */
package com.carddemo.batch.mapper;
