package com.carddemo.reference.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.reference.domain.DisclosureGroup;
import com.carddemo.reference.domain.DisclosureGroup.DisclosureGroupId;
import com.carddemo.reference.domain.TransactionCategory;
import com.carddemo.reference.domain.TransactionCategory.TransactionCategoryId;
import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.DisclosureGroupRateResponse;
import com.carddemo.reference.dto.MaintenanceActionBatchRequest;
import com.carddemo.reference.dto.MaintenanceActionBatchResponse;
import com.carddemo.reference.dto.MaintenanceActionRequest;
import com.carddemo.reference.dto.TransactionCategoryCreateRequest;
import com.carddemo.reference.dto.TransactionCategoryResponse;
import com.carddemo.reference.dto.TransactionCategoryUpdateRequest;
import com.carddemo.reference.dto.TransactionTypeCreateRequest;
import com.carddemo.reference.dto.TransactionTypeUpdateRequest;
import com.carddemo.reference.mapper.TransactionCategoryMapper;
import com.carddemo.reference.mapper.TransactionTypeMapper;
import com.carddemo.reference.repository.DisclosureGroupRepository;
import com.carddemo.reference.repository.TransactionCategoryRepository;
import com.carddemo.reference.repository.TransactionTypeRepository;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
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

    /** The category table double, driving the category write cases and the diagnosed delete. */
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
         * A stale version is refused even when the submission would change nothing.
         *
         * <p>Refactoring Rationale: the no-change short circuit used to run BEFORE the revision was
         * compared, and returning the stored row was defended on the ground that a write altering no column
         * can lose no update. That was true about the WRITE and false about the ANSWER. The comparison is
         * made against the row as it stands NOW, so the two tests part company in exactly the case
         * concurrency control exists for: when another writer has already set the description to the value
         * this caller is submitting, the submission equals the current row, the old order answered 200, and
         * the caller was told its edit had been accepted when what it was shown was somebody else's.</p>
         *
         * <p>Assumptions: the reference compares against the BEFORE-IMAGE and not against the stored row.
         * {@code 1205-COMPARE-OLD-NEW} at physical lines 783 to 797 of
         * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} compares the new screen value against the
         * value the screen carried across the pseudo-conversation, so a row changed underneath the caller
         * fails that comparison, reaches the write path and is refused there.</p>
         *
         * <p>Assumptions: the submitted description EQUALS the stored one, which is what makes this case
         * distinct from the stale-version case above. That case submits a changed description, so it would
         * be refused under either ordering; only an unchanged submission distinguishes them.</p>
         */
        @Test
        @DisplayName("refuse a stale version even when the submission would change nothing")
        void refuseAStaleVersionEvenWhenNothingWouldChange() {
            TransactionType stored = new TransactionType(TYPE_CD, "Purchase");
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));
            TransactionTypeService service = new TransactionTypeService(types, categories);

            assertThatThrownBy(() ->
                    service.replace(TYPE_CD, new TransactionTypeUpdateRequest("Purchase", 7L)))
                    .isInstanceOf(RecordConflictException.class)
                    .extracting(failure -> ((RecordConflictException) failure).currentVersion())
                    .isEqualTo(stored.getVersion());

            verify(types, never()).saveAndFlush(any());
        }

        /**
         * An unchanged submission from a CURRENT caller is still accepted without a write.
         *
         * <p>Assumptions: this is the other half of the ordering change and it is asserted so the fix
         * cannot be read as having folded the no-change outcome into the conflict. The baseline keeps the
         * two apart and answers them differently: physical lines 743 to 750 leave the edit path without
         * reaching the write at all when nothing differs, displaying the sentence at physical lines 179 and
         * 180.</p>
         */
        @Test
        @DisplayName("accept an unchanged submission from a current caller without writing")
        void acceptAnUnchangedSubmissionFromACurrentCaller() {
            TransactionType stored = new TransactionType(TYPE_CD, "Purchase");
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));
            TransactionTypeService service = new TransactionTypeService(types, categories);

            assertThat(service.replace(TYPE_CD,
                    new TransactionTypeUpdateRequest("Purchase", stored.getVersion()))
                    .description())
                    .isEqualTo("Purchase");

            verify(types, never()).saveAndFlush(any());
        }

        /**
         * The write is FLUSHED inside the block whose catches classify its failure.
         *
         * <p>Refactoring Rationale: a plain save inside a transaction schedules the statement in the
         * persistence context and the statement does not reach the database until the unit of work commits,
         * which happens after the method returns -- so the constraint violation the catches exist for was
         * raised outside them, every time, and the classification never ran. A caller who hit a unique
         * violation was answered with the referential sentence by the shared fallback and told to go and
         * delete child records.</p>
         *
         * <p>Assumptions: the flushing form is asserted rather than the classification outcome, because the
         * outcome is only reachable through a real database and this is a unit case. Asserting which
         * repository member is called is what pins the property a mock CAN observe; the classification
         * itself is exercised by the repository integration cases.</p>
         */
        @Test
        @DisplayName("flush the replace inside the classifying block")
        void flushTheReplaceInsideTheClassifyingBlock() {
            TransactionType stored = new TransactionType(TYPE_CD, "Purchase");
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));
            when(types.saveAndFlush(stored)).thenReturn(stored);
            TransactionTypeService service = new TransactionTypeService(types, categories);

            service.replace(TYPE_CD, new TransactionTypeUpdateRequest("Changed", stored.getVersion()));

            verify(types).saveAndFlush(stored);
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
                    .isInstanceOf(RecordConflictException.class)
                    // WHY : Refactoring Rationale: the KIND is asserted where this case previously
                    //       asserted only the type, and the kind asserted is now the insert refusal. It
                    //       was raised as STALE_VERSION first -- telling a caller somebody else had
                    //       edited a row it was trying to create -- and then as REFERENCED_ROW, which
                    //       renders 'Please delete associated child records first:'. That sentence is
                    //       composed in the baseline's DELETE paragraph at physical line 1641 of
                    //       COTRTUPC.cbl and cannot be reached by an insert, and its remedy is wrong for
                    //       this condition: a caller that reused a code has no dependents to remove. The
                    //       baseline composes its insert refusal in 9700-INSERT-RECORD at physical lines
                    //       1607 to 1618, naming the table the insert was aimed at, and that is what the
                    //       kind now selects. A type-only assertion passed against every one of the
                    //       three, which is why it caught neither contradiction.
                    .extracting(failure -> ((RecordConflictException) failure).kind())
                    .isEqualTo(RecordConflictException.Kind.INSERT_REFUSED);

            // WHY : Assumptions: the TABLE is asserted as well as the kind, because the sentence a caller
            //       reads is composed around it. A refusal carrying the sibling service's table would
            //       satisfy the assertion above and name the wrong table in the message band.
            assertThatThrownBy(() -> service.create(
                    new com.carddemo.reference.dto.TransactionTypeCreateRequest(
                            TYPE_CD, "Duplicate")))
                    .extracting(failure -> ((RecordConflictException) failure).targetTable())
                    .isEqualTo("TRANSACTION_TYPE");

            verify(types, never()).save(any());
            verify(types, never()).saveAndFlush(any());
        }

        /**
         * A stale version is refused even when the submitted description differs in nothing.
         *
         * <p>Purpose: this is the ordering the version precondition depends on. The published contract
         * makes the precondition unconditional -- "when the stored row has moved on since that read the
         * write is refused with 409" -- so a stale token has to be refused whatever the body says.</p>
         *
         * <p>Refactoring Rationale: the service evaluated its no-change short-circuit FIRST, so this call
         * answered 200 carrying the current row. A caller reads that as confirmation that its submission
         * was applied to the state it had read, and both halves of that reading are false: its token was
         * stale, and the row it was handed is not the row it based the submission on.</p>
         *
         * <p>Assumptions: the description submitted is byte-identical to the stored one, because that is
         * the ONLY input for which the two orderings differ. Any other description reaches the conflict
         * under either ordering, so a case using one would pass without testing the ordering at all.</p>
         */
        @Test
        @DisplayName("refuse a stale version even when the description differs in nothing")
        void refuseAStaleVersionEvenWhenNothingDiffers() {
            TransactionType stored = new TransactionType(TYPE_CD, "Purchase");
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));
            TransactionTypeService service = new TransactionTypeService(types, categories);

            assertThatThrownBy(() ->
                    service.replace(TYPE_CD, new TransactionTypeUpdateRequest("Purchase", 7L)))
                    .isInstanceOf(RecordConflictException.class)
                    .extracting(failure -> ((RecordConflictException) failure).kind())
                    .isEqualTo(RecordConflictException.Kind.STALE_VERSION);

            verify(types, never()).save(any());
            verify(types, never()).saveAndFlush(any());
        }

        /**
         * A current version whose description differs in nothing is answered without a write.
         *
         * <p>Assumptions: this is the positive control for the case above. Without it, a regression that
         * refused every same-description submission would satisfy that case completely while breaking the
         * idempotent no-op the baseline reaches at physical lines 179 and 180 of {@code COTRTUPC.cbl}.</p>
         */
        @Test
        @DisplayName("answer a current version that changes nothing without writing")
        void answerACurrentVersionThatChangesNothing() {
            TransactionType stored = new TransactionType(TYPE_CD, "Purchase");
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));
            TransactionTypeService service = new TransactionTypeService(types, categories);

            assertThat(service.replace(TYPE_CD,
                    new TransactionTypeUpdateRequest("Purchase", stored.getVersion())).description())
                    .isEqualTo("Purchase");

            verify(types, never()).save(any());
            verify(types, never()).saveAndFlush(any());
        }

        /**
         * A submission differing only in surrounding blanks describes the same state.
         *
         * <p>Purpose: this is the equality half of {@code D-REFERENCE-TRIM-TRAILING-ONLY}. Every
         * description comparison the baseline makes passes its operands through a bare
         * {@code FUNCTION TRIM}, which strips BOTH ends -- {@code COTRTLIC.cbl} L1065 and L1069,
         * {@code COTRTUPC.cbl} L791 and L795 -- so the baseline treats surrounding blanks as
         * insignificant for equality.</p>
         *
         * <p>Refactoring Rationale: the comparison previously reused the STORAGE normalisation, which
         * removes trailing blanks only. A submission differing from the stored value only in LEADING
         * blanks therefore looked like a change and went to the database as a write the baseline would
         * not have made.</p>
         *
         * <p>Assumptions: leading blanks are used on one side and trailing on the other, so a regression
         * that stripped only one end would fail rather than pass on the end it still handled.</p>
         */
        @Test
        @DisplayName("treat a submission differing only in surrounding blanks as no change")
        void treatSurroundingBlanksAsNoChange() {
            TransactionType stored = new TransactionType(TYPE_CD, "Purchase   ");
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));
            TransactionTypeService service = new TransactionTypeService(types, categories);

            assertThat(service.replace(TYPE_CD,
                    new TransactionTypeUpdateRequest("  Purchase", stored.getVersion())).description())
                    .isNotNull();

            verify(types, never()).save(any());
            verify(types, never()).saveAndFlush(any());
        }
    }

    /**
     * Cases over the category replace, which must write through the mapper and nothing else.
     *
     * <p>Refactoring Rationale: these cases exist because the category service performed its own
     * description normalisation inline, duplicating the mapper member written for it, and no test reached
     * this service at all. Two implementations of one storage rule are free to drift and a
     * {@code VARCHAR} column reports nothing when they do, so the routing is asserted here as behaviour
     * -- a stored value -- rather than by observing which method was called.</p>
     */
    @Nested
    @DisplayName("on the category replace")
    class OnTheCategoryReplace {

        /** A description as a client echoing a fixed-width screen field would send it. */
        private static final String PADDED_DESCRIPTION = "Grocery purchase          ";

        /** The same description as the column stores it and the contract publishes it. */
        private static final String TRIMMED_DESCRIPTION = "Grocery purchase";

        /**
         * A replace of a category that does not exist reports the miss and writes nothing.
         *
         * <p>Assumptions: the absence of a save is asserted as well as the message, for the same reason it
         * is on the sibling type replace above -- an upsert would answer this same call successfully, so
         * only the missing write distinguishes the strict behaviour. The sentence is the service's own
         * verbatim constant, including its ellipsis.</p>
         */
        @Test
        @DisplayName("report the miss and insert nothing")
        void reportTheMissAndInsertNothing() {
            when(categories.findByIdIs(new TransactionCategoryId(TYPE_CD, CAT_CD)))
                    .thenReturn(Optional.empty());
            TransactionCategoryService service = new TransactionCategoryService(categories);

            assertThatThrownBy(() -> service.replace(TYPE_CD, CAT_CD,
                    new TransactionCategoryUpdateRequest(TRIMMED_DESCRIPTION, 0L)))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessage(TransactionCategoryService.MESSAGE_CATEGORY_NOT_FOUND);

            verify(categories, never()).save(any());
        }

        /**
         * A replace carrying a stale revision is refused and carries the stored revision back.
         *
         * <p>Assumptions: the stored revision is asserted on the refusal rather than the refusal alone. A
         * conflict that did not report which revision won would leave a caller with no value to retry
         * against, so it would have to re-read and could lose the race again.</p>
         */
        @Test
        @DisplayName("refuse a stale revision and report the stored one")
        void refuseAStaleRevisionAndReportTheStoredOne() {
            TransactionCategory stored = new TransactionCategory(
                    new TransactionCategoryId(TYPE_CD, CAT_CD), "Superseded");
            when(categories.findByIdIs(new TransactionCategoryId(TYPE_CD, CAT_CD)))
                    .thenReturn(Optional.of(stored));
            TransactionCategoryService service = new TransactionCategoryService(categories);

            assertThatThrownBy(() -> service.replace(TYPE_CD, CAT_CD,
                    new TransactionCategoryUpdateRequest(TRIMMED_DESCRIPTION, 7L)))
                    .isInstanceOf(RecordConflictException.class)
                    .extracting(failure -> ((RecordConflictException) failure).currentVersion())
                    .isEqualTo(stored.getVersion());

            verify(categories, never()).save(any());
            assertThat(stored.getDescription())
                    .as("a refused replace must not have written the description first")
                    .isEqualTo("Superseded");
        }

        /**
         * An accepted replace stores the normalised description and leaves the key halves alone.
         *
         * <p>Assumptions: the value handed to the store is asserted, not merely the value returned, and
         * the two are asserted separately. The publication trims for the wire and the storage trims for
         * the column, so a service that stored the padded form and published a trimmed one would satisfy
         * a response-only assertion while leaving the padding in the database -- where a later caller
         * comparing two descriptions would find them unequal.</p>
         *
         * <p>Assumptions: the row saved is asserted to be the SAME instance that was read. The entity is
         * managed, so the write the persistence provider flushes is the mutation applied to that
         * instance; a service that constructed a replacement row would lose the revision the provider
         * maintains and would insert rather than update.</p>
         */
        @Test
        @DisplayName("store the trimmed description on the loaded row and publish it trimmed")
        void storeTheTrimmedDescriptionOnTheLoadedRow() {
            TransactionCategory stored = new TransactionCategory(
                    new TransactionCategoryId(TYPE_CD, CAT_CD), "Superseded");
            when(categories.findByIdIs(new TransactionCategoryId(TYPE_CD, CAT_CD)))
                    .thenReturn(Optional.of(stored));
            // WHY : Refactoring Rationale: the flushing member is stubbed and verified, where this case
            //       stubbed and verified the plain save. The route was changed to flush because the
            //       provider increments the optimistic-lock counter when it issues the UPDATE, so a
            //       non-flushing save inside a transaction left the increment to the commit and the reply
            //       carried the revision the caller had sent. The revision itself cannot be asserted from
            //       a mock -- nothing here increments anything -- so what this case pins is the member
            //       called; the incremented value reaching the reply is asserted against a real engine in
            //       TransactionCategoryRepositoryIT.
            when(categories.saveAndFlush(any(TransactionCategory.class)))
                    .thenAnswer(call -> call.getArgument(0));
            TransactionCategoryService service = new TransactionCategoryService(categories);

            TransactionCategoryResponse published = service.replace(TYPE_CD, CAT_CD,
                    new TransactionCategoryUpdateRequest(PADDED_DESCRIPTION, 0L));

            assertThat(stored.getDescription())
                    .as("the STORED form is the trimmed one, which is what routing through the mapper"
                            + " guarantees")
                    .isEqualTo(TRIMMED_DESCRIPTION);
            assertThat(published.description()).isEqualTo(TRIMMED_DESCRIPTION);
            assertThat(published.typeCd()).isEqualTo(TYPE_CD);
            assertThat(published.catCd()).isEqualTo(CAT_CD);
            assertThat(stored.getTypeCd()).isEqualTo(TYPE_CD);
            assertThat(stored.getCatCd()).isEqualTo(CAT_CD);
            verify(categories).saveAndFlush(stored);
            verify(categories, never()).save(any());
        }

        /**
         * The replace publishes the revision of the instance the FLUSHED write handed back.
         *
         * <p>Purpose: the reply's revision is the token a client sends on its next write, so where it
         * comes from is the whole contract. This case pins the source: the response is composed from
         * whatever the flushing write returns, not from the request and not from the instance as it stood
         * before the write.</p>
         *
         * <p>Assumptions: the property is stated by having the stub return a DIFFERENT instance from the
         * one handed in, carrying a description this case can recognise. A stub that returned its own
         * argument -- which is what the case above does, deliberately, because it is asserting the
         * argument -- cannot tell the two sources apart, and that indistinguishability is exactly how a
         * reply composed from the pre-write instance passed every case in this class.</p>
         *
         * <p>Assumptions: the revision value itself is not asserted here, because no mock increments one.
         * What a mock CAN observe is which instance the reply was built from, and the incremented value
         * arriving in the reply is asserted against a real engine in
         * {@code TransactionCategoryRepositoryIT}.</p>
         */
        @Test
        @DisplayName("publish the instance the flushed write returned, not the one read before it")
        void publishTheInstanceTheFlushedWriteReturned() {
            TransactionCategory stored = new TransactionCategory(
                    new TransactionCategoryId(TYPE_CD, CAT_CD), "Superseded");
            TransactionCategory flushed = new TransactionCategory(
                    new TransactionCategoryId(TYPE_CD, CAT_CD), "As the store now holds it");
            when(categories.findByIdIs(new TransactionCategoryId(TYPE_CD, CAT_CD)))
                    .thenReturn(Optional.of(stored));
            when(categories.saveAndFlush(any(TransactionCategory.class))).thenReturn(flushed);
            TransactionCategoryService service = new TransactionCategoryService(categories);

            TransactionCategoryResponse published = service.replace(TYPE_CD, CAT_CD,
                    new TransactionCategoryUpdateRequest(TRIMMED_DESCRIPTION, 0L));

            assertThat(published.description())
                    .as("the reply is composed from the flushed instance, which is the only one whose"
                            + " revision the provider has already advanced")
                    .isEqualTo("As the store now holds it");
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
            ReferenceBatchUpdateService service = new ReferenceBatchUpdateService(types, mock(PlatformTransactionManager.class));

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
            // WHY : Refactoring Rationale: the insert is verified through the repository's explicit
            //       insert member, where this verified a save. A save cannot insert this entity at all
            //       -- its identifier is caller-assigned and its version is a primitive, so newness
            //       cannot be detected and every save of a new instance reaches a merge, which loads
            //       the row the identifier names and writes an update against it. Verifying the save
            //       therefore asserted a call that could not have inserted the row, and it also made
            //       the duplicate-code refusal unreachable, because a merge raises a lock failure or
            //       silently overwrites rather than violating the primary key. The assertion's own
            //       intent is unchanged: the later action's write is still what is verified.
            verify(types).insertType("03", "Credit");
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
            ReferenceBatchUpdateService service = new ReferenceBatchUpdateService(types, mock(PlatformTransactionManager.class));

            MaintenanceActionBatchResponse reply = service.apply(
                    new MaintenanceActionBatchRequest(List.of(
                            new MaintenanceActionRequest("DELETE", "02", null))));

            assertThat(reply.returnCode())
                    .isEqualTo(ReferenceBatchUpdateService.RETURN_CODE_SOFT_WARN);
        }

        /**
         * Every per-action sentence is a literal COBTUPDT declares, and each action gets its OWN.
         *
         * <p>⚠️ Refactoring Rationale: no case in this module read the per-action MESSAGE at all -- the
         * cases around this one assert the outcome code, the applied flag and the aggregate return code
         * -- so the published path spent its life reporting FOUR sentences that exist nowhere under app/:
         * 'Record applied...', 'Record NOT found...', 'Record already exists...' and
         * 'Description is required...'. A search of the reference tree returns no file for any of them.
         * The verbatim literals were already declared on the service and were wired only to the
         * record-stream path, which no HTTP caller can reach.
         *
         * <p>Assumptions: the expected text is written out in full here rather than taken from the
         * service's own constants. Comparing a constant against itself is what let the defect survive:
         * every assertion that touched these sentences referred to them by NAME and so agreed with
         * whatever they happened to say. The citations are COBTUPDT.cbl lines 153, 179, 209 and the pair
         * 181 and 211, plus the composition of 156 and 157.
         *
         * <p>Assumptions: the three success sentences are asserted to be three DISTINCT strings, because
         * the defect being prevented is a single 'applied' message standing in for all three -- which is
         * exactly what was there. A case asserting only that each is non-blank would pass against it.
         */
        @Test
        @DisplayName("report each action with the baseline's own sentence for that action")
        void reportEachActionWithTheBaselineSentence() {
            when(types.findByTypeCd("41")).thenReturn(Optional.empty());
            when(types.findByTypeCd("42"))
                    .thenReturn(Optional.of(new TransactionType("42", "Purchase")));
            when(types.saveAndFlush(any(TransactionType.class)))
                    .thenAnswer(call -> call.getArgument(0));
            when(types.findByTypeCd("43"))
                    .thenReturn(Optional.of(new TransactionType("43", "Refund")));
            when(types.findByTypeCd("44")).thenReturn(Optional.empty());
            when(types.findByTypeCd("45")).thenReturn(Optional.empty());
            when(types.findByTypeCd("46"))
                    .thenReturn(Optional.of(new TransactionType("46", "Existing")));
            ReferenceBatchUpdateService service = new ReferenceBatchUpdateService(types, mock(PlatformTransactionManager.class));

            MaintenanceActionBatchResponse reply = service.apply(
                    new MaintenanceActionBatchRequest(List.of(
                            new MaintenanceActionRequest("INSERT", "41", "Added"),
                            new MaintenanceActionRequest("UPDATE", "42", "Changed"),
                            new MaintenanceActionRequest("DELETE", "43", null),
                            new MaintenanceActionRequest("UPDATE", "44", "Absent"),
                            new MaintenanceActionRequest("DELETE", "45", null),
                            new MaintenanceActionRequest("INSERT", "46", "Repeated"))));

            assertThat(reply.outcomes()).hasSize(6);
            assertThat(reply.outcomes().get(0).message())
                    .as("10031-INSERT-DB zero arm, COBTUPDT.cbl line 153")
                    .isEqualTo("RECORD INSERTED SUCCESSFULLY");
            assertThat(reply.outcomes().get(1).message())
                    .as("10032-UPDATE-DB zero arm, line 179")
                    .isEqualTo("RECORD UPDATED SUCCESSFULLY");
            assertThat(reply.outcomes().get(2).message())
                    .as("10033-DELETE-DB zero arm, line 209")
                    .isEqualTo("RECORD DELETED SUCCESSFULLY");
            assertThat(reply.outcomes().get(3).message())
                    .as("10032-UPDATE-DB SQLCODE +100 arm, line 181, terminating period included")
                    .isEqualTo("No records found.");
            assertThat(reply.outcomes().get(4).message())
                    .as("10033-DELETE-DB SQLCODE +100 arm, line 211, the identical literal")
                    .isEqualTo("No records found.");
            assertThat(reply.outcomes().get(5).message())
                    .as("no duplicate-key arm exists, so a repeated key takes the negative arm of 154"
                            + " and is answered with the composition of lines 156 and 157")
                    .isEqualTo("Error accessing: TRANSACTION_TYPE table. SQLCODE:");

            // WHY : Assumptions: the three success sentences are compared to EACH OTHER as well as to
            //       their literals, because the condition being prevented is one message standing in for
            //       three. Asserting the literals alone would catch a reversion to a single authored
            //       sentence; asserting distinctness states the property that made three constants
            //       necessary in the first place.
            assertThat(List.of(reply.outcomes().get(0).message(), reply.outcomes().get(1).message(),
                            reply.outcomes().get(2).message()))
                    .doesNotHaveDuplicates();

            // WHY : Assumptions: not one of the four withdrawn sentences may reappear anywhere in the
            //       reply, which is asserted directly rather than inferred from the equalities above. A
            //       fifth condition added later and given an authored sentence would satisfy every
            //       assertion above and would reintroduce exactly the defect this case exists for.
            assertThat(reply.outcomes()).allSatisfy(row -> assertThat(row.message())
                    .doesNotContain("Record applied")
                    .doesNotContain("Record NOT found")
                    .doesNotContain("Record already exists")
                    .doesNotContain("Description is required"));
        }

        /** A run in which every action applied reports the clean condition code. */
        @Test
        @DisplayName("report the clean code when every action applied")
        void reportTheCleanCodeWhenEveryActionApplied() {
            when(types.findByTypeCd("04")).thenReturn(Optional.empty());
            ReferenceBatchUpdateService service = new ReferenceBatchUpdateService(types, mock(PlatformTransactionManager.class));

            MaintenanceActionBatchResponse reply = service.apply(
                    new MaintenanceActionBatchRequest(List.of(
                            new MaintenanceActionRequest("INSERT", "04", "Authorization"))));

            assertThat(reply.returnCode())
                    .isEqualTo(ReferenceBatchUpdateService.RETURN_CODE_CLEAN);
            assertThat(reply.outcomes().get(0).applied()).isTrue();
        }

        /**
         * One maintenance record decodes to its three declared fields at the offsets the layout states.
         *
         * <p>Assumptions: the field values are asserted rather than only the branch taken, because the
         * three offsets are 0, 1 and 3 and a record read one byte out of alignment still decodes to
         * characters. Nothing would raise, so only the values reveal a misaligned layout.</p>
         */
        @Test
        @DisplayName("decode one record to its three fields at offsets zero, one and three")
        void decodeOneRecordToItsThreeFieldsAtOffsetsZeroOneAndThree() {
            ReferenceBatchUpdateService service = new ReferenceBatchUpdateService(types, mock(PlatformTransactionManager.class));

            ReferenceBatchUpdateService.RecordOutcome outcome =
                    service.applyRecord(maintenanceRecord("A", "08", "Fee Assessment"));

            assertThat(outcome.action()).isEqualTo(ReferenceBatchUpdateService.RecordAction.ADD);
            assertThat(outcome.typeCode()).isEqualTo("08");
            assertThat(outcome.succeeded()).isTrue();
            assertThat(outcome.message())
                    .isEqualTo(ReferenceBatchUpdateService.MESSAGE_RECORD_INSERTED);
            // WHY : Assumptions: the description reaches storage trimmed while the code keeps its
            //       declared width. Verifying the argument is what distinguishes the two, since a
            //       record that stored fifty blank-padded bytes would satisfy every assertion above.
            verify(types).insertType("08", "Fee Assessment");
        }

        /**
         * A stream whose length is not a whole multiple of the record length is refused as a whole.
         *
         * <p>Assumptions: this is the one failure the run does not tolerate, and the distinction is the
         * point of the case. A malformed length is something the baseline cannot meet, because its
         * access method guarantees the record length before the program sees a record, so there is no
         * per-record branch to transcribe and nothing to soft reject into.</p>
         */
        @Test
        @DisplayName("refuse a stream that is not a whole number of records")
        void refuseAStreamThatIsNotAWholeNumberOfRecords() {
            ReferenceBatchUpdateService service = new ReferenceBatchUpdateService(types, mock(PlatformTransactionManager.class));
            byte[] trailingPartialRecord = new byte[ReferenceBatchUpdateService.RECORD_LENGTH + 2];

            assertThatThrownBy(
                    () -> service.apply(new ByteArrayInputStream(trailingPartialRecord)))
                    .isInstanceOf(FixedWidthCodec.RecordLengthException.class)
                    .hasMessageContaining("WS-INPUT-REC")
                    .hasMessageContaining("53")
                    .hasMessageContaining("2");
        }

        /**
         * Each of the five dispatch branches is selected by its own action code.
         *
         * <p>Assumptions: the lowercase action code is included deliberately. A COBOL
         * {@code EVALUATE} compares the byte, so a lowercase code falls through every named arm to the
         * catch-all rather than selecting the branch its uppercase form would.</p>
         */
        @Test
        @DisplayName("select each of the five dispatch branches")
        void selectEachOfTheFiveDispatchBranches() {
            when(types.findByTypeCd("02"))
                    .thenReturn(Optional.of(new TransactionType("02", "Payment")));
            when(types.findByTypeCd("03"))
                    .thenReturn(Optional.of(new TransactionType("03", "Credit")));
            ReferenceBatchUpdateService service = new ReferenceBatchUpdateService(types, mock(PlatformTransactionManager.class));

            assertThat(service.applyRecord(maintenanceRecord("A", "08", "New")).action())
                    .isEqualTo(ReferenceBatchUpdateService.RecordAction.ADD);
            assertThat(service.applyRecord(maintenanceRecord("U", "02", "Changed")).action())
                    .isEqualTo(ReferenceBatchUpdateService.RecordAction.UPDATE);
            assertThat(service.applyRecord(maintenanceRecord("D", "03", "")).action())
                    .isEqualTo(ReferenceBatchUpdateService.RecordAction.DELETE);

            ReferenceBatchUpdateService.RecordOutcome commented =
                    service.applyRecord(maintenanceRecord("*", "01", "a commented line"));
            assertThat(commented.action())
                    .isEqualTo(ReferenceBatchUpdateService.RecordAction.COMMENT);
            assertThat(commented.succeeded()).isTrue();
            assertThat(commented.message())
                    .isEqualTo(ReferenceBatchUpdateService.DISPLAY_IGNORING_COMMENTED_LINE);

            ReferenceBatchUpdateService.RecordOutcome invalid =
                    service.applyRecord(maintenanceRecord("a", "01", "Purchase"));
            assertThat(invalid.action())
                    .isEqualTo(ReferenceBatchUpdateService.RecordAction.INVALID);
            assertThat(invalid.rejectReason())
                    .isEqualTo(ReferenceBatchUpdateService.RejectReason.INVALID_ACTION_CODE);
            assertThat(invalid.message())
                    .isEqualTo(ReferenceBatchUpdateService.MESSAGE_TYPE_NOT_VALID);
        }

        /**
         * A refused record does not stop the records after it, and the writes of those records happen.
         *
         * <p>Assumptions: this is the case that proves the soft reject, so it asserts the later writes
         * and not merely the length of the outcome list. A run that built three outcomes while writing
         * nothing after the first refusal would satisfy a count assertion and would still have given
         * up. The aggregate and the identity of the refused record are both asserted, because the
         * baseline's own condition-code register carries the aggregate and nothing else.</p>
         */
        @Test
        @DisplayName("apply later records after an earlier one is refused")
        void applyLaterRecordsAfterAnEarlierOneIsRefused() {
            when(types.findByTypeCd("01")).thenReturn(Optional.empty());
            ReferenceBatchUpdateService service = new ReferenceBatchUpdateService(types, mock(PlatformTransactionManager.class));

            ReferenceBatchUpdateService.BatchUpdateResult result = service.apply(
                    maintenanceStream(
                            maintenanceRecord("U", "01", "No such row"),
                            maintenanceRecord("A", "08", "Fee Assessment"),
                            maintenanceRecord("A", "09", "Chargeback")));

            assertThat(result.processedCount()).isEqualTo(3);
            assertThat(result.anyRejected()).isTrue();
            assertThat(result.returnCode())
                    .isEqualTo(ReferenceBatchUpdateService.RETURN_CODE_SOFT_WARN);
            assertThat(result.outcomes().get(0).succeeded()).isFalse();
            assertThat(result.outcomes().get(0).rejectReason())
                    .isEqualTo(ReferenceBatchUpdateService.RejectReason.NOT_FOUND);
            assertThat(result.outcomes().get(1).succeeded()).isTrue();
            assertThat(result.outcomes().get(2).succeeded()).isTrue();
            verify(types).insertType("08", "Fee Assessment");
            verify(types).insertType("09", "Chargeback");
        }

        /**
         * A run in which every record applied reports the clean code, and an empty stream is such a run.
         *
         * <p>Assumptions: the empty stream is asserted alongside the clean run because zero refusals is
         * the same aggregate as zero records, and a reader has to be able to tell that the empty case
         * reaches the loop rather than raising on it.</p>
         */
        @Test
        @DisplayName("report the clean code for a clean run and for an empty stream")
        void reportTheCleanCodeForACleanRunAndForAnEmptyStream() {
            ReferenceBatchUpdateService service = new ReferenceBatchUpdateService(types, mock(PlatformTransactionManager.class));

            ReferenceBatchUpdateService.BatchUpdateResult applied = service.apply(
                    maintenanceStream(maintenanceRecord("A", "08", "Fee Assessment")));
            assertThat(applied.anyRejected()).isFalse();
            assertThat(applied.returnCode())
                    .isEqualTo(ReferenceBatchUpdateService.RETURN_CODE_CLEAN);

            ReferenceBatchUpdateService.BatchUpdateResult empty =
                    service.apply(new ByteArrayInputStream(new byte[0]));
            assertThat(empty.outcomes()).isEmpty();
            assertThat(empty.processedCount()).isZero();
            assertThat(empty.returnCode())
                    .isEqualTo(ReferenceBatchUpdateService.RETURN_CODE_CLEAN);
        }

        /**
         * An update and a delete against an absent code both report the baseline not-found text.
         *
         * <p>Assumptions: both paths are asserted because the baseline composes the identical literal at
         * two separate sites, and the terminating period is part of it. Asserting one site would leave
         * the other free to drift.</p>
         */
        @Test
        @DisplayName("report the not-found text for an update and for a delete")
        void reportTheNotFoundTextForAnUpdateAndForADelete() {
            when(types.findByTypeCd("55")).thenReturn(Optional.empty());
            ReferenceBatchUpdateService service = new ReferenceBatchUpdateService(types, mock(PlatformTransactionManager.class));

            ReferenceBatchUpdateService.RecordOutcome updated =
                    service.applyRecord(maintenanceRecord("U", "55", "No such row"));
            ReferenceBatchUpdateService.RecordOutcome deleted =
                    service.applyRecord(maintenanceRecord("D", "55", ""));

            assertThat(updated.message()).isEqualTo("No records found.");
            assertThat(deleted.message()).isEqualTo("No records found.");
            assertThat(updated.rejectReason())
                    .isEqualTo(ReferenceBatchUpdateService.RejectReason.NOT_FOUND);
            assertThat(deleted.rejectReason())
                    .isEqualTo(ReferenceBatchUpdateService.RejectReason.NOT_FOUND);
            verify(types, never()).saveAndFlush(any(TransactionType.class));
            verify(types, never()).delete(any(TransactionType.class));
        }

        /**
         * The two states the baseline cannot tell apart are classified as distinct typed reasons.
         *
         * <p>Assumptions: this case is the documented divergence and asserts both halves of it. The
         * baseline's insert has no duplicate-key arm and neither its update nor its delete has a
         * referential-integrity arm, so both refusals collapse into one opaque negative arm there. The
         * verbatim text is asserted alongside the reason, because the text stays the baseline's while
         * only the classification is new.</p>
         */
        @Test
        @DisplayName("classify a duplicate key and a restricted removal as distinct reasons")
        void classifyADuplicateKeyAndARestrictedRemovalAsDistinctReasons() {
            when(types.insertType("01", "Purchase"))
                    .thenThrow(integrityViolation("23505"));
            when(types.findByTypeCd("01"))
                    .thenReturn(Optional.of(new TransactionType("01", "Purchase")));
            Mockito.doThrow(integrityViolation("23503")).when(types).flush();
            ReferenceBatchUpdateService service = new ReferenceBatchUpdateService(types, mock(PlatformTransactionManager.class));

            ReferenceBatchUpdateService.RecordOutcome duplicate =
                    service.applyRecord(maintenanceRecord("A", "01", "Purchase"));
            assertThat(duplicate.rejectReason())
                    .isEqualTo(ReferenceBatchUpdateService.RejectReason.DUPLICATE_KEY);
            assertThat(duplicate.sqlState()).isEqualTo("23505");
            assertThat(duplicate.message())
                    .isEqualTo("Error accessing: TRANSACTION_TYPE table. SQLCODE:");

            ReferenceBatchUpdateService.RecordOutcome restricted =
                    service.applyRecord(maintenanceRecord("D", "01", ""));
            assertThat(restricted.rejectReason())
                    .isEqualTo(ReferenceBatchUpdateService.RejectReason.REFERENTIAL_INTEGRITY);
            assertThat(restricted.sqlState()).isEqualTo("23503");
            assertThat(restricted.succeeded()).isFalse();
        }

        /**
         * A state outside the two classified ones is still a soft refusal.
         *
         * <p>Assumptions: the fallback is asserted so that classifying two states cannot be mistaken for
         * narrowing the negative arm to those two. The baseline tolerates every negative outcome, and so
         * does this.</p>
         */
        @Test
        @DisplayName("fall back to the general reason for an unclassified state")
        void fallBackToTheGeneralReasonForAnUnclassifiedState() {
            when(types.insertType("08", "Fee Assessment"))
                    .thenThrow(integrityViolation("40001"));
            ReferenceBatchUpdateService service = new ReferenceBatchUpdateService(types, mock(PlatformTransactionManager.class));

            ReferenceBatchUpdateService.RecordOutcome outcome =
                    service.applyRecord(maintenanceRecord("A", "08", "Fee Assessment"));

            assertThat(outcome.rejectReason())
                    .isEqualTo(ReferenceBatchUpdateService.RejectReason.SQL_ERROR);
            assertThat(outcome.succeeded()).isFalse();
        }

        /**
         * The dispatch branches are declared in the program's own order and carry its verbatim texts.
         *
         * <p>Assumptions: the order is asserted because the driver's comment block documents a different
         * one, putting delete second, and the two are deliberately left disagreeing. An assertion is
         * what stops a later reader harmonising the transcription to the documentation.</p>
         */
        @Test
        @DisplayName("declare the branches in the dispatch order with their verbatim texts")
        void declareTheBranchesInTheDispatchOrderWithTheirVerbatimTexts() {
            assertThat(ReferenceBatchUpdateService.RecordAction.values()).containsExactly(
                    ReferenceBatchUpdateService.RecordAction.ADD,
                    ReferenceBatchUpdateService.RecordAction.UPDATE,
                    ReferenceBatchUpdateService.RecordAction.DELETE,
                    ReferenceBatchUpdateService.RecordAction.COMMENT,
                    ReferenceBatchUpdateService.RecordAction.INVALID);
            assertThat(ReferenceBatchUpdateService.RecordAction.ADD.displayText())
                    .isEqualTo("ADDING RECORD");
            assertThat(ReferenceBatchUpdateService.RecordAction.UPDATE.displayText())
                    .isEqualTo("UPDATING RECORD");
            assertThat(ReferenceBatchUpdateService.RecordAction.DELETE.displayText())
                    .isEqualTo("DELETING RECORD");
            // WHY : Assumptions: the three trailing spaces are asserted with an exact comparison rather
            //       than a trimmed one, because they are the separator the program places between this
            //       text and the record it appends, and a trimmed comparison would pass without them.
            assertThat(ReferenceBatchUpdateService.DISPLAY_PROCESSING).isEqualTo("PROCESSING   ");
            assertThat(ReferenceBatchUpdateService.RETURN_MESSAGE_WIDTH).isEqualTo(80);
        }

        /**
         * The stored fixture bytes decode and dispatch through the same entry point.
         *
         * <p>Assumptions: the fixture file stores each record followed by a newline, so the terminators
         * are stripped here before the bytes are handed in. That is the staging step the record contract
         * assumes: the program's own recording mode admits no delimiter, so a newline is a property of
         * how the fixture is stored and not of the record.</p>
         *
         * @throws java.io.IOException if the fixture resource cannot be read from the test classpath
         */
        @Test
        @DisplayName("decode and dispatch the stored fixture bytes")
        void decodeAndDispatchTheStoredFixtureBytes() throws java.io.IOException {
            int reclen = ReferenceBatchUpdateService.RECORD_LENGTH;
            byte[] stored;
            try (java.io.InputStream source = getClass().getClassLoader().getResourceAsStream(
                    "fixtures/batch_reference_update/add_record/trtype-update.txt")) {
                assertThat(source).as("the add-record fixture must be on the test classpath")
                        .isNotNull();
                stored = source.readAllBytes();
            }
            assertThat(stored).hasSize(2 * (reclen + 1));
            byte[] contiguous = new byte[2 * reclen];
            System.arraycopy(stored, 0, contiguous, 0, reclen);
            System.arraycopy(stored, reclen + 1, contiguous, reclen, reclen);
            ReferenceBatchUpdateService service = new ReferenceBatchUpdateService(types, mock(PlatformTransactionManager.class));

            ReferenceBatchUpdateService.BatchUpdateResult result =
                    service.apply(new ByteArrayInputStream(contiguous));

            assertThat(result.processedCount()).isEqualTo(2);
            assertThat(result.anyRejected()).isFalse();
            verify(types).insertType("08", "Fee Assessment");
            verify(types).insertType("09", "Chargeback");
        }

        /**
         * Builds one maintenance record at the declared geometry of the input group.
         *
         * <p>Assumptions: the description is padded to its declared fifty bytes rather than written
         * short, because the record is fixed length and a short row would be refused at decode rather
         * than exercising the branch the case is about.</p>
         *
         * @param actionCode the one-character action code to place at offset zero
         * @param typeCode the two-character type code to place at offset one
         * @param description the description to place at offset three, padded out to fifty bytes
         * @return the record as {@value ReferenceBatchUpdateService#RECORD_LENGTH} bytes of US-ASCII
         */
        private byte[] maintenanceRecord(String actionCode, String typeCode, String description) {
            String composed = actionCode + typeCode + String.format("%-50s", description);
            return composed.getBytes(StandardCharsets.US_ASCII);
        }

        /**
         * Lays records end to end with no delimiter, as the record contract states.
         *
         * @param records the records to concatenate, each of the declared record length
         * @return a stream over the concatenated records
         */
        private ByteArrayInputStream maintenanceStream(byte[]... records) {
            byte[] joined = new byte[records.length * ReferenceBatchUpdateService.RECORD_LENGTH];
            for (int index = 0; index < records.length; index++) {
                System.arraycopy(records[index], 0, joined,
                        index * ReferenceBatchUpdateService.RECORD_LENGTH,
                        ReferenceBatchUpdateService.RECORD_LENGTH);
            }
            return new ByteArrayInputStream(joined);
        }

        /**
         * Builds the refusal a database driver reports for a violated constraint.
         *
         * <p>Assumptions: the state is carried on a cause rather than on the exception itself, because
         * that is where the persistence layer puts it and where the classifier walks to find it. A
         * refusal built with the state on the outer exception would not exercise the walk at all.</p>
         *
         * @param sqlState the state the driver reports for the violated constraint
         * @return the exception the write path is expected to catch
         */
        private DataIntegrityViolationException integrityViolation(String sqlState) {
            return new DataIntegrityViolationException(
                    "the constraint refused the statement", new SQLException("refused", sqlState));
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

        /**
         * The route-facing lookup declares its own read-only transaction rather than relying on the
         * delegate's.
         *
         * <p>Purpose: the entry point the controller calls is {@code resolveRate}, and it forwards to
         * {@code findRate}. The framework's transaction advice lives in a proxy AROUND the bean, so a call
         * from one member to another travels down the {@code this} reference and never traverses the
         * proxy -- the annotation on the delegate is not consulted. With the annotation only on the
         * delegate, the route-facing lookup ran with NO transaction: its two reads each took and returned
         * a connection separately, they could observe different committed states, and the read-only hint
         * that lets the driver and the database skip write bookkeeping was never applied.</p>
         *
         * <p>Assumptions: what is asserted is the DECLARATION on the route-facing method, not the runtime
         * behaviour of the proxy. The proxy's self-invocation semantics are the framework's and are not
         * this repository's to re-assert; what can regress here is the annotation, and a case that could
         * only fail by starting a container would not run on the builds where it matters. The annotation
         * on the delegate is asserted as well, because removing it in the course of "moving" the boundary
         * would leave a direct caller of that name uncovered -- a nested call inside an active
         * transaction joins it rather than starting a second, so keeping both is free.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         *
         * @throws NoSuchMethodException if either member is renamed without this case being updated,
         *     which fails the case rather than skipping it
         */
        @Test
        @DisplayName("declare the read-only transaction on the method the route calls")
        void declareTheReadOnlyTransactionOnTheRouteFacingMethod() throws NoSuchMethodException {
            Transactional onEntryPoint = DisclosureGroupService.class
                    .getDeclaredMethod("resolveRate", String.class, String.class, String.class)
                    .getAnnotation(Transactional.class);
            Transactional onDelegate = DisclosureGroupService.class
                    .getDeclaredMethod("findRate", String.class, String.class, String.class)
                    .getAnnotation(Transactional.class);

            assertThat(onEntryPoint)
                    .as("the method the controller calls must carry the boundary itself")
                    .isNotNull();
            assertThat(onEntryPoint.readOnly())
                    .as("the boundary the documentation claims is a READ-ONLY one")
                    .isTrue();
            assertThat(onDelegate)
                    .as("the delegate keeps its own boundary so a direct caller stays covered")
                    .isNotNull();
            assertThat(onDelegate.readOnly()).isTrue();
        }
    }

    /**
     * Cases over the one normalisation both reference records store their description through.
     *
     * <p>Purpose: this is the storage half of {@code D-REFERENCE-TRIM-TRAILING-ONLY}, whose equality half
     * is asserted in {@code OnTheStrictReplace} above. The divergence registered there is a PACKAGE-scope
     * ruling rather than a per-record one, so what has to hold is not only that the type path trims one
     * end -- it is that every record reaching the same member gets the same answer. A rule that held for
     * types and silently differed for categories would be a second, unregistered divergence.</p>
     *
     * <p>Assumptions: the mapper members are exercised directly rather than through a service. The
     * property under test is the stored form a normalisation produces, and a service call would add a
     * repository double whose captured argument would have to be unwrapped before the same assertion
     * could be made -- which would state the same fact about the same member less directly.</p>
     */
    @Nested
    @DisplayName("on the shared trim boundary")
    class OnTheSharedTrimBoundary {

        /** A description carrying blanks at BOTH ends, so one end being missed cannot pass. */
        private static final String SUBMITTED = " Purchase  ";

        /** What the registered ruling says is stored: the trailing blanks gone, the leading one kept. */
        private static final String STORED = " Purchase";

        /**
         * A created type stores its description with trailing blanks removed and a leading one kept.
         *
         * <p>Purpose: this is the create arm the divergence entry is answerable for. The baseline's two
         * screen programs pass this field through a trim that strips both ends, so the leading blank
         * surviving here IS the divergence and it is asserted rather than left to the comment that
         * records it.</p>
         *
         * <p>Assumptions: both ends are asserted in one case, because the two halves are what distinguish
         * this behaviour from the baseline's and from a no-trim implementation. Asserting only that the
         * trailing blanks went would pass against a full trim, and asserting only that the leading blank
         * stayed would pass against no trim at all.</p>
         */
        @Test
        @DisplayName("store a created type description trailing-trimmed with its leading blank intact")
        void storeACreatedTypeDescriptionTrailingTrimmed() {
            TransactionType created = TransactionTypeMapper.toNewEntity(
                    new TransactionTypeCreateRequest(TYPE_CD, SUBMITTED));

            assertThat(created.getDescription())
                    .as("the trailing blanks are removed and the leading blank is content")
                    .isEqualTo(STORED);
        }

        /**
         * A created category stores its description through the SAME normalisation as a type.
         *
         * <p>Purpose: this is the reuse the registered entry depends on. {@code TransactionCategoryMapper}
         * calls the type mapper's member rather than declaring its own, and this case is what would fail
         * if a later change gave the category record a private copy that drifted.</p>
         *
         * <p>Assumptions: the expectation is the same constant the type case above uses, deliberately, so
         * that a divergence between the two records cannot be introduced by editing one expectation.</p>
         */
        @Test
        @DisplayName("store a created category description through the same normalisation")
        void storeACreatedCategoryDescriptionThroughTheSameNormalisation() {
            TransactionCategory created = TransactionCategoryMapper.toNewEntity(
                    new TransactionCategoryCreateRequest(TYPE_CD, CAT_CD, SUBMITTED));

            assertThat(created.getDescription())
                    .as("a category is normalised by the same member a type is")
                    .isEqualTo(STORED);
        }

        /**
         * A replaced category stores its description through that same normalisation too.
         *
         * <p>Assumptions: the update arm is asserted separately from the create arm because they are two
         * call sites, and the whole point of the shared member is that they cannot disagree. A row created
         * with one stored form and replaced into another would compare unequal to itself in every later
         * filter, and no constraint on a variable-width column would report it.</p>
         */
        @Test
        @DisplayName("store a replaced category description through the same normalisation")
        void storeAReplacedCategoryDescriptionThroughTheSameNormalisation() {
            TransactionCategory stored = new TransactionCategory(
                    new TransactionCategory.TransactionCategoryId(TYPE_CD, CAT_CD), "Purchase");

            TransactionCategoryMapper.applyUpdate(
                    new TransactionCategoryUpdateRequest(SUBMITTED, stored.getVersion()), stored);

            assertThat(stored.getDescription())
                    .as("a replace is normalised by the same member a create is")
                    .isEqualTo(STORED);
        }
    }
}
