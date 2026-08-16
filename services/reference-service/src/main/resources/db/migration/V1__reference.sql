-- =============================================================================
-- services/reference-service/src/main/resources/db/migration/V1__reference.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Declares the six tables of the reference schema, the foreign key that
--   refuses the delete of a transaction type that still has categories, and the
--   check constraint that bounds the phone-area-code classification. This is
--   the first Flyway versioned migration of reference-service, and it is the
--   shape that both V2__seed_reference.sql and the JPA entities under
--   src/main/java/com/carddemo/reference/domain are written against.
--
--   Each table, the baseline record layout it is derived from, and the declared
--   length of that layout:
--     transaction_types       app/cpy/CVTRA03Y.cpy  RECLN 60
--     transaction_categories  app/cpy/CVTRA04Y.cpy  RECLN 60
--     disclosure_groups       app/cpy/CVTRA02Y.cpy  RECLN 50
--     us_phone_area_codes     app/cpy/CSLKPCDY.cpy  L24 field, 490 codes
--     us_states               app/cpy/CSLKPCDY.cpy  L1012 field, 56 codes
--     us_state_zip_prefixes   app/cpy/CSLKPCDY.cpy  L1071 field, 240 codes
--
--   Column types are derived from those layouts field by field. The Db2 objects
--   of the transaction-type extension corroborate the first two tables
--   independently (app/app-transaction-type-db2/ddl/TRNTYPE.ddl and
--   TRNTYCAT.ddl, restated in that tree's ctl/DB2CREAT.ctl L75-L81 and its
--   dcl/DCLTRTYP.dcl and dcl/DCLTRCAT.dcl host structures), and the IDCAMS KEYS
--   operands confirm every key width a third time: app/jcl/TRANTYPE.jcl L40
--   KEYS(2 0), app/jcl/TRANCATG.jcl L40 KEYS(6 0) and app/jcl/DISCGRP.jcl L40
--   KEYS(16 0), each against the RECORDSIZE on the following line.
--
--   Corresponding baseline contract: this file is the target analogue of the
--   IDCAMS DEFINE CLUSTER of those same three jobs (STEP10 at L33, its DEFINE
--   CLUSTER at L36, INDEXED at L44). The REPRO of STEP15 at L54/L61 loads rows
--   rather than defining structure, so its target analogue is
--   V2__seed_reference.sql and not this file. IDCAMS BLDINDEX has no target
--   analogue at all, because PostgreSQL maintains an index inside the
--   transaction that modifies the table rather than as a separate rebuild job.
--
-- Invocation:
--   Flyway applies this script once, as a unit, resolved by its V1 version
--   prefix. It takes no parameter and no substitution variable, and it contains
--   no psql meta-command, so it runs identically under Flyway, under
--   "psql -v ON_ERROR_STOP=1 -f", and through a driver cursor.
--
-- Not declared here:
--   No CREATE SCHEMA, CREATE ROLE, GRANT or ALTER DEFAULT PRIVILEGES, and no
--   SET search_path. data-migration/sql/V0__schemas_and_roles.sql creates the
--   carddemo_reference owner role in the loop at its L361 and the reference
--   schema itself at its L491-L492, and it states that boundary for every
--   per-service migration at its L11-L16. The
--   schema is pinned again by this service's Flyway default-schema and by its
--   DataSourceConfig. The baseline's own privilege statements are retired
--   rather than reproduced: app/app-transaction-type-db2/ctl/DB2CREAT.ctl
--   L72-L73 grants a tablespace and its L103-L105 grants table privileges, and
--   both belong to the bootstrap script above.
--
-- Raises:
--   - SQLSTATE 23503 (foreign_key_violation) when a transaction type that still
--     has categories is deleted. That refusal is an expected user outcome
--     rather than a fault: Spring surfaces it as DataIntegrityViolationException,
--     the GlobalExceptionHandler inherited from common-lib turns it into HTTP
--     409, and the reference-data screen reports that the delete was blocked. A
--     500 carrying a driver stack trace would be a defect.
--   - SQLSTATE 23505 (unique_violation) on a duplicate key in any of the six
--     tables.
--   - SQLSTATE 23514 (check_violation) when a phone-area-code row carries a
--     classification other than the two the baseline defines.
--
-- Rationale for the non-obvious decisions in this file:
--   - Alternatives Considered: every object is schema-qualified rather than
--     left to a search_path. Relying on the path would make the file's result
--     depend on who invoked it, so the same script could create the tables in
--     public when run by an operator and in reference when run by Flyway.
--     Qualifying costs one prefix per statement and removes that difference.
--   - Assumptions: no column is nullable. The layouts these tables come from
--     are fixed-length VSAM records in which every field always occupies its
--     bytes, so the baseline has no way to express absence and no reader that
--     tests for it. A nullable column would invent a state that no baseline
--     record can hold and that no migrated code path handles.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- reference.transaction_types  <-  app/cpy/CVTRA03Y.cpy (RECLN 60)
--
-- Layout mapped: L5 TRAN-TYPE PIC X(02) -> type_cd; L6 TRAN-TYPE-DESC
-- PIC X(50) -> description. L7 FILLER PIC X(08) is dropped, which is the one
-- FILLER of this record; 2 + 50 + 8 accounts for all 60 declared bytes.
-- -----------------------------------------------------------------------------
CREATE TABLE reference.transaction_types (
    -- Assumptions: CHAR(2) rather than VARCHAR(2) because the width is
    --     part of the key contract, not display padding. The concatenated
    --     category key in app/data/ASCII/trancatg.txt is formed by
    --     positional concatenation ("010001" = type "01" then category
    --     "0001"), so a value that stored "1" instead of "01" would not
    --     locate its own categories. app/app-transaction-type-db2/ddl/
    --     TRNTYPE.ddl declares TR_TYPE CHAR(2) NOT NULL and
    --     dcl/DCLTRTYP.dcl L38 carries it as PIC X(2), so the copybook and
    --     the Db2 definition agree and there is no tension to resolve.
    type_cd      CHAR(2)      NOT NULL,

    -- Assumptions: VARCHAR(50) rather than CHAR(50) because the trailing
    --     blanks in a 50-byte COBOL field are padding to the fixed record
    --     length rather than data. Db2 reached the same conclusion for the
    --     same field: TRNTYPE.ddl declares TR_DESCRIPTION VARCHAR(50), and
    --     dcl/DCLTRTYP.dcl L42-L46 generates it as the length-plus-text pair
    --     DCL-TR-DESCRIPTION-LEN PIC S9(4) COMP with
    --     DCL-TR-DESCRIPTION-TEXT PIC X(50), which is DCLGEN's signature for
    --     a varying-length column. CHAR(50) would make every comparison and
    --     every response body carry the padding.
    description  VARCHAR(50)  NOT NULL,

    -- Assumptions: this column is what the published contract's concurrency
    --     mechanism rests on. The reference API declares a RecordVersion on the
    --     transaction-type representation and on its replace request and answers
    --     409 with the version the row holds, none of which a table without this
    --     column could supply.
    -- Assumptions: the mechanism is the baseline's own, not an addition.
    --     app/app-transaction-type-db2/cbl/COTRTUPC.cbl snapshots the record
    --     it read into TTUP-OLD-DETAILS at its L328-L331, carries
    --     WS-DATACHANGED-FLAG at L78 with the condition
    --     DATA-WAS-CHANGED-BEFORE-UPDATE at L183, compares the snapshot
    --     against the stored row in 1205-COMPARE-OLD-NEW at L783, and commits
    --     with SYNCPOINT at L454 only when they agree. That is optimistic
    --     concurrency across a pseudo-conversational gap, and a version
    --     counter is the same guarantee expressed natively: the migration
    --     already maps it that way for the three mutable master records, so
    --     applying it to the one reference table the baseline maintains
    --     through a screen keeps one mechanism rather than two.
    -- Alternatives Considered: carrying the before image itself -- having a
    --     replace request echo back the description it read, and comparing that.
    --     Rejected because it compares only the fields the client chose to echo,
    --     so a column added later is silently outside the check, whereas a
    --     counter covers the whole row by construction.
    -- Trade-offs: the two lookup tables and disclosure_groups get NO such
    --     column, and the omission is deliberate. Nothing in this contract
    --     replaces a row in any of them -- they are seeded reference data with
    --     read-only operations -- so a version column there would be written
    --     once and never read, and its presence would suggest a maintenance
    --     path that does not exist.
    version      BIGINT       NOT NULL DEFAULT 0,

    -- Assumptions: no separate unique index accompanies this
    --     primary key. The baseline declares one at
    --     app/app-transaction-type-db2/ddl/XTRNTYPE.ddl, a UNIQUE INDEX on
    --     TRANSACTION_TYPE (TR_TYPE ASC), because in Db2 the index is the
    --     object that enforces the key. PostgreSQL implements a PRIMARY KEY
    --     by building exactly that unique B-tree itself, so reproducing
    --     XTRNTYPE would create a second index identical to the first: two
    --     structures to write on every insert and to keep in cache, enforcing
    --     one rule. The baseline behaviour is preserved by the constraint
    --     below; the divergence in object count is recorded in
    --     docs/architecture/cobol-to-service-traceability.md.
    CONSTRAINT pk_transaction_types PRIMARY KEY (type_cd)
);


-- -----------------------------------------------------------------------------
-- reference.transaction_categories  <-  app/cpy/CVTRA04Y.cpy (RECLN 60)
--
-- Layout mapped: L6 TRAN-TYPE-CD PIC X(02) -> type_cd; L7 TRAN-CAT-CD
-- PIC 9(04) -> cat_cd; L8 TRAN-CAT-TYPE-DESC PIC X(50) -> description. Both key
-- fields sit under the L5 group TRAN-CAT-KEY, which is why they form the
-- composite primary key together. L9 FILLER PIC X(04) is dropped, which is the
-- one FILLER of this record; 2 + 4 + 50 + 4 accounts for all 60 declared bytes.
-- -----------------------------------------------------------------------------
CREATE TABLE reference.transaction_categories (
    type_cd      CHAR(2)      NOT NULL,

    -- Assumptions: CHAR(4), even though app/cpy/CVTRA04Y.cpy L7 declares
    --     TRAN-CAT-CD as PIC 9(04) and a numeric picture would otherwise map
    --     to an integer column. Six independent sources in the baseline carry
    --     this field as character, and an integer column would drop the
    --     leading zeros that all six depend on, turning "0001" into 1:
    --     (1) app/app-transaction-type-db2/ddl/TRNTYCAT.ddl L3 declares
    --     TRC_TYPE_CATEGORY CHAR(4) NOT NULL;
    --     (2) that tree's ctl/DB2CREAT.ctl L77 restates CHAR(4);
    --     (3) its dcl/DCLTRCAT.dcl L42-L43 generates the host variable as
    --     DCL-TRC-TYPE-CATEGORY PIC X(4);
    --     (4) app/data/ASCII/trancatg.txt stores the codes zero-padded to
    --     four digits, in the concatenated key form "010001";
    --     (5) that tree's ctl/DB2LTCAT.ctl seeds them as quoted string
    --     literals, '0001' through '0005', not as numerics;
    --     (6) app/jcl/TRANCATG.jcl L40 defines KEYS(6 0), a six-byte key
    --     that only balances if this field is four fixed-width bytes
    --     beside the two of type_cd.
    --     The digits-only half of PIC 9(04) is deliberately not re-asserted
    --     as a column CHECK. Nothing reads this field arithmetically, and the
    --     baseline does not enforce it at rest either: IDCAMS REPRO copies
    --     bytes into the cluster without consulting a picture clause. The two
    --     properties the composite key does depend on, fixed width and
    --     preserved leading zeros, are exactly what CHAR(4) guarantees.
    cat_cd       CHAR(4)      NOT NULL,

    -- Assumptions: VARCHAR(50) for the same reason as the description on
    --     transaction_types. TRNTYCAT.ddl L4 declares TRC_CAT_DATA
    --     VARCHAR(50) NOT NULL, and dcl/DCLTRCAT.dcl L47-L51 generates the
    --     matching length-plus-text pair.
    description  VARCHAR(50)  NOT NULL,

    -- Assumptions: the same version column and the same reasoning as
    --     reference.transaction_types above, which is where the mechanism, the
    --     baseline citation and the rejected alternatives are recorded. It is
    --     repeated here rather than cross-referenced only because the two
    --     tables are maintained through the same screen family and a reader
    --     arriving at either one needs to know the column is not decoration.
    -- Assumptions: the default of zero applies to the seeded rows as well
    --     as to inserted ones, so every row has a readable version from the
    --     moment the migration runs and a caller never meets a null it has to
    --     interpret. The service increments it on a successful replace; the
    --     database does not, because an increment triggered in the database
    --     would fire for a write the service did not intend as a revision.
    version      BIGINT       NOT NULL DEFAULT 0,

    -- Assumptions: this composite primary key is also what
    --     satisfies app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl, the
    --     baseline's UNIQUE INDEX on (TRC_TYPE_CODE ASC, TRC_TYPE_CATEGORY
    --     ASC). PostgreSQL builds a unique B-tree on exactly these two
    --     columns in exactly that order to enforce the key, so the index the
    --     baseline names already exists here under a different name. No
    --     separate CREATE INDEX is issued, because a second index on the same
    --     columns in the same order would enforce nothing the constraint does
    --     not and would be written on every insert.
    CONSTRAINT pk_transaction_categories PRIMARY KEY (type_cd, cat_cd),

    -- Assumptions: RESTRICT, and never CASCADE, SET NULL or NO
    --     ACTION. This constraint is the single load-bearing object of the
    --     file. app/app-transaction-type-db2/ddl/TRNTYCAT.ddl states it at
    --     L6-L7 as FOREIGN KEY TRC_TYPE_CODE (TRC_TYPE_CODE) REFERENCES
    --     CARDDEMO.TRANSACTION_TYPE (TR_TYPE) ON DELETE RESTRICT, and that
    --     tree's ctl/DB2CREAT.ctl L96-L99 restates it as an ALTER TABLE, so
    --     the baseline asserts it twice.
    --
    --       What it preserves is an observable outcome, not merely integrity.
    --       In the baseline, Db2 answers the refused delete with SQLCODE -532,
    --       and both online programs treat that code as a distinct user-facing
    --       result rather than as a failure:
    --       app/app-transaction-type-db2/cbl/COTRTLIC.cbl L1914 branches on
    --       WHEN SQLCODE = -532, sets its delete-requested state at L1915,
    --       moves the message at L1918-L1920 and leaves through the normal exit
    --       at L1925, whereas its WHEN OTHER arm at L1926 reports a failed
    --       delete instead. COTRTUPC.cbl L1638 takes the same branch. Two
    --       different outcomes, distinguished by the baseline and therefore
    --       distinguished here.
    --
    --       CASCADE would delete the dependent categories silently, which is a
    --       different observable outcome for the same user action. Enforcing
    --       this only as an application pre-check would leave the rule
    --       raceable: a category inserted between the check and the delete
    --       would slip through, and the constraint cannot be raced.
    --
    --       The refusal reaches the user as HTTP 409, mapped by the
    --       GlobalExceptionHandler that reference-service inherits from
    --       common-lib; this service declares no advice of its own, so the
    --       mapping cannot drift per service.
    CONSTRAINT fk_transaction_categories_type
        FOREIGN KEY (type_cd) REFERENCES reference.transaction_types (type_cd)
        ON DELETE RESTRICT
);

-- Trade-offs: no index is created on transaction_categories.type_cd for
--     the foreign key's benefit. PostgreSQL does not index a referencing
--     column automatically, and enforcing RESTRICT means scanning this table
--     for children whenever a parent row is deleted. Here that scan is
--     already covered: type_cd is the leading column of
--     pk_transaction_categories, and a B-tree can be searched on a leading
--     column prefix alone. The compromise accepted is that the constraint
--     depends on the primary key's column order rather than on an index of
--     its own, so reordering that key to (cat_cd, type_cd) would silently
--     turn every parent delete into a sequential scan.


-- -----------------------------------------------------------------------------
-- reference.disclosure_groups  <-  app/cpy/CVTRA02Y.cpy (RECLN 50)
--
-- Layout mapped: L6 DIS-ACCT-GROUP-ID PIC X(10) -> acct_group_id;
-- L7 DIS-TRAN-TYPE-CD PIC X(02) -> tran_type_cd; L8 DIS-TRAN-CAT-CD PIC 9(04)
-- -> tran_cat_cd; L9 DIS-INT-RATE PIC S9(04)V99 -> interest_rate. The first
-- three sit under the L5 group DIS-GROUP-KEY and form the composite primary key
-- together, which app/jcl/DISCGRP.jcl L40 KEYS(16 0) confirms as 10 + 2 + 4
-- bytes at offset zero. L10 FILLER PIC X(28) is dropped, which is the one
-- FILLER of this record; 10 + 2 + 4 + 6 + 28 accounts for all 50 declared bytes.
-- -----------------------------------------------------------------------------
CREATE TABLE reference.disclosure_groups (
    -- Assumptions: CHAR(10) exactly, because the space padding is part of
    --     the stored key rather than a formatting artefact. The interest
    --     program declares this field as PIC X(10) at app/cbl/CBACT04C.cbl
    --     L79 and, when a rate lookup misses, falls back by moving the
    --     literal 'DEFAULT' into it at L437 before re-reading. A 7-character
    --     literal moved into a 10-byte alphanumeric field is left-justified
    --     and space-filled, so the key it then searches for is
    --     'DEFAULT' followed by three spaces -- which is precisely how
    --     app/data/ASCII/discgrp.txt stores those rows: the file holds three
    --     group values in its 51 rows, and the 17 default-group rows begin
    --     with 'DEFAULT' plus three spaces in the first ten bytes.
    --     CHAR(10) reproduces that padding on write and ignores it on
    --     comparison, so both 'DEFAULT' and its padded form find the same
    --     rows. VARCHAR(10) would store the unpadded literal as a distinct
    --     value from the padded one loaded out of the seed file, and the
    --     default-rate fallback would find nothing.
    acct_group_id  CHAR(10)      NOT NULL,

    tran_type_cd   CHAR(2)       NOT NULL,

    -- Assumptions: CHAR(4) for the same six reasons set out at
    --     transaction_categories.cat_cd. This column holds the same category
    --     codes, and app/data/ASCII/discgrp.txt stores them zero-padded in
    --     the same positional key.
    tran_cat_cd    CHAR(4)       NOT NULL,

    -- Alternatives Considered: NUMERIC(6,2) is the exact image of
    --     PIC S9(04)V99 -- four integer digits and two fractional, so the
    --     same 9999.99 ceiling and the same hundredth resolution -- and no
    --     floating-point type was admissible. This rate is not a value that
    --     is merely displayed; it is an operand. app/cbl/CBACT04C.cbl L464-
    --     L465 computes ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200, and the result
    --     is written as a generated interest transaction that then posts to a
    --     balance. REAL or DOUBLE PRECISION cannot represent a rate such as
    --     15.00 or 2.50 exactly, so the error would enter the multiplication
    --     before the division and settle into money that a statement reports
    --     and a customer is charged. The seed rates in
    --     app/data/ASCII/discgrp.txt are held as zoned decimal with a sign
    --     overpunch in the trailing byte, which is itself an exact
    --     fixed-point encoding; NUMERIC carries that across unchanged, and
    --     the arithmetic stays exact from the column through BigDecimal to
    --     the JSON string on the wire.
    interest_rate  NUMERIC(6,2)  NOT NULL,

    CONSTRAINT pk_disclosure_groups
        PRIMARY KEY (acct_group_id, tran_type_cd, tran_cat_cd)
);

-- Alternatives Considered: no foreign key runs from disclosure_groups to
--     transaction_categories, although the type and category columns look
--     like they should reference it. The baseline declares no such
--     relationship: app/cpy/CVTRA02Y.cpy is a standalone VSAM layout, the
--     cluster defined by app/jcl/DISCGRP.jcl L36 stands on its own, and the
--     only referential rule the baseline states anywhere in this domain is
--     the one on transaction_categories. Adding a constraint the baseline
--     does not have would make the seed load ordering-sensitive and could
--     refuse a disclosure row that the baseline accepts.


-- -----------------------------------------------------------------------------
-- reference.us_phone_area_codes  <-  app/cpy/CSLKPCDY.cpy, the L24 field
--
-- The copybook holds its address allow-lists as condition names on three
-- working-storage fields rather than as data, so there is no record layout and
-- no FILLER to drop for this table or the two that follow. Membership is the
-- whole content: the baseline tests a candidate value against a list of
-- literals, and the migrated form of that test is whether a row exists.
--
-- L24 declares WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX, three bytes, and carries
-- three condition names over that one field: VALID-PHONE-AREA-CODE at L30 with
-- 490 literals, VALID-GENERAL-PURP-CODE at L521 with 410, and
-- VALID-EASY-RECOG-AREA-CODE at L931 with 80. The codes are sourced in the
-- copybook's own L26-L28 comment from the North American Numbering Plan
-- Administrator's NPA report.
--
-- Read by account-service's AddressValidationService, which queries this table
-- and the two below but neither owns nor seeds them. A code missing from the
-- V2 seed therefore does not fail here; it surfaces as an address rejected
-- during account maintenance in a different service.
-- -----------------------------------------------------------------------------
CREATE TABLE reference.us_phone_area_codes (
    area_cd     CHAR(3)  NOT NULL,

    -- Alternatives Considered: one table with a classification column,
    --     rather than two tables or a pair of boolean flags, because the two
    --     narrower lists partition the broad one exactly. Counting the
    --     literals in app/cpy/CSLKPCDY.cpy gives 410 general-purpose codes and
    --     80 easily-recognisable codes; they sum to the 490 of
    --     VALID-PHONE-AREA-CODE, they share no member, and their union is
    --     equal to it as a set. A total and disjoint partition is what makes
    --     the single column sufficient and correct: every code has a class,
    --     so the column is NOT NULL with no absent case, and no code has two,
    --     so one column suffices where a flag pair would admit the
    --     both-true and both-false rows that the copybook cannot express.
    --     Two separate tables would additionally split a domain that the
    --     copybook keeps on one field, and would leave the broad
    --     490-code test -- the only one account-service performs -- to a
    --     union query rather than a primary-key probe.
    code_class  CHAR(1)  NOT NULL,

    CONSTRAINT pk_us_phone_area_codes PRIMARY KEY (area_cd),

    -- Assumptions: two values and no more, mirroring the partition above.
    --     'G' is the general-purpose list at app/cpy/CSLKPCDY.cpy L521 and 'E'
    --     the easily-recognisable list at L931. The single-character coded
    --     domain with a CHECK follows the form the baseline uses for every
    --     other classification field it carries, and the constraint is what
    --     keeps the seed honest: because the partition is total, a row that
    --     could not be classified would be a defect in the seed rather than a
    --     third legitimate class, and the check reports it at load time
    --     instead of leaving account-service to read a class it cannot
    --     interpret.
    CONSTRAINT ck_us_phone_area_codes_class CHECK (code_class IN ('G', 'E'))
);


-- -----------------------------------------------------------------------------
-- reference.us_states  <-  app/cpy/CSLKPCDY.cpy, the L1012 field
--
-- L1012 declares US-STATE-CODE-TO-EDIT PIC X(2) and L1013 carries
-- VALID-US-STATE-CODE over it with 56 literals: the fifty states plus the
-- district, territories and military mailing codes the postal service treats
-- alike. A single-column table is the whole of the contract, because the
-- baseline asks only whether a candidate code is in the list.
--
-- The comment immediately above the field, at L1011, reads as though it
-- introduced the phone area codes rather than the states. It is cited here as
-- it stands and is not altered; app/** is read as reference only.
-- -----------------------------------------------------------------------------
CREATE TABLE reference.us_states (
    -- Assumptions: CHAR(2) because the baseline compares a two-byte field
    --     against two-byte literals with no trimming, and account-service
    --     probes this table by equality on a value that reached it from a
    --     fixed-width source. Every literal in the copybook list is exactly
    --     two characters, so unlike the disclosure-group id there is no
    --     padding in the data itself and the two candidate types would store
    --     these values identically. The difference is in comparison, and it
    --     is silent: bpchar ignores trailing blanks, so a code that arrives
    --     as 'AL ' from a fixed-width field still matches its row, whereas
    --     under VARCHAR(2) the same probe returns nothing. It would not
    --     error either, because a trailing blank is truncated away rather
    --     than rejected, so the mismatch would surface only as an address
    --     that account-service reports as invalid.
    state_cd  CHAR(2)  NOT NULL,

    CONSTRAINT pk_us_states PRIMARY KEY (state_cd)
);


-- -----------------------------------------------------------------------------
-- reference.us_state_zip_prefixes  <-  app/cpy/CSLKPCDY.cpy, the L1071 field
--
-- L1071 declares US-STATE-ZIPCODE-TO-EDIT, whose L1072 subordinate
-- US-STATE-AND-FIRST-ZIP2 PIC X(4) carries VALID-US-STATE-ZIP-CD2-COMBO at
-- L1073 with 240 literals. Each literal is a state code followed by the first
-- two digits of a postal code, so the column is one concatenated value rather
-- than two: the baseline builds the four bytes and tests the pair as a unit,
-- and splitting it into separate columns here would require every reader to
-- reassemble it before comparing.
-- -----------------------------------------------------------------------------
CREATE TABLE reference.us_state_zip_prefixes (
    -- Assumptions: CHAR(4) holding the state code and the two leading
    --     postal digits together, in that order, exactly as the copybook
    --     literals are written. The leading digits are significant, so the
    --     postal half cannot become numeric without losing a code whose first
    --     digit is zero.
    state_zip_cd  CHAR(4)  NOT NULL,

    CONSTRAINT pk_us_state_zip_prefixes PRIMARY KEY (state_zip_cd)
);
