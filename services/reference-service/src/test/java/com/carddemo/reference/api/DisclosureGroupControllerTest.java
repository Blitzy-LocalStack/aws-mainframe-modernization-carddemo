// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/api/DisclosureGroupControllerTest.java
// -----------------------------------------------------------------------------
// Purpose:
//       Boundary cases over DisclosureGroupController, the one route that publishes a disclosure
//       rate. Everything asserted below is observable from outside the process: the shape of the
//       reply, the JSON token the rate is written as, the indicator that reports which account
//       group answered, the status a key that resolves to nothing renders, and the authority the
//       deployed chain demands of a read. No rule is re-derived here; the rate rules live in
//       com.carddemo.reference.service and the seeded rows in com.carddemo.reference.repository.
//
// WHY (non-obvious design decisions):
//   1.  Assumptions: the four category labels below are written in the plural, unwrapped form the
//       rule document lists at its lines 31 to 34 -- Alternatives Considered, Refactoring
//       Rationale, Assumptions and Trade-offs. The singular and parenthesised spellings mean the
//       same thing and are simply not used; that equivalence is recorded in this sentence alone
//       and the forms are not mixed anywhere in this file. The hyphen in the trade-offs label is
//       the ASCII hyphen-minus, taken from the rule document rather than from tests/README.md,
//       whose L548 is the very line listing these labels and renders that label with a
//       non-breaking hyphen followed by an em dash -- two characters indistinguishable on screen
//       from their ASCII counterparts, either of which would put a byte outside ASCII into a file
//       whose documentation audit is configured for one character set.
//   2.  Assumptions: the shared advice is handed to the unguarded dispatcher BY HAND, and every
//       status assertion in this file rests on that. com.carddemo.common.error
//       .GlobalExceptionHandler carries the only @RestControllerAdvice in this migration, at L177
//       of its own source, and it sits outside the com.carddemo.reference scan root; the module
//       entry point receives it from com.carddemo.common.CardDemoCommonAutoConfiguration rather
//       than naming it. A dispatcher assembled by standaloneSetup has no context to discover it
//       from, and omitting the registration does not fail loudly: the dispatcher falls back to
//       the framework's default error handling, the assertions observe that instead of the real
//       mapping, and the 404 below would read as green while the real mapping was broken.
//   3.  Alternatives Considered: the context-slicing web-test annotation, which would have
//       discovered the advice through an imported auto-configuration instead. It is unavailable
//       rather than declined: the framework release in use moved its test slices into per-module
//       artifacts, the only slice on this classpath is the JSON one, and the artifact carrying
//       the web-mvc slice is not a declared dependency and may not become one. The mechanism used
//       instead is the one this package publishes -- a real dispatcher over a hand-built
//       controller -- and it registers the advice explicitly, which is the property the
//       assertions actually depend on.
//   4.  Assumptions: TWO dispatchers are built rather than one, and the split is load-bearing in
//       both directions. The unguarded dispatcher installs no filter chain, so a 404 from it can
//       only mean the key resolved to nothing and never that a caller was refused -- which is
//       what makes the terminal-miss case below unambiguous. The guarded dispatcher installs the
//       deployed chain, which is the only way to observe what a read actually demands of a
//       caller. Merging them would cost the first property to gain the second.
//   5.  Assumptions: the rate is written as a JSON STRING because
//       com.carddemo.common.money.MoneyModule binds its serialiser to the Money TYPE at its L269,
//       not to the decimal it wraps, and this file asserts the JSON TOKEN rather than only its
//       text. The consequence of the other form is specific: a JSON number is parsed into an
//       IEEE-754 binary64 value by most clients, and this rate is one of the two operands of
//       COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200 at L464 to L465 of
//       app/cbl/CBACT04C.cbl, inside the paragraph opening at its L462, so a rate that arrived
//       slightly wrong would be scaled by a balance rather than staying the size it entered as.
//   6.  Assumptions: every numeric comparison below is made by value with the scale asserted
//       separately, and equality is used on no decimal anywhere. Numeric comparison is
//       deliberately scale-insensitive, so a rate that arrived spelled 15 or 15.0 would satisfy
//       it, while a decimal spelled 15 is NOT equal by the equality contract to one spelled 15.00,
//       so equality would reject a value that is numerically right. The pair is what makes these
//       cases equivalent to the column contract instead of to half of it, and it is why equality
//       appears on no decimal anywhere in this file.
//   7.  Assumptions: the substituted group is TEN characters wide with three trailing blanks.
//       L437 of app/cbl/CBACT04C.cbl moves the seven-character literal 'DEFAULT' into
//       FD-DIS-ACCT-GROUP-ID, which its L79 declares PIC X(10) and app/cpy/CVTRA02Y.cpy L6
//       declares the same width on the record, and an alphanumeric move into a wider alphanumeric
//       field left-justifies and space-fills. A case that wrote the unpadded name into an
//       assertion would be comparing against its own transcription rather than against the value
//       the service looks up.
//   8.  Alternatives Considered: exercising the substitution on any (type, category) pair other
//       than 07 and 0001. Rejected on measurement: app/data/ASCII/discgrp.txt holds three account
//       groups over the same seventeen pairs, and sixteen of those pairs price identically under
//       A000000000 and the substituted group, so an assertion on one of them passes whether or
//       not the substitution fired and would keep passing if the branch were deleted. At 07 and
//       0001 the two rates differ, which is what lets the case fail.
//   9.  Assumptions: the terminal miss is driven with type 01 and category 0005 because that pair
//       is the one the seed leaves unpriced in EVERY group, the substituted group included.
//       app/data/ASCII/trancatg.txt carries eighteen pairs against the seventeen
//       app/data/ASCII/discgrp.txt prices, and the difference is exactly that pair. The counts
//       are cited as evidence and are not re-proved here; they are owned where those bytes are
//       owned, together with the line-ending asymmetry between the two files -- the category and
//       type extracts end their records with a carriage return and line feed while the rate
//       extract ends with a line feed alone -- which is why no position here is derived by
//       multiplying a record width by a row number.
//  10.  Refactoring Rationale: that terminal miss renders 404 where the reference program does not
//       continue at all. app/cbl/CBACT04C.cbl reports the condition at its L455 and performs the
//       abend paragraph from its L458, which is the paragraph at L628 to L632 issuing a genuine
//       language-environment abend. The baseline behaves as those lines describe and is not
//       altered by this migration; the Java reports the condition to the caller instead; and the
//       divergence is registered in docs/architecture/cobol-to-service-traceability.md, a
//       document owned elsewhere and referenced from here. A neighbouring paragraph name means
//       something different and is easy to merge with it: the similarly-named paragraph of
//       app/app-transaction-type-db2/cbl/COBTUPDT.cbl at its L230 to L233 sets a warning return
//       code and exits normally.
//  11.  Assumptions: the sentence that miss renders reaches the caller VERBATIM only because it is
//       within the seventy-five characters the shared error body publishes as its rendering width,
//       read from CCARD-ERROR-MSG PIC X(75) at L28 of app/cpy/CVCRD01Y.cpy and the identically
//       declared CCARD-RETURN-MSG on its L29. A longer sentence is replaced wholesale by the
//       generic one, so the width is not decoration here -- it is the mechanism. The two message
//       fields of the baseline differ in their off sentinel, SPACES at L168 of
//       app/app-transaction-type-db2/cbl/COTRTUPC.cbl against LOW-VALUES at L30 of that copybook,
//       and neither sentinel travels the wire; only the width does.
//  12.  Trade-offs: the fixture set below is bounded to the three keys each case needs -- one
//       priced pair under the group asked for, the same pair under the substituted group, and the
//       unpriced pair -- rather than walking every seeded combination. What is given up is
//       breadth, and it is given up deliberately: the exhaustive per-row cases belong to the
//       container-backed classes in com.carddemo.reference.repository, which read the rows
//       instead of stubbing them, and a boundary case that walked all of them would report a
//       seeding defect as a boundary defect.
// =============================================================================
package com.carddemo.reference.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.AbendDetail;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reference.config.SecurityConfig;
import com.carddemo.reference.domain.DisclosureGroup;
import com.carddemo.reference.domain.TransactionCategory;
import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.DisclosureGroupRateResponse;
import com.carddemo.reference.service.DisclosureGroupService;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverters;
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
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds the boundary cases for the disclosure-group rate route and nothing beneath that boundary.
 *
 * <p>Purpose: this class answers what a caller of the rate route actually receives. Six questions
 * are settled here and are settled nowhere else: whether the reply is one object rather than a
 * window over rows, which JSON token the rate is written as, whether the indicator reporting the
 * substituted group reaches the caller in both of its states, which status a key that resolves to
 * nothing renders and with which sentence, whether the three key components survive the round trip
 * at their declared widths, and what authority the deployed chain demands of a read.</p>
 *
 * <p>Assumptions: what this class must NOT restate is as fixed as what it asserts. The rate rules
 * -- which read runs first, when the substitution is reached, what the raised condition carries --
 * belong to {@code com.carddemo.reference.service}. The seeded rows belong to the container-backed
 * classes in {@code com.carddemo.reference.repository}. The module-wide prohibition on inexact
 * arithmetic, and the same string-versus-number property asserted over the transfer object with a
 * bare mapper, belong to {@code ReferenceMoneyPathRulesTest} at this subtree's root; the case here
 * drives a real dispatcher and its message converter instead, which is the half that test cannot
 * see. The refusal of a malformed path segment, and the census of which operations are mounted,
 * belong to the sibling classes in this package. A property asserted in two places can be
 * satisfied in one of them and reported as satisfied in both, and the weaker assertion is then the
 * one nobody maintains while everybody still trusts it.</p>
 *
 * <p>Assumptions: no golden master covers this route. {@code tests/README.md} records at its L83 to
 * L85 that the online programs cannot be run end to end without a terminal-services runtime, which
 * the runner does not have, so parity for this path rests on transcribed logic together with the
 * record-layout and schema contracts. That makes these assertions primary evidence rather than a
 * supplement to an earlier comparison, and it is why each one cites the line it comes from. Rate
 * rows do exist under the baseline suite's interest fixtures, but they are inputs to that suite's
 * batch comparison and never a dependency of anything here.</p>
 *
 * <p>Trade-offs: two dispatchers are assembled instead of one, which is more wiring than a single
 * builder would need. The compromise buys two properties that cannot be held at once by one
 * dispatcher: without a filter chain a 404 can only mean the key, and with the deployed chain the
 * authority a read demands becomes observable. Naming both in one class also keeps the two
 * readings of a 404 beside each other, where a reader cannot mistake one for the other.</p>
 *
 * <p>On parameters, return values and exceptions at the type level: a test class is instantiated by
 * the runner through its implicit no-argument constructor, accepts nothing from a caller and
 * returns nothing, so this descriptor carries no parameter or return at-clause. The inapplicability
 * is written down rather than left silent because the Explainability rule lists a docstring that
 * omits its parameters or return values among its forbidden patterns at its line 39, and a reader
 * has to be able to tell a declared inapplicability from an oversight. Every member below carries
 * its own at-clauses, including the private helpers, because the documentation audit that governs
 * this file reads test sources and filters method visibility down to private.</p>
 */
@DisplayName("the disclosure-group rate route at its HTTP boundary")
class DisclosureGroupControllerTest {

    /**
     * The account group the seed prices at fifteen percent for the pair these cases drive.
     *
     * <p>Assumptions: this is one of the three account groups {@code app/data/ASCII/discgrp.txt}
     * carries, and it is already exactly the ten characters the stored key declares, so a request
     * naming it reaches the resolver unchanged. That matters for the stubs below: a shorter name
     * would be padded by the handler and the stub would have to anticipate the padded form.</p>
     */
    private static final String GROUP_PRICED = "A000000000";

    /**
     * An account group no seeded row carries, used to reach the substitution.
     *
     * <p>Assumptions: ten characters again, and deliberately outside the three the seed carries,
     * so the first read misses and the substituted group is the only one left to answer.</p>
     */
    private static final String GROUP_ABSENT = "Z999999999";

    /**
     * The transaction type of the one pair whose rate differs between the two account groups.
     *
     * <p>Assumptions: type {@code 07} with category {@code 0001} is the only pair in the seed
     * priced differently under {@code A000000000} and the substituted group, which is what makes a
     * substitution case here able to fail rather than merely able to pass.</p>
     */
    private static final String TYPE_DISCRIMINATING = "07";

    /**
     * The transaction category of that same discriminating pair.
     *
     * <p>Assumptions: four characters with its leading zeros intact.
     * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} declares
     * {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL} at its L3 and is the authority on the stored
     * shape, where {@code app/cpy/CVTRA04Y.cpy} L7 declares the numeric picture
     * {@code PIC 9(04)}; read as a number this value would address {@code 1} and match no row.</p>
     */
    private static final String CATEGORY_DISCRIMINATING = "0001";

    /**
     * The transaction type of the one pair the seed leaves unpriced in every account group.
     *
     * <p>Assumptions: type {@code 01} with category {@code 0005} is present in the category
     * extract and absent from the rate extract under all three account groups, the substituted one
     * included, so a lookup for it misses both reads and reaches the terminal condition.</p>
     */
    private static final String TYPE_UNPRICED = "01";

    /** The transaction category of that unpriced pair, four characters with its leading zero. */
    private static final String CATEGORY_UNPRICED = "0005";

    /**
     * The rate the seed carries for the priced pair, as the wire spells it.
     *
     * <p>Assumptions: the seeded bytes are <code>00150{</code>, a zoned decimal whose trailing
     * brace is the positive-zero overpunch of its low-order digit, giving {@code 001500} against the
     * implied two fractional places of {@code DIS-INT-RATE PIC S9(04)V99} at L9 of
     * {@code app/cpy/CVTRA02Y.cpy}. The byte-level tables that establish that decoding are owned
     * by the descriptor of {@code src/test/resources/fixtures} and are honoured here rather than
     * restated.</p>
     */
    private static final String RATE_PRICED_TEXT = "15.00";

    /**
     * The rate the substituted group carries for that same pair, as the wire spells it.
     *
     * <p>Assumptions: <code>00000{</code> in the seed, which is the whole point of choosing this
     * pair: it differs from the other group's rate, so an assertion on the value distinguishes the two
     * reads instead of passing under either.</p>
     */
    private static final String RATE_SUBSTITUTED_TEXT = "0.00";

    /**
     * The reply member carrying the rate, as the published contract names it.
     *
     * <p>Assumptions: {@code src/main/resources/openapi/reference-api.yaml} declares this member
     * on its {@code DisclosureGroupRate} schema and gives it the {@code InterestRate} schema,
     * which is {@code type: string} with an expression admitting up to four integer digits and
     * exactly two fractional ones -- never a numeric type. The browser client is written against
     * that same document, so this name is a published identifier rather than an internal one.</p>
     */
    private static final String RATE_MEMBER = "interestRate";

    /** The reply member reporting which account group actually supplied the rate. */
    private static final String APPLIED_GROUP_MEMBER = "appliedAcctGroupId";

    /** The reply member echoing the account group the caller asked about. */
    private static final String REQUESTED_GROUP_MEMBER = "requestedAcctGroupId";

    /** The reply member reporting whether the substitution supplied the rate. */
    private static final String FALLBACK_MEMBER = "defaultGroupApplied";

    /** The reply member echoing the transaction type of the rate returned. */
    private static final String TYPE_MEMBER = "tranTypeCd";

    /** The reply member echoing the transaction category of the rate returned. */
    private static final String CATEGORY_MEMBER = "tranCatCd";

    /**
     * An authority outside the two the deployed chain admits.
     *
     * <p>Assumptions: a scope authority is presented rather than an invented third group name, so
     * the refusal case cannot pass by naming something the chain never checks. The two admitted
     * authorities are read from {@link SecurityConfig#BUSINESS_AUTHORITIES} and never retyped.</p>
     */
    private static final String UNRELATED_AUTHORITY = "SCOPE_openid";

    /**
     * The instant both dispatchers read the clock at.
     *
     * <p>Assumptions: the clock is fixed rather than live because the shared error body stamps a
     * timestamp into every refusal it renders. With a fixed clock two identical refusals are byte
     * identical, which is what lets the statelessness case compare whole bodies instead of
     * comparing them field by field around a moving value.</p>
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-02-01T00:00:00Z");

    /** The mapper the response bodies in this class are read back with. */
    private static final JsonMapper JSON = JsonMapper.builder().addModule(new MoneyModule()).build();

    /** The rate resolver, substituted because its rules are asserted in the service package. */
    private static DisclosureGroupService rates;

    /** The context backing the dispatcher that carries the deployed filter chain. */
    private static AnnotationConfigWebApplicationContext guardedContext;

    /** The dispatcher that carries the deployed filter chain, used only by the authority cases. */
    private static MockMvc guarded;

    /** The dispatcher that carries no filter chain, used by every body and status case. */
    private static MockMvc unguarded;

    /**
     * Builds both dispatchers over one substituted resolver, once for the class.
     *
     * <p>Trade-offs: the guarded context is assembled ONCE rather than per case, because starting
     * a security-enabled context is the most expensive thing this class does and no case mutates
     * it. What is given up is per-case isolation of the substituted resolver, which is bought back
     * by resetting it before each case.</p>
     *
     * <p>Assumptions: the unguarded dispatcher is given the shared advice through
     * {@code setControllerAdvice} and a converter carrying the money module through
     * {@code setMessageConverters}. Both are deliberate and both are load-bearing: without the
     * advice a refusal renders through the framework's default handling and the status assertions
     * stop describing the real mapping, and without the module the rate renders in a shape the
     * running service does not publish.</p>
     */
    @BeforeAll
    static void buildDispatchers() {
        rates = mock(DisclosureGroupService.class);

        unguarded = MockMvcBuilders
                .standaloneSetup(new DisclosureGroupController(rates))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JSON))
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();

        guardedContext = new AnnotationConfigWebApplicationContext();
        // WHY : Assumptions: a servlet context is installed BEFORE the refresh because the deployed
        //       chain is a servlet filter chain and the configuration that builds it is conditional
        //       on a servlet environment. Refreshing without one resolves no chain at all, and the
        //       symptom would be an authority case that admitted every caller rather than one that
        //       failed to start.
        guardedContext.setServletContext(new MockServletContext());
        guardedContext.register(GuardedChainWiring.class);
        guardedContext.refresh();

        // WHY : Assumptions: the configurer form is applied rather than adding the chain filter by
        //       hand, because the request post-processor that mints an authentication publishes it
        //       through the test context repository this configurer installs. Adding the filter
        //       alone would leave every minted token invisible to the chain, so the admitted case
        //       would be refused and the assertion would report an authority failure that the
        //       wiring, and not the rule, had caused.
        guarded = MockMvcBuilders.webAppContextSetup(guardedContext)
                .apply(springSecurity())
                .build();
    }

    /** Closes the guarded context so this class leaves no refreshed application behind it. */
    @AfterAll
    static void closeGuardedContext() {
        guardedContext.close();
    }

    /**
     * Clears the substituted resolver so no case inherits another case's stubbing.
     *
     * <p>Assumptions: both dispatchers share one substituted resolver, because the guarded context
     * holds it as a bean and is built once. Resetting here is what keeps the two independent of
     * each other's stubbing while still sharing the instance.</p>
     */
    @BeforeEach
    void clearResolver() {
        reset(rates);
    }

    /**
     * The shape of the reply, which is one object and never a window over rows.
     *
     * <p>Purpose: the composite key of a rate row resolves exactly one row, so this route answers
     * with a single object. {@code app/cpy/CVTRA02Y.cpy} opens the key group {@code DIS-GROUP-KEY}
     * at its L5 and spans its three members over L6 to L8 -- a ten-character account group, a
     * two-character transaction type and a four-character transaction category -- and the record it
     * keys is fifty bytes, closed by the one {@code FILLER PIC X(28)} at its L10. A reply wrapped in
     * a page envelope would publish a collection where the key admits one member.</p>
     */
    @Nested
    @DisplayName("on the shape of the reply")
    class OnTheShapeOfTheReply {

        /**
         * The reply is one JSON object carrying every member the transfer object declares.
         *
         * <p>Assumptions: the expected member set is read from the transfer object's own record
         * components rather than listed here, so a member added to the published reply without a
         * case being written for it still has to appear in the body. Listing the names instead
         * would let the two drift apart silently.</p>
         *
         * @throws Exception if the request cannot be performed or the body cannot be read
         */
        @Test
        @DisplayName("is one object carrying exactly the members the reply declares")
        void isOneObjectCarryingExactlyTheDeclaredMembers() throws Exception {
            stubPricedHit();

            JsonNode body = bodyOf(unguarded.perform(pricedRequest())
                    .andExpect(status().isOk())
                    .andReturn());

            assertThat(body.isObject())
                    .as("the reply must be a single JSON object, because the composite key resolves"
                            + " exactly one row")
                    .isTrue();
            assertThat(body.isArray())
                    .as("the reply must not be a JSON array, because a caller reading one rate would"
                            + " have to index into it")
                    .isFalse();

            List<String> declared = new ArrayList<>();
            for (RecordComponent component
                    : DisclosureGroupRateResponse.class.getRecordComponents()) {
                declared.add(component.getName());
            }
            // WHY : Trade-offs: the member set is compared EXACTLY rather than for containment. What
            //       is given up is tolerance of an additive member, which is deliberate: the
            //       published schema closes itself to further members, so a member appearing here
            //       that the contract does not declare is a contract change and not an extension,
            //       and containment would report it as conformance.
            assertThat(body.propertyNames())
                    .as("the body must carry exactly the members the reply type declares, no more"
                            + " and no fewer")
                    .containsExactlyInAnyOrderElementsOf(declared);
        }

        /**
         * No member of the shared page envelope appears anywhere in the reply.
         *
         * <p>Alternatives Considered: naming the envelope's members as literals in this assertion.
         * Rejected because the envelope's member set is a published contract of the shared kernel
         * and a member renamed there would leave a literal here matching nothing, so the assertion
         * would keep passing while no longer describing the envelope. Reading the names from the
         * envelope type itself also means this case states no member count of its own.</p>
         *
         * @throws Exception if the request cannot be performed or the body cannot be read
         */
        @Test
        @DisplayName("carries no member of the shared page envelope")
        void carriesNoMemberOfTheSharedPageEnvelope() throws Exception {
            stubPricedHit();

            JsonNode body = bodyOf(unguarded.perform(pricedRequest())
                    .andExpect(status().isOk())
                    .andReturn());

            for (RecordComponent component : PageResponse.class.getRecordComponents()) {
                assertThat(body.has(component.getName()))
                        .as("the reply must not carry the page-envelope member '%s'; this route"
                                + " answers with one row and never with a window over rows",
                                component.getName())
                        .isFalse();
            }
        }
    }

    /**
     * The wire form of the rate, which is a quoted decimal string and never a JSON number.
     *
     * <p>Purpose: this group asserts the JSON TOKEN the rate is written as, at the boundary a
     * caller actually reads, together with the value and the scale that token carries. The same
     * string-versus-number property is asserted over the transfer object with a bare mapper by
     * {@code ReferenceMoneyPathRulesTest}; what is added here is the dispatcher and its message
     * converter, which that class does not exercise and which is where a converter assembled
     * without the money module would change the answer.</p>
     */
    @Nested
    @DisplayName("on the wire form of the rate")
    class OnTheWireFormOfTheRate {

        /**
         * The rate is written as a JSON string token and not as a JSON number.
         *
         * <p>Alternatives Considered: comparing only the member's text against the expected
         * numerals. Rejected because a JSON number renders the same characters, so a text-only
         * comparison passes under exactly the form this case exists to catch. The token type is
         * therefore asserted in both directions, positively as a string and negatively as not a
         * number, so a reader can see from the case itself which failure it detects.</p>
         *
         * @throws Exception if the request cannot be performed or the body cannot be read
         */
        @Test
        @DisplayName("is a JSON string token and never a JSON number")
        void isAJsonStringTokenAndNeverAJsonNumber() throws Exception {
            stubPricedHit();

            JsonNode rate = rateOf(unguarded.perform(pricedRequest())
                    .andExpect(status().isOk())
                    .andReturn());

            assertThat(rate.isString())
                    .as("member '%s' must be serialised as a JSON string; the money module binds its"
                            + " serialiser to the money TYPE, so a member re-typed to a bare decimal"
                            + " would emit a JSON number that most clients parse into an IEEE-754"
                            + " binary64 value and round in the cents", RATE_MEMBER)
                    .isTrue();
            assertThat(rate.isNumber())
                    .as("member '%s' must not be serialised as a JSON number", RATE_MEMBER)
                    .isFalse();
        }

        /**
         * The string the rate is written as carries the seeded value at the scale its column
         * declares.
         *
         * <p>Assumptions: the value is compared by value and the scale is asserted separately,
         * because neither implies the other. Numeric comparison is deliberately insensitive to
         * scale, so a rate spelled {@code 15} or {@code 15.0} would satisfy it; equality would
         * reject a numerically correct value, since {@code new BigDecimal("15")} is not equal to
         * {@code new BigDecimal("15.00")}. Only the pair is equivalent to the column contract, and
         * that is why equality appears on neither line.</p>
         *
         * @throws Exception if the request cannot be performed or the body cannot be read
         */
        @Test
        @DisplayName("carries the seeded value at the scale its column declares")
        void carriesTheSeededValueAtTheDeclaredScale() throws Exception {
            stubPricedHit();

            // WHY : Assumptions: the decimal is rebuilt from the TEXT the response carried rather
            //       than read back off the substituted reply. Reading the stub would assert this
            //       class against a value it supplied itself and would hold whatever scale that
            //       value had; rebuilding from the wire is what makes the scale assertion below a
            //       statement about the rendering rather than about the fixture.
            BigDecimal onTheWire = new BigDecimal(rateOf(unguarded.perform(pricedRequest())
                    .andExpect(status().isOk())
                    .andReturn()).asString());

            assertThat(onTheWire)
                    .as("the rate on the wire must equal the seeded rate by value, at whatever scale"
                            + " either side is spelled")
                    .isEqualByComparingTo(RATE_PRICED_TEXT);
            assertThat(onTheWire.scale())
                    .as("the rate on the wire must carry the scale its owning column declares; a"
                            + " renderer that dropped trailing zeros would satisfy the value"
                            + " comparison above and fail here")
                    .isEqualTo(DisclosureGroup.INTEREST_RATE_SCALE);
        }
    }

    /**
     * The indicator reporting which account group supplied the rate, in both of its states.
     *
     * <p>Purpose: the reference program leaves this implicit. On a missing-record status it
     * overwrites its own group-id field with the substituted literal at L437 of
     * {@code app/cbl/CBACT04C.cbl} and re-reads, so after the second read the field no longer
     * records which group was asked for and nothing downstream can tell a substituted rate from a
     * specific one. The migrated reply states it, and this group asserts that the statement reaches
     * the caller in both states. Which read runs, and when the substitution is reached, is asserted
     * in the service package and is not re-derived here.</p>
     */
    @Nested
    @DisplayName("on the indicator reporting the substituted group")
    class OnTheSubstitutionIndicator {

        /**
         * A group with a row of its own reports no substitution and names itself as the answerer.
         *
         * @throws Exception if the request cannot be performed or the body cannot be read
         */
        @Test
        @DisplayName("reports no substitution when the group asked for has its own row")
        void reportsNoSubstitutionOnADirectHit() throws Exception {
            stubPricedHit();

            JsonNode body = bodyOf(unguarded.perform(pricedRequest())
                    .andExpect(status().isOk())
                    .andReturn());

            assertThat(body.get(FALLBACK_MEMBER).asBoolean())
                    .as("a group answering from its own row must report no substitution")
                    .isFalse();
            assertThat(body.get(APPLIED_GROUP_MEMBER).asString())
                    .as("the group that answered must be the group asked for on a direct hit")
                    .isEqualTo(GROUP_PRICED);
            assertThat(body.get(REQUESTED_GROUP_MEMBER).asString())
                    .as("the group asked for must be echoed back unchanged")
                    .isEqualTo(GROUP_PRICED);
            assertThat(new BigDecimal(body.get(RATE_MEMBER).asString()))
                    .as("the direct hit must answer with the group's own rate")
                    .isEqualByComparingTo(RATE_PRICED_TEXT);
        }

        /**
         * A group with no row of its own reports the substitution and names the padded group.
         *
         * <p>Assumptions: the group named as the answerer is compared against
         * {@link DisclosureGroupService#DEFAULT_ACCT_GROUP_ID} rather than against a retyped
         * literal, and its width and trailing blanks are asserted as well. The constant carries the
         * padding because L437 of {@code app/cbl/CBACT04C.cbl} moves a seven-character literal into
         * a field its L79 declares {@code PIC X(10)}, and an alphanumeric move into a wider
         * alphanumeric field left-justifies and space-fills, so the key actually read is ten
         * characters wide. A reply that trimmed it would publish a key addressing no row.</p>
         *
         * <p>Assumptions: this is a 200 and not a 404. The reference paragraph settles that at L422
         * of that program, whose test admits the record-not-found status alongside the clean one and
         * sets the same successful result for both, and its L436 turns that very status into the
         * substituted read rather than into a failure.</p>
         *
         * @throws Exception if the request cannot be performed or the body cannot be read
         */
        @Test
        @DisplayName("reports the substitution and names the ten-character padded group")
        void reportsTheSubstitutionAndNamesThePaddedGroup() throws Exception {
            stubSubstitutedHit();

            JsonNode body = bodyOf(unguarded.perform(absentGroupRequest())
                    .andExpect(status().isOk())
                    .andReturn());

            assertThat(body.get(FALLBACK_MEMBER).asBoolean())
                    .as("a group answered from the substituted row must report the substitution")
                    .isTrue();

            String applied = body.get(APPLIED_GROUP_MEMBER).asString();
            assertThat(applied)
                    .as("the group that answered must be the substituted group at its stored width")
                    .isEqualTo(DisclosureGroupService.DEFAULT_ACCT_GROUP_ID);
            assertThat(applied)
                    .as("the substituted group must reach the caller with its padding intact, not"
                            + " trimmed to the literal the reference program moves")
                    .hasSize(DisclosureGroup.ACCT_GROUP_ID_WIDTH)
                    .isNotEqualTo(DisclosureGroupService.DEFAULT_ACCT_GROUP_ID.strip())
                    .endsWith(" ");
            assertThat(body.get(REQUESTED_GROUP_MEMBER).asString())
                    .as("the group asked for must still be echoed back, so a caller reconciling a"
                            + " charge is not left inferring which of the two was substituted")
                    .isEqualTo(GROUP_ABSENT);
        }

        /**
         * The two states resolve different rates, which is what makes this pair discriminate.
         *
         * <p>Assumptions: the rate returned under the substitution differs from the rate the priced
         * group carries for the same type and category, and both are asserted in one case so the
         * contrast is visible from the case itself. Were these constants ever moved onto one of the
         * sixteen pairs the two groups price alike, this is the line that fails.</p>
         *
         * @throws Exception if either request cannot be performed or its body cannot be read
         */
        @Test
        @DisplayName("resolves a different rate by each route, so the pair can fail")
        void resolvesADifferentRateByEachRoute() throws Exception {
            stubPricedHit();
            stubSubstitutedHit();

            BigDecimal direct = new BigDecimal(rateOf(unguarded.perform(pricedRequest())
                    .andExpect(status().isOk())
                    .andReturn()).asString());
            BigDecimal substituted = new BigDecimal(rateOf(unguarded.perform(absentGroupRequest())
                    .andExpect(status().isOk())
                    .andReturn()).asString());

            assertThat(direct)
                    .as("the direct read must answer with the priced group's rate")
                    .isEqualByComparingTo(RATE_PRICED_TEXT);
            assertThat(substituted)
                    .as("the substituted read must answer with the substituted group's rate")
                    .isEqualByComparingTo(RATE_SUBSTITUTED_TEXT);
            assertThat(direct)
                    .as("the two rates must differ, or an assertion on either would pass whether or"
                            + " not the substitution fired")
                    .isNotEqualByComparingTo(substituted);
        }
    }

    /**
     * The status and body a key that neither read resolves renders.
     *
     * <p>Purpose: this is the one outcome the reference program does not survive, and the migrated
     * form reports it instead. Its second read accepts only the clean status, so L455 of
     * {@code app/cbl/CBACT04C.cbl} reports the condition and L458 performs the abend paragraph at
     * its L628 to L632, which issues a language-environment abend. The baseline behaves as those
     * lines describe and is not altered; the Java answers 404 carrying the shared error body; and
     * the divergence is registered in {@code docs/architecture/cobol-to-service-traceability.md}.
     * A 500 was available and was not taken: it would tell a caller the service malfunctioned,
     * where the fact being reported is that a key has no row.</p>
     */
    @Nested
    @DisplayName("on a key neither read resolves")
    class OnAKeyNeitherReadResolves {

        /**
         * The unpriced pair renders 404 carrying the shared error body.
         *
         * <p>Assumptions: the condition raised by the resolver derives from the absent-element type
         * the shared advice maps, which is the mechanism by which this becomes the published 404
         * rather than an unclassified 500. The code compared against is the shared not-found code
         * and is never retyped.</p>
         *
         * @throws Exception if the request cannot be performed or the body cannot be read
         */
        @Test
        @DisplayName("renders 404 carrying the shared error body")
        void rendersNotFoundCarryingTheSharedErrorBody() throws Exception {
            stubTerminalMiss();

            JsonNode body = bodyOf(unguarded.perform(unpricedRequest())
                    .andExpect(status().isNotFound())
                    .andReturn());

            assertThat(body.get("code").asString())
                    .as("the refusal must carry the shared not-found code, which is what a client"
                            + " branches on")
                    .isEqualTo(ApiError.CODE_NOT_FOUND);
            assertThat(body.get("status").asInt())
                    .as("the body's own status must agree with the response status")
                    .isEqualTo(404);
            assertThat(body.propertyNames())
                    .as("the refusal must be the shared error body, so every member that body"
                            + " declares is present for a client reading it generically")
                    .containsExactlyInAnyOrderElementsOf(declaredMembersOf(ApiError.class));
        }

        /**
         * The sentence reaches the caller verbatim, which the seventy-five-character width is what
         * permits.
         *
         * <p>Assumptions: the shared advice renders an absent-record sentence verbatim only while
         * it stays within the width the shared error body publishes as its rendering width, and
         * replaces a longer one wholesale with the generic sentence. That width is seventy-five
         * characters, read from {@code CCARD-ERROR-MSG PIC X(75)} at L28 of
         * {@code app/cpy/CVCRD01Y.cpy} and from the identically declared {@code CCARD-RETURN-MSG}
         * on its L29, and it is published as a constant rather than retyped here. The width is
         * therefore not decoration on this path -- it is the mechanism, and a sentence lengthened
         * past it would silently lose its specific wording.</p>
         *
         * <p>Assumptions: only the width travels the wire, never the off sentinel. The two baseline
         * message fields disagree on that sentinel -- {@code VALUE SPACES} at L168 of
         * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} against {@code VALUE LOW-VALUES} at
         * L30 of that copybook -- and a JSON member is delimited by the encoding rather than by a
         * sentinel, so neither spelling has a target here.</p>
         *
         * @throws Exception if the request cannot be performed or the body cannot be read
         */
        @Test
        @DisplayName("renders the resolver's own sentence verbatim within the published width")
        void rendersTheResolverSentenceVerbatimWithinThePublishedWidth() throws Exception {
            stubTerminalMiss();

            JsonNode body = bodyOf(unguarded.perform(unpricedRequest())
                    .andExpect(status().isNotFound())
                    .andReturn());

            assertThat(DisclosureGroupService.MESSAGE_RATE_NOT_FOUND.length())
                    .as("the resolver's sentence must stay within the published rendering width, or"
                            + " the shared advice replaces it with the generic sentence and the"
                            + " specific wording never reaches the caller")
                    .isLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH);
            assertThat(body.get("message").asString())
                    .as("the sentence must reach the caller exactly as the resolver publishes it")
                    .isEqualTo(DisclosureGroupService.MESSAGE_RATE_NOT_FOUND);
        }

        /**
         * The first condition reported is the only one reported, and no later error displaces it.
         *
         * <p>Assumptions: the baseline reports the first condition and holds it. Its message field
         * is guarded by an off-condition tested before each move --
         * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} carries that guard at fifteen sites,
         * beginning at its L168 and running through L1563 -- so a later condition never overwrites
         * an earlier one. At this boundary the equivalent property is that the refusal carries one
         * sentence and an empty per-field array: a per-field entry appended after the fact, or a
         * second sentence, would be the observable form of an overwrite.</p>
         *
         * @throws Exception if the request cannot be performed or the body cannot be read
         */
        @Test
        @DisplayName("carries one sentence and no per-field entry, so nothing overwrites it")
        void carriesOneSentenceAndNoPerFieldEntry() throws Exception {
            stubTerminalMiss();

            JsonNode body = bodyOf(unguarded.perform(unpricedRequest())
                    .andExpect(status().isNotFound())
                    .andReturn());

            assertThat(body.get("fieldErrors").isArray())
                    .as("the per-field array must be present so a client can read it without a null"
                            + " check")
                    .isTrue();
            assertThat(body.get("fieldErrors").size())
                    .as("a key that resolves to nothing is not a malformed field, so no per-field"
                            + " entry may be appended to the sentence already reported")
                    .isZero();
            assertThat(body.get("secondaryCode").asString())
                    .as("no secondary code may be reported for this condition, which the shared"
                            + " advice reserves for a refusal that carries one")
                    .isEmpty();
        }
    }

    /**
     * The three key components, which cross the boundary at their declared widths.
     *
     * <p>Purpose: each component of the key is characters and not a number, and each has a declared
     * width the reply has to preserve. The category is the one most easily got wrong: the stored
     * shape is settled by {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL} at L3 of
     * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl}, which is the authority, while
     * {@code app/cpy/CVTRA04Y.cpy} declares the numeric picture {@code PIC 9(04)} at its L7 and
     * {@code app/cpy/CVTRA02Y.cpy} the same at its L8 -- and read as a number the seeded
     * {@code 0001} would address {@code 1} and match no row.</p>
     */
    @Nested
    @DisplayName("on the widths the key components cross the boundary at")
    class OnTheKeyComponentWidths {

        /**
         * The category keeps its leading zeros through the request and the reply.
         *
         * <p>Assumptions: both seeded categories these cases use begin with zeros, and both are
         * asserted, because a value whose leading zeros were dropped somewhere between the path and
         * the reply would still round-trip a category that happened to have none.</p>
         *
         * @throws Exception if either request cannot be performed or its body cannot be read
         */
        @Test
        @DisplayName("keeps the category's leading zeros through request and reply")
        void keepsTheCategoryLeadingZeros() throws Exception {
            stubPricedHit();

            JsonNode body = bodyOf(unguarded.perform(pricedRequest())
                    .andExpect(status().isOk())
                    .andReturn());

            assertThat(body.get(CATEGORY_MEMBER).isString())
                    .as("the category must be published as characters; a numeric member would drop"
                            + " the leading zeros the key depends on")
                    .isTrue();
            assertThat(body.get(CATEGORY_MEMBER).asString())
                    .as("the category must be echoed back exactly as sent, zeros intact")
                    .isEqualTo(CATEGORY_DISCRIMINATING)
                    .hasSize(TransactionCategory.CAT_CD_WIDTH)
                    .startsWith("0");
        }

        /**
         * The type and the account group are published at the widths their columns declare.
         *
         * <p>Assumptions: the widths are read from the entities that publish them rather than
         * written as literals, so a column widened in a migration cannot leave this case asserting
         * the old figure. The type is characters for the same reason as the category: the seeded
         * values begin at {@code 01}, and a numeric reading would lose that zero.</p>
         *
         * @throws Exception if the request cannot be performed or the body cannot be read
         */
        @Test
        @DisplayName("publishes the type and account group at their declared widths")
        void publishesTypeAndGroupAtDeclaredWidths() throws Exception {
            stubPricedHit();

            JsonNode body = bodyOf(unguarded.perform(pricedRequest())
                    .andExpect(status().isOk())
                    .andReturn());

            assertThat(body.get(TYPE_MEMBER).isString())
                    .as("the transaction type must be published as characters")
                    .isTrue();
            assertThat(body.get(TYPE_MEMBER).asString())
                    .as("the transaction type must be echoed back at its declared width, since the"
                            + " substitution replaces the account group alone")
                    .isEqualTo(TYPE_DISCRIMINATING)
                    .hasSize(TransactionType.TYPE_CD_WIDTH);
            assertThat(body.get(REQUESTED_GROUP_MEMBER).asString())
                    .as("the account group must be published at the width its stored key declares")
                    .hasSize(DisclosureGroup.ACCT_GROUP_ID_WIDTH);
            assertThat(body.get(APPLIED_GROUP_MEMBER).asString())
                    .as("the answering account group must be published at that same width")
                    .hasSize(DisclosureGroup.ACCT_GROUP_ID_WIDTH);
        }
    }

    /**
     * The absence of carried state at this boundary.
     *
     * <p>Purpose: the reference programs were pseudo-conversational and carried every scrap of
     * continuity between screen turns in a passed structure, including a discriminator recording
     * whether a turn was a first entry or a re-entry. None of that is ported. Identity arrives in a
     * validated token, the key arrives in the path, and there is no turn to count -- so this group
     * asserts that no request parameter, header or cookie conveys one and that two identical
     * requests are answered identically.</p>
     */
    @Nested
    @DisplayName("on the absence of carried state")
    class OnTheAbsenceOfCarriedState {

        /**
         * The handler binds the three key components and nothing else.
         *
         * <p>Assumptions: the bound names are read off the handler reflectively rather than being
         * inferred from a request that happened to succeed, because a re-entry discriminator added
         * as an optional parameter would change no successful request and would still put carried
         * state into the contract. The three expected names are the controller's own published
         * constants and are never retyped.</p>
         *
         * @throws Exception if the handler cannot be resolved on the controller
         */
        @Test
        @DisplayName("binds the three key components and no parameter, header or cookie besides")
        void bindsTheThreeKeyComponentsAndNothingElse() throws Exception {
            Method handler = rateHandler();

            // WHY : Alternatives Considered: examining only the query-parameter annotation, on the
            //       reasoning that a turn count would arrive as a query parameter. Rejected because
            //       the reference structure carried its continuity in whatever the platform offered,
            //       and its migrated equivalents arrive by three different routes -- a query
            //       parameter, a header and a cookie. Claiming all three, and additionally requiring
            //       every parameter to be a path segment, is what closes the set rather than the one
            //       route a reader happens to expect.
            List<String> bound = new ArrayList<>();
            for (Parameter parameter : handler.getParameters()) {
                PathVariable pathVariable = parameter.getAnnotation(PathVariable.class);
                assertThat(pathVariable)
                        .as("every parameter of the rate handler must be a path segment; a"
                                + " parameter bound from anywhere else is carried state this"
                                + " boundary does not have")
                        .isNotNull();
                assertThat(parameter.getAnnotation(RequestParam.class))
                        .as("no query parameter may be bound, which is where a turn count or a"
                                + " resubmit flag would arrive")
                        .isNull();
                assertThat(parameter.getAnnotation(RequestHeader.class))
                        .as("no request header may be bound into this handler")
                        .isNull();
                assertThat(parameter.getAnnotation(CookieValue.class))
                        .as("no cookie may be bound, which is where a session handle would arrive")
                        .isNull();
                bound.add(pathVariable.name());
            }

            assertThat(bound)
                    .as("the handler must bind exactly the three components of the composite key")
                    .containsExactlyInAnyOrder(
                            DisclosureGroupController.PARAM_ACCT_GROUP_ID,
                            DisclosureGroupController.PARAM_TRAN_TYPE_CD,
                            DisclosureGroupController.PARAM_TRAN_CAT_CD);
        }

        /**
         * Two identical requests are answered identically and neither opens a session.
         *
         * <p>Assumptions: the bodies are compared whole rather than member by member, which the
         * fixed clock makes possible: the only member that would otherwise move between two
         * responses is the timestamp the shared error body stamps, and a fixed clock holds it. A
         * response that varied between two identical requests, or a session opened to remember the
         * first, would be the observable form of carried state.</p>
         *
         * @throws Exception if either request cannot be performed
         */
        @Test
        @DisplayName("answers two identical requests identically and opens no session")
        void answersTwoIdenticalRequestsIdentically() throws Exception {
            stubPricedHit();

            MvcResult first = unguarded.perform(pricedRequest())
                    .andExpect(status().isOk())
                    .andReturn();
            MvcResult second = unguarded.perform(pricedRequest())
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(second.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .as("a stateless route must answer two identical requests identically")
                    .isEqualTo(first.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(first.getRequest().getSession(false))
                    .as("no session may be created to remember a turn, since there is no turn to"
                            + " remember")
                    .isNull();
            assertThat(second.getRequest().getSession(false))
                    .as("the second request must open no session either")
                    .isNull();
        }
    }

    /**
     * The authority the deployed chain demands of this read.
     *
     * <p>Purpose: these are the only cases in this class driven through the deployed filter chain,
     * and they answer one question the rest of the class cannot: whether a read of a rate has been
     * guarded more tightly than the contract publishes. The chain's own installed managers are
     * asserted in the sibling {@code com.carddemo.reference.config} test package and are not
     * restated; what is asserted here is the effect at this route.</p>
     *
     * <p>Assumptions: every authenticated case mints its caller with the security test support
     * rather than presenting a real token. The token is a stand-in carrying only the authorities
     * named on it, the decoder behind the chain is a substitute that is never asked to decode
     * anything, and the issuer the test profile names is deliberately unresolvable -- so no
     * credential, endpoint or signing key appears anywhere in this file.</p>
     */
    @Nested
    @DisplayName("on the authority a read demands")
    class OnTheAuthorityAReadDemands {

        /**
         * A request carrying no token at all is refused before the handler.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("refuses a request carrying no token")
        void refusesARequestCarryingNoToken() throws Exception {
            guarded.perform(pricedRequest())
                    .andExpect(status().isUnauthorized());

            // WHY : Assumptions: the refusal is additionally shown to stop BEFORE the collaborator.
            //       A status on its own cannot distinguish a request refused by the chain from one
            //       that reached the resolver, read a rate and was then refused on the way out --
            //       and the second of those has already disclosed the rate it was refused for.
            verifyNoInteractions(rates);
        }

        /**
         * A token carrying neither published group authority is refused.
         *
         * <p>Assumptions: the authority presented is outside the admitted set rather than an
         * invented third group name, so the case cannot pass by naming something the chain never
         * checks.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("refuses a token carrying neither published group authority")
        void refusesATokenCarryingNeitherGroupAuthority() throws Exception {
            guarded.perform(pricedRequest()
                            .with(jwt().authorities(
                                    new SimpleGrantedAuthority(UNRELATED_AUTHORITY))))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(rates);
        }

        /**
         * A caller holding only the ordinary user group reaches the rate.
         *
         * <p>Assumptions: this is the load-bearing case of the group. A read guarded with the
         * administrator authority would still satisfy every other case in this class, because none
         * of them installs a chain, and it would refuse every ordinary caller in production. The
         * authority presented is read from the shared converter's published constant, and the
         * assertion below additionally pins it as NOT the administrator authority so the case
         * cannot be satisfied by a chain that admitted only administrators.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("admits a caller holding only the ordinary user group")
        void admitsACallerHoldingOnlyTheOrdinaryUserGroup() throws Exception {
            stubPricedHit();

            assertThat(JwtRoleConverter.USER_AUTHORITY)
                    .as("the authority this case presents must not be the administrator one, or the"
                            + " case would pass against a read that admitted only administrators")
                    .isNotEqualTo(JwtRoleConverter.ADMIN_AUTHORITY);
            assertThat(SecurityConfig.BUSINESS_AUTHORITIES)
                    .as("the ordinary user group must be one the deployed chain publishes as"
                            + " admitted, so this case presents a real authority and not a"
                            + " hand-written one")
                    .contains(JwtRoleConverter.USER_AUTHORITY);

            guarded.perform(pricedRequest()
                            .with(jwt().authorities(
                                    new SimpleGrantedAuthority(JwtRoleConverter.USER_AUTHORITY))))
                    .andExpect(status().isOk());
        }

        /**
         * The handler carries no authority annotation of its own.
         *
         * <p>Assumptions: the chain is the single gate on this read, and this case is what makes
         * that checkable. A method-level or class-level authority annotation would be a second gate
         * that no test of the chain could see, and the symptom of adding one to a read is a caller
         * refused for a rule nobody wrote down. Annotations are examined by their own package and
         * name rather than by importing candidate types, so an annotation from any of the several
         * families that express authority is caught rather than only the two most familiar.</p>
         *
         * @throws Exception if the handler cannot be resolved on the controller
         */
        @Test
        @DisplayName("carries no authority annotation of its own on the handler or its class")
        void carriesNoAuthorityAnnotationOfItsOwn() throws Exception {
            List<Annotation> declared = new ArrayList<>();
            declared.addAll(List.of(rateHandler().getAnnotations()));
            declared.addAll(List.of(DisclosureGroupController.class.getAnnotations()));

            for (Annotation annotation : declared) {
                String name = annotation.annotationType().getSimpleName()
                        .toLowerCase(Locale.ROOT);
                assertThat(name)
                        .as("neither the rate handler nor its class may declare an authority"
                                + " annotation; the deployed chain is the single gate on this read,"
                                + " and a second gate here would be invisible to every test of that"
                                + " chain. Found: %s", annotation.annotationType().getName())
                        .doesNotContain("authorize")
                        .doesNotContain("secured")
                        .doesNotContain("rolesallowed")
                        .doesNotContain("permitall")
                        .doesNotContain("denyall");
            }
        }
    }

    /**
     * Stubs the resolver so the priced pair answers from the group asked for.
     *
     * <p>Assumptions: the stub is keyed on the group at its full stored width, because the handler
     * pads a shorter group before calling the resolver and these cases send a group that is already
     * ten characters. Stubbing a narrower key would leave the resolver answering nothing while the
     * request still bound and dispatched correctly.</p>
     */
    private static void stubPricedHit() {
        when(rates.resolveRate(GROUP_PRICED, TYPE_DISCRIMINATING, CATEGORY_DISCRIMINATING))
                .thenReturn(new DisclosureGroupRateResponse(
                        GROUP_PRICED,
                        GROUP_PRICED,
                        TYPE_DISCRIMINATING,
                        CATEGORY_DISCRIMINATING,
                        Money.of(new BigDecimal(RATE_PRICED_TEXT)),
                        DisclosureGroupService.RateSource.REQUESTED_GROUP.isDefaultGroupApplied()));
    }

    /**
     * Stubs the resolver so the priced pair answers from the substituted group instead.
     *
     * <p>Assumptions: the indicator is taken from the resolver's own outcome enumeration rather than
     * written as a literal, so the two states below cannot disagree with the two the resolver
     * publishes. The group named as the answerer is the resolver's padded constant for the same
     * reason.</p>
     */
    private static void stubSubstitutedHit() {
        when(rates.resolveRate(GROUP_ABSENT, TYPE_DISCRIMINATING, CATEGORY_DISCRIMINATING))
                .thenReturn(new DisclosureGroupRateResponse(
                        GROUP_ABSENT,
                        DisclosureGroupService.DEFAULT_ACCT_GROUP_ID,
                        TYPE_DISCRIMINATING,
                        CATEGORY_DISCRIMINATING,
                        Money.of(new BigDecimal(RATE_SUBSTITUTED_TEXT)),
                        DisclosureGroupService.RateSource.DEFAULT_GROUP.isDefaultGroupApplied()));
    }

    /**
     * Stubs the resolver so the unpriced pair raises the condition neither read resolves.
     *
     * <p>Assumptions: the four operator-facing values handed to the raised condition are the
     * reference program's own -- the abend code its L631 moves, the program that abends, the message
     * its L455 displays and the message its L629 displays. Whether the resolver reproduces those
     * four is asserted in the service package; they are supplied here only so the condition raised
     * is the shape the shared advice receives in production, and none of them reaches the wire.</p>
     */
    private static void stubTerminalMiss() {
        when(rates.resolveRate(GROUP_PRICED, TYPE_UNPRICED, CATEGORY_UNPRICED))
                .thenThrow(new DisclosureGroupService.DisclosureGroupNotFoundException(
                        GROUP_PRICED,
                        TYPE_UNPRICED,
                        CATEGORY_UNPRICED,
                        new AbendDetail("999", "CBACT04C",
                                "ERROR READING DEFAULT DISCLOSURE GROUP", "ABENDING PROGRAM")));
    }

    /**
     * Builds a request for the priced pair under the group that carries its own row.
     *
     * @return a request builder addressing the rate route with the priced key, never {@code null}
     */
    private static MockHttpServletRequestBuilder pricedRequest() {
        return rateRequest(GROUP_PRICED, TYPE_DISCRIMINATING, CATEGORY_DISCRIMINATING);
    }

    /**
     * Builds a request for the priced pair under a group no seeded row carries.
     *
     * @return a request builder addressing the rate route with the absent group, never {@code null}
     */
    private static MockHttpServletRequestBuilder absentGroupRequest() {
        return rateRequest(GROUP_ABSENT, TYPE_DISCRIMINATING, CATEGORY_DISCRIMINATING);
    }

    /**
     * Builds a request for the pair the seed leaves unpriced in every group.
     *
     * @return a request builder addressing the rate route with the unpriced key, never {@code null}
     */
    private static MockHttpServletRequestBuilder unpricedRequest() {
        return rateRequest(GROUP_PRICED, TYPE_UNPRICED, CATEGORY_UNPRICED);
    }

    /**
     * Builds a request addressing the rate route with one three-part key.
     *
     * <p>Assumptions: the address is assembled from the controller's own published path constants
     * with the three segments supplied as template values, so a route moved on the controller moves
     * these cases with it rather than leaving them addressing a path nothing is mounted at. The
     * accepted representation is stated on every request because the reply is negotiated, and a
     * request that accepted anything would let a converter other than the one under assertion
     * answer.</p>
     *
     * @param acctGroupId the account group segment, as a {@code String} the contract admits from one
     *     to ten characters
     * @param tranTypeCd the transaction type segment, as a two-character {@code String}
     * @param tranCatCd the transaction category segment, as a four-digit {@code String} whose
     *     leading zeros are part of the value
     * @return a request builder addressing the rate route with those three segments, never
     *     {@code null}
     */
    private static MockHttpServletRequestBuilder rateRequest(String acctGroupId, String tranTypeCd,
            String tranCatCd) {

        return get(DisclosureGroupController.BASE_PATH + "/{acctGroupId}/{tranTypeCd}/{tranCatCd}",
                acctGroupId, tranTypeCd, tranCatCd)
                .accept(MediaType.APPLICATION_JSON);
    }

    /**
     * Reads a completed exchange's body back into a parsed tree.
     *
     * <p>Assumptions: the body is read with an explicit character set rather than the container's
     * default, because a default that differed between environments would change the parsed value
     * of any member carrying a character outside the ASCII range and would do so silently.</p>
     *
     * @param result the completed exchange, as an {@code MvcResult} whose response has been written
     * @return the parsed body as a {@code JsonNode}, never {@code null}
     * @throws java.io.UnsupportedEncodingException if the response cannot be read at that character
     *     set, which is the checked failure the servlet response declares
     * @throws tools.jackson.core.JacksonException if the body is not well-formed JSON, which is the
     *     unchecked failure the parser raises
     */
    private static JsonNode bodyOf(MvcResult result)
            throws java.io.UnsupportedEncodingException {

        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * Reads the rate member out of a completed exchange's body.
     *
     * <p>Assumptions: the member is asserted present before it is returned, so a member renamed on
     * the published reply fails with a sentence naming the member rather than with a null reference
     * inside whichever case happened to run first.</p>
     *
     * @param result the completed exchange, as an {@code MvcResult} whose response has been written
     * @return the rate member as a {@code JsonNode}, never {@code null}
     * @throws java.io.UnsupportedEncodingException if the response cannot be read at UTF-8, which is
     *     the checked failure the servlet response declares
     * @throws tools.jackson.core.JacksonException if the body is not well-formed JSON, which is the
     *     unchecked failure the parser raises
     */
    private static JsonNode rateOf(MvcResult result)
            throws java.io.UnsupportedEncodingException {

        JsonNode rate = bodyOf(result).get(RATE_MEMBER);
        assertThat(rate)
                .as("member '%s' is absent from the reply, so the wire form of the rate could not be"
                        + " inspected; confirm the member name the published contract declares",
                        RATE_MEMBER)
                .isNotNull();
        return rate;
    }

    /**
     * Names the record components one type declares, in declaration order.
     *
     * @param type the record type to read, as a {@code Class} whose components are being named
     * @return the component names as a {@code List} in declaration order, never {@code null}
     */
    private static List<String> declaredMembersOf(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /**
     * Resolves the one handler method the rate route is mounted on.
     *
     * <p>Assumptions: the method is resolved by its declared signature rather than searched for by
     * name, so a second overload added beside it would fail to resolve here instead of being picked
     * up arbitrarily.</p>
     *
     * @return the rate handler as a {@code Method}, never {@code null}
     * @throws NoSuchMethodException if the controller no longer declares that handler, which is the
     *     checked failure reflective lookup raises
     * @throws SecurityException if reflective access to the declared method is refused, which is the
     *     unchecked failure that lookup raises
     */
    private static Method rateHandler() throws NoSuchMethodException {
        return DisclosureGroupController.class.getDeclaredMethod(
                "getDisclosureGroupRate", String.class, String.class, String.class);
    }

    /**
     * Assembles the context whose dispatcher carries the deployed filter chain.
     *
     * <p>Purpose: this wiring exists so the authority cases observe the chain this service actually
     * deploys rather than a chain written for the test. The security configuration under assertion
     * is instantiated directly and its two published bean methods are called, so a rule changed
     * there changes these cases with it.</p>
     *
     * <p>Assumptions: the token decoder is a substitute and is never asked to decode anything,
     * because the security test support mints an authentication directly. The two group names handed
     * to the authority converter are the shared converter's own published constants rather than
     * configuration values, which is what lets this context refresh without the property source the
     * running service reads them from.</p>
     *
     * <p>Assumptions: this class implements the web configurer so it can install a message converter
     * carrying the money module. Without it the rate would be rendered by a converter assembled from
     * defaults, and the admitted authority case would fail for a rendering reason belonging to the
     * framework rather than for anything to do with authority.</p>
     *
     * <p>On parameters, return values and exceptions at the type level: a configuration class is
     * instantiated by the context through its implicit no-argument constructor and returns nothing,
     * so this descriptor carries no parameter or return at-clause; each bean method below carries
     * its own.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class GuardedChainWiring implements WebMvcConfigurer {

        /**
         * Installs a JSON converter carrying the exact-money module.
         *
         * <p>Assumptions: the converter is installed as the JSON converter rather than appended as a
         * custom one, so it replaces the default instead of competing with it for the same
         * representation.</p>
         *
         * @param builder the converter registry this context is assembling, as a
         *     {@code HttpMessageConverters.ServerBuilder}
         */
        @Override
        public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
            builder.withJsonConverter(new JacksonJsonHttpMessageConverter(JSON));
        }

        /**
         * Supplies the fixed clock the shared error body stamps its timestamp from.
         *
         * @return a {@code Clock} fixed at this class's instant, never {@code null}
         */
        @Bean
        Clock clock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }

        /**
         * Supplies the controller under assertion over the substituted resolver.
         *
         * @return the {@code DisclosureGroupController} the chain guards, never {@code null}
         */
        @Bean
        DisclosureGroupController disclosureGroupController() {
            return new DisclosureGroupController(rates);
        }

        /**
         * Supplies the shared advice, which this context cannot discover for itself.
         *
         * <p>Assumptions: the advice sits outside this module's component-scan root and reaches the
         * running service through the shared kernel's auto-configuration, which no test context
         * applies. Naming it here is therefore the only way a refusal inside this context renders
         * through the real mapping.</p>
         *
         * @param clock the clock the advice stamps refusals from, as a {@code Clock}
         * @return the shared {@code GlobalExceptionHandler}, never {@code null}
         */
        @Bean
        GlobalExceptionHandler globalExceptionHandler(Clock clock) {
            return new GlobalExceptionHandler(clock);
        }

        /**
         * Supplies a substituted token decoder the chain never exercises.
         *
         * @return a substituted {@code JwtDecoder}, never {@code null}
         */
        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }

        /**
         * Supplies the authority converter the deployed configuration publishes.
         *
         * @return the configured {@code JwtAuthenticationConverter}, never {@code null}
         */
        @Bean
        JwtAuthenticationConverter jwtAuthenticationConverter() {
            return new SecurityConfig().jwtAuthenticationConverter(
                    JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);
        }

        /**
         * Supplies the filter chain the deployed configuration builds.
         *
         * @param http the chain builder this context supplies, as a {@code HttpSecurity}
         * @param converter the authority converter the chain reads groups through, as a
         *     {@code JwtAuthenticationConverter}
         * @param clock the clock the chain's own refusal rendering stamps from, as a {@code Clock}
         * @return the deployed {@code SecurityFilterChain}, never {@code null}
         * @throws Exception if the chain cannot be assembled, which is the checked failure the
         *     builder declares
         */
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter,
                Clock clock) throws Exception {

            return new SecurityConfig().filterChain(http, converter, clock);
        }
    }
}
