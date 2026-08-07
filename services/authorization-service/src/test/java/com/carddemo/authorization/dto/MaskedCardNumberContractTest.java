package com.carddemo.authorization.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.money.Money;
import com.carddemo.common.web.CursorToken;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Exhausts every position at which a card number can fail the masked form both response bodies publish.
 *
 * <h2>Purpose</h2>
 * <p>Refactoring Rationale: the detail view previously carried its OWN restatement of the masked-form
 * rule, and that restatement checked only the total length and the first character. A value such as
 * {@code *123456789012345} therefore satisfied it while disclosing fifteen of the sixteen digits on a
 * detail body -- a leak the list body's stricter rule already refused. Sharing one validator removed the
 * disagreement; this class is what keeps it removed. Every one of the sixteen positions is exercised
 * individually and against BOTH record types, so a future narrowing of either side fails here rather than
 * on a response.</p>
 *
 * <p>Alternatives Considered: asserting only the historical bypass string was rejected. That single case
 * proves the specific leak is closed but says nothing about the other fifteen positions, and the original
 * defect was precisely a rule that happened to cover position one and nothing else. Enumerating all
 * sixteen is what turns "the known leak is closed" into "no position can leak".</p>
 *
 * <p>Assumptions: the two record types are exercised through their public constructors rather than through
 * the shared validator directly, because the constructor is the boundary a mapper and a deserialiser both
 * cross. Calling the validator alone would keep passing after either constructor stopped invoking it,
 * which is the regression that matters.</p>
 *
 * <p>Trade-offs: the class is annotated {@code PER_CLASS} so the sealer is built once. A sealed selector
 * is required by both constructors and building it per test would repeat a key derivation for no
 * assertion; the sealer holds no per-test state, so sharing it cannot leak one case into another.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaskedCardNumberContractTest {

    /**
     * A correctly masked card number: twelve mask characters followed by four digits.
     */
    private static final String MASKED = "*".repeat(12) + "2345";

    /**
     * The exact value the superseded detail-view rule admitted while disclosing fifteen digits.
     */
    private static final String HISTORICAL_BYPASS = "*123456789012345";

    /**
     * The selector binding, which any constant satisfies because nothing here reopens the token.
     */
    private static final String BINDING = "pending-auth-row";

    /**
     * A sealed row selector, required by both constructors before the card number is examined.
     */
    private final String sealedKey = sealer().seal(BINDING, "00000000001|2026015|14300000");

    /**
     * Builds a sealer over deterministic key material.
     *
     * <p>Assumptions: the key material is fixed rather than random so a failure is reproducible.
     * Nothing here asserts a token's TEXT, only that a genuinely sealed selector is supplied, so the
     * particular bytes are immaterial.</p>
     *
     * @return a sealer whose tokens satisfy {@link CursorToken#hasSealedShape(String)}; never
     *     {@code null}
     */
    private static CursorToken sealer() {
        byte[] keyMaterial = new byte[CursorToken.MIN_KEY_LENGTH];
        for (int index = 0; index < keyMaterial.length; index++) {
            keyMaterial[index] = (byte) (index + 1);
        }
        return new CursorToken(keyMaterial, Duration.ofMinutes(5));
    }

    /**
     * Returns an action that constructs a list row carrying a supplied card number.
     *
     * @return an action that performs the construction, so a caller can assert on what it throws
     */
    private Consumer<String> rowConstructor() {
        return cardNum -> new PendingAuthRowView(this.sealedKey, "000000000000001", "2026-01-15",
                "14:30:00", "01", PendingAuthRowView.APPROVAL_STATUS_APPROVED, "P",
                Money.of("125.50"), cardNum);
    }

    /**
     * Returns an action that constructs a detail body carrying a supplied card number.
     *
     * @return an action that performs the construction, so a caller can assert on what it throws
     */
    private Consumer<String> detailConstructor() {
        return cardNum -> new PendingAuthDetailView(this.sealedKey, "00000000001", 2026015, 14300000,
                "2026-01-15", "14:30:00", cardNum, "01", "2812", "0100", "POS", "143000", "00",
                "0000", "000000", Money.of("125.50"), Money.of("125.50"), "5411", "840", "05",
                "000000000", "CORNER STORE", "SEATTLE", "WA", "981010000", "000000000000001",
                "P", null, null);
    }

    /**
     * Returns the two constructors under test, paired with the name used in a failure message.
     *
     * @return the list-row and detail-body constructors, keyed by record name; never {@code null}
     */
    private List<Object[]> bothConstructors() {
        List<Object[]> constructors = new ArrayList<>();
        constructors.add(new Object[] {"PendingAuthRowView", rowConstructor()});
        constructors.add(new Object[] {"PendingAuthDetailView", detailConstructor()});
        return constructors;
    }

    /**
     * Applies one constructor and asserts it refuses the value.
     *
     * @param recordName the record name, used to attribute a failure to the right side
     * @param constructor the construction action
     * @param candidate the card number that must be refused
     */
    private static void assertRefused(String recordName, Consumer<String> constructor,
            String candidate) {
        assertThatThrownBy(() -> constructor.accept(candidate))
                .as(recordName + " must refuse \"" + candidate + "\"")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies a correctly masked value is accepted by both records, so the guard is not simply closed.
     */
    @Test
    @DisplayName("a correctly masked card number is accepted by both records")
    void aCorrectlyMaskedValueIsAccepted() {
        for (Object[] pair : bothConstructors()) {
            @SuppressWarnings("unchecked")
            Consumer<String> constructor = (Consumer<String>) pair[1];
            assertThatCode(() -> constructor.accept(MASKED))
                    .as(pair[0] + " must accept a correctly masked value")
                    .doesNotThrowAnyException();
        }
    }

    /**
     * Verifies a disclosed digit at any of the twelve masked positions is refused, position by position.
     *
     * <p>Assumptions: the substituted character is a DIGIT rather than an arbitrary symbol, because a
     * disclosed digit is the value that actually leaks. A rule that refused a letter but admitted a digit
     * would pass a symbol-based test while leaking every real card number.</p>
     */
    @Test
    @DisplayName("a disclosed digit at any of the twelve masked positions is refused")
    void aDisclosedDigitAtEveryMaskedPositionIsRefused() {
        int maskedPrefix = PendingAuthRowView.MASKED_CARD_LENGTH
                - PendingAuthRowView.VISIBLE_CARD_DIGITS;

        for (int index = 0; index < maskedPrefix; index++) {
            String candidate = MASKED.substring(0, index) + '7' + MASKED.substring(index + 1);

            assertThat(candidate)
                    .as("the probe must keep the declared width so length is not what refuses it")
                    .hasSize(PendingAuthRowView.MASKED_CARD_LENGTH);
            for (Object[] pair : bothConstructors()) {
                @SuppressWarnings("unchecked")
                Consumer<String> constructor = (Consumer<String>) pair[1];
                assertRefused((String) pair[0], constructor, candidate);
            }
        }
    }

    /**
     * Verifies the refusal names the offending one-based position rather than echoing the value.
     *
     * <p>Assumptions: the message must locate the fault without reproducing the card number, because the
     * message reaches a log. Asserting the position is present AND the candidate is absent pins both
     * halves of that requirement.</p>
     */
    @Test
    @DisplayName("the refusal names the offending position and never echoes the value")
    void theRefusalNamesThePositionAndNotTheValue() {
        String disclosedAtFifth = MASKED.substring(0, 4) + '7' + MASKED.substring(5);

        assertThatThrownBy(() -> rowConstructor().accept(disclosedAtFifth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("position 5")
                .hasMessageNotContaining(disclosedAtFifth);
    }

    /**
     * Verifies a non-digit at any of the four visible positions is refused, position by position.
     *
     * <p>Assumptions: the visible tail is checked as well as the masked prefix because the tail is the
     * part a screen reads. A letter there would render a value the reference cannot produce, so admitting
     * it would publish a body that no consumer can interpret.</p>
     */
    @Test
    @DisplayName("a non-digit at any of the four visible positions is refused")
    void aNonDigitAtEveryVisiblePositionIsRefused() {
        int maskedPrefix = PendingAuthRowView.MASKED_CARD_LENGTH
                - PendingAuthRowView.VISIBLE_CARD_DIGITS;

        for (int index = maskedPrefix; index < PendingAuthRowView.MASKED_CARD_LENGTH; index++) {
            for (char intruder : new char[] {'*', 'X', ' ', '-'}) {
                String candidate = MASKED.substring(0, index) + intruder + MASKED.substring(index + 1);

                for (Object[] pair : bothConstructors()) {
                    @SuppressWarnings("unchecked")
                    Consumer<String> constructor = (Consumer<String>) pair[1];
                    assertRefused((String) pair[0], constructor, candidate);
                }
            }
        }
    }

    /**
     * Verifies the historical bypass is refused by both records.
     *
     * <p>Assumptions: this exact string is asserted by name because it is the value the superseded rule
     * admitted. Keeping it as a named case means the specific regression is reported as itself rather
     * than as one anonymous position among sixteen.</p>
     */
    @Test
    @DisplayName("the value the superseded rule admitted is refused by both records")
    void theHistoricalBypassIsRefused() {
        assertThat(HISTORICAL_BYPASS)
                .as("the bypass had the right length and the right first character, which is why it passed")
                .hasSize(PendingAuthRowView.MASKED_CARD_LENGTH)
                .startsWith("*");

        for (Object[] pair : bothConstructors()) {
            @SuppressWarnings("unchecked")
            Consumer<String> constructor = (Consumer<String>) pair[1];
            assertRefused((String) pair[0], constructor, HISTORICAL_BYPASS);
        }
    }

    /**
     * Verifies a fully unmasked card number is refused by both records.
     */
    @Test
    @DisplayName("a fully unmasked card number is refused by both records")
    void aFullyUnmaskedValueIsRefused() {
        for (Object[] pair : bothConstructors()) {
            @SuppressWarnings("unchecked")
            Consumer<String> constructor = (Consumer<String>) pair[1];
            assertRefused((String) pair[0], constructor, "4111111111112345");
        }
    }

    /**
     * Verifies a value shorter or longer than the declared width is refused by both records.
     */
    @Test
    @DisplayName("a value of the wrong width is refused by both records")
    void aValueOfTheWrongWidthIsRefused() {
        List<String> wrongWidths = List.of("", "*".repeat(11) + "2345", "*".repeat(13) + "2345",
                "*".repeat(12) + "234", "*".repeat(12) + "23456");

        for (String candidate : wrongWidths) {
            for (Object[] pair : bothConstructors()) {
                @SuppressWarnings("unchecked")
                Consumer<String> constructor = (Consumer<String>) pair[1];
                assertRefused((String) pair[0], constructor, candidate);
            }
        }
    }

    /**
     * Verifies an absent card number is refused as a missing required component by both records.
     */
    @Test
    @DisplayName("an absent card number is refused as missing by both records")
    void anAbsentValueIsRefused() {
        for (Object[] pair : bothConstructors()) {
            @SuppressWarnings("unchecked")
            Consumer<String> constructor = (Consumer<String>) pair[1];
            assertThatThrownBy(() -> constructor.accept(null))
                    .as(pair[0] + " must refuse an absent card number")
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("cardNum");
        }
    }
}
