package com.carddemo.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.dto.DatasetGeneration.GenerationReference;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

/**
 * Pins the rulings the generation-data-group reference carried, on the service that replaces it.
 *
 * <p>Purpose: the reference baseline names a dataset generation relatively. A {@code DSN=} operand
 * appends {@code (+1)} to name the generation the job is creating and {@code (0)} to name the one that
 * already exists, and the catalog resolves the notation once per job. {@link DatasetGenerationService}
 * performs that resolution on the target, so the rulings the catalog used to enforce now need pinning
 * somewhere, and this is where. Each case below drives the service and names the job line it settles.</p>
 *
 * <p>Assumptions: the sibling {@code BatchServicesTest.GenerationDiscipline} already drives the two PURE
 * decisions this service exposes, {@code nextGeneration} and {@code generationsToScratch}, both of which
 * take the existing generations as an argument and perform no input or output. Neither is re-driven here.
 * This class takes the complementary surface: the retention constant, the allocating operation and its
 * memoisation, the current-generation operation, the location accessor, and the family roster. Splitting
 * it that way keeps one subject's cases in one place instead of two, and the boundary is the presence of
 * a listing rather than an arbitrary division.</p>
 *
 * <p>Assumptions: every input here is constructed in code because this module has no fixture directory to
 * load one from. Measured at this revision {@code services/batch-service/src/test/resources} holds
 * exactly one file, the test profile, and no {@code fixtures} subtree beneath it -- so there is no
 * {@code dataset} folder, and none was intended. A reader looking for a committed byte image will not
 * find one, and the answer is a constructor and a private helper rather than a new directory.</p>
 *
 * <p>Trade-offs: plain JUnit with a stubbed object store is used rather than an application context. Every
 * ruling below is a decision over the service's own arguments and its own memoisation table, so a context
 * would prove no ruling that this shape does not, while making each case pay for a context start. Wiring
 * is observable where it can actually be observed, and the job and repository tiers prove it there.</p>
 *
 * <p>Trade-offs: the object store is a stub rather than an emulator. This tier settles which generation
 * a step addresses -- a prefix resolution and a memoisation -- and not whether an object can be written,
 * so an emulator would add a process to start and a network to reach for no ruling gained. Object-storage
 * behaviour belongs to the tier that runs against one.</p>
 *
 * <p>Assumptions: the stub intercepts the LISTING entry point and returns a real paginator over itself,
 * rather than stubbing the paginator's {@code commonPrefixes} accessor. That accessor is declared final on
 * the software development kit's paginator, so stubbing it would work only while the mocking framework's
 * final-method support stays configured as it is today. Returning a real paginator over the stubbed client
 * runs the kit's own pagination for real and depends on no such configuration.</p>
 *
 * <p>Assumptions: no case here asserts a transaction boundary. The production service package opens none
 * -- its charter fixes that no method in it carries a transaction annotation, so a rule there participates
 * in whatever unit of work its caller already opened. There is nothing to observe, so an assertion about
 * commit or rollback would be asserting a caller's behaviour through a subject that has no say in it.</p>
 *
 * <p>Assumptions: no case here asserts a literal bucket name. The bucket, the ten prefix families and the
 * lifecycle configuration that enforces retention are provisioned by {@code infra/modules/s3-datasets},
 * and the service holds the bucket only as a configured value. The cases therefore assert the SHAPE of a
 * location and that the CONFIGURED value is the one used, which is what the service is answerable for.</p>
 *
 * <p>Assumptions: no case here re-asserts the rendering that {@link DatasetGeneration} owns. That record
 * declares the family roster, both relative forms, the derivation of the partition-date and generation
 * segments and the composition of a key prefix, and its own test settles them. Restating any of them here
 * would create a second declaration of one contract, which is how two declarations come to disagree, so
 * these cases assert only that the service DELEGATES to it.</p>
 *
 * <p>Assumptions: every citation below is provenance. Nothing under {@code app/**} is read at run time and
 * nothing under it is altered by this migration -- the reference implementation is the behavioural oracle
 * and stays byte-identical. Line numbers refer to the source as committed, and in a job line columns 73 to
 * 80 carry a sequence field that is not part of the statement.</p>
 */
@DisplayName("the dataset generation resolver")
class DatasetGenerationServiceTest {

    /**
     * The bucket value the cases configure the service with.
     *
     * <p>Assumptions: the value is deliberately a description of its own provenance rather than anything
     * resembling a real bucket. The migration plan makes committing no endpoint a non-negotiable
     * constraint, and a plausible-looking name in a test is how a name that was never real comes to be
     * copied somewhere it has to be. Nothing about these cases depends on what the string says: the
     * location cases assert that whatever is configured is what appears, and one of them configures a
     * second, different value precisely to prove the accessor reads configuration rather than a
     * constant.</p>
     */
    private static final String CONFIGURED_BUCKET = "bucket-supplied-by-configuration";

    /**
     * A second, different configured bucket, used to prove the location accessor reads configuration.
     *
     * <p>Assumptions: two values are needed rather than one because a single value cannot distinguish a
     * configured bucket from a hard-coded one -- both render the same location. Only a second instance
     * configured differently makes the dependency observable.</p>
     *
     * <p>Assumptions: neither value is a substring of the other, and that is a requirement rather than an
     * accident of naming. Prefixing this one with a qualifier -- naming it as a second form of the value
     * above -- would embed that value inside this one, and a location carrying only this bucket would then
     * satisfy a search for the other. Any assertion distinguishing the two by substring would report the
     * two buckets as indistinguishable while the accessor was working correctly.</p>
     */
    private static final String OTHER_CONFIGURED_BUCKET = "other-value-supplied-by-configuration";

    /**
     * The orchestrator execution identifier the cases allocate under.
     *
     * <p>Assumptions: the identifier stands in for the state machine execution name the batch task
     * receives, and it is the run half of the service's memoisation key. Its content is immaterial; that
     * two DIFFERENT identifiers key different allocations is what one case below settles.</p>
     */
    private static final String RUN_ID = "batch-run-0001";

    /**
     * A second execution identifier, used to prove an allocation does not outlive its run.
     *
     * <p>Assumptions: a distinct value is required because the memoisation key is the run paired with the
     * family, so re-allocating under the same identifier could only ever return the memoised answer and
     * would settle nothing about the key's run half.</p>
     */
    private static final String OTHER_RUN_ID = "batch-run-0002";

    /**
     * The injected business date every case partitions under.
     *
     * <p>Assumptions: the date is supplied as a constructed value and never read from a clock, which is
     * the property that makes a rerun produce identical output. The reference baseline injects it the same
     * way -- {@code app/jcl/INTCALC.jcl:22} passes {@code PARM='2022071800'} to the interest step rather
     * than letting the program read the date itself.</p>
     *
     * <p>Assumptions: the separated ten-character layout is used rather than the compact layout that
     * production parameter carries. Both resolve to the same partition segment, and which layouts resolve
     * how is a ruling {@link DatasetGeneration} owns and its own test settles, so choosing the layout that
     * needs no resolution keeps these cases about the service.</p>
     */
    private static final BusinessDate BUSINESS_DATE = new BusinessDate("2022-07-18");

    /**
     * Builds a stubbed object store whose listing reports exactly the supplied generations as present.
     *
     * <p>Assumptions: the listing is answered from the REQUESTED prefix rather than returned wholesale, so
     * a store staged with generations of several families answers each family's listing with only its own.
     * Returning every staged prefix to every listing would let one family's generations raise another
     * family's next number, which would quietly invalidate the case that proves the two are independent --
     * it would still pass, while asserting the opposite of what it claims.</p>
     *
     * <p>Assumptions: the paginator returned is a REAL one constructed over this same stub, so the
     * kit's own pagination and its final {@code commonPrefixes} accessor execute for real and only the
     * request-response boundary is stubbed. The single page is marked not truncated so the paginator
     * terminates after it.</p>
     *
     * @param staged the generations the store is to report as already present, in any order and across any
     *     mix of families; an empty list stages a store holding no generation at all
     * @return the stubbed object store, never {@code null}
     */
    private static S3Client objectStoreHolding(List<DatasetGeneration> staged) {
        S3Client objectStore = mock(S3Client.class);

        when(objectStore.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
                .thenAnswer(call -> new ListObjectsV2Iterable(objectStore, call.getArgument(0)));

        when(objectStore.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(call -> {
            String requestedPrefix = call.<ListObjectsV2Request>getArgument(0).prefix();

            // WHY : Assumptions: the child prefixes are rendered by the coordinate itself rather than
            //       spelled here. A generation's key prefix IS its partition prefix followed by the
            //       generation segment and a separator, so a staged coordinate's own rendering is exactly
            //       the child a real listing would return for it. Spelling the markers here instead would
            //       put a second declaration of the segment convention in a test, and the service would
            //       then be read back through a convention this file asserted rather than the one the
            //       record publishes.
            List<CommonPrefix> children = staged.stream()
                    .map(DatasetGeneration::keyPrefix)
                    .filter(childPrefix -> childPrefix.startsWith(requestedPrefix))
                    .map(childPrefix -> CommonPrefix.builder().prefix(childPrefix).build())
                    .toList();

            return ListObjectsV2Response.builder()
                    .isTruncated(false)
                    .commonPrefixes(children)
                    .build();
        });

        return objectStore;
    }

    /**
     * Builds one coordinate for a family under the shared business date.
     *
     * @param family the generation-dataset family the coordinate belongs to
     * @param generationNumber the generation number within that family and date
     * @return the coordinate, never {@code null}
     */
    private static DatasetGeneration generation(DatasetFamily family, int generationNumber) {
        return new DatasetGeneration(family, BUSINESS_DATE, generationNumber);
    }

    /**
     * Builds a service over a store holding the supplied generations.
     *
     * <p>Assumptions: a fresh service is built per case rather than shared, because the memoisation table
     * is per instance and an instance shared across cases would carry one case's allocations into the
     * next. That is the same reason one case below builds a SECOND instance deliberately.</p>
     *
     * @param objectStore the stubbed store the service lists generations through
     * @return the service under test, never {@code null}
     */
    private static DatasetGenerationService serviceOver(S3Client objectStore) {
        return new DatasetGenerationService(objectStore, CONFIGURED_BUCKET);
    }

    /**
     * Settles the retention count the ten generation bases declare.
     *
     * <p>Purpose: the count is the one number in this subject that the reference baseline states twice and
     * states differently, so it is the one number a later reader is most likely to change without knowing
     * what the change contradicts. These two cases fix it and record the contradiction.</p>
     */
    @Nested
    @DisplayName("the retention count")
    class RetentionCount {

        /**
         * The retention count is five, which is the limit the ten canonical base definitions declare.
         *
         * <p>Pins {@code app/jcl/DEFGDGB.jcl:38}, the canonical limit of the report base, together with
         * the nine sibling limits at {@code app/jcl/DEFGDGB.jcl:26}, {@code :32}, {@code :44},
         * {@code :50} and {@code :56}, {@code app/jcl/DEFGDGD.jcl:29}, {@code :52} and {@code :75}, and
         * {@code app/jcl/DALYREJS.jcl:26}. Each of the ten pairs its limit with {@code SCRATCH} on the
         * following line, which is what makes an aged-out generation deleted rather than merely
         * uncatalogued.</p>
         *
         * <p>Alternatives Considered: ten was the other candidate, and it is not hypothetical -- it is
         * written in the baseline. The report family is defined TWICE with two different limits:
         * {@code app/jcl/DEFGDGB.jcl:37-38} names it at {@code LIMIT(5)}, and
         * {@code app/jcl/REPTFILE.jcl:25-27} names the same base again at {@code LIMIT(10)}. That second
         * definition is not an eleventh family; the distinct-base count is still exactly ten. Five is
         * adopted for three reasons. {@code app/jcl/DEFGDGB.jcl:19} states that job's own scope as
         * defining the generation bases the project needs, and it declares the report base alongside five
         * siblings, whereas {@code app/jcl/REPTFILE.jcl} is a single-purpose job declaring that one base
         * on its own. The two definitions differ in a second respect that points the same way: the
         * canonical one pairs its limit with {@code SCRATCH} at {@code :39} and tolerates an
         * already-exists condition at {@code :41}, while the outlier does neither. And the migration plan
         * fixes five noncurrent versions as the target retention, which
         * {@code infra/modules/s3-datasets} configures, so five is the count a step and the bucket both
         * prune on rather than two counts that happen to agree. The outlier is recorded rather than
         * resolved: the baseline states the limit twice and differently, and it stays exactly as
         * committed.</p>
         *
         * <p>Assumptions: the literal five is written HERE and only here. Every other case in this file
         * that needs the count refers to the constant, so a change to the rule breaks one assertion rather
         * than several that would each have to be found.</p>
         */
        @Test
        @DisplayName("retain five generations, the canonical limit rather than the outlier ten")
        void retentionCountIsFive() {
            assertThat(DatasetGenerationService.GDG_GENERATION_LIMIT).isEqualTo(5);
        }

        /**
         * The count is one declaration reached by two names, not two literals that could drift apart.
         *
         * <p>Pins the uniformity of {@code app/jcl/DEFGDGB.jcl:26} through {@code :56},
         * {@code app/jcl/DEFGDGD.jcl:29} through {@code :75} and {@code app/jcl/DALYREJS.jcl:26}: all ten
         * canonical definitions carry the same limit, so the migrated rule is one value.</p>
         *
         * <p>Trade-offs: a map from family to limit was the alternative, and it would model a
         * per-family rule faithfully if one existed. It does not -- all ten canonical definitions agree --
         * so a map would offer ten places for a value to be edited independently while nothing in the
         * baseline justified any of them differing. The cost accepted by a single constant is that
         * honouring a genuinely per-family limit later means changing a type rather than a table entry,
         * and the case above records where the one conflicting definition would have to be revisited if
         * that day comes.</p>
         *
         * <p>Assumptions: the service publishes the count under the generation-data-group name a reader
         * auditing the baseline arrives with, while the record publishes the same number under the name
         * the object-store lifecycle rule uses. Asserting that the two are the same value is what makes
         * "one number, two vocabularies" checkable rather than merely stated.</p>
         */
        @Test
        @DisplayName("publish one declaration of the count under two names")
        void retentionCountIsASingleDeclaration() {
            assertThat(DatasetGenerationService.GDG_GENERATION_LIMIT)
                    .isEqualTo(DatasetGeneration.RETAINED_GENERATION_COUNT);
        }
    }

    /**
     * Settles that an allocation happens once per run and family, which is why this service exists.
     *
     * <p>Purpose: these cases fix the identity the allocating operation exists to provide, in both
     * directions -- that a repeat request does not allocate again, and that a distinct family is not
     * suppressed by one that was already allocated.</p>
     *
     * <p>Assumptions: the reference baseline resolves every {@code (+1)} reference to one base within a
     * single job to the SAME newly created generation, and four jobs depend on it. In each of the four, the
     * first reference creates the generation and a later step of the same job reads it back through the
     * identical spelling: {@code app/jcl/COMBTRAN.jcl:37} then {@code :44},
     * {@code app/jcl/TRANREPT.jcl:33} then {@code :39}, {@code app/jcl/TRANREPT.jcl:55} then {@code :66},
     * and {@code app/jcl/PRTCATBL.jcl:39} then {@code :45}. Allocating on every call instead would hand
     * the reading step a second, empty prefix, write two generations where the reference writes one, and
     * consume the retention window at twice the intended rate.</p>
     *
     * <p>Assumptions: the identity is scoped to ONE job and to ONE base. It is scoped to one job because
     * each of those four pairings is a single submission, and to one base because
     * {@code app/jcl/TRANREPT.jcl} allocates two different families in one job -- {@code TRANSACT.BKUP} at
     * line 33 and {@code TRANSACT.DALY} at line 55 -- and each keeps its own generation.</p>
     *
     * <p>Assumptions: an allocation is observed to have HAPPENED by the listing it performs, not by the
     * coordinate it returns. A memoised call and a fresh call over an unchanged store return coordinates
     * that compare equal, so the returned value cannot distinguish them and an assertion resting on it
     * would pass whether the memoisation worked or not. The listing count can distinguish them, so the
     * cases below assert the returned coordinate for the CONTRACT and the listing count for the
     * MECHANISM.</p>
     */
    @Nested
    @DisplayName("allocating the generation a run writes")
    class AllocationMemoisation {

        /**
         * A repeat request for the same family in the same run returns the identical generation.
         *
         * <p>Pins {@code app/jcl/COMBTRAN.jcl:37} and {@code :44}, where the combine job names
         * {@code TRANSACT.COMBINED(+1)} twice -- writing it as the sort output and then reading it back as
         * the input of the load step that follows. Without this identity the load step would read a prefix
         * the sort never wrote and the combine would report success over an empty input.</p>
         *
         * <p>Assumptions: the whole coordinate is compared rather than its number alone. Two coordinates
         * can share a number and differ in family or date, so a number-only comparison would accept an
         * answer for the wrong partition.</p>
         */
        @Test
        @DisplayName("return the identical generation on a repeat request in one run")
        void repeatRequestForOneFamilyReturnsTheSameGeneration() {
            S3Client objectStore = objectStoreHolding(
                    List.of(generation(DatasetFamily.TRANSACT_COMBINED, 3)));
            DatasetGenerationService service = serviceOver(objectStore);

            DatasetGeneration first = service.allocateNewGeneration(
                    DatasetFamily.TRANSACT_COMBINED, BUSINESS_DATE, RUN_ID);
            DatasetGeneration second = service.allocateNewGeneration(
                    DatasetFamily.TRANSACT_COMBINED, BUSINESS_DATE, RUN_ID);

            assertThat(second).isEqualTo(first);
            assertThat(second.generationNumber()).isEqualTo(4);

            // WHY : Assumptions: one listing across two calls is the observable proof that the second call
            //       allocated nothing. The equality above is the contract the combine job depends on, but
            //       it would hold just as well if the second call had re-derived the same answer from an
            //       unchanged store, so it cannot by itself distinguish a memoised answer from a
            //       recomputed one.
            verify(objectStore, times(1)).listObjectsV2Paginator(any(ListObjectsV2Request.class));
        }

        /**
         * Two different families allocate independently rather than sharing one memoised generation.
         *
         * <p>Pins {@code app/jcl/TRANREPT.jcl:33} and {@code :55}, where one job allocates
         * {@code TRANSACT.BKUP(+1)} and {@code TRANSACT.DALY(+1)} as two separate generations, each read
         * back later in that same job at {@code :39} and {@code :66} respectively.</p>
         *
         * <p>Assumptions: memoising GLOBALLY rather than per family is the mirror-image fault to omitting
         * the memoisation, and it is equally damaging: the second family would receive the first family's
         * coordinate and the job would write both datasets to one prefix. The two families are staged at
         * DIFFERENT existing generations so that the fault cannot hide -- a globally memoised answer would
         * carry the first family and its number, and both are asserted against.</p>
         */
        @Test
        @DisplayName("allocate independently for two different families in one run")
        void differentFamiliesAllocateIndependently() {
            S3Client objectStore = objectStoreHolding(List.of(
                    generation(DatasetFamily.TRANSACT_BKUP, 3),
                    generation(DatasetFamily.TRANSACT_DALY, 7)));
            DatasetGenerationService service = serviceOver(objectStore);

            DatasetGeneration backup = service.allocateNewGeneration(
                    DatasetFamily.TRANSACT_BKUP, BUSINESS_DATE, RUN_ID);
            DatasetGeneration daily = service.allocateNewGeneration(
                    DatasetFamily.TRANSACT_DALY, BUSINESS_DATE, RUN_ID);

            assertThat(backup.family()).isEqualTo(DatasetFamily.TRANSACT_BKUP);
            assertThat(backup.generationNumber()).isEqualTo(4);
            assertThat(daily.family()).isEqualTo(DatasetFamily.TRANSACT_DALY);
            assertThat(daily.generationNumber()).isEqualTo(8);
            assertThat(daily).isNotEqualTo(backup);

            // WHY : Assumptions: two listings prove neither allocation was suppressed by the other. A
            //       global memo would satisfy its key on the second call, skip the listing, and leave this
            //       count at one, so the count discriminates the fault in the direction the assertions
            //       above cannot reach on their own.
            verify(objectStore, times(2)).listObjectsV2Paginator(any(ListObjectsV2Request.class));
        }

        /**
         * A different run allocates afresh, because the memoised identity belongs to one run.
         *
         * <p>Pins the scope of the identity established at {@code app/jcl/COMBTRAN.jcl:37} and {@code :44}:
         * it holds WITHIN one job. Two submissions of that job are two jobs, and the second is entitled to
         * its own generation rather than the one the first created.</p>
         *
         * <p>Assumptions: the coordinates compare equal here because the staged store is unchanged between
         * the two calls, so once again only the listing count shows that the second call allocated rather
         * than answered from the table. Asserting inequality of the coordinates would be asserting
         * something the service does not promise -- it allocates one past the highest PRESENT generation,
         * and nothing in this case makes a generation present.</p>
         */
        @Test
        @DisplayName("allocate afresh for a different run")
        void differentRunAllocatesAfresh() {
            S3Client objectStore = objectStoreHolding(
                    List.of(generation(DatasetFamily.SYSTRAN, 1)));
            DatasetGenerationService service = serviceOver(objectStore);

            service.allocateNewGeneration(DatasetFamily.SYSTRAN, BUSINESS_DATE, RUN_ID);
            service.allocateNewGeneration(DatasetFamily.SYSTRAN, BUSINESS_DATE, OTHER_RUN_ID);

            verify(objectStore, times(2)).listObjectsV2Paginator(any(ListObjectsV2Request.class));
        }

        /**
         * A fresh service instance allocates afresh, because the table does not outlive the run.
         *
         * <p>Pins the same one-job scope as {@code app/jcl/COMBTRAN.jcl:37} and {@code :44}. A batch task
         * is one run, so a new instance is a new run and inherits no allocation from a previous one.</p>
         *
         * <p>Assumptions: a second instance over the SAME stubbed store is what makes the leak observable.
         * Sharing the store keeps the listing count cumulative across both instances, so a table that
         * somehow survived instantiation would show as one listing rather than two.</p>
         */
        @Test
        @DisplayName("allocate afresh on a fresh instance")
        void freshInstanceAllocatesAfresh() {
            S3Client objectStore = objectStoreHolding(
                    List.of(generation(DatasetFamily.DALYREJS, 2)));

            serviceOver(objectStore)
                    .allocateNewGeneration(DatasetFamily.DALYREJS, BUSINESS_DATE, RUN_ID);
            serviceOver(objectStore)
                    .allocateNewGeneration(DatasetFamily.DALYREJS, BUSINESS_DATE, RUN_ID);

            verify(objectStore, times(2)).listObjectsV2Paginator(any(ListObjectsV2Request.class));
        }

        /**
         * A repeat allocation is non-fatal and provisions nothing, which is the tolerated-redefine analogue.
         *
         * <p>Pins {@code app/jcl/DEFGDGB.jcl:29}, {@code :35}, {@code :41}, {@code :47}, {@code :53} and
         * {@code :59}, each of which follows a base definition with a step that clears the condition code
         * so that an already-exists outcome is tolerated rather than failing the job.</p>
         *
         * <p>Assumptions: the service exposes no create-if-absent operation to test directly, because it
         * provisions nothing -- it creates no prefix and deletes no object, and the bucket and its prefixes
         * are provisioned by {@code infra/modules/s3-datasets}. The migrated form of the tolerated redefine
         * is therefore this: repeating the request neither raises nor writes. That is asserted both ways,
         * by driving the repeat and by pinning that no object was put, so the claim that the service
         * provisions nothing is checkable rather than only documented.</p>
         */
        @Test
        @DisplayName("tolerate a repeat request without provisioning anything")
        void repeatAllocationIsNonFatalAndProvisionsNothing() {
            S3Client objectStore = objectStoreHolding(
                    List.of(generation(DatasetFamily.TCATBALF_BKUP, 1)));
            DatasetGenerationService service = serviceOver(objectStore);

            DatasetGeneration first = service.allocateNewGeneration(
                    DatasetFamily.TCATBALF_BKUP, BUSINESS_DATE, RUN_ID);
            DatasetGeneration repeated = service.allocateNewGeneration(
                    DatasetFamily.TCATBALF_BKUP, BUSINESS_DATE, RUN_ID);

            assertThat(repeated).isEqualTo(first);
            verify(objectStore, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }
    }

    /**
     * Settles that reading the current generation is a distinct operation from allocating a new one.
     *
     * <p>Purpose: the current form is not a weaker allocation. It is read by exactly one flow, and it reads
     * datasets that flow did not produce: {@code app/jcl/COMBTRAN.jcl:24} names
     * {@code TRANSACT.BKUP(0)} and {@code :26} names {@code SYSTRAN(0)} as the two concatenated inputs of
     * one merge sort at {@code :22}. Those two inputs are the join point between the posting pipeline and
     * the interest pipeline, each written by an earlier job, so this form must find what already exists and
     * must not create anything or claim anything for the current run.</p>
     */
    @Nested
    @DisplayName("resolving the generation that already exists")
    class CurrentGenerationResolution {

        /**
         * Reading the current generation neither allocates nor claims the family for the run.
         *
         * <p>Pins {@code app/jcl/COMBTRAN.jcl:24}, where the combine job reads {@code TRANSACT.BKUP(0)} as
         * a sort input. Reading an input must not consume the family's next generation.</p>
         *
         * <p>Assumptions: the decisive assertion is the one AFTER the read. If reading had populated the
         * memoisation table, the following allocation would have answered from it and returned the
         * generation that already exists; it returns one past it instead, which is what proves the read left
         * the table untouched. Nothing else the read returns could show this.</p>
         */
        @Test
        @DisplayName("allocate nothing and leave the run's table untouched")
        void readingCurrentGenerationDoesNotAllocate() {
            S3Client objectStore = objectStoreHolding(
                    List.of(generation(DatasetFamily.TRANSACT_BKUP, 3)));
            DatasetGenerationService service = serviceOver(objectStore);

            Optional<DatasetGeneration> current = service.resolveCurrentGeneration(
                    DatasetFamily.TRANSACT_BKUP, BUSINESS_DATE);

            assertThat(current).isPresent();
            assertThat(current.orElseThrow().generationNumber()).isEqualTo(3);
            verify(objectStore, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));

            DatasetGeneration allocated = service.allocateNewGeneration(
                    DatasetFamily.TRANSACT_BKUP, BUSINESS_DATE, RUN_ID);

            assertThat(allocated.generationNumber()).isEqualTo(4);
            assertThat(allocated).isNotEqualTo(current.orElseThrow());
        }

        /**
         * The two relative forms yield different coordinates for one family.
         *
         * <p>Pins {@code app/jcl/COMBTRAN.jcl:26} against {@code :37}: the same job reads
         * {@code SYSTRAN(0)} and writes {@code TRANSACT.COMBINED(+1)}, and the notations are not
         * interchangeable. The record publishes both spellings, {@code (0)} and {@code (+1)}, and this case
         * fixes that the service answers them differently for one family and one date.</p>
         *
         * <p>Assumptions: the two forms are asserted to differ rather than to hold particular numbers,
         * because which number each carries is already settled by the cases above. What is settled here is
         * that resolving and allocating are not the same question.</p>
         */
        @Test
        @DisplayName("answer the current and the new form with different coordinates")
        void currentAndNewFormsDiffer() {
            S3Client objectStore = objectStoreHolding(
                    List.of(generation(DatasetFamily.SYSTRAN, 5)));
            DatasetGenerationService service = serviceOver(objectStore);

            DatasetGeneration current = service
                    .resolveCurrentGeneration(DatasetFamily.SYSTRAN, BUSINESS_DATE)
                    .orElseThrow();
            DatasetGeneration allocated = service.allocateNewGeneration(
                    DatasetFamily.SYSTRAN, BUSINESS_DATE, RUN_ID);

            assertThat(allocated).isNotEqualTo(current);
            assertThat(allocated.family()).isEqualTo(current.family());
            assertThat(allocated.businessDate()).isEqualTo(current.businessDate());

            // WHY : Assumptions: the two notations are asserted to be distinct as published values as well
            //       as distinct in effect. The record declares them, so if the two ever collapsed into one
            //       spelling the cases above would keep passing while the vocabulary they are written
            //       against had lost the distinction they rely on.
            assertThat(GenerationReference.NEW.jclNotation())
                    .isNotEqualTo(GenerationReference.CURRENT.jclNotation());
        }

        /**
         * A partition holding no generation is reported as absent rather than as the zeroth generation.
         *
         * <p>Pins the two reads at {@code app/jcl/COMBTRAN.jcl:24} and {@code :26}, which name inputs the
         * combine job did not produce. Answering an unwritten partition with a coordinate would send the
         * merge at a prefix nothing was ever staged under and yield an empty output indistinguishable from a
         * successful merge of empty inputs.</p>
         *
         * <p>Assumptions: absence and the zeroth generation are different answers because the record admits
         * a generation numbered zero as a real coordinate. That is why an empty result rather than a
         * substituted minimum is the correct report, and it is why this case asserts emptiness rather than
         * a number.</p>
         */
        @Test
        @DisplayName("report an unwritten partition as absent")
        void unwrittenPartitionIsReportedAbsent() {
            DatasetGenerationService service = serviceOver(objectStoreHolding(List.of()));

            assertThat(service.resolveCurrentGeneration(DatasetFamily.SYSTRAN, BUSINESS_DATE)).isEmpty();
        }
    }

    /**
     * Settles how a resolved coordinate becomes a location a step can be pointed at.
     *
     * <p>Purpose: a {@code DD DSN=} operand such as the one at {@code app/jcl/POSTTRAN.jcl:38} names a
     * dataset a step writes, and the target equivalent is an object-store location. The service holds the
     * one piece of that location the coordinate cannot carry -- the bucket -- and joins it to the prefix the
     * coordinate renders.</p>
     *
     * <p>Assumptions: the division of labour is the thing under test here, not the spelling. The coordinate
     * renders a prefix carrying no bucket and no scheme so that one value is valid unchanged in every
     * environment, and the service supplies exactly the scheme and the bucket. These cases assert that
     * split holds and assert nothing the coordinate's own test already settles.</p>
     */
    @Nested
    @DisplayName("composing the location of a generation")
    class LocationComposition {

        /**
         * The location is the scheme, the configured bucket and the coordinate's own prefix.
         *
         * <p>Pins the operand shape at {@code app/jcl/POSTTRAN.jcl:38}, which names
         * {@code DALYREJS(+1)} as the reject stream the posting step creates. Its target equivalent is a
         * prefix under the dataset bucket, ordered domain then dataset then partition date then
         * generation.</p>
         *
         * <p>Assumptions: the expected value is composed from the coordinate's own rendering rather than
         * written out, so this case asserts DELEGATION and not the segment convention. Writing the segments
         * out would restate a contract the record owns and its own test settles, and the two statements
         * could then disagree while both passed.</p>
         */
        @Test
        @DisplayName("join the scheme and the configured bucket to the coordinate's prefix")
        void locationJoinsSchemeBucketAndPrefix() {
            DatasetGenerationService service = serviceOver(objectStoreHolding(List.of()));
            DatasetGeneration allocated = service.allocateNewGeneration(
                    DatasetFamily.DALYREJS, BUSINESS_DATE, RUN_ID);

            assertThat(service.datasetUri(allocated))
                    .isEqualTo("s3://" + CONFIGURED_BUCKET + "/" + allocated.keyPrefix())
                    .startsWith("s3://" + CONFIGURED_BUCKET + "/")
                    .endsWith("/");
        }

        /**
         * The bucket in the location is the configured one, not a value hard-coded in the service.
         *
         * <p>Pins the same operand shape at {@code app/jcl/POSTTRAN.jcl:38}. The baseline names a dataset
         * through a catalog the job never spells; the target names it through a bucket the service never
         * spells either, and the migration plan makes committing no endpoint a non-negotiable
         * constraint.</p>
         *
         * <p>Assumptions: two instances configured DIFFERENTLY are what make the dependency observable. A
         * single instance cannot show it -- whatever value appeared could equally have come from a constant
         * inside the service -- so this case configures a second bucket and requires the location to follow
         * it. No real bucket name appears in either value.</p>
         *
         * <p>Alternatives Considered: asserting that each location CONTAINS its own bucket and does not
         * contain the other. It was declined because a containment pair is only sound while neither value is
         * a substring of the other, which makes the case's correctness rest on how two constants happen to be
         * spelled rather than on what the accessor does. Comparing each location against the whole value it
         * should equal is independent of that spelling and is the stronger statement anyway: it fixes where
         * the bucket appears in the location, not merely that it appears somewhere in it.</p>
         */
        @Test
        @DisplayName("take the bucket from configuration rather than from a hard-coded value")
        void locationTakesTheBucketFromConfiguration() {
            S3Client objectStore = objectStoreHolding(List.of());
            DatasetGeneration coordinate = generation(
                    DatasetFamily.TRANREPT, DatasetGeneration.MINIMUM_GENERATION_NUMBER);

            String configured = serviceOver(objectStore).datasetUri(coordinate);
            String otherwiseConfigured = new DatasetGenerationService(objectStore, OTHER_CONFIGURED_BUCKET)
                    .datasetUri(coordinate);

            assertThat(configured)
                    .isEqualTo("s3://" + CONFIGURED_BUCKET + "/" + coordinate.keyPrefix());
            assertThat(otherwiseConfigured)
                    .isEqualTo("s3://" + OTHER_CONFIGURED_BUCKET + "/" + coordinate.keyPrefix());
            assertThat(configured).isNotEqualTo(otherwiseConfigured);
        }

        /**
         * The partition-date and generation segments in the location are the coordinate's own.
         *
         * <p>Pins the business date injected at {@code app/jcl/INTCALC.jcl:22} as the value the partition
         * segment derives from, and the generation reference at {@code app/jcl/INTCALC.jcl:41}, which names
         * {@code SYSTRAN(+1)} as the step's system-transaction output.</p>
         *
         * <p>Assumptions: the two segments are asserted to be PRESENT AS RENDERED BY THE COORDINATE, and
         * neither the date layout nor the generation's padded width is asserted here. Both derivations
         * belong to the record and are settled by its own test; the ruling that belongs to this class is
         * only that the service passes them through rather than re-deriving them, because a service that
         * re-derived them would be a second declaration of a convention that has to match a prefix another
         * component writes.</p>
         */
        @Test
        @DisplayName("carry the coordinate's own date and generation segments")
        void locationDelegatesSegmentRendering() {
            DatasetGenerationService service = serviceOver(objectStoreHolding(List.of()));
            DatasetGeneration allocated = service.allocateNewGeneration(
                    DatasetFamily.SYSTRAN, BUSINESS_DATE, RUN_ID);

            assertThat(service.datasetUri(allocated))
                    .contains(allocated.datePartitionSegment())
                    .endsWith(allocated.generationSegment() + "/");
        }
    }

    /**
     * Settles that every one of the ten generation bases is addressable through the service.
     *
     * <p>Purpose: the count is easy to get wrong because the ten are declared across three jobs and the
     * first of the three reads as though it were the whole inventory. Six are defined in
     * {@code app/jcl/DEFGDGB.jcl}, whose base names are at lines 25, 31, 37, 43, 49 and 55; three more in
     * {@code app/jcl/DEFGDGD.jcl} at lines 28, 51 and 74; and the tenth in
     * {@code app/jcl/DALYREJS.jcl} at line 25. A case that drives all ten is the cheapest available guard
     * against one being dropped.</p>
     *
     * <p>Assumptions: each citation anchors on the base's name line rather than on the defining verb one
     * line earlier. Both are defensible and only a consistent choice makes the ten comparable, so a reader
     * checking line 24 of {@code app/jcl/DALYREJS.jcl} finds the verb while line 25 carries the name.</p>
     */
    @Nested
    @DisplayName("the roster of ten generation bases")
    class FamilyRoster {

        /**
         * All ten bases allocate through the service and land on ten distinct locations.
         *
         * <p>Pins the ten base definitions at {@code app/jcl/DEFGDGB.jcl:25}, {@code :31}, {@code :37},
         * {@code :43}, {@code :49} and {@code :55}, {@code app/jcl/DEFGDGD.jcl:28}, {@code :51} and
         * {@code :74}, and {@code app/jcl/DALYREJS.jcl:25}.</p>
         *
         * <p>Assumptions: distinctness of the ten locations is asserted as well as the count, because a
         * count alone would not catch two families that resolved to one prefix. Two families sharing a
         * prefix would have one overwrite the other's generations while every count still read ten, and a
         * retention rule would then prune a family on another family's activity.</p>
         *
         * <p>Assumptions: the store is staged empty so that every family allocates its family's first
         * generation, which isolates the family portion of each location as the only part that varies. That
         * is precisely the part a dropped or duplicated family would disturb.</p>
         */
        @Test
        @DisplayName("allocate for all ten bases and land on ten distinct locations")
        void allTenBasesAllocateToDistinctLocations() {
            DatasetGenerationService service = serviceOver(objectStoreHolding(List.of()));

            List<String> locations = Arrays.stream(DatasetFamily.values())
                    .map(family -> service.datasetUri(
                            service.allocateNewGeneration(family, BUSINESS_DATE, RUN_ID)))
                    .toList();

            assertThat(DatasetFamily.values()).hasSize(10);
            assertThat(locations).hasSize(10).doesNotHaveDuplicates();
            assertThat(locations).allSatisfy(location ->
                    assertThat(location).startsWith("s3://" + CONFIGURED_BUCKET + "/").endsWith("/"));
        }

        /**
         * Every base is reachable from the dataset name the reference baseline spells.
         *
         * <p>Pins the base names themselves as committed at {@code app/jcl/DEFGDGB.jcl:25}, {@code :31},
         * {@code :37}, {@code :43}, {@code :49} and {@code :55}, {@code app/jcl/DEFGDGD.jcl:28},
         * {@code :51} and {@code :74}, and {@code app/jcl/DALYREJS.jcl:25}.</p>
         *
         * <p>Assumptions: the round trip is asserted through the record's own resolver rather than against a
         * list of names written here. A list written here would be an eleventh declaration of the roster and
         * would have to be maintained alongside it; asking each constant for its base name and requiring the
         * resolver to return that same constant proves the mapping is total and one-to-one without
         * restating a single name.</p>
         */
        @Test
        @DisplayName("resolve every base name back to its own family")
        void everyBaseNameResolvesToItsFamily() {
            assertThat(DatasetFamily.values()).allSatisfy(family ->
                    assertThat(DatasetFamily.resolveByMainframeBaseName(family.mainframeBaseName()))
                            .isEqualTo(family));
        }
    }
}
