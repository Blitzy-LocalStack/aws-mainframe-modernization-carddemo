-- =============================================================================
-- V3__auth_identity_sync_operations.sql
--
-- Purpose
--   Narrow auth.identity_sync_task.operation to the TWO verbs a write path can
--   actually record, withdrawing 'PROVISION' from the admitted set.
--
-- Why this exists as a third migration rather than an edit to V2
--   V2 declared the constraint with three values and is already committed, so a
--   deployed database has recorded its checksum. Editing it in place would make
--   the migration engine refuse to run against any database it had already been
--   applied to, which turns a schema correction into a failed deployment. A new
--   migration expresses the change once and leaves every applied history valid.
--
-- Why 'PROVISION' is withdrawn
--   Assumptions: no write path can ever record it, and the reason is a column
--   constraint one table over. auth.users.cognito_sub is NOT NULL, and the
--   subject it holds is minted by the managed provider when the pool account is
--   created -- so the account must exist BEFORE the row can be written, and the
--   create path cannot record an intention first and apply it afterwards the way
--   the update and delete paths do. It provisions inline and compensates by
--   recording a WITHDRAW if the row write then fails.
--   Trade-offs: the value was admitted so that a future create path could use
--   the ledger, and the applier carried a branch for it. What that cost is a
--   branch no deployment can reach, an operator who reads three verbs in the
--   column comment and can produce only two, and a hand-inserted 'PROVISION' row
--   that would be attempted four times and then abandoned. Narrowing the
--   constraint makes the column's domain and the applier's branches the same set.
--   Alternatives Considered: making the create path record 'PROVISION' so the
--   value became reachable. Rejected because it cannot: the row it would be
--   recorded alongside cannot be inserted until provisioning has already
--   happened, so the intention would always be recorded after the act it
--   describes.
--
-- Why the constraint is dropped and re-added rather than altered
--   Assumptions: a CHECK constraint has no in-place redefinition in this engine,
--   so the pair of statements IS the alteration rather than a workaround. The
--   name is reused so that the constraint a reader finds on the table is the one
--   V2 documents, and so this migration cannot silently leave two constraints
--   where a later reader expects one.
--
-- Why no data is migrated
--   Assumptions: no row can hold the withdrawn value, because no code path ever
--   wrote it -- the only two callers of the recording method name SYNCHRONISE and
--   WITHDRAW. The re-added constraint is therefore validated against existing
--   rows without a rewrite step, and if a hand-inserted row did hold the value
--   the ADD would fail loudly here rather than leaving an unenforceable
--   constraint behind, which is the outcome to prefer.
-- =============================================================================

ALTER TABLE auth.identity_sync_task
    DROP CONSTRAINT ck_identity_sync_task_operation;

ALTER TABLE auth.identity_sync_task
    ADD CONSTRAINT ck_identity_sync_task_operation
    CHECK (operation IN ('SYNCHRONISE', 'WITHDRAW'));

-- WHY : Assumptions: the comment is rewritten in the same migration that narrows
--   the constraint, because the two are one fact. A column comment naming a verb
--   the constraint refuses is worse than no comment: it is the document an
--   operator reads before hand-inserting a reconciliation row.
COMMENT ON COLUMN auth.identity_sync_task.operation IS
    'SYNCHRONISE or WITHDRAW: the two provider verbs a committed auth.users '
    'change can owe. Account creation is not among them because auth.users '
    'requires the subject the create call mints, so the create path provisions '
    'inline and records a WITHDRAW if its own row write then fails.';
