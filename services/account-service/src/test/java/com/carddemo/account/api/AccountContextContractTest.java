package com.carddemo.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.account.config.SecurityConfig;
import com.carddemo.account.domain.Account;
import com.carddemo.account.domain.CardXref;
import com.carddemo.account.dto.AccountContextView;
import com.carddemo.account.dto.AccountUpdateRequest;
import com.carddemo.account.dto.AccountUpdateResponse;
import com.carddemo.account.dto.AccountViewResponse;
import com.carddemo.account.dto.CardXrefLookupRequest;
import com.carddemo.account.dto.CardXrefResponse;
import com.carddemo.account.dto.CardXrefView;
import com.carddemo.account.mapper.AccountContextMapper;
import com.carddemo.account.mapper.AccountMapper;
import com.carddemo.account.mapper.CardXrefMapper;
import com.carddemo.account.mapper.CustomerMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.account.service.AccountUpdateService;
import com.carddemo.account.service.AccountViewService;
import com.carddemo.common.money.MoneyModule;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
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

    /** The end-user account-view path template. */
    private static final String ACCOUNT_VIEW_PATH_TEMPLATE = "/api/v1/accounts/{accountId}/view";

    /** The end-user by-account cross-reference listing path template. */
    private static final String ACCOUNT_XREF_PATH_TEMPLATE =
            "/api/v1/accounts/{accountId}/card-cross-references";

    /**
     * The adapters whose mapping annotations make up the mounted set.
     *
     * <p>Assumptions: they are enumerated rather than discovered by scanning, so that adding an adapter is
     * a deliberate act that shows up in this list. A scan would silently absorb a new one and the guard
     * below would keep passing while the new routes were published nowhere.</p>
     */
    private static final List<Class<?>> ADAPTERS =
            List.of(AccountController.class, CardXrefController.class, CustomerController.class);

    /**
     * The transfer records whose shape this contract restates, checked component for component.
     */
    private static final List<Class<?>> DOCUMENTED_RECORDS = List.of(
            AccountContextView.class, CardXrefView.class, CardXrefLookupRequest.class,
            AccountViewResponse.class, AccountViewResponse.AccountDetail.class,
            AccountViewResponse.CustomerDetail.class, AccountUpdateRequest.class,
            AccountUpdateResponse.class, CardXrefResponse.class);

    /** The HTTP methods a path item may declare an operation under. */
    private static final List<String> HTTP_METHODS =
            List.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    /** The prefix a published HEAD operation carries in the comparable operation key. */
    private static final String HEAD_VERB_PREFIX = "head ";

    /** The prefix a mounted GET mapping carries in the comparable operation key. */
    private static final String GET_VERB_PREFIX = "get ";

    /** The tag every operation of the machine-facing surface carries. */
    private static final String INTERNAL_TAG = "internal";

    /** The tag every operation of the browser-facing surface carries. */
    private static final String END_USER_TAG = "end-user";

    /** The security scheme the machine-facing surface requires. */
    private static final String INTERNAL_SCHEME = "internalServiceToken";

    /** The security scheme the browser-facing surface requires. */
    private static final String END_USER_SCHEME = "bearerAuth";

    /** The operation extension field naming the authorities the application chain enforces. */
    private static final String AUTHORITIES_FIELD = "x-required-authorities";

    /** A single non-empty segment standing in for any path variable when a rule is evaluated. */
    private static final String SAMPLE_SEGMENT = "11";

    /** The matcher the security rules are evaluated with, matching the house idiom. */
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

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
     * <p>Assumptions: the three mapper collaborators the service gained for the human view are
     * substituted with mocks here rather than built for real, because every case in this class asserts
     * the MACHINE contract -- the three monetary strings a neighbouring context reads -- and never
     * touches the human view. Constructing a real customer mapper would additionally require the
     * protected-identifier port, which is a dependency this class's subject does not use.</p>
     *
     * @param crossReferences the cross-reference repository substitute
     * @param accounts the account repository substitute
     * @param customers the customer repository substitute
     * @return the service under test, never {@code null}
     */
    private AccountViewService reads(CardXrefRepository crossReferences,
            AccountRepository accounts,
            CustomerRepository customers) {
        return new AccountViewService(accounts, customers, crossReferences, this.mapper,
                mock(AccountMapper.class), mock(CustomerMapper.class), mock(CardXrefMapper.class));
    }

    /**
     * Builds the controller under test over a read path, with the write path substituted.
     *
     * <p>Assumptions: the write path is a mock because no case in this class performs an update. It is a
     * constructor argument rather than an optional collaborator, so the controller cannot be built
     * without one, and substituting it is what keeps these cases about the read they assert.</p>
     *
     * @param readPath the read service the controller should use
     * @return the controller under test, never {@code null}
     */
    private static AccountController controllerOver(AccountViewService readPath) {
        return new AccountController(readPath, mock(AccountUpdateService.class));
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
     * Verifies the three internal paths are exactly the ones the neighbouring context builds.
     */
    @Test
    @DisplayName("the three internal paths are exactly the ones the consumer builds")
    void theThreeInternalPathsArePinned() {
        assertThat(CardXrefController.BASE_PATH + CardXrefController.LOOKUP_PATH)
                .isEqualTo(XREF_LOOKUP_PATH);
        assertThat(AccountController.BASE_PATH + "/{accountId}").isEqualTo(ACCOUNT_PATH_TEMPLATE);
        assertThat(CustomerController.BASE_PATH + "/{customerId}").isEqualTo(CUSTOMER_PATH_TEMPLATE);
    }

    /**
     * Verifies the published contract declares exactly the five paths this context serves, with exactly
     * the operations it serves at each.
     *
     * <p>Assumptions: the document is read from the classpath rather than from a source path, so what is
     * asserted is the copy that is packaged and served rather than a file that merely exists in the tree.</p>
     *
     * <p>Refactoring Rationale: this case asserted a closed set of THREE paths and three operations, and it
     * passed while three further routes were mounted and served requests -- the human account view, the
     * account update and the by-account cross-reference listing. A closed-set assertion over the DOCUMENT
     * can only ever detect a contract entry that should not be there; it cannot see a handler the contract
     * omits, which is what the mounted-versus-published case below exists for. Both are kept because they
     * fail on different mistakes.</p>
     *
     * @throws Exception if the packaged document is absent or unreadable, which is itself the defect
     */
    @Test
    @DisplayName("the contract declares exactly the five served paths and their operations")
    void theContractDocumentDeclaresEveryServedOperation() throws Exception {
        Map<String, Object> paths = contractPaths();

        assertThat(paths).containsOnlyKeys(XREF_LOOKUP_PATH, ACCOUNT_PATH_TEMPLATE,
                ACCOUNT_VIEW_PATH_TEMPLATE, ACCOUNT_XREF_PATH_TEMPLATE, CUSTOMER_PATH_TEMPLATE);
        assertThat(operation(paths, XREF_LOOKUP_PATH)).containsOnlyKeys("post");

        // WHY : Assumptions: the account address carries BOTH a get and a put, and the two serve
        //   different surfaces -- the get is the neighbouring context's machine read, the put is the
        //   end-user edit. They share an address because they act on one record; they are separated by
        //   filter chain rather than by prefix, which SecurityConfig.ACCOUNT_PATH_PATTERN records in
        //   full.
        assertThat(operation(paths, ACCOUNT_PATH_TEMPLATE)).containsOnlyKeys("get", "put");
        assertThat(operation(paths, ACCOUNT_VIEW_PATH_TEMPLATE)).containsOnlyKeys("get");
        assertThat(operation(paths, ACCOUNT_XREF_PATH_TEMPLATE)).containsOnlyKeys("get");

        // WHY : Assumptions: the customer path must declare BOTH methods, and the HEAD entry is the one
        //   that matters most: the consumer issues HEAD, so a contract naming only GET would document a
        //   surface nobody calls. GET is required too because one handler serves both -- it is declared as a
        //   GET mapping and the framework answers HEAD from it -- so a document naming only HEAD would
        //   misdescribe the mapping that actually exists.
        assertThat(operation(paths, CUSTOMER_PATH_TEMPLATE)).containsOnlyKeys("head", "get");
    }

    /**
     * Verifies that the operations the contract publishes and the operations this package mounts are the
     * same set, in BOTH directions.
     *
     * <p>Purpose: this closes the dimension the closed-set case above cannot reach. That one compares the
     * document with a literal list, and it held while three mounted routes were published nowhere -- so a
     * client written from the contract could not discover an operation that exists, and nothing in the
     * build said so. The sibling auth and card contracts carry the same guard for the opposite failure,
     * where a published operation had no handler and answered 404 while three artifacts described it as
     * present. One assertion covering both directions is what makes either mistake fail a build.</p>
     *
     * <p>Assumptions: the mounted set is read from the mapping annotations rather than from a running
     * context, so the assertion needs no container and cannot be satisfied by a stub. All three adapters of
     * this package are enrolled explicitly, which is the intended friction: a fourth adapter has to be
     * named here, and until it is, its routes appear in neither set and the omission is visible as a
     * missing contract entry rather than as silence.</p>
     *
     * <p>Assumptions: HEAD is the one asymmetry, and it is deliberate rather than a gap. The framework
     * answers HEAD from a GET mapping by discarding the body, so a published HEAD is served by the GET
     * mapping at the same address and there is no HEAD annotation to find. The reverse does not hold: a
     * GET mapping does not oblige the contract to declare HEAD, because HEAD is implicitly available on
     * every GET in this system and declaring it everywhere would document six operations no consumer
     * issues. The customer probe declares it because its consumer issues exactly that method.</p>
     *
     * @throws Exception if the packaged document is absent or unreadable, which is itself the defect
     */
    @Test
    @DisplayName("the published and mounted operation sets agree in both directions")
    void everyPublishedOperationIsMounted() throws Exception {
        Set<String> mounted = mountedOperations();
        Set<String> published = publishedOperations();

        // WHY : Assumptions: a published HEAD is looked up as the GET at the same address, for the
        //   reason recorded on this method. Rewriting the PUBLISHED entry rather than expanding the
        //   MOUNTED set is what keeps the second direction strict: expanding the mounted set with a
        //   head entry for every get would make the reverse comparison demand a head declaration on
        //   every read.
        Set<String> publishedAsMounted = published.stream()
                .map(operation -> operation.startsWith(HEAD_VERB_PREFIX)
                        ? GET_VERB_PREFIX + operation.substring(HEAD_VERB_PREFIX.length())
                        : operation)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(publishedAsMounted)
                .as("every published operation must have a handler: one that does not answers 404 while"
                        + " the contract, the filter chain and this package's charter all describe it as"
                        + " present")
                .isSubsetOf(mounted);
        assertThat(mounted)
                .as("every mounted operation must be published: one that is not is a surface no client"
                        + " can discover, no reviewer sees in the contract and no gateway route covers")
                .isSubsetOf(publishedAsMounted);
    }

    /**
     * Verifies each end-user operation declares the authorities the application chain actually enforces,
     * and that none of them falls inside a subtree the chain denies outright.
     *
     * <p>Assumptions: the declared list is compared with the constant the chain builds its rule from
     * rather than with a literal pair. Comparing against a literal would restate the contract; comparing
     * against the constant means neither the document nor the chain can be edited alone.</p>
     *
     * <p>Assumptions: the authorities are declared as a LIST where the sibling auth and card contracts
     * declare a single {@code x-required-authority}. The difference follows from the rule: this context's
     * catch-all admits EITHER business group, exactly as the reference menu graph admitted both user types
     * to the account screens, so a singular field could not state the rule without narrowing it.</p>
     *
     * @throws Exception if the packaged document is absent or unreadable, which is itself the defect
     */
    @Test
    @DisplayName("each end-user operation declares the authorities the chain enforces")
    void endUserOperationsDeclareTheEnforcedAuthorities() throws Exception {
        Map<String, Map<String, Object>> operations = publishedOperationDetail();
        Map<String, Map<String, Object>> endUser = surfaceSubset(operations, END_USER_TAG);

        assertThat(endUser).as("the end-user surface must not be empty").isNotEmpty();
        endUser.forEach((name, operation) -> {
            assertThat(operation.get(AUTHORITIES_FIELD))
                    .as("operation %s must declare %s", name, AUTHORITIES_FIELD)
                    .isEqualTo(SecurityConfig.BUSINESS_AUTHORITIES);
            assertThat(securitySchemesOf(operation))
                    .as("operation %s must require the identity-provider token and not the internal one",
                            name)
                    .containsExactly(END_USER_SCHEME);

            String concrete = concretePath(pathOf(name));
            assertThat(MATCHER.match(SecurityConfig.CARD_XREF_PATH_PATTERN, concrete))
                    .as("%s must not fall inside the denied cross-reference subtree", name)
                    .isFalse();
            assertThat(MATCHER.match(SecurityConfig.CUSTOMER_PATH_PATTERN, concrete))
                    .as("%s must not fall inside the denied customer subtree", name)
                    .isFalse();
        });
    }

    /**
     * Verifies the internal surface is exactly the three addresses the internal chain is built from, and
     * that every operation on it requires the internal credential.
     *
     * <p>Assumptions: the comparison is against the CONSTANTS the internal chain composes its matcher
     * from rather than against the matcher itself, and the reason is deliberate rather than a
     * convenience: that accessor is package-visible on purpose, with its own recorded rationale for
     * staying out of the module's API, and widening it so a test in a different package could reach it
     * would give up the property it was narrowed for. The matcher's own behaviour -- including that it
     * claims the internal methods and refuses every neighbouring address and every other method -- is
     * asserted by {@code InternalApiSecurityConfigTest}, which lives in the package that can see it. The
     * two cases compose: that one pins the matcher to these addresses, this one pins the document's
     * internal tag to the same addresses, and neither can move alone.</p>
     *
     * <p>Assumptions: the two surfaces are asserted DISJOINT as well as complete. An end-user operation
     * that appeared on the internal surface would demand a service-minted token from a browser and would
     * be unreachable rather than insecure -- the failure would look like a 403 nobody could explain.</p>
     *
     * @throws Exception if the packaged document is absent or unreadable, which is itself the defect
     */
    @Test
    @DisplayName("the internal surface is exactly the three addresses the internal chain governs")
    void theInternalSurfaceIsExactlyTheInternalAddresses() throws Exception {
        Map<String, Map<String, Object>> operations = publishedOperationDetail();
        Map<String, Map<String, Object>> internal = surfaceSubset(operations, INTERNAL_TAG);
        Map<String, Map<String, Object>> endUser = surfaceSubset(operations, END_USER_TAG);

        assertThat(internal.keySet().stream().map(AccountContextContractTest::pathOf).distinct())
                .as("the internal surface must be exactly the three addresses the internal chain is"
                        + " composed from")
                .containsExactlyInAnyOrder(XREF_LOOKUP_PATH, ACCOUNT_PATH_TEMPLATE,
                        CUSTOMER_PATH_TEMPLATE);
        internal.forEach((name, operation) ->
                assertThat(securitySchemesOf(operation))
                        .as("%s must require the internal token", name)
                        .containsExactly(INTERNAL_SCHEME));

        assertThat(internal.keySet())
                .as("no operation may carry both surface tags")
                .doesNotContainAnyElementsOf(endUser.keySet());
        assertThat(internal.size() + endUser.size())
                .as("every published operation must declare exactly one surface")
                .isEqualTo(operations.size());
    }

    /**
     * Verifies every schema named after a transfer record declares exactly that record's components.
     *
     * <p>Purpose: a schema and the record it describes are two independent statements of one shape, and
     * nothing in the build compares them. This case does, by reflecting over the record's components and
     * requiring the schema of the same name to declare exactly those property names -- so a component
     * added, removed or renamed without the document following fails here rather than at a client.</p>
     *
     * <p>Assumptions: property NAMES are compared and types are not. The names are what a client binds by
     * and are the whole of the agreement a JSON body carries; the types are asserted where they matter by
     * the rendering cases in this class, which pin money as a quoted string rather than a number.</p>
     *
     * @throws Exception if the packaged document is absent or unreadable, which is itself the defect
     */
    @Test
    @DisplayName("every record-named schema declares exactly that record's components")
    void everyRecordNamedSchemaMatchesItsRecord() throws Exception {
        Map<String, Object> schemas = schemaSection();

        for (Class<?> record : DOCUMENTED_RECORDS) {
            List<String> components = Arrays.stream(record.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) schemas.get(record.getSimpleName());
            assertThat(schema)
                    .as("the contract must declare a schema named %s", record.getSimpleName())
                    .isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertThat(properties)
                    .as("schema %s must declare properties", record.getSimpleName())
                    .isNotNull();
            assertThat(properties.keySet())
                    .as("schema %s must declare exactly the components of %s",
                            record.getSimpleName(), record.getName())
                    .containsExactlyInAnyOrderElementsOf(components);
        }
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

        AccountContextView view = controllerOver(
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

        String body = this.json.writeValueAsString(controllerOver(
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
        AccountController controller = controllerOver(
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
     * Reads the schema section of the packaged contract.
     *
     * @return the declared schemas keyed by name, never {@code null}
     * @throws Exception if the document is absent from the classpath or declares no schema section
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> schemaSection() throws Exception {
        try (InputStream document = getClass().getResourceAsStream("/openapi/account-api.yaml")) {
            assertThat(document).as("/openapi/account-api.yaml must be on the classpath").isNotNull();
            Map<String, Object> root = new Yaml().load(document);
            Map<String, Object> components = (Map<String, Object>) root.get("components");
            assertThat(components).as("the contract must declare components").isNotNull();
            Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
            assertThat(schemas).as("the contract must declare schemas").isNotNull();
            return schemas;
        }
    }

    /**
     * Reads every published operation as a {@code "<method> <path>"} key mapped to its own declaration.
     *
     * @return the published operations in document order, never {@code null}
     * @throws Exception if the document is absent from the classpath or is not readable as a mapping
     */
    private Map<String, Map<String, Object>> publishedOperationDetail() throws Exception {
        Map<String, Object> paths = contractPaths();
        Map<String, Map<String, Object>> operations = new LinkedHashMap<>();
        for (String path : paths.keySet()) {
            Map<String, Object> pathItem = operation(paths, path);
            for (String method : HTTP_METHODS) {
                if (pathItem.containsKey(method)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> declaration = (Map<String, Object>) pathItem.get(method);
                    operations.put(method + " " + path, declaration);
                }
            }
        }
        assertThat(operations).as("the contract must declare at least one operation").isNotEmpty();
        return operations;
    }

    /**
     * Reads the set of published operations as comparable keys.
     *
     * @return the keys, never {@code null}
     * @throws Exception if the document is absent from the classpath or is not readable as a mapping
     */
    private Set<String> publishedOperations() throws Exception {
        return new LinkedHashSet<>(publishedOperationDetail().keySet());
    }

    /**
     * Reads the set of mounted operations from the mapping annotations of every enrolled adapter.
     *
     * <p>Assumptions: each mapping is read as a MERGED {@code RequestMapping} rather than by asking a
     * shorthand annotation for its own members. The shorthand annotations declare their path under
     * {@code value} and alias it to {@code path}, and raw reflection returns whichever member the author
     * happened to write -- so reading one member directly finds nothing on half of these handlers. The
     * merged view resolves the alias and additionally reports the verb, which is why one lookup replaces a
     * branch per annotation type.</p>
     *
     * @return the keys, never {@code null}
     */
    private static Set<String> mountedOperations() {
        Set<String> mounted = new LinkedHashSet<>();
        for (Class<?> adapter : ADAPTERS) {
            RequestMapping atClass =
                    AnnotatedElementUtils.findMergedAnnotation(adapter, RequestMapping.class);
            assertThat(atClass)
                    .as("%s must carry a class-level request mapping", adapter.getSimpleName())
                    .isNotNull();
            String prefix = firstDeclaredPath(atClass, "");

            for (Method handler : adapter.getDeclaredMethods()) {
                RequestMapping mapping =
                        AnnotatedElementUtils.findMergedAnnotation(handler, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String path = prefix + firstDeclaredPath(mapping, "");
                assertThat(mapping.method())
                        .as("handler %s.%s must declare its HTTP method", adapter.getSimpleName(),
                                handler.getName())
                        .isNotEmpty();
                for (RequestMethod verb : mapping.method()) {
                    mounted.add(verb.name().toLowerCase(Locale.ROOT) + " " + path);
                }
            }
        }
        assertThat(mounted).as("the enrolled adapters must mount at least one operation").isNotEmpty();
        return mounted;
    }

    /**
     * Reads the single path a merged mapping declares, tolerating either alias.
     *
     * @param mapping the merged mapping annotation
     * @param fallback the value to use when the mapping declares no path at all, which a class-level
     *     mapping legitimately may not
     * @return the declared path, or the fallback
     */
    private static String firstDeclaredPath(RequestMapping mapping, String fallback) {
        if (mapping.path().length > 0) {
            return mapping.path()[0];
        }
        if (mapping.value().length > 0) {
            return mapping.value()[0];
        }
        return fallback;
    }

    /**
     * Selects the published operations carrying one surface tag.
     *
     * @param operations every published operation keyed by method and path
     * @param tag the surface tag to select
     * @return the matching operations, never {@code null}
     */
    private static Map<String, Map<String, Object>> surfaceSubset(
            Map<String, Map<String, Object>> operations, String tag) {

        Map<String, Map<String, Object>> selected = new LinkedHashMap<>();
        operations.forEach((name, declaration) -> {
            Object tags = declaration.get("tags");
            assertThat(tags).as("operation %s must declare tags", name).isInstanceOf(List.class);
            if (((List<?>) tags).contains(tag)) {
                selected.put(name, declaration);
            }
        });
        return selected;
    }

    /**
     * Reads the security scheme names one operation requires.
     *
     * @param operation the operation declaration
     * @return the scheme names, never {@code null}
     */
    private static List<String> securitySchemesOf(Map<String, Object> operation) {
        Object security = operation.get("security");
        assertThat(security).as("every operation must declare a security requirement")
                .isInstanceOf(List.class);
        return ((List<?>) security).stream()
                .map(requirement -> ((Map<?, ?>) requirement).keySet().iterator().next().toString())
                .toList();
    }

    /**
     * Extracts the path from an operation key.
     *
     * @param operationKey the {@code "<method> <path>"} key
     * @return the path template
     */
    private static String pathOf(String operationKey) {
        return operationKey.substring(operationKey.indexOf(' ') + 1);
    }

    /**
     * Substitutes a concrete segment for every template variable in a path.
     *
     * <p>Assumptions: one substitution value serves every variable, because a security rule matches on
     * segment structure rather than on the value in a segment.</p>
     *
     * @param pathTemplate the published path template
     * @return a concrete request path a rule can be evaluated against
     */
    private static String concretePath(String pathTemplate) {
        return pathTemplate.replaceAll("\\{[^}]+}", SAMPLE_SEGMENT);
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
