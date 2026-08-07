package com.carddemo.reference.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.RecordConflictException;
import com.carddemo.reference.domain.DisclosureGroup;
import com.carddemo.reference.domain.DisclosureGroup.DisclosureGroupId;
import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.DisclosureGroupRateResponse;
import com.carddemo.reference.dto.MaintenanceActionBatchRequest;
import com.carddemo.reference.dto.MaintenanceActionBatchResponse;
import com.carddemo.reference.dto.MaintenanceActionRequest;
import com.carddemo.reference.dto.TransactionTypeUpdateRequest;
import com.carddemo.reference.repository.DisclosureGroupRepository;
import com.carddemo.reference.repository.TransactionCategoryRepository;
import com.carddemo.reference.repository.TransactionTypeRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Proves the three baseline write behaviours stay distinct, and that the rate fallback is not total.
 *
 * <p>Purpose: this class asserts the distinctions registered as {@code D-REFERENCE-UPSERT-NOT-EXPOSED}
 * and the terminal-miss condition the service charter records. Each case asserts what did NOT happen as
 * well as what did, because every one of these behaviours differs from its neighbour precisely in the
 * write it declines to perform.</p>
 */
@DisplayName("the reference write behaviours")
class ReferenceWriteBehaviourTest {

    /** A canonical two-character type code. */
    private static final String TYPE_CD = "01";

    /** A canonical four-digit category code. */
    private static final String CAT_CD = "0005";

    /** The blank-padded default account group, exactly as the seed stores it. */
    private static final String DEFAULT_GROUP = "DEFAULT   ";

    /** A requested account group that the fixtures give no row for. */
    private static final String MISSING_GROUP = "GROUPZZZ  ";

    /** The transaction-type table double. */
    private TransactionTypeRepository types;

    /** The category table double, used only where a delete is diagnosed. */
    private TransactionCategoryRepository categories;

    /** The disclosure-group table double. */
    private DisclosureGroupRepository groups;

    /** Prepares fresh doubles for each case so no case can observe another's interactions. */
    @BeforeEach
    void setUp() {
        this.types = Mockito.mock(TransactionTypeRepository.class);
        this.categories = Mockito.mock(TransactionCategoryRepository.class);
        this.groups = Mockito.mock(DisclosureGroupRepository.class);
    }

    /** Cases over the strict replace, which must report a miss rather than inserting. */
    @Nested
    @DisplayName("on the strict replace")
    class OnTheStrictReplace {

        /**
         * A replace of a code that does not exist reports the miss and writes nothing.
         *
         * <p>Assumptions: the assertion that NOTHING was written is the point of this case, and a
         * message-only assertion could not make it. An upsert would answer this same call successfully,
         * so only the absence of a save distinguishes the strict behaviour from the maintenance
         * screen's.</p>
         */
        @Test
        @DisplayName("report the miss and insert nothing")
        void reportTheMissAndInsertNothing() {
            when(types.findByTypeCd("77")).thenReturn(Optional.empty());
            TransactionTypeService service = new TransactionTypeService(types, categories);

            assertThatThrownBy(() ->
                    service.replace("77", new TransactionTypeUpdateRequest("Anything", 0L)))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessage(TransactionTypeService.MESSAGE_TYPE_NOT_FOUND);

            verify(types, never()).save(any());
        }

        /**
         * A replace carrying a stale version is refused and carries the stored version back.
         *
         * <p>Assumptions: the stored version is asserted on the refusal, not merely the refusal itself. A
         * conflict that did not report which revision won would leave a caller with no value to retry
         * against, so it would have to re-read and could lose the race again.</p>
         */
        @Test
        @DisplayName("refuse a stale version and report the stored one")
        void refuseAStaleVersionAndReportTheStoredOne() {
            TransactionType stored = new TransactionType(TYPE_CD, "Purchase");
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));
            TransactionTypeService service = new TransactionTypeService(types, categories);

            assertThatThrownBy(() ->
                    service.replace(TYPE_CD, new TransactionTypeUpdateRequest("Changed", 7L)))
                    .isInstanceOf(RecordConflictException.class)
                    .extracting(failure -> ((RecordConflictException) failure).currentVersion())
                    .isEqualTo(stored.getVersion());

            verify(types, never()).save(any());
        }

        /**
         * A create refuses a code that already exists rather than replacing it.
         *
         * <p>Assumptions: this is the other half of the granularity decision. If a create silently
         * replaced, the pair of operations would together behave as an upsert and the registered
         * divergence would be untrue.</p>
         */
        @Test
        @DisplayName("refuse a create whose code already exists")
        void refuseACreateWhoseCodeAlreadyExists() {
            when(types.findByTypeCd(TYPE_CD))
                    .thenReturn(Optional.of(new TransactionType(TYPE_CD, "Purchase")));
            TransactionTypeService service = new TransactionTypeService(types, categories);

            assertThatThrownBy(() -> service.create(
                    new com.carddemo.reference.dto.TransactionTypeCreateRequest(
                            TYPE_CD, "Duplicate")))
                    .isInstanceOf(RecordConflictException.class);

            verify(types, never()).save(any());
        }
    }

    /** Cases over the maintenance batch, which must tolerate a reject and continue. */
    @Nested
    @DisplayName("on the maintenance batch")
    class OnTheMaintenanceBatch {

        /**
         * A rejected action does not stop the actions after it.
         *
         * <p>Assumptions: the later action's write is verified, not merely the reply's length. A run that
         * built three outcomes while writing nothing after the first reject would satisfy a
         * count assertion and would still have abandoned itself.</p>
         */
        @Test
        @DisplayName("apply later actions after an earlier one is rejected")
        void applyLaterActionsAfterAnEarlierOneIsRejected() {
            when(types.findByTypeCd("02")).thenReturn(Optional.empty());
            when(types.findByTypeCd("03")).thenReturn(Optional.empty());
            ReferenceBatchUpdateService service = new ReferenceBatchUpdateService(types);

            MaintenanceActionBatchResponse reply = service.apply(
                    new MaintenanceActionBatchRequest(List.of(
                            new MaintenanceActionRequest("UPDATE", "02", "Missing row"),
                            new MaintenanceActionRequest("INSERT", "03", "Credit"))));

            assertThat(reply.outcomes()).hasSize(2);
            assertThat(reply.outcomes().get(0).outcome())
                    .isEqualTo(ReferenceBatchUpdateService.OUTCOME_NO_ROWS_FOUND);
            assertThat(reply.outcomes().get(0).applied()).isFalse();
            assertThat(reply.outcomes().get(1).outcome())
                    .isEqualTo(ReferenceBatchUpdateService.OUTCOME_APPLIED);
            verify(types).save(any(TransactionType.class));
        }

        /**
         * The aggregate condition code is the worst seen and the run still reports success per action.
         *
         * <p>Assumptions: a soft warn rather than a failure, which is the baseline's own grading -- its
         * driver sets one tolerated code for every failure rather than distinguishing them.</p>
         */
        @Test
        @DisplayName("aggregate the worst condition code seen")
        void aggregateTheWorstConditionCodeSeen() {
            when(types.findByTypeCd("02")).thenReturn(Optional.empty());
            ReferenceBatchUpdateService service = new ReferenceBatchUpdateService(types);

            MaintenanceActionBatchResponse reply = service.apply(
                    new MaintenanceActionBatchRequest(List.of(
                            new MaintenanceActionRequest("DELETE", "02", null))));

            assertThat(reply.returnCode())
                    .isEqualTo(ReferenceBatchUpdateService.RETURN_CODE_SOFT_WARN);
        }

        /** A run in which every action applied reports the clean condition code. */
        @Test
        @DisplayName("report the clean code when every action applied")
        void reportTheCleanCodeWhenEveryActionApplied() {
            when(types.findByTypeCd("04")).thenReturn(Optional.empty());
            ReferenceBatchUpdateService service = new ReferenceBatchUpdateService(types);

            MaintenanceActionBatchResponse reply = service.apply(
                    new MaintenanceActionBatchRequest(List.of(
                            new MaintenanceActionRequest("INSERT", "04", "Authorization"))));

            assertThat(reply.returnCode())
                    .isEqualTo(ReferenceBatchUpdateService.RETURN_CODE_CLEAN);
            assertThat(reply.outcomes().get(0).applied()).isTrue();
        }
    }

    /** Cases over the rate lookup, whose fallback is a substitution and is not total. */
    @Nested
    @DisplayName("on the rate lookup")
    class OnTheRateLookup {

        /** A direct hit reports the group asked for and does not flag a fallback. */
        @Test
        @DisplayName("report a direct hit without flagging a fallback")
        void reportADirectHitWithoutFlaggingAFallback() {
            DisclosureGroupId id = new DisclosureGroupId(MISSING_GROUP, TYPE_CD, CAT_CD);
            when(groups.findByIdIs(id))
                    .thenReturn(Optional.of(new DisclosureGroup(id, new BigDecimal("15.00"))));
            DisclosureGroupService service = new DisclosureGroupService(groups);

            DisclosureGroupRateResponse reply =
                    service.resolveRate(MISSING_GROUP, TYPE_CD, CAT_CD);

            assertThat(reply.defaultGroupApplied()).isFalse();
            assertThat(reply.appliedAcctGroupId()).isEqualTo(MISSING_GROUP);
            assertThat(reply.interestRate().amount()).isEqualByComparingTo("15.00");
        }

        /**
         * A miss falls back to the default group, replacing the group component alone.
         *
         * <p>Assumptions: the type and the category are asserted unchanged on the reply. A fallback that
         * also generalised either of them would return the rate of a different product, and because the
         * lookup does not fail there would be no symptom other than the wrong money.</p>
         */
        @Test
        @DisplayName("fall back to the default group, replacing the group component alone")
        void fallBackToTheDefaultGroupReplacingTheGroupAlone() {
            when(groups.findByIdIs(new DisclosureGroupId(MISSING_GROUP, TYPE_CD, CAT_CD)))
                    .thenReturn(Optional.empty());
            DisclosureGroupId fallbackId =
                    new DisclosureGroupId(DEFAULT_GROUP, TYPE_CD, CAT_CD);
            when(groups.findByIdIs(fallbackId))
                    .thenReturn(Optional.of(new DisclosureGroup(fallbackId, new BigDecimal("25.00"))));
            DisclosureGroupService service = new DisclosureGroupService(groups);

            DisclosureGroupRateResponse reply =
                    service.resolveRate(MISSING_GROUP, TYPE_CD, CAT_CD);

            assertThat(reply.defaultGroupApplied()).isTrue();
            assertThat(reply.requestedAcctGroupId()).isEqualTo(MISSING_GROUP);
            assertThat(reply.appliedAcctGroupId()).isEqualTo(DEFAULT_GROUP);
            assertThat(reply.tranTypeCd()).isEqualTo(TYPE_CD);
            assertThat(reply.tranCatCd()).isEqualTo(CAT_CD);
        }

        /**
         * A pair with no row in any group, the default included, is a terminal miss.
         *
         * <p>Assumptions: this case exists because the charter records that the fallback is NOT total.
         * Treating it as exhaustive would leave an absent rate flowing onward and would accrue nothing
         * while reporting success.</p>
         */
        @Test
        @DisplayName("report a terminal miss when even the default group has no row")
        void reportATerminalMissWhenEvenTheDefaultGroupHasNoRow() {
            when(groups.findByIdIs(any())).thenReturn(Optional.empty());
            DisclosureGroupService service = new DisclosureGroupService(groups);

            assertThatThrownBy(() -> service.resolveRate(MISSING_GROUP, TYPE_CD, CAT_CD))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessage(DisclosureGroupService.MESSAGE_RATE_NOT_FOUND);
        }

        /** The rate lookup never touches the transaction-type or category tables. */
        @Test
        @DisplayName("read no table but the disclosure groups")
        void readNoTableButTheDisclosureGroups() {
            DisclosureGroupId id = new DisclosureGroupId(DEFAULT_GROUP, TYPE_CD, CAT_CD);
            when(groups.findByIdIs(any()))
                    .thenReturn(Optional.of(new DisclosureGroup(id, new BigDecimal("0.00"))));
            DisclosureGroupService service = new DisclosureGroupService(groups);

            service.resolveRate(DEFAULT_GROUP, TYPE_CD, CAT_CD);

            verifyNoInteractions(types);
            verifyNoInteractions(categories);
        }
    }
}
