package com.carddemo.reference.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reference.dto.DisclosureGroupRateResponse;
import com.carddemo.reference.dto.LookupPageRequest;
import com.carddemo.reference.dto.PageDirection;
import com.carddemo.reference.dto.TransactionCategoryListRequest;
import com.carddemo.reference.dto.TransactionCategoryResponse;
import com.carddemo.reference.dto.TransactionTypeListRequest;
import com.carddemo.reference.dto.TransactionTypeResponse;
import com.carddemo.reference.mapper.UsPhoneAreaCodeMapper;
import com.carddemo.reference.mapper.UsStateMapper;
import com.carddemo.reference.mapper.UsStateZipPrefixMapper;
import com.carddemo.reference.repository.UsPhoneAreaCodeRepository;
import com.carddemo.reference.repository.UsStateRepository;
import com.carddemo.reference.repository.UsStateZipPrefixRepository;
import com.carddemo.reference.service.AddressLookupService;
import com.carddemo.reference.service.DisclosureGroupService;
import com.carddemo.reference.service.ReferencePaging;
import com.carddemo.reference.service.TransactionCategoryService;
import com.carddemo.reference.service.TransactionTypeService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives every constrained reference query parameter and path segment through a real dispatcher.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Purpose: five list routes and the disclosure-group read declare bean-validation constraints on their
 * handler parameters, and a declared constraint is only worth what the dispatcher does with it. A direct
 * handler call passes whatever value it likes straight into the body, so it cannot distinguish a constraint
 * that is enforced from one that is merely written down. Each case below sends a value that must be refused
 * and asserts both the published status and the parameter the problem document names.</p>
 *
 * <p>Refactoring Rationale: the constraints these cases exercise were declared in response to a review
 * finding that the request records carried them while the handlers did not -- the handlers bound raw text
 * and constructed the record afterwards, at which point nothing validated it. A malformed cursor therefore
 * reached the sealer and a malformed path segment reached a domain identity type, whose refusal is a bare
 * {@link IllegalArgumentException} that the shared advice does not classify, so a caller's own bad input was
 * answered as a 500. Every refusal asserted here previously produced either that 500 or a 200 over a
 * silently ignored filter.</p>
 *
 * <p>Assumptions: each refusal case is paired with an accepted case on the same parameter. A constraint
 * expression can refuse everything, and a test that only ever sends invalid values would pass against one
 * that does -- the accepted case is what shows the boundary is where the contract puts it and not lower.</p>
 *
 * <p>Assumptions: the seal is REAL and the services beneath the two write-owning controllers are mocked.
 * The distinction matters and is not uniform by accident: a cursor refusal has to be produced by the same
 * code a deployment runs, so the sealer is real; whereas what a service does after a parameter is admitted
 * is asserted in {@code com.carddemo.reference.service} and restating it here would give a later change two
 * places to update and one to forget.</p>
 *
 * <p>Assumptions: the dispatcher is assembled with {@code standaloneSetup} and registers no security chain,
 * so a failure localises to argument resolution, method validation or the advice. Which authority each
 * route demands is asserted in {@code com.carddemo.reference.config} against the chain's own installed
 * authorization managers.</p>
 *
 * <p>Assumptions: the shared advice is registered, because the outcome asserted in every refusal case IS
 * the advice's rendering of a method-validation failure. Without it the same refusals would surface as a
 * servlet-container default that says nothing about the published contract.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; each method below carries its own.</p>
 */
class ReferenceParameterConstraintTest {

    /** Key material for the real seal; test-only and deliberately not a credential. */
    /**
     * The authenticated caller every request in this class is issued as.
     *
     * <p>Assumptions: a fabricated eight-character identifier in the shape the reference user record
     * declares. It identifies nobody; what it buys is that the cursor bindings the browses compose are
     * the same shape a deployment composes.</p>
     */
    private static final java.security.Principal CALLER = () -> "REFUSR01";

    private static final byte[] CURSOR_KEY =
            "carddemo-reference-parameter-constraint-cursor-material-not-a-secret"
                    .getBytes(StandardCharsets.UTF_8);

    /** How long a sealed cursor stays redeemable; generous, because no case here asserts expiry. */
    private static final Duration CURSOR_LIFETIME = Duration.ofHours(1);

    /** The instant the advice stamps on every problem document, fixed so a body is comparable. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-02-01T00:00:00Z");

    /** The query parameter carrying an opaque paging position, on all five list routes. */
    private static final String PARAM_CURSOR = "cursor";

    // WHY : Assumptions: the parameter name is read from the enumeration that owns it rather than written
    //       as a literal here, because the same constant is what a refusal names as the field at fault --
    //       so a case sending one spelling and asserting another could not drift apart silently.
    /** The query parameter carrying the paging direction, on all five list routes. */
    private static final String PARAM_DIRECTION = PageDirection.PARAMETER_NAME;

    /** The query parameter carrying the area-code classification filter. */
    private static final String PARAM_CODE_CLASS = "codeClass";

    /** The query parameter carrying the exact type-code filter on the two browse routes. */
    private static final String PARAM_TYPE_CODE = "typeCode";

    /** The query parameter carrying the description filter on the two browse routes. */
    private static final String PARAM_DESCRIPTION = "description";

    // WHY : Assumptions: this is a value the SHAPE expression refuses and not merely one the seal cannot
    //       open, which is the distinction the constraint exists to draw. A raw key -- the form a caller
    //       reaches for when it mistakes the opaque position for a record identity -- carries none of the
    //       three dot-separated segments, so it is refused at the boundary rather than being decoded.
    /** A position that is not of the sealed shape at all, so the parameter constraint refuses it. */
    private static final String MALFORMED_CURSOR = "01";

    // WHY : Assumptions: this value satisfies the SHAPE expression and nothing more. It is sent only on
    //       the two routes whose service is mocked, so nothing ever opens it; its purpose is to get past
    //       the parameter constraint so that the direction beside it is the only thing under test.
    /** A position of the sealed shape, for the two routes whose mocked service never opens one. */
    private static final String SHAPED_CURSOR =
            CursorToken.VERSION + ".YWJjZGVmZ2hpamts.0123456789012345678901234567890123456789012";

    /** The dispatcher under test, carrying all four reference controllers. */
    private MockMvc mockMvc;

    /** The type browse the two type-filter cases drive, mocked because its rules are asserted elsewhere. */
    private TransactionTypeService types;

    /** The category browse, mocked for the same reason as the type browse above. */
    private TransactionCategoryService categories;

    /** The rate resolver, mocked so the accepted disclosure-group case has a rate to render. */
    private DisclosureGroupService rates;

    // WHY : Assumptions: the sealer is held as a FIELD rather than as a local of the builder because the
    //       direction cases below mint a real position with it and then send that position back. Minting
    //       with the same instance the dispatcher opens with is what makes those cases assert the whole
    //       path -- the lower-case direction converting, the position opening under the scope that
    //       direction implies, and a query being reached -- rather than only the conversion.
    /** The real seal the dispatcher opens positions with, and the direction cases mint positions with. */
    private CursorToken sealer;

    /**
     * Builds one dispatcher over all four reference controllers with the shared advice registered.
     *
     * <p>Assumptions: the money module is installed on the converter because the disclosure-group reply
     * carries a rate as {@link Money}, and a converter assembled without it would either fail to render
     * that reply or render it in a shape the running service does not publish.</p>
     */
    @BeforeEach
    void buildDispatcher() {
        UsPhoneAreaCodeRepository areaCodes = mock(UsPhoneAreaCodeRepository.class);
        UsStateRepository states = mock(UsStateRepository.class);
        UsStateZipPrefixRepository zipPrefixes = mock(UsStateZipPrefixRepository.class);
        this.types = mock(TransactionTypeService.class);
        this.categories = mock(TransactionCategoryService.class);
        this.rates = mock(DisclosureGroupService.class);

        when(areaCodes.findAllByOrderByAreaCodeAsc(any(Limit.class))).thenReturn(List.of());
        when(areaCodes.findByCodeClassOrderByAreaCodeAsc(anyString(), any(Limit.class)))
                .thenReturn(List.of());
        when(states.findAllByOrderByStateCodeAsc(any(Limit.class))).thenReturn(List.of());
        when(zipPrefixes.findAllByOrderByStateZipCdAsc(any(Limit.class))).thenReturn(List.of());
        // Assumptions: the caller's name is matched loosely, because the paging call now seals its
        //   position against the authenticated subject and this class asserts parameter CONSTRAINTS
        //   rather than the binding itself, which has its own cases.
        when(this.types.list(any(TransactionTypeListRequest.class), any(CursorToken.class),
                anyString())).thenReturn(PageResponse.empty());
        when(this.categories.list(any(TransactionCategoryListRequest.class), any(CursorToken.class),
                anyString())).thenReturn(PageResponse.empty());

        this.sealer = new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);

        JacksonJsonHttpMessageConverter converter = new JacksonJsonHttpMessageConverter(
                JsonMapper.builder().addModule(new MoneyModule()).build());

        this.mockMvc = MockMvcBuilders
                .standaloneSetup(
                        // WHY : ⚠️ Refactoring Rationale: the address-lookup controller is now assembled
                        //       over its SERVICE rather than over three repositories and the sealer. The
                        //       three repositories and the sealer are still constructed here because the
                        //       service holds them, and this class asserts what the DISPATCHER refuses
                        //       before a handler runs -- so the stack below the handler still has to be
                        //       real enough that a request which passes every constraint reaches a read
                        //       rather than a null.
                        new AddressLookupController(new AddressLookupService(
                                areaCodes, states, zipPrefixes, this.sealer,
                                new UsPhoneAreaCodeMapper(), new UsStateMapper(),
                                new UsStateZipPrefixMapper())),
                        new TransactionTypeController(this.types, this.sealer),
                        new TransactionCategoryController(this.categories, this.sealer),
                        new DisclosureGroupController(this.rates))
                // WHY : Assumptions: an authenticated caller is supplied on EVERY request by default rather
                //       than case by case. Each browse seals its paging position against the caller's name,
                //       so a request carrying no principal reaches the handler and fails on a null one --
                //       which would report a parameter-constraint case as a 500 that says nothing about the
                //       constraint. Setting it once also means a case added later cannot forget it.
                .defaultRequest(get("/").principal(CALLER))
                .setMessageConverters(converter)
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    /**
     * The three address-lookup browses, whose only caller-supplied narrowing is a classification.
     */
    @Nested
    @DisplayName("on the address lookups")
    class OnTheAddressLookups {

        /**
         * A position that is not of the sealed shape is refused on the area-code browse.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a malformed cursor is refused 400 naming the cursor")
        void aMalformedCursorIsRefusedOnAreaCodes() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(AddressLookupController.AREA_CODE_PATH)
                            .param(PARAM_CURSOR, MALFORMED_CURSOR))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(PARAM_CURSOR));
        }

        /**
         * A position longer than a sealed token can be is refused rather than opened.
         *
         * <p>Assumptions: this case is deliberately NOT claimed to isolate the size bound from the shape
         * bound, and the reason is arithmetic rather than preference. A shape-conforming token is the
         * version marker, a middle segment of at most two hundred characters and a fixed forty-three
         * character tail, so the longest value the expression admits is shorter than the maximum the size
         * constraint allows -- no value can violate the size bound while satisfying the shape. Both
         * constraints therefore refuse this value, and the size bound's purpose is to put a ceiling on
         * the input the expression is evaluated over rather than to be an independent boundary. What the
         * case asserts is the outcome that matters to a caller: an oversized position is refused at the
         * dispatcher with the parameter named, and never reaches the decoder.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a cursor longer than the sealed maximum is refused 400")
        void anOverlongCursorIsRefused() throws Exception {
            String overlong = CursorToken.VERSION + "."
                    + "a".repeat(CursorToken.MAX_TOKEN_LENGTH) + ".a";

            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(AddressLookupController.AREA_CODE_PATH)
                            .param(PARAM_CURSOR, overlong))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(PARAM_CURSOR));
        }

        /**
         * A classification outside the closed domain is refused rather than silently matching nothing.
         *
         * <p>Refactoring Rationale: this is the outcome the constraint changed. Unconstrained, the value
         * reached the query as a filter no seeded row carries, and the caller was answered 200 with an
         * empty page -- indistinguishable from a classification that exists but is unpopulated.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a classification outside the closed domain is refused 400")
        void anUnknownClassificationIsRefused() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(AddressLookupController.AREA_CODE_PATH)
                            .param(PARAM_CODE_CLASS, "X"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(PARAM_CODE_CLASS));
        }

        /**
         * Each admitted classification is accepted, so the constraint is the published domain.
         *
         * <p>Assumptions: both members of the domain are sent rather than one, because an expression
         * admitting only the first would satisfy a single-value check while refusing a request the
         * document accepts.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("each classification the contract admits is accepted 200")
        void eachAdmittedClassificationIsAccepted() throws Exception {
            for (char admitted : new char[] {'G', 'E'}) {
                ReferenceParameterConstraintTest.this.mockMvc
                        .perform(get(AddressLookupController.AREA_CODE_PATH)
                                .param(PARAM_CODE_CLASS, String.valueOf(admitted)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items").isArray());
            }
        }

        /**
         * A malformed position is refused on the state browse as well as on the area-code browse.
         *
         * <p>Assumptions: the three browses are asserted individually rather than one standing for all
         * three. They declare their constraints separately, so an omission on one is exactly the defect
         * this class exists to detect and a single representative case would not see it.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a malformed cursor is refused 400 on the state browse")
        void aMalformedCursorIsRefusedOnStates() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(AddressLookupController.STATE_PATH)
                            .param(PARAM_CURSOR, MALFORMED_CURSOR))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(PARAM_CURSOR));
        }

        /**
         * A malformed position is refused on the state-and-prefix browse.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a malformed cursor is refused 400 on the prefix browse")
        void aMalformedCursorIsRefusedOnZipPrefixes() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(AddressLookupController.ZIP_PREFIX_PATH)
                            .param(PARAM_CURSOR, MALFORMED_CURSOR))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(PARAM_CURSOR));
        }

        /**
         * A request carrying no parameter at all is accepted, which is the browse's opening state.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a request with no parameters answers the opening page")
        void anUnnarrowedRequestIsAccepted() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(AddressLookupController.STATE_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isArray());
        }
    }

    /**
     * The two reference browses whose filters are a type code and a description.
     */
    @Nested
    @DisplayName("on the type and category browses")
    class OnTheFilteredBrowses {

        /**
         * A type filter narrower than its declared width is refused rather than padded.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a type filter of one character is refused 400 naming the filter")
        void aTooShortTypeFilterIsRefused() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH)
                            .param(PARAM_TYPE_CODE, "1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(PARAM_TYPE_CODE));
        }

        /**
         * A non-numeric type filter is refused, so the closed domain is the expression's and not text.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a non-numeric type filter is refused 400")
        void aNonNumericTypeFilterIsRefused() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH)
                            .param(PARAM_TYPE_CODE, "ab"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(PARAM_TYPE_CODE));
        }

        /**
         * A description filter one character beyond the stored width is refused.
         *
         * <p>Assumptions: the value sent is exactly one character longer than the column the filter is
         * compared against admits, so the case states the boundary rather than merely that a very long
         * value is refused somewhere.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a description filter one character too long is refused 400")
        void anOverlongDescriptionFilterIsRefused() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH)
                            .param(PARAM_DESCRIPTION,
                                    "d".repeat(TransactionTypeListRequest.DESCRIPTION_MAX_LENGTH + 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(PARAM_DESCRIPTION));
        }

        /**
         * An empty description filter is refused rather than treated as no filter at all.
         *
         * <p>Assumptions: the minimum is one character and this is the value immediately below it. A
         * present-but-empty parameter is the shape a form submits for a field the operator cleared, and
         * refusing it is what keeps "cleared" from silently meaning "unfiltered" -- the contract's way of
         * expressing no filter is to omit the parameter, which the accepted case below sends.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an empty description filter is refused 400")
        void anEmptyDescriptionFilterIsRefused() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH)
                            .param(PARAM_DESCRIPTION, ""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(PARAM_DESCRIPTION));
        }

        /**
         * Both filters at their admitted bounds are accepted and reach the browse.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("filters at their admitted bounds are accepted 200")
        void admittedFiltersAreAccepted() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH)
                            .param(PARAM_TYPE_CODE, "01")
                            .param(PARAM_DESCRIPTION,
                                    "d".repeat(TransactionTypeListRequest.DESCRIPTION_MAX_LENGTH)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isArray());
        }

        /**
         * A malformed position is refused on the type browse.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a malformed cursor is refused 400 on the type browse")
        void aMalformedCursorIsRefusedOnTypes() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH)
                            .param(PARAM_CURSOR, MALFORMED_CURSOR))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(PARAM_CURSOR));
        }

        /**
         * The category browse declares the same filter constraints and enforces them too.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a malformed type filter is refused 400 on the category browse")
        void aMalformedTypeFilterIsRefusedOnCategories() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(TransactionCategoryController.BASE_PATH)
                            .param(PARAM_TYPE_CODE, "999"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(PARAM_TYPE_CODE));
        }

        /**
         * A malformed position is refused on the category browse.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a malformed cursor is refused 400 on the category browse")
        void aMalformedCursorIsRefusedOnCategories() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(TransactionCategoryController.BASE_PATH)
                            .param(PARAM_CURSOR, MALFORMED_CURSOR))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(PARAM_CURSOR));
        }

        /**
         * A category browse carrying an admitted type filter is accepted.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an admitted type filter is accepted 200 on the category browse")
        void anAdmittedTypeFilterIsAcceptedOnCategories() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(TransactionCategoryController.BASE_PATH)
                            .param(PARAM_TYPE_CODE,
                                    "0".repeat(TransactionCategoryListRequest.TYPE_CODE_LENGTH - 1)
                                            + "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isArray());
        }
    }

    /**
     * The disclosure-group read, whose three path segments were previously unconstrained text.
     */
    @Nested
    @DisplayName("on the disclosure-group read")
    class OnTheDisclosureGroupRead {

        /** A well-formed account group, at the width the accepted case exercises. */
        private static final String GROUP = "DEFAULT";

        /** A well-formed transaction type. */
        private static final String TYPE = "01";

        /** A well-formed transaction category. */
        private static final String CATEGORY = "0005";

        /** The width the handler pads an account group out to before the stored key is built. */
        private static final int STORED_GROUP_WIDTH = 10;

        /**
         * An account group beyond the declared width is refused with the segment named.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an over-wide account group is refused 400 naming the segment")
        void anOverWideGroupIsRefused() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(DisclosureGroupController.BASE_PATH + "/{a}/{t}/{c}",
                            "GROUPTOOLONG", TYPE, CATEGORY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                    .andExpect(jsonPath("$.fieldErrors[0].field")
                            .value(DisclosureGroupController.PARAM_ACCT_GROUP_ID));
        }

        /**
         * The type value the published domain excludes is refused rather than looked up.
         *
         * <p>Assumptions: {@code 00} is chosen deliberately over a non-numeric value, because it is the
         * one input a two-digit reading would admit and the published expression does not. A case sending
         * letters would pass against a constraint that had been relaxed to two digits.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the excluded type value is refused 400 naming the segment")
        void theExcludedTypeValueIsRefused() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(DisclosureGroupController.BASE_PATH + "/{a}/{t}/{c}",
                            GROUP, "00", CATEGORY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field")
                            .value(DisclosureGroupController.PARAM_TRAN_TYPE_CD));
        }

        /**
         * A type segment of the wrong width is refused.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a type segment of one character is refused 400")
        void aTooShortTypeSegmentIsRefused() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(DisclosureGroupController.BASE_PATH + "/{a}/{t}/{c}",
                            GROUP, "1", CATEGORY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field")
                            .value(DisclosureGroupController.PARAM_TRAN_TYPE_CD));
        }

        /**
         * A non-numeric category segment is refused with that segment named.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a non-numeric category is refused 400 naming the segment")
        void aNonNumericCategoryIsRefused() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(DisclosureGroupController.BASE_PATH + "/{a}/{t}/{c}",
                            GROUP, TYPE, "abcd"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field")
                            .value(DisclosureGroupController.PARAM_TRAN_CAT_CD));
        }

        /**
         * A category segment of the wrong width is refused rather than left-padded.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a three-digit category is refused 400")
        void aTooShortCategoryIsRefused() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(DisclosureGroupController.BASE_PATH + "/{a}/{t}/{c}",
                            GROUP, TYPE, "005"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field")
                            .value(DisclosureGroupController.PARAM_TRAN_CAT_CD));
        }

        /**
         * A key well formed in all three segments is admitted and answered with the resolved rate.
         *
         * <p>Assumptions: the group reaches the resolver PADDED to the stored width, which is asserted
         * here rather than only in the accepted status. The padding is the handler's, so a constraint
         * tightened to the stored width would have refused this request and a constraint that dropped
         * the padding would have queried a key no row carries -- both would leave a status-only
         * assertion green.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a well-formed key is accepted 200 and reaches the resolver padded")
        void aWellFormedKeyIsAcceptedAndPadded() throws Exception {
            String padded = GROUP + " ".repeat(STORED_GROUP_WIDTH - GROUP.length());
            when(ReferenceParameterConstraintTest.this.rates.resolveRate(padded, TYPE, CATEGORY))
                    .thenReturn(new DisclosureGroupRateResponse(padded, padded, TYPE, CATEGORY,
                            Money.of(new BigDecimal("12.75")), false));

            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(DisclosureGroupController.BASE_PATH + "/{a}/{t}/{c}",
                            GROUP, TYPE, CATEGORY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.interestRate").value("12.75"))
                    .andExpect(jsonPath("$.defaultGroupApplied").value(false));
        }

        /**
         * The shortest account group the contract admits is accepted.
         *
         * <p>Assumptions: this is the lower bound of the width, and it is sent because the segment's
         * minimum was the one bound a transcription could plausibly have written as the stored width
         * instead. A single case at the upper bound would not distinguish the two.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a single-character account group is accepted 200")
        void theShortestGroupIsAccepted() throws Exception {
            when(ReferenceParameterConstraintTest.this.rates
                    .resolveRate(anyString(), anyString(), anyString()))
                    .thenReturn(new DisclosureGroupRateResponse(
                            "A" + " ".repeat(STORED_GROUP_WIDTH - 1),
                            "A" + " ".repeat(STORED_GROUP_WIDTH - 1),
                            TYPE, CATEGORY, Money.of(new BigDecimal("1.00")), true));

            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(DisclosureGroupController.BASE_PATH + "/{a}/{t}/{c}",
                            "A", TYPE, CATEGORY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.defaultGroupApplied").value(true));
        }
    }

    /**
     * The item routes, whose segments were bound as unconstrained text and read for.
     *
     * <p>Purpose: these six routes failed in two different ways and both are asserted. The category routes
     * build a composite identity type from their two segments, whose bare refusal the shared advice does
     * not classify, so a malformed segment rendered 500. The type and lookup routes read straight for the
     * value, so a malformed segment matched nothing and rendered 404 with a "NOT found" sentence -- a
     * misdirection rather than a fault, and the harder of the two to notice.</p>
     */
    @Nested
    @DisplayName("on the item routes")
    class OnTheItemRoutes {

        /**
         * The type value the published domain excludes renders 400 and not the absent-row 404.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an excluded type code is refused 400 rather than reported absent")
        void anExcludedTypeCodeIsRefusedRatherThanReportedAbsent() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH + "/{typeCd}", "00"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                    .andExpect(jsonPath("$.fieldErrors[0].field")
                            .value(TransactionTypeController.PARAM_TYPE_CD));
        }

        /**
         * A well-formed type code is admitted and answered from the browse.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a well-formed type code is admitted 200")
        void aWellFormedTypeCodeIsAdmitted() throws Exception {
            when(ReferenceParameterConstraintTest.this.types.read("01"))
                    .thenReturn(new TransactionTypeResponse("01", "Purchase", 0L));

            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH + "/{typeCd}", "01"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.typeCd").value("01"));
        }

        /**
         * A malformed type half of a category key renders 400 where it used to render 500.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a malformed category type half is refused 400 and not 500")
        void aMalformedCategoryTypeHalfIsRefused() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(TransactionCategoryController.BASE_PATH + "/{typeCd}/{catCd}",
                            "1", "0005"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                    .andExpect(jsonPath("$.fieldErrors[0].field")
                            .value(TransactionCategoryController.PARAM_TYPE_CD));
        }

        /**
         * A malformed category half of a category key renders 400 naming that half.
         *
         * <p>Assumptions: the two halves are asserted separately because the problem document names the
         * offending segment, and a constraint applied to only one of the two would still produce a 400 on
         * a request whose other half was malformed if the wrong half were reported.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a malformed category half is refused 400 naming that half")
        void aMalformedCategoryHalfIsRefused() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(TransactionCategoryController.BASE_PATH + "/{typeCd}/{catCd}",
                            "01", "abcd"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field")
                            .value(TransactionCategoryController.PARAM_CAT_CD));
        }

        /**
         * The delete verb on the same template is constrained too, not only the read.
         *
         * <p>Assumptions: a write verb is exercised as well as a read, because all three of the category
         * item verbs reach the same identity type and constraints are declared per handler -- an omission
         * on the delete would leave a malformed path answering 500 on exactly the verb whose failure is
         * least visible in a browser.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a malformed key is refused 400 on the delete as well as the read")
        void aMalformedKeyIsRefusedOnTheDelete() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(delete(TransactionCategoryController.BASE_PATH + "/{typeCd}/{catCd}",
                            "1", "0005"))
                    .andExpect(status().isBadRequest());
        }

        /**
         * A well-formed category key is admitted and answered.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a well-formed category key is admitted 200")
        void aWellFormedCategoryKeyIsAdmitted() throws Exception {
            when(ReferenceParameterConstraintTest.this.categories.read("01", "0005"))
                    .thenReturn(new TransactionCategoryResponse("01", "0005", "Retail", 0L));

            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(TransactionCategoryController.BASE_PATH + "/{typeCd}/{catCd}",
                            "01", "0005"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.catCd").value("0005"));
        }

        /**
         * An area code of the wrong width renders 400 rather than the absent-code 404.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an area code of the wrong width is refused 400")
        void aMalformedAreaCodeIsRefused() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(AddressLookupController.AREA_CODE_PATH + "/{areaCd}", "12"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("areaCd"));
        }

        /**
         * A lower-case state code renders 400, because the published domain is upper case.
         *
         * <p>Assumptions: case is the interesting boundary on this segment rather than width. The seeded
         * codes are stored upper case and the schema declares that, so a lower-case value is a request the
         * document does not admit -- and it is exactly the value a caller is most likely to send, which is
         * why refusing it explicitly beats reading for it and reporting the code unseeded.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a lower-case state code is refused 400")
        void aLowerCaseStateCodeIsRefused() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(AddressLookupController.STATE_PATH + "/{stateCd}", "ny"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("stateCd"));
        }

        /**
         * A state-and-prefix pair of the wrong shape renders 400.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a prefix pair of the wrong shape is refused 400")
        void aMalformedZipPrefixIsRefused() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(AddressLookupController.ZIP_PREFIX_PATH + "/{stateZipCd}", "NY1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("stateZipCd"));
        }

        /**
         * A well-formed lookup value is ADMITTED, and an unseeded one is then reported absent.
         *
         * <p>Assumptions: 404 is the expected outcome here and it is the positive control the refusal cases
         * need. The store answers empty for every value in this fixture, so reaching the absent-row refusal
         * at all is what proves the constraint admitted the value rather than refusing it -- and it
         * simultaneously shows the two answers stay distinguishable, which was the whole defect.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a well-formed but unseeded area code is admitted and reported absent 404")
        void aWellFormedAreaCodeIsAdmittedThenReportedAbsent() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(AddressLookupController.AREA_CODE_PATH + "/{areaCd}", "201"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message")
                            .value(AddressLookupService.MESSAGE_AREA_CODE_NOT_FOUND));
        }
    }

    /**
     * The paging direction, on every one of the five routes that publishes it.
     *
     * <p>⚠️ Purpose and Refactoring Rationale: review found that every one of these five routes declared
     * its {@code direction} query parameter as {@link PageDirection} itself, which the framework binds
     * through {@code StringToEnumConverterFactory} and therefore through {@code Enum.valueOf} against the
     * CONSTANT NAME. The published contract declares two lower-case values, {@code next} and
     * {@code previous}, and {@code ui/src/api/reference.ts} sends exactly those -- so the only two values
     * a caller is told to send were the two the binding refused, while {@code NEXT} and {@code PREVIOUS},
     * which appear in no contract, were accepted. Every backward page on every browse in this module was
     * unreachable. The nested cases below send the two published spellings on all five routes and assert
     * they are honoured, and then send an unadmitted value and assert the refusal names the parameter.</p>
     *
     * <p>Assumptions: all five routes are asserted rather than one standing for the rest. The defect was
     * five independent declarations of the same wrong type, so a single representative case would have
     * passed against a tree in which four of the five were still wrong -- which is the failure mode this
     * whole class exists to detect.</p>
     *
     * <p>Assumptions: the three address routes are driven with a position this class MINTS with the real
     * seal, and the two filtered browses are driven with a position of the sealed shape that their mocked
     * service never opens. The asymmetry follows the class's existing rule: the address service is real
     * here, so its position has to be genuine for the request to reach a query; the type and category
     * services are mocked, so what reaches them is the converted direction and nothing else.</p>
     */
    @Nested
    @DisplayName("on the paging direction")
    class OnThePagingDirection {

        /**
         * Mints a position of the area-code browse for this class's caller, under one direction.
         *
         * @param backward whether to mint the leading boundary a backward request moves from
         * @param codeClass the classification the walk was performed under, or {@code null} when none
         * @return a sealed position the dispatcher will open for that direction
         */
        private String areaCodePosition(boolean backward, String codeClass) {
            return ReferenceParameterConstraintTest.this.sealer.seal(
                    ReferencePaging.binding(AddressLookupService.AREA_CODE_BINDING, CALLER.getName(),
                            backward, codeClass),
                    "201");
        }

        /**
         * The forward spelling the contract publishes is honoured on the area-code browse.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the published forward spelling is accepted on the area-code browse")
        void theForwardSpellingIsAcceptedOnAreaCodes() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(AddressLookupController.AREA_CODE_PATH)
                            .param(PARAM_CURSOR, areaCodePosition(false, null))
                            .param(PARAM_DIRECTION, PageDirection.NEXT.wireValue()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isArray());
        }

        /**
         * The backward spelling the contract publishes is honoured on the area-code browse.
         *
         * <p>Assumptions: the position is minted under the BACKWARD binding, because a position presented
         * with that direction is the leading boundary of the page the caller is on. Minting it forward and
         * sending it backward is a refusal by design, so this case would fail for the wrong reason.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the published backward spelling is accepted on the area-code browse")
        void theBackwardSpellingIsAcceptedOnAreaCodes() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(AddressLookupController.AREA_CODE_PATH)
                            .param(PARAM_CURSOR, areaCodePosition(true, null))
                            .param(PARAM_DIRECTION, PageDirection.PREVIOUS.wireValue()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isArray());
        }

        /**
         * Both published spellings are honoured on the state browse.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("both published spellings are accepted on the state browse")
        void bothSpellingsAreAcceptedOnStates() throws Exception {
            for (PageDirection direction : PageDirection.values()) {
                String position = ReferenceParameterConstraintTest.this.sealer.seal(
                        ReferencePaging.binding(AddressLookupService.STATE_BINDING, CALLER.getName(),
                                direction == PageDirection.PREVIOUS),
                        "NY");
                ReferenceParameterConstraintTest.this.mockMvc
                        .perform(get(AddressLookupController.STATE_PATH)
                                .param(PARAM_CURSOR, position)
                                .param(PARAM_DIRECTION, direction.wireValue()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items").isArray());
            }
        }

        /**
         * Both published spellings are honoured on the state-and-prefix browse.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("both published spellings are accepted on the prefix browse")
        void bothSpellingsAreAcceptedOnZipPrefixes() throws Exception {
            for (PageDirection direction : PageDirection.values()) {
                String position = ReferenceParameterConstraintTest.this.sealer.seal(
                        ReferencePaging.binding(AddressLookupService.ZIP_PREFIX_BINDING,
                                CALLER.getName(), direction == PageDirection.PREVIOUS),
                        "NY10");
                ReferenceParameterConstraintTest.this.mockMvc
                        .perform(get(AddressLookupController.ZIP_PREFIX_PATH)
                                .param(PARAM_CURSOR, position)
                                .param(PARAM_DIRECTION, direction.wireValue()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items").isArray());
            }
        }

        /**
         * Both published spellings reach the transaction-type browse and arrive as the matching constant.
         *
         * <p>Assumptions: this case asserts the CONVERTED value that reached the service and not merely
         * the status, because that browse's service is mocked and would answer 200 for any direction at
         * all -- including a null one, which is what a silently-discarded parameter would look like.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("both published spellings reach the type browse as the matching constant")
        void bothSpellingsReachTheTypeBrowse() throws Exception {
            for (PageDirection direction : PageDirection.values()) {
                ReferenceParameterConstraintTest.this.mockMvc
                        .perform(get(TransactionTypeController.BASE_PATH)
                                .param(PARAM_CURSOR, SHAPED_CURSOR)
                                .param(PARAM_DIRECTION, direction.wireValue()))
                        .andExpect(status().isOk());

                ArgumentCaptor<TransactionTypeListRequest> received =
                        ArgumentCaptor.forClass(TransactionTypeListRequest.class);
                verify(ReferenceParameterConstraintTest.this.types, atLeastOnce())
                        .list(received.capture(), any(CursorToken.class), anyString());
                assertThat(received.getAllValues())
                        .as("the direction the handler passed on for wire value %s",
                                direction.wireValue())
                        .anyMatch(request -> request.direction() == direction);
            }
        }

        /**
         * Both published spellings reach the transaction-category browse as the matching constant.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("both published spellings reach the category browse as the matching constant")
        void bothSpellingsReachTheCategoryBrowse() throws Exception {
            for (PageDirection direction : PageDirection.values()) {
                ReferenceParameterConstraintTest.this.mockMvc
                        .perform(get(TransactionCategoryController.BASE_PATH)
                                .param(PARAM_CURSOR, SHAPED_CURSOR)
                                .param(PARAM_DIRECTION, direction.wireValue()))
                        .andExpect(status().isOk());

                ArgumentCaptor<TransactionCategoryListRequest> received =
                        ArgumentCaptor.forClass(TransactionCategoryListRequest.class);
                verify(ReferenceParameterConstraintTest.this.categories, atLeastOnce())
                        .list(received.capture(), any(CursorToken.class), anyString());
                assertThat(received.getAllValues())
                        .as("the direction the handler passed on for wire value %s",
                                direction.wireValue())
                        .anyMatch(request -> request.direction() == direction);
            }
        }

        /**
         * A value outside the published domain is refused 400 naming the direction parameter.
         *
         * <p>Assumptions: the FIELD is asserted and not only the status. The framework's own refusal of an
         * unconvertible enumeration arrives as a type mismatch carrying no field at all, so a caller was
         * told something was wrong without being told which parameter to correct; naming it is the second
         * improvement the conversion member was written for, and a case asserting only the status would
         * pass against a tree that had lost it.</p>
         *
         * <p>Assumptions: the constant NAME is the value sent, because that is the spelling the broken
         * binding accepted. A case sending arbitrary text would be refused by a tree in which the old
         * binding was still in place, so it would not distinguish the two.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the constant name the old binding accepted is now refused 400 naming direction")
        void theConstantNameIsRefusedNamingTheParameter() throws Exception {
            ReferenceParameterConstraintTest.this.mockMvc
                    .perform(get(AddressLookupController.AREA_CODE_PATH)
                            .param(PARAM_CURSOR, areaCodePosition(false, null))
                            .param(PARAM_DIRECTION, PageDirection.NEXT.name()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                    .andExpect(jsonPath("$.fieldErrors[0].field")
                            .value(PageDirection.PARAMETER_NAME))
                    .andExpect(jsonPath("$.fieldErrors[0].message")
                            .value(PageDirection.MESSAGE_UNADMITTED_DIRECTION));
        }

        /**
         * An unrecognised value is refused on each of the other four routes as well.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an unrecognised direction is refused 400 on all five routes")
        void anUnrecognisedDirectionIsRefusedEverywhere() throws Exception {
            String[] routes = {
                AddressLookupController.AREA_CODE_PATH,
                AddressLookupController.STATE_PATH,
                AddressLookupController.ZIP_PREFIX_PATH,
                TransactionTypeController.BASE_PATH,
                TransactionCategoryController.BASE_PATH,
            };
            for (String route : routes) {
                ReferenceParameterConstraintTest.this.mockMvc
                        .perform(get(route)
                                .param(PARAM_CURSOR, SHAPED_CURSOR)
                                .param(PARAM_DIRECTION, "sideways"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.fieldErrors[0].field")
                                .value(PageDirection.PARAMETER_NAME));
            }
        }

        /**
         * Pins the two wire spellings these cases send against the ones the type declares.
         *
         * <p>Assumptions: the spellings are read from the enumeration rather than written as literals, so
         * a change to either published value fails here instead of leaving the cases above asserting
         * against a contract that no longer says what they assume.</p>
         */
        @Test
        @DisplayName("the two wire spellings these cases send are the ones the contract publishes")
        void theWireSpellingsAreThePublishedOnes() {
            assertThat(PageDirection.NEXT.wireValue()).isEqualTo("next");
            assertThat(PageDirection.PREVIOUS.wireValue()).isEqualTo("previous");
            assertThat(PageDirection.NEXT.name()).isNotEqualTo(PageDirection.NEXT.wireValue());
        }
    }

    /**
     * Pins the one classification expression this class sends values against.
     *
     * <p>Assumptions: the expression is read from the request record rather than restated, so a change to
     * the published domain fails here instead of leaving the cases above asserting against a domain the
     * contract no longer declares.</p>
     */
    @Test
    @DisplayName("the classification domain this class exercises is the one the record declares")
    void theClassificationDomainIsTheDeclaredOne() {
        assertThat(LookupPageRequest.CODE_CLASS_PATTERN)
                .as("the two admitted classifications the accepted case sends")
                .isEqualTo("[GE]");
    }
}
