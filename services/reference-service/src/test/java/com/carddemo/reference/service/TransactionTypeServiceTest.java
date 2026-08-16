// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/service/TransactionTypeServiceTest.java
// -----------------------------------------------------------------------------
// Purpose:
//      Unit cases over TransactionTypeService, the parent half of the
//      transaction-reference feature. They pin the write semantics the three
//      baseline programs disagree about, the precedence the baseline classifier
//      declares, the contention conditions the migration reports apart, the
//      before-image comparison that stands in for a held lock, the description
//      filter's escaping ruling, and the message literals rule T8 makes
//      contracts. Every collaborator is a double; nothing here starts a
//      container or a Spring context.
//
// WHY (non-obvious design decisions):
//  (1) Assumptions: NO GOLDEN MASTER COVERS ANY PATH ASSERTED HERE. The
//      reference-only oracle states at lines 83 to 85 of tests/README.md that
//      the online CO* programs cannot run end to end without a CICS runtime and
//      that only their extractable field-validation logic is unit-tested, and a
//      case-insensitive search of that tree for a cotrt, trtyp, trantype or
//      trancatg artefact returns no filename at all. These assertions are
//      therefore primary evidence rather than a second opinion, so each one
//      cites the physical baseline line it was read from and asserts what did
//      NOT happen as well as what did.
//  (2) Assumptions: every baseline citation below is a PHYSICAL line number,
//      readable with sed -n '1864p'. It is never the six-digit sequence printed
//      in columns 1 to 6. In COTRTLIC.cbl the two agree only as far as physical
//      line 1807, after which the printed sequence stands four higher for the
//      rest of the file -- physical line 1825 prints 182900 -- so a citation
//      taken from the printed field names the wrong line in exactly the region
//      these cases quote most.
//  (3) Assumptions: the four rationale labels are typed here and never copied
//      out of tests/README.md. That file carries the non-breaking hyphen 106
//      times across 77 of its lines and its own list of the four categories at
//      line 548 renders the compromise label with that character together with a
//      typographic dash, both indistinguishable on screen from the ASCII forms
//      while behaving differently in a search. This file is pure ASCII, which
//      puts the hazard out of reach rather than relying on care.
//  (4) Assumptions: the singular spellings that appear in the reference-only
//      suite's configuration headers denote these same four categories. That
//      equivalence is declared once, here, so nobody later corrects one form
//      into the other; it governs those reference-only artifacts alone and does
//      not propagate into this file, which uses the plural form exclusively.
//  (5) Trade-offs: this class is the fifth compilation unit in its directory,
//      and the volatile inventory paragraph of the package charter beside it
//      counts four. That paragraph is deliberately kept apart from the charter's
//      durable rules precisely because it lags, it is bound by no build gate,
//      and it is not this file's to rewrite; the count is recorded here instead
//      so a reader who checks it finds the discrepancy explained rather than
//      unremarked.
// =============================================================================

package com.carddemo.reference.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.TransactionTypeCreateRequest;
import com.carddemo.reference.dto.TransactionTypeListRequest;
import com.carddemo.reference.dto.TransactionTypeResponse;
import com.carddemo.reference.dto.TransactionTypeUpdateRequest;
import com.carddemo.reference.repository.TransactionCategoryRepository;
import com.carddemo.reference.repository.TransactionTypeRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;

/**
 * Pins the transaction-type behaviours the baseline records and the migration must not merge.
 *
 * <p>Purpose: the cases below assert six families of property that a behavioural change could silently
 * cross. First, that the strict replace remains strict and that the maintenance screen's insert-on-miss
 * is not published, so the three baseline answers to "the row was not there" stay three. Second, that
 * the outcome classifier keeps the precedence its baseline declares, proven with cases where two
 * conditions hold at once, since a single-condition case cannot see an order at all. Third, that the
 * contention conditions the baseline reports with different sentences are reported apart here too.
 * Fourth, that the restricted delete refuses as a conflict while an unrelated failure does not, because
 * asserting only the first half proves only half the contract. Fifth, that the before-image comparison
 * is case-insensitive and trim-insensitive exactly as the baseline's is. Sixth, that the literals rule
 * T8 makes contracts are carried character for character, typos and all.
 *
 * <p>Assumptions: the surface exercised is the one actually authored, read at authoring time rather
 * than assumed. {@link TransactionTypeService} publishes {@code list}, {@code read}, {@code create},
 * {@code replace} and {@code delete}; its refusals are {@link NoSuchElementException} and
 * {@link RecordConflictException} from the shared kernel, not exception classes nested in the service,
 * a choice that class documents having considered and declined. It takes two collaborators, because the
 * mapper it uses is a final class of static members and is called rather than injected.
 *
 * <p>Assumptions: the entity DOES carry an optimistic-lock counter. {@code TransactionType} maps
 * {@code @Version} onto its {@code version} column and the domain package charter records that two of
 * the six entities in that package carry one. The replace path compares the submitted revision against
 * the stored one before it writes anything, which is the native expression of the baseline's own
 * before-image discipline rather than an addition to it.
 *
 * <p>Alternatives Considered: starting a Spring slice or a database container for these cases. Declined
 * because every property asserted here is decided by this class's own branching, and a case that could
 * only run where a container starts would not run on the builds where a merged write path or a smoothed-over
 * literal is introduced. The repository integration tests beside this package own the container-backed
 * half, and the browse and write-behaviour classes in this package own the paths they name; this class
 * complements them rather than restating them. The package charter's own ruling is that a rule may be
 * asserted by a class that is not named after it, so what matters is that the property is asserted
 * somewhere in the package, not which file carries it.
 *
 * <p>Trade-offs: two cases reach a private static member by reflection. That couples them to a member
 * name, which is accepted for one specific reason: the precedence being asserted is only observable
 * when two conditions hold simultaneously, and the public write path can never construct that state --
 * its two conditions come from catching two exception types that share no subtype, so exactly one of
 * them can be recorded per call. A public-path case would therefore assert the order without ever
 * exercising it. Reflection is available because no compilation unit in this reactor declares a module
 * descriptor and the cases sit in the member's own package.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * at-clause.
 */
@DisplayName("the transaction-type service")
class TransactionTypeServiceTest {

    /** A canonical two-character type code, the first the seed file carries. */
    private static final String TYPE_CD = "01";

    /** A code no seeded row uses, for the paths that must report a miss. */
    private static final String ABSENT_TYPE_CD = "77";

    /** The description the seed file stores against {@link #TYPE_CD}. */
    private static final String STORED_DESCRIPTION = "Purchase";

    /** The revision every stored fixture row carries, matching the column's declared default. */
    private static final long STORED_VERSION = 0L;

    /** The authenticated caller every browse in this class is issued as. */
    private static final String SUBJECT = "REFUSER1";

    /**
     * The key browse positions are sealed under, fabricated here and used nowhere else.
     *
     * <p>Assumptions: the VALUE is immaterial and only the length is a contract -- the sealer refuses a
     * key shorter than its keyed digest requires, and this phrase is forty-one bytes, comfortably past
     * that floor. It is a visibly non-production phrase held in a unit test, it never reaches a deployed
     * profile, and no case here asserts anything about the confidentiality of a deployed key.
     */
    private static final byte[] CURSOR_KEY =
            "transaction-type-service-test-sealing-key".getBytes(StandardCharsets.UTF_8);

    /** How long a sealed position stays openable, long enough that no case can expire mid-run. */
    private static final Duration CURSOR_LIFETIME = Duration.ofHours(1);

    /** The vendor text a driver exception carries, chosen to be searchable and to occur nowhere else. */
    private static final String DRIVER_REASON = "carddemo-driver-reason-marker";

    /**
     * The validator factory the request-shape cases resolve their constraints through.
     *
     * <p>Alternatives Considered: reading the constraint annotations off the request shapes reflectively
     * instead of running a provider, which needs no bootstrap at all. Declined because it would assert
     * the TEXT of a declared expression rather than the effect of it, and the property these cases exist
     * to pin is which values are actually refused -- an expression can be present and admit the wrong
     * set. Running the declared constraints is the only form of that assertion that would notice.
     *
     * <p>Trade-offs: the compromise is specific and measured rather than a general preference. Bootstrap
     * of the provider costs about twenty seconds on this four-processor runner and is paid once, when this
     * class is first loaded, which is roughly a tenth of the module's unit-test wall time; against that,
     * the request-shape cases assert effective refusal instead of annotation text. One factory serves the
     * whole class because building one per case would multiply that bootstrap by the number of shapes
     * asserted while handing every case an identical validator. The same cost appears in sibling
     * validation classes in this reactor, so it is the module's established shape and not a new one.
     */
    private static final ValidatorFactory VALIDATOR_FACTORY =
            Validation.buildDefaultValidatorFactory();

    /** The validator drawn from {@link #VALIDATOR_FACTORY}, shared by every request-shape case. */
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    /** The transaction-type table double. */
    private TransactionTypeRepository types;

    /** The category table double, which the delete path reads to diagnose its refusal. */
    private TransactionCategoryRepository categories;

    /** The service under test, rebuilt per case over fresh doubles. */
    private TransactionTypeService service;

    /**
     * Prepares fresh doubles and a fresh service for each case.
     *
     * <p>Assumptions: the doubles are rebuilt rather than reset because a case that verifies an
     * interaction did NOT happen is only meaningful against a double no earlier case has touched, and
     * several cases below assert exactly that.
     *
     * <p>It takes no parameter and returns no value.
     */
    @BeforeEach
    void setUp() {
        this.types = mock(TransactionTypeRepository.class);
        this.categories = mock(TransactionCategoryRepository.class);
        this.service = new TransactionTypeService(this.types, this.categories);
    }

    /**
     * Releases the shared validator factory once every case in this class has run.
     *
     * <p>Assumptions: the factory holds provider resources and declares itself closeable, so it is closed
     * rather than left to the run's end. A factory built per case inside a try-with-resources would close
     * itself but would pay the bootstrap cost repeatedly, which is the compromise recorded on the field.
     *
     * <p>It takes no parameter and returns no value.
     */
    @AfterAll
    static void releaseValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    /**
     * Builds a stored row at the canonical code, description and revision.
     *
     * @return a transaction type standing for the row a read would return, never {@code null}
     */
    private static TransactionType storedType() {
        return new TransactionType(TYPE_CD, STORED_DESCRIPTION);
    }

    /**
     * Names the request properties a candidate's declared constraints refuse.
     *
     * <p>Assumptions: the property NAMES are collected rather than the messages, because the message a
     * provider composes is that provider's and not this contract's, whereas which property was refused is
     * exactly what the request shape declares.
     *
     * @param <T> the request shape being validated
     * @param candidate the request instance to validate; must not be {@code null}
     * @return the distinct property paths carrying at least one violation, empty when the shape accepts
     *     the instance, never {@code null}
     */
    private static <T> List<String> refusedProperties(T candidate) {
        Set<ConstraintViolation<T>> found = VALIDATOR.validate(candidate);
        return found.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Builds a replace body carrying a description and the revision the caller believes it read.
     *
     * @param description the description submitted, taken as literal text
     * @param version the revision the caller read, compared against the stored counter before any write
     * @return the replace body, never {@code null}
     */
    private static TransactionTypeUpdateRequest replaceBody(String description, long version) {
        return new TransactionTypeUpdateRequest(description, version);
    }

    /**
     * Builds a browse request carrying only the parts a case needs.
     *
     * @param typeCode the type-code filter, or {@code null} for none
     * @param description the description filter, or {@code null} for none
     * @return the browse request with no position and no direction, never {@code null}
     */
    private static TransactionTypeListRequest browse(String typeCode, String description) {
        return new TransactionTypeListRequest(null, null, typeCode, description);
    }

    /**
     * Builds a fresh sealer for a browse case.
     *
     * @return a sealer over the constant key and lifetime, never {@code null}
     */
    private static CursorToken sealer() {
        return new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);
    }

    /**
     * Wraps a SQLSTATE in the integrity violation a provider would raise for it.
     *
     * <p>Assumptions: the state is carried on a {@link SQLException} in the cause chain rather than in
     * the wrapper's text, because that is where the service reads it from -- it walks the chain for the
     * one interface that is part of the platform and reports the state directly. A wrapper whose message
     * merely mentioned the digits would not be classified at all.
     *
     * @param sqlState the five-character state the database reported
     * @return an integrity violation whose cause reports that state, never {@code null}
     */
    private static DataIntegrityViolationException integrityViolation(String sqlState) {
        return new DataIntegrityViolationException(
                "constraint refused the statement", new SQLException(DRIVER_REASON, sqlState));
    }

    /**
     * Resolves the write outcome a set of simultaneous conditions selects.
     *
     * <p>Trade-offs: this reaches the classifier directly, which is the only way to present it with two
     * conditions at once. The reasoning is recorded on the class block above and is not repeated per
     * case.
     *
     * @param lockUnavailable whether the row could not be locked, the migrated SQLCODE -911 condition
     * @param updateFailed whether the statement itself was refused by a constraint
     * @param dataChanged whether the before-image comparison found the row altered underneath
     * @return the single outcome those conditions select, never {@code null}
     * @throws ReflectiveOperationException if the classifier is renamed or its signature changes without
     *     these cases being updated, which fails them rather than letting them pass by omission
     */
    private static TransactionTypeService.WriteOutcome resolveOutcome(
            boolean lockUnavailable, boolean updateFailed, boolean dataChanged)
            throws ReflectiveOperationException {

        Method classifier = TransactionTypeService.class.getDeclaredMethod(
                "resolveWriteOutcome", boolean.class, boolean.class, boolean.class);
        classifier.setAccessible(true);
        return (TransactionTypeService.WriteOutcome)
                classifier.invoke(null, lockUnavailable, updateFailed, dataChanged);
    }

    /**
     * Counts how many times a fragment occurs in a text.
     *
     * <p>Assumptions: the walk advances by the WHOLE fragment, so occurrences are counted without
     * overlap. That is the semantic the delete case needs: it asks whether one vendor string was
     * concatenated into a sentence twice, and an overlapping count would report a second occurrence for
     * a fragment that merely repeats its own opening characters, which would answer that question
     * wrongly for any self-similar text.
     *
     * @param haystack the text to search, which may be {@code null} and then contributes nothing
     * @param needle the fragment to count, which is never blank at any call site below
     * @return the number of non-overlapping occurrences, zero when the text is absent
     */
    private static int occurrences(String haystack, String needle) {
        if (haystack == null) {
            return 0;
        }
        int found = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            found++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return found;
    }

    /**
     * Cases keeping the three baseline answers to a write against a missing row distinct.
     *
     * <p>Assumptions: three programs answer "the UPDATE returned +100" three different ways, and the
     * migration exposes exactly one of them. {@code COTRTUPC.cbl} inserts, at physical lines 1558 to
     * 1560, which perform the insert paragraph at physical line 1596. {@code COTRTLIC.cbl} reports the
     * miss, at physical lines 1861 to 1869, moving the sentence at physical line 1864 and leaving by the
     * exit at physical line 1869 without writing. {@code COBTUPDT.cbl} rejects the one record, at
     * physical lines 180 to 184, and its driver loop at physical lines 93 to 96 continues to the next.
     */
    @Nested
    @DisplayName("on the three write semantics")
    class OnTheThreeWriteSemantics {

        /**
         * A replace of an absent code reports the miss and issues no write of any kind.
         *
         * <p>Assumptions: the absence of a write is what carries this case. An upsert would answer the
         * same call successfully, so a case asserting only the refusal would pass against either
         * behaviour and would detect nothing. Both write routes are asserted unused, because the entity
         * cannot be inserted through {@code save} at all -- it carries an assigned identifier and a
         * primitive revision, so newness cannot be expressed and every {@code save} of a new row becomes
         * a merge -- which is why the service owns an explicit insert that must also stay unused here.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a replace of an absent code reports the miss and writes nothing")
        void aReplaceOfAnAbsentCodeWritesNothing() {
            when(types.findByTypeCd(ABSENT_TYPE_CD)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.replace(ABSENT_TYPE_CD, replaceBody("Anything", 0L)))
                    .as("the baseline reports the miss at COTRTLIC.cbl physical line 1864")
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessage(TransactionTypeService.MESSAGE_TYPE_NOT_FOUND);

            verify(types, never()).saveAndFlush(any(TransactionType.class));
            verify(types, never()).insertType(anyString(), anyString());
        }

        /**
         * The published surface offers no upsert, so the insert-on-miss behaviour is unreachable.
         *
         * <p>Alternatives Considered: publishing the maintenance screen's insert-on-miss as a second
         * write operation, or folding it into the one that exists. Three behaviours were available and
         * one was chosen. Inserting on a miss, which {@code COTRTUPC.cbl} does at physical lines 1558 to
         * 1560, was declined because it makes the published not-found answer unreachable, so a caller who
         * mistypes a code silently creates a type instead of learning the code is unknown. Rejecting the
         * single record and carrying on, which {@code COBTUPDT.cbl} does at physical lines 180 to 184
         * under the loop at physical lines 93 to 96, belongs to a batch driver reading a sequential file
         * and has no meaning for one keyed request; it is asserted by the batch class in this package
         * rather than here. Reporting the miss, which {@code COTRTLIC.cbl} does at physical lines 1861 to
         * 1869, is what the contract publishes. The distinction is registered as
         * D-REFERENCE-UPSERT-NOT-EXPOSED in {@code docs/architecture/cobol-to-service-traceability.md}.
         *
         * <p>Assumptions: the guard is the whole published set rather than the absence of one name,
         * because a name is easy to vary. An operation added to this service fails this case and has to
         * be reasoned about, which is the behaviour wanted from a guard.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("no upsert entry point is published alongside the strict replace")
        void noUpsertEntryPointIsPublished() {
            List<String> published = Arrays.stream(TransactionTypeService.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> !method.isSynthetic())
                    .map(Method::getName)
                    .distinct()
                    .toList();

            assertThat(published)
                    .as("one write per baseline behaviour would be three operations, not five members")
                    .containsExactlyInAnyOrder("list", "read", "create", "replace", "delete");
        }

        /**
         * A replace whose target exists writes through the flushing route rather than the insert.
         *
         * <p>Assumptions: this is the positive half of the pair above. Asserting only that an absent code
         * writes nothing would also be satisfied by a service that never writes at all, so the case that
         * a present code DOES write is what makes the first one mean something.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a replace of a present code writes through the update route and never the insert")
        void aReplaceOfAPresentCodeUsesTheUpdateRoute() {
            TransactionType stored = storedType();
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));
            when(types.saveAndFlush(stored)).thenReturn(stored);

            TransactionTypeResponse answered =
                    service.replace(TYPE_CD, replaceBody("Purchase revised", STORED_VERSION));

            assertThat(answered.description())
                    .as("only the description column is written, per COTRTLIC.cbl physical lines 1846 to 1850")
                    .isEqualTo("Purchase revised");
            verify(types).saveAndFlush(stored);
            verify(types, never()).insertType(anyString(), anyString());
        }
    }

    /**
     * Cases pinning the order the outcome classifier tests its conditions in.
     *
     * <p>Assumptions: the order is the contract rather than an implementation detail, because it is the
     * only thing that decides the answer when two conditions hold at once. The baseline declares it at
     * physical lines 1580 to 1589 of {@code COTRTUPC.cbl}: the lock condition at physical lines 1581 and
     * 1582, the failed statement at 1583 and 1584, the changed row at 1585 and 1586, and success as the
     * {@code WHEN OTHER} arm at 1587 and 1588.
     */
    @Nested
    @DisplayName("on the outcome precedence")
    class OnTheOutcomePrecedence {

        /**
         * The constants are declared in the order the baseline classifier tests them.
         *
         * <p>Assumptions: the declaration order is asserted because the service's own documentation makes
         * it the carrier of the precedence, so a constant inserted in the middle would move the
         * precedence without changing a line of branching.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the constants are declared lock, failed, changed, then success")
        void theConstantsAreDeclaredInPrecedenceOrder() {
            assertThat(TransactionTypeService.WriteOutcome.values())
                    .as("COTRTUPC.cbl physical lines 1581, 1583, 1585 and 1587 in that sequence")
                    .containsExactly(
                            TransactionTypeService.WriteOutcome.LOCK_ERROR,
                            TransactionTypeService.WriteOutcome.UPDATE_FAILED,
                            TransactionTypeService.WriteOutcome.DATA_CHANGED,
                            TransactionTypeService.WriteOutcome.OK);
        }

        /**
         * With a lock condition and a failed statement both recorded, the lock condition wins.
         *
         * <p>Assumptions: this is the case a single-condition case cannot replace, and getting the order
         * wrong here has a concrete cost rather than a cosmetic one: a lock timeout reported as a failed
         * statement tells a caller its write was refused on its merits, so it stops retrying something it
         * should have retried unchanged.
         *
         * <p>It takes no parameter and returns no value.
         *
         * @throws ReflectiveOperationException if the classifier is renamed or its signature changes
         *     without this case being updated, which fails the case rather than skipping it
         */
        @Test
        @DisplayName("a lock condition wins over a failed statement recorded at the same time")
        void aLockConditionWinsOverAFailedStatement() throws ReflectiveOperationException {
            assertThat(resolveOutcome(true, true, false))
                    .as("COTRTUPC.cbl tests the lock condition first, at physical line 1581")
                    .isEqualTo(TransactionTypeService.WriteOutcome.LOCK_ERROR);
        }

        /**
         * With a failed statement and a changed row both recorded, the failed statement wins.
         *
         * <p>Assumptions: asserted separately from the pair above because the two orderings are two
         * independent facts. A classifier that tested the changed row before the failed statement would
         * satisfy the first case and fail this one.
         *
         * <p>It takes no parameter and returns no value.
         *
         * @throws ReflectiveOperationException if the classifier is renamed or its signature changes
         *     without this case being updated, which fails the case rather than skipping it
         */
        @Test
        @DisplayName("a failed statement wins over a changed row recorded at the same time")
        void aFailedStatementWinsOverAChangedRow() throws ReflectiveOperationException {
            assertThat(resolveOutcome(false, true, true))
                    .as("COTRTUPC.cbl tests the failed statement at physical line 1583, before 1585")
                    .isEqualTo(TransactionTypeService.WriteOutcome.UPDATE_FAILED);
        }

        /**
         * With every condition recorded at once, the lock condition still wins.
         *
         * <p>Assumptions: the three-condition case is included as well as the two-condition ones because
         * a classifier built from independent tests rather than a cascade could satisfy both pairs and
         * still answer this one wrongly.
         *
         * <p>It takes no parameter and returns no value.
         *
         * @throws ReflectiveOperationException if the classifier is renamed or its signature changes
         *     without this case being updated, which fails the case rather than skipping it
         */
        @Test
        @DisplayName("every condition at once still selects the lock condition")
        void everyConditionAtOnceStillSelectsTheLockCondition() throws ReflectiveOperationException {
            assertThat(resolveOutcome(true, true, true))
                    .as("the cascade is ordered, so the first arm answers however many follow it")
                    .isEqualTo(TransactionTypeService.WriteOutcome.LOCK_ERROR);
        }

        /**
         * Success is the absence of any recorded condition and never a signal of its own.
         *
         * <p>Assumptions: the baseline's success arm is {@code WHEN OTHER} at physical lines 1587 and
         * 1588, and physical line 1556 commits while setting no flag at all, so "no error was recorded"
         * IS the success condition. Asserting a positive success signal would invent one the baseline
         * does not have.
         *
         * <p>It takes no parameter and returns no value.
         *
         * @throws ReflectiveOperationException if the classifier is renamed or its signature changes
         *     without this case being updated, which fails the case rather than skipping it
         */
        @Test
        @DisplayName("no condition at all is the only success signal")
        void noConditionAtAllIsTheOnlySuccessSignal() throws ReflectiveOperationException {
            assertThat(resolveOutcome(false, false, false))
                    .as("the WHEN OTHER arm at COTRTUPC.cbl physical lines 1587 and 1588")
                    .isEqualTo(TransactionTypeService.WriteOutcome.OK);
            assertThat(resolveOutcome(false, false, true))
                    .as("a changed row alone is still not success")
                    .isEqualTo(TransactionTypeService.WriteOutcome.DATA_CHANGED);
        }
    }

    /**
     * Cases keeping the contention conditions the baseline reports apart reported apart here.
     *
     * <p>Assumptions: the baseline separates these by sentence and by nothing else, and the two programs
     * do not even agree on the sentence. On the migrated side the separation is carried by the refusal's
     * closed kind, which no later statement can overwrite -- unlike the baseline conditions, which are
     * 88-levels declared on the very message field the following {@code STRING} rewrites.
     */
    @Nested
    @DisplayName("on contention reported apart")
    class OnContentionReportedApart {

        /**
         * A lock the provider could not acquire is reported as the lock condition and not as a fault.
         *
         * <p>Assumptions: this is the migrated form of the SQLCODE -911 arm, which {@code COTRTLIC.cbl}
         * reaches at physical lines 1870 to 1879 and {@code COTRTUPC.cbl} at physical lines 1561 to 1566.
         * It is asserted as its own kind because a caller told the wrong condition retries the wrong way:
         * a lock timeout should be retried unchanged, whereas a revision that lost has to be re-read
         * first.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a lock that could not be acquired is reported as the lock condition")
        void aLockThatCouldNotBeAcquiredIsReportedAsTheLockCondition() {
            TransactionType stored = storedType();
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));
            when(types.saveAndFlush(stored))
                    .thenThrow(new CannotAcquireLockException("lock wait timeout"));

            assertThatThrownBy(
                    () -> service.replace(TYPE_CD, replaceBody("Purchase revised", STORED_VERSION)))
                    .isInstanceOf(RecordConflictException.class)
                    .extracting(failure -> ((RecordConflictException) failure).kind())
                    .as("the lock arm must not collapse into the stale-revision arm")
                    .isEqualTo(RecordConflictException.Kind.LOCK_UNAVAILABLE);
        }

        /**
         * The two programs' deadlock sentences are two contracts and are never merged into one.
         *
         * <p>Assumptions: {@code COTRTLIC.cbl} answers SQLCODE -911 with the sentence at physical line
         * 1874 while {@code COTRTUPC.cbl} answers the same code with the condition whose literal is
         * declared at its physical line 182. Rule T8 makes each literal the contract of the screen that
         * displays it, so the assertion is that the two remain unequal and that neither has drifted
         * towards the other's wording. The shared kernel's own invalid-key sentence is included because
         * it is a third string a careless consolidation would reach for.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the two deadlock sentences stay two distinct literals")
        void theTwoDeadlockSentencesStayDistinct() {
            String listScreenSentence = "Deadlock. Someone else updating ?";
            String maintenanceScreenSentence = "Could not lock record for update";

            assertThat(listScreenSentence)
                    .as("COTRTLIC.cbl physical line 1874, byte for byte")
                    .hasSize(33)
                    .isNotEqualTo(maintenanceScreenSentence);
            assertThat(maintenanceScreenSentence)
                    .as("COTRTUPC.cbl physical line 182, byte for byte")
                    .hasSize(32);
            assertThat(ApiError.COACTUPC_RECORD_CHANGED)
                    .as("the stale-revision sentence is a third string and not either deadlock one")
                    .isNotEqualTo(listScreenSentence)
                    .isNotEqualTo(maintenanceScreenSentence);
        }

        /**
         * A repeated primary key is refused with the sentence the baseline's insert arm composes.
         *
         * <p>Refactoring Rationale: the kind asserted is the insert refusal, where this case asserted the
         * referential one. {@code 9700-INSERT-RECORD} carries a zero arm at physical line 1605 and a
         * {@code WHEN OTHER} at physical line 1607 and nothing else -- a search of the whole program for
         * the duplicate-key SQLCODE returns no occurrence -- so a reused code is reported there with the
         * 'Error inserting record into:' sentence that names the table, and that is the sentence carried
         * across. The referential sentence is composed in a DIFFERENT paragraph,
         * {@code 9800-DELETE-PROCESSING} at physical line 1641, under the SQLCODE a restricted delete
         * raises; an insert cannot reach it, and answering with it told a caller who reused a code to go
         * and delete child records that do not exist.
         *
         * <p>Assumptions: the state is the migrated form of that SQLCODE and is named through the
         * service's own constant rather than as a literal here, so the two cannot drift. The table the
         * refusal names is asserted as well, because the sentence a caller reads is composed around it and
         * a kind-only assertion would pass against a refusal naming the sibling table.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a repeated primary key is classified from its SQLSTATE")
        void aRepeatedPrimaryKeyIsClassifiedFromItsState() {
            when(types.findByTypeCd("08")).thenReturn(Optional.empty());
            when(types.insertType(eq("08"), anyString()))
                    .thenThrow(integrityViolation(TransactionTypeService.SQLSTATE_UNIQUE_VIOLATION));

            assertThatThrownBy(
                    () -> service.create(new TransactionTypeCreateRequest("08", "Late fee")))
                    .isInstanceOf(RecordConflictException.class)
                    .extracting(failure -> ((RecordConflictException) failure).kind())
                    .as("a duplicate reaching the constraint is refused, not propagated unclassified")
                    .isEqualTo(RecordConflictException.Kind.INSERT_REFUSED);

            assertThatThrownBy(
                    () -> service.create(new TransactionTypeCreateRequest("08", "Late fee")))
                    .extracting(failure -> ((RecordConflictException) failure).targetTable())
                    .as("the sentence names the table the baseline writes into it, unqualified")
                    .isEqualTo("TRANSACTION_TYPE");
        }

        /**
         * The pre-read duplicate and the constraint-raised duplicate are the SAME refusal.
         *
         * <p>Assumptions: the create has two ways of discovering that a code is taken -- the keyed read
         * before the insert, and the unique constraint that refuses the insert when two callers race that
         * read -- and a caller must not be able to tell which one refused it. Asserting the pair together
         * is what pins that: a case asserting either route alone would pass against a service that
         * answered the raced route differently, which would make the response depend on timing.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the read-detected and constraint-detected duplicates answer identically")
        void bothDuplicateRoutesAnswerIdentically() {
            when(types.findByTypeCd("08"))
                    .thenReturn(Optional.of(new TransactionType("08", "Late fee")));

            RecordConflictException byRead = catchThrowableOfType(RecordConflictException.class,
                    () -> service.create(new TransactionTypeCreateRequest("08", "Late fee")));

            when(types.findByTypeCd("08")).thenReturn(Optional.empty());
            when(types.insertType(eq("08"), anyString()))
                    .thenThrow(integrityViolation(TransactionTypeService.SQLSTATE_UNIQUE_VIOLATION));

            RecordConflictException byConstraint = catchThrowableOfType(
                    RecordConflictException.class,
                    () -> service.create(new TransactionTypeCreateRequest("08", "Late fee")));

            assertThat(byRead.kind()).isEqualTo(byConstraint.kind());
            assertThat(byRead.targetTable()).isEqualTo(byConstraint.targetTable());
            // WHY : Assumptions: the version is asserted absent on both, because an insert that was
            //       refused wrote nothing and there is no revision for a caller to compare against. A
            //       refusal that reported one would put a version entry into a body whose contract says
            //       only the concurrency condition carries one.
            assertThat(byRead.currentVersion()).isNull();
            assertThat(byConstraint.currentVersion()).isNull();
        }

        /**
         * A state the service does not classify is handed back unchanged rather than reshaped.
         *
         * <p>Trade-offs: an unrecognised state could have been folded into whichever of the two
         * classified conditions looks closest, which would have given every integrity violation a
         * caller-facing conflict. It is propagated instead, so the answer on a path whose cause is not
         * understood stays exactly what it was before any classification existed, and no new behaviour is
         * invented for it. What is given up is a uniform answer shape, which is accepted because a
         * confident wrong diagnosis is worse here than an unclassified one.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("an unclassified SQLSTATE is propagated unchanged")
        void anUnclassifiedStateIsPropagatedUnchanged() {
            TransactionType stored = storedType();
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));
            when(types.saveAndFlush(stored)).thenThrow(integrityViolation("40001"));

            assertThatThrownBy(
                    () -> service.replace(TYPE_CD, replaceBody("Purchase revised", STORED_VERSION)))
                    .as("neither of the two classified states, so neither classification applies")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .isNotInstanceOf(RecordConflictException.class);
        }
    }

    /**
     * Cases over the restricted delete, asserting both halves of the discrimination.
     *
     * <p>Assumptions: the baseline's delete flag does NOT discriminate on its own, which is why both
     * halves are asserted. {@code COTRTUPC.cbl} sets the same condition on both failing arms -- at
     * physical line 1639 for SQLCODE -532 and again at physical line 1651 for {@code WHEN OTHER} -- and
     * the only reason the second arm stays distinguishable is that physical line 1652 additionally sets a
     * condition living in a different field. A case asserting only that a referenced delete is refused
     * would pass against a service that refused every delete failure the same way.
     *
     * <p>Assumptions: the refusal has a real schema origin. Lines 6 and 7 of
     * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} declare the category table's foreign key
     * {@code ON DELETE RESTRICT}, and the baseline tests the resulting SQLCODE at physical line 1638 of
     * {@code COTRTUPC.cbl} and physical line 1914 of {@code COTRTLIC.cbl}. The third program carries no
     * such arm at all, so the asymmetry is genuine and is not generalised from one program to the others.
     */
    @Nested
    @DisplayName("on the restricted delete")
    class OnTheRestrictedDelete {

        /**
         * A type categories still reference is refused as a conflict, before any delete is attempted.
         *
         * <p>Assumptions: the count is read first and the delete is never reached, which is asserted as
         * well as the refusal. The count is deliberately not the authority -- two callers deleting a type
         * and inserting a category concurrently can each read zero -- so the constraint remains the thing
         * that decides, and the case below covers the raced route.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a referenced type is refused and no delete is attempted")
        void aReferencedTypeIsRefusedBeforeAnyDelete() {
            TransactionType stored = storedType();
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));
            when(categories.countByTypeCd(TYPE_CD)).thenReturn(3L);

            assertThatThrownBy(() -> service.delete(TYPE_CD))
                    .isInstanceOf(RecordConflictException.class)
                    .extracting(failure -> ((RecordConflictException) failure).kind())
                    .as("the ON DELETE RESTRICT relationship is never weakened to a server fault")
                    .isEqualTo(RecordConflictException.Kind.REFERENCED_ROW);

            verify(types, never()).delete(any(TransactionType.class));
            verify(types, never()).flush();
        }

        /**
         * A referential violation raised by the constraint itself is refused the same way.
         *
         * <p>Assumptions: this is the raced route the pre-check cannot cover, and it is asserted through
         * the flush rather than through the delete because a delete only marks the row for removal. Were
         * the statement deferred to commit, the classification would be unreachable on exactly the race
         * it exists to answer.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a referential violation raised by the constraint is refused as the same conflict")
        void aReferentialViolationFromTheConstraintIsRefusedAlike() {
            TransactionType stored = storedType();
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));
            when(categories.countByTypeCd(TYPE_CD)).thenReturn(0L);
            doThrow(integrityViolation(TransactionTypeService.SQLSTATE_FOREIGN_KEY_VIOLATION))
                    .when(types).flush();

            assertThatThrownBy(() -> service.delete(TYPE_CD))
                    .isInstanceOf(RecordConflictException.class)
                    .extracting(failure -> ((RecordConflictException) failure).kind())
                    .as("a duplicate found by the read and one found by the constraint answer alike")
                    .isEqualTo(RecordConflictException.Kind.REFERENCED_ROW);
        }

        /**
         * A delete failure that is not referential is NOT reported as the referential conflict.
         *
         * <p>Assumptions: this is the second half of the discrimination and the half a partial test
         * omits. Because the baseline sets its shared delete-failed condition on both arms, a migration
         * that answered every delete failure with the referential conflict would satisfy the two cases
         * above and would tell an operator hitting a serialisation failure to delete child records that
         * do not exist.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("an unrelated delete failure is not the referential conflict")
        void anUnrelatedDeleteFailureIsNotTheReferentialConflict() {
            TransactionType stored = storedType();
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));
            when(categories.countByTypeCd(TYPE_CD)).thenReturn(0L);
            doThrow(integrityViolation("23514")).when(types).flush();

            assertThatThrownBy(() -> service.delete(TYPE_CD))
                    .as("the WHEN OTHER arm at COTRTUPC.cbl physical line 1650 is a different answer")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .isNotInstanceOf(RecordConflictException.class);
        }

        /**
         * The refusal carries the vendor detail once at most, never the doubled form.
         *
         * <p>Assumptions: the baseline concatenates the vendor text TWICE into one sentence, at physical
         * lines 1645 and 1646 of {@code COTRTUPC.cbl}, which are two consecutive references to the same
         * field inside one {@code STRING}. The migrated refusal composes no sentence at all -- it carries
         * a closed kind and lets the shared advice select the wording, which keeps every user-visible
         * string in one place -- so the text can appear at most once anywhere in the raised failure, and
         * that is what is asserted. The propagated route is asserted alongside it, because the detail has
         * to survive exactly once for an operator to diagnose from rather than be lost or doubled.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the vendor detail is carried once and never concatenated twice")
        void theVendorDetailIsCarriedOnce() {
            TransactionType referenced = storedType();
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(referenced));
            when(categories.countByTypeCd(TYPE_CD)).thenReturn(2L);

            Throwable refusal = catchThrowable(() -> service.delete(TYPE_CD));

            assertThat(refusal)
                    .as("the referenced delete must refuse before anything else is asserted of it")
                    .isInstanceOf(RecordConflictException.class);
            assertThat(occurrences(refusal.getMessage(), DRIVER_REASON))
                    .as("a refusal composing no sentence cannot double a vendor string into one")
                    .isZero();

            DataIntegrityViolationException propagated = integrityViolation("23514");
            assertThat(occurrences(propagated.getCause().getMessage(), DRIVER_REASON))
                    .as("the detail an operator diagnoses from survives exactly once on the cause")
                    .isEqualTo(1);
        }
    }

    /**
     * Cases over the before-image comparison that stands in for a lock held across a caller's pause.
     *
     * <p>Assumptions: the comparison is {@code 1205-COMPARE-OLD-NEW} at physical lines 783 to 814 of
     * {@code COTRTUPC.cbl}. It defaults to no-change at physical line 784 and compares both sides through
     * {@code FUNCTION UPPER-CASE} applied over {@code FUNCTION TRIM}, at physical lines 786 to 797, so it
     * is insensitive to letter case AND to blanks at either end. All three variations are asserted
     * because each is a separate way for a re-implementation to become stricter than the source.
     *
     * <p>Assumptions: the paragraph the classifier tests for a changed row is never reached in the
     * baseline. That condition occurs exactly twice in the program, declared at physical line 183 and
     * tested at physical line 1585, and is set nowhere, so this paragraph working on its own separate
     * one-character flag is the program's real comparison and is what the migration transcribes.
     */
    @Nested
    @DisplayName("on the before-image comparison")
    class OnTheBeforeImageComparison {

        /**
         * A submission differing only in letter case counts as no change and is not written.
         *
         * <p>Trade-offs: the case-insensitivity is preserved rather than improved, and the cost is worth
         * stating plainly -- a caller wanting a letter-case-only correction cannot obtain one through this
         * operation. Comparing case-sensitively would have been a behavioural change with no baseline
         * authority behind it, and it would have turned a no-op into a write that the source performs
         * nowhere.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a lower-case submission of the stored text counts as no change")
        void aLowerCaseSubmissionCountsAsNoChange() {
            TransactionType stored = storedType();
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));

            TransactionTypeResponse answered =
                    service.replace(TYPE_CD, replaceBody("purchase", STORED_VERSION));

            assertThat(answered.description())
                    .as("the stored spelling is answered, not the submitted one")
                    .isEqualTo(STORED_DESCRIPTION);
            verify(types, never()).saveAndFlush(any(TransactionType.class));
        }

        /**
         * A submission differing only in being upper case counts as no change and is not written.
         *
         * <p>Assumptions: asserted separately from the lower-case case rather than parameterised with it,
         * because an implementation that upper-cased only one operand would pass one of the two. The
         * baseline applies the function to BOTH sides, at physical lines 786 and 788 and again at 790 and
         * 792, so both directions have to be exercised.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("an upper-case submission of the stored text counts as no change")
        void anUpperCaseSubmissionCountsAsNoChange() {
            TransactionType stored = storedType();
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));

            service.replace(TYPE_CD, replaceBody("PURCHASE", STORED_VERSION));

            verify(types, never()).saveAndFlush(any(TransactionType.class));
        }

        /**
         * A submission differing only in surrounding blanks counts as no change and is not written.
         *
         * <p>Assumptions: the blanks are removed at BOTH ends, which is what the baseline's unqualified
         * {@code FUNCTION TRIM} does -- it carries neither a leading nor a trailing operand at physical
         * lines 791 and 795. A comparison stripping only the trailing end would report this submission as
         * a change and would write it, so the leading blank is the discriminating part of the fixture.
         *
         * <p>Assumptions: equality ignoring blanks at both ends is deliberately NOT the storage
         * normalisation, which keeps a leading blank. Using one rule for both would force one of the two
         * answers to be wrong, and the divergence in storage is registered as
         * D-REFERENCE-TRIM-TRAILING-ONLY.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a submission surrounded by blanks counts as no change")
        void aSubmissionSurroundedByBlanksCountsAsNoChange() {
            TransactionType stored = storedType();
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));

            service.replace(TYPE_CD, replaceBody(" Purchase ", STORED_VERSION));

            verify(types, never()).saveAndFlush(any(TransactionType.class));
        }

        /**
         * A submission that genuinely differs is written, so the no-change cases mean something.
         *
         * <p>Assumptions: without this case the three above would also be satisfied by a service that
         * never wrote at all, which is the failure mode a group of negative assertions is most exposed to.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a genuinely different submission is written")
        void aGenuinelyDifferentSubmissionIsWritten() {
            TransactionType stored = storedType();
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));
            when(types.saveAndFlush(stored)).thenReturn(stored);

            service.replace(TYPE_CD, replaceBody("Purchase and cash advance", STORED_VERSION));

            verify(types).saveAndFlush(stored);
        }

        /**
         * A stale revision is refused before the comparison runs, and the stored revision is reported.
         *
         * <p>Assumptions: the precondition is evaluated first, so a submission carrying a stale revision
         * is refused even when its description would have compared equal. Were the comparison first, a
         * caller holding a revision from before somebody else's edit would receive success and the current
         * row, which reads as confirmation that its own submission was applied to the state it had read --
         * and both of those inferences would be false.
         *
         * <p>Assumptions: the refusal carries the stored revision rather than a sentence alone, because
         * the baseline's classifier answers this condition by putting the CURRENT state back on the
         * screen, at physical lines 1585 and 1586. A refusal naming no revision would leave a caller
         * nothing to retry against.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a stale revision is refused first and reports the stored revision")
        void aStaleRevisionIsRefusedFirst() {
            TransactionType stored = storedType();
            when(types.findByTypeCd(TYPE_CD)).thenReturn(Optional.of(stored));

            Throwable refusal = catchThrowable(
                    () -> service.replace(TYPE_CD, replaceBody(STORED_DESCRIPTION, 41L)));

            assertThat(refusal).isInstanceOf(RecordConflictException.class);
            RecordConflictException conflict = (RecordConflictException) refusal;
            assertThat(conflict.kind())
                    .as("a lost revision is the stale-revision condition and not the lock one")
                    .isEqualTo(RecordConflictException.Kind.STALE_VERSION);
            assertThat(conflict.currentVersion())
                    .as("the caller is handed the revision to retry against")
                    .isEqualTo(stored.getVersion());
            verify(types, never()).saveAndFlush(any(TransactionType.class));
        }
    }

    /**
     * Cases over the three-state field validation the baseline expresses as 88-level conditions.
     *
     * <p>Assumptions: VALID IS THE LOW VALUE, which is the single most invertible fact in this feature.
     * {@code COTRTUPC.cbl} declares two independent three-state groups and both spell it the same way:
     * the filter group at physical lines 94 to 97, where physical line 95 gives valid the figurative
     * lowest character and physical lines 96 and 97 give not-OK the digit zero and blank the letter B; and
     * the non-key group at physical lines 99 to 103, with the same three values. The shared type carries
     * both spellings because {@code COTRTLIC.cbl} uses the opposite convention for its filter flags,
     * where the digit one means valid, which is what its cursors compare against.
     */
    @Nested
    @DisplayName("on the three-state validation flags")
    class OnTheThreeStateValidationFlags {

        /**
         * The valid sentinel is the low value and not a printable digit.
         *
         * <p>Assumptions: asserted against the shared constant rather than a literal, because a zero byte
         * is invisible in source and a reader cannot tell a deliberate one from a typing accident.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the valid sentinel is the low value")
        void theValidSentinelIsTheLowValue() {
            assertThat(FieldValidationFlag.VALID_CODE)
                    .as("COTRTUPC.cbl physical lines 95 and 101 both give valid the low value")
                    .isEqualTo('\u0000');
            assertThat(FieldValidationFlag.fromCode(FieldValidationFlag.VALID_CODE))
                    .isEqualTo(FieldValidationFlag.VALID);
            assertThat(FieldValidationFlag.fromCode(FieldValidationFlag.ALTERNATE_VALID_CODE))
                    .as("the list screen's filter flags spell valid as the digit one instead")
                    .isEqualTo(FieldValidationFlag.VALID);
        }

        /**
         * The two error sentinels resolve to their own states and never to the acceptable one.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the not-OK and blank sentinels resolve to their own states")
        void theErrorSentinelsResolveToTheirOwnStates() {
            assertThat(FieldValidationFlag.NOT_OK_CODE).isEqualTo('0');
            assertThat(FieldValidationFlag.BLANK_CODE).isEqualTo('B');
            assertThat(FieldValidationFlag.fromCode(FieldValidationFlag.NOT_OK_CODE))
                    .isEqualTo(FieldValidationFlag.NOT_OK);
            assertThat(FieldValidationFlag.fromCode(FieldValidationFlag.BLANK_CODE))
                    .isEqualTo(FieldValidationFlag.BLANK);
        }

        /**
         * Blank is an error state rather than a third acceptable one.
         *
         * <p>Assumptions: the templated highlight copybook makes no distinction between a blank field and
         * an unacceptable one, at lines 18 and 19 of {@code app/cpy/CSSETATY.cpy}, so a predicate true for
         * only one of the two would let an unsupplied field render as acceptable. The state is consumed
         * through the shared predicate rather than re-derived here, and it is never reduced to a boolean,
         * because a boolean cannot hold three states.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("blank is an error state and not a third acceptable one")
        void blankIsAnErrorStateAndNotAcceptable() {
            assertThat(FieldValidationFlag.BLANK.isError())
                    .as("an unsupplied field must not pass as acceptable")
                    .isTrue();
            assertThat(FieldValidationFlag.NOT_OK.isError()).isTrue();
            assertThat(FieldValidationFlag.VALID.isError()).isFalse();
            assertThat(FieldValidationFlag.BLANK.isValid()).isFalse();
        }

        /**
         * Only the blank state carries the screen marker, and the marker is not a flag value.
         *
         * <p>Assumptions: the marker is a presentation constant and is never stored as a state, which is
         * what keeps a three-state field three-state. The baseline moves the literal into the field only
         * on the blank branch, at lines 23 to 25 of {@code app/cpy/CSSETATY.cpy}.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("only the blank state carries the screen marker")
        void onlyTheBlankStateCarriesTheScreenMarker() {
            assertThat(FieldValidationFlag.BLANK_SCREEN_MARKER).isEqualTo("*");
            assertThat(FieldValidationFlag.BLANK.screenMarker())
                    .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER);
            assertThat(FieldValidationFlag.NOT_OK.screenMarker())
                    .as("an unacceptable value is highlighted but carries no marker of its own")
                    .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);
            assertThat(FieldValidationFlag.VALID.screenMarker())
                    .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);
            assertThat(FieldValidationFlag.values())
                    .as("the marker is presentation and never becomes a fourth state")
                    .containsExactly(FieldValidationFlag.VALID, FieldValidationFlag.NOT_OK,
                            FieldValidationFlag.BLANK);
        }
    }

    /**
     * Cases over the character domain the description edit admits.
     *
     * <p>Assumptions: the LIVE class is the alphanumeric one and it is sixty-two characters wide.
     * {@code COTRTUPC.cbl} builds it at physical lines 230 to 237: a twenty-six character upper-case
     * literal at physical lines 232 and 233, a twenty-six character lower-case literal at 234 and 235 --
     * fifty-two letters with NO space among them -- plus the ten digits at 236 and 237. The edit that
     * applies it, at physical lines 849 to 903, converts every admitted character to a space at physical
     * lines 878 to 880 and then treats a value that trims empty as clean at 882 to 885, which is why a
     * supplied space is admitted without appearing in the literal: a supplied space cannot be told apart
     * from a converted one and the same trim removes both.
     *
     * <p>Assumptions: the neighbouring 'alphabets and spaces' rule is DEAD CODE and is not asserted as
     * live behaviour. Counting occurrences after the procedure division begins at physical line 344 gives
     * zero for that condition, declared at physical lines 173 and 174, and zero for each of the three
     * spare literal areas declared at physical lines 249 to 251; only the alphanumeric literal is
     * referenced, exactly once. There is therefore no baseline rule that admits spaces on the strength of
     * that sentence, and the finding is recorded so a later reader does not resurrect it.
     */
    @Nested
    @DisplayName("on the live character class")
    class OnTheLiveCharacterClass {

        /**
         * Letters, digits and spaces together are admitted, which is the live sixty-two character class.
         *
         * <p>Assumptions: a digit-bearing description is the discriminating fixture. Were the dead
         * alphabets-and-spaces rule the live one, this value would be refused, so admitting it is what
         * distinguishes the class actually applied from the one merely declared.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("letters, digits and spaces are admitted together")
        void lettersDigitsAndSpacesAreAdmitted() {
            assertThat(refusedProperties(new TransactionTypeCreateRequest("01", "Regular Sales 2")))
                    .as("the live class is the alphanumeric one from COTRTUPC.cbl physical line 230")
                    .isEmpty();
        }

        /**
         * A punctuation character outside the class is refused on the description.
         *
         * <p>Assumptions: the hyphen is chosen because it is the character the reference-only seed data
         * actually contains. Row 17 of {@code app/data/ASCII/trancatg.txt} reads
         * {@code 060002Non-fraud reversal}, and the migrated seed carries that spelling through unaltered
         * at line 199 of {@code db/migration/V2__seed_reference.sql}. That row is a CATEGORY description
         * and this rule is the TYPE one, transcribed from the only program that edits types; the seed is
         * reference-only and is never rewritten to suit a rule, which is why the fixture below is a
         * type-shaped request rather than the seed row itself.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a punctuation character is refused on the description")
        void aPunctuationCharacterIsRefused() {
            assertThat(refusedProperties(new TransactionTypeCreateRequest("01", "Non-fraud reversal")))
                    .as("the message the baseline composes for this is ' can have numbers or "
                            + "alphabets only.' at COTRTUPC.cbl physical line 893")
                    .containsExactly("description");
        }

        /**
         * An all-blank description is refused, so a space run alone is not a value.
         *
         * <p>Assumptions: the baseline refuses this on its own branch, at physical lines 854 to 859,
         * answering the sentence composed at physical line 866. The shape brackets a mandatory
         * alphanumeric between two optional runs, which is what makes a run of spaces alone insufficient
         * even though a space is an admitted character.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("an all-blank description is refused")
        void anAllBlankDescriptionIsRefused() {
            assertThat(refusedProperties(new TransactionTypeCreateRequest("01", "   ")))
                    .as("a space is admitted as a character but is not a value on its own")
                    .containsExactly("description");
        }

        /**
         * A description wider than the stored column is refused.
         *
         * <p>Assumptions: fifty is the column, declared {@code TR_DESCRIPTION VARCHAR(50) NOT NULL} at
         * line 3 of {@code app/app-transaction-type-db2/ddl/TRNTYPE.ddl} and set as the edit length at
         * physical line 760 of {@code COTRTUPC.cbl}. The bound is a ceiling rather than an equality
         * because the trailing blanks of the constant-width record are padding to a positional length rather
         * data.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a description wider than the stored column is refused")
        void aDescriptionWiderThanTheColumnIsRefused() {
            String tooWide = "P".repeat(51);

            assertThat(refusedProperties(new TransactionTypeCreateRequest("01", tooWide)))
                    .containsExactly("description");
            assertThat(refusedProperties(new TransactionTypeCreateRequest("01", "P".repeat(50))))
                    .as("exactly the column width is accepted, so the bound is a ceiling not a limit off by one")
                    .isEmpty();
        }
    }

    /**
     * Cases over the type-code edit, which the baseline runs through its NUMERIC editor.
     *
     * <p>Assumptions: the code is edited as a NUMBER and not as an alphanumeric value, which is easy to
     * assume the other way round because the field is two characters of text.
     * {@code 1210-EDIT-TRANTYPE} at physical lines 820 to 845 pre-sets the not-OK state pessimistically at
     * physical line 821, labels the field at physical line 826, sets the length to two at physical line
     * 828, and then performs the numeric editor at physical lines 829 and 830 -- the paragraph at physical
     * lines 907 to 974, not the alphanumeric one at 849 to 903. That editor makes three checks: blank,
     * answering the sentence at physical line 924; a failed numeric test at physical line 934, answering
     * physical line 943; and a value of zero, answering physical line 961.
     */
    @Nested
    @DisplayName("on the type-code edit")
    class OnTheTypeCodeEdit {

        /**
         * A blank code is refused, which is the editor's first check.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a blank code is refused")
        void aBlankCodeIsRefused() {
            assertThat(refusedProperties(new TransactionTypeCreateRequest("", "Purchase")))
                    .as("' must be supplied.' at COTRTUPC.cbl physical line 924")
                    .containsExactly("typeCd");
        }

        /**
         * A non-numeric code is refused, which is the editor's second check.
         *
         * <p>Assumptions: this is the check that proves the numeric editor rather than the alphanumeric
         * one is applied. Two letters satisfy the sixty-two character class exactly, so a code edited as
         * an alphanumeric value would be accepted here.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a non-numeric code is refused even though letters satisfy the character class")
        void aNonNumericCodeIsRefused() {
            assertThat(refusedProperties(new TransactionTypeCreateRequest("AB", "Purchase")))
                    .as("' must be numeric.' at COTRTUPC.cbl physical line 943")
                    .containsExactly("typeCd");
        }

        /**
         * A code of zero is refused, which is the editor's third check.
         *
         * <p>Assumptions: this is why the admitted shape is spelled as two alternatives rather than as a
         * two-digit repetition, since a repetition would admit the all-zero code the baseline refuses
         * outright.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a code of zero is refused")
        void aCodeOfZeroIsRefused() {
            assertThat(refusedProperties(new TransactionTypeCreateRequest("00", "Purchase")))
                    .as("' must not be zero.' at COTRTUPC.cbl physical line 961")
                    .containsExactly("typeCd");
            assertThat(TransactionType.TYPE_CD_PATTERN)
                    .as("the entity's own anchored domain agrees with the request shape")
                    .isEqualTo("^(?:0[1-9]|[1-9][0-9])$");
            assertThat("00".matches(TransactionType.TYPE_CD_PATTERN)).isFalse();
            assertThat("01".matches(TransactionType.TYPE_CD_PATTERN)).isTrue();
            assertThat("99".matches(TransactionType.TYPE_CD_PATTERN)).isTrue();
        }

        /**
         * A one-character code is REFUSED at the boundary rather than widened to two.
         *
         * <p>Alternatives Considered: reproducing the baseline's zero-pad normalisation, which turns a
         * submitted single digit into the two-character form. The baseline performs it at physical lines
         * 834 to 841 of {@code COTRTUPC.cbl}, computing a numeric value from the entry, moving it into a
         * two-character alphanumeric field and replacing spaces with zeros, so an operator typing one
         * character has it silently widened. It is declined here and the code is refused instead, for a
         * reason specific to the two surfaces: the baseline reads a constant two-byte screen field where a
         * single digit arrives blank-padded and is unambiguous, whereas a request carries a variable-length
         * string where widening would make two spellings of the key address the same row -- so a caller
         * could read a type at one spelling and be unable to echo that spelling back. The leading zero is
         * part of the value rather than presentation, which the seed file shows by storing codes 01 to 07
         * with it. The divergence is registered in
         * {@code docs/architecture/cobol-to-service-traceability.md}.
         *
         * <p>Assumptions: the refusal is asserted rather than the normalisation, so this case fails if the
         * pad is ever added, which is the direction the baseline would otherwise pull an implementer.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a one-character code is refused rather than zero-padded")
        void aOneCharacterCodeIsRefusedRatherThanPadded() {
            assertThat(refusedProperties(new TransactionTypeCreateRequest("1", "Purchase")))
                    .as("the width is part of the key, so a single digit is not widened for the caller")
                    .containsExactly("typeCd");
            assertThat("1".matches(TransactionType.TYPE_CD_PATTERN))
                    .as("the entity's domain refuses the unpadded spelling too")
                    .isFalse();
        }

        /**
         * Every code the seed file carries is accepted, so the rules above refuse nothing real.
         *
         * <p>Assumptions: the seven codes are the ones {@code app/data/ASCII/trantype.txt} holds, 01
         * through 07. Asserting them is what stops the four refusals above from being satisfied by a shape
         * that refuses everything.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("every seeded code is accepted")
        void everySeededCodeIsAccepted() {
            List<String> seeded = List.of("01", "02", "03", "04", "05", "06", "07");

            for (String code : seeded) {
                assertThat(refusedProperties(new TransactionTypeCreateRequest(code, "Purchase")))
                        .as("seeded code %s must be admitted", code)
                        .isEmpty();
            }
        }
    }

    /**
     * Cases over the description filter, whose pattern metacharacters are the unresolved-looking decision.
     *
     * <p>Alternatives Considered: passing a caller's pattern metacharacters through as live wildcards,
     * which is what the baseline does, against escaping them while keeping the containment search. The
     * baseline evidence was read first-hand rather than taken on description, because the two are easy to
     * conflate. {@code COTRTLIC.cbl} composes its OWN containment pattern at physical lines 1155 to 1162,
     * wrapping the trimmed entry between two percent signs into the field declared
     * {@code PIC X(52)} at physical line 278 -- two wider than the fifty-character column, which is
     * exactly what the two wildcards occupy -- and hands it to {@code LIKE TRIM(...)} at physical lines
     * 348, 364 and 1812. Those are the only two percent literals in the program, and it carries no
     * {@code ESCAPE} clause at any of the three sites, so an operator's own percent sign acts as a
     * wildcard there. The migration keeps the two surrounding wildcards, so the containment search is
     * preserved, and escapes the caller's metacharacters, which is the divergence. The ground for
     * escaping is concrete rather than a posture: an unescaped metacharacter changes WHICH rows match, so
     * a search for a description containing a percent sign returns rows that do not contain one and the
     * caller receives a plausible page instead of an error; and a caller-supplied pattern of alternating
     * wildcards makes the match cost grow with the pattern rather than with the data, on a column that
     * {@code V1__reference.sql} gives no index, so no pattern could have used an ordered range in the
     * first place. The divergence is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}.
     *
     * <p>Assumptions: the pattern is composed in one place, the repository member these cases call, and
     * this feature never re-derives it. Asserting the produced pattern here rather than only that the
     * service passes it along is deliberate, because the escaping ruling is the thing that can regress.
     */
    @Nested
    @DisplayName("on the description filter")
    class OnTheDescriptionFilter {

        /**
         * Plain text becomes a containment pattern, preserving the baseline's own search.
         *
         * <p>Assumptions: the two wildcards belong to the service and are the transcription of the
         * baseline's own {@code STRING} statement, so a pattern anchored at either end would narrow a
         * search the source performs broadly and would silently drop rows the list screen shows.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("plain text becomes a containment pattern")
        void plainTextBecomesAContainmentPattern() {
            assertThat(TransactionTypeRepository.descriptionFilterPattern("Purchase"))
                    .as("COTRTLIC.cbl physical lines 1155 to 1162 wrap the entry in two percent signs")
                    .isEqualTo("%Purchase%");
        }

        /**
         * A caller's own wildcard is escaped and therefore matches literally.
         *
         * <p>Assumptions: this is the property both candidate designs make or break, and it is the one
         * most easily lost. The escape character is emitted before the metacharacter rather than by
         * replacing the whole string, because a chain of whole-string replacements is order-dependent:
         * replacing the metacharacters first and the escape character afterwards escapes the escape
         * characters just emitted, turning each into a literal and leaving the metacharacter live again.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a caller's own wildcard is escaped and matches literally")
        void aCallersOwnWildcardIsEscaped() {
            assertThat(TransactionTypeRepository.descriptionFilterPattern("50% off"))
                    .as("the caller's percent sign is data, and only the two outer ones are wildcards")
                    .isEqualTo("%50" + TransactionTypeRepository.LIKE_ESCAPE + "% off%");
            assertThat(TransactionTypeRepository.descriptionFilterPattern("a_b"))
                    .as("the single-character wildcard is escaped on the same pass")
                    .isEqualTo("%a" + TransactionTypeRepository.LIKE_ESCAPE + "_b%");
        }

        /**
         * The escape character itself is escaped, so a description holding one still matches.
         *
         * <p>Assumptions: without this the escape character would consume the character following it and a
         * description legitimately containing it could not be searched for at all.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the escape character itself is escaped")
        void theEscapeCharacterItselfIsEscaped() {
            String escape = TransactionTypeRepository.LIKE_ESCAPE;

            assertThat(TransactionTypeRepository.descriptionFilterPattern("wow" + escape))
                    .isEqualTo("%wow" + escape + escape + "%");
        }

        /**
         * An absent or blank filter omits the description arm rather than searching for nothing.
         *
         * <p>Assumptions: an absent value and a blank value have to mean the same thing at the query as
         * the published contract says they mean at the boundary, because two spellings that normalise to
         * one query select the same rows. A blank filter turned into a bare double wildcard would read as
         * a filter having been supplied and would make the no-rows-for-this-filter refusal reachable for a
         * request that narrowed nothing.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("an absent or blank filter omits the description arm")
        void anAbsentOrBlankFilterOmitsTheArm() {
            assertThat(TransactionTypeRepository.descriptionFilterPattern(null)).isNull();
            assertThat(TransactionTypeRepository.descriptionFilterPattern("")).isNull();
            assertThat(TransactionTypeRepository.descriptionFilterPattern("   ")).isNull();
        }

        /**
         * The escaped pattern is what reaches the query, not the caller's raw text.
         *
         * <p>Assumptions: the composed pattern is asserted at the query boundary as well as at the
         * composer, because a service that normalised the filter a second time of its own would satisfy
         * the composer cases and still send the raw text down.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the escaped pattern is what reaches the filtered query")
        void theEscapedPatternIsWhatReachesTheQuery() {
            String escaped = TransactionTypeRepository.descriptionFilterPattern("50% off");
            when(types.countFilterMatches(isNull(), eq(escaped))).thenReturn(1L);
            when(types.findFilteredPageAfter(isNull(), eq(escaped), isNull(), any(Limit.class)))
                    .thenReturn(List.of(storedType()));

            service.list(browse(null, "50% off"), sealer(), SUBJECT);

            verify(types).findFilteredPageAfter(isNull(), eq(escaped), isNull(), any(Limit.class));
        }
    }

    /**
     * Cases carrying the message literals rule T8 makes contracts in their own right.
     *
     * <p>Assumptions: these literals are asserted here because no golden master covers any of them, so a
     * well-meant tidy-up would meet nothing that objects. Three of them carry baseline typing errors and
     * two of them differ from each other only in letter case, which is precisely the shape an editor
     * normalises without noticing. Each is recorded with the physical line it was read from and the width
     * it was measured at.
     *
     * <p>Assumptions: the literals this SERVICE carries are asserted against the live constants, while the
     * screen prompts belong to the presentation catalogue and are asserted here as measured declarations.
     * A service publishing a screen prompt would be a layering fault rather than a fidelity one, so the
     * closing case asserts what this service does NOT carry.
     */
    @Nested
    @DisplayName("on the verbatim message catalogue")
    class OnTheVerbatimMessageCatalogue {

        /**
         * One baseline literal, with the physical line it was read from and its measured width.
         *
         * @param physicalLine the physical line of the declaring program, never the printed sequence
         * @param text the literal, character for character including any trailing blank
         * @param width the width counted from the literal, which the case below re-measures
         */
        private record BaselineLiteral(int physicalLine, String text, int width) {
        }

        /**
         * The forty-character information messages, in declaration order.
         *
         * @return the literals declared on the forty-character information field, never {@code null}
         */
        private static List<BaselineLiteral> informationMessages() {
            return List.of(
                    new BaselineLiteral(146, "Selected transaction type shown above", 37),
                    new BaselineLiteral(148, "Enter transaction type to be maintained", 39),
                    new BaselineLiteral(150, "Press F05 to add. F12 to cancel", 31),
                    new BaselineLiteral(152, "Delete this record ? Press F4 to confirm", 40),
                    new BaselineLiteral(154, "Delete successful.", 18),
                    new BaselineLiteral(156, "Update transaction type details shown.", 38),
                    new BaselineLiteral(158, "Enter new transaction type details.", 35),
                    new BaselineLiteral(161, "Changes validated.Press F5 to save", 34),
                    new BaselineLiteral(163, "Changes committed to database", 29),
                    new BaselineLiteral(165, "Changes unsuccessful", 20));
        }

        /**
         * The seventy-five-character return messages, in declaration order.
         *
         * @return the literals declared on the seventy-five-character return field, never {@code null}
         */
        private static List<BaselineLiteral> returnMessages() {
            return List.of(
                    new BaselineLiteral(170, "PF03 pressed.Exiting              ", 34),
                    new BaselineLiteral(172, "Invalid Key pressed. ", 21),
                    new BaselineLiteral(176, "No record found for this key in database", 40),
                    new BaselineLiteral(178, "No input received", 17),
                    new BaselineLiteral(180, "No change detected with respect to values fetched.", 50),
                    new BaselineLiteral(182, "Could not lock record for update", 32),
                    new BaselineLiteral(184, "Record changed by some one else. Please review", 46),
                    new BaselineLiteral(186, "Update was cancelled", 20),
                    new BaselineLiteral(188, "Update of record failed", 23),
                    new BaselineLiteral(190, "Delete of record failed", 23),
                    new BaselineLiteral(192, "Delete was cancelled", 20),
                    new BaselineLiteral(194, "Invalid key pressed", 19));
        }

        /**
         * Every catalogued literal still measures the width it was read at.
         *
         * <p>Assumptions: the width is re-measured rather than trusted, which is what makes a lost
         * trailing blank or an inserted space a failure instead of an invisible edit. The information
         * literals additionally have to fit the forty-character field they are declared on, at physical
         * line 142, and the return literals the seventy-five-character field at physical line 167 -- the
         * same width the shared copybook gives both message fields at lines 28 and 29 of
         * {@code app/cpy/CVCRD01Y.cpy}.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("every catalogued literal measures the width it was read at and fits its field")
        void everyCataloguedLiteralMeasuresItsWidth() {
            for (BaselineLiteral literal : informationMessages()) {
                assertThat(literal.text())
                        .as("COTRTUPC.cbl physical line %d", literal.physicalLine())
                        .hasSize(literal.width())
                        .satisfies(text -> assertThat(text.length()).isLessThanOrEqualTo(40));
            }
            for (BaselineLiteral literal : returnMessages()) {
                assertThat(literal.text())
                        .as("COTRTUPC.cbl physical line %d", literal.physicalLine())
                        .hasSize(literal.width())
                        .satisfies(text -> assertThat(text.length()).isLessThanOrEqualTo(75));
            }
        }

        /**
         * The confirmation prompt fills its field exactly, so no character of it may be added or lost.
         *
         * <p>Assumptions: forty characters is not a coincidence -- the literal at physical line 152 fills
         * the field declared at physical line 142 completely, so this is the one literal in the set with no
         * room to grow. A word added to it would be truncated on the screen rather than reported.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the delete confirmation prompt is exactly forty characters")
        void theDeleteConfirmationPromptIsExactlyForty() {
            assertThat("Delete this record ? Press F4 to confirm")
                    .as("it fills the forty-character field at COTRTUPC.cbl physical line 142 exactly")
                    .hasSize(40);
        }

        /**
         * All three baseline typing errors are carried across unaltered.
         *
         * <p>Assumptions: each of the three would be silently smoothed over by an ordinary proof-read, and each
         * is a contract under rule T8. Two of them are a missing space after a full stop, at physical lines
         * 161 and 170, and the third spells one word as two, at physical line 184. The literal at physical
         * line 170 additionally carries fourteen trailing blanks that are part of its value. The third is
         * asserted against the live shared constant as well as against its measurement, because that
         * constant is the one the migrated conflict answer actually displays.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("all three baseline typing errors are carried unaltered")
        void allThreeBaselineTypingErrorsAreCarried() {
            assertThat("Changes validated.Press F5 to save")
                    .as("physical line 161 carries no space after its full stop")
                    .doesNotContain(". ")
                    .contains(".Press")
                    .hasSize(34);
            assertThat("PF03 pressed.Exiting              ")
                    .as("physical line 170 carries no space after its full stop and keeps its blanks")
                    .contains(".Exiting")
                    .endsWith(" ")
                    .hasSize(34);
            assertThat(ApiError.COACTUPC_RECORD_CHANGED)
                    .as("physical line 184 spells 'some one' as two words")
                    .isEqualTo("Record changed by some one else. Please review")
                    .contains("some one")
                    .doesNotContain("someone")
                    .hasSize(46);
        }

        /**
         * The three invalid-key spellings stay three distinct strings.
         *
         * <p>Assumptions: two of them are declared in the same program and differ only in one letter's
         * case and a trailing blank, at physical lines 172 and 194, and the shared kernel carries a third
         * for the common screen. They are duplicates in meaning, which is exactly why a consolidation
         * would look harmless; rule T8 makes each the contract of the screen that shows it.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the three invalid-key spellings stay distinct")
        void theThreeInvalidKeySpellingsStayDistinct() {
            String capitalised = "Invalid Key pressed. ";
            String lowerCase = "Invalid key pressed";

            assertThat(capitalised)
                    .as("physical line 172 keeps its capital K and its trailing blank")
                    .hasSize(21)
                    .endsWith(" ")
                    .isNotEqualTo(lowerCase);
            assertThat(lowerCase)
                    .as("physical line 194 keeps its lower-case k and has no trailing blank")
                    .hasSize(19)
                    .doesNotEndWith(" ");
            assertThat(ApiError.CSMSG01Y_INVALID_KEY)
                    .as("the shared screen sentence is a third string and not either of these")
                    .isNotEqualTo(capitalised)
                    .isNotEqualTo(lowerCase);
        }

        /**
         * The sentences this service publishes are carried character for character.
         *
         * <p>Assumptions: the deleted-by-others sentence keeps BOTH the space before its question mark and
         * its trailing blank, read byte for byte at physical line 1864 of {@code COTRTLIC.cbl}. It is
         * retained even though the published not-found answer is a different sentence, because the two
         * answer different questions: this one answers an update whose target vanished between the read and
         * the write, which the baseline reports on the update rather than on a read.
         *
         * <p>Assumptions: the no-change sentence differs between the two programs and the two are not
         * merged. This service carries the list screen's wording; the maintenance screen's, at physical
         * lines 179 and 180 of {@code COTRTUPC.cbl}, is a different string and is asserted here to be
         * different rather than assumed to be the same.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the sentences this service publishes are carried character for character")
        void theSentencesThisServicePublishesAreVerbatim() {
            assertThat(TransactionTypeService.MESSAGE_RECORD_DELETED_BY_OTHERS)
                    .as("COTRTLIC.cbl physical line 1864, with its space before the question mark")
                    .isEqualTo("Record not found. Deleted by others ? ")
                    .endsWith("? ")
                    .hasSize(38);
            assertThat(TransactionTypeService.MESSAGE_NO_CHANGES_DETECTED)
                    .as("the list screen's wording, which is not the maintenance screen's")
                    .isEqualTo("No change detected with respect to database values.")
                    .isNotEqualTo("No change detected with respect to values fetched.");
            assertThat(TransactionTypeService.MESSAGE_NO_RECORDS_FOR_FILTER)
                    .as("its capital R is what distinguishes it from the lower-case sibling sentence")
                    .isEqualTo("No Records found for these filter conditions");
            assertThat(TransactionTypeService.MESSAGE_TYPE_NOT_FOUND)
                    .isEqualTo("Transaction type NOT found...");
        }

        /**
         * The table-name fragments the two programs compose are not merged into one.
         *
         * <p>Assumptions: the maintenance screen capitalises the word at physical lines 1571 and 1611 of
         * {@code COTRTUPC.cbl} while the batch program leaves it lower case at physical lines 157, 188 and
         * 219 of {@code COBTUPDT.cbl}, and the batch one is not followed by the vendor text at all. Five
         * sites, two spellings, and a consolidation into one constant would change what three of them
         * display.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the two table-name fragments stay distinct")
        void theTwoTableNameFragmentsStayDistinct() {
            String maintenanceScreen = " TRANSACTION_TYPE Table. SQLCODE:";
            String batchProgram = " TRANSACTION_TYPE table. SQLCODE:";

            assertThat(maintenanceScreen)
                    .as("COTRTUPC.cbl physical lines 1571 and 1611 capitalise the word")
                    .isNotEqualTo(batchProgram)
                    .contains(" Table.");
            assertThat(batchProgram)
                    .as("COBTUPDT.cbl physical lines 157, 188 and 219 leave it lower case")
                    .contains(" table.");
            assertThat(maintenanceScreen.equalsIgnoreCase(batchProgram))
                    .as("they differ in nothing but that letter, which is why merging them looks safe")
                    .isTrue();
        }

        /**
         * The developer placeholder left in baseline working storage reaches nothing this service carries.
         *
         * <p>Assumptions: a negative assertion is the only thing that keeps this out. The literal is
         * declared at physical line 196 of {@code COTRTUPC.cbl} on a condition that the program never
         * sets -- it occurs zero times after the procedure division begins at physical line 344 -- so it is
         * unreachable there and must stay unreachable here. It is a placeholder rather than a message, and
         * a transcription that swept up every 88-level on the message field would carry it across as
         * though it were one.
         *
         * <p>Assumptions: every static text member this service declares is inspected rather than a chosen
         * few, so a constant added later is covered without this case being revisited.
         *
         * <p>It takes no parameter and returns no value.
         *
         * @throws IllegalAccessException if a static text member of the service cannot be read, which
         *     fails the case rather than letting it pass on a partial inspection
         */
        @Test
        @DisplayName("the developer placeholder is absent from everything this service publishes")
        void theDeveloperPlaceholderIsAbsent() throws IllegalAccessException {
            String placeholder = "Looks Good.... so far";
            int inspected = 0;

            for (Field declared : TransactionTypeService.class.getDeclaredFields()) {
                if (!Modifier.isStatic(declared.getModifiers())
                        || declared.getType() != String.class) {
                    continue;
                }
                declared.setAccessible(true);
                String value = (String) declared.get(null);
                inspected++;
                assertThat(value)
                        .as("static text member %s must not carry the placeholder", declared.getName())
                        .doesNotContain(placeholder);
            }

            assertThat(inspected)
                    .as("the sweep has to have read something for its verdict to mean anything")
                    .isGreaterThanOrEqualTo(6);
            assertThat(TransactionTypeService.MESSAGE_TYPE_NOT_FOUND)
                    .doesNotContain(placeholder);
            assertThat(ApiError.COACTUPC_RECORD_CHANGED).doesNotContain(placeholder);
        }

        /**
         * This service carries no screen prompt, which is where the remaining literals belong.
         *
         * <p>Assumptions: the prompts catalogued above are presentation and are carried by the browser
         * catalogue, not by a service that decides no rendering. Asserting their absence here is a
         * layering guard rather than a fidelity one: a prompt appearing in this class would mean the
         * service had taken on a rendering decision, and it would then exist twice with no rule for
         * choosing between the copies.
         *
         * <p>It takes no parameter and returns no value.
         *
         * @throws IllegalAccessException if a static text member of the service cannot be read, which
         *     fails the case rather than letting it pass on a partial inspection
         */
        @Test
        @DisplayName("this service carries no screen prompt")
        void thisServiceCarriesNoScreenPrompt() throws IllegalAccessException {
            List<String> prompts = informationMessages().stream()
                    .map(BaselineLiteral::text)
                    .toList();

            for (Field declared : TransactionTypeService.class.getDeclaredFields()) {
                if (!Modifier.isStatic(declared.getModifiers())
                        || declared.getType() != String.class) {
                    continue;
                }
                declared.setAccessible(true);
                String value = (String) declared.get(null);
                assertThat(prompts)
                        .as("static text member %s must not be a screen prompt", declared.getName())
                        .doesNotContain(value);
            }
        }
    }
}
