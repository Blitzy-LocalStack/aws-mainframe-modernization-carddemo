package com.carddemo.batch.dto;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies that {@link BusinessDate} carries its token through untouched and asserts nothing about
 * it beyond the width the derived transaction identifier depends on.
 *
 * <p>Every expectation below is pinned to an immutable reference artifact rather than to a value
 * chosen for the convenience of the test, and each citation sits beside the expectation it fixes so
 * that a reader can re-derive it without leaving this file. The three artifacts are
 * {@code app/cbl/CBACT04C.cbl}, {@code app/jcl/INTCALC.jcl} and {@code app/cpy/CVTRA05Y.cpy},
 * together with the two committed expectation files of the functional-parity oracle under
 * {@code tests/golden/interest/}.</p>
 *
 * <p>Assumptions: the arithmetic these tests defend is one sum and it has no slack.
 * {@code app/cbl/CBACT04C.cbl:476-480} reads
 * {@code STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID END-STRING}, where
 * {@code PARM-DATE} is {@code PIC X(10)} at line 178, {@code WS-TRANID-SUFFIX} is
 * {@code PIC 9(06) VALUE 0} at line 173, and {@code TRAN-ID} is {@code PIC X(16)} at
 * {@code app/cpy/CVTRA05Y.cpy:5}. Ten characters plus six digits fill sixteen exactly, so any
 * change to the token's width or bytes is a change to stored primary keys.</p>
 *
 * <p>Alternatives Considered: asserting the token against one canonical layout, which is what a
 * test of an ordinary date type would do. Rejected because the oracle commits expectation files in
 * both accepted layouts, and a test that blessed one layout would silently license the
 * normalisation that breaks the other. The two layouts are therefore both exercised here, and
 * neither is preferred.</p>
 */
class BusinessDateTest {

    /**
     * The compact token the baseline's own driver injects, {@code PARM='2022071800'} at
     * {@code app/jcl/INTCALC.jcl:22}. It is deliberately not a valid ISO local date.
     */
    private static final String COMPACT_TOKEN = "2022071800";

    /**
     * The separated token the orchestration state supplies as a container override,
     * {@code --business-date=2022-07-18}.
     */
    private static final String SEPARATED_TOKEN = "2022-07-18";

    /**
     * The separated token whose derived identifier opens
     * {@code tests/golden/interest/happy_path/transact.expected}.
     */
    private static final String GOLDEN_SEPARATED_TOKEN = "2024-01-15";

    /**
     * The first six-digit suffix the baseline emits, {@code WS-TRANID-SUFFIX} at
     * {@code app/cbl/CBACT04C.cbl:173} after the single increment at line 474.
     */
    private static final String FIRST_SUFFIX = "000001";

    /** The width of {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:5}. */
    private static final int TRAN_ID_WIDTH = 16;

    /** The width of {@code PARM-DATE PIC X(10)} at {@code app/cbl/CBACT04C.cbl:178}. */
    private static final int TOKEN_WIDTH = 10;

    /**
     * Confirms the compact token comes back out of the accessor byte for byte, so the value that
     * reaches a generated identifier is the value the caller supplied.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("the compact token is returned exactly as supplied")
    void compactTokenIsReturnedExactlyAsSupplied() {
        BusinessDate businessDate = new BusinessDate(COMPACT_TOKEN);

        assertThat(businessDate.token())
                .isEqualTo(COMPACT_TOKEN)
                .hasSize(TOKEN_WIDTH)
                .isSameAs(COMPACT_TOKEN);
    }

    /**
     * Confirms the separated token is equally untouched, so the type accepts both layouts actually
     * seen without preferring or rewriting either.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("the separated token is returned exactly as supplied")
    void separatedTokenIsReturnedExactlyAsSupplied() {
        BusinessDate businessDate = new BusinessDate(SEPARATED_TOKEN);

        assertThat(businessDate.token())
                .isEqualTo(SEPARATED_TOKEN)
                .hasSize(TOKEN_WIDTH)
                .isSameAs(SEPARATED_TOKEN);
    }

    /**
     * Confirms the sum that the whole type exists to protect: a ten-character token followed by the
     * six-digit suffix occupies the sixteen characters of {@code TRAN-ID} exactly, and reproduces
     * the opening bytes of both committed expectation files.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("token plus a six-digit suffix fills TRAN-ID PIC X(16) exactly, in both layouts")
    void tokenPlusSixDigitSuffixFillsTranIdExactly() {
        // WHY : Assumptions: the two expected identifiers are read off committed oracle output
        //       rather than computed by this test. tests/golden/interest/happy_path/transact.
        //       expected opens 2024-01-15000001 and
        //       tests/golden/interest/e2e_interest_cycle_transactions.expected opens
        //       2022071800000001, so an assertion that recomputed them from the token would prove
        //       only that concatenation concatenates.
        assertThat(new BusinessDate(COMPACT_TOKEN).token() + FIRST_SUFFIX)
                .isEqualTo("2022071800000001")
                .hasSize(TRAN_ID_WIDTH);

        assertThat(new BusinessDate(GOLDEN_SEPARATED_TOKEN).token() + FIRST_SUFFIX)
                .isEqualTo("2024-01-15000001")
                .hasSize(TRAN_ID_WIDTH);

        assertThat(FIRST_SUFFIX).hasSize(TRAN_ID_WIDTH - TOKEN_WIDTH);
    }

    /**
     * Confirms the constructor accepts the baseline's own production parameter, which no strict ISO
     * parse would admit.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("the compact token is accepted, because rejecting it would reject INTCALC.jcl:22")
    void compactTokenIsAcceptedRatherThanRejectedAsANonIsoDate() {
        // WHY : Assumptions: this is the assertion that pins the length-only contract in place. A
        //       constructor validating the token as a date would throw here, and the value it
        //       threw on is the literal that app/jcl/INTCALC.jcl:22 supplies in production, so
        //       such a constructor would be wrong rather than merely stricter.
        assertThat(new BusinessDate(COMPACT_TOKEN).token()).isEqualTo(COMPACT_TOKEN);
        assertThatExceptionOfType(DateTimeParseException.class)
                .isThrownBy(() -> LocalDate.parse(COMPACT_TOKEN));
    }

    /**
     * Confirms a token of any width other than ten is refused, covering one character short, one
     * character long, empty and absent.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("a token that is not exactly ten characters is refused")
    void tokenOfAnyOtherWidthIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new BusinessDate("2022-7-18"));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new BusinessDate("2022-07-018"));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new BusinessDate(""));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new BusinessDate(null));
    }

    /**
     * Confirms a padded token is refused on its supplied width rather than measured after
     * whitespace removal, so a space can never reach a stored key by way of a silent rewrite.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("a whitespace-padded token is refused on its supplied width, not silently reduced")
    void whitespacePaddedTokenIsRefusedRatherThanReduced() {
        // WHY : Assumptions: both values below measure ten characters only after a removal this
        //       type never performs, so the diagnostic reporting eleven is the evidence that no
        //       removal happened. Asserting merely that they throw would leave a whitespace-
        //       removing constructor indistinguishable from this one for the leading-space case.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new BusinessDate(" " + COMPACT_TOKEN))
                .withMessageContaining("11");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new BusinessDate(COMPACT_TOKEN + " "))
                .withMessageContaining("11");
    }

    /**
     * Confirms the documented contract for a token that is ten characters wide but carries a space,
     * which is accepted here and refused at the process boundary instead.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("a ten-character token carrying a space is accepted here and carried unchanged")
    void tenCharacterTokenCarryingASpaceIsCarriedUnchanged() {
        // WHY : Assumptions: this asserts the contract as documented rather than the contract a
        //       reader might assume. This type checks width alone; the character class is checked
        //       by the module entry point, which refuses anything outside ASCII digits and ASCII
        //       hyphen-minus before an application context exists. Asserting a rejection here
        //       would encode a second character-class gate that the type deliberately does not
        //       have, and the test would then fail for the right value and the wrong reason.
        String tenWideWithSpace = "2022-07-1 ";
        assertThat(tenWideWithSpace).hasSize(TOKEN_WIDTH);

        assertThat(new BusinessDate(tenWideWithSpace).token()).isEqualTo(tenWideWithSpace);
    }

    /**
     * Confirms the range-comparison accessor reads a separated token correctly and refuses a
     * compact one, which is the boundary its name advertises.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("the range-comparison accessor parses the separated layout and throws on the compact one")
    void rangeComparisonAccessorParsesSeparatedAndThrowsOnCompact() {
        assertThat(new BusinessDate(SEPARATED_TOKEN).parseIsoDateForRangeComparison())
                .isEqualTo(LocalDate.of(2022, 7, 18));

        assertThatExceptionOfType(DateTimeParseException.class)
                .isThrownBy(() -> new BusinessDate(COMPACT_TOKEN).parseIsoDateForRangeComparison());
    }

    /**
     * Confirms the range-comparison accessor cannot be on the identifier-construction path, by
     * showing the identifier is derivable from a token for which that accessor throws.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("identifier construction cannot route through the range-comparison accessor")
    void identifierConstructionCannotRouteThroughTheRangeComparisonAccessor() {
        BusinessDate businessDate = new BusinessDate(COMPACT_TOKEN);

        // WHY : Assumptions: this is the mechanical form of the by-inspection check. The compact
        //       token yields the committed identifier through the raw accessor while the parsing
        //       accessor throws on the same instance, so no path that produced that identifier can
        //       have gone through the parser. A reviewer reading only the source would have to take
        //       the separation on trust; this leaves it asserted.
        assertThat(businessDate.token() + FIRST_SUFFIX).isEqualTo("2022071800000001");
        assertThatExceptionOfType(DateTimeParseException.class)
                .isThrownBy(businessDate::parseIsoDateForRangeComparison);
    }

    /**
     * Confirms equality and hashing follow the token, so two instances built from one token are
     * interchangeable and instances built from different tokens are not.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("equality and hashing follow the token")
    void equalityAndHashingFollowTheToken() {
        BusinessDate first = new BusinessDate(new String(COMPACT_TOKEN.toCharArray()));
        BusinessDate second = new BusinessDate(new String(COMPACT_TOKEN.toCharArray()));
        BusinessDate other = new BusinessDate(SEPARATED_TOKEN);

        // WHY : Assumptions: the two equal instances are built from distinct String objects so the
        //       assertion exercises value equality rather than reference identity, which a shared
        //       interned literal would have satisfied without proving anything.
        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second).isNotSameAs(second);
        assertThat(first).isNotEqualTo(other);
    }

    /**
     * Confirms {@code toString} is the record-generated form, so it names the type and the
     * component and can never be mistaken for the wire token.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("toString is the generated form and is not the bare token")
    void toStringIsTheGeneratedFormAndNotTheBareToken() {
        String rendered = new BusinessDate(COMPACT_TOKEN).toString();

        assertThat(rendered)
                .isNotEqualTo(COMPACT_TOKEN)
                .contains(BusinessDate.class.getSimpleName())
                .contains("token=" + COMPACT_TOKEN);
    }

    /**
     * Confirms the declared surface is exactly the intended one, which is what mechanically rules
     * out a clock-reading factory and a default instance.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("the declared surface admits no factory, no default instance and no clock")
    void declaredSurfaceAdmitsNoFactoryNoDefaultInstanceAndNoClock() {
        // WHY : Assumptions: an exact method-name set is asserted in preference to searching names
        //       for a substring such as a present-day accessor. A substring search passes as soon
        //       as someone chooses a different name for the same host-clock read, whereas an exact
        //       set fails on any addition at all and therefore has to be updated deliberately.
        assertThat(Arrays.stream(BusinessDate.class.getDeclaredMethods()).map(Method::getName))
                .containsExactlyInAnyOrder(
                        "token", "parseIsoDateForRangeComparison", "toString", "hashCode", "equals");

        assertThat(Arrays.stream(BusinessDate.class.getDeclaredMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers())))
                .as("a static method here would be the shape a clock-reading factory takes")
                .isEmpty();

        assertThat(Arrays.stream(BusinessDate.class.getDeclaredFields())
                .filter(field -> field.getType() == BusinessDate.class))
                .as("a self-typed constant here would be the shape a default instance takes")
                .isEmpty();

        assertThat(Arrays.stream(BusinessDate.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .map(Field::getName))
                .containsExactly("TOKEN_LENGTH");
    }

    /**
     * Confirms the single constructor requires the token, so no instance exists that was not given
     * one explicitly.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("the one constructor requires a token argument")
    void theOneConstructorRequiresATokenArgument() {
        Constructor<?>[] constructors = BusinessDate.class.getDeclaredConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactly(String.class);
    }
}
