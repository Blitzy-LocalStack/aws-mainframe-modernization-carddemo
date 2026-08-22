-- =============================================================================
-- services/batch-service/src/main/resources/db/migration/V4__batch_posting_reject_outbox.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Create batch.posting_reject_outbox, one row per daily-feed record a posting
--   run has accounted for, carrying the verbatim 430-byte reject image for the
--   records that were rejected. It is the durable form of two things the posting
--   step previously held only in the memory and the ephemeral disk of the task
--   that produced them: the reject DATASET and the run's two counters.
--
-- Why this table exists (the defect it closes):
--   The posting step commits one transaction per feed record. That transaction
--   carries the record's writes -- the posted ledger row, the category balance
--   and the account master, or the decomposed reject row -- and it also carries
--   the watermark advance that accounts for the record, so the position and the
--   work it stands for commit together.
--
--   The 430-byte reject IMAGE was outside that transaction. It was appended to a
--   temporary file on the task's own disk while the walk ran, and that file was
--   uploaded to its dataset generation only after the walk finished. A failure
--   between the last commit and a durable object -- an unreachable bucket, a
--   denied upload, a task killed mid-stage -- therefore lost the file while
--   every row it described stayed committed and the watermark stayed advanced.
--
--   The redrive then read strictly above that watermark, found nothing, and had
--   nothing left to rebuild from: it staged a ZERO-BYTE reject dataset, reported
--   both counters as zero, and graded itself clean -- while the rejects it was
--   reporting on sat committed in ledger.transaction_rejects. Two obligations
--   were lost at once. The dataset that app/jcl/POSTTRAN.jcl:34-38 allocates as
--   DSN=AWS.M2.CARDDEMO.DALYREJS(+1) was published empty, and the warn tier that
--   app/cbl/CBTRN02C.cbl:229-230 raises whenever WS-REJECT-COUNT is above zero
--   was published as clean, which is the tier the downstream COND=(4,LT) step at
--   app/jcl/TRANBKP.jcl:51 reads.
--
--   This table closes both by moving the image INSIDE the record's own
--   transaction. The image, the reject row and the watermark advance now commit
--   or roll back as one, so a redrive can rebuild the dataset byte for byte and
--   recount the run from stored rows however many attempts it took.
--
-- Preconditions (what must already be true when Flyway applies this file):
--   - V1__batch.sql, V2__batch_feed_watermark.sql and V3__batch_run_contract_
--     restatement.sql have been applied, so the schema `batch` exists, is owned
--     by carddemo_batch, and holds batch.batch_run, batch.daily_feed_watermark
--     and the framework's job repository. This file adds one table beside them
--     and alters nothing that already exists.
--   - The connecting role is carddemo_batch, so the table below is created owned
--     by it and V0 section 4's default privileges fire for it. Those defaults
--     grant the runtime role SELECT, INSERT and UPDATE and deliberately no
--     DELETE, which is why a staged row below is MARKED and never removed.
--   - Flyway is scoped to this module's classpath location and to the `batch`
--     schema, exactly as for V1 through V3. Nothing here reaches outside it.
--   - The persistence provider runs in validate-only mode, so every column name
--     and type below is half of a two-way contract with
--     com.carddemo.batch.domain.PostingRejectOutbox. The Java type each column
--     requires is stated at the column.
--
-- Post-state established:
--   - One table, batch.posting_reject_outbox, with nine columns, a surrogate
--     identity primary key, one uniqueness constraint over the natural key of an
--     accounted record, and six value-domain and pairing checks.
--   - Table and column comments carrying the contract into the database, so an
--     operator inspecting a row sees what it means without reading this file.
--   - NO index beyond the two the constraints create, NO seed row, and no
--     privilege change.
--
-- Fails when:
--   - The schema `batch` does not exist or the connecting role cannot create in
--     it, which both mean V1 has not been applied against this database.
--   - A column name or type below disagrees with the entity mapping; the
--     validate-only pass refuses to start, which is the point of that mode.
--   - The filename is altered. Flyway recognises a versioned migration only in
--     the exact V<version>__<description>.sql form, so a single underscore where
--     the two-underscore separator belongs makes the file invisible -- the table
--     is then never created and the posting step fails on its first insert.
--
-- Derivation:
--   No baseline record layout stands behind this table, and one baseline record
--   stands behind one of its columns. The reject IMAGE column is the 430 bytes
--   app/cbl/CBTRN02C.cbl:83-84 declares as FD-REJECT-RECORD PIC X(350) followed
--   by FD-VALIDATION-TRAILER PIC X(80), the same width app/jcl/POSTTRAN.jcl:36
--   allocates as LRECL=430. Everything else is the durable form of state the
--   reference kept in a dataset's lifecycle and in working storage, because a
--   job step that could not be redriven had nowhere to keep it. Everything under
--   app/** is reference-only, cited by path and line, never modified.
--
-- Governing convention:
--   docs/CODE_DOCUMENTATION_STANDARD.md, whose SQL section requires a header
--   block plus a why-comment on each non-obvious constraint or index. No linter
--   reads this file, so review is the only gate on that obligation.
--
-- WHY (non-obvious design decisions, argued at the point of use below):
--   - Alternatives Considered: deferring the watermark advance out of the
--     record's transaction; re-deriving the dataset from
--     ledger.transaction_rejects; storing only the rejected records; a single
--     mutable per-run summary row. All four rejected -- see the block below.
--   - Assumptions: one row per ACCOUNTED record and not one per REJECT, which is
--     what makes the processed counter reconstructible. Argued at the table.
--   - Trade-offs: the image is stored a second time, beside the decomposed reject
--     row another schema already holds. Argued at the image column.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Alternatives Considered, in the order they were evaluated and rejected.
--
-- (1) Move the watermark advance OUT of the record's transaction and write it
--     once, after the dataset is durable, so a failed stage re-presents the
--     whole window and the next attempt rebuilds the stream by re-reading the
--     feed. Rejected because posting is not idempotent per record and the cure
--     is worse than the defect. Each accepted record mints a NEW transaction
--     identifier and accumulates its amount into the account master, so a
--     re-presented record that already committed is posted a second time --
--     a duplicated ledger row and a double-counted balance, both individually
--     valid, so no reject is written and no return code changes. The watermark
--     exists to prevent exactly that, and V2's own header records the night the
--     absence of it re-posted a previous night's feed.
--
-- (2) Re-derive the dataset from ledger.transaction_rejects, which already holds
--     the three parts the 430-byte record is composed of. Rejected on two
--     independent grounds, either sufficient. That table carries no run
--     discriminator -- no run identifier, no business date and no generation --
--     and its own repository contract records that this is deliberate, so a
--     query against it aggregates every reject ever loaded into the schema and
--     cannot answer "which rejects belong to THIS run". And its shape is not
--     this module's to change: transaction-service owns the `ledger` schema and
--     declares that table, while this module writes into it under a narrowly
--     scoped grant and declares no structure there at all.
--
-- (3) Store only the REJECTED records, since the reject stream is what was lost.
--     Rejected because it recovers one of the two lost obligations and leaves the
--     other broken. The step publishes TWO counters -- app/cbl/CBTRN02C.cbl:227
--     prints the processed count and :228 the rejected count -- and a redrive
--     that walks no new rows can reconstruct the rejected count from rejects
--     alone while still reporting zero processed, which is precisely the false
--     report this table exists to prevent. One row per accounted record makes
--     both counts a count of rows.
--
-- (4) One mutable summary row per run, holding the two counters and a staged
--     flag. Rejected because it cannot carry the images -- the dataset would
--     still have nowhere durable to come from -- and because a row rewritten
--     once per feed record turns a night's insert-only workload into a night of
--     updates to a single row, which serialises every record behind the previous
--     one's row lock. The counters are derived from this table instead, so there
--     is no second place for them to disagree with.
-- -----------------------------------------------------------------------------

CREATE TABLE batch.posting_reject_outbox (

    -- Java type: Long (nullable on an unflushed instance, because the database
    --       assigns the value on insert).
    -- WHY : Alternatives Considered: keying the row on (run_id, feed_name,
    --       ingest_seq) directly, which is the natural key the uniqueness
    --       constraint below already declares. Rejected for the reason
    --       batch.batch_run gives for the same shape: two of the three parts are
    --       externally supplied strings, so the key would be wide, and a run
    --       identifier is the kind of value an orchestration change renames. A
    --       surrogate ordinal keeps the natural key enforced without making every
    --       row address depend on it.
    -- WHY : Assumptions: GENERATED BY DEFAULT rather than ALWAYS, matching
    --       batch.batch_run.id and ledger.transaction_rejects.reject_seq, so a
    --       restore or a replay can supply the original ordinal explicitly. This
    --       module never exercises that: it always lets the database assign.
    outbox_seq        BIGINT       GENERATED BY DEFAULT AS IDENTITY,

    -- Java type: String. WHY : Assumptions: the run identifier is a CONTROL value
    --       here, unlike the diagnostic copy batch.daily_feed_watermark keeps. It
    --       is the key the redrive reads its own predecessor's work back by, so a
    --       row written under the wrong run is a row a redrive cannot find. The
    --       width matches batch.batch_run.run_id so the two join by eye.
    run_id            VARCHAR(80)  NOT NULL,

    -- Java type: String. WHY : Assumptions: the feed is named by RECORD LAYOUT --
    --       'DALYTRAN' -- and at the same width as
    --       batch.daily_feed_watermark.feed_name, because the ordinal stored
    --       beside it is that watermark's ordinal and the two tables have to be
    --       readable against each other. Carrying the column at all, rather than
    --       assuming the one feed this step walks, keeps the uniqueness constraint
    --       below true if a second feed is ever posted by the same run.
    feed_name         VARCHAR(30)  NOT NULL,

    -- Java type: long (a primitive, because the column is NOT NULL and every
    --       accounted record has an ordinal).
    -- WHY : Assumptions: this is ledger.daily_transactions.ingest_seq, the same
    --       ordinal batch.daily_feed_watermark stores, and it is what ORDERS the
    --       rebuilt dataset. The walk reads the feed strictly ascending by this
    --       column, so replaying stored rows in ascending order reproduces the
    --       byte sequence the walk would have appended -- including across
    --       attempts, since a later attempt starts above the watermark the earlier
    --       one advanced and therefore contributes only higher ordinals.
    -- WHY : Assumptions: BIGINT because the source column is BIGINT GENERATED BY
    --       DEFAULT AS IDENTITY in services/transaction-service's V1__ledger.sql.
    --       A narrower type would silently truncate once the accumulating feed
    --       held more rows than it admits.
    ingest_seq        BIGINT       NOT NULL,

    -- Java type: String. WHY : Assumptions: the ten-character business-date TOKEN
    --       stored VERBATIM and not as a DATE, for the reason
    --       com.carddemo.batch.dto.BusinessDate records: the reference injects the
    --       compact form at app/jcl/INTCALC.jcl:22 while the migrated chain injects
    --       the separated ISO form, both are legitimate, and a DATE column would
    --       force a parse that the compact form fails. It is the generation
    --       partition this run's dataset lands under, kept so an operator can tell
    --       which partition a stored image belongs to without re-deriving it.
    business_date     VARCHAR(10)  NOT NULL,

    -- Java type: String, mapped with @JdbcTypeCode(SqlTypes.CHAR) so the provider
    --       binds and extracts the fixed-width form rather than a variable one.
    -- WHY : Assumptions: NULL means the record was POSTED and null is the only
    --       spelling of that. A posted record still gets a row -- that is what
    --       makes the processed counter a count of rows -- and it contributes no
    --       bytes to the dataset, so the column that would hold its bytes is
    --       absent rather than blank. The check below refuses an all-blank image
    --       so the two states cannot be confused.
    -- WHY : Assumptions: CHAR and not VARCHAR, at exactly 430. The type pads a
    --       stored value out to its declared width and returns it padded, which is
    --       what guarantees the read-back image is the same 430 characters that
    --       were written: the record's own trailing blanks are CONTENT here,
    --       because app/cbl/CBTRN02C.cbl:180-182 renders the 76-character reason
    --       description blank-padded. ledger.transaction_rejects.raw_record is
    --       declared the same way at 350 for the same reason.
    -- WHY : Trade-offs: the image is stored a second time, beside the decomposed
    --       row ledger.transaction_rejects already holds, so a reject occupies
    --       roughly 780 characters across two schemas instead of 430 in one. That
    --       is accepted because the two rows answer different questions and only
    --       one of them can answer this one: the ledger row is the permanent,
    --       run-blind audit record every reject ever posted appears in, and this
    --       one is the per-run staging obligation a redrive resolves. Deriving
    --       either from the other is alternative (2) above, and it is unavailable
    --       in that direction for want of a run discriminator.
    reject_record     CHAR(430),

    -- Java type: java.time.LocalDateTime. WHY : Assumptions: zone-less and at
    --       microsecond precision, matching batch.batch_run and
    --       batch.daily_feed_watermark, because the deployment runs one cluster in
    --       one region and the value is an operational breadcrumb rather than a
    --       business fact. It is read from the module's injected Clock and never
    --       from the database's own clock, so a test can fix it and a rerun of a
    --       given night is reproducible.
    accounted_at      TIMESTAMP(6) NOT NULL,

    -- Java type: java.time.LocalDateTime, nullable.
    -- WHY : Assumptions: this is the marker that the image has reached a DURABLE
    --       object, and it is written only after the upload returns. A row written
    --       before that -- optimistically, at insert -- would say the dataset was
    --       published when the object may not exist, which is the exact confusion
    --       this table was added to remove.
    -- WHY : Trade-offs: the marker is advisory rather than a gate. The assembly
    --       replays EVERY reject row of the run, not only the unmarked ones,
    --       because a retry after a failure between the upload and this mark must
    --       rewrite the whole object: the generation coordinate is memoised per
    --       run, so the retry overwrites the same key, and a partial rewrite from
    --       unmarked rows alone would replace a complete dataset with its tail.
    --       What the marker buys is the operator's answer to "did this run's
    --       rejects reach an object, and which one".
    staged_at         TIMESTAMP(6),

    -- Java type: String, nullable. WHY : Assumptions: the width is the object
    --       store's own key limit of 1024 characters, so no key the store accepted
    --       can be refused here. The keys this module composes are far shorter --
    --       a family, a date partition, a four-digit generation and an object name
    --       -- and pinning the column to their current length would make a
    --       renamed family a schema change.
    staged_object_key VARCHAR(1024),

    CONSTRAINT pk_posting_reject_outbox PRIMARY KEY (outbox_seq),

    -- WHY : Assumptions: a record is accounted for ONCE per run, and this is the
    --       constraint that says so. Under normal operation it never fires,
    --       because a redrive starts strictly above the watermark the previous
    --       attempt advanced and so cannot re-present a record already accounted
    --       for. That is exactly why it is worth declaring: if it ever does fire,
    --       the watermark and this table have diverged, and failing the insert
    --       reports that while double-counting a night's rejects would not.
    -- WHY : Assumptions: this index is also the ACCESS PATH for every read this
    --       table serves -- the two per-run counts and the ordered replay of a
    --       run's images -- because all three qualify on run_id and feed_name for
    --       equality and then range over or order by ingest_seq, which is the
    --       leading-equality-then-range shape a composite b-tree serves directly.
    --       No further index is declared for that reason.
    CONSTRAINT uq_posting_reject_outbox_run_record UNIQUE (run_id, feed_name, ingest_seq),

    -- WHY : Assumptions: zero is admitted and negatives are not, matching
    --       batch.daily_feed_watermark's ordinal check. Feed ordinals start at
    --       one, so no stored value can be negative unless something computed it,
    --       which is a defect worth failing on rather than storing.
    CONSTRAINT ck_posting_reject_outbox_ordinal CHECK (ingest_seq >= 0),

    -- WHY : Assumptions: both identifying strings are checked non-blank rather
    --       than merely NOT NULL, because a blank run identifier passes NOT NULL
    --       and would then be indistinguishable from a missing one -- and here it
    --       would silently detach a row from the run whose redrive has to find it.
    --       The checks are btrim-based so a value of spaces is refused too.
    CONSTRAINT ck_posting_reject_outbox_run_id CHECK (btrim(run_id) <> ''),
    CONSTRAINT ck_posting_reject_outbox_feed_name CHECK (btrim(feed_name) <> ''),

    -- WHY : Assumptions: the business-date token is checked for its WIDTH and not
    --       for a date layout, which is the same check
    --       com.carddemo.batch.dto.BusinessDate performs and for the reason that
    --       type records: both the compact and the separated layout are legitimate,
    --       so a layout check here would reject the form the reference injects.
    CONSTRAINT ck_posting_reject_outbox_business_date
        CHECK (char_length(business_date) = 10),

    -- WHY : Assumptions: an ALL-BLANK image is refused, which is what keeps "this
    --       record posted" and "this record was rejected" distinguishable in one
    --       nullable column. A fixed-width character column pads, so a caller that
    --       stored an empty string would produce 430 blanks and a dataset would
    --       gain 430 bytes of nothing; the comparison reads as blank because
    --       PostgreSQL strips a fixed-width value's trailing blanks on the way to
    --       text, so this refuses both the empty string and any run of spaces.
    CONSTRAINT ck_posting_reject_outbox_reject_record
        CHECK (reject_record IS NULL OR btrim(reject_record) <> ''),

    -- WHY : Assumptions: the two staging members move TOGETHER or not at all, and
    --       only an image can be staged. A staged instant with no key names no
    --       object, a key with no instant records a publication that never
    --       happened, and a posted record has no bytes to publish -- so all three
    --       states are refused here rather than left for a reader to notice.
    -- WHY : Assumptions: the branches are written with explicit IS NULL and IS NOT
    --       NULL tests rather than as a comparison, because a bare comparison
    --       against null evaluates to unknown and a CHECK admits unknown, which
    --       would make the whole constraint vacuous on exactly the rows it exists
    --       to constrain. batch.batch_run's lifecycle check records the same
    --       reasoning.
    CONSTRAINT ck_posting_reject_outbox_staging
        CHECK ((staged_at IS NULL AND staged_object_key IS NULL)
               OR (staged_at IS NOT NULL
                   AND staged_object_key IS NOT NULL
                   AND reject_record IS NOT NULL))
);


-- WHY : Assumptions: NO seed row, because a row here asserts that a run
--       accounted for a record and no migration has done that.
-- WHY : Assumptions: NO privilege statement. V0 section 4 grants the runtime role
--       SELECT, INSERT and UPDATE by default on tables this owner creates, and
--       that is the whole of what this table needs: rows are inserted per record,
--       read back per run, and marked staged by an UPDATE. Nothing deletes from
--       it, which is deliberate -- the runtime role holds no DELETE privilege at
--       all, so a design that drained rows as it staged them would have failed on
--       its first successful upload.
-- WHY : Trade-offs: rows therefore accumulate, one per feed record posted for the
--       lifetime of the deployment. That is the same growth profile
--       ledger.daily_transactions and ledger.transaction_rejects already carry for
--       the same audit reason, and pruning it is an operator action against a
--       retention policy rather than something a nightly step may decide.

COMMENT ON TABLE batch.posting_reject_outbox IS
    'One row per daily-feed record a posting run accounted for, carrying the '
    'verbatim 430-byte reject image for the records it rejected. Written inside '
    'each record''s own transaction, together with that record''s writes and the '
    'watermark advance, so a run whose reject dataset failed to reach the object '
    'store can rebuild it byte for byte on a redrive and can report the run''s '
    'real processed and rejected counters rather than the current attempt''s. The '
    'reference needed no such record: it wrote its reject stream straight to '
    'AWS.M2.CARDDEMO.DALYREJS(+1) inside the same job step and had no redrive to '
    'serve.';

COMMENT ON COLUMN batch.posting_reject_outbox.outbox_seq IS
    'Surrogate insert ordinal, assigned by the database. Identifies the row and '
    'orders nothing: the dataset is ordered by ingest_seq.';

COMMENT ON COLUMN batch.posting_reject_outbox.run_id IS
    'Orchestrator execution that accounted for the record. A control value, not a '
    'diagnostic one: it is the key a redrive of that execution reads its own '
    'predecessor''s work back by.';

COMMENT ON COLUMN batch.posting_reject_outbox.feed_name IS
    'Record-layout name of the feed the ordinal belongs to -- DALYTRAN for '
    'ledger.daily_transactions -- spelled as batch.daily_feed_watermark spells it.';

COMMENT ON COLUMN batch.posting_reject_outbox.ingest_seq IS
    'Ingestion ordinal of the accounted feed row. Ascending order by this column '
    'is the order the reject dataset is rebuilt in, which is the order the walk '
    'that produced it appended.';

COMMENT ON COLUMN batch.posting_reject_outbox.business_date IS
    'Injected business-date TOKEN of the accounting run, ten characters stored '
    'verbatim in whichever accepted layout the run received -- 2022071800 or '
    '2022-07-18. It is the generation partition this run''s reject dataset lands '
    'under.';

COMMENT ON COLUMN batch.posting_reject_outbox.reject_record IS
    'The rejected record as the exact 430 characters app/jcl/POSTTRAN.jcl:36 '
    'allocates -- 350 of verbatim daily transaction, a four-digit reason code and '
    'a 76-character description -- or NULL when the record was posted rather than '
    'rejected.';

COMMENT ON COLUMN batch.posting_reject_outbox.accounted_at IS
    'When the record''s unit of work committed, from the module''s injected clock. '
    'Zone-less to match batch.batch_run and at microsecond precision to match '
    'every migrated timestamp.';

COMMENT ON COLUMN batch.posting_reject_outbox.staged_at IS
    'When the object holding this image became durable, or NULL while it has not. '
    'Written only after the upload returns; advisory, since a rebuild replays every '
    'reject row of the run rather than only the unmarked ones.';

COMMENT ON COLUMN batch.posting_reject_outbox.staged_object_key IS
    'Object key of the dataset generation this image reached, or NULL while it has '
    'reached none. Paired with staged_at by a check constraint.';
