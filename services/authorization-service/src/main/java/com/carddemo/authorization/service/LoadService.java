package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.mapper.PendingAuthDetailMapper;
import com.carddemo.authorization.mapper.PendingAuthSummaryMapper;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk-loads pending-authorization summary and detail images from extract files into the schema.
 *
 * <p><strong>Purpose.</strong> Carry across
 * {@code app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL}, whose {@code MAIN-PARA} at L169 to L187
 * reads two sequential input files and inserts what they carry: {@code 2000-READ-ROOT-SEG-FILE} at L222
 * to L237 reads a hundred-byte root image and {@code 2100-INSERT-ROOT-SEG} at L242 to L263 inserts it,
 * then {@code 3000-READ-CHILD-SEG-FILE} at L269 to L289 reads an account-prefixed child record and
 * {@code 3100-INSERT-CHILD-SEG} at L292 to L314 positions on its parent before
 * {@code 3200-INSERT-IMS-CALL} at L318 to L336 inserts the child. Every citation is relative to that
 * tree, which is reference material this migration reads and never modifies.
 *
 * <p>Assumptions: the two files are loaded in the reference ORDER -- every root first, then every child
 * -- and the order is a requirement rather than a convenience. The reference program's two loops are
 * sequential rather than interleaved, and the relational form has the same dependency asserted by
 * {@code fk_pending_auth_detail_summary} in {@code db/migration/V1__authorization.sql}: a detail row
 * cannot exist before the summary it hangs from. Loading the child file first would refuse every record.
 *
 * <p>Assumptions: a record whose row is ALREADY PRESENT is counted and skipped, never overwritten, and
 * never a failure. That is the reference program's own treatment of the duplicate-segment status: L256 to
 * L258 report {@code 'ROOT SEGMENT ALREADY IN DB'} and continue, L329 to L331 do the same for a child,
 * and only a status that is neither success nor duplicate reaches the abend at L360 to L366. Making a
 * duplicate an error would turn a re-run of a partially completed load -- the ordinary way an operator
 * recovers one -- into a failure.
 *
 * <p>Refactoring Rationale: the load is idempotent BY THAT SKIP rather than by a separate ledger, which
 * is the property that makes it safely re-runnable and is why the skip is reproduced rather than replaced
 * with an upsert. An upsert would let a second run silently replace a row an operator had since corrected
 * online, which is a loss the reference behaviour cannot produce.
 *
 * <p>Trade-offs: the whole load runs in ONE transaction, so a failure part-way leaves nothing behind. The
 * reference program has no syncpoint of its own and relies on the transaction monitor to commit at
 * program end, so a single unit of work is the closer reading; and the alternative -- committing per
 * record -- would leave a failed load half-applied, which is precisely the state the duplicate skip then
 * has to be trusted to recover from. Keeping it atomic means a re-run starts from a known state. The cost
 * is that a load large enough to exhaust the transaction's resources cannot be split, which the extract
 * this reads does not approach: it is one file per unload of one database.
 */
@Service
public class LoadService {

    /**
     * The log the per-record decisions are reported on, replacing the reference program's console
     * writes.
     *
     * <p>Assumptions: the reference program reports every insert, every duplicate and every failure to
     * the job log with {@code DISPLAY}, and an operator reads that log to decide whether a load
     * succeeded. The equivalent is a structured log rather than standard output, because a container's
     * standard output is not addressable per record.
     */
    private static final Logger LOG = LoggerFactory.getLogger(LoadService.class);

    /**
     * The summary rows, probed for an existing row and inserted when absent.
     */
    private final PendingAuthSummaryRepository summaries;

    /**
     * The authorization rows, probed for an existing row and inserted when absent.
     */
    private final PendingAuthDetailRepository details;

    /**
     * Builds the loader over the two repositories it writes.
     *
     * @param summaries the summary repository the root images are loaded into; must not be {@code null}
     * @param details the authorization repository the child images are loaded into; must not be
     *     {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public LoadService(PendingAuthSummaryRepository summaries,
            PendingAuthDetailRepository details) {
        this.summaries = Objects.requireNonNull(summaries, "summaries must not be null");
        this.details = Objects.requireNonNull(details, "details must not be null");
    }

    /**
     * Loads a whole extract: every root image, then every account-prefixed child record.
     *
     * <p>Assumptions: the two streams are read to exhaustion in this order for the reason the class
     * documentation records, and both are read by the caller's stream rather than opened here, so the
     * caller decides whether they come from a file, from object storage or from a test resource.
     *
     * @param rootImages the summary extract, a whole number of hundred-byte segment images; must not be
     *     {@code null}
     * @param childRecords the detail extract, a whole number of two-hundred-and-six-byte prefixed
     *     records; must not be {@code null}
     * @return the counts of what was read, inserted and skipped across both files; never {@code null}
     * @throws NullPointerException if either stream is {@code null}
     * @throws UncheckedIOException if either stream cannot be read
     * @throws IllegalArgumentException if either stream does not hold a whole number of records, or if a
     *     record is malformed for the layout it declares
     */
    @Transactional
    public LoadOutcome load(InputStream rootImages, InputStream childRecords) {
        Objects.requireNonNull(rootImages, "rootImages must not be null");
        Objects.requireNonNull(childRecords, "childRecords must not be null");
        LoadOutcome roots = loadSummaries(rootImages);
        LoadOutcome children = loadDetails(childRecords);
        return roots.combinedWith(children);
    }

    /**
     * Loads the root images of an extract, skipping every summary already present.
     *
     * @param rootImages the summary extract, a whole number of hundred-byte segment images; must not be
     *     {@code null}
     * @return the counts of what was read, inserted and skipped; never {@code null}
     * @throws NullPointerException if {@code rootImages} is {@code null}
     * @throws UncheckedIOException if the stream cannot be read
     * @throws IllegalArgumentException if the stream does not hold a whole number of segment images, or
     *     if an image is malformed for the summary layout
     */
    @Transactional
    public LoadOutcome loadSummaries(InputStream rootImages) {
        Objects.requireNonNull(rootImages, "rootImages must not be null");
        int read = 0;
        int inserted = 0;
        int alreadyPresent = 0;

        for (byte[] image : records(rootImages, PendingAuthSummaryMapper.unloadRecordLength(),
                "summary")) {
            read++;

            // WHY : Refactoring Rationale: the LOAD-path decode is used here, not the decision-path
            //       toEntity. The two accept different inputs on purpose: toEntity refuses a populated
            //       segment so the decision path cannot silently drop running state, and every parent
            //       record in a real extract IS populated -- cbl/PAUDBUNL.CBL writes the account's
            //       authorization status, its five status occurrences, both balances and all four
            //       counters. Calling toEntity here refused every record a live database produced, so
            //       the accepting decode is the correct one for this caller.
            PendingAuthSummary summary = PendingAuthSummaryMapper.fromExtractRecord(image);
            if (this.summaries.existsById(summary.getAccountId())) {
                alreadyPresent++;
                LOG.info("summary already present, skipped accountId={}", summary.getAccountId());
                continue;
            }
            this.summaries.save(summary);
            inserted++;
        }
        return new LoadOutcome(read, inserted, alreadyPresent, 0);
    }

    /**
     * Loads the account-prefixed child records of an extract, skipping orphans and rows already present.
     *
     * <p>Assumptions: a record whose prefix names an account with NO summary row is skipped rather than
     * refused, and this reproduces the reference program exactly. Its {@code 3100-INSERT-CHILD-SEG}
     * positions on the parent at L296 to L299 and inserts the child only inside the branch that a
     * successful position opens at L305; a position that fails falls past the insert and past the abend
     * test nested inside that branch, so the record is discarded with no message and no failure. That is
     * a defect in the reference program rather than a design -- the abend test it clearly intended to
     * apply to the position is unreachable for a failed position -- and it is reproduced here because
     * refusing the record instead would abort a load the reference completes. The skip IS counted and
     * logged here, which the reference does not do, so an operator can see what was discarded.
     *
     * @param childRecords the detail extract, a whole number of two-hundred-and-six-byte prefixed
     *     records; must not be {@code null}
     * @return the counts of what was read, inserted, skipped as already present and skipped as orphaned;
     *     never {@code null}
     * @throws NullPointerException if {@code childRecords} is {@code null}
     * @throws UncheckedIOException if the stream cannot be read
     * @throws IllegalArgumentException if the stream does not hold a whole number of prefixed records,
     *     or if a record is malformed for the detail layout
     */
    @Transactional
    public LoadOutcome loadDetails(InputStream childRecords) {
        Objects.requireNonNull(childRecords, "childRecords must not be null");
        int read = 0;
        int inserted = 0;
        int alreadyPresent = 0;
        int orphaned = 0;

        for (byte[] record : records(childRecords, PendingAuthDetailMapper.unloadRecordLength(),
                "prefixed detail")) {
            read++;
            PendingAuthDetail detail = PendingAuthDetailMapper.fromUnloadRecord(record);
            PendingAuthDetailKey key = detail.getId();

            if (!this.summaries.existsById(key.getAccountId())) {
                orphaned++;
                LOG.warn("authorization skipped, its account has no summary row accountId={}",
                        key.getAccountId());
                continue;
            }
            if (this.details.existsById(key)) {
                alreadyPresent++;
                LOG.info("authorization already present, skipped accountId={} authDate={}"
                        + " authTime={}", key.getAccountId(), key.getAuthDate(), key.getAuthTime());
                continue;
            }
            this.details.save(detail);
            inserted++;
        }
        return new LoadOutcome(read, inserted, alreadyPresent, orphaned);
    }

    /**
     * Reads a stream as a whole number of fixed-length records.
     *
     * <p>Assumptions: the stream is drained fully and split by the declared stride, and a remainder is a
     * refusal rather than a partial last record. A remainder means the file was produced against a
     * different layout or was truncated in transit, and either way every offset after the first short
     * record would be wrong -- so continuing would load plausible values into the wrong columns rather
     * than failing.
     *
     * <p>Alternatives Considered: streaming record by record so a large extract never sits in memory at
     * once. Rejected for this loader because the whole load is one transaction, so the rows it produces
     * are held until commit regardless, and the images are the smaller half of that: a hundred or two
     * hundred and six bytes against a persistent entity. Reading the stream whole also lets the stride
     * remainder be reported before any row is written rather than after most of them are.
     *
     * @param stream the extract to read; must not be {@code null}
     * @param stride the declared record length in bytes
     * @param description the record kind, used to identify it in a refusal
     * @return each record in file order, as a newly allocated array of exactly {@code stride} bytes
     * @throws UncheckedIOException if the stream cannot be read
     * @throws IllegalArgumentException if the stream length is not a whole multiple of {@code stride}
     */
    private static Iterable<byte[]> records(InputStream stream, int stride, String description) {
        byte[] all;
        try {
            all = stream.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "the " + description + " extract could not be read", unreadable);
        }
        if (all.length % stride != 0) {
            throw new IllegalArgumentException("a " + description + " extract must hold a whole number"
                    + " of " + stride + "-byte records but held " + all.length + " bytes");
        }
        List<byte[]> split = new ArrayList<>(all.length / stride);
        for (int offset = 0; offset < all.length; offset += stride) {
            byte[] record = new byte[stride];
            System.arraycopy(all, offset, record, 0, stride);
            split.add(record);
        }
        return split;
    }

    /**
     * What a load did: how many records it read, inserted, and skipped for each of the two reasons.
     *
     * <p>Assumptions: the two skip reasons are counted SEPARATELY because they mean different things to
     * an operator. A row already present is the ordinary outcome of re-running a load and needs no
     * action; an orphaned child means the summary extract and the detail extract disagree, and the
     * detail records that were discarded are gone from the target until the summary is loaded and the
     * detail file re-run. Summing them into one number would hide the second inside the first.
     *
     * @param read the number of records read from the extract
     * @param inserted the number of rows written
     * @param alreadyPresent the number of records whose row already existed
     * @param orphaned the number of detail records whose account had no summary row
     */
    public record LoadOutcome(int read, int inserted, int alreadyPresent, int orphaned) {

        /**
         * Adds another load's counts to these, for reporting a two-file load as one result.
         *
         * @param other the counts to add; must not be {@code null}
         * @return a new carrier holding the component-wise sum, never {@code null}
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public LoadOutcome combinedWith(LoadOutcome other) {
            Objects.requireNonNull(other, "other must not be null");
            return new LoadOutcome(this.read + other.read, this.inserted + other.inserted,
                    this.alreadyPresent + other.alreadyPresent, this.orphaned + other.orphaned);
        }
    }
}
