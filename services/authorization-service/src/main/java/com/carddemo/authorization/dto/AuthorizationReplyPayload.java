package com.carddemo.authorization.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.carddemo.common.money.Money;
import com.carddemo.common.security.CardNumberMasker;

/**
 * Carries the six-field pending-authorization reply in its declared wire order.
 *
 * <h2>Purpose</h2>
 * <p>This record is the typed boundary for the reply layout declared by
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy} lines 19 through 24. The
 * declaration order below is therefore part of the interface: the CSV codec reads the six
 * components from first to last without reordering them.</p>
 *
 * <table>
 *   <caption>Pending-authorization reply fields</caption>
 *   <tr>
 *     <th>CSV position</th>
 *     <th>Copybook field</th>
 *     <th>Line</th>
 *     <th>PICTURE</th>
 *     <th>Declared width</th>
 *   </tr>
 *   <tr><td>1</td><td>PA-RL-CARD-NUM</td><td>19</td><td>X(16)</td><td>16</td></tr>
 *   <tr><td>2</td><td>PA-RL-TRANSACTION-ID</td><td>20</td><td>X(15)</td><td>15</td></tr>
 *   <tr><td>3</td><td>PA-RL-AUTH-ID-CODE</td><td>21</td><td>X(06)</td><td>6</td></tr>
 *   <tr><td>4</td><td>PA-RL-AUTH-RESP-CODE</td><td>22</td><td>X(02)</td><td>2</td></tr>
 *   <tr><td>5</td><td>PA-RL-AUTH-RESP-REASON</td><td>23</td><td>X(04)</td><td>4</td></tr>
 *   <tr><td>6</td><td>PA-RL-APPROVED-AMT</td><td>24</td><td>+9(10).99</td><td>14</td></tr>
 * </table>
 *
 * <p>The field-width sum is exactly 57 bytes:
 * {@code 16 + 15 + 6 + 2 + 4 + 14}. The comma-delimited wire length is exactly 63 bytes,
 * because the outbound {@code STRING} in
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 722 through 731
 * emits one separator after each of the six fields, including the final amount at line 727.
 * These measurements describe different things: 57 is the sum of field widths, while 63 is
 * the complete CSV wire length.</p>
 *
 * <p>Assumptions: {@code DELIMITED BY SIZE} at {@code COPAUA0C.cbl} line 728 means every
 * field contributes its full declared width. Padding spaces remain data for the purpose of
 * byte counting and are never trimmed by {@code CsvAuthCodec}; trimming any field changes
 * the 63-byte wire contract.</p>
 *
 * <p>Assumptions: the sixth wire field uses the emitted
 * {@code WS-APPROVED-AMT-DIS PIC -zzzzzzzzz9.99} form declared at
 * {@code COPAUA0C.cbl} line 66 and populated at line 720, rather than the
 * {@code PA-RL-APPROVED-AMT PIC +9(10).99} picture declared in the reply copybook. Both
 * forms occupy 14 characters. The copybook picture always carries a sign and fills leading
 * positions with zeroes; the emitted form shows a minus only for a negative value and
 * suppresses leading zeroes to spaces. {@code CsvAuthCodec} encodes and decodes that emitted
 * form, while this record carries the exact decimal value as {@link Money}.</p>
 *
 * <p>Alternatives Considered: representing the amount as a JSON number was rejected because
 * most clients route that form through an IEEE-754 binary approximation, which cannot retain
 * every decimal cent exactly. The baseline already transports the amount as text and converts
 * inbound text with {@code FUNCTION NUMVAL} at {@code COPAUA0C.cbl} lines 376 and 377.
 * Serialising {@link Money} as a JSON string therefore preserves the existing decimal
 * boundary rather than introducing a binary one.</p>
 *
 * <p>Trade-offs: CSV remains the normative queue representation. The baseline marks the
 * outbound reply as {@code MQFMT-STRING} at {@code COPAUA0C.cbl} line 751, so field order
 * and the comma delimiter are the interface for the requester already waiting on that queue.
 * A JSON envelope is easier for an additional consumer to parse and is offered additively;
 * it does not replace CSV on the existing reply path.</p>
 *
 * <p>Alternatives Considered: Lombok was excluded because generated accessors cannot carry
 * the component documentation required at this boundary; a Java 21 record supplies the same
 * concise value shape while leaving every component visible in this declaration. MapStruct
 * was excluded because the surrounding projection is not mechanical: representation code
 * masks the primary account number, withholds the card-verification value, and preserves
 * documented spelling divergences. The name {@code AuthorizationReply} was also considered.
 * The {@code Payload} suffix is retained because this type is the SQS and CSV wire contract,
 * not the HTTP surface.</p>
 *
 * <h2>Requiredness and character domains</h2>
 *
 * <p>Refactoring Rationale: every component is now {@code @NotNull}, and previously none was. A reply
 * exists to answer a requester with six values, so a null in any of them describes no reply at all --
 * and because the codec renders each component into a fixed-width field, a null reached the renderer
 * rather than the validator and failed there, with a message about a width rather than about a missing
 * answer.</p>
 *
 * <p>Refactoring Rationale: the first three components are no longer restricted to digits. All three
 * are {@code PIC X} at {@code CCPAURLY.cpy} lines 19 to 21, so their copybook fields hold any
 * character the width allows; a digits-only expression refused values those fields can legitimately
 * carry, and it refused them at this boundary while the reference program accepted them. The two
 * genuinely closed domains -- the response code and the response reason -- keep their expressions,
 * because for those the value set is fixed by the emitting paragraph rather than by a picture.</p>
 *
 * <p>Refactoring Rationale: the width constraints are now maxima, where they were previously exact.
 * The exact form was drift rather than rigour: {@code CsvAuthCodec} pads a short value to its declared
 * width when it assembles the payload, so a six-character identification code and a three-character
 * one both reach the wire as six characters, and this boundary rejected the second while the codec
 * accepted it. The wire is still fixed-width -- padding is what makes it so -- and the padding belongs
 * to the codec, which is the one place the width table lives.</p>
 *
 * <p>Assumptions: no expression here excludes the comma delimiter, and its absence is deliberate
 * rather than an omission. {@code CsvAuthCodec} refuses an embedded delimiter in the component that
 * carries it, at the point the value is supplied, because a delimited payload with no quoting rule has
 * no way to represent one. Repeating that check here would put the delimiter rule in two places, and
 * the copy would be the one that fell behind.</p>
 *
 * <p>Alternatives Considered: a compact constructor that repeated the annotation checks was
 * evaluated and deliberately omitted. Jakarta Bean Validation owns field-specific boundary
 * failures for the five textual components, while {@link Money} owns decimal scale and
 * magnitude. Throwing from construction would replace structured component violations with
 * a generic binding failure, and normalising by trimming or padding here would alter values
 * whose fixed-width treatment belongs to {@code CsvAuthCodec}. The record therefore stores
 * the supplied semantic values without a second validation path.</p>
 *
 * <h2>Return values and exceptions</h2>
 * <p>A type declaration returns no value. This record declares no constructor body and raises
 * no exception itself; constraint violations are reported by the Jakarta validation boundary,
 * and monetary construction errors belong to the {@link Money} factory that created the
 * sixth component.</p>
 *
 * <h2>Test channel</h2>
 * <p>The test channel for this contract requires a CSV fixture whose field-width sum is
 * exactly 57 bytes. It also requires a {@code CsvAuthCodec} field-order-and-delimiter test
 * that encodes the emitted {@code -zzzzzzzzz9.99} amount form and asserts all six separators
 * and the complete 63-byte wire.</p>
 *
 * @param cardNumber the 16-character value from {@code PA-RL-CARD-NUM PIC X(16)} at
 *     {@code CCPAURLY.cpy} line 19, occupying CSV position 1 and echoing the request value
 *     assigned at {@code COPAUA0C.cbl} line 660
 * @param transactionId the 15-character value from {@code PA-RL-TRANSACTION-ID PIC X(15)} at
 *     {@code CCPAURLY.cpy} line 20, occupying CSV position 2 and echoing the request value
 *     assigned at {@code COPAUA0C.cbl} line 661
 * @param authIdCode the 6-character value from {@code PA-RL-AUTH-ID-CODE PIC X(06)} at
 *     {@code CCPAURLY.cpy} line 21 and CSV position 3, carrying the request authorization
 *     time assigned at {@code COPAUA0C.cbl} line 662 rather than a newly generated identifier
 * @param authResponseCode the 2-character decision code from {@code PA-RL-AUTH-RESP-CODE} at
 *     {@code CCPAURLY.cpy} line 22 and CSV position 4; {@code 00} denotes approval and
 *     {@code 05} denotes decline at {@code COPAUA0C.cbl} lines 693 and 688
 * @param authResponseReason the 4-character reason from {@code PA-RL-AUTH-RESP-REASON} at
 *     {@code CCPAURLY.cpy} line 23 and CSV position 5; the domain is {@code 0000},
 *     {@code 3100}, {@code 4100}, {@code 4200}, {@code 4300}, {@code 5100},
 *     {@code 5200}, or {@code 9000} as assigned at {@code COPAUA0C.cbl} lines 698
 *     through 716
 * @param approvedAmount the exact decimal value for {@code PA-RL-APPROVED-AMT} at
 *     {@code CCPAURLY.cpy} line 24 and CSV position 6, encoded into a 14-character emitted
 *     field; it is zero on decline and the requested transaction amount on approval at
 *     {@code COPAUA0C.cbl} lines 689 and 694
 */
public record AuthorizationReplyPayload(
        @NotNull @Size(max = CARD_NUMBER_WIDTH) String cardNumber,
        @NotNull @Size(max = TRANSACTION_ID_WIDTH) String transactionId,
        @NotNull @Size(max = AUTH_ID_CODE_WIDTH) String authIdCode,
        @NotNull @Size(max = AUTH_RESPONSE_CODE_WIDTH) @Pattern(regexp = RESPONSE_CODE_DOMAIN)
                String authResponseCode,
        @NotNull @Size(max = AUTH_RESPONSE_REASON_WIDTH) @Pattern(regexp = RESPONSE_REASON_DOMAIN)
                String authResponseReason,
        @NotNull Money approvedAmount) {

    /**
     * The positions {@code PA-RL-CARD-NUM PIC X(16)} declares at {@code CCPAURLY.cpy} line 19.
     */
    private static final int CARD_NUMBER_WIDTH = 16;

    /**
     * The positions {@code PA-RL-TRANSACTION-ID PIC X(15)} declares at {@code CCPAURLY.cpy} line 20.
     */
    private static final int TRANSACTION_ID_WIDTH = 15;

    /**
     * The positions {@code PA-RL-AUTH-ID-CODE PIC X(06)} declares at {@code CCPAURLY.cpy} line 21.
     */
    private static final int AUTH_ID_CODE_WIDTH = 6;

    /**
     * The positions {@code PA-RL-AUTH-RESP-CODE PIC X(02)} declares at {@code CCPAURLY.cpy} line 22.
     */
    private static final int AUTH_RESPONSE_CODE_WIDTH = 2;

    /**
     * The positions {@code PA-RL-AUTH-RESP-REASON PIC X(04)} declares at {@code CCPAURLY.cpy}
     * line 23.
     */
    private static final int AUTH_RESPONSE_REASON_WIDTH = 4;

    /**
     * The two response codes the reference program emits, {@code '00'} and {@code '05'}.
     *
     * <p>Assumptions: this domain is genuinely CLOSED, which is what makes constraining it correct.
     * The decision paragraph at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 688
     * and 693 moves one of exactly two literals into this field and there is no third path, so a value
     * outside the pair could only come from a defect in this service.</p>
     */
    private static final String RESPONSE_CODE_DOMAIN = "(?:00|05)";

    /**
     * The eight reasons the reference program emits, one on approval and seven on decline.
     *
     * <p>Assumptions: this domain is closed for the same reason the code domain is -- the selection at
     * lines 698 through 716 moves one of exactly eight literals and has no other branch. It is
     * expressed here as a compile-time pattern because a Jakarta constraint takes only constants,
     * while the authoritative table is
     * {@code com.carddemo.authorization.service.AuthorizationDecisionService.DeclineReason} plus that
     * class's approved reason. A contract test asserts the two agree member for member, so the
     * duplication cannot drift without failing a build.</p>
     */
    private static final String RESPONSE_REASON_DOMAIN =
            "(?:0000|3100|4100|4200|4300|5100|5200|9000)";

    /**
     * Renders this reply for a log or a diagnostic with the primary account number masked.
     *
     * <p>Refactoring Rationale: this is the reply counterpart of the override on
     * {@link AuthorizationRequestPayload}, and it is needed for the same reason and one more. The
     * same reason: this record's first component is a sixteen-digit primary account number -- the
     * constraint on it admits nothing shorter and nothing but digits -- so the rendering a record
     * generates for itself names it verbatim, and a payload interpolated into a log statement, an
     * assertion message or a publisher's own failure trace produces that rendering without anyone
     * writing it. The one more: a reply is produced on the failure path as often as on the success
     * path. It is assembled, committed to the outbox and published, and every one of those steps can
     * fail with the payload in hand, so this rendering is MORE likely to be logged than the
     * request's is.</p>
     *
     * <p>Assumptions: masking the rendering does not touch the wire. The published form is produced
     * by {@code com.carddemo.common.codec.CsvAuthCodec} from the components directly, and the
     * baseline's consumer reads position 1 of that CSV as the sixteen characters
     * {@code PA-RL-CARD-NUM} declares, so the full value still travels. Masking here would break
     * that contract if the two were the same code path; they are not, and this rendering is
     * parsed by nothing.</p>
     *
     * <p>Trade-offs: the decision components are rendered in full -- the response code, the reason
     * and the approved amount -- because they are the reply's whole substance and the reason a
     * reply is ever examined. None of them identifies a cardholder.</p>
     *
     * @return a single-line rendering naming this type and all six components, with the card number
     *     reduced to a mask and its last four digits; the component is labelled as masked so that no
     *     reader mistakes it for the value the wire carries
     */
    @Override
    public String toString() {
        return "AuthorizationReplyPayload[maskedCardNumber=" + CardNumberMasker.mask(cardNumber)
                + ", transactionId=" + transactionId
                + ", authIdCode=" + authIdCode
                + ", authResponseCode=" + authResponseCode
                + ", authResponseReason=" + authResponseReason
                + ", approvedAmount=" + approvedAmount + ']';
    }
}
