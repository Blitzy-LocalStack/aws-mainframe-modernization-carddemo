-- =============================================================================
-- data-migration/sql/V0__schemas_and_roles.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Bootstraps the PostgreSQL objects that every other database artifact in the
--   migrated CardDemo stack presumes already exist: the eight bounded-context
--   schemas, one login role per bounded context, and the complete cross-schema
--   privilege graph. It must run against the target database BEFORE any
--   per-service migration, and it is safe to re-run.
--
--   Each service's V1__*.sql creates only tables inside a schema this script
--   already made, owned by a role this script already made, and issues no
--   CREATE SCHEMA, CREATE ROLE, GRANT or REVOKE of its own. A schema, a role or
--   a grant that is missing here is therefore missing from the whole system,
--   and shows up as a permission error in a running service rather than as a
--   build failure.
--
--   The eight bounded contexts, and the objects each one owns:
--     auth           auth.users
--     account        account.accounts, account.customers, account.card_xref
--     card           card.cards
--     ledger         ledger.transactions, ledger.daily_transactions,
--                    ledger.transaction_rejects,
--                    ledger.transaction_category_balances
--     reference      reference.transaction_types,
--                    reference.transaction_categories,
--                    reference.disclosure_groups,
--                    reference.us_phone_area_codes, reference.us_states,
--                    reference.us_state_zip_prefixes
--     batch          batch.batch_run plus the batch job-repository tables
--     authorization  authorization.pending_auth_summary,
--                    authorization.pending_auth_detail,
--                    authorization.auth_fraud
--     reporting      no table of its own; see the note at its CREATE SCHEMA
--
-- Session context (this script takes no parameters and no substitution
-- variables, and contains no psql meta-commands):
--   - The operator selects the target database when invoking the script. No
--     database name, host, port or connection string appears in this file.
--   - The connecting role must be able to create roles: either a true
--     superuser, or a role holding CREATEROLE such as the rds_superuser-class
--     master user of an Amazon Aurora PostgreSQL cluster.
--   - The server must be PostgreSQL 16 or newer. Section 1 asserts this rather
--     than assuming it silently.
--   - No credential is established here. Each role is created able to log in
--     but with no password, so this artifact carries nothing that can
--     authenticate.
--
-- Post-state established:
--   - Eight schemas exist -- auth, account, card, ledger, reference, batch,
--     authorization, reporting -- each owned by its matching carddemo_* role.
--   - Eight login roles exist -- carddemo_auth, carddemo_account,
--     carddemo_card, carddemo_ledger, carddemo_reference, carddemo_batch,
--     carddemo_authorization, carddemo_reporting -- every one of them without
--     a password. These names are the source of truth: the datasource username
--     each service resolves, and the secret store entry each credential is
--     written to, must match them character for character.
--   - CREATE on schema public is revoked from PUBLIC.
--   - carddemo_batch holds USAGE on ledger, account, card and reference, and
--     default privileges that grant it SELECT/INSERT/UPDATE on ledger tables,
--     USAGE/SELECT on ledger sequences, SELECT/UPDATE on account tables, and
--     SELECT on card and reference tables.
--   - carddemo_reporting holds USAGE on those same four schemas, and default
--     privileges granting SELECT and nothing else on their tables.
--   - No table, index, view, constraint or seed row is created.
--
-- Fails when:
--   - The connecting role cannot create roles. CREATE ROLE reports
--     "permission denied to create role", detailing that only roles with the
--     CREATEROLE attribute may create roles.
--   - The eight roles already exist and the connecting role is neither a
--     superuser nor an administrator of them -- typically because a different
--     principal created them. Section 1's membership grant reports "permission
--     denied to grant role", detailing that only roles with the ADMIN option
--     may grant it. That refusal is correct rather than incidental: a principal
--     that does not administer these roles has no business configuring the
--     default privileges of the schemas they own. Resolve it by running the
--     bootstrap as the principal that owns the roles, not by loosening this
--     script.
--   - Section 1's membership grant is removed or reordered away, so the session
--     can create roles but cannot act for the schema owners. Section 2's
--     CREATE SCHEMA ... AUTHORIZATION then reports "must be able to SET ROLE"
--     and section 4's ALTER DEFAULT PRIVILEGES reports "permission denied to
--     change default privileges"; see that grant's comment for why both happen.
--   - The server predates PostgreSQL 16. Section 1 raises with an explicit
--     message naming the reported version.
--   - The schema name authorization is left unquoted at any SQL occurrence. The
--     parser reads it as the AUTHORIZATION keyword and reports a syntax error.
--   - It is run after a per-service migration has already created tables under
--     a different owner. Nothing errors, but section 4's and section 5's
--     default privileges are keyed on the owning role and so never reach those
--     pre-existing tables; the ON ALL TABLES statements alongside them exist to
--     repair exactly that case.
--   Every one of these cases that raises rolls the entire script back, leaving
--   no half-built bootstrap behind. The last case is the exception that proves
--   why the repair statements are there: it raises nothing at all, so only the
--   ON ALL TABLES form can reach the tables it left behind.
--
-- Derivation:
--   The eight contexts partition the baseline data that app/csd/CARDDEMO.CSD
--   exposes to one CICS region as eight DEFINE FILE stanzas -- ACCTDAT at L1,
--   CARDAIX at L13, CARDDAT at L25, CCXREF at L37, CUSTDAT at L50, CXACAIX at
--   L63, TRANSACT at L76 and USRSEC at L88 -- together with the Db2 and IMS
--   objects of the three extension trees. CARDAIX and CXACAIX name
--   VSAM.AIX.PATH datasets rather than base clusters, so they resolve to
--   secondary indexes inside card and account, not to schemas or tables of
--   their own. The record layouts that fix each table's columns live in app/cpy
--   (CSUSR01Y, CVACT01Y, CVACT02Y, CVACT03Y, CVCUS01Y and CVTRA01Y through
--   CVTRA06Y) and in the extension trees' copybooks and DDL; those columns are
--   declared by the per-service migrations, not here. Everything under app/**
--   is read as reference only and is never modified.
--
-- WHY (non-obvious design decisions):
--   - Trade-offs: the file is pure SQL -- no backslash meta-command, no
--     colon-prefixed variable substitution. That gives up conveniences such as
--     meta-command progress output, and buys one artifact that runs unchanged
--     both under "psql -v ON_ERROR_STOP=1 -f" and through a driver cursor in
--     data-migration/src/carddemo_migration.
--   - Assumptions: the script is sent as a literal script with NO bound
--     parameters. Section 1 contains format('%I', ...), and a driver asked to
--     interpolate parameters would try to consume that %I as one of its own
--     placeholders, then either fail or rewrite the statement.
--   - Alternatives Considered: setting a search_path here -- on the roles or in
--     the session -- was rejected. Each service already pins its schema in its
--     own DataSourceConfig and in its Flyway default-schema, and a setting with
--     two owners is a setting that drifts: a role-level search_path would
--     silently override what a service configured for itself.
--   - Refactoring Rationale: the baseline has no boundary to refine. Every file
--     stanza in the CICS resource definitions is declared with no recovery, no
--     journalling and uncommitted-read isolation, and one region holds every
--     capability on every file, so there is no privilege separation to port --
--     it has to be introduced. Sections 2, 4 and 5 are where that happens, and
--     each carries the specific baseline evidence it departs from.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Scope of this script
--
-- WHY (Assumptions): the V0__ prefix orders this file ahead of every V1__ for a
-- human operator reading the tree. It does NOT make the file a Flyway-managed
-- migration. Each service owns an independent Flyway history that starts at V1,
-- and this file sits on no service's Flyway classpath. Placing it on one would
-- record it in exactly that service's schema history and in none of the other
-- seven, so the identical bootstrap would read as applied from one service and
-- as pending from the rest.
--
-- WHY (Assumptions): this script creates no table, index, view, constraint or
-- seed row, so every grant below names a schema and never a table. Tables
-- belong to the owning service's V1__*.sql; the reference seed rows, including
-- the mandatory DEFAULT disclosure group, belong to reference-service's
-- V2__seed_reference.sql; and index building has no analogue to carry across at
-- all, because PostgreSQL maintains indexes transactionally. The two schema
-- constraints that do encode a preserved behavioural contract -- the
-- restrict-on-removal foreign key from transaction categories to transaction
-- types (app/app-transaction-type-db2/ddl/TRNTYCAT.ddl) and the descending
-- fraud index (app/app-authorization-ims-db2-mq/ddl/XAUTHFRD.ddl) -- are
-- declared and justified in V1__reference.sql and V1__authorization.sql
-- respectively, where the tables they attach to are created.
-- -----------------------------------------------------------------------------


-- WHY (Trade-offs): the entire script is one explicit transaction. CREATE ROLE,
-- CREATE SCHEMA, GRANT, REVOKE and ALTER DEFAULT PRIVILEGES are all
-- transactional in PostgreSQL, so an interrupted run leaves nothing behind
-- instead of leaving the eight-schema, eight-role, full-grant contract
-- half-true -- and a half-true bootstrap is strictly worse than a clean
-- failure, because the missing half surfaces as a permission error inside a
-- running service rather than as an error here. The alternative was to rely on
-- the invoker passing "psql --single-transaction"; that was rejected because it
-- makes atomicity a property of how the file happens to be called instead of a
-- property of the file. One cost is accepted: a driver session already inside a
-- transaction emits a "there is already a transaction in progress" warning at
-- this BEGIN. The warning is harmless and the run still commits exactly once.
BEGIN;


-- =============================================================================
-- 1. Roles
--
-- WHY (Assumptions): the three groups of statements in this file are ordered by
-- dependency, not by importance. Roles come first because
-- CREATE SCHEMA ... AUTHORIZATION <role> resolves the owner as it executes;
-- grants come last because ALTER DEFAULT PRIVILEGES ... IN SCHEMA <schema>
-- resolves the schema as it executes. Any other order fails on a fresh
-- database.
-- =============================================================================

-- WHY (Assumptions): PostgreSQL offers CREATE SCHEMA IF NOT EXISTS but has no
-- CREATE ROLE IF NOT EXISTS, and roles are cluster-wide rather than
-- per-database. A guarded block is therefore the only way to make role creation
-- re-runnable. A bare CREATE ROLE aborts the second run with "role already
-- exists", and because the whole script is one transaction that abort would
-- roll back every grant with it -- leaving the roles from the first run in
-- place holding no privileges at all, which is the hardest state to diagnose.
--
-- WHY (Assumptions): format('%I') applies identifier quoting to the name it
-- interpolates, which is what makes a dynamically built CREATE ROLE both
-- injection-safe and correct for any name a future bounded context introduces.
-- It is also the reason this script must never be sent to a driver alongside a
-- parameter sequence, as the header notes.
DO $$
DECLARE
    -- WHY (Assumptions): this array is the single source of truth for the role
    -- names. The datasource username each service resolves, and the secret store
    -- entry each credential is written to, must match it character for
    -- character. The carddemo_ prefix is not decoration either: PostgreSQL
    -- reserves the pg_ role-name prefix for its predefined roles and Amazon RDS
    -- reserves rds_ for its managed ones, so a namespaced prefix is what
    -- guarantees these eight names can never collide with a built-in or a
    -- managed role on the cluster.
    service_role  text;
    service_roles text[] := ARRAY[
        'carddemo_auth',
        'carddemo_account',
        'carddemo_card',
        'carddemo_ledger',
        'carddemo_reference',
        'carddemo_batch',
        'carddemo_authorization',
        'carddemo_reporting'
    ];
BEGIN
    -- WHY (Assumptions): three constructs below exist only from PostgreSQL 16 --
    -- the INHERIT and SET membership options, the SET privilege type accepted by
    -- pg_has_role, and the createrole_self_grant behaviour the membership grant
    -- compensates for. On an older server they fail as a syntax error or an
    -- unrecognised privilege type from inside a dynamic statement, which reports
    -- a cause unrelated to the actual problem. Asserting the version here turns
    -- that into one sentence naming exactly what is wrong. The floor is not
    -- arbitrary either: the managed cluster this bootstraps runs a recent
    -- engine version because serverless capacity scaling to zero requires one.
    IF current_setting('server_version_num')::int < 160000 THEN
        RAISE EXCEPTION
            'CardDemo schema bootstrap requires PostgreSQL 16 or newer, '
            'but this server reports %',
            current_setting('server_version');
    END IF;

    FOREACH service_role IN ARRAY service_roles
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = service_role) THEN
            -- WHY (Assumptions): every service connects as its own role, so each
            -- role needs LOGIN. No password clause is written, and none may be:
            -- a LOGIN role with no stored secret cannot authenticate under
            -- scram-sha-256 with transport encryption enforced, so this artifact
            -- carries no usable credential at all. That is what makes "no
            -- secrets committed to the repository" a structural property of the
            -- file rather than a claim about it. The credential is generated at
            -- provisioning time into the managed secret store and applied by an
            -- out-of-band ALTER ROLE ... PASSWORD that never appears in source.
            --
            -- WHY (Alternatives Considered): a two-tier scheme -- NOLOGIN group
            -- roles holding the privileges, plus separately created LOGIN users
            -- granted into them -- was rejected. The target design specifies one
            -- database role per bounded context; a second tier would double the
            -- object count and double the number of places a privilege can be
            -- granted, and across eight contexts it buys no additional
            -- separation, because each group would have exactly one member.
            EXECUTE format('CREATE ROLE %I LOGIN', service_role);
        END IF;

        -- WHY (Assumptions): the bootstrap session must be able to act for each
        -- owner role, and merely creating the role does not confer that. From
        -- PostgreSQL 16 a CREATEROLE role that creates a role is auto-granted
        -- the new role WITH ADMIN OPTION only: createrole_self_grant defaults to
        -- the empty string, so the resulting membership carries
        -- inherit_option = false and set_option = false. Measured on PostgreSQL
        -- 17.10 with that membership alone, section 2's
        -- CREATE SCHEMA ... AUTHORIZATION fails with "must be able to SET ROLE"
        -- and section 4's ALTER DEFAULT PRIVILEGES fails with "permission denied
        -- to change default privileges". Re-granting the membership WITH INHERIT
        -- TRUE, SET TRUE supplies exactly the two options those two checks read.
        -- The grant is idempotent, it leaves the auto-granted ADMIN OPTION
        -- intact -- which the out-of-band password step still needs -- and the
        -- guard skips it entirely when the connecting role is a superuser,
        -- because pg_has_role reports true for a superuser on every mode. It is
        -- no escalation either: a role able to create these roles can already
        -- administer them.
        IF NOT pg_has_role(CURRENT_USER, service_role, 'USAGE')
           OR NOT pg_has_role(CURRENT_USER, service_role, 'SET') THEN
            EXECUTE format(
                'GRANT %I TO CURRENT_USER WITH INHERIT TRUE, SET TRUE',
                service_role);
        END IF;
    END LOOP;
END
$$;


-- =============================================================================
-- 2. Schemas
--
-- WHY (Refactoring Rationale): the baseline draws no data boundary at all.
-- All eight DEFINE FILE stanzas in app/csd/CARDDEMO.CSD -- beginning at L1,
-- L13, L25, L37, L50, L63, L76 and L88 -- are declared RECOVERY(NONE),
-- JOURNAL(NO), JNLREAD(NONE), JNLUPDATE(NO), JNLADD(NONE), FWDRECOVLOG(NO) and
-- READINTEG(UNCOMMITTED), and every one of them opens with READ(YES),
-- UPDATE(YES), ADD(YES) and BROWSE(YES) to the single CICS region: no recovery,
-- no journalling, uncommitted-read isolation, and no privilege separation
-- whatsoever, because one region reached every record. Partitioning the data
-- into one schema per bounded context, each owned by a distinct role, is what
-- converts "every program can reach every record" into a boundary the database
-- enforces rather than one the code is trusted to respect.
--
-- The two extension trees likewise place all of their Db2 objects in a single
-- schema named CARDDEMO (app/app-authorization-ims-db2-mq/ddl/AUTHFRDS.ddl L1
-- and app/app-transaction-type-db2/ddl/TRNTYPE.ddl L1), which the target
-- deliberately splits across authorization and reference -- so the baseline
-- schema name cannot simply be mirrored, and the split has to be declared.
--
-- WHY (Trade-offs): each CREATE SCHEMA IF NOT EXISTS is followed by an
-- ALTER SCHEMA ... OWNER TO naming the same role. IF NOT EXISTS skips the whole
-- statement when the schema is already present -- the AUTHORIZATION clause
-- included -- so a schema that an earlier run or a manual step created under a
-- different owner would silently keep that owner. The per-service migration
-- would then create its tables owned by the wrong role, and because sections 4
-- and 5 key their default privileges on the owning role, those privileges would
-- never fire for any of those tables. The cost is one redundant statement per
-- schema on a first run, which is cheap next to a privilege graph that looks
-- correct in this file and is inert in the database. The alternative was to drop
-- IF NOT EXISTS and accept that any re-run aborts.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS auth AUTHORIZATION carddemo_auth;
ALTER SCHEMA auth OWNER TO carddemo_auth;

CREATE SCHEMA IF NOT EXISTS account AUTHORIZATION carddemo_account;
ALTER SCHEMA account OWNER TO carddemo_account;

CREATE SCHEMA IF NOT EXISTS card AUTHORIZATION carddemo_card;
ALTER SCHEMA card OWNER TO carddemo_card;

CREATE SCHEMA IF NOT EXISTS ledger AUTHORIZATION carddemo_ledger;
ALTER SCHEMA ledger OWNER TO carddemo_ledger;

CREATE SCHEMA IF NOT EXISTS reference AUTHORIZATION carddemo_reference;
ALTER SCHEMA reference OWNER TO carddemo_reference;

CREATE SCHEMA IF NOT EXISTS batch AUTHORIZATION carddemo_batch;
ALTER SCHEMA batch OWNER TO carddemo_batch;

-- WHY (Assumptions): authorization is a PostgreSQL reserved keyword, and
-- CREATE SCHEMA AUTHORIZATION <role> is itself valid syntax that names a schema
-- after a role. Written bare, "CREATE SCHEMA IF NOT EXISTS authorization;" is
-- read as that keyword form, the parser then finds no role name, and it reports
-- only "syntax error at or near ";"" -- a message that points nowhere near the
-- actual cause. Double quoting is therefore mandatory at EVERY SQL occurrence of
-- this schema name: here, in the ALTER SCHEMA below, in GRANT ... ON SCHEMA, in
-- ALTER DEFAULT PRIVILEGES ... IN SCHEMA, and in any hand-written SQL or
-- search_path elsewhere in the codebase that names it.
--
-- The quoting does not change the resulting name. PostgreSQL folds an unquoted
-- identifier to lower case and "authorization" is already lower case, so
-- pg_namespace.nspname is exactly authorization -- which is what
-- authorization-service's Flyway schemas and default-schema properties and its
-- JPA schema attributes expect. Those are configuration strings, never parsed
-- by the PostgreSQL grammar in a bare-identifier position, so they need no
-- quoting of their own. Renaming the schema to dodge the quoting was not an
-- option: sibling artifacts have already fixed the name authorization.
--
-- The other seven names are not keywords and are deliberately left unquoted;
-- quoting them gratuitously would invite a mixed-case name that then has to be
-- quoted forever.
CREATE SCHEMA IF NOT EXISTS "authorization" AUTHORIZATION carddemo_authorization;
ALTER SCHEMA "authorization" OWNER TO carddemo_authorization;

-- WHY (Assumptions): reporting is the eighth schema and it holds no table. The
-- eight bounded contexts are what the count refers to; seven of them own tables,
-- and reporting owns none because the reporting context reads other contexts'
-- data through the SELECT-only grants in section 5 rather than storing any of
-- its own. The schema still exists, for two reasons: it gives that context a
-- home for the read-only cross-schema views it reads through, and it makes the
-- eight-schema post-state that every downstream artifact asserts literally true
-- rather than seven-plus-a-footnote.
--
-- No view is created here, and its absence is deliberate rather than an
-- oversight: a view over ledger.transactions cannot be created when the
-- underlying table does not exist, and at the point this script runs no table
-- exists anywhere. reporting-service also owns no migration directory at all --
-- its pom.xml records that a db/migration directory in that module would be an
-- affirmative defect -- so nothing inside this schema is created by a migration
-- either. The views therefore belong to data-migration, but necessarily to a
-- step ordered after the per-service migrations have created the tables they
-- read, not to this one. What this script does establish for that context is the
-- schema they live in and the SELECT-only grants in section 5 they read through,
-- and a view missing at run time is a data-migration defect to report rather
-- than something for a service to create for itself.
CREATE SCHEMA IF NOT EXISTS reporting AUTHORIZATION carddemo_reporting;
ALTER SCHEMA reporting OWNER TO carddemo_reporting;


-- WHY (Alternatives Considered): no search_path is set for any of the eight
-- roles, and this is the point in the file where adding one is most tempting --
-- the schemas have just been created and each has exactly one natural default.
-- It was rejected because each service already pins its schema twice, in its own
-- DataSourceConfig and in its Flyway default-schema property, and a role-level
-- ALTER ROLE ... SET search_path would quietly take precedence over what the
-- service configured for itself. One setting with two owners drifts, and the
-- drift is invisible until a query resolves an unqualified name in the wrong
-- schema. Leaving the resolution wholly to the connecting service keeps a single
-- owner for it.


-- =============================================================================
-- 3. Public-schema hardening
-- =============================================================================

-- WHY (Assumptions): PostgreSQL 15 and newer already revoke this by default, so
-- on a current server this statement changes nothing. It is issued anyway, and
-- unconditionally, so that the posture does not depend on which major version
-- the cluster happens to run or on whether some earlier operator granted it
-- back: after this script, CREATE on public is not available to PUBLIC, full
-- stop. The statement is idempotent, so re-running costs nothing.
--
-- The public schema itself is deliberately NOT dropped. Extensions install into
-- it by default, and dropping it is a change with consequences well outside this
-- script's mandate, which is schemas, roles and grants.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;


-- =============================================================================
-- 4. Cross-schema privileges for the batch role
--
-- This section is the ONE documented exception to database-per-service purity in
-- the whole design, so it carries the most reasoning.
--
-- WHY (Assumptions): the nightly posting job commits three writes as a single
-- unit of work. In app/cbl/CBTRN02C.cbl the paragraph 2000-POST-TRANSACTION. at
-- L424 performs, in order, 2700-UPDATE-TCATBAL at L440 (the transaction category
-- balance, created at L510 or updated at L528), 2800-UPDATE-ACCOUNT-REC at L441
-- (the account master, updated at L554) and 2900-WRITE-TRANSACTION-FILE at L442
-- (the posted transaction, written at L564). Two of those three records land in
-- ledger and one in account. Granting the batch role narrowly scoped write
-- access across both schemas is what keeps that commit a single ACID
-- transaction, exactly as the baseline has it.
--
-- WHY (Alternatives Considered): a transactional-outbox-plus-compensating-
-- reversal design was considered and rejected, because it would introduce
-- observable partial-posting states that do not exist in the baseline, which
-- would break golden-master parity outright. A saga over per-service databases
-- loses for the same concrete reason: it replaces one atomic commit with a
-- sequence of committed steps plus reversals, so a posted transaction with an
-- unposted category balance becomes a state a reader can observe. The golden
-- masters would correctly flag that as a parity failure, and they would be
-- right to. Keeping one database and narrowing the grant is the lower-risk
-- option, and it is the only one that preserves the unit of work.
--
-- WHY (Trade-offs): the privileges are expressed as schema-level USAGE plus
-- ALTER DEFAULT PRIVILEGES, not as table-level grants. When this script runs, no
-- table exists anywhere, so a bare GRANT ... ON ALL TABLES would grant on the
-- zero tables present at that instant and have no effect whatsoever on the
-- tables the per-service migrations create afterwards -- a statement that
-- executes cleanly, reads as correct, and does nothing. Table-level grants
-- issued from a per-service migration were the obvious alternative, and they
-- were rejected twice over: every per-service V1__*.sql is contractually
-- forbidden from issuing GRANT, and a grant living inside one service's
-- migration could not cover a table that a different service adds to its own
-- schema. The accepted cost of default privileges is that they are keyed on the
-- creating role, which is what makes the FOR ROLE clause below load-bearing.
--
-- WHY (Assumptions): ALTER DEFAULT PRIVILEGES FOR ROLE <owner> applies only to
-- objects created after it runs and only to objects created BY that role. Each
-- FOR ROLE names the schema's owning role because each V1__*.sql runs as that
-- role. Omitting FOR ROLE would silently default to the current role -- the
-- bootstrap operator -- and produce default-privilege rows that are recorded,
-- visible in pg_default_acl, and never fire for a single table. The
-- GRANT ... ON ALL TABLES statements paired with each block are a no-op on a
-- first run and are present only to repair a database where tables already
-- exist; the ALTER DEFAULT PRIVILEGES statements are what actually carry the
-- contract.
--
-- WHY (Assumptions): DELETE and TRUNCATE are withheld from every schema, and
-- that is genuine least privilege rather than a gesture. No batch program performs
-- either: grepping the DELETE verb across app/cbl/CB*.cbl returns nothing, and
-- the file declarations of the three programs that drive the nightly chain show
-- why -- CBTRN02C L29-L57 opens DALYTRAN, TRANFILE, XREFFILE, DALYREJS,
-- ACCTFILE and TCATBALF and only ever writes (L451, L510, L564) or rewrites
-- (L528, L554); CBACT04C L28-L53 opens TCATBALF, XREFFILE, ACCTFILE, DISCGRP
-- and TRANSACT and only rewrites the account (L356) and writes a transaction
-- (L500); CBTRN01C L29-L58 opens DALYTRAN, CUSTFILE, XREFFILE, CARDFILE,
-- ACCTFILE and TRANFILE and writes nothing at all. The capability existed in the
-- baseline and was simply never used -- all eight CSD file stanzas are declared
-- DELETE(YES) -- so withholding it here removes a power no migrated job needs.
-- =============================================================================

-- WHY (Assumptions): USAGE on a schema is what makes its objects nameable at
-- all; without it a table-level privilege on an object inside the schema is
-- unreachable and the error names the schema, not the table. The four schemas
-- are exactly the ones the three nightly programs open: ledger and account for
-- posting and interest, reference because CBACT04C L47 opens DISCGRP for the
-- disclosure-group rate, and card because CBTRN01C L46 opens CARDFILE while
-- validating the daily file.
GRANT USAGE ON SCHEMA ledger, account, card, reference TO carddemo_batch;

-- WHY (Assumptions): read, insert and update on ledger, and nothing beyond
-- them. The posting job writes the posted transaction (app/cbl/CBTRN02C.cbl
-- L564) and the reject stream (L451), and both creates (L510) and updates
-- (L528) the category balance; the interest job writes its generated
-- transaction (app/cbl/CBACT04C.cbl L500). Insert and update are each demanded
-- by a specific write site, so neither is speculative headroom.
ALTER DEFAULT PRIVILEGES FOR ROLE carddemo_ledger IN SCHEMA ledger
    GRANT SELECT, INSERT, UPDATE ON TABLES TO carddemo_batch;

-- WHY (Assumptions): a sequence grant is required in addition to the table
-- grant, and it is the single easiest privilege in this file to omit. An INSERT
-- into a table whose key is an identity or serial column also consumes the
-- backing sequence, which needs USAGE on that sequence in its own right.
-- Omitting it produces no error when the grants are applied and no error when
-- the table is read -- it fails only at the moment a row is inserted, inside the
-- nightly batch, which is the worst possible place to discover it. Only ledger
-- needs this, because ledger holds the only tables the batch role inserts into.
ALTER DEFAULT PRIVILEGES FOR ROLE carddemo_ledger IN SCHEMA ledger
    GRANT USAGE, SELECT ON SEQUENCES TO carddemo_batch;

GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA ledger TO carddemo_batch;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ledger TO carddemo_batch;

-- WHY (Assumptions): read and update on account, deliberately without insert.
-- Posting and interest each rewrite an account master that already exists
-- (app/cbl/CBTRN02C.cbl L554, app/cbl/CBACT04C.cbl L356), and no batch program
-- creates an account, a customer or a cross-reference row -- those originate in
-- account-service. Granting insert here would hand the nightly chain authority
-- over records it never produces.
ALTER DEFAULT PRIVILEGES FOR ROLE carddemo_account IN SCHEMA account
    GRANT SELECT, UPDATE ON TABLES TO carddemo_batch;

GRANT SELECT, UPDATE ON ALL TABLES IN SCHEMA account TO carddemo_batch;

-- WHY (Assumptions): read-only on card. app/cbl/CBTRN01C.cbl opens CARDFILE at
-- L46 to validate the daily file against the card master and declares no write
-- verb at all, so any write privilege here would exceed what every batch step
-- put together performs.
ALTER DEFAULT PRIVILEGES FOR ROLE carddemo_card IN SCHEMA card
    GRANT SELECT ON TABLES TO carddemo_batch;

GRANT SELECT ON ALL TABLES IN SCHEMA card TO carddemo_batch;

-- WHY (Assumptions): read-only on reference. app/cbl/CBACT04C.cbl opens DISCGRP
-- at L47 to look up the disclosure-group interest rate and never writes to it.
-- Reference data is maintained through reference-service and seeded by its own
-- migration, never by the nightly chain.
ALTER DEFAULT PRIVILEGES FOR ROLE carddemo_reference IN SCHEMA reference
    GRANT SELECT ON TABLES TO carddemo_batch;

GRANT SELECT ON ALL TABLES IN SCHEMA reference TO carddemo_batch;


-- =============================================================================
-- 5. Cross-schema privileges for the reporting role
--
-- WHY (Assumptions): reporting reads and never writes, so it receives SELECT and
-- nothing else, on four schemas and no others. Three of the four are grounded in
-- a direct baseline read. app/cbl/CBTRN03C.cbl opens TRANFILE at L29 (ledger),
-- CARDXREF at L33 (account) and TRANTYPE and TRANCATG at L39 and L45
-- (reference), and writes only its report output. app/cbl/CBSTM03B.CBL -- the
-- data half of the statement pair, which app/cbl/CBSTM03A.CBL calls because
-- CBSTM03A itself declares only its two output files -- opens TRNXFILE at L31
-- (ledger) plus XREFFILE, CUSTFILE and ACCTFILE at L37, L43 and L49 (account),
-- and declares no write verb at all. No insert and no update anywhere: reporting
-- owns no table, so a write privilege would hand it authority over data another
-- context is responsible for.
--
-- WHY (Assumptions): card is the one grant here NOT backed by a baseline read,
-- and saying so is the point of this comment. No reporting program opens the
-- card master: the baseline statement carries only the transaction fields of
-- app/cpy/COSTM01.CPY and takes its card number from the cross-reference. It is
-- granted because the reporting context's remit is read-only presentation across
-- the record data, and the cross-schema views it reads through project card
-- attributes such as status and the masked number rather than re-deriving them.
-- A reader auditing least privilege should be able to tell which grants a
-- program demands and which one the target design does, and this is the only
-- grant in the file in the second category.
--
-- WHY (Alternatives Considered): pointing reporting at a read replica was
-- considered and rejected. A replica adds standing cost and introduces
-- replica-lag semantics -- a report that legitimately disagrees with the writer
-- about what has been posted -- for no parity benefit, since the baseline reads
-- the same records the batch chain has just written. Read replicas are out of
-- scope for that reason, and reporting reads the writer through these grants
-- instead.
--
-- Beyond sections 4 and 5 there is no cross-schema grant anywhere: no other
-- service role may read or write another context's schema. A caller needing
-- another context's data goes through that context's REST API, not through the
-- database.
-- =============================================================================

GRANT USAGE ON SCHEMA ledger, account, card, reference TO carddemo_reporting;

ALTER DEFAULT PRIVILEGES FOR ROLE carddemo_ledger IN SCHEMA ledger
    GRANT SELECT ON TABLES TO carddemo_reporting;

GRANT SELECT ON ALL TABLES IN SCHEMA ledger TO carddemo_reporting;

ALTER DEFAULT PRIVILEGES FOR ROLE carddemo_account IN SCHEMA account
    GRANT SELECT ON TABLES TO carddemo_reporting;

GRANT SELECT ON ALL TABLES IN SCHEMA account TO carddemo_reporting;

ALTER DEFAULT PRIVILEGES FOR ROLE carddemo_card IN SCHEMA card
    GRANT SELECT ON TABLES TO carddemo_reporting;

GRANT SELECT ON ALL TABLES IN SCHEMA card TO carddemo_reporting;

ALTER DEFAULT PRIVILEGES FOR ROLE carddemo_reference IN SCHEMA reference
    GRANT SELECT ON TABLES TO carddemo_reporting;

GRANT SELECT ON ALL TABLES IN SCHEMA reference TO carddemo_reporting;


COMMIT;
