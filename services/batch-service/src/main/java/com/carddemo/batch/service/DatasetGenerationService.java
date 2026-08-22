package com.carddemo.batch.service;

import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

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
 * <h2>The two relative forms have different scopes, and the difference is load-bearing</h2>
 *
 * <p>Assumptions: {@code (0)} resolves across the WHOLE family and {@code (+1)} is scoped to the target
 * business date. The two are not symmetric and must not be made so. {@code app/jcl/COMBTRAN.jcl:24}
 * reads {@code TRANSACT.BKUP(0)} and {@code :26} reads {@code SYSTRAN(0)}, and a date-scoped answer to
 * either returns nothing on the first run of a new business day even though the generation the merge
 * needs exists under the previous day's partition -- the catalog the notation came from held one
 * generation sequence per base and knew nothing of dates. Conversely a family-wide {@code (+1)} would
 * make a second staging run for an already-staged earlier date derive its number from some later date's
 * generations, so re-running one day after a subsequent day had been staged would skip numbers and leave
 * the two dates' sequences uncomparable. The sibling stager states both halves of this and enforces
 * them the same way, family-wide in its {@code current_generation} and date-scoped in its
 * {@code next_generation}.</p>
 *
 * <p>Refactoring Rationale: both forms were previously date-scoped, and so was retention. That made
 * {@code (0)} answer empty at every date boundary and made the five-generation window count five
 * generations PER DATE rather than five per family -- so a family staged on six days retained thirty
 * generations while reporting that it retained five. Ordering across a family is total because a
 * coordinate compares on its resolved partition date before its generation number, which is the same
 * ordering the sibling's coordinate type derives from its own field order, in its
 * {@code GenerationPrefix} dataclass.</p>
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
 * <p>Trade-offs: the consequence is that an allocation is recorded for the duration of a run rather than
 * recomputed, and the cost of holding that record is accepted in exchange for the second reference
 * addressing the generation the first one created. {@link #allocateNewGeneration} documents the
 * alternative at the point the decision is made.</p>
 *
 * <p>Refactoring Rationale: that record was a process-local map, and a process-local map cannot hold the
 * invariant the paragraph above states. A batch step runs as a Fargate task, the nightly chain stages
 * through a {@code Map} state that runs one containerised branch per dataset, and Step Functions may
 * retry a branch -- so the two references a job makes to one family are not guaranteed to occur in one
 * process, and a retried branch begins with an empty map. The map therefore held the invariant only in
 * the single case where nothing went wrong and everything ran in one container. It has been replaced by
 * two conditionally-written objects in the dataset bucket, described under the next heading, which hold
 * the same invariant across containers, across retries and across a restart.</p>
 *
 * <h2>The allocation is reserved durably, in the bucket, by conditional write</h2>
 *
 * <p>Assumptions: the bucket is the one durable state every branch and every attempt already agrees on,
 * so it is where the reservation belongs. The sibling stager records the same conclusion for the same
 * reason in its {@code reserve_generation}: a counter held
 * by a process is wrong twice over, because two {@code Map} branches each start from the same base and
 * compute the same number, and because a retried branch recomputes the number its failed attempt already
 * used and overwrites a generation that had completed.</p>
 *
 * <p>Assumptions: two objects are written, and each answers a different question. A marker at
 * {@value #CLAIM_OBJECT_NAME} beneath the generation's own key prefix answers "is this number taken",
 * and it is written with a conditional guard so that exactly one writer across all runs can create it.
 * An entry beneath {@value #RUN_CLAIM_ROOT} answers "which number did this run already take for this
 * family", and it is what a second reference, a retried branch or a restarted container reads instead of
 * allocating again.</p>
 *
 * <p>Trade-offs: the per-generation marker sits UNDER the generation prefix deliberately, which means a
 * claimed generation immediately becomes a listed common prefix for both implementations -- this one at
 * {@link #listGenerations(DatasetFamily, BusinessDate)} and the sibling at
 * {@code loaders/s3_stage.py}'s {@code list_generation_prefixes}. The accepted cost is one object of a
 * few bytes per generation; the
 * benefit is that a number claimed by a Java step is a number the Python stager also sees as taken,
 * which no reservation held outside the bucket could achieve. The run entry sits at a top-level prefix
 * outside every family root for the complementary reason: no family listing may return it, because a
 * bookkeeping prefix appearing among a family's dates would be a candidate the parsers then have to
 * reject.</p>
 *
 * <p>Trade-offs: an allocation that is claimed and then abandoned -- because the step failed after the
 * conditional write -- permanently consumes that generation number. That is accepted, and it is also
 * what the reference does: a job step that creates {@code (+1)} and then fails leaves a catalogued
 * generation behind, and the retention rule is what eventually removes it. The alternative, deleting the
 * marker on failure, was rejected because a delete cannot be guaranteed to run on the paths that matter
 * -- a killed container runs no cleanup -- so it would replace a reliable small cost with an unreliable
 * one.</p>
 *
 * <h2>What this class touches in the bucket, and what it does not own</h2>
 *
 * <p>Trade-offs: the family roster, the two relative forms, the derivation of the partition-date and
 * generation segments and the rendering of a key prefix all belong to
 * {@link com.carddemo.batch.dto.DatasetGeneration}, and none of them is restated here. The cost is one
 * more type in the call path of every method below; the benefit is that a family, a segment spelling or a
 * padding width cannot drift between two declarations, because there is only ever one. This class
 * decides WHICH generation a step addresses and never spells one.</p>
 *
 * <p>Assumptions: the bucket, the ten prefix families, bucket versioning and the noncurrent-version
 * lifecycle configuration are provisioned by {@code infra/modules/s3-datasets}. This class provisions
 * none of them. What it does to the objects inside that bucket is the whole of the following, and
 * nothing else:</p>
 *
 * <ul>
 *   <li>It READS by listing. {@link #listGenerations(DatasetFamily, BusinessDate)} and the family-wide
 *       walk list with a delimiter, so the store returns one common prefix per generation;
 *       {@link #scratchGeneration} lists the SAME prefix undelimited, because there it wants every key
 *       rather than the level below. It also gets one object, the run entry beneath
 *       {@value #RUN_CLAIM_ROOT}, to learn which generation this run already took.</li>
 *   <li>It WRITES two kinds of object. The two reservation markers described above, each a few bytes
 *       and each written at most once under a conditional guard; and, through
 *       {@link #stageDataset(DatasetGeneration, String, Path)}, one dataset object per call, streamed
 *       from a local file and written UNCONDITIONALLY, because the generation was already claimed
 *       exclusively before any bytes were staged into it.</li>
 *   <li>It DELETES through {@link #scratchGeneration}, which removes every object beneath one
 *       generation's prefix, batched at the store's own multiple-object limit, and reports how many it
 *       removed.
 *       That is the {@code SCRATCH} half of the retention rule the ten bases declare, and it is the only
 *       delete this class issues: no other method removes anything, and neither marker is ever deleted,
 *       for the reason the abandoned-allocation paragraph above gives. The four job callers decide WHICH
 *       generation has aged out of the five-generation window; the mechanism is here.
 *       ⚠️ This is also the paragraph an operator reads to decide what the batch task role needs, and
 *       getting it wrong has a known cost: while this text said the class deleted nothing, the deployed
 *       policy was granted read and write and no more, and the sixth run of a family could not retire its
 *       oldest generation. The role requires the delete privilege because the delete originates
 *       here.</li>
 * </ul>
 *
 * <p>Alternatives Considered: leaving the staging write and the scratch delete to the callers, so that
 * this class only ever resolved coordinates and reserved numbers. Rejected because a caller can hold a
 * coordinate but not the bucket -- the bucket is configuration this class alone reads, from
 * {@value #DATASET_BUCKET_PROPERTY} -- so every caller would have to be given it, and each would then
 * be free to address a key it built itself. The scratch path is where that costs most: a delete
 * addressed by a caller-supplied prefix string is one dropped segment away from removing a whole
 * family, whereas a delete addressed by a constructed coordinate cannot name anything but one
 * generation of one family on one date. Refactoring Rationale: this paragraph read that the class
 * "deletes no object" and wrote "only the two reservation markers", which described the shape it had
 * before those two operations existed; a reader auditing what may touch the dataset bucket would have
 * concluded that neither a staged dataset nor a scratched generation could originate here.</p>
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
 *
 * <p>Refactoring Rationale: every citation of the sibling stager above names a SYMBOL -- a function or a
 * dataclass in {@code data-migration/src/carddemo_migration/loaders/s3_stage.py} -- where each formerly
 * named a line range. Six of those ranges had already stopped pointing at what they claimed, because the
 * stager was rewritten to share this class's reservation contract and every line below the change moved.
 * A citation of a file that is edited independently of this one cannot be maintained by line number: it
 * decays silently, and it decays into the most misleading possible state, since a reader who follows it
 * lands on real code that says something else. Symbols move with their definition. Citations of
 * {@code app/**} stay line-numbered deliberately, because that tree is reference-only and byte-identical
 * by policy, so a line number there is stable in a way one into a live sibling never is.</p>
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
     * The object name, beneath a generation's own key prefix, that marks the number as taken.
     *
     * <p>Assumptions: the name opens with an underscore so that it sorts and reads as bookkeeping rather
     * than as a staged dataset object, and it carries no run identifier or date in the name itself
     * because its BODY carries the run identifier. Putting the run identifier in the key would make two
     * runs able to create two different markers under one generation prefix, which is precisely the
     * exclusivity this object exists to provide.</p>
     */
    public static final String CLAIM_OBJECT_NAME = "_generation.claim";

    /**
     * The top-level key prefix beneath which each run's per-family allocation is recorded.
     *
     * <p>Assumptions: the prefix is top-level, outside every family root, so that no family listing can
     * return it. A bookkeeping prefix appearing among a family's date partitions would be a candidate
     * every generation parser -- this module's and the sibling stager's -- would then have to reject, and
     * a parser that rejects is a parser that can be made to accept by mistake.</p>
     */
    public static final String RUN_CLAIM_ROOT = "_generation-claims/";

    /**
     * The number of times an allocation re-lists and re-attempts its conditional claim before failing.
     *
     * <p>Assumptions: a bounded retry is required rather than an unbounded loop, because a loop that
     * cannot end is a batch step that never returns and a state machine timeout rather than a diagnosable
     * failure. Each iteration is lost only when a DIFFERENT run claimed the same number in the window
     * between this run's listing and its conditional write, so the bound is the number of concurrent
     * writers this method tolerates before reporting that it cannot make progress. Eight is comfortably
     * above the widest fan-out the nightly chain has -- the staging {@code Map} state runs one branch per
     * dataset family, and no two branches of it write the same family.</p>
     */
    public static final int MAX_ALLOCATION_ATTEMPTS = 8;

    /**
     * The conditional-write guard that succeeds only when the object does not already exist.
     *
     * <p>Assumptions: the asterisk form of the no-match precondition is the object store's
     * compare-and-set primitive. It is what makes the claim atomic: two writers issuing it against one
     * key produce exactly one success, decided by the store rather than by either writer's timing.</p>
     */
    private static final String ABSENT_OBJECT_GUARD = "*";

    /**
     * The status the object store reports when a conditional write lost to an existing object.
     *
     * <p>Assumptions: this status means the key exists and the caller did not win it. It is a normal
     * outcome of a race rather than a fault, so it is translated into a retry rather than into a
     * failure.</p>
     */
    private static final int PRECONDITION_FAILED_STATUS = 412;

    /**
     * The status the object store reports when a concurrent conditional write is already in flight.
     *
     * <p>Assumptions: this status is distinct from the precondition failure above and means the outcome
     * is not yet decided rather than decided against the caller. Both are handled the same way here -- by
     * listing again and re-attempting -- because the next listing observes whichever writer won, and a
     * caller cannot act differently on "you lost" than on "ask again".</p>
     */
    private static final int CONDITIONAL_CONFLICT_STATUS = 409;

    /**
     * The separator between the run segment and the family segment of a run-claim key.
     */
    private static final String RUN_CLAIM_FAMILY_SEPARATOR = "/family=";

    /**
     * The literal opening the run segment of a run-claim key, {@code run=}.
     */
    private static final String RUN_CLAIM_RUN_MARKER = "run=";

    /**
     * The number of keys one deletion request carries, one thousand.
     *
     * <p>Assumptions: the value is the object store's own published maximum for a multiple-object delete,
     * so batching at it issues the fewest requests the store permits. Exceeding it is rejected outright
     * rather than silently truncated, which is why the batching is explicit rather than left to the size
     * of whatever a listing returned.</p>
     */
    private static final int DELETE_BATCH_SIZE = 1000;

    /**
     * The value reported when a listed child prefix carries no readable generation number.
     *
     * <p>Assumptions: a negative sentinel is unambiguous because the lowest generation number the
     * coordinate admits is one, so no readable value can collide with it. The alternative, reporting
     * absence through an optional, would box a primitive on every child of every listing to express a
     * condition the caller checks immediately with a range comparison it performs anyway.</p>
     */
    private static final int UNREADABLE_GENERATION_NUMBER = -1;

    /**
     * Orders coordinates of one family as the reference catalog ordered its generations.
     *
     * <p>Assumptions: the resolved partition date is compared before the generation number, so every
     * generation of an earlier date precedes every generation of a later one. The comparison uses the
     * RESOLVED date rather than the supplied token, because two different tokens -- the ten-character
     * separated layout and the eight-character compact layout the interest step's parameter carries --
     * resolve to the same partition and would otherwise sort as two different days. The resolved form is
     * year-month-day, so its lexical order is its chronological order and no date parsing is needed to
     * compare two of them.</p>
     */
    private static final Comparator<DatasetGeneration> FAMILY_ORDER =
            Comparator.comparing(DatasetGeneration::partitionDate)
                    .thenComparingInt(DatasetGeneration::generationNumber);

    /**
     * The business date used to build a coordinate whose only purpose is to be asked how it renders.
     *
     * <p>Assumptions: the value never reaches a key. It is used to obtain a rendered date-partition
     * segment from which the marker that opens it is recovered by subtraction, so that this class matches
     * a marker it never spells. A constant is used rather than a fresh instance per call because the
     * probe is immutable and its content is irrelevant -- only its shape is read.</p>
     *
     * <p>Assumptions: the token is deliberately a date whose digits are all distinct from one another's
     * positions in the marker, so that a defect in the subtraction cannot be masked by a coincidental
     * character match between the marker and the date.</p>
     */
    private static final BusinessDate PARTITION_PROBE_DATE = new BusinessDate("1970-01-02");

    /** The object-store client the generation listings are read through. */
    private final S3Client objectStore;

    /** The bucket every staged dataset generation lives in, supplied by configuration. */
    private final String datasetBucket;

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

        // WHY : Assumptions: the bucket is rejected when blank and not merely when absent, because
        //       the property is supplied as an environment variable, and an environment
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
     *     same family and run whether or not those calls occur in one process
     * @throws NullPointerException if {@code family}, {@code businessDate} or {@code runId} is
     *     {@code null}
     * @throws IllegalArgumentException if {@code runId} is blank
     * @throws DatasetGenerationException if the existing generations of the family cannot be listed, if
     *     the reservation markers cannot be read or written, if the family already holds the highest
     *     representable generation for the business date, or if {@value #MAX_ALLOCATION_ATTEMPTS}
     *     successive attempts each lost their number to a concurrent writer
     */
    public DatasetGeneration allocateNewGeneration(
            DatasetFamily family, BusinessDate businessDate, String runId) {

        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        requireNonBlank(runId, "runId");

        // WHY : Alternatives Considered: allocating on every call, which is the obvious reading of "(+1)
        //       means a new generation" and is wrong. Four jobs name one family twice through that same
        //       spelling and read back on the second reference -- COMBTRAN.jcl:37 then :44,
        //       PRTCATBL.jcl:39 then :45, TRANREPT.jcl:33 then :39, and TRANREPT.jcl:55 then :66 -- so
        //       per-call allocation would hand the reading step a second, empty prefix, write two
        //       generations where the reference writes one, and consume the five-generation retention
        //       window at twice the intended rate.
        // WHY : Refactoring Rationale: this read was a lookup in a process-local map. The map answered
        //       correctly only while both references occurred in one container and nothing was retried,
        //       and neither holds for a Fargate task invoked from a Map state that Step Functions may
        //       redrive. The record is now an object in the bucket, so a second reference made by a
        //       different container, a retried branch or a restarted process reads the same answer.
        Optional<DatasetGeneration> alreadyAllocated = recordedAllocation(family, businessDate, runId);
        if (alreadyAllocated.isPresent()) {
            LOG.debug("event=batch.generation.resolved runId={} family={} generation={} source=recorded",
                    runId, family.name(), alreadyAllocated.get().generationNumber());
            return alreadyAllocated.get();
        }

        DatasetGeneration claimed = claimNextUnclaimedGeneration(family, businessDate, runId);
        recordAllocation(family, businessDate, runId, claimed);

        // WHY : Assumptions: the recording step can only disagree with the claim when a second thread of
        //       the SAME run raced this one, and it resolves that by returning the recorded answer rather
        //       than the locally claimed one. Re-reading after the recording write is therefore not
        //       redundant: it is what makes both racers return one coordinate.
        DatasetGeneration allocated = recordedAllocation(family, businessDate, runId).orElse(claimed);

        LOG.info("event=batch.generation.allocated runId={} family={} generation={} prefix={}",
                runId, family.name(), allocated.generationNumber(), allocated.keyPrefix());
        return allocated;
    }

    /**
     * Claims the lowest unclaimed generation of one family and date, re-listing when a claim is lost.
     *
     * <p>Assumptions: the candidate is recomputed from a fresh listing on every attempt rather than
     * incremented locally. A lost claim means another writer created that number, and the only way to
     * learn what else it may have created in the same window is to ask the store again.</p>
     *
     * @param family the family being allocated; must not be {@code null}
     * @param businessDate the business date the allocation is partitioned under; must not be {@code null}
     * @param runId the run the claim is recorded against; must not be {@code null}
     * @return the generation this call successfully claimed, never {@code null}
     * @throws DatasetGenerationException if the generations cannot be listed, if the claim cannot be
     *     written for a reason other than losing the race, if the generation range is exhausted, or if
     *     {@value #MAX_ALLOCATION_ATTEMPTS} attempts each lost
     */
    private DatasetGeneration claimNextUnclaimedGeneration(
            DatasetFamily family, BusinessDate businessDate, String runId) {

        for (int attempt = 1; attempt <= MAX_ALLOCATION_ATTEMPTS; attempt++) {
            DatasetGeneration candidate =
                    nextGeneration(family, businessDate, listGenerations(family, businessDate));

            if (writeIfAbsent(candidate.keyPrefix() + CLAIM_OBJECT_NAME, runId)) {
                return candidate;
            }

            LOG.info("event=batch.generation.claim-lost runId={} family={} generation={} attempt={}",
                    runId, family.name(), candidate.generationNumber(), attempt);
        }

        throw new DatasetGenerationException("dataset family " + family.mainframeBaseName()
                + " could not be allocated a generation for business date " + businessDate.token()
                + " within " + MAX_ALLOCATION_ATTEMPTS + " attempts, because another writer claimed the"
                + " next number on every attempt");
    }

    /**
     * Reads the generation this run has already recorded for one family, if it has recorded one.
     *
     * <p>Assumptions: the recorded value is the generation NUMBER and the coordinate is rebuilt around
     * it from the family and business date the caller supplied. Storing a rendered prefix instead and
     * parsing it back would put a second reader of the prefix convention here, and the convention is
     * owned by one type on purpose.</p>
     *
     * @param family the family whose recorded allocation is wanted; must not be {@code null}
     * @param businessDate the business date to rebuild the coordinate under; must not be {@code null}
     * @param runId the run whose record is read; must not be {@code null}
     * @return the recorded generation, or an empty result when this run has recorded none for this
     *     family; never {@code null}
     * @throws DatasetGenerationException if the record exists but cannot be read, or if it holds
     *     something other than a generation number this type accepts
     */
    private Optional<DatasetGeneration> recordedAllocation(
            DatasetFamily family, BusinessDate businessDate, String runId) {

        String key = runClaimKey(runId, family);
        final String recorded;
        try {
            recorded = this.objectStore.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(this.datasetBucket)
                    .key(key)
                    .build()).asUtf8String();
        } catch (NoSuchKeyException absent) {
            // WHY : Assumptions: an absent record is the ordinary first-call outcome and carries no
            //       diagnostic value, so it is neither logged at an operational level nor wrapped. The
            //       exception instance is deliberately unreferenced; naming it is what documents that
            //       this branch is the store reporting absence rather than a fault being swallowed.
            return Optional.empty();
        } catch (SdkException failure) {
            throw new DatasetGenerationException("could not read the recorded generation allocation of"
                    + " dataset family " + family.mainframeBaseName() + " for run " + runId
                    + " at key " + key, failure);
        }

        // WHY : Assumptions: the body is trimmed before parsing because a value written by a shell
        //       redirection or an operator repair would carry a trailing newline, and a record that is
        //       correct apart from one byte of whitespace should be honoured rather than failing a step.
        String digits = recorded.trim();
        try {
            return Optional.of(new DatasetGeneration(family, businessDate, Integer.parseInt(digits)));
        } catch (IllegalArgumentException malformed) {
            // WHY : Assumptions: one catch covers both ways the record can be unusable, because the
            //       parse failure the platform raises for a non-numeric body is itself a subclass of the
            //       rejection the coordinate raises for a number outside the four-digit range. Naming
            //       both would not compile, and naming only the parse failure would let an out-of-range
            //       record propagate a rejection that names no key.
            throw new DatasetGenerationException("the recorded generation allocation of dataset family "
                    + family.mainframeBaseName() + " for run " + runId + " at key " + key
                    + " does not hold a generation number this run can address", malformed);
        }
    }

    /**
     * Records the generation this run allocated for one family, unless a record already exists.
     *
     * <p>Assumptions: the write is conditional on absence, so two threads of one run cannot both record
     * and the loser's value cannot overwrite the winner's. A losing write is not an error here -- the
     * caller re-reads the record afterwards and both racers return whichever value was recorded.</p>
     *
     * @param family the family the allocation belongs to; must not be {@code null}
     * @param businessDate the business date the allocation is partitioned under, named in the failure
     *     message only; must not be {@code null}
     * @param runId the run the allocation is recorded against; must not be {@code null}
     * @param allocated the generation to record; must not be {@code null}
     * @throws DatasetGenerationException if the record cannot be written for a reason other than one
     *     already existing
     */
    private void recordAllocation(DatasetFamily family, BusinessDate businessDate, String runId,
            DatasetGeneration allocated) {

        if (!writeIfAbsent(runClaimKey(runId, family),
                Integer.toString(allocated.generationNumber()))) {

            LOG.info("event=batch.generation.record-lost runId={} family={} businessDate={}"
                            + " generation={}",
                    runId, family.name(), businessDate.token(), allocated.generationNumber());
        }
    }

    /**
     * Writes one small marker object, but only if no object already occupies that key.
     *
     * <p>Assumptions: the conditional guard is the object store's own compare-and-set, so exactly one of
     * any number of concurrent writers succeeds and the store rather than the caller decides which. That
     * is the whole reason the reservation is durable: no coordination between the writers is needed, and
     * none of them has to be running at the same time as the others.</p>
     *
     * @param key the object key to create; must not be {@code null}
     * @param body the marker content, written as its own bytes with no framing; must not be {@code null}
     * @return {@code true} when this call created the object, {@code false} when an object already
     *     existed at that key or a concurrent conditional write held it
     * @throws DatasetGenerationException if the write failed for any reason other than the key being
     *     taken
     */
    private boolean writeIfAbsent(String key, String body) {
        try {
            this.objectStore.putObject(
                    PutObjectRequest.builder()
                            .bucket(this.datasetBucket)
                            .key(key)
                            .ifNoneMatch(ABSENT_OBJECT_GUARD)
                            .build(),
                    RequestBody.fromString(body, StandardCharsets.UTF_8));
            return true;
        } catch (S3Exception rejected) {
            // WHY : Assumptions: exactly two statuses mean "you do not hold this key" -- the
            //       precondition failure, which means an object is already there, and the conditional
            //       conflict, which means another conditional write is in flight and the outcome is not
            //       yet visible. Both are reported as not-created so the caller lists again; every other
            //       status is a real fault and is raised, because treating an authorisation or
            //       key-management failure as a lost race would loop until the attempt bound and then
            //       report contention that never occurred.
            int status = rejected.statusCode();
            if (status == PRECONDITION_FAILED_STATUS || status == CONDITIONAL_CONFLICT_STATUS) {
                return false;
            }
            throw new DatasetGenerationException(
                    "could not write the reservation marker at key " + key, rejected);
        } catch (SdkException failure) {
            throw new DatasetGenerationException(
                    "could not write the reservation marker at key " + key, failure);
        }
    }

    /**
     * Resolves the generation that already exists for one family, the analogue of a {@code (0)} reference.
     *
     * <p>The answer spans the whole family rather than one business date. Every generation of an earlier
     * date precedes every generation of a later one, and the newest of them all is returned.</p>
     *
     * <p>Trade-offs: an absent generation is reported as an empty result rather than as a first
     * generation or as an exception, and the distinction is load-bearing for the combine flow. That flow
     * is the only reader of this form -- {@code app/jcl/COMBTRAN.jcl:24} and {@code :26} -- and it merges
     * two inputs it did not produce, so "the family holds nothing yet" and "the family holds a
     * generation" are different situations that must not answer alike: substituting a first generation
     * would send the merge at an unwritten prefix and produce an empty output that looks like a
     * successful merge of empty inputs. An exception was the other candidate and was declined because a
     * caller that can legitimately proceed with one input present and one absent would then have to use
     * exception handling for a control decision. The cost is that every caller must unwrap the result and
     * decide, which is exactly the decision being surfaced.</p>
     *
     * <p>Refactoring Rationale: this method took a business date and answered within that date's
     * partition. The parameter has been removed rather than made optional, because a date-scoped
     * {@code (0)} is not a weaker answer -- it is a wrong one, and leaving it reachable would leave the
     * defect reachable. On the first run of a new business day the two inputs the combine flow merges
     * exist under the PREVIOUS day's partition, so the date-scoped form reported both as absent and the
     * merge silently produced an empty output. The reference catalog held one generation sequence per
     * base and had no notion of a date to scope by, which is the shape restored here.</p>
     *
     * @param family the generation-dataset family being read; must not be {@code null}
     * @return the newest generation staged for that family across every business date, or an empty
     *     result when the family holds none; never {@code null}
     * @throws NullPointerException if {@code family} is {@code null}
     * @throws DatasetGenerationException if the existing generations of the family cannot be listed
     */
    public Optional<DatasetGeneration> resolveCurrentGeneration(DatasetFamily family) {
        Objects.requireNonNull(family, "family must not be null");

        // WHY : Assumptions: the newest generation is the maximum under the family ordering, which
        //       compares the resolved partition date before the generation number. Taking the maximum by
        //       generation number alone would answer with generation three of Monday over generation two
        //       of Tuesday, which inverts the catalog's own order at exactly the date boundary this form
        //       exists to read across.
        // WHY : Alternatives Considered: reading the last element of the listing, since the object
        //       store's lexicographic ordering does agree with this ordering while the date stays
        //       year-month-day and the generation stays padded. Declined: that makes this method's
        //       correctness depend on two rendering properties of another type that nothing here
        //       asserts, and the explicit maximum costs one pass over at most a handful of coordinates.
        Optional<DatasetGeneration> current = listGenerations(family).stream().max(FAMILY_ORDER);

        if (current.isEmpty()) {
            LOG.info("event=batch.generation.absent family={}", family.name());
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
    // WHY : Refactoring Rationale: this decision is package-private rather than public because the only
    //       caller outside this method is the allocation above it, and the only callers outside the class
    //       are the tests in this package. Reviewing the surface found four operations published with no
    //       production caller beyond the class itself; three of them -- this one and the two listings --
    //       are genuinely used internally, so the honest correction is to stop publishing them rather
    //       than to delete them or to invent a caller. Alternatives Considered: leaving them public and
    //       recording that a job reaches them transitively, rejected because a published operation
    //       invites a caller that bypasses the allocation's durable reservation and re-introduces the
    //       two-writers-one-number defect the reservation exists to prevent.
    DatasetGeneration nextGeneration(DatasetFamily family, BusinessDate businessDate,
            List<DatasetGeneration> existing) {

        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(existing, "existing must not be null");

        int highest = existing.stream()
                .mapToInt(DatasetGeneration::generationNumber)
                .max()
                .orElse(DatasetGeneration.MINIMUM_GENERATION_NUMBER - 1);

        // WHY : Trade-offs: exhaustion of the generation range is reported here rather than left to
        //       the coordinate. The sibling's constructor already refuses an out-of-range number, so this
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
     * <p>Refactoring Rationale: the ordering was by generation number alone, which is correct only while
     * a family holds generations of exactly one business date. Across two dates it ranked generation
     * three of the earlier date above generation two of the later one, so the retained window kept the
     * wrong five and the rule scratched a generation newer than one it left standing. Worse, when the
     * caller passed one date's generations at a time -- which is what a date-scoped listing gave it --
     * the count applied PER DATE, so a family staged on six days retained thirty generations while every
     * call reported that it retained five. The ordering is now the family ordering, resolved date before
     * generation number, matching the ordering the sibling stager's {@code GenerationPrefix} dataclass
     * derives from its own field order.</p>
     *
     * @param existing every generation currently present for one family, across every business date, in
     *     any order; must not be {@code null}
     * @return the generations beyond the retained count, oldest first, never {@code null} and empty when
     *     the family holds no more than the retained count
     * @throws NullPointerException if {@code existing} is {@code null}
     */
    // WHY : Refactoring Rationale: the pure decision is package-private and the family-wide overload
    //       below it is the published one, because a step that reaches this form directly has to have
    //       obtained the list itself -- and a step that listed a single date and passed the result here
    //       would apply the retained count PER DATE, which is exactly the defect the family-wide overload
    //       exists to remove the opportunity for. Publishing only the overload makes that mistake
    //       unreachable from outside the class instead of merely documented against.
    List<DatasetGeneration> generationsToScratch(List<DatasetGeneration> existing) {
        Objects.requireNonNull(existing, "existing must not be null");

        List<DatasetGeneration> ordered = new ArrayList<>(existing);
        ordered.sort(FAMILY_ORDER);

        // WHY : Assumptions: the list is oldest first, so the retained window is the LAST
        //       GDG_GENERATION_LIMIT entries and everything before them is scratched. Slicing from the
        //       front rather than reversing and slicing from the back keeps the returned order oldest
        //       first with no second sort, and the size guard above makes the bound safe -- an
        //       arithmetic form such as subList(0, size - limit) would compute a negative bound for a
        //       family holding fewer generations than the count.
        if (ordered.size() <= GDG_GENERATION_LIMIT) {
            return List.of();
        }

        return List.copyOf(ordered.subList(0, ordered.size() - GDG_GENERATION_LIMIT));
    }

    /**
     * Returns the generations the retention rule removes from one family, read from the object store.
     *
     * <p>Assumptions: this is the family-wide entry point a retention step calls, and it exists so that
     * no caller has to remember that the rule counts across a family rather than within a date. A step
     * that listed one date and passed the result to the pure decision above would apply the count per
     * date and retain five generations per day, which is the defect this overload removes the
     * opportunity for.</p>
     *
     * @param family the family whose aged-out generations are wanted; must not be {@code null}
     * @return the generations beyond the retained count, oldest first, never {@code null} and empty when
     *     the family holds no more than the retained count
     * @throws NullPointerException if {@code family} is {@code null}
     * @throws DatasetGenerationException if the existing generations of the family cannot be listed
     */
    public List<DatasetGeneration> generationsToScratch(DatasetFamily family) {
        Objects.requireNonNull(family, "family must not be null");
        return generationsToScratch(listGenerations(family));
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
     * Writes one dataset file beneath a generation's prefix and returns the key it landed at.
     *
     * <p>Assumptions: the body is supplied as a file rather than as bytes, and the upload streams from it.
     * A staged generation of the transaction master is as large as the master, so holding it as a byte
     * array would bound the step by heap rather than by the container's ephemeral disk. The caller owns
     * the file's lifetime, because only the caller knows whether it wants to inspect it after the
     * upload.</p>
     *
     * <p>Assumptions: the write is NOT conditional, unlike the reservation markers. A generation is
     * claimed before it is written, so by the time a step reaches this method it already holds the number
     * exclusively and a conditional guard would only be able to fail against the step's own earlier
     * attempt -- which is a retry that should overwrite rather than an intrusion that should be
     * refused.</p>
     *
     * @param generation the generation the file belongs to; must not be {@code null}
     * @param objectName the file's name within the generation prefix, with no separator in it; must not
     *     be {@code null} or blank
     * @param body the local file whose bytes are uploaded; must not be {@code null}
     * @return the object key the file landed at, never {@code null}
     * @throws NullPointerException if {@code generation} or {@code body} is {@code null}
     * @throws IllegalArgumentException if {@code objectName} is blank or contains a key separator
     * @throws DatasetGenerationException if the upload fails
     */
    public String stageDataset(DatasetGeneration generation, String objectName, Path body) {
        Objects.requireNonNull(generation, "generation must not be null");
        Objects.requireNonNull(body, "body must not be null");
        requireNonBlank(objectName, "objectName");

        // WHY : Assumptions: a separator in the name is rejected rather than accepted as a nested path.
        //       A name carrying one would create a further prefix level beneath the generation, and the
        //       generation listing walks exactly two levels -- so the object would be staged somewhere no
        //       listing on either side of the migration reports.
        if (objectName.contains(KEY_SEGMENT_SEPARATOR)) {
            throw new IllegalArgumentException("objectName must name a file within one generation prefix"
                    + " and must not contain '" + KEY_SEGMENT_SEPARATOR + "'");
        }

        String key = generation.keyPrefix() + objectName;
        try {
            this.objectStore.putObject(
                    PutObjectRequest.builder().bucket(this.datasetBucket).key(key).build(), body);
        } catch (SdkException failure) {
            throw new DatasetGenerationException("could not stage dataset family "
                    + generation.family().mainframeBaseName() + " generation "
                    + generation.generationNumber() + " at key " + key, failure);
        }

        LOG.info("event=batch.generation.staged family={} generation={} key={}",
                generation.family().name(), generation.generationNumber(), key);
        return key;
    }

    /**
     * Deletes every object beneath one generation's prefix, the {@code SCRATCH} half of the retention rule.
     *
     * <p>Assumptions: the deletion is addressed by a CONSTRUCTED COORDINATE rather than by a caller-supplied
     * prefix string, and that is the whole safety argument. A coordinate cannot exist without a family, a
     * business date and an in-range generation number, so the prefix this method derives always names one
     * generation of one family on one date -- a family root, a bare date partition, an empty string and a
     * prefix belonging to another family are all unrepresentable at this signature. A string parameter
     * would make each of them a one-character typo away from deleting a whole family.</p>
     *
     * <p>Assumptions: the objects are listed and deleted rather than removed by a single prefix operation,
     * because the object store has no prefix delete. The listing here is deliberately UNDELIMITED, unlike
     * every other listing in this class: the goal is every key beneath the prefix rather than the prefix
     * names one level down.</p>
     *
     * <p>Refactoring Rationale: the listing is {@code ListObjectVersions} and each deletion names an
     * explicit VERSION IDENTIFIER, where both were version-blind -- {@code ListObjectsV2} and a delete
     * carrying a key alone. The bucket is versioned, which the {@code s3-datasets} module enables
     * unconditionally as the analogue of the five-generation window, and on a versioned bucket a delete
     * with no version identifier does not delete anything: it inserts a delete marker over the current
     * version and leaves every prior version stored and billed. {@code SCRATCH} on the reference baseline
     * releases the space, so the version-blind form was not a slower {@code SCRATCH} but a different
     * operation -- it hid the generation from a listing while retaining its bytes indefinitely, and the
     * five-generation window would have grown without bound behind the marker. Listing versions is also
     * what makes the operation converge on a re-run: a scratched prefix that still holds delete markers
     * is not empty, and only a version-addressed delete can remove the marker itself.</p>
     *
     * <p>Assumptions: DELETE MARKERS are collected alongside object versions and deleted by the same
     * request. A marker left behind is a live "this key does not exist" record that keeps the prefix
     * non-empty, so a prefix scratched by an earlier, version-blind run is cleaned up by this one rather
     * than being permanently unreclaimable.</p>
     *
     * <p>Trade-offs: the same discipline is applied by {@code delete_generation_prefix} in
     * {@code data-migration/src/carddemo_migration/loaders/s3_stage.py}, which prunes the same families
     * from the ETL side. The two implementations must agree, because either may be the one that retires a
     * generation the other created; the accepted cost is one grant of {@code s3:ListBucketVersions} and
     * {@code s3:DeleteObjectVersion} to both task roles rather than to one.</p>
     *
     * <p>Assumptions: a per-object error in a batched response FAILS the operation, where the response was
     * previously not examined at all. A batched delete answers 200 while reporting individual keys it
     * refused, so an unexamined response let a partial deletion be reported as a completed scratch --
     * retention would then appear to hold while the family grew past its window. The failure names the
     * family, the generation, the prefix and the distinct error codes, which is what makes it
     * diagnosable without a second run to find out what was refused.</p>
     *
     * @param generation the generation to scratch; must not be {@code null}
     * @return how many object versions and delete markers were removed, which is zero when the generation
     *     held none
     * @throws NullPointerException if {@code generation} is {@code null}
     * @throws DatasetGenerationException if the version listing fails, if a deletion request fails, or if
     *     the store refuses one or more individual versions within an otherwise successful request
     */
    public int scratchGeneration(DatasetGeneration generation) {
        Objects.requireNonNull(generation, "generation must not be null");

        String prefix = generation.keyPrefix();
        List<ObjectIdentifier> doomed = new ArrayList<>();
        try {
            // WHY : Assumptions: versions and delete markers are collected from ONE paginated listing
            //       rather than two, because the store returns both in the same response and pairing a
            //       key with its version identifier is all either kind needs to be deletable. Two
            //       listings would double the request count and could observe the prefix in two
            //       different states, leaving whichever kind was listed first partially removed.
            this.objectStore
                    .listObjectVersionsPaginator(ListObjectVersionsRequest.builder()
                            .bucket(this.datasetBucket)
                            .prefix(prefix)
                            .build())
                    .stream()
                    .forEach(page -> {
                        page.versions().forEach(version ->
                                addDoomed(doomed, version.key(), version.versionId()));
                        page.deleteMarkers().forEach(marker ->
                                addDoomed(doomed, marker.key(), marker.versionId()));
                    });

            for (int from = 0; from < doomed.size(); from += DELETE_BATCH_SIZE) {
                List<ObjectIdentifier> slice =
                        doomed.subList(from, Math.min(from + DELETE_BATCH_SIZE, doomed.size()));
                DeleteObjectsResponse response =
                        this.objectStore.deleteObjects(DeleteObjectsRequest.builder()
                                .bucket(this.datasetBucket)
                                .delete(Delete.builder().objects(slice).build())
                                .build());
                requireEveryVersionDeleted(generation, prefix, response);
            }
        } catch (SdkException failure) {
            throw new DatasetGenerationException("could not scratch dataset family "
                    + generation.family().mainframeBaseName() + " generation "
                    + generation.generationNumber() + " under prefix " + prefix, failure);
        }

        LOG.info("event=batch.generation.scratched family={} generation={} versions={}",
                generation.family().name(), generation.generationNumber(), doomed.size());
        return doomed.size();
    }

    /**
     * Adds one version-addressed deletion target, ignoring an entry the store described incompletely.
     *
     * <p>Assumptions: an entry missing either half of its address is SKIPPED rather than deleted, because
     * a deletion carrying a key and no version identifier is the version-blind delete this method exists
     * to avoid -- it would insert a delete marker instead of removing anything. Skipping leaves the entry
     * for the next retention pass, which is recoverable; deleting it version-blind is not.</p>
     *
     * @param doomed the accumulating deletion targets; must not be {@code null}
     * @param key the object key the store reported, which may be {@code null} on a malformed entry
     * @param versionId the version identifier the store reported, which may be {@code null} on a
     *     malformed entry
     */
    private static void addDoomed(List<ObjectIdentifier> doomed, String key, String versionId) {
        if (key == null || versionId == null) {
            return;
        }
        doomed.add(ObjectIdentifier.builder().key(key).versionId(versionId).build());
    }

    /**
     * Fails the scratch when a batched deletion reported any individual version it refused.
     *
     * <p>Assumptions: the DISTINCT error codes are reported rather than every refused key, because one
     * refusal per object of a large generation would produce a message no log consumer keeps whole, while
     * the codes are what distinguish the three cases an operator acts on differently -- a missing
     * permission, an object-lock retention period, and a transient internal error worth retrying. The
     * count of refusals is reported alongside them so the scale is not lost.</p>
     *
     * @param generation the generation being scratched, named in the failure; must not be {@code null}
     * @param prefix the prefix being scratched, named in the failure; must not be {@code null}
     * @param response the store's answer to one batched deletion; must not be {@code null}
     * @throws DatasetGenerationException if the response reports one or more refused versions
     */
    private static void requireEveryVersionDeleted(
            DatasetGeneration generation, String prefix, DeleteObjectsResponse response) {

        if (!response.hasErrors() || response.errors().isEmpty()) {
            return;
        }

        Set<String> codes = new LinkedHashSet<>();
        response.errors().forEach(error ->
                codes.add(error.code() == null ? "unknown" : error.code()));

        throw new DatasetGenerationException("could not scratch dataset family "
                + generation.family().mainframeBaseName() + " generation "
                + generation.generationNumber() + " under prefix " + prefix + ": the object store"
                + " refused " + response.errors().size() + " of the versions in one deletion request,"
                + " reporting " + codes);
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
    // WHY : Refactoring Rationale: the date-scoped listing is package-private because it answers a
    //       question only the allocation legitimately asks -- which numbers are already taken under the
    //       date being written -- and answering it for an outside caller invites that caller to treat a
    //       one-date listing as the family's contents. The current-generation and retention decisions
    //       both read the family, so the listing they use is the family-wide walk below.
    List<DatasetGeneration> listGenerations(DatasetFamily family, BusinessDate businessDate) {
        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");

        return List.copyOf(generationsUnder(partitionKeyPrefix(family, businessDate), family,
                businessDate));
    }

    /**
     * Lists every generation staged for one family, across every business date it holds.
     *
     * <p>Assumptions: the walk is two levels of delimited listing -- the date partitions beneath the
     * family root, then the generations beneath each date -- rather than one undelimited listing of the
     * family. An undelimited listing returns one entry per OBJECT, so a family holding five generations
     * of a three-hundred-and-fifty-byte extract returns every record key merely to learn five prefix
     * names. The accepted cost is one request per date rather than one per family. The sibling stager
     * walks the same two levels for the same reason in its {@code latest_generation}.</p>
     *
     * <p>Assumptions: a date partition is accepted purely as an opaque prefix to descend into, and no
     * attempt is made to validate it as a date. Whether a child under it is a generation is settled the
     * same way it is for a single-date listing: by asking the coordinate's own renderer whether it would
     * have produced that segment, under a business date read back from the partition itself. A prefix
     * that is not a date partition therefore yields no generations rather than an error, which is what a
     * bookkeeping prefix or a stray object placed under a family root should do.</p>
     *
     * @param family the family whose generations are listed; must not be {@code null}
     * @return every generation the family holds, in no particular order, never {@code null} and empty
     *     when the family holds none
     * @throws NullPointerException if {@code family} is {@code null}
     * @throws DatasetGenerationException if the object store cannot be read
     */
    // WHY : Refactoring Rationale: the family-wide walk is package-private for the same reason as the
    //       date-scoped one -- the two decisions built on it, current generation and retention, are the
    //       published operations, and a caller wanting either should ask for the answer rather than for
    //       the raw listing. Trade-offs: a future step that genuinely needs the inventory itself, such as
    //       an audit of what a family holds, would have to publish a named operation that says so, which
    //       is a deliberate cost paid to keep the published surface equal to the wired surface.
    List<DatasetGeneration> listGenerations(DatasetFamily family) {
        Objects.requireNonNull(family, "family must not be null");

        List<DatasetGeneration> present = new ArrayList<>();
        for (String datePrefix : datePartitionsUnder(family)) {
            Optional<BusinessDate> partitionDate = businessDateOfPartitionPrefix(datePrefix, family);
            if (partitionDate.isEmpty()) {
                LOG.debug("event=batch.generation.partition-skipped family={} prefix={}"
                        + " reason=unrecognised", family.name(), datePrefix);
                continue;
            }
            present.addAll(generationsUnder(datePrefix, family, partitionDate.get()));
        }

        return List.copyOf(present);
    }

    /**
     * Lists the immediate date-partition prefixes beneath one family's root.
     *
     * @param family the family whose date partitions are listed; must not be {@code null}
     * @return every child prefix of the family root, in listing order with duplicates removed; never
     *     {@code null}
     * @throws DatasetGenerationException if the object store cannot be read
     */
    private Set<String> datePartitionsUnder(DatasetFamily family) {
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(this.datasetBucket)
                .prefix(family.pathSegment())
                .delimiter(KEY_SEGMENT_SEPARATOR)
                .build();

        // WHY : Assumptions: the children are collected into an insertion-ordered set rather than a list,
        //       because a paginator may legitimately return one common prefix on two pages. A duplicate
        //       date would list that date's generations twice, and duplicated coordinates would make the
        //       retained window drop one real generation too many.
        Set<String> datePrefixes = new LinkedHashSet<>();
        try {
            try (Stream<CommonPrefix> children =
                    this.objectStore.listObjectsV2Paginator(request).commonPrefixes().stream()) {
                children.map(CommonPrefix::prefix)
                        .filter(Objects::nonNull)
                        .forEach(datePrefixes::add);
            }
        } catch (SdkException failure) {
            throw new DatasetGenerationException("could not list the business-date partitions of dataset"
                    + " family " + family.mainframeBaseName() + " under prefix "
                    + family.pathSegment(), failure);
        }

        return datePrefixes;
    }

    /**
     * Reads a listed child of a family root back as the business date its partition names.
     *
     * <p>Assumptions: the token is accepted only when a coordinate built from it renders back to
     * byte-identically the same partition segment. That round trip is what keeps the segment spelling
     * owned by one type -- this method matches no marker it declares itself -- and it is also what
     * rejects a bookkeeping or stray prefix without this method having to enumerate what those look
     * like.</p>
     *
     * @param datePrefix the full child prefix the family-root listing returned, including the family
     *     segment and the trailing separator; must not be {@code null}
     * @param family the family being listed; must not be {@code null}
     * @return the business date the partition names, or an empty result when the child is not a partition
     *     this family's renderer would have produced; never {@code null}
     */
    private Optional<BusinessDate> businessDateOfPartitionPrefix(String datePrefix,
            DatasetFamily family) {

        String familyPrefix = family.pathSegment();
        if (!datePrefix.startsWith(familyPrefix) || !datePrefix.endsWith(KEY_SEGMENT_SEPARATOR)) {
            return Optional.empty();
        }

        String segment = datePrefix.substring(
                familyPrefix.length(), datePrefix.length() - KEY_SEGMENT_SEPARATOR.length());

        // WHY : Assumptions: the token is recovered by stripping the marker the renderer emits, which is
        //       read back from a probe rather than spelled here. The probe's own date segment is
        //       "dt=" followed by its resolved date, so removing the resolved date from the end of it
        //       leaves exactly the marker, and no literal spelling of the marker appears in this class.
        DatasetGeneration probe =
                new DatasetGeneration(family, PARTITION_PROBE_DATE,
                        DatasetGeneration.MINIMUM_GENERATION_NUMBER);
        String marker = probe.datePartitionSegment()
                .substring(0, probe.datePartitionSegment().length() - probe.partitionDate().length());
        if (!segment.startsWith(marker)) {
            return Optional.empty();
        }

        final BusinessDate candidate;
        try {
            candidate = new BusinessDate(segment.substring(marker.length()));
        } catch (IllegalArgumentException notADate) {
            return Optional.empty();
        }

        DatasetGeneration rendered = new DatasetGeneration(family, candidate,
                DatasetGeneration.MINIMUM_GENERATION_NUMBER);
        return segment.equals(rendered.datePartitionSegment()) ? Optional.of(candidate)
                : Optional.empty();
    }

    /**
     * Lists the generation children of one already-resolved date-partition prefix.
     *
     * @param partitionPrefix the date-partition prefix to list beneath; must not be {@code null}
     * @param family the family the partition belongs to; must not be {@code null}
     * @param businessDate the business date the partition names; must not be {@code null}
     * @return the generations the partition holds, in listing order; never {@code null}
     * @throws DatasetGenerationException if the object store cannot be read
     */
    private List<DatasetGeneration> generationsUnder(String partitionPrefix, DatasetFamily family,
            BusinessDate businessDate) {

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
     * Builds the object key under which one run's allocation for one family is recorded.
     *
     * <p>Assumptions: the run identifier is placed in its own key segment ahead of the family, so every
     * record of one run sits under one prefix and an operator can list or remove a run's records
     * together. The reverse order would scatter one run's records across ten family prefixes.</p>
     *
     * @param runId the orchestrator execution the allocation belongs to; must not be {@code null}
     * @param family the family being allocated; must not be {@code null}
     * @return the record's object key, never {@code null}
     */
    private static String runClaimKey(String runId, DatasetFamily family) {
        // WHY : Assumptions: the family's own constant name is used rather than its base name or its path
        //       segment. All three are unique across the ten, and the constant name is the one that cannot
        //       change without a compilation failure somewhere, whereas the other two are strings a future
        //       edit could align between two families without the compiler noticing.
        // WHY : Assumptions: the run identifier is used as supplied and is not escaped. The orchestrator
        //       supplies a state-machine execution name, whose own character set is a subset of what an
        //       object key accepts, so escaping would rewrite a value that is already valid and would make
        //       the key a reader sees differ from the identifier the same reader finds in a log line.
        return RUN_CLAIM_ROOT + RUN_CLAIM_RUN_MARKER + runId + RUN_CLAIM_FAMILY_SEPARATOR + family.name();
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
