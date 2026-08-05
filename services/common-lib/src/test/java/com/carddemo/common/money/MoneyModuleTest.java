package com.carddemo.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the JSON wire form of a monetary amount against the Jackson release this build resolves.
 *
 * <p>Purpose: assert that {@link MoneyModule} renders an amount as a JSON <em>string</em> and
 * refuses a JSON number, in both directions, using a real mapper rather than a stubbed generator.
 * The migration carries a hard rule that money never leaves fixed point and travels as a string
 * precisely so that no client parses it into an IEEE-754 double, and this class is where that rule
 * is checked end to end.
 *
 * <p>Refactoring Rationale: this class was added when the Jackson floors in {@code services/pom.xml}
 * were raised to clear published advisories. Before it, nothing in the build exercised the module
 * through a mapper at all, so a Jackson change that altered how a string-writing serialiser or a
 * token-inspecting deserialiser behaves would have produced a green build and a ledger that
 * transported amounts as bare numbers. A dependency floor is only safe to raise if the contract that
 * rides on that dependency is asserted, so the assertion is part of raising it.
 *
 * <p>Alternatives Considered: asserting only that a mapper round-trips a value back to an equal
 * amount. Rejected because a round-trip through a JSON number would also succeed here, since decimal
 * parsing on this side is exact. Round-tripping therefore proves nothing about the property that
 * matters, which is the token TYPE on the wire, so the tests below assert the emitted text and the
 * refusal of a number token explicitly.
 *
 * <p>Assumptions: the mapper is constructed with only this module registered, not obtained from a
 * Spring context. The contract belongs to the module rather than to any service's configuration, and
 * a test that needed a context could pass because some other component happened to coerce the value.
 */
final class MoneyModuleTest {

    /**
     * Builds a mapper whose only configured behaviour is the money contract under test.
     *
     * @return a mapper with {@link MoneyModule} registered, never {@code null}
     */
    private static ObjectMapper mapper() {
        return JsonMapper.builder().addModule(new MoneyModule()).build();
    }

    /** Asserts the emitted token is a quoted string rather than a bare JSON number. */
    @Test
    @DisplayName("an amount serialises as a quoted string, never as a bare JSON number")
    void serialisesAsQuotedString() {
        String json = mapper().writeValueAsString(Money.of("1234567890.12"));

        // WHY : Assumptions: the assertion is on the quoting, not merely on the digits. A bare
        //       number would contain the same digits, so a digits-only assertion would pass for
        //       exactly the output this contract exists to prevent.
        assertThat(json).isEqualTo("\"1234567890.12\"");
    }

    /**
     * Asserts the wire form is quoted plain decimal text for signs and boundaries alike.
     *
     * @param amount the plain decimal text to round-trip, supplied by the value source
     */
    @ParameterizedTest
    @ValueSource(strings = {"0.00", "0.01", "-0.01", "123.45", "-123.45", "9999999999.99"})
    @DisplayName("every amount, including negatives and boundaries, is quoted plain decimal text")
    void serialisesEveryAmountAsPlainQuotedText(String amount) {
        String json = mapper().writeValueAsString(Money.of(amount));

        assertThat(json).isEqualTo("\"" + amount + "\"");
        // WHY : Assumptions: exponent notation is excluded explicitly. The general decimal text
        //       conversion is permitted to emit it at some scales, and the reference wire form has
        //       no exponent, so a serialiser that silently switched conversions would still produce
        //       correct digits and an unparseable field for the mainframe-derived consumers.
        assertThat(json).doesNotContain("E").doesNotContain("e");
    }

    /** Asserts a quoted decimal string is read back at the exact value and scale two. */
    @Test
    @DisplayName("a quoted decimal string deserialises to the exact same amount")
    void deserialisesQuotedStringExactly() {
        Money parsed = mapper().readValue("\"1234567890.12\"", Money.class);

        assertThat(parsed.amount()).isEqualByComparingTo(new BigDecimal("1234567890.12"));
        assertThat(parsed.amount().scale()).isEqualTo(2);
    }

    /** Asserts a number token is refused, which is what makes the string form enforceable. */
    @Test
    @DisplayName("a bare JSON number is refused rather than silently accepted")
    void refusesJsonNumber() {
        // WHY : Assumptions: a number token is refused even though parsing it here would be exact.
        //       The refusal is what makes the string form enforceable: a producer that emitted a
        //       number and was never told would keep emitting one, and at this end an amount that
        //       survived exactly is indistinguishable from one its producer already rounded.
        //       Refactoring Rationale: the assertion names the exception type and the reason rather
        //       than accepting any Exception, which is how it was first written. A bare
        //       any-exception assertion passes when the mapper fails for an unrelated cause, so it
        //       would have kept reporting success if the refusal were replaced by, say, a module
        //       registration error, and the contract would have been unguarded exactly when it
        //       looked guarded.
        assertThatThrownBy(() -> mapper().readValue("1234567890.12", Money.class))
                .isInstanceOf(MismatchedInputException.class)
                .hasMessageContaining("JSON number");
    }

    /** Asserts the contract also holds for money carried as an object property. */
    @Test
    @DisplayName("an amount nested in an object is still quoted")
    void serialisesNestedAmountAsQuotedString() {
        // WHY : Assumptions: the nested case is asserted separately because a serialiser can be
        //       reached through a different code path as a property value than as a root value, and
        //       every real payload in this migration carries money as a property rather than alone.
        String json = mapper().writeValueAsString(Map.of("balance", Money.of("-42.00")));

        assertThat(json).isEqualTo("{\"balance\":\"-42.00\"}");
    }
}
