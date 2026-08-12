package com.carddemo.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.carddemo.account.dto.AccountUpdateResponse;
import com.carddemo.account.mapper.AccountMapper;
import com.carddemo.account.mapper.CustomerMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.common.error.RecordConflictException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

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
     * Neither write-path load refusal names the account it was asked for.
     *
     * <p>Refactoring Rationale: both messages used to end in the account identifier. The sensitive-data
     * contract in {@code docs/architecture/observability.md} names account and customer identifiers
     * alongside the primary account number as values a durable diagnostic may not carry, and requires a
     * prohibited value to be OMITTED rather than abbreviated. These two are durable in two places at
     * once, because the shared advice writes them to the operational record and returns them in the
     * response body.</p>
     *
     * <p>Assumptions: both refusals are asserted in ONE case rather than two, because the property is
     * that no load refusal on this path names the key -- a case per method would pass while a third
     * method added later disclosed freely. The exact remaining text is pinned alongside the absence, so
     * emptying a message to satisfy the absence would fail.</p>
     *
     * <p>Refactoring Rationale: this note used to concede that the route carried the identifier in its
     * request line anyway, "since {@code PUT /api/v1/accounts/{accountId}} stays keyed for an end user".
     * It does not stay keyed: the edit is now {@code POST /api/v1/accounts/update} and takes its key from
     * the submitted record, so the absence asserted here and the address the caller uses now agree
     * instead of the absence being defended against a disclosure elsewhere. The correlation identifier
     * still joins this record to the request that provoked it, and now neither of the two holds the
     * account.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("neither write-path load refusal names the account identifier")
    void neitherLoadRefusalNamesTheAccount() {
        String identifier = String.valueOf(ACCOUNT_ID);

        Fixture absentAccount = new Fixture("12546     ");
        AccountUpdateRequest accountRequest = absentAccount.request().build();
        when(absentAccount.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> absentAccount.service.update(ACCOUNT_ID, accountRequest, "12546"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageNotContaining(identifier)
                .hasMessage("no account master row exists for the requested account");

        Fixture absentCustomer = new Fixture("12546     ");
        AccountUpdateRequest customerRequest = absentCustomer.request().build();
        when(absentCustomer.crossReferences.findFirstByAccountIdOrderByCardNumAsc(ACCOUNT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> absentCustomer.service.update(ACCOUNT_ID, customerRequest, "12546"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageNotContaining(identifier)
                .hasMessage("no customer could be resolved for the requested account");
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
     * The revision the service publishes is the one it then accepts, and the update republishes it.
     *
     * <p>Assumptions: this closes the loop between the two halves of the contract. A token the read path
     * publishes and the write path rejects would make every update fail, and neither half asserted alone
     * would catch it.</p>
     *
     * <p>⚠️ Refactoring Rationale: the presented token is now derived from the fixture's OWN stored rows
     * through {@link AccountRevision}, where it was previously obtained by calling a
     * {@code currentRevision} operation on the object under test. That operation has been removed, and its
     * removal is why: the adapter used it as a SECOND read-only transaction to obtain an entity tag beside
     * a body composed by a different one, so a caller could be handed a tag and a body describing two
     * different states. Deriving the token here is also a stronger arrangement in its own right -- the
     * object under test no longer supplies the input it is being tested against.</p>
     *
     * <p>Assumptions: the answer is asserted to carry a revision as well, because the round trip is only
     * closed if the update publishes a token a NEXT edit can present. A caller performing consecutive
     * edits has no other source for it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the published revision is accepted by the update, which republishes the next one")
    void thePublishedRevisionIsAcceptedByTheUpdate() {
        Fixture fixture = new Fixture("12546     ");

        String published = AccountRevision.of(fixture.account, fixture.customer);
        AccountUpdateService.RevisionedAccountUpdate answer =
                fixture.service.update(ACCOUNT_ID, fixture.request().build(), published);

        verify(fixture.customers).saveAndFlush(fixture.customer);
        verify(fixture.accounts).saveAndFlush(fixture.account);
        assertThat(answer.response()).isNotNull();
        assertThat(answer.revision())
                .as("the update must publish the token a consecutive edit will present")
                .isNotBlank();
    }

    /**
     * An omitted account key is refused with the baseline's own wording, and nothing is written.
     *
     * <p>Purpose: the submitted account key was previously edited on ONE path only -- the branch an
     * absent row selects, which the update path cannot reach because its loads either return a row or
     * raise. On the live path the key was not edited at all, so an omitted one passed straight into the
     * old-versus-new comparison.</p>
     *
     * <p>Assumptions: the wording asserted is the baseline's verbatim sentence for an absent key rather
     * than the target-authored mismatch sentence, because the form edit runs first and this submission
     * fails it. A caller who simply left the field out therefore still sees what the reference shows.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an omitted account key is refused with the baseline's absent-key sentence")
    void anOmittedAccountKeyIsRefused() {
        Fixture fixture = new Fixture("12546     ");
        AccountUpdateRequest request = fixture.request().accountId(null).build();

        assertThatThrownBy(() -> fixture.update(request))
                .hasMessageContaining(AccountUpdateService.MESSAGE_ACCOUNT_NOT_PROVIDED);

        verify(fixture.customers, never()).saveAndFlush(any());
        verify(fixture.accounts, never()).saveAndFlush(any());
    }

    /**
     * An account key naming a different account is refused, and nothing is written.
     *
     * <p>Purpose: this is the exact submission the earlier shape mishandled. The key is well formed, so
     * every form edit passes; it names another account, so the old-versus-new comparison reported a
     * CHANGE; every non-key field matched the stored row, so every remaining edit passed; and neither
     * mapper assigns a key, so both rows were written back unchanged and the response carried the
     * accepted sentence. The refusal replaces an answer that was wrong in the one direction a caller
     * could exploit -- turning "nothing changed" into "update accepted".</p>
     *
     * <p>Assumptions: the wording asserted is the TARGET-AUTHORED mismatch sentence, because the
     * baseline has none: its screen carries one account-number field, so the value it fetched by and the
     * value it submits cannot disagree there. The refusal is registered as
     * {@code D-UPDATE-BODY-KEY-MUST-NAME-ROW}.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an account key naming another account is refused and nothing is written")
    void anAccountKeyNamingAnotherAccountIsRefused() {
        Fixture fixture = new Fixture("12546     ");
        AccountUpdateRequest request = fixture.request().accountId("10000000002").build();

        assertThatThrownBy(() -> fixture.update(request))
                .hasMessageContaining(AccountUpdateService.MESSAGE_ACCOUNT_KEY_NOT_ADDRESSED);

        verify(fixture.customers, never()).saveAndFlush(any());
        verify(fixture.accounts, never()).saveAndFlush(any());
    }

    /**
     * An omitted customer key is refused, and nothing is written.
     *
     * <p>Purpose: the customer key was edited on NO path whatsoever. It participated in the
     * old-versus-new comparison and in nothing else, which is the same defect as the account key's with
     * one aggravation: the customer is not addressed by the caller at all, so it is the one component
     * that names a row the caller did not select.</p>
     *
     * <p>Assumptions: one wording covers an absent key and a mismatched one, for the reason recorded on
     * the edit itself -- the baseline has no sentence for either, and the remedy is identical.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an omitted customer key is refused and nothing is written")
    void anOmittedCustomerKeyIsRefused() {
        Fixture fixture = new Fixture("12546     ");
        AccountUpdateRequest request = fixture.request().customerId(null).build();

        assertThatThrownBy(() -> fixture.update(request))
                .hasMessageContaining(AccountUpdateService.MESSAGE_CUSTOMER_KEY_NOT_LOADED);

        verify(fixture.customers, never()).saveAndFlush(any());
        verify(fixture.accounts, never()).saveAndFlush(any());
    }

    /**
     * A customer key naming a different customer is refused, and nothing is written.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a customer key naming another customer is refused and nothing is written")
    void aCustomerKeyNamingAnotherCustomerIsRefused() {
        Fixture fixture = new Fixture("12546     ");
        AccountUpdateRequest request = fixture.request().customerId("900000002").build();

        assertThatThrownBy(() -> fixture.update(request))
                .hasMessageContaining(AccountUpdateService.MESSAGE_CUSTOMER_KEY_NOT_LOADED);

        verify(fixture.customers, never()).saveAndFlush(any());
        verify(fixture.accounts, never()).saveAndFlush(any());
    }

    /**
     * A submission that changes nothing reports the no-change sentence, not the accepted one.
     *
     * <p>Purpose: this is the regression the key edits exist to make reachable again. With the keys
     * unedited, a submission could reach the accepted sentence while changing nothing simply by omitting
     * one of them, and no case asserted that the no-change sentence was still produced for a submission
     * that genuinely matched the stored rows. It is asserted through the RESPONSE rather than through the
     * verdict, because the response is what a caller sees and the two sentences are the whole of the
     * observable difference between the two outcomes.</p>
     *
     * <p>Assumptions: the two protected identifiers are omitted from the submission, because the
     * comparison requires them to be never supplied -- they are stored as ciphertext and a submission
     * cannot restate them. Every other field is submitted at the stored row's own value.</p>
     *
     * <p>Assumptions: the postal code is submitted at its FIVE-character screen width while the stored
     * column holds the same digits padded to ten, so this case also pins the comparison as one that
     * trims padding on both sides. Submitting the padded ten characters instead would be refused by the
     * mapper, which narrows the value to the five the reference field declares -- which is the evidence
     * that the two widths are genuinely different fields and not one field spelled two ways.</p>
     *
     * <p>Assumptions: the customer key is submitted with a LEADING ZERO it is not stored with, so this
     * case also pins the comparison as numeric rather than textual. The baseline's field is
     * {@code PIC X(09)} redefined as {@code PIC 9(09)}, so both spellings are values of one field there
     * too, and a textual comparison would refuse a submission the reference accepts and would then
     * report a change where there is none.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a submission matching the stored rows reports NO CHANGES DETECTED")
    void aSubmissionThatChangesNothingReportsNoChangesDetected() {
        Fixture fixture = new Fixture("12546     ");
        AccountUpdateRequest request = fixture.request()
                .customerId("0900000001")
                .submittingNoProtectedIdentifier()
                .build();

        // WHY : Assumptions: the revision is composed from the fixture's OWN rows through
        //       AccountRevision, not read back from the object under test. The service publishes no
        //       currentRevision(long) operation -- it was withdrawn because a caller that reads the
        //       revision it is about to submit cannot detect a change made between the two calls -- and
        //       every other case in this class composes it the same way.
        AccountUpdateResponse response = fixture.service.update(ACCOUNT_ID, request,
                AccountRevision.of(fixture.account, fixture.customer)).response();

        assertThat(response.returnMessage())
                .as("the no-change sentence, not the accepted one")
                .isEqualTo(AccountUpdateService.MESSAGE_NO_CHANGES_DETECTED);
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
            // WHY : Refactoring Rationale: the stored telephone number carries its PUNCTUATION and its
            //   two-character trailing pad, being exactly the fifteen characters CustomerMapper composes
            //   -- an opening character, the area code, a closing character, the exchange prefix, a
            //   separator, the line number and two blanks. This fixture previously stored the same digits
            //   WITHOUT the separator, which no case noticed because no case had compared a submitted
            //   number against the stored one; the no-change case does compare them, and against the
            //   unpunctuated value it reported a change that was not there. The corrected value is the
            //   one the reference's own redefinition predicts at app/cbl/COACTUPC.cbl L725 through L731,
            //   so the fixture now agrees with the layout every other reader of this column assumes.
            this.customer = new Customer(CUSTOMER_ID, "GRACE", null, "HOPPER",
                    "1 NAVY YARD", null, "ARLINGTON", "VA", "USA", storedPostalCode,
                    "(703)555-0101  ", null, STORED_NATIONAL_ID, STORED_GOVERNMENT_ID,
                    LocalDate.of(1906, 12, 9), "0000000001", "Y", (short) 800);

            when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(this.account));
            when(this.customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(this.customer));
            // Assumptions: the BOUNDED single-row query is stubbed, because the service resolves the
            //   customer through it rather than reading every cross-reference row and taking the first.
            //   The row it yields is identical either way -- both name the lowest card number -- so the
            //   only thing that changed is how much of an account's cardholder data reaches the heap.
            when(this.crossReferences.findFirstByAccountIdOrderByCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(new CardXref(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID)));
            when(this.customers.saveAndFlush(any(Customer.class)))
                    .thenAnswer(call -> call.getArgument(0));
            when(this.accounts.saveAndFlush(any(Account.class)))
                    .thenAnswer(call -> call.getArgument(0));

            // WHY : Assumptions: the REAL AddressValidationService is used over a permissive lookup
            //       rather than a mock of the service, so the wiring this fixture depends on is
            //       genuinely exercised. A mocked service would return null from every validator and
            //       the preservation assertions below would then pass for the wrong reason -- they
            //       would be measuring a stubbed-out edit chain rather than the real one.
            // WHY : Refactoring Rationale: the ACCOUNT mapper is supplied where the narrower context
            //       mapper used to be, because the service now applies the account half of a submission
            //       as well as the customer half and assembles its response through that mapper. Before
            //       the change the account region was edited and then discarded unapplied, so no
            //       account-side preservation could have been observed here at all.
            // WHY : Assumptions: the time source is FIXED rather than the system clock, so the
            //       date-of-birth range edit the service performs has a pinned boundary and a case added
            //       here later cannot start depending on the day the suite runs.
            this.service = new AccountUpdateService(this.accounts, this.customers,
                    this.crossReferences, new AccountMapper(),
                    new CustomerMapper(Fixture::recognisableCiphertext),
                    new AddressValidationService(new PermissiveLookup()),
                    Clock.fixed(LocalDate.of(2022, 7, 18).atStartOfDay(ZoneOffset.UTC).toInstant(),
                            ZoneOffset.UTC),
                    // Assumptions: the transaction manager is substituted, so both templates run their
                    //   callbacks and commit nothing. These cases assert which values are written and
                    //   preserved, not that a database committed; the commit itself belongs to the
                    //   container-backed integration test.
                    mock(PlatformTransactionManager.class));
        }

        /**
         * Applies an edit using the revision the stored rows currently hold.
         *
         * @param request the submitted edit; must not be {@code null}
         */
        void update(AccountUpdateRequest request) {
            // WHY : Assumptions: the precondition is derived from the fixture's own stored rows rather
            //       than read back from the object under test. AccountRevision owns the format that both
            //       published routes render and that the write path compares, so a token built here is
            //       the same token a caller would have been given -- and building it here keeps the
            //       object under test from supplying its own input.
            this.service.update(ACCOUNT_ID, request,
                    AccountRevision.of(this.account, this.customer));
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

        /** The submitted account key, which must name the account being updated. */
        private String accountId = "10000000001";

        /** The submitted customer key, which must name the customer that account holds. */
        private String customerId = "900000001";

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
         * Sets the submitted account key.
         *
         * @param value the eleven-character screen value, or {@code null} for an omitted field
         * @return this builder, never {@code null}
         */
        RequestBuilder accountId(String value) {
            this.accountId = value;
            return this;
        }

        /**
         * Sets the submitted customer key.
         *
         * @param value the nine-character screen value, or {@code null} for an omitted field
         * @return this builder, never {@code null}
         */
        RequestBuilder customerId(String value) {
            this.customerId = value;
            return this;
        }

        /**
         * Removes every field whose absence the no-change comparison requires.
         *
         * <p>Assumptions: the two protected identifiers are cleared rather than set to their stored
         * values, because the comparison requires them to be NEVER SUPPLIED -- their stored form is
         * ciphertext and a submission cannot restate it. That is the comparison's own rule and not a
         * convenience of this builder.</p>
         *
         * @return this builder, never {@code null}
         */
        RequestBuilder submittingNoProtectedIdentifier() {
            return ssn(null, null, null).governmentIssuedId(null);
        }

        /**
         * Builds the request.
         *
         * @return the assembled request, never {@code null}
         */
        AccountUpdateRequest build() {
            return new AccountUpdateRequest(
                    this.accountId, "Y", "5000.00", "500.00", "100.00", "0.00", "0.00",
                    "2020", "01", "01",
                    "2027", "12", "31",
                    "2024", "06", "01",
                    "DEFAULT", this.customerId,
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
