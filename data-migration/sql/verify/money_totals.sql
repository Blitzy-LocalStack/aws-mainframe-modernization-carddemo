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
--       and three owning migrations have created the five tables read below:
--       V1__account.sql for account.accounts, V1__ledger.sql for the three
--       ledger tables, and V1__reference.sql for reference.disclosure_groups.
--     - The session role holds USAGE on account, ledger and reference, plus
--       SELECT on those five tables. Which principals satisfy that was MEASURED
--       against a bootstrapped cluster rather than assumed, because the answer
--       is counter-intuitive: carddemo_reporting does NOT, despite its name and
--       despite this being a reporting-shaped query. V0 revokes its base-table
--       access outright at L1337-L1342 and leaves it USAGE on the reporting
--       schema alone, for the reason given at V0 L891-L895 -- it reads through
--       views, and granting it tables here would hand back "table access the
--       view arrangement exists to withhold". carddemo_batch DOES satisfy it,
--       reading all five. Note that carddemo_batch canNOT run the sibling pass,
--       which additionally reads auth.users; this pass touches no auth table, so
--       a principal sufficient here is not sufficient there. The owner role
--       carddemo_reporting_owner also reads all five but cannot log in, so it is
--       reachable only through SET ROLE. Run this as carddemo_batch, as the
--       operator principal that applied V0, or as a verification role granted
--       SELECT on exactly these five tables.
--     - Nothing is written: no row, no object, no session setting. A principal
--       holding SELECT and nothing else is sufficient.
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
--   - SQLSTATE 42P01 undefined_table -- a table is missing because its owning
--     migration has not been applied. Failing is correct and preferable to a
--     short report that silently omits the column it could not read.
--   - SQLSTATE 42703 undefined_column -- a money column has been renamed in its
--     migration and this file was not updated with it. The names here are
--     transcribed from those migrations for exactly this reason; see WHY (7).
--   - SQLSTATE 42501 insufficient_privilege -- the session role lacks USAGE on a
--     schema or SELECT on a table, as described under session context.
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
--       cast to a binary floating-point type. Detail at the aggregate branches.
--       (2) Assumptions: each total is wrapped in COALESCE against an empty
--       table. Detail at the first aggregate branch.
--       (3) Assumptions: no timestamp column appears in any predicate, grouping,
--       ordering or filter here. Detail at the aggregate branches.
--       (4) Alternatives Considered: COUNT(*) is reported beside every total
--       rather than the total alone. Detail at the first aggregate branch.
--       (5) Alternatives Considered: a count of negative rows is reported as the
--       signature of a sign-decode defect. Detail at that expression.
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
--       (11) Refactoring Rationale: this file replaces an earlier draft that
--       opened with a psql ON_ERROR_STOP meta-command and projected three
--       columns. Four things were wrong with it. The meta-command made the file
--       unusable through a driver cursor for no gain, the runbook already
--       passing -v ON_ERROR_STOP=1 on the command line; it named the ledger
--       columns amount and balance without checking them against V1__ledger.sql,
--       which happens to agree and would not have been noticed had it not; it
--       ordered by two text columns, so row order followed the database
--       collation and the byte-identical line-diff was not guaranteed; and it
--       reported neither a row count nor a negative-row count, leaving a bare
--       sum that cannot distinguish a missing row from a wrong amount. It also
--       asserted that curr_cyc_debit "is expected to be NEGATIVE or zero" and
--       that a positive total there is itself evidence of a defect. That claim
--       is withdrawn rather than carried over: measured against the authoritative
--       EBCDIC extract both cycle accumulators are exactly 0.00 across all fifty
--       account records, so the assertion was unfounded and would have sent an
--       operator looking for a defect that the seed data cannot exhibit.
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
-- WHY : Assumptions: every table is named schema-qualified rather than left to
--       search_path. This file runs both under psql and through a driver cursor
--       whose session search_path is set by the calling role, so an unqualified
--       name could resolve to a different table between the two and the report
--       would state a total for a table it never read.
-- WHY : Trade-offs: one branch per COLUMN, so account.accounts is scanned five
--       times rather than once with five aggregates unpivoted afterwards.
--       Accepted deliberately. Each branch names one table and one column on
--       adjacent lines, which is what makes the inventory auditable by reading
--       the file -- and greppable against the owning migration, which is the
--       check that catches a renamed column before it becomes a 42703. The
--       repeated scans are of a fifty-row table, and the unpivot the alternative
--       requires would put the table-to-column pairing inside a transposition
--       step where neither a reader nor a grep could see it.
aggregated (target_table, money_column, row_count, total, negative_rows) AS (
    -- WHY : (1) Assumptions: the total stays in numeric from the column through
    --       SUM to the output, and is never cast to double precision, real or any
    --       other binary floating-point type. This is not a preference. Addition
    --       of binary floating-point values is not associative, so the total
    --       would depend on the order the executor happened to read the rows in,
    --       and two runs over byte-identical data could differ in the last place
    --       -- which would make a parity pass report a difference that does not
    --       exist, or hide one that does. PostgreSQL's SUM over numeric is exact,
    --       carrying every digit. AAP rule T3 forbids floating point across the
    --       whole money path for the same reason, and the equivalent prohibition
    --       is enforced mechanically in the Java services by an ArchUnit rule;
    --       SQL has no such linter, so here it is held by construction.
    --       (2) Assumptions: the zero substituted by COALESCE is the literal
    --       0.00, and both halves of that choice matter. SUM over zero rows
    --       returns NULL, and a NULL in this column is indistinguishable in a
    --       diff from an absent column or a query that failed outright, so an
    --       empty table must report a number. The literal carries scale 2 so the
    --       empty case prints 0.00 exactly as the populated cases do, keeping all
    --       nine rows comparable and the output line-diffable; a bare 0 would
    --       print as 0 and make ledger.transactions -- legitimately empty after
    --       the ETL -- the one row whose format differs from every other.
    --       (4) Alternatives Considered: COUNT(*) is projected beside every
    --       total. A total on its own cannot distinguish "every row loaded and
    --       one amount is wrong" from "one row never arrived", because both move
    --       the sum; the pair separates them without the reader having to
    --       cross-reference the sibling pass. COUNT(*) rather than
    --       COUNT(<column>) because the latter skips NULLs and would under-report
    --       a nullable column, understating the denominator of the total.
    SELECT 'account.accounts', 'curr_bal',
           COUNT(*), COALESCE(SUM(curr_bal), 0.00),

           -- WHY : (5) Alternatives Considered: a count of strictly-negative rows,
           --       reported beside the total as the signature of a sign-decode
           --       defect. It is the cheapest available mitigation of this pass's
           --       own documented blind spot: a compensating pair of errors can
           --       leave the grand total intact, but a decoder that mishandles the
           --       overpunch drives this count to ZERO whatever the total does, so
           --       it detects a class of defect a sum cannot. Read it against the
           --       column, not in the abstract: eight of the nine columns hold no
           --       negative row in seed state, so zero is the CORRECT answer there
           --       and only ledger.daily_transactions.amount makes this a live
           --       detector today, at 50 of its 300 rows. Alternatives considered
           --       and rejected: MIN and MAX alone, because a single outlier hides
           --       inside the extremes of a fifty-row spread; and a per-row dump,
           --       because its output is unbounded and a diff cannot consume it.
           COUNT(*) FILTER (WHERE curr_bal < 0)
    FROM account.accounts
    UNION ALL
    SELECT 'account.accounts', 'credit_limit',
           COUNT(*), COALESCE(SUM(credit_limit), 0.00),
           COUNT(*) FILTER (WHERE credit_limit < 0)
    FROM account.accounts
    UNION ALL
    SELECT 'account.accounts', 'cash_credit_limit',
           COUNT(*), COALESCE(SUM(cash_credit_limit), 0.00),
           COUNT(*) FILTER (WHERE cash_credit_limit < 0)
    FROM account.accounts
    UNION ALL
    SELECT 'account.accounts', 'curr_cyc_credit',
           COUNT(*), COALESCE(SUM(curr_cyc_credit), 0.00),
           COUNT(*) FILTER (WHERE curr_cyc_credit < 0)
    FROM account.accounts
    UNION ALL
    SELECT 'account.accounts', 'curr_cyc_debit',
           COUNT(*), COALESCE(SUM(curr_cyc_debit), 0.00),
           COUNT(*) FILTER (WHERE curr_cyc_debit < 0)
    FROM account.accounts
    UNION ALL
    SELECT 'ledger.transactions', 'amount',
           COUNT(*), COALESCE(SUM(amount), 0.00),
           COUNT(*) FILTER (WHERE amount < 0)
    FROM ledger.transactions
    UNION ALL
    SELECT 'ledger.daily_transactions', 'amount',
           COUNT(*), COALESCE(SUM(amount), 0.00),
           COUNT(*) FILTER (WHERE amount < 0)
    FROM ledger.daily_transactions
    UNION ALL
    SELECT 'ledger.transaction_category_balances', 'balance',
           COUNT(*), COALESCE(SUM(balance), 0.00),
           COUNT(*) FILTER (WHERE balance < 0)
    FROM ledger.transaction_category_balances
    UNION ALL
    SELECT 'reference.disclosure_groups', 'interest_rate',
           COUNT(*), COALESCE(SUM(interest_rate), 0.00),
           COUNT(*) FILTER (WHERE interest_rate < 0)
    FROM reference.disclosure_groups
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
