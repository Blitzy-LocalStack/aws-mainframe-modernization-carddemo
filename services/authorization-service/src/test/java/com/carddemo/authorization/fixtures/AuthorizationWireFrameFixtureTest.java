package com.carddemo.authorization.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.codec.CsvAuthCodec.AuthReply;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Consumes the two TRANSPORT-shaped wire fixtures and asserts the contract each carries.
 *
 * <p>Purpose: a message reaches this service inside a buffer, and the buffer is not the payload. The
 * two fixtures read here are the only committed artifacts that hold that distinction as bytes --
 * {@code auth-reply-transmitted-64.bin} is the 63-byte reply payload followed by one non-payload byte,
 * and {@code auth-request-buffer500.bin} is the 170-byte request payload inside the 500-byte buffer the
 * reference program declares. Neither file's contract is the payload contract, which the encode oracles
 * already own; theirs is what a receiver must do with the bytes AROUND the payload.
 *
 * <h2>Refactoring Rationale: why these two files now have a consumer</h2>
 *
 * <p>Both were committed with no executable reader. Their bytes could therefore have changed -- the
 * trailing byte of the frame, the width of the blank tail, the payload's position inside the buffer --
 * with the entire module staying green, and a fixture nothing reads documents nothing. The closure
 * assertion that should have caught the omission compared the enrolled inventory against a hard-coded
 * count rather than against the directory, so it did not; that assertion now enumerates the real
 * directory, and these cases are what make the two files load-bearing rather than merely enrolled.
 *
 * <h2>Refactoring Rationale: the 64-byte frame and the recorded decision it appeared to contradict</h2>
 *
 * <p>{@code AuthorizationFixtureContractTest} synthesises a 64-byte frame in-test, by copying the
 * 63-byte oracle and appending a blank, and its rationale for doing so is explicit: the frame and the
 * oracle "cannot drift" when one is derived from the other. Committing a second 64-byte artifact
 * appears to reverse that decision, and the appearance is what the review flagged.
 *
 * <p>Assumptions: the two are not alternatives, and both are kept for reasons that do not overlap. The
 * synthesised frame proves the codec TOLERATES a trailing pad byte, and deriving it is right there
 * because the property under test is the codec's, not the file's. This fixture proves something the
 * synthesised frame cannot: that byte 63 of the frame a real producer transmits is {@code 0x20} and not
 * a line terminator. A test that appends its own byte can only assert the byte it chose, so it can
 * never fail when the transmitted convention changes -- and the three reply artifacts in this directory
 * are 63, 64 and 64 bytes long, differing ONLY at byte 63, which makes that byte the entire content of
 * the distinction. The drift objection is answered by asserting bytes 0 to 62 of this file against the
 * oracle, so the two files cannot disagree about the payload even though only one of them holds it
 * alone.
 *
 * <h2>Assumptions: the reference declarations these widths come from</h2>
 *
 * <p>The reply layout is {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy} lines 19 to 24 --
 * six fields whose declared widths sum to 57, which with five interior delimiters and the trailing one
 * the reference emits makes 63. The request layout is {@code cpy/CCPAURQY.cpy} lines 19 to 36 --
 * eighteen fields summing to 153, which with seventeen interior delimiters makes 170. Both figures are
 * read from {@link CsvAuthCodec} rather than written here, so this class cannot disagree with the codec
 * it verifies.
 *
 * <p>Assumptions: the 500-byte buffer is not arbitrary. {@code cbl/COPAUA0C.cbl} declares its message
 * area at that width and passes the received length separately, which is why
 * {@link CsvAuthCodec#decodeRequest(byte[], int)} takes the length as an argument instead of using the
 * buffer's own. A decoder that inferred the length from the array would read 330 blanks into the last
 * field of every message.
 */
class AuthorizationWireFrameFixtureTest {

    /** The classpath directory every fixture in this module lives under. */
    private static final String FIXTURE_ROOT = "fixtures/";

    /** The transmitted reply frame: the 63-byte payload plus one non-payload byte. */
    private static final String TRANSMITTED_FRAME = "auth-reply-transmitted-64.bin";

    /** The terminator-free 63-byte reply payload this frame's first 63 bytes must equal. */
    private static final String REPLY_ORACLE = "auth-reply-encode-oracle-63.bin";

    /** The 500-byte receive buffer carrying a 170-byte request payload and a blank tail. */
    private static final String REQUEST_BUFFER = "auth-request-buffer500.bin";

    /** The terminator-free 170-byte request payload this buffer's first 170 bytes must equal. */
    private static final String REQUEST_ORACLE = "auth-request-encode-oracle-170.bin";

    /** Width of the message area {@code COPAUA0C} declares, which the buffer fixture reproduces. */
    private static final int RECEIVE_BUFFER_WIDTH = 500;

    /** The byte a transmitted frame carries after the reply payload. */
    private static final byte TRANSMITTED_PAD = (byte) 0x20;

    /** Line feed, asserted absent because a frame carrying one is a different artifact. */
    private static final byte LINE_FEED = (byte) 0x0A;

    /** Carriage return, asserted absent for the same reason as the line feed. */
    private static final byte CARRIAGE_RETURN = (byte) 0x0D;

    /** The comma the six reply fields are joined with, asserted not to be the trailing byte. */
    private static final byte DELIMITER = (byte) 0x2C;

    /**
     * Reads one fixture resource as raw bytes.
     *
     * @param name the resource name below the fixture root
     * @return the resource's bytes, exactly as stored
     * @throws AssertionError if the resource is not on the test classpath
     * @throws UncheckedIOException if the resource cannot be read once opened
     */
    private static byte[] bytesOf(String name) {
        try (InputStream stream = AuthorizationWireFrameFixtureTest.class.getClassLoader()
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
     * Confirms the frame is the reply payload followed by exactly one non-payload byte.
     *
     * <p>Assumptions: the payload span is compared against the 63-byte oracle rather than against a
     * literal, which is what ties the two files together. Comparing the frame's fields one by one
     * would pass for a frame whose two equal-width identifier fields had swapped content, and
     * comparing it against a literal payload would put a third copy of the reply wire in this
     * directory.</p>
     */
    @Test
    @DisplayName("the transmitted frame is the 63-byte payload plus one byte, byte for byte")
    void theTransmittedFrameIsThePayloadPlusOneByte() {
        byte[] frame = bytesOf(TRANSMITTED_FRAME);
        byte[] payload = bytesOf(REPLY_ORACLE);

        assertThat(payload).hasSize(CsvAuthCodec.REPLY_WIRE_LENGTH);
        assertThat(frame)
                .as("the frame is the payload width plus exactly one transport byte")
                .hasSize(CsvAuthCodec.REPLY_WIRE_LENGTH + 1);
        assertThat(Arrays.copyOf(frame, CsvAuthCodec.REPLY_WIRE_LENGTH))
                .as("bytes 0 to 62 of the frame are the payload the encoder emits")
                .isEqualTo(payload);
    }

    /**
     * Confirms byte 63 is the transport pad and is none of the bytes it could be confused with.
     *
     * <p>Assumptions: the trailing byte is asserted INDEPENDENTLY of the payload comparison above,
     * and that separation is the point of this case. The payload span and the trailing byte are two
     * different contracts -- one belongs to the encoder, the other to the transport -- and a single
     * assertion over all 64 bytes would report a changed pad byte as a changed payload.</p>
     *
     * <p>Assumptions: the byte is asserted to be neither a line feed nor a carriage return nor the
     * delimiter nor a digit. The sibling {@code auth-reply-approved-wire63.csv} is also 64 bytes and
     * ends with a line feed, so LENGTH cannot tell the two files apart and only this byte can. A
     * digit is excluded because a payload widened by one character would also produce a 64-byte file,
     * and the delimiter is excluded because a seventh empty field would too.</p>
     */
    @Test
    @DisplayName("byte 63 of the frame is the transport pad and not a terminator, digit or delimiter")
    void byteSixtyThreeIsTheTransportPadAlone() {
        byte trailing = bytesOf(TRANSMITTED_FRAME)[CsvAuthCodec.REPLY_WIRE_LENGTH];

        assertThat(trailing).isEqualTo(TRANSMITTED_PAD);
        assertThat(trailing).isNotEqualTo(LINE_FEED);
        assertThat(trailing).isNotEqualTo(CARRIAGE_RETURN);
        assertThat(trailing).isNotEqualTo(DELIMITER);
        assertThat(trailing < (byte) 0x30 || trailing > (byte) 0x39)
                .as("a digit here would mean the payload had widened rather than been padded")
                .isTrue();

        // WHY : Assumptions: the payload span is asserted free of every terminator byte as well, so
        //       the single trailing pad is proved to be the ONLY non-payload byte in the file rather
        //       than merely the last one.
        byte[] payloadSpan = Arrays.copyOf(bytesOf(TRANSMITTED_FRAME), CsvAuthCodec.REPLY_WIRE_LENGTH);
        assertThat(payloadSpan)
                .doesNotContain(LINE_FEED)
                .doesNotContain(CARRIAGE_RETURN)
                .doesNotContain((byte) 0x00);
    }

    /**
     * Confirms the real decoder reads the frame and re-emits at the declared payload width.
     *
     * <p>Assumptions: the frame is handed to the decoder at its OWN length, 64, which is the tolerance
     * being tested. Passing 63 instead would decode the payload and prove nothing about the frame,
     * because the extra byte would simply never be looked at.</p>
     *
     * <p>Assumptions: the re-emission is asserted to be 63 bytes and byte-identical to the oracle. The
     * codec is interoperable in both directions without being byte-idempotent over every variant a
     * producer might send, and this is the case that fixes which of the two it is: a codec that echoed
     * its input width would put a pad byte into the outbox row and then onto the reply queue.</p>
     */
    @Test
    @DisplayName("the decoder accepts the 64-byte frame and re-emits the 63-byte payload")
    void theDecoderAcceptsTheFrameAndReEmitsThePayload() {
        byte[] frame = bytesOf(TRANSMITTED_FRAME);

        AuthReply decoded = CsvAuthCodec.decodeReply(frame, frame.length);

        assertThat(decoded.cardNum()).isEqualTo("4000123456789010");
        assertThat(decoded.transactionId()).isEqualTo("TXN000000000100");
        assertThat(decoded.authIdCode()).isEqualTo("A00001");
        assertThat(decoded.authRespCode()).isEqualTo("00");
        assertThat(decoded.authRespReason()).isEqualTo("0000");
        assertThat(decoded.approvedAmount().amount()).isEqualByComparingTo("250.00");

        assertThat(CsvAuthCodec.encodeReplyBytes(decoded))
                .as("a decode of the frame re-encodes to the payload, never to the frame")
                .hasSize(CsvAuthCodec.REPLY_WIRE_LENGTH)
                .isEqualTo(bytesOf(REPLY_ORACLE));
    }

    /**
     * Confirms the tolerance is for a PAD byte and not for any trailing byte at all.
     *
     * @param trailing the byte substituted at position 63 of the frame, given as its numeric value
     *
     * <p>Assumptions: the substitution is made on a copy of the committed frame, so the vector differs
     * from the real transport shape in exactly one byte and a failure isolates to that byte. Building
     * a 64-byte buffer from scratch here would risk differing in the payload as well.</p>
     *
     * <p>Assumptions: each of these three bytes is a control character, and the codec refuses a payload
     * containing one. That refusal is what makes the accepted blank a deliberate tolerance rather than
     * an unchecked extra byte: a decoder that simply ignored position 63 would accept all three, and a
     * line feed absorbed there is length-indistinguishable from the committed frame.</p>
     */
    @ParameterizedTest
    @ValueSource(ints = {0x0A, 0x0D, 0x00})
    @DisplayName("a control byte in the frame's trailing position is refused, not ignored")
    void aControlByteInTheTrailingPositionIsRefused(int trailing) {
        byte[] frame = bytesOf(TRANSMITTED_FRAME);
        frame[CsvAuthCodec.REPLY_WIRE_LENGTH] = (byte) trailing;

        assertThatThrownBy(() -> CsvAuthCodec.decodeReply(frame, frame.length))
                .isInstanceOf(CsvAuthCodec.AuthMessageFormatException.class);
    }

    /**
     * Confirms the buffer holds the request payload at offset zero and blanks for the rest.
     *
     * <p>Assumptions: the payload span is compared against the 170-byte encode oracle for the same
     * reason the reply frame is compared against its own -- it ties the two files together without
     * putting a second copy of the request wire in this directory.</p>
     *
     * <p>Assumptions: the tail is asserted to be blanks EXHAUSTIVELY rather than at its endpoints. A
     * buffer whose tail held a stale message from a previous receive would still start with blanks and
     * end with blanks, and that is precisely the residue an unclear buffer leaves behind.</p>
     */
    @Test
    @DisplayName("the 500-byte buffer carries the 170-byte payload then a blank tail")
    void theBufferCarriesThePayloadThenABlankTail() {
        byte[] buffer = bytesOf(REQUEST_BUFFER);

        assertThat(buffer)
                .as("the buffer is the width the reference message area declares")
                .hasSize(RECEIVE_BUFFER_WIDTH);
        assertThat(Arrays.copyOf(buffer, CsvAuthCodec.REQUEST_WIRE_LENGTH))
                .as("the first 170 bytes are the payload the encoder emits")
                .isEqualTo(bytesOf(REQUEST_ORACLE));

        byte[] tail = Arrays.copyOfRange(buffer, CsvAuthCodec.REQUEST_WIRE_LENGTH, buffer.length);
        assertThat(tail)
                .as("the tail is exactly the buffer width less the payload width")
                .hasSize(RECEIVE_BUFFER_WIDTH - CsvAuthCodec.REQUEST_WIRE_LENGTH);
        for (int offset = 0; offset < tail.length; offset++) {
            assertThat(tail[offset])
                    .as("buffer byte %d, in the tail beyond the payload",
                            CsvAuthCodec.REQUEST_WIRE_LENGTH + offset)
                    .isEqualTo(TRANSMITTED_PAD);
        }
    }

    /**
     * Confirms the payload decodes at its declared length and re-emits without the buffer's tail.
     *
     * <p>Assumptions: the decisive assertion is the RE-EMISSION width, not the decode. A decoder that
     * carried the tail into the request would produce members that still read correctly -- the trailing
     * blanks land in the final field, whose value is unaffected by them -- and the damage would appear
     * only when that request was encoded onward, at which point a 500-byte payload would reach a queue
     * whose contract is 170. Asserting the re-emitted bytes against the oracle is what closes that.</p>
     */
    @Test
    @DisplayName("the payload decodes at its declared length and re-emits at 170 bytes")
    void thePayloadDecodesAtItsDeclaredLength() {
        byte[] buffer = bytesOf(REQUEST_BUFFER);

        AuthRequest decoded =
                CsvAuthCodec.decodeRequest(buffer, CsvAuthCodec.REQUEST_WIRE_LENGTH);

        assertThat(decoded.cardNum()).isEqualTo("4000123456789010");
        assertThat(decoded.transactionId()).isEqualTo("TXN000000000100");
        assertThat(decoded.transactionAmount().amount()).isEqualByComparingTo("250.00");
        assertThat(CsvAuthCodec.encodeRequestBytes(decoded))
                .as("the payload re-emits at its declared width, without the buffer's tail")
                .hasSize(CsvAuthCodec.REQUEST_WIRE_LENGTH)
                .isEqualTo(bytesOf(REQUEST_ORACLE));
    }

    /**
     * Confirms a blank-padded buffer length is TOLERATED and still re-emits at the declared width.
     *
     * <p>Assumptions: this is a measured tolerance and is recorded as one rather than asserted as a
     * refusal. Handing the decoder the whole 500 bytes does NOT fail: the 330 blanks fall inside the
     * eighteenth field, and the codec strips trailing pad from a text field, so every member decodes to
     * the same value the 170-byte decode produced. A test written to expect a refusal here would have
     * been asserting a behaviour the codec does not have.</p>
     *
     * <p>Trade-offs: the tolerance is the right behaviour for this transport and is worth stating as a
     * decision rather than an accident. A receiver reading a fixed-width message area cannot always
     * distinguish the message from the area, so refusing a blank-padded buffer would reject a message
     * whose content is intact. What must NOT be tolerated is the tail travelling onward, which the
     * re-emission assertion below fixes -- the padding is absorbed on the way in and is never
     * reproduced on the way out.</p>
     */
    @Test
    @DisplayName("the whole blank-padded buffer decodes to the same request and still re-emits at 170")
    void theBlankPaddedBufferIsToleratedAndNeverReEmitted() {
        byte[] buffer = bytesOf(REQUEST_BUFFER);

        AuthRequest atPayloadLength =
                CsvAuthCodec.decodeRequest(buffer, CsvAuthCodec.REQUEST_WIRE_LENGTH);
        AuthRequest atBufferLength = CsvAuthCodec.decodeRequest(buffer, buffer.length);

        assertThat(atBufferLength.transactionId()).isEqualTo(atPayloadLength.transactionId());
        assertThat(atBufferLength.cardNum()).isEqualTo(atPayloadLength.cardNum());
        assertThat(atBufferLength.transactionAmount().amount())
                .isEqualByComparingTo(atPayloadLength.transactionAmount().amount());
        assertThat(CsvAuthCodec.encodeRequestBytes(atBufferLength))
                .as("the tail is absorbed on the way in and is never reproduced on the way out")
                .hasSize(CsvAuthCodec.REQUEST_WIRE_LENGTH)
                .isEqualTo(bytesOf(REQUEST_ORACLE));
    }

    /**
     * Confirms the length argument is load-bearing, by supplying two lengths that must be refused.
     *
     * <p>Assumptions: since a blank-padded length is tolerated, the tolerance alone cannot show that
     * the argument is consulted at all -- a decoder ignoring it entirely would behave identically on
     * that vector. These two lengths are the ones that separate the two implementations. A length that
     * CUTS the payload leaves the message short of its eighteen fields, and a length BEYOND the buffer
     * is a caller error the decoder must not read past the array to satisfy.</p>
     *
     * <p>Assumptions: the over-long length is one byte past the buffer rather than a large number, so
     * the vector fails for the boundary rather than for a magnitude that would also exceed the
     * contract's own maximum payload width and so be refused for a different reason.</p>
     */
    @Test
    @DisplayName("a truncating length and an over-long length are both refused")
    void aTruncatingLengthAndAnOverLongLengthAreRefused() {
        byte[] buffer = bytesOf(REQUEST_BUFFER);

        assertThatThrownBy(() -> CsvAuthCodec.decodeRequest(buffer, 100))
                .as("a length that cuts the payload leaves it short of its eighteen fields")
                .isInstanceOf(CsvAuthCodec.AuthMessageFormatException.class);
        assertThatThrownBy(() -> CsvAuthCodec.decodeRequest(buffer, buffer.length + 1))
                .as("a length beyond the buffer must be refused rather than read past the array")
                .isInstanceOf(CsvAuthCodec.AuthMessageFormatException.class);
    }

    /**
     * Confirms the payload span carries no terminator, so the blank tail is the only padding.
     *
     * <p>Assumptions: this is asserted separately from the tail check because the two failures are
     * different. A terminator inside the payload would mean the fixture had been written through a
     * text writer, which would also have re-encoded the money token; a terminator in the tail would
     * mean the buffer had been assembled by concatenating lines. Reporting them together would leave a
     * reader unable to tell which had happened.</p>
     */
    @Test
    @DisplayName("neither the payload nor the tail of the buffer carries a terminator byte")
    void neitherThePayloadNorTheTailCarriesATerminator() {
        byte[] buffer = bytesOf(REQUEST_BUFFER);

        assertThat(buffer)
                .doesNotContain(LINE_FEED)
                .doesNotContain(CARRIAGE_RETURN)
                .doesNotContain((byte) 0x00);

        String payload = new String(buffer, 0, CsvAuthCodec.REQUEST_WIRE_LENGTH,
                StandardCharsets.US_ASCII);
        assertThat(payload.split(",", -1))
                .as("the payload holds the eighteen declared fields and no more")
                .hasSize(CsvAuthCodec.REQUEST_FIELD_COUNT);
    }
}
