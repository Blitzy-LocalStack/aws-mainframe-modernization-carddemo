-- =============================================================================
-- services/reporting-service/src/test/resources/db/testharness/
--   test-harness-reporting-relations.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Bootstraps the `reporting` schema and the three relations the statement
--   heading chunk reads -- v_card_xref, v_customers and v_accounts -- so that a
--   throwaway Testcontainers database can answer the query whose PAGING
--   CORRECTNESS is under test. It seeds four cross-reference rows arranged so
--   that ordering by the fingerprint alone and ordering by the declared tuple
--   produce different sequences, which is what makes the continuation assertion
--   able to fail.
--
--   THIS FILE IS A TEST HARNESS, NOT A FLYWAY MIGRATION. It carries no `V<n>__`
--   version prefix, it does not live under `db/migration`, and it must never be
--   added to a Flyway location. reporting-service owns no schema and ships no
--   migration at all, so there is no migration set for it to contaminate; what
--   it supplies here is what a provisioned environment supplies before this
--   service ever connects.
--
--   Execution mechanism: a Testcontainers init script, run once at container
--   start, supplied as
--   `new PostgreSQLContainer(image).withInitScript(HARNESS_SCRIPT)` -- the same
--   mechanism and the same classpath-relative shape the batch-service
--   integration tests already use for their foreign schemas.
--
-- WHY : Alternatives Considered: a harness rather than the real views.
--   The real relations are VIEWS declared in data-migration/sql/
--   V1__reporting_views.sql over base tables that FOUR other services' Flyway
--   migrations create -- account, card, ledger and reference. Reproducing them
--   here would mean reproducing four migrations this module does not own, and
--   the copy would drift from the originals silently; the sibling
--   ReportingQueryBootstrapIT records that reasoning and declines to create any
--   relation at all for it. This script takes the narrower option that reasoning
--   permits: it creates the three relations as PLAIN TABLES carrying exactly the
--   columns the entities map, which is enough for the engine to answer the
--   query and is honest about being a stand-in. It asserts nothing about the
--   real views' definitions and must never be read as evidence about them.
--
-- Trade-offs:
--   A plain table cannot catch a mismatch between an entity mapping and the real
--   view -- that gap is closed elsewhere, by the migration this file names and
--   by the mapping tests in this module. What it CAN catch, and nothing else in
--   this repository can, is a keyset predicate that does not reproduce its own
--   ORDER BY: that defect is invisible to a mocked repository, because a
--   stand-in answers whatever it is arranged to answer, and invisible to a
--   parse-only test, because the predicate parses perfectly.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS reporting;

-- Assumptions: the columns are exactly those
--   services/reporting-service/src/main/java/com/carddemo/reporting/domain/
--   CardXrefView.java maps, at the widths it declares -- card_num 16,
--   card_fingerprint 64 -- with the fingerprint as the primary key because that
--   is the column the entity annotates @Id. The masked rendering is NOT unique
--   and carries no unique constraint here, which is the property the paging
--   defect turned on: twelve constant asterisks and four digits collide as soon
--   as two cards share a tail.
-- WHY VARCHAR rather than CHAR: a CHAR comparison ignores trailing blanks, so a
--   fixture value shorter than the declared width would compare equal to a
--   padded one and the continuation's equality arm could hold for the wrong row.
--   Every value seeded below occupies its full width, so the two types would
--   behave identically -- VARCHAR is chosen so that a future fixture cannot make
--   the difference matter without failing loudly.
CREATE TABLE reporting.v_card_xref (
    card_num          VARCHAR(16) NOT NULL,
    card_fingerprint  VARCHAR(64) NOT NULL,
    customer_id       BIGINT      NOT NULL,
    account_id        BIGINT      NOT NULL,
    CONSTRAINT pk_harness_v_card_xref PRIMARY KEY (card_fingerprint)
);

-- Assumptions: every column CustomerView maps is present even though the chunk
--   query selects only ten of them, because a column the entity maps and the
--   table lacks fails the query with a missing-column error that reads as a
--   defect in the query rather than in this script.
CREATE TABLE reporting.v_customers (
    customer_id        BIGINT      NOT NULL,
    first_name         VARCHAR(25) NOT NULL,
    middle_name        VARCHAR(25),
    last_name          VARCHAR(25) NOT NULL,
    addr_line_1        VARCHAR(50) NOT NULL,
    addr_line_2        VARCHAR(50),
    addr_line_3        VARCHAR(50) NOT NULL,
    addr_state_cd      VARCHAR(2)  NOT NULL,
    addr_country_cd    VARCHAR(3)  NOT NULL,
    addr_zip           VARCHAR(10) NOT NULL,
    dob                DATE        NOT NULL,
    fico_credit_score  SMALLINT    NOT NULL,
    CONSTRAINT pk_harness_v_customers PRIMARY KEY (customer_id)
);

-- Assumptions: the two money columns are NUMERIC(12,2), which is the precision
--   and scale AccountView declares, so the value the mapping reads back is exact
--   fixed point rather than an approximation -- transformation rule T3 of the
--   migration plan forbids any approximate type in the money path, and a
--   harness that used one would let a rounding defect pass here.
CREATE TABLE reporting.v_accounts (
    account_id       BIGINT         NOT NULL,
    active_status    VARCHAR(1)     NOT NULL,
    curr_bal         NUMERIC(12, 2) NOT NULL,
    credit_limit     NUMERIC(12, 2) NOT NULL,
    open_date        DATE           NOT NULL,
    expiration_date  DATE           NOT NULL,
    reissue_date     DATE           NOT NULL,
    group_id         VARCHAR(10)    NOT NULL,
    CONSTRAINT pk_harness_v_accounts PRIMARY KEY (account_id)
);

-- =============================================================================
-- The discriminating fixture
-- -----------------------------------------------------------------------------
-- The four rows below are ordered by (card_num ASC, card_fingerprint ASC) as
--   1. ('************1111', 'ffff...f1')
--   2. ('************2222', 'aaaa...a1')
--   3. ('************2222', 'bbbb...b1')
--   4. ('************3333', 'cccc...c1')
-- and by card_fingerprint alone as 2, 3, 4, 1 -- a different sequence, which is
-- the whole point of the arrangement.
--
-- WHY these four and not four arbitrary rows: read in chunks of two under a
-- continuation that compares the fingerprint ALONE, the first chunk returns rows
-- 1 and 2 and leaves the anchor at row 2's fingerprint 'aaa...a1'; the next
-- chunk then asks for fingerprints above that value, which admits row 1 again,
-- so row 1 is REPEATED and keeps being re-admitted for as long as the anchor
-- stays below it. Rearranging the same four rows so the low fingerprint leads
-- produces the other failure instead: the anchor lands above every remaining
-- fingerprint and the walk ends early, SKIPPING the rest. One fixture cannot
-- show both at once, and the repeat is the one seeded because a repeated
-- cardholder statement and a runaway loop are both observable from the run's
-- output, whereas a skip is silent -- so a fixture that reproduces the repeat
-- also demonstrates the predicate is not merely conservative.
-- Assumptions: two of the four rows share one masked rendering, so the fixture
-- also exercises the equality arm of the corrected predicate, which is the arm
-- that resumes WITHIN a group of cards sharing a tail. Three distinct renderings
-- and one shared pair is the smallest shape that covers both arms.
-- =============================================================================

INSERT INTO reporting.v_customers (
    customer_id, first_name, middle_name, last_name, addr_line_1, addr_line_2,
    addr_line_3, addr_state_cd, addr_country_cd, addr_zip, dob, fico_credit_score)
VALUES
    (100000001, 'Ada',   'B', 'Lovelace', '1 Analytical Way', NULL,
     'Nowhere', 'NY', 'USA', '10001', DATE '1815-12-10', 780),
    (100000002, 'Grace', 'M', 'Hopper',   '2 Compiler Road',  'Suite 4',
     'Nowhere', 'NJ', 'USA', '07001', DATE '1906-12-09', 800);

INSERT INTO reporting.v_accounts (
    account_id, active_status, curr_bal, credit_limit, open_date,
    expiration_date, reissue_date, group_id)
VALUES
    (10000000001, 'Y', 1234.56, 5000.00, DATE '2020-01-01',
     DATE '2030-01-01', DATE '2025-01-01', 'DEFAULT   '),
    (10000000002, 'Y', -78.90,  9000.00, DATE '2021-06-01',
     DATE '2031-06-01', DATE '2026-06-01', 'DEFAULT   ');

INSERT INTO reporting.v_card_xref (card_num, card_fingerprint, customer_id, account_id)
VALUES
    ('************1111', repeat('f', 63) || '1', 100000001, 10000000001),
    ('************2222', repeat('a', 63) || '1', 100000002, 10000000002),
    ('************2222', repeat('b', 63) || '1', 100000001, 10000000001),
    ('************3333', repeat('c', 63) || '1', 100000002, 10000000002);
