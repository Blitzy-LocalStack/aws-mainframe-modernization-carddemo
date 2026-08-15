package com.carddemo.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.account.domain.Account;
import com.carddemo.account.domain.CardXref;
import com.carddemo.account.domain.Customer;
import com.carddemo.account.dto.AccountViewResponse;
import com.carddemo.account.mapper.AccountContextMapper;
import com.carddemo.account.mapper.AccountMapper;
import com.carddemo.account.mapper.CardXrefMapper;
import com.carddemo.account.mapper.CustomerMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.AccountScreenRow;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.money.Money;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.Limit;

/**
 * Pins the account view's three-hop composition, its verbatim message channels and its filter edit.
 *
 * <p><b>Purpose.</b> {@code app/cbl/COACTVWC.cbl}, 941 lines, resolves one account view through three
 * ordered keyed reads and reports a rejected filter or an absent record through two fixed-width message
 * channels. This class asserts the four outcomes that composition can reach, the exact bytes of every
 * sentence a user reads, the four sentinel values the input edit distinguishes, and the two report
 * behaviours of {@code app/cbl/CBACT01C.cbl}, 430 lines, that the target deliberately does not
 * reproduce. Both lengths are the physical lengths of files this migration reads as its specification
 * and never modifies.
 *
 * <p>The paragraph-to-method pairs under test are {@code 9000-READ-ACCT} at L687 with its exit at L720
 * for the composition, {@code 9200-GETCARDXREF-BYACCT} at L723, {@code 9300-GETACCTDATA-BYACCT} at L774
 * and {@code 9400-GETCUSTDATA-BYCUST} at L825 for the three reads it drives, and
 * {@code 2210-EDIT-ACCOUNT} at L649 for the input edit.
 *
 * <h2>What the composition does, and what a miss reaches</h2>
 *
 * <p>Assumptions: the reference performs the cross-reference read first because it is the only thing
 * that yields the customer key the third read needs, which it states at L739 and L740 by moving
 * {@code XREF-CUST-ID} and {@code XREF-CARD-NUM} on the normal-response arm. The first read reaches the
 * cross-reference BY ACCOUNT through a second access path, the alternate index named by the literal
 * {@code 'CXACAIX '} at L192 and L193, supplying the account identifier as the record identification
 * field at L729 with its key length at L730. That is eight characters including a trailing space, and it
 * is not the {@code 'CARDAIX '} literal at L190 and L191 beside it, which names the card alternate index
 * belonging to another bounded context. In the target the path is the secondary index
 * {@code idx_card_xref_account_id}.
 *
 * <p>Assumptions: the target issues the three reads as ONE left-joined statement, so a miss is signalled
 * by which sides of the composition row came back empty rather than by which read ran. No row at all is
 * the cross-reference miss, a row with no account is the account-master miss, and a row with an account
 * and no customer is the customer-master miss. That is why the short-circuit is asserted here as a call
 * count on the composing query together with the untouched state of the two repositories the view path
 * never reaches, and not as three stubs answering in order.
 *
 * <h2>Which sentence a miss carries, and why it is the declared one</h2>
 *
 * <p>Refactoring Rationale: each miss point publishes the DECLARED condition text rather than the
 * sentence the reference composes. On its not-found arms the reference builds a string that interpolates
 * the CICS response and reason codes into user-facing prose -- at L747 through L757 for the first read,
 * where the fragment at L751 carries two spaces after {@code file.}, at L796 through L806 for the second,
 * where the corresponding fragment at L800 carries only one, and at L846 through L856 for the third,
 * whose fragment at L852 is upper case where the other two are mixed case. Its unexpected-response arms
 * move a composed file-error text instead, at L766, L816 and L865. There is no CICS response code in the
 * target to report, so that detail belongs in structured error fields and in the operational record, and
 * what reaches a user is the constant declared at L130, L132 or L134 for the read that missed. The
 * divergence is registered in {@code docs/architecture/cobol-to-service-traceability.md}.
 *
 * <p>Trade-offs: the reference short-circuits only at the FIRST miss, and the target reports all three.
 * Its gate at L697 tests the validation flag that the first read's not-found arm sets at L743, so that
 * one holds. Its gates at L704 and L713 instead test the conditions declared at L129, L131 and L133,
 * and no statement anywhere in the program sets any of the three -- the only assignments that would are
 * themselves commented out, at L792 and at L842 -- while the arms that reach them set the validation
 * flags at L791 and L841 instead. What is given up is literal correspondence with two gates that cannot
 * fire; what is kept is that every miss is reported, so the target is strictly more informative than the
 * reference and never less. The baseline behaves as described; the Java reports each miss; the divergence
 * is documented.
 *
 * <h2>Two references this class does not carry across</h2>
 *
 * <p>Refactoring Rationale: {@code app/cbl/CBACT01C.cbl} formats an extract record in
 * {@code 1300-POPUL-ACCT-RECORD} at its L215, and two of that paragraph's statements have no target.
 * It routes the reissue date through an assembler module, setting a conversion type at L225 and L226,
 * calling the module at L231 under the comment at L229, and taking a twenty-character result at L233;
 * the assembler tree is retired with no target at all, so no migrated code can reach that module and
 * dates are published as the ten characters they are stored as. And it substitutes a fixed amount for a
 * zero current-cycle debit at L236 through L238; that is the ONLY assignment the extract field ever
 * receives, since the field is declared at L67 and written nowhere else in 430 lines and the paragraph
 * carries no alternative arm, so reproducing it would put a value no row holds into the money path of a
 * read endpoint. The substituted amount is not quoted anywhere in this class, not even as documentation,
 * so that a search of the migrated tree for it returns nothing rather than returning the one file that
 * explains its absence. Both divergences are registered in the traceability matrix.
 *
 * <h2>How this class is wired, and what it deliberately does not start</h2>
 *
 * <p>Alternatives Considered: substituted repositories with the REAL mappers, rather than a
 * container-backed context. Every property asserted here is pure logic -- which sides of a supplied
 * composition row produce which sentence, which characters a message contains, and which state a
 * sentinel resolves to -- and none of it needs a database, so a container would add a service to start
 * and a snapshot to reason about without making any of these assertions more visible. The mappers are
 * real rather than substituted because a substitute answers {@code null} for a projection, and a case
 * asserting that a view arrived populated would then pass against a view with no account and no customer
 * in it. The container-backed work of this module belongs to the {@code *IT} classes of the sibling
 * {@code com.carddemo.account.repository} package, which is where the join predicate behind the
 * composing query is asserted against a real engine.
 *
 * <p>Assumptions: no executable parity oracle exists for either reference program, so every case here is
 * authored from the program paragraphs directly and no golden-master comparison is claimed for any of
 * them. Two independent statements in {@code tests/README.md} establish it: its L83 through L85 record
 * that the online programs cannot be run end to end without a CICS runtime, which the runner does not
 * have, so only their extractable field-validation logic is unit-tested there; and the business rules
 * that suite asserts verbatim, from its L553 onward, name the posting, interest and category-balance
 * programs of other contexts and name neither of these two. These cases are strictly additive to that
 * suite, which is reference material and is neither displaced nor modified.
 *
 * <p>Parameters, return values, exceptions or errors: this class declares no constructor a caller may
 * use and yields no value; each member below carries its own at-clauses. The inapplicability is stated
 * rather than passed over, so that a reader can tell a declared inapplicability from an oversight.
 */
@DisplayName("Account view: three-hop composition, verbatim sentences and the filter edit")
class AccountViewServiceTest {

    /** The classpath name of the fifty-byte cross-reference fixture this module ships. */
    private static final String CARD_XREF_FIXTURE = "fixtures/xref/card-xref-valid.txt";

    /** The classpath name of the three-hundred-byte account fixture this module ships. */
    private static final String ACCOUNT_FIXTURE = "fixtures/account/account-valid.txt";

    /** The classpath name of the three-hundred-byte negative-balance account fixture. */
    private static final String ACCOUNT_NEGATIVE_FIXTURE = "fixtures/account/account-negative-balance.txt";

    /** The classpath name of the five-hundred-byte customer fixture this module ships. */
    private static final String CUSTOMER_FIXTURE = "fixtures/customer/customer-valid.txt";

    /** The record length {@code app/cpy/CVACT01Y.cpy} declares in its header and sums to across L5-L17. */
    private static final int ACCOUNT_RECORD_LENGTH = 300;

    /** The record length {@code app/cpy/CVCUS01Y.cpy} declares in its header and sums to across L5-L23. */
    private static final int CUSTOMER_RECORD_LENGTH = 500;

    /** The record length {@code app/cpy/CVACT03Y.cpy} declares in its header and sums to across L5-L8. */
    private static final int CARD_XREF_RECORD_LENGTH = 50;

    /** Offset and width of {@code XREF-CARD-NUM PIC X(16)} at L5 of {@code app/cpy/CVACT03Y.cpy}. */
    private static final int XREF_CARD_NUM_END = 16;

    /** End offset of {@code XREF-CUST-ID PIC 9(09)} at L6 of {@code app/cpy/CVACT03Y.cpy}. */
    private static final int XREF_CUST_ID_END = 25;

    /** End offset of {@code XREF-ACCT-ID PIC 9(11)} at L7 of {@code app/cpy/CVACT03Y.cpy}. */
    private static final int XREF_ACCT_ID_END = 36;

    /** The informational channel width, {@code WS-INFO-MSG PIC X(40)} at L110. */
    private static final int INFO_CHANNEL_WIDTH = 40;

    /** The composed width of {@code WS-FILE-ERROR-MESSAGE}, summed over its members at L86-L105. */
    private static final int FILE_ERROR_COMPOSED_WIDTH = 80;

    /** The index at which the emitted rejection at L672 carries its two consecutive spaces. */
    private static final int REJECTION_DOUBLE_SPACE_INDEX = 19;

    /** The informational text declared at L114 of {@code app/cbl/COACTVWC.cbl}, forty characters. */
    private static final String INFO_PROMPT = "Enter or update id of account to display";

    /** The condition text declared at L130 of {@code app/cbl/COACTVWC.cbl} for a cross-reference miss. */
    private static final String NOT_FOUND_IN_CARD_XREF =
            "Did not find this account in account card xref file";

    /** The condition text declared at L132 of {@code app/cbl/COACTVWC.cbl} for an account-master miss. */
    private static final String NOT_FOUND_IN_ACCOUNT_MASTER =
            "Did not find this account in account master file";

    /** The condition text declared at L134 of {@code app/cbl/COACTVWC.cbl} for a customer-master miss. */
    private static final String NOT_FOUND_IN_CUSTOMER_MASTER =
            "Did not find associated customer in master file";

    /**
     * The rejection text EMITTED at L672 of {@code app/cbl/COACTVWC.cbl}, fifty characters.
     *
     * <p>Assumptions: this is written out here rather than read from the class under test, whose own
     * constant is private. A case comparing the answer against the production constant would agree with
     * it whatever it said, so the literal is transcribed from the reference line instead and its
     * whitespace is never normalised.</p>
     */
    private static final String EMITTED_FILTER_REJECTION =
            "Account Filter must  be a non-zero 11 digit number";

    /**
     * The rejection text DECLARED at L126 and L128 of {@code app/cbl/COACTVWC.cbl}, forty-nine
     * characters.
     *
     * <p>Assumptions: both eighty-eight levels carry byte-identical text, and no statement in the
     * program sets either condition, which is why the inline literal at L672 is what a user reads. This
     * constant exists only so that a case can assert the answer is NOT this text.</p>
     */
    private static final String DECLARED_FILTER_REJECTION =
            "Account number must be a non zero 11 digit number";

    /** The cross-field text declared at L124 of {@code app/cbl/COACTVWC.cbl}. */
    private static final String NO_SEARCH_CRITERIA = "No input received";

    /** The field text declared at L122 of {@code app/cbl/COACTVWC.cbl}, superseded for a blank filter. */
    private static final String PROMPT_FOR_ACCOUNT = "Account number not provided";

    /** The response key the class under test reports a rejected account filter against. */
    private static final String ACCOUNT_FILTER_FIELD = "accountId";

    /** The rendering the class under test produces for the whole-program abend surface. */
    private static final String UNHANDLED_ABEND_RENDERING =
            "abend 9999 raised by COACTVWC: UNEXPECTED ABEND OCCURRED.";

    /** The symbolic sweep token standing for a filter arriving as the reference's low values. */
    private static final String TOKEN_LOW_VALUES = "LOW_VALUES";

    /** The symbolic sweep token standing for a filter arriving as the reference's spaces. */
    private static final String TOKEN_SPACES = "SPACES";

    /** The declared character width of the account filter, {@code WS-CARD-RID-ACCT-ID PIC 9(11)} at L78. */
    private static final int ACCOUNT_FILTER_WIDTH = 11;

    /** The substituted cross-reference repository, which owns the one composing statement. */
    private final CardXrefRepository crossReferences = mock(CardXrefRepository.class);

    /**
     * The substituted account repository, which the view path must never reach.
     *
     * <p>Assumptions: this is held as a field rather than passed inline at construction so that a case
     * can assert it was never touched. The composition reads the account master through the join in the
     * cross-reference repository's statement, so any interaction here would mean a second read.</p>
     */
    private final AccountRepository accounts = mock(AccountRepository.class);

    /** The substituted customer repository, held for the same reason as the account repository. */
    private final CustomerRepository customers = mock(CustomerRepository.class);

    /** The class under test, wired as the container wires it. */
    private AccountViewService reads;

    /** The cross-reference row built from the fixture, whose three identifiers join the other two rows. */
    private CardXref crossReference;

    /** The stored account row the composition yields, carrying a negative balance and a zero debit. */
    private Account account;

    /** The stored customer row the composition yields. */
    private Customer customer;

    /**
     * Wires the class under test over the substituted repositories and the fixture identity.
     *
     * <p>Assumptions: the account and customer rows are constructed rather than decoded from their
     * fixtures. Both fixture records are zoned decimal with sign overpunch, and the codec that decodes
     * that representation sits in the shared kernel outside this file's dependency contract; its own
     * round trip is asserted where it lives. The cross-reference row IS built from its fixture, because
     * that record carries no signed field and its three identifiers are what tie this case to the rows
     * the container-backed cases load.</p>
     *
     * <p>Trade-offs: constructing two of the three rows means their field values are stated here rather
     * than shared with the fixtures, so a fixture edit does not reach them. That is accepted because the
     * IDENTITY is shared -- the account key, the customer key and the card number all come from the
     * cross-reference fixture -- and identity is what makes the two kinds of case describe one account.
     * The alternative, decoding all three, would pull a codec into this file that none of its assertions
     * are about.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws IllegalStateException if a fixture cannot be read from the classpath, which would mean the
     *     module no longer ships the records these cases are keyed to
     */
    @BeforeEach
    void wire() {
        this.crossReference = crossReferenceFixture();

        this.account = new Account(this.crossReference.getAccountId(), "Y",
                new BigDecimal("-193.00"), new BigDecimal("1500.00"), new BigDecimal("250.00"),
                LocalDate.of(2020, 1, 15), LocalDate.of(2027, 1, 31), LocalDate.of(2024, 1, 31),
                new BigDecimal("2065.00"), new BigDecimal("0.00"), "1000101234", "DEFAULT");

        this.customer = new Customer(this.crossReference.getCustomerId(), "ADA", "M", "LOVELACE",
                "1 SYNTHETIC WAY", "SUITE 100", "TESTVILLE", "NY", "USA", "1000101234",
                "(212)5550100  ", null, "ssn".getBytes(StandardCharsets.UTF_8),
                "gid".getBytes(StandardCharsets.UTF_8), LocalDate.of(1980, 4, 2),
                "0000000001", "Y", (short) 742);

        this.reads = new AccountViewService(this.accounts, this.customers, this.crossReferences,
                mock(AccountContextMapper.class),
                new AccountMapper(),
                // WHY : Assumptions: the protection boundary REFUSES rather than answering, because the
                //       read path must never encipher an identifier -- the view publishes a fixed marker
                //       for both protected components instead. A substitute answering null would let a
                //       read that did encipher pass unnoticed, whereas a refusal names the component
                //       that was enciphered and fails the case that provoked it.
                new CustomerMapper((clearText, field) -> {
                    throw new AssertionError(
                            "the read path must not encipher an identifier, but it enciphered " + field);
                }),
                mock(CardXrefMapper.class),
                // WHY : Assumptions: a real cursor signer is supplied rather than a substitute even
                //       though no case here pages anything, because the constructor rejects an absent
                //       one and a substitute would be a collaborator no assertion ever reads.
                new CursorToken("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8),
                        Duration.ofMinutes(5)));
    }

    /**
     * A complete composition publishes both halves, the five stored amounts and the reference prompt.
     *
     * <p>Purpose: this is the arm all three reads answered on. It asserts the ten components the account
     * half carries, of which five are amounts, the eighteen the customer half carries at their screen
     * widths, and the two message channels -- the informational one holding the prompt and the aggregate
     * one unset.</p>
     *
     * <p>Assumptions: the SCREEN widths are asserted and not the record widths, because the two disagree
     * on four fields and this shape resolves three of them toward the screen. The postal code is narrowed
     * from the ten characters of {@code CUST-ADDR-ZIP PIC X(10)} at L14 of {@code app/cpy/CVCUS01Y.cpy}
     * to the five of {@code ACSZIPCI PIC X(5)} at L186 of {@code app/cpy-bms/COACTVW.CPY}, and both
     * telephone numbers are narrowed from the fifteen of L15 and L16 to the thirteen of {@code ACSPHN1I}
     * at L204 and {@code ACSPHN2I} at L216. Asserting the record widths here would assert a shape this
     * response does not have.</p>
     *
     * <p>Assumptions: the fixed-domain components are asserted at EXACTLY their screen width while the
     * free-text ones are asserted as bounded by it, which is the shape the response actually carries. A
     * value drawn from a closed domain -- a state code, a country code, a postal code, a telephone
     * number, an identifier, a score, a date -- occupies its whole field, whereas a name or an address
     * line is as long as it is. Asserting a padded length for a name would pin blank padding as though it
     * were part of the value.</p>
     *
     * <p>Assumptions: the informational channel carries the prompt declared at L114 even on this
     * successful arm, and the aggregate channel is ABSENT rather than blank. The reference clears the
     * informational channel at L689 at the head of {@code 9000-READ-ACCT} and the screen setup restores
     * that prompt at L528 and L529 before moving it out at L534, while the alternative text declared at
     * L116 is set nowhere in the program. The aggregate channel's off-state is spaces, declared at L118,
     * which the response contract represents as an absent component rather than an empty one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a complete composition publishes both halves, five amounts and the L114 prompt")
    void aCompleteCompositionPublishesBothHalvesAndTheReferencePrompt() {
        stubComposition(this.account, this.customer);

        AccountViewResponse view = this.reads.readAccountView(this.account.getAccountId()).view();

        assertThat(view.accountId())
                .as("the account key is published at the eleven digits WS-CARD-RID-ACCT-ID declares at L78")
                .isEqualTo("00000000011");
        assertThat(view.informationMessage())
                .isEqualTo(INFO_PROMPT)
                .hasSize(INFO_CHANNEL_WIDTH);
        assertThat(view.returnMessage())
                .as("the aggregate channel is unset on a successful read, not set and blank")
                .isNull();

        AccountViewResponse.AccountDetail detail = view.account();
        assertThat(detail).isNotNull();
        assertThat(detail.activeStatus()).isEqualTo("Y");
        assertThat(detail.groupId()).isEqualTo("DEFAULT");
        assertThat(detail.currentBalance()).isEqualTo(Money.of(new BigDecimal("-193.00")));
        assertThat(detail.creditLimit()).isEqualTo(Money.of(new BigDecimal("1500.00")));
        assertThat(detail.cashCreditLimit()).isEqualTo(Money.of(new BigDecimal("250.00")));
        assertThat(detail.currentCycleCredit()).isEqualTo(Money.of(new BigDecimal("2065.00")));
        assertThat(detail.currentCycleDebit()).isEqualTo(Money.ZERO);

        // WHY : Assumptions: the five amounts are gathered and asserted TOGETHER at scale two, because
        //       app/cpy/CVACT01Y.cpy declares exactly five signed fields with two decimal places -- at
        //       its L7, L8, L9, L13 and L14 -- and the count is part of the contract. Checking them one
        //       at a time would still pass if a sixth component silently became an amount or a fifth
        //       stopped being one.
        assertThat(List.of(detail.currentBalance(), detail.creditLimit(), detail.cashCreditLimit(),
                        detail.currentCycleCredit(), detail.currentCycleDebit()))
                .hasSize(5)
                .allSatisfy(amount -> assertThat(amount.amount().scale()).isEqualTo(Money.SCALE));

        AccountViewResponse.CustomerDetail person = view.customer();
        assertThat(person).isNotNull();
        assertThat(person.customerId())
                .as("nine digits, ACSTNUMI PIC X(9) at L126 agreeing with CUST-ID PIC 9(09) at L5")
                .isEqualTo("000000011");
        assertThat(person.dateOfBirth()).isEqualTo("1980-04-02");
        assertThat(person.ficoCreditScore()).isEqualTo("742");
        assertThat(person.stateCode()).isEqualTo("NY");
        assertThat(person.countryCode()).isEqualTo("USA");
        assertThat(person.zipCode())
                .as("the leftmost five of the ten-character stored postal code, per L186")
                .isEqualTo("10001");
        assertThat(person.phoneNumber1())
                .as("the leftmost thirteen of the fifteen-character stored number, per L204")
                .hasSize(13)
                .startsWith("(212)5550100");
        assertThat(person.phoneNumber2())
                .as("an omitted second telephone number stays absent rather than becoming blank")
                .isNull();
        assertThat(person.eftAccountId()).isEqualTo("0000000001");
        assertThat(person.primaryCardHolderIndicator()).isEqualTo("Y");
        assertThat(person.firstName()).isEqualTo("ADA");
        assertThat(person.middleName()).isEqualTo("M");
        assertThat(person.lastName()).isEqualTo("LOVELACE");
        assertThat(person.addressLine1()).isEqualTo("1 SYNTHETIC WAY");
        assertThat(person.addressLine2()).isEqualTo("SUITE 100");
        assertThat(person.city())
                .as("the city component is fed from CUST-ADDR-LINE-3 at L11, the only unclaimed fifty-byte field")
                .isEqualTo("TESTVILLE");

        assertThat(List.of(person.firstName(), person.middleName(), person.lastName()))
                .allSatisfy(name -> assertThat(name).hasSizeLessThanOrEqualTo(25));
        assertThat(List.of(person.addressLine1(), person.addressLine2(), person.city()))
                .allSatisfy(line -> assertThat(line).hasSizeLessThanOrEqualTo(50));

        // WHY : Assumptions: BOTH protected identifiers are asserted to be the same fixed marker and to
        //       disclose nothing of what they stand for. Neither is length-preserving or
        //       presence-preserving on purpose, so nothing derived from the stored ciphertext -- not its
        //       bytes, not its length, not whether the optional government-issued identifier was stored
        //       at all -- can reach a caller. This service's records carry no card verification value of
        //       any kind, so none can appear here either.
        assertThat(List.of(person.ssnMasked(), person.governmentIssuedIdMasked()))
                .allSatisfy(masked -> assertThat(masked)
                        .isEqualTo("[REDACTED]")
                        .doesNotContain("ssn")
                        .doesNotContain("gid"));
    }

    /**
     * A cross-reference miss is refused with the sentence declared at L130.
     *
     * <p>Refactoring Rationale: the sentence a caller receives is the condition text DECLARED at L130 and
     * not the string the reference composes at L747 through L757, whose fragment at L751 carries two
     * spaces after {@code file.} and which interpolates the CICS response and reason codes moved in at
     * L745 and L746. The unexpected-response arm of the same read is the same shape, moving a composed
     * file-error text at L766. Neither composition has a target analogue, because there is no CICS
     * response code in the target to report; that detail is carried by structured error fields and by the
     * operational record instead. The baseline emits the composed diagnostic; the Java emits the declared
     * constant; the divergence is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Assumptions: an empty answer from the composing statement IS the cross-reference miss, because
     * the statement's outer joins mean a located row is returned even when neither master holds anything
     * for it. No row at all can therefore only mean the cross-reference itself was absent.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a cross-reference miss is refused with the L130 sentence, not the composed diagnostic")
    void aCrossReferenceMissCarriesTheDeclaredCrossReferenceSentence() {
        when(this.crossReferences.findAccountScreenRows(any(), any(Limit.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> this.reads.readAccountView(this.account.getAccountId()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(NOT_FOUND_IN_CARD_XREF)
                .hasMessageNotContaining("Resp")
                .hasMessageNotContaining("Reas");
    }

    /**
     * An account-master miss is refused with the sentence declared at L132, and only that sentence.
     *
     * <p>Purpose: the composition row supplied here is missing BOTH masters, so the case asserts the
     * LATCH as well as the sentence: the account-master text is reported and the customer-master text is
     * not, for one request.</p>
     *
     * <p>Trade-offs: the reference reaches the same single sentence by a different route, and the target
     * accepts a structural difference to keep the outcome identical. There, the second read composes its
     * message under the message-off guard at L793 because the first read wrote nothing, and the third
     * read's own guard at L845 is then false, so the third sentence is never composed and the second
     * stands. Here the composition latches the second sentence and returns at the gate the reference
     * places at L704, so the third is never latched. The route differs; the sentence a caller reads does
     * not.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an account-master miss carries the L132 sentence and never the L134 one")
    void anAccountMasterMissCarriesTheDeclaredAccountSentence() {
        stubComposition(null, null);

        assertThatThrownBy(() -> this.reads.readAccountView(this.account.getAccountId()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(NOT_FOUND_IN_ACCOUNT_MASTER)
                .hasMessageNotContaining(NOT_FOUND_IN_CUSTOMER_MASTER);
    }

    /**
     * A customer-master miss is refused with the sentence declared at L134.
     *
     * <p>Trade-offs: this arm is reported at all only because the target diverges from the reference
     * here. The reference's gate at L713 tests the condition declared at L133, and no statement anywhere
     * in the program sets it -- the assignment that would is commented out at L842 -- while the arm that
     * reaches the gate sets a validation flag at L841 instead. The same holds one read earlier, at L704
     * against L131 and L792 with its flag at L791. What the target gives up is literal correspondence
     * with two gates that cannot fire; what it keeps is that a request naming an account whose customer
     * is absent is told so. The baseline leaves both gates unreachable; the Java reports both misses; the
     * divergence is registered in the traceability matrix.</p>
     *
     * <p>Assumptions: this is the one arm on which the composition still maps the account half, because
     * the reference populates its account region under the disjunction at L471 and L472 that a located
     * account already satisfies through the read flag it sets at L788. The entry point nevertheless
     * refuses, because a caller cannot tell a half-populated view from a complete one, so what is
     * observable here is the sentence rather than the partial body.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a customer-master miss carries the L134 sentence, a gate the reference cannot reach")
    void aCustomerMasterMissCarriesTheDeclaredCustomerSentence() {
        stubComposition(this.account, null);

        assertThatThrownBy(() -> this.reads.readAccountView(this.account.getAccountId()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(NOT_FOUND_IN_CUSTOMER_MASTER)
                .hasMessageNotContaining("REAS");
    }

    /**
     * Every outcome costs one composing read, and no second read is attempted at any miss.
     *
     * <p>Purpose: the reference drives three separate {@code EXEC CICS READ} statements from
     * {@code 9000-READ-ACCT} -- at L727 against the alternate index, at L776 against the account master
     * and at L826 against the customer master -- and abandons the sequence at the first miss through the
     * gate at L697 with its branch at L698. This case asserts the migrated form of that discipline: one
     * statement serves every outcome, and the two repositories that would carry a follow-on read are
     * never reached, on the complete arm or on any of the three miss arms.</p>
     *
     * <p>Alternatives Considered: asserting the short-circuit as three ordered stubs, one per reference
     * read, with the later ones asserted unused. Rejected because the target does not issue three
     * statements to order: the three sides arrive together from one left-joined statement, so there is no
     * second call for a gate to prevent. A call count on the one statement plus the untouched state of
     * the other two repositories states exactly what is true, whereas ordered stubs would describe a
     * sequence the target does not have and would pass for the wrong reason.</p>
     *
     * <p>Assumptions: reading under one statement rather than three is what makes the account and the
     * customer on one screen mutually consistent, since this datasource takes a fresh snapshot per
     * statement. That property is asserted by the sibling case that owns the concurrency token; what is
     * asserted here is only the call count it rests on.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("one composing read serves every outcome and no follow-on read is attempted")
    void everyOutcomeCostsOneComposingReadAndAttemptsNoFurtherRead() {
        long accountKey = this.account.getAccountId();

        stubComposition(this.account, this.customer);
        assertThat(this.reads.readAccountView(accountKey).view().account()).isNotNull();

        stubComposition(this.account, null);
        assertThatThrownBy(() -> this.reads.readAccountView(accountKey))
                .isInstanceOf(NoSuchElementException.class);

        stubComposition(null, null);
        assertThatThrownBy(() -> this.reads.readAccountView(accountKey))
                .isInstanceOf(NoSuchElementException.class);

        when(this.crossReferences.findAccountScreenRows(any(), any(Limit.class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> this.reads.readAccountView(accountKey))
                .isInstanceOf(NoSuchElementException.class);

        verify(this.crossReferences, times(4)).findAccountScreenRows(any(), any(Limit.class));
        verifyNoInteractions(this.accounts);
        verifyNoInteractions(this.customers);
    }

    /**
     * A rejected filter carries the literal EMITTED at L672 and never the one declared at L126 and L128.
     *
     * <p>Purpose: this case exists for one property, asserted character by character, because the two
     * candidate strings differ in three ways and a comparison that normalised whitespace would pass
     * against the wrong one. It is therefore written as an exact comparison plus positional assertions on
     * each of the three differences.</p>
     *
     * <p>Assumptions: the EMITTED literal is what a user reads, so it is what the response carries. The
     * reference reaches the inline literal at L672 rather than either declared condition because nothing
     * anywhere in the program sets {@code SEARCHED-ACCT-NOT-NUMERIC} or {@code SEARCHED-ACCT-ZEROES}, so
     * L671 through L673 moves a literal instead and the two declarations at L125 with L126 and at L127
     * with L128 are unreached. The three differences are the two consecutive spaces after {@code must},
     * the hyphen in {@code non-zero}, and the word {@code Filter} where the declarations carry
     * {@code number}. Length alone separates them at fifty characters against forty-nine, which is why
     * the size is asserted as a second guard. Transformation rule T8 requires user-visible strings to be
     * carried across verbatim.</p>
     *
     * <p>Assumptions: the physical line at L672 also carries the characters {@code 00} after its closing
     * quote, in the columns beyond 72 that fixed-format COBOL ignores. Those are sequence-area residue and
     * not part of the literal, so the case asserts the message does not end with them -- a mistake that is
     * easy to make when transcribing the line rather than the quoted value, and one no other assertion
     * here would catch.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a rejected filter carries the L672 literal byte for byte, not the L126 declaration")
    void aRejectedFilterCarriesTheEmittedLiteralAndNotTheDeclaredOne() {
        List<ApiError.FieldError> errors = this.reads.accountFilterFieldErrors("1234567890A");

        assertThat(errors).hasSize(1);
        ApiError.FieldError only = errors.get(0);
        assertThat(only.field()).isEqualTo(ACCOUNT_FILTER_FIELD);
        assertThat(only.state()).isEqualTo(FieldValidationFlag.NOT_OK);

        String message = only.message();
        assertThat(message)
                .as("the literal emitted at L672 of app/cbl/COACTVWC.cbl, transcribed byte for byte")
                .isEqualTo(EMITTED_FILTER_REJECTION)
                .hasSize(50)
                .isNotEqualTo(DECLARED_FILTER_REJECTION);

        // WHY : Assumptions: the two spaces are asserted by POSITION and not by a contains check on a
        //       doubled space, because a contains check would also pass if the run appeared somewhere
        //       else in the sentence while the run after "must" had collapsed to one. The index is the
        //       one the reference line puts it at, immediately after the four characters of "must",
        //       which begins at index fifteen.
        assertThat(message.indexOf("must")).isEqualTo(15);
        assertThat(message.substring(REJECTION_DOUBLE_SPACE_INDEX, REJECTION_DOUBLE_SPACE_INDEX + 2))
                .as("L672 carries two spaces between must and be; the declarations carry one")
                .isEqualTo("  ");
        assertThat(message)
                .contains("non-zero")
                .doesNotContain("non zero")
                .contains("Account Filter")
                .doesNotContain("Account number")
                .doesNotEndWith("00");
    }

    /**
     * Each sentinel the input edit distinguishes resolves to the state the reference sets for it.
     *
     * <p>Purpose: {@code 2210-EDIT-ACCOUNT} at L649 recognises four rejecting inputs across two branches
     * and one acceptable one, and this sweep drives all five. It asserts the resolved state, the error
     * predicate that both rejecting states share, the screen marker only one of them carries, and the
     * number of per-field entries the response would publish.</p>
     *
     * <p>Assumptions: the blank branch is evaluated FIRST because the reference evaluates it first, at
     * L653 and L654, and leaves the paragraph at L661 before the numeric branch at L666 is reached. The
     * order is observable rather than cosmetic: a value that was never supplied is not all digits either,
     * so testing the numeric guard first would report every unsupplied field as unacceptable and lose the
     * never-supplied state the screen marker depends on. The reference pre-sets the unacceptable state at
     * L650, so unacceptable is the default and acceptance is reached only at L679.</p>
     *
     * <p>Assumptions: the two rejecting inputs of the second branch are ONE case in the reference, joined
     * by OR at L666 and L667 with a single shared body, so both resolve to the same state and the same
     * sentence. The two of the first branch are likewise joined at L653 and L654, and they are two
     * genuinely distinct sentinels -- the reference's low values and its spaces -- which is why both are
     * driven rather than one standing in for the other.</p>
     *
     * <p>Assumptions: the resolved state is checked THROUGH the error predicate as well as by identity,
     * because that predicate is the migrated form of the single disjunctive test at L18 and L19 of
     * {@code app/cpy/CSSETATY.cpy}, where the template gates its highlight on either rejecting condition
     * in one expression. The blank state is a subset of error rather than a third peer, and the marker is
     * what tells the two apart.</p>
     *
     * <p>This test returns no value; each of its five cases asserts and returns.</p>
     *
     * @param token the symbolic name of the screen value to drive, resolved by {@link #screenValue}
     * @param expected the state the reference sets for that input
     * @param expectsError whether the state is one the shared error predicate reports
     * @param expectsBlankMarker whether the state contributes the asterisk screen marker
     */
    @ParameterizedTest
    @CsvSource({
        "LOW_VALUES,  BLANK,  true,  true",
        "SPACES,      BLANK,  true,  true",
        "1234567890A, NOT_OK, true,  false",
        "00000000000, NOT_OK, true,  false",
        "00000000011, VALID,  false, false"
    })
    @DisplayName("each sentinel resolves to the state 2210-EDIT-ACCOUNT sets for it")
    void eachSentinelResolvesToTheStateTheReferenceSetsForIt(String token,
            FieldValidationFlag expected, boolean expectsError, boolean expectsBlankMarker) {
        String screenValue = screenValue(token);

        FieldValidationFlag state = this.reads.editAccountFilter(screenValue);

        assertThat(state).isEqualTo(expected);
        assertThat(state.isError())
                .as("the single disjunctive test of app/cpy/CSSETATY.cpy L18-L19 covers both rejections")
                .isEqualTo(expectsError);
        assertThat(state.requiresBlankMarker()).isEqualTo(expectsBlankMarker);
        assertThat(state.screenMarker()).isEqualTo(expectsBlankMarker
                ? FieldValidationFlag.BLANK_SCREEN_MARKER
                : FieldValidationFlag.NO_SCREEN_MARKER);
        assertThat(this.reads.accountFilterFieldErrors(screenValue))
                .hasSize(expectsError ? 1 : 0);
    }

    /**
     * Only a never-supplied filter carries the asterisk, and its sentence supersedes the field one.
     *
     * <p>Purpose: the two rejecting states share one gate but not one presentation, and this case pins
     * both halves of that difference -- which state carries the marker, and which sentence each one
     * reports.</p>
     *
     * <p>Assumptions: only the never-supplied state carries the marker, because the template moves the
     * asterisk into the field under a SECOND, narrower test at L23 through L25 of
     * {@code app/cpy/CSSETATY.cpy}, nested inside the disjunction at L18 and L19 that also admits the
     * unacceptable state. Both states are highlighted; one is marked. That is the whole reason the
     * migrated state carries three values instead of collapsing to a boolean.</p>
     *
     * <p>Assumptions: the sentence a never-supplied filter reports is the cross-field text declared at
     * L124 and not the field text declared at L122, even though the reference latches the field text
     * first, at L657 and L658. The cross-field edit that follows carries NO message-off guard, unlike
     * every other assignment to that channel, so it plainly overwrites what the field edit latched.
     * Reporting L122 here would invert that behaviour, and omitting L122 from the migrated path
     * altogether would hide that the reference reaches it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("only a never-supplied filter carries the asterisk, and reports the L124 sentence")
    void onlyANeverSuppliedFilterCarriesTheScreenMarker() {
        List<ApiError.FieldError> blank =
                this.reads.accountFilterFieldErrors(screenValue(TOKEN_SPACES));

        assertThat(blank).hasSize(1);
        assertThat(blank.get(0).state()).isEqualTo(FieldValidationFlag.BLANK);
        assertThat(blank.get(0).screenMarker())
                .as("the asterisk of app/cpy/CSSETATY.cpy L24 belongs to the never-supplied state alone")
                .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER);
        assertThat(blank.get(0).message())
                .isEqualTo(NO_SEARCH_CRITERIA)
                .isNotEqualTo(PROMPT_FOR_ACCOUNT);

        List<ApiError.FieldError> unacceptable = this.reads.accountFilterFieldErrors("00000000000");

        assertThat(unacceptable).hasSize(1);
        assertThat(unacceptable.get(0).state()).isEqualTo(FieldValidationFlag.NOT_OK);
        assertThat(unacceptable.get(0).screenMarker())
                .as("an unacceptable value is highlighted without being marked")
                .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);
        assertThat(unacceptable.get(0).message()).isEqualTo(EMITTED_FILTER_REJECTION);

        assertThat(this.reads.accountFilterFieldErrors("00000000011")).isEmpty();
    }

    /**
     * Every sentence this service publishes fits the seventy-five-character aggregate channel.
     *
     * <p>Purpose: the reference carries all of these sentences through one field,
     * {@code WS-RETURN-MSG PIC X(75)} at L117, and the target publishes the same width as a rendering
     * constraint. This case asserts the constant, asserts that each sentence the service actually emits
     * fits it, and demonstrates that the one composition the reference moves into that field wider than
     * seventy-five characters loses nothing when it is cut.</p>
     *
     * <p>Assumptions: the seventy-five-character contract does NOT come from
     * {@code app/cpy/CSMSG01Y.cpy}. That copybook is twenty-four lines and declares a fifty-character
     * two-message regime, at L18 with L19 and at L20 with L21. The two fields in the whole of
     * {@code app/cpy} declared at this width are {@code CCARD-ERROR-MSG} at L28 and
     * {@code CCARD-RETURN-MSG} at L29 of {@code app/cpy/CVCRD01Y.cpy}, and the view program declares its
     * own at L117 beside them.</p>
     *
     * <p>Assumptions: the truncation the reference performs is content-lossless, and the reason is
     * arithmetic rather than incidental. {@code WS-FILE-ERROR-MESSAGE} is a composed group whose members
     * are declared at L87, L89, L91, L93, L95, L98, L100, L102 and L104, and their widths sum to eighty.
     * The running total reaches exactly seventy-five at the END of its last named member at L102, so the
     * cut falls on a member boundary and the only bytes dropped are the unnamed trailing filler of blanks
     * at L104 and L105. No named member is ever cut, which is why the target may publish the shorter form
     * without losing anything the reference would have shown.</p>
     *
     * <p>Assumptions: the informational channel is a separate, narrower field,
     * {@code WS-INFO-MSG PIC X(40)} at L110, and the one text the program ever puts in it exactly fills
     * it. The two channels are asserted against their own widths rather than against one shared number,
     * because a difference in declared width is a difference in contract.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every published sentence fits the L117 channel, and the L86-L105 cut loses only blanks")
    void everyPublishedSentenceFitsTheSeventyFiveCharacterChannel() {
        assertThat(ApiError.MESSAGE_RENDERING_WIDTH)
                .as("the width app/cpy/CVCRD01Y.cpy L28-L29 declares and app/cbl/COACTVWC.cbl L117 repeats")
                .isEqualTo(75);

        assertThat(List.of(NOT_FOUND_IN_CARD_XREF, NOT_FOUND_IN_ACCOUNT_MASTER,
                        NOT_FOUND_IN_CUSTOMER_MASTER, EMITTED_FILTER_REJECTION, NO_SEARCH_CRITERIA,
                        PROMPT_FOR_ACCOUNT))
                .allSatisfy(sentence -> assertThat(sentence)
                        .hasSizeLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH));

        assertThat(INFO_PROMPT)
                .as("the L114 prompt exactly fills the forty characters L110 declares")
                .hasSize(INFO_CHANNEL_WIDTH);

        String composed = composedFileErrorText();
        assertThat(composed).hasSize(FILE_ERROR_COMPOSED_WIDTH);

        String truncated = composed.substring(0, ApiError.MESSAGE_RENDERING_WIDTH);
        assertThat(composed.substring(ApiError.MESSAGE_RENDERING_WIDTH))
                .as("the five bytes beyond the channel are the trailing filler of blanks at L104-L105")
                .isBlank()
                .hasSize(FILE_ERROR_COMPOSED_WIDTH - ApiError.MESSAGE_RENDERING_WIDTH);
        assertThat(truncated.stripTrailing())
                .as("cutting at a member boundary drops padding and no named member")
                .isEqualTo(composed.stripTrailing());
    }

    /**
     * A stored zero current-cycle debit is published as zero.
     *
     * <p>Refactoring Rationale: {@code app/cbl/CBACT01C.cbl} substitutes a fixed amount for a zero
     * current-cycle debit while formatting its extract record, at L236 through L238 inside
     * {@code 1300-POPUL-ACCT-RECORD} at L215, and this read does not reproduce it. Three facts about that
     * statement decide the matter. It writes an OUTPUT field and not the master, which L166 reads into the
     * record structure unchanged. It is the ONLY assignment that output field ever receives, since the
     * field is declared at L67 and written nowhere else in 430 lines and the paragraph carries no
     * alternative arm, so the extract column is populated only when the stored value is zero. And
     * reproducing it on a read endpoint would put into the money path a value no row holds. The baseline
     * substitutes; the Java publishes the stored amount; the divergence is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Assumptions: the substituted amount is deliberately not written anywhere in this class, not even
     * as documentation, so that a search of the migrated tree for it returns nothing at all rather than
     * returning the one file that explains why it is absent. The three cited lines are where a reader sees
     * the value itself. What the case can therefore assert is the positive property -- that zero is
     * published as zero -- which is precisely the property a reproduced substitution would not
     * satisfy.</p>
     *
     * <p>Assumptions: the amount is asserted as exact scaled decimal and not as a number, because money
     * in this bounded context is fixed point at every hop. Both the scale and the rendered form are
     * checked, so a value that compared equal while carrying a different scale, or one that rendered
     * through binary floating point, would fail. No {@code float}, {@code double} or boxed equivalent
     * appears anywhere in this class.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a zero current-cycle debit is published as zero, not as a substituted amount")
    void aZeroCurrentCycleDebitIsPublishedAsZero() {
        assertThat(this.account.getCurrentCycleDebit())
                .as("the stored row this case rests on holds zero in that column")
                .isEqualByComparingTo(BigDecimal.ZERO);
        stubComposition(this.account, this.customer);

        Money published = this.reads.readAccountView(this.account.getAccountId())
                .view().account().currentCycleDebit();

        assertThat(published.isZero()).isTrue();
        assertThat(published).isEqualTo(Money.ZERO);
        assertThat(published.amount().scale()).isEqualTo(Money.SCALE);
        assertThat(published.toPlainString())
                .as("two decimal places are rendered, since the field is declared S9(10)V99 at L14")
                .isEqualTo("0.00");
    }

    /**
     * The three account dates are published as the ten characters they are stored as.
     *
     * <p>Refactoring Rationale: {@code app/cbl/CBACT01C.cbl} reformats one of these dates through an
     * external module and this read does not. It moves the reissue date into a conversion record at L223
     * and L224, selects a conversion type at L225 and L226, calls the module at L231 under the comment at
     * L229 that names it as an assembler program, and takes the result from a twenty-character field at
     * L233. The assembler tree is retired with no target at all, so nothing in the migrated tree can reach
     * that module, and the observable consequence is asserted here: each date is published as its stored
     * ten characters rather than as a reformatted twenty-character value. The baseline reformats; the Java
     * publishes the stored form; the divergence is registered in the traceability matrix.</p>
     *
     * <p>Assumptions: all THREE dates are asserted although only the reissue date passed through the
     * module, because the property being pinned is that this read reformats nothing. Asserting only the
     * one date the reference converted would leave a reformatting of the other two undetected.</p>
     *
     * <p>Assumptions: ten characters is the width the record declares -- {@code ACCT-OPEN-DATE} at L10,
     * {@code ACCT-EXPIRAION-DATE} at L11, misspelled in the baseline and named {@code expiration_date} in
     * the target, and {@code ACCT-REISSUE-DATE} at L12 of {@code app/cpy/CVACT01Y.cpy} -- and it is also
     * the width the view map declares at L138 for the date it shows. The stored order is year, month, day,
     * which is why a lexical comparison of these characters and a calendar comparison of the same two
     * values reach the same verdict, and therefore why nothing is lost by holding them as dates.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the three dates are published as their stored ten characters, reformatted by nothing")
    void theThreeDatesArePublishedAsTheirStoredTenCharacterText() {
        stubComposition(this.account, this.customer);

        AccountViewResponse.AccountDetail detail =
                this.reads.readAccountView(this.account.getAccountId()).view().account();

        assertThat(detail.openDate()).isEqualTo(this.account.getOpenDate().toString());
        assertThat(detail.expirationDate()).isEqualTo(this.account.getExpirationDate().toString());
        assertThat(detail.reissueDate())
                .as("the one date app/cbl/CBACT01C.cbl routed through the module at its L231")
                .isEqualTo(this.account.getReissueDate().toString());

        assertThat(List.of(detail.openDate(), detail.expirationDate(), detail.reissueDate()))
                .allSatisfy(rendered -> assertThat(rendered).hasSize(10));
    }

    /**
     * An undiagnosed read failure and an absent record reach different surfaces.
     *
     * <p>Purpose: the reference has two distinct failure surfaces and conflating them would lose one.
     * A record that is simply absent takes the not-found arm of its read and reports a condition sentence,
     * whereas an unhandled condition reaches {@code ABEND-ROUTINE} at L916, registered for the whole
     * program by the handler at L264 through L266, which defaults its message at L918 through L920, stamps
     * the program name as the culprit at L922 and abends at L934 through L936. This case drives both and
     * asserts they stay apart.</p>
     *
     * <p>Assumptions: the four components of the migrated abend record are the group item declared at L21
     * through L29 of {@code app/cpy/CSMSG02Y.cpy} -- the code at L22, the culprit at L24, the reason at
     * L26 and the message at L28 -- a file of thirty-five lines in total. The culprit is the eight
     * characters the reference holds as its own program name at L143 and L144 of
     * {@code app/cbl/COACTVWC.cbl} and moves into that component at L922, and eight is exactly the width
     * L24 declares, so it is carried whole.</p>
     *
     * <p>Assumptions: the original failure is asserted to survive as the cause. A surface that replaced
     * the underlying condition instead of wrapping it would report every infrastructure failure as an
     * unexplained abend, which is the diagnosis the reference's own handler exists to avoid making
     * twice.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an undiagnosed read failure abends while an absent record stays a not-found")
    void anUndiagnosedFailureAndAnAbsentRecordReachDifferentSurfaces() {
        UncheckedIOException underlying =
                new UncheckedIOException(new IOException("the datasource refused the statement"));
        when(this.crossReferences.findAccountScreenRows(any(), any(Limit.class)))
                .thenThrow(underlying);

        assertThatThrownBy(() -> this.reads.readAccountView(this.account.getAccountId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(UNHANDLED_ABEND_RENDERING)
                .hasCause(underlying);

        stubComposition(null, null);

        assertThatThrownBy(() -> this.reads.readAccountView(this.account.getAccountId()))
                .as("an absent record is not an abend, so it must not be rendered as one")
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(NOT_FOUND_IN_ACCOUNT_MASTER)
                .hasMessageNotContaining("abend");
    }

    /**
     * The three fixture records occupy exactly the lengths their copybooks declare.
     *
     * <p>Purpose: every offset this class reads out of the cross-reference fixture depends on that record
     * being the length its copybook declares, and the two records it does not decode are keyed to the same
     * contract. This case pins all three lengths so that a fixture edited to a different size fails here
     * rather than shifting a field silently.</p>
     *
     * <p>Assumptions: each length is the sum of the copybook's own field widths and not a separately
     * chosen number. {@code app/cpy/CVACT01Y.cpy} sums to three hundred over L5 through L17, the last of
     * which is a filler of one hundred and seventy-eight bytes that the target drops.
     * {@code app/cpy/CVCUS01Y.cpy} sums to five hundred over L5 through L23, its eighteen named fields
     * followed by a filler of one hundred and sixty-eight. {@code app/cpy/CVACT03Y.cpy} sums to fifty over
     * L5 through L8, three identifiers followed by a filler of fourteen. Each copybook also states its
     * length in its own header comment, so the two agree.</p>
     *
     * <p>Trade-offs: the two larger records are checked for LENGTH only and are not decoded, while the
     * cross-reference record is decoded field by field. Both of the larger ones hold signed amounts as
     * zoned decimal with the sign carried on the final digit, and the codec that decodes that
     * representation belongs to the shared kernel and is outside this file's dependency contract; its own
     * round trip is asserted where it lives. The cross-reference record holds no signed field at all, so
     * reading its three identifiers needs no codec. What is given up is that a corrupted amount inside a
     * fixture would not be caught here; what is kept is that this file asserts only what it can assert
     * without importing a decoder none of its cases are about.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws IllegalStateException if a fixture cannot be read from the classpath, which would mean the
     *     module no longer ships the records these cases are keyed to
     */
    @Test
    @DisplayName("the fixture records occupy the lengths CVACT01Y, CVCUS01Y and CVACT03Y declare")
    void theFixtureRecordsMatchTheirCopybookRecordLengths() {
        assertThat(fixtureBytes(ACCOUNT_FIXTURE)).hasSize(ACCOUNT_RECORD_LENGTH);
        assertThat(fixtureBytes(ACCOUNT_NEGATIVE_FIXTURE)).hasSize(ACCOUNT_RECORD_LENGTH);
        assertThat(fixtureBytes(CUSTOMER_FIXTURE)).hasSize(CUSTOMER_RECORD_LENGTH);
        assertThat(fixtureBytes(CARD_XREF_FIXTURE)).hasSize(CARD_XREF_RECORD_LENGTH);

        assertThat(this.crossReference.getCardNum())
                .as("XREF-CARD-NUM PIC X(16) at L5 of app/cpy/CVACT03Y.cpy")
                .hasSize(XREF_CARD_NUM_END);
        assertThat(this.crossReference.getCustomerId()).isEqualTo(11L);
        assertThat(this.crossReference.getAccountId()).isEqualTo(11L);

        // WHY : Assumptions: the two keys hold the same number and are still told apart by WIDTH, because
        //       the account key is published at eleven digits and the customer key at nine. A response
        //       that had confused the two would therefore fail the happy-path case on the rendered
        //       identifier alone, which is why one fixture triple can serve both keys safely.
        assertThat(ACCOUNT_FILTER_WIDTH).isNotEqualTo(XREF_CUST_ID_END - XREF_CARD_NUM_END);
    }

    /**
     * Answers the composing query with one row carrying the two supplied sides.
     *
     * <p>Assumptions: the row itself is always present and only its two master sides vary, which is how
     * the target distinguishes the three reference outcomes. The composing statement joins both masters
     * OUTER, so a located cross-reference row comes back whether or not either master holds anything for
     * it; an inner join would have collapsed the account-master miss and the customer-master miss into one
     * empty result, and each of those arms carries its own sentence.</p>
     *
     * <p>This method returns no value; it records the answer on the substituted repository.</p>
     *
     * @param accountSide the account master row the statement yields, or {@code null} to drive the
     *     account-master miss
     * @param customerSide the customer master row the statement yields, or {@code null} to drive the
     *     customer-master miss
     */
    private void stubComposition(Account accountSide, Customer customerSide) {
        when(this.crossReferences.findAccountScreenRows(any(), any(Limit.class)))
                .thenReturn(List.of(
                        new AccountScreenRow(this.crossReference, accountSide, customerSide)));
    }

    /**
     * Builds the cross-reference row from the fifty-byte fixture this module ships.
     *
     * <p>Assumptions: the three fields are cut at the offsets {@code app/cpy/CVACT03Y.cpy} declares --
     * {@code XREF-CARD-NUM PIC X(16)} at L5, {@code XREF-CUST-ID PIC 9(09)} at L6 and
     * {@code XREF-ACCT-ID PIC 9(11)} at L7 -- and the trailing {@code FILLER PIC X(14)} at L8 is dropped,
     * as the target drops it. The record is read as US-ASCII because it is the plain-text form of the
     * dataset and holds only digits and blanks; the fixed-width offsets are byte offsets, so a
     * variable-width decoding would misplace every field after the first.</p>
     *
     * <p>Assumptions: the row is built from the fixture rather than from constants written here, so that
     * this class and the container-backed cases of the sibling repository package describe ONE account. A
     * constant would let the two drift apart with nothing to detect it.</p>
     *
     * @return the cross-reference row the fixture describes, never {@code null}
     * @throws IllegalStateException if the fixture is absent from the classpath or cannot be read, or if
     *     either identifier field does not hold digits, which would mean the record no longer matches the
     *     layout these offsets come from
     */
    private static CardXref crossReferenceFixture() {
        String record = new String(fixtureBytes(CARD_XREF_FIXTURE), StandardCharsets.US_ASCII);

        try {
            return new CardXref(
                    record.substring(0, XREF_CARD_NUM_END),
                    Long.parseLong(record.substring(XREF_CARD_NUM_END, XREF_CUST_ID_END)),
                    Long.parseLong(record.substring(XREF_CUST_ID_END, XREF_ACCT_ID_END)));
        } catch (NumberFormatException misaligned) {
            throw new IllegalStateException(
                    "the cross-reference fixture no longer holds digits at the offsets"
                            + " app/cpy/CVACT03Y.cpy L5 through L7 declare", misaligned);
        }
    }

    /**
     * Reads one fixture record from the test classpath.
     *
     * <p>Assumptions: the record is resolved by CLASSPATH NAME and never through configuration, so it is
     * found wherever the build places the test resources and a case cannot be pointed at a different file
     * by a property. The bytes are returned undecoded, because the caller decides whether the record
     * admits a character decoding at all.</p>
     *
     * <p>Trade-offs: the checked failure is wrapped rather than declared, so no case that reads a fixture
     * has to carry an I/O clause it can do nothing about. What is given up is the ability to distinguish
     * an absent fixture from an unreadable one by exception type; the wrapped message names the resource
     * either way, and both mean the same thing for a case keyed to that record.</p>
     *
     * @param classpathName the resource name of the fixture, relative to the test classpath root
     * @return the whole record as it is stored, never {@code null}
     * @throws IllegalStateException if the resource is absent from the classpath or cannot be read
     */
    private static byte[] fixtureBytes(String classpathName) {
        try (InputStream stream =
                AccountViewServiceTest.class.getClassLoader().getResourceAsStream(classpathName)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "the fixture " + classpathName + " is absent from the test classpath");
            }
            return stream.readAllBytes();
        } catch (IOException unreadable) {
            throw new IllegalStateException(
                    "the fixture " + classpathName + " could not be read", unreadable);
        }
    }

    /**
     * Composes the file-error text at the width its declared members sum to.
     *
     * <p>Assumptions: the members are laid out in the order and at the widths
     * {@code app/cbl/COACTVWC.cbl} declares for {@code WS-FILE-ERROR-MESSAGE} -- the twelve-character
     * opening at L87, the eight-character operation name at L89, the four-character joiner at L91, the
     * nine-character file name at L93, the fifteen-character joiner at L95, the ten-character response
     * code at L98, the seven-character joiner at L100, the ten-character reason code at L102 and the
     * five-character trailing filler of blanks at L104 -- which sum to eighty. The operation and file
     * values are the ones the first read's unexpected-response arm moves in, at L762 and L763.</p>
     *
     * <p>Assumptions: the two code fields are filled COMPLETELY with digits rather than left at the blank
     * initial state their declarations give them at L99 and L103. The digits are synthetic and stand for
     * nothing; filling the fields is what makes the boundary assertion sharp, because a cut falling even
     * one character before the end of the last named member would then lose a digit and the comparison
     * that ignores trailing blanks would fail. Left blank, that comparison would hold for the wrong
     * reason.</p>
     *
     * <p>Assumptions: this method raises nothing, and the refusal the padding helper below can make is
     * unreachable from here. Every value it is given is a literal shorter than the field it is placed in,
     * so the two are checkable against each other by reading this method, and no runtime outcome depends
     * on the check. The inapplicability is stated rather than passed over, so that a reader can tell it
     * from an omitted at-clause.</p>
     *
     * @return the eighty-character composed text, never {@code null}
     */
    private static String composedFileErrorText() {
        return "File Error: "
                + padRight("READ", 8)
                + " on "
                + padRight("CXACAIX", 9)
                + " returned RESP "
                + "0".repeat(10)
                + ",RESP2 "
                + "0".repeat(10)
                + " ".repeat(5);
    }

    /**
     * Pads a value on the right to a declared field width.
     *
     * <p>Assumptions: padding is on the RIGHT because a character field in the reference is
     * left-justified and blank-filled, which is what a move into a wider alphanumeric field produces.
     * Padding on the left would place the value at a different offset and the composed width would still
     * be correct, so the arithmetic assertion would pass while describing a layout the reference does not
     * have.</p>
     *
     * @param value the value to place at the left of the field; must not be {@code null} and must not be
     *     longer than the field
     * @param width the declared width of the field
     * @return the value followed by enough blanks to fill the field, never {@code null}
     * @throws IllegalArgumentException if the value is longer than the declared width, which would mean
     *     the composed text no longer matches the widths it is summed from
     */
    private static String padRight(String value, int width) {
        if (value.length() > width) {
            throw new IllegalArgumentException("the value occupies " + value.length()
                    + " characters but the field declares " + width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Resolves a sweep token to the screen value it stands for.
     *
     * <p>Assumptions: the two sentinel inputs travel as symbolic TOKENS rather than as literal values,
     * for two independent reasons that both make a literal unusable. A run of blanks would be trimmed away
     * by the argument source before it reached the case, arriving as an empty value and quietly testing a
     * different input; and the reference's other absent-input sentinel has no printable spelling at all, so
     * it cannot be written in a comma-separated argument list. Any other token is returned unchanged,
     * because the remaining inputs are ordinary digit strings that survive the source untouched.</p>
     *
     * <p>Assumptions: both sentinels are built at the declared width of the field they stand in for,
     * eleven characters, which is what {@code WS-CARD-RID-ACCT-ID PIC 9(11)} at L78 and its character
     * redefinition at L79 and L80 declare. A screen field always reaches the reference at its declared
     * width, so a shorter run would not be the value the reference tests at L653 and L654.</p>
     *
     * @param token the symbolic token from the argument source
     * @return the screen value the token stands for, never {@code null}
     */
    private static String screenValue(String token) {
        if (TOKEN_LOW_VALUES.equals(token)) {
            return String.valueOf(FieldValidationFlag.ABSENT_INPUT_LOW_VALUE)
                    .repeat(ACCOUNT_FILTER_WIDTH);
        }
        if (TOKEN_SPACES.equals(token)) {
            return String.valueOf(FieldValidationFlag.ABSENT_INPUT_SPACE)
                    .repeat(ACCOUNT_FILTER_WIDTH);
        }
        return token;
    }
}
