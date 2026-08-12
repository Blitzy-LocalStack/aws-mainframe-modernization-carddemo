-- =============================================================================
-- services/auth-service/src/test/resources/fixtures/users-keyset.sql
-- -----------------------------------------------------------------------------
-- Purpose: supplies the contiguous ascending row volume that makes keyset
--       pagination over the Flyway-created auth.users table observable at the
--       baseline page size of ten. This block is the single load for the
--       repository integration test of that pagination: one load drives the
--       forward query, the backward query, the size-plus-one probe, the exact
--       hasNext boundary, the short final page and both empty-cursor directions,
--       with the cursor as the only thing that varies between cases -- which is
--       why the row set is shaped once here rather than per case. It loads
--       twenty-one deterministic rows, KSET0001 through KSET0021, in strict
--       ascending key order, spanning both the A and U user types.
--
--       Assumptions: three failure modes are loud -- a repeated cognito_sub is
--       rejected by UNIQUE, a user_type outside A/U by CHECK, and an identifier
--       wider than eight characters by the CHAR(8) primary key. The fourth
--       raises nothing at all and is the one to watch: one stray row shifts
--       every cursor boundary in the outcome table below, so the keyset
--       assertions still run, still pass, and measure the wrong thing.
--
--       Alternatives Considered: JSON, rejected because it cannot carry adjacent
--       comments; and the house fixed-width text format, rejected because
--       auth-service performs no fixed-width decoding, the online CICS flow has
--       no golden master, and app/cpy/CSUSR01Y.cpy L17-L23 declares only PIC X
--       fields, so there is no monetary or encoded-decimal value to image.
--       tests/README.md L540-L542 requires copybook layouts to stay
--       single-sourced, so this file cites the layout rather than restating it.
--       Trade-offs: SQL ties the fixture to PostgreSQL syntax, in exchange for
--       being directly loadable and keeping every rationale beside its row.
--
--       Assumptions: ten rows to a page is a baseline contract rather than a
--       tunable, resting on two live authorities -- the EVALUATE at
--       app/cbl/COUSR00C.cbl L384 whose only row branches are WHEN 1 at L387
--       through WHEN 10 at L433, and the ten row field families
--       app/cpy-bms/COUSR00.CPY declares from L78. The OCCURS 10 TIMES
--       declaration at L56-L57 is NOT relied on, its three names having no
--       procedure-division reference at all, so that block is dead. The card-list
--       screen's count does not transfer either.
--
--       Assumptions: app/cbl/COUSR00C.cbl L66 copies COCOM01Y and extends it at
--       L68-L70 with a first-key, a last-key and a page-number field. A
--       first-key and last-key pair carried across the pseudo-conversational gap
--       already IS a keyset cursor, so the target envelope of firstKey, lastKey
--       and hasNext is a one-to-one mapping rather than an approximation, and
--       this block is shaped to prove it.
--
--       Assumptions: two query shapes read this block. Forward selects
--       WHERE user_id > lastKey ORDER BY user_id ASC LIMIT size + 1, the extra
--       row being a probe that sets hasNext and is then discarded. Backward
--       selects WHERE user_id < firstKey ORDER BY user_id DESC LIMIT size + 1 and
--       reverses the retained rows to ascending, the direct analogue of READPREV.
--       Both are deterministic only if the keys totally order, which is why every
--       identifier below is exactly eight characters with a zero-padded ascending
--       counter, making lexical order and numeric order the same.
--
--       Refactoring Rationale: offset pagination is rejected outright rather than
--       merely disfavoured, because skipping a counted number of rows repeats and
--       drops rows once a concurrent insert lands inside the range already
--       scanned, which is an observable behaviour change browsing by key does not
--       have. The baseline never counted rows to find its position either: its
--       page-number field is display state, written to the screen and read at
--       L248 solely to decide whether the top-of-list refusal at L251-L252 is
--       due, so this fixture carries no ordinal at all.
--
--       Alternatives Considered: twenty rows, rejected because twenty reach the
--       exact hasNext FALSE boundary but cannot also produce a page shorter than
--       ten, and adding a second twenty-row file to get one would repeat twenty
--       near-identical rows for no new coverage while risking a primary-key and
--       cognito_sub collision with this one. Twenty-one reaches every case from a
--       single block. Trade-offs: one row past two whole pages looks arbitrary
--       until the arithmetic is written out, so it is written out here beside the
--       rows it describes rather than in the test that consumes them.
--
--       Assumptions: at page size ten this block yields exactly these outcomes,
--       and every keyset assertion in the consuming test depends on them holding.
--       Probe records whether an eleventh row was found.
--       cursor            items returned             probe  flag
--       empty             KSET0001..KSET0010 (10)    hit    hasNext TRUE
--       last=KSET0010     KSET0011..KSET0020 (10)    hit    hasNext TRUE
--       last=KSET0011     KSET0012..KSET0021 (10)    miss   hasNext FALSE
--       last=KSET0020     KSET0021 (1)               miss   hasNext FALSE
--       first=KSET0012    KSET0002..KSET0011 (10)    hit    hasNext TRUE
--       Row three is the exact boundary, a full page of ten with nothing beyond
--       it; row four is the short final page; row five reads backward.
--
--       Assumptions: row five's flag is TRUE for a reason the other four do not
--       share. The four forward rows take it from the probe, whereas a backward
--       walk sets it unconditionally in UserService.page -- a caller that has
--       just stepped back arrived from the page ahead, so a page ahead
--       demonstrably exists and no probe is needed to learn it. Row five's probe
--       column therefore records only that an eleventh row was found below the
--       position, and that finding is NOT published: whether an EARLIER page
--       exists is not a member of the four-member envelope at all. The reference
--       answers that question from the screen ordinal it already holds --
--       app/cbl/COUSR00C.cbl L247 tests the page-number field and L251 issues the
--       top-of-list refusal -- so this fixture pins the leading key the page
--       publishes and never a backward-availability flag.
--
--       Refactoring Rationale: the short final page exists in this block because
--       the baseline leaves its last-key cursor stale, and this row set pins that
--       divergence. At app/cbl/COUSR00C.cbl L387-L389 the WHEN 1 branch is the
--       only writer of the first-key field, at L433-L435 the WHEN 10 branch is
--       the only writer of the last-key field, and L439-L440 make WHEN OTHER do
--       nothing -- so on a page of fewer than ten rows the last-key field keeps
--       whatever the previous turn left in it. The target declines parity and
--       sets lastKey from the actual last returned row; the COBOL is untouched
--       and the divergence stays registered in
--       docs/architecture/cobol-to-service-traceability.md.
--
--       Assumptions: the two empty-cursor sentinels are asymmetric and both have
--       to stay observable. app/cbl/COUSR00C.cbl L239-L243 and L262-L266 apply
--       the SAME test, = SPACES OR LOW-VALUES, but substitute DIFFERENT values:
--       LOW-VALUES reading backward at L240 and HIGH-VALUES reading forward at
--       L263. A single null sentinel in the target would mask that difference, so
--       the block deliberately makes both ends reachable -- KSET0001 as the
--       first-row boundary an empty backward cursor lands on, and KSET0021 as the
--       last-row boundary an empty forward cursor walks to.
--
--       Assumptions: PostgreSQL blank-pads CHAR on retrieval and does not pad
--       VARCHAR, so user_id and user_type come back padded and the mapper trims
--       them on read; the literals below are logical values. Every identifier is
--       exactly eight characters, so the cursor comparisons carry no padding
--       ambiguity -- a shorter key would be compared against its padded stored
--       form and could order differently.
--
--       Assumptions: U carries two unrelated meanings in the baseline and only
--       one belongs here -- user type at app/cpy/COCOM01Y.cpy L28, and the
--       list-screen selection value for update at app/cbl/COUSR00C.cbl L74 and
--       L189-L190. auth.users has no selection column, so every U below carries
--       the user-type meaning, and mixing the two would silently change what
--       every list assertion is measuring.
--
--       Assumptions: the KSET prefix and the b2 marker inside each cognito_sub
--       keep this file disjoint from users.sql, which reserves BASE and a1 for
--       itself. Disjointness is load-bearing because cognito_sub is NOT NULL
--       UNIQUE and V1__auth.sql seeds no rows, so one duplicated value rejects
--       the entire load. Trade-offs: BASE sorting ahead of KSET is a diagnostic
--       aid only, making a mis-load obvious in failure output; it is NOT the
--       isolation mechanism, which is the unqualified DELETE below.
--
--       Assumptions: no credential column exists to fill, because the target
--       declines parity with the legacy cleartext authenticator and Cognito
--       performs authentication. The reasoning and its four baseline sites are
--       recorded on com.carddemo.auth.dto.UserResponse rather than restated here.
-- =============================================================================

-- Alternatives Considered: TRUNCATE TABLE, rejected because its ACCESS EXCLUSIVE
-- lock buys nothing here - auth.users is the only table in the schema, so there is
-- no sequence to reset and no foreign key to cascade. A predicate-scoped removal of
-- only the KSET rows was rejected for a sharper reason: it would leave the sibling's
-- rows standing, and the cursor arithmetic in the outcome table above is valid ONLY
-- IF these twenty-one rows are the entire table content.
-- Trade-offs: removing every existing row makes users.sql and users-keyset.sql
-- mutually exclusive rather than composable. A test loads exactly one of them; they
-- do not stack, which a reader might otherwise assume. V1__auth.sql declares no seed
-- INSERT, so this can only ever affect fixture-created rows.
DELETE FROM auth.users;

-- Assumptions: naming all five columns makes a later schema addition fail loudly
-- instead of shifting a literal into a neighbouring positional column. These five
-- are the whole of auth.users exactly as V1__auth.sql declares it.
-- Alternatives Considered: twenty-one independent INSERT statements, rejected
-- because one statement is atomic, so a CHECK or UNIQUE violation leaves the table
-- exactly as the DELETE above found it. Twenty-one statements would stop part-way
-- and leave a partial volume block, which is worse than an empty table here: the
-- keyset queries would still succeed against a shorter block, every boundary in the
-- outcome table would silently move, and the failure would surface far from its
-- cause.
-- Alternatives Considered: deriving the rows from a series-generating expression,
-- rejected even though twenty-one rows is exactly the size at which it tempts. The
-- four keys the cursor arithmetic turns on are the entire point of this file, and a
-- generated bound would hide them behind an expression a reader has to evaluate
-- before knowing which keys exist. Trade-offs: writing every row out literally is
-- repetitive, and the repetition is accepted so each boundary key is visible in a
-- diff.
-- Assumptions: each cognito_sub is a synthetic RFC-4122 version-4-shaped subject -
-- the third group leads with 4 for the version and the fourth with 8 for the
-- variant. The b2 marker identifies this file, the trailing counter 01 through 21
-- keeps all twenty-one distinct under NOT NULL UNIQUE while remaining valid
-- hexadecimal, and the leading zero groups make every value unmistakably fabricated.
-- Assumptions: user_type is A where the counter leaves remainder one on division by
-- three and U otherwise, giving seven A rows and fourteen U rows. That rule was
-- chosen so every full ten-row window in the outcome table is mixed, which is what
-- makes an authority-filtered list assertion measure something; the deliberate
-- one-row short final page is necessarily single-typed.
-- Assumptions: the KeysetRow and Window name tokens are ASCII-only synthetic data,
-- stay inside VARCHAR(20), and cannot be mistaken for migrated people. The first
-- name carries the row ordinal so a mis-ordered page shows up in a diff, and the
-- last name names the cursor window a row belongs to so a row returned from the
-- wrong window is visible without counting rows.
INSERT INTO auth.users (user_id, first_name, last_name, user_type, cognito_sub) VALUES
    ('KSET0001', 'KeysetRow01', 'WindowFirstTen',  'A', '00000000-0000-4000-8000-00000000b201'),
    ('KSET0002', 'KeysetRow02', 'WindowFirstTen',  'U', '00000000-0000-4000-8000-00000000b202'),
    ('KSET0003', 'KeysetRow03', 'WindowFirstTen',  'U', '00000000-0000-4000-8000-00000000b203'),
    ('KSET0004', 'KeysetRow04', 'WindowFirstTen',  'A', '00000000-0000-4000-8000-00000000b204'),
    ('KSET0005', 'KeysetRow05', 'WindowFirstTen',  'U', '00000000-0000-4000-8000-00000000b205'),
    ('KSET0006', 'KeysetRow06', 'WindowFirstTen',  'U', '00000000-0000-4000-8000-00000000b206'),
    ('KSET0007', 'KeysetRow07', 'WindowFirstTen',  'A', '00000000-0000-4000-8000-00000000b207'),
    ('KSET0008', 'KeysetRow08', 'WindowFirstTen',  'U', '00000000-0000-4000-8000-00000000b208'),
    ('KSET0009', 'KeysetRow09', 'WindowFirstTen',  'U', '00000000-0000-4000-8000-00000000b209'),
    -- Assumptions: KSET0010 closes the first page of ten, so it is the lastKey a
    -- forward cursor carries into row two of the outcome table. It is also the
    -- position at which the baseline's WHEN 10 branch, at app/cbl/COUSR00C.cbl
    -- L433-L435, writes its last-key field - the write that never happens on a
    -- short page.
    ('KSET0010', 'KeysetRow10', 'WindowFirstTen',  'A', '00000000-0000-4000-8000-00000000b210'),
    -- Assumptions: KSET0011 exists to be found as the size-plus-one probe of the
    -- first page, which is what sets hasNext TRUE on row one. Supplied instead as a
    -- lastKey it reaches row three, the exact boundary where ten rows remain and the
    -- probe finds nothing, so hasNext must be FALSE on a FULL page - the case a
    -- block of twenty rows cannot produce while also producing row four.
    ('KSET0011', 'KeysetRow11', 'WindowSecondTen', 'U', '00000000-0000-4000-8000-00000000b211'),
    ('KSET0012', 'KeysetRow12', 'WindowSecondTen', 'U', '00000000-0000-4000-8000-00000000b212'),
    ('KSET0013', 'KeysetRow13', 'WindowSecondTen', 'A', '00000000-0000-4000-8000-00000000b213'),
    ('KSET0014', 'KeysetRow14', 'WindowSecondTen', 'U', '00000000-0000-4000-8000-00000000b214'),
    ('KSET0015', 'KeysetRow15', 'WindowSecondTen', 'U', '00000000-0000-4000-8000-00000000b215'),
    ('KSET0016', 'KeysetRow16', 'WindowSecondTen', 'A', '00000000-0000-4000-8000-00000000b216'),
    ('KSET0017', 'KeysetRow17', 'WindowSecondTen', 'U', '00000000-0000-4000-8000-00000000b217'),
    ('KSET0018', 'KeysetRow18', 'WindowSecondTen', 'U', '00000000-0000-4000-8000-00000000b218'),
    ('KSET0019', 'KeysetRow19', 'WindowSecondTen', 'A', '00000000-0000-4000-8000-00000000b219'),
    -- Assumptions: KSET0020 closes the second page of ten. Supplied as a lastKey it
    -- reaches row four, leaving exactly one row beyond it, which is the only way
    -- this block produces a page shorter than the page size.
    ('KSET0020', 'KeysetRow20', 'WindowSecondTen', 'U', '00000000-0000-4000-8000-00000000b220'),
    -- Refactoring Rationale: KSET0021 is the twenty-first row and the reason the
    -- block is not twenty. Alone it forms the short final page of row four, on which
    -- WHEN 10 never fires, so the baseline's last-key field still holds KSET0020 -
    -- the very cursor that produced this page, so it cannot describe the page it
    -- accompanies. The target instead sets lastKey to KSET0021, the actual last row
    -- returned. This row is also the probe that makes hasNext TRUE on row two, and
    -- the last-row boundary an empty forward cursor walks to under the HIGH-VALUES
    -- substitution.
    ('KSET0021', 'KeysetRow21', 'WindowTail',      'U', '00000000-0000-4000-8000-00000000b221');

--
-- Assumptions: the state below is deliberately absent from this fixture. No
--       cleartext authentication material and no derived verifier exists in any
--       column, literal or identifier, only the synthetic Cognito subject
--       reference. No lifecycle timestamp, audit state or deletion marker is part
--       of the five-column target. No application-managed and no engine-managed
--       row revision token exists. No positional paging metadata, ordinal or
--       index-derived pagination exists, and the display-only counters named above
--       never drive a query. No list-screen selection marker is persisted, so
--       neither selection value appears as data. No schema, role, privilege or
--       structure-changing statement appears, because
--       data-migration/sql/V0__schemas_and_roles.sql owns the schemas and roles and
--       V1__auth.sql owns this table. No binary and no EBCDIC content appears,
--       because a fixture has to be reviewable in a diff. And no floating-point and
--       no monetary value exists, there being none in this bounded context, with
--       the prohibition standing regardless.
