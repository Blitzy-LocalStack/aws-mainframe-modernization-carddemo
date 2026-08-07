package com.carddemo.batch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins what the batch-service entity renderings may and may not carry into a log line.
 *
 * <p>Four entities are covered, for four related reasons. {@link CardXref} is covered because a
 * cross-reference row's whole content <em>is</em> the linkage between a card number, a customer and an
 * account, so a rendering that carried all three would reproduce the cross-reference file itself in
 * plain text somewhere no migration control governs. {@link Transaction} is covered because its
 * rendering already withheld the card number on the stated ground that a batch emits one line per
 * record across a whole daily feed -- reasoning that applies unchanged to the transaction amount and
 * had not been applied to it. {@link Account} and {@link TransactionCategoryBalance} are covered
 * because each rendered a protected value while arguing in its own Javadoc that it did not: one that
 * nothing in the account record needs masking, the other that "a running balance is not itself
 * protected". The sensitive-data logging contract in {@code docs/architecture/observability.md} names
 * account identifiers explicitly and covers persistence-bound values as a class, so both arguments
 * were wrong and both renderings disclosed. A confident rationale is exactly what stops a reader
 * re-examining a rendering, which is why these two are pinned by test rather than by comment.
 *
 * <p>Assumptions: the assertions name the values that must be <em>absent</em> rather than checking the
 * shape of what is present. That direction is deliberate: a rendering can only regress by gaining a
 * member, and an assertion on presence cannot detect a gain. Each negative assertion therefore fails
 * exactly when a withheld value returns, which is the only failure mode these overrides exist to
 * prevent.
 *
 * <p>Alternatives Considered: asserting on a captured log record through a logging test appender
 * instead of on {@code toString} directly. Rejected, because the disclosure decision lives in the
 * override and not in any one call site; testing through an appender would prove one caller behaves
 * and would say nothing about the framework-internal and exception-message paths that invoke
 * {@code toString} implicitly, which are the paths that make this a hazard in the first place.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the parameterised methods below carry their own.
 */
class DiagnosticRenderingTest {

    /**
     * A card number from the published CardDemo demonstration seed, row 7 of
     * {@code app/data/ASCII/cardxref.txt}.
     *
     * <p>Assumptions: a seed value is used rather than a minted one so the constant is verifiable, and
     * it is a demonstration value that identifies no real person and no real account.
     */
    private static final String SEED_CARD_NUMBER = "4859452612877065";

    /**
     * The last four digits of {@link #SEED_CARD_NUMBER}, the one fragment a rendering may carry.
     *
     * <p>Assumptions: four is the suffix width the migration's disclosure rule names, so this is the
     * boundary between what is permitted and what is not.
     */
    private static final String PERMITTED_SUFFIX = "7065";

    /**
     * The leading twelve digits of {@link #SEED_CARD_NUMBER}, which no rendering may carry.
     *
     * <p>Assumptions: asserting the absence of the full sixteen digits alone would pass against a
     * rendering that emitted the first twelve and masked the last four -- the inverse of the rule.
     * This constant closes that gap.
     */
    private static final String FORBIDDEN_PREFIX = "485945261287";

    /**
     * A synthetic eleven-digit account identifier, at the declared width of {@code ACCT-ID PIC 9(11)}.
     *
     * <p>Alternatives Considered: reusing the account identifier from the seed row that
     * {@link #SEED_CARD_NUMBER} belongs to, which is {@code 7}. Rejected on measurement rather than
     * on principle: a one-digit value makes every absence assertion in this class near-vacuous,
     * because the digit 7 already occurs inside the permitted card-number suffix and inside several
     * amounts, so {@code doesNotContain} would fail against a compliant rendering and would have to be
     * weakened to compensate. A distinctive eleven-digit value cannot collide with any other token a
     * rendering emits, which is what makes the absence assertions mean what they say.
     *
     * <p>Assumptions: the digits are authored rather than extracted, so they identify no real account
     * and correspond to no seed row. Nothing in this class depends on the value existing anywhere
     * else -- these cases assert on a rendering, not on a lookup.
     */
    private static final Long SYNTHETIC_ACCOUNT_ID = 21_820_493_291L;

    /**
     * Confirms a cross-reference rendering carries the card-number suffix and nothing else.
     *
     * <p>Assumptions: the customer and account identifiers are asserted absent individually rather
     * than by counting rendered fields, because the hazard is the linkage: any two of the three
     * values appearing together is already the disclosure the override exists to prevent, and a field
     * count cannot express that.
     */
    @Test
    void cardXrefRendersTheCardNumberSuffixAndNeitherIdentifier() {
        CardXref crossReference = new CardXref(SEED_CARD_NUMBER, 9L, 11L);

        String rendered = crossReference.toString();

        assertThat(rendered).contains(PERMITTED_SUFFIX);
        assertThat(rendered).doesNotContain(SEED_CARD_NUMBER);
        assertThat(rendered).doesNotContain(FORBIDDEN_PREFIX);
        assertThat(rendered).doesNotContain("customerId");
        assertThat(rendered).doesNotContain("accountId");
    }

    /**
     * Confirms a card number too short to have a four-digit suffix renders as absent, not in part.
     *
     * <p>Assumptions: a partial value from a short or unpopulated field would disclose the whole of
     * whatever it holds while reading like the suffix of something longer, which is worse than
     * disclosing nothing because it is also misleading.
     *
     * @param shortCardNumber a card number shorter than the four-digit suffix width
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1", "12", "123"})
    void cardXrefRendersAbsentRatherThanPartialWhenTheNumberIsShorterThanTheSuffix(
            String shortCardNumber) {
        String rendered = new CardXref(shortCardNumber, 9L, 11L).toString();

        assertThat(rendered).isEqualTo("CardXref[cardNumSuffix=none]");
    }

    /**
     * Confirms an unpopulated card number renders as absent rather than as the literal {@code null}.
     *
     * <p>Assumptions: an entity read before its card number is set is a real state -- a JPA proxy
     * mid-population, or a row whose column is blank -- and a rendering that emitted {@code null}
     * there would be indistinguishable from a rendering that emitted a value, which defeats reading
     * the log at all.
     *
     * @param absentCardNumber always {@code null}, supplied by the null source
     */
    @ParameterizedTest
    @NullSource
    void cardXrefRendersAbsentWhenTheCardNumberIsUnpopulated(String absentCardNumber) {
        String rendered = new CardXref(absentCardNumber, 9L, 11L).toString();

        assertThat(rendered).isEqualTo("CardXref[cardNumSuffix=none]");
        assertThat(rendered).doesNotContain("null");
    }

    /**
     * Confirms a batch transaction rendering carries neither the card number nor the amount.
     *
     * <p>Assumptions: the amount is asserted absent in both its plain and its grouped form, because
     * a future rendering that formatted it for readability would still disclose it, and an assertion
     * against one spelling alone would pass.
     */
    @Test
    void batchTransactionRendersNeitherTheCardNumberNorTheAmount() {
        Transaction transaction = new Transaction("0000000000683580");
        transaction.setTypeCd("01");
        transaction.setCategoryCd("0001");
        transaction.setCardNum(SEED_CARD_NUMBER);
        transaction.setAmount(new BigDecimal("1504.77"));
        transaction.setProcTs(LocalDateTime.of(2022, 7, 18, 0, 0, 0));

        String rendered = transaction.toString();

        assertThat(rendered).contains("transactionId=0000000000683580");
        assertThat(rendered).contains("typeCd=01");
        assertThat(rendered).contains("categoryCd=0001");
        assertThat(rendered).doesNotContain(SEED_CARD_NUMBER);
        assertThat(rendered).doesNotContain(PERMITTED_SUFFIX);
        assertThat(rendered).doesNotContain("1504.77");
        assertThat(rendered).doesNotContain("1,504.77");
    }

    /**
     * Confirms an account rendering carries neither the account identifier nor any monetary value.
     *
     * <p>Assumptions: the balance and the credit limit are asserted absent as well as the identifier,
     * because the logging contract covers persistence-bound values as a class and both are the
     * persisted content of the account master. Asserting the identifier alone would pass against a
     * rendering that withheld the key and published the money.
     *
     * <p>Assumptions: each monetary value is asserted absent in both its plain and its
     * thousands-grouped spelling, for the same reason the transaction case above does it -- a future
     * rendering that formatted a figure for readability would still disclose it, and an assertion
     * against one spelling would pass.
     */
    @Test
    void accountRendersNeitherTheIdentifierNorAnyMonetaryValue() {
        Account account = new Account(
                SYNTHETIC_ACCOUNT_ID,
                "Y",
                new BigDecimal("1504.77"),
                new BigDecimal("25000.00"),
                new BigDecimal("5000.00"),
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2024, 1, 1),
                new BigDecimal("100.00"),
                new BigDecimal("200.00"),
                "98101",
                "ZEROAPR");

        String rendered = account.toString();

        assertThat(rendered).contains("activeStatus=Y");
        assertThat(rendered).contains("groupId=ZEROAPR");
        assertThat(rendered).doesNotContain(SYNTHETIC_ACCOUNT_ID.toString());
        assertThat(rendered).doesNotContain("1504.77");
        assertThat(rendered).doesNotContain("1,504.77");
        assertThat(rendered).doesNotContain("25000.00");
        assertThat(rendered).doesNotContain("25,000.00");
    }

    /**
     * Confirms a category-balance rendering carries neither the account identifier nor the balance.
     *
     * <p>Assumptions: both the enclosing rendering and the embedded key's own rendering are asserted,
     * and that is the point of the case rather than thoroughness for its own sake. The enclosing
     * rendering formerly delegated the key to the embedded type, so narrowing only one of the two
     * would have left the identifier reachable through the other.
     */
    @Test
    void categoryBalanceRendersNeitherTheAccountIdentifierNorTheBalance() {
        TransactionCategoryBalance.TransactionCategoryBalanceId id =
                new TransactionCategoryBalance.TransactionCategoryBalanceId(
                        SYNTHETIC_ACCOUNT_ID, "01", "0001");
        TransactionCategoryBalance balance =
                new TransactionCategoryBalance(id, new BigDecimal("1504.77"));

        String renderedBalance = balance.toString();
        String renderedKey = id.toString();

        assertThat(renderedBalance).contains("typeCd='01'");
        assertThat(renderedBalance).contains("categoryCd='0001'");
        assertThat(renderedBalance).doesNotContain(SYNTHETIC_ACCOUNT_ID.toString());
        assertThat(renderedBalance).doesNotContain("1504.77");
        assertThat(renderedBalance).doesNotContain("1,504.77");

        assertThat(renderedKey).contains("typeCd='01'");
        assertThat(renderedKey).contains("categoryCd='0001'");
        assertThat(renderedKey).doesNotContain(SYNTHETIC_ACCOUNT_ID.toString());
    }
}
