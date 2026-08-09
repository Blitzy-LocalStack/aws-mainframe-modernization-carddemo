package com.carddemo.auth.repository;

import com.carddemo.auth.domain.IdentitySyncTask;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The data-access port onto {@code auth.identity_sync_task}, the durable ledger of changes this context
 * still owes the managed user pool.
 *
 * <p>Purpose: supply the two reads an applier performs and nothing else. One read drains the tasks owed
 * for a single user, which is what a write path calls immediately after its own commit; the other drains
 * the oldest pending tasks across all users, which is what a reconciliation pass calls to pick up
 * anything a process death left behind. Keyed and save operations are inherited.</p>
 *
 * <p>Assumptions: BOTH queries are bounded by a {@link Limit} the caller supplies, and neither has an
 * unbounded sibling. An unbounded drain is the failure this ledger would otherwise invite: a deployment
 * that accumulated a backlog while the provider was unreachable would, on the provider's return, load
 * every pending task into one persistence context and issue that many provider calls from one thread.
 * A bound turns that into as many bounded passes as the backlog needs.</p>
 *
 * <p>Assumptions: both queries order by the surrogate key ascending, which is the order the tasks were
 * intended in. That matters for correctness rather than tidiness: a promotion followed by a demotion
 * applied out of order leaves the pool holding the promotion, which is the opposite of what the operator
 * asked for. Ordering across ALL users rather than grouping by user is also what stops one user's
 * repeatedly failing task from starving every task queued behind it -- the abandonment ceiling on the
 * entity removes such a task from this result set altogether once it is reached.</p>
 *
 * <p>Trade-offs: the pending status is a literal in both derived query names rather than a parameter. It
 * costs one method per status, which is acceptable because there is exactly one status an applier ever
 * selects on, and it buys queries whose names state which subset they read -- so a reader does not have
 * to find the call site to learn whether a settled task can be returned here.</p>
 */
public interface IdentitySyncTaskRepository extends JpaRepository<IdentitySyncTask, Long> {

    /**
     * Reads the pending tasks owed for one user, oldest intention first.
     *
     * <p>Assumptions: this is the query a write path issues immediately after its own transaction commits,
     * so it is narrowed to the one user whose row just changed. Narrowing matters because the alternative
     * -- draining every pending task on every write -- would make one caller's request latency depend on
     * an unrelated backlog.</p>
     *
     * @param userId the eight-character identifier whose owed changes are wanted; must not be
     *     {@code null}
     * @param status the status to select, always the pending one at every call site
     * @param limit the greatest number of tasks to return; must not be {@code null}
     * @return the matching tasks in ascending task-identifier order, never {@code null}
     */
    List<IdentitySyncTask> findByUserIdAndStatusOrderByTaskIdAsc(
            String userId, String status, Limit limit);

    /**
     * Reads the oldest pending tasks across every user, for a reconciliation pass.
     *
     * <p>Assumptions: this is the query that makes the ledger a ledger rather than a log. A task recorded
     * by a request whose process then died is invisible to that request's own per-user drain, because
     * there is no longer a request; this query is how such a task is found and applied.</p>
     *
     * @param status the status to select, always the pending one at every call site
     * @param limit the greatest number of tasks to return in this pass; must not be {@code null}
     * @return the matching tasks in ascending task-identifier order, never {@code null}
     */
    List<IdentitySyncTask> findByStatusOrderByTaskIdAsc(String status, Limit limit);
}
