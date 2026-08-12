package com.carddemo.transaction.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.CardDemoCommonAutoConfiguration;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CorrelationIdFilter;
import com.carddemo.transaction.config.OpenApiConfig;
import com.carddemo.transaction.config.SecurityConfig;
import com.carddemo.transaction.dto.BillPaymentPreview;
import com.carddemo.transaction.dto.BillPaymentRequest;
import com.carddemo.transaction.dto.BillPaymentResponse;
import com.carddemo.transaction.dto.TransactionAddRequest;
import com.carddemo.transaction.mapper.BillPaymentMapper;
import com.carddemo.transaction.service.BillPaymentService;
import jakarta.servlet.Filter;
import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Pins the delivered HTTP behaviour of the one bill-payment operation.
 *
 * <p>Purpose: assert, at the wire, what {@link BillPaymentController} answers for CB00 Bill Payment --
 * the screen the online-components table of the repository root {@code README.md} names at line 302 --
 * migrated from the 572-line program {@code app/cbl/COBIL00C.cbl}. Every case below fixes one of: the
 * status a turn is answered with, the member set the body emits, the quoted encoding of the balance, the
 * single per-field error element a refusal carries, and the sentence that refusal reproduces. The three
 * other operations of this context, CT00, CT01 and CT02, belong to {@code TransactionControllerTest};
 * CR00 Transaction Reports at {@code README.md} line 301 sits beside CB00 in that same table but belongs
 * to the reporting context and is asserted nowhere in this module.</p>
 *
 * <p>Assumptions: the baseline program is reference material -- read, cited by path and line, never
 * modified -- and the sentences asserted below are carried across character for character from it. The
 * house doctrine those assertions follow is recorded at {@code tests/README.md} lines 555 to 556: a test
 * encodes the specification exactly as the production source documents it and does not redefine it.</p>
 *
 * <p>Assumptions: no golden-master oracle exists for this path and none is claimed.
 * {@code tests/README.md} lines 83 to 85 records that the online {@code CO*} CICS programs cannot run
 * end to end without a CICS runtime, which the build host does not provide, and {@code COBIL00C} is a
 * {@code CO*} program. Every expected value below is therefore read from the reference source rather
 * than captured from a run of it.</p>
 *
 * <h2>What this class asserts, and what it deliberately leaves to three siblings</h2>
 *
 * <p>Assumptions: the payment is stubbed here, so nothing below reaches a table, a queue or a token
 * issuer, and what is asserted is the mapping from an outcome to a status, a header and a body. The
 * evaluation ORDER of the program's guarded stages is asserted against the service itself in
 * {@code com.carddemo.transaction.service.BillPaymentEvaluationOrderTest}, the sentence selected on each
 * branch in {@code com.carddemo.transaction.service.BillPaymentServiceTest}, and the joint commit of the
 * ledger row and the balance in {@code com.carddemo.transaction.repository.BillPaymentAtomicityIT}.
 * Naming them is part of this class's contract rather than a courtesy: a suite of stubbed edge cases
 * sitting above a stubbed service would otherwise read as coverage of a path nothing exercises.</p>
 *
 * <p>Trade-offs: the verbatim sentences are asserted through the constants
 * {@link BillPaymentMapper} publishes rather than re-typed as literals here. The cost is that a case
 * reads one indirection away from the text an operator sees. It is accepted for the reason
 * {@code tests/README.md} lines 540 to 542 give for resolving a record layout through the compiler
 * copybook path instead of copying it -- one contract, one owner. A second copy of the catalogue in this
 * file could drift from the first while both still compiled, and the migration reproduces those strings
 * character for character precisely so that they cannot.</p>
 *
 * <h2>How the edge slice is assembled, and why not with the slice annotation</h2>
 *
 * <p>Alternatives Considered: the servlet slice annotation is the obvious wiring and is not on this
 * module's test class path. Spring Boot 4 moved it out of {@code spring-boot-test-autoconfigure}, and
 * the jar the reactor resolves carries no such annotation at all -- checked against that jar's own entry
 * list rather than inferred -- while {@code services/transaction-service/pom.xml} declares no artifact
 * that would supply it. Declaring one would mean editing that POM, which is outside this file's remit,
 * so the equivalent slice is assembled from {@link SliceConfiguration} out of types that are on the
 * path, exactly as the sibling {@code TransactionControllerTest} assembles its own: the test context
 * framework supplies the context, {@link MockitoBean} substitutes the collaborators, and
 * {@link MockMvcBuilders#webAppContextSetup} builds the entry point.</p>
 *
 * <p>Trade-offs: {@link OpenApiConfig} is imported into that configuration rather than left out, at the
 * cost of the slice also creating the published-contract bean it declares, which no assertion below
 * reads. The cost is accepted because omitting it hides a defect rather than avoiding one: a slice
 * without this module's own web configuration answers with whichever encoding a bare context happens to
 * install, so it can satisfy every assertion below while the deployed service emits different JSON. That
 * risk is sharper on this operation than on any other in the module, because the balance is the only
 * amount on the surface under test -- an unregistered codec would render it as a JSON number and the
 * quoting assertion is the one thing that would then be silently wrong. Alternatives Considered: a
 * whole-application context, which would create the persistence layer and run the schema migration for
 * assertions about statuses, member sets and sentences, and would resolve the issuer document while
 * refreshing.</p>
 *
 * <p>Refactoring Rationale: the amount codec and the single problem-rendering advice arrive through
 * {@link CardDemoCommonAutoConfiguration} rather than through {@link OpenApiConfig}, because that is
 * where the shared kernel declares them; this module's own configuration records the arrangement and
 * declares neither. An earlier revision of this class built its entry point with a standalone setup and
 * hand-registered a message converter and the advice instead. What was wrong with that is not style: a
 * hand-built converter is not the one the deployment installs, and a standalone setup runs no security
 * chain at all, so the two authorization cases below were unreachable and a refusal could not be
 * asserted to be correlatable.</p>
 *
 * <p>Assumptions: {@link CorrelationIdFilter} is placed ahead of the authentication filter in the entry
 * point built by {@link #assembleEdge()}, mirroring the order the shared kernel registers it in. That
 * ordering is what makes a refused submission correlatable at all: a 401 or a 403 is produced inside the
 * security chain, so a filter installed after it would never see one.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception clause.</p>
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = BillPaymentControllerTest.SliceConfiguration.class)
@TestPropertySource(properties = {
    "springdoc.swagger-ui.url=/transaction-api.yaml",
    "carddemo.security.cognito.admin-group-name=carddemo-admin",
    "carddemo.security.cognito.user-group-name=carddemo-user",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.invalid/carddemo-ledger",
    "carddemo.security.jwt.expected-token-use=access",
    "carddemo.security.jwt.expected-client-id=bill-payment-edge-slice-client",
    "carddemo.security.jwt.required-scope=aws.cognito.signin.user.admin"
})
@DisplayName("the payment screen at the wire: one endpoint, four turns and one message each")
class BillPaymentControllerTest {

    /**
     * The instant every rendered problem timestamp is produced from.
     *
     * <p>Assumptions: an instant is pinned rather than read from the host clock because the problem
     * shape publishes a timestamp member, so a running clock would make every refusal assertion below
     * depend on when it ran.</p>
     */
    private static final Instant PINNED_INSTANT = Instant.parse("2022-07-18T12:00:00Z");

    /**
     * The rendered form of {@link #PINNED_INSTANT}, at the width the ledger declares.
     *
     * <p>Assumptions: the width is {@link TimestampFormatter#TIMESTAMP_LENGTH}, twenty-six characters,
     * and this literal is asserted to be exactly that long rather than merely to look right.</p>
     */
    private static final String PINNED_TIMESTAMP = "2022-07-18 12:00:00.000000";

    /** A well-formed eleven-digit account identifier, zero-padded as the published contract requires. */
    private static final String ACCOUNT_ID = "00000000011";

    /** The sixteen-digit identifier a posted payment is answered with. */
    private static final String PAID_TRANSACTION_ID = "0000000000000009";

    /** The balance a stubbed account carries, as an exact decimal string. */
    private static final String PAYABLE_BALANCE = "123.45";

    /** The caller identity every authenticated submission below is made as. */
    private static final String SUBJECT = "11111111-2222-3333-4444-555555555555";

    /** The credential presented on every authenticated submission; the substituted reader answers it. */
    private static final String BEARER_TOKEN = "Bearer bill-payment-edge-slice-token";

    /** The header a presented credential travels in. */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /** A group claim value that reaches neither of the two published business authorities. */
    private static final String UNRELATED_GROUP = "carddemo-observers";

    /** A confirmation value the baseline's own final branch refuses. */
    private static final String UNACCEPTABLE_CONFIRMATION = "X";

    /**
     * A primary account number whose leading digit is a zero.
     *
     * <p>Assumptions: this value is not invented. It is the card number of the SECOND record of
     * {@code app/data/ASCII/dailytran.txt}, whose three hundred records are 350 bytes wide and whose
     * card number occupies one-based bytes 263 to 278. The first record's card number opens with a four,
     * so the second is the witness a leading zero needs.</p>
     */
    private static final String LEADING_ZERO_CARD_NUMBER = "0927987108636232";

    /**
     * The declared width of the generated screen field the reference moved its message into.
     *
     * <p>Assumptions: this width belongs to the generated map at {@code app/cpy-bms/COBIL00.CPY} line
     * 78, {@code 02 ERRMSGI PIC X(78).}, and NOT to the copybook message model, so it is declared here
     * as a local expectation rather than read from a shared constant. The shared kernel publishes the
     * three copybook widths and deliberately publishes no constant for this fourth one, which is the
     * distinction the case below asserts rather than blurs.</p>
     */
    private static final int GENERATED_SCREEN_FIELD_WIDTH = 78;

    /** The character length of the confirmation refusal both this screen and the capture screen emit. */
    private static final int SHARED_REFUSAL_SENTENCE_LENGTH = 40;

    /** The assembled slice, read to build the entry point and to reach the security chain. */
    @Autowired
    private WebApplicationContext context;

    /**
     * The payment this adapter delegates its one operation to, substituted for every case.
     *
     * <p>Assumptions: this is the PRODUCTION bean under {@code src/main}, so the adapter under assertion
     * is wired to the same type the deployment wires it to. Nothing from the sibling test package's own
     * {@code service} directory is reached from here.</p>
     */
    @MockitoBean
    private BillPaymentService billPaymentService;

    /**
     * The credential reader, substituted so the chain runs without a route to an identity provider.
     *
     * <p>Refactoring Rationale: this substitution replaces the bean {@link SecurityConfig} declares, and
     * it happens at definition level rather than after refresh, so the real factory -- which fetches the
     * issuer's provider document -- is never invoked. Stubbing the bean after the context started would
     * not help, because the fetch happens while it starts.</p>
     */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** The entry point under assertion, rebuilt per case so no filter state carries between them. */
    private MockMvc mockMvc;

    /**
     * Builds the entry point over the assembled slice, with the correlation filter ahead of the chain.
     *
     * <p>Assumptions: the security chain is added as a filter rather than relied upon implicitly,
     * because the entry point is built from the context by hand and a chain that is not added is a chain
     * that does not run -- which would make every submission below anonymous and successful, quietly
     * turning the three authorization assertions into assertions about nothing.</p>
     */
    @BeforeEach
    void assembleEdge() {
        // WHY : Assumptions: the correlation filter is handed the slice's own clock rather than a second
        //       one built here, so the instant a refusal is stamped with and the instant every other
        //       participant reads are the same instant by construction. Two independently built clocks
        //       would agree only for as long as nobody changed one of them.
        this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context)
                .addFilters(new CorrelationIdFilter(this.context.getBean(Clock.class)),
                        this.context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    /**
     * The submission carries the account and the confirmation, and admits no amount whatsoever.
     *
     * <p>Purpose: pins the input surface of {@code PROCESS-ENTER-KEY} at {@code app/cbl/COBIL00C.cbl}
     * line 154 against the generated map it received its fields from. That map declares exactly two
     * business inputs, {@code 02 ACTIDINI PIC X(11).} at line 60 of {@code app/cpy-bms/COBIL00.CPY} and
     * {@code 02 CONFIRMI PIC X(1).} at line 72, and the submitted type declares one member for each.</p>
     *
     * <p>Assumptions: the ABSENCE of an amount is asserted, not merely its presence left untested,
     * because the baseline admits no partial payment at all. Line 224 moves the whole of
     * {@code ACCT-CURR-BAL} into {@code TRAN-AMT} and line 234 subtracts that same amount back out, so
     * the sum paid is derived from stored state rather than chosen by a caller, and the screen carries no
     * amount field to migrate. A member added later would be a partial-payment capability the reference
     * cannot express, which is a change in behaviour rather than in structure; this case is what makes
     * that addition fail rather than pass unnoticed.</p>
     *
     * <p>Assumptions: the identifier is a digits-validated String and not a numeric member, and the
     * warrant is the reference's own X-over-9 pattern at {@code app/cpy/CVCRD01Y.cpy} -- line 34 declares
     * {@code CC-ACCT-ID PIC X(11)}, line 35 values it to spaces and line 36 redefines it as
     * {@code CC-ACCT-ID-N PIC 9(11)}, with lines 37 to 39 doing the same for a sixteen-digit card number.
     * The baseline therefore treats these as characters on the wire and as numbers only in arithmetic.</p>
     *
     * <p>Assumptions: the declared widths are read from those two {@code PICTURE} clauses, eleven and
     * one, so a widened constraint fails here rather than reaching a column that cannot hold it.</p>
     */
    @Test
    @DisplayName("submission: exactly the account and the confirmation, and no amount at all")
    void theSubmissionCarriesTwoMembersAndNoAmount() {
        List<String> submitted = recordComponentNames(BillPaymentRequest.class);

        assertThat(submitted).containsExactly(BillPaymentMapper.ACCOUNT_ID_FIELD,
                BillPaymentMapper.CONFIRMATION_FIELD);
        assertThat(submitted).noneMatch(member -> member.toLowerCase(Locale.ROOT)
                .contains("amount"));
        assertThat(BillPaymentMapper.ACCOUNT_ID_WIDTH).isEqualTo(11);

        BillPaymentRequest submission = new BillPaymentRequest(ACCOUNT_ID,
                BillPaymentService.CONFIRM_YES_UPPER);
        assertThat(submission.accountId()).isInstanceOf(String.class).hasSize(11).isEqualTo(ACCOUNT_ID);
        assertThat(submission.confirmation()).isInstanceOf(String.class).hasSize(1);
    }

    /**
     * The posted body reports the balance that was paid and declares no balance left behind.
     *
     * <p>Purpose: pins the published member set of the paying outcome against the reference's write run
     * at {@code app/cbl/COBIL00C.cbl} lines 233 to 235 -- the record is written at line 233, the balance
     * is reduced at line 234 and the account is rewritten at line 235.</p>
     *
     * <p>Assumptions: there is NO member for the figure line 234 leaves the account holding, and the
     * absence is asserted by comparing the member set whole rather than by probing for members expected
     * to be present. A per-member probe stays silent about a surplus member, which is the failure this
     * case exists to catch: the reference has no field for a post-payment balance anywhere, and one added
     * here would report a number no screen of the reference ever showed. That number is in any case
     * invariably zero, because line 234 subtracts the whole balance from itself.</p>
     *
     * <p>Assumptions: the paying and non-paying outcomes name their amount member differently on
     * purpose -- {@code currentBalance} on the payment and {@code payableBalance} on the preview -- and
     * the two sets are asserted separately rather than through one shared expectation, because the
     * published contract closes both objects and a client generated from it is typed on the difference.</p>
     */
    @Test
    @DisplayName("posted body: the paid balance, and no member for the balance left behind")
    void thePostedBodyDeclaresNoBalanceLeftBehind() {
        List<String> posted = recordComponentNames(BillPaymentResponse.class);
        List<String> previewed = recordComponentNames(BillPaymentPreview.class);

        assertThat(posted).containsExactly("transactionId", BillPaymentMapper.ACCOUNT_ID_FIELD,
                "currentBalance", "paid", "returnMessage");
        assertThat(previewed).containsExactly(BillPaymentMapper.ACCOUNT_ID_FIELD, "payableBalance",
                "paid", "returnMessage");

        // WHY : Assumptions: the rejected names are enumerated rather than left implicit because each is
        //       a name a later reader might reach for when adding the member this case forbids, and a
        //       negative assertion is only as good as the vocabulary it covers.
        assertThat(posted).doesNotContain("newBalance", "balanceAfter", "remainingBalance",
                "closingBalance");
        assertThat(previewed).doesNotContain("newBalance", "balanceAfter", "remainingBalance",
                "closingBalance");
    }

    /**
     * A posted payment is answered 201 with the balance quoted, and addresses the transaction it wrote.
     *
     * <p>Purpose: pins the paying turn, the branch the reference enters at line 210 of
     * {@code app/cbl/COBIL00C.cbl} once {@code CONF-PAY-YES} is set at line 176.</p>
     *
     * <p>Alternatives Considered: transporting the balance as a JSON number. Rejected on a measurable
     * loss rather than a preference. Most clients parse a JSON number into an IEEE-754 binary64 value on
     * receipt, and that binary representation cannot hold every two-place decimal fraction exactly, while
     * {@code ACCT-CURR-BAL PIC S9(10)V99} at line 7 of {@code app/cpy/CVACT01Y.cpy} is twelve significant
     * decimal digits and leaves no room for an approximation. The consequence is sharper on this
     * operation than anywhere else in the module: line 224 moves that same balance into the transaction
     * amount, so the figure reported here is simultaneously the sum that was paid, and an inexact round
     * trip would misstate the payment rather than merely its display. That is why the raw response text
     * is inspected for a QUOTED value, which a matcher reading the parsed value could not distinguish
     * from a bare numeric literal.</p>
     *
     * <p>Assumptions: the rendering width the reference gives that figure is fourteen positions,
     * {@code CURBALI PIC X(14)} at line 66 of {@code app/cpy-bms/COBIL00.CPY}, which decomposes as a sign,
     * ten integer digits, the decimal point and two decimals; the emitted text is asserted to fit it.</p>
     *
     * <p>Assumptions: the location header is asserted by value rather than for presence, because a value
     * of the wrong form is the failure that matters -- a caller following it has to arrive at the
     * transaction the payment wrote, which the sibling adapter serves at that path.</p>
     *
     * @throws Exception if the submission could not be performed
     */
    @Test
    @DisplayName("paying turn: 201, the balance as a quoted string, and the written transaction's path")
    void aPayingTurnIsAnsweredWithQuotedMoneyAndALocation() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenReturn(BillPaymentResponse.posted(
                ACCOUNT_ID, Money.of(PAYABLE_BALANCE), PAID_TRANSACTION_ID,
                BillPaymentMapper.MESSAGE_PAYMENT_SUCCESSFUL_PREFIX));

        String body = this.submitAs(JwtRoleConverter.USER_AUTHORITY,
                        submission(ACCOUNT_ID, BillPaymentService.CONFIRM_YES_UPPER))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        "/api/v1/transactions/" + PAID_TRANSACTION_ID))
                .andExpect(jsonPath("$.paid").value(true))
                .andExpect(jsonPath("$.transactionId").value(PAID_TRANSACTION_ID))
                .andExpect(jsonPath("$.currentBalance").value(PAYABLE_BALANCE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("\"currentBalance\":\"" + PAYABLE_BALANCE + "\"")
                .doesNotContain("\"currentBalance\":" + PAYABLE_BALANCE);
        assertThat(PAYABLE_BALANCE.length()).isLessThanOrEqualTo(14);
        assertThat(Money.of(PAYABLE_BALANCE).amount().scale()).isEqualTo(Money.SCALE);
    }

    /**
     * The balance the paying turn reports is the figure as it stood BEFORE the payment.
     *
     * <p>Purpose: pins the reference's own statement order, which is what makes the reported figure the
     * pre-payment one. Lines 193 and 194 of {@code app/cbl/COBIL00C.cbl} capture
     * {@code ACCT-CURR-BAL} into the working field and move it into the screen field; line 224 copies the
     * same value into {@code TRAN-AMT}; line 233 writes the record; and only THEN does line 234 compute
     * the reduced balance, with line 235 rewriting the account. Nothing repopulates the screen field
     * between line 194 and the send at line 242.</p>
     *
     * <p>Assumptions: the write-then-compute-then-update ordering at lines 233, 234 and 235 is the
     * baseline's own and is deliberately NOT normalised into compute-then-write. Reordering it would put
     * the reduced figure in front of the caller, and the one number this body carries would then be the
     * balance remaining rather than the sum paid.</p>
     *
     * <p>Assumptions: because the two are the same number, this case asserts the equality directly: the
     * amount the operation is stubbed to have paid and the balance it reports are one value. That is the
     * property a reader most often doubts, so it is stated as an assertion rather than as prose.</p>
     *
     * @throws Exception if the submission could not be performed
     */
    @Test
    @DisplayName("paying turn: the reported balance is the pre-payment figure, which is the sum paid")
    void theReportedBalanceIsThePrePaymentFigure() throws Exception {
        Money balanceBeforePayment = Money.of(PAYABLE_BALANCE);
        when(this.billPaymentService.payBalanceInFull(any())).thenReturn(BillPaymentResponse.posted(
                ACCOUNT_ID, balanceBeforePayment, PAID_TRANSACTION_ID, null));

        this.submitAs(JwtRoleConverter.USER_AUTHORITY,
                        submission(ACCOUNT_ID, BillPaymentService.CONFIRM_YES_UPPER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentBalance")
                        .value(balanceBeforePayment.toPlainString()));

        // WHY : Assumptions: the figure line 234 leaves behind is zero for this operation because the
        //       whole balance is subtracted from itself, and it is computed here from the reference's own
        //       arithmetic rather than asserted as a literal, so the identity rather than the number is
        //       what this line fixes. No member of either published body reports it.
        assertThat(balanceBeforePayment.minus(balanceBeforePayment)).isEqualTo(Money.ZERO);
    }

    /**
     * Either case of an affirmative confirmation pays, and both reach the same paying outcome.
     *
     * <p>Purpose: pins the first arm of the four-branch {@code EVALUATE CONFIRMI} at
     * {@code app/cbl/COBIL00C.cbl} lines 173 to 191. Its arm at lines 174 to 177 lists {@code 'Y'} and
     * {@code 'y'} as two separate {@code WHEN} clauses falling into one body that sets
     * {@code CONF-PAY-YES} and reads the account.</p>
     *
     * <p>Assumptions: both cases are exercised rather than one, because the reference spells them as two
     * clauses rather than folding the case, so a target that accepted only the upper case would refuse a
     * submission the reference pays. No case-insensitive comparison is assumed anywhere: the accepted set
     * is exactly the two characters those two clauses name.</p>
     *
     * @param confirmation the affirmative confirmation character to submit, of type {@link String}; must
     *     not be {@code null}
     * @throws Exception if the submission could not be performed
     */
    @ParameterizedTest(name = "confirmation \"{0}\" pays")
    @MethodSource("affirmativeConfirmations")
    @DisplayName("confirm branch: both cases of an affirmative confirmation pay")
    void anAffirmativeConfirmationPays(String confirmation) throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenReturn(BillPaymentResponse.posted(
                ACCOUNT_ID, Money.of(PAYABLE_BALANCE), PAID_TRANSACTION_ID, null));

        this.submitAs(JwtRoleConverter.USER_AUTHORITY, submission(ACCOUNT_ID, confirmation))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paid").value(true));
    }

    /**
     * A declined confirmation surfaces NO message of any kind, and is not an error.
     *
     * <p>Purpose: pins the arm at {@code app/cbl/COBIL00C.cbl} lines 178 to 181, which is the most
     * easily mis-migrated branch in this program. It lists {@code 'N'} and {@code 'n'} as two
     * {@code WHEN} clauses, performs {@code CLEAR-CURRENT-SCREEN} at line 180 and moves {@code 'Y'} into
     * the error flag at line 181 -- and moves NO text into {@code WS-MESSAGE} at all.</p>
     *
     * <p>Alternatives Considered: answering 400 here, or reporting a cancellation sentence. Both were
     * rejected on the same evidence: that error flag is a pure control-flow short circuit whose only
     * effect is to make the guarded re-tests at lines 197 and 208 skip the balance test and the payment
     * block, and no line of this program moves a sentence in this arm. A refusal answered here would put
     * a field highlight in front of an operator that the 3270 screen never showed, and a sentence
     * invented here -- "payment cancelled" being the obvious candidate -- would be text no line emits.
     * The declined turn is therefore an acknowledged turn that wrote nothing.</p>
     *
     * <p>Assumptions: this is the DISCRIMINATOR against the capture screen, and the two files must agree
     * rather than contradict. {@code app/cbl/COTRN02C.cbl} spells the same confirmation as a THREE-branch
     * form, collapsing {@code 'N'}, {@code 'n'}, {@code SPACES} and {@code LOW-VALUES} into one arm at its
     * lines 173 to 176 which DOES move a sentence, at its line 178. The sibling
     * {@code TransactionControllerTest} asserts that side. Two programs, two screens, two different
     * treatments of the same keystroke, and neither is a defect in the other.</p>
     *
     * <p>Assumptions: the emitted member set is compared WHOLE, so a sentence member added later fails
     * this case instead of passing it, and the sentence member the preview does publish is additionally
     * asserted to carry no value at byte level. Comparing the set alone would not be enough, because the
     * member exists on the shape whichever turn produced it; comparing the bytes is what states that this
     * turn valued it with nothing.</p>
     *
     * <p>Assumptions: an unvalued member is rendered as an explicit null rather than omitted, and this
     * case asserts the delivered form rather than an idealised one. No service in this migration configures
     * null omission, so the two unvalued members of a declined turn appear as nulls; the published
     * sentence member admits null for exactly this reason, and the assertion below therefore states that
     * the turn carries no sentence rather than that the shape has no room for one.</p>
     *
     * @param confirmation the declining confirmation character to submit, of type {@link String}; must
     *     not be {@code null}
     * @throws Exception if the submission could not be performed
     */
    @ParameterizedTest(name = "confirmation \"{0}\" declines silently")
    @MethodSource("decliningConfirmations")
    @DisplayName("confirm branch: a declined confirmation surfaces no message whatsoever")
    void aDeclinedConfirmationSurfacesNoMessageAtAll(String confirmation) throws Exception {
        when(this.billPaymentService.payBalanceInFull(any()))
                .thenReturn(BillPaymentPreview.cleared(ACCOUNT_ID));

        String body = this.submitAs(JwtRoleConverter.USER_AUTHORITY,
                        submission(ACCOUNT_ID, confirmation))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.paid").value(false))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.payableBalance").doesNotExist())
                .andExpect(jsonPath("$.returnMessage").doesNotExist())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(memberNames(body)).containsExactly(BillPaymentMapper.ACCOUNT_ID_FIELD,
                "payableBalance", "paid", "returnMessage");
        assertThat(body).contains("\"returnMessage\":null")
                .contains("\"payableBalance\":null");
        assertThat(body).doesNotContain(BillPaymentMapper.MESSAGE_CONFIRM_PAYMENT)
                .doesNotContain(BillPaymentMapper.MESSAGE_INVALID_CONFIRMATION)
                .doesNotContain(BillPaymentMapper.MESSAGE_NOTHING_TO_PAY);
    }

    /**
     * A withheld confirmation reports the payable balance beside the reference's own prompt.
     *
     * <p>Purpose: pins the arm at {@code app/cbl/COBIL00C.cbl} lines 182 to 184, which reads the account
     * for a confirmation of {@code SPACES} or {@code LOW-VALUES} and falls through to the {@code ELSE} at
     * line 236, whose line 237 moves the prompt into the message field.</p>
     *
     * <p>Assumptions: the withheld state is expressed by OMITTING the member rather than by sending it
     * empty. The submitted type caps the confirmation at one character and leaves a null value valid, so
     * omission is how a caller expresses the state the reference reaches for spaces or low values, and a
     * one-character empty string is not a value that map field can hold.</p>
     *
     * <p>Assumptions: the identifier member is asserted ABSENT rather than null, because the published
     * preview schema closes its object and declares no such member. A body carrying it as null would
     * still satisfy a paid-is-false assertion while telling a client that an identifier was expected here
     * and could not be produced.</p>
     *
     * @throws Exception if the submission could not be performed
     */
    @Test
    @DisplayName("confirm branch: a withheld confirmation reports the balance and the prompt")
    void aWithheldConfirmationReportsTheBalanceAndThePrompt() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenReturn(BillPaymentPreview.reporting(
                ACCOUNT_ID, Money.of(PAYABLE_BALANCE), BillPaymentMapper.MESSAGE_CONFIRM_PAYMENT));

        this.submitAs(JwtRoleConverter.USER_AUTHORITY, submission(ACCOUNT_ID, null))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.paid").value(false))
                .andExpect(jsonPath("$.payableBalance").value(PAYABLE_BALANCE))
                .andExpect(jsonPath("$.returnMessage")
                        .value(BillPaymentMapper.MESSAGE_CONFIRM_PAYMENT))
                .andExpect(jsonPath("$.transactionId").doesNotExist());
    }

    /**
     * A confirmation the reference does not accept is refused with that program's own sentence.
     *
     * <p>Purpose: pins the final arm at {@code app/cbl/COBIL00C.cbl} lines 185 to 190, whose line 186
     * raises the error flag, whose line 187 moves the refusal sentence, and whose line 189 sends the
     * operator back to the confirmation field -- which is why the per-field element names that field and
     * not the account.</p>
     *
     * <p>Assumptions: the state carried on that element is the not-acceptable one rather than the blank
     * one, and the two are not interchangeable. The value was supplied and refused, so the templated
     * highlight copybook's nested inner test does not apply and no blank marker accompanies it.</p>
     *
     * @throws Exception if the submission could not be performed
     */
    @Test
    @DisplayName("confirm branch: an unacceptable confirmation is refused with line 187's sentence")
    void anUnacceptableConfirmationIsRefusedWithItsOwnSentence() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenThrow(new ClientInputException(
                ApiError.CODE_VALIDATION, BillPaymentMapper.CONFIRMATION_FIELD,
                FieldValidationFlag.NOT_OK, BillPaymentMapper.MESSAGE_INVALID_CONFIRMATION));

        this.submitAs(JwtRoleConverter.USER_AUTHORITY,
                        submission(ACCOUNT_ID, UNACCEPTABLE_CONFIRMATION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.message")
                        .value(BillPaymentMapper.MESSAGE_INVALID_CONFIRMATION))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value(BillPaymentMapper.CONFIRMATION_FIELD))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.NOT_OK.name()));
    }

    /**
     * The refusal sentence this screen shares with the capture screen is two constants, not one.
     *
     * <p>Purpose: records a byte-identity that looks like a duplication and is not. The literal at
     * {@code app/cbl/COBIL00C.cbl} line 187 and the literal at {@code app/cbl/COTRN02C.cbl} line 184 are
     * the SAME forty characters, in two different programs driving two different screens.</p>
     *
     * <p>Assumptions: the two are carried across as two independently owned constants and are
     * deliberately NOT deduplicated into one shared string, and neither is a typographical error in the
     * other. Each program owns the text its own screen displays, so folding them together would make a
     * later edit to one screen's wording silently change the other's, which is precisely the drift that
     * reproducing user-visible text character for character exists to prevent. This case asserts the
     * equality so that a reader who notices it finds it already accounted for, and asserts the length so
     * that a change to either constant alone is caught here rather than in front of an operator.</p>
     */
    @Test
    @DisplayName("confirm branch: the shared refusal sentence is two owned constants, not one")
    void theSharedRefusalSentenceIsTwoConstantsAndNotDeduplicated() {
        assertThat(BillPaymentMapper.MESSAGE_INVALID_CONFIRMATION)
                .isEqualTo(TransactionAddRequest.CONFIRM_INVALID_VALUE)
                .hasSize(SHARED_REFUSAL_SENTENCE_LENGTH);
        assertThat(TransactionAddRequest.CONFIRM_INVALID_VALUE)
                .hasSize(SHARED_REFUSAL_SENTENCE_LENGTH);
    }

    /**
     * A never-supplied account identifier is refused with that program's sentence and the blank state.
     *
     * <p>Purpose: pins the first evaluation of {@code PROCESS-ENTER-KEY}, at
     * {@code app/cbl/COBIL00C.cbl} lines 158 to 167. Its test at line 159 is
     * {@code WHEN ACTIDINI OF COBIL0AI = SPACES OR LOW-VALUES}, line 160 raises the error flag, line 161
     * moves the sentence and line 164 sends the screen.</p>
     *
     * <p>Assumptions: the sentence capitalises {@code NOT} in the middle of an otherwise lower-case
     * phrase, and it is carried across exactly so rather than harmonised. Normalising the case would alter
     * text a terminal displayed, which is the one thing the migration's verbatim-text obligation
     * forbids.</p>
     *
     * <p>Assumptions: the state is the blank one and not merely an error, and the distinction is the
     * nested structure of the templated highlight copybook at {@code app/cpy/CSSETATY.cpy}. Its outer test
     * at lines 18 and 19 admits either the not-acceptable or the blank flag and moves the red attribute at
     * lines 21 and 22; its INNER test at line 23 admits the blank flag alone and moves the literal
     * {@code '*'} into a DIFFERENT generated field at lines 24 and 25. A blank field therefore carries
     * both the highlight and the marker, and this case asserts the marker rather than settling for the
     * error.</p>
     *
     * @throws Exception if the submission could not be performed
     */
    @Test
    @DisplayName("account test: a blank identifier is refused with line 161's sentence and the marker")
    void aBlankAccountIdentifierIsRefusedWithTheBlankStateAndItsMarker() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenThrow(new ClientInputException(
                ApiError.CODE_VALIDATION, BillPaymentMapper.ACCOUNT_ID_FIELD,
                FieldValidationFlag.BLANK, BillPaymentMapper.MESSAGE_ACCOUNT_ID_EMPTY));

        this.submitAs(JwtRoleConverter.USER_AUTHORITY,
                        submission(ACCOUNT_ID, BillPaymentService.CONFIRM_YES_UPPER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(BillPaymentMapper.MESSAGE_ACCOUNT_ID_EMPTY))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value(BillPaymentMapper.ACCOUNT_ID_FIELD))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.BLANK.name()));

        assertThat(FieldValidationFlag.BLANK.screenMarker())
                .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER)
                .isEqualTo("*");
    }

    /**
     * The three states a field can be in are kept distinct, and only one of them carries the marker.
     *
     * <p>Purpose: pins all THREE outcomes the templated highlight copybook at
     * {@code app/cpy/CSSETATY.cpy} produces, rather than the two an "error or not" reading would leave.
     * Its nesting yields exactly three: the not-acceptable flag takes the outer test and gets the red
     * attribute only; the blank flag takes the outer test AND the inner one at line 23, so it gets the
     * attribute plus the literal {@code '*'} moved at lines 24 and 25; a valid field takes neither.</p>
     *
     * <p>Assumptions: the blank state is a SUBSET of error rather than a sibling of it, which is why it
     * is asserted to report as an error while additionally carrying the marker. Treating the two as
     * mutually exclusive would drop the highlight from a blank field.</p>
     *
     * <p>Refactoring Rationale: the re-entry gate at line 20 of that copybook,
     * {@code AND CDEMO-PGM-REENTER}, has NO counterpart in the target and none may be introduced. Its
     * discriminator {@code CDEMO-PGM-CONTEXT PIC 9(01)} at line 29 of {@code app/cpy/COCOM01Y.cpy}, with
     * its enter value at line 30 and its re-enter value at line 31, existed because the highlight had to
     * survive a task ending between screen turns. A stateless handler answering with a per-field array has
     * no turn to count, so the array is populated on EVERY failing submission rather than on a second
     * one, and this case asserts the states themselves rather than any condition on when they appear.</p>
     */
    @Test
    @DisplayName("validation states: three of them, and the marker belongs to exactly one")
    void allThreeValidationStatesAreKeptDistinct() {
        assertThat(FieldValidationFlag.values()).containsExactly(FieldValidationFlag.VALID,
                FieldValidationFlag.NOT_OK, FieldValidationFlag.BLANK);

        assertThat(FieldValidationFlag.NOT_OK.isError()).isTrue();
        assertThat(FieldValidationFlag.NOT_OK.requiresBlankMarker()).isFalse();
        assertThat(FieldValidationFlag.NOT_OK.screenMarker())
                .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);

        assertThat(FieldValidationFlag.BLANK.isError()).isTrue();
        assertThat(FieldValidationFlag.BLANK.requiresBlankMarker()).isTrue();
        assertThat(FieldValidationFlag.BLANK.screenMarker())
                .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER);

        assertThat(FieldValidationFlag.VALID.isValid()).isTrue();
        assertThat(FieldValidationFlag.VALID.isError()).isFalse();
        assertThat(FieldValidationFlag.VALID.screenMarker())
                .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);
    }

    /**
     * A balance of exactly zero is nothing to pay, and so is a negative one.
     *
     * <p>Purpose: pins the inclusiveness of the guard at {@code app/cbl/COBIL00C.cbl} lines 198 to 201.
     * The comparison is {@code IF ACCT-CURR-BAL &lt;= ZEROS AND} at line 198, combined at line 199 with the
     * identifier being non-blank, and line 201 moves the advisory.</p>
     *
     * <p>Assumptions: the boundary is asserted AT zero and not only below it, because a strict reading of
     * that comparison is the plausible mis-migration and it is invisible in the common case: an account
     * with a zero balance would be let through to the payment block, which the reference never does, and
     * the caller would be answered as though a payment of nothing had been arranged. The negative case is
     * asserted alongside it so that the pair fixes the whole predicate rather than one end of it.</p>
     *
     * <p>Assumptions: this turn is answered 200 carrying the advisory rather than as a refusal, because
     * the reference reaches it after the account read and displays the balance beside the sentence -- the
     * same shape as the withheld turn, which is why the published contract carries both on the preview.</p>
     *
     * @param balance the non-positive balance the account is stubbed to hold, of type {@link String};
     *     must not be {@code null}
     * @throws Exception if the submission could not be performed
     */
    @ParameterizedTest(name = "a balance of {0} is nothing to pay")
    @MethodSource("nonPositiveBalances")
    @DisplayName("balance test: the nothing-to-pay guard is inclusive of zero")
    void aNonPositiveBalanceIsNothingToPay(String balance) throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenReturn(BillPaymentPreview.reporting(
                ACCOUNT_ID, Money.of(balance), BillPaymentMapper.MESSAGE_NOTHING_TO_PAY));

        this.submitAs(JwtRoleConverter.USER_AUTHORITY,
                        submission(ACCOUNT_ID, BillPaymentService.CONFIRM_YES_UPPER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid").value(false))
                .andExpect(jsonPath("$.payableBalance").value(balance))
                .andExpect(jsonPath("$.returnMessage")
                        .value(BillPaymentMapper.MESSAGE_NOTHING_TO_PAY));

        assertThat(Money.of(balance).isPositive()).isFalse();
    }

    /**
     * A failing submission carries exactly ONE message and exactly one per-field element.
     *
     * <p>Purpose: pins the message arity of this program. The baseline short-circuits at the first
     * failure via {@code PERFORM SEND-BILLPAY-SCREEN} followed by a task-ending
     * {@code EXEC CICS RETURN}, emitting one message; the Java collects a per-field array; the divergence
     * is documented. That short circuit is visible in the guarding: the blank-account evaluation at lines
     * 158 to 167 is followed by a re-test of the error flag at line 169 before the confirmation
     * evaluation at lines 173 to 191, another at line 197 before the balance test at lines 198 to 201,
     * and another at line 208 before the payment block, so the FIRST failure wins and every later stage is
     * bypassed. The single {@code EXEC CICS RETURN} of this program sits at line 146, which every send
     * reaches.</p>
     *
     * <p>Assumptions: a submission wrong in two ways is answered about ONE of them, and this case is
     * written so that a target collecting both would fail it. Reporting both the blank identifier and the
     * unacceptable confirmation together would surface a complaint the reference never displays, because
     * the guarded re-test at line 169 means the confirmation is never even evaluated once the account test
     * has raised the flag. The array is therefore one element long on this operation, and its one element
     * names the field the reference sends the operator back to.</p>
     *
     * @throws Exception if the submission could not be performed
     */
    @Test
    @DisplayName("refusal arity: one message and one per-field element, never a collection")
    void aFailingSubmissionCarriesExactlyOneMessage() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenThrow(new ClientInputException(
                ApiError.CODE_VALIDATION, BillPaymentMapper.ACCOUNT_ID_FIELD,
                FieldValidationFlag.BLANK, BillPaymentMapper.MESSAGE_ACCOUNT_ID_EMPTY));

        this.submitAs(JwtRoleConverter.USER_AUTHORITY,
                        submission(ACCOUNT_ID, UNACCEPTABLE_CONFIRMATION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(BillPaymentMapper.MESSAGE_ACCOUNT_ID_EMPTY))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value(BillPaymentMapper.ACCOUNT_ID_FIELD))
                .andExpect(jsonPath("$.fieldErrors[1]").doesNotExist());
    }

    /**
     * An unknown account is reported with the sentence the reference emits from three places.
     *
     * <p>Purpose: pins the not-found outcome of the account read, whose sentence the reference moves at
     * {@code app/cbl/COBIL00C.cbl} line 361.</p>
     *
     * <p>Assumptions: an account that does not exist and an account with no card cross-reference are
     * reported IDENTICALLY, because the reference emits that one sentence from three separate places --
     * the account read at line 361, the balance rewrite at line 392 and the cross-reference read at line
     * 425 -- so the conditions are indistinguishable to an operator. Publishing them apart would surface a
     * distinction the reference never shows.</p>
     *
     * @throws Exception if the submission could not be performed
     */
    @Test
    @DisplayName("account read: an unknown account is reported with line 361's sentence")
    void anUnknownAccountIsReportedWithItsOwnSentence() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenThrow(
                new NoSuchElementException(BillPaymentMapper.MESSAGE_ACCOUNT_NOT_FOUND));

        this.submitAs(JwtRoleConverter.USER_AUTHORITY,
                        submission(ACCOUNT_ID, BillPaymentService.CONFIRM_YES_UPPER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message")
                        .value(BillPaymentMapper.MESSAGE_ACCOUNT_NOT_FOUND));
    }

    /**
     * A balance rewrite that did not complete is reported with the reference's failed-update sentence.
     *
     * <p>Purpose: pins the failure outcome of the account rewrite the reference performs at line 235 of
     * {@code app/cbl/COBIL00C.cbl}, whose sentence its line 399 moves.</p>
     *
     * <p>Assumptions: the cause beneath that sentence is a data-access failure, which is what this
     * condition arises from now that the balance is reached on the operation's own transaction rather than
     * across a remote seam. The sentence is asserted rather than the cause, because the sentence is what
     * an operator reads and the cause is an implementation detail of the layer beneath.</p>
     *
     * @throws Exception if the submission could not be performed
     */
    @Test
    @DisplayName("account rewrite: a failed balance update is reported with line 399's sentence")
    void aFailedBalanceUpdateIsReportedWithItsOwnSentence() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenThrow(new IllegalStateException(
                BillPaymentMapper.MESSAGE_ACCOUNT_UPDATE_FAILED,
                new DataAccessResourceFailureException("the balance statement did not complete")));

        this.submitAs(JwtRoleConverter.USER_AUTHORITY,
                        submission(ACCOUNT_ID, BillPaymentService.CONFIRM_YES_UPPER))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message")
                        .value(BillPaymentMapper.MESSAGE_ACCOUNT_UPDATE_FAILED));
    }

    /**
     * The four message widths this screen inherits are kept apart, and the right one governs the body.
     *
     * <p>Purpose: pins which of four inherited widths applies to the message line of THIS screen. The
     * governing one is seventy-five characters, from {@code CCARD-RETURN-MSG PIC X(75)} at line 29 of
     * {@code app/cpy/CVCRD01Y.cpy} beside the identically declared {@code CCARD-ERROR-MSG} at line 28.</p>
     *
     * <p>Assumptions: the other three are not interchangeable with it and are asserted here so that a
     * later reader does not substitute one. Fifty characters belongs to the two common-message fields of
     * {@code app/cpy/CSMSG01Y.cpy}, declared {@code PIC X(50)} at its lines 18 and 20; seventy-two belongs
     * to {@code ABEND-MSG PIC X(72)} at line 28 of {@code app/cpy/CSMSG02Y.cpy}, inside the group its line
     * 21 opens; and seventy-eight belongs to {@code 02 ERRMSGI PIC X(78).} at line 78 of
     * {@code app/cpy-bms/COBIL00.CPY}. The shared kernel publishes a constant for the first three because
     * it owns the copybook message model, and publishes none for the fourth because that one is a
     * GENERATED MAP field with a different owner -- which is why the fourth is a local expectation in this
     * class and its absence from the kernel is not a contradiction.</p>
     *
     * <p>Assumptions: the source LITERAL of a common message is forty-nine characters while its declared
     * FIELD is fifty, and the two are separate contracts rather than one padded value. COBOL supplies the
     * fiftieth byte when the field is loaded, so "padded to fifty" describes the runtime value and not the
     * text in the copybook; the constant carried across preserves the source literal, and this case states
     * which of the two it is asserting so that a reader is not left to guess.</p>
     */
    @Test
    @DisplayName("message widths: four regimes, and the seventy-five one governs this screen")
    void theFourMessageWidthRegimesAreKeptApart() {
        assertThat(ApiError.MESSAGE_RENDERING_WIDTH).isEqualTo(75);
        assertThat(ApiError.COMPACT_MESSAGE_WIDTH).isEqualTo(50);
        assertThat(ApiError.ABEND_MESSAGE_WIDTH).isEqualTo(72);
        assertThat(GENERATED_SCREEN_FIELD_WIDTH).isEqualTo(78)
                .isNotEqualTo(ApiError.MESSAGE_RENDERING_WIDTH);

        assertThat(ApiError.CSMSG01Y_INVALID_KEY).hasSize(49);
        assertThat(ApiError.CSMSG01Y_INVALID_KEY.length() + 1)
                .isEqualTo(ApiError.COMPACT_MESSAGE_WIDTH);

        assertThat(BillPaymentMapper.MESSAGE_CONFIRM_PAYMENT.length())
                .isLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH);
        assertThat(BillPaymentMapper.MESSAGE_NOTHING_TO_PAY.length())
                .isLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH);
        assertThat(BillPaymentMapper.MESSAGE_ACCOUNT_ID_EMPTY.length())
                .isLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH);
        assertThat(PINNED_TIMESTAMP).hasSize(TimestampFormatter.TIMESTAMP_LENGTH);
    }

    /**
     * An absent message is null and never an empty string, and the two states stay distinct.
     *
     * <p>Purpose: pins the sentinel the reference attaches to its return-message field alone.
     * {@code app/cpy/CVCRD01Y.cpy} declares the error message at line 28 and the return message at line 29
     * with the same width, and attaches {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES.} at line 30 to
     * the SECOND of them only.</p>
     *
     * <p>Assumptions: {@code LOW-VALUES} maps to null and is NOT interchangeable with {@code SPACES}.
     * Low values mean the message is off; spaces mean a message that is present and empty, which is the
     * state the program's own message field starts in. Collapsing the two into one empty string would
     * erase a distinction the reference uses as control flow, and a client would then render an empty
     * message band where the reference rendered none -- which is exactly why the published return-message
     * member is nullable rather than merely optional.</p>
     *
     * @throws Exception if the submission could not be performed
     */
    @Test
    @DisplayName("message sentinel: an absent sentence is null, and null is not the empty string")
    void anAbsentSentenceIsNullAndNotTheEmptyString() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any()))
                .thenReturn(BillPaymentPreview.cleared(ACCOUNT_ID));

        String cleared = this.submitAs(JwtRoleConverter.USER_AUTHORITY,
                        submission(ACCOUNT_ID, BillPaymentService.CONFIRM_NO_UPPER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnMessage").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(cleared).doesNotContain("\"returnMessage\":\"\"");
        assertThat(BillPaymentPreview.cleared(ACCOUNT_ID).returnMessage()).isNull();
        assertThat(BillPaymentPreview.reporting(ACCOUNT_ID, Money.ZERO, "").returnMessage())
                .isNotNull()
                .isEmpty();
    }

    /**
     * The operation browses nothing: it takes no navigation input and emits no batch envelope.
     *
     * <p>Purpose: pins the absence of any paged behaviour on this surface, which the reference's own
     * structure establishes independently of the target design. Its attention-key dispatch at
     * {@code app/cbl/COBIL00C.cbl} lines 125 to 142 handles only four cases -- {@code DFHENTER} at lines
     * 126 and 127, {@code DFHPF3} at lines 128 to 135, {@code DFHPF4} at lines 136 and 137, and
     * {@code WHEN OTHER} at lines 138 to 141. Across this application the seventh and eighth function keys
     * are the two that move an operator between batches of rows, and neither appears in this program at
     * all, nor does the fifth.</p>
     *
     * <p>Assumptions: the {@code STARTBR}, {@code READPREV} and {@code ENDBR} run at lines 212 to 215 is
     * NOT a browse and must never be wired to a paged envelope. Line 212 moves {@code HIGH-VALUES} into
     * the identifier before the browse opens, line 214 reads backwards exactly once, and lines 216 and
     * 217 take what it found and add one -- so the run is a highest-key read that derives the next
     * identifier, the equivalent of selecting a maximum, and there is no {@code READNEXT} paragraph
     * anywhere in the program. Reading it as a browse is the plausible mis-migration precisely because the
     * three verbs are the ones a browse also uses.</p>
     *
     * <p>Assumptions: the negative is asserted by comparing each member set WHOLE and by showing that a
     * spurious query parameter changes nothing, rather than by naming the members that must be absent. A
     * closed comparison catches any addition, including one nobody thought to name.</p>
     *
     * @throws Exception if the submission could not be performed
     */
    @Test
    @DisplayName("no browse: no navigation input is taken and no batch envelope is emitted")
    void theOperationTakesNoNavigationInputAndEmitsNoEnvelope() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenReturn(BillPaymentResponse.posted(
                ACCOUNT_ID, Money.of(PAYABLE_BALANCE), PAID_TRANSACTION_ID, null));
        this.callerIn(JwtRoleConverter.USER_AUTHORITY);

        String body = this.mockMvc.perform(post(BillPaymentController.BASE_PATH)
                        .param("size", "20")
                        .param("direction", "FORWARD")
                        .header(AUTHORIZATION_HEADER, BEARER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submission(ACCOUNT_ID, BillPaymentService.CONFIRM_YES_UPPER)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(memberNames(body)).containsExactly("transactionId",
                BillPaymentMapper.ACCOUNT_ID_FIELD, "currentBalance", "paid", "returnMessage");
        assertThat(recordComponentNames(BillPaymentRequest.class)).hasSize(2);
    }

    /**
     * Both published business authorities are admitted, and each is a group name carried across verbatim.
     *
     * <p>Purpose: pins the replacement of the reference's two-valued user type. Line 26 of
     * {@code app/cpy/COCOM01Y.cpy} declares {@code CDEMO-USER-TYPE PIC X(01)} with condition names for the
     * administrator at line 27 and the ordinary user at line 28, and those two values became two group
     * claim values converted into two authorities.</p>
     *
     * <p>Assumptions: the authority names are the group names character for character and carry NO
     * framework prefix, which is why every predicate in the deployed chain is an authority predicate and
     * not a role predicate -- a role predicate would silently demand a name carrying that prefix, which no
     * token presents, and every submission would then be refused for a reason no configuration file
     * states. This case asserts the absence of that prefix so the two spellings cannot be confused.</p>
     *
     * <p>Assumptions: the conversion is performed by the DEPLOYED converter rather than by an injected
     * authentication, because a credential is presented in a header and the substituted reader answers for
     * it. An authority asserted here is therefore one the deployment would derive.</p>
     *
     * @param authority the group claim value the presented credential carries, of type {@link String};
     *     must not be {@code null}
     * @throws Exception if the submission could not be performed
     */
    @ParameterizedTest(name = "a caller in {0} may pay")
    @MethodSource("publishedBusinessAuthorities")
    @DisplayName("authorization: both published authorities are admitted, and neither carries a prefix")
    void bothPublishedBusinessAuthoritiesAreAdmitted(String authority) throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenReturn(BillPaymentResponse.posted(
                ACCOUNT_ID, Money.of(PAYABLE_BALANCE), PAID_TRANSACTION_ID, null));

        this.submitAs(authority, submission(ACCOUNT_ID, BillPaymentService.CONFIRM_YES_UPPER))
                .andExpect(status().isCreated());

        assertThat(authority).doesNotStartWith("ROLE_");
        assertThat(SecurityConfig.BUSINESS_AUTHORITIES).containsExactly(
                JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);
        assertThat(JwtRoleConverter.ADMIN_AUTHORITY).isEqualTo("carddemo-admin");
        assertThat(JwtRoleConverter.USER_AUTHORITY).isEqualTo("carddemo-user");
    }

    /**
     * A credential carrying neither business authority is refused, and the payment is never reached.
     *
     * <p>Purpose: pins the other side of the same rule the previous case states, against the two-valued
     * user type at line 26 of {@code app/cpy/COCOM01Y.cpy} whose condition names at lines 27 and 28 give
     * the administrator and the ordinary user. A credential that verifies but confers neither of the two
     * authorities those values became is refused by the chain, so the two cases together state that
     * MEMBERSHIP decides access rather than mere possession of a token.</p>
     *
     * <p>Assumptions: the refusal is asserted to be correlatable, because it is produced inside the
     * security chain rather than by a handler. The correlation filter runs ahead of that chain, which is
     * what puts an identifier on a response no handler ever saw and what makes the refusal traceable in
     * the log record beside it.</p>
     *
     * @throws Exception if the submission could not be performed
     */
    @Test
    @DisplayName("authorization: a credential carrying neither authority is refused")
    void aCredentialCarryingNeitherAuthorityIsRefused() throws Exception {
        this.callerIn(UNRELATED_GROUP);

        this.mockMvc.perform(post(BillPaymentController.BASE_PATH)
                        .header(AUTHORIZATION_HEADER, BEARER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submission(ACCOUNT_ID, BillPaymentService.CONFIRM_YES_UPPER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());

        verifyNoInteractions(this.billPaymentService);
    }

    /**
     * An unauthenticated submission is refused, reaches nothing, and leaves no session behind.
     *
     * <p>Purpose: pins the stateless posture that replaces the reference's turn-by-turn storage. The
     * session structure at {@code app/cpy/COCOM01Y.cpy} lines 19 to 44 carried the caller identity at line
     * 25, the user type at line 26 and the navigation targets at lines 21 to 24, and none of it travels
     * here: identity comes from the presented credential, the account comes from the submitted body, and
     * where a caller goes next is the caller's own affair. One field the reference reads on first entry is
     * an in-program extension of that structure rather than a member of it, declared at line 72 of
     * {@code app/cbl/COBIL00C.cbl} and pre-seeded into the screen at its lines 116 to 121; in the target
     * the identifier simply arrives in the submission.</p>
     *
     * <p>Assumptions: the absence of a session is asserted rather than assumed, by requiring that the
     * request created none and that no cookie was set. That posture is what makes the deployed service
     * horizontally scalable behind a balancer with no sticky routing: a second submission may be served by
     * a different task, so anything remembered between them would be remembered by only one.</p>
     *
     * @throws Exception if the submission could not be performed
     */
    @Test
    @DisplayName("statelessness: an unauthenticated submission is refused and keeps no session")
    void anUnauthenticatedSubmissionIsRefusedAndKeepsNoSession() throws Exception {
        MvcResult refused = this.mockMvc.perform(post(BillPaymentController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submission(ACCOUNT_ID, BillPaymentService.CONFIRM_YES_UPPER)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.timestamp").value(PINNED_TIMESTAMP))
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .andReturn();

        assertThat(refused.getRequest().getSession(false)).isNull();
        assertThat(refused.getResponse().getCookies()).isEmpty();
        verifyNoInteractions(this.billPaymentService);
    }

    /**
     * No card number and no verification value reaches this surface, and identifiers stay digit strings.
     *
     * <p>Purpose: pins two withholdings and one typing rule. The reference resolves a card number through
     * the cross-reference read at line 211 of {@code app/cbl/COBIL00C.cbl} and writes it into the
     * transaction record at line 225, yet neither published body of this operation carries a card member
     * at all -- which the screen corroborates, declaring only three business fields at lines 60, 66 and 72
     * of {@code app/cpy-bms/COBIL00.CPY}. A verification value is returned by no endpoint of this
     * migration under any status.</p>
     *
     * <p>Assumptions: every identifier crosses as a digit STRING and never as a numeric member, and the
     * witness for why is drawn from live seed data rather than from an argument. The card number of the
     * second record of {@code app/data/ASCII/dailytran.txt} opens with a zero, and this case shows what a
     * numeric member would do to it: reduced to a number and rendered back, it is fifteen digits rather
     * than sixteen, and the account it identifies is a different one. The account identifier this
     * operation does carry is zero-padded to eleven for the same reason, and is asserted to survive the
     * round trip unchanged.</p>
     *
     * @throws Exception if the submission could not be performed
     */
    @Test
    @DisplayName("withholdings: no card, no verification value, and identifiers stay digit strings")
    void noCardOrVerificationValueReachesThisSurface() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenReturn(BillPaymentResponse.posted(
                ACCOUNT_ID, Money.of(PAYABLE_BALANCE), PAID_TRANSACTION_ID, null));

        String body = this.submitAs(JwtRoleConverter.USER_AUTHORITY,
                        submission(ACCOUNT_ID, BillPaymentService.CONFIRM_YES_UPPER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(memberNames(body)).doesNotContain("cardNumber", "cardNum", "cvv",
                "cardVerificationValue");
        assertThat(body).doesNotContain(LEADING_ZERO_CARD_NUMBER);

        assertThat(LEADING_ZERO_CARD_NUMBER).hasSize(BillPaymentMapper.IDENTIFIER_WIDTH)
                .startsWith("0")
                .containsOnlyDigits();
        assertThat(new BigInteger(LEADING_ZERO_CARD_NUMBER).toString())
                .hasSize(LEADING_ZERO_CARD_NUMBER.length() - 1)
                .isNotEqualTo(LEADING_ZERO_CARD_NUMBER);
    }

    /**
     * Supplies the two affirmative confirmation characters the reference's first arm names.
     *
     * @return the upper and lower case affirmatives from {@code app/cbl/COBIL00C.cbl} lines 174 and 175,
     *     never {@code null}
     */
    private static Stream<Arguments> affirmativeConfirmations() {
        return Stream.of(Arguments.of(BillPaymentService.CONFIRM_YES_UPPER),
                Arguments.of(BillPaymentService.CONFIRM_YES_LOWER));
    }

    /**
     * Supplies the two declining confirmation characters the reference's silent arm names.
     *
     * @return the upper and lower case declines from {@code app/cbl/COBIL00C.cbl} lines 178 and 179,
     *     never {@code null}
     */
    private static Stream<Arguments> decliningConfirmations() {
        return Stream.of(Arguments.of(BillPaymentService.CONFIRM_NO_UPPER),
                Arguments.of(BillPaymentService.CONFIRM_NO_LOWER));
    }

    /**
     * Supplies two balances the reference's inclusive guard refuses, one of them exactly zero.
     *
     * <p>Assumptions: zero is listed FIRST because it is the boundary the comparison at line 198 of
     * {@code app/cbl/COBIL00C.cbl} includes and the one a strict reading would let through; the negative
     * value is the case both readings agree on and is present so the pair fixes the whole predicate.</p>
     *
     * @return the two non-positive balances as exact decimal strings, never {@code null}
     */
    private static Stream<Arguments> nonPositiveBalances() {
        return Stream.of(Arguments.of(Money.ZERO.toPlainString()), Arguments.of("-0.01"));
    }

    /**
     * Supplies the two published business authorities this operation admits.
     *
     * @return the administrator and ordinary-user authority names, never {@code null}
     */
    private static Stream<Arguments> publishedBusinessAuthorities() {
        return Stream.of(Arguments.of(JwtRoleConverter.ADMIN_AUTHORITY),
                Arguments.of(JwtRoleConverter.USER_AUTHORITY));
    }

    /**
     * Renders a submission body from an account identifier and an optional confirmation.
     *
     * <p>Assumptions: a null confirmation OMITS the member rather than sending it empty, because that is
     * how a caller expresses the never-confirmed state the reference reaches for spaces or low values at
     * lines 182 to 184. The body is assembled as text rather than serialised from the request type so that
     * a case can express a submission the type itself could not hold, which is what an omitted member and
     * an unacceptable one-character value both require.</p>
     *
     * @param accountId the account identifier to submit, of type {@link String}; must not be {@code null}
     * @param confirmation the confirmation character to submit, of type {@link String}, or {@code null} to
     *     omit the member entirely
     * @return the JSON submission body, never {@code null}
     */
    private static String submission(String accountId, String confirmation) {
        if (confirmation == null) {
            return "{\"" + BillPaymentMapper.ACCOUNT_ID_FIELD + "\":\"" + accountId + "\"}";
        }
        return "{\"" + BillPaymentMapper.ACCOUNT_ID_FIELD + "\":\"" + accountId + "\",\""
                + BillPaymentMapper.CONFIRMATION_FIELD + "\":\"" + confirmation + "\"}";
    }

    /**
     * Makes the substituted credential reader answer for a caller in the supplied groups.
     *
     * <p>Assumptions: a credential is presented the way a caller presents one, in a header, and the reader
     * is what answers for it. Nothing here injects an authentication past the chain, so the group claim is
     * converted into authorities by the deployed converter and an authority a case asserts is the one the
     * deployment would derive.</p>
     *
     * @param groups the group claim values the presented credential carries, of type {@link String}; must
     *     not be {@code null} and no entry may be {@code null}
     */
    private void callerIn(String... groups) {
        when(this.jwtDecoder.decode(anyString())).thenReturn(Jwt.withTokenValue(BEARER_TOKEN)
                .header("alg", "RS256")
                .claim("sub", SUBJECT)
                .claim(JwtRoleConverter.GROUPS_CLAIM, List.of(groups))
                .build());
    }

    /**
     * Submits a body as a caller in one group, and returns the exchange for further expectations.
     *
     * @param authority the group claim value the presented credential carries, of type {@link String};
     *     must not be {@code null}
     * @param body the JSON submission body to send, of type {@link String}; must not be {@code null}
     * @return the performed exchange, never {@code null}
     * @throws Exception if the submission could not be performed
     */
    private ResultActions submitAs(String authority, String body) throws Exception {
        this.callerIn(authority);
        MockHttpServletRequestBuilder submission = post(BillPaymentController.BASE_PATH)
                .header(AUTHORIZATION_HEADER, BEARER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return this.mockMvc.perform(submission);
    }

    /**
     * Reads the component names a record type declares, in declaration order.
     *
     * <p>Assumptions: reflection over the record's components is used rather than a hand-kept list,
     * because a hand-kept list is a second copy of the type's own shape and would go stale silently. The
     * declaration order is the order the published contract lists the members in, so a comparison against
     * it can be exact rather than order-insensitive.</p>
     *
     * @param recordType the record type to read, of type {@link Class}; must not be {@code null} and must
     *     be a record
     * @return the declared component names in declaration order, never {@code null}
     */
    private static List<String> recordComponentNames(Class<?> recordType) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : recordType.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /**
     * Reads the member names of the outermost JSON object of a response body, in the order they appear.
     *
     * <p>Alternatives Considered: parsing the body into a map and reading its keys. Rejected because a
     * parse re-interprets what was sent -- a quoted amount and a bare numeric amount both become one value
     * -- and the point of these comparisons is to describe the bytes that left the service. Tracking the
     * nesting level is what confines the answer to the members the outermost object declares, so a nested
     * object's members are never reported as the body's own.</p>
     *
     * <p>Assumptions: no member name and no rendered value in the bodies compared here contains an escaped
     * quotation mark, so a quotation mark always opens or closes a string. Every value compared is an
     * identifier, an exact decimal amount, a sentence or a literal, and none of them can carry one.</p>
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
                    continue;
                }
            }
        }
        return names;
    }

    /**
     * Assembles the servlet slice this class asserts against: one adapter, the deployed web and security
     * configuration, and the shared kernel's registrations.
     *
     * <p>Purpose: stand up the narrowest context in which the delivered edge behaviour is the deployed
     * one. Three configurations are imported and each is load-bearing: this module's web configuration
     * because it is the module's own, the shared kernel's registration class because it declares the amount
     * codec and the single problem-rendering advice, and this module's security configuration because the
     * authorization cases assert the chain it builds. Removing any one of them would leave a case
     * asserting a default rather than the deployed behaviour.</p>
     *
     * <p>Assumptions: no persistence configuration is imported, so no datasource is created and no schema
     * migration runs; and the two kernel beans whose registration is conditional on a parameter-store
     * property are absent because this slice sets no such property, so no cloud client is created either.
     * That is what keeps the slice startable on a build host with neither a database nor a network route.</p>
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
         * Supplies the clock every rendered timestamp in this slice is produced from.
         *
         * <p>Refactoring Rationale: this bean is marked primary, and the marking is required rather than
         * decorative. The shared kernel declares its own host-following clock under a missing-bean guard,
         * which suppresses it only when the guard is evaluated after the competing definition is known --
         * an ordering the auto-configuration machinery arranges and a plain import does not. Imported as a
         * configuration class here, the kernel's clock is registered as well, and without this marking
         * every consumer of a clock in the slice fails to start on an ambiguous injection rather than
         * choosing. Renaming this bean to shadow the kernel's was rejected: it would depend on definition
         * overriding being permitted, which is off by default and which discards one of two definitions
         * silently instead of stating a preference.</p>
         *
         * @return the pinned clock, never {@code null}
         */
        @Bean
        @Primary
        Clock slicePinnedClock() {
            return Clock.fixed(PINNED_INSTANT, ZoneId.of("UTC"));
        }

        /**
         * Supplies the adapter under assertion over the substituted payment.
         *
         * @param billPaymentService the payment to delegate the operation to, of type
         *     {@link BillPaymentService}; must not be {@code null}
         * @return the adapter under assertion, never {@code null}
         */
        @Bean
        BillPaymentController billPaymentController(BillPaymentService billPaymentService) {
            return new BillPaymentController(billPaymentService);
        }
    }
}
