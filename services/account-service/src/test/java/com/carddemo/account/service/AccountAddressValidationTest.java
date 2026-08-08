package com.carddemo.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.account.domain.Account;
import com.carddemo.account.domain.CardXref;
import com.carddemo.account.domain.Customer;
import com.carddemo.account.dto.AccountUpdateRequest;
import com.carddemo.account.mapper.AccountContextMapper;
import com.carddemo.account.mapper.CustomerMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.validation.FieldValidationFlag;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the account update path actually RUNS the address value-domain edits.
 *
 * <p>These tests exist because {@code AddressValidationService} had no production caller at all: it was
 * an annotated component whose port had no adapter, so the phone, state and state-with-postal-prefix
 * edits that {@code app/cbl/COACTUPC.cbl} performs at lines 1600, 1635, 1643 and 1667 reached no
 * migrated request. Landing the adapter alone would have satisfied the container without delivering the
 * rule, so the wiring is asserted here rather than assumed.
 */
class AccountAddressValidationTest {

    /** The account the fixture stores and edits. */
    private static final long ACCOUNT_ID = 10_000_000_001L;

    /** The customer the account resolves to. */
    private static final long CUSTOMER_ID = 900_000_001L;

    /** The card the cross-reference resolves for the account. */
    private static final String CARD_NUMBER = "4111111111111111";

    /**
     * A lookup whose answers each test chooses, and which records what it was asked.
     *
     * <p>Assumptions: this records CALLS as well as answering them, because two of the properties under
     * test are about a call NOT being made -- an omitted field must not be edited, and the cross-field
     * check must not run once the state is already known to be invalid. Neither can be observed from a
     * return value.
     */
    private static final class RecordingLookup
            implements AddressValidationService.ReferenceAddressLookup {

        /** The classification every area code resolves to, or empty for none. */
        private Optional<AddressValidationService.AreaCodeClass> areaCodeClass =
                Optional.of(AddressValidationService.AreaCodeClass.GENERAL_PURPOSE);

        /** Whether every state code is reported as present. */
        private boolean stateCodePresent = true;

        /** Whether every state and postal-prefix pairing is reported as present. */
        private boolean stateZipPrefixPresent = true;

        /** The methods this lookup was asked, in the order it was asked them. */
        private final List<String> calls = new ArrayList<>();

        /**
         * {@inheritDoc}
         *
         * @param areaCode {@inheritDoc}
         * @return the classification this test chose
         */
        @Override
        public Optional<AddressValidationService.AreaCodeClass> findAreaCodeClass(String areaCode) {
            this.calls.add("findAreaCodeClass:" + areaCode);
            return this.areaCodeClass;
        }

        /**
         * {@inheritDoc}
         *
         * @param stateCode {@inheritDoc}
         * @return the presence this test chose
         */
        @Override
        public boolean stateCodeExists(String stateCode) {
            this.calls.add("stateCodeExists:" + stateCode);
            return this.stateCodePresent;
        }

        /**
         * {@inheritDoc}
         *
         * @param stateZipPrefix {@inheritDoc}
         * @return the presence this test chose
         */
        @Override
        public boolean stateZipPrefixExists(String stateZipPrefix) {
            this.calls.add("stateZipPrefixExists:" + stateZipPrefix);
            return this.stateZipPrefixPresent;
        }
    }

    /** The stored rows, the mocked repositories and the wired service. */
    private static final class Fixture {

        /** The lookup whose answers the test controls. */
        private final RecordingLookup lookup = new RecordingLookup();

        /** The account repository. */
        private final AccountRepository accounts = mock(AccountRepository.class);

        /** The customer repository. */
        private final CustomerRepository customers = mock(CustomerRepository.class);

        /** The cross-reference repository. */
        private final CardXrefRepository crossReferences = mock(CardXrefRepository.class);

        /** The service under test, wired exactly as the container wires it. */
        private final AccountUpdateService service;

        /** Builds the fixture over rows the reference would accept. */
        Fixture() {
            Account account = new Account(ACCOUNT_ID, "Y", new BigDecimal("100.00"),
                    new BigDecimal("5000.00"), new BigDecimal("500.00"),
                    LocalDate.of(2020, 1, 1), LocalDate.of(2027, 12, 31), LocalDate.of(2024, 6, 1),
                    BigDecimal.ZERO, BigDecimal.ZERO, "12345", "DEFAULT");
            Customer customer = new Customer(CUSTOMER_ID, "GRACE", null, "HOPPER",
                    "1 NAVY YARD", null, "ARLINGTON", "VA", "USA", "1254600000",
                    "(703)5550101  ", null, cipher("123456789"), cipher("GOVTID0001"),
                    LocalDate.of(1906, 12, 9), "0000000001", "Y", (short) 800);

            when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
            when(this.customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
            when(this.crossReferences.findByAccountIdOrderByCardNumAsc(ACCOUNT_ID))
                    .thenReturn(List.of(new CardXref(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID)));
            when(this.customers.saveAndFlush(any(Customer.class)))
                    .thenAnswer(call -> call.getArgument(0));
            when(this.accounts.saveAndFlush(any(Account.class)))
                    .thenAnswer(call -> call.getArgument(0));

            this.service = new AccountUpdateService(this.accounts, this.customers,
                    this.crossReferences, new AccountContextMapper(),
                    new CustomerMapper(Fixture::cipherFor),
                    new AddressValidationService(this.lookup));
        }

        /**
         * Applies an edit using the revision the stored rows currently hold.
         *
         * @param request the submitted edit; must not be {@code null}
         */
        void update(AccountUpdateRequest request) {
            this.service.update(ACCOUNT_ID, request, this.service.currentRevision(ACCOUNT_ID));
        }

        /**
         * Produces recognisable stored ciphertext.
         *
         * @param clearText the value to stand in for; must not be {@code null}
         * @return the recognisable bytes, never {@code null}
         */
        private static byte[] cipher(String clearText) {
            return ("enc:" + clearText).getBytes(StandardCharsets.UTF_8);
        }

        /**
         * Produces recognisable ciphertext under the mapper's two-argument shape.
         *
         * @param clearText the value to protect; must not be {@code null}
         * @param field the field it belongs to; must not be {@code null}
         * @return the recognisable bytes, never {@code null}
         */
        private static byte[] cipherFor(String clearText, String field) {
            return ("enc:" + field + ":" + clearText).getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Builds a request whose every field the reference accepts, with both telephone numbers supplied.
     *
     * @param stateCode the state code to submit, or {@code null} to omit it
     * @param zipCode the postal code to submit, or {@code null} to omit it
     * @param phone2AreaCode the second telephone's area code, or {@code null} to omit that number
     * @return the request, never {@code null}
     */
    private static AccountUpdateRequest request(String stateCode, String zipCode,
            String phone2AreaCode) {
        String phone2Rest = phone2AreaCode == null ? null : "555";
        String phone2Line = phone2AreaCode == null ? null : "0202";
        return new AccountUpdateRequest(
                "10000000001", "Y", "5000.00", "500.00", "100.00", "0.00", "0.00",
                "2020", "01", "01",
                "2027", "12", "31",
                "2024", "06", "01",
                "DEFAULT", "900000001",
                "123", "45", "6789",
                "1906", "12", "09",
                "800",
                "GRACE", null, "HOPPER",
                "1 NAVY YARD", null, "ARLINGTON", stateCode, "USA", zipCode,
                "703", "555", "0101",
                phone2AreaCode, phone2Rest, phone2Line,
                "GOVTID0001", "0000000001", "Y");
    }

    /**
     * Confirms an address every allow-list accepts is committed.
     *
     * <p>Assumptions: this is asserted first so that every refusal below is known to be caused by the
     * edit under test rather than by the fixture failing for an unrelated reason.
     */
    @Test
    @DisplayName("an address every allow-list accepts is committed")
    void anAcceptedAddressIsCommitted() {
        Fixture fixture = new Fixture();
        assertThatCode(() -> fixture.update(request("VA", "12546", null)))
                .doesNotThrowAnyException();
        verify(fixture.customers).saveAndFlush(any(Customer.class));
        verify(fixture.accounts).saveAndFlush(any(Account.class));
    }

    /**
     * Confirms a state outside the allow-list refuses the update and names the field.
     *
     * <p>Assumptions: the refusal is the whole point of the wiring. Before it, a state code absent from
     * the reference was stored without complaint, which the reference refuses at line 1600.
     */
    @Test
    @DisplayName("a state code outside the allow-list is refused and names the state field")
    void anUnlistedStateIsRefused() {
        Fixture fixture = new Fixture();
        fixture.lookup.stateCodePresent = false;

        assertThatThrownBy(() -> fixture.update(request("ZZ", "12546", null)))
                .isInstanceOf(ClientInputException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        ClientInputException.class))
                .extracting(ClientInputException::fields)
                .isEqualTo(List.of("stateCode"));
    }

    /**
     * Confirms nothing is written when an edit refuses.
     *
     * <p>Assumptions: the edits run BEFORE the mapper applies anything, so this asserts an ordering
     * property rather than a rollback property -- the entities are never mutated at all.
     */
    @Test
    @DisplayName("a refused address writes neither row")
    void aRefusedAddressWritesNothing() {
        Fixture fixture = new Fixture();
        fixture.lookup.stateCodePresent = false;

        assertThatThrownBy(() -> fixture.update(request("ZZ", "12546", null)))
                .isInstanceOf(ClientInputException.class);
        verify(fixture.customers, never()).saveAndFlush(any(Customer.class));
        verify(fixture.accounts, never()).saveAndFlush(any(Account.class));
    }

    /**
     * Confirms an omitted state is not looked up, so the caller is told the right thing.
     *
     * <p>Assumptions: the state IS mandatory on this path -- the mapper refuses an absent state code --
     * so this is not a test that an omitted state is accepted. It is a test of WHICH refusal the caller
     * receives. The three validators are total: they pad a blank value to width and then look it up, so
     * editing an unsupplied field would report a blank state as a state outside the allow-list. The
     * caller would then be told to correct a value it never sent, instead of being told it forgot to send
     * one. Guarding the edit on the field being supplied leaves the required-field contract with the
     * mapper that owns it.
     *
     * <p>Refactoring Rationale: the discriminator is the refusal's STATE and its absence sentence, where
     * it was previously the exception type -- the mapper's required-field refusal used to be a bare
     * argument exception and was identified by NOT being a client-input one. Both refusals are now
     * client-input refusals, because the mapper's was answered as HTTP 500 while it is plainly a caller's
     * omission. The blank state is a stronger discriminator than the type ever was: the allow-list
     * refusal reports a value that was supplied and rejected, so it can only carry the not-acceptable
     * state, and no future convergence of the two types can blur that.
     */
    @Test
    @DisplayName("an omitted state is not looked up, so the refusal names the absence and not the value")
    void anOmittedStateIsNotLookedUp() {
        Fixture fixture = new Fixture();
        fixture.lookup.stateCodePresent = false;

        assertThatThrownBy(() -> fixture.update(request(null, null, null)))
                .isInstanceOf(ClientInputException.class)
                .hasMessageContaining("no value was supplied")
                .satisfies(refusal -> {
                    ClientInputException refused = (ClientInputException) refusal;
                    assertThat(refused.state())
                            .as("an omission is the blank state, not the rejected-value one")
                            .isEqualTo(FieldValidationFlag.BLANK);
                    assertThat(refused.fields())
                            .as("keyed by the request property, so a form can mark the control")
                            .containsExactly("stateCode");
                });
        assertThat(fixture.lookup.calls).noneMatch(call -> call.startsWith("stateCodeExists"));
    }

    /**
     * Confirms an area code outside the general-purpose list refuses and names the telephone field.
     */
    @Test
    @DisplayName("a first telephone area code outside the general-purpose list is refused")
    void anUnlistedFirstAreaCodeIsRefused() {
        Fixture fixture = new Fixture();
        fixture.lookup.areaCodeClass = Optional.empty();

        assertThatThrownBy(() -> fixture.update(request("VA", "12546", null)))
                .isInstanceOf(ClientInputException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        ClientInputException.class))
                .extracting(ClientInputException::fields)
                .isEqualTo(List.of("phone1AreaCode"));
    }

    /**
     * Confirms the second telephone number is edited too when it is supplied.
     *
     * <p>Assumptions: the reference performs the same edit twice, at lines 1635 and 1643, so editing
     * only the first number would leave the second unchecked.
     */
    @Test
    @DisplayName("a supplied second telephone area code is edited as well as the first")
    void aSuppliedSecondAreaCodeIsEdited() {
        Fixture fixture = new Fixture();
        assertThatCode(() -> fixture.update(request("VA", "12546", "202")))
                .doesNotThrowAnyException();
        assertThat(fixture.lookup.calls)
                .contains("findAreaCodeClass:703", "findAreaCodeClass:202");
    }

    /**
     * Confirms an unsupplied second telephone number is not edited.
     */
    @Test
    @DisplayName("an omitted second telephone area code is not looked up")
    void anOmittedSecondAreaCodeIsNotLookedUp() {
        Fixture fixture = new Fixture();
        assertThatCode(() -> fixture.update(request("VA", "12546", null)))
                .doesNotThrowAnyException();
        assertThat(fixture.lookup.calls).containsOnlyOnce("findAreaCodeClass:703");
    }

    /**
     * Confirms the cross-field check is skipped once the state is already known to be invalid.
     *
     * <p>Assumptions: this reproduces the reference guard at lines 1665 and 1666,
     * {@code IF FLG-STATE-ISVALID AND FLG-ZIPCODE-ISVALID}. Without it the caller would be told about a
     * pairing whose first half it has already been told is wrong.
     */
    @Test
    @DisplayName("the state and postal pairing is not checked when the state is already invalid")
    void thePairingIsNotCheckedWhenTheStateIsInvalid() {
        Fixture fixture = new Fixture();
        fixture.lookup.stateCodePresent = false;

        assertThatThrownBy(() -> fixture.update(request("ZZ", "12546", null)))
                .isInstanceOf(ClientInputException.class);
        assertThat(fixture.lookup.calls)
                .as("the reference guards this check on the state already being valid")
                .noneMatch(call -> call.startsWith("stateZipPrefixExists"));
    }

    /**
     * Confirms the cross-field check runs when both halves are individually valid.
     */
    @Test
    @DisplayName("the state and postal pairing is checked when both halves are valid")
    void thePairingIsCheckedWhenBothHalvesAreValid() {
        Fixture fixture = new Fixture();
        assertThatCode(() -> fixture.update(request("VA", "12546", null)))
                .doesNotThrowAnyException();
        assertThat(fixture.lookup.calls)
                .as("the four-character candidate is the state followed by two postal digits")
                .contains("stateZipPrefixExists:VA12");
    }

    /**
     * Confirms an unlisted pairing names both offending fields, each exactly once.
     *
     * <p>Assumptions: the reference highlights every field whose flag is not valid, so both halves are
     * named. They are de-duplicated because a repeated name would render two markers on one control.
     */
    @Test
    @DisplayName("an unlisted state and postal pairing names both fields exactly once")
    void anUnlistedPairingNamesBothFieldsOnce() {
        Fixture fixture = new Fixture();
        fixture.lookup.stateZipPrefixPresent = false;

        assertThatThrownBy(() -> fixture.update(request("VA", "99546", null)))
                .isInstanceOf(ClientInputException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        ClientInputException.class))
                .extracting(ClientInputException::fields)
                .isEqualTo(List.of("stateCode", "zipCode"));
    }

    /**
     * Confirms the edits run in the order the reference performs them.
     *
     * <p>Assumptions: order is asserted because the cross-field guard depends on it. If the pairing were
     * checked before the state, the guard could not consult a state outcome that did not exist yet.
     */
    @Test
    @DisplayName("the edits run in the reference's order: state, telephones, then the pairing")
    void theEditsRunInTheReferenceOrder() {
        Fixture fixture = new Fixture();
        fixture.update(request("VA", "12546", "202"));

        assertThat(fixture.lookup.calls).containsExactly(
                "stateCodeExists:VA",
                "findAreaCodeClass:703",
                "findAreaCodeClass:202",
                "stateZipPrefixExists:VA12");
    }
}
