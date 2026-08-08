package com.carddemo.authorization.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.mapper.PendingAuthDetailMapper;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.CopybookLayout.RecordSpec;
import com.carddemo.common.codec.FixedWidthCodec;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Consumes the two authorization detail fixtures that decode cleanly and are then refused, and asserts
 * WHERE each is refused.
 *
 * <p>Purpose: this class states one boundary that the rest of the module takes for granted -- the codec
 * is not the validator. {@code pautdtl1-match-status-invalid.bin} and
 * {@code pautdtl1-raw-complement-trap.bin} are both structurally perfect 200-byte segments that a
 * fixed-width decode reads without complaint, and both carry a value the target refuses. The refusal
 * comes from the DOMAIN -- the entity and its key -- and the two fixtures are here to prove it comes
 * from there rather than from the codec, because a reader who assumed otherwise would put validation in
 * the wrong layer or, worse, conclude a clean decode meant a storable record.
 *
 * <p>Refactoring Rationale: neither fixture had any test-source reference. That left two distinct
 * regressions invisible. The status fixture would have gone unnoticed if the closed match-status domain
 * were widened, because nothing asserted an out-of-domain value is refused from a REAL record as
 * opposed to from a hand-built argument. The complement trap would have gone unnoticed if the key's
 * domain checks were relaxed, because that fixture's entire purpose is to be the record on which a
 * missed nines complement is caught -- and a relaxed key would let the missed complement through as
 * silently wrong data rather than as a failure.
 *
 * <h2>Assumptions: the layout</h2>
 *
 * <p>Both records are the IMS child segment {@code PAUTDTL1}, transcribed from
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} lines 19 to 54 and registered as
 * {@code PAUTDTL}. Each is a single 200-byte segment. Every offset used below comes from the registry
 * rather than being restated here.
 *
 * <h2>Assumptions: the invalid match status</h2>
 *
 * <p>{@code PA-MATCH-STATUS} at offset 173 holds {@code 'X'}. The copybook's condition names at lines
 * 46 to 49 close that field's domain to {@code P}, {@code D}, {@code E} and {@code M}, and
 * {@code V1__authorization.sql} carries the same closure as
 * {@code ck_pending_auth_detail_match_status}. {@code 'X'} is in neither, so the record is
 * unstorable -- and it decodes to the character {@code X} without complaint, which is the point.
 * The refusal is asserted to arrive from entity construction with the message that names the byte, so a
 * failure here reads as a domain violation naming the value rather than as a database error naming a
 * constraint.
 *
 * <h2>Assumptions: the raw-complement trap</h2>
 *
 * <p>{@code COPAUA0C.cbl} lines 874 and 875 store {@code 99999 - date} and
 * {@code 999999999 - time}, so a reader that forgets to invert gets the stored value rather than the
 * real one. This fixture's stored values are chosen so that the un-inverted reading is IMPOSSIBLE
 * rather than merely wrong: the stored date {@code 75899} has a day-of-year part of 899, and the stored
 * time {@code 879999999} exceeds the largest nine-digit time of day there is. Both are outside the
 * domain {@code PendingAuthDetailKey} enforces, so a missed complement fails at construction instead of
 * persisting a date nine hundred days into a year. The inverted readings, {@code 24100} and
 * {@code 120000000}, are accepted and agree with the record's own originating characters, which is the
 * independent corroboration that the inversion is the correct reading and not merely the admissible
 * one.
 *
 * <p>Trade-offs: the trap could have been built from values that are merely unusual rather than
 * impossible, and that was rejected. An unusual-but-valid pair would let a missed complement persist a
 * row, and the fault would surface later as a mis-ordered browse or an authorization that never
 * expires; an impossible pair makes the same fault a refusal at the boundary, with a message naming the
 * value and the field.
 *
 * <p>Assumptions: the explanation lives in this Javadoc rather than beside the bytes, for the reason
 * the other fixture consumers record -- a comment byte would push a fixed-length record past its
 * declared 200 and corrupt the trailing filler, and Checkstyle polices Java files only, so prose next
 * to the bytes is policed by nothing.
 */
class PendingAuthDetailDomainRefusalFixtureTest {

    /** Root of the fixture directory on the test class path. */
    private static final String ROOT = "/fixtures/";

    /** The fixture whose match status is outside the closed domain. */
    private static final String INVALID_STATUS_FIXTURE = "pautdtl1-match-status-invalid.bin";

    /** The fixture whose stored key values are impossible unless they are complemented. */
    private static final String COMPLEMENT_TRAP_FIXTURE = "pautdtl1-raw-complement-trap.bin";

    /** Declared length of the authorization detail segment, {@code SEGM ... BYTES=200}. */
    private static final int SEGMENT_LENGTH = 200;

    /** The account both records belong to, supplied hierarchically because the segment omits it. */
    private static final Long ACCOUNT_ID = 10_000_000_001L;

    /** Complement base for the five-digit date, {@code 99999 - YYDDD} at COPAUA0C line 874. */
    private static final int DATE_COMPLEMENT_BASE = 99_999;

    /** Complement base for the nine-digit time, {@code 999999999 - HHMMSSmmm} at line 875. */
    private static final int TIME_COMPLEMENT_BASE = 999_999_999;

    /** The out-of-domain match status byte the status fixture carries at offset 173. */
    private static final String INVALID_STATUS = "X";

    /** Decoded ordinal date of the status fixture, 20 April 2024 as YYDDD. */
    private static final int INVALID_STATUS_ORDINAL = 24_111;

    /** Decoded time of the status fixture, 10:00:00.000 as HHMMSSmmm. */
    private static final int INVALID_STATUS_TIME = 100_000_000;

    /** The stored, un-inverted date of the trap fixture; its day-of-year part is 899. */
    private static final int TRAP_STORED_DATE = 75_899;

    /** The stored, un-inverted time of the trap fixture; it exceeds every nine-digit time of day. */
    private static final int TRAP_STORED_TIME = 879_999_999;

    /** The correctly inverted ordinal date of the trap fixture, 9 April 2024 as YYDDD. */
    private static final int TRAP_ORDINAL = DATE_COMPLEMENT_BASE - TRAP_STORED_DATE;

    /** The correctly inverted time of the trap fixture, 12:00:00.000 as HHMMSSmmm. */
    private static final int TRAP_TIME = TIME_COMPLEMENT_BASE - TRAP_STORED_TIME;

    /**
     * Reads one fixture resource as raw bytes.
     *
     * @param name the resource name below the fixture root
     * @return the resource's bytes, exactly as stored
     * @throws IllegalStateException if the resource is absent from the test class path or unreadable
     */
    private static byte[] bytes(String name) {
        try (InputStream fixture =
                PendingAuthDetailDomainRefusalFixtureTest.class.getResourceAsStream(ROOT + name)) {
            if (fixture == null) {
                throw new IllegalStateException(ROOT + name + " is not on the test class path");
            }
            return fixture.readAllBytes();
        } catch (IOException problem) {
            throw new IllegalStateException(ROOT + name + " could not be read", problem);
        }
    }

    /**
     * Returns the authorization detail layout from the shared registry.
     *
     * @return the registered 200-byte {@code PAUTDTL} record specification
     */
    private static RecordSpec detailLayout() {
        return CopybookLayout.layout("PAUTDTL");
    }

    /**
     * Decodes a single-segment fixture through the shared codec.
     *
     * @param name the resource name below the fixture root
     * @return every declared field of the record, in declaration order
     */
    private static Map<String, Object> record(String name) {
        return FixedWidthCodec.decodeRecord(bytes(name), detailLayout());
    }

    /**
     * Returns one decoded field as text with its trailing blanks removed.
     *
     * @param fields the decoded record
     * @param name the copybook field name
     * @return the field's characters with trailing blanks stripped
     */
    private static String text(Map<String, Object> fields, String name) {
        return String.valueOf(fields.get(name)).stripTrailing();
    }

    /**
     * Confirms the out-of-domain status decodes cleanly and survives a re-encode unchanged.
     *
     * <p>Assumptions: the re-encode is asserted BYTE-identical, which is the claim that matters here.
     * A codec that quietly substituted a valid status, or blanked the field, would produce a storable
     * record out of an unstorable one and the substitution would be invisible to every later layer.
     * Proving the byte survives is what establishes that the refusal below is a domain decision rather
     * than a repair the codec declined to make.</p>
     */
    @Test
    @DisplayName("the invalid-status segment decodes to X and re-encodes byte-identically")
    void theInvalidStatusSegmentDecodesAndSurvivesTheCodecUnrepaired() {
        byte[] segment = bytes(INVALID_STATUS_FIXTURE);
        Map<String, Object> fields = record(INVALID_STATUS_FIXTURE);

        assertThat(segment).hasSize(SEGMENT_LENGTH);
        assertThat(text(fields, "PA-MATCH-STATUS"))
                .as("the status byte at offset 173 is outside the copybook's closed domain")
                .isEqualTo(INVALID_STATUS);
        assertThat(text(fields, "PA-AUTH-ORIG-DATE")).isEqualTo("240420");
        assertThat(text(fields, "PA-AUTH-ORIG-TIME")).isEqualTo("100000");
        assertThat(text(fields, "PA-AUTH-FRAUD"))
                .as("the record has never been examined, so its fraud marker is blank")
                .isEmpty();
        assertThat(text(fields, "PA-FRAUD-RPT-DATE")).isEmpty();
        assertThat(FixedWidthCodec.encodeRecordPreservingSign(fields, detailLayout(), segment))
                .as("the codec neither repairs nor rejects the out-of-domain byte")
                .isEqualTo(segment);
    }

    /**
     * Confirms the invalid status is refused by the entity and not by the key.
     *
     * <p>Assumptions: the key is built successfully FIRST, which is what localises the refusal. Both
     * halves matter: an assertion that construction fails would pass if the key had refused it for an
     * unrelated reason, and the fixture's key values are deliberately ordinary so that the only thing
     * wrong with the record is its status byte.</p>
     *
     * <p>Assumptions: the message is asserted to be the FIRST-STAGE one, which names the value as not
     * being a match status at all. The staged refusal has a second message for a value that is in the
     * copybook's domain but may not be ORIGINATED -- {@code E} or {@code M} on the new-decision path --
     * and {@code 'X'} never reaches it because it fails the domain test first. Asserting the wrong stage
     * would have made this case pass on a domain that had been widened to admit {@code X}.</p>
     */
    @Test
    @DisplayName("the invalid-status segment is refused by the entity, not by its key")
    void theInvalidStatusSegmentIsRefusedByTheDomain() {
        byte[] segment = bytes(INVALID_STATUS_FIXTURE);

        PendingAuthDetailKey key = PendingAuthDetailMapper.toKey(segment, ACCOUNT_ID);
        assertThat(key.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(key.getAuthDate())
                .as("the key is ordinary, so nothing but the status byte can be refused")
                .isEqualTo(INVALID_STATUS_ORDINAL);
        assertThat(key.getAuthTime()).isEqualTo(INVALID_STATUS_TIME);

        assertThatThrownBy(() -> PendingAuthDetailMapper.toEntity(segment, ACCOUNT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matchStatus is not a match status value")
                .hasMessageContaining("but was: " + INVALID_STATUS);
    }

    /**
     * Confirms both stored key values of the trap fixture are impossible until they are complemented.
     *
     * <p>Assumptions: each stored value is paired with a VALID partner in its own construction attempt,
     * so the two refusals are attributed to the two fields independently. Building a key from both
     * stored values at once would fail on whichever the constructor checked first and prove nothing
     * about the other.</p>
     */
    @Test
    @DisplayName("the trap fixture's stored key values are both outside the key's domain")
    void theTrapFixtureStoredValuesAreRefusedByTheKey() {
        Map<String, Object> fields = record(COMPLEMENT_TRAP_FIXTURE);

        assertThat(Integer.parseInt(String.valueOf(fields.get("PA-AUTH-DATE-9C"))))
                .isEqualTo(TRAP_STORED_DATE);
        assertThat(Integer.parseInt(String.valueOf(fields.get("PA-AUTH-TIME-9C"))))
                .isEqualTo(TRAP_STORED_TIME);

        assertThatThrownBy(() -> new PendingAuthDetailKey(ACCOUNT_ID, TRAP_STORED_DATE, TRAP_TIME))
                .as("the stored date's day-of-year part is 899, which no year has")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("day-of-year")
                .hasMessageContaining("899");
        assertThatThrownBy(() -> new PendingAuthDetailKey(ACCOUNT_ID, TRAP_ORDINAL, TRAP_STORED_TIME))
                .as("the stored time exceeds every nine-digit time of day")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(PendingAuthDetailKey.AUTH_TIME_MAX))
                .hasMessageContaining(String.valueOf(TRAP_STORED_TIME));
    }

    /**
     * Confirms the complemented readings are accepted and agree with the record's own characters.
     *
     * <p>Assumptions: the agreement with {@code PA-AUTH-ORIG-DATE} and {@code PA-AUTH-ORIG-TIME} is
     * what makes this an oracle rather than a tautology. The complement arithmetic on its own only
     * proves the inverted values are admissible; the record states the same instant a second time in
     * characters the inversion does not touch, so the two readings agreeing is independent evidence
     * that the inversion is the CORRECT reading.</p>
     */
    @Test
    @DisplayName("the trap fixture's complemented key is accepted and matches its own characters")
    void theTrapFixtureComplementedValuesAgreeWithItsCharacters() {
        byte[] segment = bytes(COMPLEMENT_TRAP_FIXTURE);
        Map<String, Object> fields = record(COMPLEMENT_TRAP_FIXTURE);

        assertThat(segment).hasSize(SEGMENT_LENGTH);
        assertThat(text(fields, "PA-AUTH-ORIG-DATE")).isEqualTo("240409");
        assertThat(text(fields, "PA-AUTH-ORIG-TIME")).isEqualTo("120000");

        PendingAuthDetail detail = PendingAuthDetailMapper.toEntity(segment, ACCOUNT_ID);
        assertThat(detail.getId().getAuthDate())
                .as("the mapper inverts the stored date, yielding day 100 of 2024")
                .isEqualTo(TRAP_ORDINAL);
        assertThat(detail.getId().getAuthTime())
                .as("the mapper inverts the stored time, yielding midday exactly")
                .isEqualTo(TRAP_TIME);

        // WHY : Assumptions: the composed timestamp is the cross-check. It takes its DATE from the
        //       originating characters and its TIME from the inverted key, so an instant that agrees
        //       with both readings can only arise when the inversion is right. Day 100 of the leap year
        //       2024 is 9 April, which is exactly what the characters say.
        assertThat(PendingAuthDetailMapper.authTimestamp(detail))
                .isEqualTo(LocalDateTime.of(2024, 4, 9, 12, 0, 0));
    }
}
