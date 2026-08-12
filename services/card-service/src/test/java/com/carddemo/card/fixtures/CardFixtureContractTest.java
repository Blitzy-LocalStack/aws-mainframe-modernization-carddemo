package com.carddemo.card.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

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

    /** The classpath location of the contract the page size is read from. */
    private static final String CONTRACT_RESOURCE = "/openapi/card-api.yaml";

    /**
     * The binding every cursor in this class is sealed and opened under.
     *
     * <p>Assumptions: one constant binding is used throughout because the property under test is the
     * page arithmetic, not the binding's own discrimination, which {@code common-lib}'s own tests own.
     * Sealing under a name that identifies the query is what a service does, so the shape of the value
     * is representative even though its discrimination is asserted elsewhere.
     */
    private static final String CURSOR_BINDING = "card.list";

    /**
     * The key material the cursor sealer in this class is built over.
     *
     * <p>Assumptions: thirty-two bytes is {@link CursorToken#MIN_KEY_LENGTH}, the shortest this type
     * accepts, and the value is fixed rather than random so that a failure is reproducible from the
     * source alone. It is test-only material that seals test-only tokens and reaches no configuration.
     */
    private static final byte[] CURSOR_KEY =
            "card-fixture-cursor-key-32-bytes".getBytes(StandardCharsets.US_ASCII);

    /** The sealer every boundary token in this class is produced and read by. */
    private static final CursorToken SEALER = new CursorToken(CURSOR_KEY, Duration.ofMinutes(15));

    /**
     * Reads the page size this service publishes, rather than restating it as a literal.
     *
     * <p>Assumptions: the value lives on {@code CardPage.items.maxItems} in the published contract,
     * which is the one place a client can read it from, so a test measuring the corpus against anything
     * else could agree with a number the service does not publish. The contract is parsed rather than
     * matched by pattern so that a moved or renamed schema fails as a missing key here instead of as a
     * silently unmatched regular expression.
     *
     * @return the maximum number of rows one card-list page may carry
     * @throws IllegalStateException if the contract is absent from the classpath, is not a mapping, or
     *     does not declare the bound where this method expects it, any of which would leave the page
     *     arithmetic below measured against nothing
     */
    private static int contractedPageSize() {
        Object parsed;
        try (InputStream resource =
                CardFixtureContractTest.class.getResourceAsStream(CONTRACT_RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException(
                        "the published contract is absent from the classpath at " + CONTRACT_RESOURCE);
            }
            parsed = new Yaml().load(resource);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "the published contract at " + CONTRACT_RESOURCE + " could not be read", failure);
        }
        Object bound = parsed;
        for (String key : List.of("components", "schemas", "CardPage", "properties", "items",
                "maxItems")) {
            if (!(bound instanceof Map<?, ?> mapping) || !mapping.containsKey(key)) {
                throw new IllegalStateException(
                        "the published contract declares no \"" + key + "\" where the card page bound"
                                + " is expected");
            }
            bound = mapping.get(key);
        }
        if (!(bound instanceof Integer size)) {
            throw new IllegalStateException("the card page bound is not an integer");
        }
        return size;
    }

    /**
     * Returns the page corpus's card numbers in file order.
     *
     * @return the eighteen card numbers, ascending, as the index scan would yield them
     * @throws IOException if the fixture cannot be read
     */
    private static List<String> pageCorpusKeys() throws IOException {
        return rows(bytes("card-list-page-corpus.txt")).stream()
                .map(r -> r.get("CARD-NUM").toString()).toList();
    }

    /**
     * Seals one raw card number as a boundary token.
     *
     * @param cursorKey the raw card number of the boundary row
     * @return the sealed token a page response may carry
     */
    private static String seal(String cursorKey) {
        return SEALER.seal(CURSOR_BINDING, cursorKey);
    }

    /**
     * Opens a page's leading boundary token back to the raw key it names.
     *
     * @param page the page to read the leading boundary of
     * @return the raw card number the token names
     */
    private static String openedFirstKey(PageResponse<String> page) {
        return SEALER.open(CURSOR_BINDING, page.firstKey());
    }

    /**
     * Opens a page's trailing boundary token back to the raw key it names.
     *
     * @param page the page to read the trailing boundary of
     * @return the raw card number the token names
     */
    private static String openedLastKey(PageResponse<String> page) {
        return SEALER.open(CURSOR_BINDING, page.lastKey());
    }

    /**
     * Reads one forward page out of an ordered key list, exactly as the contract describes the query.
     *
     * <p>Assumptions: the read takes {@code size + 1} rows so that the further-page signal comes from
     * the existence of a row beyond the page and never from a count of rows or pages. The surplus row is
     * discarded rather than returned, which is the property the trailing boundary assertion depends on:
     * it is read, its presence is reported, and it goes nowhere.
     *
     * @param keys the ordered corpus standing in for the index scan
     * @param afterKey the raw key to read strictly beyond, or {@code null} to open the scan
     * @param size the contracted page size
     * @return the page the contract says this read produces, built by the production factory
     */
    private static PageResponse<String> forwardPage(List<String> keys, String afterKey, int size) {
        List<String> read = keys.stream()
                .filter(key -> afterKey == null || key.compareTo(afterKey) > 0)
                .limit(size + 1L)
                .toList();
        if (read.isEmpty()) {
            return PageResponse.empty();
        }
        boolean hasNext = read.size() > size;
        List<String> page = hasNext ? read.subList(0, size) : read;
        return PageResponse.ofRows(
                page, seal(page.get(0)), seal(page.get(page.size() - 1)), hasNext);
    }

    /**
     * Reads one backward page out of an ordered key list, exactly as the contract describes the query.
     *
     * <p>Assumptions: the rows are taken in descending order and then reversed, so a backward step lands
     * on the page boundary a forward walk used rather than on a window measured from the wrong end. The
     * further-page flag is reported unconditionally, because a caller can only page backward from a page
     * it paged forward to, and the reference asserts the same thing unconditionally on its backward path.
     *
     * @param keys the ordered corpus standing in for the index scan
     * @param beforeKey the raw key to read strictly below
     * @param size the contracted page size
     * @return the page the contract says this read produces, built by the production factory
     */
    private static PageResponse<String> backwardPage(List<String> keys, String beforeKey, int size) {
        List<String> descending = keys.stream()
                .filter(key -> key.compareTo(beforeKey) < 0)
                .sorted(Comparator.reverseOrder())
                .limit(size + 1L)
                .toList();
        if (descending.isEmpty()) {
            return PageResponse.empty();
        }
        List<String> trimmed = descending.size() > size ? descending.subList(0, size) : descending;
        List<String> page = new ArrayList<>(trimmed);
        page.sort(Comparator.naturalOrder());
        return PageResponse.ofRows(page, seal(page.get(0)), seal(page.get(page.size() - 1)), true);
    }

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
     * Asserts that the page corpus spans more than one page of the contracted size and is ordered.
     *
     * <p>Refactoring Rationale: the page size is read from the published contract rather than written
     * here as a literal, and it is seven rather than the ten an earlier revision of this rationale
     * stated. Seven is what both authorities say: {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7}
     * at {@code app/cbl/COCRDLIC.cbl} lines 177 and 178, whose row table and per-row error table both
     * declare {@code OCCURS 7 TIMES} at lines 76 and 86, and {@code maxItems: 7} on
     * {@code CardPage.items} in this module's own {@code openapi/card-api.yaml}, settled server-side by
     * {@code carddemo.card.list.page-size} in {@code application.yml}. Ten is the page size of the
     * transaction list, a different screen in a different module, so a literal here could agree with
     * the wrong screen and no assertion would notice. Reading the contract means the corpus is measured
     * against the number this service actually publishes.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the page corpus spans more than one contracted page and is ordered by card number")
    void thePageCorpusExceedsOnePageAndIsOrdered() throws IOException {
        List<String> numbers = pageCorpusKeys();
        int pageSize = contractedPageSize();

        // WHY : Assumptions: eighteen rows against a page of seven is not an arbitrary surplus. It
        //       yields TWO full pages, each with a row beyond it for the further-page probe to find,
        //       and a terminal third page of four that has no such row -- so one corpus reaches every
        //       state the envelope can report, including the transition from a further page existing
        //       to it not existing. A corpus of eight would reach the first full page and the terminal
        //       one but never a middle page, which is the only page whose two boundaries are both
        //       interior rows.
        assertThat(pageSize).isEqualTo(7);
        assertThat(numbers).hasSize(18).isSorted().doesNotHaveDuplicates();
        assertThat(numbers.size() / pageSize).isEqualTo(2);
        assertThat(numbers.size() % pageSize).isEqualTo(4);
        assertThat(numbers.get(0)).isEqualTo("0500024453765740");
        assertThat(numbers.get(17)).isEqualTo("4011500891777367");
    }

    /**
     * Asserts that the corpus pages forward through the contracted keyset envelope as 7, 7 then 4.
     *
     * <p>Refactoring Rationale: this case exists because the corpus previously had no executable gate
     * on the arithmetic it was built for. Its ordering and its size were asserted, which leaves every
     * property the eighteen rows exist to exercise -- where each page starts and stops, which row the
     * trailing token names, when a further page is reported -- stated in prose only. Those are exactly
     * the properties a wrong page size produces wrong answers for while an ordering assertion stays
     * green, so the page boundaries are now computed and compared row by row. This case together with
     * the backward one below is the executable form of what section 9.7 of the fixture tree's README
     * already claims of these rows: three pages of seven, seven and four, yielding two interior
     * boundaries, each traversable in both directions, with the read-one-extra lookahead exercised at
     * both. The section is named rather than paraphrased so that the two can be checked against each
     * other.
     *
     * <p>Assumptions: the envelope is built by the production {@link PageResponse#ofRows} and its
     * tokens by the production {@link CursorToken}, so the invariants under test are the shipped ones
     * and not a restatement -- a page carrying rows without both boundary tokens, a page reporting a
     * further page without a trailing token, or a boundary that is a raw key rather than a sealed token
     * are all refused by that constructor rather than by an assertion written here. The page size comes
     * from the published contract and the boundary rows from the corpus, so nothing about the arithmetic
     * is supplied by this class as an expected answer.
     *
     * <p>Assumptions: what stands in for production code here is the STORE alone, and that is a
     * measured property of this module rather than a preference. Under
     * {@code src/main/java/com/carddemo/card} the authored classes are one entity, three DTOs, three
     * configurations and the application entry point; {@code api}, {@code service}, {@code repository}
     * and {@code mapper} hold a {@code package-info.java} and nothing else, so there is no list
     * repository to query and no list service to call -- a test cannot drive a class that is not
     * authored. The ordered corpus therefore stands in for the index scan, and the walk over it is the
     * same strictly-greater-than, ascending, limit-size-plus-one read the contract describes.
     * Alternatives Considered: waiting for those classes to land and gating the arithmetic then, or
     * standing up a hand-written repository double to call a service through. The first leaves the
     * corpus with no gate at all in the meantime, which is the state this case exists to end; the
     * second asserts a page shape produced by a double this class also wrote, which is agreement with
     * itself rather than evidence. Driving the shipped envelope and cursor types over the shipped
     * contract's page size is the part of the contract that can be gated now, and it is the part a
     * wrong page size actually breaks.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the corpus pages forward through the contracted envelope as seven, seven then four")
    void theCorpusPagesForwardAsSevenSevenFour() throws IOException {
        List<String> keys = pageCorpusKeys();
        int size = contractedPageSize();

        PageResponse<String> first = forwardPage(keys, null, size);
        assertThat(first.items()).containsExactlyElementsOf(keys.subList(0, 7));
        assertThat(first.hasNext()).isTrue();
        assertThat(openedFirstKey(first)).isEqualTo(keys.get(0));

        // WHY : Assumptions: this is the assertion the whole case is built around. The trailing token
        //       must name row SEVEN, the last row the caller received, and never row EIGHT, the row the
        //       probe read to settle the further-page signal. The two are different rows and both are
        //       present in this corpus, so a token taken from the probe row would still open, still
        //       verify and still page forward -- it would merely skip a row, which no size or ordering
        //       assertion can see. The contract states the same boundary on CardPage.lastKey and
        //       registers the divergence from the reference arrangement as D-LASTKEY-RECEIVED.
        assertThat(openedLastKey(first)).isEqualTo(keys.get(6)).isNotEqualTo(keys.get(7));

        PageResponse<String> second = forwardPage(keys, openedLastKey(first), size);
        assertThat(second.items()).containsExactlyElementsOf(keys.subList(7, 14));
        assertThat(second.hasNext()).isTrue();
        assertThat(openedFirstKey(second)).isEqualTo(keys.get(7));
        assertThat(openedLastKey(second)).isEqualTo(keys.get(13)).isNotEqualTo(keys.get(14));

        PageResponse<String> third = forwardPage(keys, openedLastKey(second), size);

        // WHY : Assumptions: the terminal page reports no further page because the read beyond it found
        //       nothing, and NOT because it returned fewer rows than the page holds. The two reasons
        //       coincide here and must not be conflated: the contract says a page may carry fewer than
        //       seven rows while further rows still exist, so the row count is never the signal. The
        //       page is asserted to carry four AND to report no further page as two separate facts.
        assertThat(third.items()).containsExactlyElementsOf(keys.subList(14, 18));
        assertThat(third.items()).hasSize(4);
        assertThat(third.hasNext()).isFalse();
        assertThat(openedFirstKey(third)).isEqualTo(keys.get(14));
        assertThat(openedLastKey(third)).isEqualTo(keys.get(17));

        // WHY : Assumptions: paging on from the terminal page yields the exhausted envelope rather than
        //       an error or a repeat of the last page, and the exhausted envelope carries no tokens at
        //       all. Asserting it is what stops an off-by-one at the end of the scan from presenting as
        //       an endlessly repeating final page.
        PageResponse<String> beyond = forwardPage(keys, openedLastKey(third), size);
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.hasNext()).isFalse();
        assertThat(beyond.firstKey()).isNull();
        assertThat(beyond.lastKey()).isNull();

        // WHY : Assumptions: no page may exceed the contracted maximum, which is asserted over every
        //       page rather than page by page, because the array bound is a property of the response
        //       schema and a single page carrying an eighth row would be a defect in the service.
        assertThat(List.of(first, second, third, beyond))
                .allSatisfy(page -> assertThat(page.items()).hasSizeLessThanOrEqualTo(size));
    }

    /**
     * Asserts that the corpus pages backward to the exact pages the forward walk produced.
     *
     * <p>Assumptions: the backward read is issued from the leading token of the page in hand, is
     * strictly less than it, is taken in DESCENDING order and is then reversed, which is what makes a
     * backward step land on the page boundary the forward walk used rather than on an arbitrary window
     * seven rows back. The reference reads its backward browse the same way, and reports a further page
     * on it unconditionally, because a caller can only be paging backward from a page it paged forward
     * to; the contract states that on CardPage.hasNext and this case asserts it on a page where nothing
     * whatever precedes, which is the input on which an implementation deriving the flag from the store
     * would answer false.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the corpus pages backward onto the same boundaries the forward walk produced")
    void theCorpusPagesBackwardOntoTheSameBoundaries() throws IOException {
        List<String> keys = pageCorpusKeys();
        int size = contractedPageSize();

        PageResponse<String> backToSecond = backwardPage(keys, keys.get(14), size);
        assertThat(backToSecond.items()).containsExactlyElementsOf(keys.subList(7, 14));
        assertThat(openedFirstKey(backToSecond)).isEqualTo(keys.get(7));
        assertThat(openedLastKey(backToSecond)).isEqualTo(keys.get(13));
        assertThat(backToSecond.hasNext()).isTrue();

        PageResponse<String> backToFirst = backwardPage(keys, openedFirstKey(backToSecond), size);
        assertThat(backToFirst.items()).containsExactlyElementsOf(keys.subList(0, 7));
        assertThat(openedFirstKey(backToFirst)).isEqualTo(keys.get(0));
        assertThat(openedLastKey(backToFirst)).isEqualTo(keys.get(6));
        assertThat(backToFirst.hasNext()).isTrue();

        // WHY : Assumptions: stepping back from the very first row yields the exhausted envelope, and
        //       the row it was issued from is excluded because the predicate is STRICTLY less than. A
        //       predicate written as less-than-or-equal would return that row again here, which is a
        //       repeated row rather than an error and would survive every count assertion above.
        PageResponse<String> beforeFirst = backwardPage(keys, keys.get(0), size);
        assertThat(beforeFirst.items()).isEmpty();
        assertThat(beforeFirst.firstKey()).isNull();
        assertThat(beforeFirst.lastKey()).isNull();
    }

    /**
     * Asserts that the envelope refuses a boundary that is a raw card number rather than a sealed token.
     *
     * <p>Assumptions: this is asserted here, over a key taken from the corpus, because the corpus keys
     * are the raw values a service holds at the moment it builds a page, and passing one of them
     * straight into the envelope is the mistake the sealing requirement exists to catch. A raw
     * sixteen-digit card number is the most plausible such value: it is well formed, it identifies the
     * boundary row correctly, and an envelope that accepted it would hand a client a card number as a
     * cursor.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the envelope refuses a raw card number in place of a sealed boundary token")
    void theEnvelopeRefusesARawCardNumberAsABoundary() throws IOException {
        List<String> keys = pageCorpusKeys();
        List<String> onePage = keys.subList(0, contractedPageSize());

        assertThatThrownBy(() -> PageResponse.ofRows(onePage, keys.get(0), keys.get(6), true))
                .isInstanceOf(IllegalArgumentException.class);

        // WHY : Assumptions: the positive direction is asserted beside the negative one so that the
        //       refusal above is known to be about the SEALING and not about some other property of
        //       these arguments -- the identical call with the same two keys sealed is accepted.
        assertThatCode(
                () -> PageResponse.ofRows(
                        onePage, seal(keys.get(0)), seal(keys.get(6)), true))
                .doesNotThrowAnyException();
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
