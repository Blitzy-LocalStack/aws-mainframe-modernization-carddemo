package com.carddemo.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.auth.config.SecurityConfig;
import com.carddemo.auth.dto.SignOnRequest;
import com.carddemo.auth.dto.SignOnResponse;
import com.carddemo.auth.service.CognitoIdentityService;
import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.validation.FieldValidationFlag;
import jakarta.servlet.Filter;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
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
 * Web-layer slice assertions for the sign-on adapter, over a substituted identity service.
 *
 * <h2>What this class asserts, and what it deliberately does not</h2>
 *
 * <p>Purpose: this class holds the six subjects the package descriptor beside it fixes for this
 * package -- HTTP status, response-body shape, verbatim message text, per-field error keys, authority
 * enforcement and statelessness -- for the routes {@link AuthController} serves. The transcribed
 * validation chains, the discovery order of those chains read as behaviour, and the three-way
 * identity-exchange mapping are asserted in the sibling {@code com.carddemo.auth.service} package
 * against a mocked repository, so nothing here restates them.</p>
 *
 * <p>The behavioural specification is {@code app/cbl/COSGN00C.cbl}, 260 lines, reached as transaction
 * {@code CC00} at {@code app/csd/CARDDEMO.CSD} L378 with L379. That program is reference-only and is
 * never modified: every expected value below was read from it and from the symbolic map
 * {@code app/cpy-bms/COSGN00.CPY}, because no executable oracle exists for it --
 * {@code tests/README.md} L83-L85 records that the online {@code CO*} programs cannot run end to end
 * without a CICS runtime, which the build host does not have, and its end-to-end tier at L29 compares
 * the daily batch chain alone. These assertions therefore encode the documented rules; they do not
 * redefine them.</p>
 *
 * <h2>How the slice is assembled, and why not with the slice annotation</h2>
 *
 * <p>Alternatives Considered: {@code @WebMvcTest} is the obvious wiring and is unavailable on this
 * module's test class path. Spring Boot 4 moved the servlet slice annotation out of
 * {@code spring-boot-test-autoconfigure} into the separate artifact
 * {@code org.springframework.boot:spring-boot-webmvc-test}, which
 * {@code services/auth-service/pom.xml} does not declare and which
 * {@code spring-boot-starter-test} does not pull in -- that starter's own descriptor lists
 * {@code spring-boot-test}, {@code spring-boot-test-autoconfigure} and {@code spring-test} and no MVC
 * slice at all. Adding the artifact would mean editing a sibling-owned POM, so the gap is reported
 * rather than patched and the slice is assembled here from types that are on the path.</p>
 *
 * <p>Alternatives Considered: {@code spring-security-test} is likewise absent, so its request
 * post-processors cannot mint an authentication. A token is therefore presented the way a caller
 * presents one, in an {@code Authorization} header, and a substituted {@link JwtDecoder} answers for
 * it. That is not merely a substitute for the missing artifact: the resource-server filter, the real
 * {@link SecurityConfig#jwtAuthenticationConverter(String, String)} and the real
 * {@link JwtRoleConverter} all run, so the authority derivation under assertion is the deployed one
 * rather than a value a post-processor injected past it.</p>
 *
 * <p>Alternatives Considered: a full {@code @SpringBootTest} was rejected for the reason the package
 * descriptor records. It would start the persistence layer and the schema migration for assertions
 * about status codes, body members and message sentences, giving a serialisation-and-routing concern a
 * database-container dependency; and it would create the decoder bean
 * {@code config/SecurityConfig.java} declares, whose factory resolves the issuer document eagerly and
 * fails against the deliberately unreachable issuer this module's test profile pins.</p>
 *
 * <p>Assumptions: the substituted decoder is the one bean this slice supplies in place of a real one,
 * and the reason is recorded in {@code src/test/resources/application-test.yml}: the pinned issuer is
 * an RFC 2606 reserved name, and {@code NimbusJwtDecoder.withIssuerLocation(...).build()} issues the
 * provider-document request while the context refreshes, so a context that creates that bean cannot
 * start on a host with no route to it. Every other participant in the chain is the deployed one.</p>
 *
 * <h2>Two reference sentences this class deliberately does not assert</h2>
 *
 * <p>Assumptions: the attention-identifier complaint is excluded because it has no counterpart here.
 * {@code app/cpy/CSMSG01Y.cpy} declares {@code 05 CCDA-MSG-INVALID-KEY PIC X(50) VALUE} on L20 with its
 * literal on L21, and {@code app/cbl/COSGN00C.cbl} L93 moves it on the {@code WHEN OTHER} arm at L91 to
 * L94 -- the arm a key other than Enter or PF3 reaches. A REST request carries no attention identifier,
 * so the concept belongs to the browser client's own message catalogue and asserting it in this tree
 * would assert a code path the target has no way to enter.</p>
 *
 * <p>Assumptions: the closing courtesy on the PF3 arm at L88 to L90 is excluded for the same reason, PF3
 * being a terminal key rather than a request. It is named here only to forestall a substitution: the
 * constant that arm moves is {@code CCDA-MSG-THANK-YOU} at {@code app/cpy/CSMSG01Y.cpy} L18 with its
 * literal on L19, declared {@code PIC X(50)}, and the repository holds a second, differently declared
 * courtesy sentence elsewhere. They are two values and are never merged into one.</p>
 *
 * <p>Assumptions: no truncation case exists to write, because no sentence this operation emits can reach
 * either declared width. {@code app/cbl/COSGN00C.cbl} L38 declares
 * {@code 05 WS-MESSAGE PIC X(80) VALUE SPACES.} and the map field it feeds is
 * {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/COSGN00.CPY} L84, while the longest of the five
 * sentences asserted below measures twenty-nine characters. Writing a truncation case would oblige an
 * implementation to carry a branch that cannot execute, which is worse than leaving the measurement
 * recorded here where a later editor can check it.</p>
 *
 * <h2>What the documentation gate does and does not check</h2>
 *
 * <p>Assumptions: {@code config/checkstyle/checkstyle.xml} audits the presence and completeness of
 * every block below, down to private methods, and its {@code SummaryJavadoc} module inspects Javadoc
 * summaries only. It never reads an inline comment, so the half of Rule 1 that requires a stated
 * reason for a non-obvious decision is not mechanically checkable at all: this file can pass
 * {@code mvn validate} and still fail review under that rule's validation gate. The written
 * convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md} and the rule outranks both it and the
 * ruleset.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception clause.</p>
 */
class AuthControllerTest {

    /**
     * The sentence the reference latches when the identifier is absent, at {@code COSGN00C.cbl} L120.
     *
     * <p>Assumptions: transformation rule T8 carries every user-visible string across character for
     * character, and specification section 0.9.1 makes these sentences an externally observable
     * interface, so the space before the three-dot ellipsis is part of the value and is not
     * normalised away. The sentence is repeated here rather than read from the request record, whose
     * own copy is private, so that a change to either is a visible difference between two files
     * rather than a silent agreement.</p>
     */
    private static final String MESSAGE_IDENTIFIER_REQUIRED = "Please enter User ID ...";

    /**
     * The sentence the reference latches when the credential is absent, at {@code COSGN00C.cbl} L125.
     *
     * <p>Assumptions: L125 carries the literal and L126 carries the cursor move that follows it. An
     * inherited specification cited L126 for this sentence, which is the move rather than the text,
     * and the value below was re-read from the file on disk.</p>
     */
    private static final String MESSAGE_CREDENTIAL_REQUIRED = "Please enter Password ...";

    /**
     * The reference sentence for an absent security row, at {@code COSGN00C.cbl} L249.
     *
     * <p>Assumptions: this value exists to be asserted ABSENT and is never expected in a body. The
     * reference told an unknown identifier apart from a refused credential, writing this sentence on
     * its {@code WHEN 13} arm at L247 to L251 and the credential sentence at L242 to L243; the pool
     * this migration authenticates against returns uniform user-existence errors, so the target
     * answers both with the credential sentence and the divergence is documented on
     * {@link AuthController#onRefusedCredential} and in the committed contract.</p>
     */
    private static final String MESSAGE_ABSENT_ROW_UNREACHABLE = "User not found. Try again ...";

    /**
     * The reference sentence for an exchange that could not be evaluated, at {@code COSGN00C.cbl}
     * L254.
     *
     * <p>Assumptions: an inherited specification cited L253, which is the error-flag move above it.
     * L254 carries the literal and L255 the cursor move below it.</p>
     */
    private static final String MESSAGE_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    /**
     * An identifier of the width the reference declares, eight positions.
     *
     * <p>Assumptions: eight is {@code USERIDI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY} L72, and
     * the value is mixed neither in case nor in width so that the pass-through assertion below has
     * something a normalising adapter would visibly alter.</p>
     */
    private static final String SUBMITTED_IDENTIFIER = "Admin001";

    /**
     * A submitted second field with no resemblance to a credential of any provider.
     *
     * <p>Assumptions: the value is arbitrary because nothing in this slice evaluates it -- the
     * identity exchange is substituted -- and it is deliberately shapeless so that no scanner and no
     * reader has to decide whether it is real.</p>
     */
    private static final String SUBMITTED_SECOND_FIELD = "Qm7-Zx42-Lk9";

    /**
     * A value that is present on the wire yet blank, which is the condition the reference tests.
     *
     * <p>Assumptions: {@code COSGN00C.cbl} L118 and L123 test each field against {@code SPACES OR
     * LOW-VALUES}, so a run of spaces is a rejected submission rather than an absent member, and the
     * shared advice classifies a blank rejected value as the blank state rather than the
     * not-acceptable one.</p>
     */
    private static final String BLANK_SUBMISSION = "   ";

    /**
     * The identifier the substituted exchange answers with on an accepted sign-on.
     *
     * <p>Assumptions: it is the folded form of {@link #SUBMITTED_IDENTIFIER}, because the fold is the
     * exchange's to perform and the exchange is substituted here, so this value stands for what a
     * deployment's exchange would return rather than for anything this adapter computes.</p>
     */
    private static final String ISSUED_IDENTIFIER = "ADMIN001";

    /**
     * Provider-shaped diagnostic text the substituted exchange is made to raise.
     *
     * <p>Assumptions: it deliberately resembles the response and reason codes the reference captured at
     * {@code app/cbl/COSGN00C.cbl} L217 and L218, so that a body asserted free of this string is
     * asserted free of exactly the class of detail those codes represent.</p>
     */
    private static final String PROVIDER_DIAGNOSTIC = "RESP=13 RESP2=80 provider-internal detail";

    /**
     * The opaque token value a request presents in its authorization header.
     *
     * <p>Assumptions: it is not a token of any provider and cannot be one -- the decoder that resolves
     * it is substituted, so nothing verifies a signature here. What the value buys is a distinctive
     * string that a rendered refusal body can be asserted not to echo.</p>
     */
    private static final String PRESENTED_TOKEN = "presented-token-value";

    /**
     * A member name a caller might supply for the type, named after the reference's own field.
     *
     * <p>Assumptions: the target declares no member of this name anywhere, which is what the case using
     * it asserts. The name corresponds to {@code CDEMO-USER-TYPE} at {@code app/cpy/COCOM01Y.cpy} L26,
     * so the assertion is anchored to a field that really existed rather than to an invented one.</p>
     */
    private static final String REFERENCE_TYPE_MEMBER = "userType";

    /**
     * A member name a body might carry for a destination, named after the reference's own field.
     *
     * <p>Assumptions: it corresponds to {@code CDEMO-TO-PROGRAM} at {@code app/cpy/COCOM01Y.cpy} L24.
     * The target has no counterpart, because navigation became the client's, so this name is asserted
     * absent from an answered body rather than expected in one.</p>
     */
    private static final String REFERENCE_DESTINATION_MEMBER = "toProgram";

    /**
     * The one-character administrator code the reference's condition name tests for.
     *
     * <p>Assumptions: the quoted value {@code 'A'} is read from {@code app/cpy/COCOM01Y.cpy} L27, and it
     * is used here only as a value a caller might submit in the hope of being believed.</p>
     */
    private static final String ADMIN_TYPE_CODE = "A";

    /**
     * The serialiser the request bodies below are written with.
     *
     * <p>Assumptions: the mapper is built directly rather than taken from the slice, because the
     * bodies below are inputs and asserting on the output shape is what the response matchers do.</p>
     */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * A fixed instant, so that a stamped problem body is reproducible from this file alone.
     *
     * <p>Assumptions: the stamp is caller-supplied. The shared error record takes its timestamp as a
     * constructor argument and the shared timestamp formatter publishes no no-argument accessor, so a
     * body's instant comes from whichever clock the advice was built with and pinning that clock is
     * the only way to make the value predictable.</p>
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-08T09:14:27.481903Z");

    /**
     * The application context holding the adapter, the shared advice and the deployed filter chain.
     *
     * <p>Trade-offs: one context is refreshed for the whole class rather than one per test, and the
     * substituted beans are reset before each test to keep the cases independent. Building the
     * security chain is the expensive half of the refresh, and a per-test context would rebuild it
     * for every case while asserting nothing further; the accepted cost is that a case which
     * reconfigured a bean rather than restubbing it would leak, which is why nothing below does.</p>
     */
    private static AnnotationConfigWebApplicationContext context;

    /**
     * The entry point every request below is issued through, with the deployed chain installed.
     */
    private static MockMvc mockMvc;

    /**
     * The substituted identity exchange, so that no case here reaches a user pool or a database.
     */
    private static CognitoIdentityService identityService;

    /**
     * The substituted token decoder, which answers for the header a caller presents.
     */
    private static JwtDecoder jwtDecoder;

    /**
     * Refreshes the context once and installs the deployed security chain in front of the adapter.
     *
     * <p>Assumptions: the chain is added to the entry point explicitly. A chain is a container-level
     * filter in a running service, and this entry point installs no filter it is not given, so
     * omitting this step would leave every authorization assertion below passing for the wrong reason
     * -- the request would reach the handler with no filter having examined it.</p>
     *
     * <p>Assumptions: the correlation filter is NOT installed and no case asserts on it. The shared
     * kernel's auto-configuration owns its registration, deliberately so, and
     * {@code config/SecurityConfig.java} records at its L98 to L119 that it declares neither a second
     * registration nor an in-chain instance; there is therefore no per-service mechanism for this
     * class to exercise, and asserting the header here would assert a wiring this slice invented.</p>
     */
    @BeforeAll
    static void refreshSliceContext() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(SliceWiring.class);
        context.refresh();

        identityService = context.getBean(CognitoIdentityService.class);
        jwtDecoder = context.getBean(JwtDecoder.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    /**
     * Closes the context so that the class leaves no refreshed application behind it.
     */
    @AfterAll
    static void closeSliceContext() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * Clears the substituted beans so that each case starts from no stubbing and no recorded call.
     */
    @BeforeEach
    void resetSubstitutedBeans() {
        reset(identityService);
        reset(jwtDecoder);
    }

    /**
     * Asserts a blank identifier is answered 400 with the reference sentence and one field entry.
     *
     * <p>Assumptions: the per-field key is the transport name the request record publishes through its
     * declared check order, and it is read from that order rather than written as a literal so the
     * assertion cannot drift from the contract it describes. The reference has no per-field array to
     * key: {@code app/cbl/COSGN00C.cbl} signals the field in error solely by moving minus one into the
     * map's length subfield, at L82, L121, L126, L244, L250 and L255, which positions the cursor. A
     * copybook census over the five auth programs returns zero for the templated highlight book, so
     * the cursor target is the only per-field signal the reference carries and it is what these keys
     * are derived from.</p>
     *
     * <p>Assumptions: exactly one entry is expected because exactly one field was submitted blank. The
     * reference's own single {@code EVALUATE TRUE} at L117 to L130 runs only its first matching arm, so
     * one sentence reaches the screen per turn; the target's array can carry more than one entry, and
     * the case below with both fields blank is what asserts that wider shape.</p>
     *
     * <p>Assumptions: the state is the blank state rather than the not-acceptable one, because the
     * submitted value is present on the wire and blank. That is the same distinction the reference
     * draws at L118 and L123, each testing its field against spaces or low values.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a blank identifier answers 400 with the reference sentence and one field entry")
    void aBlankIdentifierAnswersTheReferenceSentence() throws Exception {
        mockMvc.perform(signOn(BLANK_SUBMISSION, SUBMITTED_SECOND_FIELD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MESSAGE_IDENTIFIER_REQUIRED))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(declaredFieldOrder().get(0)))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.BLANK.name()));

        // Assumptions: the exchange is never reached, because the reference reaches its own
        // security-file read only when validation passed -- app/cbl/COSGN00C.cbl L138 to L140 guard
        // the read with IF NOT ERR-FLG-ON. Asserting the status alone would pass even if the adapter
        // called the exchange first and discarded its answer.
        verifyNoInteractions(identityService);
    }

    /**
     * Asserts a blank second field is answered 400 with its own sentence and its own field entry.
     *
     * <p>Assumptions: the sentence is the reference's, at {@code app/cbl/COSGN00C.cbl} L125, and the key
     * is the second name in the declared check order, which is the field the reference's L126 moves the
     * cursor to. The two sentences differ, so answering either with the other would be a visible
     * behavioural change on a string specification section 0.9.1 treats as an observable interface.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a blank second field answers 400 with its own reference sentence and key")
    void aBlankSecondFieldAnswersItsOwnReferenceSentence() throws Exception {
        mockMvc.perform(signOn(SUBMITTED_IDENTIFIER, BLANK_SUBMISSION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MESSAGE_CREDENTIAL_REQUIRED))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(declaredFieldOrder().get(1)));

        verifyNoInteractions(identityService);
    }

    /**
     * Asserts two blank fields are answered with both entries and the identifier's sentence latched.
     *
     * <p>Assumptions: the order asserted here is the RESPONSE ARRAY's order and the aggregate sentence
     * that goes with it, which are transport properties of the body. The order in which the reference
     * program would have discovered the two fields is behaviour and is asserted in the sibling service
     * package. The two agree because the shared advice sorts by the order the request record declares
     * and latches the first entry's own sentence: the record declares the identifier first, and the
     * reference examines the identifier first at L118 before the second field at L123, which is itself
     * the screen's order -- {@code app/cpy-bms/COSGN00.CPY} declares {@code USERIDI PIC X(8)} at L72
     * before {@code PASSWDI PIC X(8)} at L78.</p>
     *
     * <p>Assumptions: one sentence is latched while both entries are reported, and both halves matter.
     * Latching reproduces the reference, whose single {@code EVALUATE} yields one message per turn;
     * reporting both entries is the documented widening that lets one response name every failing field
     * instead of obliging a caller to resubmit once per field.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("two blank fields answer with both entries and the identifier's sentence latched")
    void twoBlankFieldsLatchTheIdentifierSentenceAndReportBothEntries() throws Exception {
        mockMvc.perform(signOn(BLANK_SUBMISSION, BLANK_SUBMISSION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MESSAGE_IDENTIFIER_REQUIRED))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(declaredFieldOrder().get(0)))
                .andExpect(jsonPath("$.fieldErrors[1].field").value(declaredFieldOrder().get(1)));

        verifyNoInteractions(identityService);
    }

    /**
     * Asserts the sign-on route is served to a caller presenting no token at all.
     *
     * <p>Assumptions: the committed contract declares {@code security: []} on this operation at
     * {@code src/main/resources/openapi/auth-api.yaml} L362, and the chain permits its exact path. A
     * caller has no token at sign-on, that being what the exchange exists to obtain, so a rule that
     * challenged this path would make the whole service unreachable.</p>
     *
     * <p>Assumptions: no forgery token accompanies this state-changing request and it is not refused,
     * which is the observable half of the chain disabling that protection. The reason it is safe to
     * disable is the absence of an ambient credential: authentication here is a bearer token a client
     * attaches deliberately, never a cookie a browser sends on its own, so the confused-deputy
     * condition cannot arise. Adding any cookie-authenticated route to this service would invalidate
     * that reasoning and would have to restore the protection.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the sign-on route is served unauthenticated and answers the authenticated shape")
    void theSignOnRouteIsServedUnauthenticated() throws Exception {
        when(identityService.authenticate(any(SignOnRequest.class)))
                .thenReturn(authenticatedOutcome());

        mockMvc.perform(signOn(SUBMITTED_IDENTIFIER, SUBMITTED_SECOND_FIELD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value(SignOnResponse.OUTCOME_AUTHENTICATED))
                .andExpect(jsonPath("$.userId").value(ISSUED_IDENTIFIER));
    }

    /**
     * Asserts a refused credential is answered 401 with the reference sentence and no field entry.
     *
     * <p>Assumptions: the sentence is {@code app/cbl/COSGN00C.cbl} L242 to L243, carried character for
     * character, and no entry accompanies it. The omission is the point of the status rather than an
     * economy: the reference told a wrong credential apart from an unknown identifier, writing the
     * absent-row sentence on its numeric {@code WHEN 13} arm at L247, and the pool this migration
     * authenticates against returns uniform user-existence errors, so attributing the refusal to one
     * field or the other would restore by attribution exactly the distinction the merged sentence
     * withholds. This case therefore also asserts that the absent-row sentence appears nowhere in the
     * body.</p>
     *
     * <p>Assumptions: no diagnostic from the refusal reaches the body. The reference captured a
     * response and a reason code at L217 and L218 and its sibling user screens displayed them at a
     * terminal inside an enterprise; this operation is published without a token at an internet edge,
     * so the substituted exchange is made to carry a distinctive diagnostic and the body is asserted
     * free of it.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a refused credential answers 401 with the reference sentence and no field entry")
    void aRefusedCredentialAnswersTheReferenceSentence() throws Exception {
        when(identityService.authenticate(any(SignOnRequest.class)))
                .thenThrow(new BadCredentialsException(PROVIDER_DIAGNOSTIC));

        String body = mockMvc.perform(signOn(SUBMITTED_IDENTIFIER, SUBMITTED_SECOND_FIELD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(AuthController.MESSAGE_CREDENTIAL_REFUSED))
                .andExpect(jsonPath("$.code")
                        .value(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED))
                .andExpect(jsonPath("$.fieldErrors.length()").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(MESSAGE_ABSENT_ROW_UNREACHABLE);
        assertThat(body).doesNotContain(PROVIDER_DIAGNOSTIC);
    }

    /**
     * Asserts an unevaluable exchange is answered with the reference sentence for that outcome.
     *
     * <p>Assumptions: the sentence is {@code app/cbl/COSGN00C.cbl} L254, on the reference's
     * {@code WHEN OTHER} arm, and it reaches the body because the shared advice carries a sentence
     * forward only when the raised type is exactly the standard illegal-state one and the sentence
     * itself is one the catalogue recognises. Both tests are needed: every provider failure arrives as
     * a subclass, so the type test keeps provider prose out while letting this repository's own
     * sentence through.</p>
     *
     * <p>Assumptions: the abend block accompanies it and carries operator-facing prose only. The
     * reference has the same shape -- its own abend fields are a code, a culprit, a reason and a
     * message -- so the body is asserted to carry the block while carrying no name of the raised
     * type.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an unevaluable exchange answers with the reference sentence and an abend block")
    void anUnevaluableExchangeAnswersItsOwnReferenceSentence() throws Exception {
        when(identityService.authenticate(any(SignOnRequest.class)))
                .thenThrow(new IllegalStateException(MESSAGE_UNABLE_TO_VERIFY));

        String body = mockMvc.perform(signOn(SUBMITTED_IDENTIFIER, SUBMITTED_SECOND_FIELD))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(MESSAGE_UNABLE_TO_VERIFY))
                .andExpect(jsonPath("$.abend").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(IllegalStateException.class.getSimpleName());
    }

    /**
     * Asserts the health path is permitted rather than challenged by the chain.
     *
     * <p>Assumptions: the distinction asserted here is a permit against a challenge, and not a
     * successful health report. This slice enables the MVC infrastructure directly and no actuator
     * contributes an endpoint to it, so the path is permitted and then found to have no handler.
     * Observed rather than assumed: the dispatcher's no-handler path raises, and the shared advice's
     * last-resort handler renders that as a server fault, so the outcome of a permitted-but-unserved
     * path in this slice is a 500 and not a 404. The assertion is therefore written as the absence of a
     * chain refusal -- no challenge status, no challenge header, neither refusal code in the body --
     * which is the property actually under test and which a challenged path could not satisfy.</p>
     *
     * <p>Trade-offs: the alternative was to assert the 500 outright. It is rejected because the value
     * would document an artefact of this slice's own wiring rather than the chain rule, and would then
     * fail if the shared advice ever gained a handler for a missing route -- a change that would not
     * alter the permit this case exists to assert.</p>
     *
     * <p>Assumptions: the path must stay unauthenticated in a deployment because two consumers request
     * it and neither can present a token -- the load-balancer target group and the container's own
     * health check. Requiring a credential on it, or moving it, would break load-balancer registration
     * and container liveness at the same time. It is framework-provided and is deliberately absent from
     * the committed contract at {@code src/main/resources/openapi/auth-api.yaml}, which describes the
     * operations a client calls.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the health path is permitted by the chain rather than challenged")
    void theHealthPathIsPermittedRatherThanChallenged() throws Exception {
        MvcResult permitted = mockMvc.perform(get("/actuator/health"))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andReturn();

        assertNoChainRefusal(permitted);
    }

    /**
     * Asserts an administrative path is challenged when no token is presented.
     *
     * <p>Assumptions: this case is what proves the deployed chain is installed in front of the adapter
     * at all. Were it absent, every authority assertion in this package would pass for the wrong
     * reason, because the request would reach a handler with nothing having examined it. The pattern is
     * read from the deployed configuration rather than written as a literal, so a path change cannot
     * leave this assertion pointing at a route the chain no longer gates.</p>
     *
     * <p>Assumptions: the refusal carries the shared kernel's unauthenticated code and sentence, and it
     * is rendered by an entry point rather than by the shared advice: a refusal the chain decides never
     * reaches a handler, so it never reaches an advice either, and without the entry point the body
     * would be an empty status.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an administrative path with no token is challenged with the shared 401 body")
    void anAdministrativePathWithNoTokenIsChallenged() throws Exception {
        mockMvc.perform(get(SecurityConfig.USER_COLLECTION_PATH_PATTERN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED))
                .andExpect(jsonPath("$.message")
                        .value(ApiErrorSecurityHandlers.MESSAGE_UNAUTHENTICATED));
    }

    /**
     * Asserts a token whose group claim is absent yields no authority and no failure.
     *
     * <p>Assumptions: an absent claim is a legitimate token shape and the shared converter treats it as
     * an empty collection rather than raising or answering null. The observable consequence is a
     * refusal on an authority ground -- 403 with the shared forbidden code -- and specifically not a
     * fault, so a 500 here would mean the converter raised on a claim a real pool can legitimately
     * omit. The body is additionally asserted not to echo the presented token, since a rendered refusal
     * is the one place token content could leak into a response.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a token with no group claim is refused on authority grounds and never fails")
    void aTokenWithNoGroupClaimIsRefusedOnAuthorityGrounds() throws Exception {
        stubDecoderWithGroups(null);

        String body = mockMvc.perform(get(SecurityConfig.USER_COLLECTION_PATH_PATTERN)
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_FORBIDDEN))
                .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.MESSAGE_FORBIDDEN))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(PRESENTED_TOKEN);
    }

    /**
     * Asserts the administrator group in a signed claim is what admits a request past the chain.
     *
     * <p>Refactoring Rationale: the reference derived this decision from a value that had made a round
     * trip through the terminal. {@code app/cbl/COSGN00C.cbl} L227 moves the stored user type into the
     * session structure, L230 tests the admin condition name over it, and L232 and L237 select between
     * two program transfers; that structure is handed back to the terminal at L98 to L102, so the value
     * the next turn tested was one the client had returned. Here the group arrives in a signed claim
     * and the caller cannot assert it. What the reference expressed as a transfer between programs is,
     * per transformation rule T5, a route change the client performs, so this service publishes an
     * authority decision and no destination.</p>
     *
     * <p>Assumptions: the authority is the identity provider's group name VERBATIM, which is why the
     * deployed rules use the authority-family predicate and not the role-family one. Were a prefix ever
     * added to the emitted authority, the role-family predicate would look for something the converter
     * never produces, match nothing, and refuse every administrative request with 403 while the context
     * started cleanly -- a mismatch visible only in behaviour, which is precisely what this case and
     * the one above it detect from opposite sides.</p>
     *
     * <p>Assumptions: the same request is issued twice, once with no header and once with the token, and
     * the pair is the assertion. The first is challenged and the second is not, so what changed the
     * outcome is the claim and nothing else; asserting only the second would leave a chain that
     * permitted the path outright indistinguishable from one that read the claim.</p>
     *
     * <p>Assumptions: the admitted request then finds no handler, because this slice registers the
     * sign-on adapter alone, and the shared advice renders a missing route as a server fault as recorded
     * on the health case above. The admission is therefore asserted as the absence of a chain refusal
     * rather than as a particular status; the operations behind this path are asserted by the roster
     * adapter's own cases.</p>
     *
     * @throws Exception if either request cannot be performed
     */
    @Test
    @DisplayName("the administrator group in a signed claim admits a request past the chain")
    void theAdministratorGroupInASignedClaimAdmitsTheRequest() throws Exception {
        mockMvc.perform(get(SecurityConfig.USER_COLLECTION_PATH_PATTERN))
                .andExpect(status().isUnauthorized());

        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));

        MvcResult admitted = mockMvc.perform(get(SecurityConfig.USER_COLLECTION_PATH_PATTERN)
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andReturn();

        assertNoChainRefusal(admitted);
    }

    /**
     * Asserts the adapter hands both submitted values on exactly as received.
     *
     * <p>Refactoring Rationale: the reference folded the case of BOTH fields before comparing --
     * {@code app/cbl/COSGN00C.cbl} L132 to L134 push the identifier through the upper-case function
     * into two targets and L135 to L136 push the second field into its own work field -- which made its
     * comparison case-insensitive in the credential as well as in the identifier. Only the identifier's
     * fold survives, and it belongs to the exchange that owns the comparison; folding the second field
     * anywhere would weaken the value on the one path this migration publishes without a token. The
     * divergence is documented rather than silently introduced.</p>
     *
     * <p>Trade-offs: what this case can assert is therefore pass-through and not the fold. The fold
     * moved to the substituted exchange, so it is asserted in the sibling service package, and this
     * layer's own contract is that it adds no normalisation of its own -- a submitted mixed-case
     * identifier reaches the exchange mixed. Asserting a folded value here would assert a
     * normalisation the adapter deliberately does not perform.</p>
     *
     * @throws Exception if the request cannot be performed, or if the submitted second value cannot be
     *     read back from the captured record
     */
    @Test
    @DisplayName("the adapter hands both submitted values on unchanged, folding neither")
    void theAdapterHandsBothSubmittedValuesOnUnchanged() throws Exception {
        when(identityService.authenticate(any(SignOnRequest.class)))
                .thenReturn(authenticatedOutcome());

        mockMvc.perform(signOn(SUBMITTED_IDENTIFIER, SUBMITTED_SECOND_FIELD))
                .andExpect(status().isOk());

        ArgumentCaptor<SignOnRequest> submitted = ArgumentCaptor.forClass(SignOnRequest.class);
        verify(identityService).authenticate(submitted.capture());
        assertThat(submitted.getValue().userId()).isEqualTo(SUBMITTED_IDENTIFIER);
        assertThat(secondSubmittedValueOf(submitted.getValue())).isEqualTo(SUBMITTED_SECOND_FIELD);
    }

    /**
     * Asserts no body this adapter answers with carries the submitted second field back.
     *
     * <p>Refactoring Rationale: this is the guard for the one point in the migration where parity is
     * explicitly declined, registered as {@code D-4} in
     * {@code docs/architecture/cobol-to-service-traceability.md}. What was wrong with the reference
     * approach is concrete and is in four immutable places: {@code app/cpy/CSUSR01Y.cpy} L21 declares
     * an eight-character cleartext field at zero-based offset 48 of the 80-byte security record;
     * {@code app/cbl/COSGN00C.cbl} L223 compares it directly against what was typed;
     * {@code app/cbl/COUSR01C.cbl} L157 writes a typed value straight into it; and
     * {@code app/cbl/COUSR02C.cbl} L169 reads it back out to the screen, so the stored value was
     * displayed to an administrator.</p>
     *
     * <p>Alternatives Considered: two, and both are rejected. Porting the file read would carry the
     * whole defect class across unchanged. Adding a hashed local column would narrow the exposure while
     * keeping this service in the business of holding credential material and of choosing and rotating
     * a hashing parameter. Delegating to the managed pool removes the class rather than mitigating it:
     * {@code src/main/resources/db/migration/V1__auth.sql} declares no such column at all, so there is
     * nothing for a body to carry.</p>
     *
     * <p>Assumptions: the member name being asserted absent is read from the request record's own
     * component descriptor rather than written as a literal, for two reasons. It cannot drift from the
     * contract, and this file is held to naming no credential-shaped identifier anywhere -- the same
     * prohibition this module's test profile records in its own register of deliberate absences.</p>
     *
     * @throws Exception if any of the three requests cannot be performed
     */
    @Test
    @DisplayName("no answered body carries the submitted second field back to the caller")
    void noAnsweredBodyCarriesTheSubmittedSecondFieldBack() throws Exception {
        String forbiddenMember = "\"" + secondSubmittedFieldName() + "\"";

        when(identityService.authenticate(any(SignOnRequest.class)))
                .thenReturn(authenticatedOutcome());
        String accepted = contentOf(signOn(SUBMITTED_IDENTIFIER, SUBMITTED_SECOND_FIELD));

        String rejected = contentOf(signOn(BLANK_SUBMISSION, SUBMITTED_SECOND_FIELD));

        reset(identityService);
        when(identityService.authenticate(any(SignOnRequest.class)))
                .thenThrow(new BadCredentialsException(PROVIDER_DIAGNOSTIC));
        String refused = contentOf(signOn(SUBMITTED_IDENTIFIER, SUBMITTED_SECOND_FIELD));

        assertThat(accepted).doesNotContain(forbiddenMember, SUBMITTED_SECOND_FIELD);
        assertThat(rejected).doesNotContain(forbiddenMember, SUBMITTED_SECOND_FIELD);
        assertThat(refused).doesNotContain(forbiddenMember, SUBMITTED_SECOND_FIELD);
    }

    /**
     * Asserts a served request creates no session and issues no cookie.
     *
     * <p>Refactoring Rationale: the continuity a session would hold is exactly what this migration
     * removed. The reference ends its task at every screen turn and carries all inter-turn state in one
     * communication area: {@code app/cbl/COSGN00C.cbl} L65 declares it, L66 and L67 size it against the
     * length the terminal returns, L80 detects a first entry from that length being zero, and L98 to
     * L102 hand it back at the end of every turn. The five auth transactions are defined with a zero
     * transaction work area in {@code app/csd/CARDDEMO.CSD}, so that structure was the only channel and
     * decomposing it is sufficient. Identity now arrives as claims, selection context in the request
     * path and navigation as client-side routing, which is what makes horizontally-scaled tasks behind
     * a load balancer viable with no session affinity to preserve.</p>
     *
     * <p>Assumptions: the reference's re-entry discriminator has no counterpart, so error presentation
     * cannot depend on a remembered turn. The reference gated its field highlight on that flag, and the
     * cases above prove the target's rejection bodies stand alone: each is a complete answer to one
     * request, and nothing in this class primes a prior call for a later one to depend on.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a served request creates no session and issues no cookie")
    void aServedRequestCreatesNoSessionAndIssuesNoCookie() throws Exception {
        when(identityService.authenticate(any(SignOnRequest.class)))
                .thenReturn(authenticatedOutcome());

        MvcResult result = mockMvc.perform(signOn(SUBMITTED_IDENTIFIER, SUBMITTED_SECOND_FIELD))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    /**
     * Asserts no caller-supplied type reaches the authority decision or an answered body.
     *
     * <p>Refactoring Rationale: in the reference the type WAS a caller-supplied value, and that is the
     * security property this decomposition changes rather than merely tidies.
     * {@code app/cpy/COCOM01Y.cpy} L26 declares {@code 10 CDEMO-USER-TYPE PIC X(01).} with its only two
     * condition names on L27 and L28, and {@code app/cbl/COSGN00C.cbl} hands the whole structure back to
     * the terminal at L98 to L102, so the value tested on the next turn had made a round trip through
     * the client. Here the decision reads a signed claim, so a caller has nothing to assert: this case
     * presents a token carrying the ordinary group together with a parameter claiming the administrator
     * type, and the request is refused exactly as it is without the parameter.</p>
     *
     * <p>Assumptions: the transport keys are exactly the request record's components, in their declared
     * order, so there is no third channel for a type to arrive on in the first place. The two names
     * asserted absent from an answered body are named after the reference fields they would correspond
     * to -- the type at L26 and the destination at {@code CDEMO-TO-PROGRAM} on L24 -- and the target has
     * a counterpart to neither: what the reference expressed as a program transfer at L230 to L240 is a
     * route the client chooses from its own claim, so this service answers with no destination.</p>
     *
     * @throws Exception if either request cannot be performed
     */
    @Test
    @DisplayName("no caller-supplied type reaches the authority decision or an answered body")
    void noCallerSuppliedTypeReachesTheAuthorityDecision() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.USER_AUTHORITY));

        mockMvc.perform(get(SecurityConfig.USER_COLLECTION_PATH_PATTERN)
                        .param(REFERENCE_TYPE_MEMBER, ADMIN_TYPE_CODE)
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_FORBIDDEN));

        when(identityService.authenticate(any(SignOnRequest.class)))
                .thenReturn(authenticatedOutcome());
        String accepted = contentOf(signOn(SUBMITTED_IDENTIFIER, SUBMITTED_SECOND_FIELD));

        assertThat(accepted).doesNotContain(REFERENCE_TYPE_MEMBER, REFERENCE_DESTINATION_MEMBER);
        assertThat(declaredFieldOrder()).isEqualTo(
                Arrays.stream(SignOnRequest.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList());
    }

    /**
     * Builds a sign-on request carrying the two submitted values as a JSON body.
     *
     * <p>Assumptions: the body is serialised from the request record itself rather than assembled as
     * text, so a member the contract renames cannot leave this file still sending the old name and
     * still compiling. The path is the constant the adapter publishes, for the same reason.</p>
     *
     * @param identifier the value to submit as the identifier; may be blank, which is a case under
     *     assertion, and must not be {@code null}
     * @param secondField the value to submit as the second field; may be blank, which is a case under
     *     assertion, and must not be {@code null}
     * @return the request to perform, with the JSON content type set; never {@code null}
     */
    private static MockHttpServletRequestBuilder signOn(String identifier, String secondField) {
        return post(AuthController.SIGNON_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(new SignOnRequest(identifier, secondField)));
    }

    /**
     * Performs a request and returns the body it was answered with, whatever its status.
     *
     * <p>Assumptions: no status expectation is applied here, because the caller of this helper is
     * asserting a property that must hold of EVERY body -- an accepted one, a rejected one and a
     * refused one alike -- and applying one would restrict it to the statuses the helper knew about.</p>
     *
     * @param request the request to perform; must not be {@code null}
     * @return the response body as text, empty when the response carried none; never {@code null}
     * @throws Exception if the request cannot be performed or its body cannot be read
     */
    private static String contentOf(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    /**
     * Builds the accepted outcome the substituted exchange answers an acceptable submission with.
     *
     * <p>Assumptions: the token values are opaque placeholders and nothing in this slice inspects them,
     * because the exchange that would mint them is substituted. The identifier is the folded form,
     * standing for what a deployment's exchange returns.</p>
     *
     * @return the authenticated outcome shape, carrying the discriminator the contract declares for it;
     *     never {@code null}
     */
    private static SignOnResponse authenticatedOutcome() {
        return new SignOnResponse(SignOnResponse.OUTCOME_AUTHENTICATED, ISSUED_IDENTIFIER,
                "issued-access-token", "issued-identity-token", "issued-renewal-token", "Bearer",
                3600);
    }

    /**
     * Reads the transport field keys in the order the request contract declares them.
     *
     * <p>Assumptions: the order published by the request record is the order the shared advice sorts a
     * rejection into and the order it latches its aggregate sentence from, so reading it here means the
     * key assertions describe the contract rather than duplicating a guess about it. A record instance
     * is needed because the order is published as an instance method of the shared ordering interface,
     * and the two values handed to it are never read.</p>
     *
     * @return the declared field keys, identifier first; never {@code null}
     */
    private static List<String> declaredFieldOrder() {
        return new SignOnRequest(SUBMITTED_IDENTIFIER, SUBMITTED_SECOND_FIELD).fieldOrder();
    }

    /**
     * Reads the name of the request record's second component from its own descriptor.
     *
     * <p>Assumptions: the second component is the one the reference's L126 moves the cursor to, and its
     * position is fixed by the declared order the transport contract publishes. It is read through the
     * descriptor rather than spelled out because this file names no credential-shaped identifier
     * anywhere, which is the prohibition that accompanies the declined-parity divergence.</p>
     *
     * @return the second component's name; never {@code null}
     */
    private static String secondSubmittedFieldName() {
        RecordComponent[] components = SignOnRequest.class.getRecordComponents();
        return components[1].getName();
    }

    /**
     * Reads the value of the request record's second component from a captured submission.
     *
     * <p>Assumptions: the component's own accessor is invoked through its descriptor for the same
     * naming reason as above; the accessor is public on a public record, so no visibility is widened to
     * reach it.</p>
     *
     * @param submitted the captured submission to read; must not be {@code null}
     * @return the value submitted as the second field; never {@code null} on any path here
     * @throws ReflectiveOperationException if the component's accessor cannot be invoked, which a
     *     rename of the contract would cause and which should surface here rather than silently pass
     */
    private static String secondSubmittedValueOf(SignOnRequest submitted)
            throws ReflectiveOperationException {
        RecordComponent[] components = SignOnRequest.class.getRecordComponents();
        return (String) components[1].getAccessor().invoke(submitted);
    }

    /**
     * Stubs the substituted decoder to resolve any presented token into a token with these groups.
     *
     * <p>Assumptions: a null argument means the group claim is ABSENT from the token rather than present
     * and empty, and the two are different token shapes: a pool omits the claim for a user in no group.
     * The claim name is the shared kernel's own constant, so a rename there reaches this stub instead of
     * leaving it asserting against a name the converter no longer reads.</p>
     *
     * <p>Assumptions: the token carries a subject and a validity window because the resource server
     * derives a principal name from the subject; no validator runs, since the decoder that would apply
     * them is the substituted one.</p>
     *
     * @param groups the group names to carry in the claim, or {@code null} to omit the claim entirely
     */
    private static void stubDecoderWithGroups(List<String> groups) {
        Jwt.Builder token = Jwt.withTokenValue(PRESENTED_TOKEN)
                .header("alg", "RS256")
                .subject("11111111-2222-3333-4444-555555555555")
                .issuedAt(FIXED_INSTANT.minusSeconds(60))
                .expiresAt(FIXED_INSTANT.plusSeconds(600));
        if (groups != null) {
            token.claim(JwtRoleConverter.GROUPS_CLAIM, groups);
        }
        when(jwtDecoder.decode(anyString())).thenReturn(token.build());
    }

    /**
     * Builds the authorization header value a caller presents a bearer token in.
     *
     * @return the header value, scheme included; never {@code null}
     */
    private static String bearerHeader() {
        return "Bearer " + PRESENTED_TOKEN;
    }

    /**
     * Asserts an answered request was refused by neither of the chain's two refusal decisions.
     *
     * <p>Assumptions: both refusals are recognisable by three marks together, and all three are checked
     * because any one alone is weaker than the claim. A challenged request answers 401 and carries the
     * shared unauthenticated code in its body; a denied one answers 403 and carries the shared forbidden
     * code. Checking the status alone would miss a renderer that answered a refusal under another
     * status, and checking the body alone would miss one that carried no body.</p>
     *
     * @param answered the result to inspect; must not be {@code null}
     * @throws Exception if the response body cannot be read
     */
    private static void assertNoChainRefusal(MvcResult answered) throws Exception {
        assertThat(answered.getResponse().getStatus())
                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value())
                .isNotEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(answered.getResponse().getContentAsString())
                .doesNotContain(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED,
                        GlobalExceptionHandler.CODE_FORBIDDEN);
    }

    /**
     * The wiring this slice refreshes: the adapter, the shared advice and the deployed filter chain.
     *
     * <p>Assumptions: the chain and the token converter are obtained by CALLING the deployed
     * configuration's own methods rather than by registering that class, and the distinction is the
     * whole reason this type exists. Registering {@code config/SecurityConfig.java} would also
     * register its decoder factory, which resolves the issuer document while the context refreshes
     * and cannot complete against this module's deliberately unreachable test issuer. Calling the two
     * methods that matter leaves every rule, every handler and the group-to-authority translation
     * exactly as deployed while the one bean that needs a route to a provider is substituted.</p>
     *
     * <p>Assumptions: the two group names handed to the converter factory are the shared kernel's own
     * compiled constants. That factory compares what it is given against those constants and refuses
     * to start on a mismatch, so passing them is what keeps this slice's authority derivation
     * identical to a deployment's rather than merely similar to it.</p>
     *
     * <p>Trade-offs: the MVC infrastructure is enabled here rather than inherited from a started
     * application, so the endpoints an actuator contributes at run time are absent from this slice.
     * The consequence is stated on the case that exercises the health path: a permitted path answers
     * with the absence of a handler, and a challenged one would answer 401, so the two outcomes remain
     * distinguishable.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class SliceWiring {

        /**
         * Supplies the fixed clock the adapter and the shared advice stamp their bodies from.
         *
         * @return a clock pinned to {@link AuthControllerTest#FIXED_INSTANT}; never {@code null}
         */
        @Bean
        Clock clock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }

        /**
         * Supplies the substituted identity exchange the adapter delegates to.
         *
         * <p>Assumptions: the exchange is substituted rather than partially wired, because the
         * three-way outcome distinction it owns is asserted against a mocked repository in the sibling
         * service package. Asserting it twice would give a later change two places to update and one
         * place to forget.</p>
         *
         * @return a substitute for the identity exchange, with no stubbing applied; never {@code null}
         */
        @Bean
        CognitoIdentityService identityService() {
            return mock(CognitoIdentityService.class);
        }

        /**
         * Supplies the adapter under assertion, built over the substituted exchange.
         *
         * @param identityService the substituted identity exchange to delegate to; must not be
         *     {@code null}
         * @param clock the clock the adapter stamps its refusal bodies from; must not be {@code null}
         * @return the adapter, wired exactly as a deployment wires it; never {@code null}
         */
        @Bean
        AuthController authController(CognitoIdentityService identityService, Clock clock) {
            return new AuthController(identityService, clock);
        }

        /**
         * Supplies the shared error advice, so that the rendered body shape is the deployed one.
         *
         * <p>Assumptions: the advice is registered as a bean of its own type and is discovered because
         * it carries the advice stereotype, which is how the resolver finds it in a running service
         * too. It is registered explicitly because it lives outside this context's component-scan root
         * and reaches a deployment through the shared kernel's auto-configuration, which a slice
         * assembled by hand does not apply; the entry point class carries no import of it, so nothing
         * else here would bring it in.</p>
         *
         * @param clock the clock the advice stamps its bodies from; must not be {@code null}
         * @return the shared advice; never {@code null}
         */
        @Bean
        GlobalExceptionHandler globalExceptionHandler(Clock clock) {
            return new GlobalExceptionHandler(clock);
        }

        /**
         * Supplies the substituted decoder the resource-server filter resolves a presented token with.
         *
         * @return a substitute for the token decoder, with no stubbing applied; never {@code null}
         */
        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }

        /**
         * Supplies the deployed token-to-authentication converter, group names included.
         *
         * @return the converter the deployed configuration builds, carrying the shared
         *     group-to-authority translation; never {@code null}
         */
        @Bean
        JwtAuthenticationConverter jwtAuthenticationConverter() {
            return new SecurityConfig().jwtAuthenticationConverter(
                    JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);
        }

        /**
         * Supplies the deployed filter chain, rules, refusal renderers and session policy included.
         *
         * @param http the chain builder this context contributes; must not be {@code null}
         * @param converter the deployed token-to-authentication converter; must not be {@code null}
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
    }
}
