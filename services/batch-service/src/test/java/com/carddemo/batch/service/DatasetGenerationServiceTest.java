package com.carddemo.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.paginators.ListObjectVersionsIterable;
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
     * A business date one day earlier than the shared one, for the cases that span a date boundary.
     *
     * <p>Assumptions: an EARLIER date is required rather than a later one, because the two rulings a
     * second date settles are both about what happens on the first run of a NEW day: the current
     * generation must be found under the previous day's partition, and retention must count across both.
     * A later date would make the shared date the older one and would leave the ordering assertion
     * reading backwards from the situation it describes.</p>
     */
    private static final BusinessDate EARLIER_BUSINESS_DATE = new BusinessDate("2022-07-17");

    /**
     * The status an object store reports when a conditional write lost to an object already present.
     *
     * <p>Assumptions: the number is spelled here rather than read from the service, because the service's
     * own constant is private and, more importantly, because this value is the OBJECT STORE's contract
     * rather than the service's. A stub that took the number from the subject under test would agree with
     * it by construction and would still agree after the subject started reading the wrong status.</p>
     */
    private static final int PRECONDITION_FAILED_STATUS = 412;

    /** The status an object store reports for a key that holds no object. */
    private static final int NOT_FOUND_STATUS = 404;

    /**
     * The status an object store reports when a read failed inside the service itself.
     *
     * <p>Assumptions: the number is spelled here rather than read from the subject, for the reason the
     * precondition status above gives: this is the store's contract, and a stub that took the value from
     * the subject would agree with it by construction even after the subject started reading the wrong
     * status.</p>
     */
    private static final int SERVER_FAULT_STATUS = 500;

    /**
     * The creation timestamp the stub reports for the first object written to it.
     *
     * <p>Assumptions: the instant is a fixed constructed value and never a clock reading, which is what
     * makes an ordering assertion reproducible. Each subsequent write is reported one second later, so
     * creation order equals write order and a case can stage a family whose creation order disagrees with
     * its date order.</p>
     *
     * <p>Assumptions: the value is deliberately far from the epoch, because the service orders a
     * generation carrying no readable claim timestamp AT the epoch. A fixture instant near it could not
     * distinguish a dated generation from an undated one, which is exactly the distinction two of the
     * cases below rest on.</p>
     */
    private static final Instant FIRST_WRITE_INSTANT = Instant.parse("2022-07-18T22:00:00Z");

    /**
     * How many entries one page of the stubbed version listing carries.
     *
     * <p>Assumptions: the value is the object store's own per-page ceiling, and it is deliberately the
     * same number as the service's deletion batch size without being read from it. Reading the service's
     * constant would make the stub agree with the subject by construction, so a subject that batched at
     * the wrong size would still see pages that matched it exactly. Spelling the store's contract here
     * keeps the two independent.</p>
     */
    private static final int VERSION_PAGE_SIZE = 1000;

    /**
     * The keys the most recently built stub holds, in the order they were created.
     *
     * <p>Assumptions: the backing store is exposed to the cases as a live view rather than copied out,
     * because two of them assert what the service WROTE and one asserts what it did not write. A stub
     * whose writes vanished would let a reservation defect pass as an absence of writes.</p>
     */
    private final Map<String, String> storedObjects = new LinkedHashMap<>();

    /**
     * The version identifiers each key holds, oldest first, as a versioned bucket would hold them.
     *
     * <p>Assumptions: this is a SEPARATE registry from {@link #storedObjects} rather than a richer value
     * in it, because the two answer different questions and one case needs them to disagree. A listing
     * without a delimiter reports every version of every key, including versions no current read can
     * reach, and only a registry that outlives an overwrite can hold those. Folding the versions into the
     * body map would make a key's history exactly one entry long, and a retention pass that removed the
     * current version while leaving its predecessors would look complete.</p>
     */
    private final Map<String, List<String>> storedVersions = new LinkedHashMap<>();

    /**
     * The delete-marker version identifiers each key carries, oldest first.
     *
     * <p>Assumptions: markers are held apart from real versions because the store reports them apart, in
     * a different member of the same response, and because a scratch that removes versions while leaving
     * markers leaves the prefix non-empty. A test can seed a marker directly, which is what a prefix
     * scratched by an earlier version-blind delete actually looks like.</p>
     */
    private final Map<String, List<String>> storedDeleteMarkers = new LinkedHashMap<>();

    /**
     * Every batched deletion the service issued, in order, as the exact identifiers it named.
     *
     * <p>Assumptions: the requests are recorded as their own lists rather than flattened, because one
     * case settles the BATCH SIZE. A flattened record would show every identifier that was deleted and
     * nothing about how many requests carried them, and the batch ceiling is a hard service limit rather
     * than a preference.</p>
     */
    private final List<List<ObjectIdentifier>> deleteRequests = new ArrayList<>();

    /**
     * Per-object refusals every batched deletion is to report, or empty when none are staged.
     *
     * <p>Assumptions: the refusals are reported INSIDE an otherwise successful response rather than as a
     * thrown exception, because that is how the store reports them and it is the case the previous
     * implementation could not see. A stub that threw instead would exercise the transport-failure branch
     * and leave the partial-failure branch unexecuted.</p>
     */
    private final List<S3Error> stagedDeleteErrors = new ArrayList<>();

    /** The exception every batched deletion raises, or {@code null} when none is staged. */
    private SdkException stagedDeleteFailure;

    /**
     * The creation timestamp the most recently built stub reports for each key it holds.
     *
     * <p>Assumptions: a timestamp is recorded per WRITE and increases with every write, which is what a
     * real store does and what makes the retention ordering observable at all. The service orders the
     * retained window by the creation timestamp of each generation's claim marker, so a stub that
     * reported no timestamp -- which is what {@code GetObjectResponse.builder().build()} answers -- would
     * leave every generation at the service's undated sentinel and the ordering would be decided
     * entirely by its tie-break. Every case that discriminates between the two orderings would then pass
     * whichever ordering the service used.</p>
     *
     * <p>Assumptions: this is a SEPARATE registry from the body map rather than a richer value in it,
     * for the same reason the version registry is: two cases need a key to hold a body and NO timestamp,
     * which is the state an emulator standing in for the store can legitimately report.</p>
     */
    private final Map<String, Instant> storedTimestamps = new LinkedHashMap<>();

    /**
     * Whether the most recently built stub records a creation timestamp for the objects it holds.
     *
     * <p>Assumptions: the flag exists so that one fixture can stage both a store that timestamps its
     * objects and a store that reports none, without a second listing or write implementation. Two
     * implementations would mean the undated cases exercised a different stub from every other case, and
     * a defect in the real one could hide behind that.</p>
     */
    private boolean stubReportsTimestamps = true;

    /**
     * The claim-marker key whose read fails with a server fault, or {@code null} when none is staged.
     *
     * <p>Assumptions: the staged failure is a 500 rather than a 404, because the two outcomes are
     * required to differ: absence is evidence that orders a generation at the sentinel, while a refusal
     * is a fault that must stop the retention decision rather than default it -- defaulting would order a
     * live generation first for deletion on a transient read failure.</p>
     */
    private String failingClaimKey;

    /**
     * Supplies the next synthetic version identifier, so each write lands on its own version.
     *
     * <p>Assumptions: the identifiers are monotonic and distinct across the whole fixture rather than per
     * key, which is what the real store does. Restarting the sequence per key would let two keys share an
     * identifier, and a deletion that named the wrong key with the right identifier would then appear to
     * succeed.</p>
     */
    private final AtomicInteger nextVersionOrdinal = new AtomicInteger();

    /**
     * How many further listings the most recently built stub is to fail before it starts answering.
     *
     * <p>Assumptions: the counter is held on the fixture rather than captured per stub so that the one
     * listing implementation below can consult it, instead of a second listing implementation existing
     * for the failing cases. Two implementations would mean the cases that exercise a failure exercised a
     * different listing from every other case, and a defect in the real one could hide behind that.</p>
     */
    private final AtomicInteger listingFailuresRemaining = new AtomicInteger();

    /**
     * The exception the staged listing failures raise, or {@code null} when none is staged.
     *
     * <p>Assumptions: the instance is retained rather than rebuilt per throw, because one case asserts the
     * translated exception's cause is THE instance the store raised. A freshly built equivalent would
     * compare unequal and the retention assertion would fail against a service that retained it.</p>
     */
    private SdkException stagedListingFailure;

    /**
     * Builds a stubbed object store whose listing reports exactly the supplied generations as present.
     *
     * <p>Assumptions: the stub is backed by a key-to-body map and the LISTING is DERIVED from that map,
     * rather than the listing being stubbed independently of the writes. That is what makes a generation
     * the service itself claimed visible to the service's next listing, which is the whole property the
     * durable reservation rests on -- a stub answering a fixed listing would report the number as free
     * again on the very next call and the reservation would appear to work while proving nothing.</p>
     *
     * <p>Assumptions: a staged generation is seeded by writing its CLAIM MARKER rather than by adding a
     * prefix to a listing, because that is exactly what a real staged generation holds. A generation
     * whose prefix existed with nothing beneath it is not a state the object store can be in: a prefix in
     * that store is an artefact of the keys under it and has no independent existence.</p>
     *
     * <p>Assumptions: the listing implements DELIMITER semantics -- each key under the requested prefix
     * is truncated at the first separator that follows it, and the results are deduplicated. The previous
     * form returned each staged generation's FULL key prefix to every listing, which happens to be
     * correct for a listing made at the date partition and is wrong for one made at the family root: the
     * family-root listing would answer with generation prefixes where a real store answers with date
     * prefixes, so the two-level walk could not be exercised at all.</p>
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
    private S3Client objectStoreHolding(List<DatasetGeneration> staged) {
        return objectStoreHolding(staged, RUN_ID, true);
    }

    /**
     * Builds a stubbed object store whose staged generations are claimed by a named run.
     *
     * <p>Assumptions: the owning run is a parameter because the retention decision withholds every
     * generation held by a run the service has allocated for. A case that seeded generations under the
     * identifier it then allocates under would have every one of them withheld, so the cases that settle
     * which generation ages out have to seed them as another run's work -- which is also what they are:
     * the generations a family already holds were staged by earlier runs.</p>
     *
     * <p>Assumptions: the timestamp switch is a parameter for the complementary reason. A store that
     * reports creation timestamps exercises the allocation ordering; a store that reports none collapses
     * that ordering onto its partition-date tie-break, which is the only arrangement in which the
     * run-identity protection can be observed doing the work on its own.</p>
     *
     * @param staged the generations the store is to report as already present, in staging order; an
     *     empty list stages a store holding no generation at all
     * @param owningRunId the run identifier each staged generation's claim marker carries; must not be
     *     {@code null}
     * @param withTimestamps whether the store reports a creation timestamp for each object it holds
     * @return the stubbed object store, never {@code null}
     */
    private S3Client objectStoreHolding(List<DatasetGeneration> staged, String owningRunId,
            boolean withTimestamps) {

        this.storedObjects.clear();
        // WHY : Assumptions: the version registries and the deletion staging are cleared here alongside
        //       the body map, for the same reason it is: a case that seeds versions or stages a refusal
        //       must not leak either into the next case, or an outcome becomes a function of the order
        //       the cases happened to run in.
        this.storedVersions.clear();
        this.storedDeleteMarkers.clear();
        this.deleteRequests.clear();
        this.stagedDeleteErrors.clear();
        this.stagedDeleteFailure = null;
        this.nextVersionOrdinal.set(0);

        // WHY : Assumptions: the failure staging is cleared here rather than only set by the failing
        //       builder, so that "a plain store never fails" holds by construction. Leaving a previous
        //       case's staging in place would make a later case's outcome depend on the order the cases
        //       happened to run in, which is the one property a fixture must never have.
        this.listingFailuresRemaining.set(0);
        this.stagedListingFailure = null;

        // WHY : Assumptions: the timestamp registry, the timestamp switch and the staged claim-read
        //       failure are cleared here alongside every other staging, for the reason the block above
        //       gives: a case that stages any of them must not leak it into the next, or an outcome
        //       becomes a function of the order the cases happened to run in.
        this.storedTimestamps.clear();
        this.stubReportsTimestamps = withTimestamps;
        this.failingClaimKey = null;

        for (DatasetGeneration generation : staged) {
            storeObject(generation.keyPrefix() + DatasetGenerationService.CLAIM_OBJECT_NAME,
                    owningRunId);
        }

        S3Client objectStore = mock(S3Client.class);

        when(objectStore.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
                .thenAnswer(call -> new ListObjectsV2Iterable(objectStore, call.getArgument(0)));

        when(objectStore.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(call -> {
            raiseAnyStagedListingFailure();

            String requestedPrefix = call.<ListObjectsV2Request>getArgument(0).prefix();
            String delimiter = call.<ListObjectsV2Request>getArgument(0).delimiter();

            // WHY : Assumptions: the child is computed by truncating at the first delimiter AFTER the
            //       requested prefix, which is the rule the object store itself applies. No segment
            //       marker is spelled here, so the service is read back through the convention the
            //       coordinate record publishes rather than through one this file restates.
            Set<String> children = new LinkedHashSet<>();
            for (String key : this.storedObjects.keySet()) {
                if (!key.startsWith(requestedPrefix)) {
                    continue;
                }
                int boundary = key.indexOf(delimiter, requestedPrefix.length());
                if (boundary >= 0) {
                    children.add(key.substring(0, boundary + delimiter.length()));
                }
            }

            return ListObjectsV2Response.builder()
                    .isTruncated(false)
                    .commonPrefixes(children.stream()
                            .map(child -> CommonPrefix.builder().prefix(child).build())
                            .toList())
                    .build();
        });

        installObjectAccess(objectStore);

        return objectStore;
    }

    /**
     * Wires the read and the conditional write of a single object onto a stubbed store.
     *
     * <p>Assumptions: this wiring is shared by every builder below rather than repeated per builder,
     * because the durable reservation the service keeps is read through {@code getObjectAsBytes} on EVERY
     * allocation, including the allocations made by cases that are really about the listing. A builder
     * that omitted it would hand the service a read answering {@code null}, and the case would fail on a
     * dereference of that null rather than on the property it was written to settle.</p>
     *
     * <p>Assumptions: an absent key is reported as the store's own not-found exception rather than as an
     * empty body, because that is the distinction the service branches on -- it treats not-found as "this
     * run has recorded nothing yet" and treats any other failure as a fault. A stub returning an empty
     * body would drive the parse branch instead and would report a corrupt record where there is none.</p>
     *
     * @param objectStore the stubbed store to wire; must not be {@code null}
     */
    private void installObjectAccess(S3Client objectStore) {
        when(objectStore.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(call -> {
                    PutObjectRequest request = call.getArgument(0);

                    // WHY : Assumptions: the conditional guard is honoured by the stub rather than
                    //       ignored, because the guard IS the mechanism under test. A stub that accepted
                    //       every write would let two runs both claim one generation and the case that
                    //       proves they cannot would pass without exercising anything.
                    if (request.ifNoneMatch() != null
                            && this.storedObjects.containsKey(request.key())) {
                        throw S3Exception.builder()
                                .statusCode(PRECONDITION_FAILED_STATUS)
                                .message("stubbed object store: key already exists")
                                .build();
                    }

                    storeObject(request.key(), bodyOf(call.getArgument(1)));
                    return PutObjectResponse.builder().build();
                });

        installVersionAwareRetention(objectStore);

        when(objectStore.getObjectAsBytes(any(GetObjectRequest.class))).thenAnswer(call -> {
            String key = call.<GetObjectRequest>getArgument(0).key();

            // WHY : Assumptions: the staged fault is raised before the body lookup, because the case it
            //       serves stages a key that EXISTS. A refusal over a key holding nothing would be
            //       indistinguishable from absence and would settle nothing about how a fault is
            //       treated.
            if (key.equals(this.failingClaimKey)) {
                throw S3Exception.builder()
                        .statusCode(SERVER_FAULT_STATUS)
                        .message("stubbed object store: the read was refused")
                        .build();
            }

            String body = this.storedObjects.get(key);
            if (body == null) {
                throw NoSuchKeyException.builder()
                        .statusCode(NOT_FOUND_STATUS)
                        .message("stubbed object store: no such key")
                        .build();
            }

            // WHY : Assumptions: the response carries the recorded creation timestamp, which is what the
            //       retention ordering reads, and carries none when the fixture staged none. Building
            //       the response with no timestamp unconditionally -- which is what this stub did -- made
            //       every generation undated and left the ordering decided by its tie-break alone.
            return ResponseBytes.fromByteArray(
                    GetObjectResponse.builder().lastModified(this.storedTimestamps.get(key)).build(),
                    body.getBytes(StandardCharsets.UTF_8));
        });
    }

    /**
     * Records one write into both the body map and the version history, as a versioned bucket does.
     *
     * <p>Assumptions: a write that overwrites a key ADDS a version rather than replacing one, because
     * that is what a versioned bucket does and it is the only arrangement under which a retention pass
     * can be wrong. On an unversioned store every key holds one thing and a key-addressed delete removes
     * it, so a version-blind retention pass would pass every test; the history is what makes the
     * difference between removing the bytes and hiding them observable at all.</p>
     *
     * @param key the object key written; must not be {@code null}
     * @param body the body written; must not be {@code null}
     */
    private void storeObject(String key, String body) {
        this.storedObjects.put(key, body);
        int ordinal = this.nextVersionOrdinal.incrementAndGet();
        this.storedVersions
                .computeIfAbsent(key, absent -> new ArrayList<>())
                .add("version-" + ordinal);

        // WHY : Assumptions: the timestamp is derived from the same write ordinal the version identifier
        //       is, so creation order equals write order exactly as it does in the store being stood in
        //       for -- and a case can therefore stage a family whose creation order DISAGREES with its
        //       date order simply by writing the generations in that order. Deriving both from one
        //       counter is what keeps the two registries from telling different stories about which
        //       write came first.
        if (this.stubReportsTimestamps) {
            this.storedTimestamps.put(key, FIRST_WRITE_INSTANT.plusSeconds(ordinal));
        }
    }

    /**
     * Seeds one generation's reservation marker under a named owning run, after the store was built.
     *
     * <p>Assumptions: this seeds AFTER the builder rather than through it, because the builder stages
     * every generation it is given under one owner and two of the cases below need a family holding
     * generations owned by DIFFERENT runs. That is not a contrived arrangement: it is exactly what a
     * family looks like after a redrive, where one generation belongs to the run being retried and the
     * rest to the runs of earlier nights.</p>
     *
     * <p>Assumptions: seeding after the build works because the listing stub reads the body map as a live
     * view rather than a snapshot, and it is what makes the seeded generation the most recently created
     * one -- the write ordinal, and with it the reported creation instant, advances on every write.</p>
     *
     * @param generation the generation the marker reserves; must not be {@code null}
     * @param owningRunId the run identifier the marker's body carries; must not be {@code null}
     */
    private void stageClaimFor(DatasetGeneration generation, String owningRunId) {
        storeObject(generation.keyPrefix() + DatasetGenerationService.CLAIM_OBJECT_NAME, owningRunId);
    }

    /**
     * Seeds one generation that holds a staged object but carries no reservation marker.
     *
     * <p>Assumptions: a generation in this state is a real one rather than a defensive invention. A
     * component that writes into this key shape without reserving anything leaves exactly this -- the
     * reporting service's on-demand artifacts are documented as relying on the retention lambda rather
     * than on a reservation -- and so does a generation whose marker an earlier version-blind prune
     * removed while leaving its data behind.</p>
     *
     * <p>Assumptions: the object staged is given a name no reader of this file could confuse with the
     * marker, because the whole point of the state is the marker's ABSENCE. The listing discovers the
     * generation from this object alone, which is what the object store does: a prefix exists because a
     * key beneath it does.</p>
     *
     * @param generation the generation to stage without a reservation; must not be {@code null}
     */
    private void stageGenerationWithoutClaim(DatasetGeneration generation) {
        storeObject(generation.keyPrefix() + "part-0001.dat", "bytes staged with no reservation");
    }

    /**
     * Arranges for the read of one generation's reservation marker to be refused by the store.
     *
     * <p>Assumptions: the refusal names a marker that EXISTS, because a refusal over a key holding
     * nothing would be indistinguishable from absence and would settle nothing. The distinction under
     * test is that absence is evidence -- it orders the generation at the sentinel -- while a refusal is
     * a fault that must stop the decision rather than default it.</p>
     *
     * @param generation the generation whose marker read is to be refused; must not be {@code null}
     */
    private void failTheClaimReadOf(DatasetGeneration generation) {
        this.failingClaimKey = generation.keyPrefix() + DatasetGenerationService.CLAIM_OBJECT_NAME;
    }

    /**
     * Seeds one delete marker over a key, as a version-blind delete would have left behind.
     *
     * @param key the object key the marker covers; must not be {@code null}
     */
    private void storeDeleteMarker(String key) {
        this.storedDeleteMarkers
                .computeIfAbsent(key, absent -> new ArrayList<>())
                .add("marker-" + this.nextVersionOrdinal.incrementAndGet());
    }

    /**
     * Wires the version listing and the version-addressed batched deletion onto a stubbed store.
     *
     * <p>Assumptions: the listing PAGINATES at the store's own thousand-entry ceiling and drives the
     * continuation through the key and version markers the response carries, rather than answering every
     * entry in one page. Answering in one page was the simpler stub and was rejected: the retention pass
     * both consumes a paginator and batches its deletions at the same ceiling, so a single-page stub
     * would leave the continuation unexercised and would make a generation larger than one page look
     * like one the pass handles correctly.</p>
     *
     * <p>Assumptions: a deletion REMOVES the named identifiers from the registries rather than merely
     * being recorded, so a second retention pass over the same prefix finds it empty. A stub that only
     * recorded would make the pass look idempotent whether or not it had deleted anything.</p>
     *
     * @param objectStore the stubbed store to wire; must not be {@code null}
     */
    private void installVersionAwareRetention(S3Client objectStore) {
        when(objectStore.listObjectVersionsPaginator(any(ListObjectVersionsRequest.class)))
                .thenAnswer(call -> new ListObjectVersionsIterable(objectStore, call.getArgument(0)));

        when(objectStore.listObjectVersions(any(ListObjectVersionsRequest.class))).thenAnswer(call -> {
            raiseAnyStagedListingFailure();

            ListObjectVersionsRequest request = call.getArgument(0);
            List<Object[]> entries = versionEntriesUnder(request.prefix());

            int from = 0;
            if (request.keyMarker() != null) {
                for (int index = 0; index < entries.size(); index++) {
                    if (request.keyMarker().equals(entries.get(index)[0])
                            && request.versionIdMarker().equals(entries.get(index)[1])) {
                        from = index + 1;
                        break;
                    }
                }
            }

            List<Object[]> page = entries.subList(from, Math.min(from + VERSION_PAGE_SIZE, entries.size()));
            boolean truncated = from + page.size() < entries.size();

            ListObjectVersionsResponse.Builder response = ListObjectVersionsResponse.builder()
                    .isTruncated(truncated)
                    .versions(page.stream()
                            .filter(entry -> !(boolean) entry[2])
                            .map(entry -> ObjectVersion.builder()
                                    .key((String) entry[0])
                                    .versionId((String) entry[1])
                                    .build())
                            .toList())
                    .deleteMarkers(page.stream()
                            .filter(entry -> (boolean) entry[2])
                            .map(entry -> DeleteMarkerEntry.builder()
                                    .key((String) entry[0])
                                    .versionId((String) entry[1])
                                    .build())
                            .toList());

            if (truncated && !page.isEmpty()) {
                Object[] last = page.get(page.size() - 1);
                response.nextKeyMarker((String) last[0]).nextVersionIdMarker((String) last[1]);
            }
            return response.build();
        });

        when(objectStore.deleteObjects(any(DeleteObjectsRequest.class))).thenAnswer(call -> {
            if (this.stagedDeleteFailure != null) {
                throw this.stagedDeleteFailure;
            }

            DeleteObjectsRequest request = call.getArgument(0);
            List<ObjectIdentifier> named = List.copyOf(request.delete().objects());
            this.deleteRequests.add(named);

            for (ObjectIdentifier identifier : named) {
                removeVersion(this.storedVersions, identifier);
                removeVersion(this.storedDeleteMarkers, identifier);
                if (!this.storedVersions.containsKey(identifier.key())) {
                    this.storedObjects.remove(identifier.key());
                }
            }

            return DeleteObjectsResponse.builder().errors(List.copyOf(this.stagedDeleteErrors)).build();
        });
    }

    /**
     * Removes one named version from a version registry, dropping the key when its last one goes.
     *
     * <p>Assumptions: the key is dropped once it holds no versions, because a key with an empty history
     * is not a state the store can be in and leaving one would make a scratched prefix still list.</p>
     *
     * @param registry the registry to remove from; must not be {@code null}
     * @param identifier the key and version the deletion named; must not be {@code null}
     */
    private static void removeVersion(
            Map<String, List<String>> registry, ObjectIdentifier identifier) {

        List<String> held = registry.get(identifier.key());
        if (held == null) {
            return;
        }
        held.remove(identifier.versionId());
        if (held.isEmpty()) {
            registry.remove(identifier.key());
        }
    }

    /**
     * Renders every version and delete marker under one prefix, in the order the store reports them.
     *
     * <p>Assumptions: the entries are ordered by KEY and then by version, which is the order the store
     * lists them in and the order the marker-driven continuation depends on. An unordered answer would
     * make the continuation pick an arbitrary resume point and the pagination case would be flaky rather
     * than wrong, which is worse.</p>
     *
     * @param prefix the prefix to list beneath; must not be {@code null}
     * @return one entry per version or marker, each holding the key, the version identifier and whether
     *     it is a delete marker, never {@code null}
     */
    private List<Object[]> versionEntriesUnder(String prefix) {
        List<Object[]> entries = new ArrayList<>();
        Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(this.storedVersions.keySet());
        keys.addAll(this.storedDeleteMarkers.keySet());

        for (String key : keys) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            for (String versionId : this.storedVersions.getOrDefault(key, List.of())) {
                entries.add(new Object[] {key, versionId, false});
            }
            for (String versionId : this.storedDeleteMarkers.getOrDefault(key, List.of())) {
                entries.add(new Object[] {key, versionId, true});
            }
        }
        return entries;
    }

    /**
     * Raises the staged listing failure if any listing failures are still owed, and counts one off.
     *
     * <p>Assumptions: the failure is raised from the request-response boundary rather than from the
     * paginator factory, so it travels the path a real transport failure travels -- the service consumes a
     * real paginator inside its own {@code try} block, and the throw happens during that traversal.
     * Throwing from the factory instead would bypass the block and the translation under test would never
     * run.</p>
     */
    private void raiseAnyStagedListingFailure() {
        if (this.stagedListingFailure != null && this.listingFailuresRemaining.get() > 0) {
            this.listingFailuresRemaining.decrementAndGet();
            throw this.stagedListingFailure;
        }
    }

    /**
     * Builds a stubbed store whose listing answers exactly the supplied child prefixes, verbatim.
     *
     * <p>Assumptions: this builder exists BESIDE {@link #objectStoreHolding(List)} rather than replacing
     * it, and the two are used for different questions. The derived builder can only ever produce children
     * that the coordinate record itself rendered, so it cannot stage a child the renderer never wrote --
     * which is precisely what the four rejection cases need. This builder can stage anything, and is
     * therefore the wrong tool for every case about what the service WROTE.</p>
     *
     * <p>Assumptions: a child that lies under the requested prefix is truncated at the delimiter that
     * follows it -- the rule a real store applies -- while a child that lies OUTSIDE the requested prefix
     * is returned unchanged. Filtering the outside child away instead would make one case below
     * unwriteable: a real store would not return a foreign family's child, so the service's own partition
     * check could only be exercised by a listing that deliberately breaks that guarantee.</p>
     *
     * <p>Assumptions: a child already closed by the delimiter is returned unchanged rather than truncated
     * to itself, so a child closed by NO delimiter reaches the service intact. That is one of the four
     * shapes the service rejects, and a stub that quietly dropped it would let the case pass while the
     * rejection it names went unexecuted.</p>
     *
     * @param childPrefixes the children every listing is to report, in the order supplied
     * @return the stubbed object store, never {@code null}
     */
    private S3Client objectStoreListingLiterally(List<String> childPrefixes) {
        S3Client objectStore = objectStoreHolding(List.of());

        when(objectStore.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(call -> {
            raiseAnyStagedListingFailure();

            String requestedPrefix = call.<ListObjectsV2Request>getArgument(0).prefix();
            String delimiter = call.<ListObjectsV2Request>getArgument(0).delimiter();

            Set<String> children = new LinkedHashSet<>();
            for (String child : childPrefixes) {
                if (!child.startsWith(requestedPrefix)) {
                    children.add(child);
                    continue;
                }
                int boundary = child.indexOf(delimiter, requestedPrefix.length());
                boolean closesTheChild = boundary < 0
                        || boundary == child.length() - delimiter.length();
                children.add(closesTheChild
                        ? child
                        : child.substring(0, boundary + delimiter.length()));
            }

            return ListObjectsV2Response.builder()
                    .isTruncated(false)
                    .commonPrefixes(children.stream()
                            .map(child -> CommonPrefix.builder().prefix(child).build())
                            .toList())
                    .build();
        });

        return objectStore;
    }

    /**
     * Builds a stubbed store whose listing fails a stated number of times and then answers normally.
     *
     * <p>Assumptions: the failure count is a parameter rather than the builder always failing, because the
     * property worth asserting is not that a failure is reported -- it is that a RETRY after a failure
     * still allocates. A store that always failed could only ever show the first half of that.</p>
     *
     * <p>Assumptions: the successful answers come from {@link #objectStoreHolding(List)} unchanged, so the
     * recovered listing is the SAME derived listing every other case reads, and the writes the retry makes
     * are visible to the listing that follows them. A separately stubbed recovery listing would report the
     * claimed generation as free again and the retry would appear to work while proving nothing.</p>
     *
     * @param failuresBeforeSuccess how many consecutive listings fail before the first one succeeds; zero
     *     stages a store that never fails
     * @param failure the exception each failing listing raises; must be an SDK exception, since that is
     *     the only kind the service undertakes to translate
     * @param staged the generations the store reports once it starts succeeding, in any order
     * @return the stubbed object store, never {@code null}
     */
    private S3Client objectStoreFailingThenHolding(int failuresBeforeSuccess,
            SdkException failure, List<DatasetGeneration> staged) {

        S3Client objectStore = objectStoreHolding(staged);

        // WHY : Assumptions: the staging is applied AFTER the builder runs, because the builder clears it
        //       on entry so that a plain store cannot inherit a previous case's failures. Setting it first
        //       would be silently undone and every failing case would pass against a store that never
        //       failed.
        this.stagedListingFailure = failure;
        this.listingFailuresRemaining.set(failuresBeforeSuccess);

        return objectStore;
    }

    /**
     * Reads a request body back as the string the service wrote into it.
     *
     * <p>Assumptions: the body is drained through the kit's own content-stream provider rather than
     * through any accessor for the original string, because a request body carries a stream provider and
     * not the value it was built from. Draining once is safe here because each stub write consumes its
     * body exactly once.</p>
     *
     * @param body the request body the service supplied; must not be {@code null}
     * @return the body's bytes decoded as text, never {@code null}
     * @throws IllegalStateException if the body's stream cannot be read, which would mean the stub was
     *     handed a body backed by something other than the in-memory content this service writes
     */
    private static String bodyOf(RequestBody body) {
        try (InputStream content = body.contentStreamProvider().newStream()) {
            return new String(content.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException("a stubbed request body could not be read", unreadable);
        }
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
     * <p>Assumptions: a fresh service is built per case rather than shared, because a store shared across
     * cases would carry one case's claim markers into the next. The reservation an allocation records is
     * DURABLE -- it is an object in the store rather than an entry in a per-instance table -- which is
     * what a case below builds a SECOND instance over one store to demonstrate.</p>
     *
     * <p>Assumptions: a fresh instance does hold one piece of per-instance state, the record of what THIS
     * process has allocated, and it is empty on a new instance by design. That is why the redrive case
     * below has its second instance allocate before it asks about retention: the protection that has to
     * survive a fresh container is the one read from the durable marker's body, and an instance that had
     * never been told a run identifier could not exercise it.</p>
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
         * A fresh service instance returns the SAME generation, because the reservation is durable.
         *
         * <p>Pins the same one-job scope as {@code app/jcl/COMBTRAN.jcl:37} and {@code :44}, and it is
         * exactly the scope a process-local table could not hold. The two references a job makes to one
         * family are not guaranteed to occur in one container: a batch step runs as a task invoked from a
         * {@code Map} state that the orchestrator may redrive, so the reading reference can legitimately
         * execute in a fresh process. It must still address the generation the writing reference
         * created.</p>
         *
         * <p>Refactoring Rationale: this case previously asserted the OPPOSITE -- that a fresh instance
         * allocates afresh -- and passed, because the allocation was memoised in a field. That assertion
         * described the defect rather than the requirement: a retried branch would allocate a second
         * generation, write to a prefix the earlier attempt's reader was not looking at, and consume the
         * five-generation retention window at twice the intended rate. The store is shared across both
         * instances here, which is what makes the durable record the only thing the second instance can
         * be reading.</p>
         *
         * <p>Assumptions: the second instance issues no listing at all, which is asserted rather than
         * inferred. A durable record that was read but then ignored would still return the right
         * coordinate while allocating again underneath, and only the listing count distinguishes the
         * two.</p>
         */
        @Test
        @DisplayName("return the recorded generation on a fresh instance in the same run")
        void freshInstanceReadsTheDurableReservation() {
            S3Client objectStore = objectStoreHolding(
                    List.of(generation(DatasetFamily.DALYREJS, 2)));

            DatasetGeneration first = serviceOver(objectStore)
                    .allocateNewGeneration(DatasetFamily.DALYREJS, BUSINESS_DATE, RUN_ID);
            DatasetGeneration afterRestart = serviceOver(objectStore)
                    .allocateNewGeneration(DatasetFamily.DALYREJS, BUSINESS_DATE, RUN_ID);

            assertThat(afterRestart).isEqualTo(first);
            assertThat(first.generationNumber()).isEqualTo(3);
            verify(objectStore, times(1)).listObjectsV2Paginator(any(ListObjectsV2Request.class));
        }

        /**
         * A repeat allocation is non-fatal and writes nothing further, the tolerated-redefine analogue.
         *
         * <p>Pins {@code app/jcl/DEFGDGB.jcl:29}, {@code :35}, {@code :41}, {@code :47}, {@code :53} and
         * {@code :59}, each of which follows a base definition with a step that clears the condition code
         * so that an already-exists outcome is tolerated rather than failing the job.</p>
         *
         * <p>Refactoring Rationale: this case previously asserted that the service writes NO object at
         * all, on the stated ground that it provisions nothing. That ground held while the reservation was
         * a field and cannot hold now: a reservation is durable only if it is written down. What the case
         * asserts instead is the property that actually matters -- the FIRST allocation writes exactly the
         * two reservation markers and the repeat writes nothing further -- so a repeat that quietly
         * allocated again would be caught by the write count rather than by a claim that no write occurs.
         * The service still provisions no bucket, no prefix and no lifecycle rule, and still deletes
         * nothing.</p>
         */
        @Test
        @DisplayName("tolerate a repeat request without writing a second reservation")
        void repeatAllocationIsNonFatalAndWritesNothingFurther() {
            S3Client objectStore = objectStoreHolding(
                    List.of(generation(DatasetFamily.TCATBALF_BKUP, 1)));
            DatasetGenerationService service = serviceOver(objectStore);

            DatasetGeneration first = service.allocateNewGeneration(
                    DatasetFamily.TCATBALF_BKUP, BUSINESS_DATE, RUN_ID);
            DatasetGeneration repeated = service.allocateNewGeneration(
                    DatasetFamily.TCATBALF_BKUP, BUSINESS_DATE, RUN_ID);

            assertThat(repeated).isEqualTo(first);
            verify(objectStore, times(2))
                    .putObject(any(PutObjectRequest.class), any(RequestBody.class));
            assertThat(DatasetGenerationServiceTest.this.storedObjects)
                    .containsKey(first.keyPrefix() + DatasetGenerationService.CLAIM_OBJECT_NAME);
            assertThat(DatasetGenerationServiceTest.this.storedObjects.keySet())
                    .anySatisfy(key -> assertThat(key)
                            .startsWith(DatasetGenerationService.RUN_CLAIM_ROOT));
        }

        /**
         * The first generation of an empty family is one, never zero.
         *
         * <p>Pins the reference baseline's own first generation. Every relative creation in the tree is
         * spelled {@code (+1)} -- {@code app/jcl/TRANBKP.jcl:33} and {@code app/jcl/INTCALC.jcl:41} among
         * fifteen such references -- and the catalog resolved the first of them to {@code G0001V00}. There
         * is no generation zero to resolve to.</p>
         *
         * <p>Refactoring Rationale: this ruling was previously the opposite. The coordinate type admitted
         * zero as its minimum and an empty partition therefore allocated a {@code gen=0000} prefix, which
         * the sibling stager refuses to write at
         * {@code data-migration/src/carddemo_migration/loaders/s3_stage.py:1010-1044} and which its own
         * first-generation answer at {@code loaders/s3_stage.py:1328} contradicts. Two components writing
         * one bucket disagreed about what the first generation is called, so a Java step's first write
         * landed at a prefix the Python verification pass did not consider a generation at all.</p>
         */
        @Test
        @DisplayName("allocate generation one into an empty family")
        void emptyFamilyAllocatesGenerationOne() {
            DatasetGeneration allocated = serviceOver(objectStoreHolding(List.of()))
                    .allocateNewGeneration(DatasetFamily.TRANSACT_DALY, BUSINESS_DATE, RUN_ID);

            assertThat(allocated.generationNumber())
                    .isEqualTo(DatasetGeneration.MINIMUM_GENERATION_NUMBER)
                    .isEqualTo(1);
            assertThat(allocated.generationSegment()).endsWith("0001");
        }

        /**
         * A generation claimed by another writer between the listing and the write is not taken twice.
         *
         * <p>Pins the property a process-local reservation cannot have. The nightly chain stages through a
         * {@code Map} state running one containerised branch per dataset and the orchestrator may redrive a
         * branch, so two writers can list the same family within one window. The conditional write is what
         * decides between them, and the loser must re-list rather than proceed with a number it does not
         * hold.</p>
         *
         * <p>Assumptions: the concurrent writer is simulated by claiming the next number through a SECOND
         * service instance under a DIFFERENT run identifier, rather than by writing a key this file
         * spells. Spelling the key would restate the reservation convention in a test, and the case would
         * then prove the service agrees with this file rather than with itself.</p>
         */
        @Test
        @DisplayName("re-list and take the next free number when a claim is lost")
        void aLostClaimIsRetriedAtTheNextFreeNumber() {
            S3Client objectStore = objectStoreHolding(
                    List.of(generation(DatasetFamily.SYSTRAN, 1)));

            DatasetGeneration otherRun = serviceOver(objectStore)
                    .allocateNewGeneration(DatasetFamily.SYSTRAN, BUSINESS_DATE, OTHER_RUN_ID);
            DatasetGeneration thisRun = serviceOver(objectStore)
                    .allocateNewGeneration(DatasetFamily.SYSTRAN, BUSINESS_DATE, RUN_ID);

            assertThat(otherRun.generationNumber()).isEqualTo(2);
            assertThat(thisRun.generationNumber()).isEqualTo(3);
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
         * <p>Assumptions: the decisive assertion is the one AFTER the read. If reading had recorded a
         * reservation, the following allocation would have answered from it and returned the generation
         * that already exists; it returns one past it instead, which is what proves the read reserved
         * nothing. Nothing else the read returns could show this.</p>
         */
        @Test
        @DisplayName("allocate nothing and reserve nothing for the run")
        void readingCurrentGenerationDoesNotAllocate() {
            S3Client objectStore = objectStoreHolding(
                    List.of(generation(DatasetFamily.TRANSACT_BKUP, 3)));
            DatasetGenerationService service = serviceOver(objectStore);

            Optional<DatasetGeneration> current =
                    service.resolveCurrentGeneration(DatasetFamily.TRANSACT_BKUP);

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
                    .resolveCurrentGeneration(DatasetFamily.SYSTRAN)
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
         * A family holding no generation is reported as absent rather than as a first generation.
         *
         * <p>Pins the two reads at {@code app/jcl/COMBTRAN.jcl:24} and {@code :26}, which name inputs the
         * combine job did not produce. Answering an unwritten family with a coordinate would send the
         * merge at a prefix nothing was ever staged under and yield an empty output indistinguishable from a
         * successful merge of empty inputs.</p>
         *
         * <p>Assumptions: absence and a first generation are different answers, so an empty result rather
         * than a substituted minimum is the correct report, and it is why this case asserts emptiness
         * rather than a number.</p>
         */
        @Test
        @DisplayName("report an unwritten family as absent")
        void unwrittenFamilyIsReportedAbsent() {
            DatasetGenerationService service = serviceOver(objectStoreHolding(List.of()));

            assertThat(service.resolveCurrentGeneration(DatasetFamily.SYSTRAN)).isEmpty();
        }

        /**
         * The current generation is found under an EARLIER business date when today holds none.
         *
         * <p>Pins {@code app/jcl/COMBTRAN.jcl:24} and {@code :26} at the one moment the date scope decides
         * the answer: the first run of a new business day. The catalog those two references were resolved
         * against held one generation sequence per base and knew nothing of dates, so {@code (0)} named the
         * newest generation of the base however long ago it was written.</p>
         *
         * <p>Refactoring Rationale: a date-scoped resolution answered EMPTY here, and empty is what the
         * combine flow reads as "this input does not exist yet". The merge then ran over one input instead
         * of two and produced a short output that no return code distinguished from a correct one -- which
         * is precisely the class of defect a golden-master comparison catches only if a fixture happens to
         * straddle midnight.</p>
         */
        @Test
        @DisplayName("find the current generation under an earlier date partition")
        void currentGenerationSpansBusinessDates() {
            DatasetGenerationService service = serviceOver(objectStoreHolding(List.of(
                    new DatasetGeneration(DatasetFamily.TRANSACT_BKUP, EARLIER_BUSINESS_DATE, 4))));

            DatasetGeneration current =
                    service.resolveCurrentGeneration(DatasetFamily.TRANSACT_BKUP).orElseThrow();

            assertThat(current.generationNumber()).isEqualTo(4);
            assertThat(current.businessDate()).isEqualTo(EARLIER_BUSINESS_DATE);
        }

        /**
         * The newest generation of the LATER date outranks a higher-numbered one of an earlier date.
         *
         * <p>Pins the catalog's own ordering, which is chronological. Two dates' sequences each start at
         * one, so a generation number compared on its own is not an ordering across a family at all.</p>
         *
         * <p>Refactoring Rationale: the ordering compared generation numbers alone, which ranked
         * generation nine of the earlier date above generation one of the later one. That is the wrong
         * answer to {@code (0)} and, in the retention rule, it kept the wrong five generations. The
         * sibling stager derives the same date-then-generation ordering from its coordinate's field order
         * at {@code data-migration/src/carddemo_migration/loaders/s3_stage.py:380-389}.</p>
         */
        @Test
        @DisplayName("rank a later date above a higher generation of an earlier date")
        void laterDateOutranksHigherEarlierGeneration() {
            DatasetGenerationService service = serviceOver(objectStoreHolding(List.of(
                    new DatasetGeneration(DatasetFamily.SYSTRAN, EARLIER_BUSINESS_DATE, 9),
                    new DatasetGeneration(DatasetFamily.SYSTRAN, BUSINESS_DATE, 1))));

            DatasetGeneration current =
                    service.resolveCurrentGeneration(DatasetFamily.SYSTRAN).orElseThrow();

            assertThat(current.businessDate()).isEqualTo(BUSINESS_DATE);
            assertThat(current.generationNumber()).isEqualTo(1);
        }
    }

    /**
     * Settles that the five-generation window counts across a family rather than within a date.
     *
     * <p>Purpose: every one of the ten bases is defined {@code LIMIT(5)} with {@code SCRATCH} on the
     * following line -- {@code app/jcl/DEFGDGB.jcl:26}, {@code :32}, {@code :38}, {@code :44}, {@code :50}
     * and {@code :56}, {@code app/jcl/DEFGDGD.jcl:29}, {@code :52} and {@code :75}, and
     * {@code app/jcl/DALYREJS.jcl:26}. The limit is a property of the BASE. A window counted per business
     * date would retain five generations for every day the family was staged on, so a family staged on six
     * days would hold thirty while every report of the rule still said five.</p>
     */
    @Nested
    @DisplayName("the retention window across a family")
    class FamilyWideRetention {

        /**
         * Six generations spread over two dates scratch exactly one, and it is the oldest overall.
         *
         * <p>Assumptions: the six are split across the boundary deliberately -- four under the earlier date
         * and two under the later one -- so that neither date on its own exceeds the window. A date-scoped
         * rule therefore scratches NOTHING here, which is what makes this case discriminate between the two
         * scopes rather than merely counting.</p>
         */
        @Test
        @DisplayName("count the window across dates and scratch the oldest overall")
        void retentionCountsAcrossDates() {
            List<DatasetGeneration> staged = new ArrayList<>();
            for (int number = 1; number <= 4; number++) {
                staged.add(new DatasetGeneration(
                        DatasetFamily.TRANSACT_BKUP, EARLIER_BUSINESS_DATE, number));
            }
            for (int number = 1; number <= 2; number++) {
                staged.add(new DatasetGeneration(DatasetFamily.TRANSACT_BKUP, BUSINESS_DATE, number));
            }

            List<DatasetGeneration> scratched = serviceOver(objectStoreHolding(staged))
                    .generationsToScratch(DatasetFamily.TRANSACT_BKUP);

            assertThat(scratched).containsExactly(
                    new DatasetGeneration(DatasetFamily.TRANSACT_BKUP, EARLIER_BUSINESS_DATE, 1));
        }

        /**
         * A family within the window scratches nothing, even when its generations span two dates.
         *
         * <p>Assumptions: this is the complement of the case above and is required rather than redundant.
         * A rule that scratched by position without first checking the size would return the whole earlier
         * date here, and only a case in which the correct answer is EMPTY can catch that.</p>
         */
        @Test
        @DisplayName("scratch nothing while a family stays within the window")
        void retentionScratchesNothingWithinTheWindow() {
            List<DatasetGeneration> staged = List.of(
                    new DatasetGeneration(DatasetFamily.DISCGRP_BKUP, EARLIER_BUSINESS_DATE, 1),
                    new DatasetGeneration(DatasetFamily.DISCGRP_BKUP, EARLIER_BUSINESS_DATE, 2),
                    new DatasetGeneration(DatasetFamily.DISCGRP_BKUP, BUSINESS_DATE, 1));

            assertThat(serviceOver(objectStoreHolding(staged))
                    .generationsToScratch(DatasetFamily.DISCGRP_BKUP)).isEmpty();
        }

        /**
         * The pure decision orders by date before generation number, oldest first.
         *
         * <p>Assumptions: the pure decision is driven directly here, with the coordinates supplied out of
         * order, because the family-wide overload above reads them from a listing that happens to arrive
         * ordered. An ordering defect masked by an already-ordered input is exactly what a shuffled input
         * exposes.</p>
         */
        @Test
        @DisplayName("order the scratch list by date before generation number")
        void scratchListIsOrderedByDateThenGeneration() {
            List<DatasetGeneration> shuffled = List.of(
                    new DatasetGeneration(DatasetFamily.TRANREPT, BUSINESS_DATE, 2),
                    new DatasetGeneration(DatasetFamily.TRANREPT, EARLIER_BUSINESS_DATE, 7),
                    new DatasetGeneration(DatasetFamily.TRANREPT, BUSINESS_DATE, 1),
                    new DatasetGeneration(DatasetFamily.TRANREPT, EARLIER_BUSINESS_DATE, 6),
                    new DatasetGeneration(DatasetFamily.TRANREPT, EARLIER_BUSINESS_DATE, 8),
                    new DatasetGeneration(DatasetFamily.TRANREPT, EARLIER_BUSINESS_DATE, 5),
                    new DatasetGeneration(DatasetFamily.TRANREPT, BUSINESS_DATE, 3));

            assertThat(serviceOver(objectStoreHolding(List.of()))
                    .generationsToScratch(shuffled))
                    .containsExactly(
                            new DatasetGeneration(DatasetFamily.TRANREPT, EARLIER_BUSINESS_DATE, 5),
                            new DatasetGeneration(DatasetFamily.TRANREPT, EARLIER_BUSINESS_DATE, 6));
        }
    }

    /**
     * Settles that retention retires by CREATION SEQUENCE and never retires the running run's own work.
     *
     * <p>Purpose: retention ordered a family by the business date embedded in each key and retained the
     * newest five under that order. A run whose business date was older than the dates the family already
     * held therefore allocated a generation that sorted OLDEST, and the same staging call that had just
     * written bytes into it named it as aged out -- so a catch-up night for an earlier date destroyed its
     * own output while logging an allocation, a staging and a scratch that each looked correct. Every
     * case in this class stages a family whose creation order and date order DISAGREE, because that is
     * the only arrangement in which the two orderings can be told apart: the cases in the class above
     * stage generations in ascending date order, where the two coincide and either ordering passes.</p>
     *
     * <p>Assumptions: a generation data group retires by creation sequence and has no notion of the
     * content date its dataset carries, and its {@code (+1)} reference names the current generation by
     * definition. The two properties are settled separately here rather than together, because they are
     * independent defences and either alone leaves a case the other has to carry: the ordering protects a
     * back-dated generation whose creation instant separates it from the family, and the run-identity
     * exclusion protects it when no instant does.</p>
     */
    @Nested
    @DisplayName("retention ordered by allocation rather than by the date in the key")
    class AllocationOrderedRetention {

        /**
         * The family every case here exercises, the reject stream a posting run stages into.
         *
         * <p>Assumptions: this family is chosen because it is the one a posting run stages on every night
         * that produces a reject, so it is the family a back-dated catch-up run reaches first. Nothing in
         * these cases depends on which family it is -- the ten share one resolver -- but naming the one
         * the failure was observed against keeps the case readable against the report that found it.</p>
         */
        private final DatasetFamily family = DatasetFamily.DALYREJS;

        /**
         * The business date the back-dated run injects, older than every date the family holds.
         *
         * <p>Assumptions: the date is older than all five staged dates by more than a month, which is
         * what makes it a catch-up run rather than a boundary case. A date merely one day older would
         * settle the same ruling, but a reader could mistake it for the ordinary first-run-of-a-new-day
         * arrangement the class above stages.</p>
         */
        private final BusinessDate backDatedDate = new BusinessDate("2022-06-10");

        /**
         * The five business dates the family already holds, ascending, one generation each.
         *
         * <p>Assumptions: five is the retained count exactly, so a family holding these and nothing else
         * is at the window and scratches nothing. One further generation -- the one the run under test
         * allocates -- is what makes the rule bite, which is the smallest arrangement in which the choice
         * of victim is observable at all.</p>
         */
        private final List<BusinessDate> heldDates = List.of(
                new BusinessDate("2022-07-20"),
                new BusinessDate("2022-08-01"),
                new BusinessDate("2022-08-10"),
                new BusinessDate("2022-08-20"),
                new BusinessDate("2022-09-01"));

        /**
         * Builds the coordinate of generation one under one of the dates in play.
         *
         * @param date the business date the coordinate partitions under; must not be {@code null}
         * @return the coordinate of the first generation under that date, never {@code null}
         */
        private DatasetGeneration firstGenerationUnder(BusinessDate date) {
            return new DatasetGeneration(this.family, date, 1);
        }

        /**
         * Builds the coordinates the family already holds, one per held date, ascending.
         *
         * @return the five coordinates, ascending by date, never {@code null}
         */
        private List<DatasetGeneration> alreadyHeld() {
            return this.heldDates.stream().map(this::firstGenerationUnder).toList();
        }

        /**
         * A back-dated run's freshly allocated generation is not returned by its own retention pass.
         *
         * <p>Assumptions: this is the reported failure reproduced exactly -- a family holding five later
         * dates, a run injected with an earlier one, and the retention pass the staging step runs
         * immediately after it allocates. The assertion is in two parts on purpose. That the fresh
         * generation is absent is the property that matters; that the oldest generation the family held
         * is present is what stops the case passing against a pass that had simply stopped returning
         * anything, which would leave a family growing without limit and would look identical from the
         * fresh generation's point of view.</p>
         */
        @Test
        @DisplayName("withhold the generation the running run just allocated, and retire the oldest held")
        void aBackDatedAllocationIsNotItsOwnScratchCandidate() {
            List<DatasetGeneration> held = alreadyHeld();
            DatasetGenerationService service =
                    serviceOver(objectStoreHolding(held, OTHER_RUN_ID, true));

            DatasetGeneration allocated =
                    service.allocateNewGeneration(this.family, this.backDatedDate, RUN_ID);

            assertThat(allocated).isEqualTo(firstGenerationUnder(this.backDatedDate));
            assertThat(service.generationsToScratch(this.family))
                    .doesNotContain(allocated)
                    .containsExactly(held.get(0));
        }

        /**
         * The generation created least recently retires, even when it carries the family's LATEST date.
         *
         * <p>Assumptions: the staging order is chosen so that every date-based selector yields a
         * different wrong answer. The generation written first carries the latest date of the six, and
         * the generation carrying the earliest date is written second, so a pass ordering by date returns
         * the earliest-dated generation and a pass ordering by creation returns the latest-dated one.
         * Nothing about this input is ambiguous: exactly one generation is beyond the window under each
         * ordering, and the two are different generations.</p>
         *
         * <p>Assumptions: no allocation is made here, so the run-identity exclusion cannot contribute and
         * the ordering is settled on its own. A case that allocated would leave the ordering and the
         * exclusion both able to explain the outcome.</p>
         */
        @Test
        @DisplayName("retire the least recently created generation, not the earliest dated one")
        void allocationOrderOutranksTheDateInTheKey() {
            DatasetGeneration latestDated = firstGenerationUnder(this.heldDates.get(4));
            DatasetGeneration earliestDated = firstGenerationUnder(this.backDatedDate);
            List<DatasetGeneration> stagedOutOfDateOrder = List.of(
                    latestDated,
                    earliestDated,
                    firstGenerationUnder(this.heldDates.get(0)),
                    firstGenerationUnder(this.heldDates.get(1)),
                    firstGenerationUnder(this.heldDates.get(2)),
                    firstGenerationUnder(this.heldDates.get(3)));

            List<DatasetGeneration> scratched =
                    serviceOver(objectStoreHolding(stagedOutOfDateOrder, OTHER_RUN_ID, true))
                            .generationsToScratch(this.family);

            assertThat(scratched)
                    .doesNotContain(earliestDated)
                    .containsExactly(latestDated);
        }

        /**
         * With no creation instant to order by, the run's own generation is still withheld.
         *
         * <p>Assumptions: the store here reports no creation timestamp for anything, which collapses the
         * ordering onto its partition-date tie-break -- the very ordering that produced the failure. That
         * is deliberate: it is the arrangement in which the ordering CANNOT protect the fresh generation,
         * so whatever protects it is the run-identity exclusion and nothing else. Two generations claimed
         * within one timestamp granularity reach the decision in exactly this state.</p>
         *
         * <p>Assumptions: the expected result is empty rather than one entry, and that is the documented
         * trade-off rather than a gap. The family is left holding one more than the retained count for as
         * long as the allocating run is in flight, which the next pass retires; the alternative -- naming
         * a victim to make room -- would delete a generation the window says to keep.</p>
         */
        @Test
        @DisplayName("withhold the run's own generation when no timestamp separates the family")
        void runIdentityProtectsTheAllocationWhenNothingElseCan() {
            DatasetGenerationService service =
                    serviceOver(objectStoreHolding(alreadyHeld(), OTHER_RUN_ID, false));

            DatasetGeneration allocated =
                    service.allocateNewGeneration(this.family, this.backDatedDate, RUN_ID);

            assertThat(allocated).isEqualTo(firstGenerationUnder(this.backDatedDate));
            assertThat(service.generationsToScratch(this.family)).isEmpty();
        }

        /**
         * A redriven attempt keeps the generation its failed attempt allocated, in a fresh instance.
         *
         * <p>Assumptions: the second instance is built over the SAME store and is never told about the
         * generation directly -- it allocates for a DIFFERENT family, which is the only way it learns the
         * run identifier at all. The protection therefore comes from the durable marker's body rather
         * than from anything this process recorded, which is the property a redrive in a fresh container
         * depends on and the one no process-local record could provide.</p>
         *
         * <p>Assumptions: the family also holds an older generation belonging to another run, inside the
         * same aged-out slice, and it is asserted to be returned. Without it the case would pass against
         * a pass that withheld every candidate rather than the run's own, which is the failure mode a
         * protection rule is most likely to have.</p>
         */
        @Test
        @DisplayName("keep the redriven run's generation and still retire another run's older one")
        void aRedrivenAttemptKeepsWhatItsFailedAttemptAllocated() {
            DatasetGeneration otherRunsOldest =
                    new DatasetGeneration(this.family, new BusinessDate("2022-06-01"), 1);
            DatasetGeneration thisRunsAllocation = firstGenerationUnder(this.backDatedDate);
            S3Client store = objectStoreHolding(alreadyHeld(), OTHER_RUN_ID, false);
            stageClaimFor(otherRunsOldest, OTHER_RUN_ID);
            stageClaimFor(thisRunsAllocation, RUN_ID);

            DatasetGenerationService redriven = serviceOver(store);
            redriven.allocateNewGeneration(DatasetFamily.SYSTRAN, this.backDatedDate, RUN_ID);

            assertThat(redriven.generationsToScratch(this.family))
                    .doesNotContain(thisRunsAllocation)
                    .containsExactly(otherRunsOldest);
        }

        /**
         * A generation carrying no reservation retires first, whatever date its key carries.
         *
         * <p>Assumptions: the unreserved generation is given the family's LATEST date, so a pass ordering
         * by date would retain it and retire the oldest reserved one instead. That is what makes the case
         * discriminating rather than merely descriptive.</p>
         *
         * <p>Assumptions: retiring it first is the intended reading rather than a fallback. It is the
         * generation with the least evidence behind it, and it cannot be one the running run allocated,
         * because every allocation writes its reservation before any caller can stage a byte.</p>
         */
        @Test
        @DisplayName("retire a generation that carries no reservation ahead of every reserved one")
        void anUnreservedGenerationRetiresFirst() {
            DatasetGeneration unreserved =
                    new DatasetGeneration(this.family, new BusinessDate("2022-09-15"), 1);
            S3Client store = objectStoreHolding(alreadyHeld(), OTHER_RUN_ID, true);
            stageGenerationWithoutClaim(unreserved);

            assertThat(serviceOver(store).generationsToScratch(this.family))
                    .containsExactly(unreserved);
        }

        /**
         * A reservation the store refuses to read stops the decision rather than defaulting it.
         *
         * <p>Assumptions: the refusal is a fault rather than an absence, and the two must not be treated
         * alike. Defaulting a generation whose marker cannot be read would order it at the sentinel and
         * make it the FIRST candidate for deletion, so one transient read failure over a live generation
         * would become a permanent loss of its bytes. A failed step is retried by the state machine and
         * deletes nothing while it waits.</p>
         *
         * <p>Assumptions: the diagnosis is asserted rather than only the failure, because a retention
         * pass that stops has to say which generation it stopped on -- an operator reading the failure
         * has the whole family to choose from otherwise. The cause is asserted to be retained for the
         * same reason the listing-failure case above asserts it: the store's own reason is the only thing
         * that distinguishes an authorisation refusal from a key-management one.</p>
         */
        @Test
        @DisplayName("fail closed, and diagnosably, when a reservation cannot be read")
        void anUnreadableReservationStopsTheDecision() {
            List<DatasetGeneration> held = new ArrayList<>(alreadyHeld());
            held.add(firstGenerationUnder(new BusinessDate("2022-09-20")));
            DatasetGeneration unreadable = held.get(2);
            DatasetGenerationService service =
                    serviceOver(objectStoreHolding(held, OTHER_RUN_ID, true));
            failTheClaimReadOf(unreadable);

            assertThatThrownBy(() -> service.generationsToScratch(this.family))
                    .isInstanceOf(DatasetGenerationService.DatasetGenerationException.class)
                    .hasMessageContaining(this.family.mainframeBaseName())
                    .hasMessageContaining(unreadable.keyPrefix()
                            + DatasetGenerationService.CLAIM_OBJECT_NAME)
                    .hasCauseInstanceOf(S3Exception.class);
            assertThat(DatasetGenerationServiceTest.this.deleteRequests).isEmpty();
        }
    }

    /**
     * Settles that the current generation is the MAXIMUM present and not merely one that is present.
     *
     * <p>Purpose: every other case in this file stages a partition holding no generation or exactly one,
     * so the maximum, the minimum, the first listed and the last listed are all the same value and the
     * assertions cannot tell them apart. The service selects with an explicit maximum and its own comment
     * declines to rely on the listing's ordering, and neither the selection nor that reasoning had a case
     * that could fail if the selection were changed to take the first element, the last element or the
     * smallest. These cases supply one.</p>
     *
     * <p>Refactoring Rationale: the discriminating input is three generations staged OUT OF ORDER, and the
     * order is chosen so that every wrong selector yields a different wrong answer: staged 3, 7, 5, the
     * first is 3, the last is 5, the smallest is 3 and only the maximum is 7. Staging an ascending run
     * would have left the maximum and the last element equal, and staging two generations would have left
     * a coin-flip between first and last passing half the time.</p>
     */
    @Nested
    @DisplayName("selecting the current generation from several that are present")
    class HighestGenerationSelection {

        /** The generations staged out of order, whose maximum is neither the first nor the last. */
        private static final List<Integer> UNORDERED_GENERATIONS = List.of(3, 7, 5);

        /** The maximum of {@link #UNORDERED_GENERATIONS}, which is the only correct current generation. */
        private static final int HIGHEST_STAGED = 7;

        /**
         * Builds the staged coordinates for one family from {@link #UNORDERED_GENERATIONS}, order kept.
         *
         * @param family the family the coordinates belong to
         * @return the coordinates in the declared staging order, never {@code null}
         */
        private static List<DatasetGeneration> unorderedStaging(DatasetFamily family) {
            return UNORDERED_GENERATIONS.stream().map(number -> generation(family, number)).toList();
        }

        /**
         * The highest generation present is the current one, whatever order the listing reported.
         *
         * <p>Pins {@code app/jcl/COMBTRAN.jcl:24}, where the combine job reads {@code TRANSACT.BKUP(0)}.
         * A {@code (0)} reference names the most recent generation, so reading any other present
         * generation would silently merge stale input and produce an output that looks successful.</p>
         *
         * <p>Assumptions: the three wrong answers are asserted to be wrong explicitly rather than left
         * implied by the right one. Writing only the expected 7 would pass under a selector taking the
         * maximum and would also pass under any selector that happened to return 7 for this input; naming
         * the first, the last and the smallest as values the answer must NOT equal is what records which
         * substitutions this case is defending against.</p>
         */
        @Test
        @DisplayName("answer the maximum, not the first, the last or the smallest listed")
        void theHighestGenerationWinsRegardlessOfListingOrder() {
            List<DatasetGeneration> staged = unorderedStaging(DatasetFamily.TRANSACT_BKUP);
            DatasetGenerationService service = serviceOver(objectStoreHolding(staged));

            Optional<DatasetGeneration> current = service.resolveCurrentGeneration(DatasetFamily.TRANSACT_BKUP);

            assertThat(current).isPresent();
            int resolved = current.orElseThrow().generationNumber();

            assertThat(resolved).as("the current generation is the highest present")
                    .isEqualTo(HIGHEST_STAGED);
            assertThat(resolved).as("and so is not the first the listing reported")
                    .isNotEqualTo(UNORDERED_GENERATIONS.get(0));
            assertThat(resolved).as("nor the last the listing reported")
                    .isNotEqualTo(UNORDERED_GENERATIONS.get(UNORDERED_GENERATIONS.size() - 1));
            assertThat(resolved).as("nor the smallest present")
                    .isNotEqualTo(UNORDERED_GENERATIONS.stream().min(Integer::compareTo).orElseThrow());
        }

        /**
         * The next generation allocated after an out-of-order partition is one past the maximum.
         *
         * <p>Pins {@code app/jcl/COMBTRAN.jcl:37}, where the same job writes
         * {@code TRANSACT.COMBINED(+1)}. A {@code (+1)} reference must not collide with a generation that
         * already exists, and allocating one past anything other than the maximum would do exactly that:
         * one past the first listed here is 4, which is already taken.</p>
         *
         * <p>Assumptions: the collision is asserted directly, not merely the number. Asserting 8 alone
         * would leave a reader to work out why 8 rather than 4 or 6 matters; asserting that the allocated
         * number is absent from the staged set states the property the number exists to satisfy.</p>
         */
        @Test
        @DisplayName("allocate one past the maximum, colliding with no generation already present")
        void allocationFollowsTheMaximumAndCollidesWithNothing() {
            List<DatasetGeneration> staged = unorderedStaging(DatasetFamily.SYSTRAN);
            DatasetGenerationService service = serviceOver(objectStoreHolding(staged));

            DatasetGeneration allocated = service.allocateNewGeneration(
                    DatasetFamily.SYSTRAN, BUSINESS_DATE, RUN_ID);

            assertThat(allocated.generationNumber()).isEqualTo(HIGHEST_STAGED + 1);
            assertThat(UNORDERED_GENERATIONS).doesNotContain(allocated.generationNumber());
        }

        /**
         * The next-generation computation takes the maximum for both a rising and a falling input.
         *
         * <p>Assumptions: the computation is driven directly with a list rather than through a stubbed
         * store, because the published operation accepts the existing generations as an argument and
         * asserting it that way removes the listing from the question entirely. Two orderings are supplied
         * because a selector reading a fixed position agrees with the maximum for one ordering or the
         * other but never for both -- the ascending list's last element is the maximum and the descending
         * list's first element is, so the pair excludes both positional readings at once.</p>
         */
        @Test
        @DisplayName("compute the same next generation from an ascending and a descending list")
        void nextGenerationTakesTheMaximumWhicheverWayTheListRuns() {
            DatasetGenerationService service = serviceOver(objectStoreHolding(List.of()));
            DatasetFamily family = DatasetFamily.TRANREPT;

            List<DatasetGeneration> ascending = List.of(
                    generation(family, 3), generation(family, 5), generation(family, HIGHEST_STAGED));
            List<DatasetGeneration> descending = List.of(
                    generation(family, HIGHEST_STAGED), generation(family, 5), generation(family, 3));

            assertThat(service.nextGeneration(family, BUSINESS_DATE, ascending).generationNumber())
                    .isEqualTo(HIGHEST_STAGED + 1);
            assertThat(service.nextGeneration(family, BUSINESS_DATE, descending).generationNumber())
                    .isEqualTo(HIGHEST_STAGED + 1);
        }

        /**
         * A partition already holding the highest representable generation is reported as exhausted.
         *
         * <p>Assumptions: the ceiling is read from the coordinate's own published maximum rather than
         * written as 9999, so this case states that the partition is full rather than restating the width
         * the renderer happens to use. A literal here would let the test and the renderer disagree about
         * the ceiling while both passed.</p>
         *
         * <p>Trade-offs: the reported message is asserted to name the family and the business date, not
         * just to be an exception of the right type. The service's own reasoning for raising here rather
         * than letting the coordinate's constructor refuse is that the constructor names only an
         * out-of-range number while an operator needs the partition that is full -- so a case that
         * accepted any message would leave the only reason this check exists unasserted.</p>
         */
        @Test
        @DisplayName("report a full partition as exhausted, naming the family and the date")
        void aFullPartitionIsReportedAsExhausted() {
            DatasetFamily family = DatasetFamily.DALYREJS;
            List<DatasetGeneration> full =
                    List.of(generation(family, DatasetGeneration.MAXIMUM_GENERATION_NUMBER));
            DatasetGenerationService service = serviceOver(objectStoreHolding(full));

            assertThatThrownBy(() -> service.nextGeneration(family, BUSINESS_DATE, full))
                    .isInstanceOf(DatasetGenerationService.DatasetGenerationException.class)
                    .hasMessageContaining(family.mainframeBaseName())
                    .hasMessageContaining(BUSINESS_DATE.token())
                    .hasMessageContaining(String.valueOf(DatasetGeneration.MAXIMUM_GENERATION_NUMBER));

            assertThatThrownBy(() -> service.allocateNewGeneration(family, BUSINESS_DATE, RUN_ID))
                    .as("allocation reports the same exhaustion rather than wrapping round to zero")
                    .isInstanceOf(DatasetGenerationService.DatasetGenerationException.class);
        }
    }

    /**
     * Settles what the listing does with a child it did not write, and what it does when it cannot read.
     *
     * <p>Purpose: the service filters every child prefix through four independent rejections -- a child
     * outside the partition, a child not closed by a separator, a generation segment carrying more digits
     * than the renderer emits, and a segment that does not round-trip the renderer's own padding -- and it
     * translates a transport failure into its own exception naming the family and partition. None of that
     * had a case, because the helper the other cases use maps a coordinate's own {@code keyPrefix} and so
     * can only ever produce children that pass all four, and its stub cannot fail.</p>
     *
     * <p>Assumptions: an object-store partition is a shared namespace. A dataset bucket carries every
     * family under one root, a lifecycle rule leaves delete markers behind, and an operator may stage a
     * directory by hand, so a listing returning something the renderer never emitted is an ordinary
     * event rather than a corrupt one. Reading such a child as a generation is the failure mode these
     * cases exclude: a child ending {@code gen=10000/} read as generation 10000 would raise the next
     * allocation past the representable range, and an unpadded {@code gen=7/} read as 7 would collide
     * with the properly rendered {@code gen=0007/} sitting beside it.</p>
     */
    @Nested
    @DisplayName("reading a partition that holds children the renderer never wrote")
    class ForeignChildAndFailureHandling {

        /** The family every case in this class reads, chosen for having a short path segment. */
        private static final DatasetFamily FAMILY = DatasetFamily.SYSTRAN;

        /** The generation whose well-formed child is staged alongside each malformed one. */
        private static final int WELL_FORMED_GENERATION = 2;

        /**
         * Composes the partition prefix the service lists under, from the coordinate's own accessors.
         *
         * <p>Assumptions: the prefix is composed the way the service composes it -- the family's path
         * segment, which already ends in a separator, then the date partition segment, then the one
         * separator that closes it -- rather than spelled as a literal. Spelling it would put a second
         * declaration of the key convention in this file, and these cases would then be written against
         * the convention this file asserts instead of the one the record publishes.</p>
         *
         * @return the partition prefix for {@link #FAMILY} under the shared business date
         */
        private static String partitionPrefix() {
            DatasetGeneration probe = generation(FAMILY, DatasetGeneration.MINIMUM_GENERATION_NUMBER);
            return probe.family().pathSegment() + probe.datePartitionSegment() + "/";
        }

        /**
         * Renders the well-formed child prefix the malformed cases stage alongside their subject.
         *
         * @return the key prefix of {@link #WELL_FORMED_GENERATION} for {@link #FAMILY}, as the owning
         *     record renders it, never {@code null}
         */
        private static String wellFormedChild() {
            return generation(FAMILY, WELL_FORMED_GENERATION).keyPrefix();
        }

        /**
         * A malformed generation segment is skipped while a well-formed sibling beside it is read.
         *
         * <p>Assumptions: each malformed shape is paired with a well-formed child in the same listing and
         * the surviving generation is asserted by number, so a blanket rejection is distinguishable from
         * the selective one under test. A case staging only the malformed child would pass identically
         * under a filter that rejected everything, which is the opposite of correct.</p>
         *
         * <p>Assumptions: the four suffixes are the four rejections the service performs, one each, and
         * they are supplied as suffixes rather than whole prefixes so the partition they sit under is
         * still composed rather than spelled. {@code gen=10000/} carries a fifth digit the renderer never
         * emits; {@code gen=7/} carries the right digits without the padding, so it fails the round trip
         * against {@code gen=0007}; {@code gen=current/} ends in no digit at all; and {@code gen=0003}
         * is closed by no separator, so it is an object key rather than a generation directory.</p>
         *
         * @param malformedSuffix the child suffix, appended to the composed partition prefix
         */
        @ParameterizedTest
        @ValueSource(strings = {"gen=10000/", "gen=7/", "gen=current/", "gen=0003"})
        @DisplayName("skip a malformed generation child and still read its well-formed sibling")
        void aMalformedGenerationChildIsSkipped(String malformedSuffix) {
            S3Client objectStore = objectStoreListingLiterally(
                    List.of(partitionPrefix() + malformedSuffix, wellFormedChild()));
            DatasetGenerationService service = serviceOver(objectStore);

            List<DatasetGeneration> present = service.listGenerations(FAMILY, BUSINESS_DATE);

            assertThat(present).hasSize(1);
            assertThat(present.get(0).generationNumber()).isEqualTo(WELL_FORMED_GENERATION);
            assertThat(service.resolveCurrentGeneration(FAMILY).orElseThrow()
                    .generationNumber())
                    .as("the malformed child does not become the current generation either")
                    .isEqualTo(WELL_FORMED_GENERATION);
        }

        /**
         * A child belonging to another family is skipped even when the listing returns it.
         *
         * <p>Assumptions: the foreign child is a real, well-formed coordinate of a DIFFERENT family, not
         * a malformed string. That is the harder case: its generation segment round-trips and it is closed
         * by a separator, so the only thing disqualifying it is that it sits outside the requested
         * partition. Staging a malformed foreign child would have let a segment check pass this case while
         * the partition check was absent.</p>
         *
         * <p>Assumptions: the foreign generation number is deliberately HIGHER than the local one, so a
         * service that failed to exclude it would report the foreign number as the current generation and
         * the assertion would fail on the value rather than only on the count.</p>
         */
        @Test
        @DisplayName("skip a well-formed child of another family sharing the bucket")
        void aChildOfAnotherFamilyIsSkipped() {
            DatasetGeneration foreign = new DatasetGeneration(
                    DatasetFamily.TRANREPT, BUSINESS_DATE, WELL_FORMED_GENERATION + 5);
            S3Client objectStore = objectStoreListingLiterally(
                    List.of(foreign.keyPrefix(), wellFormedChild()));
            DatasetGenerationService service = serviceOver(objectStore);

            List<DatasetGeneration> present = service.listGenerations(FAMILY, BUSINESS_DATE);

            assertThat(present).hasSize(1);
            assertThat(present.get(0).family()).isEqualTo(FAMILY);
            assertThat(present.get(0).generationNumber()).isEqualTo(WELL_FORMED_GENERATION);
            assertThat(service.nextGeneration(FAMILY, BUSINESS_DATE, present).generationNumber())
                    .as("the foreign generation does not raise this family's next number")
                    .isEqualTo(WELL_FORMED_GENERATION + 1);
        }

        /**
         * A transport failure is reported as this service's own exception, naming what was being read.
         *
         * <p>Assumptions: the message is asserted to carry the family base name, the partition prefix and
         * the business-date token, because those three facts are the entire reason the service translates
         * rather than letting the kit's exception through. Its own comment records that the kit's
         * exception names an operation and a bucket and nothing else, so a case asserting only the
         * exception type would leave the stated purpose of the translation unverified.</p>
         *
         * <p>Assumptions: the cause is asserted to be retained. The translation's justification is
         * explicitly that nothing the kit reported is lost, and a wrapper that dropped the cause would
         * satisfy every other assertion here.</p>
         */
        @Test
        @DisplayName("translate a listing failure, naming the family, the prefix and the date")
        void aListingFailureIsTranslatedWithItsCauseRetained() {
            SdkClientException transportFailure =
                    SdkClientException.create("the object store is unreachable");
            DatasetGenerationService service = serviceOver(
                    objectStoreFailingThenHolding(1, transportFailure, List.of()));

            assertThatThrownBy(() -> service.listGenerations(FAMILY, BUSINESS_DATE))
                    .isInstanceOf(DatasetGenerationService.DatasetGenerationException.class)
                    .hasMessageContaining(FAMILY.mainframeBaseName())
                    .hasMessageContaining(partitionPrefix())
                    .hasMessageContaining(BUSINESS_DATE.token())
                    .hasCause(transportFailure);
        }

        /**
         * An allocation that failed can be retried, because a failed attempt reserves nothing.
         *
         * <p>Assumptions: the reservation this service keeps is DURABLE -- it is an object in the store,
         * not an entry in a per-instance table -- so the property under test is that a failed attempt
         * leaves no such object behind. The listing runs BEFORE anything is written, so a listing failure
         * propagates with the store untouched and the next call starts from the same state as the first.
         * Had the reservation been written ahead of the listing, the first failure would have been
         * permanent for the run and every retry would have reported the recorded number against a
         * generation that was never claimed. Nothing else in this file can show that, because nothing
         * else lets a listing fail.</p>
         *
         * <p>Assumptions: the store is arranged to fail exactly once and then succeed, and the retry is
         * asserted to produce the generation the recovered listing implies rather than merely to not
         * throw. Asserting only the absence of an exception would pass against a service that had
         * reserved a number during the failed attempt and answered from it -- which is the very thing
         * being excluded.</p>
         */
        @Test
        @DisplayName("allocate on a retry after a failed attempt, reserving nothing from the failure")
        void aFailedAllocationLeavesNoReservationAndRetriesCleanly() {
            SdkClientException transportFailure =
                    SdkClientException.create("the object store is unreachable");
            DatasetGenerationService service = serviceOver(objectStoreFailingThenHolding(
                    1, transportFailure, List.of(generation(FAMILY, WELL_FORMED_GENERATION))));

            assertThatThrownBy(() -> service.allocateNewGeneration(FAMILY, BUSINESS_DATE, RUN_ID))
                    .isInstanceOf(DatasetGenerationService.DatasetGenerationException.class);

            DatasetGeneration retried = service.allocateNewGeneration(FAMILY, BUSINESS_DATE, RUN_ID);

            assertThat(retried.generationNumber())
                    .as("the retry reads the recovered listing rather than anything the failure left")
                    .isEqualTo(WELL_FORMED_GENERATION + 1);

            // WHY : Assumptions: the SECOND successful call is asserted to answer identically, which is
            //       what shows the successful attempt DID record its reservation. Without it this case
            //       could pass against a service that recorded nothing at all, and the reservation's
            //       purpose would be asserted only by the cases that never see a failure.
            assertThat(service.allocateNewGeneration(FAMILY, BUSINESS_DATE, RUN_ID)).isEqualTo(retried);
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

    /**
     * Settles what {@code SCRATCH} removes on a versioned bucket, and what it does when a delete is refused.
     *
     * <p>Purpose: the retention window is only a window if the generations that fall out of it release
     * their space. The bucket the {@code s3-datasets} module provisions is versioned, so a key-addressed
     * delete inserts a delete marker and retains every version behind it; a pass built on one would report
     * a scratched generation while the bytes stayed stored and billed indefinitely. These cases drive the
     * pass against a store that models versions and assert what it actually removed.</p>
     *
     * <p>Assumptions: the stubbed store REMOVES what a deletion names, so the assertions can be made
     * against the store's residual state rather than only against the requests issued. Asserting requests
     * alone would pass for a pass that named the right identifiers in the wrong operation.</p>
     */
    @Nested
    @DisplayName("version-aware SCRATCH")
    class VersionAwareScratch {

        /**
         * Every version of every key under the prefix goes, not just the current one.
         *
         * <p>Pins the {@code SCRATCH} operand each of the ten bases carries -- {@code app/jcl/DEFGDGB.jcl}
         * lines 27, 33, 39, 45, 51 and 57, {@code app/jcl/DEFGDGD.jcl} lines 30, 53 and 76, and
         * {@code app/jcl/DALYREJS.jcl} line 27 -- which releases the space rather than uncataloguing it.</p>
         *
         * <p>Refactoring Rationale: the pass listed current objects and deleted them by key alone. Against
         * this store that removes nothing at all: the assertion below on the residual versions is the one
         * that fails for the previous implementation, and it fails while the request count and the returned
         * total both look right, which is why it is asserted on the store rather than on the calls.</p>
         */
        @Test
        @DisplayName("delete every version beneath the generation prefix")
        void scratchDeletesEveryVersion() {
            DatasetGeneration doomed = generation(DatasetFamily.TRANSACT_BKUP, 1);
            DatasetGenerationService service = serviceOver(objectStoreHolding(List.of(doomed)));
            String records = doomed.keyPrefix() + "records.dat";
            storeObject(records, "first");
            storeObject(records, "second");
            storeObject(doomed.keyPrefix() + "manifest.json", "{}");

            int removed = service.scratchGeneration(doomed);

            // WHY : Assumptions: four is the claim marker, the two versions of the record file and the
            //       manifest. Naming the arithmetic rather than the number is what keeps this case
            //       readable when the seeding above changes, and it is the count of VERSIONS rather than
            //       of keys -- which is the distinction the previous implementation collapsed.
            assertThat(removed).isEqualTo(4);
            assertThat(storedVersions.keySet()).noneMatch(key -> key.startsWith(doomed.keyPrefix()));
            assertThat(deleteRequests).isNotEmpty();
            assertThat(deleteRequests.stream().flatMap(List::stream).toList())
                    .allSatisfy(identifier -> assertThat(identifier.versionId()).isNotNull());
        }

        /**
         * A delete marker left by an earlier, version-blind pass is itself removed.
         *
         * <p>Assumptions: this is the recovery case rather than a hypothetical. Any generation scratched
         * before this pass became version-aware still carries a marker over every key, and a marker is a
         * live version: a pass that removed only real versions would leave the prefix listing forever and
         * the space unreclaimed. Seeding the marker directly is the only way to reach that state, because
         * this store's own deletions no longer create one.</p>
         */
        @Test
        @DisplayName("delete a marker an earlier version-blind pass left behind")
        void scratchDeletesDeleteMarkers() {
            DatasetGeneration doomed = generation(DatasetFamily.TRANSACT_DALY, 2);
            DatasetGenerationService service = serviceOver(objectStoreHolding(List.of(doomed)));
            String records = doomed.keyPrefix() + "records.dat";
            storeObject(records, "bytes");
            storeDeleteMarker(records);

            int removed = service.scratchGeneration(doomed);

            assertThat(removed).isEqualTo(3);
            assertThat(storedDeleteMarkers).isEmpty();
            assertThat(storedVersions.keySet()).noneMatch(key -> key.startsWith(doomed.keyPrefix()));
        }

        /**
         * More versions than one request may carry are deleted in several requests, none over the ceiling.
         *
         * <p>Assumptions: the ceiling is the object store's own limit on a batched deletion, so exceeding
         * it is a refused request rather than a slow one. A generation of the transaction master holds one
         * object per staging step and accumulates a version per re-stage, so passing the ceiling is an
         * ordinary outcome rather than an edge case -- and a pass that sent one oversized request would
         * fail on exactly the generations that most needed scratching.</p>
         */
        @Test
        @DisplayName("batch the deletions beneath the request ceiling")
        void scratchBatchesLargeGenerations() {
            DatasetGeneration doomed = generation(DatasetFamily.TRANREPT, 3);
            DatasetGenerationService service = serviceOver(objectStoreHolding(List.of(doomed)));
            int extraObjects = VERSION_PAGE_SIZE + 5;
            for (int index = 0; index < extraObjects; index++) {
                storeObject(doomed.keyPrefix() + String.format("part-%05d.dat", index), "bytes");
            }

            int removed = service.scratchGeneration(doomed);

            assertThat(removed).isEqualTo(extraObjects + 1);
            assertThat(deleteRequests).hasSizeGreaterThan(1);
            assertThat(deleteRequests).allSatisfy(
                    request -> assertThat(request).hasSizeLessThanOrEqualTo(VERSION_PAGE_SIZE));
        }

        /**
         * A version the store refuses inside an otherwise successful request fails the whole scratch.
         *
         * <p>Refactoring Rationale: the response was not examined. A batched deletion answers 200 while
         * naming individual keys it refused, so an unexamined response reported a partial deletion as a
         * completed scratch -- the family then grew past its five-generation window with every pass
         * logging success, which is the shape of defect that is only found when the bill arrives.</p>
         *
         * <p>Assumptions: the failure is asserted to NAME the family, the generation, the prefix and the
         * store's own error code, because those four are what an operator needs to act without a second
         * run. A refusal reported as a bare count would establish that the pass failed closed and leave
         * the reader no way to tell a missing permission from a retention lock.</p>
         */
        @Test
        @DisplayName("fail closed, and diagnosably, when the store refuses a version")
        void scratchFailsOnPartialDeletion() {
            DatasetGeneration doomed = generation(DatasetFamily.TCATBALF_BKUP, 4);
            DatasetGenerationService service = serviceOver(objectStoreHolding(List.of(doomed)));
            storeObject(doomed.keyPrefix() + "records.dat", "bytes");
            stagedDeleteErrors.add(S3Error.builder()
                    .key(doomed.keyPrefix() + "records.dat")
                    .versionId("version-1")
                    .code("AccessDenied")
                    .message("stubbed object store: the caller may not delete this version")
                    .build());

            assertThatThrownBy(() -> service.scratchGeneration(doomed))
                    .isInstanceOf(DatasetGenerationService.DatasetGenerationException.class)
                    .hasMessageContaining(DatasetFamily.TCATBALF_BKUP.mainframeBaseName())
                    .hasMessageContaining("4")
                    .hasMessageContaining(doomed.keyPrefix())
                    .hasMessageContaining("AccessDenied");
        }

        /**
         * A refused deletion REQUEST fails the scratch, naming the family, the generation and the prefix.
         *
         * <p>Purpose: this is the shape the deployed defect took rather than a variation on the case above.
         * The batch task role's policy granted object actions on the ten family prefixes and no deletion
         * action at all, so the store refused the whole request with a 403 and the sixth run of a family
         * could not retire its oldest generation. A refusal of the request and a refusal of individual
         * versions arrive through two different channels -- a thrown exception and an error list inside a
         * successful response -- so a suite that exercised only the second would leave the branch that
         * actually fired unexecuted.</p>
         *
         * <p>Assumptions: the failure is required to name the family, the generation and the prefix, and to
         * retain the store's own exception as its cause. The status code alone does not distinguish a
         * missing grant from a bucket policy denial, and without the prefix an operator cannot tell which
         * of the ten families' resource patterns the policy is short of.</p>
         */
        @Test
        @DisplayName("fail closed, and diagnosably, when the store refuses the deletion request")
        void scratchFailsWhenTheDeletionRequestIsRefused() {
            DatasetGeneration doomed = generation(DatasetFamily.TRANCATG_BKUP, 2);
            DatasetGenerationService service = serviceOver(objectStoreHolding(List.of(doomed)));
            storeObject(doomed.keyPrefix() + "records.dat", "bytes");
            SdkException refused = S3Exception.builder()
                    .statusCode(403)
                    .message("stubbed object store: the caller holds no s3:DeleteObject on this prefix")
                    .build();
            DatasetGenerationServiceTest.this.stagedDeleteFailure = refused;

            assertThatThrownBy(() -> service.scratchGeneration(doomed))
                    .isInstanceOf(DatasetGenerationService.DatasetGenerationException.class)
                    .hasMessageContaining(DatasetFamily.TRANCATG_BKUP.mainframeBaseName())
                    .hasMessageContaining("2")
                    .hasMessageContaining(doomed.keyPrefix())
                    .hasCause(refused);
            // WHY : Assumptions: the store's residual state is asserted too. A pass that reported the
            //       refusal and had already removed part of the generation would leave a half-scratched
            //       prefix, and the count it returned is unavailable to the caller once it throws -- so
            //       the residue is the only evidence that the failure was clean.
            assertThat(DatasetGenerationServiceTest.this.storedVersions)
                    .containsKey(doomed.keyPrefix() + "records.dat");
        }

        /**
         * A version listing that fails is reported as a scratch failure that retains the store's exception.
         *
         * <p>Assumptions: the cause is asserted to be THE instance the store raised rather than an
         * equivalent, because the reason for wrapping at all is to add the family and the prefix without
         * discarding the transport detail underneath. A translation that built a fresh cause would leave an
         * operator with a message naming the generation and nothing naming the network.</p>
         */
        @Test
        @DisplayName("retain the store's own failure when the version listing fails")
        void scratchRetainsTheListingFailure() {
            SdkException unreachable = SdkClientException.create("stubbed object store: unreachable");
            DatasetGeneration doomed = generation(DatasetFamily.SYSTRAN, 1);
            DatasetGenerationService service =
                    serviceOver(objectStoreFailingThenHolding(1, unreachable, List.of(doomed)));

            assertThatThrownBy(() -> service.scratchGeneration(doomed))
                    .isInstanceOf(DatasetGenerationService.DatasetGenerationException.class)
                    .hasMessageContaining(DatasetFamily.SYSTRAN.mainframeBaseName())
                    .hasCause(unreachable);
        }

        /**
         * A generation holding nothing is scratched without issuing a deletion at all.
         *
         * <p>Assumptions: a request carrying no identifiers is refused by the store, so the empty case has
         * to be a no-op rather than an empty request. It is reachable in production: retention runs after
         * every staging pass, and a generation whose staging failed before its first write holds a claim
         * marker only -- or, once that marker has been scratched, nothing.</p>
         */
        @Test
        @DisplayName("issue no deletion for a generation holding nothing")
        void scratchOfAnEmptyGenerationIssuesNothing() {
            DatasetGenerationService service = serviceOver(objectStoreHolding(List.of()));

            assertThat(service.scratchGeneration(generation(DatasetFamily.DALYREJS, 1))).isZero();
            assertThat(deleteRequests).isEmpty();
        }
    }

    /**
     * Holds the reservation this service writes equal to the reservation the Python stager reads.
     *
     * <p>Purpose: two components allocate generations of the same ten families into the same bucket --
     * this service, and {@code data-migration/src/carddemo_migration/loaders/s3_stage.py}, which the
     * {@code StageSeedDatasets} state runs one containerised branch of per family. They coordinate
     * through objects in the bucket and through nothing else: there is no shared library, no lock and no
     * call between them. A claim one of them writes is therefore only honoured by the other if both spell
     * the key the same way, and the failure when they do not is silent and destructive -- each concludes
     * the same number is free, each claims it under its own spelling, and both write a generation over one
     * prefix. These cases are the only place that agreement is checked.</p>
     *
     * <p>Assumptions: the cases read the STAGER'S OWN SOURCE and the Terraform module's own declaration
     * rather than restating either here, and they compose their keys from what they read. A test that
     * spelled the shared literals itself would agree with whichever side it copied them from and would
     * keep passing after the other side moved -- which is exactly the state this class exists to make
     * impossible. The sibling suite
     * {@code data-migration/tests/test_s3_stage.py} runs the mirror image of every case below, reading
     * this service's declarations, so neither language holds the contract alone.</p>
     *
     * <p>Alternatives Considered: exercising the two implementations together against a real object store
     * through LocalStack. Rejected as the wrong instrument rather than as unnecessary: a live store would
     * prove that two agreeing implementations interoperate, which is not in doubt, while costing a
     * container per case; what has to be caught is a DIVERGENCE, and a divergence is visible in the
     * declarations themselves. The seeded-marker and seeded-record cases below reproduce the interop
     * outcome without needing either runtime.</p>
     */
    @Nested
    @DisplayName("the reservation contract shared with the Python stager")
    class CrossLanguageClaimContract {

        /** The stager's source, read as text so its declared literals can be compared with these. */
        private static final String STAGER_SOURCE =
                "data-migration/src/carddemo_migration/loaders/s3_stage.py";

        /** The Terraform module whose lifecycle rule and published output scope the replay records. */
        private static final String DATASETS_MODULE = "infra/modules/s3-datasets/main.tf";

        /**
         * The family this class allocates for, chosen for having no hyphen in its dataset segment.
         *
         * <p>Assumptions: a hyphen-free segment is used deliberately. The stager derives its family token
         * by upper-casing the segment and replacing each hyphen with an underscore, and the ten pairs
         * that transformation produces are asserted family-by-family on the Python side where the
         * registry lives. Choosing a segment the transformation leaves alone keeps these cases about the
         * KEY SHAPE rather than re-testing the token rule from the side that does not own it.</p>
         */
        private static final DatasetFamily FAMILY = DatasetFamily.SYSTRAN;

        /**
         * Both tiers name the claim marker and the replay-record root identically.
         *
         * <p>Assumptions: the marker name and the root are read from the stager's declarations, and the
         * root is additionally read from the Terraform module. Three artifacts have to agree on the root
         * because the module's published {@code generation_claim_prefix} output is what an environment
         * root scopes the batch task role's read and write to: a root that agreed with neither
         * implementation would produce an {@code AccessDenied} on the first allocation of a deployed run,
         * with every test in both languages still green.</p>
         */
        @Test
        @DisplayName("declare one marker name and one replay root across both tiers and the module")
        void theSharedLiteralsAreDeclaredIdenticallyEverywhere() {
            assertThat(declaredInStager("_CLAIM_OBJECT_NAME"))
                    .isEqualTo(DatasetGenerationService.CLAIM_OBJECT_NAME);
            assertThat(declaredInStager("_RUN_CLAIM_ROOT"))
                    .isEqualTo(DatasetGenerationService.RUN_CLAIM_ROOT);
            assertThat(declaredInModule("generation_claim_prefix"))
                    .isEqualTo(DatasetGenerationService.RUN_CLAIM_ROOT);
        }

        /**
         * An allocation writes exactly the two objects, at the two keys, the stager reads.
         *
         * <p>Assumptions: both keys are composed from the stager's own literals -- including the run
         * marker and the family separator, which are private to this service and so cannot be read from
         * it -- so this case pins the whole key shape rather than the two literals a public constant
         * exposes. The bodies are asserted too: the marker carries the run identifier and the record
         * carries the UNPADDED decimal generation, which is the form the stager parses. A record written
         * as the zero-padded {@code gen=} segment would parse to the same number and would hide a
         * divergence the moment either side stopped trimming.</p>
         */
        @Test
        @DisplayName("write the two objects the stager reads, at the keys and with the bodies it expects")
        void anAllocationWritesTheKeysAndBodiesTheStagerReads() {
            DatasetGeneration allocated = serviceOver(objectStoreHolding(List.of()))
                    .allocateNewGeneration(FAMILY, BUSINESS_DATE, RUN_ID);

            String markerKey = allocated.keyPrefix() + declaredInStager("_CLAIM_OBJECT_NAME");
            String recordKey = declaredInStager("_RUN_CLAIM_ROOT")
                    + declaredInStager("_RUN_CLAIM_RUN_MARKER") + RUN_ID
                    + declaredInStager("_RUN_CLAIM_FAMILY_SEPARATOR") + FAMILY.name();

            assertThat(DatasetGenerationServiceTest.this.storedObjects)
                    .containsEntry(markerKey, RUN_ID)
                    .containsEntry(recordKey, Integer.toString(allocated.generationNumber()));
            assertThat(allocated.generationNumber()).isEqualTo(1);
        }

        /**
         * A generation the stager claimed is not taken a second time here.
         *
         * <p>Assumptions: the marker is seeded at a key composed from the stager's literal rather than
         * through this service's own allocation, which is what makes the case cross-language. The
         * property it settles is not the conditional write but the LISTING: a marker beneath
         * {@code gen=0001/} makes that prefix a common prefix, so this service's discovery counts the
         * number as present without knowing anything about claims. That is the whole reason the marker
         * sits inside the generation prefix rather than in a sibling of it.</p>
         */
        @Test
        @DisplayName("skip a generation the stager claimed")
        void aGenerationClaimedByTheStagerIsSkipped() {
            S3Client objectStore = objectStoreHolding(List.of());
            String claimedByStager = new DatasetGeneration(FAMILY, BUSINESS_DATE, 1).keyPrefix()
                    + declaredInStager("_CLAIM_OBJECT_NAME");
            DatasetGenerationServiceTest.this.storeObject(claimedByStager, "stager-execution-token");

            DatasetGeneration allocated = serviceOver(objectStore)
                    .allocateNewGeneration(FAMILY, BUSINESS_DATE, RUN_ID);

            assertThat(allocated.generationNumber()).isEqualTo(2);
            // WHY : Assumptions: the stager's marker is asserted UNCHANGED as well. An allocator that
            //       had overwritten it would also answer two on the next listing, so the number alone
            //       cannot distinguish honouring another writer's claim from destroying it.
            assertThat(DatasetGenerationServiceTest.this.storedObjects)
                    .containsEntry(claimedByStager, "stager-execution-token");
        }

        /**
         * A run the stager already allocated for replays the stager's number rather than taking a new one.
         *
         * <p>Assumptions: the record is seeded at a key composed entirely from the stager's literals and
         * carries the unpadded decimal body the stager writes. This is the case that makes a redriven
         * branch safe across the language boundary: the orchestrator may retry a branch as a Python task
         * and the retry as a Java task, or the reverse, and a retry that allocated afresh would consume a
         * second generation of the five the family retains and stage a duplicate copy of identical
         * bytes.</p>
         */
        @Test
        @DisplayName("replay the generation the stager recorded for this run")
        void aGenerationRecordedByTheStagerIsReplayed() {
            S3Client objectStore = objectStoreHolding(List.of());
            String recordKey = declaredInStager("_RUN_CLAIM_ROOT")
                    + declaredInStager("_RUN_CLAIM_RUN_MARKER") + RUN_ID
                    + declaredInStager("_RUN_CLAIM_FAMILY_SEPARATOR") + FAMILY.name();
            DatasetGenerationServiceTest.this.storeObject(recordKey, "7");

            DatasetGeneration allocated = serviceOver(objectStore)
                    .allocateNewGeneration(FAMILY, BUSINESS_DATE, RUN_ID);

            assertThat(allocated.generationNumber()).isEqualTo(7);
            // WHY : Assumptions: no marker is expected for the replayed number. The attempt that first
            //       recorded it wrote that marker, so writing another would be a second claim over a
            //       prefix this run already owns -- and asserting its absence is what distinguishes a
            //       replay from an allocation that happened to land on the same number.
            assertThat(DatasetGenerationServiceTest.this.storedObjects)
                    .doesNotContainKey(new DatasetGeneration(FAMILY, BUSINESS_DATE, 7).keyPrefix()
                            + DatasetGenerationService.CLAIM_OBJECT_NAME);
        }

        /**
         * Reads one module-level string constant out of the stager's source.
         *
         * @param name the constant's identifier, for example {@code _RUN_CLAIM_ROOT}; must not be
         *     {@code null}
         * @return the declared literal with its quotes removed; never {@code null}
         * @throws AssertionError if the stager declares no such constant, so a renamed literal fails
         *     here rather than leaving the comparison unmade
         */
        private String declaredInStager(String name) {
            Matcher declaration = Pattern
                    .compile(name + ":\\s*Final\\[str\\]\\s*=\\s*\"([^\"]*)\"")
                    .matcher(readRepositoryFile(STAGER_SOURCE));

            assertThat(declaration.find())
                    .withFailMessage("%s declares no Final[str] constant named %s, so the reservation"
                            + " contract has only one side", STAGER_SOURCE, name)
                    .isTrue();
            return declaration.group(1);
        }

        /**
         * Reads one local's string value out of the dataset module's Terraform.
         *
         * @param name the local's name, for example {@code generation_claim_prefix}; must not be
         *     {@code null}
         * @return the declared literal with its quotes removed; never {@code null}
         * @throws AssertionError if the module declares no such local
         */
        private String declaredInModule(String name) {
            Matcher declaration = Pattern
                    .compile(name + "\\s*=\\s*\"([^\"]*)\"")
                    .matcher(readRepositoryFile(DATASETS_MODULE));

            assertThat(declaration.find())
                    .withFailMessage("%s declares no local named %s, so the grant and the lifecycle rule"
                            + " that scope this prefix cannot be held against it", DATASETS_MODULE, name)
                    .isTrue();
            return declaration.group(1);
        }
    }

    /**
     * Reads one repository file as text.
     *
     * <p>Assumptions: the file is located by walking up from the working directory to the repository
     * root rather than by a relative literal, because Maven runs a module's tests with the working
     * directory set to that module and a literal would encode the depth from here to the root. The same
     * pattern is used by {@code DisclosureGroupSeedParityTest} and by the shared kernel's
     * cross-artifact contract tests, for the same reason: the artifacts that have to agree are a Java
     * source, a Python source and a Terraform file, and no two of those are ever on one class path.</p>
     *
     * @param relative the path relative to the repository root; must not be {@code null}
     * @return the file's full text; never {@code null}
     * @throws UncheckedIOException if the file cannot be read
     */
    private static String readRepositoryFile(String relative) {
        Path file = repositoryRoot().resolve(relative);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }

    /**
     * Locates the repository root from the directory the test runs in.
     *
     * @return the nearest ancestor of the working directory that holds both artifacts the
     *     cross-language cases read; never {@code null}
     * @throws AssertionError if no ancestor holds them
     */
    private static Path repositoryRoot() {
        // WHY : Assumptions: the search requires BOTH the stager and the Terraform module to be
        //       present, not either one. A single marker could match an ancestor that happens to
        //       contain one of them -- a nested checkout, or a partial export -- and the cases would
        //       then read one artifact from that tree and fail to find the other, reporting the
        //       contract broken when only the lookup was.
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            boolean holdsStager = Files.isRegularFile(candidate.resolve(
                    CrossLanguageClaimContract.STAGER_SOURCE));
            boolean holdsModule = Files.isRegularFile(candidate.resolve(
                    CrossLanguageClaimContract.DATASETS_MODULE));
            if (holdsStager && holdsModule) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("no ancestor of " + Path.of("").toAbsolutePath()
                + " holds both " + CrossLanguageClaimContract.STAGER_SOURCE + " and "
                + CrossLanguageClaimContract.DATASETS_MODULE + ", so the shared reservation contract"
                + " cannot be read from either side");
    }

    /**
     * Holds the published surface of the service equal to the operations a landed job reaches.
     *
     * <p>Purpose: reviewing this class found every one of its operations published with no production
     * caller. Four of them were reachable only from inside the class and from these tests and are now
     * package-private; the six that remain public are each reached by a landed batch job, which
     * {@code GenerationStagingJobsTest} and {@code BatchJobRosterTest} assert by execution rather than by
     * charter prose. This nested class keeps the property mechanical, so that publishing a seventh
     * operation -- or re-publishing one of the four -- fails here instead of waiting for a later review to
     * notice it again.</p>
     *
     * <p>Assumptions: the two cases below check different things and both are needed. The first compares
     * NAMES, which cannot distinguish overloads, so it would still pass if the list-taking
     * {@code generationsToScratch} were re-published alongside the family-taking one. The second closes
     * that gap by naming each internal helper's exact signature.</p>
     */
    @Nested
    @DisplayName("the published surface")
    class PublishedSurface {

        /** The operations a landed batch job reaches, and therefore the whole published set. */
        private static final Set<String> WIRED_OPERATIONS = Set.of(
                "allocateNewGeneration", "resolveCurrentGeneration", "generationsToScratch",
                "datasetUri", "stageDataset", "scratchGeneration");

        /**
         * Every published operation is one a job reaches, and every one a job reaches is published.
         *
         * <p>Assumptions: synthetic members are excluded because the compiler generates bridge methods
         * that carry a declared method's name at a different erasure, and counting one would make the
         * comparison depend on compilation details rather than on the surface as written.</p>
         */
        @Test
        @DisplayName("publish exactly the operations a landed job reaches")
        void publishedOperationsAreExactlyTheWiredSet() {
            List<String> published = Arrays.stream(DatasetGenerationService.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> !method.isSynthetic())
                    .map(Method::getName)
                    .toList();

            assertThat(published).containsExactlyInAnyOrderElementsOf(WIRED_OPERATIONS);
        }

        /**
         * The allocation and listing helpers are declared, and none of them is published.
         *
         * <p>Assumptions: each helper is looked up by its exact signature, so the case fails rather than
         * passing vacuously if one is renamed or its parameters change -- a guard that silently stopped
         * watching the methods it names would be worse than no guard, because the surface would look
         * defended.</p>
         *
         * @throws NoSuchMethodException if a helper's signature has moved, which is a failure of this
         *     guard rather than of the class under test
         */
        @Test
        @DisplayName("keep the allocation and listing helpers unpublished")
        void theInternalHelpersAreNotPublished() throws NoSuchMethodException {
            List<Method> helpers = List.of(
                    DatasetGenerationService.class.getDeclaredMethod("nextGeneration",
                            DatasetFamily.class, BusinessDate.class, List.class),
                    DatasetGenerationService.class.getDeclaredMethod("generationsToScratch", List.class),
                    DatasetGenerationService.class.getDeclaredMethod("listGenerations",
                            DatasetFamily.class, BusinessDate.class),
                    DatasetGenerationService.class.getDeclaredMethod("listGenerations",
                            DatasetFamily.class));

            assertThat(helpers).allSatisfy(helper ->
                    assertThat(Modifier.isPublic(helper.getModifiers()))
                            .withFailMessage("%s is published again, so a caller can reach it without"
                                    + " going through the operation that guards it", helper.getName())
                            .isFalse());
        }
    }
}
