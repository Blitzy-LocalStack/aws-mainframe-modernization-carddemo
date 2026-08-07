-- =============================================================================
-- services/card-service/src/main/resources/db/migration/V1__card.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Establishes the entire persistent shape of the card bounded context: one
--   table, card.cards, and one secondary index over it, idx_cards_account_id.
--
--   The table is derived field for field from app/cpy/CVACT02Y.cpy, where
--   01 CARD-RECORD is declared at :4 and its six named fields plus trailing
--   padding occupy the 150-byte record announced at :2. That copybook is the
--   normative source: each PICTURE clause decides the column type it becomes,
--   and no width, scale or value domain here was chosen independently of it.
--   This file is then the authoritative column list for the whole module --
--   the entity, repository, mapper and OpenAPI contract mirror it rather than
--   re-deriving from the copybook, so one reading of the record exists.
--
-- Parameters:
--   A migration takes no arguments. Its inputs are the Flyway keys declared in
--   application.yml (spring.flyway.schemas, default-schema, locations, and
--   spring.jpa.properties.hibernate.default_schema) and the pre-existing
--   schema, owner role and privilege graph created by
--   data-migration/sql/V0__schemas_and_roles.sql, which is the exclusive
--   authority for schemas, roles and grants. spring.flyway.create-schemas is
--   false, so this script may migrate the card schema and may not create it,
--   and it runs as the schema owner, which is how the table below acquires its
--   batch and reporting read privileges without issuing a single GRANT.
--
-- Return values:
--   Table card.cards with seven columns, primary key pk_cards, check
--   constraint ck_cards_active_status, and non-unique index
--   idx_cards_account_id. Deliberately absent: no schema, role, privilege
--   change, view, trigger, sequence or seed data. Because
--   spring.jpa.hibernate.ddl-auto is none, Hibernate emits no DDL, so anything
--   omitted here is simply absent from the running system.
--
-- Exceptions or errors:
--   Assumptions: the failure modes below are intended behaviour rather than
--   defects in this file. An absent schema or insufficient privilege fails
--   naming the schema it wanted rather than quietly creating a second card
--   schema carrying none of V0's privileges. A missing
--   org.flywaydb:flyway-database-postgresql surfaces only at startup, because
--   the engine alone cannot match a PostgreSQL connection -- so a successful
--   build is not evidence that this script can run. A checksum mismatch, or a
--   populated schema with no history table, aborts startup because
--   baseline-on-migrate is false. Treat this file as immutable from its first
--   successful application: a correction arrives as a new versioned migration,
--   never as an edit here, because the alternative leaves a table of the wrong
--   shape in place and undetected.
--
-- Provenance:
--   Every app/** path cited here is REFERENCE-ONLY and is never modified.
--   Primary sources: app/cpy/CVACT02Y.cpy for the record layout;
--   app/jcl/CARDFILE.jcl for the cluster, alternate index and path
--   definitions; app/cbl/COCRDUPC.cbl for the value domains, the date
--   decomposition and the before-image concurrency discipline;
--   app/cbl/CBACT02C.cbl and app/cbl/COCRDSLC.cbl for the record length and
--   access paths; and app/data/ASCII/carddata.txt as the seed extract every
--   empirical statement below was measured against. Field-level lineage,
--   including the single target-side rename noted at expiration_date, is in
--   docs/architecture/data-model-and-schema-mapping.md. The documentation
--   convention is docs/CODE_DOCUMENTATION_STANDARD.md, whose SQL section
--   requires this header plus a rationale on each non-obvious constraint and
--   index.

-- Assumptions: every object below is written schema-qualified as
--   card.<object> rather than left to the connection's search path. The
--   pooled datasource does pin that path to this one schema
--   (the connection-init-sql search path in application.yml), so an unqualified name would resolve
--   correctly today; the qualification is what keeps the script
--   deterministic when something other than that pool applies it, which
--   the repository integration test and any operator-run session both
--   are. The failure it converts is the silent kind: an unqualified
--   CREATE TABLE run under a search path someone later changed succeeds
--   against the wrong schema, whereas a qualified one fails and names
--   the schema it could not find.
CREATE TABLE card.cards (

    -- Assumptions: character, of a settled width, and never a numeric
    --   type at any hop. CARD-NUM is PIC X(16)
    --   (app/cpy/CVACT02Y.cpy:5) and the cluster keys on all sixteen of
    --   those bytes from offset zero (app/jcl/CARDFILE.jcl:54), so the
    --   width is part of the contract and not merely a ceiling. Two
    --   concrete consequences rule out an integer column: five of the
    --   fifty records in app/data/ASCII/carddata.txt begin with a zero
    --   digit, which any numeric type discards on the way in, and
    --   sixteen significant digits exceed what an IEEE-754 double
    --   represents exactly, so a client that parsed the value as a JSON
    --   number would hand back a different card. CHAR rather than
    --   VARCHAR because a value shorter than sixteen is a defect to be
    --   refused, not a shorter name to be stored.
    card_num          CHAR(16)     NOT NULL,

    -- Assumptions: CARD-ACCT-ID is PIC 9(11)
    --   (app/cpy/CVACT02Y.cpy:6) and is used throughout as an identifier
    --   rather than as a quantity, so it maps to an integer type; eleven
    --   digits overflow INTEGER and sit well inside BIGINT. NOT NULL
    --   because the alternate index over this field is declared UPGRADE
    --   (app/jcl/CARDFILE.jcl:87), meaning the baseline maintains an
    --   entry for every base record as it changes: a card carrying no
    --   account would have no entry to maintain and could not be reached
    --   at all through the access path the index at the foot of this file
    --   carries across.
    account_id        BIGINT       NOT NULL,

    -- Refactoring Rationale: the baseline holds CARD-CVV-CD as three
    --   display digits in the clear (app/cpy/CVACT02Y.cpy:7), on files
    --   defined with JOURNAL(NO) and RECOVERY(NONE)
    --   (app/csd/CARDDEMO.CSD:19 and :21 for the alternate index, :31
    --   and :33 for the base cluster). That is a property of the storage
    --   platform rather than a defect of the programs: a non-recoverable
    --   VSAM cluster has nowhere to keep ciphertext keys or an audit
    --   trail. The relational target stores the enciphered value
    --   instead, so it is unreadable at rest in a page image or a
    --   backup. BYTEA rather than CHAR(3) follows directly: ciphertext
    --   is opaque bytes of a length the cipher chooses, and a
    --   three-character column could not hold it.
    cvv_encrypted     BYTEA,

    -- Trade-offs: VARCHAR here, against CHAR everywhere else in this
    --   table, is the one place a PIC X width is read as a maximum
    --   instead of as data. CARD-EMBOSSED-NAME is PIC X(50)
    --   (app/cpy/CVACT02Y.cpy:8) and all fifty records of
    --   app/data/ASCII/carddata.txt are left-justified and space-padded
    --   to fill it, so those trailing blanks are the record format
    --   asserting itself and not part of anybody's name. CHAR(50) would
    --   make them part of the value, and every equality test and every
    --   rendered field would carry them. The cost accepted is that this
    --   column no longer states the stored width on its face, which is
    --   why the padding rule is written here; the declared width still
    --   binds, so an over-long name is refused rather than silently
    --   truncated.
    embossed_name     VARCHAR(50)  NOT NULL,

    -- Assumptions: a true DATE, from a PIC X(10) source field
    --   (app/cpy/CVACT02Y.cpy:9), because those ten bytes are
    --   demonstrably ISO 'YYYY-MM-DD' and not an arbitrary string. Three
    --   independent declarations inside app/cbl/COCRDUPC.cbl agree on
    --   it: the REDEFINES at :115-121 decomposes the field 4-1-2-1-2,
    --   placing the separators at bytes 5 and 8; the further REDEFINES
    --   at :122-123 declares all ten bytes as PIC 9(10); and the
    --   reference modification at :1505-1507 independently reads the
    --   year at (1:4), the month at (6:2) and the day at (9:2). Every
    --   record of app/data/ASCII/carddata.txt matches that pattern.
    --   Keeping the text form would preserve the bytes while giving up
    --   date arithmetic and range predicates; because ISO ordering makes
    --   a lexical comparison and a date comparison agree, DATE keeps the
    --   ordering behaviour the baseline already relies on and makes
    --   those operations expressible as well.
    expiration_date   DATE         NOT NULL,

    -- Assumptions: a one-character code, not a boolean.
    --   CARD-ACTIVE-STATUS is PIC X(01) (app/cpy/CVACT02Y.cpy:10) and
    --   the baseline tests it against the closed two-value domain
    --   declared at app/cbl/COCRDUPC.cbl:89-91, where FLG-YES-NO-VALID
    --   admits 'Y' and 'N'. BOOLEAN would read more naturally in the
    --   entity and would change two things that matter: the loader would
    --   have to translate every byte of every extract, and a third
    --   character arriving from one would be coerced into true or false
    --   instead of being refused outright by the constraint declared
    --   further down.
    active_status     CHAR(1)      NOT NULL,

    -- Refactoring Rationale: the baseline already performs optimistic
    --   concurrency by hand, so this column expresses a discipline that
    --   exists rather than introducing one that does not. Because a CICS
    --   task ends at each screen turn, app/cbl/COCRDUPC.cbl cannot hold
    --   a record lock across the user's think time; it snapshots the
    --   record into CCUP-OLD-DETAILS (:291, the verification value
    --   declared at :294 and taken at :1354) before showing the screen,
    --   then re-compares on submit. Paragraph 9300-CHECK-CHANGE-IN-REC
    --   (:1498 through its exit at :1521, performed from :1453-1454)
    --   tests six fields in the single predicate at :1503-1508 -- the
    --   verification value, the embossed name, the expiry year, month
    --   and day, and the active status -- and abandons the write at
    --   :1511 if any of them moved, the whole sequence committing at the
    --   syncpoint at :469-471. One counter subsumes that entire
    --   comparison, including the verification value the screens never
    --   showed, and it keeps holding when a column is added, which a
    --   hand-maintained field list does not.
    version           INTEGER      NOT NULL DEFAULT 0,

    -- Trade-offs: the record's trailing 59 bytes stop here, and their
    --   omission is a decision rather than an oversight. FILLER
    --   PIC X(59) (app/cpy/CVACT02Y.cpy:11) pads CARD-RECORD out to the
    --   constant 150-byte length that RECORDSIZE(150 150) demands
    --   (app/jcl/CARDFILE.jcl:55) and that FD-CARDFILE-REC confirms as
    --   134 bytes of data behind a 16-byte key
    --   (app/cbl/CBACT02C.cbl:38-40). It names no field, no program
    --   reads it, and a relational row has no constant length for it to
    --   pad out, so it becomes no column. The cost accepted is that this
    --   table on its own cannot re-emit a byte-identical 150-byte
    --   record; re-emitting one is the record codec's job, and that pads
    --   from the layout declaration rather than from padding some row
    --   had stored. Written here, at the point those bytes would
    --   otherwise have appeared, because a reader reconciling seven
    --   columns against six named copybook fields plus padding needs to
    --   find the missing 59 bytes accounted for somewhere.

    -- Assumptions: both constraints below are named explicitly instead
    --   of taking a server-generated name, because both are quoted back
    --   to something that has to recognise them. A unique violation on
    --   the key and a check violation on the status are the two errors
    --   this table raises in ordinary operation, and the service answers
    --   each with its own response; matching on a generated name would
    --   tie that mapping to a string no file in the repository declares.
    CONSTRAINT pk_cards PRIMARY KEY (card_num),

    -- Assumptions: the domain is closed at exactly two values, as
    --   FLG-YES-NO-VALID declares at app/cbl/COCRDUPC.cbl:89-91. All
    --   fifty records of app/data/ASCII/carddata.txt carry 'Y', so the
    --   constraint admits the existing extract unchanged and the load
    --   needs no exception path for it. It is declared in the schema
    --   rather than left to the service because the migration ETL loads
    --   this table directly, and a rule that lives only in application
    --   code is never reached by a bulk load.
    CONSTRAINT ck_cards_active_status CHECK (active_status IN ('Y', 'N'))
);

-- Assumptions: non-unique, and that is the declared contract rather
--   than a cautious default. The baseline does not scan to find an
--   account's cards; it reaches them through a second access path, and
--   the definition of that path is decisive here.
--   app/jcl/CARDFILE.jcl:83 defines an alternate index over the same
--   base cluster (:84) whose key is eleven bytes at offset sixteen
--   (:85) -- which is exactly CARD-ACCT-ID, the eleven bytes occupying
--   positions 17 to 27 of the record -- and declares it NONUNIQUEKEY at
--   :86 and UPGRADE at :87. A path over that index (:100-102) is the
--   object CICS surfaces as the file CARDAIX
--   (app/csd/CARDDEMO.CSD:13-14), enabled for browse, read, update and
--   delete at :19; and app/cbl/COCRDUPC.cbl:251-254 carries the base
--   cluster and that path as two separate file-name literals, so a
--   program addresses them as two distinct files. The access path is
--   therefore part of the structure the programs are written against,
--   and this index is what carries it across.
CREATE INDEX idx_cards_account_id ON card.cards (account_id);
