package com.carddemo.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the structured abend record: that each component conforms to the width the reference
 * copybook declares, that control characters cannot reach a value that is logged and rendered, and that
 * the externally-facing reduction discloses nothing about the internal failure.
 *
 * <p>Assumptions: this record is written to a structured log line AND rendered into a response body, so
 * it has two consumers with different threat models. The width conformance protects the log framing and
 * the control-character neutralisation protects both, which is why neither is treated as cosmetic
 * here.</p>
 *
 * <p>Assumptions: the four declared widths are asserted as literals against the reference copybook's own
 * declarations, because the whole point of a fixed-width diagnostic area is that a reader parsing it by
 * position finds each component where the copybook says it is.</p>
 */
class AbendDetailTest {

    /**
     * Confirms the four declared widths and their sum are the values the reference copybook declares.
     *
     * <p>Assumptions: the sum is asserted alongside the parts because a reader consuming the area as one
     * fixed-width field needs the total, and a total that drifted from its parts would misalign
     * everything after the first component.</p>
     */
    @Test
    @DisplayName("declares the four reference widths and a total that is their sum")
    void declaresTheReferenceWidths() {
        assertThat(AbendDetail.ABEND_CODE_LENGTH).isEqualTo(4);
        assertThat(AbendDetail.ABEND_CULPRIT_LENGTH).isEqualTo(8);
        assertThat(AbendDetail.ABEND_REASON_LENGTH).isEqualTo(50);
        assertThat(AbendDetail.ABEND_MSG_LENGTH).isEqualTo(72);
        assertThat(AbendDetail.ABEND_DATA_LENGTH).isEqualTo(4 + 8 + 50 + 72);
    }

    /**
     * Confirms a value that already fits is carried verbatim rather than reformatted.
     *
     * <p>Assumptions: the verbatim guarantee is what makes a diagnostic useful, so the common case must
     * not pad, trim or otherwise touch a value that already conforms. Asserting equality rather than
     * containment is what distinguishes carried-verbatim from carried-approximately.</p>
     */
    @Test
    @DisplayName("a value within its declared width is carried verbatim")
    void valueWithinWidthIsCarriedVerbatim() {
        AbendDetail detail = new AbendDetail("0C7", "CBTRN02C", "posting unit of work failed", "check");

        assertThat(detail.abendCode()).isEqualTo("0C7");
        assertThat(detail.abendCulprit()).isEqualTo("CBTRN02C");
        assertThat(detail.abendReason()).isEqualTo("posting unit of work failed");
        assertThat(detail.abendMsg()).isEqualTo("check");
    }

    /**
     * Confirms an over-long value is truncated to its declared width rather than accepted whole.
     *
     * <p>Assumptions: truncation is the right direction here and refusal is not, because this record is
     * constructed on a failure path. A constructor that threw would replace a diagnostic that is
     * slightly too long with no diagnostic at all, at exactly the moment one is most needed.</p>
     *
     * @param declaredWidth the width the component under test declares
     * @param position the ordinal of the component under test, one-based
     */
    @ParameterizedTest
    @CsvSource({"4, 1", "8, 2", "50, 3", "72, 4"})
    @DisplayName("an over-long value is truncated to its declared width")
    void overLongValueIsTruncated(int declaredWidth, int position) {
        String tooLong = "X".repeat(declaredWidth + 25);
        AbendDetail detail = switch (position) {
            case 1 -> new AbendDetail(tooLong, "", "", "");
            case 2 -> new AbendDetail("", tooLong, "", "");
            case 3 -> new AbendDetail("", "", tooLong, "");
            default -> new AbendDetail("", "", "", tooLong);
        };
        String carried = switch (position) {
            case 1 -> detail.abendCode();
            case 2 -> detail.abendCulprit();
            case 3 -> detail.abendReason();
            default -> detail.abendMsg();
        };

        assertThat(carried).hasSize(declaredWidth);
        assertThat(tooLong).startsWith(carried);
    }

    /**
     * Confirms a null component becomes an empty value rather than a null a renderer would print.
     *
     * <p>Assumptions: this record is constructed from whatever a failing path could supply, and a null
     * is a realistic supply. Normalising it here means no downstream renderer has to, which is the only
     * way the normalisation happens exactly once.</p>
     */
    @Test
    @DisplayName("a null component becomes an empty value")
    void nullComponentBecomesEmpty() {
        AbendDetail detail = new AbendDetail(null, null, null, null);

        assertThat(detail.abendCode()).isEmpty();
        assertThat(detail.abendCulprit()).isEmpty();
        assertThat(detail.abendReason()).isEmpty();
        assertThat(detail.abendMsg()).isEmpty();
    }

    /**
     * Confirms a control character cannot reach a carried component.
     *
     * <p>Assumptions: a line feed in a value that is written to a structured log line splits one record
     * into two, so the second can be forged to look as authentic as the first. The same value in a
     * response body corrupts the framing. Neutralising at construction is what makes both impossible
     * regardless of which consumer reads the record.</p>
     *
     * @param hostile a component value carrying a control character
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "line\nfeed", "carriage\rreturn", "crlf\r\nboth", "tab\there",
        "nul\u0000byte", "escape\u001bsequence", "delete\u007fchar",
    })
    @DisplayName("a control character never reaches a carried component")
    void controlCharacterNeverReachesAComponent(String hostile) {
        AbendDetail detail = new AbendDetail("0C7", "CBTRN02C", hostile, hostile);

        assertThat(detail.abendReason()).doesNotContain("\n", "\r", "\t", "\u0000", "\u001b",
            "\u007f");
        assertThat(detail.abendMsg()).doesNotContain("\n", "\r", "\t", "\u0000", "\u001b", "\u007f");
    }

    /**
     * Confirms neutralisation preserves the value's own length rather than deleting characters.
     *
     * <p>Assumptions: replacing a control character rather than removing it keeps every following
     * character at the position a reader parsing by offset expects. Deleting would shift the remainder
     * left, which for a fixed-width diagnostic area is exactly the misalignment the widths exist to
     * prevent.</p>
     */
    @Test
    @DisplayName("neutralisation preserves the value's length")
    void neutralisationPreservesLength() {
        String hostile = "before\nafter";
        AbendDetail detail = new AbendDetail("", "", hostile, "");

        assertThat(detail.abendReason()).hasSameSizeAs(hostile);
    }

    /**
     * Confirms the external reduction keeps the code and discloses nothing else.
     *
     * <p>Assumptions: the code is the one component a caller can quote back to an operator, so it
     * survives. The culprit names an internal program and the reason describes internal state, so both
     * are dropped and the message is replaced with the fixed external sentence. Asserting the two
     * emptied components explicitly is what stops a later change from letting either through.</p>
     */
    @Test
    @DisplayName("the external reduction keeps only the code and the fixed external message")
    void externalReductionDisclosesOnlyTheCode() {
        AbendDetail internal = new AbendDetail(
            "0C7", "CBTRN02C", "connection pool exhausted at host db-writer-1", "internal detail");

        AbendDetail external = internal.external();

        assertThat(external.abendCode()).isEqualTo("0C7");
        assertThat(external.abendCulprit()).isEmpty();
        assertThat(external.abendReason()).isEmpty();
        assertThat(external.abendMsg()).isEqualTo(AbendDetail.EXTERNAL_ABEND_MSG);
    }

    /**
     * Confirms the external reduction leaks no fragment of the internal values it dropped.
     *
     * <p>Assumptions: emptiness is asserted above; this asserts the stronger property that no
     * distinctive internal token appears anywhere in the reduced record's rendering, which catches a
     * change that moved a value into a different component rather than dropping it.</p>
     */
    @Test
    @DisplayName("the external reduction leaks no fragment of the internal detail")
    void externalReductionLeaksNoFragment() {
        AbendDetail external = new AbendDetail(
            "0C7", "CBTRN02C", "host db-writer-1 password rotated", "stack frame 7").external();

        String rendered = external.toString();

        assertThat(rendered)
            .doesNotContain("CBTRN02C")
            .doesNotContain("db-writer-1")
            .doesNotContain("password")
            .doesNotContain("stack frame");
    }

    /**
     * Confirms the external reduction is idempotent, so applying it twice is safe.
     *
     * <p>Assumptions: the reduction may be applied at more than one layer of an error path, and a second
     * application must not change the result. Without this, whether a response disclosed the code would
     * depend on how many layers it passed through.</p>
     */
    @Test
    @DisplayName("the external reduction is idempotent")
    void externalReductionIsIdempotent() {
        AbendDetail once = new AbendDetail("0C7", "CBTRN02C", "reason", "message").external();

        assertThat(once.external()).isEqualTo(once);
    }

    /**
     * Confirms the fixed external message fits inside the width its component declares.
     *
     * <p>Assumptions: a published constant that exceeded its own component's width would be truncated by
     * the very constructor that carries it, so the sentence a caller sees would differ from the sentence
     * the code declares.</p>
     */
    @Test
    @DisplayName("the fixed external message fits its declared component width")
    void externalMessageFitsItsComponent() {
        assertThat(AbendDetail.EXTERNAL_ABEND_MSG.length())
            .isLessThanOrEqualTo(AbendDetail.ABEND_MSG_LENGTH);
        assertThat(new AbendDetail("", "", "", AbendDetail.EXTERNAL_ABEND_MSG).abendMsg())
            .isEqualTo(AbendDetail.EXTERNAL_ABEND_MSG);
    }

    /**
     * Confirms two records built from the same inputs are equal, as a value type must be.
     *
     * <p>Assumptions: equality matters because an error path may build the same diagnostic twice and a
     * test comparing an expected record against an actual one relies on it. Asserting it also confirms
     * the normalisation is deterministic rather than carrying any per-instance state.</p>
     */
    @Test
    @DisplayName("two records built from the same inputs are equal")
    void equalInputsProduceEqualRecords() {
        AbendDetail first = new AbendDetail("0C7", "CBTRN02C", "reason\nwith control", "msg");
        AbendDetail second = new AbendDetail("0C7", "CBTRN02C", "reason\nwith control", "msg");

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    /**
     * Confirms this record is Java-serializable and survives a round trip unchanged.
     *
     * <p>Refactoring Rationale: this record is a FIELD of a serializable exception --
     * {@code com.carddemo.reference.service.DisclosureGroupService.DisclosureGroupNotFoundException},
     * which declares a serialization identity of its own and retains this record so an operator can read
     * the abend code, culprit, reason and message that stand in for the reference program's termination
     * at {@code app/cbl/CBACT04C.cbl} line 458. Java records are not serializable unless declared so, so
     * that exception promised a stable serialized form while holding a member that could not be written:
     * any attempt to write it -- a container replicating an error, a harness round-tripping one, a
     * framework capturing one for later inspection -- would have failed at the field, reporting this type
     * rather than the exception a reader was looking at.</p>
     *
     * <p>Assumptions: the round trip is performed rather than the interface merely asserted, because
     * declaring the interface is not sufficient on its own -- a component whose own type were not
     * serializable would still fail at write time. Every component here is a {@code String}, and
     * exercising the write is what proves it.</p>
     *
     * <p>Assumptions: the record is built with a control character in one component, so the round trip is
     * asserted over the NORMALISED value this type stores rather than over the input. Serializing a record
     * bypasses its canonical constructor on the way back in, so a normalisation that did not survive would
     * show up here as an inequality.</p>
     *
     * @throws Exception if the round trip cannot be performed, which is itself the failure under test
     */
    @Test
    @DisplayName("the record is serializable and survives a round trip unchanged")
    void theRecordSurvivesAJavaSerializationRoundTrip() throws Exception {
        AbendDetail original = new AbendDetail("0C7", "CBACT04C", "reason\nwith control", "msg");

        assertThat(original).isInstanceOf(Serializable.class);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        Object restored;
        try (ObjectInputStream in =
                new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = in.readObject();
        }

        assertThat(restored).isEqualTo(original);
    }
}
