package com.carddemo.authorization.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.authorization.dto.FraudMarkRequest;
import com.carddemo.authorization.dto.FraudMarkResponse;
import com.carddemo.authorization.service.FraudMarkingService;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.validation.FieldValidationFlag;
import java.lang.reflect.RecordComponent;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the HTTP boundary of the one write this context publishes: its route, its two verbatim
 * outcome sentences, the status code that tells its two write paths apart, the closed domain of its
 * toggle, and the shape of a refusal.
 *
 * <p><strong>Purpose.</strong> This class covers {@link FraudController} and its single operation
 * {@code PUT /api/v1/authorizations/&#123;key&#125;/fraud}, the migrated face of
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl}. It asserts routing, body and selector
 * binding, delegation, status selection and the rendered problem body. It asserts nothing about the
 * rules behind the write, the tables under it or the codecs beside it, for the reasons the package
 * charter states as ownership boundaries.
 *
 * <h2>The reference program is reached by a call, not by a terminal, and the whole test is shaped by it</h2>
 *
 * <p>Refactoring Rationale: the write is asserted as ONE service call, and the reference structure it
 * replaces is a call whose outcome the caller had to remember to inspect.
 * {@code cbl/COPAUS1C.cbl} reaches the fraud writer with {@code EXEC CICS LINK} at L248, naming
 * {@code PROGRAM(WS-PGM-AUTH-FRAUD)} at L249 and passing {@code COMMAREA(WS-FRAUD-DATA)} at L250, and
 * it adds {@code NOHANDLE} at L251 before closing at L252. {@code NOHANDLE} suppresses the condition
 * handler, so the outcome is legible only where the caller tests it by hand, which that program then
 * does at L253 with {@code IF EIBRESP = DFHRESP(NORMAL)} and again at L254 with
 * {@code IF WS-FRD-UPDT-SUCCESS}. A caller omitting either test proceeds as though the write had
 * succeeded. The target replaces both tests with one in-process call inside one transaction declared on
 * {@code FraudMarkingService.mark(...)}, where the detail-segment update and the fraud-row write commit
 * or roll back together and a failure arrives as a propagated exception rather than as a value to
 * inspect. The reference needs two commits held open by hand -- {@code TAKE-SYNCPOINT} at
 * {@code cbl/COPAUS1C.cbl} L557 to L559 and {@code ROLL-BACK} at L565 to L568 -- because its two writes
 * land in two different managers.
 *
 * <p>Assumptions: reading that {@code EXEC CICS LINK} as an in-process call rather than as a network
 * hop is what transformation rule T5 requires, and the reference resource definitions corroborate it
 * from five directions. {@code csd/CRDDEMO2.csd} L32 to L38 defines {@code PROGRAM(COPAUS2C)} and that
 * block DOES carry {@code TRANSID(CPVD)} at L36 as a program attribute; what is absent is a
 * {@code DEFINE TRANSACTION} naming {@code COPAUS2C} as its {@code PROGRAM(...)}, and that file declares
 * exactly three such stanzas -- {@code CPVD} at L39 over {@code PROGRAM(COPAUS1C)} at L40, {@code CPVS}
 * at L49 over {@code PROGRAM(COPAUS0C)} at L50 and {@code CP00} at L59 over {@code PROGRAM(COPAUA0C)} at
 * L60, each electing {@code ACTION(BACKOUT)} at L45, L55 and L65. {@code DEFINE DB2TRAN(CPVDTRAN)} at
 * L75 to L79 then binds {@code ENTRY(AWS01PLN) TRANSID(CPVD)} at L77, so the writer draws its database
 * thread from {@code CPVD} rather than from one of its own. That file declares only two mapsets,
 * {@code COPAU00} at L1 and {@code COPAU01} at L6, and neither belongs to the writer, which sends no map
 * and simply ends at {@code EXEC CICS RETURN} on {@code cbl/COPAUS2C.cbl} L218. Its whole interface is
 * the 272-byte {@code DFHCOMMAREA} its linkage section opens at L74. A terminal could therefore never
 * start it.
 *
 * <p>Assumptions: the plan name cited above is {@code AWS01PLN}, taken from {@code csd/CRDDEMO2.csd}
 * L69 to L74, and the extension {@code README.md} names that entry {@code DB201PLN} instead. The
 * definition file is preferred, and the reason is the kind of artifact each one is rather than a
 * preference between them: the README spells the command with the abbreviated operator verb and installs
 * it through the terminal transaction, so it records an operator PROCEDURE, whereas the definition file
 * spells the full verb and carries its own {@code DEFINETIME} stamps, so it is the deployed resource
 * definition. A transcript of a procedure can drift from the artifact it provisions; the artifact cannot
 * drift from itself. The two agree on {@code CPVDTRAN} and on {@code CPVD} and differ on the entry name
 * alone, so nothing else in this file depends on the choice.
 *
 * <p>Assumptions: two further attributes of that database entry describe the reference deployment and
 * are deliberately NOT modelled in the target, because reading either as inherited behaviour would be
 * wrong. {@code THREADLIMIT(1)} at L72 belongs to {@code DEFINE DB2ENTRY(AWS01PLN)} and not to the
 * writer, so no case here constrains the target to one concurrent write. {@code AUTHTYPE(USERID)} at
 * L71 selects which identity the reference presents to its database, which maps to the target's database
 * ROLE and not to the caller's identity, so it is not the group claim and nothing here treats it as one.
 *
 * <h2>The server is assembled standalone, and the authority matrix is asserted elsewhere</h2>
 *
 * <p>Refactoring Rationale: the server is assembled STANDALONE rather than through a sliced application
 * context, which is the convention both classes in this package follow. This module's
 * {@code config/SecurityConfig} builds its decoder from an issuer location, and that resolves the
 * issuer's discovery document EAGERLY at bean construction, so any context including that configuration
 * reaches the network from a unit test -- against a host the test profile points somewhere unresolvable
 * on purpose. A standalone server exercises exactly what this class is answerable for and reaches
 * nothing.
 *
 * <p>Trade-offs: that assembly is why the administrative authority on this route is NOT asserted here,
 * and this is the boundary most easily mistaken for an omission. A standalone server installs no filter
 * chain at all, so the principal is supplied on the request builder and every request in this class is
 * already past any gate. An assertion here that the route was reachable WITH an authority would
 * therefore say nothing whatever about whether it is reachable WITHOUT one, and publishing it would read
 * as a guarantee that had not been tested. The authority is declared once against path prefixes in
 * {@code com.carddemo.authorization.config.SecurityConfig} and asserted against the installed decision
 * object in that package's own test. What is given up is local self-containment: a reader of this file
 * cannot see the proof that the route is protected, and has to follow the named owner to find it. What
 * is bought is that the guarantee is asserted where it is actually observable. The group names the
 * decision uses are {@link JwtRoleConverter#ADMIN_AUTHORITY} and {@link JwtRoleConverter#USER_AUTHORITY},
 * carried from the {@link JwtRoleConverter#GROUPS_CLAIM} claim, and this class uses those names only to
 * prove a caller cannot smuggle one in a request body.
 *
 * <p>Trade-offs: the shared advice IS registered on the standalone server, at the cost of exercising a
 * collaborator from another module inside a test named for this controller. Half of what this route
 * publishes is the SHAPE of a refusal, and a standalone server auto-detects no
 * {@code @RestControllerAdvice} from {@code services/common-lib}; without registering it a refusal would
 * surface as a raised exception rather than as the problem body a client parses, and the per-field array
 * the contract promises would go unasserted. The cost accepted is that a change in that shared advice can
 * fail this class, which is the correct direction for the dependency to point, since this route's
 * published statuses are that advice's output.
 *
 * <h2>Where the assertions in this file can and cannot come from</h2>
 *
 * <p>Assumptions: no end-to-end golden master exists for this route, and the limit is recorded rather
 * than worked around. {@code tests/README.md} L83 to L85 states that the online programs cannot run
 * end-to-end without a transaction runtime, which the runner does not have, and that only their
 * extractable field-validation logic is unit-tested. {@code COPAUS2C} is one of those online programs,
 * so there is no captured output to compare a response against and nothing here should be read as
 * implying one. What remains directly verifiable is exactly what this class rests on: the four sentences
 * and the declared widths are readable in the program source, and they are asserted character for
 * character and count for count.
 *
 * <p>Assumptions: this class consumes NO recorded byte image and NO test profile, and both absences are
 * stated because the alternative is a reader assuming otherwise. Every input here is a JSON literal
 * built in this file, so the module's fixture directory is untouched by these cases; and a standalone
 * server loads no application context, so {@code src/test/resources/application-test.yml} does not apply
 * to anything asserted here. The only externally supplied values are the two published sentence
 * constants and the shared error codes, each named through its own type rather than restated.
 *
 * <h2>Corrections to the planned shape of this class</h2>
 *
 * <p>Refactoring Rationale: the plan this class was authored against described a different contract from
 * the one on disk in three respects, and the differences are recorded here rather than left for a later
 * reader to rediscover. The migration plan settles the precedence: the repository is authoritative, and
 * where the two differ the repository wins and the difference is written down. First, the plan described
 * a request carrying five components -- an account identifier, a customer identifier, two key components
 * and the action. {@link FraudMarkRequest} carries the ACTION ALONE, and that is a stronger form of the
 * same decision rather than a weaker one: the plan's reason for omitting the reference's 200-byte
 * segment was that a client must not dictate what is persisted, and resolving the identifiers
 * server-side from the row extends that to every field the reference passed. Second, the plan described
 * the response's outcome flag as admitting the reference's success and failure values; the published
 * domain admits the success value alone, because failure leaves through a non-2xx problem body instead.
 * Third, the plan named the sliced web-layer annotation, which this module uses nowhere, for the eager
 * issuer-resolution reason given above.
 */
class FraudControllerTest {

    /**
     * The instant every rendered problem body in this class is stamped with.
     *
     * <p>Assumptions: the clock is FIXED rather than the system clock, and that is what makes the
     * rendered timestamp assertable. The reference reads a wall clock twice on this path -- the writer
     * asks the region for the time at {@code cbl/COPAUS2C.cbl} L91 to L100 and moves it to the report
     * date at L101, and both of its statements set {@code FRAUD_RPT_DATE = CURRENT DATE}, at L194 on the
     * insert and L225 on the update -- so a target that read a clock implicitly would render a different
     * body on every run and could not be asserted at all.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-06T09:20:00Z"), ZoneOffset.UTC);

    /**
     * The rendering the fixed clock above produces in a problem body.
     *
     * <p>Assumptions: 26 positions in the form the shared kernel publishes, which is the width the
     * migration carries for a timestamp. It is written out rather than derived so that a change to the
     * renderer fails this class instead of quietly agreeing with it.
     */
    private static final String REFUSAL_TIMESTAMP = "2026-08-06 09:20:00.000000";

    /** The route the published contract names, with a placeholder for the sealed selector. */
    private static final String FRAUD_ROUTE = "/api/v1/authorizations/{key}/fraud";

    /**
     * The authenticated principal the controller reads the selector's binding subject from.
     *
     * <p>Assumptions: a standalone server runs no security chain, so the principal is supplied on the
     * request builder. The handler passes its name to the service, which is the value these cases stub
     * against, so a request carrying none fails on an absent principal rather than on the property under
     * assertion.
     */
    private static final String SUBJECT = "authorization-operator";

    /** The principal instance every request in this class is performed as. */
    private static final Principal PRINCIPAL = () -> SUBJECT;

    /**
     * A selector of the published shape, used where redemption is the service's to perform.
     *
     * <p>Assumptions: this is a shape-valid token and NOT a redeemable one -- its authentication code is
     * filler. Nothing here redeems it, the service being a double, so what these cases assert is that a
     * value of the published shape reaches the handler rather than being refused by the path constraint.
     */
    private static final String SEALED_SHAPE_SELECTOR =
            "v1.MDAwMDAwMDAwMTE6MjYyMTU6OTE2NDQ5MDI."
                    + "0123456789012345678901234567890123456789012";

    /**
     * The positions the reference declares for the outcome message, fifty.
     *
     * <p>Assumptions: read from {@code WS-FRD-ACT-MSG PIC X(50)} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} L86. This is the width of the area the
     * reference writer reports through, and it is the one this route's message belongs to.
     */
    private static final int ACTION_MESSAGE_WIDTH = 50;

    /**
     * The positions the reference detail SCREEN declares for its message line, seventy-eight.
     *
     * <p>Assumptions: named here only so that the fifty above can be asserted DISTINCT from it. The
     * screen field is fed from {@code WS-MESSAGE PIC X(80)} at {@code cbl/COPAUS1C.cbl} L37 and is
     * therefore two positions narrower than its source. Sizing this route's message from the screen
     * regime would admit a value 28 positions longer than the field it migrates can hold.
     */
    private static final int SCREEN_MESSAGE_WIDTH = 78;

    /**
     * The positions the reference screen program declares for its own message item, eighty.
     *
     * <p>Assumptions: read from {@code WS-MESSAGE PIC X(80)} at {@code cbl/COPAUS1C.cbl} L37, which is
     * where this route's fifty-position sentence is moved to at L257 when the write fails. Named for the
     * same distinctness assertion.
     */
    private static final int SCREEN_ITEM_WIDTH = 80;

    /**
     * The positions {@code WS-SQLCODE PIC +9(06)} renders into, seven.
     *
     * <p>Assumptions: declared at {@code cbl/COPAUS2C.cbl} L55. A leading sign position plus six digit
     * positions is seven, and the count matters because it is one of the four terms that close the
     * fifty-position field exactly.
     */
    private static final int SQLCODE_RENDERED_WIDTH = 7;

    /**
     * The positions {@code WS-SQLSTATE PIC +9(09)} renders into, ten.
     *
     * <p>Assumptions: declared at {@code cbl/COPAUS2C.cbl} L56, a sign position plus nine digits.
     */
    private static final int SQLSTATE_RENDERED_WIDTH = 10;

    /**
     * The leading literal of the reference INSERT-failure sentence, carried character for character.
     *
     * <p>Assumptions: from the {@code STRING} at {@code cbl/COPAUS2C.cbl} L211. Its single leading space
     * is part of the literal and is preserved; measured, it is 24 positions.
     */
    private static final String INSERT_FAILURE_PREFIX = " SYSTEM ERROR DB2: CODE:";

    /**
     * The leading literal of the reference UPDATE-failure sentence, carried character for character.
     *
     * <p>Assumptions: from the {@code STRING} at {@code cbl/COPAUS2C.cbl} L239, likewise with one
     * leading space; measured, it is 22 positions.
     */
    private static final String UPDATE_FAILURE_PREFIX = " UPDT ERROR DB2: CODE:";

    /**
     * The separator literal both reference failure sentences share.
     *
     * <p>Assumptions: from {@code cbl/COPAUS2C.cbl} L212 and L240. Its shape is particular -- the code
     * abuts the preceding literal with no separating space, and only this separator carries a trailing
     * one -- so it is carried whole rather than reassembled.
     */
    private static final String FAILURE_STATE_SEPARATOR = ", STATE: ";

    /**
     * The reference command that reports an authorization as fraudulent.
     *
     * <p>Assumptions: {@code 88 WS-REPORT-FRAUD VALUE 'F'} at {@code cbl/COPAUS2C.cbl} L81.
     */
    private static final String ACTION_REPORT_FRAUD = "F";

    /**
     * The reference command that withdraws a fraud report.
     *
     * <p>Assumptions: {@code 88 WS-REMOVE-FRAUD VALUE 'R'} at {@code cbl/COPAUS2C.cbl} L82. Its
     * existence is why this operation sets a state rather than raising a one-way flag.
     */
    private static final String ACTION_REMOVE_FRAUD = "R";

    /**
     * The reference value that means the update FAILED, which shares a character with the report command.
     *
     * <p>Assumptions: {@code 88 WS-FRD-UPDT-FAILED VALUE 'F'} at {@code cbl/COPAUS2C.cbl} L85, declared
     * on {@code WS-FRD-UPDATE-STATUS} at L83 and therefore a different field from the action at L80.
     * Named separately from {@link #ACTION_REPORT_FRAUD} even though the two strings are equal, because
     * collapsing them into one constant is the exact mistake the collision invites.
     */
    private static final String UPDATE_STATUS_FAILED = "F";

    /** The service double, so this class asserts the boundary rather than the behaviour behind it. */
    private FraudMarkingService marking;

    /** The standalone server under test. */
    private MockMvc mockMvc;

    /** The codec the standalone server binds and renders every body with. */
    private JsonMapper jsonMapper;

    /**
     * Assembles the standalone server over the service double, the shared advice and the money codec.
     *
     * <p>Assumptions: the money module is registered even though neither body on this route carries an
     * amount -- {@link FraudMarkRequest} declares one component and {@link FraudMarkResponse} two, none
     * of them monetary. The converter built here is the one the server uses for every body, and
     * registering it selectively per test class is how one service ends up rendering through two
     * different codecs.
     */
    @BeforeEach
    void setUp() {
        this.marking = mock(FraudMarkingService.class);
        this.jsonMapper = JsonMapper.builder().addModule(new MoneyModule()).build();
        this.mockMvc = MockMvcBuilders.standaloneSetup(new FraudController(this.marking))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(this.jsonMapper))
                .setControllerAdvice(new GlobalExceptionHandler(FIXED_CLOCK))
                .build();
    }

    /**
     * Renders a request body naming one fraud action and nothing else.
     *
     * @param action the one-character fraud command to place in the body's {@code action} member
     * @return the JSON body the published request schema describes
     */
    private static String bodyJson(String action) {
        return """
                {"action":"%s"}""".formatted(action);
    }

    /**
     * Stubs the service double to report a successful write with the stated carrier flag.
     *
     * @param body the success body the service is to compose, either the insert or the update sentence
     * @param created {@code true} to report that the fraud row was inserted, {@code false} to report
     *     that an existing row was replaced
     */
    private void stubWrite(FraudMarkResponse body, boolean created) {
        when(this.marking.mark(eq(SEALED_SHAPE_SELECTOR), any(FraudMarkRequest.class), eq(SUBJECT)))
                .thenReturn(new FraudMarkingService.FraudMarkOutcome(body, created));
    }

    /**
     * Performs the published write with the canonical selector, principal and content type.
     *
     * @param body the raw JSON request body to send, passed through unaltered so that a case may send a
     *     body the published schema does not describe
     * @return the completed result, so a case may assert on the response, the session or the body
     * @throws Exception if the request cannot be performed
     */
    private MvcResult performWrite(String body) throws Exception {
        return this.mockMvc.perform(put(FRAUD_ROUTE, SEALED_SHAPE_SELECTOR)
                        .principal(PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /**
     * Lists the component names a record type declares, in declaration order.
     *
     * @param recordType the record type to inspect, which must be a record
     * @return the declared component names in order, so a case may assert arity and membership together
     */
    private static List<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Reassembles what the reference {@code STRING} statement would leave in the fifty-position field.
     *
     * <p>Assumptions: the reference concatenates its parts with {@code DELIMITED BY SIZE}, so every part
     * contributes its DECLARED width rather than its trimmed value, and the receiving field is then
     * blank-filled to its own width. This helper reproduces that arithmetic from the declared widths
     * alone, which is why it takes no sample code or state value: what is under assertion is the width
     * closure, not any particular database reply.
     *
     * <p>Assumptions: the two numeric parts are stood in for by a sign followed by digits rather than by
     * blanks, because {@code PIC +9(06)} at {@code cbl/COPAUS2C.cbl} L55 and {@code PIC +9(09)} at L56
     * are SIGNED edited fields, which always render a sign position and a digit in every digit position.
     * Standing them in with blanks would occupy the same width but would make a trailing-blank assertion
     * meaningless, since the padding a caller wants to detect could then not be told apart from the value.
     *
     * @param prefix the leading literal of the sentence, carried from the reference program
     * @return the sentence as the reference would leave it in {@code WS-FRD-ACT-MSG}, blank-filled to
     *     fifty positions
     */
    private static String referenceFailureSentence(String prefix) {
        String renderedCode = "+" + "0".repeat(SQLCODE_RENDERED_WIDTH - 1);
        String renderedState = "+" + "0".repeat(SQLSTATE_RENDERED_WIDTH - 1);
        String assembled = prefix + renderedCode + FAILURE_STATE_SEPARATOR + renderedState;
        return assembled.length() >= ACTION_MESSAGE_WIDTH
                ? assembled
                : assembled + " ".repeat(ACTION_MESSAGE_WIDTH - assembled.length());
    }

    /**
     * The four reference sentences and the fifty-position field they are reported through.
     *
     * <p>Assumptions: the reference writer reports every outcome through one field,
     * {@code WS-FRD-ACT-MSG PIC X(50)} at {@code cbl/COPAUS2C.cbl} L86, and composes four distinct
     * values into it. Two are successes moved whole -- {@code 'ADD SUCCESS'} at L201 and
     * {@code 'UPDT SUCCESS'} at L232 -- and two are failures assembled by {@code STRING}, at L211 to L213
     * and at L239 to L241. These cases assert the successes as published constants and the failures as
     * measured widths, because only the successes cross this boundary.
     */
    @Nested
    @DisplayName("the reference sentences and the fifty-position field")
    class ReferenceSentencesAndWidths {

        /**
         * The insert sentence is carried character for character with no leading or trailing space.
         *
         * <p>Assumptions: {@code MOVE 'ADD SUCCESS' TO WS-FRD-ACT-MSG} at {@code cbl/COPAUS2C.cbl} L201
         * moves an eleven-character literal, and transformation rule T8 carries user-visible strings
         * across unaltered. The absence of a leading space is asserted POSITIVELY because the two failure
         * sentences in the same program each have one, so a reader cannot tell by pattern which do.
         */
        @Test
        @DisplayName("the insert sentence is eleven characters with no surrounding space")
        void insertSentenceIsCarriedCharacterForCharacter() {
            assertThat(FraudMarkResponse.MESSAGE_ADD_SUCCESS).isEqualTo("ADD SUCCESS");
            assertThat(FraudMarkResponse.MESSAGE_ADD_SUCCESS).hasSize(11);
            assertThat(FraudMarkResponse.MESSAGE_ADD_SUCCESS).doesNotStartWith(" ");
            assertThat(FraudMarkResponse.MESSAGE_ADD_SUCCESS).doesNotEndWith(" ");
        }

        /**
         * The update sentence is carried character for character, abbreviation included.
         *
         * <p>Assumptions: {@code MOVE 'UPDT SUCCESS' TO WS-FRD-ACT-MSG} at {@code cbl/COPAUS2C.cbl} L232
         * moves a twelve-character literal. The abbreviation is the reference program's own and is
         * asserted unexpanded, because expanding it would alter a string a user reads.
         */
        @Test
        @DisplayName("the update sentence is twelve characters with the reference abbreviation intact")
        void updateSentenceIsCarriedCharacterForCharacter() {
            assertThat(FraudMarkResponse.MESSAGE_UPDATE_SUCCESS).isEqualTo("UPDT SUCCESS");
            assertThat(FraudMarkResponse.MESSAGE_UPDATE_SUCCESS).hasSize(12);
            assertThat(FraudMarkResponse.MESSAGE_UPDATE_SUCCESS).doesNotStartWith(" ");
        }

        /**
         * Both success sentences fit the fifty positions the reference field declares.
         *
         * <p>Assumptions: the field is the bound, not the longer of the two wordings, so the assertion is
         * made against fifty rather than against twelve. A future sentence added to this route would then
         * fail here if it exceeded the field it migrates.
         */
        @Test
        @DisplayName("both success sentences fit the fifty declared positions")
        void bothSuccessSentencesFitTheDeclaredField() {
            assertThat(FraudMarkResponse.MESSAGE_ADD_SUCCESS.length())
                    .isLessThanOrEqualTo(ACTION_MESSAGE_WIDTH);
            assertThat(FraudMarkResponse.MESSAGE_UPDATE_SUCCESS.length())
                    .isLessThanOrEqualTo(ACTION_MESSAGE_WIDTH);
            assertThat(FraudMarkResponse.MESSAGE_ADD_SUCCESS)
                    .isNotEqualTo(FraudMarkResponse.MESSAGE_UPDATE_SUCCESS);
        }

        /**
         * The reference insert-failure sentence closes the fifty-position field exactly, with no slack.
         *
         * <p>Assumptions: the four terms are measured from their declarations rather than estimated. The
         * leading literal at {@code cbl/COPAUS2C.cbl} L211 is 24 positions; {@code WS-SQLCODE PIC +9(06)}
         * at L55 renders 7; the separator at L212 is 9; and {@code WS-SQLSTATE PIC +9(09)} at L56 renders
         * 10. Those sum to exactly 50, which is the declared width at L86. A field its own declaring
         * program fills exactly is a contract rather than a reading, so this case asserts the closure term
         * by term and then asserts that nothing is left over.
         */
        @Test
        @DisplayName("the insert-failure sentence closes the fifty positions exactly")
        void insertFailureSentenceClosesTheFieldExactly() {
            assertThat(INSERT_FAILURE_PREFIX).hasSize(24);
            assertThat(FAILURE_STATE_SEPARATOR).hasSize(9);

            int closure = INSERT_FAILURE_PREFIX.length() + SQLCODE_RENDERED_WIDTH
                    + FAILURE_STATE_SEPARATOR.length() + SQLSTATE_RENDERED_WIDTH;

            assertThat(closure).isEqualTo(ACTION_MESSAGE_WIDTH);
            assertThat(ACTION_MESSAGE_WIDTH - closure).isZero();
            assertThat(referenceFailureSentence(INSERT_FAILURE_PREFIX))
                    .hasSize(ACTION_MESSAGE_WIDTH)
                    .doesNotEndWith(" ");
        }

        /**
         * The reference update-failure sentence reaches forty-eight positions, so two blanks are padded.
         *
         * <p>Assumptions: the same arithmetic with the shorter literal at {@code cbl/COPAUS2C.cbl} L239,
         * which measures 22 positions, gives 48 of the 50 declared. A COBOL {@code MOVE} into a
         * fixed-width alphanumeric field blank-fills the remainder, so the value the reference leaves in
         * the field ends in exactly two blanks. Asserting the pad is what distinguishes this sentence from
         * the one above, which has none.
         */
        @Test
        @DisplayName("the update-failure sentence reaches forty-eight of fifty and pads two blanks")
        void updateFailureSentenceLeavesTwoTrailingBlanks() {
            assertThat(UPDATE_FAILURE_PREFIX).hasSize(22);

            int assembled = UPDATE_FAILURE_PREFIX.length() + SQLCODE_RENDERED_WIDTH
                    + FAILURE_STATE_SEPARATOR.length() + SQLSTATE_RENDERED_WIDTH;

            assertThat(assembled).isEqualTo(48);
            assertThat(ACTION_MESSAGE_WIDTH - assembled).isEqualTo(2);

            String padded = referenceFailureSentence(UPDATE_FAILURE_PREFIX);
            assertThat(padded).hasSize(ACTION_MESSAGE_WIDTH).endsWith("  ");
            assertThat(padded.substring(0, assembled)).doesNotEndWith(" ");
            assertThat(padded.stripTrailing()).hasSize(assembled);
        }

        /**
         * Both reference failure sentences carry exactly one leading space, and neither success does.
         *
         * <p>Assumptions: the leading space is part of each literal at {@code cbl/COPAUS2C.cbl} L211 and
         * L239 and is not incidental formatting, so it is asserted as present and asserted as SINGLE. A
         * second space would shift the whole sentence and break the closure asserted above.
         */
        @Test
        @DisplayName("both failure sentences carry exactly one leading space and neither success does")
        void failureSentencesCarryExactlyOneLeadingSpace() {
            assertThat(INSERT_FAILURE_PREFIX).startsWith(" ").doesNotStartWith("  ");
            assertThat(UPDATE_FAILURE_PREFIX).startsWith(" ").doesNotStartWith("  ");
            assertThat(FraudMarkResponse.MESSAGE_ADD_SUCCESS).doesNotStartWith(" ");
            assertThat(FraudMarkResponse.MESSAGE_UPDATE_SUCCESS).doesNotStartWith(" ");
        }

        /**
         * The fifty-position regime is distinct from the screen regimes and from the house width.
         *
         * <p>Assumptions: four message widths circulate around this flow and conflating any two of them
         * is a silent error, so their distinctness is asserted rather than assumed. Fifty is this route's,
         * from {@code WS-FRD-ACT-MSG PIC X(50)} at {@code cbl/COPAUS2C.cbl} L86. Seventy-eight is the
         * detail screen's message line, fed from the eighty-position {@code WS-MESSAGE} at
         * {@code cbl/COPAUS1C.cbl} L37 and truncated by two on the way to the terminal. Seventy-five is
         * the house rendering width the shared kernel publishes. The two screen regimes meet this one in
         * this very flow, because the reference moves this route's fifty-position sentence into that
         * eighty-position item at {@code cbl/COPAUS1C.cbl} L257 when the write fails, which is precisely
         * why they must not be merged.
         */
        @Test
        @DisplayName("the fifty-position regime is distinct from seventy-eight, eighty and the house width")
        void theFiftyPositionRegimeIsDistinctFromTheOthers() {
            assertThat(ACTION_MESSAGE_WIDTH).isNotEqualTo(SCREEN_MESSAGE_WIDTH);
            assertThat(ACTION_MESSAGE_WIDTH).isNotEqualTo(SCREEN_ITEM_WIDTH);
            assertThat(ACTION_MESSAGE_WIDTH).isNotEqualTo(ApiError.MESSAGE_RENDERING_WIDTH);
            assertThat(List.of(ACTION_MESSAGE_WIDTH, SCREEN_MESSAGE_WIDTH, SCREEN_ITEM_WIDTH,
                            ApiError.MESSAGE_RENDERING_WIDTH))
                    .doesNotHaveDuplicates();
            assertThat(SCREEN_ITEM_WIDTH - SCREEN_MESSAGE_WIDTH).isEqualTo(2);
        }

        /**
         * No database diagnostic reaches the message this route returns, on either write path.
         *
         * <p>Assumptions: the baseline assembles the raw reply code and state into a user-visible field
         * at {@code cbl/COPAUS2C.cbl} L211 to L213 and L239 to L241; the Java publishes only the
         * human-readable sentence on this body and reports a failure as a non-2xx problem instead, and the
         * divergence is registered in the migration's traceability document rather than left implicit.
         * These cases assert the absence on the SERIALISED body of both success paths, since that is where
         * a diagnostic would leak if a future edit routed one through.
         *
         * @throws Exception if either request cannot be performed
         */
        @Test
        @DisplayName("no database diagnostic reaches the returned message on either write path")
        void noDatabaseDiagnosticReachesTheReturnedMessage() throws Exception {
            stubWrite(FraudMarkResponse.added(), true);
            String created = performWrite(bodyJson(ACTION_REPORT_FRAUD))
                    .getResponse().getContentAsString();

            stubWrite(FraudMarkResponse.updated(), false);
            String replaced = performWrite(bodyJson(ACTION_REMOVE_FRAUD))
                    .getResponse().getContentAsString();

            assertThat(created).contains(FraudMarkResponse.MESSAGE_ADD_SUCCESS);
            assertThat(replaced).contains(FraudMarkResponse.MESSAGE_UPDATE_SUCCESS);
            for (String body : List.of(created, replaced)) {
                assertThat(body)
                        .doesNotContain(INSERT_FAILURE_PREFIX.trim())
                        .doesNotContain(UPDATE_FAILURE_PREFIX.trim())
                        .doesNotContain(FAILURE_STATE_SEPARATOR.trim())
                        .doesNotContain("SQLCODE")
                        .doesNotContain("SQLSTATE");
            }
        }
    }

    /**
     * The operation sets a fraud state in either direction and admits no third value.
     *
     * <p>Assumptions: the reference exercises both directions rather than only setting a flag. Its
     * command field {@code WS-FRD-ACTION PIC X(01)} at {@code cbl/COPAUS2C.cbl} L80 declares
     * {@code 'F'} at L81 and {@code 'R'} at L82 and nothing else, and the calling screen inverts the
     * stored state before it calls: {@code MARK-AUTH-FRAUD} at {@code cbl/COPAUS1C.cbl} L236 to L242
     * tests the confirmed condition and sets the removed one when it holds, otherwise setting the
     * confirmed one. A one-way mark would leave the withdrawal path unreachable.
     */
    @Nested
    @DisplayName("the fraud action is a two-valued toggle, not a one-way mark")
    class FraudActionToggle {

        /**
         * The report direction reaches the service carrying the character the body named.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the report direction reaches the service as the reference report command")
        void reportDirectionReachesTheService() throws Exception {
            stubWrite(FraudMarkResponse.added(), true);

            performWrite(bodyJson(ACTION_REPORT_FRAUD));

            ArgumentCaptor<FraudMarkRequest> captured = ArgumentCaptor.forClass(FraudMarkRequest.class);
            verify(FraudControllerTest.this.marking).mark(eq(SEALED_SHAPE_SELECTOR), captured.capture(), eq(SUBJECT));
            assertThat(captured.getValue().action()).isEqualTo(ACTION_REPORT_FRAUD);
        }

        /**
         * The withdraw direction reaches the service carrying the character the body named.
         *
         * <p>Assumptions: asserted separately from the report direction rather than parameterised with
         * it, because the two are different reference commands reaching different reference arms, and a
         * shared case would pass if the handler ignored the body and always forwarded one of them.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the withdraw direction reaches the service as the reference remove command")
        void withdrawDirectionReachesTheService() throws Exception {
            stubWrite(FraudMarkResponse.updated(), false);

            performWrite(bodyJson(ACTION_REMOVE_FRAUD));

            ArgumentCaptor<FraudMarkRequest> captured = ArgumentCaptor.forClass(FraudMarkRequest.class);
            verify(FraudControllerTest.this.marking).mark(eq(SEALED_SHAPE_SELECTOR), captured.capture(), eq(SUBJECT));
            assertThat(captured.getValue().action()).isEqualTo(ACTION_REMOVE_FRAUD);
            assertThat(ACTION_REMOVE_FRAUD).isNotEqualTo(ACTION_REPORT_FRAUD);
        }

        /**
         * A third character is refused before the service is reached at all.
         *
         * <p>Assumptions: the refused value is the response's own success character specifically, because
         * that is what a caller or a mapper confusing the two adjacent reference fields would send. The
         * domain is closed by the two condition names at {@code cbl/COPAUS2C.cbl} L81 and L82 and by
         * nothing else, so a third character is a value the reference cannot represent rather than an
         * unhandled case.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a third action character is refused before the service is reached")
        void thirdActionCharacterIsRefusedBeforeTheService() throws Exception {
            FraudControllerTest.this.mockMvc.perform(put(FRAUD_ROUTE, SEALED_SHAPE_SELECTOR)
                            .principal(PRINCIPAL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyJson(FraudMarkResponse.UPDATE_STATUS_SUCCESS)))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(FraudControllerTest.this.marking);
        }

        /**
         * The requested action and the reported outcome never share a Java type.
         *
         * <p>Assumptions: {@code 'F'} carries three unrelated meanings across the two reference programs
         * and the collision is the migration's hazard rather than the baseline's, because a COBOL
         * condition name tests only the field it was declared under. The requested ACTION at
         * {@code cbl/COPAUS2C.cbl} L80 reads {@code 'F'} as report fraud at L81; the immediately adjacent
         * OUTCOME at L83 reads the same character as failed at L85; and the STORED STATE
         * {@code PA-AUTH-FRAUD} at {@code cpy/CIPAUDTY.cpy} L50 reads it as confirmed at L51. This case
         * asserts the separation structurally rather than by inspection: neither record declares the
         * other's member, so no assignment between them can compile.
         */
        @Test
        @DisplayName("the requested action and the reported outcome are members of different types")
        void requestedActionAndReportedOutcomeNeverShareAType() {
            assertThat(componentNames(FraudMarkRequest.class))
                    .contains("action")
                    .doesNotContain("updateStatus");
            assertThat(componentNames(FraudMarkResponse.class))
                    .contains("updateStatus")
                    .doesNotContain("action");
            assertThat(FraudMarkRequest.class).isNotEqualTo(FraudMarkResponse.class);

            // WHY : Assumptions: the two characters are equal as strings, which is exactly why the
            //       constants are declared separately above and compared here. The domains they belong to
            //       are disjoint -- the outcome admits only the success character, so the action's own
            //       report character is not a value this response can ever carry.
            assertThat(UPDATE_STATUS_FAILED).isEqualTo(ACTION_REPORT_FRAUD);
            assertThat(FraudMarkResponse.UPDATE_STATUS_SUCCESS)
                    .isNotEqualTo(ACTION_REPORT_FRAUD)
                    .isNotEqualTo(ACTION_REMOVE_FRAUD);
            assertThat(FraudMarkResponse.added().updateStatus())
                    .isEqualTo(FraudMarkResponse.UPDATE_STATUS_SUCCESS);
        }

        /**
         * The request carries the action alone, and the response carries exactly two members.
         *
         * <p>Assumptions: the arity is asserted rather than described because it is the mechanism behind
         * the guarantee in the next case. The reference passes a 272-byte area -- 11 positions for
         * {@code WS-ACCT-ID} at {@code cbl/COPAUS2C.cbl} L75, 9 for {@code WS-CUST-ID} at L76, 200 for the
         * {@code COPY CIPAUDTY} segment at L78 and 1, 1 and 50 for the group opened at L79 -- because its
         * caller is a sibling program inside one task. This caller is a browser, so the identifiers are
         * resolved server-side from the row being marked and the sealed selector in the path is the whole
         * address of that row.
         */
        @Test
        @DisplayName("the request declares one member and the response declares two")
        void requestDeclaresOneMemberAndResponseDeclaresTwo() {
            assertThat(componentNames(FraudMarkRequest.class)).containsExactly("action");
            assertThat(componentNames(FraudMarkResponse.class))
                    .containsExactly("updateStatus", "message");
        }

        /**
         * A client-supplied identifier, stored state or group claim in the body has no effect.
         *
         * <p>Assumptions: this is the security-relevant half of the arity above, and it is asserted on the
         * wire rather than inferred from it. The body sent here smuggles the two identifiers the reference
         * passed, the stored fraud state, and the group-claim name the authority decision reads; none of
         * them is a member the request declares, so the bound value still carries the action alone and the
         * subject the service receives is still the authenticated principal's. A caller therefore cannot
         * dictate what is persisted, cannot address a different row and cannot assert its own authority.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("smuggled identifiers, stored state and group claims in the body have no effect")
        void smuggledBodyMembersHaveNoEffect() throws Exception {
            stubWrite(FraudMarkResponse.added(), true);

            String smuggled = """
                    {"action":"%s","accountId":99999999999,"customerId":999999999,\
                    "authFraud":"%s","updateStatus":"%s","message":"%s",\
                    "%s":["%s"],"userType":"A"}"""
                    .formatted(ACTION_REPORT_FRAUD, ACTION_REMOVE_FRAUD, UPDATE_STATUS_FAILED,
                            INSERT_FAILURE_PREFIX, JwtRoleConverter.GROUPS_CLAIM,
                            JwtRoleConverter.ADMIN_AUTHORITY);

            MvcResult result = performWrite(smuggled);

            ArgumentCaptor<FraudMarkRequest> captured = ArgumentCaptor.forClass(FraudMarkRequest.class);
            verify(FraudControllerTest.this.marking).mark(eq(SEALED_SHAPE_SELECTOR), captured.capture(), eq(SUBJECT));
            assertThat(captured.getValue().action()).isEqualTo(ACTION_REPORT_FRAUD);
            assertThat(componentNames(FraudMarkRequest.class))
                    .doesNotContain("accountId", "customerId", "authFraud", "userType",
                            JwtRoleConverter.GROUPS_CLAIM);
            assertThat(result.getResponse().getContentAsString())
                    .contains(FraudMarkResponse.MESSAGE_ADD_SUCCESS)
                    .doesNotContain(INSERT_FAILURE_PREFIX.trim())
                    .doesNotContain(JwtRoleConverter.ADMIN_AUTHORITY);
        }
    }

    /**
     * A first-time mark and a re-mark are told apart, because the reference tells them apart.
     *
     * <p>Alternatives Considered: reporting the two write paths as one indistinguishable success, or
     * inferring which occurred from a bare verb or a boolean placed in the body. Both were evaluated and
     * rejected, because the reference's own discriminator is neither. It attempts an
     * {@code INSERT INTO CARDDEMO.AUTHFRDS} at {@code cbl/COPAUS2C.cbl} L142, tests the duplicate-key
     * reply with {@code IF SQLCODE = -803} at L203 and diverts at L204 to {@code FRAUD-UPDATE} at L221 --
     * an upsert -- and the two arms report DIFFERENT sentences, {@code 'ADD SUCCESS'} at L201 against
     * {@code 'UPDT SUCCESS'} at L232. A boolean would collapse two byte-distinct strings a user reads into
     * one value, and a bare verb could not express the second arm at all. The distinction is therefore
     * carried where the protocol already expresses created-against-replaced, on the status code, and the
     * two sentences are carried unaltered beside it.
     */
    @Nested
    @DisplayName("the insert and update write paths are distinguishable")
    class InsertVersusUpdateDiscriminator {

        /**
         * A created fraud row answers 201 carrying the reference insert sentence.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a created fraud row answers 201 with the reference insert sentence")
        void createdFraudRowAnswersCreated() throws Exception {
            stubWrite(FraudMarkResponse.added(), true);

            FraudControllerTest.this.mockMvc.perform(put(FRAUD_ROUTE, SEALED_SHAPE_SELECTOR)
                            .principal(PRINCIPAL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyJson(ACTION_REPORT_FRAUD)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.updateStatus")
                            .value(FraudMarkResponse.UPDATE_STATUS_SUCCESS))
                    .andExpect(jsonPath("$.message").value(FraudMarkResponse.MESSAGE_ADD_SUCCESS));
        }

        /**
         * A replaced fraud row answers 200 carrying the reference update sentence.
         *
         * <p>Assumptions: asserted separately from the created case because the body SHAPE is identical
         * across the two paths and the published contract deliberately carries no third member to
         * discriminate them. A single case covering both would pass while the handler answered one status
         * for both outcomes.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a replaced fraud row answers 200 with the reference update sentence")
        void replacedFraudRowAnswersOk() throws Exception {
            stubWrite(FraudMarkResponse.updated(), false);

            FraudControllerTest.this.mockMvc.perform(put(FRAUD_ROUTE, SEALED_SHAPE_SELECTOR)
                            .principal(PRINCIPAL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyJson(ACTION_REMOVE_FRAUD)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updateStatus")
                            .value(FraudMarkResponse.UPDATE_STATUS_SUCCESS))
                    .andExpect(jsonPath("$.message").value(FraudMarkResponse.MESSAGE_UPDATE_SUCCESS));
        }

        /**
         * The status follows the service's carrier flag and is never inferred from the sentence.
         *
         * <p>Assumptions: the combination stubbed here -- the created flag paired with the update sentence
         * -- is NOT a reachable production state, and it is used deliberately because it is the only
         * arrangement that can separate the two possible sources of the status. If the handler read the
         * sentence, this case would answer 200; because it reads the flag, it answers 201. Transformation
         * rule T8 carries user-visible strings across for display and for nothing else, so a status
         * inferred from one would make a display string load-bearing and let a later wording change alter
         * a status code silently.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the status follows the carrier flag rather than the sentence in the body")
        void statusFollowsTheCarrierFlagRatherThanTheSentence() throws Exception {
            stubWrite(FraudMarkResponse.updated(), true);

            FraudControllerTest.this.mockMvc.perform(put(FRAUD_ROUTE, SEALED_SHAPE_SELECTOR)
                            .principal(PRINCIPAL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyJson(ACTION_REPORT_FRAUD)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.message").value(FraudMarkResponse.MESSAGE_UPDATE_SUCCESS));
        }

        /**
         * The rendered problem body is stamped from the injected clock, so two runs agree exactly.
         *
         * <p>Assumptions: the reference reads a wall clock three times on this path -- it asks the region
         * for the time at {@code cbl/COPAUS2C.cbl} L91 to L100 and moves it to the report date at L101, and
         * it sets {@code FRAUD_RPT_DATE = CURRENT DATE} on both statements, at L194 and L225 -- so nothing
         * it produces is reproducible between runs. The target takes its time from a supplied clock
         * instead, which is what makes this assertion possible at all: the same request performed twice
         * renders the identical stamp, and the value is the one the fixed instant denotes rather than
         * merely a repeat of itself.
         *
         * @throws Exception if either request cannot be performed
         */
        @Test
        @DisplayName("the rendered problem stamp comes from the injected clock and repeats exactly")
        void renderedProblemStampComesFromTheInjectedClock() throws Exception {
            String first = performWrite(bodyJson(FraudMarkResponse.UPDATE_STATUS_SUCCESS))
                    .getResponse().getContentAsString();
            String second = performWrite(bodyJson(FraudMarkResponse.UPDATE_STATUS_SUCCESS))
                    .getResponse().getContentAsString();

            assertThat(first).isEqualTo(second);
            assertThat(first).contains(REFUSAL_TIMESTAMP);
            assertThat(REFUSAL_TIMESTAMP).hasSize(26);
        }
    }

    /**
     * The write is one call inside one transaction, and a failure leaves no partial success behind.
     *
     * <p>Refactoring Rationale: this is the divergence the whole file turns on, and what was wrong with
     * the reference arrangement is that its outcome was a value a caller had to remember to read. The
     * reference reaches the fraud writer with {@code EXEC CICS LINK} at {@code cbl/COPAUS1C.cbl} L248 to
     * L252 and suppresses the condition handler with {@code NOHANDLE} at L251, so nothing raises on
     * failure; the caller then hand-inspects the outcome twice, testing the response code at L253 and the
     * writer's own success flag at L254, and a caller omitting either test proceeds as though the write
     * had succeeded. Worse, the two writes are not one unit there: the fraud row is written by the linked
     * program against one manager and the detail segment is replaced by {@code UPDATE-AUTH-DETAILS} at
     * L520 to L528 against another, and they are held together only by a syncpoint taken by hand at L557
     * to L559, with the failure path performing {@code ROLL-BACK} at L565 to L568 from three separate
     * places -- L258, L261 and L540. The target collapses that into ONE in-process call inside ONE
     * transaction declared on the service, so the two writes commit or roll back together and a failure
     * arrives as a propagated exception. Collapsing the reference's two managers into one schema is what
     * makes that possible; a distributed commit is eliminated rather than emulated.
     */
    @Nested
    @DisplayName("the write is one call inside one transaction")
    class SingleTransactionBoundary {

        /**
         * The route performs exactly one service call and nothing further.
         *
         * <p>Assumptions: the count is asserted exactly rather than merely as at-least-one, because two
         * calls from this layer would be two transactions and would reintroduce the split commit the
         * single boundary exists to remove.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the route performs exactly one service call and no more")
        void routePerformsExactlyOneServiceCall() throws Exception {
            stubWrite(FraudMarkResponse.added(), true);

            performWrite(bodyJson(ACTION_REPORT_FRAUD));

            verify(FraudControllerTest.this.marking, times(1))
                    .mark(eq(SEALED_SHAPE_SELECTOR), any(FraudMarkRequest.class), eq(SUBJECT));
            verifyNoMoreInteractions(FraudControllerTest.this.marking);
        }

        /**
         * This layer declares no transaction boundary of its own, on the type or on the handler.
         *
         * <p>Assumptions: asserted by inspecting the declarations rather than by describing them, because
         * a boundary opened here would fragment a commit that has to stay whole and would do so without
         * any test failing. The single boundary belongs to the service, which is the only participant that
         * sees both writes.
         */
        @Test
        @DisplayName("neither the controller nor its handler declares a transaction boundary")
        void neitherControllerNorHandlerDeclaresATransactionBoundary() {
            assertThat(FraudController.class.isAnnotationPresent(Transactional.class)).isFalse();
            assertThat(Arrays.stream(FraudController.class.getDeclaredMethods())
                            .anyMatch(method -> method.isAnnotationPresent(Transactional.class)))
                    .isFalse();
        }

        /**
         * A failure inside the write returns no success envelope at all, only a problem body.
         *
         * <p>Assumptions: the reference has the opposite shape and it is worth naming, because this is
         * where a partial success would surface. There, a failed write still returns normally and reports
         * itself in a field, so the caller moves the failure sentence into the message line at
         * {@code cbl/COPAUS1C.cbl} L257 and rolls back at L258. Here the outcome member is absent from the
         * body entirely rather than present and negative, so a client branching on the status and a client
         * branching on the body cannot disagree. The problem body's own message member is asserted NOT to
         * be either success sentence, since that member exists on a problem body too.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a failed write returns a problem body carrying no success member")
        void failedWriteReturnsNoSuccessEnvelope() throws Exception {
            when(FraudControllerTest.this.marking.mark(any(), any(FraudMarkRequest.class), any()))
                    .thenThrow(new OptimisticLockingFailureException("the row changed underneath"));

            MvcResult result = FraudControllerTest.this.mockMvc
                    .perform(put(FRAUD_ROUTE, SEALED_SHAPE_SELECTOR)
                            .principal(PRINCIPAL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyJson(ACTION_REPORT_FRAUD)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.updateStatus").doesNotExist())
                    .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain(FraudMarkResponse.MESSAGE_ADD_SUCCESS)
                    .doesNotContain(FraudMarkResponse.MESSAGE_UPDATE_SUCCESS);
        }

        /**
         * A rollback reaches this boundary as a propagated failure rather than as a value to inspect.
         *
         * <p>Assumptions: the reference analogue is the paragraph {@code ROLL-BACK} whose label sits at
         * {@code cbl/COPAUS1C.cbl} L565 and whose verb spans L566 to L568, reached from L540 when the
         * segment replace does not report success; that arm also raises the reference's own not-ok flag
         * with {@code MOVE 'Y' TO WS-ERR-FLG} at L542 and composes the sentence
         * {@code ' System error while FRAUD Tagging, ROLLBACK||'} at L545, one leading space and a
         * trailing double-pipe that separates it from the status appended after it. Under transformation
         * rule T5 that rollback becomes exception propagation out of the transaction, so this case asserts
         * that a raised failure produces an error status without the handler catching or translating it.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a rollback surfaces as a propagated failure and not as a status to inspect")
        void rollbackSurfacesAsAPropagatedFailure() throws Exception {
            when(FraudControllerTest.this.marking.mark(any(), any(FraudMarkRequest.class), any()))
                    .thenThrow(new IllegalStateException("the composed fraud key cannot be built"));

            FraudControllerTest.this.mockMvc.perform(put(FRAUD_ROUTE, SEALED_SHAPE_SELECTOR)
                            .principal(PRINCIPAL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyJson(ACTION_REPORT_FRAUD)))
                    .andExpect(status().is5xxServerError())
                    .andExpect(jsonPath("$.updateStatus").doesNotExist());
        }
    }

    /**
     * The shape a refusal takes on the wire, keyed to the member the caller can correct.
     *
     * <p>Assumptions: the reference raises one flag for the whole write --
     * {@code MOVE 'Y' TO WS-ERR-FLG} at {@code cbl/COPAUS1C.cbl} L542 -- which is its analogue of the
     * house not-ok validation flag, so its target form is exactly one entry in the per-field array the
     * shared problem type carries. These cases assert that array's three members by name, and take the
     * blank marker from the shared kernel's published constant rather than restating the byte.
     */
    @Nested
    @DisplayName("the shape of a refusal")
    class RefusalShape {

        /**
         * An action outside the closed domain carries a per-field entry keyed to the published member.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an out-of-domain action is keyed to the action member with the not-ok state")
        void outOfDomainActionIsKeyedToTheActionMember() throws Exception {
            FraudControllerTest.this.mockMvc.perform(put(FRAUD_ROUTE, SEALED_SHAPE_SELECTOR)
                            .principal(PRINCIPAL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyJson(FraudMarkResponse.UPDATE_STATUS_SUCCESS)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("action"))
                    .andExpect(jsonPath("$.fieldErrors[0].state")
                            .value(FieldValidationFlag.NOT_OK.name()))
                    .andExpect(jsonPath("$.fieldErrors[0].message").exists());
        }

        /**
         * A never-supplied action carries the blank state, whose published marker is the asterisk.
         *
         * <p>Assumptions: the two states are distinguished rather than merged, because the reference
         * template distinguishes them: it moves the error colour into a refused field and ADDITIONALLY
         * moves a literal asterisk into one that was never supplied at all. The marker is asserted through
         * the shared kernel's constant and its own accessor, so this case cannot drift from the type that
         * owns it.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a never-supplied action carries the blank state and the published asterisk marker")
        void neverSuppliedActionCarriesTheBlankState() throws Exception {
            FraudControllerTest.this.mockMvc.perform(put(FRAUD_ROUTE, SEALED_SHAPE_SELECTOR)
                            .principal(PRINCIPAL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("action"))
                    .andExpect(jsonPath("$.fieldErrors[0].state")
                            .value(FieldValidationFlag.BLANK.name()));

            assertThat(FieldValidationFlag.BLANK.screenMarker())
                    .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER)
                    .isEqualTo("*");
            assertThat(FieldValidationFlag.NOT_OK).isNotEqualTo(FieldValidationFlag.BLANK);
        }

        /**
         * A selector naming no row answers 404 rather than 400.
         *
         * <p>Assumptions: the two are told apart because a caller can correct a malformed selector and
         * cannot correct a row the expiry sweep has already removed.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a selector naming no row answers 404")
        void selectorNamingNoRowAnswersNotFound() throws Exception {
            when(FraudControllerTest.this.marking.mark(any(), any(FraudMarkRequest.class), any()))
                    .thenThrow(new NoSuchElementException("the selector names no pending authorization"));

            FraudControllerTest.this.mockMvc.perform(put(FRAUD_ROUTE, SEALED_SHAPE_SELECTOR)
                            .principal(PRINCIPAL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyJson(ACTION_REPORT_FRAUD)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_NOT_FOUND));
        }

        /**
         * A concurrent change to the row answers 409 through the shared advice.
         *
         * <p>Assumptions: driven by letting the service double raise the framework's optimistic-lock
         * failure, so what is asserted is the advice's published mapping rather than a local handler. A
         * handler on this controller would answer identically while making that mapping unobservable, and
         * the two could then disagree.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a concurrent change answers 409 with the shared conflict code")
        void concurrentChangeAnswersConflict() throws Exception {
            when(FraudControllerTest.this.marking.mark(any(), any(FraudMarkRequest.class), any()))
                    .thenThrow(new OptimisticLockingFailureException("the row changed underneath"));

            FraudControllerTest.this.mockMvc.perform(put(FRAUD_ROUTE, SEALED_SHAPE_SELECTOR)
                            .principal(PRINCIPAL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyJson(ACTION_REMOVE_FRAUD)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_CONFLICT));
        }

        /**
         * A refusal is a parseable problem body carrying the members a client needs to act on it.
         *
         * <p>Assumptions: the members are asserted present rather than assumed, because a client reading a
         * refusal needs the status and the stamp to correlate it and the per-field array to render the
         * refusal against the input. The path is asserted by its two ends rather than as a whole string,
         * because the shared advice masks the selector inside it; that masking is asserted in its own right
         * beside the other disclosure controls rather than here.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a refusal carries the status, route ends, stamp and per-field array a client acts on")
        void refusalCarriesTheMembersAClientActsOn() throws Exception {
            MvcResult result = FraudControllerTest.this.mockMvc
                    .perform(put(FRAUD_ROUTE, SEALED_SHAPE_SELECTOR)
                            .principal(PRINCIPAL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyJson(FraudMarkResponse.UPDATE_STATUS_SUCCESS)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.timestamp").value(REFUSAL_TIMESTAMP))
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andReturn();

            @SuppressWarnings("unchecked")
            Map<String, Object> rendered = FraudControllerTest.this.jsonMapper
                    .readValue(result.getResponse().getContentAsString(), Map.class);

            assertThat(String.valueOf(rendered.get("path")))
                    .startsWith("/api/v1/authorizations/")
                    .endsWith("/fraud");
        }
    }

    /**
     * Identity comes from the authenticated caller, and nothing about the request is remembered.
     *
     * <p>Assumptions: the reference is pseudo-conversational, so continuity between screen turns travels
     * in a structure the terminal hands back, and that structure is storage the client echoes -- meaning a
     * caller could in principle assert its own user type. Here identity arrives as an authenticated
     * principal the caller cannot compose, the selection arrives in the request path, and there is no
     * re-entry discriminator to gate anything on. These cases demonstrate that rather than describing it.
     */
    @Nested
    @DisplayName("identity is the caller's and no state is kept")
    class IdentityAndStatelessness {

        /**
         * The subject the service receives is the authenticated principal's name.
         *
         * <p>Assumptions: asserted with an exact match on the subject rather than with a wildcard, because
         * a wildcard would pass if the handler forwarded a value taken from the body instead.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the subject the service receives is the authenticated principal's name")
        void subjectTheServiceReceivesIsThePrincipals() throws Exception {
            stubWrite(FraudMarkResponse.added(), true);

            performWrite(bodyJson(ACTION_REPORT_FRAUD));

            ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
            verify(FraudControllerTest.this.marking).mark(eq(SEALED_SHAPE_SELECTOR),
                    any(FraudMarkRequest.class), subject.capture());
            assertThat(subject.getValue()).isEqualTo(SUBJECT);
        }

        /**
         * No server-side session is created or consulted by a successful write.
         *
         * <p>Assumptions: the absence is asserted POSITIVELY by asking for the session without creating
         * one, because a session created here would be invisible to every other assertion in this file
         * while breaking the property that any task behind the load balancer can serve this route.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("no server-side session is created or consulted")
        void noServerSideSessionIsCreatedOrConsulted() throws Exception {
            stubWrite(FraudMarkResponse.added(), true);

            MvcResult result = performWrite(bodyJson(ACTION_REPORT_FRAUD));

            assertThat(result.getRequest().getSession(false)).isNull();
        }

        /**
         * The success body carries exactly two members, so no card data can ride out on this route.
         *
         * <p>Assumptions: this is asserted as a closed member set rather than as the absence of two named
         * fields, which is the stronger form: a primary account number and a card verification value are
         * the two values that must not appear, but so is anything else a later edit might add, and only an
         * exact member set refuses all of them at once. The reference commarea did carry card data through
         * this path -- the 200-byte segment at {@code cbl/COPAUS2C.cbl} L78 includes the card number and
         * its expiry -- so the narrowing is a real change and not a restatement of the baseline.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the success body carries exactly the two published members and no card data")
        void successBodyCarriesExactlyTheTwoPublishedMembers() throws Exception {
            stubWrite(FraudMarkResponse.added(), true);

            String body = performWrite(bodyJson(ACTION_REPORT_FRAUD))
                    .getResponse().getContentAsString();

            @SuppressWarnings("unchecked")
            Map<String, Object> rendered =
                    FraudControllerTest.this.jsonMapper.readValue(body, Map.class);

            assertThat(rendered).containsOnlyKeys("updateStatus", "message");
            assertThat(rendered).hasSize(componentNames(FraudMarkResponse.class).size());
        }

        /**
         * The sealed selector is masked to its last four characters in a published problem body.
         *
         * <p>Assumptions: the selector is not an opaque nuisance value, it encodes the account identifier
         * and the composite key of the row being marked, so publishing it whole in an error body would
         * disclose the row address to anything that stores or forwards that body. The shared advice
         * therefore masks it to its trailing four characters, which is the same last-four discipline the
         * migration applies to a primary account number. This case asserts the masking on the SERIALISED
         * body: the trailing four characters survive so a caller can still correlate the refusal with the
         * request it sent, the body carries a run of mask characters, and the selector's authentication code
         * does not appear whole anywhere in it.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the sealed selector is masked to its last four characters in a problem body")
        void sealedSelectorIsMaskedInAPublishedProblemBody() throws Exception {
            String body = performWrite(bodyJson(FraudMarkResponse.UPDATE_STATUS_SUCCESS))
                    .getResponse().getContentAsString();

            String lastFour = SEALED_SHAPE_SELECTOR.substring(SEALED_SHAPE_SELECTOR.length() - 4);

            assertThat(body)
                    .doesNotContain(SEALED_SHAPE_SELECTOR)
                    .contains("*")
                    .contains(lastFour + "/fraud");
        }
    }
}
