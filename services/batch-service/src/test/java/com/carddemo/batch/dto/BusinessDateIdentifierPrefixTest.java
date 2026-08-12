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
 * Verifies that one business date resolves to exactly one ten-digit dataset-key prefix.
 *
 * <p>Purpose: the export and import jobs place this value in an object key so a staged generation is
 * addressable by the day it was produced for. An object key is listed and compared as text, so two
 * spellings of one day would be two prefixes and a run started as {@code 2024-01-15} would not find the
 * generation a run started as {@code 2024011500} wrote. The module's entry point accepts both layouts,
 * because {@code DatasetGeneration.partitionDate()} resolves both and documents both as supported, so
 * the key has to be single-valued across them and this is the one place that is arranged.</p>
 *
 * <p>Refactoring Rationale: this class was charted as verifying that "the value entering a generated
 * TRANSACTION IDENTIFIER is always numeric", and that charter is withdrawn because the premise under it
 * was false. It held that a separated token had no baseline counterpart, so normalising one could not
 * diverge. The baseline has a counterpart: {@code app/cbl/CBACT04C.cbl} lines 476 to 480 string the
 * parameter and the suffix {@code DELIMITED BY SIZE}, copying all ten characters of
 * {@code PARM-DATE PIC X(10)} verbatim with no normalisation in the program, and the committed golden
 * master for a separated parameter is that concatenation --
 * {@code tests/golden/interest/happy_path/transact.expected} carries {@code 2024-01-15000001} at bytes 1
 * to 16, which that scenario's README documents at line 161. Normalising the interest identifier would
 * break golden-master parity on the one job the oracle pins byte for byte, so
 * {@code InterestCalculationService} reads the raw token and this method has nothing to do with it.</p>
 *
 * <p>Assumptions: the assertions themselves are unchanged and are all about the METHOD -- a compact
 * token returned byte for byte, a separated one normalised, ten digits always, and an unrecognised
 * layout refused rather than passed through. Only the reason they matter changed, so they are re-based
 * rather than rewritten; deleting a correct assertion because its stated motive was wrong would lose
 * coverage to a documentation fix.</p>
 *
 * <p>Assumptions: the hazard the withdrawn charter described was real and is closed elsewhere. A
 * date-prefixed identifier is not a number, the column is {@code CHAR(16)} and orders lexicographically,
 * so such a value became the stored maximum after one night's interest run and the interactive add and
 * bill-payment paths, which derived their next key from that maximum, failed on a numeric parse. Those
 * paths now allocate from {@code ledger.transaction_id_seq} and parse no stored identifier at all, which
 * is where the hazard is closed -- not here.</p>
 */
@DisplayName("the business-date dataset-key prefix")
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
     * Two spellings of one day resolve to one prefix, so one day addresses one generation.
     *
     * <p>Refactoring Rationale: this case asserted that a separated token composes a sixteen-digit
     * TRANSACTION IDENTIFIER, which production does not compose and must not -- the interest identifier
     * is the verbatim concatenation the golden master pins. What survives from it is the property that
     * actually matters for the method's real consumer, and it is now asserted directly: the two accepted
     * layouts of one day agree on the prefix. The previous formulation asserted that agreement only
     * implicitly, by naming one expected string, so a normalisation that mapped BOTH layouts to some
     * third value would have failed it for the right reason by accident rather than by design.</p>
     *
     * <p>Assumptions: the compact side of the comparison is the token the baseline's own driver injects,
     * so the agreement is with the reference's spelling of the day and not merely internal.</p>
     */
    @Test
    @DisplayName("resolves both accepted spellings of one day to the same prefix")
    void bothSpellingsOfOneDayResolveToOnePrefix() {
        String fromSeparated = new BusinessDate("2022-07-18").identifierPrefix();
        String fromCompact = new BusinessDate("2022071800").identifierPrefix();

        assertThat(fromSeparated)
                .as("one day, one dataset-key prefix, whichever layout the operator supplied")
                .isEqualTo(fromCompact)
                .isEqualTo("2022071800");
        assertThat(fromSeparated).hasSize(PREFIX_WIDTH).containsOnlyDigits();
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
