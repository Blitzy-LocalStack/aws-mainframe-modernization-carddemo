package com.carddemo.reporting.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.CopybookLayout.FieldSpec;
import com.carddemo.common.codec.CopybookLayout.RecordSpec;
import com.carddemo.common.codec.FixedWidthCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Consumes the four committed reporting fixture records and asserts what each one contributes.
 *
 * <p>This module's reports and statements read account, customer, transaction-type and
 * transaction-category rows. These four files are the fixed-width form of those four records, and this
 * class is their consumer: it resolves each file from the test classpath, decodes it against the
 * production layout registry, and asserts both the layout contract and the coverage the chosen rows
 * provide.</p>
 *
 * <table>
 *   <caption>The four fixture files THIS CLASS consumes, measured on the branch</caption>
 *   <tr><th>File</th><th>Layout</th><th>Record</th><th>Width</th><th>Rows</th><th>Bytes</th></tr>
 *   <tr><td>{@code acctfile.txt}</td><td>{@code ACCOUNT}</td><td>{@code CVACT01Y.cpy}</td>
 *       <td>300</td><td>4</td><td>1204</td></tr>
 *   <tr><td>{@code custfile.txt}</td><td>{@code CUSTOMER}</td><td>{@code CVCUS01Y.cpy}</td>
 *       <td>500</td><td>4</td><td>2004</td></tr>
 *   <tr><td>{@code trantype.txt}</td><td>{@code TRANTYPE}</td><td>{@code CVTRA03Y.cpy}</td>
 *       <td>60</td><td>7</td><td>427</td></tr>
 *   <tr><td>{@code trancatg.txt}</td><td>{@code TRANCAT}</td><td>{@code CVTRA04Y.cpy}</td>
 *       <td>60</td><td>9</td><td>549</td></tr>
 * </table>
 *
 * <p>Every file is LF terminated with exactly one trailing newline, so each byte count is
 * {@code rows x (width + 1)}. {@link #eachFixtureHasTheGeometryItsLayoutDeclares(String, String, int)}
 * re-measures that on every run rather than trusting the table.</p>
 *
 * <p>Assumptions: the widths are taken from {@link CopybookLayout} rather than written as literals in
 * the assertions, so a fixture and a layout can never disagree silently. If a record width changed in
 * the registry, this class would fail on geometry rather than decode every field one byte out of
 * place.</p>
 *
 * <p>Alternatives Considered: decoding these rows with offsets declared locally in this class, which is
 * the shortest way to write the assertions. Rejected because it would put a second copy of each layout
 * in the repository, and the copy would be the one no production code exercises -- exactly the drift the
 * single-sourced registry exists to prevent.</p>
 *
 * <p><b>Why these rows and not other rows.</b> The four files are NOT copies of the seed datasets under
 * {@code app/data/ASCII/}, and the difference is deliberate. The seed rows are uniform: every account
 * carries a positive balance, and every reference description is a short label such as
 * {@code Purchase}. A report band is a fixed-width rendering, so the two things most likely to be wrong
 * in it are a negative or zero amount and a description that fills its declared width exactly. These
 * rows are authored to carry both:</p>
 *
 * <ul>
 *   <li>the four account rows span a positive, a negative, a zero and a second negative balance, and
 *       include one closed account, so an edit mask and an active-status branch are both exercised;</li>
 *   <li>the reference descriptions cover BOTH ends of the declared width. Four of the seven type rows
 *       and six of the nine category rows fill all fifty declared characters exactly, where the seed's
 *       longest label reaches barely half of them and leaves the right-hand end of the band untested;
 *       the remaining rows are deliberately short, so a renderer that padded or trimmed only the full
 *       case would still be caught;</li>
 *   <li>the nine category rows include the pair {@code 01} / {@code 0005}, which the disclosure-group
 *       seed carries no rate for, so a report over these rows meets the no-rate path.</li>
 * </ul>
 *
 * <p><b>Provenance and sensitive-data attestation.</b> Every byte of all four files is fabricated for
 * these assertions and represents no real person, account or card. Two of the customer values are
 * fabricated in a way that can be CHECKED rather than merely asserted, and
 * {@link #customerRowsCarryOnlyProvablyUnissuableIdentifiers()} checks them:</p>
 *
 * <ul>
 *   <li>every {@code CUST-SSN} begins with {@code 9}. The Social Security Administration has never
 *       issued a number in that range -- it is reserved for individual taxpayer identification numbers
 *       -- so none of these values can be a real Social Security number;</li>
 *   <li>every telephone number carries the {@code 555} exchange, and every PRIMARY number falls
 *       inside the {@code 555-0100} to {@code 555-0199} range specifically reserved for fictitious use,
 *       so none of them can reach a real subscriber. The assertion distinguishes the two because the
 *       secondary numbers sit in the wider {@code 555} exchange rather than in that narrow reserved
 *       range, and claiming the narrow range for all eight would be false of four of them.</li>
 * </ul>
 *
 * <p>The names, street addresses, government-issued identifiers and credit scores are likewise
 * invented; the identifiers are derived from each row's own ordinal ({@code GOVTID} followed by the
 * zero-padded customer identifier) so that no value resembles a real document number. No primary
 * account number and no card verification value appears in any of the four files, because none of the
 * four records declares either.</p>
 *
 * <p>Assumptions: that last sentence is a claim about THESE FOUR FILES and is not a claim about the
 * fixture directory, which holds seven. Two of the other three -- the card master and the card
 * cross-reference -- do declare a primary account number, and the card master also declares a
 * verification value; what those columns hold, and the checkable grounds on which no value in them can
 * be a live credential, are recorded in section 2.1 of that directory's own README. The scope is
 * spelled out because the unqualified reading is false, and a sensitive-data attestation that is
 * accidentally read as directory-wide is one a reader stops checking.</p>
 *
 * <p>Assumptions: the fixtures are read through the test classpath rather than by filesystem path.
 * Maven copies {@code src/test/resources/} into {@code target/test-classes/}, so each file resolves as
 * {@code fixtures/<name>} and these assertions hold identically in a reactor build and a single-module
 * build.</p>
 */
class ReportingFixtureRecordTest {

    /** The number of account rows the account fixture holds. */
    private static final int ACCOUNT_ROWS = 4;

    /** The number of customer rows the customer fixture holds. */
    private static final int CUSTOMER_ROWS = 4;

    /** The number of transaction-type rows the type fixture holds. */
    private static final int TYPE_ROWS = 7;

    /** The number of transaction-category rows the category fixture holds. */
    private static final int CATEGORY_ROWS = 9;

    /** The first character every fabricated national identifier in the customer fixture carries. */
    private static final char UNISSUABLE_SSN_LEADING_DIGIT = '9';

    /** The fictitious-use exchange every fabricated telephone number in the fixture carries. */
    private static final String FICTITIOUS_PHONE_EXCHANGE = "555-";

    /** The narrower reserved range every fabricated PRIMARY telephone number carries. */
    private static final String RESERVED_PHONE_RANGE = "555-01";

    /**
     * Supplies each fixture with the layout it decodes against and the row count it holds.
     *
     * @return one argument triple per fixture: resource name, registry layout name, row count
     */
    private static List<Arguments> fixtureGeometry() {
        return List.of(
                Arguments.of("fixtures/acctfile.txt", "ACCOUNT", ACCOUNT_ROWS),
                Arguments.of("fixtures/custfile.txt", "CUSTOMER", CUSTOMER_ROWS),
                Arguments.of("fixtures/trantype.txt", "TRANTYPE", TYPE_ROWS),
                Arguments.of("fixtures/trancatg.txt", "TRANCAT", CATEGORY_ROWS));
    }

    /**
     * Reads a committed fixture from the test classpath and returns its rows without terminators.
     *
     * @param resource the classpath-relative resource name, never {@code null}
     * @return the file's rows in file order, LF terminators removed, never {@code null}
     * @throws IllegalStateException if the resource is absent from the test classpath
     * @throws UncheckedIOException if the resource cannot be read
     */
    private static List<String> readRows(String resource) {
        try (InputStream stream =
                ReportingFixtureRecordTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("fixture " + resource + " is not on the test classpath");
            }
            String text = new String(stream.readAllBytes(), StandardCharsets.US_ASCII);
            List<String> rows = new ArrayList<>();
            for (String row : text.split("\n", -1)) {
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
            return List.copyOf(rows);
        } catch (IOException cause) {
            throw new UncheckedIOException("fixture " + resource + " could not be read", cause);
        }
    }

    /**
     * Decodes one fixture row against a registry layout.
     *
     * @param row the row's characters, never {@code null}
     * @param spec the layout to decode against, never {@code null}
     * @return the decoded field map, never {@code null}
     */
    private static Map<String, Object> decode(String row, RecordSpec spec) {
        return FixedWidthCodec.decodeRecord(row.getBytes(StandardCharsets.US_ASCII), spec);
    }

    /**
     * Asserts that each fixture matches the geometry its own layout declares.
     *
     * @param resource the classpath-relative fixture name
     * @param layoutName the registry layout the fixture's rows follow
     * @param expectedRows the number of rows the fixture is documented to hold
     */
    @ParameterizedTest(name = "{0} decodes as {1}")
    @MethodSource("fixtureGeometry")
    @DisplayName("each fixture has the geometry its layout declares")
    void eachFixtureHasTheGeometryItsLayoutDeclares(String resource, String layoutName, int expectedRows) {
        RecordSpec spec = CopybookLayout.layout(layoutName);
        List<String> rows = readRows(resource);

        assertEquals(expectedRows, rows.size(), resource + " holds its documented row count");
        for (int index = 0; index < rows.size(); index++) {
            assertEquals(
                    spec.reclen(),
                    rows.get(index).length(),
                    resource + " row " + (index + 1) + " is exactly the declared record length");
        }
    }

    /**
     * Asserts that every fixture row survives a decode and re-encode without changing one byte.
     *
     * <p>Assumptions: a byte-identical round trip is the strongest single statement available about a
     * fixed-width fixture, and it is stronger than decoding alone. A row can decode successfully while
     * being wrong -- a value padded on the wrong side, or a numeric field carrying a stray sign
     * character, still yields a field map -- whereas re-encoding the decoded map reconstructs every
     * byte from the layout, so any disagreement between the bytes on disk and the declared field
     * geometry shows up as a differing byte.</p>
     *
     * <p>Assumptions: {@code FILLER} is dropped on decode and regenerated on encode, so a round trip
     * that stays identical additionally proves each fixture's padding is exactly what the layout emits.
     * That is asserted here rather than described, because padding is the part of a fixed-width record a
     * reader is most likely to author by eye.</p>
     *
     * @param resource the classpath-relative fixture name
     * @param layoutName the registry layout the fixture's rows follow
     * @param expectedRows the number of rows the fixture is documented to hold
     */
    @ParameterizedTest(name = "{0} round-trips byte for byte")
    @MethodSource("fixtureGeometry")
    @DisplayName("every fixture row round-trips through the codec byte for byte")
    void everyFixtureRowRoundTripsByteForByte(String resource, String layoutName, int expectedRows) {
        RecordSpec spec = CopybookLayout.layout(layoutName);
        List<String> rows = readRows(resource);
        assertEquals(expectedRows, rows.size(), resource + " holds its documented row count");

        for (int index = 0; index < rows.size(); index++) {
            byte[] original = rows.get(index).getBytes(StandardCharsets.US_ASCII);
            byte[] reencoded = FixedWidthCodec.encodeRecord(decode(rows.get(index), spec), spec);
            assertTrue(
                    Arrays.equals(original, reencoded),
                    resource + " row " + (index + 1) + " re-encodes to the bytes it was read from");
        }
    }

    /**
     * Asserts the account rows span the amount and status cases a report band has to render.
     *
     * <p>Assumptions: an edit mask is where a fixed-width money rendering fails, and it fails
     * differently for each sign. A fixture of four positive balances would leave the negative mask and
     * the zero case unrendered, so the assertion here is about the SPREAD of the rows rather than about
     * any one value: at least one balance above zero, at least one below, and at least one exactly
     * zero.</p>
     *
     * <p>Assumptions: the closed account matters for the same reason. The active-status field is a
     * one-character branch, and a fixture in which every account is active exercises one side of it
     * only.</p>
     */
    @Test
    @DisplayName("the account rows span positive, negative and zero balances and both status values")
    void accountRowsSpanTheAmountAndStatusCases() {
        RecordSpec spec = CopybookLayout.layout("ACCOUNT");
        List<String> rows = readRows("fixtures/acctfile.txt");

        boolean sawPositive = false;
        boolean sawNegative = false;
        boolean sawZero = false;
        Set<String> statuses = new LinkedHashSet<>();

        for (String row : rows) {
            Map<String, Object> fields = decode(row, spec);
            BigDecimal balance = new BigDecimal(String.valueOf(fields.get("ACCT-CURR-BAL")));
            sawPositive |= balance.signum() > 0;
            sawNegative |= balance.signum() < 0;
            sawZero |= balance.signum() == 0;
            statuses.add(String.valueOf(fields.get("ACCT-ACTIVE-STATUS")));
        }

        assertTrue(sawPositive, "at least one account carries a balance above zero");
        assertTrue(sawNegative, "at least one account carries a balance below zero");
        assertTrue(sawZero, "at least one account carries a balance of exactly zero");
        assertEquals(
                Set.of("Y", "N"),
                statuses,
                "the rows cover both sides of the active-status branch");
    }

    /**
     * Asserts every customer row carries identifiers that provably cannot belong to a real person.
     *
     * <p>Assumptions: an attestation that a value is synthetic is worth more when it is checkable, and
     * these two are. A national identifier beginning with {@code 9} has never been issued, and a
     * telephone number in the {@code 555-01} block is reserved for fictitious use, so this assertion
     * fails the moment someone replaces a row with data that could be real -- which is precisely when a
     * reviewer would want to be told.</p>
     *
     * <p>Alternatives Considered: stating the fabrication in prose alone, as a comment beside the
     * fixture. Rejected because prose cannot notice an edit. A future author adding a fifth customer row
     * with a plausible identifier would leave the comment true of the rows it described and false of the
     * file.</p>
     */
    @Test
    @DisplayName("every customer row carries provably unissuable identifiers")
    void customerRowsCarryOnlyProvablyUnissuableIdentifiers() {
        RecordSpec spec = CopybookLayout.layout("CUSTOMER");
        List<String> rows = readRows("fixtures/custfile.txt");
        assertEquals(CUSTOMER_ROWS, rows.size(), "the customer fixture holds its documented row count");

        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> fields = decode(rows.get(index), spec);
            String national = String.valueOf(fields.get("CUST-SSN")).trim();
            assertEquals(
                    UNISSUABLE_SSN_LEADING_DIGIT,
                    national.charAt(0),
                    "customer row " + (index + 1) + " carries a national identifier that was never issued");

            for (String component : List.of("CUST-PHONE-NUM-1", "CUST-PHONE-NUM-2")) {
                String phone = String.valueOf(fields.get(component)).trim();
                assertTrue(
                        phone.contains(FICTITIOUS_PHONE_EXCHANGE),
                        "customer row " + (index + 1) + " " + component
                                + " carries the fictitious-use exchange");
            }
            String primaryPhone = String.valueOf(fields.get("CUST-PHONE-NUM-1")).trim();
            assertTrue(
                    primaryPhone.contains(RESERVED_PHONE_RANGE),
                    "customer row " + (index + 1)
                            + " primary number falls in the narrower reserved fictitious range");

            String governmentId = String.valueOf(fields.get("CUST-GOVT-ISSUED-ID")).trim();
            assertTrue(
                    governmentId.startsWith("GOVTID"),
                    "customer row " + (index + 1) + " carries a self-identifying synthetic document number");
        }
    }

    /**
     * Asserts no reference description is short enough to leave the band's right-hand end untested.
     *
     * <p>Assumptions: a report band renders a description into a fixed column span, so the failure that
     * matters is at the far end of that span -- a truncation, or a stray byte after the text. The seed
     * datasets cannot expose it: their longest description is well short of the fifty declared
     * characters, so the last part of the column stays blank whatever the renderer does with it. These
     * rows are authored close to the declared width for that reason, and the threshold below is asserted
     * so that a future edit shortening them fails here instead of quietly reducing the coverage.</p>
     */
    @Test
    @DisplayName("reference descriptions reach far enough into their declared width to matter")
    void referenceDescriptionsReachIntoTheirDeclaredWidth() {
        assertDescriptionsAreNearlyFull("fixtures/trantype.txt", "TRANTYPE", "TRAN-TYPE-DESC", TYPE_ROWS);
        assertDescriptionsAreNearlyFull(
                "fixtures/trancatg.txt", "TRANCAT", "TRAN-CAT-TYPE-DESC", CATEGORY_ROWS);
    }

    /**
     * Asserts that the category rows include the pair the disclosure-group seed carries no rate for.
     *
     * <p>Assumptions: the pair {@code 01} / {@code 0005} appears in the category seed and in NO
     * disclosure group, which is an observed property of the baseline data rather than a gap. A report
     * whose category rows all had a rate would never reach the no-rate path, so the pair is carried here
     * on purpose and asserted so that a future edit cannot drop it without a failure.</p>
     */
    @Test
    @DisplayName("the category rows include the pair no disclosure group carries a rate for")
    void categoryRowsIncludeTheNoRatePair() {
        RecordSpec spec = CopybookLayout.layout("TRANCAT");
        Set<String> pairs = new LinkedHashSet<>();
        for (String row : readRows("fixtures/trancatg.txt")) {
            Map<String, Object> fields = decode(row, spec);
            pairs.add(
                    String.valueOf(fields.get("TRAN-TYPE-CD")).trim()
                            + "|"
                            + String.valueOf(fields.get("TRAN-CAT-CD")).trim());
        }

        assertTrue(pairs.contains("01|5"), "the rows include type 01 category 0005, the no-rate pair");
        assertFalse(pairs.isEmpty(), "the category fixture decodes at least one pair");
        assertEquals(CATEGORY_ROWS, pairs.size(), "no two category rows carry the same pair");
    }

    /**
     * Asserts a reference fixture covers both the full declared description width and a short one.
     *
     * <p>Assumptions: the coverage claim is about the FILE and not about every row, and the difference
     * matters. Asserting a minimum length per row would be false of these fixtures and would also be the
     * wrong requirement -- a band renderer has to be right for a description that fills its column and
     * for one that does not, so both cases have to be present. The assertion is therefore that at least
     * one row reaches the declared width exactly, that at least one row is shorter than it and therefore
     * has to be padded, and that no row exceeds the width. The shortest description in either fixture is
     * 28 characters and the longest is 50, so both halves hold with room to spare.</p>
     *
     * @param resource the classpath-relative fixture name, never {@code null}
     * @param layoutName the registry layout the fixture's rows follow, never {@code null}
     * @param descriptionField the name of the description component to measure, never {@code null}
     * @param expectedRows the number of rows the fixture is documented to hold
     */
    private static void assertDescriptionsAreNearlyFull(
            String resource, String layoutName, String descriptionField, int expectedRows) {
        RecordSpec spec = CopybookLayout.layout(layoutName);
        int declaredWidth = declaredWidthOf(spec, descriptionField);
        List<String> rows = readRows(resource);
        assertEquals(expectedRows, rows.size(), resource + " holds its documented row count");

        boolean sawFullWidth = false;
        boolean sawShort = false;
        for (int index = 0; index < rows.size(); index++) {
            String description = String.valueOf(decode(rows.get(index), spec).get(descriptionField));
            int used = description.stripTrailing().length();
            assertTrue(
                    used <= declaredWidth,
                    resource + " row " + (index + 1) + " does not exceed its declared width");
            sawFullWidth |= used == declaredWidth;
            sawShort |= used < declaredWidth;
        }

        assertTrue(
                sawFullWidth,
                resource + " holds at least one description filling all " + declaredWidth
                        + " declared characters, so the band's far end is rendered");
        assertTrue(
                sawShort,
                resource + " holds at least one description SHORTER than the declared width, so the"
                        + " padded case is rendered too");
    }

    /**
     * Reports the declared width of one component of a layout.
     *
     * @param spec the layout to look in, never {@code null}
     * @param fieldName the component name to find, never {@code null}
     * @return the component's declared length in characters
     * @throws IllegalStateException if the layout declares no component under that name
     */
    private static int declaredWidthOf(RecordSpec spec, String fieldName) {
        for (FieldSpec field : spec.fields()) {
            if (field.name().equals(fieldName)) {
                return field.length();
            }
        }
        throw new IllegalStateException(
                "layout " + spec.name() + " declares no component named " + fieldName);
    }
}
