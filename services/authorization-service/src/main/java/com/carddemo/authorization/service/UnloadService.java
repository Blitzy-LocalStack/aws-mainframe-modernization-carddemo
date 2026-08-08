package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.mapper.PendingAuthDetailMapper;
import com.carddemo.authorization.mapper.PendingAuthSummaryMapper;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk-exports pending-authorization summary and detail rows to the two extract files a load reads back.
 *
 * <p><strong>Purpose.</strong> Carry across the two reference unload programs, which perform the SAME
 * walk and differ only in the record shape they write.
 * {@code app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL} walks roots at
 * {@code 2000-FIND-NEXT-AUTH-SUMMARY} L207 to L247 and children at {@code 3000-FIND-NEXT-AUTH-DTL} L253
 * to L284, writing a hundred-byte root at its L233 and an ACCOUNT-PREFIXED child of two hundred and six
 * bytes at its L271. {@code app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL} performs the identical
 * walk at its L216 to L257 and L263 to L295, but writes both records through sequential inserts --
 * {@code 3100-INSERT-PARENT-SEG-GSAM} at L300 to L315 and {@code 3200-INSERT-CHILD-SEG-GSAM} at L319 to
 * L334 -- each passing the SEGMENT alone, so its child record is two hundred bytes with no prefix. Every
 * citation is relative to that tree, which is reference material this migration reads and never modifies.
 *
 * <p>Refactoring Rationale: the two programs become one service with a form parameter rather than two
 * classes, because the difference between them is one field of one record. Their walks are
 * character-for-character the same, including the numeric guard on the account identifier and the
 * end-of-database and end-of-children status tests, so two classes would be two copies of one walk with a
 * six-byte difference at the end -- and a fix to one walk would silently apply to only half the export
 * paths. The form is named at the call site instead, where the caller already knows which reader will
 * consume the file.
 *
 * <p>Assumptions: the root file is IDENTICAL between the two forms and only the child file differs. Both
 * reference programs move the whole hundred-byte summary segment with no prefix -- L227 and L236
 * respectively -- and neither adds anything to it, because a root segment's first six bytes already ARE
 * its key. Prefixing the root as well would produce a file the load's root reader could not divide.
 *
 * <p>Trade-offs: the export streams row by row and holds no more than one account's children in memory,
 * where the reference program held one segment. It is read-only and marked so, which lets the provider
 * skip dirty checking on every row it materialises -- the difference between reading an extract and
 * accumulating one.
 */
@Service
public class UnloadService {

    /**
     * The lowest account identifier the walk can start below.
     *
     * <p>Assumptions: zero, and the walk seeks strictly ABOVE it, so the first page begins at the lowest
     * real account. The identifier is a packed eleven-digit value the reference program guards with a
     * numeric test at {@code cbl/PAUDBUNL.CBL} L232, and the schema declares the column a positive
     * identifier, so no stored account can be at or below zero and none can be skipped by starting here.
     */
    private static final long BEFORE_FIRST_ACCOUNT = 0L;

    /**
     * The number of summary rows fetched per step of the outer walk.
     *
     * <p>Assumptions: the walk is PAGED rather than materialising every summary at once, because the
     * reference walk holds exactly one root at a time and an export must not need memory proportional to
     * the database. Two hundred is a batch size and not a contract: the paging is keyed, so the boundary
     * between pages is not observable in the output, and a reader must not depend on this number.
     */
    private static final int SUMMARY_PAGE_SIZE = 200;

    /**
     * The summary rows, walked in ascending key order.
     */
    private final PendingAuthSummaryRepository summaries;

    /**
     * The authorization rows, walked beneath each summary in the reference twin order.
     */
    private final PendingAuthDetailRepository details;

    /**
     * Builds the exporter over the two repositories it reads.
     *
     * @param summaries the summary repository the outer walk reads; must not be {@code null}
     * @param details the authorization repository the inner walk reads; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public UnloadService(PendingAuthSummaryRepository summaries,
            PendingAuthDetailRepository details) {
        this.summaries = Objects.requireNonNull(summaries, "summaries must not be null");
        this.details = Objects.requireNonNull(details, "details must not be null");
    }

    /**
     * Writes the whole pending-authorization database to a root file and a child file.
     *
     * <p>Assumptions: both files are written by ONE walk, interleaved exactly as the reference program
     * interleaves them -- a root is written, then every child beneath it, then the next root. The two
     * files therefore agree on the order of accounts, which is what lets a load read the roots first and
     * the children second and still find each parent present. Walking the two tables independently would
     * produce the same rows in an order nothing guaranteed to agree.
     *
     * <p>Assumptions: an account whose identifier is absent is SKIPPED together with all of its children,
     * reproducing the numeric guard at {@code cbl/PAUDBUNL.CBL} L232 and {@code cbl/DBUNLDGS.CBL} L241,
     * whose false branch writes neither the root nor any child. The guard is unreachable through the
     * schema, which declares the column not null, and is reproduced so a reader comparing the two walks
     * finds the same structure.
     *
     * @param form which reference program's record shapes to write; must not be {@code null}
     * @param rootFile the stream the summary images are written to; must not be {@code null}
     * @param childFile the stream the authorization records are written to; must not be {@code null}
     * @return the counts of roots and children written; never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws UncheckedIOException if either stream cannot be written
     * @throws IllegalStateException if a stored row cannot be encoded into the record its layout declares
     */
    @Transactional(readOnly = true)
    public UnloadOutcome unload(UnloadForm form, OutputStream rootFile, OutputStream childFile) {
        Objects.requireNonNull(form, "form must not be null");
        Objects.requireNonNull(rootFile, "rootFile must not be null");
        Objects.requireNonNull(childFile, "childFile must not be null");

        int rootsWritten = 0;
        int childrenWritten = 0;
        long position = BEFORE_FIRST_ACCOUNT;

        while (true) {
            List<PendingAuthSummary> page = this.summaries
                    .findByAccountIdGreaterThanOrderByAccountIdAsc(Long.valueOf(position),
                            Limit.of(SUMMARY_PAGE_SIZE));
            if (page.isEmpty()) {
                return new UnloadOutcome(rootsWritten, childrenWritten);
            }
            for (PendingAuthSummary summary : page) {
                Long accountId = summary.getAccountId();
                position = accountId.longValue();
                write(rootFile, PendingAuthSummaryMapper.toSegment(summary));
                rootsWritten++;
                childrenWritten += writeChildren(form, childFile, accountId);
            }
        }
    }

    /**
     * Writes every authorization beneath one account in the reference twin order.
     *
     * @param form which record shape the child file carries; never {@code null}
     * @param childFile the stream the records are written to; never {@code null}
     * @param accountId the account whose authorizations are written; never {@code null}
     * @return the number of records written for that account
     * @throws UncheckedIOException if the stream cannot be written
     */
    private int writeChildren(UnloadForm form, OutputStream childFile, Long accountId) {
        List<PendingAuthDetail> children =
                this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(accountId);
        for (PendingAuthDetail child : children) {
            // WHY : Assumptions: the two forms differ ONLY here, which is the whole reason one service
            //       serves both. The hierarchical unload writes the account identifier ahead of the
            //       segment because its reader has no other way to attribute a child; the sequential
            //       unload writes the segment alone because its two data sets are consumed as a pair by a
            //       program that already knows the parent it is positioned on.
            write(childFile, switch (form) {
                case PREFIXED -> PendingAuthDetailMapper.toUnloadRecord(child);
                case SEQUENTIAL -> PendingAuthDetailMapper.toSegment(child);
            });
        }
        return children.size();
    }

    /**
     * Writes one fixed-length record, translating a write failure into an unchecked one.
     *
     * <p>Assumptions: the stream is NOT flushed or closed here. The caller owns it -- it may be a file, a
     * buffer or an object-storage upload -- and closing a stream a caller still intends to write the
     * second half of the export to would end the export half-written with no error.
     *
     * @param target the stream to write to; never {@code null}
     * @param record the record bytes to write; never {@code null}
     * @throws UncheckedIOException if the stream cannot be written
     */
    private static void write(OutputStream target, byte[] record) {
        try {
            target.write(record);
        } catch (IOException unwritable) {
            throw new UncheckedIOException("an unload record could not be written", unwritable);
        }
    }

    /**
     * Which reference program's record shapes an export writes.
     *
     * <p>Assumptions: the two constants name the reference programs' output shapes and not their storage
     * technologies, which is why neither is called after the access method it happened to use. What a
     * consumer needs to know is the child record's stride, and that is what each constant states.
     */
    public enum UnloadForm {

        /**
         * The hierarchical form: a hundred-byte root and a child prefixed with its packed parent key.
         *
         * <p>Assumptions: this is {@code cbl/PAUDBUNL.CBL}, whose child output record is declared at its
         * L46 to L48 as a {@code PIC S9(11) COMP-3} parent key ahead of a {@code PIC X(200)} segment,
         * giving two hundred and six bytes. It is the form {@code LoadService} reads back, because the
         * prefix is the only thing that attributes a child to a parent once the two are in flat files.
         */
        PREFIXED,

        /**
         * The sequential form: a hundred-byte root and a bare two-hundred-byte child segment.
         *
         * <p>Assumptions: this is {@code cbl/DBUNLDGS.CBL}, whose two inserts at L302 to L304 and L321 to
         * L323 each pass the SEGMENT alone with no prefix. A child written this way cannot be attributed
         * to an account from its own bytes, so a consumer must rely on the interleaved order of the two
         * files; that is the property the committed fixture pair asserts, and it is why the two forms are
         * both kept rather than the prefixed one being treated as a superset.
         */
        SEQUENTIAL
    }

    /**
     * What an export wrote: the number of root images and the number of child records.
     *
     * @param rootsWritten the number of summary images written to the root file
     * @param childrenWritten the number of authorization records written to the child file
     */
    public record UnloadOutcome(int rootsWritten, int childrenWritten) {
    }
}
