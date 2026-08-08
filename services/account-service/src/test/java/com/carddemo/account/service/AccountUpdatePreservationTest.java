package com.carddemo.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
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
import com.carddemo.common.error.RecordConflictException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

/**
 * Asserts what an account update PRESERVES, and that a stale precondition changes nothing.
 *
 * <p>Purpose: three separate data losses were possible on this path, and all three came from the same
 * cause -- the mapper built a new {@code Customer} from the request and never saw the stored row. This
 * class holds each of the three closed, because each is silent when it regresses: a lost postal-code
 * extension, deleted identifier ciphertext and a silently overwritten concurrent edit all produce a
 * successful response.</p>
 *
 * <p>Assumptions: the two protected identifiers are asserted as INTENTS captured at the mapper boundary
 * rather than as stored bytes read back off the entity, and that is a constraint of the design rather than
 * a convenience. {@code Customer} publishes no accessor for either protected column and its own rendering
 * documents that not even PRESENCE may reach a log through it, so there is deliberately no route from a
 * test to the stored ciphertext -- and adding one to make an assertion possible would remove the property
 * the entity exists to hold. What the mapper hands the entity is the decision under test in any case: the
 * finding was that an omitted value was resolved as a deletion, and the intent is exactly that
 * resolution.</p>
 *
 * <p>Assumptions: the postal code IS read back directly, because {@code getAddressZip} is public -- a
 * postal code is not a protected column.</p>
 */
class AccountUpdatePreservationTest {

    /** The account every case here edits. */
    private static final long ACCOUNT_ID = 10_000_000_001L;

    /** The customer behind that account. */
    private static final long CUSTOMER_ID = 900_000_001L;

    /** The card that ties the two together, so the by-account path resolves. */
    private static final String CARD_NUMBER = "4000123456789010";

    /** Ciphertext standing for a government-issued identifier already stored. */
    private static final byte[] STORED_GOVERNMENT_ID = "stored-govt-ciphertext".getBytes(
            StandardCharsets.UTF_8);

    /** Ciphertext standing for a national identifier already stored. */
    private static final byte[] STORED_NATIONAL_ID = "stored-ssn-ciphertext".getBytes(
            StandardCharsets.UTF_8);

    /**
     * A stored postal code keeps its extension when the submitted five characters are unchanged.
     *
     * <p>Assumptions: this is the case the seed data makes matter. Thirty of the fifty customer records
     * carry a real four-digit extension in positions six to ten, and the screen shows only the leading
     * five -- so a submitter editing a telephone number and saving would have dropped the extension on
     * any of those thirty. The reference does exactly that; this is the documented divergence.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unchanged postal code keeps the stored extension the screen never showed")
    void anUnchangedPostalCodeKeepsItsExtension() {
        Fixture fixture = new Fixture("19852-6716");

        fixture.update(fixture.request().zipCode("19852").build());

        assertThat(fixture.customer.getAddressZip()).isEqualTo("19852-6716");
    }

    /**
     * A changed postal code does NOT retain the extension of the code it replaced.
     *
     * <p>Assumptions: this half is asserted with the half above because preserving unconditionally would
     * be a different and worse defect -- a customer who moved would keep the previous address's
     * extension, which is a wrong value rather than a lost one. The condition is what makes the
     * divergence narrow.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a changed postal code drops the extension belonging to the old one")
    void aChangedPostalCodeDropsTheOldExtension() {
        Fixture fixture = new Fixture("19852-6716");

        fixture.update(fixture.request().zipCode("22770").build());

        assertThat(fixture.customer.getAddressZip()).isEqualTo("22770     ");
    }

    /**
     * An omitted government-issued identifier leaves the stored ciphertext in place.
     *
     * <p>Assumptions: this is the centre of the sensitive-data finding. The submitter is shown a mask, so
     * an omitted value means "I did not edit this" -- and the previous behaviour wrote {@code null},
     * destroying a value the submitter had never seen and could not re-supply.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an omitted government-issued identifier is resolved as PRESERVE, not as a deletion")
    void anOmittedGovernmentIdentifierIsPreserved() {
        assertThat(governmentIntentFor(null)).hasToString("ProtectedValueUpdate[preserve]");
    }

    /**
     * The reference's own removal marker clears the government-issued identifier.
     *
     * <p>Assumptions: removal must remain POSSIBLE, or preserving an omitted value would make the column
     * write-once. The marker is the reference's own -- {@code app/cbl/COACTUPC.cbl} L1401 to L1403 tests
     * the screen field for it -- so the target adds the ability to tell removal from an unedited field
     * without adding a convention a user must learn.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the reference's removal marker is resolved as CLEAR")
    void theRemovalMarkerIsResolvedAsClear() {
        assertThat(governmentIntentFor("*")).hasToString("ProtectedValueUpdate[clear]");
    }

    /**
     * A supplied government-issued identifier replaces the stored ciphertext.
     *
     * <p>Assumptions: asserted alongside the other two so all three intents are covered, and the
     * substituted boundary is what makes the replacement identifiable -- it returns bytes derived from
     * the value it was given, so this case can show the column holds the NEW value rather than merely
     * that it changed.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a supplied government-issued identifier is resolved as REPLACE")
    void aSuppliedGovernmentIdentifierIsResolvedAsReplace() {
        assertThat(governmentIntentFor("NEWGOVTID")).hasToString("ProtectedValueUpdate[replace]");
    }

    /**
     * An unedited national identifier is preserved rather than re-enciphered.
     *
     * <p>Assumptions: re-enciphering an unedited value produces fresh ciphertext on every save, which
     * makes the column change in every audit of the table and advances the row's version for a value that
     * did not change -- so a concurrent reader's precondition would fail for an edit that never happened.
     * The column is declared not null, so preserve and replace are the only two intents available.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unedited national identifier is resolved as PRESERVE, not re-enciphered")
    void anUneditedNationalIdentifierIsPreserved() {
        assertThat(nationalIntentFor(null, null, null))
                .hasToString("ProtectedValueUpdate[preserve]");
    }

    /**
     * A submitted national identifier is resolved as a replacement.
     *
     * <p>Assumptions: asserted beside the preserve case so both available intents are covered. The column
     * is declared not null, so a clearing intent is not among them and the entity refuses one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a submitted national identifier is resolved as REPLACE")
    void aSubmittedNationalIdentifierIsResolvedAsReplace() {
        assertThat(nationalIntentFor("123", "45", "6789"))
                .hasToString("ProtectedValueUpdate[replace]");
    }

    /**
     * Captures the government-identifier intent the mapper hands the entity for a submitted value.
     *
     * @param submitted the submitted value, the removal marker, or {@code null} for an omitted field
     * @return the intent, never {@code null}
     */
    private static Customer.ProtectedValueUpdate governmentIntentFor(String submitted) {
        return capturedIntents(new RequestBuilder().governmentIssuedId(submitted).build())
                .governmentIdentifier();
    }

    /**
     * Captures the national-identifier intent the mapper hands the entity for three submitted parts.
     *
     * @param part1 the first part, or {@code null} for an omitted field
     * @param part2 the second part, or {@code null} for an omitted field
     * @param part3 the third part, or {@code null} for an omitted field
     * @return the intent, never {@code null}
     */
    private static Customer.ProtectedValueUpdate nationalIntentFor(String part1, String part2,
            String part3) {
        return capturedIntents(new RequestBuilder().ssn(part1, part2, part3).build())
                .nationalIdentifier();
    }

    /**
     * Applies a request against a substituted entity and returns the two intents it was handed.
     *
     * <p>Assumptions: the entity is a MOCK so the two intents can be captured at the boundary they are
     * decided on. Its stored postal code is stubbed because the mapper reads it to decide whether the
     * extension survives, and an unstubbed read would make that decision run against {@code null}.</p>
     *
     * @param request the submitted edit; must not be {@code null}
     * @return the pair of intents the mapper handed the entity, never {@code null}
     */
    private static CapturedIntents capturedIntents(AccountUpdateRequest request) {
        Customer stored = mock(Customer.class);
        when(stored.getAddressZip()).thenReturn("12546     ");

        new CustomerMapper(Fixture::recognisableCiphertext).applyUpdate(stored, request);

        ArgumentCaptor<Customer.ProtectedValueUpdate> national =
                ArgumentCaptor.forClass(Customer.ProtectedValueUpdate.class);
        ArgumentCaptor<Customer.ProtectedValueUpdate> government =
                ArgumentCaptor.forClass(Customer.ProtectedValueUpdate.class);
        verify(stored).applyUpdate(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), national.capture(), government.capture(), any(), any(), any(),
                anyShort());
        return new CapturedIntents(national.getValue(), government.getValue());
    }

    /**
     * The pair of protected-identifier intents one application handed the entity.
     *
     * @param nationalIdentifier the intent for the national identifier
     * @param governmentIdentifier the intent for the government-issued identifier
     */
    private record CapturedIntents(Customer.ProtectedValueUpdate nationalIdentifier,
            Customer.ProtectedValueUpdate governmentIdentifier) {
    }

    /**
     * A stale precondition is refused and NOTHING is written.
     *
     * <p>Assumptions: the assertion is that no save happened at all, not merely that the call raised. The
     * reference checks before rewriting and issues a rollback rather than a write, so a stale submission
     * must leave the rows exactly as they were -- and a partly-applied edit that was rolled back is a
     * different guarantee from one that was never applied.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a stale revision is refused and neither row is written")
    void aStaleRevisionIsRefusedAndNothingIsWritten() {
        Fixture fixture = new Fixture("12546     ");
        AccountUpdateRequest request = fixture.request().build();

        assertThatExceptionOfType(RecordConflictException.class)
                .isThrownBy(() -> fixture.service.update(ACCOUNT_ID, request, "99-99"));

        verify(fixture.customers, never()).saveAndFlush(any());
        verify(fixture.accounts, never()).saveAndFlush(any());
    }

    /**
     * A blank precondition is refused, so the check cannot be opted out of by omission.
     *
     * <p>Assumptions: treating blank as "no opinion" would make the whole check opt-in, and a caller that
     * sent an empty header would get exactly the silent-overwrite behaviour the check removes.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a blank revision is refused rather than treated as no opinion")
    void aBlankRevisionIsRefused() {
        Fixture fixture = new Fixture("12546     ");
        AccountUpdateRequest request = fixture.request().build();

        assertThatExceptionOfType(RecordConflictException.class)
                .isThrownBy(() -> fixture.service.update(ACCOUNT_ID, request, "  "));

        verify(fixture.customers, never()).saveAndFlush(any());
    }

    /**
     * The revision the service publishes is the one it then accepts.
     *
     * <p>Assumptions: this closes the loop between the two halves of the contract. A token the read path
     * publishes and the write path rejects would make every update fail, and neither half asserted alone
     * would catch it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the published revision is accepted by the update that follows it")
    void thePublishedRevisionIsAcceptedByTheUpdate() {
        Fixture fixture = new Fixture("12546     ");

        String published = fixture.service.currentRevision(ACCOUNT_ID);
        fixture.service.update(ACCOUNT_ID, fixture.request().build(), published);

        verify(fixture.customers).saveAndFlush(fixture.customer);
        verify(fixture.accounts).saveAndFlush(fixture.account);
    }

    /** Wires the service over substituted repositories and one stored account, customer and card. */
    private static final class Fixture {

        /** The substituted account repository. */
        private final AccountRepository accounts = mock(AccountRepository.class);

        /** The substituted customer repository. */
        private final CustomerRepository customers = mock(CustomerRepository.class);

        /** The substituted cross-reference repository, which resolves the by-account path. */
        private final CardXrefRepository crossReferences = mock(CardXrefRepository.class);

        /** The stored account row every case edits. */
        private final Account account;

        /** The stored customer row every case edits. */
        private final Customer customer;

        /** The service under test. */
        private final AccountUpdateService service;

        /**
         * Builds the fixture with a stated stored postal code.
         *
         * @param storedPostalCode the ten-character stored value; must not be {@code null}
         */
        Fixture(String storedPostalCode) {
            this.account = new Account(ACCOUNT_ID, "Y", new BigDecimal("100.00"),
                    new BigDecimal("5000.00"), new BigDecimal("500.00"),
                    LocalDate.of(2020, 1, 1), LocalDate.of(2027, 12, 31), LocalDate.of(2024, 6, 1),
                    BigDecimal.ZERO, BigDecimal.ZERO, "12345", "DEFAULT");
            this.customer = new Customer(CUSTOMER_ID, "GRACE", null, "HOPPER",
                    "1 NAVY YARD", null, "ARLINGTON", "VA", "USA", storedPostalCode,
                    "(703)5550101  ", null, STORED_NATIONAL_ID, STORED_GOVERNMENT_ID,
                    LocalDate.of(1906, 12, 9), "0000000001", "Y", (short) 800);

            when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(this.account));
            when(this.customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(this.customer));
            when(this.crossReferences.findByAccountIdOrderByCardNumAsc(ACCOUNT_ID))
                    .thenReturn(List.of(new CardXref(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID)));
            when(this.customers.saveAndFlush(any(Customer.class)))
                    .thenAnswer(call -> call.getArgument(0));
            when(this.accounts.saveAndFlush(any(Account.class)))
                    .thenAnswer(call -> call.getArgument(0));

            // WHY : Assumptions: the REAL AddressValidationService is used over a permissive lookup
            //       rather than a mock of the service, so the wiring this fixture depends on is
            //       genuinely exercised. A mocked service would return null from every validator and
            //       the preservation assertions below would then pass for the wrong reason -- they
            //       would be measuring a stubbed-out edit chain rather than the real one.
            this.service = new AccountUpdateService(this.accounts, this.customers,
                    this.crossReferences, new AccountContextMapper(),
                    new CustomerMapper(Fixture::recognisableCiphertext),
                    new AddressValidationService(new PermissiveLookup()));
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
         * Starts a request builder holding values every required field accepts.
         *
         * @return the builder, never {@code null}
         */
        RequestBuilder request() {
            return new RequestBuilder();
        }

        /**
         * Stands in for the protection boundary, returning bytes that name what they protect.
         *
         * <p>Assumptions: the result is DERIVED from the input rather than random, which is the whole
         * reason a substitute is used here. A real cipher returns different bytes every time by design,
         * so a case could not distinguish "this column holds the new value" from "this column was
         * re-enciphered with the old one".</p>
         *
         * @param clearText the value to protect
         * @param field the column it belongs to
         * @return recognisable bytes naming the column and the value, never {@code null}
         */
        private static byte[] recognisableCiphertext(String clearText, String field) {
            return ("enc:" + field + ":" + clearText).getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Assembles an account update request, varying only the fields a case is about.
     *
     * <p>Assumptions: a builder exists because the record has forty-two components and every case here
     * varies one or two. Constructing it positionally per case would put forty untouched values in front
     * of each assertion, and a transposition among them would look like a mapper defect.</p>
     */
    private static final class RequestBuilder {

        /** The submitted postal code. */
        private String zipCode = "12546";

        /** The submitted government-issued identifier. */
        private String governmentIssuedId = "GOVTID0001";

        /** The submitted first part of the national identifier. */
        private String ssnPart1 = "123";

        /** The submitted second part of the national identifier. */
        private String ssnPart2 = "45";

        /** The submitted third part of the national identifier. */
        private String ssnPart3 = "6789";

        /**
         * Sets the submitted postal code.
         *
         * @param value the five-character screen value
         * @return this builder, never {@code null}
         */
        RequestBuilder zipCode(String value) {
            this.zipCode = value;
            return this;
        }

        /**
         * Sets the submitted government-issued identifier.
         *
         * @param value the value, the removal marker, or {@code null} for an omitted field
         * @return this builder, never {@code null}
         */
        RequestBuilder governmentIssuedId(String value) {
            this.governmentIssuedId = value;
            return this;
        }

        /**
         * Sets the three submitted parts of the national identifier.
         *
         * @param part1 the first part, or {@code null} for an omitted field
         * @param part2 the second part, or {@code null} for an omitted field
         * @param part3 the third part, or {@code null} for an omitted field
         * @return this builder, never {@code null}
         */
        RequestBuilder ssn(String part1, String part2, String part3) {
            this.ssnPart1 = part1;
            this.ssnPart2 = part2;
            this.ssnPart3 = part3;
            return this;
        }

        /**
         * Builds the request.
         *
         * @return the assembled request, never {@code null}
         */
        AccountUpdateRequest build() {
            return new AccountUpdateRequest(
                    "10000000001", "Y", "5000.00", "500.00", "100.00", "0.00", "0.00",
                    "2020", "01", "01",
                    "2027", "12", "31",
                    "2024", "06", "01",
                    "DEFAULT", "900000001",
                    this.ssnPart1, this.ssnPart2, this.ssnPart3,
                    "1906", "12", "09",
                    "800",
                    "GRACE", null, "HOPPER",
                    "1 NAVY YARD", null, "ARLINGTON", "VA", "USA", this.zipCode,
                    "703", "555", "0101",
                    null, null, null,
                    this.governmentIssuedId, "0000000001", "Y");
        }
    }

    /**
     * A reference lookup that accepts every code, so the address edits never refuse this fixture.
     *
     * <p>Assumptions: this double answers "present" rather than being a mock, because these tests are
     * about what an edit PRESERVES and not about the address allow-lists. A refusal here would abort
     * the update before the mapper ran and every preservation assertion would fail for a reason that
     * has nothing to do with preservation. The refusal path is covered by
     * {@code AccountAddressValidationTest} instead.
     */
    private static final class PermissiveLookup
            implements AddressValidationService.ReferenceAddressLookup {

        /**
         * Reports every area code as belonging to the general-purpose list.
         *
         * @param areaCode the candidate area code, ignored
         * @return always the general-purpose classification, never empty
         */
        @Override
        public Optional<AddressValidationService.AreaCodeClass> findAreaCodeClass(String areaCode) {
            return Optional.of(AddressValidationService.AreaCodeClass.GENERAL_PURPOSE);
        }

        /**
         * Reports every state code as present.
         *
         * @param stateCode the candidate state code, ignored
         * @return always {@code true}
         */
        @Override
        public boolean stateCodeExists(String stateCode) {
            return true;
        }

        /**
         * Reports every state and postal-prefix pairing as present.
         *
         * @param stateZipPrefix the candidate pairing, ignored
         * @return always {@code true}
         */
        @Override
        public boolean stateZipPrefixExists(String stateZipPrefix) {
            return true;
        }
    }

}
