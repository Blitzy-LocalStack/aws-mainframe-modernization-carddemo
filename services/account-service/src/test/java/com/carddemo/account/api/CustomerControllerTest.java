package com.carddemo.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.account.config.SecurityConfig;
import com.carddemo.account.dto.CustomerResponse;
import com.carddemo.account.mapper.CustomerMapper;
import com.carddemo.account.service.AccountViewService;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Holds the published contract invariants of the customer read surface.
 *
 * <p>Purpose: this class pins the facts about {@link CustomerController} that hold on every response it
 * ever produces -- how many members the customer document declares and of what type, which values it
 * withholds, which members it does not declare at all, that its bounded scan publishes only the page it
 * was asked for, that an exhausted scan publishes nothing a caller could resume from, and which authority
 * the deployed chain demands of the subtree. The reference is {@code app/cbl/CBCUS01C.cbl}, 178 lines: a
 * BATCH reader carrying no {@code EXEC CICS} verb and bound to no transaction, whose whole access surface
 * is its file declaration and its driver loop. The record layout is {@code app/cpy/CVCUS01Y.cpy}, 26
 * lines, whose group item {@code 01 CUSTOMER-RECORD.} opens at L4 and declares 500 bytes. Both are
 * reference-only: every figure below was read from them and is cited by path and physical line, and
 * neither is modified.
 *
 * <p>Assumptions: NO EXECUTABLE PARITY ORACLE EXISTS for this reference, so every case below was authored
 * from the COBOL paragraphs directly and none of them may be described as agreeing with a golden master.
 * {@code tests/README.md} records at L83 through L85 that the online {@code CO*} CICS programs cannot be
 * driven end to end without a CICS runtime, which the build host does not have, and that only their
 * extractable field-validation logic is unit-tested. The catalogue of verbatim business rules in that same
 * file opens at its L553 and, from that line onward, names {@code CBTRN02C}, {@code CBACT04C} and
 * {@code TCATBAL} and no program of this bounded context at all -- neither {@code CBCUS01C} nor
 * {@code COACTVWC} nor {@code COACTUPC} -- so there is no recorded output for this reference to compare
 * with. These assertions therefore ENCODE the documented layout and access path and are strictly additive
 * to that suite, which is reference-only and is neither modified, nor re-pinned, nor reached by any path
 * from here.
 *
 * <h2>What this class asserts, and what its siblings assert</h2>
 *
 * <p>Assumptions: the division matters more than an inventory, because an overlap gives a later change two
 * places to update and one place to forget. {@code CustomerReadRouteTest} beside this file owns the ROUTE
 * behaviour of the same two operations -- which status each outcome carries, how the page size and the
 * position parameters are bound, resolved and refused, and exactly what the handler hands the service.
 * {@code AccountDispatcherTest} owns the presence probe's two bodiless outcomes and the retired keyed
 * probe. {@code AccountContextContractTest} owns the published contract document and the internal
 * surface's authority declarations. What is left, and what this class owns, is the shape of the DOCUMENT
 * and of the ENVELOPE: the declared member set, the values withheld from it, the boundedness of the scan,
 * and the subtree's authority rule as the end-user chain states it.
 *
 * <p>Alternatives Considered: the servlet slice annotation is the obvious wiring for a class like this and
 * is unavailable here. Spring Boot 4 moved it out of {@code spring-boot-test-autoconfigure} into a
 * separate artifact that neither {@code services/account-service/pom.xml} nor
 * {@code spring-boot-starter-test} brings in, so the annotation cannot be resolved on this module's test
 * class path and declaring it would not compile. The charter in {@code package-info.java} beside this file
 * settles the same question independently for a wire-contract class, and both sibling controller tests in
 * this package already use the shape adopted here, so a reader moving between them meets one shape.
 *
 * <p>Assumptions: the one collaborator substituted is {@link AccountViewService}, and it is the only one
 * there is. {@link CustomerController} takes a single constructor argument and delegates all three of its
 * operations to it; the read coordination for this context lives on that account read path and there is no
 * customer-specific service beside it. The shared error advice is NOT substituted: it is registered for
 * real, because the not-found status and the problem document's shape are its behaviour, and a substitute
 * would assert this class's own expectations back to itself.
 *
 * <p>Assumptions: no message converter is registered, and the omission is deliberate rather than an
 * oversight. Sibling controller tests in the account, authorization and transaction contexts register one
 * in order to install the shared money module, because a body of theirs carries an amount that must leave
 * as a JSON string. The customer contract carries NO amount at all -- none of the eighteen fields the
 * record declares at L5 through L22 of {@code app/cpy/CVCUS01Y.cpy} is a signed decimal, in contrast with
 * {@code app/cpy/CVACT01Y.cpy}, whose five signed decimals sit at L7, L8, L9, L13 and L14 -- so
 * registering a converter here would state a dependency this contract does not have.
 *
 * <p>Assumptions: the clock handed to the advice is FIXED, because the advice stamps every problem
 * document it renders and a clock read would put a value in the body that changes between runs. Nothing
 * here asserts the stamp; fixing it keeps the response byte-stable for anyone who later wants to.
 *
 * <p>A test class is instantiated by the engine, accepts no parameter, yields no value and raises nothing,
 * so this block carries no parameter, return or exception section; the inapplicability is stated rather
 * than passed over so a reader can tell it from an oversight.
 */
class CustomerControllerTest {

    /**
     * The keyed record route, composed from the controller's own two constants.
     *
     * <p>Assumptions: composed rather than spelled out, so that renaming either constant moves this route
     * with it instead of leaving a literal that no longer addresses the handler.</p>
     */
    private static final String RECORD_ROUTE =
            CustomerController.BASE_PATH + CustomerController.RECORD_PATH;

    /**
     * The bounded ascending scan route, which is the collection address itself.
     */
    private static final String SCAN_ROUTE = CustomerController.BASE_PATH;

    /**
     * The customer identifier every case in this class reads.
     *
     * <p>Assumptions: within the nine digits {@code CUST-ID PIC 9(09)} declares at L5 of
     * {@code app/cpy/CVCUS01Y.cpy}, and obviously synthetic so that no case carries a value that could be
     * mistaken for a real customer.</p>
     */
    private static final long CUSTOMER_ID = 42L;

    /**
     * The request body the keyed read carries, the identifier having moved out of the path segment.
     */
    private static final String RECORD_BODY = "{\"customerId\":42}";

    /**
     * The nine-digit rendering the response publishes for {@link #CUSTOMER_ID}.
     *
     * <p>Assumptions: left-zero-padded to the declared width, because the component travels as text and
     * a numeric member would drop exactly these leading zeros.</p>
     */
    private static final String CUSTOMER_ID_DIGITS = "000000042";

    /**
     * The city value the fixture places in the third address line.
     *
     * <p>Assumptions: a recognisable city name is used so that the case asserting where a city travels
     * fails visibly if the value ever moves to another member.</p>
     */
    private static final String CITY_VALUE = "NEW YORK";

    /**
     * The nine digits the fixture's national identifier would carry if it were ever published unmasked.
     *
     * <p>Assumptions: chosen to share no digit run with {@link #CUSTOMER_ID_DIGITS}, which is what makes
     * the leak assertion able to fail. Were the two alike, a body carrying only the identifier would
     * satisfy a search for this value and the case would pass while proving nothing.</p>
     */
    private static final String UNMASKED_NATIONAL_IDENTIFIER = "987654321";

    /**
     * The government-issued identifier the fixture would carry if it were ever published unmasked.
     *
     * <p>Assumptions: obviously synthetic and not shaped like any issuing authority's real format, so it
     * cannot be mistaken for a credential by a reader or by a secret scanner.</p>
     */
    private static final String UNMASKED_GOVERNMENT_IDENTIFIER = "SYNTHETIC-ID-0001";

    /**
     * The sentence the customer master's absence condition carries.
     *
     * <p>Assumptions: carried verbatim from the condition at L133 with L134 of
     * {@code app/cbl/COACTVWC.cbl}, as transformation rule T8 requires of any user-visible string, and
     * named here once so that the width case and the problem-shape case measure the same characters.</p>
     */
    private static final String NOT_FOUND_SENTENCE = "Did not find associated customer in master file";

    /**
     * The width the informational channel is composed in on the program side, forty characters.
     *
     * <p>Assumptions: read from {@code 05  WS-INFO-MSG  PIC X(40).} at L110 of
     * {@code app/cbl/COACTVWC.cbl}.</p>
     */
    private static final int INFORMATION_CHANNEL_WIDTH = 40;

    /**
     * The width the aggregate return channel is composed in on the program side, seventy-five characters.
     *
     * <p>Assumptions: read from {@code 05  WS-RETURN-MSG  PIC X(75).} at L117 of
     * {@code app/cbl/COACTVWC.cbl}, corroborated by the identically declared field at L479 of
     * {@code app/cbl/COACTUPC.cbl} and by {@code 10  CCARD-RETURN-MSG  PIC X(75).} at L29 of
     * {@code app/cpy/CVCRD01Y.cpy}, whose L28 declares the error channel at the same width. Those two
     * lines are the ONLY occurrences of that width anywhere under {@code app/cpy}; the common message
     * copybook {@code app/cpy/CSMSG01Y.cpy} is a different, fifty-character regime at its L18 through
     * L21 and is not the source of this figure.</p>
     */
    private static final int AGGREGATE_CHANNEL_WIDTH = 75;

    /**
     * The number of data fields the customer record declares, eighteen.
     *
     * <p>Assumptions: counted at L5 through L22 of {@code app/cpy/CVCUS01Y.cpy}. The {@code FILLER PIC
     * X(168)} at L23 is deliberately excluded, being padding to the declared 500-byte length rather than
     * a field.</p>
     */
    private static final int DECLARED_FIELD_COUNT = 18;

    /**
     * The page width the scan cases ask for.
     *
     * <p>Assumptions: below {@link AccountViewService#CUSTOMER_SCAN_MAX_PAGE_SIZE} so that no case is
     * refused at the edge before it reaches the assertion it was written for, and above one so that a
     * page can hold more than a single row.</p>
     */
    private static final int PAGE_WIDTH = 3;

    /**
     * The instant the shared advice stamps every problem document with in this class.
     */
    private static final Instant PINNED_INSTANT = Instant.parse("2026-08-09T06:00:00Z");

    /**
     * Key material for the sealer that mints this class's boundary tokens.
     *
     * <p>Assumptions: test-only material and not a credential, spelled as prose so that neither a reader
     * nor a secret scanner's high-entropy rule can mistake it for a key. It exceeds
     * {@link CursorToken#MIN_KEY_LENGTH} bytes, which the sealer refuses to be constructed below.</p>
     */
    private static final byte[] CURSOR_KEY =
            "carddemo-account-customer-contract-test-cursor-material-not-a-secret"
                    .getBytes(StandardCharsets.UTF_8);

    /**
     * The sealer that mints the boundary tokens this class's page fixtures publish.
     *
     * <p>Assumptions: a REAL sealer is required rather than tidy, even though nothing here opens a token.
     * {@link PageResponse}'s canonical constructor refuses a boundary that is not a sealed token, because
     * a boundary built from the key columns would publish those columns to a client. A literal such as
     * {@code "opening-position"} therefore cannot be used, and the envelope's own guard is what says
     * so.</p>
     *
     * <p>Assumptions: the binding these tokens are sealed under is this class's own and NOT the scan's,
     * because the envelope checks only the SHAPE of a boundary and never opens one. Reusing the scan's
     * binding would suggest this class asserts something about which query sealed them, which it does
     * not.</p>
     */
    private static final CursorToken FIXTURE_SEALER =
            new CursorToken(CURSOR_KEY, Duration.ofHours(1));

    /**
     * The read path, substituted so a case controls the answer and can observe what it was asked.
     */
    private static AccountViewService reads;

    /**
     * The context backing the dispatcher that carries the deployed filter chain.
     */
    private static AnnotationConfigWebApplicationContext guardedContext;

    /**
     * The dispatcher that carries the deployed filter chain, used only for authority questions.
     */
    private static MockMvc guarded;

    /**
     * The dispatcher that carries no filter chain, used for every document and envelope assertion.
     */
    private MockMvc mockMvc;

    /**
     * Builds the substituted read path and the dispatcher that carries the deployed filter chain.
     *
     * <p>Trade-offs: the guarded context is built ONCE for the class rather than per case, because
     * assembling a security-enabled context is the most expensive thing this class does and no case below
     * mutates it. What is given up is per-case isolation of the substituted collaborator, which is bought
     * back by resetting it before each case.</p>
     */
    @BeforeAll
    static void buildGuardedDispatcher() {
        reads = mock(AccountViewService.class);

        guardedContext = new AnnotationConfigWebApplicationContext();
        guardedContext.setServletContext(new MockServletContext());
        guardedContext.register(GuardedSliceWiring.class);
        guardedContext.refresh();

        // WHAT: wraps the security-enabled context in a dispatcher whose requests traverse the chain.
        // WHY : Assumptions: the configurer form is used rather than adding the chain filter by hand,
        //       because the request post-processor that mints an authentication publishes it through the
        //       test context repository this configurer installs. Adding the filter alone would leave
        //       every minted token invisible to the chain, so a refusal asserted below could have been
        //       caused by the wiring rather than by the rule under assertion.
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
     * Builds the unguarded dispatcher and returns the substituted read path to a clean state.
     */
    @BeforeEach
    void buildDispatcher() {
        reset(reads);

        // WHY : Assumptions: the shared advice is REGISTERED, never re-implemented. The not-found status
        //       and the problem document's member set belong to
        //       com.carddemo.common.error.GlobalExceptionHandler, so a dispatcher assembled without it
        //       would answer an absent customer with 500 and the failure would read as the controller
        //       misbehaving rather than as a missing participant in this wiring.
        this.mockMvc = MockMvcBuilders.standaloneSetup(new CustomerController(reads))
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    /**
     * The customer document declares the record's eighteen fields, all as text, and no padding member.
     *
     * <p>Purpose: {@code app/cpy/CVCUS01Y.cpy} declares eighteen data fields at L5 through L22 beneath the
     * group item at L4, followed by {@code FILLER PIC X(168)} at L23. This case pins the count, pins that
     * every published member is text, and pins that the padding is absent -- three facts a single added or
     * retyped member would break.</p>
     *
     * <p>Assumptions: every numeric field of this record is published as a digits-only TEXT member rather
     * than as a numeric one, including the key at L5, the national identifier at L17 and the credit score
     * at L22, all three of which the copybook declares numeric. Two independently verified grounds carry
     * the decision. The reference holds exactly that separation itself, declaring a character field and
     * overlaying a numeric reading on the same storage: {@code ACUP-OLD-ACCT-ID-X PIC X(11)} at L671 of
     * {@code app/cbl/COACTUPC.cbl} redefined {@code PIC 9(11)} across L672 and L673,
     * {@code ACUP-OLD-CURR-BAL PIC X(12)} at L675 redefined {@code PIC S9(10)V99} across L676 and L677,
     * and {@code ACUP-OLD-CUST-FICO-SCORE-X PIC X(03)} at L754 redefined {@code PIC 9(03)} across L755 and
     * L756 -- so the value on the wire is characters and the number is a reading of it. And the two
     * symbolic maps disagree on the type of the same logical field at the same physical line,
     * {@code ACCTSIDI} being {@code PIC X(11)} at L60 of {@code app/cpy-bms/COACTUP.CPY} against
     * {@code PIC 99999999999} at L60 of {@code app/cpy-bms/COACTVW.CPY}, and the stricter update-map form
     * is the one taken. A numeric-typed member would silently accept and reformat values the reference
     * treats as characters, dropping the leading zeros the declared widths require.</p>
     *
     * <p>Trade-offs: these eighteen members are published at RECORD widths, and the nested customer
     * grouping of the account view carries the same eighteen logical fields at SCREEN widths, which differ
     * on several of them -- the postal code is {@code PIC X(10)} at L14 of {@code app/cpy/CVCUS01Y.cpy}
     * against {@code PIC X(5)} on the account view map, and each telephone number is {@code PIC X(15)} at
     * L15 and L16 against a narrower screen field. {@link CustomerResponse} is therefore deliberately NOT
     * reused as a member of the account view's document. Reuse was available and is declined because the
     * two carry different widths for the same logical fields, so sharing one type would have forced one of
     * the two contracts to bend to the other; the cost accepted is two declarations of a similar member
     * set, and the alternative cost was a contract that silently narrows stored values.</p>
     */
    @Test
    @DisplayName("the customer document declares the record's eighteen fields as text and no padding")
    void theCustomerDocumentDeclaresEighteenTextMembersAndNoPadding() {
        List<String> declared = componentNamesOf(CustomerResponse.class);

        assertThat(declared)
                .as("the document must declare one member per data field at L5 through L22, and no more")
                .hasSize(DECLARED_FIELD_COUNT)
                .containsExactly(
                        "customerId",
                        "firstName",
                        "middleName",
                        "lastName",
                        "addressLine1",
                        "addressLine2",
                        "addressLine3",
                        "stateCode",
                        "countryCode",
                        "zipCode",
                        "phoneNumber1",
                        "phoneNumber2",
                        "ssnMasked",
                        "governmentIssuedIdMasked",
                        "dateOfBirth",
                        "eftAccountId",
                        "primaryCardHolderIndicator",
                        "ficoCreditScore");

        assertThat(Arrays.stream(CustomerResponse.class.getRecordComponents())
                .map(RecordComponent::getType)
                .distinct()
                .toList())
                .as("every member travels as text, so exactly one member type may appear")
                .containsExactly(String.class);

        // WHY : Assumptions: the padding is asserted absent by NAME rather than by counting alone,
        //       because a contributor adding a member and removing another would keep the count at
        //       eighteen while reintroducing the very thing transformation rule T1 drops.
        assertThat(declared)
                .as("the FILLER at L23 is padding to the 500-byte length and is not a field")
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("filler"));
    }

    /**
     * The city travels in the third address line, under that name and no other.
     *
     * <p>Purpose: the reference declares NO city field. {@code app/cpy/CVCUS01Y.cpy} gives three address
     * lines at L9, L10 and L11, all {@code PIC X(50)}, and the third of them is where a city is held --
     * {@code app/cbl/COACTVWC.cbl} moves {@code CUST-ADDR-LINE-3} into the account view screen's city
     * field. There being no {@code CUST-CITY} anywhere in the layout, L11 is the city's home and the
     * published member is named for the field rather than for the screen label.</p>
     *
     * <p>Assumptions: the member is asserted absent under a city name as well as present under the address
     * name, because the risk this case guards is a well-meant rename. The layout that settles it is
     * {@code CUST-ADDR-LINE-3 PIC X(50)} at L11 of {@code app/cpy/CVCUS01Y.cpy}, and no {@code CUST-CITY}
     * is declared anywhere among the eighteen fields at L5 through L22. A member called after the label
     * would therefore assert a stored field the reference does not declare, and a client written against it
     * would be reading a name with no counterpart in the record.</p>
     *
     * @throws Exception if the request cannot be performed or the response body cannot be read
     */
    @Test
    @DisplayName("the city travels in the third address line and under no city-named member")
    void theCityTravelsInTheThirdAddressLine() throws Exception {
        when(reads.readCustomer(CUSTOMER_ID)).thenReturn(customer());

        this.mockMvc.perform(post(RECORD_ROUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECORD_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressLine3").value(CITY_VALUE))
                .andExpect(jsonPath("$.city").doesNotExist());

        assertThat(componentNamesOf(CustomerResponse.class))
                .as("no member may be named for a field the reference layout never declares")
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("city"));
    }

    /**
     * The customer document declares no card verification value, so there is none to mask.
     *
     * <p>Purpose: this case asserts an ABSENCE rather than a masking, and the distinction is the point.
     * None of the three records this bounded context owns declares a card verification value:
     * {@code app/cpy/CVACT01Y.cpy} is 20 lines and declares the account's identifier, status, five signed
     * decimals, three dates, a postal code and a group identifier; {@code app/cpy/CVCUS01Y.cpy} is 26
     * lines and declares the eighteen fields at L5 through L22; {@code app/cpy/CVACT03Y.cpy} is 11 lines
     * and declares a card number at L5, a customer identifier at L6 and an account identifier at L7. The
     * schema agrees: {@code services/account-service/src/main/resources/db/migration/V1__account.sql}
     * creates no such column in any of its tables.</p>
     *
     * <p>Assumptions: because no such value is stored by this context -- neither
     * {@code app/cpy/CVACT01Y.cpy} at L5 through L16, nor {@code app/cpy/CVCUS01Y.cpy} at L5 through L22,
     * nor {@code app/cpy/CVACT03Y.cpy} at L5 through L7 declares one, and {@code V1__account.sql} creates
     * no column for one -- asserting that it is masked would be asserting a treatment of something that
     * does not exist, and such a case cannot fail. Asserting the absence can: it fails the day a member
     * carrying the value is introduced, which is the only event worth catching. Two spellings are judged,
     * the compact acronym and the expanded name, because a later contributor could reach for either.</p>
     */
    @Test
    @DisplayName("the customer document declares no card verification value to mask")
    void theCustomerDocumentDeclaresNoCardVerificationValue() {
        assertThat(componentNamesOf(CustomerResponse.class))
                .as("this context stores no card verification value, so none may be published")
                .noneMatch(CustomerControllerTest::namesACardVerificationValue);
    }

    /**
     * The keyed read answers exactly one document and leaks neither stored identifier.
     *
     * <p>Purpose: the reference positions this read by {@code RECORD KEY IS FD-CUST-ID}, declared at L32 of
     * {@code app/cbl/CBCUS01C.cbl}, so one key selects one record. This case pins that the response is a
     * single document rather than a collection of one, and that both protected identifiers arrive reduced.
     * {@code CUST-SSN} at L17 of {@code app/cpy/CVCUS01Y.cpy} is declared {@code PIC 9(09)} and
     * {@code CUST-GOVT-ISSUED-ID} at L18 is declared {@code PIC X(20)}: the two are alike only in their
     * disclosure treatment, so each is asserted separately.</p>
     *
     * <p>Assumptions: the masking is an invariant established UPSTREAM of the handler rather than applied
     * by it. {@code CustomerMapper} holds the masking, so the controller is never handed a whole value and
     * imports no stored entity and no mapper of its own; what is asserted here is therefore the observable
     * output and not the mechanism, which is asserted where the mapping happens.</p>
     *
     * <p>Assumptions: the RAW response bytes are searched for each unmasked value, rather than only the
     * masked members being read. The reason is that a document-level assertion cannot fail for the case
     * that matters: a projection that masked correctly while a serialiser published a second, unmasked
     * member beside it would satisfy every path expression above and still disclose the value. Searching
     * the bytes is what makes that reachable, and the two fixture values are chosen to share no digit run
     * with the published identifier so the search can fail.</p>
     *
     * @throws Exception if the request cannot be performed or the response body cannot be read
     */
    @Test
    @DisplayName("the keyed read answers one document and leaks neither stored identifier")
    void theKeyedReadAnswersOneDocumentAndLeaksNeitherIdentifier() throws Exception {
        when(reads.readCustomer(CUSTOMER_ID)).thenReturn(customer());

        this.mockMvc.perform(post(RECORD_ROUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECORD_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(CUSTOMER_ID_DIGITS))
                .andExpect(jsonPath("$.ssnMasked").value(CustomerMapper.IDENTIFIER_REDACTED))
                .andExpect(jsonPath("$.governmentIssuedIdMasked")
                        .value(CustomerMapper.IDENTIFIER_REDACTED))
                // WHY : Assumptions: a keyed read answers a document, so the envelope members belong to
                //       the scan and must be absent here. Their presence would mean the keyed arm had
                //       been folded into the collection arm, which a client reading one record would
                //       have to unwrap.
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(content().string(not(containsString(UNMASKED_NATIONAL_IDENTIFIER))))
                .andExpect(content().string(not(containsString(UNMASKED_GOVERNMENT_IDENTIFIER))));

        verify(reads).readCustomer(CUSTOMER_ID);
    }

    /**
     * An absent customer answers the problem document, not a server fault and not an empty success.
     *
     * <p>Purpose: the read path raises the standard no-such-element type carrying the reference's own
     * absence sentence, and the shared advice renders it. This case pins that the outcome is the project's
     * problem document at 404 -- the code, the status, the addressed path and the carried sentence -- and
     * pins the two wrong answers it must not be: a 500, which would mean nothing recognised the condition,
     * and a 200 carrying an empty body, which would leave a client unable to tell an absent customer from
     * a customer whose every field is blank.</p>
     *
     * <p>Assumptions: the per-field array is asserted PRESENT AND EMPTY rather than absent. No field
     * offended on this path -- the key was well formed and simply matched nothing -- and the document
     * publishes the member regardless, so a client may read it without first testing for its presence.
     * Transformation rule T7 makes the array the destination of the reference's per-field condition
     * pattern, and an array that vanished when no field offended would make every client's read of it
     * conditional.</p>
     *
     * <p>Assumptions: the sentence is carried across character for character as transformation rule T8
     * requires, and the exact string is asserted rather than a measurement of it.</p>
     *
     * @throws Exception if the request cannot be performed or the response body cannot be read
     */
    @Test
    @DisplayName("an absent customer answers the problem document with an empty per-field array")
    void anAbsentCustomerAnswersTheProblemDocument() throws Exception {
        when(reads.readCustomer(CUSTOMER_ID))
                .thenThrow(new NoSuchElementException(NOT_FOUND_SENTENCE));

        this.mockMvc.perform(post(RECORD_ROUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECORD_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_NOT_FOUND))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value(RECORD_ROUTE))
                .andExpect(jsonPath("$.message").value(NOT_FOUND_SENTENCE))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    /**
     * The per-field array the problem document publishes is never absent and cannot be added to.
     *
     * <p>Purpose: this pins the two structural properties of the array that a client depends on and that
     * the wire alone cannot show. A document rendered without any offending field still carries the member,
     * and the list a caller holds cannot be extended, so a component that received a problem document
     * cannot append to it and pass it on as though the extra entry had come from the service.</p>
     *
     * <p>Assumptions: the document is built through the same factory the shared advice uses for this
     * outcome rather than read back off the wire, because unmodifiability is a property of the object and
     * is erased by serialisation. The advice's own rendering of the same outcome is asserted on the wire by
     * the case above, so between the two the property is pinned at both ends.</p>
     */
    @Test
    @DisplayName("the per-field array is never absent and cannot be added to")
    void thePerFieldArrayIsNeverAbsentAndCannotBeAddedTo() {
        ApiError rendered = ApiError.of(ApiError.CODE_NOT_FOUND, NOT_FOUND_SENTENCE, 404,
                "correlation-fixture", RECORD_ROUTE, Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC));

        assertThat(rendered.fieldErrors())
                .as("absence of an offending field is an empty array, never a null member")
                .isNotNull()
                .isEmpty();

        List<ApiError.FieldError> published = rendered.fieldErrors();
        ApiError.FieldError intruder =
                new ApiError.FieldError("customerId", FieldValidationFlag.NOT_OK, "refused");

        assertThatThrownBy(() -> published.add(intruder))
                .as("a holder of the document must not be able to append an entry the service never sent")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * The absent-field marker belongs to the blank state alone and never to a published value.
     *
     * <p>Purpose: transformation rule T7 carries the reference's per-field condition pattern into a
     * structured array, and the reference draws a literal marker into a field only when that field was left
     * empty -- not when it held something unacceptable. This case pins that the marker follows the blank
     * state alone, and pins the property that keeps it honest: it is PRESENTATIONAL, so it never appears as
     * the value of any of the eighteen published members.</p>
     *
     * <p>Assumptions: the second half is what makes this case belong to this class rather than to the
     * shared kernel. Which state carries the marker is the shared type's own rule and is asserted there
     * too; that no member of THIS document ever carries the marker as data is a property of this contract,
     * and it is the one a client would be misled by -- a value of a single asterisk read as a name, a
     * postal code or an indicator would be indistinguishable from a real one.</p>
     *
     * @throws Exception if the request cannot be performed or the response body cannot be read
     */
    @Test
    @DisplayName("the absent-field marker follows the blank state alone and is never a published value")
    void theAbsentFieldMarkerFollowsTheBlankStateAlone() throws Exception {
        assertThat(FieldValidationFlag.BLANK.screenMarker())
                .as("the blank state is the only one the reference draws a marker for")
                .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER);
        assertThat(FieldValidationFlag.NOT_OK.screenMarker())
                .as("an unacceptable value is highlighted, not marked with the absence character")
                .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);
        assertThat(FieldValidationFlag.VALID.screenMarker())
                .as("an acceptable value carries no marker at all")
                .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);

        when(reads.readCustomer(CUSTOMER_ID)).thenReturn(customer());

        String document = this.mockMvc.perform(post(RECORD_ROUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECORD_BODY))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // WHY : Assumptions: the marker is sought as a QUOTED whole value rather than anywhere in the
        //       body, because an asterisk may legitimately occur inside a masked value and finding one
        //       there would be a false alarm. Only a member whose entire value is the marker would mean
        //       a presentational character had been published as data.
        assertThat(document)
                .as("no published member may carry the absence marker as its whole value")
                .doesNotContain("\"" + FieldValidationFlag.BLANK_SCREEN_MARKER + "\"");
    }

    /**
     * The scan publishes the page it was asked for and withholds the row that proved more remained.
     *
     * <p>Refactoring Rationale: the baseline performs an UNBOUNDED, forward-only sweep of the whole
     * customer master -- {@code PERFORM UNTIL END-OF-FILE = 'Y'} at L74 of {@code app/cbl/CBCUS01C.cbl},
     * driving the read at L93 of its paragraph {@code 1000-CUSTFILE-GET-NEXT.} declared at L92 -- and the
     * Java implements a bounded ascending scan over the same key in the same order. The divergence is
     * documented rather than silent: it is the one behavioural difference between the two, and it is what
     * makes the surface safe to expose over HTTP, because a batch reader writing to a print stream can
     * afford to read an entire master in one step and an operation answering a request cannot stream one
     * into a single response. What a caller can observe of the ORDER those rows arrive in and the ACCESS
     * PATH they are reached by is unchanged, so the pages concatenate to the sequence the sweep would have
     * written.</p>
     *
     * <p>Assumptions: whether a further page exists is settled by reading ONE ROW MORE than will be
     * published and observing whether it arrives, so the surplus row is a probe and not content. This case
     * pins that it is withheld: the page reports a further page while carrying exactly the width asked for,
     * and the probe row's identifier is absent from the body. A page that returned the probe would give a
     * caller a duplicate on its next request, because the next request resumes strictly after the last row
     * it was shown.</p>
     *
     * @throws Exception if the request cannot be performed or the response body cannot be read
     */
    @Test
    @DisplayName("the scan publishes the page asked for and withholds the probe row")
    void theScanPublishesThePageAskedForAndWithholdsTheProbeRow() throws Exception {
        String leading = sealedPosition("1");
        String trailing = sealedPosition("3");

        when(reads.listCustomers(isNull(), eq(PAGE_WIDTH)))
                .thenReturn(PageResponse.ofRows(
                        pageOf("000000001", "000000002", "000000003"),
                        leading, trailing, true, false));

        this.mockMvc.perform(get(SCAN_ROUTE).param("size", String.valueOf(PAGE_WIDTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(PAGE_WIDTH))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.hasPrevious").value(false))
                .andExpect(jsonPath("$.firstKey").value(leading))
                .andExpect(jsonPath("$.lastKey").value(trailing))
                // WHY : Assumptions: every published identifier is named BY POSITION rather than the
                //       probe row's value being sought anywhere in the body. A body-wide search for a
                //       digit run can match an unrelated member -- the electronic-funds account
                //       identifier at L20 of app/cpy/CVCUS01Y.cpy is drawn from the same alphabet -- so
                //       it can fail on a collision and, worse, pass on one. Naming each position fails
                //       for the three things that matter: a probe row published, a real row dropped to
                //       make room for it, and the ascending order of L31 and L32 not being kept.
                .andExpect(jsonPath("$.items[0].customerId").value("000000001"))
                .andExpect(jsonPath("$.items[1].customerId").value("000000002"))
                .andExpect(jsonPath("$.items[2].customerId").value("000000003"));
    }

    /**
     * The final page of the scan reports no further page while still naming both of its boundaries.
     *
     * <p>Purpose: this is the other side of the probe read. When the surplus row does not arrive, the page
     * is the last one and says so, and it continues to name its trailing boundary -- which is what a caller
     * paging backward off the last page seeks from, so the boundary is not dropped merely because nothing
     * lies ahead. The earlier page it followed is reported present, matching the direction the reference's
     * own browse state distinguishes.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the final page reports no further page and still names both boundaries")
    void theFinalPageReportsNoFurtherPage() throws Exception {
        String leading = sealedPosition("7");
        String trailing = sealedPosition("8");

        when(reads.listCustomers(isNull(), anyInt()))
                .thenReturn(PageResponse.ofRows(
                        pageOf("000000007", "000000008"), leading, trailing, false, true));

        this.mockMvc.perform(get(SCAN_ROUTE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.hasPrevious").value(true))
                .andExpect(jsonPath("$.lastKey").value(trailing));
    }

    /**
     * An exhausted scan publishes no rows and neither boundary, so there is nothing to resume from.
     *
     * <p>Purpose: the reference's sweep ends when its read reports end-of-file, and the migrated form
     * expresses the same terminal state as a page with no rows. This case pins that both boundary members
     * are absent on that page and that neither availability indicator is set, which together mean a client
     * has been told the scan is over and given no position it could send back.</p>
     *
     * <p>Assumptions: the body is additionally asserted to hold no sealed token at all, which is stronger
     * than reading the two members and independent of how an absent member is represented. A boundary
     * published on an exhausted page would let a client resume a scan that had ended, and the token's
     * version prefix is the one part of its shape that is fixed and searchable.</p>
     *
     * @throws Exception if the request cannot be performed or the response body cannot be read
     */
    @Test
    @DisplayName("an exhausted scan publishes no rows and neither boundary")
    void anExhaustedScanPublishesNeitherBoundary() throws Exception {
        when(reads.listCustomers(isNull(), anyInt())).thenReturn(PageResponse.empty());

        this.mockMvc.perform(get(SCAN_ROUTE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.hasPrevious").value(false))
                .andExpect(jsonPath("$.firstKey", nullValue()))
                .andExpect(jsonPath("$.lastKey", nullValue()))
                .andExpect(content().string(not(containsString(CursorToken.VERSION + "."))));
    }

    /**
     * Paging forward resumes from the trailing boundary the previous page named, with no row seen twice.
     *
     * <p>Purpose: this pins the continuity property that makes the two pages of a scan join up. The value a
     * client sends back is exactly the trailing boundary it was given, the service is asked to resume from
     * that value and no other, and the page it answers with shares no row with the page before it.</p>
     *
     * <p>Alternatives Considered: positioning the page by an offset or a page number was evaluated and
     * rejected on correctness rather than on cost. An offset is evaluated against the table as it stands
     * when each page is fetched, so a row inserted or removed between two fetches shifts the window and the
     * caller silently skips a row or receives the same row twice -- neither of which it can detect. A
     * position naming the last key seen is not moved by a concurrent write. The baseline's own browse is
     * already key-ordered, reached through {@code ACCESS MODE IS SEQUENTIAL} at L31 of
     * {@code app/cbl/CBCUS01C.cbl} over {@code RECORD KEY IS FD-CUST-ID} at L32, so keyset positioning
     * preserves observable behaviour that offset positioning would change. No count of any kind is
     * published for the same reason: there is nothing in the reference's browse state to migrate one
     * from.</p>
     *
     * @throws Exception if either request cannot be performed or a response body cannot be read
     */
    @Test
    @DisplayName("paging forward resumes from the trailing boundary with no row seen twice")
    void pagingForwardResumesFromTheTrailingBoundary() throws Exception {
        String openingLeading = sealedPosition("1");
        String openingTrailing = sealedPosition("2");
        String nextLeading = sealedPosition("3");
        String nextTrailing = sealedPosition("4");

        when(reads.listCustomers(isNull(), eq(PAGE_WIDTH)))
                .thenReturn(PageResponse.ofRows(
                        pageOf("000000001", "000000002"), openingLeading, openingTrailing, true, false));
        when(reads.listCustomers(eq(openingTrailing), eq(PAGE_WIDTH)))
                .thenReturn(PageResponse.ofRows(
                        pageOf("000000003", "000000004"), nextLeading, nextTrailing, false, true));

        this.mockMvc.perform(get(SCAN_ROUTE)
                        .param("size", String.valueOf(PAGE_WIDTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastKey").value(openingTrailing))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].customerId").value("000000001"))
                .andExpect(jsonPath("$.items[1].customerId").value("000000002"));

        this.mockMvc.perform(get(SCAN_ROUTE)
                        .param("cursor", openingTrailing)
                        .param("size", String.valueOf(PAGE_WIDTH)))
                .andExpect(status().isOk())
                // WHY : Assumptions: the second page's identifiers are named BY POSITION, which pins
                //       both halves of the continuity property at once -- a repeated row and a skipped
                //       row each change what sits at a position, so neither can pass. An absence
                //       assertion over the raw body was tried first and rejected: it matches any member
                //       drawn from the same alphabet, so it fails on an unrelated collision and would
                //       equally pass on one. Repetition and omission under a concurrent write are the
                //       specific failures keyset positioning exists to prevent, so they are what this
                //       names.
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].customerId").value("000000003"))
                .andExpect(jsonPath("$.items[1].customerId").value("000000004"));

        // WHY : Assumptions: the argument the service received is verified rather than inferred from the
        //       answer, because a handler that discarded the position would still be answered by the
        //       stub above through its no-position overload and the case would pass while the scan
        //       silently restarted at the beginning on every request.
        verify(reads).listCustomers(openingTrailing, PAGE_WIDTH);
    }

    /**
     * The carried absence sentence fits the program-side return channel and not the informational one.
     *
     * <p>Purpose: the reference composes its two user-visible channels at different widths, and the wider
     * of them is the contract this surface's aggregate sentence answers to. This case measures the sentence
     * the absent-customer outcome carries against both, pinning that it fits the seventy-five-character
     * return channel and that it could not have been composed in the forty-character informational one --
     * which is what places it in the return channel rather than merely asserting a number.</p>
     *
     * <p>Trade-offs: the PROGRAM-side widths are the contract and the screen-side widths are not, and the
     * two disagree. The reference declares {@code 05  WS-INFO-MSG  PIC X(40).} at L110 of
     * {@code app/cbl/COACTVWC.cbl} and {@code 05  WS-RETURN-MSG  PIC X(75).} at L117, while its symbolic
     * maps declare the same two channels wider -- {@code INFOMSGI PIC X(45)} and {@code ERRMSGI PIC X(78)}
     * at L234 and L240 of {@code app/cpy-bms/COACTVW.CPY}, and at L318 and L324 of
     * {@code app/cpy-bms/COACTUP.CPY}. Forty and seventy-five are taken because those are the widths the
     * program composes in, and the map fields are the terminal's rendering of them with room to spare;
     * adopting the wider pair would let a sentence pass here that the program itself would truncate. The
     * cost accepted is that a screen-side reader sees five and three characters of headroom this contract
     * does not use.</p>
     */
    @Test
    @DisplayName("the carried absence sentence fits the program-side return channel")
    void theCarriedSentenceFitsTheProgramSideReturnChannel() {
        assertThat(NOT_FOUND_SENTENCE.length())
                .as("the aggregate sentence must fit the channel the reference composes it in")
                .isLessThanOrEqualTo(AGGREGATE_CHANNEL_WIDTH);

        assertThat(NOT_FOUND_SENTENCE.length())
                .as("a sentence this long belongs to the return channel, not the informational one")
                .isGreaterThan(INFORMATION_CHANNEL_WIDTH);
    }

    /**
     * An unauthenticated read of the customer subtree is refused before the read path is entered.
     *
     * <p>Purpose: this is one of the two cases that carries the DEPLOYED filter chain, so the refusal
     * asserted is produced by the rule the service enforces rather than by a substitute. The read path is
     * asserted untouched, because a refusal that still reached it would mean the guard sat behind the
     * handler rather than in front of it.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an unauthenticated customer read is refused and never reaches the read path")
    void anUnauthenticatedCustomerReadIsRefused() throws Exception {
        guarded.perform(get(SCAN_ROUTE))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reads);
    }

    /**
     * Neither business group authority reaches the customer subtree on the end-user chain.
     *
     * <p>Purpose: {@code SecurityConfig} governs the end-user surface and refuses this subtree outright,
     * and this case pins that rule rather than a hoped-for one. The two authorities presented are the two
     * the configuration itself publishes as one list, so the case cannot pass by naming a group the chain
     * never checks; both are exercised because a rule that had opened to one of them would still refuse a
     * case presenting only the other.</p>
     *
     * <p>Assumptions: the pair descends from the reference's own two-value domain,
     * {@code CDEMO-USER-TYPE PIC X(01)} at L26 of {@code app/cpy/COCOM01Y.cpy}, whose conditions at L27 and
     * L28 admit an administrator and an ordinary user and nothing else. Nothing here widens that domain,
     * and the refusal is the correct outcome rather than a gap: no baseline screen reads a customer record
     * directly, so admitting a group on this subtree would ADD a capability instead of preserving one. The
     * scope-bearing internal chain that does serve these addresses is asserted where it is declared, in the
     * sibling configuration test, and is deliberately not re-asserted here.</p>
     *
     * @throws Exception if any request cannot be performed
     */
    @Test
    @DisplayName("neither business group authority reaches the customer subtree")
    void neitherBusinessGroupAuthorityReachesTheCustomerSubtree() throws Exception {
        for (String authority : SecurityConfig.BUSINESS_AUTHORITIES) {
            guarded.perform(get(SCAN_ROUTE)
                            .with(jwt().authorities(new SimpleGrantedAuthority(authority))))
                    .andExpect(status().isForbidden());
        }

        assertThat(SecurityConfig.BUSINESS_AUTHORITIES)
                .as("the published set is the reference's two-value user-type domain and no wider")
                .hasSize(2);
        verifyNoInteractions(reads);
    }

    /**
     * A caller-supplied user type or role changes nothing about either outcome.
     *
     * <p>Purpose: identity reaches this service only as claims of a validated token, so a value a caller
     * puts in the request naming its own user type or role must be inert. This case sends both spellings on
     * both operations and asserts each response is byte-identical to the same request without them, and that
     * the read path was asked exactly the same thing.</p>
     *
     * <p>Refactoring Rationale: the reference was in a different position. It carried the user type in the
     * communication area the terminal echoed back, {@code CDEMO-USER-TYPE PIC X(01)} at L26 of
     * {@code app/cpy/COCOM01Y.cpy} with its two conditions at L27 and L28, so a client was able to assert
     * its own type. Here the distinction arrives signed and the request cannot carry it at all; that is a
     * genuine narrowing of what a client can influence, and it is documented rather than presented as
     * parity.</p>
     *
     * @throws Exception if any request cannot be performed or a response body cannot be read
     */
    @Test
    @DisplayName("a caller-supplied user type or role changes neither outcome")
    void aCallerSuppliedUserTypeChangesNothing() throws Exception {
        when(reads.listCustomers(isNull(), anyInt())).thenReturn(PageResponse.empty());
        when(reads.readCustomer(CUSTOMER_ID)).thenReturn(customer());

        String plainScan = bodyOf(get(SCAN_ROUTE));
        String claimedScan = bodyOf(get(SCAN_ROUTE)
                .param("userType", "A")
                .param("role", "carddemo-admin"));

        assertThat(claimedScan)
                .as("a claimed user type must leave the scan response byte-identical")
                .isEqualTo(plainScan);

        String plainRecord = this.mockMvc.perform(post(RECORD_ROUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECORD_BODY))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String claimedRecord = this.mockMvc.perform(post(RECORD_ROUTE)
                        .param("userType", "A")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECORD_BODY))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(claimedRecord)
                .as("a claimed user type must leave the keyed response byte-identical")
                .isEqualTo(plainRecord);

        // WHY : Assumptions: the read path is verified to have been asked the SAME question twice, because
        //       two identical bodies could also be produced by a handler that had branched on the claimed
        //       value and happened to reach the same answer.
        verify(reads, times(2)).readCustomer(CUSTOMER_ID);
    }

    /**
     * Lists the declared member names of a record type, in declaration order.
     *
     * <p>Assumptions: the names are read from the record's own declaration rather than from a serialised
     * document, because a case that must prove a member is ABSENT cannot do so from a document -- an absent
     * member and a member serialised as null are indistinguishable once null suppression is in play.
     * Reading the declaration answers the question the assertions actually ask.</p>
     *
     * @param recordType the record type to describe; must be a record and must not be {@code null}
     * @return the declared member names in declaration order, never {@code null}
     */
    private static List<String> componentNamesOf(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Reports whether a member name would carry a card verification value.
     *
     * <p>Assumptions: two spellings are judged rather than one, because the value has a compact acronym and
     * an expanded name and a later contributor could reach for either. The comparison is folded in a fixed
     * locale so it cannot change behaviour with the host's default one.</p>
     *
     * @param componentName the declared member name to judge; must not be {@code null}
     * @return {@code true} when the name would carry a card verification value, {@code false} otherwise
     */
    private static boolean namesACardVerificationValue(String componentName) {
        String folded = componentName.toLowerCase(Locale.ROOT);
        return folded.contains("cvv") || folded.contains("verification");
    }

    /**
     * Seals a boundary token the page envelope will accept.
     *
     * <p>Assumptions: the envelope's canonical constructor refuses a boundary that is not a sealed token,
     * because a boundary built from the key columns would publish those columns to a client. The token is
     * therefore minted by a real sealer rather than written as a literal, and its shape is asserted so a
     * fixture cannot drift out of the accepted form and begin failing for a reason unrelated to the case
     * using it.</p>
     *
     * <p>Alternatives Considered: opening the token to read the key back, which no case here needs and which
     * would add a participant to assertions that are about the envelope's members rather than about
     * cryptography. The sealing itself is asserted in the shared kernel's own tests.</p>
     *
     * @param boundaryKey the key column value this boundary names, kept short and obviously synthetic; must
     *     not be {@code null}
     * @return a token satisfying the shape the envelope enforces, never {@code null}
     */
    private static String sealedPosition(String boundaryKey) {
        String sealed = FIXTURE_SEALER.seal("account.customers.contract-test", boundaryKey);
        assertThat(CursorToken.hasSealedShape(sealed))
                .as("a fixture boundary must satisfy the shape the envelope enforces")
                .isTrue();
        return sealed;
    }

    /**
     * Builds the customer document the keyed read is stubbed to answer with.
     *
     * <p>Assumptions: both protected identifiers arrive ALREADY REDUCED, matching the invariant the mapper
     * upholds upstream of the controller, so the fixture cannot suggest the handler does the masking. Every
     * value is text and obviously synthetic, and the third address line carries the city because the
     * reference declares no city field of its own.</p>
     *
     * @return a customer document carrying all eighteen published members, never {@code null}
     */
    private static CustomerResponse customer() {
        return customer(CUSTOMER_ID_DIGITS);
    }

    /**
     * Builds the customer document for one identifier, every other member held constant.
     *
     * <p>Assumptions: the identifier is the only member a caller varies, so the page fixtures below differ
     * in exactly the value the scan orders by and in nothing else. Holding the rest constant keeps a page
     * assertion that names a row unambiguous about which member identified it.</p>
     *
     * @param customerId the nine-digit identifier this document publishes, as text; must not be
     *     {@code null}
     * @return a customer document for that identifier, never {@code null}
     */
    private static CustomerResponse customer(String customerId) {
        return new CustomerResponse(
                customerId,
                "JOHN",
                "Q",
                "PUBLIC",
                "1 SYNTHETIC WAY",
                "APT 2",
                CITY_VALUE,
                "NY",
                "USA",
                "10001-0000",
                "212-555-0100",
                "212-555-0101",
                CustomerMapper.IDENTIFIER_REDACTED,
                CustomerMapper.IDENTIFIER_REDACTED,
                "1955-01-01",
                "EFT0000042",
                "Y",
                "789");
    }

    /**
     * Builds one page's worth of customer documents, one per identifier supplied.
     *
     * <p>Assumptions: the rows are built in the order given and the order is the caller's, because the scan
     * publishes rows in ascending key order and a helper that sorted them would hide a handler that
     * did not.</p>
     *
     * @param customerIds the identifiers the page carries, in the order the scan publishes them; must not
     *     be {@code null} and must contain no {@code null} element
     * @return the rows of one page, never {@code null}
     */
    private static List<CustomerResponse> pageOf(String... customerIds) {
        List<CustomerResponse> rows = new ArrayList<>(customerIds.length);
        for (String customerId : customerIds) {
            rows.add(customer(customerId));
        }
        return List.copyOf(rows);
    }

    /**
     * Performs a request on the unguarded dispatcher and returns the response body it answered with.
     *
     * <p>Assumptions: the status is asserted successful inside this helper rather than by each caller,
     * because every case that compares two bodies depends on both requests having been answered; comparing
     * two refusal bodies would otherwise pass while proving nothing about the outcome.</p>
     *
     * @param request the request to perform, already addressed and parameterised; must not be {@code null}
     * @return the response body as text, never {@code null}
     * @throws Exception if the request cannot be performed or the response body cannot be read
     */
    private String bodyOf(MockHttpServletRequestBuilder request) throws Exception {
        return this.mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /**
     * Assembles the security-enabled context whose dispatcher answers this class's authority cases.
     *
     * <p>Assumptions: every participant in the authorization decision is the DEPLOYED one -- the rules and
     * the refusal renderers come from {@code new SecurityConfig().filterChain(...)}, and the
     * token-to-authentication translation from the same class's own factory, handed the two group names the
     * configuration itself publishes. Passing that list rather than two literals is what stops this wiring
     * and the rule it exercises drifting apart.</p>
     *
     * <p>Assumptions: the token DECODER is the one bean substituted, and the substitution is required rather
     * than convenient. The deployed factory for it resolves the issuer's provider document while the context
     * refreshes, so a context that created it could not start on a host with no route to the pinned issuer;
     * the request post-processor the cases use mints an authentication directly and never consults a decoder,
     * so nothing asserted here depends on the substitute's behaviour.</p>
     *
     * <p>Trade-offs: the MVC infrastructure is enabled here rather than inherited from a started
     * application, so this context's message converters are the framework's defaults. No case driven through
     * this dispatcher reads a body, which is why that costs nothing: both of them assert a status and that
     * the read path was never entered.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class GuardedSliceWiring {

        /**
         * Supplies the pinned clock the refusal renderers stamp their bodies from.
         *
         * @return a clock pinned to {@link CustomerControllerTest#PINNED_INSTANT}, never {@code null}
         */
        @Bean
        Clock clock() {
            return Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC);
        }

        /**
         * Supplies the controller under assertion, over the same substituted read path.
         *
         * @return the controller, wired exactly as a deployment wires it, never {@code null}
         */
        @Bean
        CustomerController customerController() {
            return new CustomerController(reads);
        }

        /**
         * Supplies the shared error advice so a refusal reaching the handler renders the deployed body.
         *
         * <p>Assumptions: registered explicitly because it lives outside this context's scan root and
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
