package com.carddemo.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.account.config.SecurityConfig;
import com.carddemo.account.domain.Account;
import com.carddemo.account.domain.CardXref;
import com.carddemo.account.dto.AccountContextView;
import com.carddemo.account.dto.AccountLookupRequest;
import com.carddemo.account.dto.AccountUpdateRequest;
import com.carddemo.account.dto.AccountUpdateResponse;
import com.carddemo.account.dto.AccountViewResponse;
import com.carddemo.account.dto.CardXrefByAccountView;
import com.carddemo.account.dto.CardXrefLookupRequest;
import com.carddemo.account.dto.CardXrefResponse;
import com.carddemo.account.dto.CardXrefView;
import com.carddemo.account.dto.CustomerDisplayView;
import com.carddemo.account.dto.CustomerLookupRequest;
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
import com.carddemo.common.web.CursorToken;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
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
import tools.jackson.core.type.TypeReference;
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
     * The account-keyed cross-reference lookup path, written as a literal on purpose.
     */
    private static final String XREF_LOOKUP_BY_ACCOUNT_PATH = "/api/v1/card-xrefs/lookup-by-account";

    /**
     * The paged account-keyed cross-reference walk path, written as a literal on purpose.
     */
    private static final String XREF_SEARCH_BY_ACCOUNT_PATH = "/api/v1/card-xrefs/search-by-account";

    /**
     * The end-user account edit path, written as a literal on purpose.
     *
     * <p>Refactoring Rationale: this was the TEMPLATE {@code /api/v1/accounts/{accountId}}, which named the
     * address of both the machine read and the end-user edit while the machine read was a keyed {@code GET}.
     * The read moved to {@link #ACCOUNT_LOOKUP_PATH} first; the edit has now moved here, to a fixed address
     * with its key in the body it already carried, so no template remains on the account prefix at all.</p>
     *
     * <p>Assumptions: a literal and not a template, for the reason {@link #ACCOUNT_LOOKUP_PATH} records --
     * the identifier no longer appears in the address, and a future edit that reintroduced a path variable
     * would have to change this literal, which the closed-set assertion below would report.</p>
     */
    private static final String ACCOUNT_UPDATE_PATH = "/api/v1/accounts/update";

    /**
     * The internal account context lookup path, written as a literal on purpose.
     *
     * <p>Assumptions: a literal and not a template, because the identifier no longer appears in the
     * address at all. That is the property this constant exists to pin: a future edit that reintroduced a
     * path variable here would have to change this literal, and the disjointness assertion below would
     * report it.</p>
     */
    private static final String ACCOUNT_LOOKUP_PATH = "/api/v1/accounts/lookup";

    /**
     * The customer presence path, written as a literal on purpose.
     *
     * <p>Refactoring Rationale: this was the template {@code /api/v1/customers/{customerId}}, carrying a
     * {@code HEAD} and a {@code GET} served by one handler. Both collapsed into one {@code POST} at this
     * address with the identifier in a body, for the reason recorded on {@code CustomerLookupRequest}.</p>
     */
    private static final String CUSTOMER_LOOKUP_PATH = "/api/v1/customers/lookup";

    /**
     * The end-user account-view path, written as a literal on purpose.
     *
     * <p>Refactoring Rationale: this was the template {@code /api/v1/accounts/{accountId}/view}. The key
     * moved into a request body carrying the same schema the internal read uses, for the reason recorded on
     * {@code AccountLookupRequest}: a load balancer composes its access record from the request line before
     * any application code runs, so the only place the identifier can be withheld from it is the body.</p>
     */
    private static final String ACCOUNT_VIEW_PATH = "/api/v1/accounts/view";

    /**
     * The customer scan path, written as a literal on purpose.
     *
     * <p>Assumptions: this is the COLLECTION address and is an internal one, unlike the collection address
     * of any other subtree in this context. The application chain denies the customer subtree outright, so
     * an address published here that were not claimed by the internal chain would answer no caller at
     * all.</p>
     */
    private static final String CUSTOMER_SCAN_PATH = "/api/v1/customers";

    /**
     * The keyed customer record read path, written as a literal on purpose.
     *
     * <p>Refactoring Rationale: the {@code {customerId}} segment is gone for the same reason it left the
     * presence check above, and this operation additionally moved onto its own internal scope. Both changes
     * are recorded where they are enforced -- the address on {@code CustomerController.RECORD_PATH}, the
     * scope on {@code InternalServiceToken.SCOPE_CUSTOMER_MASTER_READ}.</p>
     */
    private static final String CUSTOMER_RECORD_PATH = "/api/v1/customers/record";

    /**
     * The dispatcher path of the body-bearing customer display lookup.
     *
     * <p>Assumptions: the address is written out rather than composed from the controller's constants,
     * exactly as its three siblings above are, so that a renamed constant is caught by a failing
     * assertion here rather than silently agreeing with itself.</p>
     */
    private static final String CUSTOMER_DISPLAY_PATH = "/api/v1/customers/display";

    /**
     * The end-user by-account cross-reference walk path, written as a literal on purpose.
     *
     * <p>Refactoring Rationale: this was the template
     * {@code /api/v1/accounts/{accountId}/card-cross-references}. The account moved into a request body for
     * the reason above and the address gained {@code /search}, which is how this document spells a bounded
     * {@code POST} read everywhere it has one -- the internal twin of this same walk is at
     * {@link #XREF_SEARCH_BY_ACCOUNT_PATH}. The {@code cursor} and {@code direction} parameters stay in the
     * query string: a sealed cursor is confidential by construction and a direction is one of two published
     * words, so neither is a value the sensitive-data contract prohibits.</p>
     */
    private static final String ACCOUNT_XREF_SEARCH_PATH =
            "/api/v1/accounts/card-cross-references/search";

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
     *
     * <p>Refactoring Rationale: the two lookup request records were added here when the machine reads moved
     * onto bodies. A request record whose schema this contract did not restate is one a caller composes from
     * the document and the server rejects at binding -- the same class of caller-versus-server disagreement
     * the whole move was made to close, so the records are enrolled rather than left to the path assertions
     * alone.</p>
     *
     * <p>Refactoring Rationale: {@code CardXrefByAccountView} joined the list when the account-keyed lookup
     * stopped sharing the card-keyed response shape. The shape it left behind withholds the card number, and
     * withholding it is exactly what made the account-keyed consumer unable to write a ledger row -- so the
     * record that replaced it is enrolled here, which is what makes its third component and the document's
     * third property fail together if either moves without the other.</p>
     */
    private static final List<Class<?>> DOCUMENTED_RECORDS = List.of(
            AccountContextView.class, CardXrefView.class, CardXrefByAccountView.class,
            CardXrefLookupRequest.class,
            AccountLookupRequest.class, CustomerLookupRequest.class,
            AccountViewResponse.class, AccountViewResponse.AccountDetail.class,
            AccountViewResponse.CustomerDetail.class, AccountUpdateRequest.class,
            AccountUpdateResponse.class, CardXrefResponse.class, CustomerDisplayView.class);

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
     * The target type both member-set reads in this class deserialise into.
     *
     * <p>Refactoring Rationale: both reads formerly passed {@code Map.class}, which yields a RAW
     * {@code Map}. A raw actual erases the assertion library's own type parameters, so the key matcher called
     * on it becomes an unchecked call: the compiler stops checking the very key arguments that ARE the
     * assertion. These two cases exist to pin a published member set exactly, so an assertion the compiler
     * declines to check is the wrong instrument for them. Naming the parameterisation once restores the check
     * and removes three {@code javac -Xlint:unchecked} warnings per site.</p>
     *
     * <p>Assumptions: the value type is {@code Object} rather than {@code String} even though every
     * member asserted here renders as a JSON string. The monetary members are strings by rule T3 and the
     * identifiers are numbers, so a {@code String} value type would fail to bind the account-context body
     * that the sibling case reads -- the binding would fail for a reason that has nothing to do with the
     * member set under test.</p>
     */
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };

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
     * <p>Assumptions: the cursor sealer is a REAL instance over fixed key material rather than a
     * substitute, matching how every sibling suite in this reactor obtains one. The type is final, so a
     * substitute would depend on the inline mock maker to exist at all, and a real sealer over a constant
     * key costs nothing and keeps the constructor satisfiable without that dependency. No case in this
     * class reads a page, so the sealer is never exercised.</p>
     *
     * @param crossReferences the cross-reference repository substitute
     * @param accounts the account repository substitute
     * @param customers the customer repository substitute
     * @return the service under test, never {@code null}
     */
    private AccountViewService reads(CardXrefRepository crossReferences,
            AccountRepository accounts,
            CustomerRepository customers) {
        // WHY : Assumptions: no case in this class walks a page -- every one asserts the MACHINE contract
        //   or a single keyed read, neither of which seals a boundary token -- so the sealer is present
        //   only to satisfy the constructor. It is a REAL instance over fixed key material rather than a
        //   substitute because the type is final, so a substitute would depend on the inline mock maker
        //   existing at all, and a real sealer over a constant key costs nothing.
        byte[] keyMaterial = new byte[CursorToken.MIN_KEY_LENGTH];
        Arrays.fill(keyMaterial, (byte) 0x2B);
        return new AccountViewService(accounts, customers, crossReferences, this.mapper,
                mock(AccountMapper.class), mock(CustomerMapper.class), mock(CardXrefMapper.class),
                new CursorToken(keyMaterial, Duration.ofMinutes(5)));
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
     * Verifies the addresses this context MOUNTS for the neighbouring context are the ones its client is
     * pinned to.
     *
     * <p>Assumptions: the subject is the path set the pending-authorization context's
     * {@code RestAccountContextClient} assembles, not the internal surface as a whole -- that surface is
     * wider, because the customer scan and the customer record read are matched on the internal chain for a
     * reason unrelated to this consumer. Naming the consumer keeps the sentence true the next time an
     * internal route lands.</p>
     *
     * <p>Assumptions: the assertion is a composition of the CONTROLLER constants against literals declared
     * in this class, so it fails when either side moves alone. The consuming context holds its own copy of
     * each literal, which is what this comparison stands in for: the two services cannot import one
     * another, so a shared address is agreed by two pinned literals and a build that compares them.</p>
     */
    @Test
    @DisplayName("the addresses mounted for the consumer are the ones it is pinned to")
    void theConsumerBuiltPathsArePinned() {
        // WHY : Refactoring Rationale: two of the three pins moved from a path TEMPLATE to a fixed
        //   /lookup segment, matching the addresses the consumer's own constants declare. They are
        //   asserted from the controller constants rather than from the literals alone, so a segment
        //   renamed on the server side fails here instead of at run time as a 404 the consumer reports
        //   as a dependency failure.
        assertThat(CardXrefController.BASE_PATH + CardXrefController.LOOKUP_PATH)
                .isEqualTo(XREF_LOOKUP_PATH);
        assertThat(AccountController.BASE_PATH + AccountController.LOOKUP_PATH)
                .isEqualTo(ACCOUNT_LOOKUP_PATH);
        assertThat(CustomerController.BASE_PATH + CustomerController.LOOKUP_PATH)
                .isEqualTo(CUSTOMER_LOOKUP_PATH);
    }

    /**
     * Verifies the published contract declares exactly the paths this context serves, with exactly the
     * operations it serves at each.
     *
     * <p>Assumptions: the document is read from the classpath rather than from a source path, so what is
     * asserted is the copy that is packaged and served rather than a file that merely exists in the tree.</p>
     *
     * <p>Refactoring Rationale: this case asserted a closed set of THREE paths and three operations, and it
     * passed while three further routes were mounted and served requests -- the human account view, the
     * account update and the by-account cross-reference listing. A closed-set assertion over the DOCUMENT
     * can only ever detect a contract entry that should not be there; it cannot see a handler the contract
     * omits, which is what the mounted-versus-published case below exists for. Both are kept because they
     * fail on different mistakes. The set is widened as routes are added rather than left to drift, which
     * is what makes it an assertion instead of a snapshot.</p>
     *
     * <p>Assumptions: the two customer-record reads are named individually, because each is a separate
     * address with one operation. The scan sits at the collection address and the keyed read at a segment
     * below it, for the reason recorded on {@code CustomerController.RECORD_PATH}.</p>
     *
     * <p>Refactoring Rationale: the keyed customer template is GONE from the expected set and two fixed
     * lookup addresses are in it. The machine account read and the customer presence check both moved onto
     * bodies, so the keyed customer template is published by nothing and the keyed account template carries
     * the end-user update alone. Asserting the set as CLOSED is what makes the withdrawal assertable: a
     * stale entry left behind for a route no handler serves fails here.</p>
     *
     * @throws Exception if the packaged document is absent or unreadable, which is itself the defect
     */
    @Test
    @DisplayName("the contract declares exactly the served paths and their operations")
    void theContractDocumentDeclaresEveryServedOperation() throws Exception {
        Map<String, Object> paths = contractPaths();

        // WHY : ⚠️ Refactoring Rationale: the closed set carries ELEVEN addresses and the display read is
        //   the eleventh. It is not a widening of this context's surface but the operation a screen in
        //   another context was already relying on and could not name: the pending-authorization detail
        //   screen renders a cardholder's name and address, and with no read for those fields it rendered
        //   them from an existence check that answers no body at all. Leaving it out of this assertion
        //   would have let the address exist while the one test that polices the document's completeness
        //   passed, which is the exact failure mode a closed-set check exists to prevent.
        assertThat(paths).containsOnlyKeys(XREF_LOOKUP_PATH, XREF_LOOKUP_BY_ACCOUNT_PATH,
                XREF_SEARCH_BY_ACCOUNT_PATH, ACCOUNT_LOOKUP_PATH, ACCOUNT_UPDATE_PATH,
                ACCOUNT_VIEW_PATH, ACCOUNT_XREF_SEARCH_PATH, CUSTOMER_LOOKUP_PATH,
                CUSTOMER_SCAN_PATH, CUSTOMER_RECORD_PATH, CUSTOMER_DISPLAY_PATH);
        assertThat(operation(paths, XREF_LOOKUP_PATH)).containsOnlyKeys("post");

        // WHY : Assumptions: all three cross-reference addresses declare a post and nothing else, and the
        //   verb is the same on each for one reason rather than three. Each keys on a value the migration's
        //   logging contract withholds from a durable diagnostic -- a primary account number on the first,
        //   an account identifier on the other two -- and a request line is composed into an access record
        //   before any application code runs, whereas a body is not. Declaring a get on any of them would
        //   publish the shape that puts the key back in the target.
        assertThat(operation(paths, XREF_LOOKUP_BY_ACCOUNT_PATH)).containsOnlyKeys("post");
        assertThat(operation(paths, XREF_SEARCH_BY_ACCOUNT_PATH)).containsOnlyKeys("post");

        // WHY : Refactoring Rationale: every operation on the account prefix declares a post and nothing
        //   else, and the four are the same verb for one reason rather than four. Each takes an account
        //   identifier, a request line is composed into the load balancer's access record before any
        //   application code runs, and a body is not -- so a get or a put on any of these would publish
        //   the shape that puts the key back in a target. The machine read moved first and the three
        //   end-user operations followed; what a reader should NOT conclude is that the two surfaces are
        //   now separated by verb, because they are not separated by verb at all. They are separated by
        //   filter CHAIN, which SecurityConfig.ACCOUNT_PATH_PATTERN records in full, and by sub-path,
        //   which is what makes each address claimable by exactly one of the two chains.
        assertThat(operation(paths, ACCOUNT_LOOKUP_PATH)).containsOnlyKeys("post");
        assertThat(operation(paths, ACCOUNT_UPDATE_PATH)).containsOnlyKeys("post");
        assertThat(operation(paths, ACCOUNT_VIEW_PATH)).containsOnlyKeys("post");
        assertThat(operation(paths, ACCOUNT_XREF_SEARCH_PATH)).containsOnlyKeys("post");

        // WHY : Refactoring Rationale: the presence check declares ONE method where the document
        //   previously declared a head and a get at a keyed address. Both were served by one handler, so
        //   the pair described one behaviour twice and could not diverge; collapsing them into a single
        //   post removed the duplicate description rather than a capability, and the answer was never in a
        //   body so a caller wanting only presence still transfers none.
        assertThat(operation(paths, CUSTOMER_LOOKUP_PATH)).containsOnlyKeys("post");

        // WHY : Assumptions: the display read declares a post and nothing else, for the reason every other
        //   address keyed by a customer or account identifier does -- the key travels in a body because a
        //   request line reaches the load balancer's access record before any application code runs.
        assertThat(operation(paths, CUSTOMER_DISPLAY_PATH)).containsOnlyKeys("post");

        // WHY : Assumptions: the scan declares a get and the record read a post, and the asymmetry follows
        //   from what each carries. The scan is positioned by an opaque cursor and bounded by a size, so it
        //   holds no identifier and keeps the method its shape implies; the record read carries the
        //   nine-digit key, so it takes a body for the reason its request record states. Neither declares a
        //   HEAD entry: HEAD is implicitly available on every GET in this system, and declaring it on a
        //   read that returns a representation would document an operation no caller has reason to send.
        assertThat(operation(paths, CUSTOMER_SCAN_PATH)).containsOnlyKeys("get");
        assertThat(operation(paths, CUSTOMER_RECORD_PATH)).containsOnlyKeys("post");
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
     * <p>Assumptions: the HEAD normalisation below is retained although no operation currently declares
     * that method. The only published HEAD was the customer probe's, and it collapsed into the presence
     * check's single POST -- so the mapping is a no-op today. It is kept rather than deleted because the
     * property it encodes is a framework behaviour and not a fact about this contract: the framework
     * answers HEAD from a GET mapping by discarding the body, so a published HEAD is served by the GET
     * mapping at the same address and there is no HEAD annotation for the mounted set to find. Deleting the
     * mapping would leave the next declared HEAD failing this case as an unmounted operation, which it
     * would not be. Refactoring Rationale: the alternative -- deleting it and restoring it if needed -- was
     * rejected because the failure it prevents reports the opposite of the truth, and a reviewer would
     * spend the effort on the handler rather than on this case.</p>
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
     * Verifies the internal surface is exactly the addresses the internal chain is built from, and that
     * every operation on it requires the internal credential.
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
    @DisplayName("the internal surface is exactly the addresses the internal chain governs")
    void theInternalSurfaceIsExactlyTheInternalAddresses() throws Exception {
        Map<String, Map<String, Object>> operations = publishedOperationDetail();
        Map<String, Map<String, Object>> internal = surfaceSubset(operations, INTERNAL_TAG);
        Map<String, Map<String, Object>> endUser = surfaceSubset(operations, END_USER_TAG);

        // WHY : Assumptions: both customer READS belong on this surface and not the end-user one, which is
        //   settled by the application chain rather than by preference: SecurityConfig denies the whole
        //   customer subtree there, so an operation tagged end-user inside it would be refused to every
        //   caller. Their reference, app/cbl/CBCUS01C.cbl, carries no EXEC CICS verb and appears in no
        //   resource definition, so there is no screen behind either of them to grant to a business group.
        assertThat(internal.keySet().stream().map(AccountContextContractTest::pathOf).distinct())
                .as("the internal surface must be exactly the addresses the internal chain is composed"
                        + " from")
                .containsExactlyInAnyOrder(XREF_LOOKUP_PATH, XREF_LOOKUP_BY_ACCOUNT_PATH,
                        XREF_SEARCH_BY_ACCOUNT_PATH, ACCOUNT_LOOKUP_PATH,
                        CUSTOMER_LOOKUP_PATH, CUSTOMER_SCAN_PATH, CUSTOMER_RECORD_PATH,
                        CUSTOMER_DISPLAY_PATH);
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
     * Verifies the CARD-keyed cross-reference response carries exactly the two identifiers.
     *
     * <p>Assumptions: the absence of the card number is asserted as well as the presence of the two
     * identifiers. The consumer supplied the card, so echoing it back would add nothing and would place a
     * primary account number in a second response body, a second access log and a second client's memory.</p>
     *
     * <p>Assumptions: this case is now explicitly about the CARD-keyed operation, and its account-keyed
     * counterpart below asserts the opposite. The pair is what stops either shape drifting into the other:
     * this operation must not gain the card number and that one must not lose it, and a single case covering
     * "the cross-reference response" could only ever have pinned one of the two.</p>
     */
    @Test
    @DisplayName("the card-keyed cross-reference response carries the two identifiers and no card number")
    void theCardKeyedCrossReferenceResponseCarriesNoCardNumber() {
        CardXrefRepository crossReferences = mock(CardXrefRepository.class);
        when(crossReferences.findByCardNum(CARD_NUMBER))
                .thenReturn(Optional.of(new CardXref(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID)));

        CardXrefView view = new CardXrefController(
                reads(crossReferences, mock(AccountRepository.class), mock(CustomerRepository.class)))
                .lookup(new CardXrefLookupRequest(CARD_NUMBER));

        assertThat(view.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(view.customerId()).isEqualTo(CUSTOMER_ID);

        String body = this.json.writeValueAsString(view);
        assertThat(this.json.readValue(body, JSON_OBJECT)).containsOnlyKeys("accountId", "customerId");
        assertThat(body).doesNotContain(CARD_NUMBER).doesNotContain(CARD_NUMBER.substring(0, 6));
    }

    /**
     * Verifies the ACCOUNT-keyed cross-reference response carries the selected card number in full.
     *
     * <p>Refactoring Rationale: this operation answered with the two-identifier shape above, which withholds
     * the card number, and the consumer's own seam record declared a {@code cardNumber} member that no
     * response could populate. It is not a cosmetic gap: the consumer transcribes
     * {@code READ-CXACAIX-FILE} at lines 576 to 604 of {@code app/cbl/COTRN02C.cbl} and the same read at line
     * 414 of {@code app/cbl/COBIL00C.cbl}, both of which take {@code XREF-CARD-NUM} from the record and write
     * the row they produce UNDER it, so a transaction and a bill payment could not be written at all.</p>
     *
     * <p>Assumptions: the value is asserted present, whole and unmasked, and the three are asserted together
     * because a masked value would satisfy presence while being a DIFFERENT key -- the consumer writes it into
     * a ledger row, so masking it would write the row against a card that does not exist. A case that
     * asserted only that the property existed would pass on exactly that failure.</p>
     */
    @Test
    @DisplayName("the account-keyed cross-reference response carries the selected card number in full")
    void theAccountKeyedCrossReferenceResponseCarriesTheCardNumber() {
        CardXrefRepository crossReferences = mock(CardXrefRepository.class);
        when(crossReferences.findFirstByAccountIdOrderByCardNumAsc(ACCOUNT_ID))
                .thenReturn(Optional.of(new CardXref(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID)));

        CardXrefByAccountView view = new CardXrefController(
                reads(crossReferences, mock(AccountRepository.class), mock(CustomerRepository.class)))
                .lookupByAccount(new AccountLookupRequest(ACCOUNT_ID));

        assertThat(view.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(view.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(view.cardNumber())
                .as("the consumer writes its ledger row under this value, so a masked form is a wrong key")
                .isEqualTo(CARD_NUMBER);

        String body = this.json.writeValueAsString(view);
        assertThat(this.json.readValue(body, Map.class))
                .containsOnlyKeys("accountId", "customerId", "cardNumber");
        assertThat(body)
                .as("the whole sixteen digits must survive serialisation, unmasked and unabbreviated")
                .contains(CARD_NUMBER);
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
                .lookup(new AccountLookupRequest(ACCOUNT_ID));

        String body = this.json.writeValueAsString(view);
        assertThat(this.json.readValue(body, JSON_OBJECT))
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
                .lookup(new AccountLookupRequest(ACCOUNT_ID)));

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
     * Verifies an absent account raises the type the shared advice renders as 404, naming no identifier.
     *
     * <p>Refactoring Rationale: this case previously asserted the message CONTAINED the account
     * identifier, so it did not merely tolerate a disclosure -- it required one, and a fix removing the
     * identifier would have been reported as a regression. The sensitive-data contract in
     * {@code docs/architecture/observability.md} names account and customer identifiers alongside the
     * primary account number as values a durable diagnostic may not carry, and requires a prohibited
     * value to be OMITTED rather than abbreviated. This message is durable: the shared advice writes it
     * to the operational record and returns it in a response body. The assertion is therefore inverted
     * to absence, which is the direction that keeps the disclosure from returning.</p>
     *
     * <p>Assumptions: the exact remaining text is pinned as well as the absence, because an absence
     * assertion alone would also pass against a message emptied to nothing and an operator reading the
     * record still needs to know WHICH refusal occurred. The correlation identifier the shared filter
     * stamps carries the join back to the calling context, which is where the identifier legitimately
     * lives.</p>
     *
     * <p>Assumptions: the premise the old comment rested on -- that the identifier "already appears in
     * the request path" -- is not merely unsupported now, it is false: this operation is reached by a
     * {@code POST} carrying its key in a body, so the identifier appears in no request line and no
     * access log, and the message would have been the ONLY place it landed.</p>
     */
    @Test
    @DisplayName("an absent account raises a not-found that names no account identifier")
    void anAbsentAccountRaisesNotFound() {
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.empty());
        AccountController controller = controllerOver(
                reads(mock(CardXrefRepository.class), accounts, mock(CustomerRepository.class)));

        assertThatThrownBy(() -> controller.lookup(new AccountLookupRequest(ACCOUNT_ID)))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageNotContaining(String.valueOf(ACCOUNT_ID))
                .hasMessage("no account master row exists for the requested account");
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

        ResponseEntity<Void> present = controller.lookup(new CustomerLookupRequest(CUSTOMER_ID));
        ResponseEntity<Void> absent = controller.lookup(new CustomerLookupRequest(CUSTOMER_ID + 1));

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
     * Reads the PACKAGED contract document.
     *
     * <p>Assumptions: it is read from the classpath and not from a source path, so what every case here
     * asserts is the copy that is packaged and served rather than a file that merely exists in the tree.</p>
     *
     * @return the whole document as a mapping, never {@code null}
     * @throws Exception if the document is absent from the classpath or is not readable as a mapping, either
     *     of which would mean the packaged contract is not the one under test
     */
    private Map<String, Object> contractDocument() throws Exception {
        try (InputStream document =
                     getClass().getResourceAsStream("/openapi/account-api.yaml")) {
            assertThat(document).as("/openapi/account-api.yaml must be on the classpath").isNotNull();
            Map<String, Object> root = new Yaml().load(document);
            assertThat(root).containsKeys("paths", "components");
            return root;
        }
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
        return (Map<String, Object>) contractDocument().get("paths");
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
     * Verifies that no published path template and no declared path or query parameter can carry an account
     * or a customer identifier.
     *
     * <p>Purpose: this is the standing guarantee behind a statement made outside this module.
     * {@code infra/modules/alb/main.tf} enables access logging unconditionally -- the policy scan gates it
     * at HIGH severity -- and an ELB access record has no field allow-list: the request line is always
     * written, in full, composed by the load balancer itself before any application code runs. Neither
     * {@code LogSafeText}, nor {@code CardNumberMasker}, nor {@code GlobalExceptionHandler} can reach it.
     * The only available control is therefore that the value never enters a target, which is a property of
     * this contract and not of any logging configuration, and that property is what this case pins.</p>
     *
     * <p>Assumptions: the test is STRUCTURAL -- it asks whether a target with a place to put an identifier
     * exists -- rather than behavioural. A behavioural version would build a concrete URL and require a
     * masker to redact it, which is the same category error that resource's own note records being made
     * twice: masking bounds what a service writes and cannot bound what the load balancer already
     * wrote.</p>
     *
     * <p>Assumptions: the prohibited names are the ones the migration's sensitive-data contract enumerates
     * for this context -- an account identifier and a customer identifier -- and the sweep is over both
     * path templates and declared parameters, because a query string is part of the request line exactly
     * as a path segment is. Trade-offs: a cursor and a direction remain declared query parameters and are
     * deliberately not caught, because a sealed cursor is confidential by construction and a direction is
     * one of two published words, so neither is a value the contract prohibits.</p>
     *
     * @throws Exception if the packaged document is absent or unreadable, which is itself the defect
     */
    @Test
    @DisplayName("no path template and no declared parameter can carry an account or customer identifier")
    void noRequestLineCanCarryAnAccountOrCustomerIdentifier() throws Exception {
        List<String> prohibited = List.of("accountid", "acctid", "customerid", "custid", "cardnumber");
        List<String> offending = new ArrayList<>();

        for (String template : contractPaths().keySet()) {
            String lowered = template.toLowerCase(Locale.ROOT);
            if (prohibited.stream().anyMatch(lowered::contains)) {
                offending.add("path " + template);
            }
        }
        declaredParameters().forEach((name, declared) -> {
            String in = String.valueOf(declared.get("in"));
            if (!"path".equals(in) && !"query".equals(in)) {
                return;
            }
            String parameter = String.valueOf(declared.get("name")).toLowerCase(Locale.ROOT);
            if (prohibited.contains(parameter)) {
                offending.add(in + " parameter " + name);
            }
        });

        assertThat(offending)
                .as("a path segment and a query string are both persisted verbatim by the load balancer's"
                        + " mandatory access log, which no downstream masking can redact")
                .isEmpty();

        // WHY : Assumptions: the sweep is proved capable of failing, because a sweep over an empty
        //       collection is indistinguishable from a sweep that finds nothing. Both halves are exercised
        //       against a fabricated input: a template naming the identifier is caught, and one naming the
        //       walk's own address is not.
        assertThat(prohibited.stream()
                        .anyMatch("/api/v1/accounts/{accountid}"::contains))
                .as("the sweep must be able to recognise an identifier-bearing template")
                .isTrue();
        assertThat(prohibited.stream()
                        .anyMatch(ACCOUNT_XREF_SEARCH_PATH::contains))
                .as("the sweep must not report an address that carries no identifier")
                .isFalse();
    }

    /**
     * Reads every parameter component the packaged contract declares.
     *
     * <p>Assumptions: the COMPONENTS are read rather than the inline parameter lists, and the two are
     * different populations. This document declares its reusable parameters as components and its two
     * one-off query parameters inline, so the sweep above walks the components for a declared identifier
     * and the path templates for an embedded one; an inline parameter naming an identifier would be a
     * third shape, and the operations that could declare one all take their selector in a body.</p>
     *
     * @return each parameter component keyed by its component name, never {@code null}
     * @throws Exception if the packaged document is absent or unreadable
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> declaredParameters() throws Exception {
        Map<String, Object> components =
                (Map<String, Object>) contractDocument().get("components");
        Object parameters = components.get("parameters");
        if (parameters == null) {
            return Map.of();
        }
        Map<String, Map<String, Object>> declared = new LinkedHashMap<>();
        ((Map<String, Object>) parameters).forEach((name, value) ->
                declared.put(name, (Map<String, Object>) value));
        return declared;
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
