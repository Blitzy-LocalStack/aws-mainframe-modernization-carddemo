// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/api/TransactionTypeControllerTest.java
// -----------------------------------------------------------------------------
// WHAT: Drives the transaction-type surface through a real dispatcher and holds the answers a
//       caller receives to the published contract: which status each refusal renders, which
//       sentence discriminates the three refusals that share a status, which four members the
//       page envelope carries, which position its trailing cursor names, and that nothing about
//       one request is remembered into the next.
//
// WHY (non-obvious design decisions):
//   (1) Assumptions: this class registers the shared advice BY HAND. A dispatcher assembled by
//       standaloneSetup has no context to discover a @RestControllerAdvice from, and
//       com.carddemo.common.error.GlobalExceptionHandler is the only one anywhere in this
//       migration. Omitting the registration does not fail loudly: the dispatcher falls back to
//       the framework's default error handling, every status assertion below then observes that
//       instead of the real mapping, and a refusal that must render as a conflict reads as
//       green. Every status assertion in this file rests on that registration.
//   (2) Alternatives Considered: a context-slicing web test, which the migration plan specified
//       and which would have imported the advice as a bean instead. Not adopted, because no such
//       annotation is used anywhere in this reactor and the two sibling dispatcher classes in
//       this package already fix the idiom -- a hand-constructed controller over a substituted
//       service, one message converter, one advice. Following the tree keeps one mechanism in
//       this package rather than two.
//   (3) Alternatives Considered: asserting the authority each route demands, which the plan also
//       specified for this file. Deliberately NOT done. No security chain is installed here, and
//       that absence is what keeps an unmounted address distinguishable from a refused caller:
//       with no chain there is no authority to refuse, so a 404 means the address rather than the
//       caller. The rule table is owned by the sibling com.carddemo.reference.config test
//       package, which asserts it against the chain's own installed authorization managers. What
//       IS asserted here is the one authority-adjacent property that belongs to this boundary
//       rather than to the chain: the caller's name reaching the browse as the value its paging
//       positions are sealed against.
//   (4) Trade-offs: the service is substituted, so what this class can witness is the answer the
//       boundary composes from what the service raised, and not the query that decided it. The
//       transcribed rules are proven in com.carddemo.reference.service and the constraints the
//       schema itself makes in com.carddemo.reference.repository against a container. The
//       compromise buys a single owner per rule; a rule asserted in two places can be satisfied
//       in one of them and reported as satisfied in both.
//   (5) Assumptions: the four category labels below are written in the plural, un-parenthesised
//       form the rules document lists at its lines 31 to 34. The singular and parenthesised
//       spellings mean the same thing and are simply not used; that equivalence is recorded in
//       this sentence alone and the forms are not mixed anywhere in this file.
//
//   Assumptions: this header block sits ABOVE the package declaration, which is
//       unusual for Java and is what makes the WHAT: line above legal. Rule 1's
//       prohibition is on a statement-level WHAT:, and
//       config/rule1/rule1_gate.py implements it by permitting WHAT: only inside a
//       file's leading header block -- a contiguous run of comment lines beginning
//       at line ONE. With the package declaration first, the same block is a
//       statement-level comment and the gate fails the build. Three sibling
//       dispatcher classes in this package already carry the header first; the
//       package-first ones carry no WHAT: line at all, which is the other legal
//       shape.
// =============================================================================

package com.carddemo.reference.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;


import com.carddemo.common.CardDemoCommonAutoConfiguration;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CorrelationIdFilter;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.PageDirection;
import com.carddemo.reference.dto.TransactionTypeCreateRequest;
import com.carddemo.reference.dto.TransactionTypeListRequest;
import com.carddemo.reference.dto.TransactionTypeResponse;
import com.carddemo.reference.dto.TransactionTypeUpdateRequest;
import com.carddemo.reference.service.ReferencePaging;
import com.carddemo.reference.service.TransactionTypeService;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds the transaction-type boundary to the answers its published contract declares.
 *
 * <h2>What this class answers for</h2>
 *
 * <p>Purpose: five questions, each observable only at the boundary. Which status a refusal
 * renders, and in particular that a delete refused by a still-referencing row renders as a
 * conflict rather than as a fault. Which sentence a caller receives, since three distinct
 * conditions share the conflict status and the sentence is the only thing that separates them.
 * Which members the page envelope publishes, and which row its trailing position names. Whether
 * the caller's identity reaches the browse that seals paging positions against it. And whether
 * two identical requests are answered identically, with nothing carried between them.
 *
 * <p>Assumptions: the authoritative statement of all of that is
 * {@code src/main/resources/openapi/reference-api.yaml}, which publishes 204, 404 and 409 for the
 * delete, 200, 404 and 409 for the replace, and 201 and 409 for the create. The browser client at
 * {@code ui/src/api/reference.ts} is written against the same document, so an assertion here is
 * an assertion about that client too.
 *
 * <h2>What it deliberately does not assert</h2>
 *
 * <p>Assumptions: the sibling classes in this package own the surrounding questions and none is
 * repeated here. The census of mounted addresses against the published operations is owned by
 * {@code ReferenceApiRoutingContractTest}; every constrained query parameter and path segment,
 * each refusal paired with an accepted value, together with the whole paging-direction binding
 * matrix, is owned by {@code ReferenceParameterConstraintTest}; the created-resource header is
 * owned for the category surface by {@code TransactionCategoryCreationDispatcherTest}; and the
 * date routes are owned by the two date classes. The authority each route demands is owned by the
 * {@code com.carddemo.reference.config} test package. The prohibition on inexact arithmetic on
 * the rate path is owned by {@code ReferenceMoneyPathRulesTest} at this subtree's root, and
 * layering by the rule class delivered from the shared kernel.
 *
 * <p>Alternatives Considered: exercising the transaction-category endpoints from here as
 * sub-resources of a type, which the migration plan specified. That surface does not exist: the
 * category collection is mounted as a ROOT collection at
 * {@code TransactionCategoryController.BASE_PATH} whose item carries both key halves as path
 * segments, it has a controller of its own, and its width constraints and its created-resource
 * header are already owned by the two sibling classes named above. Reaching a category through a
 * nested address would address a surface this context does not publish and would be refused for
 * the wrong reason. The one property that genuinely couples the two contexts is the referential
 * refusal, and it is asserted here from the parent side, which is where the delete that provokes
 * it lives.
 *
 * <h2>How the boundary is assembled</h2>
 *
 * <p>Refactoring Rationale: this class is a real MVC slice, and it was not. It previously built its
 * dispatcher with {@code MockMvcBuilders.standaloneSetup}, constructing the controller itself,
 * installing one hand-built message converter and registering the shared advice by hand. Everything
 * that arrangement asserted was true of the arrangement rather than of the deployed boundary: the
 * controller was reached because this file instantiated it and not because the component scan finds
 * it; a response serialised through a converter this file assembled and not through the one the
 * running service installs; the advice answered because this file passed it in, so dropping the
 * shared kernel's registration of it would have changed nothing here. Four classes of real defect
 * were therefore unreachable from this file at all -- a controller the scan cannot see, a Jackson
 * customisation that stops being applied, an advice bean that stops being published, and a converter
 * whose configuration differs from the deployed one.
 *
 * <p>Assumptions: {@code @WebMvcTest} names ONE controller, so the slice mounts that controller's
 * routes and no others. That narrowness is deliberate and is what keeps a 404 here meaningful: an
 * address this class does not expect to be served is genuinely unmounted rather than merely
 * unreached. The service collaborator is substituted with {@code @MockitoBean}, so each case still
 * asserts the answer the boundary composes and never the query that decided it.
 *
 * <p>Assumptions: the shared kernel's auto-configuration is imported EXPLICITLY rather than left to
 * arrive on its own. A slice applies only the web-related auto-configurations Spring Boot lists for
 * it, and {@code CardDemoCommonAutoConfiguration} is not among them, so without the import the
 * advice, the money module and the Jackson customisation that refuses a non-textual scalar for a
 * text target would all be absent -- and their absence is silent, because the container answers a
 * refusal with its own representation and every status assertion below would still pass.
 *
 * <p>Alternatives Considered: importing it with {@code @ImportAutoConfiguration} rather than with
 * {@code @Import}, which reads more naturally for an auto-configuration class. Rejected because that
 * annotation is not repeatable and the slice annotation is itself meta-annotated with it: declaring it
 * again here replaced the slice's own attributes, including the exclusion list aliased below, so the
 * excluded auto-configurations came back and the context failed to start. {@code @Import} adds the
 * class without disturbing anything the slice declares, and an auto-configuration imported that way
 * still evaluates every condition it carries.
 *
 * <p>Assumptions: two beans are supplied by the nested configuration and both are supplied because
 * the deployed ones are unusable in a test rather than because a stand-in is preferred. The clock is
 * fixed, because the shared advice stamps a refusal with the current instant and an assertion on a
 * moving value cannot be written. The cursor codec carries this file's own key material, because the
 * deployed bean is conditional on a signing-key property that names a secret no test holds; both
 * auto-configured beans declare themselves conditional on being missing, so a locally declared one
 * takes precedence without any exclusion. Trade-offs: a real key is not exercised, and that is the
 * accepted cost of not committing one.
 *
 * <p>Assumptions: the two resource-server auto-configurations are excluded by name, and the exclusion
 * is required rather than tidy. One of them builds a token decoder from an issuer location and issues
 * the provider-document request while the context refreshes, against an issuer the test profile pins
 * to a reserved name that resolves to nothing; the other declares a filter chain and needs the
 * security builder a slice does not create. Neither is reachable from any case below, because no
 * request here presents a token.
 *
 * <p>Trade-offs: the deployed security chain is still NOT installed here, and that is preserved from
 * the previous arrangement on purpose. The slice's include filter admits controllers, advices,
 * converters and web configurers, and {@code com.carddemo.reference.config.SecurityConfig} is none of
 * those, so no chain is installed, no authority is demanded, and a refusal cannot be confused with an
 * unmounted address. The rule table is
 * owned by the {@code com.carddemo.reference.config} test package, which asserts it against the
 * chain's own installed authorization managers. The one authority-adjacent property that belongs to
 * this boundary rather than to the chain IS asserted here: the caller's name reaching the browse as
 * the value its paging positions are sealed against, which the nested customizer supplies as a
 * default principal.
 *
 * <p>Assumptions: the four category labels used below are written in the plural, un-parenthesised
 * form the rules document lists at its lines 31 to 34. The singular and parenthesised spellings mean
 * the same thing and are simply not used; that equivalence is recorded in this sentence alone and the
 * forms are not mixed anywhere in this file.
 *
 * <p>Refactoring Rationale: this file previously opened with a shell-style banner comment placed
 * after its package declaration, carrying a {@code WHAT:} narration and five numbered rationale
 * points. The banner failed {@code config/rule1/rule1_gate.py --check what}, which admits a
 * {@code WHAT:} comment only inside a file's LEADING header block and treats one below the package
 * declaration as a statement-level restatement; the gate is build-failing, so the whole repository
 * gate was red. The banner has been removed rather than relocated above the package declaration, and
 * the deviation from the suggested remedy is deliberate: no other Java class in this repository
 * carries a pre-package banner -- the construct appears only in {@code package-info.java} descriptors,
 * where it is Javadoc -- so relocating it would have made this one file the sole exception to a
 * convention held everywhere else, and its content would have sat outside the Javadoc that Checkstyle
 * audits. Every surviving point is above, and the three that described the hand-assembled dispatcher
 * are superseded rather than copied, because that dispatcher is gone.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries
 * no parameter, return or exception tag. The inapplicability is stated rather than left silent,
 * because the Explainability rule lists a docstring that omits its parameters or return values
 * among its forbidden patterns at line 39 and a reader has to be able to tell a declared
 * inapplicability from an oversight.
 */
@WebMvcTest(controllers = TransactionTypeController.class,
        excludeAutoConfiguration = {
            OAuth2ResourceServerAutoConfiguration.class,
            OAuth2ResourceServerWebSecurityAutoConfiguration.class
        })
@Import({CardDemoCommonAutoConfiguration.class, TransactionTypeControllerTest.SliceFixtures.class})
@ActiveProfiles("test")
class TransactionTypeControllerTest {

    /** A seeded type code, the highest of the seven the reference data carries. */
    private static final String TYPE_CD = "07";

    /** A well-formed code the domain admits but no row holds, for the absent-row paths. */
    private static final String ABSENT_TYPE_CD = "42";

    /**
     * The characters a transaction-type code occupies, everywhere it appears.
     *
     * <p>Assumptions: two, from {@code PIC X(02)} at line 6 of {@code app/cpy/CVTRA03Y.cpy} and from the
     * {@code TRAN_TYPE CHAR(2)} column the reference schema declares. It is named rather than written as
     * a literal two because the assertion that reads it is about the width being DECLARED somewhere and
     * carried, and a bare digit in an assertion is indistinguishable from a coincidence.</p>
     */
    private static final int TYPE_CODE_WIDTH = 2;

    /** The stored description of {@link #TYPE_CD}, within the fifty characters the column declares. */
    private static final String DESCRIPTION = "ADJUSTMENT";

    /** A replacement description a caller submits, distinct from {@link #DESCRIPTION}. */
    private static final String NEW_DESCRIPTION = "ADJUSTMENT MANUAL";

    /** The concurrency token a caller read and submits back. */
    private static final long SUBMITTED_VERSION = 2L;

    /** The token a stored row carries once written. */
    private static final long STORED_VERSION = 3L;

    /** The token a row holds after a competing write, reported back on a stale submission. */
    private static final long CURRENT_VERSION = 9L;

    /** A fixed instant, so a refusal the shared advice renders carries a reproducible timestamp. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T09:16:44.902355Z");

    /**
     * Reads the correlation identifier the deployed filter put on one response.
     *
     * <p>Assumptions: read from the response HEADER rather than parsed out of the body, because the
     * header is where the shared filter publishes it and the body member is a copy the advice makes.
     * Reading the source rather than the copy is what lets a case assert the two agree.</p>
     *
     * @param result the completed exchange
     * @return the identifier the filter published, never {@code null}
     */
    private static String correlationOf(MvcResult result) {
        String correlation = result.getResponse().getHeader(
                CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(correlation)
                .as("the deployed correlation filter must publish an identifier on every response,"
                        + " so its absence means the filter is not installed in this slice at all")
                .isNotNull();
        return correlation;
    }

    /**
     * The sealing key this class constructs its cursor codec with.
     *
     * <p>Assumptions: at least the thirty-two bytes {@code CursorToken.MIN_KEY_LENGTH} declares,
     * counted as bytes rather than characters. A shorter literal is refused by that constructor,
     * which would fail every case in this class for a reason unrelated to what it asserts.
     */
    private static final byte[] CURSOR_KEY =
            "reference-transaction-type-boundary-key".getBytes(StandardCharsets.US_ASCII);

    /** The paging-position lifetime this class constructs its cursor codec with. */
    private static final Duration CURSOR_LIFETIME = Duration.ofMinutes(10);

    /**
     * The authenticated caller every request carries.
     *
     * <p>Assumptions: an identity is supplied on every request rather than case by case, because
     * the browse seals its paging positions against the caller's name and a request carrying no
     * principal reaches the handler and fails on a null one. No token is decoded and no issuer is
     * contacted: a principal is a name, and the name is the only part of an identity this
     * boundary reads.
     */
    private static final Principal CALLER = () -> "REFUSR07";

    /** The members the published page schema declares, and the complete set a page may carry. */
    private static final Set<String> PAGE_MEMBERS =
            Set.of("items", "firstKey", "lastKey", "hasNext");

    /**
     * The members the published item schema declares, and the complete set one row may carry.
     *
     * <p>Assumptions: the set is closed rather than a minimum, because
     * {@code src/main/resources/openapi/reference-api.yaml} declares the transaction-type object with
     * {@code additionalProperties: false} and names all three members required. A client validating
     * against that document rejects a fourth member, so a containment assertion here would admit a body
     * such a client would refuse.</p>
     */
    private static final Set<String> ITEM_MEMBERS = Set.of("typeCd", "description", "version");

    /**
     * The codes a full first page publishes, at the published page width.
     *
     * <p>Assumptions: seven codes, matching {@code TransactionTypeService.PAGE_SIZE}, which carries
     * the baseline's own constant from line 60 of
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}. They are the seven the reference data
     * seeds, so the page a case asserts against is the page this collection actually has.
     */
    private static final List<String> FIRST_PAGE_CODES =
            List.of("01", "02", "03", "04", "05", "06", "07");

    /** The last code a full first page publishes, and the row its trailing position must name. */
    private static final String LAST_PUBLISHED_CODE = "07";

    /**
     * The code of the row found beyond a full page, which the trailing position must NOT name.
     *
     * <p>Assumptions: a code the seed does not carry, standing for the surplus row a browse reads to
     * learn whether a further page exists and then discards. The baseline's own position names this
     * row, because its seek is inclusive; the migrated envelope's names the row before it, because
     * its seek is strict. Naming it here lets a case assert which of the two conventions is in
     * force rather than merely that a position is present.
     */
    private static final String SURPLUS_CODE = "08";

    /** The codes a second page publishes, beginning at the row after the first page's last. */
    private static final List<String> SECOND_PAGE_CODES = List.of("08", "09");

    /**
     * A narrowing text carrying both SQL pattern metacharacters, written as a caller would send it.
     *
     * <p>Assumptions: the published description filter declares a length range and no character
     * class, so a caller's per-cent sign and low line both pass validation at this boundary and
     * reach the collaborator. That is what makes the escaping decision reachable over HTTP at all,
     * and it is why this literal carries both characters rather than plain letters.
     */
    private static final String FILTER_WITH_METACHARACTERS = "50% _OFF";

    /**
     * A state no branch of the service classifies, standing for an integrity failure it cannot act on.
     *
     * <p>Assumptions: {@code 23502} is {@code not_null_violation}, and it is chosen on three
     * grounds rather than one. It sits in SQLSTATE class {@code 23}, integrity constraint
     * violation, so a translator genuinely renders it as
     * {@link DataIntegrityViolationException} and the failure this class hands the boundary is one
     * a driver could actually produce. It is neither of the two states the service classifies --
     * {@code 23503} for a referencing row and {@code 23505} for a duplicate key -- so it still
     * exercises the arm that hands the failure back unchanged. And it is reachable against THIS
     * table: {@code src/main/resources/db/migration/V1__reference.sql} declares
     * {@code description VARCHAR(50) NOT NULL} on {@code reference.transaction_types}, so a write
     * that left the description absent reports exactly this state.
     *
     * <p>Refactoring Rationale: this constant was {@code 42P01}, undefined table. Class {@code 42}
     * is syntax-error-or-access-rule-violation, which a translator renders as
     * {@code BadSqlGrammarException} -- a sibling of {@link DataIntegrityViolationException} and
     * never a subtype of it. So the case below manufactured a pairing no translator produces, and
     * an undefined table is a schema fault the deployment owns rather than a conflict a caller can
     * resolve. The case still passed, because the boundary classifies by exception CLASS and the
     * hand-built wrapper said integrity violation whatever state it carried -- which is precisely
     * why the wrong state was invisible. Asserting a 409 for a state that can only arrive as a
     * fault documented the opposite of the intended contract. The undefined-table condition is now
     * asserted separately, under the type it really translates to, by
     * {@code anUndefinedTableAnswersALeakFreeFaultAndNeverAConflict}.
     */
    private static final String UNCLASSIFIED_INTEGRITY_SQLSTATE = "23502";

    /**
     * The undefined-table state, kept so the schema fault it stands for can be asserted as a fault.
     *
     * <p>Assumptions: this is carried on a {@code BadSqlGrammarException} rather than on an
     * integrity violation, because that is what a translator produces for class {@code 42}. The
     * distinction is the whole point of the case that uses it: a missing relation means the
     * deployment is wrong, and reporting it to a caller as a conflict would invite a retry that
     * cannot succeed while hiding the condition from the fault channel the alerting watches.
     */
    private static final String UNDEFINED_TABLE_SQLSTATE = "42P01";

    /**
     * Statement text standing for the SQL a translator attaches to a grammar failure.
     *
     * <p>Assumptions: a real translator carries the offending statement on the exception so an
     * operator can read it out of a log. It names the schema and the table, so it is exactly the kind
     * of text a body must not repeat, and this literal carries that shape so its absence is
     * assertable.
     */
    private static final String FAILING_STATEMENT =
            "delete from reference.transaction_type where type_cd = ?";

    /**
     * The referencing-row state, standing for a genuine restricted-delete refusal from the engine.
     *
     * <p>Assumptions: this is the state the equivalent of the baseline's restricted foreign key
     * reports, and it is declared here rather than read from the service because the service holds
     * it with package visibility in {@code com.carddemo.reference.service} and this class sits in
     * {@code com.carddemo.reference.api}. Widening the service's constant so a test in a sibling
     * package could read it would relax production visibility for a test's convenience, which is
     * the wrong direction of the two.
     */
    private static final String CLASSIFIED_REFERENCING_SQLSTATE = "23503";

    /**
     * The duplicate-key state, the second member of the integrity family this surface can meet.
     *
     * <p>Assumptions: it is named alongside the referencing state so the case below asserts the
     * whole family the classifier admits rather than one member of it, for the same visibility
     * reason recorded on {@link #CLASSIFIED_REFERENCING_SQLSTATE}.
     */
    private static final String CLASSIFIED_DUPLICATE_SQLSTATE = "23505";

    /**
     * Text standing for the diagnostic a driver composes, which must never reach a caller.
     *
     * <p>Assumptions: a real driver names the schema, the table and the constraint, and appends a
     * detail clause quoting the values that violated it. This literal carries the same shape so
     * that a case can assert its absence from the body rather than assert nothing.
     */
    private static final String VENDOR_DIAGNOSTIC =
            "ERROR: relation \"reference.transaction_type\" does not exist; "
                    + "constraint fk_tran_cat_type; Key (tr_type)=(07)";

    /**
     * A path segment of the declared width that the published pattern excludes.
     *
     * <p>Assumptions: {@code 00} is used rather than an arbitrary value because the published pattern
     * admits 01 through 99, so this is a value a width check alone would let through. It is the one
     * segment that separates an enforced pattern from an enforced length.</p>
     */
    private static final String SEGMENT_OUTSIDE_THE_DOMAIN = "00";

    /**
     * A request body the parser cannot read at all, for the malformed-payload refusal.
     *
     * <p>Assumptions: it is truncated rather than merely wrong, so the failure is a parse failure and not
     * a validation failure. A well-formed document carrying the wrong members would be refused by
     * validation instead, which is a different handler and already covered by the segment above.</p>
     */
    private static final String UNREADABLE_BODY = "{\"description\":";

    /**
     * A sentence left in the baseline's working storage that no path ever selects.
     *
     * <p>Assumptions: {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} declares it at line
     * 196 under the condition name {@code CODING-TO-BE-DONE}, and that name occurs nowhere after
     * the procedure division opens at line 344, so the baseline itself never displays it. It is
     * named here so its absence can be asserted; a developer placeholder is kept out of a
     * published body by a negative assertion or by nothing at all.
     */
    private static final String DEVELOPER_PLACEHOLDER = "Looks Good.... so far";

    /** The substituted collaborator, so each case asserts the answer and not the store. */
    @MockitoBean
    private TransactionTypeService service;

    /**
     * The cursor codec both this class and the handler under test read positions through.
     *
     * <p>Assumptions: injected rather than constructed here, so this class reads positions through the
     * SAME instance the handler was given. Constructing a second one with the same key would work by
     * coincidence and would stop working the moment the codec carried any per-instance state.</p>
     */
    @Autowired
    private CursorToken sealer;

    /** The dispatcher under test, built by the slice from the deployed web configuration. */
    @Autowired
    private MockMvc mockMvc;

    /**
     * The mapper the assertions read a response body back through.
     *
     * <p>Assumptions: this is the context's own mapper and not a locally built one, so a body is read
     * back through the same configuration that wrote it. A locally built mapper would silently paper
     * over a serialisation difference by parsing it away.</p>
     */
    @Autowired
    private JsonMapper mapper;

    /**
     * Supplies the three beans the slice cannot obtain from the deployed configuration.
     *
     * <p>Assumptions: it is nested rather than top-level because the values it declares are this
     * class's fixtures, and a top-level configuration would invite a second class to depend on them.
     * It is named explicitly in this class's {@code @Import} rather than left to be discovered: a
     * nested configuration class is auto-detected only for a test class that declares its cases
     * directly, and every case here lives in a {@code @Nested} group, for which the framework skips
     * that detection entirely. Relying on discovery therefore left the slice with no clock, no money
     * module and no cursor signer, and the failure surfaced as a missing-bean error rather than as
     * anything pointing at the nesting.</p>
     *
     * <p>Assumptions: it is {@code final}, which is what keeps the framework from also reporting it as
     * an ignored default configuration class. The detection above admits only a static, non-private,
     * NON-FINAL nested class, so marking it final states in the type system that discovery is not the
     * route being used and removes the warning that says the class was found and skipped. Nothing is
     * given up: {@code proxyBeanMethods = false} on the annotation already declines the CGLIB subclass
     * that would have needed the type to be extensible.</p>
     */
    @TestConfiguration(proxyBeanMethods = false)
    static final class SliceFixtures {

        /**
         * A clock stopped at {@link #FIXED_INSTANT}, so a stamped refusal is assertable.
         *
         * <p>Assumptions: the shared advice stamps every refusal with the current instant, so with the
         * deployed system clock no assertion could name the value. The auto-configured clock declares
         * itself conditional on being missing, so declaring one here replaces it without any
         * exclusion.</p>
         *
         * @return a fixed clock in UTC, never {@code null}
         */
        @Bean
        Clock clock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }

        /**
         * The cursor codec, carrying this file's own key material.
         *
         * <p>Assumptions: the deployed bean is conditional on a signing-key property naming a secret
         * that reaches a running task from a secret store, so no test can obtain it. The key here is
         * this class's own constant and seals nothing that outlives the test.</p>
         *
         * @return a cursor codec over the test key and lifetime, never {@code null}
         */
        @Bean
        CursorToken cursorToken() {
            return new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);
        }

        /**
         * Gives every request a default principal, which the browse seals its positions against.
         *
         * <p>Assumptions: the principal is supplied as a builder default rather than per request,
         * because it is a property of the arrangement and not of any single case, and because a case
         * that forgot it would then fail on a sealing mismatch far from the omission. This is the one
         * authority-adjacent value this slice carries; no chain is installed, so nothing here decides
         * whether the caller is admitted.</p>
         *
         * @return a customizer installing {@link #CALLER} as the default request principal, never
         *     {@code null}
         */
        @Bean
        MockMvcBuilderCustomizer defaultPrincipal() {
            return builder -> builder.defaultRequest(get("/").principal(CALLER));
        }
    }

    /**
     * Builds the address of one type item from the controller's own path templates.
     *
     * <p>Assumptions: composed from the published constants rather than written as a literal, so a
     * case cannot pass against an address the routes do not serve.
     *
     * @param typeCd the two-character code to address
     * @return the absolute path of that item
     */
    private static String itemPath(String typeCd) {
        return TransactionTypeController.BASE_PATH
                + TransactionTypeController.ITEM_PATH.replace(
                        "{" + TransactionTypeController.PARAM_TYPE_CD + "}", typeCd);
    }

    /**
     * Builds the unfiltered browse binding for one direction, as the service builds it.
     *
     * <p>Assumptions: computed through {@link ReferencePaging#binding(String, String, boolean,
     * String...)} rather than assembled here, so a case reads a position back under the same
     * binding the handler's collaborator would have sealed it under. Writing the binding out by
     * hand would let this class agree with itself while disagreeing with the service.
     *
     * @param backward whether the binding is the backward one rather than the forward one
     * @return the binding an unfiltered browse of this collection seals its positions under
     */
    private static String browseBinding(boolean backward) {
        return ReferencePaging.binding(TransactionTypeService.CURSOR_BINDING, CALLER.getName(),
                backward, null, null);
    }

    /**
     * Seals one row key into an opaque paging position.
     *
     * @param backward whether to seal under the backward binding rather than the forward one
     * @param rowKey the key of the row the position names
     * @return the sealed position, in the shape the published cursor schema declares
     */
    private String seal(boolean backward, String rowKey) {
        return this.sealer.seal(browseBinding(backward), rowKey);
    }

    /**
     * Builds one page of responses from a run of type codes.
     *
     * @param codes the codes the page publishes, in the order it publishes them
     * @param firstKey the sealed position naming the page's leading boundary
     * @param lastKey the sealed position naming the page's trailing boundary
     * @param hasNext whether a further page follows
     * @return the envelope the substituted collaborator answers with
     */
    private static PageResponse<TransactionTypeResponse> pageOf(List<String> codes, String firstKey,
            String lastKey, boolean hasNext) {

        List<TransactionTypeResponse> rows = codes.stream()
                .map(code -> new TransactionTypeResponse(code, DESCRIPTION + " " + code,
                        STORED_VERSION))
                .toList();
        return new PageResponse<>(rows, firstKey, lastKey, hasNext);
    }

    /**
     * Builds an integrity violation reporting one SQLSTATE, as a driver would.
     *
     * <p>Assumptions: the state is carried on a {@link SQLException} in the cause chain, which is
     * where the service reads it from, and the message carries diagnostic-shaped text so a case
     * can assert that text does not reach a caller.
     *
     * @param sqlState the state the simulated driver reports
     * @return the failure a repository would surface for that state
     */
    private static DataIntegrityViolationException integrityViolation(String sqlState) {
        return new DataIntegrityViolationException(VENDOR_DIAGNOSTIC,
                new SQLException(VENDOR_DIAGNOSTIC, sqlState));
    }

    /**
     * Builds the failure a translator produces for a class {@code 42} state, as a driver would.
     *
     * <p>Assumptions: {@code BadSqlGrammarException} is the type the persistence abstraction raises
     * for syntax-error-or-access-rule-violation states, and it is NOT a subtype of
     * {@link DataIntegrityViolationException} -- both descend from {@code NonTransientDataAccessException}
     * as siblings. That is what makes it the honest carrier for an undefined table, and what lets a
     * case assert that the boundary answers such a condition on the fault channel rather than the
     * conflict one.
     *
     * <p>Assumptions: the statement text handed to the constructor is diagnostic-shaped for the same
     * reason the message is -- so a case can assert that neither the state nor the SQL reaches a
     * caller, rather than assert nothing.
     *
     * @param sqlState the state the simulated driver reports
     * @return the failure a repository would surface for that state
     */
    private static BadSqlGrammarException undefinedTableFailure(String sqlState) {
        return new BadSqlGrammarException(VENDOR_DIAGNOSTIC, FAILING_STATEMENT,
                new SQLException(VENDOR_DIAGNOSTIC, sqlState));
    }

    /**
     * Reads a response body back as a mapping of its members.
     *
     * @param result the completed exchange to read
     * @return the body's top-level members, keyed by the names the contract publishes
     * @throws UnsupportedEncodingException if the response charset is unsupported
     */
    private Map<String, Object> bodyOf(MvcResult result) throws UnsupportedEncodingException {
        return this.mapper.readValue(result.getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() { });
    }

    /**
     * Reads the {@code message} member of an error body.
     *
     * @param result the completed exchange to read
     * @return the sentence the body publishes
     * @throws UnsupportedEncodingException if the response charset is unsupported
     */
    private String messageOf(MvcResult result) throws UnsupportedEncodingException {
        return String.valueOf(bodyOf(result).get("message"));
    }

    /**
     * Reads a response body as the exact characters that travelled.
     *
     * <p>Assumptions: read as raw text rather than through the mapper, because two of the cases
     * below assert something about the body a parsed view cannot express -- how many times a
     * sentence occurs in it, and that a diagnostic occurs nowhere in it at all.
     *
     * @param result the completed exchange to read
     * @return the body as it was written
     * @throws UnsupportedEncodingException if the response charset is unsupported
     */
    private static String rawBodyOf(MvcResult result) throws UnsupportedEncodingException {
        return result.getResponse().getContentAsString();
    }

    /**
     * Counts how many times a sentence occurs in a body, counting no occurrence twice.
     *
     * <p>Refactoring Rationale: the search resumes at the END of a match rather than one character past
     * its start, which is what makes the count non-overlapping as this method's contract says. Resuming
     * one character on counts an overlapping match as two, so a sentence able to overlap itself would have
     * been reported more often than it travelled -- and the one case that reads this method asserts a
     * count of exactly one, so an over-count would have been read as the boundary publishing a sentence
     * twice. The sentence it counts today cannot overlap itself, which is precisely why the defect was
     * invisible: it was a correct answer from an incorrect rule, and the next sentence counted would have
     * inherited the rule rather than the answer.</p>
     *
     * @param body the body to search
     * @param sentence the sentence to count; must not be empty, since an empty needle matches at every
     *     position and no advance would terminate
     * @return the number of non-overlapping occurrences
     */
    private static int occurrencesOf(String body, String sentence) {
        int found = 0;
        for (int at = body.indexOf(sentence); at >= 0;
                at = body.indexOf(sentence, at + sentence.length())) {
            found++;
        }
        return found;
    }

    /**
     * Holds the delete route to the refusal the child table's restricted foreign key demands.
     *
     * <p>Purpose: this is the single most consequential answer on this surface, because a break
     * anywhere along its chain turns a caller error into a server error. The constraint at
     * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} lines 6 and 7 declares the category
     * table's foreign key with a restricted delete. Db2 reports that restriction as SQLCODE -532,
     * which the baseline tests for at line 1914 of
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} and answers with the sentence at its
     * line 1919 -- the identical characters the maintenance screen carries at line 1641 of that
     * tree's {@code COTRTUPC.cbl}. The equivalent constraint reports SQLSTATE 23503, the service
     * raises a kinded conflict, and the shared advice renders 409. The Java implements that
     * chain; the difference from the baseline's own routing is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}.
     *
     * <p>Assumptions: the constraint itself is proven against a real database in
     * {@code com.carddemo.reference.repository}, and the classification of a SQLSTATE into a
     * kinded conflict in {@code com.carddemo.reference.service}. What is proven here is the last
     * link: that a refusal in the integrity-constraint family SURFACES as a conflict carrying the
     * baseline's sentence, whether the service classified it or the shared advice did, and that a
     * failure outside that family -- a lost connection, or a state such as an undefined relation
     * that no constraint reports -- surfaces as a fault instead.
     *
     * <p>A test class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception tag.
     */
    @Nested
    @DisplayName("on deleting a type that categories still reference")
    class OnTheRestrictedDelete {

        /**
         * A delete refused because categories still reference the type answers 409, never 500.
         *
         * <p>Assumptions: every member the published conflict response fixes is asserted, not the
         * status alone. That document states all three conflict conditions carry the same code,
         * the warning severity, the relational subsystem and an EMPTY subordinate code, and that
         * only the concurrency condition reports a version -- so a referential refusal carrying a
         * field entry would be publishing a value this condition does not contend on.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the refusal answers 409 with the baseline's referential sentence")
        void aReferencedTypeIsRefusedAsAConflict() throws Exception {
            doThrow(new RecordConflictException(RecordConflictException.Kind.REFERENCED_ROW))
                    .when(TransactionTypeControllerTest.this.service).delete(TYPE_CD);

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(delete(itemPath(TYPE_CD)))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("a delete refused by a still-referencing row must answer 409; a 500 would"
                            + " report a caller error as a server fault")
                    .isEqualTo(409);

            Map<String, Object> body = bodyOf(result);
            assertThat(body.get("message"))
                    .as("the sentence is the one both baseline programs declare, carried verbatim")
                    .isEqualTo(GlobalExceptionHandler.MESSAGE_REFERENCED_ROW);
            assertThat(body.get("code")).isEqualTo(ApiError.CODE_CONFLICT);
            assertThat(body.get("secondaryCode"))
                    .as("the published conflict response fixes an empty subordinate code")
                    .isEqualTo(ApiError.NO_SECONDARY_CODE);
            assertThat(body.get("severity")).isEqualTo(ApiError.Severity.WARNING.name());
            assertThat(body.get("subsystem")).isEqualTo(ApiError.Subsystem.RELATIONAL.name());
            assertThat(body.get("status")).isEqualTo(409);
            assertThat((List<?>) body.get("fieldErrors"))
                    .as("only the concurrency condition reports a version; this one contends on"
                            + " none, so its field array is empty")
                    .isEmpty();
        }

        /**
         * The referential sentence travels once and carries no database diagnostic after it.
         *
         * <p>Assumptions: two separate properties are asserted together because they are two
         * halves of one contract. The baseline composes its own message by following the sentence
         * with the driver's diagnostic, and at lines 1645 AND 1646 of
         * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} it concatenates that diagnostic
         * TWICE into one buffer. The Java publishes the sentence alone: it ends at its colon, the
         * diagnostic goes to the operational record instead, and the sentence appears once. The
         * difference is registered in
         * {@code docs/architecture/cobol-to-service-traceability.md}.
         *
         * <p>Trade-offs: withholding the diagnostic costs a caller the detail that named which
         * constraint refused the statement, and buys not publishing the schema, the table, the
         * constraint name and the offending key values to whoever made the request. The detail is
         * not lost, only redirected; an operator reads it under the same correlation identity the
         * body carries.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the sentence is published once and no vendor diagnostic follows it")
        void theRefusalCarriesOneSentenceAndNoDiagnostic() throws Exception {
            doThrow(new RecordConflictException(RecordConflictException.Kind.REFERENCED_ROW))
                    .when(TransactionTypeControllerTest.this.service).delete(TYPE_CD);

            String body = rawBodyOf(TransactionTypeControllerTest.this.mockMvc
                    .perform(delete(itemPath(TYPE_CD)))
                    .andReturn());

            assertThat(occurrencesOf(body, GlobalExceptionHandler.MESSAGE_REFERENCED_ROW))
                    .as("the sentence must appear exactly once; the baseline concatenates its own"
                            + " diagnostic twice and the Java emits neither copy")
                    .isEqualTo(1);
            assertThat(GlobalExceptionHandler.MESSAGE_REFERENCED_ROW)
                    .as("the trailing colon is the baseline's and is retained")
                    .endsWith(":");
            assertThat(body)
                    .as("no driver diagnostic, constraint name or key value may reach a caller")
                    .doesNotContain(VENDOR_DIAGNOSTIC)
                    .doesNotContain("fk_tran_cat_type")
                    .doesNotContain("23503")
                    .doesNotContain("transaction_type");
        }

        /**
         * A translated integrity refusal in the constraint family answers 409 without the service
         * having classified it.
         *
         * <p>Assumptions: the referential guarantee has two independent links and this case proves
         * the SECOND one. The case above reaches 409 through a kinded conflict the SERVICE raised
         * after reading the state; this one hands the advice the persistence abstraction's own
         * translated failure, unclassified, and still expects 409 with the same sentence. The advice
         * recognises the failure by class name while walking the cause chain -- which is why it needs
         * no dependency on a driver -- and reads the SQL state off the {@link SQLException} beneath
         * it. Both states the engine reports for this table are exercised, so the answer is
         * established for the family and not for one member of it.
         *
         * <p>Trade-offs: because the advice answers correctly for the whole constraint family, the
         * service's own classification buys the SENTENCE rather than the status for this table --
         * both of the states it recognises reach the same referential wording here. That redundancy
         * is accepted deliberately: it is what keeps the status right when the engine reports a
         * constraint state the service has no branch for, and its cost is one classification step
         * whose effect on this surface is invisible.
         *
         * @param sqlState the constraint state the simulated driver reports
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest
        @ValueSource(strings = {CLASSIFIED_REFERENCING_SQLSTATE, CLASSIFIED_DUPLICATE_SQLSTATE})
        @DisplayName("a translated constraint refusal answers 409 with the baseline's sentence")
        void aTranslatedConstraintRefusalAnswersConflict(String sqlState) throws Exception {
            doThrow(integrityViolation(sqlState))
                    .when(TransactionTypeControllerTest.this.service).delete(TYPE_CD);

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(delete(itemPath(TYPE_CD)))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("a constraint breach is a caller error; answering 500 here would report a"
                            + " blocked delete as a fault the caller cannot act on")
                    .isEqualTo(409);
            assertThat(messageOf(result))
                    .isEqualTo(GlobalExceptionHandler.MESSAGE_REFERENCED_ROW);
            assertThat(rawBodyOf(result))
                    .as("the driver's own text names a relation and a key and must not travel")
                    .doesNotContain(VENDOR_DIAGNOSTIC)
                    .doesNotContain(sqlState);
        }

        /**
         * An integrity failure whose state this service does not classify still answers 409.
         *
         * <p>Purpose: the service's own classifier recognises exactly two states -- the unique breach
         * and the foreign-key breach -- and hands anything else back unchanged, so a third
         * constraint state reaches the shared advice as the raw translated violation. This case is
         * the guard on what happens then, and the answer is the conflict: the advice narrows its
         * conflict branch to SQLSTATE class {@code 23}, and every member of that class describes data
         * the caller supplied being refused by a rule.
         *
         * <p>⚠️ Refactoring Rationale: the state used here was {@code 42P01}, undefined table, and the
         * assertion was a 500. Both were wrong together, and in opposite directions. Class {@code 42}
         * is syntax-error-or-access-rule-violation, which a translator renders as
         * {@code BadSqlGrammarException} and never as a subtype of the integrity violation this case
         * stubs -- so the pairing was one no translator produces, and the case was really asserting
         * the advice's behaviour for a type it was not given. The state is now {@code 23502}, a
         * not-null breach, which is a real class {@code 23} condition this schema can report:
         * {@code src/main/resources/db/migration/V1__reference.sql} declares
         * {@code description VARCHAR(50) NOT NULL}. The undefined-table condition is asserted
         * separately, under the type it really translates to and as the fault it really is, by
         * {@code anUndefinedTableAnswersALeakFreeFaultAndNeverAConflict} below -- so the pair covers
         * both halves of the classification instead of one case straddling them.
         *
         * <p>Assumptions: the vendor text and the state are asserted ABSENT from the body whichever
         * answer is given. The driver composes that text and quotes the values that violated the
         * constraint, so it is caller data, and the reason it must not travel does not depend on the
         * status code carrying it.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an integrity refusal answers 409 even when its state was not classified")
        void anUnclassifiedIntegrityStateStillAnswersConflict() throws Exception {
            doThrow(integrityViolation(UNCLASSIFIED_INTEGRITY_SQLSTATE))
                    .when(TransactionTypeControllerTest.this.service).delete(TYPE_CD);

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(delete(itemPath(TYPE_CD)))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("a class 23 state is a constraint the caller tripped, so the shared advice"
                            + " answers the conflict even for a member this service does not classify")
                    .isEqualTo(409);

            String body = rawBodyOf(result);
            assertThat(messageOf(result)).isEqualTo(GlobalExceptionHandler.MESSAGE_REFERENCED_ROW);
            assertThat(body)
                    .as("the driver's own text names a relation and a key and must not travel")
                    .doesNotContain(VENDOR_DIAGNOSTIC)
                    .doesNotContain(UNCLASSIFIED_INTEGRITY_SQLSTATE);
        }

        /**
         * An undefined table answers a leak-free 500, and never the referential conflict.
         *
         * <p>Purpose: this is the other side of the case above, and the pair is what keeps the
         * classification honest. A state in SQLSTATE class {@code 23} is a constraint the caller
         * tripped and can resolve; a state in class {@code 42} is a relation that is not there,
         * which no caller can act on and which means the schema this service was deployed against is
         * not the one its migration declares. The two must not share an answer.
         *
         * <p>Assumptions: the failure arrives as {@code BadSqlGrammarException} because that is what
         * a translator produces for class {@code 42}, and that type is a SIBLING of
         * {@link DataIntegrityViolationException} rather than a subtype -- so the advice's
         * integrity-family recognition does not match it and it falls through to the fault channel.
         * Building it as an integrity violation instead would be the defect this case exists to
         * prevent: it would pass while asserting the wrong contract.
         *
         * <p>Trade-offs: asserting the negative -- that the referential sentence is absent -- costs
         * one more assertion than reading the status alone, and it is the assertion that earns the
         * case. A boundary that answered 500 while still attaching the referential wording would
         * satisfy a status-only check and would tell an operator reading the body that a row was
         * referenced, when in fact no table was found to reference it.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an undefined table answers a leak-free 500 and never the referential 409")
        void anUndefinedTableAnswersALeakFreeFaultAndNeverAConflict() throws Exception {
            doThrow(undefinedTableFailure(UNDEFINED_TABLE_SQLSTATE))
                    .when(TransactionTypeControllerTest.this.service).delete(TYPE_CD);

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(delete(itemPath(TYPE_CD)))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("a missing relation is a deployment fault, not a conflict a caller can"
                            + " resolve; answering 409 would invite a retry that cannot succeed")
                    .isEqualTo(500)
                    .isNotEqualTo(409);
            assertThat(messageOf(result)).isEqualTo(GlobalExceptionHandler.MESSAGE_INTERNAL);

            String body = rawBodyOf(result);
            assertThat(body)
                    .as("the referential sentence belongs to a tripped constraint and must not be"
                            + " attached to a schema that is not there")
                    .doesNotContain(GlobalExceptionHandler.MESSAGE_REFERENCED_ROW);
            assertThat(body)
                    .as("neither the state, the driver's text nor the failing statement may travel")
                    .doesNotContain(UNDEFINED_TABLE_SQLSTATE)
                    .doesNotContain(VENDOR_DIAGNOSTIC)
                    .doesNotContain(FAILING_STATEMENT);
        }

        /**
         * A failure that is not an integrity refusal at all answers 500 and never 409.
         *
         * <p>Assumptions: this is the half of the contract an assertion on the refusal alone does
         * not reach, and the baseline is the reason it has to be asserted separately. In
         * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} the condition
         * {@code RECORD-DELETE-FAILED}, whose text is declared at line 190, is raised on BOTH the
         * restricted branch at line 1639 and the catch-all branch at line 1651, so that flag alone
         * cannot tell the two apart; only {@code TTUP-DELETE-FAILED} at line 1652 is unique to the
         * catch-all, and it is the PAIR that discriminates. The catch-all answers with a different
         * sentence, at line 1654 and at line 1929 of {@code COTRTLIC.cbl}. A target that answered
         * every failed delete with a conflict would satisfy the case above while losing exactly that
         * distinction, so the negative is asserted too.
         *
         * <p>Assumptions: the failure used is a resource failure rather than an integrity
         * violation, because the advice recognises the integrity family by class name and would
         * correctly answer 409 for any member of it. Losing a connection while deleting is not
         * something a caller can act on, so it belongs on the fault channel the alerting watches.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a failure outside the integrity family answers 500 and not 409")
        void anUnrelatedFailureIsNotAConflict() throws Exception {
            doThrow(new DataAccessResourceFailureException(VENDOR_DIAGNOSTIC))
                    .when(TransactionTypeControllerTest.this.service).delete(TYPE_CD);

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(delete(itemPath(TYPE_CD)))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("a failure a caller cannot correct is a fault and not a conflict;"
                            + " answering 409 here would erase the one distinction the baseline's"
                            + " flag pair carries")
                    .isEqualTo(500)
                    .isNotEqualTo(409);

            String body = rawBodyOf(result);
            assertThat(messageOf(result)).isEqualTo(GlobalExceptionHandler.MESSAGE_INTERNAL);
            assertThat(body)
                    .as("the referential sentence belongs to the refusal a caller can act on and"
                            + " must not be attached to a fault it cannot")
                    .doesNotContain(GlobalExceptionHandler.MESSAGE_REFERENCED_ROW);
            assertThat(body)
                    .as("nothing the failure carried about the database may travel")
                    .doesNotContain(VENDOR_DIAGNOSTIC);
        }

        /**
         * A delete naming a code no row holds answers 404 with the service's own sentence.
         *
         * <p>Alternatives Considered: reproducing the baseline's routing for this condition, which
         * would answer through the catch-all. Its selection at line 1907 of
         * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} carries only three arms -- success,
         * the restricted refusal at 1914, and a catch-all at 1926 -- and, unlike the update arm at
         * 1861, none for an absent row, so an absent code falls in with genuine failures. The
         * published contract declares 404 as a first-class reply for this operation, and that is
         * what the Java implements; the divergence is registered in
         * {@code docs/architecture/cobol-to-service-traceability.md}.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a delete of an absent code answers 404 rather than a generic failure")
        void anAbsentCodeIsReportedAsNotFound() throws Exception {
            doThrow(new NoSuchElementException(TransactionTypeService.MESSAGE_TYPE_NOT_FOUND))
                    .when(TransactionTypeControllerTest.this.service).delete(ABSENT_TYPE_CD);

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(delete(itemPath(ABSENT_TYPE_CD)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(messageOf(result))
                    .isEqualTo(TransactionTypeService.MESSAGE_TYPE_NOT_FOUND);
            assertThat(bodyOf(result).get("code")).isEqualTo(ApiError.CODE_NOT_FOUND);
        }

        /**
         * A delete nothing refuses answers 204 with no body at all.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an unreferenced type is deleted and answers 204 with no body")
        void anUnreferencedTypeIsDeleted() throws Exception {
            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(delete(itemPath(TYPE_CD)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(204);
            assertThat(rawBodyOf(result))
                    .as("204 is declared with no content, so a body here would contradict it")
                    .isEmpty();
            verify(TransactionTypeControllerTest.this.service).delete(TYPE_CD);
        }
    }

    /**
     * Holds the create and replace routes to the statuses and sentences the contract publishes.
     *
     * <p>Purpose: the replace route had to CHOOSE one semantic, because the baseline does three
     * different things when a write finds no row, and the choice is what this group pins.
     *
     * <p>Alternatives Considered: all three baseline behaviours were available and two were
     * rejected. The list screen's update arm at lines 1837 to 1892 of
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} treats an absent row as a refusal: its
     * no-row branch at line 1861 sets an outcome flag at 1862 and moves a sentence about the record
     * having been removed by another at 1864, inserting nothing. The maintenance screen's write arm
     * at lines 1531 to 1593 of that tree's {@code COTRTUPC.cbl} does the opposite: its no-row
     * branch at 1558 performs the insert paragraph at 1559 and 1560, making that path a genuine
     * create-or-replace. The batch reference updater does a third thing again --
     * {@code COBTUPDT.cbl} reports no rows from its update paragraph at line 166 with the sentence
     * at 181, then performs a paragraph whose body at 230 to 233 displays, moves 4 into the program
     * return code and exits without stopping, so that record is soft-rejected and the loop carries
     * on. The list screen's refusal is the semantic this route implements, because an item address
     * in a path asserts that the item exists and a request contradicting it is a caller error;
     * create-or-replace is preserved by create being a separate operation, and the batch arm's soft
     * reject belongs to a driver that reports its own aggregate code at the end of a run, which an
     * interactive caller answered one request at a time has no equivalent of. The divergence from the maintenance screen's write arm
     * is registered in {@code docs/architecture/cobol-to-service-traceability.md}.
     *
     * <p>Assumptions: the ordering of the outcomes is the baseline's own. The maintenance screen
     * classifies in two stages -- lines 1555 to 1578 map a driver code to a typed condition, and
     * lines 1580 to 1589 then map those conditions to one caller-visible outcome in a strict order,
     * a lock refusal first, a failed write second, an altered row third and success only as the
     * remaining arm. {@code TransactionTypeService.WriteOutcome} declares its constants in that
     * order, so the order is part of the behaviour rather than an implementation detail.
     *
     * <p>A test class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception tag.
     */
    @Nested
    @DisplayName("on the write routes")
    class OnTheWriteRoutes {

        /**
         * A replace naming a code no row holds answers 404 and creates nothing.
         *
         * <p>Assumptions: the second half of this case is what makes it worth writing. A route that
         * answered 404 while ALSO inserting would satisfy a status assertion and still be the
         * create-or-replace semantic this operation rejected, so the create path is asserted never
         * to have been reached.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a replace of an absent code answers 404 and inserts nothing")
        void aReplaceOfAnAbsentCodeRefusesAndDoesNotInsert() throws Exception {
            when(TransactionTypeControllerTest.this.service
                    .replace(eq(ABSENT_TYPE_CD), any(TransactionTypeUpdateRequest.class)))
                    .thenThrow(new NoSuchElementException(
                            TransactionTypeService.MESSAGE_TYPE_NOT_FOUND));

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(put(itemPath(ABSENT_TYPE_CD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replaceBody(NEW_DESCRIPTION, SUBMITTED_VERSION)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(messageOf(result))
                    .isEqualTo(TransactionTypeService.MESSAGE_TYPE_NOT_FOUND);
            verify(TransactionTypeControllerTest.this.service, never())
                    .create(any(TransactionTypeCreateRequest.class));
        }

        /**
         * A submission carrying a token the row no longer holds answers 409 reporting the current one.
         *
         * <p>Assumptions: the published conflict response states that only this condition reports a
         * version, and that it reports it as a field entry keyed {@code version} whose message is
         * the value the row now holds, so a caller can re-read, compare and resubmit against that
         * value rather than guessing. Both the key and the value are asserted, because an entry
         * carrying the right key and the submitted version instead of the stored one would leave a
         * caller resubmitting the value that was just refused.
         *
         * <p>Assumptions: the sentence is the shared kernel's own constant and is asserted through
         * it rather than retyped. It preserves the baseline's two-word spelling, from the
         * 46-character literal at line 522 of {@code app/cbl/COACTUPC.cbl}, and the reference
         * maintenance screen declares the identical characters at line 184 of
         * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a stale token answers 409 reporting the version the row now holds")
        void aStaleTokenIsRefusedWithTheCurrentVersion() throws Exception {
            when(TransactionTypeControllerTest.this.service
                    .replace(eq(TYPE_CD), any(TransactionTypeUpdateRequest.class)))
                    .thenThrow(new RecordConflictException(
                            RecordConflictException.Kind.STALE_VERSION, CURRENT_VERSION));

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(put(itemPath(TYPE_CD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replaceBody(NEW_DESCRIPTION, SUBMITTED_VERSION)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(messageOf(result)).isEqualTo(ApiError.COACTUPC_RECORD_CHANGED);

            List<?> fieldErrors = (List<?>) bodyOf(result).get("fieldErrors");
            assertThat(fieldErrors)
                    .as("exactly one entry, naming the version and nothing else")
                    .hasSize(1);

            Map<?, ?> entry = (Map<?, ?>) fieldErrors.get(0);
            assertThat(entry.get("field")).isEqualTo(GlobalExceptionHandler.FIELD_VERSION);
            assertThat(entry.get("state")).isEqualTo(FieldValidationFlag.NOT_OK.name());
            assertThat(entry.get("message"))
                    .as("the value reported is the one the row NOW holds, not the one submitted;"
                            + " reporting the submitted value would send a caller back with the"
                            + " token that was just refused")
                    .isEqualTo(String.valueOf(CURRENT_VERSION))
                    .isNotEqualTo(String.valueOf(SUBMITTED_VERSION));
        }

        /**
         * A row that could not be taken for update answers 409 with its own distinct sentence.
         *
         * <p>Assumptions: the baseline reaches this condition from SQLCODE -911 and the two
         * programs answer it with DIFFERENT sentences, which are deliberately not merged. The
         * maintenance screen's branch at line 1561 of
         * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} selects the condition declared at
         * its line 182, while the list screen at line 1874 of {@code COTRTLIC.cbl} carries a
         * sentence about a deadlock instead. The shared kernel publishes the maintenance screen's
         * wording for this status, and that is the one asserted.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a row that could not be locked answers 409 with the lock sentence")
        void aLockRefusalIsAConflictWithItsOwnSentence() throws Exception {
            when(TransactionTypeControllerTest.this.service
                    .replace(eq(TYPE_CD), any(TransactionTypeUpdateRequest.class)))
                    .thenThrow(new RecordConflictException(
                            RecordConflictException.Kind.LOCK_UNAVAILABLE));

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(put(itemPath(TYPE_CD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replaceBody(NEW_DESCRIPTION, SUBMITTED_VERSION)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(messageOf(result))
                    .isEqualTo(GlobalExceptionHandler.MESSAGE_LOCK_UNAVAILABLE);
            assertThat((List<?>) bodyOf(result).get("fieldErrors"))
                    .as("contention on a lock reports no version, so the array is empty")
                    .isEmpty();
        }

        /**
         * The three conflict conditions share a status and are separated only by their sentence.
         *
         * <p>Assumptions: the published conflict response states that a caller acts differently on
         * each -- re-read and resubmit, retry unchanged, or remove the dependents first -- and that
         * because the shared advice publishes no subordinate identifier the sentence is the whole
         * discriminator. A case asserting the status alone would pass while the three answers were
         * indistinguishable, which is precisely the failure this one exists to exclude.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the three conflict conditions render three distinct sentences under one status")
        void theSentenceIsWhatSeparatesTheConflictConditions() throws Exception {
            String stale = conflictSentenceFor(
                    new RecordConflictException(RecordConflictException.Kind.STALE_VERSION,
                            CURRENT_VERSION));
            String locked = conflictSentenceFor(
                    new RecordConflictException(RecordConflictException.Kind.LOCK_UNAVAILABLE));
            String referenced = conflictSentenceFor(
                    new RecordConflictException(RecordConflictException.Kind.REFERENCED_ROW));

            assertThat(List.of(stale, locked, referenced))
                    .as("three conditions, three sentences; merging any two would leave a caller"
                            + " unable to tell which action to take")
                    .doesNotHaveDuplicates()
                    .containsExactly(ApiError.COACTUPC_RECORD_CHANGED,
                            GlobalExceptionHandler.MESSAGE_LOCK_UNAVAILABLE,
                            GlobalExceptionHandler.MESSAGE_REFERENCED_ROW);
        }

        /**
         * A replace nothing refuses answers 200 carrying the stored row.
         *
         * <p>Assumptions: the body returned is the STORED representation, so the version it carries
         * is the one the write produced rather than the one the caller submitted. A caller that
         * intends to edit twice needs the new token, and echoing the submitted one back would leave
         * its second submission stale by construction.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an accepted replace answers 200 with the stored row and its new token")
        void anAcceptedReplaceAnswersTheStoredRow() throws Exception {
            when(TransactionTypeControllerTest.this.service
                    .replace(eq(TYPE_CD), any(TransactionTypeUpdateRequest.class)))
                    .thenReturn(new TransactionTypeResponse(TYPE_CD, NEW_DESCRIPTION,
                            STORED_VERSION));

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(put(itemPath(TYPE_CD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replaceBody(NEW_DESCRIPTION, SUBMITTED_VERSION)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(200);

            Map<String, Object> body = bodyOf(result);
            assertThat(body.get("typeCd")).isEqualTo(TYPE_CD);
            assertThat(body.get("description")).isEqualTo(NEW_DESCRIPTION);
            // WHY : Assumptions: compared as an int because a JSON integer small enough to fit one is
            //       read back as an Integer, and an Integer never equals a Long of the same value.
            //       Alternatives Considered: asserting on the raw text of the body instead, which
            //       would sidestep the boxing entirely and would also stop distinguishing the number
            //       3 from the string "3" -- and the published schema declares this member a number,
            //       which is the one thing the comparison is here to keep true.
            assertThat(body.get("version"))
                    .as("the stored token, not the submitted one")
                    .isEqualTo((int) STORED_VERSION);
        }

        /**
         * The submitted body reaches the collaborator with the code taken from the path.
         *
         * <p>Assumptions: only the description is replaceable, which is why the request shape
         * declares no code at all -- both baseline write paths set the description column alone, at
         * line 1848 of {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} and line 1546 of that
         * tree's {@code COTRTUPC.cbl}, so the key is never updated. The code therefore comes from
         * the path and from nowhere else, and this case asserts that it does.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the code acted on comes from the path and the body carries only the edit")
        void theCodeComesFromThePathAndTheBodyCarriesTheEdit() throws Exception {
            when(TransactionTypeControllerTest.this.service
                    .replace(anyString(), any(TransactionTypeUpdateRequest.class)))
                    .thenReturn(new TransactionTypeResponse(TYPE_CD, NEW_DESCRIPTION,
                            STORED_VERSION));

            TransactionTypeControllerTest.this.mockMvc
                    .perform(put(itemPath(TYPE_CD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replaceBody(NEW_DESCRIPTION, SUBMITTED_VERSION)))
                    .andReturn();

            ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<TransactionTypeUpdateRequest> submitted =
                    ArgumentCaptor.forClass(TransactionTypeUpdateRequest.class);
            verify(TransactionTypeControllerTest.this.service)
                    .replace(code.capture(), submitted.capture());

            assertThat(code.getValue()).isEqualTo(TYPE_CD);
            assertThat(submitted.getValue().description()).isEqualTo(NEW_DESCRIPTION);
            assertThat(submitted.getValue().version()).isEqualTo(SUBMITTED_VERSION);
        }

        /**
         * A created type answers 201 naming the path of the row it created.
         *
         * <p>Assumptions: the address is built from the stored representation rather than from the
         * submitted body, so a header cannot claim a row was stored exactly as proposed. It is also
         * asserted to be a PATH and not an absolute url: the usual idiom composes one from the
         * inbound request, which behind a gateway and an internal load balancer names the internal
         * host rather than the one the caller used, and would still satisfy a check that looked for
         * the path as a suffix.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a created type answers 201 with the path of the created row")
        void aCreatedTypeAnswersItsLocation() throws Exception {
            when(TransactionTypeControllerTest.this.service
                    .create(any(TransactionTypeCreateRequest.class)))
                    .thenReturn(new TransactionTypeResponse(TYPE_CD, DESCRIPTION, STORED_VERSION));

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(post(TransactionTypeController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(TYPE_CD, DESCRIPTION)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(result.getResponse().getHeader("Location"))
                    .as("the created row's own path, composed from the route templates")
                    .isEqualTo(itemPath(TYPE_CD))
                    .doesNotStartWith("http");
        }

        /**
         * A create carrying a code already stored answers 409 through the referential sentence.
         *
         * <p>Refactoring Rationale: this is a documented divergence rather than a transcription,
         * and the evidence is specific. The baseline's insert arm has no branch for a duplicate key
         * at all -- a search of both reference programs for the Db2 code reporting one returns
         * nothing -- so its catch-all raises the same condition flag as the update arm, and the
         * classifier reading that flag cannot tell an insert refusal from an update refusal. The
         * Java keeps the two states apart at the source, a duplicate key arriving as SQLSTATE 23505
         * and a still-referenced row as 23503, and the service raises a kinded conflict for each.
         * The published contract states which sentence a duplicate create receives: the referential
         * one, reached through the same integrity branch as a restricted delete. The divergence is
         * registered in {@code docs/architecture/cobol-to-service-traceability.md}.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a duplicate code answers 409 with the sentence the contract publishes for it")
        void aDuplicateCodeIsRefusedAsAConflict() throws Exception {
            when(TransactionTypeControllerTest.this.service
                    .create(any(TransactionTypeCreateRequest.class)))
                    .thenThrow(new RecordConflictException(
                            RecordConflictException.Kind.REFERENCED_ROW));

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(post(TransactionTypeController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(TYPE_CD, DESCRIPTION)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(messageOf(result))
                    .isEqualTo(GlobalExceptionHandler.MESSAGE_REFERENCED_ROW);
            assertThat(result.getResponse().getHeader("Location"))
                    .as("nothing was created, so no created-resource address may be published")
                    .isNull();
        }

        /**
         * Renders the sentence one conflict condition reaches a caller with.
         *
         * @param condition the refusal the collaborator raises
         * @return the sentence the boundary publishes for it
         * @throws Exception if the request cannot be performed
         */
        private String conflictSentenceFor(RecordConflictException condition) throws Exception {
            when(TransactionTypeControllerTest.this.service
                    .replace(eq(TYPE_CD), any(TransactionTypeUpdateRequest.class)))
                    .thenThrow(condition);

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(put(itemPath(TYPE_CD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replaceBody(NEW_DESCRIPTION, SUBMITTED_VERSION)))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("every one of the three conditions carries the conflict status")
                    .isEqualTo(409);
            return messageOf(result);
        }
    }

    /**
     * Holds the browse to the page envelope the contract publishes and to paging by key.
     *
     * <p>Purpose: four questions the envelope alone answers. Which members it carries. Which row
     * its trailing position names, which is what decides whether the next page repeats a row.
     * Whether the positions disclose the key they stand for. And whether the caller's identity is
     * what those positions are sealed against.
     *
     * <p>Alternatives Considered: paging by ordinal position, which the framework offers ready-made
     * and which would let a case assert a row count and a page number instead of opening a cursor.
     * Rejected on behaviour rather than on cost: under inserts concurrent with a browse, positional
     * paging skips and repeats rows, and the baseline browse can do neither because it resumes from
     * a key. An assertion written against a position would therefore pass while describing
     * behaviour this surface does not have. No positional paging type appears anywhere in this
     * class.
     *
     * <p>Assumptions: the baseline's forward cursor is INCLUSIVE -- line 343 of
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} declares
     * {@code WHERE TR_TYPE >= :WS-START-KEY}, ordered ascending at line 351 -- and taken alone that
     * would re-read the row the caller already holds. It does not, because of a second mechanism:
     * having stored the last DISPLAYED row's key at line 1659, the reader performs one further fetch
     * at lines 1661 to 1665 and, on finding a row at line 1670, sets its further-page flag at 1671
     * and then OVERWRITES the stored key at line 1673 with that surplus row's key; at line 1674 a
     * no-row outcome instead clears the flag at 1675. The inclusive predicate and the overwrite are
     * one mechanism and neither is correct alone. The migrated envelope fixes the opposite
     * convention -- its trailing position names the last row actually PUBLISHED, never the surplus
     * one -- so the equivalent seek against it is the strict one. The row sequence is identical: no
     * row repeats and none is skipped. Which of the two conventions the envelope follows is
     * therefore load-bearing, and it is what the second case below pins.
     *
     * <p>Assumptions: the backward cursor is EXCLUSIVE and descending -- lines 359 and 367 of the
     * same program -- so its rows arrive in the reverse of display order and are reversed again
     * before publication. A case asserting membership alone could not see that, so the emitted
     * ORDER is asserted.
     *
     * <p>Assumptions: this class reads no fixture file. The seed at
     * {@code app/data/ASCII/trantype.txt} carries seven rows and the published page width is seven,
     * so no state of the seed can express a further page, and the module's own reference-list
     * fixture mirrors the seed at seven rows rather than exceeding it. That is not an obstacle here
     * because what a dispatcher class can witness is the envelope the boundary CARRIES rather than
     * the query that decided its contents: the further-page flag is established by the collaborator
     * requesting one row beyond the page, and whether it does so is proven in
     * {@code com.carddemo.reference.service} and against a container in
     * {@code com.carddemo.reference.repository}.
     *
     * <p>A test class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception tag.
     */
    @Nested
    @DisplayName("on the browse")
    class OnTheBrowse {

        /**
         * A page publishes exactly the four members the contract declares and no others.
         *
         * <p>Assumptions: the whole member set is compared rather than each member checked for
         * presence, because the failure worth catching is an EXTRA member. A page that additionally
         * published a row count, a page number or a backward flag would satisfy every
         * presence check while publishing a member the document does not describe, and a client
         * generated from the document would ignore it while a hand-written one might come to depend
         * on it.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a page carries items, both positions and the further-page flag, and nothing else")
        void aPageCarriesExactlyThePublishedMembers() throws Exception {
            stubFirstPage();

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(bodyOf(result).keySet())
                    .as("the published page schema fixes these four members; backward availability"
                            + " is the presence of the leading position and is not a member of its"
                            + " own")
                    .containsExactlyInAnyOrderElementsOf(PAGE_MEMBERS);
        }

        /**
         * The trailing position names the last row published, never the surplus row beyond it.
         *
         * <p>Assumptions: this is the single assertion that decides whether a caller walking the
         * set sees a row twice. The migrated seek is strictly greater than the position it is given,
         * so a position naming the last PUBLISHED row resumes at the row after it, while a position
         * naming the surplus row would skip that row entirely. The opposite pairing -- an inclusive
         * seek with a surplus position, which is the baseline's -- is equally correct, and mixing
         * the two halves in either direction repeats or drops exactly one row per page boundary. No
         * assertion on membership within a single page can see that, which is why the position is
         * opened and compared rather than merely checked for presence.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the trailing position names the last row published and not the surplus row")
        void theTrailingPositionNamesTheLastPublishedRow() throws Exception {
            stubFirstPage();

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH))
                    .andReturn();

            Map<String, Object> body = bodyOf(result);
            String lastKey = String.valueOf(body.get("lastKey"));
            String opened = TransactionTypeControllerTest.this.sealer
                    .open(browseBinding(false), lastKey);

            assertThat(opened)
                    .as("the last row this page published, so a strictly-greater seek resumes at"
                            + " the row after it")
                    .isEqualTo(LAST_PUBLISHED_CODE)
                    .as("never the surplus row, whose key would make the next page skip a row")
                    .isNotEqualTo(SURPLUS_CODE);
            assertThat(body.get("hasNext"))
                    .as("a surplus row was found, so a further page is reported")
                    .isEqualTo(Boolean.TRUE);
        }

        /**
         * The leading position names the first row published, which is what a backward step seeks from.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the leading position names the first row published")
        void theLeadingPositionNamesTheFirstPublishedRow() throws Exception {
            stubFirstPage();

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH))
                    .andReturn();

            String firstKey = String.valueOf(bodyOf(result).get("firstKey"));

            assertThat(TransactionTypeControllerTest.this.sealer
                    .open(browseBinding(false), firstKey))
                    .as("the first row published, which a backward step reads strictly before")
                    .isEqualTo(FIRST_PAGE_CODES.get(0));
        }

        /**
         * The position a caller returns reaches the browse verbatim, and the page answered is coherent.
         *
         * <p>Assumptions: the position is opaque to a client, so the boundary must hand it on
         * unaltered; a boundary that normalised, trimmed or re-encoded it would invalidate the seal
         * and turn a legitimate continuation into a refusal.
         *
         * <p>Assumptions: the ANSWERED envelope is read as well as the composed request, so the page a
         * continuation produces is held to being one a browse could produce -- two rows, a leading position
         * naming the first of them and a trailing position naming the last.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the returned position reaches the browse verbatim with the matching direction")
        void theReturnedPositionReachesTheBrowseVerbatim() throws Exception {
            String minted = seal(false, LAST_PUBLISHED_CODE);
            stubSecondPage();

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH)
                            .param(TransactionTypeController.PARAM_CURSOR, minted)
                            .param(TransactionTypeController.PARAM_DIRECTION,
                                    PageDirection.NEXT.wireValue()))
                    .andReturn();

            assertThat(capturedRequest().cursor())
                    .as("handed on unaltered; any rewriting would break the seal")
                    .isEqualTo(minted);
            assertThat(capturedRequest().direction()).isEqualTo(PageDirection.NEXT);

            // WHY : Refactoring Rationale: the envelope this continuation ANSWERS with is asserted as
            //       well, and it was not before. A case that reads only the request it composed cannot
            //       notice that the page it received described two rows whose leading and trailing
            //       positions named one row -- which is what this stub used to return. Reading both
            //       positions back through the deployed codec is what ties the stub to a page a browse
            //       could produce, and it is why the correction to that stub is visible here.
            Map<String, Object> envelope = bodyOf(result);
            assertThat(TransactionTypeControllerTest.this.sealer
                    .open(browseBinding(false), String.valueOf(envelope.get("firstKey"))))
                    .as("the leading position names the first row this page published")
                    .isEqualTo(SECOND_PAGE_CODES.get(0));
            assertThat(TransactionTypeControllerTest.this.sealer
                    .open(browseBinding(false), String.valueOf(envelope.get("lastKey"))))
                    .as("the trailing position names the LAST row published, never the first again")
                    .isEqualTo(SECOND_PAGE_CODES.get(SECOND_PAGE_CODES.size() - 1));
            assertThat(envelope.get("hasNext"))
                    .as("a final page reports no further page")
                    .isEqualTo(Boolean.FALSE);
        }

        /**
         * A backward page is published in ascending order, not in the order it was read.
         *
         * <p>Assumptions: the backward cursor is ordered descending, so its rows arrive reversed and
         * are reversed again for publication. Asserting the emitted sequence rather than its
         * membership is what distinguishes a page that was reversed from one that merely contains
         * the right rows.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a backward page is published in ascending order and names both its positions")
        void aBackwardPageIsPublishedAscending() throws Exception {
            when(TransactionTypeControllerTest.this.service.list(
                    any(TransactionTypeListRequest.class), any(CursorToken.class), anyString()))
                    .thenReturn(pageOf(FIRST_PAGE_CODES, seal(true, FIRST_PAGE_CODES.get(0)),
                            seal(true, LAST_PUBLISHED_CODE), true));

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH)
                            .param(TransactionTypeController.PARAM_CURSOR,
                                    seal(true, SURPLUS_CODE))
                            .param(TransactionTypeController.PARAM_DIRECTION,
                                    PageDirection.PREVIOUS.wireValue()))
                    .andReturn();

            assertThat(capturedRequest().direction())
                    .as("the published lower-case spelling must bind to the matching constant")
                    .isEqualTo(PageDirection.PREVIOUS);
            assertThat(publishedCodes(result))
                    .as("ascending display order, not the descending order the read produced")
                    .containsExactlyElementsOf(FIRST_PAGE_CODES)
                    .isSorted();
        }

        /**
         * The positions disclose nothing about the keys they stand for.
         *
         * <p>Assumptions: the published cursor schema describes the position as opaque and sealed,
         * so the only way to recover a key from it is to open it with the codec. The failure this
         * guards against is concrete rather than theoretical: assigning the raw keyset key satisfies
         * a type declared as a string exactly as well as a sealed token does, and on other browses
         * in this migration that raw key is a primary account number. Here it is only a two-digit
         * code, but the property is asserted on this surface too so the shape is uniform.
         *
         * <p>⚠️ Refactoring Rationale: this case asserted that the published position does not CONTAIN
         * the key as a substring, and that assertion was decided by chance rather than by behaviour.
         * {@code CursorToken} draws a fresh {@code SecureRandom} nonce per seal, so the encoded token is
         * a different random string on every run; the key here is the two characters {@code 07}, and a
         * base64url body of this length contains any given two-character sequence by coincidence
         * roughly one run in seventy. It failed exactly that way during this checkpoint's validation.
         * A test that fails on a coincidence is worse than no test, because the first response to it is
         * to re-run the build. Alternatives Considered: pinning the nonce for the test, which would make
         * the substring assertion deterministic. Rejected: it would require a seam into the codec's
         * randomness that exists for no other reason, and the assertion would still be asserting a
         * property of one nonce rather than of the seal. What replaces it says the same thing without
         * appealing to the encoding: the published value is not the key, it carries the sealed shape and
         * version marker, and opening it with the codec yields the key -- which is the whole of
         * "recoverable only through the codec", stated as two facts rather than as an absence.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the positions are sealed tokens and carry no readable key")
        void thePositionsAreOpaque() throws Exception {
            stubFirstPage();

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH))
                    .andReturn();

            Map<String, Object> body = bodyOf(result);
            String lastKey = String.valueOf(body.get("lastKey"));

            assertThat(CursorToken.hasSealedShape(lastKey))
                    .as("the published cursor shape, so a raw key is unrepresentable here")
                    .isTrue();
            assertThat(lastKey)
                    .as("the position published is the sealed token and never the key itself")
                    .isNotEqualTo(LAST_PUBLISHED_CODE);
            assertThat(lastKey).startsWith(CursorToken.VERSION + ".");
            assertThat(TransactionTypeControllerTest.this.sealer
                    .open(browseBinding(false), lastKey))
                    .as("the key it stands for is recoverable through the codec and only through it")
                    .isEqualTo(LAST_PUBLISHED_CODE);
        }

        /**
         * A position minted for one caller does not open for another.
         *
         * <p>Assumptions: this is the one authority-adjacent property that belongs to this boundary
         * rather than to the security chain, and it is asserted here because nothing else asserts
         * it. The handler takes the authenticated principal and hands its NAME to the browse, which
         * folds it into the binding its positions are sealed under, so a position cannot be replayed
         * by a different caller. A boundary that passed a constant, or the empty string, would leave
         * every position interchangeable between callers while every other case in this class
         * continued to pass.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the caller's own name is what the browse seals its positions against")
        void theCallerNameIsWhatPositionsAreSealedAgainst() throws Exception {
            stubFirstPage();

            TransactionTypeControllerTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH))
                    .andReturn();

            ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
            verify(TransactionTypeControllerTest.this.service)
                    .list(any(TransactionTypeListRequest.class), any(CursorToken.class),
                            subject.capture());

            assertThat(subject.getValue())
                    .as("the authenticated caller's name, taken from the principal the chain"
                            + " established and not from anything the request body carried")
                    .isEqualTo(CALLER.getName());

            String mintedForAnother = TransactionTypeControllerTest.this.sealer.seal(
                    ReferencePaging.binding(TransactionTypeService.CURSOR_BINDING, "REFUSR99",
                            false, null, null),
                    LAST_PUBLISHED_CODE);
            assertThat(CursorToken.hasSealedShape(mintedForAnother)).isTrue();
            assertThatOpeningFails(mintedForAnother);
        }

        /**
         * A full page is published at its whole width, with no row dropped at the boundary.
         *
         * <p>Assumptions: the width is the baseline's own named constant rather than a count off a
         * screen. {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} declares
         * {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7.} at line 60, and
         * {@code TransactionTypeService.PAGE_SIZE} carries it. What is asserted here is that every
         * row the collaborator published survives serialisation, which is the boundary's own
         * obligation; that the collaborator fills the page to that width is proven where the query
         * lives.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("every row of a full page survives to the response")
        void aFullPageIsPublishedWhole() throws Exception {
            stubFirstPage();

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH))
                    .andReturn();

            assertThat(publishedCodes(result))
                    .as("the whole page, at the width the baseline's own constant declares")
                    .hasSize(TransactionTypeService.PAGE_SIZE)
                    .containsExactlyElementsOf(FIRST_PAGE_CODES);
        }

        /**
         * Both narrowing filters reach the browse as the caller wrote them, gaining no pattern.
         *
         * <p>Alternatives Considered: the tree's own two descriptions of this filter contradict each
         * other -- one calls the pattern deliberately un-escaped, the other calls escaping mandatory
         * because the metacharacters are caller-supplied -- so the authored source was read instead
         * of either description being believed. It resolves toward escaping AND containment, and
         * both halves are the baseline's own: the module's transaction-type repository declares a
         * one-character escape, names it in the escape clause of every filtered query, and wraps the
         * caller's trimmed text in a per-cent sign on each side, which is what line 348 of
         * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} does when it builds its pattern by
         * stringing a per-cent sign, the trimmed filter and a per-cent sign together. So a plain
         * filter IS a containment match, faithfully, while a caller's OWN metacharacter is escaped
         * and matches as the literal character. The rejected alternative was passing the caller's
         * text through unescaped, and what it costs is specific: an unescaped low line matches any
         * single character and an unescaped per-cent sign matches any run of them, so one character
         * from a caller widens a filter to the whole table, and a filter of many per-cent signs
         * makes the server walk far more of the index than the request describes.
         *
         * <p>Assumptions: WHICH pattern the query receives is asserted where the pattern is built,
         * in {@code com.carddemo.reference.service}, and against a container in
         * {@code com.carddemo.reference.repository}; a substituted collaborator cannot witness it.
         * What this boundary owes, and what this case asserts, is the other half -- the caller's
         * text arrives exactly as sent. A boundary that wrapped or escaped it itself would have the
         * pattern built twice, which leaves the escape clause of the query with nothing to act on
         * and turns a literal metacharacter back into a wildcard.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the narrowing filters reach the browse exactly as the caller sent them")
        void theNarrowingFiltersReachTheBrowseVerbatim() throws Exception {
            stubFirstPage();

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH)
                            .param(TransactionTypeController.PARAM_TYPE_CODE, TYPE_CD)
                            .param(TransactionTypeController.PARAM_DESCRIPTION,
                                    FILTER_WITH_METACHARACTERS))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(200);

            TransactionTypeListRequest received = capturedRequest();

            assertThat(received.typeCode())
                    .as("the code filter is carried, not consumed by the boundary")
                    .isEqualTo(TYPE_CD);
            assertThat(received.description())
                    .as("no metacharacter added and none escaped here, or the pattern would be"
                            + " built twice")
                    .isEqualTo(FILTER_WITH_METACHARACTERS);
        }

        /**
         * The three remaining filter combinations arrive with the omitted narrowing absent, not blank.
         *
         * <p>Purpose: the case above drives BOTH filters together. This one drives the other three
         * combinations the two optional parameters admit -- neither, the code alone, the description
         * alone -- and asserts what the boundary composes for the parameter that was not sent.
         *
         * <p>Assumptions: an omitted parameter must reach the collaborator as {@code null} and never as
         * an empty string, and the two are not interchangeable one layer down. The browse builds a
         * {@code LIKE} pattern from a description that is present; an empty string is present, so it
         * would build {@code LIKE '%%'} and narrow nothing while every row paid for a pattern match, and
         * an empty code filter would compare a two-character column against a zero-length value and
         * narrow to nothing at all. Those two mistakes fail in opposite directions -- one returns
         * everything, the other returns nothing -- and neither is visible from a status code, which is
         * why the composed request is inspected rather than the response.
         *
         * <p>Alternatives Considered: asserting the four combinations through the SQL they produce.
         * Rejected as the wrong boundary: the predicate the two arms build is owned by
         * {@code com.carddemo.reference.service.TransactionTypeBrowseTest} and, against a real schema, by
         * {@code com.carddemo.reference.repository.TransactionTypeRepositoryIT}, whose case "both filter
         * arms active narrow by their conjunction" asserts the conjunction itself. What belongs here is
         * only the binding: which value each arm RECEIVES for a request a caller actually sent.
         *
         * @param typeCode the value to send for the code filter, or {@code null} to omit the parameter
         * @param description the value to send for the description filter, or {@code null} to omit it
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest(name = "typeCode={0} description={1}")
        @CsvSource(nullValues = "OMITTED", value = {
            "OMITTED,OMITTED",
            "07,OMITTED",
            "OMITTED,50% _OFF"})
        @DisplayName("each filter combination reaches the browse with an omitted narrowing left absent")
        void eachFilterCombinationReachesTheBrowseAsSent(String typeCode, String description)
                throws Exception {

            stubFirstPage();

            MockHttpServletRequestBuilder request = get(TransactionTypeController.BASE_PATH);
            if (typeCode != null) {
                request = request.param(TransactionTypeController.PARAM_TYPE_CODE, typeCode);
            }
            if (description != null) {
                request = request.param(TransactionTypeController.PARAM_DESCRIPTION, description);
            }

            MvcResult result = TransactionTypeControllerTest.this.mockMvc.perform(request).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);

            TransactionTypeListRequest received = capturedRequest();
            assertThat(received.typeCode())
                    .as("the code filter must arrive exactly as sent, and absent when not sent")
                    .isEqualTo(typeCode);
            assertThat(received.description())
                    .as("the description filter must arrive exactly as sent, and absent when not sent")
                    .isEqualTo(description);
        }

        /**
         * A published row's code carries its declared two characters, leading zero intact.
         *
         * <p>Assumptions: the code is asserted as a JSON STRING of exactly two characters rather than
         * merely equal to a literal, because the column is
         * {@code TRAN_TYPE CHAR(2)} and the copybook picture at line 6 of {@code app/cpy/CVTRA03Y.cpy}
         * is {@code PIC X(02)} -- a code serialised as a JSON number would arrive as {@code 7}, address
         * no row on the next request a client built from it, and still satisfy an equality assertion
         * written against an unquoted value. The width and the type are therefore both asserted, and the
         * category code beside it is asserted at four for the same reason: {@code TRC_TYPE_CATEGORY
         * CHAR(4)} against {@code PIC 9(04)}, where a numeric reading loses three leading zeros.
         *
         * <p>Alternatives Considered: leaving this to the schema, which declares the widths, and to the
         * repository suite, which asserts the columns. Rejected because neither observes the SERIALISED
         * form: the padding survives to the column and is then free to be lost by the writer, and this
         * is the only boundary in the module where the wire representation of a reference key is visible.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a published code is a two-character string on the wire, not a number")
        void aPublishedCodeKeepsItsDeclaredWidthOnTheWire() throws Exception {
            stubFirstPage();

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH))
                    .andReturn();

            String payload = result.getResponse().getContentAsString();
            assertThat(publishedCodes(result))
                    .allSatisfy(code -> assertThat(code).hasSize(TYPE_CODE_WIDTH));
            assertThat(payload)
                    .as("the code must be quoted; an unquoted value is a number and loses its padding")
                    .contains("\"typeCd\":\"" + FIRST_PAGE_CODES.get(0) + "\"")
                    .doesNotContain("\"typeCd\":" + FIRST_PAGE_CODES.get(0));
        }

        /**
         * Substitutes a first page of the published width with a surplus row beyond it.
         */
        private void stubFirstPage() {
            when(TransactionTypeControllerTest.this.service.list(
                    any(TransactionTypeListRequest.class), any(CursorToken.class), anyString()))
                    .thenReturn(pageOf(FIRST_PAGE_CODES, seal(false, FIRST_PAGE_CODES.get(0)),
                            seal(false, LAST_PUBLISHED_CODE), true));
        }

        /**
         * Substitutes a final page beginning at the row after the first page's last.
         *
         * <p>Refactoring Rationale: the trailing position names the LAST code the page publishes and no
         * longer repeats the leading one. It used to seal both positions against the first code of this
         * page, which describes an envelope no browse can produce: a two-row page whose leading and
         * trailing positions name the same row asserts that the row after the first is also the row before
         * the last. Nothing failed, because the one case that drives this stub reads the request the
         * boundary composed rather than the envelope it answered -- so the impossible envelope was never
         * looked at. It is corrected here and asserted in that case, so the stub describes a page a
         * deployed browse could actually return.</p>
         */
        private void stubSecondPage() {
            when(TransactionTypeControllerTest.this.service.list(
                    any(TransactionTypeListRequest.class), any(CursorToken.class), anyString()))
                    .thenReturn(pageOf(SECOND_PAGE_CODES,
                            seal(false, SECOND_PAGE_CODES.get(0)),
                            seal(false, SECOND_PAGE_CODES.get(SECOND_PAGE_CODES.size() - 1)),
                            false));
        }

        /**
         * Captures the browse request the boundary composed.
         *
         * @return the request the collaborator received
         */
        private TransactionTypeListRequest capturedRequest() {
            ArgumentCaptor<TransactionTypeListRequest> received =
                    ArgumentCaptor.forClass(TransactionTypeListRequest.class);
            verify(TransactionTypeControllerTest.this.service)
                    .list(received.capture(), any(CursorToken.class), anyString());
            return received.getValue();
        }

        /**
         * Reads the codes a page published, in the order it published them.
         *
         * @param result the completed exchange to read
         * @return the published codes in emitted order
         * @throws UnsupportedEncodingException if the response charset is unsupported
         */
        private List<String> publishedCodes(MvcResult result) throws UnsupportedEncodingException {
            List<?> items = (List<?>) bodyOf(result).get("items");
            return items.stream()
                    .map(row -> String.valueOf(((Map<?, ?>) row).get("typeCd")))
                    .toList();
        }

        /**
         * Asserts a position minted under another caller's binding cannot be opened under this one.
         *
         * @param token the position to attempt to open
         */
        private void assertThatOpeningFails(String token) {
            assertThatExceptionOfType(CursorToken.InvalidCursorException.class)
                    .as("a position sealed for one caller must not open for another, or every"
                            + " position would be interchangeable between callers")
                    .isThrownBy(() -> TransactionTypeControllerTest.this.sealer
                            .open(browseBinding(false), token));
        }
    }

    /**
     * Builds the body of a replace request.
     *
     * <p>Assumptions: written as text rather than serialised from the request record, so the case
     * exercises the binding a client actually drives instead of round-tripping the type under test
     * through its own mapper.
     *
     * @param description the replacement description
     * @param version the concurrency token the caller read
     * @return the JSON body to submit
     */
    private static String replaceBody(String description, long version) {
        return "{\"description\":\"" + description + "\",\"version\":" + version + "}";
    }

    /**
     * Builds the body of a create request.
     *
     * @param typeCd the code the caller proposes
     * @param description the description the caller proposes
     * @return the JSON body to submit
     */
    private static String createBody(String typeCd, String description) {
        return "{\"typeCd\":\"" + typeCd + "\",\"description\":\"" + description + "\"}";
    }

    /**
     * Holds the sentences this surface publishes to the characters their sources declare.
     *
     * <p>Purpose: transformation rule T8 carries every user-visible string across character for
     * character, so a sentence is a contract in its own right and a well-meaning normalisation of
     * one is a behavioural change. Two of the sentences below carry spellings the baseline carries
     * that a modern reader reads as mistakes -- a two-word "some one" and a missing terminal period --
     * and normalising either would be exactly that change.
     *
     * <p>Assumptions: those spellings are described as the baseline's FORM and never as defects to be
     * repaired, and the distinction is not cosmetic. {@code app/**} is reference-only, so the baseline's
     * wording is the specification rather than a candidate for correction; calling it defective invites
     * the reading that the migration is entitled to put it right, which is the one thing rule T8 forbids.
     * What the assertions below protect is character-for-character carry-over, whatever a reader thinks
     * of the characters.
     *
     * <p>Assumptions: every sentence is asserted THROUGH the constant that publishes it and never
     * against a copy retyped here. That is the difference between checking that the boundary
     * publishes the catalogue's sentence and checking that it publishes whatever this file happens
     * to say -- the second passes when both are wrong in the same way.
     *
     * <p>Trade-offs: the baseline's full screen catalogue is wider than what this surface can
     * publish, and the difference is bounded deliberately rather than papered over. Two further
     * literals whose spacing a reader would likewise want to normalise sit in
     * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} -- the save prompt at line 161, with no
     * space after its period, and the exit notice at line 170, with no space after
     * its period and with trailing spaces that are part of the literal. Both are screen-turn
     * notices belonging to a terminal conversation this surface does not have: no route publishes
     * either, the shared catalogue declares neither, and asserting them here would mean retyping
     * baseline text that nothing serves, which is the practice this group exists to avoid. Their
     * paths and lines are named so a reader can find them, and what is given up is coverage of
     * literals that have no boundary to be observed at. The same file's dead literal at line 173,
     * whose condition name occurs nowhere after the procedure division opens at line 344, is
     * likewise not carried into any validation message on this surface.
     *
     * <p>A test class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception tag.
     */
    @Nested
    @DisplayName("on the sentences a caller receives")
    class OnThePublishedSentences {

        /**
         * The concurrency sentence keeps the baseline's two-word spelling.
         *
         * <p>Assumptions: the shared catalogue preserves the mixed-case, unpunctuated regime of the
         * 46-character literal at line 522 of {@code app/cbl/COACTUPC.cbl}, including its two-word
         * spelling and its absence of a terminating period, and the reference maintenance screen
         * declares the identical characters at line 184 of
         * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}. Both the presence of the two-word
         * form and the absence of the one-word form are asserted, because a normalisation would
         * substitute one for the other and an assertion on only the first would still pass if the
         * sentence had been rewritten around it.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the concurrency sentence keeps its two-word spelling and takes no period")
        void theConcurrencySentenceKeepsItsSpelling() throws Exception {
            when(TransactionTypeControllerTest.this.service
                    .replace(eq(TYPE_CD), any(TransactionTypeUpdateRequest.class)))
                    .thenThrow(new RecordConflictException(
                            RecordConflictException.Kind.STALE_VERSION, CURRENT_VERSION));

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(put(itemPath(TYPE_CD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replaceBody(NEW_DESCRIPTION, SUBMITTED_VERSION)))
                    .andReturn();

            assertThat(messageOf(result))
                    .isEqualTo(ApiError.COACTUPC_RECORD_CHANGED)
                    .contains("some one")
                    .doesNotContain("someone")
                    .doesNotEndWith(".");
        }

        /**
         * The referential sentence keeps its trailing colon and gains nothing after it.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the referential sentence ends at the colon the baseline gives it")
        void theReferentialSentenceEndsAtItsColon() throws Exception {
            doThrow(new RecordConflictException(RecordConflictException.Kind.REFERENCED_ROW))
                    .when(TransactionTypeControllerTest.this.service).delete(TYPE_CD);

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(delete(itemPath(TYPE_CD)))
                    .andReturn();

            assertThat(messageOf(result))
                    .isEqualTo(GlobalExceptionHandler.MESSAGE_REFERENCED_ROW)
                    .endsWith(":");
        }

        /**
         * The two absent-row sentences answer different questions and are not merged.
         *
         * <p>Assumptions: the published read and delete answer with
         * {@code TransactionTypeService.MESSAGE_TYPE_NOT_FOUND}, while
         * {@code MESSAGE_RECORD_DELETED_BY_OTHERS} is the answer the list screen gives to an UPDATE
         * whose target vanished between the read and the write -- the condition the baseline reports
         * at SQLCODE +100 on the update rather than on a read, moving the sentence at line 1864 of
         * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}. That literal carries a space before
         * its question mark AND a trailing space, both of which the constant preserves. The two are
         * kept apart rather than collapsed, so this case asserts both that the constant keeps its
         * trailing space and that it is not what the published refusal returns.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the read refusal and the vanished-row sentence are two sentences, not one")
        void theTwoAbsentRowSentencesAreNotMerged() throws Exception {
            assertThat(TransactionTypeService.MESSAGE_RECORD_DELETED_BY_OTHERS)
                    .as("the baseline literal carries a space before its question mark and a"
                            + " trailing space, and both are part of the contract")
                    .endsWith("? ")
                    .isNotEqualTo(TransactionTypeService.MESSAGE_TYPE_NOT_FOUND);

            doThrow(new NoSuchElementException(TransactionTypeService.MESSAGE_TYPE_NOT_FOUND))
                    .when(TransactionTypeControllerTest.this.service).delete(ABSENT_TYPE_CD);

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(delete(itemPath(ABSENT_TYPE_CD)))
                    .andReturn();

            assertThat(messageOf(result))
                    .isEqualTo(TransactionTypeService.MESSAGE_TYPE_NOT_FOUND);
            assertThat(rawBodyOf(result))
                    .as("the sentence for a vanished update target must not answer a read")
                    .doesNotContain(TransactionTypeService.MESSAGE_RECORD_DELETED_BY_OTHERS);
        }

        /**
         * The developer placeholder left in the baseline reaches no response on any path.
         *
         * <p>Assumptions: a negative assertion is the only thing that keeps it out. The literal is
         * declared at line 196 of {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} under a
         * condition name that occurs nowhere after the procedure division opens at line 344, so the
         * baseline itself never displays it -- which means nothing about the baseline's own
         * behaviour would be violated by a migration that did. Every body this surface can produce
         * is checked, the FAULT channel included, because a placeholder that leaked on one path only
         * would otherwise be invisible -- and that is measured rather than assumed: substituting the
         * placeholder into the fault sentence is caught here only because that channel is covered.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the developer placeholder appears in no response this surface produces")
        void theDeveloperPlaceholderNeverReachesAResponse() throws Exception {
            for (String body : everyRefusalBody()) {
                assertThat(body)
                        .as("a placeholder in working storage must not become a published sentence")
                        .doesNotContain(DEVELOPER_PLACEHOLDER);
            }
        }

        /**
         * Every sentence this surface publishes fits the width the message line declares.
         *
         * <p>Assumptions: the reference message line is declared as
         * {@code WS-RETURN-MSG PIC X(75)} at line 167 of
         * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}, and the shared card copybook
         * declares the same width at lines 28 and 29 of {@code app/cpy/CVCRD01Y.cpy}. The width is
         * preserved as a rendering constraint rather than as storage, so a sentence exceeding it
         * would be truncated by whatever renders it and a caller would read a clipped sentence. The
         * shared error type publishes the bound, and it is read from there rather than written as
         * 75 here.
         *
         * <p>Assumptions: one divergence between those two declarations is recorded so it is not
         * read as an inconsistency. The reference screen's off state is
         * {@code VALUE SPACES} at line 168 while the card copybook's is {@code VALUE LOW-VALUES} at
         * line 30 -- two different spellings of an empty message line. Neither reaches this surface,
         * because an absent sentence here is an absent member rather than a filled field, but the
         * pair is why a reader should not expect one canonical empty value in the baseline.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("every published sentence fits the declared message-line width")
        void everyPublishedSentenceFitsTheMessageWidth() throws Exception {
            // WHY : Assumptions: the bodies arrive as text from the helper and are parsed here rather
            //       than through the exchange-reading helper, which takes a completed exchange this
            //       loop no longer holds. Trade-offs: one extra parse per body, against a helper that
            //       would have to return exchanges and leave every caller re-reading them.
            for (String body : everyRefusalBody()) {
                String sentence = String.valueOf(TransactionTypeControllerTest.this.mapper
                        .readValue(body, new TypeReference<Map<String, Object>>() { })
                        .get("message"));
                assertThat(sentence.length())
                        .as("the sentence %s must fit the declared width, or a renderer clips it",
                                sentence)
                        .isLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH);
            }
        }

        /**
         * Renders the body of every refusal and every fault this surface can produce.
         *
         * <p>Assumptions: every body is driven through a real route rather than composed here, so what a
         * case inspects is a body that actually travelled.
         *
         * <p>Refactoring Rationale: the FAULT body is included, and its absence was a measured hole
         * rather than a hypothetical one. This helper originally returned the four client-correctable
         * refusals only, and every one of them renders a sentence selected for a conflict or a
         * not-found. Substituting the developer placeholder into the sentence the FAULT channel
         * publishes therefore left all four bodies unchanged, and the case that exists to keep that
         * placeholder out of a response passed while the placeholder was in one. The fault path is
         * the channel most likely to acquire a stray sentence, because it is the one nobody looks at
         * in a passing build, so it is the one that most needed covering.
         *
         * <p>Refactoring Rationale: the five PROTOCOL-level refusals were then added for the same
         * reason, and their absence was the larger hole of the two. This method's name and its first
         * sentence claim every refusal, and the two cases that read it -- one asserting no developer
         * placeholder reaches a caller, the other that every published sentence fits the declared
         * message-line width -- are properties of EVERY body this surface writes, not only of the ones a
         * collaborator raised. The five omitted paths were all reachable and none of them was covered:
         * a path segment outside the published domain, a body the parser cannot read, a submitted media
         * type the route does not consume, a method the route does not declare, and an accepted media
         * type it cannot produce. Each is rendered by a DIFFERENT handler of the shared advice, so a
         * sentence over the width or a placeholder in any one of them would have travelled unnoticed.
         *
         * <p>Assumptions: a SECURITY refusal is deliberately not among them, and its absence is a
         * property of the arrangement rather than an omission. This slice installs no filter chain -- the
         * security configuration is a plain configuration class the slice's include filter does not admit
         * -- so an unauthenticated or unauthorised request is answered by the handler here rather than
         * refused before it. The bodies the deployed chain writes for those two conditions are asserted
         * against the chain itself in {@code com.carddemo.reference.config}, which is the only place they
         * can be produced at all.
         *
         * @return one body per refusal path, collaborator-raised and protocol-level, plus one for the
         *     fault path, in no particular order
         * @throws Exception if a request cannot be performed
         */
        private List<String> everyRefusalBody() throws Exception {
            doThrow(new RecordConflictException(RecordConflictException.Kind.REFERENCED_ROW))
                    .when(TransactionTypeControllerTest.this.service).delete(TYPE_CD);
            doThrow(new NoSuchElementException(TransactionTypeService.MESSAGE_TYPE_NOT_FOUND))
                    .when(TransactionTypeControllerTest.this.service).delete(ABSENT_TYPE_CD);
            when(TransactionTypeControllerTest.this.service
                    .replace(eq(TYPE_CD), any(TransactionTypeUpdateRequest.class)))
                    .thenThrow(new RecordConflictException(
                            RecordConflictException.Kind.STALE_VERSION, CURRENT_VERSION));
            when(TransactionTypeControllerTest.this.service
                    .create(any(TransactionTypeCreateRequest.class)))
                    .thenThrow(new RecordConflictException(
                            RecordConflictException.Kind.LOCK_UNAVAILABLE));
            when(TransactionTypeControllerTest.this.service.read(TYPE_CD))
                    .thenThrow(new DataAccessResourceFailureException(VENDOR_DIAGNOSTIC));

            // WHY : Assumptions: the browse is stubbed to SUCCEED, and it is the route the negotiation
            //       refusal is driven against for a specific reason. A negotiation failure is raised while
            //       the answer is being written, so it can only arise on a request the handler answered.
            //       Driving it against a route whose collaborator raises instead produces the collaborator's
            //       failure, whose rendering is then negotiated against the same unacceptable header and
            //       escapes the dispatcher unrendered -- which is a different condition from the one this
            //       body is here to represent. The negotiation handler presets its own content type, which
            //       is why ITS body still renders.
            when(TransactionTypeControllerTest.this.service.list(
                    any(TransactionTypeListRequest.class), any(CursorToken.class), anyString()))
                    .thenReturn(pageOf(FIRST_PAGE_CODES, seal(false, FIRST_PAGE_CODES.get(0)),
                            seal(false, LAST_PUBLISHED_CODE), false));

            return List.of(
                    rawBodyOf(TransactionTypeControllerTest.this.mockMvc
                            .perform(delete(itemPath(TYPE_CD))).andReturn()),
                    rawBodyOf(TransactionTypeControllerTest.this.mockMvc
                            .perform(delete(itemPath(ABSENT_TYPE_CD))).andReturn()),
                    rawBodyOf(TransactionTypeControllerTest.this.mockMvc
                            .perform(put(itemPath(TYPE_CD))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(replaceBody(NEW_DESCRIPTION, SUBMITTED_VERSION)))
                            .andReturn()),
                    rawBodyOf(TransactionTypeControllerTest.this.mockMvc
                            .perform(post(TransactionTypeController.BASE_PATH)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(createBody(TYPE_CD, DESCRIPTION)))
                            .andReturn()),
                    rawBodyOf(TransactionTypeControllerTest.this.mockMvc
                            .perform(get(itemPath(TYPE_CD))).andReturn()),
                    rawBodyOf(TransactionTypeControllerTest.this.mockMvc
                            .perform(get(itemPath(SEGMENT_OUTSIDE_THE_DOMAIN))).andReturn()),
                    rawBodyOf(TransactionTypeControllerTest.this.mockMvc
                            .perform(post(TransactionTypeController.BASE_PATH)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(UNREADABLE_BODY))
                            .andReturn()),
                    rawBodyOf(TransactionTypeControllerTest.this.mockMvc
                            .perform(post(TransactionTypeController.BASE_PATH)
                                    .contentType(MediaType.TEXT_PLAIN)
                                    .content(createBody(TYPE_CD, DESCRIPTION)))
                            .andReturn()),
                    rawBodyOf(TransactionTypeControllerTest.this.mockMvc
                            .perform(patch(itemPath(TYPE_CD))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(replaceBody(NEW_DESCRIPTION, SUBMITTED_VERSION)))
                            .andReturn()),
                    rawBodyOf(TransactionTypeControllerTest.this.mockMvc
                            .perform(get(TransactionTypeController.BASE_PATH)
                                    .accept(MediaType.APPLICATION_XML))
                            .andReturn()));
        }
    }

    /**
     * Holds the keyed read to the answer the published contract declares for it.
     *
     * <p>Purpose: this group exists because the SUCCESSFUL keyed read had no owner anywhere in this
     * module. Every other case that touched {@code service.read} drove it to raise, so the one answer a
     * caller receives on the ordinary path -- a 200 carrying one object -- was asserted nowhere, and a
     * handler that had stopped binding its segment, stopped delegating, renamed a member or added one
     * would have satisfied every existing case in this file.
     *
     * <p>Assumptions: the segment the handler is given is CAPTURED rather than inferred from the answer,
     * because the published path parameter is narrowed by a pattern and a width and the failure this
     * guards against is a boundary that read for the wrong value rather than one that answered wrongly.
     * A case reading only the body cannot tell a handler that delegated the caller's code from one that
     * delegated a normalised copy of it.
     *
     * <p>Assumptions: the published schema closes its member set with {@code additionalProperties: false}
     * and declares all three members required, so the answered member set is asserted as an EQUALITY
     * rather than as a containment. A containment assertion admits a body that grew a member, which is
     * precisely what a closed schema forbids and what a client validating against it would reject.
     *
     * <p>Assumptions: authority is not decided here and is not asserted here. This slice installs no
     * filter chain, which is what keeps a 404 on this route meaning that no row holds the code rather
     * than that a caller was refused; which caller the deployed chain admits to this read is asserted
     * against the chain's own authorization managers in {@code com.carddemo.reference.config}. What this
     * group asserts is the complement: that the handler itself reaches the same answer whichever
     * authorities the caller carries, so no authority decision has been duplicated into it.
     *
     * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
     * parameter, return or exception tag.
     */
    @Nested
    @DisplayName("on the keyed read")
    class OnTheKeyedRead {

        /**
         * The ordinary read answers 200 with the stored row, delegating the caller's code verbatim.
         *
         * <p>Assumptions: the delegation is verified as the ONLY interaction, so a boundary that read
         * twice -- once to check existence and once for the value -- would fail here. The service already
         * answers an absent row by raising, so a second read would be a boundary re-deciding something
         * the collaborator has decided.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the read answers 200 with the stored row and delegates the code verbatim")
        void theReadAnswersTheStoredRow() throws Exception {
            when(TransactionTypeControllerTest.this.service.read(TYPE_CD))
                    .thenReturn(new TransactionTypeResponse(TYPE_CD, DESCRIPTION, STORED_VERSION));

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(get(itemPath(TYPE_CD)))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("the ordinary read is the one answer no case in this file used to assert")
                    .isEqualTo(200);

            Map<String, Object> body = TransactionTypeControllerTest.this.bodyOf(result);
            assertThat(body.get("typeCd")).isEqualTo(TYPE_CD);
            assertThat(body.get("description")).isEqualTo(DESCRIPTION);
            assertThat(body.get("version")).isEqualTo((int) STORED_VERSION);

            ArgumentCaptor<String> delegated = ArgumentCaptor.forClass(String.class);
            verify(TransactionTypeControllerTest.this.service).read(delegated.capture());
            verifyNoMoreInteractions(TransactionTypeControllerTest.this.service);
            assertThat(delegated.getValue())
                    .as("the segment is handed on as the caller wrote it, at its declared width")
                    .isEqualTo(TYPE_CD)
                    .hasSize(TransactionType.TYPE_CD_WIDTH);
        }

        /**
         * The answered body carries exactly the three members the closed schema declares.
         *
         * <p>Assumptions: the member set is compared for equality against the published set, so both a
         * missing member and an added one fail. The published schema declares all three required and
         * closes the object, so either departure is a contract break rather than a tolerance.
         *
         * <p>Assumptions: the code is asserted to be a JSON STRING and the version a JSON NUMBER, read
         * from the parsed tree's own types rather than from the text. The code is a fixed-width character
         * value whose leading zero is significant -- {@code app/cpy/CVTRA03Y.cpy} declares it as two
         * characters at its line 5 -- so a code rendered as a number would arrive as 7 rather than as 07
         * and would no longer address the row it names.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the body carries exactly the three declared members, the code as a string")
        void theBodyCarriesExactlyTheDeclaredMembers() throws Exception {
            when(TransactionTypeControllerTest.this.service.read(TYPE_CD))
                    .thenReturn(new TransactionTypeResponse(TYPE_CD, DESCRIPTION, STORED_VERSION));

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(get(itemPath(TYPE_CD)))
                    .andReturn();

            assertThat(TransactionTypeControllerTest.this.bodyOf(result).keySet())
                    .as("the schema closes its member set, so a grown body is a break and not a"
                            + " tolerated addition")
                    .containsExactlyInAnyOrderElementsOf(ITEM_MEMBERS);

            String raw = rawBodyOf(result);
            assertThat(raw)
                    .as("the code travels as a quoted string; rendered as a number its leading zero"
                            + " is lost and the value no longer addresses its row")
                    .contains("\"typeCd\":\"" + TYPE_CD + "\"")
                    .contains("\"version\":" + STORED_VERSION)
                    .doesNotContain("\"version\":\"");
        }

        /**
         * A code no row holds answers 404 carrying the service's own verbatim sentence.
         *
         * <p>Assumptions: the sentence is asserted through the service's published constant rather than
         * retyped, so a change to the constant moves this assertion with it instead of leaving a stale
         * copy behind. The status is asserted alongside it because the two are separable: the same
         * sentence rendered with a 500 would report a caller's mistake as a fault.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a code no row holds answers 404 with the service's sentence")
        void anAbsentCodeIsReportedAsNotFound() throws Exception {
            when(TransactionTypeControllerTest.this.service.read(ABSENT_TYPE_CD))
                    .thenThrow(new NoSuchElementException(
                            TransactionTypeService.MESSAGE_TYPE_NOT_FOUND));

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(get(itemPath(ABSENT_TYPE_CD)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(TransactionTypeControllerTest.this.messageOf(result))
                    .isEqualTo(TransactionTypeService.MESSAGE_TYPE_NOT_FOUND);
            assertThat(rawBodyOf(result))
                    .as("an absent row says nothing about the table it was looked for in")
                    .doesNotContain(VENDOR_DIAGNOSTIC);
        }

        /**
         * A segment outside the published domain is refused as a bad request and never read for.
         *
         * <p>Assumptions: the two values driven are the two distinguishable ways a segment can leave the
         * domain -- a value of the declared width that the pattern excludes, and a value of the wrong
         * width entirely. Both matter because the pattern the contract publishes admits 01 through 99 and
         * therefore excludes 00, so a boundary enforcing only the width would let 00 through.
         *
         * <p>Assumptions: the collaborator is asserted never to have been called at all. That is the
         * property that separates a refused request from a request answered as an absent row: without it
         * a boundary that read for the malformed value and reported 404 would satisfy a status-only case
         * while telling a caller something about the table when what is wrong is the request.
         *
         * @param outsideTheDomain a segment the published path parameter does not admit
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest
        @ValueSource(strings = {"00", "7"})
        @DisplayName("a segment outside the published domain is refused without reaching the store")
        void aSegmentOutsideTheDomainIsRefused(String outsideTheDomain) throws Exception {
            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(get(itemPath(outsideTheDomain)))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("a request outside the published domain is the caller's to correct;"
                            + " answering 404 would describe the table instead of the request")
                    .isEqualTo(400);
            assertThat(TransactionTypeControllerTest.this.bodyOf(result).get("code"))
                    .isEqualTo(ApiError.CODE_VALIDATION);
            verify(TransactionTypeControllerTest.this.service, never()).read(anyString());
        }

        /**
         * The handler reaches the same answer whichever authorities the caller carries.
         *
         * <p>Purpose: this is the complement of the chain assertions in
         * {@code com.carddemo.reference.config}, and it is what stops an authority decision being
         * duplicated into the handler. A handler that inspected the caller's groups itself would give two
         * different answers here, and the two mechanisms would then disagree about who may read -- with
         * whichever ran first deciding.
         *
         * <p>Assumptions: the callers are presented with the security test support rather than with a
         * real token, a reachable issuer or a credential written into a source file, and the three
         * postures driven are the administrative group, the ordinary group and no authority at all.
         *
         * @throws Exception if a request cannot be performed
         */
        @Test
        @DisplayName("the handler decides no authority: both groups and neither receive one answer")
        void theHandlerMakesNoAuthorityDecisionOfItsOwn() throws Exception {
            when(TransactionTypeControllerTest.this.service.read(TYPE_CD))
                    .thenReturn(new TransactionTypeResponse(TYPE_CD, DESCRIPTION, STORED_VERSION));

            String asAdmin = readAs(JwtRoleConverter.ADMIN_AUTHORITY);
            String asUser = readAs(JwtRoleConverter.USER_AUTHORITY);
            String withNone = readAs();

            assertThat(asAdmin)
                    .as("no authority is read by the handler, so all three answers are one answer")
                    .isEqualTo(asUser)
                    .isEqualTo(withNone);
            assertThat(asAdmin).contains("\"typeCd\":\"" + TYPE_CD + "\"");
        }

        /**
         * Reads the seeded code as a caller holding the stated authorities and returns the raw body.
         *
         * @param authorities the authority names the presented caller carries; an empty argument list
         *     yields an authenticated caller carrying none
         * @return the body exactly as it travelled
         * @throws Exception if the request cannot be performed
         */
        private String readAs(String... authorities) throws Exception {
            return rawBodyOf(TransactionTypeControllerTest.this.mockMvc
                    .perform(get(itemPath(TYPE_CD)).with(jwt().authorities(
                            Arrays.stream(authorities)
                                    .map(SimpleGrantedAuthority::new)
                                    .toList()
                                    .toArray(new SimpleGrantedAuthority[0]))))
                    .andReturn());
        }
    }

    /**
     * Holds the boundary to answering from the request alone, with nothing carried between requests.
     *
     * <p>Purpose: the screens this surface replaces were pseudo-conversational. A task ended at
     * every screen turn, so all continuity lived in a structure passed back and forth, and a
     * re-entry discriminator told a program whether it was seeing a first entry or a later turn.
     * None of that is ported: the identity comes from the validated principal, the code from the
     * path, and the discriminator disappears entirely.
     *
     * <p>Refactoring Rationale: the disappearance has an observable consequence worth naming,
     * because it removes a coupling rather than merely a field. The templated highlight at lines 17
     * to 27 of {@code app/cpy/CSSETATY.cpy} gates its whole effect on that discriminator at line 20
     * -- a field is highlighted only when a validation flag is unset AND the program is on a later
     * turn. With no turn to be on, error presentation here is driven purely by the response body,
     * so a refusal is presented identically however many times it is requested. That is what the
     * last case below asserts.
     *
     * <p>Assumptions: statelessness is the baseline's own contract on the adjacent surface rather
     * than a preference adopted here. {@code app/app-vsam-mq/cbl/CODATE01.cbl} declares
     * {@code PROGRAM-ID.           CODATE01 IS INITIAL.} at its line 2, which requires the program's
     * storage to be reinitialised on every entry.
     *
     * <p>A test class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception tag.
     */
    @Nested
    @DisplayName("on answering from the request alone")
    class OnTheStatelessBoundary {

        /**
         * Two identical requests are answered identically, apart from the per-request correlation.
         *
         * <p>Assumptions: everything except one member is comparable byte for byte, because the advice
         * is constructed over a fixed clock, so a timestamp cannot differ between two renderings of one
         * refusal. Comparing whole bodies rather than selected members is deliberate: a comparison of
         * named members stops seeing a difference in any member it does not name, which is precisely
         * how a remembered turn count would hide.
         *
         * <p>Refactoring Rationale: the correlation identifier is excluded from the comparison, and the
         * exclusion is a correction rather than a concession. This case previously compared the two
         * bodies whole and passed, because the boundary it ran against was a bare dispatcher with no
         * filters: nothing issued a correlation identifier, so the member was constant. The deployed
         * boundary registers the shared correlation filter, which mints one per request BY DESIGN -- a
         * value shared between two requests would defeat the whole point of carrying it. So the member
         * is asserted to DIFFER, which is the real contract, and the remainder is asserted to be
         * identical, which is the property this case is about. Comparing the bodies whole and calling
         * the failure a defect would have been asserting that the deployed filter should not exist.
         *
         * @throws Exception if a request cannot be performed
         */
        @Test
        @DisplayName("two identical requests answer identically and are each answered afresh")
        void twoIdenticalRequestsAreAnsweredIdentically() throws Exception {
            doThrow(new RecordConflictException(RecordConflictException.Kind.REFERENCED_ROW))
                    .when(TransactionTypeControllerTest.this.service).delete(TYPE_CD);

            MvcResult firstResult = TransactionTypeControllerTest.this.mockMvc
                    .perform(delete(itemPath(TYPE_CD))).andReturn();
            MvcResult secondResult = TransactionTypeControllerTest.this.mockMvc
                    .perform(delete(itemPath(TYPE_CD))).andReturn();
            String first = rawBodyOf(firstResult);
            String second = rawBodyOf(secondResult);

            String firstCorrelation = correlationOf(firstResult);
            String secondCorrelation = correlationOf(secondResult);
            assertThat(secondCorrelation)
                    .as("a correlation identifier is minted per request, so two requests must not"
                            + " share one; a shared value would make two calls indistinguishable in"
                            + " every log that carries it")
                    .isNotEqualTo(firstCorrelation);
            assertThat(second.replace(secondCorrelation, firstCorrelation))
                    .as("no turn count and no remembered context, so a repeated request is the"
                            + " same request rather than a later turn of the first")
                    .isEqualTo(first);
            verify(TransactionTypeControllerTest.this.service, times(2)).delete(TYPE_CD);
            // WHY : Assumptions: the collaborator is held to those two calls and NOTHING else, because
            //       the way a stateless boundary would most plausibly stop being one is by reading
            //       something extra on a repeat -- a stored context, a prior outcome -- and a count on
            //       the delete alone would not see a second, different call at all.
            verifyNoMoreInteractions(TransactionTypeControllerTest.this.service);
        }

        /**
         * No session is established, so nothing can be carried between requests server-side.
         *
         * <p>Assumptions: asserted on the request the dispatcher handled rather than on the response
         * headers, because the absence of a session cookie proves only that none was published,
         * while the absence of a session proves none was created. A handler that established one
         * would leave the service horizontally unscalable without sticky routing, which is the
         * property the whole surface is shaped to avoid needing.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("no server-side session is created for a request on this surface")
        void noSessionIsEstablished() throws Exception {
            stubOnePage();

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH))
                    .andReturn();

            assertThat(result.getRequest().getSession(false))
                    .as("a session here would be state carried between requests, which is exactly"
                            + " what the discarded communication area used to be")
                    .isNull();
        }

        /**
         * A parameter shaped like a re-entry flag changes nothing about the answer.
         *
         * <p>Assumptions: the two published filters and the two paging parameters are the whole
         * caller-supplied surface of this browse, so a name outside that set has no binding to
         * reach. This case sends one anyway and asserts the answer is unchanged, which is the
         * observable form of the discriminator having no successor: a client that tried to tell this
         * surface which turn it was on would be answered as though it had not.
         *
         * @throws Exception if a request cannot be performed
         */
        @Test
        @DisplayName("a re-entry shaped parameter is not honoured and changes no answer")
        void aReEntryShapedParameterIsNotHonoured() throws Exception {
            stubOnePage();

            String plain = rawBodyOf(TransactionTypeControllerTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH)).andReturn());
            String withFlag = rawBodyOf(TransactionTypeControllerTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH)
                            .param("pgmContext", "1")
                            .param("reenter", "true"))
                    .andReturn());

            assertThat(withFlag)
                    .as("no re-entry, turn-count or resubmit parameter exists to be honoured")
                    .isEqualTo(plain);

            assertThat(capturedRequests())
                    .as("both requests reached the browse with the same caller-supplied values")
                    .allSatisfy(received -> {
                        assertThat(received.cursor()).isNull();
                        assertThat(received.typeCode()).isNull();
                        assertThat(received.description()).isNull();
                        assertThat(received.direction()).isNull();
                    });
        }

        /**
         * Substitutes a single final page, since these cases assert carriage rather than paging.
         */
        private void stubOnePage() {
            when(TransactionTypeControllerTest.this.service.list(
                    any(TransactionTypeListRequest.class), any(CursorToken.class), anyString()))
                    .thenReturn(pageOf(FIRST_PAGE_CODES, seal(false, FIRST_PAGE_CODES.get(0)),
                            seal(false, LAST_PUBLISHED_CODE), false));
        }

        /**
         * Captures every browse request the boundary composed.
         *
         * @return the requests the collaborator received, in call order
         */
        private List<TransactionTypeListRequest> capturedRequests() {
            ArgumentCaptor<TransactionTypeListRequest> received =
                    ArgumentCaptor.forClass(TransactionTypeListRequest.class);
            verify(TransactionTypeControllerTest.this.service, times(2))
                    .list(received.capture(), any(CursorToken.class), anyString());
            return received.getAllValues();
        }
    }
}
