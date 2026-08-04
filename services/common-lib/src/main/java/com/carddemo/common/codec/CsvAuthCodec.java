package com.carddemo.common.codec;

import com.carddemo.common.money.Money;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Encodes and decodes the two comma-delimited authorization messages, the eighteen-field request and
 * the six-field reply.
 *
 * <p>Assumptions: field order and the delimiter <em>are</em> this contract, not a serialisation
 * detail of it. Both directions declare the payload as string format to the queue manager --
 * {@code MOVE MQFMT-STRING TO MQMD-FORMAT} at line 397 of
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} on the inbound message and at line 751
 * on the outbound one -- and a string-format payload carries no structural metadata at all. A
 * receiver has nothing but ordinal position and the delimiter to work from, so changing either is a
 * contract change rather than a representation change. Every rule this class enforces traces to a
 * declaration in {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy} or
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy}, or to a statement in
 * {@code COPAUA0C.cbl}, cited by line at the point it is applied.</p>
 *
 * <p>Assumptions: the delimiter is a literal comma, proven on both directions rather than inferred.
 * The inbound parse is {@code UNSTRING W01-GET-BUFFER(1:W01-DATALEN) DELIMITED BY ','} at lines 354
 * to 355 of {@code COPAUA0C.cbl}, closed by {@code END-UNSTRING} at line 374, receiving eighteen
 * fields at lines 356 to 373 in the order the request copybook declares them. The outbound build is
 * the {@code STRING} at lines 722 to 731, which interleaves a {@code ','} literal with each value,
 * uses {@code DELIMITED BY SIZE} at line 728 and closes with {@code END-STRING} at line 731.</p>
 *
 * <h2>Declared width is not wire length</h2>
 *
 * <p>Assumptions: three quantities are involved and conflating any two of them shifts every
 * subsequent field. They are kept as separate named constants for exactly that reason:</p>
 *
 * <pre>
 * payload   fields   declared width sum   delimiters   wire length
 * request       18                  153   17 commas            170
 * reply          6                   57    6 commas             63
 * </pre>
 *
 * <p>Assumptions: the request sums to 153 over its eighteen declared widths
 * ({@code 6+6+16+4+4+6+6+6+14+4+3+2+15+22+13+2+9+15}) at lines 19 to 36 of {@code CCPAURQY.cpy},
 * and the reply sums to 57 over its six ({@code 16+15+6+2+4+14}) at lines 19 to 24 of
 * {@code CCPAURLY.cpy}. Neither sum counts a delimiter, because a copybook declares fields and not
 * the message that carries them.</p>
 *
 * <p>Assumptions: the reply carries <strong>six</strong> commas for six fields, one of them
 * trailing, so its wire length is 63 and not 62. Two sources disagree on that figure and the
 * disagreement is recorded rather than resolved silently.
 * {@code docs/architecture/messaging-contracts.md} gives 62 as the interior-delimited nominal
 * length, the arithmetic that places one comma between each adjacent pair and therefore five commas
 * across six fields. The first-hand evidence gives 63: the {@code STRING} at lines 722 to 731 of
 * {@code COPAUA0C.cbl} pairs a {@code ','} literal with every one of the six values, including the
 * sixth at line 727, so the built buffer is 57 characters of data plus six commas. This class
 * implements 63, because the emitting statement outranks the arithmetic that predicts it. The same
 * document records the consequence directly: a decoder requiring 62 bytes with no trailing
 * delimiter would reject every well-formed reply the reference program produces.</p>
 *
 * <p>Assumptions: the request needs no such reconciliation. Its interior-delimited length and its
 * wire length are the same 170, because the {@code UNSTRING} at line 354 names eighteen receiving
 * fields and therefore consumes seventeen interior delimiters and no trailing one.</p>
 *
 * <h2>Buffer length, payload length and cursor position are three different values</h2>
 *
 * <p>Assumptions: the transport buffer is declared {@code 01 W01-GET-BUFFER PIC X(500).} at line 103
 * of {@code COPAUA0C.cbl}, comfortably larger than either payload, and the reference program passes
 * an explicit data length alongside it -- {@code W01-DATALEN}, distinct from the {@code W01-BUFFLEN}
 * it sets to the buffer's own length at line 398. This class must therefore never treat a buffer's
 * length as a payload length, which is why the byte-array entry points take the payload length as a
 * separate argument.</p>
 *
 * <p>Assumptions: the reference program also shows what happens when a cursor and a length are held
 * in one value. {@code WS-RESP-LENGTH} is declared {@code PIC S9(4) VALUE 1} at line 46, is used as
 * the {@code WITH POINTER} cursor of the outbound {@code STRING} at line 730 -- so after the
 * transfer it addresses the position one past the last character written -- and is then moved
 * straight into the put's buffer length at line 756 and passed to the call at line 762. The baseline
 * therefore sends 64 bytes for the 63 it builds, the sixty-fourth being a space from the 200-byte
 * put buffer declared at line 108. This class implements 63; the divergence is documented. Inside
 * this class a payload length and a scan position are always separate, differently named values,
 * because holding them in one is precisely what produced the extra byte.</p>
 *
 * <h2>Strict on encode, tolerant on decode</h2>
 *
 * <p>Trade-offs: symmetric strictness was evaluated and rejected. It reads as the more principled
 * choice and it would leave this codec unable to read the reference program's own traffic, for three
 * separate reasons that are each measurable in the source. The reply's trailing comma means a
 * decoder must accept a final empty token rather than count it as a seventh field. The
 * pointer-derived length means a decoder must accept one pad byte beyond the expected length. And
 * the outbound money rendering is {@code WS-APPROVED-AMT-DIS PIC -zzzzzzzzz9.99} at line 66, whose
 * zero suppression emits leading spaces and no {@code +} for a positive value, so a decoder must
 * trim the token rather than match it against a fourteen-character mask. Encoding, by contrast, has
 * one correct answer and no reason to hedge: the widths and the rendering the copybooks declare.
 * What is accepted is that this codec is not byte-idempotent over every input a producer might
 * send -- a tolerated variant re-encodes to the declared form rather than to itself -- and what is
 * bought is interoperability in both directions.</p>
 *
 * <h2>What this class does not own</h2>
 *
 * <p>Assumptions: transport belongs to the queue-owning service, not here. The FIFO message-group
 * identifier taken from the card number, the deduplication identifier taken from the transaction
 * identifier, the reply-queue routing, the dead-letter configuration and the {@code expiresAt}
 * attribute that stands in for the reference program's message expiry -- {@code MOVE 50 TO
 * MQMD-EXPIRY} at line 750, five seconds in tenths -- are all queue concerns. This class produces
 * and consumes a payload and holds no client, no queue name and no attribute map, so none of them
 * can be configured through it.</p>
 *
 * <p>Assumptions: the consumer's batch discipline is likewise not here. The reference program's
 * processing limit of {@code WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500} at line 40 becomes a
 * bounded long-poll loop, and its get-with-wait of {@code MOVE 5000 TO WS-WAIT-INTERVAL} at line
 * 242, five seconds in milliseconds, becomes an equivalent receive wait. Both live in the consumer
 * that drives this codec.</p>
 *
 * <p>Assumptions: a JSON envelope over these two payloads is additive for new consumers and never a
 * replacement, so this class imports no JSON library and offers no JSON view. With a string-format
 * payload there is no schema anywhere else, so replacing the encoding would be a contract change
 * against an external producer that the baseline does not supply and that cannot be renegotiated
 * with. A JSON projection, if one is ever wanted, is built by a service-layer mapper from the record
 * carriers below, where the additive framing is visible.</p>
 *
 * <p>Assumptions: the error-log record is outside this class. {@code CCPAUERY.cpy} declares
 * {@code ERROR-LOG-RECORD} at line 19 with eleven fields summing to 122 bytes, and it carries no
 * delimiter at all -- it is a byte-positional record, so its owners are the sibling
 * {@code CopybookLayout} descriptor and {@code FixedWidthCodec}, which is the regime built for
 * offsets. Its declaration also carries a hazard worth recording where a reader will meet it: the
 * {@code 01} sits ten spaces in, beyond Area A, so a census pattern anchored as
 * {@code ^ {0,7}01 } does not match it. That is not a one-off; line 60 of
 * {@code app/cbl/CSUTLDTC.cbl} indents an {@code 01} the same way.</p>
 *
 * <h2>Money on this wire, and the five renderings that are not this class's</h2>
 *
 * <p>Assumptions: money crosses this wire as an edited display form of exactly fourteen characters,
 * and this class owns that one rendering. Five others exist in the migration and each belongs
 * elsewhere: zoned overpunch of eleven or twelve bytes is {@code ZonedDecimalCodec}; packed
 * {@code COMP-3}, seven bytes for {@code PIC S9(10)V99}, and binary {@code COMP} are
 * {@code PackedDecimalCodec}; the twelve-character sort edit mask, and the fifteen-character report
 * masks at line 30 and lines 54, 60 and 66 of {@code app/cpy/CVTRA07Y.cpy}, are the reporting
 * concern. Naming the boundary here is what keeps a sixth rendering from being added to this
 * class.</p>
 *
 * <h2>Why this class carries its own layout knowledge</h2>
 *
 * <p>Alternatives Considered: routing these two payloads through {@code FixedWidthCodec} was
 * evaluated and rejected. Assumptions: that codec addresses a record by byte offset, and these
 * payloads have no byte offsets to address. Their fields have declared widths, but the widths are
 * not where the fields sit: seventeen delimiters are interleaved between the request's eighteen
 * values, and the reference program's own intake proves the widths are not load-bearing on receive,
 * since it reads the ordinal-nine money token into {@code WS-TRANSACTION-AMT-AN PIC X(13)} at line
 * 63, one byte narrower than the fourteen the copybook declares at line 27. Reusing a byte-positional
 * codec would mean inventing offsets the wire does not have, and an invented offset is exactly the
 * silent-shift failure this contract cannot absorb. The two regimes are kept apart so that choosing
 * the wrong one is a compilation-time mistake rather than a run-time misread.</p>
 *
 * <p>Assumptions: the copybook declarations were read with whitespace-run tokenising rather than
 * single-space splitting, because {@code CCPAURQY.cpy} writes {@code PIC  X(06)} with two spaces
 * after {@code PIC} on every alphanumeric field while writing {@code PIC +9(10).99} with one on the
 * money field at line 27. A single-space split silently produces an empty token for the two-space
 * form, which would have dropped seventeen of the eighteen widths recorded below.</p>
 *
 * <p>Trade-offs: the two carriers and the failure type are nested inside this class rather than
 * standing as their own files. Separate top-level types would be conventional, and were declined
 * because these three declarations have no meaning apart from this wire format: the carriers exist
 * to hold exactly the fields the two copybooks declare, in exactly that order, and the failure type
 * reports a violation of that same format. Nesting them keeps the format and its vocabulary in one
 * place a reader can hold at once. The cost accepted is a long file.</p>
 */
public final class CsvAuthCodec {

    /**
     * The field separator, proven at lines 354 to 355 and 722 to 731 of {@code COPAUA0C.cbl}.
     */
    public static final char DELIMITER = ',';

    /**
     * The number of fields the request declares, at lines 19 to 36 of {@code CCPAURQY.cpy}.
     */
    public static final int REQUEST_FIELD_COUNT = 18;

    /**
     * The sum of the request's eighteen declared field widths, counting no delimiter.
     */
    public static final int REQUEST_DECLARED_WIDTH_SUM = 153;

    /**
     * The request length on the wire, the declared width sum plus seventeen interior delimiters.
     */
    public static final int REQUEST_WIRE_LENGTH = 170;

    /**
     * The number of fields the reply declares, at lines 19 to 24 of {@code CCPAURLY.cpy}.
     */
    public static final int REPLY_FIELD_COUNT = 6;

    /**
     * The sum of the reply's six declared field widths, counting no delimiter.
     */
    public static final int REPLY_DECLARED_WIDTH_SUM = 57;

    /**
     * The reply length on the wire, the declared width sum plus six delimiters including a trailing
     * one.
     */
    public static final int REPLY_WIRE_LENGTH = 63;

    /**
     * The length of the correlation key that matches a reply to its request.
     */
    public static final int CORRELATION_KEY_LENGTH = 31;

    /**
     * The width of the edited display money rendering {@code PIC +9(10).99}.
     */
    public static final int MONEY_EDITED_WIDTH = 14;

    /**
     * The number of integer digit positions in the edited display money rendering.
     */
    public static final int MONEY_INTEGER_DIGITS = 10;

    /**
     * The number of decimal digit positions in the edited display money rendering.
     */
    public static final int MONEY_SCALE = 2;

    /**
     * The declared width of the card number, at line 21 of {@code CCPAURQY.cpy} and line 19 of
     * {@code CCPAURLY.cpy}.
     */
    public static final int CARD_NUM_WIDTH = 16;

    /**
     * The declared width of the transaction identifier, at line 36 of {@code CCPAURQY.cpy} and line
     * 20 of {@code CCPAURLY.cpy}.
     */
    public static final int TRANSACTION_ID_WIDTH = 15;

    /**
     * The eighteen request field widths, in the order the wire carries them.
     */
    public static final List<Integer> REQUEST_FIELD_WIDTHS =
            List.of(6, 6, 16, 4, 4, 6, 6, 6, 14, 4, 3, 2, 15, 22, 13, 2, 9, 15);

    /**
     * The eighteen request field names as the copybook declares them, in wire order.
     */
    public static final List<String> REQUEST_FIELD_NAMES = List.of(
            "PA-RQ-AUTH-DATE",
            "PA-RQ-AUTH-TIME",
            "PA-RQ-CARD-NUM",
            "PA-RQ-AUTH-TYPE",
            "PA-RQ-CARD-EXPIRY-DATE",
            "PA-RQ-MESSAGE-TYPE",
            "PA-RQ-MESSAGE-SOURCE",
            "PA-RQ-PROCESSING-CODE",
            "PA-RQ-TRANSACTION-AMT",
            "PA-RQ-MERCHANT-CATAGORY-CODE",
            "PA-RQ-ACQR-COUNTRY-CODE",
            "PA-RQ-POS-ENTRY-MODE",
            "PA-RQ-MERCHANT-ID",
            "PA-RQ-MERCHANT-NAME",
            "PA-RQ-MERCHANT-CITY",
            "PA-RQ-MERCHANT-STATE",
            "PA-RQ-MERCHANT-ZIP",
            "PA-RQ-TRANSACTION-ID");

    /**
     * The six reply field widths, in the order the wire carries them.
     */
    public static final List<Integer> REPLY_FIELD_WIDTHS = List.of(16, 15, 6, 2, 4, 14);

    /**
     * The six reply field names as the copybook declares them, in wire order.
     */
    public static final List<String> REPLY_FIELD_NAMES = List.of(
            "PA-RL-CARD-NUM",
            "PA-RL-TRANSACTION-ID",
            "PA-RL-AUTH-ID-CODE",
            "PA-RL-AUTH-RESP-CODE",
            "PA-RL-AUTH-RESP-REASON",
            "PA-RL-APPROVED-AMT");

    /**
     * The sign character the edited display rendering emits for a non-negative amount.
     */
    private static final char MONEY_POSITIVE_SIGN = '+';

    /**
     * The sign character the edited display rendering emits for a negative amount.
     */
    private static final char MONEY_NEGATIVE_SIGN = '-';

    /**
     * The literal decimal point of the edited display rendering, which occupies a real byte.
     */
    private static final char MONEY_DECIMAL_POINT = '.';

    /**
     * The pad character {@code DELIMITED BY SIZE} contributes for an unused declared position.
     */
    private static final char PAD = ' ';

    /**
     * The zero digit, used to left-pad the integer part of the edited display rendering.
     */
    private static final char ZERO_DIGIT = '0';

    /**
     * Prevents instantiation of this static codec holder.
     *
     * <p>Alternatives Considered: an instantiable codec, or one exposing a shared instance, was
     * evaluated and rejected. This class holds no configuration -- the widths, the names, the
     * delimiter and the money rendering are all determined by two copybooks and cannot vary per
     * caller -- so an instance would advertise a lifecycle and an injection point that have nothing
     * behind them. A private constructor on a final class states that in the one place the language
     * enforces it.</p>
     */
    private CsvAuthCodec() {
        // WHY : Alternatives Considered: throwing from this body was evaluated and judged noise. The
        //       private modifier already makes the only call site that could reach it impossible to
        //       write, so an exception here would guard a state the compiler has already excluded.
    }


    /**
     * Reports that a payload or a field value violates the declared authorization message format.
     *
     * <p>Assumptions: a format violation on this wire is a caller-side defect rather than a
     * recoverable condition, which is why this extends {@link IllegalArgumentException} rather than
     * a checked type. Nothing downstream can retry its way out of a payload carrying the wrong field
     * count or a money token with no decimal point; the message has to be rejected and reported, and
     * the consumer's dead-letter queue is where a rejected message goes.</p>
     *
     * <p>Trade-offs: a named subtype rather than a bare {@code IllegalArgumentException} costs one
     * more declaration and buys a consumer the ability to separate a malformed payload, which
     * belongs on the dead-letter queue, from a programming error raised by the same call. Declaring
     * it nested rather than as its own file follows the reasoning recorded on the enclosing
     * class.</p>
     */
    public static final class AuthMessageFormatException extends IllegalArgumentException {

        /**
         * The serialisation identity of this exception type.
         */
        private static final long serialVersionUID = 1L;

        /**
         * Creates a format failure carrying a description of what was rejected.
         *
         * <p>Alternatives Considered: restricting this constructor to this package was evaluated and
         * rejected. It would have made the exception evidence that this codec in particular did the
         * rejecting, and the cost outweighed that: a consumer typically validates a transport
         * attribute before handing the body over, and with a package-private constructor it would
         * have to raise a second, unrelated type for that half of the same check. One type for one
         * failure mode leaves a consumer with one exception to route to its dead-letter queue.</p>
         *
         * @param message the description of the violation, which names the field or the payload
         *     concerned and the value that was rejected, so that a dead-lettered message can be
         *     diagnosed from the log line alone
         */
        public AuthMessageFormatException(String message) {
            super(message);
        }
    }


    /**
     * Carries the eighteen fields of an authorization request in the order the wire declares them.
     *
     * <p>Assumptions: every component is stored with its trailing pad removed, so a component holds
     * the value and not the padding that surrounded it on the wire. That follows the copybook rule
     * that a trailing blank in an alphanumeric field is padding rather than data, and it is what
     * makes a decode of an encode return an equal record. The padding is restored on encode from the
     * declared widths recorded in {@code REQUEST_FIELD_WIDTHS}.</p>
     *
     * <p>Assumptions: the ordinal-ten component records a misspelling that the baseline carries in
     * two forms, and both are recorded here so the lineage is never ambiguous. Line 28 of
     * {@code CCPAURQY.cpy} declares it {@code PA-RQ-MERCHANT-CATAGORY-CODE} with the request infix,
     * and line 36 of {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} declares the bare
     * form {@code PA-MERCHANT-CATAGORY-CODE}. Under AAP Rule T1 (Copybook is normative) no field is
     * renamed except three documented misspelling corrections, and this is the third of them: the
     * target column is {@code merchant_category_code} and the component is
     * {@code merchantCategoryCode}. This is a target-name correction with the baseline spelling
     * preserved verbatim in {@code REQUEST_FIELD_NAMES}, which is what every failure message and
     * every wire-order assertion reads from.</p>
     *
     * <p>Assumptions: the two fields declared {@code PIC 9(nn)} -- the processing code at line 26 and
     * the entry mode at line 30 -- are carried as text and not as numbers. They are identifiers
     * rather than quantities: nothing adds them, a leading zero is significant to the acquirer that
     * assigned them, and an integer component would silently discard that zero on the way back out.
     * The single component the wire genuinely counts as money is the amount, and it is the only one
     * typed {@link Money}.</p>
     *
     * @param authDate the authorization date as {@code PA-RQ-AUTH-DATE} at line 19 of
     *     {@code CCPAURQY.cpy}, at most 6 characters
     * @param authTime the authorization time as {@code PA-RQ-AUTH-TIME} at line 20, at most 6
     *     characters
     * @param cardNum the primary account number as {@code PA-RQ-CARD-NUM} at line 21, at most 16
     *     characters, and the first half of the correlation key
     * @param authType the authorization type as {@code PA-RQ-AUTH-TYPE} at line 22, at most 4
     *     characters
     * @param cardExpiryDate the card expiry as {@code PA-RQ-CARD-EXPIRY-DATE} at line 23, at most 4
     *     characters
     * @param messageType the message type as {@code PA-RQ-MESSAGE-TYPE} at line 24, at most 6
     *     characters
     * @param messageSource the originating source as {@code PA-RQ-MESSAGE-SOURCE} at line 25, at
     *     most 6 characters
     * @param processingCode the processing code as {@code PA-RQ-PROCESSING-CODE} at line 26, at most
     *     6 characters, carried as text because its leading zeros are significant
     * @param transactionAmount the requested amount as {@code PA-RQ-TRANSACTION-AMT} at line 27,
     *     exact at two decimal places and rendered on the wire as fourteen characters
     * @param merchantCategoryCode the merchant category as {@code PA-RQ-MERCHANT-CATAGORY-CODE} at
     *     line 28, at most 4 characters; see the misspelling note above for the target name
     * @param acquirerCountryCode the acquirer country as {@code PA-RQ-ACQR-COUNTRY-CODE} at line 29,
     *     at most 3 characters
     * @param posEntryMode the point-of-sale entry mode as {@code PA-RQ-POS-ENTRY-MODE} at line 30,
     *     at most 2 characters, carried as text for the same reason as the processing code
     * @param merchantId the merchant identifier as {@code PA-RQ-MERCHANT-ID} at line 31, at most 15
     *     characters
     * @param merchantName the merchant name as {@code PA-RQ-MERCHANT-NAME} at line 32, at most 22
     *     characters
     * @param merchantCity the merchant city as {@code PA-RQ-MERCHANT-CITY} at line 33, at most 13
     *     characters
     * @param merchantState the merchant state as {@code PA-RQ-MERCHANT-STATE} at line 34, at most 2
     *     characters
     * @param merchantZip the merchant postal code as {@code PA-RQ-MERCHANT-ZIP} at line 35, at most
     *     9 characters
     * @param transactionId the transaction identifier as {@code PA-RQ-TRANSACTION-ID} at line 36, at
     *     most 15 characters, and the second half of the correlation key
     */
    public record AuthRequest(
            String authDate,
            String authTime,
            String cardNum,
            String authType,
            String cardExpiryDate,
            String messageType,
            String messageSource,
            String processingCode,
            Money transactionAmount,
            String merchantCategoryCode,
            String acquirerCountryCode,
            String posEntryMode,
            String merchantId,
            String merchantName,
            String merchantCity,
            String merchantState,
            String merchantZip,
            String transactionId) {

        /**
         * Normalises and validates the eighteen components so that every instance already obeys the
         * declared wire contract.
         *
         * <p>Assumptions: validating here rather than in the encoder is what makes the encoder
         * unable to emit a payload of the wrong length. A record is the only gate every instance
         * passes through, so a component that is absent, that overruns its declared width, or that
         * contains the delimiter itself is rejected at the point it is supplied rather than at the
         * point it would have corrupted a message.</p>
         *
         * @param authDate the authorization date, which must not be {@code null}
         * @param authTime the authorization time, which must not be {@code null}
         * @param cardNum the primary account number, which must not be {@code null}
         * @param authType the authorization type, which must not be {@code null}
         * @param cardExpiryDate the card expiry, which must not be {@code null}
         * @param messageType the message type, which must not be {@code null}
         * @param messageSource the originating source, which must not be {@code null}
         * @param processingCode the processing code, which must not be {@code null}
         * @param transactionAmount the requested amount, which must not be {@code null}
         * @param merchantCategoryCode the merchant category code, which must not be {@code null}
         * @param acquirerCountryCode the acquirer country code, which must not be {@code null}
         * @param posEntryMode the point-of-sale entry mode, which must not be {@code null}
         * @param merchantId the merchant identifier, which must not be {@code null}
         * @param merchantName the merchant name, which must not be {@code null}
         * @param merchantCity the merchant city, which must not be {@code null}
         * @param merchantState the merchant state, which must not be {@code null}
         * @param merchantZip the merchant postal code, which must not be {@code null}
         * @param transactionId the transaction identifier, which must not be {@code null}
         * @throws NullPointerException if any component is {@code null}, because a string-format
         *     payload has no representation for an absent field and substituting a blank would emit
         *     a well-formed message asserting something the caller never said
         * @throws AuthMessageFormatException if any character component is longer than the width its
         *     copybook line declares, or contains the delimiter
         */
        public AuthRequest {
            authDate = characterField(authDate, REQUEST_FIELD_NAMES.get(0), REQUEST_FIELD_WIDTHS.get(0));
            authTime = characterField(authTime, REQUEST_FIELD_NAMES.get(1), REQUEST_FIELD_WIDTHS.get(1));
            cardNum = characterField(cardNum, REQUEST_FIELD_NAMES.get(2), REQUEST_FIELD_WIDTHS.get(2));
            authType = characterField(authType, REQUEST_FIELD_NAMES.get(3), REQUEST_FIELD_WIDTHS.get(3));
            cardExpiryDate =
                    characterField(cardExpiryDate, REQUEST_FIELD_NAMES.get(4), REQUEST_FIELD_WIDTHS.get(4));
            messageType =
                    characterField(messageType, REQUEST_FIELD_NAMES.get(5), REQUEST_FIELD_WIDTHS.get(5));
            messageSource =
                    characterField(messageSource, REQUEST_FIELD_NAMES.get(6), REQUEST_FIELD_WIDTHS.get(6));
            processingCode =
                    characterField(processingCode, REQUEST_FIELD_NAMES.get(7), REQUEST_FIELD_WIDTHS.get(7));

            // WHY : Assumptions: the amount is checked for presence only, because its type already
            //       carries every other guarantee this contract needs. A Money is invariantly exact
            //       at two decimal places and bounded by Money.MAX_MAGNITUDE, which is
            //       9999999999.99 -- precisely the ten integer digits the picture at line 27 of
            //       CCPAURQY.cpy declares -- so no width check here could reject a value the
            //       renderer would then fail on.
            transactionAmount = requireAmount(transactionAmount, REQUEST_FIELD_NAMES.get(8));

            merchantCategoryCode = characterField(
                    merchantCategoryCode, REQUEST_FIELD_NAMES.get(9), REQUEST_FIELD_WIDTHS.get(9));
            acquirerCountryCode = characterField(
                    acquirerCountryCode, REQUEST_FIELD_NAMES.get(10), REQUEST_FIELD_WIDTHS.get(10));
            posEntryMode =
                    characterField(posEntryMode, REQUEST_FIELD_NAMES.get(11), REQUEST_FIELD_WIDTHS.get(11));
            merchantId =
                    characterField(merchantId, REQUEST_FIELD_NAMES.get(12), REQUEST_FIELD_WIDTHS.get(12));
            merchantName =
                    characterField(merchantName, REQUEST_FIELD_NAMES.get(13), REQUEST_FIELD_WIDTHS.get(13));
            merchantCity =
                    characterField(merchantCity, REQUEST_FIELD_NAMES.get(14), REQUEST_FIELD_WIDTHS.get(14));
            merchantState =
                    characterField(merchantState, REQUEST_FIELD_NAMES.get(15), REQUEST_FIELD_WIDTHS.get(15));
            merchantZip =
                    characterField(merchantZip, REQUEST_FIELD_NAMES.get(16), REQUEST_FIELD_WIDTHS.get(16));
            transactionId =
                    characterField(transactionId, REQUEST_FIELD_NAMES.get(17), REQUEST_FIELD_WIDTHS.get(17));
        }

        /**
         * Returns the thirty-one character key that matches this request to its reply.
         *
         * <p>Assumptions: the key is the card number at its declared sixteen characters followed by
         * the transaction identifier at its declared fifteen, and neither component may be shortened
         * on the wire or in the key. Both fields lead the reply -- lines 19 and 20 of
         * {@code CCPAURLY.cpy} -- for exactly this reason, so a consumer can pair a reply with its
         * request without decoding the rest of either message. Trimming either component would make
         * two different pairs collide the moment one identifier were a prefix of another, which is
         * why the key pads each part back to its declared width rather than concatenating the
         * stored values.</p>
         *
         * @return the correlation key, always exactly {@code CORRELATION_KEY_LENGTH} characters
         */
        public String correlationKey() {
            return buildCorrelationKey(cardNum, REQUEST_FIELD_NAMES.get(2), transactionId,
                    REQUEST_FIELD_NAMES.get(17));
        }
    }


    /**
     * Carries the six fields of an authorization reply in the order the wire declares them.
     *
     * <p>Assumptions: the components are stored with their trailing pad removed, for the same reason
     * recorded on the request carrier. The reply additionally has to survive the pad byte the
     * reference program appends: because the length handed to the put at line 762 of
     * {@code COPAUA0C.cbl} is the {@code STRING} cursor rather than the transferred count, the
     * sixty-fourth byte of a genuine reply is a space, and stripping the pad is what makes that byte
     * disappear into the trailing empty token instead of becoming part of a value.</p>
     *
     * @param cardNum the primary account number as {@code PA-RL-CARD-NUM} at line 19 of
     *     {@code CCPAURLY.cpy}, at most 16 characters, and the first half of the correlation key
     * @param transactionId the transaction identifier as {@code PA-RL-TRANSACTION-ID} at line 20, at
     *     most 15 characters, and the second half of the correlation key
     * @param authIdCode the authorization identification code as {@code PA-RL-AUTH-ID-CODE} at line
     *     21, at most 6 characters
     * @param authRespCode the response code as {@code PA-RL-AUTH-RESP-CODE} at line 22, at most 2
     *     characters
     * @param authRespReason the response reason as {@code PA-RL-AUTH-RESP-REASON} at line 23, at
     *     most 4 characters
     * @param approvedAmount the approved amount as {@code PA-RL-APPROVED-AMT} at line 24, exact at
     *     two decimal places and rendered on the wire as fourteen characters
     */
    public record AuthReply(
            String cardNum,
            String transactionId,
            String authIdCode,
            String authRespCode,
            String authRespReason,
            Money approvedAmount) {

        /**
         * Normalises and validates the six components so that every instance already obeys the
         * declared wire contract.
         *
         * @param cardNum the primary account number, which must not be {@code null}
         * @param transactionId the transaction identifier, which must not be {@code null}
         * @param authIdCode the authorization identification code, which must not be {@code null}
         * @param authRespCode the response code, which must not be {@code null}
         * @param authRespReason the response reason, which must not be {@code null}
         * @param approvedAmount the approved amount, which must not be {@code null}
         * @throws NullPointerException if any component is {@code null}
         * @throws AuthMessageFormatException if any character component is longer than the width its
         *     copybook line declares, or contains the delimiter
         */
        public AuthReply {
            cardNum = characterField(cardNum, REPLY_FIELD_NAMES.get(0), REPLY_FIELD_WIDTHS.get(0));
            transactionId =
                    characterField(transactionId, REPLY_FIELD_NAMES.get(1), REPLY_FIELD_WIDTHS.get(1));
            authIdCode = characterField(authIdCode, REPLY_FIELD_NAMES.get(2), REPLY_FIELD_WIDTHS.get(2));
            authRespCode =
                    characterField(authRespCode, REPLY_FIELD_NAMES.get(3), REPLY_FIELD_WIDTHS.get(3));
            authRespReason =
                    characterField(authRespReason, REPLY_FIELD_NAMES.get(4), REPLY_FIELD_WIDTHS.get(4));
            approvedAmount = requireAmount(approvedAmount, REPLY_FIELD_NAMES.get(5));
        }

        /**
         * Returns the thirty-one character key that matches this reply to its request.
         *
         * @return the correlation key, always exactly {@code CORRELATION_KEY_LENGTH} characters
         */
        public String correlationKey() {
            return buildCorrelationKey(cardNum, REPLY_FIELD_NAMES.get(0), transactionId,
                    REPLY_FIELD_NAMES.get(1));
        }
    }


    /**
     * Renders an authorization request as its comma-delimited payload.
     *
     * <p>Assumptions: every field is padded out to the width its copybook line declares, because the
     * outbound build at lines 722 to 731 of {@code COPAUA0C.cbl} joins with
     * {@code DELIMITED BY SIZE}, which contributes each source field's full declared size including
     * its blanks. Trade-offs: emitting trimmed fields instead would produce a shorter payload that a
     * comma-splitting reader would still parse, and it was rejected because it changes the byte image
     * the existing integration receives. The declared widths are the interface, so the padding is
     * reproduced and the length lands on {@code REQUEST_WIRE_LENGTH} rather than somewhere between
     * {@code REQUEST_DECLARED_WIDTH_SUM} and it.</p>
     *
     * @param request the request to render; must not be {@code null}. Its components are already
     *     within their declared widths, because the carrier validates them at construction
     * @return the payload, always exactly {@code REQUEST_WIRE_LENGTH} characters, with seventeen
     *     interior delimiters and no trailing one
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws AuthMessageFormatException if the assembled payload does not reach exactly
     *     {@code REQUEST_WIRE_LENGTH} characters, which can only mean the width table and the wire
     *     length constant have stopped agreeing
     */
    public static String encodeRequest(AuthRequest request) {
        if (request == null) {
            throw new NullPointerException("request must not be null");
        }

        // WHY : Assumptions: the ordinal-nine value is produced by the money renderer rather than by
        //       a byte copy of a stored string. The reference program converts explicitly in
        //       this direction too -- MOVE WS-APPROVED-AMT TO WS-APPROVED-AMT-DIS at line 720 of
        //       COPAUA0C.cbl moves a numeric field into an edited-display field before the STRING --
        //       so the rendering is a conversion step in the baseline and is kept as one here.
        List<String> values = List.of(
                request.authDate(),
                request.authTime(),
                request.cardNum(),
                request.authType(),
                request.cardExpiryDate(),
                request.messageType(),
                request.messageSource(),
                request.processingCode(),
                formatMoney(request.transactionAmount(), REQUEST_FIELD_NAMES.get(8)),
                request.merchantCategoryCode(),
                request.acquirerCountryCode(),
                request.posEntryMode(),
                request.merchantId(),
                request.merchantName(),
                request.merchantCity(),
                request.merchantState(),
                request.merchantZip(),
                request.transactionId());

        return join(values, REQUEST_FIELD_WIDTHS, REQUEST_FIELD_NAMES, false, REQUEST_WIRE_LENGTH,
                "request");
    }

    /**
     * Renders an authorization request as the bytes of its comma-delimited payload.
     *
     * @param request the request to render; must not be {@code null}
     * @return the payload bytes, always exactly {@code REQUEST_WIRE_LENGTH} bytes long
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws AuthMessageFormatException if the assembled payload does not reach exactly
     *     {@code REQUEST_WIRE_LENGTH} characters
     */
    public static byte[] encodeRequestBytes(AuthRequest request) {
        return toWireBytes(encodeRequest(request));
    }

    /**
     * Parses an authorization request from its comma-delimited payload.
     *
     * <p>Assumptions: the eighteen values are taken by ordinal position, which is the whole of the
     * contract. The {@code UNSTRING} at lines 354 to 355 of {@code COPAUA0C.cbl} names its eighteen
     * receiving fields at lines 356 to 373 in the order {@code CCPAURQY.cpy} declares them at lines
     * 19 to 36, and {@code REQUEST_FIELD_NAMES} records that order so a test can assert it against
     * the copybook rather than against this method.</p>
     *
     * @param payload the comma-delimited payload; must not be {@code null}. A trailing pad-only
     *     token is tolerated, so a producer that appends a delimiter after the last field is read
     *     rather than rejected
     * @return the parsed request, with every character field's trailing pad removed and the amount
     *     converted through the edited display form
     * @throws NullPointerException if {@code payload} is {@code null}
     * @throws AuthMessageFormatException if the payload does not carry exactly
     *     {@code REQUEST_FIELD_COUNT} fields, if any field overruns its declared width, or if the
     *     amount token is not a well-formed edited display value
     */
    public static AuthRequest decodeRequest(String payload) {
        List<String> tokens = splitOnDelimiter(payload, REQUEST_FIELD_COUNT, "request");

        return new AuthRequest(
                tokens.get(0),
                tokens.get(1),
                tokens.get(2),
                tokens.get(3),
                tokens.get(4),
                tokens.get(5),
                tokens.get(6),
                tokens.get(7),
                parseMoney(tokens.get(8), REQUEST_FIELD_NAMES.get(8)),
                tokens.get(9),
                tokens.get(10),
                tokens.get(11),
                tokens.get(12),
                tokens.get(13),
                tokens.get(14),
                tokens.get(15),
                tokens.get(16),
                tokens.get(17));
    }

    /**
     * Parses an authorization request from the first bytes of a transport buffer.
     *
     * @param buffer the transport buffer, which may be longer than the payload it carries; must not
     *     be {@code null}
     * @param payloadLength the number of leading bytes of {@code buffer} that constitute the payload,
     *     which is the analogue of the reference program's explicit data length and is deliberately
     *     not derived from the buffer's own length
     * @return the parsed request
     * @throws NullPointerException if {@code buffer} is {@code null}
     * @throws AuthMessageFormatException if {@code payloadLength} is negative or exceeds the
     *     buffer's length, if the payload does not carry exactly {@code REQUEST_FIELD_COUNT} fields,
     *     if any field overruns its declared width, or if the amount token is malformed
     */
    public static AuthRequest decodeRequest(byte[] buffer, int payloadLength) {
        return decodeRequest(payloadText(buffer, payloadLength, "request"));
    }

    /**
     * Renders an authorization reply as its comma-delimited payload.
     *
     * <p>Assumptions: the payload ends with a delimiter, which is why it is 63 characters and not 62.
     * The {@code STRING} at lines 722 to 731 of {@code COPAUA0C.cbl} pairs a {@code ','} literal with
     * every one of the six values including the sixth at line 727, so the trailing comma is part of
     * the emitted form rather than an artefact of it. Emitting the interior-only form that the
     * nominal arithmetic in {@code docs/architecture/messaging-contracts.md} predicts would produce a
     * payload one byte shorter than the one the reference consumer has always received.</p>
     *
     * <p>Assumptions: this method emits 63 characters where the reference program's put transmits 64.
     * The extra byte is a consequence of {@code WS-RESP-LENGTH} serving as both the {@code STRING}
     * cursor at line 730 and the put's buffer length at lines 756 and 762; the baseline sends 64, the
     * Java emits 63, and the divergence is documented. The reciprocal tolerance lives in
     * {@link #decodeReply(String)}.</p>
     *
     * @param reply the reply to render; must not be {@code null}
     * @return the payload, always exactly {@code REPLY_WIRE_LENGTH} characters, with six delimiters
     *     of which the last is trailing
     * @throws NullPointerException if {@code reply} is {@code null}
     * @throws AuthMessageFormatException if the assembled payload does not reach exactly
     *     {@code REPLY_WIRE_LENGTH} characters
     */
    public static String encodeReply(AuthReply reply) {
        if (reply == null) {
            throw new NullPointerException("reply must not be null");
        }

        List<String> values = List.of(
                reply.cardNum(),
                reply.transactionId(),
                reply.authIdCode(),
                reply.authRespCode(),
                reply.authRespReason(),
                formatMoney(reply.approvedAmount(), REPLY_FIELD_NAMES.get(5)));

        return join(values, REPLY_FIELD_WIDTHS, REPLY_FIELD_NAMES, true, REPLY_WIRE_LENGTH, "reply");
    }

    /**
     * Renders an authorization reply as the bytes of its comma-delimited payload.
     *
     * @param reply the reply to render; must not be {@code null}
     * @return the payload bytes, always exactly {@code REPLY_WIRE_LENGTH} bytes long
     * @throws NullPointerException if {@code reply} is {@code null}
     * @throws AuthMessageFormatException if the assembled payload does not reach exactly
     *     {@code REPLY_WIRE_LENGTH} characters
     */
    public static byte[] encodeReplyBytes(AuthReply reply) {
        return toWireBytes(encodeReply(reply));
    }

    /**
     * Parses an authorization reply from its comma-delimited payload.
     *
     * <p>Assumptions: a trailing pad-only token is expected here rather than merely tolerated,
     * because the trailing comma at line 727 of {@code COPAUA0C.cbl} guarantees one. Splitting a
     * 63-character payload on the delimiter yields seven tokens whose seventh is empty, and splitting
     * the 64-byte form the put actually sends yields seven whose seventh is a single space. Both are
     * accepted, and a seventh token carrying content is rejected, so the tolerance cannot be
     * mistaken for accepting a seven-field reply.</p>
     *
     * @param payload the comma-delimited payload; must not be {@code null}. The canonical form
     *     carries the trailing delimiter, and a producer that omits it is also accepted
     * @return the parsed reply, with every character field's trailing pad removed and the amount
     *     converted through the edited display form
     * @throws NullPointerException if {@code payload} is {@code null}
     * @throws AuthMessageFormatException if the payload does not carry exactly
     *     {@code REPLY_FIELD_COUNT} fields, if any field overruns its declared width, or if the
     *     amount token is not a well-formed edited display value
     */
    public static AuthReply decodeReply(String payload) {
        List<String> tokens = splitOnDelimiter(payload, REPLY_FIELD_COUNT, "reply");

        return new AuthReply(
                tokens.get(0),
                tokens.get(1),
                tokens.get(2),
                tokens.get(3),
                tokens.get(4),
                parseMoney(tokens.get(5), REPLY_FIELD_NAMES.get(5)));
    }

    /**
     * Parses an authorization reply from the first bytes of a transport buffer.
     *
     * @param buffer the transport buffer, which may be longer than the payload it carries; must not
     *     be {@code null}
     * @param payloadLength the number of leading bytes of {@code buffer} that constitute the payload,
     *     held separately from the buffer's own length
     * @return the parsed reply
     * @throws NullPointerException if {@code buffer} is {@code null}
     * @throws AuthMessageFormatException if {@code payloadLength} is negative or exceeds the
     *     buffer's length, if the payload does not carry exactly {@code REPLY_FIELD_COUNT} fields,
     *     if any field overruns its declared width, or if the amount token is malformed
     */
    public static AuthReply decodeReply(byte[] buffer, int payloadLength) {
        return decodeReply(payloadText(buffer, payloadLength, "reply"));
    }

    /**
     * Renders an amount in the fourteen-character edited display form the copybooks declare.
     *
     * <p>Assumptions: the rendering is {@code PIC +9(10).99} at line 27 of {@code CCPAURQY.cpy} and
     * line 24 of {@code CCPAURLY.cpy}, and it is fourteen characters rather than twelve or thirteen
     * because the sign and the decimal point are real bytes. One sign, ten integer digits, one point
     * and two fractional digits is fourteen. The implied decimal position that {@code V} denotes in
     * the zoned and packed pictures occupies no byte at all, and reading this field at that width
     * would shift every field after it.</p>
     *
     * <p>Assumptions: zero renders as a {@code +} followed by ten zeros and {@code .00}, so
     * {@link BigDecimal}'s lack of a negative zero is invisible on this wire. There is no
     * negative-zero carve-out to make here, unlike the packed rendering, where a sign nibble can
     * encode a signed zero distinctly and the decoder has to decide what that means.</p>
     *
     * <p>Trade-offs: this method is lossless or it raises. Silent truncation of a value wider than
     * ten integer digits was rejected because it yields a materially smaller number that still looks
     * like money, and silent rounding of a value carrying more than two decimal places was rejected
     * because rounding is a business decision and this is a transport boundary. Rounding is delegated
     * to {@link Money}, which applies scale {@value Money#SCALE} with {@code RoundingMode.HALF_UP} at
     * the point a caller has decided that reducing the value is correct. What is accepted is that a
     * caller holding a three-decimal intermediate has to route it through {@code Money} explicitly
     * rather than having this method decide for them.</p>
     *
     * @param amount the amount to render; must not be {@code null} and must carry no more than
     *     {@code MONEY_SCALE} decimal places, a value with fewer being padded rather than rejected
     * @param fieldName the copybook name of the field being rendered, used only to compose a failure
     *     message so that a rejection names the field a reader can look up
     * @return the amount as exactly {@code MONEY_EDITED_WIDTH} characters: a sign, ten integer digits
     *     left-padded with zeros, a literal decimal point, and two fractional digits
     * @throws NullPointerException if {@code amount} is {@code null}
     * @throws AuthMessageFormatException if {@code amount} carries more than {@code MONEY_SCALE}
     *     decimal places, or if its magnitude needs more than {@code MONEY_INTEGER_DIGITS} integer
     *     digits
     */
    public static String formatMoney(BigDecimal amount, String fieldName) {
        if (amount == null) {
            throw new NullPointerException(fieldName + " amount must not be null");
        }

        if (amount.scale() > MONEY_SCALE) {
            throw new AuthMessageFormatException(fieldName
                    + " carries " + amount.scale() + " decimal places but the picture declares "
                    + MONEY_SCALE + "; reduce it through Money so the rounding is an explicit"
                    + " decision, value was " + amount.toPlainString());
        }

        // WHY : Assumptions: the bare setScale is safe only because of the guard above. It raises
        //       whenever a reduction would discard a digit, and the guard has already established
        //       that the scale is at most two, so this call only ever pads. Passing a rounding mode
        //       here instead would make the guard decorative and would reintroduce exactly the
        //       silent rounding the guard exists to prevent.
        BigDecimal canonical = amount.setScale(MONEY_SCALE);

        // WHY : Assumptions: the sign is taken from the signed value while the digits are taken from
        //       its magnitude, because the picture places the sign in its own leading position rather
        //       than folding it into a digit. That is the difference between this rendering and the
        //       zoned overpunch, where the sign shares the final byte with a digit.
        String magnitude = canonical.abs().toPlainString();
        int pointIndex = magnitude.indexOf(MONEY_DECIMAL_POINT);
        String integerDigits = magnitude.substring(0, pointIndex);
        String fractionDigits = magnitude.substring(pointIndex + 1);

        if (integerDigits.length() > MONEY_INTEGER_DIGITS) {
            throw new AuthMessageFormatException(fieldName
                    + " needs " + integerDigits.length() + " integer digits but the picture declares "
                    + MONEY_INTEGER_DIGITS + "; value was " + canonical.toPlainString());
        }

        StringBuilder rendered = new StringBuilder(MONEY_EDITED_WIDTH);
        rendered.append(canonical.signum() < 0 ? MONEY_NEGATIVE_SIGN : MONEY_POSITIVE_SIGN);
        for (int position = integerDigits.length(); position < MONEY_INTEGER_DIGITS; position++) {
            rendered.append(ZERO_DIGIT);
        }
        rendered.append(integerDigits).append(MONEY_DECIMAL_POINT).append(fractionDigits);

        // WHY : Assumptions: the result is MONEY_EDITED_WIDTH characters by construction rather than
        //       by check. It is one sign, MONEY_INTEGER_DIGITS digits after the zero padding above,
        //       one point, and the MONEY_SCALE fractional digits that the canonical scale guarantees
        //       toPlainString emits. A length check here would test the two loops directly above it
        //       rather than anything a caller can influence.
        return rendered.toString();
    }

    /**
     * Renders a monetary amount in the fourteen-character edited display form the copybooks declare.
     *
     * <p>Assumptions: this overload cannot raise a format failure, because its argument type has
     * already excluded both failure modes. A {@link Money} is invariantly exact at
     * {@value Money#SCALE} decimal places and bounded by {@code Money.MAX_MAGNITUDE}, which is
     * {@code 9999999999.99} -- precisely the ten integer digits this picture declares -- so neither
     * the scale guard nor the width guard in the delegate can fire. It is offered as the ergonomic
     * entry point, and the {@link BigDecimal} overload remains available for a caller that holds a
     * raw value and wants the guards applied.</p>
     *
     * @param amount the amount to render; must not be {@code null}
     * @param fieldName the copybook name of the field being rendered, used only to compose a failure
     *     message
     * @return the amount as exactly {@code MONEY_EDITED_WIDTH} characters
     * @throws NullPointerException if {@code amount} is {@code null}
     */
    public static String formatMoney(Money amount, String fieldName) {
        if (amount == null) {
            throw new NullPointerException(fieldName + " amount must not be null");
        }

        return formatMoney(amount.amount(), fieldName);
    }

    /**
     * Parses an amount from the edited display token the wire carries.
     *
     * <p>Assumptions: the conversion is an explicit parse and never a byte copy of the wire
     * characters. The
     * reference program converts explicitly in this direction too --
     * {@code COMPUTE PA-RQ-TRANSACTION-AMT = FUNCTION NUMVAL(WS-TRANSACTION-AMT-AN)} at lines 376 to
     * 377 of {@code COPAUA0C.cbl} -- so the text form and the numeric form are distinct
     * representations in the baseline and are kept distinct here.</p>
     *
     * <p>Assumptions: the accepted grammar is wider than the emitted one, and each widening answers
     * something measurable in the source. The sign may be omitted, because the outbound rendering the
     * reference program actually uses is {@code WS-APPROVED-AMT-DIS PIC -zzzzzzzzz9.99} at line 66,
     * whose zero suppression leaves the sign position blank for a positive value. Leading and
     * trailing pad
     * are removed, because that same zero-suppressed rendering emits leading spaces and because the
     * put's pointer-derived length appends a trailing one. Fewer than ten integer digits are
     * accepted, for the same zero-suppression reason. What is not widened is the decimal point, the
     * digits or the sign character itself: a missing or misplaced point, a non-digit in a digit
     * position, and a sign that is neither {@code +} nor {@code -} are each rejected by name, because
     * every one of them would otherwise be read as a different amount rather than as a failure.</p>
     *
     * @param token the field as it appeared on the wire, with or without its pad; must not be
     *     {@code null}
     * @param fieldName the copybook name of the field being parsed, used to compose a failure message
     *     so that a rejected payload names the field and the offending token
     * @return the parsed amount, exact at {@value Money#SCALE} decimal places
     * @throws NullPointerException if {@code token} is {@code null}
     * @throws AuthMessageFormatException if the token is blank, carries a sign character other than
     *     {@code +} or {@code -}, has no decimal point or more than one, has no integer digit before
     *     the point, does not have exactly {@code MONEY_SCALE} digits after it, contains a non-digit
     *     in a digit position, or needs more than {@code MONEY_INTEGER_DIGITS} integer digits
     */
    public static Money parseMoney(String token, String fieldName) {
        if (token == null) {
            throw new NullPointerException(fieldName + " token must not be null");
        }

        String value = stripSurroundingPad(token);
        if (value.isEmpty()) {
            throw new AuthMessageFormatException(
                    fieldName + " carries no amount; the picture declares " + MONEY_EDITED_WIDTH
                            + " characters and the token was blank");
        }

        boolean negative = false;
        int digitsStart = 0;
        char signCandidate = value.charAt(0);
        if (signCandidate == MONEY_POSITIVE_SIGN) {
            digitsStart = 1;
        } else if (signCandidate == MONEY_NEGATIVE_SIGN) {
            negative = true;
            digitsStart = 1;
        } else if (!isAsciiDigit(signCandidate)) {
            throw new AuthMessageFormatException(fieldName
                    + " carries '" + signCandidate + "' in its sign position, which the picture"
                    + " declares as '" + MONEY_POSITIVE_SIGN + "' or '" + MONEY_NEGATIVE_SIGN
                    + "'; token was '" + token + "'");
        }

        String unsigned = value.substring(digitsStart);
        int pointIndex = unsigned.indexOf(MONEY_DECIMAL_POINT);
        if (pointIndex < 0) {
            throw new AuthMessageFormatException(fieldName
                    + " carries no '" + MONEY_DECIMAL_POINT + "'; the picture declares a literal"
                    + " decimal point, token was '" + token + "'");
        }
        if (unsigned.indexOf(MONEY_DECIMAL_POINT, pointIndex + 1) >= 0) {
            throw new AuthMessageFormatException(fieldName
                    + " carries more than one '" + MONEY_DECIMAL_POINT + "'; token was '" + token
                    + "'");
        }

        String integerDigits = unsigned.substring(0, pointIndex);
        String fractionDigits = unsigned.substring(pointIndex + 1);

        if (integerDigits.isEmpty()) {
            throw new AuthMessageFormatException(fieldName
                    + " has no digit before its decimal point; token was '" + token + "'");
        }
        if (fractionDigits.length() != MONEY_SCALE) {
            throw new AuthMessageFormatException(fieldName
                    + " has " + fractionDigits.length() + " digits after its decimal point but the"
                    + " picture declares " + MONEY_SCALE + "; token was '" + token + "'");
        }

        requireAsciiDigits(integerDigits, fieldName, token);
        requireAsciiDigits(fractionDigits, fieldName, token);

        if (integerDigits.length() > MONEY_INTEGER_DIGITS) {
            throw new AuthMessageFormatException(fieldName
                    + " needs " + integerDigits.length() + " integer digits but the picture declares "
                    + MONEY_INTEGER_DIGITS + "; token was '" + token + "'");
        }

        // WHY : Assumptions: the two validated digit runs are read as one whole number of cents and
        //       handed to Money.ofCents, which interprets it against an implied decimal point and so
        //       performs no division and engages no rounding mode. Money.of would have accepted a
        //       decimal string just as readily and would have applied its rounding mode on the way
        //       in, which is a behaviour this path must not have. The concatenation is at most
        //       MONEY_INTEGER_DIGITS plus MONEY_SCALE digits, so twelve, and every twelve-digit
        //       integer is inside the long domain; the ten-digit bound checked above is also exactly
        //       Money.MAX_MAGNITUDE, so the factory's own domain check cannot fire here either.
        long cents = Long.parseLong(integerDigits + fractionDigits);

        return Money.ofCents(negative ? -cents : cents);
    }

    /**
     * Joins already-rendered field values into a delimited payload of the expected wire length.
     *
     * @param values the field values in wire order, each already within its declared width
     * @param widths the declared width of each field, in the same order as {@code values}
     * @param names the copybook name of each field, in the same order, used for failure messages
     * @param trailingDelimiter whether a delimiter follows the final field, which the reply carries
     *     and the request does not
     * @param expectedWireLength the length the assembled payload must reach
     * @param payloadName the payload's name, used to compose a failure message
     * @return the assembled payload, exactly {@code expectedWireLength} characters long
     * @throws AuthMessageFormatException if a value overruns its declared width, or if the assembled
     *     length does not equal {@code expectedWireLength}
     */
    private static String join(List<String> values, List<Integer> widths, List<String> names,
            boolean trailingDelimiter, int expectedWireLength, String payloadName) {
        StringBuilder payload = new StringBuilder(expectedWireLength);
        int lastIndex = values.size() - 1;

        // WHAT: pad each value out to its declared width and place a delimiter after it, omitting
        //       only the delimiter that would follow the final field of a payload that has none.
        // WHY : Assumptions: the builder is sized to the expected wire length rather than left to
        //       grow, because that length is known before the first field is appended and is the same
        //       for every message of this kind. Sizing it also means a payload that reached the wrong
        //       length would have reallocated, which the guard below then reports rather than hides.
        for (int fieldIndex = 0; fieldIndex <= lastIndex; fieldIndex++) {
            payload.append(padToWidth(values.get(fieldIndex), widths.get(fieldIndex),
                    names.get(fieldIndex)));

            // WHY : Assumptions: whether the final field is followed by a delimiter is a per-payload
            //       property rather than a general one, which is the whole of defect D-COMMA. The
            //       reply's STRING at lines 722 to 731 of COPAUA0C.cbl pairs a comma with all six
            //       values including the sixth, while the request's UNSTRING at line 354 names
            //       eighteen receiving fields and therefore consumes only interior delimiters.
            if (fieldIndex < lastIndex || trailingDelimiter) {
                payload.append(DELIMITER);
            }
        }

        // WHY : Assumptions: this guard protects a future edit rather than the current caller. Every
        //       value reaching here has already been width-checked at construction, so the length is
        //       determined by the width table and the delimiter rule alone. What it would catch is a
        //       field added to a carrier without a matching entry in the width table, or a wire
        //       length constant changed without its table -- either of which would otherwise ship a
        //       payload that parses field by field and is the wrong length overall.
        if (payload.length() != expectedWireLength) {
            throw new AuthMessageFormatException("assembled " + payloadName + " payload is "
                    + payload.length() + " characters but the contract declares "
                    + expectedWireLength + "; the declared width table and the wire length constant"
                    + " no longer agree");
        }

        return payload.toString();
    }

    /**
     * Splits a payload on the delimiter and establishes that it carries the expected field count.
     *
     * <p>Assumptions: the split keeps empty tokens, because an omitted optional field and a trailing
     * delimiter are both legitimate and both produce one. A split that discarded them would turn a
     * blank card number into a missing field and shift every value after it.</p>
     *
     * @param payload the delimited payload as received; must not be {@code null}
     * @param expectedFieldCount the number of fields the payload's copybook declares
     * @param payloadName the payload's name, used to compose a failure message
     * @return the field values in wire order, exactly {@code expectedFieldCount} of them, with any
     *     tolerated trailing pad-only token removed
     * @throws NullPointerException if {@code payload} is {@code null}
     * @throws AuthMessageFormatException if the payload carries fewer or more fields than
     *     {@code expectedFieldCount}, a trailing pad-only token being the one tolerated excess
     */
    private static List<String> splitOnDelimiter(String payload, int expectedFieldCount,
            String payloadName) {
        if (payload == null) {
            throw new NullPointerException(payloadName + " payload must not be null");
        }

        // WHY : Assumptions: the payload length and the scan position are separate, differently named
        //       values here on purpose. Holding a one-based cursor and a length in one variable is
        //       exactly what produced the reference program's extra byte: WS-RESP-LENGTH is the
        //       STRING cursor at line 730 of COPAUA0C.cbl and is then used as the put's length at
        //       lines 756 and 762. Naming them apart makes that conflation unavailable here.
        final int payloadLength = payload.length();

        // WHAT: count the delimiters, then fill an array sized from that count, so that every field
        //       between two delimiters is retained even when it is empty.
        // WHY : Alternatives Considered: String.split with a negative limit produces the same tokens
        //       in one line and was rejected on two counts. It compiles its argument as a regular
        //       expression, so the delimiter would become a pattern and a future change to a
        //       character that happens to be a metacharacter would silently stop matching it; and it
        //       offers nowhere to name the payload length and the scan position apart, which is the
        //       separation this method exists to keep. A growable collection would avoid the first
        //       pass and would need a type this class deliberately does not depend on.
        int delimiterCount = 0;
        for (int scanPosition = 0; scanPosition < payloadLength; scanPosition++) {
            if (payload.charAt(scanPosition) == DELIMITER) {
                delimiterCount++;
            }
        }

        String[] tokens = new String[delimiterCount + 1];
        int tokenIndex = 0;
        int fieldStart = 0;
        for (int scanPosition = 0; scanPosition < payloadLength; scanPosition++) {
            if (payload.charAt(scanPosition) == DELIMITER) {
                tokens[tokenIndex] = payload.substring(fieldStart, scanPosition);
                tokenIndex++;
                fieldStart = scanPosition + 1;
            }
        }
        tokens[tokenIndex] = payload.substring(fieldStart, payloadLength);

        List<String> fields = List.of(tokens);

        // WHY : Trade-offs: one excess token is accepted when it holds nothing but pad, and no other
        //       excess is. This is the tolerant half of the strict-encode, tolerant-decode discipline
        //       and it is narrower than it first looks. It admits the reply's canonical trailing
        //       comma, which yields an empty seventh token, and the 64-byte form the put actually
        //       sends, whose seventh token is a single space from the 200-byte buffer at line 108 of
        //       COPAUA0C.cbl. It rejects a genuine extra field, so the tolerance cannot be mistaken
        //       for accepting a seven-field reply or a nineteen-field request. Symmetric strictness
        //       was rejected because it would reject every reply the reference program emits.
        if (fields.size() == expectedFieldCount + 1 && isPadOnly(fields.get(expectedFieldCount))) {
            return fields.subList(0, expectedFieldCount);
        }

        if (fields.size() != expectedFieldCount) {
            throw new AuthMessageFormatException("the " + payloadName + " payload carries "
                    + fields.size() + " delimited fields but the copybook declares "
                    + expectedFieldCount + "; field order and the delimiter are the whole of this"
                    + " contract, so a differing count cannot be reconciled by position");
        }

        return fields;
    }

    /**
     * Reads the leading bytes of a transport buffer as payload text.
     *
     * <p>Assumptions: the payload length is taken from the caller and never from the buffer, because
     * the two are routinely different. The reference program declares
     * {@code 01 W01-GET-BUFFER PIC X(500).} at line 103 of {@code COPAUA0C.cbl}, sets
     * {@code W01-BUFFLEN} to that whole length at line 398, and then parses only
     * {@code W01-GET-BUFFER(1:W01-DATALEN)} at line 354. A codec that inferred the payload from the
     * buffer would read 500 bytes of which most are pad.</p>
     *
     * <p>Assumptions: the decoding is single-byte, so a declared character width is also a byte
     * width. Alternatives Considered: UTF-8 was evaluated and rejected. One non-ASCII byte in the
     * 22-character merchant name at line 32 of {@code CCPAURQY.cpy} would either combine with its
     * neighbour into one character or decode to a replacement character, and in both cases a field's
     * character count would stop matching its byte count and the payload would miss its declared wire
     * length while every field still read plausibly as text.</p>
     *
     * @param buffer the transport buffer; must not be {@code null}
     * @param payloadLength the number of leading bytes that constitute the payload
     * @param payloadName the payload's name, used to compose a failure message
     * @return the payload as text, exactly {@code payloadLength} characters long
     * @throws NullPointerException if {@code buffer} is {@code null}
     * @throws AuthMessageFormatException if {@code payloadLength} is negative or greater than the
     *     buffer's length
     */
    private static String payloadText(byte[] buffer, int payloadLength, String payloadName) {
        if (buffer == null) {
            throw new NullPointerException(payloadName + " buffer must not be null");
        }
        if (payloadLength < 0 || payloadLength > buffer.length) {
            throw new AuthMessageFormatException("the " + payloadName + " payload length is "
                    + payloadLength + ", which is outside the " + buffer.length
                    + "-byte buffer that carries it");
        }

        return new String(buffer, 0, payloadLength, StandardCharsets.ISO_8859_1);
    }

    /**
     * Encodes an assembled payload as the bytes the transport carries.
     *
     * <p>Assumptions: the encoding is the single-byte counterpart of the decoding, for the reason
     * recorded on {@code payloadText}. Because that encoding maps every character it can represent to
     * exactly one byte, the returned array's length equals the payload's character length, which is
     * what lets a caller pass the declared wire length as the message length without measuring the
     * array.</p>
     *
     * @param payload the assembled payload text
     * @return the payload bytes, one byte per character
     */
    private static byte[] toWireBytes(String payload) {
        return payload.getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Normalises and validates one character field against the width its copybook line declares.
     *
     * <p>Assumptions: only trailing pad is removed, never leading pad. A COBOL alphanumeric move
     * left-justifies its source and pads on the right, so on this wire the pad is always trailing and
     * a leading space is data. Stripping both ends would silently alter a value whose first character
     * is genuinely a space.</p>
     *
     * @param value the field value as supplied or as received; must not be {@code null}
     * @param cobolName the copybook name of the field, used to compose a failure message
     * @param declaredWidth the width the copybook line declares for this field
     * @return the value with its trailing pad removed
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws AuthMessageFormatException if the value contains the delimiter, or is longer than
     *     {@code declaredWidth} once its trailing pad is removed
     */
    private static String characterField(String value, String cobolName, int declaredWidth) {
        if (value == null) {
            throw new NullPointerException(cobolName + " must not be null");
        }

        String normalised = stripTrailingPad(value);

        // WHY : Assumptions: an embedded delimiter is unrepresentable rather than merely awkward.
        //       Nothing in the baseline quotes or escapes a field: the UNSTRING at lines 354 to 355
        //       of COPAUA0C.cbl splits on every comma it meets, so one comma inside a merchant name
        //       would be read as a field boundary and would shift every value after it by one
        //       position. Rejecting it where the value enters is the only place the offending field
        //       is still identifiable.
        if (normalised.indexOf(DELIMITER) >= 0) {
            throw new AuthMessageFormatException(cobolName + " contains the '" + DELIMITER
                    + "' delimiter, which this payload has no way to quote or escape; value was '"
                    + normalised + "'");
        }

        if (normalised.length() > declaredWidth) {
            throw new AuthMessageFormatException(cobolName + " is " + normalised.length()
                    + " characters but the copybook declares " + declaredWidth + "; value was '"
                    + normalised + "'");
        }

        return normalised;
    }

    /**
     * Establishes that a monetary component was supplied.
     *
     * @param amount the amount to check; must not be {@code null}
     * @param cobolName the copybook name of the field, used to compose a failure message
     * @return the amount unchanged
     * @throws NullPointerException if {@code amount} is {@code null}
     */
    private static Money requireAmount(Money amount, String cobolName) {
        if (amount == null) {
            throw new NullPointerException(cobolName + " must not be null");
        }

        return amount;
    }

    /**
     * Builds the correlation key from a card number and a transaction identifier.
     *
     * <p>Assumptions: each part is padded back to its declared width rather than concatenated as
     * stored, so the key is positional and its two halves cannot run together. Concatenating the
     * trimmed values would make two distinct pairs collide whenever one card number were a prefix of
     * another, which is the failure a positional key exists to prevent.</p>
     *
     * @param cardNum the card number, already within {@code CARD_NUM_WIDTH} characters
     * @param cardNumName the copybook name of the card number field on the carrier that asked, used
     *     only to compose a failure message
     * @param transactionId the transaction identifier, already within
     *     {@code TRANSACTION_ID_WIDTH} characters
     * @param transactionIdName the copybook name of the transaction identifier field on the carrier
     *     that asked, used only to compose a failure message
     * @return the correlation key, exactly {@code CORRELATION_KEY_LENGTH} characters long
     * @throws AuthMessageFormatException if either part is longer than its declared width
     */
    private static String buildCorrelationKey(String cardNum, String cardNumName,
            String transactionId, String transactionIdName) {
        // WHY : Assumptions: the two field names are supplied by the caller rather than written here
        //       because the same key is built from two differently named pairs -- PA-RQ-CARD-NUM with
        //       PA-RQ-TRANSACTION-ID at lines 21 and 36 of CCPAURQY.cpy, and PA-RL-CARD-NUM with
        //       PA-RL-TRANSACTION-ID at lines 19 and 20 of CCPAURLY.cpy. A message naming the reply's
        //       fields for a request would send a reader to the wrong copybook.
        return padToWidth(cardNum, CARD_NUM_WIDTH, cardNumName)
                + padToWidth(transactionId, TRANSACTION_ID_WIDTH, transactionIdName);
    }

    /**
     * Pads a field value on the right to the width its copybook line declares.
     *
     * @param value the field value, already within {@code declaredWidth} characters
     * @param declaredWidth the width the copybook line declares for this field
     * @param cobolName the copybook name of the field, used to compose a failure message
     * @return the value followed by enough pad characters to reach {@code declaredWidth}
     * @throws AuthMessageFormatException if the value is longer than {@code declaredWidth}
     */
    private static String padToWidth(String value, int declaredWidth, String cobolName) {
        if (value.length() > declaredWidth) {
            throw new AuthMessageFormatException(cobolName + " is " + value.length()
                    + " characters but the copybook declares " + declaredWidth + "; value was '"
                    + value + "'");
        }

        StringBuilder padded = new StringBuilder(declaredWidth);
        padded.append(value);
        while (padded.length() < declaredWidth) {
            padded.append(PAD);
        }

        return padded.toString();
    }

    /**
     * Removes the trailing pad characters from a field value.
     *
     * @param value the field value as supplied or as received
     * @return the value with every trailing pad character removed, which may be empty
     */
    private static String stripTrailingPad(String value) {
        // WHY : Assumptions: only the ASCII space is treated as pad, rather than every character
        //       Java's own trailing-strip considers whitespace. DELIMITED BY SIZE at line 728 of
        //       COPAUA0C.cbl contributes the unused positions of a PIC X field, and those positions
        //       hold spaces; a tab or a line feed inside a field would be data, and removing it would
        //       change a value rather than remove its padding.
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == PAD) {
            end--;
        }

        return value.substring(0, end);
    }

    /**
     * Removes the leading and trailing pad characters from a field value.
     *
     * @param value the field value as received
     * @return the value with pad characters removed from both ends, which may be empty
     */
    private static String stripSurroundingPad(String value) {
        // WHY : Assumptions: the money token is the one field stripped at both ends, because the
        //       rendering the reference program emits leaves pad on the left. WS-APPROVED-AMT-DIS is
        //       PIC -zzzzzzzzz9.99 at line 66 of COPAUA0C.cbl, and zero suppression replaces each
        //       leading zero with a space, so a small positive amount arrives right-justified inside
        //       its fourteen positions with nothing in the sign position at all.
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == PAD) {
            start++;
        }
        while (end > start && value.charAt(end - 1) == PAD) {
            end--;
        }

        return value.substring(start, end);
    }

    /**
     * Reports whether a token holds nothing but pad characters.
     *
     * @param value the token to inspect
     * @return {@code true} when the token is empty or consists only of pad characters
     */
    private static boolean isPadOnly(String value) {
        for (int position = 0; position < value.length(); position++) {
            if (value.charAt(position) != PAD) {
                return false;
            }
        }

        return true;
    }

    /**
     * Establishes that every character of a digit run is an ASCII digit.
     *
     * @param digits the run of characters that the picture declares as digit positions
     * @param fieldName the copybook name of the field being parsed, used for the failure message
     * @param token the whole token as received, quoted in the failure message so the offending value
     *     is visible rather than only the run it was taken from
     * @throws AuthMessageFormatException if any character of {@code digits} is not an ASCII digit
     */
    private static void requireAsciiDigits(String digits, String fieldName, String token) {
        for (int position = 0; position < digits.length(); position++) {
            char digit = digits.charAt(position);
            if (!isAsciiDigit(digit)) {
                throw new AuthMessageFormatException(fieldName + " carries '" + digit
                        + "' in a digit position; token was '" + token + "'");
            }
        }
    }

    /**
     * Reports whether a character is one of the ten ASCII digits.
     *
     * @param candidate the character to classify
     * @return {@code true} when the character is in the range {@code '0'} to {@code '9'}
     */
    private static boolean isAsciiDigit(char candidate) {
        // WHY : Assumptions: an explicit ASCII range test is used rather than Character.isDigit,
        //       which accepts every Unicode decimal digit including the Arabic-Indic and Devanagari
        //       forms. None of those is representable in the single-byte encoding this payload uses,
        //       so accepting one here would let a token pass validation and then encode to a byte
        //       that no reader of this contract can interpret as a digit.
        return candidate >= ZERO_DIGIT && candidate <= '9';
    }
}
