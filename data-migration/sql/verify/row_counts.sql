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
--     - data-migration/sql/V0__schemas_and_roles.sql has created the schemas, the
--       five owning Flyway migrations have created all eleven tables, and
--       data-migration/sql/V3__verification_surfaces.sql has created the two
--       aggregate views this file reads.
--     - The session role holds USAGE on the `reporting` schema and SELECT on
--       reporting.v_verification_row_counts, and NOTHING ELSE is required. That
--       is satisfied by carddemo_reporting, the least-privilege read-only role,
--       which is the role this file is meant to be run as:
--
--         psql "$CARDDEMO_DB_URL" -v ON_ERROR_STOP=1 \
--              -f data-migration/sql/verify/row_counts.sql
--
--       Refactoring Rationale: this note used to say the opposite -- that NO
--       least-privilege runtime role could run this file, that carddemo_reporting
--       read none of the eleven tables, and that an operator should therefore run
--       it "as the operator principal that applied V0". Every one of those
--       statements was true of the cluster as it then stood, and the arrangement
--       they described was the defect: a verification pass that can only be run
--       by a principal holding row-level read access to every account balance,
--       card number and national identifier in the system is a pass whose
--       execution is itself a disclosure. V3 fixes the cause rather than the
--       documentation, by publishing the COUNTS as an owner-backed aggregate view
--       and granting SELECT on that view alone. What this file needs is "how many
--       rows"; what it now requires is exactly that and nothing more.
--     - Nothing is written: no row, no object, no session setting. A principal
--       holding SELECT on one view and nothing else is sufficient to run this
--       file -- and, being unable to write anything anywhere, is incapable of
--       altering the data it is verifying.
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
--   - SQLSTATE 42P01 undefined_table -- the aggregate view is missing because
--     V3__verification_surfaces.sql has not been applied, or a base table beneath
--     it is missing because its owning migration has not been applied. Either
--     failure is correct and preferable to a silently short report that omits the
--     table it could not read.
--   - SQLSTATE 42501 insufficient_privilege -- the session role lacks USAGE on the
--     `reporting` schema or SELECT on reporting.v_verification_row_counts, as
--     described under session context. A privilege error can no longer be caused
--     by a base table, because this file names none.
--
-- Misleads when: it is read as evidence of anything beyond arrival and
--   quantity. Every case is enumerated under "What this pass CANNOT prove".
--
-- WHY (non-obvious design decisions):
--       (1) Assumptions: the counts are read from an aggregate VIEW and are no
--       longer computed here. Detail at the `actual` branch below and, for the
--       privilege reasoning, in V3__verification_surfaces.sql. The COUNT(*)
--       rather than COUNT(<column>) decision that used to live here now lives
--       with the counting, in that file: a column-qualified count would skip
--       NULLs, and ledger.daily_transactions.proc_ts is nullable BY DESIGN -- the
--       pre-posting feed leaves those 26 bytes blank on 300 of 300 records, so
--       COUNT(proc_ts) returns 0 where COUNT(*) returns 300.
--       (2) Assumptions: no timestamp column appears in any predicate, grouping,
--       ordering or filter here or in the view. proc_ts is a runtime wall-clock
--       stamp, and orig_ts is deterministic only for posted rows, so any
--       timestamp predicate would make this report irreproducible between runs.
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
--       (8) Assumptions: the aggregate view is named schema-qualified rather than
--       resolved through search_path. Detail at the `actual` branch.
--       (9) Trade-offs: status and delta are derived here in SQL rather than
--       left to the Python consumer. Detail at the CASE expression.
--       (10) Assumptions: the file is pure SQL, carrying no psql meta-command,
--       because it is run both by psql and through a driver cursor and a
--       meta-command is unusable through the latter. The runbook passes
--       -v ON_ERROR_STOP=1 on the command line instead, and with a single
--       statement there is no second statement for that switch to skip. Pure
--       SQL is the settled convention for this directory, recorded at
--       V0__schemas_and_roles.sql L130-L135.
--       (11) Alternatives Considered: counts are exact rather than estimated,
--       and the full scan that costs is accepted. Detail at the counting
--       branches.
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

        -- WHY : Refactoring Rationale: this note used to say these three
        --       reference tables are populated by Flyway "and NOT by the ETL
        --       loaders", and concluded that the counts therefore "hold whether
        --       the seed migration ran, the ETL ran, or both did". The first
        --       clause was false and the second did not follow from it.
        --       loaders/aurora.py has always declared TRANTYPE, TRANCAT and
        --       DISGROUP as load targets, so BOTH components write all three --
        --       and the loader wrote them through a plain COPY, which aborts on
        --       the first primary-key collision. Running the seed migration and
        --       then the ETL, which is the documented order, therefore failed
        --       the load outright rather than composing with it. Two writers
        --       also disagreed on content: V2__seed_reference.sql writes
        --       'Purchase', while the copybook field is PIC X(50) and the loader
        --       carried its blank padding into a VARCHAR(50) column, so the row
        --       a screen rendered depended on which writer ran first while these
        --       counts agreed either way.
        -- WHY : Assumptions: the counts DO hold in all three orders now, and the
        --       two mechanisms that make them hold are stated here because
        --       neither is visible from this file. The loader declares a conflict
        --       key on exactly these three targets and loads them by staging into
        --       a session-temporary table and merging with
        --       ON CONFLICT ... DO NOTHING -- the same conflict target
        --       V2__seed_reference.sql uses, asserted against that file's own
        --       clause by data-migration/tests/test_aurora_loader.py -- so a row
        --       already present is skipped rather than colliding. And the loader
        --       TRIMS the description fields, so the row either writer produces
        --       is byte-identical to the other's. The baselines below are
        --       unchanged by any of this: they still come from app/data/ASCII.
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
-- WHY : Alternatives Considered: every branch below counts rows with COUNT(*)
--       rather than reading the planner's reltuples estimate out of pg_class.
--       reltuples is an ESTIMATE maintained by vacuum and analyze, and
--       immediately after a bulk load it is routinely stale or zero. A
--       verification that accepted it could report a load as complete on the
--       strength of a number the database itself does not claim is accurate,
--       which is the opposite of what this pass is for.
-- WHY : Trade-offs: COUNT(*) reads every row of every table below, so the cost
--       of this statement grows with the data. That is accepted because it runs
--       once per migration rather than per request, and because the cheaper
--       alternative is not a cheaper truth but a weaker claim.
actual (target_table, actual_rows) AS (
    SELECT v.target_table, v.actual_rows
    FROM   reporting.v_verification_row_counts v
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
