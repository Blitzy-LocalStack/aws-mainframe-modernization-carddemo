/**
 * Hand-written anti-corruption layer for the batch bounded context, and the one boundary at which
 * the physical representation of a baseline record is read or written.
 *
 * <h2>Purpose, and the boundary this package is</h2>
 *
 * <p>Assumptions: the package root is {@code com.carddemo.batch}, and {@code mapper} is one of the
 * subpackages that {@code com.carddemo.batch.package-info} fixes and declares closed: {@code job},
 * {@code service}, {@code repository}, {@code domain}, {@code dto}, {@code mapper} and
 * {@code config}. The migration plan designates the anti-corruption layer at its section 0.4.3 as
 * net-new and deliberate rather than as a transcription of anything the baseline had, and it is the
 * same section that gives this package its single defining ruling: <b>this is the only place
 * copybook representation concerns are allowed to appear, and every object downstream of a mapper
 * is clean.</b></p>
 *
 * <p>Read that ruling in both directions, because only one of the two is obvious. Read forwards it
 * is a permission: a type here may know about byte offsets, sign nibbles and padding, and it is the
 * only kind of type in this module that may. Read backwards it is a prohibition with far more
 * reach: nothing in {@code com.carddemo.batch.domain}, {@code com.carddemo.batch.dto},
 * {@code com.carddemo.batch.service}, {@code com.carddemo.batch.job},
 * {@code com.carddemo.batch.repository} or {@code com.carddemo.batch.config} may know any of it. A
 * field width that leaks into a service is not merely misplaced; it makes that service untestable
 * without a byte image, and it puts a second, undocumented copy of a layout into a tree whose whole
 * discipline is that a layout exists once. The sibling charters state the matching half of that
 * boundary from their own side, so the two agree rather than merely coexist.</p>
 *
 * <p>"Representation concerns" is enumerated rather than gestured at, because a vague boundary
 * drifts and a specific one does not. Concretely, and exhaustively, the following belong here and
 * nowhere else in this module:</p>
 *
 * <ul> <li><b>Fixed-width byte offsets and declared record lengths.</b> The zero-based start offset
 * and length of every field, and the record length each field set must reconcile to -- 300 bytes
 * for the account record, 50 for the card cross-reference, the category balance and the disclosure
 * group, 350 for the posted-transaction and daily-transaction records, 430 for the reject stream
 * and 500 for the export record.</li> <li><b>Zoned-decimal sign overpunch.</b> The money regime of
 * every base master, in which the sign is carried in the high nibble of the trailing digit rather
 * than in a byte of its own.</li> <li><b>Packed decimal ({@code COMP-3}) and binary ({@code COMP})
 * storage widths.</b> Both appear, and they appear together in one record:
 * {@code app/cpy/CVEXPORT.cpy} declares {@code COMP-3} amounts at its lines 41, 50, 52 and 71 and
 * {@code COMP} integers at its lines 16, 25, 57, 72, 87, 95 and 96. A width taken from the digit
 * count rather than from the storage rule is wrong for both.</li> <li><b>{@code FILLER}, dropped on
 * decode and padded back on encode.</b> The asymmetry is deliberate and is stated in full below,
 * along with the two cases that invert the intuition.</li> <li><b>The two {@code EXPIRAION}
 * misspellings.</b> The baseline spells the account and card expiration dates without the {@code T}
 * in the third syllable; the target names them differently. This boundary is where the two names
 * meet, and the wording that meeting must use is prescribed below.</li> <li><b>Primary account
 * number masking, card verification value suppression, and encryption of the national and
 * government-issued identifiers.</b> These are transformations of value rather than of layout, and
 * the reason they sit here rather than one layer down is important enough to have its own
 * section.</li> </ul>
 *
 * <h2>Why every mapper here is hand-written, and never generated</h2>
 *
 * <p>The name of this package is misleading in a way worth confronting immediately: in most
 * codebases "mapper" means "generated", and here it means the opposite. Because this package is by
 * design the only place representation concerns surface, every decision that surfaces here is a
 * decision about which of two or more defensible readings of a byte range to take -- whether a
 * trailing region is padding or content, whether a sign lives in a nibble or a byte, whether a name
 * crosses the boundary as written or as renamed, whether a value crosses whole or masked. A code
 * generator emits a method body; it has nowhere to put the sentence explaining why that body reads
 * offset 58 rather than 59. That is why these are written by hand.</p>
 *
 * <p>Alternatives Considered: MapStruct, the obvious candidate for exactly this shape of work, is
 * prohibited outright, and the migration plan records two independent grounds at its section
 * 0.6.1.1. The first is availability: its most recent published release is a beta, which is not a
 * dependency this tree takes. The second is the substantive one and would stand even if a stable
 * release shipped tomorrow -- copybook-to-target mapping is <em>not mechanical</em>. It drops
 * {@code FILLER}, masks the primary account number to its last four digits, suppresses the card
 * verification value entirely, encrypts the national and government-issued identifiers, and renames
 * misspelled baseline fields. Not one of those five is recoverable by a reader from the code that
 * performs it, so each needs its justification at the mapping site, and a generated mapper has
 * nowhere to hold one. Generating these bodies would satisfy the compiler and defeat the only
 * mechanism this repository has for keeping the five decisions reviewable.</p>
 *
 * <p>Alternatives Considered: Lombok is prohibited too, for a reason that has nothing to do with
 * the one above and must not be collapsed into it. The migration plan rejects it at the same
 * section on the ground that generated accessors cannot be documented at all. The sanctioned
 * brevity mechanism is the Java 21 {@code record} with an explicit constructor, which gives the
 * same concision with members that can be documented. Collapsing the two prohibitions into one
 * sentence would lose information, because removing either cause leaves the other prohibition
 * standing on its own.</p>
 *
 * <p>Trade-offs: hand-written mapping is more verbose than either generated alternative, and that
 * cost is accepted rather than disputed. What is bought is a justification at every site where a
 * decision was made; the verbosity is the price of it. What is given up is compile-time coupling
 * between a layout descriptor and the mapper that reads it, so a descriptor edited without its
 * mapper is a runtime geometry failure rather than a compile error. That exposure is mitigated by
 * keeping one descriptor per record in {@code com.carddemo.common.codec.CopybookLayout} rather than
 * by generating the mappers, since generation would remove the compile-time gap only by removing
 * the documentation with it.</p>
 *
 * <p>Alternatives Considered: <b>no abstract base mapper, no generic reflective mapper, and no
 * shared superclass of any kind belongs in this package.</b> This follows from the same reasoning
 * and is stated separately because it is the form the temptation actually takes once the verbosity
 * above becomes visible. Eight mappers that each decode a fixed-width record look like eight
 * instances of one pattern, and factoring the pattern upward is the natural instinct. The migration
 * plan's designation of this layer as hand-written means each decision is justified in place, and a
 * base class is precisely a mechanism for relocating decisions away from the place they apply: the
 * eight justifications would collapse into one paragraph on the superclass that is true of none of
 * the eight in particular. Reflection makes it worse again, because a reflective mapper's field
 * handling is not readable at all from the site that invokes it.</p>
 *
 * <h2>The nine compilation units, and the two mappers that look missing</h2>
 *
 * <p>Assumptions: eight mappers belong here and no ninth; with this charter the directory holds
 * nine compilation units. The migration plan declares this population at its section 0.4.1.2 as
 * {@code mapper/*Mapper.java}, which fixes two things at once: what the members are, and that they
 * sit directly in this directory. <b>This package is a leaf and has no subpackage.</b> A directory
 * beneath this one would need a charter of its own and would have to justify a boundary the plan
 * does not draw, so a mapper that grows large is still one file rather than a subtree.</p>
 *
 * <dl> <dt>{@code package-info.java}</dt> <dd>This charter. It declares no executable type, and its
 * entire value is that the eight mappers are written against the rulings stated here.</dd>
 *
 * <dt>{@code CardXrefRecordMapper}</dt> <dd>The 50-byte card cross-reference of
 * {@code app/cpy/CVACT03Y.cpy}: three fields and a trailing {@code FILLER PIC X(14)} at line 8,
 * reconciling 36 mapped bytes plus 14 of padding to the declared 50.</dd>
 *
 * <dt>{@code AccountRecordMapper}</dt> <dd>The 300-byte account record of
 * {@code app/cpy/CVACT01Y.cpy}, carrying five zoned {@code PIC S9(10)V99} money fields, one of the
 * two {@code EXPIRAION} names at line 11, and a trailing {@code FILLER PIC X(178)} at line 17.</dd>
 *
 * <dt>{@code DisclosureGroupRecordMapper}</dt> <dd>The 50-byte interest-rate lookup of
 * {@code app/cpy/CVTRA02Y.cpy}: a three-part key and one {@code PIC S9(04)V99} rate. The rate is
 * the one number this package handles that is not money and is never summed with any.</dd>
 *
 * <dt>{@code TransactionCategoryBalanceRecordMapper}</dt> <dd>The 50-byte per-account, per-type,
 * per-category running balance of {@code app/cpy/CVTRA01Y.cpy}, whose amount is the narrower
 * {@code PIC S9(09)V99} precision.</dd>
 *
 * <dt>{@code TransactionRecordMapper}</dt> <dd>The 350-byte posted-transaction record of
 * {@code app/cpy/CVTRA05Y.cpy} -- the ledger side of the pair whose two halves are field-for-field
 * identical and differ only by prefix.</dd>
 *
 * <dt>{@code DailyTransactionMapper}</dt> <dd>The 350-byte daily-transaction input record of
 * {@code app/cpy/CVTRA06Y.cpy}, and also the posting field map described below.</dd>
 *
 * <dt>{@code TransactionRejectRecordMapper}</dt> <dd>The 430-byte reject stream: a rejected daily
 * transaction carried whole at its full 350 bytes, followed by a numeric reason and a 76-character
 * description.</dd>
 *
 * <dt>{@code ExportRecordMapper}</dt> <dd>The 500-byte multi-record export layout of
 * {@code app/cpy/CVEXPORT.cpy}, discriminator and all five views.</dd> </dl>
 *
 * <p>Alternatives Considered: {@code DailyTransactionMapper} deliberately owns two things rather
 * than one -- the {@code app/cpy/CVTRA06Y.cpy} 350-byte record representation, and the field map
 * that the {@code 2000-POST-TRANSACTION} paragraph of {@code app/cbl/CBTRN02C.cbl} applies to it.
 * The alternative was a separate posting-map type, and it was rejected on a concrete dependency:
 * {@code TransactionRejectRecordMapper} needs that same 350-byte layout descriptor in order to emit
 * the raw reject image at its original width, so the descriptor has two consumers whichever way the
 * files are cut. Splitting it produces two descriptors for one record, and two descriptors for one
 * record drift -- silently, because each stays internally consistent while they disagree with each
 * other, and a reject image written from the stale one is a file that still opens and still reads.
 * One owner makes the drift impossible rather than merely unlikely.</p>
 *
 * <p>Alternatives Considered: {@code ExportRecordMapper} is one file and not five, even though
 * {@code app/cpy/CVEXPORT.cpy} defines five distinct record shapes over one payload -- the customer
 * view at lines 24 to 42, the account view at 47 to 60, the transaction view at 65 to 79, the card
 * cross-reference view at 84 to 88 and the card view at 93 to 100, each redefining the same
 * {@code EXPORT-RECORD-DATA PIC X(460)} declared at line 19. A file per view is the tidier-looking
 * arrangement and was rejected because Java has no {@code REDEFINES}. In COBOL the five overlays
 * coexist and the program selects among them; in Java the bytes are one array and nothing about
 * them says which view applies. Only the {@code EXPORT-REC-TYPE PIC X(1)} discriminator at
 * {@code app/cpy/CVEXPORT.cpy:10} says that, and it must be read before any of the five views is
 * valid. Five files would each have to duplicate the discriminator read, which puts five copies of
 * one decision in the tree and admits the failure where four are updated and the fifth silently
 * decodes an account view over customer bytes. One file reads the discriminator once and
 * dispatches.</p>
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
 * would mean inventing a batch-side entity that no job in this module reads or writes, which is how
 * a bounded context acquires a table it does not own.</p>
 *
 * <h2>What is imported here, and never re-declared here</h2>
 *
 * <p>Assumptions: the migration plan's transformation rule T2 makes one former {@code COPY}
 * statement become exactly one type import from the single package that owns that contract. For
 * this package the consequence is narrow and absolute: <b>shared representation machinery is
 * imported from {@code com.carddemo.common} and is never re-declared under
 * {@code com.carddemo.batch}.</b> The types are named individually below, because naming them is
 * the mechanism that stops a mapper from writing its own:</p>
 *
 * <ul> <li>{@code com.carddemo.common.codec.CopybookLayout} -- the layout descriptors, together
 * with its nested {@code Kind}, {@code FieldSpec} and {@code RecordSpec}. {@code Kind} is the
 * storage classification a field's width is derived from, {@code FieldSpec} carries one field's
 * name, zero-based start offset, length and kind, and {@code RecordSpec} carries a whole record's
 * field set with its declared and key lengths. This is the single source every geometry decision in
 * this package reads from.</li> <li>{@code com.carddemo.common.codec.FixedWidthCodec} -- record and
 * field encode and decode by offset and length.</li>
 * <li>{@code com.carddemo.common.codec.ZonedDecimalCodec} -- sign-overpunch decode and encode, the
 * money regime of every base master record this package handles.</li>
 * <li>{@code com.carddemo.common.codec.PackedDecimalCodec} -- packed {@code COMP-3} and binary
 * {@code COMP} decode and encode, the regime of the export record.</li>
 * <li>{@code com.carddemo.common.money.Money} -- the fixed-point arithmetic, including the
 * multiply-before-divide ordering the interest formula depends on.</li>
 * <li>{@code com.carddemo.common.money.MoneyModule} -- the serialisation that puts money on a wire
 * as a JSON string.</li> <li>{@code com.carddemo.common.time.TimestampFormatter} -- the exact
 * 26-character target timestamp form.</li> </ul>
 *
 * <p>Assumptions: {@code common-lib} is the Java analogue of compiling every COBOL program with a
 * single copybook include path, so a layout or a codec re-declared per service is the same defect
 * as a copybook duplicated per program -- one artifact, two truths, and nothing that fails when
 * they diverge. That is the whole reason the shared kernel exists, and it is why a mapper that
 * needs a codec behaviour the kernel does not yet offer extends the kernel rather than writing a
 * local variant. This module declares exactly one intra-repository Maven dependency, on
 * {@code common-lib}, so the kernel is reachable and no other service's code is.</p>
 *
 * <p>Assumptions: money is exact fixed point at every hop and is never anything else. The migration
 * plan's transformation rule T3 fixes the representation as {@code NUMERIC(p,2)} in SQL,
 * {@code BigDecimal} at scale 2 in Java, and a JSON string on any wire. <b>{@code float},
 * {@code double}, {@code java.lang.Double} and bare JSON numbers are forbidden in the money
 * path</b>, and the prohibition belongs to the layering rules that the {@code architecture-rules}
 * Surefire execution in {@code services/pom.xml} selects by the simple name
 * {@code LayeringRulesTest}, so it is asserted by a build rather than requested in a review. The
 * specific harm is not abstract: a JSON number is parsed into an IEEE-754 double by most clients,
 * which destroys exactness at precisely the boundary a user reads, and a balance wrong in the cents
 * still looks entirely plausible. Two precisions meet in this package and a mapper has to know
 * which is which -- account-side money is {@code PIC S9(10)V99}, twelve display bytes, for example
 * {@code ACCT-CURR-BAL} at {@code app/cpy/CVACT01Y.cpy:7}, while transaction-side money is
 * {@code PIC S9(09)V99}, eleven display bytes. Decoding the wider field at the narrower scale
 * silently loses its eleventh integer digit on exactly the accounts that have one.</p>
 *
 * <p>Assumptions: <b>no type in this package may import another service's {@code ..domain..}
 * package</b>, and this too is asserted by {@code LayeringRulesTest} rather than left to review.
 * The rule is easy to trip here specifically, because seven of the tables this module's entities
 * map already have an entity in {@code transaction-service} or {@code account-service}, so reaching
 * for one of those looks like reuse. It is not: a compile-time dependency between two independently
 * deployable services means the two must be built, versioned and released together, which
 * reintroduces the coupling bounded contexts exist to remove. This module and those two agree
 * through the physical schema and never through code.</p>
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
 * re-encoded 300-byte account record that is right in its first 122 bytes and 178 bytes short is a
 * failed comparison, not a near miss. The same property is what makes round-tripping meaningful at
 * all: a decode followed by an encode has to reproduce the original bytes exactly, and it cannot if
 * either direction treats the padding as optional.</p>
 *
 * <p>Assumptions: two cases are genuinely not padding, and a mapper that applies the general rule
 * to either will place every subsequent field wrongly.</p>
 *
 * <ul> <li><b>A {@code FILLER} carrying a {@code REDEFINES} clause is an overlay alias and
 * contributes zero bytes.</b> It must not advance the offset. The exemplar is
 * {@code app/cbl/CBTRN02C.cbl:160}, which declares {@code 01 FILLER REDEFINES DB2-FORMAT-TS.} over
 * the {@code PIC X(26)} timestamp declared immediately above it at line 159, and then subdivides
 * that same 26 bytes into fourteen subordinates. The subordinates are a second way of reading bytes
 * that already exist; they are not 26 further bytes. Adding the overlay's width to the running
 * offset double-counts the region and shifts everything after it, and because the record still
 * reaches its declared length the result is a decode that succeeds and is wrong.</li> <li><b>A
 * {@code FILLER} carrying a {@code VALUE} clause is content, not padding.</b> A value clause means
 * the region has a defined content the baseline relies on, so it is carried rather than dropped and
 * is certainly not overwritten with blanks on encode.</li> </ul>
 *
 * <p>Assumptions: a <em>named</em> filler is still padding. The name is a convenience for the COBOL
 * programmer and carries no meaning that survives the boundary; the shared kernel's own layout
 * catalogue makes the same call, treating {@code SEC-USR-FILLER PIC X(23)} as padding despite its
 * having a name. The test a mapper applies is therefore the clause the field carries -- a
 * redefinition, a value, or neither -- and never whether the field happens to be called
 * {@code FILLER}.</p>
 *
 * <h2>Masking belongs to this package, and not to the codec</h2>
 *
 * <p>Assumptions: {@code FixedWidthCodec} does not mask anything, and a mapper that assumes
 * otherwise will ship a full primary account number. The shared kernel is explicit about the
 * division: {@code CopybookLayout.FieldSpec} carries a {@code sensitive} flag, and that flag
 * <em>marks</em> the field and does nothing else. The kernel's own charter states the boundary from
 * its side and assigns primary account number masking to the last four digits, card verification
 * value suppression, and encryption of the national and government-issued identifiers to the mapper
 * layer -- to this package. <b>This charter is the receiving end of that delegation, and there is
 * no third place for the work to have gone.</b></p>
 *
 * <p>Assumptions: the reason the codec declines the job is structural rather than a division of
 * labour that could have gone the other way. A codec that masked could not round-trip. Masking is
 * lossy by definition -- twelve of the sixteen digits are gone, and a suppressed verification value
 * is gone entirely -- so a decode that masked could never be followed by an encode reproducing the
 * original bytes, and byte-identical round-tripping is the property the parity oracle depends on.
 * Masking therefore has to happen strictly after the layout boundary, on the way to a target that
 * is allowed to hold less than the record did. The {@code sensitive} flag exists so that a mapper
 * can find those fields without hard-coding a list of names, which is the part the descriptor can
 * usefully own.</p>
 *
 * <p>Assumptions: one discipline follows immediately and applies to every mapper here. <b>An
 * exception message concerning a sensitive field reports only that field's name, its offset, its
 * length and its kind, and never echoes the raw bytes.</b> The kernel's descriptor already takes
 * this line in its own failure reporting, and it has to hold on this side too, because a decode
 * failure is exactly the moment the tempting thing to log is the input that failed. A diagnostic
 * that quotes the offending bytes of a card number has put a primary account number into a log
 * aggregator, where it long outlives the incident it was meant to explain. Naming the field, the
 * offset, the length and the kind is enough to locate any real geometry defect, so nothing
 * diagnostic is lost by the restriction.</p>
 *
 * <h2>How a divergence from the baseline is worded</h2>
 *
 * <p>Assumptions: this package sits closer to the baseline than any other in the module, so it is
 * where the temptation to describe the migration as an improvement is strongest. The wording is
 * constrained, and the constraint is absolute. Everything under {@code app/**} is reference-only
 * under the migration plan's section 0.2.2: it is read as the specification, it is never modified,
 * and it keeps running unchanged. <b>A comment in this package therefore never says that anything
 * under {@code app/**} was {@code fixed}, {@code corrected}, {@code patched}, {@code remediated} or
 * {@code repaired}.</b> Each of those five would be factually wrong, not merely out of scope --
 * nothing was done to the COBOL at all.</p>
 *
 * <p>The one permitted framing has three parts and always all three: <b>the baseline declares X;
 * the Java implements Y; the divergence is documented in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</b> The third part is what makes it a
 * divergence rather than a discrepancy, so it is never dropped.</p>
 *
 * <p>Two divergences reach this package, and they are named so that a mapper author recognises one
 * on meeting it rather than inventing wording under pressure.</p>
 *
 * <ul> <li><b>The two {@code EXPIRAION} names.</b> {@code app/cpy/CVACT01Y.cpy:11} declares
 * {@code ACCT-EXPIRAION-DATE}, ten bytes, with the {@code T} absent from the third syllable;
 * {@code app/cpy/CVEXPORT.cpy:54} declares {@code EXP-ACCT-EXPIRAION-DATE} and
 * {@code app/cpy/CVEXPORT.cpy:98} declares {@code EXP-CARD-EXPIRAION-DATE} the same way. The target
 * names the field for its ordinary spelling, and the rename is registered in
 * {@code docs/architecture/data-model-and-schema-mapping.md} along with the migration's other
 * renames. Two consequences follow for a mapper. First, the baseline spelling is carried unchanged
 * into every citation of those lines, including citations that sit beside the target name, because
 * a citation whose text has been tidied no longer locates the field it claims to. Second, the
 * rename happens at this boundary and nowhere else: no type outside this package ever sees the
 * baseline spelling, which is what keeps the misspelling from propagating into a column name, a
 * transfer object or an interface.</li> <li><b>The export and import record key.</b>
 * {@code app/cbl/CBEXPORT.cbl:65-69} selects the export file as {@code ORGANIZATION IS INDEXED}
 * with {@code RECORD KEY IS EXPORT-SEQUENCE-NUM}, while the file description at
 * {@code app/cbl/CBEXPORT.cbl:89-92} declares its record as a bare {@code PIC X(500)} -- so the
 * named key is not a field of the record it keys. The name resolves instead to the working-storage
 * copy of {@code app/cpy/CVEXPORT.cpy}, where {@code EXPORT-SEQUENCE-NUM PIC 9(9) COMP} is declared
 * at line 16. {@code app/cbl/CBIMPORT.cbl} declares the mirror image of the same arrangement. The
 * consequence recorded by the reference test suite is that this pair does not compile under the
 * open-source compiler and its round-trip test is skipped, and the suite reports that as a soft
 * warning rather than a failure. {@code ExportRecordMapper} implements the sequence number as a
 * field of the 500-byte record it keys, which is the behaviour the layout supports; the baseline is
 * left exactly as it is, and the divergence is registered in the traceability document. The framing
 * matters here more than anywhere else in this package, because this is the one place where a
 * reader might reasonably suppose the migration had touched the COBOL. It did not.</li> </ul>
 */
package com.carddemo.batch.mapper;
