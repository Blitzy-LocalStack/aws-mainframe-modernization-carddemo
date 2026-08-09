package com.carddemo.authorization.mapper;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.dto.PendingAuthDetailResponse;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.codec.PackedDecimalCodec;
import com.carddemo.common.money.Money;
import com.carddemo.common.security.CardNumberMasker;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Carries the 200-byte pending-authorization detail segment to the entity and to the detail screen.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class is the crossing point between the stored form of a pending authorization and both of
 * its Java forms. It decodes the segment image into the {@link PendingAuthDetail} entity, encodes an
 * entity back into an image that is byte-identical to the one it came from, reads the six-byte
 * prefixed unload record the reference unload program writes, composes the 23-character authorization
 * timestamp that neither segment stores, and projects an entity onto the 27-component
 * {@link PendingAuthDetailResponse} the detail screen is specified by. It also defines the per-field
 * render rules the rest of this package shares.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a static holder with a private
 * constructor, so the type itself accepts no parameter, yields no value and raises nothing. The
 * inapplicability is stated rather than passed over, because user-specified Rule 1 (Explainability)
 * forbids at its line 39 a docstring that omits parameters, return values or purpose, and a reader has
 * to be able to tell a declared inapplicability from an oversight. Every member below carries its own
 * parameter, return and exception at-clauses.</p>
 *
 * <h2>Assumptions: the geometry is read from the registry and is never restated here</h2>
 *
 * <p>The layout of the segment is registered once, as {@code CopybookLayout.layout("PAUTDTL")}, and
 * this class holds no offset and no field width of its own. That registry entry transcribes
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} L19 to L54, whose declared widths sum to
 * exactly 200 and are corroborated independently by {@code ims/DBPAUTP0.dbd} L36,
 * {@code SEGM NAME=PAUTDTL1 ... BYTES=200}. The sum closes only because
 * {@code PIC S9(10)V99 COMP-3} at L34 and L35 occupies SEVEN bytes each and not six: twelve digit
 * positions plus one sign nibble is thirteen nibbles, and thirteen nibbles occupy the ceiling of
 * thirteen halved. At six bytes the record would total 198, which the declared 200 does not admit.</p>
 *
 * <p>Alternatives Considered: restating the twenty-eight offsets here as local constants, which would
 * let each mapping line read its own interval without a registry lookup. Rejected because an offset
 * declared twice can disagree with itself, and a segment read one byte out of alignment still decodes
 * to digits -- nothing raises, and the resulting dates and amounts are simply wrong. A single
 * declaration cannot disagree with anything, and {@code RecordSpec.validateGeometry} already proves
 * that declaration tiles the record exactly.</p>
 *
 * <h2>Assumptions: the two key components are stored as nines complements and MUST be decoded</h2>
 *
 * <p>The eight-byte composite key at {@code cpy/CIPAUDTY.cpy} L19 to L21 does not store the
 * authorization date and time. It stores their nines complements, and the {@code -9C} suffix on both
 * component names is the abbreviation of that. Four statements in the reference application establish
 * it, two encoding and two decoding, and all four are needed because either direction alone would
 * leave the base a guess:</p>
 *
 * <ul>
 *   <li>{@code cbl/COPAUA0C.cbl} L874 encodes the date as {@code 99999 - WS-YYDDD}, into the
 *       {@code PIC S9(05) COMP-3} component at {@code cpy/CIPAUDTY.cpy} L20, which occupies three
 *       bytes.</li>
 *   <li>{@code cbl/COPAUA0C.cbl} L875 encodes the time as {@code 999999999 - WS-TIME-WITH-MS}, into
 *       the {@code PIC S9(09) COMP-3} component at L21, which occupies five bytes.</li>
 *   <li>{@code cbl/CBPAUP0C.cbl} L280 decodes the date as {@code 99999 - PA-AUTH-DATE-9C}.</li>
 *   <li>{@code cbl/COPAUS2C.cbl} L107 decodes the time as {@code 999999999 - PA-AUTH-TIME-9C}.</li>
 * </ul>
 *
 * <p>Assumptions: the REASON for the complement is in the database description and not in any program,
 * which is why it is recorded here rather than left to be inferred from the arithmetic.
 * {@code ims/DBPAUTP0.dbd} L37 declares
 * {@code FIELD NAME=(PAUT9CTS,SEQ,U),START=1,BYTES=8,TYPE=C} -- a CHARACTER unique sequence field
 * spanning exactly the three plus five bytes of the packed key. A unique sequence field of that kind
 * orders its occurrences ASCENDING and offers no descending option, so complementing the value is the
 * only way the NEWEST authorization can be the first one read under its parent, whose own key is
 * declared at L30 as {@code FIELD NAME=(ACCNTID,SEQ,U),START=1,BYTES=6,TYPE=P}. Decoding the
 * complement is therefore a semantic step and not a formatting one.</p>
 *
 * <p>Assumptions: this class stores the DECODED values and the target reproduces the newest-first
 * order with an ordering clause instead. {@link PendingAuthDetailKey} takes a decoded Julian date and
 * a decoded millisecond time, and the migration declares the two columns as INTEGER with domain
 * constraints written over decoded values -- the date between 1 and 99366 with its day-of-year part
 * between 1 and 366, and the time between 0 and 235959999 with its minute and second parts each at
 * most 59. Storing the raw complement would satisfy neither constraint's intent while leaving the
 * composite primary key perfectly functional: every row would still insert and still be uniquely
 * addressable, and every rendered date, every rendered time and every ordering would be wrong. That is
 * the silent-corruption class the migration plan names at its section 0.7.3, an error that produces
 * plausible numbers rather than a failure. Newest-first is a property of the repository's
 * {@code ORDER BY auth_date DESC, auth_time DESC} and never of the persisted value.</p>
 *
 * <p>Assumptions: the same fact is why the relational side of this bounded context carries a
 * DESCENDING index rather than a complement. {@code ddl/XAUTHFRD.ddl} L3 orders its unique index
 * {@code (CARD_NUM ASC, AUTH_TS DESC)}, so the Db2 half of the reference application re-expresses in
 * an index exactly what the IMS half achieved by inverting the key. The two mechanisms are one
 * requirement seen twice, and the target adopts the index form for both.</p>
 *
 * <h2>Assumptions: the 23-character authorization timestamp exists in NEITHER segment</h2>
 *
 * <p>{@code cbl/COPAUS2C.cbl} L38 to L51 declares {@code WS-AUTH-TS} as a group of thirteen
 * subordinates: a two-character year, a hyphen, a two-character month, a hyphen, a two-character day,
 * a space, a two-character hour, a FULL STOP, a two-character minute, a full stop, a two-character
 * second, a three-character millisecond, and a three-character {@code FILLER} carrying
 * {@code VALUE '000'}. Those widths sum to 23, and the rendered form is
 * {@code YY-MM-DD HH.MI.SSsss000}. Three of its properties are ones a guess gets wrong: the
 * separators between the clock fields are full stops and not colons, the year is two digits and not
 * four, and the trailing three characters are a hard-coded literal. A {@code FILLER} carrying a
 * {@code VALUE} is CONTENT rather than padding, because the literal IS the data, so those three
 * characters are not droppable.</p>
 *
 * <p>The composed string is consumed by {@code TIMESTAMP_FORMAT (:AUTH-TS, 'YY-MM-DD HH24.MI.SSNNNNNN')}
 * at {@code cbl/COPAUS2C.cbl} L171 to L172 on the insert path and again at L227 to L228 on the update
 * path. That mask is itself 23 characters wide, and its six microsecond positions are fed by the three
 * real millisecond digits followed by the literal. The consequence is worth stating because it looks
 * like precision and is not: the stored timestamp has microsecond declared precision whose low three
 * fractional digits are ALWAYS ZERO, so a duration computed between two such values carries
 * millisecond resolution at best.</p>
 *
 * <p>Assumptions: {@code com.carddemo.common.time.TimestampFormatter} emits a DIFFERENT format and is
 * deliberately not used here. Its form is the 26-character {@code YYYY-MM-DD HH:MM:SS.mmmmmm} of the
 * base masters -- four year digits, colon separators between the clock fields, and six significant
 * fractional digits. Every one of those three differs from the form this class composes, so delegating
 * to it would emit a string {@code TIMESTAMP_FORMAT} cannot parse under the reference mask and would
 * silently change the stored instant's rendering. A second trap points the same way and is recorded so
 * it is not read as evidence for the 26-character form: {@code dcl/AUTHFRDS.dcl} L57 declares the host
 * variable as {@code AUTH-TS PIC X(26)}, which is 26 and not 23. The 23 composed characters are
 * left-justified into that 26-byte field and the three trailing bytes are blanks the conversion
 * function ignores, because the mask it is given is 23 characters long.</p>
 *
 * <h2>Assumptions: the composed timestamp mixes two clocks, and that is the baseline's design</h2>
 *
 * <p>The insert paragraph {@code 8500-INSERT-AUTH} of {@code cbl/COPAUA0C.cbl} populates the two
 * halves of this record from two different sources, and a reader who assumes one source produces a
 * timestamp that is internally inconsistent in a way no test written without this knowledge would
 * catch:</p>
 *
 * <ul>
 *   <li>L857 to L866 read the SERVER's clock, with {@code EXEC CICS ASKTIME} followed by
 *       {@code EXEC CICS FORMATTIME} requesting the day-of-year form, the time and the
 *       milliseconds.</li>
 *   <li>L868 takes the first five characters of that day-of-year form, and L869 with L871 to L872
 *       compose the millisecond-resolution time as
 *       {@code (WS-CUR-TIME-N6 * 1000) + WS-CUR-TIME-MS}.</li>
 *   <li>L874 to L875 complement those SERVER values into the key.</li>
 *   <li>L877 to L878, by contrast, copy the REQUESTER's declared first and second message fields
 *       straight into {@code PA-AUTH-ORIG-DATE} and {@code PA-AUTH-ORIG-TIME}.</li>
 * </ul>
 *
 * <p>{@code cbl/COPAUS2C.cbl} then composes the timestamp from the REQUESTER's originating date at its
 * L103 to L105 and the SERVER's decoded key time at its L107 to L111. One value therefore carries a
 * date the acquirer asserted and a time this system observed. This class reproduces that composition
 * unchanged, because the composed value is the key of an existing relational row and changing which
 * clock either half comes from would address a different row.</p>
 *
 * <h2>Assumptions: a decoded time must be zero-padded to nine digits before it is sliced</h2>
 *
 * <p>{@code cbl/COPAUS2C.cbl} L35 to L37 declares {@code WS-AUTH-TIME PIC 9(09)} and redefines it as
 * {@code WS-AUTH-TIME-AN PIC X(09)}, then slices the alphanumeric overlay at L108 to L111. A display
 * numeric item is stored zero-filled to its declared width, so that overlay is always nine characters
 * with leading zeros present. Rendering the decoded value without padding mis-slices every time before
 * ten in the morning: the shipped fixture {@code pautdtl1-time-leading-nines.bin} stores the
 * complement 999998999, which decodes to 1000, and nine-digit padding yields {@code 000001000} for one
 * second past midnight whereas an unpadded rendering yields {@code 1000} and reads the hour as ten. The
 * same case occurs in {@code unload-prefixed-detail-206.bin}, whose first record decodes to 91500000
 * and pads to {@code 091500000}.</p>
 *
 * <h2>Assumptions: packed widths and sign nibbles</h2>
 *
 * <p>A packed field occupies the ceiling of one plus its digit count, halved. Three digits occupy two
 * bytes, five occupy three, nine occupy five, eleven occupy six and twelve occupy seven. Both money
 * components of this segment declare twelve digit positions and therefore occupy seven bytes each, and
 * the parent key of the unload record declares eleven and therefore occupies six. The sign travels in
 * the low-order nibble of the final byte: {@code 0xC} is positive, {@code 0xD} is negative,
 * {@code 0xF} is positive-unsigned, and {@code 0xA}, {@code 0xB} and {@code 0xE} are alternate forms.
 * A value from {@code 0x0} to {@code 0x9} in the sign position is not a sign at all and is refused
 * rather than interpreted. Where the total digit count is odd the high nibble of the first byte is a
 * leading pad and must be zero. All of that arithmetic belongs to
 * {@link PackedDecimalCodec} and none of it is reimplemented here; it is recorded because the widths
 * are what make the ledger above close.</p>
 *
 * <p>Assumptions: no packed byte reaches a database column. This bounded context is the only one in
 * the migration whose PERSISTED data originates in packed decimal, so the decode happens once, at this
 * edge, and what travels onward is an exact decimal.</p>
 *
 * <h2>Assumptions: the unload record is a packed key ahead of an opaque segment image</h2>
 *
 * <p>{@code cbl/PAUDBUNL.CBL} L45 to L48 declares {@code FD OPFILE2} with {@code 01 OPFIL2-REC}
 * holding {@code 05 ROOT-SEG-KEY PIC S9(11) COMP-3} followed by {@code 05 CHILD-SEG-REC PIC X(200)},
 * so each record is six bytes of packed parent key ahead of 200 bytes of child image, 206 in total,
 * and its L230 populates that prefix by moving {@code PA-ACCT-ID} into it. The prefix is another packed
 * field to decode and never text to read: its bytes fall outside the digit range entirely.</p>
 *
 * <p>Alternatives Considered: registering a 206-byte layout beside the 200-byte one so the prefixed
 * form could be decoded in a single call. Rejected because the reference declaration itself treats the
 * child as an opaque {@code PIC X(200)} behind the key, so the prefixed form has no field geometry of
 * its own to register; a second registry entry would restate all twenty-eight of the child's offsets
 * for the sake of a six-byte prefix, and the two copies could then drift. Taking the prefix width from
 * {@code PackedDecimalCodec.packedWidth} and delegating the remainder to the one registered layout
 * keeps a single declaration and makes the stride arithmetic self-evident.</p>
 *
 * <h2>Trade-offs: the response is a projection of the screen and not a dump of the segment</h2>
 *
 * <p>{@link PendingAuthDetailResponse} has 27 components in the declaration order of the symbolic map
 * {@code cpy-bms/COPAU01.cpy}, whose field components run at six-line spacing from its L24 to its
 * L180. The segment also leaves 27 leaf components once its L19 group header and its L54 padding are
 * set aside, and the two counts are a coincidence rather than a correspondence: the two sets differ.
 * The response carries NO component for {@code PA-AUTH-ID-CODE}, {@code PA-ACQR-COUNTRY-CODE},
 * {@code PA-MESSAGE-TYPE}, the raw {@code PA-AUTH-RESP-CODE} or the raw
 * {@code PA-AUTH-ORIG-DATE} and {@code PA-AUTH-ORIG-TIME}, and it carries six components of screen
 * chrome the segment has no field for at all. The set it does carry is exactly what
 * {@code cbl/COPAUS1C.cbl} L295 to L342 moves to the map.</p>
 *
 * <p>The compromise accepted is that the response is narrower than the record behind it, so a caller
 * that needs a field the screen never showed has to read the entity. What that buys is that the
 * response cannot publish a value the form it mirrors has no place to display, which is the property
 * that makes it usable as the specification the browser screen implements. Its component widths come
 * from {@code cpy/CIPAUDTY.cpy} and not from the map, because six map fields are declared wider than
 * the data behind them while four more carry rendered forms of a different width again; the record is
 * normative and the map is presentation.</p>
 *
 * <h2>Refactoring Rationale: the merchant category code is respelled at the schema boundary</h2>
 *
 * <p>The reference application spells this field {@code CATAGORY} consistently, in four places and at
 * three different layers: {@code cpy/CIPAUDTY.cpy} L36 declares
 * {@code PA-MERCHANT-CATAGORY-CODE}, {@code cpy/CCPAURQY.cpy} L28 declares the message-payload form
 * {@code PA-RQ-MERCHANT-CATAGORY-CODE}, {@code ddl/AUTHFRDS.ddl} L14 declares the column
 * {@code MERCHANT_CATAGORY_CODE CHAR(4)} and {@code dcl/AUTHFRDS.dcl} L37 declares the matching host
 * variable. Both spellings are recorded here so that a search for either one reaches this note.</p>
 *
 * <p>Because the spelling reaches a PERSISTED column name, adopting {@code merchant_category_code} in
 * the target is a breaking change at the schema boundary rather than a cosmetic one, and it is
 * registered as such in {@code docs/architecture/data-model-and-schema-mapping.md} under AAP Rule T1
 * (Copybook is normative). The alternative was to carry the baseline spelling inward, which would have
 * cost nothing at the boundary and would have propagated a transcription artefact into every Java
 * member name, every column name and every generated contract downstream of it, where no reader could
 * tell it from an intended name. The reference files are read and never modified, so this paragraph
 * documents the CONSEQUENCE of the baseline structure and reaches no verdict about it. One further
 * limit is worth stating: the WIRE keeps the baseline spelling. Only internal and persisted names
 * change, because the message payload's field order and spelling are the interface an external
 * acquirer already speaks.
 *
 * <h2>Assumptions: the card number is masked here, and the reasons are in the resource definitions</h2>
 *
 * <p>Three independent facts about the reference application establish what the target adds, and all
 * three are properties of its configuration rather than faults in it -- the migration adds a path, it
 * does not remove one:</p>
 *
 * <ol>
 *   <li>{@code csd/CRDDEMO2.csd} enables diagnostics and does not classify the payload as
 *       confidential on all three of this extension's transactions: {@code DUMP(YES) TRACE(YES)} at
 *       L44, L54 and L64 with {@code CONFDATA(NO)} at L45, L55 and L65. A traced or dumped task could
 *       therefore emit a primary account number into diagnostic output.</li>
 *   <li>{@code cpy-bms/COPAU01.cpy} L60 declares {@code 02 CARDNUMI PIC X(16).}, so the detail screen
 *       displayed all sixteen digits.</li>
 *   <li>{@code csd/CRDDEMO2.csd} L46, L56 and L66 declare {@code RESSEC(NO) CMDSEC(NO)}, so no
 *       resource-level or command-level check stood between an operator and either of those.</li>
 * </ol>
 *
 * <p>The target masks the account number to its last four digits everywhere except an administrative
 * endpoint that is specified as showing more, and never returns a card verification value from any
 * endpoint at all. Masking belongs at THIS layer and not in the codec: the codec's own contract ends at
 * bytes decoded to a decimal or a string, and its layout descriptor's sensitive flag MARKS a field
 * without transforming it. Concentrating the transform at the one crossing between stored form and
 * transported form is what makes it inescapable, because a field cannot be serialised out of this
 * bounded context without passing through a signature declared in this package.</p>
 *
 * <p>Trade-offs: the mask itself is applied by {@code CardNumberMasker} in the shared kernel rather
 * than reimplemented here. The cost is a dependency on a type outside this module for a four-line
 * transform. What it buys is that the visible-tail length and the mask character are decided once for
 * the whole migration, so this context cannot mask to a different tail length than another context and
 * leave two endpoints disclosing different amounts of the same number. AAP Rule T2 places shared
 * concerns in the shared kernel for exactly this reason.</p>
 *
 * <h2>Assumptions: the trailing padding is dropped and the trailing blanks of a field are not</h2>
 *
 * <p>{@code cpy/CIPAUDTY.cpy} L54 declares {@code FILLER PIC X(17)}, which exists to reach the
 * declared 200-byte length and carries no data, so it reaches no entity member and no response
 * component. The drop is recorded per record in
 * {@code docs/architecture/data-model-and-schema-mapping.md} as AAP Rule T1 (Copybook is normative)
 * requires, and the reference application set the precedent itself: {@code ddl/AUTHFRDS.ddl} declares
 * no column for it. The encode direction restores it, because the physical record width is part of the
 * contract.</p>
 *
 * <p>Assumptions: trailing blanks INSIDE a field are a different matter and are preserved. They are
 * observable in exactly one column of this bounded context, {@code MERCHANT_NAME}, which
 * {@code ddl/AUTHFRDS.ddl} L18 declares {@code VARCHAR(22)} while every other text column of that
 * table is {@code CHAR(n)}; and {@code cbl/COPAUS2C.cbl} L130 sets that VARCHAR's length prefix from
 * {@code LENGTH OF PA-MERCHANT-NAME}, which is the compile-time constant 22 rather than the length of
 * the content, with no trimming anywhere on the path. Text decoded here is therefore handed on at its
 * declared width and is never trimmed.</p>
 *
 * <h2>Assumptions: the fraud pair arrives blank rather than absent</h2>
 *
 * <p>{@code cbl/COPAUA0C.cbl} L908 to L909 move {@code SPACE} into both {@code PA-AUTH-FRAUD} and
 * {@code PA-FRAUD-RPT-DATE} on EVERY insert, and those two lines are the only writes to either field
 * on that path. The report date is therefore eight spaces rather than a date, and eight spaces cannot
 * be parsed as the month-first form the field otherwise holds. Two consequences follow and both are
 * decisions this class makes explicitly, because neither is recoverable from the schema or from the
 * segment:</p>
 *
 * <ul>
 *   <li>An all-blank report date becomes SQL {@code NULL} on the relational fraud row, whose
 *       {@code fraud_rpt_date} is a nullable {@code DATE} for exactly this reason, while the segment
 *       equivalent on the detail row is {@code CHAR(8)} and keeps the eight characters themselves.
 *       Those are two representations of one value and the reference application has the same two.</li>
 *   <li>A blank fraud flag is left UNSET rather than written as a space or as a null, because the
 *       entity's constructor accepts neither fraud member and its
 *       {@code applyFraudMark} operation admits only the two marking values. The migration's check
 *       constraint tolerates a space as well as a null so that an extract-loaded blank does not fail
 *       it, so both are legal to store; this class writes neither, which leaves the member at the
 *       state the entity itself documents as never-examined. Choosing a space would assert that this
 *       deployment had examined the authorization and found nothing, and choosing a null would erase
 *       the distinction the entity draws between a loaded blank and an absent value.</li>
 * </ul>
 *
 * <h2>Trade-offs: the render rules are defined here for the whole package</h2>
 *
 * <p>The summary response's row components draw their values from the DETAIL segment, and the fraud row
 * needs the composed timestamp, so more than one mapper in this package needs the same per-field
 * rules. They are defined here, package-private, and consumed by the siblings. The compromise accepted
 * is package-private coupling between mappers rather than four self-contained classes. What it buys is
 * one definition per representation concern, which is the property the package charter beside this file
 * exists to hold; two definitions of a date re-ordering in one package would be two places it could be
 * got wrong and one place a reader would not think to look.</p>
 *
 * <p>Trade-offs: the same reasoning runs the other way for two rules that already have a home, and both
 * are consumed rather than restated. The approved-or-declined character is derived by
 * {@code PendingAuthViewMapper.approvalStatusOf}, and the account-number mask by
 * {@code CardNumberMasker.mask}. Redefining either would give this package two answers to one question,
 * and a divergence between them would surface as two endpoints disagreeing about the same
 * authorization rather than as a failure anywhere.</p>
 *
 * <h2>Trade-offs: decoding to an entity inherits the entity's origination invariant</h2>
 *
 * <p>{@code cpy/CIPAUDTY.cpy} L46 to L49 close the match-status domain to P, D, E and M, and
 * {@code cbl/COPAUA0C.cbl} L902 to L906 show that the insert path reaches only the first two. The
 * entity encodes that split: its constructor admits only the two statuses an insert originates, while
 * the migration's check constraint admits all four so a column can hold every state a row reaches.
 * Decoding a segment that carries E or M into an entity through that constructor is therefore refused,
 * and the refusal is the entity's own.</p>
 *
 * <p>The compromise accepted is that the entity-producing decode covers three of the four states a
 * stored segment can be in, so a caller inspecting expired or matched occurrences uses
 * {@link #toSegmentFields(byte[])} instead and gets every field of every state. What it buys is that no
 * path in this package can fabricate an entity asserting an outcome no insert produced. The alternative
 * -- widening the entity to originate all four -- was rejected because it would make a status the
 * purge job and the transaction match are supposed to reach constructible by a message handler, and
 * nothing downstream could then tell an originated E from a transitioned one.</p>
 *
 * <h2>Alternatives Considered: two libraries deliberately not adopted</h2>
 *
 * <p>Alternatives Considered: a generated mapping framework, specifically MapStruct. Rejected on two
 * independent grounds. Its most recent published release is a beta, and, decisively, this mapping is
 * not mechanical: it decodes packed decimal and separately decodes a nines complement, composes one
 * timestamp from two disagreeing clocks, zero-pads a numeric before slicing it, re-orders a
 * six-character date, applies a target spelling in place of a baseline one, masks an account number,
 * suppresses a verification value outright, distinguishes a blank that becomes null from one that stays
 * blank, and refuses two of the four values one field admits. Each of those is a decision that needs a
 * justification recorded at the mapping site, and a generated mapper has nowhere to hold one. Adopting
 * the framework would not save the documentation work; it would delete the place the documentation
 * goes.</p>
 *
 * <p>Alternatives Considered: Lombok. Rejected because generated accessors and constructors cannot
 * carry the Javadoc user-specified Rule 1 (Explainability) requires of a member, so a class built that
 * way either fails the documentation gate or has to be exempted from it in a suppression file whose
 * charter admits no such entry. Java 21 {@code record} types with explicit constructors give the same
 * brevity with members that can be documented.</p>
 *
 * <h2>Assumptions: parity evidence available to this class, stated plainly</h2>
 *
 * <p>No golden master exists for any path in this bounded context and none may be claimed for one.
 * {@code tests/README.md} L83 to L85 records that the online CICS programs cannot run end to end
 * without a CICS runtime, which the runner does not have, and that only their extractable
 * field-validation logic is unit-tested. The message producer is not supplied by the reference
 * application, only a stub. And {@code cbl/COPAUA0C.cbl} cannot be compiled at all: it names six
 * distinct vendor message-queue copybooks across its L149, L152, L155, L158, L161, L164, L167 and
 * L170, and a case-insensitive search for them across the repository returns nothing. Every claim this
 * class makes therefore rests on the copybook, database-description and data-definition contracts cited
 * above and on logic transcribed from the reference programs, and it rests on committed byte fixtures
 * decoded against those contracts. It does not rest on a recorded output comparison.</p>
 *
 * <h2>Assumptions: one construct in the reference tree is inventoried and not ported</h2>
 *
 * <p>{@code cbl/PAUDBLOD.CBL} L307 and L308 are two statements that add 2 to each half of the packed
 * authorization key. Both carry an asterisk in column 7, so both are commented out and neither
 * executes. They are recorded here so a reader looking for their Java equivalent stops looking, and
 * they are not reproduced: adding a constant to a complemented key would shift the decoded date by two
 * days and the decoded time by two milliseconds in the direction opposite to the one the arithmetic
 * suggests, and no executing statement anywhere in the reference tree does it.</p>
 */
public final class PendingAuthDetailMapper {

    /**
     * The registry name under which the 200-byte detail segment's geometry is declared.
     *
     * <p>Assumptions: the name is the one the database description gives the segment at
     * {@code ims/DBPAUTP0.dbd} L36, less its trailing occurrence digit, and the registry entry it
     * resolves is the only declaration of this record's offsets in the migration.</p>
     */
    static final String SEGMENT_LAYOUT_NAME = "PAUTDTL";

    /**
     * The base the reference application subtracts from to complement the authorization date.
     *
     * <p>Assumptions: five nines, matching the five digit positions of {@code PIC S9(05) COMP-3} at
     * {@code cpy/CIPAUDTY.cpy} L20. It appears as the literal in the encode at
     * {@code cbl/COPAUA0C.cbl} L874 and in the decode at {@code cbl/CBPAUP0C.cbl} L280, so the same
     * constant serves both directions and the round trip is an identity.</p>
     */
    static final int AUTH_DATE_COMPLEMENT_BASE = 99_999;

    /**
     * The base the reference application subtracts from to complement the authorization time.
     *
     * <p>Assumptions: nine nines, matching the nine digit positions of {@code PIC S9(09) COMP-3} at
     * {@code cpy/CIPAUDTY.cpy} L21. It appears as the literal in the encode at
     * {@code cbl/COPAUA0C.cbl} L875 and in the decode at {@code cbl/COPAUS2C.cbl} L107.</p>
     */
    static final int AUTH_TIME_COMPLEMENT_BASE = 999_999_999;

    /**
     * The declared width of the composed authorization timestamp, in characters.
     *
     * <p>Assumptions: 23, being the sum of the thirteen subordinate widths of {@code WS-AUTH-TS} at
     * {@code cbl/COPAUS2C.cbl} L38 to L51, and equally the width of the conversion mask at its L171
     * to L172. The two agreeing is what makes the value parseable by the reference expression.</p>
     */
    static final int AUTH_TIMESTAMP_LENGTH = 23;

    /**
     * The digit positions a decoded authorization time is zero-padded to before it is sliced.
     *
     * <p>Assumptions: nine, from {@code WS-AUTH-TIME PIC 9(09)} at {@code cbl/COPAUS2C.cbl} L35. The
     * padding is load-bearing rather than cosmetic, for the reason recorded on
     * {@link #paddedTimeDigits(int)}.</p>
     */
    static final int TIME_DIGIT_POSITIONS = 9;

    /**
     * The declared width of the originating date and of the originating time, in characters.
     *
     * <p>Assumptions: six each, from {@code PA-AUTH-ORIG-DATE} and {@code PA-AUTH-ORIG-TIME} at
     * {@code cpy/CIPAUDTY.cpy} L22 and L23. The two share a constant because both are sliced two
     * characters at a time into three parts.</p>
     */
    static final int ORIGINATING_FIELD_LENGTH = 6;

    /**
     * The declared width of the card expiry field, in characters.
     *
     * <p>Assumptions: four, from {@code PA-CARD-EXPIRY-DATE} at {@code cpy/CIPAUDTY.cpy} L26, holding
     * a two-digit month followed by a two-digit year.</p>
     */
    static final int CARD_EXPIRY_LENGTH = 4;

    /**
     * The declared width of the fraud report date, in characters.
     *
     * <p>Assumptions: eight, from {@code PA-FRAUD-RPT-DATE} at {@code cpy/CIPAUDTY.cpy} L53, holding
     * the month-first separated form the reference application formats at
     * {@code cbl/COPAUS2C.cbl} L95 to L101. The entity's own marking operation requires exactly this
     * width.</p>
     */
    static final int FRAUD_REPORT_DATE_LENGTH = 8;

    /**
     * The digit positions of the packed parent key that prefixes an unload record.
     *
     * <p>Assumptions: eleven, from {@code ROOT-SEG-KEY PIC S9(11) COMP-3} at
     * {@code cbl/PAUDBUNL.CBL} L47. Eleven digits plus a sign nibble occupy six bytes, which is what
     * makes the unload stride 206 rather than some other number.</p>
     */
    static final int UNLOAD_ROOT_KEY_DIGITS = 11;

    /**
     * The integer digit positions each money component of this segment declares.
     *
     * <p>Assumptions: ten, from {@code PIC S9(10)V99 COMP-3} at {@code cpy/CIPAUDTY.cpy} L34 and L35.
     * It bounds the amount at the picture rather than at the widest picture in the corpus, which is
     * what {@code Money.ofPicture} exists to express.</p>
     */
    static final int MONEY_INTEGER_DIGITS = 10;

    /**
     * The decimal digit positions each money component of this segment declares.
     *
     * <p>Assumptions: two, the {@code V99} of {@code PIC S9(10)V99 COMP-3}, and therefore the scale
     * every decoded amount carries and the scale of the {@code NUMERIC(12,2)} column behind it.</p>
     */
    static final int MONEY_DECIMAL_DIGITS = 2;

    /**
     * The digit positions the processing code declares.
     *
     * <p>Assumptions: six, from {@code PA-PROCESSING-CODE PIC 9(06)} at {@code cpy/CIPAUDTY.cpy} L33.
     * It is an unsigned DISPLAY numeric, so the shared codec decodes it to an integral value and the
     * leading zeros of the stored form have to be restored when it is rendered back to characters.</p>
     */
    static final int PROCESSING_CODE_DIGITS = 6;

    /**
     * The digit positions the point-of-sale entry mode declares.
     *
     * <p>Assumptions: two, from {@code PA-POS-ENTRY-MODE PIC 9(02)} at {@code cpy/CIPAUDTY.cpy} L38,
     * which is why the entity bounds the value to the range zero through ninety-nine.</p>
     */
    static final int POS_ENTRY_MODE_DIGITS = 2;

    /**
     * The two-digit year at or above which a year belongs to the twentieth century.
     *
     * <p>Assumptions: seventy, and the value is not chosen here. The reference application stores two
     * digits and never widens them, so a pivot has to be supplied by the migration rather than read
     * from the data; this module has already chosen seventy in its fraud-marking service, and matching
     * it is what keeps one authorization from resolving to two different years depending on which code
     * path widened it. A different pivot in this class would be observable only as two components of
     * one response disagreeing about the century.</p>
     */
    static final int CENTURY_PIVOT = 70;

    /**
     * The century added to a two-digit year at or above the pivot.
     *
     * <p>Assumptions: nineteen hundred, paired with {@link #CENTURY_PIVOT} and with
     * {@link #TWENTY_FIRST_CENTURY} so all three parts of the widening rule are read together.</p>
     */
    static final int TWENTIETH_CENTURY = 1900;

    /**
     * The century added to a two-digit year below the pivot.
     *
     * <p>Assumptions: two thousand, which is the branch every date in the committed fixtures takes,
     * their two-digit years being 23 and 24.</p>
     */
    static final int TWENTY_FIRST_CENTURY = 2000;

    /**
     * The number of milliseconds in one second.
     *
     * <p>Assumptions: the decoded time is a millisecond-resolution time of day, because
     * {@code cbl/COPAUA0C.cbl} L871 to L872 build it as the six-digit clock time multiplied by this
     * value plus the milliseconds. Dividing by it recovers the clock time.</p>
     */
    static final int MILLIS_PER_SECOND = 1000;

    /**
     * The modulus that separates one two-digit clock field from the next.
     *
     * <p>Assumptions: one hundred, because the decoded time packs hours, minutes and seconds two
     * digits each, so each field is recovered by one division and one remainder against this
     * value.</p>
     */
    static final int CLOCK_FIELD_MODULUS = 100;

    /**
     * The number of nanoseconds in one millisecond.
     *
     * <p>Assumptions: one million. The composed instant carries millisecond resolution, and the
     * platform's date-time type takes nanoseconds, so the three real digits are scaled by this value
     * and the six remaining nanosecond positions are zero -- which is the same zeroing the reference
     * application performs literally with its trailing {@code VALUE '000'}.</p>
     */
    static final int NANOS_PER_MILLI = 1_000_000;

    /**
     * The literal the reference application appends to the composed timestamp.
     *
     * <p>Assumptions: three zero characters, from the {@code FILLER PIC X(03) VALUE '000'} at
     * {@code cbl/COPAUS2C.cbl} L51. It is CONTENT and not padding, because the literal is the data:
     * the conversion mask at L171 to L172 reads six microsecond positions and only three of them are
     * ever significant.</p>
     */
    static final String MILLISECOND_MICROSECOND_PAD = "000";

    /**
     * The response's rendering of a fraud position that has never been examined.
     *
     * <p>Assumptions: a bare hyphen, from the unconditional {@code ELSE} at
     * {@code cbl/COPAUS1C.cbl} L349, which moves a single hyphen into a ten-byte display field. The
     * remaining nine bytes of that field are blanks the terminal shows as blanks, so the hyphen alone
     * carries the whole of the displayed value.</p>
     */
    static final String FRAUD_MARK_ABSENT = "-";

    /**
     * The response reason code the reference application substitutes when its lookup finds no entry.
     *
     * <p>Assumptions: four nines, from {@code cbl/COPAUS1C.cbl} L321. The fallback replaces the stored
     * reason code as well as the description, so both halves of the composed value change together and
     * neither is carried through from the segment.</p>
     */
    static final String UNKNOWN_REASON_CODE = "9999";

    /**
     * The response reason description the reference application substitutes when its lookup fails.
     *
     * <p>Assumptions: the word the reference application writes at {@code cbl/COPAUS1C.cbl} L323,
     * carried across character for character under AAP Rule T8 (user-visible strings verbatim).</p>
     */
    static final String UNKNOWN_REASON_DESCRIPTION = "ERROR";

    // WHY : Assumptions: the copybook's own field names are held as constants rather than repeated as
    //       literals at each mapping site because every one of them is used on BOTH the decode and the
    //       encode path, and the shared codec addresses fields by name through a map. A name misspelled
    //       on one of the two paths compiles, and then fails at run time on a lookup that returns
    //       nothing -- or, on the encode side, silently omits a field the record needs. A constant makes
    //       the same mistake a compilation error instead.
    /** The copybook name of the complemented authorization date, the first key component. */
    private static final String FIELD_AUTH_DATE_COMPLEMENT = "PA-AUTH-DATE-9C";

    /** The copybook name of the complemented authorization time, the second key component. */
    private static final String FIELD_AUTH_TIME_COMPLEMENT = "PA-AUTH-TIME-9C";

    /** The copybook name of the acquirer-declared originating date. */
    private static final String FIELD_AUTH_ORIG_DATE = "PA-AUTH-ORIG-DATE";

    /** The copybook name of the acquirer-declared originating time. */
    private static final String FIELD_AUTH_ORIG_TIME = "PA-AUTH-ORIG-TIME";

    /** The copybook name of the primary account number. */
    private static final String FIELD_CARD_NUM = "PA-CARD-NUM";

    /** The copybook name of the authorization type. */
    private static final String FIELD_AUTH_TYPE = "PA-AUTH-TYPE";

    /** The copybook name of the card expiry, held as a month and a two-digit year. */
    private static final String FIELD_CARD_EXPIRY_DATE = "PA-CARD-EXPIRY-DATE";

    /** The copybook name of the message type. */
    private static final String FIELD_MESSAGE_TYPE = "PA-MESSAGE-TYPE";

    /** The copybook name of the message source. */
    private static final String FIELD_MESSAGE_SOURCE = "PA-MESSAGE-SOURCE";

    /** The copybook name of the authorization identification code. */
    private static final String FIELD_AUTH_ID_CODE = "PA-AUTH-ID-CODE";

    /** The copybook name of the authorization response code. */
    private static final String FIELD_AUTH_RESP_CODE = "PA-AUTH-RESP-CODE";

    /** The copybook name of the authorization response reason. */
    private static final String FIELD_AUTH_RESP_REASON = "PA-AUTH-RESP-REASON";

    /** The copybook name of the processing code. */
    private static final String FIELD_PROCESSING_CODE = "PA-PROCESSING-CODE";

    /** The copybook name of the requested transaction amount. */
    private static final String FIELD_TRANSACTION_AMT = "PA-TRANSACTION-AMT";

    /** The copybook name of the approved amount. */
    private static final String FIELD_APPROVED_AMT = "PA-APPROVED-AMT";

    /** The copybook name of the merchant category code, in the baseline spelling. */
    private static final String FIELD_MERCHANT_CATEGORY_CODE = "PA-MERCHANT-CATAGORY-CODE";

    /** The copybook name of the acquirer country code. */
    private static final String FIELD_ACQR_COUNTRY_CODE = "PA-ACQR-COUNTRY-CODE";

    /** The copybook name of the point-of-sale entry mode. */
    private static final String FIELD_POS_ENTRY_MODE = "PA-POS-ENTRY-MODE";

    /** The copybook name of the merchant identifier. */
    private static final String FIELD_MERCHANT_ID = "PA-MERCHANT-ID";

    /** The copybook name of the merchant name, the one field whose trailing blanks are observable. */
    private static final String FIELD_MERCHANT_NAME = "PA-MERCHANT-NAME";

    /** The copybook name of the merchant city. */
    private static final String FIELD_MERCHANT_CITY = "PA-MERCHANT-CITY";

    /** The copybook name of the merchant state. */
    private static final String FIELD_MERCHANT_STATE = "PA-MERCHANT-STATE";

    /** The copybook name of the merchant postal code. */
    private static final String FIELD_MERCHANT_ZIP = "PA-MERCHANT-ZIP";

    /** The copybook name of the transaction identifier. */
    private static final String FIELD_TRANSACTION_ID = "PA-TRANSACTION-ID";

    /** The copybook name of the match status. */
    private static final String FIELD_MATCH_STATUS = "PA-MATCH-STATUS";

    /** The copybook name of the fraud indicator. */
    private static final String FIELD_AUTH_FRAUD = "PA-AUTH-FRAUD";

    /** The copybook name of the fraud report date. */
    private static final String FIELD_FRAUD_RPT_DATE = "PA-FRAUD-RPT-DATE";

    // WHY : Assumptions: the layout is resolved once into an immutable descriptor rather than looked up
    //       per call. The registry hands back a record whose field list it has already copied
    //       defensively, so holding the reference introduces no shared mutable state, and resolving it
    //       here means a registry name this class gets wrong fails at class initialisation instead of on
    //       the first record decoded in production.
    /** The registered geometry of the 200-byte detail segment, resolved once at class initialisation. */
    private static final CopybookLayout.RecordSpec SEGMENT =
            CopybookLayout.layout(SEGMENT_LAYOUT_NAME);

    // WHY : Assumptions: the prefix width is COMPUTED from the declared digit count rather than written
    //       as six, so the unload stride is derived from the same rule that governs every other packed
    //       field in the migration. Writing the six directly would state a result whose derivation a
    //       reader would have to reconstruct, and would not move if the declaration ever did.
    /** The byte width of the packed parent key that prefixes each unload record. */
    private static final int UNLOAD_ROOT_KEY_WIDTH =
            PackedDecimalCodec.packedWidth(UNLOAD_ROOT_KEY_DIGITS, 0);

    /**
     * Refuses construction, this class holding only static members.
     *
     * <p>Assumptions: every operation here is a pure function of its arguments and holds no state, so
     * an instance would carry nothing. The constructor is declared rather than defaulted because a
     * defaulted one is public on a public class and would make the type look instantiable.</p>
     */
    private PendingAuthDetailMapper() {
    }

    /**
     * Returns the declared byte length of one detail segment image.
     *
     * @return the segment length in bytes, which is 200 for as long as the registered layout says so
     */
    public static int segmentLength() {
        return SEGMENT.reclen();
    }

    /**
     * Returns the declared byte length of one prefixed unload record.
     *
     * <p>Assumptions: the length is the packed parent key's width added to the segment's, which is 206.
     * It is exposed because a caller reading an unload file needs the stride to divide the file by, and
     * deriving it independently is how a reader ends up dividing a 206-byte file by 200 and reporting a
     * remainder as corruption.</p>
     *
     * @return the prefixed unload record length in bytes
     */
    public static int unloadRecordLength() {
        return UNLOAD_ROOT_KEY_WIDTH + SEGMENT.reclen();
    }

    /**
     * Decodes the stored nines complement of the authorization date into the Julian date it stands for.
     *
     * <p>Assumptions: the arithmetic is {@code 99999} minus the stored value and it is the reference
     * application's own, established in both directions so the base is not a guess. The encode is
     * {@code cbl/COPAUA0C.cbl} L874, {@code COMPUTE PA-AUTH-DATE-9C = 99999 - WS-YYDDD}, and the decode
     * is {@code cbl/CBPAUP0C.cbl} L280, {@code COMPUTE WS-AUTH-DATE = 99999 - PA-AUTH-DATE-9C}. The
     * REASON the value is inverted at all is {@code ims/DBPAUTP0.dbd} L37, which declares the eight
     * packed key bytes as a CHARACTER unique sequence field; such a field orders ascending only, so
     * inverting the value is what makes the newest occurrence the first one read.</p>
     *
     * <p>Assumptions: the result is a Julian date, a two-digit year followed by a day of year, because
     * {@code cbl/COPAUA0C.cbl} L868 takes it from the day-of-year form the platform returns. It is not
     * a calendar date and its parts are not a month and a day. A shipped fixture proves the reading
     * independently: {@code pautdtl1-canonical.bin} stores the complement 76819, which decodes to
     * 23180, and its separately stored originating date is {@code 230629} -- day 180 of 2023 is the
     * twenty-ninth of June.</p>
     *
     * @param storedComplement the value held in the first key component, as decoded from its packed
     *     bytes; must be between zero and {@code 99999} inclusive
     * @return the Julian authorization date the complement stands for, which the entity's key and the
     *     migration's INTEGER column both hold in this decoded form
     * @throws IllegalArgumentException if {@code storedComplement} lies outside the range the
     *     complement base admits, which would produce a negative date rather than a date
     */
    static int decodeAuthDate(int storedComplement) {
        return complement(AUTH_DATE_COMPLEMENT_BASE, storedComplement, "authorization date");
    }

    /**
     * Encodes a Julian authorization date into the nines complement the segment stores.
     *
     * <p>Assumptions: this is the same subtraction as {@link #decodeAuthDate(int)} because the
     * operation is its own inverse, and that identity is the cheapest available proof the decode is
     * right: {@code 99999 - (99999 - d)} is {@code d} for every admissible {@code d}. The two are kept
     * as separate named operations rather than collapsed into one, so a call site reads as the
     * direction it means; a single method would leave every caller's intent to be inferred from
     * context.</p>
     *
     * @param julianDate the decoded Julian authorization date, a two-digit year followed by a day of
     *     year; must be between zero and {@code 99999} inclusive
     * @return the complemented value to write into the first key component
     * @throws IllegalArgumentException if {@code julianDate} lies outside the range the complement base
     *     admits, which no valid Julian date does
     */
    static int encodeAuthDate(int julianDate) {
        return complement(AUTH_DATE_COMPLEMENT_BASE, julianDate, "authorization date");
    }

    /**
     * Decodes the stored nines complement of the authorization time into the time it stands for.
     *
     * <p>Assumptions: the arithmetic is {@code 999999999} minus the stored value, encoded at
     * {@code cbl/COPAUA0C.cbl} L875 and decoded at {@code cbl/COPAUS2C.cbl} L107. The result is a
     * millisecond-resolution time of day rather than a clock time: {@code cbl/COPAUA0C.cbl} L871 to
     * L872 build it as the six-digit clock time multiplied by one thousand plus the milliseconds, so
     * its nine digit positions are two hours, two minutes, two seconds and three milliseconds.</p>
     *
     * @param storedComplement the value held in the second key component, as decoded from its packed
     *     bytes; must be between zero and {@code 999999999} inclusive
     * @return the decoded millisecond-resolution authorization time
     * @throws IllegalArgumentException if {@code storedComplement} lies outside the range the
     *     complement base admits
     */
    static int decodeAuthTime(int storedComplement) {
        return complement(AUTH_TIME_COMPLEMENT_BASE, storedComplement, "authorization time");
    }

    /**
     * Encodes a decoded authorization time into the nines complement the segment stores.
     *
     * @param millisecondOfDay the decoded millisecond-resolution authorization time; must be between
     *     zero and {@code 999999999} inclusive
     * @return the complemented value to write into the second key component
     * @throws IllegalArgumentException if {@code millisecondOfDay} lies outside the range the
     *     complement base admits
     */
    static int encodeAuthTime(int millisecondOfDay) {
        return complement(AUTH_TIME_COMPLEMENT_BASE, millisecondOfDay, "authorization time");
    }

    /**
     * Subtracts a value from a nines-complement base, refusing a value the base cannot complement.
     *
     * <p>Assumptions: the guard is a RANGE check and deliberately not a calendar or clock check. A
     * value outside zero through the base cannot be what the field holds, because the field's own
     * picture admits no more digit positions than the base has nines, so the arithmetic itself would be
     * meaningless. Whether the decoded result names a real day of year or a real time of day is a
     * separate question that is answered where the parts are used -- the migration's two domain
     * constraints answer it at the column, and composing an instant answers it by refusing an
     * impossible one. Folding both checks in here would refuse reference data at the decode boundary on
     * grounds the reference application itself never applied.</p>
     *
     * @param base the complement base, five nines for the date component and nine for the time
     * @param value the value to complement, in either direction
     * @param label the human-readable name of the component, used only to name the field in a refusal
     * @return the difference between the base and the value, which is the other side of the complement
     * @throws IllegalArgumentException if {@code value} is negative or exceeds {@code base}
     */
    private static int complement(int base, int value, String label) {
        if (value < 0 || value > base) {
            throw new IllegalArgumentException("the " + label + " component must be between 0 and "
                    + base + " for the nines complement to be meaningful, but was " + value);
        }
        return base - value;
    }

    /**
     * Renders a decoded authorization time as the nine zero-padded digits the reference slices.
     *
     * <p>Assumptions: the padding is required and is not presentation. {@code cbl/COPAUS2C.cbl} L35 to
     * L37 declare {@code WS-AUTH-TIME PIC 9(09)} and redefine it as {@code WS-AUTH-TIME-AN PIC X(09)},
     * and L108 to L111 slice that alphanumeric overlay at constant positions. A display numeric is stored
     * zero-filled to its declared width, so the overlay always presents nine characters. Rendering
     * without padding mis-slices every time before ten in the morning, and it mis-slices SILENTLY: the
     * committed fixture {@code pautdtl1-time-leading-nines.bin} decodes to 1000, which pads to
     * {@code 000001000} and reads as one second past midnight, whereas an unpadded {@code 1000} reads
     * as the tenth hour. Both are well-formed times and only one is the stored one.</p>
     *
     * @param decodedTime the decoded millisecond-resolution authorization time; must be between zero
     *     and {@code 999999999} inclusive
     * @return exactly nine digit characters, zero-filled on the left
     * @throws IllegalArgumentException if {@code decodedTime} is negative or needs more than nine digit
     *     positions, either of which would render a string those constant slices cannot address
     */
    static String paddedTimeDigits(int decodedTime) {
        if (decodedTime < 0 || decodedTime > AUTH_TIME_COMPLEMENT_BASE) {
            throw new IllegalArgumentException("a decoded authorization time must occupy at most "
                    + TIME_DIGIT_POSITIONS + " digit positions, but was " + decodedTime);
        }
        return String.format("%0" + TIME_DIGIT_POSITIONS + "d", decodedTime);
    }

    /**
     * Widens a two-digit year to a calendar year using the module's pivot.
     *
     * <p>Assumptions: the reference application stores two digits and never widens them, so the pivot
     * is supplied by the migration rather than read from the data. Seventy is used because this module
     * has already chosen seventy elsewhere, and agreeing with it is the whole point: two pivots in one
     * module would resolve one stored authorization to two different years depending on which code path
     * widened it, and the disagreement would surface as two views of the same row showing different
     * centuries rather than as any failure.</p>
     *
     * <p>Refactoring Rationale: BOTH branches are reachable from committed data and both are exercised,
     * which is a correction rather than an elaboration. This paragraph previously read "Every two-digit
     * year in the committed fixtures is 23 or 24 and therefore takes the twenty-first-century branch",
     * and {@code pautdtl1-date-formats.bin} falsified it: its second record stores {@code 991231}, whose
     * year of 99 is at or above the pivot and resolves to 1999. The statement mattered more than its
     * size, because a reader who believed the twentieth-century branch unreachable in practice could
     * have removed it as dead. {@code PendingAuthDetailDatePivotFixtureTest} now drives that record and
     * asserts 1999 rather than 2099, so the branch has an oracle and this paragraph cannot go stale in
     * the same direction again -- a fixture year crossing the pivot fails there.</p>
     *
     * @param twoDigitYear the two digits as stored; must be between zero and ninety-nine inclusive
     * @return the four-digit calendar year the pivot assigns
     * @throws IllegalArgumentException if {@code twoDigitYear} is outside zero through ninety-nine,
     *     which two stored characters cannot be
     */
    static int calendarYearOf(int twoDigitYear) {
        if (twoDigitYear < 0 || twoDigitYear > 99) {
            throw new IllegalArgumentException("a two-digit year must be between 0 and 99, but was "
                    + twoDigitYear);
        }
        return (twoDigitYear >= CENTURY_PIVOT ? TWENTIETH_CENTURY : TWENTY_FIRST_CENTURY)
                + twoDigitYear;
    }

    /**
     * Composes the 23-character authorization timestamp from an originating date and a decoded time.
     *
     * <p>Assumptions: the form is {@code YY-MM-DD HH.MI.SSsss000} and every one of its parts is taken
     * from {@code cbl/COPAUS2C.cbl}. The group at L38 to L51 sets the shape: two-digit year, hyphen,
     * month, hyphen, day, space, hour, FULL STOP, minute, full stop, second, three milliseconds, and a
     * three-character literal. The date parts are sliced at L103 to L105 in the order year, month, day,
     * which is what establishes that {@code PA-AUTH-ORIG-DATE} is stored year-first; the time parts are
     * sliced at L108 to L111 from the zero-padded decoded value. The whole is bound to the host variable
     * at L114 and read back by the mask {@code 'YY-MM-DD HH24.MI.SSNNNNNN'} at L171 to L172. Two
     * separator choices here are the ones a guess inverts: the clock separator is a full stop and not a
     * colon, and the year is two digits and not four.</p>
     *
     * <p>Assumptions: the two halves come from DIFFERENT clocks and this method reproduces that rather
     * than reconciling it. The date is the acquirer's declared value, copied straight in at
     * {@code cbl/COPAUA0C.cbl} L877; the time is this system's own, observed at L857 to L866, composed
     * at L871 to L872 and complemented into the key at L875. The composed value is the key of an
     * existing relational row, so drawing either half from the other clock would address a different
     * row.</p>
     *
     * <p>Assumptions: the trailing literal is CONTENT. It is a {@code FILLER} carrying
     * {@code VALUE '000'} at L51, and the conversion mask reads six microsecond positions of which only
     * the first three are ever significant, so the stored timestamp's low three fractional digits are
     * always zero BY CONSTRUCTION. Nobody computing a duration between two such values should read
     * microsecond resolution into them. This is also why the 26-character form the shared kernel's
     * timestamp formatter emits is a DIFFERENT format and is not used here: it carries four year digits,
     * colon separators and six significant fractional digits, so a value it produced would not parse
     * under the reference mask at all.</p>
     *
     * @param originatingDate the acquirer-declared originating date exactly as stored, year first; must
     *     be non-null and exactly six characters
     * @param decodedTime the DECODED authorization time, never the stored complement; must be between
     *     zero and {@code 999999999} inclusive
     * @return the 23 characters the reference application binds to its timestamp host variable
     * @throws IllegalArgumentException if {@code originatingDate} is null or is not exactly six
     *     characters, or if {@code decodedTime} needs more than nine digit positions
     */
    static String composeAuthTimestampText(String originatingDate, int decodedTime) {
        if (originatingDate == null || originatingDate.length() != ORIGINATING_FIELD_LENGTH) {
            throw new IllegalArgumentException("an originating date must be exactly "
                    + ORIGINATING_FIELD_LENGTH + " characters for the timestamp to be composed, but was "
                    + (originatingDate == null ? "null" : "of length " + originatingDate.length()));
        }

        String clock = paddedTimeDigits(decodedTime);
        String composed = originatingDate.substring(0, 2)
                + '-' + originatingDate.substring(2, 4)
                + '-' + originatingDate.substring(4, ORIGINATING_FIELD_LENGTH)
                + ' ' + clock.substring(0, 2)
                + '.' + clock.substring(2, 4)
                + '.' + clock.substring(4, 6)
                + clock.substring(6, TIME_DIGIT_POSITIONS)
                + MILLISECOND_MICROSECOND_PAD;

        // WHY : Assumptions: the width is asserted rather than trusted, because the value's only
        //       consumer is a conversion mask of exactly this width and a string one character short or
        //       long is rejected there as a data exception naming neither this method nor the field it
        //       came from. Checking here reports the composition that produced it.
        if (composed.length() != AUTH_TIMESTAMP_LENGTH) {
            throw new IllegalArgumentException("a composed authorization timestamp must be exactly "
                    + AUTH_TIMESTAMP_LENGTH + " characters, but was " + composed.length());
        }
        return composed;
    }

    /**
     * Composes the 23-character authorization timestamp of a stored authorization.
     *
     * <p>Assumptions: the time half is read from the entity's key, where it is already DECODED, so no
     * complement arithmetic is repeated here. The date half is read from the originating date member.
     * The two provenances recorded on {@link #composeAuthTimestampText(String, int)} apply
     * unchanged.</p>
     *
     * @param detail the stored authorization to compose a timestamp for; must not be null and must
     *     carry both an originating date and a key time
     * @return the 23 characters that address this authorization's row in the relational fraud table
     * @throws NullPointerException if {@code detail} is null, or if it carries no key
     * @throws IllegalStateException if the authorization carries no key time, which a stored row cannot
     *     because the column is declared NOT NULL
     * @throws IllegalArgumentException if the originating date is absent or is not exactly six
     *     characters, which is reachable because that value is acquirer-supplied and is stored as
     *     characters precisely so an unparseable one can be held
     */
    public static String authTimestampText(PendingAuthDetail detail) {
        Objects.requireNonNull(detail, "detail must not be null");
        PendingAuthDetailKey key =
                Objects.requireNonNull(detail.getId(), "detail must carry a key");
        Integer decodedTime = key.getAuthTime();
        if (decodedTime == null) {
            throw new IllegalStateException("the authorization carries no key time, so its timestamp"
                    + " cannot be composed");
        }
        return composeAuthTimestampText(detail.getAuthOrigDate(), decodedTime.intValue());
    }

    /**
     * Composes the authorization timestamp of a stored authorization as a date-time value.
     *
     * <p>Assumptions: this is the same composition as {@link #authTimestampText(PendingAuthDetail)} and
     * not a second one -- both draw the calendar parts from the originating date and the clock parts
     * from the decoded key time, in the same order and with the same pivot. Two renderings of one
     * composition are needed because the reference application's own two consumers differ: its insert
     * and update statements bind the 23 characters and let the database convert them, whereas the
     * target's fraud row carries a real timestamp column and takes a date-time value directly. Deriving
     * the second rendering by re-parsing the first was considered and rejected: it would make a
     * parse failure of a string this class had just produced indistinguishable from a parse failure of
     * stored data, and it would put the century pivot on both sides of one round trip.</p>
     *
     * <p>Assumptions: the three real millisecond digits are scaled to nanoseconds and the remaining six
     * nanosecond positions are left at zero, which reproduces exactly what the reference application
     * achieves with its literal {@code '000'}.</p>
     *
     * @param detail the stored authorization to compose a timestamp for; must not be null and must
     *     carry both an originating date and a key time
     * @return the instant that forms the second half of this authorization's fraud-row key
     * @throws NullPointerException if {@code detail} is null, or if it carries no key
     * @throws IllegalStateException if the authorization carries no key time, if its originating date is
     *     absent or is not exactly six characters, if that date is not six digits, or if the date and
     *     time together name no instant -- a month of thirteen or a thirty-first of February parse as
     *     digits and name nothing, and stored acquirer-supplied characters are the first place that can
     *     be discovered
     */
    public static LocalDateTime authTimestamp(PendingAuthDetail detail) {
        Objects.requireNonNull(detail, "detail must not be null");
        PendingAuthDetailKey key =
                Objects.requireNonNull(detail.getId(), "detail must carry a key");
        Integer decodedTime = key.getAuthTime();
        String originatingDate = detail.getAuthOrigDate();
        if (decodedTime == null || originatingDate == null
                || originatingDate.length() != ORIGINATING_FIELD_LENGTH) {
            throw new IllegalStateException("the authorization carries no key time or no"
                    + " six-character originating date, so its timestamp cannot be composed");
        }

        // WHY : Assumptions: an unparseable originating date is reported as an illegal STATE and not as
        //       an illegal argument, because the caller supplied none of it. The value is stored data
        //       this bounded context accepted as characters, and the reference outcome is the same class
        //       of failure -- its composed string reaches the database conversion function and returns a
        //       system error the transaction rolls back.
        int year;
        int month;
        int day;
        try {
            year = calendarYearOf(Integer.parseInt(originatingDate.substring(0, 2)));
            month = Integer.parseInt(originatingDate.substring(2, 4));
            day = Integer.parseInt(originatingDate.substring(4, ORIGINATING_FIELD_LENGTH));
        } catch (NumberFormatException malformed) {
            throw new IllegalStateException("the authorization's originating date is not six digits,"
                    + " so its timestamp cannot be composed", malformed);
        }

        int composed = decodedTime.intValue();
        int millis = composed % MILLIS_PER_SECOND;
        int clock = composed / MILLIS_PER_SECOND;
        int second = clock % CLOCK_FIELD_MODULUS;
        clock /= CLOCK_FIELD_MODULUS;
        int minute = clock % CLOCK_FIELD_MODULUS;
        int hour = clock / CLOCK_FIELD_MODULUS;

        try {
            return LocalDateTime.of(year, month, day, hour, minute, second,
                    millis * NANOS_PER_MILLI);
        } catch (java.time.DateTimeException impossible) {
            throw new IllegalStateException("the authorization's originating date and key time name no"
                    + " instant, so its timestamp cannot be composed", impossible);
        }
    }

    /**
     * Renders a stored originating date as the month-first form the reference screens display.
     *
     * <p>Assumptions: the stored six characters are YEAR first, then month, then day, and the display
     * form RE-ORDERS them. {@code cbl/COPAUS0C.cbl} L531 to L533 slice the first two characters into a
     * year part, the next two into a month part and the last two into a day part, then its L534 moves
     * the recomposed month-day-year group into a field its L59 declares as
     * {@code PIC X(08) VALUE '00/00/00'} -- the separators are already in the receiving field, which is
     * what makes the result month-first and solidus-separated. Reading the six stored characters as
     * month-first would yield dates that are entirely well-formed and wrong, which is why the ordering
     * is stated here rather than left to the shape of the value; the twenty-ninth of June 2023 is stored
     * {@code 230629} and displayed {@code 06/29/23}, and a naive reading would call it the twenty-ninth
     * of the sixth month of 2306.</p>
     *
     * <p>Assumptions: the characters are re-ordered and never validated as digits. The reference
     * application applies no such check on this path, and a re-ordering of whatever is stored is a
     * faithful rendering of whatever is stored; imposing a digit check here would refuse an
     * acquirer-supplied value the reference screen displayed as it was.</p>
     *
     * @param storedDate the six stored characters, year first, or null or blank when the field carries
     *     no value
     * @return the eight-character month-first form, or null when {@code storedDate} is null or blank
     * @throws IllegalArgumentException if {@code storedDate} is neither blank nor exactly six
     *     characters, a width no declared field of this segment can hold
     */
    static String renderOriginatingDate(String storedDate) {
        if (isBlank(storedDate)) {
            return null;
        }
        requireLength(storedDate, ORIGINATING_FIELD_LENGTH, "originating date");
        return storedDate.substring(2, 4) + '/' + storedDate.substring(4, ORIGINATING_FIELD_LENGTH)
                + '/' + storedDate.substring(0, 2);
    }

    /**
     * Renders a stored originating time as the colon-separated form the reference screens display.
     *
     * <p>Assumptions: the stored six characters are hours, minutes then seconds in that order, and the
     * display form only re-spaces them. {@code cbl/COPAUS0C.cbl} L527 to L529 move the three pairs into
     * positions one, four and seven of a field its L60 declares as
     * {@code PIC X(08) VALUE '00:00:00'}, so the colons come from the receiving field's initial value
     * and the pairs keep their order. This is the one render rule in this class where the stored order
     * and the displayed order agree, and it is stated explicitly because the adjacent date rule does not
     * agree and a reader who generalised from one to the other would corrupt the other.</p>
     *
     * @param storedTime the six stored characters, hours first, or null or blank when the field carries
     *     no value
     * @return the eight-character colon-separated form, or null when {@code storedTime} is null or blank
     * @throws IllegalArgumentException if {@code storedTime} is neither blank nor exactly six characters
     */
    static String renderOriginatingTime(String storedTime) {
        if (isBlank(storedTime)) {
            return null;
        }
        requireLength(storedTime, ORIGINATING_FIELD_LENGTH, "originating time");
        return storedTime.substring(0, 2) + ':' + storedTime.substring(2, 4)
                + ':' + storedTime.substring(4, ORIGINATING_FIELD_LENGTH);
    }

    /**
     * Renders a stored card expiry as the separated month-and-year form the detail screen displays.
     *
     * <p>Assumptions: the stored four characters are a two-digit month followed by a two-digit year, and
     * the rendered form is FIVE characters rather than four. {@code cbl/COPAUS1C.cbl} L336 to L338 move
     * the first pair to positions one and two of the receiving field, a solidus to position three, and
     * the second pair to positions four and five -- a four-character source widened by the separator it
     * is given. The width difference is worth naming because the response component that carries this
     * value is bounded at five while the segment field it comes from is four, and a reader comparing the
     * two would otherwise read the extra character as an inconsistency.</p>
     *
     * @param storedExpiry the four stored characters, month first, or null or blank when the field
     *     carries no value
     * @return the five-character separated form, or null when {@code storedExpiry} is null or blank
     * @throws IllegalArgumentException if {@code storedExpiry} is neither blank nor exactly four
     *     characters
     */
    static String renderCardExpiry(String storedExpiry) {
        if (isBlank(storedExpiry)) {
            return null;
        }
        requireLength(storedExpiry, CARD_EXPIRY_LENGTH, "card expiry");
        return storedExpiry.substring(0, 2) + '/' + storedExpiry.substring(2, CARD_EXPIRY_LENGTH);
    }

    /**
     * Renders the fraud position and its report date as the single field the detail screen displays.
     *
     * <p>Assumptions: the reference application composes this field two different ways depending on the
     * fraud position, and the branch is on the position and not on the date.
     * {@code cbl/COPAUS1C.cbl} L344 tests whether the flag is either of the two marking values; when it
     * is, L345 to L347 write the flag, then a hyphen, then the eight-character report date, filling ten
     * positions exactly. When it is not, L349 writes a bare hyphen over the whole field.</p>
     *
     * <p>Trade-offs: the unmarked case returns the hyphen alone rather than the hyphen followed by the
     * nine blanks the reference display field physically holds. The compromise accepted is that the
     * response value is shorter than the terminal field; what it buys is that the payload carries no run
     * of blanks that means nothing, and the terminal rendered those nine bytes as blanks in any case.
     * The marked case is returned at its full ten characters because every one of them is significant
     * there.</p>
     *
     * @param fraudFlag the stored one-character fraud position, which is blank or null for an
     *     authorization nobody has examined
     * @param reportDate the stored eight-character report date, which is blank or null for the same
     *     authorizations
     * @return ten characters when the authorization is marked, and a single hyphen when it is not
     * @throws IllegalArgumentException if the authorization is marked but its report date is neither
     *     blank nor exactly eight characters, which would render a field of the wrong width
     */
    static String renderFraudMark(String fraudFlag, String reportDate) {
        boolean marked = PendingAuthDetail.FRAUD_REPORTED.equals(fraudFlag)
                || PendingAuthDetail.FRAUD_REMOVED.equals(fraudFlag);
        if (!marked) {
            return FRAUD_MARK_ABSENT;
        }
        if (isBlank(reportDate)) {
            // WHY : Assumptions: a marked authorization with a blank date is not refused, because the
            //       reference branch tests only the flag and would compose the field with blanks in the
            //       date positions. Returning the flag and its separator reproduces the significant part
            //       of that without publishing the eight blanks that carry nothing.
            return fraudFlag + FRAUD_MARK_ABSENT;
        }
        requireLength(reportDate, FRAUD_REPORT_DATE_LENGTH, "fraud report date");
        return fraudFlag + FRAUD_MARK_ABSENT + reportDate;
    }

    /**
     * Renders the point-of-sale entry mode as the zero-filled digits its picture declares.
     *
     * <p>Assumptions: {@code PA-POS-ENTRY-MODE} is {@code PIC 9(02)} at {@code cpy/CIPAUDTY.cpy} L38,
     * an unsigned DISPLAY numeric, so its stored form is two digit characters zero-filled on the left.
     * The shared codec decodes such a field to an integral value, which discards the leading zero of a
     * single-digit mode, so the zero has to be restored on the way to a response component the contract
     * constrains to digits. Rendering the integral value directly would publish {@code 5} where the
     * segment holds {@code 05}, and both satisfy a digits-only constraint.</p>
     *
     * @param mode the stored entry mode as the entity holds it, or null when the field carries no value
     * @return exactly two digit characters, or null when {@code mode} is null
     * @throws IllegalArgumentException if {@code mode} is negative or needs more than two digit
     *     positions, neither of which an unsigned two-digit picture can hold
     */
    static String renderPosEntryMode(Short mode) {
        if (mode == null) {
            return null;
        }
        if (mode < 0 || mode > 99) {
            throw new IllegalArgumentException("a point-of-sale entry mode must occupy at most "
                    + POS_ENTRY_MODE_DIGITS + " unsigned digit positions, but was " + mode);
        }
        return String.format("%0" + POS_ENTRY_MODE_DIGITS + "d", mode);
    }

    /**
     * Renders the processing code as the zero-filled digits its picture declares.
     *
     * <p>Assumptions: {@code PA-PROCESSING-CODE} is {@code PIC 9(06)} at {@code cpy/CIPAUDTY.cpy} L33,
     * and the reasoning is the point-of-sale entry mode's exactly: the codec returns an integral value
     * and the stored form is six zero-filled digit characters, so a code below one hundred thousand
     * needs its leading zeros restored. The two are separate methods rather than one parameterised by
     * width because each one's width is a property of its own picture, and a shared method would take
     * the width from its caller -- which is where a wrong width would then come from.</p>
     *
     * @param decoded the integral value the codec returned for the field, or null when it carries none
     * @return exactly six digit characters, or null when {@code decoded} is null
     * @throws IllegalArgumentException if {@code decoded} is negative or needs more than six digit
     *     positions
     */
    static String renderProcessingCode(Long decoded) {
        if (decoded == null) {
            return null;
        }
        if (decoded < 0L || decoded > 999_999L) {
            throw new IllegalArgumentException("a processing code must occupy at most "
                    + PROCESSING_CODE_DIGITS + " unsigned digit positions, but was " + decoded);
        }
        return String.format("%0" + PROCESSING_CODE_DIGITS + "d", decoded);
    }

    /**
     * Parses a stored fraud report date into a calendar date, treating a blank field as no date.
     *
     * <p>Assumptions: the stored characters are month-first and separated, and they arrive BLANK rather
     * than absent on every authorization the message path inserts. {@code cbl/COPAUA0C.cbl} L908 to L909
     * move {@code SPACE} into the fraud flag and into this field on every insert, and those two lines are
     * the only writes to it on that path. Eight spaces cannot be parsed as a date, so the mapping to SQL
     * {@code NULL} is a requirement rather than a convenience: the relational fraud row's own report-date
     * column is a nullable date for exactly this reason, while the detail row keeps the eight characters
     * themselves in a declared-width character column. Two representations of one value, matching the reference
     * application's own two.</p>
     *
     * <p>Assumptions: the month-first ordering is settled two independent ways, which matters because it
     * is the opposite of the ISO ordering the rest of the migration uses.
     * {@code cbl/COPAUS2C.cbl} L91 to L101 format the current date with a month-first request and a
     * separator and move the result straight into the field, and {@code cbl/COPAUS1C.cbl} L344 to L350
     * render one flag character, one separator and these eight characters into a ten-character display
     * field, which only closes if the field is eight characters wide with its separators included.</p>
     *
     * @param storedDate the eight stored characters, month first and solidus-separated, or null or blank
     *     when the authorization has never been examined
     * @return the calendar date the characters name, or null when {@code storedDate} is null or blank
     * @throws IllegalArgumentException if {@code storedDate} is neither blank nor exactly eight
     *     characters, if its separators are not where the format puts them, if its parts are not digits,
     *     or if those digits name no calendar date
     */
    static LocalDate parseFraudReportDate(String storedDate) {
        if (isBlank(storedDate)) {
            return null;
        }
        requireLength(storedDate, FRAUD_REPORT_DATE_LENGTH, "fraud report date");
        if (storedDate.charAt(2) != '/' || storedDate.charAt(5) != '/') {
            throw new IllegalArgumentException("a fraud report date must carry its separators at the"
                    + " third and sixth positions, as the reference format writes them");
        }

        int month;
        int day;
        int year;
        try {
            month = Integer.parseInt(storedDate.substring(0, 2));
            day = Integer.parseInt(storedDate.substring(3, 5));
            year = calendarYearOf(Integer.parseInt(storedDate.substring(6,
                    FRAUD_REPORT_DATE_LENGTH)));
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("a fraud report date must be digits either side of its"
                    + " separators", malformed);
        }

        try {
            return LocalDate.of(year, month, day);
        } catch (java.time.DateTimeException impossible) {
            throw new IllegalArgumentException("a fraud report date's digits name no calendar date",
                    impossible);
        }
    }

    /**
     * Masks a primary account number to its last four digits.
     *
     * <p>Assumptions: the mask itself belongs to the shared kernel and this method only routes to it, so
     * the visible-tail length and the mask character are decided once for the whole migration. Wrapping
     * rather than calling the kernel directly at each site is what lets this class state the reason the
     * mask applies at all in one place -- the reference resource definitions enable tracing and dumping
     * without classifying the payload as confidential, the detail screen displayed all sixteen digits,
     * and no resource-level or command-level check stood in front of either. The migration adds the
     * narrowing; it removes nothing.</p>
     *
     * @param cardNumber the stored account number as the segment holds it, or null when absent
     * @return the account number with all but its last four digits replaced, or null when
     *     {@code cardNumber} is null
     */
    static String maskedCardNumber(String cardNumber) {
        return cardNumber == null ? null : CardNumberMasker.mask(cardNumber);
    }

    /**
     * Reports whether a stored character field carries nothing but spaces.
     *
     * <p>Assumptions: a null and a run of spaces are treated alike, because the two arise from different
     * sources that mean the same thing here -- a null is a column the extract left unset and a run of
     * spaces is the value the reference insert path writes. Treating only one of them as absent would
     * make the same authorization render two ways depending on how its row reached the database.</p>
     *
     * @param value the stored characters to inspect, which may be null
     * @return true when {@code value} is null, empty, or made up entirely of spaces
     */
    private static boolean isBlank(String value) {
        if (value == null) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Refuses a stored character field whose width is not the one its picture declares.
     *
     * <p>Assumptions: the width is checked before any slice rather than relying on the slice to fail,
     * because a value one character longer than declared slices without complaint and yields a
     * well-formed rendering of the wrong characters. A refusal naming the field and both widths is
     * recoverable; a plausible wrong date is not.</p>
     *
     * @param value the stored characters, already known to be non-blank
     * @param expected the width the declaring picture fixes
     * @param label the human-readable name of the field, used only to name it in a refusal
     * @throws IllegalArgumentException if {@code value} is not exactly {@code expected} characters
     */
    private static void requireLength(String value, int expected, String label) {
        if (value.length() != expected) {
            throw new IllegalArgumentException("a stored " + label + " must be exactly " + expected
                    + " characters, but was of length " + value.length());
        }
    }

    /**
     * Decodes one segment image into its declared fields, keyed by their copybook names.
     *
     * <p>Assumptions: the two key components come back as the STORED COMPLEMENTS and not as decoded
     * values, because this method is the record's field projection and the copybook's field is the
     * complement. A caller wanting the decoded date and time uses {@link #toKey(byte[], Long)} or the
     * complement operations directly. Naming that here matters because a caller that read the two values
     * out of this map and treated them as a date and a time would get plausible numbers that are
     * wrong.</p>
     *
     * <p>Assumptions: this projection reaches every state a stored segment can be in, including the
     * expired and matched match statuses that {@link #toEntity(byte[], Long)} refuses. That is what it
     * is for: the purge and unload paths inspect occurrences the insert path did not originate, and the
     * entity type declines to represent those by construction.</p>
     *
     * <p>Assumptions: blank trailing padding is absent from the result and every other field is present
     * at its declared width with its trailing blanks intact. The shared codec drops the padding
     * descriptor and restores it on encode, and it never trims a field, for the reason recorded on the
     * merchant name in this class's charter above.</p>
     *
     * @param segment one detail segment image; must not be null and must be exactly the declared segment
     *     length
     * @return a mutable map in copybook declaration order, whose values are character strings for text
     *     fields, integral values for unsigned display fields and exact decimals for packed fields
     * @throws NullPointerException if {@code segment} is null
     * @throws FixedWidthCodec.RecordLengthException if {@code segment} is not exactly the declared
     *     segment length, which is how a prefixed unload record passed here is caught rather than
     *     decoded six bytes out of alignment
     * @throws FixedWidthCodec.FieldCodecException if any field's bytes are malformed for the storage
     *     regime its descriptor declares
     */
    public static Map<String, Object> toSegmentFields(byte[] segment) {
        Objects.requireNonNull(segment, "segment must not be null");
        return FixedWidthCodec.decodeRecord(segment, SEGMENT);
    }

    /**
     * Decodes the composite key of one segment image, complementing both of its components.
     *
     * <p>Assumptions: the account identifier is NOT in the segment and has to be supplied. It belongs to
     * the parent, whose own key {@code ims/DBPAUTP0.dbd} L30 declares separately, and the reference
     * detail path never reaches a child occurrence without positioning on its parent first. A caller
     * therefore always knows which account it is reading under, and inventing one here would be
     * inventing the row's identity.</p>
     *
     * <p>Assumptions: both components are converted with an exact integral conversion rather than a
     * truncating one, so a packed value too wide for the target type is refused instead of being
     * silently narrowed. A narrowed key inserts successfully and addresses a different authorization,
     * which is the failure hardest to notice afterwards.</p>
     *
     * @param segment one detail segment image; must not be null and must be exactly the declared segment
     *     length
     * @param accountId the identifier of the parent occurrence this segment hangs under; must not be null
     * @return the three-part key carrying the account identifier and the DECODED Julian date and
     *     millisecond time
     * @throws NullPointerException if {@code segment} or {@code accountId} is null
     * @throws FixedWidthCodec.RecordLengthException if {@code segment} is not exactly the declared
     *     segment length
     * @throws FixedWidthCodec.FieldCodecException if either packed key component's bytes are malformed,
     *     which includes a digit in the sign position
     * @throws ArithmeticException if either decoded component needs more precision than an integer holds
     * @throws IllegalArgumentException if either stored complement lies outside the range its base
     *     admits
     */
    public static PendingAuthDetailKey toKey(byte[] segment, Long accountId) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        return keyOf(toSegmentFields(segment), accountId);
    }

    /**
     * Builds the composite key from an already-decoded field map, complementing both components.
     *
     * <p>Assumptions: the key is built from the decoded MAP rather than from the raw bytes, and the
     * distinction is the reason this helper exists. Addressing the two packed spans directly would only
     * check that each span fits inside the array, so a 206-byte prefixed unload record passed as a
     * segment would decode its packed parent key as though it were the date complement and succeed --
     * yielding a well-formed key for the wrong authorization. Going through the map means the codec's own
     * whole-record length check has already refused that array before either component is read.</p>
     *
     * <p>Assumptions: both components are converted with an exact integral conversion rather than a
     * truncating one, so a packed value too wide for the target type is refused instead of being silently
     * narrowed. A narrowed key inserts successfully and addresses a different authorization.</p>
     *
     * @param fields the decoded field map of one segment image
     * @param accountId the identifier of the parent occurrence this segment hangs under
     * @return the three-part key carrying the account identifier and both DECODED components
     * @throws NullPointerException if either key component is absent from the map, which the codec's
     *     contract makes unreachable because it drops only blank trailing padding
     * @throws ArithmeticException if either decoded component needs more precision than an integer holds
     * @throws IllegalArgumentException if either stored complement lies outside the range its base admits
     */
    private static PendingAuthDetailKey keyOf(Map<String, Object> fields, Long accountId) {
        return new PendingAuthDetailKey(accountId,
                decodeAuthDate(complementComponent(fields, FIELD_AUTH_DATE_COMPLEMENT)),
                decodeAuthTime(complementComponent(fields, FIELD_AUTH_TIME_COMPLEMENT)));
    }

    /**
     * Reads one still-complemented key component out of a decoded field map as an exact integer.
     *
     * @param fields the decoded field map of one segment image
     * @param fieldName the copybook name of the key component to read
     * @return the stored complement as an integer, still complemented
     * @throws NullPointerException if the component is absent from the map
     * @throws ArithmeticException if the value needs more precision than an integer holds
     */
    private static int complementComponent(Map<String, Object> fields, String fieldName) {
        BigDecimal stored = (BigDecimal) fields.get(fieldName);
        Objects.requireNonNull(stored, () -> "the decoded segment carries no " + fieldName);
        return stored.intValueExact();
    }

    /**
     * Decodes one segment image into the entity, complementing the key and decoding every amount.
     *
     * <p>Assumptions: the amounts are routed through the migration's money type at the picture the
     * copybook declares rather than being handed on as the raw decimals the codec returns. The two are
     * numerically identical for well-formed data, so the routing buys one thing specifically: an amount
     * legal for the widest money picture in the corpus but too wide for this field's ten integer digits
     * is refused here, at the assignment that introduced it, instead of reaching the
     * {@code NUMERIC(12,2)} column and returning a numeric-field-overflow that names no field.</p>
     *
     * <p>Assumptions: text is handed on at its declared width and is never trimmed, and the fraud pair is
     * applied through the entity's own marking operation rather than assigned. That operation admits only
     * the two marking values and requires the eight-character report date, so a blank fraud position
     * leaves both members at the never-examined state the entity documents rather than being written as a
     * space or as a null. The reference insert path writes blanks into both on every insert, so blank is
     * the ordinary case and not an edge one.</p>
     *
     * <p>Refactoring Rationale: this operation now reaches ALL FOUR stored states, through
     * {@link PendingAuthDetail#rehydrated}, and it previously reached three. It routed every occurrence
     * through the entity's originating constructor, which admits only the two values an insert selects
     * between, so a segment carrying the expired or the matched value was refused with a message stating
     * that the value "is reached by a later transition and never by an insert" -- true of the constructor
     * and false of the segment. Those two states are not edge cases: the purge job writes the first and
     * the posting match writes the second, the migration's own check constraint admits both, and the
     * committed fixture {@code pautdtl1-match-status-domain.bin} carries one occurrence of each, so an
     * extract load of real data failed on them. The entity now separates originating a decision from
     * reconstituting a row and this operation is a reconstitution, which is why it uses the second.
     *
     * <p>Assumptions: the narrow rule still applies where it belongs. Nothing here can fabricate a NEW
     * decision claiming to have been matched, because the decision path uses the constructor and the
     * constructor is unchanged; what changed is that a LOAD is no longer held to the rule of an insert.
     *
     * @param segment one detail segment image; must not be null and must be exactly the declared segment
     *     length
     * @param accountId the identifier of the parent occurrence this segment hangs under; must not be null
     * @return a populated entity whose key carries decoded values and whose fraud position is applied
     *     only when the segment carries one
     * @throws NullPointerException if {@code segment} or {@code accountId} is null
     * @throws FixedWidthCodec.RecordLengthException if {@code segment} is not exactly the declared
     *     segment length
     * @throws FixedWidthCodec.FieldCodecException if any field's bytes are malformed for its declared
     *     storage regime
     * @throws ArithmeticException if a decoded key component or amount exceeds the range its target
     *     holds
     * @throws IllegalArgumentException if a stored complement is out of range, if the decoded key falls
     *     outside the domain its columns declare, if the match status is outside the closed four-value
     *     domain, if the entry mode is outside the range its picture admits, if a fraud position is
     *     present with a report date of the wrong width, or if the fraud position is nonblank and is
     *     neither of the two marking characters
     */
    public static PendingAuthDetail toEntity(byte[] segment, Long accountId) {
        Objects.requireNonNull(accountId, "accountId must not be null");

        // WHY : Trade-offs: the image is decoded ONCE and the key is built from the resulting map, rather
        //       than delegating to the key operation and decoding a second time. The cost is that the two
        //       public entry points each hold a line of assembly instead of one calling the other; what it
        //       buys is that a caller decoding a whole file pays one pass per record instead of two, and
        //       that the key and the members it accompanies are provably read from the same decode.
        Map<String, Object> fields = toSegmentFields(segment);
        PendingAuthDetailKey key = keyOf(fields, accountId);

        // WHY : Refactoring Rationale: the rehydration factory rather than the constructor, for the reason
        //       recorded on this method: a stored occurrence may hold any of the four match statuses its
        //       column admits, and the constructor admits only the two an insert originates.
        PendingAuthDetail detail = PendingAuthDetail.rehydrated(key,
                text(fields, FIELD_AUTH_ORIG_DATE),
                text(fields, FIELD_AUTH_ORIG_TIME),
                text(fields, FIELD_CARD_NUM),
                text(fields, FIELD_AUTH_TYPE),
                text(fields, FIELD_CARD_EXPIRY_DATE),
                text(fields, FIELD_MESSAGE_TYPE),
                text(fields, FIELD_MESSAGE_SOURCE),
                text(fields, FIELD_AUTH_ID_CODE),
                text(fields, FIELD_AUTH_RESP_CODE),
                text(fields, FIELD_AUTH_RESP_REASON),
                renderProcessingCode(unsigned(fields, FIELD_PROCESSING_CODE)),
                amount(fields, FIELD_TRANSACTION_AMT),
                amount(fields, FIELD_APPROVED_AMT),
                text(fields, FIELD_MERCHANT_CATEGORY_CODE),
                text(fields, FIELD_ACQR_COUNTRY_CODE),
                entryMode(fields),
                text(fields, FIELD_MERCHANT_ID),
                text(fields, FIELD_MERCHANT_NAME),
                text(fields, FIELD_MERCHANT_CITY),
                text(fields, FIELD_MERCHANT_STATE),
                text(fields, FIELD_MERCHANT_ZIP),
                text(fields, FIELD_TRANSACTION_ID),
                text(fields, FIELD_MATCH_STATUS));

        // WHY : Assumptions: the marking operation is called only when the segment actually carries one
        //       of the two marking values, and the entity is otherwise left alone. Calling it
        //       unconditionally would refuse every ordinary occurrence, because the reference insert path
        //       writes a space into the position and the operation admits no space; and assigning the
        //       members some other way is not available, since the constructor accepts neither.
        // WHY : Refactoring Rationale: a value that is neither blank nor one of the two marking
        //       characters used to fall through this branch SILENTLY, leaving the entity unmarked and the
        //       stored character discarded. That lost audit state without reporting anything: the column
        //       admits only the two characters and null, so the entity presented null, the check
        //       constraint ck_pending_auth_detail_auth_fraud never saw the offending byte, and the row
        //       loaded clean while the extract's own record said an authorization had been marked. It is
        //       now REFUSED, so a defective extract is reported at the record that carries it instead of
        //       being normalised into a state the source never held.
        String fraudFlag = text(fields, FIELD_AUTH_FRAUD);
        String reportDate = text(fields, FIELD_FRAUD_RPT_DATE);
        if (PendingAuthDetail.FRAUD_REPORTED.equals(fraudFlag)
                || PendingAuthDetail.FRAUD_REMOVED.equals(fraudFlag)) {
            detail.applyFraudMark(fraudFlag, reportDate);
        } else {
            requireFraudPositionInDomain(fraudFlag);
        }
        return detail;
    }

    /**
     * Refuses a decoded fraud position that is neither blank nor one of the two marking characters.
     *
     * <p>Assumptions: BLANK is admitted and is not a marking. The copybook at
     * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} L50 declares the field as {@code X(01)}
     * and names conditions for exactly two values at L51 and L52, and {@code cbl/COPAUS1C.cbl} L344 to
     * L349 handles the unnamed third state explicitly, so a space is the ordinary unmarked reading of an
     * authorization no reviewer has touched. Null is admitted for the same reason: the shared codec hands
     * back {@code null} where a field trims to nothing.
     *
     * <p>Assumptions: anything else is a DEFECT IN THE SOURCE and not a value to interpret. The three
     * admitted states are the whole of what the column accepts, so a fourth character cannot be stored
     * whatever this class does with it -- the only question is whether it is reported here or discarded
     * here and reported nowhere.
     *
     * <p>Alternatives Considered: normalising the character to unmarked, which is what this class did
     * before, and normalising it to reported on the grounds that SOMETHING was marked. Both were
     * rejected for the same reason: each invents a state the source did not hold, and the two invent
     * opposite ones, which is the clearest possible sign that neither is a reading of the data.
     *
     * <p>Trade-offs: the refused character is named in the message and the account is not. The character
     * is one byte of a domain of printable codes and is the whole of what an operator needs in order to
     * find the offending records in the extract, whereas the account identifier is the confidential
     * value this service's logging rules keep out of diagnostics -- and the caller that decoded the
     * record already knows which record it handed over.
     *
     * @param fraudFlag the decoded fraud position, which may be {@code null} or blank
     * @throws IllegalArgumentException if the position is nonblank and is neither {@code F} nor {@code R}
     */
    private static void requireFraudPositionInDomain(String fraudFlag) {
        if (isBlank(fraudFlag)) {
            return;
        }
        throw new IllegalArgumentException("the authorization fraud position holds '" + fraudFlag
                + "', which is outside the domain the column admits: '"
                + PendingAuthDetail.FRAUD_REPORTED + "', '" + PendingAuthDetail.FRAUD_REMOVED
                + "' or blank");
    }

    /**
     * Encodes an entity into one segment image, re-applying the complement and restoring the padding.
     *
     * <p>Assumptions: the key components are re-complemented on the way out, which is what makes the
     * round trip an identity and is the cheapest available proof the decode is right --
     * {@code 99999 - (99999 - d)} is {@code d} and {@code 999999999 - (999999999 - t)} is {@code t}. A
     * decode-only mapper cannot be checked this way at all.</p>
     *
     * <p>Trade-offs: this form does not reproduce two things the sign-preserving form does, and both are
     * stated because a byte comparison that fails on either looks like a decode defect and is not. A
     * packed zero whose stored sign nibble was the negative one re-encodes with the positive nibble,
     * which is a registered divergence of the shared codec's plain encoder; and a record whose trailing
     * padding held anything other than blanks re-encodes with blanks, because the entity carries no
     * padding member to hold the difference. Use {@link #toSegment(PendingAuthDetail, byte[])} where byte
     * identity against a specific source image is what is being asserted.</p>
     *
     * @param detail the entity to encode; must not be null and must carry a key with both components
     * @return a newly allocated image of exactly the declared segment length
     * @throws NullPointerException if {@code detail} is null, or if it carries no key
     * @throws IllegalStateException if the key carries no date or no time, which a stored row cannot
     *     because both columns are declared NOT NULL
     * @throws IllegalArgumentException if a decoded key component lies outside the range its complement
     *     base admits
     * @throws FixedWidthCodec.FieldCodecException if a value the entity holds cannot be encoded into its
     *     declared interval, which includes text wider than its field and an amount wider than its
     *     picture
     */
    public static byte[] toSegment(PendingAuthDetail detail) {
        return FixedWidthCodec.encodeRecord(segmentFieldsOf(detail), SEGMENT);
    }

    /**
     * Encodes an entity into one prefixed unload record, for the unload path.
     *
     * <p>Purpose: this is the exact inverse of {@link #fromUnloadRecord(byte[])} and the encode half of
     * what {@code cbl/PAUDBUNL.CBL} performs per child segment -- L44 to L48 declare the record as a
     * packed eleven-digit parent key ahead of the two-hundred-byte segment, and the write at its
     * {@code 3000-FIND-NEXT-AUTH-DTL} paragraph emits exactly that pair after moving
     * {@code PA-ACCT-ID} into the prefix.</p>
     *
     * <p>Assumptions: the prefix is taken from the entity's OWN key rather than from a parameter, because
     * the reference program writes the account identifier of the parent it is currently positioned on and
     * the child it just read belongs to that parent by construction. Accepting an identifier here would
     * make it possible to emit a record whose prefix names one account and whose segment belongs to
     * another, and nothing downstream could detect it: both halves would be well-formed.</p>
     *
     * <p>Assumptions: the prefix is written as packed decimal and never as text, matching the
     * {@code PIC S9(11) COMP-3} the record declares, and its width is computed from that picture rather
     * than written as six so that the two cannot disagree.</p>
     *
     * @param detail the stored authorization to encode; must not be {@code null}, and its key must carry
     *     an account identifier because the prefix column cannot be omitted
     * @return a newly allocated record of exactly {@link #unloadRecordLength()} bytes
     * @throws NullPointerException if {@code detail} is {@code null}, or if it carries no key, or if its
     *     key carries no account identifier
     * @throws FixedWidthCodec.FieldCodecException if a stored value does not fit its declared interval
     * @throws PackedDecimalCodec.PackedDecimalException if the account identifier needs more than the
     *     eleven digits the prefix declares, or if a stored amount exceeds its picture
     */
    public static byte[] toUnloadRecord(PendingAuthDetail detail) {
        Objects.requireNonNull(detail, "detail must not be null");
        PendingAuthDetailKey key = Objects.requireNonNull(detail.getId(),
                "detail must carry a key, because the unload prefix is taken from it");
        Long accountId = Objects.requireNonNull(key.getAccountId(),
                "the key must carry an account identifier, because the prefix column cannot be omitted");

        byte[] record = new byte[unloadRecordLength()];
        byte[] prefix = PackedDecimalCodec.encodePacked(
                BigDecimal.valueOf(accountId), UNLOAD_ROOT_KEY_DIGITS, 0, true);
        System.arraycopy(prefix, 0, record, 0, UNLOAD_ROOT_KEY_WIDTH);
        System.arraycopy(toSegment(detail), 0, record, UNLOAD_ROOT_KEY_WIDTH, SEGMENT.reclen());
        return record;
    }

    /**
     * Encodes an entity into one segment image that is byte-identical to the image it was decoded from.
     *
     * <p>Assumptions: the source image is needed for one reason only, and it is a property of the shared
     * codec rather than of this class. A signed packed field holding zero has two legal encodings, the
     * positive and the negative sign nibble, and the plain encoder emits the positive one; where the
     * source held the negative one, byte identity requires copying that carrier back. Supplying the
     * source is what lets a round-trip assertion test the DECODE rather than testing that
     * normalisation.</p>
     *
     * @param detail the entity to encode; must not be null and must carry a key with both components
     * @param decodedFrom the image this entity was decoded from, whose sign carriers are restored; must
     *     not be null and must be exactly the declared segment length
     * @return a newly allocated image of exactly the declared segment length
     * @throws NullPointerException if {@code detail} is null, or if it carries no key
     * @throws IllegalStateException if the key carries no date or no time
     * @throws IllegalArgumentException if a decoded key component lies outside the range its complement
     *     base admits
     * @throws FixedWidthCodec.RecordLengthException if {@code decodedFrom} is not exactly the declared
     *     segment length
     * @throws FixedWidthCodec.FieldCodecException if {@code decodedFrom} is null or a value the entity
     *     holds cannot be encoded into its declared interval
     */
    public static byte[] toSegment(PendingAuthDetail detail, byte[] decodedFrom) {
        return FixedWidthCodec.encodeRecordPreservingSign(segmentFieldsOf(detail), SEGMENT,
                decodedFrom);
    }

    /**
     * Projects an entity onto the field map the shared codec encodes, supplying every declared field.
     *
     * <p>Assumptions: the encoder refuses a missing field and refuses a null value, and it treats only
     * the trailing padding descriptor as omissible. A segment has no representation for absent, so every
     * member the entity holds as null is encoded as the value the field would hold in that state: blanks
     * of the declared width for a character field, zeros for an unsigned display field whose digit check
     * a blank run would fail, and zero for a packed amount. That is not a substitution of convenience --
     * the reference insert path writes a value into every one of these fields, so a null here can only
     * have come from a column an extract left unset, and blanks and zeros are what the segment form of
     * that is.</p>
     *
     * <p>Assumptions: the trailing padding is omitted deliberately, and the encoder rebuilds it with the
     * charset's blank byte rather than leaving the new array's zero bytes in place. Those two are not the
     * same thing to a reader of the record even though both look empty.</p>
     *
     * @param detail the entity to project; must not be null and must carry a key with both components
     * @return a map carrying every declared field except the trailing padding, keyed by copybook name
     * @throws NullPointerException if {@code detail} is null, or if it carries no key
     * @throws IllegalStateException if the key carries no date or no time
     * @throws IllegalArgumentException if a decoded key component lies outside the range its complement
     *     base admits, or if the entry mode needs more than its two declared digit positions
     */
    private static Map<String, Object> segmentFieldsOf(PendingAuthDetail detail) {
        Objects.requireNonNull(detail, "detail must not be null");
        PendingAuthDetailKey key =
                Objects.requireNonNull(detail.getId(), "detail must carry a key");
        if (key.getAuthDate() == null || key.getAuthTime() == null) {
            throw new IllegalStateException("the authorization's key carries no date or no time, so its"
                    + " segment image cannot be encoded");
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(FIELD_AUTH_DATE_COMPLEMENT,
                BigDecimal.valueOf(encodeAuthDate(key.getAuthDate())));
        fields.put(FIELD_AUTH_TIME_COMPLEMENT,
                BigDecimal.valueOf(encodeAuthTime(key.getAuthTime())));
        fields.put(FIELD_AUTH_ORIG_DATE, characters(detail.getAuthOrigDate(),
                FIELD_AUTH_ORIG_DATE));
        fields.put(FIELD_AUTH_ORIG_TIME, characters(detail.getAuthOrigTime(),
                FIELD_AUTH_ORIG_TIME));
        fields.put(FIELD_CARD_NUM, characters(detail.getCardNum(), FIELD_CARD_NUM));
        fields.put(FIELD_AUTH_TYPE, characters(detail.getAuthType(), FIELD_AUTH_TYPE));
        fields.put(FIELD_CARD_EXPIRY_DATE, characters(detail.getCardExpiryDate(),
                FIELD_CARD_EXPIRY_DATE));
        fields.put(FIELD_MESSAGE_TYPE, characters(detail.getMessageType(), FIELD_MESSAGE_TYPE));
        fields.put(FIELD_MESSAGE_SOURCE, characters(detail.getMessageSource(),
                FIELD_MESSAGE_SOURCE));
        fields.put(FIELD_AUTH_ID_CODE, characters(detail.getAuthIdCode(), FIELD_AUTH_ID_CODE));
        fields.put(FIELD_AUTH_RESP_CODE, characters(detail.getAuthRespCode(),
                FIELD_AUTH_RESP_CODE));
        fields.put(FIELD_AUTH_RESP_REASON, characters(detail.getAuthRespReason(),
                FIELD_AUTH_RESP_REASON));
        fields.put(FIELD_PROCESSING_CODE, unsignedDigits(detail.getProcessingCode(),
                PROCESSING_CODE_DIGITS));
        fields.put(FIELD_TRANSACTION_AMT, amountOrZero(detail.getTransactionAmount()));
        fields.put(FIELD_APPROVED_AMT, amountOrZero(detail.getApprovedAmount()));
        fields.put(FIELD_MERCHANT_CATEGORY_CODE, characters(detail.getMerchantCategoryCode(),
                FIELD_MERCHANT_CATEGORY_CODE));
        fields.put(FIELD_ACQR_COUNTRY_CODE, characters(detail.getAcqrCountryCode(),
                FIELD_ACQR_COUNTRY_CODE));
        fields.put(FIELD_POS_ENTRY_MODE, unsignedDigits(renderPosEntryMode(detail.getPosEntryMode()),
                POS_ENTRY_MODE_DIGITS));
        fields.put(FIELD_MERCHANT_ID, characters(detail.getMerchantId(), FIELD_MERCHANT_ID));
        fields.put(FIELD_MERCHANT_NAME, characters(detail.getMerchantName(), FIELD_MERCHANT_NAME));
        fields.put(FIELD_MERCHANT_CITY, characters(detail.getMerchantCity(), FIELD_MERCHANT_CITY));
        fields.put(FIELD_MERCHANT_STATE, characters(detail.getMerchantState(),
                FIELD_MERCHANT_STATE));
        fields.put(FIELD_MERCHANT_ZIP, characters(detail.getMerchantZip(), FIELD_MERCHANT_ZIP));
        fields.put(FIELD_TRANSACTION_ID, characters(detail.getTransactionId(),
                FIELD_TRANSACTION_ID));
        fields.put(FIELD_MATCH_STATUS, characters(detail.getMatchStatus(), FIELD_MATCH_STATUS));
        fields.put(FIELD_AUTH_FRAUD, characters(detail.getAuthFraud(), FIELD_AUTH_FRAUD));
        fields.put(FIELD_FRAUD_RPT_DATE, characters(detail.getFraudReportDate(),
                FIELD_FRAUD_RPT_DATE));
        return fields;
    }

    /**
     * Reads one decoded text field out of a field map without trimming it.
     *
     * <p>Assumptions: an absent key yields null rather than an empty string, and that case is reachable
     * for exactly one field -- the trailing padding, which the codec drops when it is blank. Every other
     * field is always present. No trimming is applied, for the reason recorded on the merchant name in
     * this class's charter above.</p>
     *
     * @param fields the decoded field map
     * @param fieldName the copybook name of the field to read
     * @return the decoded characters at their declared width, or null when the key is absent
     */
    private static String text(Map<String, Object> fields, String fieldName) {
        return (String) fields.get(fieldName);
    }

    /**
     * Reads one decoded unsigned display field out of a field map.
     *
     * @param fields the decoded field map
     * @param fieldName the copybook name of the field to read
     * @return the integral value the codec decoded, or null when the key is absent
     */
    private static Long unsigned(Map<String, Object> fields, String fieldName) {
        return (Long) fields.get(fieldName);
    }

    /**
     * Reads one decoded packed amount out of a field map and bounds it at its declared picture.
     *
     * <p>Assumptions: the value is routed through the migration's money type at ten integer digits, which
     * is the picture {@code cpy/CIPAUDTY.cpy} L34 and L35 declare, and the exact decimal it carries is
     * then handed to the entity because the column behind it is a fixed-point numeric. The general money
     * contract is used -- scale two, half-up -- and never the truncating variant reserved for the interest
     * formula, because this bounded context performs no interest arithmetic at all. Binary
     * floating-point appears nowhere on this path and is refused across the migration by an architecture
     * test rather than by prose.</p>
     *
     * @param fields the decoded field map
     * @param fieldName the copybook name of the amount to read
     * @return the amount as an exact decimal at scale two, or null when the key is absent
     * @throws ArithmeticException if the decoded magnitude needs more than the declared ten integer
     *     digits, which the column behind it could not hold either
     */
    private static BigDecimal amount(Map<String, Object> fields, String fieldName) {
        BigDecimal decoded = (BigDecimal) fields.get(fieldName);
        return decoded == null ? null : Money.ofPicture(decoded, MONEY_INTEGER_DIGITS).amount();
    }

    /**
     * Narrows the decoded entry mode to the type the entity declares.
     *
     * <p>Assumptions: the narrowing is safe because the picture at {@code cpy/CIPAUDTY.cpy} L38 admits
     * two unsigned digit positions, so the value cannot exceed ninety-nine, and the entity range-checks
     * it again on construction. The conversion is written out rather than left implicit so that a reader
     * can see the width claim being made.</p>
     *
     * @param fields the decoded field map
     * @return the entry mode as the entity's type, or null when the field is absent
     */
    private static Short entryMode(Map<String, Object> fields) {
        Long decoded = unsigned(fields, FIELD_POS_ENTRY_MODE);
        return decoded == null ? null : Short.valueOf(decoded.shortValue());
    }

    /**
     * Supplies the character form a field holds, substituting blanks of its declared width for null.
     *
     * <p>Assumptions: the width comes from the registered descriptor rather than from a literal, so a
     * blank run is always exactly as wide as the field it fills. A segment has no representation for
     * absent, so a null member can only have come from a column an extract left unset, and blanks are the
     * segment form of that.</p>
     *
     * @param value the member's value, which may be null
     * @param fieldName the copybook name whose declared width sizes the substitute
     * @return the value unchanged, or a run of blanks exactly as wide as the field
     */
    private static String characters(String value, String fieldName) {
        return value == null ? " ".repeat(SEGMENT.field(fieldName).length()) : value;
    }

    /**
     * Supplies the digit form an unsigned display field holds, substituting zeros for null.
     *
     * <p>Assumptions: an unsigned display field cannot be encoded from blanks, because the encoder
     * validates every character as a digit and a blank run fails that check. Zeros are therefore the only
     * encodable form of an absent value here, and they are what the field would hold on any occurrence
     * the reference insert path produced, since that path moves a value into both of these fields
     * unconditionally.</p>
     *
     * @param value the member's digit characters, which may be null
     * @param width the digit positions the declaring picture fixes
     * @return the value unchanged, or a run of zeros exactly as wide as the picture
     */
    private static String unsignedDigits(String value, int width) {
        return value == null ? "0".repeat(width) : value;
    }

    /**
     * Supplies the amount a packed field holds, substituting an exact zero for null.
     *
     * <p>Assumptions: a packed field has no absent representation either, and zero at the declared scale
     * is the only encodable substitute. The scale is set explicitly rather than left at whatever the
     * constant carries, so the encoded nibbles fill the declared decimal positions instead of relying on
     * the encoder to widen a scale-zero value.</p>
     *
     * @param value the member's amount, which may be null
     * @return the amount unchanged, or zero at the declared decimal scale
     */
    private static BigDecimal amountOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(MONEY_DECIMAL_DIGITS) : value;
    }

    /**
     * Decodes the packed parent key that prefixes one unload record.
     *
     * <p>Assumptions: the prefix is a packed field and never text. {@code cbl/PAUDBUNL.CBL} L47 declares
     * it {@code PIC S9(11) COMP-3} and its L230 populates it from the parent's account identifier, so its
     * six bytes carry two digits each and a sign nibble, and every one of them falls outside the range a
     * digit character occupies. Reading them as characters yields a string of unprintable bytes rather
     * than a number.</p>
     *
     * @param unloadRecord one prefixed unload record; must not be null and must be exactly the declared
     *     unload record length
     * @return the account identifier the prefix carries
     * @throws NullPointerException if {@code unloadRecord} is null
     * @throws IllegalArgumentException if {@code unloadRecord} is not exactly the declared
     *     unload record length
     * @throws PackedDecimalCodec.PackedDecimalException if the prefix bytes are malformed for the packed
     *     regime, which includes a digit in the sign position and a non-zero leading pad nibble
     * @throws ArithmeticException if the decoded identifier is not a whole number
     */
    public static Long unloadedAccountId(byte[] unloadRecord) {
        requireUnloadRecordLength(unloadRecord);
        BigDecimal decoded = PackedDecimalCodec.decodePacked(unloadRecord, 0,
                UNLOAD_ROOT_KEY_DIGITS, 0, true);
        return Long.valueOf(decoded.toBigIntegerExact().longValueExact());
    }

    /**
     * Extracts the 200-byte segment image that follows the packed prefix of one unload record.
     *
     * <p>Assumptions: the reference declaration treats the child as an opaque {@code PIC X(200)} behind
     * the key at {@code cbl/PAUDBUNL.CBL} L48, so copying that span out and handing it to the one
     * registered layout is a transcription of the declaration rather than a shortcut around it.</p>
     *
     * @param unloadRecord one prefixed unload record; must not be null and must be exactly the declared
     *     unload record length
     * @return a newly allocated copy of the segment image, exactly the declared segment length
     * @throws NullPointerException if {@code unloadRecord} is null
     * @throws IllegalArgumentException if {@code unloadRecord} is not exactly the declared
     *     unload record length
     */
    public static byte[] unloadedSegment(byte[] unloadRecord) {
        requireUnloadRecordLength(unloadRecord);
        byte[] segment = new byte[SEGMENT.reclen()];
        System.arraycopy(unloadRecord, UNLOAD_ROOT_KEY_WIDTH, segment, 0, SEGMENT.reclen());
        return segment;
    }

    /**
     * Decodes one prefixed unload record into the entity, taking the account identifier from its prefix.
     *
     * <p>Assumptions: this is the only decode path in this class that does not need an account identifier
     * supplied, because the unload form carries one. That is the prefix's whole purpose: without it, the
     * four child images of a two-parent unload file could not be attributed to their parents at all,
     * since nothing inside the child segment names the account.</p>
     *
     * @param unloadRecord one prefixed unload record; must not be null and must be exactly the declared
     *     unload record length
     * @return a populated entity whose key carries the decoded prefix and the decoded complements
     * @throws NullPointerException if {@code unloadRecord} is null
     * @throws IllegalArgumentException if {@code unloadRecord} is not exactly the declared unload record
     *     length, if a stored complement is out of range, or if the embedded segment's match status is one
     *     an insert path cannot originate
     * @throws PackedDecimalCodec.PackedDecimalException if the prefix bytes are malformed for the packed
     *     regime
     * @throws FixedWidthCodec.FieldCodecException if any field of the embedded segment is malformed for
     *     its declared storage regime
     * @throws ArithmeticException if the prefix or a decoded key component exceeds the range its target
     *     holds
     */
    public static PendingAuthDetail fromUnloadRecord(byte[] unloadRecord) {
        return toEntity(unloadedSegment(unloadRecord), unloadedAccountId(unloadRecord));
    }

    /**
     * Refuses a byte array that is not exactly one prefixed unload record.
     *
     * <p>Assumptions: the length is checked before the prefix is addressed, because a 200-byte segment
     * image offered here would decode its first six bytes -- the two packed key complements -- as though
     * they were a parent account identifier, and those bytes are perfectly well-formed packed decimal.
     * The result would be a plausible account number belonging to no account.</p>
     *
     * <p>Alternatives Considered: raising the shared codec's own record-length exception so that a caller
     * could catch one type for every length refusal in the migration. Rejected because that type's
     * constructor is private to the codec, which is a deliberate property rather than an oversight: only
     * the class that owns a registered layout may claim a record is the wrong length for it, and no
     * 206-byte layout is registered. A plain illegal-argument refusal is raised instead, which the codec's
     * own exceptions also extend, so a caller catching that one type still handles both.</p>
     *
     * @param unloadRecord the array offered as an unload record
     * @throws NullPointerException if {@code unloadRecord} is null
     * @throws IllegalArgumentException if {@code unloadRecord} is not exactly the declared unload record
     *     length
     */
    private static void requireUnloadRecordLength(byte[] unloadRecord) {
        Objects.requireNonNull(unloadRecord, "unloadRecord must not be null");
        if (unloadRecord.length != unloadRecordLength()) {
            throw new IllegalArgumentException("a prefixed unload record of " + SEGMENT.name()
                    + " expects exactly " + unloadRecordLength() + " bytes but received "
                    + unloadRecord.length);
        }
    }

    /**
     * The six components of the detail screen that describe the screen rather than the authorization.
     *
     * <p>Assumptions: these six exist in the symbolic map and in NO segment, which is why they are
     * carried in separately rather than derived from the entity. The map declares them at its L24, L30,
     * L36, L42, L48 and L54 -- a transaction name, two title lines, a program name, and the date and time
     * the screen was rendered -- and the reference programs fill them from the running task's own
     * identity and clock, neither of which is a property of the authorization being displayed.</p>
     *
     * <p>Alternatives Considered: adding six parameters to the projection instead of grouping them.
     * Rejected because five of the six are strings and four of those are interchangeable at the call
     * site, so a transposed pair would compile and would publish the program name where a title belongs.
     * A record makes each one nameable at the point it is supplied. Passing the rendering instant rather
     * than two pre-rendered strings is the same argument once more: the two screen fields are two
     * renderings of one instant, and letting a caller supply them separately would let them
     * disagree.</p>
     *
     * @param transactionName the four-character transaction identifier the screen was reached by
     * @param title01 the first title line, carried verbatim from the reference title constants
     * @param programName the eight-character name of the program the screen belongs to
     * @param title02 the second title line, carried verbatim from the reference title constants
     * @param renderedAt the instant the screen was produced, from which both screen clock fields are
     *     rendered so the two cannot disagree
     * @param message the message line the screen displays, bounded at the 78 characters the map's message
     *     field declares rather than at the 75 the house error contract uses elsewhere
     */
    public record ScreenContext(String transactionName, String title01, String programName,
            String title02, LocalDateTime renderedAt, String message) {
    }

    /**
     * Projects a stored authorization onto the 27-component detail response the screen is specified by.
     *
     * <p>Assumptions: the component set is a PROJECTION and not the segment. It omits the authorization
     * identification code, the acquirer country code, the message type, the raw response code and the raw
     * originating date and time, because {@code cbl/COPAUS1C.cbl} L295 to L342 never move those to the
     * map; and it adds six components of screen chrome no segment holds. The segment also leaves 27 leaf
     * components once its group header and its trailing padding are set aside, and the equality of the
     * two counts is a coincidence rather than a correspondence -- mapping the two sets onto each other
     * one for one would put the wrong value in most positions.</p>
     *
     * <p>Assumptions: four values are RENDERED here rather than passed through, and each rendering is the
     * reference program's. The originating date is re-ordered month-first, the originating time is
     * re-spaced with colons, the card expiry is widened by a separator, and the fraud position is composed
     * with its report date. The approved-or-declined character is derived by the sibling view mapper,
     * whose rule is the unconditional test at {@code cbl/COPAUS0C.cbl} L536 to L540 and is reused rather
     * than restated so this package holds one answer to that question.</p>
     *
     * <p>Assumptions: the account number is masked to its last four digits and no verification value is
     * published, for the reasons recorded in this class's charter above. The amount published is the
     * APPROVED amount and not the requested one: {@code cbl/COPAUS1C.cbl} L308 to L309 move
     * {@code PA-APPROVED-AMT} to the display field, and the two differ on a declined authorization -- the
     * fourth record of {@code unload-prefixed-detail-206.bin} requests 125.00 and approves nothing.
     * Publishing the requested amount would show a declined authorization as though it had gone
     * through.</p>
     *
     * @param detail the stored authorization to project; must not be null
     * @param declineDescription the description the caller's decline-reason lookup resolved for this
     *     authorization's response reason, or null when the lookup found no entry
     * @param context the six screen components the segment has no field for; must not be null
     * @return a fully populated response carrying rendered and masked values
     * @throws NullPointerException if {@code detail} or {@code context} is null
     * @throws IllegalArgumentException if a stored value is not the width its declaring picture fixes,
     *     which a rendering cannot proceed from
     */
    public static PendingAuthDetailResponse toResponse(PendingAuthDetail detail,
            String declineDescription, ScreenContext context) {
        Objects.requireNonNull(detail, "detail must not be null");
        Objects.requireNonNull(context, "context must not be null");

        return new PendingAuthDetailResponse(
                context.transactionName(),
                context.title01(),
                renderScreenDate(context.renderedAt()),
                context.programName(),
                context.title02(),
                renderScreenTime(context.renderedAt()),
                maskedCardNumber(detail.getCardNum()),
                renderOriginatingDate(detail.getAuthOrigDate()),
                renderOriginatingTime(detail.getAuthOrigTime()),
                PendingAuthViewMapper.approvalStatusOf(detail.getAuthRespCode()),
                composeDeclineReason(detail.getAuthRespReason(), declineDescription),
                detail.getProcessingCode(),
                approvedAmountOf(detail),
                renderPosEntryMode(detail.getPosEntryMode()),
                detail.getMessageSource(),
                detail.getMerchantCategoryCode(),
                renderCardExpiry(detail.getCardExpiryDate()),
                detail.getAuthType(),
                detail.getTransactionId(),
                detail.getMatchStatus(),
                renderFraudMark(detail.getAuthFraud(), detail.getFraudReportDate()),
                detail.getMerchantName(),
                detail.getMerchantId(),
                detail.getMerchantCity(),
                detail.getMerchantState(),
                detail.getMerchantZip(),
                context.message());
    }

    /**
     * Composes the 20-character response reason from its code and its resolved description.
     *
     * <p>Assumptions: the reference lookup at {@code cbl/COPAUS1C.cbl} L319 to L328 runs on EVERY
     * authorization and not only on declined ones, and its two branches replace different amounts of the
     * value. When the table resolves the stored reason, L325 to L327 write that reason, a separator and
     * the description. When it does not, L321 to L323 write a code of four nines and the word this class
     * carries verbatim -- so the fallback replaces the stored code as well as the description, and
     * carrying the stored code through beside a fallback description would publish a pair the reference
     * screen never showed.</p>
     *
     * <p>Assumptions: the composition itself belongs to the response type, which bounds the whole at
     * twenty characters and truncates the description at fifteen. That truncation is the reference
     * behaviour rather than a limitation: L327 targets a receiver of exactly fifteen positions, so the
     * sixteenth character of a description never reached the screen.</p>
     *
     * @param storedReason the response reason as stored, which may be null or blank
     * @param declineDescription the description the caller's lookup resolved, or null when it found none
     * @return the composed twenty-character value the screen displayed
     */
    private static String composeDeclineReason(String storedReason, String declineDescription) {
        if (isBlank(declineDescription) || isBlank(storedReason)) {
            return PendingAuthDetailResponse.composeAuthResponseReason(UNKNOWN_REASON_CODE,
                    UNKNOWN_REASON_DESCRIPTION);
        }
        return PendingAuthDetailResponse.composeAuthResponseReason(storedReason, declineDescription);
    }

    /**
     * Supplies the approved amount as the money type the response declares.
     *
     * <p>Assumptions: an absent amount becomes an explicit zero rather than being published as null,
     * because the reference display field is an edited numeric that always rendered digits and had no
     * blank state. The bound applied is the picture's ten integer digits, and the general money contract
     * is used throughout -- scale two, half-up -- so the value reaches the wire as a fixed-point string
     * and never as a number a client could route through binary floating point.</p>
     *
     * @param detail the stored authorization whose approved amount is published
     * @return the approved amount at scale two, or an exact zero when the authorization carries none
     * @throws ArithmeticException if the stored magnitude needs more than the declared ten integer digits
     */
    private static Money approvedAmountOf(PendingAuthDetail detail) {
        BigDecimal approved = detail.getApprovedAmount();
        return approved == null ? Money.ZERO : Money.ofPicture(approved, MONEY_INTEGER_DIGITS);
    }

    /**
     * Renders the screen's current-date field from the instant the screen was produced.
     *
     * <p>Assumptions: the form is month-first with a two-digit year, matching the eight-character field
     * the map declares and the same ordering the reference application uses for every date it displays.
     * It is rendered from the instant rather than accepted as a string so that this field and the
     * current-time field beside it cannot describe two different moments.</p>
     *
     * @param renderedAt the instant the screen was produced; must not be null
     * @return the eight-character month-first form
     * @throws NullPointerException if {@code renderedAt} is null
     */
    private static String renderScreenDate(LocalDateTime renderedAt) {
        Objects.requireNonNull(renderedAt, "renderedAt must not be null");
        return String.format("%02d/%02d/%02d", renderedAt.getMonthValue(),
                renderedAt.getDayOfMonth(), renderedAt.getYear() % CLOCK_FIELD_MODULUS);
    }

    /**
     * Renders the screen's current-time field from the instant the screen was produced.
     *
     * <p>Assumptions: the form is colon-separated hours, minutes and seconds, matching the
     * eight-character field the map declares. Sub-second precision is dropped because the field has no
     * position for it, which is also why this rendering and the authorization's own time rendering happen
     * to share a shape while carrying entirely different values.</p>
     *
     * @param renderedAt the instant the screen was produced; must not be null
     * @return the eight-character colon-separated form
     * @throws NullPointerException if {@code renderedAt} is null
     */
    private static String renderScreenTime(LocalDateTime renderedAt) {
        Objects.requireNonNull(renderedAt, "renderedAt must not be null");
        return String.format("%02d:%02d:%02d", renderedAt.getHour(), renderedAt.getMinute(),
                renderedAt.getSecond());
    }
}
