-- =============================================================================
-- services/reference-service/src/main/resources/db/migration/V2__seed_reference.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Seeds the six tables that V1__reference.sql created. It inserts rows and
--   nothing else: no schema, no role, no grant, no table, no index and no
--   search_path. This is the second and last Flyway versioned migration of
--   reference-service.
--
--   What is seeded, and how many rows, so that a future diff is auditable
--   without re-counting the baseline:
--     reference.transaction_types          7    app/data/ASCII/trantype.txt
--     reference.transaction_categories    18    app/data/ASCII/trancatg.txt
--     reference.disclosure_groups         51    app/data/ASCII/discgrp.txt
--     reference.us_phone_area_codes      490    app/cpy/CSLKPCDY.cpy L30
--     reference.us_states                 56    app/cpy/CSLKPCDY.cpy L1013
--     reference.us_state_zip_prefixes    240    app/cpy/CSLKPCDY.cpy L1073
--   Reference subtotal 76, lookup subtotal 786, total 862.
--
--   Corresponding baseline contract: this file is the target analogue of the
--   IDCAMS REPRO load step of app/jcl/TRANTYPE.jcl, app/jcl/TRANCATG.jcl and
--   app/jcl/DISCGRP.jcl -- STEP15 at L54 of each, whose L61 copies
--   AWS.M2.CARDDEMO.<name>.PS into the KSDS defined by the preceding step, into
--   the ddnames TTYPVSAM, TCATVSAM and DISCVSAM respectively. The DEFINE
--   CLUSTER of STEP10 loads no rows, so its analogue is V1__reference.sql and
--   not this file. The three lookup tables have no REPRO analogue at all,
--   because the baseline holds those codes as condition names compiled into
--   every program that tests them rather than as a dataset.
--
-- Invocation:
--   Flyway applies this script once, as a unit, resolved by its V2 version
--   prefix and ordered after V1 by that prefix. It takes no parameter and no
--   substitution variable, and it contains no psql meta-command, so it runs
--   identically under Flyway, under "psql -v ON_ERROR_STOP=1 -f", and through a
--   driver cursor.
--
-- Not seeded here:
--   No schema, role or grant. data-migration/sql/V0__schemas_and_roles.sql owns
--   those and states the boundary for every per-service migration at its
--   L11-L16. No DDL of any kind: every table, key, check and foreign key this
--   file writes into belongs to V1__reference.sql, so a column that turns out
--   to be missing is a change to V1 rather than an addition here.
--
-- Raises:
--   - SQLSTATE 23503 (foreign_key_violation) if the transaction_categories
--     insert is ever moved above the transaction_types insert. V1's
--     fk_transaction_categories_type is checked as each row is inserted, not at
--     commit, so the order of the two statements below is a correctness
--     requirement and not a matter of presentation.
--   - SQLSTATE 23514 (check_violation) if a phone area code were given a class
--     outside the two V1 admits. The classification below is derived from the
--     copybook rather than assigned, so this cannot arise from the data as it
--     stands; it would report a defect introduced into the derivation.
--   - No error is raised by re-running this script. Every statement carries ON
--     CONFLICT DO NOTHING, so a second application inserts nothing and returns
--     success rather than failing on the primary keys.
--   - Not raised here but caused here: omitting the 17 'DEFAULT   ' rows of
--     reference.disclosure_groups makes batch-service abend when it accrues
--     interest. The mechanism is set out at that insert; the reason it is
--     called out this far up is that the failure appears in a different
--     service, with no local defect of its own to find.
--
-- WHY (non-obvious design decisions):
--   - Assumptions: the rationale labels used below are the four Rule 1
--     categories, written un-parenthesised and colon-terminated:
--     "Alternatives Considered:", "Refactoring Rationale:", "Assumptions:" and
--     "Trade-offs:". The repository's dominant idiom is the singular
--     parenthesised form, "WHY (Assumptions):", measured across the COBOL-era
--     tree at 471 sites in 37 files; the two forms mean the same thing and this
--     file uses the label form throughout rather than mixing them, matching its
--     sibling V1__reference.sql and docs/CODE_DOCUMENTATION_STANDARD.md. The
--     equivalence is stated once, here, so that a reader never has to decide
--     whether a difference in spelling carries a difference in meaning.
--   - Alternatives Considered: every table is schema-qualified rather than left
--     to a search_path, for the reason V1 gives: a path-dependent script
--     produces different results for different callers, and one prefix per
--     statement removes that difference.
--   - Assumptions: the seed values are taken from app/data/ASCII, the VSAM
--     lineage, wherever a value exists in two baseline lineages. See the
--     transaction_types insert, where the two lineages disagree.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- reference.transaction_types  <-  app/data/ASCII/trantype.txt (7 x 60 bytes)
--
-- Field positions taken from app/cpy/CVTRA03Y.cpy: TRAN-TYPE PIC X(02) at
-- columns 1-2 and TRAN-TYPE-DESC PIC X(50) at 3-52. The FILLER PIC X(08) that
-- fills columns 53-60 holds '00000000' in all seven rows and is dropped, as V1
-- records. The descriptions below are the 50-byte field with its trailing pad
-- blanks removed, which is what makes VARCHAR the right target: the blanks pad
-- the record to a fixed length and are not part of the value.
-- -----------------------------------------------------------------------------
INSERT INTO reference.transaction_types (type_cd, description) VALUES
    ('01', 'Purchase'),
    ('02', 'Payment'),
    ('03', 'Credit'),
    ('04', 'Authorization'),
    ('05', 'Refund'),
    ('06', 'Reversal'),
    ('07', 'Adjustment')
-- WHY : Trade-offs: ON CONFLICT DO NOTHING here and on all six inserts below.
--       Flyway applies a versioned migration exactly once against a given
--       schema history, so on the path this file is designed for the clause
--       never fires. It is present for the path where the history is rebuilt
--       rather than the data: a re-baselined environment, or a database seeded
--       by data-migration's loaders and then brought under Flyway, reaches this
--       script with the rows already in place, and a bare INSERT would abort
--       the migration on a primary-key violation and leave the schema history
--       marked failed.
--       The compromise accepted is specific and worth stating plainly: DO
--       NOTHING silently keeps a pre-existing row whose non-key columns differ
--       from the values here, so this script guarantees the rows exist but does
--       not guarantee they match. That is acceptable only because these are
--       immutable reference codes -- a transaction type's description does not
--       get revised, and app/** cannot change. It would not be acceptable for a
--       table whose non-key columns are expected to drift, where DO UPDATE SET
--       would be the correct clause; DO UPDATE was rejected here because it
--       would let this migration overwrite an operator's deliberate correction
--       without reporting that it had done so.
ON CONFLICT (type_cd) DO NOTHING;

-- WHY : Assumptions: the descriptions above come from app/data/ASCII, and the
--       baseline offers two lineages that disagree. The VSAM seed
--       app/data/ASCII/trantype.txt carries them in title case and spells row
--       '06' as 'Reversal'. The Db2 extension's control card
--       app/app-transaction-type-db2/ctl/DB2LTTYP.ctl seeds the same seven
--       types at its L17-L23 in upper case, and its L22 spells the same row
--       'REVERAL'. The divergence therefore covers the casing of all seven
--       descriptions plus the spelling of one.
--       The VSAM lineage is the one this service is migrated from, so it is the
--       one seeded: reference-service replaces the VSAM-backed programs, its
--       tables are derived from app/cpy/CVTRA03Y.cpy rather than from the
--       extension's DDL, and the ETL under data-migration reads the same ASCII
--       datasets. Seeding the Db2 spelling instead would put a value in the
--       column that the dataset this schema is loaded from does not contain.
--       app/** is read as reference only and neither lineage is altered; the
--       divergence is recorded in
--       docs/architecture/cobol-to-service-traceability.md.


-- -----------------------------------------------------------------------------
-- reference.transaction_categories  <-  app/data/ASCII/trancatg.txt (18 x 60)
--
-- Field positions taken from app/cpy/CVTRA04Y.cpy: the TRAN-CAT-KEY group holds
-- TRAN-TYPE-CD PIC X(02) at columns 1-2 and TRAN-CAT-CD PIC 9(04) at 3-6,
-- followed by TRAN-CAT-TYPE-DESC PIC X(50) at 7-56 and a FILLER PIC X(04) at
-- 57-60 that holds '0000' in all eighteen rows and is dropped.
--
-- The dataset stores the key as one concatenated six-character value, so
-- "010001" is split into type_cd '01' and cat_cd '0001' to match V1's two
-- columns. That split is positional, not arithmetic.
-- -----------------------------------------------------------------------------

-- WHY : Assumptions: cat_cd is written as a four-character literal with its
--       leading zeros intact, never as an integer. V1 declares the column
--       CHAR(4) and sets out six independent baseline sources for that choice;
--       the consequence for this file is that '0001' and 1 are different
--       values, and only the first one joins to anything. The quotes here are
--       what preserve the zeros: an unquoted 0001 would be parsed as the
--       integer 1 and then widened back to the string '1', which no key in the
--       baseline matches.
INSERT INTO reference.transaction_categories
    (type_cd, cat_cd, description) VALUES
    ('01', '0001', 'Regular Sales Draft'),
    ('01', '0002', 'Regular Cash Advance'),
    ('01', '0003', 'Convenience Check Debit'),
    ('01', '0004', 'ATM Cash Advance'),
    ('01', '0005', 'Interest Amount'),
    ('02', '0001', 'Cash payment'),
    ('02', '0002', 'Electronic payment'),
    ('02', '0003', 'Check payment'),
    ('03', '0001', 'Credit to Account'),
    ('03', '0002', 'Credit to Purchase balance'),
    ('03', '0003', 'Credit to Cash balance'),
    ('04', '0001', 'Zero dollar authorization'),
    ('04', '0002', 'Online purchase authorization'),
    ('04', '0003', 'Travel booking authorization'),
    ('05', '0001', 'Refund credit'),
    ('06', '0001', 'Fraud reversal'),
    ('06', '0002', 'Non-fraud reversal'),
    ('07', '0001', 'Sales draft credit adjustment')
ON CONFLICT (type_cd, cat_cd) DO NOTHING;

-- WHY : Assumptions: this statement must stay below the transaction_types
--       insert. V1's fk_transaction_categories_type references
--       transaction_types(type_cd), and PostgreSQL checks a foreign key as each
--       row is inserted rather than deferring to commit, so reversing the two
--       statements would fail every row here with SQLSTATE 23503 even though
--       the parent rows arrive later in the same transaction. Declaring the
--       constraint DEFERRABLE INITIALLY DEFERRED would have made the order
--       irrelevant, and was rejected: it would weaken a constraint that exists
--       to preserve an observable refusal, in order to buy freedom to reorder
--       two statements that have no reason to move.
--
-- WHY : Assumptions: the same casing divergence recorded at transaction_types
--       applies to all eighteen rows here, and one spelling differs too.
--       app/app-transaction-type-db2/ctl/DB2LTCAT.ctl seeds these categories at
--       its L20-L37 in upper case, and its L36 writes 'NON FRAUD REVERSAL' with
--       a space where app/data/ASCII/trancatg.txt row 17 has 'Non-fraud
--       reversal' with a hyphen. The ASCII dataset is seeded for the reason
--       given at transaction_types, and neither baseline file is altered.
--
-- WHY : Assumptions: the ('01','0005') row above, 'Interest Amount', is the
--       category that app/cbl/CBACT04C.cbl posts generated interest into -- it
--       moves '01' to TRAN-TYPE-CD at L482 and '05' to TRAN-CAT-CD at L483.
--       It is present here because trancatg.txt defines it, and it is
--       deliberately absent from the disclosure_groups seed below because
--       discgrp.txt does not define a rate for it. That asymmetry between the
--       two datasets is not an omission in this file; it is the condition the
--       DEFAULT group exists to answer, and it is explained at that insert.


-- -----------------------------------------------------------------------------
-- reference.disclosure_groups  <-  app/data/ASCII/discgrp.txt (51 x 50 bytes)
--
-- Field positions taken from app/cpy/CVTRA02Y.cpy: the DIS-GROUP-KEY group
-- holds DIS-ACCT-GROUP-ID PIC X(10) at columns 1-10, DIS-TRAN-TYPE-CD PIC X(02)
-- at 11-12 and DIS-TRAN-CAT-CD PIC 9(04) at 13-16, followed by DIS-INT-RATE
-- PIC S9(04)V99 at 17-22 and a FILLER PIC X(28) at 23-50 that holds zeros in
-- all fifty-one rows and is dropped.
--
-- The dataset holds three group ids, each carrying the same seventeen
-- (type, category) combinations: 'A000000000', 'DEFAULT   ' and 'ZEROAPR   ',
-- seventeen rows each. The two shorter ids are stored space-padded to the full
-- ten bytes, and that padding is data rather than presentation -- see below.
-- -----------------------------------------------------------------------------

-- WHY : Assumptions: the rates below are written as decoded fixed-point
--       literals, and the bytes on disk are not decimal digits. discgrp.txt
--       stores DIS-INT-RATE as zoned decimal with a sign overpunch in the
--       trailing byte, so the six bytes of a 15.00 rate are '00150{' -- the '{'
--       is the overpunch that encodes a final digit 0 together with a positive
--       sign. Under PIC S9(04)V99 the six digits carry an implied decimal point
--       two places from the right, so the field decodes to 0015.00.
--       The whole dataset uses only three rate values, and their multiplicities
--       account for all fifty-one rows: '00000{' appears 30 times and decodes
--       to 0.00, '00150{' appears 15 times and decodes to 15.00, and '00250{'
--       appears 6 times and decodes to 25.00. Every rate in this dataset is
--       therefore non-negative, and no negative overpunch byte occurs.
--       The decoding is done before the literal reaches this file, and no
--       overpunch byte appears anywhere in it. Passing '00150{' to PostgreSQL
--       would not silently mis-store the rate, it would fail the statement with
--       an invalid-input error for NUMERIC, so the risk this note guards
--       against is not a failed load but a hand-edit that "fixes" the literal
--       by stripping the brace to 00150 and stores a rate of 150.00 in place of
--       15.00 -- a hundredfold error in an operand that
--       app/cbl/CBACT04C.cbl L464-L465 multiplies a balance by. Where this
--       decoding happens in Java rather than in a literal, the one permitted
--       decoder is ZonedDecimalCodec in common-lib's codec package.
INSERT INTO reference.disclosure_groups
    (acct_group_id, tran_type_cd, tran_cat_cd, interest_rate) VALUES
    ('A000000000', '01', '0001', 15.00),
    ('A000000000', '01', '0002', 25.00),
    ('A000000000', '01', '0003', 25.00),
    ('A000000000', '01', '0004', 25.00),
    ('A000000000', '02', '0001',  0.00),
    ('A000000000', '02', '0002',  0.00),
    ('A000000000', '02', '0003',  0.00),
    ('A000000000', '03', '0001',  0.00),
    ('A000000000', '03', '0002',  0.00),
    ('A000000000', '03', '0003',  0.00),
    ('A000000000', '04', '0001', 15.00),
    ('A000000000', '04', '0002', 15.00),
    ('A000000000', '04', '0003', 15.00),
    ('A000000000', '05', '0001', 15.00),
    ('A000000000', '06', '0001', 15.00),
    ('A000000000', '06', '0002', 15.00),
    ('A000000000', '07', '0001', 15.00),

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
    ('DEFAULT   ', '07', '0001',  0.00),

    ('ZEROAPR   ', '01', '0001',  0.00),
    ('ZEROAPR   ', '01', '0002',  0.00),
    ('ZEROAPR   ', '01', '0003',  0.00),
    ('ZEROAPR   ', '01', '0004',  0.00),
    ('ZEROAPR   ', '02', '0001',  0.00),
    ('ZEROAPR   ', '02', '0002',  0.00),
    ('ZEROAPR   ', '02', '0003',  0.00),
    ('ZEROAPR   ', '03', '0001',  0.00),
    ('ZEROAPR   ', '03', '0002',  0.00),
    ('ZEROAPR   ', '03', '0003',  0.00),
    ('ZEROAPR   ', '04', '0001',  0.00),
    ('ZEROAPR   ', '04', '0002',  0.00),
    ('ZEROAPR   ', '04', '0003',  0.00),
    ('ZEROAPR   ', '05', '0001',  0.00),
    ('ZEROAPR   ', '06', '0001',  0.00),
    ('ZEROAPR   ', '06', '0002',  0.00),
    ('ZEROAPR   ', '07', '0001',  0.00)
ON CONFLICT (acct_group_id, tran_type_cd, tran_cat_cd) DO NOTHING;

-- WHY : Assumptions: the seventeen 'DEFAULT   ' rows above are load-bearing.
--       They are not a convenience default and they are not redundant with
--       'A000000000'; deleting them makes batch-service abend. The mechanism,
--       read from app/cbl/CBACT04C.cbl:
--         (1) 1200-GET-INTEREST-RATE at L415-L420 reads this table by the
--             account's own disclosure group. L422 accepts file status '00' or
--             '23', so a miss -- status 23, record not found -- is tolerated
--             rather than treated as an error, and L431-L434 abends on any
--             other status.
--         (2) L436-L439 is the consumer of these rows: on status '23' it moves
--             the literal 'DEFAULT' into FD-DIS-ACCT-GROUP-ID and performs
--             1200-A-GET-DEFAULT-INT-RATE.
--         (3) 1200-A-GET-DEFAULT-INT-RATE at L443-L460 re-reads this table with
--             that key and tolerates nothing. On any status other than '00' it
--             displays 'ERROR READING DEFAULT DISCLOSURE GROUP' at L455 and
--             performs 9999-ABEND-PROGRAM at L458.
--         (4) 9999-ABEND-PROGRAM at L628-L632 is a genuine Language Environment
--             abend, not a soft exit: it displays 'ABENDING PROGRAM', moves 999
--             to ABCODE and calls 'CEE3ABD'. The interest run does not degrade
--             gracefully and does not complete with a warning -- it terminates.
--       So the second read has no fallback of its own, which is what makes
--       these rows a hard dependency rather than a nicety. Worth stating
--       because of where the failure surfaces: the rows are seeded by
--       reference-service and consumed by batch-service, so removing them
--       produces a crash in a service that contains no defect of its own,
--       which is the hardest kind of failure to locate.
--       Do not read the same paragraph name elsewhere in the baseline as
--       equivalent: app/app-transaction-type-db2/cbl/COBTUPDT.cbl L230-L233
--       also has a 9999-ABEND, but that one displays a message, moves 4 to
--       RETURN-CODE and exits, so it continues. The two sit at opposite
--       severities despite the shared name.
--
-- WHY : Assumptions: these seventeen combinations do not include ('01','0005'),
--       and that gap is the reason the fallback above is reachable at all.
--       discgrp.txt defines rates for seventeen (type, category) pairs and
--       'Interest Amount' is not among them, while L482-L483 of
--       app/cbl/CBACT04C.cbl posts generated interest into exactly that pair.
--       The first read therefore misses by construction on the program's own
--       generated rows, status 23 fires, and the DEFAULT group supplies the
--       rate. The gap is faithful to the dataset and is left visible here
--       rather than filled in, because inventing an ('01','0005') rate would
--       make the fallback unreachable and silently change which rate the
--       interest calculation uses.
--
-- WHY : Alternatives Considered: no foreign key is added from these columns to
--       reference.transaction_categories, and the seventeen combinations
--       present would in fact all satisfy one -- every pair above appears in
--       the eighteen categories seeded earlier. It is still not added: V1 owns
--       the constraints and declares none here, its own note explains that the
--       baseline states no such relationship, and this file authors no DDL. The
--       observation is recorded only so that the ('01','0005') gap reads as a
--       property of the data rather than as evidence of a missing constraint.
--
-- WHY : Assumptions: 'DEFAULT   ' and 'ZEROAPR   ' are written with their
--       trailing spaces because acct_group_id is CHAR(10) and the padding is
--       part of the stored key. app/cbl/CBACT04C.cbl L79 declares the field
--       PIC X(10) and L437 moves the seven-character literal 'DEFAULT' into it;
--       a short alphanumeric move in COBOL is left-justified and space-filled,
--       so the key actually searched for is 'DEFAULT' followed by three spaces,
--       which is exactly how discgrp.txt stores those rows. These are the only
--       string literals in this file whose interior spaces are data; no literal
--       here is padded for alignment, so that the two cannot be confused.


-- -----------------------------------------------------------------------------
-- reference.us_phone_area_codes  <-  app/cpy/CSLKPCDY.cpy (490 codes)
--
-- These codes are not a dataset. The baseline holds them as 88-level condition
-- names over a working-storage field, compiled into every program that tests a
-- value, so there is no VSAM cluster and no REPRO step to be the analogue of
-- this insert. The copybook cites the North American Numbering Plan
-- Administrator's NPA report as its source at its own L26-L28.
--
-- All three condition names hang off one field, L24
-- WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX: VALID-PHONE-AREA-CODE at L30 with 490
-- literals, VALID-GENERAL-PURP-CODE at L521 with 410 and
-- VALID-EASY-RECOG-AREA-CODE at L931 with 80.
-- -----------------------------------------------------------------------------

-- WHY : Alternatives Considered: one table with a classification column, rather
--       than two tables or a pair of boolean flags, because the two narrower
--       lists partition the broader one exactly. Counting the literals in the
--       copybook gives 410 general-purpose codes and 80 easily-recognisable
--       ones; they sum to the 490 of VALID-PHONE-AREA-CODE, their intersection
--       is empty, and their union is equal to it as a set -- a total and
--       disjoint partition, verified in all three directions rather than
--       assumed from the arithmetic alone.
--       That property is what makes one column both sufficient and correct.
--       Because the partition is total, every one of the 490 rows below carries
--       a class and none is NULL, which is why V1 could declare the column NOT
--       NULL. Because it is disjoint, no code belongs to both, which is why one
--       column suffices where a pair of flags would admit both-true and
--       both-false rows that the copybook cannot express and no program can
--       interpret. Two separate tables were rejected for a further reason: the
--       only test account-service performs is the broad 490-code one, which
--       reduces here to a single primary-key probe but would become a union
--       across two tables.
--       Stated explicitly because it is easy to get backwards: these two lists
--       do not overlap. Any description of them as overlapping is wrong, and a
--       reader who assumed it would expect codes to need two classes.
--
-- WHY : Assumptions: 'G' is the general-purpose list at app/cpy/CSLKPCDY.cpy
--       L521 and 'E' the easily-recognisable list at L931, matching the two
--       values V1's ck_us_phone_area_codes_class admits. The class of each row
--       below is derived from which of the two copybook lists contains that
--       code, not assigned by pattern: the easily-recognisable codes are mostly
--       repeated-digit and toll-free forms, but that regularity is a
--       consequence of the list rather than its definition, and deriving the
--       class from the shape of the digits would misclassify any code the
--       administrator assigned against the pattern.
INSERT INTO reference.us_phone_area_codes (area_cd, code_class) VALUES
    ('201', 'G'), ('202', 'G'), ('203', 'G'), ('204', 'G'), ('205', 'G'),
    ('206', 'G'), ('207', 'G'), ('208', 'G'), ('209', 'G'), ('210', 'G'),
    ('212', 'G'), ('213', 'G'), ('214', 'G'), ('215', 'G'), ('216', 'G'),
    ('217', 'G'), ('218', 'G'), ('219', 'G'), ('220', 'G'), ('223', 'G'),
    ('224', 'G'), ('225', 'G'), ('226', 'G'), ('228', 'G'), ('229', 'G'),
    ('231', 'G'), ('234', 'G'), ('236', 'G'), ('239', 'G'), ('240', 'G'),
    ('242', 'G'), ('246', 'G'), ('248', 'G'), ('249', 'G'), ('250', 'G'),
    ('251', 'G'), ('252', 'G'), ('253', 'G'), ('254', 'G'), ('256', 'G'),
    ('260', 'G'), ('262', 'G'), ('264', 'G'), ('267', 'G'), ('268', 'G'),
    ('269', 'G'), ('270', 'G'), ('272', 'G'), ('276', 'G'), ('279', 'G'),
    ('281', 'G'), ('284', 'G'), ('289', 'G'), ('301', 'G'), ('302', 'G'),
    ('303', 'G'), ('304', 'G'), ('305', 'G'), ('306', 'G'), ('307', 'G'),
    ('308', 'G'), ('309', 'G'), ('310', 'G'), ('312', 'G'), ('313', 'G'),
    ('314', 'G'), ('315', 'G'), ('316', 'G'), ('317', 'G'), ('318', 'G'),
    ('319', 'G'), ('320', 'G'), ('321', 'G'), ('323', 'G'), ('325', 'G'),
    ('326', 'G'), ('330', 'G'), ('331', 'G'), ('332', 'G'), ('334', 'G'),
    ('336', 'G'), ('337', 'G'), ('339', 'G'), ('340', 'G'), ('341', 'G'),
    ('343', 'G'), ('345', 'G'), ('346', 'G'), ('347', 'G'), ('351', 'G'),
    ('352', 'G'), ('360', 'G'), ('361', 'G'), ('364', 'G'), ('365', 'G'),
    ('367', 'G'), ('368', 'G'), ('380', 'G'), ('385', 'G'), ('386', 'G'),
    ('401', 'G'), ('402', 'G'), ('403', 'G'), ('404', 'G'), ('405', 'G'),
    ('406', 'G'), ('407', 'G'), ('408', 'G'), ('409', 'G'), ('410', 'G'),
    ('412', 'G'), ('413', 'G'), ('414', 'G'), ('415', 'G'), ('416', 'G'),
    ('417', 'G'), ('418', 'G'), ('419', 'G'), ('423', 'G'), ('424', 'G'),
    ('425', 'G'), ('430', 'G'), ('431', 'G'), ('432', 'G'), ('434', 'G'),
    ('435', 'G'), ('437', 'G'), ('438', 'G'), ('440', 'G'), ('441', 'G'),
    ('442', 'G'), ('443', 'G'), ('445', 'G'), ('447', 'G'), ('448', 'G'),
    ('450', 'G'), ('458', 'G'), ('463', 'G'), ('464', 'G'), ('469', 'G'),
    ('470', 'G'), ('473', 'G'), ('474', 'G'), ('475', 'G'), ('478', 'G'),
    ('479', 'G'), ('480', 'G'), ('484', 'G'), ('501', 'G'), ('502', 'G'),
    ('503', 'G'), ('504', 'G'), ('505', 'G'), ('506', 'G'), ('507', 'G'),
    ('508', 'G'), ('509', 'G'), ('510', 'G'), ('512', 'G'), ('513', 'G'),
    ('514', 'G'), ('515', 'G'), ('516', 'G'), ('517', 'G'), ('518', 'G'),
    ('519', 'G'), ('520', 'G'), ('530', 'G'), ('531', 'G'), ('534', 'G'),
    ('539', 'G'), ('540', 'G'), ('541', 'G'), ('548', 'G'), ('551', 'G'),
    ('559', 'G'), ('561', 'G'), ('562', 'G'), ('563', 'G'), ('564', 'G'),
    ('567', 'G'), ('570', 'G'), ('571', 'G'), ('572', 'G'), ('573', 'G'),
    ('574', 'G'), ('575', 'G'), ('579', 'G'), ('580', 'G'), ('581', 'G'),
    ('582', 'G'), ('585', 'G'), ('586', 'G'), ('587', 'G'), ('601', 'G'),
    ('602', 'G'), ('603', 'G'), ('604', 'G'), ('605', 'G'), ('606', 'G'),
    ('607', 'G'), ('608', 'G'), ('609', 'G'), ('610', 'G'), ('612', 'G'),
    ('613', 'G'), ('614', 'G'), ('615', 'G'), ('616', 'G'), ('617', 'G'),
    ('618', 'G'), ('619', 'G'), ('620', 'G'), ('623', 'G'), ('626', 'G'),
    ('628', 'G'), ('629', 'G'), ('630', 'G'), ('631', 'G'), ('636', 'G'),
    ('639', 'G'), ('640', 'G'), ('641', 'G'), ('646', 'G'), ('647', 'G'),
    ('649', 'G'), ('650', 'G'), ('651', 'G'), ('656', 'G'), ('657', 'G'),
    ('658', 'G'), ('659', 'G'), ('660', 'G'), ('661', 'G'), ('662', 'G'),
    ('664', 'G'), ('667', 'G'), ('669', 'G'), ('670', 'G'), ('671', 'G'),
    ('672', 'G'), ('678', 'G'), ('680', 'G'), ('681', 'G'), ('682', 'G'),
    ('683', 'G'), ('684', 'G'), ('689', 'G'), ('701', 'G'), ('702', 'G'),
    ('703', 'G'), ('704', 'G'), ('705', 'G'), ('706', 'G'), ('707', 'G'),
    ('708', 'G'), ('709', 'G'), ('712', 'G'), ('713', 'G'), ('714', 'G'),
    ('715', 'G'), ('716', 'G'), ('717', 'G'), ('718', 'G'), ('719', 'G'),
    ('720', 'G'), ('721', 'G'), ('724', 'G'), ('725', 'G'), ('726', 'G'),
    ('727', 'G'), ('731', 'G'), ('732', 'G'), ('734', 'G'), ('737', 'G'),
    ('740', 'G'), ('742', 'G'), ('743', 'G'), ('747', 'G'), ('753', 'G'),
    ('754', 'G'), ('757', 'G'), ('758', 'G'), ('760', 'G'), ('762', 'G'),
    ('763', 'G'), ('765', 'G'), ('767', 'G'), ('769', 'G'), ('770', 'G'),
    ('771', 'G'), ('772', 'G'), ('773', 'G'), ('774', 'G'), ('775', 'G'),
    ('778', 'G'), ('779', 'G'), ('780', 'G'), ('781', 'G'), ('782', 'G'),
    ('784', 'G'), ('785', 'G'), ('786', 'G'), ('787', 'G'), ('801', 'G'),
    ('802', 'G'), ('803', 'G'), ('804', 'G'), ('805', 'G'), ('806', 'G'),
    ('807', 'G'), ('808', 'G'), ('809', 'G'), ('810', 'G'), ('812', 'G'),
    ('813', 'G'), ('814', 'G'), ('815', 'G'), ('816', 'G'), ('817', 'G'),
    ('818', 'G'), ('819', 'G'), ('820', 'G'), ('825', 'G'), ('826', 'G'),
    ('828', 'G'), ('829', 'G'), ('830', 'G'), ('831', 'G'), ('832', 'G'),
    ('838', 'G'), ('839', 'G'), ('840', 'G'), ('843', 'G'), ('845', 'G'),
    ('847', 'G'), ('848', 'G'), ('849', 'G'), ('850', 'G'), ('854', 'G'),
    ('856', 'G'), ('857', 'G'), ('858', 'G'), ('859', 'G'), ('860', 'G'),
    ('862', 'G'), ('863', 'G'), ('864', 'G'), ('865', 'G'), ('867', 'G'),
    ('868', 'G'), ('869', 'G'), ('870', 'G'), ('872', 'G'), ('873', 'G'),
    ('876', 'G'), ('878', 'G'), ('901', 'G'), ('902', 'G'), ('903', 'G'),
    ('904', 'G'), ('905', 'G'), ('906', 'G'), ('907', 'G'), ('908', 'G'),
    ('909', 'G'), ('910', 'G'), ('912', 'G'), ('913', 'G'), ('914', 'G'),
    ('915', 'G'), ('916', 'G'), ('917', 'G'), ('918', 'G'), ('919', 'G'),
    ('920', 'G'), ('925', 'G'), ('928', 'G'), ('929', 'G'), ('930', 'G'),
    ('931', 'G'), ('934', 'G'), ('936', 'G'), ('937', 'G'), ('938', 'G'),
    ('939', 'G'), ('940', 'G'), ('941', 'G'), ('943', 'G'), ('945', 'G'),
    ('947', 'G'), ('948', 'G'), ('949', 'G'), ('951', 'G'), ('952', 'G'),
    ('954', 'G'), ('956', 'G'), ('959', 'G'), ('970', 'G'), ('971', 'G'),
    ('972', 'G'), ('973', 'G'), ('978', 'G'), ('979', 'G'), ('980', 'G'),
    ('983', 'G'), ('984', 'G'), ('985', 'G'), ('986', 'G'), ('989', 'G'),
    ('200', 'E'), ('211', 'E'), ('222', 'E'), ('233', 'E'), ('244', 'E'),
    ('255', 'E'), ('266', 'E'), ('277', 'E'), ('288', 'E'), ('299', 'E'),
    ('300', 'E'), ('311', 'E'), ('322', 'E'), ('333', 'E'), ('344', 'E'),
    ('355', 'E'), ('366', 'E'), ('377', 'E'), ('388', 'E'), ('399', 'E'),
    ('400', 'E'), ('411', 'E'), ('422', 'E'), ('433', 'E'), ('444', 'E'),
    ('455', 'E'), ('466', 'E'), ('477', 'E'), ('488', 'E'), ('499', 'E'),
    ('500', 'E'), ('511', 'E'), ('522', 'E'), ('533', 'E'), ('544', 'E'),
    ('555', 'E'), ('566', 'E'), ('577', 'E'), ('588', 'E'), ('599', 'E'),
    ('600', 'E'), ('611', 'E'), ('622', 'E'), ('633', 'E'), ('644', 'E'),
    ('655', 'E'), ('666', 'E'), ('677', 'E'), ('688', 'E'), ('699', 'E'),
    ('700', 'E'), ('711', 'E'), ('722', 'E'), ('733', 'E'), ('744', 'E'),
    ('755', 'E'), ('766', 'E'), ('777', 'E'), ('788', 'E'), ('799', 'E'),
    ('800', 'E'), ('811', 'E'), ('822', 'E'), ('833', 'E'), ('844', 'E'),
    ('855', 'E'), ('866', 'E'), ('877', 'E'), ('888', 'E'), ('899', 'E'),
    ('900', 'E'), ('911', 'E'), ('922', 'E'), ('933', 'E'), ('944', 'E'),
    ('955', 'E'), ('966', 'E'), ('977', 'E'), ('988', 'E'), ('999', 'E')
ON CONFLICT (area_cd) DO NOTHING;


-- -----------------------------------------------------------------------------
-- reference.us_states  <-  app/cpy/CSLKPCDY.cpy (56 codes)
--
-- L1012 declares US-STATE-CODE-TO-EDIT PIC X(2) and L1013 carries
-- VALID-US-STATE-CODE over it with 56 literals: the fifty states plus the
-- district, the territories and the military mailing codes. The rows below keep
-- the copybook's declaration order, which runs alphabetically through the fifty
-- states and then appends DC and the five non-state codes, rather than being
-- re-sorted -- the order carries the copybook's own grouping and a diff against
-- the source stays readable.
--
-- The comment immediately above the field, at L1011, reads as though it
-- introduced the phone area codes rather than the states. It is cited as it
-- stands and not altered; app/** is read as reference only.
-- -----------------------------------------------------------------------------
INSERT INTO reference.us_states (state_cd) VALUES
    ('AL'), ('AK'), ('AZ'), ('AR'), ('CA'), ('CO'), ('CT'), ('DE'),
    ('FL'), ('GA'), ('HI'), ('ID'), ('IL'), ('IN'), ('IA'), ('KS'),
    ('KY'), ('LA'), ('ME'), ('MD'), ('MA'), ('MI'), ('MN'), ('MS'),
    ('MO'), ('MT'), ('NE'), ('NV'), ('NH'), ('NJ'), ('NM'), ('NY'),
    ('NC'), ('ND'), ('OH'), ('OK'), ('OR'), ('PA'), ('RI'), ('SC'),
    ('SD'), ('TN'), ('TX'), ('UT'), ('VT'), ('VA'), ('WA'), ('WV'),
    ('WI'), ('WY'), ('DC'), ('AS'), ('GU'), ('MP'), ('PR'), ('VI')
ON CONFLICT (state_cd) DO NOTHING;


-- -----------------------------------------------------------------------------
-- reference.us_state_zip_prefixes  <-  app/cpy/CSLKPCDY.cpy (240 codes)
--
-- L1071 declares US-STATE-ZIPCODE-TO-EDIT, whose L1072 subordinate
-- US-STATE-AND-FIRST-ZIP2 PIC X(4) carries VALID-US-STATE-ZIP-CD2-COMBO at
-- L1073 with 240 literals. Each literal is a two-character state code followed
-- by the first two digits of a postal code, stored as the one concatenated
-- value the baseline builds and tests as a unit.
-- -----------------------------------------------------------------------------

-- WHY : Refactoring Rationale: this table and the two above are seeded here but
--       read elsewhere. account-service's AddressValidationService queries all
--       three and owns none of them, which follows the single-ownership rule
--       that gives each table exactly one writing service. The consequence is
--       worth naming because it decides where a defect appears: a code missing
--       from this seed causes no failure in reference-service at all, and
--       surfaces instead as an address that account-service rejects as invalid
--       during account maintenance. There is nothing in that rejection to point
--       at the seed, which is why the three counts are asserted by test rather
--       than left to be noticed.
INSERT INTO reference.us_state_zip_prefixes (state_zip_cd) VALUES
    ('AA34'), ('AE90'), ('AE91'), ('AE92'), ('AE93'), ('AE94'), ('AE95'),
    ('AE96'), ('AE97'), ('AE98'), ('AK99'), ('AL35'), ('AL36'), ('AP96'),
    ('AR71'), ('AR72'), ('AS96'), ('AZ85'), ('AZ86'), ('CA90'), ('CA91'),
    ('CA92'), ('CA93'), ('CA94'), ('CA95'), ('CA96'), ('CO80'), ('CO81'),
    ('CT60'), ('CT61'), ('CT62'), ('CT63'), ('CT64'), ('CT65'), ('CT66'),
    ('CT67'), ('CT68'), ('CT69'), ('DC20'), ('DC56'), ('DC88'), ('DE19'),
    ('FL32'), ('FL33'), ('FL34'), ('FM96'), ('GA30'), ('GA31'), ('GA39'),
    ('GU96'), ('HI96'), ('IA50'), ('IA51'), ('IA52'), ('ID83'), ('IL60'),
    ('IL61'), ('IL62'), ('IN46'), ('IN47'), ('KS66'), ('KS67'), ('KY40'),
    ('KY41'), ('KY42'), ('LA70'), ('LA71'), ('MA10'), ('MA11'), ('MA12'),
    ('MA13'), ('MA14'), ('MA15'), ('MA16'), ('MA17'), ('MA18'), ('MA19'),
    ('MA20'), ('MA21'), ('MA22'), ('MA23'), ('MA24'), ('MA25'), ('MA26'),
    ('MA27'), ('MA55'), ('MD20'), ('MD21'), ('ME39'), ('ME40'), ('ME41'),
    ('ME42'), ('ME43'), ('ME44'), ('ME45'), ('ME46'), ('ME47'), ('ME48'),
    ('ME49'), ('MH96'), ('MI48'), ('MI49'), ('MN55'), ('MN56'), ('MO63'),
    ('MO64'), ('MO65'), ('MO72'), ('MP96'), ('MS38'), ('MS39'), ('MT59'),
    ('NC27'), ('NC28'), ('ND58'), ('NE68'), ('NE69'), ('NH30'), ('NH31'),
    ('NH32'), ('NH33'), ('NH34'), ('NH35'), ('NH36'), ('NH37'), ('NH38'),
    ('NJ70'), ('NJ71'), ('NJ72'), ('NJ73'), ('NJ74'), ('NJ75'), ('NJ76'),
    ('NJ77'), ('NJ78'), ('NJ79'), ('NJ80'), ('NJ81'), ('NJ82'), ('NJ83'),
    ('NJ84'), ('NJ85'), ('NJ86'), ('NJ87'), ('NJ88'), ('NJ89'), ('NM87'),
    ('NM88'), ('NV88'), ('NV89'), ('NY50'), ('NY54'), ('NY63'), ('NY10'),
    ('NY11'), ('NY12'), ('NY13'), ('NY14'), ('OH43'), ('OH44'), ('OH45'),
    ('OK73'), ('OK74'), ('OR97'), ('PA15'), ('PA16'), ('PA17'), ('PA18'),
    ('PA19'), ('PR60'), ('PR61'), ('PR62'), ('PR63'), ('PR64'), ('PR65'),
    ('PR66'), ('PR67'), ('PR68'), ('PR69'), ('PR70'), ('PR71'), ('PR72'),
    ('PR73'), ('PR74'), ('PR75'), ('PR76'), ('PR77'), ('PR78'), ('PR79'),
    ('PR90'), ('PR91'), ('PR92'), ('PR93'), ('PR94'), ('PR95'), ('PR96'),
    ('PR97'), ('PR98'), ('PW96'), ('RI28'), ('RI29'), ('SC29'), ('SD57'),
    ('TN37'), ('TN38'), ('TX73'), ('TX75'), ('TX76'), ('TX77'), ('TX78'),
    ('TX79'), ('TX88'), ('UT84'), ('VA20'), ('VA22'), ('VA23'), ('VA24'),
    ('VI80'), ('VI82'), ('VI83'), ('VI84'), ('VI85'), ('VT50'), ('VT51'),
    ('VT52'), ('VT53'), ('VT54'), ('VT56'), ('VT57'), ('VT58'), ('VT59'),
    ('WA98'), ('WA99'), ('WI53'), ('WI54'), ('WV24'), ('WV25'), ('WV26'),
    ('WY82'), ('WY83')
ON CONFLICT (state_zip_cd) DO NOTHING;


-- =============================================================================
-- Seeded row counts, restated so that a future diff is auditable without
-- re-counting the baseline:
--
--   reference.transaction_types            7
--   reference.transaction_categories      18
--   reference.disclosure_groups           51   (three group ids x 17 each)
--                                        ---
--   reference subtotal                    76
--
--   reference.us_phone_area_codes        490   (410 class 'G' + 80 class 'E')
--   reference.us_states                   56
--   reference.us_state_zip_prefixes      240
--                                        ---
--   lookup subtotal                      786
--                                        ===
--   total                                862
--
-- WHY : Assumptions: these counts are the contract, not a description. Each one
--       is a literal count of its baseline source, and for the three datasets
--       the byte arithmetic closes exactly on the file size, which is what
--       rules out a row lost to a mis-parsed line ending:
--         trantype.txt  7 x 60 = 420 data bytes, + 6 CR + 7 LF =  433 on disk
--         trancatg.txt 18 x 60 = 1080 data bytes, + 18 CR + 18 LF = 1116
--         discgrp.txt  51 x 50 = 2550 data bytes, + 0 CR + 51 LF = 2601
--       The line endings genuinely differ between the three -- trantype.txt is
--       CRLF except on its last row, trancatg.txt is CRLF throughout and
--       discgrp.txt is LF only -- so a reader that assumes one convention drops
--       or gains a row silently. The three lookup counts are the literals under
--       the copybook condition names cited at each insert.
--       The counts are restated here because every one of them is silent when
--       wrong: a short seed raises no error, and the two consequences it does
--       have are both remote from this file -- an interest run that abends in
--       batch-service, and an address rejected in account-service. They are
--       asserted in reference-service's own tests for that reason.
-- =============================================================================
