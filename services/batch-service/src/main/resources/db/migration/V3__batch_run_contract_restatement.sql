-- =============================================================================
-- services/batch-service/src/main/resources/db/migration/V3__batch_run_contract_restatement.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Restate the post-state of batch.batch_run accurately, and complete the
--   catalogue commentary that describes it. V1__batch.sql's "Post-state
--   established" header block says the table has "seven columns and five named
--   constraints" and describes five. The table it creates has EIGHT columns and
--   SEVEN named constraints. The two the description omits are the ones added
--   with the redrive counter -- the counter itself, and the coherence rule
--   between the two timestamps -- and the omission is not confined to prose:
--   V1 issues a COMMENT ON COLUMN for seven of its eight columns and for none of
--   its seven constraints, so the database's own record of the contract stops
--   exactly where the description stops.
--
--   This file closes both halves. It states the accurate post-state here, where
--   the description can be maintained, and it writes the missing commentary into
--   the catalogue so that the operator V1 wrote its comments for -- one
--   inspecting the table from a session, during a failed nightly run, with no
--   access to this repository -- reads eight columns and seven named rules
--   rather than seven and five.
--
-- Why this is a NEW migration and not an edit to V1__batch.sql:
--   V1 has been applied. Flyway's checksum covers the whole file, comments
--   included, so an edit to its header block -- however small, and even though
--   it changes no DDL -- changes the checksum and every environment that already
--   ran the earlier bytes refuses to start under the validate-on-migrate and
--   clean-disabled posture these services deliberately run with. That is not a
--   hypothetical: it has happened twice in this repository, to V1__reference.sql
--   and to this very file, and
--   services/common-lib/src/test/java/com/carddemo/common/architecture/
--   ReleasedMigrationImmutabilityTest.java now holds every released migration to
--   its released bytes so that it cannot happen a third time. V1__account.sql's
--   own header states the rule this file follows: a later change arrives as a new
--   versioned migration, never as an edit to a released one.
--
--   Trade-offs: the consequence, stated plainly rather than left for a reader to
--   discover, is that V1's header block still reads "seven columns and five named
--   constraints" and cannot be corrected in place. A reader who opens V1 alone
--   reads a stale count. What this file can do -- and does -- is make the
--   accurate statement exist in the migration set, in the catalogue, and in the
--   module's own documentation, so that every route to the contract other than
--   that one frozen comment block agrees. Alternatives Considered: correcting V1
--   and re-recording its digests in the immutability guard in the same commit.
--   Rejected: it is the precise action that guard exists to refuse, and the cost
--   of being wrong about whether an environment holds the old checksum is a
--   service that will not start, against a benefit of one accurate comment.
--
-- Preconditions (what must already be true when Flyway applies this file):
--   - V1__batch.sql and V2__batch_feed_watermark.sql have been applied, so
--     batch.batch_run exists with all eight columns and all seven named
--     constraints. This file adds no object and alters no definition; it only
--     writes catalogue commentary, so it fails loudly if any of them is absent.
--   - The connecting role is carddemo_batch, which owns the table. COMMENT
--     requires ownership, so a migration applied under any other role fails here
--     rather than silently leaving the catalogue as it was.
--
-- Post-state established (what exists after this file has been applied):
--   - batch.batch_run, unchanged in shape, with EIGHT columns and SEVEN named
--     constraints:
--       * Columns: id (surrogate identity key), run_id (orchestrator execution),
--         step_name (state within that execution), status (lifecycle state),
--         started_at, finished_at, return_code (mainframe condition-code tier)
--         and attempt (redrive counter).
--       * Constraints: pk_batch_run, the surrogate identity primary key;
--         uq_batch_run_run_step, uniqueness over the natural key (run_id,
--         step_name), which is what makes a redrive of an already-recorded step
--         collide rather than post twice; ck_batch_run_status, the lifecycle-state
--         domain; ck_batch_run_return_code, the exit-status tier domain (null, 0,
--         4, or 8 and above); ck_batch_run_lifecycle, the state-versus-timestamp
--         -versus-return-code coherence rule; ck_batch_run_attempt, which refuses
--         an attempt count below one because a row exists only once an attempt has
--         begun; and ck_batch_run_finished_after_started, which refuses a finish
--         instant before the start instant while admitting a null finish for a
--         step still running.
--   - A comment on every one of those eight columns and on every one of those
--     seven constraints, so the catalogue carries the whole contract rather than
--     the part of it V1 described.
--   - No new table, no new index, no new constraint and no altered column. The
--     absence is the point: the physical schema was always correct and only its
--     description was not.
-- =============================================================================

-- WHY : Assumptions: the eighth column's comment is written here rather than being
--       absent, and its absence in V1 is the same defect as the miscount rather
--       than a separate one. V1 comments id, run_id, step_name, status,
--       started_at, finished_at and return_code -- seven -- and stops. An operator
--       reading the catalogue during a failed run therefore found every column
--       explained except the one that tells them how many times the step had
--       already been attempted, which is the first thing a redrive raises.
COMMENT ON COLUMN batch.batch_run.attempt IS
    'How many times this (run_id, step_name) pair has been attempted, counting from '
    '1 for the first attempt. A redrive UPDATES this row and increments the counter '
    'rather than inserting a second one, so the pair stays unique and the ledger '
    'keeps one durable record per step. Unbounded above deliberately: a redrive is an '
    'operator action, and refusing the write would lose the record of the attempt '
    'that mattered most.';

-- WHY : Assumptions: constraints are commented as well as columns, and the reason is
--       the reason V1 gives for naming them at all -- a violated constraint reports
--       its own name and nothing else. Naming makes the rule identifiable; the
--       comment is what makes it legible to the operator who has just been handed
--       the name by a failed insert, in the same session, without this repository.
--       Alternatives Considered: leaving the rules described in the migration text
--       only, which is where V1 left them. Rejected because the session that needs
--       them is exactly the session that does not have the file.
COMMENT ON CONSTRAINT pk_batch_run ON batch.batch_run IS
    'Surrogate identity primary key. The natural key is (run_id, step_name) and is '
    'enforced by uq_batch_run_run_step instead, because both of its parts are '
    'externally supplied strings.';

COMMENT ON CONSTRAINT uq_batch_run_run_step ON batch.batch_run IS
    'Uniqueness over the natural key. This is the redrive-idempotency mechanism: a '
    'redrive of an already-recorded step collides here instead of inserting, so the '
    'step becomes a no-op rather than a repetition -- and re-running a completed '
    'posting step is not a wasted rerun, it posts twice. The baseline had no '
    'checkpoint contract at all: the only RESTART= among the thirty-eight jobs in '
    'app/jcl is commented out, so a failed step was resubmitted by hand from the top.';

COMMENT ON CONSTRAINT ck_batch_run_status ON batch.batch_run IS
    'Lifecycle-state domain: STARTED, COMPLETED or FAILED and nothing else. A state '
    'outside the domain would be read by the repository as neither running nor '
    'finished, and a redrive would then neither skip the step nor re-run it.';

COMMENT ON CONSTRAINT ck_batch_run_return_code ON batch.batch_run IS
    'Exit-status tier domain, mirroring the mainframe condition-code rubric: null '
    'while the step runs, 0 for success, 4 for a soft warn -- a business-rule reject '
    'correctly written -- and 8 or above for failure. Values 1 to 3 and 5 to 7 are '
    'refused because nothing emits them and a branch on "greater than 4" would read '
    'them as failure while a branch on "equals 8" would not.';

COMMENT ON CONSTRAINT ck_batch_run_lifecycle ON batch.batch_run IS
    'Coherence between the state, the finish instant and the exit status: STARTED '
    'carries neither a finish nor a code, COMPLETED carries both with the code in '
    '(0, 4), and FAILED carries a finish with either no code or one of 8 and above. '
    'Without it a row could claim to be running while carrying an exit status, which '
    'a redrive would read as a completed step and skip.';

COMMENT ON CONSTRAINT ck_batch_run_attempt ON batch.batch_run IS
    'An attempt count below one describes a row that exists before its first attempt '
    'began, which the ledger has no way to produce. No upper bound: capping the count '
    'would refuse the write rather than the redrive.';

COMMENT ON CONSTRAINT ck_batch_run_finished_after_started ON batch.batch_run IS
    'A finish instant may not precede the start instant. The null arm is stated '
    'explicitly rather than left to three-valued logic: a comparison against null '
    'evaluates to unknown and a CHECK treats unknown as satisfied, so a running step '
    'would already pass -- but as a side effect of null semantics rather than as a '
    'decision a reader can see.';
