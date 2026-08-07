package com.carddemo.reporting.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the markup statement mapper to two obligations at once: byte parity and safe output.
 *
 * <p>Purpose: this class had no test of any kind, and it is the one component of the statement path
 * that composes stored customer data into a document a browser loads. Two properties therefore have to
 * hold together, and either alone is worthless. The bytes of a statement assembled from conforming
 * data must be exactly what they were, because the reference artifact is the parity oracle; and data
 * that is not conforming must never reach the document as markup, because the reader of a statement is
 * not its subject.
 *
 * <p>Assumptions: the parity half is asserted against LITERAL expected records, assembled here from
 * the declared prefixes and the values under test rather than by re-running the mapper's own cut. A
 * test that computed its expectation with a copy of the production cut would agree with itself about
 * any cut at all, so every value below is chosen so that its cut result is evident by construction --
 * each carries single blanks inside it and reaches its declared width by trailing blanks, so the first
 * blank pair is exactly where the padding begins.
 *
 * <p>Assumptions: every conforming value below is drawn from the character classes the seed corpus
 * actually uses -- upper-case letters, digits, blank, full stop and hyphen -- so the escaping is
 * provably a no-op over them and the parity assertions are the proof of it. That is what allows the
 * escaping to be added without moving the golden artifact under {@code tests/golden}, which is
 * reference material this project does not modify.
 */
class StatementHtmlMapperTest {

    /**
     * Attribute names that would put a dynamic value into a URL context.
     *
     * <p>Assumptions: these four fetch or navigate to a location, so a value interpolated into any
     * one of them is a URL and the text-context escape does not make it safe -- a URL can carry a
     * script scheme with no bracket in it at all. The {@code style} attribute is deliberately
     * absent: the artifact declares exactly one, it is a compile-time constant, and no dynamic value
     * reaches it.</p>
     */
    private static final List<String> URL_ATTRIBUTE_NAMES =
            List.of("href=", "src=", "action=", "formaction=");

    /**
     * Shape of an inline event-handler attribute, which would put a value into a script context.
     *
     * <p>Alternatives Considered: searching for the two-character prefix every such attribute
     * shares. Rejected because those two characters occur inside ordinary prose -- a fragment
     * reading {@code Statement on ...} would match -- so the check would fail on a legitimate
     * literal added later and would then be relaxed or removed. Matching the whole attribute shape,
     * being whitespace, the prefix, at least one further letter and an equals sign, cannot collide
     * with prose because prose does not assign.</p>
     */
    private static final Pattern EVENT_HANDLER_ATTRIBUTE = Pattern.compile("\\s+on[a-z]+\\s*=");

    /**
     * Number of cell and heading literals counted alongside the declared fragment table.
     *
     * <p>Assumptions: seven literals sit outside the fragment table and are checked with it -- the
     * five cell prefixes, the styled name prefix among them, plus the two halves of the account
     * heading. Declaring the number means the walk below cannot silently stop covering one of them.
     * </p>
     */
    private static final int NON_FRAGMENT_LITERAL_COUNT = 7;

    /** The declared width of the assembled name item. */
    private static final int ASSEMBLED_NAME_WIDTH = StatementTextMapper.ASSEMBLED_NAME_WIDTH;

    /** The declared width of one address line item. */
    private static final int ADDRESS_LINE_WIDTH = StatementTextMapper.ADDRESS_LINE_WIDTH;

    /** The declared width of the assembled address item, the tightest cell in the artifact. */
    private static final int ASSEMBLED_ADDRESS_WIDTH = StatementTextMapper.ASSEMBLED_ADDRESS_WIDTH;

    /** The declared width of the account-identifier display item. */
    private static final int ACCOUNT_ID_ITEM_WIDTH = StatementTextMapper.ACCOUNT_ID_ITEM_WIDTH;

    /** The declared width of the credit-score display item. */
    private static final int CREDIT_SCORE_ITEM_WIDTH = StatementTextMapper.CREDIT_SCORE_ITEM_WIDTH;

    /** The declared width of the transaction-identifier item. */
    private static final int TRANSACTION_ID_WIDTH = StatementTextMapper.TRANSACTION_ID_WIDTH;

    /** The declared width of the transaction-description item. */
    private static final int DESCRIPTION_ITEM_WIDTH = StatementTextMapper.DESCRIPTION_ITEM_WIDTH;

    /** The declared width of both edited money items. */
    private static final int AMOUNT_WIDTH = StatementBandLayouts.STATEMENT_AMOUNT_LENGTH;

    /** The zero-based index of the name cell in the name-address-detail emission. */
    private static final int NAME_CELL_INDEX = 0;

    /** The zero-based index of the first address cell. */
    private static final int ADDRESS_LINE_1_INDEX = 1;

    /** The zero-based index of the second address cell. */
    private static final int ADDRESS_LINE_2_INDEX = 2;

    /** The zero-based index of the assembled-address cell. */
    private static final int ASSEMBLED_ADDRESS_INDEX = 3;

    /** The zero-based index of the account-identifier basic-detail cell. */
    private static final int ACCOUNT_ID_INDEX = 13;

    /** The zero-based index of the current-balance basic-detail cell. */
    private static final int CURRENT_BALANCE_INDEX = 14;

    /** The zero-based index of the credit-score basic-detail cell. */
    private static final int CREDIT_SCORE_INDEX = 15;

    /** The zero-based index of the transaction-identifier cell in a transaction row. */
    private static final int TRANSACTION_ID_INDEX = 2;

    /** The zero-based index of the description cell in a transaction row. */
    private static final int DESCRIPTION_INDEX = 5;

    /** The zero-based index of the amount cell in a transaction row. */
    private static final int AMOUNT_INDEX = 8;

    /** A conforming customer name whose cut result is the text before its trailing padding. */
    private static final String NAME_TEXT = "JOHN A DOE";

    /** A conforming first address line. */
    private static final String ADDRESS_1_TEXT = "123 MAIN ST";

    /** A conforming second address line. */
    private static final String ADDRESS_2_TEXT = "APT 4";

    /** A conforming assembled address, single-blank separated so no internal pair cuts it. */
    private static final String ASSEMBLED_ADDRESS_TEXT = "123 MAIN ST SPRINGFIELD IL 62701";

    /** A conforming eleven-digit account identifier. */
    private static final String ACCOUNT_DIGITS = "00000000011";

    /** A conforming edited balance at its declared width, leading blanks included. */
    private static final String BALANCE_ITEM = "   1234567.89";

    /** A conforming credit score. */
    private static final String CREDIT_SCORE_TEXT = "750";

    /** A conforming transaction identifier at its exact declared width. */
    private static final String TRANSACTION_ID_TEXT = "0000000000000001";

    /** A conforming transaction description. */
    private static final String DESCRIPTION_TEXT = "PURCHASE AT STORE";

    /** A conforming edited amount at its declared width. */
    private static final String AMOUNT_ITEM = "       250.00";

    /**
     * Pads a value out to a declared width with trailing blanks, as the reference move does.
     *
     * @param text the value's characters
     * @param declaredWidth the width the item declares
     * @return the value followed by blanks, exactly {@code declaredWidth} characters
     */
    private static String item(String text, int declaredWidth) {
        return text + " ".repeat(declaredWidth - text.length());
    }

    /**
     * Renders one emitted record as text so an assertion can read it.
     *
     * @param record one emitted record of the declared length
     * @return the record's characters
     */
    private static String text(byte[] record) {
        return new String(record, StandardCharsets.US_ASCII);
    }

    /**
     * Builds the record a cell is expected to occupy, from its literals and its rendered value.
     *
     * <p>Assumptions: this composes the expectation from the mapper's PUBLISHED literal constants and
     * the value the test supplied, and performs no cut and no escaping of its own. It is therefore an
     * expectation about bytes rather than a second implementation of the assembly.
     *
     * @param content the whole assembled content of the record, prefix and suffix included
     * @return the content followed by blanks out to the declared record length
     */
    private static String expectedRecord(String content) {
        return content + " ".repeat(StatementHtmlMapper.HTML_RECORD_LENGTH - content.length());
    }

    /**
     * Builds the conforming header fields every parity assertion below reads.
     *
     * @return prepared header fields whose every component is at its exact declared width and holds
     *     only characters the seed corpus uses
     */
    private static StatementTextMapper.PreparedHeaderFields conformingHeader() {
        return new StatementTextMapper.PreparedHeaderFields(
                item(NAME_TEXT, ASSEMBLED_NAME_WIDTH),
                item(ADDRESS_1_TEXT, ADDRESS_LINE_WIDTH),
                item(ADDRESS_2_TEXT, ADDRESS_LINE_WIDTH),
                item(ASSEMBLED_ADDRESS_TEXT, ASSEMBLED_ADDRESS_WIDTH),
                item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH),
                BALANCE_ITEM,
                item(CREDIT_SCORE_TEXT, CREDIT_SCORE_ITEM_WIDTH));
    }

    /**
     * Builds header fields identical to the conforming ones but for one substituted component.
     *
     * @param assembledAddress the assembled-address component to substitute, at its declared width
     * @return prepared header fields carrying the supplied assembled address
     */
    private static StatementTextMapper.PreparedHeaderFields headerWithAddress(String assembledAddress) {
        return new StatementTextMapper.PreparedHeaderFields(
                item(NAME_TEXT, ASSEMBLED_NAME_WIDTH),
                item(ADDRESS_1_TEXT, ADDRESS_LINE_WIDTH),
                item(ADDRESS_2_TEXT, ADDRESS_LINE_WIDTH),
                assembledAddress,
                item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH),
                BALANCE_ITEM,
                item(CREDIT_SCORE_TEXT, CREDIT_SCORE_ITEM_WIDTH));
    }

    /**
     * Builds header fields identical to the conforming ones but for a substituted assembled name.
     *
     * @param assembledName the assembled-name component to substitute, at its declared width
     * @return prepared header fields carrying the supplied name
     */
    private static StatementTextMapper.PreparedHeaderFields headerWithName(String assembledName) {
        return new StatementTextMapper.PreparedHeaderFields(
                assembledName,
                item(ADDRESS_1_TEXT, ADDRESS_LINE_WIDTH),
                item(ADDRESS_2_TEXT, ADDRESS_LINE_WIDTH),
                item(ASSEMBLED_ADDRESS_TEXT, ASSEMBLED_ADDRESS_WIDTH),
                item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH),
                BALANCE_ITEM,
                item(CREDIT_SCORE_TEXT, CREDIT_SCORE_ITEM_WIDTH));
    }

    /**
     * Builds transaction fields with one substituted description.
     *
     * @param description the description component to substitute, at its declared width
     * @return prepared transaction fields carrying the supplied description
     */
    private static StatementTextMapper.PreparedTransactionFields rowWithDescription(String description) {
        return new StatementTextMapper.PreparedTransactionFields(
                item(TRANSACTION_ID_TEXT, TRANSACTION_ID_WIDTH), description, AMOUNT_ITEM);
    }

    /**
     * Confirms that a statement assembled from conforming data carries exactly its former bytes.
     *
     * <p>Assumptions: this is the parity assertion the escaping had to survive, and it is expressed as
     * the seven dynamic cells' exact expected records rather than as a document-level comparison. Each
     * expectation is the prefix, the value as supplied, and the suffix -- with no entity reference
     * anywhere in it -- so the assertion passes only while the escaping leaves the seed character
     * classes untouched. If a future change escaped a full stop, a hyphen or a blank, this case fails
     * rather than the failure surfacing later as a golden-artifact mismatch outside this module.
     */
    @Test
    @DisplayName("a statement assembled from conforming data is byte-identical to its former output")
    void conformingDataIsUnchangedByTheEscaping() {
        List<byte[]> records =
                StatementHtmlMapper.emitNameAddressAndBasicDetails(conformingHeader());

        assertThat(records).hasSize(StatementHtmlMapper.NAME_ADDRESS_BASIC_DETAIL_LINE_COUNT);
        assertThat(records).allSatisfy(record ->
                assertThat(record).hasSize(StatementHtmlMapper.HTML_RECORD_LENGTH));

        assertThat(text(records.get(NAME_CELL_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.NAME_CELL_PREFIX + NAME_TEXT
                        + StatementHtmlMapper.PAIRED_BLANK + StatementHtmlMapper.CELL_SUFFIX));
        assertThat(text(records.get(ADDRESS_LINE_1_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.PLAIN_CELL_PREFIX + ADDRESS_1_TEXT
                        + StatementHtmlMapper.PAIRED_BLANK + StatementHtmlMapper.CELL_SUFFIX));
        assertThat(text(records.get(ADDRESS_LINE_2_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.PLAIN_CELL_PREFIX + ADDRESS_2_TEXT
                        + StatementHtmlMapper.PAIRED_BLANK + StatementHtmlMapper.CELL_SUFFIX));
        assertThat(text(records.get(ASSEMBLED_ADDRESS_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.PLAIN_CELL_PREFIX + ASSEMBLED_ADDRESS_TEXT
                        + StatementHtmlMapper.PAIRED_BLANK + StatementHtmlMapper.CELL_SUFFIX));

        // WHY : Assumptions: the three whole-item cells are asserted with their DECLARED BLANKS still
        //       inside them, because that regime transfers the item complete. Asserting them trimmed
        //       would pass for an implementation that had started trimming, which would move the
        //       closing tag of every basic-detail cell.
        assertThat(text(records.get(ACCOUNT_ID_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.ACCOUNT_ID_CELL_PREFIX + item(ACCOUNT_DIGITS,
                        ACCOUNT_ID_ITEM_WIDTH) + StatementHtmlMapper.CELL_SUFFIX));
        assertThat(text(records.get(CURRENT_BALANCE_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.CURRENT_BALANCE_CELL_PREFIX + BALANCE_ITEM
                        + StatementHtmlMapper.CELL_SUFFIX));
        assertThat(text(records.get(CREDIT_SCORE_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.FICO_SCORE_CELL_PREFIX + item(CREDIT_SCORE_TEXT,
                        CREDIT_SCORE_ITEM_WIDTH) + StatementHtmlMapper.CELL_SUFFIX));

        // WHY : Assumptions: the absence of every entity reference is asserted over the whole emission
        //       rather than only over the seven dynamic cells. The declared fragments carry real angle
        //       brackets, so a change that escaped them would leave the document rendering its own tags
        //       as text -- a failure a per-cell assertion would not see.
        assertThat(records).allSatisfy(record -> assertThat(text(record))
                .doesNotContain("&amp;").doesNotContain("&lt;").doesNotContain("&gt;")
                .doesNotContain("&quot;").doesNotContain("&#39;"));
    }

    /**
     * Confirms a transaction row assembled from conforming data carries exactly its former bytes.
     */
    @Test
    @DisplayName("a transaction row assembled from conforming data is byte-identical to its former"
            + " output")
    void conformingTransactionRowIsUnchangedByTheEscaping() {
        List<byte[]> records = StatementHtmlMapper.emitTransactionRow(
                new StatementTextMapper.PreparedTransactionFields(
                        item(TRANSACTION_ID_TEXT, TRANSACTION_ID_WIDTH),
                        item(DESCRIPTION_TEXT, DESCRIPTION_ITEM_WIDTH),
                        AMOUNT_ITEM));

        assertThat(records).hasSize(StatementHtmlMapper.TRANSACTION_ROW_LINE_COUNT);
        assertThat(text(records.get(TRANSACTION_ID_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.PLAIN_CELL_PREFIX + item(TRANSACTION_ID_TEXT,
                        TRANSACTION_ID_WIDTH) + StatementHtmlMapper.CELL_SUFFIX));
        assertThat(text(records.get(DESCRIPTION_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.PLAIN_CELL_PREFIX + item(DESCRIPTION_TEXT,
                        DESCRIPTION_ITEM_WIDTH) + StatementHtmlMapper.CELL_SUFFIX));
        assertThat(text(records.get(AMOUNT_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.PLAIN_CELL_PREFIX + AMOUNT_ITEM
                        + StatementHtmlMapper.CELL_SUFFIX));
    }

    /**
     * Confirms the declared fragments reach the document as markup rather than as escaped text.
     *
     * <p>Assumptions: this is asserted as its own case because it is the property that could be broken
     * by over-correcting the finding. Escaping applied at the shared record exit rather than at the two
     * cell helpers would have produced a document whose every tag rendered as visible text, which is a
     * different defect of the same origin.
     */
    @Test
    @DisplayName("the declared fragments reach the document as markup and are never escaped")
    void declaredFragmentsAreNotEscaped() {
        List<byte[]> header = StatementHtmlMapper.emitDocumentHeader(
                item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH));

        assertThat(header).hasSize(StatementHtmlMapper.DOCUMENT_HEADER_LINE_COUNT);
        String document = String.join("", header.stream().map(StatementHtmlMapperTest::text).toList());
        assertThat(document).contains("<").contains(">").doesNotContain("&lt;").doesNotContain("&gt;");
        assertThat(text(header.get(10)))
                .startsWith(StatementHtmlMapper.ACCOUNT_HEADING_PREFIX)
                .contains(ACCOUNT_DIGITS);

        assertThat(StatementHtmlMapper.emitDocumentFooter()).allSatisfy(record ->
                assertThat(text(record)).doesNotContain("&lt;").doesNotContain("&gt;"));
    }

    /**
     * Confirms a script-shaped name is rendered as text and cannot execute.
     *
     * <p>Assumptions: both directions are asserted -- the escaped form is present AND the executable
     * form is absent. Asserting only the presence of the escaped form would pass for output that
     * carried both, which is what a partial escaping produces.
     */
    @Test
    @DisplayName("a script-shaped name is rendered as text rather than as an element")
    void aScriptShapedNameIsRenderedAsText() {
        String hostile = "<script>alert(1)</script>";
        List<byte[]> records = StatementHtmlMapper.emitNameAddressAndBasicDetails(
                headerWithName(item(hostile, ASSEMBLED_NAME_WIDTH)));

        String cell = text(records.get(NAME_CELL_INDEX));
        assertThat(cell).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(cell).doesNotContain("<script").doesNotContain("</script");
        assertThat(cell).isEqualTo(expectedRecord(StatementHtmlMapper.NAME_CELL_PREFIX
                + "&lt;script&gt;alert(1)&lt;/script&gt;"
                + StatementHtmlMapper.PAIRED_BLANK + StatementHtmlMapper.CELL_SUFFIX));
    }

    /**
     * Confirms a stored entity reference is escaped again rather than passed through.
     *
     * <p>Assumptions: this is the case that proves the ampersand is escaped, and it is the one most
     * easily left out. A value holding {@code &lt;} would, with the ampersand untouched, render in a
     * browser as {@code <} -- so the escaping would be defeated by data that merely spells the escape
     * itself. Rendering it as {@code &amp;lt;} makes the browser display the four characters the
     * customer's data actually holds.
     */
    @Test
    @DisplayName("a stored entity reference is escaped again and cannot reintroduce a metacharacter")
    void aStoredEntityReferenceIsEscapedAgain() {
        List<byte[]> records = StatementHtmlMapper.emitNameAddressAndBasicDetails(
                headerWithName(item("&lt;script&gt;", ASSEMBLED_NAME_WIDTH)));

        String cell = text(records.get(NAME_CELL_INDEX));
        assertThat(cell).isEqualTo(expectedRecord(StatementHtmlMapper.NAME_CELL_PREFIX
                + "&amp;lt;script&amp;gt;"
                + StatementHtmlMapper.PAIRED_BLANK + StatementHtmlMapper.CELL_SUFFIX));

        // WHY : Assumptions: the raw metacharacters are asserted absent from the RENDERED VALUE alone
        //       and not from the whole record, because the record's own prefix and suffix are declared
        //       markup and legitimately carry angle brackets. Extracting the value's span by the
        //       prefix length is what keeps the assertion about the data rather than about the wrapper.
        String rendered = cell.substring(StatementHtmlMapper.NAME_CELL_PREFIX.length(),
                cell.indexOf(StatementHtmlMapper.CELL_SUFFIX));
        assertThat(rendered).doesNotContain("<").doesNotContain(">");
    }

    /**
     * Confirms the quote characters that break out of an attribute value are escaped.
     */
    @Test
    @DisplayName("both quote characters are escaped rather than only the angle brackets")
    void bothQuoteCharactersAreEscaped() {
        List<byte[]> records = StatementHtmlMapper.emitTransactionRow(
                rowWithDescription(item("A\"B'C", DESCRIPTION_ITEM_WIDTH)));

        assertThat(text(records.get(DESCRIPTION_INDEX)))
                .isEqualTo(expectedRecord(StatementHtmlMapper.PLAIN_CELL_PREFIX
                        + "A&quot;B&#39;C" + " ".repeat(DESCRIPTION_ITEM_WIDTH - 5)
                        + StatementHtmlMapper.CELL_SUFFIX));
    }

    /**
     * Confirms a record-breaking control character is refused and never reaches a record.
     *
     * <p>Assumptions: the artifact is a fixed-length record data set, so an embedded line feed does not
     * merely look wrong -- it forges a record boundary and displaces every record after it. Refusal is
     * therefore asserted for the newline and the carriage return specifically, and the diagnostic is
     * asserted to name the component while withholding the value.
     */
    @Test
    @DisplayName("a record-breaking control character is refused and the diagnostic withholds the"
            + " value")
    void aControlCharacterIsRefusedWithoutQuotingTheValue() {
        for (String control : List.of("\n", "\r", "\u0000", "\u001b")) {
            String hostile = item("AB" + control + "CD", DESCRIPTION_ITEM_WIDTH);
            assertThatThrownBy(() ->
                    StatementHtmlMapper.emitTransactionRow(rowWithDescription(hostile)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("description")
                    .hasMessageContaining("position 2")
                    .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("AB"));
        }
    }

    /**
     * Confirms a character outside US-ASCII is refused rather than silently substituted.
     *
     * <p>Assumptions: the encoder this artifact uses substitutes a replacement byte for such a
     * character, so accepting one would put a byte into a customer's statement that the customer's data
     * does not contain, and nothing downstream could tell that had happened. Refusal is the only
     * outcome in which the substitution cannot go unnoticed.
     */
    @Test
    @DisplayName("a character outside US-ASCII is refused rather than encoded as a replacement byte")
    void aNonAsciiCharacterIsRefused() {
        assertThatThrownBy(() -> StatementHtmlMapper.emitTransactionRow(
                rowWithDescription(item("AB\u00e9CD", DESCRIPTION_ITEM_WIDTH))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description")
                .hasMessageContaining("US-ASCII");

        assertThatThrownBy(() -> StatementHtmlMapper.emitTransactionRow(
                rowWithDescription(item("AB\u007fCD", DESCRIPTION_ITEM_WIDTH))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    /**
     * Confirms the tightest cell fails closed when escaping expands it past the record length.
     *
     * <p>Assumptions: the boundary is asserted from BOTH sides on the assembled address, which is the
     * tightest cell in the artifact: its prefix, its declared width, its blank pair and its suffix
     * occupy 89 of the 100 characters, leaving eleven. A double quote expands by five, so two of them
     * fit at 99 and three overrun at 104. Asserting only the overrun would pass for an implementation
     * that refused every quote, and asserting only the accepted case would pass for one that truncated
     * instead of refusing.
     */
    @Test
    @DisplayName("the tightest cell accepts two expanding characters and refuses three")
    void theTightestCellFailsClosedWhenEscapingOverrunsTheRecord() {
        String twoQuotes = "X".repeat(ASSEMBLED_ADDRESS_WIDTH - 2) + "\"\"";
        String threeQuotes = "X".repeat(ASSEMBLED_ADDRESS_WIDTH - 3) + "\"\"\"";
        assertThat(twoQuotes).hasSize(ASSEMBLED_ADDRESS_WIDTH);
        assertThat(threeQuotes).hasSize(ASSEMBLED_ADDRESS_WIDTH);

        assertThatCode(() -> StatementHtmlMapper.emitNameAddressAndBasicDetails(
                headerWithAddress(twoQuotes))).doesNotThrowAnyException();

        assertThatThrownBy(() -> StatementHtmlMapper.emitNameAddressAndBasicDetails(
                headerWithAddress(threeQuotes)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assembledAddress")
                .hasMessageContaining(String.valueOf(StatementHtmlMapper.HTML_RECORD_LENGTH))
                .hasMessageContaining("withheld");
    }

    /**
     * Confirms the overlength diagnostic reports lengths only and never the assembled content.
     *
     * <p>Assumptions: the marker searched for is a distinctive string placed inside the offending
     * value, so its absence from the message is evidence that no part of the value was reproduced. The
     * declared literals are searched for as well, because the assembled content is the value wrapped in
     * them and a message quoting the wrapper would be quoting the value's position in the document.
     */
    @Test
    @DisplayName("the overlength diagnostic reports lengths and never the statement content")
    void theOverlengthDiagnosticWithholdsTheContent() {
        String marker = "SECRETPAYLOAD";
        String hostile = marker + "X".repeat(ASSEMBLED_ADDRESS_WIDTH - marker.length() - 3) + "\"\"\"";
        assertThat(hostile).hasSize(ASSEMBLED_ADDRESS_WIDTH);

        assertThatThrownBy(() -> StatementHtmlMapper.emitNameAddressAndBasicDetails(
                headerWithAddress(hostile)))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertThat(failure.getMessage())
                        .doesNotContain(marker)
                        .doesNotContain("&quot;")
                        .doesNotContain(StatementHtmlMapper.CELL_SUFFIX)
                        .contains("104")
                        .contains("excess of 4"));
    }

    /**
     * Confirms the account heading refuses anything but the digits and blanks its item declares.
     *
     * <p>Assumptions: this operation is guarded by a character domain rather than by escaping, because
     * its assembled heading must be exactly its declared length and any expansion would fail that.
     * Both directions are asserted: a conforming item is accepted, and twenty characters of markup are
     * refused rather than reaching the document as a heading.
     */
    @Test
    @DisplayName("the account heading refuses any character outside digits and blanks")
    void theAccountHeadingRefusesAnythingButDigitsAndBlanks() {
        assertThatCode(() -> StatementHtmlMapper.emitDocumentHeader(
                item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH))).doesNotThrowAnyException();

        String hostile = "<script>abcdefghij</";
        assertThat(hostile).hasSize(ACCOUNT_ID_ITEM_WIDTH);
        assertThatThrownBy(() -> StatementHtmlMapper.emitDocumentHeader(hostile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountIdItem")
                .hasMessageContaining("position 0");
    }

    /**
     * Every record of every emission is exactly the declared length, whatever the emitter.
     *
     * <p>Assumptions: this sweeps all four emitters in one case rather than asserting the length inside
     * each behavioural test, because the property belongs to the record contract and not to any one
     * emitter. A new emitter that assembled its own records without padding them would fail here even
     * if it passed every test written for its own behaviour.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every record of every emission is exactly the declared length")
    void everyRecordOfEveryEmissionIsTheDeclaredLength() {
        List<List<byte[]>> emissions = List.of(
                StatementHtmlMapper.emitDocumentHeader(item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH)),
                StatementHtmlMapper.emitNameAddressAndBasicDetails(conformingHeader()),
                StatementHtmlMapper.emitTransactionRow(
                        rowWithDescription(item(DESCRIPTION_TEXT, DESCRIPTION_ITEM_WIDTH))),
                StatementHtmlMapper.emitDocumentFooter());

        assertThat(emissions).allSatisfy(records -> assertThat(records)
                .isNotEmpty()
                .allSatisfy(record ->
                        assertThat(record).hasSize(StatementHtmlMapper.HTML_RECORD_LENGTH)));
    }

    /**
     * The four declared record counts hold exactly for conforming values.
     *
     * <p>Assumptions: the counts are asserted as EXACT rather than as minima, which is the whole of the
     * geometry decision this class makes. One cell occupies one record and a value that would widen past
     * the record length is refused at the sink, so an emission's arity is fixed by the reference
     * paragraph it reproduces and never by the data. An implementation that continued an over-wide cell
     * onto a further record would satisfy a minimum and break the reference's line numbering.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the four declared record counts hold exactly for conforming values")
    void theDeclaredCountsHoldExactlyForConformingValues() {
        assertThat(StatementHtmlMapper.emitDocumentHeader(item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH)))
                .hasSize(StatementHtmlMapper.DOCUMENT_HEADER_LINE_COUNT);
        assertThat(StatementHtmlMapper.emitNameAddressAndBasicDetails(conformingHeader()))
                .hasSize(StatementHtmlMapper.NAME_ADDRESS_BASIC_DETAIL_LINE_COUNT);
        assertThat(StatementHtmlMapper.emitTransactionRow(
                rowWithDescription(item(DESCRIPTION_TEXT, DESCRIPTION_ITEM_WIDTH))))
                .hasSize(StatementHtmlMapper.TRANSACTION_ROW_LINE_COUNT);
        assertThat(StatementHtmlMapper.emitDocumentFooter())
                .hasSize(StatementHtmlMapper.DOCUMENT_FOOTER_LINE_COUNT);
    }

    /**
     * Each emitter that takes an argument refuses a null one rather than rendering a literal null.
     *
     * <p>Assumptions: asserted because the alternative failure is silent rather than loud. String
     * concatenation renders a null reference as the four characters {@code null}, so an emitter that did
     * not check would produce a well-formed record of exactly the declared length carrying the word
     * null where a customer's name belongs, and every geometry assertion in this class would pass.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("each emitter refuses a null argument rather than rendering the word null")
    void eachEmitterRefusesANullArgument() {
        assertThatThrownBy(() -> StatementHtmlMapper.emitDocumentHeader(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StatementHtmlMapper.emitNameAddressAndBasicDetails(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StatementHtmlMapper.emitTransactionRow(null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * The blank-pair cut is applied before the escaping, not after it.
     *
     * <p>Assumptions: the order is observable and is asserted for that reason. Escaping first would let
     * an entity reference introduced by the escaping supply the blank pair the cut looks for, or move the
     * pair the value carried, so a value would be cut at a position the reference program's own
     * {@code UNSTRING} would not have cut it at. The value here carries a metacharacter before its blank
     * pair and text after it: the metacharacter must arrive escaped and the trailing text must be
     * gone.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the blank-pair cut precedes the escaping")
    void theBlankPairCutPrecedesTheEscaping() {
        String emitted = text(StatementHtmlMapper.emitNameAddressAndBasicDetails(
                headerWithName(item("A&B  CUTAWAY", ASSEMBLED_NAME_WIDTH))).get(NAME_CELL_INDEX));

        assertThat(emitted).contains("A&amp;B").doesNotContain("CUTAWAY");
    }

    /**
     * Every one of the five markup-significant characters is replaced by its reference.
     *
     * <p>Assumptions: all five are swept in one case because the escaping is a switch and a switch is
     * exactly the shape in which one arm goes missing without the others noticing. The value is checked
     * BOTH ways round -- the raw character absent and the reference present -- because an escaper that
     * dropped a character rather than replacing it would satisfy the first assertion alone.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every markup-significant character is replaced by its reference")
    void everyMarkupSignificantCharacterIsReplaced() {
        Map<String, String> references = Map.of(
                "&", "&amp;", "<", "&lt;", ">", "&gt;", "\"", "&quot;", "'", "&#39;");

        references.forEach((hostile, reference) -> {
            String emitted = text(StatementHtmlMapper.emitTransactionRow(rowWithDescription(
                    item("A" + hostile + "B", DESCRIPTION_ITEM_WIDTH))).get(DESCRIPTION_INDEX));

            assertThat(emitted)
                    .as("%s must not reach the document unescaped", hostile)
                    .doesNotContain("A" + hostile + "B");
            assertThat(emitted)
                    .as("%s must arrive as %s", hostile, reference)
                    .contains("A" + reference + "B");
        });
    }

    /**
     * A script element in the transaction description is rendered as text.
     *
     * <p>Assumptions: the description sink is asserted separately from the name sink because the two
     * reach the escaping by different regimes -- the description is transferred whole and the name is cut
     * at its first blank pair -- so a regression confined to one regime would leave the other passing.
     * The description is also the only one of the eleven sinks fed from transaction data rather than from
     * customer data, which is the half an operator cannot vet.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a script element in the transaction description is rendered as text")
    void aScriptElementInTheDescriptionIsRenderedAsText() {
        String emitted = text(StatementHtmlMapper.emitTransactionRow(rowWithDescription(
                item("<script>alert(1)</script>", DESCRIPTION_ITEM_WIDTH))).get(DESCRIPTION_INDEX));

        assertThat(emitted).doesNotContain("<script").doesNotContain("</script");
        assertThat(emitted).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }


    /**
     * No declared fragment and no cell prefix opens an attribute a dynamic value could reach.
     *
     * <p>Assumptions: this assertion is what makes the text-context escape SUFFICIENT rather than
     * merely necessary. Escaping for a text node does not make a value safe in a URL or a script
     * context -- a value placed in an {@code href} can carry a script scheme with no bracket in it
     * at all -- so the claim that the text escape is the whole obligation depends on the artifact
     * having no such context. Walking the whole fragment table means a fragment added later cannot
     * introduce one without failing here.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("no declared fragment or cell prefix opens a URL or script attribute")
    void noFragmentOpensAUrlOrScriptAttribute() {
        List<String> literals = Stream.concat(
                        StatementHtmlMapper.FRAGMENT_TABLE.stream(),
                        Stream.of(StatementHtmlMapper.NAME_CELL_PREFIX,
                                StatementHtmlMapper.PLAIN_CELL_PREFIX,
                                StatementHtmlMapper.ACCOUNT_ID_CELL_PREFIX,
                                StatementHtmlMapper.CURRENT_BALANCE_CELL_PREFIX,
                                StatementHtmlMapper.FICO_SCORE_CELL_PREFIX,
                                StatementHtmlMapper.ACCOUNT_HEADING_PREFIX,
                                StatementHtmlMapper.ACCOUNT_HEADING_SUFFIX))
                .toList();

        assertThat(literals)
                .as("the fragment table is complete and the prefixes are counted with it")
                .hasSize(StatementHtmlMapper.FRAGMENT_COUNT + NON_FRAGMENT_LITERAL_COUNT);
        assertThat(literals).allSatisfy(literal -> {
            String lowered = literal.toLowerCase(Locale.ROOT);
            assertThat(URL_ATTRIBUTE_NAMES)
                    .as("the literal %s opens no URL attribute", literal)
                    .noneSatisfy(urlAttribute -> assertThat(lowered).contains(urlAttribute));
            assertThat(EVENT_HANDLER_ATTRIBUTE.matcher(lowered).find())
                    .as("the literal %s declares no inline event handler", literal)
                    .isFalse();
        });
    }
}
