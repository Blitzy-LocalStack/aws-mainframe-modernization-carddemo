package com.carddemo.authorization.dto;

import com.carddemo.common.money.Money;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Carries one inbound pending-authorization request in the eighteen-field order its copybook
 * declares.
 *
 * <h2>Purpose, and the edge this record sits on</h2>
 *
 * <p>This is the message an external point-of-sale producer places on the authorization request
 * queue, expressed as a Java value. It is the asynchronous half of the two external edges the
 * charter at {@code com.carddemo.authorization.dto} describes, and it carries eighteen components
 * and nothing else. It reads no datastore, holds no business rule and reaches no decision: the
 * decision is taken in {@code com.carddemo.authorization.service}, the persistent shape lives in
 * {@code com.carddemo.authorization.domain}, and the crossing between the two is made in
 * {@code com.carddemo.authorization.mapper}.
 *
 * <p>Every component below is shaped from one field of
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy}, whose eighteen {@code 05} declarations
 * occupy L19 to L36 -- the whole of that file's data description, since L1 to L18 are its banner
 * and its licence header. That tree is reference material: it is read as the specification and it
 * is never modified.
 *
 * <p>Assumptions: the declaration order of those eighteen fields is the contract rather than a
 * presentational choice, and the reference consumer is what makes it so. The parse at
 * {@code cbl/COPAUA0C.cbl} is positional -- an {@code UNSTRING} opening at L354, delimited by a
 * comma at L355, filling eighteen receivers at L356 to L373 and closing at L374 -- so it takes the
 * ordinal-three token as the card number because it is third and for no other reason. A component
 * reordered here is a change of contract rather than a rearrangement, and so is one added or
 * removed.
 *
 * <h2>Three measurements, and the name of each one</h2>
 *
 * <p>Assumptions: three different numbers describe this payload and any two of them can be
 * mistaken for each other, so each is named wherever it appears and none is written as a bare
 * figure.
 *
 * <ul>
 *   <li>The <b>copybook-declared width sum is 153</b>. It is the sum of the eighteen declared
 *       widths at {@code CCPAURQY.cpy} L19 to L36 -- 6, 6, 16, 4, 4, 6, 6, 6, 14, 4, 3, 2, 15, 22,
 *       13, 2, 9 and 15 -- and it counts no delimiter, because a copybook describes fields and not
 *       the message that carries them. This is the measurement the fixture named under the
 *       test-channel heading below has to satisfy.</li>
 *   <li>The <b>comma-delimited wire length on that basis is 170</b>, which is the 153 above plus
 *       seventeen interior separators. Seventeen rather than eighteen, because eighteen fields have
 *       seventeen gaps between them and the request carries no trailing separator.</li>
 *   <li>The <b>emitted width sum is 152 and the emitted length 169</b>. Those are the codec's
 *       numbers rather than the copybook's, and the single-character difference between the two
 *       pairs is accounted for under the heading after next.
 *       {@code com.carddemo.common.codec.CsvAuthCodec} owns them as
 *       {@code REQUEST_DECLARED_WIDTH_SUM} and {@code REQUEST_WIRE_LENGTH}, beside
 *       {@code MONEY_EDITED_WIDTH} at 14 and {@code REQUEST_MONEY_WIDTH} at 13, which are the two
 *       widths whose difference produces it.</li>
 * </ul>
 *
 * <p>Trade-offs: this record states the first two measurements and leaves the second two to the
 * codec instead of restating them as constants of its own. The cost is that a reader wanting the
 * emitted figures has to open another file. What that buys is the property the charter at
 * {@code com.carddemo.authorization.dto} asserts: the wire form has a single owner, so field order
 * and emitted width live in one place rather than once per payload type, and two places cannot come
 * to disagree about them.
 *
 * <h2>The amount is an exact money value, and text wherever it is serialised</h2>
 *
 * <p>Alternatives Considered: emitting the ordinal-nine amount as a JSON number, which is the shape
     * a reader arriving from an ordinary payload format expects to find. It is rejected because most
     * clients parse a JSON number through an IEEE-754 binary representation, and a two-place decimal
     * fraction such as one cent has no finite binary expansion, so what such a client reads back is an
     * approximation of what was sent. The loss lands exactly at the boundary a user reads, and nothing
     * in the message reports that it happened. The component is therefore
     * {@code com.carddemo.common.money.Money}, whose
 * {@code toPlainString} is documented as its JSON form and whose {@code of(String)} reads that form
 * back unchanged.
 *
 * <p>Assumptions: a string preserves the contract the reference already had rather than departing
 * from it. The baseline transports the amount as text and converts it arithmetically only after the
 * split, at {@code cbl/COPAUA0C.cbl} L376 to L377, where
 * {@code COMPUTE PA-RQ-TRANSACTION-AMT = FUNCTION NUMVAL(WS-TRANSACTION-AMT-AN)} turns the received
 * characters into a number. Text on the wire is what the baseline does; a JSON number would be the
 * departure.
 *
 * <p>Assumptions: the amount's domain is twelve significant decimal digits at a scale of two,
 * signed, and two independent declarations agree on it. The wire edits it to fourteen characters as
 * {@code PA-RQ-TRANSACTION-AMT PIC +9(10).99} at {@code CCPAURQY.cpy} L27, and the segment that
 * persists it declares {@code PA-TRANSACTION-AMT PIC S9(10)V99 COMP-3} at {@code cpy/CIPAUDTY.cpy}
 * L34. Ten integer places and two decimal places in both, which is the bound
 * {@code Money.MAX_MAGNITUDE} already enforces, so no width constraint is declared on this
 * component: the type carries the domain.
 *
 * <h2>The reference receiver is one position narrower than the field it receives</h2>
 *
 * <p>Assumptions: the ordinal-nine field is fourteen characters on the wire and the reference
 * consumer receives it into thirteen. {@code CCPAURQY.cpy} L27 declares
 * {@code PA-RQ-TRANSACTION-AMT PIC +9(10).99}, which is a sign, ten digits, a point and two further
 * digits: fourteen positions. The {@code UNSTRING} at {@code cbl/COPAUA0C.cbl} L354 to L374 does
 * not receive the amount token into that field at all. Its ninth receiver, at L364, is
 * {@code WS-TRANSACTION-AMT-AN}, declared {@code PIC X(13)} at L63, and L376 to L377 then converts
 * that thirteen-character value. An alphanumeric receiver takes the leftmost characters that fit,
 * so a full fourteen-character token arrives with its final position absent and the conversion runs
 * on what remains.
 *
 * <p>Assumptions: the baseline behaves as described immediately above; the target reads all
 * fourteen positions L27 declares and does not reproduce the loss; and the divergence is documented
 * -- here, and in the register at {@code docs/architecture/cobol-to-service-traceability.md}.
 * Nothing in the reference tree is altered to arrive at that: the migration adds a path, it does
 * not remove one.
 *
 * <p>Trade-offs: this is the source of the 153-against-152 difference named under the previous
 * heading, and it is why those are two numbers rather than one. This record is declared at the
 * copybook's fourteen so that a producer sending a full-width amount is represented faithfully; the
 * codec emits at thirteen so that a message this system produces is one the reference consumer can
 * still receive. Both are deliberate and neither substitutes for the other.
 *
 * <h2>Declared widths are normative, and one field keeps its reference spelling</h2>
 *
 * <p>Assumptions: transformation rule T1 of the migration plan makes a copybook field's picture the
 * normative source of its width, its type and its offset, so every width asserted below is read
 * from {@code CCPAURQY.cpy} L19 to L36 and none is inferred from a sample message. That plan
 * numbers its transformation rules T1 to T10, and those identifiers are a different namespace from
 * the user-specified rules: T1 is not Rule 1 and Rule 1 is not T1, so a citation blending the two
 * sends a reader to the wrong document entirely.
 *
 * <p>Assumptions: two facts about the ordinal-ten field are true at the same time, and neither
 * survives being reconciled with the other. On the wire the field keeps its reference spelling,
 * {@code PA-RQ-MERCHANT-CATAGORY-CODE} at {@code CCPAURQY.cpy} L28, in its ordinal-ten position,
 * because the wire is a contract with an external producer and a name that producer does not use is
 * not a name the wire can carry. In Java, and in the column that persists it, the spelling is
 * {@code merchantCategoryCode}. The reference spelling also appears in the persisted layout at
 * {@code cpy/CIPAUDTY.cpy} L36, so the target spelling is a uniform target-side choice rather than
 * something applied at one edge only.
 *
 * <h2>The delimited text is the interface, and an envelope is offered beside it</h2>
 *
 * <p>Assumptions: the payload is declared to the queue as string-format data, inbound at
 * {@code cbl/COPAUA0C.cbl} L397 and outbound at L751, each moving {@code MQFMT-STRING} into the
 * message descriptor's format. With a string-format payload no schema travels with the message, so
 * the field order and the delimiter are not an encoding detail sitting underneath the interface:
 * they are the interface.
 *
 * <p>Assumptions: every field contributes its full declared width, space-padded, and nothing is
 * trimmed. The program's only builder of a delimited payload is the {@code STRING} at L722 to L731,
 * which interleaves a comma literal between values, uses {@code DELIMITED BY SIZE} at L728 and
 * closes at L731; that phrase takes each value at its declared size rather than up to a delimiter,
 * so the padding is part of what is sent, and a consumer that trims changes the byte count. Two
 * limits of this evidence are stated rather than glossed over: L722 to L731 builds the reply, since
 * the program consumes requests and produces replies, and no request producer exists in this
 * repository to observe -- so the padding discipline is read from the emitter the program does have
 * together with the receiver widths its request parse fills.
 *
 * <p>Trade-offs: an envelope carrying these components by name is offered in addition to the
 * delimited form and never in place of it. Such an envelope is readable without knowing an ordinal,
 * which is a real gain and the whole reason it is offered. What is not done is replacing the
 * delimited form on the existing queue: the producer on the far side is external and sends the
 * eighteen-token text described above, so a queue accepting only an envelope would stop accepting
 * the traffic this context exists to process. The accepted cost is two representations of one
 * payload; the compensation is that neither consumer is broken to serve the other.
 *
 * <p>Alternatives Considered: a compact canonical constructor that trims each component and
 * reports an all-padding value as absent, which is what the sibling
 * {@code com.carddemo.reporting.dto.StatementRequest} does and is therefore the shape a reader of
 * this tree has reason to expect here. It is rejected because the reason that holds there is absent
 * here and its opposite applies. That type is a report selector, where trailing padding carries no
 * meaning and absence is a state the selector has to express; this type is the wire payload, where
 * padding is part of the declared width the emitter sends and removing it changes the message
 * length. Trimming on construction would leave this record unable to represent the bytes it was
 * built from. No constructor is declared for that reason, and with none declared there is no body
 * in which a width could be asserted a second time: each width is asserted once, by the constraint
 * on its own component in the header below.
 *
 * <h2>Not generated, and named for the edge it serves</h2>
 *
 * <p>Alternatives Considered: Lombok, which would supply the accessors a class holding these
 * eighteen values would otherwise have to declare. It is not used, because a generated accessor
 * cannot carry the docstring user-specified Rule 1 (Explainability) L15 requires of it, so the
 * brevity would be bought by making a documentation obligation unmeetable. A Java 21 record reaches
 * the same brevity from the other direction: the accessors come from the language rather than from
 * a generator, and every component is declared explicitly and documented by its own {@code @param}
 * tag on this type.
 *
 * <p>Alternatives Considered: MapStruct, to generate the crossing between this payload and the
 * persistent shape. It is not used, because that crossing is not mechanical. It drops
 * {@code FILLER}, which {@code cpy/CIPAUDTY.cpy} declares at L54 purely to reach the record length;
 * it masks a primary account number to its last four digits; it suppresses a card verification
 * value wherever one occurs; and it changes the reference field spellings, of which the ordinal-ten
 * code named above is the one falling in this context. Each of those is a decision a reader cannot
 * recover from the code, so each needs a justification written at the site performing it, and a
 * generated mapper has nowhere to hold one. The crossing is hand-written in
 * {@code com.carddemo.authorization.mapper} for that reason.
 *
 * <p>Alternatives Considered: naming this type {@code AuthorizationRequest}. The suffix is kept,
 * because this context has two inbound request surfaces -- the queue edge this type serves and the
 * synchronous HTTP edge -- and a name carrying neither would leave a reader to work out which was
 * meant. The suffix names the queue: this is the delimited wire contract, not the HTTP surface.
 *
 * <h2>Two components this record deliberately does not have</h2>
 *
 * <p>Assumptions: there is no card-verification-value component, and none may be added. No field of
 * {@code CCPAURQY.cpy} L19 to L36 declares one, so a component for it would be an addition to the
 * wire rather than a migration of it; and the masking and suppression this context applies are
 * performed in {@code com.carddemo.authorization.mapper}, which is where representation concerns
 * are permitted to appear at all. A value serialised straight out of this record would bypass that
 * single point, which is precisely what routing every crossing through the mapper prevents.
 *
 * <p>Assumptions: there is no message-expiry component either. The reference sets an expiry on the
 * message descriptor rather than inside the payload -- {@code cbl/COPAUA0C.cbl} L750 moves 50 into
 * {@code MQMD-EXPIRY} of the reply descriptor, a field whose unit is tenths of a second, so five
 * seconds -- and the target likewise carries the expiry instant as a message attribute. A
 * nineteenth component would break the eighteen-field count the positional parse at L354 to L374
 * depends on.
 *
 * <h2>How the components are constrained, and one hazard in re-deriving them</h2>
 *
 * <p>Assumptions: {@code @NotNull} on every component expresses the field count rather than a
 * business requirement. The wire always carries eighteen tokens, so a token is present even when it
 * is entirely padding, and a component absent altogether means a message that failed its count
 * check before reaching this record. Whether a token is blank, and whether a blank one is
 * acceptable, is a rule about the request rather than about its shape, and the charter at
 * {@code com.carddemo.authorization.dto} closes this package to business rules; that question is
 * answered in {@code com.carddemo.authorization.service}.
 *
 * <p>Assumptions: the two numerically-pictured components are strings of digits and not numeric
 * types. {@code PA-RQ-PROCESSING-CODE PIC 9(06)} at L26 and {@code PA-RQ-POS-ENTRY-MODE PIC 9(02)}
 * at L30 carry numeric pictures, and the persisted layout agrees at {@code cpy/CIPAUDTY.cpy} L33
 * and L38, but the migration plan's reading of the reference overlays settles the representation the
 * other way: the baseline declares such identifiers over the same bytes twice, once as characters
 * and once as a number, so the characters are what the wire carries and the number exists only so
 * that arithmetic can reach the same storage. Neither value is arithmetic here -- a processing code
 * is a code and an entry mode is an enumeration -- and a string preserves a leading zero that a
 * numeric type discards, so nothing is lost by keeping them as characters and a leading zero is
 * not.
 *
 * <p>Alternatives Considered: writing each declared width as a literal inside its constraint
 * annotation. Named constants are declared in the body below instead, so that each width has one
 * executable position which can be compared against the single copybook line it was read from,
 * rather than a bare number sitting in an annotation with no stated provenance. The widths are
 * quoted in the component descriptions below as well, and that is prose rather than a second
 * executable source: nothing reads those sentences to decide anything.
 *
 * <p>Assumptions: a caution for anyone re-deriving these eighteen fields from the copybook. That
 * file separates a field name from its picture with a run of spaces rather than a single space --
 * L19 carries two spaces between {@code PA-RQ-AUTH-DATE} and {@code X(06)} -- so a search written
 * with one space matches nothing. Counting the six-position pictures there bears it out: a
 * single-spaced pattern returns zero matches while a whitespace-run-tolerant expression returns
 * four. The failure is the expensive kind, because zero matches reads as an absent field rather
 * than as a broken search.
 *
 * <h2>What the test channel has to supply</h2>
 *
 * <p>Assumptions: the sibling tree
 * {@code services/authorization-service/src/test/java/com/carddemo/authorization} holds no test for
 * this type yet, so its obligations are recorded here rather than referred elsewhere. Two are
 * required. A delimited fixture for this payload whose copybook-declared width sum is 153 -- the
 * first of the three measurements named above, and not the emitted 152 -- so that a component read
 * at an ordinal is read at the width its copybook declares. And a test over
 * {@code com.carddemo.common.codec.CsvAuthCodec} asserting the field order and the delimiter, since
 * those two are the interface and a test exercising neither would leave the part that can shift
 * silently unexercised.
 *
 * @param authDate the date an acquirer stamped on the authorization request, ordinal one on the
 *     wire, six positions from {@code PA-RQ-AUTH-DATE PIC X(06)} at L19; carried as characters, and
 *     the segment persisting it declares the same six positions as {@code PA-AUTH-ORIG-DATE} at
 *     {@code cpy/CIPAUDTY.cpy} L22
 * @param authTime the time stamped alongside the date above, ordinal two, six positions from
 *     {@code PA-RQ-AUTH-TIME PIC X(06)} at L20; its persisted counterpart is the six-position
 *     {@code PA-AUTH-ORIG-TIME} at {@code cpy/CIPAUDTY.cpy} L23, and this pair is what the detail
 *     segment's composite key at L20 and L21 of that copybook is derived from
 * @param cardNumber the primary account number the authorization is sought against, ordinal three,
 *     sixteen positions from {@code PA-RQ-CARD-NUM PIC X(16)} at L21; it arrives unmasked because
 *     the producer sends it so, and it is masked to its last four digits on the way out in
 *     {@code com.carddemo.authorization.mapper} rather than here
 * @param authType the acquirer's classification of the authorization, ordinal four, four positions
 *     from {@code PA-RQ-AUTH-TYPE PIC X(04)} at L22; the value domain is the acquirer's and is not
 *     enumerated by the copybook, which declares width and nothing narrower
 * @param cardExpiryDate the expiry the producer read from the card, ordinal five, four positions
 *     from {@code PA-RQ-CARD-EXPIRY-DATE PIC X(04)} at L23; four positions rather than a full
 *     date's ten, which is a month and a year carrying no day
 * @param messageType the acquirer's message-type indicator, ordinal six, six positions from
 *     {@code PA-RQ-MESSAGE-TYPE PIC X(06)} at L24; persisted at the same width as
 *     {@code PA-MESSAGE-TYPE} at {@code cpy/CIPAUDTY.cpy} L27
 * @param messageSource the channel the request originated from, ordinal seven, six positions from
 *     {@code PA-RQ-MESSAGE-SOURCE PIC X(06)} at L25; persisted at the same width as
 *     {@code PA-MESSAGE-SOURCE} at {@code cpy/CIPAUDTY.cpy} L28
 * @param processingCode the acquirer's processing code, ordinal eight, six positions from
 *     {@code PA-RQ-PROCESSING-CODE PIC 9(06)} at L26; a numeric picture carried as digit characters
 *     for the overlay reason recorded above, so a leading zero survives the move
 * @param transactionAmount the amount authorization is sought for, ordinal nine, fourteen positions
 *     edited as {@code PA-RQ-TRANSACTION-AMT PIC +9(10).99} at L27; exact at two decimal places
 *     over ten integer places, the domain {@code cpy/CIPAUDTY.cpy} L34 persists as packed decimal,
 *     and rendered as text rather than as a number wherever it is serialised
 * @param merchantCategoryCode the category an acquirer assigns the merchant, ordinal ten, four
 *     positions from the field {@code CCPAURQY.cpy} L28 spells
 *     {@code PA-RQ-MERCHANT-CATAGORY-CODE}; that reference spelling stays on the wire and in the
 *     persisted layout at {@code cpy/CIPAUDTY.cpy} L36, while this component and its column use the
 *     spelling above
 * @param acquirerCountryCode the country of the acquirer, ordinal eleven, three positions from
 *     {@code PA-RQ-ACQR-COUNTRY-CODE PIC X(03)} at L29; three positions admits an alphabetic or a
 *     numeric country encoding and the copybook commits to neither
 * @param posEntryMode how the card was presented at the point of sale, ordinal twelve, two
 *     positions from {@code PA-RQ-POS-ENTRY-MODE PIC 9(02)} at L30; digit characters on the same
 *     terms as the processing code, and persisted at {@code cpy/CIPAUDTY.cpy} L38
 * @param merchantId the acquirer's identifier for the merchant, ordinal thirteen, fifteen positions
 *     from {@code PA-RQ-MERCHANT-ID PIC X(15)} at L31; the same declared width as the transaction
 *     identifier below, which is a coincidence of width and not a shared domain
 * @param merchantName the merchant's trading name as the acquirer supplies it, ordinal fourteen,
 *     twenty-two positions from {@code PA-RQ-MERCHANT-NAME PIC X(22)} at L32 -- the widest of the
 *     eighteen fields
 * @param merchantCity the city of the merchant location, ordinal fifteen, thirteen positions from
 *     {@code PA-RQ-MERCHANT-CITY PIC X(13)} at L33
 * @param merchantState the state of the merchant location, ordinal sixteen, two positions from
 *     {@code PA-RQ-MERCHANT-STATE PIC X(02)} at L34; two positions is an abbreviation rather than a
 *     name, and no allow-list is applied here because checking a code against reference data is a
 *     rule rather than a shape
 * @param merchantZip the postal code of the merchant location, ordinal seventeen, nine positions
 *     from {@code PA-RQ-MERCHANT-ZIP PIC X(09)} at L35; nine rather than five, which is what admits
 *     the extended form
 * @param transactionId the acquirer's identifier for the transaction this authorization belongs to,
 *     ordinal eighteen and last, fifteen positions from {@code PA-RQ-TRANSACTION-ID PIC X(15)} at
 *     L36; it is what a reply is correlated back on and what makes a redelivered request
 *     recognisable as one already seen
 */
public record AuthorizationRequestPayload(
        @NotNull @Size(max = AUTH_DATE_WIDTH) String authDate,
        @NotNull @Size(max = AUTH_TIME_WIDTH) String authTime,
        @NotNull @Size(max = CARD_NUMBER_WIDTH) String cardNumber,
        @NotNull @Size(max = AUTH_TYPE_WIDTH) String authType,
        @NotNull @Size(max = CARD_EXPIRY_DATE_WIDTH) String cardExpiryDate,
        @NotNull @Size(max = MESSAGE_TYPE_WIDTH) String messageType,
        @NotNull @Size(max = MESSAGE_SOURCE_WIDTH) String messageSource,
        // Assumptions: the digits-only expression is applied here and on the ordinal-twelve
        //   component below, and on no other, because L26 and L30 are the only two of the eighteen
        //   declarations carrying a numeric picture. A character picture holds any character its
        //   width allows, so constraining one of those to digits would refuse a value its copybook
        //   field can legitimately hold.
        @NotNull @Size(max = PROCESSING_CODE_WIDTH) @Pattern(regexp = DIGITS_ONLY)
                String processingCode,
        // Assumptions: no size constraint accompanies this component, and its absence is the
        //   decision. The bound belongs to the money type, which admits ten integer places and two
        //   decimal places and refuses anything larger; a character-count constraint here would
        //   describe the edited wire rendering instead, and the two are different quantities that
        //   would then disagree the moment either moved.
        @NotNull Money transactionAmount,
        @NotNull @Size(max = MERCHANT_CATEGORY_CODE_WIDTH) String merchantCategoryCode,
        @NotNull @Size(max = ACQUIRER_COUNTRY_CODE_WIDTH) String acquirerCountryCode,
        @NotNull @Size(max = POS_ENTRY_MODE_WIDTH) @Pattern(regexp = DIGITS_ONLY)
                String posEntryMode,
        @NotNull @Size(max = MERCHANT_ID_WIDTH) String merchantId,
        @NotNull @Size(max = MERCHANT_NAME_WIDTH) String merchantName,
        @NotNull @Size(max = MERCHANT_CITY_WIDTH) String merchantCity,
        @NotNull @Size(max = MERCHANT_STATE_WIDTH) String merchantState,
        @NotNull @Size(max = MERCHANT_ZIP_WIDTH) String merchantZip,
        @NotNull @Size(max = TRANSACTION_ID_WIDTH) String transactionId) {

    /**
     * The positions {@code PA-RQ-AUTH-DATE PIC X(06)} declares at {@code CCPAURQY.cpy} L19.
     */
    private static final int AUTH_DATE_WIDTH = 6;

    /**
     * The positions {@code PA-RQ-AUTH-TIME PIC X(06)} declares at {@code CCPAURQY.cpy} L20.
     */
    private static final int AUTH_TIME_WIDTH = 6;

    /**
     * The positions {@code PA-RQ-CARD-NUM PIC X(16)} declares at {@code CCPAURQY.cpy} L21.
     *
     * <p>Assumptions: sixteen is corroborated by the persisted layout, where
     * {@code PA-CARD-NUM PIC X(16)} appears at {@code cpy/CIPAUDTY.cpy} L24, so the wire and the
     * segment agree on the width and it is a contract rather than a single reading.
     */
    private static final int CARD_NUMBER_WIDTH = 16;

    /**
     * The positions {@code PA-RQ-AUTH-TYPE PIC X(04)} declares at {@code CCPAURQY.cpy} L22.
     */
    private static final int AUTH_TYPE_WIDTH = 4;

    /**
     * The positions {@code PA-RQ-CARD-EXPIRY-DATE PIC X(04)} declares at {@code CCPAURQY.cpy} L23.
     */
    private static final int CARD_EXPIRY_DATE_WIDTH = 4;

    /**
     * The positions {@code PA-RQ-MESSAGE-TYPE PIC X(06)} declares at {@code CCPAURQY.cpy} L24.
     */
    private static final int MESSAGE_TYPE_WIDTH = 6;

    /**
     * The positions {@code PA-RQ-MESSAGE-SOURCE PIC X(06)} declares at {@code CCPAURQY.cpy} L25.
     */
    private static final int MESSAGE_SOURCE_WIDTH = 6;

    /**
     * The positions {@code PA-RQ-PROCESSING-CODE PIC 9(06)} declares at {@code CCPAURQY.cpy} L26.
     *
     * <p>Assumptions: the picture is numeric and the constraint it sizes is nonetheless a character
     * count, because the component is a string of digits for the overlay reason recorded on this
     * type. Six digit positions and six character positions are the same six positions here, since
     * a numeric display picture occupies one position per digit.
     */
    private static final int PROCESSING_CODE_WIDTH = 6;

    /**
     * The positions the merchant category code declares at {@code CCPAURQY.cpy} L28.
     *
     * <p>Assumptions: the field this width is read from is spelled
     * {@code PA-RQ-MERCHANT-CATAGORY-CODE} in the copybook, and that spelling is what a search of
     * the reference tree has to use to find it. The constant carries the target spelling because it
     * names a Java constant; the two spellings are reconciled on this type rather than unified.
     */
    private static final int MERCHANT_CATEGORY_CODE_WIDTH = 4;

    /**
     * The positions {@code PA-RQ-ACQR-COUNTRY-CODE PIC X(03)} declares at {@code CCPAURQY.cpy} L29.
     */
    private static final int ACQUIRER_COUNTRY_CODE_WIDTH = 3;

    /**
     * The positions {@code PA-RQ-POS-ENTRY-MODE PIC 9(02)} declares at {@code CCPAURQY.cpy} L30.
     */
    private static final int POS_ENTRY_MODE_WIDTH = 2;

    /**
     * The positions {@code PA-RQ-MERCHANT-ID PIC X(15)} declares at {@code CCPAURQY.cpy} L31.
     */
    private static final int MERCHANT_ID_WIDTH = 15;

    /**
     * The positions {@code PA-RQ-MERCHANT-NAME PIC X(22)} declares at {@code CCPAURQY.cpy} L32.
     */
    private static final int MERCHANT_NAME_WIDTH = 22;

    /**
     * The positions {@code PA-RQ-MERCHANT-CITY PIC X(13)} declares at {@code CCPAURQY.cpy} L33.
     */
    private static final int MERCHANT_CITY_WIDTH = 13;

    /**
     * The positions {@code PA-RQ-MERCHANT-STATE PIC X(02)} declares at {@code CCPAURQY.cpy} L34.
     */
    private static final int MERCHANT_STATE_WIDTH = 2;

    /**
     * The positions {@code PA-RQ-MERCHANT-ZIP PIC X(09)} declares at {@code CCPAURQY.cpy} L35.
     */
    private static final int MERCHANT_ZIP_WIDTH = 9;

    /**
     * The positions {@code PA-RQ-TRANSACTION-ID PIC X(15)} declares at {@code CCPAURQY.cpy} L36.
     *
     * <p>Assumptions: fifteen is corroborated by {@code PA-TRANSACTION-ID PIC X(15)} at
     * {@code cpy/CIPAUDTY.cpy} L44, the persisted counterpart, so the identifier a reply is
     * correlated on carries the same width at both ends of the exchange.
     */
    private static final int TRANSACTION_ID_WIDTH = 15;

    /**
     * The expression the two numerically-pictured components have to match in full.
     *
     * <p>Assumptions: the character class is written as an explicit range rather than as the
     * shorthand digit class, because the shorthand is interpretation-dependent -- it broadens to
     * every decimal digit in Unicode once Unicode character-class mode is turned on by an embedded
     * flag expression -- so a value carrying a non-Latin digit would then satisfy a constraint that
     * a {@code PIC 9} field cannot hold. This follows the same reading applied at
     * {@code com.carddemo.reporting.dto.StatementRequest}, so the two agree.
     *
     * <p>Assumptions: the quantifier requires at least one digit and sets no upper bound, because
     * the upper bound is the declared width and that is asserted by the size constraint beside this
     * one on each component. Stating the width here as well would put one declared width in two
     * executable positions, which is what lets two constraints come to disagree about it.
     */
    private static final String DIGITS_ONLY = "[0-9]+";
}
