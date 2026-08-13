package com.carddemo.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the two value types that make a run-wide statement artifact addressable per card.
 *
 * <p>Purpose: the statement run publishes one plain-text artifact and one markup artifact for the whole
 * night, and the per-card response points a caller at them. The index is what tells a caller which
 * records of that artifact are its own statement, so its encoding is a published contract in everything
 * but name: the read path derives an entry's position from the declared width, so a change of width or a
 * change of padding silently repoints every position already published. Every case here exists to make
 * such a change fail.
 *
 * <p>Assumptions: the encoded form is asserted by LENGTH and by CONTENT rather than only round-tripped.
 * A round trip passes against any encoding whose reader and writer agree, including one whose width
 * drifted, and it is exactly that agreement-with-itself that would hide a break with artifacts already
 * stored.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the methods below carry their own where they have any.
 */
class StatementRunIndexTest {

    /** A fingerprint at the declared width, standing for one card. */
    private static final String FINGERPRINT = "a".repeat(63) + "1";

    /**
     * The encoded index record.
     */
    @Nested
    @DisplayName("the index entry")
    class Entry {

        /**
         * Asserts that an entry encodes to the declared width and decodes back to itself.
         */
        @Test
        @DisplayName("encodes to the declared width and decodes back unchanged")
        void encodesToTheDeclaredWidth() {
            StatementIndexEntry entry = new StatementIndexEntry(FINGERPRINT, 240L, 27L);

            byte[] encoded = entry.encode();

            assertThat(encoded).hasSize(StatementIndexEntry.ENCODED_WIDTH);
            assertThat(StatementIndexEntry.decode(encoded)).isEqualTo(entry);
        }

        // WHY : Assumptions: the padding is asserted as CONTENT and not merely as a width, because
        //       blank padding would produce a record of the same length that sorts differently from the
        //       numbers it encodes -- and the read path bisects over sorted records, so a padding change
        //       is a correctness change rather than a cosmetic one.
        /**
         * Asserts that positions are zero-padded on the left at their declared width.
         */
        @Test
        @DisplayName("pads each position with leading zeroes")
        void padsEachPositionWithZeroes() {
            String encoded = new String(new StatementIndexEntry(FINGERPRINT, 7L, 3L).encode(),
                    StandardCharsets.US_ASCII);

            assertThat(encoded).startsWith(FINGERPRINT);
            assertThat(encoded.substring(StatementIndexEntry.FINGERPRINT_WIDTH))
                    .isEqualTo("000000000007" + "000000000003");
        }

        /**
         * Asserts that a fingerprint of the wrong width is refused when the entry is built.
         */
        @Test
        @DisplayName("refuses a fingerprint of the wrong width")
        void refusesAFingerprintOfTheWrongWidth() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementIndexEntry("abc", 0L, 1L))
                    .withMessageContaining("characters");
        }

        /**
         * Asserts that a negative ordinal is refused.
         */
        @Test
        @DisplayName("refuses a negative ordinal")
        void refusesANegativeOrdinal() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementIndexEntry(FINGERPRINT, -1L, 1L))
                    .withMessageContaining("firstRecord");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementIndexEntry(FINGERPRINT, 0L, -1L))
                    .withMessageContaining("recordCount");
        }

        /**
         * Asserts that a position too wide for the record is refused rather than truncated.
         */
        @Test
        @DisplayName("refuses a position wider than the record can carry")
        void refusesAPositionTooWide() {
            long tooWide = 1_000_000_000_000L;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementIndexEntry(FINGERPRINT, tooWide, 1L))
                    .withMessageContaining("digits");
        }

        // WHY : Assumptions: a record of the WRONG LENGTH is refused rather than decoded from its
        //       prefix. A ranged read that returned more or fewer bytes than one entry means the
        //       artifact and this reader disagree about the width, and decoding the prefix anyway would
        //       return a position belonging to a different card -- which is the one failure mode a
        //       caller could not detect.
        /**
         * Asserts that a record of the wrong length is refused rather than decoded from its prefix.
         */
        @Test
        @DisplayName("refuses a record of the wrong length")
        void refusesARecordOfTheWrongLength() {
            byte[] tooLong = new byte[StatementIndexEntry.ENCODED_WIDTH + 1];

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> StatementIndexEntry.decode(tooLong))
                    .withMessageContaining("disagree about the width");
        }

        /**
         * Asserts that a position that is not a run of digits is refused.
         */
        @Test
        @DisplayName("refuses a position that is not a run of digits")
        void refusesANonNumericPosition() {
            byte[] malformed = (FINGERPRINT + "00000000000x" + "000000000003")
                    .getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> StatementIndexEntry.decode(malformed))
                    .withMessageContaining("run of digits");
        }

        // WHY : Assumptions: the fingerprint is asserted ABSENT from the rendering, and the assertion
        //       also checks a TAIL of it. A rendering that abbreviated the fingerprint would satisfy a
        //       whole-value assertion while still narrowing a card, which is the same reason every other
        //       rendering in this reactor withholds rather than shortens.
        /**
         * Asserts that the rendering withholds the fingerprint and keeps the positions.
         */
        @Test
        @DisplayName("renders the positions and withholds the fingerprint")
        void rendersThePositionsAndWithholdsTheFingerprint() {
            String rendered = new StatementIndexEntry(FINGERPRINT, 240L, 27L).toString();

            assertThat(rendered)
                    .doesNotContain(FINGERPRINT)
                    .doesNotContain(FINGERPRINT.substring(FINGERPRINT.length() - 8))
                    .contains("REDACTED")
                    .contains("firstRecord=240")
                    .contains("recordCount=27");
        }
    }

    /**
     * What one run reports about itself.
     */
    @Nested
    @DisplayName("the run outcome")
    class Outcome {

        /**
         * Asserts that a count agreeing with the index is accepted.
         */
        @Test
        @DisplayName("accepts a count that agrees with the index")
        void acceptsAnAgreeingCount() {
            StatementRunOutcome outcome = new StatementRunOutcome(1,
                    List.of(new StatementIndexEntry(FINGERPRINT, 0L, 9L)));

            assertThat(outcome.statementsProduced()).isEqualTo(1);
            assertThat(outcome.index()).hasSize(1);
        }

        // WHY : Assumptions: the disagreement is refused rather than reconciled, because the two
        //       components are two views of one run and a mismatch means one was built wrongly. The
        //       consequence of accepting it is an index missing a card, which the read path would report
        //       as "your statement is not in this artifact" for a statement that is -- a wrong answer
        //       rather than an error.
        /**
         * Asserts that a count disagreeing with the index is refused.
         */
        @Test
        @DisplayName("refuses a count that disagrees with the index")
        void refusesADisagreeingCount() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementRunOutcome(2,
                            List.of(new StatementIndexEntry(FINGERPRINT, 0L, 9L))))
                    .withMessageContaining("unreachable");
        }

        /**
         * Asserts that an empty run is accepted, being a night that produced nothing.
         */
        @Test
        @DisplayName("accepts an empty run")
        void acceptsAnEmptyRun() {
            assertThat(new StatementRunOutcome(0, List.of()).index()).isEmpty();
        }

        /**
         * Asserts that the rendering reduces the index to its size rather than printing its entries.
         */
        @Test
        @DisplayName("renders the index as a count of entries")
        void rendersTheIndexAsACount() {
            String rendered = new StatementRunOutcome(1,
                    List.of(new StatementIndexEntry(FINGERPRINT, 0L, 9L))).toString();

            assertThat(rendered)
                    .doesNotContain(FINGERPRINT)
                    .contains("index=1 entries");
        }
    }
}
