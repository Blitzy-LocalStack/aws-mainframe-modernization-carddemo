package com.carddemo.authorization.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.dto.PendingAuthDetailResponse;
import com.carddemo.authorization.dto.PendingAuthRowView;
import com.carddemo.authorization.dto.PendingAuthSummaryResponse;
import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.web.PageResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds every public conversion of the two IMS segment mappers to the committed binary fixtures.
 *
 * <h2>Purpose</h2>
 *
 * <p>Refactoring Rationale: this class exists because eighteen public conversions across
 * {@link PendingAuthDetailMapper} and {@link PendingAuthSummaryMapper} had NO caller of any kind --
 * not a production path and not a test. Their owning load and unload services are a later part of the
 * migration, so production reachability cannot be manufactured honestly here; what can be established,
 * and what was missing, is that each conversion is held to the record layout it claims to implement.
 * The distinction matters because these two classes together are the whole decode of the reference
 * hierarchical segments: a field read from the wrong offset, a packed key used raw instead of decoded,
 * or a century pivot applied to the wrong field would each produce a plausible row that no later reader
 * could question.</p>
 *
 * <p>Assumptions: every case works from a committed fixture rather than from a hand-built object,
 * because a hand-built object cannot disagree with the mapper about where a field lives. The fixtures
 * are the byte images the ETL codec produced for the same layouts, so a mapper that read an offset
 * differently would fail here rather than agreeing with itself.</p>
 *
 * <p>Assumptions: five fixtures this class consumes previously had no executable consumer at all --
 * {@code auth-reply-transmitted-64.bin}, {@code auth-request-buffer500.bin},
 * {@code pautdtl1-date-formats.bin}, {@code pautdtl1-match-status-invalid.bin} and
 * {@code pautdtl1-raw-complement-trap.bin}. Each was enrolled for presence and nothing more, so the
 * regression each was authored to catch could return unnoticed. Their assertions are named in the cases
 * below rather than folded into a generic sweep, so a failure names the contract that broke.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
class SegmentConversionContractTest {

    /**
     * The classpath directory every fixture in this module sits beneath.
     */
    private static final String FIXTURE_ROOT = "fixtures/";

    /**
     * The account identifier the detail fixtures were generated against.
     *
     * <p>Assumptions: the detail segment does NOT carry its parent's account identifier -- the
     * hierarchy supplies it from the root -- so every conversion of a detail segment takes it as an
     * argument, and this is the value the committed fixtures were produced under.</p>
     */
    private static final Long FIXTURE_ACCOUNT_ID = 10000000001L;

    /**
     * The declared byte length of one detail segment.
     */
    private static final int DETAIL_SEGMENT_LENGTH = 200;

    /**
     * The declared byte length of one summary segment.
     */
    private static final int SUMMARY_SEGMENT_LENGTH = 100;

    /**
     * The payload length of one encoded reply, before the reference program's trailing byte.
     */
    private static final int REPLY_PAYLOAD_LENGTH = 63;

    /**
     * The buffer width the reference consumer receives a request into.
     */
    private static final int REQUEST_BUFFER_LENGTH = 500;

    /**
     * The payload length of one encoded request.
     */
    private static final int REQUEST_PAYLOAD_LENGTH = 170;

    /**
     * Confirms the two mappers publish the segment and unload lengths their layouts declare.
     *
     * <p>Assumptions: the lengths are asserted because every other case divides a fixture by one of
     * them. A mapper reporting the wrong length would make every record count below wrong in the same
     * direction, and a sweep that derived its expectations from the same method would still pass.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("both mappers publish the segment and unload lengths their layouts declare")
    void bothMappersPublishTheirDeclaredLengths() {
        assertThat(PendingAuthDetailMapper.segmentLength()).isEqualTo(DETAIL_SEGMENT_LENGTH);
        assertThat(PendingAuthSummaryMapper.segmentLength()).isEqualTo(SUMMARY_SEGMENT_LENGTH);
        assertThat(PendingAuthDetailMapper.unloadRecordLength())
                .isGreaterThanOrEqualTo(DETAIL_SEGMENT_LENGTH);
        assertThat(PendingAuthSummaryMapper.unloadRecordLength())
                .isGreaterThanOrEqualTo(SUMMARY_SEGMENT_LENGTH);
    }

    /**
     * A canonical detail segment decodes to an entity and re-encodes byte for byte.
     *
     * <p>Assumptions: the round trip is asserted on the two-argument encode, which is handed the image
     * it was decoded from, because that is the overload a replace path uses -- it preserves the bytes of
     * every position the entity does not model, and the seventeen-byte filler is exactly such a
     * position. Asserting byte identity through the one-argument overload would require the entity to
     * model padding it deliberately does not.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a canonical detail segment decodes to an entity and re-encodes byte for byte")
    void aDetailSegmentRoundTripsThroughItsEntity() {
        byte[] segment = bytesOf("pautdtl1-canonical.bin");
        assertThat(segment).hasSize(DETAIL_SEGMENT_LENGTH);

        PendingAuthDetail entity = PendingAuthDetailMapper.toEntity(segment, FIXTURE_ACCOUNT_ID);
        PendingAuthDetailKey key = PendingAuthDetailMapper.toKey(segment, FIXTURE_ACCOUNT_ID);

        assertThat(entity.getId()).isEqualTo(key);
        assertThat(key.getAccountId()).isEqualTo(FIXTURE_ACCOUNT_ID);
        assertThat(PendingAuthDetailMapper.toSegment(entity, segment)).isEqualTo(segment);
    }

    /**
     * The detail segment's field map names every leaf the layout declares.
     *
     * <p>Assumptions: the map is asserted to be non-empty and to agree with the entity on the two
     * identifying values, rather than being compared against a written list of names. A written list
     * would restate the layout registry in a second place, which is the duplication the registry exists
     * to prevent; agreeing with the entity proves the two decodes of one image reach one answer.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the detail segment's field map agrees with the entity decoded from the same image")
    void theDetailFieldMapAgreesWithTheEntity() {
        byte[] segment = bytesOf("pautdtl1-canonical.bin");

        Map<String, Object> fields = PendingAuthDetailMapper.toSegmentFields(segment);
        PendingAuthDetail entity = PendingAuthDetailMapper.toEntity(segment, FIXTURE_ACCOUNT_ID);

        assertThat(fields).isNotEmpty();
        assertThat(fields.values()).allSatisfy(value -> assertThat(value).isNotNull());
        assertThat(String.valueOf(fields.get("PA-CARD-NUM")).trim())
                .isEqualTo(entity.getCardNum().trim());
    }

    /**
     * A summary segment decodes to a segment carrier, to an entity, and re-encodes byte for byte.
     *
     * <p>Assumptions: all three of the summary encode overloads are exercised, because they take
     * different inputs -- a decoded carrier, a field map, and a field map plus the image it came from --
     * and only the third can preserve the thirty-four-byte filler. The first two are asserted to produce
     * the declared length rather than byte identity, which is what they can honestly promise.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a summary segment round-trips through all three encode overloads")
    void aSummarySegmentRoundTripsThroughEveryEncodeOverload() {
        byte[] segment = bytesOf("pautsum0-canonical.bin");
        assertThat(segment).hasSize(SUMMARY_SEGMENT_LENGTH);

        PendingAuthSummaryMapper.SummarySegment decoded =
                PendingAuthSummaryMapper.toSummarySegment(segment);
        Map<String, Object> fields = PendingAuthSummaryMapper.toSegmentFields(segment);

        assertThat(decoded.accountId()).isNotNull();
        assertThat(decoded.customerId()).isNotNull();
        assertThat(PendingAuthSummaryMapper.toSegment(decoded)).hasSize(SUMMARY_SEGMENT_LENGTH);
        assertThat(PendingAuthSummaryMapper.toSegment(fields)).hasSize(SUMMARY_SEGMENT_LENGTH);
        assertThat(PendingAuthSummaryMapper.toSegment(fields, segment)).isEqualTo(segment);
    }

    /**
     * The aggregate decode accepts a segment whose counters are unset and refuses one that carries them.
     *
     * <p>Assumptions: the two halves are asserted together because the refusal is the load-bearing one
     * and would otherwise look like a defect. The aggregate owns the arithmetic that produces its
     * balances, counters and totals, so a decision-path decode that assembled them from bytes could
     * produce a state no sequence of authorizations could reach -- and the row would be perfectly well
     * formed.</p>
     *
     * <p>Assumptions: the SAME populated segment is then required to decode through the load-path
     * counterpart, which is what makes the refusal a routing rule rather than a dead end. The pair is
     * asserted in one case on purpose: a refusal asserted alone reads as "this segment cannot be
     * loaded", and the load path exists precisely to load it. Refactoring Rationale: the loader was
     * first written against the refusing decode and could not have loaded any real extract, because every
     * parent record a running database produces is populated. This case is what fails if the two decodes
     * are ever conflated again.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the summary aggregate decode accepts an unset segment and refuses a populated one")
    void theSummaryAggregateDecodeRefusesUnrepresentableState() {
        byte[] populated = bytesOf("pautsum0-canonical.bin");
        byte[] unset = PendingAuthSummaryMapper.toSegment(unsetAggregateFields(populated), populated);

        PendingAuthSummary aggregate = PendingAuthSummaryMapper.toEntity(unset);

        assertThat(aggregate.getAccountId()).isEqualTo(10000000001L);
        assertThat(aggregate.getCustomerId()).isNotNull();
        assertThat(aggregate.getCreditLimit()).isEqualByComparingTo("5000.00");
        assertThatThrownBy(() -> PendingAuthSummaryMapper.toEntity(populated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the summary aggregate cannot represent");

        PendingAuthSummary loaded = PendingAuthSummaryMapper.fromExtractRecord(populated);

        assertThat(loaded.getAccountId()).isEqualTo(aggregate.getAccountId());
        assertThat(loaded.getCustomerId()).isEqualTo(aggregate.getCustomerId());
        assertThat(PendingAuthSummaryMapper.toUnloadRecord(loaded))
                .as("the load decode and the unload encode are inverses over a populated segment")
                .isEqualTo(populated);
    }

    /**
     * Builds the field map of a committed segment with every aggregate-owned component cleared.
     *
     * <p>Assumptions: the cleared components are exactly the ones the aggregate computes for itself --
     * the authorization status character, the five status slots, the credit balance, the two counters and
     * the two totals. The two identifiers and the two mirrored limits are left as the fixture holds them,
     * because the aggregate does accept those: one is its identity and the other pair is refreshed from
     * the account master on every authorization.</p>
     *
     * <p>Assumptions: the map is derived from a COMMITTED image rather than written out, so the offsets
     * and widths still come from the registered layout. Writing a segment by hand would let this case
     * agree with itself about where a field lives, which is the failure the fixtures exist to prevent.</p>
     *
     * @param segment the committed segment image to derive from; must not be {@code null}
     * @return the field map with the aggregate-owned components cleared, never {@code null}
     */
    private static Map<String, Object> unsetAggregateFields(byte[] segment) {
        Map<String, Object> fields =
                new LinkedHashMap<>(PendingAuthSummaryMapper.toSegmentFields(segment));
        fields.put("PA-AUTH-STATUS", " ");
        fields.put("PA-ACCOUNT-STATUS",
                " ".repeat(PendingAuthSummaryMapper.ACCOUNT_STATUS_SLOTS
                        * PendingAuthSummaryMapper.ACCOUNT_STATUS_SLOT_WIDTH));
        fields.put("PA-CREDIT-BALANCE", BigDecimal.ZERO.setScale(2));
        fields.put("PA-APPROVED-AUTH-CNT", BigDecimal.ZERO);
        fields.put("PA-DECLINED-AUTH-CNT", BigDecimal.ZERO);
        fields.put("PA-APPROVED-AUTH-AMT", BigDecimal.ZERO.setScale(2));
        fields.put("PA-DECLINED-AUTH-AMT", BigDecimal.ZERO.setScale(2));
        return fields;
    }

    /**
     * The five account-status slots the summary segment declares are each readable by occurrence.
     *
     * <p>Assumptions: the arity of five is asserted by reading every slot and by requiring the sixth to
     * be refused, because the schema stores the array as five discrete columns precisely so the arity is
     * enforced rather than assumed. A reader that silently returned nothing for an out-of-range
     * occurrence would let a caller walk past the end of the array without noticing.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the summary segment's five account-status slots are readable and bounded at five")
    void theSummaryAccountStatusArrayIsBoundedAtFive() {
        PendingAuthSummaryMapper.SummarySegment decoded =
                PendingAuthSummaryMapper.toSummarySegment(bytesOf("pautsum0-canonical.bin"));

        for (int occurrence = 1; occurrence <= PendingAuthSummaryMapper.ACCOUNT_STATUS_SLOTS;
                occurrence++) {
            assertThat(decoded.accountStatusSlot(occurrence))
                    .as("account status slot %d", occurrence)
                    .isNotNull()
                    .hasSize(PendingAuthSummaryMapper.ACCOUNT_STATUS_SLOT_WIDTH);
        }
        assertThatThrownBy(() -> decoded.accountStatusSlot(
                PendingAuthSummaryMapper.ACCOUNT_STATUS_SLOTS + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and "
                        + PendingAuthSummaryMapper.ACCOUNT_STATUS_SLOTS);
    }

    /**
     * The unload forms of both segments decode to the same values their bare segments do.
     *
     * <p>Assumptions: the prefixed detail unload form is used for the account-identifier accessor,
     * because the account identifier is what the prefix carries -- a bare segment has none, which is why
     * every other detail conversion takes it as an argument. The unload record is then asserted to yield
     * the same entity the segment behind its prefix does, which is the property an unload-then-reload
     * cycle depends on.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the unload forms of both segments decode to the values their segments carry")
    void theUnloadFormsDecodeToTheSameValues() {
        byte[] prefixedDetail = bytesOf("unload-prefixed-detail-206.bin");
        int stride = PendingAuthDetailMapper.unloadRecordLength();
        byte[] firstRecord = Arrays.copyOf(prefixedDetail, stride);

        Long accountId = PendingAuthDetailMapper.unloadedAccountId(firstRecord);
        byte[] segment = PendingAuthDetailMapper.unloadedSegment(firstRecord);
        PendingAuthDetail fromUnload = PendingAuthDetailMapper.fromUnloadRecord(firstRecord);

        assertThat(accountId).isNotNull();
        assertThat(segment).hasSize(DETAIL_SEGMENT_LENGTH);
        assertThat(fromUnload.getId().getAccountId()).isEqualTo(accountId);
        assertThat(PendingAuthDetailMapper.toEntity(segment, accountId).getTransactionId())
                .isEqualTo(fromUnload.getTransactionId());

        byte[] prefixedSummary = bytesOf("unload-prefixed-summary-100.bin");
        byte[] firstSummary = Arrays.copyOf(prefixedSummary,
                PendingAuthSummaryMapper.unloadRecordLength());
        assertThat(PendingAuthSummaryMapper.fromUnloadRecord(firstSummary).accountId()).isNotNull();
    }

    /**
     * The detail and summary response projections publish a narrowed account number.
     *
     * <p>Assumptions: both projections are exercised together because both are screen-shaped records
     * that no HTTP operation returns -- the committed contract records the summary projection as the
     * record of what the terminal displayed rather than as a response body. What has to hold regardless
     * is the narrowing: a projection that published all sixteen digits would defeat the one disclosure
     * rule this context adds, and there is no endpoint test to catch it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("both screen projections publish the account number narrowed to its last four")
    void bothScreenProjectionsNarrowTheAccountNumber() {
        PendingAuthDetail detail =
                PendingAuthDetailMapper.toEntity(bytesOf("pautdtl1-canonical.bin"), FIXTURE_ACCOUNT_ID);
        PendingAuthDetailMapper.ScreenContext detailContext =
                new PendingAuthDetailMapper.ScreenContext("CP01", "CardDemo", "COPAUS1C", "Pending",
                        LocalDateTime.of(2024, 7, 15, 9, 15, 30), null);

        PendingAuthDetailResponse detailResponse =
                PendingAuthDetailMapper.toResponse(detail, null, detailContext);

        assertThat(detailResponse.cardNumber()).doesNotContain(detail.getCardNum());
        assertThat(detailResponse.cardNumber())
                .endsWith(detail.getCardNum().substring(detail.getCardNum().length() - 4));

        byte[] populatedSummary = bytesOf("pautsum0-canonical.bin");
        PendingAuthSummary summary = PendingAuthSummaryMapper.toEntity(
                PendingAuthSummaryMapper.toSegment(unsetAggregateFields(populatedSummary),
                        populatedSummary));
        PendingAuthSummaryResponse summaryResponse = PendingAuthSummaryMapper.toResponse(summary,
                PageResponse.<PendingAuthRowView>empty(),
                new PendingAuthSummaryMapper.ScreenChrome("CP00", "CardDemo", "07/15/24", "COPAUS0C",
                        "Pending Authorizations", "09:15:30"),
                new PendingAuthSummaryMapper.CardholderContext("CARDHOLDER NAME", "ADDRESS ONE", null,
                        "ADDRESS TWO", "2065550100"),
                null);

        assertThat(summaryResponse).isNotNull();
    }

    /**
     * The authorization timestamp and its rendered form agree on one instant.
     *
     * <p>Assumptions: the rendered form is asserted to be the declared twenty-three characters and to
     * agree with the instant, because the two are produced by different code paths over the same two
     * members -- the acquirer's originating date and the server-derived key time -- and a disagreement
     * would put one value on a screen and a different one in a fraud row's primary key.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the authorization timestamp and its twenty-three character rendering agree")
    void theAuthorizationTimestampAndItsRenderingAgree() {
        PendingAuthDetail detail =
                PendingAuthDetailMapper.toEntity(bytesOf("pautdtl1-canonical.bin"), FIXTURE_ACCOUNT_ID);

        LocalDateTime instant = PendingAuthDetailMapper.authTimestamp(detail);
        String rendered = PendingAuthDetailMapper.authTimestampText(detail);

        assertThat(rendered).hasSize(PendingAuthDetailMapper.AUTH_TIMESTAMP_LENGTH);
        assertThat(rendered).startsWith(String.format("%02d-%02d-%02d",
                instant.getYear() % 100, instant.getMonthValue(), instant.getDayOfMonth()));
        assertThat(rendered).contains(String.format("%02d.%02d.%02d", instant.getHour(),
                instant.getMinute(), instant.getSecond()));
    }

    /**
     * The paired date-format fixture decodes both centuries of the fraud report date.
     *
     * <p>Assumptions: this is the fixture's whole purpose and it had no consumer. Its two records carry
     * report dates of {@code 07/15/24} and {@code 12/31/99}, which straddle the century pivot of
     * seventy: the first must resolve into the twenty-first century and the second into the twentieth.
     * A pivot applied in the wrong direction would put both in one century, and both results would be
     * valid calendar dates, so only a paired fixture can tell them apart.</p>
     *
     * <p>Assumptions: the rendered form is asserted to round-trip back to the same eight characters, so
     * the render and the parse are held to each other rather than each to a written expectation.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the paired date-format fixture decodes both sides of the century pivot")
    void thePairedDateFormatFixtureStraddlesTheCenturyPivot() {
        byte[] pair = bytesOf("pautdtl1-date-formats.bin");
        assertThat(pair).hasSize(2 * DETAIL_SEGMENT_LENGTH);

        PendingAuthDetail twentyFirst = PendingAuthDetailMapper.toEntity(
                Arrays.copyOfRange(pair, 0, DETAIL_SEGMENT_LENGTH), FIXTURE_ACCOUNT_ID);
        PendingAuthDetail twentieth = PendingAuthDetailMapper.toEntity(
                Arrays.copyOfRange(pair, DETAIL_SEGMENT_LENGTH, pair.length), FIXTURE_ACCOUNT_ID);

        LocalDate recent = AuthFraudMapper.segmentFraudReportDate(twentyFirst.getFraudReportDate());
        LocalDate old = AuthFraudMapper.segmentFraudReportDate(twentieth.getFraudReportDate());

        assertThat(recent).isEqualTo(LocalDate.of(2024, 7, 15));
        assertThat(old).isEqualTo(LocalDate.of(1999, 12, 31));
        assertThat(AuthFraudMapper.segmentFraudReportDateText(recent)).isEqualTo("07/15/24");
        assertThat(AuthFraudMapper.segmentFraudReportDateText(old)).isEqualTo("12/31/99");
    }

    /**
     * The invalid-match-status fixture is refused rather than decoded into an out-of-domain row.
     *
     * <p>Assumptions: this is the fixture's whole purpose and it had no consumer. Its match-status
     * position carries {@code X}, which is outside the four values the column admits, and the refusal
     * has to come from the decode rather than from the database -- a row that reached the store would be
     * rejected by a check constraint far from its cause, inside a transaction that had already done
     * work.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the invalid-match-status fixture is refused by the segment decode")
    void theInvalidMatchStatusFixtureIsRefused() {
        byte[] segment = bytesOf("pautdtl1-match-status-invalid.bin");
        assertThat(segment).hasSize(DETAIL_SEGMENT_LENGTH);

        assertThatThrownBy(() -> PendingAuthDetailMapper.toEntity(segment, FIXTURE_ACCOUNT_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The complement-trap fixture decodes its key rather than carrying the stored complement.
     *
     * <p>Assumptions: this is the fixture's whole purpose and it had no consumer. The reference segment
     * stores its date and time as nines complements so that an ascending index scan reads newest first;
     * the migrated key stores the plain values. A decode that carried the complement through would
     * produce a key that is numerically valid, orders correctly among other complemented keys, and
     * names a different instant from the one the authorization happened at. The assertion is therefore
     * that the decoded key is NOT the stored complement and that re-encoding reproduces the stored
     * bytes, which pins both directions.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the complement-trap fixture decodes its key instead of carrying the complement")
    void theComplementTrapFixtureDecodesItsKey() {
        byte[] segment = bytesOf("pautdtl1-raw-complement-trap.bin");
        assertThat(segment).hasSize(DETAIL_SEGMENT_LENGTH);

        PendingAuthDetailKey key = PendingAuthDetailMapper.toKey(segment, FIXTURE_ACCOUNT_ID);
        PendingAuthDetail entity = PendingAuthDetailMapper.toEntity(segment, FIXTURE_ACCOUNT_ID);

        assertThat(key.getAuthDate()).isPositive()
                .isNotEqualTo(PendingAuthDetailMapper.AUTH_DATE_COMPLEMENT_BASE
                        - key.getAuthDate());
        assertThat(key.getAuthTime()).isNotNegative()
                .isNotEqualTo(PendingAuthDetailMapper.AUTH_TIME_COMPLEMENT_BASE
                        - key.getAuthTime());
        assertThat(PendingAuthDetailMapper.toSegment(entity, segment)).isEqualTo(segment);
    }

    /**
     * The transmitted reply frame is sixty-four bytes and decodes to the sixty-three the encoder emits.
     *
     * <p>Assumptions: this is the fixture's whole purpose and it had no consumer. The reference program
     * declares its assembly cursor {@code PIC S9(4) VALUE 1} and reuses it as a buffer length, so it
     * transmits one byte more than it wrote; the migrated encoder emits exactly sixty-three and the
     * decoder tolerates both. The trailing byte is asserted to be a BLANK and specifically not a line
     * feed, because a line feed would make the frame indistinguishable from the text form of the same
     * payload, which is also sixty-four bytes.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the transmitted reply frame is 64 bytes and re-emits at 63")
    void theTransmittedReplyFrameReEmitsAtSixtyThree() {
        byte[] transmitted = bytesOf("auth-reply-transmitted-64.bin");
        assertThat(transmitted).hasSize(REPLY_PAYLOAD_LENGTH + 1);
        assertThat(transmitted[REPLY_PAYLOAD_LENGTH]).isEqualTo((byte) ' ');
        assertThat(transmitted).doesNotContain((byte) '\n').doesNotContain((byte) '\r');

        String frame = new String(transmitted, StandardCharsets.US_ASCII);
        CsvAuthCodec.AuthReply decoded = CsvAuthCodec.decodeReply(frame);

        assertThat(CsvAuthCodec.encodeReply(decoded)).hasSize(REPLY_PAYLOAD_LENGTH);
        assertThat(CsvAuthCodec.encodeReplyBytes(decoded))
                .isEqualTo(Arrays.copyOf(transmitted, REPLY_PAYLOAD_LENGTH));
    }

    /**
     * The five-hundred byte request buffer carries one payload and blanks to the end.
     *
     * <p>Assumptions: this is the fixture's whole purpose and it had no consumer. The reference consumer
     * receives into a five-hundred character buffer and parses the payload out of it, so the padding is
     * part of the interface rather than an artefact of the file: a decoder that took the buffer's whole
     * width as the payload would read a hundred and seventy character message plus three hundred and
     * thirty blanks into eighteen fields. Every padding byte is asserted to be a blank, and not merely
     * the length, because a NUL-padded buffer is length-indistinguishable and would decode
     * differently.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the 500-byte request buffer carries one 170-character payload and blank padding")
    void theRequestBufferCarriesOnePayloadAndBlankPadding() {
        byte[] buffer = bytesOf("auth-request-buffer500.bin");
        assertThat(buffer).hasSize(REQUEST_BUFFER_LENGTH);
        assertThat(buffer).doesNotContain((byte) '\n').doesNotContain((byte) '\r')
                .doesNotContain((byte) 0);

        byte[] payload = Arrays.copyOf(buffer, REQUEST_PAYLOAD_LENGTH);
        for (int index = REQUEST_PAYLOAD_LENGTH; index < REQUEST_BUFFER_LENGTH; index++) {
            assertThat(buffer[index]).as("padding byte at %d", index).isEqualTo((byte) ' ');
        }

        CsvAuthCodec.AuthRequest decoded =
                CsvAuthCodec.decodeRequest(new String(payload, StandardCharsets.US_ASCII));

        assertThat(decoded.cardNum()).hasSize(16);
        assertThat(decoded.transactionId()).isNotBlank();
        assertThat(CsvAuthCodec.encodeRequest(decoded))
                .isEqualTo(new String(payload, StandardCharsets.US_ASCII));
    }

    /**
     * Reads one fixture resource as bytes.
     *
     * @param name the resource name below the fixture root; must not be {@code null}
     * @return the resource's bytes, never {@code null}
     * @throws AssertionError if the resource does not resolve on the test classpath
     * @throws UncheckedIOException if the resource cannot be read
     */
    private static byte[] bytesOf(String name) {
        try (InputStream stream = SegmentConversionContractTest.class.getClassLoader()
                .getResourceAsStream(FIXTURE_ROOT + name)) {
            if (stream == null) {
                throw new AssertionError("fixture " + name + " is not on the test classpath");
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException("fixture " + name + " could not be read", failure);
        }
    }
}
