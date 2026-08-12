package com.carddemo.authorization.api;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.dto.PendingAuthDetailResponse;
import com.carddemo.authorization.dto.PendingAuthListView;
import com.carddemo.authorization.dto.PendingAuthRowView;
import com.carddemo.authorization.mapper.PendingAuthDetailMapper;
import com.carddemo.authorization.mapper.PendingAuthViewMapper;
import com.carddemo.authorization.service.PendingAuthDetailService;
import com.carddemo.authorization.service.PendingAuthSummaryService;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the HTTP boundary of {@link PendingAuthController}: its four routes, the sentences it
 * publishes character for character, the projection it renders, its key-positioned envelope and its
 * refusal shape.
 *
 * <p>The controller carries across two terminal transactions. {@code CPVS}, the summary list, is defined
 * against {@code cbl/COPAUS0C.cbl}, a 1032-line program; {@code CPVD}, the detail view, against
 * {@code cbl/COPAUS1C.cbl}, a 604-line one. Every citation in this class is relative to
 * {@code app/app-authorization-ims-db2-mq} unless it names another root, and that tree is reference
 * material this migration reads and never modifies.
 *
 * <p>Assumptions: the two services are stand-ins and the mapper is REAL, so the body asserted here is the
 * body the production adapter produces. Mocking the mapper would leave every rendered value unasserted,
 * including the account-number masking that is the whole reason a controller in this context never handles
 * an entity.
 *
 * <p>Assumptions: no end-to-end captured output exists for either program, and the limit is stated rather
 * than worked around. {@code tests/README.md} L83 to L85 records that the online programs cannot run
 * end-to-end without a transaction runtime, which the runner does not have, and that only their extractable
 * field-validation logic is unit-tested. Both programs behind this controller are online programs, so
 * nothing here should be read as comparing a response against a captured baseline run. What IS directly
 * verifiable is message text and field shape, both readable in the programs and their symbolic maps, and
 * both asserted below character for character and component for component.
 *
 * <p>Assumptions: this class consumes NO recorded byte image. The thirty-eight images under
 * {@code src/test/resources/fixtures} are read by this module's segment-decoding packages, and every body
 * here is instead built from the domain constructor by the helpers at the foot of this class, so the layout
 * this class depends on is the ENTITY's constructor signature and not any file on disk. The subject is
 * stated rather than omitted because a reader auditing fixture provenance needs to be able to stop here.
 *
 * <p>Assumptions: the message band this boundary publishes into admits 78 positions, not the 75 of
 * {@code ApiError#MESSAGE_RENDERING_WIDTH}. Four declarations agree -- {@code cpy-bms/COPAU00.cpy} L390 and
 * L764, {@code cpy-bms/COPAU01.cpy} L180 and L344, each {@code PIC X(78)} -- while both programs source
 * that field from {@code WS-MESSAGE PIC X(80)}, declared at L37 of each. The narrowing happens at exactly
 * ONE site per program, {@code cbl/COPAUS0C.cbl} L692 and {@code cbl/COPAUS1C.cbl} L377, each the sole
 * {@code MOVE WS-MESSAGE TO ERRMSGO} in its program. The two-character loss is therefore a single uniform
 * funnel rather than a per-sentence property, and it is accounted for once, in
 * {@link MessageBandTest#everySentenceThisBoundaryPublishesFitsTheBandTheMapDeclares()}.
 *
 * <p>Assumptions: the diagnostic catalogue is keyed by ORIGINATING PROGRAM and not by meaning, because the
 * two programs word the same condition differently and both wordings are user-visible.
 * {@code cbl/COPAUS0C.cbl} L477 and L989 capitalise the subject as {@code AUTH Details} and
 * {@code AUTH Summary}; {@code cbl/COPAUS1C.cbl} L482 and L456 spell the same two {@code Auth Details} and
 * {@code Auth Summary}.
 *
 * <p>Assumptions: the summary screen projection's own component order is irregular in its fifth row and
 * that irregularity is NOT asserted here. {@code cpy-bms/COPAU00.cpy} declares the selection field first in
 * rows one to four -- {@code SEL0001L} L145 ahead of {@code PAMT001L} L187, and likewise at L193, L241 and
 * L289 -- but row five begins at {@code TRNID05L} L337 and ends with {@code PAMT005L} L373 FOLLOWED by
 * {@code SEL0005L} L379, with the data fields at the same offset pair, {@code PAMT005I} L378 then
 * {@code SEL0005I} L384. Because symbolic-map offsets follow declaration order, no component set derived
 * from that map may assume a uniform eight-field selection-first stride. The subject is recorded here
 * because a reader of this class needs it; the ASSERTION belongs to the mapping package, which owns
 * component order and already pins the whole irregular tail.
 *
 * <p>Trade-offs: that split is the one boundary decision most easily mistaken for a gap. Restating the
 * order assertion here would buy local self-containment and cost single ownership: two suites pinning one
 * order means a deliberate change fails twice, no reader can tell which is the authority, and whichever
 * copy is not updated goes on passing against a contract that has moved. The owner is named so the claim is
 * checkable rather than asserted: {@code mapper/PendingAuthSummaryMapperTest} pins that whole irregular tail
 * -- the fifth row's amount ahead of its marker, the message line, then the five selectors -- so this class
 * asserts instead what only it can, which is what crosses the wire.
 */
class PendingAuthControllerTest {

    /**
     * The clock the screen representation's rendered instant is read from.
     *
     * <p>Assumptions: it is FIXED, so the instant the screen body carries is an assertable value rather
     * than whatever the run happened to observe. The controller reads that instant server-side by design,
     * because it is evidence of when the response was produced, so a test of the response needs it pinned.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-05T10:45:35Z"), ZoneOffset.UTC);

    /**
     * The clock the shared advice timestamps a refusal from.
     *
     * <p>Assumptions: it is a SECOND fixed clock rather than the one above, so a body cannot pass an
     * assertion about the screen instant by accidentally carrying the advice instant, or the reverse.
     */
    private static final Clock ADVICE_CLOCK =
            Clock.fixed(Instant.parse("2026-08-06T09:20:00Z"), ZoneOffset.UTC);

    /**
     * The authenticated subject the controller reads the sealed values' binding name from.
     *
     * <p>Assumptions: a standalone server runs no security chain, so the principal is supplied on the
     * request builder and its name is what the handler forwards to the service.
     */
    private static final String SUBJECT = "authorization-operator";

    /**
     * The principal every request in this class is performed as.
     */
    private static final Principal PRINCIPAL = () -> SUBJECT;

    /** The collection search route the published contract names. */
    private static final String LIST_ROUTE = "/api/v1/authorizations/search";

    /** The member route the published contract names, with a placeholder for the sealed selector. */
    private static final String READ_ROUTE = "/api/v1/authorizations/{key}";

    /** The screen-shaped member route the published contract names. */
    private static final String SCREEN_ROUTE = "/api/v1/authorizations/{key}/screen";

    /** The forward paging move the published contract names on the member. */
    private static final String NEXT_ROUTE = "/api/v1/authorizations/{key}/next";

    /**
     * The declared width of the screen message line.
     *
     * <p>Assumptions: 78 is written here as a literal rather than read from the response record, whose own
     * width is private, because a test that reached for a value in order to compare it against itself would
     * assert nothing. The authority is the map: {@code cpy-bms/COPAU00.cpy} L764 and
     * {@code cpy-bms/COPAU01.cpy} L344 each declare {@code 02  ERRMSGO  PIC X(78)}.
     */
    private static final int SCREEN_MESSAGE_WIDTH = 78;

    /**
     * The width of the work field both programs compose a sentence in before the funnel narrows it.
     *
     * <p>Assumptions: {@code WS-MESSAGE PIC X(80) VALUE SPACES} is declared at L37 of BOTH programs, and
     * the two declarations are byte-identical, which is why one constant serves both.
     */
    private static final int WORK_MESSAGE_WIDTH = 80;

    /** The account every request in this class scopes itself to, as eleven digits. */
    private static final String ACCOUNT_ID_DIGITS = "00000000011";

    /** The account as the stored key holds it. */
    private static final long ACCOUNT_ID = 11L;

    /** The customer the summary row names. */
    private static final long CUSTOMER_ID = 11L;

    /** The date component of the stored key. */
    private static final int AUTH_DATE = 26215;

    /** The time component of the stored key, positionally 09:16:44 and 902 milliseconds. */
    private static final int AUTH_TIME = 9_16_44_902;

    /** The primary account number the row carries, which every body must publish masked. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The masked rendering of that number, twelve mask characters and the last four digits. */
    private static final String MASKED_CARD_NUMBER = "************1111";

    /** The stored originating date, as the segment holds it, year first. */
    private static final String STORED_ORIG_DATE = "260803";

    /** The stored originating time, as the segment holds it, hours first. */
    private static final String STORED_ORIG_TIME = "091644";

    /** The stored response code the reference tests for approval. */
    private static final String RESPONSE_CODE_APPROVED = "00";

    /** A stored response code that is not the approved one, so the derivation must decline it. */
    private static final String RESPONSE_CODE_DECLINED = "05";

    /** The stored processing code, which the six-position screen field carries. */
    private static final String PROCESSING_CODE = "003000";

    /** The stored authorization identification code, which the screen projection omits. */
    private static final String AUTH_ID_CODE = "AUTH01";

    /** The eight-character fraud report date the composed fraud mark carries. */
    private static final String FRAUD_REPORT_DATE = "20260803";

    /**
     * The approval reason the shared row carries, which is itself a display-table entry.
     *
     * <p>Assumptions: {@code '0000'} is the FIRST entry of the reference display table at
     * {@code cbl/COPAUS1C.cbl} L58, so the default row exercises a table hit rather than the no-entry path.
     * The reference runs its search on every authorization, approved ones included.
     */
    private static final String APPROVED_REASON = "0000";

    /**
     * The reason the producing ladder writes when it declines for a reason it does not enumerate.
     *
     * <p>Assumptions: {@code '9000'} is BOTH a reachable outcome and a table entry. L715 and L716 of
     * {@code cbl/COPAUA0C.cbl} write it on the ladder's {@code WHEN OTHER} arm and L67 of
     * {@code cbl/COPAUS1C.cbl} carries its description, which is what makes it a different case from a code
     * the table does not hold.
     */
    private static final String CATCH_ALL_REASON = "9000";

    /**
     * A stored response reason no reference program writes and the display table does not hold.
     *
     * <p>Assumptions: the value sits outside the table on purpose, so a read takes the no-entry path the
     * reference reaches at L319 to L323. It is deliberately NOT {@code '4400'} or {@code '5300'}: those two
     * ARE table entries, held at L63 and L66 while no program writes them, so either would take the hit
     * path and assert the opposite of what the case is for.
     */
    private static final String UNTABLED_REASON = "7777";

    /** The account-scope refusal the summary program writes when the field arrives empty, L269. */
    private static final String S0C_L269_ENTER_ACCOUNT = "Please enter Acct Id...";

    /**
     * The account-scope refusal the summary program writes when the field is not all digits, L278.
     *
     * <p>Assumptions: the bytes are {@code Numeric}, then ONE space, then the ellipsis. The sibling refusal
     * above has no space in that position, so a comparison that normalised whitespace would let the two be
     * exchanged without failing. The edit is reached only once the empty edit has passed, which
     * {@code cbl/COPAUS0C.cbl} L273 establishes with {@code IF ACCTIDI OF COPAU0AI IS NOT NUMERIC}.
     */
    private static final String S0C_L278_NUMERIC_ACCOUNT = "Acct Id must be Numeric ...";

    /** The backward navigation boundary the summary program writes, L381 continuing onto L382. */
    private static final String S0C_L381_TOP_OF_PAGE = "You are already at the top of the page...";

    /** The forward navigation boundary the summary program writes, L409 continuing onto L410. */
    private static final String S0C_L409_BOTTOM_OF_PAGE = "You are already at the bottom of the page...";

    /** The summary program's diagnostic for a failed detail retrieval, L477, subject in upper case. */
    private static final String S0C_L477_READ_DETAILS =
            " System error while reading AUTH Details: Code:";

    /**
     * The summary program's diagnostic for a failed repositioning, L510.
     *
     * <p>Assumptions: the abbreviation {@code repos.} carries a PERIOD, which makes this a genuinely third
     * member of the detail-diagnostic family rather than a restatement of L477. The repositioning path it
     * belongs to is {@code REPOSITION-AUTHORIZATIONS} at L488, which the forward move reaches at L397.
     */
    private static final String S0C_L510_REPOSITION_DETAILS =
            " System error while repos. AUTH Details: Code:";

    /** The summary program's diagnostic for a failed summary retrieval, L989, subject in upper case. */
    private static final String S0C_L989_READ_SUMMARY =
            " System error while reading AUTH Summary: Code:";

    /** The summary program's diagnostic for a failed resource scheduling, L1023. */
    private static final String S0C_L1023_SCHEDULE_PSB =
            " System error while scheduling PSB: Code:";

    /**
     * The detail program's navigation boundary, L283 continuing onto L284.
     *
     * <p>Assumptions: the wording is NOT the {@code You are already at ...} phrasing the summary program
     * uses at L381 and L409. Three navigation boundaries exist across the two programs and all three are
     * separate strings, so an assertion that accepted any of them would let them be merged into one.
     */
    private static final String S1C_L283_LAST_AUTHORIZATION = "Already at the last Authorization...";

    /** The detail program's diagnostic for a failed summary retrieval, L456, subject in mixed case. */
    private static final String S1C_L456_READ_SUMMARY =
            " System error while reading Auth Summary: Code:";

    /** The detail program's diagnostic for a failed detail retrieval, L482, subject in mixed case. */
    private static final String S1C_L482_READ_DETAILS =
            " System error while reading Auth Details: Code:";

    /**
     * The detail program's diagnostic for a failed forward read, L511.
     *
     * <p>Assumptions: this sentence belongs to NEITHER the summary family nor the detail family. It names
     * the navigation operation rather than a segment, so a catalogue organised by segment would have no
     * place to put it and would most likely fold it into the detail family, losing a byte-distinct string.
     */
    private static final String S1C_L511_READ_NEXT = " System error while reading next Auth: Code:";

    /** The detail program's diagnostic for a failed resource scheduling, L596. */
    private static final String S1C_L596_SCHEDULE_PSB =
            " System error while scheduling PSB: Code:";

    /** The trailing fragment every retrieval diagnostic in both programs ends with. */
    private static final String DIAGNOSTIC_CODE_SUFFIX = ": Code:";

    /**
     * The trailing fragment every file-access diagnostic ends with.
     *
     * <p>Assumptions: the separator before {@code Resp} is a PERIOD where the retrieval family uses a
     * colon. The two families are told apart by that one byte, so normalising either onto the other would
     * silently rewrite one of them.
     */
    private static final String FILE_DIAGNOSTIC_RESPONSE_SUFFIX = ". Resp:";

    /** The further trailing fragment all three file-access diagnostics share, with no period. */
    private static final String FILE_DIAGNOSTIC_REASON_SUFFIX = " Reas:";

    /** The leading label the cross-reference and account file diagnostics are composed with, L852 and L902. */
    private static final String FILE_DIAGNOSTIC_ACCOUNT_LABEL = "Account:";

    /** The leading label the customer file diagnostic is composed with instead, L953. */
    private static final String FILE_DIAGNOSTIC_CUSTOMER_LABEL = "Customer:";

    /** The middle fragment of the cross-reference file diagnostic, L854. */
    private static final String S0C_L854_XREF_FILE =
            " System error while reading XREF file. Resp:";

    /** The middle fragment of the account file diagnostic, L904. */
    private static final String S0C_L904_ACCT_FILE =
            " System error while reading ACCT file. Resp:";

    /** The middle fragment of the customer file diagnostic, L955. */
    private static final String S0C_L955_CUST_FILE =
            " System error while reading CUST file. Resp:";

    /** The eleven-position rendering of the account the two account-labelled composites splice in. */
    private static final String COMPOSITE_ACCOUNT_VALUE = "00000000011";

    /** The nine-position rendering of the customer the customer-labelled composite splices in instead. */
    private static final String COMPOSITE_CUSTOMER_VALUE = "000000011";

    /** The two-position response code the file composites splice in. */
    private static final String COMPOSITE_RESPONSE_VALUE = "13";

    /** The two-position reason code the file composites splice in. */
    private static final String COMPOSITE_REASON_VALUE = "00";

    /** The list service stand-in. */
    private PendingAuthSummaryService summaries;

    /** The single-row read service stand-in. */
    private PendingAuthDetailService detail;

    /** The real mapper, used here only to build the views the stand-ins return. */
    private PendingAuthViewMapper mapper;

    /** The standalone server under test. */
    private MockMvc mockMvc;

    /**
     * The one sealed selector this test addresses the shared authorization row with.
     *
     * <p>Assumptions: it is minted once per test rather than per use, because sealing is not idempotent --
     * see {@link #selectorForSharedRow()} for why a second mint would silently unmatch every stand-in.
     */
    private String sharedSelector;

    /**
     * Assembles the standalone server over the two service stand-ins, the shared advice and the money
     * codec.
     *
     * <p>Trade-offs: a STANDALONE server is assembled rather than a sliced web-layer context, and the
     * reason is specific to this module rather than a preference. Its {@code SecurityConfig} builds a token
     * decoder from an issuer LOCATION, which resolves that issuer's discovery document eagerly at bean
     * construction, so any context including that configuration reaches the network from a unit test --
     * against a host the test profile points somewhere unresolvable on purpose. The cost accepted is real
     * and is stated rather than glossed: no filter chain is installed, so nothing here can demonstrate that
     * a route is unreachable WITHOUT the right authority, and the principal is supplied on the request
     * builder instead. That matrix is asserted against the installed decision object in
     * {@code config/SecurityConfigTest}, and the conversion of group claims to authorities that
     * {@code com.carddemo.common.security.JwtRoleConverter} performs cannot run here at all. A second
     * consequence follows and is named for the same reason: no context loads, so
     * {@code src/test/resources/application-test.yml} does not apply to this class and no value asserted
     * below comes from it.
     *
     * <p>Trade-offs: the shared advice is INSTALLED rather than left out, which widens the slice past a
     * strictly isolated controller. The alternative was to assert only the status the controller itself
     * declares and leave the problem body unasserted. It is rejected because the advice lives in another
     * module, {@code services/common-lib}, and is the sole producer of the per-field array, the refusal code
     * and the message text this contract publishes; without it every sentence assertion below would be
     * unreachable and the two verbatim account-scope refusals would have no test anywhere. What is bought
     * is that the real mapping is asserted; what is paid is that a failure here can implicate the advice as
     * well as the controller, which the assertion messages name explicitly so the two stay tellable apart.
     *
     * <p>Assumptions: the money codec is registered on the converter this server binds bodies with, because
     * transformation rule T3 requires every amount to reach a client as a JSON STRING. A default converter
     * would emit a bare number, which a client parses into IEEE-754 binary floating point, and the money
     * assertions below would then be asserting the wrong contract rather than failing.
     */
    @BeforeEach
    void setUp() {
        this.summaries = mock(PendingAuthSummaryService.class);
        this.detail = mock(PendingAuthDetailService.class);

        // WHY : Assumptions: the sealing key is an obviously synthetic repeated byte rather than a
        //       generated one, which keeps a credential out of this file entirely -- it carries no secret
        //       because nothing outside this class can redeem a token sealed with it. Note what a stable
        //       KEY does and does not buy: the mapper can redeem any token it minted, but the token BYTES
        //       still differ on every mint, so a stable key is not reproducible output. That is why the
        //       one selector these cases share is minted here, once, rather than on each use; see
        //       selectorForSharedRow for what a second mint would silently break.
        byte[] keyMaterial = new byte[CursorToken.MIN_KEY_LENGTH];
        Arrays.fill(keyMaterial, (byte) 0x3C);
        this.mapper = new PendingAuthViewMapper(new CursorToken(keyMaterial, Duration.ofMinutes(5)));
        this.sharedSelector = this.mapper.toRowView(row(), SUBJECT).key();

        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new PendingAuthController(this.summaries, this.detail, FIXED_CLOCK))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(
                        JsonMapper.builder().addModule(new MoneyModule()).build()))
                .setControllerAdvice(new GlobalExceptionHandler(ADVICE_CLOCK))
                .build();
    }

    /**
     * The sentences these two programs put in front of an operator, carried across byte for byte.
     *
     * <p>Transformation rule T8 requires a user-visible string to survive the migration character for
     * character, and this group is where that requirement is discharged for the sixteen message sites the
     * two programs behind this controller own. Five are field edits or navigation boundaries and are
     * asserted on the wire, because a client reads them; eight are retrieval diagnostics and three are
     * file-access composites, and those are asserted as transcriptions, because the target deliberately does
     * not publish them -- see
     * {@link #noDiagnosticCodeReachesAUserVisibleMessageAtThisBoundary()} for what it publishes instead.
     *
     * <p>Alternatives Considered: one canonical sentence per MEANING, so that a single catalogue entry
     * served both programs' wording of the same condition. It is rejected on measurement.
     * {@code cbl/COPAUS0C.cbl} L477 and L989 write {@code AUTH Details} and {@code AUTH Summary} in upper
     * case while {@code cbl/COPAUS1C.cbl} L482 and L456 write {@code Auth Details} and {@code Auth Summary}
     * in mixed case, so merging by meaning would have kept one of each pair and silently rewritten the
     * other -- two byte-distinct strings an operator can actually see reduced to one. A third variant
     * exists, the {@code repos.} form at L510, and one sentence belongs to neither family, the forward-read
     * form at L511. That the two programs DO agree byte for byte on one sentence, the scheduling diagnostic
     * at L1023 and L596, is what makes the disagreement on the others a property of the source rather than
     * an accident of transcription.
     *
     * <p>Trade-offs: the three file-access composites are asserted WHOLE -- label, spliced value, middle
     * fragment and both trailing fragments -- rather than by testing that a rendered line contains the
     * middle fragment. Containment on the middle alone is cheaper to write and would pass while the leading
     * label was wrong, absent, or exchanged between the account-labelled and customer-labelled forms, which
     * is exactly the difference {@code cbl/COPAUS0C.cbl} L953 introduces when it labels the customer form
     * {@code 'Customer:'} and splices {@code WS-CARD-RID-CUST-ID-X} where its two siblings at L852 and L902
     * label {@code 'Account:'} and splice {@code WS-CARD-RID-ACCT-ID-X}. The cost accepted is that each
     * expectation is longer and names a spliced value the reference derives at run time; the compensation is
     * that no part of a composite is left unasserted.
     */
    @Nested
    @DisplayName("the message text these two programs own")
    class MessageLiteralsTest {

        /**
         * An empty account scope is refused with the summary program's own sentence, keyed as never
         * supplied.
         *
         * <p>Assumptions: the sentence is asserted INCLUDING the absence of a space before its ellipsis,
         * and it is asserted twice -- once as the problem body's message and once as the message on the
         * per-field entry -- because a client may read either and the two could drift apart without a test
         * that pins both. It is carried from {@code cbl/COPAUS0C.cbl} L268 to L269.
         *
         * <p>Assumptions: the state is the never-supplied one rather than the not-acceptable one, because
         * the reference highlight template does something EXTRA for a never-supplied value: it additionally
         * writes a marker into the field. A form needs the two told apart to know whether to draw it.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an empty account scope is refused with the reference sentence and the blank state")
        void anEmptyAccountScopeIsRefusedWithTheReferenceSentence() throws Exception {
            PendingAuthControllerTest.this.mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody("", null))
                            .principal(PRINCIPAL))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                    .andExpect(jsonPath("$.message").value(S0C_L269_ENTER_ACCOUNT))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("accountId"))
                    .andExpect(jsonPath("$.fieldErrors[0].state")
                            .value(FieldValidationFlag.BLANK.name()))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value(S0C_L269_ENTER_ACCOUNT));

            verifyNoInteractions(PendingAuthControllerTest.this.summaries);
        }

        /**
         * An all-blank account scope is refused ONCE, in the never-supplied state, exactly as an empty one.
         *
         * <p>Purpose: eleven spaces is what a fixed-width screen field sends when the operator types
         * nothing into it and the client forwards the field untouched, so it is the shape a form most often
         * produces for "no account given". It has to be answered as the same refusal as an empty string,
         * because it means the same thing.</p>
         *
         * <p>⚠️ Refactoring Rationale: the observed behaviour was TWO per-field entries for one field --
         * the presence constraint refused the value as blank, and the domain pattern refused it a second
         * time because its widening admitted only the EMPTY string and not an all-whitespace one. Both
         * entries were keyed {@code accountId}, so a form binding an error to a control by field name had
         * two messages for one control and no rule for which to draw. The length assertion below is the
         * substance of this case: pinning the first entry alone -- which the sibling cases above do -- would
         * have passed against the defect, because the first entry was always the right one.</p>
         *
         * <p>Assumptions: the state asserted is the never-supplied one rather than the not-acceptable one,
         * because the value IS absent as far as the reference edit is concerned; the reference tests blank
         * before numeric and never reaches the numeric edit for a value it has already refused as blank.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an all-blank account scope is refused once, in the same state as an empty one")
        void anAllBlankAccountScopeIsRefusedExactlyOnce() throws Exception {
            PendingAuthControllerTest.this.mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody("           ", null))
                            .principal(PRINCIPAL))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                    .andExpect(jsonPath("$.message").value(S0C_L269_ENTER_ACCOUNT))
                    .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("accountId"))
                    .andExpect(jsonPath("$.fieldErrors[0].state")
                            .value(FieldValidationFlag.BLANK.name()))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value(S0C_L269_ENTER_ACCOUNT));

            verifyNoInteractions(PendingAuthControllerTest.this.summaries);
        }

        /**
         * A non-digit account scope is refused with the summary program's second sentence.
         *
         * <p>Assumptions: the single space before the ellipsis is part of the expectation, and it is the
         * only thing separating this sentence from its sibling in shape. The reference reaches this edit
         * only once the empty edit has passed, at {@code cbl/COPAUS0C.cbl} L273, and the sentence itself
         * comes from L277 to L278.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a non-digit account scope is refused with the reference numeric sentence")
        void aNonDigitAccountScopeIsRefusedWithTheReferenceSentence() throws Exception {
            PendingAuthControllerTest.this.mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody("0000000001X", null))
                            .principal(PRINCIPAL))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                    .andExpect(jsonPath("$.message").value(S0C_L278_NUMERIC_ACCOUNT))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("accountId"))
                    .andExpect(jsonPath("$.fieldErrors[0].state")
                            .value(FieldValidationFlag.NOT_OK.name()))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value(S0C_L278_NUMERIC_ACCOUNT));

            verifyNoInteractions(PendingAuthControllerTest.this.summaries);
        }

        /**
         * The two list navigation boundaries reach a client on the message line, each from its own site.
         *
         * <p>Assumptions: the two are driven as SEPARATE requests against separate expectations, because
         * they are two strings in the reference and not one parameterised string.
         * {@code cbl/COPAUS0C.cbl} L381 writes the backward sentence and L409 the forward one. Neither
         * carries a leading space, unlike every retrieval diagnostic in the same program, so the comparison
         * is character for character rather than trimmed.
         *
         * @throws Exception if either request cannot be performed
         */
        @Test
        @DisplayName("the top and bottom list boundaries publish their own reference sentences")
        void theTwoListBoundarySentencesArePublishedDistinctly() throws Exception {
            String cursor = PendingAuthControllerTest.this.mapper.toRowView(row(), SUBJECT).key();
            when(PendingAuthControllerTest.this.summaries.list(ACCOUNT_ID, cursor,
                    PendingAuthSummaryService.DIRECTION_PREVIOUS, SUBJECT))
                    .thenReturn(messageListView(PendingAuthListView.MESSAGE_TOP_OF_PAGE));
            when(PendingAuthControllerTest.this.summaries.list(ACCOUNT_ID, cursor,
                    PendingAuthSummaryService.DIRECTION_NEXT, SUBJECT))
                    .thenReturn(messageListView(PendingAuthListView.MESSAGE_BOTTOM_OF_PAGE));

            PendingAuthControllerTest.this.mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(pagingBody(cursor, PendingAuthSummaryService.DIRECTION_PREVIOUS))
                            .principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.screenMessage").value(S0C_L381_TOP_OF_PAGE));

            PendingAuthControllerTest.this.mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(pagingBody(cursor, PendingAuthSummaryService.DIRECTION_NEXT))
                            .principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.screenMessage").value(S0C_L409_BOTTOM_OF_PAGE));
        }

        /**
         * The forward move on the member route answers with the detail program's own boundary sentence.
         *
         * <p>Assumptions: the sentence is the detail screen's third boundary string, at
         * {@code cbl/COPAUS1C.cbl} L283, and it is compared against that literal rather than against either
         * list sentence. All three are separate strings in the reference, so an expectation that accepted
         * any of the three would let them be merged into one.
         *
         * <p>Assumptions: the status is 200 and not 404, because "nothing follows" is a successful answer
         * to the forward move. The reference writes the sentence on the screen and leaves the displayed
         * authorization in place rather than refusing the request, and a 404 would be indistinguishable from
         * a selector that named nothing at all.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the forward move answers 200 with the detail screen's own boundary sentence")
        void theForwardMoveAnswersEndOfDataWithTheDetailBoundarySentence() throws Exception {
            String selector = PendingAuthControllerTest.this.mapper.toRowView(row(), SUBJECT).key();
            when(PendingAuthControllerTest.this.detail.readNext(selector, SUBJECT)).thenReturn(
                    new PendingAuthDetailService.NextAuthorization(null, true,
                            PendingAuthDetailService.LAST_AUTHORIZATION_REACHED));

            PendingAuthControllerTest.this.mockMvc.perform(get(NEXT_ROUTE, selector).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.endOfData").value(true))
                    .andExpect(jsonPath("$.authorization").doesNotExist())
                    .andExpect(jsonPath("$.message").value(S1C_L283_LAST_AUTHORIZATION));
        }

        /**
         * The three navigation sentences this module publishes are the three the two programs write.
         *
         * <p>Assumptions: the published catalogue is compared against a transcription taken independently
         * from the programs, which is what makes this more than a restatement. Two sources have to agree:
         * {@code cbl/COPAUS0C.cbl} L381 and L409 and {@code cbl/COPAUS1C.cbl} L283 on one side, and the
         * three constants {@code PendingAuthListView} publishes on the other. A single-sided assertion
         * could not detect a catalogue that had drifted from the source it was transcribed from.
         */
        @Test
        @DisplayName("the published navigation catalogue matches the transcription, sentence for sentence")
        void thePublishedNavigationCatalogueMatchesTheTranscription() {
            assertThat(PendingAuthListView.BOUNDARY_MESSAGES)
                    .as("the three navigation sentences of COPAUS0C L381, L409 and COPAUS1C L283")
                    .containsExactly(S0C_L381_TOP_OF_PAGE, S0C_L409_BOTTOM_OF_PAGE,
                            S1C_L283_LAST_AUTHORIZATION);
            assertThat(PendingAuthDetailService.LAST_AUTHORIZATION_REACHED)
                    .as("the forward-move boundary the detail service publishes, from COPAUS1C L283")
                    .isEqualTo(S1C_L283_LAST_AUTHORIZATION);
        }

        /**
         * The catalogue is keyed by originating program, so both wordings of a shared condition survive.
         *
         * <p>Assumptions: thirteen single-literal message sites exist across the two programs and they carry
         * TWELVE distinct byte values, the one duplicate being the scheduling diagnostic that
         * {@code cbl/COPAUS0C.cbl} L1023 and {@code cbl/COPAUS1C.cbl} L596 write identically. The two counts
         * are asserted together on purpose: keying by meaning would collapse the two detail wordings and the
         * two summary wordings, taking the distinct count from twelve to ten, so the pair of numbers is what
         * makes the merge detectable rather than either number alone.
         */
        @Test
        @DisplayName("thirteen message sites carry twelve distinct values, and the two wordings both survive")
        void theCatalogueIsKeyedByProgramSoBothWordingsSurvive() {
            Map<String, String> catalogue = messageCatalogue();

            assertThat(catalogue)
                    .as("the message sites COPAUS0C and COPAUS1C declare, keyed by program and line")
                    .hasSize(13);
            assertThat(catalogue.values().stream().distinct().toList())
                    .as("twelve distinct values, the scheduling sentence being shared byte for byte")
                    .hasSize(12);
            assertThat(S0C_L1023_SCHEDULE_PSB)
                    .as("COPAUS0C L1023 and COPAUS1C L596 are the one pair that agrees byte for byte")
                    .isEqualTo(S1C_L596_SCHEDULE_PSB);

            assertThat(S0C_L477_READ_DETAILS)
                    .as("COPAUS0C L477 and COPAUS1C L482 differ only in the case of their subject")
                    .isEqualToIgnoringCase(S1C_L482_READ_DETAILS)
                    .isNotEqualTo(S1C_L482_READ_DETAILS);
            assertThat(S0C_L989_READ_SUMMARY)
                    .as("COPAUS0C L989 and COPAUS1C L456 differ only in the case of their subject")
                    .isEqualToIgnoringCase(S1C_L456_READ_SUMMARY)
                    .isNotEqualTo(S1C_L456_READ_SUMMARY);

            assertThat(S0C_L510_REPOSITION_DETAILS)
                    .as("COPAUS0C L510 is a third detail wording and keeps the period on its abbreviation")
                    .contains("repos.")
                    .isNotEqualTo(S0C_L477_READ_DETAILS);
            assertThat(S1C_L511_READ_NEXT)
                    .as("COPAUS1C L511 names the navigation operation, so it joins neither family")
                    .doesNotContain("Details")
                    .doesNotContain("Summary");
        }

        /**
         * Each diagnostic family keeps its own leading space and its own trailing fragment.
         *
         * <p>Assumptions: every one of the eight retrieval diagnostics begins with exactly one space and
         * ends with a colon before {@code Code}, while the five field edits and navigation boundaries begin
         * with no space at all. The leading space is what visually separates a diagnostic from the field it
         * follows on a monospaced line, so it is part of the value rather than padding.
         *
         * <p>Assumptions: the file-access family ends with a PERIOD before {@code Resp} where the retrieval
         * family ends with a colon before {@code Code}. One byte separates the two families, which is why
         * both suffixes are asserted rather than a shared prefix.
         */
        @Test
        @DisplayName("the retrieval and file diagnostic families keep their own leading and trailing bytes")
        void theDiagnosticFamiliesKeepTheirOwnLeadingAndTrailingBytes() {
            for (String diagnostic : retrievalDiagnostics()) {
                assertThat(diagnostic)
                        .as("a retrieval diagnostic leads with one space and closes with its code fragment")
                        .startsWith(" ")
                        .doesNotStartWith("  ")
                        .endsWith(DIAGNOSTIC_CODE_SUFFIX);
            }
            for (String sentence : operatorSentences()) {
                assertThat(sentence)
                        .as("a field edit or navigation boundary carries no leading space")
                        .doesNotStartWith(" ");
            }
            for (String middle : fileDiagnosticMiddles()) {
                assertThat(middle)
                        .as("a file diagnostic closes with a period before its response fragment")
                        .startsWith(" ")
                        .endsWith(FILE_DIAGNOSTIC_RESPONSE_SUFFIX)
                        .doesNotContain(DIAGNOSTIC_CODE_SUFFIX);
            }
            assertThat(FILE_DIAGNOSTIC_REASON_SUFFIX)
                    .as("the further shared fragment of L855, L905 and L956 carries no period")
                    .doesNotContain(".");
        }

        /**
         * The three file-access diagnostics are whole composites, and two of their three parts vary.
         *
         * <p>Assumptions: each is built by ONE reference statement out of a leading label, a spliced
         * identifier, a middle fragment and two trailing fragments -- {@code cbl/COPAUS0C.cbl} L851 to L855
         * for the cross-reference form, L901 to L905 for the account form and L952 to L956 for the customer
         * form. Asserting the whole composite is what detects a label or a spliced identifier going astray;
         * the customer form is the reason it matters, because it alone labels {@code 'Customer:'} and
         * splices the customer identifier where the other two label {@code 'Account:'} and splice the
         * account identifier.
         */
        @Test
        @DisplayName("each file diagnostic composes its own label, value, middle and shared tail")
        void eachFileDiagnosticComposesItsOwnLabelValueMiddleAndSharedTail() {
            String sharedTail = COMPOSITE_RESPONSE_VALUE + FILE_DIAGNOSTIC_REASON_SUFFIX
                    + COMPOSITE_REASON_VALUE;

            assertThat(FILE_DIAGNOSTIC_ACCOUNT_LABEL + COMPOSITE_ACCOUNT_VALUE + S0C_L854_XREF_FILE
                    + sharedTail)
                    .as("the cross-reference composite of COPAUS0C L851 to L855, whole")
                    .isEqualTo("Account:00000000011 System error while reading XREF file. Resp:13 Reas:00");
            assertThat(FILE_DIAGNOSTIC_ACCOUNT_LABEL + COMPOSITE_ACCOUNT_VALUE + S0C_L904_ACCT_FILE
                    + sharedTail)
                    .as("the account composite of COPAUS0C L901 to L905, whole")
                    .isEqualTo("Account:00000000011 System error while reading ACCT file. Resp:13 Reas:00");
            assertThat(FILE_DIAGNOSTIC_CUSTOMER_LABEL + COMPOSITE_CUSTOMER_VALUE + S0C_L955_CUST_FILE
                    + sharedTail)
                    .as("the customer composite of COPAUS0C L952 to L956, with its own label and value")
                    .isEqualTo("Customer:000000011 System error while reading CUST file. Resp:13 Reas:00");

            assertThat(FILE_DIAGNOSTIC_CUSTOMER_LABEL)
                    .as("L953 labels the customer form differently from L852 and L902")
                    .isNotEqualTo(FILE_DIAGNOSTIC_ACCOUNT_LABEL);
            assertThat(fileDiagnosticMiddles())
                    .as("the three middle fragments are three distinct strings")
                    .doesNotHaveDuplicates()
                    .hasSize(3);
        }

        /**
         * A failed read answers a sentence an operator can act on and carries no machine diagnostic code.
         *
         * <p>Refactoring Rationale: this is the one place a documented divergence from the reference is
         * asserted rather than described, and the reference behaviour it replaces is specific. All eight
         * retrieval diagnostics splice a machine status directly into the sentence an operator reads -- the
         * {@code STRING} at {@code cbl/COPAUS0C.cbl} L476 to L481 concatenates
         * {@code IMS-RETURN-CODE} onto its text, and the three file composites concatenate a response and a
         * reason code the same way at L855, L905 and L956. The Java implements a stable sentence plus a
         * correlation identifier instead: the operator gets something to quote and the machine status stays
         * in the log where the diagnosis happens. The divergence is documented rather than silent, and the
         * reference tree is read and never modified.
         *
         * <p>Assumptions: the stand-in is made to fail with a message that IS one of those composites, so
         * the case would fail if the boundary echoed a failure message through. Asserting only that the body
         * lacks the fragments would pass against a handler that echoed a different composite instead.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a failed read publishes a stable sentence and no machine diagnostic code")
        void noDiagnosticCodeReachesAUserVisibleMessageAtThisBoundary() throws Exception {
            when(PendingAuthControllerTest.this.detail.read(any(), any()))
                    .thenThrow(new IllegalStateException(
                            S1C_L482_READ_DETAILS + "AI" + FILE_DIAGNOSTIC_REASON_SUFFIX + "0000000C"));

            String body = PendingAuthControllerTest.this.mockMvc.perform(
                            get(READ_ROUTE, selectorForSharedRow()).principal(PRINCIPAL))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_INTERNAL))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(body)
                    .as("no part of a reference diagnostic composite reaches a response body")
                    .doesNotContain(DIAGNOSTIC_CODE_SUFFIX)
                    .doesNotContain(FILE_DIAGNOSTIC_RESPONSE_SUFFIX)
                    .doesNotContain(FILE_DIAGNOSTIC_REASON_SUFFIX)
                    .doesNotContain("System error while");
            assertThat(body)
                    .as("the body offers a correlation identifier as the thing to quote instead")
                    .contains("correlationId");
        }
    }

    /**
     * The width of the line these two screens say things on, and the two characters the funnel drops.
     *
     * <p>Assumptions: this boundary's band is 78 positions and the house error line's is 75, and the two are
     * different contracts rather than one rounded differently. Five message-width regimes exist across this
     * codebase and {@code ApiError} publishes three of them itself, so conflating any two is a live hazard
     * rather than a hypothetical one.
     */
    @Nested
    @DisplayName("the width of the screen message line")
    class MessageBandTest {

        /**
         * The band is 78 positions, and the house 75-position error line is a different contract.
         *
         * <p>Assumptions: 78 comes from the map and 75 from a copybook these two programs never include, so
         * the inequality is asserted rather than assumed. The three widths {@code ApiError} publishes are
         * named alongside it so a reader can see that 78 is not among them and cannot be reached by picking
         * a different one.
         */
        @Test
        @DisplayName("the band is 78 positions and none of the shared widths equals it")
        void theBandIsSeventyEightAndTheSharedWidthsAreDifferentContracts() {
            assertThat(SCREEN_MESSAGE_WIDTH)
                    .as("COPAU00.cpy L764 and COPAU01.cpy L344 each declare ERRMSGO PIC X(78)")
                    .isEqualTo(78)
                    .isNotEqualTo(ApiError.MESSAGE_RENDERING_WIDTH);
            assertThat(List.of(ApiError.MESSAGE_RENDERING_WIDTH, ApiError.COMPACT_MESSAGE_WIDTH,
                    ApiError.ABEND_MESSAGE_WIDTH, ApiError.DATE_DIAGNOSTIC_WIDTH))
                    .as("no width the shared kernel publishes coincides with this boundary's band")
                    .doesNotContain(SCREEN_MESSAGE_WIDTH);
        }

        /**
         * Every sentence this boundary can publish fits the band, and the funnel accounts for the shortfall.
         *
         * <p>Assumptions: the funnel is a single site per program, so the 80-to-78 shortfall is asserted once
         * here rather than per sentence. Both programs compose in {@code WS-MESSAGE PIC X(80)} at their L37
         * and narrow it exactly once, at {@code cbl/COPAUS0C.cbl} L692 and {@code cbl/COPAUS1C.cbl} L377,
         * each the only {@code MOVE WS-MESSAGE TO ERRMSGO} in its program.
         *
         * <p>Assumptions: a sentence longer than the field could never have reached a terminal at all, so
         * publishing one would be a divergence no width annotation on the response would catch -- the list
         * view's message is drawn from a closed set rather than from a length-checked string. That is why
         * this case measures the CLOSED SET and not one rendered instance.
         */
        @Test
        @DisplayName("every publishable sentence fits 78 positions, and the funnel drops exactly two")
        void everySentenceThisBoundaryPublishesFitsTheBandTheMapDeclares() {
            assertThat(WORK_MESSAGE_WIDTH - SCREEN_MESSAGE_WIDTH)
                    .as("the single funnel at COPAUS0C L692 and COPAUS1C L377 drops two positions")
                    .isEqualTo(2);

            List<String> publishable = new ArrayList<>(PendingAuthListView.BOUNDARY_MESSAGES);
            publishable.add(S0C_L269_ENTER_ACCOUNT);
            publishable.add(S0C_L278_NUMERIC_ACCOUNT);
            for (String sentence : publishable) {
                assertThat(sentence.length())
                        .as("the sentence '%s' must fit the 78 positions the map declares", sentence)
                        .isLessThanOrEqualTo(SCREEN_MESSAGE_WIDTH);
            }
            for (String diagnostic : retrievalDiagnostics()) {
                assertThat(diagnostic.length())
                        .as("even a diagnostic transcription must fit the band it would have gone to")
                        .isLessThanOrEqualTo(SCREEN_MESSAGE_WIDTH);
            }
        }

        /**
         * A read that succeeded writes nothing on the message line, and writes absence rather than blank.
         *
         * <p>Assumptions: the absence is a property of the CONTROLLER, which supplies that component of the
         * screen context absent rather than empty, because the reference writes its message line only on a
         * refusal or a navigation boundary and leaves it untouched on a read that succeeded. Distinguishing
         * absent from blank is what keeps a client able to tell "nothing was said" from "an empty sentence
         * was said".
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a successful screen read leaves the message line unwritten")
        void aSuccessfulScreenReadLeavesTheMessageLineUnwritten() throws Exception {
            stubScreenRead(row());

            PendingAuthControllerTest.this.mockMvc.perform(
                            get(SCREEN_ROUTE, selectorForSharedRow()).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").doesNotExist());
        }
    }

    /**
     * The shape of what crosses the wire, which is this package's own question and nobody else's.
     *
     * <p>The controller publishes two representations of one authorization and they are deliberately not the
     * same set of members. {@code GET /api/v1/authorizations/&#123;key&#125;} renders the stored record;
     * {@code GET /api/v1/authorizations/&#123;key&#125;/screen} renders the projection
     * {@code cpy-bms/COPAU01.cpy} declares, which is strictly narrower. Contrasting the two is what makes the
     * projection assertable at all, and it can only be done where a body exists.
     *
     * <p>Assumptions: the projection is a PROJECTION and not the segment. {@code cpy-bms/COPAU01.cpy} has no
     * map field for four items {@code cpy/CIPAUDTY.cpy} stores -- the message type at its L27, the
     * authorization identification code at L29, the transaction amount at L34 and the acquirer country code
     * at L37 -- because {@code cbl/COPAUS1C.cbl} L295 to L356 never moves them to the map. The projection is
     * asserted rather than completed.
     *
     * <p>Assumptions: absence is asserted by reading the body's own member names rather than with a
     * path-absence expectation, because this serialiser emits a null-valued member rather than omitting it,
     * and a path-absence expectation is satisfied by a member whose value is null. The two are different
     * facts and only the member names tell them apart.
     */
    @Nested
    @DisplayName("the shape of the body that crosses the wire")
    class ResponseShapeTest {

        /**
         * The screen body publishes exactly the twenty-seven members the detail map declares, in its order.
         *
         * <p>Assumptions: twenty-seven is a measurement of the map, not a round number.
         * {@code cpy-bms/COPAU01.cpy} declares its length fields between L17 and L180 and there are
         * twenty-seven of them: six of screen chrome, twenty of authorization detail and the message line at
         * L175. The count and the order are asserted together because either alone would miss a member
         * exchanged with its neighbour.
         *
         * <p>Assumptions: the wire list is ALSO compared against the published record's own component list,
         * and the two comparisons answer different questions. Whether the record declares its components in
         * the map's order is a question about the record, and the mapping package owns it; whether the
         * serialiser then puts exactly those components on the wire, under those names and in that order, is a
         * question about this boundary. A rename or an ignore annotation added to one component would break
         * the second while leaving the first passing, which is why the second is asserted here.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the screen body publishes exactly 27 members in the detail map's own order")
        void theScreenBodyPublishesExactlyTheTwentySevenMapDerivedMembers() throws Exception {
            stubScreenRead(row());

            MvcResult result = PendingAuthControllerTest.this.mockMvc.perform(
                            get(SCREEN_ROUTE, selectorForSharedRow()).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(topLevelMemberNames(result))
                    .as("the screen body's members, in the declaration order of COPAU01.cpy L17 to L180")
                    .containsExactly("transactionName", "title01", "currentDate", "programName", "title02",
                            "currentTime", "cardNumber", "authDate", "authTime", "authResponse",
                            "authResponseReason", "processingCode", "approvedAmount", "posEntryMode",
                            "messageSource", "merchantCategoryCode", "cardExpiry", "authType",
                            "transactionId", "matchStatus", "fraudMark", "merchantName", "merchantId",
                            "merchantCity", "merchantState", "merchantZip", "message")
                    .hasSize(27);

            assertThat(topLevelMemberNames(result))
                    .as("serialisation renames nothing, drops nothing and reorders nothing")
                    .containsExactlyElementsOf(Arrays
                            .stream(PendingAuthDetailResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList());
        }

        /**
         * The screen body omits the four stored items the map never declares, and the record read carries
         * them.
         *
         * <p>Assumptions: the contrast is the assertion. Asserting only that the screen body lacks the four
         * would pass equally against a boundary that had lost them everywhere, which would be a data loss
         * rather than a projection; asserting that the sibling representation still publishes all four is
         * what establishes that the narrowing is deliberate and local to the screen shape.
         *
         * @throws Exception if either request cannot be performed
         */
        @Test
        @DisplayName("the four map-absent items are missing from the screen body and present on the record")
        void theScreenBodyOmitsTheFourItemsTheMapNeverDeclares() throws Exception {
            List<String> mapAbsent =
                    List.of("messageType", "authIdCode", "transactionAmt", "acqrCountryCode");
            stubScreenRead(row());
            String selector = selectorForSharedRow();

            MvcResult screen = PendingAuthControllerTest.this.mockMvc.perform(
                            get(SCREEN_ROUTE, selector).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(topLevelMemberNames(screen))
                    .as("CIPAUDTY.cpy L27, L29, L34 and L37 have no map field in COPAU01.cpy")
                    .doesNotContainAnyElementsOf(mapAbsent);

            when(PendingAuthControllerTest.this.detail.read(selector, SUBJECT))
                    .thenReturn(PendingAuthControllerTest.this.mapper.toDetailView(row(), SUBJECT));
            MvcResult record = PendingAuthControllerTest.this.mockMvc.perform(
                            get(READ_ROUTE, selector).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(topLevelMemberNames(record))
                    .as("the record representation still publishes all four, so nothing was lost")
                    .containsAll(mapAbsent);
        }

        /**
         * The six-position authorization code member carries the processing code, despite its map name.
         *
         * <p>Assumptions: the map field named as though it held an authorization code holds the PROCESSING
         * code instead. {@code cbl/COPAUS1C.cbl} L331 moves {@code PA-PROCESSING-CODE} into it, and the
         * authorization identification code {@code cpy/CIPAUDTY.cpy} L29 declares is never moved anywhere.
         * The two stored values are made deliberately different here so a case cannot pass by coincidence.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the six-position code member carries the processing code, not the identification code")
        void theSixPositionCodeMemberCarriesTheProcessingCode() throws Exception {
            assertThat(PROCESSING_CODE)
                    .as("the two stored codes must differ for this case to prove anything")
                    .isNotEqualTo(AUTH_ID_CODE);
            stubScreenRead(row());

            PendingAuthControllerTest.this.mockMvc.perform(
                            get(SCREEN_ROUTE, selectorForSharedRow()).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.processingCode").value(PROCESSING_CODE));
        }

        /**
         * The record read publishes the decoded key components rather than the values the segment stores.
         *
         * <p>Assumptions: both temporal key components ARE published on the record representation, unlike on
         * a list row, and both are the DECODED values rather than the complements the segment holds. A body
         * carrying a complement would look well formed while every date derived from it came out inverted.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the record read publishes the decoded key and a masked account number")
        void theRecordReadPublishesTheDecodedKey() throws Exception {
            String selector = selectorForSharedRow();
            when(PendingAuthControllerTest.this.detail.read(selector, SUBJECT))
                    .thenReturn(PendingAuthControllerTest.this.mapper.toDetailView(row(), SUBJECT));

            PendingAuthControllerTest.this.mockMvc.perform(get(READ_ROUTE, selector).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID_DIGITS))
                    .andExpect(jsonPath("$.authDate").value(AUTH_DATE))
                    .andExpect(jsonPath("$.authTime").value(AUTH_TIME))
                    .andExpect(jsonPath("$.cardNum").value(MASKED_CARD_NUMBER));
        }
    }

    /**
     * The values these screens DERIVE rather than carry, and the one they re-order on the way out.
     *
     * <p>Three renderings in this context are computed at display time and each is asserted on the wire. The
     * approve-or-decline flag is derived from the stored response code in BOTH programs -- at
     * {@code cbl/COPAUS0C.cbl} L536 to L540 into its row work field and at {@code cbl/COPAUS1C.cbl} L311 to
     * L317 into the map -- and the raw code never reaches either screen. The originating date is re-ordered
     * from stored year-first to displayed month-first, at {@code cbl/COPAUS0C.cbl} L531 to L534 and
     * {@code cbl/COPAUS1C.cbl} L297 to L300. The originating time is only re-spaced, at L527 to L529 and
     * L303 to L305 respectively, and it KEEPS its order.
     *
     * <p>Assumptions: colour is a detail-screen concern with no list equivalent and is not asserted here at
     * all. A census of the extension's three screen programs finds one use of the green attribute and one of
     * the red, both in {@code cbl/COPAUS1C.cbl} at L313 and L316, and none in either
     * {@code cbl/COPAUS0C.cbl} or {@code cbl/COPAUS2C.cbl}. The DERIVATION is shared by both programs and is
     * asserted; the attribute is one program's presentation of it and this contract publishes no colour, so
     * inventing a list equivalent would assert something the reference does not do.
     */
    @Nested
    @DisplayName("the values these screens derive or re-order")
    class DerivedRenderingTest {

        /**
         * The screen publishes the derived flag and no raw response code, and the record read publishes both.
         *
         * <p>Assumptions: the derivation is asserted for an approved code AND a declined one, because a
         * single case would pass against an implementation that returned a constant. The declined code is a
         * value other than the approved one rather than any particular decline, since the reference tests
         * only for equality with the approved value and treats everything else alike.
         *
         * @throws Exception if any request cannot be performed
         */
        @Test
        @DisplayName("the screen publishes the derived flag while the record read keeps the raw code")
        void theScreenPublishesTheDerivedFlagAndNotTheRawResponseCode() throws Exception {
            String selector = selectorForSharedRow();

            stubScreenRead(row());
            MvcResult approved = PendingAuthControllerTest.this.mockMvc.perform(
                            get(SCREEN_ROUTE, selector).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authResponse").value(PendingAuthRowView.APPROVAL_STATUS_APPROVED))
                    .andReturn();
            assertThat(topLevelMemberNames(approved))
                    .as("the raw response code has no member on the screen shape")
                    .doesNotContain("authRespCode");

            stubScreenRead(rowWith(RESPONSE_CODE_DECLINED, CATCH_ALL_REASON));
            PendingAuthControllerTest.this.mockMvc.perform(
                            get(SCREEN_ROUTE, selector).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authResponse").value(PendingAuthRowView.APPROVAL_STATUS_DECLINED));

            when(PendingAuthControllerTest.this.detail.read(selector, SUBJECT))
                    .thenReturn(PendingAuthControllerTest.this.mapper.toDetailView(row(), SUBJECT));
            PendingAuthControllerTest.this.mockMvc.perform(get(READ_ROUTE, selector).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authRespCode").value(RESPONSE_CODE_APPROVED));
        }

        /**
         * A list row publishes the same derived flag the screen does, from the same stored code.
         *
         * <p>Assumptions: the two are asserted against the SAME published constants rather than against two
         * literals, because the reference derives the flag independently in each program and the migration's
         * value is that one derivation now serves both. Two literals would pass while the two paths had
         * silently diverged.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a list row publishes the same derived flag as the screen")
        void aListRowPublishesTheSameDerivedFlagAsTheScreen() throws Exception {
            when(PendingAuthControllerTest.this.summaries.list(ACCOUNT_ID, null, null, SUBJECT))
                    .thenReturn(listView());

            PendingAuthControllerTest.this.mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(ACCOUNT_ID_DIGITS, null))
                            .principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.items[0].approvalStatus")
                            .value(PendingAuthRowView.APPROVAL_STATUS_APPROVED));
        }

        /**
         * The originating date is published month first while the time keeps the order it is stored in.
         *
         * <p>Assumptions: the stored date and the stored time are BOTH six characters and the two rules
         * differ, which is the whole reason both are asserted in one case. The date is re-assembled through a
         * month-day-year work field, so stored {@code 260803} displays as {@code 08/03/26}; the time is only
         * given separators at positions three and six, so stored {@code 091644} displays as
         * {@code 09:16:44}. A reader who generalised either rule to the other would corrupt the other, and
         * the record representation carries both stored forms unchanged so the difference is visible.
         *
         * @throws Exception if either request cannot be performed
         */
        @Test
        @DisplayName("the date is re-ordered month first and the time only gains separators")
        void theDateIsReorderedMonthFirstAndTheTimeKeepsItsOrder() throws Exception {
            String selector = selectorForSharedRow();
            stubScreenRead(row());

            PendingAuthControllerTest.this.mockMvc.perform(
                            get(SCREEN_ROUTE, selector).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authDate").value("08/03/26"))
                    .andExpect(jsonPath("$.authTime").value("09:16:44"));

            when(PendingAuthControllerTest.this.detail.read(selector, SUBJECT))
                    .thenReturn(PendingAuthControllerTest.this.mapper.toDetailView(row(), SUBJECT));
            PendingAuthControllerTest.this.mockMvc.perform(get(READ_ROUTE, selector).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authOrigDate").value(STORED_ORIG_DATE))
                    .andExpect(jsonPath("$.authOrigTime").value(STORED_ORIG_TIME));
        }

        /**
         * The fraud mark is a composed value when a report stands and the bare separator when none does.
         *
         * <p>Assumptions: the composition is a flag, a separator and the eight-character report date, giving
         * a ten-position value in a field {@code cpy-bms/COPAU01.cpy} declares as {@code X(10)} at L139 and
         * L174. {@code cbl/COPAUS1C.cbl} L344 to L350 writes the three parts when either fraud condition
         * holds and writes the separator alone otherwise, so the unmarked case is a single character rather
         * than an empty one and both are asserted.
         *
         * @throws Exception if either request cannot be performed
         */
        @Test
        @DisplayName("the fraud mark composes to ten positions when marked and to one when not")
        void theFraudMarkIsComposedWhenMarkedAndBareWhenNot() throws Exception {
            String selector = selectorForSharedRow();

            stubScreenRead(row());
            PendingAuthControllerTest.this.mockMvc.perform(
                            get(SCREEN_ROUTE, selector).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fraudMark").value("-"));

            PendingAuthDetail marked = row();
            marked.applyFraudMark(PendingAuthDetail.FRAUD_REPORTED, FRAUD_REPORT_DATE);
            stubScreenRead(marked);
            PendingAuthControllerTest.this.mockMvc.perform(
                            get(SCREEN_ROUTE, selector).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fraudMark")
                            .value(PendingAuthDetail.FRAUD_REPORTED + "-" + FRAUD_REPORT_DATE));
        }

        /**
         * A recognised catch-all reason and a reason absent from the table render as different values.
         *
         * <p>Assumptions: the two answer different questions and the reference keeps them apart.
         * {@code '9000UNKNOWN'} is a table ENTRY, declared at {@code cbl/COPAUS1C.cbl} L67 and reachable
         * because L715 and L716 of {@code cbl/COPAUA0C.cbl} write {@code '9000'} on the ladder's
         * {@code WHEN OTHER} arm; the table MISS is a separate mechanism, rendering the {@code '9999'} and
         * {@code 'ERROR'} pair L321 to L323 write. A client shown the miss form for an authorization declined
         * for an unenumerated reason would be told the stored row was unreadable when it was not.
         *
         * <p>Assumptions: the composed value is asserted at the full twenty positions the map's field
         * declares, blanks included. The reference moves a four-position code, a separator and a
         * fifteen-position description into a field {@code cpy-bms/COPAU01.cpy} declares as {@code X(20)}, so
         * the trailing blanks are positions the field has rather than whitespace a comparison may ignore.
         *
         * @throws Exception if either request cannot be performed
         */
        @Test
        @DisplayName("the catch-all reason and a reason outside the table render as distinct values")
        void theCatchAllReasonAndTheTableMissRenderDistinctly() throws Exception {
            String selector = selectorForSharedRow();

            stubScreenRead(rowWith(RESPONSE_CODE_DECLINED, CATCH_ALL_REASON));
            PendingAuthControllerTest.this.mockMvc.perform(
                            get(SCREEN_ROUTE, selector).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authResponseReason").value("9000-UNKNOWN        "));

            stubScreenRead(rowWith(RESPONSE_CODE_DECLINED, UNTABLED_REASON));
            PendingAuthControllerTest.this.mockMvc.perform(
                            get(SCREEN_ROUTE, selector).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authResponseReason").value("9999-ERROR          "));
        }

        /**
         * The screen reports the reference chrome, and reports it from the reference declarations.
         *
         * <p>Assumptions: all four chrome values are compared against the reference literals rather than
         * against the controller's own constants, so an expectation cannot follow a constant that drifts. The
         * transaction name is {@code CPVD} per {@code cbl/COPAUS1C.cbl} L36, moved to the screen at L415; the
         * program name is {@code COPAUS1C} per L33, moved at L416; and the two title lines are the complete
         * forty-position title items of {@code app/cpy/COTTL01Y.cpy} L18 to L22, moved at L413 and L414. The
         * leading and trailing blanks are part of each expectation, because the reference moves a
         * forty-position item into a forty-position field and trimming either would be a different value.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the screen reports the reference transaction, program and title band")
        void theScreenReportsTheReferenceChrome() throws Exception {
            stubScreenRead(row());

            PendingAuthControllerTest.this.mockMvc.perform(
                            get(SCREEN_ROUTE, selectorForSharedRow()).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionName").value("CPVD"))
                    .andExpect(jsonPath("$.programName").value("COPAUS1C"))
                    .andExpect(jsonPath("$.title01").value("      AWS Mainframe Modernization       "))
                    .andExpect(jsonPath("$.title02").value("              CardDemo                  "))
                    .andExpect(jsonPath("$.currentDate").value("08/05/26"))
                    .andExpect(jsonPath("$.currentTime").value("10:45:35"));
        }
    }

    /**
     * The envelope, which positions on a key and counts nothing.
     *
     * <p>Alternatives Considered: offset pagination -- a page number, or a skip count with a total, in place
     * of the opaque cursor this envelope carries. It is rejected because it does not preserve what the
     * reference browse observably does. An offset is resolved against the rows that exist at the instant each
     * page is fetched, so an insert landing ahead of the reader between two fetches SKIPS a row out of the
     * next page and a delete REPEATS one; a row seen twice and a row never seen at all are both states a
     * client can observe, and neither can arise from positioning on a key. The reference browse state is
     * already a key cursor rather than a position, which is what makes the substitution exact:
     * {@code cbl/COPAUS0C.cbl} carries a forward key at L391 and L394 and repositions on it through
     * {@code REPOSITION-AUTHORIZATIONS} at L397, and its further-page indicator
     * {@code CDEMO-CPVS-NEXT-PAGE-FLG} is declared at L123 with its two condition names at L124 and L125 --
     * a flag, not a count.
     *
     * <p>Assumptions: the page depth is FIVE, stated three times over in that program. L126 declares the key
     * table as occurring five times, L424 bounds the fill loop above five and L611 bounds the clearing loop
     * the same way, and five is also the number of row groups {@code cpy-bms/COPAU00.cpy} declares.
     */
    @Nested
    @DisplayName("the key-positioned envelope")
    class KeysetPagingTest {

        /**
         * The envelope publishes its five key-positioned members and no counted or numbered one.
         *
         * <p>Assumptions: the absence of a page number, an offset, a skip and a total is asserted POSITIVELY
         * rather than trusted, because those are the members an offset-paged envelope would carry and an
         * envelope that quietly grew one would let a client start paging by position against data that is
         * positioned by key.
         *
         * <p>Assumptions: the published envelope type is inspected as well as the body, so a member added to
         * it is caught even in a case where that member happened to serialise as absent. The forbidden
         * fragments are matched case-insensitively against every declared component name.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the envelope carries five key-positioned members and nothing counted or numbered")
        void theEnvelopeCarriesItsFiveMembersAndNothingCounted() throws Exception {
            when(PendingAuthControllerTest.this.summaries.list(ACCOUNT_ID, null, null, SUBJECT))
                    .thenReturn(fullPageListView());

            MvcResult result = PendingAuthControllerTest.this.mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(ACCOUNT_ID_DIGITS, null))
                            .principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.items.length()").value(PendingAuthSummaryService.PAGE_SIZE))
                    .andExpect(jsonPath("$.page.hasNext").value(true))
                    .andExpect(jsonPath("$.page.firstKey").exists())
                    .andExpect(jsonPath("$.page.lastKey").exists())
                    .andReturn();

            assertThat(memberNamesAt(result, "page"))
                    .as("the envelope's members are the two boundary keys, the further-page flag and the"
                            + " rows")
                    .containsExactlyInAnyOrder("items", "firstKey", "lastKey", "hasNext");

            for (RecordComponent component : PageResponse.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .as("the published envelope declares no positional or counting member")
                        .doesNotContain("offset")
                        .doesNotContain("skip")
                        .doesNotContain("total")
                        .doesNotContain("count")
                        .doesNotContain("number")
                        .doesNotContain("pagenum");
            }
        }

        /**
         * A direction outside the two published values is refused at the boundary, keyed to the direction.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an unpublished paging direction is refused at the boundary")
        void anUnpublishedPagingDirectionIsRefusedAtTheBoundary() throws Exception {
            PendingAuthControllerTest.this.mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(ACCOUNT_ID_DIGITS, "backwards"))
                            .principal(PRINCIPAL))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field")
                            .value(PendingAuthSummaryService.DIRECTION_FIELD));

            verifyNoInteractions(PendingAuthControllerTest.this.summaries);
        }

        /**
         * A direction supplied with no cursor reaches the service and is refused there, keyed the same way.
         *
         * <p>Assumptions: this refusal is the SERVICE's rather than the boundary's, because it is a
         * relationship between two criteria and not a property of either alone. Asserting it here is what
         * proves the relationship survives the trip out through the boundary rather than being absorbed by a
         * default, and the field key it arrives under is the same one the boundary uses for its own refusal.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a direction with no cursor is refused and keyed to the direction")
        void aDirectionWithNoCursorIsRefused() throws Exception {
            when(PendingAuthControllerTest.this.summaries.list(ACCOUNT_ID, null, "previous", SUBJECT))
                    .thenThrow(new ClientInputException(
                            PendingAuthSummaryService.PAGING_REFUSAL_CODE,
                            PendingAuthSummaryService.DIRECTION_FIELD,
                            "a paging direction is meaningful only alongside a cursor"));

            PendingAuthControllerTest.this.mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(ACCOUNT_ID_DIGITS, "previous"))
                            .principal(PRINCIPAL))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                    .andExpect(jsonPath("$.fieldErrors[0].field")
                            .value(PendingAuthSummaryService.DIRECTION_FIELD));
        }

        /**
         * An account with no summary row answers 200 with a zeroed summary and an empty page.
         *
         * <p>Assumptions: this route publishes no 404 at all, and the case is written at the boundary because
         * that is where the difference is observable. The reference settles it twice over: its keyed retrieval
         * evaluates a found arm and a not-found arm with no end-of-database arm at {@code cbl/COPAUS0C.cbl}
         * L980 to L996, and its caller then RENDERS the absence rather than reporting it, moving zero into all
         * six aggregate positions at L800 to L807 and skipping the browse at L354 to L356.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an account with no summary row answers 200 with a zeroed summary and no rows")
        void anAccountWithNoSummaryRowAnswersZeroedEmptyPage() throws Exception {
            when(PendingAuthControllerTest.this.summaries.list(ACCOUNT_ID, null, null, SUBJECT))
                    .thenReturn(zeroedListView());

            PendingAuthControllerTest.this.mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(ACCOUNT_ID_DIGITS, null))
                            .principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.summary.accountId").value(ACCOUNT_ID_DIGITS))
                    .andExpect(jsonPath("$.summary.approvedAuthCnt").value(0))
                    .andExpect(jsonPath("$.summary.declinedAuthCnt").value(0))
                    .andExpect(jsonPath("$.summary.creditBalance").value("0.00"))
                    .andExpect(jsonPath("$.summary.approvedAuthAmt").value("0.00"))
                    .andExpect(jsonPath("$.page.items").isEmpty())
                    .andExpect(jsonPath("$.page.hasNext").value(false))
                    .andExpect(jsonPath("$.page.firstKey").doesNotExist())
                    .andExpect(jsonPath("$.page.lastKey").doesNotExist())
                    .andExpect(jsonPath("$.screenMessage").doesNotExist());
        }
    }

    /**
     * Statelessness, demonstrated rather than described.
     *
     * <p>Refactoring Rationale: the reference is strictly pseudo-conversational -- its task ends at every
     * screen turn -- so all continuity between turns lives in one structure the terminal hands back, declared
     * at {@code cbl/COPAUS1C.cbl} L151 to L154 as {@code 01 DFHCOMMAREA} over
     * {@code 05 LK-COMMAREA PIC X(01) OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN}. Two things were wrong
     * with that arrangement and both are the reason this group exists rather than a preference for stateless
     * services. First, the structure is storage THE CLIENT ECHOES BACK, so the user type it carries is a
     * value the client supplies and could in principle assert for itself; identity here arrives instead as an
     * authenticated principal the client cannot compose. Second, the same structure carries a re-entry
     * discriminator, which the reference resets at L364 and L365 with {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT}
     * and {@code SET CDEMO-PGM-ENTER TO TRUE} immediately before transferring control at L367 to L370 -- and
     * that discriminator additionally GATED the field-highlight logic, so error presentation depended on a
     * remembered turn count. Here it depends on the response body and on nothing else, which is why no case
     * in this class sends a discriminator and none is needed to make a refusal render.
     *
     * <p>Assumptions: the selection context arrives in the request rather than in retained state -- the
     * account scope in the search body and the sealed selector in the path -- so every request is
     * self-describing and independently answerable. That is the property horizontal scaling rests on, and it
     * is what makes the absence of a session below meaningful rather than incidental.
     */
    @Nested
    @DisplayName("statelessness at this boundary")
    class StatelessnessTest {

        /**
         * A client-supplied user type has no effect on the response.
         *
         * <p>Assumptions: the two responses are compared BYTE FOR BYTE, which is only sound because the body
         * chosen carries no row and therefore no sealed token; a token embeds fresh random material on every
         * mint, so a body containing one differs between two otherwise identical requests and would fail this
         * comparison for a reason that has nothing to do with the claim.
         *
         * <p>Assumptions: the criteria record admits no such member, so the request is accepted and the extra
         * member is disregarded. Both outcomes -- disregarded, or refused -- would satisfy "has no effect";
         * what would NOT satisfy it is a member that reached an authority decision, and comparing whole
         * bodies is what excludes that without depending on which of the two outcomes the binder chooses.
         *
         * <p>⚠️ Assumptions: which of the two outcomes the DEPLOYED reader chooses is now settled, and it is
         * refusal -- this module's {@code application.yml} refuses an undeclared member. The reader used here
         * is built by hand in {@code setUp} and is deliberately left at its defaults, because this class
         * exercises one controller rather than the module's configuration; the refusal is asserted where the
         * configuration is, in {@code config/JsonReadConfigTest}. The claim above is unaffected either way,
         * which is why this case is stated to survive both.
         *
         * @throws Exception if either request cannot be performed
         */
        @Test
        @DisplayName("a client-supplied user type changes nothing about the response")
        void aClientSuppliedUserTypeChangesNothingAboutTheResponse() throws Exception {
            when(PendingAuthControllerTest.this.summaries.list(ACCOUNT_ID, null, null, SUBJECT))
                    .thenReturn(zeroedListView());

            String withoutClaim = PendingAuthControllerTest.this.mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(ACCOUNT_ID_DIGITS, null))
                            .principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            String withClaim = PendingAuthControllerTest.this.mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"accountId\":\"" + ACCOUNT_ID_DIGITS
                                    + "\",\"userType\":\"A\",\"cdemoUserType\":\"A\"}")
                            .principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(withClaim)
                    .as("a user type the client asserted for itself must not alter what it is answered")
                    .isEqualTo(withoutClaim);
        }

        /**
         * No route creates or consults a session, and no body carries a re-entry discriminator.
         *
         * <p>Assumptions: the session is queried WITHOUT creating one, so the query itself cannot manufacture
         * the thing it is looking for. A query that created a session on demand would answer non-null on
         * every route and the case would assert nothing.
         *
         * <p>Assumptions: the forbidden member names are matched case-insensitively against the whole body
         * text rather than against top-level names alone, because a discriminator smuggled into a nested
         * object would be just as much retained state as one at the root.
         *
         * @throws Exception if any request cannot be performed
         */
        @Test
        @DisplayName("no route opens a session and no body carries a re-entry discriminator")
        void noRouteOpensASessionAndNoBodyCarriesAReEntryDiscriminator() throws Exception {
            String selector = selectorForSharedRow();
            when(PendingAuthControllerTest.this.summaries.list(ACCOUNT_ID, null, null, SUBJECT))
                    .thenReturn(listView());
            when(PendingAuthControllerTest.this.detail.read(selector, SUBJECT))
                    .thenReturn(PendingAuthControllerTest.this.mapper.toDetailView(row(), SUBJECT));
            stubScreenRead(row());

            List<MvcResult> results = List.of(
                    PendingAuthControllerTest.this.mockMvc.perform(post(LIST_ROUTE)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(listBody(ACCOUNT_ID_DIGITS, null))
                                    .principal(PRINCIPAL))
                            .andExpect(status().isOk()).andReturn(),
                    PendingAuthControllerTest.this.mockMvc.perform(
                                    get(READ_ROUTE, selector).principal(PRINCIPAL))
                            .andExpect(status().isOk()).andReturn(),
                    PendingAuthControllerTest.this.mockMvc.perform(
                                    get(SCREEN_ROUTE, selector).principal(PRINCIPAL))
                            .andExpect(status().isOk()).andReturn());

            for (MvcResult result : results) {
                assertThat(result.getRequest().getSession(false))
                        .as("the request to %s must leave no session behind",
                                result.getRequest().getRequestURI())
                        .isNull();
                String body = result.getResponse().getContentAsString().toLowerCase(Locale.ROOT);
                assertThat(body)
                        .as("no body may carry the re-entry discriminator or the passed structure")
                        .doesNotContain("pgmcontext")
                        .doesNotContain("pgm_context")
                        .doesNotContain("reenter")
                        .doesNotContain("commarea")
                        .doesNotContain("usertype");
            }
        }
    }

    /**
     * What must never cross the wire, asserted on the serialised body rather than on a view.
     *
     * <p>Assumptions: the masking is a property of the mapping layer, and this group asserts THAT the masked
     * form is what crosses rather than HOW the mask is applied -- that mechanism belongs to the mapping
     * package. The distinction is worth keeping because masking appears in both places, and the reason it
     * must appear here too is that a body is where a disclosure would actually occur.
     */
    @Nested
    @DisplayName("what must never cross the wire")
    class MaskingTest {

        /**
         * No route publishes the sixteen-digit account number, and none publishes a verification value.
         *
         * <p>Assumptions: all four published routes are exercised in one case, because the claim is about the
         * package rather than about a handler, and a route added later without masking is exactly what a
         * per-handler case would miss. The unmasked number is searched for as a SUBSTRING of the raw body, so
         * a member holding it under any name is caught.
         *
         * <p>Assumptions: the verification value has no member on any representation in this context, which
         * is stronger than it being masked. It is asserted by name rather than by value because there is no
         * value to look for -- nothing in this context stores one.
         *
         * @throws Exception if any request cannot be performed
         */
        @Test
        @DisplayName("no route publishes the full account number or any verification value")
        void noRoutePublishesTheFullAccountNumberOrAnyVerificationValue() throws Exception {
            String selector = selectorForSharedRow();
            when(PendingAuthControllerTest.this.summaries.list(ACCOUNT_ID, null, null, SUBJECT))
                    .thenReturn(listView());
            when(PendingAuthControllerTest.this.detail.read(selector, SUBJECT))
                    .thenReturn(PendingAuthControllerTest.this.mapper.toDetailView(row(), SUBJECT));
            when(PendingAuthControllerTest.this.detail.readNext(selector, SUBJECT))
                    .thenReturn(new PendingAuthDetailService.NextAuthorization(
                            PendingAuthControllerTest.this.mapper.toDetailView(row(), SUBJECT), false, null));
            stubScreenRead(row());

            List<String> bodies = new ArrayList<>();
            bodies.add(PendingAuthControllerTest.this.mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(ACCOUNT_ID_DIGITS, null))
                            .principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.items[0].cardNum").value(MASKED_CARD_NUMBER))
                    .andReturn().getResponse().getContentAsString());
            bodies.add(PendingAuthControllerTest.this.mockMvc.perform(
                            get(READ_ROUTE, selector).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cardNum").value(MASKED_CARD_NUMBER))
                    .andReturn().getResponse().getContentAsString());
            bodies.add(PendingAuthControllerTest.this.mockMvc.perform(
                            get(SCREEN_ROUTE, selector).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cardNumber").value(MASKED_CARD_NUMBER))
                    .andReturn().getResponse().getContentAsString());
            bodies.add(PendingAuthControllerTest.this.mockMvc.perform(
                            get(NEXT_ROUTE, selector).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authorization.cardNum").value(MASKED_CARD_NUMBER))
                    .andReturn().getResponse().getContentAsString());

            for (String body : bodies) {
                assertThat(body)
                        .as("the unmasked primary account number must appear in no body")
                        .doesNotContain(CARD_NUMBER)
                        .contains(MASKED_CARD_NUMBER);
                // WHY : ⚠️ Refactoring Rationale: the member-name scan runs over the body with the SEALED
                //       SELECTOR TOKENS removed, and the exclusion is a correctness fix rather than a
                //       convenience. A selector is base64url material minted fresh per response with a
                //       random nonce, so any three-letter sequence -- "cvv" among them -- occurs in it by
                //       chance, and this assertion failed on a run whose token happened to contain
                //       "...oycvvdzuq...". A test that fails on a coin flip stops being read as a signal,
                //       and it was asserting nothing about the token: the claim is that no MEMBER NAME on
                //       any representation declares a verification value, and a token carries no member
                //       names. The account-number assertion above is deliberately left over the WHOLE body,
                //       because that one searches for a VALUE and a token could in principle carry it.
                assertThat(withoutSelectorTokens(body).toLowerCase(Locale.ROOT))
                        .as("no representation in this context declares a verification value at all")
                        .doesNotContain("cvv")
                        .doesNotContain("verificationvalue")
                        .doesNotContain("cardverification");
            }
        }

        /**
         * Replaces every sealed selector token in a body with a fixed placeholder.
         *
         * <p>Assumptions: the tokens are matched by the published selector shape -- the version prefix, the
         * nonce and the sealed material, all base64url -- rather than by JSON member name, because they
         * appear under two different names across these four bodies ({@code key} on a row and a detail,
         * nested under {@code authorization} on the paging move) and a name-based edit would have to know all
         * of them.</p>
         *
         * @param body one serialised response body; must not be {@code null}
         * @return the same body with each selector's characters replaced, never {@code null}
         */
        private String withoutSelectorTokens(String body) {
            return body.replaceAll("v2\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "v2.SEALED.SEALED");
        }
    }

    /**
     * The shape a refusal takes, and the states it distinguishes.
     *
     * <p>Transformation rule T7 turns the reference's validation flag into a structured per-field entry, and
     * the flag is real: {@code MOVE 'Y' TO WS-ERR-FLG} appears at {@code cbl/COPAUS0C.cbl} L474, L507, L986
     * and L1020 and at {@code cbl/COPAUS1C.cbl} L542. Its target is
     * {@code com.carddemo.common.validation.FieldValidationFlag}, whose published entry carries a field key, a
     * state and a message.
     *
     * <p>Assumptions: nothing in this group commits or rolls anything back, because this controller declares
     * no transaction boundary and holds no repository. The two reference programs do commit and their commit
     * shapes genuinely differ -- {@code cbl/COPAUS1C.cbl} takes an UNGUARDED one at L557 to L560, while
     * {@code cbl/COPAUS0C.cbl} takes a GUARDED one at L684 to L688, entered only when a resource is currently
     * scheduled and paired with releasing it in the same block -- so the target boundary is a conditional
     * commit-and-release rather than an unconditional commit. Both shapes, and the rollback paragraph at
     * {@code cbl/COPAUS1C.cbl} L565 to L569 that the fraud path reaches from L540, belong to the packages that
     * own a transaction. What IS assertable here is the read-side consequence: a failure raised beneath this
     * boundary propagates out as a refusal rather than being absorbed into a partial success.
     */
    @Nested
    @DisplayName("the shape of a refusal")
    class ErrorShapeTest {

        /**
         * The never-supplied state carries the shared kernel's screen marker and the other states do not.
         *
         * <p>Assumptions: the marker is taken from the published constant rather than written as a character
         * here, because that type deliberately keeps the STATE apart from the marker: the marker the reference
         * template writes into a never-supplied field is published separately and is never a value the
         * state's own code accessor returns. Asserting the state and the marker independently is what keeps
         * the two from being conflated.
         */
        @Test
        @DisplayName("only the never-supplied state carries the screen marker")
        void onlyTheNeverSuppliedStateCarriesTheScreenMarker() {
            assertThat(FieldValidationFlag.BLANK.requiresBlankMarker())
                    .as("the reference template writes an extra marker for a never-supplied field")
                    .isTrue();
            assertThat(FieldValidationFlag.BLANK.screenMarker())
                    .as("the marker is the shared kernel's published constant")
                    .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER)
                    .isEqualTo("*");
            assertThat(FieldValidationFlag.NOT_OK.requiresBlankMarker())
                    .as("a value that was supplied and refused gets no marker")
                    .isFalse();
            assertThat(FieldValidationFlag.NOT_OK.screenMarker())
                    .as("the not-acceptable state renders no marker at all")
                    .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);
        }

        /**
         * A selector that is not of the sealed shape is refused and keyed to the path member.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a selector that is not sealed is refused and keyed to the path member")
        void aSelectorThatIsNotSealedIsRefused() throws Exception {
            when(PendingAuthControllerTest.this.detail.read(any(), any()))
                    .thenThrow(new ClientInputException(PendingAuthViewMapper.SELECTOR_REFUSAL_CODE,
                            PendingAuthViewMapper.SELECTOR_FIELD, "sealed value is not a sealed token"));

            PendingAuthControllerTest.this.mockMvc.perform(
                            get(READ_ROUTE, "11111111111:26215:91644902").principal(PRINCIPAL))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field")
                            .value(PendingAuthViewMapper.SELECTOR_FIELD));
        }

        /**
         * Contention on the stored row answers 409 rather than surfacing a persistence failure.
         *
         * <p>Assumptions: the mapping belongs to the shared advice and not to the controller, so the case is
         * driven by letting the read service raise the framework's optimistic-lock failure and asserting the
         * status and the conflict code the advice produces. A local handler on the controller would answer
         * this identically while making the advice's contract unobservable, and the two could then disagree.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an optimistic-lock conflict answers 409 through the shared advice")
        void anOptimisticLockConflictAnswersConflict() throws Exception {
            when(PendingAuthControllerTest.this.detail.read(any(), any()))
                    .thenThrow(new OptimisticLockingFailureException("row changed"));

            PendingAuthControllerTest.this.mockMvc.perform(
                            get(READ_ROUTE, selectorForSharedRow()).principal(PRINCIPAL))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_CONFLICT));
        }

        /**
         * A failure raised beneath this boundary propagates out and never yields a partial success.
         *
         * <p>Assumptions: the assertion is that the response is NOT a success and carries no rendered
         * authorization member, which is the read-side expression of propagation. A boundary that absorbed
         * the failure would answer 200 with an authorization member holding nothing, and that body is what
         * this case exists to exclude -- a client cannot tell an empty record from a record that is genuinely
         * empty.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a failure beneath the boundary propagates and yields no partial body")
        void aFailureBeneathTheBoundaryPropagatesAndYieldsNoPartialBody() throws Exception {
            when(PendingAuthControllerTest.this.detail.readNext(any(), any()))
                    .thenThrow(new IllegalStateException("the read could not be completed"));

            MvcResult result = PendingAuthControllerTest.this.mockMvc.perform(
                            get(NEXT_ROUTE, selectorForSharedRow()).principal(PRINCIPAL))
                    .andExpect(status().isInternalServerError())
                    .andReturn();

            assertThat(topLevelMemberNames(result))
                    .as("a refused request answers a problem body and nothing resembling a result")
                    .doesNotContain("authorization")
                    .doesNotContain("endOfData")
                    .contains("code", "status", "fieldErrors");
        }
    }

    /**
     * Money, which must reach a client as text and never as a bare number.
     *
     * <p>Transformation rule T3 requires exact fixed point at every hop, and the hop this class owns is the
     * serialised body. A bare JSON number is parsed into IEEE-754 binary floating point by most clients,
     * which destroys exactness at the one boundary a user actually sees, so the wire form is a string.
     *
     * <p>Assumptions: this module has to assert that for itself. The inherited layering rules in
     * {@code services/common-lib} scope their prohibition on IEEE-754 binary floating point to the shared
     * money package alone, so they do not reach this service and cannot be relied on here.
     */
    @Nested
    @DisplayName("money on the wire")
    class MoneyRenderingTest {

        /**
         * Every amount crosses the boundary quoted, on both the list and the screen representation.
         *
         * <p>Assumptions: the quoting is asserted against the RAW body text rather than through a path
         * expectation, because a path expectation comparing against a string will happily coerce a JSON
         * number and pass. Only the raw text distinguishes a quoted value from a bare one.
         *
         * @throws Exception if either request cannot be performed
         */
        @Test
        @DisplayName("every amount crosses the boundary as a quoted value, never as a bare number")
        void everyAmountCrossesTheBoundaryQuoted() throws Exception {
            when(PendingAuthControllerTest.this.summaries.list(ACCOUNT_ID, null, null, SUBJECT))
                    .thenReturn(listView());
            stubScreenRead(row());

            String list = PendingAuthControllerTest.this.mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(ACCOUNT_ID_DIGITS, null))
                            .principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(list)
                    .as("the row amount and the aggregate amounts are all quoted")
                    .contains("\"amount\":\"250.00\"")
                    .contains("\"creditLimit\":\"5000.00\"")
                    .contains("\"approvedAuthAmt\":\"250.00\"")
                    .doesNotContain("\"amount\":250")
                    .doesNotContain("\"creditLimit\":5000");

            String screen = PendingAuthControllerTest.this.mockMvc.perform(
                            get(SCREEN_ROUTE, selectorForSharedRow()).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(screen)
                    .as("the screen amount is quoted too, and is the approved amount")
                    .contains("\"approvedAmount\":\"250.00\"")
                    .doesNotContain("\"approvedAmount\":250");
        }

        /**
         * The rendered scale and the rounding are the shared kernel's own, and the rendering shows the scale.
         *
         * <p>Assumptions: the amount stored on the row has an exact two-place scale already, so the rendering
         * cannot acquire its two places from rounding and the two places seen on the wire are the declared
         * scale being preserved. The scale and the mode are read from the shared kernel rather than written
         * as literals here, so a change to either fails this case rather than passing silently against a
         * stale copy.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the rendered amount keeps the declared scale and the kernel's rounding mode")
        void theRenderedAmountKeepsTheDeclaredScaleAndRoundingMode() throws Exception {
            assertThat(Money.SCALE)
                    .as("every monetary field in the base masters is declared with two decimal places")
                    .isEqualTo(2);
            assertThat(Money.GENERAL_ROUNDING)
                    .as("the shared kernel rounds half away from zero for general arithmetic")
                    .isEqualTo(RoundingMode.HALF_UP);

            stubScreenRead(row());
            PendingAuthControllerTest.this.mockMvc.perform(
                            get(SCREEN_ROUTE, selectorForSharedRow()).principal(PRINCIPAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.approvedAmount").value("250.00"));
        }
    }

    /**
     * Builds the JSON body the search route takes its criteria in.
     *
     * <p>Refactoring Rationale: these criteria were sent as QUERY PARAMETERS against a read of the collection.
     * They travel in a body because the account scope is an account identifier, and a query string is part of
     * the request line the load balancer writes into its own access log before any application code runs -- a
     * record the migration's sensitive-data contract forbids it to hold. This helper exists so each case
     * states only the criteria it varies rather than repeating the whole shape.
     *
     * @param accountId the account scope to send, or {@code null} to omit the member entirely
     * @param direction the paging direction to send, or {@code null} to omit the member entirely
     * @return a JSON object carrying exactly the members that were supplied, never {@code null}
     */
    private static String listBody(String accountId, String direction) {
        StringBuilder body = new StringBuilder("{");
        if (accountId != null) {
            body.append("\"accountId\":\"").append(accountId).append('"');
        }
        if (direction != null) {
            if (body.length() > 1) {
                body.append(',');
            }
            body.append("\"direction\":\"").append(direction).append('"');
        }
        return body.append('}').toString();
    }

    /**
     * Builds the JSON body a paging move sends: the account scope, a cursor and a direction.
     *
     * @param cursor the sealed cursor to send; must not be {@code null}
     * @param direction the paging direction to send; must not be {@code null}
     * @return a JSON object carrying the scope, the cursor and the direction, never {@code null}
     */
    private static String pagingBody(String cursor, String direction) {
        return "{\"accountId\":\"" + ACCOUNT_ID_DIGITS + "\",\"cursor\":\"" + cursor
                + "\",\"direction\":\"" + direction + "\"}";
    }

    /**
     * Collects the thirteen single-literal message sites the two programs declare, keyed by program and line.
     *
     * <p>Assumptions: the key is the ORIGINATING SITE and not the condition, which is what allows two
     * programs' wordings of one condition to coexist as two entries. Keying by condition would make the map
     * lossy at construction time, before any assertion could observe it.
     *
     * @return an insertion-ordered map from a program-and-line key to the sentence declared there, never
     *     {@code null}
     */
    private static Map<String, String> messageCatalogue() {
        Map<String, String> catalogue = new LinkedHashMap<>();
        catalogue.put("COPAUS0C/L269", S0C_L269_ENTER_ACCOUNT);
        catalogue.put("COPAUS0C/L278", S0C_L278_NUMERIC_ACCOUNT);
        catalogue.put("COPAUS0C/L381", S0C_L381_TOP_OF_PAGE);
        catalogue.put("COPAUS0C/L409", S0C_L409_BOTTOM_OF_PAGE);
        catalogue.put("COPAUS0C/L477", S0C_L477_READ_DETAILS);
        catalogue.put("COPAUS0C/L510", S0C_L510_REPOSITION_DETAILS);
        catalogue.put("COPAUS0C/L989", S0C_L989_READ_SUMMARY);
        catalogue.put("COPAUS0C/L1023", S0C_L1023_SCHEDULE_PSB);
        catalogue.put("COPAUS1C/L283", S1C_L283_LAST_AUTHORIZATION);
        catalogue.put("COPAUS1C/L456", S1C_L456_READ_SUMMARY);
        catalogue.put("COPAUS1C/L482", S1C_L482_READ_DETAILS);
        catalogue.put("COPAUS1C/L511", S1C_L511_READ_NEXT);
        catalogue.put("COPAUS1C/L596", S1C_L596_SCHEDULE_PSB);
        return catalogue;
    }

    /**
     * Lists the eight retrieval diagnostics, which are the sites that splice a machine status in the
     * reference.
     *
     * @return the eight sentences, four from each program, never {@code null}
     */
    private static List<String> retrievalDiagnostics() {
        return List.of(S0C_L477_READ_DETAILS, S0C_L510_REPOSITION_DETAILS, S0C_L989_READ_SUMMARY,
                S0C_L1023_SCHEDULE_PSB, S1C_L456_READ_SUMMARY, S1C_L482_READ_DETAILS, S1C_L511_READ_NEXT,
                S1C_L596_SCHEDULE_PSB);
    }

    /**
     * Lists the five sentences addressed to an operator rather than describing a machine condition.
     *
     * @return the two field edits and the three navigation boundaries, never {@code null}
     */
    private static List<String> operatorSentences() {
        return List.of(S0C_L269_ENTER_ACCOUNT, S0C_L278_NUMERIC_ACCOUNT, S0C_L381_TOP_OF_PAGE,
                S0C_L409_BOTTOM_OF_PAGE, S1C_L283_LAST_AUTHORIZATION);
    }

    /**
     * Lists the three middle fragments of the file-access composites.
     *
     * @return the cross-reference, account and customer fragments in declaration order, never {@code null}
     */
    private static List<String> fileDiagnosticMiddles() {
        return List.of(S0C_L854_XREF_FILE, S0C_L904_ACCT_FILE, S0C_L955_CUST_FILE);
    }

    /**
     * Reads the member names of a response body's root object, in the order they were serialised.
     *
     * <p>Assumptions: a member present with a null value is REPORTED, which is the whole reason this helper
     * exists rather than a path-absence expectation. This serialiser emits such a member rather than omitting
     * it, and a path-absence expectation is satisfied by a null value, so absence and null-valued presence are
     * indistinguishable through that route and distinguishable through this one.
     *
     * @param result the completed exchange whose response body should be read; must not be {@code null}
     * @return the root object's member names in serialised order, never {@code null}
     * @throws Exception if the response body cannot be read or is not a JSON object
     */
    private static List<String> topLevelMemberNames(MvcResult result) throws Exception {
        return List.copyOf(BODY_READER.readTree(result.getResponse().getContentAsString())
                .propertyNames());
    }

    /**
     * Reads the member names of one nested object inside a response body.
     *
     * @param result the completed exchange whose response body should be read; must not be {@code null}
     * @param member the name of the root member holding the nested object; must not be {@code null}
     * @return the nested object's member names in serialised order, never {@code null}
     * @throws Exception if the response body cannot be read or the named member is not a JSON object
     */
    private static List<String> memberNamesAt(MvcResult result, String member) throws Exception {
        return List.copyOf(BODY_READER.readTree(result.getResponse().getContentAsString())
                .get(member).propertyNames());
    }

    /**
     * The reader used to inspect a response body's member names.
     *
     * <p>Assumptions: it is a plain mapper with no module registered, unlike the one the server serialises
     * with, because it only ever reads member NAMES and never binds a value. Registering the money codec here
     * would suggest it participated in the assertions, which it does not.
     */
    private static final JsonMapper BODY_READER = JsonMapper.builder().build();

    /**
     * Points the screen read at one authorization row, rendered through the real mapper.
     *
     * <p>Assumptions: the screen chrome is taken from the context the CONTROLLER built and captured from the
     * invocation, rather than restated here, which is what makes the chrome assertions assertions about the
     * controller. Restating it would test this helper.
     *
     * <p>Assumptions: the composed decline description is resolved from the row's OWN stored reason through
     * the service's published lookup, which is the composition the production read performs. Passing an
     * unrelated description would let a case assert a pairing the service cannot produce.
     *
     * @param row the authorization row the screen read should render; must not be {@code null}
     */
    private void stubScreenRead(PendingAuthDetail row) {
        when(this.detail.readForScreen(eq(selectorForSharedRow()), eq(SUBJECT), any()))
                .thenAnswer(invocation -> PendingAuthDetailMapper.toResponse(row,
                        PendingAuthDetailService.declineDescriptionFor(row.getAuthRespReason()),
                        invocation.getArgument(2)));
    }

    /**
     * Returns the one sealed selector, minted for this test, that addresses the shared authorization row.
     *
     * <p>Assumptions: the selector is minted through the REAL mapper rather than written as a literal,
     * because it is a sealed token whose shape the boundary constrains and no literal a test could write
     * would be redeemable.
     *
     * <p>Assumptions: it is minted ONCE per test and then reused, and that is load-bearing rather than
     * tidiness. Sealing embeds fresh random material on every mint, so two mints of the SAME row are two
     * different tokens; a stand-in keyed on the first while the request carried the second would simply never
     * match, and a stand-in that does not match answers with nothing at all. The resulting empty body then
     * fails whichever member a case happened to assert, which points at the member rather than at the
     * mismatch -- so this method exists to make the two sides provably the same value.
     *
     * @return the sealed selector bound to the test subject for this test, never {@code null}
     */
    private String selectorForSharedRow() {
        return this.sharedSelector;
    }

    /**
     * Builds a list body carrying a message line and no rows.
     *
     * @param message the message line the view should carry, or {@code null} for none
     * @return a mapped list view carrying that message and an empty page, never {@code null}
     */
    private PendingAuthListView messageListView(String message) {
        return this.mapper.toListView(new PendingAuthSummary(ACCOUNT_ID, CUSTOMER_ID), List.of(),
                false, message, SUBJECT, null);
    }

    /**
     * Builds a list body carrying a full page of rows with a further page behind it.
     *
     * <p>Assumptions: the rows differ only in the time component of their key, because the page depth is what
     * this body exists to exercise and each row's selector is sealed from its key -- identical rows would seal
     * to one value and the envelope's two boundary cursors would then be indistinguishable.
     *
     * @return a mapped list view carrying exactly the reference page depth of rows, never {@code null}
     */
    private PendingAuthListView fullPageListView() {
        List<PendingAuthDetail> rows = new ArrayList<>();
        for (int index = 0; index < PendingAuthSummaryService.PAGE_SIZE; index++) {
            rows.add(rowAt(AUTH_TIME - index));
        }
        return this.mapper.toListView(new PendingAuthSummary(ACCOUNT_ID, CUSTOMER_ID), rows, true,
                null, SUBJECT, null);
    }

    /**
     * Builds the list body most cases return: one summary block carrying limits and one row.
     *
     * @return a mapped list view carrying one row and no further page, never {@code null}
     */
    private PendingAuthListView listView() {
        PendingAuthSummary summary = new PendingAuthSummary(ACCOUNT_ID, CUSTOMER_ID);
        summary.refreshLimits(new BigDecimal("5000.00"), new BigDecimal("1000.00"));
        summary.recordApproved(new BigDecimal("250.00"));
        return this.mapper.toListView(summary, List.of(row()), false, null, SUBJECT, null);
    }

    /**
     * Builds the body an account with no summary row is answered with: zeros and no rows.
     *
     * <p>Assumptions: the zeroed state comes from the domain type's own identified-and-otherwise-zeroed
     * constructor, the same instrument the service under test uses, so this stand-in cannot assert a shape the
     * service does not produce. The customer identifier is zero because that field is sourced from a
     * cross-context read this context does not perform.
     *
     * @return a mapped list view carrying a zeroed summary block, no rows and no boundary cursors, never
     *     {@code null}
     */
    private PendingAuthListView zeroedListView() {
        return this.mapper.toListView(new PendingAuthSummary(ACCOUNT_ID, 0L), List.of(), false,
                null, SUBJECT, null);
    }

    /**
     * Builds the authorization row most bodies in this class are rendered from.
     *
     * @return a fully populated approved authorization row, never {@code null}
     */
    private static PendingAuthDetail row() {
        return rowAt(AUTH_TIME);
    }

    /**
     * Builds the shared authorization row at a chosen time component of its key.
     *
     * <p>Assumptions: only the time component varies, because it is the part of the key that orders rows
     * within one day and is therefore the smallest change that yields distinct rows -- and so distinct sealed
     * selectors -- for a multi-row page.
     *
     * @param authTime the composed time component of the key, positionally hours, minutes, seconds and
     *     milliseconds
     * @return a fully populated approved authorization row keyed at that time, never {@code null}
     */
    private static PendingAuthDetail rowAt(int authTime) {
        return row(authTime, RESPONSE_CODE_APPROVED, APPROVED_REASON);
    }

    /**
     * Builds the shared authorization row carrying a chosen stored response code and reason.
     *
     * @param authRespCode the two-character response code as the segment would hold it; must not be
     *     {@code null}
     * @param authRespReason the four-character response reason as the segment would hold it; must not be
     *     {@code null}
     * @return a fully populated authorization row carrying that code and reason, never {@code null}
     */
    private static PendingAuthDetail rowWith(String authRespCode, String authRespReason) {
        return row(AUTH_TIME, authRespCode, authRespReason);
    }

    /**
     * Builds an authorization row at a chosen key time, response code and response reason.
     *
     * <p>Assumptions: the identification code and the processing code are given DIFFERENT values, so a case
     * asserting that the six-position screen member carries the processing code cannot pass by coincidence.
     * The stored dates and times are the year-first and hour-first forms the segment holds, so the display
     * rules have something to re-order.
     *
     * @param authTime the composed time component of the key, positionally hours, minutes, seconds and
     *     milliseconds
     * @param authRespCode the two-character response code as the segment would hold it; must not be
     *     {@code null}
     * @param authRespReason the four-character response reason as the segment would hold it; must not be
     *     {@code null}
     * @return a fully populated authorization row, never {@code null}
     */
    private static PendingAuthDetail row(int authTime, String authRespCode, String authRespReason) {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, authTime),
                STORED_ORIG_DATE, STORED_ORIG_TIME, CARD_NUMBER, "0100", "2712", "0100", "0000",
                AUTH_ID_CODE, authRespCode, authRespReason, PROCESSING_CODE,
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }
}
