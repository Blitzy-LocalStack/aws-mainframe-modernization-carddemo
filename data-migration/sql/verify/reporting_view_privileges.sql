-- =============================================================================
-- data-migration/sql/verify/reporting_view_privileges.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Proves, against a provisioned database, the four properties the reporting
--   context's data access rests on. Each query returns rows ONLY when the property
--   is broken, so a run whose every result set is empty is a pass and no result
--   needs interpreting.
--
-- Parameters:
--   None. Every identifier is a literal, because the properties being checked are
--   about specific named objects and roles, and a parameterised check could be run
--   against the wrong ones and still pass.
--
-- Provides:
--   Four result sets, in the order below. Any non-empty result set is a
--   provisioning defect that must be resolved before the reporting service is
--   given a credential:
--     1. views missing, unowned by the barrier owner, or not security barriers
--     2. views the service role cannot read
--     3. source relations the service role CAN read, which it must not
--     4. write privileges held on a reporting view, which must be none
--
-- Failure modes:
--   - Run before data-migration/sql/V1__reporting_views.sql and check 1 reports
--     every missing view by name.
--   - Run against a database whose roles were never created and every check errors
--     on an unknown role, which is the correct outcome: the privilege model is the
--     subject of the check, so its absence is a failure and not a skip.
--
-- WHY (non-obvious design decisions):
--   - Alternatives Considered: writing each check as an assertion that returns a
--     pass or fail token. Rejected because a check that returns a row on success is
--     indistinguishable from a check that silently matched nothing -- a misspelled
--     view name would report a pass. Returning rows only on FAILURE makes an empty
--     output the only passing state, and a misspelled name then surfaces in check 1.
--   - Assumptions: privileges are read with has_table_privilege and from
--     pg_class.reloptions rather than by attempting the operations. Attempting them
--     would require the checks to run AS the service role, and the role is created
--     without a usable password on purpose, so the check would be unrunnable exactly
--     where it matters.
-- =============================================================================

\echo '== check 1: each view exists, is owned by the barrier owner, and is a barrier'
WITH expected(view_name) AS (
    VALUES ('v_report_transactions'),
           ('statement_transactions'),
           ('transaction_types'),
           ('transaction_categories')
)
SELECT e.view_name,
       c.oid IS NULL                                             AS missing,
       c.relowner::regrole::text                                 AS owner,
       coalesce(array_to_string(c.reloptions, ','), '(none)')     AS options
FROM expected e
LEFT JOIN pg_class c
       ON c.relname = e.view_name
      AND c.relnamespace = 'reporting'::regnamespace
      AND c.relkind = 'v'
WHERE c.oid IS NULL
   OR c.relowner <> 'carddemo_reporting_owner'::regrole
   -- WHY : Assumptions: the option is matched as a literal element rather than by a
   --       pattern, because 'security_barrier=false' contains 'security_barrier' and a
   --       pattern match would accept a view that explicitly turned the barrier off.
   OR NOT ('security_barrier=true' = ANY (coalesce(c.reloptions, ARRAY[]::text[])));

\echo '== check 2: the service role can read every view'
WITH expected(view_name) AS (
    VALUES ('reporting.v_report_transactions'),
           ('reporting.statement_transactions'),
           ('reporting.transaction_types'),
           ('reporting.transaction_categories')
)
SELECT view_name
FROM expected
WHERE NOT has_table_privilege('carddemo_reporting', view_name, 'SELECT');

\echo '== check 3: the service role cannot read any source relation'
WITH forbidden(relation_name) AS (
    VALUES ('ledger.transactions'),
           ('ledger.daily_transactions'),
           ('ledger.transaction_category_balances'),
           ('reference.transaction_types'),
           ('reference.transaction_categories'),
           ('reference.disclosure_groups')
)
SELECT relation_name
FROM forbidden
-- WHY : Assumptions: the relation is checked for existence first, so a relation not
--       yet created is not reported as a privilege failure. A missing table cannot
--       be read by anyone, and reporting it here would make check 3 fail for a
--       reason that has nothing to do with privileges.
WHERE to_regclass(relation_name) IS NOT NULL
  AND has_table_privilege('carddemo_reporting', relation_name, 'SELECT');

\echo '== check 4: the service role holds no write privilege on any view'
WITH expected(view_name) AS (
    VALUES ('reporting.v_report_transactions'),
           ('reporting.statement_transactions'),
           ('reporting.transaction_types'),
           ('reporting.transaction_categories')
), writes(privilege) AS (
    VALUES ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE')
)
SELECT e.view_name, w.privilege
FROM expected e
CROSS JOIN writes w
WHERE has_table_privilege('carddemo_reporting', e.view_name, w.privilege);
