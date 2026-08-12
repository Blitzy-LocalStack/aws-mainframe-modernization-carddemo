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
     * The classification reported for a request whose function field carries nothing.
     *
     * <p>Assumptions: an absent function is distinguished from an unrecognised one because they have
     * different causes -- a producer that never filled the field against a producer that filled it with
     * something this system does not serve -- and an operator diagnosing one is not looking for the
     * other.</p>
     */
    public static final String FUNCTION_LABEL_BLANK = "blank";

    /**
     * The classification reported for a request whose function field is not one this system serves.
     *
     * <p>Assumptions: a fixed token and NOT the value itself. The field is four characters of wire content
     * that no producer contract constrains, so it can carry a line terminator, a field delimiter or a
     * forged event prefix; a journal line that echoed it would let a producer write its own log records.
     * That is CWE-117, and the defence has to be that the value never reaches the line at all rather than
     * that it is escaped on the way -- an escape is a property of one call site and can be omitted at the
     * next.</p>
     */
    public static final String FUNCTION_LABEL_UNRECOGNISED = "unrecognised";

    // Assumptions: the six widths below are the declared widths of the diagnostic group both reference
    //     programs carry -- CODATE01.cbl physical lines 58 to 67 and COACCT01.cbl physical lines 58 to 67
    //     declare the same nine members: a 25-character paragraph name, a gap, a 25-character return
    //     message, a gap, a 2-digit condition code, a gap, a 5-digit reason code, a gap and a 48-character
    //     queue name. They matter because a consumer of the error sink locates every value by OFFSET, so
    //     each width is part of the wire contract and not a formatting preference: changing one shifts
    //     every following field to a position no existing reader expects.
    // Assumptions: they live HERE rather than in either consumer because the two reference programs
    //     declare one identical group, and this class is already the single source of the request layout
    //     and of the 1000-character framing the diagnostic is padded to. A per-consumer copy is the exact
    //     failure mode this package exists to prevent, and it is not hypothetical on this contract: the two
    //     inquiry consumers had already drifted apart on the reply's content-type attribute while each
    //     documented its own value as the shared one.
    /**
     * Declared width of the diagnostic's reporting-paragraph field.
     */
    public static final int DIAGNOSTIC_PARAGRAPH_WIDTH = 25;

    /**
     * Declared width of the diagnostic's return-message field.
     */
    public static final int DIAGNOSTIC_MESSAGE_WIDTH = 25;

    /**
     * Declared width of each gap between diagnostic fields.
     */
    public static final int DIAGNOSTIC_GAP_WIDTH = 2;

    /**
     * Declared width of the diagnostic's condition-code field.
     */
    public static final int DIAGNOSTIC_CONDITION_CODE_WIDTH = 2;

    /**
     * Declared width of the diagnostic's reason-code field.
     */
    public static final int DIAGNOSTIC_REASON_CODE_WIDTH = 5;

    /**
     * Declared width of the diagnostic's queue-name field.
     */
    public static final int DIAGNOSTIC_QUEUE_NAME_WIDTH = 48;

    /**
     * The combined width of the diagnostic's positional prefix.
     *
     * <p>Assumptions: SUMMED from the field widths above rather than written as a number, so the prefix
     * length and the fields that make it up cannot drift apart.</p>
     */
    public static final int DIAGNOSTIC_PREFIX_LENGTH =
            DIAGNOSTIC_PARAGRAPH_WIDTH + DIAGNOSTIC_GAP_WIDTH
            + DIAGNOSTIC_MESSAGE_WIDTH + DIAGNOSTIC_GAP_WIDTH
            + DIAGNOSTIC_CONDITION_CODE_WIDTH + DIAGNOSTIC_GAP_WIDTH
            + DIAGNOSTIC_REASON_CODE_WIDTH + DIAGNOSTIC_GAP_WIDTH
            + DIAGNOSTIC_QUEUE_NAME_WIDTH;

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
         * Classifies the function field into one of a closed set of tokens safe to journal.
         *
         * <p>Purpose: a consumer wants to report WHICH kind of request arrived, and the field it would
         * naturally report is four characters of unconstrained wire content. This method answers the
         * question without echoing the content: the result is always one of {@link
         * InquiryRequestCodec#FUNCTION_ACCOUNT_INQUIRY}, {@link InquiryRequestCodec#FUNCTION_LABEL_BLANK}
         * or {@link InquiryRequestCodec#FUNCTION_LABEL_UNRECOGNISED}, all three of which are compile-time
         * literals declared in this class.</p>
         *
         * <p>Alternatives Considered: sanitising the value at each log statement instead, by stripping
         * control characters. Rejected on two grounds. It leaves the value itself in the record, so a
         * producer still chooses what appears there and only its punctuation is constrained; and it makes
         * the protection a property of every individual call site, so the next log line added is unprotected
         * by default. A closed classification inverts that default -- the raw field is simply not available
         * to a journal line unless a caller reaches past this method for it.</p>
         *
         * <p>Trade-offs: an operator diagnosing a producer that sends a wrong function code learns that it
         * was wrong and not what it was. That is accepted because the two flows behave identically for every
         * unrecognised value -- the account inquiry refuses them all with one message and the date inquiry
         * answers them all alike -- so the specific bytes change no outcome, and a producer's own logs hold
         * what it sent.</p>
         *
         * @return one of the three closed classification tokens, never {@code null}
         */
        public String functionLabel() {
            if (this.isFunction(FUNCTION_ACCOUNT_INQUIRY)) {
                return FUNCTION_ACCOUNT_INQUIRY;
            }
            return this.trimmedFunction().isEmpty() ? FUNCTION_LABEL_BLANK : FUNCTION_LABEL_UNRECOGNISED;
        }

        /**
         * Renders this request for a diagnostic without disclosing either field's content.
         *
         * <p>Assumptions: the filler is 985 characters this flow never reads and a producer may put anything
         * in it, so it is never rendered. Neither is the function field, whose four characters are equally
         * unconstrained -- the classification stands in for it.</p>
         *
         * <p>Refactoring Rationale: this rendering carried the trimmed function code and the key verbatim,
         * on the stated ground that "neither is cardholder data: a function code is a literal and the key is
         * an internal account identifier". Both halves were wrong. The function code is a literal only when
         * the producer sent the literal, and an arbitrary four characters reaching a journal line through a
         * record's own rendering is CWE-117 by the shortest possible route. And the observability contract
         * requires an account identifier to be OMITTED from a durable field rather than shortened, because
         * the value's sensitivity does not depend on whether the system calls it internal. What a reader
         * needs from a diagnostic is which kind of request it was and whether the key was usable, and both
         * survive here.</p>
         *
         * @return the diagnostic rendering, never {@code null}
         */
        @Override
        public String toString() {
            return "InquiryRequest[function=" + this.functionLabel()
                    + ", keyUsable=" + this.hasUsableKey() + "]";
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

    /**
     * Composes the diagnostic buffer in the positional shape both reference programs report failures through.
     *
     * <p>Purpose: this is the target form of the {@code 9000-ERROR} buffer -- {@code CODATE01.cbl} physical
     * lines 405 to 425 and {@code COACCT01.cbl} physical line 501 both move the same nine-member diagnostic
     * group into the message buffer, set the buffer length to 1000 and put to a separate error queue. The
     * result is NOT framed here: a caller frames it with {@link #frame(String)} so that one framing rule
     * serves the reply and the diagnostic alike.</p>
     *
     * <p>Assumptions: each field is left-justified, space-padded and truncated at its declared width, which
     * is what a group move into a fixed picture does -- and it is why a return message longer than
     * {@link #DIAGNOSTIC_MESSAGE_WIDTH} arrives as its leading characters rather than widening the field.</p>
     *
     * <p>Alternatives Considered: omitting the condition-code and reason-code intervals, which hold
     * queue-manager values that have no counterpart in the target and are therefore unfillable. Rejected
     * because omitting them shortens the prefix by nine characters and shifts the queue name behind them to
     * an offset no reader of that sink expects. They are preserved and left blank instead, which keeps every
     * other field where the baseline puts it and states the absence in the buffer itself.</p>
     *
     * <p>Trade-offs: the failure detail is appended AFTER the positional prefix, in space the baseline
     * leaves blank, and it is truncated to what remains of the message length. Folding it into the
     * return-message field instead would displace the baseline's own literal, so the choice is between
     * losing the literal and using blank space; the blank space costs nothing a reader relies on. It is
     * truncated rather than refused because {@link #frame(String)} refuses an over-long body, which is the
     * right default for a reply a consumer decodes by offset and the wrong one here -- a long failure
     * rendering would turn a report about one fault into a second, unrelated fault on the reporting path.</p>
     *
     * @param paragraph the reporting paragraph's name, or {@code null} to leave the field blank
     * @param returnMessage the baseline's verbatim return message for this failure, or {@code null} to leave
     *     the field blank
     * @param queueName the configured queue name the failure concerns, or {@code null} to leave the field
     *     blank
     * @param detail the failure rendering appended after the positional prefix, or {@code null} to append
     *     nothing; it must name no value that came off the wire
     * @return the composed buffer, no longer than {@link #MESSAGE_LENGTH}, never {@code null}
     */
    public static String errorDiagnostic(String paragraph, String returnMessage, String queueName,
            String detail) {

        String prefix = fixed(paragraph, DIAGNOSTIC_PARAGRAPH_WIDTH)
                + blanks(DIAGNOSTIC_GAP_WIDTH)
                + fixed(returnMessage, DIAGNOSTIC_MESSAGE_WIDTH)
                + blanks(DIAGNOSTIC_GAP_WIDTH)
                + blanks(DIAGNOSTIC_CONDITION_CODE_WIDTH)
                + blanks(DIAGNOSTIC_GAP_WIDTH)
                + blanks(DIAGNOSTIC_REASON_CODE_WIDTH)
                + blanks(DIAGNOSTIC_GAP_WIDTH)
                + fixed(queueName, DIAGNOSTIC_QUEUE_NAME_WIDTH);

        String tail = detail == null ? "" : detail;
        int room = MESSAGE_LENGTH - DIAGNOSTIC_PREFIX_LENGTH;
        return prefix + fixed(tail, Math.min(tail.length(), room));
    }

    /**
     * Renders a value left-justified in a fixed width, space-padded and truncated as a group move would.
     *
     * @param value the value, or {@code null} for an all-blank field
     * @param width the declared field width
     * @return the rendered field, exactly {@code width} characters, never {@code null}
     */
    private static String fixed(String value, int width) {
        String text = value == null ? "" : value;
        return text.length() >= width ? text.substring(0, width) : text + blanks(width - text.length());
    }

    /**
     * Renders a run of spaces.
     *
     * @param width how many spaces
     * @return the run, never {@code null}
     */
    private static String blanks(int width) {
        return String.valueOf(PAD).repeat(width);
    }
}
