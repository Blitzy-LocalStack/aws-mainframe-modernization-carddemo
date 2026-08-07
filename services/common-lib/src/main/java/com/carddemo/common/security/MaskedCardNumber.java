package com.carddemo.common.security;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * States, once, what a masked primary account number looks like, so every response contract can refuse a
 * value that is not one.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>{@link CardNumberMasker} PRODUCES a masked rendering. Nothing established what one IS, so each
 * response type that publishes a masked card number checked a different, weaker approximation of the same
 * rule: one accepted any sixteen characters beginning with the mask character, so
 * {@code *234567890123456} passed as masked while disclosing fifteen digits of a card; two others declared
 * only a maximum width, so a full sixteen-digit primary account number satisfied them exactly. A
 * production rule and no acceptance rule is the shape in which a masking obligation quietly stops
 * holding -- masking still happens on the path anybody tested, and a path that skipped it publishes a card
 * number with nothing to refuse it.</p>
 *
 * <p>Refactoring Rationale: the rule is stated ONCE, as the compiled pattern behind {@link #DOMAIN}, and
 * both ways of applying it read that one statement. A contract that declares its constraints as
 * annotations uses {@link #DOMAIN} directly, which is why it is a compile-time constant; a contract that
 * guards its own constructor calls {@link #require(String, String)}, which matches the same pattern.
 * Neither restates the arithmetic, so the two cannot come to disagree -- which is exactly how the three
 * approximations above arose.</p>
 *
 * <h2>Why the shape is exact rather than a maximum</h2>
 *
 * <p>Assumptions: the form is exactly twelve mask characters followed by exactly four digits, at the card
 * number's own declared width of sixteen from {@code CARD-NUM PIC X(16)} at line 5 of
 * {@code app/cpy/CVACT02Y.cpy}. Every part of that is load-bearing. The width has to be exact because a
 * shorter value cannot be told apart from a truncated card number. The prefix has to be all mask
 * characters because a partially-masked value is the failure this class exists to refuse. The suffix has
 * to be digits because a masked rendering whose tail is not the card's last four digits identifies
 * nothing.</p>
 *
 * <p>Assumptions: a refusal NEVER echoes the candidate. The whole reason a value reaches this check is
 * that it might be an unmasked card number, so quoting it in the exception would write the very value the
 * check exists to keep out of a response into the log line that reports the refusal. The refusal names the
 * component and states the required shape, which is what a developer needs, and reports the candidate's
 * LENGTH and -- when the length is already correct -- the one-based POSITION of the first character the
 * shape does not permit. Those are the two properties of the value that are safe to name: neither
 * reproduces a character of it, and between them they distinguish a wrong width from a disclosed digit
 * inside the masked run.</p>
 *
 * <h2>What this class is not</h2>
 *
 * <p>Alternatives Considered: making this a value TYPE wrapping the string, so that a masked number could
 * not be confused with an unmasked one at compile time. Rejected because these values are components of
 * published response records that serialise as JSON strings, and a wrapper would either need a serialiser
 * per contract or would change the published shape of every response that carries one. The obligation is
 * met by making the constraint impossible to state loosely rather than by changing the wire type.</p>
 *
 * <p>Alternatives Considered: declaring this as a Bean Validation constraint annotation with its own
 * validator. Rejected because the two applications needed are a constraint pattern and a constructor
 * guard, and an annotation serves only the first: the authorization contracts validate inside their
 * compact constructors so that no instance can exist in a state the contract does not describe, and an
 * annotation is not read on construction. A constant and a static guard serve both without a third
 * mechanism.</p>
 *
 * <p><strong>Return value.</strong> This class declares no instance state and is never instantiated; each
 * member documents its own return value.</p>
 */
public final class MaskedCardNumber {

    /**
     * The number of characters a masked rendering occupies.
     *
     * <p>Assumptions: sixteen, the declared width of {@code CARD-NUM PIC X(16)} at line 5 of
     * {@code app/cpy/CVACT02Y.cpy}. {@link CardNumberMasker} substitutes a mask character for each leading
     * position rather than removing it, so the rendering is the same width as the number it stands for and
     * this figure is the same one three artifacts already agree on: the copybook, the stored column and
     * the published contract.</p>
     */
    public static final int MASKED_LENGTH = 16;

    /**
     * The number of leading positions a masked rendering replaces with the mask character.
     *
     * <p>Assumptions: derived rather than written, so that changing either the width or the visible tail
     * cannot leave this figure behind. It is twelve for the current pair.</p>
     */
    public static final int MASK_PREFIX_LENGTH = MASKED_LENGTH - CardNumberMasker.VISIBLE_TAIL_LENGTH;

    /**
     * The regular expression a masked rendering must match in full.
     *
     * <p>Assumptions: this is a compile-time constant expression, assembled from the constants above and
     * from {@link CardNumberMasker#MASK_CHARACTER}, so it can be used directly as a constraint annotation
     * argument. That is the reason it is a string built by concatenation rather than a pattern assembled at
     * run time: an annotation argument must be constant, and a rule that could not be used in an
     * annotation would have to be restated by every contract that declares its constraints that way.</p>
     *
     * <p>Assumptions: the mask character is ESCAPED. It is an asterisk, which is a repetition operator in
     * this notation, so an unescaped one would make the preceding element optional and the expression
     * would match values it must refuse.</p>
     *
     * <p>Assumptions: the expression is anchored at both ends. A constraint pattern is matched against the
     * whole value on this platform, so the anchors are redundant there and are kept because
     * {@link #MASKED} matches with the same expression and because an unanchored copy of this constant
     * used anywhere else would admit a masked run inside a longer string.</p>
     *
     * <p>Alternatives Considered: publishing the same expression from {@link CardNumberMasker} instead, so
     * that the rule sits beside the renderer whose constants it is built from. Rejected because the rule is
     * more than an expression: a response type needs the constraint AND the constructor guard that reports
     * a refusal without echoing the value, and those belong together. Two constants would compile
     * independently and drift independently -- one anchored and one not, one with a guard behind it and one
     * without -- and a reader who found the weaker one would have no way to know the stronger existed.
     * The expression is derived from that class's own constants, so nothing is duplicated by keeping the
     * single definition here.</p>
     */
    public static final String DOMAIN =
            "^\\" + CardNumberMasker.MASK_CHARACTER + "{" + MASK_PREFIX_LENGTH + "}[0-9]{"
                    + CardNumberMasker.VISIBLE_TAIL_LENGTH + "}$";

    /**
     * The compiled form of {@link #DOMAIN}, which is the single executable statement of the rule.
     *
     * <p>Assumptions: compiled once into a static field rather than per call. A pattern compiled inside
     * the guard would be recompiled for every component of every response row, and a list response
     * carries one per row.</p>
     */
    private static final Pattern MASKED = Pattern.compile(DOMAIN);

    /**
     * Refuses instantiation.
     *
     * <p>Assumptions: the class is a rule and a pair of operations over it, with no state to hold. A
     * private constructor is what keeps it from being injected as a collaborator and then mocked, which
     * would let a test pass against a masking rule that does not hold in production.</p>
     *
     * @throws AssertionError always, because reflective construction is the only way this is reachable
     *     and it is refused rather than silently producing a useless instance
     */
    private MaskedCardNumber() {
        throw new AssertionError("MaskedCardNumber is not instantiable");
    }

    /**
     * Reports whether a value is in the masked form every response contract publishes.
     *
     * <p>Assumptions: {@code null} is reported as NOT masked rather than accepted. Requiredness is a
     * separate concern that each contract declares for itself, and a predicate that answered {@code true}
     * for an absent value would read as though absence satisfied the masking rule.</p>
     *
     * @param candidate the value to test, which may be {@code null}
     * @return {@code true} when the value is exactly {@value #MASK_PREFIX_LENGTH} mask characters
     *     followed by exactly {@code CardNumberMasker.VISIBLE_TAIL_LENGTH} digits
     */
    public static boolean isMasked(String candidate) {
        return candidate != null && MASKED.matcher(candidate).matches();
    }

    /**
     * Refuses a value that is absent or not in the masked form the response contracts publish.
     *
     * <p>Assumptions: the check is on the SHAPE and not on a masking call having been made, because that
     * is what makes it a guard. A value that arrived unmasked -- from a mapper that forgot the call, or
     * from deserialisation of a hand-written body -- is refused on its own characters rather than trusted
     * because of where it came from.</p>
     *
     * @param componentName the name of the component being checked, used in the refusal so that a
     *     response type carrying more than one card number says which; must not be {@code null}
     * @param candidate the value to check, which may be {@code null}
     * @throws NullPointerException if {@code candidate} is {@code null}
     * @throws IllegalArgumentException if {@code candidate} is not exactly
     *     {@value #MASK_PREFIX_LENGTH} mask characters followed by exactly four digits
     */
    public static void require(String componentName, String candidate) {
        Objects.requireNonNull(componentName, "componentName is required");
        Objects.requireNonNull(candidate, componentName + " is required");
        if (!isMasked(candidate)) {
            // Assumptions: the length is named and the value is not. A candidate that reaches this
            // branch may be an unmasked card number, so quoting it would write the value into the log
            // line that reports its refusal -- the one place the refusal must not put it.
            throw new IllegalArgumentException(componentName + " must be published masked, as exactly "
                    + MASK_PREFIX_LENGTH + " '" + CardNumberMasker.MASK_CHARACTER + "' characters"
                    + " followed by exactly " + CardNumberMasker.VISIBLE_TAIL_LENGTH
                    + " digits; the value supplied was " + candidate.length() + " characters and does"
                    + " not match that shape" + offendingPositionClause(candidate));
        }
    }

    /**
     * Renders the location of the first offending character, when the value is the declared width.
     *
     * <p>Assumptions: a position is reported ONLY when the width is already correct. A value of the wrong
     * width is refused by its width, and naming a position as well would name the wrong fault -- on a
     * value one character short every position from the shortfall onward differs from what the rule wants,
     * so the first difference is an artefact of the length rather than the defect to fix.</p>
     *
     * <p>Assumptions: the position is ONE-based and the character at it is not rendered. The whole reason
     * this guard withholds the value is that a candidate reaching it may be an unmasked card number, so
     * naming the offending digit would defeat the withholding one character at a time. An index locates
     * the fault for a developer without disclosing anything about the value, which is the same division
     * {@code CsvAuthCodec} and {@code PackedDecimalCodec} already make when they refuse a field.</p>
     *
     * <p>Alternatives Considered: reporting nothing beyond the shape, which is what this guard did when it
     * replaced the two per-view checks. Rejected because the shape alone does not distinguish the two ways
     * a width-correct value fails -- a disclosed digit inside the masked run from a non-digit in the
     * visible tail -- and those have different causes: the first is a mapper that skipped the masking call,
     * the second is a value that was never a card number.</p>
     *
     * @param candidate the value being refused; must not be {@code null}
     * @return a clause naming the one-based position of the first offending character, or the empty string
     *     when the value is not the declared width and its width is therefore the fault
     */
    private static String offendingPositionClause(String candidate) {
        if (candidate.length() != MASKED_LENGTH) {
            return "";
        }
        for (int index = 0; index < MASKED_LENGTH; index++) {
            char character = candidate.charAt(index);
            boolean conforms = index < MASK_PREFIX_LENGTH
                    ? character == CardNumberMasker.MASK_CHARACTER
                    : character >= '0' && character <= '9';
            if (!conforms) {
                return "; the first character not permitted by that shape is at position " + (index + 1);
            }
        }
        // Assumptions: unreachable, because a value of the declared width whose every character conforms
        // is matched by MASKED and never reaches this branch. Returning the empty string rather than
        // throwing keeps a refusal path from failing for a second reason while it reports the first.
        return "";
    }
}
