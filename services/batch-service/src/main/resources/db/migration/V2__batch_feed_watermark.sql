-- =============================================================================
-- services/batch-service/src/main/resources/db/migration/V2__batch_feed_watermark.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Create batch.daily_feed_watermark, the one row per feed that records how far
--   into ledger.daily_transactions the posting step has already consumed. It is
--   what makes a nightly posting pass consume ITS OWN input rather than every
--   row the feed table has ever held.
--
-- Why this table exists (the defect it closes):
--   In the reference, app/jcl/POSTTRAN.jcl:30-31 supplies the feed as the flat
--   sequential dataset AWS.M2.CARDDEMO.DALYTRAN.PS and CBTRN02C reads it end to
--   end -- app/cbl/CBTRN02C.cbl:29-32 declares it ORGANIZATION IS SEQUENTIAL
--   with no record key, and the driver at :202-219 advances it to end of file.
--   The dataset is REPLACED between runs, so "the whole file" and "tonight's
--   transactions" are the same set and the program needs no cursor.
--
--   The target's feed is a TABLE. ledger.daily_transactions accumulates: each
--   night's extract is loaded into it and the rows stay, because they are the
--   audit trail the three verification passes and the reject stream are checked
--   against. The posting job's walk started from ordinal zero every night, so
--   the second night re-posted the first night's transactions -- adding their
--   amounts to account balances a second time and inserting a second posted
--   transaction row for each. That is not a difference of degree from the
--   reference; it is the opposite outcome, and nothing in the chain would have
--   reported it, because every one of those postings is individually valid.
--
--   This table is the missing half of the equivalence: the flat file's identity
--   as "one night's input" was carried by the file being replaced, and here it
--   is carried by a stored ordinal.
--
-- Preconditions (what must already be true when Flyway applies this file):
--   - V1__batch.sql has been applied, so the schema `batch` exists, is owned by
--     carddemo_batch, and holds batch.batch_run and the framework's job
--     repository. This file adds one table beside them and alters nothing.
--   - The connecting role is carddemo_batch, so the table below is created owned
--     by it and V0 section 4's default privileges fire for it.
--   - Flyway is scoped to this module's classpath location and to the `batch`
--     schema, exactly as for V1. Nothing here reaches outside that schema.
--   - The persistence provider runs in validate-only mode, so every column name
--     and type below is half of a two-way contract with
--     com.carddemo.batch.domain.DailyFeedWatermark. The Java type each column
--     requires is stated at the column.
--
-- Post-state established:
--   - One table, batch.daily_feed_watermark, with five columns, a single-column
--     primary key on the feed name and three value-domain checks.
--   - Table and column comments carrying the contract into the database, so an
--     operator inspecting the row sees what it means without reading this file.
--   - NO seed row, NO index beyond the primary key, and no privilege change.
--
-- Fails when:
--   - The schema `batch` does not exist or the connecting role cannot create in
--     it, which both mean V1 has not been applied against this database.
--   - A column name or type below disagrees with the entity mapping; the
--     validate-only pass refuses to start, which is the point of that mode.
--   - The filename is altered. Flyway recognises a versioned migration only in
--     the exact V<version>__<description>.sql form, and a single underscore
--     where the two-underscore separator belongs makes the file invisible -- the
--     table is then never created and posting silently reverts to re-consuming
--     the whole feed, with no error to show for it.
--
-- Derivation:
--   No baseline record layout stands behind this table. Like batch.batch_run it
--   is the durable form of something the reference did not need to store,
--   because the reference stored it implicitly in the lifecycle of a dataset.
--   Everything under app/** is reference-only, cited by path and line, never
--   modified.
--
-- Governing convention:
--   docs/CODE_DOCUMENTATION_STANDARD.md, whose SQL section requires a header
--   block plus a why-comment on each non-obvious constraint or index. No linter
--   reads this file, so review is the only gate on that obligation.
--
-- WHY (non-obvious design decisions, argued at the point of use below):
--   - Alternatives Considered: deleting consumed rows; filtering by business
--     date; reusing batch.batch_run; a per-run partition of the feed. All four
--     rejected, each for its own reason -- see the block above the table.
--   - Assumptions: the row is read under a write lock for the pass's duration,
--     so two concurrent posting passes serialise rather than interleave.
--   - Trade-offs: one row per FEED rather than one row per (feed, run). Argued
--     at the primary key.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Alternatives Considered, in the order they were evaluated and rejected.
--
-- (1) DELETE each consumed row, so the table only ever holds unposted rows and
--     the walk needs no cursor at all. This is the closest literal analogue of
--     the reference replacing its dataset, and it was rejected because the rows
--     are not scratch. docs/runbooks/data-migration.md's verification gate
--     compares the loaded feed against the extract's own bytes -- row counts, a
--     per-record checksum and exact money totals -- and every one of those
--     passes reads ledger.daily_transactions after posting has run. Deleting
--     consumed rows would make the load unverifiable the moment it was used,
--     and would also destroy the only record of what a reject was rejected
--     from.
--
-- (2) Filter the walk on the injected business date, so each pass posts only
--     rows belonging to its own date. Rejected because the feed carries no such
--     column to filter on and the nearest candidate changes behaviour. The
--     layout's only populated stamp is the ORIGINATION timestamp -- measured
--     blank on 0 of 300 records of app/data/ASCII/dailytran.txt, against the
--     processing stamp which is blank on 300 of 300 -- and origination date is
--     when the cardholder transacted, not when the file was presented for
--     posting. The reference posts every record in the file whatever date it
--     originated on (app/cbl/CBTRN02C.cbl:202-219 tests nothing of the kind), so
--     a date filter would drop transactions the reference posts. It would also
--     contradict AAP section 0.4.1.7, which injects the business date as a
--     parameter for reproducibility rather than as a data selector.
--
-- (3) Reuse batch.batch_run. Rejected because that table cannot hold a cursor:
--     V1__batch.sql gives it id, run_id, step_name, status, attempt, started_at,
--     finished_at and return_code, and its own comments record the division of
--     labour -- batch_run answers "did this step already complete for this run",
--     which is the question a redrive asks before doing anything, while this
--     table answers "how far into the feed has the WORKLOAD got", which persists
--     across runs and is not a property of any one of them.
--
-- (4) Partition or tag the feed per run at load time, so each pass reads its own
--     partition. Rejected as the wider change for no additional guarantee: it
--     would put an orchestration identifier into a table whose columns are
--     otherwise all transcribed from app/cpy/CVTRA06Y.cpy, and the loader that
--     writes it -- data-migration's bulk COPY -- would have to learn which
--     nightly execution it belongs to. One stored ordinal in this module's own
--     schema keeps the feed table exactly as the copybook declares it.
-- -----------------------------------------------------------------------------

CREATE TABLE batch.daily_feed_watermark (

    -- Java type: String. WHY : Trade-offs: the key is the FEED, so this table
    --       holds one row per feed and not one row per (feed, run). A per-run
    --       history would let an operator read which run consumed which range,
    --       which is genuinely useful, and it is declined because it makes the
    --       question the posting step actually asks -- "what is the highest
    --       ordinal anyone has consumed" -- an aggregate over an unbounded set
    --       rather than a single row read, and because the per-run history
    --       already exists: batch.batch_run records every step of every run, and
    --       the posting step logs the ordinal range it consumed. What is stored
    --       here is only the part that has to be read back.
    -- WHY : Assumptions: the width is 30 and the value is the RECORD LAYOUT name
    --       the migration registry uses -- 'DALYTRAN' -- rather than the table
    --       name or a service name. The layout name is the vocabulary the ETL,
    --       the verification passes and the runbook all already use for this
    --       feed, and 30 characters is comfortably above the longest registered
    --       layout name while staying short enough to read in a console.
    feed_name       VARCHAR(30)  NOT NULL,

    -- Java type: long (mapped as a primitive, because the column is NOT NULL and
    --       an absent row rather than a null value is how "nothing consumed yet"
    --       is expressed).
    -- WHY : Assumptions: this is an EXCLUSIVE upper bound on what has been
    --       consumed -- the ordinal of the last row posted, so the next pass
    --       reads strictly greater than it. That matches the feed repository's
    --       continuation finder, which is declared strictly greater for the
    --       reason its own contract records: an inclusive bound would re-deliver
    --       a row already posted, and under the additive posting model of
    --       app/cbl/CBTRN02C.cbl:202-219 a re-delivered row is a double-counted
    --       amount rather than a harmless repeat.
    -- WHY : Assumptions: the column is BIGINT because the ordinal it stores is
    --       ledger.daily_transactions.ingest_seq, declared BIGINT GENERATED BY
    --       DEFAULT AS IDENTITY by services/transaction-service's V1__ledger.sql.
    --       A narrower type here would silently truncate once the feed had
    --       accumulated more rows than it holds, which is precisely the long-run
    --       state this table exists to manage.
    last_ingest_seq BIGINT       NOT NULL,

    -- Java type: String. WHY : Assumptions: the run that last advanced the
    --       watermark is recorded for diagnosis and is NEVER read as a control
    --       value. An operator asking why tonight's pass posted nothing needs to
    --       know which execution consumed the rows, and this is the column that
    --       answers it; nothing branches on it, so a run identifier that has
    --       since been forgotten costs nothing. The width matches
    --       batch.batch_run.run_id so the two can be joined by eye.
    run_id          VARCHAR(80)  NOT NULL,

    -- Java type: String. WHY : Assumptions: the business date is stored as the
    --       ten-character TOKEN exactly as the run received it, and NOT as a DATE.
    --       com.carddemo.batch.dto.BusinessDate admits two layouts because the
    --       reference supplies both -- app/jcl/INTCALC.jcl:22 injects
    --       PARM='2022071800', ten characters with no separators, while the
    --       migrated chain injects the separated ISO form -- and that type's whole
    --       purpose is that the token is carried VERBATIM, because a re-rendered
    --       token reaching a stored identifier is a parity failure. A DATE column
    --       would force a parse, and the parse of a compact token throws; storing
    --       the characters records what the run was actually given.
    -- WHY : Assumptions: the value is the INJECTED parameter and never a clock
    --       reading, so a rerun of a given night records that night. It is stored
    --       for the same diagnostic reason as run_id and is likewise never read as
    --       a control value; in particular the walk is not filtered by it, for the
    --       reason alternative (2) above records.
    business_date   VARCHAR(10)  NOT NULL,

    -- Java type: java.time.LocalDateTime. WHY : Assumptions: zone-less, matching
    --       batch.batch_run's own timestamps, because the deployment runs one
    --       cluster in one region and the value is an operational breadcrumb
    --       rather than a business fact. Microsecond precision matches the
    --       26-character stamp AAP section 0.4.1.3 fixes for every migrated
    --       timestamp, so this column and the migrated ones round-trip alike.
    updated_at      TIMESTAMP(6) NOT NULL,

    -- WHY : Alternatives Considered: a surrogate identity key with a uniqueness
    --       constraint on feed_name, which is the shape batch.batch_run uses and
    --       whose rationale there was that both parts of its natural key are
    --       externally supplied strings a rename would rewrite. That argument
    --       does not carry here: the feed name is not externally supplied, it is
    --       the record-layout name this repository declares, and there is exactly
    --       one row per feed for the lifetime of the deployment -- so a surrogate
    --       would add a column, a sequence and an index while the natural key
    --       stays the only way anything ever addresses the row.
    CONSTRAINT pk_daily_feed_watermark PRIMARY KEY (feed_name),

    -- WHY : Assumptions: zero is admitted and negatives are not. Zero is the
    --       "nothing consumed" position a first advance may legitimately be
    --       compared against, and ingest_seq itself starts at one, so no stored
    --       ordinal can be negative unless something has computed one -- which is
    --       a defect worth failing on rather than storing.
    CONSTRAINT ck_daily_feed_watermark_ordinal CHECK (last_ingest_seq >= 0),

    -- WHY : Assumptions: both identifying strings are checked non-blank rather
    --       than merely NOT NULL, because a blank run identifier passes NOT NULL
    --       and is indistinguishable from a missing one when an operator reads
    --       the row. The check is btrim-based so a value of spaces is refused too.
    -- WHY : Trade-offs: batch.batch_run declares no such check on its own run_id
    --       and relies on the entity's constructor to refuse a blank. That
    --       asymmetry is deliberate rather than an inconsistency to remove: that
    --       table is written only by the ledger writer, which has one caller, and
    --       this one records a bound the posting step then reads back, so it is
    --       worth refusing a meaningless row at the database as well. The cost is
    --       one check the Java also enforces.
    CONSTRAINT ck_daily_feed_watermark_feed_name CHECK (btrim(feed_name) <> ''),
    CONSTRAINT ck_daily_feed_watermark_run_id CHECK (btrim(run_id) <> ''),

    -- WHY : Assumptions: the business-date token is checked for its WIDTH and not
    --       for a date layout, which is exactly the check
    --       com.carddemo.batch.dto.BusinessDate performs and for the reason that
    --       type records: it is blind to what the ten characters mean, because
    --       both the compact and the separated layout are legitimate and no
    --       reference program parses the token before using it. A layout check
    --       here would reject the compact form the reference itself injects.
    CONSTRAINT ck_daily_feed_watermark_business_date
        CHECK (char_length(business_date) = 10)
);


-- WHY : Assumptions: NO seed row is inserted, and absence of a row is the
--       documented spelling of "this feed has never been consumed". The
--       alternative -- inserting one row per known feed with an ordinal of zero
--       -- would let the read be a plain SELECT, and it was declined because it
--       puts an inventory of feeds into a migration that would then have to be
--       amended whenever one was added, and because it invents values for
--       run_id, business_date and updated_at that no run produced. A migration
--       that records a timestamp asserts an event happened. Absence asserts
--       nothing, which is the truth before the first pass.
-- WHY : Assumptions: NO index beyond the primary key. The table holds one row
--       per feed -- one row in this deployment -- and is read by primary key and
--       written by primary key. Any further index would be a plan the planner
--       never chooses over a single-row primary-key lookup.

COMMENT ON TABLE batch.daily_feed_watermark IS
    'How far into each accumulating feed the posting step has already consumed. '
    'One row per feed, keyed by record-layout name. The reference needed no such '
    'record because app/jcl/POSTTRAN.jcl supplied its feed as a flat dataset that '
    'was replaced between runs; the target''s feed is a table that accumulates, so '
    'this row is what makes a pass consume its own input instead of every row ever '
    'loaded. Absence of a row means the feed has never been consumed.';

COMMENT ON COLUMN batch.daily_feed_watermark.feed_name IS
    'Record-layout name of the feed, as the migration registry and the '
    'verification passes spell it -- DALYTRAN for ledger.daily_transactions. The '
    'primary key.';

COMMENT ON COLUMN batch.daily_feed_watermark.last_ingest_seq IS
    'Ingestion ordinal of the last row consumed, as an EXCLUSIVE lower bound for '
    'the next pass: the walk reads ingest_seq strictly greater than this. Only ever '
    'advances.';

COMMENT ON COLUMN batch.daily_feed_watermark.run_id IS
    'Orchestrator execution that last advanced this watermark. Diagnostic only; '
    'nothing branches on it.';

COMMENT ON COLUMN batch.daily_feed_watermark.business_date IS
    'Injected business-date TOKEN of the run that last advanced this watermark, ten '
    'characters stored verbatim in whichever of the two accepted layouts the run '
    'received -- 2022071800 or 2022-07-18. Diagnostic only: the walk is NOT filtered '
    'by date, because the reference posts every record its dataset holds whatever '
    'date it originated on.';

COMMENT ON COLUMN batch.daily_feed_watermark.updated_at IS
    'When the watermark was last advanced, zone-less to match batch.batch_run and '
    'at microsecond precision to match every migrated timestamp.';
