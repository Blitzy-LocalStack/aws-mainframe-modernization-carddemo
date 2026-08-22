package com.carddemo.reporting.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * Where one card's statement sits inside the run-wide plain-text statement artifact.
 *
 * <h2>Purpose</h2>
 *
 * <p>A statement run publishes two artifacts for the WHOLE night rather than one pair per card, and the
 * statement response points a caller at them. Without this entry a caller asking for one card's
 * statement was pointed at a document covering the entire portfolio with no way to find its own
 * statement inside it. One entry per card names the first record the card's statement occupies and how
 * many records it occupies, so a consumer can seek to it.
 *
 * <h2>Assumptions: the position is a RECORD ORDINAL and not a byte offset</h2>
 *
 * <p>The plain-text artifact is a sequence of fixed-width records, so both forms locate a statement
 * equally well and the ordinal is the one that stays true. A byte offset depends on how the artifact is
 * transported and terminated, so publishing one would tie this contract to a framing detail of the
 * writer; a record ordinal is a property of the reference layout itself, which declares
 * {@code FD-STMTFILE-REC PIC X(80)} at L45 of {@code app/cbl/CBSTM03A.CBL}. Alternatives Considered: a
 * byte offset, rejected for that reason; and a line number counted from one, rejected because every
 * other position this reactor publishes counts from zero and one inconsistency in a numbering base is
 * the kind of defect that is only ever found by a customer.
 *
 * <h2>Assumptions: the encoded form is FIXED WIDTH, and that is load-bearing</h2>
 *
 * <p>The index artifact is written in ascending card-fingerprint order, one entry per record, at
 * {@value #ENCODED_WIDTH} characters each. Fixed width plus sorted order is what lets the read path
 * find one entry with a handful of ranged reads instead of downloading the whole index: entry
 * {@code i} begins at {@code i} times {@value #ON_OBJECT_STRIDE}, the stride declared below, so a
 * binary search over the object's own size reaches any card in logarithmic requests. A variable-width
 * or self-describing encoding -- JSON, say -- would be more familiar and would force every read to
 * fetch and parse the entire index, whose size grows with the portfolio.
 *
 * <h2>⚠️ Refactoring Rationale: the encoded width and the on-object stride are TWO numbers</h2>
 *
 * <p>This type declared one width and the read path used it for both questions, and the two questions
 * have different answers. {@link #ENCODED_WIDTH} is how many characters one entry's own content
 * occupies; {@link #ON_OBJECT_STRIDE} is how far apart two consecutive entries begin inside the
 * published object, and the writer that publishes the index terminates every record it appends. A
 * reader that used the content width as the stride therefore read entry {@code i} from a position
 * {@code i} bytes early: the object's byte length was never a whole multiple of the content width, so
 * the alignment guard refused every read of a normally published index, and had the guard been removed
 * instead the misaligned probe would have decoded a fingerprint spliced out of two cards and reported
 * ANOTHER CARDHOLDER'S position. Both numbers are declared, both are named at the site that needs
 * them, and neither stands in for the other.
 *
 * @param cardFingerprint the hexadecimal digest naming exactly one card, at
 *     {@value #FINGERPRINT_WIDTH} characters
 * @param firstRecord the zero-based ordinal of the first record this card's statement occupies in the
 *     plain-text artifact
 * @param recordCount how many consecutive records the statement occupies from that ordinal
 */
public record StatementIndexEntry(String cardFingerprint, long firstRecord, long recordCount) {

    /**
     * Width of the fingerprint component, being 64 characters.
     *
     * <p>Assumptions: 64 is the width of a hexadecimal SHA-256 digest, which is what
     * {@code reporting.v_card_xref} projects as the card fingerprint and what {@code CardXrefView}
     * maps. It is declared here rather than derived from a value at run time so that an entry of the
     * wrong width is refused when it is built, not when it is read back.</p>
     */
    public static final int FINGERPRINT_WIDTH = 64;

    /**
     * Width of each position component, being 12 digits.
     *
     * <p>Assumptions: twelve digits admits just under a million million records, which is far beyond
     * any plausible portfolio, and the point of the excess is that the width can never need changing --
     * a change of width invalidates every index artifact already written, because the read path derives
     * an entry's position from it.</p>
     */
    public static final int POSITION_WIDTH = 12;

    /**
     * Width of one encoded entry, being {@value #FINGERPRINT_WIDTH} plus twice
     * {@value #POSITION_WIDTH}.
     *
     * <p>Assumptions: this is the width of one entry's CONTENT and is what {@link #encode()} produces
     * and {@link #decode(byte[])} accepts. It is not the distance between two consecutive entries in
     * the published object; {@link #ON_OBJECT_STRIDE} is.</p>
     */
    public static final int ENCODED_WIDTH = FINGERPRINT_WIDTH + 2 * POSITION_WIDTH;

    /**
     * Bytes the record terminator occupies after every entry in the published object, being one.
     *
     * <p>Assumptions: one byte, because the artifact writer that publishes this index appends a single
     * line feed after each record it accepts, exactly as it does for the 80-character and 100-character
     * statement streams. That writer holds the terminator as a private constant, so the value is
     * declared here rather than imported: the writer already depends on this package for the statement
     * sink seam, and importing it back would close a package cycle. Alternatives Considered: giving the
     * index its own writer that emits no terminator at all, which would make the stride equal to the
     * content width; rejected because it would put a second, nearly identical multipart writer in the
     * sink package solely to omit one byte, and because every artifact of a run would then be framed by
     * one of two conventions with nothing in the object naming which.</p>
     */
    public static final int TERMINATOR_WIDTH = 1;

    /**
     * Distance between the starts of two consecutive entries in the published index object, being
     * {@value #ENCODED_WIDTH} plus {@value #TERMINATOR_WIDTH}.
     *
     * <p>Assumptions: this is the number a reader divides an object's byte length by to learn how many
     * entries it holds, and the number it multiplies an ordinal by to reach one. The terminator is
     * SKIPPED rather than decoded: a ranged read for entry {@code i} spans
     * {@value #ENCODED_WIDTH} bytes from {@code i} times this stride, so the byte the writer appended
     * never reaches {@link #decode(byte[])} and that method can stay strict about its own width.</p>
     */
    public static final int ON_OBJECT_STRIDE = ENCODED_WIDTH + TERMINATOR_WIDTH;

    /**
     * Validates the fingerprint's width and refuses a negative position.
     *
     * @param cardFingerprint the hexadecimal digest naming exactly one card; must be
     *     {@value #FINGERPRINT_WIDTH} characters
     * @param firstRecord the zero-based ordinal of the first record of this card's statement; must not
     *     be negative
     * @param recordCount how many records the statement occupies; must not be negative
     * @throws NullPointerException if {@code cardFingerprint} is {@code null}
     * @throws IllegalArgumentException if the fingerprint is not of the declared width, if either
     *     position is negative, or if either position needs more than {@value #POSITION_WIDTH} digits
     */
    public StatementIndexEntry {
        Objects.requireNonNull(cardFingerprint, "cardFingerprint must not be null");
        if (cardFingerprint.length() != FINGERPRINT_WIDTH) {
            throw new IllegalArgumentException("cardFingerprint must be " + FINGERPRINT_WIDTH
                    + " characters; a shorter value names a card only ambiguously");
        }
        requirePosition(firstRecord, "firstRecord");
        requirePosition(recordCount, "recordCount");
    }

    /**
     * Encodes this entry as one fixed-width record of the index artifact.
     *
     * <p>Assumptions: positions are ZERO-PADDED on the left rather than blank-padded, so the encoded
     * form sorts and compares the same way the numbers do, and a reader that treats an entry as text
     * cannot mistake a padded blank for a missing digit. This is the same convention the reference
     * applies to its own numeric pictures, which are zero-filled by definition.</p>
     *
     * @return the encoded entry, exactly {@value #ENCODED_WIDTH} bytes, never {@code null}
     */
    public byte[] encode() {
        return (cardFingerprint
                + String.format(Locale.ROOT, "%0" + POSITION_WIDTH + "d", firstRecord)
                + String.format(Locale.ROOT, "%0" + POSITION_WIDTH + "d", recordCount))
                .getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Decodes one fixed-width record of the index artifact.
     *
     * <p>Assumptions: the record is accepted at exactly the declared width and a longer one is refused
     * rather than truncated. A ranged read that returned more bytes than one entry would mean the read
     * path and the artifact disagree about the width, and continuing from a truncated prefix would
     * return a position belonging to a different card.</p>
     *
     * <p>⚠️ Assumptions: the argument carries the entry's CONTENT ALONE and never a trailing record
     * terminator. This paragraph previously said a terminator "may" be present, which the width test
     * below has always contradicted -- a record of {@value #ENCODED_WIDTH} plus one is refused, and a
     * terminated record is exactly that length. The accurate statement is that the caller strips the
     * terminator by construction of the byte range it asks for: a read for one entry spans
     * {@value #ENCODED_WIDTH} bytes from a multiple of {@link #ON_OBJECT_STRIDE}, so the terminator
     * falls outside the range and never arrives here. Refactoring Rationale: the contradiction is
     * resolved in favour of the implementation rather than the prose, because a lenient reader that
     * trimmed a trailing byte would also silently accept a genuinely misaligned probe -- the one
     * failure whose consequence is another cardholder's position.</p>
     *
     * @param record the encoded entry's content, exactly {@value #ENCODED_WIDTH} bytes and carrying no
     *     record terminator; must not be {@code null}
     * @return the decoded entry, never {@code null}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record is not of the declared width, or if either
     *     position is not a run of digits
     */
    public static StatementIndexEntry decode(byte[] record) {
        Objects.requireNonNull(record, "record must not be null");
        String text = new String(record, StandardCharsets.US_ASCII);
        if (text.length() != ENCODED_WIDTH) {
            throw new IllegalArgumentException("an index entry must be " + ENCODED_WIDTH
                    + " characters; the artifact and this reader disagree about the width");
        }
        return new StatementIndexEntry(
                text.substring(0, FINGERPRINT_WIDTH),
                parsePosition(text, FINGERPRINT_WIDTH),
                parsePosition(text, FINGERPRINT_WIDTH + POSITION_WIDTH));
    }

    /**
     * Renders the entry for a diagnostic.
     *
     * <p>Assumptions: the fingerprint is WITHHELD and the two positions are printed. A fingerprint
     * names exactly one card, so it is an identifier of a cardholder by another name and the
     * sensitive-data contract in {@code docs/architecture/observability.md} treats it as one; the two
     * positions are ordinals into a run-wide document and identify nobody.</p>
     *
     * @return the rendering, never {@code null}
     */
    @Override
    public String toString() {
        return "StatementIndexEntry[cardFingerprint=REDACTED"
                + ", firstRecord=" + firstRecord
                + ", recordCount=" + recordCount + ']';
    }

    /**
     * Refuses a position that is negative or wider than the encoded form can carry.
     *
     * @param position the position to check
     * @param component the component's name, for the refusal message
     * @throws IllegalArgumentException if the position is negative or needs more than
     *     {@value #POSITION_WIDTH} digits
     */
    private static void requirePosition(long position, String component) {
        if (position < 0) {
            throw new IllegalArgumentException(component + " is an ordinal and cannot be negative");
        }
        if (Long.toString(position).length() > POSITION_WIDTH) {
            throw new IllegalArgumentException(component + " needs more than " + POSITION_WIDTH
                    + " digits, which the index record cannot carry");
        }
    }

    /**
     * Reads one zero-padded position out of an encoded entry.
     *
     * @param text the encoded entry
     * @param offset where the position begins
     * @return the parsed position
     * @throws IllegalArgumentException if the component is not a run of digits
     */
    private static long parsePosition(String text, int offset) {
        String digits = text.substring(offset, offset + POSITION_WIDTH);
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(
                    "an index position must be a run of digits, zero-padded to " + POSITION_WIDTH);
        }
    }
}
