-- =============================================================================
-- services/auth-service/src/main/resources/db/migration/V6__auth_canonical_user_id.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Closes auth.users.user_id to the FULL canonical form the service derives --
--   upper-cased AND blank-trimmed -- rather than to the fold alone, reconciles
--   the rows an earlier defective create path may have left outside that form,
--   and validates both this constraint and V4's once no violating row remains.
--
--   V4__auth_folded_user_id.sql added ck_users_user_id_folded, which asserts
--   only user_id = upper(user_id). Its own WHY block at lines 111 to 118
--   records the omission plainly: "LEADING blanks are deliberately NOT
--   constrained here ... a leading blank survives upper() unchanged, so this
--   predicate admits it." That leaves a real state storable. UserService's key
--   derivation trims as well as folds -- it is submitted.trim().toUpperCase(ROOT)
--   -- so a row stored as ' BND0001' is reachable by NO request through that
--   service: the probe, the keyed read, the update and the delete all derive
--   'BND0001' and miss it, while a create of 'BND0001' does not collide with it
--   and produces a second row for what an operator reads as one identifier.
--
--   The claim this file exists to make true was already written down. The
--   Javadoc on UserService#canonicalKey asserted that the V4 guard made the folded
--   and trimmed form "unbreakable by any writer, including one that bypasses
--   this class". Half of that form was unguarded. This migration closes the
--   other half and the Javadoc is corrected in the same change, so the source
--   and the schema state the same rule.
--
-- Parameters:
--   A migration takes no arguments, so its inputs are the Flyway state and the
--   configuration it is applied under. All of them are declared in sibling
--   resources rather than here, identically to the four migrations before it:
--   spring.flyway.schemas and spring.flyway.default-schema pin the target
--   schema and the history table's home, spring.flyway.locations pins discovery
--   at classpath:db/migration, and the executing role is the schema's owner
--   because ALTER TABLE requires it.
--
--   - Applied-version state: the schema history table in schema auth. Version 5
--     is the identifier that decides whether this script runs; a history row
--     already recording it means this file is skipped. A database carrying only
--     V1 to V4 receives this script on the next start, which is the whole point
--     of it arriving as a fifth version rather than as an edit to V4.
--
-- Return values:
--   One reconciling statement, one schema object, one comment, and a
--   conditional validation of two constraints.
--
--   - Rows corrected: every row whose stored key differs from its canonical
--     form AND whose canonical form no other row already occupies is rewritten
--     to that canonical form.
--   - Check constraint ck_users_user_id_canonical on auth.users.user_id, carrying
--     TWO terms: the fold-and-trim comparison, and a membership test that holds
--     the key to a deterministic character domain. The second term is why this
--     constraint refuses an interior blank and a control character, neither of
--     which the first term can see.
--   - A comment on that constraint, so a writer that trips it is told what to
--     do rather than only which name refused it.
--   - ck_users_user_id_canonical and ck_users_user_id_folded are VALIDATED when
--     no violating row remains, and left NOT VALID with a warning when one does.
--     The two guards are complementary and neither subsumes the other; the WHY
--     block above the constraint records which shape each one alone refuses.
--
--   Deliberately absent: no column is added, altered, widened or dropped; no
--   index is created; no row is deleted; no privilege changes; and V4's
--   constraint is NOT dropped -- see the WHY block below for why the narrower
--   one is kept beside this one.
--
-- Exceptions or errors:
--   1. A collision this script cannot resolve. The characteristic outcome of
--      the defect is TWO rows for one identifier -- one canonical and one not,
--      or two non-canonical rows folding onto one value -- and rewriting either
--      of them violates the primary key. Which of two user records survives is
--      a decision about a person's access that belongs to an operator with the
--      audit trail, not to a migration running unattended at start-up, so this
--      script leaves every colliding row exactly as it found it, emits a
--      WARNING naming how many remain, and leaves both constraints NOT VALID.
--      The service starts and serves; the rows stay unreachable, as they
--      already were. An operator lists them with the query below, which flags
--      WHICH term each row violates so a fold drift, a whitespace drift and a
--      character outside the domain are told apart rather than counted together:
--          SELECT u.user_id AS stored,
--                 upper(btrim(u.user_id)) AS canonical,
--                 u.user_id <> upper(u.user_id)  AS unfolded,
--                 u.user_id <> btrim(u.user_id)  AS blank_padded,
--                 btrim(u.user_id, DOMAIN_SET) <> ''  AS outside_domain,
--                 u.first_name, u.last_name, u.user_type, u.cognito_sub
--            FROM auth.users u
--           WHERE u.user_id <> upper(btrim(u.user_id))
--              OR btrim(u.user_id, DOMAIN_SET) <> ''
--           ORDER BY canonical, stored;
--      where DOMAIN_SET is the 94-character literal the constraint below
--      carries. A row the query reports with outside_domain true has NO
--      canonical form to be moved to, because the service can never derive that
--      key from any request; it is not a collision and must not be rewritten.
--      Resolve it as a business decision instead: identify the person from
--      first_name, last_name and cognito_sub, create the row under an identifier
--      the domain admits, and withdraw the old row and its pool account.
--
--      For every other reported row, compare the competing
--      rows' names, type and cognito_sub against the identity pool, deletes the
--      row whose pool account is to be withdrawn, and withdraws that account --
--      the provider side is in docs/runbooks/data-migration.md. Once the list is
--      list is empty, promote both constraints so a later reader is not misled by a
--      constraint marked NOT VALID:
--          ALTER TABLE auth.users VALIDATE CONSTRAINT ck_users_user_id_canonical;
--          ALTER TABLE auth.users VALIDATE CONSTRAINT ck_users_user_id_folded;
--   2. Insufficient privilege. ALTER TABLE and UPDATE require the table's
--      owner, which is the role data-migration/sql/V0__schemas_and_roles.sql
--      establishes for this schema and the role each per-service migration is
--      expected to run as. Applying this script as any other role fails naming
--      the table.
--   3. Checksum immutability. V4's own header records the same rule it followed
--      for V1: a correction arrives as a new versioned migration and never as
--      an edit, because Flyway refuses a script whose checksum changed after it
--      was applied. This file is that correction. V4 is left byte-identical,
--      and the sentence in it that leaves leading blanks unconstrained was true
--      when written and is superseded here rather than edited there.
--   4. No destructive path exists. clean is disabled by the profile overlay,
--      and this script contains no clean, no drop and no truncate. The one
--      statement that writes rows rewrites a key and deletes nothing.
--
-- Provenance:
--   Every app/** path cited in this file is REFERENCE-ONLY and is never
--   modified by this migration or by anything else in the migrated trees.
--
--   Primary sources: SEC-USR-ID is PIC X(08) at app/cpy/CSUSR01Y.cpy:18, which
--   is where the width comes from; app/cbl/COSGN00C.cbl:211-256 is the sign-on
--   comparison whose folded lookup is why an unfolded row is unreachable. The
--   target-side single definition of the canonical form is
--   services/auth-service/src/main/java/com/carddemo/auth/service/UserService.java,
--   whose canonicalKey both derives it and refuses a submitted value that cannot
--   become one. That method carried the name foldedKey in one earlier draft of
--   this correction; the surviving name is canonicalKey, because the derivation
--   trims and screens characters as well as folding, and a name saying only
--   "folded" is the same half-statement of the rule this migration exists to
--   finish correcting.
--
--   Documentation convention: docs/CODE_DOCUMENTATION_STANDARD.md, whose SQL
--   section requires this header block plus a rationale on each non-obvious
--   constraint, and records that nothing in the build inspects a migration's
--   comments.
-- Why the domain term is a btrim membership test rather than a regular expression
--   Assumptions: the term is btrim(user_id, <printable set>) = '', which is
--   empty exactly when every character of the value is in the set, and it is
--   chosen for DETERMINISM. A bracket range in a PostgreSQL regular expression
--   -- '[\x21-\x7E]', '[[:graph:]]' -- is interpreted through the database''s
--   collation, so the same predicate can admit different characters on two
--   databases and the guard would then mean something different from the
--   service''s pattern depending on where it ran. btrim compares characters
--   against an explicit set and consults no collation at all.
--   Assumptions: the set is the 94 printable code points 0x21 through 0x7E,
--   with the SPACE excluded. It is written out in full rather than generated,
--   because a generated range would reintroduce the collation dependence the
--   test exists to avoid.
--   Assumptions: the set includes the lower-case letters even though the fold
--   term refuses them, so that this term expresses the same SET as the
--   service''s pattern character for character. Ordering the two terms the other
--   way -- a set of upper-case characters only -- would make the SQL domain
--   narrower than the Java one and the two definitions would no longer be
--   comparable by reading them.
--
-- Why the space is excluded
--   Assumptions: the reference itself cannot carry an identifier containing a
--   blank. Three programs render it with STRING ... DELIMITED BY SPACE --
--   app/cbl/COUSR01C.cbl L256, app/cbl/COUSR02C.cbl L373 and
--   app/cbl/COUSR03C.cbl L319 -- so the confirmation a user reads names the
--   identifier truncated at its first blank, which is a different identifier
--   from the one stored. Excluding the space is therefore closer to the
--   reference''s own behaviour than admitting it, and it is what makes trim
--   sufficient rather than one case of a general whitespace rule.
--   Trade-offs: the narrowing is registered as D-USER-ID-CANONICAL-DOMAIN in
--   docs/architecture/cobol-to-service-traceability.md. The reference validates
--   the identifier''s characters nowhere, so this guard refuses values it would
--   have stored. Every one of the ten identifiers in the committed extract
--   app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS draws on [A-Z0-9] alone and is
--   admitted unchanged, so the parity oracle is unaffected.
--
--
-- =============================================================================

-- WHY : Refactoring Rationale: the reconciliation is attempted HERE and was
--       left entirely to an operator by V4, and the difference is which rows
--       stay broken. V4 declined to correct anything because the fold can
--       COLLIDE, and that reasoning is sound for the colliding rows and wrong
--       for every other one: a row whose canonical form no other row occupies
--       is corrected by a single deterministic rewrite with no decision to
--       make, and leaving it uncorrected leaves a row NO request can reach.
--       This statement therefore splits the set the way the risk actually
--       divides -- correct what is unambiguous, and leave what is not to the
--       operator step in the header.
-- WHY : Assumptions: the predicate excludes a row whose canonical form ANY
--       OTHER row would also hold, which covers both collision shapes in one
--       term. The obvious shape is a canonical row already occupying the
--       target key; the shape a "NOT EXISTS (target key)" test would MISS is
--       two non-canonical rows -- ' abc' and 'abc' -- neither of which
--       occupies 'ABC' beforehand and both of which would be rewritten to it,
--       failing the primary key and aborting the migration. Comparing the
--       canonical forms of the two rows catches both.
-- WHY : Assumptions: the comparison is written against the column directly
--       even though it is CHAR(8) and therefore blank-padded in storage.
--       Converting a fixed-width value to the type btrim() and upper() take
--       strips its trailing blanks, and the comparison that follows strips
--       them from the left side too, so a padded key compares on its
--       significant characters alone. V4's own WHY block records the same
--       behaviour verified against the engine: 'ABC     ' satisfies the fold
--       predicate while 'abc     ' does not.
-- WHY : Assumptions: auth.identity_sync_task.user_id is deliberately NOT
--       rewritten alongside the row. That column names the PROVIDER username a
--       pending change is owed against, and rewriting the row's key does not
--       rename the pool account -- so a pending withdrawal or projection for
--       ' bnd0001' still has to be issued under the spelling the pool holds.
--       Rewriting it would make the applier call the provider with a username
--       that does not exist there, turning a convergent task into one that can
--       never succeed. The provider-side rename is the operator step the
--       runbook cited above owns.
-- WHY : Trade-offs: a corrected row becomes reachable through this service
--       while its pool account still carries the unfolded username, so the
--       user still cannot sign on until the provider side is renamed. That is
--       accepted because it is strictly better than what it replaces: before
--       this statement the row was reachable by NOTHING -- not the service and
--       not sign-on -- and no working path is broken by it, since no sign-on
--       could ever have resolved a row this rewrite touches.
UPDATE auth.users AS u
   SET user_id = upper(btrim(u.user_id))
 WHERE u.user_id <> upper(btrim(u.user_id))
   AND NOT EXISTS (
       SELECT 1
         FROM auth.users AS other
        WHERE other.user_id <> u.user_id
          AND upper(btrim(other.user_id)) = upper(btrim(u.user_id)));

-- WHY : Assumptions: the predicate is the WHOLE canonical form -- the fold and
--       the trim together -- because that is what UserService#canonicalKey
--       derives and therefore what every keyed read, probe, update and delete
--       in this context asks the table for. A guard that asserts less than the
--       derivation admits rows the derivation can never name.
-- WHY : Assumptions: btrim() and not rtrim(). A TRAILING blank is storage
--       padding that the type itself adds and that the comparison strips on
--       both sides, so it is not the property at issue; a LEADING blank is
--       part of the value, survives upper() unchanged, and is exactly what V4
--       left admitted. btrim() removes both, which makes the predicate agree
--       with String#trim on the Java side rather than merely resemble it.
-- WHY : Alternatives Considered: dropping ck_users_user_id_folded on the ground
--       that this constraint subsumes it. Rejected, and the reason is stronger
--       than the readability one first offered for keeping it: this constraint
--       does NOT subsume that one, measured against the engine on the deployed
--       pair. An ALL-BLANK key satisfies BOTH terms here -- on a CHAR(8) column
--       it converts to the empty string, upper(btrim('')) is also the empty
--       string, and btrim('', <set>) is empty too -- so this constraint ADMITS
--       it. ck_users_user_id_folded carries an explicit btrim(...) <> '' term
--       and is the only guard that refuses it. Dropping it would therefore open
--       a row addressable by no caller at all, which is the very defect class
--       this migration exists to close.
-- WHY : Assumptions: the two guards are COMPLEMENTARY rather than nested, and
--       that is why each keeps its own name. Every key both can see, they agree
--       on; each additionally refuses one shape the other admits -- this one the
--       character outside the domain, that one the all-blank key -- so a refusal
--       naming either is a refusal naming the term that actually caught the row.
--       Verified against the engine on the applied pair: ' USER001' and
--       'lower001' violate both; 'CAN ON5', E'CAN\tON6' and 'ANON7' with a
--       non-invariant leading letter violate only this one; eight blanks violate
--       only the folded one.
-- WHY : Trade-offs: two names for one column means a writer that trips a guard
--       must read whichever name it was given, and the alternative -- folding the
--       non-blank term into this constraint so one name covered everything --
--       was declined because it would make the two predicates overlap completely
--       and leave the engine free to report either name for any violation. A
--       refusal that can name either guard tells the operator less than a
--       refusal whose name identifies the term that caught it.
-- WHY : Alternatives Considered: a BEFORE INSERT OR UPDATE trigger that
--       canonicalised the value silently instead of refusing it. Rejected for
--       the reason V4 gives for the same idea: a writer whose key is quietly
--       rewritten cannot tell that the row it reads back is not the row it
--       wrote, and it would make the database a second place where the
--       canonical form is defined -- the duplicate definition that caused this
--       defect in the first place.
-- WHY : Trade-offs: the constraint is added NOT VALID and validated below only
--       when nothing violates it. Adding it VALIDATED would scan the table and
--       FAIL on precisely the databases that still hold a colliding row, and a
--       failed migration stops the service from starting at all -- replacing a
--       data defect a query can find with an outage, on the databases most
--       likely to be affected. NOT VALID skips only the initial scan: every
--       subsequent INSERT and every UPDATE of an existing row is checked, so
--       the invariant holds from here forward either way.
-- WHY : Refactoring Rationale: one earlier draft of this correction DROPPED
--       ck_users_user_id_folded here, on the ground that the new predicate
--       implies it and that two names for one invariant make a refusal harder to
--       read. That drop is WITHDRAWN, and the premise it rested on is not merely
--       outweighed but false: the WHY block below records the measurement, and
--       an all-blank key is a value this constraint admits and that one refuses.
--       Carrying out the drop would have removed the only guard against it.
--       What the drop was really objecting to survives and
--       is answered -- V4's COMMENT described the key as "blank-trimmed" while
--       enforcing no such thing, and that false description is corrected where
--       it stands, by V5__auth_folded_user_id_trim.sql widening V4's predicate
--       to match its own comment rather than by deleting the constraint that
--       carried it.
-- WHY : Assumptions: the first term compares the column against
--   upper(btrim(...)) -- the service''s derivation character for character --
--   rather than against upper(...) alone. The comparison is written against the
--   column directly even though it is CHAR(8) and therefore blank-padded in
--   storage: converting a fixed-width value to the type these functions take
--   strips its TRAILING blanks, and the comparison strips them from the left
--   side too, so a padded key compares on its significant characters alone.
--   Verified against the engine: 'ABC     ' and '00000001' satisfy the predicate
--   while 'abc     ', 'AbC00001', ' ABC' and '  ABC' do not.
-- WHY : Assumptions: the second term is the domain membership test argued in the
--   header. It is what refuses ' ABC' -- the value V4''s predicate admitted --
--   and it also refuses an interior blank, a control character and any character
--   outside the invariant set, none of which the first term can see.
--   Verified against the engine: 'AB CD', E'AB\tCD' and 'ÄBC' satisfy the fold
--   term and fail this one.
ALTER TABLE auth.users
    ADD CONSTRAINT ck_users_user_id_canonical
    CHECK (
        user_id = upper(btrim(user_id))
        AND btrim(user_id, '!"#$%&''()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\]^_`abcdefghijklmnopqrstuvwxyz{|}~') = ''
    )
    NOT VALID;

-- WHY : Assumptions: the comment is written in the same migration that adds the
--       constraint, because the constraint alone does not tell an operator who
--       trips it what to do. A refusal on INSERT reports a constraint name;
--       this is where that name explains itself, and it names the service
--       method that derives the key so the two definitions cannot drift apart
--       unnoticed.
COMMENT ON CONSTRAINT ck_users_user_id_canonical ON auth.users IS
    'user_id is stored CANONICAL: upper-case, blank-trimmed on BOTH ends, and '
    'drawn only from the 94 printable invariant characters excluding the space '
    '-- the exact spelling UserService#canonicalKey derives before every probe, '
    'provider call, insert and keyed lookup, and refuses a submitted value for '
    'when the derived form is empty, wider than eight characters, or outside '
    'that character set. A refusal here means a writer supplied a non-canonical '
    'key; canonicalise it rather than relaxing the constraint, and if it has no '
    'canonical form the row was never addressable -- see the reconciliation '
    'statement above, the conditional VALIDATE below, and the collision '
    'procedure in the header of V6__auth_canonical_user_id.sql. This does NOT '
    'subsume ck_users_user_id_folded and does not replace it: that guard carries '
    'a non-blank term this one lacks, so an all-blank key satisfies this '
    'constraint and only that one refuses it. The two are complementary and both '
    'are enforced. Added NOT VALID '
    'because databases on which the earlier create path or a bypass writer ran '
    'may hold non-canonical rows.';

-- WHY : Refactoring Rationale: validation is CONDITIONAL and in the migration,
--       where V4 left it as a manual step for an operator to remember. Its
--       reasoning was that VALIDATE "cannot be made to succeed unattended" --
--       true only of the databases that still hold a violating row. Every
--       other database, including every fresh one, was left carrying two
--       constraints marked NOT VALID for ever, which tells a later reader the
--       invariant is unproven when in fact nothing has ever violated it. This
--       block promotes both constraints exactly when that is provable and
--       leaves them alone when it is not, so the marker means what it says.
-- WHY : Assumptions: V4's constraint is validated here too, and not only this
--       one. It has been enforcing itself on every write since V4 was applied
--       and this migration has just reconciled what it could of the rows that
--       predate it, so the same evidence settles both; leaving one validated
--       and the other not would suggest they were checked against different
--       data.
-- WHY : Assumptions: the residue is reported as a WARNING and not raised. An
--       exception here would abort the migration and stop the service from
--       starting, which is the outage the NOT VALID choice above exists to
--       avoid; a warning reaches the deployment log, where the operator
--       procedure in this file's header is what acts on it. Alternatives
--       Considered: writing the residue to a table so a monitor could alarm on
--       it. Rejected because it would add a schema object whose only reader is
--       an operator who is already reading this warning, on a condition that is
--       absent from every database the defective path never ran on.
-- WHY : Assumptions: the count is taken inside the block rather than the
--       validation being attempted and its failure caught, because a failed
--       VALIDATE inside a plpgsql block would still have to be handled and
--       would leave the transaction needing a savepoint, while the count is one
--       sequential scan of a table that holds one row per operator.
DO $$
DECLARE
    unreconciled bigint;
BEGIN
    -- WHY : Assumptions: the count weighs BOTH terms of the constraint being
    --       promoted, not the fold-and-trim term alone. A row carrying an
    --       interior blank satisfies the first term and violates the second, so
    --       counting the first alone would report zero residue and then attempt
    --       a VALIDATE that the second term fails -- turning the unattended
    --       promotion this block exists to make safe back into the outage the
    --       NOT VALID choice above avoids.
    SELECT count(*) INTO unreconciled
      FROM auth.users
     WHERE user_id <> upper(btrim(user_id))
        OR btrim(user_id, '!"#$%&''()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\]^_`abcdefghijklmnopqrstuvwxyz{|}~') <> '';

    IF unreconciled = 0 THEN
        ALTER TABLE auth.users VALIDATE CONSTRAINT ck_users_user_id_canonical;
        ALTER TABLE auth.users VALIDATE CONSTRAINT ck_users_user_id_folded;
    ELSE
        RAISE WARNING 'auth.users holds % row(s) whose key is not canonical -- '
                      'either the canonical form is occupied by another row, or '
                      'the key holds a character the domain term refuses and so '
                      'has no canonical form at all; '
                      'ck_users_user_id_canonical and ck_users_user_id_folded '
                      'remain NOT VALID. Resolve the collisions using the '
                      'procedure in V6__auth_canonical_user_id.sql, then run '
                      'ALTER TABLE auth.users VALIDATE CONSTRAINT for both.',
                      unreconciled;
    END IF;
END
$$;
