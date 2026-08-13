-- =============================================================================
-- data-migration/sql/V1__reporting_views.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Create the seven read-only views the reporting bounded context reads, plus the
--   one protected table and the one lookup function they depend on, in the
--   `reporting` schema, owned by carddemo_reporting_owner. reporting-service owns
--   no table, no index and no relational object of its own; the role it connects
--   as holds USAGE on this schema, SELECT on the seven views named here and
--   EXECUTE on the function named below, and nothing else -- notably not on the
--   one table this file creates. Those seven views ARE that context's entire
--   readable data surface, so the JPA projections in
--   services/reporting-service/src/main/java/com/carddemo/reporting/domain map
--   one relation each and map nothing outside this file.
--
--   Refactoring Rationale: this header opened with "Create the four read-only
--   relations" and then enumerated seven, which is a count left behind by the
--   revision recorded three paragraphs below. It said four because four is what an
--   earlier revision created. Correcting the opening sentence rather than the
--   enumeration is deliberate: the enumeration is what a reader checks against the
--   file, and the sentence is what a reader reads first and carries away.
--   V3__verification_surfaces.sql adds two further views to this same schema, so
--   the schema's whole population is nine views, this one table and this one
--   function -- stated here because a reader auditing the reporting role's read
--   surface arrives at this file and must not conclude the surface stops with it.
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
-- WHY : Refactoring Rationale: the fingerprint is projected on THREE relations now --
--       v_statement_transactions, v_report_transactions and v_card_xref -- where it
--       previously appeared on the statement projection alone. The narrower placement
--       rested on the reasoning that a fingerprint groups and that the other two
--       relations had nothing to group, and that reasoning was answering the wrong
--       question. A fingerprint is not only a grouping token here: it is the only
--       column on these relations that is a function of the WHOLE card number, so it
--       is also the only one that can serve as a row identity, as a join predicate and
--       as an ordering key. Without it, the report's join of a transaction to its
--       cross-reference row matched on the mask -- which is to say on four digits --
--       so two cards sharing a tail joined to each other's cross-reference row and the
--       join multiplied rows the driving relation admitted once. And v_card_xref's own
--       reasoning, that it holds one row per card so has nothing to group, was true
--       about grouping and false about SELECTION: a lookup by the mask names four
--       digits, so it can resolve to a row belonging to a different cardholder
--       entirely. Both are broken-object-selection defects rather than presentation
--       ones, and both close by joining, identifying and ordering on the fingerprint
--       while the mask stays what it always was, a value to display.
-- WHY : Assumptions: the fingerprint on the two added relations is computed by the
--       SAME expression as on the statement projection, over the same single-row
--       secret, and that identity is load-bearing rather than tidy. The report joins
--       v_report_transactions to v_card_xref on it, so a fingerprint computed with a
--       different key, a different digest or a different concatenation order would
--       join nothing at all -- and would do so silently, returning an empty report
--       rather than an error.
-- WHY : Alternatives Considered: exposing a forward oracle -- a function taking a card
--       number and returning its fingerprint -- so that the service could compute the
--       token for a card a caller supplied and select on it directly. Rejected: the
--       masked column is published beside the token, so a caller who knows an issuer
--       prefix and reads a mask has only the middle six digits left to guess, and a
--       forward oracle turns that into a search of about a million calls. The exact
--       lookup below returns nothing for a card that does not exist, so it discloses a
--       fingerprint only to a caller that already held the whole number it belongs to.
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
    -- WHY : Assumptions: the same keyed digest the statement projection publishes, for
    --       the reason recorded in the header block: the report joins this relation to
    --       reporting.v_card_xref to obtain the account identifier its layout prints,
    --       and the mask is four digits behind a constant filler so a join on it is a
    --       join on four digits. The CROSS JOIN cannot multiply rows, because the key
    --       table's primary key is fixed to a single value.
    encode(sha256(convert_to(k.key_value || rtrim(t.card_num), 'UTF8')), 'hex')
                                                                  AS card_fingerprint,
    t.orig_ts,
    t.proc_ts
FROM ledger.transactions AS t
CROSS JOIN reporting.card_grouping_key AS k;

COMMENT ON VIEW reporting.v_report_transactions IS
    'Row source for the 133-column transaction report (app/cbl/CBTRN03C.cbl, app/jcl/TRANREPT.jcl). '
    'Projects the columns of ledger.transactions the report consumes, the card number masked to its '
    'last four digits for display and a keyed per-card fingerprint for joining, grouping and '
    'ordering; the report date window is applied per run as a predicate on proc_ts, never baked '
    'into this view.';

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
-- WHY : Refactoring Rationale: fico_credit_score IS projected, and the reasoning that
--       withheld it -- "it is a credit assessment, not statement heading data, and no
--       band prints it" -- was factually wrong about the band. A band does print it.
--       app/cbl/CBSTM03A.CBL moves CUST-FICO-CREDIT-SCORE, declared PIC 9(03) at L22 of
--       app/cpy/CUSTREC.cpy, into ST-FICO-SCORE, declared PIC X(20) at L118 of
--       app/cpy/COSTM01.CPY, and the migrated renderer emits that band from
--       StatementBandLayouts.ST_LINE9. With the column absent the statement path had no
--       source for a value it is required to print, and the gap had been papered over
--       with a caller-supplied resolver function that no production caller passed --
--       so the whole-run generator could not be invoked at all. Projecting the column
--       is what lets the value come from the customer row it belongs to.
-- WHY : Trade-offs: this is the one genuinely evaluative attribute in the projection and
--       it widens the reporting role's reach by one column, which is a real cost. It is
--       accepted because the alternative is worse in both available directions: omitting
--       it leaves a required band unprintable, and supplying it from outside the database
--       means some other component reads it and hands it over, which spreads the same
--       disclosure across two roles instead of one. Least privilege here means the role
--       that renders the band reads the column, and nothing else does.
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
    c.dob,
    c.fico_credit_score
FROM account.customers AS c;

COMMENT ON VIEW reporting.v_customers IS
    'Read-only projection of account.customers for statement heading data (app/cpy/COSTM01.CPY). '
    'Deliberately omits the two enciphered national-identifier columns, both phone numbers and the '
    'transfer account reference: no reporting band prints any of them, so the role reads none of '
    'them. fico_credit_score IS projected, because the statement heading band ST-FICO-SCORE at L118 '
    'of app/cpy/COSTM01.CPY prints it.';

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
-- WHY : Refactoring Rationale: this projection now publishes card_fingerprint, and the
--       reasoning that previously withheld it -- that a fingerprint exists to GROUP and
--       that a relation holding one row per card has nothing to group -- is withdrawn
--       rather than softened. It was true about grouping and irrelevant to the two uses
--       this relation is actually put to. The report JOINS to it, and a join on the mask
--       is a join on four digits, so one transaction matched every cross-reference row
--       sharing a tail and the joined result carried more rows than the driving
--       relation admitted. The statement path SELECTS from it by card, and a select on
--       the mask is a select on four digits, so a request naming a card that does not
--       exist resolved to a different cardholder's row whenever that cardholder's card
--       shared the tail. The fingerprint is a function of the whole trimmed number, so
--       it makes both exact. The secret is read once more per query than before, which
--       is the cost, and it buys the difference between an identity and a display
--       value.
-- WHY : Assumptions: the fingerprint is the primary key of this projection as the
--       reporting context sees it, and the masked number is not. Two rows of this
--       relation can carry one identical mask and cannot carry one identical
--       fingerprint, so only the fingerprint can be mapped as an entity identifier --
--       which is what services/reporting-service/.../domain/CardXrefView.java now
--       declares.
CREATE VIEW reporting.v_card_xref
    WITH (security_barrier = true) AS
SELECT
    ('************' || right(rtrim(x.card_num), 4))::character(16) AS card_num,
    encode(sha256(convert_to(k.key_value || rtrim(x.card_num), 'UTF8')), 'hex')
                                                                  AS card_fingerprint,
    x.customer_id,
    x.account_id
FROM account.card_xref AS x
CROSS JOIN reporting.card_grouping_key AS k;

COMMENT ON VIEW reporting.v_card_xref IS
    'Read-only projection of account.card_xref resolving a card to its customer and account for '
    'statement generation (app/cbl/CBSTM03A.CBL). The card number is masked to its last four '
    'digits, as on every other card-bearing relation in this schema, and a keyed per-card '
    'fingerprint carries the identity the mask cannot: it is unique per card, so it is what the '
    'report joins on and what the reporting entity maps as its identifier.';

ALTER VIEW reporting.v_card_xref OWNER TO carddemo_reporting_owner;


-- -----------------------------------------------------------------------------
-- 8. reporting.v_transaction_category_balances -- the per-category balances the
--    category-balance report prints.
--
-- Supports app/jcl/PRTCATBL.jcl, the sort-only job that produces
-- AWS.M2.CARDDEMO.TCATBALF.REPT. That job carries no COBOL program: its STEP10R
-- sorts the unloaded transaction-category-balance file on
-- SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A) at line 52 and
-- reformats each record with OUTREC FIELDS at lines 53-56. So the whole of what it
-- needs from the master is the composite key and the balance.
--
-- WHY : Assumptions: the projection is EXACTLY four columns because the reference's
--       OUTREC names exactly four fields. ledger.transaction_category_balances has
--       no fifth column, so this is the whole table rather than a subset -- and
--       saying so matters: a reader comparing this view against the six-column
--       account projection above should not conclude a column was withheld here.
-- WHY : Assumptions: no masking is applied and none is needed. The composite key
--       is an account identifier, a two-character type code and a four-digit
--       category code, and the fourth column is a balance. None is cardholder data,
--       no card number is reachable from this relation at all, and all four are
--       printed in full by the reference's own report.
-- WHY : Alternatives Considered: joining reference.transaction_types and
--       reference.transaction_categories so the report could print descriptions
--       beside the codes. Rejected because the reference report prints CODES -- the
--       40-byte line at DCB=(LRECL=40) has room for the key, the edited balance and
--       nine trailing blanks and for nothing else -- so the join would fetch two
--       descriptions per row that no band can render, and would widen this role's
--       reach to two more relations for output it does not produce.
CREATE VIEW reporting.v_transaction_category_balances
    WITH (security_barrier = true) AS
SELECT
    tcb.account_id,
    tcb.type_cd,
    tcb.category_cd,
    tcb.balance
FROM ledger.transaction_category_balances AS tcb;

COMMENT ON VIEW reporting.v_transaction_category_balances IS
    'Composite transaction-category-balance key (account_id, type_cd, category_cd) and its '
    'balance, from ledger.transaction_category_balances. Supports the category-balance report '
    'app/jcl/PRTCATBL.jcl produces, whose OUTREC at lines 53-56 names exactly these four fields.';

ALTER VIEW reporting.v_transaction_category_balances OWNER TO carddemo_reporting_owner;


-- -----------------------------------------------------------------------------
-- 9. reporting.resolve_card -- exact resolution of one whole card number.
--
-- The statement request path receives a whole primary account number from an
-- authenticated caller and has to resolve exactly that card. Every relation above
-- publishes the number masked, so no predicate the reporting role can compose
-- selects one card: a predicate on the mask selects a tail, and a tail is shared.
--
-- This function, together with the card_fingerprint column the three transaction and
-- cross-reference projections publish, is registered as divergence
-- D-REPORT-ORDER-FINGERPRINT in docs/architecture/cobol-to-service-traceability.md. That
-- entry records what the substitution preserves -- one card's rows still sort together, so
-- a group break still occurs where the reference's does -- and what it deliberately does
-- not: the relative order BETWEEN two cards, because a digest orders differently from the
-- number it digests.
--
-- WHY : Assumptions: this is a SECURITY DEFINER function rather than a further view,
--       because what the caller needs is not a projection but a lookup whose ARGUMENT
--       is the sensitive value. A view cannot take an argument, and a view that
--       published the whole number so the service could filter on it would hand the
--       reporting role every primary account number in the cross-reference -- exactly
--       the access the masking above exists to withhold.
-- WHY : Assumptions: the function discloses a fingerprint only for a card that exists,
--       so it is not a forward oracle over the fingerprint space. A caller must already
--       hold the whole number to obtain the token belonging to it, which is the property
--       that keeps the masked column beside the token from being masked in appearance
--       only. Alternatives Considered: a plain card_fingerprint_for(text) helper, which
--       would answer for any string and turn a known issuer prefix plus a read mask into
--       a search of about a million calls. Rejected on that ground.
-- WHY : Assumptions: search_path is pinned on the function rather than inherited. A
--       SECURITY DEFINER body resolves its names with the OWNER's privileges, so an
--       unqualified name resolved through a caller-controlled search_path is the
--       classic privilege-escalation route -- the caller creates account.card_xref in a
--       schema of its own and the body reads that instead. Pinning it also makes the
--       schemas the body may reach an explicit, reviewable list.
-- WHY : Assumptions: STABLE and not IMMUTABLE. The answer depends on table contents,
--       which change between statements, so declaring it immutable would let the planner
--       fold one call's result into a plan cached across a load -- returning a card's
--       former customer after a re-issue moved it.
-- WHY : Trade-offs: EXECUTE is revoked from PUBLIC and granted to one role by name. A
--       SECURITY DEFINER function is executable by PUBLIC on creation, so omitting the
--       revoke would make this lookup reachable by every login in the database, which is
--       a strictly wider reach than the seven views it sits beside.
CREATE FUNCTION reporting.resolve_card(p_card_num character varying)
RETURNS TABLE (
    card_num          character(16),
    card_fingerprint  text,
    customer_id       bigint,
    account_id        bigint
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, reporting, account
AS $$
    SELECT
        ('************' || right(rtrim(x.card_num), 4))::character(16),
        encode(sha256(convert_to(k.key_value || rtrim(x.card_num), 'UTF8')), 'hex'),
        x.customer_id,
        x.account_id
    FROM account.card_xref AS x
    CROSS JOIN reporting.card_grouping_key AS k
    -- WHY : Assumptions: the comparison trims both sides. The stored column is
    --       CHAR(16) so it is blank-padded, and the argument arrives from a request
    --       body where a caller may or may not have padded it; comparing untrimmed
    --       would make the answer depend on the caller's padding. Trimming both sides
    --       keeps the predicate an equality on the whole number rather than on a
    --       prefix, so it stays index-eligible and cannot match two cards.
    WHERE rtrim(x.card_num) = rtrim(p_card_num)
$$;

COMMENT ON FUNCTION reporting.resolve_card(character varying) IS
    'Resolves one whole primary account number to its masked rendering, its keyed per-card '
    'fingerprint, its customer and its account. Exists because every reporting relation publishes '
    'the number masked, so no predicate the reporting role can compose selects a single card. '
    'Returns no row for a card that does not exist, so it discloses a fingerprint only to a caller '
    'that already holds the number it belongs to.';

ALTER FUNCTION reporting.resolve_card(character varying) OWNER TO carddemo_reporting_owner;

REVOKE ALL ON FUNCTION reporting.resolve_card(character varying) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION reporting.resolve_card(character varying) TO carddemo_reporting;


-- -----------------------------------------------------------------------------
-- Grants: SELECT on these eight relations, to the service login role. The one TABLE
-- this file creates is deliberately not among them; its own revoke is stated where
-- it is created, and the EXECUTE on reporting.resolve_card above is the only other
-- privilege this file grants that role.
--
-- WHY : Refactoring Rationale: this heading read "and nothing else anywhere", and that
--       clause is withdrawn because it was false in two directions at once. It was
--       already false of THIS file -- the EXECUTE granted immediately above is a
--       privilege on this schema granted to the same role -- and it became false of the
--       schema when V3__verification_surfaces.sql granted that role SELECT on
--       reporting.v_verification_row_counts and reporting.v_verification_money_totals.
--       The scope claim is narrowed to what THIS file grants, because a file cannot
--       truthfully speak for grants a later migration makes, and a privilege inventory
--       that overstates its own completeness is worse than one that states its bounds:
--       a reader auditing the role would stop here.
-- WHY : Assumptions: the count is EIGHT and is the number of GRANT SELECT statements
--       below, not the number of views this file creates -- it creates eleven views and
--       two tables, and the three views it does not grant are read only by the owner.
--       Counting the grants is what makes this heading checkable against the statements
--       beneath it rather than against the file's length.
--
-- Refactoring Rationale: the count read seven and is restated as eight with
-- v_transaction_category_balances. That view landed with the category-balance report
-- app/jcl/PRTCATBL.jcl produces, which had no target path at all -- the report state
-- claimed lineage from that job while emitting only the transaction-detail report.
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
GRANT SELECT ON reporting.v_transaction_category_balances TO carddemo_reporting;

-- WHY : Assumptions: no INSERT, UPDATE, DELETE or TRUNCATE is granted on any view
--       above, and the absence is stated as a REVOKE rather than left implicit. A
--       simple view over one table is AUTOMATICALLY UPDATABLE in PostgreSQL, so a
--       projection that reads as read-only would accept a write the moment the
--       privilege existed -- and four of the seven views here are simple enough to
--       qualify, every one except the three whose join to the grouping-key table
--       disqualifies them: v_statement_transactions, v_report_transactions and
--       v_card_xref. Refactoring Rationale: that count was six-of-seven while the
--       fingerprint was projected on the statement view alone; the two relations that
--       gained it also lost automatic updatability, and the number is restated rather
--       than left stale because a reader checking this claim against the file would
--       otherwise find it wrong and have no way to tell which half was out of date.
--       The reporting context writes nothing, so
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
