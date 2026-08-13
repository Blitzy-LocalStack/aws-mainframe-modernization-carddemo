package com.carddemo.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.security.OpaqueIdentifier;
import com.carddemo.reporting.dto.StatementDocument;
import com.carddemo.reporting.dto.StatementRequest;
import com.carddemo.reporting.dto.StatementResponse;
import com.carddemo.reporting.dto.StatementTransactionResponse;
import com.carddemo.reporting.service.ArtifactStore;
import com.carddemo.reporting.service.StatementService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the statement surface over a real request pipeline, without a running application context.
 *
 * <p>Alternatives Considered: a sliced web context. Rejected for the reason recorded on
 * {@link ReportControllerTest}: this module's configuration package builds a token decoder that
 * resolves the issuer's discovery document over the network at bean-creation time, so any context
 * including it fails in an isolated environment for a reason unrelated to the controller under test.
 *
 * <p>Assumptions: the three protective response headers are asserted on BOTH operations rather than on
 * one. They are set by a shared helper, so asserting one operation would leave the other's headers
 * unverified while appearing to cover them -- and a statement response missing one of them is
 * indistinguishable, from the outside, from one that never needed it.
 */
class StatementControllerTest {

    /** A fixed instant, so a failure body's timestamp is a known value rather than a clock read. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T09:14:27.481903Z");

    /** The narrowed rendering a response carries: twelve mask characters then four digits. */
    private static final String MASKED_CARD = "************1111";

    /** A specimen card number. Assumptions: the reserved test prefix, so it identifies no real card. */
    private static final String SAMPLE_CARD = "4111111111111111";

    /**
     * A distinctive account identifier at the declared eleven positions, zero-filled on the left.
     *
     * <p>Assumptions: eleven characters and not two, because {@code ACCT-ID} is {@code PIC 9(11)} and a
     * numeric picture is zero-filled -- the stored identifier is the padded form, so a request naming
     * the unpadded one is naming a row that does not exist.</p>
     */
    private static final String SAMPLE_ACCOUNT = "00021820493";

    /** The mapper used to write request bodies, deliberately without the money module. */
    private static final JsonMapper REQUEST_MAPPER = JsonMapper.builder().build();

    /**
     * A well-formed artifact selector, at the tokeniser's exact width.
     *
     * <p>Assumptions: composed from the declared width rather than typed as a literal, so a case
     * asserting that a malformed selector is refused cannot silently become a case asserting that a
     * well-formed one is.</p>
     */
    private static final String SELECTOR = "A".repeat(OpaqueIdentifier.TOKEN_LENGTH);

    /** The location the plain-text artifact is collected from, as the service composes it. */
    private static final String PLAIN_TEXT_LOCATION =
            StatementService.ARTIFACT_LOCATION_PREFIX + SELECTOR;

    /** The location the markup artifact is collected from, differing only in its selector. */
    private static final String MARKUP_LOCATION =
            StatementService.ARTIFACT_LOCATION_PREFIX + "B".repeat(OpaqueIdentifier.TOKEN_LENGTH);

    /** Where a stubbed statement begins in the run artifact, as a record ordinal. */
    private static final long FIRST_RECORD = 240L;

    /** How many records a stubbed statement occupies from that ordinal. */
    private static final long RECORD_COUNT = 27L;

    /** The bytes a stored artifact is stubbed to hold, standing for a rendered statement record. */
    private static final byte[] ARTIFACT_BYTES =
            "STATEMENT RECORD ONE\n".getBytes(StandardCharsets.UTF_8);

    private StatementService statements;

    private MockMvc mockMvc;

    /**
     * Builds a standalone pipeline over the controller with its collaborator mocked.
     */
    @BeforeEach
    void setUp() {
        statements = Mockito.mock(StatementService.class);

        JsonMapper mapper = JsonMapper.builder().addModule(new MoneyModule()).build();
        // WHY : Assumptions: TWO converters are registered, and the resource one is not optional here.
        //       setMessageConverters REPLACES the default list, so a pipeline carrying only the JSON
        //       converter cannot write the artifact body at all and the collection cases would fail on
        //       the harness rather than on the controller. A running application registers the resource
        //       converter itself, so this restores the production shape rather than extending it.
        mockMvc = MockMvcBuilders.standaloneSetup(new StatementController(statements))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper),
                        new ResourceHttpMessageConverter())
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    /**
     * Asserts that the description answers the narrowed number, the total as quoted text and the
     * three protective headers.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the description answers the narrowed number, a quoted total and the headers")
    void theDescriptionAnswersTheNarrowedNumber() throws Exception {
        when(statements.describe(any())).thenReturn(heading());

        mockMvc.perform(post(StatementController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(
                                new StatementRequest(SAMPLE_CARD, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardNumber").value(MASKED_CARD))
                .andExpect(jsonPath("$.totalAmount").value("-1234.56"))
                .andExpect(jsonPath("$.transactionCount").value(1))
                .andExpect(header().string(
                        StatementController.CONTENT_TYPE_OPTIONS_HEADER,
                        StatementController.NOSNIFF))
                .andExpect(header().string(
                        StatementController.CONTENT_SECURITY_POLICY_HEADER,
                        StatementController.STATEMENT_POLICY))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION,
                        StatementController.ATTACHMENT_DISPOSITION));
    }

    /**
     * Asserts that the transactions operation answers the statement's rows and nothing else.
     *
     * <p>Refactoring Rationale: the body is asserted to carry the ROWS alone, and an earlier revision
     * asserted a body carrying the heading summary beside them. The published contract declares two
     * operations here -- the summary above and the rows below -- so a body carrying both would be a
     * body no deployed route serves. The heading is still asserted, by the summary case above.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the transactions operation answers the statement's rows alone")
    void theDocumentAnswersHeadingAndLines() throws Exception {
        when(statements.compose(any())).thenReturn(new StatementDocument(heading(), List.of(line())));

        mockMvc.perform(post(StatementController.BASE_PATH + StatementController.TRANSACTIONS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(
                                new StatementRequest(SAMPLE_CARD, "00000000011"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].cardNumber").value(MASKED_CARD))
                .andExpect(jsonPath("$.items[0].transactionId").value("0000000000000001"))
                .andExpect(jsonPath("$.items[0].amount").value("-1234.56"))
                .andExpect(jsonPath("$.statement").doesNotExist())
                // WHY : Assumptions: the two count members are asserted on the ORDINARY body and not
                //       only on the truncated one, because false and equal is the state a caller reads
                //       on nearly every statement and is therefore the state it must be able to trust.
                //       A member present only when it is interesting is a member a client cannot rely
                //       on.
                .andExpect(jsonPath("$.transactionCount").value(1))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(header().string(
                        StatementController.CONTENT_SECURITY_POLICY_HEADER,
                        StatementController.STATEMENT_POLICY));
    }

    /**
     * Asserts that a bounded window reports the card's whole count and says it was bounded.
     *
     * <p>Purpose: this is the case the operation had no way to express. The service bounds the composed
     * window at {@code MAX_RESPONSE_TRANSACTIONS} rows, and the argument recorded for that bound is that
     * a caller compares the rows it received against the heading's true count -- but this operation
     * returns no heading, so before the two count members existed a bounded body and a whole one were
     * indistinguishable to a caller of it.</p>
     *
     * <p>Assumptions: the composed document is stubbed with FEWER rows than its heading counts, which is
     * exactly the shape the bounded service produces: the heading's count comes from a database aggregate
     * over the whole card while the rows are a window onto it. The figures are deliberately far apart so
     * that a body echoing the array's own length is visibly wrong rather than coincidentally right.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a bounded window reports the whole count and flags itself truncated")
    void aBoundedWindowReportsTheWholeCountAndFlagsItself() throws Exception {
        when(statements.compose(any())).thenReturn(new StatementDocument(
                headingCounting(4211), List.of(line(), line())));

        mockMvc.perform(post(StatementController.BASE_PATH + StatementController.TRANSACTIONS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(
                                new StatementRequest(SAMPLE_CARD, "00000000011"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.transactionCount")
                        .value(4211))
                .andExpect(jsonPath("$.truncated").value(true));
    }

    // WHY : Assumptions: a card with no activity is asserted to answer 200 with an EMPTY array and not
    //       404. The reference walks the cross-reference and produces a statement for every card it
    //       finds, whether or not that card had activity, so an empty document is a legitimate one and
    //       404 would tell a caller the card does not exist.
    /**
     * Asserts that a card with no activity answers a document with an empty transaction array.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a card with no activity answers an empty row collection")
    void aCardWithNoActivityAnswersAnEmptyDocument() throws Exception {
        StatementResponse empty = new StatementResponse(
                MASKED_CARD,
                "00000000011",
                "JOHN Q PUBLIC",
                Money.ZERO,
                0,
                PLAIN_TEXT_LOCATION,
                MARKUP_LOCATION,
                "2026-08-05 09:14:27.481903",
                FIRST_RECORD,
                RECORD_COUNT);
        when(statements.compose(any())).thenReturn(new StatementDocument(empty, List.of()));

        String body = mockMvc.perform(
                        post(StatementController.BASE_PATH + StatementController.TRANSACTIONS_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REQUEST_MAPPER.writeValueAsString(
                                        new StatementRequest(SAMPLE_CARD, null))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("\"items\":[]");
        // WHY : Assumptions: an empty statement is asserted NOT truncated, which is the one edge the
        //       derivation could plausibly get wrong. Zero rows against a zero count is equality and not
        //       shortfall, so a derivation written as "fewer rows than the count" rather than "strictly
        //       fewer" would flag every inactive card as a truncated document -- and a caller reading
        //       that would go looking for rows that do not exist.
        assertThat(body).contains("\"transactionCount\":0").contains("\"truncated\":false");
    }

    /**
     * Asserts that an unknown card answers 404 through the shared advice.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an unknown card answers 404 through the shared advice")
    void anUnknownCardAnswersNotFound() throws Exception {
        when(statements.describe(any()))
                .thenThrow(new NoSuchElementException("no cross-reference row for the requested card"));

        mockMvc.perform(post(StatementController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(
                                new StatementRequest(SAMPLE_CARD, null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_NOT_FOUND))
                .andExpect(jsonPath("$.status").value(404));
    }

    // WHY : Refactoring Rationale: this case previously stubbed a MASKED-COLLISION refusal -- "the
    //       requested card number masks to a rendering shared by 2 distinct cards". That refusal no
    //       longer exists and cannot be reached: selection is an equality on the whole number performed
    //       by a definer-rights function, which matches at most one row, so a collision has nothing to
    //       collide on. Stubbing a message the service can no longer raise leaves a green test asserting
    //       a contract nothing implements, so the stub is replaced with a refusal the service does
    //       raise -- an account holding more than one card -- and the advice mapping being asserted is
    //       unchanged.
    /**
     * Asserts that a service-raised selector refusal answers 400 naming the account field.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a multi-card account answers 400 naming the account field")
    void aMultiCardAccountAnswersBadRequest() throws Exception {
        when(statements.compose(any())).thenThrow(new ClientInputException(
                ApiError.CODE_VALIDATION, "accountId",
                "the requested account holds more than one card, so name the card instead"));

        mockMvc.perform(post(StatementController.BASE_PATH + StatementController.TRANSACTIONS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(
                                new StatementRequest(null, SAMPLE_ACCOUNT))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("accountId"));
    }

    // WHY : Assumptions: a width violation is asserted to be refused BEFORE the service is reached, and
    //       the never-verification is the substance of the case rather than a flourish. A short value
    //       admitted at the boundary reaches an exact-equality resolution, matches nothing, and comes
    //       back as "this card is not cross-referenced" -- so a caller that mistyped a digit is told a
    //       real card is missing. Earlier still, before the resolution path was rewritten, the same
    //       value reached a tail extraction and produced a server fault for the caller's own malformed
    //       input, which is the 500 this constraint exists to remove.
    /**
     * Asserts that a card number short of its declared width answers 400 and reaches no service.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a fifteen-digit card number answers 400 naming the card field")
    void aShortCardNumberAnswersBadRequest() throws Exception {
        mockMvc.perform(post(StatementController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"411111111111111\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cardNumber"));

        verify(statements, never()).describe(any());
    }

    /**
     * Asserts that a card number past its declared width answers 400 and reaches no service.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a seventeen-digit card number answers 400 naming the card field")
    void anOverWideCardNumberAnswersBadRequest() throws Exception {
        mockMvc.perform(post(StatementController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"41111111111111111\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cardNumber"));

        verify(statements, never()).describe(any());
    }

    // WHY : Assumptions: the account case is asserted separately and with an UNPADDED value, because
    //       the picture is PIC 9(11) and is zero-filled on the left -- the stored identifier for
    //       account 11 is eleven characters, not two. A caller sending "11" is looking for a row stored
    //       as "00000000011", so admitting it would answer a malformed request as an absent account.
    /**
     * Asserts that an account identifier short of its declared width answers 400.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an unpadded account identifier answers 400 naming the account field")
    void anUnpaddedAccountIdentifierAnswersBadRequest() throws Exception {
        mockMvc.perform(post(StatementController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"11\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("accountId"));

        verify(statements, never()).describe(any());
    }

    /**
     * Asserts that a card number of the right width carrying a non-digit answers 400.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a sixteen-character card number carrying a letter answers 400")
    void aNonNumericCardNumberAnswersBadRequest() throws Exception {
        mockMvc.perform(post(StatementController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"411111111111111X\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cardNumber"));

        verify(statements, never()).describe(any());
    }

    // WHY : Assumptions: a value at exactly the declared width is asserted to be ADMITTED, so no case
    //       above can pass by refusing everything. A boundary rule needs both sides measured; asserting
    //       only the refusals would be satisfied by a constraint that rejected every value.
    /**
     * Asserts that a card number at exactly its declared width reaches the service.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a sixteen-digit card number is admitted and reaches the service")
    void anExactWidthCardNumberIsAdmitted() throws Exception {
        when(statements.describe(any())).thenReturn(heading());

        mockMvc.perform(post(StatementController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"" + SAMPLE_CARD + "\"}"))
                .andExpect(status().isOk());

        verify(statements).describe(any());
    }

    // WHY : Assumptions: the refusal body is asserted to carry NEITHER the specimen number nor its
    //       four visible digits. The message a service raises is written into an operational record and
    //       echoed to the caller, so a refusal that quoted the value it refused would copy a primary
    //       account number into both -- which is the one leak a masking rule cannot undo afterwards.
    /**
     * Asserts that no refusal body repeats the card number the caller supplied.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a refusal body repeats neither the card number nor its visible digits")
    void aRefusalBodyRepeatsNoCardNumber() throws Exception {
        // WHY : Refactoring Rationale: the stubbed refusal was "the requested card resolves to a
        //       different account than the one stated", which was raised by a cross-check performed when
        //       both selectors arrived. Both-supplied is now refused outright as a contract violation --
        //       the published schema states exactly one of the two selects the statement -- so that
        //       cross-check no longer exists and the message is replaced with the refusal the service
        //       actually raises for this request. The request still carries both selectors, because that
        //       is the shape whose refusal body is most likely to quote a card number.
        when(statements.describe(any())).thenThrow(new ClientInputException(
                ApiError.CODE_VALIDATION, "accountId",
                "exactly one of cardNumber and accountId must be supplied, not both"));

        String body = mockMvc.perform(post(StatementController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(
                                new StatementRequest(SAMPLE_CARD, "00000000099"))))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .as("no refusal may quote the value it refused")
                .doesNotContain(SAMPLE_CARD)
                .doesNotContain("1111");
    }

    /**
     * Builds a statement heading carrying one transaction and a negative total.
     *
     * @return the heading
     */
    private static StatementResponse heading() {
        return headingCounting(1);
    }

    // WHY : Assumptions: the body is compared BYTE FOR BYTE rather than by length or by a prefix,
    //       because the plain-text artifact is the parity artifact the golden masters are compared
    //       against -- a response that re-encoded it would still satisfy a length assertion while
    //       destroying exactly the property the artifact exists for.
    /**
     * Asserts that a stored artifact is streamed unmodified, as an attachment, at its declared length.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a stored artifact is streamed as an attachment at its declared length")
    void aStoredArtifactIsStreamedAsAnAttachment() throws Exception {
        when(statements.collectArtifact(SELECTOR)).thenReturn(
                new ArtifactStore.OpenArtifact(ARTIFACT_BYTES.length,
                        new ByteArrayInputStream(ARTIFACT_BYTES)));

        byte[] body = mockMvc.perform(get(PLAIN_TEXT_LOCATION))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, ARTIFACT_BYTES.length))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        StatementController.ATTACHMENT_DISPOSITION))
                .andExpect(header().string(StatementController.CONTENT_TYPE_OPTIONS_HEADER,
                        StatementController.NOSNIFF))
                .andExpect(header().string(StatementController.CONTENT_SECURITY_POLICY_HEADER,
                        StatementController.STATEMENT_POLICY))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(body).isEqualTo(ARTIFACT_BYTES);
    }

    // WHY : Assumptions: this is the case that pins WHY the markup is served as an opaque attachment.
    //       The markup artifact is built from cardholder data, so a caller able to negotiate it as
    //       text/html could have a browser render it in this origin -- which is what the policy and the
    //       disposition header on every response of this surface exist to prevent. Asserting the
    //       refusal is what keeps a later "convenience" media type from being added without the
    //       argument being revisited.
    /**
     * Asserts that an artifact cannot be negotiated into a renderable media type.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an artifact cannot be negotiated as renderable markup")
    void anArtifactCannotBeNegotiatedAsMarkup() throws Exception {
        mockMvc.perform(get(MARKUP_LOCATION).accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotAcceptable());

        verify(statements, never()).collectArtifact(any());
    }

    /**
     * Asserts that an artifact the store does not hold answers 404 through the shared advice.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an artifact the store does not hold answers 404")
    void anAbsentArtifactAnswersNotFound() throws Exception {
        when(statements.collectArtifact(SELECTOR)).thenThrow(
                new NoSuchElementException("the requested statement artifact is not available"));

        mockMvc.perform(get(PLAIN_TEXT_LOCATION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_NOT_FOUND));
    }

    // WHY : Assumptions: the store is asserted NEVER CONSULTED, which is the property that makes the
    //       shape guard worth having. A malformed selector answered with 404 after a metadata call
    //       would be indistinguishable from this one by status alone, so the verification is what
    //       separates a guard from a coincidence.
    /**
     * Asserts that a selector of the wrong shape is refused before the store is consulted.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a selector of the wrong shape is refused before the store is consulted")
    void aMalformedSelectorIsRefusedBeforeTheStore() throws Exception {
        String tooShort = "A".repeat(OpaqueIdentifier.TOKEN_LENGTH - 1);

        mockMvc.perform(get(StatementService.ARTIFACT_LOCATION_PREFIX + tooShort))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION));

        verify(statements, never()).collectArtifact(any());
    }

    /**
     * Builds one statement heading reporting a stated transaction count.
     *
     * <p>Assumptions: the count is a parameter because it is the one heading member that can legitimately
     * disagree with the number of rows returned beside it -- it is a database aggregate over the whole
     * card, while the rows are a bounded window onto that card. Every other member is fixed.</p>
     *
     * @param transactionCount how many transactions the statement covers in total; must not be negative
     * @return the heading; never {@code null}
     */
    private static StatementResponse headingCounting(int transactionCount) {
        return new StatementResponse(
                MASKED_CARD,
                "00000000011",
                "JOHN Q PUBLIC",
                Money.of("-1234.56"),
                transactionCount,
                PLAIN_TEXT_LOCATION,
                MARKUP_LOCATION,
                "2026-08-05 09:14:27.481903",
                FIRST_RECORD,
                RECORD_COUNT);
    }

    /**
     * Builds one statement transaction line.
     *
     * @return the line
     */
    private static StatementTransactionResponse line() {
        return new StatementTransactionResponse(
                MASKED_CARD,
                "0000000000000001",
                "01",
                "0001",
                "POS       ",
                "GROCERIES",
                Money.of("-1234.56"),
                "000000123",
                "ACME STORES",
                "SEATTLE",
                "98101     ",
                "2022-07-18 09:00:00.000000",
                "2022-07-18 09:00:01.000000");
    }
}
