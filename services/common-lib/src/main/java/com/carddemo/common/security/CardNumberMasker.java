package com.carddemo.common.security;

/**
 * Renders a primary account number, or any text that may contain one, with everything but its last four
 * digits replaced by a mask character.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>The migration plan requires the primary account number to be masked to its last four digits
 * everywhere except one administrative read, at its sections 0.4.1.9 and 0.7.8. That obligation reaches
 * more places than a mapping layer covers. A card number reaches a response body through a mapper, which
 * is where the plan places the decision; but it also reaches a diagnostic rendering of a transfer object,
 * a log line naming the request that failed, and the path member of an error body. Each of those is a
 * separate site, and before this class each site that masked at all did so with its own arithmetic.</p>
 *
 * <p>Refactoring Rationale: one implementation replaces several. A masking rule written out at each site
 * is a rule that can disagree with itself, and it had already begun to: one site rendered a masked number
 * as a fixed four-character prefix followed by the tail, which is shorter than the number it stands for,
 * while the published contract renders it at the number's own width. Two renderings of one value are worse
 * than either alone, because a reader cannot tell whether a short rendering means a masked number or a
 * truncated one. This class fixes the rendering once, at the contract's width, and every site delegates.</p>
 *
 * <h2>Two operations, for two different kinds of input</h2>
 *
 * <p><strong>A known card number</strong> is masked by {@link #mask(String)}. The caller already knows the
 * value is a card number, so every character but the last four is replaced.</p>
 *
 * <p><strong>Text that may contain one</strong> is masked by {@link #maskEmbeddedCardNumbers(String)}. The
 * caller does not know whether the text contains a card number and cannot know: a request path is
 * assembled by whoever sent the request, so a caller that invents a path this service publishes no route
 * for still has its path echoed back in the refusal and written to the log lines that accompany it.</p>
 *
 * <h2>Assumptions and boundaries</h2>
 *
 * <p>Assumptions: a run of sixteen or more digits is what
 * {@link #maskEmbeddedCardNumbers(String)} treats as a card number, and shorter runs are deliberately left
 * alone. Sixteen is the width the whole baseline uses -- {@code CARD-NUM PIC X(16)} at line 5 of
 * {@code app/cpy/CVACT02Y.cpy} -- and the two other identifiers that appear in a request path are narrower:
 * an account identifier is eleven digits and a transaction identifier is fifteen characters. Masking every
 * digit run regardless of length would mask those two as well, and neither is a value the plan masks; the
 * published contracts render an account identifier in full. Runs longer than sixteen are masked too,
 * because a longer run contains a card-number-shaped value and nothing legitimate in a path is that long.</p>
 *
 * <p>Trade-offs: the digit-run rule is positional and not a check-digit test. A sixteen-digit run that is
 * not a valid card number is masked anyway, which loses a little diagnostic detail on a malformed request.
 * Validating the digits first was rejected for two reasons: it would put a card-number validity rule in the
 * one class whose whole purpose is to avoid handling card numbers, and it fails in the wrong direction --
 * a real number that failed the test would be echoed in full.</p>
 *
 * <p>Assumptions: a run is a run of digits and nothing else, so a card number written with separators is
 * NOT detected. {@code 4111-1111-1111-1111} and {@code 4111 1111 1111 1111} are each four runs of four
 * digits, no run reaches sixteen, and {@link #maskEmbeddedCardNumbers(String)} returns them unchanged. This
 * is stated explicitly because the omission is invisible from the method's own contract, and a reader who
 * assumes otherwise would apply this class to the wrong kind of input. It is harmless for every path this
 * migration actually masks: the baseline carries a card number in exactly one shape, the sixteen contiguous
 * characters of {@code CARD-NUM PIC X(16)} at line 5 of {@code app/cpy/CVACT02Y.cpy}, and every value that
 * reaches this class comes from that field, from a request path segment bound to it, or from the
 * {@code PA-RQ-CARD-NUM PIC X(16)} of the authorization wire contract. None of the three can hold a
 * separator: the copybook field has no room for one within its declared width, and a separated form fails
 * the digits-only validation on the way in long before anything renders it.</p>
 *
 * <p>Trade-offs: widening the rule to skip embedded separators was rejected for this migration. It would
 * begin masking values that are legitimately separated and not card numbers at all -- a timestamp
 * {@code 2022-07-18 09:30:00.123456} is a separated digit sequence of more than sixteen digits, and the
 * migration renders those in full by contract -- so the change would trade a hazard this system cannot
 * reach for a regression it demonstrably can. The condition under which the wider rule becomes necessary is
 * a free-text ingress: a comment, a note or an operator-supplied description that a user can type a card
 * number into in whatever shape they please. No such field exists in the baseline record layouts. Should one
 * be added, the correct change is a separator-tolerant scan that requires the separators to be uniform and
 * the digit count to be exactly sixteen or more, ADDED beside the run rule rather than replacing it, so the
 * contiguous case keeps the behaviour the current callers and their tests rely on.</p>
 *
 * <p>Assumptions: this class does not sanitise control characters, and does not need to for the paths it is
 * applied to. A request line cannot carry a raw carriage return or line feed and remain a request line, so
 * a servlet container never presents one in a request target; a client that tries must percent-encode it,
 * and the encoded form is inert text. Where control characters CAN arrive -- a value read from a record, a
 * message a caller supplied in a body -- sanitising them is the responsibility of the site that reads them,
 * and {@code com.carddemo.common.error.AbendDetail} carries that logic for the error path.</p>
 *
 * <p>Assumptions: this class does not decide WHICH members are masked, and holds no list of field names.
 * That knowledge belongs to each context's mapping layer, exactly as it does for
 * {@link OpaqueIdentifier}, and duplicating it here would create a second place for it to go stale.</p>
 */
public final class CardNumberMasker {

    /**
     * The number of trailing characters left visible.
     *
     * <p>Assumptions: four, which is the migration plan's own figure at its sections 0.4.1.9 and 0.7.8 and
     * the figure the published card contract renders. It is declared rather than written into the
     * arithmetic so that a reader can see the rule and a caller can assert against it.</p>
     */
    public static final int VISIBLE_TAIL_LENGTH = 4;

    /**
     * The character every masked position is rendered as.
     *
     * <p>Assumptions: an asterisk, matching the masked renderings the published contracts carry as examples.
     * A rendering that varied between sites would make a masked value impossible to recognise as one.</p>
     */
    public static final char MASK_CHARACTER = '*';

    /**
     * The shortest digit run {@link #maskEmbeddedCardNumbers(String)} treats as a card number.
     *
     * <p>Assumptions: sixteen, the declared width of the baseline's card number. The rationale for choosing
     * the exact width rather than a shorter threshold is on the class.</p>
     */
    private static final int CARD_NUMBER_LENGTH = 16;

    /**
     * Prevents instantiation of this utility holder.
     *
     * <p>Assumptions: both operations are stateless functions of their argument, so an instance would carry
     * nothing and exist only to be passed around. A private constructor states that, where an implicit
     * public one would invite a caller to inject this class as a collaborator and then to mock it.</p>
     *
     * @throws AssertionError always, so that reflective instantiation fails as loudly as direct
     *     instantiation is prevented
     */
    private CardNumberMasker() {
        throw new AssertionError("CardNumberMasker is a utility holder and is never instantiated");
    }

    /**
     * Masks a value the caller already knows to be a card number.
     *
     * <p>Assumptions: the rendering keeps the input's own width, so a sixteen-digit number becomes twelve
     * mask characters followed by four digits. Preserving the width is what makes the masked form line up in
     * a column with an unmasked one and matches the example the published card contract carries; a fixed
     * short prefix would not.</p>
     *
     * @param cardNumber the value to mask, which may be {@code null}
     * @return {@code null} when {@code cardNumber} is {@code null}; otherwise a string of the same length
     *     whose final four characters are those of the input and whose earlier characters are all
     *     {@link #MASK_CHARACTER}. A value of four characters or fewer is returned entirely masked, so that
     *     a short value cannot be disclosed in full by a rule written for a long one
     */
    public static String mask(String cardNumber) {
        if (cardNumber == null) {
            return null;
        }
        // WHY : Assumptions: a value at or below the visible-tail length is masked ENTIRELY rather than
        //       returned as itself. Taking a tail with a bounded start index -- rather than subtracting
        //       four from the length -- is what keeps a short value from becoming its own output, and a
        //       short value is reachable here: this is applied to a diagnostic rendering as well as to a
        //       validated field, and a diagnostic rendering runs before validation has rejected anything.
        int length = cardNumber.length();
        if (length <= VISIBLE_TAIL_LENGTH) {
            return String.valueOf(MASK_CHARACTER).repeat(length);
        }
        return String.valueOf(MASK_CHARACTER).repeat(length - VISIBLE_TAIL_LENGTH)
                + cardNumber.substring(length - VISIBLE_TAIL_LENGTH);
    }

    /**
     * Masks every card-number-shaped digit run inside arbitrary text.
     *
     * <p>Assumptions: the text is scanned for maximal runs of digits and a run of at least sixteen digits is
     * replaced by its masked rendering, leaving every other character exactly as it arrived. Everything else
     * is preserved because the caller needs the surrounding text: a masked path is only useful for diagnosis
     * if the route it names survives the masking.</p>
     *
     * <p>Trade-offs: the common case does no work and allocates nothing. The scan returns the argument
     * itself when it finds no qualifying run, which is every well-formed request this system publishes a
     * route for, so the cost of applying this on an error path is a single pass over a short string.</p>
     *
     * @param text the text to scan, which may be {@code null}
     * @return {@code null} when {@code text} is {@code null}; the same instance when it contains no run of
     *     {@value #CARD_NUMBER_LENGTH} or more digits; otherwise a copy in which every such run is masked
     *     to its last four digits
     */
    public static String maskEmbeddedCardNumbers(String text) {
        if (text == null) {
            return null;
        }

        StringBuilder masked = null;
        int index = 0;
        int length = text.length();

        while (index < length) {
            if (!isDigit(text.charAt(index))) {
                index++;
                continue;
            }

            int runEnd = index;
            while (runEnd < length && isDigit(text.charAt(runEnd))) {
                runEnd++;
            }

            if (runEnd - index >= CARD_NUMBER_LENGTH) {
                // WHY : Refactoring Rationale: the builder is created on first match rather than up front,
                //       so text with nothing to mask is returned unchanged and uncopied. This method sits on
                //       an error path that also feeds every log statement of the shared advice, so it runs
                //       on every failed request; making the no-match case allocation-free keeps a diagnostic
                //       measure from becoming a cost on the failure path it exists to make safe.
                if (masked == null) {
                    masked = new StringBuilder(text);
                }
                int maskUntil = runEnd - VISIBLE_TAIL_LENGTH;
                for (int position = index; position < maskUntil; position++) {
                    masked.setCharAt(position, MASK_CHARACTER);
                }
            }

            index = runEnd;
        }

        return masked == null ? text : masked.toString();
    }

    /**
     * Reports whether a character is one of the ten ASCII digits.
     *
     * @param candidate the character to test
     * @return {@code true} when {@code candidate} is an ASCII digit
     */
    private static boolean isDigit(char candidate) {
        // WHY : Alternatives Considered: Character.isDigit was rejected. It answers true for the decimal
        //       digits of every script Unicode defines, so a run of non-ASCII digits would be treated as a
        //       card number here while no card number can contain one -- the baseline's field is a
        //       fixed-width display field of ASCII digits. Narrowing the test to ASCII keeps the rule the
        //       same as the width it is derived from.
        return candidate >= '0' && candidate <= '9';
    }
}
