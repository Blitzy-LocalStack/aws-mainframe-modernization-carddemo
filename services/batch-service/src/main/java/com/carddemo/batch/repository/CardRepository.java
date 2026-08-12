package com.carddemo.batch.repository;

import com.carddemo.batch.domain.Card;
import jakarta.persistence.QueryHint;
import java.util.stream.Stream;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Walks the card master in key order for the branch-migration export, and does nothing else.
 *
 * <p>The table behind this interface is {@code card.cards}, and the card context OWNS it. One migrated
 * program reads it: {@code app/cbl/CBEXPORT.cbl} declares the card file
 * {@code ORGANIZATION IS INDEXED} with {@code ACCESS MODE IS SEQUENTIAL}, opens it {@code OPEN INPUT}
 * among the five inputs at {@code app/cbl/CBEXPORT.cbl:200-233}, and reads it to end of file at
 * {@code app/cbl/CBEXPORT.cbl:513}, writing one {@code 'D'} record per row. That one ordered walk is
 * the whole of what this interface has to offer.</p>
 *
 * <h2>Refactoring Rationale: why this interface exists now, having deliberately not existed</h2>
 *
 * <p>The charter beside this file recorded the interface roster as closed, and the domain charter
 * recorded that no card mapping was missing. Both were reasoned from {@code app/cbl/CBTRN01C.cbl},
 * which opens the card file and never reads a record from it, and both were sound for that program.
 * Neither extended to {@code app/cbl/CBEXPORT.cbl}, which reads all five masters. An earlier note also
 * held that this module had no privilege on the {@code card} schema; that reading was out of date --
 * {@code data-migration/sql/V0__schemas_and_roles.sql:1140} grants the batch role usage on
 * {@code card} and {@code :1268} grants it {@code SELECT} on every table in it, which includes this
 * one -- so only the Java seam was outstanding, and this interface is it.</p>
 *
 * <h2>Why the base type is narrowed rather than inherited</h2>
 *
 * <p>Alternatives Considered: {@code JpaRepository} and {@code CrudRepository} were both evaluated and
 * both rejected, because each inherits {@code save}, {@code saveAll}, {@code delete} and
 * {@code deleteAll}. <b>This module holds no write grant on the {@code card} schema at all</b> -- its
 * cross-schema write grants are scoped to {@code ledger} and {@code account} -- and {@link Card}
 * carries Hibernate's immutability marker. An inherited mutator would therefore compile cleanly, pass
 * review, and fail AT THE DATABASE partway through an operator-invoked export.
 * {@code org.springframework.data.repository.Repository} contributes no member of its own while still
 * giving Spring Data enough to build a proxy, so the reachable surface is exactly the one method
 * declared below. Trade-offs: a convenience method must be declared explicitly rather than inherited,
 * which is a small amount of extra declaration in exchange for making an unavailable capability
 * unreachable. This is the same ruling {@link DisclosureGroupRepository} records for the same
 * reason.</p>
 *
 * <h2>Assumptions: the verification value is unreachable from here</h2>
 *
 * <p>{@code card.cards.cvv_encrypted} holds an envelope that only the card context can open, and
 * {@link Card} does not map it. A row returned by this interface therefore cannot carry a card
 * verification value in any form, enciphered or otherwise. That is a structural property of the
 * mapping rather than a promise about callers, and it is what keeps this read from being a disclosure
 * of sensitive authentication data: the export writes an encoded zero into that span, registered as
 * divergence {@code D-EXPORT-PROTECTED-SPANS-REDACTED} in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * @see Card
 * @see com.carddemo.batch.job.ExportJob
 */
public interface CardRepository extends Repository<Card, String> {

    /**
     * Walks every card row in ascending card-number order.
     *
     * <p>Assumptions: the ordering is declared on the method name rather than left to the query
     * planner, for the reason the sibling customer interface records: the reference reads an indexed
     * file sequentially, and the export assigns sequence numbers in emit order, so an unordered read
     * would emit the same set of records under different sequence numbers on two runs of the same
     * data.</p>
     *
     * <p>Assumptions: the walk is one record per CARD and not one per account. An account legitimately
     * holds several cards -- {@code app/jcl/CARDFILE.jcl} defines the by-account index
     * {@code NONUNIQUEKEY} -- and {@code app/cbl/CBEXPORT.cbl:498-513} reads the card file to end of
     * file rather than positioning it by account, so a per-account read would emit one card per account
     * and silently omit the rest. That is precisely the defect the cross-reference phase of the same
     * job was corrected for.</p>
     *
     * <p>Trade-offs: a stream rather than a list, matching the sibling interfaces, because the card
     * master is unbounded in principle. The cost is a resource the caller has to release, which is why
     * the obligation is stated on the return tag below and why the export opens it in a
     * try-with-resources block.</p>
     *
     * @return a lazily-evaluated {@code Stream<Card>} over every card in ascending card-number order;
     *     never {@code null}, possibly empty, and the CALLER owns closing it
     * @throws org.springframework.dao.InvalidDataAccessApiUsageException if the stream is opened
     *     without a surrounding transaction, which {@link Propagation#MANDATORY} refuses outright
     *     rather than letting a cursor be opened that nothing keeps alive
     */
    // Assumptions: MANDATORY propagation is applied for the same reason the customer walk applies it --
    //     a Stream holds an open server-side cursor that must not outlive its transaction, and refusing
    //     the untransacted call at the boundary is clearer than diagnosing a closed connection several
    //     frames away. The export step supplies that transaction, so its only caller pays nothing.
    // Trade-offs: readOnly is retained even though the narrow base type, the absent write grant and
    //     the entity's immutability marker are what enforce read-only access. It states the intent at
    //     the method and sets the flush mode to manual, so the surrounding persistence context cannot
    //     be flushed as a side effect of this walk.
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    // Assumptions: the fetch-size hint is what makes this stream actually stream. The driver opens a
    //     server-side cursor only when a positive fetch size and a non-auto-commit connection both
    //     hold; with either missing it buffers the whole result client-side, which on an unbounded
    //     master is heap exhaustion rather than a slowdown. Only POSITIVITY carries that property, so
    //     the streaming contract rests on the value being above zero and not on which value it is.
    // Trade-offs: the hint is a per-query OVERRIDE of the session default, which the profiles set to
    //     25 in development and 250 in production while the base sets 100. What is given up is
    //     per-environment tuning of this one walk; what is bought is a window fixed at the method,
    //     which no external property can set to zero and thereby turn into a full client-side buffer.
    //     The constant is aligned with the base value so the default deployment behaves identically
    //     whichever governs, and it matches the three sibling walks for the same reason.
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "100"))
    Stream<Card> findAllByOrderByCardNumAsc();
}
