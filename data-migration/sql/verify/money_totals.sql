-- CardDemo migration | data-migration/sql/verify/money_totals.sql
--
-- Purpose: report the exact total of every money column in the migrated schemas, so a
--   post-load run can compare each against the total computed from the source dataset bytes.
--   This is the SQL half of carddemo_migration.verify.money_parity.
--
-- WHY : Assumptions: this is the check that catches the failure mode this migration is most
--   exposed to. Every money field in the baseline masters is zoned decimal with a SIGN
--   OVERPUNCH in its low-order byte, and reading that byte with the wrong sign convention
--   yields a plausible positive number where the value was negative. The record count still
--   agrees, every field still has the right width, and only a total taken from the source bytes
--   and compared against the database's own SUM will disagree. The test suite documents the same
--   hazard for the COBOL side, where compiling without EBCDIC sign handling "silently corrupts
--   negative balances".
--
-- WHY : Assumptions: SUM is wrapped in COALESCE because SUM over zero rows is NULL rather than
--   zero, and a null total compared against an exact zero reports a difference on an empty
--   table that has nothing wrong with it.
--
-- WHY : Trade-offs: totals are reported per column rather than per account. A per-account
--   report would localise a discrepancy to a row, which is more useful diagnostically, and it
--   would also make this file a per-account disclosure of balances -- readable by every holder
--   of the verification output. An aggregate over a whole table is not attributable to an
--   individual, which is the property that makes it publishable; localisation is done
--   afterwards, against the database, by someone authorised to see a balance.
--
-- Usage: psql -v ON_ERROR_STOP=1 -f data-migration/sql/verify/money_totals.sql

\set ON_ERROR_STOP on

SELECT 'account.accounts'                     AS qualified_table,
       'curr_bal'                             AS column_name,
       COALESCE(SUM(curr_bal), 0)             AS total
FROM   account.accounts
UNION ALL
SELECT 'account.accounts', 'credit_limit',      COALESCE(SUM(credit_limit), 0)      FROM account.accounts
UNION ALL
SELECT 'account.accounts', 'cash_credit_limit', COALESCE(SUM(cash_credit_limit), 0) FROM account.accounts
UNION ALL
-- WHY : both cycle accumulators are totalled, and the debit one is expected to be NEGATIVE or
--   zero. The posting job ADDS a negative amount to the debit accumulator rather than
--   subtracting it, so a positive debit total is itself evidence of a sign defect even before
--   the source comparison is made.
SELECT 'account.accounts', 'curr_cyc_credit',   COALESCE(SUM(curr_cyc_credit), 0)   FROM account.accounts
UNION ALL
SELECT 'account.accounts', 'curr_cyc_debit',    COALESCE(SUM(curr_cyc_debit), 0)    FROM account.accounts
UNION ALL
SELECT 'ledger.transactions', 'amount',         COALESCE(SUM(amount), 0)            FROM ledger.transactions
UNION ALL
SELECT 'ledger.daily_transactions', 'amount',   COALESCE(SUM(amount), 0)            FROM ledger.daily_transactions
UNION ALL
SELECT 'ledger.transaction_category_balances', 'balance',
       COALESCE(SUM(balance), 0)                                                    FROM ledger.transaction_category_balances
UNION ALL
-- WHY : the interest rate is included even though it is a rate rather than money. It is
--   NUMERIC(6,2) read from the same zoned-decimal regime as the money fields, so it fails the
--   same way, and it is the multiplier in the accrual formula -- a rate read with the wrong
--   sign would accrue interest in the wrong direction on every account in its group.
SELECT 'reference.disclosure_groups', 'interest_rate',
       COALESCE(SUM(interest_rate), 0)                                              FROM reference.disclosure_groups
ORDER BY qualified_table, column_name;
