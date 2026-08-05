package com.carddemo.common.codec;

import com.carddemo.common.codec.CopybookLayout.FieldSpec;
import com.carddemo.common.codec.CopybookLayout.Kind;
import com.carddemo.common.codec.CopybookLayout.RecordSpec;
import com.carddemo.common.codec.ZonedDecimalCodec.FieldContext;
import com.carddemo.common.codec.ZonedDecimalCodec.ZonedDecimalException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ZonedDecimalCodec} to the exact trailing-sign bytes the reference corpus holds, to the
 * complete twenty-character overpunch table, and to every refusal the codec is contracted to raise.
 *
 * <p>This class is the sole owner of zoned-display trailing-sign behaviour in the codec package. It
 * asserts BYTES rather than round-trip symmetry alone, because the defect this codec exists to
 * prevent is symmetric: a decoder that reads the final byte as a bare sign marker and an encoder that
 * writes it as one agree with each other perfectly while being out by a factor of ten against the
 * baseline. The literal spans below are transcribed from immutable reference artifacts and each one
 * is cited beside the expectation it fixes, so a reader can re-derive any expectation without leaving
 * this file.</p>
 *
 * <p>Two artifacts supply every live vector. The twelve-byte form comes from the single 300-byte
 * record of {@code tests/fixtures/posting/happy_path/acctdata.txt}, whose leading 48 characters are
 * reproduced as a constant below. The eleven-byte form comes from the five 350-byte records of
 * {@code tests/fixtures/export/happy_path/trandata.txt}, each of which carries its amount at
 * zero-based offset 132. The pictures those offsets belong to are declared at
 * {@code app/cpy/CVACT01Y.cpy} lines 5 to 9 and {@code app/cpy/CVTRA05Y.cpy} lines 10 and 11.</p>
 *
 * <p>Assumptions: no test here reads a file, a clock, an environment variable or a network resource.
 * Every vector is an inline literal, which is what lets this class run with no database, no emulator
 * and no COBOL compiler present -- and, more importantly, what stops a test passing because a fixture
 * happened to change underneath it. A fixture read at run time would make the expectation whatever
 * the file currently holds; a literal makes the expectation reviewable.</p>
 *
 * <p>Assumptions: the compiler setting named {@code -fsign=EBCDIC} names a SIGN CONVENTION and not a
 * character encoding, and nothing in this module converts a character set. The two names genuinely do
 * conflict on their face and the reconciliation belongs here rather than in a reader's head:
 * {@code tests/README.md} lines 273 and 274 state that "{@code -fsign=EBCDIC} is REQUIRED - the
 * default {@code -fsign=ASCII} misreads the zoned-decimal sign overpunch and silently corrupts
 * negative balances", while {@code tests/helpers/record_codec.py} line 135 calls the very same
 * mapping "the canonical IBM ASCII trailing-sign mapping". Both statements are accurate about
 * different things. The overpunch characters are themselves plain seven-bit ASCII -- an opening brace,
 * a closing brace and the letters A through R -- and every span in this file is such a span, which is
 * why the assertions can be written as ordinary Java string literals. What is EBCDIC is the
 * convention that assigns those characters their digit-and-sign meanings. Choosing the setting whose
 * name matches the characters is exactly the mistake the quoted sentence warns about, so this class
 * asserts the EBCDIC assignment and never a cp037 or any other transcoding: that conversion happens
 * at the loader edge, one field at a time, and is not this codec's concern.</p>
 *
 * <p>Assumptions: a field is identified as zoned by its DECLARED offset, its DECLARED length and its
 * DECLARED kind, and by nothing else. A whole-record scan for spans that look like amounts is
 * forbidden, and the reference corpus proves why rather than merely warning about it: five spans in
 * {@code tests/fixtures/export/happy_path/trandata.txt} are eleven characters long, hold ten digits
 * and end in a letter from the negative table, so each is indistinguishable from a signed
 * {@code S9(09)V99} amount by inspection. None of them is one. Each straddles four declared field
 * boundaries and ends inside a source-terminal label. The tests below decode them to the nonsense
 * amounts a byte sniffer would produce, so the hazard is recorded as an executable fact instead of a
 * caution, and they take the declared geometry from {@link CopybookLayout}, which is the single
 * normative source of record geometry in this migration.</p>
 *
 * <p>Parameters, return values and exceptions at type level: declared inapplicable. A class
 * declaration accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.</p>
 */
class ZonedDecimalCodecTest {

    /**
     * The leading 48 characters of the single account record in
     * {@code tests/fixtures/posting/happy_path/acctdata.txt}, being its first five declared fields.
     *
     * <p>Assumptions: 48 is the sum of the declared widths of {@code ACCT-ID} at 11,
     * {@code ACCT-ACTIVE-STATUS} at 1 and three {@code PIC S9(10)V99} amounts at 12 each, so the
     * prefix ends exactly on a field boundary. Cutting it there rather than at a round number is what
     * lets every slice below be taken at a declared offset instead of a counted one.</p>
     */
    private static final String ACCOUNT_RECORD_PREFIX =
            "00000000007Y00000001930{00000020650{00000002640{";

    /** The declared span of {@code ACCT-ID PIC 9(11)}, zero-based [0:11] of the account record. */
    private static final String ACCT_ID_SPAN = "00000000007";

    /** The declared span of {@code ACCT-ACTIVE-STATUS PIC X(01)}, zero-based [11:12]. */
    private static final String ACCT_ACTIVE_STATUS_SPAN = "Y";

    /** The declared span of {@code ACCT-CURR-BAL PIC S9(10)V99}, zero-based [12:24]. */
    private static final String ACCT_CURR_BAL_SPAN = "00000001930{";

    /** The declared span of {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}, zero-based [24:36]. */
    private static final String ACCT_CREDIT_LIMIT_SPAN = "00000020650{";

    /** The declared span of {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}, zero-based [36:48]. */
    private static final String ACCT_CASH_CREDIT_LIMIT_SPAN = "00000002640{";

    /**
     * The declared span of {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}, zero-based [78:90] of the same
     * account record, which is a live positive zero.
     */
    private static final String POSITIVE_ZERO_SPAN = "00000000000{";

    /**
     * The negative-zero counterpart of {@link #POSITIVE_ZERO_SPAN}, differing in its final character
     * alone.
     *
     * <p>Assumptions: this span is SYNTHETIC. The reference corpus stores positive zero at two
     * declared offsets of the account record, [78:90] and [90:102], and stores no negative zero
     * anywhere, so the one span for which the round-trip law does not hold cannot be taken from live
     * data and has to be constructed. Constructing it as the same digits under the other table is what
     * keeps the comparison to a single character.</p>
     */
    private static final String NEGATIVE_ZERO_SPAN = "00000000000}";

    /**
     * The declared span of {@code TRAN-AMT PIC S9(09)V99} in the first record of
     * {@code tests/fixtures/export/happy_path/trandata.txt}, zero-based [132:143].
     */
    private static final String TRAN_AMT_OVERPUNCH_G = "0000005047G";

    /** The same declared span in the second record of that file, carrying a negative amount. */
    private static final String TRAN_AMT_OVERPUNCH_CLOSING_BRACE = "0000009190}";

    /** The same declared span in the third record of that file. */
    private static final String TRAN_AMT_OVERPUNCH_H = "0000000678H";

    /** The same declared span in the fourth record of that file. */
    private static final String TRAN_AMT_OVERPUNCH_G_SECOND = "0000002817G";

    /** The same declared span in the fifth record of that file. */
    private static final String TRAN_AMT_OVERPUNCH_F = "0000004546F";

    /**
     * The declared span of {@code DIS-INT-RATE PIC S9(04)V99}, zero-based [16:22] of the single record
     * of {@code tests/fixtures/interest/happy_path/discgrp.txt}.
     *
     * <p>Assumptions: this is the only zoned field in the corpus with four integer digits, so it is
     * the third distinct width available from live data and the one that proves the width rule is a
     * rule rather than two special cases. It is a rate and not an amount, which is why the tests that
     * use it assert a plain decimal and never a monetary type.</p>
     */
    private static final String DIS_INT_RATE_SPAN = "00150{";

    /**
     * The ten-digit body shared by the twenty overpunch-table vectors, taken from the live span
     * {@link #TRAN_AMT_OVERPUNCH_G}.
     *
     * <p>Assumptions: reusing one live body across the whole table makes every synthetic vector a
     * one-character perturbation of an attested one. A misread final character therefore shows up as a
     * value that differs from 504.77 in its last digit alone, which is precisely the difference a
     * hand-picked body could disguise.</p>
     */
    private static final String OVERPUNCH_BODY = "0000005047";

    /** The positive trailing-sign table this codec is contracted to implement, digit 0 through 9. */
    private static final String POSITIVE_OVERPUNCH_TABLE = "{ABCDEFGHI";

    /** The negative trailing-sign table this codec is contracted to implement, digit 0 through 9. */
    private static final String NEGATIVE_OVERPUNCH_TABLE = "}JKLMNOPQR";

    /** Integer digit positions of {@code PIC S9(10)V99}, the twelve-byte account money form. */
    private static final int ACCOUNT_MONEY_INT_DIGITS = 10;

    /** Integer digit positions of {@code PIC S9(09)V99}, the eleven-byte transaction money form. */
    private static final int TRANSACTION_MONEY_INT_DIGITS = 9;

    /** Integer digit positions of {@code PIC S9(04)V99}, the six-byte disclosure-group rate form. */
    private static final int RATE_INT_DIGITS = 4;

    /** Fractional digit positions every money and rate picture in the base masters declares. */
    private static final int MONEY_DEC_DIGITS = 2;

    /** Digit positions of {@code ACCT-ID PIC 9(11)}, an unsigned display key with no sign carrier. */
    private static final int ACCOUNT_ID_DIGITS = 11;

    /** Digit positions of {@code TRAN-MERCHANT-ID PIC 9(09)}, an unsigned display identifier. */
    private static final int MERCHANT_ID_DIGITS = 9;

    /** Fractional digit positions of a whole-number unsigned display field, which declares none. */
    private static final int NO_DEC_DIGITS = 0;

    /** Reads at a call site as the picture's leading {@code S}, so the geometry is legible there. */
    private static final boolean SIGNED = true;

    /** Reads at a call site as the absence of a leading {@code S} on the picture clause. */
    private static final boolean UNSIGNED = false;

    /**
     * The zero-based offset at which all five overpunch-looking straddles begin in their records.
     *
     * <p>Assumptions: every straddle in the parameterized case below starts at this one offset because
     * each is the tail of {@code TRAN-ID} followed by {@code TRAN-TYPE-CD}, {@code TRAN-CAT-CD} and
     * the first byte of {@code TRAN-SOURCE}. Holding the offset as a constant rather than as a column
     * of the case table keeps the table to the two values that actually vary.</p>
     */
    private static final int STRADDLE_OFFSET_IN_TRANSACTION = 12;

    /**
     * Decodes a span, checks its value and declared scale, and checks that re-encoding reproduces it.
     *
     * <p>Assumptions: the three checks are made together because they fail for different reasons and a
     * caller needs all three. A wrong value is a wrong overpunch table or a wrong implied point; a
     * wrong scale is a normalisation that would still compare numerically equal while encoding to
     * different characters; and a differing re-encode is the round-trip law itself, which the parity
     * oracle depends on because it compares batch output byte for byte after timestamp
     * normalisation.</p>
     *
     * <p>Trade-offs: this helper exists rather than the three assertions being written out at each of
     * some fifty call sites. Writing them out would keep every expectation visible at its call site,
     * which is the usual and better default for a test; it was rejected here only because the
     * repetition is exact and mechanical, so the duplicated form would be the place a maintainer
     * silently omits the scale check on a new vector. What the helper deliberately does NOT do is
     * compute the expected value: that arrives as text from the caller, so no expectation is ever
     * derived from the code under test.</p>
     *
     * <p>Returns no value. A differing decode, scale or re-encoded span is reported through the
     * assertion that names that part of the contract.</p>
     *
     * @param span the exact field characters to decode, already sliced to one declared field
     * @param intDigits the number of digit positions before the implied decimal point
     * @param decDigits the number of digit positions after the implied decimal point
     * @param signed whether the picture clause carries a leading {@code S}
     * @param expectedText the expected value written as exact decimal text, whose own scale must equal
     *     {@code decDigits} so that the expectation states the scale as well as the magnitude
     */
    private static void assertDecodesAndReEncodes(String span, int intDigits, int decDigits,
            boolean signed, String expectedText) {
        BigDecimal expected = new BigDecimal(expectedText);
        assertEquals(decDigits, expected.scale(),
                "the expectation '" + expectedText + "' must itself be written at the field's declared"
                        + " scale, otherwise this helper cannot check the decoded scale against it");

        BigDecimal decoded = ZonedDecimalCodec.decode(span, intDigits, decDigits, signed);
        assertEquals(expected, decoded,
                "span '" + span + "' must decode to " + expectedText);
        assertEquals(decDigits, decoded.scale(),
                "span '" + span + "' must decode at scale exactly " + decDigits);
        assertEquals(span, ZonedDecimalCodec.encode(decoded, intDigits, decDigits, signed),
                "re-encoding the value decoded from '" + span + "' must reproduce it byte for byte");
    }

    /**
     * Confirms the twelve-byte credit limit of the live account record decodes to two thousand and
     * sixty-five and re-encodes to the same twelve characters.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a differing value, a differing scale
     * or a differing re-encoded span as a test failure.</p>
     *
     * <p>Assumptions: the span is {@code ACCT-CREDIT-LIMIT}, declared {@code PIC S9(10)V99} at line 8
     * of {@code app/cpy/CVACT01Y.cpy} and therefore occupying twelve bytes at zero-based offset 24 of
     * the 300-byte account record. It is transcribed from the single record of
     * {@code tests/fixtures/posting/happy_path/acctdata.txt} and appears there in situ beside two
     * further amounts of the same picture, both of which are checked below.</p>
     */
    @Test
    void accountCreditLimitLiveSpanDecodesToTwoThousandSixtyFive() {
        assertDecodesAndReEncodes(ACCT_CREDIT_LIMIT_SPAN, ACCOUNT_MONEY_INT_DIGITS, MONEY_DEC_DIGITS,
                SIGNED, "2065.00");

        // WHY : Assumptions: the prefix constant is sliced here rather than the span constant being
        //       trusted on its own, because the two agreeing is the only thing that shows the span was
        //       taken from a declared offset of a real record instead of being typed out. If a later
        //       edit moved either one, this line is where the disagreement surfaces.
        assertEquals(ACCT_CREDIT_LIMIT_SPAN, ACCOUNT_RECORD_PREFIX.substring(24, 36),
                "the credit-limit constant must be exactly zero-based [24:36] of the account record");
    }

    /**
     * Proves that the overpunch character {@code 'G'} contributes the low-order digit seven as well as
     * the positive sign, so that the live span {@code 0000005047G} is 504.77 and never 50.47.
     *
     * <p>Takes no parameters and returns no value. JUnit reports the factor-of-ten misreading, or any
     * other differing value, as a test failure.</p>
     *
     * <p>This is the regression case for the single most consequential defect this codec can carry.
     * The span is {@code TRAN-AMT}, declared {@code PIC S9(09)V99} at line 10 of
     * {@code app/cpy/CVTRA05Y.cpy}, at zero-based offset 132 of the first 350-byte record of
     * {@code tests/fixtures/export/happy_path/trandata.txt}. Reading the final byte as a bare sign
     * marker leaves the ten body digits alone and yields 50.47, a number that is entirely plausible as
     * a purchase amount and is wrong by roughly a factor of ten. The assertions below fix the exact
     * relationship rather than the approximation: the true reading's unscaled digits are the ten body
     * digits followed by the digit the overpunch contributes, while the sign-marker reading keeps only
     * the ten body digits and silently discards the eleventh.</p>
     *
     * <p>Assumptions: the final character carries a DIGIT and not merely a sign, which the third group
     * of assertions establishes independently of any expected literal. Advancing the overpunch by one
     * position in its own table advances the value by exactly one cent, which is a property only a
     * digit-bearing final byte can have.</p>
     */
    @Test
    void positiveOverpunchGCarriesBothTheSignAndTheLowOrderDigitSeven() {
        assertDecodesAndReEncodes(TRAN_AMT_OVERPUNCH_G, TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS,
                SIGNED, "504.77");

        BigDecimal decoded = ZonedDecimalCodec.decode(TRAN_AMT_OVERPUNCH_G,
                TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED);
        assertNotEquals(new BigDecimal("50.47"), decoded,
                "the span must not decode to the value a sign-marker-only reading produces");
        assertEquals("50477", decoded.unscaledValue().toString(),
                "the unscaled digits must be the ten body digits plus the digit the overpunch carries");
        assertEquals("5047", new BigDecimal("50.47").unscaledValue().toString(),
                "the sign-marker-only reading keeps ten digits, which is how it loses the eleventh");

        // WHY : Assumptions: this pair is the sign-independent proof. 'G' and 'H' are adjacent entries
        //       of the positive table, so if the final byte were a sign marker alone the two spans
        //       would decode to the same amount; a one-cent difference is only possible if that byte
        //       also carries a digit. The check is written against the codec's own two decodes rather
        //       than against a literal, so it holds even if both literals were mistranscribed.
        BigDecimal nextDigitUp = ZonedDecimalCodec.decode(OVERPUNCH_BODY + 'H',
                TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED);
        assertEquals(new BigDecimal("0.01"), nextDigitUp.subtract(decoded),
                "advancing the overpunch one place in its table must advance the amount one cent");
    }

    /**
     * Confirms the third live transaction amount, whose overpunch is {@code 'H'}, decodes to 67.88.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a differing value, a differing scale
     * or a differing re-encoded span as a test failure.</p>
     *
     * <p>Assumptions: the span sits at zero-based offset 132 of the third record of
     * {@code tests/fixtures/export/happy_path/trandata.txt}. It is kept as a case of its own rather
     * than folded into the table below because {@code 'H'} is one of only five overpunch characters
     * the live corpus attests, and an attested vector earns a named test.</p>
     */
    @Test
    void transactionAmountWithOverpunchHDecodesToSixtySevenPointEightEight() {
        assertDecodesAndReEncodes(TRAN_AMT_OVERPUNCH_H, TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS,
                SIGNED, "67.88");
    }

    /**
     * Confirms the second live transaction amount, whose overpunch is the closing brace, decodes to
     * minus nine hundred and nineteen.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a lost sign, a differing magnitude, a
     * differing scale or a differing re-encoded span as a test failure.</p>
     *
     * <p>Assumptions: the closing brace is the negative table's entry for the digit zero, so this one
     * span exercises the sign and the low-order digit at once: the amount is negative AND its last
     * cent digit is zero. It sits at zero-based offset 132 of the second record of
     * {@code tests/fixtures/export/happy_path/trandata.txt}, whose source field holds the literal
     * {@code OPERATOR}, consistent with a returned item rather than a purchase.</p>
     *
     * <p>Assumptions: this span round-trips byte for byte and is NOT the documented exception to the
     * round-trip law. That exception applies only where the decoded value is numerically zero, and
     * minus nine hundred and nineteen is not, so the closing brace is reproduced here exactly.</p>
     */
    @Test
    void transactionAmountWithNegativeOverpunchDecodesToMinusNineHundredNineteen() {
        assertDecodesAndReEncodes(TRAN_AMT_OVERPUNCH_CLOSING_BRACE, TRANSACTION_MONEY_INT_DIGITS,
                MONEY_DEC_DIGITS, SIGNED, "-919.00");
    }

    /**
     * Establishes that the live corpus attests only five of the twenty overpunch characters, and
     * checks every one of those five against its declared value.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a differing attested set, a differing
     * count of characters left unattested, or a differing decoded value as a test failure.</p>
     *
     * <p>Assumptions: the attested set is computed from the live spans themselves rather than asserted
     * as a bare number, so the count cannot drift away from the spans it is meant to describe. The two
     * remaining amounts of the account record and the two remaining amounts of the transaction file
     * are decoded here so that every live signed span in either artifact is checked somewhere in this
     * class, not merely the four that have named tests.</p>
     */
    @Test
    void liveCorpusAttestsOnlyFiveOfTheTwentyOverpunchCharacters() {
        assertDecodesAndReEncodes(ACCT_CURR_BAL_SPAN, ACCOUNT_MONEY_INT_DIGITS, MONEY_DEC_DIGITS,
                SIGNED, "193.00");
        assertDecodesAndReEncodes(ACCT_CASH_CREDIT_LIMIT_SPAN, ACCOUNT_MONEY_INT_DIGITS,
                MONEY_DEC_DIGITS, SIGNED, "264.00");
        assertDecodesAndReEncodes(POSITIVE_ZERO_SPAN, ACCOUNT_MONEY_INT_DIGITS, MONEY_DEC_DIGITS,
                SIGNED, "0.00");
        assertDecodesAndReEncodes(TRAN_AMT_OVERPUNCH_G_SECOND, TRANSACTION_MONEY_INT_DIGITS,
                MONEY_DEC_DIGITS, SIGNED, "281.77");
        assertDecodesAndReEncodes(TRAN_AMT_OVERPUNCH_F, TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS,
                SIGNED, "454.66");

        String[] liveSignedSpans = {
            ACCT_CURR_BAL_SPAN,
            ACCT_CREDIT_LIMIT_SPAN,
            ACCT_CASH_CREDIT_LIMIT_SPAN,
            POSITIVE_ZERO_SPAN,
            TRAN_AMT_OVERPUNCH_G,
            TRAN_AMT_OVERPUNCH_CLOSING_BRACE,
            TRAN_AMT_OVERPUNCH_H,
            TRAN_AMT_OVERPUNCH_G_SECOND,
            TRAN_AMT_OVERPUNCH_F,
            DIS_INT_RATE_SPAN,
        };
        StringBuilder attested = new StringBuilder();
        for (String span : liveSignedSpans) {
            char last = span.charAt(span.length() - 1);
            if (attested.indexOf(String.valueOf(last)) < 0) {
                attested.append(last);
            }
        }
        assertEquals("{G}HF", attested.toString(),
                "the live corpus must attest exactly these five overpunch characters, in this order of"
                        + " first appearance across the account, transaction and rate artifacts");

        assertEquals(20, POSITIVE_OVERPUNCH_TABLE.length() + NEGATIVE_OVERPUNCH_TABLE.length(),
                "the two tables together must declare twenty characters, ten signs by ten digits");

        // WHY : Trade-offs: the fifteen characters this loop counts are covered by SYNTHETIC vectors in
        //       the two table cases below, and that is a deliberate exchange rather than an oversight.
        //       The alternative was to assert only what live data attests, which would leave three
        //       quarters of the table unexercised while reading as complete coverage -- and the
        //       unattested three quarters includes all nine letter entries of the negative table, so a
        //       transposition anywhere in the negative table would ship undetected. Exhaustive coverage
        //       of a table whose twenty entries are fixed by the reference implementation is worth more
        //       than fidelity to a corpus that happens to sample five of them, so the synthetic vectors
        //       are used and this assertion states exactly how many of them there have to be.
        int unattested = 0;
        for (char candidate : (POSITIVE_OVERPUNCH_TABLE + NEGATIVE_OVERPUNCH_TABLE).toCharArray()) {
            if (attested.indexOf(String.valueOf(candidate)) < 0) {
                unattested++;
            }
        }
        assertEquals(15, unattested,
                "fifteen of the twenty overpunch characters are unattested by live data and must"
                        + " therefore be covered by clearly identified synthetic vectors");
    }

    /**
     * Checks all ten entries of the positive trailing-sign table, each carrying its own low-order
     * digit, over the digit body of the live span {@code 0000005047G}.
     *
     * <p>Six of these ten vectors are SYNTHETIC. The characters {@code 'A'}, {@code 'B'}, {@code 'C'},
     * {@code 'D'}, {@code 'E'} and {@code 'I'} appear in no signed field of the reference corpus, so
     * their spans are constructed by substituting the final character of the attested span
     * {@code 0000005047G} and are identified as synthetic here rather than presented as transcriptions.
     * The remaining entries, the opening brace and {@code 'F'}, {@code 'G'} and {@code 'H'}, are
     * attested and are also checked by named tests above.</p>
     *
     * <p>Assumptions: the third column asserts the table's positional invariant, which is the property
     * the whole encoding rests on -- a character's INDEX in its table IS the digit it carries. Checking
     * the value without checking the index would pass for a table that produced the right amounts from
     * the wrong characters.</p>
     *
     * <p>Returns no value. Each parameterized invocation reports a differing table position, value,
     * scale or round-trip span as a JUnit failure.</p>
     *
     * @param span the eleven-character span to decode, being the shared body plus one table entry
     * @param expectedText the expected value written as exact decimal text at scale two
     * @param digit the digit position the final character occupies in the positive table
     */
    @ParameterizedTest
    @CsvSource({
        "0000005047{, 504.70, 0",
        "0000005047A, 504.71, 1",
        "0000005047B, 504.72, 2",
        "0000005047C, 504.73, 3",
        "0000005047D, 504.74, 4",
        "0000005047E, 504.75, 5",
        "0000005047F, 504.76, 6",
        "0000005047G, 504.77, 7",
        "0000005047H, 504.78, 8",
        "0000005047I, 504.79, 9",
    })
    void positiveOverpunchTableCarriesTheLowOrderDigitZeroThroughNine(String span,
            String expectedText, int digit) {
        assertEquals(OVERPUNCH_BODY, span.substring(0, OVERPUNCH_BODY.length()),
                "every table vector must share the digit body of the attested live span");
        assertEquals(POSITIVE_OVERPUNCH_TABLE.charAt(digit), span.charAt(span.length() - 1),
                "the final character's index in the positive table must be the digit it carries");
        assertDecodesAndReEncodes(span, TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED,
                expectedText);
    }

    /**
     * Checks all ten entries of the negative trailing-sign table, each carrying its own low-order digit
     * and a negative sign, over the digit body of the live span {@code 0000005047G}.
     *
     * <p>Nine of these ten vectors are SYNTHETIC. Only the closing brace is attested by the reference
     * corpus, in the span {@code 0000009190}} of the second transaction record; the letters {@code 'J'}
     * through {@code 'R'} appear in no signed field of it. Their spans are constructed by substituting
     * the final character of the attested span {@code 0000005047G}, and they are the reason the
     * synthetic vectors exist at all: without them the negative table would be one tenth exercised.</p>
     *
     * <p>Assumptions: none of these ten values is zero, so every one of them round-trips byte for byte
     * and the documented negative-zero exception does not apply to any of them. That exception is
     * exercised on its own below, over a span whose digits are all zeros.</p>
     *
     * <p>Returns no value. Each parameterized invocation reports a differing table position, sign,
     * value, scale or round-trip span as a JUnit failure.</p>
     *
     * @param span the eleven-character span to decode, being the shared body plus one table entry
     * @param expectedText the expected negative value written as exact decimal text at scale two
     * @param digit the digit position the final character occupies in the negative table
     */
    @ParameterizedTest
    @CsvSource({
        "0000005047}, -504.70, 0",
        "0000005047J, -504.71, 1",
        "0000005047K, -504.72, 2",
        "0000005047L, -504.73, 3",
        "0000005047M, -504.74, 4",
        "0000005047N, -504.75, 5",
        "0000005047O, -504.76, 6",
        "0000005047P, -504.77, 7",
        "0000005047Q, -504.78, 8",
        "0000005047R, -504.79, 9",
    })
    void negativeOverpunchTableCarriesTheLowOrderDigitZeroThroughNine(String span,
            String expectedText, int digit) {
        assertEquals(OVERPUNCH_BODY, span.substring(0, OVERPUNCH_BODY.length()),
                "every table vector must share the digit body of the attested live span");
        assertEquals(NEGATIVE_OVERPUNCH_TABLE.charAt(digit), span.charAt(span.length() - 1),
                "the final character's index in the negative table must be the digit it carries");
        assertDecodesAndReEncodes(span, TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED,
                expectedText);
    }

    /**
     * Reconstructs both trailing-sign tables from the encode direction alone and checks them against
     * the two literal tables this codec is contracted to implement.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a table whose characters differ, or
     * whose characters are in a different order, as a test failure.</p>
     *
     * <p>Assumptions: the two production tables are private constants, so they cannot be read from a
     * test and are asserted through behaviour instead. Encoding the ten values whose last cent digit
     * runs zero through nine, once positive and once negative, emits each table entry exactly once and
     * in index order, which is a complete statement of both tables without reaching into the class.</p>
     *
     * <p>Alternatives Considered: widening the production constants to package-private so this test
     * could compare them directly. Rejected because it would let the test pass by comparing the
     * implementation with itself: a transposed table would satisfy such an assertion trivially. Driving
     * the comparison through the encoder means the literals here are an independent statement of the
     * contract, which is the only form in which they are worth asserting.</p>
     */
    @Test
    void encodedOverpunchCharactersReproduceBothProductionTablesInOrder() {
        StringBuilder positives = new StringBuilder();
        StringBuilder negatives = new StringBuilder();
        for (int digit = 0; digit <= 9; digit++) {
            BigDecimal magnitude = new BigDecimal("504.7" + digit);
            String positiveSpan = ZonedDecimalCodec.encode(magnitude, TRANSACTION_MONEY_INT_DIGITS,
                    MONEY_DEC_DIGITS, SIGNED);
            String negativeSpan = ZonedDecimalCodec.encode(magnitude.negate(),
                    TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED);
            positives.append(positiveSpan.charAt(positiveSpan.length() - 1));
            negatives.append(negativeSpan.charAt(negativeSpan.length() - 1));
        }

        assertEquals(POSITIVE_OVERPUNCH_TABLE, positives.toString(),
                "encoding positive values must emit the positive table in index order");
        assertEquals(NEGATIVE_OVERPUNCH_TABLE, negatives.toString(),
                "encoding negative values must emit the negative table in index order");
    }

    /**
     * Confirms that a closing-brace zero decodes to numerical zero at the declared scale and that
     * re-encoding it emits the opening brace, which is the one documented zoned span that does not
     * round-trip byte for byte.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a non-zero value, a differing scale,
     * or a re-encoded span other than the positive-zero form as a test failure.</p>
     *
     * <p>This is the ONLY documented non-byte-identical zoned round trip. It is a consequence of the
     * target type and not a defect on either side. The baseline distinguishes the opening-brace
     * overpunch, a positive zero, from the closing-brace overpunch, a negative zero, as two distinct
     * bytes; {@link BigDecimal} has no negative zero, because a value built from the text
     * {@code "-0.00"} carries scale two and signum zero and is indistinguishable from one built from
     * {@code "0.00"}. Decoding either overpunched zero therefore yields the same value, and encoding a
     * zero always emits the opening brace. Every other span this codec accepts reproduces itself
     * exactly.</p>
     *
     * <p>Assumptions: the Java contract is authoritative here and the reference implementation is
     * deliberately NOT followed. {@code tests/helpers/record_codec.py} reads the sign of its decoded
     * value at line 395 with a test that preserves a signed zero, so a closing-brace zero re-encodes to
     * a closing brace there. This codec classifies every zero as non-negative and canonicalises to the
     * opening brace. The divergence is stated in both directions so that a reader comparing the two
     * implementations finds the difference declared rather than having to discover it.</p>
     */
    @Test
    void negativeZeroDecodesToZeroAndReEncodesAsThePositiveZeroOverpunch() {
        BigDecimal fromNegativeZero = ZonedDecimalCodec.decode(NEGATIVE_ZERO_SPAN,
                ACCOUNT_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED);
        BigDecimal fromPositiveZero = ZonedDecimalCodec.decode(POSITIVE_ZERO_SPAN,
                ACCOUNT_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED);

        assertEquals(new BigDecimal("0.00"), fromNegativeZero,
                "a closing-brace zero must decode to numerical zero");
        assertEquals(MONEY_DEC_DIGITS, fromNegativeZero.scale(),
                "a closing-brace zero must decode at the field's declared scale, not at scale zero");
        assertEquals(0, fromNegativeZero.signum(),
                "the target type has no negative zero, so the decoded signum must be zero");
        assertEquals(fromPositiveZero, fromNegativeZero,
                "both overpunched zeros must decode to one indistinguishable value");

        assertEquals(POSITIVE_ZERO_SPAN,
                ZonedDecimalCodec.encode(fromNegativeZero, ACCOUNT_MONEY_INT_DIGITS, MONEY_DEC_DIGITS,
                        SIGNED),
                "encoding a zero must emit the canonical positive-zero overpunch");
        assertNotEquals(NEGATIVE_ZERO_SPAN,
                ZonedDecimalCodec.encode(fromNegativeZero, ACCOUNT_MONEY_INT_DIGITS, MONEY_DEC_DIGITS,
                        SIGNED),
                "the closing-brace zero is the one span this codec does not reproduce byte for byte");

        // WHY : Assumptions: the two spans differ in their final character alone, and this line fixes
        //       that so the divergence above cannot be mistaken for a wider difference. Everything the
        //       previous assertions describe is caused by one byte.
        assertEquals(POSITIVE_ZERO_SPAN.substring(0, POSITIVE_ZERO_SPAN.length() - 1),
                NEGATIVE_ZERO_SPAN.substring(0, NEGATIVE_ZERO_SPAN.length() - 1),
                "the two zero spans must share every character except the overpunch");
    }

    /**
     * Proves that decoding preserves exactly the fractional width declared by the picture, including
     * trailing zeros that do not change numerical magnitude.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a differing scale or a plain-text
     * rendering that omits a declared trailing zero as a test failure.</p>
     *
     * <p>Assumptions: the account amount, transaction amount and disclosure-group rate are retained as
     * three separate checks because they are live fields of three physical widths. The unsigned
     * account identifier supplies the zero-fraction control case. Together they prove that scale
     * follows {@code decDigits}, not the field width, the sign or the number of trailing zeros in the
     * decoded magnitude.</p>
     */
    @Test
    void decodedScaleAlwaysEqualsTheDeclaredFractionalDigitCount() {
        BigDecimal accountAmount = ZonedDecimalCodec.decode(ACCT_CREDIT_LIMIT_SPAN,
                ACCOUNT_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED);
        BigDecimal transactionAmount = ZonedDecimalCodec.decode(TRAN_AMT_OVERPUNCH_G,
                TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED);
        BigDecimal rate = ZonedDecimalCodec.decode(DIS_INT_RATE_SPAN, RATE_INT_DIGITS,
                MONEY_DEC_DIGITS, SIGNED);
        BigDecimal accountId = ZonedDecimalCodec.decode(ACCT_ID_SPAN, ACCOUNT_ID_DIGITS,
                NO_DEC_DIGITS, UNSIGNED);

        assertEquals(MONEY_DEC_DIGITS, accountAmount.scale(),
                "the twelve-byte account amount must retain its two declared fractional digits");
        assertEquals("2065.00", accountAmount.toPlainString(),
                "the account amount must retain both trailing zeros in exact decimal text");
        assertEquals(MONEY_DEC_DIGITS, transactionAmount.scale(),
                "the eleven-byte transaction amount must retain its two declared fractional digits");
        assertEquals("504.77", transactionAmount.toPlainString(),
                "the transaction amount must retain both digits after its implied point");
        assertEquals(MONEY_DEC_DIGITS, rate.scale(),
                "the six-byte rate must retain its two declared fractional digits");
        assertEquals("15.00", rate.toPlainString(),
                "the rate must retain both trailing zeros even though its magnitude is a whole number");
        assertEquals(NO_DEC_DIGITS, accountId.scale(),
                "the unsigned identifier declares no fractional digits and must therefore have scale zero");
        assertEquals("7", accountId.toPlainString(),
                "the zero-fraction control must contain no invented point or fractional zero");
    }

    /**
     * Proves that zoned width is the integer-digit count plus the fractional-digit count because the
     * sign and the implied decimal point consume no separate byte.
     *
     * <p>Takes no parameters and returns no value. JUnit reports disagreement between the codec width,
     * the layout width and any live field descriptor as a test failure.</p>
     *
     * <p>Assumptions: three independent expressions of width are compared deliberately. The codec
     * determines how many characters it accepts, the layout utility determines how many bytes a
     * numeric kind occupies, and each {@link FieldSpec} transcribes the copybook's physical span. If
     * any two shared one mistaken constant, the third would still expose the disagreement.</p>
     */
    @Test
    void zonedWidthIsTheDigitCountBecauseNeitherSignNorPointOccupiesAByte() {
        FieldSpec accountAmount = CopybookLayout.layout("ACCOUNT").field("ACCT-CREDIT-LIMIT");
        FieldSpec transactionAmount = CopybookLayout.layout("TRAN").field("TRAN-AMT");
        FieldSpec rate = CopybookLayout.layout("DISGROUP").field("DIS-INT-RATE");

        assertEquals(12, ZonedDecimalCodec.widthOf(ACCOUNT_MONEY_INT_DIGITS, MONEY_DEC_DIGITS),
                "PIC S9(10)V99 must occupy twelve codec characters");
        assertEquals(12, CopybookLayout.zonedWidth(
                ACCOUNT_MONEY_INT_DIGITS, MONEY_DEC_DIGITS),
                "PIC S9(10)V99 must occupy twelve layout bytes");
        assertEquals(12, CopybookLayout.widthOf(
                Kind.ZONED, ACCOUNT_MONEY_INT_DIGITS, MONEY_DEC_DIGITS),
                "dispatching the ZONED kind must preserve the twelve-byte width");
        assertEquals(12, accountAmount.length(),
                "the declared account amount span must be twelve bytes");
        assertEquals(ACCT_CREDIT_LIMIT_SPAN.length(), accountAmount.length(),
                "the live account span must fill its declared field exactly");

        assertEquals(11, ZonedDecimalCodec.widthOf(
                TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS),
                "PIC S9(09)V99 must occupy eleven codec characters");
        assertEquals(11, CopybookLayout.zonedWidth(
                TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS),
                "PIC S9(09)V99 must occupy eleven layout bytes");
        assertEquals(11, CopybookLayout.widthOf(
                Kind.ZONED, TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS),
                "dispatching the ZONED kind must preserve the eleven-byte width");
        assertEquals(11, transactionAmount.length(),
                "the declared transaction amount span must be eleven bytes");
        assertEquals(TRAN_AMT_OVERPUNCH_G.length(), transactionAmount.length(),
                "the live transaction span must fill its declared field exactly");

        assertEquals(6, ZonedDecimalCodec.widthOf(RATE_INT_DIGITS, MONEY_DEC_DIGITS),
                "PIC S9(04)V99 must occupy six codec characters");
        assertEquals(6, CopybookLayout.widthOf(
                Kind.ZONED, RATE_INT_DIGITS, MONEY_DEC_DIGITS),
                "dispatching the ZONED kind must preserve the six-byte width");
        assertEquals(6, rate.length(),
                "the declared disclosure-group rate span must be six bytes");
        assertEquals(DIS_INT_RATE_SPAN.length(), rate.length(),
                "the live rate span must fill its declared field exactly");
    }

    /**
     * Proves that decoded values are exact decimal text with no binary numeric intermediate, at both
     * the smallest non-zero cent and the largest positive account-picture magnitude.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a differing plain-text value,
     * unscaled digit sequence, declared scale or re-encoded span as a test failure.</p>
     *
     * <p>Assumptions: both vectors are SYNTHETIC one-character constructions. {@code 0000000000A}
     * contributes low-order digit one to an otherwise zero transaction-width body, while
     * {@code 99999999999I} contributes low-order digit nine to an all-nine account-width body. These
     * are the two ends at which an approximate binary detour would be easiest to expose, and asserting
     * the unscaled digits makes the exact construction observable without asking the implementation
     * how it built the value.</p>
     */
    @Test
    void decodedValueIsExactDecimalTextWithNoBinaryNumericIntermediate() {
        String smallestSpan = "0000000000A";
        BigDecimal smallest = ZonedDecimalCodec.decode(smallestSpan,
                TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED);
        assertEquals(new BigDecimal("0.01"), smallest,
                "the low-order positive overpunch must produce one exact cent");
        assertEquals("0.01", smallest.toPlainString(),
                "the smallest non-zero value must render as exact decimal text");
        assertEquals("1", smallest.unscaledValue().toString(),
                "one cent at scale two must carry the unscaled digit sequence one");
        assertDecodesAndReEncodes(smallestSpan, TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS,
                SIGNED, "0.01");

        String largestSpan = "99999999999I";
        BigDecimal largest = ZonedDecimalCodec.decode(largestSpan,
                ACCOUNT_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED);
        assertEquals(new BigDecimal("9999999999.99"), largest,
                "the all-nine account picture must retain every declared digit exactly");
        assertEquals("9999999999.99", largest.toPlainString(),
                "the largest account-picture magnitude must render as exact decimal text");
        assertEquals("999999999999", largest.unscaledValue().toString(),
                "the all-nine value must carry all twelve unscaled digits");
        assertDecodesAndReEncodes(largestSpan, ACCOUNT_MONEY_INT_DIGITS, MONEY_DEC_DIGITS,
                SIGNED, "9999999999.99");
    }

    /**
     * Proves that the unsigned eleven-byte account identifier is decoded only within its declared
     * span and leaves the adjacent active-status byte as a separate text field.
     *
     * <p>Takes no parameters and returns no value. JUnit reports differing layout metadata, a slice
     * crossing the declared boundary, a differing identifier value or a changed status byte as a
     * test failure.</p>
     *
     * <p>Assumptions: {@code ACCT-ID PIC 9(11)} has no sign carrier, even though the adjacent status
     * {@code 'Y'} could be mistaken for a final marker by a record-wide scan. Slicing from the two
     * {@link FieldSpec} intervals before decoding is the executable statement that field geometry
     * outranks character resemblance.</p>
     */
    @Test
    void unsignedAccountIdDecodesStrictlyWithinItsDeclaredElevenByteSpan() {
        RecordSpec account = CopybookLayout.layout("ACCOUNT");
        FieldSpec accountId = account.field("ACCT-ID");
        FieldSpec activeStatus = account.field("ACCT-ACTIVE-STATUS");

        assertEquals(Kind.UINT, accountId.kind(),
                "the layout must identify ACCT-ID as unsigned display rather than zoned");
        assertEquals(0, accountId.start(), "ACCT-ID must begin at the start of the account record");
        assertEquals(11, accountId.length(), "ACCT-ID PIC 9(11) must occupy eleven bytes");
        assertEquals(11, accountId.end(), "ACCT-ID must end immediately before the status byte");
        assertEquals(false, accountId.signed(), "ACCT-ID declares no leading sign");
        assertEquals(accountId.end(), activeStatus.start(),
                "the status field must begin exactly where the identifier ends");
        assertEquals(1, activeStatus.length(), "the active-status field must remain one byte");
        assertEquals(Kind.TEXT, activeStatus.kind(),
                "the adjacent status byte must remain text rather than a sign carrier");

        // WHY : Assumptions: these slices use descriptor bounds rather than the known literals so the
        //       test fails if the registry moves either boundary. Decoding a separately declared span
        //       is the guard; merely comparing the constants would still permit a caller to pass both
        //       fields to one codec operation.
        String slicedId = ACCOUNT_RECORD_PREFIX.substring(accountId.start(), accountId.end());
        String slicedStatus = ACCOUNT_RECORD_PREFIX.substring(
                activeStatus.start(), activeStatus.end());
        assertEquals(ACCT_ID_SPAN, slicedId,
                "the descriptor-bounded slice must recover the eleven-byte live identifier");
        assertEquals(ACCT_ACTIVE_STATUS_SPAN, slicedStatus,
                "the descriptor-bounded adjacent slice must recover the status independently");
        assertDecodesAndReEncodes(slicedId, accountId.length(), NO_DEC_DIGITS,
                accountId.signed(), "7");
    }

    /**
     * Proves that declared offset, length and {@link Kind} are the only facts that identify a zoned
     * field, even when an undeclared record substring has valid zoned syntax.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a differing transaction layout, a
     * false field declaration at the straddle offset or a differing amount decode as a test
     * failure.</p>
     *
     * <p>Assumptions: the live {@code TRAN-AMT} descriptor is the positive control because it names
     * {@link Kind#ZONED} at offset 132 with the exact eleven-byte geometry that its picture implies.
     * Offset 12 is the negative control: an eleven-character substring there decodes successfully as
     * zoned data, but no field descriptor starts there. Syntax is therefore shown to be insufficient
     * without relying on a warning in prose.</p>
     */
    @Test
    void theDeclaredLayoutIsTheOnlyThingThatIdentifiesAZonedField() {
        RecordSpec transaction = CopybookLayout.layout("TRAN");
        FieldSpec amount = transaction.field("TRAN-AMT");

        assertEquals(Kind.ZONED, amount.kind(),
                "the transaction amount must be explicitly declared as the real ZONED enum member");
        assertEquals(132, amount.start(), "the transaction amount must begin at zero-based offset 132");
        assertEquals(11, amount.length(), "the transaction amount must occupy exactly eleven bytes");
        assertEquals(TRANSACTION_MONEY_INT_DIGITS, amount.intDigits(),
                "the transaction amount must declare nine integer digits");
        assertEquals(MONEY_DEC_DIGITS, amount.decDigits(),
                "the transaction amount must declare two fractional digits");
        assertTrue(amount.signed(), "the transaction amount picture must carry a leading sign");

        // WHY : Assumptions: asking the registry whether a matching descriptor exists is the decisive
        //       negative check. Rejecting the substring merely because its decoded value looks absurd
        //       would encode a business-value heuristic, and a large legitimate amount could then be
        //       discarded while this false positive happened to pass.
        assertTrue(transaction.fields().stream().noneMatch(candidate ->
                        candidate.start() == STRADDLE_OFFSET_IN_TRANSACTION
                                && candidate.length() == amount.length()
                                && candidate.kind() == Kind.ZONED),
                "no zoned field may be invented at the overpunch-looking straddle offset");

        assertDecodesAndReEncodes(TRAN_AMT_OVERPUNCH_G, amount.intDigits(), amount.decDigits(),
                amount.signed(), "504.77");
        assertDecodesAndReEncodes("3580010001P", amount.intDigits(), amount.decDigits(),
                amount.signed(), "-358001000.17");
    }

    /**
     * Demonstrates that each eleven-character boundary straddle at offset 12 is syntactically valid
     * zoned data yet is not an amount because the transaction layout declares four other fields there.
     *
     * <p>The five rows are transcribed from zero-based [12:23] of the five records in
     * {@code tests/fixtures/export/happy_path/trandata.txt}. Each starts in {@code TRAN-ID}, crosses
     * {@code TRAN-TYPE-CD} and {@code TRAN-CAT-CD}, and takes its final character from
     * {@code TRAN-SOURCE}. Their decodes are asserted only to prove that a syntax sniffer would accept
     * them; those values have no domain meaning.</p>
     *
     * <p>Assumptions: the expected false value is retained because successful round-trip symmetry is
     * part of the hazard. A whole-record scanner would not merely find a candidate; it could decode
     * and re-encode the candidate without error, so only the declared field list can reject it.</p>
     *
     * <p>Returns no value. Each parameterized invocation reports a differing boundary, descriptor,
     * false decode or round-trip span as a JUnit failure.</p>
     *
     * @param span the eleven-character live substring that straddles four declared fields
     * @param expectedFalseValue the meaningless value a zoned syntax sniffer would nevertheless read
     */
    @ParameterizedTest
    @CsvSource({
        "3580010001P, -358001000.17",
        "4260030001O, -426003000.16",
        "2564010001P, -256401000.17",
        "1861010001P, -186101000.17",
        "2252010001P, -225201000.17",
    })
    void spansEndingInAnOverpunchCharacterAreNotAmountsUnlessTheLayoutSaysSo(
            String span, String expectedFalseValue) {
        RecordSpec transaction = CopybookLayout.layout("TRAN");
        FieldSpec transactionId = transaction.field("TRAN-ID");
        FieldSpec transactionType = transaction.field("TRAN-TYPE-CD");
        FieldSpec transactionCategory = transaction.field("TRAN-CAT-CD");
        FieldSpec transactionSource = transaction.field("TRAN-SOURCE");

        assertEquals(11, span.length(), "each measured straddle must be transaction-amount width");
        assertTrue(NEGATIVE_OVERPUNCH_TABLE.indexOf(span.charAt(span.length() - 1)) >= 0,
                "each measured straddle must end in a character the negative table accepts");
        assertTrue(STRADDLE_OFFSET_IN_TRANSACTION >= transactionId.start()
                        && STRADDLE_OFFSET_IN_TRANSACTION < transactionId.end(),
                "the straddle must begin inside TRAN-ID rather than on a field boundary");
        assertEquals(transactionId.end(), transactionType.start(),
                "TRAN-TYPE-CD must immediately follow TRAN-ID");
        assertEquals(transactionType.end(), transactionCategory.start(),
                "TRAN-CAT-CD must immediately follow TRAN-TYPE-CD");
        assertEquals(transactionCategory.end(), transactionSource.start(),
                "TRAN-SOURCE must immediately follow TRAN-CAT-CD");
        assertEquals(transactionSource.start() + 1,
                STRADDLE_OFFSET_IN_TRANSACTION + span.length(),
                "the straddle must end after taking exactly one byte from TRAN-SOURCE");
        assertTrue(transaction.fields().stream().noneMatch(candidate ->
                        candidate.start() == STRADDLE_OFFSET_IN_TRANSACTION
                                && candidate.length() == span.length()
                                && candidate.kind() == Kind.ZONED),
                "the transaction layout must declare no zoned field over the four-field straddle");

        // WHY : Assumptions: decoding here is deliberately the wrong operation, performed to make the
        //       false positive executable. The following successful round trip is evidence against a
        //       whole-record syntax scan, not evidence that the substring is a business amount.
        assertDecodesAndReEncodes(span, TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED,
                expectedFalseValue);
    }

    /**
     * Proves that each nine-byte merchant identifier remains unsigned and that the following initial
     * remains part of the merchant name, even when their concatenation resembles signed zoned data.
     *
     * <p>The two rows are measured boundaries from records two and five of
     * {@code tests/fixtures/export/happy_path/trandata.txt}. A syntax scan over ten bytes would read
     * the name initials {@code 'N'} and {@code 'K'} as negative overpunches; the declared layout
     * instead decodes the first nine bytes as one identifier and leaves the initial untouched.</p>
     *
     * <p>Returns no value. Each parameterized invocation reports a differing field boundary,
     * identifier, name initial or deliberately demonstrated false decode as a JUnit failure.</p>
     *
     * @param straddle the live merchant identifier followed by the first merchant-name character
     * @param expectedFalseValue the meaningless signed value a ten-character syntax scan would read
     * @param expectedNameInitial the character declared to begin {@code TRAN-MERCHANT-NAME}
     */
    @ParameterizedTest
    @CsvSource({
        "800000000K, -80000000.02, K",
        "800000000N, -80000000.05, N",
    })
    void merchantIdentifierDecodesWithinItsDeclaredNineByteSpan(
            String straddle, String expectedFalseValue, String expectedNameInitial) {
        RecordSpec transaction = CopybookLayout.layout("TRAN");
        FieldSpec merchantId = transaction.field("TRAN-MERCHANT-ID");
        FieldSpec merchantName = transaction.field("TRAN-MERCHANT-NAME");

        assertEquals(Kind.UINT, merchantId.kind(),
                "the merchant identifier must be declared as unsigned display");
        assertEquals(MERCHANT_ID_DIGITS, merchantId.length(),
                "the merchant identifier must occupy its nine declared bytes");
        assertEquals(merchantId.end(), merchantName.start(),
                "the merchant name must begin immediately after the identifier");
        assertEquals(Kind.TEXT, merchantName.kind(),
                "the following initial must belong to a text field");

        String identifier = straddle.substring(0, merchantId.length());
        String nameInitial = straddle.substring(merchantId.length());
        assertEquals("800000000", identifier,
                "both measured boundaries must carry the same nine-byte merchant identifier");
        assertEquals(expectedNameInitial, nameInitial,
                "the tenth character must remain the first byte of the merchant name");
        assertDecodesAndReEncodes(identifier, MERCHANT_ID_DIGITS, NO_DEC_DIGITS,
                UNSIGNED, "800000000");

        // WHY : Assumptions: this deliberate false decode uses the only signed geometry that fits ten
        //       characters at scale two. Its success proves that length and a valid final overpunch do
        //       not identify a field; the adjacent descriptors above do.
        int falseIntDigits = straddle.length() - MONEY_DEC_DIGITS;
        assertEquals(new BigDecimal(expectedFalseValue),
                ZonedDecimalCodec.decode(straddle, falseIntDigits, MONEY_DEC_DIGITS, SIGNED),
                "a ten-character syntax scan must expose the exact false value the layout prevents");
    }

    /**
     * Shows that longer live boundary straddles either decode and round-trip as false zoned values or
     * fail at the first invalid body or overpunch character, neither of which identifies a field.
     *
     * <p>Takes no parameters and returns no value. The expected failures are exact
     * {@link ZonedDecimalException} instances raised inside assertion lambdas and captured there, so
     * this method itself completes normally; JUnit reports a missing or differently typed exception,
     * a differing validation location or a differing false decode as a test failure.</p>
     *
     * <p>Assumptions: the three substrings are transcribed from live record boundaries rather than
     * invented malformed inputs. The fifteen-character all-digit-body form comes from the first
     * transaction record; the seventeen-character form extends its offset-12 straddle through
     * {@code TRAN-SOURCE}; and the twelve-character account form concatenates {@code ACCT-ID} with
     * {@code ACCT-ACTIVE-STATUS}. Their different outcomes demonstrate why neither acceptance nor
     * rejection is a substitute for consulting the layout.</p>
     */
    @Test
    void longerStraddlesAreRejectedOrProvablyMisreadDependingOnTheirBody() {
        assertDecodesAndReEncodes("00683580010001P", 13, MONEY_DEC_DIGITS, SIGNED,
                "-68358001000.17");

        ZonedDecimalException bodyFailure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.decode(
                        "3580010001POS TER", 15, MONEY_DEC_DIGITS, SIGNED));
        assertEquals(ZonedDecimalException.class, bodyFailure.getClass(),
                "the body defect must raise the codec's exact nested exception class");
        assertThat(bodyFailure.getMessage())
                .contains("digit body")
                .contains("zero-based index 10");

        ZonedDecimalException finalFailure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.decode(
                        ACCT_ID_SPAN + ACCT_ACTIVE_STATUS_SPAN,
                        ACCOUNT_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED));
        assertEquals(ZonedDecimalException.class, finalFailure.getClass(),
                "the status byte must raise the codec's exact nested exception class");
        assertThat(finalFailure.getMessage())
                .contains(POSITIVE_OVERPUNCH_TABLE)
                .contains(NEGATIVE_OVERPUNCH_TABLE);
    }

    /**
     * Confirms that an absent field span is rejected before any character access is attempted.
     *
     * <p>Takes no parameters and returns no value. The expected failure is an exact
     * {@link ZonedDecimalException}, raised inside the assertion lambda and captured there, so this
     * method itself completes normally; JUnit reports a missing or differently typed exception as a
     * test failure.</p>
     *
     * <p>Assumptions: valid digit metadata is supplied so absence is the first and only defect. This
     * isolates the null contract from the separate metadata-precedence case below.</p>
     */
    @Test
    void absentSpanIsRejected() {
        ZonedDecimalException failure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.decode(
                        null, TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED));

        assertEquals(ZonedDecimalException.class, failure.getClass(),
                "an absent span must raise the codec's exact nested exception class");
        assertThat(failure.getMessage())
                .contains("zoned field span is absent");
    }

    /**
     * Confirms that a span one character shorter than its declared width is rejected without padding.
     *
     * <p>Takes no parameters and returns no value. The expected failure is an exact
     * {@link ZonedDecimalException}, raised inside the assertion lambda and captured there, so this
     * method itself completes normally; JUnit reports a missing or differently typed exception as a
     * test failure.</p>
     *
     * <p>Assumptions: the ten supplied characters are otherwise digits, so a failure can only be the
     * eleven-character geometry of {@code PIC S9(09)V99}. The convenience overload intentionally
     * withholds those characters because no field context declares them safe to quote.</p>
     */
    @Test
    void spanShorterThanTheDeclaredWidthIsRejected() {
        String shortSpan = "0000005047";
        ZonedDecimalException failure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.decode(shortSpan,
                        TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED));

        assertEquals(ZonedDecimalException.class, failure.getClass(),
                "a short span must raise the codec's exact nested exception class");
        assertThat(failure.getMessage())
                .contains("expected 11 characters")
                .contains("span holds 10")
                .doesNotContain(shortSpan);
    }

    /**
     * Confirms that a span one character longer than its declared width is rejected without truncation.
     *
     * <p>Takes no parameters and returns no value. The expected failure is an exact
     * {@link ZonedDecimalException}, raised inside the assertion lambda and captured there, so this
     * method itself completes normally; JUnit reports a missing or differently typed exception as a
     * test failure.</p>
     *
     * <p>Assumptions: the live transaction span is extended by one digit rather than altered, which
     * proves that a valid eleven-character prefix cannot license silently discarding a twelfth
     * character. The convenience overload withholds the offending content by default.</p>
     */
    @Test
    void spanLongerThanTheDeclaredWidthIsRejected() {
        String longSpan = TRAN_AMT_OVERPUNCH_G + "0";
        ZonedDecimalException failure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.decode(longSpan,
                        TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED));

        assertEquals(ZonedDecimalException.class, failure.getClass(),
                "a long span must raise the codec's exact nested exception class");
        assertThat(failure.getMessage())
                .contains("expected 11 characters")
                .contains("span holds 12")
                .doesNotContain(longSpan);
    }

    /**
     * Confirms that a non-digit character in the signed field's digit body is rejected at its exact
     * zero-based position.
     *
     * <p>Takes no parameters and returns no value. The expected failure is an exact
     * {@link ZonedDecimalException}, raised inside the assertion lambda and captured there, so this
     * method itself completes normally; JUnit reports a missing or differently typed exception or a
     * differing index as a test failure.</p>
     *
     * <p>Assumptions: the final {@code 'G'} remains a valid overpunch and every other body character
     * remains a digit, so zero-based index five is the single malformed position and no later
     * validation can account for the failure.</p>
     */
    @Test
    void nonDigitInTheDigitBodyIsRejected() {
        ZonedDecimalException failure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.decode(
                        "00000X5047G", TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED));

        assertEquals(ZonedDecimalException.class, failure.getClass(),
                "a body defect must raise the codec's exact nested exception class");
        assertThat(failure.getMessage())
                .contains("signed zoned field")
                .contains("non-digit character")
                .contains("zero-based index 5")
                .contains("digit body");
    }

    /**
     * Confirms that a final character outside both trailing-sign tables is rejected after its digit
     * body has passed validation.
     *
     * <p>Takes no parameters and returns no value. The expected failure is an exact
     * {@link ZonedDecimalException}, raised inside the assertion lambda and captured there, so this
     * method itself completes normally; JUnit reports a missing or differently typed exception or
     * missing table names as a test failure.</p>
     *
     * <p>Assumptions: the asterisk is placed only in the final position, after ten valid body digits,
     * so this case reaches trailing-sign classification and cannot be mistaken for a body defect.</p>
     */
    @Test
    void unrecognisedFinalCharacterIsRejected() {
        ZonedDecimalException failure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.decode(
                        "0000005047*", TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED));

        assertEquals(ZonedDecimalException.class, failure.getClass(),
                "an unknown overpunch must raise the codec's exact nested exception class");
        assertThat(failure.getMessage())
                .contains("must end in a trailing-sign overpunch character")
                .contains(POSITIVE_OVERPUNCH_TABLE)
                .contains(NEGATIVE_OVERPUNCH_TABLE);
    }

    /**
     * Confirms that a plain trailing digit on a signed field is rejected rather than interpreted as
     * an implicit positive sign.
     *
     * <p>Takes no parameters and returns no value. The expected failure is an exact
     * {@link ZonedDecimalException}, raised inside the assertion lambda and captured there, so this
     * method itself completes normally; JUnit reports a missing or differently typed exception or an
     * assumed sign as a test failure.</p>
     *
     * <p>Alternatives Considered: accepting the final seven as an un-overpunched positive digit.
     * Rejected because the field's declared leading {@code S} requires a sign carrier, and a bare
     * digit is the observable symptom of the incompatible compiler sign convention. Assuming positive
     * would convert a configuration defect into an undetectable sign error for negative records.</p>
     */
    @Test
    void plainTrailingDigitOnASignedFieldIsRejectedRatherThanAssumedPositive() {
        // WHY : Alternatives Considered: decoding this as positive 504.77 was rejected because no
        //       byte in the span then carries the declared sign. Refusal preserves evidence that the
        //       producer used the wrong sign convention instead of inventing a sign the bytes omit.
        ZonedDecimalException failure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.decode(
                        "00000050477", TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED));

        assertEquals(ZonedDecimalException.class, failure.getClass(),
                "a plain signed-field digit must raise the codec's exact nested exception class");
        assertThat(failure.getMessage())
                .contains("ends in a plain digit")
                .contains("sign is unknown")
                .contains("not assumed");
    }

    /**
     * Confirms that encoding a negative value into a declared-unsigned display field is rejected.
     *
     * <p>Takes no parameters and returns no value. The expected failure is an exact
     * {@link ZonedDecimalException}, raised inside the assertion lambda and captured there, so this
     * method itself completes normally; JUnit reports a missing or differently typed exception as a
     * test failure.</p>
     *
     * <p>Assumptions: this encode-side refusal is the actual signedness-incompatibility path. There is
     * no decode-side negative-overpunch equivalent for an unsigned field because all of its characters
     * are validated as plain digits and therefore carry no sign to interpret.</p>
     */
    @Test
    void negativeValueBoundForAnUnsignedFieldIsRejectedOnEncode() {
        BigDecimal negativeIdentifier = new BigDecimal("-1");
        ZonedDecimalException failure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.encode(negativeIdentifier,
                        MERCHANT_ID_DIGITS, NO_DEC_DIGITS, UNSIGNED));

        assertEquals(ZonedDecimalException.class, failure.getClass(),
                "an unsigned negative must raise the codec's exact nested exception class");
        assertThat(failure.getMessage())
                .contains("unsigned zoned field cannot carry a negative value")
                .contains("has no sign carrier")
                .doesNotContain(negativeIdentifier.toPlainString());
    }

    /**
     * Confirms that a magnitude needing more digit positions than the declared field is rejected
     * without truncating either end.
     *
     * <p>Takes no parameters and returns no value. The expected failure is an exact
     * {@link ZonedDecimalException}, raised inside the assertion lambda and captured there, so this
     * method itself completes normally; JUnit reports a missing or differently typed exception or
     * differing width as a test failure.</p>
     *
     * <p>Assumptions: the positive whole number has ten digits and is bound for
     * {@code TRAN-MERCHANT-ID PIC 9(09)}, so signedness and fractional precision are both valid before
     * the magnitude check is reached.</p>
     */
    @Test
    void magnitudeWiderThanTheFieldIsRejectedOnEncode() {
        BigDecimal wideIdentifier = new BigDecimal("1234567890");
        ZonedDecimalException failure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.encode(wideIdentifier,
                        MERCHANT_ID_DIGITS, NO_DEC_DIGITS, UNSIGNED));

        assertEquals(ZonedDecimalException.class, failure.getClass(),
                "a wide magnitude must raise the codec's exact nested exception class");
        assertThat(failure.getMessage())
                .contains("needs 10 digit positions")
                .contains("field provides 9")
                .contains("intDigits=9")
                .contains("decDigits=0")
                .doesNotContain(wideIdentifier.toPlainString());
    }

    /**
     * Confirms that a value carrying non-zero precision beyond {@code decDigits} is rejected rather
     * than rounded or truncated.
     *
     * <p>Takes no parameters and returns no value. The expected failure is an exact
     * {@link ZonedDecimalException}, raised inside the assertion lambda and captured there, so this
     * method itself completes normally; JUnit reports a missing or differently typed exception or
     * any silently encoded result as a test failure.</p>
     *
     * <p>Trade-offs: a caller must make any business rounding decision before invoking the codec.
     * Requiring that extra explicit step is preferable to a representation layer silently dropping a
     * fraction of a cent, because the chosen rule then remains visible at the business boundary that
     * owns it.</p>
     */
    @Test
    void precisionBeyondTheFractionalWidthIsRejectedRatherThanRounded() {
        BigDecimal overPrecise = new BigDecimal("504.771");
        ZonedDecimalException failure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.encode(overPrecise,
                        TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED));

        assertEquals(ZonedDecimalException.class, failure.getClass(),
                "excess precision must raise the codec's exact nested exception class");
        assertThat(failure.getMessage())
                .contains("more than 2 decimal places")
                .contains("cannot hold without dropping precision")
                .doesNotContain(overPrecise.toPlainString());
    }

    /**
     * Confirms that an absent value is rejected before scale, sign or magnitude processing begins.
     *
     * <p>Takes no parameters and returns no value. The expected failure is an exact
     * {@link ZonedDecimalException}, raised inside the assertion lambda and captured there, so this
     * method itself completes normally; JUnit reports a missing or differently typed exception as a
     * test failure.</p>
     *
     * <p>Assumptions: valid transaction-amount geometry is supplied so absence is the first and only
     * value defect, independently of the metadata-precedence case below.</p>
     */
    @Test
    void absentValueIsRejectedOnEncode() {
        ZonedDecimalException failure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.encode(
                        null, TRANSACTION_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, SIGNED));

        assertEquals(ZonedDecimalException.class, failure.getClass(),
                "an absent value must raise the codec's exact nested exception class");
        assertThat(failure.getMessage())
                .contains("zoned field value is absent");
    }

    /**
     * Confirms that invalid digit metadata is rejected before an absent span can be examined.
     *
     * <p>Takes no parameters and returns no value. Both expected failures are exact
     * {@link ZonedDecimalException} instances raised inside assertion lambdas and captured there, so
     * this method itself completes normally; JUnit reports a missing or differently typed exception,
     * or an absence error winning precedence, as a test failure.</p>
     *
     * <p>Assumptions: null is deliberately paired with each malformed digit pair. If span validation
     * ran first these calls would report absence; reporting negative counts and zero total instead
     * proves that geometry is validated before content.</p>
     */
    @Test
    void invalidDigitCountMetadataIsRejectedBeforeTheSpanIsEvenRead() {
        ZonedDecimalException negativeCount = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.decode(null, -1, MONEY_DEC_DIGITS, SIGNED));
        assertEquals(ZonedDecimalException.class, negativeCount.getClass(),
                "negative metadata must raise the codec's exact nested exception class");
        assertThat(negativeCount.getMessage())
                .contains("digit counts must not be negative")
                .contains("intDigits=-1")
                .doesNotContain("span is absent");

        ZonedDecimalException zeroWidth = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.decode(null, 0, 0, SIGNED));
        assertEquals(ZonedDecimalException.class, zeroWidth.getClass(),
                "zero-width metadata must raise the codec's exact nested exception class");
        assertThat(zeroWidth.getMessage())
                .contains("must declare at least one digit position")
                .contains("sum to zero")
                .doesNotContain("span is absent");
    }

    /**
     * Confirms that a sensitive field diagnostic names safe geometry and never echoes raw field
     * content.
     *
     * <p>Takes no parameters and returns no value. The expected failure is an exact
     * {@link ZonedDecimalException}, raised through the production field-aware overload inside the
     * assertion lambda and captured there, so this method itself completes normally; JUnit reports a
     * missing or differently typed exception, missing geometry or disclosed content as a test
     * failure.</p>
     *
     * <p>Assumptions: {@code CUST-SSN} is taken from the real {@code CUSTOMER} layout because it is a
     * sensitive unsigned-display field that reaches this codec. The malformed span is SYNTHETIC and
     * contains no real identifier; using a visibly fake value keeps the test safe even if the masking
     * assertion itself regresses.</p>
     */
    @Test
    void aSensitiveFieldDiagnosticNamesTheFieldAndWithholdsItsContent() {
        FieldSpec sensitiveField = CopybookLayout.layout("CUSTOMER").field("CUST-SSN");
        FieldContext context = new FieldContext(
                sensitiveField.name(),
                sensitiveField.start(),
                sensitiveField.length(),
                sensitiveField.kind().name(),
                sensitiveField.sensitive());
        String syntheticSensitiveSpan = "55555555X";

        assertEquals(Kind.UINT, sensitiveField.kind(),
                "the real sensitive field must use the unsigned-display path");
        assertTrue(sensitiveField.sensitive(),
                "the real CUST-SSN descriptor must require diagnostic masking");

        ZonedDecimalException failure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.decode(
                        syntheticSensitiveSpan,
                        sensitiveField.length(),
                        NO_DEC_DIGITS,
                        sensitiveField.signed(),
                        context));

        assertEquals(ZonedDecimalException.class, failure.getClass(),
                "a sensitive body defect must raise the codec's exact nested exception class");
        assertThat(failure.getMessage())
                .contains("CUST-SSN")
                .contains("zero-based offset 279")
                .contains("length 9")
                .contains("kind UINT")
                .contains("zero-based index 8")
                .doesNotContain(syntheticSensitiveSpan);
    }

    /**
     * Proves the deterministic decode order: width before body, body before final-character
     * classification, and an unrecognised final character before the distinct plain-digit refusal.
     *
     * <p>Takes no parameters and returns no value. Every expected failure is an exact
     * {@link ZonedDecimalException} raised inside an assertion lambda and captured there, so this
     * method itself completes normally; JUnit reports a missing or differently typed exception, a
     * later defect winning precedence or a differing final successful value as a test failure.</p>
     *
     * <p>Assumptions: each successive input removes exactly the defect the preceding assertion had to
     * report while retaining defects belonging to later stages. The ladder therefore proves order,
     * whereas isolated one-defect cases prove only that each validation exists.</p>
     */
    @Test
    void decodeValidationOrderIsLengthThenDigitBodyThenFinalCharacter() {
        ZonedDecimalException lengthFailure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.decode(
                        "X00000504*YZ",
                        TRANSACTION_MONEY_INT_DIGITS,
                        MONEY_DEC_DIGITS,
                        SIGNED));
        assertEquals(ZonedDecimalException.class, lengthFailure.getClass(),
                "the multi-defect long span must raise the codec's exact nested exception class");
        assertThat(lengthFailure.getMessage())
                .contains("expected 11 characters")
                .contains("span holds 12")
                .doesNotContain("digit body")
                .doesNotContain(POSITIVE_OVERPUNCH_TABLE);

        ZonedDecimalException bodyFailure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.decode(
                        "X00000504*Y",
                        TRANSACTION_MONEY_INT_DIGITS,
                        MONEY_DEC_DIGITS,
                        SIGNED));
        assertEquals(ZonedDecimalException.class, bodyFailure.getClass(),
                "the width-correct body defect must raise the codec's exact nested exception class");
        assertThat(bodyFailure.getMessage())
                .contains("zero-based index 0")
                .contains("digit body")
                .doesNotContain(POSITIVE_OVERPUNCH_TABLE);

        ZonedDecimalException overpunchFailure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.decode(
                        "0000005047*",
                        TRANSACTION_MONEY_INT_DIGITS,
                        MONEY_DEC_DIGITS,
                        SIGNED));
        assertEquals(ZonedDecimalException.class, overpunchFailure.getClass(),
                "the body-clean final defect must raise the codec's exact nested exception class");
        assertThat(overpunchFailure.getMessage())
                .contains(POSITIVE_OVERPUNCH_TABLE)
                .contains(NEGATIVE_OVERPUNCH_TABLE)
                .doesNotContain("digit body");

        ZonedDecimalException plainDigitFailure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.decode(
                        "00000050477",
                        TRANSACTION_MONEY_INT_DIGITS,
                        MONEY_DEC_DIGITS,
                        SIGNED));
        assertEquals(ZonedDecimalException.class, plainDigitFailure.getClass(),
                "the body-clean plain digit must raise the codec's exact nested exception class");
        assertThat(plainDigitFailure.getMessage())
                .contains("plain digit")
                .contains("sign is unknown")
                .doesNotContain("digit body");

        assertEquals(new BigDecimal("504.77"),
                ZonedDecimalCodec.decode(
                        TRAN_AMT_OVERPUNCH_G,
                        TRANSACTION_MONEY_INT_DIGITS,
                        MONEY_DEC_DIGITS,
                        SIGNED),
                "removing the final defect must reveal the live successful value");
    }

    /**
     * Proves the deterministic encode order: fractional precision before unsigned signedness,
     * unsigned signedness before magnitude, and magnitude before successful left padding.
     *
     * <p>Takes no parameters and returns no value. Every expected failure is an exact
     * {@link ZonedDecimalException} raised inside an assertion lambda and captured there, so this
     * method itself completes normally; JUnit reports a missing or differently typed exception, a
     * later defect winning precedence or a differing final encoded span as a test failure.</p>
     *
     * <p>Assumptions: each successive value removes exactly one defect while keeping the ones whose
     * checks follow it. All values target the real nine-byte unsigned merchant-identifier geometry,
     * so the ladder exercises production metadata rather than an arbitrary width.</p>
     */
    @Test
    void encodeValidationOrderIsPrecisionThenSignednessThenMagnitude() {
        BigDecimal excessivePrecision = new BigDecimal("-12345678901.234");
        ZonedDecimalException precisionFailure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.encode(
                        excessivePrecision,
                        MERCHANT_ID_DIGITS,
                        NO_DEC_DIGITS,
                        UNSIGNED));
        assertEquals(ZonedDecimalException.class, precisionFailure.getClass(),
                "the multi-defect precise value must raise the codec's exact nested exception class");
        assertThat(precisionFailure.getMessage())
                .contains("more than 0 decimal places")
                .doesNotContain("unsigned zoned field")
                .doesNotContain("digit positions");

        BigDecimal negativeAndWide = new BigDecimal("-12345678901");
        ZonedDecimalException signednessFailure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.encode(
                        negativeAndWide,
                        MERCHANT_ID_DIGITS,
                        NO_DEC_DIGITS,
                        UNSIGNED));
        assertEquals(ZonedDecimalException.class, signednessFailure.getClass(),
                "the whole negative value must raise the codec's exact nested exception class");
        assertThat(signednessFailure.getMessage())
                .contains("unsigned zoned field cannot carry a negative value")
                .doesNotContain("digit positions");

        BigDecimal wide = new BigDecimal("12345678901");
        ZonedDecimalException magnitudeFailure = assertThrows(ZonedDecimalException.class,
                () -> ZonedDecimalCodec.encode(
                        wide,
                        MERCHANT_ID_DIGITS,
                        NO_DEC_DIGITS,
                        UNSIGNED));
        assertEquals(ZonedDecimalException.class, magnitudeFailure.getClass(),
                "the positive wide value must raise the codec's exact nested exception class");
        assertThat(magnitudeFailure.getMessage())
                .contains("needs 11 digit positions")
                .contains("field provides 9")
                .doesNotContain("unsigned zoned field");

        assertEquals("123456789",
                ZonedDecimalCodec.encode(
                        new BigDecimal("123456789"),
                        MERCHANT_ID_DIGITS,
                        NO_DEC_DIGITS,
                        UNSIGNED),
                "removing the magnitude defect must reveal the successful unsigned span");
    }
}
