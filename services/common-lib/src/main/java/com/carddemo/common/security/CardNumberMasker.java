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
 * <p>Refactoring Rationale: separated card numbers ARE detected, and an earlier revision of this class
 * argued at length that they need not be. That argument turned on a claim about ordering that does not
 * hold: it asserted that "a separated form fails the digits-only validation on the way in long before
 * anything renders it". A rejected path member is rendered precisely BECAUSE validation failed -- the
 * refusal echoes the path a caller invented, and the shared advice logs it alongside -- so validation
 * running first is the reason the value reaches a log line rather than a reason it cannot. The same is
 * true of the correlation header: {@code com.carddemo.common.web.CorrelationIdFilter} admits a hyphen,
 * a dot and an underscore inside an identity it publishes to the mapped diagnostic context, so
 * {@code 4111-1111-1111-1111} conformed and was echoed. The contiguous rule is unchanged and the
 * separated rule is ADDED beside it, exactly as the earlier revision said the correction should be
 * made.</p>
 *
 * <p>Assumptions: a separated candidate is a sequence of digit groups of ONE uniform length, joined by
 * a single occurrence of ONE uniform separator drawn from {@value #SEPARATOR_CHARACTERS}, carrying at
 * least {@value #CARD_NUMBER_LENGTH} digits in total. Uniformity is what keeps the rule from firing on
 * a timestamp: {@code 2022-07-18 09:30:00.123456} has twenty digits, but its groups run 4, 2, 2, 2, 2,
 * 2, 6 and no two consecutive ones agree, so it is left exactly as it arrived -- which the migration
 * requires, because it renders timestamps in full by contract. A date {@code 2022-07-18} and a version
 * {@code 1.11.21} fail for the same reason. The forms that DO match are the two a human writes a card
 * number in, four groups of four joined by hyphens or by spaces, and their longer relatives.</p>
 *
 * <p>Trade-offs: the rule can fire on a value that is not a card number at all -- four hyphen-separated
 * four-digit groups such as {@code 2022-2023-2024-2025} match it. That is accepted on the same ground
 * the contiguous rule already accepts it: this class is positional and not a check-digit test, and it
 * fails in the safe direction. The alternative -- requiring a Luhn check before masking -- was rejected
 * twice over: it would put a card-number validity rule in the one class whose purpose is to avoid
 * handling card numbers, and a real number that failed the test would then be echoed in full.</p>
 *
 * <p>Assumptions: the separator characters are exactly the three the correlation filter admits inside an
 * identity, plus the space. The three are what make a smuggled PAN reachable through that header at all;
 * the space is included because it cannot appear in that header but can appear in a path segment, a
 * request parameter and a message a caller supplied. Widening the set further -- to a slash, say --
 * was rejected because a slash separates PATH SEGMENTS, so admitting it would let two unrelated
 * four-digit segments of one route be read as a single separated number.</p>
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
     * The regular expression a fully masked card number matches, and no unmasked one does.
     *
     * <p>Refactoring Rationale: this is published so that a response type carrying a masked rendering can
     * constrain it, which is what stops an unmasked primary account number from satisfying a member
     * declared to hold a masked one. Before it existed, the card response shapes bounded that member by
     * WIDTH alone -- and a raw sixteen-digit number is exactly sixteen characters wide, so it satisfied
     * every such bound and nothing in the type system said otherwise.</p>
     *
     * <p>Alternatives Considered: writing the expression into each response shape that needs it. Rejected
     * because it copies two constants this class owns -- the mask character and the number of visible
     * trailing positions -- into types that do not own them, so a change here would leave each copy a
     * stale second definition of the masking rule while still compiling. Deriving the expression from the
     * same constants {@link #mask(String)} renders with means the rule has one definition and the
     * constraint cannot drift from the renderer.</p>
     *
     * <p>Assumptions: the expression is built from constant expressions alone, so it remains a
     * compile-time constant and can be used as a constraint annotation's value, which is the whole point
     * of publishing it rather than a compiled {@code Pattern}. The mask character is wrapped in a literal
     * quotation so that a character with meaning in a regular expression -- which an asterisk is -- cannot
     * change what the expression means if the constant is ever changed.</p>
     *
     * <p>Assumptions: this describes a FULL-WIDTH masked rendering only, that is
     * {@value #CARD_NUMBER_LENGTH} characters of which the last {@value #VISIBLE_TAIL_LENGTH} are digits.
     * {@link #mask(String)} also renders a short value entirely masked, and such a value does not match
     * this expression. That is intended: a card number is a declared sixteen-position field, so a
     * response carrying anything narrower is reporting a row that could not have been stored, and the
     * expression refusing it is the correct answer rather than a gap.</p>
     */
    public static final String MASKED_FORM_PATTERN =
            "[\\Q" + MASK_CHARACTER + "\\E]{" + (CARD_NUMBER_LENGTH - VISIBLE_TAIL_LENGTH)
                    + "}[0-9]{" + VISIBLE_TAIL_LENGTH + "}";

    /**
     * The characters a separated card number may be written with.
     *
     * <p>Assumptions: the three punctuation marks are exactly the set
     * {@code com.carddemo.common.web.CorrelationIdFilter} admits inside an identity it publishes to the
     * mapped diagnostic context, which is what made a separated primary account number reachable through
     * that header. The space is added because it cannot occur there but can occur in a path segment or a
     * caller-supplied message. The rationale for not widening the set further is on the class.</p>
     */
    private static final String SEPARATOR_CHARACTERS = "-._ ";

    /**
     * The number of digits in one group of a separated card number.
     *
     * <p>Assumptions: four, which is how a card number is grouped everywhere it is written by hand and on
     * the card itself. Requiring the groups to be uniformly this width is the whole of what distinguishes
     * a separated card number from a timestamp, a date or a dotted version, each of which has groups of
     * differing widths. A rule that accepted any uniform width would match {@code 1.11.21} at width two
     * once it reached sixteen digits, and would match nothing useful that this width misses.</p>
     */
    private static final int SEPARATED_GROUP_LENGTH = 4;

    /**
     * The fewest groups a separated candidate must carry, derived rather than chosen.
     *
     * <p>Assumptions: {@value #CARD_NUMBER_LENGTH} digits in groups of
     * {@value #SEPARATED_GROUP_LENGTH} is four groups, so the threshold is the quotient and moves with
     * either constant instead of being restated. Writing the literal four here would let the two rules
     * disagree after a change to the card width.</p>
     */
    private static final int SEPARATED_MIN_GROUPS = CARD_NUMBER_LENGTH / SEPARATED_GROUP_LENGTH;

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
     * @return {@code null} when {@code text} is {@code null}; the same instance when it contains neither a
     *     run of {@value #CARD_NUMBER_LENGTH} or more digits nor a uniformly separated candidate;
     *     otherwise a copy in which every such value is masked to its last four digits, with any
     *     separators left in place so the surrounding text stays readable
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
                index = runEnd;
                continue;
            }

            // WHY : Assumptions: the separated test is reached only when the contiguous test did not fire,
            //       and it starts at the same position, so the two rules never both act on one value and the
            //       contiguous behaviour every existing caller relies on is bit-for-bit unchanged. Ordering
            //       them this way rather than the reverse matters: a contiguous run of sixteen digits is
            //       also a "uniformly separated" sequence of four groups with an empty separator under a
            //       looser reading, and letting the separated rule see it first would make one value
            //       maskable by two code paths with two different tail calculations.
            int separatedEnd = separatedCandidateEnd(text, index, runEnd);
            if (separatedEnd > index) {
                if (masked == null) {
                    masked = new StringBuilder(text);
                }
                maskSeparatedDigits(masked, index, separatedEnd);
                index = separatedEnd;
                continue;
            }

            index = runEnd;
        }

        return masked == null ? text : masked.toString();
    }

    /**
     * Finds the end of a uniformly separated card-number candidate beginning at a digit run.
     *
     * <p>Assumptions: the candidate must open with a group of exactly {@value #SEPARATED_GROUP_LENGTH}
     * digits, and every subsequent group must be that same width and be introduced by one occurrence of
     * the SAME separator character as the first join. The first join is what fixes the separator for the
     * whole candidate, so {@code 4111-1111 1111-1111} is not one candidate but a shorter one that stops
     * at the space -- and stopping there leaves it below the digit threshold, so nothing is masked. That
     * strictness is deliberate: a mixed-separator sequence is not a shape anybody writes a card number
     * in, and admitting it would let two adjacent unrelated values be joined into one match.</p>
     *
     * <p>Assumptions: a group whose digit run is not exactly {@value #SEPARATED_GROUP_LENGTH} long ends
     * the candidate WITHOUT being counted, so a trailing group of five digits leaves only the three
     * groups before it and the candidate then falls below the group threshold and matches nothing.
     * Measuring each group's whole run before comparing -- rather than reading four digits and moving
     * on -- is what makes that true: reading a fixed four would accept the first four digits of a wider
     * group and report a match inside a value that is not of this shape at all.</p>
     *
     * <p>Trade-offs: the scan returns an end offset rather than a boolean plus a second pass, so the
     * caller masks exactly the span this method measured. Returning a boolean would leave the caller to
     * re-derive the extent, which is the kind of duplicated arithmetic that lets a masking rule disagree
     * with its own detector.</p>
     *
     * @param text the text being scanned
     * @param start the index of the first digit of the opening group
     * @param firstRunEnd the exclusive end of the opening digit run, already measured by the caller
     * @return the exclusive end offset of the candidate when one is present and carries at least
     *     {@value #CARD_NUMBER_LENGTH} digits, otherwise {@code start} to report no match
     */
    private static int separatedCandidateEnd(String text, int start, int firstRunEnd) {
        if (firstRunEnd - start != SEPARATED_GROUP_LENGTH) {
            return start;
        }

        char separator = 0;
        int groups = 1;
        int position = firstRunEnd;
        int length = text.length();

        while (position + 1 + SEPARATED_GROUP_LENGTH <= length) {
            char candidateSeparator = text.charAt(position);
            if (SEPARATOR_CHARACTERS.indexOf(candidateSeparator) < 0) {
                break;
            }
            if (separator != 0 && candidateSeparator != separator) {
                break;
            }

            int groupStart = position + 1;
            int groupEnd = groupStart;
            while (groupEnd < length && isDigit(text.charAt(groupEnd))) {
                groupEnd++;
            }
            if (groupEnd - groupStart != SEPARATED_GROUP_LENGTH) {
                break;
            }

            separator = candidateSeparator;
            groups++;
            position = groupEnd;
        }

        return groups >= SEPARATED_MIN_GROUPS ? position : start;
    }

    /**
     * Masks every digit of a separated candidate except its last four, leaving the separators in place.
     *
     * <p>Assumptions: the tail exposed is four DIGITS rather than four characters, counted from the end
     * of the span, so a candidate ending in a separator could not shift the boundary. Counting characters
     * instead would expose only three digits of {@code 4111-1111-1111-1111} and would expose a separator
     * as though it were data.</p>
     *
     * <p>Trade-offs: the separators survive the masking, so {@code 4111-1111-1111-1111} renders as
     * {@code ****-****-****-1111} rather than as an unbroken run of mask characters. Keeping them is
     * what preserves the width and the shape of the surrounding text, which is the same property
     * {@link #mask(String)} preserves for a bare number, and it makes a masked value recognisable as the
     * separated form it arrived in.</p>
     *
     * @param masked the buffer holding a copy of the scanned text, modified in place
     * @param start the inclusive start offset of the candidate
     * @param end the exclusive end offset of the candidate
     */
    private static void maskSeparatedDigits(StringBuilder masked, int start, int end) {
        int digitsRemaining = 0;
        for (int position = start; position < end; position++) {
            if (isDigit(masked.charAt(position))) {
                digitsRemaining++;
            }
        }

        int digitsToMask = digitsRemaining - VISIBLE_TAIL_LENGTH;
        for (int position = start; position < end && digitsToMask > 0; position++) {
            if (isDigit(masked.charAt(position))) {
                masked.setCharAt(position, MASK_CHARACTER);
                digitsToMask--;
            }
        }
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
