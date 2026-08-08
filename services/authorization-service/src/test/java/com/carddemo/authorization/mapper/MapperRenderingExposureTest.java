package com.carddemo.authorization.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.money.Money;
import com.carddemo.common.security.OpaqueIdentifier;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that no projection this context publishes discloses a protected value when it is rendered for
 * an operator, and that the diagnostic projection tokenises the one identifier it carries.
 *
 * <h2>Purpose</h2>
 *
 * <p>Refactoring Rationale: four nested projections declared no rendering of their own, so each fell back
 * to the compiler's, which prints every component. Between them that published account and customer
 * identifiers, four limit and balance amounts, two authorization totals, a cardholder's name, both of
 * their address lines, their telephone number, a merchant name, a queue address and the twenty-character
 * key of the item being processed when a failure occurred. Separately, the diagnostic projection's
 * structured-field map emitted that key VERBATIM: it was sanitized against log forging, which is a
 * different property from disclosure, and the reference program puts a primary account number, an account
 * identifier or a customer identifier there depending on which step failed. The normative rule is
 * {@code docs/architecture/observability.md}, under "What a toString() may render", and it requires every
 * owning module to carry a test of exactly this shape.</p>
 *
 * <p>Assumptions: each protected value is asserted absent by its own distinctive digits rather than by a
 * substring of a rendering, so the assertion fails on a disclosure however the rendering is formatted.
 * The fixture values are chosen not to collide -- no amount's digits appear inside an identifier and no
 * identifier's inside another -- because an assertion that a value is absent proves nothing if the same
 * digits arrive through a component that is legitimately rendered.</p>
 *
 * <p>Trade-offs: the renderings are asserted to CONTAIN the members that are permitted to remain, not
 * only to omit the ones that are not. A test that checked omission alone would pass on a rendering that
 * returned an empty string or the class name, which withholds the protected values and also withholds
 * everything that makes a diagnostic line worth writing -- and the rule's third part exists precisely to
 * say what should remain.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * at-clause; the convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
class MapperRenderingExposureTest {

    /** An account identifier no rendering may disclose. */
    private static final Long ACCOUNT_ID = 11122233344L;

    /** A customer identifier no rendering may disclose. */
    private static final Long CUSTOMER_ID = 987654321L;

    /** A cardholder name no rendering may disclose. */
    private static final String CUSTOMER_NAME = "ADA LOVELACE";

    /** A first address line no rendering may disclose. */
    private static final String ADDRESS_LINE_1 = "77 ANALYTICAL ENGINE WAY";

    /** A second address line no rendering may disclose. */
    private static final String ADDRESS_LINE_2 = "APARTMENT 5150";

    /** A telephone number no rendering may disclose. */
    private static final String PHONE_NUMBER = "206-555-0177";

    /** A merchant name no rendering may disclose, being acquirer-supplied free text. */
    private static final String MERCHANT_NAME = "ACME HARDWARE         ";

    /** A queue address no rendering may disclose, because it names the account and region it lives in. */
    private static final String REPLY_QUEUE_URL =
            "https://sqs.us-east-1.amazonaws.com/459123987456/carddemo-pauth-reply-prod.fifo";

    /** The diagnostic event key, which carries a primary account number for a card-step failure. */
    private static final String EVENT_KEY = "4000123456789010";

    /** The account status that a rendering is permitted to keep. */
    private static final String ACCOUNT_STATUS = "AC";

    /**
     * Builds key material long enough to key a tokeniser.
     *
     * <p>Assumptions: the material is fixed rather than random, so a token this test computes twice is the
     * same token. What is under test is that the key is TOKENISED and not that any particular token
     * results, so a deterministic key removes a source of flakiness without weakening the assertion.</p>
     *
     * @return key material of exactly the minimum accepted length; never {@code null}
     */
    private static byte[] fixedKeyMaterial() {
        byte[] material = new byte[OpaqueIdentifier.MIN_KEY_LENGTH];
        for (int index = 0; index < material.length; index++) {
            material[index] = (byte) (index + 1);
        }
        return material;
    }

    /**
     * Builds a summary segment carrying every protected value the type can hold.
     *
     * @return the segment under test; never {@code null}
     */
    private static PendingAuthSummaryMapper.SummarySegment segment() {
        return new PendingAuthSummaryMapper.SummarySegment(
                ACCOUNT_ID, CUSTOMER_ID, "P",
                ACCOUNT_STATUS, "  ", "  ", "  ", "  ",
                Money.of("5000.00"), Money.of("1500.00"),
                Money.of("4321.99"), Money.of("222.33"),
                Short.valueOf((short) 7), Short.valueOf((short) 2),
                Money.of("6543.21"), Money.of("111.22"));
    }

    /**
     * Builds a fraud row view carrying every protected value the type can hold.
     *
     * @return the view under test; never {@code null}
     */
    private static AuthFraudMapper.FraudRowView fraudRow() {
        return new AuthFraudMapper.FraudRowView(
                "************9010",
                LocalDateTime.of(2026, 8, 3, 9, 15, 0),
                "F", LocalDate.of(2026, 8, 6), "TXN000000000100",
                Money.of("250.00"), Money.of("250.00"),
                MERCHANT_NAME, ACCOUNT_ID, CUSTOMER_ID);
    }

    /**
     * Builds a diagnostic entry whose event key carries a primary account number.
     *
     * @return the entry under test; never {@code null}
     */
    private static AuthorizationMessageMapper.ErrorLogEntry diagnostic() {
        return new AuthorizationMessageMapper.ErrorLogEntry(
                "20260806", "091500", "CP00    ", "COPAUA0C", "0413", "C", "M",
                "000000012", "000000034", "AUTHORIZATION REQUEST COULD NOT BE DECODED",
                EVENT_KEY);
    }

    /**
     * Verifies the summary segment renders its status codes and no identifier, amount, limit or balance.
     */
    @Test
    @DisplayName("the summary segment renders status codes and no identifier, amount, limit or balance")
    void theSummarySegmentRendersStatusCodesOnly() {
        String rendered = segment().toString();

        assertThat(rendered).doesNotContain(String.valueOf(ACCOUNT_ID), String.valueOf(CUSTOMER_ID),
                "5000.00", "1500.00", "4321.99", "222.33", "6543.21", "111.22");
        assertThat(rendered).contains("authStatus=P", "accountStatus1=" + ACCOUNT_STATUS,
                "approvedAuthCount=7", "declinedAuthCount=2");
    }

    /**
     * Verifies the cardholder context renders the account status and nothing that identifies a person.
     */
    @Test
    @DisplayName("the cardholder context renders the account status and no name, address or telephone")
    void theCardholderContextRendersTheAccountStatusOnly() {
        String rendered = new PendingAuthSummaryMapper.CardholderContext(
                CUSTOMER_NAME, ADDRESS_LINE_1, ACCOUNT_STATUS, ADDRESS_LINE_2, PHONE_NUMBER)
                .toString();

        assertThat(rendered).doesNotContain(CUSTOMER_NAME, ADDRESS_LINE_1, ADDRESS_LINE_2,
                PHONE_NUMBER);
        assertThat(rendered).contains("accountStatus=" + ACCOUNT_STATUS);
    }

    /**
     * Verifies the fraud row renders the masked card, the dates and the transaction identifier only.
     *
     * <p>Assumptions: the masked card number is asserted PRESENT, which is the one sanctioned abbreviation
     * in the rendering rule and is sanctioned here because a fraud row has no other way to say which row
     * a line describes. The unmasked digits are asserted absent in the test below, where an unmasked value
     * is deliberately placed in the component.</p>
     */
    @Test
    @DisplayName("the fraud row renders the masked card, both dates and the transaction identifier")
    void theFraudRowRendersTheMaskedCardAndDatesOnly() {
        String rendered = fraudRow().toString();

        assertThat(rendered).doesNotContain(String.valueOf(ACCOUNT_ID), String.valueOf(CUSTOMER_ID),
                "250.00", MERCHANT_NAME.trim());
        assertThat(rendered).contains("************9010", "2026-08-03T09:15", "fraudMarker=F",
                "2026-08-06", "TXN000000000100");
    }

    /**
     * Verifies the fraud row masks a card number placed in the masked component unmasked.
     *
     * <p>Assumptions: this covers the component's contract being violated by a caller rather than by this
     * mapper. The component is a plain string, so its type cannot enforce that it arrives masked, and a
     * projection assembled somewhere other than this mapper could put a full number in it. Passing it
     * through the masker on the way out means the single sanctioned abbreviation cannot be bypassed.</p>
     */
    @Test
    @DisplayName("the fraud row masks a full card number placed in its masked component")
    void theFraudRowMasksAnUnmaskedComponent() {
        String rendered = new AuthFraudMapper.FraudRowView(
                "4000123456789010", LocalDateTime.of(2026, 8, 3, 9, 15, 0), "F",
                LocalDate.of(2026, 8, 6), "TXN000000000100", Money.of("250.00"),
                Money.of("250.00"), MERCHANT_NAME, ACCOUNT_ID, CUSTOMER_ID).toString();

        assertThat(rendered).doesNotContain("4000123456789010");
        assertThat(rendered).contains("9010");
    }

    /**
     * Verifies the reply routing renders the correlation identity and never the queue address.
     */
    @Test
    @DisplayName("the reply routing renders the correlation identity and never the queue address")
    void theReplyRoutingRendersTheCorrelationIdentityOnly() {
        String rendered = new AuthorizationMessageMapper.ReplyRouting(
                REPLY_QUEUE_URL, "corr-0001", LocalDateTime.of(2026, 8, 6, 9, 20, 5)).toString();

        assertThat(rendered).doesNotContain(REPLY_QUEUE_URL, "459123987456", "sqs.us-east-1");
        assertThat(rendered).contains("correlationId=corr-0001", "destinationPresent=true",
                "2026-08-06T09:20:05");
    }

    /**
     * Verifies the diagnostic rendering keeps its bounded fields and omits the event key.
     */
    @Test
    @DisplayName("the diagnostic rendering keeps its bounded fields and omits the event key")
    void theDiagnosticRenderingOmitsTheEventKey() {
        String rendered = diagnostic().toString();

        assertThat(rendered).doesNotContain(EVENT_KEY);
        assertThat(rendered).contains("errDate=20260806", "errTime=091500", "program=COPAUA0C",
                "level=C", "codeOne=000000012", "codeTwo=000000034");
    }

    /**
     * Verifies the structured projection omits the event key entirely.
     *
     * <p>Assumptions: the KEY name is asserted absent as well as the value. A projection that kept the
     * field and emptied it would report a failure as having no event key, which is a different statement
     * from having withheld one, and a reader diagnosing the failure would draw the wrong conclusion about
     * what the producer sent.</p>
     */
    @Test
    @DisplayName("the structured projection omits the event key, field name included")
    void theStructuredProjectionOmitsTheEventKey() {
        Map<String, String> fields = diagnostic().structuredFields();

        assertThat(fields).doesNotContainKey("eventKey");
        assertThat(fields.values()).noneMatch(value -> value.contains(EVENT_KEY));
        assertThat(fields).containsKeys("errDate", "errTime", "application", "program", "location",
                "level", "subsystem", "codeOne", "codeTwo", "message");
    }

    /**
     * Verifies the tokenising projection records the key as a token and never as itself.
     *
     * <p>Assumptions: the token is asserted to be stable across two calls and to differ from the token the
     * same value takes under another purpose. Stability is what makes two failures on one item joinable;
     * purpose separation is what stops a reader joining a diagnostic line to a queue message and
     * recovering the identifier that neither of them discloses.</p>
     */
    @Test
    @DisplayName("the tokenising projection records the key as a purpose-scoped token, never as itself")
    void theTokenisingProjectionRecordsAToken() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(fixedKeyMaterial());

        Map<String, String> fields = diagnostic().structuredFields(tokeniser);

        assertThat(fields).doesNotContainKey("eventKey");
        String token = fields.get("eventKeyToken");
        assertThat(token).isNotNull().doesNotContain(EVENT_KEY)
                .hasSize(OpaqueIdentifier.TOKEN_LENGTH)
                .isEqualTo(diagnostic().structuredFields(tokeniser).get("eventKeyToken"))
                .isNotEqualTo(tokeniser.token("carddemo/pauth/order-group", EVENT_KEY));
    }

    /**
     * Verifies a diagnostic with no event key yields no token entry rather than a token of nothing.
     */
    @Test
    @DisplayName("a diagnostic with no event key yields no token entry")
    void aDiagnosticWithNoEventKeyYieldsNoTokenEntry() {
        AuthorizationMessageMapper.ErrorLogEntry blankKey =
                new AuthorizationMessageMapper.ErrorLogEntry("20260806", "091500", "CP00    ",
                        "COPAUA0C", "0413", "C", "M", "000000012", "000000034", "DECODE FAILED",
                        "   ");

        assertThat(blankKey.structuredFields(new OpaqueIdentifier(fixedKeyMaterial())))
                .doesNotContainKeys("eventKey", "eventKeyToken");
    }

    /**
     * Verifies the tokenising projection refuses a null tokeniser rather than omitting the token.
     *
     * <p>Assumptions: refusal is required because a silently omitted token is indistinguishable from a
     * diagnostic whose key was absent, so a caller that failed to supply a tokeniser would lose the ability
     * to join failures on one item without anything reporting the loss.</p>
     */
    @Test
    @DisplayName("the tokenising projection refuses a null tokeniser")
    void theTokenisingProjectionRefusesANullTokeniser() {
        assertThatThrownBy(() -> diagnostic().structuredFields(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tokeniser");
    }
}
