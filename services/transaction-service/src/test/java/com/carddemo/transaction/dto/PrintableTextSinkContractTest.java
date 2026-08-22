package com.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Holds the character domain this module admits to the fixed-width encoder every downstream sink writes
 * through, so the boundary admits exactly what the records it feeds can carry.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Refactoring Rationale: the review that prompted this class found that the five externally authored
 * {@code PIC X} members of the capture -- source, description, merchant name, merchant city and merchant
 * postal code -- carried a width limit and no character-set limit at all, and that the omission was
 * argued for on the ground that {@code TRAN-DESC PIC X(100)} at {@code app/cpy/CVTRA05Y.cpy} line 9
 * admits every character of its code page and that safety belongs to the output sink. That argument holds
 * for exactly one sink. The HTML statement escapes, so a stored angle bracket is inert there; the
 * plain-text statement's 80-column bands and the transaction report's 133-column records have no escaping
 * mechanism of any kind, so a stored line terminator is a record boundary a reader cannot tell from a real
 * one. And a character the US-ASCII fixed-width encoder cannot represent is not refused when it is
 * submitted -- it is stored, and it fails a batch or reporting run days later, attributable to no request
 * and refusable by nobody.</p>
 *
 * <p>Assumptions: those two hazards are what the domain is DERIVED from rather than a tidiness
 * preference, and this class proves the derivation against the encoder itself rather than restating it.
 * {@link com.carddemo.common.codec.FixedWidthCodec} is the encoder AAP section 0.7.3 and transformation
 * rule T3 make the boundary the data must survive, and its {@code TRAN} geometry is the 350-byte record
 * the statement and report jobs read, so encoding one value into one of its five text spans is the
 * narrowest honest test of what a stored value does downstream.</p>
 *
 * <p>Assumptions: no downstream module is touched by this class and none needs to be. The property under
 * test is a property of the ENCODER and of the domain the boundary admits, and both are reachable from
 * here: {@code common-lib} is a compile dependency of this module. What a statement or a report does with
 * an encoded record is that module's own subject, and the cross-module case that would exercise the whole
 * create-then-render path is named in this group's report rather than authored into a sibling's tree.</p>
 *
 * <p>Alternatives Considered: asserting the domain against a hand-written list of characters instead of
 * against the encoder. Rejected because the list would be a second authority on the same question, and
 * the finding this class answers exists precisely because two authorities disagreed -- the request
 * accepted what the record could not carry. Deriving both sides from the encoder makes the two agree by
 * construction, and makes a future change to the encoder's charset fail here rather than in a nightly
 * run.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the methods below carry their own.</p>
 */
class PrintableTextSinkContractTest {

    /** The registered geometry of the 350-byte transaction record, from {@code app/cpy/CVTRA05Y.cpy}. */
    private static final CopybookLayout.RecordSpec TRAN = CopybookLayout.layout("TRAN");

    /**
     * The five text spans of that record an external producer authors, in declaration order.
     *
     * <p>Assumptions: these are exactly the five members {@link TransactionAddRequest} constrains with
     * {@link TransactionAddRequest#PRINTABLE_TEXT}, and no other text span of the record is included. The
     * identifier at offset zero and the type code are digit runs the request already constrains
     * numerically, the card number is resolved by the service rather than authored, the two timestamps
     * are formatted by the migration and the trailing span is padding -- so none of them can carry a
     * character a producer chose.</p>
     */
    private static final List<String> EXTERNALLY_AUTHORED_SPANS = List.of(
            "TRAN-SOURCE",
            "TRAN-DESC",
            "TRAN-MERCHANT-NAME",
            "TRAN-MERCHANT-CITY",
            "TRAN-MERCHANT-ZIP");

    /** The lowest code point the contract admits, the space. */
    private static final int FIRST_ADMITTED_CODE_POINT = 0x20;

    /** The highest code point the contract admits, the tilde. */
    private static final int LAST_ADMITTED_CODE_POINT = 0x7E;

    /** How many code points that span holds, ninety-five. */
    private static final int ADMITTED_CODE_POINT_COUNT = 95;

    /**
     * The highest code point the sweep below walks, chosen to cover three distinct refusal reasons.
     *
     * <p>Assumptions: {@code 0x2FF} reaches past the C1 controls at {@code 0x80} to {@code 0x9F}, past
     * the Latin-1 letters an accented merchant name would use, and into Latin Extended, so the sweep
     * covers a control that is also unrepresentable, a printable character that is unrepresentable, and
     * an ordinary letter of a script this code page never carried. A supplementary code point is
     * asserted separately, because it is the case a filter written over UTF-16 units rather than code
     * points would mishandle.</p>
     */
    private static final int SWEEP_LAST_CODE_POINT = 0x2FF;

    /** The compiled domain the request record publishes, so this class tests that constant and no copy. */
    private static final Pattern ADMITTED = Pattern.compile(TransactionAddRequest.PRINTABLE_TEXT);

    /**
     * A record buffer for one encode, at the declared length so every field offset lands inside it.
     *
     * @return a zero-filled buffer of the record's declared 350 bytes
     */
    private static byte[] emptyRecord() {
        return new byte[TRAN.reclen()];
    }

    /**
     * Reports whether the fixed-width encoder can carry one value in one span.
     *
     * <p>Assumptions: the verdict is taken from the encoder by CALLING it, and the exception it raises
     * for an unrepresentable value is converted to a boolean here rather than asserted at each call site.
     * That keeps the sweep below able to compare the encoder's verdict with the contract's on every code
     * point in one expression, which is the property this class exists to state.</p>
     *
     * @param field the span to write into
     * @param value the characters to write
     * @return {@code true} when the encoder wrote the value, {@code false} when it refused it as
     *     unrepresentable in its charset
     */
    private static boolean encodes(CopybookLayout.FieldSpec field, String value) {
        try {
            FixedWidthCodec.encodeField(value, field, emptyRecord());
            return true;
        } catch (FixedWidthCodec.FieldCodecException refused) {
            return false;
        }
    }

    /**
     * Confirms every character the contract admits round-trips through the encoder unchanged.
     *
     * <p>Assumptions: all five spans are exercised over all ninety-five code points rather than one span
     * standing in for the others, because the five have three different declared widths and the encoder
     * pads to the width of the span it is given. A domain proven on the hundred-character description
     * would say nothing about the ten-character source.</p>
     *
     * <p>Assumptions: the value written is the character between two others, so the decode assertion
     * distinguishes a character that survived from one the blank padding swallowed. The space is the case
     * that makes this necessary.</p>
     */
    @Test
    void everyAdmittedCharacterSurvivesTheFixedWidthEncoder() {
        for (String spanName : EXTERNALLY_AUTHORED_SPANS) {
            CopybookLayout.FieldSpec span = TRAN.field(spanName);
            for (int code = FIRST_ADMITTED_CODE_POINT; code <= LAST_ADMITTED_CODE_POINT; code++) {
                String value = "A" + Character.toString(code) + "B";
                byte[] record = emptyRecord();

                assertThatCode(() -> FixedWidthCodec.encodeField(value, span, record))
                        .as("%s must carry code point 0x%02X", spanName, code)
                        .doesNotThrowAnyException();
                assertThat(FixedWidthCodec.decodeField(record, span))
                        .as("%s must round-trip code point 0x%02X", spanName, code)
                        .asString()
                        .startsWith(value);
            }
        }
    }

    /**
     * Confirms no character the contract admits can end a record in a line-oriented sink.
     *
     * <p>Assumptions: the assertion is on the ENCODED byte rather than on the Java character, because the
     * plain-text statement and the 133-column report are read as bytes by whatever consumes them. A
     * domain that excluded the two terminators as characters but admitted something that encoded to one
     * would still open a record, and this is the assertion that closes that gap.</p>
     */
    @Test
    void noAdmittedCharacterEncodesToALineTerminator() {
        CopybookLayout.FieldSpec description = TRAN.field("TRAN-DESC");
        List<Integer> terminators = new ArrayList<>();

        for (int code = FIRST_ADMITTED_CODE_POINT; code <= LAST_ADMITTED_CODE_POINT; code++) {
            byte[] record = emptyRecord();
            FixedWidthCodec.encodeField(Character.toString(code), description, record);
            byte encoded = record[description.start()];
            if (encoded == (byte) '\r' || encoded == (byte) '\n') {
                terminators.add(code);
            }
        }

        assertThat(terminators)
                .as("no admitted code point may encode to CR or LF")
                .isEmpty();
        assertThat(LAST_ADMITTED_CODE_POINT - FIRST_ADMITTED_CODE_POINT + 1)
                .isEqualTo(ADMITTED_CODE_POINT_COUNT);
    }

    /**
     * Confirms the contract admits a character exactly when the encoder can carry it and it is inert.
     *
     * <p>Assumptions: this is the derivation stated as one property over a sweep of code points, and it
     * is the case that makes the domain non-arbitrary. A character is admitted if and only if the
     * fixed-width encoder can represent it AND it is not a control -- the first clause is the durable
     * poison hazard, the second is the record-injection hazard, and the domain is exactly their
     * intersection. Anything the encoder cannot carry is refused whatever it looks like; anything it can
     * carry but that steers a reader is refused too.</p>
     *
     * <p>Assumptions: {@link Character#isISOControl(int)} is the inertness test, and it is the right one
     * rather than a convenient one: it names the C0 span, the delete character and the C1 span, which
     * together are every code point that carries no glyph in a single-byte code page. Every one of them
     * either terminates a record, blanks a column, or renders as something no operator keyed.</p>
     */
    @Test
    void theAdmittedDomainIsExactlyWhatTheEncoderCarriesInertly() {
        CopybookLayout.FieldSpec description = TRAN.field("TRAN-DESC");

        for (int code = 0; code <= SWEEP_LAST_CODE_POINT; code++) {
            String value = Character.toString(code);
            boolean carriedInertly = encodes(description, value) && !Character.isISOControl(code);

            assertThat(ADMITTED.matcher(value).matches())
                    .as("code point 0x%02X: contract and encoder must agree", code)
                    .isEqualTo(carriedInertly);
        }

        String supplementary = Character.toString(0x1D49C);
        assertThat(ADMITTED.matcher(supplementary).matches()).isFalse();
        assertThat(encodes(description, supplementary)).isFalse();
    }

    /**
     * Confirms an unrepresentable character fails the ENCODER, which is the late failure now prevented.
     *
     * <p>Assumptions: this case documents the consequence of the omission rather than a new behaviour. A
     * value the encoder refuses is refused at encode time, which is inside a nightly batch or a reporting
     * run, over a row that was stored on some earlier turn -- so the refusal names a record and not a
     * request, and the operator who submitted it cannot be told and cannot correct it. That is why the
     * domain is enforced at the boundary rather than left to the sink: the sink's refusal is correct and
     * useless.</p>
     */
    @Test
    void anUnrepresentableCharacterFailsTheEncoderRatherThanTheProducer() {
        CopybookLayout.FieldSpec description = TRAN.field("TRAN-DESC");

        for (String unrepresentable : List.of("CAFÉ", "MÜNCHEN", "МОСКВА", Character.toString(0x1D49C))) {
            assertThat(ADMITTED.matcher(unrepresentable).matches())
                    .as("the contract must refuse %s at the boundary", unrepresentable)
                    .isFalse();
            assertThatThrownBy(
                    () -> FixedWidthCodec.encodeField(unrepresentable, description, emptyRecord()))
                    .as("the encoder must refuse %s far downstream", unrepresentable)
                    .isInstanceOf(FixedWidthCodec.FieldCodecException.class)
                    .hasMessageContaining("not representable");
        }
    }

    /**
     * Confirms a control character reaches the record unescaped, so the encoder cannot be the defence.
     *
     * <p>Assumptions: every one of these five encodes CLEANLY, and that is the point of the case. The
     * encoder's charset check catches the unrepresentable half of the hazard and is blind to this half:
     * the byte is written into the span exactly as submitted, and a fixed-width record has nowhere to put
     * an escape. A carriage return in a description therefore appears verbatim inside the 133-column
     * report line and inside the plain-text statement band, where it ends the line a reader is parsing.
     * The only place this can be stopped is the boundary, which is where the domain now stops it.</p>
     */
    @Test
    void aControlCharacterReachesTheRecordUnescaped() {
        CopybookLayout.FieldSpec description = TRAN.field("TRAN-DESC");

        for (int control : List.of(0x0D, 0x0A, 0x09, 0x00, 0x7F)) {
            String value = "A" + Character.toString(control) + "B";
            byte[] record = emptyRecord();

            assertThatCode(() -> FixedWidthCodec.encodeField(value, description, record))
                    .as("the encoder does not refuse code point 0x%02X", control)
                    .doesNotThrowAnyException();
            assertThat(record[description.start() + 1])
                    .as("code point 0x%02X lands in the record unescaped", control)
                    .isEqualTo((byte) control);
            assertThat(ADMITTED.matcher(value).matches())
                    .as("the contract must refuse code point 0x%02X", control)
                    .isFalse();
        }
    }
}
