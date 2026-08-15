package com.carddemo.reporting.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.money.Money;
import com.carddemo.reporting.mapper.StatementTextMapper;
import com.carddemo.reporting.mapper.TransactionReportMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Consumes every fixed-width fixture of this service and asserts the contract each one claims.
 *
 * <h2>Purpose</h2>
 *
 * <p>Fixture files sat under {@code src/test/resources/fixtures} with no consumer. Their
 * record lengths, field offsets, key domains and synthetic origin were therefore unverified claims,
 * and a fixture that no test reads cannot fail when it drifts. This class reads every one of them:
 * it pins
 * the directory to a closed set of names, decodes every row through the shared codec against the
 * registered descriptor, asserts the field values the reporting mappers actually consume, re-encodes
 * every row to prove byte identity, drives both mappers with fixture rows so the data reaches the
 * code it was authored for, and asserts the structural markers that make the synthetic-data
 * attestation in {@code fixtures/README.md} checkable rather than merely stated.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a
 * caller invokes, no value it yields and no exception it raises to anything outside the test engine,
 * so the type itself accepts no parameter, returns nothing and throws nothing. The inapplicability
 * is stated rather than passed over, because user-specified Rule 1 (Explainability) forbids a
 * docstring that omits parameters, return values or purpose, and a reader has to be able to tell a
 * declared inapplicability from an oversight. Every member below carries its own parameter, return
 * and exception at-clauses.</p>
 *
 * <h2>Assumptions: the newline is a file convention and never part of a record</h2>
 *
 * <p>Each fixture is line-oriented, one fixed-width record per line, with a terminating newline on
 * the last line -- the same shape the reference extracts under {@code app/data/ASCII/} use. The
 * declared record length excludes that byte, so every row is stripped of its line terminator before
 * decoding. Alternatives Considered: reading each file as one continuous byte stream and slicing it
 * at the declared length, which is what a production loader over an unterminated dataset would do.
 * Rejected because it would mis-align every record after the first by a growing number of bytes and
 * would report the misalignment as a field-content failure somewhere in the middle of the file.</p>
 *
 * <h2>Assumptions: the synthetic-data markers are asserted, not assumed</h2>
 *
 * <p>One of the fixtures is shaped exactly like a customer master and carries names, street
 * addresses, telephone numbers, a national identifier, a government-issued identifier and a date of
 * birth in every row. Its README attests that all of it is fabricated. Three structural markers make
 * that attestation checkable -- an unissued national-identifier area range, a reserved fictional
 * telephone range and a literal government-identifier prefix -- and each is asserted below, so a row
 * added later from a real source fails this suite rather than passing quietly.</p>
 */
@DisplayName("Reporting fixtures: inventory, decoding, mapper consumption and provenance markers")
class ReportingFixtureContractTest {

    /**
     * Classpath directory holding every fixture this service ships.
     */
    private static final String FIXTURE_DIRECTORY = "fixtures";

    /**
     * The complete set of resources this directory may contain, in alphabetical order.
     *
     * <p>Assumptions: the set is closed rather than a lower bound, and the README is a member of it.
     * A directory assertion that only checked for the presence of expected files would pass after an
     * unrelated fixture was dropped in, and one that omitted the README would pass after the
     * provenance statement was deleted. Both are things this test exists to prevent.</p>
     *
     * <p>Assumptions: closing the set means each planned fixture must be admitted here by name as it
     * lands, so this list grows by deliberate edit rather than on its own. {@code tcatbal.txt} is
     * such an admission: it is the transaction-category-balance record of
     * {@code app/cpy/CVTRA01Y.cpy}, which {@code app/jcl/PRTCATBL.jcl} reads at its declared
     * 50-byte length, and it is a planned member of this directory rather than a stray file.</p>
     *
     * <p>Alternatives Considered: relaxing the assertion to a lower bound so that any newly added
     * fixture passes without an edit here. Rejected because it would surrender the exact property
     * the paragraph above is built on: an unrelated or accidentally committed file, or a fixture
     * written against no descriptor at all, would then enter this directory silently. Naming each
     * arrival costs one line and keeps the directory's contents an owned decision.</p>
     *
     * <p>Assumptions: {@code carddata.txt} is the second such admission. It is the card master of
     * {@code app/cpy/CVACT02Y.cpy}, whose {@code 01 CARD-RECORD} at line 4 declares six named fields
     * summing to 91 bytes and a trailing {@code FILLER PIC X(59)} at line 11, giving the 150-byte
     * length its header comment at line 2 states and {@link CopybookLayout} registers as
     * {@code CARD}. Its {@code CARD-CVV-CD} at line 7 occupies zero-based offset 27 for three bytes
     * and is registered SENSITIVE, so the fixture carries the clear synthetic digits the copybook
     * declares and nothing masks them here; masking and suppression are mapper decisions, and
     * applying either in a fixture would break the byte-identical round trip asserted below.
     * {@code CARD-EXPIRAION-DATE} at line 9 carries the second of the three baseline misspellings and
     * is written at its declared offset under its declared name, because the correction to
     * {@code cards.expiration_date} belongs to the entity and the mapper rather than to the data.</p>
     *
     * <p>Alternatives Considered: leaving this record out of the directory altogether, on the ground
     * that NO reporting program reads the card master. That ground is real and is worth stating
     * plainly so no reader infers a dependency that does not exist: {@code app/jcl/TRANREPT.jcl}
     * declares its inputs at lines 65 to 74 as {@code TRANFILE}, {@code CARDXREF}, {@code TRANTYPE},
     * {@code TRANCATG} and {@code DATEPARM} with no {@code CARDFILE} among them, matching the six
     * {@code SELECT} clauses of {@code app/cbl/CBTRN03C.cbl} at lines 28 to 57; and the
     * {@code EVALUATE LK-M03B-DD} at line 118 of {@code app/cbl/CBSTM03B.CBL} offers only
     * {@code 'TRNXFILE'}, {@code 'XREFFILE'}, {@code 'CUSTFILE'} and {@code 'ACCTFILE'}, so
     * {@code app/cbl/CBSTM03A.CBL} cannot reach the card master either. Admitting it anyway was
     * chosen because {@code ReportingDtoMapperTest} already names {@code carddata.txt} among the ten
     * records this context reads and binds it to the {@code CARD} descriptor, and a named binding
     * with no record behind it is a claim rather than a check. The file makes that binding a
     * decodable exemplar that the three structural cases below actually exercise.</p>
     *
     * <p>Alternatively the record could have carried all eighty-eight card numbers the sibling
     * cross-reference and statement extracts mention. Rejected because nothing in this module joins
     * against them, so eighty-four further rows would add no coverage and eighty-four chances to
     * drift out of agreement with the account fixture. Five rows are carried instead, and they are
     * chosen to make three properties checkable: two of them share account {@code 00000000050} so
     * that the card-keyed control break of {@code app/cbl/CBTRN03C.cbl} line 181 -- whose band line
     * 183 labels "Account Total" while keying on {@code WS-CURR-CARD-NUM} at line 137 -- is
     * distinguishable from an implementation grouped by account; exactly one carries {@code 'N'} in
     * {@code CARD-ACTIVE-STATUS}, without which column 91 would be indistinguishable from padding,
     * since all fifty rows of the reference extract are {@code 'Y'}; and every
     * {@code CARD-ACCT-ID} resolves in {@code acctfile.txt} with every {@code CARD-EMBOSSED-NAME}
     * agreeing with the matching customer in {@code custfile.txt}.</p>
     */
    private static final List<String> EXPECTED_RESOURCES =
            List.of("README.md", "acctfile.txt", "carddata.txt", "custfile.txt", "tcatbal.txt",
                    "trancatg.txt", "trantype.txt");

    /**
     * Resolves the fixture directory on the test classpath.
     *
     * @return the directory holding the fixtures, never {@code null}
     * @throws IllegalStateException if the directory is absent from the classpath, which means the
     *     resource copy step did not run rather than that a fixture is missing
     */
    private static Path fixtureDirectory() {
        URL located = ReportingFixtureContractTest.class.getClassLoader()
                .getResource(FIXTURE_DIRECTORY);
        if (located == null) {
            throw new IllegalStateException(
                    "fixture directory " + FIXTURE_DIRECTORY + " is not on the test classpath");
        }
        try {
            return Path.of(located.toURI());
        } catch (URISyntaxException cause) {
            throw new IllegalStateException("fixture directory URL is not a usable path", cause);
        }
    }

    /**
     * Reads one fixture as a list of records, with every line terminator removed.
     *
     * @param fileName the fixture file name, relative to the fixture directory
     * @return the records in file order, none of them carrying a line terminator
     * @throws UncheckedIOException if the fixture cannot be read, which is a broken checkout rather
     *     than a contract failure and is therefore not translated into an assertion
     */
    private static List<String> records(String fileName) {
        try {
            return Files.readAllLines(fixtureDirectory().resolve(fileName), StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot read fixture " + fileName, cause);
        }
    }

    /**
     * Decodes one record of a fixture against its registered descriptor.
     *
     * @param fileName the fixture file name
     * @param descriptorName the registered layout name the fixture is written against
     * @param rowIndex the zero-based row to decode
     * @return the decoded field map, keyed by copybook field name
     */
    private static Map<String, Object> decode(String fileName, String descriptorName,
            int rowIndex) {
        return FixedWidthCodec.decodeRecord(
                records(fileName).get(rowIndex).getBytes(StandardCharsets.UTF_8),
                CopybookLayout.layout(descriptorName));
    }

    /**
     * Supplies each fixture paired with the descriptor, record length and row count it claims.
     *
     * @return a stream of file name, descriptor name, declared record length and expected row count
     */
    private static Stream<Arguments> everyFixture() {
        return Stream.of(
                Arguments.of("acctfile.txt", "ACCOUNT", 300, 4),
                Arguments.of("custfile.txt", "CUSTOMER", 500, 4),
                Arguments.of("trantype.txt", "TRANTYPE", 60, 7),
                Arguments.of("trancatg.txt", "TRANCAT", 60, 9),

                // WHY : Assumptions: the card fixture is registered here rather than left merely
                //       present in the directory listing, because being listed proves only that a
                //       file exists. Its five rows against the 150-byte CARD descriptor are what
                //       subject it to the same three structural cases as its siblings: the declared
                //       length, the byte-for-byte round trip and the registered-descriptor check.
                //       Trade-offs: the row count is stated here as well as being readable from the
                //       file, so a row silently added or dropped fails this argument source rather
                //       than passing a suite that only ever asserted "every row is 150 bytes".
                Arguments.of("carddata.txt", "CARD", 150, 5),

                // WHY : Assumptions: the balance fixture is registered last because it is the child
                //       of the two reference fixtures above -- every one of its rows draws a type
                //       code from trantype.txt and a type-and-category pair from trancatg.txt, which
                //       is the shape the target preserves as a restricting foreign key. Declaring it
                //       here subjects it to the same three structural assertions as its siblings
                //       rather than leaving it merely present in the directory listing.
                //       Trade-offs: the descriptor name TCATBAL differs from the file name
                //       tcatbal.txt only in case, and the two are NOT interchangeable. The file is
                //       named for the seed dataset because PRTCATBL.jcl STEP10R runs DFSORT with no
                //       COBOL program and so has only generic SORTIN and SORTOUT names to offer,
                //       while TCATBAL is the registry key. Passing either one in the other position
                //       fails, which is the intended outcome.
                Arguments.of("tcatbal.txt", "TCATBAL", 50, 8));
    }

    /**
     * Asserts that the fixture directory holds exactly the closed set of resources named here.
     *
     * @throws IOException if the directory cannot be listed
     */
    @Test
    @DisplayName("the fixture directory holds exactly the closed set of resources named here")
    void theFixtureDirectoryHoldsExactlyTheExpectedResources() throws IOException {
        try (Stream<Path> entries = Files.list(fixtureDirectory())) {
            assertThat(entries.map(path -> path.getFileName().toString()).sorted().toList())
                    .containsExactlyElementsOf(EXPECTED_RESOURCES);
        }
    }

    /**
     * Asserts that every row of every fixture is exactly the record length its descriptor declares.
     *
     * @param fileName the fixture under test
     * @param descriptorName the registered layout the fixture is written against
     * @param declaredLength the record length the descriptor declares
     * @param expectedRows the number of records the fixture is expected to hold
     */
    @ParameterizedTest(name = "{0} holds {3} rows of exactly {2} bytes")
    @MethodSource("everyFixture")
    @DisplayName("every row of every fixture is exactly the record length its descriptor declares")
    void everyRowIsExactlyItsDeclaredRecordLength(String fileName, String descriptorName,
            int declaredLength, int expectedRows) {
        List<String> rows = records(fileName);

        // WHY : Assumptions: the declared length is read from the registered descriptor AND stated
        //       again as a parameter, so the two must agree. Asserting only that each row matches the
        //       descriptor would pass for a descriptor whose reclen had itself been changed, which is
        //       the failure a fixture directory cannot detect on its own.
        assertThat(CopybookLayout.layout(descriptorName).reclen()).isEqualTo(declaredLength);
        assertThat(rows).hasSize(expectedRows);
        assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(declaredLength));
    }

    /**
     * Asserts that every row of every fixture decodes and re-encodes to the identical bytes.
     *
     * @param fileName the fixture under test
     * @param descriptorName the registered layout the fixture is written against
     * @param declaredLength the record length the descriptor declares, unused by this assertion and
     *     accepted only because the argument source is shared
     * @param expectedRows the expected row count, unused by this assertion and accepted only because
     *     the argument source is shared
     */
    @ParameterizedTest(name = "{0} round-trips byte for byte")
    @MethodSource("everyFixture")
    @DisplayName("every row of every fixture decodes and re-encodes to the identical bytes")
    void everyRowRoundTripsByteForByte(String fileName, String descriptorName, int declaredLength,
            int expectedRows) {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(descriptorName);

        for (String row : records(fileName)) {
            byte[] original = row.getBytes(StandardCharsets.UTF_8);
            Map<String, Object> fields = FixedWidthCodec.decodeRecord(original, spec);

            // WHY : Assumptions: the sign-preserving encoder is used rather than the plain one. The
            //       plain encoder normalises a negative zero overpunch, which is a deliberate
            //       divergence registered as D-SIGNED-ZERO-ZONED, so a fixture holding one would
            //       fail a byte comparison for a reason that is not a fixture fault.
            assertThat(FixedWidthCodec.encodeRecordPreservingSign(fields, spec, original))
                    .isEqualTo(original);
        }
    }

    /**
     * Supplies each fixture paired with the trailing-pad character its reference extract uses.
     *
     * <p>Assumptions: the pad character is a per-record-type fact taken from the shipped extracts
     * under {@code app/data/ASCII/}, and it is NOT one convention for the whole directory. Measured
     * across those extracts, {@code acctdata.txt} and {@code custdata.txt} pad with BLANKS while
     * {@code tcatbal.txt}, {@code trantype.txt} and {@code trancatg.txt} pad with ASCII ZEROES, and
     * the sibling fixture trees agree -- {@code transaction-service}'s balance fixtures and
     * {@code reference-service}'s type and category fixtures all pad with zeroes.</p>
     *
     * <p>Refactoring Rationale: three of this directory's fixtures padded with blanks where
     * their extracts pad with zeroes, so the same record type was written two ways two directories
     * apart -- {@code reference-service}'s {@code trantype.txt} and this one differed in their last
     * eight bytes while claiming the same descriptor. The three are corrected and the convention is
     * asserted here so it cannot drift back silently, because a wrong pad byte changes nothing a
     * decode can detect: {@code FILLER} is a character field, so blanks and zeroes both decode, both
     * re-encode and both round-trip byte for byte.</p>
     *
     * <p>Alternatives Considered: padding every fixture in this directory with zeroes, which is the
     * simpler rule and is what a one-line reading of the defect suggests. Rejected on measurement:
     * it would have changed {@code acctfile.txt} and {@code custfile.txt} away from what their own
     * extracts do, replacing three divergences with two new ones. The pad belongs to the record
     * type, not to the directory.</p>
     *
     * @return a stream of fixture name, descriptor name and the single character its pad is made of
     */
    private static Stream<Arguments> everyFixturePad() {
        return Stream.of(
                Arguments.of("acctfile.txt", "ACCOUNT", ' '),
                Arguments.of("custfile.txt", "CUSTOMER", ' '),

                // WHY : Assumptions: the card record's pad is a BLANK, measured rather than assumed.
                //       The FILLER span at columns 92 to 150 of app/data/ASCII/carddata.txt holds
                //       exactly one distinct character across all fifty of its records, and that
                //       character is a blank, so this row follows the account and customer extracts
                //       rather than the three zero-padded ones. The fixture reaches the same byte by
                //       a second, independent route: it omits the FILLER key entirely and lets
                //       FixedWidthCodec rebuild the pad, which makes the value a codec fact.
                Arguments.of("carddata.txt", "CARD", ' '),
                Arguments.of("tcatbal.txt", "TCATBAL", '0'),
                Arguments.of("trantype.txt", "TRANTYPE", '0'),
                Arguments.of("trancatg.txt", "TRANCAT", '0'));
    }

    /**
     * Asserts every fixture's trailing pad is made of the character its reference extract uses.
     *
     * @param fileName the fixture under test
     * @param descriptorName the registered layout the fixture is written against
     * @param padCharacter the single character the pad must consist of
     */
    @ParameterizedTest(name = "{0} pads its trailing FILLER with ''{2}''")
    @MethodSource("everyFixturePad")
    @DisplayName("every fixture pads its trailing FILLER with its extract's own character")
    void everyFixturePadsWithItsExtractsCharacter(String fileName, String descriptorName,
            char padCharacter) {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(descriptorName);
        List<CopybookLayout.FieldSpec> fields = spec.fields();
        CopybookLayout.FieldSpec pad = fields.get(fields.size() - 1);

        // WHY : Assumptions: the pad is located as the LAST declared field rather than by name,
        //       because the security-user record names its trailing pad SEC-USR-FILLER and a
        //       name match would silently skip a record whose pad is spelled differently. Its
        //       being a FILLER is then asserted, so a record whose last field is real content
        //       fails here rather than having its content compared against a pad character.
        assertThat(pad.name()).as("last declared field of %s", descriptorName).isEqualTo("FILLER");
        assertThat(pad.end()).isEqualTo(spec.reclen());

        String expected = String.valueOf(padCharacter).repeat(pad.length());
        for (String row : records(fileName)) {
            assertThat(row.substring(pad.start(), pad.end()))
                    .as("trailing pad of a %s row in %s", descriptorName, fileName)
                    .isEqualTo(expected);
        }

        // WHY : Assumptions: the two pad characters are asserted to be DIFFERENT in the same test,
        //       so the parameter is doing work. A suite that only checked "the pad is all of one
        //       character" would pass for a directory that had standardised on blanks and lost the
        //       per-record-type distinction this case exists to hold.
        assertThat(everyFixturePad().map(arguments -> arguments.get()[2]).distinct().count())
                .as("the pad character is a per-record-type fact, not one directory convention")
                .isEqualTo(2);
    }

    /**
     * Asserts that a row one byte short of its record length is refused rather than mis-decoded.
     */
    @Test
    @DisplayName("a row one byte short of its record length is refused rather than mis-decoded")
    void aShortRowIsRefusedRatherThanMisDecoded() {
        String row = records("trantype.txt").get(0);

        // WHY : Assumptions: a truncated row is the failure mode this directory is most exposed to,
        //       because an editor that strips trailing blanks shortens every record in a file at
        //       once. Asserting the refusal here is what turns that into a named failure rather than
        //       a field whose content silently came from the next field along.
        assertThatThrownBy(() -> FixedWidthCodec.decodeRecord(
                row.substring(0, row.length() - 1).getBytes(StandardCharsets.UTF_8),
                CopybookLayout.layout("TRANTYPE")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> FixedWidthCodec.decodeRecord(
                (row + " ").getBytes(StandardCharsets.UTF_8),
                CopybookLayout.layout("TRANTYPE")))
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * Asserts that the account fixture carries the key domain and money values its README states.
     */
    @Test
    @DisplayName("the account fixture carries the key domain and money values its README states")
    void theAccountFixtureCarriesItsStatedKeysAndMoney() {
        assertThat(records("acctfile.txt").stream().map(row -> row.substring(0, 11)).toList())
                .containsExactly("00000000007", "00000000050", "00000000101", "00000000102");

        Map<String, Object> first = decode("acctfile.txt", "ACCOUNT", 0);

        // WHY : Assumptions: the money fields are asserted through their decoded values rather than
        //       their stored characters, because the stored form carries a zoned-decimal sign
        //       overpunch -- the balance ends in 'G' for a positive 7 in the units position -- and a
        //       character assertion would pass for a codec that read the overpunch as text.
        assertThat(first.get("ACCT-ID")).hasToString("7");
        assertThat(first.get("ACCT-ACTIVE-STATUS")).isEqualTo("Y");
        assertThat(first.get("ACCT-CURR-BAL")).hasToString("504.77");
        assertThat(first.get("ACCT-CREDIT-LIMIT")).hasToString("2065.00");
        assertThat(first.get("ACCT-CASH-CREDIT-LIMIT")).hasToString("264.00");
        assertThat(first.get("ACCT-OPEN-DATE")).isEqualTo("2012-10-12");
        assertThat(first.get("ACCT-EXPIRAION-DATE")).isEqualTo("2028-11-30");
        assertThat(first.get("ACCT-GROUP-ID")).isEqualTo("ZEROAPR   ");
    }

    /**
     * Asserts that every account row carries a balance below the nine integer digits the statement
     * mask provides.
     */
    @Test
    @DisplayName("every account row carries a balance below the nine integer digits the statement "
            + "mask provides")
    void everyAccountBalanceFitsTheStatementMask() {
        // WHY : Assumptions: this is a property of the FIXTURE and not of the mapper. A balance of a
        //       thousand million or more raises under registered divergence D-EDIT-MASK-OVERFLOW, so
        //       a fixture row carrying one would make every statement test that used it fail for a
        //       reason unrelated to what it was asserting. Pinning the property here states the
        //       constraint where a row is added rather than where it is consumed.
        for (int row = 0; row < records("acctfile.txt").size(); row++) {
            Object balance = decode("acctfile.txt", "ACCOUNT", row).get("ACCT-CURR-BAL");
            assertThat(new java.math.BigDecimal(balance.toString()).abs())
                    .isLessThan(new java.math.BigDecimal("1000000000.00"));
        }
    }

    /**
     * Asserts that the customer fixture carries the name and address parts the statement header
     * reads.
     */
    @Test
    @DisplayName("the customer fixture carries the name and address parts the statement header "
            + "reads")
    void theCustomerFixtureCarriesTheStatementHeaderParts() {
        assertThat(records("custfile.txt").stream().map(row -> row.substring(0, 9)).toList())
                .containsExactly("000000007", "000000050", "000000101", "000000102");

        Map<String, Object> first = decode("custfile.txt", "CUSTOMER", 0);

        // WHY : Assumptions: every character field is asserted at its FULL declared width including
        //       trailing blanks, because that is the form the statement header preparation requires:
        //       it cuts each name part at its first blank, so a value handed over already trimmed
        //       would change which characters the assembly keeps.
        assertThat(first.get("CUST-FIRST-NAME")).isEqualTo("Marcus                   ");
        assertThat(first.get("CUST-MIDDLE-NAME")).isEqualTo("Elliot                   ");
        assertThat(first.get("CUST-LAST-NAME")).isEqualTo("Whitfield                ");
        assertThat(first.get("CUST-ADDR-LINE-1"))
                .isEqualTo("4820 Kingsbridge Terrace                          ");
        assertThat(first.get("CUST-ADDR-STATE-CD")).isEqualTo("OH");
        assertThat(first.get("CUST-ADDR-COUNTRY-CD")).isEqualTo("USA");
        assertThat(first.get("CUST-FICO-CREDIT-SCORE")).hasToString("701");
    }

    /**
     * Supplies the three structural markers that make the synthetic-data attestation checkable.
     *
     * @return a stream of marker label, field name and the assertion each row's value must satisfy
     */
    private static Stream<Arguments> syntheticMarkers() {
        return Stream.of(
                Arguments.of("national identifier in the unissued 900 range", "CUST-SSN", "9"),
                Arguments.of("government identifier carries the literal test prefix",
                        "CUST-GOVT-ISSUED-ID", "GOVTID"),
                Arguments.of("telephone number in the reserved fictional range", "CUST-PHONE-NUM-1",
                        "555-01"));
    }

    /**
     * Asserts that every customer row carries the synthetic marker its attestation claims.
     *
     * @param markerLabel a short description of the marker, shown in the case name
     * @param fieldName the copybook field the marker appears in
     * @param marker the substring every row's value must contain
     */
    @ParameterizedTest(name = "every customer row shows the {0}")
    @MethodSource("syntheticMarkers")
    @DisplayName("every customer row carries the synthetic marker its attestation claims")
    void everyCustomerRowCarriesItsSyntheticMarker(String markerLabel, String fieldName,
            String marker) {
        // WHY : Assumptions: these three assertions are the mechanical half of the synthetic-data
        //       attestation in fixtures/README.md. Without them the attestation is prose, and a row
        //       copied from a real source would satisfy every other assertion in this class -- its
        //       width, its offsets and its round trip are all indifferent to where a value came
        //       from. Trade-offs: a marker cannot PROVE synthesis, only that the value falls in a
        //       range no issuing authority uses; that is the strongest property a test can check,
        //       and the README carries the reasoning the test cannot.
        for (int row = 0; row < records("custfile.txt").size(); row++) {
            assertThat(String.valueOf(decode("custfile.txt", "CUSTOMER", row).get(fieldName)))
                    .contains(marker);
        }
    }

    /**
     * Measures how many characters of each row's description field are occupied by text.
     *
     * <p>Assumptions: the trailing blanks of a decoded text field are padding to the declared width
     * and are removed here, because the property being measured is how much text a row carries and
     * not how wide its field is. Only TRAILING blanks are removed: a leading blank would be content
     * in an alphanumeric move and removing it would change which characters a narrowing keeps.</p>
     *
     * @param fileName the fixture file name
     * @param descriptorName the registered layout name the fixture is written against
     * @param fieldName the copybook description field to measure
     * @return the occupied length of each row's description, in file order
     */
    private static List<Integer> occupiedDescriptionLengths(String fileName, String descriptorName,
            String fieldName) {
        return records(fileName).stream()
                .map(row -> String.valueOf(
                        FixedWidthCodec.decodeRecord(row.getBytes(StandardCharsets.UTF_8),
                                CopybookLayout.layout(descriptorName)).get(fieldName))
                        .stripTrailing().length())
                .toList();
    }

    /**
     * Asserts that the two description fixtures hold both a full-width and a short population.
     */
    @Test
    @DisplayName("the two description fixtures hold both a full-width and a short population")
    void theDescriptionFixturesHoldBothPopulations() {
        List<Integer> typeLengths = occupiedDescriptionLengths("trantype.txt", "TRANTYPE",
                "TRAN-TYPE-DESC");
        List<Integer> categoryLengths = occupiedDescriptionLengths("trancatg.txt", "TRANCAT",
                "TRAN-CAT-TYPE-DESC");

        // WHY : Assumptions: the OCCUPIED length is measured and not the decoded length. A text field
        //       decodes at its full declared 50 with its trailing blanks intact, so a raw length
        //       assertion would report every row as 50 and could not tell a full description from a
        //       short one at all -- which is precisely the distinction this test needs.
        // WHY : Assumptions: both populations are required and the reason is asymmetric. A fixture of
        //       only short descriptions would pass against a narrowing that returned its input
        //       unchanged; a fixture of only full-width ones would pass against a narrowing that
        //       always truncated and never blank-filled. Neither alone distinguishes a correct
        //       narrowing from a broken one.
        assertThat(typeLengths).contains(50);
        assertThat(typeLengths).anyMatch(length -> length < 15);
        assertThat(categoryLengths).contains(50);
        assertThat(categoryLengths).anyMatch(length -> length < 29);
    }

    /**
     * Asserts that the category fixture keys on the six-byte composite rather than on the type
     * alone.
     */
    @Test
    @DisplayName("the category fixture keys on the six-byte composite rather than on the type "
            + "alone")
    void theCategoryFixtureKeysOnTheComposite() {
        List<String> keys = records("trancatg.txt").stream()
                .map(row -> row.substring(0, 6))
                .toList();

        // WHY : Assumptions: the three rows under type 01 are what make this fixture able to tell a
        //       composite-keyed lookup from a type-keyed one. A one-to-one fixture would satisfy
        //       both readings, and the composite is what app/cpy/CVTRA04Y.cpy declares.
        assertThat(keys).containsExactly("010001", "010002", "010005", "020001", "030001", "040001",
                "050001", "060001", "070001");
        assertThat(keys).doesNotHaveDuplicates();
        assertThat(keys.stream().filter(key -> key.startsWith("01")).count()).isEqualTo(3);
    }

    /**
     * Asserts that a fixture account and customer row together drive a complete statement header
     * block.
     */
    @Test
    @DisplayName("a fixture account and customer row together drive a complete statement header "
            + "block")
    void fixtureRowsDriveACompleteStatementHeaderBlock() {
        Map<String, Object> account = decode("acctfile.txt", "ACCOUNT", 0);
        Map<String, Object> customer = decode("custfile.txt", "CUSTOMER", 0);

        StatementTextMapper.PreparedHeaderFields header = StatementTextMapper.prepareHeaderFields(
                String.valueOf(customer.get("CUST-FIRST-NAME")),
                String.valueOf(customer.get("CUST-MIDDLE-NAME")),
                String.valueOf(customer.get("CUST-LAST-NAME")),
                String.valueOf(customer.get("CUST-ADDR-LINE-1")),
                String.valueOf(customer.get("CUST-ADDR-LINE-2")),
                String.valueOf(customer.get("CUST-ADDR-LINE-3")),
                String.valueOf(customer.get("CUST-ADDR-STATE-CD")),
                String.valueOf(customer.get("CUST-ADDR-COUNTRY-CD")),
                String.valueOf(customer.get("CUST-ADDR-ZIP")),
                Long.parseLong(account.get("ACCT-ID").toString()),
                Money.of(account.get("ACCT-CURR-BAL").toString()),
                Integer.parseInt(customer.get("CUST-FICO-CREDIT-SCORE").toString()));

        // WHY : Assumptions: this is what makes the fixtures CONSUMED rather than merely validated.
        //       Every offset in both records is exercised through the mapper that reads it, so a
        //       field placed one byte out shows up as a wrong statement line rather than only as a
        //       wrong decoded value.
        List<byte[]> block = StatementTextMapper.emitHeaderBlock(header);
        assertThat(block).hasSize(StatementTextMapper.HEADER_BLOCK_LINE_COUNT);
        assertThat(block).allSatisfy(line -> assertThat(line).hasSize(80));
        assertThat(new String(block.get(1), StandardCharsets.UTF_8).stripTrailing())
                .isEqualTo("Marcus Elliot Whitfield");
        assertThat(new String(block.get(8), StandardCharsets.UTF_8))
                .startsWith("Account ID         :00000000007");
        assertThat(new String(block.get(9), StandardCharsets.UTF_8))
                .startsWith("Current Balance    :000000504.77");
        assertThat(new String(block.get(10), StandardCharsets.UTF_8))
                .startsWith("FICO Score         :701");
    }

    /**
     * Asserts that a fixture type and category row together drive a complete report detail line.
     */
    @Test
    @DisplayName("a fixture type and category row together drive a complete report detail line")
    void fixtureRowsDriveACompleteReportDetailLine() {
        Map<String, Object> type = decode("trantype.txt", "TRANTYPE", 0);
        Map<String, Object> category = decode("trancatg.txt", "TRANCAT", 0);

        byte[] detail = TransactionReportMapper.encodeDetailLine(
                "0000000000000001",
                7L,
                String.valueOf(type.get("TRAN-TYPE")),
                String.valueOf(type.get("TRAN-TYPE-DESC")),
                Integer.parseInt(category.get("TRAN-CAT-CD").toString()),
                String.valueOf(category.get("TRAN-CAT-TYPE-DESC")),
                "POS TERM  ",
                Money.of("1234.56"));

        // WHY : Assumptions: both fixture descriptions are 50 characters here, so this assertion is
        //       the one that proves the narrowing actually discards text. The type description keeps
        //       its leftmost 15 and the category description its leftmost 29, and each expected
        //       substring is written out rather than computed so a change of column width cannot be
        //       absorbed silently by the expectation.
        String line = new String(detail, StandardCharsets.UTF_8);
        assertThat(detail).hasSize(133);
        assertThat(line).isEqualTo("0000000000000001"
                + " " + "00000000007"
                + " " + "01" + "-" + "Purchase at poi"
                + " " + "0001" + "-" + "Regular sales draft purchase "
                + " " + "POS TERM  "
                + "    " + "       1,234.56"
                + "  " + " ".repeat(19));
        assertThat(line.substring(32, 47)).isEqualTo("Purchase at poi");
        assertThat(line.substring(53, 82)).isEqualTo("Regular sales draft purchase ");
    }

    /**
     * Asserts that a fixture category description drives the statement's own forty-nine-character
     * narrowing.
     */
    @Test
    @DisplayName("a fixture category description drives the statement's own forty-nine-character "
            + "narrowing")
    void aFixtureDescriptionDrivesTheStatementNarrowing() {
        String description =
                String.valueOf(decode("trancatg.txt", "TRANCAT", 0).get("TRAN-CAT-TYPE-DESC"));

        // WHY : Assumptions: the statement column is 49 where the report's category column is 29, so
        //       the same fixture value is narrowed to two different widths by two different
        //       artifacts. Asserting both from one source value is what shows the two narrowings are
        //       independent rather than one shared rule.
        assertThat(description).hasSize(50);
        assertThat(StatementTextMapper.renderDescriptionItem(description))
                .isEqualTo(description.substring(0, 49))
                .hasSize(StatementTextMapper.DESCRIPTION_ITEM_WIDTH);
    }

    /**
     * Asserts that every fixture is decoded against a descriptor the shared registry actually holds.
     *
     * @param fileName the fixture under test, shown in the case name
     * @param descriptorName the registered layout name the fixture is written against
     * @param declaredLength the record length the descriptor declares, unused by this assertion and
     *     accepted only because the argument source is shared
     * @param expectedRows the expected row count, unused by this assertion and accepted only because
     *     the argument source is shared
     */
    @ParameterizedTest(name = "{0} is written against the registered descriptor {1}")
    @MethodSource("everyFixture")
    @DisplayName("every fixture is decoded against a descriptor the shared registry actually holds")
    void everyFixtureUsesARegisteredDescriptor(String fileName, String descriptorName,
            int declaredLength, int expectedRows) {
        // WHY : Assumptions: membership of the registry is asserted rather than assumed, because the
        //       codec blanks an absent position only for a layout its own registry holds. A fixture
        //       written against a locally declared descriptor would decode under different rules
        //       from the one production reads it with, and nothing else in this class would notice.
        assertThat(CopybookLayout.names()).contains(descriptorName);
        assertThat(CopybookLayout.layout(descriptorName).name()).isEqualTo(descriptorName);
    }

    /**
     * Asserts that reading a fixture does not depend on the working directory or on a report date.
     */
    @Test
    @DisplayName("reading a fixture does not depend on the working directory or on a report date")
    void readingAFixtureIsIndependentOfAmbientState() {
        // WHY : Assumptions: the fixtures are resolved through the CLASS LOADER and not through a
        //       relative file path, so the suite runs identically from the reactor root and from the
        //       module directory. The report title band is included here because it is the only
        //       member of either mapper that takes a date, and it takes it as an argument rather
        //       than reading a clock -- which is what makes a rerun over the same fixtures produce
        //       the same bytes.
        assertThat(fixtureDirectory()).exists().isDirectory();
        byte[] firstRun = TransactionReportMapper.encodeNameHeader(LocalDate.of(2022, 7, 1),
                LocalDate.of(2022, 7, 31));
        byte[] secondRun = TransactionReportMapper.encodeNameHeader(LocalDate.of(2022, 7, 1),
                LocalDate.of(2022, 7, 31));
        assertThat(Arrays.equals(firstRun, secondRun)).isTrue();
    }
}
