package com.carddemo.card.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Pins what a {@link Card} rendering may and may not carry into a log line.
 *
 * <p>This entity is the sharpest case of the migration's sensitive-data logging contract in the whole
 * services tree, because two of its seven members are protected values and one of them is its own
 * primary key. The key is a primary account number; the member beside it is the eleven-digit account
 * identifier that {@code docs/architecture/observability.md} names in its own clause, alongside the
 * primary account number rather than beneath it. An earlier revision of the rendering reasoned
 * carefully about the card number and the enciphered verification value and then rendered the account
 * identifier without remark -- a confident rationale that covered two members and left the third
 * unexamined, which is exactly the failure a comment cannot catch and a test can.</p>
 *
 * <p>Assumptions: the assertions name the values that must be ABSENT rather than checking the shape
 * of what is present. That direction is deliberate: a rendering can only regress by GAINING a member,
 * and an assertion on presence cannot detect a gain. Each negative assertion therefore fails exactly
 * when a withheld value returns, which is the only failure mode this override exists to prevent. A
 * small number of positive assertions accompany them, solely so that a rendering reduced to the empty
 * string could not pass by carrying nothing at all.</p>
 *
 * <p>Alternatives Considered: asserting on a captured log record through a logging test appender
 * rather than on {@code toString} directly. Rejected, because the disclosure decision lives in the
 * override and not in any one call site; an appender test would prove one caller behaves and would say
 * nothing about the framework-internal and exception-message paths that invoke {@code toString}
 * implicitly, and those are the paths that make this a hazard rather than a style question.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the methods below carry their own where they have any.</p>
 */
class DiagnosticRenderingTest {

    /**
     * A card number from the published CardDemo demonstration seed, {@code app/data/ASCII/cardxref.txt}.
     *
     * <p>Assumptions: a seed value is used rather than a minted one so the constant is verifiable
     * against a committed file, and it is a demonstration value that identifies no real person and no
     * real account.</p>
     */
    private static final String SEED_CARD_NUMBER = "4859452612877065";

    /**
     * The leading twelve digits of {@link #SEED_CARD_NUMBER}, which no rendering may carry.
     *
     * <p>Assumptions: asserting the absence of all sixteen digits alone would pass against a rendering
     * that emitted the first twelve and withheld the last four, which is the inverse of the rule and is
     * a worse disclosure than either half suggests. This constant closes that gap.</p>
     */
    private static final String FORBIDDEN_PREFIX = "485945261287";

    /**
     * The last four digits of {@link #SEED_CARD_NUMBER}, which this rendering also may not carry.
     *
     * <p>Refactoring Rationale: four trailing digits are the one card-number fragment the migration's
     * disclosure rule permits, and the sibling contexts' renderings do carry them. This one does not,
     * and the difference is the point of the constant rather than an inconsistency: producing that
     * fragment is masking, masking has a single owner in this context -- {@code com.carddemo.card.mapper}
     * -- and an entity that produced its own slightly different masked form would give one value two
     * renderings with neither authoritative. Asserting the suffix absent is what stops a future edit
     * from quietly moving that responsibility into the entity.</p>
     */
    private static final String PERMITTED_ELSEWHERE_SUFFIX = "7065";

    /**
     * A synthetic eleven-digit account identifier, at the declared width of {@code CARD-ACCT-ID PIC 9(11)}.
     *
     * <p>Alternatives Considered: reusing the account identifier from the seed row that
     * {@link #SEED_CARD_NUMBER} belongs to, which is {@code 7}. Rejected on measurement rather than on
     * principle: a one-digit value makes an absence assertion near-vacuous, because the digit 7 already
     * occurs inside the card number this class also names, so the assertion would have to be weakened
     * to compensate. A distinctive eleven-digit value cannot collide with any other token the rendering
     * emits, which is what makes these absence assertions mean what they say.</p>
     *
     * <p>Assumptions: the digits are authored rather than extracted, so they identify no real account
     * and correspond to no seed row. Nothing here depends on the value existing anywhere else -- these
     * cases assert on a rendering, not on a lookup.</p>
     */
    private static final Long SYNTHETIC_ACCOUNT_ID = 21_820_493_291L;

    /**
     * The embossed name used below, which is descriptive personal data and is not a rendered member.
     *
     * <p>Assumptions: an authored name is used rather than a seed one for the same reason as the
     * identifier above, and because a real seed name would put a person's name into a test source for
     * no assertion that needs it.</p>
     */
    private static final String EMBOSSED_NAME = "CARDHOLDER SPECIMEN NAME";

    /**
     * An expiry date chosen so that none of its digits collide with a value asserted absent.
     *
     * <p>Assumptions: the rendering does carry this date, so a careless choice -- an expiry in 2065,
     * say -- would make the card-number suffix assertion pass or fail for the wrong reason. The year,
     * month and day here share no four-digit run with either card-number constant.</p>
     */
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 31);

    /**
     * Builds a fully populated card carrying every protected value this class asserts absent.
     *
     * <p>Assumptions: the enciphered member is given a real envelope rather than left null, because a
     * null member renders as the literal {@code null} and would let an override that emitted the member
     * pass the rendering assertion by accident.</p>
     *
     * <p>Refactoring Rationale: the envelope is assembled through {@link EncryptedCvv#wrap} rather than
     * from a raw byte array. The entity's attribute is no longer a bare array, so an array is not a
     * value this constructor accepts; building the envelope here keeps this class asserting the
     * rendering property alone rather than restating the value type's framing rules, which
     * {@code EncryptedCvvTest} owns.</p>
     *
     * @return a populated card whose rendering is the subject of every case below
     */
    private static Card populatedCard() {
        return new Card(SEED_CARD_NUMBER, SYNTHETIC_ACCOUNT_ID, populatedCvv(), EMBOSSED_NAME, EXPIRY,
                "Y");
    }

    /**
     * Builds an enciphered verification value at the exact lengths the value type declares.
     *
     * <p>Assumptions: every part is a fixed filler byte rather than real key material, because this
     * class asserts what a rendering omits and never what a decipherment returns. The lengths are read
     * from {@link EncryptedCvv}'s own constants so that a change to the envelope geometry fails at the
     * one place that declares it instead of here.</p>
     *
     * @return an enciphered verification value that is valid envelope-shaped state for the entity
     */
    private static EncryptedCvv populatedCvv() {
        byte[] encipheredDataKey = new byte[184];
        Arrays.fill(encipheredDataKey, (byte) 0xA7);
        byte[] initialisationVector = new byte[EncryptedCvv.INITIALISATION_VECTOR_LENGTH];
        Arrays.fill(initialisationVector, (byte) 0x5C);
        byte[] ciphertext = new byte[EncryptedCvv.MIN_CIPHERTEXT_LENGTH];
        Arrays.fill(ciphertext, (byte) 0x3E);
        return EncryptedCvv.wrap(encipheredDataKey, initialisationVector, ciphertext);
    }

    /**
     * Confirms the rendering carries neither identifier of the record, in whole or in part.
     *
     * <p>Assumptions: the member NAMES are asserted absent alongside the values. A rendering that
     * emitted {@code accountId=null} on a partly built instance would pass a value-only assertion while
     * still announcing that the member is rendered, and the next populated instance would disclose. The
     * name assertion fails on the first edit that reintroduces the member, rather than on the first
     * instance that happens to have populated it.</p>
     */
    @Test
    void cardRendersNeitherTheCardNumberNorTheAccountIdentifier() {
        String rendered = populatedCard().toString();

        assertThat(rendered).doesNotContain(SEED_CARD_NUMBER);
        assertThat(rendered).doesNotContain(FORBIDDEN_PREFIX);
        assertThat(rendered).doesNotContain(PERMITTED_ELSEWHERE_SUFFIX);
        assertThat(rendered).doesNotContain(String.valueOf(SYNTHETIC_ACCOUNT_ID));
        assertThat(rendered).doesNotContain("cardNum");
        assertThat(rendered).doesNotContain("accountId");
    }

    /**
     * Confirms the rendering carries no trace of the enciphered verification value and no embossed name.
     *
     * <p>Assumptions: the verification value has no safe rendering at all, so three separate traces are
     * asserted absent -- its plaintext bytes, the default array rendering that would disclose the
     * cipher's output size, and the member name. The embossed name is asserted absent on the narrower
     * ground that it is descriptive personal data with no diagnostic use: knowing whose card a log line
     * concerns is precisely what the identifiers above are withheld to prevent.</p>
     */
    @Test
    void cardRendersNeitherTheEncipheredValueNorTheEmbossedName() {
        String rendered = populatedCard().toString();

        assertThat(rendered).doesNotContain("ciphertext");
        assertThat(rendered).doesNotContain("[B@");
        assertThat(rendered).doesNotContainIgnoringCase("cvv");
        assertThat(rendered).doesNotContain(EMBOSSED_NAME);
    }

    /**
     * Confirms the rendering still says something, so that no absence assertion above passes vacuously.
     *
     * <p>Assumptions: every other case in this class is a negative assertion, and an override reduced to
     * the empty string would satisfy all of them. This case is the counterweight and asserts the three
     * members that remain: the expiry date, the status code and the concurrency counter, which together
     * say what STATE a card was in without saying which card it was.</p>
     */
    @Test
    void cardRendersTheStateMembersThatRemain() {
        String rendered = populatedCard().toString();

        assertThat(rendered).startsWith("Card[");
        assertThat(rendered).contains("expirationDate=2026-08-31");
        assertThat(rendered).contains("activeStatus=Y");
        assertThat(rendered).contains("version=0");
    }

    /**
     * Confirms an instance the provider has not yet populated renders without raising.
     *
     * <p>Refactoring Rationale: this is the path a rendering is most likely to be taken down and least
     * likely to be tested on. The provider constructs through the no-argument constructor and populates
     * afterwards, so a report of a failed flush or a failed conversion can render an instance whose
     * members are all still absent. A rendering that raised there would replace the diagnostic with a
     * second failure that named nothing about the first.</p>
     *
     * <p>Assumptions: the no-argument constructor is reachable from this case only because the test
     * shares the entity's package, which is why this class is not placed in a test-only package of its
     * own.</p>
     */
    @Test
    void unpopulatedCardRendersWithoutRaising() {
        Card unpopulated = new Card();

        assertThatCode(unpopulated::toString).doesNotThrowAnyException();
        assertThat(unpopulated.toString()).doesNotContain("accountId");
        assertThat(unpopulated.toString()).doesNotContain("cardNum");
    }
}
