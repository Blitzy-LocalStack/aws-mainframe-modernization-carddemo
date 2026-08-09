package com.carddemo.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.carddemo.common.money.Money;
import com.carddemo.reporting.domain.StatementTransactionView.StatementTransactionKey;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * Pins what {@link StatementTransactionView#toString()} may carry into a log line.
 *
 * <p>Refactoring Rationale: this projection's rendering withheld the description and the two merchant
 * free-text attributes, reasoned only about the primary account number, and left the amount and the
 * merchant identifier in. Part of its stated case for the omissions it did make was BREVITY -- two
 * hundred characters "that identify nothing" -- which is the wrong axis: brevity and disclosure are
 * separate concerns, and reaching the right answer on length is what stopped it examining the other two
 * on content. The sensitive-data logging contract in {@code docs/architecture/observability.md} covers
 * persistence-bound values as a class, and both are exactly that. A confident rationale is what stops a
 * reader re-examining a rendering, which is why this is pinned by test rather than by comment.</p>
 *
 * <p>Assumptions: the assertions name the values that must be ABSENT rather than checking the shape of
 * what is present. That direction is deliberate: a rendering can only regress by GAINING a member, and
 * an assertion on presence cannot detect a gain. Positive assertions accompany them solely so that a
 * rendering reduced to the empty string could not pass by carrying nothing at all.</p>
 *
 * <p>Assumptions: each withheld value is asserted absent by its VALUE and by its MEMBER NAME. A
 * rendering emitting {@code amount=null} would pass a value-only assertion while still announcing that
 * the component is rendered, and the next hydrated row would disclose.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the methods below carry their own where they have any.</p>
 */
class DiagnosticRenderingTest {

    /**
     * A card number from the published CardDemo demonstration seed, {@code app/data/ASCII/cardxref.txt}.
     *
     * <p>Assumptions: a seed value is used rather than a minted one so the constant is verifiable
     * against a committed file, and it is a demonstration value that identifies no real person.</p>
     */
    private static final String SEED_CARD_NUMBER = "4859452612877065";

    /**
     * The leading twelve digits of {@link #SEED_CARD_NUMBER}, which no rendering may carry.
     *
     * <p>Assumptions: the key's own rendering keeps the last four digits deliberately, so asserting the
     * absence of all sixteen would pass against a rendering that emitted the first twelve and masked the
     * last four -- the inverse of the rule. This constant is what closes that gap.</p>
     */
    private static final String FORBIDDEN_PREFIX = "485945261287";

    /**
     * The last four digits of {@link #SEED_CARD_NUMBER}, the one card-number fragment permitted here.
     */
    private static final String PERMITTED_SUFFIX = "7065";

    /**
     * The statement-line amount the hydrated row carries, which the rendering must not name.
     */
    private static final String LINE_AMOUNT = "1234.56";

    /**
     * A distinctive nine-digit merchant identifier, at the declared width of {@code TRNX-MERCHANT-ID}.
     *
     * <p>Alternatives Considered: an identifier of repeated zeros, which is what a seed row carries.
     * Rejected on measurement: a run of zeros collides with the zero-padded transaction identifier this
     * row also carries, so the absence assertion would fail against a compliant rendering and would have
     * to be weakened until it asserted nothing.</p>
     */
    private static final Long MERCHANT_ID = 748_113_902L;

    /**
     * An eleven-digit account identifier at the declared width of {@code ACCT-ID}.
     *
     * <p>Alternatives Considered: reusing a seed account identifier from
     * {@code app/data/ASCII/acctdata.txt}. Rejected on measurement rather than on principle: the seed
     * identifiers are zero-padded, and a run of zeros collides with the zero-padded transaction
     * identifier and with the {@code 0001} category code these cases also carry, so an absence
     * assertion over one would have to be weakened until it asserted nothing.</p>
     */
    private static final Long SEED_ACCOUNT_ID = 47_193_820_615L;

    /**
     * A nine-digit customer identifier at the declared width of {@code CUST-ID}.
     *
     * <p>Assumptions: distinct from {@link #SEED_ACCOUNT_ID} in every digit position that matters, so a
     * rendering that emitted one and withheld the other cannot pass both absence assertions by
     * accident.</p>
     */
    private static final Long SEED_CUSTOMER_ID = 356_812_477L;

    /**
     * Confirms the projection renders no amount and no merchant identifier.
     *
     * <p>Assumptions: the two are asserted in one case because they were introduced by one omission and
     * one argument, so a fix that addressed only one of them should fail here rather than half-pass.</p>
     */
    @Test
    void theProjectionRendersNeitherTheAmountNorTheMerchantIdentifier() {
        String rendered = hydratedRow().toString();

        assertThat(rendered).doesNotContain(LINE_AMOUNT);
        assertThat(rendered).doesNotContain("amount");
        assertThat(rendered).doesNotContain(String.valueOf(MERCHANT_ID));
        assertThat(rendered).doesNotContain("merchantId");
    }

    /**
     * Confirms the projection renders no free-text attribute and no unmasked card number.
     *
     * <p>Assumptions: the three free-text attributes were already withheld and are asserted anyway,
     * because the earlier rationale rested part of its case for withholding them on their LENGTH rather
     * than on their content. An argument from length would be satisfied by a rendering that truncated
     * them, so an assertion is what holds the omission to content.</p>
     */
    @Test
    void theProjectionRendersNoFreeTextAndNoUnmaskedCardNumber() {
        String rendered = hydratedRow().toString();

        assertThat(rendered).doesNotContain("COFFEE AND PASTRIES");
        assertThat(rendered).doesNotContain("SPECIMEN MERCHANT");
        assertThat(rendered).doesNotContain("SPRINGFIELD");
        assertThat(rendered).doesNotContain(SEED_CARD_NUMBER);
        assertThat(rendered).doesNotContain(FORBIDDEN_PREFIX);
    }

    /**
     * Confirms the surviving components are rendered, so no absence assertion above passes vacuously.
     *
     * <p>Assumptions: the source and the processing timestamp survive on purpose. A ten-character source
     * code is a closed reference domain, and the sibling {@link ReportTransactionView} renders its own
     * processing timestamp, so keeping both leaves the two projections of the same base table
     * consistent. The masked card-number suffix reaches this rendering only through the embedded key,
     * which is why it is asserted here rather than in a case of its own.</p>
     */
    @Test
    void theProjectionRendersTheSurvivingComponents() {
        String rendered = hydratedRow().toString();

        assertThat(rendered).startsWith("StatementTransactionView[");
        assertThat(rendered).contains(PERMITTED_SUFFIX);
        assertThat(rendered).contains("typeCode=01");
        assertThat(rendered).contains("categoryCode=0001");
        assertThat(rendered).contains("source=POS");
        assertThat(rendered).contains("processingTimestamp=");
    }

    /**
     * Confirms a projection the provider has not yet hydrated renders without raising.
     *
     * <p>Refactoring Rationale: this is the path a rendering is most likely to be taken down and least
     * likely to be tested on. The provider constructs through the no-argument constructor and populates
     * afterwards, so a report of a failed conversion can render a row whose members are all still
     * absent. A rendering that raised there would replace the diagnostic with a second failure naming
     * nothing about the first.</p>
     */
    @Test
    void anUnhydratedProjectionRendersWithoutRaising() {
        StatementTransactionView unhydrated = new StatementTransactionView();

        assertThatCode(unhydrated::toString).doesNotThrowAnyException();
        assertThat(unhydrated.toString()).doesNotContain("amount");
        assertThat(unhydrated.toString()).doesNotContain("merchantId");
    }

    /**
     * Confirms the account projection renders no account identifier and no monetary figure.
     *
     * <p>Assumptions: the account identifier is asserted absent by its VALUE and by its MEMBER NAME,
     * for the reason this class states for every withheld value -- a rendering emitting
     * {@code accountId=null} would pass a value-only assertion while announcing that the component is
     * rendered, and the next hydrated row would disclose.</p>
     *
     * <p>Refactoring Rationale: this case exists because the rendering carried the account identifier
     * and argued for it from a baseline statement print. A rendering defended in its own Javadoc is
     * exactly the one a reader stops re-examining, so the rule is pinned here where an argument cannot
     * reach it.</p>
     */
    @Test
    void theAccountProjectionRendersNeitherTheIdentifierNorAnyMoney() {
        AccountView row = new AccountView();
        set(AccountView.class, row, "accountId", SEED_ACCOUNT_ID);
        set(AccountView.class, row, "activeStatus", "Y");

        String rendered = row.toString();

        assertThat(rendered).doesNotContain(String.valueOf(SEED_ACCOUNT_ID));
        assertThat(rendered).doesNotContain("accountId");
        assertThat(rendered).doesNotContain("Balance");
        assertThat(rendered).doesNotContain("creditLimit");
        assertThat(rendered).startsWith("AccountView[");
        assertThat(rendered).contains("activeStatus=Y");
    }

    /**
     * Confirms the customer projection renders no identifier and no personal data.
     *
     * <p>Assumptions: the rendering is expected to name its type and nothing else, which is what the
     * observability authority prescribes for a type left with no member it may disclose. The exact
     * string is asserted rather than a set of absences alone, because for this type the complete
     * absence IS the contract and an assertion listing eleven absences would silently tolerate a
     * twelfth member being added and rendered.</p>
     */
    @Test
    void theCustomerProjectionRendersNoIdentifierAndNoPersonalData() {
        CustomerView row = new CustomerView();
        set(CustomerView.class, row, "customerId", SEED_CUSTOMER_ID);
        set(CustomerView.class, row, "firstName", "SPECIMEN");
        set(CustomerView.class, row, "lastName", "HOLDER");
        set(CustomerView.class, row, "addressLine1", "1 SPECIMEN WAY");
        set(CustomerView.class, row, "dateOfBirth", LocalDate.of(1970, 3, 4));

        String rendered = row.toString();

        assertThat(rendered).isEqualTo("CustomerView[]");
        assertThat(rendered).doesNotContain(String.valueOf(SEED_CUSTOMER_ID));
        assertThat(rendered).doesNotContain("customerId");
        assertThat(rendered).doesNotContain("SPECIMEN");
        assertThat(rendered).doesNotContain("HOLDER");
        assertThat(rendered).doesNotContain("1970");
    }

    /**
     * Confirms the cross-reference projection renders neither identifier nor any part of the card.
     *
     * <p>Assumptions: the forbidden card prefix and the last four digits are BOTH asserted absent on
     * this type, unlike on the statement projection above where the last four are permitted. The
     * difference is not an inconsistency: the statement key has no other way to say which row it
     * describes, which is the narrow condition under which the observability authority sanctions the
     * masked form, whereas this projection is permitted to disclose nothing at all.</p>
     */
    @Test
    void theCrossReferenceProjectionRendersNoIdentifierAndNoCardFragment() {
        CardXrefView row = new CardXrefView();
        set(CardXrefView.class, row, "cardNum", SEED_CARD_NUMBER);
        set(CardXrefView.class, row, "customerId", SEED_CUSTOMER_ID);
        set(CardXrefView.class, row, "accountId", SEED_ACCOUNT_ID);

        String rendered = row.toString();

        assertThat(rendered).isEqualTo("CardXrefView[]");
        assertThat(rendered).doesNotContain(FORBIDDEN_PREFIX);
        assertThat(rendered).doesNotContain(PERMITTED_SUFFIX);
        assertThat(rendered).doesNotContain(String.valueOf(SEED_CUSTOMER_ID));
        assertThat(rendered).doesNotContain(String.valueOf(SEED_ACCOUNT_ID));
        assertThat(rendered).doesNotContain("accountId");
        assertThat(rendered).doesNotContain("customerId");
    }

    /**
     * Confirms an unhydrated instance of each cross-context projection renders without raising.
     *
     * <p>Assumptions: a diagnostic method must not throw, because it is reached from a failure path
     * where an instance may be partly populated. The three types are checked together because they
     * share the rule and would fail it for the same reason.</p>
     */
    @Test
    void anUnhydratedCrossContextProjectionRendersWithoutRaising() {
        assertThatCode(() -> new AccountView().toString()).doesNotThrowAnyException();
        assertThatCode(() -> new CustomerView().toString()).doesNotThrowAnyException();
        assertThatCode(() -> new CardXrefView().toString()).doesNotThrowAnyException();
    }

    /**
     * Builds a fully hydrated projection carrying every value this class asserts absent.
     *
     * <p>Assumptions: the members are set through field access rather than through a constructor because
     * this type declares neither a public constructor nor any mutator -- it is a read projection the
     * persistence provider populates by field access, and this reproduces that path rather than
     * inventing a second one. The protected no-argument constructor is reachable because this test shares
     * the projection's package.</p>
     *
     * @return a hydrated projection whose rendering is the subject of the cases above
     */
    private static StatementTransactionView hydratedRow() {
        StatementTransactionView row = new StatementTransactionView();
        set(row, "key", new StatementTransactionKey(SEED_CARD_NUMBER, "TRN0000000000001"));
        set(row, "typeCode", "01");
        set(row, "categoryCode", "0001");
        set(row, "source", "POS");
        set(row, "description", "COFFEE AND PASTRIES");
        set(row, "amount", Money.of(LINE_AMOUNT));
        set(row, "merchantId", MERCHANT_ID);
        set(row, "merchantName", "SPECIMEN MERCHANT");
        set(row, "merchantCity", "SPRINGFIELD");
        set(row, "processingTimestamp", LocalDateTime.of(2026, 1, 16, 14, 30, 0));
        return row;
    }

    /**
     * Assigns one declared member of a projection by field access.
     *
     * <p>Assumptions: a failure to reach a member is rethrown as an unchecked failure rather than
     * reported as a test assertion, because a missing or renamed member is a fault in the fixture and not
     * a disclosure the cases above are measuring. Surfacing it as a distinct failure keeps the two
     * apart.</p>
     *
     * @param row the projection to populate
     * @param memberName the declared field name to assign
     * @param value the value to assign to that field
     * @throws IllegalStateException if the projection declares no such member, or if it cannot be
     *     assigned
     */
    private static void set(StatementTransactionView row, String memberName, Object value) {
        set(StatementTransactionView.class, row, memberName, value);
    }

    /**
     * Assigns one declared member of any projection in this package by field access.
     *
     * <p>Refactoring Rationale: the single-type helper above delegates here rather than the two
     * existing side by side. Four projections are populated by this class and they share no supertype
     * that declares their members, so one generic assignment is the alternative to four near-identical
     * ones -- and four copies of a reflective setter is four places for the "member is not assignable"
     * diagnostic to drift out of step.</p>
     *
     * <p>Assumptions: a failure to reach a member is rethrown as an unchecked failure rather than
     * reported as a test assertion, for the same reason the single-type helper gives: a missing or
     * renamed member is a fault in the fixture and not a disclosure the cases above are measuring.</p>
     *
     * @param type the projection class declaring the member
     * @param row the projection instance to populate
     * @param memberName the declared field name to assign
     * @param value the value to assign to that field
     * @throws IllegalStateException if the projection declares no such member, or if it cannot be
     *     assigned
     */
    private static void set(Class<?> type, Object row, String memberName, Object value) {
        try {
            Field member = type.getDeclaredField(memberName);
            member.setAccessible(true);
            member.set(row, value);
        } catch (NoSuchFieldException | IllegalAccessException failure) {
            throw new IllegalStateException(
                    type.getSimpleName() + " member " + memberName + " is not assignable", failure);
        }
    }
}
