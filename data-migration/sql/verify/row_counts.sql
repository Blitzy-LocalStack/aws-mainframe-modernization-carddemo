-- =============================================================================
-- data-migration/sql/verify/row_counts.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Verification pass 1 of 3 of the CardDemo data migration. Emits one row per
--   (seed dataset, target table) pair carrying the record count the source
--   dataset holds, the row count the migrated table holds now, their signed
--   difference, and a verdict token -- so a load can be judged from this output
--   alone, without the reader having to hold eleven baseline numbers in memory.
--
--   What this pass CANNOT prove. This is stated here, in the Purpose, because a
--   reader who treats a green report as proof of a correct load has been
--   actively misled, and an inaccurate statement of scope is the one defect no
--   other pass can repair:
--     - A row count shows only that rows ARRIVED, and in the right quantity. It
--       is blind to every defect that preserves the number of records.
--     - It cannot detect a sign-overpunch decode defect, nor a packed-decimal
--       (COMP-3) nibble decode defect. That is this migration's most dangerous
--       silent failure, precisely because the result looks almost right: the
--       count agrees, every field keeps its declared width, and only the SIGN of
--       a money value is wrong. tests/helpers/statement_compat.py L33-L36
--       records the same hazard from the compiler side -- the seeds encode
--       signed zoned decimal with EBCDIC sign overpunch, so a build using the
--       ASCII sign convention "would render negative amounts differently".
--     - It cannot detect a per-row value error, a field misalignment that
--       preserves the record count, a mis-assigned key, or a text field
--       truncated to its column width. Row count and record length are
--       orthogonal properties, and this pass observes only the former.
--   The two complementary passes that close those gaps:
--     - Pass 2, record checksums:
--       data-migration/src/carddemo_migration/verify/checksum.py. That pass is
--       pure Python and deliberately has no SQL counterpart in this directory.
--     - Pass 3, money-total parity: the sibling money_totals.sql, executed by
--       data-migration/src/carddemo_migration/verify/money_parity.py. It is the
--       ONLY pass that catches a systematically mis-decoded sign. A green row
--       count is therefore NECESSARY BUT NOT SUFFICIENT evidence of a good load.
--
-- Session context, which stands in place of parameters:
--   This file declares NO bound parameters and uses NO colon-prefixed variable
--   substitution, and it contains no backslash meta-command, so the identical
--   text runs under `psql -v ON_ERROR_STOP=1 -f` and through a driver cursor.
--   It assumes, and does not verify:
--     - data-migration/sql/V0__schemas_and_roles.sql has created the schemas,
--       and the five owning Flyway migrations have created all eleven tables.
--     - The session role holds USAGE on auth, account, card, ledger and
--       reference, plus SELECT on each of the eleven tables. NO least-privilege
--       runtime role holds that union, and that is by design rather than an
--       oversight: measured against a bootstrapped cluster, carddemo_reporting
--       reads NONE of the eleven, because it is granted SELECT on reporting
--       views rather than on base tables; carddemo_reporting_owner and
--       carddemo_batch each read ten and are stopped by auth.users, the auth
--       schema being held outside both of their grant graphs. Run this as the
--       operator principal that applied V0, or as a verification role granted
--       SELECT on exactly these eleven tables. Choosing carddemo_reporting
--       because its name suggests reporting produces the 42501 below on the
--       first branch evaluated.
--     - Nothing is written: no row, no object, no session setting. A principal
--       holding SELECT and nothing else is sufficient to run this file.
--
-- Returns exactly one result set of exactly eleven rows, one per (dataset,
-- target table) pair:
--     dataset        text    seed dataset label; '(none)' where none exists
--     target_table   text    schema-qualified name of the migrated table
--     expected_rows  bigint  baseline record count; NULL where no seed dataset
--     actual_rows    bigint  rows present in the target table now
--     delta          bigint  actual_rows - expected_rows; NULL when the
--                            baseline is NULL, because no difference is defined
--     status         text    exactly one of 'MATCH', 'MISMATCH', 'NO_BASELINE'
--   Row order is fixed by an integer ordinal that is not projected, so two runs
--   against the same data produce byte-identical output and the paired harness
--   at data-migration/src/carddemo_migration/verify/row_counts.py can line-diff
--   the result rather than parse it.
--
-- Fails when:
--   - SQLSTATE 42P01 undefined_table -- a table is missing because its owning
--     migration has not been applied. The failure is correct and preferable to a
--     silently short report that omits the table it could not read.
--   - SQLSTATE 42501 insufficient_privilege -- the session role lacks USAGE on a
--     schema or SELECT on a table, as described under session context.
--
-- Misleads when: it is read as evidence of anything beyond arrival and
--   quantity. Every case is enumerated under "What this pass CANNOT prove".
--
-- WHY (non-obvious design decisions):
--       (1) Alternatives Considered: every branch counts with COUNT(*) and
--       never COUNT(<column>). COUNT over a column skips NULLs, and
--       ledger.daily_transactions.proc_ts is nullable BY DESIGN -- the
--       pre-posting feed leaves those 26 bytes blank on 300 of 300 records, so
--       COUNT(proc_ts) returns 0 where COUNT(*) returns 300. A column-qualified
--       count would report a perfectly loaded table as entirely empty.
--       (2) Assumptions: no timestamp column appears in any predicate, grouping,
--       ordering or filter here. proc_ts is a runtime wall-clock stamp, and
--       orig_ts is deterministic only for posted rows, so any timestamp
--       predicate would make this report irreproducible between runs.
--       (3) Assumptions: ledger.transactions carries a NULL baseline rather than
--       0, because it has no seed dataset at all. Detail at the row itself.
--       (4) Assumptions: reference.disclosure_groups expects 51 where every
--       other master expects 50. Detail at the row itself.
--       (5) Trade-offs: four groups of tables that exist are deliberately not
--       counted. Detail at the exclusion note below the branches.
--       (6) Alternatives Considered: one statement, terminated by one semicolon.
--       Detail at the statement itself.
--       (7) Assumptions: the three reference tables are seeded by Flyway rather
--       than by the ETL loaders, and idempotently. Detail at those rows.
--       (8) Assumptions: every table is named schema-qualified rather than
--       resolved through search_path. Detail at the counting branches.
--       (9) Trade-offs: status and delta are derived here in SQL rather than
--       left to the Python consumer. Detail at the CASE expression.
--       (10) Refactoring Rationale: this file replaces an earlier draft that
--       reported bare counts under a (schema_name, table_name, row_count)
--       shape, preceded by a psql ON_ERROR_STOP meta-command. Three things were
--       wrong with it. It carried no baselines, so its output could not be
--       judged without knowing eleven numbers from elsewhere. The meta-command
--       made the file unusable through a driver cursor for no gain: the runbook
--       already passes -v ON_ERROR_STOP=1 on the command line, and with a
--       single statement there is no second statement for the switch to skip.
--       And it counted ledger.transaction_rejects, a table no seed dataset can
--       ever justify a baseline for. Pure SQL is the settled convention for this
--       directory, recorded at V0__schemas_and_roles.sql L130-L135.
-- =============================================================================

-- WHY : Alternatives Considered: ONE statement, one terminating semicolon, and
--       a fixed integer ordering. Two alternatives were rejected for concrete
--       mechanical reasons. A file of eleven separate statements would lose ten
--       of the eleven results through a driver cursor, whose execute() exposes
--       only the LAST result set, so the harness would validate one table while
--       reporting that it had validated the load. Ordering by dataset text
--       instead of by ordinal would make row order depend on the database
--       collation, so '(none)' could sort before or after 'acctdata' between a
--       C-collation and a UTF-8-collation cluster and break the byte-identical
--       line-diff the ordering exists to guarantee. sort_key is used in ORDER BY
--       without being projected, which keeps the contract at six columns.
WITH expected (sort_key, dataset, target_table, expected_rows) AS (
    VALUES
        -- WHY : Assumptions: each baseline is a literal, cross-checked two
        --       independent ways against the immutable seed data -- the line
        --       count of app/data/ASCII/<dataset>.txt, and the byte size of the
        --       matching app/data/EBCDIC dataset divided by the record length
        --       its copybook declares. Both derivations agree on every row, and
        --       every division is exact with no remainder, which is what makes
        --       the record lengths themselves corroborated rather than assumed.
        --       Literals are used rather than a count taken from the source at
        --       run time so that this file needs no access to the seed files and
        --       a drift in either the data or the loader is visible as a delta.
        (1::smallint, 'acctdata'::text, 'account.accounts'::text,                     50::bigint),
        (2,           'carddata',       'card.cards',                                 50),
        (3,           'cardxref',       'account.card_xref',                          50),
        (4,           'custdata',       'account.customers',                          50),
        (5,           'dailytran',      'ledger.daily_transactions',                 300),

        -- WHY : Assumptions: 51, where every other master is 50, and the odd
        --       number is the correct one rather than a miscount. Both
        --       derivations agree: app/data/ASCII/discgrp.txt holds 51 lines and
        --       AWS.M2.CARDDEMO.DISCGRP.PS holds 2550 bytes of a 50-byte record.
        --       The 51 rows span three group keys -- 34 across 'A000000000' and
        --       'ZEROAPR', plus the 17 'DEFAULT' rows the interest calculation
        --       falls back to when an account's own group key is not found, which
        --       V2__seed_reference.sql L382 records as load-bearing. Carrying 50
        --       here would report a correct load as holding one row too many and
        --       send an operator hunting a duplicate that does not exist.
        (6,           'discgrp',        'reference.disclosure_groups',                51),
        (7,           'tcatbal',        'ledger.transaction_category_balances',       50),

        -- WHY : Assumptions: these three reference tables are populated by
        --       Flyway, in V2__seed_reference.sql, and NOT by the ETL loaders,
        --       so the counts hold whether the seed migration ran, the ETL ran,
        --       or both did. Each of that file's inserts carries a key-targeted
        --       ON CONFLICT ... DO NOTHING, so a second application adds
        --       nothing. The baselines are unchanged by this -- they still come
        --       from app/data/ASCII -- but attributing them to the ETL would
        --       send anyone diagnosing a mismatch to the wrong component.
        (8,           'trancatg',       'reference.transaction_categories',           18),
        (9,           'trantype',       'reference.transaction_types',                 7),

        -- WHY : Assumptions: usrsec is derived from the EBCDIC dataset alone,
        --       because it is the one master with no app/data/ASCII counterpart.
        --       Three sources agree on 10: 800 bytes over the 80-byte record
        --       app/cpy/CSUSR01Y.cpy declares, and the in-stream IEBGENER data
        --       of app/jcl/DUSRSECJ.jcl L35-L44, which lists exactly ten users,
        --       five of type A and five of type U.
        (10,          'usrsec',         'auth.users',                                 10),

        -- WHY : Assumptions: a NULL baseline, not 0, and the row is reported
        --       rather than omitted. No seed dataset for this table exists
        --       anywhere: there is no app/data/ASCII/transact.txt and no
        --       TRANSACT dataset under app/data/EBCDIC. app/jcl/TRANFILE.jcl
        --       defines the cluster at L49-L54 and primes it at L67-L74 by
        --       copying AWS.M2.CARDDEMO.DALYTRAN.PS.INIT, which is 350 bytes --
        --       exactly ONE initializer record -- so even the baseline job loads
        --       no master data here. The table is filled by the posting job from
        --       ledger.daily_transactions, so 0 rows immediately after the ETL
        --       is correct and only becomes meaningful once posting has run.
        --       NULL propagates into delta and selects the NO_BASELINE token, so
        --       a correct fresh load cannot read as a failure; omitting the row
        --       instead would read as an oversight in the query.
        (11,          '(none)',         'ledger.transactions',                        NULL::bigint)
),

-- WHY : Assumptions: every table is named schema-qualified, rather than left to
--       be resolved through search_path. This file is run both by psql and
--       through a driver cursor whose session search_path is set by the calling
--       role, so an unqualified name could resolve to a different table between
--       the two and the report would state a count for a table it did not read.
--       The qualified name costs one prefix per branch and removes that class of
--       error entirely.
actual (target_table, actual_rows) AS (
    SELECT 'account.accounts',                     COUNT(*) FROM account.accounts
    UNION ALL
    SELECT 'card.cards',                           COUNT(*) FROM card.cards
    UNION ALL
    SELECT 'account.card_xref',                    COUNT(*) FROM account.card_xref
    UNION ALL
    SELECT 'account.customers',                    COUNT(*) FROM account.customers
    UNION ALL
    SELECT 'ledger.daily_transactions',            COUNT(*) FROM ledger.daily_transactions
    UNION ALL
    SELECT 'reference.disclosure_groups',          COUNT(*) FROM reference.disclosure_groups
    UNION ALL
    SELECT 'ledger.transaction_category_balances', COUNT(*) FROM ledger.transaction_category_balances
    UNION ALL
    SELECT 'reference.transaction_categories',     COUNT(*) FROM reference.transaction_categories
    UNION ALL
    SELECT 'reference.transaction_types',          COUNT(*) FROM reference.transaction_types
    UNION ALL
    SELECT 'auth.users',                           COUNT(*) FROM auth.users
    UNION ALL
    SELECT 'ledger.transactions',                  COUNT(*) FROM ledger.transactions
)

-- WHY : Trade-offs: four groups of tables that exist in these schemas are
--       deliberately not counted, because this pass is per SEED DATASET and
--       none of them has one. reference.us_phone_area_codes,
--       reference.us_states and reference.us_state_zip_prefixes are seeded by
--       Flyway from the 88-level condition-name lists in app/cpy/CSLKPCDY.cpy,
--       which is compiled-in source rather than a dataset.
--       ledger.transaction_rejects is written by the posting job at run time.
--       batch.* and "authorization".* are likewise runtime-populated. Counting
--       any of them would emit a row whose expected_rows could only ever be
--       NULL, diluting a report whose whole value is that every line can be
--       judged against a baseline. Their absence here is a decision, not a gap.
--       The cost accepted is that this file is not an inventory of the schema;
--       a caller wanting that should query the catalog instead.
SELECT e.dataset,
       e.target_table,
       e.expected_rows,
       a.actual_rows,

       -- WHY : Assumptions: the difference stays in bigint end to end. COUNT(*)
       --       yields bigint and the baselines are cast to bigint, so no value
       --       on this line is ever converted to a binary floating-point type,
       --       in which a large count could not be represented exactly.
       a.actual_rows - e.expected_rows AS delta,

       -- WHY : Trade-offs: the verdict is derived here rather than left to the
       --       paired Python harness, which duplicates a small amount of
       --       comparison logic in two places. Accepted because it makes a bare
       --       `psql -f` run self-interpreting: an operator reading the raw
       --       output sees MATCH, MISMATCH or NO_BASELINE without having to
       --       compare two numeric columns by eye across eleven rows. The three
       --       tokens are distinct, so NO_BASELINE can never be mistaken for
       --       agreement -- which is the specific misreading that a table with
       --       no seed dataset invites.
       CASE
           WHEN e.expected_rows IS NULL             THEN 'NO_BASELINE'
           WHEN a.actual_rows = e.expected_rows     THEN 'MATCH'
           ELSE                                          'MISMATCH'
       END AS status
FROM   expected e
       JOIN actual a ON a.target_table = e.target_table
ORDER  BY e.sort_key;
