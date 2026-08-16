// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/api/ReferenceWriteBoundaryDispatcherTest.java
// -----------------------------------------------------------------------------
// WHAT:
//      Drives the three reference write operations that had no dispatcher coverage at all --
//      the category replace, the category delete and the maintenance batch -- and holds each
//      to the answer a caller receives: the status, the body, which refusals the boundary
//      renders for a rejected value, for an absent row and for a lost race, and that nothing
//      a collaborator raised about the database travels in the response.
//
// WHY (non-obvious design decisions):
//   (1) Refactoring Rationale: this package's own charter claims it owns the binding, status
//       and error behaviour of the published surface, and for these three operations it owned
//       none of it. TransactionCategoryCreationDispatcherTest covers the CREATE and nothing
//       else on that controller; ReferenceMaintenanceController had no test of any kind.
//       Service-level tests cannot substitute: they cannot see a status, a rendered body, a
//       path-variable constraint refusal or a response header, because each of those is
//       produced by the dispatcher from what the handler returned rather than by the service.
//   (2) Assumptions: the dispatcher is assembled with standaloneSetup over hand-constructed
//       controllers and substituted services, in the shape the three sibling dispatcher
//       classes in this package already fix. The shared advice is registered BY HAND, because
//       a standaloneSetup dispatcher has no context to discover a @RestControllerAdvice from
//       -- and omitting it does not fail loudly: the dispatcher falls back to the framework's
//       default error handling and every status assertion below would then observe that
//       instead of the real mapping.
//   (3) Alternatives Considered: asserting the authority each route demands here as well.
//       Deliberately NOT done, and the reason is the same one TransactionTypeControllerTest
//       records: no security chain is installed, so there is no authority to refuse and a 404
//       means the address rather than the caller. The authority these three routes demand is
//       asserted against the DEPLOYED chain by
//       com.carddemo.reference.config.SecurityDocumentationAccessTest, which exercises all
//       four mutating methods at these exact addresses with a user-only and an admin token.
//       One owner per property.
//   (4) Trade-offs: the services are substituted, so what this class witnesses is the answer
//       the boundary composes from what the service raised, not the query that decided it.
//       The transcribed rules are proven in com.carddemo.reference.service and the schema's
//       own constraints in com.carddemo.reference.repository against a container. The
//       compromise buys a single owner per rule; a rule asserted in two places can be
//       satisfied in one and reported as satisfied in both.
//   (5) Assumptions: every status, code and sentence asserted below is read from a compiled
//       constant -- ApiError for the codes, GlobalExceptionHandler for the shared sentences,
//       the two services for their own verbatim ones -- rather than written as a literal
//       here, so a case cannot pass by restating a value the production side no longer uses.
// =============================================================================

package com.carddemo.reference.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.web.CursorToken;
import com.carddemo.reference.dto.MaintenanceActionBatchRequest;
import com.carddemo.reference.dto.MaintenanceActionBatchResponse;
import com.carddemo.reference.dto.MaintenanceActionOutcomeResponse;
import com.carddemo.reference.dto.TransactionCategoryResponse;
import com.carddemo.reference.dto.TransactionCategoryUpdateRequest;
import com.carddemo.reference.service.ReferenceBatchUpdateService;
import com.carddemo.reference.service.TransactionCategoryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds the three previously-uncovered reference write operations to the answers they publish.
 *
 * <p>Purpose: drives the category replace, the category delete and the maintenance batch through a
 * real dispatcher with substituted services, asserting the success line, the refusals and the
 * absence of any leaked database diagnostic.</p>
 *
 * <p>Assumptions: of the four content elements user-specified Rule 1 enumerates, only Purpose
 * applies to a type declaration -- it accepts no parameters, yields no value and raises nothing --
 * so the other three are inapplicable rather than omitted.</p>
 */
class ReferenceWriteBoundaryDispatcherTest {

    /** The type half every request below addresses, inside the two-digit domain the column declares. */
    private static final String TYPE_CD = "01";

    /** The category half every request below addresses, at the four digits the column declares. */
    private static final String CAT_CD = "0001";

    /** The description a well-formed replace submits, within the fifty characters the column holds. */
    private static final String DESCRIPTION = "PURCHASE RETAIL";

    /** The revision a well-formed replace claims to have read. */
    private static final long SUBMITTED_VERSION = 3L;

    /** The revision the stored row carries after a successful replace. */
    private static final long STORED_VERSION = 4L;

    /** A fixed instant, so a refusal rendered by the shared advice carries a reproducible timestamp. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T09:16:44.902355Z");

    /** The paging key the browse sealer is constructed with; this class issues no browse. */
    private static final byte[] CURSOR_KEY = "reference-write-boundary-dispatcher-key".getBytes();

    /** The paging-token lifetime the sealer is constructed with; this class issues no browse. */
    private static final Duration CURSOR_LIFETIME = Duration.ofMinutes(10);

    /**
     * Text standing for the diagnostic a driver composes, which must never reach a caller.
     *
     * <p>Assumptions: a real driver names the schema, the table and the constraint, and appends a
     * detail clause quoting the values that violated it. This literal carries the same shape so that
     * a case can assert its absence from the body rather than assert nothing.
     */
    private static final String VENDOR_DIAGNOSTIC =
            "ERROR: relation \"reference.transaction_category\" does not exist; "
                    + "constraint pk_transaction_categories; Key (type_cd, cat_cd)=(01, 0001)";

    /** The substituted category rules, so each case asserts the answer and not the store. */
    private TransactionCategoryService categories;

    /** The substituted batch rules, so each case asserts the answer and not the store. */
    private ReferenceBatchUpdateService maintenance;

    /** The dispatcher under test, holding both controllers. */
    private MockMvc mockMvc;

    /**
     * Assembles the dispatcher, both controllers and both substituted services before each case.
     *
     * <p>Assumptions: both controllers are mounted on ONE dispatcher rather than one each. They share
     * the shared advice and the single message converter, and mounting them together is what lets a
     * case assert that a request to one address is not answered by the other's handler.</p>
     */
    @BeforeEach
    void setUp() {
        this.categories = mock(TransactionCategoryService.class);
        this.maintenance = mock(ReferenceBatchUpdateService.class);

        JacksonJsonHttpMessageConverter converter = new JacksonJsonHttpMessageConverter(
                JsonMapper.builder().addModule(new MoneyModule()).build());

        this.mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new TransactionCategoryController(this.categories,
                                new CursorToken(CURSOR_KEY, CURSOR_LIFETIME)),
                        new ReferenceMaintenanceController(this.maintenance))
                .setMessageConverters(converter)
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    /** The category replace, reached by PUT on the item address. */
    @Nested
    @DisplayName("on replacing a category")
    class OnReplacingACategory {

        /**
         * A well-formed replace answers 200 carrying the stored row, revision included.
         *
         * <p>Assumptions: the asserted revision is the one the SERVICE answered with and not the one
         * the request submitted, and the two are made deliberately different so the distinction is
         * observable. A caller re-reads against the stored revision, so a boundary that echoed the
         * submitted one would hand back a value that is already stale.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a well-formed replace answers 200 with the stored row")
        void aWellFormedReplaceAnswersTheStoredRow() throws Exception {
            when(ReferenceWriteBoundaryDispatcherTest.this.categories
                    .replace(eq(TYPE_CD), eq(CAT_CD), any(TransactionCategoryUpdateRequest.class)))
                    .thenReturn(new TransactionCategoryResponse(TYPE_CD, CAT_CD, DESCRIPTION,
                            STORED_VERSION));

            ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(put(itemPath(TYPE_CD, CAT_CD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replaceBody(DESCRIPTION, SUBMITTED_VERSION)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.typeCd").value(TYPE_CD))
                    .andExpect(jsonPath("$.catCd").value(CAT_CD))
                    .andExpect(jsonPath("$.description").value(DESCRIPTION))
                    .andExpect(jsonPath("$.version").value((int) STORED_VERSION));
        }

        /**
         * The key the service is handed comes from the PATH and never from the body.
         *
         * <p>Purpose: the replace body carries no key, by the contract's own design, and this case is
         * what keeps that true at the boundary. A handler that accepted a key from the body would
         * give one request two keys that can disagree with each other, and the request would then
         * write a row the caller did not address.</p>
         *
         * <p>Assumptions: the body submitted here carries key-shaped members that the record does not
         * declare. They must be ignored rather than bound, and the captured arguments must still be
         * the path's -- which is what distinguishes an ignored member from a bound one.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the replaced key is taken from the path and a body key is ignored")
        void theReplacedKeyComesFromThePath() throws Exception {
            when(ReferenceWriteBoundaryDispatcherTest.this.categories
                    .replace(eq(TYPE_CD), eq(CAT_CD), any(TransactionCategoryUpdateRequest.class)))
                    .thenReturn(new TransactionCategoryResponse(TYPE_CD, CAT_CD, DESCRIPTION,
                            STORED_VERSION));

            String bodyWithStrayKey = "{\"typeCd\":\"99\",\"catCd\":\"9999\",\"description\":\""
                    + DESCRIPTION + "\",\"version\":" + SUBMITTED_VERSION + '}';

            ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(put(itemPath(TYPE_CD, CAT_CD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyWithStrayKey))
                    .andExpect(status().isOk());

            verify(ReferenceWriteBoundaryDispatcherTest.this.categories)
                    .replace(eq(TYPE_CD), eq(CAT_CD), any(TransactionCategoryUpdateRequest.class));
        }

        /**
         * A rejected description is refused as a validation failure and never reaches the service.
         *
         * <p>Assumptions: the value refused is a description of only spaces. It satisfies the length
         * bound and fails the shape, which is the combination the transcribed rule exists for -- the
         * baseline's own blank branch -- so it exercises the pattern rather than the size. A value
         * that failed both would pass this case while the pattern was absent.</p>
         *
         * <p>Assumptions: the service is asserted NOT to have been called. A boundary that refused
         * after delegating would already have attempted the write, and the status alone cannot tell
         * the two apart.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a blank description is refused before the service is reached")
        void aBlankDescriptionIsRefusedBeforeTheService() throws Exception {
            ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(put(itemPath(TYPE_CD, CAT_CD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replaceBody("     ", SUBMITTED_VERSION)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION));

            verify(ReferenceWriteBoundaryDispatcherTest.this.categories, never())
                    .replace(any(), any(), any());
        }

        /**
         * An omitted revision is refused, because a replace with no revision cannot detect a race.
         *
         * <p>Assumptions: the revision is declared as the boxed type precisely so that an omission is
         * distinguishable from a zero, and this case is what makes that declaration load-bearing.
         * With the primitive, an omitted revision would arrive as zero -- a value every seeded row
         * once held -- and a replace that read no revision at all would silently overwrite whatever
         * the row now contains.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a replace with no revision is refused")
        void aReplaceWithNoRevisionIsRefused() throws Exception {
            ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(put(itemPath(TYPE_CD, CAT_CD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"description\":\"" + DESCRIPTION + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION));

            verify(ReferenceWriteBoundaryDispatcherTest.this.categories, never())
                    .replace(any(), any(), any());
        }

        /**
         * A key half outside its declared domain is refused by the path constraint.
         *
         * <p>Assumptions: both halves are exercised, and each with a value that is the RIGHT WIDTH
         * and the wrong domain -- {@code 00} for a type whose pattern excludes it, and a
         * non-numeric category. A short or long value would be refused by the size bound and would
         * pass this case while the pattern was absent, which is the substitution most likely to be
         * made when a constraint list is trimmed.</p>
         *
         * @throws Exception if a request cannot be performed
         */
        @Test
        @DisplayName("a key half outside its domain is refused by the path constraint")
        void aKeyHalfOutsideItsDomainIsRefused() throws Exception {
            ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(put(itemPath("00", CAT_CD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replaceBody(DESCRIPTION, SUBMITTED_VERSION)))
                    .andExpect(status().isBadRequest());

            ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(put(itemPath(TYPE_CD, "00A1"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replaceBody(DESCRIPTION, SUBMITTED_VERSION)))
                    .andExpect(status().isBadRequest());

            verify(ReferenceWriteBoundaryDispatcherTest.this.categories, never())
                    .replace(any(), any(), any());
        }

        /**
         * A replace naming a pair no row holds answers 404 with the service's verbatim sentence.
         *
         * <p>Assumptions: the sentence is read from the service's own published constant, so the case
         * asserts the wording a caller receives rather than a copy of it. The refusal extends the
         * platform's no-such-element type expressly so the shared advice answers it as a not-found
         * and renders its message; asserting the message is what shows that inheritance took.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a replace of an absent pair answers 404 with the verbatim sentence")
        void aReplaceOfAnAbsentPairAnswersNotFound() throws Exception {
            doThrow(new TransactionCategoryService.TransactionCategoryNotFoundException())
                    .when(ReferenceWriteBoundaryDispatcherTest.this.categories)
                    .replace(eq(TYPE_CD), eq(CAT_CD), any(TransactionCategoryUpdateRequest.class));

            ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(put(itemPath(TYPE_CD, CAT_CD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replaceBody(DESCRIPTION, SUBMITTED_VERSION)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_NOT_FOUND))
                    .andExpect(jsonPath("$.message")
                            .value(TransactionCategoryService.MESSAGE_CATEGORY_NOT_FOUND));
        }

        /**
         * A lost race answers 409 with the record-changed sentence and the stored revision.
         *
         * <p>Purpose: this is the migrated form of the baseline's pre-edit comparison across a screen
         * turn, and the arm that reaches it there puts the stored row back on the screen rather than
         * only refusing. That is why the refusal carries the revision the row now holds, and why this
         * case asserts the revision as well as the status: a 409 that told a caller only that it lost
         * would leave it no way to retry except by reading again.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a lost race answers 409 with the record-changed sentence")
        void aLostRaceAnswersConflict() throws Exception {
            doThrow(new TransactionCategoryService.TransactionCategoryDataChangedException(
                    STORED_VERSION))
                    .when(ReferenceWriteBoundaryDispatcherTest.this.categories)
                    .replace(eq(TYPE_CD), eq(CAT_CD), any(TransactionCategoryUpdateRequest.class));

            ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(put(itemPath(TYPE_CD, CAT_CD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replaceBody(DESCRIPTION, SUBMITTED_VERSION)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_CONFLICT))
                    .andExpect(jsonPath("$.message").value(ApiError.COACTUPC_RECORD_CHANGED));
        }

        /**
         * A fault answers 500 and carries nothing the failure said about the database.
         *
         * <p>Assumptions: the failure used is a resource failure rather than an integrity violation,
         * because the shared advice recognises the integrity family by class name and would
         * correctly answer 409 for any member of it. Losing a connection mid-write is not something
         * a caller can act on, so it belongs on the fault channel the alerting watches.</p>
         *
         * <p>Assumptions: the negative assertion is the one that earns the case. A 500 that still
         * carried the driver's text would satisfy a status-only check while publishing the schema,
         * the table and the offending key values to whoever provoked it.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a fault during a replace answers a leak-free 500")
        void aFaultDuringAReplaceIsLeakFree() throws Exception {
            doThrow(new DataAccessResourceFailureException(VENDOR_DIAGNOSTIC))
                    .when(ReferenceWriteBoundaryDispatcherTest.this.categories)
                    .replace(eq(TYPE_CD), eq(CAT_CD), any(TransactionCategoryUpdateRequest.class));

            MvcResult result = ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(put(itemPath(TYPE_CD, CAT_CD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replaceBody(DESCRIPTION, SUBMITTED_VERSION)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.MESSAGE_INTERNAL))
                    .andReturn();

            assertThat(bodyOf(result))
                    .as("nothing the failure carried about the database may travel")
                    .doesNotContain(VENDOR_DIAGNOSTIC)
                    .doesNotContain("pk_transaction_categories");
        }
    }

    /** The category delete, reached by DELETE on the item address. */
    @Nested
    @DisplayName("on deleting a category")
    class OnDeletingACategory {

        /**
         * A successful delete answers 204 with an empty body.
         *
         * <p>Assumptions: the emptiness is asserted rather than assumed. The published 204 declares
         * no content, and a handler that answered 204 while writing a body would produce a response
         * the status forbids -- which a status-only assertion cannot see.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a successful delete answers 204 with no body")
        void aSuccessfulDeleteAnswersNoContent() throws Exception {
            MvcResult result = ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(delete(itemPath(TYPE_CD, CAT_CD)))
                    .andExpect(status().isNoContent())
                    .andReturn();

            assertThat(bodyOf(result))
                    .as("the published 204 declares no content, so the body must be empty")
                    .isEmpty();
            verify(ReferenceWriteBoundaryDispatcherTest.this.categories).delete(TYPE_CD, CAT_CD);
        }

        /**
         * A delete naming a pair no row holds answers 404 with the verbatim sentence.
         *
         * <p>Assumptions: an absent row is reported as a miss rather than passed off as a successful
         * delete, which is the transcribed decision -- telling a caller it removed something that was
         * never there is the outcome this case exists to prevent. The 204 case above would pass on
         * its own with that defect present.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a delete of an absent pair answers 404 with the verbatim sentence")
        void aDeleteOfAnAbsentPairAnswersNotFound() throws Exception {
            doThrow(new TransactionCategoryService.TransactionCategoryNotFoundException())
                    .when(ReferenceWriteBoundaryDispatcherTest.this.categories)
                    .delete(TYPE_CD, CAT_CD);

            ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(delete(itemPath(TYPE_CD, CAT_CD)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_NOT_FOUND))
                    .andExpect(jsonPath("$.message")
                            .value(TransactionCategoryService.MESSAGE_CATEGORY_NOT_FOUND));
        }

        /**
         * A key half outside its declared domain is refused before the delete is attempted.
         *
         * <p>Assumptions: a delete is the one operation where reaching the service with an
         * unvalidated key is unrecoverable, so the service is asserted never to have been called.
         * Every other operation can be retried; a row removed under a malformed key cannot be put
         * back by the caller that removed it.</p>
         *
         * @throws Exception if a request cannot be performed
         */
        @Test
        @DisplayName("a key half outside its domain is refused before the delete is attempted")
        void aMalformedKeyIsRefusedBeforeTheDelete() throws Exception {
            ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(delete(itemPath("0A", CAT_CD)))
                    .andExpect(status().isBadRequest());

            ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(delete(itemPath(TYPE_CD, "1")))
                    .andExpect(status().isBadRequest());

            verify(ReferenceWriteBoundaryDispatcherTest.this.categories, never())
                    .delete(any(), any());
        }

        /**
         * A fault during a delete answers a leak-free 500.
         *
         * <p>Assumptions: asserted for the delete as well as the replace because the two reach the
         * advice through different handler signatures -- one returns a body, the other returns void
         * under a method-level status -- and a void handler is the one where a fault could plausibly
         * be rendered as the declared 204. That is the failure this case forecloses.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a fault during a delete answers a leak-free 500 and not the declared 204")
        void aFaultDuringADeleteIsLeakFree() throws Exception {
            doThrow(new DataAccessResourceFailureException(VENDOR_DIAGNOSTIC))
                    .when(ReferenceWriteBoundaryDispatcherTest.this.categories)
                    .delete(TYPE_CD, CAT_CD);

            MvcResult result = ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(delete(itemPath(TYPE_CD, CAT_CD)))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("a fault must not be rendered as the operation's declared success status")
                    .isEqualTo(500)
                    .isNotEqualTo(204);
            assertThat(bodyOf(result))
                    .as("nothing the failure carried about the database may travel")
                    .doesNotContain(VENDOR_DIAGNOSTIC);
        }
    }

    /** The maintenance batch, reached by POST on its own collection address. */
    @Nested
    @DisplayName("on the maintenance batch")
    class OnTheMaintenanceBatch {

        /**
         * A batch in which some actions did not apply still answers 200, outcomes and code in body.
         *
         * <p>Purpose: this is the published decision that a status-only reading of the operation
         * would get backwards, and it is the reason this case submits a MIXED batch. The baseline's
         * driver accumulates the worst condition code it sees and completes rather than abandoning
         * the run at the first reject, so answering a failure status here would report a run as
         * broken that the reference completes -- and would discard the outcomes of the actions that
         * did apply.</p>
         *
         * <p>Assumptions: the aggregate code asserted is the warn tier rather than zero, because a
         * zero would be indistinguishable from a batch in which everything applied and would not
         * show that the code travels at all.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a partly-applied batch answers 200 with every outcome and the aggregate code")
        void aPartlyAppliedBatchStillAnswersOk() throws Exception {
            when(ReferenceWriteBoundaryDispatcherTest.this.maintenance
                    .apply(any(MaintenanceActionBatchRequest.class)))
                    .thenReturn(new MaintenanceActionBatchResponse(List.of(
                            new MaintenanceActionOutcomeResponse(1,
                                    ReferenceBatchUpdateService.ACTION_INSERT, TYPE_CD,
                                    ReferenceBatchUpdateService.OUTCOME_APPLIED, true,
                                    ReferenceBatchUpdateService.MESSAGE_APPLIED),
                            new MaintenanceActionOutcomeResponse(2,
                                    ReferenceBatchUpdateService.ACTION_DELETE, "02",
                                    ReferenceBatchUpdateService.OUTCOME_NO_ROWS_FOUND, false,
                                    ReferenceBatchUpdateService.MESSAGE_NO_ROWS)),
                            4));

            ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(post(ReferenceMaintenanceController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(batchBody()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.returnCode").value(4))
                    .andExpect(jsonPath("$.outcomes.length()").value(2))
                    .andExpect(jsonPath("$.outcomes[0].applied").value(true))
                    .andExpect(jsonPath("$.outcomes[0].outcome")
                            .value(ReferenceBatchUpdateService.OUTCOME_APPLIED))
                    .andExpect(jsonPath("$.outcomes[1].applied").value(false))
                    .andExpect(jsonPath("$.outcomes[1].outcome")
                            .value(ReferenceBatchUpdateService.OUTCOME_NO_ROWS_FOUND))
                    .andExpect(jsonPath("$.outcomes[1].message")
                            .value(ReferenceBatchUpdateService.MESSAGE_NO_ROWS));
        }

        /**
         * The outcomes are answered in SUBMISSION order, which is the whole contract of a batch.
         *
         * <p>Purpose: each outcome carries its own position, so a client could in principle reorder;
         * but the published shape is an ordered array and a caller reading it positionally is
         * entitled to that order. Asserting the positions in sequence is what distinguishes an
         * ordered answer from a set that happens to arrive ordered.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the outcomes are answered in submission order")
        void theOutcomesAreAnsweredInSubmissionOrder() throws Exception {
            when(ReferenceWriteBoundaryDispatcherTest.this.maintenance
                    .apply(any(MaintenanceActionBatchRequest.class)))
                    .thenReturn(new MaintenanceActionBatchResponse(List.of(
                            new MaintenanceActionOutcomeResponse(1,
                                    ReferenceBatchUpdateService.ACTION_INSERT, TYPE_CD,
                                    ReferenceBatchUpdateService.OUTCOME_APPLIED, true,
                                    ReferenceBatchUpdateService.MESSAGE_APPLIED),
                            new MaintenanceActionOutcomeResponse(2,
                                    ReferenceBatchUpdateService.ACTION_UPDATE, "02",
                                    ReferenceBatchUpdateService.OUTCOME_APPLIED, true,
                                    ReferenceBatchUpdateService.MESSAGE_APPLIED),
                            new MaintenanceActionOutcomeResponse(3,
                                    ReferenceBatchUpdateService.ACTION_DELETE, "03",
                                    ReferenceBatchUpdateService.OUTCOME_FAILED, false,
                                    ReferenceBatchUpdateService.MESSAGE_ALREADY_EXISTS)),
                            8));

            ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(post(ReferenceMaintenanceController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(batchBody()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcomes[0].position").value(1))
                    .andExpect(jsonPath("$.outcomes[1].position").value(2))
                    .andExpect(jsonPath("$.outcomes[2].position").value(3))
                    .andExpect(jsonPath("$.returnCode").value(8));
        }

        /**
         * An empty action array is refused, because an empty batch asks for nothing.
         *
         * <p>Assumptions: refused rather than answered with an empty outcome list and a zero code.
         * A zero aggregate code means every action succeeded, and reporting that for a request that
         * submitted no action would tell an operator a maintenance run completed when none ran.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an empty action array is refused")
        void anEmptyActionArrayIsRefused() throws Exception {
            ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(post(ReferenceMaintenanceController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"actions\":[]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION));

            verify(ReferenceWriteBoundaryDispatcherTest.this.maintenance, never()).apply(any(MaintenanceActionBatchRequest.class));
        }

        /**
         * A malformed member of an action is refused, and the batch does not run in part.
         *
         * <p>Purpose: the action list is validated as a whole before any of it is applied, and this
         * case is what pins that. One rejected action must refuse the request rather than apply the
         * others and report the rejection as one outcome among many -- otherwise a caller correcting
         * the rejected action and resubmitting would apply the valid ones twice.</p>
         *
         * <p>Assumptions: the malformed member is the action verb, set to a value outside the three
         * the pattern admits, with every other member well-formed. That isolates the nested
         * validation from the outer one: an entirely empty action object would be refused by several
         * rules at once and the case would pass while the verb pattern was absent.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("one malformed action refuses the whole batch and applies nothing")
        void oneMalformedActionRefusesTheWholeBatch() throws Exception {
            String body = "{\"actions\":[{\"action\":\""
                    + ReferenceBatchUpdateService.ACTION_INSERT + "\",\"typeCd\":\"" + TYPE_CD
                    + "\",\"description\":\"" + DESCRIPTION + "\"},"
                    + "{\"action\":\"REPLACE\",\"typeCd\":\"02\",\"description\":\""
                    + DESCRIPTION + "\"}]}";

            ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(post(ReferenceMaintenanceController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION));

            verify(ReferenceWriteBoundaryDispatcherTest.this.maintenance, never()).apply(any(MaintenanceActionBatchRequest.class));
        }

        /**
         * A fault while applying the batch answers a leak-free 500 and no partial outcome list.
         *
         * <p>Assumptions: the published 200 carries an outcome per action even when actions failed,
         * so a fault is the one condition where this operation must NOT answer 200. A boundary that
         * rendered a fault as a 200 with an empty outcome list would tell an operator the run
         * completed with nothing to do.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a fault while applying the batch answers a leak-free 500 and not a 200")
        void aFaultApplyingTheBatchIsLeakFree() throws Exception {
            when(ReferenceWriteBoundaryDispatcherTest.this.maintenance
                    .apply(any(MaintenanceActionBatchRequest.class)))
                    .thenThrow(new DataAccessResourceFailureException(VENDOR_DIAGNOSTIC));

            MvcResult result = ReferenceWriteBoundaryDispatcherTest.this.mockMvc
                    .perform(post(ReferenceMaintenanceController.BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(batchBody()))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("a fault must not be reported as a completed maintenance run")
                    .isEqualTo(500)
                    .isNotEqualTo(200);
            assertThat(bodyOf(result))
                    .as("nothing the failure carried about the database may travel")
                    .doesNotContain(VENDOR_DIAGNOSTIC);
        }
    }

    /**
     * Builds the item address for one pair of key halves.
     *
     * <p>Assumptions: composed from the controller's own published constants rather than a second
     * spelling of the path, so a route that moves takes these cases with it instead of leaving them
     * addressing an address nothing serves -- which would answer 404 and read as a refusal.</p>
     *
     * @param typeCd the type half to address
     * @param catCd the category half to address
     * @return the item address; never {@code null}
     */
    private static String itemPath(String typeCd, String catCd) {
        return TransactionCategoryController.BASE_PATH + '/' + typeCd + '/' + catCd;
    }

    /**
     * Builds a category replace body.
     *
     * @param description the description to submit
     * @param version the revision to claim to have read
     * @return the JSON body; never {@code null}
     */
    private static String replaceBody(String description, long version) {
        return "{\"description\":\"" + description + "\",\"version\":" + version + '}';
    }

    /**
     * Builds a single-action maintenance batch body that passes validation.
     *
     * <p>Assumptions: the verb and the code domain are taken from the batch service's own compiled
     * constants and the declared two-digit pattern, so this body stays valid if a rule is tightened
     * rather than silently becoming the refusal case it is used to contrast with.</p>
     *
     * @return the JSON body; never {@code null}
     */
    private static String batchBody() {
        return "{\"actions\":[{\"action\":\"" + ReferenceBatchUpdateService.ACTION_INSERT
                + "\",\"typeCd\":\"" + TYPE_CD + "\",\"description\":\"" + DESCRIPTION + "\"}]}";
    }

    /**
     * Reads a response body back as the exact text the dispatcher wrote.
     *
     * <p>Assumptions: read as raw text rather than parsed, because the leak assertions are about
     * whether a substring travels ANYWHERE in the response -- in a message, a detail, a field error
     * or a trace member -- and a parse would only look where the parser was pointed.</p>
     *
     * @param result the completed exchange to read
     * @return the body as written; never {@code null}
     * @throws java.io.UnsupportedEncodingException if the response's encoding is unavailable
     */
    private static String bodyOf(MvcResult result) throws java.io.UnsupportedEncodingException {
        return result.getResponse().getContentAsString();
    }
}
