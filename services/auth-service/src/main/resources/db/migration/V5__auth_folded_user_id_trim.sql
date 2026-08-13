-- =============================================================================
-- V5__auth_folded_user_id_trim.sql
--
-- Purpose
--   Close the gap V4__auth_folded_user_id.sql left open: make the database
--   constraint on auth.users.user_id assert the WHOLE fold the service derives,
--   which is upper-case AND blank-trimmed, rather than the upper-case half
--   alone. It also rejects an all-blank key, folds the rows it can fold safely,
--   and then ATTEMPTS to validate itself so that a database needing operator
--   attention says so in its log instead of refusing to start.
--
-- What V4 left reachable
--   V4 asserted CHECK (user_id = upper(user_id)) and recorded, in its own WHY
--   block, that LEADING blanks were deliberately not constrained -- on the
--   reasoning that a leading blank is a different property from the fold and that
--   the identifier's shape is already asserted at the adapter where it arrives.
--   That reasoning is reversed here, and the reversal is the point of this file.
--   UserService#foldedKey derives submitted.trim().toUpperCase(Locale.ROOT), so
--   trimming is not a separate well-formedness concern -- it is HALF OF THE KEY
--   DERIVATION. Because upper() leaves a leading blank untouched, V4's predicate
--   admits ' BND0001', while every service path looks that row up as 'BND0001'
--   and never finds it. That is the identical unreachable-row defect V4 was
--   written to eliminate, surviving in the one input shape V4 chose not to
--   cover, and the "asserted at the adapter" argument does not reach it: the
--   whole reason the constraint exists is the writer that does NOT come through
--   the adapter.
--   The consequences are the same three V4 measured, so they are not restated
--   here: a row reachable by no spelling, two rows for one identifier, and an
--   update or delete that names one record and alters another.
--
-- Why a fifth migration rather than an edit to V4
--   Assumptions: V4 is already applied on every deployed database and its
--   checksum is recorded, so editing it in place would make the migration engine
--   refuse to run at all -- turning a schema correction into a failed
--   deployment. V3 and V4 each took this course for this reason and record it;
--   this file follows the established practice rather than restating the
--   argument.
--
-- Why the constraint keeps its name
--   Assumptions: the replacement reuses ck_users_user_id_folded rather than
--   introducing ck_users_user_id_folded_trimmed. The name appears in an
--   operator's reconciliation runbook, in the COMMENT a refusal sends a reader
--   to, and in the Javadoc of UserService#foldedKey, and a refusal reports the
--   name and nothing else. Renaming would strand all three and buy a reader
--   nothing: the constraint asserts the same invariant, more completely.
--   Trade-offs: the cost is that a database inspected between V4 and V5 and one
--   inspected after V5 report the same name for two different predicates. The
--   COMMENT below carries the predicate in words for exactly that reason, so the
--   name is not the only thing an inspector has.
--
-- Why the rows are folded here when V4 declined to fold them
--   Assumptions: V4 declined because the fold can COLLIDE -- the defect's
--   characteristic outcome is two rows for one key, and folding the unfolded one
--   violates the primary key -- and because choosing which of two user records
--   survives is an operator's decision with the audit trail, not a migration's.
--   Both statements remain true and neither is contradicted below. What changes
--   is that the UPDATE here is guarded by a NOT EXISTS on the folded spelling, so
--   it touches ONLY the rows whose fold cannot collide. A colliding pair is left
--   exactly as V4 left it, for the operator, and the VALIDATE that would have
--   failed on it is attempted rather than asserted. The set V4 could not fold
--   safely and the set it could are therefore handled differently instead of
--   both being deferred, which is what lets the common case need no operator at
--   all.
--   Alternatives Considered: leaving the fold entirely to the operator, as V4
--   did, and adding only the wider predicate. Rejected because it would leave
--   every affected database in the NOT VALID state indefinitely -- the state a
--   later reader is most likely to misread as "checked" -- when the majority of
--   affected rows can be corrected here deterministically and provably without a
--   decision being made about anyone's access.
--   Alternatives Considered: a BEFORE INSERT OR UPDATE trigger folding the value
--   silently. Rejected for the two reasons V4 gives and does not need repeating:
--   a writer whose key is quietly rewritten cannot tell the row it reads back is
--   not the row it wrote, and it would make the database a second definition of
--   the fold, which is the duplication that caused the original defect.
--
-- Why VALIDATE is attempted here rather than left wholly to the operator
--   Assumptions: V4 left VALIDATE as an operator step because it "cannot be made
--   to succeed unattended" -- it fails on exactly the databases still holding an
--   unreconciled row, and a failed migration stops the service from starting.
--   That is true of a BARE ALTER TABLE ... VALIDATE CONSTRAINT and is why one
--   does not appear below. The block below instead runs it inside an exception
--   handler, which makes the two outcomes different rather than making the
--   failure disappear: on a database with nothing left to reconcile the
--   constraint is promoted to fully validated with no operator involved, and on
--   one that still holds a colliding pair the subtransaction rolls back, the
--   constraint stays NOT VALID exactly as V4 left it, and a WARNING naming the
--   detection query is written to the log. Neither outcome can stop the service
--   starting.
--   Trade-offs: a warning in a start-up log is quieter than a failed deployment
--   and can be missed. It is accepted because the alternative is an outage on the
--   databases most likely to be affected, which is the trade V4 already made and
--   this file does not reopen; the warning names the query, and the detection
--   query below remains the authoritative check.
--
-- Operator step: the rows this migration cannot correct
--   Detection -- lists every row still violating the invariant after this
--   migration, and is empty on a database that needed no operator:
--
--     SELECT user_id, first_name, last_name, user_type, cognito_sub
--       FROM auth.users
--      WHERE user_id <> upper(btrim(user_id)) OR btrim(user_id) = ''
--      ORDER BY user_id;
--
--   Every row it returns is one whose folded spelling is ALREADY TAKEN by another
--   row, or one whose key is entirely blank. Resolve it exactly as V4 describes:
--   compare the two rows' names, type and cognito_sub against the identity pool,
--   delete the row whose pool account is to be withdrawn, and withdraw that
--   account. Do NOT resolve it by folding, which the primary key refuses. The
--   provider side is in docs/runbooks/data-migration.md.
--
--   Once the detection query returns no rows, promote the constraint:
--
--     ALTER TABLE auth.users VALIDATE CONSTRAINT ck_users_user_id_folded;
--
--   Assumptions: this statement is safe to run repeatedly and is a no-op on a
--   constraint the block below already validated, so an operator following the
--   runbook need not first determine which of the two outcomes occurred.
-- =============================================================================

-- WHY : Assumptions: btrim() and not ltrim(), even though a TRAILING blank is
--   already immaterial. user_id is CHAR(8) and therefore blank-padded in storage,
--   and casting it to the type btrim() takes strips those trailing blanks anyway,
--   so the two calls behave identically on this column today. btrim() is written
--   because it is what the Java derivation does -- String#trim removes from both
--   ends -- and the value of this predicate is that it says the same thing as
--   UserService#foldedKey. A reader comparing the two should find them equivalent
--   without having to reason about CHAR padding to bridge a difference.
-- WHY : Assumptions: the collision guard is NOT EXISTS on the folded spelling
--   rather than an ON CONFLICT clause. ON CONFLICT DO NOTHING would silently skip
--   the colliding row and leave no way to distinguish "folded" from "skipped",
--   whereas the predicate leaves the colliding row untouched AND leaves it
--   visible to the detection query above, which is what the operator step needs.
-- WHY : Assumptions: an all-blank key is excluded from the fold rather than
--   folded, because there is nothing to fold it to -- btrim() yields the empty
--   string, and writing that back would leave the row equally unreachable while
--   appearing corrected. Such a row is left for the operator with everything
--   else the detection query returns.
UPDATE auth.users AS u
   SET user_id = upper(btrim(u.user_id))
 WHERE u.user_id <> upper(btrim(u.user_id))
   AND btrim(u.user_id) <> ''
   AND NOT EXISTS (
       SELECT 1
         FROM auth.users AS folded
        WHERE folded.user_id = upper(btrim(u.user_id))
   );

-- WHY : Assumptions: the old constraint is dropped and a new one added under the
--   same name, rather than the predicate being widened in place, because
--   PostgreSQL offers no ALTER CONSTRAINT for a CHECK -- the predicate of a check
--   constraint is immutable once created. Drop-then-add is the only expression of
--   this change available.
-- WHY : Assumptions: IF EXISTS is used so this migration is idempotent against a
--   database whose constraint an operator has already dropped by hand while
--   working through V4's reconciliation. The subsequent ADD is unconditional, so
--   a missing constraint still ends this migration with the invariant asserted;
--   what IF EXISTS removes is a failure that would tell the operator only that
--   their own earlier step had succeeded.
ALTER TABLE auth.users
    DROP CONSTRAINT IF EXISTS ck_users_user_id_folded;

-- WHY : Assumptions: the second term is required and is not defensive noise. On a
--   CHAR(8) column an all-blank value casts to the empty string, and
--   upper(btrim('')) is also the empty string, so the first term ALONE admits a
--   key of eight blanks -- a row no caller can address, which is the very
--   condition this constraint exists to prevent. Verified against the engine
--   rather than reasoned about.
-- WHY : Assumptions: NOT VALID is retained rather than dropped now that the
--   UPDATE above has folded what it safely can, because a colliding pair may
--   still be present and a validating ADD would fail on it -- the outage V4 was
--   written to avoid. The block below promotes the constraint whenever that scan
--   would succeed, so NOT VALID here is the starting state and not the end state.
ALTER TABLE auth.users
    ADD CONSTRAINT ck_users_user_id_folded
    CHECK (user_id = upper(btrim(user_id)) AND btrim(user_id) <> '')
    NOT VALID;

-- WHY : Assumptions: the COMMENT is replaced rather than left as V4 wrote it,
--   because V4's text already CLAIMED the stronger invariant -- "upper-case and
--   blank-trimmed" -- while its predicate asserted only the upper-case half. The
--   text was the accurate description of the intent and the predicate was the
--   incomplete one; this migration makes the predicate match the sentence, and
--   the sentence is restated here so it describes the constraint now in force
--   rather than the one it replaced.
COMMENT ON CONSTRAINT ck_users_user_id_folded ON auth.users IS
    'user_id is stored FOLDED: upper-case and blank-trimmed, and never blank -- '
    'the spelling UserService#foldedKey derives with trim() and '
    'toUpperCase(Locale.ROOT) before every probe, provider call, insert and keyed '
    'lookup. A refusal here means a writer supplied an unfolded, untrimmed or '
    'blank key; fold and trim it rather than relaxing the constraint. May be NOT '
    'VALID on a database that still holds a colliding pair from the earlier '
    'create path -- see the detection and VALIDATE steps in '
    'V5__auth_folded_user_id_trim.sql.';

-- WHY : Assumptions: the exception handler names check_violation specifically
--   rather than catching OTHERS, so only the one anticipated failure is absorbed.
--   A lock timeout, a permission refusal or a missing table is a different
--   problem with a different remedy, and absorbing those into the same warning
--   would report "reconcile your rows" for a condition no reconciliation fixes.
-- WHY : Assumptions: the handler leaves the constraint NOT VALID rather than
--   attempting any repair, because PL/pgSQL rolls the enclosing subtransaction
--   back on a caught exception -- so the failed VALIDATE is undone and the state
--   V4 established is exactly what remains. That is the desired outcome: the
--   invariant is enforced for every write from here on either way, and only the
--   guarantee about pre-existing rows is deferred.
-- WHY : Trade-offs: RAISE WARNING rather than RAISE EXCEPTION, which is the whole
--   difference between this file and the bare ALTER TABLE that V4 declined to
--   write. An EXCEPTION here would fail the migration and stop the service on
--   precisely the databases holding unreconciled data, which is the outage this
--   design exists to avoid; a WARNING records the same fact without one.
DO $$
BEGIN
    ALTER TABLE auth.users VALIDATE CONSTRAINT ck_users_user_id_folded;
    RAISE INFO
        'ck_users_user_id_folded validated: every auth.users row is folded, trimmed and non-blank.';
EXCEPTION
    WHEN check_violation THEN
        RAISE WARNING
            'ck_users_user_id_folded remains NOT VALID: auth.users still holds a row whose folded '
            'key collides with an existing row, or whose key is blank. New and updated rows ARE '
            'checked. Run the detection query in V5__auth_folded_user_id_trim.sql, resolve each row '
            'it returns, then run ALTER TABLE auth.users VALIDATE CONSTRAINT '
            'ck_users_user_id_folded.';
END;
$$;
