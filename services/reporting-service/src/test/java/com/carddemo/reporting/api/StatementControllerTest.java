package com.carddemo.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.security.OpaqueIdentifier;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.reporting.config.SecurityConfig;
import com.carddemo.reporting.dto.StatementDocument;
import com.carddemo.reporting.dto.StatementRequest;
import com.carddemo.reporting.dto.StatementResponse;
import com.carddemo.reporting.dto.StatementTransactionCollection;
import com.carddemo.reporting.dto.StatementTransactionResponse;
import com.carddemo.reporting.service.ArtifactStore;
import com.carddemo.reporting.service.StatementService;
import java.io.ByteArrayInputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the statement surface over a real request pipeline, without a running application context.
 *
 * <p>Alternatives Considered: a sliced web context. Rejected for the reason recorded on
 * {@link ReportControllerTest}: this module's configuration package builds a token decoder that
 * resolves the discovery document of the configured authorization server over the network at
 * bean-creation time, so any context including it fails in an isolated environment for a reason
 * unrelated to the controller under test.
 *
 * <p>Assumptions: the three protective response headers are asserted on BOTH operations rather than on
 * one. They are set by a shared helper, so asserting one operation would leave the other's headers
 * unverified while appearing to cover them -- and a statement response missing one of them is
 * indistinguishable, from the outside, from one that never needed it.
 *
 * <h2>What this class answers for, and what it does not</h2>
 *
 * <p>Assumptions: this class asserts TRANSPORT contracts and nothing beneath them. The statement
 * assembly, the fixed-width and markup rendering, the row ceiling and the arity of the rendered
 * artifact belong to {@link com.carddemo.reporting.service.StatementService} and to
 * {@code com.carddemo.reporting.mapper}, both of which are asserted by their own siblings; the
 * collaborator is substituted here, and a substitute returns what it was stubbed with. No byte
 * emission and no padding is performed anywhere in this class for the same reason.
 *
 * <p>Trade-offs: the two batch programs behind this surface, {@code app/cbl/CBSTM03A.CBL} and
 * {@code app/cbl/CBSTM03B.CBL}, DO have a golden-master oracle over their rendered output, and this
 * class deliberately compares against none of it. The accepted compromise is that a rendering
 * regression is caught by the sibling that exercises the composer rather than here: with the composer
 * substituted there is nothing in this pipeline for a golden file to be an oracle of.
 *
 * <h2>The labelled decision register</h2>
 *
 * <p>Ten decisions govern what this class asserts. Each is recorded once here under the label the
 * user-specified explainability rule names, and each cites measured evidence rather than describing
 * it.
 *
 * <p>SC-01 Refactoring Rationale: the method register of this surface is asserted POSITIVELY, where an
 * earlier revision of this class asserted no method refusal at all and left the register to be
 * inferred from the absence of a case. The baseline statement pair is a BATCH GENERATOR -- the whole
 * of {@code app/cbl/CBSTM03A.CBL} runs from one job step, {@code app/jcl/CREASTMT.JCL} L79 -- so the
 * only online-equivalent operation is retrieval, and a mutating verb on this surface would be an
 * operation the baseline has no analogue for. Both describing operations therefore admit exactly one
 * method and the artifact collection admits exactly one other, which the cases in
 * {@code OnTheMethodRegister} prove one verb at a time.
 *
 * <p>SC-02 Assumptions: {@link StatementRequest} carries exactly TWO components and NO date range, and
 * both halves of that are asserted rather than left to the absence of a case. The evidence is a
 * contrast between two jobs of the same tree: {@code app/jcl/CREASTMT.JCL} injects no date at all,
 * where the report job {@code app/jcl/TRANREPT.jcl} injects {@code PARM-START-DATE} at its L43 and
 * {@code PARM-END-DATE} at its L44 and filters on them. A statement is delimited by the statement, so
 * there is no range for a caller to state. The two widths are the copybook's own: sixteen positions
 * from {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY} L22 and eleven from
 * {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy} L5, the account being resolved to cards
 * through the cross-reference {@code app/cbl/CBSTM03B.CBL} reads.
 *
 * <p>SC-03 Assumptions: there is NO output-format selector because BOTH artifacts are always produced.
 * {@code app/jcl/CREASTMT.JCL} L79 runs one step that creates both datasets -- the eighty-byte plain
 * text at L87 to L91, its width declared at L89, and the hundred-byte markup at L92 to L96, its width
 * at L94 -- and the step before it, {@code STEP030} at L66, runs the do-nothing utility over both
 * datasets with a disposition that deletes them, so the pair is REPLACED on every run and never
 * appended to. Neither artifact is selectable, so a format component would name a choice the baseline
 * does not offer.
 *
 * <p>SC-04 Alternatives Considered: content negotiation returning the eighty-byte or the hundred-byte
 * artifact inline from the describing operation, so that one address served both the summary and the
 * document. Rejected on two counts. It would place fixed-width byte emission in the transport layer,
 * which belongs to {@code com.carddemo.reporting.mapper} and to nothing else; and it would make two
 * artifacts the baseline always produces together look mutually exclusive, since a single response can
 * carry only one representation. The two locations are therefore ARTIFACT LOCATIONS carried side by
 * side in one JSON body, and {@code OnTheTwoArtifactLocations} asserts that naming a renderable type
 * in an accept header does not switch the payload.
 *
 * <p>SC-05 Assumptions: the row order is CARD NUMBER FIRST and then transaction identifier, and the
 * key is a thirty-two-position composite. {@code app/cpy/COSTM01.CPY} declares {@code TRNX-KEY} at L21
 * as {@code TRNX-CARD-NUM PIC X(16)} at L22 followed by {@code TRNX-ID PIC X(16)} at L23 -- the
 * trailing component is sixteen positions wide and not fifteen, read from that line rather than
 * carried over from a sibling record -- and {@code app/jcl/CREASTMT.JCL} L53 sorts
 * {@code FIELDS=(263,16,CH,A,1,16,CH,A)}, the card number at position 263 then the identifier at
 * position 1, both ascending. The copybook DELIBERATELY reorders the key relative to
 * {@code app/cpy/CVTRA05Y.cpy}, whose own record leads with the identifier and carries the card number
 * at position 263; both records total 350 positions.
 *
 * <p>SC-06 Trade-offs: the transactions operation answers ONE bounded body rather than a walkable
 * sequence, and it publishes the fact of the bound rather than a means of stepping past it. The set is
 * closed by the statement's own period, so there is no open-ended sequence for a boundary token to
 * walk; where this migration does walk a sequence it walks it by KEY, because a scheme addressing rows
 * by their ordinal position skips and repeats rows under concurrent inserts and so changes observable
 * behaviour that browse-by-key does not. What is given up is the ability to retrieve an exceptionally
 * long history through this operation; what is bought is that no caller can be handed a page boundary
 * this surface cannot honour. A caller needing every row reads the rendered artifact, which carries no
 * ceiling.
 *
 * <p>SC-07 Assumptions: both timestamps stay OPAQUE twenty-six-position strings and are never parsed
 * into a date-time value on this surface. {@code app/jcl/CREASTMT.JCL} L54 rearranges the record with
 * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)}, which writes 16 plus 262 plus 50 equals 328 of
 * the 350 positions; the last of its three groups copies input positions 279 to 328, which is all
 * twenty-six positions of {@code TRNX-ORIG-TS} and only the FIRST TWENTY-FOUR of the twenty-six of
 * {@code TRNX-PROC-TS} at {@code app/cpy/COSTM01.CPY} L35. A strict parse of that value would fail and
 * a wholly blank twenty-six-position value is a legitimate one, so
 * {@link com.carddemo.common.time.TimestampFormatter#parse(String)} is deliberately NOT applied to a
 * statement timestamp anywhere in this class.
 *
 * <p>SC-08 Assumptions: every monetary amount is serialised as a JSON STRING and asserted as one.
 * {@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY} L29 is exact fixed point, and a JSON
 * number is parsed into IEEE-754 binary floating point by most clients, which destroys that exactness
 * at the boundary the user actually sees. The mechanism is {@link MoneyModule}, registered on the
 * converter of this pipeline exactly as {@code ReportingApplication} registers it on a deployment's;
 * {@code com.carddemo.reporting.config.OpenApiConfig} contributes no mapper bean precisely so that
 * registration stays in force, which is why a case here can assert the string form at all.
 *
 * <p>SC-09 Assumptions: the twenty-position padding item at {@code app/cpy/COSTM01.CPY} L36 is
 * DROPPED, and the drop is recorded here rather than inferred: it is padding to the fixed 350-position
 * length and carries no data, which leaves the thirteen elementary named items of that record as the
 * thirteen components of {@link StatementTransactionResponse}. Truncation is likewise not this layer's
 * concern -- only three of those thirteen reach the plain-text detail band, where
 * {@code app/cbl/CBSTM03A.CBL} L677 moves the hundred-position description into
 * {@code ST-TRANDT PIC X(49)} at its L135 -- so a case here asserts that a full-width description
 * survives transport unshortened.
 *
 * <p>SC-10 Alternatives Considered: pointing the secured group at a real or a stand-in authorization
 * server, so the chain resolved a token the way a deployment does. Rejected because it would make a
 * unit case depend on network reachability and on a credential this repository must never contain.
 * {@code com.carddemo.reporting.config.SecurityConfig} contributes no token decoder of its own and
 * relies on the framework's, which is derived from a configured location that
 * {@code src/test/resources/application-test.yml} declares EMPTY for exactly this reason; a
 * substituted decoder is supplied instead and no case here presents a token header at all.
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

    /** The whole address of the operation that answers the rows behind one statement. */
    private static final String TRANSACTIONS_ROUTE =
            StatementController.BASE_PATH + StatementController.TRANSACTIONS_PATH;

    /** A well-formed selector body, so a case can turn on something other than the body's shape. */
    private static final String SELECTOR_BODY = "{\"cardNumber\":\"" + SAMPLE_CARD + "\"}";

    /** The narrowed rendering of a second card, so an ordering case has two cards to order. */
    private static final String SECOND_MASKED_CARD = "************2222";

    /** The identifier component of the first stubbed row, at the declared sixteen positions. */
    private static final String SAMPLE_TRANSACTION_ID = "0000000000000001";

    /**
     * A HIGHER identifier than the first row carries, held by a LOWER card number.
     *
     * <p>Assumptions: the pairing is deliberate rather than incidental. A body ordered by identifier
     * alone would put this row second, and a body ordered card number first puts it first, so the two
     * orderings are distinguishable by one assertion instead of being indistinguishable on rows whose
     * identifiers happen to ascend with their card numbers.</p>
     */
    private static final String SECOND_TRANSACTION_ID = "0000000000000009";

    /** The originating instant a stubbed row carries, at the twenty-six declared positions. */
    private static final String ORIGIN_TIMESTAMP = "2022-07-18 09:00:00.000000";

    /** The processing instant a stubbed row carries, at the twenty-six declared positions. */
    private static final String PROCESSING_TIMESTAMP = "2022-07-18 09:00:01.000000";

    /**
     * The processing instant AS THE REARRANGING STEP LEAVES IT, twenty-four positions of twenty-six.
     *
     * <p>Assumptions: the value is derived from the whole one by removing its last two positions rather
     * than being typed independently, so it cannot drift away from the value it is a prefix of. Two is
     * the shortfall {@code app/jcl/CREASTMT.JCL} L54 produces, recorded on this class under SC-07.</p>
     */
    private static final String SHORT_PROCESSING_TIMESTAMP =
            PROCESSING_TIMESTAMP.substring(0, TimestampFormatter.TIMESTAMP_LENGTH - 2);

    /** A wholly blank processing instant at its declared width, which is a legitimate value. */
    private static final String BLANK_PROCESSING_TIMESTAMP =
            " ".repeat(TimestampFormatter.TIMESTAMP_LENGTH);

    /** The declared width of the description item, from {@code app/cpy/COSTM01.CPY} L28. */
    private static final int DESCRIPTION_WIDTH = 100;

    /** A description occupying every declared position, so a shortening would be visible. */
    private static final String FULL_WIDTH_DESCRIPTION = "GROCERIES ".repeat(DESCRIPTION_WIDTH / 10);

    /**
     * An authority naming no group this service recognises.
     *
     * <p>Assumptions: the value is shaped like the two admitted authorities and is neither of them,
     * because the caller under refusal has to be fully AUTHENTICATED for the case to mean anything -- a
     * rule reading only "is the caller authenticated" would have admitted it.</p>
     */
    private static final String UNRECOGNISED_AUTHORITY = "carddemo-visitor";

    /** The substituted statement composer every case in this class stubs or verifies against. */
    private StatementService statements;

    /** The entry point every case outside the secured group issues its requests through. */
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

    // WHY : Assumptions: the three asserted members are the reference's own heading figures. The
    //       assembled total is app/cbl/CBSTM03A.CBL L142 ST-TOTAL-TRAMT PIC Z(9).99-, written at its
    //       L434, the narrowed number stands for TRNX-CARD-NUM PIC X(16) at app/cpy/COSTM01.CPY L22, and
    //       the count is the number of rows the reference's detail band at app/cbl/CBSTM03A.CBL L132 to
    //       L137 would have rendered.
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

    // WHY : Assumptions: the rows this operation answers are the reference's detail band --
    //       app/cbl/CBSTM03A.CBL L676 moves the identifier, its L677 the description and its L678 the
    //       amount into ST-LINE14 at its L132 -- so a body carrying the heading beside them would be a
    //       body no address of this surface serves.
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

    // WHY : Assumptions: the reference reaches a statement by WALKING the cross-reference in a batch
    //       job, app/cbl/CBSTM03A.CBL L319 performing the get-next paragraph at its L345, so it never
    //       published a row window to a caller at all. The window and its ceiling are this surface's
    //       own, and their arity is answered for by the sibling that exercises the composer.
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
    // WHY : Assumptions: the walk is app/cbl/CBSTM03A.CBL L319 performing the cross-reference get-next
    //       at its L345 over the dataset app/jcl/CREASTMT.JCL L84 supplies, and the walk is driven by the
    //       cross-reference rather than by activity, so a card with no rows still yields a statement.
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

    // WHY : Assumptions: a card is resolved through the cross-reference the subprogram reads --
    //       app/cbl/CBSTM03B.CBL L37 selects XREFFILE and its L40 keys it on FD-XREF-CARD-NUM PIC X(16)
    //       at its L67, the dataset app/jcl/CREASTMT.JCL L84 supplies -- so a card with no row there is
    //       a card the reference cannot produce a statement for either.
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
    // WHY : Assumptions: an account maps to MANY cards because the cross-reference is keyed on the card
    //       -- app/cpy/CVACT03Y.cpy L5 declares XREF-CARD-NUM PIC X(16) as the leading item and its L7
    //       carries XREF-ACCT-ID PIC 9(11) as an attribute -- so an account selector can name a set the
    //       card-keyed statement flow cannot resolve to one statement.
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
    // WHY : Assumptions: the declared width is TRNX-CARD-NUM PIC X(16) at app/cpy/COSTM01.CPY L22,
    //       corroborated by the control-break field WS-SAVE-CARD PIC X(16) at app/cbl/CBSTM03A.CBL L69,
    //       so a fifteen-position value cannot match a stored row under any circumstances.
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

    // WHY : Assumptions: sixteen positions is the whole of the field and not a ceiling --
    //       TRNX-CARD-NUM PIC X(16) at app/cpy/COSTM01.CPY L22 and FD-XREF-CARD-NUM PIC X(16) at
    //       app/cbl/CBSTM03B.CBL L67 agree -- so a seventeenth position cannot be stored and a value
    //       carrying one names no card.
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
    // WHY : Assumptions: the eleven positions are ACCT-ID PIC 9(11) at app/cpy/CVACT01Y.cpy L5, the key
    //       the statement flow reads the account by -- FD-ACCT-ID PIC 9(11) at app/cbl/CBSTM03B.CBL L77
    //       over the dataset app/jcl/CREASTMT.JCL L85 supplies.
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

    // WHY : Assumptions: the width alone does not settle the alphabet, because an X(16) picture holds
    //       any sixteen characters. What rejects a letter is the numeric overlay app/cpy/CVCRD01Y.cpy
    //       declares, CC-CARD-NUM PIC X(16) at its L37 redefined as PIC 9(16) at its L39.
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
    // WHY : Assumptions: the admitted width is the copybook's own, TRNX-CARD-NUM PIC X(16) at
    //       app/cpy/COSTM01.CPY L22, so the boundary this case measures from is a declared contract and
    //       not a convention of this surface.
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
    // WHY : Assumptions: the value withheld is a whole primary account number at the sixteen positions
    //       app/cpy/COSTM01.CPY L22 declares, and the four positions the narrowed rendering does reveal
    //       are withheld here too, because a refusal has no need of even those.
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
    // WHY : Assumptions: the plain-text rendering is the eighty-position dataset app/jcl/CREASTMT.JCL
    //       creates at its L87 to L91, its width declared at its L89, written by the program that job
    //       runs at its L79 -- fixed-position output whose bytes are the parity artifact, so transport
    //       must not re-encode them.
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
    // WHY : Assumptions: the markup rendering is the hundred-position dataset app/jcl/CREASTMT.JCL
    //       creates at its L92 to L96, its width declared at its L94, and it is built from the customer
    //       name the reference assembles into ST-NAME PIC X(75) at app/cbl/CBSTM03A.CBL L91 -- cardholder
    //       text, which is why a browser must not be invited to render it in this origin.
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

    // WHY : Assumptions: an absent rendering is a legitimate state and not a fault, because
    //       app/jcl/CREASTMT.JCL L66 runs the do-nothing utility over both datasets with a disposition
    //       that deletes them before its L79 recreates them -- so between those two steps neither
    //       rendering exists at all.
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
    // WHY : Assumptions: an opaque selector has NO baseline analogue -- the reference names its two
    //       renderings by dataset at app/jcl/CREASTMT.JCL L91 and L96 -- so the selector's shape is this
    //       surface's own published contract and refusing a malformed one discloses nothing new.
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
        return lineOf(MASKED_CARD, SAMPLE_TRANSACTION_ID, "GROCERIES", PROCESSING_TIMESTAMP);
    }

    // WHY : Assumptions: the four varied components are exactly the four this class needs to vary --
    //       the two key components for the ordering case, the description for the shortening case and
    //       the processing instant for the two short-value cases. Every other component is held fixed,
    //       so a case that turns on one of the four cannot be satisfied by a change in another.
    /**
     * Builds one statement transaction line varying the four components the cases below turn on.
     *
     * @param cardNumber the narrowed card rendering, which is the leading key component; must carry
     *     content, because {@link StatementTransactionResponse} refuses a blank one
     * @param transactionId the identifier at its sixteen declared positions, which is the trailing key
     *     component; must carry content for the same reason
     * @param description the free-text description as the record holds it, at up to the hundred
     *     positions {@code app/cpy/COSTM01.CPY} L28 declares
     * @param processingTimestamp the processing instant exactly as the caller should receive it, which
     *     may be shorter than its declared width or wholly blank
     * @return the line carrying those four components and fixed values for the other nine; never
     *     {@code null}
     */
    private static StatementTransactionResponse lineOf(String cardNumber, String transactionId,
            String description, String processingTimestamp) {
        return new StatementTransactionResponse(
                cardNumber,
                transactionId,
                "01",
                "0001",
                "POS       ",
                description,
                Money.of("-1234.56"),
                "000000123",
                "ACME STORES",
                "SEATTLE",
                "98101     ",
                ORIGIN_TIMESTAMP,
                processingTimestamp);
    }

    /**
     * Reads the component names a record declares, in the order the record declares them.
     *
     * <p>Assumptions: the component set is read by REFLECTION rather than inferred from a rendered
     * body, because the two cases that use it assert an ABSENCE -- that no component names a date
     * range, a period or an output format -- and a rendered body cannot evidence the absence of a
     * member that was simply left unset. The declaration is the contract; the rendering is one
     * instance of it.</p>
     *
     * @param recordType the record type whose declared components are wanted; must be a record class
     * @return the component names in declaration order; never {@code null}
     */
    private static List<String> componentNamesOf(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Groups the cases that pin which HTTP method each address of this surface admits.
     *
     * <p>Assumptions: each unoffered verb is asserted on its own rather than one standing for the
     * others, because a mapping admits methods individually and a case naming one would pass while
     * another was silently opened.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a
     * type declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Nested
    @DisplayName("on the method register of this surface")
    class OnTheMethodRegister {

        // WHY : Refactoring Rationale: SC-01 on this class. The baseline statement pair is a batch
        //       generator driven from one job step, app/jcl/CREASTMT.JCL L79, so retrieval is the only
        //       online-equivalent operation and a mutating verb here would have no baseline analogue.
        //       An earlier revision of this class asserted no method refusal at all, which left the
        //       register to be inferred from the absence of a case.
        /**
         * Confirms a mutating verb is refused on the describing address with the shared problem shape.
         *
         * <p>Assumptions: the refusal is asserted to carry the published problem shape rather than a
         * bodyless status, because every other refusal of this surface carries it and a caller cannot
         * be expected to parse one answer differently from the rest.</p>
         *
         * @param verb one mutating HTTP method this address does not offer, supplied once per
         *     invocation as its uppercase name
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
        @DisplayName("a mutating verb is refused on the describing address")
        void aMutatingVerbIsRefusedOnTheDescribingAddress(String verb) throws Exception {
            mockMvc.perform(request(HttpMethod.valueOf(verb), StatementController.BASE_PATH))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_METHOD_NOT_ALLOWED))
                    .andExpect(jsonPath("$.status").value(ApiError.METHOD_NOT_ALLOWED_STATUS));

            verify(statements, never()).describe(any());
        }

        // WHY : Refactoring Rationale: SC-01 on this class, asserted a second time because the rows
        //       address is a SECOND address. A mapping admits methods per address, so a register proved
        //       on one of the two would leave the other unproved while appearing to cover it.
        // WHY : Assumptions: the rows address answers the reference's detail band, moved into place at
        //       app/cbl/CBSTM03A.CBL L676 to L678 by the one job step app/jcl/CREASTMT.JCL L79 runs, and
        //       nothing in the baseline writes a row back.
        /**
         * Confirms a mutating verb is refused on the rows address with the shared problem shape.
         *
         * @param verb one mutating HTTP method this address does not offer, supplied once per
         *     invocation as its uppercase name
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
        @DisplayName("a mutating verb is refused on the rows address")
        void aMutatingVerbIsRefusedOnTheRowsAddress(String verb) throws Exception {
            mockMvc.perform(request(HttpMethod.valueOf(verb), TRANSACTIONS_ROUTE))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_METHOD_NOT_ALLOWED));

            verify(statements, never()).compose(any());
        }

        // WHY : Assumptions: SC-01 on this class reaches the collection address too, and here the
        //       refused set includes the verb the other two addresses ADMIT. The program named at
        //       app/jcl/CREASTMT.JCL L79 writes both renderings from that single step -- the datasets its
        //       L87 to L91 and its L92 to L96 declare -- and nothing in the baseline writes a rendering
        //       back, so collection is a read of an already-written artifact and no verb that could
        //       replace one belongs on this address.
        /**
         * Confirms every verb but retrieval is refused on the artifact collection address.
         *
         * @param verb one HTTP method this address does not offer, supplied once per invocation as its
         *     uppercase name, including the verb the two describing addresses admit
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
        @DisplayName("every verb but retrieval is refused on the artifact collection address")
        void everyVerbButRetrievalIsRefusedOnTheCollectionAddress(String verb) throws Exception {
            mockMvc.perform(request(HttpMethod.valueOf(verb), PLAIN_TEXT_LOCATION))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_METHOD_NOT_ALLOWED));

            verify(statements, never()).collectArtifact(any());
        }
    }

    /**
     * Groups the cases that pin what a caller may state when it asks for a statement.
     *
     * <p>Assumptions: the two DELIBERATE ABSENCES are asserted as positive properties rather than left
     * to the absence of a case. A class that merely never sends a date range proves nothing about
     * whether one would be honoured.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a
     * type declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Nested
    @DisplayName("on the selector contract")
    class OnTheSelectorContract {

        // WHY : Assumptions: SC-02 and SC-03 on this class. app/jcl/CREASTMT.JCL injects no date at all,
        //       where app/jcl/TRANREPT.jcl L43 injects PARM-START-DATE and its L44 PARM-END-DATE and the
        //       sort at its L47 filters on them; and app/jcl/CREASTMT.JCL L79 creates BOTH renderings in
        //       one step, so neither is selectable. The two widths are app/cpy/COSTM01.CPY L22 and
        //       app/cpy/CVACT01Y.cpy L5.
        /**
         * Confirms the selector declares two components and neither a date range nor an output format.
         *
         * <p>Assumptions: the assertion is over the DECLARED components, so it holds for every request
         * this type can carry rather than for the one a case happens to send.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the selector declares two components, no date range and no output format")
        void theSelectorDeclaresTwoComponentsAndNoRange() {
            List<String> components = componentNamesOf(StatementRequest.class);

            assertThat(components)
                    .as("the selector names one card or one account, and nothing else")
                    .containsExactly("cardNumber", "accountId");
            assertThat(components)
                    .as("no component may name a period, a range or an output rendering")
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT)
                            .matches(".*(date|range|period|format|layout|rendering).*"));
        }

        // WHY : Assumptions: SC-02 on this class, asserted at the BOUNDARY rather than only over the
        //       declaration. A caller that has read app/jcl/TRANREPT.jcl L43 to L44 and expects the same
        //       two parameters here has to learn that they narrow nothing, and the only way to show that
        //       is to send them and read what reached the service.
        /**
         * Confirms a supplied date range and output format narrow nothing and never reach the service.
         *
         * <p>Assumptions: the request that reached the collaborator is CAPTURED and inspected, because
         * an answer of 200 alone would be equally consistent with the extra members having been honoured
         * silently.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a supplied date range and output format narrow nothing")
        void aSuppliedDateRangeNarrowsNothing() throws Exception {
            when(statements.describe(any())).thenReturn(heading());
            ArgumentCaptor<StatementRequest> captured =
                    ArgumentCaptor.forClass(StatementRequest.class);

            mockMvc.perform(post(StatementController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cardNumber\":\"" + SAMPLE_CARD + "\","
                                    + "\"startDate\":\"2022-01-01\",\"endDate\":\"2022-07-06\","
                                    + "\"format\":\"html\"}"))
                    .andExpect(status().isOk());

            verify(statements).describe(captured.capture());
            assertThat(captured.getValue().cardNumber())
                    .as("the card selector is the only thing the extra members left standing")
                    .isEqualTo(SAMPLE_CARD);
            assertThat(captured.getValue().accountId())
                    .as("the account selector was not supplied and must not be invented")
                    .isNull();
        }
    }

    /**
     * Groups the cases that pin the two rendered artifacts as locations rather than representations.
     *
     * <p>Assumptions: both locations are asserted on ONE body rather than one per case, because the
     * property under assertion is that they are carried together -- two cases each asserting one would
     * be satisfied by a surface that answered with either.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a
     * type declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Nested
    @DisplayName("on the two artifact locations")
    class OnTheTwoArtifactLocations {

        // WHY : Assumptions: SC-03 on this class. app/jcl/CREASTMT.JCL L79 runs one step that creates
        //       the eighty-position plain text at L87 to L91 and the hundred-position markup at L92 to
        //       L96, and the step at L66 deletes both first, so the pair is replaced together on every
        //       run and a response naming one of the two would be naming half a run.
        /**
         * Confirms one successful description carries both artifact locations, side by side.
         *
         * <p>Assumptions: the two locations are additionally asserted to DIFFER, because a surface that
         * composed one location and published it twice would satisfy a presence assertion while leaving
         * a caller unable to collect the second rendering at all.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("one description carries both artifact locations")
        void oneDescriptionCarriesBothArtifactLocations() throws Exception {
            when(statements.describe(any())).thenReturn(heading());

            mockMvc.perform(post(StatementController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.plainTextUri").value(PLAIN_TEXT_LOCATION))
                    .andExpect(jsonPath("$.htmlUri").value(MARKUP_LOCATION));

            assertThat(PLAIN_TEXT_LOCATION)
                    .as("two renderings are two artifacts, so their locations cannot be one value")
                    .isNotEqualTo(MARKUP_LOCATION);
        }

        // WHY : Alternatives Considered: SC-04 on this class. Returning the eighty-position or the
        //       hundred-position rendering inline under a negotiated type was the rejected alternative:
        //       it would put fixed-width emission in this layer, which belongs to
        //       com.carddemo.reporting.mapper, and would make two renderings that
        //       app/jcl/CREASTMT.JCL L79 always produces together look mutually exclusive.
        /**
         * Confirms naming a renderable type does not switch either describing operation's payload.
         *
         * <p>Assumptions: BOTH describing addresses are asserted in one case because the property is the
         * SURFACE-wide producible type and not one route's wiring, and the collaborator is additionally
         * asserted never consulted -- a refusal rendered after the statement had been assembled would
         * have already paid for the answer it then declined to give.</p>
         *
         * @param renderableType a media type a browser would render rather than parse, supplied once per
         *     invocation
         * @throws Exception if either request cannot be performed
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {MediaType.TEXT_HTML_VALUE, MediaType.TEXT_PLAIN_VALUE})
        @DisplayName("naming a renderable type does not switch the payload")
        void namingARenderableTypeDoesNotSwitchThePayload(String renderableType) throws Exception {
            mockMvc.perform(post(StatementController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.parseMediaType(renderableType))
                            .content(SELECTOR_BODY))
                    .andExpect(status().isNotAcceptable())
                    .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_NOT_ACCEPTABLE));

            mockMvc.perform(post(TRANSACTIONS_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.parseMediaType(renderableType))
                            .content(SELECTOR_BODY))
                    .andExpect(status().isNotAcceptable())
                    .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_NOT_ACCEPTABLE));

            verify(statements, never()).describe(any());
            verify(statements, never()).compose(any());
        }

        // WHY : Assumptions: SC-04 on this class, its admitted half. A case proving only that a
        //       renderable type is refused would be satisfied by a surface that produced nothing at all,
        //       so the type it DOES produce is asserted on the same address.
        // WHY : Assumptions: the two renderings the reference produces are datasets --
        //       app/jcl/CREASTMT.JCL L87 to L91 and its L92 to L96 -- so what this address answers is a
        //       description OF them and never one of them, and structured data is the only shape a
        //       description can take.
        /**
         * Confirms the describing operation answers structured data rather than statement bytes.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the describing operation answers structured data")
        void theDescribingOperationAnswersStructuredData() throws Exception {
            when(statements.describe(any())).thenReturn(heading());

            mockMvc.perform(post(StatementController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        }
    }

    /**
     * Groups the cases that pin the composition, the ordering and the boundedness of one row.
     *
     * <p>Assumptions: the row contract is asserted component by component rather than by comparing a
     * whole body against a literal, because a whole-body comparison fails on any change and therefore
     * says nothing about which part of the contract moved.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a
     * type declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Nested
    @DisplayName("on the row contract")
    class OnTheRowContract {

        // WHY : Assumptions: SC-09 on this class. app/cpy/COSTM01.CPY declares thirteen elementary named
        //       items between its L22 and its L35, gathered under the two group items at its L21 and its
        //       L24, and closes with a twenty-position padding item at its L36 that pads the record to
        //       350 positions and carries no data. The padding is dropped and the drop is recorded here.
        /**
         * Confirms the row declares the thirteen named record items and no padding component.
         *
         * <p>Assumptions: the order is asserted as well as the set, because the components are the
         * copybook's own items in the copybook's own sequence and a reordering would break the
         * correspondence a reader of both files relies on.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the row declares the thirteen named items and no padding component")
        void theRowDeclaresTheThirteenNamedItems() {
            assertThat(componentNamesOf(StatementTransactionResponse.class))
                    .as("the thirteen elementary items of app/cpy/COSTM01.CPY L22 to L35, in order")
                    .containsExactly("cardNumber", "transactionId", "typeCode", "categoryCode",
                            "source", "description", "amount", "merchantId", "merchantName",
                            "merchantCity", "merchantZip", "originTimestamp", "processingTimestamp")
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("filler"));
        }

        // WHY : Assumptions: SC-09 on this class, its rendered half. Every one of the thirteen is
        //       asserted PRESENT in a body, because a declared component that the serialiser withheld
        //       would still satisfy the declaration assertion above while reaching no caller.
        // WHY : Assumptions: the thirteen are the elementary named items app/cpy/COSTM01.CPY declares
        //       between its L22 and its L35, gathered under the group items at its L21 and its L24.
        /**
         * Confirms every one of the thirteen components reaches the caller in a rendered row.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("every one of the thirteen components reaches the caller")
        void everyOneOfTheThirteenComponentsReachesTheCaller() throws Exception {
            when(statements.compose(any()))
                    .thenReturn(new StatementDocument(heading(), List.of(line())));

            mockMvc.perform(post(TRANSACTIONS_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].cardNumber").value(MASKED_CARD))
                    .andExpect(jsonPath("$.items[0].transactionId").value(SAMPLE_TRANSACTION_ID))
                    .andExpect(jsonPath("$.items[0].typeCode").value("01"))
                    .andExpect(jsonPath("$.items[0].categoryCode").value("0001"))
                    .andExpect(jsonPath("$.items[0].source").value("POS       "))
                    .andExpect(jsonPath("$.items[0].description").value("GROCERIES"))
                    .andExpect(jsonPath("$.items[0].amount").value("-1234.56"))
                    .andExpect(jsonPath("$.items[0].merchantId").value("000000123"))
                    .andExpect(jsonPath("$.items[0].merchantName").value("ACME STORES"))
                    .andExpect(jsonPath("$.items[0].merchantCity").value("SEATTLE"))
                    .andExpect(jsonPath("$.items[0].merchantZip").value("98101     "))
                    .andExpect(jsonPath("$.items[0].originTimestamp").value(ORIGIN_TIMESTAMP))
                    .andExpect(jsonPath("$.items[0].processingTimestamp")
                            .value(PROCESSING_TIMESTAMP))
                    .andExpect(jsonPath("$.items[0].filler").doesNotExist());
        }

        // WHY : Assumptions: SC-05 on this class. app/cpy/COSTM01.CPY declares TRNX-KEY at its L21 as
        //       TRNX-CARD-NUM PIC X(16) at its L22 followed by TRNX-ID PIC X(16) at its L23 -- a
        //       thirty-two-position composite with the card number leading -- and app/jcl/CREASTMT.JCL
        //       L53 sorts FIELDS=(263,16,CH,A,1,16,CH,A), the card number at position 263 then the
        //       identifier at position 1. The copybook deliberately reorders the key relative to
        //       app/cpy/CVTRA05Y.cpy, whose own L5 leads with the identifier and whose L15 places the
        //       card number at position 263; both records total 350 positions.
        /**
         * Confirms transport preserves the card-number-first composite ordering of the rows.
         *
         * <p>Assumptions: the two stubbed rows are chosen so that the two candidate orderings DISAGREE --
         * the lower card carries the higher identifier -- so a body sorted by identifier alone and a body
         * sorted card number first are told apart by one assertion. Rows whose identifiers happened to
         * ascend with their card numbers would satisfy either.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("transport preserves the card-number-first composite ordering")
        void transportPreservesTheCardNumberFirstOrdering() throws Exception {
            StatementTransactionResponse leading = lineOf(MASKED_CARD, SECOND_TRANSACTION_ID,
                    "GROCERIES", PROCESSING_TIMESTAMP);
            StatementTransactionResponse trailing = lineOf(SECOND_MASKED_CARD,
                    SAMPLE_TRANSACTION_ID, "GROCERIES", PROCESSING_TIMESTAMP);
            when(statements.compose(any())).thenReturn(
                    new StatementDocument(headingCounting(2), List.of(leading, trailing)));

            mockMvc.perform(post(TRANSACTIONS_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].cardNumber").value(MASKED_CARD))
                    .andExpect(jsonPath("$.items[0].transactionId").value(SECOND_TRANSACTION_ID))
                    .andExpect(jsonPath("$.items[1].cardNumber").value(SECOND_MASKED_CARD))
                    .andExpect(jsonPath("$.items[1].transactionId").value(SAMPLE_TRANSACTION_ID));

            assertThat(MASKED_CARD.compareTo(SECOND_MASKED_CARD))
                    .as("the leading row's card must sort below the trailing row's for the case to"
                            + " distinguish the two candidate orderings")
                    .isNegative();
            assertThat(SECOND_TRANSACTION_ID.compareTo(SAMPLE_TRANSACTION_ID))
                    .as("and its identifier must sort above, so identifier-first ordering would have"
                            + " reversed the pair")
                    .isPositive();
        }

        // WHY : Trade-offs: SC-06 on this class. The set is closed by the statement's own period, so the
        //       body publishes the FACT of its ceiling rather than a boundary token for stepping past
        //       one; where this migration does walk a sequence it walks it by key, because a scheme
        //       addressing rows by ordinal position skips and repeats rows under concurrent inserts.
        // WHY : Assumptions: the reference reaches its rows by walking the cross-reference inside a
        //       batch job -- app/cbl/CBSTM03A.CBL L319 performing the get-next paragraph at its L345 --
        //       and published no walkable sequence to any caller, so there is no boundary token to carry
        //       across.
        /**
         * Confirms the collection publishes its ceiling and carries no boundary token of any kind.
         *
         * <p>Assumptions: the absence is asserted over the DECLARED components as well as over a
         * rendered body, so it holds for every answer this operation can give rather than for the one a
         * case happens to provoke.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the collection publishes its ceiling and carries no boundary token")
        void theCollectionPublishesItsCeilingAndNoBoundaryToken() throws Exception {
            assertThat(componentNamesOf(StatementTransactionCollection.class))
                    .as("the rows, the card's whole count and whether the window was capped")
                    .containsExactly("items", "transactionCount", "truncated");

            when(statements.compose(any())).thenReturn(
                    new StatementDocument(headingCounting(4211), List.of(line(), line())));

            mockMvc.perform(post(TRANSACTIONS_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionCount").value(4211))
                    .andExpect(jsonPath("$.truncated").value(true))
                    .andExpect(jsonPath("$.cursor").doesNotExist())
                    .andExpect(jsonPath("$.nextCursor").doesNotExist())
                    .andExpect(jsonPath("$.firstKey").doesNotExist())
                    .andExpect(jsonPath("$.lastKey").doesNotExist())
                    .andExpect(jsonPath("$.hasNext").doesNotExist());
        }

        // WHY : Assumptions: SC-09 on this class, its truncation half. Only three of the thirteen items
        //       reach the plain-text detail band -- app/cbl/CBSTM03A.CBL L676 to L678 move the
        //       identifier, the description and the amount -- and its L677 moves the hundred-position
        //       description into ST-TRANDT PIC X(49) at its L135. That shortening belongs to
        //       com.carddemo.reporting.mapper, so this layer must hand the whole value through.
        /**
         * Confirms a description occupying every declared position survives transport unshortened.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a full-width description survives transport unshortened")
        void aFullWidthDescriptionSurvivesTransportUnshortened() throws Exception {
            when(statements.compose(any())).thenReturn(new StatementDocument(heading(),
                    List.of(lineOf(MASKED_CARD, SAMPLE_TRANSACTION_ID, FULL_WIDTH_DESCRIPTION,
                            PROCESSING_TIMESTAMP))));

            mockMvc.perform(post(TRANSACTIONS_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].description").value(FULL_WIDTH_DESCRIPTION));

            assertThat(FULL_WIDTH_DESCRIPTION)
                    .as("the case is only a shortening case if the value fills its declared width")
                    .hasSize(DESCRIPTION_WIDTH);
        }
    }

    /**
     * Groups the cases that hold both timestamps to opaque strings of their declared width.
     *
     * <p>Assumptions: a timestamp is treated as TEXT throughout this surface, so the cases assert its
     * characters rather than an instant it might denote. Nothing in this class calls a parsing routine
     * on one, and SC-07 records why.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a
     * type declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Nested
    @DisplayName("on the two opaque timestamps")
    class OnTheTwoOpaqueTimestamps {

        // WHY : Assumptions: SC-07 on this class. app/cpy/COSTM01.CPY declares TRNX-ORIG-TS PIC X(26) at
        //       its L34 and TRNX-PROC-TS PIC X(26) at its L35, both character items, and the shared
        //       kernel's own declared width agrees at twenty-six.
        /**
         * Confirms both timestamps arrive as strings at the declared twenty-six positions.
         *
         * <p>Assumptions: the string TYPE is asserted as well as the value, because a value assertion
         * alone would still pass if the member had been rendered as a JSON number of the same digits --
         * and the shared kernel's declared width is compared against rather than a typed literal, so a
         * change of width cannot leave this case asserting a stale one.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("both timestamps arrive as strings at their declared width")
        void bothTimestampsArriveAsStringsAtTheirDeclaredWidth() throws Exception {
            when(statements.compose(any()))
                    .thenReturn(new StatementDocument(heading(), List.of(line())));

            mockMvc.perform(post(TRANSACTIONS_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].originTimestamp").isString())
                    .andExpect(jsonPath("$.items[0].originTimestamp").value(ORIGIN_TIMESTAMP))
                    .andExpect(jsonPath("$.items[0].processingTimestamp").isString())
                    .andExpect(jsonPath("$.items[0].processingTimestamp")
                            .value(PROCESSING_TIMESTAMP));

            assertThat(ORIGIN_TIMESTAMP).hasSize(TimestampFormatter.TIMESTAMP_LENGTH);
            assertThat(PROCESSING_TIMESTAMP).hasSize(TimestampFormatter.TIMESTAMP_LENGTH);
        }

        // WHY : Assumptions: SC-07 on this class, its measured half. app/jcl/CREASTMT.JCL L54 rearranges
        //       the record with OUTREC FIELDS=(1:263,16,17:1,262,279:279,50), writing 16 plus 262 plus 50
        //       equals 328 of the 350 positions; the last group copies input 279 to 328, which is all
        //       twenty-six positions of TRNX-ORIG-TS and only the first twenty-four of the twenty-six of
        //       TRNX-PROC-TS. A caller therefore legitimately receives a short value.
        /**
         * Confirms a processing timestamp two positions short of its width arrives unchanged.
         *
         * <p>Assumptions: the case would fail with a rendered problem body rather than a rendered row if
         * this layer parsed the value: a parse of twenty-four positions against the twenty-six-position
         * pattern raises {@link java.time.format.DateTimeParseException}, which the shared advice renders
         * as a server fault. Asserting the exact value back is what proves no parse occurred.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a short processing timestamp arrives unchanged and unparsed")
        void aShortProcessingTimestampArrivesUnchanged() throws Exception {
            when(statements.compose(any())).thenReturn(new StatementDocument(heading(),
                    List.of(lineOf(MASKED_CARD, SAMPLE_TRANSACTION_ID, "GROCERIES",
                            SHORT_PROCESSING_TIMESTAMP))));

            mockMvc.perform(post(TRANSACTIONS_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].processingTimestamp").isString())
                    .andExpect(jsonPath("$.items[0].processingTimestamp")
                            .value(SHORT_PROCESSING_TIMESTAMP));

            assertThat(SHORT_PROCESSING_TIMESTAMP)
                    .as("the rearranging step at app/jcl/CREASTMT.JCL L54 leaves two positions short")
                    .hasSize(TimestampFormatter.TIMESTAMP_LENGTH - 2);
        }

        // WHY : Assumptions: SC-07 on this class, and the distinction app/cpy/CVCRD01Y.cpy L30 draws. Its
        //       88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES attaches to CCARD-RETURN-MSG at its L29 and NOT
        //       to CCARD-ERROR-MSG at its L28, so the baseline itself holds a never-set field apart from
        //       a blank one. The target keeps them apart the same way: absent renders as a null member
        //       and blank renders as a present string of blanks.
        /**
         * Confirms a blank timestamp and an absent one are rendered differently, never collapsed.
         *
         * <p>Assumptions: the two are asserted on the SERIALISED text rather than through a path
         * expression, because a path expression reports an absent member and a member holding null
         * alike, which is exactly the distinction under assertion.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if either request cannot be performed
         */
        @Test
        @DisplayName("a blank timestamp and an absent one are rendered differently")
        void aBlankTimestampAndAnAbsentOneAreRenderedDifferently() throws Exception {
            // WHY : Alternatives Considered: resetting the substitute between the two requests, which
            //       would have discarded the first stubbing to install the second. Rejected because
            //       consecutive answers on one stubbing keep both shapes visible in one statement, and a
            //       reset additionally clears the interaction record the never-verifications elsewhere in
            //       this class depend on -- a habit that reads harmlessly here and does not stay harmless.
            when(statements.compose(any())).thenReturn(
                    new StatementDocument(heading(), List.of(lineOf(MASKED_CARD,
                            SAMPLE_TRANSACTION_ID, "GROCERIES", BLANK_PROCESSING_TIMESTAMP))),
                    new StatementDocument(heading(), List.of(lineOf(MASKED_CARD,
                            SAMPLE_TRANSACTION_ID, "GROCERIES", null))));

            String blankBody = mockMvc.perform(post(TRANSACTIONS_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            String absentBody = mockMvc.perform(post(TRANSACTIONS_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(blankBody)
                    .as("a blank value at its declared width is content and stays content")
                    .contains("\"processingTimestamp\":\"" + BLANK_PROCESSING_TIMESTAMP + "\"");
            assertThat(absentBody)
                    .as("a value that was never set is not the same answer as a blank one")
                    .contains("\"processingTimestamp\":null");
            assertThat(blankBody)
                    .as("and the two must not render alike")
                    .isNotEqualTo(absentBody);
        }
    }

    /**
     * Groups the cases that pin how money is rendered and what this surface refuses to disclose.
     *
     * <p>Assumptions: the two concerns share a group because both are properties of the SERIALISED body
     * rather than of any handler decision, and both are asserted on both describing operations for the
     * same reason -- a rule proved on one body says nothing about the other.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a
     * type declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Nested
    @DisplayName("on money rendering and disclosure")
    class OnMoneyRenderingAndDisclosure {

        // WHY : Assumptions: SC-08 on this class. TRNX-AMT PIC S9(09)V99 at app/cpy/COSTM01.CPY L29 is
        //       exact fixed point, and a JSON number is parsed into IEEE-754 binary floating point by
        //       most clients, which destroys that exactness at the boundary the user actually sees.
        /**
         * Confirms both monetary members are rendered as quoted text and never as JSON numbers.
         *
         * <p>Assumptions: the TYPE is asserted and not only the digits, because a member rendered as a
         * bare number of the same digits satisfies a value comparison while having already lost the
         * property the string form exists to keep. The two members live on two different bodies, so both
         * bodies are requested.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if either request cannot be performed
         */
        @Test
        @DisplayName("both monetary members are rendered as quoted text")
        void bothMonetaryMembersAreRenderedAsQuotedText() throws Exception {
            when(statements.describe(any())).thenReturn(heading());
            when(statements.compose(any()))
                    .thenReturn(new StatementDocument(heading(), List.of(line())));

            String summary = mockMvc.perform(post(StatementController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalAmount").isString())
                    .andExpect(jsonPath("$.totalAmount").value("-1234.56"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            String rows = mockMvc.perform(post(TRANSACTIONS_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].amount").isString())
                    .andExpect(jsonPath("$.items[0].amount").value("-1234.56"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(summary)
                    .as("the statement total travels quoted")
                    .contains("\"totalAmount\":\"-1234.56\"");
            assertThat(rows)
                    .as("and so does every row's own amount")
                    .contains("\"amount\":\"-1234.56\"");
        }

        // WHY : Assumptions: the disclosure posture of this migration narrows a primary account number to
        //       its last four positions on every response, and app/cpy/COSTM01.CPY L22 declares the whole
        //       number at sixteen -- so a body carrying the sixteen is carrying a usable card number. The
        //       verification value and the two identity documents have no member on this surface at all,
        //       and the case asserts their names are absent so that adding one is a visible change.
        /**
         * Confirms neither body carries a whole card number, a verification value or an identity number.
         *
         * <p>Assumptions: the narrowed rendering is asserted PRESENT alongside the whole number being
         * absent, because a body that carried no card member at all would satisfy the absence assertion
         * while telling a caller nothing about which card it was reading.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if either request cannot be performed
         */
        @Test
        @DisplayName("neither body carries a whole card number or an identity number")
        void neitherBodyCarriesAWholeCardNumber() throws Exception {
            when(statements.describe(any())).thenReturn(heading());
            when(statements.compose(any()))
                    .thenReturn(new StatementDocument(heading(), List.of(line())));

            String summary = mockMvc.perform(post(StatementController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cardNumber").value(MASKED_CARD))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            String rows = mockMvc.perform(post(TRANSACTIONS_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].cardNumber").value(MASKED_CARD))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(List.of(summary, rows)).allSatisfy(body -> assertThat(body)
                    .as("no whole card number, verification value or identity number may be disclosed")
                    .doesNotContain(SAMPLE_CARD)
                    .doesNotContainIgnoringCase("verificationValue")
                    .doesNotContainIgnoringCase("cardVerification")
                    .doesNotContainIgnoringCase("securityCode")
                    .doesNotContainIgnoringCase("nationalId")
                    .doesNotContainIgnoringCase("governmentIssuedId"));
        }
    }

    /**
     * Groups the cases that pin the shape a refusal takes on this surface.
     *
     * <p>Assumptions: identity arrives as validated claims and selection arrives in the request, so
     * there is no remembered turn for a refusal to depend on. Every case here therefore issues ONE
     * request and expects the whole answer from it.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a
     * type declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Nested
    @DisplayName("on the shape of a refusal")
    class OnTheShapeOfARefusal {

        // WHY : Assumptions: the baseline is pseudo-conversational and carries its continuity in a passed
        //       structure -- app/cpy/COCOM01Y.cpy L29 declares the re-entry indicator with its two named
        //       states at its L30 and L31 -- and the target keeps none of it, so the per-field array has
        //       to arrive on the FIRST request. A surface that only reported field detail on a second
        //       attempt would be carrying that indicator by another name.
        /**
         * Confirms a per-field entry arrives on the first request, naming the component at fault.
         *
         * <p>Assumptions: the collaborator is asserted never consulted, so the case proves the refusal
         * came from the boundary rather than from a statement that had already been assembled.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a per-field entry arrives on the first request")
        void aPerFieldEntryArrivesOnTheFirstRequest() throws Exception {
            mockMvc.perform(post(StatementController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cardNumber\":\"411111111111111X\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("cardNumber"))
                    .andExpect(jsonPath("$.fieldErrors[0].state").value("NOT_OK"));

            verify(statements, never()).describe(any());
        }

        // WHY : Assumptions: app/cpy/CVCRD01Y.cpy L30 attaches its 88 CCARD-RETURN-MSG-OFF VALUE
        //       LOW-VALUES to CCARD-RETURN-MSG at its L29 and NOT to CCARD-ERROR-MSG at its L28, so the
        //       baseline itself holds a never-supplied field apart from one carrying blanks. The target
        //       keeps the two apart as two field states, and collapsing them would ask a form to draw a
        //       marker against a field the caller had actually filled in.
        /**
         * Confirms a never-supplied selector and a malformed one carry different field states.
         *
         * <p>Assumptions: the never-supplied arm is raised by the collaborator rather than by the
         * boundary, because the selector record reports a value carrying no content as absent and an
         * absent value satisfies every constraint on it -- so the exactly-one-of rule is where that arm
         * is decided, and {@link ClientInputException} is how it reaches the caller.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if either request cannot be performed
         */
        @Test
        @DisplayName("a never-supplied selector and a malformed one carry different field states")
        void aNeverSuppliedSelectorAndAMalformedOneDiffer() throws Exception {
            when(statements.describe(any())).thenThrow(new ClientInputException(
                    ApiError.CODE_VALIDATION, "cardNumber", FieldValidationFlag.BLANK,
                    "exactly one of cardNumber and accountId must be supplied, not neither"));

            mockMvc.perform(post(StatementController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("cardNumber"))
                    .andExpect(jsonPath("$.fieldErrors[0].state")
                            .value(FieldValidationFlag.BLANK.name()));

            mockMvc.perform(post(StatementController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cardNumber\":\"411111111111111X\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].state")
                            .value(FieldValidationFlag.NOT_OK.name()));

            assertThat(FieldValidationFlag.BLANK.screenMarker())
                    .as("only the never-supplied state asks a form to draw a marker")
                    .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER)
                    .isNotEqualTo(FieldValidationFlag.NOT_OK.screenMarker());
        }

        // WHY : Assumptions: the machine-readable code and the human sentence are separate members of the
        //       published problem shape, and the baseline's own message fields carry no code -- 
        //       CCARD-ERROR-MSG PIC X(75) at app/cpy/CVCRD01Y.cpy L28 is seventy-five positions of
        //       sentence. Interpolating the code into the sentence would spend part of that width on a
        //       token no reader of the sentence needs.
        /**
         * Confirms the machine code sits in its own member and is not spliced into the sentence.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the machine code sits in its own member")
        void theMachineCodeSitsInItsOwnMember() throws Exception {
            when(statements.describe(any()))
                    .thenThrow(new NoSuchElementException("no cross-reference row for that card"));

            String body = mockMvc.perform(post(StatementController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_NOT_FOUND))
                    .andExpect(jsonPath("$.message").isString())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(body).contains("\"code\":\"" + ApiError.CODE_NOT_FOUND + "\"");
            assertThat(REQUEST_MAPPER.readTree(body).path("message").asString())
                    .as("the sentence a caller reads carries no machine token")
                    .doesNotContain(ApiError.CODE_NOT_FOUND);
        }

        // WHY : Assumptions: the reference reports an unrecoverable condition through a structured area
        //       rather than a dump -- app/cpy/CSMSG02Y.cpy L21 declares ABEND-DATA with ABEND-CODE X(4) at
        //       its L22, ABEND-CULPRIT X(8) at its L24, ABEND-REASON X(50) at its L26 and ABEND-MSG X(72)
        //       at its L28, and the file holds thirty-five lines in total. A refusal leaking a stack, a
        //       connection string, a vendor code or a constraint name would be reporting something that
        //       structure has no field for and that no caller can act on.
        /**
         * Confirms a not-found refusal discloses no internal detail of the failure.
         *
         * <p>Assumptions: the assertion is over the WHOLE serialised body rather than over the sentence
         * alone, because a leaked detail would most plausibly arrive in a nested member added later
         * rather than inside the sentence a reviewer already reads.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a not-found refusal discloses no internal detail")
        void aNotFoundRefusalDisclosesNoInternalDetail() throws Exception {
            when(statements.describe(any()))
                    .thenThrow(new NoSuchElementException("no cross-reference row for that card"));

            String body = mockMvc.perform(post(StatementController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isNotFound())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(body)
                    .as("no stack, no connection string, no vendor code and no constraint name")
                    .doesNotContain("com.carddemo.reporting.service")
                    .doesNotContain("java.util.NoSuchElementException")
                    .doesNotContain("at com.carddemo")
                    .doesNotContainIgnoringCase("jdbc:")
                    .doesNotContainIgnoringCase("sqlstate")
                    .doesNotContainIgnoringCase("postgres")
                    .doesNotContainIgnoringCase("constraint");
        }
    }

    /**
     * Assembles a web context carrying the DEPLOYED filter chain in front of this handler.
     *
     * <p>Alternatives Considered: substituting the chain's authorization decision and installing it by
     * hand in the standalone pipeline the rest of this class uses. Rejected because the decision is only
     * half of the guard -- the order of the rules, the session policy and the refusal renderers are the
     * other half, and a hand-installed decision would assert the half that is easiest to get right.
     * Registering the deployed configuration means the group answers for the chain a deployment runs
     * rather than for a reconstruction of it.</p>
     *
     * <p>Alternatives Considered: SC-10 on this class -- registering this module's token-decoder
     * configuration so the chain resolves a decoder the way a deployment does. Rejected because that
     * class builds its decoder eagerly from the configured location's discovery document, so registering
     * it would reach the network from a unit case, and because the location it would read is one this
     * repository must never carry a credential for. A substituted decoder is supplied instead and no case
     * here presents a token header at all.</p>
     *
     * <p>Assumptions: the two group names are passed to the deployed converter factory rather than
     * spelled here. That factory compares what it is given against its own compiled constants and
     * refuses to start on a mismatch, which is what keeps this slice's authority derivation identical to
     * a deployment's rather than merely similar to it.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a type
     * declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class SecuredSliceWiring {

        /**
         * Supplies the pinned clock the chain's refusal renderers stamp their bodies from.
         *
         * @return a clock pinned to the instant this class holds still; never {@code null}
         */
        @Bean
        Clock clock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }

        /**
         * Supplies the substituted decoder the resource-server filter would resolve a token with.
         *
         * @return a substitute for the token decoder, with no stubbing applied; never {@code null}
         */
        @Bean
        JwtDecoder jwtDecoder() {
            return Mockito.mock(JwtDecoder.class);
        }

        /**
         * Supplies the deployed claim-to-authority converter, both group names included.
         *
         * @return the converter the deployed configuration builds; never {@code null}
         */
        @Bean
        JwtAuthenticationConverter jwtAuthenticationConverter() {
            return new SecurityConfig().jwtAuthenticationConverter(
                    JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);
        }

        /**
         * Supplies the deployed chain, rule order, refusal renderers and session policy included.
         *
         * @param http the chain builder this context contributes; must not be {@code null}
         * @param converter the deployed claim-to-authority converter; must not be {@code null}
         * @param clock the clock the rendered refusal bodies read their instant from; must not be
         *     {@code null}
         * @return the chain the deployed configuration builds; never {@code null}
         * @throws Exception when the builder cannot assemble the chain, which it declares
         */
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter,
                Clock clock) throws Exception {
            return new SecurityConfig().filterChain(http, converter, clock);
        }

        /**
         * Supplies the substituted statement composer every address of this surface reads through.
         *
         * @return a substitute for the composer, with no stubbing applied; never {@code null}
         */
        @Bean
        StatementService securedStatements() {
            return Mockito.mock(StatementService.class);
        }

        /**
         * Supplies the handler under test, wired to the substitute above.
         *
         * @param statements the substituted composer; must not be {@code null}
         * @return the handler this group issues its requests against; never {@code null}
         */
        @Bean
        StatementController statementController(StatementService statements) {
            return new StatementController(statements);
        }

        /**
         * Supplies the shared advice, so a refusal raised past the chain renders its published shape.
         *
         * @param clock the clock the rendered bodies read their instant from; must not be {@code null}
         * @return the shared advice the deployed context registers; never {@code null}
         */
        @Bean
        GlobalExceptionHandler globalExceptionHandler(Clock clock) {
            return new GlobalExceptionHandler(clock);
        }
    }

    /**
     * Groups the cases that hold this surface behind the deployed group-claim guard.
     *
     * <p>Assumptions: identity arrives as validated claims and selection context arrives in the request,
     * so there is nothing for the handler to trust that a caller supplied about itself. The two group
     * authorities are the migration of the two user kinds the reference stores -- {@code app/cpy/COCOM01Y.cpy}
     * L26 declares the field and its L27 and L28 name the values {@code 'A'} and {@code 'U'} -- and the
     * chain admits either on a business address while admitting nothing else.</p>
     *
     * <p>Trade-offs: this group refreshes a web context per case, which is slower than the standalone
     * pipeline the rest of this class uses, and its converters are the framework's defaults rather than
     * this surface's own -- so no case here asserts a payload. The cost is accepted because a refusal
     * status is produced by the chain and not by the handler, so a standalone pipeline cannot observe it
     * at all; the payload assertions live in the groups above, where the money-aware converter is
     * registered.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a type
     * declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Nested
    @DisplayName("on the group-claim guard")
    class OnTheGroupClaimGuard {

        /** The refreshed context the deployed chain and the handler are built in. */
        private AnnotationConfigWebApplicationContext securedContext;

        /** The entry point every request in this group is issued through. */
        private MockMvc securedMvc;

        /** The substituted composer the admitted cases stub and the refused cases never reach. */
        private StatementService securedStatements;

        /**
         * Refreshes the secured context and installs the deployed chain in front of the dispatcher.
         *
         * <p>Assumptions: the security configurer is applied rather than the chain bean being added as a
         * plain filter, because the configurer is what lets a case establish an authentication through a
         * request post-processor instead of presenting a signed token. No case here has a token to
         * present, since the decoder is substituted.</p>
         *
         * <p>This method takes no parameter and yields no value.</p>
         */
        @BeforeEach
        void refreshSecuredSlice() {
            securedContext = new AnnotationConfigWebApplicationContext();
            securedContext.setServletContext(new MockServletContext());
            securedContext.register(SecuredSliceWiring.class);
            securedContext.refresh();

            securedStatements = securedContext.getBean(StatementService.class);
            securedMvc = MockMvcBuilders.webAppContextSetup(securedContext)
                    .apply(springSecurity())
                    .build();
        }

        /**
         * Closes the refreshed context so this group leaves no application behind it.
         *
         * <p>This method takes no parameter and yields no value.</p>
         */
        @AfterEach
        void closeSecuredSlice() {
            if (securedContext != null) {
                securedContext.close();
            }
        }

        // WHY : Assumptions: BOTH group authorities are exercised, because the chain admits either and a
        //       case naming one would pass while the other was silently withdrawn. The two are the
        //       migration of the reference's two user kinds, the values 'A' and 'U' that
        //       app/cpy/COCOM01Y.cpy L27 and L28 name, and the statement screens restrict neither.
        /**
         * Confirms either group authority reaches the rows operation.
         *
         * <p>Assumptions: the composer is stubbed to answer an empty row set, so the case turns on the
         * authorization decision rather than on a rendered population.</p>
         *
         * @param authority one of the two group authorities the deployed chain admits on a business
         *     address, supplied once per invocation
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY})
        @DisplayName("either group authority reaches the rows operation")
        void eitherGroupAuthorityReachesTheRowsOperation(String authority) throws Exception {
            when(securedStatements.compose(any()))
                    .thenReturn(new StatementDocument(headingCounting(0), List.of()));

            securedMvc.perform(post(TRANSACTIONS_ROUTE)
                            .with(jwt().authorities(new SimpleGrantedAuthority(authority)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isOk());
        }

        // WHY : Assumptions: the artifact collection is exercised as well as the rows, because the chain
        //       authorises by ADDRESS and the two are two addresses. app/csd/CARDDEMO.CSD grants the
        //       reporting transactions to every admitted user kind of app/cpy/COCOM01Y.cpy L27 and L28, so
        //       each address this surface publishes is reachable by an admitted caller.
        /**
         * Confirms an admitted caller reaches the artifact collection as well.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an admitted caller reaches the artifact collection")
        void anAdmittedCallerReachesTheArtifactCollection() throws Exception {
            when(securedStatements.collectArtifact(SELECTOR)).thenReturn(
                    new ArtifactStore.OpenArtifact(ARTIFACT_BYTES.length,
                            new ByteArrayInputStream(ARTIFACT_BYTES)));

            securedMvc.perform(get(PLAIN_TEXT_LOCATION)
                            .with(jwt().authorities(new SimpleGrantedAuthority(
                                    JwtRoleConverter.USER_AUTHORITY))))
                    .andExpect(status().isOk());
        }

        // WHY : Assumptions: the refused caller is fully AUTHENTICATED and merely holds no recognised
        //       group, which is the case a rule of "is the caller authenticated" would have admitted. The
        //       user-kind domain of app/cpy/COCOM01Y.cpy L27 and L28 holds exactly two values, so a caller
        //       carrying a third is not an admitted kind and reaches nothing.
        /**
         * Confirms an authenticated caller holding no recognised group is refused on every address.
         *
         * <p>Assumptions: all three addresses are asserted in one case because the property is the RULE
         * and not one route's wiring, and the composer is additionally asserted never consulted -- a
         * refusal rendered after a read would have already spent the query it was supposed to prevent.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if any request cannot be performed
         */
        @Test
        @DisplayName("a caller holding no recognised group is refused on every address")
        void aCallerHoldingNoRecognisedGroupIsRefused() throws Exception {
            SimpleGrantedAuthority unrecognised = new SimpleGrantedAuthority(UNRECOGNISED_AUTHORITY);

            securedMvc.perform(post(StatementController.BASE_PATH)
                            .with(jwt().authorities(unrecognised))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_FORBIDDEN));

            securedMvc.perform(post(TRANSACTIONS_ROUTE)
                            .with(jwt().authorities(unrecognised))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_FORBIDDEN));

            securedMvc.perform(get(PLAIN_TEXT_LOCATION)
                            .with(jwt().authorities(unrecognised)))
                    .andExpect(status().isForbidden());

            verify(securedStatements, never()).describe(any());
            verify(securedStatements, never()).compose(any());
            verify(securedStatements, never()).collectArtifact(any());
        }

        // WHY : Assumptions: an unauthenticated request is asserted to be CHALLENGED rather than
        //       answered, which is also what proves the chain is in front of the dispatcher at all.
        //       Without it an omitted filter would be indistinguishable from a granted dispatch in every
        //       other case in this group. The reference sends a task arriving with no passed area straight
        //       back to sign-on, so an unidentified caller never reaches a statement either.
        // WHY : Assumptions: the reference's identity travels in the passed structure
        //       app/cpy/COCOM01Y.cpy L19 declares, whose CDEMO-USER-ID PIC X(08) at its L25 and
        //       CDEMO-USER-TYPE PIC X(01) at its L26 are what the target takes from signed claims
        //       instead.
        /**
         * Confirms an unauthenticated request is challenged, proving the chain is installed.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an unauthenticated request is challenged, proving the chain is installed")
        void anUnauthenticatedRequestIsChallenged() throws Exception {
            securedMvc.perform(post(StatementController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code")
                            .value(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED));

            verify(securedStatements, never()).describe(any());
        }

        // WHY : Assumptions: statelessness is asserted on an ADMITTED request, because a refused one never
        //       reaches the handler and could be stateless for the wrong reason. The reference is
        //       pseudo-conversational and carries its continuity in a passed structure between turns --
        //       app/cpy/COCOM01Y.cpy L29 declares the re-entry indicator with its two named states at its
        //       L30 and L31 -- and the migration keeps none of it server-side, which is what lets
        //       horizontally scaled instances sit behind one address without any of them holding a
        //       caller's turn.
        /**
         * Confirms an admitted request creates no session and advertises no cookie.
         *
         * <p>Assumptions: both halves are asserted because they fail independently -- a container can
         * create a session that is never advertised, and a chain can advertise a cookie for a session
         * created elsewhere. Neither may happen on a stateless surface.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an admitted request creates no session and advertises no cookie")
        void anAdmittedRequestCreatesNoSession() throws Exception {
            when(securedStatements.compose(any()))
                    .thenReturn(new StatementDocument(headingCounting(0), List.of()));

            var result = securedMvc.perform(post(TRANSACTIONS_ROUTE)
                            .with(jwt().authorities(new SimpleGrantedAuthority(
                                    JwtRoleConverter.USER_AUTHORITY)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SELECTOR_BODY))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getRequest().getSession(false))
                    .as("the deployed chain declares a stateless session policy, so nothing may be held"
                            + " for the caller between requests")
                    .isNull();
            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                    .as("no session may be advertised either")
                    .isNull();
        }
    }
}
