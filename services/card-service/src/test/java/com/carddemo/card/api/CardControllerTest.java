package com.carddemo.card.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.card.config.SecurityConfig;
import com.carddemo.card.dto.AdminCardDetail;
import com.carddemo.card.dto.CardDetail;
import com.carddemo.card.dto.CardSummary;
import com.carddemo.card.dto.CardUpdateRequest;
import com.carddemo.card.mapper.CardMapper;
import com.carddemo.card.service.CardAdminViewService;
import com.carddemo.card.service.CardListService;
import com.carddemo.card.service.CardUpdateService;
import com.carddemo.card.service.CardViewService;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.security.SealedSelector;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the five card operations through the DEPLOYED filter chain and the DEPLOYED error advice.
 *
 * <h2>What this class asserts, and why no sibling can assert it</h2>
 *
 * <p>Purpose: this is the only class in this package whose requests pass through an authorization
 * chain and whose refusals are rendered by the shared advice. The three classes beside it are
 * assembled from constructors: the census reflects over the method table without sending a request,
 * and the two dispatcher classes call
 * {@code org.springframework.test.web.servlet.setup.MockMvcBuilders#standaloneSetup} with no chain at
 * all. Two properties are therefore unassertable anywhere in this package until this class exists --
 * that an administrative caller is admitted to the one route disclosing a full primary account number
 * while an ordinary caller is refused it, and that a refusal leaves the service as the shared problem
 * document rather than as the container's own error representation.
 *
 * <p>Purpose: the module's own test profile,
 * {@code services/card-service/src/test/resources/application-test.yml}, names this class three times
 * and fixes what it is for -- at its line 28 as the reason the module declares
 * {@code spring-security-test}, and at its lines 479 and 491 as the class whose whole claim is the
 * split between an administrative caller and an ordinary one on the route that returns an unmasked
 * primary account number. That expectation is what the cases below discharge.
 *
 * <p>Assumptions: the specification is the three reference programs, read and never modified.
 * {@code app/cbl/COCRDLIC.cbl} at 1459 lines is the browse, {@code app/cbl/COCRDSLC.cbl} at 887 lines
 * is the detail read and {@code app/cbl/COCRDUPC.cbl} at 1560 lines is the edit, reached in the
 * baseline as CICS transactions {@code CCLI}, {@code CCDL} and {@code CCUP} whose stanzas open at
 * {@code app/csd/CARDDEMO.CSD:357}, {@code :347} and {@code :367}. Every sentence asserted below was
 * read from those files and is cited at the case that asserts it.
 *
 * <h2>How the slice is assembled, and why not with the slice annotation</h2>
 *
 * <p>Alternatives Considered: {@code org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest} is
 * the obvious wiring and is not on this module's test class path. Spring Boot 4 moved the servlet
 * slice annotation into a separate artifact that {@code services/card-service/pom.xml} does not
 * declare and that {@code spring-boot-starter-test} does not carry -- unpacking the pinned
 * {@code spring-boot-test-autoconfigure} jar yields one slice descriptor, for the JSON testers, and no
 * MVC slice class exists in the resolved dependency set at all. Adding the artifact would edit a POM
 * this class does not own, so the slice is assembled from types that ARE on the path. That shape is not
 * invented here: the sign-on slice at
 * {@code services/auth-service/src/test/java/com/carddemo/auth/api/AuthControllerTest.java} records the
 * same finding and assembles itself the same way, and it is cited by path because this module declares no
 * dependency on that one and none is implied by following its pattern.
 *
 * <p>Alternatives Considered: a full application context. Rejected because it would start the
 * persistence layer and the schema migration in order to assert statuses, body members and message
 * sentences, giving a routing-and-serialisation concern a database dependency; and because it would
 * create the decoder bean {@code com.carddemo.card.config.SecurityConfig} declares, whose factory
 * resolves the issuer document while the context refreshes.
 *
 * <h2>The two ways this class could pass while asserting nothing, and how each is closed</h2>
 *
 * <p>Assumptions: CT-01 -- the shared advice is NOT part of this bounded context and a hand-assembled
 * context brings it in only because {@link SliceWiring} declares it.
 * {@code com.carddemo.common.error.GlobalExceptionHandler} carries {@code @RestControllerAdvice} at
 * line 160 of its own file, sits in {@code com.carddemo.common.error} and therefore outside the
 * {@code com.carddemo.card} scan root, and reaches a running service through exactly one mechanism:
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, the single class named in the shared
 * kernel's {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports},
 * whose nested servlet error configuration declares it. A context refreshed by hand applies NO
 * auto-configuration, so nothing would register it here. Alternatives Considered: leaving it out and
 * asserting statuses only. Rejected because the container would then answer with its own error
 * representation, every assertion about the problem document, the 404 and the 409 would still pass,
 * and this class would report success having verified none of them. The case at
 * {@link #theAdviceRendersARefusalAsTheSharedProblemDocument()} exists to fail if that registration is
 * ever dropped.
 *
 * <p>Assumptions: CT-02 -- {@code com.carddemo.card.config.SecurityConfig} owns the entire
 * authorization matrix, including the administrator gate on {@code /api/v1/admin/cards/*} and the
 * closing {@code denyAll}, and {@link CardController} carries no authority expression of any kind, so
 * reading the controller reveals nothing about who may call it. A hand-assembled context installs
 * neither the chain nor a decoder. Alternatives Considered: asserting the routes with no chain
 * present. Rejected because an assertion that an administrator is admitted and an ordinary caller
 * refused would pass with nothing present to refuse anybody -- which is why {@link SliceWiring} obtains
 * the chain by CALLING that configuration's own
 * {@code filterChain(HttpSecurity, JwtAuthenticationConverter, Clock)} and
 * {@code jwtAuthenticationConverter(String, String)} methods rather than by registering the class.
 * Registering the class would also register its decoder factory, and
 * {@code NimbusJwtDecoder.withIssuerLocation(...).build()} issues the provider-document request while
 * the context refreshes, against an issuer the test profile pins to an RFC 2606 reserved name. The
 * decoder is therefore the ONE bean substituted; every rule, every refusal renderer and the
 * group-to-authority translation are the deployed ones.
 *
 * <p>Refactoring Rationale: CT-03 -- the administrator gate is a capability the baseline platform did
 * not have, and that is the reason it is asserted here rather than a preference for a stricter
 * default. All three reference transactions are defined {@code RESSEC(NO) CMDSEC(NO)}, on the single
 * lines {@code app/csd/CARDDEMO.CSD:354} for {@code CCDL}, {@code :364} for {@code CCLI} and
 * {@code :375} for {@code CCUP}, so the region performed no resource-level and no command-level
 * authorization on any of them; the administrator-versus-user distinction lived in
 * {@code CDEMO-USER-TYPE}, a field the terminal client echoed back inside the communication area. A
 * caller could therefore assert its own type. The migrated route reads a group membership out of a
 * signed claim the caller cannot author, which moves the decision to the server. The same three
 * stanzas declare {@code CONFDATA(NO)} at {@code :353}, {@code :363} and {@code :374}, so the region
 * applied no narrowing to what it sent to the screen either, which is why the masked and unmasked
 * shapes are separate response types here and why both are asserted member by member below.
 *
 * <h2>What parity here rests on</h2>
 *
 * <p>Assumptions: no executable oracle exists for any route this class drives, so parity rests on
 * sentences and widths transcribed from the reference programs and their copybooks, never on a recorded
 * output stream. {@code tests/README.md:83-85} states that the online {@code CO*} CICS programs cannot
 * run end to end without a CICS runtime, which the build host does not provide, and that suite's golden
 * masters cover the batch chain; none of the three card programs is a batch program. Shipping inputs
 * with no recorded output is that suite's own practice, described at {@code tests/README.md:139-146}
 * for its export domain and called internally consistent there. That suite is REFERENCE: nothing here
 * modifies or re-pins it, and its graded return codes do not reach this file -- the gates a class in
 * this directory answers to are binary, so a partial result here is a failure.
 *
 * <p>Trade-offs: CT-04 -- the bodies are asserted as SERIALISED JSON rather than as deserialised
 * objects. The compromise is readability: a JSON path is harder to read than a field access, and the
 * expressions below are correspondingly verbose. It is accepted because the wire shape is the external
 * interface -- {@code src/main/resources/openapi/card-api.yaml} publishes these member names and
 * {@code ui/src/api/cards.ts} is written against them -- so a rename that kept the Java compiling
 * while changing a serialised property is exactly the regression this class must catch, and an
 * assertion over a deserialised object cannot see it. Two cases go further and assert the COMPLETE set
 * of top-level member names, because a path assertion proves what IS published and this package also
 * has to prove what is NOT.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return-value or exception at-clause; every member below carries its own.
 */
class CardControllerTest {

    /**
     * The reference sentence for an optimistic-lock refusal, at {@code app/cbl/COCRDUPC.cbl:207-208}.
     *
     * <p>Assumptions: CT-06 -- {@code :207} carries the condition name and {@code :208} the literal,
     * inside the value set declared under {@code 05 WS-RETURN-MSG PIC X(75).} at {@code :173} with its
     * blank sentinel at {@code :174}. Two details of the value are load-bearing and are stated so a
     * later editor does not normalise them: {@code some one} is TWO words, and the sentence ends with no
     * period. It is spelled out here rather than read from
     * {@link com.carddemo.common.error.ApiError#COACTUPC_RECORD_CHANGED} so that a change to either is a
     * visible difference between two files rather than a silent agreement between one file and itself.
     *
     * <p>Assumptions: no character count is asserted against this value anywhere below. A neighbouring
     * brief gives the length as 45 and a direct measurement gives 46, so any length assertion here is
     * derived from the literal itself or compared against
     * {@link com.carddemo.common.error.ApiError#MESSAGE_RENDERING_WIDTH}, and never written as a figure
     * copied from prose.
     */
    private static final String MESSAGE_RECORD_CHANGED =
            "Record changed by some one else. Please review";

    /**
     * The reference sentence for a lock that could not be taken, at {@code app/cbl/COCRDUPC.cbl:205-206}.
     *
     * <p>Assumptions: this is a DIFFERENT condition from the one above and is asserted separately so the
     * two cannot be conflated. The reference declares three neighbouring conditions in one value set --
     * a lock-acquisition failure at {@code :205-206}, the before-image mismatch at {@code :207-208} and
     * a rewrite failure at {@code :209-210} -- and only the middle one is the optimistic-lock outcome.
     */
    private static final String MESSAGE_COULD_NOT_LOCK = "Could not lock record for update";

    /**
     * The reference sentence for a rewrite that failed, at {@code app/cbl/COCRDUPC.cbl:209-210}.
     *
     * <p>Assumptions: this value is asserted only to be DISTINCT from the two above. The migrated
     * conflict advice recognises three conditions and answers the third with a referential-integrity
     * sentence rather than this one, so the rewrite-failure sentence is carried by its own constant on
     * {@link com.carddemo.common.error.GlobalExceptionHandler} and reached by a different path. Naming it
     * here is what keeps all four sentences separable in one place.
     */
    private static final String MESSAGE_UPDATE_FAILED = "Update of record failed";

    /**
     * The reference sentence for a search that matched no card, at {@code app/cbl/COCRDUPC.cbl:203-204}.
     *
     * <p>Assumptions: the sentence ends with no period, unlike the no-change sentence two conditions
     * above it at {@code :187-188}, which does. Both spellings are the reference's and neither is
     * regularised.
     */
    private static final String MESSAGE_CARD_NOT_FOUND = "Did not find cards for this search condition";

    /**
     * The reference sentence for a blank embossed name, at {@code app/cbl/COCRDUPC.cbl:181-182}.
     *
     * <p>Assumptions: this is the BLANK arm of {@code 1230-EDIT-NAME}, whose paragraph opens at
     * {@code :806}. The neighbouring arm answers a value that is present but not alphabetic, and the two
     * are rendered differently on the screen, which is what the state assertions below turn on.
     */
    private static final String MESSAGE_NAME_NOT_PROVIDED = "Card name not provided";

    /**
     * The reference sentence for a non-alphabetic embossed name, at {@code app/cbl/COCRDUPC.cbl:183-184}.
     */
    private static final String MESSAGE_NAME_MUST_BE_ALPHA =
            "Card name can only contain alphabets and spaces";

    /**
     * The reference sentence for a status outside its two values, at {@code app/cbl/COCRDUPC.cbl:195-196}.
     *
     * <p>Assumptions: the gate is {@code 1240-EDIT-CARDSTATUS} at {@code :845} and the reference latches
     * this one sentence on both of that paragraph's arms, the blank one and the out-of-domain one.
     */
    private static final String MESSAGE_STATUS_MUST_BE_YES_NO = "Card Active Status must be Y or N";

    /**
     * The reference sentence for an unacceptable expiry month, at {@code app/cbl/COCRDUPC.cbl:197-198}.
     *
     * <p>Assumptions: the gate is {@code 1250-EDIT-EXPIRY-MON} at {@code :877}. The wording says between
     * 1 and 12 while the accepted form is zero-padded to two positions; the wording is carried unchanged
     * because a user-visible sentence is not reworded to match an implementation detail.
     */
    private static final String MESSAGE_EXPIRY_MONTH_NOT_VALID =
            "Card expiry month must be between 1 and 12";

    /**
     * The reference sentence for an unacceptable expiry year, at {@code app/cbl/COCRDUPC.cbl:199-200}.
     *
     * <p>Assumptions: the gate is {@code 1260-EDIT-EXPIRY-YEAR} at {@code :913}. None of the four gates
     * short-circuits the others, which is why the accumulation case below expects several entries rather
     * than one.
     */
    private static final String MESSAGE_EXPIRY_YEAR_NOT_VALID = "Invalid card expiry year";

    /**
     * The instant every rendered problem document is stamped with, so a body stays comparable.
     *
     * <p>Assumptions: the shared error record takes its timestamp from whichever clock the advice was
     * built with, so pinning that clock is the only way to make the stamp predictable. The value is
     * inside the range the shared formatter supports and carries microsecond precision, which is the
     * 26-character form that formatter emits.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-03-04T05:06:07.891234Z");

    /**
     * Key material for the real selector seal; test-only and deliberately not a credential.
     *
     * <p>Assumptions: a real sealer is used rather than a contrived 59-character string because both
     * card response shapes REFUSE an unsealed key in their compact constructors, so a fixture built any
     * other way could not be constructed at all. Its length clears the sealer's declared minimum, and it
     * seals nothing that exists.
     */
    private static final byte[] SELECTOR_KEY =
            "carddemo-card-controller-test-selector-material-not-a-secret"
                    .getBytes(StandardCharsets.UTF_8);

    /**
     * Key material for the real cursor seal; test-only and deliberately not a credential.
     *
     * <p>Assumptions: a real cursor seal is used for the same reason a real selector seal is. The shared
     * page envelope REFUSES a boundary position that is not a sealed token, on the stated ground that a
     * cursor built from the key columns would publish those columns -- one of which is a primary account
     * number -- so a page fixture carrying a plain string cannot be constructed at all. Discovering that
     * from the refusal rather than assuming it is why this constant exists.
     */
    private static final byte[] CURSOR_KEY =
            "carddemo-card-controller-test-cursor-material-not-a-secret"
                    .getBytes(StandardCharsets.UTF_8);

    /**
     * How long a sealed boundary position stays redeemable; generous, because no case asserts expiry.
     */
    private static final Duration CURSOR_LIFETIME = Duration.ofHours(1);

    /**
     * The leading digits every demonstration card number below is built from.
     *
     * <p>Assumptions: the number is COMPOSED at run time from this prefix and a row ordinal rather than
     * written out as one literal. Two things follow that are both wanted: a row's number and its masked
     * rendering are derived from one source so they cannot disagree, and no long run of digits appears in
     * this source at all. The value is a demonstration number in the published CardDemo seed range and
     * identifies no real account.
     */
    private static final String CARD_NUMBER_TEMPLATE = "65092303" + "6255%04d";

    /**
     * The account identifier every fixture row below belongs to, eleven digits as the contract declares.
     *
     * <p>Assumptions: eleven is {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:6},
     * corroborated by {@code ACCTSIDI PIC X(11)} at {@code app/cpy-bms/COCRDSL.CPY:60}. The value is a
     * demonstration identifier and names no real account.
     */
    private static final String ACCOUNT_ID = "10000000001";

    /**
     * The embossed name every fixture row below carries.
     *
     * <p>Assumptions: the width bound is {@code CARD-EMBOSSED-NAME PIC X(50)} at
     * {@code app/cpy/CVACT02Y.cpy:8} and the value is well inside it. Its mixed case is deliberate: the
     * reference upper-cases before it compares, so a Title-Case value exercises the same acceptance a
     * value in one case would.
     */
    private static final String EMBOSSED_NAME = "Layla Ullrich";

    /**
     * The expiration date every fixture row below carries, in the ten-character form.
     *
     * <p>Assumptions: ten characters is {@code CARD-EXPIRAION-DATE PIC X(10)} at
     * {@code app/cpy/CVACT02Y.cpy:9}. That baseline spelling is the reference's own; the migrated column
     * and this member are named for the corrected spelling, and that naming decision is the only one of
     * its kind in this bounded context.
     */
    private static final String EXPIRATION_DATE = "2024-06-27";

    /**
     * The revision the stored fixture row is read at.
     *
     * <p>Assumptions: CT-06 -- the concurrency token is a member of the SUBMITTED BODY and not a request
     * header, because {@code com.carddemo.card.dto.CardUpdateRequest} declares five components and the
     * fifth is that token, bound {@code @NotNull} with a lower bound. The controller's own record of the
     * decision names the header form as the alternative considered and rejected on the ground that this
     * context's published request schema requires the member, while the sibling account context
     * publishes the header form -- so the two differ because their contracts differ. A test that sent the
     * token in a header would therefore exercise a shape this contract does not publish.
     */
    private static final int STORED_VERSION = 3;

    /**
     * A bearer value presented in an {@code Authorization} header; not a token and not a credential.
     *
     * <p>Assumptions: the substituted decoder answers for whatever string arrives, so the value never
     * has to be a real token and deliberately is not one. No issuer location, key set, pool, region or
     * account is named anywhere in this file.
     */
    private static final String PRESENTED_BEARER_VALUE = "presented-value-decoded-by-a-substitute";

    /**
     * An address the published contract does not carry, used to reach the chain's closing rule.
     *
     * <p>Assumptions: this is beneath the versioned prefix but matches none of the chain's three card
     * patterns, so it can only be answered by {@code anyRequest().denyAll()}. It deliberately names no
     * handler, because the point is that the chain refuses before the servlet chooses one.
     */
    private static final String UNPUBLISHED_ADDRESS = "/api/v1/no-such-collection";

    /**
     * The member names the masked card detail publishes, in contract order.
     *
     * <p>Assumptions: the set is asserted COMPLETE rather than sampled, and the reason is what is absent
     * from it. The card verification value is declared in storage as
     * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:7}, and it appears in no schema of
     * {@code src/main/resources/openapi/card-api.yaml}, in no response type of this context and in
     * neither of the two shapes below -- not masked, not truncated, absent. A path assertion can only
     * show what IS published, so proving the absence takes the whole set.
     */
    private static final Set<String> MASKED_DETAIL_MEMBERS = Set.of("key", "displayCardNumber",
            "accountId", "embossedName", "expirationDate", "activeStatus", "version");

    /**
     * The member names the administrative card detail publishes, in contract order.
     *
     * <p>Assumptions: this differs from the masked set in exactly one name, the number member, and in
     * nothing else. The wider authority widens what may be RENDERED of the card number and grants no
     * additional member, so the verification value is as absent here as it is there.
     */
    private static final Set<String> ADMIN_DETAIL_MEMBERS = Set.of("key", "cardNumber",
            "accountId", "embossedName", "expirationDate", "activeStatus", "version");

    /**
     * The member names the shared page envelope publishes.
     *
     * <p>Assumptions: the envelope declares FOUR members and this set is all of them. Positioning by a
     * row ordinal, a page ordinal or a set total is published nowhere, so no name of that kind appears
     * here or anywhere below; the backward direction contributes the leading boundary token and no
     * availability answer, because that answer is the page ordinal the reference keeps on the terminal
     * at {@code app/cbl/COCRDLIC.cbl:237-238} and the SPA now keeps for itself.
     */
    private static final Set<String> PAGE_ENVELOPE_MEMBERS =
            Set.of("items", "firstKey", "lastKey", "hasNext");

    /**
     * Serialises request bodies and reads response bodies back for whole-set assertions.
     */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * The real seal that mints every selector the fixtures below are addressed by.
     */
    private static final SealedSelector SELECTOR_SEALER = new SealedSelector(SELECTOR_KEY);

    /**
     * The real seal that mints the boundary positions the page fixture below carries.
     */
    private static final CursorToken CURSOR_SEALER = new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);

    /**
     * The binding every boundary position below is sealed under.
     *
     * <p>Assumptions: the binding is composed with the published helper and the browse's own published
     * query name rather than an invented string, so a fixture position is indistinguishable in form from a
     * produced one. The narrowing part is the published no-narrowing constant, because the page fixture
     * stands for an unnarrowed browse. Nothing in this class OPENS a position, so the binding affects only
     * the token's form -- which is exactly what the envelope inspects.
     */
    private static final String CURSOR_BINDING = CursorToken.binding(CardListService.LIST_BINDING,
            "card-caller-under-assertion", CursorToken.SCOPE_NONE);

    /**
     * The context holding the adapter, the shared advice and the deployed filter chain.
     *
     * <p>Trade-offs: one context is refreshed for the whole class and the substituted collaborators are
     * reset before each case, rather than a context per case. Building the chain is the expensive half of
     * a refresh and a per-case context would rebuild it while asserting nothing further; the cost
     * accepted is that a case which reconfigured a bean instead of restubbing one would leak into the
     * next, which is why no case below does.
     */
    private static AnnotationConfigWebApplicationContext context;

    /**
     * The entry point every request below is issued through, with the deployed chain installed.
     */
    private static MockMvc mockMvc;

    /**
     * The substituted browse service, so no case here reaches a store.
     */
    private static CardListService reads;

    /**
     * The substituted masked-read service.
     */
    private static CardViewService views;

    /**
     * The substituted administrative-read service, whose answers carry a full card number.
     */
    private static CardAdminViewService adminViews;

    /**
     * The substituted edit service, which is where a conflict is raised from.
     */
    private static CardUpdateService writes;

    /**
     * The substituted token decoder, which answers for a bearer value a caller presents.
     */
    private static JwtDecoder jwtDecoder;

    /**
     * Refreshes the slice context once and installs the deployed chain in front of the adapter.
     *
     * <p>Assumptions: the chain is handed to the entry point EXPLICITLY. In a running service it is a
     * container-level filter, and this entry point installs no filter it is not given, so omitting this
     * step would leave every authority assertion below passing for the wrong reason -- the request would
     * reach the handler with nothing having examined it. The security-aware configurer is applied as well
     * as the filter, because that is what lets a case attach an already-authenticated caller instead of
     * presenting a bearer value; both mechanisms are used below and the reason each is used is recorded
     * where it is.
     *
     * <p>Assumptions: the correlation filter is NOT installed and no case asserts on the correlation
     * header. Its registration belongs to the shared kernel's auto-configuration, which a hand-refreshed
     * context does not apply, so there is no per-service wiring here to exercise and asserting the header
     * would assert something this class invented.
     */
    @BeforeAll
    static void refreshSliceContext() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(SliceWiring.class);
        context.refresh();

        reads = context.getBean(CardListService.class);
        views = context.getBean(CardViewService.class);
        adminViews = context.getBean(CardAdminViewService.class);
        writes = context.getBean(CardUpdateService.class);
        jwtDecoder = context.getBean(JwtDecoder.class);

        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity(
                        context.getBean("springSecurityFilterChain", Filter.class)))
                .build();
    }

    /**
     * Closes the context so the class leaves no refreshed application behind it.
     */
    @AfterAll
    static void closeSliceContext() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * Clears every substituted collaborator so each case starts from no stubbing and no recorded call.
     *
     * <p>Assumptions: the decoder is reset alongside the four services. Several cases below assert that a
     * request never reached a service, and a stub or a recorded call surviving from a previous case would
     * make such an assertion report on the wrong request.
     *
     * <p>Refactoring Rationale: every expected value below is built into a local BEFORE the stub that
     * returns it is completed, rather than being built inside the stubbing call. That is a correction
     * rather than a style preference, and the failure it removes was observed here: a helper that raised
     * while evaluating an argument left a stub half-declared, and the substitution framework then reported
     * that half-declared stub from THIS method during the next case -- so one broken fixture produced two
     * failures, and the second named a case and a line that had nothing wrong with them. Building the value
     * first keeps a fixture failure inside the case that owns the fixture.
     */
    @BeforeEach
    void resetSubstitutedCollaborators() {
        reset(reads, views, adminViews, writes, jwtDecoder);
    }

    /**
     * A service-raised absence is rendered as the shared problem document, not a container error.
     *
     * <p>Purpose: this case is the gate on CT-01. Every member asserted below is one the shared error
     * record declares and the container's own error representation does not carry, so the case cannot
     * pass unless {@link SliceWiring#globalExceptionHandler(Clock)} actually registered the advice. With
     * that bean withdrawn the raised absence propagates out of the entry point instead, and this case
     * fails rather than quietly reporting success -- which is the whole reason it asserts a body shape
     * and not only a status.
     *
     * <p>Assumptions: the stamp is asserted by its declared WIDTH rather than by a literal, so the case
     * states the shared formatter's 26-character contract without hard-coding an instant that a change of
     * clock would break.
     *
     * @throws Exception if the request cannot be performed or its body cannot be read
     */
    @Test
    @DisplayName("a service-raised absence is rendered as the shared problem document")
    void theAdviceRendersARefusalAsTheSharedProblemDocument() throws Exception {
        when(views.viewBySelector(anyString()))
                .thenThrow(new NoSuchElementException(MESSAGE_CARD_NOT_FOUND));

        mockMvc.perform(get(CardController.CARD_PATH, selectorFor(1))
                        .with(callerWithAuthority(JwtRoleConverter.USER_AUTHORITY)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_NOT_FOUND))
                .andExpect(jsonPath("$.status").value(HttpStatus.NOT_FOUND.value()))
                .andExpect(jsonPath("$.severity").value(ApiError.Severity.WARNING.name()))
                .andExpect(jsonPath("$.message").value(MESSAGE_CARD_NOT_FOUND))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    /**
     * An absent card is answered with the reference sentence and with no per-field entry.
     *
     * <p>Assumptions: CT-07 -- the published two-field shape names BOTH search members even though the
     * read is keyed on the card number ALONE, and the second half of that sentence is why the choice is
     * worth stating. {@code 9000-READ-DATA.} at {@code app/cbl/COCRDSLC.cbl:726} performs only
     * {@code 9100-GETCARD-BYACCTCARD} at {@code :728-729}; inside that paragraph, which opens at
     * {@code :736}, the statement that would have moved the account identifier into the record identifier
     * is COMMENTED OUT at {@code :739} while the card-number move is live at {@code :740}, and the read at
     * {@code :742} is issued with that field as its record identifier at {@code :744} and its own length
     * as the key length at {@code :745}. Only one of the two members is therefore a key, and the shape
     * still flags both -- so the assertion derives the pair from
     * {@link CardViewService#notFoundFieldErrors()} rather than restating it, and confirms both entries
     * carry the same sentence this route answered with.
     *
     * <p>Assumptions: on THIS route the array is empty, and that is asserted rather than assumed. The
     * route addresses a card by its sealed selector, so there is no submitted account member for an entry
     * to name; the two-field shape belongs to the read that takes both filters. Asserting the emptiness
     * keeps the two shapes from being read as one.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an absent card answers the reference sentence, and the two-field shape names both")
    void anAbsentCardIsAnsweredWithTheReferenceSentence() throws Exception {
        when(views.viewBySelector(anyString()))
                .thenThrow(new NoSuchElementException(MESSAGE_CARD_NOT_FOUND));

        mockMvc.perform(get(CardController.CARD_PATH, selectorFor(1))
                        .with(callerWithAuthority(JwtRoleConverter.USER_AUTHORITY)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MESSAGE_CARD_NOT_FOUND))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());

        List<ApiError.FieldError> published = CardViewService.notFoundFieldErrors();
        assertThat(published).extracting(ApiError.FieldError::field)
                .containsExactly(CardViewService.FIELD_ACCOUNT_ID, CardViewService.FIELD_CARD_NUMBER);
        assertThat(published).extracting(ApiError.FieldError::message)
                .containsOnly(MESSAGE_CARD_NOT_FOUND);
    }

    /**
     * An administrative caller reads the full primary account number on the administrative route.
     *
     * <p>Purpose: this is the admitted half of the CT-02 gate. It is asserted together with the refused
     * half below, because a route that answers one way for every caller proves nothing about a chain --
     * only observing BOTH outcomes on the SAME address shows that a rule ran.
     *
     * <p>Assumptions: the disclosed member is asserted to equal the composed number in full, and the
     * masked member is asserted ABSENT from this shape. A presence check on the number alone would be
     * satisfied by a masked rendering too, so the case would pass against the regression it exists to
     * catch.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an administrative caller reads the unmasked number on the administrative route")
    void anAdministratorReadsTheUnmaskedNumber() throws Exception {
        AdminCardDetail disclosed = administrativeDetail(1);
        when(adminViews.viewForAdministrator(anyString())).thenReturn(disclosed);

        mockMvc.perform(get(CardController.ADMIN_CARD_PATH, selectorFor(1))
                        .with(callerWithAuthority(JwtRoleConverter.ADMIN_AUTHORITY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardNumber").value(cardNumber(1)))
                .andExpect(jsonPath("$.displayCardNumber").doesNotExist());
    }

    /**
     * An ordinary caller is refused the administrative route and reaches no service.
     *
     * <p>Purpose: this is the refused half of the CT-02 gate. With the chain withdrawn the request would
     * be answered 200 and the substituted administrative service WOULD be called, so both halves of this
     * case fail if the wiring is dropped -- which is what makes it a gate rather than a description.
     *
     * <p>Assumptions: the refusal is asserted by its rendered body as well as its status, because a
     * refusal carrying no body is a different outcome for a client than one carrying the shared forbidden
     * code, and only the second is what the contract publishes.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an ordinary caller is refused the administrative route and reaches no service")
    void anOrdinaryCallerIsRefusedTheAdministrativeRoute() throws Exception {
        mockMvc.perform(get(CardController.ADMIN_CARD_PATH, selectorFor(1))
                        .with(callerWithAuthority(JwtRoleConverter.USER_AUTHORITY)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_FORBIDDEN));

        verifyNoInteractions(adminViews);
    }

    /**
     * A request carrying no caller at all is challenged rather than served.
     *
     * <p>Assumptions: the answer is the shared unauthenticated code and not the forbidden one, because
     * the chain distinguishes a caller it could not identify from one it identified and would not admit.
     * Both are asserted in this class so the distinction is observable, and neither is inferred from the
     * other.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a request carrying no caller is challenged rather than served")
    void anUnauthenticatedRequestIsChallengedRatherThanServed() throws Exception {
        mockMvc.perform(get(CardController.ADMIN_CARD_PATH, selectorFor(1)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED));

        verifyNoInteractions(adminViews);
    }

    /**
     * A presented administrator group claim is translated by the deployed converter and admitted.
     *
     * <p>Assumptions: this case reaches the administrative route the way a client does, by presenting a
     * bearer value in an {@code Authorization} header, so the whole deployed path runs -- the
     * resource-server filter, the substituted decoder, the real converter
     * {@link SecurityConfig#jwtAuthenticationConverter(String, String)} builds and the real
     * {@link JwtRoleConverter} inside it. Alternatives Considered: attaching an already-authenticated
     * caller for every case, which is what the sibling cases above do. Both mechanisms are kept
     * deliberately: attaching a caller states which AUTHORITY a rule demands, while presenting a claim
     * states that a GROUP NAME in a token becomes that authority. Only the second would notice a change
     * in the claim the converter reads or in the group vocabulary it recognises.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a presented administrator group claim is translated and admitted")
    void aPresentedAdministratorGroupClaimIsTranslatedByTheDeployedConverter() throws Exception {
        AdminCardDetail disclosed = administrativeDetail(1);
        when(adminViews.viewForAdministrator(anyString())).thenReturn(disclosed);
        presentGroupClaim(JwtRoleConverter.ADMIN_AUTHORITY);

        mockMvc.perform(get(CardController.ADMIN_CARD_PATH, selectorFor(1))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PRESENTED_BEARER_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardNumber").value(cardNumber(1)));
    }

    /**
     * A presented ordinary group claim is refused the administrative route.
     *
     * <p>Assumptions: the token differs from the admitted one above in the group claim ALONE, so the
     * difference in outcome can only come from the translation and the rule. The group names the deployed
     * converter recognises are its own compiled constants, which is why {@link SliceWiring} hands it those
     * constants rather than strings written here.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a presented ordinary group claim is refused the administrative route")
    void aPresentedOrdinaryGroupClaimIsRefusedTheAdministrativeRoute() throws Exception {
        presentGroupClaim(JwtRoleConverter.USER_AUTHORITY);

        mockMvc.perform(get(CardController.ADMIN_CARD_PATH, selectorFor(1))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PRESENTED_BEARER_VALUE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_FORBIDDEN));

        verifyNoInteractions(adminViews);
    }

    /**
     * A token whose group claim names nothing the converter recognises reaches no card route.
     *
     * <p>Assumptions: the presented group is a plausible-looking name outside the recognised pair, which
     * is the case a misconfigured pool actually produces. The converter grants no authority for it, so the
     * masked read is refused by the rule covering the card subtree rather than by the administrator gate --
     * the two ordinary card patterns admit either recognised authority and nothing else.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a token carrying no recognised group reaches no card route")
    void aTokenCarryingNoRecognisedGroupReachesNoCardRoute() throws Exception {
        presentGroupClaim("carddemo-observer");

        mockMvc.perform(get(CardController.CARD_PATH, selectorFor(1))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PRESENTED_BEARER_VALUE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_FORBIDDEN));

        verifyNoInteractions(views);
    }

    /**
     * The masked read discloses only the last four digits of the primary account number.
     *
     * <p>Assumptions: the expected rendering is produced by {@link CardNumberMasker#mask(String)} rather
     * than written out, so the case cannot drift from the masker the response shapes are held to, and the
     * masked value is additionally asserted NOT to contain the number it was derived from.
     *
     * @throws Exception if the request cannot be performed or its body cannot be read
     */
    @Test
    @DisplayName("the masked read discloses only the last four digits")
    void theMaskedReadDisclosesOnlyTheLastFourDigits() throws Exception {
        CardDetail masked = maskedDetail(1);
        when(views.viewBySelector(anyString())).thenReturn(masked);

        String body = mockMvc.perform(get(CardController.CARD_PATH, selectorFor(1))
                        .with(callerWithAuthority(JwtRoleConverter.USER_AUTHORITY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayCardNumber").value(CardNumberMasker.mask(cardNumber(1))))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(cardNumber(1));
    }

    /**
     * The masked read publishes exactly the members the contract declares, and no others.
     *
     * <p>Assumptions: asserting the COMPLETE set is what proves the card verification value is absent
     * rather than merely unqueried. The value exists in storage as {@code CARD-CVV-CD PIC 9(03)} at
     * {@code app/cpy/CVACT02Y.cpy:7} and it is published by no schema, carried by no response type and
     * present in neither detail shape, so any additional member appearing here is a disclosure this
     * assertion is meant to catch.
     *
     * @throws Exception if the request cannot be performed or its body cannot be read
     */
    @Test
    @DisplayName("the masked read publishes exactly the contracted members")
    void theMaskedReadPublishesExactlyTheContractedMembers() throws Exception {
        CardDetail masked = maskedDetail(1);
        when(views.viewBySelector(anyString())).thenReturn(masked);

        MvcResult answered = mockMvc.perform(get(CardController.CARD_PATH, selectorFor(1))
                        .with(callerWithAuthority(JwtRoleConverter.USER_AUTHORITY)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(publishedMemberNames(answered)).isEqualTo(MASKED_DETAIL_MEMBERS);
    }

    /**
     * The administrative read publishes exactly the members the contract declares, and no others.
     *
     * <p>Assumptions: the two published sets differ in the number member ALONE. The wider authority
     * widens what may be rendered of that one value and grants no additional member, so this case and the
     * one above it together state that the administrative route is a change of rendering rather than a
     * change of shape.
     *
     * @throws Exception if the request cannot be performed or its body cannot be read
     */
    @Test
    @DisplayName("the administrative read publishes exactly the contracted members")
    void theAdministrativeReadPublishesExactlyTheContractedMembers() throws Exception {
        AdminCardDetail disclosed = administrativeDetail(1);
        when(adminViews.viewForAdministrator(anyString())).thenReturn(disclosed);

        MvcResult answered = mockMvc.perform(get(CardController.ADMIN_CARD_PATH, selectorFor(1))
                        .with(callerWithAuthority(JwtRoleConverter.ADMIN_AUTHORITY)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(publishedMemberNames(answered)).isEqualTo(ADMIN_DETAIL_MEMBERS);
    }

    /**
     * A full page carries the reference row count and every member of the shared envelope.
     *
     * <p>Assumptions: the expected row count is {@link CardListService#PAGE_SIZE} rather than a figure
     * written here, and that constant is the reference's own -- {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP}
     * is declared {@code VALUE 7} at {@code app/cbl/COCRDLIC.cbl:176-178}, corroborated by the comment at
     * {@code :250} working the row array out as twenty-eight characters by seven rows.
     *
     * <p>Assumptions: the envelope's complete member set is asserted, and what it does not contain is the
     * point. The reference itself positions by key, carrying a last-key and a first-key pair with a
     * further-page indicator across its screen turns at {@code app/cbl/COCRDLIC.cbl:230-244}, so no
     * member reports a row ordinal, a page ordinal or a set total and none is expected here.
     *
     * @throws Exception if the request cannot be performed or its body cannot be read
     */
    @Test
    @DisplayName("a full page carries seven rows and every envelope member")
    void aFullPageCarriesSevenRowsAndEveryEnvelopeMember() throws Exception {
        PageResponse<CardSummary> page = fullPage();
        when(reads.list(any(), any(), anyBoolean(), anyString())).thenReturn(page);

        MvcResult answered = mockMvc.perform(post(CardController.SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(callerWithAuthority(JwtRoleConverter.USER_AUTHORITY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(CardListService.PAGE_SIZE))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.firstKey").isNotEmpty())
                .andExpect(jsonPath("$.lastKey").isNotEmpty())
                .andExpect(jsonPath("$.items[0].displayCardNumber")
                        .value(CardNumberMasker.mask(cardNumber(1))))
                .andReturn();

        assertThat(publishedMemberNames(answered)).isEqualTo(PAGE_ENVELOPE_MEMBERS);
    }

    /**
     * An empty page carries no boundary cursors and neither availability indicator.
     *
     * <p>Assumptions: the two cursors are absent rather than empty strings, because they are boundary
     * keys of rows that were read and an empty page read none. Both indicators are false for the same
     * reason, and asserting all four together is what distinguishes an exhausted browse from a failed one.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an empty page carries no cursors and neither availability indicator")
    void anEmptyPageCarriesNoCursorsAndNeitherAvailabilityIndicator() throws Exception {
        when(reads.list(any(), any(), anyBoolean(), anyString())).thenReturn(PageResponse.empty());

        mockMvc.perform(post(CardController.SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(callerWithAuthority(JwtRoleConverter.USER_AUTHORITY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.firstKey").doesNotExist())
                .andExpect(jsonPath("$.lastKey").doesNotExist());
    }

    /**
     * The browse narrowing is optional while the lookup number is mandatory.
     *
     * <p>Assumptions: CT-08 -- the asymmetry is the reference's own and is read off the value each edit
     * PRE-SETS its field state to rather than from any comment. On the browse,
     * {@code 2210-EDIT-ACCOUNT.} at {@code app/cbl/COCRDLIC.cbl:1003} pre-sets blank at {@code :1004} and
     * {@code 2220-EDIT-CARD.} at {@code :1036} does the same at {@code :1039}, so an unfilled field
     * narrows nothing. On the detail read, {@code 2210-EDIT-ACCOUNT.} at
     * {@code app/cbl/COCRDSLC.cbl:647} pre-sets NOT-ACCEPTABLE at {@code :648} and
     * {@code 2220-EDIT-CARD.} at {@code :685}, under its own comments at {@code :686} and {@code :687},
     * does the same at {@code :688} -- which is what makes those fields mandatory. Both conventions are
     * preserved as they stand, which is why this one case asserts both outcomes: the pair looks like an
     * inconsistency and is not.
     *
     * <p>Assumptions: a further asymmetry sits in the browse's MESSAGES and is recorded here because it
     * is easy to read as a defect. The card-filter sentence is moved only inside
     * {@code IF WS-ERROR-MSG-OFF} at {@code app/cbl/COCRDLIC.cbl:1056}, with its literal at
     * {@code :1058} and the guard closing at {@code :1060}, while the account-filter sentence at
     * {@code :1021-1023}, whose literal is at {@code :1022}, carries no such guard. So when both
     * narrowings are unacceptable the ACCOUNT sentence wins the single aggregate line while both field
     * states still accumulate -- which is the same one-aggregate-many-entries shape the accumulation case
     * below asserts.
     *
     * @throws Exception if either request cannot be performed
     */
    @Test
    @DisplayName("the browse narrowing is optional while the lookup number is mandatory")
    void theBrowseNarrowingIsOptionalWhileTheLookupNumberIsMandatory() throws Exception {
        PageResponse<CardSummary> page = fullPage();
        when(reads.list(any(), any(), anyBoolean(), anyString())).thenReturn(page);

        mockMvc.perform(post(CardController.SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(callerWithAuthority(JwtRoleConverter.USER_AUTHORITY)))
                .andExpect(status().isOk());

        mockMvc.perform(post(CardController.LOOKUP_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(callerWithAuthority(JwtRoleConverter.USER_AUTHORITY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(CardViewService.FIELD_CARD_NUMBER));

        verifyNoInteractions(views);
    }

    /**
     * The reference clear-filter sentinel is refused rather than read as an emptied narrowing.
     *
     * <p>Alternatives Considered: CT-05 -- reproducing the reference affordance that treats a typed
     * asterisk as a cleared field. {@code app/cbl/COCRDSLC.cbl:614} carries the comment announcing it, and
     * the two blocks beneath it at {@code :615-620} and {@code :622-627} each move low values into a
     * filter field that holds an asterisk OR spaces. It is deliberately NOT reproduced: it exists because
     * a constant-width terminal field cannot otherwise be emptied by a user, and over this transport an
     * omitted member IS absence. The published narrowing admits eleven digits and nothing else, so an
     * asterisk earns a refusal naming the member instead of silently widening the query it appeared to
     * narrow.
     *
     * <p>Assumptions: this asterisk is a DIFFERENT thing from the one at
     * {@code app/cbl/COCRDUPC.cbl:1270}, which the reference writes INTO an output subfield to mark a
     * blank field and which this migration does reproduce -- the blank-state case below is where that one
     * is asserted. Two semantics, one character, kept apart here so a later reader does not merge them.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the reference clear-filter sentinel is refused rather than read as absence")
    void theReferenceClearFilterSentinelIsNotReproduced() throws Exception {
        mockMvc.perform(post(CardController.SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"*\"}")
                        .with(callerWithAuthority(JwtRoleConverter.USER_AUTHORITY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value(CardListService.FIELD_ACCOUNT_FILTER));

        verifyNoInteractions(reads);
    }

    /**
     * The lookup route resolves a submitted number to that card's masked detail.
     *
     * <p>Assumptions: the answer carries the SAME masked shape the selector-addressed read answers with,
     * so a caller arriving with a number leaves holding a selector and never has to send the number a
     * second time. That is asserted by comparing the published member set rather than a single member.
     *
     * @throws Exception if the request cannot be performed or its body cannot be read
     */
    @Test
    @DisplayName("the lookup route resolves a number to the masked detail shape")
    void theLookupRouteResolvesANumberToTheMaskedDetailShape() throws Exception {
        CardDetail masked = maskedDetail(1);
        when(views.viewByCardNumber(anyString())).thenReturn(masked);

        MvcResult answered = mockMvc.perform(post(CardController.LOOKUP_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"" + cardNumber(1) + "\"}")
                        .with(callerWithAuthority(JwtRoleConverter.USER_AUTHORITY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(selectorFor(1)))
                .andReturn();

        assertThat(publishedMemberNames(answered)).isEqualTo(MASKED_DETAIL_MEMBERS);
    }

    /**
     * A conforming edit is answered with the saved card's masked detail.
     *
     * <p>Assumptions: the submitted body carries the revision the row was read at, and the answer carries
     * the revision the saved row now holds, so a caller making a second change does not have to re-read
     * the card first. Both are asserted, because an answer echoing the SUBMITTED revision would leave a
     * second edit certain to conflict.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a conforming edit is answered with the saved masked detail")
    void aConformingEditIsAnsweredWithTheSavedMaskedDetail() throws Exception {
        CardDetail saved = savedDetail(1, STORED_VERSION + 1);
        when(writes.update(anyString(), any(CardUpdateRequest.class))).thenReturn(saved);

        mockMvc.perform(put(CardController.CARD_PATH, selectorFor(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submittedEdit(STORED_VERSION))
                        .with(callerWithAuthority(JwtRoleConverter.USER_AUTHORITY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(STORED_VERSION + 1))
                .andExpect(jsonPath("$.embossedName").value(EMBOSSED_NAME));
    }

    /**
     * A stale revision is answered 409 carrying the reference sentence character for character.
     *
     * <p>Assumptions: CT-06 -- the revision travels in the submitted BODY, so this case sends it there.
     * {@code com.carddemo.card.dto.CardUpdateRequest} declares five components and the fifth is the
     * revision, bound not-null with a lower bound, and the controller records the conditional-request
     * header form as the alternative it rejected because this context's published request schema requires
     * the member. A case presenting the revision in a header would exercise a shape this contract does not
     * publish.
     *
     * <p>Assumptions: the sentence is asserted as the literal and compared with the shared constant, and
     * the only quantitative claim made about it is that it fits the width the reference declares. No
     * character count is written as a figure, because a neighbouring brief gives 45 and a measurement
     * gives 46; the two-word spelling and the absent final period are asserted directly instead, since
     * those are the properties a well-meaning normalisation would change.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a stale revision answers 409 with the reference sentence, character for character")
    void aStaleRevisionIsAnsweredWithTheReferenceSentence() throws Exception {
        when(writes.update(anyString(), any(CardUpdateRequest.class))).thenThrow(
                new RecordConflictException(RecordConflictException.Kind.STALE_VERSION,
                        (long) (STORED_VERSION + 1)));

        mockMvc.perform(put(CardController.CARD_PATH, selectorFor(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submittedEdit(STORED_VERSION))
                        .with(callerWithAuthority(JwtRoleConverter.USER_AUTHORITY)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_CONFLICT))
                .andExpect(jsonPath("$.message").value(MESSAGE_RECORD_CHANGED))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value(GlobalExceptionHandler.FIELD_VERSION))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value(String.valueOf(STORED_VERSION + 1)));

        assertThat(MESSAGE_RECORD_CHANGED)
                .isEqualTo(ApiError.COACTUPC_RECORD_CHANGED)
                .contains("some one")
                .doesNotEndWith(".");
        assertThat(MESSAGE_RECORD_CHANGED.length())
                .isLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH);
    }

    /**
     * Each conflict condition keeps its own sentence, so none stands in for another.
     *
     * <p>Assumptions: the reference declares three neighbouring conditions in one value set -- a
     * lock-acquisition failure at {@code app/cbl/COCRDUPC.cbl:205-206}, the before-image mismatch at
     * {@code :207-208} and a rewrite failure at {@code :209-210} -- and only the middle one is the
     * optimistic-lock outcome. This case sends the same request three times, varying only the raised
     * condition, so a handler that answered every conflict with one sentence would fail here rather than
     * pass the case above by coincidence.
     *
     * <p>Assumptions: the third recognised condition answers with a referential-integrity sentence rather
     * than the reference's rewrite-failure one, so the rewrite sentence is asserted separately to be
     * distinct from all three. That keeps four sentences separable and stops the conflict answer being
     * read as one undifferentiated outcome.
     *
     * @param raised the condition the substituted edit service reports for this run
     * @param expected the sentence the shared advice must answer that condition with
     * @throws Exception if the request cannot be performed
     */
    @ParameterizedTest(name = "{0} answers \"{1}\"")
    @MethodSource("conflictConditions")
    @DisplayName("each conflict condition keeps its own reference sentence")
    void eachConflictConditionKeepsItsOwnSentence(RecordConflictException.Kind raised,
            String expected) throws Exception {
        when(writes.update(anyString(), any(CardUpdateRequest.class)))
                .thenThrow(new RecordConflictException(raised));

        mockMvc.perform(put(CardController.CARD_PATH, selectorFor(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submittedEdit(STORED_VERSION))
                        .with(callerWithAuthority(JwtRoleConverter.USER_AUTHORITY)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(expected))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());

        assertThat(MESSAGE_UPDATE_FAILED)
                .isEqualTo(GlobalExceptionHandler.MESSAGE_UPDATE_FAILED)
                .isNotEqualTo(expected);
    }

    /**
     * Several faulted attributes are reported together beside one aggregate sentence.
     *
     * <p>Assumptions: the reference runs all four attribute edits on every turn --
     * {@code 1230-EDIT-NAME} at {@code app/cbl/COCRDUPC.cbl:806},
     * {@code 1240-EDIT-CARDSTATUS} at {@code :845}, {@code 1250-EDIT-EXPIRY-MON} at {@code :877} and
     * {@code 1260-EDIT-EXPIRY-YEAR} at {@code :913} -- and none short-circuits the others, so several
     * faults coexist on one turn. The shared error record answers that with ONE latched aggregate sentence
     * and MANY accumulated entries, which is why the count is asserted rather than assumed to be one and
     * why the aggregate is asserted as its own member rather than derived from the array.
     *
     * <p>Assumptions: what this case asserts is the ADVICE's rendering of an accumulated refusal, not the
     * edit rules themselves. Those are transcribed in {@code com.carddemo.card.service.CardUpdateService}
     * and asserted against the reference paragraphs in the sibling service test package, so restating them
     * here would give one rule two owners.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("several faulted attributes are reported together beside one aggregate sentence")
    void severalFaultedAttributesAreReportedBesideOneAggregateSentence() throws Exception {
        when(writes.update(anyString(), any(CardUpdateRequest.class))).thenThrow(
                new ClientInputException(ApiError.CODE_VALIDATION,
                        List.of(CardUpdateService.FIELD_EMBOSSED_NAME,
                                CardUpdateService.FIELD_ACTIVE_STATUS,
                                CardUpdateService.FIELD_EXPIRATION_MONTH),
                        FieldValidationFlag.NOT_OK, MESSAGE_NAME_MUST_BE_ALPHA));

        mockMvc.perform(put(CardController.CARD_PATH, selectorFor(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submittedEdit(STORED_VERSION))
                        .with(callerWithAuthority(JwtRoleConverter.USER_AUTHORITY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.message").value(MESSAGE_NAME_MUST_BE_ALPHA))
                .andExpect(jsonPath("$.fieldErrors.length()").value(3))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value(CardUpdateService.FIELD_EMBOSSED_NAME))
                .andExpect(jsonPath("$.fieldErrors[1].field")
                        .value(CardUpdateService.FIELD_ACTIVE_STATUS))
                .andExpect(jsonPath("$.fieldErrors[2].field")
                        .value(CardUpdateService.FIELD_EXPIRATION_MONTH));
    }

    /**
     * A blank attribute publishes the state the screen marker attaches to; an unacceptable one does not.
     *
     * <p>Assumptions: the reference renders the two faults DIFFERENTLY, and the two targets are what makes
     * the difference legible. At {@code app/cbl/COCRDUPC.cbl:1263-1264} an unacceptable name moves the red
     * attribute into the field's COLOUR subfield at {@code :1265} and nothing else, the block closing at
     * {@code :1266}; at {@code :1268-1269} a blank name moves an asterisk into the field's OUTPUT subfield
     * at {@code :1270} AND the red attribute into the colour subfield at {@code :1271}, closing at
     * {@code :1272}. The templated highlight book states the same thing once for every field, testing
     * not-acceptable OR blank as a single disjunction at {@code app/cpy/CSSETATY.cpy:18-19} -- so blank is
     * a SUBSET of error rather than a peer of it -- and separating the colour move at {@code :21-22} from
     * the asterisk move at {@code :24-25}.
     *
     * <p>Assumptions: the marker is derived from the state the response PUBLISHED rather than asserted as
     * a member of its own, because the asterisk is presentational and never a domain value: the body
     * carries the field state, and the marker is a function of that state which a renderer applies. The
     * gate the reference put on all of this, its re-entry discriminator, has no counterpart here at all,
     * so the presentation is driven purely by the response body.
     *
     * @throws Exception if either request cannot be performed or a body cannot be read
     */
    @Test
    @DisplayName("a blank attribute publishes the marked state and an unacceptable one does not")
    void aBlankAttributePublishesTheMarkedStateAndAnUnacceptableOneDoesNot() throws Exception {
        when(writes.update(anyString(), any(CardUpdateRequest.class))).thenThrow(
                new ClientInputException(ApiError.CODE_VALIDATION,
                        CardUpdateService.FIELD_EMBOSSED_NAME, FieldValidationFlag.BLANK,
                        MESSAGE_NAME_NOT_PROVIDED));

        MvcResult blank = mockMvc.perform(put(CardController.CARD_PATH, selectorFor(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submittedEdit(STORED_VERSION))
                        .with(callerWithAuthority(JwtRoleConverter.USER_AUTHORITY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MESSAGE_NAME_NOT_PROVIDED))
                .andReturn();

        assertThat(publishedFieldState(blank).screenMarker())
                .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER);

        reset(writes);
        when(writes.update(anyString(), any(CardUpdateRequest.class))).thenThrow(
                new ClientInputException(ApiError.CODE_VALIDATION,
                        CardUpdateService.FIELD_EMBOSSED_NAME, FieldValidationFlag.NOT_OK,
                        MESSAGE_NAME_MUST_BE_ALPHA));

        MvcResult unacceptable = mockMvc.perform(put(CardController.CARD_PATH, selectorFor(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submittedEdit(STORED_VERSION))
                        .with(callerWithAuthority(JwtRoleConverter.USER_AUTHORITY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MESSAGE_NAME_MUST_BE_ALPHA))
                .andReturn();

        assertThat(publishedFieldState(unacceptable).screenMarker())
                .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);
    }

    /**
     * Every sentence this class asserts fits the width the reference declares for a message line.
     *
     * <p>Assumptions: CT-09 -- the width asserted is the PROGRAM-side one, seventy-five characters, and
     * not a map field's. {@code app/cpy/CVCRD01Y.cpy:28} declares the error line and {@code :29} the
     * return line, each {@code PIC X(75)}, with a low-values sentinel at {@code :30} attaching to the
     * second of them only; {@code app/cbl/COCRDUPC.cbl:173} declares its own working copy at the same
     * width with a spaces sentinel at {@code :174}. The MAP containers are WIDER and are named here so
     * the figure is not mistaken for theirs -- {@code ERRMSGI PIC X(80)} at
     * {@code app/cpy-bms/COCRDSL.CPY:102} and at {@code app/cpy-bms/COCRDUP.CPY:108}, and
     * {@code PIC X(78)} at {@code app/cpy-bms/COCRDLI.CPY:288}.
     *
     * <p>Assumptions: the bound is read from {@link ApiError#MESSAGE_RENDERING_WIDTH} rather than written
     * as a number, so this case cannot disagree with the constant every renderer in the migration is held
     * to. The two browse sentences are included by way of their published constants rather than re-spelled,
     * because they are the longest sentences this context can answer with and are therefore the ones a
     * width regression would surface in first.
     */
    @Test
    @DisplayName("every asserted sentence fits the program-side message width")
    void everyAssertedSentenceFitsTheProgramSideMessageWidth() {
        List<String> asserted = List.of(MESSAGE_RECORD_CHANGED, MESSAGE_COULD_NOT_LOCK,
                MESSAGE_UPDATE_FAILED, MESSAGE_CARD_NOT_FOUND, MESSAGE_NAME_NOT_PROVIDED,
                MESSAGE_NAME_MUST_BE_ALPHA, MESSAGE_STATUS_MUST_BE_YES_NO,
                MESSAGE_EXPIRY_MONTH_NOT_VALID, MESSAGE_EXPIRY_YEAR_NOT_VALID,
                CardListService.MESSAGE_ACCOUNT_FILTER_INVALID,
                CardListService.MESSAGE_CARD_FILTER_INVALID);

        assertThat(asserted).allSatisfy(sentence ->
                assertThat(sentence.length()).isLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH));
    }

    /**
     * The three attribute sentences this class holds match the constants the edit service publishes.
     *
     * <p>Assumptions: the sentences are declared TWICE on purpose, once here as literals read from the
     * reference and once on {@code com.carddemo.card.service.CardUpdateService} as the constants the
     * service latches. This case is what makes the duplication safe: a change to either becomes a visible
     * disagreement between two files instead of a silent agreement between one file and itself. Without it
     * the literals above would be decorative, since no case asserts a sentence the service produced.
     */
    @Test
    @DisplayName("the attribute sentences match the constants the edit service publishes")
    void theAttributeSentencesMatchThePublishedConstants() {
        assertThat(MESSAGE_NAME_NOT_PROVIDED)
                .isEqualTo(CardUpdateService.MESSAGE_NAME_NOT_PROVIDED);
        assertThat(MESSAGE_NAME_MUST_BE_ALPHA)
                .isEqualTo(CardUpdateService.MESSAGE_NAME_MUST_BE_ALPHA);
        assertThat(MESSAGE_STATUS_MUST_BE_YES_NO)
                .isEqualTo(CardUpdateService.MESSAGE_STATUS_MUST_BE_YES_NO);
        assertThat(MESSAGE_EXPIRY_MONTH_NOT_VALID)
                .isEqualTo(CardUpdateService.MESSAGE_EXPIRY_MONTH_NOT_VALID);
        assertThat(MESSAGE_EXPIRY_YEAR_NOT_VALID)
                .isEqualTo(CardUpdateService.MESSAGE_EXPIRY_YEAR_NOT_VALID);
        assertThat(MESSAGE_CARD_NOT_FOUND).isEqualTo(CardViewService.MESSAGE_CARD_NOT_FOUND);
        assertThat(MESSAGE_COULD_NOT_LOCK).isEqualTo(CardUpdateService.MESSAGE_COULD_NOT_LOCK);
    }

    /**
     * An address the contract does not publish is refused by the chain's closing rule.
     *
     * <p>Assumptions: the caller presented for this request holds the ADMINISTRATOR authority, which is
     * the widest one this service recognises, so the refusal cannot be attributed to an insufficient
     * caller. What refuses it is the closing {@code denyAll}, and asserting it with the widest authority is
     * what shows the rule is a default-deny rather than a gap that a stronger caller would slip through.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an unpublished address is refused by the chain's closing rule")
    void anUnpublishedAddressIsRefusedByTheChainsClosingRule() throws Exception {
        mockMvc.perform(get(UNPUBLISHED_ADDRESS)
                        .with(callerWithAuthority(JwtRoleConverter.ADMIN_AUTHORITY)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_FORBIDDEN));
    }

    /**
     * The health path is admitted without a caller, unlike every card route.
     *
     * <p>Trade-offs: this slice enables the MVC infrastructure rather than inheriting it from a started
     * application, so the endpoints an actuator contributes at run time are absent and no handler answers
     * this address. The consequence is stated rather than worked around: the case asserts that the request
     * was NOT refused by the chain, which is the property the rule owns, and does not assert a body an
     * absent endpoint cannot produce. A challenged path would answer with the unauthenticated code and a
     * denied one with the forbidden code, so the two outcomes stay distinguishable from this one.
     *
     * @throws Exception if the request cannot be performed or its body cannot be read
     */
    @Test
    @DisplayName("the health path is admitted without a caller")
    void theHealthPathIsAdmittedWithoutACaller() throws Exception {
        MvcResult answered = mockMvc.perform(get("/actuator/health")).andReturn();

        assertThat(answered.getResponse().getStatus())
                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value())
                .isNotEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(answered.getResponse().getContentAsString())
                .doesNotContain(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED,
                        GlobalExceptionHandler.CODE_FORBIDDEN);
    }

    /**
     * Supplies the three conflict conditions the shared advice recognises with their sentences.
     *
     * <p>Assumptions: the expected sentences are read from the advice's own published constants rather
     * than written out, EXCEPT for the optimistic-lock one, which is this class's literal read from the
     * reference. That asymmetry is deliberate: the one sentence the reference specifies for this outcome is
     * held independently so the case can detect a change to it, while the other two are outcomes the
     * migration adds and are therefore compared against their owners.
     *
     * @return one argument pair per recognised condition, each a condition and the sentence it must answer
     *     with; never {@code null}
     */
    private static Stream<Arguments> conflictConditions() {
        return Stream.of(
                Arguments.of(RecordConflictException.Kind.STALE_VERSION, MESSAGE_RECORD_CHANGED),
                Arguments.of(RecordConflictException.Kind.LOCK_UNAVAILABLE,
                        GlobalExceptionHandler.MESSAGE_LOCK_UNAVAILABLE),
                Arguments.of(RecordConflictException.Kind.REFERENCED_ROW,
                        GlobalExceptionHandler.MESSAGE_REFERENCED_ROW));
    }

    /**
     * Attaches an already-authenticated caller holding exactly one authority to a request.
     *
     * <p>Assumptions: the authority is passed explicitly rather than left to the post-processor's default,
     * because the defaults are scope-derived names this service's chain does not recognise, so a request
     * carrying them would be refused for a reason unrelated to the case. The names handed in are the shared
     * converter's own compiled constants, which is what keeps the demanded authority and the granted one
     * the same value rather than two strings that happen to match.
     *
     * @param authority the single authority the attached caller holds; must not be {@code null}
     * @return a post-processor that authenticates the request as that caller; never {@code null}
     */
    private static RequestPostProcessor callerWithAuthority(String authority) {
        return jwt().authorities(new SimpleGrantedAuthority(authority));
    }

    /**
     * Stubs the substituted decoder to answer a presented bearer value with the named group claim.
     *
     * <p>Assumptions: the claim key is {@link JwtRoleConverter#GROUPS_CLAIM} rather than a string written
     * here, so a change to the claim the deployed converter reads makes these cases fail rather than
     * silently pass on a claim nothing consumes. A header is required on the built token because the token
     * type refuses to be constructed without one; its value carries no meaning to any assertion.
     *
     * @param groupNames the group names the answered token carries in its group claim; must not be
     *     {@code null}
     */
    private static void presentGroupClaim(String... groupNames) {
        Jwt answered = Jwt.withTokenValue(PRESENTED_BEARER_VALUE)
                .header("alg", "none")
                .subject("card-caller-under-assertion")
                .claim(JwtRoleConverter.GROUPS_CLAIM, List.of(groupNames))
                .build();

        when(jwtDecoder.decode(anyString())).thenReturn(answered);
    }

    /**
     * Builds the sixteen-digit demonstration number of one fixture row.
     *
     * @param index the row's one-based ordinal, which must leave the composed value sixteen digits long
     * @return the composed number; never {@code null}
     */
    private static String cardNumber(int index) {
        return String.format(CARD_NUMBER_TEMPLATE, index);
    }

    /**
     * Seals one fixture row's number into the opaque selector every card route addresses it by.
     *
     * <p>Assumptions: the purpose handed to the seal is {@link CardMapper#SELECTOR_PURPOSE}, the same one
     * the mapper uses, rather than a string written here. The purpose is bound into the seal, so a selector
     * minted under a different one would be a value no deployment could open -- and while the substituted
     * services never open these, a fixture indistinguishable from a produced value is what keeps the
     * response shapes constructible and the assertions honest.
     *
     * @param index the row's one-based ordinal
     * @return the sealed selector for that row; never {@code null}
     */
    private static String selectorFor(int index) {
        return SELECTOR_SEALER.seal(CardMapper.SELECTOR_PURPOSE, cardNumber(index));
    }

    /**
     * Builds the masked detail of one fixture row at the revision it is stored at.
     *
     * @param index the row's one-based ordinal
     * @return the masked detail; never {@code null}
     */
    private static CardDetail maskedDetail(int index) {
        return savedDetail(index, STORED_VERSION);
    }

    /**
     * Builds the masked detail of one fixture row at a nominated revision.
     *
     * <p>Assumptions: the masked member is produced by {@link CardNumberMasker#mask(String)} because the
     * response shape REFUSES any other rendering in its compact constructor, so a fixture that masked by
     * hand could not be constructed if the two ever disagreed.
     *
     * @param index the row's one-based ordinal
     * @param version the revision the built detail reports
     * @return the masked detail; never {@code null}
     */
    private static CardDetail savedDetail(int index, int version) {
        return new CardDetail(selectorFor(index), CardNumberMasker.mask(cardNumber(index)),
                ACCOUNT_ID, EMBOSSED_NAME, EXPIRATION_DATE, "Y", version);
    }

    /**
     * Builds the administrative detail of one fixture row, carrying the number in full.
     *
     * <p>Assumptions: this shape carries the number UNMASKED and carries no additional member for doing
     * so, which is what the two member-set cases above assert. It is a separate type from the masked
     * detail so the ordinary routes cannot reach the disclosure through any argument a caller supplies.
     *
     * @param index the row's one-based ordinal
     * @return the administrative detail; never {@code null}
     */
    private static AdminCardDetail administrativeDetail(int index) {
        return new AdminCardDetail(selectorFor(index), cardNumber(index), ACCOUNT_ID, EMBOSSED_NAME,
                EXPIRATION_DATE, "Y", STORED_VERSION);
    }

    /**
     * Builds a page holding the reference row count with a further page available.
     *
     * <p>Assumptions: the two boundary positions are REAL sealed tokens over the boundary rows' keys, and
     * they have to be. The envelope refuses a position that is not sealed, on the stated ground that a
     * position built from the key columns would publish those columns and one of them is a primary account
     * number, so a page carrying two plain strings cannot be constructed. This class asserts only that
     * both positions are PRESENT on a page that read rows and absent on one that did not; what a position
     * decodes to belongs to the service that mints it and is asserted where that service is.
     *
     * @return the page; never {@code null}
     */
    private static PageResponse<CardSummary> fullPage() {
        List<CardSummary> rows = new ArrayList<>(CardListService.PAGE_SIZE);
        for (int index = 1; index <= CardListService.PAGE_SIZE; index++) {
            rows.add(summaryRow(index));
        }

        return PageResponse.ofRows(rows,
                CURSOR_SEALER.seal(CURSOR_BINDING, cardNumber(1)),
                CURSOR_SEALER.seal(CURSOR_BINDING, cardNumber(CardListService.PAGE_SIZE)),
                true);
    }

    /**
     * Builds one row of the browse page.
     *
     * <p>Assumptions: a row carries a sealed selector and a masked rendering and NOTHING that identifies
     * the cardholder, which is the reference display row's own composition -- twenty-eight characters of
     * account number, card number and status at {@code app/cbl/COCRDLIC.cbl:258-260} and no name, date or
     * verification value.
     *
     * @param index the row's one-based ordinal
     * @return the row; never {@code null}
     */
    private static CardSummary summaryRow(int index) {
        return new CardSummary(selectorFor(index), CardNumberMasker.mask(cardNumber(index)),
                ACCOUNT_ID, "Y");
    }

    /**
     * Serialises a conforming edit submission carrying a nominated revision.
     *
     * <p>Assumptions: the body is produced from the request record rather than written as JSON text, so a
     * member the contract renames cannot leave this file still sending the old name and still compiling.
     * The four attribute values are inside every published bound, because the cases that use this body
     * assert an outcome the SERVICE reports rather than one a declared constraint reports.
     *
     * @param version the revision the submission claims to have read the row at
     * @return the serialised submission; never {@code null}
     */
    private static String submittedEdit(int version) {
        return MAPPER.writeValueAsString(
                new CardUpdateRequest(EMBOSSED_NAME, "Y", "06", "2024", version));
    }

    /**
     * Reads the complete set of top-level member names a response published.
     *
     * <p>Assumptions: the whole set is returned rather than a queried member, because the cases that call
     * this assert what a body does NOT carry, and no path expression can state an absence over a shape
     * that may have grown a member nobody thought to query.
     *
     * @param answered the result whose body is read; must not be {@code null}
     * @return the member names the body carried at its top level; never {@code null}
     * @throws Exception if the response body cannot be read or is not a JSON object
     */
    private static Set<String> publishedMemberNames(MvcResult answered) throws Exception {
        return Set.copyOf(
                MAPPER.readTree(answered.getResponse().getContentAsString()).propertyNames());
    }

    /**
     * Reads the field state the first per-field entry of a refusal published.
     *
     * <p>Assumptions: the state is converted back into the shared enumeration rather than compared as
     * text, so the case that calls this can ask the state itself which screen marker attaches to it instead
     * of restating the mapping. That is what keeps the marker derived from the published state.
     *
     * @param answered the refusal whose body is read; must not be {@code null}
     * @return the state the first entry published; never {@code null}
     * @throws Exception if the response body cannot be read
     */
    private static FieldValidationFlag publishedFieldState(MvcResult answered) throws Exception {
        String published = MAPPER.readTree(answered.getResponse().getContentAsString())
                .path("fieldErrors").path(0).path("state").asString();

        return FieldValidationFlag.valueOf(published);
    }

    /**
     * The wiring this slice refreshes: the adapter, the shared advice and the deployed filter chain.
     *
     * <p>Assumptions: the chain and the token converter are obtained by CALLING the deployed
     * configuration's own methods rather than by registering that class, and that distinction is the whole
     * reason this type exists. Registering {@code com.carddemo.card.config.SecurityConfig} would also
     * register its decoder factory, and that factory resolves the issuer document while the context
     * refreshes -- against the reserved issuer name this module's test profile pins, which no build host has
     * a route to. Calling the two methods that matter leaves every rule, every refusal renderer and the
     * group-to-authority translation exactly as deployed while the one bean needing a provider is
     * substituted.
     *
     * <p>Assumptions: the two group names handed to the converter factory are the shared kernel's own
     * compiled constants. That factory compares what it is given against those constants and refuses to
     * start on a mismatch, so passing them is what makes this slice's authority derivation identical to a
     * deployment's rather than merely similar to it.
     *
     * <p>Trade-offs: the MVC infrastructure is enabled here rather than inherited from a started
     * application, so anything an actuator or an auto-configuration contributes at run time is absent. What
     * is bought is that no data source, no schema migration and no token issuer has to be reachable for a
     * status, a body member or a sentence to be asserted; what is given up is coverage of the run-time
     * registrations themselves, which is why the health case above asserts only the property the chain
     * owns.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class SliceWiring {

        /**
         * Supplies the fixed clock the shared advice stamps every rendered body from.
         *
         * @return a clock pinned to the class's fixed instant; never {@code null}
         */
        @Bean
        Clock clock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }

        /**
         * Supplies the substituted browse service.
         *
         * <p>Assumptions: the service is substituted rather than built over a store, because the keyset
         * decisions it owns are asserted against the reference paragraphs in the sibling service test
         * package and through a real service in the sibling dispatcher class. What is left for this class
         * is how the envelope it returns is serialised and which callers may ask for it.
         *
         * @return a substitute for the browse service, with no stubbing applied; never {@code null}
         */
        @Bean
        CardListService cardListService() {
            return mock(CardListService.class);
        }

        /**
         * Supplies the substituted masked-read service.
         *
         * @return a substitute for the masked-read service, with no stubbing applied; never {@code null}
         */
        @Bean
        CardViewService cardViewService() {
            return mock(CardViewService.class);
        }

        /**
         * Supplies the substituted administrative-read service.
         *
         * @return a substitute for the administrative-read service, with no stubbing applied; never
         *     {@code null}
         */
        @Bean
        CardAdminViewService cardAdminViewService() {
            return mock(CardAdminViewService.class);
        }

        /**
         * Supplies the substituted edit service, which every conflict below is raised from.
         *
         * @return a substitute for the edit service, with no stubbing applied; never {@code null}
         */
        @Bean
        CardUpdateService cardUpdateService() {
            return mock(CardUpdateService.class);
        }

        /**
         * Supplies the adapter under assertion, wired exactly as a deployment wires it.
         *
         * @param reads the substituted browse service; must not be {@code null}
         * @param views the substituted masked-read service; must not be {@code null}
         * @param adminViews the substituted administrative-read service; must not be {@code null}
         * @param writes the substituted edit service; must not be {@code null}
         * @return the adapter; never {@code null}
         */
        @Bean
        CardController cardController(CardListService reads, CardViewService views,
                CardAdminViewService adminViews, CardUpdateService writes) {
            return new CardController(reads, views, adminViews, writes);
        }

        /**
         * Supplies the shared error advice so the rendered body shape is the deployed one.
         *
         * <p>Assumptions: this is CT-01's registration. The advice is discovered because it carries the
         * advice stereotype, which is how a running service finds it too, and it is declared HERE because
         * it lives outside this context's scan root and reaches a deployment only through the shared
         * kernel's auto-configuration -- which a hand-refreshed context does not apply. Alternatives
         * Considered: omitting it and asserting statuses only; rejected because the container's own error
         * representation would then satisfy every status assertion while carrying none of the members the
         * contract publishes.
         *
         * @param clock the clock the advice stamps its bodies from; must not be {@code null}
         * @return the shared advice; never {@code null}
         */
        @Bean
        GlobalExceptionHandler globalExceptionHandler(Clock clock) {
            return new GlobalExceptionHandler(clock);
        }

        /**
         * Supplies the substituted decoder the resource-server filter resolves a presented value with.
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
         * @return the converter the deployed configuration builds; never {@code null}
         */
        @Bean
        JwtAuthenticationConverter jwtAuthenticationConverter() {
            return new SecurityConfig().jwtAuthenticationConverter(
                    JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);
        }

        /**
         * Supplies the deployed filter chain, rules, refusal renderers and session policy included.
         *
         * <p>Assumptions: this is CT-02's registration, and it is the reason an authority assertion in
         * this class is not vacuous. Alternatives Considered: an unsecured slice, which is how the two
         * sibling dispatcher classes are assembled; rejected here because the administrative route's
         * admitted and refused outcomes would then be the same outcome, and a case observing one outcome
         * for every caller states nothing about a rule.
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
