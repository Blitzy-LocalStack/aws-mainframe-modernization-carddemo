package com.carddemo.batch.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies that the value entering a generated transaction identifier is always numeric.
 *
 * <p>Refactoring Rationale: this class exists because of a specific defect with a delayed symptom, and
 * the assertions below are shaped by that timing rather than by the shape of the method under test.
 * Identifier construction used to read the business-date token verbatim, on the correct observation that
 * {@code app/cbl/CBACT04C.cbl} lines 476 to 480 concatenate the parameter as supplied. The baseline's own
 * parameter is compact and numeric -- {@code app/jcl/INTCALC.jcl} line 22 injects
 * {@code PARM='2022071800'} -- so its identifiers are sixteen digits. This module's entry point also
 * accepts a SEPARATED token of the same width, because {@code DatasetGeneration.partitionDate()} resolves
 * both layouts, and a separated token concatenated as supplied produces {@code 2024-01-15000001}: the
 * right length, and not a number.</p>
 *
 * <p>Assumptions: that identifier stores cleanly and is not detectably wrong until something reads it
 * back arithmetically. Because the column is {@code CHAR(16)} and orders lexicographically, a
 * date-prefixed identifier sorts above every sequence-format one, so after one night's interest run it
 * became the stored maximum -- and the interactive add and bill-payment paths, which derived their next
 * key from that maximum, then failed on a numeric parse. The failure therefore appeared a day after the
 * change that caused it, in a different service, on a path that had not been touched. That is why the
 * guarantee is asserted here at the point of composition rather than only at the point of consumption.</p>
 */
@DisplayName("the business-date identifier prefix")
class BusinessDateIdentifierPrefixTest {

    /**
     * The width every prefix must occupy, which with a six-digit suffix fills the identifier exactly.
     */
    private static final int PREFIX_WIDTH = 10;

    /**
     * The baseline's own production parameter is returned byte-for-byte.
     *
     * <p>Assumptions: this is the most important single assertion in the class. The reference's
     * identifiers must be reproduced exactly, so a compact token must pass through unaltered rather than
     * be re-derived through any parse-and-render path that might normalise it differently.</p>
     */
    @Test
    @DisplayName("returns the baseline's compact parameter unchanged")
    void compactBaselineParameterIsUnchanged() {
        assertThat(new BusinessDate("2022071800").identifierPrefix()).isEqualTo("2022071800");
    }

    /**
     * A separated token normalises to the compact layout the baseline would have carried.
     *
     * <p>Assumptions: the expected value is the token with its hyphens removed and two zero digits
     * appended, because two zeros are what the baseline's own parameter carries in those positions. Any
     * other filler would make a normalised identifier differ from the one the baseline would have
     * produced for the same day.</p>
     *
     * @param separated the separated token supplied on a command line
     * @param expected the compact prefix it must normalise to
     */
    @ParameterizedTest(name = "{0} normalises to {1}")
    @MethodSource("separatedTokens")
    @DisplayName("normalises a separated token to the compact numeric layout")
    void separatedTokenNormalises(String separated, String expected) {
        assertThat(new BusinessDate(separated).identifierPrefix()).isEqualTo(expected);
    }

    /**
     * Supplies separated tokens paired with the compact prefix each must yield.
     *
     * @return the argument pairs, never empty
     */
    private static Stream<Arguments> separatedTokens() {
        return Stream.of(
                Arguments.of("2024-01-15", "2024011500"),
                Arguments.of("2022-07-18", "2022071800"),
                Arguments.of("1999-12-31", "1999123100"),
                Arguments.of("2026-02-29", "2026022900"));
    }

    /**
     * Every accepted token yields a prefix that is ten ASCII digits.
     *
     * <p>Assumptions: this is asserted as a property over both layouts rather than case by case, because
     * the property -- not any individual value -- is what the interactive paths depended on. A prefix
     * that is ten digits, concatenated with a six-digit suffix, is a sixteen-digit identifier that a
     * numeric read cannot reject.</p>
     *
     * @param token a token in either accepted layout
     */
    @ParameterizedTest(name = "{0} yields ten digits")
    @ValueSource(strings = {"2022071800", "2024-01-15", "0000000000", "9999999999", "2026-08-08"})
    @DisplayName("always yields exactly ten ASCII digits")
    void everyPrefixIsTenDigits(String token) {
        String prefix = new BusinessDate(token).identifierPrefix();

        assertThat(prefix).hasSize(PREFIX_WIDTH);
        assertThat(prefix).containsOnlyDigits();
    }

    /**
     * A composed identifier is sixteen digits and parses as a number.
     *
     * <p>Refactoring Rationale: this asserts the exact condition that used to fail, and it asserts it
     * against the SEPARATED layout specifically, because the compact layout never failed. The parse is
     * performed here even though production no longer parses a stored identifier on the add path, and the
     * redundancy is deliberate: the property being protected is that a stored identifier remains numeric,
     * and some future consumer may reasonably rely on it again.</p>
     */
    @Test
    @DisplayName("composes a sixteen-digit identifier from a separated token")
    void composedIdentifierFromSeparatedTokenIsNumeric() {
        // WHY : Assumptions: the suffix is rendered to six digits the way the reference renders it --
        //       WS-TRANID-SUFFIX PIC 9(06) at app/cbl/CBACT04C.cbl:173 is a zero-suppressed-free numeric
        //       display field, so 1 occupies all six positions as 000001.
        String identifier = new BusinessDate("2024-01-15").identifierPrefix()
                + String.format("%06d", 1);

        assertThat(identifier).isEqualTo("2024011500000001");
        assertThat(identifier).hasSize(16).containsOnlyDigits();
        assertThat(Long.parseLong(identifier)).isPositive();
    }

    /**
     * A token in neither layout is refused rather than producing an unusable prefix.
     *
     * <p>Assumptions: refusing is asserted rather than merely permitted. Returning the token unchanged
     * for an unrecognised layout would put a non-numeric value into an identifier, which is the whole
     * condition this method exists to prevent, and it would do so silently.</p>
     *
     * @param token a ten-character token in neither accepted layout
     */
    @ParameterizedTest(name = "{0} is refused")
    @ValueSource(strings = {"2024/01/15", "20-24-01-1", "2024-0115x", "abcdefghij", "2024-1-15 "})
    @DisplayName("refuses a token in neither accepted layout")
    void unrecognisedLayoutIsRefused(String token) {
        BusinessDate businessDate = new BusinessDate(token);

        assertThatThrownBy(businessDate::identifierPrefix)
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * A hyphen in the wrong position is refused rather than stripped wherever it falls.
     *
     * <p>Assumptions: asserted separately from the cases above because a looser implementation --
     * removing every hyphen regardless of position -- would pass every other test in this class while
     * accepting a value like {@code 20-24-01-15} and producing a prefix for a day nobody named.</p>
     */
    @Test
    @DisplayName("refuses hyphens outside the two separated-layout positions")
    void misplacedHyphensAreRefused() {
        assertThatThrownBy(() -> new BusinessDate("20-24-0115").identifierPrefix())
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The refusal names the token so an operator can see which command-line value was rejected.
     *
     * <p>Assumptions: quoting the value is correct here and is the opposite of the rule that applies to
     * cardholder data. A business date is a scheduling parameter an operator typed, carries no personal
     * or financial content, and is the one thing they need to see in order to correct the invocation.</p>
     */
    @Test
    @DisplayName("names the refused token in its diagnostic")
    void refusalNamesTheToken() {
        assertThatThrownBy(() -> new BusinessDate("2024/01/15").identifierPrefix())
                .hasMessageContaining("2024/01/15");
    }
}
