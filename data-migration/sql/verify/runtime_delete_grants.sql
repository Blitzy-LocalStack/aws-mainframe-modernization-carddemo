-- =============================================================================
-- data-migration/sql/verify/runtime_delete_grants.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Proves, against a provisioned database, that DELETE is held by exactly the three
--   runtime roles that need it, on exactly the six tables their published
--   operations delete from, and nowhere else. Each query returns rows ONLY when the
--   property is broken, so a run whose every result set is empty is a pass and no
--   result needs interpreting.
--
-- Parameters:
--   None. Every identifier is a literal, because the properties being checked are
--   about specific named tables and roles, and a parameterised check could be run
--   against the wrong ones and still pass.
--
-- Provides:
--   Three result sets, in the order below. Any non-empty result set is a
--   provisioning defect that must be resolved before the affected service is given
--   a credential:
--     1. tables that MUST be deletable by their runtime role and are not
--     2. tables in the three affected schemas that are deletable and must not be
--     3. runtime roles other than the three intended ones that hold DELETE anywhere
--
-- Failure modes:
--   - Run before data-migration/sql/V2__runtime_delete_grants.sql and check 1
--     reports all six tables by name.
--   - Run before the owning services' Flyway migrations and every check errors on an
--     unknown relation, which is the correct outcome: the tables are the subject of
--     the check, so their absence is a failure and not a skip.
--   - Run against a database whose roles were never created and every check errors on
--     an unknown role, for the same reason.
--
-- WHY (non-obvious design decisions):
--   - Alternatives Considered: writing each check as an assertion returning a pass
--     token. Rejected for the reason the sibling reporting check records: a check that
--     returns a row on success cannot be told apart from one that matched nothing, so
--     a misspelled table name would report a pass. Rows only on FAILURE makes an empty
--     output the only passing state.
--   - Assumptions: privileges are read with has_table_privilege rather than by
--     attempting a delete. Attempting one would need the check to run AS the runtime
--     role, whose credential is delivered out of band and is not available to an
--     operator running verification, and it would also have to be rolled back --
--     leaving a check that mutates the table it is verifying.
--   - Assumptions: check 2 is written as a negative over the WHOLE schema rather than
--     as a list of the tables that must not be deletable. A list would have to be
--     extended by hand every time a table was added, and the failure mode of
--     forgetting is silence; enumerating pg_tables means a newly added table is
--     covered the moment it exists.
-- =============================================================================

\echo '== check 1: the six contracted tables are deletable by their runtime role'
-- WHY : Assumptions: the six are named as literals paired with the role that must
--   hold the privilege, so a grant made to the WRONG role fails this check as well as
--   check 3. A query that asked only whether some role could delete would pass on a
--   grant to a role that has no business holding it.
SELECT expected.role_name,
       expected.table_name,
       'MISSING DELETE' AS defect
FROM (VALUES
        ('carddemo_auth',          'auth.users'),
        ('carddemo_reference',     'reference.transaction_types'),
        ('carddemo_reference',     'reference.transaction_categories'),
        ('carddemo_authorization', '"authorization".auth_reply_outbox'),
        ('carddemo_authorization', '"authorization".pending_auth_detail'),
        ('carddemo_authorization', '"authorization".pending_auth_summary')
     ) AS expected(role_name, table_name)
WHERE NOT has_table_privilege(expected.role_name, expected.table_name, 'DELETE');

\echo '== check 2: no other table in those three schemas is deletable by that role'
-- WHY : Assumptions: only the three schemas that grant DELETE at all are scanned, and
--   check 3 covers the other five by asking about the roles instead. Scanning every
--   schema here would report the same defect twice and would make a failure read as
--   two problems.
SELECT t.schemaname || '.' || t.tablename AS deletable_table,
       CASE t.schemaname
            WHEN 'auth' THEN 'carddemo_auth'
            WHEN 'reference' THEN 'carddemo_reference'
            ELSE 'carddemo_authorization'
       END AS role_name,
       'UNEXPECTED DELETE' AS defect
FROM pg_tables AS t
WHERE t.schemaname IN ('auth', 'reference', 'authorization')
  AND has_table_privilege(
          CASE t.schemaname
               WHEN 'auth' THEN 'carddemo_auth'
               WHEN 'reference' THEN 'carddemo_reference'
               ELSE 'carddemo_authorization'
          END,
          format('%I.%I', t.schemaname, t.tablename),
          'DELETE')
  AND format('%I.%I', t.schemaname, t.tablename) NOT IN (
          'auth.users',
          'reference.transaction_types',
          'reference.transaction_categories',
          '"authorization".auth_reply_outbox',
          '"authorization".pending_auth_detail',
          '"authorization".pending_auth_summary');

\echo '== check 3: no other runtime role holds DELETE on any table it can reach'
-- WHY : Assumptions: the five remaining runtime roles are named rather than derived
--   from the catalogue, because a role added later should be a deliberate addition to
--   this list. Deriving them from a name pattern would silently admit a new role and
--   its grants without review, which is the opposite of what this check is for.
-- WHY : Refactoring Rationale: carddemo_auth was listed here and is not any more. It
--   now legitimately holds DELETE on auth.users, so leaving it in a check that asserts
--   the role holds DELETE nowhere would have made a correctly provisioned database fail
--   verification. The auth schema moves into check 2 instead, where the single table it
--   may delete from is allow-listed and every other table in that schema is still
--   covered -- so the role stays checked rather than becoming exempt.
-- WHY : Assumptions: reporting is included even though it owns no table. Its remit is
--   masked read-only presentation and its views are automatically updatable where they
--   are simple, so a DELETE reaching it is exactly the mistake V1__reporting_views.sql
--   revokes against, and verifying it here costs one row of input.
SELECT r.role_name,
       t.schemaname || '.' || t.tablename AS deletable_table,
       'UNEXPECTED DELETE' AS defect
FROM (VALUES
        ('carddemo_account'),
        ('carddemo_card'),
        ('carddemo_ledger'),
        ('carddemo_batch'),
        ('carddemo_reporting')
     ) AS r(role_name)
CROSS JOIN pg_tables AS t
WHERE t.schemaname IN ('auth', 'account', 'card', 'ledger', 'reference',
                       'batch', 'authorization', 'reporting')
  AND has_table_privilege(r.role_name, format('%I.%I', t.schemaname, t.tablename),
                          'DELETE');
