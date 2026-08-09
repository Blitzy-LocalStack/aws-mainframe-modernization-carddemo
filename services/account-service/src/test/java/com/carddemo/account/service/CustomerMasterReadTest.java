package com.carddemo.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.account.domain.Customer;
import com.carddemo.account.dto.CustomerResponse;
import com.carddemo.account.mapper.AccountContextMapper;
import com.carddemo.account.mapper.AccountMapper;
import com.carddemo.account.mapper.CardXrefMapper;
import com.carddemo.account.mapper.CustomerMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

/**
 * Pins the two customer-master reads of the view service: the keyed read and the bounded ascending scan.
 *
 * <p>Purpose. Both operations migrate one reference program, {@code app/cbl/CBCUS01C.cbl}. Its file
 * declares {@code ACCESS MODE IS SEQUENTIAL} at L31 over {@code RECORD KEY IS FD-CUST-ID} at L32, and its
 * body is a single {@code PERFORM UNTIL END-OF-FILE = 'Y'} at L74 through L81 that reads one record at a
 * time and writes it to a print stream at L78. So the reference supplies the KEY and the ORDER, and
 * nothing else: it filters nothing, pages nothing, and never reaches a record by key at all. What has to
 * be asserted is therefore what the migration added on top of that -- a bound, an opaque resume position,
 * a further-rows answer and a masking projection -- because none of those can be checked against a
 * reference statement, only against the decisions this service records.
 *
 * <p>Refactoring Rationale: these two methods were authored with no behavioural test. Route membership,
 * the published property names and the security expression were asserted by the sibling contract class,
 * which reaches the handler by direct invocation and substitutes the projection -- so it could not, and
 * did not, observe which query the scan chooses, how wide it asks the read to be, which row it discards,
 * which key it seals, or that the masking survives the page as well as the keyed read. A page that
 * silently sealed the surplus row's key, or clamped nothing, or resumed through the descending query,
 * would have passed everything that existed before this class.
 *
 * <p>Assumptions: the seal is a REAL {@link CursorToken} and the projection is the REAL
 * {@link CustomerMapper}, while the store is substituted. The split follows what each case is about. A
 * substituted sealer returning arbitrary text would be refused by the envelope rather than exercised, and
 * -- more to the point -- a real seal lets a case OPEN the boundary token it was handed and so assert
 * WHICH key was sealed, which is the one property the finding's own resolution turns on. A real mapper is
 * used because the masking of the two stored identifiers is an invariant of these methods rather than of
 * their caller, so a substituted projection would assert the invariant away.
 *
 * <p>Assumptions: the projection's key provider is satisfied by a substitute that returns fixed bytes, so
 * no key material of any kind appears in this class. The port exists for exactly this reason, and the
 * ciphertext is never read back: both identifiers are published as a constant marker, so what a case can
 * observe about them is that neither is derived from the stored bytes at all.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.
 */
class CustomerMasterReadTest {

    /**
     * The customer every keyed case reads.
     *
     * <p>Assumptions: forty-two rather than a nine-digit value, deliberately. {@code CUST-ID} is
     * {@code PIC 9(09)} at L5 of {@code app/cpy/CVCUS01Y.cpy}, so the PUBLISHED form is nine digits with
     * leading zeros while the STORED key is the number itself -- and one case below asserts the boundary
     * token carries the number and not the padded rendering. A value that was already nine digits long
     * could not tell the two apart.</p>
     */
    private static final long CUSTOMER_ID = 42L;

    /** The nine-digit rendering of that identifier, the form the response publishes. */
    private static final String CUSTOMER_ID_DIGITS = "000000042";

    /**
     * The sentence an absent customer is raised with.
     *
     * <p>Assumptions: carried verbatim from the condition declared at L133 with L134 of
     * {@code app/cbl/COACTVWC.cbl}, which is the text the gate at L713 tests and the same text the update
     * program declares at L501 with L502 of {@code app/cbl/COACTUPC.cbl}. It is restated here rather than
     * imported because the service holds it privately, and a case comparing against a value it read from
     * the class under test would agree with any text that class chose.</p>
     */
    private static final String NOT_FOUND_SENTENCE = "Did not find associated customer in master file";

    /**
     * The query identity the customer scan seals every boundary against.
     *
     * <p>Assumptions: restated as a literal for the same reason as the sentence above -- the service holds
     * it privately, and this class has to seal and open tokens under exactly the binding the service uses
     * or the refusal case below would pass for the wrong reason.</p>
     */
    private static final String SCAN_BINDING = "account.customers.scan";

    /**
     * A binding no scan in this service uses, for the refusal case.
     *
     * <p>Assumptions: it differs from the accepted binding in the query name alone, so the case shows that
     * the binding is what is checked rather than the token's shape or its lifetime.</p>
     */
    private static final String FOREIGN_BINDING = "account.accounts.scan";

    /** Key material for the real seal; test-only, and deliberately not a credential. */
    private static final byte[] CURSOR_KEY =
            "carddemo-account-customer-scan-test-cursor-material-not-a-secret"
                    .getBytes(StandardCharsets.UTF_8);

    /** How long a sealed position stays redeemable; generous, because no case here asserts expiry. */
    private static final Duration CURSOR_LIFETIME = Duration.ofHours(1);

    /** The page width most cases ask for, comfortably inside the ceiling the service declares. */
    private static final int PAGE_SIZE = 5;

    /** The ciphertext the substitute key provider returns for either protected identifier. */
    private static final byte[] FIXED_CIPHERTEXT = {0x01, 0x02, 0x03, 0x04};

    /** The customer master rows this class supplies, substituted for the store. */
    private CustomerRepository customers;

    /** The real seal every boundary token is produced and opened by. */
    private CursorToken sealer;

    /** The read path under test. */
    private AccountViewService reads;

    /**
     * Builds the read path over a substituted store, a real seal and a real projection.
     *
     * <p>Assumptions: the four collaborators this pair of methods never touches -- the account master, the
     * cross-reference, and the account and cross-reference projections -- are substituted with no
     * behaviour at all. A case reaching one of them would fail on a null rather than pass quietly, which
     * is the outcome wanted: neither method under test has any business reading an account.</p>
     */
    @BeforeEach
    void setUp() {
        this.customers = mock(CustomerRepository.class);
        this.sealer = new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);
        this.reads = new AccountViewService(mock(AccountRepository.class), this.customers,
                mock(CardXrefRepository.class), mock(AccountContextMapper.class),
                mock(AccountMapper.class), new CustomerMapper((clearText, field) -> FIXED_CIPHERTEXT),
                mock(CardXrefMapper.class), this.sealer);
    }

    /**
     * Verifies the keyed read publishes the record at record widths with both identifiers masked.
     *
     * <p>Assumptions: the masking is asserted on the value the METHOD returns rather than on the mapper in
     * isolation, because the finding is that nothing exercised this method -- and the property at stake is
     * that a caller of it cannot receive either identifier whole. The two are not alike in the reference:
     * {@code CUST-SSN} is {@code PIC 9(09)} at L17 of {@code app/cpy/CVCUS01Y.cpy} and
     * {@code CUST-GOVT-ISSUED-ID} is {@code PIC X(20)} at L18, so both are asserted rather than one
     * standing in for the other.</p>
     *
     * <p>Assumptions: the postal code is asserted at TEN characters, which is the record width at L14 of
     * the same copybook and not the five the account view screen shows. This shape is the record-width
     * shape, and a projection that had adopted the screen's narrowing would discard five stored
     * characters with no endpoint left to read them through.</p>
     */
    @Test
    @DisplayName("the keyed read publishes record widths and masks both stored identifiers")
    void theKeyedReadMasksBothStoredIdentifiers() {
        when(this.customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer(CUSTOMER_ID)));

        CustomerResponse published = this.reads.readCustomer(CUSTOMER_ID);

        assertThat(published.customerId())
                .as("the identifier travels as digits-only text at its declared nine-character width")
                .isEqualTo(CUSTOMER_ID_DIGITS);
        assertThat(published.ssnMasked())
                .as("no caller of this method may receive the national identifier whole")
                .isEqualTo(CustomerMapper.IDENTIFIER_REDACTED);
        assertThat(published.governmentIssuedIdMasked())
                .as("nor the government-issued identifier, which is a different width and type")
                .isEqualTo(CustomerMapper.IDENTIFIER_REDACTED);
        assertThat(published.zipCode())
                .as("the postal code is published at its RECORD width of ten, not the screen's five")
                .hasSize(10);
        assertThat(published.ficoCreditScore()).isEqualTo("789");
    }

    /**
     * Verifies an absent customer is raised, carrying the reference sentence rather than an empty answer.
     *
     * <p>Assumptions: the sentence is compared character for character, because it is the text a caller
     * displays and the shared advice renders it into a 404 body unchanged. The presence probe beside this
     * method answers a yes-or-no question instead; this one promises a representation, and there is no
     * representation of a row that is not there.</p>
     */
    @Test
    @DisplayName("an absent customer is raised with the reference sentence")
    void anAbsentCustomerIsRaisedWithTheReferenceSentence() {
        when(this.customers.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.reads.readCustomer(CUSTOMER_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(NOT_FOUND_SENTENCE);
    }

    /**
     * Verifies the opening page reads one row beyond the window and seals the last row it shows.
     *
     * <p>Assumptions: the further-rows answer is reached by the arrival of a surplus row and never by
     * counting the master, which is the reference's own method -- at L242 through L244 of
     * {@code app/cbl/COCRDLIC.cbl} the indicator is a single flag raised by discovering one record beyond
     * what the screen holds. The width of the read is therefore asserted exactly, because a read of the
     * page width alone could only answer the question by a second statement.</p>
     *
     * <p>Assumptions: the trailing boundary is OPENED and compared, not merely checked for presence.
     * Sealing the surplus row's key would advance a caller past a row it never received and drop a row
     * from the following page -- a defect no status code or item count can reveal, and the divergence the
     * reference itself has at L1212 through L1214 of {@code app/cbl/COCRDLIC.cbl}.</p>
     */
    @Test
    @DisplayName("the opening page probes one row beyond the window and seals the last row shown")
    void theOpeningPageProbesOneRowBeyondTheWindow() {
        when(this.customers.findAllByOrderByCustomerIdAsc(any(Limit.class)))
                .thenReturn(rows(1, PAGE_SIZE + 1));

        PageResponse<CustomerResponse> page = this.reads.listCustomers(null, PAGE_SIZE);

        assertThat(page.items()).hasSize(PAGE_SIZE);
        assertThat(page.hasNext()).isTrue();
        verify(this.customers).findAllByOrderByCustomerIdAsc(Limit.of(PAGE_SIZE + 1));
        verify(this.customers, never())
                .findByCustomerIdGreaterThanOrderByCustomerIdAsc(anyLong(), any(Limit.class));

        assertThat(this.sealer.open(SCAN_BINDING, page.lastKey()))
                .as("the trailing boundary names the last row RETURNED, never the surplus probe row")
                .isEqualTo(String.valueOf(PAGE_SIZE));
        assertThat(this.sealer.open(SCAN_BINDING, page.firstKey()))
                .as("the leading boundary names the first row of the page")
                .isEqualTo("1");
        assertThat(page.items().getFirst().ssnMasked())
                .as("the masking invariant holds on a PAGE as well as on the keyed read")
                .isEqualTo(CustomerMapper.IDENTIFIER_REDACTED);
    }

    /**
     * Verifies a supplied position is opened and bound to the resuming query as a number.
     *
     * <p>Assumptions: the bound is asserted as the opened key rather than as the token, and that is the
     * whole of the difference between this scan and the reference's echoed communication area. The
     * reference reads its browse state back from the terminal at each turn -- the block at L229 onward of
     * {@code app/cbl/COCRDLIC.cbl} -- and therefore trusts whatever came back; here a position is opened
     * before it reaches a predicate, so the number the query receives cannot be chosen by a caller.</p>
     *
     * <p>Assumptions: the opening query is asserted NEVER to run, because an implementation that passed a
     * sentinel key to the resuming query would produce the same rows for this case and a different set for
     * the opening page. The descending query is asserted never to run for the reason recorded on the
     * service: the reference walk has one get-next paragraph at L92 and no backward counterpart, so a
     * backward step would be a capability this program never had.</p>
     */
    @Test
    @DisplayName("a supplied position is opened and bound to the resuming query, forward only")
    void aSuppliedPositionIsOpenedAndBoundToTheResumingQuery() {
        String cursor = this.sealer.seal(SCAN_BINDING, "300");
        when(this.customers.findByCustomerIdGreaterThanOrderByCustomerIdAsc(eq(300L), any(Limit.class)))
                .thenReturn(rows(301, PAGE_SIZE));

        PageResponse<CustomerResponse> page = this.reads.listCustomers(cursor, PAGE_SIZE);

        assertThat(page.items()).hasSize(PAGE_SIZE);
        assertThat(page.hasNext())
                .as("the window came back exactly full, so no surplus row arrived and none is claimed")
                .isFalse();
        verify(this.customers)
                .findByCustomerIdGreaterThanOrderByCustomerIdAsc(300L, Limit.of(PAGE_SIZE + 1));
        verify(this.customers, never()).findAllByOrderByCustomerIdAsc(any(Limit.class));
        verify(this.customers, never())
                .findByCustomerIdLessThanOrderByCustomerIdDesc(anyLong(), any(Limit.class));
    }

    /**
     * Verifies a position this scan did not seal is refused before any query is issued.
     *
     * <p>Assumptions: the token used is well formed and unexpired and was sealed by the same key material
     * -- only its BINDING differs -- so the case shows that the query identity is checked rather than the
     * signature alone. Without that check a position minted by the account listing would resume the
     * customer scan against a key column it never named.</p>
     *
     * <p>Assumptions: the store is asserted untouched, because a refusal that happened after the read
     * would still raise while having already spent a database round trip on an attacker-supplied
     * value.</p>
     */
    @Test
    @DisplayName("a position sealed for another query is refused and no read is issued")
    void aPositionSealedForAnotherQueryIsRefused() {
        String foreign = this.sealer.seal(FOREIGN_BINDING, "300");

        assertThatThrownBy(() -> this.reads.listCustomers(foreign, PAGE_SIZE))
                .isInstanceOf(CursorToken.InvalidCursorException.class);

        verify(this.customers, never()).findAllByOrderByCustomerIdAsc(any(Limit.class));
        verify(this.customers, never())
                .findByCustomerIdGreaterThanOrderByCustomerIdAsc(anyLong(), any(Limit.class));
    }

    /**
     * Verifies a page size below the floor and above the ceiling are both clamped rather than refused.
     *
     * <p>Assumptions: the clamp is asserted at BOTH ends and through the width of the read, which is the
     * only place it is observable. The ceiling is enforced here as well as on the request parameter
     * because a bound declared only at the edge is a bound a second caller of this method would not
     * inherit, and the floor exists because a page of no rows would answer nothing.</p>
     *
     * <p>Assumptions: an out-of-range size is CLAMPED at this layer and REFUSED at the edge, and the two
     * are not in conflict. The edge rejects what a client sent so the client learns its request was
     * wrong; this method has no client to inform and is called by the edge, so it makes the value usable
     * instead of failing a request that was already validated.</p>
     */
    @Test
    @DisplayName("a page size outside the declared range is clamped at both ends")
    void aPageSizeOutsideTheRangeIsClamped() {
        when(this.customers.findAllByOrderByCustomerIdAsc(any(Limit.class))).thenReturn(rows(1, 1));

        this.reads.listCustomers(null, 0);
        verify(this.customers)
                .findAllByOrderByCustomerIdAsc(Limit.of(2));

        this.reads.listCustomers(null, AccountViewService.CUSTOMER_SCAN_MAX_PAGE_SIZE * 10);
        verify(this.customers).findAllByOrderByCustomerIdAsc(
                Limit.of(AccountViewService.CUSTOMER_SCAN_MAX_PAGE_SIZE + 1));
    }

    /**
     * Verifies an exhausted read answers with the shared exhausted envelope rather than sealed boundaries.
     *
     * <p>Assumptions: both boundaries are asserted absent as well as the item list, because an envelope
     * naming boundaries over no rows would hand a caller a position derived from nothing. This is the
     * state the reference reaches when its read reports end-of-file at L98 and L99 of
     * {@code app/cbl/CBCUS01C.cbl} and raises its end-of-file flag at L108 -- an empty result, not a
     * failure, which is why this case expects no exception.</p>
     */
    @Test
    @DisplayName("an exhausted scan yields the empty envelope with no boundaries")
    void anExhaustedScanYieldsTheEmptyEnvelope() {
        when(this.customers.findAllByOrderByCustomerIdAsc(any(Limit.class))).thenReturn(List.of());

        PageResponse<CustomerResponse> page = this.reads.listCustomers(null, PAGE_SIZE);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.firstKey()).isNull();
        assertThat(page.lastKey()).isNull();
    }

    /**
     * Verifies the boundaries are sealed from the STORED key rather than from the published rendering.
     *
     * <p>Assumptions: this is the reason the identifier in this class is forty-two and not a nine-digit
     * value. The projection renders the identifier as nine digits with leading zeros, while the resuming
     * query binds a number, so a boundary sealed from the rendered form would make the next page's bound
     * depend on a display decision -- and {@code Long.parseLong} would accept the padded form, so the
     * defect would not surface as a failure at all. It would surface as a page boundary that moved if the
     * rendering ever changed.</p>
     *
     * <p>Assumptions: a single row is used, so the leading and trailing boundaries are the same row and
     * both are asserted. A page of one is a legitimate page: the floor of the clamp is one.</p>
     */
    @Test
    @DisplayName("both boundaries are sealed from the stored numeric key, not the padded rendering")
    void theBoundariesAreSealedFromTheStoredKey() {
        when(this.customers.findAllByOrderByCustomerIdAsc(any(Limit.class)))
                .thenReturn(List.of(customer(CUSTOMER_ID)));

        PageResponse<CustomerResponse> page = this.reads.listCustomers(null, PAGE_SIZE);

        assertThat(page.items().getFirst().customerId())
                .as("the PUBLISHED identifier is padded to its declared nine characters")
                .isEqualTo(CUSTOMER_ID_DIGITS);
        assertThat(this.sealer.open(SCAN_BINDING, page.firstKey()))
                .as("the SEALED identifier is the stored number, with no padding")
                .isEqualTo(String.valueOf(CUSTOMER_ID));
        assertThat(this.sealer.open(SCAN_BINDING, page.lastKey()))
                .isEqualTo(String.valueOf(CUSTOMER_ID));
    }

    /**
     * Builds a run of consecutive customer rows in ascending identifier order.
     *
     * @param firstId the identifier of the first row
     * @param count the number of rows to build
     * @return the rows in ascending identifier order, never {@code null}
     */
    private static List<Customer> rows(long firstId, int count) {
        List<Customer> built = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            built.add(customer(firstId + offset));
        }
        return built;
    }

    /**
     * Builds one stored customer row whose every value is inside its declared width.
     *
     * <p>Assumptions: every value is a fixture constant and none identifies a real person. The two
     * protected columns hold fixed bytes rather than ciphertext of anything, which is admissible here
     * precisely because the projection publishes a constant marker for both and so reads neither.</p>
     *
     * <p>Assumptions: the widths that MUST be exact are exact -- the two-character state code, the
     * three-character country code, the ten-character postal code, the ten-character electronic-funds
     * identifier and the single-character indicator -- because the projection refuses a stored value that
     * does not match the width its {@code PICTURE} clause declares. The descriptive fields are left
     * unpadded, since their declared width is a maximum.</p>
     *
     * @param customerId the stored key of the row
     * @return a transient customer row, never {@code null}
     */
    private static Customer customer(long customerId) {
        return new Customer(customerId, "FIRST", "M", "LAST", "1 FIXTURE WAY", null, "FIXTURE CITY",
                "NY", "USA", "10001-0000", "(212)555-0100  ", null, FIXED_CIPHERTEXT, FIXED_CIPHERTEXT,
                LocalDate.of(1980, 1, 15), "0000000001", "Y", (short) 789);
    }
}
