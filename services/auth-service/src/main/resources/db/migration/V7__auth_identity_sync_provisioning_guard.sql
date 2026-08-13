-- =============================================================================
-- V7__auth_identity_sync_provisioning_guard.sql
--
-- Purpose
--   Admit two further states on auth.identity_sync_task so that the CREATE path
--   can record its intention BEFORE it calls the managed provider, and so that a
--   recorded intention which turns out not to be owed can be closed as such
--   rather than reported as a provider call that happened.
--
-- What defect this closes
--   The create path could not record an intention first and therefore recorded
--   nothing until it had already failed. auth.users.cognito_sub is NOT NULL and
--   only the provider can mint the subject, so the sequence was: probe, create
--   the pool account, insert the row. A process death BETWEEN the create and the
--   insert left a pool account that can authenticate, holds a group, and has no
--   row in this schema -- and nothing recorded that it existed. Nothing could
--   therefore reconcile it: the in-process catch arms that record a WITHDRAW are
--   not reached when the process is gone. A later create for the same identifier
--   was then refused on the provider's own duplicate-username condition, so the
--   identifier read as taken while no row named it, and the only evidence was a
--   log line nobody was reading at the moment it was written.
--   Assumptions: the remedy is a WITHDRAW recorded and COMMITTED before the
--   provider call -- a provisioning guard. If the create completes, the guard is
--   settled in the same transaction as the insert, so the row and the guard's
--   closure are one atomic fact. If anything interrupts the create, the guard
--   survives as a pending row the reconciliation pass owns.
--
-- Why CLAIMED is admitted
--   Assumptions: a guard is recorded before the act it compensates, so between
--   its commit and the insert's commit there is a window in which the intention
--   is durable and not yet owed. A scheduled reconciliation pass that applied it
--   in that window would withdraw the pool account of a create still in flight.
--   CLAIMED names that window: the guard is recorded in this state, the
--   reconciliation pass ignores it, and the two paths that know the outcome move
--   it on -- to CANCELLED when the create completed or the provider refused, and
--   to PENDING when the create failed after the account may already exist.
--   Alternatives Considered: leaving the guard PENDING and having the pass skip a
--   row younger than a grace period. Rejected as a weaker form of the same
--   control: a grace period is a guess about how long a create can take, and a
--   pass that ran late would still act inside the window it was meant to avoid.
--   A state the pass does not select cannot be raced at all.
--   Also considered: recording the guard in the SAME transaction as the insert,
--   which needs no new state. Rejected because that transaction does not exist
--   yet when the provider is called -- which is the whole reason the create path
--   could not record an intention first.
--
-- Why CANCELLED is admitted rather than reusing APPLIED
--   Assumptions: APPLIED means the provider confirmed the change. A guard that
--   was never owed -- because the row was written, or because the provider
--   refused the username and created nothing -- had no provider call at all, so
--   recording it as APPLIED would put a false event in the one table an operator
--   reads to answer "was this pool account withdrawn". CANCELLED says the
--   intention was closed without a call, which is the fact.
--   Trade-offs: two more values for a reader to learn, against a ledger whose
--   terminal states can be trusted. Accepted because this table exists precisely
--   to be read after an incident.
--
-- Why the constraint is dropped and re-added rather than altered
--   Assumptions: a CHECK constraint has no in-place redefinition in this engine,
--   so the pair of statements IS the alteration. The name is reused so the
--   constraint a reader finds is the one V2 documents.
--
-- Why the pending index is left alone
--   Assumptions: idx_identity_sync_task_pending is partial on status = 'PENDING'
--   and that is still exactly the set both drains select, because a CLAIMED guard
--   is deliberately invisible to them. A second partial index on CLAIMED would
--   serve no query: the two paths that move a claimed guard on hold its task
--   identifier already and reach it by primary key.
--
-- Why no data is migrated
--   Assumptions: no row can hold either new value, because no code path could
--   write them before this migration. The re-added constraint is therefore
--   validated against existing rows without a rewrite, and a hand-inserted row
--   holding an unadmitted value would fail the ADD loudly here rather than
--   leaving an unenforceable constraint behind.
-- =============================================================================

ALTER TABLE auth.identity_sync_task
    DROP CONSTRAINT ck_identity_sync_task_status;

ALTER TABLE auth.identity_sync_task
    ADD CONSTRAINT ck_identity_sync_task_status
    CHECK (status IN ('CLAIMED', 'PENDING', 'APPLIED', 'CANCELLED', 'ABANDONED'));

-- WHY : Assumptions: the comment is rewritten in the same migration that widens
--   the constraint, because the two are one fact. A column comment naming three
--   states where the constraint admits five is the document an operator reads
--   before deciding whether a row they are looking at can be acted on.
COMMENT ON COLUMN auth.identity_sync_task.status IS
    'CLAIMED while an intention is recorded but not yet owed, which is how the '
    'create path makes its compensation durable before it calls the provider; '
    'PENDING while the change is owed and the drains will apply it; APPLIED once '
    'the provider confirmed it; CANCELLED once it was closed without a provider '
    'call because it turned out not to be owed; ABANDONED once the attempt '
    'ceiling was reached, which is the state an operator reconciles by hand.';
