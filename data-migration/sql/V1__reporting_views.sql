-- =============================================================================
-- data-migration/sql/V1__reporting_views.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Create the four read-only relations the reporting bounded context reads, in
--   the `reporting` schema, owned by carddemo_reporting_owner. reporting-service
--   owns no table, no index and no relational object of its own; it holds USAGE
--   on this schema and SELECT on the views named here, and nothing else. These
--   four views ARE that context's entire data surface, so the JPA projections in
--   services/reporting-service/src/main/java/com/carddemo/reporting/domain map
--   one relation each and map nothing outside this file.
--
--   Relation                              Read by
--   reporting.v_report_transactions       ReportTransactionView
--   reporting.v_statement_transactions    StatementTransactionView
--   reporting.v_transaction_types         TransactionTypeView
--   reporting.v_transaction_categories    TransactionCategoryView
--
--   WHAT THIS FILE DOES NOT YET CREATE, and why that is a statement rather than
--   an omission: the reporting domain package's charter declares a closed set of
--   SEVEN projections, so three further views -- over account.accounts,
--   account.customers and account.card_xref -- belong in this file and are NOT
--   here. The reason is mechanical: CREATE VIEW resolves its base references at
--   creation time, and account-service has no db/migration directory yet, so
--   those three tables do not exist and a view over them cannot be created.
--   Adding them now would make this whole file fail on its first statement and
--   would take the four views that CAN be created down with it. They must be
--   added to this file, in this transaction, in the same shape and with the same
--   grant, at the point account-service's V1__account.sql lands. Until then the
--   three projections that read them are planned rather than broken, and their
--   absence here is the single place a reader can see that.
--
-- WHY this file lives HERE and not in a service migration:
--   - Assumptions: data-migration/sql/V0__schemas_and_roles.sql establishes the
--     schema, its owner and the service role's USAGE grant, and states in its own
--     comments that it deliberately creates NO view -- a view over
--     ledger.transactions cannot be created before that table exists, and at the
--     point V0 runs no table exists anywhere. It names the step that must create
--     them: one ordered AFTER the per-service Flyway migrations. This file is
--     that step.
--   - Alternatives Considered: a db/migration/V1__reporting_views.sql inside
--     services/reporting-service, which is the reflex location for a service's
--     own DDL. Rejected because that module declares no migration tool and its
--     pom.xml records that a db/migration directory there would be an
--     affirmative defect: the views read ledger, account, card and reference
--     tables that four OTHER services' migrations create, so a Flyway history
--     inside the reporting module would order this DDL against the wrong
--     baseline and would fail whenever reporting started before those four had
--     migrated. Ordering by ETL step instead makes the dependency explicit.
--
-- Preconditions, in order. Each is a real failure if skipped, not a formality:
--   1. data-migration/sql/V0__schemas_and_roles.sql has run, so the `reporting`
--      schema exists, carddemo_reporting_owner owns it, that role holds USAGE
--      and SELECT on ledger, account, card and reference, and carddemo_reporting
--      holds USAGE on `reporting`.
--   2. The four owning services' Flyway migrations have run, so
--      ledger.transactions and reference.transaction_types /
--      reference.transaction_categories exist. CREATE VIEW resolves its
--      references at creation time, so running this file first fails with
--      "relation does not exist" naming the missing table.
--   3. This script is executed AS carddemo_reporting_owner, or by a superuser
--      that has SET ROLE to it. The owner matters twice over: only the owner may
--      create in this schema, and a view's underlying reads are checked against
--      the VIEW OWNER's privileges, which is the entire mechanism by which
--      carddemo_reporting can read through a view while holding no privilege at
--      all on the four source schemas.
--
-- Run:
--   psql "$CARDDEMO_DB_URL" -v ON_ERROR_STOP=1 \
--        -f data-migration/sql/V1__reporting_views.sql
-- =============================================================================

-- WHY : Trade-offs: one explicit transaction for the whole file. CREATE VIEW and
--       GRANT are both transactional in PostgreSQL, so an interrupted run leaves
--       no half-built surface -- either all four views and all four grants exist
--       or none do. The alternative, four independent statements, can leave
--       reporting able to read two relations and not the other two, which
--       presents as a partly-working report rather than as a failed migration.
BEGIN;

-- WHY : Assumptions: the owner is asserted rather than assumed. A run as any
--       other role would either fail on CREATE (no CREATE privilege on the
--       schema) or, if run as a superuser, succeed while making the SUPERUSER
--       the view owner -- and a superuser-owned non-security_invoker view reads
--       the base tables with superuser rights, which silently widens the exact
--       boundary these views exist to narrow. Failing loudly here is the whole
--       point of the check.
DO $$
BEGIN
    IF NOT pg_has_role(current_user, 'carddemo_reporting_owner', 'MEMBER') THEN
        RAISE EXCEPTION
            'this script must run as carddemo_reporting_owner (current_user is %); a view created'
            ' under another owner reads its base tables with that owner''s privileges',
            current_user;
    END IF;
END
$$;

SET LOCAL ROLE carddemo_reporting_owner;


-- -----------------------------------------------------------------------------
-- 1. reporting.v_report_transactions -- the transaction report's row source.
--
-- Replaces the sequential read of AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS that
-- app/cbl/CBTRN03C.cbl performs, driven by app/jcl/TRANREPT.jcl. The report's
-- date window is a QUERY predicate applied by the service, not a filter baked in
-- here: app/jcl/TRANREPT.jcl L47 supplies PARM-START-DATE and PARM-END-DATE as
-- sort control operands, so the window is a parameter of a run and two runs with
-- two windows must read one relation.
--
-- WHY : Assumptions: the column list is the projection the report actually
--       consumes and is deliberately NOT `SELECT *`. A star view acquires a
--       column the moment ledger adds one, so a column added there for another
--       purpose would become readable by this context without any change here --
--       which is precisely the widening the SELECT-only role exists to prevent.
--       Naming the columns makes the surface an explicit decision.
-- WHY : Assumptions: no masking is applied to card_num in THIS view, and that is
--       a deliberate scope statement rather than an omission. The 133-column
--       report at app/cpy/CVTRA07Y.cpy carries the card number in full, so a
--       masked view would change the report's bytes and break golden-master
--       parity. Masking is a presentation decision made per endpoint by the
--       mapper layer; the statement view below takes the same position for the
--       same reason.
-- WHY : Assumptions: security_barrier is set on every view in this file. It stops
--       the planner pushing a caller-supplied predicate below the view's own
--       filtering, which is what stops a crafted WHERE clause being evaluated
--       against an underlying value and leaking it through an error message or a
--       timing difference. It is set even on the unfiltered views, so that adding
--       a filter later cannot forget it.
-- WHY : Assumptions: the views are left in the DEFAULT non-security_invoker mode.
--       That is what makes the underlying reads run with the owner's privileges
--       and lets carddemo_reporting hold nothing on ledger, account, card or
--       reference. Setting security_invoker = true would invert the arrangement
--       and every read would fail with a permission error.
-- WHY : Assumptions: the primary account number is MASKED in both transaction
--       projections, to the last four digits behind a twelve-character filler, and
--       the cast keeps the result the character(16) width the report and statement
--       layouts declare. The reporting role reads nothing but these views, so
--       masking here is the whole of the control rather than a convention a mapper
--       could forget -- and a report or statement renders an account number to a
--       reader who has no business seeing the other twelve digits.
--       Alternatives Considered: projecting the full number and masking it in the
--       presentation mapper. Rejected because it would give the reporting role read
--       access to every primary account number in the ledger, which is exactly the
--       access the schema-per-service boundary exists to withhold.
-- WHY : Assumptions: card_fingerprint exists because a masked number cannot GROUP a
--       statement -- two cards sharing their last four digits would merge into one
--       statement, and with a twelve-digit filler that collision is a certainty
--       rather than a risk. The digest is deterministic, so it groups without
--       collision; it is used ONLY as a grouping key, is not reversible to a card
--       number by anyone who does not already hold that number, and no client sees
--       it.
CREATE VIEW reporting.v_report_transactions
    WITH (security_barrier = true) AS
SELECT
    t.transaction_id,
    t.type_cd,
    t.category_cd,
    t.source,
    t.description,
    t.amount,
    t.merchant_id,
    t.merchant_name,
    t.merchant_city,
    t.merchant_zip,
    ('************' || right(rtrim(t.card_num), 4))::character(16) AS card_num,
    t.orig_ts,
    t.proc_ts
FROM ledger.transactions AS t;

COMMENT ON VIEW reporting.v_report_transactions IS
    'Row source for the 133-column transaction report (app/cbl/CBTRN03C.cbl, app/jcl/TRANREPT.jcl). '
    'Projects the thirteen columns of ledger.transactions the report consumes; the report date '
    'window is applied per run as a predicate on proc_ts, never baked into this view.';

-- WHY : Assumptions: ownership is ALSO assigned explicitly, view by view, even though
--       every definition above ran under SET LOCAL ROLE and is therefore already owned
--       by this role. The redundancy is deliberate and cheap: a view's owner is the
--       identity whose privileges its body is evaluated with, so it is the single most
--       consequential property in this file, and stating it per view means the catalogue
--       records the intent for each projection rather than leaving it to be inferred from
--       one SET LOCAL ROLE statement a hundred lines earlier. It also makes the property
--       assertable per view -- data-migration/tests/test_reporting_views.py checks each
--       one by name -- and it fails closed: if the role statement were ever removed or
--       moved, these statements would error rather than silently leave a projection owned
--       by whichever superuser ran the migration, which is the outcome that would quietly
--       hand the reporting login the base tables through a barrier it cannot see.
ALTER VIEW reporting.v_report_transactions OWNER TO carddemo_reporting_owner;


-- -----------------------------------------------------------------------------
-- 2. reporting.v_statement_transactions -- the statement generator's row source.
--
-- Replaces the card-ordered view of the transaction master that
-- app/cbl/CBSTM03A.CBL reads through app/cpy/COSTM01.CPY, whose leading key is
-- the card number followed by the transaction identifier rather than the
-- transaction identifier alone.
--
-- WHY : Assumptions: this is a VIEW over the same table as (1) and NOT a second
--       table. The AAP is explicit that the card-ordered transaction view becomes
--       an index plus a read-only projection rather than a separate relation, and
--       the index that makes the ordering cheap already exists --
--       idx_transactions_card_num, created by
--       services/transaction-service/src/main/resources/db/migration/V1__ledger.sql.
--       Materialising a second copy would double the storage, need a refresh step
--       in the nightly chain, and introduce a window in which a statement could
--       be generated from transactions that posting had already superseded.
-- WHY : Assumptions: no ORDER BY appears here. A view's ordering is not a
--       contract a caller can rely on -- the planner is free to discard it under
--       a join or an outer query -- so the ordering the statement generator needs
--       belongs in the query the repository issues, where it is visible and
--       plannable against the index above. Writing it here would look like a
--       guarantee and would not be one.
CREATE VIEW reporting.v_statement_transactions
    WITH (security_barrier = true) AS
SELECT
    ('************' || right(rtrim(t.card_num), 4))::character(16) AS card_num,
    md5(rtrim(t.card_num))                                        AS card_fingerprint,
    t.transaction_id,
    t.type_cd,
    t.category_cd,
    t.source,
    t.description,
    t.amount,
    t.merchant_id,
    t.merchant_name,
    t.merchant_city,
    t.merchant_zip,
    t.orig_ts,
    t.proc_ts
FROM ledger.transactions AS t;

COMMENT ON VIEW reporting.v_statement_transactions IS
    'Card-ordered projection of ledger.transactions for statement generation (app/cbl/CBSTM03A.CBL '
    'through app/cpy/COSTM01.CPY). Same table as v_report_transactions, projected with the card '
    'number leading because the statement key is card number then transaction identifier.';

ALTER VIEW reporting.v_statement_transactions OWNER TO carddemo_reporting_owner;


-- -----------------------------------------------------------------------------
-- 3. reporting.v_transaction_types -- the two-character type code and its name.
--
-- WHY : Assumptions: THE COLUMN IS `description` AND THE VIEW EXPOSES IT AS
--       `type_desc`, and both halves of that sentence are load-bearing.
--       reference.transaction_types, created by
--       services/reference-service/src/main/resources/db/migration/V1__reference.sql,
--       declares (type_cd CHAR(2), description VARCHAR(50)). There is no
--       `type_desc` column anywhere in that schema. The alias here is what lets
--       the reporting projection keep the fuller name, which it needs because the
--       report joins TWO descriptions of the same width -- the TYPE description
--       at app/cpy/CVTRA03Y.cpy L6 and the CATEGORY description at
--       app/cpy/CVTRA04Y.cpy L8, both X(50), moved to two different report fields
--       at app/cbl/CBTRN03C.cbl L366 and L368. A bare `description` on both sides
--       of that join is the ambiguity the alias removes.
-- WHY : Alternatives Considered: having the reporting projection map
--       reference.transaction_types directly, which is what an earlier revision
--       did. Rejected on two independent grounds, either sufficient. It cannot
--       work: carddemo_reporting holds no privilege on the reference schema at
--       all, so the read fails with a permission error rather than a wrong
--       answer. And it should not work even if it did: the whole point of routing
--       this context through views is that its login cannot reach a base table,
--       so one projection reaching across would make the boundary advisory.
CREATE VIEW reporting.v_transaction_types
    WITH (security_barrier = true) AS
SELECT
    tt.type_cd,
    tt.description AS type_desc
FROM reference.transaction_types AS tt;

COMMENT ON VIEW reporting.v_transaction_types IS
    'Transaction type code and description for report and statement joins. Aliases '
    'reference.transaction_types.description to type_desc so the type description is '
    'distinguishable from the equally-wide category description in the same report.';

ALTER VIEW reporting.v_transaction_types OWNER TO carddemo_reporting_owner;


-- -----------------------------------------------------------------------------
-- 4. reporting.v_transaction_categories -- the composite category key and name.
--
-- WHY : Assumptions: the key is the PAIR (type_cd, cat_cd), which is the six-byte
--       key app/cpy/CVTRA04Y.cpy declares as TRAN-TYPE-CD X(02) followed by
--       TRAN-CAT-CD X(04). Projecting the category code alone would collapse
--       categories that share a number across two types, and the report would
--       then attribute a total to the wrong type -- an error that produces
--       plausible numbers, which is the class of defect this migration takes most
--       seriously.
-- WHY : Assumptions: the interest rate is NOT projected. It lives in
--       reference.disclosure_groups and belongs to interest accrual, not to
--       reporting, so including it here would hand this context a value it has no
--       use for and would widen the surface for nothing.
CREATE VIEW reporting.v_transaction_categories
    WITH (security_barrier = true) AS
SELECT
    tc.type_cd,
    tc.cat_cd,
    tc.description
FROM reference.transaction_categories AS tc;

COMMENT ON VIEW reporting.v_transaction_categories IS
    'Composite transaction category key (type_cd, cat_cd) and its description, from '
    'reference.transaction_categories. The pair is the key because a category number is unique '
    'only within a type (app/cpy/CVTRA04Y.cpy).';

ALTER VIEW reporting.v_transaction_categories OWNER TO carddemo_reporting_owner;


-- -----------------------------------------------------------------------------
-- Grants: SELECT on these four relations, to the service login role, and nothing
-- else anywhere.
--
-- WHY : Assumptions: the grants name the four views individually and never use
--       GRANT ... ON ALL TABLES IN SCHEMA reporting. The two forms differ in
--       future scope, not in effect today: ON ALL TABLES would also cover any
--       relation later created in this schema, so a view added for one purpose
--       would become readable by the service the moment it existed. Naming each
--       view keeps every addition a decision.
-- WHY : Assumptions: SELECT only. reporting-service's remit is masked, read-only
--       presentation and no write path exists at any layer -- V0 gives the role no
--       INSERT, UPDATE or DELETE, and a view over a join is not updatable in any
--       case. The absence of a write grant is what makes the read-only claim
--       structural rather than conventional.
GRANT SELECT ON reporting.v_report_transactions    TO carddemo_reporting;
GRANT SELECT ON reporting.v_statement_transactions TO carddemo_reporting;
GRANT SELECT ON reporting.v_transaction_types      TO carddemo_reporting;
GRANT SELECT ON reporting.v_transaction_categories TO carddemo_reporting;

-- WHY : Assumptions: no INSERT, UPDATE, DELETE or TRUNCATE is granted on any view
--       above, and the absence is stated as a REVOKE rather than left implicit. A
--       simple view over one table is AUTOMATICALLY UPDATABLE in PostgreSQL, so a
--       projection that reads as read-only would accept a write the moment the
--       privilege existed -- and three of the four views here are simple enough to
--       qualify. The reporting context owns no table and writes nothing, so this is
--       a contract rather than an oversight, and revoking makes it one the catalogue
--       records instead of one a reader has to infer from what is missing.
--       Trade-offs: the statement is a no-op on a first run, because a freshly
--       created view grants nothing to anyone but its owner. That is accepted: it
--       costs one statement and it makes a later, well-meant GRANT ALL on this
--       schema unable to leave a writable path behind without also removing this
--       line.
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER
    ON reporting.v_report_transactions,
       reporting.v_statement_transactions,
       reporting.v_transaction_types,
       reporting.v_transaction_categories
    FROM carddemo_reporting;

-- WHY : Assumptions: the role reset is explicit rather than left to transaction
--       end. SET LOCAL ROLE does end with the transaction, so this is belt and
--       braces -- but a later editor appending a statement after COMMIT would
--       otherwise run it under a role the file set and never mentioned again.
RESET ROLE;

COMMIT;
