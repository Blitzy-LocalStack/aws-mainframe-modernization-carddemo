-- =============================================================================
-- data-migration/sql/V3__verification_surfaces.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Create the two AGGREGATE-ONLY relations the migration's SQL verification
--   passes read, so that both passes can be executed by the least-privilege
--   read-only role the project specifies for verification -- carddemo_reporting
--   -- without that role holding any privilege on any base table.
--
--     Relation                                Read by
--     reporting.v_verification_row_counts     sql/verify/row_counts.sql
--     reporting.v_verification_money_totals   sql/verify/money_totals.sql
--     auth.v_verification_row_counts          the first of those, indirectly
--
--   Every column of every relation here is an AGGREGATE: a COUNT, an exact
--   NUMERIC SUM, or a filtered COUNT. No relation projects a key, an identifier,
--   an amount belonging to one row, or any other row-level value. That is the
--   whole design, and it is what makes granting SELECT on these three relations
--   a strictly smaller privilege than granting SELECT on the eleven base tables
--   the passes would otherwise have to read.
--
-- WHY this file exists (Refactoring Rationale):
--   Both verification passes were written to be run "as the operator principal
--   that applied V0, or as a verification role granted SELECT on exactly these
--   tables", and both recorded, accurately, that carddemo_reporting could not run
--   them -- V0 revokes its base-table access outright and leaves it USAGE on the
--   `reporting` schema alone, because it reads through views. money_totals.sql
--   went further and directed an operator to run it as carddemo_batch, which is a
--   WRITE-CAPABLE role: V0 grants carddemo_batch SELECT, INSERT and UPDATE across
--   ledger and account, and V2 grants it DELETE on three tables. Pointing a
--   verification step at a role that can modify the data it is verifying inverts
--   the control the step exists to provide -- a verification pass should be
--   incapable of changing its own subject.
--
--   Two alternatives were considered and rejected before this file was written:
--
--     - GRANT SELECT on the eleven base tables to carddemo_reporting. Rejected:
--       it hands back exactly the base-table access the view arrangement exists
--       to withhold, and it would give the reporting service role -- the role a
--       running service authenticates as -- row-level read access to every
--       account balance, card number, national identifier and transaction amount
--       in the system, in order to make a count of them possible. The privilege
--       needed is "how many rows" and the privilege granted would be "every row".
--     - A SECURITY DEFINER function per pass, returning the same aggregates.
--       Rejected on two grounds. A function body's reads are not visible to the
--       privilege verifier in sql/verify/reporting_view_privileges.sql, which
--       inspects relation grants, so the surface would become the one part of the
--       boundary that could not be audited by the audit that exists for it; and
--       SECURITY DEFINER carries a search_path hazard that a view does not, since
--       a view's base references are resolved and stored at creation time.
--
--   A view is therefore the right instrument, and it is the instrument this
--   project already uses for precisely this purpose: V1__reporting_views.sql
--   creates seven owner-owned, non-security_invoker views for the reporting
--   service and states the mechanism in full -- a view's underlying reads are
--   checked against the VIEW OWNER's privileges, which is how a role holding
--   nothing on the source schemas can read through it. This file applies that
--   established mechanism to the verification passes.
--
-- WHY the auth relation is separate (Assumptions):
--   carddemo_reporting_owner holds USAGE and SELECT on ledger, account, card and
--   reference -- V0 L1265-L1285 -- and deliberately NOT on auth. The auth schema
--   is held outside its grant graph because it carries the identity table, and
--   widening the reporting owner to cover it would defeat that separation for the
--   sake of one COUNT. So the auth count is produced by a view in the AUTH schema,
--   owned by carddemo_auth_owner, which does hold that privilege; the reporting
--   relation then reads that view rather than the table beneath it. The privilege
--   the reporting owner gains is SELECT on ONE aggregate view, not on auth.users.
--
--   Note which role gains that privilege: carddemo_reporting_OWNER, which cannot
--   log in (V0 creates the owners NOLOGIN and they are reachable only by SET ROLE).
--   carddemo_reporting -- the role a running service authenticates as -- gains no
--   privilege in the auth schema at all, not even USAGE. Putting the widening on
--   the non-login owner rather than on the runtime role is the point of the extra
--   hop.
--
-- Preconditions, in order. Each is a real failure if skipped:
--   1. data-migration/sql/V0__schemas_and_roles.sql has run, so the schemas,
--      owners and role graph exist.
--   2. All five owning service migrations have run, so the eleven tables read
--      below exist. CREATE VIEW resolves its base references at creation time, so
--      running this file first fails with "relation does not exist" naming the
--      missing table.
--   3. data-migration/sql/V1__reporting_views.sql has run, so the `reporting`
--      schema's grant to carddemo_reporting is in place. This file does not
--      depend on the seven views themselves.
--   4. This file is executed by a principal that can SET ROLE to BOTH
--      carddemo_auth_owner and carddemo_reporting_owner -- in practice the
--      operator principal that applied V0, or a superuser. Each half asserts its
--      own owner before creating anything, for the reason V1 records: a view
--      created under another owner reads its base tables with that owner's
--      privileges, and a superuser-owned view would silently widen the boundary
--      these views exist to narrow.
--
-- Run:
--   psql "$CARDDEMO_DB_URL" -v ON_ERROR_STOP=1 \
--        -f data-migration/sql/V3__verification_surfaces.sql
--
-- Idempotent: yes. Every CREATE is CREATE OR REPLACE and every GRANT is
--   idempotent by definition, so a second application is a no-op. A column list
--   change would require a DROP, which is why the column lists are stated once
--   here and consumed by name in the two verifiers rather than expanded with a
--   star at either end.
-- =============================================================================

-- WHY : Trade-offs: TWO transactions rather than one, because the two halves are
--       created under two different owners and PostgreSQL's SET ROLE is
--       transaction-scoped when issued as SET LOCAL. One transaction switching
--       roles mid-way would work, but the failure mode is worse: a run that
--       failed after the auth half committed would leave a partly-built surface
--       whose missing half is the one an operator is least likely to look for.
--       Two transactions make each half all-or-nothing on its own, and the second
--       depends on the first, so a failure of the first stops the second with a
--       "relation does not exist" naming exactly what is missing.

BEGIN;

-- WHY : Assumptions: the owner is asserted rather than assumed, exactly as
--       V1__reporting_views.sql asserts its own. A run as any other role either
--       fails on CREATE for want of privilege on the schema or, as a superuser,
--       succeeds while making the SUPERUSER the view owner -- at which point the
--       view reads auth.users with superuser rights and the aggregate boundary is
--       decoration. Failing loudly here is the whole point.
DO $$
BEGIN
    IF NOT pg_has_role(current_user, 'carddemo_auth_owner', 'MEMBER') THEN
        RAISE EXCEPTION
            'this script must run as a member of carddemo_auth_owner (current_user is %); a view'
            ' created under another owner reads its base table with that owner''s privileges',
            current_user;
    END IF;
END
$$;

SET LOCAL ROLE carddemo_auth_owner;

-- -----------------------------------------------------------------------------
-- auth.v_verification_row_counts -- the auth schema's contribution to pass 1.
--
-- WHY : Assumptions: this view projects exactly TWO columns and both are derived
--       from the whole table rather than from any row: a constant naming the
--       table, and COUNT(*) over it. It is not possible to learn a user
--       identifier, a name, a type or a subject reference from this relation, and
--       there is no predicate a caller can add that would change that -- the
--       aggregate is computed before any outer filter can apply, so a WHERE on
--       the result filters the single row and not the rows beneath it.
-- WHY : Assumptions: COUNT(*) and never COUNT(<column>). A column-qualified count
--       skips NULLs, and the sibling verifier's own commentary records the case
--       this matters for elsewhere in the corpus; using COUNT(*) uniformly across
--       both halves of this surface means the two cannot disagree about what
--       "row count" means.
-- WHY : Assumptions: security_barrier is set, matching every view in
--       V1__reporting_views.sql. It stops the planner pushing a caller-supplied
--       predicate below the view's own evaluation, which for an aggregate view is
--       belt and braces rather than load-bearing -- and it is set anyway, so that
--       a later revision adding a filter cannot forget it.
-- WHY : Assumptions: the view is left in the DEFAULT non-security_invoker mode,
--       which is what makes the read of auth.users run with carddemo_auth_owner's
--       privileges. That is the entire mechanism by which carddemo_reporting_owner
--       -- which holds nothing at all in the auth schema -- can read this count.
--       Setting security_invoker = true would invert it and every read would fail.
CREATE OR REPLACE VIEW auth.v_verification_row_counts
    WITH (security_barrier = true) AS
SELECT 'auth.users'::text AS target_table,
       COUNT(*)::bigint   AS actual_rows
FROM   auth.users;

-- WHY : Assumptions: USAGE on the schema and SELECT on the view are granted to
--       carddemo_reporting_OWNER and to nothing else -- not to carddemo_reporting,
--       not to carddemo_batch, not to PUBLIC. USAGE on a schema conveys name
--       resolution only and no privilege on any object in it, so the reporting
--       owner gains the ability to name auth.v_verification_row_counts and
--       nothing further; auth.users itself remains unreadable to it.
GRANT USAGE ON SCHEMA auth TO carddemo_reporting_owner;
GRANT SELECT ON auth.v_verification_row_counts TO carddemo_reporting_owner;

COMMIT;


BEGIN;

DO $$
BEGIN
    IF NOT pg_has_role(current_user, 'carddemo_reporting_owner', 'MEMBER') THEN
        RAISE EXCEPTION
            'this script must run as a member of carddemo_reporting_owner (current_user is %); a'
            ' view created under another owner reads its base tables with that owner''s privileges',
            current_user;
    END IF;
END
$$;

SET LOCAL ROLE carddemo_reporting_owner;

-- -----------------------------------------------------------------------------
-- reporting.v_verification_row_counts -- pass 1's entire row source.
--
-- Eleven rows, one per (target table) the row-count pass reports on: ten counted
-- here against the schemas this owner reads, and auth.users read through the view
-- created above.
--
-- WHY : Assumptions: every table is named schema-qualified rather than resolved
--       through search_path. A view's references are resolved at creation time, so
--       an unqualified name would bind to whatever the CREATING session's
--       search_path pointed at and the view would then permanently report a count
--       for a table nobody meant. The qualified name costs one prefix per branch
--       and removes that class of error entirely.
-- WHY : Trade-offs: the eleven counts are a UNION ALL of eleven single-row
--       aggregates rather than one query over a catalog view such as
--       pg_stat_user_tables. Rejected the catalog form for a specific reason: its
--       n_live_tup is an ESTIMATE maintained by the statistics collector, which is
--       approximate, lags behind a load, and resets on a statistics reset -- so a
--       verification pass built on it could report a mismatch that does not exist
--       or, worse, agreement that does not. An exact COUNT(*) is the only figure a
--       load can be judged by. The accepted cost is eleven scans of small tables.
-- WHY : Assumptions: the same eleven tables the pass has always reported on, and
--       no others. Which tables are deliberately excluded, and why, is stated at
--       the exclusion note in sql/verify/row_counts.sql; that decision is not
--       restated here, because two statements of one exclusion list is how the two
--       come to disagree.
CREATE OR REPLACE VIEW reporting.v_verification_row_counts
    WITH (security_barrier = true) AS
SELECT 'account.accounts'::text AS target_table, COUNT(*)::bigint AS actual_rows
FROM   account.accounts
UNION ALL
SELECT 'card.cards', COUNT(*) FROM card.cards
UNION ALL
SELECT 'account.card_xref', COUNT(*) FROM account.card_xref
UNION ALL
SELECT 'account.customers', COUNT(*) FROM account.customers
UNION ALL
SELECT 'ledger.daily_transactions', COUNT(*) FROM ledger.daily_transactions
UNION ALL
SELECT 'reference.disclosure_groups', COUNT(*) FROM reference.disclosure_groups
UNION ALL
SELECT 'ledger.transaction_category_balances', COUNT(*)
FROM   ledger.transaction_category_balances
UNION ALL
SELECT 'reference.transaction_categories', COUNT(*) FROM reference.transaction_categories
UNION ALL
SELECT 'reference.transaction_types', COUNT(*) FROM reference.transaction_types
UNION ALL
SELECT 'ledger.transactions', COUNT(*) FROM ledger.transactions
UNION ALL
-- WHY : Assumptions: the auth count comes from the view rather than from
--       auth.users, and this line is the reason the auth half of this file exists.
--       Reading the table directly here would fail: this view's owner holds no
--       privilege in the auth schema, and a non-security_invoker view's reads are
--       checked against its OWNER. The nested view supplies the count under its
--       own owner's privileges instead.
SELECT target_table, actual_rows FROM auth.v_verification_row_counts;

-- -----------------------------------------------------------------------------
-- reporting.v_verification_money_totals -- pass 3's entire row source.
--
-- Nine rows, one per money column: five from account.accounts and one from each
-- of the four other tables that carry an exact fixed-point amount.
--
-- WHY : Assumptions: the total is NUMERIC from the column through SUM to the
--       output and is never cast to double precision, real or any other binary
--       floating-point type. Addition of binary floating-point values is not
--       associative, so a total would depend on the order the executor happened to
--       read the rows in and two runs over byte-identical data could differ in the
--       last place -- which would make a parity pass report a difference that does
--       not exist, or hide one that does. AAP rule T3 forbids floating point
--       across the whole money path; the Java services hold it with an ArchUnit
--       rule, SQL has no such linter, so here it is held by construction.
-- WHY : Assumptions: the zero substituted by COALESCE carries scale 2, so an empty
--       table prints 0.00 exactly as a populated one does. SUM over zero rows
--       returns NULL, and a NULL here is indistinguishable in a diff from an
--       absent column or a query that failed outright; a bare 0 would print as 0
--       and make ledger.transactions -- legitimately empty after the ETL -- the one
--       row whose format differs from every other.
-- WHY : Assumptions: a strictly-negative row COUNT is projected beside every total,
--       because it detects a class of defect a sum cannot: a compensating pair of
--       mis-decodes leaves a total intact, but a decoder mishandling the sign
--       overpunch drives this count to zero whatever the total does. The full
--       reasoning, and which single column makes it a live detector on seed data,
--       is recorded at the corresponding branch in sql/verify/money_totals.sql.
-- WHY : Trade-offs: five separate scans of account.accounts, one per money column,
--       rather than one scan producing five aggregates unpivoted afterwards. Each
--       branch names one table and one column on adjacent lines, which is what
--       makes the inventory auditable by reading the file and greppable against
--       the owning migration; the unpivot would put the table-to-column pairing
--       inside a transposition step where neither a reader nor a grep could see it.
--       The repeated scans are of a fifty-row table.
CREATE OR REPLACE VIEW reporting.v_verification_money_totals
    WITH (security_barrier = true) AS
SELECT 'account.accounts'::text                   AS target_table,
       'curr_bal'::text                           AS money_column,
       COUNT(*)::bigint                           AS row_count,
       COALESCE(SUM(curr_bal), 0.00)              AS total,
       COUNT(*) FILTER (WHERE curr_bal < 0)::bigint AS negative_rows
FROM   account.accounts
UNION ALL
SELECT 'account.accounts', 'credit_limit',
       COUNT(*), COALESCE(SUM(credit_limit), 0.00),
       COUNT(*) FILTER (WHERE credit_limit < 0)
FROM   account.accounts
UNION ALL
SELECT 'account.accounts', 'cash_credit_limit',
       COUNT(*), COALESCE(SUM(cash_credit_limit), 0.00),
       COUNT(*) FILTER (WHERE cash_credit_limit < 0)
FROM   account.accounts
UNION ALL
SELECT 'account.accounts', 'curr_cyc_credit',
       COUNT(*), COALESCE(SUM(curr_cyc_credit), 0.00),
       COUNT(*) FILTER (WHERE curr_cyc_credit < 0)
FROM   account.accounts
UNION ALL
SELECT 'account.accounts', 'curr_cyc_debit',
       COUNT(*), COALESCE(SUM(curr_cyc_debit), 0.00),
       COUNT(*) FILTER (WHERE curr_cyc_debit < 0)
FROM   account.accounts
UNION ALL
SELECT 'ledger.transactions', 'amount',
       COUNT(*), COALESCE(SUM(amount), 0.00),
       COUNT(*) FILTER (WHERE amount < 0)
FROM   ledger.transactions
UNION ALL
SELECT 'ledger.daily_transactions', 'amount',
       COUNT(*), COALESCE(SUM(amount), 0.00),
       COUNT(*) FILTER (WHERE amount < 0)
FROM   ledger.daily_transactions
UNION ALL
SELECT 'ledger.transaction_category_balances', 'balance',
       COUNT(*), COALESCE(SUM(balance), 0.00),
       COUNT(*) FILTER (WHERE balance < 0)
FROM   ledger.transaction_category_balances
UNION ALL
SELECT 'reference.disclosure_groups', 'interest_rate',
       COUNT(*), COALESCE(SUM(interest_rate), 0.00),
       COUNT(*) FILTER (WHERE interest_rate < 0)
FROM   reference.disclosure_groups;

-- WHY : Assumptions: SELECT is granted to carddemo_reporting and to nothing else
--       -- not to carddemo_batch, which is write-capable and must not be the
--       principal a verification pass runs as, and not to PUBLIC. The two passes
--       then run under a role that can read nine aggregates and seven reporting
--       views and cannot read one base-table row or write anything anywhere,
--       which is the property the verification step is supposed to have.
GRANT SELECT ON reporting.v_verification_row_counts   TO carddemo_reporting;
GRANT SELECT ON reporting.v_verification_money_totals TO carddemo_reporting;

COMMIT;
