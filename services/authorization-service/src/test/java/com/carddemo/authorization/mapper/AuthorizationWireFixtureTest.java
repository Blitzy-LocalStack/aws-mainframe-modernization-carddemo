package com.carddemo.authorization.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.codec.CsvAuthCodec.AuthMessageFormatException;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Consumes the two committed authorization request wire fixtures and asserts what each one proves.
 *
 * <p>These files are a matched pair and neither is meaningful alone. Together they pin the one numeric
 * decision in the request contract that a reader is most likely to reverse: the width of the money
 * token. {@code PA-RQ-TRANSACTION-AMT PIC +9(10).99} at line 27 of {@code cpy/CCPAURQY.cpy} declares
 * fourteen characters and that declaration is the contract, so
 * {@link CsvAuthCodec#REQUEST_WIRE_LENGTH} is 170. The reference consumer copies the token into
 * {@code WS-TRANSACTION-AMT-AN PIC X(13)} at line 63 of {@code cbl/COPAUA0C.cbl} before converting it,
 * so a producer that emitted the narrower form is still read; that is the tolerance the second fixture
 * exists to demonstrate, and it is not a second contract.</p>
 *
 * <p>Assumptions: the three rows of the variants file differ from one another in the amount field and
 * in the transaction identifier only. Holding the other sixteen fields constant is what makes the
 * amount the independent variable; a row that also changed the merchant or the card would not isolate
 * it.</p>
 *
 * <p><b>Provenance and sensitive-data attestation.</b> Every value in both files is fabricated for
 * this test and represents no real card, cardholder, merchant or transaction. The primary account
 * number {@code 4000123456789010} is a synthetic sixteen-digit test value; it is not an issued card
 * number, it corresponds to no account in this repository -- the card master under
 * {@code app/data/ASCII/carddata.txt} does not contain it -- and it is present only because the
 * request contract declares {@code PA-RQ-CARD-NUM PIC X(16)} at line 21 and a shorter token would not
 * exercise the declared width. The merchant name, city, state and postal code are likewise invented.
 * No card verification value appears in either file, because the request declaration carries none.</p>
 *
 * <p>Assumptions: the fixtures are read through the test classpath rather than by filesystem path.
 * Maven copies {@code src/test/resources/} into {@code target/test-classes/}, so each file resolves as
 * {@code fixtures/<name>} from this class's loader and the tests pass identically from a reactor build
 * and from a single-module build.</p>
 */
class AuthorizationWireFixtureTest {

    /**
     * The classpath resource holding the single row whose money token is at the receiver's width.
     */
    private static final String RECEIVER_WIDTH_RESOURCE =
            "fixtures/auth-request-receiver-wire169-decode-only.csv";

    /**
     * The classpath resource holding the single canonical request row.
     *
     * <p>Assumptions: this is the encode oracle -- byte for byte what
     * {@code CsvAuthCodec.encodeRequest} emits for the same amount -- so the re-emitted payload is
     * compared against it rather than against a length alone.</p>
     */
    private static final String CANONICAL_WIRE_RESOURCE =
            "fixtures/auth-request-canonical-wire170.csv";

    /**
     * The classpath resource holding the three canonical-width request rows the contract accepts.
     */
    private static final String VARIANTS_RESOURCE = "fixtures/auth-request-amount-variants.csv";

    /**
     * The synthetic primary account number every row of both fixtures carries.
     *
     * <p>Assumptions: this is asserted rather than merely documented so that a future edit which
     * introduced a different number -- in particular one resembling an issued card -- fails here
     * instead of passing review unnoticed.</p>
     */
    private static final String SYNTHETIC_CARD_NUM = "4000123456789010";

    /**
     * Reads a committed fixture from the test classpath and returns its rows without terminators.
     *
     * @param resource the classpath-relative resource name, never {@code null}
     * @return the file's rows in file order, with the LF terminators removed, never {@code null}
     * @throws IllegalStateException if the resource is absent from the test classpath
     * @throws UncheckedIOException if the resource cannot be read
     */
    private static List<String> readRows(String resource) {
        try (InputStream stream =
                AuthorizationWireFixtureTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("fixture " + resource + " is not on the test classpath");
            }
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return List.of(text.split("\n", -1)).stream()
                    .filter(row -> !row.isEmpty())
                    .toList();
        } catch (IOException cause) {
            throw new UncheckedIOException("fixture " + resource + " could not be read", cause);
        }
    }

    /**
     * Asserts that each fixture row is exactly as long as the file's name claims.
     *
     * <p>Assumptions: the length is the whole point of these two files, so it is re-measured here
     * rather than trusted. A silent edit that added or removed one character would otherwise change
     * which side of the width decision each file demonstrates while every other assertion in this
     * class continued to pass.</p>
     */
    @Test
    @DisplayName("both fixtures carry the row lengths their names and geometry claim")
    void fixtureRowsCarryTheLengthsTheirNamesClaim() {
        List<String> receiverWidth = readRows(RECEIVER_WIDTH_RESOURCE);
        assertEquals(1, receiverWidth.size(), "the receiver-width fixture holds exactly one row");
        assertEquals(CsvAuthCodec.REQUEST_WIRE_LENGTH - 1, receiverWidth.get(0).length(),
                "the receiver-width fixture row is one character short of the wire");

        List<String> variants = readRows(VARIANTS_RESOURCE);
        assertEquals(3, variants.size(), "the variants fixture holds exactly three rows");
        for (int index = 0; index < variants.size(); index++) {
            assertEquals(
                    CsvAuthCodec.REQUEST_WIRE_LENGTH,
                    variants.get(index).length(),
                    "variants row " + (index + 1) + " is REQUEST_WIRE_LENGTH characters");
        }
    }

    /**
     * Asserts that every row of both fixtures carries the declared eighteen fields.
     *
     * <p>Assumptions: field count is checked separately from field width because the two fail
     * differently. A missing delimiter shortens the field count and shifts every field after it, which
     * is the failure mode a reader of a 170-character line cannot see, whereas a wrong width fails only
     * the one token.</p>
     */
    @Test
    @DisplayName("every fixture row carries the eighteen fields CCPAURQY declares")
    void everyFixtureRowCarriesEighteenFields() {
        for (String row : readRows(RECEIVER_WIDTH_RESOURCE)) {
            assertEquals(
                    CsvAuthCodec.REQUEST_FIELD_COUNT,
                    row.split(",", -1).length,
                    "the declared-width row carries REQUEST_FIELD_COUNT fields");
        }
        for (String row : readRows(VARIANTS_RESOURCE)) {
            assertEquals(
                    CsvAuthCodec.REQUEST_FIELD_COUNT,
                    row.split(",", -1).length,
                    "each variants row carries REQUEST_FIELD_COUNT fields");
        }
    }

    /**
     * Asserts that the 170-character row decodes at full precision and re-emits at 169 characters.
     *
     * <p>This is the assertion the pair exists for, and its shape was settled by measuring the codec
     * rather than by assuming it. Decoding is DELIBERATELY tolerant of the wider token: an inbound
     * payload is produced by an acquirer this repository does not own, so refusing a payload whose only
     * irregularity is one extra cents character would drop a message whose amount is unambiguous.
     * Emission is where the width is decided, and it narrows the token to thirteen characters -- which
     * is what keeps the reference consumer's {@code PIC X(13)} receiver from discarding the second
     * cents digit and computing one tenth of the amount sent.</p>
     *
     * <p>Alternatives Considered: asserting that the 170-character payload is REFUSED. Rejected on the
     * measurement: the decoder accepts it and yields {@code 250.00}, so an assertion of refusal would
     * document a contract the code does not implement. Alternatives Considered: asserting only that the
     * amount decodes, which is what a reader would write first. Rejected because the truncation hazard
     * lives on the EMISSION side, so a decode-only assertion would pass while a codec that re-emitted
     * fourteen characters shipped the defect.</p>
     *
     * <p>Assumptions: the re-emitted amount token loses the explicit {@code +} the fixture carries.
     * That is the narrowing, not a lost sign: the thirteen-character form spends every character on
     * digits and the decimal point, and a positive amount needs no sign character to be unambiguous.
     * The negative variant in the sibling fixture is what proves a sign still survives when there is
     * one.</p>
     */
    @Test
    @DisplayName("the receiver-width row decodes at full precision and re-emits at the wire width")
    void theOverlongMoneyTokenDecodesAndNarrowsOnEmission() {
        String row = readRows(RECEIVER_WIDTH_RESOURCE).get(0);

        AuthRequest decoded = CsvAuthCodec.decodeRequest(row);
        assertEquals(
                0,
                new BigDecimal("250.00").compareTo(decoded.transactionAmount().amount()),
                "an unsigned token decodes as the positive amount it spells");
        assertEquals(
                CsvAuthCodec.MONEY_SCALE,
                decoded.transactionAmount().amount().scale(),
                "the decoded amount keeps both cents digits");

        String reemitted = CsvAuthCodec.encodeRequest(decoded);
        assertEquals(
                CsvAuthCodec.REQUEST_WIRE_LENGTH,
                reemitted.length(),
                "emission widens the payload to REQUEST_WIRE_LENGTH characters");
        assertEquals(
                CsvAuthCodec.REQUEST_MONEY_WIDTH,
                reemitted.split(",", -1)[CsvAuthCodec.REQUEST_AMOUNT_ORDINAL].length(),
                "the re-emitted money token is REQUEST_MONEY_WIDTH characters wide");
        assertEquals(
                0,
                new BigDecimal("250.00").compareTo(CsvAuthCodec.decodeRequest(reemitted).transactionAmount().amount()),
                "widening the token changes the width and not the amount");

        // Assumptions: the re-emitted payload is compared against the committed canonical fixture
        //   field by field, not only by length and amount, because the length assertions above would
        //   hold for an emission that also transposed two other fields and a positional consumer
        //   cannot survive that.
        // Assumptions: the transaction identifier is excluded from that comparison because the two
        //   vectors deliberately carry different identifiers, so that every committed row stays
        //   distinguishable; it is asserted separately by value on both sides.
        String[] emitted = reemitted.split(",", -1);
        String[] canonical = readRows(CANONICAL_WIRE_RESOURCE).get(0).split(",", -1);
        assertEquals(
                CsvAuthCodec.REQUEST_FIELD_COUNT,
                canonical.length,
                "the canonical vector carries REQUEST_FIELD_COUNT fields");
        for (int ordinal = 0; ordinal < CsvAuthCodec.REQUEST_FIELD_COUNT - 1; ordinal++) {
            assertEquals(
                    canonical[ordinal],
                    emitted[ordinal],
                    "emitted field at ordinal " + ordinal + " matches the canonical vector");
        }
        assertEquals(
                "TXN000000000169",
                emitted[CsvAuthCodec.REQUEST_FIELD_COUNT - 1],
                "emission echoes the receiver-width row's own identifier");
        assertEquals(
                "TXN000000000100",
                canonical[CsvAuthCodec.REQUEST_FIELD_COUNT - 1],
                "the canonical vector carries its own distinct identifier");
    }

    /**
     * Asserts that a payload one character short of the accepted length is refused.
     *
     * <p>Assumptions: the tolerance demonstrated above is bounded, and this is where the bound is
     * shown. Truncating the last character of an accepted row removes the final character of the
     * transaction identifier, so the payload is no longer a well-formed request and the decoder must
     * say so rather than accept a short identifier. Without this assertion the class would document
     * tolerance with no limit, which is not the contract.</p>
     */
    @Test
    @DisplayName("a payload truncated below the accepted length is refused")
    void aTruncatedPayloadIsRefused() {
        String row = readRows(VARIANTS_RESOURCE).get(0);
        String truncated = row.substring(0, row.length() - 1);
        AuthMessageFormatException thrown =
                assertThrows(
                        AuthMessageFormatException.class,
                        () -> CsvAuthCodec.decodeRequest(truncated),
                        "a request payload short of its declared token widths must not decode");
        assertNotNull(thrown.getMessage(), "the refusal carries a message naming the defect");
    }

    /**
     * Asserts that each accepted row decodes to its documented exact amount.
     *
     * <p>The three rows are the negative, the maximum and the zero amount, in that file order:</p>
     *
     * <table>
     *   <caption>The three amount variants and what each one covers</caption>
     *   <tr><th>Row</th><th>Token</th><th>Decoded</th><th>What it covers</th></tr>
     *   <tr><td>1</td><td>{@code -000000250.00}</td><td>{@code -250.00}</td>
     *       <td>the leading sign character, which the positive form does not exercise</td></tr>
     *   <tr><td>2</td><td>{@code 9999999999.99}</td><td>{@code 9999999999.99}</td>
     *       <td>all ten integer digits and both cents digits at once</td></tr>
     *   <tr><td>3</td><td>{@code 0000000000.00}</td><td>{@code 0.00}</td>
     *       <td>zero, whose scale must survive as two rather than collapsing</td></tr>
     * </table>
     *
     * <p>Assumptions: each expectation is compared with {@code compareTo} on the unscaled decimal
     * value and separately on the scale, so an amount that is numerically right at the wrong scale
     * still fails. Money never leaves exact fixed point on this path, so a tolerance would defeat the
     * assertion rather than loosen it.</p>
     */
    @Test
    @DisplayName("each accepted row decodes to its exact documented amount")
    void eachAcceptedRowDecodesToItsExactAmount() {
        List<String> rows = readRows(VARIANTS_RESOURCE);
        List<BigDecimal> expected =
                List.of(
                        new BigDecimal("-250.00"),
                        new BigDecimal("9999999999.99"),
                        new BigDecimal("0.00"));

        for (int index = 0; index < rows.size(); index++) {
            AuthRequest decoded = CsvAuthCodec.decodeRequest(rows.get(index));
            BigDecimal actual = decoded.transactionAmount().amount();
            assertEquals(
                    0,
                    expected.get(index).compareTo(actual),
                    "variants row " + (index + 1) + " decodes to its documented amount");
            assertEquals(
                    CsvAuthCodec.MONEY_SCALE,
                    actual.scale(),
                    "variants row " + (index + 1) + " decodes at MONEY_SCALE");
        }
    }

    /**
     * Asserts that every fixture row carries the same synthetic card number and a distinct identifier.
     *
     * <p>Assumptions: the card number is held constant across all four rows so that the amount and the
     * identifier are the only things that vary, and the identifiers are distinct so that a failure
     * message identifies which row produced it. Both properties are asserted rather than described,
     * because a fixture edit that duplicated an identifier would make two rows indistinguishable in a
     * failure report.</p>
     */
    @Test
    @DisplayName("all four rows share the synthetic card number and carry distinct identifiers")
    void rowsShareTheSyntheticCardNumberAndCarryDistinctIdentifiers() {
        List<String> allRows =
                java.util.stream.Stream.concat(
                                readRows(RECEIVER_WIDTH_RESOURCE).stream(), readRows(VARIANTS_RESOURCE).stream())
                        .toList();
        assertEquals(4, allRows.size(), "the two fixtures hold four rows between them");

        java.util.Set<String> identifiers = new java.util.LinkedHashSet<>();
        for (String row : allRows) {
            String[] fields = row.split(",", -1);
            assertEquals(
                    SYNTHETIC_CARD_NUM,
                    fields[2],
                    "every row carries the synthetic card number at request ordinal three");
            assertTrue(
                    identifiers.add(fields[CsvAuthCodec.REQUEST_FIELD_COUNT - 1]),
                    "every row carries a transaction identifier no other row carries");
        }
    }
}
