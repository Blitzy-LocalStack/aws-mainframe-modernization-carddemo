package com.carddemo.reference.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.CopybookLayout.RecordSpec;
import com.carddemo.common.codec.FixedWidthCodec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Reads every fixture in this module's tree and asserts the byte contracts its charter declares.
 *
 * <p>Assumptions: each assertion below restates a claim the charter
 * {@code src/test/resources/fixtures/README.md} makes about these bytes, so a failure here means
 * either a fixture or that document is wrong and the two have parted company. Sections are cited
 * beside the assertions that carry them.</p>
 *
 * <p>Trade-offs: the fixtures are read off the TEST CLASS PATH rather than from the source tree.
 * Reading the source tree would let a test pass while the packaged artifact was missing a file, and
 * the charter section 9.2 makes the packaged copy the one a consumer sees. The cost is that a file
 * added to {@code src/test/resources} and not rebuilt is invisible here, which a clean build
 * removes.</p>
 */
class ReferenceFixtureTest {

    /** Root of the fixture tree on the test class path. */
    private static final String ROOT = "/fixtures/";

    /** The disclosure group whose rows the account-owned lookup answers from. */
    private static final String ACCOUNT_GROUP = "A000000000";

    /** The fallback group id, seven characters padded to the field's declared ten. */
    private static final String DEFAULT_GROUP = "DEFAULT   ";

    /** The one type and category pair at which the two groups' rates differ. */
    private static final String DISCRIMINATING_PAIR = "07|1";

    /**
     * Confirms every fixture is a whole number of records of its declared width, with LF endings.
     *
     * <p>Assumptions: this is the charter's own definition of a valid file, taken from three places at
     * once -- exact-length enforcement in section 5.2, LF-only derivation in section 6.1, and exactly
     * one trailing newline in section 5.6. A record file one byte adrift decodes as a length failure
     * rather than as bad data, so this is the assertion that has to run before any value assertion
     * means anything.</p>
     *
     * @param path the fixture path relative to the tree root
     * @param reclen the declared record length in bytes
     * @param records the number of records the charter says the file holds
     */
    @ParameterizedTest(name = "{0} is {2} record(s) of {1} bytes")
    @MethodSource("fixtureGeometry")
    @DisplayName("every fixture is a whole number of records of its declared width, LF terminated")
    void everyFixtureIsAWholeNumberOfRecordsOfItsDeclaredWidth(String path, int reclen, int records) {
        byte[] content = bytes(path);

        assertThat(content)
                .as("%s: %d records of %d bytes plus one newline each", path, records, reclen)
                .hasSize(records == 0 ? 0 : records * (reclen + 1));
        assertThat(new String(content, StandardCharsets.US_ASCII))
                .as("section 6.1: every record file is derived to LF only, so no CR may appear")
                .doesNotContain("\r");
        List<byte[]> decoded = records(path, reclen);
        assertThat(decoded).as("%s must yield exactly %d records", path, records).hasSize(records);
        assertThat(decoded).allSatisfy(record -> assertThat(record).hasSize(reclen));
    }

    /**
     * Every record file in the packaged tree is enrolled in the geometry list above.
     *
     * <p>Purpose: the geometry list is the only place every fixture receives a width, a record-count and
     * a line-ending assertion, so a file left out of it receives none of them -- and a record file that
     * nothing loads by its own path can be edited into something wrong with the whole suite staying
     * green. That is not hypothetical: three files sat committed and unenrolled while this list's own
     * documentation claimed to name every file in the tree, and each of their scenario READMEs recorded
     * the consequence honestly rather than the list catching it. This case closes the list against that
     * happening again, by comparing it with what is actually on the class path rather than with a
     * remembered count.</p>
     *
     * <p>Alternatives Considered: asserting the list's SIZE against a committed number, which is what the
     * list's documentation previously invited by naming a count. Rejected because a number has to be
     * updated by the same edit that adds a file, so it fails exactly when someone remembers -- and the
     * failure it must catch is someone forgetting. Comparing the two SETS names the missing path instead
     * of reporting an arithmetic mismatch.</p>
     *
     * <p>Assumptions: the tree is walked on the CLASS PATH rather than in {@code src/test/resources}, for
     * the reason this class's own header gives: the packaged copy is the one a consumer sees, and a file
     * present in the source tree but absent from the artifact must fail rather than pass.</p>
     *
     * @throws IOException if the packaged fixture tree cannot be walked
     * @throws AssertionError if the tree root resolves to a location that cannot be walked as a path,
     *     which is a packaging fault rather than a fixture fault and is raised as such
     */
    @Test
    @DisplayName("every record file on the class path is enrolled in the geometry list")
    void everyPackagedRecordFileIsEnrolled() throws IOException {
        URL root = ReferenceFixtureTest.class.getResource(ROOT);
        assertThat(root).as("the packaged fixture tree must be on the test class path").isNotNull();

        Path treeRoot;
        try {
            treeRoot = Path.of(root.toURI());
        } catch (URISyntaxException malformed) {
            throw new AssertionError("the fixture tree resolved to a path that cannot be walked: "
                    + root, malformed);
        }

        List<String> packaged;
        try (Stream<Path> walk = Files.walk(treeRoot)) {
            packaged = walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".txt"))
                    .map(file -> treeRoot.relativize(file).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }

        List<String> enrolled = fixtureGeometry()
                .map(argument -> (String) argument.get()[0])
                .sorted()
                .toList();

        assertThat(enrolled)
                .as("a fixture absent from the geometry list receives no assertion of any kind")
                .containsExactlyElementsOf(packaged);
    }

    /**
     * Confirms a zero-byte fixture is genuinely zero bytes and yields no record at all.
     *
     * <p>Assumptions: charter section 6.5 defines "empty" as a zero-byte file and nothing else, so a
     * file holding one blank record of the declared width would be a different scenario wearing this
     * name. The decode attempt is included because the length failure is the observable behaviour a
     * consumer of an empty file must handle, and asserting the file size alone would leave that
     * untested.</p>
     *
     * @param path the empty fixture path relative to the tree root
     * @param reclen the record length the surrounding domain declares
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("emptyFixtures")
    @DisplayName("an empty fixture is zero bytes and decodes to no record")
    void anEmptyFixtureIsZeroBytesAndDecodesToNoRecord(String path, int reclen) {
        assertThat(bytes(path)).as("section 6.5: empty means zero bytes").isEmpty();
        assertThat(records(path, reclen)).as("no record can be read from nothing").isEmpty();
        assertThatThrownBy(() -> FixedWidthCodec.decodeRecord(new byte[0], layoutFor(reclen)))
                .as("a zero-length image is a LENGTH failure, not an empty field map")
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class);
    }

    /**
     * Confirms the seven seeded transaction types decode through the registered layout and re-encode.
     *
     * <p>Assumptions: the FILLER assertion is the point of this test rather than an aside. Charter
     * section 3.2 records that this record's eight filler bytes are ASCII zeros copied verbatim from
     * the seed, and the codec drops only BLANK registered padding, so a filler of zeros must survive
     * decoding as content. If it were dropped the re-encode would still produce sixty bytes and the
     * loss would be invisible in the length.</p>
     */
    @Test
    @DisplayName("the seven seeded transaction types decode and re-encode byte for byte")
    void theSevenSeededTransactionTypesDecodeAndReEncodeByteForByte() {
        RecordSpec layout = CopybookLayout.layout("TRANTYPE");
        List<byte[]> rows = records("reference_list/happy_path/trantype.txt", layout.reclen());

        assertThat(rows).hasSize(7);
        List<String> codes = new ArrayList<>();
        for (byte[] row : rows) {
            Map<String, Object> fields = FixedWidthCodec.decodeRecord(row, layout);
            codes.add((String) fields.get("TRAN-TYPE"));
            assertThat(fields.get("FILLER"))
                    .as("section 3.2: the eight filler bytes are ASCII zeros and are content, not padding")
                    .isEqualTo("00000000");
            assertThat(FixedWidthCodec.encodeRecord(fields, layout))
                    .as("a decode that loses a byte must not be able to re-encode the record")
                    .isEqualTo(row);
        }

        assertThat(codes).containsExactly("01", "02", "03", "04", "05", "06", "07");
        assertThat(description(rows.get(0), layout, "TRAN-TYPE-DESC")).isEqualTo("Purchase");
        assertThat(description(rows.get(6), layout, "TRAN-TYPE-DESC")).isEqualTo("Adjustment");
    }

    /**
     * Confirms the category fixture carries the rate-less pair and that the rate fixture omits it.
     *
     * <p>Assumptions: this is the one assertion in the file that spans two fixtures, and it has to.
     * Charter section 6.4 records that pair {@code 01|0005} exists as a category and has no rate row
     * in any disclosure group, which is why the category file holds eighteen pairs while the rate file
     * holds seventeen per group. That asymmetry IS the tree's only lookup-miss path, so a future
     * author who "fixed" the rate file by adding the missing row would delete a scenario. Asserting
     * the absence here makes that edit fail a test rather than pass review.</p>
     */
    @Test
    @DisplayName("the category fixture carries pair 01|0005 and no disclosure group prices it")
    void theCategoryFixtureCarriesTheRateLessPairAndNoGroupPricesIt() {
        RecordSpec categories = CopybookLayout.layout("TRANCAT");
        List<Map<String, Object>> categoryRows = decodeAll("reference_update/happy_path/trancatg.txt",
                categories);

        assertThat(categoryRows).hasSize(18);
        assertThat(categoryRows).anySatisfy(row -> {
            assertThat(row.get("TRAN-TYPE-CD")).isEqualTo("01");
            assertThat(row.get("TRAN-CAT-CD")).isEqualTo(5L);
            assertThat(String.valueOf(row.get("TRAN-CAT-TYPE-DESC")).strip())
                    .isEqualTo("Interest Amount");
        });

        RecordSpec rates = CopybookLayout.layout("DISGROUP");
        assertThat(decodeAll("disclosure_group/default_fallback/discgrp.txt", rates))
                .as("section 6.4: no group prices 01|0005, and that absence is the lookup-miss path")
                .noneSatisfy(row -> {
                    assertThat(row.get("DIS-TRAN-TYPE-CD")).isEqualTo("01");
                    assertThat(row.get("DIS-TRAN-CAT-CD")).isEqualTo(5L);
                });
    }

    /**
     * Confirms the fallback fixture differs from the account group at exactly the discriminating pair.
     *
     * <p>Assumptions: charter section 6.3 calls this the single most important fixture-design fact in
     * the tree, and the reason is that on any other pair the two groups return byte-identical rates,
     * so an assertion that the fallback fired would pass whether it fired or not. This test pins the
     * property the discrimination rests on: sixteen pairs agree, one differs, and the one that differs
     * is {@code 07|0001}.</p>
     */
    @Test
    @DisplayName("the two groups differ at exactly one pair, and it is 07|0001")
    void theTwoGroupsDifferAtExactlyOnePairAndItIsTheDocumentedOne() {
        RecordSpec layout = CopybookLayout.layout("DISGROUP");
        List<Map<String, Object>> rows = decodeAll("disclosure_group/default_fallback/discgrp.txt",
                layout);

        assertThat(rows).hasSize(34);
        Map<String, BigDecimal> accountRates = ratesOf(rows, ACCOUNT_GROUP);
        Map<String, BigDecimal> defaultRates = ratesOf(rows, DEFAULT_GROUP);
        assertThat(accountRates).hasSize(17);
        assertThat(defaultRates).as("both groups must price the same seventeen pairs")
                .containsOnlyKeys(accountRates.keySet().toArray(String[]::new));

        List<String> differing = accountRates.keySet().stream()
                .filter(pair -> !accountRates.get(pair).equals(defaultRates.get(pair)))
                .toList();
        assertThat(differing)
                .as("section 6.3: exactly one pair discriminates, or the fallback assertion is vacuous")
                .containsExactly(DISCRIMINATING_PAIR);
        assertThat(accountRates.get(DISCRIMINATING_PAIR)).isEqualByComparingTo("15.00");
        assertThat(defaultRates.get(DISCRIMINATING_PAIR))
                .as("this fixture is copied from the ASCII twin, where the fallback row reads zero;"
                        + " the EBCDIC extract the reference load job REPROs reads 15.00 there, which"
                        + " is why a fixture built from THAT form could not discriminate at all")
                .isEqualByComparingTo("0.00");
    }

    /**
     * Confirms the single-row rate fixture is the direct-hit case at an exact fixed-point rate.
     *
     * <p>Assumptions: the rate is compared as a scaled decimal rather than as text, because charter
     * section 6.3 records the consuming formula as a multiply-then-divide over this value with no
     * floating-point tolerance. A rate that decoded to the right digits at the wrong scale would
     * satisfy a text comparison and change every derived amount.</p>
     */
    @Test
    @DisplayName("the happy-path rate fixture is one direct-hit row at 15.00")
    void theHappyPathRateFixtureIsOneDirectHitRow() {
        RecordSpec layout = CopybookLayout.layout("DISGROUP");
        List<Map<String, Object>> rows = decodeAll("disclosure_group/happy_path/discgrp.txt", layout);

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("DIS-ACCT-GROUP-ID")).isEqualTo(ACCOUNT_GROUP);
        assertThat(row.get("DIS-TRAN-TYPE-CD")).isEqualTo("01");
        assertThat(row.get("DIS-TRAN-CAT-CD")).isEqualTo(1L);
        assertThat((BigDecimal) row.get("DIS-INT-RATE")).isEqualByComparingTo("15.00");
        assertThat(((BigDecimal) row.get("DIS-INT-RATE")).scale())
                .as("the rate is S9(04)V99, so two decimal places are part of the contract")
                .isEqualTo(2);
    }

    /**
     * Confirms the delete fixture pairs a type categories reference with one no category references.
     *
     * <p>Assumptions: the restrict path and the permitted path are asserted TOGETHER, against the
     * category fixture rather than against a literal list. A fixture holding only the restricted type
     * would exercise the 409 and leave the successful delete untested, and a fixture holding only the
     * free type would do the reverse; the pair is what makes one file cover both edges of the
     * restricting foreign key.</p>
     */
    @Test
    @DisplayName("the delete fixture pairs a referenced type with an unreferenced one")
    void theDeleteFixturePairsAReferencedTypeWithAnUnreferencedOne() {
        RecordSpec types = CopybookLayout.layout("TRANTYPE");
        List<Map<String, Object>> rows =
                decodeAll("reference_update/delete_restricted_by_category/trantype.txt", types);
        assertThat(rows).hasSize(2);

        // WHY : Refactoring Rationale: the referencing set is now built from the SCENARIO'S OWN category
        //       fixture, where an earlier revision built it from the happy path's. The scenario ships a
        //       two-record trancatg.txt beside its two-record trantype.txt, so the pair a loader for this
        //       scenario would load is now the pair this assertion reads; borrowing another scenario's
        //       categories described a combination that is never loaded together.
        Set<String> referenced = new LinkedHashSet<>();
        decodeAll("reference_update/delete_restricted_by_category/trancatg.txt",
                CopybookLayout.layout("TRANCAT"))
                .forEach(category -> referenced.add((String) category.get("TRAN-TYPE-CD")));

        assertThat(rows.get(0).get("TRAN-TYPE"))
                .as("the first row must be a type the categories reference, so its delete is refused")
                .isEqualTo("06");
        assertThat(referenced).contains("06");
        assertThat(rows.get(1).get("TRAN-TYPE"))
                .as("the second row must be referenced by nothing, so its delete is permitted")
                .isEqualTo("99");
        assertThat(referenced).doesNotContain("99");

        // WHY : Assumptions: the set is asserted to hold EXACTLY the restricted code. A category file
        //       referring to a third type would satisfy every assertion above and would additionally make
        //       that third type's delete refusable, so the fixture would stop isolating one refused delete
        //       from one permitted one.
        assertThat(referenced).containsExactly("06");
    }

    /**
     * Confirms every category's type code exists as a type in the same domain's type fixture.
     *
     * <p>Assumptions: the target expresses this relationship as a restricting foreign key, so a
     * category naming a type that does not exist could not be loaded at all. Asserting it here catches
     * a fixture edit that would otherwise surface much later as a constraint violation inside a
     * container-backed test, where the cause is far less legible.</p>
     */
    @Test
    @DisplayName("every category references a type the type fixture declares")
    void everyCategoryReferencesATypeTheTypeFixtureDeclares() {
        Set<String> declared = new LinkedHashSet<>();
        decodeAll("reference_update/happy_path/trantype.txt", CopybookLayout.layout("TRANTYPE"))
                .forEach(type -> declared.add((String) type.get("TRAN-TYPE")));
        assertThat(declared).containsExactly("01", "02", "03", "04", "05", "06", "07", "08");

        List<String> orphans = decodeAll("reference_update/happy_path/trancatg.txt",
                CopybookLayout.layout("TRANCAT")).stream()
                .map(row -> (String) row.get("TRAN-TYPE-CD"))
                .filter(code -> !declared.contains(code))
                .distinct()
                .toList();
        assertThat(orphans).as("a category whose type is absent violates the restricting foreign key")
                .isEmpty();

        // WHY : Assumptions: the LIST scenario is checked for the same closure, in the same case, rather
        //       than in a second one. Its category fixture was enrolled in no inventory and read by
        //       nothing when the review found it, and the property that matters about it is identical to
        //       the property asserted above -- so a second test would restate this argument rather than
        //       add one. The declared set differs: the list scenario seeds SEVEN types where the update
        //       scenario seeds eight, and asserting both sets by value is what keeps the two scenarios
        //       distinguishable.
        Set<String> listDeclared = new LinkedHashSet<>();
        decodeAll("reference_list/happy_path/trantype.txt", CopybookLayout.layout("TRANTYPE"))
                .forEach(type -> listDeclared.add((String) type.get("TRAN-TYPE")));
        assertThat(listDeclared).containsExactly("01", "02", "03", "04", "05", "06", "07");

        List<String> listOrphans = decodeAll("reference_list/happy_path/trancatg.txt",
                CopybookLayout.layout("TRANCAT")).stream()
                .map(row -> (String) row.get("TRAN-TYPE-CD"))
                .filter(code -> !listDeclared.contains(code))
                .distinct()
                .toList();
        assertThat(listOrphans)
                .as("the list scenario's categories must all name a type the list scenario seeds")
                .isEmpty();
    }

    /**
     * Confirms no fixture carries the same key twice.
     *
     * <p>Assumptions: uniqueness is asserted where ORDER deliberately is not. Charter section 8.5
     * records that the house pre-sort step does not transfer to this tree, because no loader here
     * requires key order, so an ordering assertion would fail a legitimate future fixture. Duplicate
     * keys are a different matter: every one of these datasets is keyed, so a repeated key is a
     * defect in any order.</p>
     *
     * @param path the fixture path relative to the tree root
     * @param layoutName the registered layout whose key interval identifies a row
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("keyedFixtures")
    @DisplayName("no fixture carries a duplicate key")
    void noFixtureCarriesADuplicateKey(String path, String layoutName) {
        RecordSpec layout = CopybookLayout.layout(layoutName);
        List<String> keys = records(path, layout.reclen()).stream()
                .map(record -> new String(Arrays.copyOfRange(record, layout.keyOffset(),
                        layout.keyOffset() + layout.keyLength()), StandardCharsets.US_ASCII))
                .toList();

        assertThat(keys).as("%s: each of these datasets is keyed, so a repeated key is a defect", path)
                .doesNotHaveDuplicates();
    }

    /**
     * Confirms the batch input rows carry their dispatch byte in position zero.
     *
     * <p>Assumptions: the two fixtures are read together because the pair is what distinguishes the
     * rejection from the add. The charter tables the dispatch at {@code COBTUPDT.cbl} lines 109 to
     * 129: {@code 'A'}, {@code 'U'} and {@code 'D'} act, {@code '*'} is a program-recognised comment
     * row, and ANYTHING ELSE falls to {@code WHEN OTHER} and is rejected. The lower-case {@code 'a'}
     * in the reject fixture is therefore not a typographical slip in the data and must not be
     * "corrected"; it is the scenario. Asserting the byte's case here is what stops that correction
     * passing silently.</p>
     *
     * <p>Assumptions: the invalid row is FIRST and a valid update follows it, and the order is the
     * point. The read at {@code COBTUPDT.cbl} line 101 is sequential and the reject paragraph it
     * reaches at lines 230 to 233 displays, moves 4 to the return code and exits WITHOUT stopping the
     * run, so the update behind the rejected row is applied. An implementation that halted instead
     * would leave that row unapplied -- an observable difference a fixture with the invalid row last
     * could not tell apart.</p>
     */
    @Test
    @DisplayName("the batch input rows carry their dispatch byte in position zero")
    void theBatchInputRowsCarryTheirDispatchByteInPositionZero() {
        RecordSpec layout = batchInputLayout();
        List<Map<String, Object>> added =
                decodeAll("batch_reference_update/add_record/trtype-update.txt", layout);
        assertThat(added).hasSize(2);
        assertThat(added).allSatisfy(row -> assertThat(row.get("INPUT-REC-TYPE")).isEqualTo("A"));
        assertThat(added.get(0).get("INPUT-REC-NUMBER")).isEqualTo("08");
        assertThat(String.valueOf(added.get(0).get("INPUT-REC-DESC")).strip())
                .isEqualTo("Fee Assessment");
        assertThat(added.get(1).get("INPUT-REC-NUMBER")).isEqualTo("09");

        List<Map<String, Object>> softRejected =
                decodeAll("batch_reference_update/invalid_type_abend/trtype-update.txt", layout);
        assertThat(softRejected).hasSize(2);
        assertThat(softRejected.get(0).get("INPUT-REC-TYPE"))
                .as("a byte outside A, U, D and * is REJECTED, and lower case is outside it")
                .isEqualTo("a");
        assertThat(softRejected.get(1).get("INPUT-REC-TYPE"))
                .as("a VALID row sits BEHIND the rejected one, and it is the row the run goes on to apply")
                .isEqualTo("U");
    }

    /**
     * Confirms the date-conversion request carries a function code, an eleven-digit key and spaces.
     *
     * <p>Assumptions: the filler is asserted to be SPACES rather than zeros, and that is the one field
     * in this tree where the two regimes diverge. Charter section 5.3 records that the three
     * seed-derived datasets carry zero-filled padding copied from the seed while this record's filler
     * is space-filled because {@code CODATE01.cbl} line 112 declares {@code VALUE SPACES}. A fixture
     * that zero-filled it would still be exactly a thousand bytes.</p>
     */
    @Test
    @DisplayName("the date request carries a function code, an eleven-digit key and space filler")
    void theDateRequestCarriesAFunctionCodeAnElevenDigitKeyAndSpaceFiller() {
        RecordSpec layout = dateRequestLayout();

        Map<String, Object> happy =
                decodeAll("date_conversion/happy_path/date-request.txt", layout).get(0);
        assertThat(happy.get("WS-FUNC")).isEqualTo("DATE");
        assertThat(happy.get("WS-KEY")).isEqualTo(1L);
        assertThat(happy.get("WS-FILLER"))
                .as("section 5.3: this record's filler is space-filled per VALUE SPACES, not zeroed")
                .isEqualTo(" ".repeat(985));

        Map<String, Object> ignoredPayload =
                decodeAll("date_conversion/request_payload_ignored/date-request.txt", layout).get(0);
        assertThat(ignoredPayload.get("WS-FUNC"))
                .as("this request differs from the accepted one in its FUNCTION code, and is answered"
                        + " identically because CODATE01 reads no field of the request")
                .isEqualTo("DTE ");
        assertThat(ignoredPayload.get("WS-KEY")).isEqualTo(2L);

        // WHY : Refactoring Rationale: the third scenario is decoded here because its record file was
        //       authored during this remediation, having previously had a README documenting bytes
        //       that did not exist. Decoding it through the same registered layout is what turns that
        //       README's field table into an asserted fact; a fixture no test decodes states an
        //       intention only.
        // WHY : Assumptions: its key decodes to the same ordinal as the scenario above, and the
        //       equality is asserted rather than worked around. Neither scenario is distinguished by
        //       its envelope -- the date refusal one exercises is carried by a ten-character parameter
        //       to DateEditValidator, not by any field of these bytes -- so a differing key here would
        //       imply an envelope-level distinction CODATE01 cannot act on.
        Map<String, Object> refusedDate =
                decodeAll("date_conversion/invalid_date_rejected/date-request.txt", layout).get(0);
        assertThat(refusedDate.get("WS-FUNC")).isEqualTo("DTE ");
        assertThat(refusedDate.get("WS-KEY")).isEqualTo(2L);
        assertThat(refusedDate.get("WS-FILLER"))
                .as("section 5.3: this record's filler is space-filled per VALUE SPACES, not zeroed")
                .isEqualTo(" ".repeat(985));
    }

    /**
     * Supplies the path, record length and record count of every fixture in the tree.
     *
     * <p>Assumptions: this list is the tree's geometry INVENTORY and is complete, so a record file that
     * exists on disk and is absent from it is a gap rather than an omission of no consequence -- the
     * width, record-count and line-ending assertions this method feeds are the only ones every file in
     * the tree receives, and a file left out of the list receives none of them. Its size is the count
     * the tree charter publishes, so the two are checkable against each other and against
     * {@code find . -name '*.txt' | wc -l}.</p>
     *
     * @return one argument triple per fixture file
     */
    private static Stream<Arguments> fixtureGeometry() {
        return Stream.of(
                Arguments.of("batch_reference_update/add_record/trtype-update.txt", 53, 2),
                Arguments.of("batch_reference_update/commented_line/trtype-update.txt", 53, 2),
                Arguments.of("batch_reference_update/delete_record/trtype-update.txt", 53, 2),
                Arguments.of("batch_reference_update/empty_input/trtype-update.txt", 53, 0),
                Arguments.of("batch_reference_update/invalid_type_abend/trtype-update.txt", 53, 2),
                Arguments.of("batch_reference_update/update_record/trtype-update.txt", 53, 1),
                Arguments.of("date_conversion/empty_input/date-request.txt", 1000, 0),
                Arguments.of("date_conversion/happy_path/date-request.txt", 1000, 1),
                Arguments.of("date_conversion/invalid_date_rejected/date-request.txt", 1000, 1),
                Arguments.of("date_conversion/request_payload_ignored/date-request.txt", 1000, 1),
                Arguments.of("disclosure_group/default_fallback/discgrp.txt", 50, 34),
                Arguments.of("disclosure_group/empty_input/discgrp.txt", 50, 0),
                Arguments.of("disclosure_group/happy_path/discgrp.txt", 50, 1),
                Arguments.of("reference_list/empty_input/trancatg.txt", 60, 0),
                Arguments.of("reference_list/empty_input/trantype.txt", 60, 0),
                Arguments.of("reference_list/happy_path/trancatg.txt", 60, 18),
                Arguments.of("reference_list/happy_path/trantype.txt", 60, 7),
                Arguments.of("reference_update/delete_restricted_by_category/trancatg.txt", 60, 2),
                Arguments.of("reference_update/delete_restricted_by_category/trantype.txt", 60, 2),
                Arguments.of("reference_update/happy_path/trancatg.txt", 60, 18),
                Arguments.of("reference_update/happy_path/trantype.txt", 60, 8));
    }

    /**
     * Supplies the five zero-byte fixtures and the record length their domain declares.
     *
     * @return one argument pair per empty fixture
     */
    private static Stream<Arguments> emptyFixtures() {
        return Stream.of(
                Arguments.of("batch_reference_update/empty_input/trtype-update.txt", 53),
                Arguments.of("date_conversion/empty_input/date-request.txt", 1000),
                Arguments.of("disclosure_group/empty_input/discgrp.txt", 50),
                Arguments.of("reference_list/empty_input/trancatg.txt", 60),
                Arguments.of("reference_list/empty_input/trantype.txt", 60));
    }

    /**
     * Supplies the non-empty fixtures that a registered layout gives a key interval for.
     *
     * @return one argument pair per keyed fixture: its path and its registered layout name
     */
    private static Stream<Arguments> keyedFixtures() {
        return Stream.of(
                Arguments.of("disclosure_group/default_fallback/discgrp.txt", "DISGROUP"),
                Arguments.of("disclosure_group/happy_path/discgrp.txt", "DISGROUP"),
                Arguments.of("reference_list/happy_path/trancatg.txt", "TRANCAT"),
                Arguments.of("reference_list/happy_path/trantype.txt", "TRANTYPE"),
                Arguments.of("reference_list/happy_path/trancatg.txt", "TRANCAT"),
                Arguments.of("reference_update/delete_restricted_by_category/trantype.txt", "TRANTYPE"),
                Arguments.of("reference_update/delete_restricted_by_category/trancatg.txt", "TRANCAT"),
                Arguments.of("reference_update/happy_path/trantype.txt", "TRANTYPE"),
                Arguments.of("reference_update/happy_path/trancatg.txt", "TRANCAT"));
    }

    /**
     * Builds the descriptor for the batch reference-update input record.
     *
     * <p>Assumptions: this record has no copybook and therefore no registered layout, for the reason
     * the package charter states. Its three fields and their offsets come from charter section 3.6,
     * which cites {@code COBTUPDT.cbl} lines 72, 74 and 76.</p>
     *
     * @return a validated fifty-three-byte descriptor, never {@code null}
     */
    private static RecordSpec batchInputLayout() {
        return new RecordSpec("WS-INPUT-REC", 53, 3, 0, List.of(
                CopybookLayout.text("INPUT-REC-TYPE", 0, 1),
                CopybookLayout.text("INPUT-REC-NUMBER", 1, 2),
                CopybookLayout.text("INPUT-REC-DESC", 3, 50))).validateGeometry();
    }

    /**
     * Builds the descriptor for the date-conversion request record.
     *
     * <p>Assumptions: as with the batch input record there is no copybook to register. The three
     * fields come from charter section 3.7, which cites {@code CODATE01.cbl} lines 110 to 112, and the
     * key length of eleven at offset four is that record's only identifier.</p>
     *
     * @return a validated one-thousand-byte descriptor, never {@code null}
     */
    private static RecordSpec dateRequestLayout() {
        return new RecordSpec("REQUEST-MSG-COPY", 1000, 11, 4, List.of(
                CopybookLayout.text("WS-FUNC", 0, 4),
                CopybookLayout.uint("WS-KEY", 4, 11),
                CopybookLayout.text("WS-FILLER", 15, 985))).validateGeometry();
    }

    /**
     * Chooses the layout that matches a record length, for the empty-file length assertion.
     *
     * @param reclen the record length to resolve
     * @return a descriptor of exactly that record length, never {@code null}
     */
    private static RecordSpec layoutFor(int reclen) {
        return switch (reclen) {
            case 50 -> CopybookLayout.layout("DISGROUP");
            case 53 -> batchInputLayout();
            case 60 -> CopybookLayout.layout("TRANTYPE");
            default -> dateRequestLayout();
        };
    }

    /**
     * Decodes every record of one fixture under one layout.
     *
     * @param path the fixture path relative to the tree root
     * @param layout the descriptor to decode under
     * @return the decoded field maps in file order, never {@code null}
     */
    private static List<Map<String, Object>> decodeAll(String path, RecordSpec layout) {
        return records(path, layout.reclen()).stream()
                .map(record -> FixedWidthCodec.decodeRecord(record, layout))
                .toList();
    }

    /**
     * Collects one group's rates from a decoded rate fixture, keyed by type and category.
     *
     * @param rows the decoded rate rows
     * @param groupId the group id to select, padded to its declared width
     * @return the group's rates keyed by {@code type|category}, never {@code null}
     */
    private static Map<String, BigDecimal> ratesOf(List<Map<String, Object>> rows, String groupId) {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (groupId.equals(row.get("DIS-ACCT-GROUP-ID"))) {
                rates.put(row.get("DIS-TRAN-TYPE-CD") + "|" + row.get("DIS-TRAN-CAT-CD"),
                        (BigDecimal) row.get("DIS-INT-RATE"));
            }
        }
        return rates;
    }

    /**
     * Reads one text field of a record and strips its declared padding.
     *
     * @param record the physical record image
     * @param layout the descriptor the record belongs to
     * @param field the field name to read
     * @return the field's value with trailing padding removed, never {@code null}
     */
    private static String description(byte[] record, RecordSpec layout, String field) {
        return String.valueOf(FixedWidthCodec.decodeField(record, layout.field(field))).strip();
    }

    /**
     * Splits one fixture into its physical records on the newline the tree derives to.
     *
     * <p>Assumptions: the split is on LF and the trailing empty element is discarded, which is exactly
     * what charter sections 5.6 and 6.1 describe -- one trailing newline, no CR anywhere. A record
     * whose own bytes could contain a newline would make this unsafe, and none can here: every field
     * in all five layouts is a display picture, so this tree carries no binary field at all.</p>
     *
     * @param path the fixture path relative to the tree root
     * @param reclen the record length to check each line against
     * @return the physical records in file order, never {@code null}
     */
    private static List<byte[]> records(String path, int reclen) {
        String content = new String(bytes(path), StandardCharsets.US_ASCII);
        List<byte[]> out = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            if (!line.isEmpty()) {
                out.add(line.getBytes(StandardCharsets.US_ASCII));
            }
        }
        assertThat(out).allSatisfy(record -> assertThat(record.length)
                .as("%s: every record must be exactly %d bytes", path, reclen).isEqualTo(reclen));
        return out;
    }

    /**
     * Reads one fixture from the test class path.
     *
     * @param path the fixture path relative to the tree root
     * @return the file's bytes, never {@code null}
     * @throws IllegalStateException if the fixture is absent from the test class path or unreadable,
     *     either of which would mean this assertion was testing nothing
     */
    private static byte[] bytes(String path) {
        try (InputStream fixture = ReferenceFixtureTest.class.getResourceAsStream(ROOT + path)) {
            if (fixture == null) {
                throw new IllegalStateException(ROOT + path + " is not on the test class path");
            }
            return fixture.readAllBytes();
        } catch (IOException problem) {
            throw new IllegalStateException(ROOT + path + " could not be read", problem);
        }
    }
}
