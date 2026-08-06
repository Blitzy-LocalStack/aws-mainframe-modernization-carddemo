package com.carddemo.batch.mapper;

import com.carddemo.batch.domain.CardXref;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Converts the 50-byte card cross-reference record of {@code app/cpy/CVACT03Y.cpy} to and from
 * {@link CardXref} for the batch bounded context.
 *
 * <p>This is the narrowest record shape this module reads: three fields and a trailing pad, with no
 * money, no packed decimal, no binary storage and no timestamp. Every migrated batch job
 * nevertheless depends on it, because the account identifier a posting or accrual step works with
 * is resolved through this record rather than taken from the transaction being processed.</p>
 *
 * <h2>The geometry this mapper is written against</h2>
 *
 * <table border="1">
 *   <caption>Field geometry derived from {@code app/cpy/CVACT03Y.cpy:4-8}</caption>
 *   <tr><th>Copybook field</th><th>Line</th><th>PICTURE</th><th>Offset</th><th>Length</th>
 *       <th>Kind</th><th>Entity property</th></tr>
 *   <tr><td>{@code XREF-CARD-NUM}</td><td>5</td><td>{@code X(16)}</td><td>0</td><td>16</td>
 *       <td>{@code TEXT}</td><td>{@code cardNum}</td></tr>
 *   <tr><td>{@code XREF-CUST-ID}</td><td>6</td><td>{@code 9(09)}</td><td>16</td><td>9</td>
 *       <td>{@code UINT}</td><td>{@code customerId}</td></tr>
 *   <tr><td>{@code XREF-ACCT-ID}</td><td>7</td><td>{@code 9(11)}</td><td>25</td><td>11</td>
 *       <td>{@code UINT}</td><td>{@code accountId}</td></tr>
 *   <tr><td>{@code FILLER}</td><td>8</td><td>{@code X(14)}</td><td>36</td><td>14</td>
 *       <td>{@code TEXT}</td><td>dropped</td></tr>
 * </table>
 *
 * <p>Assumptions: the mapped fields plus the dropped pad account for every byte, and the sum is
 * written out because an unnoticed shortfall shifts every field after it: 16 plus 9 plus 11 plus 14
 * is the declared 50 that {@code app/cpy/CVACT03Y.cpy:2} states as {@code RECLN 50}. The banner is
 * treated as a cross-check rather than as the source, because banner regimes are not uniform across
 * the copybook library, whereas summing the declared widths is. Two file definitions written
 * independently of that banner agree with it -- {@code app/cbl/CBACT04C.cbl:69-74} subdivides the
 * record into the same 16, 9, 11 and 14 -- and so does the dataset definition, which declares
 * {@code RECORDSIZE(50 50)} at {@code app/jcl/XREFFILE.jcl:44}.</p>
 *
 * <p>Assumptions: the two identifier offsets are corroborated by sources that share no lineage with
 * the copybook, which is what removes the last doubt from them. {@code app/jcl/XREFFILE.jcl:43}
 * declares the base cluster key as {@code KEYS(16 0)} -- sixteen bytes at offset zero, exactly the
 * span of {@code XREF-CARD-NUM} -- and {@code app/jcl/XREFFILE.jcl:74} declares the alternate index
 * as {@code KEYS(11,25)}, eleven bytes at offset 25, exactly where {@code XREF-ACCT-ID} falls by
 * summation. An offset agreed by a copybook and by a dataset definition is not a transcription that
 * needs re-deriving later.</p>
 *
 * <h2>The round-trip property, and its one caveat</h2>
 *
 * <p>Assumptions: {@code toRecord(toEntity(bytes))} returns bytes equal to its input for any valid
 * 50-byte record whose pad is blank, and that property is stated here so a test author knows to
 * assert it rather than infer it. It holds because the decode preserves the card number at its full
 * declared width, both identifiers survive a digits-to-{@code Long}-to-digits crossing with their
 * leading zeros restored by left-padding, and the pad this mapper drops is written back as the same
 * blanks it dropped.</p>
 *
 * <p>Trade-offs: the property is stated for a blank pad specifically, because a record carrying
 * non-blank bytes in the 14-byte span at offset 36 loses them. {@link CardXref} declares no member
 * for the pad, so once the entity exists those bytes are gone and the encode restores blanks in
 * their place. That loss is intended rather than tolerated: the pad is padding to a fixed record
 * length, so carrying an entity member for it would put a column on
 * {@code account.card_xref} whose only content is whatever a producer happened to leave there.
 * Preserving the bytes instead would mean either widening the entity or threading a byte array
 * alongside it, and both trade a documented, bounded loss for a permanent one-record exception to
 * the way every other record in this package is handled.</p>
 *
 * <h2>What this mapper does not do</h2>
 *
 * <p>Assumptions: the record is decoded as US-ASCII, through the two-argument codec entry points
 * rather than the charset-bearing overloads. The alternative was to pass {@code IBM037} so that a
 * mainframe-encoded extract could be read directly, and it is refused because it would decode the
 * committed ASCII datasets under {@code app/data/ASCII} as unrelated characters while leaving every
 * field width plausible. Character-set translation happens once, at the extract-transform-load
 * edge, and it is deliberately not a record-wide operation:
 * {@code data-migration/src/carddemo_migration/__init__.py:23} assigns cp037 decoding to that
 * package's own codec and states the rule as per fixed-width field and never per record, so that
 * sign bytes and packed nibbles are never routed through a text decoder. By the time a record
 * reaches this module it is already ASCII, and a second translation here would corrupt it.</p>
 *
 * <p>Assumptions: this type reads and writes nothing. It holds no repository, opens no transaction
 * and performs no lookup, so the two access paths the baseline uses over this file -- by card number
 * over the base cluster and by account identifier over the alternate index -- are resolved by the
 * service layer and merely converted here. That division is what lets this class be exercised
 * against a byte array with no database and no application context.</p>
 */
@Component
public class CardXrefRecordMapper {

    /**
     * The logical name the card cross-reference layout is registered under in the shared kernel.
     *
     * <p>Assumptions: the name is the whole of the coupling between this mapper and the descriptor
     * it reads, because the shared kernel's registry resolves geometry by exact name and offers no
     * other handle. Naming it in one constant rather than at each use is what makes that coupling a
     * single line a reader can find.</p>
     */
    private static final String LAYOUT_NAME = "XREF";

    /**
     * The copybook name of the sixteen-character card number that keys this record.
     */
    private static final String FIELD_CARD_NUM = "XREF-CARD-NUM";

    /**
     * The copybook name of the nine-digit customer identifier.
     */
    private static final String FIELD_CUSTOMER_ID = "XREF-CUST-ID";

    /**
     * The copybook name of the eleven-digit account identifier.
     */
    private static final String FIELD_ACCOUNT_ID = "XREF-ACCT-ID";

    /**
     * The validated layout descriptor every offset, width and storage kind in this class is taken
     * from.
     *
     * <p>Assumptions: the descriptor is held rather than resolved per call so that one registry
     * lookup serves every record in a feed, and so that the field metadata a diagnostic needs is
     * reachable without a second lookup on the failure path.</p>
     */
    private final CopybookLayout.RecordSpec layout;

    /**
     * Creates the mapper and resolves the card cross-reference layout from the shared registry.
     *
     * <p>Assumptions: the descriptor is obtained from
     * {@link CopybookLayout#layout(String)} and is never declared locally. The migration plan's
     * transformation rule T2 puts layout ownership in the shared kernel, so a second descriptor for
     * this record authored here would be a second truth about the same 50 bytes -- and it would
     * drift silently, because each copy stays internally consistent while they disagree with each
     * other, and nothing fails at the moment they diverge. One descriptor for this record already
     * exists outside Java and cannot be collapsed into the kernel's:
     * {@code data-migration/src/carddemo_migration/copybook/layouts.py:2120-2131} declares the same
     * geometry for the extract-transform-load path, down to the same offsets and the same
     * {@code XREF-ACCT-ID} alternate key at offset 25. Two descriptors across a language boundary are
     * unavoidable and are reconciled by both quoting the copybook; a third one inside this module
     * would be neither.</p>
     *
     * <p>Alternatives Considered: the lookup happens here, at construction, rather than inside each
     * conversion. Resolving per call was the alternative and is rejected on when it fails rather
     * than on cost: an unregistered or renamed layout would then surface on the first record of a
     * nightly feed, after the step had already started and taken its input, whereas resolving once
     * at construction fails the application context before any job runs. The registry itself
     * supplies the loud failure this needs -- it rejects an unknown name and lists every name that
     * is registered -- so nothing here has to detect the miss, and no fallback is offered, because
     * a mapper with no geometry cannot slice a record and a guessed geometry yields plausible
     * digits instead of an error.</p>
     */
    public CardXrefRecordMapper() {
        this.layout = CopybookLayout.layout(LAYOUT_NAME);
    }

    /**
     * Converts one physical card cross-reference record into its entity form.
     *
     * <p>Assumptions: exactly one record in, exactly one entity out. A caller resolving by ACCOUNT
     * identifier must therefore expect a SEQUENCE of records and call this once per record, because
     * that access path is not unique: {@code app/jcl/XREFFILE.jcl:74-75} defines the alternate index
     * over {@code XREF-ACCT-ID} with {@code NONUNIQUEKEY}, which is the dataset stating outright
     * that one account may hold more than one card. This mapper cannot collapse such a group and
     * must not appear to -- it sees one 50-byte image and has no view of the ones either side of it,
     * so a caller that treated a single conversion as the answer for an account would silently use
     * whichever card the read returned first.</p>
     *
     * <p>Assumptions: {@code XREF-CARD-NUM} is returned at its full declared width of sixteen
     * characters and its trailing blanks are NOT trimmed. The width is part of the contract rather
     * than an upper bound, because the field is the whole of the base cluster key -- sixteen bytes
     * at offset zero, per {@code app/jcl/XREFFILE.jcl:43} -- and the target column is a fixed-width
     * {@code CHAR(16)}. The baseline presents a key of exactly sixteen bytes every time, since
     * {@code app/cbl/CBTRN02C.cbl:382} forms it by moving a value into a {@code PIC X(16)} key
     * field, so a trimmed value would compare unequal to the key it is looked up by and the row
     * would be missed. This is the opposite of the treatment a DESCRIPTIVE character field gets
     * elsewhere in this package, where trailing blanks are padding and are trimmed; applying that
     * general rule here breaks a primary key without failing anything first.</p>
     *
     * <p>Assumptions: {@code XREF-CUST-ID} and {@code XREF-ACCT-ID} are read as plain unsigned
     * digits and never through the sign-overpunch path, because
     * {@code app/cpy/CVACT03Y.cpy:6} declares {@code PIC 9(09)} and
     * {@code app/cpy/CVACT03Y.cpy:7} declares {@code PIC 9(11)} -- both WITHOUT the leading
     * {@code S} that would make them signed. Signedness is taken from the descriptor's own flag and
     * is never inferred from the bytes, and that distinction is the single most likely source of
     * silent corruption in this record.</p>
     *
     * <p>Assumptions: the corpus proves that inference wrong rather than merely inelegant, and the
     * proof lands on a span the same width as {@code XREF-ACCT-ID}, which is why it belongs here.
     * {@code app/data/ASCII/dailytran.txt} contains the eleven-character runs
     * {@code 3580010001P}, {@code 2252010001P}, {@code 1861010001P}, {@code 2564010001P} and
     * {@code 4260030001O}. Each ends in a letter that appears in the negative overpunch table, so
     * each looks exactly like a signed {@code PIC S9(09)V99} amount, and none of them is one -- none
     * is a field at all, since each sits at zero-based offset 12 of its own 350-byte
     * daily-transaction record and straddles four declarations. What that establishes for this
     * mapper is that an eleven-character run ending in an overpunch letter is a shape this baseline's
     * data really contains, so a decoder that resolved the trailing byte of an eleven-byte span by
     * looking at it would have real input to be wrong about. It would consume that character as a
     * sign, take the digit body one character short, and return a value wrong by an order of
     * magnitude and negative, while still reading as an account identifier. Only the descriptor's
     * {@code signed} flag, false here because {@code app/cpy/CVACT03Y.cpy:7} omits the {@code S},
     * stands between the two readings.</p>
     *
     * <p>Assumptions: a non-digit anywhere in either identifier span is rejected rather than
     * coerced, skipped or read as far as it parses. The shared codec raises and names the relative
     * offset of the offending byte, which localises a real geometry defect immediately; the
     * alternative of parsing the leading digits would return a short number from a shifted record
     * and post it against an account that exists, which is the failure mode that never gets
     * noticed.</p>
     *
     * @param record the byte array holding one physical record, which must be exactly 50 bytes --
     *     the length the shared descriptor declares and the length
     *     {@code app/jcl/XREFFILE.jcl:44} fixes as {@code RECORDSIZE(50 50)}
     * @return the equivalent CardXref, never {@code null}, carrying the card number at its full
     *     sixteen characters and both identifiers as {@code Long}
     * @throws FixedWidthCodec.RecordLengthException if {@code record} is {@code null} or is not
     *     exactly 50 bytes long
     * @throws FixedWidthCodec.FieldCodecException if either identifier span holds a byte that is
     *     not an ASCII digit, if an identifier exceeds the integral range, or if the card number
     *     span does not survive a character round trip
     * @throws IllegalStateException if the registered descriptor no longer produces the field names
     *     and value types this mapper is written against
     */
    public CardXref toEntity(byte[] record) {
        // WHY : Alternatives Considered: the length and null checks are left to the codec instead of
        //       being repeated here. Its own check runs before the first slice and names both the
        //       expected and the received width, so a guard added here would duplicate a stricter
        //       check with a weaker message and would then have to be kept in step with it.
        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(record, layout);

        // WHY : Assumptions: the pad is absent from this map and is never read back out of it. The
        //       codec omits a blank registered pad on decode, and this mapper wants exactly that
        //       omission -- app/cpy/CVACT03Y.cpy:8 declares a bare FILLER PIC X(14) carrying neither
        //       a REDEFINES clause nor a VALUE clause, which is what makes it padding to the fixed
        //       record length rather than content. The two clause-bearing forms would each have to
        //       be carried, so the test applied is the clause the field declares and never the fact
        //       that it happens to be named FILLER.
        return new CardXref(
                decodedCardNumber(decoded),
                decodedIdentifier(decoded, FIELD_CUSTOMER_ID),
                decodedIdentifier(decoded, FIELD_ACCOUNT_ID));
    }

    /**
     * Converts one entity into its physical card cross-reference record.
     *
     * <p>Assumptions: the returned array is ALWAYS exactly 50 bytes, never shorter and never merely
     * long enough for the values supplied. That guarantee is stated explicitly because a caller
     * writing a sequential dataset depends on it: a record right in its first 36 bytes and 14 bytes
     * short still writes, still reads back, and shifts every field of every record after it.</p>
     *
     * <p>Assumptions: the pad is supplied to the codec by OMISSION and is written back as blanks.
     * The pad at {@code app/cpy/CVACT03Y.cpy:8} is left out of the field map deliberately, and the
     * codec restores a registered pad it was not given by filling the span with the charset's blank
     * byte. Leaving the freshly allocated array's zero bytes in place instead would preserve the
     * record's length but not its content, and a COBOL reader distinguishes low values from spaces
     * even though both look empty once read into a string.</p>
     *
     * <p>Trade-offs: the two directions therefore treat the pad differently -- dropped on the way in,
     * reconstructed on the way out -- and the asymmetry is the ruling rather than an inconsistency to
     * be tidied away. Handling both directions alike is the error: dropping it on encode as well
     * would emit 36 bytes where the dataset declares 50, and carrying it on decode as well would put
     * fourteen bytes of inert padding into the entity and into every consumer downstream of it. What
     * the asymmetry costs is that a decode followed by an encode is only byte-exact when the source
     * pad was blank, which the class documentation states as the caveat on the round-trip
     * property.</p>
     *
     * @param entity the CardXref row to render, whose three properties supply the three mapped
     *     fields; must not be {@code null}
     * @return a newly allocated byte array of exactly 50 bytes, with the card number left-justified
     *     and blank-filled to sixteen characters, both identifiers zero-filled to their declared
     *     digit counts, and the 14-byte pad at offset 36 blank
     * @throws NullPointerException if {@code entity} is {@code null}
     * @throws IllegalArgumentException if any of the entity's three properties is {@code null},
     *     since no column of the target row permits one
     * @throws FixedWidthCodec.FieldCodecException if a supplied value does not fit its declared
     *     field -- a card number longer than sixteen characters, a negative identifier, or an
     *     identifier needing more digits than its field holds
     */
    public byte[] toRecord(CardXref entity) {
        if (entity == null) {
            throw new NullPointerException("card cross-reference entity must not be null");
        }

        // WHY : Assumptions: insertion order is preserved so the map reads in copybook declaration
        //       order, matching the order the codec walks the descriptor in. The codec keys by name
        //       and so does not require it, but a map that reads in a different order from the record
        //       it produces is a map a reader has to reconcile against the descriptor by hand.
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(FIELD_CARD_NUM, requiredProperty(entity.getCardNum(), "cardNum", FIELD_CARD_NUM));
        fields.put(FIELD_CUSTOMER_ID,
                requiredProperty(entity.getCustomerId(), "customerId", FIELD_CUSTOMER_ID));
        fields.put(FIELD_ACCOUNT_ID,
                requiredProperty(entity.getAccountId(), "accountId", FIELD_ACCOUNT_ID));

        // WHY : Assumptions: the pad is absent from this map on purpose, which is what asks the codec
        //       to blank-fill its span. Putting an explicit run of fourteen spaces in would produce
        //       the same bytes today and would stop doing so the moment the descriptor's pad width
        //       changed, because the literal would then be measured against a span it no longer fits.
        return FixedWidthCodec.encodeRecord(fields, layout);
    }

    /**
     * Projects the decoded card number out of the field map at its full declared width.
     *
     * <p>Trade-offs: the value crosses this boundary WHOLE, at all sixteen characters, even though
     * the migration plan requires at its section 0.4.1.9 that a primary account number be masked to
     * its last four digits wherever it is exposed. Masking it here is not available: the complete
     * number IS the key the cross-reference is read by, as {@code app/cbl/CBTRN02C.cbl:382} shows
     * when posting moves the daily transaction's card number into the sixteen-byte key field, so a
     * shortened value stored on the entity would fail to match the key it is looked up by and every
     * transaction would take the reject path instead. The obligation the plan imposes is discharged
     * one layer further out, wherever a value is rendered rather than resolved, and what is accepted
     * here is that this class hands its caller a value the caller must then be trusted to mask.</p>
     *
     * <p>Trade-offs: the failure branch is the other half of that decision and gives up detail to
     * keep it. The descriptor marks this field sensitive, so the diagnostic reports the field's
     * name, offset, length and kind together with the TYPE the value arrived as, and never the value
     * itself. A message quoting the offending characters would be the more useful message and would
     * also put a primary account number into a log aggregator, where a batch job renders one line
     * per record and the aggregate becomes a copy of the cross-reference in plain text. Name, offset,
     * length and kind locate any genuine geometry defect, which is the only defect this branch can
     * report, so the restriction costs nothing diagnostic.</p>
     *
     * @param decoded the Map of copybook field name to decoded value that the shared codec produced
     *     for this record
     * @return the card number as a String of exactly the declared sixteen characters, untrimmed
     * @throws IllegalStateException if the registered descriptor no longer yields this field as
     *     character data under its expected name
     */
    private String decodedCardNumber(Map<String, Object> decoded) {
        Object value = decoded.get(FIELD_CARD_NUM);
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalStateException(geometryFailure(FIELD_CARD_NUM, value));
    }

    /**
     * Projects one decoded unsigned identifier out of the field map.
     *
     * <p>Assumptions: the target type is {@code Long} for both identifiers, and for the account
     * identifier it is the only correct choice rather than a uniform one. Eleven digits, which
     * {@code app/cpy/CVACT03Y.cpy:7} declares, exceed the range of a 32-bit integer outright, so an
     * {@code Integer} target would overflow on values the dataset holds legitimately and would wrap
     * to a negative number that still reads as an identifier. The nine-digit customer identifier
     * would fit a narrower type and takes the same one anyway, so that every identifier in the
     * migration has one width and the columns behind them are uniformly {@code BIGINT}.</p>
     *
     * @param decoded the Map of copybook field name to decoded value that the shared codec produced
     *     for this record
     * @param fieldName the String holding the exact copybook name of the identifier field to project,
     *     which must be one of the two unsigned fields this record declares
     * @return the identifier as a Long, with any leading zeros of the source digits absorbed into
     *     its numeric value
     * @throws IllegalStateException if the registered descriptor no longer yields that field as an
     *     unsigned integral value under its expected name
     */
    private Long decodedIdentifier(Map<String, Object> decoded, String fieldName) {
        Object value = decoded.get(fieldName);
        if (value instanceof Long identifier) {
            return identifier;
        }
        throw new IllegalStateException(geometryFailure(fieldName, value));
    }

    /**
     * Requires that an entity property supplying a mapped field has a value.
     *
     * <p>Assumptions: there is NO permitted-null case to encode, which is why this rejects rather
     * than substituting a blank or a zero. All three columns the entity maps are declared not-null,
     * so a null property is a row no owner should have written. Encoding one anyway would be worse
     * than rejecting it in each direction it could go: a blank card number produces a record whose
     * sixteen-byte key is spaces, which writes successfully and then keys nothing, and a zero
     * identifier produces a row pointing at an account that does not exist.</p>
     *
     * <p>Alternatives Considered: letting the codec reject the null on its own was evaluated and
     * refused on the diagnostic rather than on the outcome, since it does reject it. Its message
     * names the copybook field and states that a character sequence was required, which is the right
     * vocabulary for a caller holding a byte array and the wrong one for a caller holding an entity;
     * this message names the entity property that was empty and the field it was to supply, so the
     * reader is pointed at the object they actually passed.</p>
     *
     * @param value the Object property value read from the entity, which may be {@code null}
     * @param propertyName the String naming the entity property the value was read from, used to point
     *     a caller at the object they supplied rather than at the copybook
     * @param fieldName the String holding the exact copybook name of the field this property supplies
     * @return the same Object unchanged, so the check can be applied inline where the value is used
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    private Object requiredProperty(Object value, String propertyName, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException("card cross-reference property " + propertyName
                    + ", which supplies " + describeField(fieldName)
                    + ", is null; every column this record maps is declared not-null, so there is no"
                    + " permitted-null case to render as blanks or zeros");
        }
        return value;
    }

    /**
     * Builds the message for a decoded value whose type contradicts its declared storage kind.
     *
     * <p>Assumptions: this reports a descriptor that has changed shape rather than a bad record, so
     * it names what the field was expected to be and what arrived, and stops there. A record cannot
     * reach this point with the wrong value type: the codec decodes strictly by declared kind and
     * raises on its own before returning, so the only way a projection above finds an unexpected type
     * is that the registered geometry for this record no longer matches what this class was written
     * against -- a field renamed, or its kind changed. Naming the offending value would describe the
     * data when the fault is in the descriptor.</p>
     *
     * <p>Assumptions: if the descriptor and {@code app/cpy/CVACT03Y.cpy} ever disagree, the copybook
     * is right and the descriptor is the defect, under the migration plan's transformation rule T1
     * that makes the copybook normative. This message therefore reports the disagreement instead of
     * compensating for it locally, because a local compensation would leave the shared descriptor
     * wrong for the extract-transform-load reader and the other seven mappers that share it.</p>
     *
     * @param fieldName the String holding the exact copybook name of the field whose projection
     *     failed
     * @param value the Object the field map held for that name, which may be {@code null} when the
     *     name is absent altogether
     * @return the String diagnostic message, carrying the field's name, offset, length and declared kind
     *     together with the type that arrived, and no field content
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under
     *     that name at all
     */
    private String geometryFailure(String fieldName, Object value) {
        // WHY : Assumptions: the arriving TYPE is named and the arriving VALUE is not, and the choice
        //       holds for all three fields rather than only for the sensitive one. A type name cannot
        //       carry account data, so one rule covers the record and no future field can be added to
        //       it that this branch would then disclose.
        String arrived = value == null
                ? "absent from the decoded field map"
                : value.getClass().getName();
        return "record " + layout.name() + " " + describeField(fieldName)
                + " decoded as " + arrived
                + ", which contradicts the type its declared kind implies; the registered layout no"
                + " longer matches the geometry this mapper was written against, and"
                + " app/cpy/CVACT03Y.cpy is the authority on which of the two is wrong";
    }

    /**
     * Renders one field's identity for a diagnostic, without any of its content.
     *
     * <p>Assumptions: the four properties reported here -- name, offset, length and declared kind --
     * are exactly the set this package permits a message about a sensitive field to carry, and the
     * same wording is used for every field so that no caller has to know which of the three is
     * marked sensitive in order to log safely.</p>
     *
     * @param fieldName the String holding the exact copybook name of the field to describe
     * @return a String carrying the field's name, zero-based offset, length in bytes and declared
     *     storage kind
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under
     *     that name
     */
    private String describeField(String fieldName) {
        CopybookLayout.FieldSpec field = layout.field(fieldName);
        return "field " + field.name() + " at offset " + field.start() + " with length "
                + field.length() + " and kind " + field.kind();
    }
}
