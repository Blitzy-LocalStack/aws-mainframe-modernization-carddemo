-- =============================================================================
-- data-migration/sql/verify/money_totals.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Verification pass 3 of 3 of the CardDemo data migration. Emits one row per
--   migrated money column carrying the row count of its table, the exact total
--   of the column, and the number of rows in which it is negative -- so that a
--   total taken from the source dataset bytes can be compared against the total
--   the database actually holds, column by column.
--
--   What this pass PROVES that neither other pass can. It is the ONLY pass that
--   catches a systematically mis-decoded sign. Every money field in the baseline
--   masters is signed zoned decimal whose sign lives as an OVERPUNCH in the
--   field's low-order byte: tests/helpers/record_codec.py L137-L138 declares the
--   two mappings, '{ABCDEFGHI' for +0 through +9 and '}JKLMNOPQR' for -0 through
--   -9, applied to that last byte with the decimal point implied. A decoder that
--   reads 'J' through 'R' as the plain digits 1 through 9, or that ignores the
--   overpunch, turns every negative amount positive. Nothing else notices: the
--   record keeps its declared length, every field keeps its boundaries, and the
--   row count is unchanged, so the result looks almost right. Only a total
--   disagrees. app/data/ASCII/discgrp.txt corroborates the encoding in one
--   readable byte -- its first record's rate field holds '00150{', where the '{'
--   is the +0 overpunch and the value is +0015.00. The file
--   tests/helpers/statement_compat.py at L33-L36 records the identical hazard
--   from the compiler side: the goldens were produced under the EBCDIC sign
--   convention, so an ASCII-sign build "would render negative amounts
--   differently and mismatch the golden", which is why -fsign=EBCDIC is
--   mandatory there.
--
--   What this pass CANNOT prove. Stated here, in the Purpose, because a sum is
--   the single most over-trusted number in a migration report and an inaccurate
--   claim of scope is the one defect no later pass can repair:
--     - It cannot catch a COMPENSATING PAIR OF ERRORS. Two mis-decodes of
--       opposite direction and equal magnitude leave the total untouched. The
--       negative_rows column below is a partial mitigation, not a refutation.
--     - It cannot catch a PER-ROW MIS-ASSIGNMENT. The right amounts attached to
--       the wrong keys -- an account's balance landing on its neighbour -- sum
--       identically, because addition does not record which row it read.
--     - It cannot catch a DEFECT IN A NON-MONEY COLUMN. A name truncated to its
--       column width, a wrong date, a mis-keyed identifier: every total is
--       unchanged. This pass observes nine columns and is blind to the rest.
--   The two complementary passes that close those gaps:
--     - Pass 1, row counts: the sibling row_counts.sql, paired with
--       data-migration/src/carddemo_migration/verify/row_counts.py. It shows
--       only that rows ARRIVED and in the right quantity, and it cannot see a
--       sign defect at all -- a flipped sign changes no count.
--     - Pass 2, record checksums:
--       data-migration/src/carddemo_migration/verify/checksum.py. That pass is
--       pure Python and deliberately has no SQL counterpart in this directory.
--   All three together are the gate. No one of them is evidence on its own.
--
-- Session context, which stands in place of parameters:
--   This file declares NO bound parameters and uses NO colon-prefixed variable
--   substitution, and it contains no backslash meta-command, so the identical
--   text runs unchanged under `psql -v ON_ERROR_STOP=1 -f` and through a driver
--   cursor. Its paired harness is
--   data-migration/src/carddemo_migration/verify/money_parity.py, which is the
--   pass that compares a source total against a target total; that module issues
--   its own per-column aggregate rather than loading this text, so this file is
--   the operator-facing form of the same pass and has to stand on its own. That
--   is why the single-statement contract below is a contract and not a
--   preference: it keeps this text safe to hand to a cursor unedited.
--   It assumes, and does not verify:
--     - data-migration/sql/V0__schemas_and_roles.sql has created the schemas,
--       three owning migrations have created the five tables the aggregate view
--       reads -- V1__account.sql for account.accounts, V1__ledger.sql for the
--       three ledger tables, V1__reference.sql for reference.disclosure_groups --
--       and data-migration/sql/V3__verification_surfaces.sql has created that
--       view.
--     - The session role holds USAGE on the `reporting` schema and SELECT on
--       reporting.v_verification_money_totals, and NOTHING ELSE is required.
--       That is satisfied by carddemo_reporting, the least-privilege read-only
--       role, which is the role this file is meant to be run as:
--
--         psql "$CARDDEMO_DB_URL" -v ON_ERROR_STOP=1 \
--              -f data-migration/sql/verify/money_totals.sql
--
--       Alternatives Considered: running the pass as carddemo_batch, which holds
--       direct SELECT on the five underlying tables. Rejected, and deliberately not
--       offered above as a fallback: carddemo_batch is WRITE-CAPABLE -- V0 grants it
--       SELECT, INSERT and UPDATE across ledger and account, and
--       V2__runtime_delete_grants.sql grants it DELETE on three tables -- so running
--       a verification pass as it would let the principal modify the data it is
--       verifying, inverting the control the pass exists to provide, and would make
--       the pass's own execution a row-level disclosure of every balance and amount
--       in the system for the sake of nine sums. V3 removes the need instead of
--       documenting a workaround, by publishing the nine aggregates as an
--       owner-backed view and granting SELECT on that view alone.
--     - Nothing is written: no row, no object, no session setting. A principal
--       holding SELECT on one view and nothing else is sufficient -- and, being
--       unable to write anything anywhere, is incapable of altering the data it is
--       verifying.
--
-- Returns exactly one result set of exactly nine rows, one per money column --
-- five from account.accounts and one from each of the other four tables:
--     target_table    text     schema-qualified name of the table read
--     money_column    text     column name as the owning migration declares it
--     cobol_field     text     originating COBOL field, e.g. ACCT-CURR-BAL
--     cobol_picture   text     that field's PICTURE, e.g. PIC S9(10)V99
--     sql_type        text     the migrated column type, e.g. NUMERIC(12,2)
--     row_count       bigint   COUNT(*) over the whole table
--     total           numeric  exact sum of the column; 0.00 when it has no rows
--     negative_rows   bigint   rows in which the column is strictly negative
--   Row order is fixed by an integer ordinal that is not projected, so two runs
--   over the same data produce byte-identical output and the paired harness can
--   line-diff the result rather than parse it.
--
-- Fails when:
--   - SQLSTATE 42P01 undefined_table -- the aggregate view is missing because
--     V3__verification_surfaces.sql has not been applied, or a base table beneath
--     it is missing because its owning migration has not been applied. Failing is
--     correct and preferable to a short report that silently omits the column it
--     could not read.
--   - SQLSTATE 42703 undefined_column -- a money column has been renamed in its
--     migration and the aggregate view was not updated with it. The names in the
--     descriptor below are transcribed from those migrations for exactly this
--     reason. A rename surfaces when the view is created rather than when this file
--     runs, which is earlier and therefore better.
--   - SQLSTATE 42501 insufficient_privilege -- the session role lacks USAGE on the
--     `reporting` schema or SELECT on reporting.v_verification_money_totals, as
--     described under session context. No base table can cause a privilege error
--     here, because this file names none.
--
-- Misleads when: a total is read as proof of a correct load. Every case is
--   enumerated under "What this pass CANNOT prove", and two readings deserve
--   naming because they look like failures and are not. ledger.transactions is
--   legitimately EMPTY straight after the ETL, so total 0.00 is the correct
--   result there and only becomes meaningful once posting has run; and
--   negative_rows is legitimately 0 on eight of the nine columns in seed state,
--   so a zero is only evidence of a defect on a column that should carry
--   negatives. Both are detailed at the rows themselves.
--
-- WHY (non-obvious design decisions):
--       (1) Assumptions: every aggregate stays in numeric and nothing is ever
--       cast to a binary floating-point type. The aggregation itself now lives in
--       reporting.v_verification_money_totals, so that reasoning is recorded with
--       it, in V3__verification_surfaces.sql, rather than restated here.
--       (2) Assumptions: each total is wrapped in COALESCE against an empty
--       table, at scale 2 so an empty table prints 0.00. Recorded with the
--       aggregation, in V3.
--       (3) Assumptions: no timestamp column appears in any predicate, grouping,
--       ordering or filter here or in the view. Detail below.
--       (4) Alternatives Considered: COUNT(*) is reported beside every total
--       rather than the total alone, and it is COUNT(*) rather than
--       COUNT(<column>). Recorded with the aggregation, in V3.
--       (5) Alternatives Considered: a count of negative rows is reported as the
--       signature of a sign-decode defect. Detail at that column's descriptor
--       note below; the expression itself is in V3.
--       (6) Assumptions: exactly these nine columns over exactly these five
--       tables, and no others. Detail at the exclusion note below the
--       descriptor.
--       (7) Assumptions: every column name is transcribed from the owning
--       migration rather than derived from its COBOL field name. Detail at the
--       descriptor.
--       (8) Assumptions: every table is named schema-qualified rather than
--       resolved through search_path. Detail at the aggregate branches.
--       (9) Trade-offs: reference.disclosure_groups.interest_rate is included
--       even though a rate is arguably not money. Detail at its descriptor row.
--       (10) Alternatives Considered: one statement, terminated by one
--       semicolon, ordered by a non-projected ordinal. Detail at the statement.
--       (11) Assumptions: this file carries NO psql meta-command, not even
--       ON_ERROR_STOP. The runbook passes -v ON_ERROR_STOP=1 on the command line,
--       so a meta-command here would buy nothing and would make the text unusable
--       through a driver cursor, which the single-statement contract above exists
--       to keep possible.
--       (12) Assumptions: no sign expectation is asserted for either cycle
--       accumulator. Measured against the authoritative EBCDIC extract,
--       curr_cyc_credit and curr_cyc_debit are both exactly 0.00 across all fifty
--       account records, so a rule such as "debit is expected to be negative or
--       zero" would send an operator hunting a defect the seed data cannot
--       exhibit. The negative-row count reported beside each total is the only
--       sign signal this pass makes, and item (5) records which single column it
--       is live on.
--       (13) Alternatives Considered: the `aggregated` CTE reads ONE view rather
--       than the five base tables the nine aggregates are computed over. Detail at
--       the aggregate source below.
-- =============================================================================

-- WHY : Alternatives Considered: ONE statement, one terminating semicolon, and a
--       fixed integer ordering. Two alternatives were rejected for concrete
--       mechanical reasons. A file of nine separate statements would lose eight
--       of the nine results through a driver cursor, whose execute() exposes only
--       the LAST result set, so a caller would receive one column's totals while
--       believing it had received the money path's -- the exact shape of false
--       assurance this pass exists to remove. Ordering by target_table and
--       money_column instead of by ordinal would make row order depend on the
--       database collation, so 'account.accounts' could sort before or after
--       'ledger.transactions' between a C-collation and a UTF-8-collation
--       cluster and break the byte-identical line-diff the ordering exists to
--       guarantee. sort_key is used in ORDER BY without being projected, which
--       keeps the contract at the eight columns the header declares.
WITH money_columns (sort_key, target_table, money_column, cobol_field,
                    cobol_picture, sql_type) AS (
    VALUES
        -- WHY : Assumptions: every column name on the right of this descriptor is
        --       TRANSCRIBED from the migration that declares it -- V1__account.sql
        --       L243-L317, V1__ledger.sql L198, L449 and L869, V1__reference.sql
        --       L337 -- and never derived from the COBOL field name beside it. No
        --       derivation rule could be correct, because the baseline-to-column
        --       mapping is not mechanical and disagrees with itself: TRAN-AMT
        --       keeps neither its prefix nor its abbreviation and becomes plain
        --       `amount`, TRAN-CAT-BAL becomes plain `balance`, while DIS-INT-RATE
        --       drops its prefix AND expands its middle word into `interest_rate`.
        --       A rule that produced any one of those would produce the wrong name
        --       for the other two. The cobol_field and cobol_picture columns are
        --       carried so a reader can audit that mapping from this output alone,
        --       without opening a copybook; they are documentation that travels
        --       with the data rather than a comment that can drift away from it.
        (1::smallint, 'account.accounts'::text,   'curr_bal'::text,
                      'ACCT-CURR-BAL'::text,      'PIC S9(10)V99'::text, 'NUMERIC(12,2)'::text),
        (2,           'account.accounts',         'credit_limit',
                      'ACCT-CREDIT-LIMIT',        'PIC S9(10)V99',       'NUMERIC(12,2)'),
        (3,           'account.accounts',         'cash_credit_limit',
                      'ACCT-CASH-CREDIT-LIMIT',   'PIC S9(10)V99',       'NUMERIC(12,2)'),
        (4,           'account.accounts',         'curr_cyc_credit',
                      'ACCT-CURR-CYC-CREDIT',     'PIC S9(10)V99',       'NUMERIC(12,2)'),
        (5,           'account.accounts',         'curr_cyc_debit',
                      'ACCT-CURR-CYC-DEBIT',      'PIC S9(10)V99',       'NUMERIC(12,2)'),

        -- WHY : Assumptions: this row's total is 0.00 immediately after the ETL
        --       and that is the CORRECT result, not a load failure. No seed
        --       dataset for the transaction master exists in either encoding:
        --       there is no app/data/ASCII/transact.txt and no TRANSACT extract
        --       under app/data/EBCDIC. app/jcl/TRANFILE.jcl defines the cluster
        --       and then primes it at L67-L74 with a REPRO whose input DD is
        --       AWS.M2.CARDDEMO.DALYTRAN.PS.INIT, measured at 350 bytes -- exactly
        --       ONE initializer record -- so even the baseline job loads no master
        --       data here. The table is filled by the posting job from
        --       ledger.daily_transactions, so this total only becomes meaningful
        --       once posting has run. The row is reported rather than omitted
        --       because an absent row reads as an oversight in the query, whereas
        --       an explicit zero beside a zero row_count is self-explaining.
        (6,           'ledger.transactions',      'amount',
                      'TRAN-AMT',                 'PIC S9(09)V99',       'NUMERIC(11,2)'),

        -- WHY : Assumptions: this is the one column whose negative_rows is a LIVE
        --       detector in seed state. Measured against the authoritative EBCDIC
        --       extract AWS.M2.CARDDEMO.DALYTRAN.PS, 50 of its 300 records carry a
        --       negative amount, so a correct load reports 50 here. A decoder that
        --       mishandled the overpunch would report 0 -- and would also inflate
        --       the total by twice the magnitude of those fifty rows, which is why
        --       the two columns are read together rather than either alone.
        (7,           'ledger.daily_transactions', 'amount',
                      'DALYTRAN-AMT',             'PIC S9(09)V99',       'NUMERIC(11,2)'),
        (8,           'ledger.transaction_category_balances', 'balance',
                      'TRAN-CAT-BAL',             'PIC S9(09)V99',       'NUMERIC(11,2)'),

        -- WHY : Trade-offs: an interest RATE is included in a money-parity pass,
        --       which stretches the name of the file. Accepted, and the inclusion
        --       is the higher-value half of this row. The field is signed zoned
        --       decimal, PIC S9(04)V99, decoded through the identical overpunch
        --       path as every balance, so it fails in the identical way; and it is
        --       one of the two operands of the accrual formula that
        --       app/cbl/CBACT04C.cbl L464-L465 computes as
        --       ( TRAN-CAT-BAL * DIS-INT-RATE ) / 1200. A sign defect in a rate is
        --       therefore not confined to one row -- it corrupts accrued interest
        --       for every account in that disclosure group. Excluding it on a
        --       naming technicality would leave the single highest-leverage
        --       numeric column in the migration unverified.
        (9,           'reference.disclosure_groups', 'interest_rate',
                      'DIS-INT-RATE',             'PIC S9(04)V99',       'NUMERIC(6,2)')
),

-- WHY : Assumptions: exactly these nine columns over exactly these five tables,
--       and the omissions are verified rather than incidental. Nine is what the
--       baseline declares: grepping the eleven base copybooks for a signed
--       fixed-point PICTURE returns exactly nine fields -- five in CVACT01Y and
--       one each in CVTRA05Y, CVTRA06Y, CVTRA01Y and CVTRA02Y -- and the migrated
--       catalog independently holds exactly nine numeric columns of scale 2
--       across these five tables and no other numeric column at all. SIX
--       migrated tables are money-free and are deliberately absent, each
--       confirmed against its copybook: card.cards (CVACT02Y -- card number,
--       account id, CVV, embossed name, expiry, status, FILLER),
--       account.customers (CVCUS01Y -- no signed or V99 field anywhere; the FICO
--       score PIC 9(03) and the SSN PIC 9(09) are unsigned integers, not money),
--       account.card_xref (CVACT03Y -- three identifiers plus FILLER), auth.users
--       (CSUSR01Y -- five character fields plus FILLER),
--       reference.transaction_types (CVTRA03Y -- code, description, FILLER) and
--       reference.transaction_categories (CVTRA04Y -- key group, description,
--       FILLER). Naming them here is the point: an unexplained absence reads as
--       an oversight, whereas a named absence can be re-checked. Also outside
--       this pass, and for a different reason, are the packed-decimal COMP-3
--       fields of the export record and the authorization IMS segments -- those
--       decode through a different codec and are not part of the base masters.
-- WHY : Assumptions: no timestamp column appears in any predicate, grouping,
--       ordering or filter below, and the omission is deliberate rather than an
--       oversight. proc_ts is a runtime wall-clock stamp -- CBTRN02C sets it from
--       Z-GET-DB2-FORMAT-TIMESTAMP at L437-L438 -- so it is never reproducible;
--       and orig_ts is deterministic only for POSTED rows, because CBACT04C sets
--       both stamps to the runtime clock on the interest transactions it
--       generates, which is why record_codec.py needs a separate INTTRAN_LAYOUT
--       that normalises both. Any timestamp predicate would therefore make this
--       report disagree with itself between two runs over identical data. A SUM
--       over a numeric column is inherently timestamp-independent, which is
--       precisely why this pass takes this form and not a windowed one.
-- WHY : Alternatives Considered: computing the nine aggregates here, naming the
--       five base tables across three schemas with a COUNT, a SUM and a filtered
--       COUNT each. Rejected -- the least-privilege verification role holds no
--       SELECT on those tables, so that form could be run only by a write-capable
--       principal, which is precisely what a verification pass must not be.
--       reporting.v_verification_money_totals, created by
--       V3__verification_surfaces.sql, publishes the (target_table, money_column,
--       row_count, total, negative_rows) tuples instead, computed under the view
--       owner's privileges, with SELECT granted to carddemo_reporting and to
--       nothing else. The nine expressions live there verbatim -- the exact NUMERIC
--       sum, the scale-2 COALESCE and the strictly-negative row count -- so this
--       file names no base table at all and a privilege error cannot originate in
--       one.
-- WHY : Assumptions: the view is named schema-qualified rather than left to
--       search_path. This file runs both under psql and through a driver cursor
--       whose session search_path is set by the calling role, so an unqualified
--       name could resolve to a different relation between the two and the report
--       would state totals it never read.
-- WHY : Trade-offs: the column list is projected explicitly rather than with a
--       star, so a column added to the view later cannot silently widen this CTE
--       and change what the join below matches on.
aggregated (target_table, money_column, row_count, total, negative_rows) AS (
    SELECT v.target_table, v.money_column, v.row_count, v.total, v.negative_rows
    FROM   reporting.v_verification_money_totals v
)

-- WHY : Assumptions: the join is on the (target_table, money_column) pair rather
--       than on either alone, because money_column is not unique on its own --
--       'amount' names a column of two different ledger tables -- and neither is
--       target_table, account.accounts contributing five rows. An inner join is
--       correct here rather than defensive: both sides are closed sets written in
--       this file, so a row that failed to match would mean the descriptor and
--       the branches had drifted apart, and losing that row loudly at review is
--       better than carrying a half-populated one into a parity report.
SELECT d.target_table,
       d.money_column,
       d.cobol_field,
       d.cobol_picture,
       d.sql_type,
       a.row_count,
       a.total,
       a.negative_rows
FROM   money_columns d
       JOIN aggregated a
         ON  a.target_table = d.target_table
         AND a.money_column = d.money_column
ORDER  BY d.sort_key;
