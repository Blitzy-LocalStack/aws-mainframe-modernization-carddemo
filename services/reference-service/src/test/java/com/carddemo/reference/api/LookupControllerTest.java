// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/api/LookupControllerTest.java
// -----------------------------------------------------------------------------
// Purpose:
//       HTTP-boundary cases over the three seeded address allow-lists that
//       AddressLookupController publishes. Four properties are pinned that no
//       other class in this reactor pins: the shape of the page envelope the
//       three browses answer with, the three lookup domain sizes surviving
//       serialisation intact, the classification arriving as a total and
//       disjoint two-value partition, and the authorisation posture of a read
//       measured at the dispatcher rather than at an authorization manager.
//       No golden master covers these paths, so these assertions are primary
//       evidence rather than a supplement.
//
// WHY (non-obvious design decisions):
//   1.  Assumptions: this file is the sixth test class in a directory whose
//       charter measured five. That charter carries NO counted directory
//       marker and declares no case count, so common-lib's
//       PackageCharterInventoryTest does not hold it to this directory and
//       nothing here falsifies a machine-checked claim. Its prose figure goes
//       stale, and its own closing paragraph nominates itself as the thing to
//       re-measure when a class is added; the figure that IS machine-checked
//       lives in this module's README and moves with this file.
//   2.  Refactoring Rationale: the projection that specified this file
//       described a LookupController reading three repositories directly and
//       answering the state browse with an unpaged array. Neither exists. The
//       mounted controller is AddressLookupController, it holds exactly one
//       collaborator, and all three browses answer a page envelope -- which is
//       also what openapi/reference-api.yaml declares, at UsPhoneAreaCodePage,
//       UsStatePage and UsStateZipPrefixPage. Every departure is named beside
//       the assertion it changes, so a reader arriving with the projection has
//       a reason to prefer the tree over it rather than reversing this file
//       back to a contract the service does not publish.
//   3.  Trade-offs: this class assembles a web application context and installs
//       the deployed security chain, which costs one refresh per class where a
//       standalone dispatcher costs none. The cost is accepted because the
//       authorisation cases are the reason the file exists in the shape it
//       does: a dispatcher with no chain admits every request, so those cases
//       would pass against a read that had been guarded shut and would report
//       as green while proving nothing.
//   4.  Assumptions: the seven-line page bound of the transaction-type browse,
//       the referential refusal on a type delete, and the padded default
//       disclosure group are all owned elsewhere in this reactor and are
//       neither asserted nor contradicted here.
// =============================================================================
package com.carddemo.reference.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reference.config.SecurityConfig;
import com.carddemo.reference.dto.LookupPageRequest;
import com.carddemo.reference.dto.PageDirection;
import com.carddemo.reference.dto.PhoneAreaCodeResponse;
import com.carddemo.reference.dto.UsStateResponse;
import com.carddemo.reference.dto.UsStateZipPrefixResponse;
import com.carddemo.reference.service.AddressLookupService;
import jakarta.servlet.Filter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockServletContext;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the three seeded address browses and their three item reads through the deployed chain.
 *
 * <h2>What this class answers for</h2>
 *
 * <p>Purpose: the six operations of {@link AddressLookupController} are the only surface through
 * which the seeded area-code, state and state-and-postal-prefix allow-lists leave this context. This
 * class asserts what happens at that surface and nothing beneath it: the members the page envelope
 * carries, the three domain sizes reaching a client without loss, the classification arriving as a
 * total and disjoint two-value partition, the exact width each key round-trips at, the problem
 * document an absent code is refused with, and which caller is admitted to a read at all.</p>
 *
 * <p>Refactoring Rationale: asserting the domain sizes here rather than only where the rows are
 * stored is a decision about where an omission would present. These three tables are queried by a
 * DIFFERENT bounded context, whose address edit admits a supplied area code, state and postal prefix
 * only if this context serves it. That consumer does not own these rows, so a code missing from this
 * surface reaches it as a valid address being rejected, with no defect of its own to point at and
 * nothing in its own tree to read. The relationship is over the published contract and over the
 * schema, never through code, so no type of that context is named anywhere in this file.</p>
 *
 * <h2>How the dispatcher is assembled, and why not with the slice annotation</h2>
 *
 * <p>Alternatives Considered: the servlet slice annotation is the obvious wiring and is not on this
 * module's test class path. The framework moved it out of {@code spring-boot-test-autoconfigure} into
 * a separate artifact that {@code services/reference-service/pom.xml} does not declare and that the
 * framework test starter does not pull in; the annotation class is absent from the resolved
 * dependency set, so a class using it would not compile. Declaring the artifact would add a test
 * dependency to a sibling-owned build file, so the dispatcher is assembled here from types that are
 * on the path. That is also the mechanism this package's charter records for every other dispatcher
 * class in this directory.</p>
 *
 * <p>Assumptions: {@code com.carddemo.common.error.GlobalExceptionHandler} is registered
 * EXPLICITLY, as a bean of the context below, and every status this class asserts on a refused
 * request depends on that registration. It is the only advice in this migration and it lives outside
 * the {@code com.carddemo.reference} scan root, so nothing hands it to a dispatcher assembled here
 * automatically. Without it the same refusals would be rendered by a servlet-container default: a
 * status would still arrive, so a wrong mapping would read as green rather than failing. Removing
 * the bean and watching a status assertion fail is what shows the registration is load-bearing, and
 * no second advice is declared anywhere in this file to keep that proof unambiguous.</p>
 *
 * <p>Assumptions: the token decoder is the ONE participant replaced by a stand-in; every other
 * participant is the deployed one. The real {@link SecurityConfig#filterChain} is built, the real
 * {@link SecurityConfig#jwtAuthenticationConverter} converts, and the real {@link JwtRoleConverter}
 * derives authorities from the group claim, so the authority under assertion is the one a deployment
 * derives rather than a value a post-processor placed past the chain. The decoder is replaced because
 * the deployed one resolves its provider document while the context refreshes and this module's test
 * profile pins a deliberately unreachable issuer. A token is presented the way a caller presents one,
 * in an authorization header, and its value identifies nobody and is not a credential.</p>
 *
 * <p>Assumptions: the one collaborator behind the controller is
 * {@link AddressLookupService}, replaced by a stand-in. The controller reaches no store, so the
 * service is the whole of what sits beneath the handler; what that service does with an admitted
 * request -- which query member it chooses, how it bounds a walk, how it reverses a backward page --
 * is asserted in {@code com.carddemo.reference.service} and in
 * {@code com.carddemo.reference.repository}, and restating it here would give a later change two
 * places to update and one to forget.</p>
 *
 * <h2>Positions are opaque, and only their shape is decidable here</h2>
 *
 * <p>Trade-offs: the two positions this class sends and receives satisfy the published sealed SHAPE
 * and carry no authentic seal, and no key material appears in this file. Shape is the whole of what
 * this boundary decides: the request constraint and the envelope both test shape without a key, while
 * authenticity is verified where a position is redeemed, one layer down, by the type that holds the
 * key. What the shaped values buy is that a position travels the round trip verbatim, which is the
 * property a client depends on and the one an accidental re-encoding would break.</p>
 *
 * <h2>Where this class stops</h2>
 *
 * <p>Trade-offs: the fixtures below are sized from the domain figures the copybook declares and are
 * not extracted from it. Extraction, and the identity of every individual code, is what
 * {@code com.carddemo.reference.repository} owns against real rows; duplicating it here would let one
 * property be satisfied once while reporting as satisfied twice. What this file adds is the half that
 * package cannot see: that a full domain crosses the boundary without being truncated, capped or
 * re-paged, and that its classification survives as a partition. The two are complementary and
 * neither contradicts the other.</p>
 *
 * <p>Assumptions: no case here restates a parameter refusal. Malformed and over-long positions on all
 * three browses, a classification outside the closed domain, both published direction spellings, a
 * key of the wrong width on each item route and an unseeded area code are all already driven in this
 * package, and the authority each route demands is separately asserted against the chain's own
 * installed authorization manager in {@code com.carddemo.reference.config}. This class adds the
 * envelope, the domain surface, the partition, the message band, the statelessness of the boundary,
 * and the authorisation posture measured on these three paths through a real controller.</p>
 *
 * <p>Assumptions: the four rationale labels above and below are written in one form only --
 * {@code Alternatives Considered:}, {@code Refactoring Rationale:}, {@code Assumptions:} and
 * {@code Trade-offs:} -- taken from the user rule's own lines 31 to 34: plural, unparenthesised,
 * colon-terminated, spelled with an ordinary hyphen-minus and carrying no emphasis markup. The
 * singular spellings that occur in this repository's reference-only configuration headers denote the
 * same four categories; that equivalence is declared in this sentence alone and the forms are never
 * mixed here. This file is plain seven-bit text throughout.</p>
 *
 * <p>Assumptions: of the content elements the user rule enumerates, only Purpose applies to a type
 * declaration, so parameters, return values and exceptions are inapplicable here rather than omitted;
 * each member below carries its own.</p>
 */
class LookupControllerTest {

    /**
     * The number of area codes the seeded allow-list admits.
     *
     * <p>Assumptions: the figure is the literal count of the master list declared at L30 of
     * {@code app/cpy/CSLKPCDY.cpy}, whose values run to L520, over a field declared {@code PIC XXX} at
     * L24 of that copybook. It is stated as a constant rather than written into an assertion so that
     * the two sub-list figures below can be arithmetically checked against it.</p>
     */
    private static final int SEEDED_AREA_CODES = 490;

    /**
     * The number of area codes belonging to the general-purpose sub-list.
     *
     * <p>Assumptions: the literal count of the sub-list declared at L521 of
     * {@code app/cpy/CSLKPCDY.cpy}, whose values run to L930. This is the sub-list the baseline's own
     * address edit admits, so narrowing or widening it changes which telephone numbers are accepted --
     * which is why its size is pinned here and not merely its existence.</p>
     */
    private static final int GENERAL_PURPOSE_AREA_CODES = 410;

    /**
     * The number of area codes belonging to the easily-recognisable sub-list.
     *
     * <p>Assumptions: the literal count of the sub-list declared at L931 of
     * {@code app/cpy/CSLKPCDY.cpy}, whose values run to L1010.</p>
     */
    private static final int EASILY_RECOGNISABLE_AREA_CODES = 80;

    /**
     * The number of state-and-postal-prefix pairs the seeded allow-list admits.
     *
     * <p>Assumptions: the literal count of the list declared at L1073 of
     * {@code app/cpy/CSLKPCDY.cpy}, whose values run to L1313, over a field declared {@code PIC X(4)}
     * at L1072 of that copybook.</p>
     */
    private static final int SEEDED_ZIP_PREFIXES = 240;

    /**
     * Every state code the seeded allow-list admits, transcribed in the copybook's own order.
     *
     * <p>Assumptions: this list is FIFTY-SIX entries and must not be narrowed to the fifty states.
     * It is transcribed from the list declared at L1013 of {@code app/cpy/CSLKPCDY.cpy}, whose values
     * run to L1069, over a field declared {@code PIC X(2)} at L1012. Beyond the fifty states it carries
     * the federal district and the five inhabited territories, and the baseline admits an address in
     * every one of them. The membership is enumerated rather than counted so that a narrowing cannot be
     * made to pass by editing a single number: removing a territory changes both this list and the size
     * assertion that reads it.</p>
     */
    private static final List<String> SEEDED_STATE_CODES = List.of(
            "AK", "AL", "AR", "AS", "AZ", "CA", "CO", "CT", "DC", "DE",
            "FL", "GA", "GU", "HI", "IA", "ID", "IL", "IN", "KS", "KY",
            "LA", "MA", "MD", "ME", "MI", "MN", "MO", "MP", "MS", "MT",
            "NC", "ND", "NE", "NH", "NJ", "NM", "NV", "NY", "OH", "OK",
            "OR", "PA", "PR", "RI", "SC", "SD", "TN", "TX", "UT", "VA",
            "VI", "VT", "WA", "WI", "WV", "WY");

    /**
     * The members of the state list that are not one of the fifty states.
     *
     * <p>Assumptions: the federal district and the five inhabited territories. They are named so that
     * the assertion which forbids narrowing the domain can state WHICH six entries a narrowing would
     * discard, rather than only that six would go missing.</p>
     */
    private static final List<String> DISTRICT_AND_TERRITORIES =
            List.of("DC", "AS", "GU", "MP", "PR", "VI");

    /** The lexically first state-and-postal-prefix pair, whose literal sits within L1073 to L1313. */
    private static final String FIRST_ZIP_PREFIX = "AA34";

    /** The lexically last state-and-postal-prefix pair, whose literal sits at L1313 of the copybook. */
    private static final String LAST_ZIP_PREFIX = "WY83";

    /** The exact width an area code occupies, from the {@code PIC XXX} field at L24 of the copybook. */
    private static final int AREA_CODE_WIDTH = 3;

    /** The exact width a state code occupies, from the {@code PIC X(2)} field at L1012. */
    private static final int STATE_CODE_WIDTH = 2;

    /** The exact width a state-and-postal-prefix pair occupies, from the {@code PIC X(4)} field at L1072. */
    private static final int ZIP_PREFIX_WIDTH = 4;

    /**
     * The four members a page envelope publishes, in the order the shared type declares them.
     *
     * <p>Assumptions: FOUR and not five. The shared type at
     * {@code services/common-lib/src/main/java/com/carddemo/common/web/PageResponse.java} is a record
     * of exactly these four components, and all three page schemas of
     * {@code openapi/reference-api.yaml} declare exactly these four as required properties with no
     * additional property admitted. Two nearby documents assert a fifth carrying backward
     * availability; no such component exists, so backward availability is INFERRED from the leading
     * position being present and is never read from an accessor. Naming the set here is what lets the
     * envelope assertion below be exhaustive rather than a list of members somebody remembered.</p>
     */
    private static final Set<String> ENVELOPE_MEMBERS =
            new LinkedHashSet<>(List.of("items", "firstKey", "lastKey", "hasNext"));

    /**
     * Member names an envelope must not publish.
     *
     * <p>Assumptions: the first is the backward-availability member the two stale documents describe,
     * and the rest are the members an offset-paged envelope would carry. A page ordinal, a page size
     * and a total are all absent by design: the browse is positioned by key, and the baseline keeps a
     * page ordinal on the terminal side rather than in the reply.</p>
     */
    private static final List<String> FORBIDDEN_ENVELOPE_MEMBERS =
            List.of("hasPrev", "hasPrevious", "pageSize", "size", "page", "pageNumber", "offset",
                    "totalElements", "totalPages", "total", "numberOfElements", "last", "first");

    /**
     * The components the paging request shape declares, which are the whole of what a caller may steer.
     *
     * <p>Assumptions: a position, a direction and a classification filter, and nothing else. The set is
     * asserted rather than described because it carries two separate guarantees at once: that the
     * browse is positioned by key, since no offset or page ordinal is expressible; and that the
     * pseudo-conversational re-entry discriminator of the baseline is GONE rather than renamed, since
     * no turn count, re-entry flag or resubmit marker is expressible either.</p>
     */
    private static final Set<String> PAGING_REQUEST_COMPONENTS =
            new LinkedHashSet<>(List.of("cursor", "direction", "codeClass"));

    /**
     * A position of the published sealed shape, standing for a page's leading boundary.
     *
     * <p>Assumptions: the value satisfies the shape expression the shared cursor type publishes -- the
     * version, a sixteen-character segment and a payload segment -- and carries no authentic seal. It
     * is accepted by the request constraint and by the envelope, both of which decide shape without a
     * key, and it is never opened because the service beneath the handler is a stand-in.</p>
     */
    private static final String LEADING_POSITION =
            CursorToken.VERSION + ".bGVhZGluZ3Bvc2l0.aGVhZC1vZi1wYWdl";

    /** A second position of the same shape, distinct from the leading one, standing for the trailing boundary. */
    private static final String TRAILING_POSITION =
            CursorToken.VERSION + ".dHJhaWxpbmdwb3Np.dGFpbC1vZi1wYWdl";

    /** The instant the advice and the refusal renderers stamp every document from, so a body is reproducible. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-15T00:00:00Z");

    /** The bearer value a caller presents; it identifies nobody and is not a credential. */
    private static final String PRESENTED_TOKEN = "presented-token-value";

    /** The subject the stand-in decoder puts in the token, which becomes the caller a position is sealed against. */
    private static final String CALLER_SUBJECT = "11111111-2222-3333-4444-555555555555";

    /** The query parameter carrying the paging position, read from the type that publishes the shape. */
    private static final String PARAM_CURSOR = "cursor";

    /** The query parameter carrying the paging direction, read from the enumeration that owns the name. */
    private static final String PARAM_DIRECTION = PageDirection.PARAMETER_NAME;

    /** The query parameter carrying the area-code classification filter. */
    private static final String PARAM_CODE_CLASS = "codeClass";

    /** The reader the envelope assertions inspect a rendered body with. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The context the deployed chain and the controller are built in, refreshed once for the class. */
    private static AnnotationConfigWebApplicationContext context;

    /** The entry point every request below is issued through, with the deployed chain in front of it. */
    private static MockMvc mockMvc;

    /** The stand-in decoder that answers for the bearer value a caller presents. */
    private static JwtDecoder jwtDecoder;

    /** The stand-in for the one collaborator the controller holds. */
    private static AddressLookupService lookups;

    /**
     * Refreshes the context once and installs the deployed chain in front of the dispatcher.
     *
     * <p>Assumptions: the chain is installed by name, as the bean the security configuration
     * contributes, rather than assembled here. A chain built by this class would be a chain this class
     * had authored, so an authorisation case would assert its own wiring and would keep passing if the
     * deployed rules changed underneath it.</p>
     */
    @BeforeAll
    static void refreshDispatcher() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(DispatcherWiring.class);
        context.refresh();

        jwtDecoder = context.getBean(JwtDecoder.class);
        lookups = context.getBean(AddressLookupService.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    /** Closes the context so the class leaves no refreshed application behind it. */
    @AfterAll
    static void closeDispatcher() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * Clears both stand-ins and grants the ordinary group, so each case starts from one known posture.
     *
     * <p>Assumptions: the ordinary group is granted by default rather than case by case. The read rule
     * admits it, so it is the posture under which every non-authorisation case below is meant to run;
     * granting it once also means a case added later cannot forget it and report an authorisation
     * refusal as an envelope or domain failure. The authorisation cases restate the grant themselves,
     * because for them it is the subject under test rather than a precondition.</p>
     */
    @BeforeEach
    void resetStandIns() {
        reset(jwtDecoder, lookups);
        grantGroups(JwtRoleConverter.USER_AUTHORITY);
    }

    /**
     * Stubs the decoder to answer for the presented value with a token carrying the named groups.
     *
     * @param grantedGroups the group names to place in the claim the authority converter reads; an
     *     empty argument list yields a validly decoded token that grants no authority at all
     */
    private static void grantGroups(String... grantedGroups) {
        Jwt token = Jwt.withTokenValue(PRESENTED_TOKEN)
                .header("alg", "none")
                .subject(CALLER_SUBJECT)
                .claim(JwtRoleConverter.GROUPS_CLAIM, List.of(grantedGroups))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(token);
    }

    /**
     * Builds a request to a path with the bearer value presented the way a caller presents it.
     *
     * @param path the published path to issue the request against
     * @return a request builder carrying the authorization header, ready for further parameters
     */
    private static MockHttpServletRequestBuilder authorisedGet(String path) {
        return get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + PRESENTED_TOKEN);
    }

    /**
     * Reads a rendered response body as a tree so its member names can be inspected exhaustively.
     *
     * <p>Assumptions: the body is parsed rather than matched with expressions because the envelope
     * assertions are EXHAUSTIVE -- they compare the whole member set for equality, which an expression
     * that probes for one member at a time cannot do, and it is an ADDED member that those assertions
     * exist to catch.</p>
     *
     * @param result the completed exchange whose body is to be read
     * @return the parsed body; never {@code null}
     * @throws UnsupportedEncodingException if the recorded response names a character encoding this
     *     platform cannot decode, which the servlet response accessor declares
     */
    private static JsonNode bodyOf(MvcResult result) throws UnsupportedEncodingException {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Collects the member names a rendered object body publishes, in document order.
     *
     * @param body the parsed body to inspect
     * @return the member names present, including those whose value is null; never {@code null}
     */
    private static Set<String> memberNamesOf(JsonNode body) {
        return new LinkedHashSet<>(body.propertyNames());
    }

    /**
     * Captures the paging shape the controller handed to the service on the most recent browse.
     *
     * @return the request shape the service received; never {@code null}
     */
    private static LookupPageRequest capturedStateRequest() {
        ArgumentCaptor<LookupPageRequest> captor = ArgumentCaptor.forClass(LookupPageRequest.class);
        verify(lookups).listStates(captor.capture(), anyString());
        return captor.getValue();
    }

    /**
     * Builds a page of state replies over the codes supplied, naming both of its boundaries.
     *
     * @param codes the state codes the page carries, in the order they are to be emitted
     * @param hasNext whether the page is to report a further page ahead of it
     * @return a page carrying one reply per code supplied; never {@code null}
     */
    private static PageResponse<UsStateResponse> statePage(List<String> codes, boolean hasNext) {
        List<UsStateResponse> rows = new ArrayList<>(codes.size());
        for (String code : codes) {
            rows.add(new UsStateResponse(code));
        }
        return PageResponse.ofRows(rows, LEADING_POSITION, TRAILING_POSITION, hasNext);
    }

    /**
     * Builds a page carrying the whole seeded area-code population, classified as the copybook splits it.
     *
     * <p>Assumptions: the population is composed as the two sub-lists rather than as one undifferentiated
     * run, and the two sizes are the copybook's own. Composing it this way is what lets one fixture carry
     * two properties: that the whole domain crosses the boundary, and that its classification is a total
     * partition whose two classes sum exactly to the master list.</p>
     *
     * @return a page of one reply per seeded area code, the general-purpose members first; never
     *     {@code null}
     */
    private static PageResponse<PhoneAreaCodeResponse> wholeAreaCodePage() {
        List<PhoneAreaCodeResponse> rows = new ArrayList<>(SEEDED_AREA_CODES);
        for (int index = 0; index < GENERAL_PURPOSE_AREA_CODES; index++) {
            rows.add(new PhoneAreaCodeResponse(distinctAreaCode(index),
                    PhoneAreaCodeResponse.CodeClass.GENERAL_PURPOSE));
        }
        for (int index = 0; index < EASILY_RECOGNISABLE_AREA_CODES; index++) {
            rows.add(new PhoneAreaCodeResponse(
                    distinctAreaCode(GENERAL_PURPOSE_AREA_CODES + index),
                    PhoneAreaCodeResponse.CodeClass.EASILY_RECOGNISABLE));
        }
        return PageResponse.ofRows(rows, LEADING_POSITION, TRAILING_POSITION, false);
    }

    /**
     * Renders a distinct three-digit area code for a position in the seeded population.
     *
     * <p>Assumptions: the identity of each individual code is not what this file asserts, so the value
     * is a distinct three-digit rendering rather than a transcription. Leading zeros are retained
     * because the published width is exactly three and a code losing one would no longer be one.</p>
     *
     * @param index the position in the population, which must be below one thousand for the rendering
     *     to occupy exactly three digits
     * @return a three-character run of digits distinct for each distinct index; never {@code null}
     */
    private static String distinctAreaCode(int index) {
        return String.format("%03d", index);
    }

    /**
     * Builds a page carrying the whole seeded state-and-postal-prefix population.
     *
     * <p>Assumptions: the two ends of the published lexical span are placed at the two ends of the page
     * so that a truncation at either end is visible, and every other member is a distinct rendering of
     * the same four-character geometry.</p>
     *
     * @return a page of one reply per seeded pair, spanning the published range; never {@code null}
     */
    private static PageResponse<UsStateZipPrefixResponse> wholeZipPrefixPage() {
        List<UsStateZipPrefixResponse> rows = new ArrayList<>(SEEDED_ZIP_PREFIXES);
        rows.add(new UsStateZipPrefixResponse(FIRST_ZIP_PREFIX));
        for (int index = 1; index < SEEDED_ZIP_PREFIXES - 1; index++) {
            rows.add(new UsStateZipPrefixResponse(distinctZipPrefix(index)));
        }
        rows.add(new UsStateZipPrefixResponse(LAST_ZIP_PREFIX));
        return PageResponse.ofRows(rows, LEADING_POSITION, TRAILING_POSITION, false);
    }

    /**
     * Renders a distinct four-character state-and-postal-prefix pair for a position in the population.
     *
     * <p>Assumptions: two upper-case letters then two digits, which is the geometry the published
     * schema declares. The pair is built as ONE value rather than as a state part and a digit part,
     * because that is how it travels and how the baseline validates it.</p>
     *
     * @param index the position in the population, which must be below six hundred and seventy-six
     *     times one hundred for the rendering to stay within its two halves
     * @return a four-character pair distinct for each distinct index; never {@code null}
     */
    private static String distinctZipPrefix(int index) {
        char firstLetter = (char) ('A' + (index / 100) % 26);
        char secondLetter = (char) ('A' + (index / 2600) % 26);
        return String.valueOf(firstLetter) + secondLetter + String.format("%02d", index % 100);
    }

    /**
     * Collects the component names a record type declares, so a shape can be asserted exhaustively.
     *
     * @param recordType the record type whose components are to be named
     * @return the component names in declaration order; never {@code null}
     */
    private static Set<String> componentNamesOf(Class<?> recordType) {
        Set<String> names = new LinkedHashSet<>();
        for (RecordComponent component : recordType.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /**
     * The members the page envelope publishes, and the positions a caller pages by.
     */
    @Nested
    @DisplayName("on the page envelope")
    class OnThePageEnvelope {

        /**
         * The state browse answers a page envelope of exactly four members and publishes no fifth.
         *
         * <p>Refactoring Rationale: the projection that specified this class described this route as
         * answering an UNPAGED array of state replies, on the ground that the seeded set is small and
         * bounded. It does not. The handler's return type is the shared page envelope and the published
         * contract declares a page schema for this operation, so an unpaged assertion would fail against
         * both the service and its own contract, and the browser client written against that contract
         * reads a page here. The asymmetry the projection described is therefore recorded as absent
         * rather than asserted, and this case pins the shape that IS published so a later reader does not
         * reinstate the array.</p>
         *
         * <p>Assumptions: the member set is compared for equality rather than searched for the four
         * expected names, because an equality is what detects an ADDED member. A published fifth member
         * would be a body the shared type cannot produce for every route, so a strict client would
         * reject valid replies elsewhere for lacking it.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the state browse answers exactly items, firstKey, lastKey and hasNext")
        void theStateBrowseAnswersTheFourMemberEnvelope() throws Exception {
            when(lookups.listStates(any(LookupPageRequest.class), anyString()))
                    .thenReturn(statePage(List.of("AL", "AK"), true));

            MvcResult result = mockMvc
                    .perform(authorisedGet(AddressLookupController.STATE_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode body = bodyOf(result);
            assertThat(memberNamesOf(body)).isEqualTo(ENVELOPE_MEMBERS);
            assertThat(body.size()).isEqualTo(ENVELOPE_MEMBERS.size());
        }

        /**
         * The area-code browse publishes the same four members and no more.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the area-code browse answers the same four-member envelope")
        void theAreaCodeBrowseAnswersTheFourMemberEnvelope() throws Exception {
            when(lookups.listAreaCodes(any(LookupPageRequest.class), anyString()))
                    .thenReturn(PageResponse.ofRows(
                            List.of(new PhoneAreaCodeResponse("201",
                                    PhoneAreaCodeResponse.CodeClass.GENERAL_PURPOSE)),
                            LEADING_POSITION, TRAILING_POSITION, true));

            MvcResult result = mockMvc
                    .perform(authorisedGet(AddressLookupController.AREA_CODE_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(memberNamesOf(bodyOf(result))).isEqualTo(ENVELOPE_MEMBERS);
        }

        /**
         * The prefix browse publishes the same four members and no more.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the prefix browse answers the same four-member envelope")
        void thePrefixBrowseAnswersTheFourMemberEnvelope() throws Exception {
            when(lookups.listZipPrefixes(any(LookupPageRequest.class), anyString()))
                    .thenReturn(PageResponse.ofRows(
                            List.of(new UsStateZipPrefixResponse(FIRST_ZIP_PREFIX)),
                            LEADING_POSITION, TRAILING_POSITION, true));

            MvcResult result = mockMvc
                    .perform(authorisedGet(AddressLookupController.ZIP_PREFIX_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(memberNamesOf(bodyOf(result))).isEqualTo(ENVELOPE_MEMBERS);
        }

        /**
         * No envelope publishes a backward-availability member or any member of an offset-paged reply.
         *
         * <p>Assumptions: backward availability is INFERRED from the leading position being present
         * and is not published. Two nearby documents in this repository state that the shared type
         * carries a fifth component naming it; the type is a record of four components and all three
         * published page schemas declare four required properties with no additional property admitted,
         * so those two statements are wrong and this case is what keeps them from being acted on. The
         * remaining forbidden names are the members an offset-paged reply would carry.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("no envelope publishes a backward flag, a page ordinal, a size or a total")
        void theEnvelopePublishesNoBackwardFlagAndNoOffsetMember() throws Exception {
            when(lookups.listStates(any(LookupPageRequest.class), anyString()))
                    .thenReturn(statePage(List.of("AL"), true));

            MvcResult result = mockMvc
                    .perform(authorisedGet(AddressLookupController.STATE_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode body = bodyOf(result);
            for (String forbidden : FORBIDDEN_ENVELOPE_MEMBERS) {
                assertThat(body.has(forbidden))
                        .withFailMessage("the envelope published the member '%s', which no page schema "
                                + "declares and the shared response type has no component for", forbidden)
                        .isFalse();
            }
            assertThat(body.get("firstKey").stringValue()).isEqualTo(LEADING_POSITION);
        }

        /**
         * An exhausted page reports no further page and names neither boundary.
         *
         * <p>Assumptions: the two positions arrive as a present member whose value is null rather than
         * as an omitted member, which is what the shared type serialises and what the published schemas
         * require. A case written to expect them ABSENT would fail against a correct reply, so the
         * distinction is asserted rather than left to whichever spelling a reader assumed.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an exhausted page reports no further page and both positions null")
        void anExhaustedPageNamesNeitherBoundary() throws Exception {
            when(lookups.listStates(any(LookupPageRequest.class), anyString()))
                    .thenReturn(PageResponse.empty());

            MvcResult result = mockMvc
                    .perform(authorisedGet(AddressLookupController.STATE_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty())
                    .andExpect(jsonPath("$.hasNext").value(false))
                    .andReturn();

            JsonNode body = bodyOf(result);
            assertThat(memberNamesOf(body)).isEqualTo(ENVELOPE_MEMBERS);
            assertThat(body.get("firstKey").isNull()).isTrue();
            assertThat(body.get("lastKey").isNull()).isTrue();
        }

        /**
         * A trailing position a page published is accepted verbatim as the next forward position.
         *
         * <p>Assumptions: the value is compared for exact equality on both legs of the round trip, which
         * is the whole point of the case. A position is opaque, so any re-encoding between the reply and
         * the following request -- a trim, a case change, an unescape -- would leave a client unable to
         * continue while every status stayed 200.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a published trailing position is accepted verbatim as the forward position")
        void aPublishedPositionRoundTripsVerbatim() throws Exception {
            when(lookups.listStates(any(LookupPageRequest.class), anyString()))
                    .thenReturn(statePage(List.of("AL", "AK"), true));

            MvcResult opening = mockMvc
                    .perform(authorisedGet(AddressLookupController.STATE_PATH))
                    .andExpect(status().isOk())
                    .andReturn();
            String published = bodyOf(opening).get("lastKey").stringValue();
            assertThat(published).isEqualTo(TRAILING_POSITION);

            reset(lookups);
            when(lookups.listStates(any(LookupPageRequest.class), anyString()))
                    .thenReturn(statePage(List.of("AZ"), false));

            mockMvc.perform(authorisedGet(AddressLookupController.STATE_PATH)
                            .param(PARAM_CURSOR, published)
                            .param(PARAM_DIRECTION, PageDirection.NEXT.wireValue()))
                    .andExpect(status().isOk());

            LookupPageRequest received = capturedStateRequest();
            assertThat(received.cursor()).isEqualTo(published);
            assertThat(received.direction()).isEqualTo(PageDirection.NEXT);
        }

        /**
         * A backward request reaches the service as the backward direction, carrying the leading position.
         *
         * <p>Assumptions: the baseline's backward browse seeks strictly before the position it is given
         * and reads descending -- {@code WHERE TR_TYPE < :WS-START-KEY} at L359 of
         * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} ordered by L367 -- against a forward
         * browse that seeks inclusively and reads ascending, at L343 and L351. Which of those two the
         * direction selects is asserted where the queries live; what is asserted here is that the
         * direction a caller sent is the direction the layer below receives, since a direction silently
         * defaulted to forward pages the wrong way while answering 200.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a backward request reaches the service as the backward direction")
        void aBackwardRequestReachesTheServiceAsBackward() throws Exception {
            when(lookups.listStates(any(LookupPageRequest.class), anyString()))
                    .thenReturn(statePage(List.of("AK", "AL"), false));

            mockMvc.perform(authorisedGet(AddressLookupController.STATE_PATH)
                            .param(PARAM_CURSOR, LEADING_POSITION)
                            .param(PARAM_DIRECTION, PageDirection.PREVIOUS.wireValue()))
                    .andExpect(status().isOk());

            LookupPageRequest received = capturedStateRequest();
            assertThat(received.direction()).isEqualTo(PageDirection.PREVIOUS);
            assertThat(received.cursor()).isEqualTo(LEADING_POSITION);
        }

        /**
         * A backward page is emitted in the order the layer below supplied, not merely with its members.
         *
         * <p>Assumptions: a backward walk reads descending and its rows are reversed before display, so
         * the order the service hands up is already the display order. Asserting membership alone would
         * pass against a boundary that re-sorted or reversed the page again, which is the one mistake
         * that leaves a caller's positions pointing at the wrong ends of what it was shown.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a backward page is emitted in the order the service supplied")
        void aBackwardPageKeepsTheOrderItWasGiven() throws Exception {
            List<String> supplied = List.of("WY", "WV", "WI");
            when(lookups.listStates(any(LookupPageRequest.class), anyString()))
                    .thenReturn(statePage(supplied, false));

            MvcResult result = mockMvc
                    .perform(authorisedGet(AddressLookupController.STATE_PATH)
                            .param(PARAM_CURSOR, LEADING_POSITION)
                            .param(PARAM_DIRECTION, PageDirection.PREVIOUS.wireValue()))
                    .andExpect(status().isOk())
                    .andReturn();

            List<String> emitted = new ArrayList<>();
            for (JsonNode row : bodyOf(result).get("items")) {
                emitted.add(row.get("stateCd").stringValue());
            }
            assertThat(emitted).containsExactlyElementsOf(supplied);
        }

        /**
         * The paging shape a caller may steer carries a position, a direction and a filter, and nothing else.
         *
         * <p>Alternatives Considered: paging by offset, which the persistence framework offers
         * ready-made and which would have made this shape a page number and a page size. It is rejected
         * for a behavioural reason and not a preference: an offset walk over a set that is being written
         * SKIPS AND REPEATS rows, because a row inserted before the current offset shifts every later
         * row across a page boundary. A walk positioned by key cannot do either, since it names the row
         * it continues from. These three tables are maintained through the write operations this same
         * contract publishes, so the set genuinely does change under a reader.</p>
         *
         * <p>Assumptions: the same assertion carries the elimination of the baseline's
         * pseudo-conversational re-entry discriminator. That baseline distinguished a first arrival at a
         * screen from a re-entry, and no member of this shape can carry a turn count, a re-entry flag or
         * a resubmit marker, so the discriminator is gone rather than renamed. Comparing the component
         * set for equality is what makes both guarantees hold as one check.</p>
         */
        @Test
        @DisplayName("the paging shape declares only a position, a direction and a filter")
        void thePagingShapeCarriesNoOffsetAndNoReEntryMarker() {
            assertThat(componentNamesOf(LookupPageRequest.class))
                    .isEqualTo(PAGING_REQUEST_COMPONENTS);
        }
    }

    /**
     * The three seeded domains reaching a client at their declared sizes and widths.
     */
    @Nested
    @DisplayName("on the seeded domain surface")
    class OnTheSeededDomainSurface {

        /**
         * The whole seeded area-code population crosses the boundary with every member intact.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("all 490 seeded area codes reach the surface")
        void theWholeAreaCodePopulationReachesTheSurface() throws Exception {
            when(lookups.listAreaCodes(any(LookupPageRequest.class), anyString()))
                    .thenReturn(wholeAreaCodePage());

            MvcResult result = mockMvc
                    .perform(authorisedGet(AddressLookupController.AREA_CODE_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode items = bodyOf(result).get("items");
            assertThat(items.size()).isEqualTo(SEEDED_AREA_CODES);
            Set<String> distinct = new LinkedHashSet<>();
            for (JsonNode row : items) {
                String areaCd = row.get("areaCd").stringValue();
                assertThat(areaCd).hasSize(AREA_CODE_WIDTH);
                distinct.add(areaCd);
            }
            assertThat(distinct).hasSize(SEEDED_AREA_CODES);
        }

        /**
         * All fifty-six seeded state codes reach the surface, and the domain is not the fifty states.
         *
         * <p>Assumptions: the domain must NOT be narrowed to fifty. Beyond the states the seeded list
         * admits the federal district and the five inhabited territories, and the consuming context's
         * address edit accepts an address in each of them only because this surface serves the code. A
         * narrowing would therefore present as a resident of one of those six being told their state is
         * invalid, in a service that owns none of this data. Both halves are asserted -- the size and the
         * presence of the six -- because either alone can be satisfied by a list that is wrong in the
         * other way.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("all 56 seeded state codes reach the surface, district and territories included")
        void theWholeStatePopulationReachesTheSurface() throws Exception {
            when(lookups.listStates(any(LookupPageRequest.class), anyString()))
                    .thenReturn(statePage(SEEDED_STATE_CODES, false));

            MvcResult result = mockMvc
                    .perform(authorisedGet(AddressLookupController.STATE_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            List<String> emitted = new ArrayList<>();
            for (JsonNode row : bodyOf(result).get("items")) {
                String stateCd = row.get("stateCd").stringValue();
                assertThat(stateCd).hasSize(STATE_CODE_WIDTH);
                emitted.add(stateCd);
            }

            assertThat(emitted).hasSize(56);
            assertThat(emitted).containsExactlyElementsOf(SEEDED_STATE_CODES);
            assertThat(emitted).containsAll(DISTRICT_AND_TERRITORIES);
            List<String> withoutTheSix = new ArrayList<>(emitted);
            withoutTheSix.removeAll(DISTRICT_AND_TERRITORIES);
            assertThat(withoutTheSix)
                    .withFailMessage("the seeded state domain must exceed the fifty states by exactly "
                            + "the district and the five territories")
                    .hasSize(50);
        }

        /**
         * All two hundred and forty seeded prefix pairs reach the surface across the published span.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("all 240 seeded prefix pairs reach the surface across the published span")
        void theWholePrefixPopulationReachesTheSurface() throws Exception {
            when(lookups.listZipPrefixes(any(LookupPageRequest.class), anyString()))
                    .thenReturn(wholeZipPrefixPage());

            MvcResult result = mockMvc
                    .perform(authorisedGet(AddressLookupController.ZIP_PREFIX_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode items = bodyOf(result).get("items");
            assertThat(items.size()).isEqualTo(SEEDED_ZIP_PREFIXES);
            List<String> emitted = new ArrayList<>();
            for (JsonNode row : items) {
                emitted.add(row.get("stateZipCd").stringValue());
            }
            assertThat(emitted).contains(FIRST_ZIP_PREFIX, LAST_ZIP_PREFIX);
            assertThat(emitted.get(0)).isEqualTo(FIRST_ZIP_PREFIX);
            assertThat(emitted.get(emitted.size() - 1)).isEqualTo(LAST_ZIP_PREFIX);
        }

        /**
         * No prefix reply carries the trailing three postal digits the copybook parses beside the pair.
         *
         * <p>Assumptions: the field declared {@code PIC X(3)} at L1314 of
         * {@code app/cpy/CSLKPCDY.cpy} is input-parsing scaffolding and has no counterpart on this
         * surface. It sits beside the four-character pair only so that a supplied postal code can be
         * split; the allow-list at L1073 admits the PAIR and the baseline never validates a whole postal
         * code against anything. Publishing a member for it would advertise a validation this system does
         * not perform, so its absence is asserted rather than assumed.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("no prefix reply carries the trailing three postal digits")
        void noPrefixReplyCarriesTheTrailingPostalDigits() throws Exception {
            when(lookups.listZipPrefixes(any(LookupPageRequest.class), anyString()))
                    .thenReturn(PageResponse.ofRows(
                            List.of(new UsStateZipPrefixResponse(FIRST_ZIP_PREFIX)),
                            LEADING_POSITION, TRAILING_POSITION, false));

            MvcResult result = mockMvc
                    .perform(authorisedGet(AddressLookupController.ZIP_PREFIX_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode row = bodyOf(result).get("items").get(0);
            assertThat(memberNamesOf(row)).containsExactly("stateZipCd");
            for (String absent : List.of("lastThreeOfZip", "last3OfZip", "zipSuffix", "zipCode",
                    "postalCode", "zip")) {
                assertThat(row.has(absent))
                        .withFailMessage("the prefix reply published '%s', which corresponds to the "
                                + "parsing field at L1314 of app/cpy/CSLKPCDY.cpy and has no target "
                                + "column", absent)
                        .isFalse();
            }
        }
    }

    /**
     * The area-code classification arriving as a total and disjoint two-value partition.
     */
    @Nested
    @DisplayName("on the area-code classification")
    class OnTheAreaCodeClassification {

        /**
         * Every area code on the surface carries one of exactly two classifications, and none is absent.
         *
         * <p>Assumptions: the partition is TOTAL and DISJOINT, and two neighbouring documents in this
         * module describe the underlying lists as overlapping. They are wrong, and the arithmetic they
         * themselves quote refutes them. Extracting the literals of
         * {@code app/cpy/CSLKPCDY.cpy} over columns seven to seventy-two yields four hundred and ninety
         * in the master list at L30, four hundred and ten in the general-purpose list at L521 and eighty
         * in the easily-recognisable list at L931; the intersection of the two sub-lists is EMPTY, their
         * union equals the master list in both directions, and four hundred and ten plus eighty is four
         * hundred and ninety exactly. This case is what keeps the prose from being acted on, so nobody
         * relaxes the assertion back to an overlap.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("every code carries one of exactly two classifications and none is absent")
        void theClassificationIsTotalOverTheSurface() throws Exception {
            when(lookups.listAreaCodes(any(LookupPageRequest.class), anyString()))
                    .thenReturn(wholeAreaCodePage());

            MvcResult result = mockMvc
                    .perform(authorisedGet(AddressLookupController.AREA_CODE_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            Set<String> observedClasses = new LinkedHashSet<>();
            for (JsonNode row : bodyOf(result).get("items")) {
                assertThat(row.has("codeClass"))
                        .withFailMessage("an area code reached the surface with no classification, "
                                + "which the partition forbids")
                        .isTrue();
                JsonNode codeClass = row.get("codeClass");
                assertThat(codeClass.isNull())
                        .withFailMessage("an area code reached the surface with a null classification, "
                                + "which the partition forbids")
                        .isFalse();
                observedClasses.add(codeClass.stringValue());
            }

            assertThat(observedClasses).containsExactlyInAnyOrder(
                    PhoneAreaCodeResponse.CodeClass.GENERAL_PURPOSE.wireValue(),
                    PhoneAreaCodeResponse.CodeClass.EASILY_RECOGNISABLE.wireValue());
        }

        /**
         * No area code appears in both classes, and the two classes sum to the master list.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the two classes are disjoint and sum to the master list")
        void theTwoClassesAreDisjointAndSumToTheMasterList() throws Exception {
            when(lookups.listAreaCodes(any(LookupPageRequest.class), anyString()))
                    .thenReturn(wholeAreaCodePage());

            MvcResult result = mockMvc
                    .perform(authorisedGet(AddressLookupController.AREA_CODE_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            Set<String> general = new LinkedHashSet<>();
            Set<String> easilyRecognisable = new LinkedHashSet<>();
            for (JsonNode row : bodyOf(result).get("items")) {
                String areaCd = row.get("areaCd").stringValue();
                if (PhoneAreaCodeResponse.CodeClass.GENERAL_PURPOSE.wireValue()
                        .equals(row.get("codeClass").stringValue())) {
                    general.add(areaCd);
                } else {
                    easilyRecognisable.add(areaCd);
                }
            }

            assertThat(general).hasSize(GENERAL_PURPOSE_AREA_CODES);
            assertThat(easilyRecognisable).hasSize(EASILY_RECOGNISABLE_AREA_CODES);
            assertThat(general).doesNotContainAnyElementsOf(easilyRecognisable);
            assertThat(GENERAL_PURPOSE_AREA_CODES + EASILY_RECOGNISABLE_AREA_CODES)
                    .isEqualTo(SEEDED_AREA_CODES);
        }

        /**
         * The classification domain the surface publishes has exactly the two values the filter admits.
         *
         * <p>Assumptions: the published characters are read from the type that declares them rather than
         * written as literals, so a case sending one spelling and a filter admitting another cannot drift
         * apart silently. The filter expression itself is exercised in this package already; what is
         * added here is that the two values the filter admits are the same two the surface emits.</p>
         */
        @Test
        @DisplayName("the published classification domain has exactly two values")
        void theClassificationDomainHasExactlyTwoValues() {
            List<String> published = new ArrayList<>();
            for (PhoneAreaCodeResponse.CodeClass codeClass
                    : PhoneAreaCodeResponse.CodeClass.values()) {
                published.add(codeClass.wireValue());
            }

            assertThat(published).hasSize(2);
            for (String value : published) {
                assertThat(value).matches(LookupPageRequest.CODE_CLASS_PATTERN);
            }
        }
    }

    /**
     * The width each key round-trips at, and the problem document an absent code is refused with.
     */
    @Nested
    @DisplayName("on the key shapes and the refusal document")
    class OnTheKeyShapesAndTheRefusalDocument {

        /**
         * An area code read by value round-trips at exactly three characters, leading zero intact.
         *
         * <p>Assumptions: a code whose leading digit is zero is the case that distinguishes a
         * three-character contract from a numeric one. Carried as a number it would render as two
         * characters and no longer be a code, so the value chosen begins with a zero deliberately.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an area code round-trips at exactly three characters")
        void anAreaCodeRoundTripsAtThreeCharacters() throws Exception {
            when(lookups.readAreaCode("012")).thenReturn(new PhoneAreaCodeResponse("012",
                    PhoneAreaCodeResponse.CodeClass.GENERAL_PURPOSE));

            MvcResult result = mockMvc
                    .perform(authorisedGet(AddressLookupController.AREA_CODE_PATH + "/012"))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode body = bodyOf(result);
            assertThat(body.get("areaCd").stringValue()).isEqualTo("012");
            assertThat(body.get("areaCd").stringValue()).hasSize(AREA_CODE_WIDTH);
        }

        /**
         * A state code read by value round-trips at exactly two characters.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a state code round-trips at exactly two characters")
        void aStateCodeRoundTripsAtTwoCharacters() throws Exception {
            when(lookups.readState("AL")).thenReturn(new UsStateResponse("AL"));

            MvcResult result = mockMvc
                    .perform(authorisedGet(AddressLookupController.STATE_PATH + "/AL"))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode body = bodyOf(result);
            assertThat(memberNamesOf(body)).containsExactly("stateCd");
            assertThat(body.get("stateCd").stringValue()).isEqualTo("AL");
            assertThat(body.get("stateCd").stringValue()).hasSize(STATE_CODE_WIDTH);
        }

        /**
         * A prefix pair round-trips as one indivisible four-character value, never split on the wire.
         *
         * <p>Assumptions: the pair is ONE value. Its two halves are concatenated in the field declared
         * {@code PIC X(4)} at L1072 of {@code app/cpy/CSLKPCDY.cpy} and the allow-list at L1073 admits the
         * concatenation, so splitting it into a state member and a digits member on the wire would offer a
         * client two keys where the seeded data has one and would invite a probe for a combination the
         * baseline never probes for. The reply is therefore asserted to carry exactly one member.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a prefix pair round-trips as one indivisible four-character value")
        void aPrefixPairRoundTripsAsOneIndivisibleValue() throws Exception {
            when(lookups.readZipPrefix(LAST_ZIP_PREFIX))
                    .thenReturn(new UsStateZipPrefixResponse(LAST_ZIP_PREFIX));

            MvcResult result = mockMvc
                    .perform(authorisedGet(
                            AddressLookupController.ZIP_PREFIX_PATH + "/" + LAST_ZIP_PREFIX))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode body = bodyOf(result);
            assertThat(memberNamesOf(body)).containsExactly("stateZipCd");
            assertThat(body.get("stateZipCd").stringValue()).isEqualTo(LAST_ZIP_PREFIX);
            assertThat(body.get("stateZipCd").stringValue()).hasSize(ZIP_PREFIX_WIDTH);
        }

        /**
         * A well-formed but unseeded code is refused with the shared problem document at the published status.
         *
         * <p>Assumptions: this case exists for the SHARED document and not for the status, which is
         * already driven in this package on the area-code item route. What is added is that the refusal
         * carries the shared shape's own code member and the domain's own sentence, both of which depend
         * on the advice registered by the wiring below; without that registration a status would still
         * arrive and neither member would.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an unseeded state code is refused with the shared problem document")
        void anUnseededCodeIsRefusedWithTheSharedDocument() throws Exception {
            when(lookups.readState("ZZ")).thenThrow(
                    new NoSuchElementException(AddressLookupService.MESSAGE_STATE_NOT_FOUND));

            mockMvc.perform(authorisedGet(AddressLookupController.STATE_PATH + "/ZZ"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_NOT_FOUND))
                    .andExpect(jsonPath("$.message").value(
                            AddressLookupService.MESSAGE_STATE_NOT_FOUND))
                    .andExpect(jsonPath("$.status").value(404));
        }

        /**
         * A refusal sentence within the message band reaches the body, and one beyond it does not.
         *
         * <p>Assumptions: the band is seventy-five characters, which is the width the reference
         * message line occupies in the baseline -- {@code CCARD-ERROR-MSG} and
         * {@code CCARD-RETURN-MSG}, both {@code PIC X(75)}, at L28 and L29 of
         * {@code app/cpy/CVCRD01Y.cpy}, and {@code WS-RETURN-MSG PIC X(75)} at L167 of
         * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}. The shared shape publishes the same
         * width, and the advice admits a domain sentence only while it fits: a longer one is discarded
         * for the generic wording. Both directions are asserted, because a case that only sent a short
         * sentence would pass against a boundary that had no bound at all.</p>
         *
         * <p>Assumptions: the two baseline declarations disagree on the sentinel that marks the line
         * EMPTY -- L168 of that program uses spaces where L30 of that copybook uses low values -- and
         * neither sentinel travels on this surface, where an absent message is an absent member. The
         * divergence is recorded so that a reader comparing the two declarations does not take it for an
         * inconsistency introduced here.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a sentence within the 75-character band reaches the body and a wider one does not")
        void theMessageBandIsEnforcedInBothDirections() throws Exception {
            assertThat(AddressLookupService.MESSAGE_STATE_NOT_FOUND.length())
                    .isLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH);

            when(lookups.readState("AL")).thenThrow(
                    new NoSuchElementException(AddressLookupService.MESSAGE_STATE_NOT_FOUND));

            MvcResult within = mockMvc
                    .perform(authorisedGet(AddressLookupController.STATE_PATH + "/AL"))
                    .andExpect(status().isNotFound())
                    .andReturn();
            String rendered = bodyOf(within).get("message").stringValue();
            assertThat(rendered).isEqualTo(AddressLookupService.MESSAGE_STATE_NOT_FOUND);
            assertThat(rendered.length()).isLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH);

            String beyondTheBand = "S".repeat(ApiError.MESSAGE_RENDERING_WIDTH + 1);
            when(lookups.readState("AK")).thenThrow(new NoSuchElementException(beyondTheBand));

            MvcResult beyond = mockMvc
                    .perform(authorisedGet(AddressLookupController.STATE_PATH + "/AK"))
                    .andExpect(status().isNotFound())
                    .andReturn();
            assertThat(bodyOf(beyond).get("message").stringValue())
                    .withFailMessage("a sentence wider than the %d-character band reached the body, so "
                            + "the band is documented rather than enforced",
                            ApiError.MESSAGE_RENDERING_WIDTH)
                    .isNotEqualTo(beyondTheBand);
        }

        /**
         * A populated refusal is not overwritten by a later one on the same request.
         *
         * <p>Assumptions: this is the baseline's first-error-wins discipline, which guards every
         * message assignment behind a test that the line is still empty -- seventeen such guards in
         * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}, the first at L1195 and the last at
         * L2040, over the line declared at L167 to L168 of the accompanying update program. The request
         * below is wrong in two places at once: the position fails a parameter constraint, and the
         * direction is a value the domain does not admit and would be refused inside the handler. The
         * earlier refusal must survive, so the document must name the position and must NOT name the
         * direction -- if it named the direction, the handler had run and the first refusal had been
         * discarded.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the earlier refusal survives and the later one never reaches the document")
        void aPopulatedRefusalIsNotOverwritten() throws Exception {
            MvcResult result = mockMvc
                    .perform(authorisedGet(AddressLookupController.AREA_CODE_PATH)
                            .param(PARAM_CURSOR, "not-a-sealed-position")
                            .param(PARAM_DIRECTION, "sideways"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                    .andReturn();

            List<String> namedFields = new ArrayList<>();
            for (JsonNode fieldError : bodyOf(result).get("fieldErrors")) {
                namedFields.add(fieldError.get("field").stringValue());
            }

            assertThat(namedFields).contains(PARAM_CURSOR);
            assertThat(namedFields)
                    .withFailMessage("the document named the direction, so the handler ran and the "
                            + "earlier refusal was discarded rather than answered")
                    .doesNotContain(PARAM_DIRECTION);
            assertThat(bodyOf(result).get("message").stringValue())
                    .isNotEqualTo(PageDirection.MESSAGE_UNADMITTED_DIRECTION);
        }
    }

    /**
     * Which caller the deployed chain admits to a lookup read, measured at the dispatcher.
     */
    @Nested
    @DisplayName("on the authorisation posture of a read")
    class OnTheAuthorisationPostureOfARead {

        /**
         * A browse presented with no token at all is challenged rather than served.
         *
         * <p>Assumptions: this case also proves the chain is genuinely in front of the dispatcher, which
         * every other case in this group depends on. Were the filter absent the request would reach the
         * handler, so a granted dispatch and an omitted chain would otherwise be indistinguishable.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a browse with no token is refused")
        void aBrowseWithNoTokenIsRefused() throws Exception {
            mockMvc.perform(get(AddressLookupController.STATE_PATH))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * A caller holding only the ordinary group is admitted to all three browses.
         *
         * <p>Refactoring Rationale: this is the load-bearing case of the class. The published contract
         * marks every one of these reads as requiring the ordinary group, and the read rule the chain
         * installs admits either CardDemo group; if a read were guarded to the administrative group
         * instead, this surface would answer the administrators only. The consuming context's address
         * edit runs as whichever ordinary user is signed on, so over-guarding a read here would present
         * there as every address validation failing for every ordinary user, in a service that owns none
         * of this data and has no defect of its own to find. The case is written per path rather than
         * once, because the rule is installed on a subtree pattern and a route mounted outside that
         * pattern would fall through to the chain's own deny-all.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a caller holding only the ordinary group is admitted to every browse")
        void theOrdinaryGroupAloneIsAdmitted() throws Exception {
            grantGroups(JwtRoleConverter.USER_AUTHORITY);
            when(lookups.listStates(any(LookupPageRequest.class), anyString()))
                    .thenReturn(PageResponse.empty());
            when(lookups.listAreaCodes(any(LookupPageRequest.class), anyString()))
                    .thenReturn(PageResponse.empty());
            when(lookups.listZipPrefixes(any(LookupPageRequest.class), anyString()))
                    .thenReturn(PageResponse.empty());

            for (String path : List.of(AddressLookupController.STATE_PATH,
                    AddressLookupController.AREA_CODE_PATH,
                    AddressLookupController.ZIP_PREFIX_PATH)) {
                mockMvc.perform(authorisedGet(path))
                        .andExpect(status().isOk());
            }
        }

        /**
         * A caller holding the administrative group is admitted to a read as well.
         *
         * <p>Assumptions: an administrator satisfies the read rule alongside an ordinary user, which is
         * the two-value user domain the baseline already had rather than a privilege granted here. The
         * case is present so that the previous one cannot be satisfied by a rule that admits ONLY the
         * ordinary group, which would refuse an administrator the reference data every administrative
         * screen also displays.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a caller holding the administrative group is admitted to a read")
        void theAdministrativeGroupIsAdmittedToARead() throws Exception {
            grantGroups(JwtRoleConverter.ADMIN_AUTHORITY);
            when(lookups.listStates(any(LookupPageRequest.class), anyString()))
                    .thenReturn(PageResponse.empty());

            mockMvc.perform(authorisedGet(AddressLookupController.STATE_PATH))
                    .andExpect(status().isOk());
        }

        /**
         * A validly decoded token carrying neither group reaches no lookup read.
         *
         * <p>Assumptions: the token decodes and authenticates, so what is refused here is the
         * AUTHORISATION and not the authentication. That distinction is the point: a rule of merely
         * being authenticated would admit this caller, and the read rule the chain installs names the two
         * groups instead.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a token carrying neither group reaches no lookup read")
        void aTokenCarryingNeitherGroupIsRefused() throws Exception {
            grantGroups();

            mockMvc.perform(authorisedGet(AddressLookupController.STATE_PATH))
                    .andExpect(status().isForbidden());
        }
    }

    /**
     * The statelessness of the boundary, which the baseline's screen dialogue did not have.
     */
    @Nested
    @DisplayName("on the statelessness of the boundary")
    class OnTheStatelessnessOfTheBoundary {

        /**
         * Two identical requests answer identically, and neither establishes a session.
         *
         * <p>Assumptions: the baseline's online dialogue was pseudo-conversational, so continuity
         * between two turns lived in a passed structure and a turn was not interpretable without the
         * previous one. Nothing of that survives here: the caller comes from a validated token, the
         * position travels as a request parameter, and the re-entry discriminator is gone. The
         * baseline itself supplies the corroborating precedent -- the date-conversion program is declared
         * {@code IS INITIAL} at L2 of {@code app/app-vsam-mq/cbl/CODATE01.cbl}, so its storage is
         * reinitialised on every invocation and statelessness is the baseline contract rather than a
         * preference adopted here.</p>
         *
         * <p>Assumptions: the absence of a session is asserted as well as the equality of the two bodies,
         * because equal bodies alone would also be produced by a boundary that had established a session
         * and happened to answer the same thing twice. A surface that established one could not be
         * scaled across tasks without the requests being pinned to a task.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("two identical requests answer identically and establish no session")
        void twoIdenticalRequestsAnswerIdenticallyWithNoSession() throws Exception {
            when(lookups.listStates(any(LookupPageRequest.class), anyString()))
                    .thenReturn(statePage(List.of("AL", "AK"), true));

            MvcResult first = mockMvc
                    .perform(authorisedGet(AddressLookupController.STATE_PATH)
                            .param(PARAM_CURSOR, LEADING_POSITION)
                            .param(PARAM_DIRECTION, PageDirection.NEXT.wireValue()))
                    .andExpect(status().isOk())
                    .andReturn();
            MvcResult second = mockMvc
                    .perform(authorisedGet(AddressLookupController.STATE_PATH)
                            .param(PARAM_CURSOR, LEADING_POSITION)
                            .param(PARAM_DIRECTION, PageDirection.NEXT.wireValue()))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(second.getResponse().getContentAsString())
                    .isEqualTo(first.getResponse().getContentAsString());
            for (MvcResult exchange : List.of(first, second)) {
                assertThat(exchange.getRequest().getSession(false))
                        .withFailMessage("the boundary established a session, so a browse could not be "
                                + "served by any task that did not hold it")
                        .isNull();
                assertThat(exchange.getResponse().getCookies()).isEmpty();
            }
        }

        /**
         * A re-entry marker a caller invents steers nothing, so the reply is the one it would have had.
         *
         * <p>Assumptions: the parameters below are the shapes a caller porting the baseline's dialogue
         * would reach for. None is declared by the contract, so each must be inert; the assertion is that
         * the reply is byte-identical to the reply for the same request without them, which is stronger
         * than checking a status because it also rules out a marker that quietly narrowed the page.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an invented re-entry or turn-count parameter steers nothing")
        void anInventedReEntryParameterSteersNothing() throws Exception {
            when(lookups.listStates(any(LookupPageRequest.class), anyString()))
                    .thenReturn(statePage(SEEDED_STATE_CODES, false));

            MvcResult plain = mockMvc
                    .perform(authorisedGet(AddressLookupController.STATE_PATH))
                    .andExpect(status().isOk())
                    .andReturn();
            MvcResult embellished = mockMvc
                    .perform(authorisedGet(AddressLookupController.STATE_PATH)
                            .param("pgmContext", "1")
                            .param("reenter", "true")
                            .param("screenNumber", "3")
                            .param("page", "2")
                            .param("offset", "40"))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(embellished.getResponse().getContentAsString())
                    .isEqualTo(plain.getResponse().getContentAsString());
        }
    }

    /**
     * Wires the smallest context that can hold the deployed chain, the controller and the shared advice.
     *
     * <p>Assumptions: the two group names handed to the authority converter factory are the shared
     * kernel's own compiled constants. That factory compares what it is given against those constants
     * and refuses to start on a mismatch, so passing them is what keeps this class's authority
     * derivation identical to a deployment's rather than merely similar to it.</p>
     *
     * <p>Assumptions: the shared advice is contributed HERE, as a bean, and that is the registration
     * every status assertion in this class rests on. It is the only advice in this migration, it lives
     * outside this context's scan root, and a dispatcher assembled by hand inherits nothing; removing
     * this one method is what makes a status assertion fail rather than quietly pass against a container
     * default. No second advice is declared.</p>
     *
     * <p>Assumptions: the security configuration's own methods are CALLED rather than the class being
     * registered as a configuration. Registering it would also create the token decoder it declares,
     * whose factory resolves the provider document while the context refreshes, so a context that
     * created that bean could not start against the deliberately unreachable issuer this module's test
     * profile pins.</p>
     *
     * <p>Assumptions: of the content elements the user rule enumerates, only Purpose applies to a type
     * declaration, so the other three are inapplicable here rather than omitted.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class DispatcherWiring {

        /**
         * Supplies the fixed clock the advice and the refusal renderers stamp every document from.
         *
         * @return a clock pinned to the instant this class fixes; never {@code null}
         */
        @Bean
        Clock clock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }

        /**
         * Supplies the stand-in decoder the resource-server filter resolves a presented token with.
         *
         * @return a stand-in for the token decoder, with no stubbing applied; never {@code null}
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

        /**
         * Supplies the stand-in for the one collaborator the controller holds.
         *
         * @return a stand-in for the address-lookup service; never {@code null}
         */
        @Bean
        AddressLookupService addressLookupService() {
            return mock(AddressLookupService.class);
        }

        /**
         * Supplies the deployed controller over the stand-in service.
         *
         * @param lookups the stand-in service the handlers delegate to; must not be {@code null}
         * @return the controller under test; never {@code null}
         */
        @Bean
        AddressLookupController addressLookupController(AddressLookupService lookups) {
            return new AddressLookupController(lookups);
        }

        /**
         * Supplies the shared advice, which is what renders every refusal this class asserts on.
         *
         * @param clock the clock the rendered documents read their instant from; must not be
         *     {@code null}
         * @return the one advice of this migration; never {@code null}
         */
        @Bean
        GlobalExceptionHandler globalExceptionHandler(Clock clock) {
            return new GlobalExceptionHandler(clock);
        }
    }
}
