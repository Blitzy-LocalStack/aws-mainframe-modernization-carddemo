package com.carddemo.card.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds every card fixture to the 150-byte record contract and to the values its README states.
 *
 * <p>Assumptions: the twelve fixtures in this tree had no executable consumer, so their bytes could
 * change while every test in the module stayed green -- the module's only other tests read the
 * published OpenAPI contract, not these files. Each record is decoded through the production
 * {@code CARD} layout rather than against offsets restated here, so a change to the registered layout
 * that broke these files fails in this class instead of in a service not yet authored.</p>
 */
class CardFixtureContractTest {

    /** The classpath prefix every card fixture resolves under. */
    private static final String ROOT = "fixtures/";

    /** The declared record length of {@code CARD-RECORD}. */
    private static final int RECLEN = 150;

    /** The registry name of the layout every fixture in this tree is read through. */
    private static final String LAYOUT = "CARD";

    /**
     * Reads one fixture from the test classpath.
     *
     * @param name the file name below {@value #ROOT}, for example {@code card-valid-active.txt}
     * @return the file's exact bytes
     * @throws IllegalStateException if the resource is absent from the classpath
     * @throws IOException if the stream cannot be read
     */
    private static byte[] bytes(String name) throws IOException {
        try (InputStream in = CardFixtureContractTest.class.getClassLoader()
                .getResourceAsStream(ROOT + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture absent from the test classpath: "
                        + ROOT + name);
            }
            return in.readAllBytes();
        }
    }

    /**
     * Splits a fixture into 150-byte records, each followed by exactly one line feed.
     *
     * @param raw the file's exact bytes
     * @return the decoded field map for each record, in file order
     */
    private static List<Map<String, Object>> rows(byte[] raw) {
        assertThat(raw.length % (RECLEN + 1))
                .as("file length %d is not a whole number of %d-byte records plus one LF each",
                        raw.length, RECLEN)
                .isZero();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int off = 0; off + RECLEN <= raw.length; off += RECLEN + 1) {
            assertThat(raw[off + RECLEN]).isEqualTo((byte) '\n');
            byte[] rec = new byte[RECLEN];
            System.arraycopy(raw, off, rec, 0, RECLEN);
            out.add(FixedWidthCodec.decodeRecord(rec, CopybookLayout.layout(LAYOUT),
                    StandardCharsets.US_ASCII));
        }
        return out;
    }

    /**
     * Supplies every fixture with the record count its name and README imply.
     *
     * @return one argument set per fixture
     */
    private static Stream<Arguments> allFixtures() {
        return Stream.of(
                Arguments.of("card-valid-active.txt", 1),
                Arguments.of("card-valid-inactive.txt", 1),
                Arguments.of("card-schema-reject-status-out-of-domain.txt", 1),
                Arguments.of("card-rule-reject-name-blank.txt", 2),
                Arguments.of("card-rule-reject-name-non-alpha.txt", 2),
                Arguments.of("card-rule-reject-expiry-year-out-of-range.txt", 2),
                Arguments.of("card-schema-reject-expiry-month-out-of-range.txt", 2),
                Arguments.of("card-expiry-day-preserved.txt", 3),
                Arguments.of("card-boundary-expiry-inclusive.txt", 4),
                Arguments.of("card-by-account-corpus.txt", 4),
                Arguments.of("card-list-page-corpus.txt", 18));
    }

    /**
     * Asserts that every populated fixture holds whole 150-byte records and no carriage return.
     *
     * @param name the fixture file name
     * @param expectedRows the record count the fixture is expected to hold
     * @throws IOException if the fixture cannot be read
     */
    @ParameterizedTest(name = "{0} -> {1} records")
    @MethodSource("allFixtures")
    @DisplayName("every populated fixture holds whole 150-byte records and no carriage return")
    void everyFixtureHoldsWholeRecords(String name, int expectedRows) throws IOException {
        byte[] raw = bytes(name);

        assertThat(raw).hasSize(expectedRows * (RECLEN + 1));
        assertThat(rows(raw)).hasSize(expectedRows);
        assertThat(new String(raw, StandardCharsets.US_ASCII)).doesNotContain("\r");
    }

    /**
     * Asserts that the empty-input fixture is exactly zero bytes.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the empty-input fixture is exactly zero bytes")
    void theEmptyFixtureIsZeroBytes() throws IOException {
        // WHY : Assumptions: zero bytes is the only correct encoding of an empty fixed-width input.
        //       A file holding one blank line is a 1-byte file carrying one zero-length record and
        //       fails parsing instead of exercising the empty path, and an editor that appends a
        //       final newline on save is the realistic way that regresses.
        assertThat(bytes("card-empty-input.txt")).isEmpty();
    }

    /**
     * Asserts that the two single-record fixtures differ only in the active-status byte.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the two single-record fixtures differ only in the active-status byte")
    void theValidPairDiffersOnlyInStatus() throws IOException {
        Map<String, Object> active = rows(bytes("card-valid-active.txt")).get(0);
        Map<String, Object> inactive = rows(bytes("card-valid-inactive.txt")).get(0);

        assertThat(active.get("CARD-ACTIVE-STATUS")).isEqualTo("Y");
        assertThat(inactive.get("CARD-ACTIVE-STATUS")).isEqualTo("N");

        // WHY : Assumptions: the status domain is exactly the two letters, so a fixture carrying
        //       anything else belongs to the out-of-domain scenario rather than to this pair. The
        //       third fixture holds 'X' precisely so a consumer can assert the domain is closed,
        //       and it is checked here so the three files cannot drift into agreeing.
        assertThat(rows(bytes("card-schema-reject-status-out-of-domain.txt")).get(0)
                .get("CARD-ACTIVE-STATUS")).isEqualTo("X").isNotIn("Y", "N");
    }

    /**
     * Asserts that the expiry-boundary fixture brackets the accepted year range inclusively.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the expiry-boundary fixture brackets the accepted year range inclusively")
    void theExpiryBoundaryFixtureBracketsTheAcceptedYears() throws IOException {
        List<String> inclusive = rows(bytes("card-boundary-expiry-inclusive.txt")).stream()
                .map(r -> r.get("CARD-EXPIRAION-DATE").toString()).toList();
        List<String> rejected = rows(bytes("card-rule-reject-expiry-year-out-of-range.txt")).stream()
                .map(r -> r.get("CARD-EXPIRAION-DATE").toString()).toList();

        // WHY : Assumptions: the pair of files is the assertion, not either file alone. The
        //       inclusive fixture carries the first and last ACCEPTED years, 1950 and 2099, and the
        //       reject fixture carries the years one step outside them, 1949 and 2100. A boundary
        //       tested from one side only passes equally against an off-by-one bound, which is the
        //       specific defect this arrangement rules out.
        assertThat(inclusive).containsExactly("2024-01-15", "2024-12-15", "1950-06-15", "2099-06-15");
        assertThat(rejected).containsExactly("1949-06-15", "2100-06-15");
        assertThat(inclusive).noneMatch(d -> d.startsWith("1949") || d.startsWith("2100"));

        // WHY : Trade-offs: the four year-boundary rows deliberately share one month and day,
        //       '-06-15', rather than each keeping the month and day of the seed record its
        //       identity fields came from. Keeping the seed's own month and day would have left
        //       1949-05-19 facing 1950-06-15, where three components differ at once, so a rule
        //       that rejected on the MONTH or mis-read the date offset entirely would satisfy a
        //       year assertion just as well. Pinning the background costs a date that no longer
        //       matches its seed position component for component -- the same cost section 6.1 of
        //       the README accepts for the inclusive fixture -- and buys the guarantee that the
        //       year is the only variable, which is the whole point of the pairing.
        assertThat(inclusive.subList(2, 4)).allMatch(d -> d.endsWith("-06-15"));
        assertThat(rejected).allMatch(d -> d.endsWith("-06-15"));
    }

    /**
     * Asserts that the month-boundary fixture carries one month below and one above the range.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the month-boundary fixture carries one month below and one above the range")
    void theMonthBoundaryFixtureBracketsTheRange() throws IOException {
        List<String> dates = rows(bytes("card-schema-reject-expiry-month-out-of-range.txt")).stream()
                .map(r -> r.get("CARD-EXPIRAION-DATE").toString()).toList();

        // WHY : Assumptions: these values are refused for their MONTH and not for their year, so
        //       both years are inside the accepted 1950-to-2099 range on purpose. A fixture whose
        //       year was also out of range would be refused for either reason and could not
        //       demonstrate which check fired.
        assertThat(dates).containsExactly("2025-00-12", "2023-13-23");
        assertThat(dates).allSatisfy(d -> assertThat(Integer.parseInt(d.substring(0, 4)))
                .isBetween(1950, 2099));
    }

    /**
     * Asserts that the day-preserved fixture keeps three distinct days in one month.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the day-preserved fixture keeps three distinct days in one month")
    void theDayPreservedFixtureKeepsThreeDistinctDays() throws IOException {
        List<String> dates = rows(bytes("card-expiry-day-preserved.txt")).stream()
                .map(r -> r.get("CARD-EXPIRAION-DATE").toString()).toList();

        // WHY : Assumptions: the baseline stores a full ten-character date and the target must not
        //       normalise the day to a month end or a month start. Three different days inside one
        //       identical year and month is what makes a normalisation visible: if any consumer
        //       collapsed the day, all three rows would become equal.
        assertThat(dates).containsExactly("2025-06-01", "2025-06-15", "2025-06-28");
        assertThat(dates).doesNotHaveDuplicates();
        assertThat(dates).allSatisfy(d -> assertThat(d).startsWith("2025-06-"));
    }

    /**
     * Asserts that the name-reject fixtures carry two distinct kinds of unacceptable name.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the name-reject fixtures carry two distinct kinds of unacceptable name")
    void theNameRejectFixturesCarryTwoDistinctKinds() throws IOException {
        List<Map<String, Object>> blank = rows(bytes("card-rule-reject-name-blank.txt"));
        List<Map<String, Object>> nonAlpha = rows(bytes("card-rule-reject-name-non-alpha.txt"));

        // WHY : Assumptions: the file named "blank" carries TWO different empty-ish forms, and the
        //       distinction is worth pinning because only one of them is blank in the ordinary
        //       sense. Row 1 is fifty spaces; row 2 is fifty '0' characters, which is a populated
        //       field holding no alphabetic content. A consumer that tested only for spaces would
        //       accept the second.
        assertThat(blank.get(0).get("CARD-EMBOSSED-NAME")).isEqualTo(" ".repeat(50));
        assertThat(blank.get(1).get("CARD-EMBOSSED-NAME")).isEqualTo("0".repeat(50));

        // WHY : Assumptions: the non-alphabetic pair is likewise two different characters, an
        //       apostrophe inside an otherwise ordinary surname and a trailing digit. The
        //       apostrophe case is the one most likely to be "fixed" by a well-meaning edit, since
        //       O'Connell is a real name shape, so it is asserted explicitly.
        assertThat(nonAlpha.get(0).get("CARD-EMBOSSED-NAME").toString().strip())
                .isEqualTo("Lucious O'Connell").contains("'");
        assertThat(nonAlpha.get(1).get("CARD-EMBOSSED-NAME").toString().strip())
                .isEqualTo("Britney Waters 2").matches(".*\\d.*");
    }

    /**
     * Asserts that the by-account corpus groups several cards under one account identifier.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the by-account corpus groups several cards under one account identifier")
    void theByAccountCorpusGroupsCardsUnderOneAccount() throws IOException {
        List<Map<String, Object>> corpus = rows(bytes("card-by-account-corpus.txt"));

        List<String> accounts = corpus.stream().map(r -> r.get("CARD-ACCT-ID").toString()).toList();

        // WHY : Assumptions: this corpus exists for the secondary access path that replaces the
        //       CARDAIX alternate index, so it must contain a genuine one-to-many grouping and at
        //       least one other account to exclude. Three cards on 901 and one on 902 is the
        //       smallest shape that can fail if a query returned every row regardless of account.
        assertThat(accounts).containsExactly("901", "901", "901", "902");
        assertThat(accounts.stream().distinct().toList()).hasSize(2);
    }

    /**
     * Asserts that the page corpus holds more rows than one page and is ordered by card number.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the page corpus holds more rows than one page and is ordered by card number")
    void thePageCorpusExceedsOnePageAndIsOrdered() throws IOException {
        List<String> numbers = rows(bytes("card-list-page-corpus.txt")).stream()
                .map(r -> r.get("CARD-NUM").toString()).toList();

        // WHY : Assumptions: keyset paging is only exercised by a corpus larger than one page, and
        //       the reference grid is ten rows, so eighteen gives a full first page, a partial
        //       second and a surplus row to derive the further-page signal from. The ascending
        //       order is the base cluster key order a browse resumes in, so a corpus that was not
        //       sorted would make a keyset boundary meaningless.
        assertThat(numbers).hasSize(18).isSorted().doesNotHaveDuplicates();
        assertThat(numbers.get(0)).isEqualTo("0500024453765740");
        assertThat(numbers.get(17)).isEqualTo("4011500891777367");
    }

    /**
     * Asserts that a blank trailing FILLER is dropped from every decoded card record.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("a blank trailing FILLER is dropped from every decoded card record")
    void aBlankFillerIsDroppedFromEveryRecord() throws IOException {
        List<Map<String, Object>> corpus = rows(bytes("card-list-page-corpus.txt"));

        // WHY : Assumptions: the codec's FILLER rule is content-based -- a filler is omitted only
        //       when its decoded text is blank -- so this assertion is about these fixtures' bytes
        //       and not about FILLER in general. Every card record here pads with SPACES, so the
        //       59-byte filler is dropped and the map carries six of the seven declared fields. The
        //       reference-service fixtures pad the same construct with '0' characters and their
        //       fillers are consequently retained, which is the contrast that makes the rule
        //       content-based rather than positional.
        assertThat(corpus).allSatisfy(r -> {
            assertThat(r).hasSize(6);
            assertThat(r).doesNotContainKey("FILLER");
        });
        assertThat(corpus.get(0).keySet()).containsExactly("CARD-NUM", "CARD-ACCT-ID",
                "CARD-CVV-CD", "CARD-EMBOSSED-NAME", "CARD-EXPIRAION-DATE", "CARD-ACTIVE-STATUS");
    }

    /**
     * Asserts that the two sensitive fields decode to real values rather than masked ones.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the two sensitive fields decode to real values rather than masked ones")
    void theSensitiveFieldsDecodeUnmasked() throws IOException {
        Map<String, Object> only = rows(bytes("card-valid-active.txt")).get(0);

        // WHY : Assumptions: the layout marks the card number, the verification value and the
        //       embossed name sensitive, and that mark governs DIAGNOSTIC handling only. The codec
        //       must still return the actual bytes, because masking here would make an encode of the
        //       decoded map reproduce different bytes and break the round trip. Masking is a mapper
        //       decision, and asserting the unmasked value here is what keeps that boundary honest.
        assertThat(only.get("CARD-NUM")).isEqualTo("6509230362553816");
        assertThat(only.get("CARD-CVV-CD")).hasToString("236");
        assertThat(only.get("CARD-ACCT-ID")).hasToString("30");
    }

    /**
     * Asserts that a record fed at the wrong declared width is refused rather than mis-read.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("a record fed at the wrong declared width is refused rather than mis-read")
    void aRecordAtTheWrongWidthIsRefused() throws IOException {
        byte[] raw = bytes("card-valid-active.txt");
        byte[] truncated = new byte[RECLEN - 1];
        System.arraycopy(raw, 0, truncated, 0, RECLEN - 1);

        // WHY : Assumptions: a short record must raise rather than decode what it has and pad the
        //       rest, because a silent decode produces a plausible wrong value for every field
        //       after the truncation point instead of an error a maintainer can see.
        assertThatThrownBy(() -> FixedWidthCodec.decodeRecord(truncated,
                CopybookLayout.layout(LAYOUT), StandardCharsets.US_ASCII))
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class)
                .hasMessageContaining("149");
    }

    /**
     * Asserts that an absent fixture name fails loudly instead of silently testing nothing.
     */
    @Test
    @DisplayName("an absent fixture name fails loudly instead of silently testing nothing")
    void anAbsentFixtureNameFailsLoudly() {
        // WHY : Assumptions: the loader raises rather than returning null, because a consumer that
        //       resolved null and skipped would report success for a fixture that had been renamed
        //       or deleted. The probe name has never existed, so the assertion cannot be satisfied
        //       by a stale target/test-classes copy of a file removed from the source tree.
        assertThatThrownBy(() -> bytes("card-no-such-fixture.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absent from the test classpath");
    }
}
