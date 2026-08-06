package com.carddemo.authorization.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CsvAuthCodec;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the committed request-wire fixtures to the one wire width this migration publishes.
 *
 * <h2>The contradiction this test closes</h2>
 *
 * <p>Refactoring Rationale: two fixtures described two different wires and neither was referenced by
 * any test, so nothing contradicted either -- which is how a repository ends up with two normative
 * statements about one contract. One carried three payloads of
 * {@value CsvAuthCodec#REQUEST_WIRE_LENGTH} bytes with a thirteen-character amount; the other carried a
 * single payload one byte longer, with the amount at the width its copybook declares, and its FILE NAME
 * asserted that the wider form was canonical. The wider file is kept, because the payload it holds is
 * one a real producer can emit, but it is renamed to describe the payload instead of claiming an
 * authority, and this test states what each file actually proves.</p>
 *
 * <h2>Why the emitted width is thirteen and not fourteen</h2>
 *
 * <p>Assumptions: the copybook declares the field wider than the reference receiver can hold, and the
 * receiver is what settles the emitted width. {@code PA-RQ-TRANSACTION-AMT PIC +9(10).99} at line 27 of
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy} is fourteen characters, so the declared
 * widths plus their seventeen delimiters derive 170. But the only program that reads this wire,
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}, declares its receiving item
 * {@code WS-TRANSACTION-AMT-AN PIC X(13)} at line 63, unstrings ordinal nine into it at line 364 and
 * converts it at lines 376 to 377. An alphanumeric move into a shorter item drops the LAST character --
 * the second cents digit -- so a producer emitting the declared width has its amount received as one
 * tenth of the value it sent, with nothing raised anywhere. This codec therefore EMITS thirteen and its
 * canonical wire length is {@value CsvAuthCodec#REQUEST_WIRE_LENGTH}.</p>
 *
 * <h2>Why the wider token is nonetheless read, and read in full</h2>
 *
 * <p>Trade-offs: this codec is a tolerant reader in one direction and a strict writer in the other. It
 * accepts a fourteen-character token and parses every character of it, so a producer built from the
 * copybook alone is understood rather than misread; and it never emits one, so the reference receiver is
 * never handed a token it would truncate. Refusing the wider token instead was considered and rejected:
 * it would dead-letter a payload whose intent is unambiguous, purely because a third party built to the
 * declaration this repository publishes as the layout. Reproducing the truncation was rejected outright,
 * because it would silently divide an amount by ten. Reading it in full is a divergence from the
 * reference receiver and is registered as {@code D-AUTH-AMOUNT-TOLERANT-READ} in section 7.4 of
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 */
class AuthRequestWireFixtureTest {

    /** Classpath location of the payloads that conform to the published wire. */
    private static final String CONFORMING_FIXTURE = "/fixtures/auth-request-amount-variants.csv";

    /** Classpath location of the payload whose amount is at the copybook's declared width. */
    private static final String DECLARED_WIDTH_FIXTURE =
            "/fixtures/auth-request-copybook-wire170-decode-only.csv";

    /**
     * The wire length a payload built at the copybook's declared amount width reaches.
     *
     * <p>Assumptions: one more byte than the published length, because exactly one field differs and it
     * differs by exactly one character. Stating it as an offset from the published constant rather than
     * as a bare 170 keeps the two figures from being read as independent facts.
     */
    private static final int DECLARED_WIDTH_WIRE_LENGTH = CsvAuthCodec.REQUEST_WIRE_LENGTH + 1;

    /**
     * Confirms every conforming fixture line is exactly the published wire length and decodes.
     *
     * <p>Assumptions: the length is asserted as well as the decode, because a payload can decode while
     * being the wrong length if a field is short and another is long. Asserting both pins the wire and
     * not merely the parse.</p>
     *
     * @throws IOException if the fixture cannot be read, which means it is absent from the classpath
     */
    @Test
    @DisplayName("every conforming fixture line is the published wire length and decodes")
    void conformingFixtureLinesAreThePublishedWireLength() throws IOException {
        List<String> payloads = readLines(CONFORMING_FIXTURE);

        assertThat(payloads).isNotEmpty();
        for (String payload : payloads) {
            assertThat(payload.length())
                    .as("a conforming payload is exactly the published wire length")
                    .isEqualTo(CsvAuthCodec.REQUEST_WIRE_LENGTH);
            assertThat(payload.split(",", -1)[CsvAuthCodec.REQUEST_AMOUNT_ORDINAL].length())
                    .as("the amount token is the width the receiving item can hold")
                    .isEqualTo(CsvAuthCodec.REQUEST_MONEY_WIDTH);
            assertThat(CsvAuthCodec.decodeRequest(payload).transactionAmount()).isNotNull();
        }
    }

    /**
     * Confirms the fixture's three amount vectors decode to the values they spell.
     *
     * <p>Assumptions: the three are a negative amount, the largest magnitude the ten integer digits and
     * two decimals admit, and zero -- the boundaries a money token can carry rather than three arbitrary
     * values. Asserting the decoded numbers rather than only that decoding succeeded is what would catch
     * a sign convention read the wrong way round.</p>
     *
     * @throws IOException if the fixture cannot be read, which means it is absent from the classpath
     */
    @Test
    @DisplayName("the three amount vectors decode to the negative, maximum and zero values they spell")
    void amountVectorsDecodeToTheValuesTheySpell() throws IOException {
        List<String> payloads = readLines(CONFORMING_FIXTURE);

        assertThat(payloads).hasSize(3);
        assertThat(CsvAuthCodec.decodeRequest(payloads.get(0)).transactionAmount().amount())
                .isEqualByComparingTo(new BigDecimal("-250.00"));
        assertThat(CsvAuthCodec.decodeRequest(payloads.get(1)).transactionAmount().amount())
                .isEqualByComparingTo(new BigDecimal("9999999999.99"));
        assertThat(CsvAuthCodec.decodeRequest(payloads.get(2)).transactionAmount().amount())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * Confirms the declared-width fixture is one byte longer than the wire and reads in full.
     *
     * <p>Assumptions: both halves are asserted together because either alone would be misleading. That
     * the payload is 170 bytes records the copybook derivation; that it decodes to the value it spells
     * records the tolerant read. A fixture asserting only the first would look like a claim that 170 is
     * the wire length, which is the claim its former file name made and this test exists to retire.</p>
     *
     * @throws IOException if the fixture cannot be read, which means it is absent from the classpath
     */
    @Test
    @DisplayName("the declared-width payload is one byte longer than the wire and reads in full")
    void declaredWidthPayloadReadsInFull() throws IOException {
        List<String> payloads = readLines(DECLARED_WIDTH_FIXTURE);

        assertThat(payloads).hasSize(1);
        String declaredWidth = payloads.get(0);

        assertThat(declaredWidth.length()).isEqualTo(DECLARED_WIDTH_WIRE_LENGTH);
        assertThat(declaredWidth.split(",", -1)[CsvAuthCodec.REQUEST_AMOUNT_ORDINAL].length())
                .as("the amount token is the copybook's declared width, one character wider than the"
                        + " receiving item at line 63 of COPAUA0C.cbl can hold")
                .isEqualTo(CsvAuthCodec.MONEY_EDITED_WIDTH);
        assertThat(CsvAuthCodec.decodeRequest(declaredWidth).transactionAmount().amount())
                .as("every character of the token is parsed, so the producer's intent survives")
                .isEqualByComparingTo(new BigDecimal("250.00"));
    }

    /**
     * Confirms a token the reference receiver would have truncated is read whole here or refused, never
     * silently misread.
     *
     * <p>Refactoring Rationale: this is the assertion that makes the divergence measurable instead of
     * merely asserted, and running it corrected the expectation it was written with. The reference hands
     * ordinal nine into a thirteen-character item and then applies {@code FUNCTION NUMVAL}, which
     * accepts one fraction digit as readily as two -- so a token whose fourteenth character was a
     * non-zero cents digit is received as a DIFFERENT, plausible amount. This codec cannot reach that
     * outcome from either direction: at full width it parses every character, and at the truncated width
     * it REFUSES the token by name, because its inner geometry requires exactly
     * {@value CsvAuthCodec#MONEY_SCALE} fraction digits. Both arms are asserted, because it is the pair
     * that rules out the silent misread rather than either one alone.</p>
     *
     * <p>Assumptions: the token used is one whose final character is a NON-ZERO cents digit, because a
     * trailing zero would truncate to a numerically equal value and the assertion would pass while
     * demonstrating nothing.</p>
     */
    @Test
    @DisplayName("a token the reference would truncate is read whole or refused, never misread")
    void truncatedAmountIsRefusedRatherThanMisread() {
        String declaredWidthToken = "+0000000100.99";
        String truncatedToken = declaredWidthToken.substring(0, CsvAuthCodec.REQUEST_MONEY_WIDTH);

        assertThat(declaredWidthToken).hasSize(CsvAuthCodec.MONEY_EDITED_WIDTH);
        assertThat(CsvAuthCodec.parseMoney(declaredWidthToken, "PA-RQ-TRANSACTION-AMT").amount())
                .as("at full width every character is parsed")
                .isEqualByComparingTo(new BigDecimal("100.99"));

        assertThatThrownBy(() -> CsvAuthCodec.parseMoney(truncatedToken, "PA-RQ-TRANSACTION-AMT"))
                .as("the reference's NUMVAL would have returned 100.90 from this token; this codec"
                        + " refuses it instead, so no truncated amount can be acted on")
                .isInstanceOf(CsvAuthCodec.AuthMessageFormatException.class)
                .hasMessageContaining("digits after its decimal point");
    }

    /**
     * Confirms re-emitting a decoded declared-width payload yields the canonical wire, not the wider one.
     *
     * <p>Assumptions: this is what makes 169 the canonical length rather than merely the preferred one.
     * A payload accepted at 170 is re-emitted at
     * {@value CsvAuthCodec#REQUEST_WIRE_LENGTH} with a {@value CsvAuthCodec#REQUEST_MONEY_WIDTH}-character
     * amount, so nothing this service sends can be truncated by the reference receiver even when what it
     * received was wider.</p>
     *
     * @throws IOException if the fixture cannot be read, which means it is absent from the classpath
     */
    @Test
    @DisplayName("re-emitting a declared-width payload produces the canonical wire length")
    void reEmissionProducesTheCanonicalWireLength() throws IOException {
        String reEmitted = CsvAuthCodec.encodeRequest(
                CsvAuthCodec.decodeRequest(readLines(DECLARED_WIDTH_FIXTURE).get(0)));

        assertThat(reEmitted.length()).isEqualTo(CsvAuthCodec.REQUEST_WIRE_LENGTH);
        assertThat(reEmitted.split(",", -1)[CsvAuthCodec.REQUEST_AMOUNT_ORDINAL].length())
                .isEqualTo(CsvAuthCodec.REQUEST_MONEY_WIDTH);
        assertThat(CsvAuthCodec.decodeRequest(reEmitted).transactionAmount().amount())
                .as("the round trip through the narrower emission preserves the value")
                .isEqualByComparingTo(new BigDecimal("250.00"));
    }

    /**
     * Reads a fixture's non-empty lines from the classpath.
     *
     * <p>Assumptions: the resource is read from the CLASSPATH rather than from a source path, so this
     * asserts against the fixture the module actually packages. Reading the source tree through the file
     * system would pass while the packaged resource was stale or absent.</p>
     *
     * @param resource the absolute classpath location of the fixture; must not be {@code null}
     * @return the fixture's lines with any trailing newline removed and blank lines dropped
     * @throws IOException if the resource cannot be read
     * @throws NullPointerException if the resource is absent from the classpath
     */
    private static List<String> readLines(String resource) throws IOException {
        try (InputStream stream = AuthRequestWireFixtureTest.class.getResourceAsStream(resource)) {
            Objects.requireNonNull(stream, "the fixture is absent from the classpath at " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank())
                    .toList();
        }
    }
}
