package com.carddemo.common.security;

import java.util.Objects;

/**
 * Encodes a value so that placing it inside a markup document cannot change that document's
 * structure.
 *
 * <p>This exists because the migrated statement generator writes a markup artifact whose cells are
 * assembled by concatenation, and three of those cells carry free text a client supplied: a
 * transaction description, a customer name and a customer address. In the baseline those values
 * reached a 3270 terminal, which has no markup and therefore no injection surface -- the reference
 * generator {@code app/cbl/CBSTM03A.CBL} concatenates them into its markup output with no encoding
 * at all, and that is not a defect of the baseline so much as a property of the era it was written
 * for. A browser opening the migrated artifact is a different consumer, and the same concatenation
 * that was inert on a terminal executes there. Encoding at the point the value enters the markup is
 * the standard answer, and this class is the single place that answer is implemented.</p>
 *
 * <h2>Alternatives Considered: rejecting the input instead of encoding the output</h2>
 *
 * <p>Constraining the accepted character set of the description, the name and the address at their
 * request shapes was weighed and rejected. The baseline items are {@code PIC X(n)}, which admits
 * every character in the code page, so narrowing them would be a behavioural divergence from the
 * reference for every legitimate value that happens to contain an ampersand -- a merchant named
 * "Marks &amp; Spencer" is not hostile input. It would also be the wrong control even where it
 * worked: the value is safe or unsafe only relative to the context it lands in, and the same string
 * that must be encoded for markup must NOT be encoded for the plain-text artifact or the byte
 * comparison against the reference fails. Encoding belongs at each sink, and this class is designed
 * to be called there.</p>
 *
 * <h2>Trade-offs: five characters are encoded, not three</h2>
 *
 * <p>Text content between tags is made safe by encoding {@code &}, {@code <} and {@code >} alone;
 * the quote characters matter only inside a quoted attribute value. This class encodes all five
 * anyway. The reason is that a text-content-only encoder is a footgun: it is correct at every site
 * it is used today and silently wrong at the first site someone later places inside
 * {@code attr="..."}, and that misuse is invisible in review because the call looks identical. The
 * cost of the wider set is a few bytes on values that contain a quote or an apostrophe -- "O'Brien"
 * encodes four characters longer -- and, because a numeric character reference renders as the
 * character it names, no rendered output changes. Paying bytes to remove a whole class of future
 * misuse is the better trade here, and it is stated because the narrower set is what a reader would
 * otherwise expect.</p>
 *
 * <h2>Assumptions: a value with nothing to encode is returned unchanged</h2>
 *
 * <p>The common case is a value containing none of the five characters, and for that case this class
 * returns the argument itself rather than a rebuilt copy. That is not only an allocation
 * optimisation: it is what makes encoding a no-op on data that has nothing to encode, so an artifact
 * assembled from ordinary values is byte-for-byte what it was before this class was introduced. Any
 * test comparing such an artifact against a recorded expectation therefore keeps passing, and a
 * difference in one of those comparisons means a genuine metacharacter was present.</p>
 *
 * @see CardNumberMasker for the other output-encoding control in this package, which narrows a value
 *     rather than escaping it
 */
public final class HtmlTextEncoder {

    /**
     * The longest replacement any single character expands to, in characters.
     *
     * <p>Assumptions: {@code &quot;} is six characters and is the longest of the five replacements,
     * so a value of length n can never encode to more than six times n. A caller sizing a buffer or
     * reasoning about a fixed-width record can use this bound without consulting the table.</p>
     */
    public static final int MAX_EXPANSION_PER_CHARACTER = 6;

    /** Replacement for the ampersand, which must be encoded first in any hand-written loop. */
    private static final String AMPERSAND = "&amp;";

    /** Replacement for the less-than sign, the character that opens a tag. */
    private static final String LESS_THAN = "&lt;";

    /** Replacement for the greater-than sign, encoded for symmetry with the tag-opening character. */
    private static final String GREATER_THAN = "&gt;";

    /** Replacement for the double quote, which would otherwise close a quoted attribute value. */
    private static final String DOUBLE_QUOTE = "&quot;";

    /**
     * Replacement for the apostrophe, given as a numeric reference rather than as {@code &apos;}.
     *
     * <p>Assumptions: the numeric form is used because {@code &apos;} is defined by XML and by HTML
     * 5 but not by HTML 4, so a document served or parsed as the older grammar would render the
     * entity name literally. A numeric character reference is defined in every version of both, so
     * it renders as an apostrophe wherever the artifact is opened.</p>
     */
    private static final String APOSTROPHE = "&#39;";

    /**
     * Prevents instantiation of this stateless utility.
     *
     * <p>Assumptions: every member is static because encoding depends on nothing but its argument.
     * The constructor is private so that no instance can be created and injected, which would
     * suggest the encoding is configurable when it is not.</p>
     *
     * @throws AssertionError always, so that a reflective instantiation fails loudly rather than
     *     yielding an object whose existence implies this encoding has state
     */
    private HtmlTextEncoder() {
        throw new AssertionError("HtmlTextEncoder is a utility and is never instantiated");
    }

    /**
     * Reports whether a character has to be replaced before it can enter a markup document.
     *
     * @param character the character to classify
     * @return {@code true} for the five characters this class replaces, {@code false} otherwise
     */
    private static boolean requiresEncoding(char character) {
        return character == '&'
                || character == '<'
                || character == '>'
                || character == '"'
                || character == '\'';
    }

    /**
     * Returns the replacement for one character that requires encoding.
     *
     * @param character one of the five characters {@link #requiresEncoding} accepts
     * @return the replacement text for that character; never {@code null}
     * @throws IllegalArgumentException if the character does not require encoding, which would mean
     *     a caller had classified it with something other than {@link #requiresEncoding}
     */
    private static String replacementFor(char character) {
        return switch (character) {
            case '&' -> AMPERSAND;
            case '<' -> LESS_THAN;
            case '>' -> GREATER_THAN;
            case '"' -> DOUBLE_QUOTE;
            case '\'' -> APOSTROPHE;
            default -> throw new IllegalArgumentException(
                    "character does not require encoding: " + (int) character);
        };
    }

    /**
     * Encodes a value for placement inside a markup document.
     *
     * <p>Assumptions: the whole value is encoded and nothing is dropped, so this method never
     * shortens its argument and its result may be up to
     * {@value #MAX_EXPANSION_PER_CHARACTER} times longer. A caller assembling a record of declared
     * width must use {@link #encodeWithin} instead, which is bounded.</p>
     *
     * @param value the value to encode; must not be {@code null}
     * @return the value with each of the five markup-significant characters replaced, or the
     *     argument itself when it contains none of them; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}, because an absent value and an
     *     empty one mean different things to a caller assembling a fixed-width cell and silently
     *     treating the first as the second would fill that cell with the wrong number of blanks
     */
    public static String encode(String value) {
        Objects.requireNonNull(value, "value must not be null");

        // WHY : Assumptions: the scan runs before any allocation so that the common case -- a value
        //       with nothing to encode -- returns the argument itself. See the class note: this is
        //       what makes encoding byte-neutral on ordinary data rather than merely cheap.
        int first = -1;
        for (int i = 0; i < value.length(); i++) {
            if (requiresEncoding(value.charAt(i))) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return value;
        }

        // WHY : Trade-offs: the builder is sized from the input length plus a modest allowance
        //       rather than from the worst-case expansion. Sizing for the worst case would allocate
        //       six times the input for a value that usually needs a handful of extra characters,
        //       and a builder that outgrows its capacity resizes correctly on its own -- so the
        //       worst case costs one array copy and the common case costs nothing.
        StringBuilder encoded = new StringBuilder(value.length() + 16);
        encoded.append(value, 0, first);
        for (int i = first; i < value.length(); i++) {
            char character = value.charAt(i);
            if (requiresEncoding(character)) {
                encoded.append(replacementFor(character));
            } else {
                encoded.append(character);
            }
        }
        return encoded.toString();
    }

    /**
     * Encodes a value for markup, stopping before the result would exceed a character budget.
     *
     * <p>This is the form a fixed-width record must use. Encoding expands a value, and the markup
     * statement's records have a declared length that a grown cell would overrun, so a caller that
     * has a ceiling needs a result guaranteed to fit rather than one it has to check.</p>
     *
     * <p>Assumptions: the budget is applied to the ENCODED length and a replacement is admitted only
     * whole. That is the property that makes truncation safe rather than merely tidy: a result cut in
     * the middle of {@code &amp;} would end in a partial reference, which a browser is entitled to
     * resolve by consuming whatever markup follows it -- so a naive substring on the encoded text
     * could reintroduce exactly the injection this class exists to prevent.</p>
     *
     * <p>Trade-offs: content beyond the budget is DISCARDED, silently and without a marker. Three
     * alternatives were weighed. Throwing was rejected because the budget is exceeded only by a value
     * carrying an implausible density of markup characters, and a caller that throws turns such a
     * value into a failure of the whole document rather than a defect in one cell -- which hands an
     * author of hostile input a denial of service. Appending an ellipsis or a marker was rejected
     * because the marker would itself consume budget and would appear in an artifact whose byte
     * layout is compared against a reference. Widening the record was rejected because the declared
     * length is a property of the data set the artifact is written to, not of this encoder. What is
     * accepted is that the markup artifact may render less of a pathological value than the
     * plain-text artifact holds; the plain-text artifact is the complete record and is unaffected by
     * this class.</p>
     *
     * @param value the value to encode; must not be {@code null}
     * @param budget the maximum length of the returned text in characters; must not be negative
     * @return the encoded value, truncated at a whole-replacement boundary if necessary so that its
     *     length never exceeds {@code budget}; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code budget} is negative, which reports a defect in the
     *     caller's arithmetic -- a record whose prefix and suffix already exceed its declared length
     *     leaves no room for any value at all and would otherwise silently produce an empty cell
     */
    public static String encodeWithin(String value, int budget) {
        Objects.requireNonNull(value, "value must not be null");
        if (budget < 0) {
            throw new IllegalArgumentException(
                    "budget must not be negative but was " + budget);
        }

        // WHY : Assumptions: the unbounded encoding is computed first and returned when it fits,
        //       which is every case but a pathological one. Building the result character by
        //       character against the budget from the start would be the same work in the common
        //       case and would lose the byte-neutral identity return that encode() provides.
        String encoded = encode(value);
        if (encoded.length() <= budget) {
            return encoded;
        }

        // WHY : Assumptions: the walk is over the SOURCE and accumulates the encoded length,
        //       because that is the only way to stop on a replacement boundary. Cutting the encoded
        //       string instead would require finding whether the cut fell inside a replacement,
        //       which means re-parsing text this method just produced -- and getting that wrong is
        //       precisely the partial-reference hazard documented above.
        StringBuilder bounded = new StringBuilder(budget);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            String piece = requiresEncoding(character)
                    ? replacementFor(character)
                    : String.valueOf(character);
            if (bounded.length() + piece.length() > budget) {
                break;
            }
            bounded.append(piece);
        }
        return bounded.toString();
    }
}
