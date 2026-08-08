package com.carddemo.authorization.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.mapper.PendingAuthDetailMapper;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.CopybookLayout.RecordSpec;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.codec.PackedDecimalCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Consumes the two committed detail segments whose point is a refusal or a decode trap.
 *
 * <p>Purpose: {@code pautdtl1-match-status-invalid.bin} and {@code pautdtl1-raw-complement-trap.bin} were
 * committed without an executable reader, so nothing established what either was for and nothing would
 * have failed had either been edited, mislabelled or deleted. Both are 200-byte {@code PAUTDTL1}
 * segments; one must NOT become an entity and one must, and this class states which is which and why.
 *
 * <h2>Refactoring Rationale: the match-status fixture asserts a JAVA refusal, not a SQL one</h2>
 *
 * <p>The obvious reading of an out-of-domain match status is that it exercises
 * {@code ck_pending_auth_detail_match_status}, the check constraint {@code V1__authorization.sql} declares
 * at its line 621. Through the mapper it cannot. {@link PendingAuthDetail}'s constructor routes the value
 * through a guard that rejects anything outside the copybook's closed {@code {P, D, E, M}} domain, so
 * {@link PendingAuthDetailMapper#toEntity(byte[], Long)} raises before a statement is prepared and the
 * database is never consulted. A test claiming the constraint was exercised would have described a path
 * that does not execute: it would have passed, for the wrong reason, while the constraint stayed
 * unverified and a reader believed otherwise.
 *
 * <p>Assumptions: the correct subject is therefore the JAVA invariant -- the refusal happens, it happens
 * at construction rather than at persistence, and its message names the domain rather than a column. The
 * engine-tier claim, that the constraint refuses a row arriving by some other route, belongs to a
 * container-backed test, exactly as the fraud byte's two claims are already split between
 * {@link PendingAuthDetailFraudDomainFixtureTest} and {@code PendingAuthFraudDomainRepositoryIT}. This
 * class drives no engine and does not pretend to.
 *
 * <h2>Refactoring Rationale: the complement trap asserts what its near-sibling structurally cannot</h2>
 *
 * <p>{@code pautdtl1-time-leading-nines.bin} already proves the raw-complement leak, and this fixture
 * decodes to the same Julian date of 24100, so on the date alone it would be a duplicate. Its distinct
 * contribution is measured, not asserted by assumption: the sibling's decoded time is 1000, four digit
 * characters wide, so the nine-position zero-fill the reference slices is what turns it into
 * {@code 000001000} -- and a reader that skipped the inversion would hold {@code 999998999}, which differs
 * from the true value in WIDTH as well as in value. This fixture's decoded time is 120000000, already
 * nine characters wide, and its stored complement 879999999 is nine characters wide too. The width safety
 * net is therefore absent here, and the only thing separating a correctly inverted reading from an
 * uninverted one is the hour position: 12 against 87.
 *
 * <p>Assumptions: that difference is why both files are kept. The sibling catches a pipeline that never
 * zero-filled; this one catches a pipeline that read the packed span cleanly, produced an ordinary-looking
 * nine-digit number, and never inverted it. Neither substitutes for the other, and the two assertions
 * below that compare the pair are what stop a maintainer thinning the corpus from removing the one
 * carrying the boundary the other has no shape to carry.
 *
 * <p>Trade-offs: this class additionally drives {@link PendingAuthDetailMapper#toEntity(byte[], Long)} for
 * the trap, which the sibling's consumer never does -- it asserts at codec level only. Proving that the
 * key COLUMNS receive the inverted values, and not merely that a subtraction in a test yields them, is
 * the claim that closes the leak at the layer a loader actually uses.
 *
 * <h2>Assumptions: the layout</h2>
 *
 * <p>Both records are the IMS child segment {@code PAUTDTL1} from
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} lines 19 to 54, registered as
 * {@code PAUTDTL} at 200 bytes. Offsets are read from the registry rather than restated, so a registry
 * or fixture that moved a field fails here instead of leaving a case that reads a neighbouring byte and
 * passes.
 */
class PendingAuthDetailRefusedSegmentFixtureTest {

    /** The classpath directory every fixture in this module lives under. */
    private static final String FIXTURE_ROOT = "fixtures/";

    /** The segment whose match status is outside the copybook's closed domain. */
    private static final String INVALID_MATCH_STATUS = "pautdtl1-match-status-invalid.bin";

    /** The segment whose stored key is an ordinary-looking nine-digit number that must be inverted. */
    private static final String COMPLEMENT_TRAP = "pautdtl1-raw-complement-trap.bin";

    /** The near-sibling whose date this fixture repeats and whose digit width it deliberately does not. */
    private static final String LEADING_NINES = "pautdtl1-time-leading-nines.bin";

    /** The registry name of the layout both fixtures are written against. */
    private static final String LAYOUT = "PAUTDTL";

    /** The account the parent summary occurrence carries, which the child segment does not hold. */
    private static final long PARENT_ACCOUNT_ID = 10_000_000_001L;

    /** The out-of-domain match status the first fixture stores at the declared match-status offset. */
    private static final String REFUSED_MATCH_STATUS = "X";

    /** The nines base the five-digit Julian date is subtracted from, per {@code cbl/CBPAUP0C.cbl} L280. */
    private static final int DATE_COMPLEMENT_BASE = 99_999;

    /** The nines base the nine-digit time is subtracted from, per {@code cbl/COPAUS2C.cbl} L107. */
    private static final int TIME_COMPLEMENT_BASE = 999_999_999;

    /** The Julian date both the trap and its near-sibling decode to. */
    private static final int SHARED_BUSINESS_DATE = 24_100;

    /** The trap's decoded authorization time, which reads as noon exactly. */
    private static final int TRAP_BUSINESS_TIME = 120_000_000;

    /** The trap's stored complement, which is nine characters wide just as the decoded value is. */
    private static final int TRAP_STORED_TIME = 879_999_999;

    /** The near-sibling's decoded authorization time, which is four characters wide before zero-fill. */
    private static final int SIBLING_BUSINESS_TIME = 1_000;

    /** The digit positions the reference's nine-digit alphanumeric time overlay presents. */
    private static final int TIME_DIGIT_POSITIONS = 9;

    /**
     * Reads one fixture resource as raw bytes.
     *
     * @param name the resource name below the fixture root
     * @return the resource's bytes, exactly as stored
     * @throws AssertionError if the resource is not on the test classpath
     * @throws UncheckedIOException if the resource cannot be read once opened
     */
    private static byte[] bytesOf(String name) {
        try (InputStream stream = PendingAuthDetailRefusedSegmentFixtureTest.class.getClassLoader()
                .getResourceAsStream(FIXTURE_ROOT + name)) {
            if (stream == null) {
                throw new AssertionError("fixture " + name + " is not on the test classpath");
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException("fixture " + name + " could not be read", failure);
        }
    }

    /**
     * Decodes one fixture's single record through the registered layout.
     *
     * @param name the resource name below the fixture root
     * @return the decoded field map, keyed by copybook field name
     */
    private static Map<String, Object> decoded(String name) {
        return FixedWidthCodec.decodeRecord(bytesOf(name), CopybookLayout.layout(LAYOUT));
    }

    /**
     * Returns one decoded text field with its trailing blanks removed.
     *
     * @param fields the decoded record
     * @param name the copybook field name
     * @return the field's characters with trailing blanks stripped
     */
    private static String textField(Map<String, Object> fields, String name) {
        return String.valueOf(fields.get(name)).stripTrailing();
    }

    /**
     * Returns the registry-declared start offset of one field.
     *
     * @param name the copybook field name
     * @return the zero-based byte offset the registry declares for that field
     * @throws java.util.NoSuchElementException if the layout declares no such field
     */
    private static int offsetOf(String name) {
        return CopybookLayout.layout(LAYOUT).field(name).start();
    }

    /**
     * Renders a decoded time as the nine positions the reference receiver slices.
     *
     * <p>Assumptions: the zero-fill is the reference's own -- {@code cbl/COPAUS2C.cbl} L35 to L37 declare
     * a nine-digit display numeric and redefine it as a nine-character alphanumeric, and a display
     * numeric is stored zero-filled to its declared width. Reproducing it here keeps the slice
     * boundaries visible at the assertions that depend on them.</p>
     *
     * @param decodedTime the decoded time, which must occupy at most nine digit positions
     * @return exactly nine digit characters, zero-filled on the left
     */
    private static String clockOf(int decodedTime) {
        return String.format("%0" + TIME_DIGIT_POSITIONS + "d", decodedTime);
    }

    /**
     * Confirms both fixtures are one whole record of the registry-declared segment length.
     *
     * <p>Assumptions: the width is asserted from the registry rather than against a literal, so a change
     * to the declared geometry fails here rather than leaving two files that no longer divide.</p>
     */
    @Test
    @DisplayName("both fixtures are exactly one record of the registry-declared length")
    void bothFixturesAreOneWholeRecord() {
        int reclen = CopybookLayout.layout(LAYOUT).reclen();

        assertThat(bytesOf(INVALID_MATCH_STATUS)).hasSize(reclen);
        assertThat(bytesOf(COMPLEMENT_TRAP)).hasSize(reclen);
    }

    /**
     * Confirms the out-of-domain match status is present, at the offset the registry declares.
     *
     * <p>Assumptions: the byte is located through the registry's field descriptor rather than a literal
     * offset, so a field that had moved fails here rather than leaving this case reading a neighbouring
     * character that happened to differ.</p>
     */
    @Test
    @DisplayName("the fixture stores an out-of-domain match status at the declared offset")
    void theFixtureStoresAnOutOfDomainMatchStatus() {
        assertThat(textField(decoded(INVALID_MATCH_STATUS), "PA-MATCH-STATUS"))
                .isEqualTo(REFUSED_MATCH_STATUS);
        assertThat(bytesOf(INVALID_MATCH_STATUS)[offsetOf("PA-MATCH-STATUS")])
                .as("the stored byte is the ASCII letter X, not a control byte or a blank")
                .isEqualTo((byte) 'X');

        // WHY : Assumptions: the ADJACENT fraud position is asserted to hold its legitimate unmarked
        //       state, so the refusal below is provably about the match status and not about the byte
        //       beside it. The two are consecutive single-character fields, which is precisely the pair
        //       an off-by-one offset would confuse, and a blank fraud mark is the ordinary state for an
        //       authorization no operator has touched.
        assertThat(bytesOf(INVALID_MATCH_STATUS)[offsetOf("PA-AUTH-FRAUD")])
                .as("the neighbouring fraud position is unmarked, so the refusal isolates")
                .isEqualTo((byte) ' ');
    }

    /**
     * Confirms the refusal happens in Java, at construction, before any statement is prepared.
     *
     * <p>Assumptions: the exception type and the message content are asserted together, and the pairing
     * is what distinguishes this refusal from a database one. A constraint violation would arrive as a
     * persistence exception naming a constraint; the two are not interchangeable evidence, and accepting
     * either would let the class pass whichever layer happened to reject first.</p>
     *
     * <p>Trade-offs: the mapper is driven with the WHOLE segment rather than with the single character,
     * so the path under test is the one a loader takes. Calling the domain guard directly would prove the
     * guard and leave open whether the mapper reaches it -- which is the only question the fixture
     * raises.</p>
     */
    @Test
    @DisplayName("the mapper refuses the segment in Java, naming the closed domain")
    void theMapperRefusesTheSegmentBeforeAnyStatement() {
        byte[] segment = bytesOf(INVALID_MATCH_STATUS);

        assertThatThrownBy(() -> PendingAuthDetailMapper.toEntity(segment, PARENT_ACCOUNT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matchStatus")
                .hasMessageContaining(REFUSED_MATCH_STATUS);

        // WHY : Assumptions: the admissible set is read from the DOMAIN TYPE and the refused value is
        //       asserted absent from it, rather than P and D being written out here. A second
        //       declaration of the copybook's condition names inside a test would be free to disagree
        //       with the type it exists to verify.
        assertThat(PendingAuthDetail.ORIGINATED_MATCH_STATUSES)
                .containsExactly(PendingAuthDetail.MATCH_STATUS_PENDING,
                        PendingAuthDetail.MATCH_STATUS_DECLINED)
                .doesNotContain(REFUSED_MATCH_STATUS);
    }

    /**
     * Confirms every other field of the refused segment is well formed, so only the status is at fault.
     *
     * <p>Assumptions: a refusal proves nothing about WHICH field was refused unless the rest of the
     * record is known to be acceptable. Decoding the whole segment and reaching fields well beyond the
     * match status is what shows this is a single-fault vector rather than a generally malformed record
     * that would have been refused several ways over.</p>
     *
     * <p>Trade-offs: the field-map operation is used rather than the entity operation, because the field
     * map reaches every state a stored segment can be in while the entity operation admits only the two
     * an insert may originate. That division is the mapper's own, and it is why a reader of an expired or
     * matched occurrence uses the map.</p>
     */
    @Test
    @DisplayName("only the match status is at fault; every other field of the segment decodes")
    void onlyTheMatchStatusIsAtFault() {
        Map<String, Object> fields = decoded(INVALID_MATCH_STATUS);

        assertThat(textField(fields, "PA-CARD-NUM")).isEqualTo("4000123456789010");
        assertThat(textField(fields, "PA-TRANSACTION-ID")).isEqualTo("TXN000000000034");
        assertThat(textField(fields, "PA-AUTH-RESP-CODE")).isEqualTo("00");
        assertThat(textField(fields, "PA-MERCHANT-NAME")).isEqualTo("ACME HARDWARE");
        assertThat(textField(fields, "PA-AUTH-ORIG-DATE")).isEqualTo("240420");
        assertThat(textField(fields, "PA-AUTH-ORIG-TIME")).isEqualTo("100000");

        // WHY : Assumptions: the trailing pad is asserted from the RAW BYTES because the decoder omits
        //       FILLER from the field map by design -- asserting it through the map would silently
        //       compare a null. A record whose pad carried content would be a second fault, and this
        //       vector is deliberately a single one.
        RecordSpec spec = CopybookLayout.layout(LAYOUT);
        assertThat(fields).doesNotContainKey("FILLER");
        String pad = new String(bytesOf(INVALID_MATCH_STATUS), offsetOf("FILLER"),
                spec.field("FILLER").length(), StandardCharsets.US_ASCII);
        assertThat(pad.isBlank()).as("the trailing pad carries no content").isTrue();
    }

    /**
     * Confirms the trap's stored key inverts to a real clock reading and its raw form does not.
     *
     * <p>Assumptions: the subtractions are the reference's own and are written out rather than hidden
     * behind a helper, matching {@link PendingAuthDetailComplementKeyFixtureTest}. The hour position is
     * the assertion that carries the case: 12 is an hour and 87 is not, and no other position separates
     * the two readings.</p>
     */
    @Test
    @DisplayName("the trap's key inverts to noon while its uninverted form yields no legal hour")
    void theTrapsKeyInvertsToARealClockReading() {
        byte[] segment = bytesOf(COMPLEMENT_TRAP);
        int storedDate = PackedDecimalCodec.decodePacked(segment, 0, 5, 0, true).intValueExact();
        int storedTime = PackedDecimalCodec.decodePacked(segment, 3, 9, 0, true).intValueExact();

        assertThat(storedTime).isEqualTo(TRAP_STORED_TIME);
        assertThat(DATE_COMPLEMENT_BASE - storedDate).isEqualTo(SHARED_BUSINESS_DATE);
        assertThat(TIME_COMPLEMENT_BASE - storedTime).isEqualTo(TRAP_BUSINESS_TIME);

        String decodedClock = clockOf(TIME_COMPLEMENT_BASE - storedTime);
        assertThat(decodedClock.substring(0, 2)).as("decoded hours").isEqualTo("12");
        assertThat(decodedClock.substring(2, 4)).as("decoded minutes").isEqualTo("00");
        assertThat(decodedClock.substring(4, 6)).as("decoded seconds").isEqualTo("00");
        assertThat(decodedClock.substring(6)).as("decoded milliseconds").isEqualTo("000");

        assertThat(Integer.parseInt(clockOf(storedTime).substring(0, 2)))
                .as("an uninverted reading yields an hour no clock can hold")
                .isGreaterThan(23);

        // WHY : Trade-offs: the ORIGINATING time field is a separate ASCII span holding 120000, and
        //       cross-checking the inverted key against it is what makes the expected value independent
        //       of the arithmetic just performed. Without it both sides of the comparison would come
        //       from the same subtraction, and a wrong complement base would agree with itself.
        assertThat(textField(decoded(COMPLEMENT_TRAP), "PA-AUTH-ORIG-TIME"))
                .isEqualTo(decodedClock.substring(0, 6));
    }

    /**
     * Confirms the trap contributes a width-independent boundary its near-sibling has no shape to carry.
     *
     * <p>Assumptions: the two fixtures are asserted to share a decoded date and to differ in the DIGIT
     * WIDTH of their decoded times, and that second property is the justification for keeping both.
     * Without it the pair reads as a duplicate, and a maintainer thinning the corpus would have no way
     * to see which file carried which detector.</p>
     *
     * <p>Trade-offs: both raw forms fail an hour check, so hour range alone does not distinguish them.
     * What distinguishes them is that the sibling's decoded time is narrower than the nine positions the
     * reference slices, so a pipeline that skipped the inversion there produces a value of the wrong
     * WIDTH as well as the wrong magnitude and is caught twice over. The trap's decoded time is already
     * nine wide, as is its stored complement, so width detects nothing and the inversion is the only
     * thing under test.</p>
     */
    @Test
    @DisplayName("the trap removes the width safety net its near-sibling still enjoys")
    void theTrapContributesAWidthIndependentBoundary() {
        byte[] sibling = bytesOf(LEADING_NINES);
        int siblingStoredDate = PackedDecimalCodec.decodePacked(sibling, 0, 5, 0, true).intValueExact();
        int siblingStoredTime = PackedDecimalCodec.decodePacked(sibling, 3, 9, 0, true).intValueExact();

        assertThat(DATE_COMPLEMENT_BASE - siblingStoredDate)
                .as("the two fixtures deliberately share a decoded Julian date")
                .isEqualTo(SHARED_BUSINESS_DATE);
        assertThat(TIME_COMPLEMENT_BASE - siblingStoredTime).isEqualTo(SIBLING_BUSINESS_TIME);

        assertThat(String.valueOf(SIBLING_BUSINESS_TIME).length())
                .as("the sibling's decoded time is narrower than the nine sliced positions, so a "
                        + "pipeline that skipped the inversion there is caught on width as well")
                .isLessThan(TIME_DIGIT_POSITIONS);
        assertThat(String.valueOf(TRAP_BUSINESS_TIME).length())
                .as("the trap's decoded time already fills all nine positions")
                .isEqualTo(TIME_DIGIT_POSITIONS);
        assertThat(String.valueOf(TRAP_STORED_TIME).length())
                .as("and so does its stored complement, which is why width detects nothing here")
                .isEqualTo(TIME_DIGIT_POSITIONS);
    }

    /**
     * Confirms the trap segment loads and that the key columns receive the inverted values.
     *
     * <p>Assumptions: unlike the refused segment this one must SUCCEED through the mapper, and asserting
     * that is what fixes the difference between the two files. A trap that could not be loaded would
     * test what the refused segment already tests, and the leak it exists to catch happens on a record
     * that loads cleanly.</p>
     *
     * <p>Trade-offs: this is the assertion the near-sibling's consumer does not make -- that one stops at
     * codec level. Proving the key COLUMNS hold 24100 and 120000000, rather than that a subtraction in a
     * test yields them, is what closes the leak at the layer a loader uses.</p>
     */
    @Test
    @DisplayName("the trap segment loads and its key columns hold the inverted values")
    void theTrapSegmentLoadsWithAnInvertedKey() {
        PendingAuthDetail loaded =
                PendingAuthDetailMapper.toEntity(bytesOf(COMPLEMENT_TRAP), PARENT_ACCOUNT_ID);

        assertThat(loaded.getId().getAccountId()).isEqualTo(PARENT_ACCOUNT_ID);
        assertThat(loaded.getId().getAuthDate())
                .as("the key column holds the inverted date, never the stored complement")
                .isEqualTo(SHARED_BUSINESS_DATE);
        assertThat(loaded.getId().getAuthTime())
                .as("the key column holds the inverted time, never the stored complement")
                .isEqualTo(TRAP_BUSINESS_TIME);
        assertThat(loaded.getMatchStatus()).isEqualTo(PendingAuthDetail.MATCH_STATUS_PENDING);
        assertThat(loaded.getTransactionId()).isEqualTo("TXN000000000021");
    }
}
