package com.carddemo.account.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Asserts the update response cannot carry a whole protected identifier, and drops nothing the form
 * needs to read back.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class asserts a property of a TYPE rather than a behaviour of a method: that the shape
 * {@code POST /api/v1/accounts/update} answers with declares no component able to carry a whole
 * national identifier or a whole government-issued identifier, and that it still mirrors every
 * submitted value a client has to be able to read back. The subject is the shape of a record, so the
 * assertions read declared components reflectively rather than calling accessors by name -- a
 * name-by-name test would pass on a record that had gained a component nobody thought to check.</p>
 *
 * <p>Refactoring Rationale: the property was first asserted against a flat {@code AccountUpdateEcho}
 * record, which the committed contract does not declare and which no response referenced.
 * {@code src/main/resources/openapi/account-api.yaml} declares {@code AccountUpdateResponse.account}
 * as {@code $ref: AccountDetail} and its {@code customer} as {@code $ref: CustomerDetail}, so those
 * two are the shapes a consumer actually reads and they are what this class now examines. The
 * property is unchanged and the subject is the shipped one; asserting it against a type the contract
 * does not publish proved nothing about what leaves the service.</p>
 *
 * <p>Assumptions: the two nested detail records are examined rather than the outer response, because
 * the outer response carries only the two references plus the message members, so every value that
 * could disclose an identifier is inside one of them. Examining the outer record alone would be
 * vacuous, and examining both is what makes the sweep complete.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * at-clause; the convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
@DisplayName("AccountUpdateResponse: the protected identifiers have nowhere whole to go")
class AccountUpdateResponseShapeTest {

    /**
     * Name fragments that mark a component as carrying a protected identifier.
     *
     * <p>Assumptions: matched as lower-cased substrings rather than as whole names, so a component
     * added later under any spelling containing one of them is caught without this list growing.</p>
     */
    private static final Set<String> PROTECTED_FRAGMENTS = Set.of("ssn", "governmentissued");

    /**
     * The redacted members that may carry a protected identifier's NAME, having no whole value.
     *
     * <p>Assumptions: both are permitted by the masked suffix rather than by being named exceptions to
     * the rule. A component called {@code ssn} would still fail, which is the point: the suffix is the
     * evidence that what the component holds is not the value.</p>
     */
    private static final Set<String> PERMITTED_REDACTED_MEMBERS =
            Set.of("ssnmasked", "governmentissuedidmasked");

    /**
     * The submitted components that are composed into one response component rather than mirrored.
     *
     * <p>Assumptions: enumerated rather than derived, because each is a decision the mapper makes and
     * a reader checking this test has to be able to see which submitted parts became which single
     * value. The three national-identifier parts are absent from this set deliberately -- they are
     * withheld rather than composed, and the case below asserts that separately.</p>
     */
    private static final Set<String> COMPOSED_REQUEST_COMPONENTS = Set.of(
            "openDateYear", "openDateMonth", "openDateDay",
            "expirationDateYear", "expirationDateMonth", "expirationDateDay",
            "reissueDateYear", "reissueDateMonth", "reissueDateDay",
            "dateOfBirthYear", "dateOfBirthMonth", "dateOfBirthDay",
            "phone1AreaCode", "phone1Prefix", "phone1LineNumber",
            "phone2AreaCode", "phone2Prefix", "phone2LineNumber");

    /**
     * The submitted components the response withholds outright.
     *
     * <p>Assumptions: the national identifier's three parts and the government-issued identifier are
     * the whole of this set, and each reappears only in masked form. They are listed so the mirroring
     * case below can exclude exactly them and no more, which is what stops that case from being
     * weakened by an exclusion added to make it pass.</p>
     */
    private static final Set<String> WITHHELD_REQUEST_COMPONENTS =
            Set.of("ssnPart1", "ssnPart2", "ssnPart3", "governmentIssuedId");

    /**
     * Reads the component names of a record, in declaration order.
     *
     * @param type the record class to examine, of type {@code Class}
     * @return the component names, of type {@code List} of {@code String}, never {@code null}
     */
    private static List<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /** The components of the two records a consumer reads the updated row from. */
    @Nested
    @DisplayName("the declared component set")
    class ComponentSet {

        /**
         * Confirms no component of either detail record can hold a whole protected identifier.
         */
        @Test
        @DisplayName("no component of either detail record can hold a whole protected identifier")
        void noComponentCanHoldAProtectedIdentifier() {
            List<String> offending = Arrays.stream(new Class<?>[] {
                            AccountViewResponse.AccountDetail.class,
                            AccountViewResponse.CustomerDetail.class})
                    .flatMap(type -> componentNames(type).stream())
                    .filter(name -> {
                        String lowered = name.toLowerCase(Locale.ROOT);
                        return PROTECTED_FRAGMENTS.stream().anyMatch(lowered::contains)
                                && !PERMITTED_REDACTED_MEMBERS.contains(lowered);
                    })
                    .toList();

            assertThat(offending)
                    .as("each of these could carry a whole protected identifier out of the service,"
                            + " which is the exposure this response's shape exists to make impossible")
                    .isEmpty();
        }

        /**
         * Confirms the three submitted national-identifier parts have no counterpart at all.
         */
        // WHY : Assumptions: the three parts and the government-issued identifier are named explicitly
        //       here as well as being covered by the substring sweep above, because they are the
        //       specific members the finding named and a reader looking for that assertion should find
        //       it spelled out rather than implied.
        @Test
        @DisplayName("the submitted identifier parts have no counterpart in either detail record")
        void theSubmittedIdentifierPartsAreAbsent() {
            List<String> published = Arrays.stream(new Class<?>[] {
                            AccountViewResponse.AccountDetail.class,
                            AccountViewResponse.CustomerDetail.class})
                    .flatMap(type -> componentNames(type).stream())
                    .toList();

            assertThat(published).doesNotContainAnyElementsOf(WITHHELD_REQUEST_COMPONENTS);
        }

        /**
         * Confirms every submitted value a client must read back is published in some form.
         */
        // WHY : Assumptions: this is the other half of the shape contract and it fails in the opposite
        //       direction -- a redaction that also dropped the city or the credit limit would satisfy
        //       every assertion above while making the response useless to the form it answers.
        @Test
        @DisplayName("every submitted component is mirrored, composed, or deliberately withheld")
        void everySubmittedComponentIsAccountedFor() {
            List<String> published = Arrays.stream(new Class<?>[] {
                            AccountUpdateResponse.class,
                            AccountViewResponse.AccountDetail.class,
                            AccountViewResponse.CustomerDetail.class})
                    .flatMap(type -> componentNames(type).stream())
                    .toList();

            List<String> unaccounted = componentNames(AccountUpdateRequest.class).stream()
                    .filter(name -> !published.contains(name))
                    .filter(name -> !COMPOSED_REQUEST_COMPONENTS.contains(name))
                    .filter(name -> !WITHHELD_REQUEST_COMPONENTS.contains(name))
                    .toList();

            assertThat(unaccounted)
                    .as("the response answers the submitted form, so a component neither mirrored,"
                            + " composed nor deliberately withheld is a field the client cannot read"
                            + " back")
                    .isEmpty();
        }

        /**
         * Confirms the arities the contract declares, so a component cannot be added unnoticed.
         */
        // WHY : Assumptions: the three arities are asserted together because the relationship between
        //       them is the property, not any one figure. The response carries six members, ten of the
        //       account's own values and eighteen of the customer's; the request carries forty-three,
        //       eighteen of which are composed and four withheld. A component added to any of the three
        //       fails here and is then examined by the sweeps above rather than arriving silently.
        @Test
        @DisplayName("the declared arities are the ones the committed contract describes")
        void theDeclaredAritiesAreTheContractsOwn() {
            assertThat(AccountUpdateResponse.class.getRecordComponents()).hasSize(6);
            assertThat(AccountViewResponse.AccountDetail.class.getRecordComponents()).hasSize(10);
            assertThat(AccountViewResponse.CustomerDetail.class.getRecordComponents()).hasSize(18);
            assertThat(AccountUpdateRequest.class.getRecordComponents()).hasSize(43);
        }
    }

    /** The reference the response declares for each of the two detail shapes. */
    @Nested
    @DisplayName("the declared component types")
    class ComponentTypes {

        /**
         * Confirms the response's two row components are the contract's declared detail records.
         */
        // WHY : Refactoring Rationale: this case replaces one that required the component to be a
        //       bespoke echo record. The contract declares $ref AccountDetail and $ref CustomerDetail
        //       for these two members, so requiring anything else made the generated document and the
        //       committed file describe different shapes -- which is the drift the contract tests in
        //       this module exist to prevent.
        @Test
        @DisplayName("the row components are typed to the contract's two detail records")
        void theRowComponentsAreTypedToTheDetailRecords() {
            assertThat(componentType("account")).isEqualTo(AccountViewResponse.AccountDetail.class);
            assertThat(componentType("customer"))
                    .isEqualTo(AccountViewResponse.CustomerDetail.class);
        }

        /**
         * Resolves one declared component of the update response by name.
         *
         * @param name the component name to resolve, of type {@code String}
         * @return the component's declared type, of type {@code Class}, never {@code null}
         * @throws AssertionError if the response declares no component of that name, which a rename
         *     would cause and which should fail this test rather than skip it
         */
        private Class<?> componentType(String name) {
            return Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                    .filter(component -> component.getName().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "AccountUpdateResponse declares no component named " + name))
                    .getType();
        }
    }
}
