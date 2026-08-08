package com.carddemo.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.account.domain.Account;
import com.carddemo.account.dto.AccountUpdateRequest;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.validation.FieldValidationFlag;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies the account region of the update request reaches the account row, and nothing else does.
 *
 * <p>Assumptions: the row under test is constructed reflectively. {@link Account} declares its no-argument
 * constructor {@code protected} for the persistence provider's benefit, and this test is not in that
 * class's package, so there is no accessible way to obtain an empty row. Reflection is confined to the one
 * helper below rather than spread through the cases, and the alternative -- widening the constructor for a
 * test -- would relax a production type to suit an assertion.</p>
 */
@DisplayName("Account update mapping: the seventeen account components and nothing more")
class AccountMapperTest {

    /**
     * The mapper under test.
     */
    private final AccountMapper mapper = new AccountMapper();

    /**
     * Builds a submission in which every component carries an acceptable value.
     *
     * <p>Assumptions: the values are acceptable rather than realistic, because the subject of this class
     * is mapping and not validation. Each case below changes exactly one component away from this baseline
     * so that what it asserts is attributable to that component alone.</p>
     *
     * @return a fully-populated request, never {@code null}
     */
    private static AccountUpdateRequest acceptable() {
        return new AccountUpdateRequest("00000000011", "Y", "1000.00", "500.00", "250.00", "10.00",
                "20.00", "2014", "11", "20", "2026", "12", "31", "2024", "06", "15", "GROUP01",
                "000000011", "123", "45", "6789", "1985", "03", "14", "700", "JANE", "Q", "DOE",
                "1 MAIN STREET", "APT 2", "SPRINGFIELD", "IL", "USA", "62704", "217", "555",
                "0123", "217", "555", "0124", "GOVT-ID-000000000001", "0000000001", "Y");
    }

    /**
     * Creates an empty account row through the constructor the provider uses.
     *
     * @return an empty row, never {@code null}
     * @throws IllegalStateException if the entity no longer declares a no-argument constructor, which
     *     would break the persistence provider before it broke this test
     */
    private static Account emptyRow() {
        try {
            Constructor<Account> constructor = Account.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException unavailable) {
            throw new IllegalStateException(
                    "Account must keep a no-argument constructor for the provider", unavailable);
        }
    }

    /**
     * Asserts every one of the seventeen account components reaches the row it belongs to.
     *
     * <p>Assumptions: this is the assertion that closes the delivery gap this class was written for. The
     * request declares forty-three components; twenty-six are the customer mapper's and the seventeen
     * below are this class's, and before this class existed none of the seventeen had a production
     * consumer at all. Each is asserted individually rather than by comparing whole rows, so a failure
     * names the component that was dropped.</p>
     */
    @Test
    @DisplayName("all seventeen account components reach the row")
    void allSeventeenAccountComponentsReachTheRow() {
        Account row = emptyRow();

        this.mapper.applyUpdate(row, acceptable());

        assertThat(row.getActiveStatus()).isEqualTo("Y");
        assertThat(row.getCreditLimit()).isEqualByComparingTo("1000.00");
        assertThat(row.getCashCreditLimit()).isEqualByComparingTo("500.00");
        assertThat(row.getCurrentBalance()).isEqualByComparingTo("250.00");
        assertThat(row.getCurrentCycleCredit()).isEqualByComparingTo("10.00");
        assertThat(row.getCurrentCycleDebit()).isEqualByComparingTo("20.00");
        assertThat(row.getOpenDate()).isEqualTo(LocalDate.of(2014, 11, 20));
        assertThat(row.getExpirationDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(row.getReissueDate()).isEqualTo(LocalDate.of(2024, 6, 15));
        assertThat(row.getGroupId()).isEqualTo("GROUP01");

        // WHY : Assumptions: the seventeenth component is the account identifier and the assertion on it
        //   is that it is NOT written. It is the row's key, the row was loaded by it, and the reference's
        //   rewrite writes the record it read, so assigning it from a submission would relocate a row.
        assertThat(row.getAccountId()).isNull();
    }

    /**
     * Asserts the account's own postal code is never written from this screen.
     *
     * <p>Assumptions: this is a negative assertion about a column the row HAS, which is why it is worth
     * writing. The update map carries one postal code and it is the customer's; the account record's own
     * {@code ACCT-ADDR-ZIP} appears nowhere in the receiving program, so a mapper that helpfully assigned
     * it would make the two rows disagree after an update the baseline applied to one of them.</p>
     */
    @Test
    @DisplayName("the account's own postal code is left untouched")
    void theAccountsOwnPostalCodeIsLeftUntouched() {
        Account row = emptyRow();
        row.setAddressZip("99999");

        this.mapper.applyUpdate(row, acceptable());

        assertThat(row.getAddressZip()).isEqualTo("99999");
    }

    /**
     * Asserts a never-supplied group identifier is stored blank rather than refused or invented.
     *
     * <p>Assumptions: blank and not null, because {@code group_id} is {@code CHAR(10) NOT NULL} and the
     * entity's setter refuses null outright. Blank is also the authoritative value: the shipped account
     * seeds carry a blank group and the interest calculation's DEFAULT disclosure-group fallback is the
     * live behaviour a blank group reaches, so storing a literal group name here would bypass the
     * fallback rather than trigger it.</p>
     */
    @Test
    @DisplayName("an absent group identifier is stored blank, not null and not invented")
    void anAbsentGroupIdentifierIsStoredBlank() {
        Account row = emptyRow();
        AccountUpdateRequest submission = withGroupId(acceptable(), "   ");

        this.mapper.applyUpdate(row, submission);

        assertThat(row.getGroupId()).isNotNull().isBlank().hasSize(10);
        assertThat(row.getGroupId()).isNotEqualTo("DEFAULT");
    }

    /**
     * Asserts the four identifier components are withheld from the echo and the other thirty-nine are not.
     *
     * <p>Assumptions: the count is asserted as well as the values. A test that only checked the four were
     * masked would still pass if the echo masked everything, which would be a different defect and a
     * worse one.</p>
     */
    @Test
    @DisplayName("the echo withholds exactly the four identifier components")
    void theEchoWithholdsExactlyTheFourIdentifierComponents() {
        AccountUpdateRequest echo = this.mapper.toEcho(acceptable());

        assertThat(echo.ssnPart1()).isEqualTo(CustomerMapper.IDENTIFIER_REDACTED);
        assertThat(echo.ssnPart2()).isEqualTo(CustomerMapper.IDENTIFIER_REDACTED);
        assertThat(echo.ssnPart3()).isEqualTo(CustomerMapper.IDENTIFIER_REDACTED);
        assertThat(echo.governmentIssuedId()).isEqualTo(CustomerMapper.IDENTIFIER_REDACTED);

        assertThat(echo.accountId()).isEqualTo("00000000011");
        assertThat(echo.customerId()).isEqualTo("000000011");
        assertThat(echo.lastName()).isEqualTo("DOE");
        assertThat(echo.creditLimit()).isEqualTo("1000.00");
        assertThat(echo.zipCode()).isEqualTo("62704");
        assertThat(echo.eftAccountId()).isEqualTo("0000000001");
        assertThat(echo.primaryCardHolderIndicator()).isEqualTo("Y");
    }

    /**
     * Asserts the echo carries no clear national identifier anywhere in its rendering.
     */
    @Test
    @DisplayName("no part of the submitted national identifier survives the echo")
    void noPartOfTheSubmittedNationalIdentifierSurvivesTheEcho() {
        AccountUpdateRequest echo = this.mapper.toEcho(acceptable());

        assertThat(List.of(echo.ssnPart1(), echo.ssnPart2(), echo.ssnPart3()))
                .doesNotContain("123", "45", "6789");
        assertThat(echo.governmentIssuedId()).isNotEqualTo("GOVT-ID-000000000001");
    }

    /**
     * Asserts which amount shapes the mask can emit and which it cannot.
     *
     * @param screenValue the value as a screen would carry it
     * @param accepted whether the reference's numeric test would accept it
     */
    @ParameterizedTest
    @DisplayName("the amount edit accepts what the mask emits and refuses what it cannot")
    @CsvSource({
        "1000.00,     true",
        "1000,        true",
        "1000.5,      true",
        "'1,000.00',  true",
        "'1,000,000', true",
        "-250.00,     true",
        "+250.00,     true",
        "250.00-,     true",
        "'  1000.00', true",
        "0.00,        true",
        ".50,         true",
        "'',          false",
        "'   ',       false",
        "1000.000,    false",
        "1e5,         false",
        "ABC,         false",
        "'$1000.00',  false",
        "'1000.00CR', false",
        "'(1000.00)', false",
        "1.2.3,       false",
        "'-',         false",
        "'1000000000000000.00', false"
    })
    void theAmountEditAcceptsWhatTheMaskEmits(String screenValue, boolean accepted) {
        assertThat(AccountMapper.isEditedAmount(screenValue))
                .as("isEditedAmount(%s)", screenValue)
                .isEqualTo(accepted);
    }

    /**
     * Asserts an accepted amount converts at the stored scale, whatever decoration it carried.
     *
     * @param screenValue the value as a screen would carry it
     * @param expected the amount the column should hold
     */
    @ParameterizedTest
    @DisplayName("an accepted amount converts at the stored scale")
    @CsvSource({
        "1000.00,    1000.00",
        "'1,000',    1000.00",
        "1000.5,     1000.50",
        "-250.00,    -250.00",
        "250.00-,    -250.00",
        "+7,         7.00",
        ".50,        0.50"
    })
    void anAcceptedAmountConvertsAtTheStoredScale(String screenValue, String expected) {
        BigDecimal converted = AccountMapper.storedAmount(screenValue, "creditLimit");

        assertThat(converted).isEqualByComparingTo(expected);
        assertThat(converted.scale())
                .as("the stored column carries two fractional digits")
                .isEqualTo(2);
    }

    /**
     * Asserts a value the mask cannot emit is refused by conversion rather than silently coerced.
     */
    @Test
    @DisplayName("converting a value the mask cannot emit is refused")
    void convertingAValueTheMaskCannotEmitIsRefused() {
        assertThatThrownBy(() -> AccountMapper.storedAmount("ABC", "creditLimit"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("creditLimit");
        assertThatThrownBy(() -> AccountMapper.storedAmount("   ", "creditLimit"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("creditLimit");
    }

    /**
     * Asserts three parts compose a date only when they name a day that exists.
     *
     * @param year the year part
     * @param month the month part
     * @param day the day part
     * @param composable whether the three name a real day
     */
    @ParameterizedTest
    @DisplayName("three parts compose a date only when they name a day that exists")
    @CsvSource({
        "2024, 02, 29, true",
        "2023, 02, 29, false",
        "2024, 02, 30, false",
        "2024, 13, 01, false",
        "2024, 00, 10, false",
        "2024, 06, 00, false",
        "2024, 06, 31, false",
        "2024, 06, 15, true",
        "24,   06, 15, false",
        "2024, 6,  15, false",
        "20x4, 06, 15, false"
    })
    void threePartsComposeADateOnlyWhenTheyNameADayThatExists(String year, String month, String day,
            boolean composable) {
        assertThat(AccountMapper.isComposableDate(year, month, day)).isEqualTo(composable);
    }

    /**
     * Asserts every required account component that was never supplied is reported, and no optional one.
     *
     * @param field the component to blank out
     * @param reported whether the mapper should report it
     */
    @ParameterizedTest
    @DisplayName("every required account component is reported when never supplied")
    @MethodSource("everyAccountComponent")
    void everyRequiredAccountComponentIsReportedWhenNeverSupplied(String field, boolean reported) {
        AccountUpdateRequest submission = blanked(acceptable(), field);

        List<ApiError.FieldError> errors = this.mapper.accountFieldErrors(submission);
        List<String> reportedFields = errors.stream().map(ApiError.FieldError::field).toList();

        if (reported) {
            assertThat(reportedFields).as("blanking %s must report it", field).contains(field);
            assertThat(errors).allSatisfy(entry ->
                    assertThat(entry.state()).isEqualTo(FieldValidationFlag.BLANK));
        } else {
            assertThat(reportedFields).as("blanking %s must NOT report it", field)
                    .doesNotContain(field);
        }
    }

    /**
     * Supplies each account component together with whether the reference requires it.
     *
     * <p>Assumptions: the group identifier is the single {@code false} in this table, and it is false
     * because the reference declares no validation flag for it and reaches no edit routine with it.
     * Having exactly one exception is what makes the table worth parameterising rather than asserting in
     * one case.</p>
     *
     * @return one argument pair per account component, never {@code null}
     */
    private static Stream<Arguments> everyAccountComponent() {
        return Stream.of(
                Arguments.of("accountId", true),
                Arguments.of("activeStatus", true),
                Arguments.of("creditLimit", true),
                Arguments.of("cashCreditLimit", true),
                Arguments.of("currentBalance", true),
                Arguments.of("currentCycleCredit", true),
                Arguments.of("currentCycleDebit", true),
                Arguments.of("openDateYear", true),
                Arguments.of("openDateMonth", true),
                Arguments.of("openDateDay", true),
                Arguments.of("expirationDateYear", true),
                Arguments.of("expirationDateMonth", true),
                Arguments.of("expirationDateDay", true),
                Arguments.of("reissueDateYear", true),
                Arguments.of("reissueDateMonth", true),
                Arguments.of("reissueDateDay", true),
                Arguments.of("groupId", false));
    }

    /**
     * Asserts an acceptable submission produces no account entry at all.
     */
    @Test
    @DisplayName("an acceptable submission produces no account entry")
    void anAcceptableSubmissionProducesNoAccountEntry() {
        assertThat(this.mapper.accountFieldErrors(acceptable())).isEmpty();
    }

    /**
     * Returns the submission with one named component blanked out.
     *
     * @param request the submission to copy; must not be {@code null}
     * @param field the component to blank; must not be {@code null}
     * @return the copy, never {@code null}
     */
    private static AccountUpdateRequest blanked(AccountUpdateRequest request, String field) {
        return new AccountUpdateRequest(
                blankIf(field, "accountId", request.accountId()),
                blankIf(field, "activeStatus", request.activeStatus()),
                blankIf(field, "creditLimit", request.creditLimit()),
                blankIf(field, "cashCreditLimit", request.cashCreditLimit()),
                blankIf(field, "currentBalance", request.currentBalance()),
                blankIf(field, "currentCycleCredit", request.currentCycleCredit()),
                blankIf(field, "currentCycleDebit", request.currentCycleDebit()),
                blankIf(field, "openDateYear", request.openDateYear()),
                blankIf(field, "openDateMonth", request.openDateMonth()),
                blankIf(field, "openDateDay", request.openDateDay()),
                blankIf(field, "expirationDateYear", request.expirationDateYear()),
                blankIf(field, "expirationDateMonth", request.expirationDateMonth()),
                blankIf(field, "expirationDateDay", request.expirationDateDay()),
                blankIf(field, "reissueDateYear", request.reissueDateYear()),
                blankIf(field, "reissueDateMonth", request.reissueDateMonth()),
                blankIf(field, "reissueDateDay", request.reissueDateDay()),
                blankIf(field, "groupId", request.groupId()),
                request.customerId(), request.ssnPart1(), request.ssnPart2(), request.ssnPart3(),
                request.dateOfBirthYear(), request.dateOfBirthMonth(), request.dateOfBirthDay(),
                request.ficoCreditScore(), request.firstName(), request.middleName(),
                request.lastName(), request.addressLine1(), request.addressLine2(), request.city(),
                request.stateCode(), request.countryCode(), request.zipCode(),
                request.phone1AreaCode(), request.phone1Prefix(), request.phone1LineNumber(),
                request.phone2AreaCode(), request.phone2Prefix(), request.phone2LineNumber(),
                request.governmentIssuedId(), request.eftAccountId(),
                request.primaryCardHolderIndicator());
    }

    /**
     * Returns spaces when the component is the one being blanked, and the value otherwise.
     *
     * @param target the component being blanked; must not be {@code null}
     * @param candidate the component this value belongs to; must not be {@code null}
     * @param value the value to keep when the two do not match; may be {@code null}
     * @return the value or a run of spaces
     */
    private static String blankIf(String target, String candidate, String value) {
        return target.equals(candidate) ? "   " : value;
    }

    /**
     * Returns the submission with a different group identifier.
     *
     * @param request the submission to copy; must not be {@code null}
     * @param groupId the group identifier to carry; may be {@code null}
     * @return the copy, never {@code null}
     */
    private static AccountUpdateRequest withGroupId(AccountUpdateRequest request, String groupId) {
        return new AccountUpdateRequest(request.accountId(), request.activeStatus(),
                request.creditLimit(), request.cashCreditLimit(), request.currentBalance(),
                request.currentCycleCredit(), request.currentCycleDebit(), request.openDateYear(),
                request.openDateMonth(), request.openDateDay(), request.expirationDateYear(),
                request.expirationDateMonth(), request.expirationDateDay(),
                request.reissueDateYear(), request.reissueDateMonth(), request.reissueDateDay(),
                groupId, request.customerId(), request.ssnPart1(), request.ssnPart2(),
                request.ssnPart3(), request.dateOfBirthYear(), request.dateOfBirthMonth(),
                request.dateOfBirthDay(), request.ficoCreditScore(), request.firstName(),
                request.middleName(), request.lastName(), request.addressLine1(),
                request.addressLine2(), request.city(), request.stateCode(), request.countryCode(),
                request.zipCode(), request.phone1AreaCode(), request.phone1Prefix(),
                request.phone1LineNumber(), request.phone2AreaCode(), request.phone2Prefix(),
                request.phone2LineNumber(), request.governmentIssuedId(), request.eftAccountId(),
                request.primaryCardHolderIndicator());
    }
}
