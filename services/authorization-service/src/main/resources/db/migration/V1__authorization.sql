-- =============================================================================
-- services/authorization-service/src/main/resources/db/migration/V1__authorization.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Declares the four tables of the authorization schema: the two migrated IMS
--   segments, the migrated Db2 fraud table, and the transactional outbox that
--   has no baseline counterpart at all.
--
--     pending_auth_summary  IMS root segment  PAUTSUM0, 100 bytes
--                           app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd L28
--                           fields at cpy/CIPAUSMY.cpy L19-L31
--     pending_auth_detail   IMS child segment PAUTDTL1, 200 bytes
--                           same file L36, fields at cpy/CIPAUDTY.cpy L19-L54
--     auth_fraud            Db2 table CARDDEMO.AUTHFRDS
--                           ddl/AUTHFRDS.ddl L2-L28, 26 columns and a two-column key
--     auth_reply_outbox     no baseline counterpart; see the WHY on that table
--
-- Corresponding baseline contract:
--   The baseline splits this context across TWO datastores and joins them with a
--   two-phase commit -- the hierarchical database holds the two segments and the
--   relational database holds the fraud table. Here they are four tables in ONE
--   PostgreSQL schema, so the distributed transaction is ELIMINATED rather than
--   emulated: a fraud mark and the detail row it marks commit together in one
--   local transaction. That is the migration's single largest simplification in
--   this context and it is recorded in
--   docs/architecture/cobol-to-service-traceability.md as a documented
--   divergence rather than left to be inferred from the absence of a second
--   datastore.
--
-- Invocation:
--   Flyway applies this script once, as a unit, resolved by its V1 prefix. It
--   takes no parameter. It runs as the carddemo_authorization role, which
--   data-migration/sql/V0__schemas_and_roles.sql creates and makes the owner of
--   the authorization schema, so every object below is owned by that role and no
--   GRANT is needed for the service's own access.
--
-- Errors:
--   - A missing authorization schema aborts the migration; V0 creates it and must
--     run first.
--   - A packed-decimal value that overflows a declared NUMERIC precision is
--     rejected by the server at insert time rather than truncated. That is the
--     intended behaviour: the ETL decodes COMP-3 at the edge and a value that
--     will not fit is a decoding defect, which is better reported than stored.
--
-- WHY : Assumptions: every occurrence of the schema name below is DOUBLE QUOTED,
--       because authorization is a PostgreSQL reserved keyword and
--       CREATE SCHEMA AUTHORIZATION <role> is itself valid syntax. Written bare in
--       a qualified table name the parser reports "syntax error at or near
--       \"authorization\"", which points nowhere near the cause. The quoting does
--       not change the resulting name -- an unquoted identifier folds to lower case
--       and this one is already lower case -- so pg_namespace.nspname is exactly
--       authorization, which is what this module's Flyway schema property and its
--       JPA schema attributes expect. The same rule and the same reasoning are
--       recorded at data-migration/sql/V0__schemas_and_roles.sql L471-L493, which
--       creates the schema; this file follows it rather than restating the choice.
-- WHY : Assumptions: no packed-decimal, zoned-decimal or EBCDIC representation
--       survives into any column here. Every COMP-3 field of the two segments is
--       decoded once, at the ETL boundary, and stored as exact NUMERIC; every
--       character field is stored as CHAR or VARCHAR. Storing the packed bytes
--       would make every consumer carry a codec and would put the sign nibble
--       inside the database, where no SQL predicate can read it.
-- WHY : Assumptions: money is NUMERIC with scale 2 in every case and never a
--       binary floating-point type, in this schema as in every other. The two
--       segments declare their amounts as S9(09)V99 and S9(10)V99 COMP-3, so the
--       precisions below are 11 and 12 respectively -- nine or ten integer digits
--       plus two fractional ones -- and the scale is stated explicitly rather
--       than defaulted.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- pending_auth_summary -- the per-account authorization summary.
--
-- WHY : Assumptions: PA-ACCT-ID is PIC S9(11) COMP-3 at cpy/CIPAUSMY.cpy L19, so
--       eleven signed decimal digits, which BIGINT holds with room to spare and
--       INTEGER does not. It is the primary key here because it is the IMS root
--       segment's key: one summary row per account, which is what makes the
--       child detail rows addressable by account plus timestamp below.
-- -----------------------------------------------------------------------------
CREATE TABLE "authorization".pending_auth_summary (
    account_id            BIGINT         NOT NULL,
    customer_id           BIGINT         NOT NULL,
    auth_status           CHAR(1),

    -- WHY : Assumptions: PA-ACCOUNT-STATUS is PIC X(02) OCCURS 5 TIMES at
    --       cpy/CIPAUSMY.cpy L22, and it becomes FIVE discrete columns rather
    --       than one array column. The arity is fixed at five by the copybook, so
    --       five columns let the schema itself enforce it, keep every value
    --       addressable by an ordinary predicate, and stay portable to a JPA
    --       mapping without a converter. A PostgreSQL array would express the
    --       shape more compactly and would accept a sixth element that the
    --       baseline record cannot hold.
    account_status_1      CHAR(2),
    account_status_2      CHAR(2),
    account_status_3      CHAR(2),
    account_status_4      CHAR(2),
    account_status_5      CHAR(2),

    credit_limit          NUMERIC(11,2)  NOT NULL DEFAULT 0,
    cash_limit            NUMERIC(11,2)  NOT NULL DEFAULT 0,
    credit_balance        NUMERIC(11,2)  NOT NULL DEFAULT 0,
    cash_balance          NUMERIC(11,2)  NOT NULL DEFAULT 0,

    -- WHY : Assumptions: the two counts are PIC S9(04) COMP at L26 and L27, a
    --       signed binary halfword, so SMALLINT is the exact target rather than a
    --       narrowing of a wider type.
    approved_auth_count   SMALLINT       NOT NULL DEFAULT 0,
    declined_auth_count   SMALLINT       NOT NULL DEFAULT 0,

    approved_auth_amount  NUMERIC(11,2)  NOT NULL DEFAULT 0,
    declined_auth_amount  NUMERIC(11,2)  NOT NULL DEFAULT 0,

    -- WHY : Assumptions: FILLER PIC X(34) at L31 pads the segment to its declared
    --       100 bytes and carries no data, so it is DROPPED rather than stored.
    --       The drop is recorded here and in
    --       docs/architecture/data-model-and-schema-mapping.md, because an
    --       unrecorded drop is indistinguishable from an overlooked field.

    CONSTRAINT pk_pending_auth_summary PRIMARY KEY (account_id)
);


-- -----------------------------------------------------------------------------
-- pending_auth_detail -- one row per authorization message beneath a summary.
--
-- WHY : Assumptions: the segment key is the two-part PA-AUTHORIZATION-KEY at
--       cpy/CIPAUDTY.cpy L19-L21 -- PA-AUTH-DATE-9C PIC S9(05) COMP-3 and
--       PA-AUTH-TIME-9C PIC S9(09) COMP-3 -- and it becomes two INTEGER columns
--       rather than one composite string or one timestamp. Two columns keep each
--       component independently comparable, which is what the descending
--       most-recent-first ordering of the detail list depends on; folding them
--       into a timestamp would additionally require inventing a century for a
--       five-digit date the baseline does not qualify.
-- WHY : Assumptions: the primary key is (account_id, auth_date, auth_time),
--       which adds the parent's key to the segment's own. The hierarchical
--       database reaches a child only through its root, so the segment key is
--       unique WITHIN one account and not across accounts; a key of the two
--       segment components alone would collide the moment two accounts recorded
--       an authorization in the same hundredth of a second.
-- -----------------------------------------------------------------------------
CREATE TABLE "authorization".pending_auth_detail (
    account_id            BIGINT         NOT NULL,
    auth_date             INTEGER        NOT NULL,
    auth_time             INTEGER        NOT NULL,

    auth_orig_date        CHAR(6),
    auth_orig_time        CHAR(6),

    card_num              CHAR(16)       NOT NULL,
    auth_type             CHAR(4),
    card_expiry_date      CHAR(4),
    message_type          CHAR(6),
    message_source        CHAR(6),
    auth_id_code          CHAR(6),
    auth_resp_code        CHAR(2),
    auth_resp_reason      CHAR(4),

    -- WHY : Assumptions: PA-PROCESSING-CODE is PIC 9(06) at L32, six UNSIGNED
    --       decimal digits used as a code rather than counted with, so it is
    --       stored as CHAR(6). An integer target would drop a leading zero, and a
    --       leading zero in a code is data.
    processing_code       CHAR(6),

    transaction_amount    NUMERIC(12,2)  NOT NULL DEFAULT 0,
    approved_amount       NUMERIC(12,2)  NOT NULL DEFAULT 0,

    -- WHY : Refactoring Rationale: the baseline spells this field
    --       PA-MERCHANT-CATAGORY-CODE at L35, transposing the second and third
    --       vowels of "category". The misspelling is corrected here, and the
    --       correction is one of exactly three in the whole migration, each
    --       recorded in docs/architecture/data-model-and-schema-mapping.md so the
    --       lineage stays traceable. Carrying the typo forward would propagate it
    --       into a column name, a JPA field, a DTO member and a browser client.
    merchant_category_code CHAR(4),

    acqr_country_code     CHAR(3),
    pos_entry_mode        SMALLINT,
    merchant_id           CHAR(15),
    merchant_name         VARCHAR(22),
    merchant_city         CHAR(13),
    merchant_state        CHAR(2),
    merchant_zip          CHAR(9),
    transaction_id        CHAR(15),

    match_status          CHAR(1)        NOT NULL DEFAULT 'P',
    auth_fraud            CHAR(1),
    fraud_report_date     CHAR(8),

    CONSTRAINT pk_pending_auth_detail PRIMARY KEY (account_id, auth_date, auth_time),

    -- WHY : Assumptions: the four accepted values are the 88-level condition
    --       names at cpy/CIPAUDTY.cpy L47-L50 -- pending, declined,
    --       pending-expired and matched -- and the constraint is what carries a
    --       COBOL value domain into the target. Without it the column would accept
    --       any single character and the purge job's expiry transition could write
    --       a value no reader recognises.
    CONSTRAINT ck_pending_auth_detail_match_status
        CHECK (match_status IN ('P', 'D', 'E', 'M')),

    -- WHY : Assumptions: PA-AUTH-FRAUD at L51-L53 declares only confirmed and
    --       removed, so NULL is admitted as a third state meaning never marked --
    --       which is what the baseline's blank-filled field means. Writing blank
    --       instead of NULL was considered and rejected: a CHAR(1) holding a space
    --       compares unequal to NULL in aggregation, so a count of unmarked rows
    --       would silently depend on which representation the writer used.
    CONSTRAINT ck_pending_auth_detail_auth_fraud
        CHECK (auth_fraud IS NULL OR auth_fraud IN ('F', 'R')),

    -- WHY : Assumptions: a detail row cannot exist without its summary, which is
    --       structurally guaranteed in a hierarchical database -- a child segment
    --       is reachable only beneath its root -- and has to be asserted
    --       explicitly here. ON DELETE CASCADE reproduces the hierarchical delete:
    --       removing a root removes its children with it.
    CONSTRAINT fk_pending_auth_detail_summary
        FOREIGN KEY (account_id) REFERENCES "authorization".pending_auth_summary (account_id)
        ON DELETE CASCADE
);

-- WHY : Assumptions: the detail list is read most-recent-first for one account,
--       which is the order cbl/COPAUS0C.cbl presents it in, so the index carries
--       the two key components DESCENDING beneath the account. A default
--       ascending index would serve the same predicate and would force a sort for
--       every page of the screen that reads it.
CREATE INDEX idx_pending_auth_detail_recent
    ON "authorization".pending_auth_detail (account_id, auth_date DESC, auth_time DESC);

-- WHY : Assumptions: the card number is the other access path, because an
--       authorization arriving from the network names a card and not an account.
--       It is non-unique: one card legitimately has many authorizations.
CREATE INDEX idx_pending_auth_detail_card_num
    ON "authorization".pending_auth_detail (card_num);

-- WHY : Assumptions: the acquirer's transaction identifier is the request's own
--       idempotency key, so this index is the seek that recognises a REDELIVERED
--       request as one already decided. The queue suppresses duplicates only
--       inside its five-minute deduplication window; a redelivery after that
--       window arrives as a new message, and without this lookup it would be
--       decided a second time and the account's counters would double-count it.
-- WHY : Trade-offs: it is UNIQUE, and partial so that the rows the ETL loads
--       from the baseline extract -- which carry no transaction identifier,
--       because the segment permits the field to be blank -- do not collide with
--       one another on a shared null. A non-unique index would answer the same
--       seek at the same cost and would let two rows claim one identifier, which
--       is the exact state the seek exists to prevent.
CREATE UNIQUE INDEX idx_pending_auth_detail_transaction_id
    ON "authorization".pending_auth_detail (transaction_id)
    WHERE transaction_id IS NOT NULL;


-- -----------------------------------------------------------------------------
-- auth_fraud -- the fraud-tagged authorizations, migrated from Db2.
--
-- WHY : Assumptions: the column set is ddl/AUTHFRDS.ddl L2-L27 field for field,
--       and the primary key is its L28 PRIMARY KEY(CARD_NUM, AUTH_TS). The two
--       DECIMAL(12,2) amounts and the DECIMAL(11)/DECIMAL(9) identifiers are
--       carried across as NUMERIC and BIGINT respectively, which is the same
--       mapping rule the two segments above follow.
-- WHY : Refactoring Rationale: FRAUD_RPT_DATE is DATE in the Db2 table and
--       CHAR(08) in the segment at cpy/CIPAUDTY.cpy L54. Both are kept as their
--       source declares them rather than reconciled: the segment's character form
--       is what the ETL reads out of a 200-byte record, and converting it during
--       load would make an unparseable value a load failure rather than a row an
--       operator can see and correct.
-- -----------------------------------------------------------------------------
CREATE TABLE "authorization".auth_fraud (
    card_num              CHAR(16)       NOT NULL,
    auth_ts               TIMESTAMP(6)   NOT NULL,
    auth_type             CHAR(4),
    card_expiry_date      CHAR(4),
    message_type          CHAR(6),
    message_source        CHAR(6),
    auth_id_code          CHAR(6),
    auth_resp_code        CHAR(2),
    auth_resp_reason      CHAR(4),
    processing_code       CHAR(6),
    transaction_amount    NUMERIC(12,2),
    approved_amount       NUMERIC(12,2),
    merchant_category_code CHAR(4),
    acqr_country_code     CHAR(3),
    pos_entry_mode        SMALLINT,
    merchant_id           CHAR(15),
    merchant_name         VARCHAR(22),
    merchant_city         CHAR(13),
    merchant_state        CHAR(2),
    merchant_zip          CHAR(9),
    transaction_id        CHAR(15),
    match_status          CHAR(1),
    auth_fraud            CHAR(1),
    fraud_report_date     DATE,
    account_id            BIGINT,
    customer_id           BIGINT,

    CONSTRAINT pk_auth_fraud PRIMARY KEY (card_num, auth_ts),

    CONSTRAINT ck_auth_fraud_flag
        CHECK (auth_fraud IS NULL OR auth_fraud IN ('F', 'R'))
);

-- WHY : Assumptions: the ordering here is the migrated form of the Db2 index
--       XAUTHFRD, whose declaration orders the card number ascending and the
--       authorization timestamp DESCENDING. Reproducing the direction matters
--       rather than merely reproducing the columns: a fraud review reads the most
--       recent authorizations for a card first, so an all-ascending index would
--       answer the same query with a sort that the baseline's index avoided.
CREATE INDEX idx_auth_fraud_card_recent
    ON "authorization".auth_fraud (card_num ASC, auth_ts DESC);


-- -----------------------------------------------------------------------------
-- auth_reply_outbox -- the transactional outbox. NO BASELINE COUNTERPART.
--
-- WHY : Refactoring Rationale: this table exists to close a window the baseline
--       leaves open, and it is the only table in this schema with no counterpart
--       in the reference system. The authorization consumer reads its request
--       with the no-syncpoint option at cbl/COPAUA0C.cbl L389 and puts its reply
--       with the no-syncpoint option at L753, while committing its database work
--       separately at L335. A failure between the commit at L335 and the put at
--       L753 therefore loses a reply the data says was produced, and no retry
--       recovers it: the request has already been consumed and the decision has
--       already been committed. Writing the reply as a ROW inside the same
--       transaction as the decision makes the reply as durable as the decision,
--       and a publisher then moves committed rows to the queue at least once.
-- WHY : Trade-offs: at-least-once publication is the accepted cost. A publisher
--       that crashes after the send and before marking the row published sends
--       the reply twice. That is safe here specifically because the reply queue
--       is FIFO with content-independent deduplication keyed on the transaction
--       identifier, so a duplicate inside the deduplication interval is discarded
--       by the queue rather than by the consumer. The alternative -- marking the
--       row published before sending -- converts a duplicate into a LOST reply,
--       which is the failure this table exists to eliminate.
-- -----------------------------------------------------------------------------
CREATE TABLE "authorization".auth_reply_outbox (
    -- WHY : Assumptions: a generated identity rather than a natural key. The
    --       natural candidate is the transaction identifier, and it is rejected
    --       because a request redelivered outside the queue's deduplication
    --       interval legitimately produces a second reply row for the same
    --       transaction; a natural key would reject that row and lose the reply.
    outbox_id             BIGINT         GENERATED BY DEFAULT AS IDENTITY,

    -- WHY : Assumptions: the queue URL travels in the ROW rather than being
    --       resolved by the publisher from configuration, because the reply is
    --       routed to the queue the REQUEST nominated. That is the migrated form
    --       of the message-descriptor reply-to field the baseline reads, and
    --       holding it here is what lets the publisher stay ignorant of who asked.
    reply_queue_url       VARCHAR(1024)  NOT NULL,

    -- WHY : Assumptions: the correlation identity is echoed from the request
    --       unaltered, mirroring cbl/COPAUA0C.cbl L745 which moves the saved
    --       inbound identifier into the reply exactly as received.
    correlation_id        VARCHAR(64),

    -- WHY : Assumptions: the ordering group is the CARD NUMBER and the
    --       deduplication key is the TRANSACTION IDENTIFIER, and the two are
    --       stored rather than derived at publication time so that the row is
    --       self-contained: a publisher that had to parse the payload to recover
    --       them would fail to publish a payload it could not parse, which is the
    --       one case where publishing matters most.
    message_group_id      VARCHAR(128)   NOT NULL,
    deduplication_id      VARCHAR(128)   NOT NULL,

    -- WHY : Assumptions: the payload is the six-field delimited reply exactly as
    --       the wire carries it, 63 characters, stored as text rather than as
    --       structured columns. Field order and the delimiter ARE the contract for
    --       a string-format payload, so re-encoding at publication time would be a
    --       second place for that contract to be got wrong.
    payload               TEXT           NOT NULL,
    content_type          VARCHAR(64)    NOT NULL DEFAULT 'text/csv',

    -- WHY : Refactoring Rationale: the baseline sets a five-second message expiry
    --       in the descriptor and the target transport has no per-message
    --       time-to-live at all, so the deadline moves out of the transport and
    --       into the payload contract: this column carries the instant past which
    --       the reply is stale, the publisher declines to send an expired row, and
    --       the consumer of a request drops one whose deadline has passed. The gap
    --       and this resolution are recorded in docs/adr/ADR-004.
    expires_at            TIMESTAMP(6),

    created_at            TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- WHY : Assumptions: publication is recorded by SETTING this column rather
    --       than by DELETING the row, so a published reply remains auditable
    --       alongside the decision it answered. The partial index below is what
    --       keeps the unpublished set cheap to find as the table grows.
    published_at          TIMESTAMP(6),

    attempts              SMALLINT       NOT NULL DEFAULT 0,
    last_error            VARCHAR(256),

    CONSTRAINT pk_auth_reply_outbox PRIMARY KEY (outbox_id)
);

-- WHY : Assumptions: a PARTIAL index over unpublished rows only. The publisher's
--       one query is "the oldest rows not yet published", and the unpublished set
--       is small and bounded by the publication interval while the published set
--       grows without bound. A full index would grow with the table and would be
--       almost entirely dead entries.
CREATE INDEX idx_auth_reply_outbox_unpublished
    ON "authorization".auth_reply_outbox (created_at)
    WHERE published_at IS NULL;
