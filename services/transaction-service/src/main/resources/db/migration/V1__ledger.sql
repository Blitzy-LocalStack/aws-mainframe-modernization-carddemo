-- =============================================================================
-- V1__ledger.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Creates the four tables of the `ledger` schema, which is the persistence
--   contract of the transaction-service bounded context, together with the two
--   secondary indexes that carry the access paths the baseline served from one
--   VSAM base cluster plus one alternate index.
--
--   This migration creates tables and indexes and nothing else. It creates no
--   schema and no role, and it confers no privilege: `ledger` and its owning
--   login are established by data-migration/sql/V0__schemas_and_roles.sql,
--   which states the same division at its own L11-L16. That bootstrap has
--   already run before Flyway reaches this file.
--
-- Post-state established:
--   ledger.transactions                    13 columns, primary key on
--                                          transaction_id, plus
--                                          idx_transactions_card_num and
--                                          idx_transactions_proc_ts
--   ledger.daily_transactions              the same 13 columns, no primary key,
--                                          proc_ts nullable
--   ledger.transaction_rejects             3 columns, no primary key
--   ledger.transaction_category_balances   4 columns, three-part composite
--                                          primary key
--
-- Derivation:
--   Three copybooks under app/** fix every column, and app/** is read as
--   reference only and is never modified:
--     app/cpy/CVTRA05Y.cpy L4-L18  TRAN-RECORD, 350 bytes
--                                    -> transactions
--     app/cpy/CVTRA06Y.cpy L4-L18  DALYTRAN-RECORD, 350 bytes
--                                    -> daily_transactions
--     app/cpy/CVTRA01Y.cpy L4-L10  TRAN-CAT-BAL-RECORD, 50 bytes
--                                    -> transaction_category_balances
--   transaction_rejects has no copybook; its layout is declared inline in
--   app/cbl/CBTRN02C.cbl.
--   The PICTURE-to-type mapping applied here is the twelve-rule table in
--   docs/architecture/data-model-and-schema-mapping.md L304-L317, and the
--   column names are that document's L1119-L1192 verbatim. Byte ranges quoted
--   in this file are one-based and inclusive.
--
-- Fails when:
--   - The `ledger` schema does not exist, because V0__schemas_and_roles.sql has
--     not run against this database. CREATE TABLE reports that schema "ledger"
--     does not exist, and Flyway marks the migration failed. That refusal is
--     the designed behaviour rather than an accident: the sibling
--     application.yml sets spring.flyway.create-schemas to false at its L446
--     precisely so a missing bootstrap surfaces here instead of being papered
--     over by a schema this file invented and does not own.
--   - The connecting role is not the schema owner, so it cannot create objects
--     in `ledger`. The privilege model belongs to the bootstrap, so the fix is
--     to run as the owning login rather than to add anything to this file.
--
-- WHY : Assumptions: every table name below is written `ledger.`-qualified, and
--       that is load-bearing rather than stylistic. Two different logins reach
--       these four tables. transaction-service pins the schema on each
--       connection, at application.yml L317 `connection-init-sql: SET
--       search_path TO ledger`. batch-service reaches the same tables under its
--       own login, which needs `ledger` and `account` together and therefore
--       cannot pin a single schema the same way. And V0__schemas_and_roles.sql
--       L592-L597 records the deliberate decision to set no schema search order
--       on any of the eight roles, leaving name resolution to each connecting
--       service. Qualifying every name means this file resolves to the same
--       four objects no matter which of those connections executes or reads it,
--       so none of that configuration is a dependency of the DDL.
--
-- Alternatives Considered: batch-service writes these tables under a second
--   login holding narrowly scoped cross-schema write privileges on `ledger.*`
--   and `account.*`, established in data-migration/sql/V0__schemas_and_roles.sql
--   section 4. A reader who does not know that sees an apparent
--   one-schema-per-service violation here, so the reason is recorded beside the
--   tables it applies to. In app/cbl/CBTRN02C.cbl the paragraph
--   2000-POST-TRANSACTION at L424 performs 2700-UPDATE-TCATBAL at L440,
--   2800-UPDATE-ACCOUNT-REC at L441 and 2900-WRITE-TRANSACTION-FILE at L442,
--   closing at L444: two of those three records land in `ledger` and one in
--   `account`, inside one unit of work. A transactional outbox with a
--   compensating reversal was rejected because it would introduce observable
--   partial-posting states that do not exist in the baseline, breaking
--   golden-master parity outright, and a saga across separate databases fails for
--   the same reason. Keeping one database and narrowing the second login's
--   privileges is what holds the posting commit atomic, so any change to this
--   file is a change to that unit of work.
--
-- Trade-offs: NOT NULL is asserted on primary-key columns, which PostgreSQL
--   requires in any case, and on transactions.proc_ts, and nowhere else. The
--   restraint costs declared strictness. A blank fixed-width field decodes to
--   NULL at the load boundary, and that is measured rather than hypothetical: in
--   app/data/ASCII/dailytran.txt the 26 bytes at 305-330 are blank on all 300 of
--   300 records. Blank fields therefore do occur in the extracts these tables are
--   loaded from, and asserting NOT NULL more widely would refuse a load the
--   baseline itself accepts. The accepted cost is that column presence beyond the
--   keys is enforced by the writing service rather than by the schema.
--
-- Refactoring Rationale: the baseline file these tables replace is defined at
--   app/csd/CARDDEMO.CSD L76 as `DEFINE FILE(TRANSACT)` over
--   `DSNAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS)` at L77, carrying `JOURNAL(NO)`
--   at the end of L82 and `RECOVERY(NONE)` at L84, so it had neither journalling
--   nor recoverability of its own. Nothing here reproduces that posture and
--   nothing here configures the replacement either: encryption at rest and
--   automated backups are cluster properties carried by
--   infra/modules/aurora-postgresql, not table properties. Recorded so the
--   absence of any storage or recovery clause below reads as a deliberate
--   division of responsibility rather than an omission.
-- =============================================================================
-- 1. ledger.transactions
--
-- The posted transaction master. app/cpy/CVTRA05Y.cpy L4-L18 declares thirteen
-- fields totalling 330 bytes; `FILLER PIC X(20)` at L18 pads bytes 331-350 out
-- to the declared 350 and is not mapped to a column, because it is the final
-- field, carries no data, and a column derived from it would hold nothing but
-- blanks in every row. The same omission is inventoried per record in
-- docs/architecture/data-model-and-schema-mapping.md L824-L838.
-- =============================================================================

CREATE TABLE ledger.transactions (

    -- Assumptions: CHAR(16) rather than an integer, for a field whose
    --   characters happen to be digits. app/cpy/CVTRA05Y.cpy L5 declares
    --   `TRAN-ID PIC X(16)`, an alphanumeric picture, so the fixed width is
    --   the contract and leading zeros are significant. That is not
    --   theoretical: in app/data/ASCII/dailytran.txt all 300 identifiers
    --   occupy the full 16 bytes with no trailing blank, and values such as
    --   `0000000000683580` carry leading zeros an integer column would
    --   discard. Bytes 1-16.
    transaction_id  CHAR(16)      NOT NULL,

    -- Assumptions: a two-character code, so CHAR(2) by the fixed-code
    --   rule, and the baseline agrees where it expressed the same field
    --   relationally: app/app-transaction-type-db2/ddl/TRNTYCAT.ddl L2
    --   declares `TRC_TYPE_CODE CHAR(2) NOT NULL`. Bytes 17-18.
    type_cd         CHAR(2),

    -- Assumptions: CHAR(4) although app/cpy/CVTRA05Y.cpy L7 declares
    --   `TRAN-CAT-CD PIC 9(04)`, which a width-only reading would map to a
    --   small integer since 9999 fits in two bytes. The category code is a
    --   label rather than a magnitude: no program performs arithmetic on
    --   it, and its leading zeros are significant, so it falls on the code
    --   side of the code-versus-quantity split recorded at
    --   docs/architecture/data-model-and-schema-mapping.md L319-L323. The
    --   baseline settles it: app/app-transaction-type-db2/ddl/TRNTYCAT.ddl
    --   L3 declares this same field as `TRC_TYPE_CATEGORY CHAR(4) NOT NULL`
    --   where it stored it in Db2. Every value in
    --   app/data/ASCII/tcatbal.txt is `0001`, which an integer column would
    --   render as 1. Bytes 19-22.
    category_cd     CHAR(4),

    -- Assumptions: CHAR(10) keeps the blank-padded comparison semantics
    --   the source relies on. COBOL compares a PIC X(10) field padded to
    --   its full width, and CHAR does the same, so `POS TERM` and `POS TERM
    --   ` remain one value; under VARCHAR they would be two. The field is a
    --   small closed set used as a label, not free text: both distinct
    --   values in app/data/ASCII/dailytran.txt, `OPERATOR` and `POS TERM`,
    --   are shorter than the declared width and blank-padded on all 300
    --   records. Bytes 23-32.
    source          CHAR(10),

    -- Assumptions: VARCHAR(100), not CHAR, because this is descriptive
    --   text whose trailing blanks are padding to the fixed record length
    --   and are never compared as part of a code. The measured evidence is
    --   the shape of the data: every one of the 300 descriptions in
    --   app/data/ASCII/dailytran.txt is blank-padded, and the longest
    --   trimmed value is 48 of the declared 100 characters, so storing the
    --   trimmed text keeps the declared maximum as a constraint without
    --   storing 52 blanks that mean nothing. Bytes 33-132.
    description     VARCHAR(100),

    -- Assumptions: exact fixed point at precision 11, scale 2, because
    --   app/cpy/CVTRA05Y.cpy L10 declares `TRAN-AMT PIC S9(09)V99` -- nine
    --   integer digits plus two fractional digits, occupying 11 bytes in
    --   DISPLAY usage as the measured 11-byte field at bytes 133-143
    --   confirms, each value ending in a sign overpunch such as the `G` and
    --   `}` visible in app/data/ASCII/dailytran.txt. NUMERIC(11,2) carries
    --   that domain digit for digit. A binary approximate type is excluded
    --   here and everywhere in this file: it cannot hold every value this
    --   field can express, so a balance could differ from the baseline by a
    --   cent with nothing in the schema to reveal it. Bytes 133-143.
    amount          NUMERIC(11,2),

    -- Assumptions: an identifier, so BIGINT by the numeric-identifier
    --   rule, and unlike the category code this one is a magnitude with no
    --   significant leading zero -- every value measured in
    --   app/data/ASCII/dailytran.txt is `800000000`, and
    --   app/cbl/COBIL00C.cbl L226 writes the all-nines sentinel `MOVE
    --   999999999 TO TRAN-MERCHANT-ID` for a bill payment, so the column
    --   must hold the full nine-digit width exactly. Bytes 144-152.
    merchant_id     BIGINT,

    -- Assumptions: descriptive, so VARCHAR by the same reasoning as the
    --   description column; blank-padded on all 300 measured records with a
    --   longest trimmed value of 36. app/app-transaction-type-db2/ddl/
    --   TRNTYCAT.ddl L4 shows the baseline making the identical
    --   descriptive-to-VARCHAR choice with `TRC_CAT_DATA VARCHAR(50)`.
    --   Bytes 153-202.
    merchant_name   VARCHAR(50),

    -- Assumptions: descriptive, VARCHAR for the reason above; longest
    --   trimmed value measured is 19 of 50. Bytes 203-252.
    merchant_city   VARCHAR(50),

    -- Assumptions: CHAR(10) rather than VARCHAR, because a postal code is
    --   matched as a code and its leading zeros are significant -- 27 of
    --   the 300 values in app/data/ASCII/dailytran.txt begin with a zero,
    --   for example `00022`. Keeping it fixed width preserves the
    --   blank-padded comparison the source performs. Bytes 253-262.
    merchant_zip    CHAR(10),

    -- Assumptions: CHAR(16) and never a numeric type.
    --   app/cpy/CVTRA05Y.cpy L15 declares `TRAN-CARD-NUM PIC X(16)`, and 30
    --   of the 300 card numbers in app/data/ASCII/dailytran.txt carry a
    --   leading zero, so an integer column would silently shorten those to
    --   15 digits and break every lookup on this value. No example value is
    --   reproduced here: a primary account number does not belong in source
    --   prose even when it comes from the seed extract, and the aggregate
    --   count is what the type decision actually rests on.
    --   app/jcl/TRANREPT.jcl L41 types the same field
    --   `TRAN-CARD-NUM,263,16,ZD` for the sort utility's purposes; the
    --   copybook is the normative declaration and it says alphanumeric.
    --   Bytes 263-278, which is zero-based offset 262.
    card_num        CHAR(16),

    -- Assumptions: TIMESTAMP(6) matches `PIC X(26)` exactly. The
    --   26-character form is `YYYY-MM-DD HH:MM:SS.mmmmmm`: 19 characters to
    --   the second, a point, then six fractional digits. Microsecond
    --   precision therefore truncates no digit on the way in and invents
    --   none on the way out. A measured value from
    --   app/data/ASCII/dailytran.txt is `2022-06-10 19:27:53.000000`. Bytes
    --   279-304.
    orig_ts         TIMESTAMP(6),

    -- Assumptions: the one NOT NULL beyond the primary key in this file,
    --   and the asymmetry against ledger.daily_transactions.proc_ts is
    --   intended. Every writer of this table sets the processing stamp.
    --   app/cbl/CBTRN02C.cbl takes the origination stamp from the feed at
    --   L436 but mints this one at posting time, performing
    --   Z-GET-DB2-FORMAT-TIMESTAMP at L437 and moving the result at L438;
    --   app/cbl/COBIL00C.cbl L230-L232 sets this field and orig_ts together
    --   from one GET-CURRENT-TIMESTAMP. There is also no transaction-master
    --   extract anywhere under app/data to load a blank value from: the
    --   only transaction-shaped seed file is the daily feed. So a row here
    --   without a processing stamp would be a row no baseline path can
    --   produce. Bytes 305-330, which is zero-based offset 304. The
    --   counterpart declaration on daily_transactions carries the evidence
    --   for the nullable side.
    proc_ts         TIMESTAMP(6)  NOT NULL,

    -- Assumptions: a single-column key on the transaction identifier,
    --   because that is the key the baseline declares. app/cbl/CBTRN02C.cbl
    --   L34-L37 selects this file as `ORGANIZATION IS INDEXED` with `RECORD
    --   KEY IS FD-TRANS-ID`, and app/cbl/COTRN00C.cbl rides that same key
    --   to page the list screen, passing `RIDFLD (TRAN-ID)` to STARTBR at
    --   L595, READNEXT at L630 and READPREV at L664. Declaring it as the
    --   primary key is what keeps that keyset paging a single ordered index
    --   scan.
    CONSTRAINT pk_transactions PRIMARY KEY (transaction_id)
);


-- Assumptions: the baseline reaches these records in card order without
--   an alternate index of its own, by sorting a copy: app/jcl/TRANREPT.jcl
--   declares the field at L41 as `TRAN-CARD-NUM,263,16,ZD` and then orders
--   the extract at L46 with ` SORT FIELDS=(TRAN-CARD-NUM,A)`. The statement
--   and report paths read the same card-ordered sequence. An index supplies
--   that order on demand, so the ordering survives without the physically
--   re-sorted copy the sort step produced. It is deliberately not unique: a
--   card has many transactions, which is the entire point of the path. Note
--   that this is a different path from the list screen's, which pages on
--   the primary key rather than on this column.
CREATE INDEX idx_transactions_card_num
    ON ledger.transactions (card_num);


-- Refactoring Rationale: this index is the surviving half of the batch
--   alternate index `AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX`, defined at
--   app/jcl/TRANIDX.jcl L25 and related to the base cluster at L26. Its L27
--   reads `KEYS(26 304)`, a 26-byte key at zero-based offset 304, which is
--   exactly `TRAN-PROC-TS` at one-based bytes 305-330; app/jcl/TRANREPT.jcl
--   L42 independently places the same field at one-based 305 with
--   `TRAN-PROC-DT,305,10,CH`, and the two agree. It is NON-UNIQUE because
--   L28 of that definition declares `NONUNIQUEKEY`: many transactions share
--   one processing timestamp, so a unique index would reject the second row
--   of any posting run and is therefore excluded rather than merely
--   unnecessary. Plain CREATE INDEX is non-unique in PostgreSQL, so the
--   property is carried by the absence of UNIQUE, which is why it is stated
--   here. What does not survive is the `BLDINDEX` step at L52 of the same
--   job, and its companion `DEFINE PATH` at L42: PostgreSQL maintains an
--   index transactionally as rows change, so there is no build to schedule
--   and no path object to relate. Those steps are retired as migration
--   targets only; both still stand unmodified in app/jcl/TRANIDX.jcl, which
--   this file never edits. The KEY is what carries forward.
CREATE INDEX idx_transactions_proc_ts
    ON ledger.transactions (proc_ts);


-- =============================================================================
-- 2. ledger.daily_transactions
--
-- The pre-posting feed that app/cbl/CBTRN02C.cbl reads. app/cpy/CVTRA06Y.cpy
-- L4-L18 was read field by field against app/cpy/CVTRA05Y.cpy L4-L18 and is
-- structurally identical to it: the same thirteen pictures in the same order
-- totalling the same 330 bytes, closed by the same `FILLER PIC X(20)` at L18
-- padding bytes 331-350 to 350. Only the field-name prefix differs, `DALYTRAN-`
-- for `TRAN-`. The FILLER is not mapped to a column here for the same reason it
-- is not on the posted master.
--
-- Trade-offs: the column list is repeated rather than shared. PostgreSQL
--   can inherit or copy a table's shape, and doing so would state these
--   thirteen columns once; that was rejected because the two tables are
--   genuinely diverging already -- proc_ts nullability and the primary key
--   both differ -- so a shared definition would need overriding on both
--   counts, and inheritance additionally makes a parent scan return child
--   rows, which would silently mix the feed into every query over the
--   posted master. The accepted cost is a duplicated column list, and the
--   per-column rationale below is deliberately compact and points at the
--   posted master's declarations rather than restating them, so that one
--   argument cannot drift into two divergent copies.
-- =============================================================================

CREATE TABLE ledger.daily_transactions (

    -- Assumptions: alphanumeric picture with significant leading zeros,
    --   bytes 1-16; argued at ledger.transactions.transaction_id. It is not
    --   a primary key here, for the reason given at the end of this table.
    transaction_id  CHAR(16),

    -- Assumptions: fixed-width code, bytes 17-18; argued at
    --   ledger.transactions.type_cd.
    type_cd         CHAR(2),

    -- Assumptions: a code rather than a quantity despite its `9(04)`
    --   picture, bytes 19-22; argued at ledger.transactions.category_cd.
    category_cd     CHAR(4),

    -- Assumptions: closed label set compared blank-padded, bytes 23-32;
    --   argued at ledger.transactions.source.
    source          CHAR(10),

    -- Assumptions: descriptive text whose trailing blanks are padding,
    --   bytes 33-132; argued at ledger.transactions.description.
    description     VARCHAR(100),

    -- Assumptions: exact fixed point from `PIC S9(09)V99`, bytes 133-143;
    --   argued at ledger.transactions.amount. No approximate type appears
    --   on either side of the posting boundary, so the value the feed
    --   carries and the value that posts are the same digits.
    amount          NUMERIC(11,2),

    -- Assumptions: numeric identifier with no significant leading zero,
    --   bytes 144-152; argued at ledger.transactions.merchant_id.
    merchant_id     BIGINT,

    -- Assumptions: descriptive, bytes 153-202; argued at
    --   ledger.transactions.merchant_name.
    merchant_name   VARCHAR(50),

    -- Assumptions: descriptive, bytes 203-252; argued at
    --   ledger.transactions.merchant_city.
    merchant_city   VARCHAR(50),

    -- Assumptions: postal code with significant leading zeros, bytes
    --   253-262; argued at ledger.transactions.merchant_zip.
    merchant_zip    CHAR(10),

    -- Assumptions: alphanumeric, 30 of 300 measured values carry a
    --   leading zero, bytes 263-278; argued at
    --   ledger.transactions.card_num. This is the column
    --   app/cbl/CBTRN02C.cbl L382 moves into the cross-reference key to
    --   resolve the account, so a shortened value would turn a valid row
    --   into reject reason 100.
    card_num        CHAR(16),

    -- Assumptions: `PIC X(26)` to microsecond precision, bytes 279-304;
    --   argued at ledger.transactions.orig_ts. This is the stamp the feed
    --   supplies, and app/cbl/CBTRN02C.cbl L414 compares its first ten
    --   characters against the account expiration date, so it is populated
    --   on every row the baseline can validate.
    orig_ts         TIMESTAMP(6),

    -- Assumptions: nullable here while ledger.transactions.proc_ts is NOT
    --   NULL. The asymmetry is the point of this declaration and it rests
    --   on four independent findings. (1) The feed leaves it blank: in
    --   app/data/ASCII/dailytran.txt, 105300 bytes holding exactly 300
    --   records of 350 bytes, the 26 bytes at 305-330 are blank on 300 of
    --   300 records, and a blank fixed-width field decodes to NULL. (2) The
    --   blankness is specific to this field rather than a general gap in
    --   the extract: orig_ts in the same 300 records is populated 300
    --   times, for instance `2022-06-10 19:27:53.000000`. (3) The
    --   processing stamp is minted downstream, not supplied upstream:
    --   app/cbl/CBTRN02C.cbl L437 performs Z-GET-DB2-FORMAT-TIMESTAMP and
    --   L438 moves the result into the posted record, whereas L436 copies
    --   orig_ts straight across from this feed. (4) app/cpy/CVTRA06Y.cpy
    --   L17 and app/cpy/CVTRA05Y.cpy L17 declare the field at the identical
    --   `PIC X(26)` width, so the difference between the two tables is
    --   semantic rather than structural and has to be expressed as
    --   nullability rather than as a different type. Asserting NOT NULL
    --   here would reject the seed extract in its entirety. Bytes 305-330.
    proc_ts         TIMESTAMP(6)
);

-- Assumptions: this table has no primary key and no index, and that is
--   the baseline contract rather than an omission. app/cbl/CBTRN02C.cbl
--   L29-L31 selects the feed as `ORGANIZATION IS SEQUENTIAL` with `ACCESS
--   MODE IS SEQUENTIAL` and declares no RECORD KEY clause at all, and
--   app/jcl/POSTTRAN.jcl L30-L31 supplies it as the physical sequential
--   dataset `AWS.M2.CARDDEMO.DALYTRAN.PS`. The posting job reads it front
--   to back and never keys into it. Declaring a primary key on
--   transaction_id would assert a uniqueness the source does not, and would
--   fail a load of any feed that legitimately carried a repeated identifier
--   -- a load the baseline would process without complaint. A surrogate key
--   was excluded for the same reason and because it would be a column no
--   copybook field corresponds to. That all 300 identifiers in the current
--   extract happen to be distinct is a property of one extract, not a
--   contract.


-- =============================================================================
-- 3. ledger.transaction_rejects
--
-- The reject stream app/cbl/CBTRN02C.cbl writes for a transaction that fails
-- validation. It has no copybook: its 430-byte layout is declared inline in
-- that program and is corroborated three independent ways, all read on disk.
-- Every
-- bare line reference in this section and in the comments inside this table
-- belongs to app/cbl/CBTRN02C.cbl unless another file is named.
--   (a) The file description, CBTRN02C L82-L84: `01 FD-REJS-RECORD.` over
--       `05 FD-REJECT-RECORD PIC X(350).` and
--       `05 FD-VALIDATION-TRAILER PIC X(80).`, so 350 + 80 = 430.
--   (b) The working-storage form, CBTRN02C L176-L178, declaring the same 350
--       and 80, with the trailer decomposed at L180-L182 into
--       `WS-VALIDATION-FAIL-REASON PIC 9(04)` and
--       `WS-VALIDATION-FAIL-REASON-DESC PIC X(76)`, so 350 + 4 + 76 = 430.
--   (c) The dataset attributes, app/jcl/POSTTRAN.jcl L36
--       `DCB=(RECFM=F,LRECL=430,BLKSIZE=0)`, inside that job's DALYREJS block
--       at its L34-L38, whose generation base is defined at
--       app/jcl/DALYREJS.jcl
--       L24-L28 as a GENERATIONDATAGROUP with `LIMIT(5)` and `SCRATCH`.
-- The record is fully occupied, so unlike the other three tables there is no
-- FILLER and no byte range to account for.
--
-- The three columns split the record at exactly the two boundaries the program
-- splits it at, and 2500-WRITE-REJECT-REC at CBTRN02C L446 fills them in that
-- order: L447 moves the whole inbound record, L448 moves the trailer.
-- =============================================================================

CREATE TABLE ledger.transaction_rejects (

    -- Trade-offs: the rejected record is retained verbatim as one fixed
    --   350-byte value and is deliberately NOT decomposed into the thirteen
    --   columns its layout would yield, because decomposing it would lose the
    --   very thing it is kept for: the bytes that caused the reject. A record
    --   rejected as reason 100 has a card number that resolved to nothing, and a
    --   reason-102 record may hold an amount no validated column should accept,
    --   so parsing it into typed columns would either fail on the malformed input
    --   or normalise away the evidence, and a re-drive would replay something
    --   other than what arrived. The program treats it as opaque for the same
    --   reason: CBTRN02C L447 is `MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA`, a
    --   wholesale copy with no field-level handling. CHAR rather than VARCHAR
    --   because the existing golden-master comparison reads these records back at
    --   350 bytes including trailing blanks, and only the fixed-width type
    --   guarantees that width whatever a writer supplies: a short write is padded
    --   out to 350 here, whereas VARCHAR(350) would faithfully store the short
    --   value and silently shorten the compared record. The accepted cost is that
    --   reading a single field back out requires a substring at a documented
    --   offset, and one retrieval detail is worth knowing before it looks like
    --   data loss: length() and an explicit cast to text both report the value
    --   with trailing blanks removed, so length() over a 350-byte record returns
    --   the trimmed figure while octet_length() returns 350. The padding is
    --   present in storage and in what a client reads, so a 430-byte
    --   reconstruction must pad the cast result back to 350 before appending the
    --   trailer.
    raw_record   CHAR(350),

    -- Assumptions: SMALLINT is the narrowest exact integer type covering
    --   the declared domain: `PIC 9(04)` at CBTRN02C L181 admits at most
    --   9999, well inside SMALLINT's 32767 ceiling, so a wider type would
    --   reserve bytes no value can use. It is a separate column rather than
    --   a substring of raw_record because it is the field every consumer
    --   filters on -- the reject count that CBTRN02C L229-L230 turns into
    --   the job's return code is a count over this value -- and extracting
    --   it from a character substring on every read would make that count a
    --   string operation over the whole table. Five reason codes are set in
    --   the baseline and all are three digits, at CBTRN02C: 100 at L385
    --   with the text at L386, 101 at L397 with L398, 102 at L410 with
    --   L411, 103 at L417 with L418, and 109 at L556 with L557.
    reason_code  SMALLINT,

    -- Assumptions: descriptive text, so VARCHAR, holding the declared
    --   76-character maximum from CBTRN02C L182 as the constraint. The five
    --   verbatim texts the baseline writes are `INVALID CARD NUMBER FOUND`,
    --   `ACCOUNT RECORD NOT FOUND`, `OVERLIMIT TRANSACTION`, `TRANSACTION
    --   RECEIVED AFTER ACCT EXPIRATION` and, for 109, a second `ACCOUNT
    --   RECORD NOT FOUND` that is byte-identical to 101's, so the code and
    --   not the text is what distinguishes those two. The longest is 103's
    --   at 42 characters, so every one fits with room to spare and the
    --   declared width is preserved as the contract rather than trimmed to
    --   the observed maximum.
    reason_desc  VARCHAR(76)
);

-- Assumptions: no primary key, no unique constraint and no index, because
--   the source has none of the three. app/cbl/CBTRN02C.cbl L46-L47 selects
--   DALYREJS as `ORGANIZATION IS SEQUENTIAL` with no RECORD KEY, and
--   app/jcl/POSTTRAN.jcl L36 gives it `RECFM=F` -- a flat fixed-length
--   stream appended to, never keyed into. Duplicate rows are legitimate
--   here: the same record rejected on two runs is two entries in the
--   stream, and app/jcl/DALYREJS.jcl L26 keeps five generations of exactly
--   that. Any key would have to be a surrogate, which no copybook field
--   corresponds to and which the migration excludes on principle.
--
-- Assumptions: reason 109 is representable here but the baseline never
--   writes a row carrying it, and describing it accurately matters because
--   its text is indistinguishable from 101's. It is set inside
--   2800-UPDATE-ACCOUNT-REC, the paragraph at CBTRN02C L545, on the INVALID
--   KEY path at L555 of the account REWRITE at L554 -- a post-validation
--   failure, whereas 101 fires on the initial account read during
--   validation. That paragraph runs at CBTRN02C L441, inside
--   2000-POST-TRANSACTION, which the main loop only enters after L211 `IF
--   WS-VALIDATION-FAIL-REASON = 0` has already found the reason zero, and
--   the enclosing PERFORM loop at L202-L219 never re-tests it afterwards.
--   So the code is reachable but no reject row for it is, and it is a
--   REWRITE failure code rather than a validation reject. The column domain
--   admits it so a migrated implementation can persist what the baseline
--   could only leave in a field.
--
-- Assumptions: rejects are a persisted table rather than an error channel
--   because a reject is a normal outcome in this pipeline, not a fault.
--   CBTRN02C L229-L230 read `IF WS-REJECT-COUNT > 0` then `MOVE 4 TO
--   RETURN-CODE`, which is the warn tier and not a failure, so the run
--   completes and its rejects have to survive it. Acting on that return
--   code belongs to the batch orchestration, not to this schema; only the
--   durability of the rows is this file's concern.


-- =============================================================================
-- 4. ledger.transaction_category_balances
--
-- The per-account, per-type, per-category running balance.
-- app/cpy/CVTRA01Y.cpy L4-L10 declares four elementary fields totalling 28
-- bytes, closed by `FILLER PIC X(22)` at L10 padding bytes 29-50 out to the
-- declared 50; that FILLER is not mapped to a column, for the same reason as on
-- the other records. The offsets are corroborated outside the copybook by
-- app/jcl/PRTCATBL.jcl, which declares the type code at one-based 12 for 2
-- bytes on L48, the category code at 14 for 4 on L49 and the balance at 18 for
-- 11 on
-- L50 -- matching the copybook widths exactly.
-- =============================================================================

CREATE TABLE ledger.transaction_category_balances (

    -- Assumptions: BIGINT is required rather than merely chosen.
    --   app/cpy/CVTRA01Y.cpy L6 declares `TRANCAT-ACCT-ID PIC 9(11)`, whose
    --   maximum of 99999999999 exceeds a 32-bit integer's 2147483647, so a
    --   narrower integer type could not hold the declared domain. It is an
    --   account identifier and a magnitude, not a code, which is why it
    --   maps to an integer type while the category code beside it does not.
    --   Bytes 1-11.
    account_id   BIGINT       NOT NULL,

    -- Assumptions: the same two-character code as on the transaction
    --   tables and deliberately the same type, so that a join or a lookup
    --   across the two needs no cast, bytes 12-13; argued at
    --   ledger.transactions.type_cd.
    type_cd      CHAR(2)      NOT NULL,

    -- Assumptions: CHAR(4) for the same code-versus-quantity reason as on
    --   the transaction tables, bytes 14-17; argued at
    --   ledger.transactions.category_cd. Being part of the key makes it
    --   stronger here, not weaker: `0001` and `1` must not be two keys.
    category_cd  CHAR(4)      NOT NULL,

    -- Assumptions: exact fixed point at precision 11, scale 2, from
    --   `TRAN-CAT-BAL PIC S9(09)V99` at L9 -- the same domain as the
    --   transaction amount that accumulates into it, which matters because
    --   app/cbl/CBTRN02C.cbl adds one to the other at L508 on the create
    --   path and at L527 on the update path. Both operands therefore carry
    --   the same precision and scale, and no approximate type appears in
    --   between. The measured field confirms the width: in
    --   app/data/ASCII/tcatbal.txt bytes 18-28 hold 11 characters ending in
    --   a sign overpunch, `0000000000{` on the first record. Bytes 18-28.
    balance      NUMERIC(11,2),

    -- Assumptions: a three-part composite key with exactly this arity and
    --   exactly this component order, taken from the group item the
    --   baseline keys the file on. app/cpy/CVTRA01Y.cpy L5 declares
    --   `TRAN-CAT-KEY` as a group over the three fields at L6, L7 and L8,
    --   and app/cbl/CBTRN02C.cbl L57-L60 selects the file as `ORGANIZATION
    --   IS INDEXED` with `RECORD KEY IS FD-TRAN-CAT-KEY`.
    --   2700-UPDATE-TCATBAL builds precisely that key before reading, at
    --   L469, L470 and L471. The component order is confirmed a second time
    --   outside the program by app/jcl/PRTCATBL.jcl L52, ` SORT
    --   FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)`. The
    --   arity is fixed at three by the copybook, so no fourth component and
    --   no surrogate key is admissible.
    CONSTRAINT pk_transaction_category_balances
        PRIMARY KEY (account_id, type_cd, category_cd)
);

-- Alternatives Considered: the composite natural key above is the sole
--   key and carries no default, no generated value and no ON
--   CONFLICT-shaped helper, so that a caller can still tell an insert from
--   an update. The distinction is observable behaviour in the baseline and
--   is tested as such: app/cbl/CBTRN02C.cbl sets a flag to 'N' at L473,
--   reads the row at L474, flips the flag on INVALID KEY at L475, and then
--   branches at L495-L499 into 2700-A-CREATE-TCATBAL-REC at L503, which
--   INITIALIZEs and WRITEs at L510, or 2700-B-UPDATE-TCATBAL-REC at L526,
--   which adds and REWRITEs at L528. Collapsing that into an opaque
--   single-statement merge was considered and rejected, because it would
--   hide which path ran and the two paths are separately asserted. Leaving
--   the key as the only constraint keeps a uniqueness violation, and
--   therefore the create-versus -update decision, visible to the service
--   that has to report it.
