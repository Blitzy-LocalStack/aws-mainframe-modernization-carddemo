package com.carddemo.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.account.domain.Account;
import com.carddemo.account.domain.CardXref;
import com.carddemo.account.dto.AccountContextView;
import com.carddemo.account.dto.CardXrefLookupRequest;
import com.carddemo.account.dto.CardXrefView;
import com.carddemo.account.mapper.AccountContextMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.account.service.AccountViewService;
import com.carddemo.common.money.MoneyModule;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the wire contract the authorization bounded context resolves its account reads against.
 *
 * <h2>Purpose</h2>
 * <p>Three operations in this module exist for exactly one consumer: the authorization service's
 * {@code RestAccountContextClient}, which builds each request path itself and deserialises each response into
 * its own record. Neither side compiles against the other -- they are separate deployables and the layering
 * rules forbid one context importing another's types -- so nothing in either build reports a disagreement.
 * This class supplies the server half of that agreement: the exact paths, the exact JSON property names, the
 * rendering of money, the absence of anything sensitive, and the two not-found behaviours.</p>
 *
 * <p>Assumptions: the literal path strings and property names below are duplicated deliberately rather than
 * derived from a shared constant. The counterpart is
 * {@code services/authorization-service/src/test/java/com/carddemo/authorization/service/RestAccountContextClientTest}
 * and specifically its path-pinning case, which pins the same literals from the consumer's side. Two
 * independently written literal sets that must match are what makes a unilateral change to either side fail a
 * test; a shared constant would let both move together and prove nothing about the agreement.</p>
 *
 * <p>Alternatives Considered: placing the three paths in the shared kernel so both sides could import them.
 * Rejected on two grounds. The kernel's contents are enumerated by the migration plan at its section 0.4.1.2
 * and a route registry is not among them, so adding one would widen the shared surface beyond what the plan
 * assigns. More importantly it would remove the property this pair of tests exists to provide: if both sides
 * read one constant, renaming the route silently renames it for everyone and the contract change ships
 * unnoticed.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no parameter,
 * return or exception section.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountContextContractTest {

    /**
     * The cross-reference lookup path, written as a literal on purpose.
     */
    private static final String XREF_LOOKUP_PATH = "/api/v1/card-xrefs/lookup";

    /**
     * The account read path template, written as a literal on purpose.
     */
    private static final String ACCOUNT_PATH_TEMPLATE = "/api/v1/accounts/{accountId}";

    /**
     * The customer presence path template, written as a literal on purpose.
     */
    private static final String CUSTOMER_PATH_TEMPLATE = "/api/v1/customers/{customerId}";

    /**
     * A fabricated primary account number, structurally valid and belonging to nobody.
     */
    private static final String CARD_NUMBER = "4111111111111111";

    /**
     * The account the fabricated card resolves to.
     */
    private static final long ACCOUNT_ID = 12_345_678_901L;

    /**
     * The customer the fabricated card resolves to.
     */
    private static final long CUSTOMER_ID = 987_654_321L;

    /**
     * A mapper configured exactly as the application's is, so the rendering asserted here is the deployed one.
     *
     * <p>Assumptions: the money module is registered because without it a monetary member serialises as a JSON
     * NUMBER, and a number is parsed into a binary double by most clients -- which is the one outcome
     * transformation rule T3 forbids for the money path. A mapper without the module would therefore pass a
     * shape assertion while producing the wrong wire form.</p>
     */
    private final JsonMapper json = JsonMapper.builder().addModule(new MoneyModule()).build();

    /**
     * The projection under test, in its real form rather than a substitute.
     */
    private final AccountContextMapper mapper = new AccountContextMapper();

    /**
     * Builds the read path over substituted repositories.
     *
     * @param crossReferences the cross-reference repository substitute
     * @param accounts the account repository substitute
     * @param customers the customer repository substitute
     * @return the service under test, never {@code null}
     */
    private AccountViewService reads(CardXrefRepository crossReferences,
            AccountRepository accounts,
            CustomerRepository customers) {
        return new AccountViewService(accounts, customers, crossReferences, this.mapper);
    }

    /**
     * Builds an account master row with the three monetary values the contract publishes.
     *
     * @return the row, never {@code null}
     */
    private static Account accountRow() {
        return new Account(ACCOUNT_ID, "Y",
                new BigDecimal("1234.56"), new BigDecimal("5000.00"), new BigDecimal("500.00"),
                LocalDate.of(2020, 1, 1), LocalDate.of(2027, 12, 31), LocalDate.of(2024, 6, 1),
                new BigDecimal("100.00"), new BigDecimal("200.00"), "12345", "DEFAULT");
    }

    /**
     * Verifies the three published paths are exactly the ones the consumer builds.
     */
    @Test
    @DisplayName("the three published paths are exactly the ones the consumer builds")
    void theThreePublishedPathsArePinned() {
        assertThat(CardXrefController.BASE_PATH + CardXrefController.LOOKUP_PATH)
                .isEqualTo(XREF_LOOKUP_PATH);
        assertThat(AccountController.BASE_PATH + "/{accountId}").isEqualTo(ACCOUNT_PATH_TEMPLATE);
        assertThat(CustomerController.BASE_PATH + "/{customerId}").isEqualTo(CUSTOMER_PATH_TEMPLATE);
    }

    /**
     * Verifies the published contract document declares the three operations at those paths.
     *
     * <p>Assumptions: the document is read from the classpath rather than from a source path, so what is
     * asserted is the copy that is packaged and served rather than a file that merely exists in the tree.</p>
     *
     * @throws Exception if the packaged document is absent or unreadable, which is itself the defect
     */
    @Test
    @DisplayName("the contract document declares the three operations")
    void theContractDocumentDeclaresTheThreeOperations() throws Exception {
        Map<String, Object> paths = contractPaths();

        assertThat(paths).containsOnlyKeys(XREF_LOOKUP_PATH, ACCOUNT_PATH_TEMPLATE, CUSTOMER_PATH_TEMPLATE);
        assertThat(operation(paths, XREF_LOOKUP_PATH)).containsOnlyKeys("post");
        assertThat(operation(paths, ACCOUNT_PATH_TEMPLATE)).containsOnlyKeys("get");

        // WHY : Assumptions: the customer path must declare BOTH methods, and the HEAD entry is the one
        //   that matters most: the consumer issues HEAD, so a contract naming only GET would document a
        //   surface nobody calls. GET is required too because one handler serves both -- it is declared as a
        //   GET mapping and the framework answers HEAD from it -- so a document naming only HEAD would
        //   misdescribe the mapping that actually exists.
        assertThat(operation(paths, CUSTOMER_PATH_TEMPLATE)).containsOnlyKeys("head", "get");
    }

    /**
     * Verifies the cross-reference response carries exactly the two identifiers and never the card number.
     *
     * <p>Assumptions: the absence of the card number is asserted as well as the presence of the two
     * identifiers. The consumer supplied the card, so echoing it back would add nothing and would place a
     * primary account number in a second response body, a second access log and a second client's memory.</p>
     */
    @Test
    @DisplayName("the cross-reference response carries the two identifiers and no card number")
    void theCrossReferenceResponseCarriesNoCardNumber() {
        CardXrefRepository crossReferences = mock(CardXrefRepository.class);
        when(crossReferences.findByCardNum(CARD_NUMBER))
                .thenReturn(Optional.of(new CardXref(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID)));

        CardXrefView view = new CardXrefController(
                reads(crossReferences, mock(AccountRepository.class), mock(CustomerRepository.class)))
                .lookup(new CardXrefLookupRequest(CARD_NUMBER));

        assertThat(view.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(view.customerId()).isEqualTo(CUSTOMER_ID);

        String body = this.json.writeValueAsString(view);
        assertThat(this.json.readValue(body, Map.class)).containsOnlyKeys("accountId", "customerId");
        assertThat(body).doesNotContain(CARD_NUMBER).doesNotContain(CARD_NUMBER.substring(0, 6));
    }

    /**
     * Verifies the account response carries exactly the three monetary values, each as a JSON string.
     *
     * <p>Assumptions: the string rendering is asserted against the literal text including its quotes, because
     * a numeric rendering would satisfy a value comparison while being the form that loses exactness at the
     * client. The property set is asserted as exactly three, because the account row holds a further seven
     * columns -- the cycle amounts, the three dates, the postal code and the group -- and the consumer needs
     * none of them; publishing them would widen disclosure with no consumer.</p>
     */
    @Test
    @DisplayName("the account response carries three monetary values as JSON strings and nothing else")
    void theAccountResponseCarriesThreeMonetaryStrings() {
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountRow()));

        AccountContextView view = new AccountController(
                reads(mock(CardXrefRepository.class), accounts, mock(CustomerRepository.class)))
                .read(ACCOUNT_ID);

        String body = this.json.writeValueAsString(view);
        assertThat(this.json.readValue(body, Map.class))
                .containsOnlyKeys("creditLimit", "cashCreditLimit", "currentBalance");
        assertThat(body)
                .contains("\"creditLimit\":\"5000.00\"")
                .contains("\"cashCreditLimit\":\"500.00\"")
                .contains("\"currentBalance\":\"1234.56\"");
        assertThat(body)
                .as("the active status, cycle amounts, dates, postal code and group are not published")
                .doesNotContain("activeStatus")
                .doesNotContain("currentCycleCredit")
                .doesNotContain("addressZip")
                .doesNotContain("groupId");
    }

    /**
     * Verifies money crosses at scale two even when the stored value carries a different scale.
     *
     * <p>Assumptions: a stored value of a whole number of units is the case a scale error hides behind -- it
     * compares equal numerically while rendering as a different string -- so it is asserted explicitly.</p>
     */
    @Test
    @DisplayName("money crosses at scale two regardless of the stored scale")
    void moneyCrossesAtScaleTwo() {
        AccountRepository accounts = mock(AccountRepository.class);
        Account row = new Account(ACCOUNT_ID, "Y",
                new BigDecimal("0"), new BigDecimal("7000"), new BigDecimal("1000.5"),
                LocalDate.of(2020, 1, 1), LocalDate.of(2027, 12, 31), LocalDate.of(2024, 6, 1),
                BigDecimal.ZERO, BigDecimal.ZERO, "12345", "DEFAULT");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(row));

        String body = this.json.writeValueAsString(new AccountController(
                reads(mock(CardXrefRepository.class), accounts, mock(CustomerRepository.class)))
                .read(ACCOUNT_ID));

        assertThat(body)
                .contains("\"currentBalance\":\"0.00\"")
                .contains("\"creditLimit\":\"7000.00\"")
                .contains("\"cashCreditLimit\":\"1000.50\"");
    }

    /**
     * Verifies an unresolved card raises the type the shared advice renders as 404, naming no card digits.
     */
    @Test
    @DisplayName("an unresolved card raises a not-found naming no card digits")
    void anUnresolvedCardRaisesNotFoundNamingNoDigits() {
        CardXrefRepository crossReferences = mock(CardXrefRepository.class);
        when(crossReferences.findByCardNum(CARD_NUMBER)).thenReturn(Optional.empty());
        CardXrefController controller = new CardXrefController(
                reads(crossReferences, mock(AccountRepository.class), mock(CustomerRepository.class)));
        CardXrefLookupRequest request = new CardXrefLookupRequest(CARD_NUMBER);

        assertThatThrownBy(() -> controller.lookup(request))
                .isInstanceOf(NoSuchElementException.class)
                .satisfies(raised -> assertThat(raised.getMessage())
                        .doesNotContain(CARD_NUMBER)
                        .doesNotContain(CARD_NUMBER.substring(0, 6))
                        .doesNotContain(CARD_NUMBER.substring(12)));
    }

    /**
     * Verifies an absent account raises the type the shared advice renders as 404.
     */
    @Test
    @DisplayName("an absent account raises a not-found")
    void anAbsentAccountRaisesNotFound() {
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.empty());
        AccountController controller = new AccountController(
                reads(mock(CardXrefRepository.class), accounts, mock(CustomerRepository.class)));

        assertThatThrownBy(() -> controller.read(ACCOUNT_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(String.valueOf(ACCOUNT_ID));
    }

    /**
     * Verifies the customer probe answers with a status and never a body.
     *
     * <p>Assumptions: both outcomes are asserted to carry no body, because the consumer treats this operation
     * as a presence question and a body on either answer would be a shape it does not read. The present answer
     * is 204 rather than 200 for the same reason -- 200 announces a representation that does not exist.</p>
     */
    @Test
    @DisplayName("the customer probe answers with a status and never a body")
    void theCustomerProbeAnswersWithAStatusAndNoBody() {
        CustomerRepository customers = mock(CustomerRepository.class);
        when(customers.existsById(CUSTOMER_ID)).thenReturn(true);
        when(customers.existsById(CUSTOMER_ID + 1)).thenReturn(false);
        CustomerController controller = new CustomerController(
                reads(mock(CardXrefRepository.class), mock(AccountRepository.class), customers));

        ResponseEntity<Void> present = controller.exists(CUSTOMER_ID);
        ResponseEntity<Void> absent = controller.exists(CUSTOMER_ID + 1);

        assertThat(present.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(present.getBody()).isNull();
        assertThat(absent.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(absent.getBody()).isNull();
    }

    /**
     * Verifies the request the consumer sends deserialises under the published property name.
     *
     * <p>Assumptions: the direction asserted is inbound. The consumer serialises its own record and this
     * module must accept the result, so a mismatch here would appear as a validation refusal on a
     * well-formed request rather than as a serialisation error anywhere.</p>
     */
    @Test
    @DisplayName("the lookup request deserialises under the published property name")
    void theLookupRequestDeserialisesUnderThePublishedName() {
        CardXrefLookupRequest bound = this.json.readValue(
                "{\"cardNumber\":\"" + CARD_NUMBER + "\"}", CardXrefLookupRequest.class);

        assertThat(bound.cardNumber()).isEqualTo(CARD_NUMBER);
        assertThat(this.json.writeValueAsString(bound)).isEqualTo("{\"cardNumber\":\"" + CARD_NUMBER + "\"}");
    }

    /**
     * Reads the paths block of the packaged contract document.
     *
     * @return the declared paths keyed by path template, never {@code null}
     * @throws Exception if the document is absent from the classpath or is not readable as a mapping, either
     *     of which would mean the packaged contract is not the one under test
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> contractPaths() throws Exception {
        try (InputStream document =
                     getClass().getResourceAsStream("/openapi/account-api.yaml")) {
            assertThat(document).as("/openapi/account-api.yaml must be on the classpath").isNotNull();
            Map<String, Object> root = new Yaml().load(document);
            assertThat(root).containsKey("paths");
            return (Map<String, Object>) root.get("paths");
        }
    }

    /**
     * Extracts one path item.
     *
     * @param paths the declared paths
     * @param path the path template to extract
     * @return the operations declared at that path keyed by method, never {@code null}
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> operation(Map<String, Object> paths, String path) {
        assertThat(paths).containsKey(path);
        return (Map<String, Object>) paths.get(path);
    }

    /**
     * Verifies the contract document names no member this module withholds.
     *
     * <p>Assumptions: the whole document text is searched rather than a schema walked, because the property
     * being asserted is an absence and an absence cannot be located by walking to it. The two encrypted
     * identifiers and the verification value are the members whose accidental publication would matter
     * most.</p>
     *
     * @throws Exception if the packaged document is absent or unreadable, which is itself the defect
     */
    @Test
    @DisplayName("the contract document names nothing this module withholds")
    void theContractNamesNothingWithheld() throws Exception {
        String document;
        try (InputStream stream = getClass().getResourceAsStream("/openapi/account-api.yaml")) {
            assertThat(stream).isNotNull();
            document = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        for (String withheld : List.of("ssnEncrypted", "governmentIssuedIdEncrypted", "cvv", "cardNum:")) {
            assertThat(document)
                    .as("the published contract must not declare %s", withheld)
                    .doesNotContain(withheld);
        }
    }
}
