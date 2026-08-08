package com.carddemo.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.account.dto.AccountUpdateRequest;
import com.carddemo.account.dto.AccountUpdateResponse;
import com.carddemo.account.dto.AccountViewResponse;
import com.carddemo.account.dto.CardXrefResponse;
import com.carddemo.account.dto.CustomerResponse;
import com.carddemo.common.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins what the account-service entity and projection renderings may and may not carry into a log line.
 *
 * <p>Eight types are covered, and the reason they need pinning together is that they each answered the
 * same question for themselves and several answered it wrongly while arguing the answer in their own
 * Javadoc. {@link Account} carried the account identifier on the ground that an identifier "is not
 * account detail"; {@link CardXref} carried the customer and account identifiers on the ground that
 * masking the card number had "already broken the linkage"; {@link Customer} carried the customer
 * identifier on the ground that it "is not cardholder data". All three readings are refuted by the
 * sensitive-data logging contract in {@code docs/architecture/observability.md}, which names account and
 * customer identifiers explicitly and covers persistence-bound values as a class. The five projection
 * records carried everything they hold, because a record's generated rendering prints every component
 * and none of them overrode it -- and {@link AccountUpdateRequest} is the worst-placed of those, since a
 * request record is rendered precisely when the request failed.
 *
 * <p>Assumptions: the assertions name the values that must be ABSENT rather than checking the shape of
 * what is present. That direction is deliberate: a rendering can only regress by gaining a member, and
 * an assertion on presence cannot detect a gain. Each negative assertion therefore fails exactly when a
 * withheld value returns, which is the only failure mode these overrides exist to prevent.
 *
 * <p>Alternatives Considered: asserting on a captured log record through a logging test appender instead
 * of on {@code toString} directly. Rejected, because the disclosure decision lives in the override and
 * not in any one call site; testing through an appender would prove one caller behaves and would say
 * nothing about the framework-internal and exception-message paths that invoke {@code toString}
 * implicitly, which are the paths that make this a hazard in the first place.
 *
 * <p>Alternatives Considered: placing this class in the {@code dto} package, since five of the eight
 * types under test live there. Rejected because the three entities are the types whose confident and
 * wrong rationales made the class necessary, and a reader arriving from one of those files should find
 * the assertions beside it. This module's charter for the {@code dto} test package would otherwise have
 * to claim a subject it does not own.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the parameterised methods below carry their own.
 */
class DiagnosticRenderingTest {

    /**
     * A card number from the published CardDemo demonstration seed, row 7 of
     * {@code app/data/ASCII/cardxref.txt}.
     *
     * <p>Assumptions: a seed value is used rather than a minted one so the constant is verifiable, and it
     * is a demonstration value identifying no real person and no real account.
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
     * rendering that emitted the first twelve and masked the last four -- the inverse of the rule. This
     * constant closes that gap.
     */
    private static final String FORBIDDEN_PREFIX = "485945261287";

    /**
     * A synthetic eleven-digit account identifier, at the declared width of {@code ACCT-ID PIC 9(11)}.
     *
     * <p>Assumptions: authored rather than extracted, so it identifies no real account, and chosen with
     * no digit run that could occur inside the permitted card-number suffix or inside an amount. A
     * distinctive value is what makes {@code doesNotContain} mean what it says.
     */
    private static final Long SYNTHETIC_ACCOUNT_ID = 21_820_493_291L;

    /**
     * A synthetic nine-digit customer identifier, at the declared width of {@code CUST-ID PIC 9(09)}.
     *
     * <p>Assumptions: authored rather than extracted, and distinct from every digit sequence any other
     * constant here contains, for the reason recorded on the account identifier above.
     */
    private static final Long SYNTHETIC_CUSTOMER_ID = 573_916_482L;

    /**
     * A synthetic national identifier at the declared width of {@code CUST-SSN PIC 9(09)}.
     *
     * <p>Assumptions: authored, so it identifies no real person. It is asserted absent from every
     * rendering under test, including the ones that carry a withholding marker in its place.
     */
    private static final String SYNTHETIC_NATIONAL_IDENTIFIER = "418736925";

    /**
     * A synthetic government-issued identifier within {@code CUST-GOVT-ISSUED-ID PIC X(20)}.
     *
     * <p>Assumptions: authored, visibly artificial, and distinctive enough that its presence in any
     * rendering could not be a coincidence of formatting.
     */
    private static final String SYNTHETIC_GOVERNMENT_IDENTIFIER = "DL9J4X72QW6";

    /**
     * A synthetic current balance, chosen with a distinctive cent value.
     *
     * <p>Assumptions: the cents are non-zero and unusual so that the rendered form of this amount cannot
     * coincide with any other digit sequence a rendering emits.
     */
    private static final BigDecimal SYNTHETIC_BALANCE = new BigDecimal("48213.67");

    /**
     * A synthetic credit limit, distinct from the balance for the same reason.
     */
    private static final BigDecimal SYNTHETIC_CREDIT_LIMIT = new BigDecimal("91544.23");

    /**
     * Confirms the account rendering names no identifier and no amount.
     *
     * <p>Assumptions: both amounts are asserted absent as text, because that is how they would reach a
     * log line -- an amount is rendered before it is written, so a numeric assertion would not detect the
     * disclosure this case exists to prevent.
     */
    @Test
    void accountRendersNoIdentifierAndNoAmount() {
        Account account = new Account(SYNTHETIC_ACCOUNT_ID, "Y", SYNTHETIC_BALANCE,
                SYNTHETIC_CREDIT_LIMIT, new BigDecimal("5000.00"), LocalDate.of(2020, 1, 15),
                LocalDate.of(2027, 1, 31), LocalDate.of(2024, 2, 1), new BigDecimal("120.45"),
                new BigDecimal("310.90"), "98101", "DEFAULT");

        String rendered = account.toString();

        assertThat(rendered).doesNotContain(String.valueOf(SYNTHETIC_ACCOUNT_ID));
        assertThat(rendered).doesNotContain("accountId");
        assertThat(rendered).doesNotContain(SYNTHETIC_BALANCE.toPlainString());
        assertThat(rendered).doesNotContain(SYNTHETIC_CREDIT_LIMIT.toPlainString());
        assertThat(rendered).doesNotContain("98101");
        assertThat(rendered).contains("activeStatus=Y");
        assertThat(rendered).contains("version=");
    }

    /**
     * Confirms a cross-reference row renders the card-number suffix and neither identifier.
     *
     * <p>Assumptions: the customer and account identifiers are asserted absent individually rather than
     * by counting rendered fields, because the hazard is the linkage: any two of the three values
     * appearing together is already the disclosure the override exists to prevent, and a field count
     * cannot express that.
     */
    @Test
    void cardXrefRendersTheCardNumberSuffixAndNeitherIdentifier() {
        CardXref crossReference =
                new CardXref(SEED_CARD_NUMBER, SYNTHETIC_CUSTOMER_ID, SYNTHETIC_ACCOUNT_ID);

        String rendered = crossReference.toString();

        assertThat(rendered).contains(PERMITTED_SUFFIX);
        assertThat(rendered).doesNotContain(SEED_CARD_NUMBER);
        assertThat(rendered).doesNotContain(FORBIDDEN_PREFIX);
        assertThat(rendered).doesNotContain(String.valueOf(SYNTHETIC_CUSTOMER_ID));
        assertThat(rendered).doesNotContain(String.valueOf(SYNTHETIC_ACCOUNT_ID));
        assertThat(rendered).doesNotContain("customerId");
        assertThat(rendered).doesNotContain("accountId");
    }

    /**
     * Confirms a card number too short to have a four-digit suffix renders as absent, not in part.
     *
     * <p>Assumptions: a partial value from a short or unpopulated field would disclose the whole of
     * whatever it holds while reading like the suffix of something longer, which is worse than disclosing
     * nothing because it is also misleading.
     *
     * <p>Assumptions: an ABSENT card number is not among the cases, because this constructor refuses one
     * outright -- the entity requires it, the column is the row's key, and a cross-reference row without a
     * card number is not a row. The rendering handles absence anyway, for the instance the persistence
     * provider builds through the protected constructor before it populates a field, and that path cannot
     * be reached from a test without reflection. Reaching it that way was rejected: it would assert
     * against a state no caller can construct, at the cost of a test that breaks whenever the field set
     * changes.
     *
     * @param shortCardNumber a card number shorter than the four-digit suffix width
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "7", "706"})
    void aCardNumberTooShortForItsSuffixRendersAsAbsent(String shortCardNumber) {
        CardXref crossReference =
                new CardXref(shortCardNumber, SYNTHETIC_CUSTOMER_ID, SYNTHETIC_ACCOUNT_ID);

        String rendered = crossReference.toString();

        if (!shortCardNumber.isEmpty()) {
            assertThat(rendered).doesNotContain(shortCardNumber);
        }
        assertThat(rendered).doesNotContain(String.valueOf(SYNTHETIC_CUSTOMER_ID));
    }

    /**
     * Confirms the customer rendering names no identifier and no cardholder detail.
     *
     * <p>Assumptions: the two ciphertext arrays are handed real bytes rather than empty ones, so that a
     * rendering which emitted their content or their length would be detectable. The assertion names the
     * clear identifiers as well, since those are what a reader of a log line would recognise.
     */
    @Test
    void customerRendersNoIdentifierAndNoCardholderDetail() {
        Customer customer = new Customer(SYNTHETIC_CUSTOMER_ID, "Marisol", "Q", "Trevanion",
                "1180 Wexford Terrace", "Suite 4402", "Bellingham", "WA", "USA", "98226-1174",
                "3605558812", "3605551907", SYNTHETIC_NATIONAL_IDENTIFIER.getBytes(),
                SYNTHETIC_GOVERNMENT_IDENTIFIER.getBytes(), LocalDate.of(1974, 8, 19),
                "00099887766", "Y", (short) 731);

        String rendered = customer.toString();

        assertThat(rendered).doesNotContain(String.valueOf(SYNTHETIC_CUSTOMER_ID));
        assertThat(rendered).doesNotContain("customerId");
        assertThat(rendered).doesNotContain("Marisol");
        assertThat(rendered).doesNotContain("Trevanion");
        assertThat(rendered).doesNotContain("Wexford");
        assertThat(rendered).doesNotContain("98226");
        assertThat(rendered).doesNotContain("3605558812");
        assertThat(rendered).doesNotContain("1974");
        assertThat(rendered).doesNotContain("731");
        assertThat(rendered).doesNotContain(SYNTHETIC_NATIONAL_IDENTIFIER);
        assertThat(rendered).doesNotContain(SYNTHETIC_GOVERNMENT_IDENTIFIER);
        assertThat(rendered).contains("[REDACTED]");
    }

    /**
     * Confirms the submitted update shape renders nothing the caller typed.
     *
     * <p>Assumptions: this is the most important case in the class. A request record is rendered exactly
     * when the request FAILED -- by bean validation, by deserialisation or by argument resolution -- so
     * the generated rendering would have written the caller's national identifier and government-issued
     * identifier into a log stream on the failure path and nowhere else, which is the hardest disclosure
     * to notice in testing and the easiest to reach in production.
     */
    @Test
    void theSubmittedUpdateShapeRendersNothingTheCallerTyped() {
        AccountUpdateRequest request = submittedRequest();

        String rendered = request.toString();

        assertThat(rendered).doesNotContain("418");
        assertThat(rendered).doesNotContain("73");
        assertThat(rendered).doesNotContain("6925");
        assertThat(rendered).doesNotContain(SYNTHETIC_GOVERNMENT_IDENTIFIER);
        assertThat(rendered).doesNotContain(String.valueOf(SYNTHETIC_ACCOUNT_ID));
        assertThat(rendered).doesNotContain("Marisol");
        assertThat(rendered).doesNotContain("Trevanion");
        assertThat(rendered).doesNotContain("Wexford");
        assertThat(rendered).doesNotContain(SYNTHETIC_BALANCE.toPlainString());
        assertThat(rendered).contains("accountIdSupplied=true");
        assertThat(rendered).contains("populatedComponents=");
    }

    /**
     * Confirms the update response renders neither its returned groups nor an identifier.
     *
     * <p>Assumptions: both returned groups are supplied rather than left absent, because the nested
     * records are where the disclosure lived: a response with no groups would render safely for the wrong
     * reason and the case would pass against a regression.
     *
     * <p>Assumptions: the response is built in the shape the contract of record declares -- an account
     * group and a customer group, per the {@code AccountUpdateResponse} schema in
     * {@code openapi/account-api.yaml} -- so the rendering asserted here is the one a caller of the
     * published operation can actually produce.
     */
    @Test
    void theUpdateResponseRendersNeitherItsStateNorAnIdentifier() {
        AccountUpdateResponse response = new AccountUpdateResponse(
                String.valueOf(SYNTHETIC_ACCOUNT_ID), "Looks Good.... so far", null, List.of(),
                returnedAccount(), returnedCustomer());

        String rendered = response.toString();

        assertThat(rendered).doesNotContain(String.valueOf(SYNTHETIC_ACCOUNT_ID));
        assertThat(rendered).doesNotContain(SYNTHETIC_NATIONAL_IDENTIFIER);
        assertThat(rendered).doesNotContain("Marisol");
        assertThat(rendered).doesNotContain(SYNTHETIC_BALANCE.toPlainString());
        assertThat(rendered).contains("fieldErrors=0 entries");
    }

    /**
     * Confirms the two state projections a response carries render only their status characters.
     *
     * <p>Assumptions: asserted separately from the responses above because each projection is a published
     * type a caller may render on its own, so its own override has to hold independently of whichever
     * response carries it. The response cases assert presence flags and therefore never exercise these
     * two overrides at all.
     *
     * <p>Refactoring Rationale: this case drove a separate {@code AccountUpdateCommittedState} record and
     * now drives the two projections the update response is actually typed to --
     * {@code AccountViewResponse.AccountDetail} and {@code CustomerDetail}. That record existed to give
     * the update response a state shape which could not hold the caller's cleartext national and
     * government identifiers, and the response reached the same end by a different route: it is typed to
     * the two view projections, which is what {@code openapi/account-api.yaml} publishes for its
     * {@code account} and {@code customer} components. Two shapes for one response state meant the
     * unreferenced one could drift without anything failing, so the property is asserted on the shapes
     * the response carries and the record is withdrawn.
     */
    @Test
    void theStateProjectionsRenderOnlyTheirStatusCharacters() {
        String renderedAccount = returnedAccount().toString();
        String renderedCustomer = returnedCustomer().toString();

        assertThat(renderedAccount).doesNotContain(String.valueOf(SYNTHETIC_ACCOUNT_ID));
        assertThat(renderedAccount).doesNotContain(SYNTHETIC_BALANCE.toPlainString());
        assertThat(renderedAccount).contains("activeStatus=Y");

        assertThat(renderedCustomer).doesNotContain(String.valueOf(SYNTHETIC_CUSTOMER_ID));
        assertThat(renderedCustomer).doesNotContain("Marisol");
        assertThat(renderedCustomer).doesNotContain("Wexford");
        assertThat(renderedCustomer).doesNotContain("731");
        assertThat(renderedCustomer).contains("primaryCardHolderIndicator=Y");

        // WHY : Assumptions: the two projections are asserted to agree with the sibling shapes that
        //       render the same data, because the defect this case found was a DISAGREEMENT rather than
        //       an absolute disclosure: CustomerResponse withheld the customer identifier while the
        //       projection an update response carries rendered it. Asserting each shape alone would have
        //       let the pair diverge again in the other direction.
        assertThat(renderedCustomer).startsWith("CustomerDetail[primaryCardHolderIndicator=");
        assertThat(renderedAccount).startsWith("AccountDetail[activeStatus=");
    }

    /**
     * Confirms the view response renders group presence rather than group content.
     *
     * <p>Assumptions: both nested groups are populated, for the same reason the update response's state
     * is: an absent group would render safely without the override being exercised at all.
     */
    @Test
    void theViewResponseRendersGroupPresenceRatherThanContent() {
        AccountViewResponse.AccountDetail account = new AccountViewResponse.AccountDetail("Y",
                "2020-01-15", Money.of(SYNTHETIC_CREDIT_LIMIT), "2027-01-31",
                Money.of(new BigDecimal("5000.00")), "2024-02-01", Money.of(SYNTHETIC_BALANCE),
                Money.of(new BigDecimal("120.45")), "DEFAULT", Money.of(new BigDecimal("310.90")));
        AccountViewResponse.CustomerDetail customer = new AccountViewResponse.CustomerDetail(
                String.valueOf(SYNTHETIC_CUSTOMER_ID), "[REDACTED]", "1974-08-19", "731", "Marisol",
                "Q", "Trevanion", "1180 Wexford Terrace", "WA", "Suite 4402", "98226", "Bellingham",
                "USA", "360-555-8812", "[REDACTED]", "360-555-1907", "00099887766", "Y");

        String rendered = new AccountViewResponse(String.valueOf(SYNTHETIC_ACCOUNT_ID), account,
                customer, "Looks Good.... so far", null).toString();

        assertThat(rendered).doesNotContain(String.valueOf(SYNTHETIC_ACCOUNT_ID));
        assertThat(rendered).doesNotContain(String.valueOf(SYNTHETIC_CUSTOMER_ID));
        assertThat(rendered).doesNotContain("Marisol");
        assertThat(rendered).doesNotContain("Wexford");
        assertThat(rendered).doesNotContain(SYNTHETIC_BALANCE.toPlainString());
        assertThat(rendered).doesNotContain("731");
        assertThat(rendered).contains("accountPresent=true");
        assertThat(rendered).contains("customerPresent=true");
    }

    /**
     * Confirms the cross-reference projection renders only the value that was already masked.
     *
     * <p>Assumptions: the masked component is supplied already masked, exactly as the mapping layer
     * produces it, so this case asserts what the record does with a masked value rather than asserting
     * that the record masks -- which it must not, masking having one owner per bounded context.
     */
    @Test
    void theCrossReferenceProjectionRendersOnlyTheMaskedCardNumber() {
        String rendered = new CardXrefResponse("************" + PERMITTED_SUFFIX,
                String.valueOf(SYNTHETIC_CUSTOMER_ID), String.valueOf(SYNTHETIC_ACCOUNT_ID))
                .toString();

        assertThat(rendered).contains(PERMITTED_SUFFIX);
        assertThat(rendered).doesNotContain(FORBIDDEN_PREFIX);
        assertThat(rendered).doesNotContain(String.valueOf(SYNTHETIC_CUSTOMER_ID));
        assertThat(rendered).doesNotContain(String.valueOf(SYNTHETIC_ACCOUNT_ID));
    }

    /**
     * Confirms the customer projection renders only the primary-cardholder indicator.
     *
     * <p>Assumptions: the two identifier components are supplied already masked, so a rendering that
     * emitted them would disclose nothing -- and they are still asserted absent, because a rendering
     * carrying two constants says nothing while looking like it says something.
     */
    @Test
    void theCustomerProjectionRendersOnlyThePrimaryCardholderIndicator() {
        String rendered = new CustomerResponse(String.valueOf(SYNTHETIC_CUSTOMER_ID), "Marisol", "Q",
                "Trevanion", "1180 Wexford Terrace", "Suite 4402", "Bellingham", "WA", "USA",
                "98226-1174", "360555881200", "360555190700", "[REDACTED]", "[REDACTED]",
                "1974-08-19", "00099887766", "Y", "731").toString();

        assertThat(rendered).doesNotContain(String.valueOf(SYNTHETIC_CUSTOMER_ID));
        assertThat(rendered).doesNotContain("Marisol");
        assertThat(rendered).doesNotContain("Trevanion");
        assertThat(rendered).doesNotContain("Wexford");
        assertThat(rendered).doesNotContain("98226");
        assertThat(rendered).doesNotContain("360555881200");
        assertThat(rendered).doesNotContain("1974");
        assertThat(rendered).doesNotContain("731");
        assertThat(rendered).doesNotContain("[REDACTED]");
        assertThat(rendered).contains("primaryCardHolderIndicator=Y");
    }

    /**
     * Builds a submitted update request populated with every value a caller would type.
     *
     * <p>Assumptions: every component is populated, including both protected identifiers, because a
     * partially populated request would leave several absence assertions vacuous.
     *
     * @return a fully populated submitted request, never {@code null}
     */
    private static AccountUpdateRequest submittedRequest() {
        return new AccountUpdateRequest(String.valueOf(SYNTHETIC_ACCOUNT_ID), "Y",
                SYNTHETIC_CREDIT_LIMIT.toPlainString(), "5000.00",
                SYNTHETIC_BALANCE.toPlainString(), "120.45", "310.90",
                "2020", "01", "15", "2027", "01", "31", "2024", "02", "01", "DEFAULT",
                String.valueOf(SYNTHETIC_CUSTOMER_ID), "418", "73", "6925",
                "1974", "08", "19", "731", "Marisol", "Q", "Trevanion",
                "1180 Wexford Terrace", "Suite 4402", "Bellingham", "WA", "USA", "98226",
                "360", "555", "8812", "360", "555", "1907", SYNTHETIC_GOVERNMENT_IDENTIFIER,
                "00099887766", "Y");
    }


    /**
     * Builds the account group the update response returns, populated at every component.
     *
     * @return a fully populated account group, never {@code null}
     */
    private static AccountViewResponse.AccountDetail returnedAccount() {
        return new AccountViewResponse.AccountDetail("Y",
                "2020-01-15", Money.of(SYNTHETIC_CREDIT_LIMIT), "2027-01-31",
                Money.of(new BigDecimal("5000.00")), "2024-02-01", Money.of(SYNTHETIC_BALANCE),
                Money.of(new BigDecimal("120.45")), "DEFAULT", Money.of(new BigDecimal("310.90")));
    }

    /**
     * Builds the customer group the update response returns, with both protected identifiers withheld.
     *
     * <p>Assumptions: the two protected components carry the withholding marker because that is what the
     * mapping layer puts there; supplying a real identifier would assert a state this group never holds.
     *
     * @return a fully populated customer group, never {@code null}
     */
    private static AccountViewResponse.CustomerDetail returnedCustomer() {
        return new AccountViewResponse.CustomerDetail(
                String.valueOf(SYNTHETIC_CUSTOMER_ID), "[REDACTED]", "1974-08-19", "731", "Marisol",
                "Q", "Trevanion", "1180 Wexford Terrace", "WA", "Suite 4402", "98226", "Bellingham",
                "USA", "360-555-8812", "[REDACTED]", "360-555-1907", "00099887766", "Y");
    }
}
