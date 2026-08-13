package com.carddemo.reference.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.web.CursorToken;
import com.carddemo.reference.dto.TransactionCategoryCreateRequest;
import com.carddemo.reference.dto.TransactionCategoryResponse;
import com.carddemo.reference.service.TransactionCategoryService;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the category creation through a real dispatcher and holds its response line to the contract.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Refactoring Rationale: the 201 of {@code src/main/resources/openapi/reference-api.yaml} declares a
 * {@code Location} header with {@code required: true}, and the handler produced only the status and a body,
 * so every successful creation answered without a header the document promises. Nothing could have caught it:
 * no test in this module drove the write routes through a dispatcher at all -- the two existing dispatcher
 * classes cover the date evaluation and the constrained query parameters -- and a service-level test cannot
 * see a response header, because the header is produced by the return value the handler hands the framework
 * rather than by anything the service does.
 *
 * <p>Assumptions: the dispatcher is assembled with {@code standaloneSetup} over a hand-constructed
 * controller and a substituted service, in the shape the two sibling dispatcher classes already use. No
 * security chain is installed, because what is under test is the response line rather than admittance, and
 * admittance for this route is covered by the rule-table assertions in
 * {@code com.carddemo.reference.config}.
 *
 * <p>Assumptions: the expected header value is read from the CONTRACT's own published example rather than
 * written here, so a case that passed by restating a mistake is not possible. The document publishes the
 * example path for this header, and the request this class sends carries the two key halves that example
 * names, so the two are comparable by construction.
 *
 * <p>Measured: withdrawing the header -- returning the body alone under a method-level created status, which
 * is what the handler did before -- fails ALL THREE cases, each reporting
 * {@code Response header 'Location' expected:<...> but was:<null>}. Every member the second case asserts
 * about the BODY continues to hold under that reversion, and so does the status; the header is therefore
 * asserted in all three cases rather than only the first, because a case that checked the body alone would
 * have passed against the defect. That is how the defect survived until now.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception tag.</p>
 */
class TransactionCategoryCreationDispatcherTest {

    /** The committed contract, read from the classpath so a stale packaged copy cannot pass. */
    private static final String CONTRACT_RESOURCE = "/openapi/reference-api.yaml";

    /** The type half the submitted body carries, and the one the contract's own example names. */
    private static final String TYPE_CD = "01";

    /** The category half the submitted body carries, and the one the contract's own example names. */
    private static final String CAT_CD = "0006";

    /** The description the submitted body carries, within the fifty characters the column declares. */
    private static final String DESCRIPTION = "PURCHASE RETAIL";

    /** The version a newly-written row holds, so the returned body is a stored one rather than a draft. */
    private static final long STORED_VERSION = 0L;

    /** A fixed instant, so a refusal rendered by the shared advice carries a reproducible timestamp. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T09:16:44.902355Z");

    /** The paging key the browse sealer is constructed with; this class issues no browse. */
    private static final byte[] CURSOR_KEY = "reference-creation-dispatcher-key".getBytes();

    /** The paging-token lifetime the sealer is constructed with; this class issues no browse. */
    private static final Duration CURSOR_LIFETIME = Duration.ofMinutes(10);

    /** The substituted write path, so the case asserts the response line and not the store. */
    private TransactionCategoryService categories;

    /** The dispatcher under test. */
    private MockMvc mockMvc;

    /** Assembles the dispatcher and the substituted write path before each case. */
    @BeforeEach
    void setUp() {
        this.categories = mock(TransactionCategoryService.class);
        when(this.categories.create(any(TransactionCategoryCreateRequest.class)))
                .thenReturn(new TransactionCategoryResponse(TYPE_CD, CAT_CD, DESCRIPTION,
                        STORED_VERSION));

        JacksonJsonHttpMessageConverter converter = new JacksonJsonHttpMessageConverter(
                JsonMapper.builder().addModule(new MoneyModule()).build());

        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new TransactionCategoryController(this.categories,
                        new CursorToken(CURSOR_KEY, CURSOR_LIFETIME)))
                .setMessageConverters(converter)
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    /**
     * A created category answers 201 carrying the path of the row it created.
     *
     * <p>Assumptions: the header is compared against the address the contract publishes as its example,
     * and it is additionally asserted to be a PATH rather than an absolute url. That second assertion is
     * not redundant: the usual idiom for this header composes an absolute url from the inbound request,
     * which behind an API gateway and an internal load balancer names the internal host rather than the one
     * the caller used -- and it would satisfy a check that only looked for the path as a suffix.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a created category answers 201 with the Location the contract publishes")
    void aCreatedCategoryAnswersWithThePublishedLocation() throws Exception {
        String published = publishedLocationExample();

        this.mockMvc.perform(post(TransactionCategoryController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submittedBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", published));

        assertThat(published)
                .as("the contract publishes a path, so the header must not name a host")
                .startsWith("/")
                .isEqualTo(TransactionCategoryController.BASE_PATH + "/" + TYPE_CD + "/" + CAT_CD);
    }

    /**
     * The created body is the stored representation, and the header addresses that same row.
     *
     * <p>Assumptions: the two halves of the address are asserted to be the ones the RESPONSE carries rather
     * than the ones the request sent. The handler reads them from the stored representation, so a write path
     * that normalised a submitted value would move the header with it; asserting against the request would
     * pass either way and would not show which of the two the header follows.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the created body is the stored row and the Location addresses that row")
    void theCreatedBodyIsTheStoredRowAndTheLocationAddressesIt() throws Exception {
        this.mockMvc.perform(post(TransactionCategoryController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submittedBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.typeCd").value(TYPE_CD))
                .andExpect(jsonPath("$.catCd").value(CAT_CD))
                .andExpect(jsonPath("$.description").value(DESCRIPTION))
                .andExpect(jsonPath("$.version").value((int) STORED_VERSION))
                .andExpect(header().string("Location",
                        TransactionCategoryController.BASE_PATH + "/" + TYPE_CD + "/" + CAT_CD));
    }

    /**
     * A normalised key half moves the address with it, because the address follows the stored row.
     *
     * <p>Purpose: this is the case that distinguishes a header built from the response from one built from
     * the request, and the two are indistinguishable on every ordinary creation. The substituted write path
     * answers with a different category half from the one submitted, which is what a normalising store would
     * do, and the header must name the half that was STORED -- otherwise the address a client follows resolves
     * to nothing.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the Location follows the stored key, not the submitted one")
    void theLocationFollowsTheStoredKeyRatherThanTheSubmittedOne() throws Exception {
        String storedCatCd = "0007";
        when(this.categories.create(any(TransactionCategoryCreateRequest.class)))
                .thenReturn(new TransactionCategoryResponse(TYPE_CD, storedCatCd, DESCRIPTION,
                        STORED_VERSION));

        this.mockMvc.perform(post(TransactionCategoryController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submittedBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        TransactionCategoryController.BASE_PATH + "/" + TYPE_CD + "/" + storedCatCd));
    }

    /**
     * Builds the submitted creation body.
     *
     * @return the JSON body naming both key halves and the description; never {@code null}
     */
    private static String submittedBody() {
        return "{\"typeCd\":\"" + TYPE_CD + "\",\"catCd\":\"" + CAT_CD + "\",\"description\":\""
                + DESCRIPTION + "\"}";
    }

    /**
     * Reads the address the contract publishes as the example of its created-location header.
     *
     * @return the published example address; never {@code null}
     * @throws IllegalStateException if the contract is absent, or declares no example for the header, either
     *     of which would mean this case was comparing against nothing
     */
    @SuppressWarnings("unchecked")
    private static String publishedLocationExample() {
        try (InputStream stream = TransactionCategoryCreationDispatcherTest.class
                .getResourceAsStream(CONTRACT_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(CONTRACT_RESOURCE + " is not on the test classpath");
            }
            Map<String, Object> contract = new Yaml().load(stream);
            Map<String, Object> operation = (Map<String, Object>) navigate(contract, "paths",
                    TransactionCategoryController.BASE_PATH, "post", "responses", "201", "headers",
                    "Location", "schema");
            Object examples = operation.get("examples");
            if (!(examples instanceof java.util.List<?> published) || published.isEmpty()) {
                throw new IllegalStateException(
                        "the contract declares no example for the created-location header");
            }
            return String.valueOf(published.get(0));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("could not read " + CONTRACT_RESOURCE, failure);
        }
    }

    /**
     * Walks a parsed document down a sequence of keys.
     *
     * @param root the parsed document
     * @param keys the keys to follow in order
     * @return the node reached
     * @throws IllegalStateException if any key is absent, naming the key, because a silent null would make
     *     the assertion above compare against nothing
     */
    @SuppressWarnings("unchecked")
    private static Object navigate(Map<String, Object> root, String... keys) {
        Object current = root;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> mapping) || !mapping.containsKey(key)) {
                throw new IllegalStateException("the contract has no '" + key + "' where one is required");
            }
            current = ((Map<String, Object>) mapping).get(key);
        }
        return current;
    }
}
