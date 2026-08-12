-- =============================================================================
-- services/transaction-service/src/test/resources/db/testharness/
--   test-harness-account-schema.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Bootstraps the ONE foreign schema and the ONE foreign table that
--   transaction-service reads and writes outside its own `ledger` schema:
--   `account.accounts`, whose `curr_bal` column the bill payment reduces in the
--   same database transaction that appends the ledger row. A throwaway
--   Testcontainers database starts empty, so without this script the two
--   statements com.carddemo.transaction.repository.AccountBalanceRepository
--   issues resolve against nothing and the integration test that proves the
--   single commit fails on an undefined table rather than on its own claim.
--
--   THIS FILE IS A TEST HARNESS, NOT A FLYWAY MIGRATION. It carries no `V<n>__`
--   version prefix, it does not live under `db/migration`, and it must NEVER be
--   added to `spring.flyway.locations`. That property reads
--   `classpath:db/migration` identically in both profiles, and keeping this file
--   outside it is the entire point: the set of migrations Flyway applies under
--   test is exactly the set it applies in production, and this script supplies
--   only what a provisioned environment supplies AHEAD of Flyway. In a
--   provisioned environment `account.accounts` is created by account-service's
--   own V1__account.sql and the schema itself by
--   data-migration/sql/V0__schemas_and_roles.sql; neither belongs to this
--   module, and neither is copied into this module's migration set.
--
--   Execution mechanism: a Testcontainers init script, run once at container
--   start and therefore strictly BEFORE Flyway opens its first connection. The
--   caller supplies it as
--   `new PostgreSQLContainer(image).withInitScript(HARNESS_SCRIPT)` where
--   HARNESS_SCRIPT is the classpath-relative path
--   db/testharness/test-harness-account-schema.sql. That path is fixed by this
--   file's location, so renaming or moving the file breaks the reference with no
--   compiler to catch it: the container simply starts without the schema and the
--   test fails on an undefined-table error that names nothing about the move. The
--   calling test therefore asserts the post-state below before asserting
--   anything else.
--
-- Inputs and preconditions:
--   A reachable PostgreSQL database, empty or already carrying the objects
--   below, and a connecting role permitted to CREATE SCHEMA in it. No parameter,
--   variable, credential or endpoint is read or embedded: the script is a closed
--   set of literals, which is what makes it safe to run unattended at container
--   start.
--
-- Post-state established:
--   account                  schema exists
--   account.accounts         13 columns, primary key on account_id, `version`
--                            defaulting to 0
--
-- Failure modes:
--   Every statement is guarded with IF NOT EXISTS, so a re-run is a no-op rather
--   than an error -- which matters because Testcontainers may replay an init
--   script against a reused container. If the connecting role may not CREATE
--   SCHEMA the first statement fails with SQLSTATE 42501 and the container start
--   aborts, which is the correct place for that failure to surface.
-- =============================================================================


-- Section 1. The foreign schema.
--
-- Assumptions: the schema is created WITHOUT an AUTHORIZATION clause,
--   unlike the `ledger` schema Flyway creates under `SET ROLE
--   carddemo_ledger_owner`. The distinction is deliberate: the ownership split
--   this module reproduces under test exists so that every
--   ALTER DEFAULT PRIVILEGES FOR ROLE clause keyed on the CREATING role applies
--   to the objects this module's migration creates. `account.accounts` is not one
--   of those objects -- account-service creates it, under its own owner -- so
--   naming an owner here would assert an ownership fact this module has no
--   authority over and cannot verify.
CREATE SCHEMA IF NOT EXISTS account;


-- Section 2. The account master, as account-service's V1__account.sql declares it.
--
-- Assumptions: all thirteen columns are declared even though the bill
--   payment touches exactly two of them, `curr_bal` and `version`. A narrower
--   table would let the two statements under test pass against a shape the
--   deployed cluster does not have, and it would hide the NOT NULL constraints
--   that make an INSERT in the calling test fail loudly if a column is forgotten
--   -- which is the check that keeps the fixture honest about what an account row
--   actually is.
--
-- Assumptions: `version` carries its DEFAULT 0 because the reduction
--   statement advances it -- `version = version + 1` -- rather than leaving it
--   alone. account-service maps that column as a JPA @Version, so a payment that
--   reduced the balance without advancing it would leave a concurrent
--   account-service update matching on a revision that no longer describes the
--   row, and that update would then overwrite the payment. The default is what
--   lets the calling test insert a row without naming the column and still
--   observe the advance.
--
-- Trade-offs: the two CHECK constraints account-service declares are
--   reproduced for `active_status` and omitted for everything else. The cost is
--   that this fixture accepts some values the real table refuses; what is bought
--   is that the constraint on the one column whose domain the payment path could
--   plausibly disturb is present, and the reproduction stays short enough to stay
--   in step with the owning migration.
CREATE TABLE IF NOT EXISTS account.accounts (
    account_id           BIGINT          NOT NULL,
    active_status        CHAR(1)         NOT NULL,
    curr_bal             NUMERIC(12, 2)  NOT NULL,
    credit_limit         NUMERIC(12, 2)  NOT NULL,
    cash_credit_limit    NUMERIC(12, 2)  NOT NULL,
    open_date            DATE            NOT NULL,
    expiration_date      DATE            NOT NULL,
    reissue_date         DATE            NOT NULL,
    curr_cyc_credit      NUMERIC(12, 2)  NOT NULL,
    curr_cyc_debit       NUMERIC(12, 2)  NOT NULL,
    addr_zip             CHAR(10)        NOT NULL,
    group_id             CHAR(10)        NOT NULL,
    version              BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_accounts PRIMARY KEY (account_id),
    CONSTRAINT ck_accounts_active_status CHECK (active_status IN ('Y', 'N'))
);
