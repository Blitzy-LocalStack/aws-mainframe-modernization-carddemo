package com.carddemo.batch.service;

import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

/**
 * Resolves a generation-dataset reference to the object-store location one batch step reads or writes.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: the reference baseline addresses a dataset generation relatively. A JCL {@code DSN=}
 * operand appends {@code (+1)} to name the generation the job is creating and {@code (0)} to name the
 * one that already exists, and the catalog resolves the notation to an absolute generation at job
 * submission. There is no catalog on the target, so the resolution has to happen somewhere, and this is
 * where. The migration plan's transformation rule T6 assigns {@code (+1)} to a new prefix and
 * {@code (0)} to the current one; this service performs that assignment, applies the retention
 * discipline the ten bases declare, and joins a rendered prefix to the configured bucket.</p>
 *
 * <p>This is the one class in this package that transcribes no COBOL paragraph, because its
 * specification is JCL rather than COBOL. Every citation below therefore names a job step rather than a
 * program paragraph.</p>
 *
 * <p>Refactoring Rationale: an earlier shape of this class carried only the two retention decisions,
 * {@link #nextGeneration} and {@link #generationsToScratch}, and both of those took the existing
 * generations as an argument. They are retained unchanged below, because a pure decision is worth having
 * separately, but on their own they could not answer the question a step actually asks. Nothing resolved
 * a relative reference: there was no caller-facing allocating operation, so no step could obtain the
 * generation it was to write; there was no current-generation operation, so the combine flow's two
 * {@code (0)} reads had no counterpart at all; there was no memoisation, so the four read-back sites
 * listed below had no way to address the generation their own job had created; and there was nothing that
 * knew the bucket, so a resolved coordinate could not be turned into a location. The three operations
 * added here supply exactly those four missing pieces, and the retention constant is published under the
 * generation-data-group name a reader auditing the baseline arrives with.</p>
 *
 * <h2>Ten bases, every one retaining five generations</h2>
 *
 * <p>Assumptions: the retention rule is uniform across all ten generation bases and is never a
 * per-family parameter, because each of the ten is defined with {@code LIMIT(5)} immediately followed by
 * {@code SCRATCH}. Six are defined in {@code app/jcl/DEFGDGB.jcl}, whose {@code NAME(} lines are 25, 31,
 * 37, 43, 49 and 55 with the limit on each following line and {@code SCRATCH} on the line after that;
 * three more in {@code app/jcl/DEFGDGD.jcl} at lines 28, 51 and 74; and the tenth in
 * {@code app/jcl/DALYREJS.jcl} at line 25. Ten is the count and six is the number a careful reader
 * arrives at, because the first of those three files is headed as though it were the whole inventory.
 * {@link DatasetFamily} declares one constant per base and is the authority on the set.</p>
 *
 * <h2>Both relative forms are required, and the inventory says why</h2>
 *
 * <p>Assumptions: implementing only the allocating form would leave the combine flow with nothing to
 * read. A search of the reference baseline finds seventeen relative generation references: fifteen
 * spelled {@code (+1)} and exactly two spelled {@code (0)}, the latter both in
 * {@code app/jcl/COMBTRAN.jcl}, at line 24 reading {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} and line 26
 * reading {@code AWS.M2.CARDDEMO.SYSTRAN(0)} as the two concatenated inputs of one merge sort. Those two
 * families each appear in BOTH forms across the tree -- the backup family is written at
 * {@code app/jcl/TRANBKP.jcl:33} and the system-transaction family at {@code app/jcl/INTCALC.jcl:41} --
 * so neither operation can be omitted on the grounds that a family only ever needs one of them.</p>
 *
 * <p>Assumptions: {@code (0)} is read only by the combine flow, and only for datasets that flow did not
 * itself produce. That is why the current form resolves against the object store rather than against
 * anything this process remembers: the generation it must find was written by an earlier task in the
 * chain, in a different container, whose in-process state is long gone.</p>
 *
 * <h2>The load-bearing ruling: an allocation happens once per run, not once per call</h2>
 *
 * <p>Assumptions: within one job, every {@code (+1)} reference to one base resolves to the SAME newly
 * allocated generation. Four jobs rely on that, each naming a family twice through the identical
 * spelling, and in every one of the four the first reference CREATES the generation and the second READS
 * it back in a later step of the same job:</p>
 *
 * <ul>
 *   <li>{@code app/jcl/COMBTRAN.jcl:37} writes {@code TRANSACT.COMBINED(+1)} as the sort output under
 *       {@code DISP=(NEW,CATLG,DELETE)}, and line 44 reads the same reference under {@code DISP=SHR} as
 *       the input of the load step that follows.</li>
 *   <li>{@code app/jcl/TRANREPT.jcl:33} writes {@code TRANSACT.BKUP(+1)}, and line 39 reads it under
 *       {@code DISP=SHR} as the sort input.</li>
 *   <li>{@code app/jcl/TRANREPT.jcl:55} writes {@code TRANSACT.DALY(+1)}, and line 66 reads it under
 *       {@code DISP=SHR} as the report program's transaction input.</li>
 *   <li>{@code app/jcl/PRTCATBL.jcl:39} writes {@code TCATBALF.BKUP(+1)}, and line 45 reads it under
 *       {@code DISP=SHR} as the sort input.</li>
 * </ul>
 *
 * <p>Trade-offs: the consequence is that an allocation is memoised for the duration of a run rather than
 * recomputed, and the cost of holding that state is accepted in exchange for the second reference
 * addressing the generation the first one created. {@link #allocateNewGeneration} documents the
 * alternative at the point the decision is made.</p>
 *
 * <h2>What this class does not own</h2>
 *
 * <p>Trade-offs: the family roster, the two relative forms, the derivation of the partition-date and
 * generation segments and the rendering of a key prefix all belong to
 * {@link com.carddemo.batch.dto.DatasetGeneration}, and none of them is restated here. The cost is one
 * more type in the call path of every method below; the benefit is that a family, a segment spelling or a
 * padding width cannot drift between two declarations, because there is only ever one. This class
 * decides WHICH generation a step addresses and never spells one.</p>
 *
 * <p>Assumptions: the bucket, the ten prefix families and the lifecycle configuration that enforces
 * retention are provisioned by {@code infra/modules/s3-datasets}. This class provisions nothing, creates
 * no prefix and deletes no object; it reports coordinates and leaves acting on them to its callers.</p>
 *
 * <h2>Baseline lineage: provenance only</h2>
 *
 * <p>Every citation in this file is provenance. Nothing under {@code app/**} is read at run time and
 * nothing under it is altered by this migration -- the reference implementation is the behavioural
 * oracle and stays byte-identical. Where migrated behaviour differs from the reference, the reference
 * does one thing, the Java does another, and the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. Line numbers refer to the source as
 * committed, and in a JCL line columns 73 to 80 carry a sequence field that is not part of the
 * statement.</p>
 */
@Service
public class DatasetGenerationService {

    /**
     * The number of generations a family retains, five.
     *
     * <p>Assumptions: five is the reference baseline's own limit and it is uniform. Every one of the ten
     * generation bases is defined {@code LIMIT(5)} with {@code SCRATCH} on the following line -- the six
     * in {@code app/jcl/DEFGDGB.jcl} whose limits are at lines 26, 32, 38, 44, 50 and 56, the three in
     * {@code app/jcl/DEFGDGD.jcl} at lines 29, 52 and 75, and the tenth in
     * {@code app/jcl/DALYREJS.jcl} at line 26. The pairing matters: the limit alone would leave an
     * aged-out generation uncatalogued, and {@code SCRATCH} is what makes it deleted. The target
     * analogue is bucket versioning with a lifecycle rule retaining five noncurrent object versions,
     * which {@code infra/modules/s3-datasets} configures per family, so a step and the bucket prune on
     * the same count rather than on two counts that happen to agree.</p>
     *
     * <p>Alternatives Considered: writing the literal five here, which is what the folder brief that
     * assigns this constant to this class spells. It was declined in favour of deriving the value from
     * {@link DatasetGeneration#RETAINED_GENERATION_COUNT}, because the sibling already declares the same
     * number with the same ten citations and two literals in two files can disagree silently while an
     * alias cannot. The value is unchanged and remains a compile-time constant, so
     * {@code {@value}} references and switch labels behave exactly as they would with a literal; what is
     * given up is only the ability to read the digit without following one reference.</p>
     *
     * <p>Assumptions: this constant is published under the name the generation-data-group vocabulary
     * uses because that is the name a reader auditing the baseline's retention arrives with, whereas the
     * sibling publishes the same number under the name the object-store lifecycle rule uses. One number,
     * two vocabularies, one declaration.</p>
     */
    public static final int GDG_GENERATION_LIMIT = DatasetGeneration.RETAINED_GENERATION_COUNT;

    /**
     * The configuration property naming the bucket that holds every staged dataset generation.
     *
     * <p>Assumptions: the value is a Terraform output and never a committed constant.
     * {@code infra/modules/step-functions-batch/main.tf} passes it into each batch task as the container
     * override environment entry {@code CARDDEMO_DATASET_BUCKET}, taken from the dataset module's own
     * bucket name, and the environment roots also publish it as the platform parameter
     * {@code /carddemo/<environment>/datasets/bucket}. The migration plan makes committing no endpoint
     * and no credential a non-negotiable constraint, so a default value would defeat a structural
     * guarantee rather than add a convenience.</p>
     *
     * <p>Assumptions: this property is deliberately absent from
     * {@code services/batch-service/src/main/resources/application.yml}, and the absence is the reason
     * the name is spelled in this relaxed form rather than in the service-qualified form a peer module
     * uses. That file states that no bucket is named in it, and the framework's environment binding
     * resolves this property directly from the {@code CARDDEMO_DATASET_BUCKET} variable the orchestrator
     * already injects, so nothing has to be added to that file for this property to resolve. Declaring
     * it there as well would give the task two sources for one value that a later edit could put into
     * disagreement.</p>
     */
    public static final String DATASET_BUCKET_PROPERTY = "carddemo.dataset.bucket";

    /** The operational log this class writes its resolution decisions to. */
    private static final Logger LOG = LoggerFactory.getLogger(DatasetGenerationService.class);

    /** The scheme and authority separator of an object-store location, {@code s3://}. */
    private static final String OBJECT_STORE_SCHEME = "s3://";

    /** The single character that separates one object-key segment from the next, a forward slash. */
    private static final String KEY_SEGMENT_SEPARATOR = "/";

    /**
     * The separator joining a run identifier to a family name in a memoisation key.
     *
     * <p>Assumptions: a non-printing character is used rather than a punctuation mark so that no run
     * identifier can contain it and thereby collide with a different run and family pair. The
     * orchestrator supplies the execution name, which is constrained to printable characters, so a
     * control character cannot occur inside one.</p>
     */
    private static final char MEMOISATION_KEY_SEPARATOR = '\u0000';

    /**
     * The value reported when a listed child prefix carries no readable generation number.
     *
     * <p>Assumptions: a negative sentinel is unambiguous because the lowest generation number the
     * coordinate admits is zero, so no readable value can collide with it. The alternative, reporting
     * absence through an optional, would box a primitive on every child of every listing to express a
     * condition the caller checks immediately with a range comparison it performs anyway.</p>
     */
    private static final int UNREADABLE_GENERATION_NUMBER = -1;

    /** The object-store client the generation listings are read through. */
    private final S3Client objectStore;

    /** The bucket every staged dataset generation lives in, supplied by configuration. */
    private final String datasetBucket;

    /**
     * The generation allocated for each run and family pair, so a repeat request returns the same one.
     *
     * <p>Assumptions: a concurrent map is used and the allocation goes through a single atomic
     * compute-if-absent call. A batch task runs one step at a time, so an ordinary map guarded by
     * nothing would be sufficient for the access pattern this class actually sees; the concurrent form
     * is chosen because the bean is a singleton and costs nothing here, which removes the need to rely
     * on that access pattern staying true.</p>
     */
    private final ConcurrentMap<String, DatasetGeneration> allocatedGenerations = new ConcurrentHashMap<>();

    /**
     * Builds the service over its object-store client and its configured bucket.
     *
     * @param objectStore the client the generation listings are read through; must not be {@code null}
     * @param datasetBucket the bucket holding every staged dataset generation, resolved from
     *     {@value #DATASET_BUCKET_PROPERTY}; must not be {@code null} or blank
     * @throws NullPointerException if {@code objectStore} or {@code datasetBucket} is {@code null}
     * @throws IllegalArgumentException if {@code datasetBucket} is blank
     */
    public DatasetGenerationService(
            S3Client objectStore,
            @Value("${" + DATASET_BUCKET_PROPERTY + "}") String datasetBucket) {

        this.objectStore = Objects.requireNonNull(objectStore, "objectStore must not be null");

        // WHAT: the bucket is rejected when blank, not merely when absent.
        // WHY : Assumptions: the property is supplied as an environment variable, and an environment
        //       variable that is exported with an empty value resolves successfully to the empty string
        //       rather than failing to resolve. Without this check the task would start, compose
        //       locations of the form s3:///ledger/... and fail on the first listing with a message
        //       about a malformed bucket rather than about the variable nobody set.
        this.datasetBucket = requireNonBlank(datasetBucket, "datasetBucket");
    }

    /**
     * Allocates the generation this run writes for one family, the analogue of a {@code (+1)} reference.
     *
     * <p>The generation is allocated at most once per run and family pair. A second call naming the same
     * family within the same run returns the identical coordinate rather than allocating a further
     * generation, which is what makes the four read-back sites cited on this class address the generation
     * their own job created.</p>
     *
     * @param family the generation-dataset family being written; must not be {@code null}
     * @param businessDate the injected business date the generation is partitioned under; must not be
     *     {@code null}
     * @param runId the orchestrator execution this allocation belongs to, which is the execution name
     *     the state machine passes as {@code CARDDEMO_BATCH_RUN_ID}; must not be {@code null} or blank
     * @return the allocated generation, never {@code null}, and identical across repeat calls for the
     *     same family and run
     * @throws NullPointerException if {@code family}, {@code businessDate} or {@code runId} is
     *     {@code null}
     * @throws IllegalArgumentException if {@code runId} is blank
     * @throws DatasetGenerationException if the existing generations of the family cannot be listed, or
     *     if the family already holds the highest representable generation for the business date
     */
    public DatasetGeneration allocateNewGeneration(
            DatasetFamily family, BusinessDate businessDate, String runId) {

        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        requireNonBlank(runId, "runId");

        // WHAT: one atomic compute-if-absent performs the allocation and the memoisation together.
        // WHY : Alternatives Considered: allocating on every call, which is the obvious reading of "(+1)
        //       means a new generation" and is wrong. Four jobs name one family twice through that same
        //       spelling and read back on the second reference -- COMBTRAN.jcl:37 then :44,
        //       PRTCATBL.jcl:39 then :45, TRANREPT.jcl:33 then :39, and TRANREPT.jcl:55 then :66 -- so
        //       per-call allocation would hand the reading step a second, empty prefix, write two
        //       generations where the reference writes one, and consume the five-generation retention
        //       window at twice the intended rate.
        // WHY : Alternatives Considered: a get followed by a put, which reads correctly and races. Two
        //       callers finding the key absent would both list, both allocate and both store, and the
        //       loser's coordinate would already have been handed out. Compute-if-absent evaluates the
        //       allocation at most once per key, so the invariant this memoisation exists to hold is
        //       enforced by the map rather than by call ordering.
        // WHY : Assumptions: a failure inside the allocation propagates and leaves no entry behind, so a
        //       run whose first attempt could not reach the object store retries cleanly instead of
        //       caching a half-formed answer.
        // WHY : Trade-offs: the allocation event is emitted INSIDE the mapping function and the
        //       resolution event outside it, rather than one event after the call. One event would report
        //       an allocation on a memoised call too, and an operator seeing the same family allocated
        //       twice in one run would read it as the memoisation having failed, which is the one failure
        //       this method exists to prevent. The cost is a log statement in a mapping function; it adds
        //       no contention worth counting, because the listing that function performs already holds
        //       the same bin for the duration of a network call.
        DatasetGeneration allocated = this.allocatedGenerations.computeIfAbsent(
                memoisationKey(runId, family),
                key -> {
                    DatasetGeneration fresh =
                            nextGeneration(family, businessDate, listGenerations(family, businessDate));
                    LOG.info("event=batch.generation.allocated runId={} family={} generation={} prefix={}",
                            runId, family.name(), fresh.generationNumber(), fresh.keyPrefix());
                    return fresh;
                });

        LOG.debug("event=batch.generation.resolved runId={} family={} generation={}",
                runId, family.name(), allocated.generationNumber());
        return allocated;
    }

    /**
     * Resolves the generation that already exists for one family, the analogue of a {@code (0)} reference.
     *
     * <p>Trade-offs: an absent generation is reported as an empty result rather than as the zeroth
     * generation or as an exception, and the distinction is load-bearing for the combine flow. That flow
     * is the only reader of this form -- {@code app/jcl/COMBTRAN.jcl:24} and {@code :26} -- and it merges
     * two inputs it did not produce, so "the partition holds nothing yet" and "the partition holds the
     * generation numbered zero" are different situations that must not answer alike: substituting the
     * zeroth would send the merge at an unwritten prefix and produce an empty output that looks like a
     * successful merge of empty inputs. An exception was the other candidate and was declined because a
     * caller that can legitimately proceed with one input present and one absent would then have to use
     * exception handling for a control decision. The cost is that every caller must unwrap the result and
     * decide, which is exactly the decision being surfaced.</p>
     *
     * @param family the generation-dataset family being read; must not be {@code null}
     * @param businessDate the business date whose partition is inspected; must not be {@code null}
     * @return the highest generation staged for that family and date, or an empty result when the
     *     partition holds none; never {@code null}
     * @throws NullPointerException if {@code family} or {@code businessDate} is {@code null}
     * @throws DatasetGenerationException if the existing generations of the family cannot be listed
     */
    public Optional<DatasetGeneration> resolveCurrentGeneration(
            DatasetFamily family, BusinessDate businessDate) {

        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");

        // WHY : Assumptions: the current generation is the highest-numbered one present, which holds
        //       because a generation is written once and never renumbered. The listing is already
        //       ordered, since the sibling pads the generation segment to a constant width precisely so
        //       that the object store's lexicographic ordering matches the numeric one, but the maximum
        //       is taken explicitly rather than by reading the last element: relying on the ordering
        //       would make this method's correctness depend on a property of a different class that
        //       nothing here asserts.
        Optional<DatasetGeneration> current = listGenerations(family, businessDate).stream()
                .max(Comparator.comparingInt(DatasetGeneration::generationNumber));

        if (current.isEmpty()) {
            LOG.info("event=batch.generation.absent family={} businessDate={}",
                    family.name(), businessDate.token());
        }

        return current;
    }

    /**
     * Returns the generation a step should write, one past the highest that already exists.
     *
     * <p>Assumptions: an empty family yields the minimum generation number rather than one past it, so
     * the first run of a family writes the same number a freshly defined base would carry.</p>
     *
     * <p>Assumptions: this decision takes the existing generations as an argument rather than reading
     * them, which is what lets the allocation above supply a listing while a test supplies a literal
     * list. It performs no input or output of any kind.</p>
     *
     * @param family the dataset family being written; must not be {@code null}
     * @param businessDate the injected business date the generation is partitioned under; must not be
     *     {@code null}
     * @param existing the generations already present for that family and date, in any order; must not be
     *     {@code null}
     * @return the generation to write, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws DatasetGenerationException if the highest present generation is already the highest the
     *     rendered width can represent, so no successor exists
     */
    public DatasetGeneration nextGeneration(DatasetFamily family, BusinessDate businessDate,
            List<DatasetGeneration> existing) {

        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(existing, "existing must not be null");

        int highest = existing.stream()
                .mapToInt(DatasetGeneration::generationNumber)
                .max()
                .orElse(DatasetGeneration.MINIMUM_GENERATION_NUMBER - 1);

        // WHAT: exhaustion of the generation range is reported here rather than left to the coordinate.
        // WHY : Trade-offs: the sibling's constructor already refuses an out-of-range number, so this
        //       check is not what makes the outcome safe -- it is what makes the outcome legible. A
        //       coordinate rejected there reports a number outside an accepted range, which reads like a
        //       caller passing nonsense; reported here it names the family and the date whose partition
        //       is full, which is the fact an operator has to act on. The accepted cost is one comparison
        //       that can never be the only line of defence.
        if (highest >= DatasetGeneration.MAXIMUM_GENERATION_NUMBER) {
            throw new DatasetGenerationException("dataset family " + family.mainframeBaseName()
                    + " already holds generation " + highest + " for business date "
                    + businessDate.token()
                    + ", which is the highest the rendered generation width can represent, so no further"
                    + " generation can be allocated under that partition");
        }

        return new DatasetGeneration(family, businessDate, highest + 1);
    }

    /**
     * Returns the generations the retention rule removes, newest-first order preserved among the rest.
     *
     * <p>Assumptions: the returned list is what a caller deletes, rather than what it keeps. A method
     * returning the survivors would leave the caller to compute the complement, and a caller computing a
     * complement of a retention rule is where an off-by-one deletes a generation that should have
     * stood.</p>
     *
     * <p>Assumptions: retention counts the newest {@value #GDG_GENERATION_LIMIT} generations as retained,
     * so the rule bites on the sixth-newest and older. That is what the baseline's limit paired with
     * {@code SCRATCH} means, and it is the same count the bucket's own lifecycle rule applies, so a step
     * calling this method and the bucket pruning on its own schedule agree rather than each acting on a
     * different window.</p>
     *
     * @param existing every generation currently present for one family and date, in any order; must not
     *     be {@code null}
     * @return the generations beyond the retained count, oldest first, never {@code null} and empty when
     *     the family holds no more than the retained count
     * @throws NullPointerException if {@code existing} is {@code null}
     */
    public List<DatasetGeneration> generationsToScratch(List<DatasetGeneration> existing) {
        Objects.requireNonNull(existing, "existing must not be null");

        List<DatasetGeneration> ordered = new ArrayList<>(existing);
        ordered.sort(Comparator.comparingInt(DatasetGeneration::generationNumber).reversed());

        // WHY : Assumptions: the newest generation is index zero after the reverse sort, and the retained
        //       window is the first GDG_GENERATION_LIMIT entries counted from it. Everything after that
        //       window is scratched, which is the sixth-newest and older.
        if (ordered.size() <= GDG_GENERATION_LIMIT) {
            return List.of();
        }

        List<DatasetGeneration> scratched =
                new ArrayList<>(ordered.subList(GDG_GENERATION_LIMIT, ordered.size()));
        scratched.sort(Comparator.comparingInt(DatasetGeneration::generationNumber));
        return List.copyOf(scratched);
    }

    /**
     * Renders the fully-qualified object-store location of one generation.
     *
     * <p>The value is the scheme, the configured bucket and the coordinate's own key prefix, and it ends
     * with a forward slash because it addresses a prefix that objects are placed beneath rather than the
     * key of an object.</p>
     *
     * @param generation the coordinate to locate; must not be {@code null}
     * @return the location, for example {@code s3://<bucket>/ledger/dalyrejs/} followed by the partition
     *     and generation segments; never {@code null}, and always terminated by a forward slash
     * @throws NullPointerException if {@code generation} is {@code null}
     * @throws IllegalStateException if the coordinate's business-date token matches neither layout its
     *     own renderer accepts
     */
    public String datasetUri(DatasetGeneration generation) {
        Objects.requireNonNull(generation, "generation must not be null");

        // WHY : Trade-offs: the scheme and the bucket are joined here and the rest is delegated, which is
        //       the split the sibling's rendering was designed around: it returns a prefix carrying no
        //       bucket, no environment and no scheme so that one coordinate value is valid unchanged in
        //       every environment and can be asserted in a test with no configuration at all. The cost of
        //       that split is exactly this join, and it belongs here because this class is the only one in
        //       the module that holds the bucket.
        return OBJECT_STORE_SCHEME + this.datasetBucket + KEY_SEGMENT_SEPARATOR + generation.keyPrefix();
    }

    /**
     * Lists the generations already staged for one family under one business date.
     *
     * <p>Assumptions: the listing is delimited, so the object store returns one common prefix per
     * generation instead of one entry per object. A generation holds an unbounded number of objects and
     * only its identity is wanted here, so an undelimited listing would transfer the whole partition's
     * key space to learn a handful of numbers.</p>
     *
     * @param family the family whose generations are listed; must not be {@code null}
     * @param businessDate the business date whose partition is listed; must not be {@code null}
     * @return every generation the partition holds, in no particular order, never {@code null} and empty
     *     when the partition holds none
     * @throws NullPointerException if {@code family} or {@code businessDate} is {@code null}
     * @throws DatasetGenerationException if the object store cannot be read
     */
    public List<DatasetGeneration> listGenerations(DatasetFamily family, BusinessDate businessDate) {
        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");

        String partitionPrefix = partitionKeyPrefix(family, businessDate);
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(this.datasetBucket)
                .prefix(partitionPrefix)
                .delimiter(KEY_SEGMENT_SEPARATOR)
                .build();

        List<DatasetGeneration> present = new ArrayList<>();
        try {
            // WHY : Trade-offs: the paginator's stream is taken inside a try-with-resources even though a
            //       synchronous paginator holds nothing that a close would release today. The alternative
            //       is a bare stream, which reads more simply and depends on that remaining true; the
            //       accepted cost of the extra block is that a step cannot pin a client resource for its
            //       whole duration if the paginator ever acquires one, and the data source in this module
            //       runs with leak detection precisely because a step is long enough for such a pin to
            //       matter.
            try (Stream<CommonPrefix> generationPrefixes =
                    this.objectStore.listObjectsV2Paginator(request).commonPrefixes().stream()) {

                generationPrefixes.forEach(candidate -> generationOfChildPrefix(
                        candidate.prefix(), partitionPrefix, family, businessDate).ifPresent(present::add));
            }
        } catch (SdkException failure) {
            // WHY : Alternatives Considered: letting the software development kit's own exception
            //       propagate, since it is already a runtime exception and the orchestrator's per-state
            //       retry would treat either the same. It was declined because that exception names an
            //       operation and a bucket and nothing else, so a failure would be reported without the
            //       family or the partition that was being read, and those are the two facts that identify
            //       which step of the nightly chain stopped. The cause is retained, so nothing the kit
            //       reported is lost.
            throw new DatasetGenerationException("could not list the generations of dataset family "
                    + family.mainframeBaseName() + " under prefix " + partitionPrefix
                    + " for business date " + businessDate.token(), failure);
        }

        return List.copyOf(present);
    }

    /**
     * Composes the key prefix of one family's partition for one business date.
     *
     * <p>Assumptions: both variable parts are rendered by the type that owns them and neither is spelled
     * here. The family portion comes from the family constant and already ends with a separator, and the
     * partition-date portion comes from a coordinate constructed purely to render it, so the only thing
     * this method contributes is the one separator that closes the date segment. Composing the segments
     * this way rather than spelling either marker keeps a single declaration of each, which is the whole
     * reason the rendering lives in the sibling.</p>
     *
     * @param family the family whose partition prefix is wanted; must not be {@code null}
     * @param businessDate the business date the partition is keyed by; must not be {@code null}
     * @return the partition prefix, always terminated by a forward slash, never {@code null}
     * @throws IllegalStateException if the business-date token matches neither layout the coordinate's own
     *     renderer accepts
     */
    private String partitionKeyPrefix(DatasetFamily family, BusinessDate businessDate) {
        // WHY : Assumptions: the coordinate built here is a rendering probe and is never returned or
        //       stored. The minimum generation number is used because the constructor requires a legal
        //       one and this coordinate's generation is irrelevant -- only its date segment is read.
        DatasetGeneration renderer =
                new DatasetGeneration(family, businessDate, DatasetGeneration.MINIMUM_GENERATION_NUMBER);

        return family.pathSegment() + renderer.datePartitionSegment() + KEY_SEGMENT_SEPARATOR;
    }

    /**
     * Interprets one listed child prefix as a generation of the family being listed.
     *
     * <p>Assumptions: a child is accepted only when the coordinate it appears to name renders back to
     * byte-identically the same segment. That round trip is what keeps the segment spelling owned by one
     * type: this method never matches a marker it declares itself, it asks the owning renderer whether it
     * would have produced the segment in question. A child that fails the round trip is something other
     * than a generation of this family -- a stray object placed under the partition, or a generation
     * written by a component using a different width -- and is skipped rather than guessed at, because a
     * misread generation number is indistinguishable from a real one once it has been returned.</p>
     *
     * @param childPrefix the full child prefix the listing returned, including the partition prefix and
     *     the trailing separator; must not be {@code null}
     * @param partitionPrefix the partition prefix the listing was made under; must not be {@code null}
     * @param family the family being listed; must not be {@code null}
     * @param businessDate the business date being listed; must not be {@code null}
     * @return the generation the child names, or an empty result when the child is not one this family's
     *     renderer would have produced; never {@code null}
     */
    private Optional<DatasetGeneration> generationOfChildPrefix(String childPrefix,
            String partitionPrefix, DatasetFamily family, BusinessDate businessDate) {

        if (!childPrefix.startsWith(partitionPrefix) || !childPrefix.endsWith(KEY_SEGMENT_SEPARATOR)) {
            return Optional.empty();
        }

        String segment = childPrefix.substring(
                partitionPrefix.length(), childPrefix.length() - KEY_SEGMENT_SEPARATOR.length());

        int candidateNumber = trailingDigits(segment);
        if (candidateNumber < DatasetGeneration.MINIMUM_GENERATION_NUMBER
                || candidateNumber > DatasetGeneration.MAXIMUM_GENERATION_NUMBER) {
            LOG.debug("event=batch.generation.skipped family={} childPrefix={} reason=unparseable",
                    family.name(), childPrefix);
            return Optional.empty();
        }

        DatasetGeneration candidate = new DatasetGeneration(family, businessDate, candidateNumber);
        if (!segment.equals(candidate.generationSegment())) {
            LOG.debug("event=batch.generation.skipped family={} childPrefix={} reason=unrecognised",
                    family.name(), childPrefix);
            return Optional.empty();
        }

        return Optional.of(candidate);
    }

    /**
     * Reads the run of decimal digits that ends a segment as a number.
     *
     * <p>Assumptions: the digits are taken from the end of the segment rather than from a position after
     * a marker, so this method needs no knowledge of how the generation segment is introduced. That keeps
     * the marker's spelling declared in exactly one place, the type that renders it, and the round-trip
     * check in the caller is what confirms the reading was right.</p>
     *
     * <p>Assumptions: the run is bounded to the rendered width, so a longer run of digits is reported as
     * unreadable rather than accumulated. A segment carrying more digits than the convention allows was
     * not produced by the owning renderer, and reading its trailing digits would silently reinterpret it
     * as some other generation.</p>
     *
     * @param segment the child segment to read; must not be {@code null}
     * @return the number the trailing digits spell, or {@link #UNREADABLE_GENERATION_NUMBER} when the
     *     segment ends in no ASCII digit, is nothing but digits, or ends in more digits than the rendered
     *     width holds
     */
    private static int trailingDigits(String segment) {
        int firstDigit = segment.length();
        while (firstDigit > 0 && isAsciiDigit(segment.charAt(firstDigit - 1))) {
            firstDigit--;
        }

        int digitCount = segment.length() - firstDigit;
        if (digitCount == 0 || digitCount > DatasetGeneration.GENERATION_DIGITS || firstDigit == 0) {
            return UNREADABLE_GENERATION_NUMBER;
        }

        // WHY : Assumptions: the run is parsed rather than accumulated digit by digit because it is
        //       already bounded above by the rendered width, so it cannot overflow the return type, and
        //       the ASCII test above has already excluded every character the parse would reject.
        return Integer.parseInt(segment.substring(firstDigit));
    }

    /**
     * Reports whether a character is one of the ten ASCII decimal digits.
     *
     * <p>Assumptions: the test is deliberately narrower than the platform's own digit predicate, which
     * answers true for the decimal digits of every script and pairs with a parse that accepts them.
     * Measured on this runtime, the Arabic-Indic digit three is reported as a digit and parses to the
     * value three, so a prefix spelled in another script's digits would be read as a generation number
     * the owning renderer never emits. The round-trip check in the caller would reject such a child in
     * any case, so this narrowing is not what makes the outcome correct; it is what keeps the rejection
     * happening for the stated reason rather than through a comparison that happens to disagree.</p>
     *
     * @param candidate the character to classify
     * @return {@code true} when the character is one of {@code 0} through {@code 9}, {@code false}
     *     otherwise
     */
    private static boolean isAsciiDigit(char candidate) {
        return candidate >= '0' && candidate <= '9';
    }

    /**
     * Builds the key under which one run's allocation for one family is memoised.
     *
     * @param runId the orchestrator execution the allocation belongs to; must not be {@code null}
     * @param family the family being allocated; must not be {@code null}
     * @return the memoisation key, never {@code null}
     */
    private static String memoisationKey(String runId, DatasetFamily family) {
        // WHY : Assumptions: the family's own constant name is used rather than its base name or its path
        //       segment. All three are unique across the ten, and the constant name is the one that cannot
        //       change without a compilation failure somewhere, whereas the other two are strings a future
        //       edit could align between two families without the compiler noticing.
        return runId + MEMOISATION_KEY_SEPARATOR + family.name();
    }

    /**
     * Rejects a configuration or argument value that is absent or contains nothing but whitespace.
     *
     * @param value the value to check; may be {@code null}, which is rejected
     * @param name the value's name, used in the rejection message so a log identifies which one was
     *     missing; must not be {@code null}
     * @return the value unchanged when it carries content
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is empty or contains nothing but whitespace
     */
    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");

        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }

        return value;
    }

    /**
     * Reports that a dataset generation could not be resolved.
     *
     * <p>Assumptions: one exception type covers both causes -- an unreadable object store and an
     * exhausted generation range -- because a caller can do nothing different about them. Each is
     * terminal for the step, and the step's failure is what the invoking state machine catches and routes
     * to its notification path, so the distinction that matters is carried by the message rather than by
     * the type.</p>
     *
     * <p>Assumptions: the type is unchecked, which matches how the object-store client already reports
     * failure and keeps the resolution methods callable from inside a memoising lambda without a wrapper
     * whose only purpose would be to satisfy a checked signature.</p>
     */
    public static final class DatasetGenerationException extends RuntimeException {

        /**
         * The version identifier of this exception's serialised form.
         *
         * <p>Assumptions: the value is declared rather than left to the compiler so that the serialised
         * form is stable across recompilation, which is the contract every serialisable type in the
         * platform library carries.</p>
         */
        private static final long serialVersionUID = 1L;

        /**
         * Builds the exception from a message describing what could not be resolved.
         *
         * @param message what could not be resolved, naming the family and the partition; must not be
         *     {@code null}
         */
        public DatasetGenerationException(String message) {
            super(message);
        }

        /**
         * Builds the exception from a message and the underlying failure it wraps.
         *
         * @param message what could not be resolved, naming the family and the partition; must not be
         *     {@code null}
         * @param cause the failure this one was raised in response to; must not be {@code null}
         */
        public DatasetGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
