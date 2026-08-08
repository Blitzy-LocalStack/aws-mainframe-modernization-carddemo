-- =============================================================================
-- data-migration/sql/V1__reporting_views.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Create the four read-only relations the reporting bounded context reads, in
--   the `reporting` schema, owned by carddemo_reporting_owner. reporting-service
--   owns no table, no index and no relational object of its own; the role it
--   connects as holds USAGE on this schema and SELECT on the seven views named
--   here, and nothing else -- notably not on the one table this file creates.
--   Those seven views ARE that context's entire readable data surface, so the JPA
--   projections in
--   services/reporting-service/src/main/java/com/carddemo/reporting/domain map
--   one relation each and map nothing outside this file.
--
--   Relation                              Read by
--   reporting.v_report_transactions       ReportTransactionView
--   reporting.v_statement_transactions    StatementTransactionView
--   reporting.v_transaction_types         TransactionTypeView
--   reporting.v_transaction_categories    TransactionCategoryView
--   reporting.v_accounts                  the account-backed projection
--   reporting.v_customers                 the customer-backed projection
--   reporting.v_card_xref                 the cross-reference projection
--
--   Refactoring Rationale: an earlier revision of this file created only the
--   first FOUR of those relations and recorded, here, that the remaining three
--   could not be created because account-service had no db/migration directory
--   and CREATE VIEW resolves its base references at creation time -- so a view
--   over account.accounts would have failed and taken the whole transaction with
--   it. That reason was accurate when written and is now spent:
--   services/account-service/src/main/resources/db/migration/V1__account.sql
--   creates account.accounts, account.customers and account.card_xref, so the
--   three views are created below and the reporting domain package's declared set
--   of SEVEN projections is complete. The ORDERING that made the earlier state
--   necessary has not gone away and is stated under "Prerequisites" below.
--
--   This file additionally creates ONE table, reporting.card_grouping_key, which
--   is not a projection and is not readable by the reporting service role. It
--   holds the secret described at the statement view, and it is the single
--   relation in this schema that role may not select from.
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

-- WHY : Trade-offs: one explicit transaction for the whole file. CREATE TABLE,
--       CREATE VIEW and GRANT are all transactional in PostgreSQL, so an
--       interrupted run leaves no half-built surface -- either the grouping-key
--       table, all seven views and all seven grants exist, or none of them do.
--       The alternative, letting each object commit independently, can leave
--       reporting able to read some relations and not others, which presents as a
--       partly-working report rather than as a failed migration.
-- WHY : Refactoring Rationale: this note said "all four views and all four grants"
--       and offered "four independent statements" as the alternative. The file has
--       created seven views since the statement and reference projections were
--       added, so the figure was falsified by the file it describes -- and it is
--       the shape of claim that goes stale silently, because nothing fails when a
--       comment undercounts. The counts are restated from the CREATE and GRANT
--       statements below, and the alternative is described by its mechanism rather
--       than by a number so that adding an eighth view cannot invalidate it again.
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
--       collision; it is used ONLY as a grouping key and no client ever sees it.
-- WHY : Refactoring Rationale: that token is KEYED, and an earlier revision's
--       unkeyed md5(rtrim(card_num)) was not. The earlier form was accompanied by
--       the claim that it was "not reversible to a card number by anyone who does
--       not already hold that number", and that claim was false in a way worth
--       stating plainly, because it is the reason this section changed. A digest is
--       only as hard to invert as its input space is large, and a card number is a
--       sixteen-digit decimal string -- so holding the digest is enough to recover
--       the number by exhaustive search, without any prior knowledge of it. Against
--       a known issuer prefix and a check digit the search collapses further still.
--       The masked card_num column beside it would then be masked in appearance
--       only: a reader holding both columns could recover in full what the masking
--       exists to withhold. Mixing in a secret the holder of the token does not
--       have removes the search entirely, because the attacker no longer knows what
--       to hash.
-- -----------------------------------------------------------------------------
-- 0. reporting.card_grouping_key -- the secret behind the statement grouping token.
--
-- WHY : Assumptions: ONE row, ONE column, and a value generated here rather than
--       supplied. gen_random_uuid() is core PostgreSQL from version 13 onward, so
--       this needs no extension -- pgcrypto is deliberately not a dependency, and
--       sha256() below is core as well. The value never leaves the database and is
--       never displayed, so its only requirement is that it be unpredictable and
--       stable, which a random UUID satisfies.
-- WHY : Assumptions: the single-row shape is enforced rather than assumed. A second
--       row would make the join at the statement view multiply every transaction
--       row it touches, silently doubling a statement; the primary key on a column
--       fixed to one value is what makes that unrepresentable.
-- WHY : Trade-offs: the token is a KEYED DIGEST rather than a random surrogate
--       per card. A surrogate -- one generated identifier per distinct card, held
--       in a mapping table -- is unconditionally unlinkable and therefore
--       stronger, and it was rejected on operational cost rather than on strength:
--       it needs a row inserted for every card that appears, which means a write
--       path and a refresh step inside a context whose entire point is that it
--       holds no writable relation and no maintenance job. The keyed digest needs
--       neither, is computed on read, and covers cards that appear after this file
--       ran. What it gives up is that the mapping is recoverable BY the holder of
--       the key, which is the database owner and no one else.
-- WHY : Assumptions: the value is stable for the life of the database, and
--       ON CONFLICT DO NOTHING is what keeps it so. Re-running this file must not
--       rotate the key: the token would change, and a statement run spanning the
--       rotation would break one card into two groups. Rotation is therefore a
--       deliberate operator act, not a side effect of re-running a migration.
CREATE TABLE IF NOT EXISTS reporting.card_grouping_key (
    singleton   boolean NOT NULL DEFAULT true,
    key_value   text    NOT NULL,
    CONSTRAINT pk_card_grouping_key PRIMARY KEY (singleton),
    CONSTRAINT ck_card_grouping_key_singleton CHECK (singleton)
);

INSERT INTO reporting.card_grouping_key (singleton, key_value)
VALUES (true, gen_random_uuid()::text)
ON CONFLICT (singleton) DO NOTHING;

ALTER TABLE reporting.card_grouping_key OWNER TO carddemo_reporting_owner;

-- WHY : Assumptions: this REVOKE is required and is not merely defensive. The
--       bootstrap sets a default privilege granting SELECT on TABLES in this schema
--       to carddemo_reporting (data-migration/sql/V0__schemas_and_roles.sql), and
--       PostgreSQL default privileges cannot distinguish a view from a table -- so
--       without this statement the key table would be readable by the very role the
--       key is being withheld from, and the token would be invertible again by
--       anyone who could run two SELECTs. The bootstrap issues the same revoke,
--       guarded, after its blanket grant; that one closes the window a re-run of the
--       bootstrap opens, and this one closes the window creation opens. Neither
--       makes the other redundant.
REVOKE ALL ON reporting.card_grouping_key FROM carddemo_reporting;

COMMENT ON TABLE reporting.card_grouping_key IS
    'Single-row secret mixed into reporting.v_statement_transactions.card_fingerprint so that the '
    'per-card grouping token cannot be inverted to a card number by exhaustive search. Readable by '
    'carddemo_reporting_owner only; never granted to carddemo_reporting and never displayed.';


-- -----------------------------------------------------------------------------
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
    -- WHY : Assumptions: the key is joined in rather than read by a scalar
    --       subquery per row, so it is read exactly once for the whole scan. The
    --       join is a CROSS JOIN over a one-row table, which the single-row
    --       primary key above guarantees cannot multiply the transaction rows.
    -- WHY : Assumptions: convert_to(..., 'UTF8') rather than a bare cast, because
    --       sha256 takes bytea and the conversion has to be EXPLICIT about its
    --       encoding: an implicit one would make the token depend on the server
    --       encoding, so the same card would fingerprint differently on two
    --       databases holding the same data. The key is concatenated as a prefix
    --       so that the card digits terminate the input, which keeps the token a
    --       function of the whole trimmed number rather than of a prefix of it.
    encode(sha256(convert_to(k.key_value || rtrim(t.card_num), 'UTF8')), 'hex')
                                                                  AS card_fingerprint,
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
FROM ledger.transactions AS t
CROSS JOIN reporting.card_grouping_key AS k;

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
-- 5. reporting.v_accounts -- the account master, without its money-bearing detail
--    beyond what a statement or report prints.
--
-- Supports the account-backed reporting the baseline performs from the account
-- master: app/cbl/CBSTM03A.CBL reads it to head a statement, and
-- app/cbl/CBTRN03C.cbl reaches it for the account a transaction posted to.
--
-- WHY : Assumptions: the projection is a strict SUBSET of account.accounts and
--       omits three columns deliberately -- cash_credit_limit, curr_cyc_credit and
--       curr_cyc_debit. None of the three is printed by any statement or report
--       band in services/reporting-service, so including them would widen the
--       reporting role's reach past what it renders. The columns that ARE here are
--       the ones a band prints: the identifier, the status, the balance, the credit
--       limit, the group and the three dates.
-- WHY : Assumptions: no masking is applied to any column, and that is a statement
--       about what an account identifier is rather than an omission. Unlike a card
--       number it is not cardholder data and it appears in full on every printed
--       statement and report in the baseline, so masking it here would break the
--       output this context exists to reproduce while protecting nothing.
CREATE VIEW reporting.v_accounts
    WITH (security_barrier = true) AS
SELECT
    a.account_id,
    a.active_status,
    a.curr_bal,
    a.credit_limit,
    a.open_date,
    a.expiration_date,
    a.reissue_date,
    a.group_id
FROM account.accounts AS a;

COMMENT ON VIEW reporting.v_accounts IS
    'Read-only projection of account.accounts for statement and report heading data '
    '(app/cbl/CBSTM03A.CBL, app/cbl/CBTRN03C.cbl). Omits cash_credit_limit and the two cycle '
    'accumulators because no reporting band prints them.';

ALTER VIEW reporting.v_accounts OWNER TO carddemo_reporting_owner;


-- -----------------------------------------------------------------------------
-- 6. reporting.v_customers -- the customer name and address a statement heads with,
--    and nothing that identifies the customer nationally.
--
-- WHY : Assumptions: the two enciphered national-identifier columns of
--       account.customers -- the ones the schema mapping records as the model's most
--       sensitive attributes -- are ABSENT from this projection rather than masked in
--       it. No statement or report band prints either, so the correct reach for this
--       role is none at all: projecting a masked form would still give the role a
--       column to read, and projecting the ciphertext would hand it material to
--       attack offline. Omission is the only form of masking that cannot be undone
--       by a later change to a mapper.
-- WHY : Assumptions: those two columns are described here rather than NAMED, and the
--       indirection is deliberate. data-migration/tests/test_reporting_views.py
--       asserts that neither identifier appears anywhere in this file, comments
--       included, precisely so that no protected column can be uncommented into a
--       projection later. Spelling them out to explain their absence would defeat
--       the check that guarantees the absence, so the explanation is kept and the
--       identifiers are not.
-- WHY : Assumptions: fico_credit_score is absent for the same reason -- it is a
--       credit assessment, not statement heading data, and no band prints it.
-- WHY : Assumptions: the three address lines are all projected, including
--       addr_line_3, which app/cbl/COACTUPC.cbl treats as the city (:1615). A
--       statement heading prints the whole address block, so dropping any line
--       would truncate the rendered address.
CREATE VIEW reporting.v_customers
    WITH (security_barrier = true) AS
SELECT
    c.customer_id,
    c.first_name,
    c.middle_name,
    c.last_name,
    c.addr_line_1,
    c.addr_line_2,
    c.addr_line_3,
    c.addr_state_cd,
    c.addr_country_cd,
    c.addr_zip,
    c.dob
FROM account.customers AS c;

COMMENT ON VIEW reporting.v_customers IS
    'Read-only projection of account.customers for statement heading data (app/cpy/COSTM01.CPY). '
    'Deliberately omits the two enciphered national-identifier columns, the credit score, both '
    'phone numbers and the transfer account reference: no reporting band prints any of them, so '
    'the role reads none of them.';

ALTER VIEW reporting.v_customers OWNER TO carddemo_reporting_owner;


-- -----------------------------------------------------------------------------
-- 7. reporting.v_card_xref -- card to customer and account, with the card masked.
--
-- Carries across the access path app/cbl/CBSTM03A.CBL uses to resolve the card a
-- statement is being produced for to the account and customer it belongs to.
--
-- WHY : Assumptions: card_num is masked to its last four digits by the SAME
--       expression the two transaction views use, and cast to character(16) so the
--       column keeps the declared width of the field it projects. The reason is
--       the reason recorded on v_report_transactions: masking in the view is the
--       whole of the control, and projecting the full number here would hand the
--       reporting role every primary account number in the cross-reference --
--       defeating the masking on the other views, since this relation joins to
--       both of them.
-- WHY : Assumptions: NO fingerprint column here, unlike the statement view. A
--       fingerprint exists to GROUP rows that a masked number would merge, and
--       this relation is keyed one row per card rather than many rows per card, so
--       there is nothing to group. Adding one would create a second place the
--       grouping secret is read for no purpose.
CREATE VIEW reporting.v_card_xref
    WITH (security_barrier = true) AS
SELECT
    ('************' || right(rtrim(x.card_num), 4))::character(16) AS card_num,
    x.customer_id,
    x.account_id
FROM account.card_xref AS x;

COMMENT ON VIEW reporting.v_card_xref IS
    'Read-only projection of account.card_xref resolving a card to its customer and account for '
    'statement generation (app/cbl/CBSTM03A.CBL). The card number is masked to its last four '
    'digits, as on every other card-bearing relation in this schema.';

ALTER VIEW reporting.v_card_xref OWNER TO carddemo_reporting_owner;


-- -----------------------------------------------------------------------------
-- Grants: SELECT on these seven relations, to the service login role, and nothing
-- else anywhere. The one TABLE this file creates is deliberately not among them;
-- its own revoke is stated where it is created.
--
-- WHY : Assumptions: the grants name the seven views individually and never use
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
GRANT SELECT ON reporting.v_accounts               TO carddemo_reporting;
GRANT SELECT ON reporting.v_customers              TO carddemo_reporting;
GRANT SELECT ON reporting.v_card_xref              TO carddemo_reporting;

-- WHY : Assumptions: no INSERT, UPDATE, DELETE or TRUNCATE is granted on any view
--       above, and the absence is stated as a REVOKE rather than left implicit. A
--       simple view over one table is AUTOMATICALLY UPDATABLE in PostgreSQL, so a
--       projection that reads as read-only would accept a write the moment the
--       privilege existed -- and six of the seven views here are simple enough to
--       qualify, every one except v_statement_transactions, whose join to the
--       grouping-key table disqualifies it. The reporting context writes nothing, so
--       this is
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
       reporting.v_transaction_categories,
       reporting.v_accounts,
       reporting.v_customers,
       reporting.v_card_xref
    FROM carddemo_reporting;

-- WHY : Assumptions: the role reset is explicit rather than left to transaction
--       end. SET LOCAL ROLE does end with the transaction, so this is belt and
--       braces -- but a later editor appending a statement after COMMIT would
--       otherwise run it under a role the file set and never mentioned again.
RESET ROLE;

COMMIT;
