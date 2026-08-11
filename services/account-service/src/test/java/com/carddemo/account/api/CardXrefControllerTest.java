package com.carddemo.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.account.config.SecurityConfig;
import com.carddemo.account.dto.AccountLookupRequest;
import com.carddemo.account.dto.CardXrefLookupRequest;
import com.carddemo.account.dto.CardXrefResponse;
import com.carddemo.account.dto.CardXrefView;
import com.carddemo.account.service.AccountViewService;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds the published contract invariants of the card cross-reference read surface.
 *
 * <p>Purpose: this class pins the facts about {@link CardXrefController} that hold on every response it
 * ever produces -- how many members its projection declares and of what type, which value it reduces
 * before publishing, which members it does not declare at all, that its account-keyed walk publishes only
 * the rows it was asked for and names its boundaries as opaque tokens, which step words its walk admits,
 * and which authority the deployed chain demands of the subtree. The reference is
 * {@code app/cbl/CBACT03C.cbl}, 178 lines: a BATCH reader carrying no {@code EXEC CICS} verb and bound to
 * no transaction, whose whole access surface is its file declaration at L29 through L33 and its driver
 * loop at L74 through L81. The record layout is {@code app/cpy/CVACT03Y.cpy}, 11 lines, whose group item
 * {@code 01 CARD-XREF-RECORD.} opens at L4 and whose header at L2 declares 50 bytes. Both are
 * reference-only: every figure below was read from them and is cited by path and physical line, and
 * neither is modified.
 *
 * <p>Assumptions: NO EXECUTABLE PARITY ORACLE EXISTS for this reference, so every case below was authored
 * from the COBOL paragraphs directly and none of them may be described as agreeing with a golden master.
 * {@code tests/README.md} records at L83 through L85 that the online {@code CO*} CICS programs cannot be
 * driven end to end without a CICS runtime, which the build host does not have, and that only their
 * extractable field-validation logic is unit-tested. The catalogue of verbatim business rules in that same
 * file opens at its L553 and, from that line onward, names {@code CBTRN02C}, {@code CBACT04C} and
 * {@code TCATBAL} and no program of this bounded context at all -- neither {@code CBACT03C} nor
 * {@code COACTVWC} nor {@code COACTUPC} -- so there is no recorded output for this reference to compare
 * with. These assertions therefore ENCODE the documented layout and access paths and are strictly additive
 * to that suite, which is reference-only and is neither modified, nor re-pinned, nor reached by any path
 * from here.
 *
 * <p>Parameters, return values, exceptions or errors. A test class is instantiated by the engine and
 * declares no constructor, yields no value to a caller and raises nothing at the type level, so this
 * heading carries no at-clause; the inapplicability is declared rather than passed over so that a reader
 * can tell it from an oversight. Every method below carries its own complete set.
 *
 * <h2>The two dispatcher shapes, and why neither is a context slice</h2>
 *
 * <p>Alternatives Considered: a framework-assembled web slice over this one controller, which the
 * package charter in {@code package-info.java} beside this file evaluated and rejected for the whole
 * package, and which is therefore not adopted here either. Its cost is a started context, this module's
 * own auto-configuration and its filter chain, none of which decides any outcome asserted below, while
 * route membership and the security expression are already asserted from the handler metadata by
 * {@code AccountContextContractTest} and {@code InternalApiSecurityConfigTest}. No test in this build uses
 * that shape, so adopting it in this one file would make this module the one a reader had to learn twice.
 * What is used instead is the shape every other controller test in this build uses: a standalone
 * dispatcher over the real controller with the shared advice registered for every document and envelope
 * case, and a hand-assembled security-enabled context for the two authority cases.
 *
 * <p>Assumptions: exactly ONE collaborator is substituted, {@link AccountViewService}, because that is the
 * only one this controller holds -- its single constructor takes the read path and nothing else, and all
 * three of its operations read. Nothing here substitutes a repository, a mapper or a write path, and
 * nothing here starts a database, applies a schema migration or reaches an identity pool.
 *
 * <h2>What this class asserts, and what its siblings assert</h2>
 *
 * <p>Assumptions: the division matters more than an inventory, because an overlap gives a later change two
 * places to update and one place to forget. {@code AccountContextContractTest} owns the machine contract
 * of this surface -- that {@link CardXrefView} declares the two identifiers and no card number, that an
 * unresolved card raises the type the shared advice renders as 404, that the route constants are the
 * addresses the consuming contexts are pinned to, and that each published schema declares exactly its
 * record's members. {@code InternalApiSecurityConfigTest} owns which chain governs each of the three
 * addresses. {@code CardXrefByAccountReadTest} in the sibling service package owns the walk's BEHAVIOUR
 * against a mocked repository, including how a boundary token is sealed and which mismatched binding it
 * refuses. {@code AccountControllerTest} owns the same walk as published on the ACCOUNT route and owns the
 * conflict contract of the update path. This class owns what is left and is the only place it is asserted:
 * the documents and envelopes THIS controller answers with on ITS three addresses, the parameter domain of
 * its walk, and the deployed chain's verdict on its subtree.
 *
 * @see CardXrefController
 * @see AccountViewService
 */
class CardXrefControllerTest {

    /**
     * The dispatcher path of the card-keyed cross-reference read.
     */
    private static final String LOOKUP_ROUTE =
            CardXrefController.BASE_PATH + CardXrefController.LOOKUP_PATH;

    /**
     * The dispatcher path of the single account-keyed cross-reference read.
     */
    private static final String ACCOUNT_LOOKUP_ROUTE =
            CardXrefController.BASE_PATH + CardXrefController.LOOKUP_BY_ACCOUNT_PATH;

    /**
     * The dispatcher path of the account-keyed cross-reference walk.
     */
    private static final String WALK_ROUTE =
            CardXrefController.BASE_PATH + CardXrefController.SEARCH_BY_ACCOUNT_PATH;

    /**
     * The card number every case keys its read on, obviously synthetic and of the declared width.
     *
     * <p>Assumptions: sixteen digits exactly, which is what {@code XREF-CARD-NUM PIC X(16)} at L5 of
     * {@code app/cpy/CVACT03Y.cpy} declares and what {@link CardXrefLookupRequest#CARD_NUMBER_LENGTH}
     * republishes as the accepted length. A value of any other width would be refused at the edge before
     * it reached the assertion it was written for.</p>
     */
    private static final String CARD_KEY = "4000123456789010";

    /**
     * The number of trailing digits a published card number discloses.
     */
    private static final int DISCLOSED_DIGIT_COUNT = 4;

    /**
     * The marker a published card number carries in place of the digits it withholds.
     *
     * <p>Assumptions: the mapper upstream of this controller composes a published card number as this
     * marker followed by the disclosed trailing digits, so a fixture built any other way would assert a
     * form no deployment produces. The marker is declared here rather than read from that class because it
     * is private there, which is itself the correct arrangement: the only place a masking decision is made
     * is the one place that makes it.</p>
     */
    private static final String MASK_MARKER = "****";

    /**
     * The published form of {@link #CARD_KEY}, reduced to its trailing digits.
     */
    private static final String MASKED_CARD_KEY =
            MASK_MARKER + CARD_KEY.substring(CARD_KEY.length() - DISCLOSED_DIGIT_COUNT);

    /**
     * The leading digits of {@link #CARD_KEY}, the span no response may carry.
     *
     * <p>Assumptions: asserted separately from the whole value because a partial disclosure is still a
     * disclosure -- a response that published the issuer span and withheld only the middle would satisfy
     * an assertion written against the complete sixteen characters.</p>
     */
    private static final String WITHHELD_CARD_PREFIX = CARD_KEY.substring(0, 6);

    /**
     * The account identifier every account-keyed case reads with, as the request carries it.
     *
     * <p>Assumptions: inside the range {@link AccountLookupRequest#ACCOUNT_ID_MIN} through
     * {@link AccountLookupRequest#ACCOUNT_ID_MAX}, which is the eleven-digit domain
     * {@code XREF-ACCT-ID PIC 9(11)} declares at L7 of {@code app/cpy/CVACT03Y.cpy}.</p>
     */
    private static final long ACCOUNT_ID = 12_345_678_901L;

    /**
     * The published form of {@link #ACCOUNT_ID}, digits only and at its declared width.
     */
    private static final String ACCOUNT_KEY = "12345678901";

    /**
     * The published form of the customer identifier, digits only and at its declared width.
     *
     * <p>Assumptions: nine characters including the leading zeros, because
     * {@code XREF-CUST-ID PIC 9(09)} at L6 of {@code app/cpy/CVACT03Y.cpy} is a display numeric of
     * invariant width and a shorter rendering of the same value is a different key.</p>
     */
    private static final String CUSTOMER_KEY = "000000042";

    /**
     * The customer identifier the substituted read path answers the card-keyed read with.
     */
    private static final long CUSTOMER_ID = 42L;

    /**
     * The request body of the card-keyed read, carrying the complete card number.
     */
    private static final String LOOKUP_BODY = "{\"cardNumber\":\"" + CARD_KEY + "\"}";

    /**
     * The request body of both account-keyed operations.
     */
    private static final String ACCOUNT_BODY = "{\"accountId\":" + ACCOUNT_ID + "}";

    /**
     * The validated caller the walk seals its boundaries for.
     */
    private static final String SUBJECT = "carddemo-tester";

    /**
     * The number of members the cross-reference projection declares.
     *
     * <p>Assumptions: three, one per named field of the record -- {@code XREF-CARD-NUM} at L5,
     * {@code XREF-CUST-ID} at L6 and {@code XREF-ACCT-ID} at L7 of {@code app/cpy/CVACT03Y.cpy} -- and
     * none for the {@code FILLER PIC X(14)} at L8, which pads the record to the 50 bytes its L2 header
     * declares and carries nothing a caller can read.</p>
     */
    private static final int DECLARED_MEMBER_COUNT = 3;

    /**
     * The number of members the published page envelope declares.
     */
    private static final int ENVELOPE_MEMBER_COUNT = 5;

    /**
     * The number of rows every page fixture in this class carries.
     *
     * <p>Assumptions: above one so a page can hold more than a single row and an ordering assertion has
     * something to order, and small enough that each row's disclosed digits stay readable in a failure
     * message. The service's own window width is its business and is asserted where it is declared.</p>
     */
    private static final int PAGE_WIDTH = 3;

    /**
     * The width of the reference's informational message channel.
     *
     * <p>Assumptions: forty characters, read from {@code 05  WS-INFO-MSG  PIC X(40).} at L110 of
     * {@code app/cbl/COACTVWC.cbl}.</p>
     */
    private static final int INFORMATION_CHANNEL_WIDTH = 40;

    /**
     * The width of the reference's aggregate return message channel.
     *
     * <p>Assumptions: seventy-five characters, read from {@code 05  WS-RETURN-MSG  PIC X(75).} at L117 of
     * {@code app/cbl/COACTVWC.cbl} and corroborated by the two commarea fields a search of
     * {@code app/cpy/} for that width returns, {@code CCARD-ERROR-MSG} at L28 and
     * {@code CCARD-RETURN-MSG} at L29 of {@code app/cpy/CVCRD01Y.cpy}. It is NOT the fifty-character
     * regime of {@code app/cpy/CSMSG01Y.cpy}, whose two messages are declared {@code PIC X(50)} across
     * L18 to L19 and L20 to L21.</p>
     */
    private static final int AGGREGATE_CHANNEL_WIDTH = 75;

    /**
     * The width the symbolic maps declare for the informational field, which is not the contract.
     *
     * <p>Assumptions: forty-five characters, from {@code 02  INFOMSGI  PIC X(45).} at L234 of
     * {@code app/cpy-bms/COACTVW.CPY} and at L318 of {@code app/cpy-bms/COACTUP.CPY}. Held here only so
     * the case that chooses between the two regimes can name the one it declines.</p>
     */
    private static final int SCREEN_INFORMATION_WIDTH = 45;

    /**
     * The width the symbolic maps declare for the error field, which is not the contract.
     *
     * <p>Assumptions: seventy-eight characters, from {@code 02  ERRMSGI  PIC X(78).} at L240 of
     * {@code app/cpy-bms/COACTVW.CPY} and at L324 of {@code app/cpy-bms/COACTUP.CPY}.</p>
     */
    private static final int SCREEN_AGGREGATE_WIDTH = 78;

    /**
     * The step word that advances the walk, the analogue of the reference's forward browse.
     */
    private static final String STEP_FORWARD = "next";

    /**
     * The step word that retreats the walk, the analogue of the reference's backward browse.
     */
    private static final String STEP_BACKWARD = "previous";

    /**
     * The instant the shared advice stamps every problem document with in this class.
     */
    private static final Instant PINNED_INSTANT = Instant.parse("2026-08-11T06:00:00Z");

    /**
     * The binding this class's fixture boundaries are sealed under.
     *
     * <p>Assumptions: deliberately NOT the walk's own binding. The envelope checks only the SHAPE of a
     * boundary and never opens one, so reusing the walk's binding would suggest this class asserts
     * something about which query sealed a token, which it does not; that is asserted in the sibling
     * service package.</p>
     */
    private static final String FIXTURE_BINDING = "account.card-xref.contract-test";

    /**
     * Key material for the sealer that mints this class's boundary tokens.
     *
     * <p>Assumptions: test-only material and not a credential, spelled as prose so that neither a reader
     * nor a secret scanner's high-entropy rule can mistake it for a key. It exceeds
     * {@link CursorToken#MIN_KEY_LENGTH} bytes, which the sealer refuses to be constructed below.</p>
     */
    private static final byte[] CURSOR_KEY =
            "carddemo-account-card-xref-contract-test-cursor-material-not-a-secret"
                    .getBytes(StandardCharsets.UTF_8);

    /**
     * The sealer that mints the boundary tokens this class's page fixtures publish.
     *
     * <p>Assumptions: a REAL sealer is required rather than tidy, even though nothing here opens a token.
     * {@link PageResponse}'s canonical constructor refuses a boundary that is not a sealed token, because
     * a boundary built from the key columns would publish those columns to a client -- and on this surface
     * the ordering key IS the primary account number, which is exactly the value the context withholds. A
     * literal such as {@code "opening-position"} therefore cannot be used, and the envelope's own guard is
     * what says so.</p>
     */
    private static final CursorToken FIXTURE_SEALER =
            new CursorToken(CURSOR_KEY, Duration.ofHours(1));

    /**
     * The read path, substituted so a case controls the answer and can observe what it was asked.
     */
    private static AccountViewService reads;

    /**
     * The context backing the dispatcher that carries the deployed filter chain.
     */
    private static AnnotationConfigWebApplicationContext guardedContext;

    /**
     * The dispatcher that carries the deployed filter chain, used only for authority questions.
     */
    private static MockMvc guarded;

    /**
     * The dispatcher that carries no filter chain, used for every document and envelope assertion.
     */
    private MockMvc mockMvc;

    /**
     * The reader that answers which members a serialised body actually carries.
     *
     * <p>Assumptions: built with no module registered, because nothing this surface publishes needs one --
     * all three members of the projection are text and neither the projection nor the envelope carries a
     * monetary component. The account record's five monetary fields, which
     * {@code app/cpy/CVACT01Y.cpy} declares as signed decimals at L7, L8, L9, L13 and L14, belong to a
     * different document asserted in a different class.</p>
     */
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Builds the substituted read path and the dispatcher that carries the deployed filter chain.
     *
     * <p>Trade-offs: the guarded context is built ONCE for the class rather than per case, because
     * assembling a security-enabled context is the most expensive thing this class does and no case below
     * mutates it. What is given up is per-case isolation of the substituted collaborator, which is bought
     * back by resetting it before each case.</p>
     */
    @BeforeAll
    static void buildGuardedDispatcher() {
        reads = mock(AccountViewService.class);

        guardedContext = new AnnotationConfigWebApplicationContext();
        guardedContext.setServletContext(new MockServletContext());
        guardedContext.register(GuardedSliceWiring.class);
        guardedContext.refresh();

        // WHAT: wraps the security-enabled context in a dispatcher whose requests traverse the chain.
        // WHY : Assumptions: the configurer form is used rather than adding the chain filter by hand,
        //       because the request post-processor that mints an authentication publishes it through the
        //       test context repository this configurer installs. Adding the filter alone would leave
        //       every minted token invisible to the chain, so a refusal asserted below could have been
        //       caused by the wiring rather than by the rule under assertion.
        guarded = MockMvcBuilders.webAppContextSetup(guardedContext)
                .apply(springSecurity())
                .build();
    }

    /**
     * Releases the guarded context so the class leaves no started context behind.
     */
    @AfterAll
    static void closeGuardedDispatcher() {
        guardedContext.close();
    }

    /**
     * Builds the unguarded dispatcher and returns the substituted read path to a clean state.
     */
    @BeforeEach
    void buildDispatcher() {
        reset(reads);

        // WHY : Assumptions: the shared advice is REGISTERED, never re-implemented. The not-found status,
        //       the refusal code and the problem document's member set belong to
        //       com.carddemo.common.error.GlobalExceptionHandler, so a dispatcher assembled without it
        //       would answer an absent cross-reference row with 500 and the failure would read as the
        //       controller misbehaving rather than as a missing participant in this wiring.
        this.mockMvc = MockMvcBuilders.standaloneSetup(new CardXrefController(reads))
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    /**
     * The cross-reference projection declares the record's three named fields, all as text, and no padding.
     *
     * <p>Purpose: {@code app/cpy/CVACT03Y.cpy} declares three data fields at L5 through L7 beneath the
     * group item at L4, followed by {@code FILLER PIC X(14)} at L8. This case pins the count, pins that
     * every published member is text, and pins that the padding is absent -- three facts a single added or
     * retyped member would break.</p>
     *
     * <p>Assumptions: both numeric fields of this record are published as digits-only TEXT members rather
     * than as numeric ones, namely the customer identifier {@code PIC 9(09)} at L6 and the account
     * identifier {@code PIC 9(11)} at L7. Two independently verified grounds carry the decision. The
     * reference holds exactly that separation itself, declaring a character field and overlaying a numeric
     * reading on the same storage: {@code ACUP-OLD-ACCT-ID-X PIC X(11)} at L671 of
     * {@code app/cbl/COACTUPC.cbl} redefined {@code PIC 9(11)} across L672 and L673,
     * {@code ACUP-OLD-CURR-BAL PIC X(12)} at L675 redefined {@code PIC S9(10)V99} across L676 and L677,
     * and {@code ACUP-OLD-CUST-FICO-SCORE-X PIC X(03)} at L754 redefined {@code PIC 9(03)} across L755 and
     * L756 -- so the value on the wire is characters and the number is a reading of it. And the two
     * symbolic maps disagree on the type of the same logical field at the same physical line,
     * {@code ACCTSIDI} being {@code PIC X(11)} at L60 of {@code app/cpy-bms/COACTUP.CPY} against
     * {@code PIC 99999999999} at L60 of {@code app/cpy-bms/COACTVW.CPY}, and the stricter update-map form
     * is the one taken. A numeric-typed member would silently accept and reformat values the reference
     * treats as characters, and would additionally drop the leading zeros an invariant-width key depends
     * on.</p>
     *
     * <p>Assumptions: the padding's absence is read from the record DECLARATION rather than from a
     * serialised body, because an absent member and a member serialised as null are indistinguishable once
     * null suppression is in play, and only the declaration answers the question this case asks. The
     * arithmetic corroborates that nothing is lost by dropping it: the three named widths are 16 plus 9
     * plus 11, which is 36, and 36 plus the 14 of the padding is the 50 that L2 of the same copybook
     * declares.</p>
     */
    @Test
    @DisplayName("the cross-reference projection declares three text members and no padding")
    void theCrossReferenceProjectionDeclaresThreeTextMembersAndNoPadding() {
        List<String> declared = componentNamesOf(CardXrefResponse.class);

        assertThat(declared)
                .as("the projection must declare one member per named field at L5 through L7, and no more")
                .hasSize(DECLARED_MEMBER_COUNT)
                .containsExactly("cardNumberMasked", "customerId", "accountId");

        assertThat(declared)
                .as("the padding at L8 carries no data and must not be published")
                .noneMatch(member -> member.toLowerCase(Locale.ROOT).contains("filler"));

        for (RecordComponent component : CardXrefResponse.class.getRecordComponents()) {
            assertThat(component.getType())
                    .as("member %s must travel as text, keeping the leading zeros its width requires",
                            component.getName())
                    .isEqualTo(String.class);
        }
    }

    /**
     * The cross-reference projection declares no card verification value to withhold.
     *
     * <p>Purpose: this case asserts an ABSENCE rather than a masking, and the distinction is the whole
     * point of writing it. A case asserting that such a value is published masked would be asserting a
     * member into existence.</p>
     *
     * <p>Assumptions: no record this bounded context owns declares a card verification value at all --
     * {@code app/cpy/CVACT01Y.cpy} is 20 lines and declares the account's twelve fields and its padding,
     * {@code app/cpy/CVCUS01Y.cpy} is 26 lines and declares the customer's eighteen fields and its
     * padding, and {@code app/cpy/CVACT03Y.cpy} is 11 lines and declares the three cross-reference fields
     * and its padding. This module's own migration {@code db/migration/V1__account.sql} creates no such
     * column on any of its three tables either. There is therefore nothing here to mask, and the correct
     * assertion is that nothing names one. The verification value the wider application does hold belongs
     * to the card bounded context, whose own tests assert its treatment.</p>
     */
    @Test
    @DisplayName("the cross-reference projection declares no card verification value")
    void theCrossReferenceProjectionDeclaresNoCardVerificationValue() {
        assertThat(componentNamesOf(CardXrefResponse.class))
                .as("no member of a record that declares no verification value may name one")
                .noneMatch(CardXrefControllerTest::namesACardVerificationValue);

        assertThat(componentNamesOf(CardXrefView.class))
                .as("the resolved view declares two identifiers and names no verification value either")
                .noneMatch(CardXrefControllerTest::namesACardVerificationValue);
    }

    /**
     * The card-keyed read answers one row from the complete card number and asks nothing else.
     *
     * <p>Purpose: the cross-reference is keyed on the card and on nothing else --
     * {@code RECORD KEY   IS FD-XREF-CARD-NUM} at L32 of {@code app/cbl/CBACT03C.cbl} -- so this operation
     * is the migrated form of that keyed access. The case asserts the answer carries both resolved
     * identifiers, that the request carrying the COMPLETE sixteen-character key resolves, and that the read
     * path was asked for that one key and asked nothing further.</p>
     *
     * <p>Assumptions: the read is asserted to be KEYED rather than swept, by verifying no further
     * interaction with the read path. The reference program itself sweeps: L74 opens
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} and its paragraph {@code 1000-XREFFILE-GET-NEXT.} at L92
     * advances one record at a time at L93 under the {@code ACCESS MODE  IS SEQUENTIAL} its L31 declares.
     * That is a batch report driver, not the access path a single lookup needs, so the migrated operation
     * resolves one key and the absence of any other call is what says so.</p>
     *
     * <p>Assumptions: the response is asserted to carry NO part of the card number, which is stronger than
     * masking and is what this particular answer permits. The caller supplied the card, so echoing any of
     * it back would place a primary account number in a second response body and a second client's memory
     * for no gain. The leading span is asserted separately from the whole value because a response
     * publishing only the issuer digits would satisfy an assertion written against all sixteen.</p>
     *
     * @throws Exception if the request cannot be performed or the response body cannot be read
     */
    @Test
    @DisplayName("the card-keyed read answers one row from the complete key and asks nothing else")
    void theCardKeyedReadAnswersOneRowFromTheCompleteKey() throws Exception {
        when(reads.resolveCardCrossReference(CARD_KEY))
                .thenReturn(new CardXrefView(ACCOUNT_ID, CUSTOMER_ID));

        String body = bodyOf(post(LOOKUP_ROUTE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(LOOKUP_BODY));

        assertThat(body)
                .as("a resolved row publishes both identifiers and no part of the key it was found by")
                .contains(String.valueOf(ACCOUNT_ID))
                .contains(String.valueOf(CUSTOMER_ID))
                .doesNotContain(CARD_KEY)
                .doesNotContain(WITHHELD_CARD_PREFIX);

        verify(reads).resolveCardCrossReference(CARD_KEY);
        verifyNoMoreInteractions(reads);
    }

    /**
     * A card with no cross-reference row answers the problem document rather than an empty success.
     *
     * <p>Purpose: three outcomes are wrong here and each is excluded. A 200 carrying an empty body would
     * make an absent row indistinguishable from a resolved one whose members were null. A 500 would report
     * an ordinary business outcome as a defect. A body of some other shape would give this service a second
     * refusal contract. The case therefore pins the status, the refusal code and the per-field array
     * together.</p>
     *
     * <p>Assumptions: the per-field array is asserted PRESENT and EMPTY rather than absent. The shared
     * problem record normalises an unsupplied array to the empty one in its canonical constructor, so a
     * client may bind it unconditionally; a case tolerating its absence would let that normalisation be
     * removed without a failure. Nothing about this refusal is attributable to a single request member --
     * the key was well formed and simply resolved to nothing -- so the array being empty is the correct
     * content rather than an omission.</p>
     *
     * <p>Assumptions: the rendered aggregate sentence is asserted to carry no part of the card number. The
     * shared advice writes that sentence to the operational record as well as to the response body, so a
     * sentence naming the key would place a primary account number in a durable diagnostic.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a card with no cross-reference row answers the problem document")
    void anAbsentCardAnswersTheProblemDocument() throws Exception {
        when(reads.resolveCardCrossReference(CARD_KEY))
                .thenThrow(new NoSuchElementException("no cross-reference row exists for the card"));

        String body = this.mockMvc.perform(post(LOOKUP_ROUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOOKUP_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_NOT_FOUND))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors.length()").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .as("a durable diagnostic may carry no part of the primary account number")
                .doesNotContain(CARD_KEY)
                .doesNotContain(WITHHELD_CARD_PREFIX);
    }

    /**
     * An ill-formed card key is answered as a per-field refusal rather than as an internal failure.
     *
     * <p>Purpose: the accepted key is sixteen digits, which is what {@code XREF-CARD-NUM PIC X(16)} at L5
     * of {@code app/cpy/CVACT03Y.cpy} declares. Two ill-formed values are driven -- one of the wrong width
     * and one carrying a character that is not a digit -- and each must be answered as a refusal keyed to
     * the member that offended, with the read path never entered.</p>
     *
     * <p>Assumptions: this is the migrated form of the reference's validation-flag pattern, in which a
     * per-field condition is raised and the offending field is then highlighted on the screen. The
     * migration expresses that as a structured per-field array on the refusal document, so the assertion
     * names the member and its state rather than a screen attribute. The state is the refused one rather
     * than the absent one, because a value WAS supplied and was rejected on its content.</p>
     *
     * @throws Exception if a request cannot be performed
     */
    @Test
    @DisplayName("an ill-formed card key is answered as a per-field refusal keyed to the card number")
    void anIllFormedCardKeyIsAnsweredAsAPerFieldRefusal() throws Exception {
        this.mockMvc.perform(post(LOOKUP_ROUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"40001234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cardNumber"))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.NOT_OK.name()));

        this.mockMvc.perform(post(LOOKUP_ROUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"40001234567890AB\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cardNumber"));

        verify(reads, never()).resolveCardCrossReference(anyString());
        verifyNoInteractions(reads);
    }

    /**
     * The absent-field marker follows the blank state alone and is never a published value.
     *
     * <p>Purpose: the reference distinguishes a field that was refused from a field that was never filled
     * in, moving a literal marker into the latter and only into the latter. This case pins that the
     * migrated state carries the same distinction and that an omitted card number is reported as absent
     * rather than as refused.</p>
     *
     * <p>Assumptions: the marker is PRESENTATIONAL and is never a domain value. It exists so a screen can
     * draw an empty control differently from a wrong one; nothing downstream may read it as content, which
     * is why the state rather than the marker is what the refusal document publishes.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the absent-field marker follows the blank state alone")
    void theAbsentFieldMarkerFollowsTheBlankStateAlone() throws Exception {
        assertThat(FieldValidationFlag.BLANK.requiresBlankMarker())
                .as("an unfilled field draws the marker the reference moves into it")
                .isTrue();
        assertThat(FieldValidationFlag.BLANK.screenMarker())
                .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER);
        assertThat(FieldValidationFlag.NOT_OK.requiresBlankMarker())
                .as("a refused value is not an empty control and draws no marker")
                .isFalse();
        assertThat(FieldValidationFlag.NOT_OK.screenMarker())
                .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);

        this.mockMvc.perform(post(LOOKUP_ROUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cardNumber"))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.BLANK.name()));

        verifyNoInteractions(reads);
    }

    /**
     * The single account-keyed read resolves a cross-reference row by account rather than by card.
     *
     * <p>Purpose: this operation and the walk below are the migrated form of an access path the reference
     * reaches through a SEPARATE index, and this case pins that the account identifier alone resolves a
     * row.</p>
     *
     * <p>Refactoring Rationale: the reference cannot answer this question from its base file. That file is
     * keyed on the card -- {@code RECORD KEY   IS FD-XREF-CARD-NUM} at L32 of
     * {@code app/cbl/CBACT03C.cbl} -- so reaching the same rows by account required a second, separately
     * declared access path: {@code app/csd/CARDDEMO.CSD} defines the base cluster at L37 with
     * {@code DESCRIPTION(CARD TO ACCOUNT XREF)} at L38 over
     * {@code DSNAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS)} at L39, and defines a second file resource at L63
     * whose own {@code DESCRIPTION(ALTERNATE INDEX TO CCXREF VIA ACCOUNT KEY)} at L64 states its purpose
     * over {@code DSNAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH)} at L65. The Java implements that same
     * access path as a real secondary index on the account column rather than emulating it in application
     * code, so no access path is lost and none is reconstructed by scanning; the divergence is a change of
     * MECHANISM only and is documented as such. The reference file itself is not altered in any way.</p>
     *
     * <p>Assumptions: the ordering and the cost of that access path depend on the non-unique index
     * {@code idx_card_xref_account_id}, which this module's {@code db/migration/V1__account.sql} owns and
     * creates on the account column of the cross-reference table -- naming the owner here so a reader knows
     * where the guarantee comes from rather than assuming the query optimiser will find one. This case does
     * NOT verify that index and cannot: no database participates in a dispatcher-level assertion. What it
     * verifies is the controller's observable contract, that an account identifier is the whole of the key
     * this operation accepts and that it is handed to the read path unchanged.</p>
     *
     * @throws Exception if the request cannot be performed or the response body cannot be read
     */
    @Test
    @DisplayName("the single account-keyed read resolves a row by account rather than by card")
    void theSingleAccountKeyedReadResolvesByAccount() throws Exception {
        when(reads.resolveCardCrossReferenceByAccount(ACCOUNT_ID))
                .thenReturn(new CardXrefView(ACCOUNT_ID, CUSTOMER_ID));

        String body = bodyOf(post(ACCOUNT_LOOKUP_ROUTE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ACCOUNT_BODY));

        assertThat(body)
                .as("a row reached by account publishes both identifiers and no card digits")
                .contains(String.valueOf(ACCOUNT_ID))
                .doesNotContain(CARD_KEY)
                .doesNotContain(WITHHELD_CARD_PREFIX);

        verify(reads).resolveCardCrossReferenceByAccount(ACCOUNT_ID);
        verifyNoMoreInteractions(reads);
    }

    /**
     * The account-keyed walk publishes every row it was given, in key order, with a masked card number.
     *
     * <p>Purpose: one account may cross-reference several cards, so the account-keyed access path answers
     * a set rather than a row and the walk is what publishes it. This case pins four facts at once: the
     * rows arrive in the order the read path produced them, each card number is reduced to its trailing
     * digits, both boundaries are published as opaque tokens, and a further page is reported while the row
     * that revealed it is withheld.</p>
     *
     * <p>Assumptions: the row order is asserted EXACTLY and is the read path's, not this class's. The walk
     * orders by the cross-reference key, which is the card number per L32 of
     * {@code app/cbl/CBACT03C.cbl}, so a handler that re-sorted or de-duplicated rows would change what a
     * caller stepping through pages sees; asserting a set rather than a sequence would not detect it.</p>
     *
     * <p>Assumptions: the published card number is reduced to its trailing digits, and the reduction is an
     * invariant of the mapper upstream of this controller rather than anything the handler does -- the
     * handler never receives an unmasked value at all. What this case verifies is therefore the observable
     * output and not the mechanism, and the mechanism is asserted where it lives, in the mapper's own
     * tests.</p>
     *
     * <p>Assumptions: a boundary is asserted to disclose NO key column. The ordering key on this surface IS
     * the primary account number, so a boundary built from the key rather than sealed would publish the
     * very value every other assertion here withholds.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the account-keyed walk publishes every row in key order with a masked card number")
    void theAccountKeyedWalkPublishesEveryRowInKeyOrder() throws Exception {
        String leading = sealedPosition("opening");
        String trailing = sealedPosition("closing");
        List<CardXrefResponse> rows = pageOf(CARD_KEY, "4000123456789028", "4000123456789036");

        when(reads.listCardCrossReferences(eq(ACCOUNT_ID), isNull(), isNull(), eq(SUBJECT)))
                .thenReturn(PageResponse.ofRows(rows, leading, trailing, true, false));

        String body = this.mockMvc.perform(walk().content(ACCOUNT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(PAGE_WIDTH))
                .andExpect(jsonPath("$.items[0].cardNumberMasked").value(MASKED_CARD_KEY))
                .andExpect(jsonPath("$.items[1].cardNumberMasked").value(MASK_MARKER + "9028"))
                .andExpect(jsonPath("$.items[2].cardNumberMasked").value(MASK_MARKER + "9036"))
                .andExpect(jsonPath("$.items[0].customerId").value(CUSTOMER_KEY))
                .andExpect(jsonPath("$.items[0].accountId").value(ACCOUNT_KEY))
                .andExpect(jsonPath("$.firstKey").value(leading))
                .andExpect(jsonPath("$.lastKey").value(trailing))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.hasPrevious").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // WHY : Assumptions: the WHOLE payload is asserted clean, not only the member the reduction was
        //       applied to. A member-level assertion passes against a body that also carried the complete
        //       key somewhere else -- inside a boundary token, or as a second member a serialiser added --
        //       and this surface's ordering key IS the primary account number, so that is exactly the
        //       payload where an unreduced copy could travel unnoticed.
        assertThat(body)
                .as("no payload of this surface may carry an unreduced primary account number")
                .doesNotContain(CARD_KEY)
                .doesNotContain(WITHHELD_CARD_PREFIX);

        assertThat(leading)
                .as("a published boundary must disclose no key column, and a card number least of all")
                .doesNotContain(CARD_KEY)
                .doesNotContain(WITHHELD_CARD_PREFIX);
        assertThat(trailing)
                .as("the trailing boundary is under the same obligation as the leading one")
                .doesNotContain(CARD_KEY)
                .doesNotContain(WITHHELD_CARD_PREFIX);
    }

    /**
     * An account with no cross-referenced card answers an empty page and names neither boundary.
     *
     * <p>Purpose: an account holding no card is an ordinary result rather than a missing resource, so three
     * outcomes are wrong and are excluded together: a 404, which would report an existing account as
     * absent; a 500, which would report an ordinary result as a defect; and a page naming a boundary a
     * caller could step from, which would invite a request for a page that cannot exist.</p>
     *
     * <p>Assumptions: both boundaries are asserted NULL rather than empty text. The envelope carries them
     * as opaque tokens and a caller decides whether to offer a step by their presence, so an empty string
     * would be a token-shaped value that is not a token and the guard on the envelope's own constructor
     * would then be the only thing standing between a caller and a request it cannot make.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an account with no cross-referenced card answers an empty page and neither boundary")
    void anAccountWithNoCrossReferencedCardAnswersAnEmptyPage() throws Exception {
        when(reads.listCardCrossReferences(eq(ACCOUNT_ID), isNull(), isNull(), eq(SUBJECT)))
                .thenReturn(PageResponse.empty());

        this.mockMvc.perform(walk().content(ACCOUNT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.firstKey").value(nullValue()))
                .andExpect(jsonPath("$.lastKey").value(nullValue()))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.hasPrevious").value(false));
    }

    /**
     * The last page reports no further page and still names both boundaries.
     *
     * <p>Purpose: the two indicators and the two boundaries are independent, and a handler that cleared the
     * boundaries once the rows ran out would strip a caller of its way back. This case pins that a page
     * that ends the walk forward still carries the positions a caller reached it at.</p>
     *
     * <p>Assumptions: a further page is reported by reading one row beyond the window rather than by
     * counting the rows that match, and the absence of a further row is therefore the whole of what a
     * closing page knows. That is the reference's own arrangement: its browse discovers whether another
     * screen exists by finding one more record than the screen holds, and it holds no total of any kind
     * anywhere.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the last page reports no further page and still names both boundaries")
    void theLastPageReportsNoFurtherPageAndStillNamesBothBoundaries() throws Exception {
        String leading = sealedPosition("final-opening");
        String trailing = sealedPosition("final-closing");

        when(reads.listCardCrossReferences(eq(ACCOUNT_ID), anyString(), eq(STEP_FORWARD), eq(SUBJECT)))
                .thenReturn(PageResponse.ofRows(pageOf(CARD_KEY), leading, trailing, false, true));

        this.mockMvc.perform(walk()
                        .param("cursor", sealedPosition("carried"))
                        .param("direction", STEP_FORWARD)
                        .content(ACCOUNT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.hasPrevious").value(true))
                .andExpect(jsonPath("$.firstKey").value(leading))
                .andExpect(jsonPath("$.lastKey").value(trailing));
    }

    /**
     * Stepping forward resumes from the trailing boundary and shows no row twice.
     *
     * <p>Purpose: this is the walk's whole reason for publishing boundaries, so it is asserted end to end:
     * the first request is made without a position, the boundary the answer names is carried into a second
     * request unchanged, and the rows the second answer carries are disjoint from the first's.</p>
     *
     * <p>Alternatives Considered: positioning a page by its ordinal rather than by its key, which is the
     * more familiar arrangement and is rejected here. Under concurrent insertion an ordinal names a
     * different row from one request to the next, so a caller stepping through pages sees some rows twice
     * and never sees others -- a change in observable behaviour that a key-ordered walk does not have. The
     * reference is already key-ordered: its browse carries the key it last read and resumes strictly beyond
     * it, so the migrated form preserves the behaviour rather than trading it for a more familiar shape.
     * The cost accepted is that a caller cannot jump to an arbitrary page, which no screen of the reference
     * could do either.</p>
     *
     * <p>Assumptions: the carried boundary is verified to reach the read path UNCHANGED. It is an opaque
     * sealed token, so a handler that trimmed, re-encoded or defaulted it would produce a value the read
     * path could not open, and the refusal would then be attributed to the caller rather than to the
     * handler.</p>
     *
     * @throws Exception if a request cannot be performed
     */
    @Test
    @DisplayName("stepping forward resumes from the trailing boundary and shows no row twice")
    void steppingForwardResumesFromTheTrailingBoundary() throws Exception {
        String leading = sealedPosition("first-opening");
        String trailing = sealedPosition("first-closing");
        when(reads.listCardCrossReferences(eq(ACCOUNT_ID), isNull(), isNull(), eq(SUBJECT)))
                .thenReturn(PageResponse.ofRows(
                        pageOf(CARD_KEY, "4000123456789028", "4000123456789036"),
                        leading, trailing, true, false));

        String carried = this.json.readValue(
                        bodyOf(walk().content(ACCOUNT_BODY)), Map.class)
                .get("lastKey")
                .toString();

        assertThat(carried)
                .as("the boundary a caller steps from is the one the answer published")
                .isEqualTo(trailing);

        when(reads.listCardCrossReferences(
                eq(ACCOUNT_ID), eq(carried), eq(STEP_FORWARD), eq(SUBJECT)))
                .thenReturn(PageResponse.ofRows(
                        pageOf("4000123456789044", "4000123456789051", "4000123456789069"),
                        sealedPosition("second-opening"), sealedPosition("second-closing"),
                        false, true));

        this.mockMvc.perform(walk()
                        .param("cursor", carried)
                        .param("direction", STEP_FORWARD)
                        .content(ACCOUNT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(PAGE_WIDTH))
                .andExpect(jsonPath("$.items[0].cardNumberMasked").value(MASK_MARKER + "9044"))
                .andExpect(jsonPath("$.items[2].cardNumberMasked").value(MASK_MARKER + "9069"))
                .andExpect(jsonPath("$.hasNext").value(false));

        verify(reads).listCardCrossReferences(eq(ACCOUNT_ID), eq(trailing), eq(STEP_FORWARD),
                eq(SUBJECT));
    }

    /**
     * Stepping backward carries the retreat word and the leading boundary to the read path unchanged.
     *
     * <p>Purpose: the walk has two directions and the backward one is the analogue of the reference's
     * read-previous browse. This case pins that the retreat word and the leading boundary both reach the
     * read path as given, since a handler that silently ignored the direction would answer the page a
     * caller had just left and nothing in the response would say so.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("stepping backward carries the retreat word and the leading boundary unchanged")
    void steppingBackwardCarriesTheRetreatWordUnchanged() throws Exception {
        String carried = sealedPosition("retreat-from");
        when(reads.listCardCrossReferences(
                eq(ACCOUNT_ID), eq(carried), eq(STEP_BACKWARD), eq(SUBJECT)))
                .thenReturn(PageResponse.ofRows(pageOf(CARD_KEY),
                        sealedPosition("prior-opening"), sealedPosition("prior-closing"), true, false));

        this.mockMvc.perform(walk()
                        .param("cursor", carried)
                        .param("direction", STEP_BACKWARD)
                        .content(ACCOUNT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(true));

        verify(reads).listCardCrossReferences(eq(ACCOUNT_ID), eq(carried), eq(STEP_BACKWARD),
                eq(SUBJECT));
    }

    /**
     * The published envelope declares two boundaries, two availability indicators and the rows, and nothing
     * else.
     *
     * <p>Purpose: what the envelope does NOT declare is the substance of this case. A member reporting how
     * many rows exist in total, or which ordinal position a page occupies, would require the read path to
     * count rows it is not reading and would give a caller a way to address a page that the key-ordered walk
     * cannot honour. The member set is therefore pinned exactly, from the declaration and from a serialised
     * body, so neither route can acquire one quietly.</p>
     *
     * <p>Assumptions: the member set is read from the record DECLARATION as well as from a body, because a
     * body alone cannot prove a member is absent once null suppression is in play, and a declaration alone
     * cannot prove the serialiser does not add one. Both together answer the question.</p>
     *
     * @throws Exception if the request cannot be performed or the response body cannot be read
     */
    @Test
    @DisplayName("the published envelope declares the rows, both boundaries and both indicators only")
    void thePublishedEnvelopeDeclaresNothingBeyondRowsBoundariesAndIndicators() throws Exception {
        assertThat(componentNamesOf(PageResponse.class))
                .as("the envelope declares the rows, both boundaries and both availability indicators")
                .hasSize(ENVELOPE_MEMBER_COUNT)
                .containsExactly("items", "firstKey", "lastKey", "hasNext", "hasPrevious");

        when(reads.listCardCrossReferences(eq(ACCOUNT_ID), isNull(), isNull(), eq(SUBJECT)))
                .thenReturn(PageResponse.ofRows(pageOf(CARD_KEY),
                        sealedPosition("only-opening"), sealedPosition("only-closing"), false, false));

        assertThat(this.json.readValue(bodyOf(walk().content(ACCOUNT_BODY)), Map.class))
                .as("a serialised envelope must carry the declared members and no further one")
                .containsOnlyKeys("items", "firstKey", "lastKey", "hasNext", "hasPrevious");
    }

    /**
     * The walk admits exactly two step words and refuses any other as a per-field refusal.
     *
     * <p>Purpose: the walk has two directions and no third, so a value outside that pair is a caller error
     * and must be answered as one. This case drives an unrecognised word and asserts the refusal is keyed to
     * the parameter that carried it, with the read path never entered -- a handler that fell back to a
     * default direction would answer a page the caller did not ask for and would say nothing about it.</p>
     *
     * <p>Assumptions: the two admitted words are the migrated form of the reference's forward and backward
     * browse, so the pair is closed by the reference's own vocabulary rather than by preference. Both are
     * asserted admitted as well as the third refused, because a refusal case alone would also pass against a
     * handler that refused everything.</p>
     *
     * @throws Exception if a request cannot be performed
     */
    @Test
    @DisplayName("the walk admits exactly two step words and refuses any other")
    void theWalkAdmitsExactlyTwoStepWords() throws Exception {
        when(reads.listCardCrossReferences(anyLong(), any(), any(), anyString()))
                .thenReturn(PageResponse.empty());

        for (String admitted : List.of(STEP_FORWARD, STEP_BACKWARD)) {
            this.mockMvc.perform(walk()
                            .param("direction", admitted)
                            .content(ACCOUNT_BODY))
                    .andExpect(status().isOk());
        }

        reset(reads);

        this.mockMvc.perform(walk()
                        .param("direction", "sideways")
                        .content(ACCOUNT_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("direction"));

        verifyNoInteractions(reads);
    }

    /**
     * A boundary longer than the accepted ceiling is refused before the read path is entered.
     *
     * <p>Purpose: a boundary arrives from a caller and is therefore untrusted input of a bounded length. The
     * ceiling is {@link CursorToken#MAX_TOKEN_LENGTH}, and a value beyond it is refused at the edge rather
     * than carried inward, so this case pins the refusal and that it is keyed to the parameter that carried
     * it.</p>
     *
     * <p>Trade-offs: refusing at the edge and refusing inside the read path are both correct and both
     * exist, and the duplication is accepted deliberately. The edge has a caller to inform and refuses on
     * LENGTH alone, which needs no key material; the read path refuses on the seal, which needs the key and
     * is asserted in the sibling service package. Collapsing them into one check would mean either
     * admitting an unbounded value into the read path or moving key material to the edge.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a boundary longer than the accepted ceiling is refused at the edge")
    void anOversizedBoundaryIsRefusedAtTheEdge() throws Exception {
        String oversized = "p".repeat(CursorToken.MAX_TOKEN_LENGTH + 1);

        this.mockMvc.perform(walk()
                        .param("cursor", oversized)
                        .content(ACCOUNT_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cursor"));

        verifyNoInteractions(reads);
    }

    /**
     * The cross-reference surface declares no write mapping and publishes no revision to precondition on.
     *
     * <p>Purpose: this case exists to record an ABSENCE explicitly, so that a reader looking for a
     * concurrency-conflict assertion here finds the reason it is not here rather than concluding the
     * coverage was forgotten. Every operation this controller declares is a read, so there is no write for
     * two callers to race and no conflict status to assert.</p>
     *
     * <p>Assumptions: the absence is structural at three levels that agree with each other. The controller
     * declares no updating mapping at all, which is what the reflection below asserts. Its projection
     * publishes no revision member, so a caller has nothing to precondition a submission on. And the stored
     * cross-reference row is the ONE entity of this bounded context that carries no revision column --
     * unlike the account, the customer and the card -- precisely because this surface has no update path.
     * The conflict contract itself, whose sentence the reference declares verbatim at L521 to L522 of
     * {@code app/cbl/COACTUPC.cbl} as the value of a condition on the 75-character return field declared at
     * L479 of that same file, belongs to the account update surface and is asserted in
     * {@code AccountControllerTest}. Nothing here re-declares it, and nothing here declares any local
     * mapping from a raised type to a status: the shared advice owns every one of those mappings.</p>
     */
    @Test
    @DisplayName("the cross-reference surface declares no write mapping and no revision member")
    void theCrossReferenceSurfaceDeclaresNoWriteMapping() {
        for (Method declared : CardXrefController.class.getDeclaredMethods()) {
            assertThat(declared.isAnnotationPresent(PutMapping.class))
                    .as("%s must not declare a replacing mapping on a read-only surface",
                            declared.getName())
                    .isFalse();
            assertThat(declared.isAnnotationPresent(PatchMapping.class))
                    .as("%s must not declare an amending mapping on a read-only surface",
                            declared.getName())
                    .isFalse();
            assertThat(declared.isAnnotationPresent(DeleteMapping.class))
                    .as("%s must not declare a removing mapping on a read-only surface",
                            declared.getName())
                    .isFalse();
        }

        // WHY : Assumptions: the three published addresses are asserted to be reached by the SAME verb,
        //       because a surface where every operation reads but one of them is mounted on a different
        //       verb would leave a caller guessing which. They carry their keys in a body rather than in
        //       an address so that a primary account number never reaches a request line or an access log,
        //       which is why a read is mounted on a submitting verb here at all.
        long mounted = Arrays.stream(CardXrefController.class.getDeclaredMethods())
                .filter(declared -> declared.isAnnotationPresent(PostMapping.class))
                .count();
        assertThat(mounted)
                .as("all three published operations are mounted on the one verb")
                .isEqualTo(3L);

        assertThat(componentNamesOf(CardXrefResponse.class))
                .as("a read-only projection publishes no revision for a caller to precondition on")
                .noneMatch(member -> {
                    String lowered = member.toLowerCase(Locale.ROOT);
                    return lowered.contains("version") || lowered.contains("revision")
                            || lowered.contains("etag");
                });
    }

    /**
     * The rendered refusal sentence fits the program-side channel the reference composes it in.
     *
     * <p>Purpose: a refusal sentence has to fit somewhere, and the reference declares exactly where. This
     * case pins that the sentence the shared advice renders on this surface fits the aggregate channel, and
     * pins which pair of widths this migration treats as the contract.</p>
     *
     * <p>Trade-offs: the PROGRAM-side widths are the contract and the screen-side widths are not, and the
     * choice costs something that is accepted. The reference composes its messages into
     * {@code 05  WS-INFO-MSG  PIC X(40).} at L110 and {@code 05  WS-RETURN-MSG  PIC X(75).} at L117 of
     * {@code app/cbl/COACTVWC.cbl}, alongside {@code 05  WS-LONG-MSG  PIC X(500).} at L109, and the same
     * 75-character aggregate width is the only one a search of {@code app/cpy/} for that width returns, in
     * the two commarea fields {@code CCARD-ERROR-MSG} at L28 and {@code CCARD-RETURN-MSG} at L29 of
     * {@code app/cpy/CVCRD01Y.cpy}. The symbolic maps are wider -- {@code 02  INFOMSGI  PIC X(45).} and
     * {@code 02  ERRMSGI  PIC X(78).} at L234 and L240 of {@code app/cpy-bms/COACTVW.CPY} and at L318 and
     * L324 of {@code app/cpy-bms/COACTUP.CPY} -- and taking those instead would admit sentences the
     * reference's own program could not have composed, because a value moved into a narrower field is
     * truncated at the move. Choosing the narrower pair therefore gives up five and three characters of
     * room a screen would have rendered, in exchange for never publishing a sentence the reference could not
     * have produced. Neither width comes from {@code app/cpy/CSMSG01Y.cpy}, which is a 50-character
     * two-message regime declaring {@code CCDA-MSG-THANK-YOU PIC X(50)} across L18 and L19 and
     * {@code CCDA-MSG-INVALID-KEY PIC X(50)} across L20 and L21, and is a different contract
     * altogether.</p>
     *
     * @throws Exception if the request cannot be performed or the response body cannot be read
     */
    @Test
    @DisplayName("the rendered refusal sentence fits the program-side aggregate channel")
    void theRenderedRefusalSentenceFitsTheProgramSideChannel() throws Exception {
        assertThat(AGGREGATE_CHANNEL_WIDTH)
                .as("the shared kernel renders into the same aggregate channel the reference declares")
                .isEqualTo(ApiError.MESSAGE_RENDERING_WIDTH);
        assertThat(INFORMATION_CHANNEL_WIDTH)
                .as("the informational channel is the narrower of the two program-side widths")
                .isLessThan(AGGREGATE_CHANNEL_WIDTH);
        assertThat(AGGREGATE_CHANNEL_WIDTH)
                .as("the program-side aggregate width is narrower than the screen field's")
                .isLessThan(SCREEN_AGGREGATE_WIDTH);
        assertThat(INFORMATION_CHANNEL_WIDTH)
                .as("the program-side informational width is narrower than the screen field's")
                .isLessThan(SCREEN_INFORMATION_WIDTH);

        when(reads.resolveCardCrossReferenceByAccount(ACCOUNT_ID))
                .thenThrow(new NoSuchElementException("the account has no cross-referenced card"));

        String rendered = this.json.readValue(this.mockMvc.perform(post(ACCOUNT_LOOKUP_ROUTE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(ACCOUNT_BODY))
                        .andExpect(status().isNotFound())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(), Map.class)
                .get("message")
                .toString();

        assertThat(rendered)
                .as("a rendered sentence must fit the channel the reference composes it in")
                .isNotBlank()
                .hasSizeLessThanOrEqualTo(AGGREGATE_CHANNEL_WIDTH);
    }

    /**
     * An unauthenticated cross-reference read is refused before the read path is entered.
     *
     * <p>Purpose: this is one of the two cases that carries the DEPLOYED filter chain, so the refusal
     * asserted is produced by the rule the service enforces rather than by a substitute. The read path is
     * asserted untouched, because a refusal that still reached it would mean the guard sat behind the handler
     * rather than in front of it.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an unauthenticated cross-reference read is refused and never reaches the read path")
    void anUnauthenticatedCrossReferenceReadIsRefused() throws Exception {
        guarded.perform(post(LOOKUP_ROUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOOKUP_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reads);
    }

    /**
     * Neither business group authority reaches the cross-reference subtree on the end-user chain.
     *
     * <p>Purpose: {@code SecurityConfig} governs the end-user surface and refuses this subtree outright, and
     * this case pins that rule rather than a hoped-for one. The two authorities presented are the two the
     * configuration itself publishes as one list, so the case cannot pass by naming a group the chain never
     * checks; both are exercised because a rule that had opened to one of them would still refuse a case
     * presenting only the other.</p>
     *
     * <p>Assumptions: the pair descends from the reference's own two-value domain,
     * {@code 10 CDEMO-USER-TYPE               PIC X(01).} at L26 of {@code app/cpy/COCOM01Y.cpy}, whose
     * conditions at L27 and L28 admit an administrator and an ordinary user and nothing else. Nothing here
     * widens that domain, and the refusal is the correct outcome rather than a gap: no screen of the
     * reference reads a cross-reference row directly -- the addresses exist for the authorization and
     * transaction contexts to resolve an account with -- so admitting an end-user group on this subtree would
     * ADD a capability instead of preserving one. The scope-bearing internal chain that does serve these
     * addresses is asserted where it is declared, in the sibling configuration test, and is deliberately not
     * re-asserted here. The translation from a group claim to an authority is likewise asserted once, in the
     * shared kernel that owns it.</p>
     *
     * @throws Exception if any request cannot be performed
     */
    @Test
    @DisplayName("neither business group authority reaches the cross-reference subtree")
    void neitherBusinessGroupAuthorityReachesTheCrossReferenceSubtree() throws Exception {
        for (String authority : SecurityConfig.BUSINESS_AUTHORITIES) {
            guarded.perform(post(LOOKUP_ROUTE)
                            .with(jwt().authorities(new SimpleGrantedAuthority(authority)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(LOOKUP_BODY))
                    .andExpect(status().isForbidden());
        }

        assertThat(SecurityConfig.BUSINESS_AUTHORITIES)
                .as("the published set is the reference's two-value user-type domain and no wider")
                .hasSize(2);
        verifyNoInteractions(reads);
    }

    /**
     * A caller-supplied user type or role changes nothing about either answer.
     *
     * <p>Purpose: identity reaches this service only as claims of a validated token, so a value a caller puts
     * in a request naming its own user type or role must be inert. This case sends both spellings on the
     * card-keyed read and on the walk, asserts each answer is byte-identical to the same request without
     * them, and asserts the read path was asked exactly the same thing.</p>
     *
     * <p>Refactoring Rationale: the reference was in a different position. It carried the user type in the
     * communication area the terminal echoed back, {@code 10 CDEMO-USER-TYPE               PIC X(01).} at L26
     * of {@code app/cpy/COCOM01Y.cpy} with its two conditions at L27 and L28, so a client was able to assert
     * its own type. The Java takes the distinction from a signed claim instead and the request cannot carry it
     * at all; that is a genuine narrowing of what a client can influence, and it is documented as a divergence
     * rather than presented as parity. The reference is not altered.</p>
     *
     * @throws Exception if any request cannot be performed or a response body cannot be read
     */
    @Test
    @DisplayName("a caller-supplied user type or role changes neither answer")
    void aCallerSuppliedUserTypeChangesNothing() throws Exception {
        when(reads.resolveCardCrossReference(CARD_KEY))
                .thenReturn(new CardXrefView(ACCOUNT_ID, CUSTOMER_ID));
        when(reads.listCardCrossReferences(eq(ACCOUNT_ID), isNull(), isNull(), eq(SUBJECT)))
                .thenReturn(PageResponse.empty());

        String plainRow = bodyOf(post(LOOKUP_ROUTE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(LOOKUP_BODY));
        String claimedRow = bodyOf(post(LOOKUP_ROUTE)
                .param("userType", "A")
                .param("role", "carddemo-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(LOOKUP_BODY));

        assertThat(claimedRow)
                .as("a claimed user type must leave the card-keyed answer byte-identical")
                .isEqualTo(plainRow);

        String plainPage = bodyOf(walk().content(ACCOUNT_BODY));
        String claimedPage = bodyOf(walk()
                .param("userType", "A")
                .param("role", "carddemo-admin")
                .content(ACCOUNT_BODY));

        assertThat(claimedPage)
                .as("a claimed user type must leave the walk's answer byte-identical")
                .isEqualTo(plainPage);

        // WHY : Assumptions: the read path is verified to have been asked the SAME question twice, because
        //       two identical bodies could also be produced by a handler that had branched on the claimed
        //       value and happened to reach the same answer.
        verify(reads, times(2)).resolveCardCrossReference(CARD_KEY);
        verify(reads, times(2)).listCardCrossReferences(eq(ACCOUNT_ID), isNull(), isNull(),
                eq(SUBJECT));
    }

    /**
     * Lists the declared member names of a record type, in declaration order.
     *
     * <p>Assumptions: the names are read from the record's own declaration rather than from a serialised
     * body, because a case that must prove a member is ABSENT cannot do so from a body -- an absent member
     * and a member serialised as null are indistinguishable once null suppression is in play. Reading the
     * declaration answers the question the assertions actually ask.</p>
     *
     * @param recordType the record type to describe; must be a record and must not be {@code null}
     * @return the declared member names in declaration order, never {@code null}
     */
    private static List<String> componentNamesOf(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Reports whether a member name would name a card verification value under any usual spelling.
     *
     * <p>Assumptions: several spellings are tested rather than one, because the assertion this serves is an
     * absence and an absence proved against a single spelling proves very little. The three tested are the
     * abbreviation and the two written forms a schema or a projection would plausibly use.</p>
     *
     * @param componentName the declared member name to examine; must not be {@code null}
     * @return {@code true} when the name would name a card verification value, {@code false} otherwise
     */
    private static boolean namesACardVerificationValue(String componentName) {
        String lowered = componentName.toLowerCase(Locale.ROOT);
        return lowered.contains("cvv") || lowered.contains("verification")
                || lowered.contains("securitycode");
    }

    /**
     * Mints a boundary token of the shape the published envelope accepts.
     *
     * <p>Assumptions: the envelope's canonical constructor refuses a boundary that is not a sealed token,
     * because a boundary built from the key columns would publish those columns to a client -- and the key
     * column here is the primary account number. The token is therefore minted by a real sealer rather than
     * written as a literal, and its shape is asserted so a fixture cannot drift out of the accepted form and
     * begin failing for a reason unrelated to the case using it.</p>
     *
     * <p>Alternatives Considered: opening the token to read the position back, which no case here needs and
     * which would add cryptography to assertions that are about the envelope's members. The sealing itself is
     * asserted in the shared kernel's own tests and the binding discipline in the sibling service package.</p>
     *
     * @param boundaryLabel the label this boundary stands for, kept short and obviously synthetic; must not
     *     be {@code null}
     * @return a token satisfying the shape the envelope enforces, never {@code null}
     */
    private static String sealedPosition(String boundaryLabel) {
        String sealed = FIXTURE_SEALER.seal(FIXTURE_BINDING, boundaryLabel);
        assertThat(CursorToken.hasSealedShape(sealed))
                .as("a fixture boundary must satisfy the shape the envelope enforces")
                .isTrue();
        return sealed;
    }

    /**
     * Builds the cross-reference projection for one card number, both identifiers held constant.
     *
     * <p>Assumptions: the card number arrives ALREADY REDUCED to its trailing digits, matching the invariant
     * the mapper upholds upstream of the controller, so no fixture can suggest the handler performs the
     * reduction. Both identifiers are digit text at their declared widths -- nine for the customer per L6 and
     * eleven for the account per L7 of {@code app/cpy/CVACT03Y.cpy} -- and are held constant so that a page
     * assertion naming a row is unambiguous about which member identified it.</p>
     *
     * @param cardNumber the complete card number this row stands for, from which the published form is
     *     derived; must be at least {@link #DISCLOSED_DIGIT_COUNT} characters and must not be {@code null}
     * @return the projection of one cross-reference row, never {@code null}
     */
    private static CardXrefResponse crossReference(String cardNumber) {
        String disclosed = cardNumber.substring(cardNumber.length() - DISCLOSED_DIGIT_COUNT);
        return new CardXrefResponse(MASK_MARKER + disclosed, CUSTOMER_KEY, ACCOUNT_KEY);
    }

    /**
     * Builds one page's worth of cross-reference projections, one per card number supplied.
     *
     * <p>Assumptions: the rows are built in the order given and the order is the caller's, because the walk
     * publishes rows in ascending key order and a helper that sorted them would hide a handler that did
     * not.</p>
     *
     * @param cardNumbers the complete card numbers the page stands for, in the order the walk publishes
     *     them; must not be {@code null} and must contain no {@code null} element
     * @return the rows of one page, unmodifiable, never {@code null}
     */
    private static List<CardXrefResponse> pageOf(String... cardNumbers) {
        List<CardXrefResponse> rows = new ArrayList<>(cardNumbers.length);
        for (String cardNumber : cardNumbers) {
            rows.add(crossReference(cardNumber));
        }
        return List.copyOf(rows);
    }

    /**
     * Begins a request on the walk, addressed and carrying the validated caller the boundaries are sealed for.
     *
     * <p>Assumptions: the caller is supplied because the walk seals its boundaries FOR a subject, so a
     * request without one could not be answered at all and the failure would read as a defect in the handler.
     * The body is left to each case, since the cases that vary the parameters all send the same one.</p>
     *
     * @return a request builder for the walk, ready for a body and any parameters, never {@code null}
     */
    private static MockHttpServletRequestBuilder walk() {
        return post(WALK_ROUTE)
                .principal(() -> SUBJECT)
                .contentType(MediaType.APPLICATION_JSON);
    }

    /**
     * Performs a request on the unguarded dispatcher and returns the body it answered with.
     *
     * <p>Assumptions: the status is asserted successful inside this helper rather than by each caller,
     * because every case that compares two bodies depends on both requests having been answered; comparing
     * two refusal bodies would otherwise pass while proving nothing about the outcome.</p>
     *
     * @param request the request to perform, already addressed and parameterised; must not be {@code null}
     * @return the response body as text, never {@code null}
     * @throws Exception if the request cannot be performed or the response body cannot be read
     */
    private String bodyOf(MockHttpServletRequestBuilder request) throws Exception {
        return this.mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /**
     * Assembles the security-enabled context whose dispatcher answers this class's authority cases.
     *
     * <p>Assumptions: every participant in the authorization decision is the DEPLOYED one -- the rules and
     * the refusal renderers come from {@code new SecurityConfig().filterChain(...)}, and the
     * token-to-authentication translation from the same class's own factory, handed the two group names the
     * configuration itself publishes. Passing that list rather than two literals is what stops this wiring
     * and the rule it exercises drifting apart.</p>
     *
     * <p>Assumptions: the token DECODER is the one bean substituted, and the substitution is required rather
     * than convenient. The deployed factory for it resolves the issuer's provider document while the context
     * refreshes, so a context that created it could not start on a host with no route to the configured
     * issuer; the request post-processor the cases use mints an authentication directly and never consults a
     * decoder, so nothing asserted here depends on the substitute's behaviour.</p>
     *
     * <p>Trade-offs: the MVC infrastructure is enabled here rather than inherited from a started
     * application, so this context's message converters are the framework's defaults. No case driven through
     * this dispatcher reads a body, which is why that costs nothing: both of them assert a status and that the
     * read path was never entered.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class GuardedSliceWiring {

        /**
         * Supplies the pinned clock the refusal renderers stamp their bodies from.
         *
         * @return a clock pinned to {@link CardXrefControllerTest#PINNED_INSTANT}, never {@code null}
         */
        @Bean
        Clock clock() {
            return Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC);
        }

        /**
         * Supplies the controller under assertion, over the same substituted read path.
         *
         * @return the controller, wired exactly as a deployment wires it, never {@code null}
         */
        @Bean
        CardXrefController cardXrefController() {
            return new CardXrefController(reads);
        }

        /**
         * Supplies the shared error advice so a refusal reaching the handler renders the deployed body.
         *
         * <p>Assumptions: registered explicitly because it lives outside this context's scan root and
         * reaches a deployment through the shared kernel's auto-configuration, which a context assembled by
         * hand does not apply.</p>
         *
         * @param clock the clock the advice stamps its bodies from; must not be {@code null}
         * @return the shared advice, never {@code null}
         */
        @Bean
        GlobalExceptionHandler globalExceptionHandler(Clock clock) {
            return new GlobalExceptionHandler(clock);
        }

        /**
         * Supplies the substituted token decoder the resource-server filter would resolve a token with.
         *
         * @return a substitute for the token decoder, with no stubbing applied, never {@code null}
         */
        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }

        /**
         * Supplies the deployed token-to-authentication translation, group names included.
         *
         * @return the converter the deployed configuration builds, never {@code null}
         */
        @Bean
        JwtAuthenticationConverter jwtAuthenticationConverter() {
            return new SecurityConfig().jwtAuthenticationConverter(
                    SecurityConfig.BUSINESS_AUTHORITIES.get(0),
                    SecurityConfig.BUSINESS_AUTHORITIES.get(1));
        }

        /**
         * Supplies the deployed filter chain, rules, refusal renderers and session policy included.
         *
         * @param http the chain builder this context contributes; must not be {@code null}
         * @param decoder the substituted token decoder; must not be {@code null}
         * @param converter the deployed token-to-authentication translation; must not be {@code null}
         * @param clock the clock the rendered refusal bodies read their instant from; must not be
         *     {@code null}
         * @return the chain the deployed configuration builds, never {@code null}
         * @throws Exception when the builder cannot assemble the chain, which it declares
         */
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder decoder,
                JwtAuthenticationConverter converter, Clock clock) throws Exception {
            return new SecurityConfig().filterChain(http, decoder, converter, clock);
        }
    }
}
