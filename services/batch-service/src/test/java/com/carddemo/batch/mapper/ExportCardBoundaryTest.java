package com.carddemo.batch.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.mapper.ExportRecordMapper.ExportRecord;
import com.carddemo.batch.mapper.ExportRecordMapper.OpaqueSensitiveValue;
import com.carddemo.batch.mapper.ExportRecordMapper.Prefix;
import com.carddemo.batch.mapper.ExportRecordMapper.RecordType;
import com.carddemo.common.codec.CopybookLayout;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the card export boundary is reachable, and that its sensitive carrier stays opaque.
 *
 * <p>Purpose: {@code ExportRecord.ofCard} is public and requires an {@code OpaqueSensitiveValue}, and the
 * only ways to obtain one used to be a private constructor and a private constant -- so no caller outside
 * the mapper could assemble a card export record at all, and the export direction of that view was
 * unreachable rather than merely untested. These cases exercise the two new factories through the public
 * encode boundary, so the opening is proven usable and proven narrow.</p>
 */
@DisplayName("the card export boundary")
class ExportCardBoundaryTest {

    /** A 26-character timestamp in the form the prefix declares. */
    private static final String TIMESTAMP = "2022-07-18 12:00:00.000000";

    /** The field name the verification-value span is declared under. */
    private static final String FIELD_CARD_CVV = "EXP-CARD-CVV-CD";

    /** The two bytes a present carrier is built from, chosen so neither is zero. */
    private static final byte[] CARRIED = {0x01, 0x39};

    /**
     * Builds a card prefix.
     *
     * @return the prefix, never {@code null}
     */
    private static Prefix cardPrefix() {
        return new Prefix(RecordType.CARD, TIMESTAMP, 1L, "0001", "US");
    }

    /**
     * Builds the ordered card projection the encoder expects, with the pad and the verification value
     * both excluded.
     *
     * @return the projection, never {@code null}
     */
    private static Map<String, Object> cardProjection() {
        CopybookLayout.RecordSpec view = ExportRecordMapper.viewLayout(RecordType.CARD);
        Map<String, Object> fields = new LinkedHashMap<>();
        for (CopybookLayout.FieldSpec field : view.fields()) {
            if (FIELD_CARD_CVV.equals(field.name()) || "FILLER".equals(field.name())) {
                continue;
            }
            fields.put(field.name(), field.kind() == CopybookLayout.Kind.TEXT ? " ".repeat(field.length())
                    : java.math.BigDecimal.ZERO);
        }
        return fields;
    }

    /** A record assembled with an absent carrier encodes to the declared record length. */
    @Test
    @DisplayName("assemble and encode a card record with no verification value")
    void assemblesAndEncodesWithAnAbsentCarrier() {
        ExportRecord assembled =
                ExportRecord.ofCard(cardPrefix(), cardProjection(), OpaqueSensitiveValue.absent());

        assertThat(assembled.cardVerificationValue().isPresent()).isFalse();
        assertThat(ExportRecordMapper.toRecord(assembled))
                .hasSize(ExportRecordMapper.recordLayout().reclen());
    }

    /**
     * A record assembled with a present carrier lays exactly those bytes over the declared span.
     *
     * <p>Assumptions: the span is located from the descriptor rather than from a literal offset, so the
     * assertion follows the copybook if the layout ever moves.</p>
     */
    @Test
    @DisplayName("lay the carried bytes over the declared span")
    void laysTheCarriedBytesOverTheDeclaredSpan() {
        ExportRecord assembled = ExportRecord.ofCard(cardPrefix(), cardProjection(),
                OpaqueSensitiveValue.of(CARRIED));

        byte[] image = ExportRecordMapper.toRecord(assembled);
        CopybookLayout.RecordSpec view = ExportRecordMapper.viewLayout(RecordType.CARD);
        CopybookLayout.FieldSpec span = view.field(FIELD_CARD_CVV);
        int payloadOffset = ExportRecordMapper.recordLayout().field("EXPORT-RECORD-DATA").start();

        assertThat(new byte[] {image[payloadOffset + span.start()],
                image[payloadOffset + span.start() + 1]}).isEqualTo(CARRIED);
    }

    /** The carrier copies on the way in, so a caller may zero its own array afterwards. */
    @Test
    @DisplayName("copy on the way in, so a caller may clear its own array")
    void copiesOnTheWayIn() {
        byte[] caller = CARRIED.clone();
        OpaqueSensitiveValue carrier = OpaqueSensitiveValue.of(caller);
        java.util.Arrays.fill(caller, (byte) 0);

        assertThat(carrier.copyBytes()).isEqualTo(CARRIED);
    }

    /** The carrier copies on the way out, so one reader cannot empty it for the next. */
    @Test
    @DisplayName("copy on the way out, so one reader cannot empty it for the next")
    void copiesOnTheWayOut() {
        OpaqueSensitiveValue carrier = OpaqueSensitiveValue.of(CARRIED);
        java.util.Arrays.fill(carrier.copyBytes(), (byte) 0);

        assertThat(carrier.copyBytes()).isEqualTo(CARRIED);
    }

    /** The carrier renders as a constant marker and never as its content. */
    @Test
    @DisplayName("render as a redaction marker and never as content")
    void rendersAsARedactionMarker() {
        String rendered = OpaqueSensitiveValue.of(CARRIED).toString();

        assertThat(rendered).isEqualTo(OpaqueSensitiveValue.of(new byte[] {0x7F, 0x7F}).toString());
        assertThat(rendered.getBytes(StandardCharsets.US_ASCII)).doesNotContain(CARRIED[1]);
    }

    /** A carrier of the wrong width is refused rather than padded or truncated. */
    @Test
    @DisplayName("refuse a carrier of any other width")
    void refusesACarrierOfTheWrongWidth() {
        assertThatThrownBy(() -> OpaqueSensitiveValue.of(new byte[] {0x01}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly " + OpaqueSensitiveValue.VALUE_WIDTH);
        assertThatThrownBy(() -> OpaqueSensitiveValue.of(new byte[] {0x01, 0x02, 0x03}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A null is refused rather than routed to the absent carrier. */
    @Test
    @DisplayName("refuse a null rather than treating it as absence")
    void refusesANullRatherThanTreatingItAsAbsence() {
        assertThatThrownBy(() -> OpaqueSensitiveValue.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absent()");
    }
}
