-- =============================================================================
-- services/batch-service/src/test/resources/db/testharness/test-harness-schemas-and-foreign-tables.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Bootstraps the four PostgreSQL schemas -- batch, ledger, account and
--   reference -- and the seven FOREIGN tables that the batch-service
--   integration-test suite reads and writes, so that a throwaway Testcontainers
--   database reaches the state a provisioned environment is already in before
--   any service migration runs.
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
--   Execution mechanism: a Testcontainers init script -- a `jdbc:tc:` URL
--   carrying TC_INITSCRIPT with the classpath-relative path
--   db/testharness/test-harness-schemas-and-foreign-tables.sql, or an
--   equivalent withInitScript call -- which runs once at container start and
--   therefore strictly BEFORE Flyway opens its first connection. That
--   classpath-relative path is fixed by this file's location, so renaming or
--   moving the file breaks the reference with no compiler to catch it: the
--   container simply starts without schemas and every repository test fails on
--   an unrelated-looking undefined-table error.
--
-- Inputs and preconditions:
--   A reachable PostgreSQL database, empty or already carrying the objects
--   below, and a connecting role permitted to CREATE SCHEMA in it. No
--   parameter, variable, credential or endpoint is read or embedded: the script
--   is a closed set of literals, which is what makes it safe to run unattended
--   at container start.
--
-- Post-state established:
--   batch                                  schema only, deliberately EMPTY
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
--   - **Alternatives Considered:** three other routes to the same post-state
--     were available, and all three were rejected.
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
--     (c) Scoping the integration tests to `batch.*` alone, so that no foreign
--         table would be needed. Rejected: the posting unit of work writes
--         transaction_category_balances, then accounts, then transactions
--         inside ONE transaction -- app/cbl/CBTRN02C.cbl:440-442 performs
--         2700-UPDATE-TCATBAL, 2800-UPDATE-ACCOUNT-REC and
--         2900-WRITE-TRANSACTION-FILE in that order -- so a batch-only scope
--         would leave that single-commit property with no test able to observe
--         it.
--   - **Trade-offs:** mirroring another service's DDL accepts a real drift
--     risk. If an owning service alters a column, this file does not follow
--     automatically, and the drift surfaces as a puzzling integration-test
--     failure rather than as a schema error at the point of change. Two things
--     bound that cost. The mirror surface is kept minimal -- only the tables
--     this module maps, and within them only the columns the owner declares,
--     with nothing invented -- and every table below cites its owning migration
--     by path, so reconciling a drift is a two-file diff rather than an
--     investigation.
--   - **Assumptions:** the owning migrations are the authority for every column
--     name and type here, not this module's entity prose, and where the two
--     disagreed the owner was mirrored. The specific disagreements are recorded
--     at the tables they affect rather than collected here, so that a reader
--     comparing one table against its owner finds the note without having to
--     read the whole file.
--   - **Assumptions:** creating and seeding schemas owned by other services is
--     confined to an ephemeral container that is destroyed with the build. This
--     file authors nothing under another service's tree and ships in no image,
--     which is what keeps it a test fixture rather than an encroachment on
--     another service's schema ownership.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Section 1 of 4 -- schemas
-- -----------------------------------------------------------------------------

-- WHAT: create the four schemas this module connects across, ahead of any table.
-- WHY : two independent reasons, neither of which alone would be sufficient.
--       (1) Flyway here is scoped to a single schema, so it can never provision
--           the other three. Both profiles set `schemas: batch` and
--           `default-schema: batch` -- application.yml L590-L591 and
--           application-test.yml L191-L192 -- so `batch` is the only schema
--           Flyway addresses at all, and V1__batch.sql creates tables and
--           nothing else. Nothing else on this module's test classpath creates
--           `ledger`, `account` or `reference`. Note that `create-schemas`
--           differs between the two profiles, false at application.yml L602 and
--           true at application-test.yml L208, and the difference does not
--           reach this decision: that setting governs only whether Flyway
--           creates the schema it is scoped to, so under either value `ledger`,
--           `account` and `reference` remain uncreated. `CREATE SCHEMA IF NOT
--           EXISTS batch` below is therefore correct under both, deferring to
--           Flyway where Flyway would act and supplying the schema where it
--           would not.
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
CREATE SCHEMA IF NOT EXISTS batch;
CREATE SCHEMA IF NOT EXISTS ledger;
CREATE SCHEMA IF NOT EXISTS account;
CREATE SCHEMA IF NOT EXISTS reference;

-- WHAT: `batch` is created above and then left completely empty.
-- WHY : **Assumptions:** V1__batch.sql is the sole owner of every object in
--       `batch` -- batch.batch_run at its L271, the six Spring Batch
--       JobRepository tables at L664-L729 and their three sequences at
--       L737-L739 -- and Flyway applies it into the schema this script has just
--       created. Creating any of those objects here would not merely duplicate
--       work: Flyway would either fail outright on the first CREATE TABLE that
--       finds its target already present, or record a checksum over a shape it
--       did not build. In both cases the reported failure would point at the
--       migration rather than at this file, which is the expensive kind of
--       misdirection. `flyway_schema_history` is likewise not created here;
--       Flyway creates and owns it inside `batch`.
-- WHY : **Assumptions:** no schema beyond these four is created. The mapped
--       entity set of this module spans exactly these four, so a fifth schema
--       would be structure no test can reach and drift no owner would notice.


-- -----------------------------------------------------------------------------
-- Section 2 of 4 -- account schema, mirroring
-- services/account-service/src/main/resources/db/migration/V1__account.sql
-- -----------------------------------------------------------------------------

-- WHAT: mirror account.accounts as the owning migration declares it at
--       V1__account.sql:183, derived there from the 300-byte ACCOUNT-RECORD in
--       app/cpy/CVACT01Y.cpy with its trailing FILLER X(178) dropped.
-- WHY : **Assumptions:** the column names are the owner's, and one of them
--       disagrees with this module's entity prose. The owner declares the
--       primary key `account_id`; a reading of the batch-service domain prose
--       alone would suggest `acct_id`. The owner is mirrored because it is the
--       shape a provisioned environment actually has, and Account.java:242
--       already maps `@Column(name = "account_id")`, so the two agree in
--       practice. Reconciling a Java field name to a column is the entity's job
--       through an explicit @Column, never this file's job through a rename: a
--       harness column renamed away from its owner would let a test pass
--       against a schema production will never produce.
-- WHY : **Assumptions:** money is NUMERIC(12,2) here and NUMERIC(11,2) in the
--       ledger tables, and the difference is derived rather than chosen.
--       ACCT-CURR-BAL and its four siblings are PIC S9(10)V99 in
--       app/cpy/CVACT01Y.cpy -- ten integer digits and two decimals, so twelve
--       significant digits -- whereas TRAN-AMT is PIC S9(09)V99, giving eleven.
--       Widening the ledger side to match, or narrowing this side, would place a
--       representable baseline value outside its own column.
-- WHY : **Assumptions:** the three date columns are DATE even though the
--       baseline holds them as PIC X(10) character fields. Those fields carry
--       'YYYY-MM-DD', which is ordered identically as text and as a date, so the
--       expiration comparison at app/cbl/CBTRN02C.cbl:414 keeps its meaning
--       across the change of type. `expiration_date` also corrects the
--       baseline's misspelled ACCT-EXPIRAION-DATE at app/cpy/CVACT01Y.cpy:L11;
--       the baseline is reference-only and keeps the misspelling, and this
--       column follows the owner's corrected name.
-- WHY : **Trade-offs:** the owner's NOT NULL constraints and its
--       active_status CHECK are reproduced rather than relaxed, even though
--       Hibernate schema validation inspects neither. Relaxing them would let a
--       fixture that production would reject load cleanly in the container, so
--       the failure would move from this suite to a deployed environment.
--       Reproducing them costs the suite a stricter fixture contract, and that
--       is the cheaper side of the trade.
-- WHY : **Assumptions:** no card-verification-value column exists on any table
--       in this file, because the owning migration declares none and no job in
--       this module reads one. Its absence is deliberate, not an omission to be
--       repaired by a later reader.
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
    -- WHY : **Assumptions:** group_id is CHAR(10) because it is the join key
    --       into reference.disclosure_groups.acct_group_id, which is CHAR(10)
    --       for the space-padding reason recorded in section 3. A VARCHAR here
    --       against a CHAR(10) there would compare a trimmed value against a
    --       padded one and turn every interest-rate lookup into a miss, which
    --       the baseline resolves by falling back to the DEFAULT group rather
    --       than by reporting an error -- so the defect would be silent.
    group_id             CHAR(10)        NOT NULL,
    -- WHY : **Assumptions:** `version` exists because Account.java:469-471 maps
    --       it with @Version, so the provider issues UPDATE ... WHERE version = ?
    --       and needs the column present. It reproduces the before-image
    --       comparison the baseline already performs across a screen turn; it is
    --       not a new concurrency policy introduced by the target.
    version              BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_accounts PRIMARY KEY (account_id),
    CONSTRAINT ck_accounts_active_status CHECK (active_status IN ('Y', 'N'))
);

-- WHAT: mirror account.card_xref as declared at V1__account.sql:641, derived
--       from the 50-byte CARD-XREF-RECORD in app/cpy/CVACT03Y.cpy with its
--       trailing FILLER X(14) dropped.
-- WHY : **Assumptions:** three columns and no more. In particular there is no
--       `version` column here, unlike account.accounts: the owner declares
--       none, and CardXref.java:326 maps the entity @Immutable, so nothing in
--       this module updates a cross-reference row and there is no lost-update
--       window for a version column to close.
-- WHY : **Assumptions:** the two identifier names are the owner's,
--       `customer_id` and `account_id`, and the batch-service domain prose can
--       be read as suggesting `cust_id` and `acct_id`. The owner is mirrored,
--       and CardXref.java:393 and :428 already map the owner's names, so the
--       two agree.
CREATE TABLE IF NOT EXISTS account.card_xref (
    card_num        CHAR(16)    NOT NULL,
    customer_id     BIGINT      NOT NULL,
    account_id      BIGINT      NOT NULL,
    CONSTRAINT pk_card_xref PRIMARY KEY (card_num)
);

-- WHAT: no foreign key is declared from account.card_xref.account_id to
--       account.accounts.account_id, and none anywhere else in this file.
-- WHY : **Assumptions:** the owning migration declares no inter-table foreign
--       key, and adding one here would not be a harmless tightening -- it would
--       break a test that must pass. Reject reason 101 exists precisely for a
--       cross-reference row whose account is absent: app/cbl/CBTRN02C.cbl:394
--       moves XREF-ACCT-ID into the account key, and the INVALID KEY path at
--       :397-:398 moves 101 and 'ACCOUNT RECORD NOT FOUND'. A fixture built to
--       exercise that branch has to be able to INSERT a cross-reference row
--       pointing at a missing account. Under a foreign key that INSERT fails
--       with 23503 foreign_key_violation during setup, and the test reports a
--       fixture error instead of reaching the validation branch it exists to
--       cover.

-- WHAT: a NON-UNIQUE index on account.card_xref(account_id), named exactly as
--       the owning migration names it at V1__account.sql:726.
-- WHY : **Assumptions:** this index is the target of a real baseline access
--       path, not decoration, and the two batch jobs in this module disagree
--       about needing it -- which is the whole reason it must exist. The
--       interest job reaches the cross-reference BY ACCOUNT: app/jcl/INTCALC.jcl
--       mounts a second DD at L31-L32 over the CARDXREF alternate-index PATH
--       dataset, in addition to the base cluster it already mounts at L29-L30,
--       and app/cbl/CBACT04C.cbl:38 declares
--       `ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID` to read it. The posting job
--       reaches the same file BY CARD NUMBER only: app/jcl/POSTTRAN.jcl mounts
--       the base cluster alone at L32-L33 and contains no XREFFIL1 DD at all
--       (verified by search: zero occurrences in that file), matching the keyed
--       read at app/cbl/CBTRN02C.cbl:380-392. Omitting the index would leave the
--       interest job's access path with no equivalent here, so it is the one
--       index in this file that is not optional.
-- WHY : **Trade-offs:** it is deliberately non-unique. The baseline alternate
--       index is itself non-unique, and many cards legitimately share one
--       account, so a UNIQUE index would reject correct multi-card fixtures with
--       23505 unique_violation.
CREATE INDEX IF NOT EXISTS idx_card_xref_account_id
    ON account.card_xref (account_id);

-- WHAT: account.customers is deliberately NOT created, although
--       V1__account.sql:423 declares it in the same schema.
-- WHY : **Trade-offs:** this is the mirror-surface bound from the header applied
--       concretely. No entity in
--       services/batch-service/src/main/java/com/carddemo/batch/domain maps a
--       customer, so no query this module issues can reach the table, and
--       reproducing its nineteen columns would add drift surface against
--       account-service that nothing here could ever detect. The cost accepted
--       is that a future test needing customer data must add the table at that
--       point rather than find it waiting.


-- -----------------------------------------------------------------------------
-- Section 3 of 4 -- reference schema, mirroring
-- services/reference-service/src/main/resources/db/migration/V1__reference.sql
-- -----------------------------------------------------------------------------

-- WHAT: mirror reference.disclosure_groups as declared at V1__reference.sql:294,
--       derived from the 50-byte DIS-GROUP-RECORD in app/cpy/CVTRA02Y.cpy with
--       its trailing FILLER X(28) dropped.
-- WHY : **Assumptions:** acct_group_id is CHAR(10) and must not be softened to
--       VARCHAR, because the stored value is space-padded and the padding is
--       load-bearing. app/cbl/CBACT04C.cbl:437 moves the SEVEN-character
--       literal 'DEFAULT' into a PIC X(10) field, so what the baseline actually
--       matches on is 'DEFAULT' followed by three spaces -- visible verbatim in
--       app/data/ASCII/discgrp.txt, whose DEFAULT records begin 'DEFAULT   '.
--       CHAR(10) reproduces that padding on comparison; VARCHAR(10) would store
--       and compare the trimmed form, so a lookup written either way would miss.
-- WHY : **Assumptions:** tran_cat_cd is CHAR(4) rather than a numeric type,
--       preserving the zero-padded form that DIS-TRAN-CAT-CD PIC 9(04) produces
--       and that appears as '0001' in the fixture bytes. The owner declares the
--       same width for the corresponding column of
--       ledger.transaction_category_balances, so the two cat-code columns agree
--       at CHAR(4) and neither needs converting to reach the other. Both are
--       mirrored as their owners declare them.
CREATE TABLE IF NOT EXISTS reference.disclosure_groups (
    acct_group_id  CHAR(10)      NOT NULL,
    tran_type_cd   CHAR(2)       NOT NULL,
    tran_cat_cd    CHAR(4)       NOT NULL,
    interest_rate  NUMERIC(6,2)  NOT NULL,
    -- WHY : **Assumptions:** the key components are listed in PHYSICAL record
    --       order -- group, then TYPE, then CAT -- as app/cpy/CVTRA02Y.cpy
    --       declares them at L6, L7 and L8 and as the owning migration declares
    --       them. The COBOL populates the same key in a DIFFERENT order:
    --       app/cbl/CBACT04C.cbl:210 moves the group, then :211 moves the
    --       CATEGORY and :212 moves the TYPE, so the last two are genuinely
    --       reversed with respect to the layout. That sequence is the order of
    --       two MOVE statements into a group item and has no bearing on the key
    --       itself. This note exists so that nobody reconciles the two by
    --       "correcting" the key here to follow the MOVE order, which would
    --       reorder the index and silently diverge from the owner.
    CONSTRAINT pk_disclosure_groups
        PRIMARY KEY (acct_group_id, tran_type_cd, tran_cat_cd)
);

-- WHAT: seed the seventeen 'DEFAULT   ' disclosure-group rows, and only those.
-- WHY : **Assumptions:** these rows are environment-invariant reference data
--       that a provisioned environment always holds before any batch job runs,
--       so the harness has to reproduce them to be a faithful starting state.
--       V2__seed_reference.sql seeds reference.disclosure_groups
--       unconditionally in every environment from app/data/ASCII/discgrp.txt --
--       measured at 2601 bytes, 51 records of 50 bytes, comprising exactly three
--       group ids of seventeen rows each -- and seventeen of those rows carry
--       the DEFAULT group, one per (tran_type_cd, tran_cat_cd) pair.
-- WHY : **Trade-offs:** only the DEFAULT rows are seeded here; the thirty-four
--       A000000000 and ZEROAPR rows are left out. The DEFAULT rows are the
--       fallback baseline every scenario shares, whereas a direct-hit group row
--       is what distinguishes one interest scenario from another -- the house
--       COBOL fixtures already draw the line in exactly this place, shipping a
--       single A000000000 row where the direct lookup must hit and only the
--       seventeen DEFAULT rows where it must miss. Seeding the direct-hit rows
--       here would make the fallback scenario unable to miss, so the two
--       scenarios could no longer be distinguished. Scenario rows are therefore
--       loaded by each test additively on top of this baseline.
-- WHY : **Assumptions:** a missing DEFAULT row does not surface as a
--       recognisable data problem, which is why their absence would be
--       expensive to diagnose and why they are seeded here rather than left to
--       each test. When a direct group lookup misses, app/cbl/CBACT04C.cbl:436
--       tests for VSAM status '23', :437 substitutes the DEFAULT group and :438
--       performs the retry read. That retry, at :443-:460, has NO INVALID KEY
--       clause on its READ at :444 and treats anything other than status '00' as
--       fatal at :446, so a missing DEFAULT row reaches :455 and :458 and lands
--       in 9999-ABEND-PROGRAM, which at :631 sets ABCODE 999 and at :632 issues
--       a genuine abend through CALL 'CEE3ABD'. The observable symptom is a
--       crash attributed to the interest job while the interest job is behaving
--       exactly as specified.
-- WHY : **Assumptions:** the seventeen rates below were decoded from the
--       baseline bytes rather than copied from another seed script, because the
--       two disagree on one row. Each rate is DIS-INT-RATE PIC S9(04)V99 held as
--       zoned decimal with a sign overpunch on the final digit, where '{' is +0:
--       '00150{' is digits 001500, that is 15.00. Decoding all seventeen gives
--       0.00 for the ('07','0001') pair -- its bytes are '00000{' -- whereas
--       V2__seed_reference.sql seeds that one pair as 15.00, which is the value
--       the A000000000 group carries for the same pair. The immutable baseline
--       data is the ground truth and is followed here. The divergence is
--       recorded rather than repaired: V2__seed_reference.sql belongs to
--       reference-service and is not this module's to edit.
-- WHY : **Trade-offs:** ON CONFLICT DO NOTHING rather than a plain INSERT, which
--       is also the idiom the owning seed migration uses. It gives up detecting
--       a pre-seeded row, and buys re-runnability against a reused container,
--       where a plain INSERT would abort on 23505 unique_violation.
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
    ('DEFAULT   ', '07', '0001',  0.00)
ON CONFLICT (acct_group_id, tran_type_cd, tran_cat_cd) DO NOTHING;

-- WHAT: reference.transaction_types, reference.transaction_categories,
--       reference.us_phone_area_codes, reference.us_states and
--       reference.us_state_zip_prefixes are deliberately NOT created, although
--       V1__reference.sql declares all five in this same schema.
-- WHY : **Trade-offs:** no entity in this module maps any of them, so no query
--       it issues can reach them. Creating them would widen the drift surface
--       against reference-service with no test able to detect a divergence --
--       and reproducing transaction_categories in particular would drag in its
--       ON DELETE RESTRICT foreign key to transaction_types, a constraint whose
--       preserved legacy semantic belongs to reference-service's own suite to
--       verify, not to this one. The accepted cost is the same as for
--       account.customers: a future test needing lookup data adds what it needs
--       at that point.



-- -----------------------------------------------------------------------------
-- Section 4 of 4 -- ledger schema, mirroring
-- services/transaction-service/src/main/resources/db/migration/V1__ledger.sql
-- -----------------------------------------------------------------------------

-- WHAT: mirror ledger.transactions as declared at V1__ledger.sql:117, derived
--       from the 350-byte TRAN-RECORD in app/cpy/CVTRA05Y.cpy: fourteen fields,
--       of which the trailing FILLER X(20) is dropped, leaving thirteen columns.
-- WHY : **Assumptions:** the column widths are the record layout, not a
--       judgement. Summing the declared field widths of that copybook places
--       card_num at zero-based offset 262, orig_ts at 278 and proc_ts at 304,
--       and app/jcl/TRANREPT.jcl corroborates both boundaries independently from
--       the sort side, declaring TRAN-CARD-NUM at one-based 263 for 16 bytes at
--       L41 and TRAN-PROC-DT at one-based 305 for 10 bytes at L42. Two unrelated
--       sources agreeing is what makes the widths safe to rely on.
-- WHY : **Assumptions:** the money column is named `amount` and is
--       NUMERIC(11,2). The name is the owner's, where this module's entity prose
--       can be read as suggesting `tran_amt`; Transaction.java:239 maps
--       `@Column(name = "amount", precision = 11, scale = 2)`, so entity and
--       owner agree. The precision is derived from TRAN-AMT PIC S9(09)V99 --
--       nine integer digits plus two decimals -- and is deliberately NARROWER
--       than the NUMERIC(12,2) of account.accounts, because the two copybooks
--       declare different widths and collapsing them to one would misrepresent
--       one side. Money is exact fixed point at every hop here; no column in
--       this file uses a floating-point type.
-- WHY : **Assumptions:** proc_ts is NOT NULL on this table, in contrast to
--       ledger.daily_transactions below, and the asymmetry is the owner's. A row
--       reaches ledger.transactions only by being posted, and posting stamps the
--       processing timestamp on the way in -- app/cbl/CBTRN02C.cbl:438 moves a
--       formatted timestamp into TRAN-PROC-TS before the write at :442 -- so
--       there is no state in which a posted transaction legitimately lacks one.
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

-- WHAT: mirror ledger.daily_transactions as declared at V1__ledger.sql:339 --
--       the same thirteen columns as ledger.transactions plus a surrogate
--       ingest_seq -- derived from the 350-byte DALYTRAN-RECORD in
--       app/cpy/CVTRA06Y.cpy, which is field-for-field identical in width to
--       CVTRA05Y.
-- WHY : **Assumptions:** proc_ts is NULLABLE here, and this is measured rather
--       than inferred. app/cpy/CVTRA06Y.cpy places DALYTRAN-PROC-TS at zero-based
--       offset 304, and every one of the 300 records of
--       app/data/ASCII/dailytran.txt -- 105300 bytes, each record exactly 350 --
--       carries twenty-six spaces there, while DALYTRAN-ORIG-TS at 278 carries a
--       real timestamp. The pre-posting feed genuinely has no processing
--       timestamp yet: posting is what assigns one. Declaring the column NOT NULL
--       would reject the baseline's own seed data.
-- WHY : **Assumptions:** those twenty-six spaces mean one thing here and a
--       different thing in a golden file, and conflating the two leads to the
--       wrong conclusion about this column. In the feed they are real data --
--       the absent-processing-timestamp state described above. In a committed
--       golden they are the product of timestamp normalisation, which blanks a
--       non-deterministic value so that byte comparison stays stable across
--       runs. Same bytes, two unrelated reasons; only the feed's reason bears on
--       nullability.
-- WHY : **Assumptions:** the seed file is app/data/ASCII/dailytran.txt. The
--       spelling matters when locating it, because the copybook, the DD name and
--       the record prefix all use the contracted form DALYTRAN while the file on
--       disk does not.
-- WHY : **Assumptions:** ingest_seq is GENERATED BY DEFAULT AS IDENTITY, matching
--       the owner, and BY DEFAULT rather than ALWAYS is required rather than
--       stylistic. DailyTransaction.java:366-368 places @Id on the member with no
--       @GeneratedValue, so the application supplies the value itself; under
--       GENERATED ALWAYS PostgreSQL would reject that INSERT with 428C9 unless
--       every writer added OVERRIDING SYSTEM VALUE.
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

-- WHAT: mirror ledger.transaction_rejects as declared at V1__ledger.sql:579 --
--       the 430-byte reject contract expressed as three columns, plus a
--       surrogate key.
-- WHY : **Assumptions:** the 350 + 80 split is the baseline's own record shape
--       and is corroborated from several independent directions, which is why
--       these widths are not adjustable. The FD at app/cbl/CBTRN02C.cbl:83-84
--       declares FD-REJECT-RECORD PIC X(350) followed by FD-VALIDATION-TRAILER
--       PIC X(80); working storage at :177, :181 and :182 declares the matching
--       REJECT-TRAN-DATA PIC X(350), WS-VALIDATION-FAIL-REASON PIC 9(04) and
--       WS-VALIDATION-FAIL-REASON-DESC PIC X(76); the pair of MOVEs at :447-:448
--       fills them; app/jcl/POSTTRAN.jcl:36 defines the output stream as
--       LRECL=430; and the committed goldens measure 430 bytes per line.
--       raw_record is CHAR(350) because :447 copies the daily-transaction image
--       verbatim, padding included -- it is a byte image, not a parsed record.
-- WHY : **Assumptions:** reason_code is SMALLINT holding the INTEGER 102, and
--       this is the single most confusable column in this file. The four-digit
--       zero-padded '0102' that appears in the fixtures and goldens is the WIRE
--       rendering that WS-VALIDATION-FAIL-REASON PIC 9(04) produces when the
--       trailer is written as bytes: in the committed golden for the over-limit
--       scenario the four bytes at one-based 351-354 read '0102', immediately
--       after the 350-byte image, and the account-missing golden reads '0101' in
--       the same position. Because that padded form is what a reader sees first,
--       modelling the column as CHAR(4) is the natural mistake; the owner
--       declares SMALLINT, the entity maps a Short, and the padding belongs to
--       the byte image rather than to the column.
-- WHY : **Assumptions:** the reason codes this column carries are 100, 101, 102
--       and 103 -- set at app/cbl/CBTRN02C.cbl:385, :397, :410 and :417 with
--       their descriptions at :386, :398, :411 and :418. Note that a fifth
--       value, 109, is set at :556 inside the account-rewrite INVALID KEY path
--       and is NOT one of the four documented posting rejects, so a test
--       asserting over the reject stream should not expect it among them. The
--       owner's CHECK admits the whole PIC 9(04) range rather than an
--       enumeration of the four, which is what leaves room for 109.
-- WHY : **Assumptions:** the primary key is a surrogate identity column because
--       the artifact being mirrored has no key at all -- the baseline writes
--       rejects to a sequential output stream, so there is no natural candidate
--       and no uniqueness to preserve. Two rejects may legitimately be
--       byte-identical. TransactionReject.java:315-318 maps
--       @GeneratedValue(strategy = IDENTITY), so GENERATED BY DEFAULT AS
--       IDENTITY here lets the provider omit the column on INSERT and read the
--       assigned value back.
CREATE TABLE IF NOT EXISTS ledger.transaction_rejects (
    reject_seq   BIGINT GENERATED BY DEFAULT AS IDENTITY,
    raw_record   CHAR(350)     NOT NULL,
    reason_code  SMALLINT      NOT NULL,
    reason_desc  VARCHAR(76)   NOT NULL,
    CONSTRAINT ck_transaction_rejects_reason_code
        CHECK (reason_code BETWEEN 0 AND 9999),
    CONSTRAINT pk_transaction_rejects PRIMARY KEY (reject_seq)
);

-- WHAT: mirror ledger.transaction_category_balances as declared at
--       V1__ledger.sql:801, derived from the 50-byte TRAN-CAT-BAL-RECORD in
--       app/cpy/CVTRA01Y.cpy with its trailing FILLER X(22) dropped.
-- WHY : **Assumptions:** the three key columns reproduce the 17-byte TRAN-CAT-KEY
--       group that app/cpy/CVTRA01Y.cpy declares at L5, composed of
--       TRANCAT-ACCT-ID PIC 9(11) at L6, TRANCAT-TYPE-CD PIC X(02) at L7 and
--       TRANCAT-CD PIC 9(04) at L8. They are a composite primary key rather than
--       a surrogate because that group IS the record's key in the baseline, and
--       because the posting job's create-versus-update decision depends on a
--       keyed lookup over exactly those three components.
-- WHY : **Assumptions:** account_id here arrives from the CROSS-REFERENCE read,
--       not from the daily-transaction record, which is why a fixture cannot
--       populate it straight from the feed. app/cbl/CBTRN02C.cbl:469 moves
--       XREF-ACCT-ID into the key, with the type at :470 and the category at
--       :471 coming from the transaction; the daily-transaction record carries a
--       card number and no account id at all.
-- WHY : **Assumptions:** category_cd is CHAR(4) and named as the owner names it,
--       where this module's entity prose can be read as suggesting a numeric
--       cat_cd. TransactionCategoryBalance.java:784 maps
--       `@Column(name = "category_cd", length = 4)`, so entity and owner agree,
--       and the column therefore matches reference.disclosure_groups.tran_cat_cd
--       in both width and type -- so the two cat-code columns join without a
--       cast in either direction.
CREATE TABLE IF NOT EXISTS ledger.transaction_category_balances (
    account_id   BIGINT       NOT NULL,
    type_cd      CHAR(2)      NOT NULL,
    category_cd  CHAR(4)      NOT NULL,
    -- WHY : **Trade-offs:** DEFAULT 0 is carried across from the owner. It
    --       means a create-path INSERT that omits the balance lands at zero
    --       rather than failing, which is what the baseline's create branch
    --       does before it adds the transaction amount; the cost is that an
    --       accidentally omitted balance is silently zero rather than loud.
    balance      NUMERIC(11,2) NOT NULL DEFAULT 0,
    CONSTRAINT pk_transaction_category_balances
        PRIMARY KEY (account_id, type_cd, category_cd)
);

-- WHAT: the two secondary indexes the owning migration declares on
--       ledger.transactions -- idx_transactions_card_num at V1__ledger.sql:289
--       and the non-unique idx_transactions_proc_ts at :311 -- are deliberately
--       NOT created here.
-- WHY : **Trade-offs:** neither carries an access path this module needs.
--       idx_transactions_card_num serves the online transaction browse, which
--       lives in transaction-service, and idx_transactions_proc_ts replaces the
--       reporting alternate index whose key app/jcl/TRANIDX.jcl declares as
--       KEYS(26 304) at L27 with NONUNIQUEKEY at L28 -- a reporting path, not a
--       posting or interest path. Fixture volumes here are a few rows, so
--       neither index changes a query plan meaningfully, and omitting them keeps
--       the mirror surface at what the tests actually exercise. The cost is that
--       this file is not a complete picture of the ledger schema, which is why
--       the omission is stated rather than left to be inferred from absence. If
--       a test ever needs either, it must be added under the owner's exact name
--       so the two remain diffable.

-- WHAT: no role is created and no privilege is granted anywhere in this file.
-- WHY : **Alternatives Considered:** reproducing the production grant asymmetry,
--       so that the read-only projections were read-only in the container too.
--       Rejected: it would require the test connection to assume one of those
--       roles, adding a failure mode to container start while proving nothing
--       about the application. **Trade-offs:** the limitation this accepts is
--       specific and worth stating, because it makes one class of defect
--       invisible here. In production batch-service holds SELECT only on
--       reference.disclosure_groups; in this container it owns everything. A
--       test that wrote to that table would therefore SUCCEED here and FAIL in a
--       deployed environment with 42501 insufficient_privilege. The read-only
--       property of that projection is consequently guaranteed by the mapping
--       rather than by the database -- DisclosureGroup.java:167 declares the
--       entity @Immutable, so the provider emits no UPDATE for it -- and that
--       annotation, not this file, is what must not be removed.
-- =============================================================================

