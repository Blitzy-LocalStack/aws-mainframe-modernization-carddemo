-- CardDemo migration | data-migration/sql/verify/row_counts.sql
--
-- Purpose: report one row per migrated table giving its current row count, so a post-load
--   verification run can be compared against the record counts the source datasets hold. This
--   is the SQL half of the pairing the migration plan asks for; the Python half is
--   carddemo_migration.verify.row_counts, which reads the source side and compares.
--
-- WHY : Alternatives Considered: a single UNION ALL over every table rather than one query per
--   table run from the harness. Chosen because a verification report is read by a human and by
--   a diff, and one result set whose rows are (schema, table, rows) is stable under a table
--   being added -- the new row appears at its sorted position -- whereas a wide single-row
--   result changes shape and breaks the diff every time the set of tables changes.
--
-- WHY : Assumptions: COUNT(*) is used rather than the planner's reltuples estimate from
--   pg_class. reltuples is an ESTIMATE maintained by vacuum and analyze, and immediately after
--   a bulk load it is routinely stale or zero. A verification that accepted an estimate could
--   report a load as complete on the strength of a number the database itself does not claim is
--   accurate, which is the opposite of the point.
--
-- WHY : Trade-offs: COUNT(*) reads every row of every table, so this script's cost grows with
--   the data. That is accepted because it runs once per migration rather than per request, and
--   because the alternative is not a cheaper truth but a different and weaker claim.
--
-- Usage: psql -v ON_ERROR_STOP=1 -f data-migration/sql/verify/row_counts.sql
--
-- Assumptions: the invoking role can SELECT from every schema listed. The verification role is
--   the reporting role, which V1__reporting_views.sql grants SELECT across the migrated
--   schemas; a service role scoped to its own schema will fail on the first table it cannot
--   read, which is the correct outcome rather than a silently short report.

\set ON_ERROR_STOP on

SELECT 'account'   AS schema_name, 'accounts'                       AS table_name, COUNT(*) AS row_count FROM account.accounts
UNION ALL
SELECT 'account',   'customers',                     COUNT(*) FROM account.customers
UNION ALL
-- WHY : the cross-reference is counted separately from the cards it points at, because the two
--   are loaded from DIFFERENT datasets -- CARDXREF.PS and CARDDATA.PS -- and a load that
--   populated one and not the other must show as a difference on exactly one line.
SELECT 'account',   'card_xref',                     COUNT(*) FROM account.card_xref
UNION ALL
SELECT 'card',      'cards',                         COUNT(*) FROM card.cards
UNION ALL
SELECT 'ledger',    'transactions',                  COUNT(*) FROM ledger.transactions
UNION ALL
SELECT 'ledger',    'daily_transactions',            COUNT(*) FROM ledger.daily_transactions
UNION ALL
SELECT 'ledger',    'transaction_category_balances', COUNT(*) FROM ledger.transaction_category_balances
UNION ALL
-- WHY : the reject stream is counted even though the seed load never writes to it. A non-zero
--   count here immediately after a fresh load means the posting job has already run, which
--   changes what every other count on this report means.
SELECT 'ledger',    'transaction_rejects',           COUNT(*) FROM ledger.transaction_rejects
UNION ALL
SELECT 'reference', 'transaction_types',             COUNT(*) FROM reference.transaction_types
UNION ALL
SELECT 'reference', 'transaction_categories',        COUNT(*) FROM reference.transaction_categories
UNION ALL
SELECT 'reference', 'disclosure_groups',             COUNT(*) FROM reference.disclosure_groups
UNION ALL
SELECT 'auth',      'users',                         COUNT(*) FROM auth.users
ORDER BY schema_name, table_name;
