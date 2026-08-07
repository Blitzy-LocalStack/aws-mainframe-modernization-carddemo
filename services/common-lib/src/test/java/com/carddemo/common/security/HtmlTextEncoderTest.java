package com.carddemo.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Fixes the encoding contract the markup statement path depends on.
 *
 * <p>Assumptions: the properties asserted here are the ones another class relies on rather than the
 * ones a reader would guess. Three are load-bearing and each has cases of its own: a value with
 * nothing to encode comes back identical, so an artifact assembled from ordinary data keeps its bytes;
 * every one of the five significant characters is replaced, so no tag can be opened; and a budgeted
 * result never exceeds its budget and never ends inside a replacement, so a fixed-width record can
 * neither overrun nor emit a partial character reference.</p>
 *
 * <p>Refactoring Rationale: the hostile inputs below are real injection payloads rather than strings
 * merely containing a bracket. A test that encoded {@code "a<b"} would pass under an encoder that
 * replaced only {@code <}, which is exactly the encoder this class must not be allowed to become; a
 * payload that closes a tag, opens a script and closes an attribute exercises every character the
 * class claims to handle in the arrangement an attacker would actually use.</p>
 */
class HtmlTextEncoderTest {

    /** A payload that terminates a paragraph cell and opens an executable element. */
    private static final String TAG_INJECTION = "</p><script>alert(1)</script><p>";

    /** A payload that closes a quoted attribute value and adds an event handler. */
    private static final String ATTRIBUTE_INJECTION = "\" onmouseover=\"alert(1)";

    /** A payload using single quotes, which an attribute-context encoder must also handle. */
    private static final String SINGLE_QUOTE_INJECTION = "' onfocus='alert(1)";

    /**
     * Asserts that a value carrying none of the five characters is returned unchanged.
     *
     * <p>Assumptions: identity is asserted with {@code isSameAs} and not merely {@code isEqualTo},
     * because the guarantee the statement mapper depends on is that no copy is made -- that is what
     * makes encoding byte-neutral rather than byte-neutral-in-practice. An implementation that
     * rebuilt an equal string would satisfy an equality assertion and would still be a change to
     * every artifact that had ever been recorded.</p>
     *
     * @param ordinary a value of the kind the statement cells normally carry
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "",
        " ",
        "JOHN SMITH",
        "PAUL BUCK                                         ",
        "12 HIGH STREET, ANYTOWN",
        "PURCHASE AT MERCHANT 001",
        "  1234567.89-",
        "00000000011         "
    })
    @DisplayName("a value with nothing to encode is returned unchanged, without a copy")
    void anOrdinaryValueIsReturnedUnchanged(String ordinary) {
        assertThat(HtmlTextEncoder.encode(ordinary))
                .as("an artifact assembled from ordinary values must keep its bytes exactly")
                .isSameAs(ordinary);
    }

    /**
     * Asserts each of the five significant characters is replaced with the expected reference.
     *
     * @param raw the single character to encode
     * @param expected the reference it must become
     */
    @ParameterizedTest
    @CsvSource({
        "'&','&amp;'",
        "'<','&lt;'",
        "'>','&gt;'",
        "'\"','&quot;'",
        "'''','&#39;'"
    })
    @DisplayName("each significant character is replaced with its character reference")
    void eachSignificantCharacterIsReplaced(String raw, String expected) {
        assertThat(HtmlTextEncoder.encode(raw)).isEqualTo(expected);
    }

    /**
     * Asserts an ampersand is not double-encoded by a second pass over its own output.
     *
     * <p>Assumptions: this is the classic ordering defect and it is asserted rather than assumed. An
     * implementation that replaced {@code <} before {@code &} would turn a lone {@code <} into
     * {@code &amp;lt;}, which renders as the literal text {@code &lt;} instead of as a
     * less-than sign -- safe, but visibly wrong in the statement.</p>
     */
    @Test
    @DisplayName("a lone less-than sign encodes once, not twice")
    void aSignificantCharacterIsNotDoubleEncoded() {
        assertThat(HtmlTextEncoder.encode("<")).isEqualTo("&lt;");
        assertThat(HtmlTextEncoder.encode("&lt;")).isEqualTo("&amp;lt;");
    }

    /**
     * Asserts a tag-opening payload cannot survive encoding.
     *
     * <p>Assumptions: the assertion is that NO unencoded angle bracket remains, rather than that the
     * output equals a particular string. Equality would also pass if the encoder happened to drop the
     * payload, and dropping is not what is wanted; the absence of the bracket is the safety property
     * and the presence of the replacement is what shows the content survived.</p>
     */
    @Test
    @DisplayName("a tag-opening payload leaves no unencoded bracket")
    void aTagPayloadIsNeutralised() {
        String encoded = HtmlTextEncoder.encode(TAG_INJECTION);

        assertThat(encoded).doesNotContain("<").doesNotContain(">");
        assertThat(encoded).contains("&lt;script&gt;");
        assertThat(encoded)
                .as("the text is preserved, only its markup significance is removed")
                .contains("alert(1)");
    }

    /**
     * Asserts both quote payloads cannot escape a quoted attribute value.
     *
     * <p>Assumptions: these are asserted even though every current call site places its value in text
     * content, where a quote is harmless. They are the reason the encoder handles five characters
     * rather than three, so withdrawing that behaviour has to fail a test rather than merely
     * contradict a comment.</p>
     */
    @Test
    @DisplayName("both quote payloads leave no unencoded quote")
    void quotePayloadsAreNeutralised() {
        assertThat(HtmlTextEncoder.encode(ATTRIBUTE_INJECTION))
                .doesNotContain("\"")
                .contains("&quot;");
        assertThat(HtmlTextEncoder.encode(SINGLE_QUOTE_INJECTION))
                .doesNotContain("'")
                .contains("&#39;");
    }

    /**
     * Asserts a budgeted result never exceeds its budget, for every budget up to the encoded length.
     *
     * <p>Assumptions: every budget in the range is exercised rather than a chosen few, because the
     * failure this guards against is an off-by-one at a replacement boundary and that appears at one
     * specific budget rather than across the range.</p>
     */
    @Test
    @DisplayName("a budgeted result never exceeds its budget at any budget")
    void aBudgetedResultNeverExceedsItsBudget() {
        String encodedLength = HtmlTextEncoder.encode(TAG_INJECTION);
        for (int budget = 0; budget <= encodedLength.length() + 8; budget++) {
            assertThat(HtmlTextEncoder.encodeWithin(TAG_INJECTION, budget).length())
                    .as("budget %d must not be exceeded", budget)
                    .isLessThanOrEqualTo(budget);
        }
    }

    /**
     * Asserts a budgeted result never ends inside a character reference.
     *
     * <p>Refactoring Rationale: this is the assertion that makes truncation a safety property rather
     * than a tidiness one. A result cut in the middle of {@code &amp;} ends with an unterminated
     * reference, and a browser resolving it may consume the markup that follows -- so a naive
     * substring of the encoded text would reintroduce the very injection the encoding removed. The
     * check is that every ampersand in the result is followed by a complete, terminated reference.</p>
     */
    @Test
    @DisplayName("a budgeted result never ends inside a character reference")
    void aBudgetedResultNeverEndsInsideAReference() {
        String hostile = "&&&<<<>>>\"\"\"'''";
        for (int budget = 0; budget <= 64; budget++) {
            String bounded = HtmlTextEncoder.encodeWithin(hostile, budget);
            int index = bounded.indexOf('&');
            while (index >= 0) {
                int terminator = bounded.indexOf(';', index);
                assertThat(terminator)
                        .as("budget %d left an unterminated reference in \"%s\"", budget, bounded)
                        .isGreaterThan(index);
                index = bounded.indexOf('&', terminator);
            }
            assertThat(bounded).doesNotContain("<").doesNotContain(">").doesNotContain("\"");
        }
    }

    /**
     * Asserts a value that fits its budget is returned whole and identically to the unbounded form.
     */
    @Test
    @DisplayName("a value that fits its budget is returned whole")
    void aValueThatFitsIsReturnedWhole() {
        assertThat(HtmlTextEncoder.encodeWithin("JOHN SMITH", 64)).isEqualTo("JOHN SMITH");
        assertThat(HtmlTextEncoder.encodeWithin("MARKS & SPENCER", 64))
                .isEqualTo(HtmlTextEncoder.encode("MARKS & SPENCER"))
                .isEqualTo("MARKS &amp; SPENCER");
    }

    /**
     * Asserts a zero budget yields an empty result rather than a partial one or a failure.
     */
    @Test
    @DisplayName("a zero budget yields an empty result")
    void aZeroBudgetYieldsAnEmptyResult() {
        assertThat(HtmlTextEncoder.encodeWithin(TAG_INJECTION, 0)).isEmpty();
    }

    /**
     * Asserts the declared worst-case expansion bound is the true one.
     *
     * <p>Assumptions: a caller sizing a record against this constant is entitled to rely on it, so it
     * is checked against the longest replacement rather than left as a comment. Six is the length of
     * the double-quote replacement.</p>
     */
    @Test
    @DisplayName("the declared expansion bound holds for every replacement")
    void theDeclaredExpansionBoundHolds() {
        for (String raw : new String[] {"&", "<", ">", "\"", "'"}) {
            assertThat(HtmlTextEncoder.encode(raw).length())
                    .as("replacement for %s must fit the declared bound", raw)
                    .isLessThanOrEqualTo(HtmlTextEncoder.MAX_EXPANSION_PER_CHARACTER);
        }
        assertThat(HtmlTextEncoder.encode("\"").length())
                .as("the bound must be tight, not merely sufficient, or a caller sizing to it wastes"
                        + " record space it may need")
                .isEqualTo(HtmlTextEncoder.MAX_EXPANSION_PER_CHARACTER);
    }

    /** Asserts a null value is refused rather than silently treated as empty. */
    @Test
    @DisplayName("a null value is refused by both entry points")
    void aNullValueIsRefused() {
        assertThatThrownBy(() -> HtmlTextEncoder.encode(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value");
        assertThatThrownBy(() -> HtmlTextEncoder.encodeWithin(null, 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value");
    }

    /** Asserts a negative budget is refused, since it reports a defect in a caller's arithmetic. */
    @Test
    @DisplayName("a negative budget is refused")
    void aNegativeBudgetIsRefused() {
        assertThatThrownBy(() -> HtmlTextEncoder.encodeWithin("x", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
    }

    /**
     * Asserts the utility cannot be instantiated.
     *
     * @throws NoSuchMethodException if the no-argument constructor is removed, which would itself mean
     *     the guarantee needs re-establishing
     */
    @Test
    @DisplayName("the utility cannot be instantiated")
    void theUtilityCannotBeInstantiated() throws NoSuchMethodException {
        Constructor<HtmlTextEncoder> constructor = HtmlTextEncoder.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .cause()
                .isInstanceOf(AssertionError.class);
    }
}
