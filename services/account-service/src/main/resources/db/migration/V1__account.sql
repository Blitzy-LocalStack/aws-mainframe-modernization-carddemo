-- =============================================================================
-- services/account-service/src/main/resources/db/migration/V1__account.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Establishes the entire persistent shape of the account bounded context:
--   three tables -- account.accounts, account.customers and
--   account.card_xref -- and one secondary index over the last of them,
--   idx_card_xref_account_id.
--
--   Each table is derived field for field from the copybook that declares its
--   record. 01 ACCOUNT-RECORD is declared at app/cpy/CVACT01Y.cpy:4 and its
--   twelve named fields plus trailing padding occupy the 300-byte record
--   announced at :2. 01 CUSTOMER-RECORD is declared at
--   app/cpy/CVCUS01Y.cpy:4, eighteen named fields plus padding in the
--   500-byte record announced at :2. 01 CARD-XREF-RECORD is declared at
--   app/cpy/CVACT03Y.cpy:4, three named fields plus padding in a 50-byte
--   record. Those three copybooks are the normative source for this schema:
--   each PICTURE clause decides the column type it becomes, and no width,
--   scale or value domain here was chosen independently of one.
--
--   This file is the authoritative column list for the whole account-service
--   module. Its entities, repositories, mappers and served OpenAPI contract
--   are authored after it and mirror the names, widths and nullability
--   settled here rather than re-deriving them from the copybooks, so that
--   one reading of each record exists instead of five.
--
--   The three record lengths this column set accounts for are corroborated
--   beyond each copybook's own declaration by the cluster definitions and by
--   the seed extracts: RECORDSIZE(300 300) with KEYS(11 0) at
--   app/jcl/ACCTFILE.jcl:40-41, RECORDSIZE(500 500) with KEYS(9 0) at
--   app/jcl/CUSTFILE.jcl:50-51, and RECORDSIZE(50 50) with KEYS(16 0) at
--   app/jcl/XREFFILE.jcl:43-44. Every record of app/data/ASCII/acctdata.txt
--   measures exactly 300 bytes and every record of
--   app/data/ASCII/custdata.txt exactly 500, across 50 records each.
--
-- WHY : Assumptions: the cross-reference extract is the one place where the
--       seed file and the cluster disagree, and the difference is padding
--       rather than content. Every record of app/data/ASCII/cardxref.txt
--       measures 36 bytes, which is 16 + 9 + 11 -- the three named fields
--       and nothing else -- while the cluster declares 50. The 14 bytes
--       between them are the FILLER at app/cpy/CVACT03Y.cpy:8, which the
--       extract does not carry. This is recorded because a reader checking
--       36 against a 50-byte record would otherwise suspect a truncated
--       extract, and because the loader must pad rather than reject: the
--       omission changes no column below, since that FILLER becomes none.
--
-- Parameters:
--   A migration takes no arguments, so its inputs are the Flyway state and
--   configuration it is applied under. All of them are declared in sibling
--   resources rather than here, and each is named with the key that pins it.
--
--   - Target schema: account. Pinned three times over, by spring.flyway.schemas
--     and spring.flyway.default-schema in application.yml, and by
--     spring.jpa.properties.hibernate.default_schema beside them. The second of
--     the Flyway pair also decides where the schema history table lives,
--     which is what keeps this module's migration state out of every other
--     module's.
--   - Discovery location: classpath:db/migration, set by
--     spring.flyway.locations. The file name is part of that contract, not
--     decoration: a capital V, the version 1, two underscores, then the
--     description. Renaming it or altering the prefix removes the script from
--     discovery, and a location that resolves to nothing is reported as zero
--     migrations applied rather than as an error.
--   - Applied-version state: the schema history table in schema account.
--     Version 1 is the identifier that decides whether this script runs at
--     all; a history row already recording it means this file is skipped.
--   - A pre-existing schema, owner role and privilege graph, bootstrapped by
--     data-migration/sql/V0__schemas_and_roles.sql, which is the exclusive
--     authority for schemas, roles and grants in this system. The schema and
--     its owning role are established together at V0:701. Because
--     spring.flyway.create-schemas is false in application.yml, this script
--     may migrate the account schema and may not create it.
--   - The executing role: Flyway authenticates as carddemo_account_migrator
--     and issues SET ROLE carddemo_account_owner first, so the owner owns
--     every object below. That ownership is what carries V0's ALTER DEFAULT
--     PRIVILEGES clauses at V0:879-882, V0:1115-1116 and V0:1235-1236 onto
--     these tables, without this file issuing a single GRANT of its own.
--
-- Return values:
--   The schema objects this script leaves behind, and nothing besides.
--
--   - Table account.accounts, with thirteen columns: account_id,
--     active_status, curr_bal, credit_limit, cash_credit_limit, open_date,
--     expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit,
--     addr_zip, group_id and version. Twelve carry a named copybook field;
--     version carries none and is explained where it is declared.
--   - Table account.customers, with nineteen columns: customer_id,
--     first_name, middle_name, last_name, addr_line_1, addr_line_2,
--     addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1,
--     phone_num_2, ssn_encrypted, govt_issued_id_encrypted, dob,
--     eft_account_id, pri_card_holder_ind, fico_credit_score and version.
--   - Table account.card_xref, with three columns: card_num, customer_id and
--     account_id. It carries no version column, for the reason recorded on
--     the table.
--   - Primary keys pk_accounts, pk_customers and pk_card_xref.
--   - Check constraints ck_accounts_active_status and
--     ck_customers_pri_card_holder_ind.
--   - Non-unique index idx_card_xref_account_id on card_xref.account_id.
--
--   Deliberately absent, each for a reason recorded at the point it would
--   otherwise have appeared: no schema, no role and no privilege change; no
--   foreign key between these three tables or out of them; no check
--   constraint on fico_credit_score; no view, trigger, sequence or seed data.
--
--   Because spring.jpa.hibernate.ddl-auto is none in application.yml,
--   Hibernate emits no DDL and validates nothing. Every object listed above
--   exists at runtime only because this script created it, and anything
--   omitted here is simply absent from the running system.
--
-- Exceptions or errors:
--   The failure modes a reader will actually meet, so that each one reads as
--   intended behaviour rather than as a defect in this file.
--
--   1. Absent schema, or insufficient privilege. create-schemas is false and
--      the executing role needs CREATE on schema account, so applying this
--      script to a database on which the bootstrap never ran fails naming the
--      schema it wanted, instead of quietly producing a second account schema
--      owned by whichever role happened to connect and carrying none of the
--      privileges V0 grants.
--   2. Missing PostgreSQL support module. The migration engine and its
--      PostgreSQL dialect are separate Maven coordinates, and this module's
--      pom.xml declares org.flywaydb:flyway-database-postgresql precisely
--      because the engine alone cannot match a PostgreSQL connection. Neither
--      coordinate carries a version; the aggregator manages both, and nothing
--      here should ever add one.
--   3. Missing migration autoconfiguration. With the Flyway starter off the
--      classpath, every spring.flyway.* key binds to nothing and this script
--      never runs at all, while the service starts and reports healthy
--      against a database that has no accounts table.
--   4. Checksum mismatch, or pre-existing objects with no history. Flyway
--      refuses to re-run a script whose checksum changed after it was
--      applied, and with baseline-on-migrate false an account schema that
--      already holds objects but carries no history table aborts startup
--      rather than being recorded as already migrated. Treat this file as
--      immutable from its first successful application onward: a correction
--      arrives as a new versioned migration, never as an edit here, because
--      the alternative leaves a table of the wrong shape in place and
--      undetected.
--   5. No destructive path exists. clean is disabled by the profile overlay,
--      and this script contains no clean, no drop and no truncate of its own,
--      so there is nothing here for such a request to act on.
--
-- Provenance:
--   Every app/** path cited in this file is REFERENCE-ONLY and is never
--   modified by this migration or by anything else in the migrated trees.
--   The COBOL baseline is the specification this schema encodes, and it keeps
--   running: the migration adds a path, it does not remove one.
--
--   Primary sources: app/cpy/CVACT01Y.cpy, app/cpy/CVCUS01Y.cpy and
--   app/cpy/CVACT03Y.cpy for the three record layouts; app/jcl/ACCTFILE.jcl,
--   app/jcl/CUSTFILE.jcl and app/jcl/XREFFILE.jcl for the cluster, alternate
--   index and path definitions; app/cbl/COACTUPC.cbl for the value domains
--   and the before-image concurrency discipline; app/cbl/CBACT01C.cbl,
--   app/cbl/CBCUS01C.cbl and app/cbl/CBACT03C.cbl for the record lengths and
--   sequential access paths; app/csd/CARDDEMO.CSD for the four CICS file
--   definitions ACCTDAT (:1), CCXREF (:37), CUSTDAT (:50) and CXACAIX (:63);
--   and app/data/ASCII/acctdata.txt, custdata.txt and cardxref.txt as the
--   seed extracts every empirical statement below was measured against.
--   Field-level lineage, including the single target-side rename noted at
--   expiration_date, is recorded in
--   docs/architecture/data-model-and-schema-mapping.md.
--
--   Documentation convention: docs/CODE_DOCUMENTATION_STANDARD.md, whose SQL
--   section requires this header block plus a rationale on each non-obvious
--   constraint and index, and records that nothing in the build inspects a
--   migration's comments. The user-specified Explainability rule is answered
--   here by the four sections above and below by an adjacent rationale on
--   every non-obvious choice, each tagged with one of the four categories it
--   names.
-- =============================================================================

-- WHY : Assumptions: every object below is written schema-qualified as
--       account.<object> rather than left to the connection's search path.
--       The pooled datasource does pin that path to this one schema (the
--       connection-init-sql search path in application.yml), so an
--       unqualified name would resolve correctly today; the qualification is
--       what keeps the script deterministic when something other than that
--       pool applies it, which the repository integration test and any
--       operator-run session both are. The failure it converts is the silent
--       kind: an unqualified CREATE TABLE run under a search path someone
--       later changed succeeds against the wrong schema, whereas a qualified
--       one fails and names the schema it could not find.
CREATE TABLE account.accounts (

    -- WHY : Assumptions: ACCT-ID is PIC 9(11) (app/cpy/CVACT01Y.cpy:5) and is
    --       used throughout as an identifier rather than as a quantity, so it
    --       maps to an integer type; eleven digits overflow INTEGER and sit
    --       well inside BIGINT. The cluster keys on all eleven bytes from
    --       offset zero (app/jcl/ACCTFILE.jcl:40), which is what makes this
    --       the primary key rather than merely a unique column.
    --
    --       Alternatives Considered: CHAR(11), by symmetry with the card
    --       number in the sibling card schema and in card_xref below.
    --       Rejected because the copybook itself distinguishes the two cases:
    --       this field is PIC 9(11), a NUMERIC picture, whereas the card
    --       number is PIC X(16), an alphanumeric one. That distinction is
    --       what decides the question, and the seed extract shows why it has
    --       to be read carefully rather than inferred from appearance. Every
    --       one of the fifty values in app/data/ASCII/acctdata.txt begins
    --       with a zero digit -- they run 00000000001 upward, 41 of them
    --       carrying nine leading zeros and 9 carrying ten -- so an extract
    --       inspected without its picture looks exactly like a set of
    --       zero-significant strings. Under a numeric picture those zeros are
    --       the fixed-width DISPLAY form of the number, not part of its
    --       value: 00000000001 is the number 1, and storing it as 1 loses
    --       nothing that the picture can express. The card number's
    --       alphanumeric picture makes no such promise, which is why the
    --       identical-looking leading zeros there are significant and its
    --       column is CHAR.
    --
    --       Assumptions: an integer column additionally makes the join to
    --       card_xref.account_id and to the ledger's account references a
    --       comparison between like types rather than between a number and a
    --       padded string.
    account_id           BIGINT          NOT NULL,

    -- WHY : Assumptions: ACCT-ACTIVE-STATUS is PIC X(01)
    --       (app/cpy/CVACT01Y.cpy:6), a single character, and CHAR(1) is the
    --       exact width rather than a ceiling. NOT NULL because the baseline
    --       record has no absent state for a fixed-width display field: an
    --       unset value would be a blank, which the check constraint at the
    --       foot of this table refuses outright.
    active_status        CHAR(1)         NOT NULL,

    -- WHY : Assumptions: NUMERIC(12,2) and never a binary floating-point
    --       type, at this or any other hop. ACCT-CURR-BAL is
    --       PIC S9(10)V99 (app/cpy/CVACT01Y.cpy:7) -- ten integer digits, two
    --       decimal places and a sign -- so the column needs twelve digits of
    --       precision at scale two, exactly. DOUBLE PRECISION would represent
    --       most of those values approximately and a few of them wrongly,
    --       and the error is the silent kind: a balance one cent adrift still
    --       looks entirely plausible, and it decides the over-limit
    --       comparison at app/cbl/CBTRN02C.cbl that admits a transaction
    --       exactly at the credit limit and rejects one cent beyond it.
    --
    --       Alternatives Considered: BIGINT holding integer cents, which is
    --       also exact. Rejected because every reader of this column -- the
    --       interest calculation, the statement and report edit masks, and
    --       the JSON string representation -- works in decimal, so an
    --       integer-cents column would insert a scaling conversion at each
    --       of them and put the placement of the decimal point into
    --       application code rather than into the schema.
    curr_bal             NUMERIC(12, 2)  NOT NULL,

    -- WHY : Assumptions: PIC S9(10)V99 (app/cpy/CVACT01Y.cpy:8), so the type
    --       argument recorded on curr_bal applies unchanged. NOT NULL because
    --       this column is read on the posting path as a comparison operand:
    --       app/cbl/CBTRN02C.cbl tests the transaction against the credit
    --       limit, and a NULL would make that comparison neither true nor
    --       false and silently skip the over-limit reject the baseline
    --       raises.
    credit_limit         NUMERIC(12, 2)  NOT NULL,

    -- WHY : Assumptions: PIC S9(10)V99 (app/cpy/CVACT01Y.cpy:9). Present as
    --       its own column rather than derived from credit_limit because the
    --       baseline holds it as an independent field and the two carry
    --       different values in the seed extract.
    cash_credit_limit    NUMERIC(12, 2)  NOT NULL,

    -- WHY : Assumptions: DATE rather than CHAR(10), and the substitution is
    --       safe for one specific reason. ACCT-OPEN-DATE is PIC X(10)
    --       (app/cpy/CVACT01Y.cpy:10) holding 'YYYY-MM-DD', which is already
    --       ISO-ordered, so a lexical comparison over the characters and a
    --       chronological comparison over the dates return the same answer.
    --       That equivalence is what lets the column become a real date type
    --       without changing the meaning of any comparison the baseline
    --       performs on it.
    --
    --       Trade-offs: what a DATE column will not store is a value the
    --       baseline can hold -- ten blanks, or a syntactically malformed
    --       date -- because a character field has no validity rule and this
    --       column does. That is accepted deliberately: rejecting an invalid
    --       date at the storage boundary is the behaviour
    --       app/cbl/CSUTLDTC.cbl exists to provide, and the loader validates
    --       each date before insert so a malformed seed value is reported
    --       against its own record rather than stored and misread later.
    open_date            DATE            NOT NULL,

    -- WHY : Refactoring Rationale: the copybook field is spelled
    --       ACCT-EXPIRAION-DATE (app/cpy/CVACT01Y.cpy:11) -- the baseline
    --       misspells "expiration" -- and this column corrects it to
    --       expiration_date. The rename is deliberate and is one of exactly
    --       three such corrections in the migration, all three recorded in
    --       docs/architecture/data-model-and-schema-mapping.md so the lineage
    --       is never ambiguous. Carrying the misspelling forward was the
    --       alternative and was rejected because a schema column is read and
    --       typed by people for the lifetime of the system, whereas the
    --       lineage note is read once; propagating a typo into every query,
    --       entity field and API member to preserve a byte-level resemblance
    --       to a name no longer used would cost more than it records.
    --
    --       Assumptions: the type argument recorded on open_date applies
    --       unchanged -- PIC X(10) holding an ISO-ordered date.
    expiration_date      DATE            NOT NULL,

    -- WHY : Assumptions: PIC X(10) holding an ISO-ordered date
    --       (app/cpy/CVACT01Y.cpy:12), so the DATE argument on open_date
    --       applies. NOT NULL, on two pieces of evidence rather than by
    --       symmetry with the dates around it. The baseline edits this field
    --       through the SAME routine as the other two, performing
    --       EDIT-DATE-CCYYMMDD on it at app/cbl/COACTUPC.cbl:1504-1507 exactly
    --       as it does for the expiry date at :1491-1494, with no optional
    --       path and no blank-permitted branch; and all fifty records of
    --       app/data/ASCII/acctdata.txt carry a real date here, the first
    --       three of them repeating their own expiration date. There is
    --       therefore no absent state to represent, and admitting NULL would
    --       invent one the baseline has no encoding for.
    reissue_date         DATE            NOT NULL,

    -- WHY : Assumptions: PIC S9(10)V99 (app/cpy/CVACT01Y.cpy:13). This and
    --       the debit column below are cycle accumulators the posting job
    --       rewrites, so they carry the same exactness requirement as the
    --       balance and for the same reason.
    curr_cyc_credit      NUMERIC(12, 2)  NOT NULL,

    -- WHY : Assumptions: PIC S9(10)V99 (app/cpy/CVACT01Y.cpy:14).
    curr_cyc_debit       NUMERIC(12, 2)  NOT NULL,

    -- WHY : Assumptions: CHAR(10) rather than VARCHAR(10), because the width
    --       of a postal code is meaningful rather than incidental.
    --       ACCT-ADDR-ZIP is PIC X(10) (app/cpy/CVACT01Y.cpy:15) and
    --       app/cbl/COACTUPC.cbl validates an account's ZIP against the state
    --       code using the seeded prefix table, a comparison over leading
    --       characters that a trimmed value would still satisfy but a
    --       differently padded one would not.
    addr_zip             CHAR(10)        NOT NULL,

    -- WHY : Assumptions: CHAR(10) because this is the lookup key into
    --       reference.disclosure_groups, and a trimmed value would not match
    --       the seeded key. ACCT-GROUP-ID is PIC X(10)
    --       (app/cpy/CVACT01Y.cpy:16).
    --
    --       Assumptions: NO foreign key to reference.disclosure_groups, and
    --       the seed extract is what settles it rather than a preference for
    --       loose coupling. All fifty records of app/data/ASCII/acctdata.txt
    --       carry ten BLANKS in this field, so a foreign key would refuse
    --       every row of the extract the loader must insert. That is not an
    --       accident of the sample: it is the condition the interest
    --       calculation is written for. app/cbl/CBACT04C.cbl reads the
    --       disclosure group by this key, and when the read returns VSAM
    --       status 23 -- not found -- it falls back to the group named
    --       'DEFAULT'. A blank group identifier is therefore the ordinary
    --       case and the fallback is the path that actually runs, which a
    --       referential constraint would make unreachable by refusing the row
    --       first. The constraint would also cross a bounded-context
    --       boundary, from a table account-service owns to one
    --       reference-service owns.
    group_id             CHAR(10)        NOT NULL,

    -- WHY : Refactoring Rationale: the baseline already performs optimistic
    --       concurrency by hand, so this column expresses a discipline that
    --       exists rather than introducing one that does not. Because a CICS
    --       task ends at each screen turn, app/cbl/COACTUPC.cbl cannot hold a
    --       record lock across the user's think time; it snapshots the entire
    --       pre-edit record into ACUP-OLD-DETAILS (:669 onward, each numeric
    --       held as a display field with a numeric REDEFINES alongside it,
    --       for example the balance at :675-677), carries a change flag
    --       (:168) with the condition DATA-WAS-CHANGED-BEFORE-UPDATE (:521),
    --       and on a failed rewrite issues EXEC CICS SYNCPOINT ROLLBACK
    --       (:4095-4104) rather than completing the write. One counter
    --       subsumes that entire field-by-field comparison, and it keeps
    --       holding when a column is added, which a hand-maintained field
    --       list does not.
    --
    --       Assumptions: BIGINT NOT NULL, which is what the authoritative
    --       mapping record declares and what batch-service's consuming entity
    --       maps to a primitive long. The sibling card schema uses INTEGER
    --       for its own version column; the difference is not an
    --       inconsistency to be reconciled here, because each schema's
    --       mapping record fixes its own type and the consuming entity in
    --       each case is written to match it.
    --
    --       DEFAULT 0 so that the bulk load can insert a row from the seed
    --       extract without supplying a column that extract has no field for.
    version              BIGINT          NOT NULL DEFAULT 0,

    -- WHY : Trade-offs: the record's trailing 178 bytes stop here, and their
    --       omission is a decision rather than an oversight. FILLER
    --       PIC X(178) (app/cpy/CVACT01Y.cpy:17) pads ACCOUNT-RECORD out to
    --       the constant 300-byte length that RECORDSIZE(300 300) demands
    --       (app/jcl/ACCTFILE.jcl:41). It names no field, no program reads
    --       it, and a relational row has no constant length for it to pad
    --       out, so it becomes no column. The cost accepted is that this
    --       table on its own cannot re-emit a byte-identical 300-byte record;
    --       re-emitting one is the record codec's job, and that pads from the
    --       layout declaration rather than from padding some row had stored.
    --       Written here, at the point those bytes would otherwise have
    --       appeared, because a reader reconciling thirteen columns against
    --       twelve named copybook fields plus padding needs to find the
    --       missing 178 bytes accounted for somewhere.

    -- WHY : Assumptions: both constraints below are named explicitly instead
    --       of taking a server-generated name, because both are quoted back
    --       to something that has to recognise them. A unique violation on
    --       the key and a check violation on the status are the two errors
    --       this table raises in ordinary operation, and the service answers
    --       each with its own response; matching on a generated name would
    --       tie that mapping to a string no file in the repository declares.
    CONSTRAINT pk_accounts PRIMARY KEY (account_id),

    -- WHY : Assumptions: the domain is closed at exactly two values, as
    --       FLG-ACCT-STATUS-ISVALID declares with VALUES 'Y', 'N' at
    --       app/cbl/COACTUPC.cbl:193. All fifty records of
    --       app/data/ASCII/acctdata.txt carry 'Y', so the constraint admits
    --       the existing extract unchanged and the load needs no exception
    --       path for it. It is declared in the schema rather than left to the
    --       service because the migration ETL loads this table directly, and
    --       a rule that lives only in application code is never reached by a
    --       bulk load.
    CONSTRAINT ck_accounts_active_status CHECK (active_status IN ('Y', 'N'))
);

-- WHY : Assumptions: no index is declared on this table beyond its primary
--       key, and the access paths are what settle that. The cluster defines
--       one key and no alternate index (app/jcl/ACCTFILE.jcl:36-48, which
--       has no DEFINE ALTERNATEINDEX step at all, unlike its cross-reference
--       sibling), and every program reaching an account does so by
--       identifier: app/cbl/CBACT01C.cbl reads the master sequentially and
--       app/cbl/COACTVWC.cbl and app/cbl/COACTUPC.cbl read it keyed. An
--       index on active_status or on group_id would serve no path the
--       baseline has, and each would be maintained on every posting rewrite.

CREATE TABLE account.customers (

    -- WHY : Assumptions: CUST-ID is PIC 9(09) (app/cpy/CVCUS01Y.cpy:5), an
    --       identifier rather than a quantity, so an integer type; nine
    --       digits exceed what INTEGER holds and sit inside BIGINT. The
    --       cluster keys on all nine bytes from offset zero
    --       (app/jcl/CUSTFILE.jcl:50).
    customer_id                 BIGINT          NOT NULL,

    -- WHY : Assumptions: VARCHAR rather than CHAR for all three name parts,
    --       which is the opposite choice from the codes above and is made on
    --       the same principle. CUST-FIRST-NAME is PIC X(25)
    --       (app/cpy/CVCUS01Y.cpy:6), so 25 is the maximum a value may reach;
    --       but a name shorter than 25 characters is a shorter NAME, not a
    --       defective one, and its trailing blanks in the fixed-width record
    --       are padding rather than data. Storing them would make every
    --       comparison and every rendered response carry pad the baseline
    --       only ever had because its records were fixed-length.
    first_name                  VARCHAR(25)     NOT NULL,

    -- WHY : Assumptions: PIC X(25) (app/cpy/CVCUS01Y.cpy:7). NULLABLE, and it
    --       is the only one of the three name parts that is. The baseline
    --       states the distinction itself rather than leaving it to be
    --       inferred: app/cbl/COACTUPC.cbl edits this field with
    --       1235-EDIT-ALPHA-OPT (:1571) -- the OPTIONAL alphabetic routine --
    --       while editing the first and last names with 1225-EDIT-ALPHA-REQD
    --       (:1563 and :1579), the REQUIRED one. All fifty seed records
    --       happen to carry a middle name, so the extract alone could not have
    --       settled this and the edit routine is the evidence that does.
    middle_name                 VARCHAR(25),

    -- WHY : Assumptions: PIC X(25) (app/cpy/CVCUS01Y.cpy:8).
    last_name                   VARCHAR(25)     NOT NULL,

    -- WHY : Assumptions: PIC X(50) (app/cpy/CVCUS01Y.cpy:9). The VARCHAR
    --       argument on first_name applies to all three address lines. NOT
    --       NULL because app/cbl/COACTUPC.cbl edits this field with
    --       1215-EDIT-MANDATORY (:1587).
    addr_line_1                 VARCHAR(50)     NOT NULL,

    -- WHY : Assumptions: PIC X(50) (app/cpy/CVCUS01Y.cpy:10). NULLABLE, and
    --       this field is the single one on the customer record that the
    --       baseline does not validate at all: its edit is COMMENTED OUT at
    --       app/cbl/COACTUPC.cbl:1614, where the line that would have moved
    --       'Address Line 2' into the diagnostic name is disabled and the
    --       following statement moves 'City' instead. A field the baseline
    --       never checks cannot be given a NOT NULL it was never held to.
    addr_line_2                 VARCHAR(50),

    -- WHY : Assumptions: PIC X(50) (app/cpy/CVCUS01Y.cpy:11). NOT NULL, which
    --       is the opposite of its neighbour above and is decided by what the
    --       baseline actually does with this field rather than by its
    --       positional name. app/cbl/COACTUPC.cbl labels it 'City' (:1615) and
    --       edits it with 1225-EDIT-ALPHA-REQD (:1618), the REQUIRED
    --       alphabetic routine -- so the third address line is, in the
    --       baseline's own treatment, a mandatory city rather than an optional
    --       overflow line. The column keeps the copybook's name because the
    --       copybook is normative for names, and the divergence between that
    --       name and the program's label is exactly why this rationale is
    --       recorded here.
    addr_line_3                 VARCHAR(50)     NOT NULL,

    -- WHY : Assumptions: CHAR(2) because a state code is a fixed-width code
    --       whose width is meaningful. CUST-ADDR-STATE-CD is PIC X(02)
    --       (app/cpy/CVCUS01Y.cpy:12).
    --
    --       Assumptions: NO foreign key to reference.us_states, for the same
    --       cross-context reason recorded on accounts.group_id and for one
    --       specific to the data: the fifty seed customers carry 36 distinct
    --       state codes including territory codes such as AS, FM, GU, MH, PW
    --       and VI, and a constraint narrower than the seeded lookup table
    --       would refuse rows the baseline holds. The validation the baseline
    --       performs is at the online-update boundary --
    --       app/cbl/COACTUPC.cbl edits the state code and the state-ZIP
    --       combination there -- and account-service's AddressValidationService
    --       carries it across at that same boundary.
    addr_state_cd               CHAR(2)         NOT NULL,

    -- WHY : Assumptions: PIC X(03) (app/cpy/CVCUS01Y.cpy:13); all fifty seed
    --       records carry 'USA'. CHAR for the fixed-width-code reason
    --       recorded on addr_state_cd.
    addr_country_cd             CHAR(3)         NOT NULL,

    -- WHY : Assumptions: PIC X(10) (app/cpy/CVCUS01Y.cpy:14), CHAR for the
    --       reason recorded on accounts.addr_zip: the state-ZIP validation
    --       compares leading characters.
    addr_zip                    CHAR(10)        NOT NULL,

    -- WHY : Assumptions: VARCHAR(15) rather than a numeric type, and this is
    --       the case where the picture and the content disagree.
    --       CUST-PHONE-NUM-1 is PIC X(15) (app/cpy/CVCUS01Y.cpy:15) and the
    --       seed values carry punctuation in the '(999)999-9999' shape, so
    --       the field is characters and not digits. app/cbl/COACTUPC.cbl
    --       validates the area code against the seeded area-code table, which
    --       is a comparison over a substring of that punctuated form. The seed
    --       values confirm the shape: the first record carries
    --       '(908)119-8310'.
    --
    --       Assumptions: NULLABLE, because 1260-EDIT-US-PHONE-NUM states its
    --       own optionality -- it comments 'Not mandatory to enter a phone
    --       number', sets the valid flag and exits when the field is spaces or
    --       low-values. That applies to BOTH phone columns, so neither is NOT
    --       NULL even though all fifty seed records populate both.
    phone_num_1                 VARCHAR(15),

    -- WHY : Assumptions: PIC X(15) (app/cpy/CVCUS01Y.cpy:16). NULLABLE for
    --       the reason recorded on phone_num_1, which cites the shared edit
    --       routine both columns pass through.
    phone_num_2                 VARCHAR(15),

    -- WHY : Trade-offs: this column overrides the type its picture implies,
    --       and the cost of doing so is specific and accepted. CUST-SSN is
    --       PIC 9(09) (app/cpy/CVCUS01Y.cpy:17), nine digits, which the
    --       type-derivation rules would make a BIGINT. It becomes an
    --       enciphered BYTEA instead because it is one of the two most
    --       sensitive attributes in this model. What that costs is
    --       queryability: a ciphertext column cannot be range-queried,
    --       sorted, or matched by prefix or equality on the plaintext, so no
    --       endpoint can search customers by national identifier and none is
    --       offered. What it buys is that a query result, a log of one, and a
    --       backup of this table all disclose ciphertext rather than the
    --       identifier. Responses return a masked form, never the decrypted
    --       value.
    --
    --       Assumptions: BYTEA rather than a sized type follows directly from
    --       that choice -- ciphertext is opaque bytes of a length the cipher
    --       chooses, and a nine-digit column could not hold it. NOT NULL
    --       because every one of the fifty seed records carries a value and
    --       the baseline offers no absence encoding for a numeric picture.
    ssn_encrypted               BYTEA           NOT NULL,

    -- WHY : Trade-offs: PIC X(20) (app/cpy/CVCUS01Y.cpy:18), which rule 3
    --       would make VARCHAR(20). Enciphered for the reason recorded on
    --       ssn_encrypted, which applies to this column identically.
    --
    --       Assumptions: NULLABLE, on two observations about how the baseline
    --       handles this field specifically. It passes through no EDIT routine
    --       at all -- app/cbl/COACTUPC.cbl moves, compares and displays it
    --       (:1405, :1756-1758, :2945) but submits it to none of the mandatory
    --       or optional editors every neighbouring field goes through -- and
    --       at :1403 the program moves LOW-VALUES into it when the screen
    --       field is empty, which is the baseline's own encoding of an absent
    --       value. A NULL is the relational form of that encoding.
    govt_issued_id_encrypted    BYTEA,

    -- WHY : Assumptions: DATE for the reason recorded on accounts.open_date
    --       -- CUST-DOB-YYYY-MM-DD is PIC X(10)
    --       (app/cpy/CVCUS01Y.cpy:19) holding an ISO-ordered date, so lexical
    --       and chronological order coincide. The field name states the
    --       format, which is why the column does not repeat it.
    dob                         DATE            NOT NULL,

    -- WHY : Assumptions: CHAR(10) (app/cpy/CVCUS01Y.cpy:20). This is an
    --       external account reference for electronic funds transfer, a
    --       fixed-width code rather than a description. NOT NULL because
    --       app/cbl/COACTUPC.cbl edits it with 1245-EDIT-NUM-REQD (:1652), the
    --       REQUIRED numeric routine, and all fifty seed records populate it.
    eft_account_id              CHAR(10)        NOT NULL,

    -- WHY : Assumptions: PIC X(01) (app/cpy/CVCUS01Y.cpy:21), a single
    --       character with the closed domain the constraint at the foot of
    --       this table declares.
    pri_card_holder_ind         CHAR(1)         NOT NULL,

    -- WHY : Assumptions: SMALLINT, because CUST-FICO-CREDIT-SCORE is
    --       PIC 9(03) (app/cpy/CVCUS01Y.cpy:22) -- at most three digits, a
    --       bounded quantity that sits inside SMALLINT with room to spare and
    --       for which BIGINT would be four times the storage for no reach.
    --
    --       Trade-offs: NO check constraint bounding this column to 300
    --       through 850, even though the baseline states exactly that range.
    --       app/cbl/COACTUPC.cbl declares
    --       88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850 (:848-849) and
    --       rejects anything outside it with the message
    --       'should be between 300 and 850' (:2523). The reason the
    --       constraint is nonetheless absent is measurable: 21 of the 50
    --       records in app/data/ASCII/custdata.txt carry a score BELOW 300,
    --       the lowest being 001, so a schema-level check would refuse 42 per
    --       cent of the seed extract the loader must insert. The baseline has
    --       the same split because its rule lives at the online-update
    --       boundary and its file has no validity rule at all -- VSAM
    --       enforces no domain, so a record loaded by IDCAMS REPRO was never
    --       checked. Placing the constraint here would therefore not preserve
    --       baseline behaviour but change it, by rejecting data the baseline
    --       stores. The rule is carried across where the baseline carries it,
    --       at the update boundary, and this column accepts what the extract
    --       holds.
    fico_credit_score           SMALLINT        NOT NULL,

    -- WHY : Refactoring Rationale: a version column for the reason recorded
    --       in full on accounts.version -- app/cbl/COACTUPC.cbl performs
    --       optimistic concurrency by hand across the pseudo-conversational
    --       gap, and it snapshots the customer record alongside the account
    --       because one screen updates both. This table carries the counter
    --       for the same reason and of the same type.
    version                     BIGINT          NOT NULL DEFAULT 0,

    -- WHY : Trade-offs: the record's trailing 168 bytes stop here. FILLER
    --       PIC X(168) (app/cpy/CVCUS01Y.cpy:23) pads CUSTOMER-RECORD out to
    --       the 500-byte length RECORDSIZE(500 500) demands
    --       (app/jcl/CUSTFILE.jcl:51). The argument recorded on
    --       accounts.version's neighbouring FILLER note applies unchanged:
    --       it names no field, no program reads it, and re-emitting a
    --       fixed-length record is the codec's job rather than this table's.

    CONSTRAINT pk_customers PRIMARY KEY (customer_id),

    -- WHY : Assumptions: the domain is closed at exactly two values, as
    --       FLG-PRI-CARDHOLDER-ISVALID declares with VALUES 'Y', 'N' at
    --       app/cbl/COACTUPC.cbl:350. All fifty seed records carry 'Y', so
    --       the constraint admits the extract unchanged. Declared in the
    --       schema rather than in the service for the reason recorded on
    --       ck_accounts_active_status: the ETL loads this table directly and
    --       never executes application code.
    CONSTRAINT ck_customers_pri_card_holder_ind
        CHECK (pri_card_holder_ind IN ('Y', 'N'))
);

CREATE TABLE account.card_xref (

    -- WHY : Assumptions: character, of a settled width, and never a numeric
    --       type at any hop -- the identical argument the card schema records
    --       for its own key, and it applies here because this is the same
    --       card number. XREF-CARD-NUM is PIC X(16)
    --       (app/cpy/CVACT03Y.cpy:5) and the cluster keys on all sixteen
    --       bytes from offset zero (app/jcl/XREFFILE.jcl:43). Two concrete
    --       consequences rule out an integer column: card numbers in the seed
    --       extracts begin with a zero digit in several cases, which any
    --       numeric type discards on the way in, and sixteen significant
    --       digits exceed what an IEEE-754 double represents exactly, so a
    --       client that parsed the value as a JSON number would hand back a
    --       different card. CHAR rather than VARCHAR because a value shorter
    --       than sixteen is a defect to be refused, not a shorter name to be
    --       stored.
    card_num        CHAR(16)    NOT NULL,

    -- WHY : Assumptions: XREF-CUST-ID is PIC 9(09)
    --       (app/cpy/CVACT03Y.cpy:6), the same identifier as
    --       customers.customer_id and therefore the same BIGINT. NOT NULL
    --       because a cross-reference row exists precisely to resolve a card
    --       to a customer and an account; a row missing either is not a
    --       partial answer but an unusable one.
    customer_id     BIGINT      NOT NULL,

    -- WHY : Assumptions: XREF-ACCT-ID is PIC 9(11)
    --       (app/cpy/CVACT03Y.cpy:7), the same identifier as
    --       accounts.account_id and therefore the same BIGINT. NOT NULL
    --       additionally because the alternate index over this field is
    --       declared UPGRADE (app/jcl/XREFFILE.jcl:76), meaning the baseline
    --       maintains an entry for every base record as it changes: a
    --       cross-reference carrying no account would have no entry to
    --       maintain and could not be reached at all through the access path
    --       the index at the foot of this file carries across.
    account_id      BIGINT      NOT NULL,

    -- WHY : Trade-offs: the record's trailing 14 bytes stop here. FILLER
    --       PIC X(14) (app/cpy/CVACT03Y.cpy:8) pads CARD-XREF-RECORD out to
    --       the 50-byte length RECORDSIZE(50 50) demands
    --       (app/jcl/XREFFILE.jcl:44); the seed extract does not carry those
    --       bytes at all, as the header records. It becomes no column.

    -- WHY : Assumptions: no version column on this table, and the omission is
    --       deliberate rather than an oversight in a set of three tables
    --       where the other two carry one. A version column exists to detect
    --       a concurrent modification, and nothing modifies a
    --       cross-reference row: app/cbl/COACTUPC.cbl updates the account and
    --       the customer, and no online program in the baseline rewrites this
    --       record. Its three columns are wholly determined by the card that
    --       exists, so a change is an insert or a delete rather than an
    --       update, and adding a counter would suggest an update path that
    --       does not exist.

    CONSTRAINT pk_card_xref PRIMARY KEY (card_num)
);

-- WHY : Assumptions: non-unique, and that is the declared contract rather
--       than a cautious default. The baseline does not scan the
--       cross-reference to find an account's cards; it reaches them through a
--       second access path, and the definition of that path is decisive here.
--       app/jcl/XREFFILE.jcl:72 defines an alternate index over the same base
--       cluster (:73) whose key is eleven bytes at offset 25 (:74) -- which is
--       exactly XREF-ACCT-ID, the eleven bytes following the sixteen-byte
--       card number and the nine-byte customer identifier -- and declares it
--       NONUNIQUEKEY at :75 and UPGRADE at :76. A path over that index
--       (:90-92) is the object CICS surfaces as the file CXACAIX, described
--       at app/csd/CARDDEMO.CSD:63-64 as the alternate index to CCXREF via
--       the account key. The access path is therefore part of the structure
--       the programs are written against, and this index is what carries it
--       across.
--
--       NONUNIQUEKEY at :75 is why no UNIQUE appears below: the declared
--       cardinality permits many cards on one account. The seed extract holds
--       fifty cross-reference rows across fifty distinct accounts, which is
--       incidentally one to one and does not narrow that declaration; a
--       unique index would refuse a second card on an account the baseline
--       accepts, and nothing in that extract would reveal the difference
--       until such a card first appeared.
--
--       UPGRADE at :76 is also why the BLDINDEX step at :100 has no
--       counterpart anywhere in this file. That step populates the alternate
--       index as a separate act after the base cluster has been loaded;
--       PostgreSQL maintains an index inside the same transaction that
--       changes the table, so there is no separate build left to express.
CREATE INDEX idx_card_xref_account_id ON account.card_xref (account_id);

-- WHY : Assumptions: no foreign key from card_xref to accounts or to
--       customers, and none from accounts to customers, even though the
--       identifier columns plainly correspond. Two reasons, and the second is
--       the load order rather than a design preference.
--
--       First, the baseline asserts no such constraint: these are three
--       independent VSAM clusters, and nothing in
--       app/jcl/{ACCTFILE,CUSTFILE,XREFFILE}.jcl relates them. Where the
--       baseline DOES assert referential integrity it does so explicitly --
--       the transaction-category table's ON DELETE RESTRICT, carried across
--       in reference-service's own migration -- so the absence here is
--       informative rather than accidental.
--
--       Second, the ETL loads these three tables independently and in
--       parallel from three extracts, and a foreign key would impose an order
--       on that load and fail any parallel arrangement of it. The referential
--       question the constraint would answer is answered instead by
--       data-migration's verification stage, which counts unmatched
--       identifiers across the loaded tables and reports them per dataset --
--       a check that inspects the whole loaded set rather than one row at a
--       time, and that reports every violation instead of aborting on the
--       first.
