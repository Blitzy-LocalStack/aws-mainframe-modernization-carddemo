package com.carddemo.reference.api;

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
// =============================================================================

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
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
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries
 * no parameter, return or exception tag. The inapplicability is stated rather than left silent,
 * because the Explainability rule lists a docstring that omits its parameters or return values
 * among its forbidden patterns at line 39 and a reader has to be able to tell a declared
 * inapplicability from an oversight.
 */
class TransactionTypeControllerTest {

    /** A seeded type code, the highest of the seven the reference data carries. */
    private static final String TYPE_CD = "07";

    /** A well-formed code the domain admits but no row holds, for the absent-row paths. */
    private static final String ABSENT_TYPE_CD = "42";

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
     * <p>Assumptions: {@code 42P01} is an undefined-table condition, chosen because it is neither
     * of the two states the service classifies -- {@code 23503} for a referencing row and
     * {@code 23505} for a duplicate key -- so it exercises the arm that hands the failure back
     * unchanged.
     */
    private static final String UNCLASSIFIED_SQLSTATE = "42P01";

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
    private TransactionTypeService service;

    /** The cursor codec both this class and the handler under test read positions through. */
    private CursorToken sealer;

    /** The dispatcher under test. */
    private MockMvc mockMvc;

    /** The mapper the assertions read a response body back through. */
    private JsonMapper mapper;

    /**
     * Assembles the dispatcher, the substituted collaborator and the cursor codec before each case.
     *
     * <p>Assumptions: the message converter is built over the shared money module even though
     * nothing on this surface carries money, because the converter this dispatcher installs
     * replaces the framework's defaults wholesale -- so it has to be the one the running service
     * uses rather than a reduced stand-in, or a serialisation difference would go unnoticed here
     * and appear in production.
     */
    @BeforeEach
    void setUp() {
        this.service = mock(TransactionTypeService.class);
        this.sealer = new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);
        this.mapper = JsonMapper.builder().addModule(new MoneyModule()).build();

        JacksonJsonHttpMessageConverter converter = new JacksonJsonHttpMessageConverter(this.mapper);

        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new TransactionTypeController(this.service, this.sealer))
                .defaultRequest(get("/").principal(CALLER))
                .setMessageConverters(converter)
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
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
     * Counts how many times a sentence occurs in a body.
     *
     * @param body the body to search
     * @param sentence the sentence to count
     * @return the number of non-overlapping occurrences
     */
    private static int occurrencesOf(String body, String sentence) {
        int found = 0;
        for (int at = body.indexOf(sentence); at >= 0; at = body.indexOf(sentence, at + 1)) {
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
     * link: that the refusal SURFACES as a conflict carrying the baseline's sentence, and that a
     * failure the service could not classify does not.
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
         * An integrity refusal still answers 409 when the service did not classify its state.
         *
         * <p>Assumptions: the referential guarantee does not depend on the service recognising the
         * state, and this case is what establishes that. The service classifies two states, 23503
         * for a referencing row and 23505 for a duplicate key, and hands any other integrity
         * failure back unchanged. The shared advice then recognises the failure by class name while
         * walking the cause chain -- which is why it needs no dependency on a driver -- and answers
         * 409 with the same referential sentence. So a state nobody anticipated cannot downgrade
         * the refusal into a fault, which is the failure mode the ON DELETE RESTRICT semantic must
         * survive.
         *
         * <p>Trade-offs: because the advice already answers correctly for the whole family, the
         * service's classification buys the SENTENCE and not the status for this table -- both of
         * the states it recognises reach the same referential wording here. That redundancy is
         * accepted deliberately: it is what makes the status robust to an unrecognised state, and
         * the cost is one classification step whose effect on this surface is invisible.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an integrity refusal answers 409 even when its state was not classified")
        void anUnclassifiedIntegrityStateStillAnswersConflict() throws Exception {
            doThrow(integrityViolation(UNCLASSIFIED_SQLSTATE))
                    .when(TransactionTypeControllerTest.this.service).delete(TYPE_CD);

            MvcResult result = TransactionTypeControllerTest.this.mockMvc
                    .perform(delete(itemPath(TYPE_CD)))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("an integrity refusal is a caller error whatever state it reports;"
                            + " answering 500 here would report a blocked delete as a fault")
                    .isEqualTo(409);
            assertThat(messageOf(result))
                    .isEqualTo(GlobalExceptionHandler.MESSAGE_REFERENCED_ROW);
            assertThat(rawBodyOf(result))
                    .as("the driver's own text names a relation and a key and must not travel")
                    .doesNotContain(VENDOR_DIAGNOSTIC)
                    .doesNotContain(UNCLASSIFIED_SQLSTATE);
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
         * The position a caller returns reaches the browse exactly as it was minted.
         *
         * <p>Assumptions: the position is opaque to a client, so the boundary must hand it on
         * unaltered; a boundary that normalised, trimmed or re-encoded it would invalidate the seal
         * and turn a legitimate continuation into a refusal.
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the returned position reaches the browse verbatim with the matching direction")
        void theReturnedPositionReachesTheBrowseVerbatim() throws Exception {
            String minted = seal(false, LAST_PUBLISHED_CODE);
            stubSecondPage();

            TransactionTypeControllerTest.this.mockMvc
                    .perform(get(TransactionTypeController.BASE_PATH)
                            .param(TransactionTypeController.PARAM_CURSOR, minted)
                            .param(TransactionTypeController.PARAM_DIRECTION,
                                    PageDirection.NEXT.wireValue()))
                    .andReturn();

            assertThat(capturedRequest().cursor())
                    .as("handed on unaltered; any rewriting would break the seal")
                    .isEqualTo(minted);
            assertThat(capturedRequest().direction()).isEqualTo(PageDirection.NEXT);
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
                    .as("the key it stands for is recoverable only through the codec")
                    .doesNotContain(LAST_PUBLISHED_CODE);
            assertThat(lastKey).startsWith(CursorToken.VERSION + ".");
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
         */
        private void stubSecondPage() {
            when(TransactionTypeControllerTest.this.service.list(
                    any(TransactionTypeListRequest.class), any(CursorToken.class), anyString()))
                    .thenReturn(pageOf(SECOND_PAGE_CODES, seal(false, SURPLUS_CODE),
                            seal(false, SURPLUS_CODE), false));
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
     * one is a behavioural change. Two of the sentences below carry a defect the baseline carries,
     * and repairing either would be exactly that change.
     *
     * <p>Assumptions: every sentence is asserted THROUGH the constant that publishes it and never
     * against a copy retyped here. That is the difference between checking that the boundary
     * publishes the catalogue's sentence and checking that it publishes whatever this file happens
     * to say -- the second passes when both are wrong in the same way.
     *
     * <p>Trade-offs: the baseline's full screen catalogue is wider than what this surface can
     * publish, and the difference is bounded deliberately rather than papered over. Two further
     * defective literals sit in
     * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} -- the save prompt at line 161, missing
     * the space after its period, and the exit notice at line 170, missing the space after
     * its period and carrying trailing spaces that are part of the literal. Both are screen-turn
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
         * form and the absence of the one-word form are asserted, because a repair would substitute
         * one for the other and an assertion on only the first would still pass if the sentence had
         * been rewritten around it.
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
         * <p>Assumptions: all five are driven through their real routes rather than composed here,
         * so what a case inspects is a body that actually travelled.
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
         * @return one body per refusal path plus one for the fault path, in no particular order
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
                            .perform(get(itemPath(TYPE_CD))).andReturn()));
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
         * Two identical requests are answered identically, byte for byte.
         *
         * <p>Assumptions: comparable byte for byte because the advice is constructed over a fixed
         * clock, so the only thing that could differ between two renderings of one refusal is the
         * timestamp, and it does not. Without that the case would have to compare selected members
         * and would stop seeing a difference in any member it did not name.
         *
         * @throws Exception if a request cannot be performed
         */
        @Test
        @DisplayName("two identical requests answer identically and are each answered afresh")
        void twoIdenticalRequestsAreAnsweredIdentically() throws Exception {
            doThrow(new RecordConflictException(RecordConflictException.Kind.REFERENCED_ROW))
                    .when(TransactionTypeControllerTest.this.service).delete(TYPE_CD);

            String first = rawBodyOf(TransactionTypeControllerTest.this.mockMvc
                    .perform(delete(itemPath(TYPE_CD))).andReturn());
            String second = rawBodyOf(TransactionTypeControllerTest.this.mockMvc
                    .perform(delete(itemPath(TYPE_CD))).andReturn());

            assertThat(second)
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
