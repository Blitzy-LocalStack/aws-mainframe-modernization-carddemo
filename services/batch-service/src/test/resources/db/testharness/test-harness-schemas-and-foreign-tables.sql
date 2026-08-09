-- =============================================================================
-- services/batch-service/src/test/resources/db/testharness/
--   test-harness-schemas-and-foreign-tables.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Bootstraps the three FOREIGN PostgreSQL schemas -- ledger, account and
--   reference -- and the seven foreign tables that the batch-service
--   integration-test suite reads and writes, so that a throwaway Testcontainers
--   database reaches the state a provisioned environment is already in before
--   any service migration runs. The fourth schema this module connects to,
--   `batch`, is created by Flyway rather than here, for the ownership reason
--   recorded in Section 1.
--
--   THIS FILE IS A TEST HARNESS, NOT A FLYWAY MIGRATION. It carries no `V<n>__`
--   version prefix, it does not live under `db/migration`, and it must NEVER be
--   added to `spring.flyway.locations`. That property reads
--   `classpath:db/migration` identically in both profiles --
--   services/batch-service/src/main/resources/application.yml L572 and
--   services/batch-service/src/test/resources/application-test.yml L178 -- and
--   keeping this file outside it is the entire point: the set of migrations
--   Flyway applies under test is exactly the set it applies in production, and
--   this script supplies only what a provisioned environment supplies ahead of
--   Flyway rather than adding a migration to that set.
--
--   Execution mechanism: a Testcontainers init script, run once at container
--   start and therefore strictly BEFORE Flyway opens its first connection. The
--   three integration tests of com.carddemo.batch.repository each supply it the
--   same way, through
--   `new PostgreSQLContainer(image).withInitScript(HARNESS_SCRIPT)` where
--   HARNESS_SCRIPT is the classpath-relative path
--   db/testharness/test-harness-schemas-and-foreign-tables.sql; a `jdbc:tc:` URL
--   carrying TC_INITSCRIPT with the same path is the equivalent mechanism for a
--   caller that has no container handle. That classpath-relative path is fixed by
--   this file's location, so renaming or moving the file breaks the reference with
--   no compiler to catch it: the container simply starts without schemas and every
--   repository test fails on an unrelated-looking undefined-table error. Each of
--   the three tests therefore asserts the post-state below before asserting
--   anything else, so a broken reference is reported as a missing schema at the
--   point it occurs rather than as a query failure much later.
--
-- Inputs and preconditions:
--   A reachable PostgreSQL database, empty or already carrying the objects
--   below, and a connecting role permitted to CREATE SCHEMA in it. No
--   parameter, variable, credential or endpoint is read or embedded: the script
--   is a closed set of literals, which is what makes it safe to run unattended
--   at container start.
--
-- Post-state established:
--   (batch)                                NOT created here -- Flyway creates it,
--                                          owned by carddemo_batch_owner
--   ledger.transactions                    13 columns, primary key on
--                                          transaction_id
--   ledger.daily_transactions              14 columns, primary key on
--                                          ingest_seq, proc_ts nullable
--   ledger.transaction_rejects             4 columns, primary key on reject_seq
--   ledger.transaction_category_balances   4 columns, three-part primary key
--   account.accounts                       13 columns, primary key on
--                                          account_id
--   account.card_xref                      3 columns, primary key on card_num,
--                                          plus idx_card_xref_account_id
--   reference.disclosure_groups            4 columns, three-part primary key,
--                                          seeded with the 17 DEFAULT rows
--
-- Failure modes:
--   Re-running against the same container is a no-op: every statement is
--   idempotent, by IF NOT EXISTS or by ON CONFLICT DO NOTHING, so a reused
--   container and a retried start behave identically. If the connecting role
--   may not CREATE SCHEMA, the first statement fails with SQLSTATE 42501 and
--   container start fails loudly, which is the outcome to want -- a silent
--   partial bootstrap would instead reappear much later as an undefined-table
--   error inside an unrelated test.
--
-- WHY (non-obvious design decisions):
--   Alternatives Considered: three other routes to the same post-state were
--     available, and all three were rejected.
--     (a) Declaring a Maven dependency on transaction-service and
--         account-service so their authored migrations could be reused
--         directly. Rejected: this module may depend on common-lib and on no
--         other sibling service, and the ArchUnit layering rule forbidding a
--         cross-service domain import would fail the build alongside it. Those
--         migrations are therefore unreachable from this module's test
--         classpath, which is why their content is mirrored here instead.
--     (b) Adding this file to `spring.flyway.locations` for the test profile.
--         Rejected on two counts: it would make the test's Flyway
--         configuration differ from production's, which is the single thing the
--         integration suite exists to hold constant; and a harness sitting
--         inside the migration locations invites a later reader to treat it as
--         an owned migration and to alter, from here, a table this module does
--         not own.
--     (c) Scoping the integration tests to `batch.*` alone, so that no mirrored
--         table would be needed. Rejected: the posting unit of work writes
--         transaction_category_balances, then accounts, then transactions
--         inside ONE transaction -- app/cbl/CBTRN02C.cbl:440-442 performs
--         2700-UPDATE-TCATBAL, 2800-UPDATE-ACCOUNT-REC and
--         2900-WRITE-TRANSACTION-FILE in that order -- so a batch-only scope
--         would leave that single-commit property with no test able to observe
--         it.
--   Trade-offs: mirroring another service's DDL accepts a real drift risk. If
--     an owning service alters a column, this file does not follow
--     automatically, and the drift surfaces as a puzzling integration-test
--     failure rather than as a schema error at the point of change. Two things
--     bound that cost. The mirror surface is kept minimal -- only the tables
--     this module maps, and within them only the columns the owner declares,
--     with nothing invented -- and every table below cites its owning migration
--     by path, so reconciling a drift is a two-file diff rather than an
--     investigation.
--   Assumptions: the owning migrations are the authority for every column name,
--     every type and every seeded value here, not this module's entity prose
--     and not a decode of the reference extracts. Where a value could be
--     derived two ways, the owner's choice is mirrored, so a test and a
--     provisioned environment cannot disagree about it.
--   Assumptions: creating and seeding schemas owned by other services is
--     confined to an ephemeral container that is destroyed with the build. This
--     file authors nothing under another service's tree and ships in no image,
--     which is what keeps it a test fixture rather than an encroachment on
--     another service's schema ownership.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Section 1 of 4 -- schemas
-- -----------------------------------------------------------------------------

-- WHAT: create the three FOREIGN schemas this module connects across, ahead of
--       any table. `batch` is deliberately NOT among them; see the note that
--       follows this statement group.
-- WHY : two independent reasons, neither of which alone would be sufficient.
--       (1) Flyway here is scoped to a single schema, so it can never provision
--           these three. Both profiles set `schemas: batch` and
--           `default-schema: batch` -- application.yml L590-L591 and
--           application-test.yml L192-L193 -- so `batch` is the only schema
--           Flyway addresses at all, and V1__batch.sql creates tables and
--           nothing else. Nothing else on this module's test classpath creates
--           `ledger`, `account` or `reference`. Note that `create-schemas`
--           differs between the two profiles, false at application.yml L602 and
--           true at application-test.yml L214, and the difference does not
--           reach this decision: that setting governs only whether Flyway
--           creates the schema it is scoped to, so under either value `ledger`,
--           `account` and `reference` remain uncreated.
--       (2) Ordering within this one script is load-bearing rather than
--           stylistic: `CREATE TABLE ledger.transactions` issued against a
--           missing `ledger` fails with SQLSTATE 3F000 invalid_schema_name, so
--           the schemas have to be established before the tables that name them.
-- WHY : **Assumptions:** the connection `search_path` must not be mistaken for a
--       safety net covering reason (2). The datasource sets `search_path` to
--       `batch, ledger, account, reference`, but PostgreSQL resolves that list
--       lazily and accepts a name that does not exist, so `SET search_path` over
--       a missing schema returns SET rather than raising -- verified directly
--       against PostgreSQL 17.10 on an empty database. A missing schema
--       therefore produces no diagnostic at connection time and resurfaces later
--       as 42P01 undefined_table inside whichever repository call happens to
--       touch it first.
-- WHY : **Trade-offs:** IF NOT EXISTS is used here and throughout this file
--       rather than a bare CREATE. It gives up the ability to notice a
--       pre-existing object, and buys a script that is safe to execute twice --
--       which matters because both a reused container and a retried container
--       start re-execute it, and a bare CREATE would abort the second run with
--       42P06 duplicate_schema before reaching a single table.
CREATE SCHEMA IF NOT EXISTS ledger;
CREATE SCHEMA IF NOT EXISTS account;
CREATE SCHEMA IF NOT EXISTS reference;

-- WHAT: `batch` is NOT created here, and its absence from the three statements
--       above is required rather than an oversight. Flyway creates it, under
--       `create-schemas: true` at application-test.yml L214.
-- WHY : **Refactoring Rationale:** this file did carry
--       `CREATE SCHEMA IF NOT EXISTS batch;` alongside the three above, and that
--       statement made the production Flyway configuration FAIL. The mechanism is
--       ownership, and it was measured against PostgreSQL 17.10 rather than
--       reasoned about. An init script runs as the container's own generated
--       superuser, so the schema it creates is owned by that user. Flyway then
--       connects and its `init-sqls` -- application-test.yml, the two statements
--       under that key -- create the NOLOGIN role `carddemo_batch_owner`, grant it
--       CREATE on the container's database and `SET ROLE` to it, which drops the
--       superuser attribute for the rest of the session. The very next statement,
--       `CREATE TABLE batch.batch_run`, then fails with SQLSTATE 42501
--       `permission denied for schema batch`: the assumed role holds CREATE on the
--       DATABASE but not on a schema somebody else owns. Every integration test in
--       this module aborted on context load, and the reported cause named the
--       migration rather than this file -- exactly the misdirection the note below
--       warns about, arriving by a different route.
-- WHY : **Assumptions:** deferring to Flyway does not merely avoid the denial, it
--       reproduces the deployed ownership exactly.
--       data-migration/sql/V0__schemas_and_roles.sql L713 declares
--       `CREATE SCHEMA IF NOT EXISTS batch AUTHORIZATION carddemo_batch_owner`, so
--       in a provisioned environment the schema belongs to that role. With the
--       statement removed, `create-schemas: true` has Flyway issue the CREATE
--       while the SET ROLE is in force, and `pg_namespace.nspowner` then resolves
--       to `carddemo_batch_owner` -- verified in a throwaway container. Every
--       `ALTER DEFAULT PRIVILEGES FOR ROLE` clause in that bootstrap file is keyed
--       on the CREATING role and is inert otherwise, which is why the ownership is
--       the property that has to match and not merely the schema's existence.
-- WHY : **Alternatives Considered:** three ways to keep the statement were
--       evaluated and all three were rejected. (a) Creating the role here and
--       writing `CREATE SCHEMA IF NOT EXISTS batch AUTHORIZATION
--       carddemo_batch_owner`, mirroring V0 literally. Rejected because it would
--       put role creation into a file whose closing note states that no role is
--       created and no privilege granted anywhere in it, and it would duplicate
--       the role definition that already lives in the test profile's `init-sqls`,
--       giving two places to keep one role's attributes in step. (b) Granting
--       CREATE on `batch` to the migration role from here. Rejected for the same
--       reason and because it would leave the schema owned by the wrong role, so
--       the default-privilege clauses above would still not apply. (c) Overriding
--       `init-sqls` in the test profile so that no role is assumed at all.
--       Rejected because the ownership split is the mechanism the deployed
--       configuration depends on, and a suite that stopped exercising it would
--       leave it asserted by nothing.
-- WHY : **Assumptions:** V1__batch.sql is the sole owner of every object in
--       `batch` -- batch.batch_run at its L271, the six Spring Batch
--       JobRepository tables at L664-L729 and their three sequences at
--       L737-L739 -- and Flyway applies it into the schema it has itself created.
--       Creating any of those objects here would not merely duplicate work:
--       Flyway would either fail outright on the first CREATE TABLE that finds its
--       target already present, or record a checksum over a shape it did not
--       build. In both cases the reported failure would point at the migration
--       rather than at this file. `flyway_schema_history` is likewise not created
--       here; Flyway creates and owns it inside `batch`.
-- WHY : **Assumptions:** no schema beyond these three is created. The mapped
--       entity set of this module spans these three plus `batch`, so a fifth
--       schema would be structure no test can reach and drift no owner would
--       notice.


-- -----------------------------------------------------------------------------
-- Section 2 of 4 -- account context, mirroring
-- services/account-service/src/main/resources/db/migration/V1__account.sql
-- -----------------------------------------------------------------------------

-- WHY : Assumptions: the five money columns are NUMERIC(12,2) because the
--       reference declares each as PIC S9(10)V99 -- app/cpy/CVACT01Y.cpy L7-L9
--       and L13-L14 -- which is ten integer digits and two decimals. A
--       floating-point type here would make the posting assertions approximate,
--       and the balance this module updates is the operand the interest job
--       later multiplies.
-- WHY : Assumptions: addr_zip and group_id are CHAR(10) rather than VARCHAR, so
--       the trailing blanks of the fixed reference fields are preserved. The
--       disclosure-group lookup below joins on the padded form, so trimming here
--       would make a lookup that succeeds in a provisioned environment miss in a
--       test.
-- WHY : Assumptions: the version column is mirrored even though no batch write
--       increments it. It is NOT NULL DEFAULT 0 in the owning migration, so a
--       harness that omitted it would let an insert succeed here that fails
--       there.
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

-- WHY : Assumptions: the index is NON-UNIQUE, and that is the contract rather
--       than an omission. It replaces the CXACAIX alternate index, whose key is
--       the account identifier while the base cluster keys on the card number --
--       app/cbl/CBACT03C.cbl L32 declares RECORD KEY IS FD-XREF-CARD-NUM -- so a
--       multi-card account holds several rows under one account identifier and a
--       unique index would refuse the second card.
CREATE TABLE IF NOT EXISTS account.card_xref (
    card_num        CHAR(16)    NOT NULL,
    customer_id     BIGINT      NOT NULL,
    account_id      BIGINT      NOT NULL,
    CONSTRAINT pk_card_xref PRIMARY KEY (card_num)
);

CREATE INDEX IF NOT EXISTS idx_card_xref_account_id
    ON account.card_xref (account_id);


-- -----------------------------------------------------------------------------
-- Section 3 of 4 -- reference context, mirroring
-- services/reference-service/src/main/resources/db/migration/V1__reference.sql
-- and its seed at V2__seed_reference.sql
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS reference.disclosure_groups (
    acct_group_id  CHAR(10)      NOT NULL,
    tran_type_cd   CHAR(2)       NOT NULL,
    tran_cat_cd    CHAR(4)       NOT NULL,
    interest_rate  NUMERIC(6,2)  NOT NULL,
    CONSTRAINT pk_disclosure_groups
        PRIMARY KEY (acct_group_id, tran_type_cd, tran_cat_cd)
);

-- WHY : Assumptions: the DEFAULT rows are seeded HERE rather than left to each
--       test, because their absence is expensive to diagnose. When a direct group
--       lookup misses, app/cbl/CBACT04C.cbl:436 tests for VSAM status '23', :437
--       substitutes the DEFAULT group and :438 retries the read. That retry, at
--       :443-:460, has NO INVALID KEY clause on its READ at :444 and treats
--       anything other than status '00' as fatal at :446, so a missing DEFAULT
--       row reaches :455 and :458 and lands in 9999-ABEND-PROGRAM, which at :631
--       sets ABCODE 999 and at :632 abends through CALL 'CEE3ABD'. The observable
--       symptom is a crash attributed to the interest job while the interest job
--       is behaving exactly as specified.
-- WHY : Assumptions: 'DEFAULT   ' carries its three trailing blanks because the
--       column is CHAR(10) and the reference group field is PIC X(10). The
--       lookup joins on the padded form, so a trimmed literal would seed a row no
--       lookup finds.
-- WHY : Assumptions: every rate below is the value the OWNING migration seeds,
--       V2__seed_reference.sql L335-L361, and not an independent decode of the
--       reference bytes. One pair makes the distinction matter: the two shipped
--       encodings of this dataset disagree on ('07','0001') -- the EBCDIC extract
--       reads '00150{' for 15.00 and the ASCII extract reads '00000{' for 0.00 --
--       and the owner settles that in favour of the EBCDIC extract, registered as
--       D-SEED-ENCODING-AUTHORITY in
--       docs/architecture/cobol-to-service-traceability.md. Mirroring the owner's
--       value rather than re-deciding it is what makes a test and a provisioned
--       environment agree about an operand CBACT04C L464-L465 multiplies a
--       balance by; both this file and the owner use ON CONFLICT DO NOTHING, so
--       whichever ran first would otherwise decide the rate.
-- WHY : Trade-offs: ON CONFLICT DO NOTHING rather than a plain INSERT, which is
--       also the idiom the owning migration uses. It gives up detecting a
--       pre-seeded row, and buys re-runnability against a reused container, where
--       a plain INSERT would abort on 23505 unique_violation.
INSERT INTO reference.disclosure_groups
    (acct_group_id, tran_type_cd, tran_cat_cd, interest_rate) VALUES
    ('DEFAULT   ', '01', '0001', 15.00),
    ('DEFAULT   ', '01', '0002', 25.00),
    ('DEFAULT   ', '01', '0003', 25.00),
    ('DEFAULT   ', '01', '0004', 25.00),
    ('DEFAULT   ', '02', '0001',  0.00),
    ('DEFAULT   ', '02', '0002',  0.00),
    ('DEFAULT   ', '02', '0003',  0.00),
    ('DEFAULT   ', '03', '0001',  0.00),
    ('DEFAULT   ', '03', '0002',  0.00),
    ('DEFAULT   ', '03', '0003',  0.00),
    ('DEFAULT   ', '04', '0001', 15.00),
    ('DEFAULT   ', '04', '0002', 15.00),
    ('DEFAULT   ', '04', '0003', 15.00),
    ('DEFAULT   ', '05', '0001', 15.00),
    ('DEFAULT   ', '06', '0001', 15.00),
    ('DEFAULT   ', '06', '0002', 15.00),
    ('DEFAULT   ', '07', '0001', 15.00)
ON CONFLICT (acct_group_id, tran_type_cd, tran_cat_cd) DO NOTHING;

-- WHY : Trade-offs: reference.transaction_types, transaction_categories,
--       us_phone_area_codes, us_states and us_state_zip_prefixes are
--       deliberately NOT created, although V1__reference.sql declares all five in
--       this same schema. No entity in this module maps any of them, so no query
--       it issues can reach them; creating them would widen the drift surface
--       against reference-service with no test able to detect a divergence. Their
--       absence is a decision, not a gap.


-- -----------------------------------------------------------------------------
-- Section 4 of 4 -- ledger context, mirroring
-- services/transaction-service/src/main/resources/db/migration/V1__ledger.sql
-- -----------------------------------------------------------------------------

-- WHY : Assumptions: proc_ts is NOT NULL here and NULLABLE on
--       daily_transactions below, and the asymmetry is the whole point of the two
--       tables. A posted row has been processed by definition -- CBTRN02C stamps
--       it as it writes -- whereas the pre-posting feed leaves those 26 bytes
--       blank, so a NOT NULL here catches a posting path that forgot to stamp,
--       and a NOT NULL there would reject every seeded input record.
-- WHY : Assumptions: amount is NUMERIC(11,2) rather than the account master's
--       NUMERIC(12,2), because the transaction amount is PIC S9(09)V99 at
--       app/cpy/CVTRA05Y.cpy L11 -- nine integer digits, not ten. Widening it
--       would let a test insert an amount the owning schema refuses.
CREATE TABLE IF NOT EXISTS ledger.transactions (
    transaction_id  CHAR(16)      NOT NULL,
    type_cd         CHAR(2),
    category_cd     CHAR(4),
    source          CHAR(10),
    description     VARCHAR(100),
    amount          NUMERIC(11,2) NOT NULL,
    merchant_id     BIGINT,
    merchant_name   VARCHAR(50),
    merchant_city   VARCHAR(50),
    merchant_zip    CHAR(10),
    card_num        CHAR(16),
    orig_ts         TIMESTAMP(6),
    proc_ts         TIMESTAMP(6)  NOT NULL,
    CONSTRAINT pk_transactions PRIMARY KEY (transaction_id)
);

-- WHY : Assumptions: the key is a generated ingest_seq and NOT transaction_id,
--       because the pre-posting feed has no usable natural key. Its
--       transaction_id is nullable and the feed may legitimately carry the same
--       identifier twice, so keying on it would reject input the reference
--       accepts. GENERATED BY DEFAULT rather than ALWAYS so a fixture may supply
--       its own sequence when a test needs a deterministic order.
CREATE TABLE IF NOT EXISTS ledger.daily_transactions (
    ingest_seq      BIGINT GENERATED BY DEFAULT AS IDENTITY,
    transaction_id  CHAR(16),
    type_cd         CHAR(2),
    category_cd     CHAR(4),
    source          CHAR(10),
    description     VARCHAR(100),
    amount          NUMERIC(11,2) NOT NULL,
    merchant_id     BIGINT,
    merchant_name   VARCHAR(50),
    merchant_city   VARCHAR(50),
    merchant_zip    CHAR(10),
    card_num        CHAR(16),
    orig_ts         TIMESTAMP(6),
    proc_ts         TIMESTAMP(6),
    CONSTRAINT pk_daily_transactions PRIMARY KEY (ingest_seq)
);

-- WHY : Assumptions: the three data columns preserve the reference's 430-byte
--       reject contract by composition -- raw_record CHAR(350) is the daily
--       record verbatim, reason_code its numeric reason, and reason_desc
--       VARCHAR(76) the verbatim sentence -- rather than storing one 430-byte
--       string. The record is kept CHAR(350) so a reject can be replayed through
--       the same fixed-width decoder that produced it, which a trimmed column
--       would break at the first field after a trailing blank.
-- WHY : Assumptions: the reason-code range is bounded rather than enumerated. The
--       four documented reasons are 100 to 103, but the reference writes the code
--       it computed, so pinning the check to those four would make a harness
--       stricter than the owning schema and turn a new reason into a constraint
--       violation instead of a visible row.
CREATE TABLE IF NOT EXISTS ledger.transaction_rejects (
    reject_seq   BIGINT GENERATED BY DEFAULT AS IDENTITY,
    raw_record   CHAR(350)     NOT NULL,
    reason_code  SMALLINT      NOT NULL,
    reason_desc  VARCHAR(76)   NOT NULL,
    CONSTRAINT ck_transaction_rejects_reason_code
        CHECK (reason_code BETWEEN 0 AND 9999),
    CONSTRAINT pk_transaction_rejects PRIMARY KEY (reject_seq)
);

-- WHY : Assumptions: the three-part primary key is what makes the reference's
--       create-versus-update branch observable. CBTRN02C reaches 2700-A-CREATE
--       when no row exists for the account, type and category triple and
--       2700-B-UPDATE when one does, so a key over fewer columns would collapse
--       two rows the reference keeps apart and neither branch could be asserted.
-- WHY : Assumptions: the balance defaults to 0 rather than being NOT NULL without
--       a default, so the create branch may insert the row and then add to it in
--       the same unit of work, which is the order the reference performs.
CREATE TABLE IF NOT EXISTS ledger.transaction_category_balances (
    account_id   BIGINT       NOT NULL,
    type_cd      CHAR(2)      NOT NULL,
    category_cd  CHAR(4)      NOT NULL,
    balance      NUMERIC(11,2) NOT NULL DEFAULT 0,
    CONSTRAINT pk_transaction_category_balances
        PRIMARY KEY (account_id, type_cd, category_cd)
);

-- WHY : Assumptions: the batch schema is created EMPTY. Its tables --
--       batch.batch_run and the Spring Batch job repository -- are the ones this
--       module owns, so Flyway creates them from V1__batch.sql under the very
--       configuration production uses. Creating them here would replace the thing
--       the integration suite exists to exercise with a copy of it.
