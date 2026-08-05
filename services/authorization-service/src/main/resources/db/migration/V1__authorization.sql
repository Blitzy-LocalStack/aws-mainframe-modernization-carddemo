-- =============================================================================
-- services/authorization-service/src/main/resources/db/migration/V1__authorization.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Declares the four tables and the two indexes of the authorization schema:
--   the two migrated IMS segments, the migrated Db2 fraud table, and a
--   transactional outbox that has no baseline counterpart at all.
--
--     pending_auth_summary  IMS root segment  PAUTSUM0, 100 bytes
--                           ims/DBPAUTP0.dbd L28, fields at cpy/CIPAUSMY.cpy
--                           L19-L31 (13 05-levels)
--     pending_auth_detail   IMS child segment PAUTDTL1, 200 bytes
--                           ims/DBPAUTP0.dbd L36, fields at cpy/CIPAUDTY.cpy
--                           L19-L54 (27 05-levels, 2 10-levels)
--     auth_fraud            Db2 table CARDDEMO.AUTHFRDS, 26 columns
--                           ddl/AUTHFRDS.ddl L2-L28, index ddl/XAUTHFRD.ddl
--     auth_reply_outbox     no baseline counterpart; see the WHY on that table
--
--   Unless stated otherwise, every citation below is relative to
--   app/app-authorization-ims-db2-mq. That tree, and all of app/**, is
--   REFERENCE-ONLY: it is read as the specification for this file and is never
--   modified, which is why each citation is a path and a line number rather
--   than an edit.
--
-- Corresponding baseline contract:
--   The baseline splits this context across TWO resource managers and joins
--   them with a genuine two-phase commit. One user action -- mark or unmark an
--   authorization as fraud -- writes both: cbl/COPAUS1C.cbl L522 and its
--   EXEC DLI REPL at L525-L528 replace the IMS detail segment, while
--   cbl/COPAUS2C.cbl reaches Db2 through EXEC SQL only (INSERT L141-L198,
--   UPDATE L222-L229) and holds no PCB and no syncpoint of its own, returning
--   uncommitted at its L218-L219. Both programs run under one transaction,
--   CPVD (csd/CRDDEMO2.csd L29 and L36), which CICS coordinates with
--   ACTION(BACKOUT) at L45 and attaches to Db2 through DEFINE DB2ENTRY
--   (AWS01PLN) at L69-L74 with DROLLBACK(YES) at L71 and DEFINE DB2TRAN
--   (CPVDTRAN) at L75-L79, whose L77 reads ENTRY(AWS01PLN) TRANSID(CPVD). The
--   single commit point for both resource managers is the lone EXEC CICS
--   SYNCPOINT at cbl/COPAUS1C.cbl L557-L559, with the rollback path at
--   L565-L568.
--
--   Because all four tables below live in ONE PostgreSQL schema, that
--   distributed transaction is ELIMINATED rather than emulated: the fraud row
--   and the detail row it marks commit together in one local transaction, and
--   there is no second resource manager left to coordinate. A reader who knows
--   the baseline will look for that second resource manager, so it is named
--   here rather than left to be inferred from its absence. This is documented
--   divergence D-6 in docs/architecture/cobol-to-service-traceability.md. The
--   baseline's THREADLIMIT(1) at csd/CRDDEMO2.csd L72 is a throughput ceiling
--   of the Db2 attachment and is likewise not reproduced.
--
-- Invocation:
--   Flyway applies this script once, as a unit, resolved by its V1 version
--   prefix. It takes no parameter and no substitution variable and contains no
--   psql meta-command, so it runs identically under Flyway and under
--   "psql -v ON_ERROR_STOP=1 -f".
--
-- Not declared here:
--   No CREATE SCHEMA, no CREATE ROLE and no GRANT.
--   data-migration/sql/V0__schemas_and_roles.sql owns all three: it creates the
--   per-service login roles in the loop at its L381 and the schema itself at
--   its L539, and it states that boundary for every per-service migration at
--   its L11-L16. This file therefore declares tables and indexes only.
--
-- Raises:
--   - SQLSTATE 42P01 (undefined_table) at the first statement if the
--     authorization schema is absent, because V0 above did not run.
--   - SQLSTATE 23514 (check_violation) when a match status or fraud flag
--     outside the domains preserved below is written. Both domains are closed
--     by the baseline's own 88-level condition names, so a rejected value is
--     one the baseline could not have produced.
--   - SQLSTATE 22003 (numeric_value_out_of_range) when a decoded packed
--     quantity exceeds a declared NUMERIC precision. Rejecting is intended:
--     the ETL decodes COMP-3 at the edge, so an overflowing value is a
--     decoding defect, which is more useful reported than silently stored.
--
-- WHY (non-obvious design decisions):
--   - Alternatives Considered: every object below is created UNQUALIFIED, which
--     differs from the five peer migrations -- V1__auth.sql writes auth.users,
--     V1__ledger.sql writes ledger.transactions, and so on. The rejected
--     alternative is to qualify here too, and it is rejected because this one
--     schema is named by a reserved word: PostgreSQL's pg_get_keywords()
--     reports "authorization" with catcode T, "reserved (can be function or
--     type name)", so a bare authorization.pending_auth_summary is not a wrong
--     lookup but "syntax error at or near \"authorization\"", which points
--     nowhere near its cause. Qualifying would mean double-quoting the schema
--     on every table, every index and every REFERENCES clause, where one
--     omission is a parse failure. Unqualified names remove the hazard instead
--     of quoting around it, and resolution is pinned twice by the declared
--     consumer: Flyway default-schema at application.yml L166 for the
--     migration session, and the pool's connection-init-sql at its L125 for
--     every other connection. That file states the same principle at its L118,
--     and its L183-L189 record that the persistence provider is deliberately
--     given no default_schema for exactly this reason.
--   - Assumptions: no packed-decimal, zoned-decimal or EBCDIC representation
--     survives into any column. Every COMP-3 field of the two segments is
--     decoded once, at the ETL and mapper boundary, and stored as an exact
--     typed value. Storing the packed bytes would put the sign nibble inside
--     the database, where no SQL predicate can read it, and would oblige every
--     consumer to carry a codec to answer an ordinary query.
--   - Assumptions: money is NUMERIC with an explicit scale of 2 in every case,
--     never a binary floating-point type. The segments declare their amounts
--     S9(09)V99 and S9(10)V99 COMP-3, so the precisions are 11 and 12 -- nine
--     or ten integer digits plus two fractional -- and the Db2 table
--     independently declares DECIMAL(12,2) at ddl/AUTHFRDS.ddl L12-L13. An
--     approximate type would make a cent depend on the platform's rounding.
--   - Assumptions: the four rationale labels used throughout this file are
--     Alternatives Considered, Refactoring Rationale, Assumptions and
--     Trade-offs, written plural. That is the one permitted written form set
--     by docs/CODE_DOCUMENTATION_STANDARD.md, which names the singular
--     Assumption and Trade-off as not permitted abbreviations, and it is the
--     plural wording Rule 1 itself uses for its own categories. The
--     reference-only test suite tags its header bullets singular; that idiom
--     is not carried into this tree and the two forms are never mixed inside
--     one file.
--   - Trade-offs: no mechanical gate inspects the comments in this file, and
--     that is stated rather than assumed. config/checkstyle/checkstyle.xml
--     sets fileExtensions="java" on its Checker, so the documentation gate
--     reaches this module's Java sources and not this one, and the plan
--     contains no SQL linter at all.
--     docs/CODE_DOCUMENTATION_STANDARD.md says so in its own SQL subsection:
--     the obligation here is review-only, in full. The discipline below is
--     therefore applied with more care rather than less, precisely because
--     nothing in the build will fail if it lapses.
--   - Trade-offs: parity for this context rests on the record layouts and the
--     transcribed logic, not on a byte comparison, and claiming otherwise
--     would overstate it. All four online programs of this context are CICS
--     programs, and tests/README.md records that such programs cannot run end
--     to end without a CICS runtime, which the runner does not have; the
--     external authorization producer is not shipped either. The copybook, DBD
--     and DDL contracts cited above are consequently the strongest available
--     oracle for every column width, key and domain in this file.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- pending_auth_summary -- the per-account authorization summary.
--
-- Migrated from IMS root segment PAUTSUM0, declared 100 bytes at
-- ims/DBPAUTP0.dbd L28 and corroborated twice: the GSAM extract DBD
-- ims/PASFLDBD.DBD L27 declares RECORD=(100), and the 13 PICTURE clauses of
-- cpy/CIPAUSMY.cpy L19-L31 sum to 100 (6+9+1+10+6+6+6+6+2+2+6+6+34).
-- -----------------------------------------------------------------------------
CREATE TABLE pending_auth_summary (
    -- WHY : Assumptions: THREE numeric representations coexist inside this one
    --       100-byte record, and a migration that assumes one of them gives
    --       some columns the wrong type. cpy/CIPAUSMY.cpy L19 and L23-L26 and
    --       L29-L30 are COMP-3 packed decimal; L27-L28 are COMP, a two-byte
    --       binary halfword; and L20 is plain DISPLAY with no USAGE clause at
    --       all. Each column below takes the type its own declared
    --       representation implies rather than one rule applied to the record.
    --       PA-ACCT-ID is S9(11) COMP-3, eleven signed digits, which BIGINT
    --       holds exactly and INTEGER cannot.
    account_id            BIGINT         NOT NULL,

    -- WHY : Assumptions: PA-CUST-ID at L20 is PIC 9(09), unsigned DISPLAY, and
    --       becomes BIGINT because it is an identifier rather than a quantity.
    --       It is nullable here: the segment can carry it unset, and ledger
    --       ownership of the customer record belongs to another context, so
    --       there is no local row to make it mandatory against.
    customer_id           BIGINT,

    -- WHY : Assumptions: PA-AUTH-STATUS at L21 carries NO CHECK constraint,
    --       deliberately. It is declared PIC X(01) and no 88-level follows it
    --       -- the next line is PA-ACCOUNT-STATUS -- so unlike the two flags on
    --       pending_auth_detail there is no value domain in the baseline to
    --       preserve. Inventing one would reject rows the baseline can produce,
    --       which is the opposite of the parity this schema exists to hold.
    auth_status           CHAR(1),

    -- WHY : Alternatives Considered: PA-ACCOUNT-STATUS at L22 is PIC X(02)
    --       OCCURS 5 TIMES and becomes FIVE discrete columns. The rejected
    --       alternative is a single CHAR(2)[] array column, and it is rejected
    --       for two concrete reasons: an array accepts any length, so the arity
    --       of exactly five that the copybook fixes would stop being enforced
    --       by the schema and would survive only as a convention in
    --       application code; and array mapping is vendor-specific in JPA,
    --       requiring a converter that five plain columns do not need. The
    --       occurrence number is part of each column name so the ordinal
    --       position of the baseline's table is not lost.
    account_status_1      CHAR(2),
    account_status_2      CHAR(2),
    account_status_3      CHAR(2),
    account_status_4      CHAR(2),
    account_status_5      CHAR(2),

    credit_limit          NUMERIC(11,2),
    cash_limit            NUMERIC(11,2),
    credit_balance        NUMERIC(11,2),
    cash_balance          NUMERIC(11,2),

    -- WHY : Assumptions: the two counters are SMALLINT because L27-L28 declare
    --       them PIC S9(04) COMP, a signed two-byte binary halfword, and
    --       SMALLINT is that same signed halfword rather than a narrowing of
    --       something wider. The sign is load-bearing, not decorative: the
    --       purge program DECREMENTS these fields, at cbl/CBPAUP0C.cbl
    --       L287-L293, subtracting one from the approved or declined count and
    --       the matching amount from its total as each aged authorization is
    --       removed. All four counter and amount columns are therefore mutable
    --       running aggregates rather than immutable snapshots, and an
    --       unsigned target would be wrong the first time a total was reduced.
    approved_auth_cnt     SMALLINT,
    declined_auth_cnt     SMALLINT,

    approved_auth_amt     NUMERIC(11,2),
    declined_auth_amt     NUMERIC(11,2),

    -- WHY : Assumptions: FILLER PIC X(34) at cpy/CIPAUSMY.cpy L31 is DROPPED
    --       rather than stored. It pads the record to the fixed 100 bytes the
    --       DBD declares and carries no data, and the baseline's own relational
    --       table shows the same treatment: ddl/AUTHFRDS.ddl has no FILLER
    --       column. The drop is recorded here and in
    --       docs/architecture/data-model-and-schema-mapping.md, because an
    --       unrecorded drop cannot be told apart from an overlooked field.

    -- WHY : Assumptions: the primary key is account_id ALONE, and three
    --       independent readings settle it. ims/DBPAUTP0.dbd L30 declares
    --       FIELD NAME=(ACCNTID,SEQ,U),START=1,BYTES=6,TYPE=P -- a unique
    --       sequence field of six packed bytes at offset one, which is exactly
    --       PA-ACCT-ID and leaves no room for a second component. The list
    --       screen reads the segment on that key and nothing else, at
    --       cbl/COPAUS0C.cbl L971 and L973-L977, whose WHERE clause is
    --       (ACCNTID = PA-ACCT-ID). And the alternative key move on the
    --       intervening L972 is commented out, so it is dead scaffolding rather
    --       than a second access path. One summary row per account is what
    --       makes the child rows below addressable by account plus key, so
    --       pending_auth_detail is the only composite key in this schema.
    CONSTRAINT pk_pending_auth_summary PRIMARY KEY (account_id)
);


-- -----------------------------------------------------------------------------
-- pending_auth_detail -- one row per authorization message beneath a summary.
--
-- Migrated from IMS child segment PAUTDTL1, declared 200 bytes at
-- ims/DBPAUTP0.dbd L36 and corroborated twice: ims/PADFLDBD.DBD L27 declares
-- RECORD=(200), and the PICTURE clauses of cpy/CIPAUDTY.cpy L19-L54 sum to 200.
-- -----------------------------------------------------------------------------
CREATE TABLE pending_auth_detail (
    -- WHY : Assumptions: this table carries account_id even though
    --       cpy/CIPAUDTY.cpy declares no account field of its own -- it opens
    --       at PA-AUTHORIZATION-KEY on L19, an eight-byte group of just two
    --       level-10 packed children, PA-AUTH-DATE-9C S9(05) COMP-3 on L20 and
    --       PA-AUTH-TIME-9C S9(09) COMP-3 on L21. In IMS a child is reached
    --       only beneath its root, so the parent key is inherited
    --       hierarchically rather than stored. The baseline itself materialises
    --       that inheritance the moment it has to write the segment outside the
    --       hierarchy: cbl/PAUDBUNL.CBL L43-L48 declares its child output
    --       record as 05 ROOT-SEG-KEY PIC S9(11) COMP-3 followed by
    --       05 CHILD-SEG-REC PIC X(200) -- a 206-byte record whose prefix its
    --       L230 fills with MOVE PA-ACCT-ID TO ROOT-SEG-KEY. The composite key
    --       below is that same hierarchic path made explicit, not a design
    --       choice taken here.
    account_id            BIGINT         NOT NULL,

    -- WHY : Assumptions: these two columns store the DECODED values, never the
    --       nines complement the segment holds, and getting this wrong fails
    --       silently. The baseline ENCODES on write -- cbl/COPAUA0C.cbl L874
    --       computes PA-AUTH-DATE-9C = 99999 - WS-YYDDD and its L875 computes
    --       PA-AUTH-TIME-9C = 999999999 - WS-TIME-WITH-MS -- and DECODES on
    --       every read, at cbl/CBPAUP0C.cbl L280 and cbl/COPAUS2C.cbl L107 with
    --       the same two constants. The complement exists only because an IMS
    --       unique sequence field sorts ascending, and the key is a CHARACTER
    --       sequence over those eight packed bytes (ims/DBPAUTP0.dbd L37,
    --       TYPE=C), so complementing is what puts the newest authorization
    --       first under its parent. PostgreSQL orders descending directly, so
    --       the complement has no work left to do here. Persisting it anyway
    --       would leave the key superficially functional while every rendered
    --       date and time came out wrong and every ordering came out inverted
    --       -- plausible numbers that are wrong, which is the failure class
    --       this migration is most exposed to. Newest-first is expressed as
    --       ORDER BY auth_date DESC, auth_time DESC instead.
    -- WHY : Assumptions: auth_date is a five-digit Julian date, two-digit year
    --       plus day-of-year, and auth_time is HHMMSSmmm to the millisecond.
    --       Both forms are proven by the encode site rather than inferred:
    --       cbl/COPAUA0C.cbl L861-L866 requests YYDDD, TIME and MILLISECONDS
    --       from one FORMATTIME, its L868 takes only the first five characters
    --       of the date, and its L871-L872 build the time as
    --       (seconds-of-day form * 1000) + milliseconds. INTEGER holds nine
    --       digits exactly, so neither column needs a wider type.
    auth_date             INTEGER        NOT NULL,
    auth_time             INTEGER        NOT NULL,

    -- WHY : Assumptions: PA-AUTH-ORIG-DATE at L22 stores YYMMDD, not MMDDYY,
    --       and it is parsed into a real DATE on that reading. Two independent
    --       sites slice it the same way: cbl/COPAUS0C.cbl L531-L534 sends
    --       (1:2) to a year field, (3:2) to a month field and (5:2) to a day
    --       field before moving the result into a target whose name states the
    --       display order, and cbl/COPAUS2C.cbl L103-L105 slices identically.
    --       The six characters are therefore stored year-first and merely
    --       DISPLAYED month-first; a reader who takes the stored order for the
    --       displayed one produces dates that are wrong without looking wrong.
    -- WHY : Assumptions: the two-digit year is resolved against a fixed
    --       century pivot at the parse boundary, because a two-digit year
    --       cannot be widened without one. The baseline never had to choose: it
    --       compares and displays the characters and never converts them.
    --       Naming the pivot once, outside the schema, keeps the stored value
    --       unambiguous rather than making every reader guess.
    -- WHY : Assumptions: this column and auth_orig_time hold values supplied by
    --       the ACQUIRER, not read from the server clock -- cbl/COPAUA0C.cbl
    --       L877-L878 move them straight off the request into the segment,
    --       which is why they are X(06) wire fields. Only the complemented key
    --       above comes from the CICS clock at L857-L875. Externally supplied
    --       values are therefore what these two columns must tolerate.
    auth_orig_date        DATE,

    -- WHY : Trade-offs: auth_orig_time stays CHAR(6) while auth_orig_date
    --       becomes DATE, and the asymmetry is deliberate. Both are acquirer
    --       supplied, but the date participates in the composed auth_ts of
    --       auth_fraud below and in date-range reasoning, which a DATE
    --       expresses and six characters do not; the time has no such role. A
    --       TIME column was rejected because an acquirer value that will not
    --       parse must still round-trip rather than fail the row, and the
    --       baseline itself only ever slices these characters for display
    --       (cbl/COPAUS0C.cbl L527-L529) rather than computing with them.
    auth_orig_time        CHAR(6),

    card_num              CHAR(16)       NOT NULL,
    auth_type             CHAR(4),
    card_expiry_date      CHAR(4),
    message_type          CHAR(6),
    message_source        CHAR(6),
    auth_id_code          CHAR(6),
    auth_resp_code        CHAR(2),
    auth_resp_reason      CHAR(4),

    -- WHY : Assumptions: PA-PROCESSING-CODE at L33 is PIC 9(06), digits used as
    --       a code rather than counted with, so it is stored as CHAR(6). An
    --       integer target would discard a leading zero, and in a code a
    --       leading zero is data. The baseline's relational table agrees
    --       independently: ddl/AUTHFRDS.ddl L11 declares PROCESSING_CODE
    --       CHAR(6) even though the same field is numeric in the copybook.
    processing_code       CHAR(6),

    transaction_amt       NUMERIC(12,2),
    approved_amt          NUMERIC(12,2),

    -- WHY : Refactoring Rationale: the baseline names this field
    --       PA-MERCHANT-CATAGORY-CODE, at cpy/CIPAUDTY.cpy L36, transposing
    --       letters in "category"; the target column is spelled
    --       merchant_category_code and the baseline spelling is not carried
    --       forward. This is a deliberate breaking change at the schema
    --       boundary rather than a cosmetic edit, because the baseline
    --       misspelling reached persisted state and running code: it is the
    --       actual Db2 column name at ddl/AUTHFRDS.ddl L14, the host variable
    --       at dcl/AUTHFRDS.dcl L68-L69, and the move at cbl/COPAUS2C.cbl
    --       L125-L126. Carrying it forward would propagate it into a column
    --       name, a JPA field, a DTO member and a browser client, where every
    --       future reader would have to learn it. The divergence is recorded in
    --       docs/architecture/data-model-and-schema-mapping.md.
    -- WHY : Assumptions: the WIRE keeps the baseline spelling. The request
    --       contract declares PA-RQ-MERCHANT-CATAGORY-CODE at
    --       cpy/CCPAURQY.cpy L28 and the field order of that delimited payload
    --       is the interface, so only the internal and persisted names change;
    --       cbl/COPAUA0C.cbl L886-L887 is the hop between the two spellings.
    --       This is the only rename this schema owns -- the two expiration-date
    --       renames belong to the account and card contexts.
    merchant_category_code CHAR(4),

    acqr_country_code     CHAR(3),

    -- WHY : Assumptions: SMALLINT, not CHAR(2), even though the copybook
    --       declares PA-POS-ENTRY-MODE as PIC 9(02) at L38. The baseline's own
    --       relational declaration is the deciding reading: ddl/AUTHFRDS.ddl
    --       L16 makes POS_ENTRY_MODE a SMALLINT, and the generated host
    --       variable at dcl/AUTHFRDS.dcl L71 is PIC S9(4) USAGE COMP, a binary
    --       halfword. Entry mode is a bounded quantity rather than a code whose
    --       leading zero carries meaning, which is what separates it from
    --       processing_code above.
    pos_entry_mode        SMALLINT,

    merchant_id           CHAR(15),

    -- WHY : Assumptions: merchant_name is the one VARCHAR here, mirroring
    --       ddl/AUTHFRDS.ddl L18 where MERCHANT_NAME is the only VARCHAR(22)
    --       among otherwise fixed CHAR columns, and its blank padding is
    --       PERSISTED rather than trimmed. The baseline sets the length half of
    --       the Db2 varying-length host variable to the full declared width
    --       regardless of content: cbl/COPAUS2C.cbl L130 moves LENGTH OF
    --       PA-MERCHANT-NAME -- a constant 22 -- into MERCHANT-NAME-LEN before
    --       L131 moves the text, against the 49-level LEN and TEXT pair
    --       declared at dcl/AUTHFRDS.dcl L73-L77. Trimming on the way in would
    --       therefore change stored bytes the baseline preserves.
    merchant_name         VARCHAR(22),

    merchant_city         CHAR(13),
    merchant_state        CHAR(2),
    merchant_zip          CHAR(9),
    transaction_id        CHAR(15),

    match_status          CHAR(1),
    auth_fraud            CHAR(1),

    -- WHY : Assumptions: fraud_rpt_date is a real DATE parsed from the
    --       baseline's MM/DD/YY characters, not the characters themselves.
    --       PA-FRAUD-RPT-DATE is PIC X(08) at cpy/CIPAUDTY.cpy L53, and the
    --       separator is proven rather than guessed: cbl/COPAUS2C.cbl L95-L100
    --       formats the current date with MMDDYY and DATESEP, whose default
    --       separator is a solidus, and its L101 moves the result straight into
    --       the field. The width corroborates it -- cbl/COPAUS1C.cbl L344-L347
    --       renders a one-character flag, a separator and this eight-character
    --       date into a ten-character screen field. Db2 stores the same value
    --       as a real DATE at ddl/AUTHFRDS.ddl L25, so parsing here keeps the
    --       two representations of one date from diverging in the target the
    --       way they do in the baseline, where dcl/AUTHFRDS.dcl L84 declares
    --       the host variable X(10) against the copybook's X(08).
    -- WHY : Assumptions: it is NULLABLE, and blank maps to NULL rather than to
    --       a sentinel such as 0001-01-01. On the ordinary path the baseline
    --       writes neither fraud field: the ELSE at cbl/COPAUS1C.cbl L349 fills
    --       the whole screen field with a single separator, which is reachable
    --       only when the flag is neither confirmed nor removed, so the stored
    --       state is blank and there is no date at all. A blank X(08) cannot be
    --       held in a DATE, and a sentinel would be indistinguishable from a
    --       real report date in every predicate. Db2 agrees: L25 carries no NOT
    --       NULL, and only CARD_NUM at L2 and AUTH_TS at L3 do.
    fraud_rpt_date        DATE,

    -- WHY : Assumptions: FILLER PIC X(17) at cpy/CIPAUDTY.cpy L54 is DROPPED,
    --       for the same reason as the summary's FILLER above: it pads the
    --       record to the 200 bytes the DBD declares and holds no data. The
    --       drop is recorded in
    --       docs/architecture/data-model-and-schema-mapping.md.

    -- WHY : Assumptions: the key is (account_id, auth_date, auth_time) --
    --       the inherited parent key followed by the segment's own two-part
    --       key. The segment key is unique WITHIN one account, because that is
    --       the only scope IMS enforces it in, so the two segment components
    --       alone would collide as soon as two accounts recorded an
    --       authorization in the same millisecond. That the eight-byte key is
    --       the addressing mechanism rather than mere content is visible at
    --       cbl/COPAUS0C.cbl L544-L545, which carries PA-AUTHORIZATION-KEY out
    --       to the list screen as the token identifying a selected row.
    -- WHY : Trade-offs: no separate descending index is declared for the
    --       most-recent-first read of one account's authorizations, even though
    --       that is the order the list screen presents. This key's own btree
    --       already answers it: with account_id fixed by equality, PostgreSQL
    --       scans the remaining two columns backward to yield auth_date and
    --       auth_time descending without a sort. Declaring a second index would
    --       add an object with no access path of its own to serve. The fraud
    --       table below is the opposite case and does declare one, for the
    --       reason given there.
    CONSTRAINT pk_pending_auth_detail PRIMARY KEY (account_id, auth_date, auth_time),

    -- WHY : Assumptions: the four accepted values are exactly the 88-level
    --       condition names at cpy/CIPAUDTY.cpy L46-L49 -- PA-MATCH-PENDING
    --       'P', PA-MATCH-AUTH-DECLINED 'D', PA-MATCH-PENDING-EXPIRED 'E' and
    --       PA-MATCHED-WITH-TRAN 'M'. A closed 88-level set is a value domain
    --       the baseline enforces in code, and this constraint is what carries
    --       it into the target; without it the column would accept any single
    --       character and a value no reader recognises could be stored. NULL
    --       passes, as it must -- the column is nullable and an unset match
    --       status is not an out-of-domain one.
    CONSTRAINT ck_pending_auth_detail_match_status
        CHECK (match_status IN ('P', 'D', 'E', 'M')),

    -- WHY : Assumptions: this domain admits a BLANK as well as NULL, and the
    --       blank is the part that matters. cpy/CIPAUDTY.cpy L50-L52 declares
    --       only PA-FRAUD-CONFIRMED 'F' and PA-FRAUD-REMOVED 'R', which invites
    --       the narrower domain of those two values plus NULL -- and that
    --       narrower form would reject rows the baseline routinely produces.
    --       The ELSE branch at cbl/COPAUS1C.cbl L349 is decisive: it is
    --       reachable exactly when the flag is neither 'F' nor 'R', so the
    --       ordinary state of a never-marked authorization is a SPACE, which is
    --       what a COBOL X(01) holds when nothing is moved into it. A row
    --       arriving from the extract with a blank flag is an ordinary row, so
    --       the constraint accepts 'F', 'R', NULL and a single space, and
    --       nothing else.
    -- WHY : Refactoring Rationale: the baseline enforced this domain only in
    --       application code -- ddl/AUTHFRDS.ddl L23-L24 declare MATCH_STATUS
    --       and AUTH_FRAUD as bare CHAR(1) and that whole file contains no
    --       CHECK constraint at all -- so this constraint and the one above
    --       promote an application-only invariant into the database. The
    --       reason to move it is that the flag is now written by more than one
    --       path: the marking flow toggles it in both directions
    --       (cbl/COPAUS1C.cbl L534-L538 selects a removed or a marked message),
    --       and the ETL loads it from the extract. An invariant asserted in one
    --       program cannot bind the others.
    CONSTRAINT ck_pending_auth_detail_auth_fraud
        CHECK (auth_fraud IN ('F', 'R') OR auth_fraud IS NULL OR auth_fraud = ' '),

    -- WHY : Assumptions: a detail row cannot exist without its summary. In the
    --       baseline that is structural rather than declared -- a child segment
    --       is reachable only beneath its root -- so in a relational target it
    --       has to be asserted, and ON DELETE CASCADE is what reproduces the
    --       hierarchical delete of a root taking its children with it. The
    --       reference stays inside this schema; no foreign key here crosses a
    --       schema boundary, because the tables another context owns are not
    --       this migration's to depend on.
    -- WHY : Trade-offs: this makes load order significant for the ETL, which
    --       must place summary rows before the detail rows beneath them. That
    --       ordering is already inherent in the baseline extract --
    --       cbl/PAUDBUNL.CBL writes the root to one file and the
    --       account-prefixed child to another -- so the constraint records a
    --       dependency the data already has rather than imposing a new one.
    CONSTRAINT fk_pending_auth_detail_summary
        FOREIGN KEY (account_id) REFERENCES pending_auth_summary (account_id)
        ON DELETE CASCADE
);


-- -----------------------------------------------------------------------------
-- auth_fraud -- the fraud-tagged authorizations, migrated from Db2.
--
-- Migrated from CARDDEMO.AUTHFRDS at ddl/AUTHFRDS.ddl, whose 28 lines carry one
-- column per line on L2 through L27 and the primary key on L28. The column
-- count is EXACTLY 26 and the baseline states it itself: dcl/AUTHFRDS.dcl L88
-- is the generated comment "THE NUMBER OF COLUMNS DESCRIBED BY THIS DECLARATION
-- IS 26". Two further readings agree -- the INSERT at cbl/COPAUS2C.cbl
-- L143-L168 names 26 columns against 26 values on L170-L196, and deriving from
-- the segment gives the same total: 27 05-levels of cpy/CIPAUDTY.cpy, less its
-- FILLER, less the three date and time items folded into auth_ts, plus acct_id
-- and cust_id.
--
-- WHY : Assumptions: acct_id and cust_id have no counterpart in either IMS
--       segment and arrive from the CALLER. cbl/COPAUS2C.cbl declares them in
--       its LINKAGE SECTION at L73-L86, where the detail segment itself also
--       arrives by COMMAREA through COPY CIPAUDTY at L78, and its L138-L139
--       move them into the row. They are the parent summary segment's account
--       and customer passed down, which is why the fraud table holds identity
--       the child segment does not.
-- WHY : Trade-offs: the KEY SPACE differs from the segments above and is left
--       differing. The IMS path is account-scoped and hierarchical, root
--       PAUTSUM0 to child PAUTDTL1; this table is card-scoped and relational,
--       keyed (card_num, auth_ts) by ddl/AUTHFRDS.ddl L28. Reconciling the two
--       onto one key was rejected because each is an access path the baseline
--       exposes to a different reader -- the pending list reaches
--       authorizations by account, a fraud review reaches them by card -- and
--       collapsing them would remove one of those paths.
-- WHY : Assumptions: five items of the segment are absent here BY DESIGN rather
--       than dropped by oversight, and naming them is what makes that legible:
--       PA-AUTH-STATUS, the OCCURS 5 account-status table and FILLER X(17) have
--       no column in ddl/AUTHFRDS.ddl at all, and PA-AUTH-ORIG-DATE and
--       PA-AUTH-ORIG-TIME are folded into the single auth_ts below.
-- WHY : Assumptions: THREE different targets for zoned numerics coexist in this
--       one table, resolved by the role each field plays rather than by its
--       PICTURE. A bounded QUANTITY becomes an integer (pos_entry_mode,
--       SMALLINT at ddl/AUTHFRDS.ddl L16); a CODE whose leading zero is data
--       stays character (processing_code, CHAR(6) at L11); an IDENTIFIER
--       becomes BIGINT (acct_id and cust_id, L26-L27).
-- WHY : Trade-offs: mapping those two identifiers to BIGINT diverges from the
--       baseline's DECIMAL(11) and DECIMAL(9), and the divergence is safe for a
--       stated reason: dcl/AUTHFRDS.dcl L49-L50 declare them DECIMAL(11, 0) and
--       DECIMAL(9, 0), scale explicitly zero, so both fit BIGINT exactly with
--       no fractional component to lose. They are identity and are never
--       arithmetic operands. NUMERIC(11,0) would preserve the declared type
--       more literally at the cost of making every join key a variable-length
--       value.
-- -----------------------------------------------------------------------------
CREATE TABLE auth_fraud (
    card_num              CHAR(16)       NOT NULL,

    -- WHY : Assumptions: auth_ts is a COMPOSED value that exists in NEITHER IMS
    --       segment, assembled by cbl/COPAUS2C.cbl from two different sources.
    --       Its date part comes from PA-AUTH-ORIG-DATE, sliced year-month-day at
    --       L103-L105; its time part comes from the DECODED nines complement at
    --       L107, split into hours, minutes, seconds and milliseconds at
    --       L108-L111. The date is therefore ACQUIRER-supplied while the time is
    --       server-derived, a mixed origin worth knowing before anyone treats
    --       the column as a single trustworthy instant.
    -- WHY : Assumptions: TIMESTAMP(6), and its three low fractional digits are
    --       ALWAYS ZERO by construction rather than by accident, so nobody
    --       should later raise the precision to "recover" them. The assembled
    --       string is declared at cbl/COPAUS2C.cbl L38-L51 as exactly 23
    --       characters, ending in a three-digit millisecond field followed by a
    --       FILLER literal of three zeros, and it is read back with
    --       TIMESTAMP_FORMAT under the mask 'YY-MM-DD HH24.MI.SSNNNNNN' at
    --       L171-L172 and L227-L228. Six fractional digits are consequently fed
    --       by three real ones plus that literal padding. The 26-character host
    --       variable at dcl/AUTHFRDS.dcl L57 confirms the microsecond width
    --       independently.
    auth_ts               TIMESTAMP(6)   NOT NULL,

    auth_type             CHAR(4),
    card_expiry_date      CHAR(4),
    message_type          CHAR(6),
    message_source        CHAR(6),
    auth_id_code          CHAR(6),
    auth_resp_code        CHAR(2),
    auth_resp_reason      CHAR(4),
    processing_code       CHAR(6),
    transaction_amt       NUMERIC(12,2),
    approved_amt          NUMERIC(12,2),
    merchant_category_code CHAR(4),
    acqr_country_code     CHAR(3),
    pos_entry_mode        SMALLINT,
    merchant_id           CHAR(15),
    merchant_name         VARCHAR(22),
    merchant_city         CHAR(13),
    merchant_state        CHAR(2),
    merchant_zip          CHAR(9),
    transaction_id        CHAR(15),

    -- WHY : Assumptions: these two flags carry NO CHECK constraint on this
    --       table, unlike their counterparts on pending_auth_detail, and the
    --       asymmetry is intentional twice over. The baseline's own relational
    --       table leaves them unconstrained -- ddl/AUTHFRDS.ddl L23-L24 are bare
    --       CHAR(1) and that file declares no CHECK anywhere -- and the one path
    --       that writes this table writes only the two marked values, from the
    --       closed 88-level pair at cbl/COPAUS2C.cbl L80-L82 moved into the row
    --       at its L137. The blank state that forces the detail table's domain
    --       open therefore does not arise here. The logical domain is shared
    --       with that table and is documented as such; it is asserted there,
    --       where a blank actually occurs, which keeps this schema's value-domain
    --       constraints to the two the baseline's condition names define.
    match_status          CHAR(1),
    auth_fraud            CHAR(1),

    -- WHY : Assumptions: nullable, and populated from a DIFFERENT source than
    --       the same-named column on pending_auth_detail -- the two must not be
    --       conflated. Here both write paths take the database's own current
    --       date: the INSERT supplies CURRENT DATE positionally at
    --       cbl/COPAUS2C.cbl L194, matching FRAUD_RPT_DATE in the column list at
    --       L166, rather than passing the host variable; and the UPDATE sets
    --       FRAUD_RPT_DATE = CURRENT DATE at L224-L225. The MM/DD/YY string
    --       built at L101 populates the IMS segment field instead, which
    --       cbl/COPAUS1C.cbl L525-L528 then replaces into PAUTDTL1. One column
    --       is parsed from characters, the other is a server date.
    fraud_rpt_date        DATE,

    acct_id               BIGINT,
    cust_id               BIGINT,

    -- WHY : Assumptions: (card_num, auth_ts) reproduces ddl/AUTHFRDS.ddl L28,
    --       and it is also what makes the target's upsert expressible on the
    --       same terms the baseline used. The baseline's control flow IS an
    --       upsert: cbl/COPAUS2C.cbl attempts the INSERT, tests SQLCODE at L199,
    --       and on -803 -- the duplicate-key condition -- performs FRAUD-UPDATE
    --       at L203-L204, whose WHERE clause at L226-L228 matches this key
    --       exactly and nothing else. An ON CONFLICT (card_num, auth_ts) DO
    --       UPDATE in the service layer is therefore a transcription of that
    --       flow rather than a new design, and this constraint is the conflict
    --       target it names.
    CONSTRAINT pk_auth_fraud PRIMARY KEY (card_num, auth_ts)
);

-- WHY : Assumptions: this index is declared SEPARATELY from the primary key
--       above, and in PostgreSQL it has to be. A unique constraint and a
--       directional index are two distinct objects: the key's own btree is
--       ascending in both columns, so it does not carry the descending access
--       path the baseline built, and an index over the same two columns in the
--       same direction as the key would not either. Direction is part of the
--       access path, which is why it is stated explicitly on both columns here
--       rather than left to the default.
-- WHY : Assumptions: the column order and both directions are
--       ddl/XAUTHFRD.ddl, whose entire four lines declare a unique index on
--       (CARD_NUM ASC, AUTH_TS DESC). The ROOT CAUSE of that descending second
--       column is the nines complement at cbl/COPAUA0C.cbl L874-L875: the
--       hierarchical side achieved newest-authorization-first by complementing
--       the KEY, because an IMS sequence field ascends only, and the relational
--       side had no complement to lean on and so re-expressed the same
--       intention as an INDEX. Both encode one behaviour -- for a given card,
--       the most recent authorization is the first row read -- and this index is
--       where that behaviour survives in the target. An all-ascending index
--       would answer the same predicate while turning that read into a backward
--       scan or a sort, which is a different access path from the one the
--       baseline shipped.
-- WHY : Trade-offs: the baseline index is UNIQUE and carries COPY YES; neither
--       is reproduced as an index property. Uniqueness over those two columns
--       is already asserted by the primary key, so repeating it would declare a
--       second unique object over the identical column pair. COPY YES is a Db2
--       image-copy attribute with no PostgreSQL analogue at all -- its
--       equivalent is the cluster's automated backups and point-in-time
--       recovery, which are infrastructure rather than schema. Recording that
--       here is deliberate, so the omission reads as a resolved question rather
--       than a dropped clause.
CREATE INDEX idx_auth_fraud_card_recent
    ON auth_fraud (card_num ASC, auth_ts DESC);


-- -----------------------------------------------------------------------------
-- auth_reply_outbox -- the transactional outbox. NO BASELINE COUNTERPART.
--
-- WHY : Refactoring Rationale: this is the only table in this schema with no
--       counterpart in the reference system, and it exists to close a window the
--       baseline leaves open. The order of operations in cbl/COPAUA0C.cbl is
--       DECIDE, then REPLY, then WRITE, then COMMIT: its L459 computes the
--       decision, its L461 puts the reply, its L463-L465 write the database, and
--       the single EXEC CICS SYNCPOINT at L334-L336 commits last of all, in the
--       outer loop after the whole request has been processed. The reply is
--       therefore published BEFORE the data it describes is committed, and the
--       dominant failure is a PHANTOM REPLY: the client holds an approval for a
--       decision the database never recorded. Its converse, a LOST REPLY, is the
--       same seam read the other way -- the commit succeeds and the put never
--       happens. Writing the reply as a ROW inside the same transaction as the
--       decision makes the two exactly as durable as each other, and a publisher
--       then drains committed rows to the queue afterwards.
-- WHY : Assumptions: neither failure is recoverable by retry in the baseline,
--       which is what rules out simply retrying instead of adding this table.
--       The request is consumed with the no-syncpoint option
--       (cbl/COPAUA0C.cbl L389-L391) and the reply is put with it as well
--       (L753-L754), so on failure the request message is already destroyed and
--       nothing is redelivered to reconstruct the answer from. The asymmetry at
--       L461 against L463-L465 sharpens it further: the reply is UNCONDITIONAL
--       while the database write is gated on the cross-reference lookup having
--       found the card, so an unknown-card decline replies and then writes
--       nothing at all.
-- WHY : Assumptions: this ruling is specific to this context and must not be
--       transposed. The account-inquiry extension takes its message and puts its
--       response UNDER syncpoint -- app/app-vsam-mq/cbl/COACCT01.cbl L347 sets
--       MQGMO-SYNCPOINT on the get and its L475 and L512 set MQPMO-SYNCPOINT on
--       the puts -- which makes receive, logic and send one atomic unit, so that
--       context has neither window and correctly declares no outbox. The three
--       extensions do not share one messaging discipline, and treating them
--       alike would break one of them: an outbox there would add a table with
--       nothing to guarantee, and no outbox here would leave the window above
--       open.
-- WHY : Assumptions: this table is a documented divergence, D-5 in
--       docs/architecture/cobol-to-service-traceability.md, and not a repair of
--       the baseline. No COBOL is edited by this migration: the baseline behaves
--       as described above, the target adds a durable reply, and the difference
--       is registered rather than made silently.
-- -----------------------------------------------------------------------------
CREATE TABLE auth_reply_outbox (
    -- WHY : Alternatives Considered: a generated identity rather than the
    --       transaction identifier as a natural key. The natural key is rejected
    --       because a request redelivered outside the queue's deduplication
    --       interval legitimately produces a second reply for the same
    --       transaction; a natural key would reject that row, and the row it
    --       rejected would be the reply this table exists to guarantee.
    outbox_id             BIGINT         GENERATED BY DEFAULT AS IDENTITY,

    -- WHY : Assumptions: the destination travels IN THE ROW rather than being
    --       read from configuration by the publisher, because the baseline
    --       routes each reply to the queue that request nominated -- it moves a
    --       per-request queue name into the reply descriptor at
    --       cbl/COPAUA0C.cbl L741-L742 rather than addressing a fixed
    --       destination. Holding it here keeps the publisher indifferent to who
    --       asked, and keeps a reply routable even if configuration changes
    --       between the decision and the drain.
    reply_to_queue_url    VARCHAR(1024)  NOT NULL,

    -- WHY : Assumptions: the correlation identity is echoed from the request
    --       unaltered, mirroring cbl/COPAUA0C.cbl L745, which moves the saved
    --       inbound identifier into the reply descriptor exactly as it arrived.
    --       It is how the requester matches an answer to its question, so it is
    --       stored rather than regenerated at publication.
    correlation_id        VARCHAR(64),

    -- WHY : Assumptions: the ordering group is the CARD NUMBER and the
    --       deduplication key is the TRANSACTION IDENTIFIER, and both are stored
    --       rather than derived when the row is drained. A publisher that had to
    --       parse the payload to recover them would be unable to publish a
    --       payload it could not parse, which is precisely the case where
    --       publishing matters most. Grouping by card is what preserves the
    --       per-card ordering the baseline gets from a single-threaded consumer.
    message_group_id      VARCHAR(128)   NOT NULL,
    deduplication_id      VARCHAR(128)   NOT NULL,

    -- WHY : Assumptions: the payload is stored as the delimited text the wire
    --       carries, not as structured columns. For a string-format message the
    --       field ORDER and the delimiter are the contract, so re-encoding at
    --       publication time would create a second place for that contract to be
    --       got wrong. The reply is the six fields of cpy/CCPAURLY.cpy L19-L24,
    --       whose declared widths sum to 57 characters -- 16, 15, 6, 2, 4 and a
    --       14-character signed edited amount -- reaching 62 on the wire once the
    --       five separators are counted. TEXT rather than a bounded CHAR because
    --       the two figures are different things and a column sized to the width
    --       sum would truncate the separated form; the request payload, 18 fields
    --       summing to 153 and 170 on the wire, is not stored here at all.
    payload               TEXT           NOT NULL,
    content_type          VARCHAR(64)    NOT NULL DEFAULT 'text/csv',

    -- WHY : Refactoring Rationale: the baseline expresses a reply deadline in
    --       the message descriptor -- cbl/COPAUA0C.cbl L750 sets an expiry of 50
    --       tenths of a second, five seconds -- and the target transport has no
    --       per-message time-to-live at all. The deadline therefore moves out of
    --       the transport and into this column: the publisher declines to send a
    --       row whose instant has passed and a consumer drops a stale reply.
    --       Leaving it to queue retention alone was rejected because retention is
    --       a queue-wide setting and cannot express a per-message deadline. The
    --       gap and this resolution are recorded in docs/adr/ADR-004.
    expires_at            TIMESTAMP(6),

    created_at            TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- WHY : Alternatives Considered: publication is recorded by SETTING this
    --       column, not by deleting the row. Deleting was rejected because a
    --       published reply then leaves no trace beside the decision it
    --       answered, and this table's whole purpose is to make the reply as
    --       auditable as the decision.
    published_at          TIMESTAMP(6),

    -- WHY : Trade-offs: at-least-once publication is the accepted cost of this
    --       design, which is why these two columns exist. A publisher that fails
    --       after sending but before marking the row sends that reply twice.
    --       That is tolerable here specifically because the reply queue
    --       deduplicates on the identifier stored above, so a repeat inside the
    --       deduplication interval is discarded by the transport. The
    --       alternative ordering -- mark first, then send -- turns a duplicate
    --       into a lost reply, which is the failure this table was added to
    --       remove, so the duplicate is the safer of the two.
    attempts              SMALLINT       NOT NULL DEFAULT 0,
    last_error            VARCHAR(256),

    CONSTRAINT pk_auth_reply_outbox PRIMARY KEY (outbox_id)
);

-- WHY : Trade-offs: a PARTIAL index over unpublished rows only. The publisher
--       issues one query -- the oldest rows not yet published -- and because
--       publication sets a column instead of deleting the row, the published set
--       grows without bound while the unpublished set stays small and bounded by
--       the drain interval. A full index on created_at would answer the same
--       query while growing with the table and consisting almost entirely of
--       rows the query can never want. The cost is that this index serves only
--       predicates carrying the same IS NULL test, which is the sole access path
--       this table has.
CREATE INDEX idx_auth_reply_outbox_unpublished
    ON auth_reply_outbox (created_at)
    WHERE published_at IS NULL;
