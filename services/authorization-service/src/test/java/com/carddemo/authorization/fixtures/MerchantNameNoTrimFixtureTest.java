package com.carddemo.authorization.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.CopybookLayout.RecordSpec;
import com.carddemo.common.codec.FixedWidthCodec;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the trailing blanks of {@code PA-MERCHANT-NAME} are stored data and survive the load path.
 *
 * <p>This class is the documentation carrier for
 * {@code src/test/resources/fixtures/pautdtl1-merchant-name-notrim.bin}. That file is 200 bytes of
 * record and nothing else -- no header, no comment, no trailing newline -- so it cannot hold a
 * justification of its own without ceasing to be a valid segment image. The reasoning therefore lives
 * here, next to the assertions it justifies.</p>
 *
 * <p>Assumptions: the layout is {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} lines 19 to
 * 54, whose declared widths sum to exactly 200 and are registered once as
 * {@code CopybookLayout.layout("PAUTDTL")}. The width is corroborated independently by
 * {@code ims/DBPAUTP0.dbd} line 36, {@code SEGM NAME=PAUTDTL1 ... BYTES=200}, and by
 * {@code ims/PADFLDBD.DBD} line 27, {@code RECORD=(200),RECFM=F}. The fixture holds one image, so its
 * length is its record count and no framing or delimiter is involved.</p>
 *
 * <p>Assumptions -- and this is the fixture's entire reason to exist -- trailing blanks in
 * {@code PA-MERCHANT-NAME} are CONTENT, not padding to be discarded. Three independent sources in the
 * reference application establish it, and no single one of them would be sufficient:</p>
 *
 * <ol>
 *   <li>{@code dcl/AUTHFRDS.dcl} lines 73 to 77 declare {@code MERCHANT-NAME} not as a fixed
 *       character host variable but as a Db2 VARCHAR host structure -- the level-49 pair
 *       {@code MERCHANT-NAME-LEN PIC S9(4) USAGE COMP} and
 *       {@code MERCHANT-NAME-TEXT PIC X(22)}. A VARCHAR stores exactly as many characters as its
 *       length prefix declares, so that prefix, and nothing else, decides whether padding is
 *       kept.</li>
 *   <li>{@code cbl/COPAUS2C.cbl} line 130 sets that prefix with
 *       {@code MOVE LENGTH OF PA-MERCHANT-NAME TO MERCHANT-NAME-LEN}. {@code LENGTH OF} applied to a
 *       {@code PIC X(22)} item is the compile-time constant 22, never the length of the content, and
 *       the move is unconditional: there is no {@code INSPECT}, no reverse scan for the last
 *       non-blank and no {@code FUNCTION TRIM} anywhere on that path. Line 131 then moves the whole
 *       padded field into the text half. The prefix is therefore always 22 and the padded form is
 *       always what is written.</li>
 *   <li>{@code ddl/AUTHFRDS.ddl} line 18 declares {@code MERCHANT_NAME VARCHAR(22)}, and it is the
 *       ONLY VARCHAR in that table -- every other text column is {@code CHAR(n)}. That is what makes
 *       this the one field in the bounded context where trimming is OBSERVABLE: a {@code CHAR(n)}
 *       column re-pads whatever it is given, so there the distinction cannot be seen, whereas a
 *       VARCHAR preserves what it receives.</li>
 * </ol>
 *
 * <p>Trade-offs: trimming would therefore be a behavioural change and not a cleanup, so it is
 * declined. No divergence is claimed for merchant text anywhere in the migration, which leaves
 * preserving the padding as the only option consistent with parity. The cost is that a padded value
 * reaches API responses, and that cost is accepted because the alternative silently rewrites what the
 * reference application stores.</p>
 *
 * <p>Assumptions -- scope of the claim, stated so it is not over-read: the three sources above govern
 * how the baseline populates the Db2 FRAUD table's VARCHAR. The same no-trim rule holds for
 * {@code pending_auth_detail.merchant_name}, but for a different reason: that column's type follows
 * the migration's derivation rule for descriptive {@code PIC X(n)} text, and the IMS segment field is
 * itself a blank-padded {@code X(22)} that nothing in the read path trims. Line 18 of the DDL is not
 * cited as authority over the detail table.</p>
 *
 * <p>Assumptions: {@code MERCHANT-NAME-LEN} is a two-byte binary halfword holding 22, which encodes
 * as {@code 0x00 0x16} -- neither byte a line terminator, so it introduces no read hazard. It exists
 * only inside the Db2 host structure and never inside the 200-byte segment, so this fixture contains
 * no {@code COMP} field at all. Stated explicitly to forestall the reasonable assumption that a
 * length prefix must live somewhere in the record bytes; it does not.</p>
 *
 * <p>Assumptions: one blank byte plays THREE distinct roles in this single record, which is a
 * genuinely confusing adjacency and is asserted rather than described. At offsets 119 to 133 a blank
 * is retained padding that reaches {@code merchant_name} intact. At offset 174 a blank is a VALUE:
 * {@code PA-AUTH-FRAUD} has condition names only for {@code 'F'} and {@code 'R'}
 * ({@code CIPAUDTY.cpy} lines 51 and 52), yet the baseline moves {@code SPACE} there and
 * {@code cbl/COPAUS1C.cbl} lines 344 to 349 renders a bare {@code '-'} when neither condition holds,
 * which is the positive evidence behind the blank-tolerant check constraint on that column. At
 * offsets 183 to 199 a blank is {@code FILLER} that is dropped entirely and reaches no column.</p>
 *
 * <p>Trade-offs: this file is read as a fixed-length byte stream and never line by line.
 * {@code tests/fixtures/README.md} section 3.3 asks for a single trailing newline so that
 * {@code wc -l} equals the record count, and that convention is deliberately NOT followed here: a
 * {@code COMP-3} negative whose final digit is zero produces the sign byte {@code 0x0D}, so byte
 * values in a packed record cannot be line-delimited. Section 3.2 allows a departure only when the
 * scenario's own README documents it, and no README may exist in that fixture directory, which is the
 * mechanical reason this justification is Javadoc. Being precise about the hazard: a {@code COMP-3}
 * byte can never be {@code 0x0A}, because the low nibble {@code A} is neither a digit nor a valid
 * sign, so a line feed could only arrive from a {@code COMP} field and this record has none.</p>
 *
 * <p>Trade-offs: the fixture is roughly a quarter ASCII spaces, concentrated in its tail, so any tool
 * that strips trailing whitespace, cleans on save, or applies text normalisation would shorten it
 * while leaving it superficially intact. It is committed as binary and verified by byte count, never
 * by eye. The 199-byte rejection asserted below is the direct guard: losing one stripped blank is
 * exactly how this file would degrade.</p>
 */
class MerchantNameNoTrimFixtureTest {

    /** Root of the fixture directory on the test class path. */
    private static final String ROOT = "/fixtures/";

    /** The fixture under test. */
    private static final String FIXTURE = "pautdtl1-merchant-name-notrim.bin";

    /** Declared length of the pending-authorization detail segment, {@code SEGM ... BYTES=200}. */
    private static final int SEGMENT_LENGTH = 200;

    /** Logical name under which the detail layout is registered in the shared codec. */
    private static final String LAYOUT_NAME = "PAUTDTL";

    /**
     * The account this record hangs beneath.
     *
     * <p>Assumptions: {@code CIPAUDTY.cpy} declares no account field at all -- IMS supplies the
     * parent key hierarchically, and {@code DBPAUTP0.dbd} line 30 carries it on the root segment as a
     * six-byte packed {@code ACCNTID}. The relational detail table has no such hierarchy, so the
     * loader must supply the value, and it is stated here rather than decoded from the image because
     * the image genuinely does not contain it.</p>
     *
     * <p>Assumptions: the two tables are keyed differently, and the DBD says why.
     * {@code pending_auth_summary} is keyed on the account ALONE, matching the root segment's single
     * six-byte {@code TYPE=P} sequence field at line 30; {@code pending_auth_detail} is keyed on the
     * COMPOSITE of account, date and time, being that inherited parent key plus the child segment's
     * own eight-byte {@code TYPE=C} sequence field at line 37. One key per level in IMS becomes one
     * key of one or three columns relationally, which is why a detail row needs all three parts and a
     * summary row needs one.</p>
     */
    private static final Long PARENT_ACCOUNT_ID = 10_000_000_001L;

    /**
     * Length of the child segment's sequence field, {@code FIELD ... BYTES=8} at line 37 of the DBD.
     */
    private static final int KEY_LENGTH = 8;

    /**
     * Every other detail image committed to this directory, as a closed set.
     *
     * <p>Assumptions: the list is enumerated rather than discovered by scanning the directory,
     * because a classpath resource directory cannot be listed portably from a jar and a scan that
     * silently found nothing would turn the uniqueness assertion below into a test that passes
     * without comparing anything.</p>
     */
    private static final List<String> SIBLING_DETAIL_FIXTURES = List.of(
            "pautdtl-canonical.bin",
            "pautdtl-filler-nonblank.bin",
            "pautdtl-fraud-marked.bin",
            "pautdtl-line-terminator-bytes.bin",
            "pautdtl-match-status-domain.bin",
            "pautdtl-negative-zero-decode-only.bin",
            "pautdtl-purge-children.bin");

    /**
     * The base from which {@code PA-AUTH-DATE-9C} is complemented.
     *
     * <p>Assumptions: {@code cbl/COPAUA0C.cbl} line 874 computes the stored value as
     * {@code 99999 - WS-YYDDD} over a five-digit Julian date, and {@code cbl/CBPAUP0C.cbl} line 280
     * inverts it with the same base. The field name's {@code 9C} suffix is the nines-complement
     * marker.</p>
     */
    private static final int DATE_COMPLEMENT_BASE = 99_999;

    /**
     * The base from which {@code PA-AUTH-TIME-9C} is complemented.
     *
     * <p>Assumptions: {@code cbl/COPAUA0C.cbl} line 875 computes {@code 999999999 - WS-TIME-WITH-MS},
     * where line 871 builds that operand as {@code (HHMMSS * 1000) + milliseconds}. The resolution is
     * therefore milliseconds, not seconds, which is why nine digits are needed.
     * {@code cbl/COPAUS2C.cbl} line 107 inverts it with the same base.</p>
     */
    private static final int TIME_COMPLEMENT_BASE = 999_999_999;

    /** Declared width of {@code PA-MERCHANT-NAME}, and of the {@code VARCHAR} it populates. */
    private static final int MERCHANT_NAME_WIDTH = 22;

    /** The content the merchant name carries, before its retained padding. */
    private static final String MERCHANT_NAME_CONTENT = "ACME CO";

    /**
     * Confirms the fixture is exactly one unframed segment image that the shared layout decodes.
     *
     * <p>Assumptions: the absence of framing is asserted rather than assumed. The file carries no
     * length prefix, no record-descriptor word and no terminator, so its size alone determines the
     * record count; a fixture that gained a prefix would still decode field by field while shifting
     * every value by the prefix width, which is a failure that reads as success.</p>
     */
    @Test
    @DisplayName("the fixture is one unframed 200-byte image with no line terminators")
    void fixtureIsOneUnframedSegmentImage() {
        byte[] image = image();

        assertThat(image).as("one image of %d bytes, unframed and unterminated", SEGMENT_LENGTH)
                .hasSize(SEGMENT_LENGTH);
        assertThat(image[SEGMENT_LENGTH - 1])
                .as("the last byte is the final FILLER blank, not a newline")
                .isEqualTo((byte) ' ');
        assertThat(image).as("a line feed could only come from a COMP field, and there is none")
                .doesNotContain((byte) 0x0A);
        assertThat(image).as("no carriage return: every packed value here is positive, so no 0x0D")
                .doesNotContain((byte) 0x0D);
        assertThat(decoded()).as("every declared field decodes except the dropped blank FILLER")
                .hasSize(layout().fields().size() - 1);
    }

    /**
     * Confirms the merchant name keeps all fifteen of its trailing blanks at its declared width.
     *
     * <p>Assumptions: the comparison is against the padded literal, never against a trimmed form. An
     * assertion written as an equality with {@code "ACME CO"} after trimming would pass under exactly
     * the defect this fixture exists to catch, so the padded value is compared whole and is
     * additionally asserted to DIFFER from its own right-trimmed form. That second assertion is what
     * fails the moment a trim is introduced anywhere on the load path.</p>
     *
     * <p>Assumptions: seven characters of content in a twenty-two byte field is the widest contrast
     * short of an empty value, and fifteen blanks cannot be lost silently -- the length assertion
     * names the width it expected.</p>
     */
    @Test
    @DisplayName("merchant name decodes as its content plus fifteen retained trailing blanks")
    void merchantNameKeepsItsFifteenTrailingBlanks() {
        String name = text("PA-MERCHANT-NAME");

        assertThat(name).as("PA-MERCHANT-NAME is PIC X(22) and decodes at its declared width")
                .hasSize(MERCHANT_NAME_WIDTH)
                .isEqualTo(MERCHANT_NAME_CONTENT + " ".repeat(15));
        assertThat(name).as("the padded value is not its own trimmed form, so no trim has occurred")
                .isNotEqualTo(name.stripTrailing());
        assertThat(name.stripTrailing()).as("the content itself is unaffected by the padding")
                .isEqualTo(MERCHANT_NAME_CONTENT);
    }

    /**
     * Confirms each padded span is blank across its whole run with its boundary pinned by content.
     *
     * <p>Assumptions: the byte immediately before each run is asserted to be non-blank. Without that,
     * a run assertion would still pass if the content had shifted left and the run had grown, so the
     * pair of assertions is what fixes the boundary at an exact offset rather than approximately.</p>
     *
     * <p>Trade-offs: the city and postal-code runs are asserted even though their columns are
     * {@code CHAR(13)} and {@code CHAR(9)}, where the database re-pads whatever it receives and a trim
     * could not be observed. They are asserted because the byte-level contract is identical and only
     * the observability differs; recording that difference here is what stops a future reader
     * concluding that merchant name was special in its LAYOUT rather than in its COLUMN TYPE.</p>
     */
    @Test
    @DisplayName("every padded span is blank to its declared width with a content byte before it")
    void everyPaddedSpanIsBlankWithItsBoundaryPinned() {
        byte[] image = image();

        assertBlankRun(image, 119, 134, "merchant name");
        assertBlankRun(image, 142, 147, "merchant city");
        assertBlankRun(image, 154, 158, "merchant postal code");

        assertThat(text("PA-MERCHANT-CITY")).as("PIC X(13) content plus five blanks")
                .hasSize(13).isEqualTo("PORTLAND" + " ".repeat(5));
        assertThat(text("PA-MERCHANT-ZIP")).as("PIC X(09) content plus four blanks")
                .hasSize(9).isEqualTo("97201" + " ".repeat(4));
        assertThat(text("PA-MERCHANT-ID")).as("PIC X(15) filled exactly, so it cannot hide a trim")
                .hasSize(15).isEqualTo("MERCH0000000002");
        assertThat(text("PA-MERCHANT-STATE")).as("PIC X(02) filled exactly, a second control")
                .hasSize(2).isEqualTo("OR");
    }

    /**
     * Confirms the value that reaches the {@code VARCHAR(22)} column is twenty-two characters wide.
     *
     * <p>Assumptions: the entity is constructed from the decoded image, so this asserts that no layer
     * between the codec and the column narrows the value. The codec's own contract is to return text
     * at its declared width; if a right-trim were later introduced into this load path -- the most
     * common instinct when mapping fixed-width text into a database -- the width assertion here is
     * what would fail, and it would fail naming the width it expected.</p>
     *
     * <p>Assumptions: the fraud row that {@code cbl/COPAUS2C.cbl} derives from this segment carries
     * the same twenty-two characters, because its length prefix comes from the unconditional
     * {@code LENGTH OF} move at line 130 of that program rather than from the content. The invariant
     * is asserted as the width the prefix would declare.</p>
     */
    @Test
    @DisplayName("the merchant name handed to the VARCHAR column fills its declared width")
    void valueReachingTheVarcharColumnIsTwentyTwoCharacters() {
        PendingAuthDetail detail = entityFromFixture();

        assertThat(detail.getMerchantName())
                .as("merchant_name is VARCHAR(22) and receives all 22 characters")
                .hasSize(MERCHANT_NAME_WIDTH)
                .isEqualTo(MERCHANT_NAME_CONTENT + " ".repeat(15));
        assertThat(detail.getMerchantName().length())
                .as("the length prefix COPAUS2C line 130 would declare is the declared width")
                .isEqualTo(MERCHANT_NAME_WIDTH);
        assertThat(detail.getMerchantCity()).as("merchant_city keeps its padding as well")
                .hasSize(13);
        assertThat(detail.getMerchantZip()).as("merchant_zip keeps its padding as well").hasSize(9);
    }

    /**
     * Confirms the stored key holds nines complements while the columns hold business values.
     *
     * <p>Assumptions: the image carries 75869 and 896999999; the columns carry 24130 and 103000000.
     * Both halves are asserted, because asserting only the decoded values would pass for a fixture
     * that had stored the business values directly and so would no longer be exercising the
     * complement at all. The complement exists so that IMS, which orders twins ascending, returns the
     * most recent authorization first.</p>
     *
     * <p>Assumptions: 24130 is a Julian date -- 2024, day 130, which is 9 May 2024 in a leap year --
     * and it is distinct from every sibling detail fixture's date, so the whole family loads into one
     * database without a composite-key collision masking a real failure.</p>
     *
     * <p>Assumptions: both sign nibbles are {@code 0xC} rather than {@code 0xF}.
     * {@code ims/DBPAUTP0.dbd} line 37 declares the eight-byte key {@code TYPE=C}, so IMS compares it
     * byte-wise over the raw complement bytes and the sign nibble participates in that ordering.
     * Mixing carriers across sibling fixtures would perturb their order while leaving every decoded
     * value identical, which is a failure with no visible symptom. The shared codec independently
     * rejects {@code 0xF} on a signed field, so the two constraints agree.</p>
     */
    @Test
    @DisplayName("the key stores nines complements that decode to their business date and time")
    void complementKeysDecodeToTheirBusinessValues() {
        byte[] image = image();

        assertThat(Arrays.copyOfRange(image, 0, 3)).as("PA-AUTH-DATE-9C packed, sign nibble 0xC")
                .containsExactly((byte) 0x75, (byte) 0x86, (byte) 0x9C);
        assertThat(Arrays.copyOfRange(image, 3, 8)).as("PA-AUTH-TIME-9C packed, sign nibble 0xC")
                .containsExactly((byte) 0x89, (byte) 0x69, (byte) 0x99, (byte) 0x99, (byte) 0x9C);
        assertThat(packed("PA-AUTH-DATE-9C")).as("the stored value is the complement, not the date")
                .isEqualByComparingTo("75869");
        assertThat(packed("PA-AUTH-TIME-9C")).as("the stored value is the complement, not the time")
                .isEqualByComparingTo("896999999");

        PendingAuthDetailKey key = entityFromFixture().getId();
        assertThat(key.getAuthDate()).as("auth_date is the Julian date, never the complement")
                .isEqualTo(24_130);
        assertThat(key.getAuthTime()).as("auth_time is HHMMSSmmm, never the complement")
                .isEqualTo(103_000_000);
        assertThat(key.getAccountId()).as("the parent key IMS supplies hierarchically")
                .isEqualTo(PARENT_ACCOUNT_ID);
    }

    /**
     * Confirms both money spans decode exactly and demonstrate where the sign nibble lives.
     *
     * <p>Assumptions: {@code S9(10)V99} is twelve digits, which occupy seven bytes, which is fourteen
     * nibbles for thirteen used -- so exactly one LEADING pad nibble exists and the shared codec
     * rejects it if it is non-zero. The sibling summary segment's {@code S9(09)V99} is eleven digits
     * in six bytes with NO pad, where a zero high nibble would instead silently drop a digit. Two
     * record types in one directory therefore obey opposite rules, and the pad nibble is asserted here
     * so that this record's rule is pinned.</p>
     *
     * <p>Assumptions: the amount is deliberately not a round one. Its final byte is {@code 0x5C},
     * which demonstrates that the sign nibble occupies the LOW nibble of the LAST byte and shares that
     * byte with the final digit 5. A codec that assumed a whole trailing sign byte would decode this
     * as a different number rather than failing outright, so a round amount ending {@code 0x0C} would
     * not have exercised the distinction.</p>
     */
    @Test
    @DisplayName("both money spans decode to 812.45 with a zero pad nibble and a shared sign byte")
    void moneySpansDecodeExactlyAtScaleTwo() {
        byte[] image = image();

        assertThat(packed("PA-TRANSACTION-AMT")).as("NUMERIC(12,2), exact fixed point")
                .isEqualByComparingTo("812.45");
        assertThat(packed("PA-APPROVED-AMT")).as("fully approved, so the amounts agree")
                .isEqualByComparingTo("812.45");
        assertThat(image[74] >> 4).as("the single leading pad nibble must be zero").isZero();
        assertThat(image[80]).as("sign nibble 0xC shares its byte with the final digit 5")
                .isEqualTo((byte) 0x5C);
        assertThat(Arrays.copyOfRange(image, 74, 81)).as("the transaction amount span, whole")
                .containsExactly((byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x81,
                        (byte) 0x24, (byte) 0x5C);
        assertThat(Arrays.copyOfRange(image, 81, 88)).as("the approved amount span, whole")
                .containsExactly((byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x81,
                        (byte) 0x24, (byte) 0x5C);
    }

    /**
     * Confirms the POS entry mode is stored as display digits and not as a binary halfword.
     *
     * <p>Assumptions: three sources disagree in FORM while agreeing in VALUE, and the segment's form
     * is the one that governs these bytes. {@code CIPAUDTY.cpy} line 38 declares
     * {@code PIC 9(02)}, which is two characters of display; {@code dcl/AUTHFRDS.dcl} line 71 declares
     * the Db2 host variable {@code S9(4) USAGE COMP}, a binary halfword; and
     * {@code ddl/AUTHFRDS.ddl} line 16 declares the column {@code SMALLINT}.
     * {@code cbl/COPAUS2C.cbl} line 128 is the conversion between them. Writing a halfword into the
     * segment would corrupt offsets 95 and 96 while still yielding a plausible small number, so the
     * bytes are asserted as ASCII digits and not merely as the value five.</p>
     */
    @Test
    @DisplayName("POS entry mode occupies two ASCII digits, not a two-byte binary integer")
    void posEntryModeIsStoredAsDisplayDigits() {
        byte[] image = image();

        assertThat(Arrays.copyOfRange(image, 95, 97)).as("the characters '0' and '5', not 0x00 0x05")
                .containsExactly((byte) '0', (byte) '5');
        assertThat(decoded().get("PA-POS-ENTRY-MODE"))
                .as("a display-numeric span decodes to a whole number")
                .isEqualTo(5L);
        assertThat(entityFromFixture().getPosEntryMode()).as("pos_entry_mode is SMALLINT")
                .isEqualTo((short) 5);

        // WHY : Assumptions: PA-PROCESSING-CODE is the other display-numeric span in this record, and
        //       it is asserted as BYTES rather than as its decoded value because its value is zero.
        //       Zero is the one value that reads identically whether the span holds six ASCII digits
        //       or six binary NULs, so only the byte assertion can distinguish the two forms here.
        assertThat(Arrays.copyOfRange(image, 68, 74)).as("PIC 9(06) display is six ASCII digits")
                .containsOnly((byte) '0');
        assertThat(decoded().get("PA-PROCESSING-CODE")).as("and decodes to a whole number")
                .isEqualTo(0L);
    }

    /**
     * Confirms a blank byte carries three different meanings within this one record.
     *
     * <p>Assumptions: the three roles are asserted together because that is the only arrangement in
     * which the difference between them is visible. Retained padding reaches its column whole; the
     * single blank at offset 174 is a value with no condition name of its own; and the seventeen
     * blanks of {@code FILLER} reach no column at all, being dropped by the codec while remaining part
     * of the physical width. The byte value is identical in all three cases, so nothing but the layout
     * distinguishes them.</p>
     *
     * <p>Assumptions: an all-blank {@code PA-FRAUD-RPT-DATE} is an absent report date and becomes SQL
     * null, never a zero or epoch date. The fraud flag and the report date are consistent: no fraud
     * has been marked on this authorization, so no date exists to record.</p>
     */
    @Test
    @DisplayName("a blank is retained padding, a value, and dropped filler in the same record")
    void blankPlaysThreeDistinctRolesInOneRecord() {
        byte[] image = image();
        Map<String, Object> fields = decoded();

        assertThat(text("PA-MERCHANT-NAME")).as("role one: padding retained into merchant_name")
                .endsWith(" ").hasSize(MERCHANT_NAME_WIDTH);
        assertThat(text("PA-AUTH-FRAUD")).as("role two: a value, admitted by the blank-tolerant check")
                .isEqualTo(" ").hasSize(1);
        assertThat(fields).as("role three: blank FILLER is dropped and reaches no column")
                .doesNotContainKey("FILLER");
        assertThat(Arrays.copyOfRange(image, 183, 200))
                .as("yet the seventeen bytes remain part of the physical record")
                .containsOnly((byte) ' ');

        assertThat(text("PA-FRAUD-RPT-DATE")).as("eight blanks: an absent date, mapped to null")
                .isEqualTo(" ".repeat(8));
        assertThat(text("PA-MATCH-STATUS")).as("'P' is 88 PA-MATCH-PENDING at CIPAUDTY line 46")
                .isEqualTo("P");
        assertThat(text("PA-AUTH-RESP-CODE")).as("'00' is 88 PA-AUTH-APPROVED at CIPAUDTY line 31")
                .isEqualTo("00");
    }

    /**
     * Confirms re-encoding the decoded record reproduces every original byte.
     *
     * <p>Trade-offs: this assertion is necessary but NOT sufficient on its own, and pairing it with
     * the column-width assertion above is the point. A codec that trimmed on decode and re-padded on
     * encode would round-trip these bytes perfectly while handing a seven-character value to a
     * {@code VARCHAR(22)} column -- the exact defect this fixture exists to catch would pass a
     * round-trip test in isolation. The two assertions together close that gap: one proves the bytes
     * survive, the other proves the value does.</p>
     *
     * <p>Assumptions: the sign-preserving encoder is used rather than the plain one. Every packed
     * value here is a non-zero positive, so the two forms agree on this record, but the
     * sign-preserving form is what the surrounding fixtures use and keeping one idiom means a future
     * negative-zero sibling needs no change to this idiom.</p>
     */
    @Test
    @DisplayName("re-encoding the decoded record reproduces the fixture byte for byte")
    void roundTripReproducesEveryByte() {
        byte[] image = image();

        byte[] reencoded = FixedWidthCodec.encodeRecordPreservingSign(decoded(), layout(), image);

        assertThat(reencoded).as("byte identity, including all three blank runs and the 0x5C sign")
                .isEqualTo(image);
        assertThat(Arrays.copyOfRange(reencoded, 112, 134))
                .as("the merchant name span survives the round trip unchanged")
                .isEqualTo(Arrays.copyOfRange(image, 112, 134));
    }

    /**
     * Confirms a record one byte short or one byte long is refused rather than partially decoded.
     *
     * <p>Assumptions: the 199-byte case is the one that matters most for this fixture. A tool that
     * stripped a single trailing blank is precisely how this file would lose a byte, and the codec
     * checks the length before it slices the first field, so the loss is reported as a boundary
     * failure naming both widths instead of yielding a plausible map with every span shifted.</p>
     */
    @Test
    @DisplayName("a 199- or 201-byte image is rejected before any field is sliced")
    void wrongLengthImageIsRejected() {
        byte[] image = image();
        byte[] short199 = Arrays.copyOfRange(image, 0, SEGMENT_LENGTH - 1);
        byte[] long201 = Arrays.copyOf(image, SEGMENT_LENGTH + 1);

        assertThatExceptionOfType(FixedWidthCodec.RecordLengthException.class)
                .as("a stripped trailing blank must fail, not decode shifted")
                .isThrownBy(() -> FixedWidthCodec.decodeRecord(short199, layout()));
        assertThatExceptionOfType(FixedWidthCodec.RecordLengthException.class)
                .as("an appended byte, such as a newline, must fail as well")
                .isThrownBy(() -> FixedWidthCodec.decodeRecord(long201, layout()));
    }

    /**
     * Confirms this image's key collides with no other detail image in the directory.
     *
     * <p>Assumptions: the comparison is made on the eight RAW key bytes rather than on the decoded
     * date and time. Those bytes are the child segment's whole sequence field, so byte inequality is
     * exactly the uniqueness IMS enforces; and because the sibling images in this directory belong to
     * an earlier generation that stored the business values directly where this one stores their nines
     * complement, comparing decoded numbers would be comparing two different encodings and would
     * report a difference that proves nothing. With the parent account fixed, distinct key bytes also
     * mean a distinct relational composite key, so this one assertion covers both.</p>
     *
     * <p>Trade-offs: this is asserted here rather than by inserting rows into a database. A real
     * insert would additionally prove the constraint is declared, but it would need a live PostgreSQL
     * instance for a property that is decidable from the bytes alone, and the value of the assertion
     * is that a future fixture reusing this date and time is caught at all -- otherwise the collision
     * would surface as one row silently replacing or rejecting another during a load, and the failure
     * would be read as a loader defect rather than as duplicate fixture data.</p>
     */
    @Test
    @DisplayName("this image's eight-byte key is unique across every sibling detail image")
    void keyDoesNotCollideWithAnySiblingDetailImage() {
        byte[] mine = Arrays.copyOfRange(image(), 0, KEY_LENGTH);

        for (String sibling : SIBLING_DETAIL_FIXTURES) {
            byte[] content = bytes(sibling);
            assertThat(content.length % SEGMENT_LENGTH)
                    .as("%s must be a whole number of detail images", sibling)
                    .isZero();

            for (int start = 0; start < content.length; start += SEGMENT_LENGTH) {
                assertThat(Arrays.copyOfRange(content, start, start + KEY_LENGTH))
                        .as("%s image %d must not carry this fixture's key", sibling,
                                start / SEGMENT_LENGTH)
                        .isNotEqualTo(mine);
            }
        }
    }

    /**
     * Asserts a half-open span is entirely blank and is immediately preceded by content.
     *
     * @param image the record being inspected
     * @param from the first offset of the blank run, inclusive
     * @param to the offset one past the blank run
     * @param field the field name to name in a failure message
     */
    private static void assertBlankRun(byte[] image, int from, int to, String field) {
        assertThat(Arrays.copyOfRange(image, from, to))
                .as("%s: offsets %d to %d are all blank (%d bytes)", field, from, to - 1, to - from)
                .containsOnly((byte) ' ');
        assertThat(image[from - 1])
                .as("%s: offset %d is content, which pins the run's start exactly", field, from - 1)
                .isNotEqualTo((byte) ' ');
    }

    /**
     * Builds the detail entity the loader would persist from this image.
     *
     * <p>Assumptions: the account identifier is supplied rather than decoded, because the segment does
     * not contain one. Every other member, the match status included, comes from the image.</p>
     *
     * <p>Refactoring Rationale: the match status is now DECODED from offset 173 and passed in, where an
     * earlier revision relied on the constructor defaulting it to pending. Both spellings produce
     * {@code 'P'} for this particular image, so the change is invisible in the assertions -- but the
     * earlier form asserted nothing about the loader, because it would have produced {@code 'P'} for an
     * image carrying a declined status too. Reading the byte the fixture actually holds is what makes
     * this a test of the load path rather than of a default.</p>
     *
     * @return the entity populated from the decoded fixture
     */
    private static PendingAuthDetail entityFromFixture() {
        Map<String, Object> fields = decoded();

        // WHY : Trade-offs: intValueExact is used rather than intValue, so a complement that did not
        //       fit an int would raise here instead of being silently truncated into a plausible date
        //       or time. A truncation would produce a key that inserts successfully and points at the
        //       wrong authorization, which is the failure mode hardest to notice afterwards.
        PendingAuthDetailKey key = new PendingAuthDetailKey(PARENT_ACCOUNT_ID,
                DATE_COMPLEMENT_BASE - packed("PA-AUTH-DATE-9C").intValueExact(),
                TIME_COMPLEMENT_BASE - packed("PA-AUTH-TIME-9C").intValueExact());

        return new PendingAuthDetail(key, text("PA-AUTH-ORIG-DATE"), text("PA-AUTH-ORIG-TIME"),
                text("PA-CARD-NUM"), text("PA-AUTH-TYPE"), text("PA-CARD-EXPIRY-DATE"),
                text("PA-MESSAGE-TYPE"), text("PA-MESSAGE-SOURCE"), text("PA-AUTH-ID-CODE"),
                text("PA-AUTH-RESP-CODE"), text("PA-AUTH-RESP-REASON"), displayDigits(fields),
                packed("PA-TRANSACTION-AMT"), packed("PA-APPROVED-AMT"),
                text("PA-MERCHANT-CATAGORY-CODE"), text("PA-ACQR-COUNTRY-CODE"),
                ((Long) fields.get("PA-POS-ENTRY-MODE")).shortValue(), text("PA-MERCHANT-ID"),
                text("PA-MERCHANT-NAME"), text("PA-MERCHANT-CITY"), text("PA-MERCHANT-STATE"),
                text("PA-MERCHANT-ZIP"), text("PA-TRANSACTION-ID"),
                // WHY : Assumptions: the fixture's own stored match status is used rather than a
                //       literal, so this construction asserts that the committed binary carries a
                //       status the insert path can actually originate. A literal would make the
                //       assertion about this test instead of about the fixture.
                text("PA-MATCH-STATUS"));
    }

    /**
     * Restores the leading zeros of the processing code so it fills its fixed-width column.
     *
     * <p>Assumptions: {@code PA-PROCESSING-CODE} is {@code PIC 9(06)} display, so the shared codec
     * decodes it to a whole number and its leading zeros are not part of that number.
     * {@code processing_code} is {@code CHAR(6)} though, holding the digits as the segment wrote them,
     * so the zeros have to be re-established here rather than left to a numeric-to-text conversion
     * that would yield a single character for this value.</p>
     *
     * <p>Trade-offs: the width is written as a format specifier rather than derived from the layout's
     * field descriptor. Deriving it would keep one source of truth for the width, but it would also
     * make this helper depend on a descriptor lookup for a value the copybook fixes at six, and the
     * failure mode of a wrong constant here is an immediate width mismatch in the assertions above.</p>
     *
     * @param fields the decoded record
     * @return the processing code as six digits, exactly as the segment carries it
     */
    private static String displayDigits(Map<String, Object> fields) {
        return String.format("%06d", (Long) fields.get("PA-PROCESSING-CODE"));
    }

    /**
     * Returns one decoded text field at its declared width.
     *
     * @param field the exact copybook field name
     * @return the field's characters, padding included
     */
    private static String text(String field) {
        return (String) decoded().get(field);
    }

    /**
     * Returns one decoded packed field as an exact decimal.
     *
     * @param field the exact copybook field name
     * @return the field's value, at the scale its picture declares
     */
    private static BigDecimal packed(String field) {
        return (BigDecimal) decoded().get(field);
    }

    /**
     * Decodes the fixture through the shared codec.
     *
     * @return the decoded fields, keyed by copybook field name, in declaration order
     */
    private static Map<String, Object> decoded() {
        return FixedWidthCodec.decodeRecord(image(), layout());
    }

    /**
     * Returns the registered detail layout.
     *
     * @return the {@code PAUTDTL} record specification
     */
    private static RecordSpec layout() {
        return CopybookLayout.layout(LAYOUT_NAME);
    }

    /**
     * Reads the fixture under test as raw bytes.
     *
     * @return the fixture's bytes, exactly as committed
     * @throws IllegalStateException if the fixture is absent from the test class path or unreadable
     */
    private static byte[] image() {
        return bytes(FIXTURE);
    }

    /**
     * Reads any fixture in this directory as raw bytes.
     *
     * <p>Assumptions: the resource is read as bytes and never as characters. These images hold packed
     * decimal, so a character read would substitute replacement characters for byte values that are
     * not valid text and would change the length as well as the content.</p>
     *
     * @param name the fixture file name below the fixture root
     * @return that fixture's bytes, exactly as committed
     * @throws IllegalStateException if the fixture is absent from the test class path or unreadable
     */
    private static byte[] bytes(String name) {
        try (InputStream fixture =
                MerchantNameNoTrimFixtureTest.class.getResourceAsStream(ROOT + name)) {
            if (fixture == null) {
                throw new IllegalStateException(ROOT + name + " is not on the test class path");
            }
            return fixture.readAllBytes();
        } catch (IOException problem) {
            throw new IllegalStateException(ROOT + name + " could not be read", problem);
        }
    }
}
