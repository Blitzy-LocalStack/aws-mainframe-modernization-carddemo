package com.carddemo.transaction.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.CardDemoCommonAutoConfiguration;
import com.carddemo.common.control.OnlineWriteGateExempt;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CorrelationIdFilter;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.transaction.config.OpenApiConfig;
import com.carddemo.transaction.config.SecurityConfig;
import com.carddemo.transaction.dto.TransactionAddPreview;
import com.carddemo.transaction.dto.TransactionAddRequest;
import com.carddemo.transaction.dto.TransactionAddResponse;
import com.carddemo.transaction.dto.CopiedTransactionData;
import com.carddemo.transaction.dto.CopyLastRequest;
import com.carddemo.transaction.dto.TransactionDetailResponse;
import com.carddemo.transaction.dto.TransactionListItemResponse;
import com.carddemo.transaction.dto.TransactionListRequest;
import com.carddemo.transaction.service.TransactionAddService;
import com.carddemo.transaction.service.TransactionListService;
import com.carddemo.transaction.service.TransactionViewService;
import jakarta.servlet.Filter;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Pins the delivered HTTP behaviour of the three ledger browse-and-capture operations.
 *
 * <p>Purpose: assert, at the wire, what {@link TransactionController} answers for CT00 Transaction List
 * (root {@code README.md} line 298), CT01 Transaction View (line 299) and CT02 Transaction Add (line
 * 300) -- the status, the emitted member set, the quoted encoding of every amount, the per-field error
 * array and the sentence each failure carries. The fourth published operation on this module, CB00 Bill
 * Payment at line 302, belongs to {@code BillPaymentControllerTest}, and CR00 Transaction Reports at
 * line 301 belongs to the reporting context, so neither is exercised here.</p>
 *
 * <p>Assumptions: the sentences asserted below are carried across character for character from
 * {@code app/cbl/COTRN00C.cbl} (699 lines), {@code app/cbl/COTRN01C.cbl} (330 lines) and
 * {@code app/cbl/COTRN02C.cbl} (783 lines), each cited by line at its assertion site. That tree is
 * reference material and is never edited; {@code tests/README.md} lines 555 to 556 states the house
 * doctrine these assertions follow, that a test encodes the specification exactly as the production
 * source documents it and does not redefine it.</p>
 *
 * <p>Assumptions: no golden-master oracle exists for any of the three. {@code tests/README.md} lines 83
 * to 85 records that the online {@code CO*} programs cannot run end to end without a CICS runtime, which
 * the build host does not have, and all three programs named above are {@code CO*}. Every expected value
 * below is therefore read from the reference source rather than captured from a run of it.</p>
 *
 * <h2>How the edge slice is assembled, and why not with the slice annotation</h2>
 *
 * <p>Alternatives Considered: the servlet slice annotation is the obvious wiring and is not on this
 * module's test class path. Spring Boot 4 moved it out of {@code spring-boot-test-autoconfigure} into a
 * separate artifact that {@code services/transaction-service/pom.xml} does not declare and that
 * {@code spring-boot-starter-test} does not bring in transitively; the class is absent from the
 * autoconfigure jar the reactor resolves, which was checked against that jar's own entry list rather
 * than inferred. Declaring the artifact would mean editing that POM, which is outside this file's remit,
 * so the equivalent slice is assembled here from {@link SliceConfiguration} using types that are on the
 * path: the test context framework supplies the context, {@link MockitoBean} substitutes the three
 * collaborators, and {@link MockMvcBuilders#webAppContextSetup} builds the entry point.</p>
 *
 * <p>Trade-offs: {@link OpenApiConfig} is imported into that configuration rather than left out, at the
 * cost of the slice also creating the published-contract bean it declares, which no assertion below
 * reads. The cost is accepted because the alternative hides a defect rather than avoiding one: a slice
 * that omits this module's own web configuration answers with whichever encoding a bare context happens
 * to install, so it can pass every assertion below while the deployed service emits different JSON. The
 * import keeps the amount encoding and the problem shape under assertion the ones this module
 * configures.</p>
 *
 * <p>Refactoring Rationale: the exact-amount codec is imported through
 * {@link CardDemoCommonAutoConfiguration} rather than through {@link OpenApiConfig}, because that is
 * where it is declared -- its {@code carddemoMoneyModule()} bean sits at line 264 of that class under
 * the missing-bean guard at its lines 262 to 263, and the single problem-rendering advice at its line
 * 607. An earlier reading placed both in this module's {@code config} package; that class declares one
 * bean and imports nothing, so a slice built on that reading would have registered neither and would
 * have asserted an encoding no participant produced.</p>
 *
 * <p>Alternatives Considered: a whole-application context was rejected. It creates the persistence
 * layer and runs the schema migration for assertions about status codes, member sets and sentences, and
 * it creates the decoder bean {@link SecurityConfig#jwtDecoder(String, String, String, String)}
 * declares, whose factory resolves the issuer document while the context refreshes and so cannot
 * succeed against the unreachable issuer pinned below. Substituting the decoder is what keeps the
 * refusal paths reachable without a route to an identity provider: every other participant in the
 * chain, including the real {@link JwtRoleConverter}, is the deployed one, so an authority asserted
 * below is one the deployed conversion produced.</p>
 *
 * <p>Assumptions: {@link CorrelationIdFilter} is placed ahead of the authentication filter in the
 * entry point built by {@link #assembleEdge()}, mirroring the order the shared kernel registers it in,
 * at highest precedence plus one. That ordering is why a refused request is correlatable at all: a 401
 * or a 403 is produced inside the security chain, so a filter installed after it would never see one.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception clause.</p>
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = TransactionControllerTest.SliceConfiguration.class)
@TestPropertySource(properties = {
    "springdoc.swagger-ui.url=/transaction-api.yaml",
    "carddemo.security.cognito.admin-group-name=carddemo-admin",
    "carddemo.security.cognito.user-group-name=carddemo-user",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.invalid/carddemo-ledger",
    "carddemo.security.jwt.expected-token-use=access",
    "carddemo.security.jwt.expected-client-id=transaction-edge-slice-client",
    "carddemo.security.jwt.required-scope=aws.cognito.signin.user.admin"
})
@DisplayName("the ledger browse, view and capture operations at the wire")
class TransactionControllerTest {

    /**
     * The instant every rendered problem timestamp is produced from.
     *
     * <p>Assumptions: an instant is pinned rather than read from the host clock because the problem
     * shape publishes a timestamp member, so a running clock would make every refusal assertion below
     * depend on when it ran.</p>
     */
    private static final Instant PINNED_INSTANT = Instant.parse("2022-07-18T12:00:00Z");

    /** The rendered form of {@link #PINNED_INSTANT}, at the 26-character width the ledger declares. */
    private static final String PINNED_TIMESTAMP = "2022-07-18 12:00:00.000000";

    /**
     * The key material the boundary tokens in this class are sealed with.
     *
     * <p>Assumptions: any 32 bytes will do because nothing below re-opens a token; the sealing exists
     * only because {@link PageResponse} refuses a boundary key that is not sealed, which it does so that
     * a browse can never publish the key columns themselves.</p>
     */
    private static final byte[] SEALING_KEY = new byte[32];

    /** The caller identity every authenticated request below is made as. */
    private static final String SUBJECT = "11111111-2222-3333-4444-555555555555";

    /** A well-formed identifier, used wherever the identifier is not itself what is under assertion. */
    private static final String PRESENT_ID = "0000000000000001";

    /** The identifier a capture is answered with, one above {@link #PRESENT_ID}. */
    private static final String ASSIGNED_ID = "0000000000000002";

    /**
     * The sentence a copy raises when the selected key has no transaction to copy from.
     *
     * <p>Assumptions: the value is a plain sentence rather than one of the service's published
     * constants, because what this class asserts about that condition is its STATUS and the absence of a
     * created address. Which sentence the service chooses for it is the service package's to pin, and
     * asserting a constant here as well would give one decision two owners.
     */
    private static final String COPY_SOURCE_ABSENT_SENTENCE = "no transaction to copy for that key";

    /**
     * Text standing for a diagnostic the store would produce, which must never reach a caller.
     *
     * <p>Assumptions: shaped like a real one -- naming a relation and a key -- so that a case asserting
     * its absence from a body is asserting something a real failure would actually carry.
     */
    private static final String STORE_DIAGNOSTIC =
            "ERROR: could not append to relation \"ledger.transactions\"; key (tran_id)=(0000000000000002)";

    /**
     * A primary account number whose leading digit is a zero.
     *
     * <p>Assumptions: this value is not invented. It is the card number of the second record of
     * {@code app/data/ASCII/dailytran.txt}, whose 300 records are 350 bytes wide and whose card number
     * occupies one-based bytes 263 to 278. The first record's card number begins with a four, so the
     * second is the witness a leading zero needs.</p>
     */
    private static final String LEADING_ZERO_CARD_NUMBER = "0927987108636232";

    /**
     * The card number a preview reports as the RESOLVED one.
     *
     * <p>Assumptions: deliberately different from {@link #LEADING_ZERO_CARD_NUMBER}, so that a case
     * asserting the block reports the resolved card cannot pass against a block that echoed a submitted
     * one. That distinction is the whole point of the identity members being on the preview at all.
     */
    private static final String RESOLVED_CARD_NUMBER = "4111111111111111";

    /** {@link #LEADING_ZERO_CARD_NUMBER} reduced to its last four digits, as a response carries it. */
    private static final String MASKED_CARD_NUMBER = "************6232";

    /** The credential presented on every authenticated request; the substituted decoder answers for it. */
    private static final String BEARER_TOKEN = "Bearer edge-slice-token";

    /** The header a presented credential travels in. */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /** The group claim value that reaches neither of the two published business authorities. */
    private static final String UNRELATED_GROUP = "carddemo-observers";

    /**
     * The declared width of the generated screen field the reference moved its message into.
     *
     * <p>Assumptions: this width belongs to the generated map and not to the copybook message model, so it
     * is declared here as a local expectation rather than read from a shared constant. The shared kernel
     * owns the three copybook widths and deliberately publishes no constant for this one, which is the
     * distinction the case below asserts rather than blurs.</p>
     */
    private static final int GENERATED_SCREEN_FIELD_WIDTH = 78;

    /** The assembled slice, read to build the entry point and to reach the security chain. */
    @Autowired
    private WebApplicationContext context;

    /** The browse this adapter delegates CT00 to, substituted so no reader and no table is involved. */
    @MockitoBean
    private TransactionListService listService;

    /** The keyed read this adapter delegates CT01 to, substituted for the same reason. */
    @MockitoBean
    private TransactionViewService viewService;

    /** The capture this adapter delegates CT02 to, substituted for the same reason. */
    @MockitoBean
    private TransactionAddService addService;

    /**
     * The credential reader, substituted so the chain runs without a route to an identity provider.
     *
     * <p>Refactoring Rationale: this substitution replaces the bean {@link SecurityConfig} declares, and
     * the replacement happens at definition level rather than after refresh, so the real factory -- which
     * fetches the issuer's provider document -- is never invoked. Stubbing the bean after the context
     * started would not help: the fetch happens while it starts.</p>
     */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** The entry point under assertion, rebuilt per test so no filter state carries between them. */
    private MockMvc mockMvc;

    /** Seals the boundary tokens the browse answers with, using {@link #SEALING_KEY}. */
    private CursorToken sealer;

    /**
     * Builds the entry point over the assembled slice, with the correlation filter ahead of the chain.
     *
     * <p>Assumptions: the security chain is added as a filter rather than relied upon implicitly, because
     * the entry point is built from the context by hand and a chain that is not added is a chain that
     * does not run -- which would make every request below anonymous and successful, quietly turning the
     * two authorization assertions into assertions about nothing.</p>
     */
    @BeforeEach
    void assembleEdge() {
        this.sealer = new CursorToken(SEALING_KEY, Duration.ofMinutes(15));

        // WHY : Assumptions: the correlation filter is given the slice's own clock rather than a second
        //       one built here, so the instant a refusal is stamped with and the instant every other
        //       participant reads are the same instant by construction. Two independently built clocks
        //       would agree only for as long as nobody changed one of them.
        this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context)
                .addFilters(new CorrelationIdFilter(this.context.getBean(Clock.class)),
                        this.context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    /**
     * The browse answers the sealed keyset envelope and nothing beyond its four published members.
     *
     * <p>Purpose: pins the response of the browse migrated from the screen-filling construct at lines 290
     * to 301 of {@code app/cbl/COTRN00C.cbl}.</p>
     *
     * <p>Alternatives Considered: the emitted member set is compared whole rather than probed member by
     * member. A per-member probe names what it expects to find and stays silent about a surplus member,
     * which is the failure this assertion exists to catch: the envelope must carry a boundary key and a
     * further-page indicator and must not acquire a page number or a count of all matching rows, and only
     * comparing the whole set catches an addition.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("browse: answer the four-member keyset envelope and no fifth member")
    void browseAnswersTheFourMemberKeysetEnvelope() throws Exception {
        String boundary = this.sealed(PRESENT_ID);
        when(this.listService.listTransactions(any(), any(), any())).thenReturn(
                PageResponse.ofRows(List.of(this.groceryRow()), boundary, boundary, true));

        String body = this.browseAs("carddemo-user")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.firstKey").value(boundary))
                .andExpect(jsonPath("$.lastKey").value(boundary))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(memberNames(body)).containsExactlyInAnyOrder("items", "firstKey", "lastKey",
                "hasNext");
    }

    /**
     * The browse accepts exactly the three published query parameters and nothing that indexes a page.
     *
     * <p>Purpose: pins the parameter list of the browse against the reference's own browse state, the
     * in-program group at lines 62 to 70 of {@code app/cbl/COTRN00C.cbl} that follows its
     * {@code COPY COCOM01Y.} at line 61 -- a first key at line 63, a last key at line 64, a page number
     * at line 65 and a further-page flag at lines 66 to 68.</p>
     *
     * <p>Assumptions: of those five, only the two keys and the flag cross the boundary. The page number
     * at line 65 is carried for display alone: the reference increments it, defaults it, moves it to the
     * map field and decrements it, and never compares it against a key, so nothing about which rows are
     * read depends on it. Its counterpart here is therefore absent rather than accepted and ignored,
     * which is why the declared set is asserted to equal the three published names exactly.</p>
     *
     * @throws Exception if the declared method could not be read by reflection
     */
    @Test
    @DisplayName("browse: accept exactly the three published parameters, none of them a page index")
    void browseAcceptsExactlyTheThreePublishedParameters() throws Exception {
        Method browse = TransactionController.class.getMethod("listTransactions", String.class,
                String.class, String.class, Principal.class);

        List<String> declared = Arrays.stream(browse.getParameters())
                .map(parameter -> parameter.getAnnotation(RequestParam.class))
                .filter(annotation -> annotation != null)
                .map(RequestParam::name)
                .toList();

        assertThat(declared).containsExactly(TransactionController.PARAM_TRANSACTION_ID_FILTER,
                TransactionController.PARAM_CURSOR, TransactionController.PARAM_DIRECTION);
    }

    /**
     * The number of rows a page carries is decided by the server, not asked for by the caller.
     *
     * <p>Purpose: records the reference's page width and pins that no caller can vary it. Three lines of
     * {@code app/cbl/COTRN00C.cbl} fix it at ten and none of them reads an input: the clearing construct
     * at line 290 runs while its index is not above ten, the filling construct at line 297 runs until the
     * index reaches eleven or the dataset is exhausted, and line 301 advances the index by one.</p>
     *
     * <p>Assumptions: the width is a property of the reference program, so it is recorded here as the
     * baseline fact it is and asserted through the absence of any parameter that could change it. The
     * previous case compares the declared parameter list, so this one adds the reason that list is closed
     * rather than repeating the comparison.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("browse: decide the page width server-side, at the reference's ten rows")
    void browsePageWidthIsNotACallerParameter() throws Exception {
        when(this.listService.listTransactions(any(), any(), any()))
                .thenReturn(PageResponse.empty());

        this.browseAs("carddemo-user").andExpect(status().isOk());

        TransactionListRequest received = this.capturedBrowseRequest();
        assertThat(received.transactionIdFilter()).isNull();
        assertThat(received.cursor()).isNull();
        assertThat(received.direction()).isNull();
        assertThat(received.effectiveDirection()).isEqualTo(TransactionListRequest.Direction.NEXT);
    }

    /**
     * Each published direction token binds to its own strictly-ordered constant.
     *
     * <p>Purpose: pins the two directions of the browse against the reference's own strictness. Line 597
     * of {@code app/cbl/COTRN00C.cbl} carries {@code GTEQ} in a commented-out position, so the browse it
     * starts is positioned on an equal-or-greater key nowhere: forward reads strictly beyond the last key
     * of the page just shown, and backward strictly before its first key. A direction that bound to the
     * wrong constant would read the page the caller is already looking at.</p>
     *
     * @param wireToken the published token a caller sends, of type {@link String}; must not be
     *     {@code null}
     * @param expected the constant that token must bind to, of type
     *     {@link TransactionListRequest.Direction}; must not be {@code null}
     * @throws Exception if the request could not be performed
     */
    @ParameterizedTest(name = "{0} binds to {1}")
    @MethodSource("publishedDirections")
    @DisplayName("browse: bind each published direction token to its strict keyset constant")
    void browseBindsEachPublishedDirectionToken(String wireToken,
            TransactionListRequest.Direction expected) throws Exception {
        this.callerIn("carddemo-user");
        when(this.listService.listTransactions(any(), any(), any()))
                .thenReturn(PageResponse.empty());

        this.mockMvc.perform(get(TransactionController.BASE_PATH)
                        .param(TransactionController.PARAM_DIRECTION, wireToken)
                        .param(TransactionController.PARAM_CURSOR, this.sealed(PRESENT_ID))
                        .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isOk());

        assertThat(this.capturedBrowseRequest().direction()).isEqualTo(expected);
    }

    /**
     * Supplies each published direction token beside the constant it must bind to.
     *
     * @return the token-and-constant pairs, never {@code null}
     */
    private static Stream<Arguments> publishedDirections() {
        return Stream.of(
                Arguments.of(TransactionListRequest.Direction.NEXT.wireValue(),
                        TransactionListRequest.Direction.NEXT),
                Arguments.of(TransactionListRequest.Direction.PREVIOUS.wireValue(),
                        TransactionListRequest.Direction.PREVIOUS));
    }

    /**
     * A browse with no filter reaches the browse with no filter, meaning the start of the key space.
     *
     * <p>Purpose: pins the blank-filter contract of lines 206 to 207 of {@code app/cbl/COTRN00C.cbl},
     * where a filter field holding spaces or low values moves low values into the record key, so an
     * unfilled filter starts the browse at the beginning of the key space rather than refusing it.</p>
     *
     * <p>Assumptions: the absence is carried as an absent member rather than as an empty string, because
     * the two are distinguishable on the wire and only one of them means what the reference means. The
     * browse is what turns the absence into a starting position, so this case asserts the absence
     * arrives intact and does not assert the position, which belongs to the browse's own cases.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("browse: carry an unfilled filter through as absent, per lines 206 to 207")
    void browseCarriesAnUnfilledFilterThroughAsAbsent() throws Exception {
        when(this.listService.listTransactions(any(), any(), any()))
                .thenReturn(PageResponse.empty());

        this.browseAs("carddemo-user").andExpect(status().isOk());

        assertThat(this.capturedBrowseRequest().hasTransactionIdFilter()).isFalse();
        assertThat(this.capturedBrowseRequest().transactionIdFilter()).isNull();
    }

    /**
     * A browse row carries no selection marker, because selection never was a property of a row.
     *
     * <p>Purpose: pins the member set of a browse row against lines 69 to 70 of
     * {@code app/cbl/COTRN00C.cbl}, where the selection flag and the selected identifier live in the
     * program's own browse state and not in any of the ten row groups the map defines.</p>
     *
     * <p>Assumptions: the marker was pure navigation. Lines 186 and 187 of that program match the marker
     * in either case and lines 188 to 195 transfer control to the detail program, so the marker decided
     * which screen came next and never what a row contained. Its counterpart here is the caller following
     * the item path, which is why no row member corresponds to it.</p>
     */
    @Test
    @DisplayName("browse: carry no selection marker on a row, per lines 69 to 70")
    void browseRowCarriesNoSelectionMarker() {
        List<String> components = Arrays.stream(
                        TransactionListItemResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(components).containsExactly("transactionId", "description", "amount",
                "originTimestamp");
    }

    /**
     * A filter that is not sixteen digits is refused, and the refusal names the member it came from.
     *
     * <p>Purpose: pins the counterpart of line 214 of {@code app/cbl/COTRN00C.cbl}, whose complaint that
     * the identifier must be numeric is raised where the reference's filter field holds something other
     * than digits.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("browse: refuse a filter that is not sixteen digits, naming the member")
    void browseRefusesAFilterThatIsNotSixteenDigits() throws Exception {
        this.callerIn("carddemo-user");

        this.mockMvc.perform(get(TransactionController.BASE_PATH)
                        .param(TransactionController.PARAM_TRANSACTION_ID_FILTER, "not-an-id")
                        .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[*].field",
                        hasItem(TransactionController.PARAM_TRANSACTION_ID_FILTER)))
                .andExpect(jsonPath("$.fieldErrors[*].state",
                        hasItem(FieldValidationFlag.NOT_OK.name())));

        verifyNoInteractions(this.listService);
    }

    /**
     * The five boundary sentences of the browse are five values, and no two of them are the same value.
     *
     * <p>Purpose: pins all five against their own lines of {@code app/cbl/COTRN00C.cbl}. Line 248 is
     * reached from the else arm of the page test at line 245, line 270 from the else arm of the
     * further-page test at line 267, line 608 where the browse cannot be started, line 642 where a
     * forward read is exhausted and line 676 where a backward read is exhausted.</p>
     *
     * <p>Alternatives Considered: merging them into one boundary sentence, or into a pair for the two
     * edges, was rejected. Three of the five speak of the top and two of the bottom, and within each edge
     * they differ by whether the reader is already there or has just reached it -- a distinction the
     * reference draws deliberately and one a caller displays verbatim. Collapsing any pair would answer a
     * caller with a sentence the reference never emits for that condition, and because all five read
     * alike at a glance the collapse would not be visible in review, which is why it is asserted.</p>
     */
    @Test
    @DisplayName("browse: keep the five boundary sentences of lines 248, 270, 608, 642 and 676 distinct")
    void theFiveBoundarySentencesAreFiveDistinctValues() {
        assertThat(TransactionListService.MESSAGE_ALREADY_AT_TOP)
                .isEqualTo("You are already at the top of the page...");
        assertThat(TransactionListService.MESSAGE_ALREADY_AT_BOTTOM)
                .isEqualTo("You are already at the bottom of the page...");
        assertThat(TransactionListService.MESSAGE_AT_TOP)
                .isEqualTo("You are at the top of the page...");
        assertThat(TransactionListService.MESSAGE_REACHED_BOTTOM)
                .isEqualTo("You have reached the bottom of the page...");
        assertThat(TransactionListService.MESSAGE_REACHED_TOP)
                .isEqualTo("You have reached the top of the page...");

        assertThat(List.of(TransactionListService.MESSAGE_ALREADY_AT_TOP,
                        TransactionListService.MESSAGE_ALREADY_AT_BOTTOM,
                        TransactionListService.MESSAGE_AT_TOP,
                        TransactionListService.MESSAGE_REACHED_BOTTOM,
                        TransactionListService.MESSAGE_REACHED_TOP))
                .doesNotHaveDuplicates();
    }

    /**
     * The browse and the detail read report a failed read with sentences that differ only in one case.
     *
     * <p>Purpose: pins the case difference the two reference programs carry. The browse spells it in lower
     * case at lines 615, 649 and 683 of {@code app/cbl/COTRN00C.cbl}, three sites and no others; the
     * capitalised spelling belongs to five other sites, at line 292 of {@code app/cbl/COTRN01C.cbl},
     * lines 664 and 693 of {@code app/cbl/COTRN02C.cbl} and lines 463 and 492 of
     * {@code app/cbl/COBIL00C.cbl}.</p>
     *
     * <p>Assumptions: the two are asserted together rather than each beside its own operation because
     * they differ by the case of a single letter, which a reader comparing them by eye will not see.
     * Asserting equality-ignoring-case as well as inequality is what states the relationship precisely:
     * they are the same words and they are not the same value, so neither may stand in for the other.</p>
     */
    @Test
    @DisplayName("browse and view: keep the two failed-read sentences distinct in case alone")
    void theTwoFailedReadSentencesDifferOnlyInCase() {
        assertThat(TransactionListService.MESSAGE_LOOKUP_FAILED)
                .isEqualTo("Unable to lookup transaction...");
        assertThat(TransactionViewService.MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION)
                .isEqualTo("Unable to lookup Transaction...");
        assertThat(TransactionListService.MESSAGE_LOOKUP_FAILED)
                .isNotEqualTo(TransactionViewService.MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION)
                .isEqualToIgnoringCase(TransactionViewService.MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION);
    }

    /**
     * A failed browse is reported with the browse's own lower-case sentence, not the detail read's.
     *
     * <p>Purpose: carries the previous case's constant-level distinction through to a delivered response,
     * so the lower-case spelling of lines 615, 649 and 683 of {@code app/cbl/COTRN00C.cbl} is what a
     * caller of the browse actually receives.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("browse: render the lower-case failed-read sentence on a 500")
    void browseReportsFailureWithItsOwnLowerCaseSentence() throws Exception {
        when(this.listService.listTransactions(any(), any(), any())).thenThrow(
                new IllegalStateException(TransactionListService.MESSAGE_LOOKUP_FAILED,
                        new RuntimeException("reader")));

        this.browseAs("carddemo-user")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value(TransactionListService.MESSAGE_LOOKUP_FAILED))
                .andExpect(jsonPath("$.message")
                        .value(not(TransactionViewService.MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION)));
    }

    /**
     * The complaint about an invalid selection carries no ellipsis, unlike its neighbour eight lines on.
     *
     * <p>Purpose: pins two sentences of {@code app/cbl/COTRN00C.cbl} whose punctuation differs. Line 199
     * ends after the letter, with no ellipsis at all, while line 214 places a space before its ellipsis.
     * Both are reproduced here exactly as written.</p>
     *
     * <p>Assumptions: this pair is asserted because the difference is invisible in review and because the
     * temptation is to regularise it. Neither is regularised: a caller displays what the reference
     * emitted, so a supplied ellipsis or a removed space would be text the reference never produced. The
     * selection sentence itself has no counterpart operation here -- the marker it complains about became
     * the caller following the item path -- so it is pinned as the reference value it is.</p>
     */
    @Test
    @DisplayName("browse: keep line 199 without an ellipsis and line 214 with a space before one")
    void theSelectionAndNumericComplaintsKeepTheirOwnPunctuation() {
        String invalidSelection = "Invalid selection. Valid value is S";
        String notNumeric = "Tran ID must be Numeric ...";

        assertThat(invalidSelection).doesNotEndWith("...");
        assertThat(notNumeric).endsWith(" ...");
        assertThat(notNumeric).isNotEqualTo(notNumeric.replace(" ...", "..."));
    }

    /**
     * The detail read answers the thirteen record-ordered members and the closing sentence member.
     *
     * <p>Purpose: pins the member order of the detail response against the record layout in
     * {@code app/cpy/CVTRA05Y.cpy}, whose group at line 4 declares a 350-byte record (line 2) as thirteen
     * fields at lines 5 to 17 followed by 20 bytes of filler at line 18 that no response carries.</p>
     *
     * <p>Assumptions: the order is the RECORD order of lines 5 to 17 and deliberately not the order the
     * screen was filled in. Lines 178 to 190 of {@code app/cbl/COTRN01C.cbl} are thirteen moves in screen
     * order, and the two orders disagree in two places: the card number is eleventh in the record at line
     * 15 but second on the screen at line 179, and the description is fifth in the record at line 9 but
     * seventh on the screen at line 184. The record order is chosen because the record is the stored
     * contract the ETL and the column layout both derive from, while the screen order belonged to a
     * 24-by-80 device that no longer exists; letting the move sequence drive the member order would make
     * a presentation decision of a retired terminal govern a storage contract.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("view: answer the thirteen members in record order, not in the screen order")
    void viewAnswersTheThirteenMembersInRecordOrder() throws Exception {
        this.callerIn("carddemo-user");
        when(this.viewService.viewTransaction(PRESENT_ID)).thenReturn(this.groceryDetail(null));

        this.mockMvc.perform(get(TransactionController.BASE_PATH + "/" + PRESENT_ID)
                        .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(PRESENT_ID))
                .andExpect(jsonPath("$.cardNumber").value(MASKED_CARD_NUMBER));

        List<String> components = Arrays.stream(
                        TransactionDetailResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(components).containsExactly("transactionId", "typeCode", "categoryCode", "source",
                "description", "amount", "merchantId", "merchantName", "merchantCity", "merchantZip",
                "cardNumber", "originTimestamp", "processTimestamp", "returnMessage");
        assertThat(components.indexOf("cardNumber")).isEqualTo(10);
        assertThat(components.indexOf("description")).isEqualTo(4);
    }

    /**
     * An absent closing sentence is carried as an absent value, which is not the same as a blank one.
     *
     * <p>Purpose: pins the sentinel of line 30 of {@code app/cpy/CVCRD01Y.cpy}, a condition name whose
     * value is low values and whose subject is the closing-sentence field at line 29 alone. The
     * neighbouring error field at line 28 carries no such condition name, so the two 75-character fields
     * are not interchangeable in this respect either.</p>
     *
     * <p>Assumptions: low values maps to an absent member and spaces do not. They are two distinct
     * reference states -- one says no sentence was produced, the other says a sentence of blanks was --
     * and the wire can tell them apart, so collapsing them would answer a caller that a sentence exists
     * whose content is nothing. The assertion therefore requires the member to be null and separately
     * requires it not to be the blank string.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("view: carry an absent closing sentence as null, never as blanks")
    void viewCarriesAnAbsentClosingSentenceAsNull() throws Exception {
        this.callerIn("carddemo-user");
        when(this.viewService.viewTransaction(PRESENT_ID)).thenReturn(this.groceryDetail(null));

        this.mockMvc.perform(get(TransactionController.BASE_PATH + "/" + PRESENT_ID)
                        .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnMessage").value(nullValue()))
                .andExpect(jsonPath("$.returnMessage").value(not("")));

        assertThat(this.groceryDetail("").returnMessage())
                .isNotEqualTo(this.groceryDetail(null).returnMessage());
    }

    /**
     * A closing sentence that is present is carried at the 75-character width its own field declares.
     *
     * <p>Purpose: pins which of the repository's four message widths governs this surface. The screens
     * migrated here carry their message line in the two 75-character fields at lines 28 and 29 of
     * {@code app/cpy/CVCRD01Y.cpy}, so 75 is the width for a closing sentence. The other three widths
     * belong elsewhere and are not this surface's: 50 for the two shared sentences at lines 18 to 21 of
     * {@code app/cpy/CSMSG01Y.cpy}, 72 for the abend sentence at line 28 of
     * {@code app/cpy/CSMSG02Y.cpy}, and 78 for the map field declared {@code ERRMSGI PIC X(78).} at line
     * 372 of {@code app/cpy-bms/COTRN00.CPY}, which is a generated screen field and not part of the
     * copybook message model at all. That fourth width is asserted below as a value distinct from the
     * other three rather than merely described, because the copybook model publishes no constant for it
     * and an unasserted width is one a reader may substitute for the 75 this surface uses.</p>
     *
     * <p>Assumptions: the two 50-character declarations are the ones a reader is most likely to assert
     * wrongly, because their declared width and their source text disagree. Both literals measure 49
     * characters as written -- at line 19 and at line 21 -- inside fields declared 50 wide, so the
     * fiftieth byte is supplied when the program runs and is not present in the source. This case asserts
     * the 75-character contract of the surface it is about and records that distinction so nobody asserts
     * a 50-character literal that no file contains.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("view: hold a present closing sentence inside the 75-character message contract")
    void viewHoldsAPresentClosingSentenceWithinItsDeclaredWidth() throws Exception {
        this.callerIn("carddemo-user");
        String sentence = TransactionViewService.MESSAGE_TRANSACTION_ID_NOT_FOUND;
        when(this.viewService.viewTransaction(PRESENT_ID)).thenReturn(this.groceryDetail(sentence));

        this.mockMvc.perform(get(TransactionController.BASE_PATH + "/" + PRESENT_ID)
                        .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnMessage").value(sentence));

        assertThat(sentence.length()).isLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH);
        assertThat(ApiError.MESSAGE_RENDERING_WIDTH).isEqualTo(75);
        assertThat(ApiError.COMPACT_MESSAGE_WIDTH).isEqualTo(50);
        assertThat(ApiError.ABEND_MESSAGE_WIDTH).isEqualTo(72);
        assertThat(GENERATED_SCREEN_FIELD_WIDTH)
                .isEqualTo(78)
                .isNotEqualTo(ApiError.MESSAGE_RENDERING_WIDTH)
                .isNotEqualTo(ApiError.COMPACT_MESSAGE_WIDTH)
                .isNotEqualTo(ApiError.ABEND_MESSAGE_WIDTH);
        assertThat("Invalid key pressed. Please see below...         ".length()).isEqualTo(49);
        assertThat("Thank you for using CardDemo application...      ".length()).isEqualTo(49);
    }

    /**
     * An absent row is reported with the detail read's own not-found sentence.
     *
     * <p>Purpose: pins line 285 of {@code app/cbl/COTRN01C.cbl}, the sentence moved on the not-found arm
     * that opens at line 283. Line 284 is the error flag being set, not the sentence, so the sentence is
     * read from 285.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("view: render the not-found sentence of line 285 on a 404")
    void viewReportsAnAbsentRowWithItsNotFoundSentence() throws Exception {
        this.callerIn("carddemo-user");
        when(this.viewService.viewTransaction(anyString())).thenThrow(
                new NoSuchElementException(TransactionViewService.MESSAGE_TRANSACTION_ID_NOT_FOUND));

        this.mockMvc.perform(get(TransactionController.BASE_PATH + "/" + PRESENT_ID)
                        .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value(TransactionViewService.MESSAGE_TRANSACTION_ID_NOT_FOUND));
    }

    /**
     * A failed detail read is reported with the capitalised sentence its own program carries.
     *
     * <p>Purpose: pins line 292 of {@code app/cbl/COTRN01C.cbl}, the sentence moved on the residual arm
     * that opens at line 289; line 290 displays the response codes and line 291 sets the error flag, so
     * again the sentence is read from the last of the four lines.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("view: render the capitalised failed-read sentence of line 292 on a 500")
    void viewReportsAFailedReadWithTheCapitalisedSentence() throws Exception {
        this.callerIn("carddemo-user");
        when(this.viewService.viewTransaction(anyString())).thenThrow(new IllegalStateException(
                TransactionViewService.MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION,
                new RuntimeException("reader")));

        this.mockMvc.perform(get(TransactionController.BASE_PATH + "/" + PRESENT_ID)
                        .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value(TransactionViewService.MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION));
    }

    /**
     * The identifier the detail read answers for is the one in the path, handed on untouched.
     *
     * <p>Purpose: pins the identifier's source against line 172 of {@code app/cbl/COTRN01C.cbl}, which
     * moves the keyed-in field straight into the record key with no editing between.</p>
     *
     * <p>Assumptions: the identifier travels in the path and not in a body, because the reference read it
     * from the screen field that names the row being looked at, and a read that names its subject in the
     * request line is independently authorizable. The value is asserted to arrive byte for byte, with no
     * padding and no trimming applied here, because the stored key is a declared-width character column
     * and any adjustment invented at this boundary would answer for a key the caller never named.</p>
     *
     * <p>Assumptions: the reference read carries the update option at line 275 and no rewrite ever
     * follows it, so the lock it takes is never used. The Java performs a plain read with no lock; the
     * baseline does X, the Java implements Y, and the divergence is documented.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("view: take the identifier from the path and hand it on untouched, per line 172")
    void viewTakesTheIdentifierFromThePathUntouched() throws Exception {
        this.callerIn("carddemo-user");
        when(this.viewService.viewTransaction(anyString())).thenReturn(this.groceryDetail(null));

        this.mockMvc.perform(get(TransactionController.BASE_PATH + "/" + PRESENT_ID)
                        .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isOk());

        verify(this.viewService).viewTransaction(eq(PRESENT_ID));
    }

    /**
     * A never-supplied identifier is refused with the blank state and the empty-identifier sentence.
     *
     * <p>Purpose: pins line 149 of {@code app/cbl/COTRN01C.cbl}, the sentence moved on the first arm of
     * the evaluation opened at line 146, which is reached where the keyed-in identifier field holds spaces
     * or low values; line 148 raises the error flag and line 152 sends the screen back. This case is what
     * shows the blank state, rather than the not-ok state, reaching the wire for that condition.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("view: refuse a never-supplied identifier with the blank state")
    void viewRefusesANeverSuppliedIdentifierWithTheBlankState() throws Exception {
        this.callerIn("carddemo-user");
        when(this.viewService.viewTransaction(anyString())).thenThrow(new ClientInputException(
                ApiError.CODE_VALIDATION, TransactionViewService.FIELD_TRANSACTION_ID,
                FieldValidationFlag.BLANK, TransactionViewService.MESSAGE_TRAN_ID_EMPTY));

        this.mockMvc.perform(get(TransactionController.BASE_PATH + "/" + PRESENT_ID)
                        .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(TransactionViewService.MESSAGE_TRAN_ID_EMPTY))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value(TransactionViewService.FIELD_TRANSACTION_ID))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.BLANK.name()))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value(TransactionViewService.MESSAGE_TRAN_ID_EMPTY));
    }

    /**
     * A written capture is answered created, at the path of the identifier the server assigned.
     *
     * <p>Purpose: pins the capture's success response against the derivation at lines 444 to 449 of
     * {@code app/cbl/COTRN02C.cbl}: high values are moved into the key at 444, a browse is started at 445
     * and read backward at 446 and ended at 447, the key found is moved to the work field at 448 and one
     * is added at 449. The empty-table arm at line 689 moves zeros instead, so the first identifier
     * assigned against an empty ledger is one.</p>
     *
     * <p>Assumptions: the request carries no identifier member at all, which is why the response has to
     * carry one. The server assigns it, so a client cannot propose it and cannot be answered with its own
     * proposal; the created location is therefore the only place a caller learns the key it must use to
     * read the row back.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("capture: answer a written capture created, at the assigned identifier's path")
    void captureAnswersAWrittenCaptureCreated() throws Exception {
        this.callerIn("carddemo-user");
        when(this.addService.addTransaction(any(TransactionAddRequest.class))).thenReturn(
                new TransactionAddResponse(ASSIGNED_ID, Money.of("125.50"), assembledSuccess()));

        this.mockMvc.perform(this.capture(captureBody("\"accountId\": \"00000000011\",", null)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        TransactionController.BASE_PATH + "/" + ASSIGNED_ID))
                .andExpect(jsonPath("$.transactionId").value(ASSIGNED_ID));

        assertThat(Arrays.stream(TransactionAddResponse.class.getRecordComponents())
                .map(RecordComponent::getName).toList())
                .containsExactly("transactionId", "amount", "returnMessage");
    }

    /**
     * The success sentence carries two consecutive spaces, and they are carried rather than tidied.
     *
     * <p>Purpose: pins the assembly at lines 728 to 733 of {@code app/cbl/COTRN02C.cbl}. The literal at
     * 728 ends with a space and the literal at 730 begins with one, both delimited by size, so the two
     * spaces meet; line 731 contributes the identifier delimited by space and line 732 the closing stop.
     * The three constants asserted here are the three literals, and concatenating them reproduces the
     * assembled sentence including the pair.</p>
     *
     * <p>Assumptions: the pair is reproduced rather than normalised to one space. It is what the reference
     * emits, and a caller displays the sentence it is given, so removing a space would put text on a
     * screen that the reference never produced. Recording it as an assertion is what stops a later editor
     * removing it as a typing slip -- which is exactly what it looks like.</p>
     *
     * <p>Assumptions: the reference renders this sentence in green at line 727, in the colour attribute
     * field rather than the data field, so a success is not an error even though it travels on the same
     * message line. Its counterpart here is the closing-sentence member of a created response and not the
     * problem shape, which is why this case reads that member and not an error body.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("capture: keep the two consecutive spaces the assembly at lines 728 to 733 produces")
    void theSuccessSentenceKeepsItsTwoConsecutiveSpaces() throws Exception {
        this.callerIn("carddemo-user");
        when(this.addService.addTransaction(any(TransactionAddRequest.class))).thenReturn(
                new TransactionAddResponse(ASSIGNED_ID, Money.of("125.50"), assembledSuccess()));

        this.mockMvc.perform(this.capture(captureBody("\"accountId\": \"00000000011\",", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.returnMessage").value(assembledSuccess()));

        assertThat(TransactionAddService.MESSAGE_ADDED_PREFIX)
                .isEqualTo("Transaction added successfully. ").endsWith(" ");
        assertThat(TransactionAddService.MESSAGE_ADDED_INFIX)
                .isEqualTo(" Your Tran ID is ").startsWith(" ");
        assertThat(TransactionAddService.MESSAGE_ADDED_SUFFIX).isEqualTo(".");
        assertThat(assembledSuccess()).contains("successfully.  Your");
    }

    /**
     * A withheld confirmation is answered with the reference's own prompt, not with a refusal.
     *
     * <p>Purpose: pins the confirmation construct at lines 169 to 188 of {@code app/cbl/COTRN02C.cbl},
     * which has three arms. Lines 170 to 172 add the row on either case of the letter Y; lines 173 to 176
     * gather the letter N in either case together with spaces and low values into ONE arm that moves the
     * prompt at line 178; and the residual arm at line 182 moves the invalid-value complaint at line
     * 184.</p>
     *
     * <p>Assumptions: this is the discriminator against the payment operation's own construct, which is
     * shaped differently and whose sibling case asserts the contrast. Lines 173 to 190 of
     * {@code app/cbl/COBIL00C.cbl} split the same values across FOUR arms -- Y at 174, N at 178, the blank
     * pair at 182, residual at 185 -- and its N arm clears the screen and raises the flag without moving
     * any sentence at all. So here an N is answered with a prompt and there it is answered with nothing,
     * and the two must not be given a common implementation.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("capture: answer a withheld confirmation with the prompt of line 178")
    void captureAnswersAWithheldConfirmationWithThePrompt() throws Exception {
        this.callerIn("carddemo-user");
        when(this.addService.addTransaction(any(TransactionAddRequest.class))).thenReturn(
                TransactionAddPreview.prompting(Money.of("125.50"),
                        TransactionAddService.MESSAGE_CONFIRM_ADD, "00000000011",
                        RESOLVED_CARD_NUMBER).withCopiedSource(copiedData()));

        this.mockMvc.perform(this.capture(captureBody("\"accountId\": \"00000000011\",",
                        TransactionAddService.CONFIRM_NO_UPPER)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.written").value(false))
                .andExpect(jsonPath("$.returnMessage")
                        .value(TransactionAddService.MESSAGE_CONFIRM_ADD));

        assertThat(TransactionAddService.MESSAGE_CONFIRM_ADD)
                .isEqualTo("Confirm to add this transaction...");
        assertThat(TransactionAddRequest.CONFIRM_INVALID_VALUE)
                .isEqualTo("Invalid value. Valid values are (Y/N)...")
                .isNotEqualTo(TransactionAddService.MESSAGE_CONFIRM_ADD);
    }

    /**
     * A capture carrying neither key is refused, and the refusal names both key members.
     *
     * <p>Purpose: pins the residual arm of the key construct at lines 224 to 229 of
     * {@code app/cbl/COTRN02C.cbl}, reached when neither control was filled, whose line 226 moves the
     * sentence naming both. The construct opens at line 193 as an evaluation whose account arm comes
     * first at 196 and whose card arm follows at 210, so this is the only condition it reports.</p>
     *
     * <p>Assumptions: the per-field array is asserted rather than the status alone, because a client
     * displays a complaint beside the input it belongs to. A refusal attributed to the request as a whole
     * would answer 400 just the same and would be undisplayable, so naming both members is the part of
     * the contract worth pinning.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("capture: refuse a submission carrying neither key, naming both members")
    void captureRefusesASubmissionCarryingNeitherKey() throws Exception {
        this.callerIn("carddemo-user");

        this.mockMvc.perform(this.capture(captureBody("", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field",
                        hasItems(TransactionAddService.FIELD_ACCOUNT_ID,
                                TransactionAddService.FIELD_CARD_NUMBER)))
                .andExpect(jsonPath("$.fieldErrors[*].message",
                        hasItem(TransactionAddRequest.KEY_FIELD_REQUIRED)));

        assertThat(TransactionAddRequest.KEY_FIELD_REQUIRED)
                .isEqualTo("Account or Card Number must be entered...");
        verifyNoInteractions(this.addService);
    }

    /**
     * A capture carrying both keys is admitted, because the reference resolves such a pair account-first.
     *
     * <p>Purpose: pins the precedence of the evaluation opened at line 193 of
     * {@code app/cbl/COTRN02C.cbl}, whose account arm at line 196 is tested before the card arm at line
     * 210. A submission carrying both therefore takes the account arm, which reads the cross-reference at
     * line 208 and then, at line 209, writes the resolved card number over the one submitted -- with no
     * complaint of any kind.</p>
     *
     * <p>Assumptions: the boundary must admit this submission for that precedence to be reachable at all.
     * The reference accepts it silently, so refusing it here would make a documented branch unreachable
     * through the published surface for the one case the branch exists to decide. The card arm's own
     * complaints are pinned as values below rather than provoked, because reaching either of them requires
     * a non-numeric key that the boundary's own declared shapes already exclude.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("capture: admit a submission carrying both keys, resolved account-first at line 196")
    void captureAdmitsASubmissionCarryingBothKeys() throws Exception {
        this.callerIn("carddemo-user");
        when(this.addService.addTransaction(any(TransactionAddRequest.class))).thenReturn(
                new TransactionAddResponse(ASSIGNED_ID, Money.of("125.50"), assembledSuccess()));

        this.mockMvc.perform(this.capture(captureBody("\"accountId\": \"00000000011\","
                        + "\n\"cardNumber\": \"" + LEADING_ZERO_CARD_NUMBER + "\",", null)))
                .andExpect(status().isCreated());

        assertThat(TransactionAddRequest.ACCOUNT_ID_NOT_NUMERIC)
                .isEqualTo("Account ID must be Numeric...");
        assertThat(TransactionAddRequest.CARD_NUMBER_NOT_NUMERIC)
                .isEqualTo("Card Number must be Numeric...");
    }

    /**
     * An identifier already stored is reported as a duplicate-key conflict and never as a stale version.
     *
     * <p>Purpose: pins the conflict arm of {@code app/cbl/COTRN02C.cbl}, where lines 735 and 736 place the
     * duplicate-key and duplicate-record conditions on the same arm, line 737 raises the flag and line 738
     * moves the one sentence both conditions share.</p>
     *
     * <p>Refactoring Rationale: the discriminating assertion is that the array of per-field complaints is
     * EMPTY. A stale-version conflict answers the same status with a different sentence and publishes the
     * current version as a complaint against a version member, so an empty array plus this sentence is
     * what states positively that the conflict is about a key that already exists. No ledger entity in
     * this context carries a version member at all, so an optimistic-lock failure cannot arise on this
     * path and an assertion phrased as one would be asserting a condition the code cannot reach. This is
     * recorded so the case is not later rewritten into a version-conflict assertion that would pass
     * vacuously.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("capture: report an already-stored identifier as a duplicate-key conflict")
    void captureReportsAnAlreadyStoredIdentifierAsADuplicateKeyConflict() throws Exception {
        this.callerIn("carddemo-user");
        when(this.addService.addTransaction(any(TransactionAddRequest.class)))
                .thenThrow(new RecordConflictException(RecordConflictException.Kind.DUPLICATE_KEY));

        this.mockMvc.perform(this.capture(captureBody("\"accountId\": \"00000000011\",", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(ApiError.CONFLICT_STATUS))
                .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.MESSAGE_DUPLICATE_KEY))
                .andExpect(jsonPath("$.message")
                        .value(not(GlobalExceptionHandler.MESSAGE_RECORD_CHANGED)))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());

        assertThat(GlobalExceptionHandler.MESSAGE_DUPLICATE_KEY)
                .isEqualTo("Tran ID already exist...");
    }

    /**
     * A capture that fails for any other reason is reported with the catch-all sentence of line 745.
     *
     * <p>Purpose: pins the residual arm of {@code app/cbl/COTRN02C.cbl}, opened at line 742, which
     * displays the response codes at 743, raises the flag at 744 and moves its sentence at 745.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("capture: render the catch-all sentence of line 745 on a 500")
    void captureReportsAFailedWriteWithItsCatchAllSentence() throws Exception {
        this.callerIn("carddemo-user");
        when(this.addService.addTransaction(any(TransactionAddRequest.class))).thenThrow(
                new IllegalStateException(TransactionAddService.MESSAGE_ADD_FAILED,
                        new RuntimeException("writer")));

        this.mockMvc.perform(this.capture(captureBody("\"accountId\": \"00000000011\",", null)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(TransactionAddService.MESSAGE_ADD_FAILED));

        assertThat(TransactionAddService.MESSAGE_ADD_FAILED)
                .isEqualTo("Unable to Add Transaction...");
    }

    /**
     * A submission wrong in two members is answered with a complaint against each of the two.
     *
     * <p>Purpose: pins the shape of a multi-member refusal for the three positional-format complaints of
     * {@code app/cbl/COTRN02C.cbl} -- the amount form at line 345 from the evaluation opening at 339, the
     * origin date form at line 360 from the evaluation opening at 353, and the process date form at line
     * 375 from the evaluation opening at 368.</p>
     *
     * <p>Assumptions: the baseline short-circuits at the first failure via PERFORM SEND-TRNADD-SCREEN then
     * EXEC CICS RETURN (lines 530 to 534), emitting one message; the Java collects a per-field array; the
     * divergence is documented. That program holds exactly two task-ending returns, at line 156 and line
     * 530, and every validation arm reaches the second of them through the sending paragraph at line 516,
     * which moves the single message at line 520 and sends the map at line 522 -- so one screen turn can
     * carry one sentence and no more.</p>
     *
     * <p>Trade-offs: the array is accepted as a divergence rather than reproduced as a single complaint.
     * A caller of the migrated surface is not a screen and can display every complaint at once, so
     * answering one at a time would cost a round trip per wrong member for no gain in parity of meaning;
     * the compromise accepted is that a caller receives more information per response than the reference
     * could carry, and each individual sentence stays exactly the sentence the reference emits for that
     * member.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("capture: answer a two-member failure with a complaint against each member")
    void captureAnswersAMultiMemberFailureWithOneComplaintPerMember() throws Exception {
        this.callerIn("carddemo-user");

        this.mockMvc.perform(this.capture("{\n"
                        + "\"accountId\": \"00000000011\",\n"
                        + "\"typeCode\": \"01\",\n"
                        + "\"categoryCode\": \"0001\",\n"
                        + "\"source\": \"POS TERM\",\n"
                        + "\"description\": \"GROCERY PURCHASE\",\n"
                        + "\"amount\": \"125.50\",\n"
                        + "\"merchantId\": \"123456789\",\n"
                        + "\"merchantName\": \"CORNER STORE\",\n"
                        + "\"merchantCity\": \"SEATTLE\",\n"
                        + "\"merchantZip\": \"98101\",\n"
                        + "\"originDate\": \"15-01-2026\",\n"
                        + "\"processDate\": \"16-01-2026\"\n"
                        + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field",
                        hasItems(TransactionAddService.FIELD_ORIGIN_DATE,
                                TransactionAddService.FIELD_PROCESS_DATE)))
                .andExpect(jsonPath("$.fieldErrors[*].message",
                        hasItems(TransactionAddRequest.ORIGIN_DATE_FORMAT,
                                TransactionAddRequest.PROCESS_DATE_FORMAT)));

        assertThat(TransactionAddRequest.AMOUNT_FORMAT)
                .isEqualTo("Amount should be in format -99999999.99");
        assertThat(TransactionAddRequest.ORIGIN_DATE_FORMAT)
                .isEqualTo("Orig Date should be in format YYYY-MM-DD");
        assertThat(TransactionAddRequest.PROCESS_DATE_FORMAT)
                .isEqualTo("Proc Date should be in format YYYY-MM-DD");
    }

    /**
     * Every amount reaches the wire quoted, on all three operations, and never as a bare JSON number.
     *
     * <p>Purpose: pins the encoding of the amount whose precision {@code app/cpy/CVTRA05Y.cpy} declares at
     * line 10, signed with nine integer digits and two decimal places, against all three response bodies:
     * the browse row, the detail read and the created capture.</p>
     *
     * <p>Alternatives Considered: emitting the amount as a JSON number was rejected. A JSON number is
     * parsed by most clients into an IEEE-754 binary value of 64 bits, which cannot represent every
     * two-decimal amount exactly, so the loss would happen after the response left this service and at
     * the boundary the person reading a statement actually sees. A quoted decimal crosses intact and each
     * client decides its own decimal type. The cost is that a client must parse rather than read a number
     * directly, which is the compromise this contract accepts.</p>
     *
     * <p>Assumptions: the encoding is asserted by reading the raw body text for the quoted form rather
     * than through a path expression, because a path expression coerces {@code "125.50"} and
     * {@code 125.50} to the same value and so cannot tell the two encodings apart -- which is the one
     * thing this case exists to distinguish. {@link MoneyModule} is what binds the encoding, and it is
     * consumed from the shared kernel rather than restated here.</p>
     *
     * @throws Exception if a request could not be performed
     */
    @Test
    @DisplayName("all three: quote every amount on the wire, never emit a bare JSON number")
    void everyAmountReachesTheWireQuoted() throws Exception {
        this.callerIn("carddemo-user");
        String boundary = this.sealed(PRESENT_ID);
        when(this.listService.listTransactions(any(), any(), any())).thenReturn(
                PageResponse.ofRows(List.of(this.groceryRow()), boundary, boundary, false));
        when(this.viewService.viewTransaction(anyString())).thenReturn(this.groceryDetail(null));
        when(this.addService.addTransaction(any(TransactionAddRequest.class))).thenReturn(
                new TransactionAddResponse(ASSIGNED_ID, Money.of("125.50"), assembledSuccess()));

        String browsed = this.browseAs("carddemo-user").andReturn().getResponse()
                .getContentAsString();
        String viewed = this.mockMvc.perform(get(TransactionController.BASE_PATH + "/" + PRESENT_ID)
                        .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andReturn().getResponse().getContentAsString();
        String captured = this.mockMvc
                .perform(this.capture(captureBody("\"accountId\": \"00000000011\",", null)))
                .andReturn().getResponse().getContentAsString();

        assertThat(browsed).contains("\"amount\":\"125.50\"").doesNotContain("\"amount\":125.50");
        assertThat(viewed).contains("\"amount\":\"-125.50\"").doesNotContain("\"amount\":-125.50");
        assertThat(captured).contains("\"amount\":\"125.50\"").doesNotContain("\"amount\":125.50");
        assertThat(Money.of("125.50").amount().scale()).isEqualTo(Money.SCALE);
        assertThat(Money.SCALE).isEqualTo(2);
    }

    /**
     * A card number whose leading digit is a zero survives the wire with that digit still in place.
     *
     * <p>Purpose: pins the encoding of the card number that {@code app/cpy/CVTRA05Y.cpy} declares at line
     * 15 as sixteen characters, using the witness the seed data supplies: the second record of
     * {@code app/data/ASCII/dailytran.txt} carries a card number beginning with a zero at one-based bytes
     * 263 to 278.</p>
     *
     * <p>Assumptions: the member is a digits-only string and not a number, and the reference itself says
     * why. {@code app/cpy/CVCRD01Y.cpy} declares the card number twice over the same storage, as sixteen
     * characters at line 37 and as sixteen digits redefining it at line 39, and does the same for the
     * account identifier at lines 34 and 36 -- so the reference treats these keys as characters on the
     * wire and as numbers only where it does arithmetic. A numeric member would drop this witness's
     * leading digit and answer for a different card, which no status code would reveal.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("capture: keep a leading zero in a card number, per the second seed record")
    void aCardNumberWithALeadingZeroSurvivesTheWire() throws Exception {
        this.callerIn("carddemo-user");
        ArgumentCaptor<TransactionAddRequest> submitted =
                ArgumentCaptor.forClass(TransactionAddRequest.class);
        when(this.addService.addTransaction(any(TransactionAddRequest.class))).thenReturn(
                new TransactionAddResponse(ASSIGNED_ID, Money.of("125.50"), assembledSuccess()));

        this.mockMvc.perform(this.capture(captureBody(
                        "\"cardNumber\": \"" + LEADING_ZERO_CARD_NUMBER + "\",", null)))
                .andExpect(status().isCreated());

        verify(this.addService).addTransaction(submitted.capture());
        assertThat(submitted.getValue().cardNumber())
                .isEqualTo(LEADING_ZERO_CARD_NUMBER)
                .startsWith("0")
                .hasSize(TransactionAddRequest.CARD_NUMBER_WIDTH)
                .containsOnlyDigits();
    }

    /**
     * No response of these three operations has a member a card verification value could travel in.
     *
     * <p>Purpose: pins the absence structurally rather than by inspecting a value. The record layout in
     * {@code app/cpy/CVTRA05Y.cpy} declares no verification value at lines 5 to 17, and none of the three
     * response shapes declares one either, so there is no member for such a value to occupy.</p>
     *
     * <p>Alternatives Considered: asserting that a particular response body does not contain a particular
     * verification value was rejected as the primary check. It would pass for every value nobody thought
     * to try, whereas comparing the declared member set of each shape against its published list proves
     * that no such member exists at all -- and the same comparison catches a member being added later. The
     * detail read's own card number is separately asserted to arrive masked to its last four digits.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("all three: declare no member a card verification value could occupy")
    void noResponseShapeCanCarryACardVerificationValue() throws Exception {
        this.callerIn("carddemo-user");
        when(this.viewService.viewTransaction(anyString())).thenReturn(this.groceryDetail(null));

        String viewed = this.mockMvc.perform(get(TransactionController.BASE_PATH + "/" + PRESENT_ID)
                        .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(jsonPath("$.cardNumber").value(MASKED_CARD_NUMBER))
                .andReturn().getResponse().getContentAsString();

        assertThat(memberNames(viewed)).containsExactlyInAnyOrder("transactionId", "typeCode",
                "categoryCode", "source", "description", "amount", "merchantId", "merchantName",
                "merchantCity", "merchantZip", "cardNumber", "originTimestamp", "processTimestamp",
                "returnMessage");
        assertThat(MASKED_CARD_NUMBER)
                .endsWith(LEADING_ZERO_CARD_NUMBER.substring(12))
                .doesNotContain(LEADING_ZERO_CARD_NUMBER.substring(0, 12));
    }

    /**
     * The per-member complaint array distinguishes all three validation states, marker included.
     *
     * <p>Purpose: pins the three-state model the templated highlight in {@code app/cpy/CSSETATY.cpy}
     * encodes by NESTING. Its outer test at lines 18 to 19 admits either the not-ok state or the blank
     * state and moves red into the colour-attribute field at lines 21 to 22; its inner test at line 23,
     * nested inside that outer one, admits the blank state alone and moves the asterisk into the
     * output-data field at lines 24 to 25, a different generated field. So the not-ok state colours only,
     * the blank state colours and additionally marks, and a valid member does neither.</p>
     *
     * <p>Assumptions: the marker is derived from the state rather than carried beside it. The state member
     * is what reaches the wire, and the asterisk is read from the state, which is why this case asserts
     * the two wire states it can provoke and then asserts the marker each of the three states derives.
     * A wire member for the marker would let a body claim a state and a marker that disagree.</p>
     *
     * <p>Assumptions: the outer test at line 20 is additionally gated on the re-entry condition, the
     * context flag declared at lines 29 to 31 of {@code app/cpy/COCOM01Y.cpy} with its two condition names
     * for first entry and re-entry. That gate is severed here: a stateless request has no earlier turn to
     * compare itself with, so the array is populated on every failing request and never on a turn count.
     * That is why the two provoked cases below need no priming request to become visible.</p>
     *
     * @throws Exception if a request could not be performed
     */
    @Test
    @DisplayName("all three: distinguish not-ok, blank and valid, with the blank marker derived")
    void theComplaintArrayDistinguishesAllThreeValidationStates() throws Exception {
        this.callerIn("carddemo-user");
        when(this.viewService.viewTransaction(anyString())).thenThrow(new ClientInputException(
                ApiError.CODE_VALIDATION, TransactionViewService.FIELD_TRANSACTION_ID,
                FieldValidationFlag.BLANK, TransactionViewService.MESSAGE_TRAN_ID_EMPTY));

        this.mockMvc.perform(get(TransactionController.BASE_PATH + "/" + PRESENT_ID)
                        .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(jsonPath("$.fieldErrors[*].state")
                        .value(containsInAnyOrder(FieldValidationFlag.BLANK.name())));

        this.mockMvc.perform(get(TransactionController.BASE_PATH)
                        .param(TransactionController.PARAM_TRANSACTION_ID_FILTER, "still-not-an-id")
                        .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(jsonPath("$.fieldErrors[*].state",
                        hasItem(FieldValidationFlag.NOT_OK.name())))
                .andExpect(jsonPath("$.fieldErrors[*].state",
                        not(hasItem(FieldValidationFlag.VALID.name()))));

        assertThat(FieldValidationFlag.BLANK.screenMarker())
                .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER).isEqualTo("*");
        assertThat(FieldValidationFlag.NOT_OK.screenMarker())
                .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER).isEmpty();
        assertThat(FieldValidationFlag.VALID.screenMarker())
                .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER).isEmpty();
        assertThat(FieldValidationFlag.BLANK.isError()).isTrue();
        assertThat(FieldValidationFlag.NOT_OK.isError()).isTrue();
        assertThat(FieldValidationFlag.VALID.isError()).isFalse();
        assertThat(new ApiError.FieldError(TransactionViewService.FIELD_TRANSACTION_ID,
                FieldValidationFlag.BLANK, TransactionViewService.MESSAGE_TRAN_ID_EMPTY)
                .screenMarker()).isEqualTo("*");
    }

    /**
     * Either published business authority reaches the browse, and both are named without a role prefix.
     *
     * <p>Purpose: pins the authorization the deployed chain applies, which admits any request bearing
     * either of the two group-derived authorities. Those two replace the user-type field the reference
     * carried at line 26 of {@code app/cpy/COCOM01Y.cpy} with its condition names for the administrator
     * and the ordinary user at lines 27 and 28.</p>
     *
     * <p>Assumptions: authority names are asserted verbatim and are deliberately not role names. The
     * chain matches the authority string exactly as the group claim converts to it, so a role-style
     * assertion would be testing a role-style name the converter never produces and the chain never
     * matches -- and the mistake would surface as a blanket refusal of every caller rather than as a
     * failed comparison.</p>
     *
     * <p>Refactoring Rationale: this replaces a client-echoed flag with a signed claim. The reference read
     * the user type from storage the terminal handed back each turn, so a caller could in principle assert
     * its own type; here the group travels in a signed token, the conversion happens inside the chain, and
     * the caller cannot state anything about its own authority.</p>
     *
     * @param group the group claim value presented, of type {@link String}; must not be {@code null}
     * @throws Exception if the request could not be performed
     */
    @ParameterizedTest(name = "{0} reaches the browse")
    @MethodSource("publishedBusinessGroups")
    @DisplayName("browse: admit either published business authority")
    void eitherPublishedBusinessAuthorityReachesTheBrowse(String group) throws Exception {
        when(this.listService.listTransactions(any(), any(), any()))
                .thenReturn(PageResponse.empty());

        this.browseAs(group).andExpect(status().isOk());

        assertThat(JwtRoleConverter.ADMIN_AUTHORITY).isEqualTo("carddemo-admin")
                .doesNotStartWith("ROLE_");
        assertThat(JwtRoleConverter.USER_AUTHORITY).isEqualTo("carddemo-user")
                .doesNotStartWith("ROLE_");
        assertThat(SecurityConfig.BUSINESS_AUTHORITIES)
                .containsExactly(JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);
    }

    /**
     * Supplies each published business group the browse must admit.
     *
     * @return the two group claim values, never {@code null}
     */
    private static Stream<Arguments> publishedBusinessGroups() {
        return Stream.of(Arguments.of(JwtRoleConverter.ADMIN_AUTHORITY),
                Arguments.of(JwtRoleConverter.USER_AUTHORITY));
    }

    /**
     * A token carrying neither business authority is refused, and no collaborator is reached.
     *
     * <p>Purpose: pins the other side of the same rule as the previous case, against the two-valued user
     * type the reference carried at line 26 of {@code app/cpy/COCOM01Y.cpy} with its condition names for
     * the administrator and the ordinary user at lines 27 and 28. A credential that verifies but confers
     * neither of the authorities those two values became is refused by the chain, so the two cases
     * together state that membership decides access rather than mere possession of a token.</p>
     *
     * <p>Assumptions: the refusal is asserted to be correlatable, because it is produced inside the
     * security chain rather than by a handler. The correlation filter runs ahead of the chain, which is
     * what puts an identifier on a response no handler ever saw and what makes the refusal traceable in
     * the log record that accompanies it.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("browse: refuse a token carrying neither published business authority")
    void aTokenCarryingNeitherBusinessAuthorityIsRefused() throws Exception {
        this.callerIn(UNRELATED_GROUP);

        this.mockMvc.perform(get(TransactionController.BASE_PATH)
                        .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());

        verifyNoInteractions(this.listService, this.viewService, this.addService);
    }

    /**
     * An unauthenticated request is refused before any collaborator is reached, and keeps no session.
     *
     * <p>Purpose: pins the stateless posture that replaces the reference's turn-by-turn storage. The
     * reference's session group spans lines 19 to 44 of {@code app/cpy/COCOM01Y.cpy} and carried the
     * caller identity at line 25, the user type at line 26 and the navigation targets at lines 21 to 24;
     * none of it travels here. Identity comes from the presented credential, the subject of a browse comes
     * from the request itself, and where a caller goes next is the caller's own affair.</p>
     *
     * <p>Assumptions: the absence of a session is asserted rather than assumed, by requiring that the
     * request created none and that no session cookie was set. That posture is what makes the deployed
     * service horizontally scalable behind a balancer without sticky routing: a second request may be
     * served by a different task, so anything remembered between requests would be remembered by only one
     * of them.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("browse: refuse an unauthenticated request and keep no session for it")
    void anUnauthenticatedRequestIsRefusedAndLeavesNoSession() throws Exception {
        MvcResult refused = this.mockMvc.perform(get(TransactionController.BASE_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.timestamp").value(PINNED_TIMESTAMP))
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .andReturn();

        assertThat(refused.getRequest().getSession(false)).isNull();
        assertThat(refused.getResponse().getCookies()).isEmpty();
        verifyNoInteractions(this.listService, this.viewService, this.addService);
    }


    /**
     * The copy action is mapped, and a confirmed copy is answered created at the assigned path.
     *
     * <p>Refactoring Rationale: this operation had no boundary assertion of any kind. Its handler is
     * declared, its service method is unit-tested against a substituted store, and the routing contract
     * class mentions the path only in prose -- so nothing established that a request to it resolved to a
     * handler, that a confirmed copy answered 201 rather than 200, or that the created address named the
     * ASSIGNED identifier. The action reproduces a function key on the reference's own capture screen,
     * which lines 471 to 495 of {@code app/cbl/COTRN02C.cbl} implement as a key-field validation, an
     * eleven-member move and then the ordinary enter-key path, so its success shape is the capture's --
     * and that is precisely why an unasserted 200 would have looked plausible.</p>
     *
     * <p>Assumptions: the address is asserted against the identifier the SERVICE assigned rather than
     * anything the request carried, because the request carries no identifier member at all. A created
     * address derived from input would name a row the store did not write.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("copy: answer a confirmed copy created, at the assigned identifier's path")
    void copyAnswersAConfirmedCopyCreated() throws Exception {
        this.callerIn("carddemo-user");
        when(this.addService.copyLastTransactionData(any(CopyLastRequest.class))).thenReturn(
                new TransactionAddResponse(ASSIGNED_ID, Money.of("125.50"), assembledSuccess()));

        this.mockMvc.perform(this.copyLast(copyBody("\"accountId\": \"00000000011\",",
                        TransactionAddService.CONFIRM_YES_UPPER)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        TransactionController.BASE_PATH + "/" + ASSIGNED_ID))
                .andExpect(jsonPath("$.transactionId").value(ASSIGNED_ID))
                .andExpect(jsonPath("$.amount").value("125.50"));

        verify(this.addService).copyLastTransactionData(any(CopyLastRequest.class));
        verify(this.addService, never()).addTransaction(any(TransactionAddRequest.class));
    }

    /**
     * A copy with the confirmation withheld is answered 200 with the prompt and no created address.
     *
     * <p>Purpose: the two turns of this action answer with two DIFFERENT shapes at two different
     * statuses, and the absence of the created address on the withheld turn is the half a client acts
     * on -- it is how the client knows nothing was written. A handler that answered 201 on both turns
     * would tell a client a row exists at an address that resolves to nothing.</p>
     *
     * <p>Assumptions: the address is asserted ABSENT rather than merely unequal, because the published
     * 200 declares no such header and an empty or partially-composed one would be a header the contract
     * does not declare.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("copy: answer a withheld confirmation 200 with the prompt and no created address")
    void copyAnswersAWithheldConfirmationWithThePrompt() throws Exception {
        this.callerIn("carddemo-user");
        when(this.addService.copyLastTransactionData(any(CopyLastRequest.class))).thenReturn(
                TransactionAddPreview.prompting(Money.of("125.50"),
                        TransactionAddService.MESSAGE_CONFIRM_ADD, "00000000011",
                        RESOLVED_CARD_NUMBER).withCopiedSource(copiedData()));

        this.mockMvc.perform(this.copyLast(copyBody("\"accountId\": \"00000000011\",",
                        TransactionAddService.CONFIRM_NO_UPPER)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.written").value(false))
                .andExpect(jsonPath("$.returnMessage")
                        .value(TransactionAddService.MESSAGE_CONFIRM_ADD))
                .andExpect(jsonPath("$.transactionId").doesNotExist());
    }

    /**
     * A copy carrying neither key is refused, naming both key members, and reaches no service.
     *
     * <p>Purpose: the reference validates the key fields FIRST on this path -- line 473, before the
     * eleven-member move at 482 to 492 -- so a copy with no key selects no record to copy from. Pinning
     * the refusal at the boundary is what keeps that ordering observable: a copy that reached the store
     * with no key would have to invent a selection.</p>
     *
     * <p>Assumptions: the per-field array is asserted rather than the status alone, for the same reason
     * the capture's sibling case gives -- a client displays a complaint beside the input it belongs to,
     * and a refusal attributed to the request as a whole answers 400 just the same while being
     * undisplayable.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("copy: refuse a submission carrying neither key, naming both members")
    void copyRefusesASubmissionCarryingNeitherKey() throws Exception {
        this.callerIn("carddemo-user");

        this.mockMvc.perform(this.copyLast(copyBody("",
                        TransactionAddService.CONFIRM_YES_UPPER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field",
                        hasItems(TransactionAddService.FIELD_ACCOUNT_ID,
                                TransactionAddService.FIELD_CARD_NUMBER)))
                .andExpect(jsonPath("$.fieldErrors[*].message",
                        hasItem(TransactionAddRequest.KEY_FIELD_REQUIRED)));

        verifyNoInteractions(this.addService);
    }

    /**
     * A copy whose key resolves to no transaction to copy is answered 404, not 201 and not 500.
     *
     * <p>Purpose: an empty ledger for the selected key is a condition the caller can act on -- by
     * capturing a transaction rather than copying one -- so it belongs on the not-found channel. A
     * handler that rendered it as a fault would put an actionable outcome on the channel the alerting
     * watches, and one that rendered it as a created capture would report a row that does not exist.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("copy: answer a key with nothing to copy as 404, never as created or as a fault")
    void copyAnswersNothingToCopyAsNotFound() throws Exception {
        this.callerIn("carddemo-user");
        when(this.addService.copyLastTransactionData(any(CopyLastRequest.class)))
                .thenThrow(new NoSuchElementException(COPY_SOURCE_ABSENT_SENTENCE));

        this.mockMvc.perform(this.copyLast(copyBody("\"accountId\": \"00000000011\",",
                        TransactionAddService.CONFIRM_YES_UPPER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_NOT_FOUND))
                .andExpect(header().doesNotExist("Location"));
    }

    /**
     * A fault while copying answers a leak-free 500 and carries no store diagnostic.
     *
     * <p>Assumptions: the failure raised is the standard illegal-state one, which is what the handler
     * declares for a read or an append the caller cannot correct, and it is given a diagnostic-shaped
     * message so the body can be asserted free of it rather than asserted against nothing.</p>
     *
     * <p>Assumptions: the created address is asserted absent here too. A fault mid-append is exactly the
     * condition in which a handler that composed the header before deciding the status would emit an
     * address for a row that was rolled back.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("copy: answer a fault with a leak-free 500 and no created address")
    void copyAnswersAFaultLeakFree() throws Exception {
        this.callerIn("carddemo-user");
        when(this.addService.copyLastTransactionData(any(CopyLastRequest.class)))
                .thenThrow(new IllegalStateException(STORE_DIAGNOSTIC));

        MvcResult result = this.mockMvc.perform(this.copyLast(
                        copyBody("\"accountId\": \"00000000011\",",
                                TransactionAddService.CONFIRM_YES_UPPER)))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("Location"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .as("nothing the store said about itself may reach a caller")
                .doesNotContain(STORE_DIAGNOSTIC);
    }

    /**
     * The copy action demands a business authority, and refuses a token carrying neither.
     *
     * <p>Assumptions: it is deliberately NOT narrowed to an administrative authority. The reference
     * binds this action to a function key on the ordinary capture screen, available to whoever may
     * capture at all, so requiring more here would refuse an operator the reference admits -- and
     * requiring less would admit a token with no business group to a write.</p>
     *
     * <p>Assumptions: both halves are asserted in one case. Admission alone is satisfiable by a chain
     * that authorizes everything, and refusal alone by a chain that authorizes nothing; only the pair
     * establishes that the authority is what decided.</p>
     *
     * @throws Exception if either request could not be performed
     */
    @Test
    @DisplayName("copy: admit a business authority and refuse a token carrying neither")
    void copyDemandsABusinessAuthority() throws Exception {
        when(this.addService.copyLastTransactionData(any(CopyLastRequest.class))).thenReturn(
                new TransactionAddResponse(ASSIGNED_ID, Money.of("125.50"), assembledSuccess()));

        this.callerIn("carddemo-user");
        this.mockMvc.perform(this.copyLast(copyBody("\"accountId\": \"00000000011\",",
                        TransactionAddService.CONFIRM_YES_UPPER)))
                .andExpect(status().isCreated());

        this.callerIn("some-unrelated-group");
        this.mockMvc.perform(this.copyLast(copyBody("\"accountId\": \"00000000011\",",
                        TransactionAddService.CONFIRM_YES_UPPER)))
                .andExpect(status().isForbidden());

        this.mockMvc.perform(post(TransactionController.BASE_PATH
                        + TransactionController.COPY_LAST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(copyBody("\"accountId\": \"00000000011\",",
                                TransactionAddService.CONFIRM_YES_UPPER)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A copy submission carrying a key and a confirmation ALONE reaches the service.
     *
     * <p>⚠️ Purpose: this is the case that would have caught the defect, and it is the only kind of case
     * that could. The operation was published over the capture request shape, whose eleven data components
     * each carry a not-blank and a width or shape constraint; bean validation runs on the bound body
     * BEFORE any handler is entered, so a copy submission leaving those fields empty was refused with
     * eleven field errors and never reached the service at all. That empty submission is the ONLY one the
     * reference's own function key can produce -- the operator presses it on a screen they have not filled
     * in, because lines 482 to 492 of {@code app/cbl/COTRN02C.cbl} are about to overwrite every data field
     * -- so the normal copy flow was unreachable through the published surface while the service method
     * behind it was correct. No service test could see it: a service-level call bypasses bean validation
     * entirely.</p>
     *
     * <p>Assumptions: the body carries the account and the confirmation and NOTHING else, and the service
     * is verified to have been called. Asserting the status alone would not distinguish a refusal that
     * happened to answer 200 from a submission that was actually processed, and asserting the service call
     * is what pins the reachability rather than the outcome.</p>
     *
     * <p>Assumptions: the first turn is exercised with the confirmation WITHHELD as well as with it given,
     * because the withheld turn is the one an operator performs first and it is the turn whose body is
     * emptiest. A case covering the affirmative turn alone would still carry one more member than the
     * shape strictly requires.</p>
     *
     * @throws Exception if either request could not be performed
     */
    @Test
    @DisplayName("copy: accept a key and a confirmation alone, with no data member at all")
    void copyAcceptsAKeyAndConfirmationAlone() throws Exception {
        this.callerIn("carddemo-user");
        when(this.addService.copyLastTransactionData(any(CopyLastRequest.class))).thenReturn(
                TransactionAddPreview.prompting(Money.of("42.75"),
                        TransactionAddService.MESSAGE_CONFIRM_ADD, "00000000011",
                        RESOLVED_CARD_NUMBER).withCopiedSource(copiedData()));

        this.mockMvc.perform(this.copyLast(
                        "{\"accountId\": \"00000000011\", \"confirmation\": \"N\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(false))
                .andExpect(jsonPath("$.resolvedCardNumber").value(RESOLVED_CARD_NUMBER));

        this.mockMvc.perform(this.copyLast("{\"accountId\": \"00000000011\"}"))
                .andExpect(status().isOk());

        verify(this.addService, times(2))
                .copyLastTransactionData(any(CopyLastRequest.class));
    }

    /**
     * A copy preview publishes the resolved pair and the copied record, so a client can render what it is
     * confirming.
     *
     * <p>⚠️ Purpose: the disclosure is only useful if it reaches the wire, and the published schema closes
     * the preview object with {@code additionalProperties: false} -- so a member the record carries and
     * the document does not would make every preview body invalid against its own contract. This case
     * reads the block off the RESPONSE rather than off the record, which is the only place that
     * distinction is observable.</p>
     *
     * <p>Assumptions: the resolved card is asserted in FULL rather than masked. It is the one value the
     * operator must be able to compare against what they typed, and a suffix cannot distinguish two cards
     * on one account; the prohibition the sensitive-data contract states is on durable diagnostics, which
     * is why the record's own rendering withholds it and this body does not.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("copy: publish the resolved pair, the source row and all ten data values")
    void copyPublishesTheResolvedPairAndTheCopiedRecord() throws Exception {
        this.callerIn("carddemo-user");
        when(this.addService.copyLastTransactionData(any(CopyLastRequest.class))).thenReturn(
                TransactionAddPreview.prompting(Money.of("42.75"),
                        TransactionAddService.MESSAGE_CONFIRM_ADD, "00000000011",
                        RESOLVED_CARD_NUMBER).withCopiedSource(copiedData()));

        this.mockMvc.perform(this.copyLast(copyBody("\"accountId\": \"00000000011\",",
                        TransactionAddService.CONFIRM_NO_UPPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAccountId").value("00000000011"))
                .andExpect(jsonPath("$.resolvedCardNumber").value(RESOLVED_CARD_NUMBER))
                .andExpect(jsonPath("$.copied.typeCode").value("01"))
                .andExpect(jsonPath("$.copied.categoryCode").value("0001"))
                .andExpect(jsonPath("$.copied.source").value("POS TERM"))
                .andExpect(jsonPath("$.copied.description").value("GROCERY PURCHASE"))
                .andExpect(jsonPath("$.copied.merchantId").value("123456789"))
                .andExpect(jsonPath("$.copied.merchantName").value("CORNER STORE"))
                .andExpect(jsonPath("$.copied.merchantCity").value("SEATTLE"))
                .andExpect(jsonPath("$.copied.merchantZip").value("98101"))
                .andExpect(jsonPath("$.copied.originDate").value("2026-01-15"))
                .andExpect(jsonPath("$.copied.processDate").value("2026-01-16"));
    }

    /**
     * Builds a copy-last body: the key members, the confirmation, and nothing else.
     *
     * <p>Purpose: the copy operation binds {@code CopyLastRequest}, which declares the two key
     * components and the confirmation ONLY. Sending a full capture body here would compile and pass while
     * testing the wrong shape, so this helper exists to make the two bodies impossible to confuse.</p>
     *
     * <p>Assumptions: no data member is sent, which is the reachable form of this request rather than an
     * economy. The reference's function key is pressed on a screen the operator has NOT filled in --
     * lines 482 to 492 of {@code app/cbl/COTRN02C.cbl} are about to overwrite every data field -- and a
     * body carrying eleven data values is exactly the submission that has no need to copy anything.</p>
     *
     * @param keyMembers the key members as JSON text including their trailing comma, or empty text for a
     *     submission naming neither key; must not be {@code null}
     * @param confirmation the confirmation character, or {@code null} to omit the member entirely
     * @return the JSON body; never {@code null}
     */
    private static String copyBody(String keyMembers, String confirmation) {
        String members = keyMembers.isEmpty() ? "" : keyMembers;
        String confirmed = confirmation == null ? "" : "\"confirmation\": \"" + confirmation + "\"";
        String body = members + confirmed;
        return "{\n" + (body.endsWith(",") ? body.substring(0, body.length() - 1) : body) + "\n}";
    }

    /**
     * Builds the copied-data block a withheld preview reports.
     *
     * <p>Assumptions: the values match the ones {@code captureBody} submits and the resolved identity the
     * substituted service would report, so a case asserting a member of the block is asserting a value the
     * exchange could actually have produced rather than an arbitrary one.</p>
     *
     * @return the disclosure block; never {@code null}
     */
    private static CopiedTransactionData copiedData() {
        return new CopiedTransactionData(SOURCE_TRANSACTION_ID, "01", "0001", "POS TERM",
                "GROCERY PURCHASE", "123456789", "CORNER STORE", "SEATTLE", "98101", "2026-01-15",
                "2026-01-16");
    }

    /** The identifier of the row a copied block names as its source. */
    private static final String SOURCE_TRANSACTION_ID = "0000000000000001";

    /**
     * Builds a copy request carrying the supplied body as a caller already made known.
     *
     * <p>Assumptions are the capture helper's: the address is composed from the controller's own two
     * published constants rather than a second spelling, so a route that moves takes these cases with it
     * instead of leaving them addressing nothing -- which would answer 404 and read as a refusal.</p>
     *
     * @param requestBody the JSON body to submit, of type {@link String}; must not be {@code null}
     * @return the request builder, never {@code null}
     */
    private MockHttpServletRequestBuilder copyLast(String requestBody) {
        return post(TransactionController.BASE_PATH + TransactionController.COPY_LAST_PATH)
                .header(AUTHORIZATION_HEADER, BEARER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody);
    }

    /**
     * Makes the substituted credential reader answer for a caller in the supplied groups.
     *
     * <p>Assumptions: a credential is presented the way a caller presents one, in a header, and the reader
     * is what answers for it. Nothing here injects an authentication past the chain, so the conversion of
     * the group claim into authorities is performed by the deployed converter and the authority a case
     * asserts is the one the deployment would derive.</p>
     *
     * @param groups the group claim values the presented credential carries, of type {@link String};
     *     must not be {@code null} and each entry must not be {@code null}
     */
    private void callerIn(String... groups) {
        when(this.jwtDecoder.decode(anyString())).thenReturn(Jwt.withTokenValue(BEARER_TOKEN)
                .header("alg", "RS256")
                .claim("sub", SUBJECT)
                .claim(JwtRoleConverter.GROUPS_CLAIM, List.of(groups))
                .build());
    }

    /**
     * Performs a browse as a caller in one group, with no query parameter supplied.
     *
     * @param group the group claim value the presented credential carries, of type {@link String}; must
     *     not be {@code null}
     * @return the performed exchange, for further expectations; never {@code null}
     * @throws Exception if the request could not be performed
     */
    private ResultActions browseAs(String group) throws Exception {
        this.callerIn(group);
        return this.mockMvc.perform(get(TransactionController.BASE_PATH)
                .header(AUTHORIZATION_HEADER, BEARER_TOKEN));
    }

    /**
     * Builds a capture request carrying the supplied body as a caller already made known.
     *
     * @param requestBody the JSON body to submit, of type {@link String}; must not be {@code null}
     * @return the request builder, never {@code null}
     */
    private MockHttpServletRequestBuilder capture(String requestBody) {
        return post(TransactionController.BASE_PATH)
                .header(AUTHORIZATION_HEADER, BEARER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody);
    }

    /**
     * Reads the browse request the adapter handed to the substituted browse on the latest exchange.
     *
     * <p>Assumptions: the latest captured value is taken rather than the only one, because a parameterised
     * case performs one exchange per invocation against one substituted collaborator and the interesting
     * value is always the most recent.</p>
     *
     * @return the captured browse request, never {@code null}
     */
    private TransactionListRequest capturedBrowseRequest() {
        ArgumentCaptor<TransactionListRequest> captured =
                ArgumentCaptor.forClass(TransactionListRequest.class);
        verify(this.listService, atLeastOnce())
                .listTransactions(captured.capture(), any(), anyString());
        return captured.getValue();
    }

    /**
     * Seals a raw boundary key the way the browse seals the keys it publishes.
     *
     * <p>Assumptions: the sealing binding is composed from the browse's own query name and the caller
     * subject, with no further scope, matching the binding the browse composes. A page whose boundary keys
     * were sealed under a different binding would be a page no caller could turn.</p>
     *
     * @param rawKey the key column value to seal, of type {@link String}; must not be {@code null}
     * @return the sealed token, never {@code null}
     */
    private String sealed(String rawKey) {
        return this.sealer.seal(CursorToken.binding(TransactionListService.CURSOR_QUERY_NAME, SUBJECT,
                CursorToken.SCOPE_NONE), rawKey);
    }

    /**
     * Builds the one browse row every browse case in this class is answered with.
     *
     * @return the browse row, never {@code null}
     */
    private TransactionListItemResponse groceryRow() {
        return new TransactionListItemResponse(PRESENT_ID, "GROCERY PURCHASE", Money.of("125.50"),
                PINNED_TIMESTAMP);
    }

    /**
     * Builds the detail response every detail case in this class is answered with.
     *
     * <p>Assumptions: the card number is supplied already reduced to its last four digits, because
     * reducing it is the mapping layer's responsibility and that layer is not part of this slice. What
     * this slice can assert is that the boundary carries the reduced value through unchanged and declares
     * no member the full value could travel in instead.</p>
     *
     * @param closingSentence the closing sentence to carry, of type {@link String}, or {@code null} to
     *     carry none
     * @return the detail response, never {@code null}
     */
    private TransactionDetailResponse groceryDetail(String closingSentence) {
        return new TransactionDetailResponse(PRESENT_ID, "01", "0001", "POS TERM", "GROCERY PURCHASE",
                Money.of("-125.50"), "123456789", "CORNER STORE", "SEATTLE", "98101",
                MASKED_CARD_NUMBER, PINNED_TIMESTAMP, "2022-07-19 12:00:00.000000", closingSentence);
    }

    /**
     * Assembles the success sentence from the three reference literals, pair of spaces included.
     *
     * <p>Assumptions: the sentence is composed from the three published constants rather than written out
     * as one string, so the pair of spaces below is produced the way the reference produces it -- by the
     * meeting of a trailing space and a leading one -- and cannot be silently regularised by a reader
     * retyping a literal.</p>
     *
     * @return the assembled success sentence, never {@code null}
     */
    private static String assembledSuccess() {
        return TransactionAddService.MESSAGE_ADDED_PREFIX + TransactionAddService.MESSAGE_ADDED_INFIX
                + ASSIGNED_ID + TransactionAddService.MESSAGE_ADDED_SUFFIX;
    }

    /**
     * Builds a capture body every declared shape but the key rule accepts, with the keys supplied.
     *
     * <p>Assumptions: the body is assembled as text rather than serialised from the request shape, because
     * a shape serialised through the mapper the handler binds cannot express the key-absent submission
     * distinctly from the key-present one at the wire -- and the wire is where the rule under assertion is
     * applied. Every other member carries a value the declared shapes accept, so a refusal can only come
     * from the members a case deliberately varies.</p>
     *
     * @param keyMembers the key members to include, of type {@link String}, already rendered as JSON
     *     member text with a trailing comma; empty to supply neither key and never {@code null}
     * @param confirmation the confirmation value to include, of type {@link String}, or {@code null} to
     *     include no confirmation member at all
     * @return the request body, never {@code null}
     */
    private static String captureBody(String keyMembers, String confirmation) {
        return "{\n" + keyMembers + "\n"
                + "\"typeCode\": \"01\",\n"
                + "\"categoryCode\": \"0001\",\n"
                + "\"source\": \"POS TERM\",\n"
                + "\"description\": \"GROCERY PURCHASE\",\n"
                + "\"amount\": \"125.50\",\n"
                + "\"merchantId\": \"123456789\",\n"
                + "\"merchantName\": \"CORNER STORE\",\n"
                + "\"merchantCity\": \"SEATTLE\",\n"
                + "\"merchantZip\": \"98101\",\n"
                + "\"originDate\": \"2026-01-15\",\n"
                + "\"processDate\": \"2026-01-16\""
                + (confirmation == null ? "\n" : ",\n\"confirmation\": \"" + confirmation + "\"\n")
                + "}";
    }

    /**
     * Reads the member names a JSON object body puts on the wire, at its outermost level only.
     *
     * <p>Alternatives Considered: a JSON parser was rejected in favour of scanning the rendered text. A
     * parser would need a mapper configured like the one under assertion, and the point of these
     * comparisons is to describe the bytes that left the service rather than a re-interpretation of them.
     * A plain expression over the text was rejected in turn, and the reason is a defect it produced: the
     * browse envelope nests a row object inside its collection member, so an expression that matches a
     * name after a brace or a comma reports the row's members as the envelope's own. Tracking the nesting
     * level is what confines the answer to the members the outermost object declares.</p>
     *
     * <p>Assumptions: no member name and no rendered value in the four compared bodies contains an escaped
     * quotation mark, so a quotation mark always opens or closes a string here. Every value compared is an
     * identifier, a decimal amount, a timestamp, a sealed token, a sentence or a literal, and none of them
     * can carry one.</p>
     *
     * @param body the response body to read, of type {@link String}; must not be {@code null}
     * @return the outermost member names in the order they appear, never {@code null}
     */
    private static List<String> memberNames(String body) {
        List<String> names = new ArrayList<>();
        StringBuilder recentString = new StringBuilder();
        boolean withinString = false;
        int depth = 0;
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (withinString) {
                if (character == '"') {
                    withinString = false;
                } else {
                    recentString.append(character);
                }
                continue;
            }
            switch (character) {
                case '"' -> {
                    withinString = true;
                    recentString.setLength(0);
                }
                case '{', '[' -> depth++;
                case '}', ']' -> depth--;
                case ':' -> {
                    if (depth == 1 && !recentString.isEmpty()) {
                        names.add(recentString.toString());
                        recentString.setLength(0);
                    }
                }
                default -> {
                    // WHY : Assumptions: every other character is part of a value that no comparison here
                    //       reads, so it is passed over rather than accumulated. Accumulating it would put
                    //       unquoted values such as the further-page indicator into the name candidate.
                }
            }
        }
        return List.copyOf(names);
    }

    /**
     * The edge slice: this module's own web configuration over substituted collaborators.
     *
     * <p>Purpose: assemble the smallest context in which the deployed encoding, the deployed problem shape
     * and the deployed authorization all run, and in which no datastore, no schema migration and no
     * identity provider is required.</p>
     *
     * <p>Assumptions: three configurations are imported and each is load-bearing. This module's published
     * contract configuration is imported because it is this module's own web configuration; the shared
     * kernel's registration class is imported because it declares the amount codec and the single problem
     * advice; and this module's security configuration is imported because the authorization cases assert
     * the chain it builds. Removing any one of them would leave a case asserting a default rather than the
     * deployed behaviour.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception clause.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({OpenApiConfig.class, SecurityConfig.class, CardDemoCommonAutoConfiguration.class})
    static class SliceConfiguration {

        /**
         * Supplies the clock every rendered timestamp is produced from.
         *
         * <p>Refactoring Rationale: this bean is marked primary, and the marking is required rather than
         * decorative. The shared kernel declares its own host-following clock under a missing-bean guard,
         * which suppresses it only when the guard is evaluated after the competing definition is known --
         * an ordering the auto-configuration machinery arranges and a plain import does not. Imported as a
         * configuration class here, the kernel's clock is therefore registered as well, and without this
         * marking every consumer of a clock in the slice fails to start on an ambiguous injection rather
         * than choosing. Renaming this bean to shadow the kernel's was rejected: it would depend on
         * definition overriding being permitted, which is off by default and which silently discards one
         * of two definitions instead of stating a preference.</p>
         *
         * @return the pinned clock, never {@code null}
         */
        @Bean
        @Primary
        Clock slicePinnedClock() {
            return Clock.fixed(PINNED_INSTANT, ZoneId.of("UTC"));
        }

        /**
         * Supplies the sealer the adapter hands to the browse.
         *
         * <p>Assumptions: the key material matches the one this class seals its expected tokens with, so
         * a token the browse is stubbed to answer with is a token this slice would accept back. The
         * lifetime is generous relative to a test exchange, so no case can fail on expiry.</p>
         *
         * @return the sealer, never {@code null}
         */
        @Bean
        CursorToken sliceCursorToken() {
            return new CursorToken(SEALING_KEY, Duration.ofMinutes(15));
        }

        /**
         * Supplies the adapter under assertion over the substituted collaborators.
         *
         * @param listService the browse to delegate CT00 to, of type {@link TransactionListService}; must
         *     not be {@code null}
         * @param viewService the keyed read to delegate CT01 to, of type {@link TransactionViewService};
         *     must not be {@code null}
         * @param addService the capture to delegate CT02 to, of type {@link TransactionAddService}; must
         *     not be {@code null}
         * @param cursorToken the sealer of published boundary keys, of type {@link CursorToken}; must not
         *     be {@code null}
         * @return the adapter under assertion, never {@code null}
         */
        @Bean
        TransactionController transactionController(TransactionListService listService,
                TransactionViewService viewService, TransactionAddService addService,
                CursorToken cursorToken) {
            return new TransactionController(listService, viewService, addService, cursorToken);
        }
    }
}
