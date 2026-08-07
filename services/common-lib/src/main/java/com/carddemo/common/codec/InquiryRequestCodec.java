package com.carddemo.common.codec;

import java.util.Objects;

/**
 * The fixed-width request and reply framing shared by the two request/reply inquiry flows.
 *
 * <h2>Purpose</h2>
 * <p>Both migrated inquiry flows exchange a fixed one-thousand-character message, and both declare the request
 * with the same three fields. The layout is taken from the two reference programs, which declare it
 * identically:</p>
 *
 * <pre>
 * 01 REQUEST-MSG-COPY.
 *    10 WS-FUNC     PIC X(04) VALUE SPACES.
 *    10 WS-KEY      PIC 9(11) VALUE ZEROES.
 *    10 WS-FILLER   PIC X(985) VALUE SPACES.
 * </pre>
 *
 * <p>at {@code app/app-vsam-mq/cbl/COACCT01.cbl} physical lines 111 to 113 and at
 * {@code app/app-vsam-mq/cbl/CODATE01.cbl} physical lines 110 to 112. Both programs declare a
 * {@code PIC X(1000)} buffer and both put and get exactly one thousand characters. Those files are
 * REFERENCE-ONLY: they are read as the specification for this class and are never modified.</p>
 *
 * <h2>Why one class serves both flows</h2>
 * <p>Assumptions: the layout is byte-identical in the two programs, so it is transcribed once. Transformation
 * rule T2 of the migration plan places a shared wire contract in the shared kernel for exactly this reason --
 * two transcriptions of one layout are two chances to disagree about an offset, and an offset disagreement
 * between a producer and a consumer presents as a field that is silently one character short rather than as an
 * error. The REPLIES differ per flow and are therefore NOT modelled here; each context builds its own reply
 * body and hands it to {@link #frame(String)} for the common padding.</p>
 *
 * <h2>What this class deliberately does not decide</h2>
 * <p>Assumptions: this codec is purely structural. It splits the three fields and it does not judge them.
 * Whether a function code is the one a flow serves, and whether a key is a usable positive number, are
 * DOMAIN questions that the reference programs answer in their own procedure divisions -- COACCT01 with
 * {@code IF WS-FUNC = 'INQA' AND WS-KEY > ZEROES} at physical line 342, and CODATE01 with no test at all,
 * replying to any message with the system date and time. Encoding either answer here would impose one flow's
 * rule on the other.</p>
 *
 * <p>Alternatives Considered: parsing the key into a {@code long} inside the codec and refusing a non-numeric
 * one as a format error. Rejected on fidelity grounds, and the distinction matters operationally. A request
 * whose key is not numeric fails COACCT01's test and receives its {@code INVALID REQUEST PARAMETERS} reply --
 * the requester gets an answer. Refusing it here would instead raise, and a raised exception on this path
 * returns the message to the queue and eventually to the dead-letter queue, leaving a requester that the
 * baseline would have answered waiting forever. The key is therefore carried as its raw characters and the
 * numeric test is offered as a query the caller may ask.</p>
 *
 * <p>This class holds no state and cannot be instantiated.</p>
 */
public final class InquiryRequestCodec {

    /**
     * The exact character length of both the request and the reply message.
     *
     * <p>Assumptions: one thousand is not a buffer size that happens to be large enough. Both programs move a
     * {@code PIC X(1000)} buffer and both pass a literal length of 1000 to their get and put calls, so the
     * length is part of the contract a producer and a consumer must agree on.</p>
     */
    public static final int MESSAGE_LENGTH = 1000;

    /**
     * The width of the function code, from {@code WS-FUNC PIC X(04)}.
     */
    public static final int FUNCTION_WIDTH = 4;

    /**
     * The width of the key, from {@code WS-KEY PIC 9(11)}.
     */
    public static final int KEY_WIDTH = 11;

    /**
     * The width of the trailing filler, from {@code WS-FILLER PIC X(985)}.
     *
     * <p>Assumptions: declared even though nothing reads the filler, because the three widths must sum to
     * {@link #MESSAGE_LENGTH} and stating all three is what lets that be asserted rather than assumed.</p>
     */
    public static final int FILLER_WIDTH = 985;

    /**
     * The function code the account-inquiry flow serves, from {@code COACCT01.cbl} physical line 342.
     */
    public static final String FUNCTION_ACCOUNT_INQUIRY = "INQA";

    /**
     * The character both the request filler and the reply padding are filled with.
     */
    private static final char PAD = ' ';

    /**
     * Prevents instantiation of this utility holder.
     *
     * @throws AssertionError always, so an accidental reflective construction fails loudly
     */
    private InquiryRequestCodec() {
        throw new AssertionError("InquiryRequestCodec is a utility holder and must not be instantiated");
    }

    /**
     * One decoded inquiry request.
     *
     * <p>Assumptions: the key is a {@code String} of exactly {@link #KEY_WIDTH} characters rather than a
     * number, for the reason recorded on the enclosing class. {@link #hasUsableKey()} is the numeric test the
     * reference program applies, offered as a query so the caller decides what to do about a key that fails
     * it.</p>
     *
     * @param function the four-character function code, exactly {@link #FUNCTION_WIDTH} characters, never
     *     {@code null}
     * @param key the eleven-character key as it arrived, exactly {@link #KEY_WIDTH} characters, never
     *     {@code null}
     */
    public record InquiryRequest(String function, String key) {

        /**
         * Creates a decoded request, refusing a shape the codec could not have produced.
         *
         * @param function the four-character function code; must not be {@code null}
         * @param key the eleven-character key as it arrived; must not be {@code null}
         * @throws NullPointerException if either component is {@code null}
         * @throws IllegalArgumentException if either component is not at its declared width, which would mean
         *     a caller constructed this record by hand and got an offset wrong
         */
        public InquiryRequest {
            Objects.requireNonNull(function, "function must not be null");
            Objects.requireNonNull(key, "key must not be null");
            if (function.length() != FUNCTION_WIDTH) {
                throw new IllegalArgumentException("function must be exactly " + FUNCTION_WIDTH
                        + " characters, matching WS-FUNC PIC X(04); received " + function.length());
            }
            if (key.length() != KEY_WIDTH) {
                throw new IllegalArgumentException("key must be exactly " + KEY_WIDTH
                        + " characters, matching WS-KEY PIC 9(11); received " + key.length());
            }
        }

        /**
         * Reports the function code with trailing padding removed.
         *
         * <p>Assumptions: only TRAILING padding is stripped. A leading space is significant: it means the
         * producer did not left-justify the code, so {@code " INQ"} is not {@code "INQ "} and must not be
         * made equal to it by trimming both ends.</p>
         *
         * @return the function code without trailing spaces, never {@code null}
         */
        public String trimmedFunction() {
            int end = this.function.length();
            while (end > 0 && this.function.charAt(end - 1) == PAD) {
                end--;
            }
            return this.function.substring(0, end);
        }

        /**
         * Reports whether this request names the given function.
         *
         * @param candidate the function code to compare against; must not be {@code null}
         * @return {@code true} when the trimmed function code equals the candidate exactly, case sensitively
         */
        public boolean isFunction(String candidate) {
            // WHY : Assumptions: the comparison is case SENSITIVE. The reference test is a COBOL literal
            //   comparison against 'INQA', which is case sensitive, and accepting a lower-case variant here
            //   would answer a request the baseline refused.
            return this.trimmedFunction().equals(Objects.requireNonNull(candidate, "candidate"));
        }

        /**
         * Reports whether the key is a usable positive number, which is the reference program's own test.
         *
         * <p>Assumptions: this is the target form of {@code WS-KEY > ZEROES}. It additionally requires every
         * character to be an ASCII digit, because the reference field is declared {@code PIC 9(11)} and the
         * comparison's result on non-numeric content is not defined by the standard -- so refusing to treat
         * such content as a number is the only reading that is the same on every implementation. The
         * consequence is a reply of {@code INVALID REQUEST PARAMETERS}, which is what the baseline sends when
         * its test fails, rather than an exception.</p>
         *
         * @return {@code true} when every character is an ASCII digit and the value is greater than zero
         */
        public boolean hasUsableKey() {
            boolean nonZero = false;
            for (int index = 0; index < this.key.length(); index++) {
                char character = this.key.charAt(index);
                if (character < '0' || character > '9') {
                    return false;
                }
                if (character != '0') {
                    nonZero = true;
                }
            }
            return nonZero;
        }

        /**
         * Reports the key as a number.
         *
         * @return the numeric key
         * @throws IllegalStateException if the key is not a usable positive number, so a caller cannot read a
         *     number out of content this record has already reported as unusable
         */
        public long keyValue() {
            if (!this.hasUsableKey()) {
                throw new IllegalStateException(
                        "the key is not a usable positive number; call hasUsableKey() before keyValue()");
            }
            return Long.parseLong(this.key);
        }

        /**
         * Renders this request for a diagnostic without disclosing the filler.
         *
         * <p>Assumptions: the filler is 985 characters this flow never reads, and a producer may put anything
         * in it. Rendering it would place unexamined wire content into a log line, so only the two fields
         * this flow acts on are rendered -- and neither is cardholder data: a function code is a literal and
         * the key is an internal account identifier.</p>
         *
         * @return the diagnostic rendering, never {@code null}
         */
        @Override
        public String toString() {
            return "InquiryRequest[function=" + this.trimmedFunction() + ", key=" + this.key + "]";
        }
    }

    /**
     * Decodes a received request message.
     *
     * <p>Assumptions: a payload shorter than {@link #MESSAGE_LENGTH} is padded and a longer one is truncated,
     * rather than either being refused. That is what the reference program does and it is not an act of
     * leniency: {@code MOVE REQUEST-MESSAGE TO REQUEST-MSG-COPY} at {@code COACCT01.cbl} physical line 322 is
     * a group move, which space-pads a shorter source and truncates a longer one. Refusing a short payload
     * here would dead-letter a message the baseline answered.</p>
     *
     * @param payload the received message body; must not be {@code null}
     * @return the decoded request, never {@code null}
     * @throws NullPointerException if {@code payload} is {@code null}, which is not a wire condition but a
     *     caller passing nothing
     */
    public static InquiryRequest decode(String payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        String framed = frame(payload, true);
        return new InquiryRequest(
                framed.substring(0, FUNCTION_WIDTH),
                framed.substring(FUNCTION_WIDTH, FUNCTION_WIDTH + KEY_WIDTH));
    }

    /**
     * Frames a reply body to the exact message length by padding it with spaces.
     *
     * <p>Assumptions: padding is what the reference program does. It moves a shorter response group into
     * {@code REPLY-MESSAGE PIC X(1000)} and then a literal 1000 into the buffer length, so every reply on the
     * wire is one thousand characters regardless of how much of it carries data.</p>
     *
     * @param body the reply body; must not be {@code null}
     * @return the body padded with spaces to exactly {@link #MESSAGE_LENGTH} characters, never {@code null}
     * @throws NullPointerException if {@code body} is {@code null}
     * @throws IllegalArgumentException if the body is longer than {@link #MESSAGE_LENGTH}, which is a defect
     *     in the caller's own formatting rather than a wire condition and must not be silently truncated
     */
    public static String frame(String body) {
        return frame(body, false);
    }

    /**
     * Frames a value to the exact message length.
     *
     * @param value the value to frame; must not be {@code null}
     * @param truncate whether an over-long value is truncated rather than refused
     * @return the framed value, exactly {@link #MESSAGE_LENGTH} characters, never {@code null}
     * @throws IllegalArgumentException if the value is over-long and {@code truncate} is {@code false}
     */
    private static String frame(String value, boolean truncate) {
        if (value.length() == MESSAGE_LENGTH) {
            return value;
        }
        if (value.length() > MESSAGE_LENGTH) {
            if (truncate) {
                return value.substring(0, MESSAGE_LENGTH);
            }
            throw new IllegalArgumentException("a reply body of " + value.length()
                    + " characters exceeds the " + MESSAGE_LENGTH
                    + "-character message length; truncating it would silently drop reply content");
        }
        StringBuilder padded = new StringBuilder(MESSAGE_LENGTH).append(value);
        while (padded.length() < MESSAGE_LENGTH) {
            padded.append(PAD);
        }
        return padded.toString();
    }
}
