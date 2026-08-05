package com.carddemo.authorization.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.carddemo.common.money.Money;

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
 * @param cardNumber the 16-digit value from {@code PA-RL-CARD-NUM} at
 *     {@code CCPAURLY.cpy} line 19, occupying CSV position 1 and echoing the request value
 *     assigned at {@code COPAUA0C.cbl} line 660
 * @param transactionId the 15-digit value from {@code PA-RL-TRANSACTION-ID} at
 *     {@code CCPAURLY.cpy} line 20, occupying CSV position 2 and echoing the request value
 *     assigned at {@code COPAUA0C.cbl} line 661
 * @param authIdCode the 6-digit value from {@code PA-RL-AUTH-ID-CODE} at
 *     {@code CCPAURLY.cpy} line 21 and CSV position 3, carrying the request authorization
 *     time assigned at {@code COPAUA0C.cbl} line 662 rather than a newly generated identifier
 * @param authResponseCode the 2-digit decision code from {@code PA-RL-AUTH-RESP-CODE} at
 *     {@code CCPAURLY.cpy} line 22 and CSV position 4; {@code 00} denotes approval and
 *     {@code 05} denotes decline at {@code COPAUA0C.cbl} lines 693 and 688
 * @param authResponseReason the 4-digit reason from {@code PA-RL-AUTH-RESP-REASON} at
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
        @Size(min = 16, max = 16) @Pattern(regexp = "[0-9]{16}") String cardNumber,
        @Size(min = 15, max = 15) @Pattern(regexp = "[0-9]{15}") String transactionId,
        @Size(min = 6, max = 6) @Pattern(regexp = "[0-9]{6}") String authIdCode,
        @Size(min = 2, max = 2) @Pattern(regexp = "(?:00|05)") String authResponseCode,
        @Size(min = 4, max = 4)
        @Pattern(regexp = "(?:0000|3100|4100|4200|4300|5100|5200|9000)")
        String authResponseReason,
        Money approvedAmount) {
}
