package com.carddemo.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.account.config.SecurityConfig;
import com.carddemo.account.dto.AccountUpdateRequest;
import com.carddemo.account.dto.AccountUpdateResponse;
import com.carddemo.account.dto.AccountViewResponse;
import com.carddemo.account.dto.CardXrefResponse;
import com.carddemo.account.service.AccountUpdateService;
import com.carddemo.account.service.AccountViewService;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds the wire contract of the account view and account update surfaces.
 *
 * <p>Purpose: this class asserts what a caller of {@link AccountController} observes -- the status, the
 * headers, the serialised body, the per-field refusal array and the authority a route demands -- for the
 * two end-user operations this controller migrates, plus the by-account cross-reference walk that hangs
 * off the same account. The account view is specified by {@code app/cbl/COACTVWC.cbl}, 941 lines, reached
 * as transaction {@code CAVW} defined at {@code app/csd/CARDDEMO.CSD} L317 naming program
 * {@code COACTVWC} on L318. The account update is specified by {@code app/cbl/COACTUPC.cbl}, 4236 lines,
 * reached as transaction {@code CAUP} defined on L306 naming {@code COACTUPC} on L308. Both programs are
 * reference-only: every expected value below was read from them and is cited by path and physical line,
 * and neither is modified.
 *
 * <p>Assumptions: NO EXECUTABLE PARITY ORACLE EXISTS for either program, so every case below was authored
 * from the COBOL paragraphs directly and none of them may be described as agreeing with a golden master.
 * {@code tests/README.md} L83 to L85 records that the online {@code CO*} CICS programs cannot run end to
 * end without a CICS runtime, which the build host does not have, and that only their extractable
 * field-validation logic is unit-tested. The catalogue of verbatim business rules in that same
 * {@code tests/README.md} opens at its L553 and names neither {@code COACTVWC} nor {@code COACTUPC}
 * anywhere after that line, so no golden output for either program exists to compare with. These assertions
 * therefore ENCODE the documented rules and are strictly additive to that suite, which is reference-only
 * and is neither modified nor re-pinned nor reached by any path from here.
 *
 * <p>Alternatives Considered: the servlet slice annotation is the obvious wiring for a class like this
 * one and is unavailable on this module's test class path. Spring Boot 4 moved it out of
 * {@code spring-boot-test-autoconfigure} -- whose 4.1.0 artifact carries forty-two entries and no servlet
 * slice at all -- into a separate artifact that neither {@code services/account-service/pom.xml} nor
 * {@code spring-boot-starter-test} brings in, so the annotation cannot be resolved here and declaring it
 * would not compile. Adding the artifact would mean editing a POM this class does not own. The slice is
 * therefore assembled from types that ARE on the path, which is also what the charter in
 * {@code package-info.java} beside this file settles for a wire-contract class and what the sibling
 * dispatcher test already does, so a reader moving between the two meets one shape.
 *
 * <p>Alternatives Considered: a full application context was rejected for the reason that charter
 * records. It would start the persistence layer and the schema migration to assert status codes, body
 * members and message sentences, and it would create the token-decoder bean
 * {@code config/SecurityConfig.java} declares, whose factory resolves the issuer document while the
 * context refreshes and therefore cannot start on a host with no route to the pinned issuer.
 *
 * <p>Assumptions: the two participants that decide the headline assertions are the DEPLOYED ones, not
 * substitutes, and both have to be registered by hand because a deployment obtains them from
 * auto-configuration rather than from an import. {@code AccountApplication} declares no bean method and
 * no import at all; {@code services/common-lib/.../CardDemoCommonAutoConfiguration.java} contributes the
 * money Jackson module at its L227 under the missing-bean guard on its L225, and the shared error advice
 * at its L516 under the guard on its L515 inside a servlet-conditional block opening at its L498. A slice
 * assembled by hand applies no auto-configuration, so this class installs the real module on the message
 * converter and registers the real advice as the dispatcher's advice. Nothing here declares an advice of
 * its own, converts an exception to a status of its own, or serialises an amount by any other means: the
 * conflict mapping belongs to {@code com.carddemo.common.error.GlobalExceptionHandler} and the quoting of
 * an amount belongs to {@code com.carddemo.common.money.MoneyModule}, and re-implementing either would
 * assert this class's behaviour rather than the deployed behaviour.
 *
 * <p>Trade-offs: two dispatchers are built rather than one. The first carries no filter chain and answers
 * every body, header and refusal assertion; the second carries the deployed chain and answers only the
 * authority questions. Building one dispatcher for both would have meant paying for security filtering on
 * every case that has nothing to say about authority, and -- because the second dispatcher enables the
 * framework's own MVC defaults rather than this class's converter -- would have put the money-quoting
 * assertions behind a converter that does not carry the module. Keeping them apart is what lets each
 * assertion name exactly one cause of failure.
 *
 * <p>Parameters, return values, exceptions or errors. A test class is instantiated by the engine, takes no
 * parameter, yields no value and raises nothing, so no such at-clause is carried; the inapplicability is
 * stated rather than passed over so a reader can tell it from an oversight.
 */
class AccountControllerTest {

    /**
     * An account identifier inside the eleven-digit width the account record declares.
     *
     * <p>Assumptions: the width is the one at {@code app/cpy/CVACT01Y.cpy} L5, which declares
     * {@code 05  ACCT-ID  PIC 9(11).}, and the value is non-zero so that it survives the reference's own
     * filter edit at {@code app/cbl/COACTVWC.cbl} L666 and L667 rather than being refused before the
     * read.</p>
     */
    private static final long ACCOUNT_ID = 12_345_678_901L;

    /**
     * The eleven-character key rendering the controller submits to the reference's edits.
     *
     * <p>Assumptions: the controller renders the bound number left-zero-padded to the declared width
     * before asking either edit about it, so a stub keyed on anything else is never reached. Stating the
     * rendered form here once keeps every stub in this class keyed on the same characters.</p>
     */
    private static final String ACCOUNT_KEY = "12345678901";

    /**
     * The revision the read path publishes and an update must return.
     *
     * <p>Assumptions: an opaque token rather than a number, because the controller only ever renders it
     * into an entity tag and compares the value inside; nothing here depends on it being ordered.</p>
     */
    private static final String REVISION = "7";

    /**
     * The instant every rendered problem body is stamped from.
     *
     * <p>Assumptions: a pinned instant rather than the host clock, so a body that carries a timestamp is
     * byte-stable across runs and a failure reports a real difference rather than the time of day.</p>
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-18T00:00:00Z");

    /**
     * The nine digits the fixture's national identifier would carry if it were ever returned unmasked.
     *
     * <p>Assumptions: composed from the three parts the submission fixture sends -- the identifier arrives
     * SPLIT three, two and four, so its unmasked form is the concatenation of those parts. Naming it once
     * keeps the leak assertion and the fixture that would leak it from drifting apart, which is the whole
     * value of the assertion: a run written as a literal beside the assertion could stop matching the
     * fixture and the case would then pass while proving nothing.</p>
     */
    private static final String UNMASKED_NATIONAL_IDENTIFIER = "111226789";

    /**
     * The width the account view's informational channel is composed in, forty characters.
     *
     * <p>Assumptions: read from {@code 05  WS-INFO-MSG  PIC X(40).} at {@code app/cbl/COACTVWC.cbl} L110.
     * The number is written here once so that the two literals asserted against it cannot be measured
     * against two different figures in two cases.</p>
     */
    private static final int INFORMATION_CHANNEL_WIDTH = 40;

    /**
     * The width the aggregate return channel is composed in, seventy-five characters.
     *
     * <p>Assumptions: read from {@code 05  WS-RETURN-MSG  PIC X(75).} at {@code app/cbl/COACTVWC.cbl}
     * L117 and from the identically declared field at {@code app/cbl/COACTUPC.cbl} L479, and corroborated
     * by {@code 10  CCARD-RETURN-MSG  PIC X(75).} at {@code app/cpy/CVCRD01Y.cpy} L29.</p>
     */
    private static final int AGGREGATE_CHANNEL_WIDTH = 75;

    /**
     * The dispatcher path of the account view operation.
     */
    private static final String VIEW_PATH = AccountController.BASE_PATH + "/{accountId}/view";

    /**
     * The dispatcher path of the account update operation.
     */
    private static final String UPDATE_PATH = AccountController.BASE_PATH + "/{accountId}";

    /**
     * The dispatcher path of the by-account cross-reference walk.
     */
    private static final String XREF_PATH =
            AccountController.BASE_PATH + "/{accountId}/card-cross-references";

    /**
     * The read path the controller under assertion is built over, substituted.
     */
    private static AccountViewService reads;

    /**
     * The write path the controller under assertion is built over, substituted.
     */
    private static AccountUpdateService writes;

    /**
     * The context backing the dispatcher that carries the deployed filter chain.
     */
    private static AnnotationConfigWebApplicationContext guardedContext;

    /**
     * The dispatcher that carries the deployed filter chain, used only for authority questions.
     */
    private static MockMvc guarded;

    /**
     * The dispatcher that carries no filter chain, used for every body, header and refusal assertion.
     */
    private MockMvc mockMvc;

    /**
     * The mapper the expected request bodies in this class are written with.
     */
    private JsonMapper jsonMapper;

    /**
     * Builds the context and dispatcher that carry the deployed filter chain, once for the class.
     *
     * <p>Trade-offs: the guarded context is built ONCE rather than per test, because assembling a
     * security-enabled context is the most expensive thing this class does and no case below mutates it.
     * What is given up is per-test isolation of its two substituted collaborators, which is bought back
     * by resetting them before each case.</p>
     */
    @BeforeAll
    static void buildGuardedDispatcher() {
        reads = mock(AccountViewService.class);
        writes = mock(AccountUpdateService.class);

        guardedContext = new AnnotationConfigWebApplicationContext();
        guardedContext.setServletContext(new MockServletContext());
        guardedContext.register(GuardedSliceWiring.class);
        guardedContext.refresh();

        // WHAT: assembles a servlet context carrying the controller, the shared advice and the deployed
        //       filter chain, then wraps it in a dispatcher whose requests traverse that chain.
        // WHY : Assumptions: the configurer form is used rather than adding the chain filter by hand,
        //       because the request post-processor that mints an authentication publishes it through the
        //       test context repository this configurer installs. Adding the filter alone would leave
        //       every minted token invisible to the chain, so a granted case would be refused and the
        //       assertion would report an authority failure that the wiring, not the rule, had caused.
        guarded = MockMvcBuilders.webAppContextSetup(guardedContext)
                .apply(springSecurity())
                .build();
    }

    /**
     * Releases the guarded context so the class leaves no started context behind.
     */
    @AfterAll
    static void closeGuardedDispatcher() {
        guardedContext.close();
    }

    /**
     * Builds the unguarded dispatcher and returns both substituted collaborators to a clean state.
     */
    @BeforeEach
    void buildDispatcher() {
        reset(reads, writes);

        // WHAT: builds a dispatcher over the one controller under assertion, carrying the deployed
        //       Jackson module and the deployed error advice and no security filtering at all.
        // WHY : Assumptions: the real money module is installed on the converter because the account
        //       view carries five amounts and each has to leave as a JSON STRING. Without it they would
        //       serialise as JSON numbers and the assertions below would pass against a representation a
        //       client parses into IEEE-754 binary floating point, which is the exactness loss the
        //       migration's fixed-point rule exists to prevent. The module binds its handlers to the
        //       amount type itself, so installing it changes no other property of any payload.
        this.jsonMapper = JsonMapper.builder().addModule(new MoneyModule()).build();
        JacksonJsonHttpMessageConverter converter =
                new JacksonJsonHttpMessageConverter(this.jsonMapper);

        // WHY : Assumptions: the shared advice is REGISTERED, never re-implemented. The conflict status
        //       and the sentence it carries belong to com.carddemo.common.error.GlobalExceptionHandler,
        //       so a dispatcher without it would answer a contention with 500 and the failure would read
        //       as the controller misbehaving rather than as a missing participant in this wiring.
        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new AccountController(reads, writes))
                .setMessageConverters(converter)
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    /**
     * Every one of the account view's five amounts leaves the service quoted, at scale two.
     *
     * <p>Purpose: the account grouping of the view response carries exactly five amount-typed members,
     * mirroring the five signed-decimal money fields the account record declares -- the posted balance at
     * {@code app/cpy/CVACT01Y.cpy} L7, the credit limit on L8, the cash credit limit on L9, the current
     * cycle credit on L13 and the current cycle debit on L14, each {@code PIC S9(10)V99} within the
     * 300-byte record whose group item opens at L4. This case pins all five at once, and pins a negative,
     * a zero and a whole amount among them so the scale-two rendering is pinned in all three shapes.</p>
     *
     * <p>Alternatives Considered: asserting these values off a DESERIALISED response object was evaluated
     * and rejected, and the reason is that it cannot fail for the thing that matters. A reader of the
     * parsed document, and equally a path expression evaluated over it, coerces {@code 193.00} and
     * {@code "193.00"} to the same comparison result, so an assertion written that way passes unchanged
     * on the day the wire form stops being a string. It matters because a JSON number is parsed into
     * IEEE-754 binary floating point by most clients, which destroys exactness at the boundary the user
     * actually sees -- a balance is the one value in this payload a caller may not re-round. The raw
     * response bytes are therefore asserted to carry each amount INSIDE quotation marks, and the pattern
     * assertion beside it fails if any of the five is ever followed by a bare digit or sign.</p>
     *
     * @throws Exception if the request cannot be performed or the response body cannot be read
     */
    @Test
    @DisplayName("the account view carries all five amounts as quoted JSON strings at scale two")
    void theViewCarriesEveryAmountAsAQuotedString() throws Exception {
        when(reads.accountFilterFieldErrors(ACCOUNT_KEY)).thenReturn(List.of());
        when(reads.readAccountView(ACCOUNT_ID)).thenReturn(viewResponse());
        when(writes.currentRevision(ACCOUNT_ID)).thenReturn(REVISION);

        MvcResult result = this.mockMvc.perform(get(VIEW_PATH, ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.currentBalance").value("-193.00"))
                .andExpect(jsonPath("$.account.creditLimit").value("1500.00"))
                .andExpect(jsonPath("$.account.cashCreditLimit").value("0.00"))
                .andExpect(jsonPath("$.account.currentCycleCredit").value("250.75"))
                .andExpect(jsonPath("$.account.currentCycleDebit").value("1000.00"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("each amount must appear between quotation marks in the response bytes")
                .contains("\"currentBalance\":\"-193.00\"")
                .contains("\"creditLimit\":\"1500.00\"")
                .contains("\"cashCreditLimit\":\"0.00\"")
                .contains("\"currentCycleCredit\":\"250.75\"")
                .contains("\"currentCycleDebit\":\"1000.00\"");
    }

    /**
     * No amount in the account view is ever emitted as a bare JSON number.
     *
     * <p>Assumptions: this is the negative half of the case above and is written separately because the
     * two fail for different reasons -- that one fails when a value is wrong, this one fails when the
     * TYPE is wrong while the value still reads correctly. The pattern looks for an amount member
     * followed by a digit or a sign rather than by a quote, which is the exact byte sequence a numeric
     * rendering would produce, and it also rules out a whole amount losing its cents or an amount
     * arriving in exponent form.</p>
     *
     * @throws Exception if the request cannot be performed or the response body cannot be read
     */
    @Test
    @DisplayName("no account-view amount is emitted as a bare JSON number")
    void noAmountIsEmittedAsABareJsonNumber() throws Exception {
        when(reads.accountFilterFieldErrors(ACCOUNT_KEY)).thenReturn(List.of());
        when(reads.readAccountView(ACCOUNT_ID)).thenReturn(viewResponse());
        when(writes.currentRevision(ACCOUNT_ID)).thenReturn(REVISION);

        String body = this.mockMvc.perform(get(VIEW_PATH, ACCOUNT_ID))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .as("an amount member followed by anything other than a quote is a numeric rendering")
                .doesNotContainPattern("\"currentBalance\"\\s*:\\s*[-+0-9]")
                .doesNotContainPattern("\"creditLimit\"\\s*:\\s*[-+0-9]")
                .doesNotContainPattern("\"cashCreditLimit\"\\s*:\\s*[-+0-9]")
                .doesNotContainPattern("\"currentCycleCredit\"\\s*:\\s*[-+0-9]")
                .doesNotContainPattern("\"currentCycleDebit\"\\s*:\\s*[-+0-9]");
        assertThat(body)
                .as("a whole amount keeps its two decimal places and never arrives in exponent form")
                .doesNotContain("\"creditLimit\":\"1500\"")
                .doesNotContain("1.5E3")
                .doesNotContain("1.5E+3");
    }

    /**
     * The account view returns both protected customer identifiers masked and declares no card
     * verification value at all.
     *
     * <p>Purpose: the two protected members of the customer grouping are the national identifier, declared
     * {@code 05  CUST-SSN  PIC 9(09).} at {@code app/cpy/CVCUS01Y.cpy} L17, and the government-issued
     * identifier, declared {@code 05  CUST-GOVT-ISSUED-ID  PIC X(20).} on L18. Both reach a caller masked,
     * and this case asserts the masked form rather than the presence of a member so that an unmasked
     * value cannot pass.</p>
     *
     * <p>Assumptions: the ABSENCE of a card verification value is asserted instead of its masking, because
     * this bounded context has none to mask. The three records it owns declare no such field anywhere --
     * {@code app/cpy/CVACT01Y.cpy} is 20 lines, {@code app/cpy/CVCUS01Y.cpy} is 26 and
     * {@code app/cpy/CVACT03Y.cpy} is 11, and none of them carries one -- and
     * {@code src/main/resources/db/migration/V1__account.sql} creates no such column, so there is nothing
     * for a mapper to suppress here and nothing for a response to leak. Asserting a mask would assert a
     * member that cannot exist, which would pass forever and prove nothing; asserting the absence fails
     * the day one is introduced without a masking decision being taken.</p>
     *
     * <p>Assumptions: this case verifies the OBSERVABLE output and not the mechanism. The controller
     * imports no entity and injects no mapper, so masking and encryption are an upstream invariant of the
     * transaction that reads the rows, and the mechanism is asserted in the sibling mapper test package.
     * Every value below is obviously synthetic; no real credential, endpoint or identifier appears.</p>
     *
     * @throws Exception if the request cannot be performed or the response body cannot be read
     */
    @Test
    @DisplayName("the account view masks both protected identifiers and declares no verification value")
    void theViewMasksProtectedIdentifiersAndDeclaresNoVerificationValue() throws Exception {
        when(reads.accountFilterFieldErrors(ACCOUNT_KEY)).thenReturn(List.of());
        when(reads.readAccountView(ACCOUNT_ID)).thenReturn(viewResponse());
        when(writes.currentRevision(ACCOUNT_ID)).thenReturn(REVISION);

        String body = this.mockMvc.perform(get(VIEW_PATH, ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.ssnMasked").value("***-**-6789"))
                .andExpect(jsonPath("$.customer.governmentIssuedIdMasked").value("****4321"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // WHY : Assumptions: the digit run asserted absent is the UNMASKED national identifier of the
        //       fixture, not the account key. The account key legitimately appears in the response and
        //       happens to contain a nine-digit run of its own, so asserting against that run would fail
        //       on a correctly masked response and would have to be weakened until it proved nothing.
        assertThat(body)
                .as("no digit sequence of the unmasked national identifier may survive")
                .doesNotContain(UNMASKED_NATIONAL_IDENTIFIER);
        assertThat(componentNamesOf(AccountViewResponse.CustomerDetail.class))
                .as("the customer grouping declares no card verification member to mask")
                .noneMatch(AccountControllerTest::namesACardVerificationValue);
        assertThat(componentNamesOf(AccountViewResponse.AccountDetail.class))
                .as("the account grouping declares no card verification member to mask")
                .noneMatch(AccountControllerTest::namesACardVerificationValue);
        assertThat(componentNamesOf(AccountViewResponse.class))
                .as("the enclosing view declares no card verification member to mask")
                .noneMatch(AccountControllerTest::namesACardVerificationValue);
    }

    /**
     * The account view carries both message channels inside the widths the PROGRAM declares.
     *
     * <p>Purpose: the view composes its two operator sentences in fields of its own rather than in the
     * screen's. {@code app/cbl/COACTVWC.cbl} declares {@code 05  WS-INFO-MSG  PIC X(40).} at L110 -- the
     * informational channel, whose two literals are {@code 'Enter or update id of account to display'} at
     * L113 with L114 and {@code 'Displaying details of given Account'} at L115 with L116 -- and
     * {@code 05  WS-RETURN-MSG  PIC X(75).} at L117, with its unset state at L118. The update side carries
     * the same 75-character aggregate at {@code app/cbl/COACTUPC.cbl} L479 with its unset state on L480.
     * The 75 is corroborated in the shared record: {@code app/cpy/CVCRD01Y.cpy} declares
     * {@code 10  CCARD-ERROR-MSG  PIC X(75).} on L28 and {@code 10  CCARD-RETURN-MSG  PIC X(75).} on L29,
     * with the sentinel on the return channel alone at L30. It does NOT come from
     * {@code app/cpy/CSMSG01Y.cpy}, which is 24 lines and a 50-character two-sentence regime at L18 with
     * L19 and L20 with L21.</p>
     *
     * <p>Trade-offs: the SCREEN-side widths were available and were not chosen. Both symbolic maps declare
     * wider fields -- {@code app/cpy-bms/COACTVW.CPY} L234 gives {@code INFOMSGI  PIC X(45).} and L240
     * gives {@code ERRMSGI  PIC X(78).}, and {@code app/cpy-bms/COACTUP.CPY} repeats both at L318 and L324
     * -- so asserting 45 and 78 would have admitted sentences the program could not have produced. Forty
     * and seventy-five are asserted because the program-side field is what CONSTRUCTS the sentence a user
     * reads, while the map field merely holds it for display; the compromise accepted is that a sentence
     * between the two pairs of widths would be reported here as a failure even though the terminal could
     * have shown it, which is the safer direction.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the account view keeps both channels inside the program-side widths of 40 and 75")
    void theViewKeepsBothChannelsInsideTheProgramSideWidths() throws Exception {
        when(reads.accountFilterFieldErrors(ACCOUNT_KEY)).thenReturn(List.of());
        when(reads.readAccountView(ACCOUNT_ID)).thenReturn(viewResponse());
        when(writes.currentRevision(ACCOUNT_ID)).thenReturn(REVISION);

        this.mockMvc.perform(get(VIEW_PATH, ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.informationMessage")
                        .value("Displaying details of given Account"))
                .andExpect(jsonPath("$.returnMessage").value(""));

        assertThat("Displaying details of given Account".length())
                .as("the informational sentence must fit the 40 characters COACTVWC L110 declares")
                .isLessThanOrEqualTo(INFORMATION_CHANNEL_WIDTH);
        assertThat("Enter or update id of account to display".length())
                .as("the prompt sentence must fit the same 40 characters")
                .isLessThanOrEqualTo(INFORMATION_CHANNEL_WIDTH);
        assertThat(ApiError.MESSAGE_RENDERING_WIDTH)
                .as("the shared aggregate width must be the 75 of COACTVWC L117 and COACTUPC L479")
                .isEqualTo(AGGREGATE_CHANNEL_WIDTH);
    }

    /**
     * The account view publishes the revision an update must return, as a weak entity tag.
     *
     * <p>Assumptions: the tag is WEAK because a revision asserts semantic equivalence rather than octet
     * equality -- two responses at one revision are the same account state but need not be the same bytes,
     * since the masked identifiers and the two message channels are assembled per response. The update
     * compares the value inside the tag, so this case pins the exact rendering the update will be handed
     * back.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the account view publishes the revision as a weak entity tag")
    void theViewPublishesTheRevisionAsAWeakEntityTag() throws Exception {
        when(reads.accountFilterFieldErrors(ACCOUNT_KEY)).thenReturn(List.of());
        when(reads.readAccountView(ACCOUNT_ID)).thenReturn(viewResponse());
        when(writes.currentRevision(ACCOUNT_ID)).thenReturn(REVISION);

        this.mockMvc.perform(get(VIEW_PATH, ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "W/\"" + REVISION + "\""));
    }

    /**
     * A stale precondition is answered with the reference's own changed-record sentence, at 409.
     *
     * <p>Purpose: the reference compares a complete before-image of the record across the
     * pseudo-conversational gap. It snapshots the pre-edit state into the group opening
     * {@code 05 ACUP-OLD-DETAILS.} at {@code app/cbl/COACTUPC.cbl} L669, whose account subgroup opens on
     * L670, and compares it against the group opening {@code 05 ACUP-NEW-DETAILS.} on L757. The Java
     * implements the same protection as a persistence revision carried in and out as an entity tag, and
     * that divergence in MECHANISM is documented rather than being presented as identical; the observable
     * outcome -- one caller's edit refused because another's landed first -- is what this case pins.</p>
     *
     * <p>Assumptions: the mapping from a contention to this status and this sentence is OWNED by
     * {@code com.carddemo.common.error.GlobalExceptionHandler} and is not re-implemented here. This class
     * declares no advice of its own, catches no persistence failure in order to convert it, and asserts
     * only what the registered advice emits. Asserting the status and the text is therefore asserting the
     * deployed mapping; converting the failure here instead would have asserted this class.</p>
     *
     * <p>Assumptions: the sentence is asserted as the EMITTED literal rather than a normalised one, because
     * the emitted literal is what the user reads. It is
     * {@code 'Record changed by some one else. Please review'}, declared at {@code app/cbl/COACTUPC.cbl}
     * L521 with its value on L522, and it is reproduced character for character -- with the TWO-WORD
     * spelling of "some one" and with no closing full stop -- because transformation rule T8 carries a
     * user-visible string across unchanged and nothing here may normalise its capitalisation, its spacing
     * or its punctuation. No character count is asserted alongside it: the string itself is the contract,
     * and a length written beside it would be a second, independently wrong figure. That condition sits on
     * the seventy-five-character return-message field declared on L479 with its unset state on L480, and
     * NOT on the one-character change flag at L168 whose only two conditions are on L169 and L170 -- two
     * different constructs that are never conflated.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a stale precondition is answered 409 with the reference changed-record sentence")
    void aStalePreconditionIsAnsweredWithTheChangedRecordSentence() throws Exception {
        when(writes.editAccountKey(ACCOUNT_KEY))
                .thenReturn(AccountUpdateService.EditOutcome.acceptable());
        when(writes.update(eq(ACCOUNT_ID), any(AccountUpdateRequest.class), anyString()))
                .thenThrow(new RecordConflictException(RecordConflictException.Kind.STALE_VERSION));

        this.mockMvc.perform(submit(REVISION, submission()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(ApiError.CONFLICT_STATUS))
                .andExpect(jsonPath("$.code").value(ApiError.CODE_CONFLICT))
                .andExpect(jsonPath("$.message")
                        .value("Record changed by some one else. Please review"));
    }

    /**
     * The three contention conditions the reference words differently stay distinguishable.
     *
     * <p>Assumptions: the reference does not answer every contention with one sentence, and neither does
     * the shared advice, so this case pins that they remain apart rather than collapsing into a single
     * generic conflict. {@code app/cbl/COACTUPC.cbl} words a failure to acquire a lock at L517 with L518
     * for the account row and at L519 with L520 for the customer row, the before-image mismatch at L521
     * with L522, and a failed rewrite at L523 with L524. All three arrive as 409 because the caller's
     * remedy is the same, and the sentence is what tells them apart; asserting only the status would let
     * two of them be merged without any case failing.</p>
     *
     * @throws Exception if either request cannot be performed
     */
    @Test
    @DisplayName("a lock-acquisition contention is worded differently from a stale precondition")
    void theContentionConditionsStayDistinguishable() throws Exception {
        when(writes.editAccountKey(ACCOUNT_KEY))
                .thenReturn(AccountUpdateService.EditOutcome.acceptable());
        when(writes.update(eq(ACCOUNT_ID), any(AccountUpdateRequest.class), anyString()))
                .thenThrow(new RecordConflictException(RecordConflictException.Kind.LOCK_UNAVAILABLE));

        String lockSentence = this.mockMvc.perform(submit(REVISION, submission()))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(lockSentence)
                .as("a lock-acquisition contention must not borrow the changed-record wording")
                .doesNotContain("Record changed by some one else. Please review")
                .contains(GlobalExceptionHandler.MESSAGE_LOCK_UNAVAILABLE);
        assertThat(GlobalExceptionHandler.MESSAGE_LOCK_UNAVAILABLE)
                .as("the two sentences are separate values and are never merged into one")
                .isNotEqualTo(GlobalExceptionHandler.MESSAGE_RECORD_CHANGED);
    }

    /**
     * A refused submission is answered 400 with one entry per offending property.
     *
     * <p>Purpose: transformation rule T7 turns the reference's per-field condition pattern into a
     * structured array on the response, and this case pins the ARITY of that array -- several offending
     * properties produce several entries in one response, so a caller learns the whole set in one round
     * trip rather than one member at a time. The reference maintains the equivalent by hand: the
     * highlight fragment at {@code app/cpy/CSSETATY.cpy} is expanded once per validated field throughout
     * {@code app/cbl/COACTUPC.cbl}.</p>
     *
     * <p>Assumptions: the entry type is the nested record the shared kernel publishes,
     * {@code ApiError.FieldError}, whose own constructor refuses a blank property name, a blank sentence
     * and a non-error state -- so no entry in this array can name a field that is actually acceptable.
     * Nothing here declares a per-field entry type of its own.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a refused submission answers 400 with one entry per offending property")
    void aRefusedSubmissionNamesEveryOffendingProperty() throws Exception {
        when(writes.editAccountKey(ACCOUNT_KEY))
                .thenReturn(AccountUpdateService.EditOutcome.acceptable());
        when(writes.update(eq(ACCOUNT_ID), any(AccountUpdateRequest.class), anyString()))
                .thenThrow(new ClientInputException(ApiError.CODE_VALIDATION,
                        List.of("activeStatus", "creditLimit", "expirationDateMonth"),
                        FieldValidationFlag.NOT_OK,
                        "Account Active Status must be Y or N"));

        this.mockMvc.perform(submit(REVISION, submission()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors.length()").value(3))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("activeStatus"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("creditLimit"))
                .andExpect(jsonPath("$.fieldErrors[2].field").value("expirationDateMonth"))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.NOT_OK.name()))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value("Account Active Status must be Y or N"));
    }

    /**
     * A malformed amount reaches the per-field array instead of failing the request binding.
     *
     * <p>Assumptions: every numeric-looking member of the submitted record is a digits-only
     * {@code String}, and that is a deliberate reading of the reference rather than a convenience. The
     * reference itself holds these values in BOTH representations over one storage and treats them as
     * characters on the wire and as numbers only in arithmetic:
     * {@code 15  ACUP-OLD-ACCT-ID-X  PIC X(11).} at {@code app/cbl/COACTUPC.cbl} L671 is redefined
     * numeric on L672 with L673 -- note the CHARACTER form carries the {@code -X} suffix there -- while
     * {@code 15  ACUP-OLD-CURR-BAL  PIC X(12).} on L675 is redefined on L676 with L677 as
     * {@code PIC S9(10)V99} under a {@code -N} suffix, the opposite convention in the same group; the
     * credit limit repeats the shape on L678 with L679 and L680, the cash credit limit on L681, and the
     * credit score on L754 with L755 and L756. The two symbolic maps then disagree about the type of the
     * SAME logical field at the SAME physical line: {@code app/cpy-bms/COACTVW.CPY} L60 declares
     * {@code 02  ACCTSIDI  PIC 99999999999.} while {@code app/cpy-bms/COACTUP.CPY} L60 declares
     * {@code 02  ACCTSIDI  PIC X(11).}, with the surrounding L56 to L59 and L61 to L62 byte-identical in
     * both files. The target takes the stricter update-map form, because a numeric-typed member would let
     * a document reader accept and silently reformat a value the reference treats as characters.</p>
     *
     * <p>Assumptions: the consequence asserted here is the one that follows from that choice. Because the
     * member is a {@code String}, a non-numeric amount BINDS successfully and is then refused by the
     * edit that owns the wording, so the caller receives a per-field entry naming the property -- not a
     * document-reader failure, and not an internal error.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a malformed amount is answered as a per-field refusal rather than a binding failure")
    void aMalformedAmountReachesTheFieldArray() throws Exception {
        when(writes.editAccountKey(ACCOUNT_KEY))
                .thenReturn(AccountUpdateService.EditOutcome.acceptable());
        when(writes.update(eq(ACCOUNT_ID), any(AccountUpdateRequest.class), anyString()))
                .thenThrow(new ClientInputException(ApiError.CODE_VALIDATION, "creditLimit",
                        FieldValidationFlag.NOT_OK, "Credit Limit is not valid"));

        AccountUpdateRequest malformed = submission("1,500.OO");

        this.mockMvc.perform(submit(REVISION, malformed))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("creditLimit"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Credit Limit is not valid"));

        // WHY : Assumptions: the malformed characters must have reached the write path unchanged, which is
        //       what proves the refusal came from the edit rather than from the reader. A reader that had
        //       rejected the body would have left the write path uncalled, and this class would then be
        //       asserting a refusal produced one layer earlier than the reference produces it.
        ArgumentCaptor<AccountUpdateRequest> submitted =
                ArgumentCaptor.forClass(AccountUpdateRequest.class);
        verify(writes).update(eq(ACCOUNT_ID), submitted.capture(), anyString());
        assertThat(submitted.getValue().creditLimit()).isEqualTo("1,500.OO");
    }

    /**
     * A value is carried inbound exactly as submitted, with no reformatting of any kind.
     *
     * <p>Assumptions: because the members are character strings rather than numbers, a leading zero
     * survives, grouping separators are neither added nor removed, and a value is not re-scaled on the
     * way in. This is the same reasoning the reference relies on when it normalises a screen field: the
     * sites at {@code app/cbl/COACTUPC.cbl} L1051 and L1419 test the raw characters against
     * {@code '*'} and against spaces before anything numeric happens, which is only meaningful if the
     * value is still characters at that point.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a submitted value is carried inbound unchanged, keeping leading zeroes")
    void aSubmittedValueIsCarriedInboundUnchanged() throws Exception {
        when(writes.editAccountKey(ACCOUNT_KEY))
                .thenReturn(AccountUpdateService.EditOutcome.acceptable());
        when(writes.update(eq(ACCOUNT_ID), any(AccountUpdateRequest.class), anyString()))
                .thenReturn(acceptedResponse());

        AccountUpdateRequest padded = submission("0000001500.00");

        this.mockMvc.perform(submit(REVISION, padded)).andExpect(status().isOk());

        ArgumentCaptor<AccountUpdateRequest> submitted =
                ArgumentCaptor.forClass(AccountUpdateRequest.class);
        verify(writes).update(eq(ACCOUNT_ID), submitted.capture(), anyString());
        assertThat(submitted.getValue().creditLimit())
                .as("no leading zero is stripped and no grouping separator is introduced")
                .isEqualTo("0000001500.00");
        assertThat(submitted.getValue().accountId())
                .as("the eleven-character key form survives the round trip")
                .isEqualTo(ACCOUNT_KEY);
    }

    /**
     * An accepted submission carries an empty, unmodifiable per-field array rather than an absent one.
     *
     * <p>Assumptions: the array is a required member with an empty state rather than an optional member,
     * so a client renders markers by iterating it without first testing for its presence. The response
     * record enforces both halves in its own constructor -- it refuses an absent array and publishes an
     * unmodifiable copy -- and the unmodifiability is asserted against the object rather than the document
     * because a serialised array cannot express it.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an accepted submission carries an empty and unmodifiable per-field array")
    void anAcceptedSubmissionCarriesAnEmptyUnmodifiableArray() throws Exception {
        when(writes.editAccountKey(ACCOUNT_KEY))
                .thenReturn(AccountUpdateService.EditOutcome.acceptable());
        when(writes.update(eq(ACCOUNT_ID), any(AccountUpdateRequest.class), anyString()))
                .thenReturn(acceptedResponse());
        when(writes.currentRevision(ACCOUNT_ID)).thenReturn("8");

        this.mockMvc.perform(submit(REVISION, submission()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors.length()").value(0))
                .andExpect(jsonPath("$.returnMessage")
                        .value(AccountUpdateService.MESSAGE_UPDATE_ACCEPTED))
                .andExpect(header().string(HttpHeaders.ETAG, "W/\"8\""));

        assertThatThrownBy(() -> acceptedResponse().fieldErrors()
                .add(new ApiError.FieldError("activeStatus", FieldValidationFlag.NOT_OK,
                        "Account Active Status must be Y or N")))
                .as("the published array must not be mutable by a holder of the response")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * The aggregate channel stays singular while the per-field array accumulates every refusal.
     *
     * <p>Purpose: the update response is DUAL-CHANNEL, and the two channels have different arities by
     * design. One aggregate sentence is latched -- the reference latches it under
     * {@code IF WS-RETURN-MSG-OFF} so the FIRST refusal wins the seventy-five-character field declared at
     * {@code app/cbl/COACTUPC.cbl} L479, the same latch the view performs at
     * {@code app/cbl/COACTVWC.cbl} L670 -- while every offending field still contributes its own entry.
     * The reference has thirty-six such per-field markers, one per validated screen field: the group
     * opening {@code 05 WS-NON-KEY-FLAGS.} at L191 through the last marker on L352 carries exactly
     * thirty-six blank conditions and exactly thirty-six not-acceptable conditions.</p>
     *
     * <p>Assumptions: this case is written on the ACCEPTED-status channel rather than the refusal channel
     * because only the response record can carry a different sentence per entry; the refusal shape carries
     * one sentence for the set. Both shapes are asserted, in this case and in the refusal case above, so
     * neither channel is left unexercised.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("one latched aggregate sentence accompanies a many-valued per-field array")
    void theAggregateChannelIsSingularWhileTheArrayAccumulates() throws Exception {
        when(writes.editAccountKey(ACCOUNT_KEY))
                .thenReturn(AccountUpdateService.EditOutcome.acceptable());
        when(writes.update(eq(ACCOUNT_ID), any(AccountUpdateRequest.class), anyString()))
                .thenReturn(new AccountUpdateResponse(ACCOUNT_KEY, null,
                        "Account Active Status must be Y or N",
                        List.of(new ApiError.FieldError("activeStatus", FieldValidationFlag.NOT_OK,
                                        "Account Active Status must be Y or N"),
                                new ApiError.FieldError("creditLimit", FieldValidationFlag.NOT_OK,
                                        "Credit Limit is not valid"),
                                new ApiError.FieldError("expirationDateYear",
                                        FieldValidationFlag.NOT_OK, "Invalid card expiry year")),
                        null, null));
        when(writes.currentRevision(ACCOUNT_ID)).thenReturn(REVISION);

        this.mockMvc.perform(submit(REVISION, submission()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnMessage").isString())
                .andExpect(jsonPath("$.returnMessage")
                        .value("Account Active Status must be Y or N"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(3))
                .andExpect(jsonPath("$.fieldErrors[1].message").value("Credit Limit is not valid"))
                .andExpect(jsonPath("$.fieldErrors[2].message").value("Invalid card expiry year"));
    }

    /**
     * The absent-field marker belongs to the blank state alone, across both of the reference's regimes.
     *
     * <p>Purpose: the reference spells "absent" two structurally different ways inside one program, and
     * the shared state carries both without a caller having to know which regime a field came from. The
     * KEY filters use a SPACE for absent -- {@code 05  WS-EDIT-ACCT-FLAG  PIC X(1).} at
     * {@code app/cbl/COACTUPC.cbl} L183 with its acceptable value on L184, its not-acceptable value on
     * L185 and its blank value {@code ' '} on L186, and {@code WS-EDIT-CUST-FLAG} repeating the shape from
     * L187 -- while the NON-KEY fields use the letter {@code 'B'}, as in
     * {@code 10  WS-EDIT-ACCT-STATUS  PIC  X(1).} on L192 whose domain letters are on L193, whose
     * not-acceptable value is on L194 and whose blank value {@code 'B'} is on L195, and
     * {@code 10  WS-EDIT-CREDIT-LIMIT} on L196 whose acceptable value is LOW-VALUES on L197 and whose
     * blank value {@code 'B'} is on L199.</p>
     *
     * <p>Assumptions: absent is a KIND of error rather than a third peer state, which is what lets one
     * test serve both and matches the reference joining the two conditions with a single disjunction. The
     * literal marker is PRESENTATIONAL and belongs to the rendering of an absent field only: a refused
     * value is not an empty control, so it draws no marker.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the absent-field marker is drawn for the blank state only, in both flag regimes")
    void theAbsentFieldMarkerBelongsToTheBlankStateAlone() throws Exception {
        assertThat(FieldValidationFlag.BLANK.requiresBlankMarker())
                .as("an absent field draws the marker the reference moves into it")
                .isTrue();
        assertThat(FieldValidationFlag.BLANK.screenMarker())
                .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER);
        assertThat(FieldValidationFlag.NOT_OK.requiresBlankMarker())
                .as("a refused value is not an empty control and draws no marker")
                .isFalse();
        assertThat(FieldValidationFlag.NOT_OK.screenMarker())
                .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);
        assertThat(FieldValidationFlag.BLANK.isError())
                .as("absent is a kind of error rather than a third peer state")
                .isTrue();

        // WHY : Assumptions: the KEY-filter regime is exercised through the write path's own key edit,
        //       because that is the edit whose absent value is a SPACE rather than the letter B. Driving
        //       it through the same request the non-key regime uses would have asserted one regime twice.
        when(writes.editAccountKey(ACCOUNT_KEY)).thenReturn(
                AccountUpdateService.EditOutcome.blank(
                        AccountUpdateService.MESSAGE_ACCOUNT_NOT_PROVIDED));

        this.mockMvc.perform(submit(REVISION, submission()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("accountId"))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.BLANK.name()))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value(AccountUpdateService.MESSAGE_ACCOUNT_NOT_PROVIDED));
        verifyNoInteractions(reads);
    }

    /**
     * An update without the concurrency precondition is refused before the handler is entered.
     *
     * <p>Assumptions: the precondition header is REQUIRED rather than optional, and the difference is the
     * whole protection: an optional header lets a caller opt out by omitting it, and opting out means the
     * silent overwrite the check exists to remove. The framework refuses the request during argument
     * resolution, so the write path is never consulted -- which this case asserts, because a refusal that
     * still reached the write path would mean the guard was inside the transaction rather than ahead of
     * it.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an update without the concurrency precondition never reaches the write path")
    void anUpdateWithoutThePreconditionIsRefused() throws Exception {
        this.mockMvc.perform(put(UPDATE_PATH, ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(this.jsonMapper.writeValueAsString(submission())))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(writes);
    }

    /**
     * A reformatted entity tag is accepted, and the revision inside it is what reaches the write path.
     *
     * <p>Assumptions: an intermediary is permitted to reformat an entity tag, so a caller that echoes back
     * what it received must not be refused for a difference it did not make. All three renderings of one
     * revision -- weak and quoted, quoted alone, and bare -- therefore arrive at the write path as the
     * same value, and that is asserted by capturing what the write path was handed rather than by
     * observing only that the request succeeded.</p>
     *
     * @throws Exception if any of the three requests cannot be performed
     */
    @Test
    @DisplayName("a weak, a quoted and a bare entity tag all reach the write path as one revision")
    void aReformattedEntityTagIsTolerated() throws Exception {
        when(writes.editAccountKey(ACCOUNT_KEY))
                .thenReturn(AccountUpdateService.EditOutcome.acceptable());
        when(writes.update(eq(ACCOUNT_ID), any(AccountUpdateRequest.class), anyString()))
                .thenReturn(acceptedResponse());
        when(writes.currentRevision(ACCOUNT_ID)).thenReturn(REVISION);

        for (String presented : List.of("W/\"" + REVISION + "\"", "\"" + REVISION + "\"", REVISION)) {
            this.mockMvc.perform(submit(presented, submission())).andExpect(status().isOk());
        }

        ArgumentCaptor<String> precondition = ArgumentCaptor.forClass(String.class);
        verify(writes, times(3))
                .update(eq(ACCOUNT_ID), any(AccountUpdateRequest.class), precondition.capture());
        assertThat(precondition.getAllValues())
                .as("every accepted rendering must reduce to the same revision")
                .containsExactly(REVISION, REVISION, REVISION);
    }

    /**
     * A user type or role supplied by the caller changes nothing about the outcome.
     *
     * <p>Purpose: the reference carried identity in the communication area the terminal echoed back --
     * {@code 01 CARDDEMO-COMMAREA.} opens at {@code app/cpy/COCOM01Y.cpy} L19, the user identifier is on
     * L25 and the one-character user type is on L26 with its administrator condition on L27 and its
     * ordinary-user condition on L28 -- and both transactions are defined
     * {@code RESSEC(NO) CMDSEC(NO)} at {@code app/csd/CARDDEMO.CSD} L314 and L324, so no resource or
     * command check stood behind that field. The Java takes identity from signed token claims instead, so
     * a caller cannot assert its own user type; that divergence is documented rather than presented as a
     * repair of the reference. This case pins the consequence a caller can observe: extra members and
     * extra query values naming a user type or a role are simply not part of any contract here.</p>
     *
     * <p>Assumptions: the submitted record declares forty-three members and none of them is a user
     * identifier, a user type or a role, so this case asserts both that the record declares no such
     * member and that presenting one over the wire leaves the outcome identical. The token-to-authority
     * translation itself belongs to the shared kernel and is not re-asserted here.</p>
     *
     * @throws Exception if either request cannot be performed
     */
    @Test
    @DisplayName("a caller-supplied user type or role does not change the update outcome")
    void aCallerSuppliedUserTypeChangesNothing() throws Exception {
        assertThat(componentNamesOf(AccountUpdateRequest.class))
                .as("the submitted record declares forty-three members and no identity among them")
                .hasSize(43)
                .noneMatch(name -> name.equals("userType") || name.equals("userId")
                        || name.equals("role"))
                .contains("accountId", "creditLimit", "primaryCardHolderIndicator");

        when(writes.editAccountKey(ACCOUNT_KEY))
                .thenReturn(AccountUpdateService.EditOutcome.acceptable());
        when(writes.update(eq(ACCOUNT_ID), any(AccountUpdateRequest.class), anyString()))
                .thenReturn(acceptedResponse());
        when(writes.currentRevision(ACCOUNT_ID)).thenReturn(REVISION);

        String plain = this.mockMvc.perform(submit(REVISION, submission()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // WHY : Assumptions: the claimed identity is presented two ways at once, as query values and as a
        //       header, because a client wanting to escalate would try whichever one the server happened
        //       to read. Asserting the two bodies are byte-identical is what shows neither was read,
        //       whereas asserting only the status would still pass if one of them had altered the payload.
        // WHY : Alternatives Considered: presenting the claim as an extra BODY member as well. Rejected
        //       because the outcome would then depend on the document reader's unknown-member policy,
        //       which is a mapper configuration this class assembles by hand and a deployment obtains from
        //       auto-configuration -- so the case would report on this class's mapper rather than on the
        //       controller's contract. The structural assertion above covers the body instead, and covers
        //       it more strongly: a member the record does not declare cannot bind under any policy.
        String claimed = this.mockMvc.perform(put(UPDATE_PATH + "?userType=A&role=carddemo-admin",
                        ACCOUNT_ID)
                        .header(HttpHeaders.IF_MATCH, REVISION)
                        .header("X-Carddemo-Claimed-User-Type", "A")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(this.jsonMapper.writeValueAsString(submission())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(claimed)
                .as("a claimed user type must leave the response byte-identical")
                .isEqualTo(plain);
    }

    /**
     * The by-account cross-reference walk answers a keyset envelope and masks the card number.
     *
     * <p>Purpose: this route is the migrated {@code CXACAIX} access path, which the reference surfaces as
     * an alternate index over the cross-reference file and reads by account. The envelope carries the rows
     * and the two sealed boundaries a caller steps with, and the projection carries the card number already
     * masked to its last four digits, since a whole primary account number is exactly the value this
     * context withholds from an end-user surface.</p>
     *
     * <p>Assumptions: the walk is by KEY. The reference's own browse state is already a keyset cursor --
     * it holds a last-key pair and a first-key pair and discovers whether a further page exists by reading
     * one row more than the screen holds -- and nothing in that structure is a row number or a position, so
     * the envelope publishes boundaries and availability indicators and no count of any kind.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the cross-reference walk answers boundaries, availability and a masked card number")
    void theCrossReferenceWalkAnswersTheKeysetEnvelope() throws Exception {
        String firstBoundary = sealedShapedCursor("opening");
        String lastBoundary = sealedShapedCursor("closing");

        when(reads.listCardCrossReferences(anyLong(), any(), any(), anyString()))
                .thenReturn(new PageResponse<>(
                        List.of(new CardXrefResponse("************4444", "000000123", ACCOUNT_KEY)),
                        firstBoundary, lastBoundary, true, false));

        this.mockMvc.perform(get(XREF_PATH, ACCOUNT_ID).principal(() -> "carddemo-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].cardNumberMasked").value("************4444"))
                .andExpect(jsonPath("$.firstKey").value(firstBoundary))
                .andExpect(jsonPath("$.lastKey").value(lastBoundary))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.hasPrevious").value(false));

        assertThat(firstBoundary)
                .as("a published boundary must disclose no key column, and a card number least of all")
                .doesNotContain("4444")
                .doesNotContain(ACCOUNT_KEY);
    }

    /**
     * An unauthenticated update is refused by the deployed chain before the handler is entered.
     *
     * <p>Assumptions: this is the only group of cases that carries the deployed filter chain, so the
     * refusal asserted here is produced by the rule the service enforces and not by a substitute. The
     * write path is asserted untouched, because a refusal that still reached it would mean the guard sat
     * behind the handler rather than in front of it.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an unauthenticated update is refused and never reaches the write path")
    void anUnauthenticatedUpdateIsRefused() throws Exception {
        guarded.perform(put(UPDATE_PATH, ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(this.jsonMapper.writeValueAsString(submission())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(writes);
    }

    /**
     * A token carrying neither group authority is refused by the deployed chain.
     *
     * <p>Assumptions: the two authorities the chain admits are the two the shared configuration publishes
     * as one list, and this case presents an authority outside that list rather than a hand-written third
     * group name, so it cannot pass by naming something the chain never checked. The pair descends from the
     * reference's own two-value domain, the one-character user type at {@code app/cpy/COCOM01Y.cpy} L26
     * whose two conditions on L27 and L28 admit an administrator and an ordinary user; nothing here widens
     * that domain.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a token carrying neither group authority is refused on the account subtree")
    void aTokenWithoutEitherGroupAuthorityIsRefused() throws Exception {
        guarded.perform(get(XREF_PATH, ACCOUNT_ID)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_openid"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reads);
    }

    /**
     * Either group authority reaches the handler on the account subtree.
     *
     * <p>Assumptions: both members of the published authority list are exercised, because a rule that had
     * narrowed to one of them would still satisfy a case that presented only the other. The route driven is
     * the cross-reference walk rather than the account view, and the choice is deliberate: its response
     * carries no amount, so a granted case cannot fail for a serialisation reason belonging to the
     * framework's own converters rather than to the authority rule under assertion.</p>
     *
     * @throws Exception if either request cannot be performed
     */
    @Test
    @DisplayName("both published group authorities reach the handler on the account subtree")
    void eitherGroupAuthorityReachesTheHandler() throws Exception {
        when(reads.listCardCrossReferences(anyLong(), any(), any(), anyString()))
                .thenReturn(new PageResponse<>(List.of(), null, null, false, false));

        for (String authority : SecurityConfig.BUSINESS_AUTHORITIES) {
            guarded.perform(get(XREF_PATH, ACCOUNT_ID)
                            .with(jwt().authorities(new SimpleGrantedAuthority(authority))))
                    .andExpect(status().isOk());
        }

        assertThat(SecurityConfig.BUSINESS_AUTHORITIES)
                .as("the admitted set is the reference's two-value user-type domain and no wider")
                .hasSize(2);
        verify(reads, times(2)).listCardCrossReferences(anyLong(), any(), any(), anyString());
    }

    /**
     * Lists the declared component names of a record type.
     *
     * <p>Assumptions: the names are read from the record's own declaration rather than from a serialised
     * document, because a case that must prove a member is ABSENT cannot do so from a document -- an absent
     * member and a member serialised as null look the same on the wire once null suppression is in play.
     * Reading the declaration answers the question the assertions actually ask.</p>
     *
     * @param recordType the record type to describe; must be a record and must not be {@code null}
     * @return the component names in declaration order, never {@code null}
     */
    private static List<String> componentNamesOf(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Reports whether a component name would carry a card verification value.
     *
     * <p>Assumptions: two spellings are tested rather than one, because the value has a compact acronym and
     * an expanded name and a later contributor could reach for either. The comparison is
     * locale-independent so it cannot change behaviour with the host's default locale.</p>
     *
     * @param componentName the declared component name to judge; must not be {@code null}
     * @return {@code true} when the name would carry a card verification value, {@code false} otherwise
     */
    private static boolean namesACardVerificationValue(String componentName) {
        String folded = componentName.toLowerCase(Locale.ROOT);
        return folded.contains("cvv") || folded.contains("verification");
    }

    /**
     * Builds a boundary token that satisfies the sealed shape the page envelope insists on.
     *
     * <p>Assumptions: the envelope's canonical constructor refuses a boundary that is not a sealed token,
     * and the reason is recorded there -- a boundary built from the key columns would publish those columns
     * to a client, and one of them is a primary account number. This helper therefore composes a
     * shape-valid token from the version the sealing type publishes rather than from a literal, and the
     * result is asserted shape-valid so the fixture cannot drift out of the accepted form and start failing
     * for a reason unrelated to the case using it.</p>
     *
     * <p>Alternatives Considered: sealing a real token with a real key. Rejected because a sealed token is
     * opaque by construction, so nothing this class asserts can read one, and standing up a sealing key
     * would add a participant to a case that is about the envelope's members rather than about
     * cryptography. The sealing itself is asserted where it belongs, in the shared kernel's own tests.</p>
     *
     * @param label a short distinguishing suffix, restricted to the characters the sealed shape admits;
     *     must not be {@code null}
     * @return a token satisfying the sealed shape, never {@code null}
     */
    private static String sealedShapedCursor(String label) {
        // WHY : Trade-offs: the sixteen-character segment the shape requires is spelled as readable
        //       words rather than as a run of hexadecimal. Hexadecimal of that length reads like key
        //       material to a human and to a secret scanner's generic high-entropy rule, and a fixture
        //       that has to be explained to every reviewer -- or allow-listed in the scanner
        //       configuration -- costs more than choosing characters nobody can mistake for a key. The
        //       local is named for what it is rather than "token" for the same reason.
        String candidate = CursorToken.VERSION + ".fixtureboundary0." + label;
        assertThat(CursorToken.hasSealedShape(candidate))
                .as("the fixture boundary must satisfy the shape the envelope enforces")
                .isTrue();
        return candidate;
    }

    /**
     * Builds the account view the read path is stubbed to answer with.
     *
     * <p>Assumptions: the five amounts are chosen so one case pins three renderings at once -- a negative
     * balance, a zero cash limit and two whole amounts that must keep their cents -- because the scale-two
     * rendering can fail in each of those shapes independently. Every identifier is obviously synthetic and
     * the two protected ones arrive already masked, matching the invariant the mapper upholds upstream.</p>
     *
     * @return an account view carrying both nested groupings and both message channels, never {@code null}
     */
    private static AccountViewResponse viewResponse() {
        AccountViewResponse.AccountDetail account = new AccountViewResponse.AccountDetail(
                "Y",
                "2020-01-15",
                Money.of("1500.00"),
                "2027-01-31",
                Money.of("0.00"),
                "2024-01-31",
                Money.of("-193.00"),
                Money.of("250.75"),
                "DEFAULT",
                Money.of("1000.00"));

        AccountViewResponse.CustomerDetail customer = new AccountViewResponse.CustomerDetail(
                "000000456",
                "***-**-6789",
                "1980-04-02",
                "742",
                "ADA",
                "M",
                "LOVELACE",
                "1 SYNTHETIC WAY",
                "NY",
                "SUITE 100",
                "10001",
                "TESTVILLE",
                "USA",
                "212-555-0100",
                "****4321",
                null,
                "0000000001",
                "Y");

        return new AccountViewResponse(ACCOUNT_KEY, account, customer,
                "Displaying details of given Account", "");
    }

    /**
     * Builds a submission whose amount members are the plain scale-two form.
     *
     * @return a fully populated submission, never {@code null}
     */
    private static AccountUpdateRequest submission() {
        return submission("1500.00");
    }

    /**
     * Builds a submission with the credit limit set to a caller-chosen character sequence.
     *
     * <p>Assumptions: only the credit limit varies, so a case that is about one member's characters does not
     * also change forty-two others and leave a failure ambiguous about which member caused it. Dates arrive
     * DECOMPOSED into year, month and day at widths four, two and two; the national identifier arrives SPLIT
     * three, two and four; each telephone number arrives SPLIT three, three and four; and the account's own
     * postal code -- {@code 05  ACCT-ADDR-ZIP  PIC X(10).} at {@code app/cpy/CVACT01Y.cpy} L15 -- is
     * deliberately absent, the only postal code submitted being the customer's.</p>
     *
     * @param creditLimit the exact characters to place in the credit-limit member; must not be {@code null}
     * @return a fully populated submission carrying that credit limit, never {@code null}
     */
    private static AccountUpdateRequest submission(String creditLimit) {
        return new AccountUpdateRequest(
                ACCOUNT_KEY,
                "Y",
                creditLimit,
                "500.00",
                "-193.00",
                "250.75",
                "1000.00",
                "2020", "01", "15",
                "2027", "01", "31",
                "2024", "01", "31",
                "DEFAULT",
                "000000456",
                "111", "22", "6789",
                "1980", "04", "02",
                "742",
                "ADA",
                "M",
                "LOVELACE",
                "1 SYNTHETIC WAY",
                "SUITE 100",
                "TESTVILLE",
                "NY",
                "USA",
                "10001",
                "212", "555", "0100",
                "", "", "",
                "SYNTHETICID0000004321",
                "0000000001",
                "Y");
    }

    /**
     * Builds the response the write path is stubbed to answer with when a submission is accepted.
     *
     * <p>Assumptions: the two nested groupings are absent, which the response record permits and documents
     * -- it validates only the per-field array. Leaving them absent keeps the accepted-path cases free of
     * any amount, so none of them can fail for a serialisation reason belonging to a different case.</p>
     *
     * @return an accepted update response with an empty per-field array, never {@code null}
     */
    private static AccountUpdateResponse acceptedResponse() {
        return new AccountUpdateResponse(ACCOUNT_KEY, null,
                AccountUpdateService.MESSAGE_UPDATE_ACCEPTED, List.of(), null, null);
    }

    /**
     * Builds an update request carrying a concurrency precondition and a submission body.
     *
     * <p>Assumptions: the precondition header is written by this helper rather than by each case, because
     * the header is required by the contract and a case that omitted it by accident would be refused for a
     * reason it was not written to assert. The one case that IS about the missing header builds its request
     * without this helper, deliberately.</p>
     *
     * @param ifMatch the exact characters to present as the concurrency precondition; must not be
     *     {@code null}
     * @param body the submission to serialise into the request body; must not be {@code null}
     * @return a request builder ready to be performed, never {@code null}
     */
    private MockHttpServletRequestBuilder submit(String ifMatch, AccountUpdateRequest body) {
        return put(UPDATE_PATH, ACCOUNT_ID)
                .header(HttpHeaders.IF_MATCH, ifMatch)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(this.jsonMapper.writeValueAsString(body));
    }

    /**
     * Assembles the controller behind the deployed filter chain, with only the token decoder substituted.
     *
     * <p>Assumptions: every participant in the authorization decision is the deployed one -- the rules and
     * the refusal renderers come from {@code new SecurityConfig().filterChain(...)}, and the
     * token-to-authentication translation comes from the same class's own factory, handed the two group
     * names the chain itself publishes as {@code SecurityConfig.BUSINESS_AUTHORITIES}. Passing that list
     * rather than two literals is what stops this wiring and the rule it exercises drifting apart.</p>
     *
     * <p>Assumptions: the token DECODER is the one bean substituted, and the substitution is required
     * rather than convenient. The deployed factory for it resolves the issuer's provider document while the
     * context refreshes, so a context that created it could not start on a host with no route to the pinned
     * issuer; the request post-processor the cases use mints an authentication directly and never consults
     * a decoder, so nothing asserted here depends on the substitute's behaviour.</p>
     *
     * <p>Trade-offs: the MVC infrastructure is enabled here rather than inherited from a started
     * application, so this context's message converters are the framework's defaults and do not carry the
     * money module. That is why the cases driven through this dispatcher deliberately avoid any response
     * holding an amount.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class GuardedSliceWiring {

        /**
         * Supplies the pinned clock the refusal renderers stamp their bodies from.
         *
         * @return a clock pinned to {@link AccountControllerTest#FIXED_INSTANT}, never {@code null}
         */
        @Bean
        Clock clock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }

        /**
         * Supplies the controller under assertion, over the same substituted collaborators the unguarded
         * dispatcher uses.
         *
         * @return the controller, wired exactly as a deployment wires it, never {@code null}
         */
        @Bean
        AccountController accountController() {
            return new AccountController(reads, writes);
        }

        /**
         * Supplies the shared error advice so a refusal reaching the handler renders the deployed body.
         *
         * <p>Assumptions: it is registered explicitly because it lives outside this context's scan root and
         * reaches a deployment through the shared kernel's auto-configuration, which a context assembled by
         * hand does not apply.</p>
         *
         * @param clock the clock the advice stamps its bodies from; must not be {@code null}
         * @return the shared advice, never {@code null}
         */
        @Bean
        GlobalExceptionHandler globalExceptionHandler(Clock clock) {
            return new GlobalExceptionHandler(clock);
        }

        /**
         * Supplies the substituted token decoder the resource-server filter would resolve a token with.
         *
         * @return a substitute for the token decoder, with no stubbing applied, never {@code null}
         */
        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }

        /**
         * Supplies the deployed token-to-authentication translation, group names included.
         *
         * @return the converter the deployed configuration builds, never {@code null}
         */
        @Bean
        JwtAuthenticationConverter jwtAuthenticationConverter() {
            return new SecurityConfig().jwtAuthenticationConverter(
                    SecurityConfig.BUSINESS_AUTHORITIES.get(0),
                    SecurityConfig.BUSINESS_AUTHORITIES.get(1));
        }

        /**
         * Supplies the deployed filter chain, rules, refusal renderers and session policy included.
         *
         * @param http the chain builder this context contributes; must not be {@code null}
         * @param decoder the substituted token decoder; must not be {@code null}
         * @param converter the deployed token-to-authentication translation; must not be {@code null}
         * @param clock the clock the rendered refusal bodies read their instant from; must not be
         *     {@code null}
         * @return the chain the deployed configuration builds, never {@code null}
         * @throws Exception when the builder cannot assemble the chain, which it declares
         */
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder decoder,
                JwtAuthenticationConverter converter, Clock clock) throws Exception {
            return new SecurityConfig().filterChain(http, decoder, converter, clock);
        }
    }
}
