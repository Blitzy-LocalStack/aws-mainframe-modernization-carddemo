package com.carddemo.common.codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declares the byte geometry of every fixed-width record the migration reads or writes.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class is the single normative source from which every byte offset, every field length and
 * every downstream column type in the migration is derived. {@code FixedWidthCodec} slices records
 * using the descriptors declared here, {@code ZonedDecimalCodec} and {@code PackedDecimalCodec} are
 * told which regime a field is in by the {@link Kind} recorded here, and the loader that stages the
 * reference extracts reads its offsets from the same table. Nothing else in the migrated Java
 * re-derives a record layout, which is the whole point: a layout declared twice is a layout that can
 * disagree with itself.</p>
 *
 * <p>Assumptions: the three sibling class names above are written as plain code spans rather than as
 * links because this class is authored ahead of them and a link to a type that has no file yet does
 * not resolve. The package charter beside this file states the same convention for the same reason.
 * They become links when the files exist.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a static holder that cannot be
 * instantiated, so the type itself accepts no parameter, yields no value and raises nothing. The
 * inapplicability is stated rather than passed over, because user-specified Rule 1 (Explainability)
 * forbids at its line 39 a docstring that omits parameters, return values or purpose, and a reader
 * has to be able to tell a declared inapplicability from an oversight. Every member below carries
 * its own parameter, return and exception at-clauses.</p>
 *
 * <h2>Assumptions: this class is purely structural and depends on no money type</h2>
 *
 * <p>Every component of every descriptor below is geometry or a flag: a name, a zero-based offset, a
 * length, a storage regime, two digit counts, and three booleans. No decimal type, and specifically
 * not this migration's money type from the sibling money package, appears anywhere in this class. The
 * omission is deliberate and is worth stating because the opposite would look natural: the descriptors
 * here describe money fields, so a reader might expect the money type among the imports.</p>
 *
 * <p>Alternatives Considered: importing the money type so that a descriptor could carry the scale or
 * expose a decoded value directly. Rejected on two grounds. It would assert a dependency edge that
 * does not exist -- a layout says where a money field IS and how wide it is, while turning those bytes
 * into an exact decimal is the numeric codecs' work -- and a descriptor able to decode would give the
 * package two decode paths where the whole design intent is one. The digit counts recorded here are
 * what a codec needs to construct the right scale, and they are integers.</p>
 *
 * <p>Assumptions: one consequence is worth naming. The migration forbids IEEE-754 binary arithmetic
 * anywhere in the money path, through both of the language's binary primitive types, both of their
 * wrapper types and a bare JSON number, and that prohibition is asserted by an architecture test
 * rather than by prose. This class satisfies it trivially, because it carries no numeric type beyond
 * the integer offsets and counts a byte layout is made of. Those forbidden type names are described
 * here rather than spelled, following the convention the package charter beside this file states and
 * for the same reason: spelling them would make this file match a search for the very tokens the money
 * path must not contain, and that search is one of the checks this tree is audited with.</p>
 *
 * <h2>Assumptions: what makes this table trustworthy</h2>
 *
 * <p>Every length in the registry was derived by SUMMING the declared field widths of its copybook,
 * and the result was then cross-checked against an independent source. The cross-check is what
 * removes doubt from every offset-dependent decision in this package, so it is recorded here rather
 * than left implicit:</p>
 *
 * <ul>
 *   <li>Summing {@code app/cpy/CVTRA05Y.cpy} places {@code TRAN-CARD-NUM} at zero-based offset 262
 *       and {@code TRAN-PROC-TS} at zero-based offset 304.</li>
 *   <li>{@code app/jcl/TRANREPT.jcl} lines 41 and 42 declare the DFSORT symbol names
 *       {@code TRAN-CARD-NUM,263,16,ZD} and {@code TRAN-PROC-DT,305,10,CH}. DFSORT positions are
 *       ONE-based, so 263 and 305 are 262 and 304 here. The two sources agree exactly.</li>
 *   <li>{@code app/jcl/TRANIDX.jcl} line 27 defines the alternate index key as
 *       {@code KEYS(26 304)}. IDCAMS key operands are ZERO-based, and its two operands are
 *       SPACE-separated in that file while {@code app/jcl/DUSRSECJ.jcl} line 65 writes the same
 *       clause comma-separated as {@code KEYS(8,0)}; both spellings occur in the baseline. That
 *       26-byte key at offset 304 is precisely {@code TRAN-PROC-TS}, so a third source confirms the
 *       same offset.</li>
 *   <li>{@code app/jcl/PRTCATBL.jcl} lines 47 to 50 declare four ONE-based symbol names over the
 *       50-byte category-balance record, {@code TRANCAT-ACCT-ID,1,11,ZD} through
 *       {@code TRAN-CAT-BAL,18,11,ZD}. Converted, those are offsets 0, 11, 13 and 17, which is
 *       field for field what summing {@code app/cpy/CVTRA01Y.cpy} produces.</li>
 * </ul>
 *
 * <p>Assumptions: the conversion between those two coordinate systems runs in exactly one direction
 * and is stated here once because getting it backwards is silent. A JCL or DFSORT position is
 * ONE-based; a {@link FieldSpec#start()} is ZERO-based; the conversion is always
 * {@code zeroBased = oneBased - 1}. Applied the other way it shifts every field by one byte, and a
 * record read one byte out of alignment still decodes to digits, so nothing raises and the numbers
 * are simply wrong.</p>
 *
 * <h2>Alternatives Considered: summing rather than reading the banner</h2>
 *
 * <p>Most of these copybooks carry a comment banner stating their record length, and parsing that
 * banner would be less code than summing the fields. It was rejected because the banner is written
 * four different ways across the corpus and is absent from one file entirely:</p>
 *
 * <ul>
 *   <li>{@code app/cpy/CVACT01Y.cpy} line 2 and {@code app/cpy/CVCUS01Y.cpy} line 2 write
 *       {@code RECLN 300} and {@code RECLN 500}, with NO equals sign.</li>
 *   <li>{@code app/cpy/CVTRA01Y.cpy} line 2 writes {@code RECLN = 50}, with an equals sign and
 *       surrounding spaces.</li>
 *   <li>{@code app/cpy/CVEXPORT.cpy} line 5 uses prose, {@code Total Record Length: 500 bytes}.</li>
 *   <li>{@code app/cpy/CSUSR01Y.cpy} carries NO record-length banner at all. Its first sixteen lines
 *       are an Apache 2.0 licence header, which is why its {@code 01 SEC-USER-DATA.} sits at line 17
 *       rather than line 4 like every other base master.</li>
 * </ul>
 *
 * <p>A banner-parsing strategy would therefore need four dialects and would still fail on the fourth
 * file. Summing needs one rule and never fails, and the banner is retained here only as the
 * corroboration it is fit to be. Every registry entry's declared length equals the sum of its field
 * lengths, and {@link RecordSpec#validateGeometry()} proves that rather than trusting it.</p>
 *
 * <h2>Assumptions: USAGE fixes the physical width, and PICTURE does not</h2>
 *
 * <p>{@code app/cpy/CVEXPORT.cpy} mixes three usages inside one 500-byte record, which makes it the
 * clearest proof in the corpus that a picture clause does not determine how many bytes a field
 * occupies. Three fields of the same picture, in the same record, occupy three different widths:</p>
 *
 * <pre>
 * line  field                          declaration                    bytes
 *   50  EXP-ACCT-CURR-BAL              PIC S9(10)V99 COMP-3               7
 *   51  EXP-ACCT-CREDIT-LIMIT          PIC S9(10)V99                     12
 *   57  EXP-ACCT-CURR-CYC-DEBIT        PIC S9(10)V99 COMP                 8
 * </pre>
 *
 * <p>The three width rules that follow from this are implemented by {@link #zonedWidth(int, int)},
 * {@link #packedWidth(int, int)} and {@link #binaryWidth(int, int)}, and every numeric
 * {@link FieldSpec} is checked against them at construction.</p>
 *
 * <p>Assumptions: the packed width above is SEVEN bytes and not six, and the point is laboured
 * because a six-byte belief is arithmetically impossible and would corrupt every field after it.
 * Twelve digit positions plus one sign nibble is thirteen nibbles, and thirteen nibbles occupy the
 * ceiling of thirteen halved, which is seven. It is proven twice over from files that had no reason
 * to agree. Arithmetically: {@code app/cpy/CVEXPORT.cpy} declares five overlay branches over one
 * 460-byte area, and each one closes at exactly 460 only when a {@code PIC S9(10)V99 COMP-3} field is
 * seven bytes. Its account branch, lines 48 to 60, sums to 108 bytes of named fields plus a 352-byte
 * trailing pad; at six bytes for each of its two packed amounts that sum would be 106 and the branch
 * would close at 458, which the 460-byte area does not admit. By contradiction:
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} sums to exactly 200 bytes only at seven.
 * Any statement that {@code PIC S9(10)V99 COMP-3} is six bytes is defective and must not be
 * propagated.</p>
 *
 * <h2>Trade-offs: FILLER is retained here and dropped later</h2>
 *
 * <p>{@code FILLER} is declared as a real field in every layout below rather than omitted. That costs
 * a descriptor entry per record that no domain object will ever carry, and it is what makes the two
 * invariants in {@link RecordSpec#validateGeometry()} expressible at all: fields cannot be proven
 * contiguous, and their lengths cannot be proven to sum to the declared record length, if the padding
 * between and after them is not described. It is also what lets {@code FixedWidthCodec} pad correctly
 * on encode, which matters because the parity oracle compares output byte for byte after timestamp
 * normalisation, so a re-encoded record differing only in its padding is a failed comparison.</p>
 *
 * <p>Dropping {@code FILLER} is therefore deferred to the domain projection, and AAP Rule T1
 * (Copybook is normative) requires that the drop be RECORDED per record. This class is where that
 * record lives: the trailing pad of each base master is named in the table under
 * {@link #layout(String)} and is present in the field list, so a reader can see exactly which bytes
 * the domain object does not carry.</p>
 *
 * <p>Assumptions: two constructs spelled {@code FILLER} are NOT padding, and treating them as padding
 * is how a layout silently gains bytes it does not have.</p>
 *
 * <ol>
 *   <li>{@code FILLER REDEFINES <name>} is an overlay alias and must NOT advance the offset. It
 *       occurs at {@code app/cbl/COCRDLIC.cbl} line 254 as {@code 10 FILLER REDEFINES WS-ALL-ROWS.},
 *       at {@code app/cbl/CBTRN02C.cbl} line 160 and again at {@code app/cbl/CBACT04C.cbl} line 151,
 *       both as {@code 01 FILLER REDEFINES DB2-FORMAT-TS.}, so it is a recurring construct rather than
 *       a single oddity. The first is arithmetically self-proving: the redefined field is
 *       {@code PIC X(196)} at line 253 and the overlay is seven occurrences of an
 *       eleven, sixteen and one byte triple, which is seven times 28, which is 196. Counted as
 *       padding it would add a second 196 bytes that the record does not contain.</li>
 *   <li>{@code FILLER} carrying a {@code VALUE} is CONTENT, not padding, because the literal IS the
 *       data. {@code app/cpy/CVTRA07Y.cpy} declares 22 {@code FILLER} items and all 22 carry a
 *       {@code VALUE}, so in that file the proportion is not most of them but every one of them.
 *       Those literals are the column headings and rule lines of the 133-column report.</li>
 * </ol>
 *
 * <p>Assumptions: the converse case also occurs, and it is the reason this class judges padding by
 * role rather than by name. {@code app/cpy/CSUSR01Y.cpy} line 23 declares
 * {@code 05 SEC-USR-FILLER PIC X(23).} which IS padding despite not being spelled {@code FILLER},
 * and it is declared below under its own name because AAP Rule T1 (Copybook is normative) permits no
 * renaming beyond the three documented misspellings.</p>
 *
 * <h2>Assumptions: the three baseline misspellings, and the fact that nothing else is renamed</h2>
 *
 * <p>Three field names in the reference baseline are misspelled. The names are carried into this
 * class EXACTLY as the baseline declares them, because a descriptor whose names did not match the
 * copybook would no longer be a transcription of it. Only the target column and entity names spell
 * these three correctly, one layer further out, and the correspondence is recorded here so the lineage
 * is never ambiguous:</p>
 *
 * <pre>
 * baseline name                declared at                          target name
 * ACCT-EXPIRAION-DATE          app/cpy/CVACT01Y.cpy line 11         expiration_date
 * CARD-EXPIRAION-DATE          app/cpy/CVACT02Y.cpy line 9          expiration_date
 * PA-MERCHANT-CATAGORY-CODE    CIPAUDTY.cpy line 36                 merchant_category_code
 * </pre>
 *
 * <p>Assumptions: the third of those has two spellings in the baseline and both are recorded so a
 * search for either one finds this note. The bare form {@code PA-MERCHANT-CATAGORY-CODE} appears in
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} at line 36, and a second form carrying an
 * {@code -RQ-} infix, {@code PA-RQ-MERCHANT-CATAGORY-CODE}, appears in
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy} at line 28. The first two misspellings
 * also recur twice inside {@code app/cpy/CVEXPORT.cpy}, at its lines 54 and 98. Per AAP Rule T1
 * (Copybook is normative) NO OTHER field is renamed anywhere in the migration.</p>
 *
 * <h2>Assumptions: what {@code sensitive} means, and what it does not</h2>
 *
 * <p>A field marked {@link FieldSpec#sensitive()} is one whose raw bytes must never reach a log line
 * or an exception message. The flag MARKS the field and does nothing else. Masking a primary account
 * number to its last four digits, suppressing a card verification value from a response, and
 * encrypting a national or government identifier at rest all belong to the anti-corruption mapper
 * layer at {@code services/*}{@code /mapper/*Mapper.java}, not here. Stating that boundary matters
 * because a reader who assumed the flag masked anything would ship an endpoint that returns a full
 * account number.</p>
 *
 * <p>The fields marked sensitive below are the primary account number wherever it appears, the card
 * verification value, the embossed name, the customer name, address, telephone, date of birth and
 * electronic-transfer account, the eight-character password the baseline stores in clear at
 * {@code app/cpy/CSUSR01Y.cpy} line 21, and the two identifiers the target stores encrypted:
 * {@code CUST-SSN} at {@code app/cpy/CVCUS01Y.cpy} line 17 and {@code CUST-GOVT-ISSUED-ID} at line 18
 * of the same file.</p>
 *
 * <p>Assumptions: the two IMS authorization segments are classified by a different MECHANISM and the
 * distinction is deliberate. Every record above marks its sensitive fields at their own declaration
 * sites, which is a DENYLIST -- a new field is disclosable until somebody marks it. The two
 * authorization segments instead declare their fields plainly and then run them through
 * {@link #closeAuthorizationDisclosure(List)}, which withholds everything
 * {@link #AUTHORIZATION_DISCLOSABLE_FIELDS} does not name, so a new field there is withheld until
 * somebody names it. Sixteen fields across the two segments are consequently withheld -- fifteen of
 * them beyond the primary account number this paragraph's list already covers -- and they are not
 * enumerated here, because enumerating them would be a second statement of a set that has one
 * authoritative statement.</p>
 *
 * <p>Trade-offs: two mechanisms in one class is a cost, and it is paid for one reason. The
 * authorization segments are the only records whose content is ALSO decoded by a second class in this
 * package, {@link CsvAuthCodec}, over the request and reply payloads; a denylist let the two disagree,
 * and they did. The remaining records have exactly one decoder each, so their per-site marking has
 * nothing to disagree with. Converting every record to an allowlist was considered and rejected as a
 * far larger change to records the review did not fault, and one that would move fifteen records'
 * classifications away from the declaration sites where they read most clearly.</p>
 *
 * <h2>Alternatives Considered: a flat field list rather than a group tree</h2>
 *
 * <p>Several of these copybooks nest their leading fields inside a group item, and that group is
 * frequently the record key. {@code app/cpy/CVTRA01Y.cpy} line 5 declares {@code TRAN-CAT-KEY} as a
 * 17-byte group of three subordinates, {@code app/cpy/CVTRA02Y.cpy} line 5 declares
 * {@code DIS-GROUP-KEY} as a 16-byte group, {@code app/cpy/COSTM01.CPY} line 21 declares
 * {@code TRNX-KEY} as a 32-byte group, and
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} line 19 declares
 * {@code PA-AUTHORIZATION-KEY} as an eight-byte group of two packed fields that decomposes downstream
 * into two integer columns forming a composite primary key.</p>
 *
 * <p>A nested tree of group nodes was considered and rejected. Every consumer of this class works in
 * flat byte offsets, so a tree would force each of them to re-flatten it before use, and the
 * re-flattening is exactly the step where two consumers can disagree. Group items are therefore
 * FLATTENED to their leaf elementary fields in {@link RecordSpec#fields()}, and the composite-key
 * geometry that the group carried is preserved in {@link RecordSpec#keyLength()} and
 * {@link RecordSpec#keyOffset()} instead, where it remains addressable as a whole. The reference
 * codec at {@code tests/helpers/record_codec.py} reaches the same conclusion independently: its
 * layout field list is flat and its composite keys are expressed through a key length.</p>
 *
 * <h2>Assumptions: layouts are scoped per copybook, never globally by field name</h2>
 *
 * <p>A global field-name map is impossible for this corpus, and the proof is a single name.
 * {@code TRAN-CAT-KEY} exists in two copybooks with two different widths: at
 * {@code app/cpy/CVTRA01Y.cpy} line 5 it is 17 bytes, being an eleven-byte account identifier plus a
 * two-byte type code plus a four-byte category code, with subordinates named {@code TRANCAT-}
 * something; at {@code app/cpy/CVTRA04Y.cpy} line 5 it is 6 bytes, being only the type and category
 * codes, with subordinates named {@code TRAN-TYPE-CD} and {@code TRAN-CAT-CD}. The two widths are
 * independently corroborated by the dataset definitions, which declare {@code KEYS(17 0)} at
 * {@code app/jcl/TCATBALF.jcl} line 40 and {@code KEYS(6 0)} at {@code app/jcl/TRANCATG.jcl} line 40.
 * Those same two subordinate names appear a third time, at {@code app/cpy/CVTRA05Y.cpy} lines 6 and
 * 7, at a different level number. A name therefore resolves only WITHIN a named layout, which is why
 * {@link RecordSpec#field(String)} is an instance method and there is no registry-wide field
 * lookup.</p>
 *
 * <p>Assumptions: that collision stays observable through this class's own API, in two places, and a
 * reader checking the claim should know which to look at. Because group items are flattened, the
 * colliding GROUP name is not itself a field here; its two widths survive as the key length of the two
 * records, so {@code layout("TCATBAL").keyLength()} is 17 while {@code layout("TRANCAT").keyLength()}
 * is 6. The collision is also live at field level: {@code TRAN-TYPE-CD} and {@code TRAN-CAT-CD} are
 * declared in both the category reference and the posted transaction record, at offsets 0 and 2 in the
 * first and 16 and 18 in the second, so resolving either name against the wrong layout yields the
 * wrong offset rather than an error. That is precisely why no registry-wide lookup exists.</p>
 *
 * <h2>Trade-offs: layouts are declared in Java rather than loaded from a resource</h2>
 *
 * <p>These tables could have been externalised to a resource file parsed at startup, which would let
 * a layout change without recompiling. That was rejected on two grounds. This module is a library
 * rather than a deployable and deliberately ships no resource directory at all, so a classpath
 * resource would be a new kind of artifact introduced for one table; and a resource is parsed at run
 * time, which converts a transcription error from a compilation failure into a startup failure in
 * whichever service happens to load first. Declaring the tables in Java costs real verbosity, which
 * is the compromise accepted, and buys compile-time type safety plus zero classpath input or output
 * during startup.</p>
 *
 * <h2>Assumptions: the copybook hazards this class is shaped by</h2>
 *
 * <p>The reference copybooks are not uniform, and the irregularities below were each confirmed
 * against the file cited. They are recorded here because this class is the authoritative home of that
 * knowledge: anyone extending the registry, or writing a scanner that proposes new entries for it,
 * has to survive all of them.</p>
 *
 * <ol>
 *   <li><b>A copybook may declare MANY {@code 01} levels, so stopping at the first one loses the
 *       rest.</b> {@code app/cpy/CVTRA07Y.cpy} declares SEVEN, at lines 4, 15, 33, 48, 50, 56 and 62,
 *       whose group lengths are 115, 114, 114, 133, 112, 112 and 112 bytes. A parser that stops at
 *       the first silently loses six. {@code app/cpy/CSLKPCDY.cpy} declares three across 1318
 *       lines.</li>
 *   <li><b>A {@code 01} may be ELEMENTARY, with its own picture and no subordinates.</b>
 *       {@code app/cpy/CVTRA07Y.cpy} line 48 is
 *       {@code 01 TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'.}, which also fixes the 133-column
 *       report width. Code assuming every {@code 01} is a group breaks on it.</li>
 *   <li><b>A copybook may have NO {@code 01} level at all.</b> The four message and segment fragments
 *       {@code CCPAURQY}, {@code CCPAURLY}, {@code CIPAUSMY} and {@code CIPAUDTY} all begin at level
 *       {@code 05}, being fragments meant to be copied under a group the caller supplies, and
 *       {@code app/cpy/CSUTLDWY.cpy} begins at level {@code 10}. A layout name must therefore be
 *       something a caller can supply, which is why {@link RecordSpec#name()} is a parameter rather
 *       than something read out of the source.</li>
 *   <li><b>A copybook may contain no data items whatsoever.</b> {@code app/cpy/CSSETATY.cpy} is 30
 *       lines of procedural statements with no picture clause, no {@code 01} and no condition name;
 *       it is a substitution template carrying {@code (TESTVAR1)}, {@code (SCRNVAR2)} and
 *       {@code (MAPNAME3)} placeholders, which are NOT field names. Handed that file, a scanner must
 *       yield an EMPTY layout and not an error.</li>
 *   <li><b>A {@code 01} may be indented past area A, which defeats a column-anchored search.</b>
 *       {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy} line 19 indents
 *       {@code 01 ERROR-LOG-RECORD.} by ten spaces, and {@code app/cbl/CSUTLDTC.cbl} line 60 does the
 *       same, so it is a pattern rather than a single slip. Leading whitespace before a level number
 *       must be tolerated in any quantity.</li>
 *   <li><b>Level numbers are neither monotonic nor a predictable ladder.</b> Both
 *       {@code 01/05/10/15/20/25}
 *       and {@code 01/02/03/04} occur, {@code app/cbl/CBTRN02C.cbl} line 161 uses {@code 06}, and
 *       {@code app/cbl/COCRDLIC.cbl} line 252 REGRESSES to {@code 05 WS-SCREEN-DATA.} after a
 *       {@code 10} level, giving the sequence 01, 10, 15 then 05. A level stack is required, and an
 *       assumed ladder is not.</li>
 *   <li><b>{@code OCCURS} appears at both group and elementary level, and ignoring its multiplicity
 *       loses bytes.</b> {@code app/cpy/CVEXPORT.cpy} lines 29 and 30 repeat a 50-byte group three
 *       times and its lines 34 and 35 repeat a 15-byte group twice; counted once each rather than
 *       three and two times, that one branch loses 100 plus 15 bytes and no longer closes against its
 *       460-byte area. {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} line 22 puts
 *       {@code OCCURS 5 TIMES} on an elementary two-byte field, ten bytes in all, which becomes five
 *       discrete columns downstream rather than an array so that the arity of five is enforced by the
 *       schema itself.</li>
 *   <li><b>Condition names sit in places a layout reader must skip past.</b> They are mis-indented at
 *       {@code app/cbl/COCRDLIC.cbl} lines 247 and 248, they precede their own subordinate levels at
 *       {@code app/cpy/CSUTLDWY.cpy} lines 44 and 45, their value sets span continuation lines at
 *       lines 58 to 85 of that same file, and they mix quoted with bare literals across the corpus in
 *       every form from a single character to a hexadecimal string. One of them,
 *       {@code WS-EDIT-DATE-IS-INVALID}, is declared on a GROUP item at
 *       {@code app/cpy/CSUTLDWY.cpy} line 19 and read at {@code app/cpy/CSUTLDPY.cpy} line 274, so
 *       its value spans the subordinates beneath it.</li>
 *   <li><b>Columns 1 to 6 and 73 to 80 are not part of the source and must be stripped.</b>
 *       {@code app/cpy/CSMSG02Y.cpy} carries legacy sequence numbers on its first four lines and none
 *       thereafter; {@code app/cpy/CVCRD01Y.cpy} carries a DUPLICATE sequence number, {@code 004800}
 *       appearing on both line 40 and line 42, so duplicates must be tolerated rather than treated as
 *       an index. In the identification area, {@code app/cbl/CBTRN02C.cbl} lines 161 to 165 carry the
 *       stray characters {@code E}, {@code M} and {@code D} past column 72, which become spurious
 *       tokens if that area is read.</li>
 *   <li><b>An asterisk in column 7 is a comment, and comments inflate any raw match count.</b>
 *       {@code app/cpy/CVCRD01Y.cpy} has eight commented-out declarations, at lines 20, 22, 25, 26,
 *       27, 31, 32 and 33. In {@code app/cpy/CVEXPORT.cpy} a raw search for {@code REDEFINES} returns
 *       seven where only six are clauses, the seventh being the prose at line 7, and a raw search for
 *       {@code COMP-3} returns five where only four are usage clauses, the fifth being line 6.</li>
 *   <li><b>Indentation is variable and tokens must be split on whitespace RUNS.</b>
 *       {@code app/cpy/CVACT03Y.cpy} line 4 indents its {@code 01} by one space where
 *       {@code app/cpy/CVACT01Y.cpy} line 4 uses two, and {@code app/cpy/CVTRA04Y.cpy} line 6 puts
 *       two spaces after its level number.</li>
 *   <li><b>A picture clause may be split from its value, spelled in mixed case, written without a
 *       repeat count, or continued onto the next line.</b> Picture and value are on separate lines at
 *       {@code app/cpy/CSMSG01Y.cpy} lines 18 to 21; the keyword is lower case at
 *       {@code app/cpy/CSUTLDWY.cpy} line 68, which reads {@code Pic 9(4)}; a bare {@code PIC X} with
 *       no parenthesised count appears at {@code app/cpy/CVTRA07Y.cpy} line 44 and means one
 *       character; {@code VALUE ALL} appears four times in that same file, at lines 48, 53, 59 and
 *       65, and expands to the field width; and a dozen of its literals continue onto the following
 *       line, so continuations must be joined before tokenising or a literal is truncated in
 *       silence.</li>
 *   <li><b>{@code REDEFINES} on an ordinary named field is equally an overlay, and equally does not
 *       advance the offset.</b> {@code app/cpy/CVCRD01Y.cpy} redefines character fields as numeric at
 *       its lines 36, 39 and 42, adding eleven, sixteen and nine bytes to a naive sum, which is 36
 *       bytes the record does not contain. {@code app/cpy/CSUTLDWY.cpy} contains NINE such clauses,
 *       at lines 7, 12, 14, 17, 26, 35, 40, 62 and 67; a count of thirteen has been asserted for that
 *       file and is wrong.</li>
 *   <li><b>One reference file that looks like a copybook is not one.</b>
 *       {@code app/cbl/CSUTLDTC.cbl} is a dynamically called subprogram, built as a shared module
 *       rather than an executable, and it lives under {@code app/cbl} rather than {@code app/cpy}.
 *       Its line 86 declares an 80-byte diagnostic result, which is a fourth message width alongside
 *       the 50, 72 and 75-byte regimes and is easily mistaken for one of them.</li>
 * </ol>
 *
 * <h2>Assumptions: the reference codec this class is modelled on</h2>
 *
 * <p>{@code tests/helpers/record_codec.py} is the parity oracle's own codec and the direct design
 * source for the shape of this class. Its field descriptor carries the same nine attributes in the
 * same order, its layout descriptor binds a name to a record length, a key length, a key offset and
 * an ordered field list, and its import-time self-check asserts the same two invariants
 * {@link RecordSpec#validateGeometry()} asserts here. Where this class differs from it, the
 * difference is documented at the point where it occurs. That file is reference material: it is read
 * and cited throughout this class and is never modified.</p>
 *
 * <p>Two differences are worth stating up front because they are visible in this class's public
 * shape. The reference kind is three-valued and this one has five constants, for the reason given on
 * {@link Kind}. And the reference layout descriptor carries a sixth attribute for alternate index
 * keys, modelling for instance the account-identifier index the interest program reads the
 * cross-reference file by; {@link RecordSpec} carries the primary key only, because the target
 * expresses a secondary access path as a database index declared in a schema migration rather than as
 * a property of a record layout.</p>
 */
public final class CopybookLayout {

    /**
     * Prevents instantiation of this static registry holder.
     *
     * <p>Alternatives Considered: an instantiable class, or an injectable singleton, was evaluated
     * and rejected. The registry is a compile-time constant table with no state to configure and no
     * lifecycle, so an instance would advertise both and provide neither. A private constructor
     * states that in the one place the language enforces it, and the class is final so no subclass
     * can reopen the decision.</p>
     */
    private CopybookLayout() {
        // WHY : Alternatives Considered: throwing from this body was evaluated and judged noise.
        //       The private modifier already makes the only call site that could reach it impossible
        //       to write, so an exception here would document a state the compiler forbids outright.
    }

    /**
     * Names the physical storage regime of one field, which is what determines how its bytes decode.
     *
     * <p>A field's regime is a property of its declared USAGE and not of its picture clause, as the
     * three-width proof on the enclosing class establishes. The regime is therefore knowable
     * statically for every field in the corpus, which is why it is recorded once here rather than
     * guessed at decode time.</p>
     *
     * <p>Alternatives Considered: narrowing this to the three values the reference codec uses. The
     * field descriptor in {@code tests/helpers/record_codec.py} admits only text, unsigned display
     * integer and zoned decimal, and for its purposes that is complete: searching all eleven
     * base-master copybooks for a packed, binary or repeating clause returns nothing at all, so those
     * eleven records are entirely zoned and the oracle never needs a fourth value. This class serves
     * two further record families the oracle does not, the export record at
     * {@code app/cpy/CVEXPORT.cpy} and the two authorization segments at
     * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} and
     * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy}, and both families store money packed
     * and identifiers binary. Narrowing to three would leave the export record and the whole
     * authorization bounded context unrepresentable, so the superset is deliberate rather than
     * speculative.</p>
     */
    public enum Kind {

        // WHY : Assumptions: character data, the default when no usage clause is present. It is the
        //       most common regime in the corpus by a wide margin, exemplified by
        //       05  CARD-NUM  PIC X(16). at app/cpy/CVACT02Y.cpy line 5.
        TEXT,

        // WHY : Assumptions: an UNSIGNED display integer, PIC 9(n) with no S and no usage clause, one
        //       printable digit per byte. It is kept distinct from ZONED because it carries no sign
        //       overpunch at all, so its low-order byte is an ordinary digit character rather than one
        //       that has to be folded. app/cpy/CVACT01Y.cpy line 5 declares ACCT-ID PIC 9(11) this
        //       way, and the reference codec draws the same distinction.
        UINT,

        // WHY : Assumptions: a SIGNED display decimal, PIC S9(n)V99 with no usage clause, one
        //       printable digit per byte with the sign folded into the low-order digit as an
        //       overpunch. This is the money regime of all eleven base masters, exemplified by
        //       05  ACCT-CURR-BAL  PIC S9(10)V99. at app/cpy/CVACT01Y.cpy line 7. The overpunch
        //       consumes no byte of its own, which is why zonedWidth is the digit count exactly.
        ZONED,

        // WHY : Assumptions: packed decimal, USAGE COMP-3, two digits per byte with the sign in the
        //       low-order nibble of the last byte. It reaches this class through three record layouts
        //       and no others: app/cpy/CVEXPORT.cpy declares four such fields, at its lines 41, 50, 52
        //       and 71, and the two authorization segment copybooks use it for money throughout. The
        //       authorization bounded context is consequently the only place packed decimal reaches
        //       persisted target data; for the export record the nibbles are decoded at the loader
        //       edge and never stored.
        PACKED,

        // WHY : Assumptions: binary, USAGE COMP, whose synonym spelling in the language is the word
        //       this constant is named for. Its width is a power-of-two byte count driven by the digit
        //       count rather than one byte per digit, which is why it needs a rule of its own rather
        //       than sharing PACKED's. app/cpy/CVEXPORT.cpy declares seven such fields, at its lines
        //       16, 25, 57, 72, 87, 95 and 96, and CIPAUSMY.cpy declares two.
        BINARY
    }

    /**
     * Reports a layout or field declaration that violates the geometry contract of this class.
     *
     * <p>Alternatives Considered: reusing the platform's own argument exception unqualified, or
     * declaring a checked exception. An unqualified argument exception was rejected because a caller
     * cannot then distinguish a malformed layout from any other rejected argument, and this exception
     * is the one signal that a transcription of a copybook is wrong, which is the most consequential
     * failure this package has. A checked exception was rejected because every construction site is a
     * constant declaration in a static initialiser: there is no recovery available at such a site, and
     * a checked exception would only force a catch block that could do nothing but rethrow. It extends
     * the platform argument exception so that a caller who reasonably catches that broader type still
     * catches this one.</p>
     *
     * <p>Assumptions: the message a caller sees for a field marked
     * {@link CopybookLayout.FieldSpec#sensitive()} names only that field's name, offset, length and
     * kind, and never its content. The reference codec at {@code tests/helpers/record_codec.py}
     * includes the offending raw value in its own decode failure message, which suits a test harness
     * reading committed fixtures; this class deliberately does not, because the same message here can
     * reach a production log carrying a primary account number. The divergence is documented rather
     * than silent, and it is a difference in what is reported and not in what is rejected.</p>
     */
    public static final class LayoutException extends IllegalArgumentException {

        // WHY : Assumptions: the platform requires a serial version identifier on every serialisable
        //       type, and this type inherits serialisability from the exception hierarchy. Declaring
        //       it explicitly pins the value rather than letting the compiler derive one that changes
        //       whenever a member is added, which is what makes a serialised instance readable across
        //       builds.
        private static final long serialVersionUID = 1L;

        /**
         * Creates an exception describing one violated geometry constraint.
         *
         * @param message the diagnostic text, which names the record or field, the offending value
         *     and the constraint it breached; it must never contain the raw content of a field marked
         *     sensitive
         */
        LayoutException(String message) {
            super(message);
        }
    }

    /**
     * Classifies a registered layout by where its geometry comes from.
     *
     * <p>Assumptions: this distinction has to be modelled rather than inferred, because the two
     * populations it separates are both eleven strong and are NOT the same eleven, and conflating them
     * is how three records go missing without anyone noticing. The reference codec at
     * {@code tests/helpers/record_codec.py} registers eleven layouts, at its lines 1059, 1086, 1118,
     * 1138, 1157, 1174, 1194, 1227, 1257, 1302 and 1333. The migration has eleven base-master
     * records. Of those eleven base masters only EIGHT appear in the reference registry; the three
     * that do not are the transaction type at {@code app/cpy/CVTRA03Y.cpy}, the transaction category
     * at {@code app/cpy/CVTRA04Y.cpy} and the user security record at {@code app/cpy/CSUSR01Y.cpy}.
     * The reference registry makes up its count with three entries that are not base masters at all
     * but are DERIVED from them. Both the count of eight and the count of eleven are correct at
     * different levels, and the registry here carries all fourteen names with each one labelled, so
     * neither count can be mistaken for the other.</p>
     */
    public enum Provenance {

        // WHY : Assumptions: a record transcribed directly from a copybook that defines a persistent
        //       dataset. There are exactly eleven, one per base master, and they are the population
        //       AAP Rule T1 (Copybook is normative) speaks about.
        BASE_MASTER,

        // WHY : Assumptions: a record whose geometry is built from a base master rather than
        //       transcribed independently, either by appending fields to it or by altering one
        //       field's flags. There are exactly three. Deriving them is what keeps the shared prefix
        //       single-sourced, as recorded on extendWith and withFieldFlags below.
        DERIVED,

        // WHY : Assumptions: a record transcribed from a copybook that defines a persistent IMS
        //       DL/I segment rather than a VSAM dataset. There are exactly two, the parent and the
        //       child of the authorization extension's database.
        // WHY : Alternatives Considered: labelling these two BASE_MASTER, which is the closer of the
        //       two existing values because both are copybook-transcribed and both are persistent.
        //       Rejected because baseMasterNames() is consumed as the population that has a shipped
        //       flat extract to load and a byte count to divide by -- app/data/EBCDIC holds one file
        //       per VSAM master and none for either segment, since the segments are loaded from a
        //       generation data set by the extension's own load program rather than from a seed
        //       extract. Folding them in would have put two records with no extract into the group
        //       whose defining obligation is to agree with one.
        // WHY : Alternatives Considered: labelling them DERIVED, on the ground that they are simply
        //       not base masters. Rejected because DERIVED states something specific and false about
        //       them: a derived record's geometry is BUILT from a base master's, which is why
        //       extendWith and withFieldFlags exist, whereas these two are transcribed from their own
        //       copybooks and share no prefix with any other record here.
        IMS_SEGMENT
    }

    // WHY : Assumptions: eighteen is the largest number of digit positions the reference dialect
    //       admits in a picture clause, so a declaration beyond it is a transcription error rather
    //       than an unusual field. The widest declaration actually present in the corpus is twelve,
    //       PIC S9(10)V99, which appears both zoned and packed inside app/cpy/CVEXPORT.cpy.
    private static final int MAX_DIGITS = 18;

    // WHY : Assumptions: the three binary width tiers are a step function of the digit count, not an
    //       arithmetic expression of it, so the boundaries are named rather than computed. Four digits
    //       or fewer occupy two bytes, five through nine occupy four, and ten through eighteen occupy
    //       eight. app/cpy/CVEXPORT.cpy exercises the first two tiers at its line 96, PIC 9(03) COMP
    //       at two bytes, and its line 25, PIC 9(09) COMP at four; its line 87 exercises the third,
    //       PIC 9(11) COMP at eight bytes, and that eight is what lets its cross-reference branch at
    //       lines 84 to 88 close at exactly 460 rather than 456.
    private static final int BINARY_HALFWORD_MAX_DIGITS = 4;

    // WHY : Assumptions: the upper bound of the middle binary tier, kept as a named constant beside
    //       its sibling so the two boundaries of the step function are read together rather than one
    //       being a literal buried in a comparison.
    private static final int BINARY_FULLWORD_MAX_DIGITS = 9;

    /**
     * Returns the byte width of a zoned display field carrying the given digit positions.
     *
     * <p>Assumptions: a zoned field stores one printable digit per byte and folds its sign into the
     * low-order digit as an overpunch, so the sign consumes no byte of its own and the width is the
     * digit count exactly. That is why {@code PIC S9(09)V99} is eleven bytes and
     * {@code PIC S9(10)V99} is twelve, and it is confirmed independently by
     * {@code app/jcl/PRTCATBL.jcl} line 50, whose symbol name {@code TRAN-CAT-BAL,18,11,ZD} declares
     * eleven bytes for the {@code PIC S9(09)V99} field at {@code app/cpy/CVTRA01Y.cpy} line 9.</p>
     *
     * <p>Trade-offs: this method takes the two digit counts separately rather than their sum, which is
     * marginally more to pass at every call site. It is what lets the caller state the picture clause
     * as the copybook writes it, so a reader comparing a call against
     * {@code PIC S9(09)V99} sees nine and two rather than a pre-added eleven whose derivation they
     * would have to reconstruct.</p>
     *
     * @param intDigits the number of digit positions before the implied decimal point; zero or more
     * @param decDigits the number of digit positions after the implied decimal point; zero or more
     * @return the physical byte width of the field
     * @throws LayoutException if either count is negative, if both are zero, or if their sum exceeds
     *     the eighteen digit positions the reference dialect admits
     */
    public static int zonedWidth(int intDigits, int decDigits) {
        return requireDigits(intDigits, decDigits, Kind.ZONED);
    }

    /**
     * Returns the byte width of a packed decimal field carrying the given digit positions.
     *
     * <p>Assumptions: a packed field stores two digits per byte and reserves the low-order nibble of
     * its last byte for the sign, so its width is the ceiling of one more than the digit count,
     * halved. The ladder this produces is 3 digits to 2 bytes, 5 to 3, 9 to 5, 11 to 6 and 12 to 7,
     * and each rung is present in the corpus: three and five digits at
     * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} lines 20 and 21, nine and eleven at
     * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy}, and twelve at
     * {@code app/cpy/CVEXPORT.cpy} line 50.</p>
     *
     * <p>Assumptions: the twelve-digit rung is SEVEN bytes and not six, and this method is the one
     * place that arithmetic lives so that no caller can assert otherwise. The proof is given in full
     * on the enclosing class: five overlay branches in {@code app/cpy/CVEXPORT.cpy} each close at
     * exactly 460 bytes only at seven, and the segment at
     * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} sums to exactly 200 only at seven.</p>
     *
     * @param intDigits the number of digit positions before the implied decimal point; zero or more
     * @param decDigits the number of digit positions after the implied decimal point; zero or more
     * @return the physical byte width of the field
     * @throws LayoutException if either count is negative, if both are zero, or if their sum exceeds
     *     the eighteen digit positions the reference dialect admits
     */
    public static int packedWidth(int intDigits, int decDigits) {
        int digits = requireDigits(intDigits, decDigits, Kind.PACKED);

        // WHY : Assumptions: adding two before halving is integer arithmetic for the ceiling of one
        //       more than the digit count halved, and it is written this way rather than with the
        //       library ceiling function because that function operates on the binary approximate
        //       types this whole package is barred from touching. Adding one and halving would floor
        //       instead of ceiling and would return six for twelve digits, which is precisely the
        //       defective figure the class documentation refutes.
        return (digits + 2) / 2;
    }

    /**
     * Returns the byte width of a binary field carrying the given digit positions.
     *
     * <p>Assumptions: a binary field occupies a whole machine unit chosen by its digit count rather
     * than one byte per digit, so its width is a step function with the two boundaries named on the
     * constants above: two bytes to four digits, four bytes to nine, and eight bytes to eighteen. All
     * three tiers are exercised inside {@code app/cpy/CVEXPORT.cpy}, at its lines 96, 25 and 87
     * respectively, and the eight-byte tier is load-bearing there: the cross-reference branch at its
     * lines 84 to 88 closes at exactly 460 bytes only because its eleven-digit binary field is eight
     * bytes wide.</p>
     *
     * @param intDigits the number of digit positions before the implied decimal point; zero or more
     * @param decDigits the number of digit positions after the implied decimal point; zero or more
     * @return the physical byte width of the field
     * @throws LayoutException if either count is negative, if both are zero, or if their sum exceeds
     *     the eighteen digit positions the reference dialect admits
     */
    public static int binaryWidth(int intDigits, int decDigits) {
        int digits = requireDigits(intDigits, decDigits, Kind.BINARY);
        if (digits <= BINARY_HALFWORD_MAX_DIGITS) {
            return 2;
        }
        if (digits <= BINARY_FULLWORD_MAX_DIGITS) {
            return 4;
        }
        return 8;
    }

    /**
     * Returns the byte width a numeric field of the given kind and digit positions must occupy.
     *
     * <p>Assumptions: this dispatcher exists so that the width rule is applied from exactly one place
     * during field validation, rather than each construction site choosing which of the three rules
     * applies. A field whose kind and digit counts are known therefore has exactly one admissible
     * width, and {@link FieldSpec} rejects any other.</p>
     *
     * @param kind the storage regime of the field; must be one of the three numeric kinds
     * @param intDigits the number of digit positions before the implied decimal point; zero or more
     * @param decDigits the number of digit positions after the implied decimal point; zero or more
     * @return the physical byte width the field must occupy for that kind and digit count
     * @throws LayoutException if {@code kind} is null, if {@code kind} is not a numeric kind, if
     *     either digit count is negative, if both are zero, or if their sum exceeds eighteen
     */
    public static int widthOf(Kind kind, int intDigits, int decDigits) {
        if (kind == null) {
            throw new LayoutException("kind must not be null when deriving a numeric field width");
        }
        return switch (kind) {
            case ZONED -> zonedWidth(intDigits, decDigits);
            case PACKED -> packedWidth(intDigits, decDigits);
            case BINARY -> binaryWidth(intDigits, decDigits);

            // WHY : Assumptions: the two non-numeric kinds have no digit positions at all, so asking
            //       for a width derived from digit counts is a caller error rather than a value this
            //       method could compute. Rejecting is what keeps the numeric-width contract total:
            //       every kind this method accepts has exactly one admissible width, and the two it
            //       refuses take their width from the declared character count instead.
            case TEXT, UINT -> throw new LayoutException(
                    "width of a " + kind + " field is its declared character count, not a digit-derived"
                            + " width; only ZONED, PACKED and BINARY widths are derived from digits");
        };
    }

    /**
     * Validates a digit-position pair and returns their sum.
     *
     * <p>Assumptions: every numeric width rule needs the same three guarantees before it can compute
     * anything, so they are asserted once here rather than three times over. A negative count is a
     * transcription error; a pair that sums to zero describes a numeric field with no digits, which no
     * picture clause can express; and a sum beyond eighteen exceeds what the reference dialect admits,
     * so it is a transcription error too rather than an unusually wide field.</p>
     *
     * @param intDigits the number of digit positions before the implied decimal point
     * @param decDigits the number of digit positions after the implied decimal point
     * @param kind the storage regime being validated, named in the failure message so a caller can
     *     tell which of the three width rules rejected the pair
     * @return the total number of digit positions, being the sum of the two arguments
     * @throws LayoutException if either count is negative, if both are zero, or if their sum exceeds
     *     the eighteen digit positions the reference dialect admits
     */
    private static int requireDigits(int intDigits, int decDigits, Kind kind) {
        if (intDigits < 0 || decDigits < 0) {
            throw new LayoutException("digit positions of a " + kind + " field must not be negative,"
                    + " but intDigits=" + intDigits + " and decDigits=" + decDigits);
        }
        int digits = intDigits + decDigits;
        if (digits == 0) {
            throw new LayoutException("a " + kind + " field must declare at least one digit position,"
                    + " but intDigits and decDigits are both zero");
        }
        if (digits > MAX_DIGITS) {
            throw new LayoutException("a " + kind + " field must not declare more than " + MAX_DIGITS
                    + " digit positions, but intDigits=" + intDigits + " and decDigits=" + decDigits
                    + " sum to " + digits);
        }
        return digits;
    }

    /**
     * Describes one elementary field within a fixed-width record.
     *
     * <p>The nine components below are the nine attributes the reference field descriptor at
     * {@code tests/helpers/record_codec.py} carries, in the same order, so that a reader holding both
     * open reads them as one contract rather than two. Assumptions: the record is immutable because a
     * layout is a constant transcribed from a copybook, and the registry is a static table every
     * service reads; a mutable descriptor would let one caller alter the geometry every other caller
     * sees.</p>
     *
     * <p>Assumptions: {@code start} is ZERO-based and {@link #end()} is EXCLUSIVE, so the two bound a
     * half-open interval and a field occupies {@code [start, end)}. That convention is chosen because
     * it is the one the platform's own subrange operations take, so no call site has to adjust an
     * index before slicing. It is the opposite of the ONE-based, inclusive convention the reference
     * JCL uses, and the conversion is always to subtract one, as recorded on the enclosing class.</p>
     *
     * @param name the field name exactly as the copybook declares it, upper case with hyphens
     *     retained and any baseline misspelling preserved; never null and never blank
     * @param start the ZERO-based byte offset of the field from the start of the record; zero or more
     * @param length the physical byte width of the field, which for a numeric kind must equal the
     *     width its digit counts imply; one or more
     * @param kind the storage regime that determines how the field's bytes decode; never null
     * @param intDigits the number of digit positions before the implied decimal point, or zero for a
     *     non-numeric field
     * @param decDigits the number of digit positions after the implied decimal point introduced by a
     *     {@code V}, or zero for a non-numeric field
     * @param signed whether the picture clause carries a leading {@code S}, and therefore whether the
     *     field carries a sign at all
     * @param normalizeTs whether the field holds a run-generated timestamp that the parity comparison
     *     blanks before comparing; true only for the wall-clock processing stamps
     * @param sensitive whether the field's raw bytes must be kept out of every log line and exception
     *     message; the flag marks the field and performs no masking itself
     */
    public record FieldSpec(
            String name,
            int start,
            int length,
            Kind kind,
            int intDigits,
            int decDigits,
            boolean signed,
            boolean normalizeTs,
            boolean sensitive) {

        /**
         * Validates the nine components and rejects any declaration the geometry contract forbids.
         *
         * <p>Alternatives Considered: validating with the language's assertion statement rather than
         * by throwing. Assertions are disabled unless the virtual machine is started with them
         * enabled, so an assertion-based check would hold during a test run and then silently vanish
         * in the deployment where a wrong offset actually costs money. Throwing survives every
         * configuration. The reference codec reaches the same conclusion for the same reason, and
         * records it at {@code tests/helpers/record_codec.py} line 1710: its self-check raises rather
         * than asserting so that it still fires when the interpreter is asked to strip
         * assertions.</p>
         *
         * <p>Assumptions: for a field marked {@code sensitive} the failure message names only the
         * field name, the offset, the length and the kind. It never echoes content. Every value this
         * constructor could complain about is geometry rather than content, so the restriction costs
         * nothing here and is stated so that a later contributor adding a content check does not
         * quietly widen what a message can carry.</p>
         *
         * @param name the field name exactly as the copybook declares it
         * @param start the ZERO-based byte offset of the field from the start of the record
         * @param length the physical byte width of the field
         * @param kind the storage regime that determines how the field's bytes decode
         * @param intDigits the number of digit positions before the implied decimal point
         * @param decDigits the number of digit positions after the implied decimal point
         * @param signed whether the picture clause carries a leading {@code S}
         * @param normalizeTs whether the field holds a run-generated timestamp
         * @param sensitive whether the field's raw bytes must be kept out of diagnostics
         * @throws LayoutException if the name is null or blank, if the kind is null, if the offset is
         *     negative, if the length is below one, if either digit count is negative, if a
         *     non-numeric field declares digit positions, or if a numeric field's length disagrees
         *     with the width its kind and digit counts imply
         */
        public FieldSpec {
            if (name == null || name.isBlank()) {
                throw new LayoutException("a field name must be neither null nor blank, but was "
                        + (name == null ? "null" : "blank"));
            }
            if (kind == null) {
                throw new LayoutException("field " + name + " must declare a kind, but kind was null");
            }
            if (start < 0) {
                throw new LayoutException("field " + name + " must start at a non-negative offset,"
                        + " but start=" + start);
            }
            if (length < 1) {
                throw new LayoutException("field " + name + " must occupy at least one byte,"
                        + " but length=" + length);
            }
            if (intDigits < 0 || decDigits < 0) {
                throw new LayoutException("field " + name + " must not declare negative digit"
                        + " positions, but intDigits=" + intDigits + " and decDigits=" + decDigits);
            }

            // WHY : Assumptions: a character or unsigned-display field takes its width from the
            //       declared character count and has no implied decimal point, so digit counts on one
            //       would be a second, competing statement of its width. Rejecting them keeps exactly
            //       one source of width per kind. The unsigned-display kind is included in this
            //       restriction deliberately: PIC 9(11) has eleven digit positions in the copybook,
            //       but its byte width is already the length and recording the eleven twice is what
            //       would let the two disagree. The digit counts of such a field are recoverable from
            //       its length precisely because the two are equal.
            if ((kind == Kind.TEXT || kind == Kind.UINT) && (intDigits != 0 || decDigits != 0)) {
                throw new LayoutException("field " + name + " of kind " + kind + " must leave both"
                        + " digit counts at zero because its width is its declared character count,"
                        + " but intDigits=" + intDigits + " and decDigits=" + decDigits);
            }

            // WHY : Assumptions: for the three numeric kinds the declared length and the digit counts
            //       are two statements of the same fact, and this is the check that makes them agree.
            //       It is the mechanical guard against the defect the class documentation refutes at
            //       length: a twelve-digit packed field declared six bytes wide is rejected here
            //       rather than silently shifting every field after it by one byte, and a
            //       one-byte shift still decodes to digits, so nothing downstream would have raised.
            if (kind == Kind.ZONED || kind == Kind.PACKED || kind == Kind.BINARY) {
                int implied = widthOf(kind, intDigits, decDigits);
                if (implied != length) {
                    throw new LayoutException("field " + name + " of kind " + kind + " declares"
                            + " intDigits=" + intDigits + " and decDigits=" + decDigits
                            + ", which imply a width of " + implied + " bytes, but length=" + length);
                }
            }
        }

        /**
         * Returns the EXCLUSIVE zero-based end offset of this field, being its start plus its length.
         *
         * @return the offset one byte past the last byte of this field
         */
        public int end() {
            return start + length;
        }

        /**
         * Returns a copy of this field with the two diagnostic flags replaced.
         *
         * <p>Assumptions: this is the per-field half of the derivation mechanism described on
         * {@link RecordSpec#withFieldFlags(String, String, boolean, boolean)}. Only the two flags are
         * replaceable, and deliberately not the name, offset, length or kind: those four are the
         * geometry, and a derived record that altered them would no longer share a prefix with the
         * record it derives from, which is the entire property the derivation exists to preserve.</p>
         *
         * @param newNormalizeTs whether the copy holds a run-generated timestamp the parity
         *     comparison blanks before comparing
         * @param newSensitive whether the copy's raw bytes must be kept out of diagnostics
         * @return a new field with identical geometry and the two given flags
         * @throws LayoutException never in practice, since the geometry carried over from this
         *     instance was validated when this instance was constructed; it remains declared because
         *     the constructor this method calls declares it
         */
        public FieldSpec withFlags(boolean newNormalizeTs, boolean newSensitive) {
            return new FieldSpec(name, start, length, kind, intDigits, decDigits, signed,
                    newNormalizeTs, newSensitive);
        }

        /**
         * Returns a copy of this field relocated to a new start offset.
         *
         * <p>Assumptions: relocation is needed because a derived record appends a field list to a
         * prefix, and an appended field's offset is only knowable once the prefix length is known.
         * Everything except the offset is carried over, so a relocated field cannot silently change
         * width or regime while moving.</p>
         *
         * @param newStart the ZERO-based byte offset the copy starts at; zero or more
         * @return a new field identical to this one but starting at the given offset
         * @throws LayoutException if {@code newStart} is negative
         */
        public FieldSpec relocatedTo(int newStart) {
            return new FieldSpec(name, newStart, length, kind, intDigits, decDigits, signed,
                    normalizeTs, sensitive);
        }

        /**
         * Describes this field for a diagnostic message without disclosing its content.
         *
         * <p>Assumptions: this rendering names the field, its half-open byte interval and its kind,
         * and nothing else. It is what a caller uses when reporting a failure against a field that
         * may be marked sensitive, so that the report is actionable without a primary account number
         * reaching a log line. The record's generated rendering is unsuitable for that use because it
         * would print every component, and while none of the nine is content today, the generated
         * form would silently begin printing content the moment a component was added.</p>
         *
         * @return a short description naming the field, its start, its exclusive end and its kind
         */
        public String describe() {
            return name + "[" + start + "," + end() + ") " + kind;
        }
    }

    /**
     * Declares a character field of the given declared width.
     *
     * <p>Trade-offs: the eight factories below exist instead of calling the nine-argument canonical
     * constructor at every declaration site. They cost eight extra members on this class, and they buy
     * two things the constructor cannot. A declaration reads as its picture clause does, so
     * {@code text("ACCT-OPEN-DATE", 48, 10)} is checkable against
     * {@code 05  ACCT-OPEN-DATE  PIC X(10).} at a glance rather than by counting positional
     * arguments. And for the two kinds whose width is least obvious the factory DERIVES the width
     * from the digit counts rather than accepting it, which is why the correction the class
     * documentation argues for cannot be got wrong at a call site that uses one.</p>
     *
     * @param name the field name exactly as the copybook declares it
     * @param start the ZERO-based byte offset of the field from the start of the record
     * @param length the declared character count, which for a character field is its byte width
     * @return a character field descriptor carrying neither timestamp normalisation nor sensitivity
     * @throws LayoutException if the name is null or blank, the offset is negative, or the length is
     *     below one
     */
    public static FieldSpec text(String name, int start, int length) {
        return new FieldSpec(name, start, length, Kind.TEXT, 0, 0, false, false, false);
    }

    /**
     * Declares a character field whose raw bytes must be kept out of every diagnostic.
     *
     * <p>Assumptions: the sensitivity flag marks the field and masks nothing, as recorded on the
     * enclosing class. A separate factory rather than a boolean argument is used because a boolean at
     * a call site does not say what it selects, whereas the method name does, and the population it
     * selects is exactly the set of fields an audit has to be able to enumerate.</p>
     *
     * @param name the field name exactly as the copybook declares it
     * @param start the ZERO-based byte offset of the field from the start of the record
     * @param length the declared character count, which for a character field is its byte width
     * @return a character field descriptor marked sensitive
     * @throws LayoutException if the name is null or blank, the offset is negative, or the length is
     *     below one
     */
    public static FieldSpec sensitiveText(String name, int start, int length) {
        return new FieldSpec(name, start, length, Kind.TEXT, 0, 0, false, false, true);
    }

    /**
     * Declares a character field holding a run-generated timestamp the parity comparison blanks.
     *
     * <p>Assumptions: only the wall-clock PROCESSING stamp is normalised, and the ORIGINATING stamp
     * deliberately is not. The originating stamp is copied from the source transaction and is
     * therefore deterministic business data that the comparison must verify, whereas the processing
     * stamp is read from the clock during the run and would make every comparison fail. The reference
     * codec draws the same line and records the same reason at
     * {@code tests/helpers/record_codec.py}. The one exception is the interest-transaction record,
     * where the baseline writes the run clock into BOTH stamps, and that exception is expressed as a
     * derived layout rather than by weakening this rule.</p>
     *
     * @param name the field name exactly as the copybook declares it
     * @param start the ZERO-based byte offset of the field from the start of the record
     * @param length the declared character count, which for the timestamp contract is twenty-six
     * @return a character field descriptor marked for timestamp normalisation
     * @throws LayoutException if the name is null or blank, the offset is negative, or the length is
     *     below one
     */
    public static FieldSpec normalizedTimestamp(String name, int start, int length) {
        return new FieldSpec(name, start, length, Kind.TEXT, 0, 0, false, true, false);
    }

    /**
     * Declares an unsigned display integer field of the given digit count.
     *
     * <p>Assumptions: an unsigned display field stores one printable digit per byte and carries no
     * sign at all, so its byte width equals its digit count and the two are not recorded separately.
     * The digit counts on the descriptor stay at zero for exactly that reason, which the canonical
     * constructor enforces.</p>
     *
     * @param name the field name exactly as the copybook declares it
     * @param start the ZERO-based byte offset of the field from the start of the record
     * @param digits the number of digit positions, which is also the byte width
     * @return an unsigned display integer field descriptor
     * @throws LayoutException if the name is null or blank, the offset is negative, or the digit count
     *     is below one
     */
    public static FieldSpec uint(String name, int start, int digits) {
        return new FieldSpec(name, start, digits, Kind.UINT, 0, 0, false, false, false);
    }

    /**
     * Declares an unsigned display integer field whose raw bytes must be kept out of diagnostics.
     *
     * <p>Assumptions: two fields in the corpus are both numeric and identifying, so neither of the
     * two existing factories fits them alone. They are the national identifier at
     * {@code app/cpy/CVCUS01Y.cpy} line 17, declared {@code PIC 9(09)}, and the card verification
     * value at {@code app/cpy/CVACT02Y.cpy} line 7, declared {@code PIC 9(03)}. The target stores the
     * first encrypted and never returns the second from any endpoint, and both decisions belong to
     * layers outside this package; this factory only records that the bytes are identifying.</p>
     *
     * @param name the field name exactly as the copybook declares it
     * @param start the ZERO-based byte offset of the field from the start of the record
     * @param digits the number of digit positions, which is also the byte width
     * @return an unsigned display integer field descriptor marked sensitive
     * @throws LayoutException if the name is null or blank, the offset is negative, or the digit count
     *     is below one
     */
    public static FieldSpec sensitiveUint(String name, int start, int digits) {
        return new FieldSpec(name, start, digits, Kind.UINT, 0, 0, false, false, true);
    }

    /**
     * Declares a signed zoned display field of the given digit positions, deriving its byte width.
     *
     * <p>Assumptions: every money field in all eleven base masters is signed zoned display, and every
     * one of them declares a leading {@code S}, so this factory fixes the sign rather than accepting
     * it. An unsigned zoned field does not occur in the corpus, and an unsigned display integer is
     * served by {@link #uint(String, int, int)} instead. The width comes from
     * {@link #zonedWidth(int, int)} rather than from an argument, so a call site states the picture
     * clause and the width follows from it.</p>
     *
     * @param name the field name exactly as the copybook declares it
     * @param start the ZERO-based byte offset of the field from the start of the record
     * @param intDigits the number of digit positions before the implied decimal point
     * @param decDigits the number of digit positions after the implied decimal point
     * @return a signed zoned display field descriptor whose length is the sum of the digit counts
     * @throws LayoutException if the name is null or blank, the offset is negative, either digit count
     *     is negative, both are zero, or their sum exceeds eighteen
     */
    public static FieldSpec signedZoned(String name, int start, int intDigits, int decDigits) {
        return new FieldSpec(name, start, zonedWidth(intDigits, decDigits), Kind.ZONED,
                intDigits, decDigits, true, false, false);
    }

    /**
     * Declares a packed decimal field of the given digit positions, deriving its byte width.
     *
     * <p>Assumptions: this factory is where the packed width correction is enforced rather than merely
     * described. It derives the width from {@link #packedWidth(int, int)}, so a twelve-digit packed
     * amount is seven bytes at every call site and a caller cannot assert six. That matters because
     * the six-byte figure has been asserted in the past and is arithmetically impossible, as the proof
     * on the enclosing class shows.</p>
     *
     * @param name the field name exactly as the copybook declares it
     * @param start the ZERO-based byte offset of the field from the start of the record
     * @param intDigits the number of digit positions before the implied decimal point
     * @param decDigits the number of digit positions after the implied decimal point
     * @param signed whether the picture clause carries a leading {@code S}
     * @return a packed decimal field descriptor whose length is the ceiling of one more than the
     *     digit count, halved
     * @throws LayoutException if the name is null or blank, the offset is negative, either digit count
     *     is negative, both are zero, or their sum exceeds eighteen
     */
    public static FieldSpec packed(String name, int start, int intDigits, int decDigits,
            boolean signed) {
        return new FieldSpec(name, start, packedWidth(intDigits, decDigits), Kind.PACKED,
                intDigits, decDigits, signed, false, false);
    }

    /**
     * Declares a binary field of the given digit positions, deriving its byte width.
     *
     * <p>Assumptions: the width comes from {@link #binaryWidth(int, int)}, whose step function is the
     * one place the three tier boundaries live. A call site that passed a width instead would be
     * asserting which tier its digit count falls in, and the tiers are exactly what a reader is least
     * likely to recall correctly.</p>
     *
     * @param name the field name exactly as the copybook declares it
     * @param start the ZERO-based byte offset of the field from the start of the record
     * @param intDigits the number of digit positions before the implied decimal point
     * @param decDigits the number of digit positions after the implied decimal point
     * @param signed whether the picture clause carries a leading {@code S}
     * @return a binary field descriptor whose length is the machine unit its digit count selects
     * @throws LayoutException if the name is null or blank, the offset is negative, either digit count
     *     is negative, both are zero, or their sum exceeds eighteen
     */
    public static FieldSpec binary(String name, int start, int intDigits, int decDigits,
            boolean signed) {
        return new FieldSpec(name, start, binaryWidth(intDigits, decDigits), Kind.BINARY,
                intDigits, decDigits, signed, false, false);
    }

    /**
     * Describes one whole fixed-width record as an ordered, contiguous list of its fields.
     *
     * <p>Assumptions: the key length is carried alongside the record length because both are needed
     * and neither implies the other. The reference codec resolves both by name for the same reason,
     * offering one accessor for the record length and a companion for the key length at
     * {@code tests/helpers/record_codec.py} line 1364 and immediately after it, and its import-time
     * self-check at line 1684 validates a table of record-length and key-length PAIRS rather than
     * lengths alone. A descriptor carrying only the record length could not reproduce the dataset
     * definitions the reference JCL declares, each of which states both, as
     * {@code app/jcl/TCATBALF.jcl} lines 40 and 41 do with {@code KEYS(17 0)} and
     * {@code RECORDSIZE(50 50)}; nor could it express the alternate index key
     * {@code KEYS(26 304)} that {@code app/jcl/TRANIDX.jcl} line 27 declares, whose whole content is a
     * length and an offset.</p>
     *
     * <p>Assumptions: group items are FLATTENED into {@code fields} and the composite key they carried
     * survives as {@code keyLength} and {@code keyOffset}, for the reason argued on the enclosing
     * class. A key is therefore addressable as one interval without any consumer having to know that
     * the copybook happened to bracket its leading fields.</p>
     *
     * <p>Assumptions: a record may declare NO RETRIEVAL KEY, and it says so by carrying
     * {@link #NO_RETRIEVAL_KEY} as its key length. Not every fixed-width record in this migration is
     * a keyed data set: a print file and a statement file are sequential output, declared with a
     * record format and no key operand at all, and nothing ever reads a band of one by key.</p>
     *
     * <p>Refactoring Rationale: the key length was originally required to be one or more, which forced
     * every keyless descriptor to INVENT a key -- the report bands declared one nominal byte at offset
     * zero and the statement bands declared the whole eighty-character line -- so the metadata read as
     * a retrieval contract that does not exist, and read differently on two record families that are
     * keyless for the identical reason. No output byte depended on the invention, which is precisely
     * what made it dangerous: a consumer that started trusting {@code keyLength} would have extracted
     * a key from a print line and been given a plausible answer. Admitting a keyless value makes the
     * absence expressible, so a descriptor states what is true and {@link #hasRetrievalKey()} lets a
     * consumer ask.</p>
     *
     * @param name the registry key for this record, which is a logical name rather than a copybook
     *     name because two records may share a copybook geometry without being the same record; never
     *     null and never blank
     * @param reclen the total declared record length in bytes, which must equal the sum of the field
     *     lengths; one or more
     * @param keyLength the byte width of the primary key, taken from the dataset definition that
     *     declares it; one or more for a keyed record, or exactly {@link #NO_RETRIEVAL_KEY} for a
     *     record that has no retrieval key at all
     * @param keyOffset the ZERO-based byte offset at which the primary key begins, which must be zero
     *     when {@code keyLength} is {@link #NO_RETRIEVAL_KEY}; zero or more
     * @param fields the ordered field descriptors covering the record from offset zero with no gap and
     *     no overlap; never null and never empty
     */
    public record RecordSpec(
            String name,
            int reclen,
            int keyLength,
            int keyOffset,
            List<FieldSpec> fields) {

        /**
         * Validates the five components and takes an unmodifiable copy of the field list.
         *
         * <p>Trade-offs: the field list is defensively copied, which costs one allocation per
         * construction. In exchange the registry, which is a static table read by every service in the
         * migration, cannot be mutated through a reference a caller retained or handed in. That is not
         * a theoretical concern for a shared constant: a single removal from a shared list would change
         * the geometry every subsequent decode uses, and the copy is what makes the table a constant in
         * fact rather than by convention. The copy also rejects a null element, so a list containing
         * one fails here rather than at first use.</p>
         *
         * <p>Alternatives Considered: performing the full geometry validation in this constructor
         * rather than in {@link #validateGeometry()}. It was rejected so that the derivation helpers
         * can build an intermediate record and then prove it, and so that the invariant has a named,
         * independently callable home a test can aim at directly. The registry static initialiser
         * calls {@link #validateGeometry()} on every entry it holds, so every layout this class ships
         * is proven at class-load time; what this constructor guarantees on its own is the per-component
         * sanity that no amount of later checking could recover from. The reference codec makes the same
         * split, validating its assembled registry once at import rather than inside its layout
         * constructor.</p>
         *
         * @param name the registry key for this record
         * @param reclen the total declared record length in bytes
         * @param keyLength the byte width of the primary key, or {@link #NO_RETRIEVAL_KEY} for a
         *     keyless record
         * @param keyOffset the ZERO-based byte offset at which the primary key begins, which must be
         *     zero for a keyless record
         * @param fields the ordered field descriptors covering the record
         * @throws LayoutException if the name is null or blank, the record length is below one, the key
         *     length is negative, the key offset is negative, a keyless record declares a non-zero key
         *     offset, the key runs past the end of the record, or the field list is null, empty or
         *     contains a null element
         */
        public RecordSpec {
            if (name == null || name.isBlank()) {
                throw new LayoutException("a record name must be neither null nor blank, but was "
                        + (name == null ? "null" : "blank"));
            }
            if (reclen < 1) {
                throw new LayoutException("record " + name + " must declare a length of at least one"
                        + " byte, but reclen=" + reclen);
            }

            // WHY : Assumptions: a NEGATIVE key length is still rejected while zero is now admitted,
            //       and the boundary matters. Zero is the one value that can only mean "this record has
            //       no retrieval key", because a key of no bytes cannot be extracted and no dataset
            //       definition declares one; a negative length has no reading at all and is a
            //       arithmetic slip at the call site. Admitting zero without still refusing negatives
            //       would let a transposed argument pair through as a keyless declaration.
            if (keyLength < NO_RETRIEVAL_KEY) {
                throw new LayoutException("record " + name + " must declare a key length of at least"
                        + " " + NO_RETRIEVAL_KEY + ", which denotes a keyless record, but keyLength="
                        + keyLength);
            }
            if (keyOffset < 0) {
                throw new LayoutException("record " + name + " must declare a non-negative key"
                        + " offset, but keyOffset=" + keyOffset);
            }

            // WHY : Assumptions: a keyless record must declare offset zero, because an offset is a
            //       position WITHIN a key and there is no key to be positioned in. Leaving the offset
            //       free would admit two spellings of the same fact -- keyless at zero and keyless at
            //       304 -- and a consumer comparing two descriptors for the same record would then find
            //       them unequal over a component that means nothing in either.
            if (keyLength == NO_RETRIEVAL_KEY && keyOffset != 0) {
                throw new LayoutException("record " + name + " declares no retrieval key, so its key"
                        + " offset must be 0 rather than " + keyOffset + "; an offset positions a key"
                        + " and there is no key here to position");
            }

            // WHY : Assumptions: a key that runs past the end of the record cannot be extracted at
            //       all, so it is rejected here rather than at the first read. The reference codec
            //       performs the identical containment check on both the primary and the alternate key
            //       at tests/helpers/record_codec.py line 1738, and for the same reason: a malformed
            //       key builds a malformed index rather than raising.
            if (keyOffset + keyLength > reclen) {
                throw new LayoutException("record " + name + " declares a key of " + keyLength
                        + " bytes at offset " + keyOffset + ", which ends at "
                        + (keyOffset + keyLength) + " and exceeds its declared length of " + reclen);
            }
            if (fields == null || fields.isEmpty()) {
                throw new LayoutException("record " + name + " must declare at least one field, but"
                        + " the field list was " + (fields == null ? "null" : "empty"));
            }
            fields = List.copyOf(fields);
        }

        /**
         * The key length that denotes a record with no retrieval key, zero.
         *
         * <p>Assumptions: zero rather than a negative sentinel or a boxed absent value, because zero is
         * the one number whose only possible reading here is "no key": a key of no bytes cannot be
         * extracted, and no dataset definition in the reference tree declares one. A negative sentinel
         * would be indistinguishable from an arithmetic slip, and a boxed absent value would change the
         * record's component types, which the parity assertion on the component list pins.</p>
         */
        public static final int NO_RETRIEVAL_KEY = 0;

        /**
         * Declares a keyless record, so that no call site has to spell the sentinel or an offset.
         *
         * <p>Assumptions: this factory exists so that a keyless declaration reads as one at the call
         * site. The canonical constructor takes the key length and the key offset as two adjacent
         * integer arguments, and passing {@code 0, 0} through it states the absence twice while looking
         * exactly like a transposition; naming the factory states it once and states it in words.</p>
         *
         * <p>Trade-offs: the geometry check is deliberately NOT run here, so a caller still calls
         * {@link #validateGeometry()} exactly as it would on a keyed record. Running it inside the
         * factory would leave two construction paths with different guarantees, and a reader comparing
         * a keyed declaration against a keyless one beside it would have to know which path validated
         * what.</p>
         *
         * @param name the registry key for this record; never null and never blank
         * @param reclen the total declared record length in bytes; one or more
         * @param fields the ordered field descriptors covering the record from offset zero with no gap
         *     and no overlap; never null and never empty
         * @return a record descriptor declaring no retrieval key, never {@code null}
         * @throws LayoutException if the name is null or blank, the record length is below one, or the
         *     field list is null, empty or contains a null element
         */
        public static RecordSpec keyless(String name, int reclen, List<FieldSpec> fields) {
            return new RecordSpec(name, reclen, NO_RETRIEVAL_KEY, 0, fields);
        }

        /**
         * Reports whether this record declares a retrieval key at all.
         *
         * <p>Assumptions: a consumer asks this rather than comparing {@link #keyLength()} against zero,
         * so that the meaning of zero lives in one place. A consumer that extracted a key without
         * asking would, on a keyless record, be handed an empty interval and no indication that the
         * descriptor never claimed to have one.</p>
         *
         * @return {@code true} when this record declares a key of one or more bytes, {@code false} when
         *     it declares {@link #NO_RETRIEVAL_KEY}
         */
        public boolean hasRetrievalKey() {
            return keyLength != NO_RETRIEVAL_KEY;
        }

        /**
         * Returns the field with the given name, which resolves only within this record.
         *
         * <p>Assumptions: lookup is scoped to one record and there is deliberately no registry-wide
         * equivalent, because a field name is not unique across the corpus. The name
         * {@code TRAN-CAT-KEY} is 17 bytes in one copybook and 6 in another, as the enclosing class
         * documents with both dataset definitions that corroborate it, so a global map would have to
         * pick one of the two and would then be wrong for every reader of the other.</p>
         *
         * @param fieldName the field name to resolve, matched exactly as the copybook declares it
         * @return the matching field descriptor
         * @throws LayoutException if this record declares no field of that name, in which case the
         *     message names this record and the requested field so the caller can see which of the two
         *     is wrong
         */
        public FieldSpec field(String fieldName) {
            for (FieldSpec candidate : fields) {
                if (candidate.name().equals(fieldName)) {
                    return candidate;
                }
            }
            throw new LayoutException("record " + name + " declares no field named " + fieldName
                    + "; a field name resolves only within one record, never across the registry");
        }

        /**
         * Proves that this record's fields tile it exactly, and throws if they do not.
         *
         * <p>Assumptions: two invariants are asserted, and both are the reference codec's own. Fields
         * must be CONTIGUOUS from offset zero, meaning each field starts exactly where the previous one
         * ended, which forbids both a gap and an overlap in one condition. And their lengths must sum
         * to the declared record length EXACTLY, which forbids a record that is described short or
         * long. Together they are what make a declared length a derived fact rather than an assertion,
         * which is the property the enclosing class relies on when it declines to parse the record
         * length banner.</p>
         *
         * <p>Alternatives Considered: expressing these as assertion statements. Rejected for the
         * reason recorded on the field constructor: assertions are stripped unless the virtual machine
         * is started with them enabled, so the invariant would hold during a test run and vanish in the
         * deployment where a misaligned record actually costs money. Throwing holds under every
         * configuration.</p>
         *
         * <p>Assumptions: this method reports the FIRST breach it finds rather than collecting every
         * breach. One wrong length shifts every field after it, so a collected report would name a
         * cascade of consequences and leave the reader to identify the cause; naming the first
         * divergence names the cause.</p>
         *
         * @return this record, so that a registry declaration can validate and bind in one expression
         * @throws LayoutException if any field starts anywhere other than where the previous field
         *     ended, or if the field lengths do not sum to the declared record length
         */
        public RecordSpec validateGeometry() {
            int cursor = 0;
            for (FieldSpec candidate : fields) {
                if (candidate.start() != cursor) {
                    throw new LayoutException("record " + name + " field " + candidate.describe()
                            + " starts at " + candidate.start() + " but the preceding fields end at "
                            + cursor + "; fields must be contiguous from offset zero, so this is"
                            + (candidate.start() > cursor ? " a gap" : " an overlap"));
                }
                cursor += candidate.length();
            }
            if (cursor != reclen) {
                throw new LayoutException("record " + name + " field lengths sum to " + cursor
                        + " but its declared length is " + reclen);
            }
            return this;
        }

        /**
         * Derives a longer record by appending fields to this record's field list.
         *
         * <p>Alternatives Considered: re-declaring the shared prefix by hand in the derived record.
         * Rejected outright. The one record that needs this shares a 350-byte, fourteen-field prefix
         * with the record it derives from, and two hand-maintained copies of a 350-byte layout will
         * drift; the drift would be silent, because a record read against a prefix that is wrong in one
         * field still decodes to digits in every field after it. Appending keeps the prefix
         * single-sourced, so a correction to the base master reaches the derived record with no second
         * edit. The reference codec derives the same record the same way, concatenating the base
         * field list rather than restating it, at {@code tests/helpers/record_codec.py} line 1306.</p>
         *
         * <p>Assumptions: appended fields are RELOCATED to follow this record's declared length rather
         * than trusting the offsets they arrive with. A caller declaring a trailer thinks in offsets
         * relative to the whole derived record, and relocating here means the two views cannot
         * disagree. The result is validated before it is returned, so an appended list whose lengths do
         * not close against the derived length fails at the derivation rather than at first use.</p>
         *
         * @param derivedName the registry key of the derived record; never null and never blank
         * @param derivedReclen the total declared length of the derived record in bytes, which must
         *     equal this record's length plus the appended lengths
         * @param appended the fields to append in order, each of which is relocated to sit after this
         *     record's fields; never null and never empty
         * @return a validated derived record carrying this record's fields followed by the appended
         *     ones, and this record's key geometry
         * @throws LayoutException if {@code appended} is null or empty, if the derived name is null or
         *     blank, or if the resulting fields are not contiguous or do not sum to
         *     {@code derivedReclen}
         */
        public RecordSpec extendWith(String derivedName, int derivedReclen,
                List<FieldSpec> appended) {
            if (appended == null || appended.isEmpty()) {
                throw new LayoutException("record " + name + " cannot be extended into " + derivedName
                        + " with " + (appended == null ? "a null" : "an empty") + " field list");
            }
            List<FieldSpec> combined = new ArrayList<>(fields);
            int cursor = reclen;
            for (FieldSpec appendee : appended) {
                if (appendee == null) {
                    throw new LayoutException("record " + name + " cannot be extended into "
                            + derivedName + " with a field list containing a null element");
                }
                combined.add(appendee.relocatedTo(cursor));
                cursor += appendee.length();
            }
            return new RecordSpec(derivedName, derivedReclen, keyLength, keyOffset, combined)
                    .validateGeometry();
        }

        /**
         * Derives a record identical to this one except for the two diagnostic flags of one field.
         *
         * <p>Alternatives Considered: two other ways to express the one record that needs this were
         * evaluated. Altering the base record's own flag was rejected because the base record must keep
         * asserting the field in question: an ordinary posted transaction carries a deterministic
         * originating stamp that the parity comparison has to verify, so blanking it there would
         * discard business data and shorten the effectively compared record. Passing a per-call
         * override into the comparison was rejected because the policy would then live at every call
         * site instead of in the layout, and a caller that forgot it would produce a comparison that
         * fails for a reason unrelated to the change under test. A named derived layout keeps the
         * policy declarative and discoverable. The reference codec derives the same record the same
         * way, replacing one field's flag over the base field list, at
         * {@code tests/helpers/record_codec.py} line 1340.</p>
         *
         * <p>Assumptions: only the two diagnostic flags are replaceable, never the geometry, for the
         * reason recorded on {@link FieldSpec#withFlags(boolean, boolean)}. A derived record therefore
         * always tiles identically to the record it derives from, which is why the result is validated
         * and why that validation cannot fail for a geometry reason.</p>
         *
         * @param derivedName the registry key of the derived record; never null and never blank
         * @param fieldName the field whose flags are replaced, resolved within this record
         * @param newNormalizeTs whether the named field holds a run-generated timestamp in the derived
         *     record
         * @param newSensitive whether the named field's raw bytes must be kept out of diagnostics in
         *     the derived record
         * @return a validated derived record with the same geometry and the named field's flags
         *     replaced
         * @throws LayoutException if this record declares no field of that name, or if the derived name
         *     is null or blank
         */
        public RecordSpec withFieldFlags(String derivedName, String fieldName,
                boolean newNormalizeTs, boolean newSensitive) {

            // WHY : Assumptions: resolving the name before rebuilding is what turns a misspelled field
            //       name into an immediate, named failure. Without it a name that matches nothing would
            //       produce a derived record identical to the base, which is a silently wrong layout
            //       rather than an error, and the whole purpose of the derivation is the one flag it
            //       was meant to change.
            field(fieldName);

            List<FieldSpec> replaced = new ArrayList<>(fields.size());
            for (FieldSpec candidate : fields) {
                replaced.add(candidate.name().equals(fieldName)
                        ? candidate.withFlags(newNormalizeTs, newSensitive)
                        : candidate);
            }
            return new RecordSpec(derivedName, reclen, keyLength, keyOffset, replaced)
                    .validateGeometry();
        }
    }

    /**
     * Binds one registered layout to the two facts the registry knows about it beyond its geometry.
     *
     * <p>Alternatives Considered: carrying these two facts as components of {@link RecordSpec} itself,
     * or holding them in two maps parallel to the layout map. Putting them on the record was rejected
     * because they are not geometry: a derived record and the base master it derives from have
     * identical geometry, so a descriptor that carried its own provenance would make two records that
     * tile the same way compare unequal. Parallel maps were rejected because three maps keyed on the
     * same name can disagree, and the disagreement would be a layout registered with the wrong
     * provenance rather than an error. One entry per name makes all three facts a single insertion.</p>
     *
     * @param spec the validated layout descriptor
     * @param provenance whether the layout is transcribed from a copybook or derived from one that is
     * @param oracleRoundTrip whether the parity oracle's own codec at
     *     {@code tests/helpers/record_codec.py} registers a layout for this record, and therefore
     *     whether a decode here can be compared against an independent implementation of the same
     *     geometry
     */
    private record Registration(RecordSpec spec, Provenance provenance, boolean oracleRoundTrip) {
    }

    /**
     * The user security record, whose 01 group sits at line 17 of {@code app/cpy/CSUSR01Y.cpy}.
     *
     * <p>Assumptions: this is one of the three base masters the parity oracle's codec does not
     * register, so its geometry rests on the copybook and the dataset definition alone. Both agree:
     * summing its six fields gives 80 bytes, and {@code app/jcl/DUSRSECJ.jcl} lines 65 and 66 declare
     * {@code KEYS(8,0)} and {@code RECORDSIZE(80,80)}. Note that this file writes the key operands
     * comma-separated where {@code app/jcl/TRANIDX.jcl} line 27 writes them space-separated.</p>
     *
     * <p>Assumptions: the trailing pad here is NAMED rather than spelled as an anonymous filler. Line
     * 23 declares {@code 05 SEC-USR-FILLER PIC X(23).} and it is padding in role despite its name, so
     * it is the byte range the domain projection drops for this record. It keeps its declared name
     * because AAP Rule T1 (Copybook is normative) permits no renaming.</p>
     *
     * <p>Assumptions: the eight-character password at line 21 is marked sensitive and is carried in
     * this descriptor because the descriptor's job is to describe the bytes the reference dataset
     * actually contains. The target does not carry the field forward at all: identity moves to a
     * managed user pool and the target user table keeps only a subject reference. That is a documented
     * behavioural change rather than a transcription choice, and this class is the wrong layer to make
     * it in, because the loader still has to read those eight bytes in order to skip them.</p>
     */
    private static final RecordSpec SECUSER_LAYOUT = new RecordSpec("SECUSER", 80, 8, 0, List.of(
            text("SEC-USR-ID", 0, 8),
            sensitiveText("SEC-USR-FNAME", 8, 20),
            sensitiveText("SEC-USR-LNAME", 28, 20),
            sensitiveText("SEC-USR-PWD", 48, 8),
            text("SEC-USR-TYPE", 56, 1),
            text("SEC-USR-FILLER", 57, 23))).validateGeometry();

    /**
     * The account master, transcribed from {@code app/cpy/CVACT01Y.cpy}.
     *
     * <p>Assumptions: 300 bytes with an eleven-byte key at offset zero, corroborated by
     * {@code app/jcl/ACCTFILE.jcl} lines 40 and 41. Its five money fields are the canonical example of
     * the zoned regime, each {@code PIC S9(10)V99} and therefore twelve bytes. The trailing pad the
     * domain projection drops is the {@code PIC X(178)} at line 17.</p>
     *
     * <p>Assumptions: {@code ACCT-EXPIRAION-DATE} at line 11 carries the baseline misspelling and is
     * transcribed with it. The target column is spelled correctly, and the correspondence is recorded
     * in the misspelling table on the enclosing class.</p>
     */
    private static final RecordSpec ACCOUNT_LAYOUT = new RecordSpec("ACCOUNT", 300, 11, 0, List.of(
            uint("ACCT-ID", 0, 11),
            text("ACCT-ACTIVE-STATUS", 11, 1),
            signedZoned("ACCT-CURR-BAL", 12, 10, 2),
            signedZoned("ACCT-CREDIT-LIMIT", 24, 10, 2),
            signedZoned("ACCT-CASH-CREDIT-LIMIT", 36, 10, 2),
            text("ACCT-OPEN-DATE", 48, 10),
            text("ACCT-EXPIRAION-DATE", 58, 10),
            text("ACCT-REISSUE-DATE", 68, 10),
            signedZoned("ACCT-CURR-CYC-CREDIT", 78, 10, 2),
            signedZoned("ACCT-CURR-CYC-DEBIT", 90, 10, 2),
            text("ACCT-ADDR-ZIP", 102, 10),
            text("ACCT-GROUP-ID", 112, 10),
            text("FILLER", 122, 178))).validateGeometry();

    /**
     * The card master, transcribed from {@code app/cpy/CVACT02Y.cpy}.
     *
     * <p>Assumptions: 150 bytes with a sixteen-byte key at offset zero, corroborated by
     * {@code app/jcl/CARDFILE.jcl} lines 54 and 55. Three of its six named fields are marked
     * sensitive: the primary account number, the card verification value and the embossed name.
     * {@code CARD-EXPIRAION-DATE} at line 9 carries the second of the three baseline
     * misspellings.</p>
     */
    private static final RecordSpec CARD_LAYOUT = new RecordSpec("CARD", 150, 16, 0, List.of(
            sensitiveText("CARD-NUM", 0, 16),
            uint("CARD-ACCT-ID", 16, 11),
            sensitiveUint("CARD-CVV-CD", 27, 3),
            sensitiveText("CARD-EMBOSSED-NAME", 30, 50),
            text("CARD-EXPIRAION-DATE", 80, 10),
            text("CARD-ACTIVE-STATUS", 90, 1),
            text("FILLER", 91, 59))).validateGeometry();

    /**
     * The customer master, transcribed from {@code app/cpy/CVCUS01Y.cpy}.
     *
     * <p>Assumptions: 500 bytes with a nine-byte key at offset zero, corroborated by
     * {@code app/jcl/CUSTFILE.jcl} lines 50 and 51. Its eighteen named fields sum to 332 bytes and the
     * {@code PIC X(168)} pad at line 23 completes the 500, which is the arithmetic the record-length
     * banner at line 2 corroborates.</p>
     *
     * <p>Assumptions: this is the most heavily identifying record in the corpus, so twelve of its
     * eighteen named fields are marked sensitive. Two of them drive target encryption specifically:
     * {@code CUST-SSN} at line 17, declared {@code PIC 9(09)}, and {@code CUST-GOVT-ISSUED-ID} at line
     * 18, declared {@code PIC X(20)}. The encryption and the masking both happen in the mapper layer,
     * not here.</p>
     */
    private static final RecordSpec CUSTOMER_LAYOUT = new RecordSpec("CUSTOMER", 500, 9, 0, List.of(
            uint("CUST-ID", 0, 9),
            sensitiveText("CUST-FIRST-NAME", 9, 25),
            sensitiveText("CUST-MIDDLE-NAME", 34, 25),
            sensitiveText("CUST-LAST-NAME", 59, 25),
            sensitiveText("CUST-ADDR-LINE-1", 84, 50),
            sensitiveText("CUST-ADDR-LINE-2", 134, 50),
            sensitiveText("CUST-ADDR-LINE-3", 184, 50),
            text("CUST-ADDR-STATE-CD", 234, 2),
            text("CUST-ADDR-COUNTRY-CD", 236, 3),
            text("CUST-ADDR-ZIP", 239, 10),
            sensitiveText("CUST-PHONE-NUM-1", 249, 15),
            sensitiveText("CUST-PHONE-NUM-2", 264, 15),
            sensitiveUint("CUST-SSN", 279, 9),
            sensitiveText("CUST-GOVT-ISSUED-ID", 288, 20),
            sensitiveText("CUST-DOB-YYYY-MM-DD", 308, 10),
            sensitiveText("CUST-EFT-ACCOUNT-ID", 318, 10),
            text("CUST-PRI-CARD-HOLDER-IND", 328, 1),
            uint("CUST-FICO-CREDIT-SCORE", 329, 3),
            text("FILLER", 332, 168))).validateGeometry();

    /**
     * The card cross-reference, transcribed from {@code app/cpy/CVACT03Y.cpy}.
     *
     * <p>Assumptions: 50 bytes with a sixteen-byte key at offset zero, corroborated by
     * {@code app/jcl/XREFFILE.jcl} lines 43 and 44. Its 01 group is indented one space where every
     * other base master uses two, which is the indentation irregularity recorded on the enclosing
     * class.</p>
     *
     * <p>Assumptions: this record has a SECOND access path that this descriptor does not carry. The
     * interest program reads the file by account identifier rather than by card number, so the
     * reference dataset defines an alternate index over the eleven bytes at offset 25, and the
     * reference codec models that as an extra attribute on its layout. {@link RecordSpec} carries the
     * primary key only, because the target expresses the same access path as a database index declared
     * in a schema migration. The account identifier is still a first-class field here, at offset 25
     * with length 11, so the index that replaces the alternate path is derivable from this descriptor
     * without the descriptor having to name it.</p>
     */
    private static final RecordSpec XREF_LAYOUT = new RecordSpec("XREF", 50, 16, 0, List.of(
            sensitiveText("XREF-CARD-NUM", 0, 16),
            uint("XREF-CUST-ID", 16, 9),
            uint("XREF-ACCT-ID", 25, 11),
            text("FILLER", 36, 14))).validateGeometry();

    /**
     * The daily transaction input, transcribed from {@code app/cpy/CVTRA06Y.cpy}.
     *
     * <p>Assumptions: 350 bytes with a sixteen-byte key at offset zero. Its geometry is field for field
     * the same as the posted transaction record's, with a different name prefix on every field, which
     * is why the two are separate registry entries rather than one aliased entry.</p>
     *
     * <p>Assumptions: exactly one of its two timestamps is normalised. The originating stamp at offset
     * 278 is deterministic business data copied from the source transaction and is compared; the
     * processing stamp at offset 304 is read from the clock during the run and is blanked before
     * comparison. Blanking both would discard business data and shorten the effectively compared
     * record.</p>
     */
    private static final RecordSpec DALYTRAN_LAYOUT = new RecordSpec("DALYTRAN", 350, 16, 0, List.of(
            text("DALYTRAN-ID", 0, 16),
            text("DALYTRAN-TYPE-CD", 16, 2),
            uint("DALYTRAN-CAT-CD", 18, 4),
            text("DALYTRAN-SOURCE", 22, 10),
            text("DALYTRAN-DESC", 32, 100),
            signedZoned("DALYTRAN-AMT", 132, 9, 2),
            uint("DALYTRAN-MERCHANT-ID", 143, 9),
            text("DALYTRAN-MERCHANT-NAME", 152, 50),
            text("DALYTRAN-MERCHANT-CITY", 202, 50),
            text("DALYTRAN-MERCHANT-ZIP", 252, 10),
            sensitiveText("DALYTRAN-CARD-NUM", 262, 16),
            text("DALYTRAN-ORIG-TS", 278, 26),
            normalizedTimestamp("DALYTRAN-PROC-TS", 304, 26),
            text("FILLER", 330, 20))).validateGeometry();

    /**
     * The posted transaction master, transcribed from {@code app/cpy/CVTRA05Y.cpy}.
     *
     * <p>Assumptions: 350 bytes with a sixteen-byte key at offset zero, corroborated by
     * {@code app/jcl/TRANFILE.jcl} lines 53 and 54. This is the record whose offsets are confirmed by
     * three independent sources, as the enclosing class sets out: its card number sits at 262 and its
     * processing timestamp at 304, matching the ONE-based DFSORT positions 263 and 305 at
     * {@code app/jcl/TRANREPT.jcl} lines 41 and 42 and the ZERO-based alternate index key
     * {@code KEYS(26 304)} at {@code app/jcl/TRANIDX.jcl} line 27. Those two offsets are the most
     * load-bearing pair in the registry, because the report sort and the secondary index both read
     * them.</p>
     */
    private static final RecordSpec TRAN_LAYOUT = new RecordSpec("TRAN", 350, 16, 0, List.of(
            text("TRAN-ID", 0, 16),
            text("TRAN-TYPE-CD", 16, 2),
            uint("TRAN-CAT-CD", 18, 4),
            text("TRAN-SOURCE", 22, 10),
            text("TRAN-DESC", 32, 100),
            signedZoned("TRAN-AMT", 132, 9, 2),
            uint("TRAN-MERCHANT-ID", 143, 9),
            text("TRAN-MERCHANT-NAME", 152, 50),
            text("TRAN-MERCHANT-CITY", 202, 50),
            text("TRAN-MERCHANT-ZIP", 252, 10),
            sensitiveText("TRAN-CARD-NUM", 262, 16),
            text("TRAN-ORIG-TS", 278, 26),
            normalizedTimestamp("TRAN-PROC-TS", 304, 26),
            text("FILLER", 330, 20))).validateGeometry();

    /**
     * The disclosure group, transcribed from {@code app/cpy/CVTRA02Y.cpy}.
     *
     * <p>Assumptions: 50 bytes with a sixteen-byte composite key at offset zero, corroborated by
     * {@code app/jcl/DISCGRP.jcl} lines 40 and 41. Line 5 brackets the first three fields under a group
     * named for the key; those three are flattened here and the sixteen-byte composite survives as the
     * key length, which is the flattening decision argued on the enclosing class.</p>
     *
     * <p>Assumptions: the interest rate at line 9 is {@code PIC S9(04)V99}, so it is six bytes and
     * becomes a six-digit, two-decimal target column. It is the only zoned field in the corpus with
     * four integer digits, and its width follows from the same rule as every other zoned field rather
     * than from an exception.</p>
     */
    private static final RecordSpec DISGROUP_LAYOUT = new RecordSpec("DISGROUP", 50, 16, 0, List.of(
            text("DIS-ACCT-GROUP-ID", 0, 10),
            text("DIS-TRAN-TYPE-CD", 10, 2),
            uint("DIS-TRAN-CAT-CD", 12, 4),
            signedZoned("DIS-INT-RATE", 16, 4, 2),
            text("FILLER", 22, 28))).validateGeometry();

    /**
     * The transaction category reference, transcribed from {@code app/cpy/CVTRA04Y.cpy}.
     *
     * <p>Assumptions: 60 bytes with a SIX-byte composite key at offset zero, corroborated by
     * {@code app/jcl/TRANCATG.jcl} lines 40 and 41. This is one of the two records whose key group is
     * named identically to another record's while being a different width, and the pair is the reason
     * field names resolve per layout rather than globally. Here the group is two plus four bytes; in
     * the category-balance record it is eleven plus two plus four. The two dataset definitions
     * corroborate both widths independently.</p>
     *
     * <p>Assumptions: this is the second of the three base masters the parity oracle's codec does not
     * register, so its geometry rests on the copybook and the dataset definition alone.</p>
     */
    private static final RecordSpec TRANCAT_LAYOUT = new RecordSpec("TRANCAT", 60, 6, 0, List.of(
            text("TRAN-TYPE-CD", 0, 2),
            uint("TRAN-CAT-CD", 2, 4),
            text("TRAN-CAT-TYPE-DESC", 6, 50),
            text("FILLER", 56, 4))).validateGeometry();

    /**
     * The transaction type reference, transcribed from {@code app/cpy/CVTRA03Y.cpy}.
     *
     * <p>Assumptions: 60 bytes with a two-byte key at offset zero, corroborated by
     * {@code app/jcl/TRANTYPE.jcl} lines 40 and 41. It is the smallest key in the registry, and it is
     * the parent of the category reference above, which the target expresses as a restricting foreign
     * key so that deleting a type that categories still reference is refused rather than cascaded.</p>
     *
     * <p>Assumptions: this is the third of the three base masters the parity oracle's codec does not
     * register.</p>
     */
    private static final RecordSpec TRANTYPE_LAYOUT = new RecordSpec("TRANTYPE", 60, 2, 0, List.of(
            text("TRAN-TYPE", 0, 2),
            text("TRAN-TYPE-DESC", 2, 50),
            text("FILLER", 52, 8))).validateGeometry();

    /**
     * The transaction category balance, transcribed from {@code app/cpy/CVTRA01Y.cpy}.
     *
     * <p>Assumptions: 50 bytes with a SEVENTEEN-byte composite key at offset zero, corroborated by
     * {@code app/jcl/TCATBALF.jcl} lines 40 and 41. Line 5 brackets its first three fields under a
     * group whose name collides with the category reference record's, at a different width; the
     * collision is why lookup is scoped per record.</p>
     *
     * <p>Assumptions: all four of this record's field offsets are confirmed by an independent source.
     * {@code app/jcl/PRTCATBL.jcl} lines 47 to 50 declare the ONE-based DFSORT positions 1, 12, 14 and
     * 18, which convert to the offsets 0, 11, 13 and 17 declared below, and its
     * {@code TRAN-CAT-BAL,18,11,ZD} independently confirms both that the balance is zoned and that
     * eleven bytes is the width of a {@code PIC S9(09)V99}.</p>
     */
    private static final RecordSpec TCATBAL_LAYOUT = new RecordSpec("TCATBAL", 50, 17, 0, List.of(
            uint("TRANCAT-ACCT-ID", 0, 11),
            text("TRANCAT-TYPE-CD", 11, 2),
            uint("TRANCAT-CD", 13, 4),
            signedZoned("TRAN-CAT-BAL", 17, 9, 2),
            text("FILLER", 28, 22))).validateGeometry();

    /**
     * The statement view of a transaction, transcribed from {@code app/cpy/COSTM01.CPY}.
     *
     * <p>Assumptions: this is NOT an alias of the posted transaction record and must never be treated
     * as one. It is 350 bytes like that record, but it leads with a THIRTY-TWO byte composite key,
     * being the card number followed by the transaction identifier, where the posted record leads with
     * the identifier alone. Every field after the key is therefore displaced: its amount sits at offset
     * 148 where the posted record's sits at 132. The reference codec records the same finding in the
     * same terms at {@code tests/helpers/record_codec.py} line 1344, calling the two deliberately
     * separate record types with different geometry rather than aliases, and notes that an earlier
     * aliasing of the two mis-decoded every statement amount.</p>
     *
     * <p>Assumptions: line 21 brackets the key as one group and line 24 brackets the remainder as
     * another, so this copybook nests two group items rather than one. Both are flattened, and the
     * thirty-two byte composite survives as the key length.</p>
     */
    private static final RecordSpec TRNX_LAYOUT = new RecordSpec("TRNX", 350, 32, 0, List.of(
            sensitiveText("TRNX-CARD-NUM", 0, 16),
            text("TRNX-ID", 16, 16),
            text("TRNX-TYPE-CD", 32, 2),
            uint("TRNX-CAT-CD", 34, 4),
            text("TRNX-SOURCE", 38, 10),
            text("TRNX-DESC", 48, 100),
            signedZoned("TRNX-AMT", 148, 9, 2),
            uint("TRNX-MERCHANT-ID", 159, 9),
            text("TRNX-MERCHANT-NAME", 168, 50),
            text("TRNX-MERCHANT-CITY", 218, 50),
            text("TRNX-MERCHANT-ZIP", 268, 10),
            text("TRNX-ORIG-TS", 278, 26),
            normalizedTimestamp("TRNX-PROC-TS", 304, 26),
            text("FILLER", 330, 20))).validateGeometry();

    /**
     * The posting reject stream record, DERIVED by extending the daily transaction record.
     *
     * <p>Assumptions: 430 bytes, and the arithmetic is exact. The posting program writes a rejected
     * transaction as its verbatim 350-byte daily-transaction image followed by an 80-byte trailer, and
     * {@code app/cbl/CBTRN02C.cbl} declares both halves: lines 176 to 178 declare a record of a
     * 350-byte data area plus an 80-byte trailer, and lines 180 to 182 declare that trailer as a
     * four-digit reason code followed by a 76-character description. 350 plus 4 plus 76 is 430. The
     * file definition at lines 83 and 84 of the same program states the same two halves
     * independently.</p>
     *
     * <p>Assumptions: the four reason codes that populate the first trailer field are 100, 101, 102 and
     * 103, set at lines 385, 397, 410 and 417 of that program. They are recorded here because the field
     * width of four digits is only meaningful alongside the domain it carries.</p>
     *
     * <p>Alternatives Considered: declaring all sixteen fields by hand. Rejected because fourteen of
     * them would then exist twice, and two hand-maintained copies of a 350-byte prefix drift silently.
     * The extension keeps the prefix single-sourced, which also means this record inherits the
     * timestamp-normalisation decision of the record it extends rather than restating it.</p>
     */
    private static final RecordSpec REJECT_LAYOUT = DALYTRAN_LAYOUT.extendWith("REJECT", 430, List.of(
            uint("WS-VALIDATION-FAIL-REASON", 350, 4),
            text("WS-VALIDATION-FAIL-REASON-DESC", 354, 76)));

    /**
     * The accrued-interest transaction, DERIVED from the posted transaction record.
     *
     * <p>Assumptions: 350 bytes with the posted record's geometry exactly, differing in one flag only,
     * and the reason is visible in four lines of the reference baseline. The interest program moves its
     * run-clock value into BOTH timestamps, at {@code app/cbl/CBACT04C.cbl} lines 497 and 498. The
     * posting program does not: it copies the originating stamp from the source transaction at
     * {@code app/cbl/CBTRN02C.cbl} line 436 and moves the run clock only into the processing stamp at
     * line 438. So under the base layout the interest record's run-generated originating stamp would
     * make every interest comparison non-deterministic. This layout flips that one flag and changes
     * nothing else.</p>
     *
     * <p>Alternatives Considered: flipping the flag on the base record instead. Rejected because
     * ordinary posted transactions must keep asserting their deterministic originating stamp, so the
     * base record cannot blank it. The reference codec reaches the same conclusion and derives the same
     * variant the same way, at {@code tests/helpers/record_codec.py} line 1333.</p>
     */
    private static final RecordSpec INTTRAN_LAYOUT =
            TRAN_LAYOUT.withFieldFlags("INTTRAN", "TRAN-ORIG-TS", true, false);

    /**
     * Every authorization-segment field whose bytes MAY be rendered into a diagnostic.
     *
     * <p>Refactoring Rationale: the two authorization segments below used to classify almost nothing.
     * {@code PAUTDTL} marked exactly one field sensitive, the primary account number, and
     * {@code PAUTSUM0} marked none at all -- so a malformed byte anywhere in either segment could put
     * the credit limit, the cash limit, both balances, the approved and declined amounts, the card
     * expiry date, the transaction and approved amounts, the merchant identity and the transaction
     * identifier into an exception message in full. That was wrong on its own terms rather than merely
     * conservative, because the SAME record content is classified far more widely elsewhere in this very
     * package: {@link CsvAuthCodec}'s own sensitive set covers the card number, the card expiry date,
     * the transaction amount, the approved amount, the merchant identity, name, city and postal code and
     * the transaction identifier over the authorization wire. One record cannot be two sensitivities --
     * a segment decoded here and a message decoded there carry the same fields -- so a decode failure
     * here would emit precisely what that codec refuses to emit at all.</p>
     *
     * <p>Assumptions: this set names every authorization field that may be rendered, and it is the SAME
     * set as {@code _AUTHORIZATION_DISCLOSABLE_FIELDS} in
     * {@code data-migration/src/carddemo_migration/copybook/layouts.py}, name for name. That is not a
     * coincidence to be maintained by hand: the parity is asserted by a test that reads that file, so a
     * name added on either side without the other fails the Java build. The line the set draws is that
     * a date, a time, a code from a small closed domain, a count rather than a money value, the account
     * key the published contracts render in full, and the trailing pad may be disclosed; cardholder
     * identity, merchant identity beyond a two-character state, and every amount may not.</p>
     *
     * <p>Trade-offs: it is expressed as an ALLOWLIST so the default direction is CLOSED. A denylist
     * reads shorter, and that is exactly its defect -- a field added to either segment later would be
     * disclosable until somebody remembered to mark it, whereas here it is withheld until somebody
     * deliberately names it. The failure mode of forgetting becomes silence rather than disclosure. The
     * cost is that a genuinely harmless new field produces an unhelpfully bare diagnostic until it is
     * named, which is the cheaper of the two mistakes to discover.</p>
     *
     * <p>Alternatives Considered: adopting the wire set of {@link CsvAuthCodec} directly and marking
     * only those nine base names. Rejected because that set was derived from the request and reply
     * payloads, which carry no customer identifier and none of the summary segment's four balances and
     * limits, so it classifies nothing at all for {@code PAUTSUM0} -- it has no opinion to copy. The
     * allowlist covers both segments from one statement and reaches the same verdict as the wire set on
     * every field the two have in common.</p>
     *
     * <p>Assumptions: the policy is a target-only addition. A copybook declares widths and usages and
     * has no notion of a field whose content may not be logged, so the divergence is registered as
     * {@code D-AUTH-DIAGNOSTIC-DISCLOSURE} in
     * {@code docs/architecture/cobol-to-service-traceability.md} rather than presented as parity.</p>
     */
    static final java.util.Set<String> AUTHORIZATION_DISCLOSABLE_FIELDS = java.util.Set.of(
            // Summary segment: the account key, the two status fields, the two counters.
            "PA-ACCT-ID",
            "PA-AUTH-STATUS",
            "PA-ACCOUNT-STATUS",
            "PA-APPROVED-AUTH-CNT",
            "PA-DECLINED-AUTH-CNT",
            // Detail segment: the four date and time fields, the closed-domain codes, the two
            // remaining narrow codes and the merchant state.
            "PA-AUTH-DATE-9C",
            "PA-AUTH-TIME-9C",
            "PA-AUTH-ORIG-DATE",
            "PA-AUTH-ORIG-TIME",
            "PA-AUTH-TYPE",
            "PA-MESSAGE-TYPE",
            "PA-MESSAGE-SOURCE",
            "PA-AUTH-ID-CODE",
            "PA-AUTH-RESP-CODE",
            "PA-AUTH-RESP-REASON",
            "PA-PROCESSING-CODE",
            "PA-MERCHANT-CATAGORY-CODE",
            "PA-ACQR-COUNTRY-CODE",
            "PA-POS-ENTRY-MODE",
            "PA-MERCHANT-STATE",
            "PA-MATCH-STATUS",
            "PA-AUTH-FRAUD",
            "PA-FRAUD-RPT-DATE",
            // Both segments close with a trailing pad, which carries no value at all.
            "FILLER");

    /**
     * Marks every authorization field sensitive unless {@link #AUTHORIZATION_DISCLOSABLE_FIELDS} names
     * it.
     *
     * <p>Assumptions: only the sensitivity flag is ever changed, and the geometry is carried through
     * untouched by {@link FieldSpec#withFlags(boolean, boolean)}, so applying this cannot move a field,
     * resize one or alter a storage regime. That is what lets it run between the field declarations and
     * {@link RecordSpec#validateGeometry()}, leaving the hundred-byte and two-hundred-byte sums to be
     * checked exactly as before.</p>
     *
     * <p>Trade-offs: the timestamp-normalisation flag is read off each field and written straight back
     * rather than being defaulted to false, because neither authorization segment declares a wall-clock
     * stamp today and a helper that quietly cleared the flag would be wrong the moment one did. Reading
     * and restoring costs nothing and removes that trap.</p>
     *
     * <p>Alternatives Considered: reaching for {@link #sensitiveText(String, int, int)} at each
     * declaration site instead, which is how every other record in this class classifies its fields.
     * Rejected for these two records alone, and only because the policy is fail-closed: with an
     * allowlist the correct marking of a NEW field is "sensitive", so a declaration site that has to
     * remember to reach for the sensitive factory has the safe outcome as the one requiring an action.
     * Deciding it in one place inverts that. The three storage regimes are a second reason -- these
     * segments hold packed, binary and display fields that need withholding, and only two of the
     * factories have sensitive variants.</p>
     *
     * @param fields the segment's field descriptors exactly as transcribed from its copybook; never
     *     {@code null}
     * @return a list of the same fields in the same order and with the same geometry, each marked
     *     sensitive unless the allowlist names it; never {@code null}
     */
    private static List<FieldSpec> closeAuthorizationDisclosure(List<FieldSpec> fields) {
        List<FieldSpec> closed = new ArrayList<>(fields.size());
        for (FieldSpec field : fields) {
            closed.add(field.withFlags(field.normalizeTs(),
                    !AUTHORIZATION_DISCLOSABLE_FIELDS.contains(field.name())));
        }
        return List.copyOf(closed);
    }

    /**
     * The authorization summary segment, the parent of the authorization database.
     *
     * <p>Assumptions: 100 bytes with a six-byte key at offset zero, and both numbers have two
     * independent sources. The length is declared by {@code SEGM NAME=PAUTSUM0,PARENT=0,BYTES=100} in
     * {@code app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd}, and it is reproduced by summing the
     * field widths of {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} lines 19 to 31 through
     * {@link #packedWidth} and {@link #binaryWidth}. The key is declared by
     * {@code FIELD NAME=(ACCNTID,SEQ,U),START=1,BYTES=6,TYPE=P} in the same descriptor, which is the
     * packed account identifier at line 19 and nothing else -- {@code START=1} is one-based there, so
     * the offset here is zero.</p>
     *
     * <p>Assumptions: the five-occurrence account-status table at line 22 is transcribed as ONE
     * ten-byte character field rather than as five two-byte fields. The segment is decoded here as
     * bytes at offsets, and the reference program reads the table by subscript from the same ten bytes,
     * so one field of the declared total keeps the geometry exact while leaving the subscripting to the
     * consumer that has the subscript. Five separate fields would also have required five distinct
     * names, and the copybook gives the group one.</p>
     *
     * <p>Alternatives Considered: omitting this layout from the Java registry and reading the segment
     * through the Python ETL alone, which is where its geometry was first transcribed. Rejected because
     * the two implementations exist precisely so that a decode can be checked against an independent
     * transcription of the same copybook, and a record present in only one of them has no second
     * reading to disagree with it -- which is the whole mechanism by which an offset error in a
     * packed-decimal money field gets caught rather than posted.</p>
     *
     * <p>Refactoring Rationale: the field list is wrapped in
     * {@link #closeAuthorizationDisclosure(List)} rather than declaring each field's sensitivity at its
     * own site, and this segment used to declare NONE of it -- so a decode diagnostic could render the
     * customer identifier, both credit limits, both balances and the two authorization amounts in full.
     * Seven of its thirteen fields are withheld now: {@code PA-CUST-ID}, {@code PA-CREDIT-LIMIT},
     * {@code PA-CASH-LIMIT}, {@code PA-CREDIT-BALANCE}, {@code PA-CASH-BALANCE},
     * {@code PA-APPROVED-AUTH-AMT} and {@code PA-DECLINED-AUTH-AMT}. The remaining six -- the packed
     * account key, the two status fields, the two counters and the pad -- are the ones an operator
     * reading a malformed extract actually needs, and none of them is an amount or a cardholder
     * attribute.</p>
     */
    private static final RecordSpec PAUTSUM0_LAYOUT = new RecordSpec("PAUTSUM0", 100, 6, 0,
            closeAuthorizationDisclosure(List.of(
            packed("PA-ACCT-ID", 0, 11, 0, true),  // CIPAUSMY L19 PIC S9(11) COMP-3, 6 bytes
            uint("PA-CUST-ID", 6, 9),  // CIPAUSMY L20 PIC 9(09) display
            text("PA-AUTH-STATUS", 15, 1),  // CIPAUSMY L21 PIC X(01)
            text("PA-ACCOUNT-STATUS", 16, 10),  // CIPAUSMY L22 PIC X(02) OCCURS 5, 10 bytes
            packed("PA-CREDIT-LIMIT", 26, 9, 2, true),  // CIPAUSMY L23 PIC S9(09)V99 COMP-3
            packed("PA-CASH-LIMIT", 32, 9, 2, true),  // CIPAUSMY L24 PIC S9(09)V99 COMP-3
            packed("PA-CREDIT-BALANCE", 38, 9, 2, true),  // CIPAUSMY L25 PIC S9(09)V99 COMP-3
            packed("PA-CASH-BALANCE", 44, 9, 2, true),  // CIPAUSMY L26 PIC S9(09)V99 COMP-3
            binary("PA-APPROVED-AUTH-CNT", 50, 4, 0, true),  // CIPAUSMY L27 PIC S9(04) COMP, 2 bytes
            binary("PA-DECLINED-AUTH-CNT", 52, 4, 0, true),  // CIPAUSMY L28 PIC S9(04) COMP, 2 bytes
            packed("PA-APPROVED-AUTH-AMT", 54, 9, 2, true),  // CIPAUSMY L29 PIC S9(09)V99 COMP-3
            packed("PA-DECLINED-AUTH-AMT", 60, 9, 2, true),  // CIPAUSMY L30 PIC S9(09)V99 COMP-3
            text("FILLER", 66, 34)))).validateGeometry();  // CIPAUSMY L31 PIC X(34) trailing pad

    /**
     * The authorization detail segment, the child of the authorization database.
     *
     * <p>Assumptions: 200 bytes with an eight-byte key at offset zero. The length is declared by
     * {@code SEGM NAME=PAUTDTL1,PARENT=((PAUTSUM0,)),BYTES=200} in
     * {@code app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd}, and the key by
     * {@code FIELD NAME=(PAUT9CTS,SEQ,U),START=1,BYTES=8,TYPE=C}, which spans the two packed
     * components the copybook brackets under {@code PA-AUTHORIZATION-KEY} at
     * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} line 19 -- three bytes for the date at
     * line 20 and five for the time at line 21, eight together.</p>
     *
     * <p>Assumptions: the group item at line 19 is FLATTENED, so its two components appear here as
     * peers of the fields that follow them rather than nested. Every consumer works in flat byte
     * offsets, and the composite it forms survives as the declared key length, which is how the
     * eight-byte sequence field is reproduced without a tree.</p>
     *
     * <p>Assumptions: the two twelve-digit money fields at lines 34 and 35 are SEVEN bytes each, and
     * the segment's declared 200 is what proves it. At six bytes apiece the field widths sum to 198,
     * two short of the declared length with no field left to absorb them; at seven they sum to exactly
     * 200. The arithmetic is {@link #packedWidth}'s and this layout merely consumes it, but the
     * segment is the corroborating witness, so it is recorded here as well as there.</p>
     *
     * <p>Assumptions: the misspelling in {@code PA-MERCHANT-CATAGORY-CODE} at line 36 is carried
     * across verbatim. This registry names fields as the copybook declares them, because the name is
     * how a reader matches a field here against the line that defines it; the correction to
     * {@code merchant_category_code} belongs to the mapping layer and is recorded in
     * {@code docs/architecture/data-model-and-schema-mapping.md}.</p>
     *
     * <p>Refactoring Rationale: this paragraph used to read "only the primary account number at line
     * 24 is marked sensitive", and gave as its reason that "the merchant name, city, state and postal
     * code are acquirer-supplied merchant identity rather than cardholder identity, so marking them
     * would redact the very fields a fraud reviewer reads to recognise a merchant". Both halves were
     * wrong for this purpose. The reason conflates two channels: a fraud reviewer reads a RESPONSE
     * BODY, which this flag does not touch, whereas the flag governs only what a decode FAILURE may
     * quote -- and a reviewer never reads one of those. And the classification disagreed with
     * {@link CsvAuthCodec}, which withholds the merchant identifier, name, city and postal code over
     * the wire, so the same four values were withheld from one diagnostic and rendered in another. Nine
     * fields are withheld now, by {@link #closeAuthorizationDisclosure(List)} rather than at their own
     * declaration sites: {@code PA-CARD-NUM}, {@code PA-CARD-EXPIRY-DATE}, {@code PA-TRANSACTION-AMT},
     * {@code PA-APPROVED-AMT}, {@code PA-MERCHANT-ID}, {@code PA-MERCHANT-NAME},
     * {@code PA-MERCHANT-CITY}, {@code PA-MERCHANT-ZIP} and {@code PA-TRANSACTION-ID} -- exactly the
     * nine base names that codec's wire set covers.</p>
     *
     * <p>Assumptions: {@code PA-MERCHANT-STATE} remains disclosable where the other four merchant
     * fields do not, and the distinction is the one the wire set already draws. A two-character state
     * is a closed domain of roughly sixty values and narrows to no party; a name, a street-level city,
     * a nine-character postal code or a fifteen-character merchant identifier each narrow to one. The
     * segment carries no card verification value at all, so there is none to classify.</p>
     */
    private static final RecordSpec PAUTDTL_LAYOUT = new RecordSpec("PAUTDTL", 200, 8, 0,
            closeAuthorizationDisclosure(List.of(
            packed("PA-AUTH-DATE-9C", 0, 5, 0, true),  // CIPAUDTY L20 PIC S9(05) COMP-3, 3 bytes
            packed("PA-AUTH-TIME-9C", 3, 9, 0, true),  // CIPAUDTY L21 PIC S9(09) COMP-3, 5 bytes
            text("PA-AUTH-ORIG-DATE", 8, 6),  // CIPAUDTY L22 PIC X(06)
            text("PA-AUTH-ORIG-TIME", 14, 6),  // CIPAUDTY L23 PIC X(06)
            text("PA-CARD-NUM", 20, 16),  // CIPAUDTY L24 PIC X(16), withheld by the allowlist
            text("PA-AUTH-TYPE", 36, 4),  // CIPAUDTY L25 PIC X(04)
            text("PA-CARD-EXPIRY-DATE", 40, 4),  // CIPAUDTY L26 PIC X(04)
            text("PA-MESSAGE-TYPE", 44, 6),  // CIPAUDTY L27 PIC X(06)
            text("PA-MESSAGE-SOURCE", 50, 6),  // CIPAUDTY L28 PIC X(06)
            text("PA-AUTH-ID-CODE", 56, 6),  // CIPAUDTY L29 PIC X(06)
            text("PA-AUTH-RESP-CODE", 62, 2),  // CIPAUDTY L30 PIC X(02), 88 at L31
            text("PA-AUTH-RESP-REASON", 64, 4),  // CIPAUDTY L32 PIC X(04)
            uint("PA-PROCESSING-CODE", 68, 6),  // CIPAUDTY L33 PIC 9(06) display
            packed("PA-TRANSACTION-AMT", 74, 10, 2, true),  // CIPAUDTY L34 S9(10)V99 COMP-3, 7 bytes
            packed("PA-APPROVED-AMT", 81, 10, 2, true),  // CIPAUDTY L35 S9(10)V99 COMP-3, 7 bytes
            text("PA-MERCHANT-CATAGORY-CODE", 88, 4),  // CIPAUDTY L36 PIC X(04), misspelt in source
            text("PA-ACQR-COUNTRY-CODE", 92, 3),  // CIPAUDTY L37 PIC X(03)
            uint("PA-POS-ENTRY-MODE", 95, 2),  // CIPAUDTY L38 PIC 9(02) display
            text("PA-MERCHANT-ID", 97, 15),  // CIPAUDTY L39 PIC X(15)
            text("PA-MERCHANT-NAME", 112, 22),  // CIPAUDTY L40 PIC X(22)
            text("PA-MERCHANT-CITY", 134, 13),  // CIPAUDTY L41 PIC X(13)
            text("PA-MERCHANT-STATE", 147, 2),  // CIPAUDTY L42 PIC X(02)
            text("PA-MERCHANT-ZIP", 149, 9),  // CIPAUDTY L43 PIC X(09)
            text("PA-TRANSACTION-ID", 158, 15),  // CIPAUDTY L44 PIC X(15)
            text("PA-MATCH-STATUS", 173, 1),  // CIPAUDTY L45 PIC X(01), 88s L46-L49
            text("PA-AUTH-FRAUD", 174, 1),  // CIPAUDTY L50 PIC X(01), 88s L51-L52
            text("PA-FRAUD-RPT-DATE", 175, 8),  // CIPAUDTY L53 PIC X(08)
            text("FILLER", 183, 17)))).validateGeometry();  // CIPAUDTY L54 PIC X(17) trailing pad

    /**
     * The registry of every layout this class declares, keyed by logical record name.
     *
     * <p>Assumptions: insertion order is preserved and is meaningful, so the map is a linked one rather
     * than a hashed one. The eleven base masters are inserted first, in the order their copybooks
     * define the datasets, then the three derived records, then the two IMS segments. That makes
     * {@link #names()}
     * deterministic, which matters because a failure message that lists the known names would otherwise
     * list them differently on different runs and defeat a comparison against a committed
     * expectation.</p>
     */
    private static final Map<String, Registration> REGISTRY = buildRegistry();

    /**
     * Assembles the registry and proves every entry it holds.
     *
     * <p>Assumptions: this runs once at class-load time, which is the earliest moment a transcription
     * error can be detected, and every layout it inserts has already been proven by a
     * {@link RecordSpec#validateGeometry()} call at its own declaration. The reference codec places its
     * equivalent self-check at import time for the same stated reason, that the codec is the single
     * source of truth so a wrong offset must never reach the code depending on it.</p>
     *
     * <p>Assumptions: the eleven base masters are inserted before the three derived records and the
     * two IMS segments, and the provenance and oracle-support flags are supplied per entry rather than
     * inferred from the name. Inferring would mean encoding the population in a condition somewhere,
     * and the population is exactly the thing that has been miscounted before.</p>
     *
     * @return an unmodifiable, insertion-ordered map from logical record name to its registration
     * @throws LayoutException if any layout is registered twice under one name
     */
    private static Map<String, Registration> buildRegistry() {
        Map<String, Registration> registry = new LinkedHashMap<>();

        // WHY : Assumptions: the eight base masters below are the ones the parity oracle's codec also
        //       registers, so a decode here can be checked against an independent implementation of the
        //       same geometry. That is what the trailing true records, and it is the reason the flag is
        //       carried at all rather than being a comment.
        register(registry, ACCOUNT_LAYOUT, Provenance.BASE_MASTER, true);
        register(registry, CARD_LAYOUT, Provenance.BASE_MASTER, true);
        register(registry, CUSTOMER_LAYOUT, Provenance.BASE_MASTER, true);
        register(registry, XREF_LAYOUT, Provenance.BASE_MASTER, true);
        register(registry, DALYTRAN_LAYOUT, Provenance.BASE_MASTER, true);
        register(registry, TRAN_LAYOUT, Provenance.BASE_MASTER, true);
        register(registry, DISGROUP_LAYOUT, Provenance.BASE_MASTER, true);
        register(registry, TCATBAL_LAYOUT, Provenance.BASE_MASTER, true);

        // WHY : Assumptions: these three base masters have NO counterpart in the parity oracle's codec,
        //       which is the whole reason this class distinguishes the two populations. The oracle
        //       registers eleven layouts and the migration has eleven base masters, but only eight
        //       names appear in both, so treating the two elevens as one population would drop exactly
        //       these three from the registry and nothing would report the loss. Their geometry
        //       therefore rests on the copybook and the dataset definition alone, and a decode of one
        //       of them has no second implementation to be compared against.
        register(registry, SECUSER_LAYOUT, Provenance.BASE_MASTER, false);
        register(registry, TRANCAT_LAYOUT, Provenance.BASE_MASTER, false);
        register(registry, TRANTYPE_LAYOUT, Provenance.BASE_MASTER, false);

        // WHY : Assumptions: these three are the oracle's other three entries and are NOT base masters.
        //       The statement view is a separate copybook over the same dataset with a wider leading
        //       key; the reject stream and the interest transaction are both built from a base master by
        //       this class. Labelling them derived is what lets a reader reconcile the count of eight
        //       with the count of eleven instead of having to choose between them.
        register(registry, TRNX_LAYOUT, Provenance.DERIVED, true);
        register(registry, REJECT_LAYOUT, Provenance.DERIVED, true);
        register(registry, INTTRAN_LAYOUT, Provenance.DERIVED, true);

        // WHY : Assumptions: the two IMS segments are inserted last and are marked as having no
        //       oracle round trip, because the parity oracle's codec registers neither and no shipped
        //       flat extract exists for either -- app/data/EBCDIC holds one file per VSAM master and
        //       none for a segment. A decode of one of these therefore rests on its own copybook and
        //       the database descriptor that declares its length, which is why both are cited in full
        //       at each declaration above.
        register(registry, PAUTSUM0_LAYOUT, Provenance.IMS_SEGMENT, false);
        register(registry, PAUTDTL_LAYOUT, Provenance.IMS_SEGMENT, false);

        return Collections.unmodifiableMap(registry);
    }

    /**
     * Inserts one proven layout into the registry under its own name.
     *
     * <p>Assumptions: a duplicate name is rejected rather than overwritten. Silently replacing an entry
     * would leave the registry holding one of two layouts with no indication that the other was ever
     * declared, and since the two would differ in geometry every decode after the replacement would use
     * the wrong one.</p>
     *
     * @param registry the map under construction, which this method inserts into
     * @param spec the layout to register, already proven by its own declaration
     * @param provenance whether the layout is transcribed from a copybook or derived from one that is
     * @param oracleRoundTrip whether the parity oracle's codec registers a layout for the same record
     * @throws LayoutException if a layout is already registered under the same name
     */
    private static void register(Map<String, Registration> registry, RecordSpec spec,
            Provenance provenance, boolean oracleRoundTrip) {
        Registration previous = registry.putIfAbsent(spec.name(),
                new Registration(spec, provenance, oracleRoundTrip));
        if (previous != null) {
            throw new LayoutException("a layout is already registered under the name " + spec.name()
                    + "; every registry name must be unique because a name is how every consumer"
                    + " resolves geometry");
        }
    }

    /**
     * Returns the layout registered under the given logical record name.
     *
     * <p>Assumptions: an unknown name is rejected rather than answered with an absent value, and the
     * failure message lists the names that do exist. A caller asking for a layout has no fallback
     * available: it is about to slice a record, and slicing it against a guessed geometry produces
     * plausible digits rather than an error. Listing the known names is what turns a misspelling into a
     * one-line diagnosis.</p>
     *
     * @param name the logical record name to resolve, matched exactly
     * @return the validated layout descriptor registered under that name
     * @throws LayoutException if no layout is registered under that name
     */
    public static RecordSpec layout(String name) {
        return registration(name).spec();
    }

    /**
     * Returns whether the named layout is transcribed from a copybook or derived from one that is.
     *
     * @param name the logical record name to resolve, matched exactly
     * @return the provenance recorded for that layout
     * @throws LayoutException if no layout is registered under that name
     */
    public static Provenance provenanceOf(String name) {
        return registration(name).provenance();
    }

    /**
     * Returns whether the parity oracle's own codec also registers a layout for the named record.
     *
     * <p>Assumptions: a false answer is a statement about the oracle and not about this layout's
     * correctness. It means a decode of that record has no second, independently written implementation
     * of the same geometry to be compared against, so its geometry rests on the copybook and the
     * dataset definition alone. Three of the eleven base masters answer false, and knowing which three
     * is what tells a reviewer where an independent cross-check is available and where it is not.</p>
     *
     * @param name the logical record name to resolve, matched exactly
     * @return true when the parity oracle's codec registers a layout for the same record
     * @throws LayoutException if no layout is registered under that name
     */
    public static boolean hasOracleRoundTrip(String name) {
        return registration(name).oracleRoundTrip();
    }

    /**
     * Returns every registered layout name, base masters first and then derived records.
     *
     * @return an unmodifiable list of the registered names in registration order
     */
    public static List<String> names() {
        return List.copyOf(REGISTRY.keySet());
    }

    /**
     * Returns the names of the layouts transcribed directly from a copybook.
     *
     * @return an unmodifiable list of the base-master names in registration order
     */
    public static List<String> baseMasterNames() {
        return namesWithProvenance(Provenance.BASE_MASTER);
    }

    /**
     * Returns the names of the layouts built from a base master rather than transcribed.
     *
     * @return an unmodifiable list of the derived names in registration order
     */
    public static List<String> derivedNames() {
        return namesWithProvenance(Provenance.DERIVED);
    }

    /**
     * Returns the names of the layouts transcribed from an IMS segment copybook.
     *
     * <p>Assumptions: this group is published as its own accessor rather than folded into
     * {@link #baseMasterNames()}, because the two groups carry different obligations. A base master has
     * a shipped flat extract whose byte count divides by its record length; a segment has neither, so a
     * caller iterating the base masters to verify extract lengths must not be handed these two.</p>
     *
     * @return an unmodifiable list of the IMS segment names in registration order
     */
    public static List<String> imsSegmentNames() {
        return namesWithProvenance(Provenance.IMS_SEGMENT);
    }

    /**
     * Returns the registered names carrying the given provenance, in registration order.
     *
     * <p>Assumptions: filtering the one registry rather than keeping two lists is what guarantees the
     * two populations partition it exactly. Two maintained lists could omit a name from both, and the
     * omission would be invisible because neither list would look short.</p>
     *
     * @param provenance the provenance to select
     * @return an unmodifiable list of the matching names in registration order
     */
    private static List<String> namesWithProvenance(Provenance provenance) {
        List<String> selected = new ArrayList<>();
        for (Map.Entry<String, Registration> entry : REGISTRY.entrySet()) {
            if (entry.getValue().provenance() == provenance) {
                selected.add(entry.getKey());
            }
        }
        return List.copyOf(selected);
    }

    /**
     * Resolves one registry entry by name, or reports the name as unknown.
     *
     * @param name the logical record name to resolve, matched exactly
     * @return the registration held under that name
     * @throws LayoutException if no layout is registered under that name, in which case the message
     *     lists every name that is registered
     */
    private static Registration registration(String name) {
        Registration found = REGISTRY.get(name);
        if (found == null) {
            throw new LayoutException("no record layout is registered under the name " + name
                    + "; the registered names are " + REGISTRY.keySet());
        }
        return found;
    }
}
